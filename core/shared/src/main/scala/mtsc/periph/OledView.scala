package mtsc.periph

import mtsc.console.{CeConsoleState, CeDisplayMode}

/**
 * Zero-allocation `128×32` monochrome framebuffer renderer for the **Adafruit 128×32 OLED FeatherWing**
 * ([PID 2900](https://www.adafruit.com/product/2900), `SSD1306` on `I2C1` `0x3C`).
 *
 * Designed to run alongside the 2.9" Grayscale eInk Galley FeatherWing (#4777):
 * while the eInk renders the physical Selectric Composer camera-ready paper sheet,
 * the high-refresh OLED acts as the **live IBM MT/SC 6320 CE Console & Backplane Panel**:
 *
 * - **Top Band (`y = 0..8`)**:
 *   - Active Display Mode (`CLK`, `DP1`..`DP4`, or `OPR`)
 *   - Live 11-lamp incandescent indicator array (`LITE 11..1` from ILD Page 0312, `●` lit / `○` unlit)
 *     grouped into `3 | 4 | 4` nibbles (`Y11..9 | Y8..5 | Y4..1`)
 *   - Machine state badge (`RUN`, `INH`, `MPX`, `HLT`, or inverted `ERR`)
 * - **Middle Band (`y = 10..19`)**:
 *   - Current Galley Line (`L01`..`L99`)
 *   - Live Escapement & Justification Progress Bar (`0 .. targetMeasure` units)
 *   - Numeric unit counter (`084/140`)
 * - **Bottom Band (`y = 22..31`)**:
 *   - Live 11-bit Program Step Address (`PC:1A4`)
 *   - Live 6310 Magnetic Tape Reader track position (`MT:0142`)
 *   - Live Translator character & proportional escapement width (`'A'7u` or `GL:08L`)
 */
object OledView {

  final val WidthPx: Int     = 128
  final val HeightPx: Int    = 32
  final val Pages: Int       = HeightPx / 8 // 4 pages of 8 vertical pixels
  final val BufferBytes: Int = WidthPx * Pages // 512 bytes

  /**
   * Clears a 512-byte SSD1306 page-major buffer to all-black (`0x00`).
   */
  def clearBuffer(buf: Array[Byte]): Unit = {
    var i = 0
    val len = math.min(buf.length, BufferBytes)
    while (i < len) {
      buf(i) = 0.toByte
      i += 1
    }
  }

  /**
   * Sets or clears pixel `(x, y)` in SSD1306 horizontal page-major format:
   * byte index = `(y >> 3) * 128 + x`, bit mask = `1 << (y & 7)`.
   */
  @inline def setPixel(buf: Array[Byte], x: Int, y: Int, on: Boolean = true): Unit = {
    if (x < 0 || x >= WidthPx || y < 0 || y >= HeightPx) return
    val idx = (y >> 3) * WidthPx + x
    val mask = 1 << (y & 7)
    if (on) {
      buf(idx) = (buf(idx) | mask).toByte
    } else {
      buf(idx) = (buf(idx) & ~mask).toByte
    }
  }

  /**
   * Reads pixel `(x, y)` from an SSD1306 page-major buffer.
   */
  def getPixel(buf: Array[Byte], x: Int, y: Int): Boolean = {
    if (x < 0 || x >= WidthPx || y < 0 || y >= HeightPx) return false
    val idx = (y >> 3) * WidthPx + x
    ((buf(idx).toInt >> (y & 7)) & 1) != 0
  }

