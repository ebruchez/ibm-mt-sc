package mtsc.cpu.ild

import mtsc.memory.CoreMemory
import mtsc.types.{Address, Word}

/**
 * 3D 4-Plane Coincident-Current Magnetic Core Memory Array for the IBM MT/SC 6320 CCU
 * (`ILD 0305`–`0307`, `0310`, and `CE Instruction Manual` Form 241-5429-0 pp. 34–42).
 *
 * Physically, the core array consists of 4 Sense/Inhibit Planes (`Plane 1..4`, corresponding to
 * nibble bit weights `1, 2, 4, 8`). Each plane is driven by:
 * - 8 X-Drivers (`X DRV 1..8`, `0..7`) and 8 X-Switches (`X SW 1..8`, `0..7`) -> 64 X-lines
 * - 8 Y-Drivers (`Y DRV 1..8`, `0..7`) and 4 Y-Switches (`Y SW 1..4`, `0..3`) -> 32 Y-lines
 * - Even (`REWO` Read / `ROWE` Write) and Odd (`ROWE` Read / `REWO` Write) core windings per byte
 *
 * Special registers `PSAR`, `A-Word`, and `B-Word` reside in the upper special section of the core array
 * selected when `SEL MEM SPEC` is true (`X DRV 8`, `X SW 8`, `Y SW 3/4`):
 * - `Y DRV 8` (`yDriver = 7`): `PSAR` (Program Step Address Register)
 * - `Y DRV 7` (`yDriver = 6`): `A-Word` (Instruction / Immediate / P-Indirect Pointer Register)
 * - `Y DRV 6` (`yDriver = 5`): `B-Word` (Working Q-Operand / Q-Indirect Pointer Register)
 *
 * Wraps [[CoreMemory]] directly so that all 2,048 general core bytes and special registers remain
 * in sync for inspection and co-simulation.
 */
final class CoreMatrix3D(val memory: CoreMemory) {

  /**
   * Computes the 11-bit byte address (`0..2047`) decoded by `(xDriver, xSwitch, yDriver, ySwitch)`
   * when `SEL MEM` is active (`!s.selMemSpec`).
   */
  def decodedByteAddress(xDriver: Int, xSwitch: Int, yDriver: Int, ySwitch: Int): Int =
    (ySwitch & 1) |
      ((yDriver & 3) << 1) |
      ((xSwitch & 7) << 3) |
      (((ySwitch >> 1) & 1) << 6) |
      ((xDriver & 7) << 7) |
      (((yDriver >> 2) & 1) << 10)

  /**
   * Reads a single byte (`0..255`) from the core array at the current `(xDriver, xSwitch, yDriver, ySwitch)`
   * and `selMemSpec` coordinates without disturbing core state (for inspection/debugging).
   */
  def peekSelectedByte(s: BackplaneSignals): Int = {
    val isHighByte = (s.activeYSwitch & 1) != 0
    if (s.selMemSpec) {
      val word = s.activeYDriver match {
        case 7 => Word(memory.psar.unsigned)
        case 6 => memory.aWord
        case 5 => memory.bWord
        case _ => Word.Zero
      }
      if (isHighByte) word.highByte else word.lowByte
    } else {
      val addr = decodedByteAddress(s.activeXDriver, s.activeXSwitch, s.activeYDriver, s.activeYSwitch)
      memory.readByte(Address(addr))
    }
  }

