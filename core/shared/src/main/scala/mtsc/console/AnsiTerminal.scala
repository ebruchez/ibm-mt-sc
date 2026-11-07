package mtsc.console

/**
 * ANSI colors supported by terminal rendering.
 */
enum AnsiColor(val code: Int) {
  case Black   extends AnsiColor(30)
  case Red     extends AnsiColor(31)
  case Green   extends AnsiColor(32)
  case Yellow  extends AnsiColor(33)
  case Blue    extends AnsiColor(34)
  case Magenta extends AnsiColor(35)
  case Cyan    extends AnsiColor(36)
  case White   extends AnsiColor(37)
  case Gray    extends AnsiColor(90)
  case BrightRed extends AnsiColor(91)
  case BrightGreen extends AnsiColor(92)
  case BrightYellow extends AnsiColor(93)
  case BrightMagenta extends AnsiColor(95)
  case BrightCyan extends AnsiColor(96)
  case BrightWhite extends AnsiColor(97)
}

/**
 * Single cell in the virtual screen buffer.
 */
final case class Cell(
  char: Char = ' ',
  fg: Option[AnsiColor] = None,
  bg: Option[AnsiColor] = None,
  bold: Boolean = false,
  inverse: Boolean = false
)

/**
 * Double-buffered ANSI virtual screen buffer.
 *
 * Implements 2D text drawing primitives (boxes, text, colors)
 * without external dependencies, compiling cleanly to JVM, Scala Native, and Scala.js.
 */
class AnsiTerminal(val width: Int = 104, val height: Int = 38) {

  private val chars: Array[Char] = new Array[Char](width * height)
  private val attrs: Array[Short] = new Array[Short](width * height)
  private val prevRowHashes: Array[Int] = new Array[Int](height)
  private var hasRenderedFrame: Boolean = false
  private val colorValues: Array[AnsiColor] = AnsiColor.values
  private val rowBuf: Array[Char] = new Array[Char](width * 12)
  private val decScratch: Array[Char] = new Array[Char](20)

  clear()

  @inline private def packAttr(
    fg: Option[AnsiColor],
    bg: Option[AnsiColor],
    bold: Boolean,
    inverse: Boolean
  ): Short = {
    val fgBits = if (fg.isDefined) (fg.get.ordinal + 1) & 0x0f else 0
    val bgBits = if (bg.isDefined) ((bg.get.ordinal + 1) & 0x0f) << 4 else 0
    val boldBit = if (bold) 0x100 else 0
    val invBit = if (inverse) 0x200 else 0
    (fgBits | bgBits | boldBit | invBit).toShort
  }

  @inline private def putRaw(x: Int, y: Int, ch: Char, attr: Short): Unit = {
    if (x >= 0 && x < width && y >= 0 && y < height) {
      val idx = y * width + x
      chars(idx) = ch
      attrs(idx) = attr
    }
  }

  def clear(): Unit = {
    var i = 0
    val len = chars.length
    while (i < len) {
      chars(i) = ' '
      attrs(i) = 0
      i += 1
    }
  }

  def setCell(x: Int, y: Int, cell: Cell): Unit = {
    putRaw(x, y, cell.char, packAttr(cell.fg, cell.bg, cell.bold, cell.inverse))
  }

  def writeChar(
    x: Int,
    y: Int,
    ch: Char,
    fg: Option[AnsiColor] = None,
    bg: Option[AnsiColor] = None,
    bold: Boolean = false,
    inverse: Boolean = false
  ): Int = {
    putRaw(x, y, ch, packAttr(fg, bg, bold, inverse))
    x + 1
  }

  def writeText(
    x: Int,
    y: Int,
    text: String,
    fg: Option[AnsiColor] = None,
    bg: Option[AnsiColor] = None,
    bold: Boolean = false,
    inverse: Boolean = false,
    maxLen: Int = Int.MaxValue
  ): Int = {
    if (y < 0 || y >= height) return x
    val attr = packAttr(fg, bg, bold, inverse)
    val rowBase = y * width
    val limit = math.min(text.length, maxLen)
    var col = x
    var i = 0
    while (i < limit && col < width) {
      if (col >= 0) {
        val idx = rowBase + col
        chars(idx) = text.charAt(i)
        attrs(idx) = attr
      }
      col += 1
      i += 1
    }
    col
  }