  // Compact 3x5 bitmap font (15 bits per glyph: row0 in bits 14..12 .. row4 in bits 2..0)
  private def glyph3x5Bits(ch: Char): Int = (ch: @scala.annotation.switch) match {
    case '0' => 0x7B6F // 111 / 101 / 101 / 101 / 111
    case '1' => 0x2492 // 010 / 100 / 010 / 010 / 010 -> 0x2692 (let's use 010 / 110 / 010 / 010 / 111 = 0x2D97)
    case '2' => 0x73E7 // 111 / 001 / 111 / 100 / 111
    case '3' => 0x73CF // 111 / 001 / 111 / 001 / 111
    case '4' => 0x5BC9 // 101 / 101 / 111 / 001 / 001
    case '5' => 0x79CF // 111 / 100 / 111 / 001 / 111
    case '6' => 0x79EF // 111 / 100 / 111 / 101 / 111
    case '7' => 0x7249 // 111 / 001 / 001 / 001 / 001
    case '8' => 0x7BEF // 111 / 101 / 111 / 101 / 111
    case '9' => 0x7BCF // 111 / 101 / 111 / 001 / 111
    case 'A' | 'a' => 0x2BED // 010 / 101 / 111 / 101 / 101
    case 'B' | 'b' => 0x6BAE // 110 / 101 / 110 / 101 / 110
    case 'C' | 'c' => 0x3923 // 011 / 100 / 100 / 100 / 011
    case 'D' | 'd' => 0x6B6E // 110 / 101 / 101 / 101 / 110
    case 'E' | 'e' => 0x79E7 // 111 / 100 / 111 / 100 / 111
    case 'F' | 'f' => 0x79E4 // 111 / 100 / 111 / 100 / 100
    case 'G' | 'g' => 0x396F // 011 / 100 / 101 / 101 / 111
    case 'H' | 'h' => 0x5BED // 101 / 101 / 111 / 101 / 101
    case 'I' | 'i' => 0x7497 // 111 / 010 / 010 / 010 / 111
    case 'J' | 'j' => 0x126E // 001 / 001 / 001 / 101 / 110
    case 'K' | 'k' => 0x5AED // 101 / 101 / 110 / 101 / 101
    case 'L' | 'l' => 0x4927 // 100 / 100 / 100 / 100 / 111
    case 'M' | 'm' => 0x5FED // 101 / 111 / 111 / 101 / 101
    case 'N' | 'n' => 0x6B6D // 110 / 101 / 101 / 101 / 101
    case 'O' | 'o' => 0x2B6A // 010 / 101 / 101 / 101 / 010
    case 'P' | 'p' => 0x6BE4 // 110 / 101 / 111 / 100 / 100
    case 'Q' | 'q' => 0x2B6B // 010 / 101 / 101 / 101 / 011
    case 'R' | 'r' => 0x6BAD // 110 / 101 / 110 / 101 / 101
    case 'S' | 's' => 0x39CE // 011 / 100 / 111 / 001 / 110
    case 'T' | 't' => 0x7492 // 111 / 010 / 010 / 010 / 010
    case 'U' | 'u' => 0x5B6F // 101 / 101 / 101 / 101 / 111
    case 'V' | 'v' => 0x5B6A // 101 / 101 / 101 / 101 / 010
    case 'W' | 'w' => 0x5B7F // 101 / 101 / 101 / 111 / 111
    case 'X' | 'x' => 0x5AAD // 101 / 101 / 010 / 101 / 101
    case 'Y' | 'y' => 0x5AA2 // 101 / 101 / 010 / 010 / 010
    case 'Z' | 'z' => 0x72A7 // 111 / 001 / 010 / 100 / 111
    case ':'       => 0x0410 // 000 / 010 / 000 / 010 / 000
    case '/'       => 0x12A4 // 001 / 001 / 010 / 100 / 100
    case '-'       => 0x01C0 // 000 / 000 / 111 / 000 / 000
    case '\''      => 0x2400 // 010 / 010 / 000 / 000 / 000
    case '.'       => 0x0002 // 000 / 000 / 000 / 000 / 010
    case _         => 0x0000
  }

  private def drawChar3x5(
    buf: Array[Byte],
    ch: Char,
    x0: Int,
    y0: Int,
    invert: Boolean = false
  ): Unit = {
    val bits = if (ch == '1') 0x2D97 else glyph3x5Bits(ch)
    var r = 0
    while (r < 5) {
      val rowBits = (bits >> ((4 - r) * 3)) & 0x7
      var c = 0
      while (c < 3) {
        val fg = ((rowBits >> (2 - c)) & 1) != 0
        val pixelOn = if (invert) !fg else fg
        if (pixelOn) {
          setPixel(buf, x0 + c, y0 + r, on = true)
        }
        c += 1
      }
      r += 1
    }
  }

