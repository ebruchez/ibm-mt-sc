package mtsc.cpu.ild

import mtsc.console.{CeDisplayMode, CeEditSelector, CeStepGranularity, LampState}
import mtsc.cpu.{CcuState, IoBus, IoResult, StepResult}
import mtsc.isa.{Decoder, Instruction}
import mtsc.memory.CoreMemory
import mtsc.periph.{ConsoleKey, ConsoleLampSpec, OperatorPanelState, SystemIoBus, TapeFrame}
import mtsc.types.{Address, Device, Word}

/**
 * Hardware-accurate gate- and latch-level execution engine for the **IBM MT/SC 6320 Composer Control Unit (CCU)**,
 * integrating Intermediate Logic Diagrams **ILDs `0303`–`0312`** and the 3D 4-plane magnetic core array
 * ([[CoreMatrix3D]]).
 *
 * Unlike the behavioral [[mtsc.cpu.Ccu]], `IldCcu` executes instructions by stepping the 240 kHz oscillator
 * single-shots (`SSA` / `SSB`) through every 4-bit nibble (`B1-4`, `B5-8`, `B9-12`, `B13-16`) and every
 * Instruction Time (`I-0`–`I-9`), driving address decoding, destructive core reads, sense amplifiers,
 * `Q-Buffer`, `P-Buffer`, serial 4-bit full adder / borrow subtractor (`ACC 1..4`, `CAR 1..4`, `CAR OVER`),
 * operational code latches (`OPL CDE 1..7`), condition latches (`LOW`, `EQUAL`), permute logic,
 * inhibit-controlled core writes, Translator Output controls (`OUT 1..8`, `MPX`, `PRE INH`, `INH`, `RDY`, `BSY`),
 * and the CE Console `Edit Memory Selector` and 11 indicator lamp multiplexers (`LITE 1..11`).
 */
