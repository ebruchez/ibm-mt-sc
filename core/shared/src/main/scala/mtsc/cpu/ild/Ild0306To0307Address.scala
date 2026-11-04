package mtsc.cpu.ild

/**
 * Gate- and latch-level implementation of **ILD Pages `0306` & `0307`** (*Memory Allocation,
 * Memory Address Latches `Y1..Y11`, and X/Y Current Drivers & Switches*), corresponding to
 * ALD sheets `[AA042]`, `[AA101]`, `[AA111]`, `[AA112]`, `[AA121]`, `[AA122]`, `[AA161]`,
 * `[AA241]`, and `[AA271]`–`[AA275]`.
 */
object Ild0306To0307Address {

  /**
   * Evaluates all combinational memory allocation signals (`SEL PROG STEP ADDR`, `SEL A WD`,
   * `SEL B WD`, `SEL MEM SPEC`, `SEL MEM`, `SEL MEM UP`), Y-latch load/reset controls, and
   * X/Y Driver and Switch decoders (`0306` & `0307`).
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    // ---------------------------------------------------------------------------------------------
    // [AA042] & [AA112] Memory Allocation Logic
    // ---------------------------------------------------------------------------------------------
    // [AA042 1K-AQ..2K-AR]: +SELECT PROGRAM STEP ADDRESS
    s.selProgStepAddr =
      s.insnT0 || (s.pTime && ((s.insnT3 && s.jmp) || (s.insnT9 && !s.oplCde2)))

    // [AA042 4H-AN..5K-AT]: +SELECT A WORD
    s.selAWd =
      (!s.edit && s.insnT1 && s.inh) ||
      (!s.pTime && s.insnT9 && (s.oplCde1 || s.jmp)) ||
      (!s.pTime && s.insnTCtrA && !s.insnT3 && !s.insnT5 && !s.insnT9) ||
      (!s.insnTCtrA && (s.insnTCtrB || s.insnTCtrD))

    // [AA042 5C-AG..7D-BA]: +SELECT B WORD
    s.selBWd =
      (s.athOp && s.insnT5 && !s.pTime) ||
      (!s.jmp && !s.oplCde1 && s.insnT3 && !s.pTime && !s.prgCtl) ||
      (!s.insnTCtrA && !s.insnTCtrB && s.insnTCtrC) ||
      (!s.immediate && !s.pTime && s.oplCde2 && s.insnT9)

    // [AA112] +SELECT MEMORY SPECIAL & +SELECT MEMORY
    s.selMemSpec = s.selProgStepAddr || s.selAWd || s.selBWd
    s.selectMemory = !s.selMemSpec

    // [AA042 2B-AA..4B-AF]: +SELECT MEMORY UPPER
    // Note: 2B-AA includes -SELECT B WORD (!s.selBWd) so that during I-9 Q-Time (when B-Word is read),
    // selMemUp is false and Y1=1 (odd P-address) does not shift Y-Switch from the low byte of B-Word to the high byte!
    s.selMemUp =
      (!s.selProgStepAddr && s.jmp && s.insnT3) ||
      (!s.selAWd && s.insnT1) ||
      (!s.selBWd && s.insnT5 && s.oplCde6) ||
      (!s.selBWd && s.oplCde2 && s.oplCde7 && s.insnT9 && !s.immediate)

    // ---------------------------------------------------------------------------------------------
    // [AA101] Y-Latch Load & Reset Control Signals
    // ---------------------------------------------------------------------------------------------
    s.ldY         = !s.insnTCtrA && !s.insnT6 && s.cenBt
    s.ldYInsnT6   = s.insnT6 && s.cenBt
    s.incrJmpResY = s.pTime && s.b5_8 && s.oplCde1 && s.insnT2
    s.resY =
      s.masterReset ||
      (s.mpx && s.rdy && s.insnT3) ||
      (!s.insnTCtrA && s.b1_4 && !s.b && !s.twr && s.pTime && !s.cenBt)

    // ---------------------------------------------------------------------------------------------
    // [AA112], [AA121], [AA122] Y-Driver (1..8) and Y-Switch (1..4) Decodes (ILD 0306)
    // ---------------------------------------------------------------------------------------------
    val selMem = s.selectMemory
    s.dcdDrvrY1234 = selMem && (!s.selMemUp || !s.y11)
    s.dcdDrvrY2468 = (s.selMemSpec && !s.selAWd) || (selMem && s.y2)
    s.dcdDrvrY3478 = (s.selMemSpec && !s.selBWd) || (selMem && s.y3)
    s.dcdDrvrY5678 = s.selMemSpec || (selMem && s.selMemUp && s.y11)

    s.dcdSwY24 = s.btCtrB || (s.selMemUp && s.y1)
    s.dcdSwY34 = s.selMemSpec || (s.selMemUp && s.y7)

    s.activeYDriver =
      (if (s.dcdDrvrY5678) 4 else 0) |
        (if (s.dcdDrvrY3478) 2 else 0) |
        (if (s.dcdDrvrY2468) 1 else 0)

    s.activeYSwitch =
      (if (s.dcdSwY34) 2 else 0) |
        (if (s.dcdSwY24) 1 else 0)

    // ---------------------------------------------------------------------------------------------
    // [AA112], [AA121], [AA122] X-Driver (1..8) and X-Switch (1..8) Decodes (ILD 0307)
    // ---------------------------------------------------------------------------------------------
    s.dcdDrvrX1   = selMem && (!s.selMemUp || (!s.y8 && !s.y9 && !s.y10))
    s.dcdDrvrX234 = selMem && s.selMemUp && ((!s.y10 && s.y8) || (!s.y10 && s.y9))
    s.dcdDrvrX567 = selMem && s.selMemUp && ((s.y10 && !s.y8) || (s.y10 && !s.y9))
    s.dcdDrvrX8   = s.selMemSpec || (selMem && s.selMemUp && s.y8 && s.y9 && s.y10)

    s.dcdSwX1234 = selMem && !s.y6
    s.dcdSwX56   = selMem && !s.y5 && s.y6
    s.dcdSwX57   = selMem && !s.y4 && s.y6
    s.dcdSwX8    = s.selMemSpec || (selMem && s.y4 && s.y5 && s.y6)

    s.activeXDriver =
      if (s.selMemSpec) 7
      else if (!s.selMemUp) 0
      else ((if (s.y10) 4 else 0) | (if (s.y9) 2 else 0) | (if (s.y8) 1 else 0))

    s.activeXSwitch =
      if (s.selMemSpec) 7
      else ((if (s.y6) 4 else 0) | (if (s.y5) 2 else 0) | (if (s.y4) 1 else 0))
  }

  /**
   * Updates the 11 Memory Address Latches (`Y1..Y11` on `[AA111]` and `[AA161]`).
   */
  def updateYLatches(s: BackplaneSignals): Unit = {
    evaluateCombinational(s)

    // Reset conditions for Y1..Y11
    if (s.resY || (!s.edit && s.jmp)) {
      s.y1 = false
    }
    if (s.resY || s.incrJmpResY) {
      s.y2 = false
      s.y3 = false
    }
    // Note: INCR JMP RES Y resets Y4 prior to loading Y4 at B5-8 CEN BT
    if (s.resY || (s.incrJmpResY && (!s.cenBt || s.ssa))) {
      s.y4 = false
    }
    if (s.resY) {
      s.y5  = false
      s.y6  = false
      s.y7  = false
      s.y8  = false
      s.y9  = false
      s.y10 = false
      s.y11 = false
    }

    // Set conditions for Y1..Y11 from P BUFFER 1..4 during LD Y (I-0, I-2, I-4, I-8) and LD Y INSN T 6 (I-6)
    if (s.ldY && s.b1_4 && s.pBfr1 && !(!s.edit && s.jmp)) {
      s.y1 = true
    }
    if (((s.ldY && s.b1_4 && s.pBfr2) || (s.ldYInsnT6 && s.b5_8 && s.pBfr4 && !s.immediate)) && !s.incrJmpResY) {
      s.y2 = true
    }
    if (((s.ldY && s.b1_4 && s.pBfr3) || (s.ldYInsnT6 && s.b9_12 && s.pBfr1)) && !s.incrJmpResY) {
      s.y3 = true
    }
    if (
      (s.ldY && s.b1_4 && s.pBfr4) ||
      (s.ldY && s.b5_8 && s.pBfr3 && s.oplCde1 && s.insnTCtrB) ||
      (s.ldYInsnT6 && s.b9_12 && s.pBfr2)
    ) {
      s.y4 = true
    }
    if (
      (s.ldY && s.b5_8 && (s.pBfr1 || (s.oplCde1 && s.insnTCtrB))) ||
      (s.ldYInsnT6 && s.b9_12 && s.pBfr3)
    ) {
      s.y5 = true
    }
    if ((s.ldY && s.b5_8 && s.pBfr2) || (s.ldYInsnT6 && s.b9_12 && s.pBfr4)) {
      s.y6 = true
    }
    if (s.ldY && s.b5_8 && s.pBfr3) {
      s.y7 = true
    }
    if (s.ldY && s.b5_8 && s.pBfr4) {
      s.y8 = true
    }
    if (s.ldY && s.b9_12 && s.pBfr1) {
      s.y9 = true
    }
    if (s.ldY && s.b9_12 && s.pBfr2) {
      s.y10 = true
    }
    if (s.ldY && s.b9_12 && s.pBfr3) {
      s.y11 = true
    }

    evaluateCombinational(s)
  }
}