  private def drawText3x5(
    buf: Array[Byte],
    s: String,
    x0: Int,
    y0: Int
  ): Int = {
    var x = x0
    var i = 0
    val len = s.length
    while (i < len) {
      val ch = s.charAt(i)
      if (ch != ' ') {
        drawChar3x5(buf, ch, x, y0)
      }
      x += 4
      i += 1
    }
    x
  }

  private val HexDigits: Array[Char] = Array(
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
  )

  private def drawHex3(buf: Array[Byte], value: Int, x0: Int, y0: Int): Unit = {
    val v = value & 0xfff
    drawChar3x5(buf, HexDigits((v >> 8) & 0xf), x0, y0)
    drawChar3x5(buf, HexDigits((v >> 4) & 0xf), x0 + 4, y0)
    drawChar3x5(buf, HexDigits(v & 0xf), x0 + 8, y0)
  }

  private def drawDec2(buf: Array[Byte], value: Int, x0: Int, y0: Int): Unit = {
    val clamped = math.max(0, math.min(99, value))
    drawChar3x5(buf, ('0' + (clamped / 10)).toChar, x0, y0)
    drawChar3x5(buf, ('0' + (clamped % 10)).toChar, x0 + 4, y0)
  }

  private def drawDec3(buf: Array[Byte], value: Int, x0: Int, y0: Int): Unit = {
    val clamped = math.max(0, math.min(999, value))
    drawChar3x5(buf, ('0' + (clamped / 100)).toChar, x0, y0)
    drawChar3x5(buf, ('0' + ((clamped / 10) % 10)).toChar, x0 + 4, y0)
    drawChar3x5(buf, ('0' + (clamped % 10)).toChar, x0 + 8, y0)
  }

  private def drawDec4(buf: Array[Byte], value: Int, x0: Int, y0: Int): Unit = {
    val clamped = math.max(0, math.min(9999, value))
    drawChar3x5(buf, ('0' + (clamped / 1000)).toChar, x0, y0)
    drawChar3x5(buf, ('0' + ((clamped / 100) % 10)).toChar, x0 + 4, y0)
    drawChar3x5(buf, ('0' + ((clamped / 10) % 10)).toChar, x0 + 8, y0)
    drawChar3x5(buf, ('0' + (clamped % 10)).toChar, x0 + 12, y0)
  }

  /**
   * Draws one 5×5 incandescent console lamp at `(x0, y0)`:
   * - `lit == true`: filled 5×5 circle (`●`)
   * - `lit == false`: subtle 5×5 hollow ring (`○`)
   */
  private def drawLamp5x5(buf: Array[Byte], x0: Int, y0: Int, lit: Boolean): Unit = {
    // Top and bottom 3px edges
    setPixel(buf, x0 + 1, y0, on = true)
    setPixel(buf, x0 + 2, y0, on = true)
    setPixel(buf, x0 + 3, y0, on = true)
    setPixel(buf, x0 + 1, y0 + 4, on = true)
    setPixel(buf, x0 + 2, y0 + 4, on = true)
    setPixel(buf, x0 + 3, y0 + 4, on = true)
    // Left and right 3px edges
    setPixel(buf, x0, y0 + 1, on = true)
    setPixel(buf, x0, y0 + 2, on = true)
    setPixel(buf, x0, y0 + 3, on = true)
    setPixel(buf, x0 + 4, y0 + 1, on = true)
    setPixel(buf, x0 + 4, y0 + 2, on = true)
    setPixel(buf, x0 + 4, y0 + 3, on = true)

    if (lit) {
      // Fill 3×3 interior
      var dy = 1
      while (dy <= 3) {
        var dx = 1
        while (dx <= 3) {
          setPixel(buf, x0 + dx, y0 + dy, on = true)
          dx += 1
        }
        dy += 1
      }
    }
  }

