package mtsc.cpu.ild

import mtsc.periph.{SystemIoBus, TapeCartridge, TapeFrame, TapeReader}
import mtsc.types.Device

/**
 * Gate- and latch-level implementation of **ILD Pages `0301` and `0302`** (*Tape Deck Magnet and
 * Solenoid Logic* and *MT Counter, Data Latches, MT Bits, Parity*), plus the operational sequences
 * documented in **MDM Flowcharts `0401`, `0402`, and `0403`** (*MT Reader Cycle Clutch, Load Right/Left,
 * Search Right/Left, Reread, Skip, Unload Left/Right*).
 *
 * Corresponds to SMS/SLD cards:
 * - `5806469 MT CTR` (`F1/2`): `[AA191]`, `[AA192]`, `[AA193]`
 * - `5806470 MT MAG LOGIC` (`H1/2`): `[AA201]`, `[AA202]`
 * - `5806471 MT DATA REG` (`G1/2`): `[AA211]`, `[AA212]`
 * - `5806472 MT INTEG` (`K1/2`): `[AA222]`, `[BC051]`
 */
object Ild0301To0302MagneticTape {

  /**
   * Evaluates all combinational networks on **ILD `0301` and `0302`**:
   * - `MT CDE` (`!Y8 & !Y10`) and `RD COM` (`IN STRB & B13-16`)
   * - Counter preset/reset buses: `SET 0`, `SET 14`, `SET 15`, `COM RES`
   * - 5-bit `MT CTR A..E` state decodes (`Count 0`..`Count 10`, `13 Count`, `14 Count`, `15 Count`)
   * - 3-group odd parity checker (`X PRTY`, `Y PRTY`, `Z PRTY` -> `ODD`) and `ERROR LITE`
   * - `MT BITS AA211` gating `DATA LATCH 1..8` onto `inBit1..8`
   * - Solenoid, clutch, and step magnet logic (`CYC CL`, `SCH POS MAG`, `LD SCH SOL`, `STEP MAGS`, `DNT RT/LF SOL`, `LF PP SOL`, `HI BIAS`)
   * - `CAR RET` (`!RWD SEN & 10 CT & ODD & DL3 & !DL6`) and `CHAR RDY` (`ILD 0311`)
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    // ---------------------------------------------------------------------------------------------
    // 1. CCU Magnetic Tape Device Code & Read Command Decode (ILD 0302 [AA072, AA211])
    // ---------------------------------------------------------------------------------------------
    s.mtCde = !s.y8 && !s.y10
    s.rdCom = s.inStrb && s.b13_16

    // ---------------------------------------------------------------------------------------------
    // 2. Counter Preset & Reset Buses (ILD 0302 [AA191, AA192, AA202])
    // ---------------------------------------------------------------------------------------------
    s.mtComRes = s.mtEotOrUnld || s.masterReset
    s.mtSet15  = s.mtLdRt || s.mtLdLf
    s.mtSet14  = (s.mtSchKey && !s.mtComRes) || s.mtComRes
    s.mtSet0   = (s.rdCom && s.mtCde) || (s.mtHome && ((s.mtRd && !s.mtDntOut) || s.mtReread))

    // ---------------------------------------------------------------------------------------------
    // 3. 5-Bit MT Counter Decodes (ILD 0301 [AA192] & ILD 0302 [AA211])
    // ---------------------------------------------------------------------------------------------
    val a = s.mtCtrA
    val b = s.mtCtrB
    val c = s.mtCtrC
    val d = s.mtCtrD
    val e = s.mtCtrE

    s.mtCount0   = !a && !b && !c &&  d && !e // 00010 (Count 0)
    s.mtCount1   =  a && !b && !c && !d && !e // 10000 (Count 1 -> DL1 / R1)
    s.mtCount2   =  a &&  b && !c && !d && !e // 11000 (Count 2 -> DL2 / R2)
    s.mtCount3   = !a &&  b &&  c && !d && !e // 01100 (Count 3 -> DL3 / R2A)
    s.mtCount4   = !a && !b &&  c && !d && !e // 00100 (Count 4 -> DL4 / R5)
    s.mtCount5   =  a && !b &&  c &&  d && !e // 10110 (Count 5 -> DL8 / SCH)
    s.mtCount6   =  a &&  b &&  c &&  d && !e // 11110 (Count 6 -> DL5 / T1)
    s.mtCount7   = !a &&  b && !c &&  d &&  e // 01011 (Count 7 -> DL6 / T2)
    s.mtCount8   = !a && !b && !c &&  d &&  e // 00011 (Count 8 -> DL9 / CK)
    s.mtCount9   =  a && !b && !c && !d &&  e // 10001 (Count 9 -> DL7 / SHIFT)
    s.mtTenCount =  a &&  b && !c && !d &&  e // 11001 (10 Count)

    val allOnes  =  a &&  b &&  c &&  d &&  e // 11111 (13 Count Unload / 14 Count Search)
    s.mtCount13  = allOnes && s.mtEotOrUnld
    s.mtCount14  = allOnes && !s.mtEotOrUnld
    s.mtCount15  = !a &&  b && !c &&  d && !e // 01010 (15 Count Load)

    // ---------------------------------------------------------------------------------------------
    // 4. 3-Group Odd Parity Network (ILD 0302 [AA212])
    // ---------------------------------------------------------------------------------------------
    // Group X (Data Latches 1, 2, 3): 4 three-input AND gates detect even parity, inverted by N
    val xEven =
      (!s.mtData1 && !s.mtData2 && !s.mtData3) ||
      (!s.mtData1 &&  s.mtData2 &&  s.mtData3) ||
      ( s.mtData1 && !s.mtData2 &&  s.mtData3) ||
      ( s.mtData1 &&  s.mtData2 && !s.mtData3)
    s.mtXParity = !xEven

    // Group Y (Data Latches 4, 5, 6): 4 three-input AND gates detect even parity, inverted by N
    val yEven =
      (!s.mtData4 && !s.mtData5 && !s.mtData6) ||
      (!s.mtData4 &&  s.mtData5 &&  s.mtData6) ||
      ( s.mtData4 && !s.mtData5 &&  s.mtData6) ||
      ( s.mtData4 &&  s.mtData5 && !s.mtData6)
    s.mtYParity = !yEven

    // Group Z (Data Latches 7, 8, 9): 4 three-input AND gates detect even parity, inverted by N
    val zEven =
      (!s.mtData7 && !s.mtData8 && !s.mtData9) ||
      (!s.mtData7 &&  s.mtData8 &&  s.mtData9) ||
      ( s.mtData7 && !s.mtData8 &&  s.mtData9) ||
      ( s.mtData7 &&  s.mtData8 && !s.mtData9)
    s.mtZParity = !zEven

    // Final ODD gate ([AA212]): odd number of X, Y, Z groups active, or forced by SKIP
    val xyzOdd =
      ( s.mtXParity && !s.mtYParity && !s.mtZParity) ||
      (!s.mtXParity &&  s.mtYParity && !s.mtZParity) ||
      (!s.mtXParity && !s.mtYParity &&  s.mtZParity) ||
      ( s.mtXParity &&  s.mtYParity &&  s.mtZParity)
    s.mtOddParity = xyzOdd || s.mtSkip

    // ---------------------------------------------------------------------------------------------
    // 5. MT BITS [AA211] Input Bus Gating (ILD 0302)
    // ---------------------------------------------------------------------------------------------
    if (s.mtCde && (s.mtTenCount || s.mtDataLatchesMask != 0)) {
      s.inBit1 = s.mtData4 // MT BIT 1 (R5, Count 4)
      s.inBit2 = s.mtData2 // MT BIT 2 (R2, Count 2)
      s.inBit3 = s.mtData5 // MT BIT 3 (T1, Count 6)
      s.inBit4 = s.mtData6 // MT BIT 4 (T2, Count 7)
      s.inBit5 = s.mtData1 // MT BIT 5 (R1, Count 1)
      s.inBit6 = s.mtData3 // MT BIT 6 (R2A, Count 3)
      s.inBit7 = s.mtData7 // MT BIT 7 (SHIFT, Count 9)
      s.inBit8 = s.mtData8 // MT BIT 8 (SCH, Count 5)
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Station Selection, Search, Carrier Return, Error Light & Solenoids (ILD 0301 & 0302)
    // ---------------------------------------------------------------------------------------------
    s.mtStaLeft = !s.mtStaRight && !s.mtLdRt
    s.mtSch     = s.trSchSta || s.mtCount14
    s.mtResDl3  = !s.mtSchPos && s.mtSch && s.mtStaLeft
    s.mtCarRet  = !s.mtRwdSen && s.mtTenCount && s.mtOddParity && s.mtData3 && !s.mtData6

    // [AA202] ERROR LITE: active on EOT/Unload or even parity at 10 Count when not in Read/Search
    val parityFaultAt10 =
      !s.trSchSta && !s.mtOddParity && s.mtTenCount && !(s.mtRd || s.trSchSta || s.mtCount14)
    s.errorLite = s.mtEotOrUnld || parityFaultAt10

    // [AA201 / AA202] Cycle Clutch, Search Position Magnet, Load/Search Solenoid, Step Magnets & Detents
    val ppActive = (s.mtStaRight && s.mtRtPpUp) || (s.mtStaLeft && s.mtLfPpUp)
    val ccBasic  = ppActive && !s.mtSchPos && !c && !e && !s.mtReread && !s.mtCount15 && !s.mtTenCount && !s.mtCount13
    val ccRead   = s.mtTenCount && s.mtRd && !s.mtSchPos && !s.mtSch
    val ccSearch = s.mtSch && !s.mtRd && s.mtHome
    s.mtCycClutch = !s.masterReset && (ccBasic || ccRead || ccSearch)

    val schPosOr  = (!s.mtRd && e && s.mtHome && s.mtSch) || (!s.mtRd && s.mtSchPos && s.mtSch)
    s.mtSchPosMag = !schPosOr
    s.mtLdSchSol  = s.mtCount15 || schPosOr
    s.mtStepMags  = s.mtIncr && ppActive

    s.mtDntRtSol =
      !s.masterReset && (
        (!s.mtRwdSen && s.mtStaRight && s.mtCount15 && !s.mtRd) ||
        (s.mtCount14 && s.mtRtPpUp && s.mtSchPos) ||
        (s.mtStaRight && s.mtLdSchSolSw)
      )
    s.mtDntLfSol =
      !s.masterReset && (
        (!s.mtRwdSen && s.mtCount15 && !s.mtRd && s.mtStaLeft) ||
        (s.trSchSta && s.mtSchPos) ||
        (s.mtStaLeft && s.mtLdSchSolSw)
      )
    s.mtLfPpSol = s.mtStaLeft && !s.mtEotOrUnld
    s.mtHiBias  = s.mtEotOrUnld

    // [ILD 0311 AA232 / AA233 / AA212] +CHARACTER READY
    val mtCharRdy = s.mtTenCount && s.mtCde && !s.mtSch && !s.mtRd && !s.trSchSta && !s.errorLite
    s.charRdy = mtCharRdy || s.ptCharRdy
  }

  /**
   * Updates the 5-bit Magnetic Tape Counter (`MT CTR A..E` on `[AA191]`, `[AA192]`, `ILD 0302`)
   * in response to preset buses (`SET 0`, `SET 14`, `SET 15`, `COM RES`) or emitter pulses (`MPS A`, `MPS B`).
   */
  def updateCounter(s: BackplaneSignals): Unit = {
    evaluateCombinational(s)

    val a = s.mtCtrA
    val b = s.mtCtrB
    val c = s.mtCtrC
    val d = s.mtCtrD

    // Asynchronous preset / reset buses (SET 0, SET 14 / COM RES, SET 15)
    if (s.mtSet14) {
      // SET 14 (Search) or COM RES (13 Count Unload): sets A, B, C, D, E = 11111
      s.mtCtrA = true
      s.mtCtrB = true
      s.mtCtrC = true
      s.mtCtrD = true
      s.mtCtrE = true
    } else if (s.mtSet15) {
      // SET 15 (Load): sets B, D and resets A, C, E = 01010
      s.mtCtrA = false
      s.mtCtrB = true
      s.mtCtrC = false
      s.mtCtrD = true
      s.mtCtrE = false
    } else if (s.mtSet0) {
      // SET 0: sets D and resets A, B, C, E = 00010 (Count 0)
      s.mtCtrA = false
      s.mtCtrB = false
      s.mtCtrC = false
      s.mtCtrD = true
      s.mtCtrE = false
    }

    // Emitter Pulse MPS A (odd emitter coils: steps 0->1, 2->3, 4->5, 6->7, 8->9)
    if (s.mpsA) {
      val setA = !(s.mtSch && !s.mtRd) && !b
      val resA = b
      val setC = b && !d
      val resC = b && d
      val setD = !a && !b && c
      val resD = !a && !b && !c
      val setE = b && c && d

      if (setA) s.mtCtrA = true
      else if (resA) s.mtCtrA = false

      if (setC) s.mtCtrC = true
      else if (resC) s.mtCtrC = false

      if (setD) s.mtCtrD = true
      else if (resD) s.mtCtrD = false

      if (setE) s.mtCtrE = true
    }

    // Emitter Pulse MPS B (even emitter coils: steps 1->2, 3->4, 5->6, 7->8, 9->10)
    if (s.mpsB) {
      if (a) s.mtCtrB = true
      else s.mtCtrB = false
    }

    evaluateCombinational(s)
  }

