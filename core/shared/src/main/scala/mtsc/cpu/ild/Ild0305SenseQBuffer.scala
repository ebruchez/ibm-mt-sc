package mtsc.cpu.ild

/**
 * Gate- and latch-level implementation of **ILD Page `0305`** (*Power, Start, Sense Amplifiers,
 * Read Inhibit & Q-Buffer*), corresponding to ALD sheets `[AA041]`, `[AA051]`, `[AA052]`,
 * `[AA061]`, `[AA082]`, `[AA083]`, `[AA101]`, `[AA121]`, `[AA151]`, `[AA241]`, `[AA261]`, and `[AA262]`.
 */
object Ild0305SenseQBuffer {

  /**
   * Evaluates all combinational signals on **ILD `0305`**:
   * `INHIBIT MEMORY`, `TRANS + COMP`, `INHIBIT READ`, `killCsYUpperByte`, `REWO`/`ROWE` gates,
   * `READ GATE`, `RES BFR`, `LD Q BFR 1`, and `LD Q BFR 2`.
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    // [AA151 3G-AK] +START latch: sets on Power On & BEG P.S. & -CLR; resets on RES INDS & -STR BT CTR A & -PWR ON
    if (s.resInds && !s.strBtCtrA && !s.pwrOn) {
      s.start = false
    } else if (s.pwrOn && s.begPrgStp && !s.clrButtonPressed) {
      s.start = true
    }

    // [AA052 5M-AF -> 6M-AN] +INHIBIT MEMORY = -INSN T CTR A & -P TIME
    // Inhibits Core Memory current sources during Write Q of even instruction times (I-0, I-2, I-4, I-6, I-8).
    s.inhibitMemory = !s.insnTCtrA && !s.pTime

    // [AA041] 16-bit Direct/Immediate Transfer (MOV / MOVI) and Transfer-or-Compare (TRANS + COMP)
    // Note: For 16-bit Direct/Immediate, Transfer (11) & Compare (01) have oplCde4 = 1 (whereas Add = 10 & Sub = 00 have oplCde4 = 0).
    // For Byte Indirect (!immediate && (oplCde6 || oplCde7)), all instructions are Transfer (MOVB) or Compare (CMPB).
    s.trans16 = s.oplCde2 && s.oplCde3 && s.oplCde4 && !s.oplCde6 && !s.oplCde7
    s.transOrComp = s.oplCde2 && (s.oplCde4 || (!s.immediate && (s.oplCde6 || s.oplCde7)))

    // [AA061 / AA101] +INHIBIT READ (INH RD): suppresses Sense Amplifier outputs during destructive clear reads
    val inhRdI1Q       = s.insnT1 && !s.pTime
    val inhRdI5Input   = !s.output && !s.athOp && s.insnT5
    val inhRdI9JmpP    = s.jmp && s.insnT9 && s.pTime
    val inhRdClrDply1  = s.dply1 && s.clrButtonPressed && s.insnT0
    val inhRdClrDply2  = s.dply2 && s.clrButtonPressed && s.insnT1
    val inhRdI9MovP    = ((!s.btCtrB && s.transOrComp && s.oplCde3) || s.trans16) && s.insnT9 && s.pTime
    val inhRdI0B13_16  = s.insnT0 && s.b13_16

    s.inhRd =
      inhRdI1Q ||
      inhRdI5Input ||
      inhRdI9JmpP ||
      inhRdClrDply1 ||
      inhRdClrDply2 ||
      inhRdI9MovP ||
      inhRdI0B13_16 ||
      s.masterReset

    // [AA082 1C-AE, 1D-AG] Upper-byte CS Y LOG suppression during B9-12 & B13-16 (btCtrB = true):
    // - 1C-AE: suppresses Q-Read of A-Word high byte in I-9 of Immediate Arithmetic & Incremental Jump (oplCde1)
    // - 1D-AG: suppresses B9-12/B13-16 in single-byte upper-memory operations (I-5 and I-9 when selMemUp && !jmp && !insnT1)
    s.killCsYUpperByte =
      s.btCtrB && ((!s.pTime && s.insnT9 && s.oplCde1) || (!s.insnT1 && !s.jmp && s.selMemUp))

    // [AA121] +READ EVEN WRITE ODD (REWO) and +READ ODD WRITE EVEN (ROWE)
    s.rewoGate = (!s.btCtrA && !s.twr) || (s.btCtrA && s.twr)
    s.roweGate = (s.btCtrA && !s.twr) || (!s.btCtrA && s.twr)

    // [AA241] +READ GATE, +REWO GATE, +ROWE GATE
    s.readGate = s.csYLog && !s.twr
    s.rewoSenseGate = s.rewoGate && s.readGate
    s.roweSenseGate = s.roweGate && s.readGate

    // [AA083 AJ4] -RESET BUFFER (stored as positive `resBfr`)
    s.resBfr = (!s.ssa && !s.b && !s.twr && s.pTime) || s.masterReset

    // [AA101 2B-AA..3C-AF] +LOAD Q BUFFER 1 (adds +1 at B1-4, BMP STRB T):
    // 1. Indirect/Multiplex Output pointer bump (+1 at I-3 when Ready)
    // 2. CE Edit mode address step (+1 byte at I-0)
    // 3. Byte Indirect auto-bump (+1 at I-3 for [Rq]++ and I-7 for [Rp]++)
    val bumpOutI3    = s.output && s.rdy && s.insnT3
    val bumpEditI0   = s.edit && !s.inh && s.insnT0
    val bumpIndI3I7  = s.oplCde2 && s.oplCde4 && s.insnTCtrB && (s.strInsnTCtrA || s.insnTCtrA)
    s.ldQBfr1 = s.b1_4 && s.bmpStrbT && (bumpOutI3 || bumpEditI0 || bumpIndI3I7)

    // [AA101 2G-AS..4H-AY] +LOAD Q BUFFER 2 (adds +2 at B1-4, BMP STRB T):
    // 1. Normal Program Step PSAR + 2 bump at I-0 (when !inh && !edit)
    // 2. Jump with Link (JMPL) target address A-Word + 2 bump at I-2
    val bumpPsarI0   = !s.inh && !s.edit && s.insnT0
    val bumpJmplI2   = s.jmp && s.insnT2
    s.ldQBfr2 = s.masterReset || (s.b1_4 && s.bmpStrbT && (bumpPsarI0 || bumpJmplI2))
  }

  /**
   * Updates `SENSE AMP 1..4` and `Q BUFFER 1..4` (`[AA261]`, `[AA262]`) from a 4-bit raw sense nibble
   * (`0..15`) read from [[CoreMatrix3D]] and/or the `RES BFR`, `LD Q BFR 1`, `LD Q BFR 2`, and
   * `PSAR` Bit-12 clamp signals.
   */
  def updateQBuffer(s: BackplaneSignals, rawSenseNibble: Int = 0): Unit = {
    evaluateCombinational(s)

    // [AA261 / AA262] Sense Amplifiers 1..4 are gated by !inhRd
    if (s.readGate && !s.inhRd) {
      s.senseAmp1 = (rawSenseNibble & 1) != 0
      s.senseAmp2 = (rawSenseNibble & 2) != 0
      s.senseAmp3 = (rawSenseNibble & 4) != 0
      s.senseAmp4 = (rawSenseNibble & 8) != 0
    } else {
      s.senseAmp1 = false
      s.senseAmp2 = false
      s.senseAmp3 = false
      s.senseAmp4 = false
    }

    // [AA261 / AA262] Reset Q BFR 1..4 when RES BFR is active
    if (s.resBfr) {
      s.qBfr1 = false
      s.qBfr2 = false
      s.qBfr3 = false
      s.qBfr4 = false
    }

    // [AA261 / AA262] Set Q BFR 1..4 from Sense Amplifiers or LD Q BFR 1 / LD Q BFR 2
    if (s.senseAmp1 || s.ldQBfr1) s.qBfr1 = true
    if (s.senseAmp2 || s.ldQBfr2) s.qBfr2 = true
    if (s.senseAmp3)              s.qBfr3 = true
    if (s.senseAmp4)              s.qBfr4 = true

    // [AA051 / AA262 7H-AG] -*Q BUFFER 4 clamp: forces +Q BUFFER 4 low at I-0, B9-12, -B & -TWR
    // (clears bit 12 / 0-based bit 11 of the 11-bit PSAR)
    if (s.b9_12 && s.insnT0 && !s.b && !s.twr) {
      s.qBfr4 = false
    }
  }
}
