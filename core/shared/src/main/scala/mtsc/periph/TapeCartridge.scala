package mtsc.periph

import mtsc.isa.{Encoder, Instruction}
import mtsc.memory.{CoreImage, CoreMemory}
import mtsc.types.{Address, Condition}

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
   * Returns raw 8-bit frame value (7 data bits + odd parity in bit 7).
   */
  @inline def encode7Raw(char7: Int): Int = {
    val b7 = char7 & 0x7f
    val ones = Integer.bitCount(b7)
    if ((ones & 1) == 0) b7 | 0x80 else b7
  }

  /**
   * Returns raw 9-bit frame value (8 data bits + odd parity in bit 8).
   */
  @inline def encode8Raw(byte8: Int): Int = {
    val b8 = byte8 & 0xff
    val ones = Integer.bitCount(b8)
    if ((ones & 1) == 0) b8 | 0x100 else b8
  }

  @inline def pack(rawByte: Int, is8Bit: Boolean): Int =
    (rawByte & 0x1ff) | (if (is8Bit) 0x200 else 0)

  @inline def encode7Packed(char7: Int): Int =
    encode7Raw(char7)

  @inline def encode8Packed(byte8: Int): Int =
    encode8Raw(byte8) | 0x200

  /**
   * Encodes a 7-bit character code with an odd parity bit in bit 7 (0x80).
   */
  def encodeWithOddParity(char7: Int): TapeFrame =
    TapeFrame(encode7Raw(char7))

  /**
   * Encodes an 8-bit program byte with a 9th-track odd parity bit in bit 8 (0x100).
   */
  def encodeByte8WithOddParity(byte8: Int): TapeFrame =
    TapeFrame(encode8Raw(byte8), is8Bit = true)

  /**
   * Creates an invalid frame with intentional even parity for diagnostics.
   */
  def encodeWithCorruptParity(char7: Int): TapeFrame =
    TapeFrame(encode7Raw(char7) ^ 0x80) // Flip parity bit to make it even
}

/**
 * Model of an IBM MT/ST magnetic tape cartridge.
 *
 * Stores frames in fixed-size 512-element primitive `Array[Short]` chunks (1,036 bytes per chunk)
 * so that even a 2,083-frame Factory Program Tape fits entirely within single-block Immix allocations
 * with zero boxed `TapeFrame` objects and zero multi-block superblock requests.
 *
 * Each packed `Short` encodes:
 * - bits `0..8` (`0x1ff`): `rawByte` (7-bit + parity or 8-bit + parity)
 * - bit `9` (`0x200`): `1` if `is8Bit`, `0` if 7-bit
 */
class TapeCartridge private (
  val label: String,
  val length: Int,
  private val chunks: Array[Array[Short]]
) {
  import TapeCartridge.{ChunkMask, ChunkShift}

  def this(label: String, totalLength: Int) =
    this(label, totalLength, TapeCartridge.allocateChunks(totalLength))

  @inline private[periph] def getPacked(index: Int): Int =
    chunks(index >>> ChunkShift)(index & ChunkMask) & 0xffff

  @inline private[periph] def setPacked(index: Int, packed: Int): Unit =
    chunks(index >>> ChunkShift)(index & ChunkMask) = packed.toShort

  @inline def rawByteAt(index: Int): Int =
    getPacked(index) & 0x1ff

  @inline def is8BitAt(index: Int): Boolean =
    (getPacked(index) & 0x200) != 0

  @inline def data7At(index: Int): Int =
    getPacked(index) & 0x7f

  @inline def dataByteAt(index: Int): Int = {
    val p = getPacked(index)
    if ((p & 0x200) != 0) p & 0xff else p & 0x7f
  }

  @inline def hasOddParityAt(index: Int): Boolean = {
    val p = getPacked(index)
    val mask = if ((p & 0x200) != 0) 0x1ff else 0xff
    (Integer.bitCount(p & mask) & 1) != 0
  }

  def getFrame(index: Int): Option[TapeFrame] =
    if (index >= 0 && index < length) {
      val p = getPacked(index)
      Some(TapeFrame(p & 0x1ff, is8Bit = (p & 0x200) != 0))
    } else {
      None
    }

  def isAtEnd(index: Int): Boolean = index >= length

  /**
   * Injects an intentional parity error at a given index.
   */
  def corruptParityAt(index: Int): Unit =
    if (index >= 0 && index < length) {
      val p = getPacked(index)
      val parityMask = if ((p & 0x200) != 0) 0x100 else 0x80
      setPacked(index, p ^ parityMask)
    }
}

