package mtsc.cpu.ild

/**
 * Gate- and latch-level implementation of **ILD Pages `0306` & `0307`** (*Memory Allocation,
 * Memory Address Latches `Y1..Y11`, and X/Y Current Drivers & Switches*), corresponding to
 * ALD sheets `[AA042]`, `[AA101]`, `[AA111]`, `[AA112]`, `[AA121]`, `[AA122]`, `[AA161]`,
 * `[AA241]`, and `[AA271]`–`[AA275]`.
 */
object Ild0306To0307Address {

  /**
   * Evaluates memory allocation signals (`SEL PROG STEP ADDR`, `SEL A WD`, `SEL B WD`, `SEL MEM SPEC`,
   * `SEL MEM`, `SEL MEM UP`) and Y-latch load/reset controls (`LD Y`, `LD Y INSN T 6`, `INCR JMP RES Y`, `RES Y`).
   */
  def evaluateAllocationAndControl(s: BackplaneSignals): Unit = {
    // ---------------------------------------------------------------------------------------------
    // [AA042] & [AA112] Memory Allocation Logic
    // ---------------------------------------------------------------------------------------------
    // [AA042 1K-AQ..2K-AR]: +SELECT PROGRAM STEP ADDRESS
    val selPsar = s.insnT0 || (s.pTime && ((s.insnT3 && s.jmp) || (s.insnT9 && !s.oplCde2)))
    s.selProgStepAddr = selPsar

    // [AA042 4H-AN..5K-AT]: +SELECT A WORD
    val selA =
      (!s.edit && s.insnT1 && s.inh) ||
      (!s.pTime && s.insnT9 && (s.oplCde1 || s.jmp)) ||
      (!s.pTime && s.insnTCtrA && !s.insnT3 && !s.insnT5 && !s.insnT9) ||
      (!s.insnTCtrA && (s.insnTCtrB || s.insnTCtrD))
    s.selAWd = selA

    // [AA042 5C-AG..7D-BA]: +SELECT B WORD
    val selB =
      (s.athOp && s.insnT5 && !s.pTime) ||
      (!s.jmp && !s.oplCde1 && s.insnT3 && !s.pTime && !s.prgCtl) ||
      (!s.insnTCtrA && !s.insnTCtrB && s.insnTCtrC) ||
      (!s.immediate && !s.pTime && s.oplCde2 && s.insnT9)
    s.selBWd = selB

    // [AA112] +SELECT MEMORY SPECIAL & +SELECT MEMORY
    val selSpec = selPsar || selA || selB
    s.selMemSpec = selSpec
    s.selectMemory = !selSpec

    // [AA042 2B-AA..4B-AF]: +SELECT MEMORY UPPER
    s.selMemUp =
      (!selPsar && s.jmp && s.insnT3) ||
      (!selA && s.insnT1) ||
      (!selB && s.insnT5 && s.oplCde6) ||
      (!selB && s.oplCde2 && s.oplCde7 && s.insnT9 && !s.immediate)

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
  }