  def writeStringBuilder(
    x: Int,
    y: Int,
    sb: scala.collection.mutable.StringBuilder,
    fg: Option[AnsiColor] = None,
    bg: Option[AnsiColor] = None,
    bold: Boolean = false,
    inverse: Boolean = false,
    maxLen: Int = Int.MaxValue
  ): Int = {
    if (y < 0 || y >= height) return x
    val attr = packAttr(fg, bg, bold, inverse)
    val rowBase = y * width
    val limit = math.min(sb.length, maxLen)
    var col = x
    var i = 0
    while (i < limit && col < width) {
      if (col >= 0) {
        val idx = rowBase + col
        chars(idx) = sb.charAt(i)
        attrs(idx) = attr
      }
      col += 1
      i += 1
    }
    col
  }

  def writePadded(
    x: Int,
    y: Int,
    text: String,
    padWidth: Int,
    leftAlign: Boolean = true,
    fg: Option[AnsiColor] = None,
    bg: Option[AnsiColor] = None,
    bold: Boolean = false,
    inverse: Boolean = false
  ): Int = {
    if (y < 0 || y >= height) return x + padWidth
    val attr = packAttr(fg, bg, bold, inverse)
    val rowBase = y * width
    val len = text.length
    val pad = if (len < padWidth) padWidth - len else 0
    var col = x
    if (!leftAlign) {
      var p = 0
      while (p < pad && col < width) {
        if (col >= 0) {
          val idx = rowBase + col
          chars(idx) = ' '
          attrs(idx) = attr
        }
        col += 1
        p += 1
      }
    }
    var i = 0
    val maxChars = if (len > padWidth) padWidth else len
    while (i < maxChars && col < width) {
      if (col >= 0) {
        val idx = rowBase + col
        chars(idx) = text.charAt(i)
        attrs(idx) = attr
      }
      col += 1
      i += 1
    }
    if (leftAlign) {
      var p = 0
      while (p < pad && col < width) {
        if (col >= 0) {
          val idx = rowBase + col
          chars(idx) = ' '
          attrs(idx) = attr
        }
        col += 1
        p += 1
      }
    }
    col
  }

  @inline private def hexDigit(n: Int): Char = {
    val v = n & 0x0f
    (if (v < 10) '0' + v else 'A' + (v - 10)).toChar
  }

  def writeHex2(
    x: Int,
    y: Int,
    v: Int,
    fg: Option[AnsiColor] = None,
    bg: Option[AnsiColor] = None,
    bold: Boolean = false,
    inverse: Boolean = false
  ): Int = {
    val attr = packAttr(fg, bg, bold, inverse)
    putRaw(x, y, hexDigit(v >> 4), attr)
    putRaw(x + 1, y, hexDigit(v), attr)
    x + 2
  }

  def writeHex3(
    x: Int,
    y: Int,
    v: Int,
    fg: Option[AnsiColor] = None,
    bg: Option[AnsiColor] = None,
    bold: Boolean = false,
    inverse: Boolean = false
  ): Int = {
    val attr = packAttr(fg, bg, bold, inverse)
    putRaw(x, y, hexDigit(v >> 8), attr)
    putRaw(x + 1, y, hexDigit(v >> 4), attr)
    putRaw(x + 2, y, hexDigit(v), attr)
    x + 3
  }

  def writeHex4(
    x: Int,
    y: Int,
    v: Int,
    fg: Option[AnsiColor] = None,
    bg: Option[AnsiColor] = None,
    bold: Boolean = false,
    inverse: Boolean = false
  ): Int = {
    val attr = packAttr(fg, bg, bold, inverse)
    putRaw(x, y, hexDigit(v >> 12), attr)
    putRaw(x + 1, y, hexDigit(v >> 8), attr)
    putRaw(x + 2, y, hexDigit(v >> 4), attr)
    putRaw(x + 3, y, hexDigit(v), attr)
    x + 4
  }

