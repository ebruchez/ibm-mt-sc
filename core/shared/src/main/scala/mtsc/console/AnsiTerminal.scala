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

  private val buffer: Array[Cell] = Array.fill(width * height)(Cell())

  def clear(): Unit = {
    var i = 0
    val blank = Cell()
    while (i < buffer.length) {
      buffer(i) = blank
      i += 1
    }
  }

  def setCell(x: Int, y: Int, cell: Cell): Unit = {
    if (x >= 0 && x < width && y >= 0 && y < height) {
      buffer(y * width + x) = cell
    }
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
    var col = x
    var i = 0
    while (i < text.length && col < width) {
      if (col >= 0) {
        buffer(y * width + col) = Cell(text.charAt(i), fg, bg, bold, inverse)
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

    // Corners and borders
    val tl = '┌'
    val tr = '┐'
    val bl = '└'
    val br = '┘'
    val horiz = '─'
    val vert = '│'

    // Top border
    setCell(x, y, Cell(tl, fg, bg))
    var i = x + 1
    while (i < x + w - 1) {
      setCell(i, y, Cell(horiz, fg, bg))
      i += 1
    }
    setCell(x + w - 1, y, Cell(tr, fg, bg))

    // Side borders
    var j = y + 1
    while (j < y + h - 1) {
      setCell(x, j, Cell(vert, fg, bg))
      setCell(x + w - 1, j, Cell(vert, fg, bg))
      j += 1
    }

    // Bottom border
    setCell(x, y + h - 1, Cell(bl, fg, bg))
    i = x + 1
    while (i < x + w - 1) {
      setCell(i, y + h - 1, Cell(horiz, fg, bg))
      i += 1
    }
    setCell(x + w - 1, y + h - 1, Cell(br, fg, bg))

    // Title if present
    if (title.nonEmpty && title.length < w - 4) {
      writeText(x + 2, y, s" $title ", fg, bg, bold = true)
    }
  }

  def drawHLine(x: Int, y: Int, w: Int, fg: Option[AnsiColor] = Some(AnsiColor.Cyan)): Unit = {
    var i = x
    while (i < x + w && i < width) {
      setCell(i, y, Cell('─', fg))
      i += 1
    }
  }

  /**
   * Renders the buffer into an ANSI escape string.
   */
  def renderToString(): String = {
    val sb = new java.lang.StringBuilder(width * height * 8)
    // Move to top-left home position
    sb.append("\u001b[H")

    var currentFg: Option[AnsiColor] = None
    var currentBg: Option[AnsiColor] = None
    var currentBold = false
    var currentInverse = false

    var y = 0
    while (y < height) {
      var x = 0
      while (x < width) {
        val cell = buffer(y * width + x)

        // Attribute updates
        val fgChanged = cell.fg != currentFg
        val bgChanged = cell.bg != currentBg
        val boldChanged = cell.bold != currentBold
        val invChanged = cell.inverse != currentInverse

        if (fgChanged || bgChanged || boldChanged || invChanged) {
          sb.append("\u001b[0") // Reset styles
          cell.fg.foreach(c => sb.append(';').append(c.code))
          cell.bg.foreach(c => sb.append(';').append(c.code + 10))
          if (cell.bold) sb.append(";1")
          if (cell.inverse) sb.append(";7")
          sb.append('m')

          currentFg = cell.fg
          currentBg = cell.bg
          currentBold = cell.bold
          currentInverse = cell.inverse
        }

        sb.append(cell.char)
        x += 1
      }
      if (y < height - 1) sb.append("\r\n")
      y += 1
    }

    // Final reset
    sb.append("\u001b[0m")
    sb.toString
  }
}
