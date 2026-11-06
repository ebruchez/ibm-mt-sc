package mtsc.periph

import mtsc.types.*
import mtsc.console.CeStepGranularity
import mtsc.isa.{Decoder, Instruction}
import mtsc.memory.{CoreImage, CoreMemory}
import mtsc.cpu.{Ccu, CcuEngineMode, StepResult}
import mtsc.cpu.ild.Ild0301To0302MagneticTape
import mtsc.diag.{ContinuousComposerRoutine, DiagnosticPrograms, EscapementRoutine}

/**
 * Complete IBM MT/SC System Integration.
 *
 * Coordinates the CCU processor, Core Memory, Dual Tape Readers (Model 6310),
 * and Selectric Composer Translator with paper galley output.
 */
class MtscSystem(
  val memory: CoreMemory = CoreMemory.empty(),
  val ioBus: SystemIoBus = new SystemIoBus(),
  initialEngineMode: CcuEngineMode = CcuEngineMode.Default
) {

  val cpu: Ccu = new Ccu(memory, ioBus, engineMode = initialEngineMode)

  def engineMode: CcuEngineMode = cpu.engineMode
  def setEngineMode(mode: CcuEngineMode): Unit = cpu.setEngineMode(mode)
  def toggleEngineMode(): CcuEngineMode = cpu.toggleEngineMode()

  def rightTape: TapeReader = ioBus.rightTape
  def leftTape: TapeReader = ioBus.leftTape
  def translator: ComposerTranslator = ioBus.translator
  def paper: ComposerPaper = translator.paper
  def operatorPanel: OperatorPanelState = ioBus.operatorPanel

  /**
   * Handles pressing one of the 8 pushbuttons on the Magnetic Tape Reader Console.
   */
  def pressTapeButton(button: TapeConsoleButton): String = button match {
    case TapeConsoleButton.LoadRight =>
      if (rightTape.pressLoad()) {
        Ild0301To0302MagneticTape.pressLoad(cpu.ild.signals, rightStation = true, ioBus)
        cpu.ild.syncAllCombinational()
        s"Right Tape loaded to BOT track 0 (cover locked)."
      } else "Right Station has no tape cartridge mounted."

    case TapeConsoleButton.UnloadRight =>
      if (rightTape.pressUnload()) {
        Ild0301To0302MagneticTape.pressUnload(cpu.ild.signals, rightStation = true, ioBus)
        cpu.ild.syncAllCombinational()
        "Right Tape rewound into cartridge (ready to unlock cover)."
      } else "Right Station has no tape to unload."

    case TapeConsoleButton.LoadLeft =>
      if (leftTape.pressLoad()) {
        Ild0301To0302MagneticTape.pressLoad(cpu.ild.signals, rightStation = false, ioBus)
        cpu.ild.syncAllCombinational()
        s"Left Tape loaded to BOT track 0 (cover locked)."
      } else "Left Station has no tape cartridge mounted."

    case TapeConsoleButton.UnloadLeft =>
      if (leftTape.pressUnload()) {
        Ild0301To0302MagneticTape.pressUnload(cpu.ild.signals, rightStation = false, ioBus)
        cpu.ild.syncAllCombinational()
        "Left Tape rewound into cartridge (ready to unlock cover)."
      } else "Left Station has no tape to unload."

    case TapeConsoleButton.CoverUnlock =>
      val rOk = rightTape.pressCoverUnlock()
      val lOk = leftTape.pressCoverUnlock()
      if (rOk && lOk) "Tape Station Cover unlocked."
      else if (rOk) "Right Tape Station Cover unlocked (Left Station locked while tape loaded)."
      else if (lOk) "Left Tape Station Cover unlocked (Right Station locked while tape loaded)."
      else "Cover interlock active: unload tape into cartridge before unlocking cover."

    case TapeConsoleButton.Reread =>
      val msg = if (leftTape.parityError && !rightTape.parityError) {
        val pos = leftTape.pressReread()
        s"Left Tape REREAD: backstepped to track $pos (parity error cleared)."
      } else if (rightTape.parityError && !leftTape.parityError) {
        val pos = rightTape.pressReread()
        s"Right Tape REREAD: backstepped to track $pos (parity error cleared)."
      } else if (leftTape.parityError && rightTape.parityError) {
        val pL = leftTape.pressReread()
        val pR = rightTape.pressReread()
        s"Dual Tape REREAD: Left track $pL, Right track $pR (parity errors cleared)."
      } else {
        val active = if (leftTape.isTapeLoaded && !rightTape.isTapeLoaded) leftTape else rightTape
        val name = if (active eq leftTape) "Left" else "Right"
        val pos = active.pressReread()
        s"$name Tape REREAD: backstepped to track $pos."
      }
      Ild0301To0302MagneticTape.pressReread(cpu.ild.signals, ioBus)
      cpu.ild.syncAllCombinational()
      msg

    case TapeConsoleButton.Skip =>
      val msg = if (leftTape.parityError && !rightTape.parityError) {
        val pos = leftTape.pressSkip()
        s"Left Tape SKIP: advanced to track $pos."
      } else if (rightTape.parityError && !leftTape.parityError) {
        val pos = rightTape.pressSkip()
        s"Right Tape SKIP: advanced to track $pos."
      } else {
        val active = if (leftTape.isTapeLoaded && !rightTape.isTapeLoaded) leftTape else rightTape
        val name = if (active eq leftTape) "Left" else "Right"
        val pos = active.pressSkip()
        s"$name Tape SKIP: advanced to track $pos."
      }
      Ild0301To0302MagneticTape.pressSkip(cpu.ild.signals, ioBus)
      cpu.ild.syncAllCombinational()
      msg

    case TapeConsoleButton.Search =>
      if (rightTape.isTapeLoaded && leftTape.isTapeLoaded) {
        val pR = rightTape.pressSearch()
        val pL = leftTape.pressSearch()
        Ild0301To0302MagneticTape.pressSearch(cpu.ild.signals, ioBus)
        cpu.ild.syncAllCombinational()
        s"Dual Tape SEARCH: Right advanced to track $pR, Left advanced to track $pL."
      } else if (leftTape.isTapeLoaded) {
        val pos = leftTape.pressSearch()
        Ild0301To0302MagneticTape.pressSearch(cpu.ild.signals, ioBus)
        cpu.ild.syncAllCombinational()
        s"Left Tape SEARCH: advanced to reference track $pos."
      } else if (rightTape.isTapeLoaded) {
        val pos = rightTape.pressSearch()
        Ild0301To0302MagneticTape.pressSearch(cpu.ild.signals, ioBus)
        cpu.ild.syncAllCombinational()
        s"Right Tape SEARCH: advanced to reference track $pos."
      } else {
        "Tape SEARCH inactive: no tape loaded."
      }
  }

  def loadTape(station: Device, cartridge: TapeCartridge): Unit = {
    station match {
      case Device.RightTape => rightTape.mount(cartridge)
      case Device.LeftTape  => leftTape.mount(cartridge)
      case _ => throw new IllegalArgumentException("Can only load tape on RightTape or LeftTape")
    }
    Ild0301To0302MagneticTape.pressLoad(cpu.ild.signals, rightStation = (station == Device.RightTape), ioBus)
    cpu.ild.syncAllCombinational()
  }

  def unloadTape(station: Device): Option[TapeCartridge] = {
    val res = station match {
      case Device.RightTape => rightTape.unmount()
      case Device.LeftTape  => leftTape.unmount()
      case _ => None
    }
    Ild0301To0302MagneticTape.pressUnload(cpu.ild.signals, rightStation = (station == Device.RightTape), ioBus)
    cpu.ild.syncAllCombinational()
    res
  }

  /**
   * Sets input station in any loaded composition or bootstrap program in Core Memory
   * without corrupting storage when Core Memory is zeroed or holds non-tape code.
   */
  def setInputStation(station: Device): Unit = {
    require(station == Device.RightTape || station == Device.LeftTape, "Station must be RightTape or LeftTape")
    val candidates = Seq(
      ContinuousComposerRoutine.TapeInputInstructionAddress,
      DiagnosticPrograms.EndToEndCompositionDemo.TapeInputInstructionAddress,
      DiagnosticPrograms.TapeBootstrapLoader.TapeInputInstructionAddress
    )
    for (addr <- candidates) {
      val cur = memory.readWord(addr)
      Decoder.decode(cur) match {
        case Right(Instruction.Input(Device.RightTape | Device.LeftTape, _)) =>
          val updated = if (station == Device.LeftTape) cur.withBit(8, true) else cur.withBit(8, false)
          memory.writeWord(addr, updated)
        case _ => ()
      }
    }
  }

  def loadProgram(image: CoreImage): Unit = {
    memory.loadFromImage(image)
    cpu.reset()
  }

  /**
   * Clears all 2,048 bytes of Core Memory to 0x00 and resets CPU latches to 0x000
   * to simulate an uninitialized core stack ready for a cold bootstrap.
   */
  def clearMemory(): Unit = {
    memory.clear()
    cpu.state.reset()
    cpu.state.mal = Address(0)
    if (cpu.isIldMode) {
      cpu.ild.syncSignalsFromState()
    }
  }

  /**
   * Executes the 8-byte Initial Loader (at 0x02A..0x02E) until control transfers
   * to the target program's entry point (stepping past any page-0 trampoline jump).
   */
  def runBootLoader(maxCycles: Long = 20000L): StepResult = {
    cpu.state.halted = false
    var cycles = 0L
    var result: StepResult = StepResult.Normal

    while (
      !cpu.state.halted &&
      result == StepResult.Normal &&
      cycles < maxCycles &&
      memory.psar.unsigned >= 42 &&
      memory.psar.unsigned <= 47
    ) {
      result = step()
      cycles += 1
    }

    // If the loader exited via a page-0 trampoline `JMP entryPoint` (where entryPoint >= 256),
    // step once more so PSAR lands directly on `entryPoint` (e.g., 0x222).
    if (result == StepResult.Normal && !cpu.state.halted && memory.psar.unsigned < 256) {
      Decoder.decode(memory.readWord(memory.psar)) match {
        case Right(Instruction.Branch(Condition.Unconditional, false, target))
            if target != memory.psar && target.unsigned >= 256 =>
          result = step()
          cycles += 1
        case _ => ()
      }
    }

    if (cycles >= maxCycles && result == StepResult.Normal && memory.psar.unsigned >= 42 && memory.psar.unsigned <= 47) {
      StepResult.Error(s"Boot loader exceeded max cycles ($maxCycles)")
    } else {
      result
    }
  }

  /**
   * Completes any partially stepped instruction in `ILD` mode so the CCU is aligned at `I-0, B1-4`.
   */
  def finishCurrentInstruction(): StepResult = {
    if (cpu.isIldMode && cpu.ild.isMidInstruction) {
      val res = cpu.ild.finishCurrentInstruction()
      ioBus.stepCycle()
      res
    } else {
      StepResult.Normal
    }
  }

  /**
   * Steps the system according to the Left CE Slide Switch (`[Bit]`, `[Word]`, `[P.S.]`, `[Jump]`, `[Auto]`).
   * In `ILD` mode, executes genuine sub-instruction Bit-Time and Word-Time hardware steps on `IldCcu`.
   */
  def stepByGranularity(granularity: CeStepGranularity, maxJumpSteps: Int = 512): StepResult = {
    if (cpu.isIldMode) {
      granularity match {
        case CeStepGranularity.Bit | CeStepGranularity.Word =>
          val res = cpu.ild.stepByGranularity(granularity, maxJumpSteps)
          if (!cpu.ild.isMidInstruction) {
            ioBus.stepCycle()
          }
          res

        case CeStepGranularity.ProgramStep | CeStepGranularity.Auto =>
          if (cpu.ild.isMidInstruction) finishCurrentInstruction() else step()

        case CeStepGranularity.Jump =>
          var steps = 0
          var result: StepResult = StepResult.Normal
          var stoppedOnJump = false

          if (cpu.ild.isMidInstruction) {
            val prevPsar = memory.psar
            result = finishCurrentInstruction()
            steps += 1
            val s = cpu.ild.signals
            if ((s.jmp && s.tkn) || (!cpu.state.inhibit && memory.psar != prevPsar + 2)) {
              stoppedOnJump = true
            }
          }

          while (!stoppedOnJump && !cpu.state.halted && result == StepResult.Normal && steps < maxJumpSteps) {
            val prevPsar = memory.psar
            result = step()
            steps += 1
            val s = cpu.ild.signals
            if ((s.jmp && s.tkn) || (!cpu.state.inhibit && memory.psar != prevPsar + 2)) {
              stoppedOnJump = true
            } else if (!cpu.state.multiplex && memory.psar == prevPsar && !cpu.state.inhibit) {
              cpu.state.halted = true
              result = StepResult.Halted
            }
          }
          result
      }
    } else {
      granularity match {
        case CeStepGranularity.Bit =>
          cpu.state.bitTimeCtr = (cpu.state.bitTimeCtr + 1) & 0x3
          if (cpu.state.bitTimeCtr == 0) {
            cpu.state.insnTimeCtr = (cpu.state.insnTimeCtr + 1) % 6
            if (cpu.state.insnTimeCtr == 0) step() else StepResult.Normal
          } else {
            StepResult.Normal
          }

        case CeStepGranularity.Word =>
          cpu.state.bitTimeCtr = 0
          cpu.state.insnTimeCtr = (cpu.state.insnTimeCtr + 1) % 6
          if (cpu.state.insnTimeCtr == 0) step() else StepResult.Normal

        case CeStepGranularity.Jump =>
          cpu.state.bitTimeCtr = 0
          cpu.state.insnTimeCtr = 0
          var steps = 0
          var result: StepResult = StepResult.Normal
          var stoppedOnJump = false
          while (!stoppedOnJump && !cpu.state.halted && result == StepResult.Normal && steps < maxJumpSteps) {
            val prevPsar = memory.psar
            result = step()
            steps += 1
            cpu.state.lastInsn match {
              case Some(_: Instruction.Branch) =>
                stoppedOnJump = true
              case _ if memory.psar != prevPsar + 2 =>
                stoppedOnJump = true
              case _ =>
                if (!cpu.state.multiplex && memory.psar == prevPsar && !cpu.state.inhibit) {
                  cpu.state.halted = true
                  result = StepResult.Halted
                }
            }
          }
          result

        case CeStepGranularity.ProgramStep | CeStepGranularity.Auto =>
          cpu.state.bitTimeCtr = 0
          cpu.state.insnTimeCtr = 0
          step()
      }
    }
  }

  /**
   * Advances both the CCU and connected mechanical peripherals by 1 cycle.
   */
  def step(): StepResult = {
    val result = cpu.step()
    ioBus.stepCycle()
    result
  }

  /**
   * Runs the complete system until halt, breakpoint, or maxCycles.
   */
  def run(maxCycles: Long = 1000000L): StepResult = {
    var cycles = 0L
    var result: StepResult = StepResult.Normal

    while (!cpu.state.halted && result == StepResult.Normal && cycles < maxCycles) {
      val prevPsar = memory.psar
      result = step()
      cycles += 1
      if (!cpu.state.multiplex && memory.psar == prevPsar && !cpu.state.inhibit) {
        cpu.state.halted = true
        return StepResult.Halted
      }
    }

    if (cycles >= maxCycles && result == StepResult.Normal) {
      StepResult.Error(s"System run exceeded max cycles ($maxCycles)")
    } else {
      result
    }
  }

  def reset(): Unit = {
    cpu.reset()
    ioBus.reset()
  }
}