  /**
   * Updates the 9 Magnetic Tape Data Latches (`DATA LTHS 1..9` on `[AA211]`, `ILD 0302`)
   * and the `MT SET LO` flip-flop (`[AA193]`, `ILD 0302`).
   */
  def updateDataLatches(s: BackplaneSignals): Unit = {
    evaluateCombinational(s)

    if (s.mtSet0 || s.masterReset) {
      s.mtData1 = false
      s.mtData2 = false
      s.mtData3 = false
      s.mtData4 = false
      s.mtData5 = false
      s.mtData6 = false
      s.mtData7 = false
      s.mtData8 = false
      s.mtData9 = false
    }

    if (s.mtResDl3) {
      s.mtData3 = false
    }

    // Gate MT BIT into the Data Latch selected by the current MT CTR decode (Counts 1..9)
    if (s.mtBit) {
      if (s.mtCount1) s.mtData1 = true // Count 1 (10000) -> DL1 (R1 / MT BIT 5)
      if (s.mtCount2) s.mtData2 = true // Count 2 (11000) -> DL2 (R2 / MT BIT 2)
      if (s.mtCount3) s.mtData3 = true // Count 3 (01100) -> DL3 (R2A / MT BIT 6)
      if (s.mtCount4) s.mtData4 = true // Count 4 (00100) -> DL4 (R5 / MT BIT 1)
      if (s.mtCount5) s.mtData8 = true // Count 5 (10110) -> DL8 (SCH / MT BIT 8)
      if (s.mtCount6) s.mtData5 = true // Count 6 (11110) -> DL5 (T1 / MT BIT 3)
      if (s.mtCount7) s.mtData6 = true // Count 7 (01011) -> DL6 (T2 / MT BIT 4)
      if (s.mtCount8) s.mtData9 = true // Count 8 (00011) -> DL9 (CK / Parity Only)
      if (s.mtCount9) s.mtData7 = true // Count 9 (10001) -> DL7 (SHIFT / MT BIT 7)
    }

    // [AA193] MT SET LO flip-flop: set by SKIP, reset by (MT CDE & RD COM) or COM RES
    if ((s.mtCde && s.rdCom) || s.mtComRes) {
      s.setLo = false
    }
    if (s.mtSkip) {
      s.setLo = true
    }

    evaluateCombinational(s)
  }