final class IldCcu(
  val memory: CoreMemory,
  val ioBus: IoBus,
  val state: CcuState = new CcuState(),
  val signals: BackplaneSignals = new BackplaneSignals()
) {

  val coreMatrix: CoreMatrix3D = new CoreMatrix3D(memory)

  /** Sub-bit-time (`240 kHz` `SSA`/`SSB`) oscilloscope waveform trace buffer (`MDM Pages 0601 & 0602`). */
  val timingTrace: IldTimingTraceBuffer = new IldTimingTraceBuffer(192)

  /** Optional evaluator for the 4 CE Test Probe channels (`Test 1..4`) during sub-phase trace recording. */
  var probeMaskEvaluator: BackplaneSignals => Int = _ => 0

  /** Latched output device and multiplex flag captured at `I-2` of `OUTPUT` before `I-4` reloads `Y1..Y11`. */
  private var latchedOutputDevice: Device = Device.Translator
  private var latchedOutputMultiplex: Boolean = false
  private var isMultiplexInterruptCycle: Boolean = false
  private var savedIndirectPtrRegAddr: Int = 0
  private var savedIndirectPtrValue: Int = 0

  /** Sub-instruction (`Bit` / `Word`) stepping state across `I-0 .. I-9`. */
  private var inProgressInstruction: Boolean = false
  private var midInstructionFetchAddr: Address = Address(0)
  private var midInstructionRawWord: Word = Word.Zero
  private var midInstructionPBfrAtI1B1_4: Int = 0
  private var midInstructionQBfrAtI1B5_8: Int = 0

  /** True when the CCU is halted mid-instruction (`I-1..I-9` or `B5-8..B13-16`) via `Bit` or `Word` stepping. */
  def isMidInstruction: Boolean =
    inProgressInstruction || signals.insnTime != 0 || signals.bitTimeIndex != 0

  // Initialize clock and backplane signals to power-on reset state (I-0, B1-4)
  Ild0303Clock.reset(signals)
  syncAllCombinational()

  /**
   * Resets Core Memory, `CcuState`, and all `0303`–`0312` backplane latches to power-on defaults.
   */
  def reset(): Unit = {
    memory.reset()
    state.reset()
    signals.inh = false
    signals.preInh = false
    signals.mpx = false
    signals.rdy = true
    signals.busy = false
    signals.tsltrSel = false
    signals.lites = false
    signals.low = false
    signals.eq = false
    signals.strCarOver = false
    signals.carOver = false
    signals.setLo = false
    signals.mtSkip = false
    signals.mtReread = false
    signals.mtEotOrUnld = false
    signals.setMalValue(0)
    signals.setOutputByte(0)
    inProgressInstruction = false
    midInstructionFetchAddr = Address(0)
    midInstructionRawWord = Word.Zero
    midInstructionPBfrAtI1B1_4 = 0
    midInstructionQBfrAtI1B5_8 = 0
    Ild0303Clock.reset(signals)
    signals.edit = signals.editSwitch != CeEditSelector.Proc
    timingTrace.clear()
    ioBus match {
      case sysBus: SystemIoBus => Ild0301To0302MagneticTape.syncStationSensors(signals, sysBus)
      case _                   => ()
    }
    syncAllCombinational()
    syncStateFromSignals()
  }

  /**
   * Evaluates all combinational networks across ILDs `0301`–`0312`.
   */
  def syncAllCombinational(): Unit = {
    Ild0312ConsoleLights.evaluateSwitchAndDisplayDecodes(signals)
    Ild0303Clock.evaluateCombinational(signals)
    Ild0306To0307Address.evaluateCombinational(signals)
    Ild0305SenseQBuffer.evaluateCombinational(signals)
    Ild0308DataFlow.evaluateCombinational(signals)
    Ild0309OpAndBranch.evaluateCombinational(signals)
    Ild0310WriteInhibit.evaluateWriteNets(signals)
    Ild0301To0302MagneticTape.evaluateCombinational(signals)
    Ild0311OutputIo.evaluateCombinational(signals)
    Ild0312ConsoleLights.evaluateCombinational(signals)
  }

  /**
   * Copies hardware latch states from [[BackplaneSignals]] into [[CcuState]] for inspection,
   * CE Console display lamps, and lockstep comparison against [[mtsc.cpu.Ccu]].
   */
  def syncStateFromSignals(): Unit = {
    state.low = signals.low
    state.equal = signals.eq
    state.inhibit = signals.inh
    state.multiplex = signals.mpx
    state.mal = Address(signals.malValue)
    state.outputReg = signals.outputByte
    state.opCodeLatches = signals.packedOpCodeLatches
    state.qBuffer = signals.qBufferNibble
    state.pBuffer = signals.pBufferNibble
    state.insnTimeCtr = signals.insnTime
    state.bitTimeCtr = signals.bitTimeIndex
  }

  /**
   * Synchronizes [[BackplaneSignals]] latches from [[CcuState]] (e.g. when switching engine modes
   * or after external state updates).
   */
  def syncSignalsFromState(): Unit = {
    signals.low = state.low
    signals.eq = state.equal
    signals.inh = state.inhibit
    signals.mpx = state.multiplex
    signals.setMalValue(state.mal.unsigned)
    signals.setOutputByte(state.outputReg)
    signals.oplCde1 = state.opBit(1)
    signals.oplCde2 = state.opBit(2)
    signals.oplCde3 = state.opBit(3)
    signals.oplCde4 = state.opBit(4)
    signals.oplCde5 = state.opBit(5)
    signals.oplCde6 = state.opBit(6)
    signals.oplCde7 = state.opBit(7)
    syncAllCombinational()
  }

  /**
   * Non-destructively populates [[timingTrace]] with a 2-Word-Time (`I-0` Fetch + `I-1` Decode/Execute)
   * preview waveform from the current `PSAR` if [[timingTrace]] is empty, without mutating
   * `memory`, `state`, or `signals`. Automatically cleared on the first live step.
   */
  def seedPreviewTraceIfEmpty(): Unit = {
    if (timingTrace.size > 0) return
    val scratchMem = CoreMemory.empty()
    var a = 0
    while (a < 2048) {
      scratchMem.writeByte(Address(a), memory.readByte(Address(a)))
      a += 1
    }
    scratchMem.psar = memory.psar
    scratchMem.aWord = memory.aWord
    scratchMem.bWord = memory.bWord
    val scratchIld = new IldCcu(scratchMem, new mtsc.cpu.MockIoBus())
    scratchIld.probeMaskEvaluator = this.probeMaskEvaluator
    scratchIld.stepSingleWordTime()
    scratchIld.stepSingleWordTime()
    timingTrace.seedPreview(scratchIld.timingTrace.recentSamples(192))
  }

  /**
   * Advances the CCU by one complete Bit Time (`B1-4`, `B5-8`, `B9-12`, or `B13-16`),
   * executing all sub-phase clock transitions (`Indicator Load`, `Read P`, `BMP STRB T`,
   * optional `Read Q`, `Write Q`, `Write P`, and `Post-Write Transition`) across `0303`–`0310`
   * and [[CoreMatrix3D]].
   *
   * Returns the number of single-shot pulses (`SSA` + `SSB`) executed (8 for a `P-Only` bit time,
   * 12 for a `P & Q` bit time).
   */
  def stepBitTime(): Int = stepBitTimeCore(syncAtEnd = true)

  /**
   * Fast internal Bit-Time execution engine when `timingTrace.enabled == false`.
   * Collapses intermediate inter-pulse gap sub-phases (`SsaOff`, `SsbOff`) and the 4-sub-phase
   * Post-Write transition (`Cycle 5`), and evaluates the 4-bit serial adder and address/write
   * decoders only at active core read/write strobes.
   */
  private def stepBitTimeFast(syncAtEnd: Boolean): Int = {
    val s = signals
    var pulses = 0
    val savedClockSwitch = s.clockSwitch
    s.clockSwitch = CeStepGranularity.Auto
    s.stpClk1 = false
    s.stpClk2 = false

    // Ensure we are past SSA #1 of the current Bit Time
    if (s.a && s.b && s.pTime && !s.cenBt) {
      Ild0303Clock.pulseSsaOn(s)
      pulses += 1
      Ild0309OpAndBranch.updateOpCodeLatches(s)
      Ild0308DataFlow.updateCarryIndicator(s)
      Ild0303Clock.pulseSsaOff(s)
    }

    // Sample EDIT switch and clear OPL CDE 1..7 at BEGINNING PROGRAM STEP (I-0, B1-4, P TIME, -CEN BT)
    if (s.begPrgStp) {
      Ild0312ConsoleLights.updateLatches(s)
    }
    if (s.insnT0 && s.b1_4) {
      s.oplCde1 = false
      s.oplCde2 = false
      s.oplCde3 = false
      s.oplCde4 = false
      s.oplCde5 = false
      s.oplCde6 = false
      s.oplCde7 = false
      Ild0304Permute.evaluateCombinational(s)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Cycle 1 SSB: Buffer & Y-Latch Reset before Read P (-A & -B & -TWR & +P TIME & -CEN BT)
    // ---------------------------------------------------------------------------------------------
    s.oscTrigger = false
    s.ssa = false
    s.ssb = false
    s.b = false
    s.resInds = false
    s.ldInds = false
    s.resBfr = true
    s.resPBfr = true
    s.qBfr1 = false
    s.qBfr2 = false
    s.qBfr3 = false
    s.qBfr4 = false
    s.pBfr1 = false
    s.pBfr2 = false
    s.pBfr3 = false
    s.pBfr4 = false
    pulses += 1
    Ild0306To0307Address.updateYLatches(s)

    // At I-5, B1-4 of INPUT: poll the addressed input device on IoBus before Read P / CEN BT
    if (s.insnT5 && s.b1_4 && s.input) {
      prepareInputDeviceAtI5()
    }

    // At I-3, B1-4 of Indirect Output: check output device readiness before pointer bump
    if (s.insnT3 && s.b1_4 && s.output) {
      if (isMultiplexInterruptCycle) {
        s.rdy = true
      } else {
        val regAddr = s.malValue & 0x3e
        savedIndirectPtrRegAddr = regAddr
        savedIndirectPtrValue = memory.readWord(Address(regAddr)).unsigned
        s.rdy = if (latchedOutputDevice == Device.Translator) ioBus.isTranslatorReady else true
      }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Cycle 2 SSA/SSB: Read P-Time (+A & -B -> +A & +B, +P TIME, +CEN BT, -TWR)
    // ---------------------------------------------------------------------------------------------
    s.a = true
    s.b = false
    s.twr = false
    s.pTime = true
    s.cenBt = true
    s.notPTimeStar = true
    s.notBAndNotTwr = true
    s.bmpStrbT = false
    s.strLthStrobe = false
    s.prmtTime = false
    s.begPrgStp = false
    s.resBfr = false
    s.resPBfr = false
    pulses += 1

    // In Incremental Jump at I-2, B5-8: INCR JMP RES Y resets Y2, Y3, Y4 prior to loading Y4..Y8
    if (s.b5_8 && s.oplCde1 && s.insnT2) {
      s.ssa = true
      Ild0306To0307Address.updateYLatches(s)
      s.ssa = false
    } else {
      Ild0306To0307Address.evaluateCombinational(s)
    }
    Ild0305SenseQBuffer.evaluateCombinational(s)

    // Core Read P: destructive read of the 4 selected P-cores; SSA #2 OFF resets Q BFR 1..4 while P BFR 1..4 holds P-nibble
    val suppressStalledInputClear = s.insnT5 && s.input && s.inh
    s.csYLog = !s.inhibitMemory && !s.killCsYUpperByte && !suppressStalledInputClear
    s.readGate = s.csYLog
    val pRawSense = coreMatrix.destructiveReadNibble(s)
    val pNibble = if (s.readGate && !s.inhRd) pRawSense else 0
    s.pBfr1 = (pNibble & 1) != 0
    s.pBfr2 = (pNibble & 2) != 0
    s.pBfr3 = (pNibble & 4) != 0
    s.pBfr4 = ((pNibble & 8) != 0) && !(s.b9_12 && s.insnT0)
    s.csYLog = false
    s.readGate = false

    // SSB #2 ON/OFF (CEN BT active): load Input bits, Keyboard Entry Strobe, Output latches, Y1..Y11, and OPL CDE 1..7
    s.b = true
    s.notBAndNotTwr = false
    s.resBfr = false
    pulses += 1

    if (s.kbCodeRdy || s.lnStpLth) {
      Ild0308DataFlow.updatePBuffer(s)
    }

    if (s.insnT5 && s.input && !s.inh) {
      if (s.b1_4) {
        s.pBfr1 = s.inBit1
        s.pBfr2 = s.inBit2
        s.pBfr3 = s.inBit3
        s.pBfr4 = s.inBit4
      } else if (s.b5_8) {
        s.pBfr1 = s.inBit5
        s.pBfr2 = s.inBit6
        s.pBfr3 = s.inBit7
        s.pBfr4 = s.inBit8
      }
    }

    if ((s.insnT5 && s.output) || s.edit || s.masterReset) {
      Ild0308DataFlow.evaluateBufferStrobes(s)
      Ild0311OutputIo.updateOutputLatches(s)
      if (s.insnT5 && s.output && !s.ldOutLth) {
        if (s.b1_4) {
          s.out1 = s.pBfr1
          s.out2 = s.pBfr2
          s.out3 = s.pBfr3
          s.out4 = s.pBfr4
        } else if (s.b5_8) {
          s.out5 = s.pBfr1
          s.out6 = s.pBfr2
          s.out7 = s.pBfr3
          s.out8 = s.pBfr4
        }
        Ild0311OutputIo.evaluateCombinational(s)
      }
    } else {
      s.ldOutLth = false
      s.resOutLth = false
    }

    Ild0306To0307Address.updateYLatches(s)
    if (s.insnT1) {
      Ild0309OpAndBranch.updateOpCodeLatches(s)
    }
    if (s.insnT2 && s.output) {
      Ild0312ConsoleLights.updateLatches(s)
      if (s.b9_12 && !isMultiplexInterruptCycle) {
        latchedOutputDevice = if (!s.y8 && !s.y9 && !s.y10) Device.Translator else Device.Lights
        latchedOutputMultiplex = s.y11
        s.tsltrSel = !s.y8 && !s.y9 && !s.y10
        s.lites = s.y8 && s.y9 && s.y10
      }
    }

    val isPOnlyBitTime = s.pOnly

    // ---------------------------------------------------------------------------------------------
    // 3. Optional P & Q Cycles: Cycle 3 (BMP STRB T) and Cycle 4 (Read Q-Time) when !pOnly
    // ---------------------------------------------------------------------------------------------
    if (!isPOnlyBitTime) {
      // Cycle 3: BMP STRB T (-A & +B & +CEN BT -> resets P TIME)
      s.a = false
      s.b = true
      s.pTime = false
      s.cenBt = true
      s.bmpStrbT = true
      pulses += 2
      Ild0305SenseQBuffer.updateQBuffer(s, 0)

      // Cycle 4: Read Q-Time (+A & -B -> +A & +B, -P TIME, -CEN BT, -TWR)
      s.a = true
      s.b = false
      s.cenBt = false
      s.bmpStrbT = false
      pulses += 2
      Ild0306To0307Address.evaluateCombinational(s)
      Ild0305SenseQBuffer.evaluateCombinational(s)

      val preserveAWordDuringMpxI1 = isMultiplexInterruptCycle && s.insnT1
      s.csYLog = !s.inhibitMemory && !s.killCsYUpperByte && !preserveAWordDuringMpxI1
      s.readGate = s.csYLog
      val qRawSense = coreMatrix.destructiveReadNibble(s)
      Ild0305SenseQBuffer.updateQBuffer(s, qRawSense)
      s.csYLog = false
      s.readGate = false
      s.b = true
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Write Cycle (TWR = true): Write Q (-P TIME) followed by Write P (+P TIME & STR LTH STROBE)
    // ---------------------------------------------------------------------------------------------
    s.a = false
    s.b = true
    s.twr = true
    s.pTime = false
    s.bmpStrbT = isPOnlyBitTime
    pulses += 1
    if (isPOnlyBitTime) {
      Ild0305SenseQBuffer.updateQBuffer(s, 0)
    }

    // Enter SSB ON of Write Cycle (-A & -B & +TWR -> STR LTH STROBE = true)
    s.b = false
    s.bmpStrbT = false
    s.strLthStrobe = true
    s.prmtTime = s.b13_16
    Ild0304Permute.evaluateClockStrobes(s)
    pulses += 1

    // Evaluate 4-bit Full Adder / Borrow Subtractor once for both Write Q and Write P
    Ild0308DataFlow.evaluateCombinational(s)

    // --- 4a. Write Q (-P TIME & +TWR) ---
    s.pTime = false
    Ild0306To0307Address.evaluateAllocationAndControl(s)
    Ild0305SenseQBuffer.evaluateCombinational(s)
    if (s.insnT1 && s.b13_16 && s.mpx && s.rdy) {
      Ild0309OpAndBranch.updateOpCodeLatches(s)
    }
    Ild0309OpAndBranch.updateConditionLatches(s)

    val preserveAWordDuringMpxI1 = isMultiplexInterruptCycle && s.insnT1
    val writeQActive =
      !s.inhibitMemory &&
      !s.killCsYUpperByte &&
      !preserveAWordDuringMpxI1 &&
      (!s.pOnly || s.selAWd || s.selBWd)
    s.csYLog = writeQActive
    if (writeQActive) {
      Ild0306To0307Address.evaluateDecoders(s)
      Ild0310WriteInhibit.evaluateWriteNets(s)
      coreMatrix.writeInhibitedNibble(s)
    }

    // --- 4b. Write P (+P TIME & +TWR & STR LTH STROBE) ---
    s.pTime = true
    Ild0306To0307Address.evaluateCombinational(s)
    Ild0305SenseQBuffer.evaluateCombinational(s)
    Ild0309OpAndBranch.evaluateCombinational(s)
    Ild0310WriteInhibit.evaluateWriteNets(s)

    val writePActive = !s.inhibitMemory && !s.killCsYUpperByte && !suppressStalledInputClear
    s.csYLog = writePActive
    if (writePActive) {
      coreMatrix.writeInhibitedNibble(s)
    }

    // Update LOW / EQUAL condition latches (and 0304 TAKEN / SET 9) at STR LTH STROBE
    Ild0309OpAndBranch.updateConditionLatches(s)
    if (s.insnT5 && s.b13_16 && s.input && !s.inh && s.mtCde) {
      s.rdCom = true
      Ild0301To0302MagneticTape.updateTransportLatches(s)
      s.setLo = false
      s.rdCom = false
    }
    // Latch CARRY 4 into CARRY OVER steering latch
    s.strCarOver = if (s.b13_16) false else s.car4

    // At I-5, B5-8 of OUTPUT: commit the 8-bit Output Register (OUT 1..8) to IoBus
    if (s.insnT5 && s.b5_8 && s.output) {
      commitOutputDeviceAtI5()
    }

    // Update Instruction Time (0304) and Bit Time (0303) steering latches
    Ild0304Permute.updateSteeringLatches(s)
    Ild0303Clock.updateBitSteeringLatches(s)

    // ---------------------------------------------------------------------------------------------
    // 5. Post-Write Transition (+A & -B -> +A & +B, 2 pulses) & Next Bit Time Indicator Load (SSA #1, 1 pulse)
    // ---------------------------------------------------------------------------------------------
    pulses += 3
    s.csYLog = false
    s.a = false
    s.b = true
    s.twr = false
    s.pTime = true
    s.cenBt = false
    s.strLthStrobe = false
    s.prmtTime = false
    s.oscTrigger = true
    s.ssa = true
    s.ssb = false
    s.resInds = true
    s.ldInds = true

    Ild0303Clock.updateBitIndicatorLatches(s)
    Ild0304Permute.updateIndicatorLatches(s)
    s.ssa = false
    s.resInds = false
    Ild0303Clock.evaluateClockNets(s)

    s.carOver = if (s.b1_4) false else s.strCarOver
    Ild0308DataFlow.evaluateAdder(s)

    s.clockSwitch = savedClockSwitch
    if (syncAtEnd) {
      Ild0303Clock.evaluateSwitchWafers(s)
      Ild0306To0307Address.evaluateCombinational(s)
      Ild0305SenseQBuffer.evaluateCombinational(s)
      Ild0309OpAndBranch.evaluateCombinational(s)
      Ild0310WriteInhibit.evaluateWriteNets(s)
      if (s.input || s.output || s.edit) {
        Ild0311OutputIo.evaluateCombinational(s)
        Ild0312ConsoleLights.evaluateCombinational(s)
      }
      syncStateFromSignals()
    }
    pulses
  }

  private def stepBitTimeCore(syncAtEnd: Boolean): Int = {
    if (!timingTrace.enabled) {
      return stepBitTimeFast(syncAtEnd)
    }
    val s = signals
    var pulses = 0
    val savedClockSwitch = s.clockSwitch
    s.clockSwitch = CeStepGranularity.Auto
    s.stpClk1 = false
    s.stpClk2 = false

    val curInsnTime = s.insnTime
    val curBitTime = s.bitTimeIndex
    inline def rec(pulse: Int, phase: OscSubPhase, coreOp: String = ""): Unit =
      if (timingTrace.enabled) {
        timingTrace.record(s, curInsnTime, curBitTime, pulse, phase, coreOp, probeMaskEvaluator)
      }

    // Ensure we are past SSA #1 of the current Bit Time
    if (s.a && s.b && s.pTime && !s.cenBt) {
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
      pulses += 1
      Ild0309OpAndBranch.updateOpCodeLatches(s)
      Ild0308DataFlow.updateCarryIndicator(s)
      rec(1, OscSubPhase.SsaOn)
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)
      rec(1, OscSubPhase.SsaOff)
    } else if (timingTrace.enabled) {
      s.ssa = true
      s.resInds = true
      rec(1, OscSubPhase.SsaOn)
      s.ssa = false
      s.resInds = false
      s.ldInds = true
      rec(1, OscSubPhase.SsaOff)
      s.ldInds = false
    }

    // Sample EDIT switch and clear OPL CDE 1..7 at BEGINNING PROGRAM STEP (I-0, B1-4, P TIME, -CEN BT)
    if (s.begPrgStp) {
      Ild0312ConsoleLights.updateLatches(s)
    }
    if (s.insnT0 && s.b1_4) {
      s.oplCde1 = false
      s.oplCde2 = false
      s.oplCde3 = false
      s.oplCde4 = false
      s.oplCde5 = false
      s.oplCde6 = false
      s.oplCde7 = false
      Ild0304Permute.evaluateCombinational(s)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Cycle 1 SSB: Buffer & Y-Latch Reset before Read P (-A & -B & -TWR & +P TIME & -CEN BT)
    // ---------------------------------------------------------------------------------------------
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOn)
    pulses += 1
    rec(2, OscSubPhase.SsbOn)
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)

    Ild0305SenseQBuffer.updateQBuffer(s, 0)
    Ild0308DataFlow.updatePBuffer(s)
    Ild0306To0307Address.updateYLatches(s)
    rec(2, OscSubPhase.SsbOff)

    // At I-5, B1-4 of INPUT: poll the addressed input device on IoBus before Read P / CEN BT
    if (s.insnT5 && s.b1_4 && s.input) {
      prepareInputDeviceAtI5()
    }

    // At I-3, B1-4 of Indirect Output: check output device readiness before pointer bump
    if (s.insnT3 && s.b1_4 && s.output) {
      if (isMultiplexInterruptCycle) {
        s.rdy = true
      } else {
        val regAddr = s.malValue & 0x3e
        savedIndirectPtrRegAddr = regAddr
        savedIndirectPtrValue = memory.readWord(Address(regAddr)).unsigned
        s.rdy = if (latchedOutputDevice == Device.Translator) ioBus.isTranslatorReady else true
      }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Cycle 2 SSA/SSB: Read P-Time (+A & -B -> +A & +B, +P TIME, +CEN BT, -TWR)
    // ---------------------------------------------------------------------------------------------
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
    pulses += 1

    // In Incremental Jump at I-2, B5-8: INCR JMP RES Y resets Y2, Y3, Y4 prior to loading Y4..Y8
    if (s.pTime && s.b5_8 && s.oplCde1 && s.insnT2) {
      Ild0306To0307Address.updateYLatches(s)
    }

    Ild0306To0307Address.evaluateCombinational(s)
    Ild0305SenseQBuffer.evaluateCombinational(s)

    // Core Read P: destructive read of the 4 selected P-cores
    val suppressStalledInputClear = s.insnT5 && s.input && s.inh
    s.csYLog = !s.inhibitMemory && !s.killCsYUpperByte && !suppressStalledInputClear
    s.readGate = s.csYLog && !s.twr
    val pRawSense = coreMatrix.destructiveReadNibble(s)
    Ild0305SenseQBuffer.updateQBuffer(s, pRawSense)
    Ild0308DataFlow.updatePBuffer(s)
    rec(3, OscSubPhase.SsaOn, if (s.csYLog) "R-P" else "")

    // SSA #2 OFF: RES BFR resets Q BFR 1..4 while P BFR 1..4 retains the P-nibble
    s.csYLog = false
    s.readGate = false
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)
    Ild0305SenseQBuffer.updateQBuffer(s, 0)
    Ild0308DataFlow.updatePBuffer(s)
    rec(3, OscSubPhase.SsaOff)

    // SSB #2 ON/OFF (CEN BT active): load Input bits, Keyboard Entry Strobe, Output latches, Y1..Y11, and OPL CDE 1..7
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOn)
    pulses += 1

    // If a Console Keyboard key or Line Stop latch is active during CEN BT, gate KB ENT STRB into P BFR 1..4
    if (s.kbCodeRdy || s.lnStpLth) {
      Ild0308DataFlow.updatePBuffer(s)
    }

    if (s.insnT5 && s.input && !s.inh) {
      // Load input byte nibbles into P BFR 1..4 at B1-4 and B5-8 (covers both Tape IN STRB and Keyboard via IoBus)
      if (s.b1_4) {
        s.pBfr1 = s.inBit1
        s.pBfr2 = s.inBit2
        s.pBfr3 = s.inBit3
        s.pBfr4 = s.inBit4
      } else if (s.b5_8) {
        s.pBfr1 = s.inBit5
        s.pBfr2 = s.inBit6
        s.pBfr3 = s.inBit7
        s.pBfr4 = s.inBit8
      }
      Ild0308DataFlow.evaluateCombinational(s)
    }

    // [AA031 / AA141 / AA142 / ILD 0311] Update Output Register (OUT 1..8) via LD OUT LTH and RES OUT LTH
    Ild0311OutputIo.updateOutputLatches(s)
    if (s.insnT5 && s.output && !s.ldOutLth) {
      if (s.b1_4) {
        s.out1 = s.pBfr1
        s.out2 = s.pBfr2
        s.out3 = s.pBfr3
        s.out4 = s.pBfr4
      } else if (s.b5_8) {
        s.out5 = s.pBfr1
        s.out6 = s.pBfr2
        s.out7 = s.pBfr3
        s.out8 = s.pBfr4
      }
      Ild0311OutputIo.evaluateCombinational(s)
    }

    Ild0306To0307Address.updateYLatches(s)
    Ild0309OpAndBranch.updateOpCodeLatches(s)
    Ild0312ConsoleLights.updateLatches(s)

    // At I-2, B9-12 of OUTPUT: capture output device (Y8..Y10) and multiplex flag (Y11)
    if (s.insnT2 && s.b9_12 && s.output && !isMultiplexInterruptCycle) {
      latchedOutputDevice = if (!s.y8 && !s.y9 && !s.y10) Device.Translator else Device.Lights
      latchedOutputMultiplex = s.y11
      s.tsltrSel = !s.y8 && !s.y9 && !s.y10
      s.lites = s.y8 && s.y9 && s.y10
    }

    rec(4, OscSubPhase.SsbOn)
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)
    rec(4, OscSubPhase.SsbOff)

    val isPOnlyBitTime = s.pOnly

    // ---------------------------------------------------------------------------------------------
    // 3. Optional P & Q Cycles: Cycle 3 (BMP STRB T) and Cycle 4 (Read Q-Time) when !pOnly
    // ---------------------------------------------------------------------------------------------
    if (!isPOnlyBitTime) {
      // Cycle 3: BMP STRB T (-A & +B & +CEN BT -> resets P TIME)
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
      pulses += 1
      Ild0305SenseQBuffer.updateQBuffer(s, 0)
      Ild0308DataFlow.evaluateCombinational(s)
      rec(5, OscSubPhase.SsaOn)
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)
      rec(5, OscSubPhase.SsaOff)

      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOn)
      pulses += 1
      rec(6, OscSubPhase.SsbOn)
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)
      rec(6, OscSubPhase.SsbOff)

      // Cycle 4: Read Q-Time (+A & -B -> +A & +B, -P TIME, -CEN BT, -TWR)
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
      pulses += 1
      Ild0306To0307Address.evaluateCombinational(s)
      Ild0305SenseQBuffer.evaluateCombinational(s)

      // Core Read Q: destructive read of the 4 selected Q-cores (e.g. A-Word at I-1/I-9, B-Word at I-9, [MAL] at I-3 JMP)
      val preserveAWordDuringMpxI1 = isMultiplexInterruptCycle && s.insnT1
      s.csYLog = !s.inhibitMemory && !s.killCsYUpperByte && !preserveAWordDuringMpxI1
      s.readGate = s.csYLog && !s.twr
      val qRawSense = coreMatrix.destructiveReadNibble(s)
      Ild0305SenseQBuffer.updateQBuffer(s, qRawSense)
      Ild0308DataFlow.evaluateCombinational(s)
      rec(7, OscSubPhase.SsaOn, if (s.csYLog) "R-Q" else "")

      s.csYLog = false
      s.readGate = false
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)
      rec(7, OscSubPhase.SsaOff)
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOn)
      pulses += 1
      rec(8, OscSubPhase.SsbOn)
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)
      rec(8, OscSubPhase.SsbOff)
    }

    val wrPulseBase = if (isPOnlyBitTime) 5 else 9

    // ---------------------------------------------------------------------------------------------
    // 4. Write Cycle (TWR = true): Write Q (-P TIME) followed by Write P (+P TIME & STR LTH STROBE)
    // ---------------------------------------------------------------------------------------------
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
    pulses += 1
    if (s.bmpStrbT) {
      Ild0305SenseQBuffer.updateQBuffer(s, 0)
      Ild0308DataFlow.evaluateCombinational(s)
    }
    rec(wrPulseBase, OscSubPhase.SsaOn)
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)
    rec(wrPulseBase, OscSubPhase.SsaOff)

    // Enter SSB ON of Write Cycle (-A & -B & +TWR -> STR LTH STROBE = true)
    s.oscTrigger = false
    s.ssa = false
    s.ssb = true
    s.b = false
    Ild0303Clock.evaluateCombinational(s)
    pulses += 1

    // --- 4a. Write Q (-P TIME & +TWR) ---
    s.pTime = false
    Ild0306To0307Address.evaluateCombinational(s)
    Ild0305SenseQBuffer.evaluateCombinational(s)
    Ild0309OpAndBranch.updateOpCodeLatches(s)
    Ild0309OpAndBranch.updateConditionLatches(s)
    Ild0310WriteInhibit.evaluateCombinational(s)

    val preserveAWordDuringMpxI1 = isMultiplexInterruptCycle && s.insnT1
    val writeQActive =
      !s.inhibitMemory &&
      !s.killCsYUpperByte &&
      !preserveAWordDuringMpxI1 &&
      (!s.pOnly || s.selAWd || s.selBWd)
    s.csYLog = writeQActive
    if (writeQActive) {
      coreMatrix.writeInhibitedNibble(s)
    }
    rec(wrPulseBase + 1, OscSubPhase.SsbOn, if (writeQActive) "W-Q" else "")

    // --- 4b. Write P (+P TIME & +TWR & STR LTH STROBE) ---
    s.pTime = true
    Ild0306To0307Address.evaluateCombinational(s)
    Ild0305SenseQBuffer.evaluateCombinational(s)
    Ild0308DataFlow.evaluateCombinational(s)
    Ild0310WriteInhibit.evaluateCombinational(s)

    val writePActive = !s.inhibitMemory && !s.killCsYUpperByte && !suppressStalledInputClear
    s.csYLog = writePActive
    coreMatrix.writeInhibitedNibble(s)

    // Update LOW / EQUAL condition latches (and 0304 TAKEN / SET 9) at STR LTH STROBE
    Ild0309OpAndBranch.updateConditionLatches(s)
    // At I-5, B13-16 of Magnetic Tape INPUT: RD COM & MT CDE updates STA RT/LF and resets MT SET LO (ILD 0301–0302)
    if (s.insnT5 && s.b13_16 && s.input && !s.inh && s.mtCde) {
      s.rdCom = true
      Ild0301To0302MagneticTape.updateTransportLatches(s)
      s.setLo = false
      s.rdCom = false
    }
    // Latch CARRY 4 into CARRY OVER steering latch
    Ild0308DataFlow.updateCarrySteering(s)

    // At I-5, B5-8 of OUTPUT: commit the 8-bit Output Register (OUT 1..8) to IoBus
    if (s.insnT5 && s.b5_8 && s.output) {
      commitOutputDeviceAtI5()
    }

    // Update Instruction Time (0304) and Bit Time (0303) steering latches
    Ild0304Permute.updateSteeringLatches(s)
    Ild0303Clock.updateBitSteeringLatches(s)
    Ild0303Clock.evaluateCombinational(s)
    s.csYLog = writePActive
    rec(wrPulseBase + 1, OscSubPhase.SsbOff, if (writePActive) "W-P" else "")

    s.csYLog = false
    s.pTime = false
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)

    // ---------------------------------------------------------------------------------------------
    // 5. Post-Write Transition (+A & -B -> +A & +B) & Next Bit Time Indicator Load (SSA #1)
    // ---------------------------------------------------------------------------------------------
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
    pulses += 1
    rec(wrPulseBase + 2, OscSubPhase.SsaOn)
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)
    rec(wrPulseBase + 2, OscSubPhase.SsaOff)

    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOn)
    pulses += 1
    rec(wrPulseBase + 3, OscSubPhase.SsbOn)
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)
    rec(wrPulseBase + 3, OscSubPhase.SsbOff)

    // Fire SSA #1 of the next Bit Time (RES INDS / LD INDS)
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
    pulses += 1
    if (!(s.insnT0 && s.b1_4)) {
      Ild0309OpAndBranch.updateOpCodeLatches(s)
    }
    Ild0308DataFlow.updateCarryIndicator(s)
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)

    s.clockSwitch = savedClockSwitch
    if (syncAtEnd) {
      syncAllCombinational()
      syncStateFromSignals()
    }
    pulses
  }

  /**
   * Advances the CCU through one complete 16-bit Instruction Time (`B1-4` -> `B5-8` -> `B9-12` -> `B13-16`),
   * returning the total number of single-shot pulses executed (32 for `P-Only`, 48 for `P & Q`).
   */
  def stepWordTime(): Int = {
    var totalPulses = 0
    totalPulses += stepBitTimeCore(syncAtEnd = false)
    totalPulses += stepBitTimeCore(syncAtEnd = false)
    totalPulses += stepBitTimeCore(syncAtEnd = false)
    totalPulses += stepBitTimeCore(syncAtEnd = true)
    totalPulses
  }

  /**
   * Prepares the start of a new Program Step at `I-0, B1-4` (including any pending Multiplex Interrupt
   * cycle and instruction decode).
   */
  private def beginProgramStepIfNeeded(): Option[StepResult] = {
    if (state.halted) {
      return Some(StepResult.Halted)
    }
    if (!inProgressInstruction && signals.insnTime == 0 && signals.bitTimeIndex == 0) {
      val s = signals
      s.mpx = state.multiplex
      s.low = state.low
      s.eq = state.equal

      if (s.mpx && ioBus.isTranslatorReady) {
        executeMultiplexInterruptCycle()
      }

      midInstructionFetchAddr = memory.psar
      midInstructionRawWord = memory.readWord(midInstructionFetchAddr)
      Decoder.decode(midInstructionRawWord) match {
        case Left(err) =>
          return Some(StepResult.Error(s"Decode error at $midInstructionFetchAddr: $err (word: $midInstructionRawWord)"))
        case Right(insn) =>
          state.lastInsn = Some(insn)
      }

      s.inh = false
      s.rdy = !s.mpx
      midInstructionPBfrAtI1B1_4 = 0
      midInstructionQBfrAtI1B5_8 = 0
      inProgressInstruction = true
      syncAllCombinational()
    }
    None
  }

  /**
   * Finalizes a Program Step once the clock returns to `I-0, B1-4`.
   */
  private def finalizeProgramStep(useSummaryBuffers: Boolean): StepResult = {
    val s = signals
    memory.aWord = midInstructionRawWord
    val result = if (s.inh) {
      memory.psar = midInstructionFetchAddr
      StepResult.Stalled
    } else {
      StepResult.Normal
    }
    syncStateFromSignals()
    if (useSummaryBuffers) {
      state.pBuffer = if (s.output) (s.outputByte & 0x0f) else midInstructionPBfrAtI1B1_4
      state.qBuffer = midInstructionQBfrAtI1B5_8
    }
    state.cycleCount += 1
    inProgressInstruction = false
    result
  }

  /**
   * Executes a single hardware Bit Time (`B1-4`, `B5-8`, `B9-12`, or `B13-16`) as part of the
   * current Program Step (`CeStepGranularity.Bit` on `ILD 0303`).
   */
  def stepSingleBitTime(): StepResult = {
    beginProgramStepIfNeeded() match {
      case Some(earlyResult) => return earlyResult
      case None              => ()
    }

    val s = signals
    val curInsnTime = s.insnTime
    val curBitTime = s.bitTimeIndex
    stepBitTime()

    if (curInsnTime == 1 && curBitTime == 0) {
      midInstructionPBfrAtI1B1_4 = midInstructionRawWord.unsigned & 0x0f
    } else if (curInsnTime == 1 && curBitTime == 1) {
      midInstructionQBfrAtI1B5_8 = (midInstructionRawWord.unsigned >> 4) & 0x0f
    }

    if (s.insnTime == 0 && s.bitTimeIndex == 0) {
      finalizeProgramStep(useSummaryBuffers = false)
    } else {
      StepResult.Normal
    }
  }

  /**
   * Executes one hardware Instruction Time (`I-0 -> I-1 -> ... -> I-9`) as part of the current
   * Program Step (`CeStepGranularity.Word` on `ILD 0303`). If currently mid-Word-Time (`B5-8..B13-16`),
   * completes the remaining Bit Times of the current Instruction Time so the clock halts at `B1-4`.
   */
  def stepSingleWordTime(): StepResult = {
    beginProgramStepIfNeeded() match {
      case Some(earlyResult) => return earlyResult
      case None              => ()
    }

    val s = signals
    val bitsToStep = if (s.bitTimeIndex == 0) 4 else (4 - s.bitTimeIndex)
    var i = 0
    var result: StepResult = StepResult.Normal
    while (i < bitsToStep && result == StepResult.Normal) {
      result = stepSingleBitTime()
      i += 1
      if (s.insnTime == 0 && s.bitTimeIndex == 0) {
        i = bitsToStep
      }
    }
    result
  }

  /**
   * Completes any partially stepped instruction so the CCU is aligned at `I-0, B1-4`.
   */
  def finishCurrentInstruction(): StepResult = {
    if (!isMidInstruction) {
      return StepResult.Normal
    }
    var guard = 0
    var result: StepResult = StepResult.Normal
    while (isMidInstruction && guard < 64) {
      val s = signals
      val curInsnTime = s.insnTime
      val curBitTime = s.bitTimeIndex
      stepBitTime()
      if (curInsnTime == 1 && curBitTime == 0) {
        midInstructionPBfrAtI1B1_4 = midInstructionRawWord.unsigned & 0x0f
      } else if (curInsnTime == 1 && curBitTime == 1) {
        midInstructionQBfrAtI1B5_8 = (midInstructionRawWord.unsigned >> 4) & 0x0f
      }
      guard += 1
      if (s.insnTime == 0 && s.bitTimeIndex == 0) {
        result = finalizeProgramStep(useSummaryBuffers = true)
      }
    }
    result
  }

  /**
   * Steps the CCU according to the 5-position Left CE Slide Switch (`CeStepGranularity` on `ILD 0303`):
   * - `Bit`: Advances 1 Bit Time (`B1-4 -> B5-8 -> B9-12 -> B13-16`)
   * - `Word`: Advances 1 Instruction Time (`I-0 -> I-1 -> ... -> I-9`)
   * - `ProgramStep` / `Auto`: Completes the current or next full Program Step (`I-0 -> I-0`)
   * - `Jump`: Advances until a taken Branch or Incremental Jump instruction completes (`stopJmp` on `ILD 0303`)
   */
  def stepByGranularity(granularity: CeStepGranularity, maxJumpSteps: Int = 512): StepResult = {
    signals.clockSwitch = granularity
    granularity match {
      case CeStepGranularity.Bit =>
        stepSingleBitTime()

      case CeStepGranularity.Word =>
        stepSingleWordTime()

      case CeStepGranularity.ProgramStep | CeStepGranularity.Auto =>
        if (isMidInstruction) finishCurrentInstruction() else step()

      case CeStepGranularity.Jump =>
        var steps = 0
        var result: StepResult = StepResult.Normal
        var stoppedOnJump = false

        if (isMidInstruction) {
          val prevPsar = midInstructionFetchAddr
          result = finishCurrentInstruction()
          steps += 1
          val s = signals
          if ((s.jmp && s.tkn) || (!state.inhibit && memory.psar != prevPsar + 2)) {
            stoppedOnJump = true
          }
        }

        while (!stoppedOnJump && !state.halted && result == StepResult.Normal && steps < maxJumpSteps) {
          val prevPsar = memory.psar
          result = step()
          steps += 1
          val s = signals
          if ((s.jmp && s.tkn) || (!state.inhibit && memory.psar != prevPsar + 2)) {
            stoppedOnJump = true
          } else if (!state.multiplex && memory.psar == prevPsar && !state.inhibit) {
            state.halted = true
            result = StepResult.Halted
          }
        }
        result
    }
  }

  /**
   * Executes one complete Program Step (`I-0` -> `I-1` -> ... -> `I-0`), matching [[mtsc.cpu.Ccu.step]].
   */
  def step(): StepResult = {
    if (isMidInstruction) {
      return finishCurrentInstruction()
    }
    if (state.halted) {
      return StepResult.Halted
    }

    val s = signals
    s.mpx = state.multiplex
    s.low = state.low
    s.eq = state.equal

    // 1. If Multiplex mode is active and the Translator is ready, execute the hardware Multiplex Interrupt cycle
    if (s.mpx && ioBus.isTranslatorReady) {
      executeMultiplexInterruptCycle()
    }

    // 2. Record fetch address at I-0 and decode instruction for telemetry / error check
    val fetchAddr = memory.psar
    val rawWord = memory.readWord(fetchAddr)
    Decoder.decode(rawWord) match {
      case Left(err) =>
        return StepResult.Error(s"Decode error at $fetchAddr: $err (word: $rawWord)")
      case Right(insn) =>
        state.lastInsn = Some(insn)
    }

    // Ensure I-0 bumps PSAR normally; if I-5 later stalls, PSAR is restored to fetchAddr below.
    // While executing the foreground instruction, Translator Ready is deasserted if Multiplex mode is active
    // (since any pending Multiplex Interrupt for this step was already serviced above).
    s.inh = false
    s.rdy = !s.mpx
    syncAllCombinational()

    // 3. Step Word Times (I-0 -> I-1 -> ... -> I-0) through the hardware state machine
    var wordSteps = 0
    var completedProgramStep = false
    var pBfrAtI1B1_4 = 0
    var qBfrAtI1B5_8 = 0

    while (!completedProgramStep && wordSteps < 16) {
      val curInsnTime = s.insnTime
      if (curInsnTime == 1) {
        // Step the 4 bit times of I-1 individually so we can record Display 4 P/Q buffer telemetry at B1-4/B5-8
        stepBitTimeCore(syncAtEnd = false) // B1-4
        pBfrAtI1B1_4 = rawWord.unsigned & 0x0f
        stepBitTimeCore(syncAtEnd = false) // B5-8
        qBfrAtI1B5_8 = (rawWord.unsigned >> 4) & 0x0f
        stepBitTimeCore(syncAtEnd = false) // B9-12
        stepBitTimeCore(syncAtEnd = false) // B13-16
      } else {
        stepBitTimeCore(syncAtEnd = false)
        stepBitTimeCore(syncAtEnd = false)
        stepBitTimeCore(syncAtEnd = false)
        stepBitTimeCore(syncAtEnd = false)
      }
      wordSteps += 1
      if (s.insnTime == 0 && s.bitTimeIndex == 0) {
        completedProgramStep = true
      }
    }

    // Preserve A-Word telemetry if Ccu.scala expects A-Word to hold the last fetched instruction word
    memory.aWord = rawWord

    val result = if (s.inh) {
      memory.psar = fetchAddr
      StepResult.Stalled
    } else {
      StepResult.Normal
    }

    syncAllCombinational()
    syncStateFromSignals()
    state.pBuffer = if (s.output) (s.outputByte & 0x0f) else pBfrAtI1B1_4
    state.qBuffer = qBfrAtI1B5_8
    state.cycleCount += 1

    result
  }

  /**
   * Runs instructions until halted, stalled, error, `maxCycles` exceeded, or a tight self-loop is detected.
   */
  def run(maxCycles: Long = 1000000L, stopOnSelfLoop: Boolean = true): StepResult = {
    var cycles = 0L
    var result: StepResult = StepResult.Normal

    while (!state.halted && result == StepResult.Normal && cycles < maxCycles) {
      val prevPsar = memory.psar
      result = step()
      cycles += 1
      if (stopOnSelfLoop && !state.multiplex && memory.psar == prevPsar && !state.inhibit) {
        state.halted = true
        return StepResult.Halted
      }
    }

    if (cycles >= maxCycles && result == StepResult.Normal)
      StepResult.Error(s"Execution exceeded max cycles ($maxCycles)")
    else
      result
  }

  /**
   * Executes a hardware Multiplex Interrupt cycle (`I-0 -> I-1 -> I-2 -> I-3 -> I-4 -> I-5 -> I-0`)
   * using the `mpx && rdy` gates on `0305`, `0306`, and `0309`:
   * - `I-0`: `inh = true` suppresses `ldQBfr2` so `PSAR` is not advanced
   * - `I-1`: `mpx && rdy` inhibits `loadOplCde` and forces `oplCde4 = true, oplCde6 = true` (Indirect Output)
   * - `I-3`: `mpx && rdy && insnT3` forces `resY = true` (`MAL = 0 = R0`), reads `R0` into `B-Word`, and bumps `R0` by `+1`
   * - `I-4`: loads `B-Word` (the old `R0` byte pointer) into `Y1..Y11` (`MAL`) and clears `B-Word`
   * - `I-5`: reads `[MAL]` into `OUT 1..8`; if `0xFF`, resets `mpx = false`, else writes byte to `Device.Translator`
   */
  private def executeMultiplexInterruptCycle(): Unit = {
    val s = signals
    val savedAWord = memory.aWord
    val savedLow = s.low
    val savedEq = s.eq

    isMultiplexInterruptCycle = true
    s.mpx = true
    s.rdy = true
    s.inh = true
    latchedOutputDevice = Device.Translator
    latchedOutputMultiplex = true
    syncAllCombinational()

    var steps = 0
    var done = false
    while (!done && steps < 10) {
      stepWordTime()
      steps += 1
      if (s.insnTime == 0 && s.bitTimeIndex == 0) {
        done = true
      }
    }

    memory.aWord = savedAWord
    s.low = savedLow
    s.eq = savedEq
    s.inh = false
    isMultiplexInterruptCycle = false
    syncAllCombinational()
    syncStateFromSignals()
  }

  /**
   * Decodes the 4-bit input device code in `(Y11, Y10, Y9, Y8)` at `I-5, B1-4` and reads from [[IoBus]].
   */
  private def prepareInputDeviceAtI5(): Unit = {
    val s = signals
    val devBits =
      (if (s.y8) 1 else 0) |
        (if (s.y9) 2 else 0) |
        (if (s.y10) 4 else 0) |
        (if (s.y11) 8 else 0)

    val device = devBits match {
      case 0                 => Device.RightTape
      case 2                 => Device.LeftTape
      case 1 | 3             => Device.PaperTape
      case 7                 => Device.Keyboard
      case d if (d & 1) != 0 => Device.PaperTape
      case d if (d & 2) != 0 => Device.LeftTape
      case _                 => Device.RightTape
    }

    val isMagTape = device == Device.RightTape || device == Device.LeftTape
    if (isMagTape) {
      ioBus match {
        case sysBus: SystemIoBus =>
          Ild0301To0302MagneticTape.prepareStationForInput(s, sysBus)
        case _ => ()
      }
    }

    ioBus.read(device) match {
      case IoResult.Ready(byte) =>
        s.inh = false
        s.rdy = true
        val b = byte & 0xff
        if (isMagTape) {
          if (!ioBus.isInstanceOf[SystemIoBus]) {
            Ild0301To0302MagneticTape.sweepFrame(s, TapeFrame.encodeByte8WithOddParity(b))
          }
          Ild0301To0302MagneticTape.evaluateCombinational(s)
        } else {
          s.inBit1 = (b & 0x01) != 0
          s.inBit2 = (b & 0x02) != 0
          s.inBit3 = (b & 0x04) != 0
          s.inBit4 = (b & 0x08) != 0
          s.inBit5 = (b & 0x10) != 0
          s.inBit6 = (b & 0x20) != 0
          s.inBit7 = (b & 0x40) != 0
          s.inBit8 = (b & 0x80) != 0
        }

      case IoResult.Busy =>
        s.inh = true
        s.rdy = false
        s.inBit1 = false
        s.inBit2 = false
        s.inBit3 = false
        s.inBit4 = false
        s.inBit5 = false
        s.inBit6 = false
        s.inBit7 = false
        s.inBit8 = false
    }
  }

  /**
   * Commits the 8-bit character latched in `OUT 1..8` at `I-5, B5-8` of an `OUTPUT` or
   * Multiplex Interrupt cycle to [[IoBus]].
   */
  private def commitOutputDeviceAtI5(): Unit = {
    val s = signals
    val byte = s.outputByte

    if (isMultiplexInterruptCycle) {
      s.tsltrSel = true
      s.lites = false
      if (byte == 0xff) {
        s.mpx = false
        state.multiplex = false
        s.busy = false
      } else {
        ioBus.write(Device.Translator, byte, multiplex = true)
        s.busy = true
      }
      Ild0311OutputIo.evaluateCombinational(s)
      return
    }

    if (byte == 0xff) {
      s.mpx = false
      state.multiplex = false
      s.busy = false
    } else if (latchedOutputMultiplex) {
      s.mpx = true
      state.multiplex = true
    }

    ioBus.write(latchedOutputDevice, byte, latchedOutputMultiplex) match {
      case IoResult.Ready(_) =>
        s.inh = false
        s.rdy = true
        if (latchedOutputDevice == Device.Translator && byte != 0xff) {
          s.tsltrSel = true
          s.lites = false
          s.busy = true
        }
        Ild0311OutputIo.evaluateCombinational(s)

      case IoResult.Busy =>
        s.inh = true
        s.rdy = false
        // If Indirect Output had already bumped the pointer register at I-3 before Busy was returned, restore it
        if (s.oplCde6) {
          memory.writeWord(Address(savedIndirectPtrRegAddr), Word(savedIndirectPtrValue))
        }
        Ild0311OutputIo.evaluateCombinational(s)
    }
  }

  // ===============================================================================================
  // Milestone 3: CE Console Edit Selector, Clear, Keyboard Entry & Lamp Multiplexer (ILD 0311 & 0312)
  // ===============================================================================================

  /**
   * Executes one complete `I-0 -> I-1 -> I-0` hardware Edit cycle (`ILD 0304`, `0305`, `0306`, `0308`, `0311`, `0312`).
   *
   * - At `I-0`: Reads `PSAR` (or clears it if `dply1 && clrButtonPressed`), applies any `Addr` keyboard strobe
   *   (`dply1 && kbCodeRdy`), loads `Y1..Y11` (`MAL`), optionally bumps `PSAR` by `+1` if `advanceAddress` is true
   *   (`!inh`), and writes back `PSAR`.
   * - At `I-1`: Reads core byte `[MAL]` (or clears it if `dply2 && clrButtonPressed`), applies any `Data` keyboard
   *   strobe (`dply2 && !btCtrB && kbCodeRdy`), latches the byte into `OUT 1..8` (`ldOutLth`), writes the byte back
   *   to `[MAL]`, and permutes `I-1 -> I-0` via `prmtEditI1` (`insnT1 && edit`).
   */
  def stepEditCycle(advanceAddress: Boolean = false): Unit = {
    val s = signals
    s.edit = true
    s.rdy = true
    s.preInh = !advanceAddress
    s.inh = !advanceAddress
    syncAllCombinational()

    // Step I-0 (PSAR -> MAL) and I-1 ([MAL] -> OUT 1..8 & [MAL])
    stepWordTime() // I-0 -> I-1
    s.preInh = true
    s.inh = true
    stepWordTime() // I-1 -> I-0

    // If PSAR was bumped by +1 at I-0 (BMP STRB T occurs after CEN BT loads Y1..Y11),
    // run one settle cycle so MAL (Y1..Y11) and OUT 1..8 reflect the newly incremented PSAR.
    if (advanceAddress) {
      stepWordTime() // I-0
      stepWordTime() // I-1
    }

    syncAllCombinational()
    syncStateFromSignals()
  }

  /**
   * Sets the 5-position `EDIT MEMORY SELECTOR` switch (`Proc`, `Edit`, `Addr`, `Data`, `Prog` on `ILD 0312`)
   * and, when entering a non-`Proc` mode, runs an `I-0 -> I-1 -> I-0` Edit cycle to recirculate `PSAR -> MAL`
   * and `[MAL] -> OUT 1..8`.
   */
  def setEditSelector(selector: CeEditSelector): Unit = {
    val s = signals
    s.editSwitch = selector
    Ild0312ConsoleLights.evaluateSwitchAndDisplayDecodes(s)
    if (selector != CeEditSelector.Proc) {
      stepEditCycle(advanceAddress = false)
    } else {
      s.edit = false
      s.preInh = false
      s.inh = false
      state.halted = false
      syncAllCombinational()
      syncStateFromSignals()
    }
  }

  /**
   * Depresses and releases the CE Console `CLEAR` button (`+CLR` on `ILD 0305` / `0312`):
   * - In `Addr` (`DPLY 1`) mode: `inhRdClrDply1` (`dply1 && clrButtonPressed && insnT0`) clears `PSAR` and `MAL` to `0x000`.
   * - In `Data` (`DPLY 2`) mode: `inhRdClrDply2` (`!btCtrB && dply2 && clrButtonPressed && insnT1`) clears `[PSAR]` and `OUT 1..8` to `0x00`.
   * - Otherwise: resets `INH`, `LOW`, `EQUAL`, and tape parity error indicators.
   */
  def pressCeClear(): Unit = {
    val s = signals
    Ild0312ConsoleLights.evaluateSwitchAndDisplayDecodes(s)
    if (s.editSwitch == CeEditSelector.Addr || s.editSwitch == CeEditSelector.Data) {
      s.clrButtonPressed = true
      stepEditCycle(advanceAddress = false)
      s.clrButtonPressed = false
      syncAllCombinational()
      syncStateFromSignals()
    } else {
      s.inh = false
      s.preInh = false
      s.low = false
      s.eq = false
      s.errorLite = false
      ioBus match {
        case sys: SystemIoBus =>
          sys.rightTape.parityError = false
          sys.leftTape.parityError = false
        case _ => ()
      }
      syncAllCombinational()
      syncStateFromSignals()
    }
  }

  /**
   * Depresses and releases one of the 12 Console Keyboard keys (`R, 9..0, START`) in CE `Edit`, `Addr`, or `Data` mode,
   * driving `KB CODE A..D`, `KB CODE RDY`, `KB STRB`, and `KB ENT STRB` (`ILD 0308`, `0311`, `0312`) through an
   * `I-0 -> I-1 -> I-0` Edit cycle.
   */
  def pressCeKey(key: ConsoleKey): Unit = {
    val s = signals
    Ild0312ConsoleLights.evaluateSwitchAndDisplayDecodes(s)
    val isStartAdvance =
      key == ConsoleKey.Start &&
      (s.editSwitch == CeEditSelector.Edit || s.editSwitch == CeEditSelector.Prog)

    s.setKeyboardCode(key.kbCodeABCD, ready = !isStartAdvance, singleEntry = !isStartAdvance)
    stepEditCycle(advanceAddress = isStartAdvance)
    if (s.editSwitch == CeEditSelector.Addr && key.addrBitIndex.isDefined) {
      state.pBuffer = 1 << (key.addrBitIndex.get & 3)
    } else if (s.editSwitch == CeEditSelector.Data && key.dataBitIndex.isDefined) {
      state.pBuffer = 1 << (key.dataBitIndex.get & 3)
    }
    s.clearKeyboardCode()
    syncAllCombinational()
    syncStateFromSignals()
  }

  /**
   * Evaluates all 11 console indicator lamps (`LITE 1..11`) via [[Ild0312ConsoleLights]] and returns
   * their left-to-right [[LampState]] vector matching [[ConsoleLampSpec.AllLamps]].
   */
  def computeLamps(
    displayMode: CeDisplayMode,
    ceTestInputs: Int = 0,
    faceplateInstalled: Boolean = false,
    powerOn: Boolean = true
  ): Vector[LampState] = {
    val s = signals
    val sysBusOpt = ioBus match {
      case sys: SystemIoBus => Some(sys)
      case _                => None
    }

    val parityErr = sysBusOpt.exists(b => b.rightTape.parityError || b.leftTape.parityError)
    val eotActive = sysBusOpt.exists(b =>
      (b.rightTape.isTapeLoaded && b.rightTape.isAtEnd) ||
      (b.leftTape.isTapeLoaded && b.leftTape.isAtEnd)
    )
    val translatorBusy = sysBusOpt.exists(b => b.translator.busy || b.translator.cycleRemaining > 0) || s.busy
    val readySignal = !state.inhibit && !translatorBusy && !state.halted

    if (faceplateInstalled) {
      val opPanel = sysBusOpt.map(_.operatorPanel).getOrElse(new OperatorPanelState())
      val setupRedOn = opPanel.isSetupLampOn
      val activeLampNum = opPanel.activeSetupParam.map(_.lampIndex)
      val rawLights = sysBusOpt.map(_.consoleLights).getOrElse(if (s.lites) s.outputByte else 0)

      ConsoleLampSpec.AllLamps.map { spec =>
        val isOn = spec.number match {
          case 1 => parityErr || opPanel.hyphenationPromptActive || s.masterReset
          case 2 => eotActive || opPanel.typeInterventionActive || s.errorLite
          case 3 => (rawLights & 0x80) != 0 || (s.lites && translatorBusy)
          case 4 => setupRedOn || (s.lites && s.out8)
          case n => activeLampNum.contains(n) || ((rawLights >> (11 - n)) & 1) == 1
        }
        val label =
          if (spec.number == 4) "SETUP"
          else if (setupRedOn && spec.operatorBottom.nonEmpty) spec.operatorBottom
          else if (spec.operatorTop.nonEmpty) spec.operatorTop
          else s"L${spec.number}"
        LampState(spec, label, isOn && powerOn)
      }
    } else {
      val savedMal = s.malValue
      val savedOut = s.outputByte
      val savedOp1 = s.oplCde1
      val savedOp2 = s.oplCde2
      val savedOp3 = s.oplCde3
      val savedOp4 = s.oplCde4
      val savedOp5 = s.oplCde5
      val savedOp6 = s.oplCde6
      val savedOp7 = s.oplCde7
      val savedLow = s.low
      val savedEq = s.eq
      val savedInh = s.inh
      val savedMpx = s.mpx
      val savedEditSwitch = s.editSwitch
      val savedAutoOrStep = s.autoOrStep
      val savedMasterReset = s.masterReset
      val savedErrorLite = s.errorLite
      val savedBusy = s.busy
      val savedRdy = s.rdy
      val savedPBfr1 = s.pBfr1
      val savedPBfr2 = s.pBfr2
      val savedPBfr3 = s.pBfr3
      val savedPBfr4 = s.pBfr4
      val savedQBfr1 = s.qBfr1
      val savedQBfr2 = s.qBfr2
      val savedQBfr3 = s.qBfr3
      val savedQBfr4 = s.qBfr4
      val savedInsnTCtrA = s.insnTCtrA
      val savedInsnTCtrB = s.insnTCtrB
      val savedInsnTCtrC = s.insnTCtrC
      val savedInsnTCtrD = s.insnTCtrD
      val savedBtCtrA = s.btCtrA
      val savedBtCtrB = s.btCtrB

      // Configure 0312 display mux inputs from current displayMode and state
      s.setMalValue(state.mal.unsigned)
      s.setOutputByte(state.outputReg)
      s.oplCde1 = state.opBit(1)
      s.oplCde2 = state.opBit(2)
      s.oplCde3 = state.opBit(3)
      s.oplCde4 = state.opBit(4)
      s.oplCde5 = state.opBit(5)
      s.oplCde6 = state.opBit(6)
      s.oplCde7 = state.opBit(7)
      s.low = state.low
      s.eq = state.equal
      s.inh = state.inhibit
      s.mpx = state.multiplex
      s.insnTCtrA = (state.insnTimeCtr & 1) == 1
      s.insnTCtrB = ((state.insnTimeCtr >> 1) & 1) == 1
      s.insnTCtrC = ((state.insnTimeCtr >> 2) & 1) == 1
      s.insnTCtrD = ((state.insnTimeCtr >> 3) & 1) == 1
      s.btCtrA = (state.bitTimeCtr & 1) == 1
      s.btCtrB = ((state.bitTimeCtr >> 1) & 1) == 1
      s.editSwitch = CeEditSelector.Proc
      Ild0312ConsoleLights.applyDisplayModeButtons(s, displayMode)
      s.autoOrStep = displayMode != CeDisplayMode.ClockDefault
      s.setCeTestInputs(ceTestInputs)
      s.masterReset = savedMasterReset || (displayMode == CeDisplayMode.ClockDefault && parityErr)
      s.errorLite = savedErrorLite || eotActive
      s.busy = translatorBusy
      s.rdy = readySignal
      s.pBfr1 = state.pbBit(1)
      s.pBfr2 = state.pbBit(2)
      s.pBfr3 = state.pbBit(3)
      s.pBfr4 = state.pbBit(4)
      s.qBfr1 = state.qbBit(1)
      s.qBfr2 = state.qbBit(2)
      s.qBfr3 = state.qbBit(3)
      s.qBfr4 = state.qbBit(4)

      Ild0312ConsoleLights.evaluateCombinational(s)
      val lampsLR = s.lampsLeftToRight

      // Restore transient mux inputs
      s.setMalValue(savedMal)
      s.setOutputByte(savedOut)
      s.oplCde1 = savedOp1
      s.oplCde2 = savedOp2
      s.oplCde3 = savedOp3
      s.oplCde4 = savedOp4
      s.oplCde5 = savedOp5
      s.oplCde6 = savedOp6
      s.oplCde7 = savedOp7
      s.low = savedLow
      s.eq = savedEq
      s.inh = savedInh
      s.mpx = savedMpx
      s.editSwitch = savedEditSwitch
      s.autoOrStep = savedAutoOrStep
      s.masterReset = savedMasterReset
      s.errorLite = savedErrorLite
      s.busy = savedBusy
      s.rdy = savedRdy
      s.pBfr1 = savedPBfr1
      s.pBfr2 = savedPBfr2
      s.pBfr3 = savedPBfr3
      s.pBfr4 = savedPBfr4
      s.qBfr1 = savedQBfr1
      s.qBfr2 = savedQBfr2
      s.qBfr3 = savedQBfr3
      s.qBfr4 = savedQBfr4
      s.insnTCtrA = savedInsnTCtrA
      s.insnTCtrB = savedInsnTCtrB
      s.insnTCtrC = savedInsnTCtrC
      s.insnTCtrD = savedInsnTCtrD
      s.btCtrA = savedBtCtrA
      s.btCtrB = savedBtCtrB
      Ild0312ConsoleLights.evaluateCombinational(s)

      ConsoleLampSpec.AllLamps.zip(lampsLR).map { case (spec, on) =>
        val label = displayMode match {
          case CeDisplayMode.Display1     => spec.ceDisplay1
          case CeDisplayMode.Display2     => spec.ceDisplay2
          case CeDisplayMode.Display3     => spec.ceDisplay3
          case CeDisplayMode.Display4     => spec.ceDisplay4
          case CeDisplayMode.ClockDefault => spec.ceTopLip
        }
        LampState(spec, label, on && powerOn)
      }
    }
  }
}
