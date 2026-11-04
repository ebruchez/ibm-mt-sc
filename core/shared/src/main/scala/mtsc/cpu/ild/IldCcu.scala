package mtsc.cpu.ild

import mtsc.cpu.{CcuState, IoBus, IoResult, StepResult}
import mtsc.isa.{Decoder, Instruction}
import mtsc.memory.CoreMemory
import mtsc.types.{Address, Device, Word}

/**
 * Hardware-accurate gate- and latch-level execution engine for the **IBM MT/SC 6320 Composer Control Unit (CCU)**,
 * integrating Intermediate Logic Diagrams **ILDs `0303`–`0310`** and the 3D 4-plane magnetic core array
 * ([[CoreMatrix3D]]).
 *
 * Unlike the behavioral [[mtsc.cpu.Ccu]], `IldCcu` executes instructions by stepping the 240 kHz oscillator
 * single-shots (`SSA` / `SSB`) through every 4-bit nibble (`B1-4`, `B5-8`, `B9-12`, `B13-16`) and every
 * Instruction Time (`I-0`–`I-9`), driving address decoding, destructive core reads, sense amplifiers,
 * `Q-Buffer`, `P-Buffer`, serial 4-bit full adder / borrow subtractor (`ACC 1..4`, `CAR 1..4`, `CAR OVER`),
 * operational code latches (`OPL CDE 1..7`), condition latches (`LOW`, `EQUAL`), permute logic, and
 * inhibit-controlled core writes.
 */
