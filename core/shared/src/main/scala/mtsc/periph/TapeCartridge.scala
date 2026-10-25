package mtsc.periph

import mtsc.isa.{Encoder, Instruction}
import mtsc.memory.{CoreImage, CoreMemory}
import mtsc.types.{Address, Condition}
import scala.collection.mutable.ArrayBuffer

/**
 * Single recorded character on an IBM MT/ST magnetic tape track.
 *
 * Each tape frame contains 7 data bits plus 1 odd parity check bit.
 */
final case class TapeFrame(rawByte: Int, is8Bit: Boolean = false) {
  def data7: Int = rawByte & 0x7f
  def dataByte: Int = if (is8Bit) rawByte & 0xff else data7
  def parityBit: Boolean = if (is8Bit) (rawByte & 0x100) != 0 else (rawByte & 0x80) != 0
  def hasOddParity: Boolean =
    if (is8Bit) (Integer.bitCount(rawByte & 0x1ff) % 2) != 0
    else (Integer.bitCount(rawByte & 0xff) % 2) != 0
}

object TapeFrame {
  /**
   * Encodes a 7-bit character code with an odd parity bit in bit 7 (0x80).
   */
  def encodeWithOddParity(char7: Int): TapeFrame = {
    val b7 = char7 & 0x7f
    val ones = Integer.bitCount(b7)
    val raw = if (ones % 2 == 0) b7 | 0x80 else b7
    TapeFrame(raw)
  }

  /**
   * Encodes an 8-bit program byte with a 9th-track odd parity bit in bit 8 (0x100).
   */
  def encodeByte8WithOddParity(byte8: Int): TapeFrame = {
    val b8 = byte8 & 0xff
    val ones = Integer.bitCount(b8)
    val raw = if (ones % 2 == 0) b8 | 0x100 else b8
    TapeFrame(raw, is8Bit = true)
  }

  /**
   * Creates an invalid frame with intentional even parity for diagnostics.
   */
  def encodeWithCorruptParity(char7: Int): TapeFrame = {
    val valid = encodeWithOddParity(char7)
    TapeFrame(valid.rawByte ^ 0x80) // Flip parity bit to make it even
  }
}

/**
 * Model of an IBM MT/ST magnetic tape cartridge.
 *
 * Contains sequential tape frames, support for left/right reader stations,
 * feed codes, carrier return codes, and end-of-record sentinels.
 */
class TapeCartridge(val label: String, private val frames: ArrayBuffer[TapeFrame]) {

  def length: Int = frames.length

  def getFrame(index: Int): Option[TapeFrame] =
    if (index >= 0 && index < frames.length) Some(frames(index)) else None

  def isAtEnd(index: Int): Boolean = index >= frames.length

  /**
   * Injects an intentional parity error at a given index.
   */
  def corruptParityAt(index: Int): Unit =
    if (index >= 0 && index < frames.length) {
      val cur = frames(index)
      val parityMask = if (cur.is8Bit) 0x100 else 0x80
      frames(index) = TapeFrame(cur.rawByte ^ parityMask, is8Bit = cur.is8Bit)
    }
}

object TapeCartridge {

  // MT/SC Control Codes (IBM Manual 241-5429-0)
  val FeedCode: Int         = 0x00 // Leader / Feed code
  val SpaceCode: Int        = 0x20 // Space
  val CarrierReturn: Int    = 0x0D // Carrier Return
  val TabCode: Int          = 0x09 // Tab
  val StopCode: Int         = 0x1A // Line Stop / Operator Stop
  val EndOfBlock: Int       = 0xFF // End of Record / Block sentinel

  def empty(label: String = "Blank Tape"): TapeCartridge =
    new TapeCartridge(label, ArrayBuffer.empty)

  val HistoricalSampleText: String =
    """The IBM Magnetic Tape Selectric Composer is a system for preparing camera-ready copy for publication.
      |
      |Input data recorded on magnetic tape cartridges is read by the tape reader. Each tape cartridge holds approximately 4,000 words.
      |
      |The Composer Control Unit houses coincident-current magnetic core storage and Solid Logic Dense circuitry. Characters are read at 20 characters per second.
      |
      |Font escapement values are looked up automatically and line deficits are divided across inter-word space bands. The single-element spherical typing head prints camera-ready proofs at 14 characters per second.""".stripMargin

  val HistoricalMergeSampleText: String =
    """CORRECTION MERGE TAPE:
      |
      |The IBM MT/SC Dual Tape Reader (Model 6310) enables merging of revised copy from the Left Station into the original recorded tape on the Right Station.
      |
      |Correction blocks and updated paragraphs are read from the Left Tape during playback when Merge is enabled.""".stripMargin

  /**
   * Creates a tape cartridge from plain text.
   * Normalizes newlines, prepends a FeedCode, and appends EndOfBlock sentinel.
   */
  def fromText(text: String, label: String = "Text Tape"): TapeCartridge = {
    val buf = ArrayBuffer.empty[TapeFrame]
    // Leader feed code
    buf += TapeFrame.encodeWithOddParity(FeedCode)

    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    for (ch <- normalized) {
      val code = if (ch == '\n') CarrierReturn else ch.toInt & 0x7f
      buf += TapeFrame.encodeWithOddParity(code)
    }

    // End-of-block sentinel
    buf += TapeFrame(EndOfBlock) // 0xFF has 8 ones, but handled as hardware sentinel
    new TapeCartridge(label, buf)
  }