  /**
   * Writes a single byte (`0..255`) to the core array at the current `(xDriver, xSwitch, yDriver, ySwitch)`
   * and `selMemSpec` coordinates.
   */
  private def pokeSelectedByte(s: BackplaneSignals, byteVal: Int): Unit = {
    val cleanByte = byteVal & 0xff
    val isHighByte = (s.activeYSwitch & 1) != 0
    if (s.selMemSpec) {
      s.activeYDriver match {
        case 7 =>
          val cur = memory.psar.unsigned
          val updated = if (isHighByte) (cur & 0x00ff) | (cleanByte << 8) else (cur & 0xff00) | cleanByte
          val mask = if (s.edit) 0x7ff else 0x7fe
          memory.psar = Address(updated & mask)
        case 6 =>
          val cur = memory.aWord.unsigned
          val updated = if (isHighByte) (cur & 0x00ff) | (cleanByte << 8) else (cur & 0xff00) | cleanByte
          memory.aWord = Word(updated)
        case 5 =>
          val cur = memory.bWord.unsigned
          val updated = if (isHighByte) (cur & 0x00ff) | (cleanByte << 8) else (cur & 0xff00) | cleanByte
          memory.bWord = Word(updated)
        case _ => ()
      }
    } else {
      val addr = decodedByteAddress(s.activeXDriver, s.activeXSwitch, s.activeYDriver, s.activeYSwitch)
      memory.writeByte(Address(addr), cleanByte)
    }
  }

  /**
   * Performs a destructive Coincident-Current Read of the 4 cores in Planes `1..4` selected by
   * the active X/Y Drivers, X/Y Switches, and Even/Odd sense gate (`REWO` vs `ROWE`).
   *
   * If `csYLog` is active (not inhibited by `inhibitMemory` or `killCsYUpperByte`), the 4 addressed
   * cores are cleared to `0` and any core that switched from `1 -> 0` induces a raw sense pulse
   * returned as a 4-bit nibble (`bit 0 = Plane 1 .. bit 3 = Plane 4`).
   */
  def destructiveReadNibble(s: BackplaneSignals): Int = {
    if (!s.csYLog || s.twr) {
      return 0
    }

    val curByte = peekSelectedByte(s)
    // Even cores (bits 0..3) are read when rewoSenseGate is active (!btCtrA);
    // Odd cores (bits 4..7) are read when roweSenseGate is active (btCtrA).
    if (!s.btCtrA) {
      val nibble = curByte & 0x0f
      pokeSelectedByte(s, curByte & 0xf0) // Destructive read clears Even cores to 0
      nibble
    } else {
      val nibble = (curByte >> 4) & 0x0f
      pokeSelectedByte(s, curByte & 0x0f) // Destructive read clears Odd cores to 0
      nibble
    }
  }

  /**
   * Performs a Coincident-Current Write of the 4 cores in Planes `1..4` selected by
   * the active X/Y Drivers, X/Y Switches, and Even/Odd write gate (`ROWE` for Even, `REWO` for Odd),
   * opposed by the Sense/Inhibit lines (`senseInhLineEv1..4` / `senseInhLineOdd1..4`).
   *
   * Each selected core is flipped to `1` unless its corresponding Inhibit Current Driver is active.
   */
  def writeInhibitedNibble(s: BackplaneSignals): Unit = {
    if (!s.csYLog || !s.twr) {
      return
    }

    val curByte = peekSelectedByte(s)
    if (!s.btCtrA) {
      // Write Even cores (B1-4, B9-12): inhibited by SENSE INH LINE EV 1..4
      val b1 = if (!s.senseInhLineEv1) 1 else 0
      val b2 = if (!s.senseInhLineEv2) 2 else 0
      val b3 = if (!s.senseInhLineEv3) 4 else 0
      val b4 = if (!s.senseInhLineEv4) 8 else 0
      val writtenNibble = b1 | b2 | b3 | b4
      pokeSelectedByte(s, (curByte & 0xf0) | writtenNibble)
    } else {
      // Write Odd cores (B5-8, B13-16): inhibited by SENSE INH LINE ODD 1..4
      val b1 = if (!s.senseInhLineOdd1) 1 else 0
      val b2 = if (!s.senseInhLineOdd2) 2 else 0
      val b3 = if (!s.senseInhLineOdd3) 4 else 0
      val b4 = if (!s.senseInhLineOdd4) 8 else 0
      val writtenNibble = b1 | b2 | b3 | b4
      pokeSelectedByte(s, (curByte & 0x0f) | (writtenNibble << 4))
    }
  }
}
