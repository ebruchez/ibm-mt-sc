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

  def writeText(
    x: Int,
    y: Int,
    text: String,
    fg: Option[AnsiColor] = None,
    bg: Option[AnsiColor] = None,
    bold: Boolean = false,
    inverse: Boolean = false
  ): Unit = {
    if (y < 0 || y >= height) return
    val attr = packAttr(fg, bg, bold, inverse)
    val rowBase = y * width
    var col = x
    var i = 0
    while (i < text.length && col < width) {
      if (col >= 0) {
        val idx = rowBase + col
        chars(idx) = text.charAt(i)
        attrs(idx) = attr
      }
      col += 1
      i += 1
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
      writeText(x + 2, y, s" $title ", fg, bg, bold = true)
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
