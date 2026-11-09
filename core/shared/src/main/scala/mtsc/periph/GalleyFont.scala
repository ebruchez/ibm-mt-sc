package mtsc.periph

import mtsc.diag.EscapementRoutine

/**
 * Proportional Press Roman-inspired serif bitmap font and 296x128 SSD1680 eInk galley renderer
 * for the **Adafruit 2.9" Grayscale eInk FeatherWing (PID 4777)**.
 *
 * ### Proportional Escapement Geometry (`2 px = 1 escapement unit`)
 * Every character's bitmap cell width is exactly `2 * EscapementRoutine.defaultUnitWidth(ch)`:
 * - **3 units (`6 px`)**: `'i'`, `'l'`, `'j'`, `'I'`, `'.'`, `','`, `'\''`, `':'`, `';'`, `'!'`, `'|'`, `` '`' ``
 * - **4 units (`8 px`)**: `'f'`, `'r'`, `'t'`, `'-'`, `'('`, `')'`, `'['`, `']'`, `'"'`, standard `' '`
 * - **5 units (`10 px`)**: `'a'`, `'c'`, `'e'`, `'s'`, `'v'`, `'x'`, `'z'`, `'0'`..`'9'`
 * - **6 units (`12 px`)**: `'b'`, `'d'`, `'g'`, `'h'`, `'k'`, `'n'`, `'o'`, `'p'`, `'q'`, `'u'`, `'y'`, `'J'`, `'L'`, `'S'`
 * - **7 units (`14 px`)**: `'A'`..`'H'`, `'K'`, `'N'`..`'P'`, `'R'`, `'T'`..`'Z'`
 * - **8 units (`16 px`)**: `'w'`, `'Q'`, `'&'`, `'%'`, `'$'`
 * - **9 units (`18 px`)**: `'m'`, `'M'`, `'W'`, `'@'`, `'#'`, `'_'`
 *
 * ### 4-Level Grayscale (`2bpp`) Shade Encoding (`ThinkInk_290_Grayscale4_EAAMFGN`)
 * - `ShadeWhite     (0 = 0b00)`: `bwPlane = 0`, `redPlane = 0` (Paper background)
 * - `ShadeLightGray (1 = 0b01)`: `bwPlane = 1`, `redPlane = 0` (Serif/bowl anti-aliasing & ruler fill)
 * - `ShadeDarkGray  (2 = 0b10)`: `bwPlane = 0`, `redPlane = 1` (Non-repro line numbers, pica ticks, margin guide)
 * - `ShadeBlack     (3 = 0b11)`: `bwPlane = 1`, `redPlane = 1` (Primary Press Roman ink stems & serifs)
 */
object GalleyFont {

  final val WidthPx: Int = 296
  final val HeightPx: Int = 128
  final val PlaneBytes: Int = (WidthPx * HeightPx) / 8 // 4,736 bytes per bitplane

  final val PixelsPerUnit: Int = 2
  final val GlyphHeightPx: Int = 12
  final val LinePitchPx: Int = 14
  final val HeaderHeightPx: Int = 14
  final val MaxVisibleLines: Int = 8 // 14 + 8 * 14 = 126 <= 128

  final val ShadeWhite: Int = 0
  final val ShadeLightGray: Int = 1
  final val ShadeDarkGray: Int = 2
  final val ShadeBlack: Int = 3

  private final val FirstAscii: Int = 32
  private final val LastAscii: Int = 126
  private final val NumChars: Int = LastAscii - FirstAscii + 1

  // Pre-compiled bitmasks per (charIdx * GlyphHeightPx + row): bit `gx` holds the plane bit at x = gx
  private val glyphBwMasks: Array[Int] = new Array[Int](NumChars * GlyphHeightPx)
  private val glyphRedMasks: Array[Int] = new Array[Int](NumChars * GlyphHeightPx)
  private val glyphWidths: Array[Int] = new Array[Int](NumChars)

  // Open-counter 4x7 digits ('0'..'9', 28 bits each: 7 rows of 4 bits) for left-gutter line numbers
  private val tinyDigits4x7: Array[Int] = Array(
    0x6999996, // 0: 0110 / 1001 / 1001 / 1001 / 1001 / 1001 / 0110
    0x2622227, // 1: 0010 / 0110 / 0010 / 0010 / 0010 / 0010 / 0111
    0x691248F, // 2: 0110 / 1001 / 0001 / 0010 / 0100 / 1000 / 1111
    0xE11611E, // 3: 1110 / 0001 / 0001 / 0110 / 0001 / 0001 / 1110
    0x999F111, // 4: 1001 / 1001 / 1001 / 1111 / 0001 / 0001 / 0001
    0xF88E11E, // 5: 1111 / 1000 / 1000 / 1110 / 0001 / 0001 / 1110
    0x688E996, // 6: 0110 / 1000 / 1000 / 1110 / 1001 / 1001 / 0110
    0xF122444, // 7: 1111 / 0001 / 0010 / 0010 / 0100 / 0100 / 0100
    0x6996996, // 8: 0110 / 1001 / 1001 / 0110 / 1001 / 1001 / 0110
    0x6997116  // 9: 0110 / 1001 / 1001 / 0111 / 0001 / 0001 / 0110
  )

  // Open-counter 5x7 digits ('0'..'9', 7 bytes per digit, 5-bit rows) for high-legibility pica ruler labels
  private val rulerDigits5x7: Array[Byte] = Array(
    0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E, // 0
    0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E, // 1
    0x0E, 0x11, 0x01, 0x06, 0x08, 0x10, 0x1F, // 2
    0x1E, 0x01, 0x01, 0x0E, 0x01, 0x01, 0x1E, // 3
    0x12, 0x12, 0x12, 0x1F, 0x02, 0x02, 0x02, // 4
    0x1F, 0x10, 0x10, 0x1E, 0x01, 0x01, 0x1E, // 5
    0x0E, 0x10, 0x10, 0x1E, 0x11, 0x11, 0x0E, // 6
    0x1F, 0x01, 0x02, 0x04, 0x04, 0x04, 0x04, // 7
    0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E, // 8
    0x0E, 0x11, 0x11, 0x0F, 0x01, 0x01, 0x0E  // 9
  )