  /**
   * Updates the 4 transport control flip-flops on **ILD `0301`**:
   * `STA RT` (`[AA202]`), `TRF SCH STA` (`[AA202]`), `MT RD` (`[AA191]`), and `INCR` (`[AA191]`).
   */
  def updateTransportLatches(s: BackplaneSignals): Unit = {
    evaluateCombinational(s)

    val ccuSelectsLeft = s.y9 || s.y11

    // 1. [AA202] STA RT (Station Right) flip-flop
    val setStaRt = (s.mtCde && s.rdCom && !ccuSelectsLeft) || s.mtCount14 || s.mtLdRt
    val resStaRt = (s.mtHome && !s.mtRd && s.mtTenCount && s.trSchSta) || (s.mtCde && ccuSelectsLeft && s.rdCom) || s.mtLdLf
    if (setStaRt) {
      s.mtStaRight = true
    } else if (resStaRt) {
      s.mtStaRight = false
    }
    s.mtStaLeft = !s.mtStaRight && !s.mtLdRt

    // 2. [AA191] MT RD (Magnetic Tape Read) flip-flop
    val setMtRd = (s.mtRwdSen && s.mtCount15) || (s.mtDntOut && s.mtSchPos && s.mtSchBit)
    val resMtRd = s.mtCarRet || s.mtComRes
    if (resMtRd) {
      s.mtRd = false
    } else if (setMtRd) {
      s.mtRd = true
    }

    // 3. [AA202] TRF SCH STA (Transfer Search Station) flip-flop
    val setTrfSch = !s.mtLdrSenLfEmpty && s.mtCount14
    val resTrfSch = s.mtComRes || s.mtSet15 || (s.mtStaLeft && s.mtRd)
    if (resTrfSch) {
      s.trSchSta = false
    } else if (setTrfSch) {
      s.trSchSta = true
    }

    // 4. [AA191] INCR (Increment) flip-flop
    val setIncr = s.mtTenCount && ((!s.mtSch && s.mtOddParity) || s.mtRd)
    val resIncr = s.mtHome || s.mtComRes
    if (setIncr) {
      s.mtIncr = true
    } else if (resIncr) {
      s.mtIncr = false
    }

    evaluateCombinational(s)
  }

