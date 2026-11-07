package mtsc.cpu.ild

/**
 * Gate- and latch-level implementation of **ILD Page `0310`** (*`WR ACC`, `WR P BFR`,
 * Current Source Logic & Sense Inhibit Lines*), corresponding to ALD sheets `[AA012]`,
 * `[AA014]`, `[AA041]`, `[AA052]`, `[AA061]`, `[AA121]`, and `[BA011]`.
 */
object Ild0310WriteInhibit {

  /**
   * Evaluates all combinational write-control, bit-modification, current-source inhibit,
   * and even/odd sense-inhibit line signals on **ILD `0310`**.
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    // Ensure 0305, 0308, and 0309 combinational signals (inhibitMemory, transOrComp, rewoGate, roweGate, samBt, acc1..4) are fresh
    Ild0305SenseQBuffer.evaluateCombinational(s)
    Ild0308DataFlow.evaluateCombinational(s)
    Ild0309OpAndBranch.evaluateCombinational(s)
    evaluateWriteNets(s)
  }

  /**
   * Evaluates the `0310` write-control and sense-inhibit signals assuming `0305`, `0308`, and `0309`
   * signals (`inhibitMemory`, `transOrComp`, `rewoGate`, `roweGate`, `samBt`, `acc1..4`) are already fresh.
   */
  def evaluateWriteNets(s: BackplaneSignals): Unit = {

    // ---------------------------------------------------------------------------------------------
    // [AA061 3G-AS..6G-AV] +WRITE P BUFFER (WR P BFR)
    // ---------------------------------------------------------------------------------------------
    val wrPBfrWriteQ  = !s.pTime && s.insnTCtrA && !s.insnTCtrD
    val wrPBfrCmpI9   = s.pTime && s.insnT9 && s.transOrComp && s.oplCde2 && !s.oplCde3
    s.wrPBfr = wrPBfrWriteQ || wrPBfrCmpI9

    // ---------------------------------------------------------------------------------------------
    // [AA061 6B-AC..7D-AJ] +WRITE ACCUMULATOR (WR ACC)
    // ---------------------------------------------------------------------------------------------
    val inhWrAccI4       = !s.insnTCtrA && !s.insnTCtrB && s.insnTCtrC
    val inhWrAccIncJmpI2 = s.b5_8 && s.insnT2 && s.oplCde1
    val inhWrAccI6NonImm = s.insnT6 && !s.immediate
    s.wrAcc =
      s.pTime &&
      !s.wrPBfr &&
      !inhWrAccI4 &&
      !inhWrAccIncJmpI2 &&
      !inhWrAccI6NonImm

    // ---------------------------------------------------------------------------------------------
    // [AA041 / AA012 / AA014] Program Control Bit Modification (EDIT PRG CTL BIT & EDIT BIT 1..4)
    // ---------------------------------------------------------------------------------------------
    s.editPrgCtlBit = s.insnT3 && s.samBt && !s.oplCde6 && s.prgCtl

    s.editBit1 = s.editPrgCtlBit && !s.y8 && !s.y9
    s.editBit2 = s.editPrgCtlBit &&  s.y8 && !s.y9
    s.editBit3 = s.editPrgCtlBit && !s.y8 &&  s.y9
    s.editBit4 = s.editPrgCtlBit &&  s.y8 &&  s.y9

    // ---------------------------------------------------------------------------------------------
    // [AA012 / AA014] Write Data Bits 1..4 and Current Source Inhibit Logic (CURR SOURCE 1..4)
    // ---------------------------------------------------------------------------------------------
    val raw1 = (s.wrPBfr && s.pBfr1) || (s.wrAcc && s.acc1)
    val raw2 = (s.wrPBfr && s.pBfr2) || (s.wrAcc && s.acc2)
    val raw3 = (s.wrPBfr && s.pBfr3) || (s.wrAcc && s.acc3)
    val raw4 = (s.wrPBfr && s.pBfr4) || (s.wrAcc && s.acc4)

    // Incremental Jump bit-5 write bypass at I-2, B5-8 (preserves ACC 1 = bit 5 of A-Word while clearing bits 6..8)
    val incrJumpBit5 = s.insnT2 && s.acc1 && s.athOp

    s.writeBit1 = (!s.editBit1 && raw1) || (s.editBit1 && s.oplCde5) || incrJumpBit5
    s.writeBit2 = (!s.editBit2 && raw2) || (s.editBit2 && s.oplCde5)
    s.writeBit3 = (!s.editBit3 && raw3) || (s.editBit3 && s.oplCde5)
    s.writeBit4 = (!s.editBit4 && raw4) || (s.editBit4 && s.oplCde5)

    // CURR SOURCE i is the Inhibit Current Driver enable for Plane i:
    // Active during TWR (!inhibitMemory) when we want to write a 0 (!writeBit_i) to oppose the X/Y write current.
    s.currSource1 = s.twr && !s.inhibitMemory && !s.writeBit1
    s.currSource2 = s.twr && !s.inhibitMemory && !s.writeBit2
    s.currSource3 = s.twr && !s.inhibitMemory && !s.writeBit3
    s.currSource4 = s.twr && !s.inhibitMemory && !s.writeBit4

    // ---------------------------------------------------------------------------------------------
    // [BA011] Even / Odd Sense Inhibit Lines (SENSE INH LINE EV 1..4 / ODD 1..4)
    // ---------------------------------------------------------------------------------------------
    // During TWR: ROWE is active at Even bit times (B1-4, B9-12); REWO is active at Odd bit times (B5-8, B13-16)
    s.senseInhLineEv1  = s.currSource1 && s.roweGate
    s.senseInhLineEv2  = s.currSource2 && s.roweGate
    s.senseInhLineEv3  = s.currSource3 && s.roweGate
    s.senseInhLineEv4  = s.currSource4 && s.roweGate

    s.senseInhLineOdd1 = s.currSource1 && s.rewoGate
    s.senseInhLineOdd2 = s.currSource2 && s.rewoGate
    s.senseInhLineOdd3 = s.currSource3 && s.rewoGate
    s.senseInhLineOdd4 = s.currSource4 && s.rewoGate
  }
}
