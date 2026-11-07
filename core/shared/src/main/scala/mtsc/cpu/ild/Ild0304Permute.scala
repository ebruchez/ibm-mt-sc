package mtsc.cpu.ild

/**
 * Gate- and latch-level implementation of **ILD Page `0304`** (*Permute Logic & Instruction Time Counter*),
 * corresponding to ALD sheets `[AA041]`, `[AA061]`, `[AA091]`, `[AA092]`, and `[AA131]`.
 */
object Ild0304Permute {

  /**
   * Evaluates all combinational instruction-time decodes (`INSN T 0..9`), opcode class decodes
   * (`ATH OP`, `IMMEDIATE`, `OUTPUT`, `INPUT`, `PRG CTL`, `JMP`), branch condition logic (`TAKEN`),
   * and permute control lines (`SET 9`, `PRMT`, `PRMT INSN T STR LTH`, `RES INSN T STR LTH`, `P ONLY`).
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    // ---------------------------------------------------------------------------------------------
    // [AA092] Instruction Time Counter Decodes (BCD 8-4-2-1: A=1, B=2, C=4, D=8)
    // ---------------------------------------------------------------------------------------------
    val a = s.insnTCtrA
    val b = s.insnTCtrB
    val c = s.insnTCtrC
    val d = s.insnTCtrD

    s.insnT0 = !a && !b && !c && !d // [AA092 1H-AE / 2H-AL]
    s.insnT1 =  a && !b && !c && !d // [AA092 3G-AT / 4G-AY]
    s.insnT2 = !a &&  b && !c       // [AA092 1N-AH / 2N-AP]
    s.insnT3 =  a &&  b && !c       // [AA092 4N-BA / 5N-BF]
    s.insnT4 = !a && !b &&  c && !d // Implicit binary state 0100 (I-4: Q-Indirect high byte)
    s.insnT5 =  a && !b &&  c       // [AA092 5H-BD / 6H-BJ]
    s.insnT6 = !a &&  b &&  c       // [AA092 1K-AF / 2K-AA]
    s.insnT7 =  a &&  b &&  c && !d // Implicit binary state 0111 (I-7: P-Indirect low byte)
    s.insnT8 = !a && !b && !c &&  d // Implicit binary state 1000 (I-8: P-Indirect high byte)
    s.insnT9 =  a &&  d             // [AA092 5E-BC / 6E-BH]

    // ---------------------------------------------------------------------------------------------
    // [AA041] & [AA061] Operational Code Class Decodes
    // ---------------------------------------------------------------------------------------------
    // [AA041 3K-AP / 5K-AQ] +ARITHMETIC OPERATION = OPL C 1 + OPL C 2
    s.athOp = s.oplCde1 || s.oplCde2

    // [AA041 3L-AS] -IMMEDIATE = !(OPL C 1 & OPL C 2)
    s.immediate = s.oplCde1 && s.oplCde2

    // [AA061 1K-AY] -OUTPUT = !(-ATH OP & -OPL C 3 & OPL C 4 & -OPL C 5 & -OPL C 7)
    s.output = !s.athOp && !s.oplCde3 && s.oplCde4 && !s.oplCde5 && !s.oplCde7

    // [AA061 1F-AM] -INPUT = !(-ATH OP & -OPL C 3 & OPL C 4 & -OPL C 7 & -OUTPUT)
    s.input = !s.athOp && !s.oplCde3 && s.oplCde4 && !s.oplCde7 && !s.output

    // [AA061 1B-AA / AA041 4J-AN] +PROGRAM CONTROL = -ATH OP & OPL C 3 & OPL C 4 & OPL C 7
    s.prgCtl = !s.athOp && s.oplCde3 && s.oplCde4 && s.oplCde7

    // [AA041 3G-AK / 4G-AL] +JUMP = -ATH OP & -INPUT & -OUTPUT & -PROGRAM CONTROL
    s.jmp = !s.athOp && !s.input && !s.output && !s.prgCtl

    // [AA041] 16-bit Direct/Immediate Transfer (MOV / MOVI) and Transfer-or-Compare (TRANS + COMP)
    s.trans16 = s.oplCde2 && s.oplCde3 && s.oplCde4 && (s.immediate || (!s.oplCde6 && !s.oplCde7))
    s.transOrComp = s.oplCde2 && (s.oplCde4 || (!s.immediate && (s.oplCde6 || s.oplCde7)))

    // ---------------------------------------------------------------------------------------------
    // [AA131] +TAKEN Branch & Incremental Jump Condition Decode (1F-AL .. 1L-AW -> 2J-AQ -> 3J-AS)
    // ---------------------------------------------------------------------------------------------
    // 1F-AL: -JUMP & -OPL C 5 & -EQUAL (Incremental Jump on bit=0 when sampled bit was 0)
    val tknIncBitClear = !s.jmp && !s.oplCde5 && !s.eq
    // 1G-AM: -JUMP & +OPL C 5 & +EQUAL (Incremental Jump on bit=1 when sampled bit was 1)
    val tknIncBitSet = !s.jmp && s.oplCde5 && s.eq
    // 1H-AN: +JUMP & +EQUAL & +OPL C 7 & -OPL C 4 (Jump on Equal: OP3=1, OP4=0, OP7=1)
    val tknJmpEq = s.jmp && s.eq && s.oplCde7 && !s.oplCde4
    // 1J-AP: +JUMP & +LOW & -OPL C 7 (Jump on Low [1,0,0] and Low case of Jump on NotEqual [1,1,0])
    val tknJmpLo = s.jmp && s.low && !s.oplCde7
    // 1K-AV: +JUMP & -LOW & -EQUAL & +OPL C 4 (Jump on High [0,1,1] and High case of Jump on NotEqual [1,1,0])
    val tknJmpHi = s.jmp && !s.low && !s.eq && s.oplCde4
    // 1L-AW: +JUMP & -OPL C 3 & -OPL C 4 (Unconditional Jump [0,0,0])
    val tknJmpUncond = s.jmp && !s.oplCde3 && !s.oplCde4

    s.tkn = tknIncBitClear || tknIncBitSet || tknJmpEq || tknJmpLo || tknJmpHi || tknJmpUncond

    // ---------------------------------------------------------------------------------------------
    // [AA061] +SET 9 Decode (5J-AX .. 5N-BE -> 6L-BB -> 7L-BC)
    // ---------------------------------------------------------------------------------------------
    // 5J-AX: INSN T 6 & -OPL C 7 (Direct or Q-Indirect Arithmetic at I-6 -> I-9)
    val set9ArithDirectOrQInd = s.insnT6 && !s.oplCde7
    // 5K-AZ: INSN T 6 & OPL C 1 & OPL C 2 (Immediate Arithmetic at I-6 -> I-9)
    val set9Immediate = s.insnT6 && s.oplCde1 && s.oplCde2
    // 5L-BA: INSN T 3 & OPL C 1 & TAKEN (Incremental Jump Taken at I-3 -> I-9)
    val set9IncJmpTaken = s.insnT3 && s.oplCde1 && s.tkn
    // 5M-BD: INSN T 3 & JUMP & TAKEN (Jump with Link Taken at I-3 -> I-9)
    val set9LinkJmpTaken = s.insnT3 && s.jmp && s.tkn
    // 5N-BE: INSN T 1 & JUMP & -OPL C 5 & -EDIT & TAKEN (Jump without Link Taken at I-1 -> I-9)
    val set9NoLinkJmpTaken = s.insnT1 && s.jmp && !s.oplCde5 && !s.edit && s.tkn

    s.set9 =
      set9ArithDirectOrQInd ||
      set9Immediate ||
      set9IncJmpTaken ||
      set9LinkJmpTaken ||
      set9NoLinkJmpTaken

    // ---------------------------------------------------------------------------------------------
    // [AA041] & [AA092] Permute Signal (+PERMUTE = !-PERMUTE)
    // ---------------------------------------------------------------------------------------------
    // [AA041 3B-AB]: INSN T 1 & +EDIT (CE Edit / Addr / Data mode loops I-0 -> I-1 -> I-0)
    val prmtEditI1 = s.insnT1 && s.edit
    // [AA041 3C-AC / 3F-AH]: INSN T 3 & -OPL C 2 & -INPUT & -OUTPUT (Prog Ctrl, Inc Jump, Link Jump at I-3)
    val prmtI3NonQInd = s.insnT3 && !s.oplCde2 && !s.input && !s.output
    // [AA041 3D-AE]: INSN T 2 & -OPL C 1 & -OPL C 6 & -JUMP & -PROGRAM CONTROL (Skip I-3..I-4 -> I-5)
    val prmtI2ToI5 = s.insnT2 && !s.oplCde1 && !s.oplCde6 && !s.jmp && !s.prgCtl
    // [AA041 3E-AG]: INSN T 5 & -ARITHMETIC OPERATION (Input / Output finishes at I-5 -> I-0)
    val prmtIoI5 = s.insnT5 && !s.athOp
    // [AA092 1B-AA]: INSN T 1 & +JUMP & -TAKEN (Jump Not Taken finishes at I-1 -> I-0)
    val prmtJmpNotTakenI1 = s.insnT1 && s.jmp && !s.tkn
    // [AA092 2E-AK]: INSN T 1 & OPL C 1 & OPL C 2 (Immediate Arithmetic skips I-2..I-5 -> I-6)
    val prmtImmediateI1ToI6 = s.insnT1 && s.oplCde1 && s.oplCde2

    s.prmt =
      s.masterReset ||
      s.set9 ||
      s.insnT9 ||
      prmtEditI1 ||
      prmtI3NonQInd ||
      prmtI2ToI5 ||
      prmtIoI5 ||
      prmtJmpNotTakenI1 ||
      prmtImmediateI1ToI6

    // [AA092 2L-AZ] +P ONLY = -INSN T 1 & -INSN T 9 & (-JUMP | -INSN T 3)
    s.pOnly = !s.insnT1 && !s.insnT9 && (!s.jmp || !s.insnT3)

    evaluateClockStrobes(s)
  }

  /**
   * Fast evaluation of the 3 `0304` nets (`PRMT INSN T STR LTH`, `RES INSN T STR LTH`, `BEG PRG STP`)
   * that depend on sub-bit-time `0303` clock strobes (`prmtTime`, `strLthStrobe`, `b1_4`, `pTime`, `cenBt`),
   * avoiding re-evaluating all instruction-time and opcode class decodes on every single-shot edge
   * when `insnTCtrA..D`, `oplCde1..7`, `low`, `eq`, and `edit` have not changed.
   */
  inline def evaluateClockStrobes(s: BackplaneSignals): Unit = {
    // [AA092 5B-BB] +PERMUTE I TIME STEER LATCHES
    s.prmtInsnTStrLth = s.masterReset || (s.prmtTime && s.prmt)

    // [AA092 6B-BG] -RESET I-TIME STEER LATCHES (active low; stored as positive `resInsnTStrLth`)
    s.resInsnTStrLth = s.masterReset || (s.strLthStrobe && s.prmtInsnTStrLth)

    // [AA092 9L-BE] +BEGINNING PROGRAM STEP
    s.begPrgStp = s.insnT0 && s.b1_4 && s.pTime && !s.cenBt
  }

