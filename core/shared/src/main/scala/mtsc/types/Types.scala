package mtsc.types

/**
 * 16-bit Word representation.
 * The MT/SC CCU operates natively on 16-bit words (0x0000 to 0xFFFF).
 */
final case class Word(value: Int) extends AnyVal {
  def unsigned: Int = value & 0xffff
  def signed: Short = unsigned.toShort
  def highByte: Int = (unsigned >> 8) & 0xff
  def lowByte: Int  = unsigned & 0xff

  def bit(index: Int): Boolean = ((unsigned >> index) & 1) == 1
  def withBit(index: Int, set: Boolean): Word = {
    val mask = 1 << index
    Word(if (set) unsigned | mask else unsigned & ~mask)
  }

  def toHexString: String = Fmt.hex4(unsigned)
  override def toString: String = s"0x${Fmt.hex4(unsigned)}"
}

object Word {
  val Zero: Word = Word(0)

  def fromBytes(high: Int, low: Int): Word =
    Word(((high & 0xff) << 8) | (low & 0xff))
}

/**
 * 11-bit Address representation in 2,048-byte core memory.
 */
final case class Address(value: Int) extends AnyVal {
  def unsigned: Int = value & 0x7ff
  def isWordAligned: Boolean = (unsigned & 1) == 0
  def +(offset: Int): Address = Address((unsigned + offset) & 0x7ff)
  def -(offset: Int): Address = Address((unsigned - offset) & 0x7ff)

  def toHexString: String = Fmt.hex3(unsigned)
  override def toString: String = s"0x${Fmt.hex3(unsigned)}($unsigned)"
}

object Address {
  val PowerOn: Address = Address(546) // Hardware power-on instruction address
  val Max: Address     = Address(2046)
}

/**
 * Register Index: Working registers 0 to 31.
 * Working registers reside at byte addresses (RegId * 2) in core storage.
 */
final case class RegId(index: Int) {
  require(index >= 0 && index < 32, s"RegId must be between 0 and 31, got $index")
  def byteAddress: Address = Address(index * 2)
  override def toString: String = s"R$index"
}

/**
 * Branch condition codes.
 */
enum Condition(val code: Int) {
  case Unconditional extends Condition(0)
  case Hi            extends Condition(1) // Not Low and Not Equal (> 0)
  case Lo            extends Condition(2) // Negative (< 0)
  case Equal         extends Condition(3) // Zero (= 0)
  case NotEqual      extends Condition(4) // Non-zero (!= 0)
}

/**
 * Arithmetic / SCAT operations (Subtract, Compare, Add, Transfer).
 */
enum ArithOp(val code: Int) {
  case Subtract extends ArithOp(0) // P - Q -> P  or  P - I -> P
  case Compare  extends ArithOp(1) // P - Q (flags only)
  case Add      extends ArithOp(2) // P + Q -> P  or  P + I -> P
  case Transfer extends ArithOp(3) // Q -> P      or  I -> P
}

/**
 * Program Control (Bit manipulation) operations.
 */
enum BitOp(val code: Int) {
  case Reset    extends BitOp(0) // Clear bit to 0
  case Set      extends BitOp(1) // Set bit to 1
  case Preserve extends BitOp(2) // Preserve bit (sample bit without change)
  case Test     extends BitOp(3) // Test bit and update condition latches
}

/**
 * Input / Output Peripheral Devices.
 */
enum Device(val code: Int) {
  case RightTape  extends Device(0)
  case LeftTape   extends Device(1)
  case PaperTape  extends Device(2)
  case Keyboard   extends Device(3)
  case Translator extends Device(4) // Modified Selectric Composer printer
  case Lights     extends Device(5) // Console display lamps
}

/**
 * Lightweight, regex-free string formatting and ASCII case-conversion helpers.
 * Avoids pulling `java.util.Formatter`, `scala.scalanative.regex`, and `StringSpecialCasing`
 * into bare-metal RP2040 builds.
 */
object Fmt {
  private val HexChars: Array[Char] = "0123456789ABCDEF".toCharArray

  def hex2(v: Int): String = {
    val c0 = HexChars((v >> 4) & 0xf)
    val c1 = HexChars(v & 0xf)
    new String(Array(c0, c1))
  }

  def hex3(v: Int): String = {
    val c0 = HexChars((v >> 8) & 0xf)
    val c1 = HexChars((v >> 4) & 0xf)
    val c2 = HexChars(v & 0xf)
    new String(Array(c0, c1, c2))
  }

  def hex4(v: Int): String = {
    val c0 = HexChars((v >> 12) & 0xf)
    val c1 = HexChars((v >> 8) & 0xf)
    val c2 = HexChars((v >> 4) & 0xf)
    val c3 = HexChars(v & 0xf)
    new String(Array(c0, c1, c2, c3))
  }

  def padLeft(s: String, width: Int, padChar: Char = ' '): String = {
    val len = s.length
    if (len >= width) s
    else {
      val out = new Array[Char](width)
      val pad = width - len
      var i = 0
      while (i < pad) {
        out(i) = padChar
        i += 1
      }
      var j = 0
      while (j < len) {
        out(pad + j) = s.charAt(j)
        j += 1
      }
      new String(out)
    }
  }

  def padRight(s: String, width: Int, padChar: Char = ' '): String = {
    val len = s.length
    if (len >= width) s
    else {
      val out = new Array[Char](width)
      var i = 0
      while (i < len) {
        out(i) = s.charAt(i)
        i += 1
      }
      while (i < width) {
        out(i) = padChar
        i += 1
      }
      new String(out)
    }
  }

  def dec02(v: Int): String = padLeft(v.toString, 2, '0')
  def dec06(v: Long): String = padLeft(v.toString, 6, '0')
  def dec2(v: Int): String = padLeft(v.toString, 2, ' ')
  def dec3(v: Int): String = padLeft(v.toString, 3, ' ')
  def dec4(v: Int): String = padLeft(v.toString, 4, ' ')
  def dec5(v: Int): String = padLeft(v.toString, 5, ' ')

  def asciiUpper(s: String): String = {
    val len = s.length
    var needsChange = false
    var i = 0
    while (i < len && !needsChange) {
      val c = s.charAt(i)
      if (c >= 'a' && c <= 'z') needsChange = true
      i += 1
    }
    if (!needsChange) return s
    val out = new Array[Char](len)
    i = 0
    while (i < len) {
      val c = s.charAt(i)
      out(i) = if (c >= 'a' && c <= 'z') (c - 32).toChar else c
      i += 1
    }
    new String(out)
  }

  def asciiLower(s: String): String = {
    val len = s.length
    var needsChange = false
    var i = 0
    while (i < len && !needsChange) {
      val c = s.charAt(i)
      if (c >= 'A' && c <= 'Z') needsChange = true
      i += 1
    }
    if (!needsChange) return s
    val out = new Array[Char](len)
    i = 0
    while (i < len) {
      val c = s.charAt(i)
      out(i) = if (c >= 'A' && c <= 'Z') (c + 32).toChar else c
      i += 1
    }
    new String(out)
  }
}
