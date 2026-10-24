package mtsc.periph

import mtsc.types.*
import mtsc.memory.{CoreImage, CoreMemory}
import mtsc.cpu.{Ccu, StepResult}
import mtsc.diag.ContinuousComposerRoutine

/**
 * Complete IBM MT/SC System Integration.
 *
 * Coordinates the CCU processor, Core Memory, Dual Tape Readers (Model 6310),
 * and Selectric Composer Translator with paper galley output.
 */
class MtscSystem(
  val memory: CoreMemory = CoreMemory.empty(),
  val ioBus: SystemIoBus = new SystemIoBus()
) {

  val cpu: Ccu = new Ccu(memory, ioBus)

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
      if (rightTape.pressLoad()) s"Right Tape loaded to BOT track 0 (cover locked)."
      else "Right Station has no tape cartridge mounted."

    case TapeConsoleButton.UnloadRight =>
      if (rightTape.pressUnload()) "Right Tape rewound into cartridge (ready to unlock cover)."
      else "Right Station has no tape to unload."

    case TapeConsoleButton.LoadLeft =>
      if (leftTape.pressLoad()) s"Left Tape loaded to BOT track 0 (cover locked)."
      else "Left Station has no tape cartridge mounted."

    case TapeConsoleButton.UnloadLeft =>
      if (leftTape.pressUnload()) "Left Tape rewound into cartridge (ready to unlock cover)."
      else "Left Station has no tape to unload."

    case TapeConsoleButton.CoverUnlock =>
      val rOk = rightTape.pressCoverUnlock()
      val lOk = leftTape.pressCoverUnlock()
      if (rOk && lOk) "Tape Station Cover unlocked."
      else if (rOk) "Right Tape Station Cover unlocked (Left Station locked while tape loaded)."
      else if (lOk) "Left Tape Station Cover unlocked (Right Station locked while tape loaded)."
      else "Cover interlock active: unload tape into cartridge before unlocking cover."

    case TapeConsoleButton.Reread =>
      if (leftTape.parityError && !rightTape.parityError) {
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

    case TapeConsoleButton.Skip =>
      if (leftTape.parityError && !rightTape.parityError) {
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

    case TapeConsoleButton.Search =>
      if (rightTape.isTapeLoaded && leftTape.isTapeLoaded) {
        val pR = rightTape.pressSearch()
        val pL = leftTape.pressSearch()
        s"Dual Tape SEARCH: Right advanced to track $pR, Left advanced to track $pL."
      } else if (leftTape.isTapeLoaded) {
        val pos = leftTape.pressSearch()
        s"Left Tape SEARCH: advanced to reference track $pos."
      } else if (rightTape.isTapeLoaded) {
        val pos = rightTape.pressSearch()
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
  }

  def unloadTape(station: Device): Option[TapeCartridge] = station match {
    case Device.RightTape => rightTape.unmount()
    case Device.LeftTape  => leftTape.unmount()
    case _ => None
  }

  /**
   * Sets input station in loaded composition or bootstrap program in Core Memory.
   */
  def setInputStation(station: Device): Unit = {
    ContinuousComposerRoutine.setInputStation(memory, station)
  }

  def loadProgram(image: CoreImage): Unit = {
    memory.loadFromImage(image)
    cpu.reset()
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