object MtscSystem {
  /**
   * Builds a complete, bootable 8-bit odd-parity Factory Program Tape cartridge
   * containing the assembled microprogram, initialized proportional escapement table
   * (bytes 64..191), and initial working register values, ready to cold-boot via
   * the 8-byte `INITIAL LOADER` (`40..47`) on either Right or Left Tape Station.
   */
  def createFactoryProgramTape(
    station: Device = Device.RightTape,
    prog: DiagnosticPrograms.ProgramInfo = DiagnosticPrograms.ContinuousCompositionDemo,
    measure: Int = 140
  ): TapeCartridge = {
    require(station == Device.RightTape || station == Device.LeftTape, "Station must be RightTape or LeftTape")
    val mem = CoreMemory.fromImage(prog.assemble())
    EscapementRoutine.initializeTable(mem)

    if (prog == DiagnosticPrograms.ContinuousCompositionDemo) {
      mem.writeReg(RegId(30), Word(measure))
      mem.writeReg(RegId(28), Word(math.max(40, measure - 30)))
      ContinuousComposerRoutine.setInputStation(mem, station)
    } else if (prog == DiagnosticPrograms.EndToEndCompositionDemo) {
      mem.writeReg(RegId(30), Word(100))
      mem.writeReg(RegId(28), Word(70))
      DiagnosticPrograms.EndToEndCompositionDemo.setInputStation(mem, station)
    } else if (prog == DiagnosticPrograms.TapeBootstrapLoader) {
      mem.writeReg(RegId(8), Word(200))
      DiagnosticPrograms.TapeBootstrapLoader.setInputStation(mem, station)
    } else if (prog == DiagnosticPrograms.EscapementAndJustifyDemo) {
      val sample = "The logic in CCU is in the SLD family."
      for (i <- 0 until sample.length) {
        mem.writeByte(Address(316 + i), sample.charAt(i).toInt)
      }
      mem.writeByte(Address(316 + sample.length), 0)
    }

    val stShort = if (station == Device.LeftTape) "Left" else "Right"
    TapeCartridge.fromCoreImage(
      mem.toCoreImage,
      entryPoint = Address.PowerOn,
      label = s"Factory Program Tape ($stShort)"
    )
  }
}
