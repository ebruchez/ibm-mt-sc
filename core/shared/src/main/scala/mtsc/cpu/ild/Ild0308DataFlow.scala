package mtsc.cpu.ild

/**
 * Gate- and latch-level implementation of **ILD Page `0308`** (*P-Buffer, Carry Over Latch &
 * 4-Bit Serial Adder/Subtractor*), corresponding to ALD sheets `[AA011]`–`[AA014]`, `[AA051]`,
 * `[AA083]`, `[AA101]`, `[AA121]`, `[AA131]`, and `[AA141]`.
 */
object Ild0308DataFlow {

  /**
   * Evaluates P-Buffer strobe & reset signals (`KB CDE`, `IN STRB`, `KB STRB`, `KB ENT STRB`, `RES P BFR`).
   */
  def evaluateBufferStrobes(s: BackplaneSignals): Unit = {
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
    if (s.kbStrb && (s.kbCodeRdy || s.lnStpLth)) {
      val kbBitGp =
        (s.b1_4 && ((!s.kbCodeC && !s.kbCodeD) || (s.kbCodeC && s.kbCodeD))) ||
        (s.b5_8 && s.kbCodeC && !s.kbCodeD) ||
        (s.b9_12 && !s.kbCodeC && s.kbCodeD)
      s.kbEntStrb = (s.kbCodeRdy && kbBitGp) || (s.b1_4 && s.lnStpLth)
    } else {
      s.kbEntStrb = false
    }

    // [AA083 / AA011] RES P BFR = (RES BFR & -CEN BT) | MR
    s.resPBfr = (s.resBfr && !s.cenBt) || s.masterReset
  }

  /**
   * Evaluates the 4-bit serial full adder / borrow subtractor (`ADD`, `-ADD`, `ACC 1..4`, `CAR 1..4`, `ACC EQUAL ZERO`).
   */
  def evaluateAdder(s: BackplaneSignals): Unit = {
    // [AA101 1M-BH..4L-BE] +ADD and -ADD:
    // Subtraction (-ADD) is selected during I-9 of Arithmetic / Incremental Jump when OPL CDE 3 is 0
    // (SUB, SUBI, CMP, CMPI, CMPB, and Backward Incremental Jump).
    val add = !(s.insnT9 && s.athOp && !s.oplCde3)
    val notAdd = !add
    s.add = add
    s.notAdd = notAdd

    // [AA011..AA014] 4-Bit Full Adder (when +ADD) / Full Borrow Subtractor (when -ADD)
    val p1 = s.pBfr1
    val q1 = s.qBfr1
    val c0 = s.carOver
    val acc1 = p1 ^ q1 ^ c0
    val c1 =
      (add && p1 && (q1 || c0)) ||
      (notAdd && !p1 && (q1 || c0)) ||
      (q1 && c0)
    s.acc1 = acc1
    s.car1 = c1

    val p2 = s.pBfr2
    val q2 = s.qBfr2
    val acc2 = p2 ^ q2 ^ c1
    val c2 =
      (add && p2 && (q2 || c1)) ||
      (notAdd && !p2 && (q2 || c1)) ||
      (q2 && c1)
    s.acc2 = acc2
    s.car2 = c2

    val p3 = s.pBfr3
    val q3 = s.qBfr3
    val acc3 = p3 ^ q3 ^ c2
    val c3 =
      (add && p3 && (q3 || c2)) ||
      (notAdd && !p3 && (q3 || c2)) ||
      (q3 && c2)
    s.acc3 = acc3
    s.car3 = c3

    val p4 = s.pBfr4
    val q4 = s.qBfr4
    val acc4 = p4 ^ q4 ^ c3
    val c4 =
      (add && p4 && (q4 || c3)) ||
      (notAdd && !p4 && (q4 || c3)) ||
      (q4 && c3)
    s.acc4 = acc4
    s.car4 = c4

    // [AA012 7F-AW / AA014 6F-AU, 6G-AV] +ACCUMULATOR EQUAL ZERO
    s.accEqualZero = !acc1 && !acc2 && !acc3 && !acc4
  }

  /**
   * Evaluates all combinational signals on **ILD `0308`**:
   * `KB CDE`, `IN STRB`, `KB STRB`, `KB ENT STRB`, `RES P BFR`, `ADD` / `-ADD`,
   * 4-bit ripple carry/borrow `CAR 1..4`, 4-bit sum/difference `ACC 1..4`, and `ACC EQUAL ZERO`.
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    evaluateBufferStrobes(s)
    evaluateAdder(s)
  }

  /**
   * Updates `P BUFFER 1..4` (`[AA011]`, `[AA013]`) from `Q BUFFER 1..4` (during `P Read`),
   * `IN BIT 1..8` (during `IN STRB`), or Keyboard Code lines (during `KB ENT STRB`),
   * and re-evaluates the 4-bit Accumulator and Carry network.
   */
  def updatePBuffer(s: BackplaneSignals): Unit = {
    evaluateBufferStrobes(s)

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

    evaluateAdder(s)
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
      evaluateAdder(s)
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
    evaluateAdder(s)
  }
}