object TapeCartridge {

  private final val ChunkShift: Int = 9
  private final val ChunkSize: Int  = 1 << ChunkShift // 512 shorts = 1,036 bytes (< 1 Immix 2KB block)
  private final val ChunkMask: Int  = ChunkSize - 1

  private def allocateChunks(totalLength: Int): Array[Array[Short]] = {
    if (totalLength <= 0) {
      new Array[Array[Short]](0)
    } else {
      val numChunks = (totalLength + ChunkMask) >>> ChunkShift
      val chunks = new Array[Array[Short]](numChunks)
      var c = 0
      while (c < numChunks) {
        val rem = totalLength - (c << ChunkShift)
        val sz = if (rem < ChunkSize) rem else ChunkSize
        chunks(c) = new Array[Short](sz)
        c += 1
      }
      chunks
    }
  }

  // MT/SC Control Codes (IBM Manual 241-5429-0)
  val FeedCode: Int         = 0x00 // Leader / Feed code
  val SpaceCode: Int        = 0x20 // Space
  val CarrierReturn: Int    = 0x0D // Carrier Return
  val TabCode: Int          = 0x09 // Tab
  val StopCode: Int         = 0x1A // Line Stop / Operator Stop
  val EndOfBlock: Int       = 0xFF // End of Record / Block sentinel

  def empty(label: String = "Blank Tape"): TapeCartridge =
    new TapeCartridge(label, 0)

  val HistoricalSampleText: String =
    "The IBM Magnetic Tape Selectric Composer (MT/SC) is a system for preparing camera-ready copy for publication.\n\n" +
    "Input data is recorded on magnetic tape cartridges on a separate input station (MT/SR) and placed on the MT/SC tape console. Each tape cartridge holds approximately 4,000 words, read at 20 characters per second.\n\n" +
    "The Composer Control Unit (CCU) is a simple computer build with IBM SLD (Solid Logic Dense) modules and 2KB of core memory. " +
    "Its purpose is to read the tape, compute justification spaces, and output the result to a modified IBM Composer at 14 characters per second.\n\n" +
    "There is only one instance of the system known to have survived, including an input station with tape recorder, typewriter, and desk; and an output station with tape reader, control unit, Composer, and desk.\n\n" +
    "You are looking at a software simulation at the gate level of the CCU. It is written in Scala, running on an RP2040 microcontroller, and printing results to an eInk display."

  val HistoricalMergeSampleText: String =
    "CORRECTION MERGE TAPE:\n\n" +
      "The IBM MT/SC Dual Tape Reader (Model 6310) enables merging of revised copy from the Left Station into the original recorded tape on the Right Station.\n\n" +
      "Correction blocks and updated paragraphs are read from the Left Tape during playback when Merge is enabled."

  /**
   * Creates a tape cartridge from plain text.
   * Normalizes newlines, prepends a FeedCode, and appends EndOfBlock sentinel.
   * Implemented with direct primitive loops (zero temporary Strings, zero ArrayBuffers, zero Lambdas).
   */
  def fromText(text: String, label: String = "Text Tape"): TapeCartridge = {
    val n = text.length
    var normLen = 0
    var i = 0
    while (i < n) {
      val ch = text.charAt(i)
      if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
        i += 1
      }
      normLen += 1
      i += 1
    }

    val totalFrames = normLen + 2
    val cart = new TapeCartridge(label, totalFrames)
    cart.setPacked(0, TapeFrame.encode7Packed(FeedCode))

    var outIdx = 1
    i = 0
    while (i < n) {
      val ch = text.charAt(i)
      if (ch == '\r') {
        if (i + 1 < n && text.charAt(i + 1) == '\n') {
          i += 1
        }
        cart.setPacked(outIdx, TapeFrame.encode7Packed(CarrierReturn))
      } else if (ch == '\n') {
        cart.setPacked(outIdx, TapeFrame.encode7Packed(CarrierReturn))
      } else {
        cart.setPacked(outIdx, TapeFrame.encode7Packed(ch.toInt & 0x7f))
      }
      outIdx += 1
      i += 1
    }