final class IldCcu(
  val memory: CoreMemory,
  val ioBus: IoBus,
  val state: CcuState = new CcuState(),
  val signals: BackplaneSignals = new BackplaneSignals()
) {

  val coreMatrix: CoreMatrix3D = new CoreMatrix3D(memory)

  /** Latched output device and multiplex flag captured at `I-2` of `OUTPUT` before `I-4` reloads `Y1..Y11`. */
  private var latchedOutputDevice: Device = Device.Translator
  private var latchedOutputMultiplex: Boolean = false
  private var isMultiplexInterruptCycle: Boolean = false
  private var savedIndirectPtrRegAddr: Int = 0
  private var savedIndirectPtrValue: Int = 0

  // Initialize clock and backplane signals to power-on reset state (I-0, B1-4)
  Ild0303Clock.reset(signals)
  syncAllCombinational()

  /**
   * Resets Core Memory, `CcuState`, and all `0303`–`0310` backplane latches to power-on defaults.
   */
  def reset(): Unit = {
    memory.reset()
    state.reset()
    signals.inh = false
    signals.mpx = false
    signals.rdy = true
    signals.low = false
    signals.eq = false
    signals.strCarOver = false
    signals.carOver = false
    signals.setMalValue(0)
    Ild0303Clock.reset(signals)
    syncAllCombinational()
    syncStateFromSignals()
  }

  /**
   * Evaluates all combinational networks across ILDs `0303`–`0310`.
   */
  def syncAllCombinational(): Unit = {
    Ild0303Clock.evaluateCombinational(signals)
    Ild0304Permute.evaluateCombinational(signals)
    Ild0306To0307Address.evaluateCombinational(signals)
    Ild0305SenseQBuffer.evaluateCombinational(signals)
    Ild0308DataFlow.evaluateCombinational(signals)
    Ild0309OpAndBranch.evaluateCombinational(signals)
    Ild0310WriteInhibit.evaluateCombinational(signals)
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
   * Advances the CCU by one complete Bit Time (`B1-4`, `B5-8`, `B9-12`, or `B13-16`),
   * executing all sub-phase clock transitions (`Indicator Load`, `Read P`, `BMP STRB T`,
   * optional `Read Q`, `Write Q`, `Write P`, and `Post-Write Transition`) across `0303`–`0310`
   * and [[CoreMatrix3D]].
   *
   * Returns the number of single-shot pulses (`SSA` + `SSB`) executed (8 for a `P-Only` bit time,
   * 12 for a `P & Q` bit time).
   */
  def stepBitTime(): Int = {
    val s = signals
    var pulses = 0

    // Ensure we are past SSA #1 of the current Bit Time
    if (s.a && s.b && s.pTime && !s.cenBt) {
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
      pulses += 1
      Ild0309OpAndBranch.updateOpCodeLatches(s)
      Ild0308DataFlow.updateCarryIndicator(s)
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Cycle 1 SSB: Buffer & Y-Latch Reset before Read P (-A & -B & -TWR & +P TIME & -CEN BT)
    // ---------------------------------------------------------------------------------------------
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOn)
    pulses += 1
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)

    Ild0305SenseQBuffer.updateQBuffer(s, 0)
    Ild0308DataFlow.updatePBuffer(s)
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

    // SSA #2 OFF: RES BFR resets Q BFR 1..4 while P BFR 1..4 retains the P-nibble
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)
    Ild0305SenseQBuffer.updateQBuffer(s, 0)
    Ild0308DataFlow.updatePBuffer(s)

    // SSB #2 ON/OFF (CEN BT active): load Input bits, Output latches, Y1..Y11, and OPL CDE 1..7
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOn)
    pulses += 1

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

    if (s.insnT5 && s.output) {
      // [AA031 / ILD 0311] Latch 8-bit Output Register (OUT 1..8) from P BFR 1..4 during B1-4 and B5-8
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
    }

    Ild0306To0307Address.updateYLatches(s)
    Ild0309OpAndBranch.updateOpCodeLatches(s)

    // At I-2, B9-12 of OUTPUT: capture output device (Y8..Y10) and multiplex flag (Y11)
    if (s.insnT2 && s.b9_12 && s.output && !isMultiplexInterruptCycle) {
      latchedOutputDevice = if (!s.y8 && !s.y9 && !s.y10) Device.Translator else Device.Lights
      latchedOutputMultiplex = s.y11
    }

    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)

    // ---------------------------------------------------------------------------------------------
    // 3. Optional P & Q Cycles: Cycle 3 (BMP STRB T) and Cycle 4 (Read Q-Time) when !pOnly
    // ---------------------------------------------------------------------------------------------
    if (!s.pOnly) {
      // Cycle 3: BMP STRB T (-A & +B & +CEN BT -> resets P TIME)
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
      pulses += 1
      Ild0305SenseQBuffer.updateQBuffer(s, 0)
      Ild0308DataFlow.evaluateCombinational(s)
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)

      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOn)
      pulses += 1
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)

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

      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOn)
      pulses += 1
      Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Write Cycle (TWR = true): Write Q (-P TIME) followed by Write P (+P TIME & STR LTH STROBE)
    // ---------------------------------------------------------------------------------------------
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
    pulses += 1
    if (s.bmpStrbT) {
      Ild0305SenseQBuffer.updateQBuffer(s, 0)
      Ild0308DataFlow.evaluateCombinational(s)
    }
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)

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
    if (writeQActive) {
      s.csYLog = true
      coreMatrix.writeInhibitedNibble(s)
    }

    // --- 4b. Write P (+P TIME & +TWR & STR LTH STROBE) ---
    s.pTime = true
    Ild0306To0307Address.evaluateCombinational(s)
    Ild0305SenseQBuffer.evaluateCombinational(s)
    Ild0308DataFlow.evaluateCombinational(s)
    Ild0310WriteInhibit.evaluateCombinational(s)

    s.csYLog = !s.inhibitMemory && !s.killCsYUpperByte && !suppressStalledInputClear
    coreMatrix.writeInhibitedNibble(s)

    // Update LOW / EQUAL condition latches (and 0304 TAKEN / SET 9) at STR LTH STROBE
    Ild0309OpAndBranch.updateConditionLatches(s)
    // Latch CARRY 4 into CARRY OVER steering latch
    Ild0308DataFlow.updateCarrySteering(s)

    // At I-5, B5-8 of OUTPUT: commit the 8-bit Output Register (OUT 1..8) to IoBus
    if (s.insnT5 && s.b5_8 && s.output) {
      commitOutputDeviceAtI5()
    }

    s.pTime = false

    // Update Instruction Time (0304) and Bit Time (0303) steering latches
    Ild0304Permute.updateSteeringLatches(s)
    Ild0303Clock.updateBitSteeringLatches(s)
    Ild0303Clock.evaluateCombinational(s)

    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)

    // ---------------------------------------------------------------------------------------------
    // 5. Post-Write Transition (+A & -B -> +A & +B) & Next Bit Time Indicator Load (SSA #1)
    // ---------------------------------------------------------------------------------------------
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
    pulses += 1
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)

    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOn)
    pulses += 1
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsbOff)

    // Fire SSA #1 of the next Bit Time (RES INDS / LD INDS)
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOn)
    pulses += 1
    Ild0309OpAndBranch.updateOpCodeLatches(s)
    Ild0308DataFlow.updateCarryIndicator(s)
    Ild0303Clock.stepSubPhase(s, OscSubPhase.SsaOff)

    syncAllCombinational()
    syncStateFromSignals()
    pulses
  }

  /**
   * Advances the CCU through one complete 16-bit Instruction Time (`B1-4` -> `B5-8` -> `B9-12` -> `B13-16`),
   * returning the total number of single-shot pulses executed (32 for `P-Only`, 48 for `P & Q`).
   */
  def stepWordTime(): Int = {
    var totalPulses = 0
    var i = 0
    while (i < 4) {
      totalPulses += stepBitTime()
      i += 1
    }
    totalPulses
  }

  /**
   * Executes one complete Program Step (`I-0` -> `I-1` -> ... -> `I-0`), matching [[mtsc.cpu.Ccu.step]].
   */
  def step(): StepResult = {
    if (state.halted) {
      return StepResult.Halted
    }

    val s = signals
    s.mpx = state.multiplex

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
        stepBitTime() // B1-4
        pBfrAtI1B1_4 = rawWord.unsigned & 0x0f
        stepBitTime() // B5-8
        qBfrAtI1B5_8 = (rawWord.unsigned >> 4) & 0x0f
        stepBitTime() // B9-12
        stepBitTime() // B13-16
      } else {
        stepWordTime()
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

    ioBus.read(device) match {
      case IoResult.Ready(byte) =>
        s.inh = false
        s.rdy = true
        val b = byte & 0xff
        s.inBit1 = (b & 0x01) != 0
        s.inBit2 = (b & 0x02) != 0
        s.inBit3 = (b & 0x04) != 0
        s.inBit4 = (b & 0x08) != 0
        s.inBit5 = (b & 0x10) != 0
        s.inBit6 = (b & 0x20) != 0
        s.inBit7 = (b & 0x40) != 0
        s.inBit8 = (b & 0x80) != 0

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
      if (byte == 0xff) {
        s.mpx = false
        state.multiplex = false
      } else {
        ioBus.write(Device.Translator, byte, multiplex = true)
      }
      return
    }

    if (byte == 0xff) {
      s.mpx = false
      state.multiplex = false
    } else if (latchedOutputMultiplex) {
      s.mpx = true
      state.multiplex = true
    }

    ioBus.write(latchedOutputDevice, byte, latchedOutputMultiplex) match {
      case IoResult.Ready(_) =>
        s.inh = false
        s.rdy = true

      case IoResult.Busy =>
        s.inh = true
        s.rdy = false
        // If Indirect Output had already bumped the pointer register at I-3 before Busy was returned, restore it
        if (s.oplCde6) {
          memory.writeWord(Address(savedIndirectPtrRegAddr), Word(savedIndirectPtrValue))
        }
    }
  }
}
