package mtsc.periph

import mtsc.cpu.IoResult
import mtsc.diag.EscapementRoutine

/**
 * Emulation of the Translator interface and Selectric Composer print mechanism.
 *
 * Simulates:
 * - 14 characters-per-second mechanical typing cycle (~71 ms).
 * - Selection magnets (Tilt and Rotate) and cycle clutch.
 * - Busy / Ready handshake with CCU.
 * - Multiplex background output coordination.
 * - Camera-ready galley paper accumulation.
 */
class ComposerTranslator(
  var cycleDelay: Int = 0 // 0 = immediate mode; >0 = cycle-accurate mode (e.g. 700 CCU cycles)
) {

  val paper: ComposerPaper = new ComposerPaper()

  var busy: Boolean = false
  var cycleRemaining: Int = 0
  var lastOutputByte: Int = 0
  var printCount: Long = 0
  var isMultiplexActive: Boolean = false

  def isReady: Boolean = !busy && cycleRemaining == 0

  /**
   * Advances the mechanical cycle timer by 1 CCU cycle.
   */
  def stepCycle(): Unit = {
    if (cycleRemaining > 0) {
      cycleRemaining -= 1
      if (cycleRemaining == 0) {
        busy = false // Mechanical cycle complete: Translator is Ready again
      }
    }
  }

  /**
   * Accepts an output byte from the CCU (via OUT or OUTM).
   *
   * @param byte 8-bit output character code
   * @param multiplex true if initiated in Multiplex mode
   */
  def write(byte: Int, multiplex: Boolean): IoResult[Unit] = {
    if (busy) {
      return IoResult.Busy
    }

    lastOutputByte = byte & 0xff
    isMultiplexActive = multiplex

    // Handle Halt Code (0xFF): terminates multiplex mode
    if (lastOutputByte == TapeCartridge.EndOfBlock) {
      isMultiplexActive = false
      return IoResult.Ready(())
    }

    // Process character to paper
    lastOutputByte match {
      case 0x0D | 0x0A => // Carrier Return / Line Feed
        paper.carrierReturn()

      case 0x20 => // Inter-word Space
        paper.printChar(' ', escapementUnits = 4)

      case b if b >= 32 && b <= 126 => // Printable ASCII
        val ch = b.toChar
        val units = EscapementRoutine.defaultUnitWidth(ch)
        paper.printChar(ch, escapementUnits = units)

      case _ => // Control / Function code
        // Ignored or non-printing function
    }

    printCount += 1
    if (cycleDelay > 0) {
      busy = true
      cycleRemaining = cycleDelay
    }

    IoResult.Ready(())
  }

  def reset(): Unit = {
    busy = false
    cycleRemaining = 0
    lastOutputByte = 0
    isMultiplexActive = false
    paper.clear()
  }
}