  /**
   * Returns the proportional pixel width for a character (`2 * EscapementRoutine.defaultUnitWidth(ch)`).
   */
  def glyphWidthPx(ch: Char): Int = {
    val code = ch.toInt
    if (code >= FirstAscii && code <= LastAscii) {
      glyphWidths(code - FirstAscii)
    } else {
      EscapementRoutine.defaultUnitWidth(ch) * PixelsPerUnit
    }
  }

  /**
   * Computes the left margin in pixels so a given `targetMeasure` (in escapement units)
   * fits comfortably within the 296-pixel landscape width alongside the 2-digit line number gutter.
   */
  def leftMarginForMeasure(targetMeasure: Int): Int = {
    val lineWidthPx = targetMeasure * PixelsPerUnit
    if (12 + lineWidthPx <= WidthPx - 3) 12
    else math.max(2, WidthPx - 3 - lineWidthPx)
  }

  /**
   * Sets a single 2-bit pixel `(x, y)` in the SSD1680 `bwPlane` (`0x24`) and `redPlane` (`0x26`).
   */
  @inline def setPixelInPlanes(
    bwPlane: Array[Byte],
    redPlane: Array[Byte],
    x: Int,
    y: Int,
    shade: Int,
    flip180: Boolean = false
  ): Unit = {
    if (x < 0 || x >= WidthPx || y < 0 || y >= HeightPx) return
    val lx = if (flip180) (WidthPx - 1 - x) else x
    val ly = if (flip180) (HeightPx - 1 - y) else y

    val addr = (WidthPx - 1 - lx) * 16 + (ly >> 3)
    val bitMask = 1 << (7 - (ly & 7))
    val invMask = ~bitMask

    val curBw = bwPlane(addr).toInt
    val curRed = redPlane(addr).toInt

    val nextBw = if ((shade & 1) != 0) (curBw | bitMask) else (curBw & invMask)
    val nextRed = if ((shade & 2) != 0) (curRed | bitMask) else (curRed & invMask)

    bwPlane(addr) = nextBw.toByte
    redPlane(addr) = nextRed.toByte
  }

  /**
   * Reads back the 2-bit shade (`0..3`) at `(x, y)` from the SSD1680 planes.
   */
  def getPixelShade(
    bwPlane: Array[Byte],
    redPlane: Array[Byte],
    x: Int,
    y: Int,
    flip180: Boolean = false
  ): Int = {
    if (x < 0 || x >= WidthPx || y < 0 || y >= HeightPx) return ShadeWhite
    val lx = if (flip180) (WidthPx - 1 - x) else x
    val ly = if (flip180) (HeightPx - 1 - y) else y

    val addr = (WidthPx - 1 - lx) * 16 + (ly >> 3)
    val shift = 7 - (ly & 7)
    val bwBit = (bwPlane(addr).toInt >> shift) & 1
    val redBit = (redPlane(addr).toInt >> shift) & 1
    bwBit | (redBit << 1)
  }

  /**
   * Clears both SSD1680 bitplanes to `ShadeWhite` (`0x00`).
   */
  def clearPlanes(bwPlane: Array[Byte], redPlane: Array[Byte]): Unit = {
    var i = 0
    while (i < PlaneBytes) {
      bwPlane(i) = 0
      redPlane(i) = 0
      i += 1
    }
  }

  /**
   * Draws a single character glyph at `(x0, y0)` into `bwPlane` and `redPlane`.
   */
  def drawGlyph(
    bwPlane: Array[Byte],
    redPlane: Array[Byte],
    ch: Char,
    x0: Int,
    y0: Int,
    flip180: Boolean = false
  ): Unit = {
    val code = ch.toInt
    if (code <= 32 || code > LastAscii) return
    val idx = code - FirstAscii
    val w = glyphWidths(idx)
    val base = idx * GlyphHeightPx

    var row = 0
    while (row < GlyphHeightPx) {
      val py = y0 + row
      if (py >= 0 && py < HeightPx) {
        val bwMask = glyphBwMasks(base + row)
        val redMask = glyphRedMasks(base + row)
        if ((bwMask | redMask) != 0) {
          var gx = 0
          while (gx < w) {
            val bwBit = (bwMask >> gx) & 1
            val redBit = (redMask >> gx) & 1
            val shade = bwBit | (redBit << 1)
            if (shade != ShadeWhite) {
              setPixelInPlanes(bwPlane, redPlane, x0 + gx, py, shade, flip180)
            }
            gx += 1
          }
        }
      }
      row += 1
    }
  }

  private def drawTinyDigit(
    bwPlane: Array[Byte],
    redPlane: Array[Byte],
    digit: Int,
    x0: Int,
    y0: Int,
    shade: Int,
    flip180: Boolean
  ): Unit = {
    if (digit < 0 || digit > 9) return
    val bitmap = tinyDigits4x7(digit)
    var r = 0
    while (r < 7) {
      val rowBits = (bitmap >> ((6 - r) * 4)) & 0xF
      var c = 0
      while (c < 4) {
        if (((rowBits >> (3 - c)) & 1) != 0) {
          setPixelInPlanes(bwPlane, redPlane, x0 + c, y0 + r, shade, flip180)
        }
        c += 1
      }
      r += 1
    }
  }

  private def drawRulerDigit(
    bwPlane: Array[Byte],
    redPlane: Array[Byte],
    digit: Int,
    x0: Int,
    y0: Int,
    shade: Int,
    flip180: Boolean
  ): Unit = {
    if (digit < 0 || digit > 9) return
    val base = digit * 7
    var r = 0
    while (r < 7) {
      val rowBits = rulerDigits5x7(base + r).toInt & 0x1F
      var c = 0
      while (c < 5) {
        if (((rowBits >> (4 - c)) & 1) != 0) {
          setPixelInPlanes(bwPlane, redPlane, x0 + c, y0 + r, shade, flip180)
        }
        c += 1
      }
      r += 1
    }
  }