  /**
   * Updates the 4 Instruction Time Counter Steering Latches (`ST INST TIME CTR A..D`, `[AA091]`)
   * during `STR LTH STROBE` at `BIT 13-16` (or on `MASTER RESET`).
   */
  def updateSteeringLatches(s: BackplaneSignals): Unit = {
    if (s.masterReset) {
      s.strInsnTCtrA = false
      s.strInsnTCtrB = false
      s.strInsnTCtrC = false
      s.strInsnTCtrD = false
      return
    }

    if (s.strLthStrobe && s.b13_16) {
      evaluateCombinational(s)

      if (s.prmtInsnTStrLth) {
        // [AA091] Permute override: `resInsnTStrLth` resets all 4 steering latches to 0000 (I-0)
        // except those simultaneously set by the permute set gates (Set overrides Reset on OR-FL):
        // - Permute to I-5 (0101): INSN T 2 sets A (1B-AA) and C (1H-AE)
        // - Permute to I-6 (0110): -EDIT & INSN T 1 & OPL C 1 & OPL C 2 sets B (1F-AD) and C (1J-AF)
        // - Permute to I-9 (1001): +SET 9 sets A (1C-AB) and D (1M-AH)
        val permuteToI6 = !s.edit && s.insnT1 && s.oplCde1 && s.oplCde2
        s.strInsnTCtrA = s.insnT2 || s.set9
        s.strInsnTCtrB = permuteToI6
        s.strInsnTCtrC = s.insnT2 || permuteToI6
        s.strInsnTCtrD = s.set9
      } else {
        // [AA091] Normal binary count (+1) when -PERMUTE is high:
        val curA = s.insnTCtrA
        val curB = s.insnTCtrB
        val curC = s.insnTCtrC
        val curD = s.insnTCtrD

        // [AA091 2C-AK / 4C-AU] ST INST TIME CTR A toggles on B13-16 & STR LTH STROBE
        s.strInsnTCtrA = !curA

        // [AA091 2E-AL / 4E-AV] ST INST TIME CTR B toggles when curA is true
        if (curA && !curB) s.strInsnTCtrB = true
        else if (curA && curB) s.strInsnTCtrB = false

        // [AA091 2J-AN / 4J-AW] ST INST TIME CTR C sets at I-3 (curA & curB & !curC), resets at I-7 (curA & curB & curC)
        if (curA && curB && !curC) s.strInsnTCtrC = true
        else if (curA && curB && curC) s.strInsnTCtrC = false

        // [AA091 2L-AP] ST INST TIME CTR D sets at I-7 (curA & curB & curC & !curD) for I-8
        if (curA && curB && curC && !curD) s.strInsnTCtrD = true
      }
    }
  }

