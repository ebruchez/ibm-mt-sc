package mtsc.cpu.ild

/**
 * Gate- and latch-level implementation of **ILD Page `0308`** (*P-Buffer, Carry Over Latch &
 * 4-Bit Serial Adder/Subtractor*), corresponding to ALD sheets `[AA011]`–`[AA014]`, `[AA051]`,
 * `[AA083]`, `[AA101]`, `[AA121]`, `[AA131]`, and `[AA141]`.
 */
object Ild0308DataFlow {

  /**
   * Evaluates all combinational signals on **ILD `0308`**:
   * `KB CDE`, `IN STRB`, `KB STRB`, `KB ENT STRB`, `RES P BFR`, `ADD` / `-ADD`,
   * 4-bit ripple carry/borrow `CAR 1..4`, 4-bit sum/difference `ACC 1..4`, and `ACC EQUAL ZERO`.
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    // [AA121 AQ4] +KEYBOARD CODE = Y8 & Y9 & Y10 (Device 0111)
    s.kbCde = s.y8 && s.y9 && s.y10

    // [AA141 AW4] +INPUT STROBE = CEN BT & INSN T 5 & -INH & -KB CDE & INPUT
    s.inStrb = s.cenBt && s.insnT5 && !s.inh && !s.kbCde && s.input

    // [AA141 1M-AX, 2M-AY, 1N-AZ -> BC4] +KEYBOARD STROBE
    s.kbStrb =
      s.cenBt && (
        (s.edit && ((!s.btCtrB && s.insnT1 && s.dply2) || (s.insnT0 && s.dply1))) ||
        (!s.edit && s.input && s.kbCde && s.insnT5)
      )

    // [AA051] +KEYBOARD ENTRY STROBE
    val kbBitGp =
      (s.b1_4 && !s.kbCodeC && !s.kbCodeD) ||
      (s.b5_8 && s.kbCodeC && !s.kbCodeD) ||
      (s.b9_12 && !s.kbCodeC && s.kbCodeD)
    s.kbEntStrb = (s.kbStrb && s.kbCodeRdy && kbBitGp) || (s.kbStrb && s.b1_4 && s.lnStpLth)

    // [AA083 / AA011] RES P BFR = (RES BFR & -CEN BT) | MR
    s.resPBfr = (s.resBfr && !s.cenBt) || s.masterReset

    // [AA101 1M-BH..4L-BE] +ADD and -ADD:
    // Subtraction (-ADD) is selected during I-9 of Arithmetic / Incremental Jump when OPL CDE 3 is 0
    // (SUB, SUBI, CMP, CMPI, CMPB, and Backward Incremental Jump).
    s.add = !(s.insnT9 && s.athOp && !s.oplCde3)
    s.notAdd = !s.add

    // [AA011..AA014] 4-Bit Full Adder (when +ADD) / Full Borrow Subtractor (when -ADD)
    val c0 = s.carOver
    s.acc1 = s.pBfr1 ^ s.qBfr1 ^ c0
    s.car1 =
      (s.add && s.pBfr1 && (s.qBfr1 || c0)) ||
      (s.notAdd && !s.pBfr1 && (s.qBfr1 || c0)) ||
      (s.qBfr1 && c0)

    val c1 = s.car1
    s.acc2 = s.pBfr2 ^ s.qBfr2 ^ c1
    s.car2 =
      (s.add && s.pBfr2 && (s.qBfr2 || c1)) ||
      (s.notAdd && !s.pBfr2 && (s.qBfr2 || c1)) ||
      (s.qBfr2 && c1)

    val c2 = s.car2
    s.acc3 = s.pBfr3 ^ s.qBfr3 ^ c2
    s.car3 =
      (s.add && s.pBfr3 && (s.qBfr3 || c2)) ||
      (s.notAdd && !s.pBfr3 && (s.qBfr3 || c2)) ||
      (s.qBfr3 && c2)

    val c3 = s.car3
    s.acc4 = s.pBfr4 ^ s.qBfr4 ^ c3
    s.car4 =
      (s.add && s.pBfr4 && (s.qBfr4 || c3)) ||
      (s.notAdd && !s.pBfr4 && (s.qBfr4 || c3)) ||
      (s.qBfr4 && c3)

    // [AA012 7F-AW / AA014 6F-AU, 6G-AV] +ACCUMULATOR EQUAL ZERO
    s.accEqualZero = !s.acc1 && !s.acc2 && !s.acc3 && !s.acc4
  }

  /**
   * Updates `P BUFFER 1..4` (`[AA011]`, `[AA013]`) from `Q BUFFER 1..4` (during `P Read`),
   * `IN BIT 1..8` (during `IN STRB`), or Keyboard Code lines (during `KB ENT STRB`),
   * and re-evaluates the 4-bit Accumulator and Carry network.
   */
  def updatePBuffer(s: BackplaneSignals): Unit = {
    evaluateCombinational(s)

    if (s.resPBfr) {
      s.pBfr1 = false
      s.pBfr2 = false
      s.pBfr3 = false
      s.pBfr4 = false
    }

    // Load from Q BUFFER 1..4 during P-Read (P TIME & -B & -TWR)
    if (s.pTime && !s.b && !s.twr) {
      if (s.qBfr1) s.pBfr1 = true
      if (s.qBfr2) s.pBfr2 = true
      if (s.qBfr3) s.pBfr3 = true
      if (s.qBfr4) s.pBfr4 = true
    }

    // Load from external Input Bus (Tape / Paper Tape) during IN STRB (I-5, CEN BT)
    if (s.inStrb) {
      if (s.b1_4) {
        if (s.inBit1) s.pBfr1 = true
        if (s.inBit2) s.pBfr2 = true
        if (s.inBit3) s.pBfr3 = true
        if (s.inBit4) s.pBfr4 = true
      } else if (s.b5_8) {
        if (s.inBit5) s.pBfr1 = true
        if (s.inBit6) s.pBfr2 = true
        if (s.inBit7) s.pBfr3 = true
        if (s.inBit8) s.pBfr4 = true
      }
    }

    // Load from Keyboard Entry Strobe (KB ENT STRB)
    if (s.kbEntStrb) {
      if ((!s.kbCodeA && !s.kbCodeB) || (s.lnStpLth && s.kbCodeC && s.kbCodeD)) s.pBfr1 = true
      if ( s.kbCodeA && !s.kbCodeB) s.pBfr2 = true
      if (!s.kbCodeA &&  s.kbCodeB) s.pBfr3 = true
      if ( s.kbCodeA &&  s.kbCodeB) s.pBfr4 = true
    }

    evaluateCombinational(s)
  }

  /**
   * Updates the steering stage of `+CARRY OVER LATCH` (`[AA101 4G-AT..5G-AU]`) at `STR LTH STROBE`.
   */
  def updateCarrySteering(s: BackplaneSignals): Unit = {
    if (s.masterReset) {
      s.strCarOver = false
      return
    }
    if (s.strLthStrobe) {
      evaluateCombinational(s)
      s.strCarOver = if (s.b13_16) false else s.car4
    }
  }

  /**
   * Updates the indicator stage of `+CARRY OVER LATCH` (`[AA101 6G-AV..7G-BA]`) at `RES INDS` / `LD INDS`.
   */
  def updateCarryIndicator(s: BackplaneSignals): Unit = {
    if (s.masterReset || s.b1_4) {
      s.carOver = false
    } else if (s.resInds || s.ldInds) {
      s.carOver = s.strCarOver
    }
    evaluateCombinational(s)
  }
}