  def writeDec(
    x: Int,
    y: Int,
    v: Int,
    minWidth: Int = 0,
    padChar: Char = ' ',
    fg: Option[AnsiColor] = None,
    bg: Option[AnsiColor] = None,
    bold: Boolean = false,
    inverse: Boolean = false
  ): Int = {
    val attr = packAttr(fg, bg, bold, inverse)
    var num = if (v < 0) -v else v
    var count = 0
    if (num == 0) {
      decScratch(0) = '0'
      count = 1
    } else {
      while (num > 0) {
        decScratch(count) = ('0' + (num % 10)).toChar
        num /= 10
        count += 1
      }
    }
    if (v < 0) {
      decScratch(count) = '-'
      count += 1
    }
    var col = x
    var pad = minWidth - count
    while (pad > 0) {
      putRaw(col, y, padChar, attr)
      col += 1
      pad -= 1
    }
    while (count > 0) {
      count -= 1
      putRaw(col, y, decScratch(count), attr)
      col += 1
    }
    col
  }

  def writeDecLong(
    x: Int,
    y: Int,
    v: Long,
    minWidth: Int = 0,
    padChar: Char = '0',
    fg: Option[AnsiColor] = None,
    bg: Option[AnsiColor] = None,
    bold: Boolean = false,
    inverse: Boolean = false
  ): Int = {
    val attr = packAttr(fg, bg, bold, inverse)
    var num = if (v < 0L) -v else v
    var count = 0
    if (num == 0L) {
      decScratch(0) = '0'
      count = 1
    } else {
      while (num > 0L) {
        decScratch(count) = ('0' + (num % 10L).toInt).toChar
        num /= 10L
        count += 1
      }
    }
    var col = x
    var pad = minWidth - count
    while (pad > 0) {
      putRaw(col, y, padChar, attr)
      col += 1
      pad -= 1
    }
    while (count > 0) {
      count -= 1
      putRaw(col, y, decScratch(count), attr)
      col += 1
    }
    col
  }

  def writeRegName(
    x: Int,
    y: Int,
    regIdx: Int,
    fg: Option[AnsiColor] = None,
    bg: Option[AnsiColor] = None,
    bold: Boolean = false,
    inverse: Boolean = false
  ): Int = {
    val attr = packAttr(fg, bg, bold, inverse)
    putRaw(x, y, 'R', attr)
    if (regIdx >= 10) {
      putRaw(x + 1, y, ('0' + (regIdx / 10)).toChar, attr)
      putRaw(x + 2, y, ('0' + (regIdx % 10)).toChar, attr)
      x + 3
    } else {
      putRaw(x + 1, y, ('0' + regIdx).toChar, attr)
      x + 2
    }
  }

  def drawBox(
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    title: String = "",
    fg: Option[AnsiColor] = Some(AnsiColor.Cyan),
    bg: Option[AnsiColor] = None
  ): Unit = {
    if (w < 2 || h < 2) return

    val attr = packAttr(fg, bg, bold = false, inverse = false)
    val tl = '┌'
    val tr = '┐'
    val bl = '└'
    val br = '┘'
    val horiz = '─'
    val vert = '│'

    // Top border
    putRaw(x, y, tl, attr)
    var i = x + 1
    while (i < x + w - 1) {
      putRaw(i, y, horiz, attr)
      i += 1
    }
    putRaw(x + w - 1, y, tr, attr)

    // Side borders
    var j = y + 1
    while (j < y + h - 1) {
      putRaw(x, j, vert, attr)
      putRaw(x + w - 1, j, vert, attr)
      j += 1
    }

    // Bottom border
    putRaw(x, y + h - 1, bl, attr)
    i = x + 1
    while (i < x + w - 1) {
      putRaw(i, y + h - 1, horiz, attr)
      i += 1
    }
    putRaw(x + w - 1, y + h - 1, br, attr)

    // Title if present
    if (title.nonEmpty && title.length < w - 4) {
      val boldAttr = packAttr(fg, bg, bold = true, inverse = false)
      putRaw(x + 2, y, ' ', boldAttr)
      writeText(x + 3, y, title, fg, bg, bold = true)
      putRaw(x + 3 + title.length, y, ' ', boldAttr)
    }
  }

