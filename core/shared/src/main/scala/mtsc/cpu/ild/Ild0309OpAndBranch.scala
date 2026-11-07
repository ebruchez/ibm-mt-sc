package mtsc.cpu.ild

/**
 * Gate- and latch-level implementation of **ILD Page `0309`** (*Operational Code Latches,
 * Bit Sampling & Condition Latches `LOW` / `EQUAL`*), corresponding to ALD sheets `[AA012]`,
 * `[AA032]`, `[AA042]`, `[AA051]`, `[AA061]`, `[AA072]`, `[AA081]`, `[AA082]`, `[AA092]`,
 * `[AA101]`, `[AA131]`, and `[AA132]`.
 */
object Ild0309OpAndBranch {

  /**
   * Evaluates combinational signals on **ILD `0309`**:
   * `LOAD OPL CDE`, `SAM BT`, `SAM B POS`, and `RES LO & EQ`.
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    // [AA101] +LOAD OPERATIONAL CODE = (-MPX | -RDY) & CEN BT & INSN T 1
    s.loadOplCde = (!s.mpx || !s.rdy) && s.cenBt && s.insnT1

    // [AA051 AE4] +SAMPLED BIT TIME: matches (Y11, Y10) against (BT CTR B, BT CTR A)
    // during Program Control (0011) or Incremental Jump (10)
    s.samBt = (s.prgCtl || s.oplCde1) && (s.y11 == s.btCtrB) && (s.y10 == s.btCtrA)

    // [AA012 AZ4 / AA014 AX4, AY4] +SAMPLED BIT POSITION:
    // True during SAM BT when the accumulator bit selected by (Y9, Y8) is 0 (off)
    val sampledAccBit =
      (!s.y8 && !s.y9 && s.acc1) ||
      ( s.y8 && !s.y9 && s.acc2) ||
      (!s.y8 &&  s.y9 && s.acc3) ||
      ( s.y8 &&  s.y9 && s.acc4)
    s.samBPos = s.samBt && !sampledAccBit

    // [AA132 2G-AH -> 3G-AK] -RESET LOW AND EQUAL (stored as positive `resLoAndEq`):
    // Presets EQ = true and resets LOW = false during Write (-B & TWR) of even instruction times
    // I-2, I-4, I-6, I-8 for non-Output, non-Jump instructions.
    s.resLoAndEq = !s.b && s.twr && !s.insnTCtrA && !s.output && !s.jmp && !s.insnT0
  }

  /**
   * Updates the 7 Operational Code Latches (`OPL CDE 1..7` on `[AA032]`).
   */
  def updateOpCodeLatches(s: BackplaneSignals): Unit = {
    evaluateCombinational(s)

    // [AA082 / AA032] Reset OPL CDE 1..7 on MASTER RESET or RES INDS when entering I-0, B1-4
    val resetAtBegPs =
      s.resInds &&
      !s.strBtCtrA && !s.strBtCtrB &&
      !s.strInsnTCtrA && !s.strInsnTCtrB && !s.strInsnTCtrC && !s.strInsnTCtrD

    var changed = false
    if (s.masterReset || resetAtBegPs) {
      s.oplCde1 = false
      s.oplCde2 = false
      s.oplCde3 = false
      s.oplCde4 = false
      s.oplCde5 = false
      s.oplCde6 = false
      s.oplCde7 = false
      changed = true
    }

    // [AA032] Load OPL CDE 1..7 from P BUFFER 1..4 during I-1, CEN BT
    if (s.loadOplCde) {
      if (s.b1_4 && s.pBfr1)  { s.oplCde7 = true; changed = true }
      if (s.b5_8 && s.pBfr3)  { s.oplCde6 = true; changed = true }
      if (s.b9_12 && s.pBfr4) { s.oplCde5 = true; changed = true }
      if (s.b13_16) {
        if (s.pBfr1) { s.oplCde4 = true; changed = true }
        if (s.pBfr2) { s.oplCde3 = true; changed = true }
        if (s.pBfr3) { s.oplCde2 = true; changed = true }
        if (s.pBfr4) { s.oplCde1 = true; changed = true }
      }
    }

    // [AA032 / AA142] Multiplex Interrupt override at I-1, B13-16, -B & TWR:
    // Forces OPL CDE 4 = 1 and OPL CDE 6 = 1 (Indirect Output: 00010...1...0)
    if (s.mpx && s.rdy && s.insnT1 && s.b13_16 && !s.b && s.twr) {
      s.oplCde4 = true
      s.oplCde6 = true
      changed = true
    }

    if (changed) {
      Ild0304Permute.evaluateCombinational(s)
    }
  }

  /**
   * Updates the `+LOW LATCH` (`[AA132 4B-AD]`) and `+EQUAL LATCH` (`[AA132 4L-AP]`).
   */
  def updateConditionLatches(s: BackplaneSignals): Unit = {
    if (s.masterReset) {
      s.low = false
      s.eq = false
      Ild0304Permute.evaluateCombinational(s)
      return
    }

    val prevLow = s.low
    val prevEq = s.eq
    evaluateCombinational(s)

    // [AA132 2G-AH -> 3G-AK] Preset EQ = true, LOW = false at I-2, I-4, I-6, I-8
    if (s.resLoAndEq) {
      s.low = false
      s.eq = true
    }

    // [AA132 2C-PA, 2D-AE, 2E-AF -> 3B-AC] Set conditions for +LOW LATCH:
    // 1. 2C-PA: Tape parity error / skip (SET LO) during I-5 Input
    val setLowInputError = s.setLo && !s.output && s.insnT5 && (s.cenBt || s.strLthStrobe) && !s.athOp
    // 2. 2D-AE: Sign bit (ACC 4) on at I-9, B13-16, STR LTH STROBE of 16-bit Arithmetic / CMPB
    val isSingleByteMovb = s.oplCde2 && s.oplCde3 && (s.oplCde6 || s.oplCde7) && !s.immediate
    val setLowArithI9 = s.insnT9 && s.oplCde2 && s.acc4 && s.b13_16 && s.strLthStrobe && !isSingleByteMovb
    // 3. 2E-AF: Sampled bit is 0 (SAM B POS) at I-3, Write P (P TIME & TWR) of Program Control / Inc Jump
    val setLowSampledBit0 = s.pTime && s.insnT3 && s.twr && s.samBPos

    if (setLowInputError || setLowArithI9 || setLowSampledBit0) {
      s.low = true
    }

    // [AA132 3M-AQ / AA072 2L-AQ] Reset conditions for +EQUAL LATCH:
    // 1. Whenever +LOW LATCH is set, +EQUAL LATCH is reset
    // 2. At I-9, STR LTH STROBE of Arithmetic (OPL CDE 2), if any bit of ACC 1..4 is 1
    // 3. During I-5 Input (!edit & input & insnT5), if any bit of ACC 1..4 is 1
    val ignoreMovbUpperByte = isSingleByteMovb && s.btCtrB
    val resEqArithI9 = s.strLthStrobe && s.oplCde2 && s.insnT9 && !s.accEqualZero && !ignoreMovbUpperByte
    val resEqInputI5 = !s.edit && s.input && s.insnT5 && (s.cenBt || s.strLthStrobe) && !s.accEqualZero

    if (s.low || resEqArithI9 || resEqInputI5) {
      s.eq = false
    }

    if (s.low != prevLow || s.eq != prevEq) {
      Ild0304Permute.evaluateCombinational(s)
    }
  }
}