  /**
   * Returns whether the given physical track number (`1..9`, corresponding to `Count 1..Count 9`)
   * has a magnetic flux transition (`1` bit) for the given [[TapeFrame]].
   *
   * Track order per **MDM `0403` (`MT READER COUNTER AND BIT DECODE`)**:
   * - `Count 1`: Track `R1` (`MT BIT 5`, mask `0x10`) -> `DATA LATCH 1`
   * - `Count 2`: Track `R2` (`MT BIT 2`, mask `0x02`) -> `DATA LATCH 2`
   * - `Count 3`: Track `R2A` (`MT BIT 6`, mask `0x20`) -> `DATA LATCH 3`
   * - `Count 4`: Track `R5` (`MT BIT 1`, mask `0x01`) -> `DATA LATCH 4`
   * - `Count 5`: Track `SCH` (`MT BIT 8`, mask `0x80`) -> `DATA LATCH 8`
   * - `Count 6`: Track `T1` (`MT BIT 3`, mask `0x04`) -> `DATA LATCH 5`
   * - `Count 7`: Track `T2` (`MT BIT 4`, mask `0x08`) -> `DATA LATCH 6`
   * - `Count 8`: Track `CK` (Parity Check Only) -> `DATA LATCH 9`
   * - `Count 9`: Track `SHIFT` (`MT BIT 7`, mask `0x40`) -> `DATA LATCH 7`
   */
  def trackBitForCount(frame: TapeFrame, count: Int): Boolean = {
    // 7-bit EndOfBlock (0xFF) sentinel is treated as all-ones with odd parity (9 ones)
    val isEobSentinel = !frame.is8Bit && frame.rawByte == TapeCartridge.EndOfBlock
    val b = if (isEobSentinel) 0xff else frame.dataByte
    count match {
      case 1 => (b & 0x10) != 0
      case 2 => (b & 0x02) != 0
      case 3 => (b & 0x20) != 0
      case 4 => (b & 0x01) != 0
      case 5 => (b & 0x80) != 0
      case 6 => (b & 0x04) != 0
      case 7 => (b & 0x08) != 0
      case 8 => if (isEobSentinel) true else frame.parityBit
      case 9 => (b & 0x40) != 0
      case _ => false
    }
  }

