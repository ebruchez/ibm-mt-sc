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
  inline def decodedByteAddress(xDriver: Int, xSwitch: Int, yDriver: Int, ySwitch: Int): Int =
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
        case 7 => memory.psarRaw
        case 6 => memory.aWordRaw
        case 5 => memory.bWordRaw
        case _ => 0
      }
      if (isHighByte) (word >> 8) & 0xff else word & 0xff
    } else {
      val addr = decodedByteAddress(s.activeXDriver, s.activeXSwitch, s.activeYDriver, s.activeYSwitch)
      memory.readByteAt(addr)
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
          val cur = memory.psarRaw
          val updated = if (isHighByte) (cur & 0x00ff) | (cleanByte << 8) else (cur & 0xff00) | cleanByte
          val mask = if (s.edit) 0x7ff else 0x7fe
          memory.setPsarRaw(updated & mask)
        case 6 =>
          val cur = memory.aWordRaw
          val updated = if (isHighByte) (cur & 0x00ff) | (cleanByte << 8) else (cur & 0xff00) | cleanByte
          memory.setAWordRaw(updated)
        case 5 =>
          val cur = memory.bWordRaw
          val updated = if (isHighByte) (cur & 0x00ff) | (cleanByte << 8) else (cur & 0xff00) | cleanByte
          memory.setBWordRaw(updated)
        case _ => ()
      }
    } else {
      val addr = decodedByteAddress(s.activeXDriver, s.activeXSwitch, s.activeYDriver, s.activeYSwitch)
      memory.writeByteAt(addr, cleanByte)
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
    if (!s.csYLog || s.twr) 0 else destructiveReadNibbleDirect(s)
  }

  /**
   * Fast single-pass destructive Coincident-Current Read of the 4 selected cores when `csYLog`
   * is known active and `twr` is false.
   */
  inline def destructiveReadNibbleDirect(s: BackplaneSignals): Int = {
    val odd = s.btCtrA
    if (s.selMemSpec) {
      val shift = ((s.activeYSwitch & 1) << 3) | (if (odd) 4 else 0)
      val clrMask = ~(0x0f << shift)
      s.activeYDriver match {
        case 7 =>
          val cur = memory.psarRaw
          val nibble = (cur >>> shift) & 0x0f
          val mask = if (s.edit) 0x7ff else 0x7fe
          memory.setPsarRaw((cur & clrMask) & mask)
          nibble
        case 6 =>
          val cur = memory.aWordRaw
          val nibble = (cur >>> shift) & 0x0f
          memory.setAWordRaw(cur & clrMask)
          nibble
        case 5 =>
          val cur = memory.bWordRaw
          val nibble = (cur >>> shift) & 0x0f
          memory.setBWordRaw(cur & clrMask)
          nibble
        case _ => 0
      }
    } else {
      val addr = decodedByteAddress(s.activeXDriver, s.activeXSwitch, s.activeYDriver, s.activeYSwitch)
      val curByte = memory.readByteAt(addr)
      if (!odd) {
        memory.writeByteAt(addr, curByte & 0xf0)
        curByte & 0x0f
      } else {
        memory.writeByteAt(addr, curByte & 0x0f)
        (curByte >> 4) & 0x0f
      }
    }
  }

  /**
   * Fast single-pass Coincident-Current Write of a 4-bit nibble (`0..15`) to the currently
   * decoded `(selMemSpec, activeXDriver, activeXSwitch, activeYDriver, activeYSwitch)` byte and
   * Even/Odd (`btCtrA`) core half-byte.
   */
  inline def writeNibbleDirect(s: BackplaneSignals, writtenNibble: Int): Unit = {
    val cleanNib = writtenNibble & 0x0f
    val odd = s.btCtrA
    if (s.selMemSpec) {
      val shift = ((s.activeYSwitch & 1) << 3) | (if (odd) 4 else 0)
      val clrMask = ~(0x0f << shift)
      val insBits = cleanNib << shift
      s.activeYDriver match {
        case 7 =>
          val mask = if (s.edit) 0x7ff else 0x7fe
          memory.setPsarRaw(((memory.psarRaw & clrMask) | insBits) & mask)
        case 6 =>
          memory.setAWordRaw((memory.aWordRaw & clrMask) | insBits)
        case 5 =>
          memory.setBWordRaw((memory.bWordRaw & clrMask) | insBits)
        case _ => ()
      }
    } else {
      val addr = decodedByteAddress(s.activeXDriver, s.activeXSwitch, s.activeYDriver, s.activeYSwitch)
      val curByte = memory.readByteAt(addr)
      val newByte = if (!odd) (curByte & 0xf0) | cleanNib else (curByte & 0x0f) | (cleanNib << 4)
      memory.writeByteAt(addr, newByte)
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

    val writtenNibble = if (!s.btCtrA) {
      (if (!s.senseInhLineEv1) 1 else 0) |
        (if (!s.senseInhLineEv2) 2 else 0) |
        (if (!s.senseInhLineEv3) 4 else 0) |
        (if (!s.senseInhLineEv4) 8 else 0)
    } else {
      (if (!s.senseInhLineOdd1) 1 else 0) |
        (if (!s.senseInhLineOdd2) 2 else 0) |
        (if (!s.senseInhLineOdd3) 4 else 0) |
        (if (!s.senseInhLineOdd4) 8 else 0)
    }
    writeNibbleDirect(s, writtenNibble)
  }
}