  /**
   * Updates the 4 Instruction Time Counter Indicator Latches (`INST TIME CTR A..D`, `[AA091]`)
   * when `RES INDS` and/or `LD INDS` are active (or on `MASTER RESET`).
   */
  def updateIndicatorLatches(s: BackplaneSignals): Unit = {
    if (s.masterReset) {
      s.insnTCtrA = false
      s.insnTCtrB = false
      s.insnTCtrC = false
      s.insnTCtrD = false
      evaluateCombinational(s)
      return
    }

    if (s.resInds) {
      // [AA091 7C-BM, 7F-BP, 7J-BY, 5M-BB] Reset by -RESET INSTRUCTION TIME CTR
      s.insnTCtrA = false
      s.insnTCtrB = false
      s.insnTCtrC = false
      s.insnTCtrD = false
    }

    if (s.ldInds) {
      // [AA091 6C-BD, 6F-BF, 6J-BH, 5L-BK] Set by +LOAD INDICATORS & +ST INST TIME CTR A..D
      // (Set overrides Reset when LD INDS and RES INDS overlap during SSA)
      if (s.strInsnTCtrA) s.insnTCtrA = true
      if (s.strInsnTCtrB) s.insnTCtrB = true
      if (s.strInsnTCtrC) s.insnTCtrC = true
      if (s.strInsnTCtrD) s.insnTCtrD = true
    }

    evaluateCombinational(s)
  }