  private def drawRulerP(
    bwPlane: Array[Byte],
    redPlane: Array[Byte],
    x0: Int,
    y0: Int,
    shade: Int,
    flip180: Boolean
  ): Unit = {
    // 5x7 open-counter lowercase 'p':
    // ..... (0x00) / ####. (0x1E) / #...# (0x11) / #...# (0x11) / ####. (0x1E) / #.... (0x10) / #.... (0x10)
    var r = 1
    while (r < 7) {
      val rowBits =
        if (r == 1 || r == 4) 0x1E
        else if (r == 2 || r == 3) 0x11
        else 0x10
      var c = 0
      while (c < 5) {
        if (((rowBits >> (4 - c)) & 1) != 0) {
          setPixelInPlanes(bwPlane, redPlane, x0 + c, y0 + r, shade, flip180)
        }
        c += 1
      }
      r += 1
    }
  }

  private def drawRulerNumber(
    bwPlane: Array[Byte],
    redPlane: Array[Byte],
    value: Int,
    x0: Int,
    y0: Int,
    shade: Int,
    flip180: Boolean
  ): Int = {
    var x = x0
    if (value >= 100) {
      drawRulerDigit(bwPlane, redPlane, (value / 100) % 10, x, y0, shade, flip180)
      x += 6
    }
    if (value >= 10) {
      drawRulerDigit(bwPlane, redPlane, (value / 10) % 10, x, y0, shade, flip180)
      x += 6
    }
    drawRulerDigit(bwPlane, redPlane, value % 10, x, y0, shade, flip180)
    x + 5
  }

  /**
   * Computes the top visible line index (`0`-based) for the 8-line e-Ink galley window:
   * - When `scrollOffsetLines == 0` (live composition), paginates in 8-line sheets (`0`, `8`, `16`, ...)
   *   so characters type progressively onto stationary white paper without shifting every line.
   * - When `scrollOffsetLines > 0` (manual scrolling via Button B), offsets back from the last line.
   */
  def visibleStartLine(totalLines: Int, scrollOffsetLines: Int): Int = {
    if (totalLines <= MaxVisibleLines) 0
    else if (scrollOffsetLines <= 0) {
      ((totalLines - 1) / MaxVisibleLines) * MaxVisibleLines
    } else {
      val maxStart = math.max(0, totalLines - MaxVisibleLines)
      math.max(0, math.min(maxStart, maxStart - scrollOffsetLines))
    }
  }

  /**
   * Renders the complete camera-ready Selectric Composer galley sheet into `bwPlane` and `redPlane`:
   * 1. Top Pica Ruler (`0p`, `3p`, `6p`, `9p`, `12p`) with target measure marker (`▼140`)
   * 2. Vertical dotted right-margin measure guideline at `x = leftMargin + targetMeasure * 2`
   * 3. Up to 8 visible lines of proportional Press Roman serif text with 2-digit line numbers
   * 4. Active Selectric typing element cursor (`●`) on the current line when composing
   *
   * Zero heap allocations during rendering so it runs safely inside the RP2040 real-time loop.
   */
  def renderGalleyToPlanes(
    paper: ComposerPaper,
    targetMeasure: Int,
    bwPlane: Array[Byte],
    redPlane: Array[Byte],
    scrollOffsetLines: Int = 0,
    showCursor: Boolean = false,
    flip180: Boolean = false
  ): Unit = {
    clearPlanes(bwPlane, redPlane)

    val clampedMeasure = math.max(30, math.min(146, targetMeasure))
    val leftMargin = leftMarginForMeasure(clampedMeasure)
    val measureX = math.min(WidthPx - 2, leftMargin + clampedMeasure * PixelsPerUnit)

    // 1. Top Pica Ruler Bar (y = 0..12): clean White background with solid Black baseline rule at y = 12
    var x = 0
    while (x < WidthPx) {
      setPixelInPlanes(bwPlane, redPlane, x, 12, ShadeBlack, flip180)
      x += 1
    }

    // Measure numeric label next to triangle (`140` = 17 px wide, placed to the left of `▼`)
    val badgeX = math.max(2, measureX - 21)

    // Pica ticks every 1 pica (12 units = 24 px), labeled every 3 picas (0p, 3p, 6p, 9p, 12p) in 5x7 open-counter font
    var pica = 0
    while (pica <= 12) {
      val tickX = leftMargin + pica * 12 * PixelsPerUnit
      if (tickX < WidthPx - 1) {
        val isMajor = (pica % 3) == 0
        val tickTop = if (isMajor) 6 else 9
        val tickShade = if (isMajor) ShadeBlack else ShadeDarkGray
        var ty = tickTop
        while (ty <= 12) {
          setPixelInPlanes(bwPlane, redPlane, tickX, ty, tickShade, flip180)
          ty += 1
        }
        if (isMajor && (tickX + 16 < badgeX - 2 || pica == 0)) {
          val endX = drawRulerNumber(bwPlane, redPlane, pica, tickX + 3, 2, ShadeBlack, flip180)
          drawRulerP(bwPlane, redPlane, endX + 1, 2, ShadeBlack, flip180)
        }
      }
      pica += 1
    }

    // Target Measure downward pointer triangle (▼) at `measureX`
    setPixelInPlanes(bwPlane, redPlane, measureX, 11, ShadeBlack, flip180)
    setPixelInPlanes(bwPlane, redPlane, measureX - 1, 10, ShadeBlack, flip180)
    setPixelInPlanes(bwPlane, redPlane, measureX, 10, ShadeBlack, flip180)
    setPixelInPlanes(bwPlane, redPlane, measureX + 1, 10, ShadeBlack, flip180)
    setPixelInPlanes(bwPlane, redPlane, measureX - 2, 9, ShadeBlack, flip180)
    setPixelInPlanes(bwPlane, redPlane, measureX - 1, 9, ShadeBlack, flip180)
    setPixelInPlanes(bwPlane, redPlane, measureX, 9, ShadeBlack, flip180)
    setPixelInPlanes(bwPlane, redPlane, measureX + 1, 9, ShadeBlack, flip180)
    setPixelInPlanes(bwPlane, redPlane, measureX + 2, 9, ShadeBlack, flip180)

    val _ = drawRulerNumber(bwPlane, redPlane, clampedMeasure, badgeX, 2, ShadeBlack, flip180)

    // 2. Vertical dotted right-margin measure guideline (y = 14..127)
    var gy = HeaderHeightPx
    while (gy < HeightPx) {
      if ((gy & 3) == 0) {
        setPixelInPlanes(bwPlane, redPlane, measureX, gy, ShadeDarkGray, flip180)
      } else if ((gy & 3) == 2) {
        setPixelInPlanes(bwPlane, redPlane, measureX, gy, ShadeLightGray, flip180)
      }
      gy += 1
    }

    // 3. Determine visible line window
    val completedCount = paper.completedGalleyLinesCount
    val hasCurrent = paper.currentGlyphsCount > 0 || paper.currentLineBuilder.nonEmpty
    val totalLines = completedCount + (if (hasCurrent) 1 else 0)

    if (totalLines == 0) {
      // Blank galley sheet: render a subtle centered prompt in Press Roman
      drawCenteredPrompt(bwPlane, redPlane, "IBM MT/SC GALLEY READY", 48, flip180)
      drawCenteredPrompt(bwPlane, redPlane, "Press [A] or [r] to Compose", 68, flip180)
      return
    }

    val startLine = visibleStartLine(totalLines, scrollOffsetLines)
    val endLine = math.min(totalLines, startLine + MaxVisibleLines)

    var visIdx = 0
    var lineIdx = startLine
    while (lineIdx < endLine) {
      val lineTopY = HeaderHeightPx + visIdx * LinePitchPx
      val lineNum = lineIdx + 1

      // Draw 2-digit non-repro line number in left gutter (4x7 open-counter digits at x=1 and x=6)
      if (leftMargin >= 11) {
        drawTinyDigit(bwPlane, redPlane, (lineNum / 10) % 10, 1, lineTopY + 3, ShadeDarkGray, flip180)
        drawTinyDigit(bwPlane, redPlane, lineNum % 10, 6, lineTopY + 3, ShadeDarkGray, flip180)
      }

      if (lineIdx < completedCount) {
        val lineText = paper.completedLineAt(lineIdx)
        val gCount = paper.completedLineGlyphCount(lineIdx)
        var gi = 0
        while (gi < gCount) {
          val ch = lineText.charAt(gi)
          if (ch != ' ') {
            val xUnits = paper.completedLineGlyphXUnits(lineIdx, gi)
            val gx = leftMargin + xUnits * PixelsPerUnit
            drawGlyph(bwPlane, redPlane, ch, gx, lineTopY, flip180)
          }
          gi += 1
        }
      } else {
        // Active line currently being typed
        val gCount = paper.currentGlyphsCount
        var gi = 0
        while (gi < gCount) {
          val ch = paper.currentGlyphCharAt(gi)
          if (ch != ' ') {
            val xUnits = paper.currentGlyphXUnitsAt(gi)
            val gx = leftMargin + xUnits * PixelsPerUnit
            drawGlyph(bwPlane, redPlane, ch, gx, lineTopY, flip180)
          }
          gi += 1
        }
        if (showCursor) {
          val curX = leftMargin + paper.currentXUnits * PixelsPerUnit
          var cy = lineTopY + 2
          while (cy <= lineTopY + 8) {
            setPixelInPlanes(bwPlane, redPlane, curX, cy, ShadeBlack, flip180)
            setPixelInPlanes(bwPlane, redPlane, curX + 1, cy, ShadeBlack, flip180)
            cy += 1
          }
        }
      }

      visIdx += 1
      lineIdx += 1
    }
  }