  /**
   * Evaluates X/Y Driver (`1..8`) and Switch (`1..8` / `1..4`) decoders (`0306` & `0307`).
   */
  def evaluateDecoders(s: BackplaneSignals): Unit = {
    val selSpec = s.selMemSpec
    val selUp = s.selMemUp

    if (selSpec) {
      s.dcdDrvrY1234 = false
      val dY2468 = !s.selAWd
      val dY3478 = !s.selBWd
      s.dcdDrvrY2468 = dY2468
      s.dcdDrvrY3478 = dY3478
      s.dcdDrvrY5678 = true

      val swY24 = s.btCtrB || (selUp && s.y1)
      s.dcdSwY24 = swY24
      s.dcdSwY34 = true

      s.activeYDriver =
        4 |
          (if (dY3478) 2 else 0) |
          (if (dY2468) 1 else 0)

      s.activeYSwitch =
        2 |
          (if (swY24) 1 else 0)

      s.dcdDrvrX1   = false
      s.dcdDrvrX234 = false
      s.dcdDrvrX567 = false
      s.dcdDrvrX8   = true

      s.dcdSwX1234 = false
      s.dcdSwX56   = false
      s.dcdSwX57   = false
      s.dcdSwX8    = true

      s.activeXDriver = 7
      s.activeXSwitch = 7
    } else {
      val y1 = s.y1
      val y2 = s.y2
      val y3 = s.y3
      val y4 = s.y4
      val y5 = s.y5
      val y6 = s.y6
      val y7 = s.y7
      val y8 = s.y8
      val y9 = s.y9
      val y10 = s.y10
      val y11 = s.y11

      val dY5678 = selUp && y11
      s.dcdDrvrY1234 = !dY5678
      s.dcdDrvrY2468 = y2
      s.dcdDrvrY3478 = y3
      s.dcdDrvrY5678 = dY5678

      val swY24 = s.btCtrB || (selUp && y1)
      val swY34 = selUp && y7
      s.dcdSwY24 = swY24
      s.dcdSwY34 = swY34

      s.activeYDriver =
        (if (dY5678) 4 else 0) |
          (if (y3) 2 else 0) |
          (if (y2) 1 else 0)

      s.activeYSwitch =
        (if (swY34) 2 else 0) |
          (if (swY24) 1 else 0)

      s.dcdDrvrX1   = !selUp || (!y8 && !y9 && !y10)
      s.dcdDrvrX234 = selUp && !y10 && (y8 || y9)
      s.dcdDrvrX567 = selUp && y10 && (!y8 || !y9)
      s.dcdDrvrX8   = selUp && y8 && y9 && y10

      s.dcdSwX1234 = !y6
      s.dcdSwX56   = !y5 && y6
      s.dcdSwX57   = !y4 && y6
      s.dcdSwX8    = y4 && y5 && y6

      s.activeXDriver =
        if (!selUp) 0
        else ((if (y10) 4 else 0) | (if (y9) 2 else 0) | (if (y8) 1 else 0))

      s.activeXSwitch =
        (if (y6) 4 else 0) | (if (y5) 2 else 0) | (if (y4) 1 else 0)
    }
  }

  /**
   * Evaluates all combinational memory allocation signals (`SEL PROG STEP ADDR`, `SEL A WD`,
   * `SEL B WD`, `SEL MEM SPEC`, `SEL MEM`, `SEL MEM UP`), Y-latch load/reset controls, and
   * X/Y Driver and Switch decoders (`0306` & `0307`).
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    evaluateAllocationAndControl(s)
    evaluateDecoders(s)
  }

  /**
   * Updates the 11 Memory Address Latches (`Y1..Y11` on `[AA111]` and `[AA161]`).
   */
  def updateYLatches(s: BackplaneSignals): Unit = {
    evaluateAllocationAndControl(s)

    if (s.resY || (!s.edit && s.jmp) || s.incrJmpResY || s.ldY || s.ldYInsnT6) {
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
      if (s.ldY || s.ldYInsnT6) {
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
      }
    }

    evaluateDecoders(s)
  }

  /**
   * Fast P-Time (`pTime = true`) evaluation of memory allocation signals (`selProgStepAddr`,
   * `selAWd`, `selBWd`, `selMemSpec`, `selectMemory`, `selMemUp`) and 3D X/Y indices
   * (`activeXDriver`, `activeXSwitch`, `activeYDriver`, `activeYSwitch`).
   */
  inline def evaluateAddressPTimeFast(s: BackplaneSignals): Unit = {
    val selPsar = s.insnT0 || (s.insnT3 && s.jmp) || (s.insnT9 && !s.oplCde2)
    val selA = (!s.insnTCtrA && (s.insnTCtrB || s.insnTCtrD)) || (!s.edit && s.insnT1 && s.inh)
    val selB = s.insnT4
    val selSpec = selPsar || selA || selB
    s.selProgStepAddr = selPsar
    s.selAWd = selA
    s.selBWd = selB
    s.selMemSpec = selSpec
    s.selectMemory = !selSpec

    val selUp =
      (!selA && s.insnT1) ||
      (s.insnT5 && s.oplCde6) ||
      (s.oplCde2 && s.oplCde7 && s.insnT9 && !s.immediate)
    s.selMemUp = selUp

    if (selSpec) {
      s.activeYDriver = 4 | (if (!selB) 2 else 0) | (if (!selA) 1 else 0)
      s.activeYSwitch = 2 | (if (s.btCtrB || (selUp && s.y1)) 1 else 0)
      s.activeXDriver = 7
      s.activeXSwitch = 7
    } else {
      val dY5678 = selUp && s.y11
      s.activeYDriver = (if (dY5678) 4 else 0) | (if (s.y3) 2 else 0) | (if (s.y2) 1 else 0)
      s.activeYSwitch = (if (selUp && s.y7) 2 else 0) | (if (s.btCtrB || (selUp && s.y1)) 1 else 0)
      s.activeXDriver = if (!selUp) 0 else ((if (s.y10) 4 else 0) | (if (s.y9) 2 else 0) | (if (s.y8) 1 else 0))
      s.activeXSwitch = (if (s.y6) 4 else 0) | (if (s.y5) 2 else 0) | (if (s.y4) 1 else 0)
    }
  }

