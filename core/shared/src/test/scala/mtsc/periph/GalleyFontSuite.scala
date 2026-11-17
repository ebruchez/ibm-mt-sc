package mtsc.periph

import munit.FunSuite
import mtsc.diag.EscapementRoutine

class GalleyFontSuite extends FunSuite {

  test("Proportional glyph pixel widths match 2x EscapementRoutine.defaultUnitWidth for all ASCII 32..126") {
    for (code <- 32 to 126) {
      val ch = code.toChar
      val expectedPx = EscapementRoutine.defaultUnitWidth(ch) * GalleyFont.PixelsPerUnit
      val actualPx = GalleyFont.glyphWidthPx(ch)
      assertEquals(
        actualPx,
        expectedPx,
        s"Character '$ch' (ASCII $code) width mismatch: got ${actualPx}px, expected ${expectedPx}px"
      )
    }

    // Spot-check IBM Selectric Composer Press Roman unit classes (3..9 units -> 6..18 px)
    assertEquals(GalleyFont.glyphWidthPx('i'), 6)
    assertEquals(GalleyFont.glyphWidthPx('I'), 6)
    assertEquals(GalleyFont.glyphWidthPx('f'), 8)
    assertEquals(GalleyFont.glyphWidthPx('e'), 10)
    assertEquals(GalleyFont.glyphWidthPx('n'), 12)
    assertEquals(GalleyFont.glyphWidthPx('A'), 14)
    assertEquals(GalleyFont.glyphWidthPx('w'), 16)
    assertEquals(GalleyFont.glyphWidthPx('m'), 18)
    assertEquals(GalleyFont.glyphWidthPx('M'), 18)
    assertEquals(GalleyFont.glyphWidthPx('W'), 18)
  }

  test("All printable non-space ASCII glyphs (33..126) render visible ink pixels within their cell bounds") {
    val bw = new Array[Byte](GalleyFont.PlaneBytes)
    val red = new Array[Byte](GalleyFont.PlaneBytes)

    for (code <- 33 to 126) {
      val ch = code.toChar
      GalleyFont.clearPlanes(bw, red)
      val x0 = 20
      val y0 = 20
      val w = GalleyFont.glyphWidthPx(ch)
      GalleyFont.drawGlyph(bw, red, ch, x0, y0)

      var blackCount = 0
      var outsideCount = 0
      for (y <- 0 until GalleyFont.HeightPx; x <- 0 until GalleyFont.WidthPx) {
        val shade = GalleyFont.getPixelShade(bw, red, x, y)
        if (shade != GalleyFont.ShadeWhite) {
          if (x >= x0 && x < x0 + w && y >= y0 && y < y0 + GalleyFont.GlyphHeightPx) {
            if (shade == GalleyFont.ShadeBlack) {
              blackCount += 1
            }
          } else {
            outsideCount += 1
          }
        }
      }
      assert(blackCount > 0, s"Character '$ch' (ASCII $code) produced no black ink pixels")
      assertEquals(outsideCount, 0, s"Character '$ch' (ASCII $code) drew pixels outside its ${w}x12 cell")
    }
  }

  test("SSD1680 2-bit grayscale plane packing round-trips all 4 shades in normal and flip180 orientations") {
    val bw = new Array[Byte](GalleyFont.PlaneBytes)
    val red = new Array[Byte](GalleyFont.PlaneBytes)

    for (flip <- Seq(false, true)) {
      GalleyFont.clearPlanes(bw, red)
      val testCoords = Seq(
        (0, 0, GalleyFont.ShadeBlack),
        (295, 0, GalleyFont.ShadeDarkGray),
        (0, 127, GalleyFont.ShadeLightGray),
        (295, 127, GalleyFont.ShadeBlack),
        (140, 64, GalleyFont.ShadeDarkGray),
        (141, 64, GalleyFont.ShadeLightGray)
      )
      for ((x, y, shade) <- testCoords) {
        GalleyFont.setPixelInPlanes(bw, red, x, y, shade, flip180 = flip)
      }
      for ((x, y, expectedShade) <- testCoords) {
        assertEquals(
          GalleyFont.getPixelShade(bw, red, x, y, flip180 = flip),
          expectedShade,
          s"Mismatch at ($x, $y) with flip180=$flip"
        )
      }
    }
  }