  private def drawCenteredPrompt(
    bwPlane: Array[Byte],
    redPlane: Array[Byte],
    text: String,
    y0: Int,
    flip180: Boolean
  ): Unit = {
    var totalW = 0
    var i = 0
    while (i < text.length) {
      totalW += glyphWidthPx(text.charAt(i))
      i += 1
    }
    var x = math.max(4, (WidthPx - totalW) / 2)
    i = 0
    while (i < text.length) {
      val ch = text.charAt(i)
      val w = glyphWidthPx(ch)
      if (ch != ' ') {
        drawGlyph(bwPlane, redPlane, ch, x, y0, flip180)
      }
      x += w
      i += 1
    }
  }

  // ==========================================================================
  // Font Table Compiler (runs once at object initialization)
  // ==========================================================================

  private def registerGlyph(ch: Char, rows: Array[String]): Unit = {
    val code = ch.toInt
    if (code < FirstAscii || code > LastAscii) return
    val idx = code - FirstAscii
    val expectedW = EscapementRoutine.defaultUnitWidth(ch) * PixelsPerUnit
    glyphWidths(idx) = expectedW
    val base = idx * GlyphHeightPx

    var r = 0
    while (r < GlyphHeightPx) {
      val rowStr = if (r < rows.length) rows(r) else ""
      var bwMask = 0
      var redMask = 0
      val limit = math.min(expectedW, rowStr.length)
      var gx = 0
      while (gx < limit) {
        rowStr.charAt(gx) match {
          case '#' => // Black (3): bw=1, red=1
            bwMask |= (1 << gx)
            redMask |= (1 << gx)
          case '+' => // DarkGray (2): bw=0, red=1
            redMask |= (1 << gx)
          case '.' => // LightGray (1): bw=1, red=0 (anti-aliasing pixel in 4-Gray mode)
            bwMask |= (1 << gx)
          case _ => ()
        }
        gx += 1
      }
      glyphBwMasks(base + r) = bwMask
      glyphRedMasks(base + r) = redMask
      r += 1
    }
  }