  /**
   * Creates a tape from raw bytes.
   */
  def fromRawBytes(bytes: Seq[Int], label: String = "Raw Tape"): TapeCartridge = {
    val buf = ArrayBuffer.empty[TapeFrame]
    for (b <- bytes) {
      buf += TapeFrame(b & 0xff)
    }
    new TapeCartridge(label, buf)
  }

  /**
   * Creates a binary program tape where each 8-bit byte has a 9th-track odd parity bit.
   */
  def fromProgramBytes(bytes: Seq[Int], label: String = "Program Tape"): TapeCartridge = {
    val buf = ArrayBuffer.empty[TapeFrame]
    for (b <- bytes) {
      buf += TapeFrame.encodeByte8WithOddParity(b & 0xff)
    }
    new TapeCartridge(label, buf)
  }

  /**
   * Encodes a complete 2,048-byte `CoreImage` into a bootable binary Factory Program Tape
   * designed to be loaded by the 8-byte `INITIAL LOADER` (`40..47`, `PSAR = 42` / `0x02A`).
   *
   * Stream layout (2,083 8-bit odd-parity frames):
   * 1. Pass 1 (`40` bytes `0..39`): advances `R20` from `0` to `40`.
   * 2. Redirect (`1` byte at `40` = `48` / `0x30`): leaps `R20` over the live `INITIAL LOADER` (`41..47`).
   * 3. Upper Core (`2,000` bytes `48..2047`): populates escapement tables, buffers, and microcode;
   *    `(2047 + 1) & 0x7FF` wraps `R20` back to `0`. If `entryPoint >= 256`, places a 2-byte
   *    `JMP entryPoint` trampoline in an unused even word `< 256` (preferring `194..195`).
   * 4. Pass 2 (`40` bytes `0..39`): restores working registers `R0` and `R2..R19`.
   * 5. Exit (`2` bytes): redirects `R20` at `40` to `46`, then overwrites byte `46` (the low byte
   *    of `JMP 0x02A`) so execution transfers cleanly to `entryPoint`.
   */
  def fromCoreImage(
    image: CoreImage,
    entryPoint: Address = Address.PowerOn,
    label: String = "Factory Program Tape"
  ): TapeCartridge = {
    require(image.bytes.length == CoreMemory.Size, s"CoreImage must be ${CoreMemory.Size} bytes")
    require((entryPoint.unsigned & 1) == 0, s"Entry point must be word-aligned (even): $entryPoint")
    require(
      entryPoint.unsigned < 40 || entryPoint.unsigned >= 48,
      s"Entry point cannot lie inside Initial Loader workspace (40..47): $entryPoint"
    )

    val jmpWord = Encoder.encode(Instruction.Branch(Condition.Unconditional, link = false, entryPoint))
    val trampolineOpt: Option[Int] =
      if (entryPoint.unsigned < 256) {
        None
      } else {
        val candidates = Seq(194, 192, 196) ++ (198 to 254 by 2) ++ (48 to 190 by 2)
        val existing = candidates.find { addr =>
          (image.bytes(addr) & 0xff) == jmpWord.lowByte &&
          (image.bytes(addr + 1) & 0xff) == jmpWord.highByte
        }
        val zeroSlot = candidates.find { addr =>
          image.bytes(addr) == 0 && image.bytes(addr + 1) == 0
        }
        Some(existing.orElse(zeroSlot).getOrElse(194))
      }

    val stream = ArrayBuffer.empty[Int]
    stream.sizeHint(2083)

    // 1. Pass 1 over 0..39 (40 bytes) -> R20 advances to 40
    var i = 0
    while (i < 40) {
      stream += (image.bytes(i) & 0xff)
      i += 1
    }

    // 2. Redirect R20 at byte 40 to 48 (0x30), preserving live Initial Loader at 42..47
    stream += 48

    // 3. Upper Core 48..2047 (2,000 bytes) -> R20 wraps (2047 + 1) & 0x7FF -> 0
    var addr = 48
    while (addr < CoreMemory.Size) {
      val b = trampolineOpt match {
        case Some(tAddr) if addr == tAddr     => jmpWord.lowByte
        case Some(tAddr) if addr == tAddr + 1 => jmpWord.highByte
        case _                                => image.bytes(addr) & 0xff
      }
      stream += b
      addr += 1
    }

    // 4. Pass 2 over 0..39 (40 bytes) -> restores R0 and R2..R19; R20 advances to 40
    i = 0
    while (i < 40) {
      stream += (image.bytes(i) & 0xff)
      i += 1
    }

    // 5. Redirect R20 at byte 40 to 46, then patch byte 46 of `JMP 0x02A` to jump out
    stream += 46
    stream += trampolineOpt.getOrElse(jmpWord.lowByte)

    fromProgramBytes(stream.toSeq, label)
  }
}