  /**
   * Pulses a single emitter coil (`MPS A` for odd counts `1, 3, 5, 7, 9`, or `MPS B` for even counts `2, 4, 6, 8, 10`)
   * and samples the corresponding track of `frame` into `DATA LATCH 1..9`.
   */
  def stepEmitterPulse(s: BackplaneSignals, isMpsA: Boolean, frameOpt: Option[TapeFrame] = None): Unit = {
    // Once emitter sweep begins, the read head has left HOME
    s.mtHome = false
    s.mpsA = isMpsA
    s.mpsB = !isMpsA
    updateCounter(s)
    s.mpsA = false
    s.mpsB = false

    val count = s.mtCounterNumber
    s.mtBit = frameOpt.exists(f => trackBitForCount(f, count))
    s.mtSchBit = if (count == 5) s.mtBit else false
    updateDataLatches(s)
    s.mtBit = false

    if (s.mtTenCount) {
      updateTransportLatches(s)
    } else {
      evaluateCombinational(s)
    }
  }

  /**
   * Executes one complete 10-step Magnetic Tape read head sweep (`Count 0 -> Count 1 .. Count 9 -> Count 10`)
   * across `frame` via alternating `MPS A` and `MPS B` emitter pulses (`MDM 0401` & `0403`).
   */
  def sweepFrame(s: BackplaneSignals, frame: TapeFrame): Unit = {
    if (!(s.mtSch && !s.mtRd) && !s.mtEotOrUnld && !s.masterReset && !s.mtLdRt && !s.mtLdLf && !s.mtSchKey) {
      // Fast path for normal 10-step read sweep (Count 0 -> Count 10):
      s.mtHome = false
      s.mtReread = false
      s.mpsA = false
      s.mpsB = false
      s.mtBit = false
      s.mtSchBit = false
      s.mtIncr = false

      // Advance counter directly to 10 Count (11001)
      s.mtCtrA = true
      s.mtCtrB = true
      s.mtCtrC = false
      s.mtCtrD = false
      s.mtCtrE = true

      // Latch all 9 tracks into DATA LATCH 1..9 per MDM 0403 track mapping
      val isEobSentinel = !frame.is8Bit && frame.rawByte == TapeCartridge.EndOfBlock
      val b = if (isEobSentinel) 0xff else frame.dataByte
      s.mtData1 = (b & 0x10) != 0
      s.mtData2 = (b & 0x02) != 0
      s.mtData3 = (b & 0x20) != 0 && !(!s.mtSchPos && s.mtSch && s.mtStaLeft)
      s.mtData4 = (b & 0x01) != 0
      s.mtData5 = (b & 0x04) != 0
      s.mtData6 = (b & 0x08) != 0
      s.mtData7 = (b & 0x40) != 0
      s.mtData8 = (b & 0x80) != 0
      s.mtData9 = if (isEobSentinel) true else frame.parityBit

      if (!s.y8 && !s.y10 && s.inStrb && s.b13_16) {
        s.setLo = false
      }
      if (s.mtSkip) {
        s.setLo = true
      }

      updateTransportLatches(s)
      return
    }

    // 1. At HOME: reset counter to Count 0 (00010), clear DATA LATCH 1..9, and reset INCR via SET 0
    s.mtHome = true
    s.mtReread = true
    evaluateCombinational(s)
    updateCounter(s)
    updateDataLatches(s)
    updateTransportLatches(s)
    s.mtReread = false
    s.mtHome = false
    evaluateCombinational(s)

    // 2. Sweep the 5 emitter coil pairs (MPS A #1..5, MPS B #1..5) from Count 0 to Count 10
    var pair = 0
    while (pair < 5) {
      stepEmitterPulse(s, isMpsA = true, Some(frame))
      stepEmitterPulse(s, isMpsA = false, Some(frame))
      pair += 1
    }
  }