  // Initialize default widths for all ASCII 32..126
  private def initFontTable(): Unit = {
    var code = FirstAscii
    while (code <= LastAscii) {
      val ch = code.toChar
      glyphWidths(code - FirstAscii) = EscapementRoutine.defaultUnitWidth(ch) * PixelsPerUnit
      code += 1
    }

    // ------------------------------------------------------------------------
    // 3-UNIT CHARACTERS (6 pixels wide: 5 px glyph + 1 px bearing)
    // ------------------------------------------------------------------------
    registerGlyph('i', Array(
      "      ",
      "  ##  ",
      "  ..  ",
      " ###  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      " #### ",
      "      ",
      "      ",
      "      "
    ))
    registerGlyph('l', Array(
      "      ",
      " ###  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      " #### ",
      "      ",
      "      ",
      "      "
    ))
    registerGlyph('j', Array(
      "      ",
      "   ## ",
      "   .. ",
      "  ### ",
      "   ## ",
      "   ## ",
      "   ## ",
      "   ## ",
      "   ## ",
      "#  ## ",
      " ###  ",
      "      "
    ))
    registerGlyph('I', Array(
      "      ",
      "##### ",
      " .##. ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      " .##. ",
      "##### ",
      "      ",
      "      ",
      "      "
    ))
    registerGlyph('.', Array(
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "  ##  ",
      "  ##  ",
      "      ",
      "      ",
      "      "
    ))
    registerGlyph(',', Array(
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "  ##  ",
      "  ##  ",
      "  #.  ",
      " #    ",
      "      "
    ))
    registerGlyph('\'', Array(
      "      ",
      "  ##  ",
      "  ##  ",
      "  #.  ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      "
    ))
    registerGlyph('`', Array(
      "      ",
      " ##   ",
      "  ##  ",
      "   .  ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      ",
      "      "
    ))
    registerGlyph(':', Array(
      "      ",
      "      ",
      "      ",
      "  ##  ",
      "  ##  ",
      "      ",
      "      ",
      "  ##  ",
      "  ##  ",
      "      ",
      "      ",
      "      "
    ))
    registerGlyph(';', Array(
      "      ",
      "      ",
      "      ",
      "  ##  ",
      "  ##  ",
      "      ",
      "      ",
      "  ##  ",
      "  ##  ",
      "  #.  ",
      " #    ",
      "      "
    ))
    registerGlyph('!', Array(
      "      ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "      ",
      "  ##  ",
      "  ##  ",
      "      ",
      "      ",
      "      "
    ))
    registerGlyph('|', Array(
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "  ##  ",
      "      ",
      "      "
    ))

    // ------------------------------------------------------------------------
    // 4-UNIT CHARACTERS (8 pixels wide: 7 px glyph + 1 px bearing)
    // ------------------------------------------------------------------------
    registerGlyph('f', Array(
      "        ",
      "   ###. ",
      "  ##  # ",
      "  ##    ",
      " #####  ",
      "  ##    ",
      "  ##    ",
      "  ##    ",
      " #####  ",
      "        ",
      "        ",
      "        "
    ))
    registerGlyph('r', Array(
      "        ",
      "        ",
      "        ",
      " ##.##  ",
      "  ###.# ",
      "  ##    ",
      "  ##    ",
      "  ##    ",
      " #####  ",
      "        ",
      "        ",
      "        "
    ))
    registerGlyph('t', Array(
      "        ",
      "  .#    ",
      "  ##    ",
      " ###### ",
      "  ##    ",
      "  ##    ",
      "  ##    ",
      "  ## .# ",
      "   ###  ",
      "        ",
      "        ",
      "        "
    ))
    registerGlyph('-', Array(
      "        ",
      "        ",
      "        ",
      "        ",
      "        ",
      " #####  ",
      "        ",
      "        ",
      "        ",
      "        ",
      "        ",
      "        "
    ))
    registerGlyph('(', Array(
      "        ",
      "    ##  ",
      "   ##   ",
      "  ##    ",
      "  ##    ",
      "  ##    ",
      "  ##    ",
      "  ##    ",
      "   ##   ",
      "    ##  ",
      "        ",
      "        "
    ))
    registerGlyph(')', Array(
      "        ",
      "  ##    ",
      "   ##   ",
      "    ##  ",
      "    ##  ",
      "    ##  ",
      "    ##  ",
      "    ##  ",
      "   ##   ",
      "  ##    ",
      "        ",
      "        "
    ))
    registerGlyph('[', Array(
      "        ",
      "  ####  ",
      "  ##    ",
      "  ##    ",
      "  ##    ",
      "  ##    ",
      "  ##    ",
      "  ##    ",
      "  ##    ",
      "  ####  ",
      "        ",
      "        "
    ))
    registerGlyph(']', Array(
      "        ",
      "  ####  ",
      "    ##  ",
      "    ##  ",
      "    ##  ",
      "    ##  ",
      "    ##  ",
      "    ##  ",
      "    ##  ",
      "  ####  ",
      "        ",
      "        "
    ))
    registerGlyph('"', Array(
      "        ",
      " ## ##  ",
      " ## ##  ",
      " #. #.  ",
      "        ",
      "        ",
      "        ",
      "        ",
      "        ",
      "        ",
      "        ",
      "        "
    ))

    // ------------------------------------------------------------------------
    // 5-UNIT CHARACTERS (10 pixels wide: lowercase & numerals)
    // ------------------------------------------------------------------------
    registerGlyph('a', Array(
      "          ",
      "          ",
      "          ",
      "  .####.  ",
      " .#   ##  ",
      "  .#####  ",
      " ##   ##  ",
      " ##  ###  ",
      "  ###.##. ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('c', Array(
      "          ",
      "          ",
      "          ",
      "  .####.  ",
      " .##  .#  ",
      " ##       ",
      " ##       ",
      " .##   #  ",
      "  .####.  ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('e', Array(
      "          ",
      "          ",
      "          ",
      "  .####.  ",
      " .##  ##. ",
      " ######## ",
      " ##       ",
      " .##   #  ",
      "  .####.  ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('s', Array(
      "          ",
      "          ",
      "          ",
      "  .####.# ",
      " ##   .#  ",
      "  ####.   ",
      "    .###  ",
      " #.   ##  ",
      " #.####.  ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('v', Array(
      "          ",
      "          ",
      "          ",
      "###   ### ",
      " ##   ##  ",
      " .## ##.  ",
      "  ## ##   ",
      "  .###.   ",
      "   ###    ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('x', Array(
      "          ",
      "          ",
      "          ",
      "###. .### ",
      " .## ##.  ",
      "  .###.   ",
      "  .###.   ",
      " .## ##.  ",
      "###. .### ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('z', Array(
      "          ",
      "          ",
      "          ",
      " ######## ",
      " #   .##  ",
      "    .##   ",
      "   .##    ",
      "  .##   # ",
      " ######## ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('0', Array(
      "          ",
      "  .####.  ",
      " .##  ##. ",
      " ##    ## ",
      " ##    ## ",
      " ##    ## ",
      " ##    ## ",
      " .##  ##. ",
      "  .####.  ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('1', Array(
      "          ",
      "    ##    ",
      "  ####    ",
      " .  ##    ",
      "    ##    ",
      "    ##    ",
      "    ##    ",
      "    ##    ",
      "  ######  ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('2', Array(
      "          ",
      "  .####.  ",
      " ##   .## ",
      " #     ## ",
      "     .##. ",
      "    .##   ",
      "  .##     ",
      " .##   .# ",
      " ######## ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('3', Array(
      "          ",
      "  .####.  ",
      " ##   .## ",
      "       ## ",
      "   .####. ",
      "      .## ",
      "       ## ",
      " ##   .## ",
      "  .####.  ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('4', Array(
      "          ",
      "     ###  ",
      "    ####  ",
      "   ## ##  ",
      "  ##  ##  ",
      " ##   ##  ",
      " ######## ",
      "      ##  ",
      "    ##### ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('5', Array(
      "          ",
      "  ######  ",
      "  ##      ",
      "  ##      ",
      "  #####.  ",
      "      .## ",
      "       ## ",
      " ##   .## ",
      "  .####.  ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('6', Array(
      "          ",
      "   .####. ",
      "  .##     ",
      " ##       ",
      " ##.####. ",
      " ###   ## ",
      " ##    ## ",
      " .##  ##. ",
      "  .####.  ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('7', Array(
      "          ",
      " ######## ",
      " #.   .## ",
      "     .##  ",
      "    .##   ",
      "    ##    ",
      "   ##     ",
      "   ##     ",
      "   ##     ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('8', Array(
      "          ",
      "  .####.  ",
      " ##    ## ",
      " .##  ##. ",
      "  .####.  ",
      " .##  ##. ",
      " ##    ## ",
      " ##    ## ",
      "  .####.  ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('9', Array(
      "          ",
      "  .####.  ",
      " .##  ##. ",
      " ##    ## ",
      " .##  ### ",
      "  .####.# ",
      "      .## ",
      "  #  .##  ",
      "  ####.   ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('?', Array(
      "          ",
      "  .####.  ",
      " ##   .## ",
      "      .## ",
      "    .###  ",
      "    ##    ",
      "          ",
      "    ##    ",
      "    ##    ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('/', Array(
      "          ",
      "      ##  ",
      "     .##  ",
      "     ##   ",
      "    ##    ",
      "   ##     ",
      "  .##     ",
      "  ##      ",
      " ##       ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('+', Array(
      "          ",
      "          ",
      "    ##    ",
      "    ##    ",
      " ######## ",
      "    ##    ",
      "    ##    ",
      "          ",
      "          ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('=', Array(
      "          ",
      "          ",
      "          ",
      " ######## ",
      "          ",
      "          ",
      " ######## ",
      "          ",
      "          ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('*', Array(
      "          ",
      "    ##    ",
      " ##.##.## ",
      "  .####.  ",
      " ##.##.## ",
      "    ##    ",
      "          ",
      "          ",
      "          ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('<', Array(
      "          ",
      "      ##  ",
      "    .##   ",
      "   .##    ",
      "  ##      ",
      "   .##    ",
      "    .##   ",
      "      ##  ",
      "          ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('>', Array(
      "          ",
      "  ##      ",
      "   ##.    ",
      "    ##.   ",
      "      ##  ",
      "    ##.   ",
      "   ##.    ",
      "  ##      ",
      "          ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('\\', Array(
      "          ",
      " ##       ",
      "  ##      ",
      "  .##     ",
      "   ##     ",
      "    ##    ",
      "     ##   ",
      "     .##  ",
      "      ##  ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('^', Array(
      "          ",
      "    ##    ",
      "   ####   ",
      "  ##  ##  ",
      "          ",
      "          ",
      "          ",
      "          ",
      "          ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('~', Array(
      "          ",
      "          ",
      "          ",
      "          ",
      " .###.  # ",
      "##  .###. ",
      "          ",
      "          ",
      "          ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('{', Array(
      "          ",
      "    ###   ",
      "   ##     ",
      "   ##     ",
      "  ##      ",
      "   ##     ",
      "   ##     ",
      "   ##     ",
      "    ###   ",
      "          ",
      "          ",
      "          "
    ))
    registerGlyph('}', Array(
      "          ",
      "  ###     ",
      "    ##    ",
      "    ##    ",
      "     ##   ",
      "    ##    ",
      "    ##    ",
      "    ##    ",
      "  ###     ",
      "          ",
      "          ",
      "          "
    ))

    // ------------------------------------------------------------------------
    // 6-UNIT CHARACTERS (12 pixels wide: b, d, g, h, k, n, o, p, q, u, y, J, L, S)
    // ------------------------------------------------------------------------
    registerGlyph('b', Array(
      "            ",
      "###         ",
      " ##         ",
      " ## .####.  ",
      " ###.   ##. ",
      " ##      ## ",
      " ##      ## ",
      " ###.   ##. ",
      " #.#####.   ",
      "            ",
      "            ",
      "            "
    ))
    registerGlyph('d', Array(
      "            ",
      "       ###  ",
      "        ##  ",
      "  .####.##  ",
      " .##   ###  ",
      " ##     ##  ",
      " ##     ##  ",
      " .##   ###  ",
      "  .####.### ",
      "            ",
      "            ",
      "            "
    ))
    registerGlyph('g', Array(
      "            ",
      "            ",
      "            ",
      "  .####.### ",
      " .##   .##  ",
      " .##   .##  ",
      "  .####.    ",
      " .##        ",
      "  ########. ",
      " .#     .## ",
      "  .######.  ",
      "            "
    ))
    registerGlyph('h', Array(
      "            ",
      "###         ",
      " ##         ",
      " ## .####.  ",
      " ###.   ##  ",
      " ##     ##  ",
      " ##     ##  ",
      " ##     ##  ",
      "####   #### ",
      "            ",
      "            ",
      "            "
    ))
    registerGlyph('k', Array(
      "            ",
      "###         ",
      " ##         ",
      " ##  .####  ",
      " ## .##     ",
      " #####.     ",
      " ###.##.    ",
      " ##   .##   ",
      "####  .#### ",
      "            ",
      "            ",
      "            "
    ))
    registerGlyph('n', Array(
      "            ",
      "            ",
      "            ",
      "### .####.  ",
      " ###.   ##  ",
      " ##     ##  ",
      " ##     ##  ",
      " ##     ##  ",
      "####   #### ",
      "            ",
      "            ",
      "            "
    ))
    registerGlyph('o', Array(
      "            ",
      "            ",
      "            ",
      "  .######.  ",
      " .##    ##. ",
      " ##      ## ",
      " ##      ## ",
      " .##    ##. ",
      "  .######.  ",
      "            ",
      "            ",
      "            "
    ))
    registerGlyph('p', Array(
      "            ",
      "            ",
      "            ",
      "### .####.  ",
      " ###.   ##. ",
      " ##      ## ",
      " ##      ## ",
      " ###.   ##. ",
      " ##.####.   ",
      " ##         ",
      "####        ",
      "            "
    ))
    registerGlyph('q', Array(
      "            ",
      "            ",
      "            ",
      "  .####.### ",
      " .##   ###  ",
      " ##     ##  ",
      " ##     ##  ",
      " .##   ###  ",
      "  .####.##  ",
      "        ##  ",
      "       #### ",
      "            "
    ))
    registerGlyph('u', Array(
      "            ",
      "            ",
      "            ",
      "###    ###  ",
      " ##     ##  ",
      " ##     ##  ",
      " ##     ##  ",
      " ##.   ###  ",
      "  .####.### ",
      "            ",
      "            ",
      "            "
    ))
    registerGlyph('y', Array(
      "            ",
      "            ",
      "            ",
      "####   #### ",
      " .##   ##.  ",
      "  ##  .##   ",
      "  .## ##    ",
      "   ####     ",
      "    ##      ",
      "   ##       ",
      " ###.       ",
      "            "
    ))
    registerGlyph('J', Array(
      "            ",
      "     ###### ",
      "       ##   ",
      "       ##   ",
      "       ##   ",
      "       ##   ",
      " ##    ##   ",
      " .##  ##.   ",
      "  .####.    ",
      "            ",
      "            ",
      "            "
    ))
    registerGlyph('L', Array(
      "            ",
      "#####       ",
      " .##.       ",
      "  ##        ",
      "  ##        ",
      "  ##        ",
      "  ##     .# ",
      "  ##    .## ",
      "##########  ",
      "            ",
      "            ",
      "            "
    ))
    registerGlyph('S', Array(
      "            ",
      "  .#####.#  ",
      " .##    ##  ",
      " ##.        ",
      "  .#####.   ",
      "      .###. ",
      "        .## ",
      " ##.   .##  ",
      " #.######.  ",
      "            ",
      "            ",
      "            "
    ))

    // ------------------------------------------------------------------------
    // 7-UNIT CHARACTERS (14 pixels wide: Standard Uppercase)
    // ------------------------------------------------------------------------
    registerGlyph('A', Array(
      "              ",
      "     .###.    ",
      "     ## ##    ",
      "    .## ##.   ",
      "    ##   ##   ",
      "   #########  ",
      "   ##     ##  ",
      "  .##     ##. ",
      " ####    #### ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('B', Array(
      "              ",
      "#########.    ",
      " .##    .##   ",
      "  ##     ##   ",
      "  ########.   ",
      "  ##    .##.  ",
      "  ##      ##  ",
      " .##     .##  ",
      "##########.   ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('C', Array(
      "              ",
      "   .######.#  ",
      "  .##    .##  ",
      " .##       #  ",
      " ##           ",
      " ##           ",
      " .##          ",
      "  .##    .##  ",
      "   .######.   ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('D', Array(
      "              ",
      "########.     ",
      " .##   .##.   ",
      "  ##     ##.  ",
      "  ##      ##  ",
      "  ##      ##  ",
      "  ##     ##.  ",
      " .##   .##.   ",
      "########.     ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('E', Array(
      "              ",
      "###########   ",
      " .##     .#   ",
      "  ##   .      ",
      "  #######     ",
      "  ##   .      ",
      "  ##      .   ",
      " .##     .#   ",
      "###########   ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('F', Array(
      "              ",
      "###########   ",
      " .##     .#   ",
      "  ##   .      ",
      "  #######     ",
      "  ##   .      ",
      "  ##          ",
      " .##.         ",
      "#####         ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('G', Array(
      "              ",
      "   .######.#  ",
      "  .##    .##  ",
      " .##          ",
      " ##           ",
      " ##    ###### ",
      " .##     ##.  ",
      "  .##    ##.  ",
      "   .######.   ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('H', Array(
      "              ",
      "#####   ##### ",
      " .##     ##.  ",
      "  ##     ##   ",
      "  #########   ",
      "  ##     ##   ",
      "  ##     ##   ",
      " .##     ##.  ",
      "#####   ##### ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('K', Array(
      "              ",
      "#####   ####  ",
      " .##   .##.   ",
      "  ##  .##     ",
      "  #####.      ",
      "  ###.##.     ",
      "  ##   .##.   ",
      " .##    .##.  ",
      "#####    #### ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('N', Array(
      "              ",
      "####    ##### ",
      " ###.    ##.  ",
      "  ####   ##   ",
      "  ##.##  ##   ",
      "  ## .## ##   ",
      "  ##  .####   ",
      " .##   .###   ",
      "####    .##   ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('O', Array(
      "              ",
      "   .######.   ",
      "  .##    ##.  ",
      " .##      ##. ",
      " ##        ## ",
      " ##        ## ",
      " .##      ##. ",
      "  .##    ##.  ",
      "   .######.   ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('P', Array(
      "              ",
      "#########.    ",
      " .##    .##   ",
      "  ##     ##   ",
      "  ##    .##   ",
      "  ########.   ",
      "  ##          ",
      " .##.         ",
      "#####         ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('R', Array(
      "              ",
      "#########.    ",
      " .##    .##   ",
      "  ##     ##   ",
      "  ########.   ",
      "  ##  .##.    ",
      "  ##   .##.   ",
      " .##    .##.  ",
      "#####    .### ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('T', Array(
      "              ",
      "############  ",
      "#   .##.   #  ",
      "     ##       ",
      "     ##       ",
      "     ##       ",
      "     ##       ",
      "    .##.      ",
      "   ######     ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('U', Array(
      "              ",
      "#####   ##### ",
      " .##     ##.  ",
      "  ##     ##   ",
      "  ##     ##   ",
      "  ##     ##   ",
      "  ##     ##   ",
      "  .##   ##.   ",
      "   .#####.    ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('V', Array(
      "              ",
      "####     #### ",
      " .##     ##.  ",
      "  ##.   .##   ",
      "  .##   ##.   ",
      "   ##. .##    ",
      "   .## ##.    ",
      "    .###.     ",
      "     ###      ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('X', Array(
      "              ",
      "####    .#### ",
      " .##.   ##.   ",
      "  .##. .##    ",
      "    ####.     ",
      "    .####     ",
      "   ##. .##.   ",
      " .##.   .##.  ",
      "####    .#### ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('Y', Array(
      "              ",
      "####     #### ",
      " .##.   .##.  ",
      "  .##. .##.   ",
      "   .#####.    ",
      "     ###      ",
      "     ###      ",
      "    .###.     ",
      "   #######    ",
      "              ",
      "              ",
      "              "
    ))
    registerGlyph('Z', Array(
      "              ",
      " ###########  ",
      " #      .##   ",
      "       .##    ",
      "      .##     ",
      "     .##      ",
      "   .##        ",
      "  .##      #  ",
      " ###########  ",
      "              ",
      "              ",
      "              "
    ))

    // ------------------------------------------------------------------------
    // 8-UNIT CHARACTERS (16 pixels wide: w, Q, &, %, $)
    // ------------------------------------------------------------------------
    registerGlyph('w', Array(
      "                ",
      "                ",
      "                ",
      "###   ###   ### ",
      " ##   ###   ##  ",
      " .## ##.## ##.  ",
      "  ## ## ## ##   ",
      "  .#### ####.   ",
      "   ###   ###    ",
      "                ",
      "                ",
      "                "
    ))
    registerGlyph('Q', Array(
      "                ",
      "   .########.   ",
      "  .##      ##.  ",
      " .##        ##. ",
      " ##          ## ",
      " ##          ## ",
      " .##   ##   ##. ",
      "  .##   ## ##.  ",
      "   .########.   ",
      "         .###.  ",
      "           .### ",
      "                "
    ))
    registerGlyph('&', Array(
      "                ",
      "    .####.      ",
      "   .##  ##.     ",
      "   .##  ##.     ",
      "    .####.      ",
      "  .##. ###. ##  ",
      " .##    .###.   ",
      " .##     .###   ",
      "  .######. ###  ",
      "                ",
      "                ",
      "                "
    ))
    registerGlyph('%', Array(
      "                ",
      "  ###.     ##   ",
      " ##  ##   .##   ",
      "  ###.   .##    ",
      "        .##     ",
      "       .##      ",
      "      .##  ###. ",
      "     .##  ##  ##",
      "     ##    ###. ",
      "                ",
      "                ",
      "                "
    ))
    registerGlyph('$', Array(
      "                ",
      "      ##        ",
      "   .#######.    ",
      "  .##  ##  #    ",
      "  .##  ##       ",
      "   .#######.    ",
      "       ## .##   ",
      "  #    ## .##   ",
      "   .#######.    ",
      "      ##        ",
      "                ",
      "                "
    ))

    // ------------------------------------------------------------------------
    // 9-UNIT CHARACTERS (18 pixels wide: m, M, W, @, #, _)
    // ------------------------------------------------------------------------
    registerGlyph('m', Array(
      "                  ",
      "                  ",
      "                  ",
      "### .####. .####. ",
      " ###.   ###.   ## ",
      " ##     ##     ## ",
      " ##     ##     ## ",
      " ##     ##     ## ",
      "####   ####   ####",
      "                  ",
      "                  ",
      "                  "
    ))
    registerGlyph('M', Array(
      "                  ",
      "####          ####",
      " ###.        .### ",
      "  ####      ####  ",
      "  ##.##    ##.##  ",
      "  ## .##  ##. ##  ",
      "  ##  .####.  ##  ",
      " .##   .##.   ##. ",
      "####    ##    ####",
      "                  ",
      "                  ",
      "                  "
    ))
    registerGlyph('W', Array(
      "                  ",
      "####   ####   ####",
      " .##    ##    ##. ",
      "  ##   ####   ##  ",
      "  .## ##..## ##.  ",
      "   ## ##  ## ##   ",
      "   .####  ####.   ",
      "    ###    ###    ",
      "    .##    ##.    ",
      "                  ",
      "                  ",
      "                  "
    ))
    registerGlyph('@', Array(
      "                  ",
      "    .########.    ",
      "  .##        ##.  ",
      " .##   .####. ##. ",
      " ##   .##  ##  ## ",
      " ##   ##   ##  ## ",
      " .##  .##  ###.#. ",
      "  .##   .###.##.  ",
      "    .#######.     ",
      "                  ",
      "                  ",
      "                  "
    ))
    registerGlyph('#', Array(
      "                  ",
      "     ##    ##     ",
      "     ##    ##     ",
      "  ##############  ",
      "    ##    ##      ",
      "    ##    ##      ",
      "  ##############  ",
      "   ##    ##       ",
      "   ##    ##       ",
      "                  ",
      "                  ",
      "                  "
    ))
    registerGlyph('_', Array(
      "                  ",
      "                  ",
      "                  ",
      "                  ",
      "                  ",
      "                  ",
      "                  ",
      "                  ",
      "                  ",
      "################  ",
      "                  ",
      "                  "
    ))
  }

  initFontTable()
}