  /**
   * Fast Q-Time (`pTime = false`, `insnTCtrA = true`) evaluation of memory allocation signals and
   * 3D X/Y indices (`activeXDriver`, `activeXSwitch`, `activeYDriver`, `activeYSwitch`).
   */
  inline def evaluateAddressQTimeFast(s: BackplaneSignals): Unit = {
    val selA = (s.insnT9 && (s.oplCde1 || s.jmp)) || (!s.insnT3 && !s.insnT5 && !s.insnT9)
    val selB = (s.athOp && s.insnT5) || (!s.jmp && !s.oplCde1 && s.insnT3 && !s.prgCtl) || (!s.immediate && s.oplCde2 && s.insnT9)
    val selSpec = selA || selB
    s.selProgStepAddr = false
    s.selAWd = selA
    s.selBWd = selB
    s.selMemSpec = selSpec
    s.selectMemory = !selSpec

    val selUp =
      (s.jmp && s.insnT3) ||
      (!selB && ((s.insnT5 && s.oplCde6) || (s.oplCde2 && s.oplCde7 && s.insnT9 && !s.immediate)))
    s.selMemUp = selUp

    if (selSpec) {
      s.activeYDriver = 4 | (if (!selB) 2 else 0) | (if (!selA) 1 else 0)
      s.activeYSwitch = 2 | (if (s.btCtrB || (selUp && s.y1)) 1 else 0)
      s.activeXDriver = 7
      s.activeXSwitch = 7
    } else {
      val dY5678 = selUp && s.y11
      s.activeYDriver = (if (dY5678) 4 else 0) | (if (s.y3) 2 else 0) | (if (s.y2) 1 else 0)
      s.activeYSwitch = (if (selUp && s.y7) 2 else 0) | (if (s.btCtrB || (selUp && s.y1)) 1 else 0)
      s.activeXDriver = if (!selUp) 0 else ((if (s.y10) 4 else 0) | (if (s.y9) 2 else 0) | (if (s.y8) 1 else 0))
      s.activeXSwitch = (if (s.y6) 4 else 0) | (if (s.y5) 2 else 0) | (if (s.y4) 1 else 0)
    }
  }

  /**
   * Fast Y-latch load at `Cycle 2 SSB` (`CEN BT = true`) when `resY`, `!edit && jmp`, and
   * `incrJmpResY` have already been applied earlier in the bit-time.
   * Only active during `B1-4`, `B5-8`, and `B9-12` of even instruction times (`!s.insnTCtrA`).
   */
  inline def loadYLatchesAtCenBt(s: BackplaneSignals, pNib: Int): Unit = {
    if (!s.insnTCtrA && !s.b13_16) {
      if (!s.insnT6) {
        if (s.b1_4) {
          if ((pNib & 1) != 0 && (s.edit || !s.jmp)) s.y1 = true
          if ((pNib & 2) != 0) s.y2 = true
          if ((pNib & 4) != 0) s.y3 = true
          if ((pNib & 8) != 0) s.y4 = true
        } else if (s.b5_8) {
          val incJmpI2 = s.oplCde1 && s.insnTCtrB
          val p3 = (pNib & 4) != 0
          if (p3 && incJmpI2) s.y4 = true
          if ((pNib & 1) != 0 || incJmpI2) s.y5 = true
          if ((pNib & 2) != 0) s.y6 = true
          if (p3) s.y7 = true
          if ((pNib & 8) != 0) s.y8 = true
        } else {
          if ((pNib & 1) != 0) s.y9 = true
          if ((pNib & 2) != 0) s.y10 = true
          if ((pNib & 4) != 0) s.y11 = true
        }
      } else {
        if (s.b5_8) {
          if ((pNib & 8) != 0 && !s.immediate) s.y2 = true
        } else if (s.b9_12) {
          if ((pNib & 1) != 0) s.y3 = true
          if ((pNib & 2) != 0) s.y4 = true
          if ((pNib & 4) != 0) s.y5 = true
          if ((pNib & 8) != 0) s.y6 = true
        }
      }
    }
  }
}
