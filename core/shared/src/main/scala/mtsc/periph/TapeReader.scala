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

  // Tape Transport Console states (Training Guide p. 15)
  var isLoadedOnHub: Boolean = false // True when LOAD has threaded the tape from the cartridge
  var coverUnlocked: Boolean = true  // Interlocked: can only unlock when tape is unloaded into cartridge
  var searching: Boolean = false     // True while a Reference Code search is active

  def mount(cartridge: TapeCartridge): Unit = {
    mountedCartridge = Some(cartridge)
    headPosition = 0
    dataLatches = 0
    parityError = false
    cycleRemaining = 0
    isLoadedOnHub = true
    coverUnlocked = false // Cover pin locks when tape is loaded
    searching = false
  }

  def unmount(): Option[TapeCartridge] = {
    val cart = mountedCartridge
    mountedCartridge = None
    headPosition = 0
    parityError = false
    cycleRemaining = 0
    isLoadedOnHub = false
    coverUnlocked = true
    searching = false
    cart
  }

  /**
   * Simulates pressing LOAD LEFT or LOAD RIGHT on the Tape Transport Console.
   * Threads the mounted cartridge to the first recording position and locks the station cover.
   */
  def pressLoad(): Boolean = {
    if (mountedCartridge.isDefined) {
      isLoadedOnHub = true
      coverUnlocked = false
      headPosition = 0
      parityError = false
      searching = false
      true
    } else {
      false
    }
  }

  /**
   * Simulates pressing UNLOAD LEFT or UNLOAD RIGHT on the Tape Transport Console.
   * Rewinds the tape back into the cartridge so the station cover can be unlocked.
   */
  def pressUnload(): Boolean = {
    if (mountedCartridge.isDefined) {
      headPosition = 0
      isLoadedOnHub = false
      parityError = false
      searching = false
      true
    } else {
      false
    }
  }

  /**
   * Simulates pressing COVER UNLOCK on the Tape Transport Console.
   * Per Training Guide p. 15, the cover pin can only retract when the tape is unloaded into the cartridge.
   */
  def pressCoverUnlock(): Boolean = {
    if (!isLoadedOnHub) {
      coverUnlocked = true
      true
    } else {
      false
    }
  }

  /**
   * Simulates pressing REREAD on the Tape Transport Console.
   * Backsteps the tape to the start of the current line (or track 0) and clears parityError.
   */
  def pressReread(): Int = {
    parityError = false
    mountedCartridge match {
      case None => 0
      case Some(cart) =>
        // Backstep to the previous Carrier Return or track 0
        var pos = math.max(0, headPosition - 1)
        while (pos > 0 && cart.getFrame(pos - 1).exists(f => f.data7 != TapeCartridge.CarrierReturn && f.rawByte != TapeCartridge.EndOfBlock)) {
          pos -= 1
        }
        headPosition = pos
        headPosition
    }
  }

  /**
   * Simulates pressing SKIP on the Tape Transport Console.
   * Advances the tape past the current line (next Carrier Return) or block sentinel.
   */
  def pressSkip(): Int = {
    parityError = false
    mountedCartridge match {
      case None => 0
      case Some(cart) =>
        while (headPosition < cart.length) {
          val frame = cart.getFrame(headPosition).get
          headPosition += 1
          if (frame.data7 == TapeCartridge.CarrierReturn || frame.rawByte == TapeCartridge.EndOfBlock) {
            return headPosition
          }
        }
        headPosition
    }
  }

  /**
   * Simulates pressing SEARCH on the Tape Transport Console.
   * Advances the tape to the next Reference Code / block boundary.
   */
  def pressSearch(): Int = {
    searching = true
    val pos = pressSkip()
    searching = false
    pos
  }

  def isTapeLoaded: Boolean = mountedCartridge.isDefined && isLoadedOnHub

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
    if (cycleRemaining > 0 || !isLoadedOnHub) {
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

          // Sentinel 0xFF (on 7-bit text tape) is handled as EndOfBlock without parity fault
          if (!frame.is8Bit && frame.rawByte == TapeCartridge.EndOfBlock) {
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
            dataLatches = frame.dataByte
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
    searching = false
  }
}
