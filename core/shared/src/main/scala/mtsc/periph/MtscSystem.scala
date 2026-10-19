package mtsc.periph

import mtsc.types.*
import mtsc.memory.{CoreImage, CoreMemory}
import mtsc.cpu.{Ccu, StepResult}

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

  def loadTape(station: Device, cartridge: TapeCartridge): Unit = {
    station match {
      case Device.RightTape => rightTape.mount(cartridge)
      case Device.LeftTape  => leftTape.mount(cartridge)
      case _ => throw new IllegalArgumentException("Can only load tape on RightTape or LeftTape")
    }
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