  test("renderGalleyToPlanes renders pica ruler, measure margin guide, and justified proportional lines") {
    val paper = new ComposerPaper()
    paper.recordGlyphs = true
    // Construct a justified line whose total escapement equals 140 units:
    // "IBM" (3 + 7 + 9 = 19u) + space (107u) + "MT" (9 + 7 = 16u) -> let's use exact 140u:
    // 'I'(3) + 'B'(7) + 'M'(9) = 19u; space(105u); 'M'(9) + 'T'(7) = 16u; 19 + 105 + 16 = 140u
    paper.printChar('I', 3)
    paper.printChar('B', 7)
    paper.printChar('M', 9)
    paper.printChar(' ', 105)
    paper.printChar('M', 9)
    paper.printChar('T', 7)
    assertEquals(paper.currentXUnits, 140)
    paper.carrierReturn()

    val bw = new Array[Byte](GalleyFont.PlaneBytes)
    val red = new Array[Byte](GalleyFont.PlaneBytes)
    GalleyFont.renderGalleyToPlanes(paper, targetMeasure = 140, bwPlane = bw, redPlane = red)

    val leftMargin = GalleyFont.leftMarginForMeasure(140)
    val measureX = leftMargin + 140 * GalleyFont.PixelsPerUnit
    assertEquals(leftMargin, 12)
    assertEquals(measureX, 292)

    // Verify header rule at y = 12 and target measure pointer at (measureX, 11)
    assertEquals(GalleyFont.getPixelShade(bw, red, 50, 12), GalleyFont.ShadeBlack)
    assertEquals(GalleyFont.getPixelShade(bw, red, measureX, 11), GalleyFont.ShadeBlack)

    // Verify dotted margin guide at x = measureX in the galley body
    assertEquals(GalleyFont.getPixelShade(bw, red, measureX, 16), GalleyFont.ShadeDarkGray)

    // Verify 'T' glyph at the end of the 140-unit justified line (xUnits = 133, width = 7u = 14px -> x = 12 + 266..280)
    val tStartX = leftMargin + 133 * GalleyFont.PixelsPerUnit
    var tInkPixels = 0
    for (y <- GalleyFont.HeaderHeightPx until (GalleyFont.HeaderHeightPx + GalleyFont.GlyphHeightPx)) {
      for (x <- tStartX until (tStartX + 14)) {
        if (GalleyFont.getPixelShade(bw, red, x, y) == GalleyFont.ShadeBlack) {
          tInkPixels += 1
        }
      }
    }
    assert(tInkPixels > 10, s"Expected 'T' at right margin to have >10 black pixels, got $tInkPixels")
  }

  test("renderGalleyToPlanes renders clean 5x7 open-counter pica ruler numbers on White ruler background") {
    val paper = new ComposerPaper()
    val bw = new Array[Byte](GalleyFont.PlaneBytes)
    val red = new Array[Byte](GalleyFont.PlaneBytes)
    GalleyFont.renderGalleyToPlanes(paper, targetMeasure = 140, bwPlane = bw, redPlane = red)

    // Ruler background above baseline rule (y = 0..11) is clean White
    assertEquals(GalleyFont.getPixelShade(bw, red, 40, 1), GalleyFont.ShadeWhite)
    // Top-of-'0' in "0p" (at x = leftMargin + 3 = 15, y = 2: row 0 = .###. -> x = 16, 17, 18 are Black; interior (17, 5) is White)
    assertEquals(GalleyFont.getPixelShade(bw, red, 17, 2), GalleyFont.ShadeBlack)
    assertEquals(GalleyFont.getPixelShade(bw, red, 17, 5), GalleyFont.ShadeWhite)
  }