    // End-of-block sentinel (0xFF has 8 ones, handled as hardware sentinel)
    cart.setPacked(outIdx, EndOfBlock & 0x1ff)
    cart
  }

  /**
   * Creates a tape from raw bytes.
   */
  def fromRawBytes(bytes: Seq[Int], label: String = "Raw Tape"): TapeCartridge = {
    val n = bytes.length
    val cart = new TapeCartridge(label, n)
    var i = 0
    while (i < n) {
      cart.setPacked(i, bytes(i) & 0xff)
      i += 1
    }
    cart
  }

  /**
   * Creates a binary program tape where each 8-bit byte has a 9th-track odd parity bit.
   */
  def fromProgramBytes(bytes: Seq[Int], label: String = "Program Tape"): TapeCartridge = {
    val n = bytes.length
    val cart = new TapeCartridge(label, n)
    var i = 0
    while (i < n) {
      cart.setPacked(i, TapeFrame.encode8Packed(bytes(i) & 0xff))
      i += 1
    }
    cart
  }

  private val preferredTrampolineAddrs: Array[Int] = Array(194, 192, 196)

  private def findTrampolineSlot(bytes: Array[Byte], lowB: Int, highB: Int): Int = {
    // 1. Check if an existing word in 48..254 already contains the exact JMP instruction
    var i = 0
    while (i < preferredTrampolineAddrs.length) {
      val a = preferredTrampolineAddrs(i)
      if ((bytes(a) & 0xff) == lowB && (bytes(a + 1) & 0xff) == highB) return a
      i += 1
    }
    var a = 198
    while (a <= 254) {
      if ((bytes(a) & 0xff) == lowB && (bytes(a + 1) & 0xff) == highB) return a
      a += 2
    }
    a = 48
    while (a <= 190) {
      if ((bytes(a) & 0xff) == lowB && (bytes(a + 1) & 0xff) == highB) return a
      a += 2
    }

    // 2. Otherwise pick the first zero word in preferred order (194, 192, 196, 198..254, 48..190)
    i = 0
    while (i < preferredTrampolineAddrs.length) {
      val addr = preferredTrampolineAddrs(i)
      if (bytes(addr) == 0 && bytes(addr + 1) == 0) return addr
      i += 1
    }
    a = 198
    while (a <= 254) {
      if (bytes(a) == 0 && bytes(a + 1) == 0) return a
      a += 2
    }
    a = 48
    while (a <= 190) {
      if (bytes(a) == 0 && bytes(a + 1) == 0) return a
      a += 2
    }
    194
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
    val trampolineAddr: Int =
      if (entryPoint.unsigned < 256) -1
      else findTrampolineSlot(image.bytes, jmpWord.lowByte, jmpWord.highByte)

    val cart = new TapeCartridge(label, 2083)
    var outIdx = 0

    // 1. Pass 1 over 0..39 (40 bytes) -> R20 advances to 40
    var i = 0
    while (i < 40) {
      cart.setPacked(outIdx, TapeFrame.encode8Packed(image.bytes(i) & 0xff))
      outIdx += 1
      i += 1
    }

    // 2. Redirect R20 at byte 40 to 48 (0x30), preserving live Initial Loader at 42..47
    cart.setPacked(outIdx, TapeFrame.encode8Packed(48))
    outIdx += 1

    // 3. Upper Core 48..2047 (2,000 bytes) -> R20 wraps (2047 + 1) & 0x7FF -> 0
    var addr = 48
    while (addr < CoreMemory.Size) {
      val b =
        if (trampolineAddr >= 0 && addr == trampolineAddr) jmpWord.lowByte
        else if (trampolineAddr >= 0 && addr == trampolineAddr + 1) jmpWord.highByte
        else image.bytes(addr) & 0xff
      cart.setPacked(outIdx, TapeFrame.encode8Packed(b))
      outIdx += 1
      addr += 1
    }

    // 4. Pass 2 over 0..39 (40 bytes) -> restores R0 and R2..R19; R20 advances to 40
    i = 0
    while (i < 40) {
      cart.setPacked(outIdx, TapeFrame.encode8Packed(image.bytes(i) & 0xff))
      outIdx += 1
      i += 1
    }

    // 5. Redirect R20 at byte 40 to 46, then patch byte 46 of `JMP 0x02A` to jump out
    cart.setPacked(outIdx, TapeFrame.encode8Packed(46))
    outIdx += 1
    val exitLowByte = if (trampolineAddr >= 0) trampolineAddr else jmpWord.lowByte
    cart.setPacked(outIdx, TapeFrame.encode8Packed(exitLowByte))
    cart
  }
}
