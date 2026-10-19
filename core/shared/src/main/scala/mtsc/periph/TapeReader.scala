package mtsc.periph

import mtsc.types.Device
import mtsc.cpu.IoResult

/**
 * Emulation of the IBM Model 6310 Magnetic Tape Reader.
 *
 * Supports two stations (Left and Right), incremental character reading at 20 cps,
 * hardware odd-parity verification, tape stepping magnets, and pipeline stall handshakes.
 */
class TapeReader(
  val station: Device,
  var cycleDelay: Int = 0 // 0 = immediate mode; >0 = cycle-accurate mode (e.g. 500 cycles)
) {
  require(station == Device.RightTape || station == Device.LeftTape, "TapeReader station must be Right or Left tape")

  var mountedCartridge: Option[TapeCartridge] = None
  var headPosition: Int = 0
  var dataLatches: Int = 0
  var parityError: Boolean = false
  var cycleRemaining: Int = 0
  var readCount: Long = 0

  def mount(cartridge: TapeCartridge): Unit = {
    mountedCartridge = Some(cartridge)
    headPosition = 0
    dataLatches = 0
    parityError = false
    cycleRemaining = 0
  }

  def unmount(): Option[TapeCartridge] = {
    val cart = mountedCartridge
    mountedCartridge = None
    headPosition = 0
    parityError = false
    cycleRemaining = 0
    cart
  }

  def isTapeLoaded: Boolean = mountedCartridge.isDefined

  def isAtEnd: Boolean =
    mountedCartridge.forall(_.isAtEnd(headPosition))

  /**
   * Corrupts the parity of the current track under the read head for diagnostic verification.
   */
  def injectParityError(): Boolean = {
    mountedCartridge match {
      case Some(cartridge) if headPosition < cartridge.length =>
        cartridge.corruptParityAt(headPosition)
        true
      case _ => false
    }
  }

  /**
   * Advances the internal mechanical cycle timer by 1 CCU cycle.
   */
  def stepCycle(): Unit = {
    if (cycleRemaining > 0) {
      cycleRemaining -= 1
    }
  }

  /**
   * Performs an input read cycle from the tape reader.
   *
   * Faithful to IBM Manual 241-5429-0 (pages 7-8 & 91):
   * 1. If currently in a mechanical stepping cycle, returns IoResult.Busy.
   * 2. Reads the frame at the current head position.
   * 3. Checks odd parity. If even parity occurs:
   *    - parityError is asserted.
   *    - The tape does NOT step (remains on same track).
   *    - Returns IoResult.Busy.
   * 4. If odd parity is satisfied:
   *    - parityError is cleared.
   *    - Character data is loaded into data latches.
   *    - Tape increments to next track (headPosition + 1).
   *    - Sets cycleRemaining = cycleDelay.
   *    - Returns IoResult.Ready(character).
   */
  def read(): IoResult[Int] = {
    if (cycleRemaining > 0) {
      return IoResult.Busy
    }

    mountedCartridge match {
      case None =>
        IoResult.Busy // No tape in drive

      case Some(cartridge) =>
        if (cartridge.isAtEnd(headPosition)) {
          // End of recorded tracks -> delivers EndOfBlock sentinel
          IoResult.Ready(TapeCartridge.EndOfBlock)
        } else {
          val frame = cartridge.getFrame(headPosition).get

          // Sentinel 0xFF is handled as EndOfBlock without parity fault
          if (frame.rawByte == TapeCartridge.EndOfBlock) {
            headPosition += 1
            readCount += 1
            dataLatches = TapeCartridge.EndOfBlock
            cycleRemaining = cycleDelay
            IoResult.Ready(TapeCartridge.EndOfBlock)
          } else if (!frame.hasOddParity) {
            // Parity Fault: Turn on error latch and refuse to increment tape
            parityError = true
            IoResult.Busy
          } else {
            // Odd Parity Satisfied: Increment tape and latch data
            parityError = false
            dataLatches = frame.data7
            headPosition += 1
            readCount += 1
            cycleRemaining = cycleDelay
            IoResult.Ready(dataLatches)
          }
        }
    }
  }

  def reset(): Unit = {
    headPosition = 0
    dataLatches = 0
    parityError = false
    cycleRemaining = 0
  }
}