  /**
   * Synchronizes the mechanical station sensor contacts (`RT PP UP`, `LF PP UP`, `LDR SEN LF E`)
   * from `ioBus.rightTape` and `ioBus.leftTape`.
   */
  def syncStationSensors(s: BackplaneSignals, ioBus: SystemIoBus): Unit = {
    s.mtRtPpUp = ioBus.rightTape.isTapeLoaded
    s.mtLfPpUp = ioBus.leftTape.isTapeLoaded
    s.mtLdrSenLfEmpty = !ioBus.leftTape.isTapeLoaded
    evaluateCombinational(s)
  }

  /**
   * Prepares the Magnetic Tape Reader state for the currently selected station when an `INPUT`
   * instruction addresses `RightTape` or `LeftTape` (`mtCde = true`).
   */
  def prepareStationForInput(s: BackplaneSignals, ioBus: SystemIoBus): Unit = {
    syncStationSensors(s, ioBus)
    val isLeft = s.y9 || s.y11
    s.mtStaRight = !isLeft
    s.mtStaLeft  = isLeft

    val reader: TapeReader = if (isLeft) ioBus.leftTape else ioBus.rightTape
    if (!reader.isTapeLoaded || reader.cycleRemaining > 0) {
      // Station not ready: counter at Count 0, CHARACTER READY = false
      s.mtHome = true
      s.mtReread = true
      updateCounter(s)
      updateDataLatches(s)
      s.mtReread = false
      evaluateCombinational(s)
      return
    }

    reader.mountedCartridge match {
      case None =>
        s.mtHome = true
        s.mtReread = true
        updateCounter(s)
        updateDataLatches(s)
        s.mtReread = false
        evaluateCombinational(s)

      case Some(cart) =>
        val frame =
          if (cart.isAtEnd(reader.headPosition)) TapeFrame(TapeCartridge.EndOfBlock, is8Bit = false)
          else cart.getFrame(reader.headPosition).get
        sweepFrame(s, frame)
        if (!s.mtOddParity) {
          reader.parityError = true
        }
    }
  }