  def drawHLine(x: Int, y: Int, w: Int, fg: Option[AnsiColor] = Some(AnsiColor.Cyan)): Unit = {
    val attr = packAttr(fg, None, bold = false, inverse = false)
    var i = x
    while (i < x + w && i < width) {
      putRaw(i, y, '─', attr)
      i += 1
    }
  }

  @inline private def appendIntToRowBuf(pos0: Int, v: Int): Int = {
    var pos = pos0
    if (v >= 100) {
      rowBuf(pos) = ('0' + (v / 100)).toChar
      rowBuf(pos + 1) = ('0' + ((v / 10) % 10)).toChar
      rowBuf(pos + 2) = ('0' + (v % 10)).toChar
      pos + 3
    } else if (v >= 10) {
      rowBuf(pos) = ('0' + (v / 10)).toChar
      rowBuf(pos + 1) = ('0' + (v % 10)).toChar
      pos + 2
    } else {
      rowBuf(pos) = ('0' + v).toChar
      pos + 1
    }
  }

  /**
   * Zero-allocation dirty-row formatter that writes each changed row directly into the reusable
   * `rowBuf: Array[Char]` and invokes `emitChars(rowBuf, length)`.
   */
  def renderDirtyRowsChars(forceFull: Boolean, emitChars: (Array[Char], Int) => Unit): Int = {
    val full = forceFull || !hasRenderedFrame
    var emittedRows = 0
    var y = 0
    while (y < height) {
      val rowBase = y * width
      var hash = 0x811c9dc5
      var i = 0
      while (i < width) {
        val idx = rowBase + i
        val combined = (chars(idx).toInt << 16) | (attrs(idx).toInt & 0xffff)
        hash = (hash ^ combined) * 0x01000193
        i += 1
      }

      if (full || hash != prevRowHashes(y)) {
        prevRowHashes(y) = hash
        emittedRows += 1
        var pos = 0
        // "\u001b[" + (y + 1) + ";1H\u001b[0m"
        rowBuf(pos) = '\u001b'; rowBuf(pos + 1) = '['; pos += 2
        pos = appendIntToRowBuf(pos, y + 1)
        rowBuf(pos) = ';'; rowBuf(pos + 1) = '1'; rowBuf(pos + 2) = 'H'
        rowBuf(pos + 3) = '\u001b'; rowBuf(pos + 4) = '['; rowBuf(pos + 5) = '0'; rowBuf(pos + 6) = 'm'
        pos += 7

        var currentAttr: Int = 0
        var x = 0
        while (x < width) {
          val idx = rowBase + x
          val attr = attrs(idx).toInt & 0x3ff
          if (attr != currentAttr) {
            rowBuf(pos) = '\u001b'; rowBuf(pos + 1) = '['; rowBuf(pos + 2) = '0'; pos += 3
            val fgIdx = attr & 0x0f
            val bgIdx = (attr >> 4) & 0x0f
            if (fgIdx > 0) {
              rowBuf(pos) = ';'; pos += 1
              pos = appendIntToRowBuf(pos, colorValues(fgIdx - 1).code)
            }
            if (bgIdx > 0) {
              rowBuf(pos) = ';'; pos += 1
              pos = appendIntToRowBuf(pos, colorValues(bgIdx - 1).code + 10)
            }
            if ((attr & 0x100) != 0) {
              rowBuf(pos) = ';'; rowBuf(pos + 1) = '1'; pos += 2
            }
            if ((attr & 0x200) != 0) {
              rowBuf(pos) = ';'; rowBuf(pos + 1) = '7'; pos += 2
            }
            rowBuf(pos) = 'm'; pos += 1
            currentAttr = attr
          }
          rowBuf(pos) = chars(idx)
          pos += 1
          x += 1
        }
        rowBuf(pos) = '\u001b'; rowBuf(pos + 1) = '['; rowBuf(pos + 2) = '0'; rowBuf(pos + 3) = 'm'
        pos += 4
        emitChars(rowBuf, pos)
      }
      y += 1
    }
    hasRenderedFrame = true
    emittedRows
  }