  /**
   * Fast combined Instruction Time steering & indicator transition at the end of `BIT 13-16`
   * (`STR LTH STROBE` -> `SSA #1`), evaluating `0304` combinational nets once for the new Instruction Time.
   */
  inline def advanceInstructionTimeAtB13_16(s: BackplaneSignals): Unit = {
    if (s.masterReset) {
      s.strInsnTCtrA = false
      s.strInsnTCtrB = false
      s.strInsnTCtrC = false
      s.strInsnTCtrD = false
      s.insnTCtrA = false
      s.insnTCtrB = false
      s.insnTCtrC = false
      s.insnTCtrD = false
    } else if (s.prmt) {
      val permuteToI6 = !s.edit && s.insnT1 && s.oplCde1 && s.oplCde2
      val nextA = s.insnT2 || s.set9
      val nextB = permuteToI6
      val nextC = s.insnT2 || permuteToI6
      val nextD = s.set9
      s.strInsnTCtrA = nextA
      s.strInsnTCtrB = nextB
      s.strInsnTCtrC = nextC
      s.strInsnTCtrD = nextD
      s.insnTCtrA = nextA
      s.insnTCtrB = nextB
      s.insnTCtrC = nextC
      s.insnTCtrD = nextD
    } else {
      val curA = s.insnTCtrA
      val curB = s.insnTCtrB
      val curC = s.insnTCtrC
      val curD = s.insnTCtrD
      val nextA = !curA
      val nextB = if (curA) !curB else curB
      val nextC = if (curA && curB) !curC else curC
      val nextD = if (curA && curB && curC && !curD) true else s.strInsnTCtrD
      s.strInsnTCtrA = nextA
      s.strInsnTCtrB = nextB
      s.strInsnTCtrC = nextC
      s.strInsnTCtrD = nextD
      s.insnTCtrA = nextA
      s.insnTCtrB = nextB
      s.insnTCtrC = nextC
      s.insnTCtrD = nextD
    }

    evaluateCombinational(s)
  }
}
