package mtsc.memory

import mtsc.types.*

/**
 * Represents a 2,048-byte memory image of the MT/SC core storage.
 */
final class CoreImage(val bytes: Array[Byte]) {
  require(bytes.length == CoreImage.Size, s"CoreImage must be exactly ${CoreImage.Size} bytes")

  def readByte(addr: Address): Int =
    bytes(addr.unsigned) & 0xff

  def writeByte(addr: Address, b: Int): Unit =
    bytes(addr.unsigned) = (b & 0xff).toByte

  def readWord(addr: Address): Word = {
    val lo = readByte(addr)
    val hi = readByte(addr + 1)
    Word.fromBytes(hi, lo)
  }

  def writeWord(addr: Address, w: Word): Unit = {
    writeByte(addr, w.lowByte)
    writeByte(addr + 1, w.highByte)
  }

  def cloneImage(): CoreImage =
    new CoreImage(bytes.clone())

  def toHexDump(start: Address = Address(0), length: Int = 128): String = {
    val sb = new StringBuilder()
    val end = math.min(start.unsigned + length, CoreImage.Size)
    var addr = start.unsigned & ~0xf // 16-byte aligned

    while (addr < end) {
      sb.append(Fmt.hex4(addr)).append(": ")
      val hexPart = (0 until 16).map { i =>
        val a = addr + i
        if (a < CoreImage.Size) s"${Fmt.hex2(bytes(a) & 0xff)} " else "   "
      }.mkString

      val asciiPart = (0 until 16).map { i =>
        val a = addr + i
        if (a < CoreImage.Size) {
          val b = bytes(a) & 0xff
          if (b >= 32 && b < 127) b.toChar else '.'
        } else ' '
      }.mkString

      sb.append(hexPart).append(" |").append(asciiPart).append("|\n")
      addr += 16
    }
    sb.toString()
  }
}

object CoreImage {
  val Size: Int = 2048

  def empty(): CoreImage =
    new CoreImage(new Array[Byte](Size))
}