  test("visibleStartLine paginates 8-line sheets during live typing and shows continuous 8-line windows when scrolling or at bottom") {
    // 43-line HistoricalSampleText at 140u:
    // - Live sheet pagination (`paginateSheet = true`, `scrollOffsetLines = 0`) turns to page 6 at index 40 (L41..L43).
    assertEquals(GalleyFont.visibleStartLine(totalLines = 43, scrollOffsetLines = 0, paginateSheet = true), 40)

    // - Proof / Jump-to-Bottom (`paginateSheet = false`, `scrollOffsetLines = 0`) shows full 8-line bottom window 35..42 (L36..L43).
    assertEquals(GalleyFont.visibleStartLine(totalLines = 43, scrollOffsetLines = 0, paginateSheet = false), 35)
    assertEquals(GalleyFont.visibleStartLine(totalLines = 43, scrollOffsetLines = 4, paginateSheet = false), 31)

    // Verify scrolling up and down in 4-line steps across any document length never skips a line
    for (totalLines <- 1 to 75) {
      val maxScroll = math.max(0, totalLines - GalleyFont.MaxVisibleLines)
      val coveredUp = scala.collection.mutable.BitSet.empty
      var lastStart = GalleyFont.visibleStartLine(totalLines, scrollOffsetLines = 0, paginateSheet = false)
      for (i <- lastStart until math.min(totalLines, lastStart + GalleyFont.MaxVisibleLines)) coveredUp += i
      while (lastStart > 0) {
        val targetStart = math.max(0, math.min(maxScroll, lastStart - 4))
        val offset = maxScroll - targetStart
        lastStart = GalleyFont.visibleStartLine(totalLines, offset, paginateSheet = false)
        for (i <- lastStart until math.min(totalLines, lastStart + GalleyFont.MaxVisibleLines)) coveredUp += i
      }
      assertEquals(coveredUp.size, totalLines, s"Scrolling up skipped lines for totalLines=$totalLines")

      val coveredDown = scala.collection.mutable.BitSet.empty
      for (i <- 0 until math.min(totalLines, GalleyFont.MaxVisibleLines)) coveredDown += i
      while (lastStart < maxScroll) {
        val targetStart = math.max(0, math.min(maxScroll, lastStart + 4))
        val offset = maxScroll - targetStart
        lastStart = GalleyFont.visibleStartLine(totalLines, offset, paginateSheet = false)
        for (i <- lastStart until math.min(totalLines, lastStart + GalleyFont.MaxVisibleLines)) coveredDown += i
      }
      assertEquals(coveredDown.size, totalLines, s"Scrolling down skipped lines for totalLines=$totalLines")
    }
  }

  test("HistoricalSampleText at 140u includes 'written in Scala, running on' in the bottom 8-line galley window") {
    val system = new MtscSystem()
    val img = mtsc.asm.Assembler.assemble(mtsc.diag.ContinuousComposerRoutine.Source) match {
      case Right(i)  => i
      case Left(err) => fail(s"Assembly failed: $err")
    }
    system.loadProgram(img)
    EscapementRoutine.initializeTable(system.memory)
    mtsc.diag.ContinuousComposerRoutine.setMeasure(system.memory, 140)
    system.loadTape(mtsc.types.Device.RightTape, TapeCartridge.fromText(TapeCartridge.HistoricalSampleText, "Historical"))

    val res = system.run(maxCycles = 250000)
    assertEquals(res, mtsc.cpu.StepResult.Halted)

    val lines = system.paper.lines
    assertEquals(lines.length, 43)
    val startBottom = GalleyFont.visibleStartLine(lines.length, scrollOffsetLines = 0, paginateSheet = false)
    val bottomWindow = lines.slice(startBottom, startBottom + GalleyFont.MaxVisibleLines)
    assertEquals(bottomWindow.length, 8)
    assert(
      bottomWindow.contains("written in Scala, running on"),
      s"Expected bottom window to contain 'written in Scala, running on', got: $bottomWindow"
    )
  }
}