  /**
   * Executes the `LOAD RIGHT` or `LOAD LEFT` hardware sequence (`MDM 0401` / `ILD 0301–0302`):
   * 1. Asserts `LD RT` or `LD LF` -> `SET 15` (`15 Count = 01010`), sets `STA RT` / `STA LF`,
   *    energizes `LD SCH SOL` and `DNT RT/LF SOL`.
   * 2. Rewind Sense (`RWD SEN`) at `15 Count` sets `MT RD`, drops `DNT SOL`, and `HOME` triggers `SET 0`.
   * 3. Sweeps the leader Feed / Carrier Return code (`DL3 & !DL6` at `10 Count` -> `CAR RET`), resetting `MT RD`.
   * 4. Sweeps the first frame on the loaded cartridge into `DATA LATCH 1..9` at `10 Count`.
   */
  def pressLoad(s: BackplaneSignals, rightStation: Boolean, ioBus: SystemIoBus): Unit = {
    syncStationSensors(s, ioBus)
    s.mtEotOrUnld = false

    // Step 1: Pulse LD RT or LD LF -> SET 15 (15 Count = 01010)
    s.mtLdRt = rightStation
    s.mtLdLf = !rightStation
    s.mtLdSchSolSw = true
    updateCounter(s)
    updateTransportLatches(s)
    s.mtLdRt = false
    s.mtLdLf = false

    // Step 2: Rewind Sense at 15 Count sets MT RD, then HOME triggers SET 0
    s.mtRwdSen = true
    updateTransportLatches(s)
    s.mtRwdSen = false
    s.mtLdSchSolSw = false
    s.mtHome = true
    updateCounter(s)
    updateDataLatches(s)

    // Step 3: Sweep leader Carrier Return / Feed code (DL3=1, DL6=0, ODD=1 -> CAR RET resets MT RD)
    sweepFrame(s, TapeFrame.encodeWithOddParity(0x20))
    updateTransportLatches(s)

    // Step 4: Sweep first frame of the newly loaded cartridge to 10 Count
    val reader = if (rightStation) ioBus.rightTape else ioBus.leftTape
    reader.mountedCartridge.flatMap(_.getFrame(reader.headPosition)).foreach { firstFrame =>
      sweepFrame(s, firstFrame)
    }
  }

  /**
   * Executes the `UNLOAD RIGHT` or `UNLOAD LEFT` hardware sequence (`MDM 0403` / `ILD 0301–0302`):
   * Asserts `EOT + UNLD` -> `COM RES`, setting `13 Count` (`11111`), energizing `HI BIAS` and `DNT SOL`,
   * and lighting `ERROR LITE`.
   */
  def pressUnload(s: BackplaneSignals, rightStation: Boolean, ioBus: SystemIoBus): Unit = {
    syncStationSensors(s, ioBus)
    s.mtStaRight = rightStation
    s.mtStaLeft  = !rightStation
    s.mtUnldRt   = rightStation
    s.mtUnldLf   = !rightStation
    s.mtEotOrUnld = true
    updateCounter(s)
    updateDataLatches(s)
    updateTransportLatches(s)
    s.mtUnldRt = false
    s.mtUnldLf = false
  }