  private val rowSb = new java.lang.StringBuilder(width * 4)

  /**
   * Renders the buffer row-by-row, invoking `emit` for each line of ANSI output.
   * Avoids large contiguous multi-kilobyte StringBuilder/String allocations on bare-metal MCUs (RP2040).
   */
  def renderRows(emit: String => Unit): Unit = {
    var currentAttr: Int = 0

    var y = 0
    while (y < height) {
      rowSb.setLength(0)
      if (y == 0) {
        rowSb.append("\u001b[H")
      }

      val rowBase = y * width
      var x = 0
      while (x < width) {
        val idx = rowBase + x
        val attr = attrs(idx).toInt & 0x3ff

        if (attr != currentAttr) {
          rowSb.append("\u001b[0") // Reset styles
          val fgIdx = attr & 0x0f
          val bgIdx = (attr >> 4) & 0x0f
          if (fgIdx > 0) rowSb.append(';').append(colorValues(fgIdx - 1).code)
          if (bgIdx > 0) rowSb.append(';').append(colorValues(bgIdx - 1).code + 10)
          if ((attr & 0x100) != 0) rowSb.append(";1")
          if ((attr & 0x200) != 0) rowSb.append(";7")
          rowSb.append('m')

          currentAttr = attr
        }

        rowSb.append(chars(idx))
        x += 1
      }
      if (y < height - 1) {
        rowSb.append("\r\n")
      } else {
        rowSb.append("\u001b[0m")
      }
      emit(rowSb.toString)
      y += 1
    }
  }

  /**
   * Renders only the rows whose characters or attributes changed since the previous frame
   * (or all rows when `forceFull` is true), positioning the cursor explicitly with `ESC[row;1H`.
   * Returns the number of rows emitted.
   */
  def renderDirtyRows(forceFull: Boolean, emit: String => Unit): Int = {
    val full = forceFull || !hasRenderedFrame
    var emittedRows = 0
    var y = 0
    while (y < height) {
      val rowBase = y * width
      var hash = 0x811c9dc5
      var i = 0
      while (i < width) {
        val idx = rowBase + i
        val combined = (chars(idx).toInt << 16) | (attrs(idx).toInt & 0xffff)
        hash = (hash ^ combined) * 0x01000193
        i += 1
      }

      if (full || hash != prevRowHashes(y)) {
        prevRowHashes(y) = hash
        emittedRows += 1
        rowSb.setLength(0)
        rowSb.append("\u001b[").append(y + 1).append(";1H\u001b[0m")
        var currentAttr: Int = 0
        var x = 0
        while (x < width) {
          val idx = rowBase + x
          val attr = attrs(idx).toInt & 0x3ff
          if (attr != currentAttr) {
            rowSb.append("\u001b[0")
            val fgIdx = attr & 0x0f
            val bgIdx = (attr >> 4) & 0x0f
            if (fgIdx > 0) rowSb.append(';').append(colorValues(fgIdx - 1).code)
            if (bgIdx > 0) rowSb.append(';').append(colorValues(bgIdx - 1).code + 10)
            if ((attr & 0x100) != 0) rowSb.append(";1")
            if ((attr & 0x200) != 0) rowSb.append(";7")
            rowSb.append('m')
            currentAttr = attr
          }
          rowSb.append(chars(idx))
          x += 1
        }
        rowSb.append("\u001b[0m")
        emit(rowSb.toString)
      }
      y += 1
    }
    hasRenderedFrame = true
    emittedRows
  }

  /**
   * Renders the buffer into an ANSI escape string.
   */
  def renderToString(): String = {
    val sb = new java.lang.StringBuilder(width * height * 2)
    renderRows(row => sb.append(row))
    sb.toString
  }
}
