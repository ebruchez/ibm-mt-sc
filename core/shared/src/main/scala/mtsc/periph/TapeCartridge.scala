package mtsc.periph

import scala.collection.mutable.ArrayBuffer

/**
 * Single recorded character on an IBM MT/ST magnetic tape track.
 *
 * Each tape frame contains 7 data bits plus 1 odd parity check bit.
 */
final case class TapeFrame(rawByte: Int) {
  def data7: Int = rawByte & 0x7f
  def parityBit: Boolean = (rawByte & 0x80) != 0
  def hasOddParity: Boolean = (Integer.bitCount(rawByte & 0xff) % 2) != 0
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
  def corruptParityAt(index: Int): Unit = {
    if (index >= 0 && index < frames.length) {
      val cur = frames(index)
      frames(index) = TapeFrame(cur.rawByte ^ 0x80)
    }
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
      val code = if (ch == '\n') CarrierReturn else (ch.toInt & 0x7f)
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
}