  /**
   * Executes the `SEARCH` hardware sequence (`MDM 0402` / `ILD 0301–0302`):
   * 1. Asserts `SCH KEY` -> `SET 14` (`14 Count = 11111`), sets `STA RT` and `SCH`, and sets `TRF SCH STA`
   *    if a Left cartridge is also loaded (`!LDR SEN LF E & 14 CT`).
   * 2. Senses `SCH BIT` (`DNT OUT & SCH POS & SCH BIT` -> sets `MT RD`), returns `HOME` (`SET 0`),
   *    and sweeps until `CAR RET` (`DL3 & !DL6 & ODD & 10 CT`) resets `MT RD`.
   * 3. If `TRF SCH STA` was set, transfers to `STA LF` at `HOME & !MT RD & 10 CT`, resets `DL3` (`RES DL3`),
   *    searches the Left station (`STA LF & MT RD` resets `TRF SCH STA`), and sweeps until `CAR RET`.
   */
  def pressSearch(s: BackplaneSignals, ioBus: SystemIoBus): Unit = {
    syncStationSensors(s, ioBus)
    s.mtEotOrUnld = false

    // Step 1: Pulse SCH KEY -> SET 14 (14 Count = 11111)
    s.mtSchKey = true
    updateCounter(s)
    updateTransportLatches(s)
    s.mtSchKey = false

    // Step 2: Search Right (or single loaded station) -> sense SCH BIT -> MT RD -> CAR RET
    s.mtSchPos = true
    s.mtDntOut = true
    s.mtSchBit = true
    updateTransportLatches(s)
    s.mtSchPos = false
    s.mtDntOut = false
    s.mtSchBit = false
    s.mtHome = true
    updateCounter(s)
    sweepFrame(s, TapeFrame.encodeWithOddParity(0x20)) // CAR RET resets MT RD
    updateTransportLatches(s)

    // Step 3: If TRF SCH STA was set (dual-station search), transfer to Left Station and search Left
    if (s.trSchSta) {
      s.mtHome = true
      updateTransportLatches(s) // HOME & !MT RD & 10 CT resets STA RT -> STA LF = true
      updateDataLatches(s)      // RES DL3 clears DL3
      s.mtSchPos = true
      s.mtDntOut = true
      s.mtSchBit = true
      updateTransportLatches(s) // Sets MT RD, and STA LF & MT RD resets TRF SCH STA
      s.mtSchPos = false
      s.mtDntOut = false
      s.mtSchBit = false
      s.mtHome = true
      updateCounter(s)
      sweepFrame(s, TapeFrame.encodeWithOddParity(0x20)) // CAR RET resets MT RD
      updateTransportLatches(s)
    }

    // Sweep the frame at the new head position into DATA LATCH 1..9
    val active = if (ioBus.leftTape.isTapeLoaded && !ioBus.rightTape.isTapeLoaded) ioBus.leftTape else ioBus.rightTape
    active.mountedCartridge.flatMap(_.getFrame(active.headPosition)).foreach { frame =>
      sweepFrame(s, frame)
    }
  }

  /**
   * Executes the `REREAD` hardware sequence (`MDM 0403` / `ILD 0301–0302`):
   * Pulses `REREAD` with `HOME` -> `SET 0` (`Count 0`), clearing `DATA LATCH 1..9` and `ERROR LITE`,
   * then re-sweeps the frame at the current `headPosition` to `10 Count`.
   */
  def pressReread(s: BackplaneSignals, ioBus: SystemIoBus): Unit = {
    syncStationSensors(s, ioBus)
    s.mtEotOrUnld = false
    s.mtHome = true
    s.mtReread = true
    updateCounter(s)
    updateDataLatches(s)
    s.mtReread = false

    val active = if (!s.mtStaRight && ioBus.leftTape.isTapeLoaded) ioBus.leftTape
      else if (ioBus.rightTape.isTapeLoaded) ioBus.rightTape
      else ioBus.leftTape
    active.mountedCartridge.flatMap(_.getFrame(active.headPosition)).foreach { frame =>
      sweepFrame(s, frame)
    }
  }

  /**
   * Executes the `SKIP` hardware sequence (`MDM 0403` / `ILD 0301–0302`):
   * Pulses `SKIP` (`mtSkip = true`), which sets the `MT SET LO` flip-flop (`s.setLo = true` on `[AA193]`,
   * causing the CCU to set `+LOW LATCH` at `I-5` of the next `INPUT` instruction) and forces `+ODD`
   * (`[AA212]`) so `10 Count` increments past the bad character and clears `ERROR LITE`.
   */
  def pressSkip(s: BackplaneSignals, ioBus: SystemIoBus): Unit = {
    syncStationSensors(s, ioBus)
    s.mtEotOrUnld = false
    s.mtSkip = true
    s.mtHome = true
    s.mtReread = true
    updateCounter(s)
    updateDataLatches(s)
    s.mtReread = false

    val active = if (!s.mtStaRight && ioBus.leftTape.isTapeLoaded) ioBus.leftTape
      else if (ioBus.rightTape.isTapeLoaded) ioBus.rightTape
      else ioBus.leftTape
    active.mountedCartridge.flatMap(_.getFrame(active.headPosition)).foreach { frame =>
      sweepFrame(s, frame)
    }
    s.mtSkip = false
    evaluateCombinational(s)
  }
}