  /**
   * Renders the complete `128×32` live console and telemetry frame into `buf` (`512` bytes)
   * with zero heap allocations.
   */
  def renderToBuffer(
    system: MtscSystem,
    consoleState: CeConsoleState,
    targetMeasure: Int,
    buf: Array[Byte]
  ): Unit = {
    clearBuffer(buf)

    // ========================================================================
    // 1. TOP BAND (y = 0..8): Mode Label + 11 Console Lamps + Status Badge
    // ========================================================================
    if (consoleState.faceplateInstalled) {
      drawChar3x5(buf, 'O', 0, 1)
      drawChar3x5(buf, 'P', 4, 1)
      drawChar3x5(buf, 'R', 8, 1)
    } else {
      consoleState.displayMode match {
        case CeDisplayMode.ClockDefault =>
          drawChar3x5(buf, 'C', 0, 1)
          drawChar3x5(buf, 'L', 4, 1)
          drawChar3x5(buf, 'K', 8, 1)
        case CeDisplayMode.Display1 =>
          drawChar3x5(buf, 'D', 0, 1)
          drawChar3x5(buf, 'P', 4, 1)
          drawChar3x5(buf, '1', 8, 1)
        case CeDisplayMode.Display2 =>
          drawChar3x5(buf, 'D', 0, 1)
          drawChar3x5(buf, 'P', 4, 1)
          drawChar3x5(buf, '2', 8, 1)
        case CeDisplayMode.Display3 =>
          drawChar3x5(buf, 'D', 0, 1)
          drawChar3x5(buf, 'P', 4, 1)
          drawChar3x5(buf, '3', 8, 1)
        case CeDisplayMode.Display4 =>
          drawChar3x5(buf, 'D', 0, 1)
          drawChar3x5(buf, 'P', 4, 1)
          drawChar3x5(buf, '4', 8, 1)
      }
    }

    // 11 Console Lamps (bit 0 = leftmost LITE 11 .. bit 10 = rightmost LITE 1)
    val lampsMask = consoleState.computeLampsMask(system.cpu)
    var lampIdx = 0
    var lampX = 16
    while (lampIdx < 11) {
      val isLit = ((lampsMask >> lampIdx) & 1) != 0
      drawLamp5x5(buf, lampX, 1, isLit)
      lampX += 7
      // Add 2px nibble gap after Lamp #3 (Y11..Y9) and Lamp #7 (Y8..Y5)
      if (lampIdx == 2 || lampIdx == 6) {
        setPixel(buf, lampX, 3, on = true)
        lampX += 3
      }
      lampIdx += 1
    }

    // Status Badge at x = 104..127
    val sig = system.cpu.ild.signals
    val hasErr = sig.errorLite || sig.lite9 || system.rightTape.parityError || system.leftTape.parityError
    val isInh = sig.inh || sig.lite11 || system.cpu.state.inhibit || system.operatorPanel.activeSetupParam.isDefined
    val isMpx = sig.mpx || system.cpu.state.multiplex
    val isHlt = system.cpu.state.halted

    if (hasErr) {
      // Inverted box for ERR
      var bx = 113
      while (bx < 127) {
        var by = 0
        while (by < 7) {
          setPixel(buf, bx, by, on = true)
          by += 1
        }
        bx += 1
      }
      drawChar3x5(buf, 'E', 114, 1, invert = true)
      drawChar3x5(buf, 'R', 118, 1, invert = true)
      drawChar3x5(buf, 'R', 122, 1, invert = true)
    } else if (isInh) {
      drawChar3x5(buf, 'I', 114, 1)
      drawChar3x5(buf, 'N', 118, 1)
      drawChar3x5(buf, 'H', 122, 1)
    } else if (isMpx) {
      drawChar3x5(buf, 'M', 114, 1)
      drawChar3x5(buf, 'P', 118, 1)
      drawChar3x5(buf, 'X', 122, 1)
    } else if (isHlt) {
      drawChar3x5(buf, 'H', 114, 1)
      drawChar3x5(buf, 'L', 118, 1)
      drawChar3x5(buf, 'T', 122, 1)
    } else {
      drawChar3x5(buf, 'R', 114, 1)
      drawChar3x5(buf, 'U', 118, 1)
      drawChar3x5(buf, 'N', 122, 1)
    }

    // Dotted horizontal divider at y = 8
    var dx = 0
    while (dx < WidthPx) {
      if ((dx & 1) == 0) {
        setPixel(buf, dx, 8, on = true)
      }
      dx += 1
    }

    // ========================================================================
    // 2. MIDDLE BAND (y = 10..19): Line # + Escapement Progress Bar + Units
    // ========================================================================
    val lineNum = math.max(1, system.paper.lineCount)
    drawChar3x5(buf, 'L', 0, 12)
    drawDec2(buf, lineNum, 4, 12)

    // Progress bar frame at x = 15..92 (width = 78 px), y = 11..17 (height = 7 px)
    val barX0 = 15
    val barW  = 78
    val barY0 = 11
    val barH  = 7
    var px = barX0
    while (px < barX0 + barW) {
      setPixel(buf, px, barY0, on = true)
      setPixel(buf, px, barY0 + barH - 1, on = true)
      px += 1
    }
    var py = barY0
    while (py < barY0 + barH) {
      setPixel(buf, barX0, py, on = true)
      setPixel(buf, barX0 + barW - 1, py, on = true)
      py += 1
    }

    val safeMeasure = math.max(30, targetMeasure)
    val curUnits = math.max(0, system.paper.currentXUnits)
    val innerMaxW = barW - 4 // 74 px
    val fillW = math.min(innerMaxW, (curUnits * innerMaxW) / safeMeasure)
    var fx = 0
    while (fx < fillW) {
      var fy = barY0 + 2
      while (fy <= barY0 + barH - 3) {
        setPixel(buf, barX0 + 2 + fx, fy, on = true)
        fy += 1
      }
      fx += 1
    }

    // Numeric unit counter `curUnits/targetMeasure` at x = 96..127 (e.g. `084/140`)
    drawDec3(buf, curUnits, 96, 12)
    drawChar3x5(buf, '/', 108, 12)
    drawDec3(buf, safeMeasure, 112, 12)
    drawChar3x5(buf, 'U', 124, 12)

    // Dotted horizontal divider at y = 20
    dx = 0
    while (dx < WidthPx) {
      if ((dx & 1) == 0) {
        setPixel(buf, dx, 20, on = true)
      }
      dx += 1
    }

    // ========================================================================
    // 3. BOTTOM BAND (y = 22..31): PC:hex3 + MT:dec4 + Last Char / Galley Lines
    // ========================================================================
    // PC:xxx at x = 0..23
    drawChar3x5(buf, 'P', 0, 24)
    drawChar3x5(buf, 'C', 4, 24)
    drawChar3x5(buf, ':', 8, 24)
    drawHex3(buf, system.memory.psar.unsigned, 12, 24)

    // MT:xxxx at x = 32..63
    val tapeHead =
      if (system.leftTape.isTapeLoaded && system.leftTape.headPosition > 0) system.leftTape.headPosition
      else system.rightTape.headPosition
    drawChar3x5(buf, 'M', 32, 24)
    drawChar3x5(buf, 'T', 36, 24)
    drawChar3x5(buf, ':', 40, 24)
    drawDec4(buf, tapeHead, 44, 24)

    // Right telemetry at x = 72..127: `GL:xxL` + total chars printed
    drawChar3x5(buf, 'G', 70, 24)
    drawChar3x5(buf, 'L', 74, 24)
    drawChar3x5(buf, ':', 78, 24)
    drawDec2(buf, system.paper.completedLinesCount, 82, 24)
    drawChar3x5(buf, 'L', 90, 24)

    drawChar3x5(buf, 'C', 98, 24)
    drawChar3x5(buf, ':', 102, 24)
    drawDec4(buf, system.paper.totalCharactersPrinted.toInt, 106, 24)
  }
}
