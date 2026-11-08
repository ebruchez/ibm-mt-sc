package mtsc.periph

import munit.FunSuite
import mtsc.console.{CeConsoleState, CeDisplayMode}
import mtsc.cpu.CcuEngineMode
import mtsc.types.Address

class OledViewSuite extends FunSuite {

  test("OledView.setPixel and getPixel round-trip across all 4 vertical pages of 128x32 SSD1306 buffer") {
    val buf = new Array[Byte](OledView.BufferBytes)
    OledView.clearBuffer(buf)

    val coords = Seq(
      (0, 0),
      (127, 0),
      (0, 7),
      (64, 8),
      (64, 15),
      (32, 16),
      (100, 24),
      (127, 31)
    )
    for ((x, y) <- coords) {
      assert(!OledView.getPixel(buf, x, y))
      OledView.setPixel(buf, x, y, on = true)
      assert(OledView.getPixel(buf, x, y), s"Expected pixel ($x, $y) to be set")
    }
    for ((x, y) <- coords) {
      OledView.setPixel(buf, x, y, on = false)
      assert(!OledView.getPixel(buf, x, y), s"Expected pixel ($x, $y) to be cleared")
    }
  }

  test("OledView.renderToBuffer renders console lamps, justification progress bar, and CCU telemetry") {
    val system = new MtscSystem(initialEngineMode = CcuEngineMode.Ild)
    val consoleState = new CeConsoleState()
    consoleState.selectDisplayMode(CeDisplayMode.Display1)
    system.memory.psar = Address(0x5ab)
    system.cpu.state.mal = Address(0x5ab)
    system.paper.printChar('I', 3)
    system.paper.printChar('B', 7)
    system.paper.printChar('M', 9) // currentXUnits = 19

    val buf = new Array[Byte](OledView.BufferBytes)
    OledView.renderToBuffer(system, consoleState, targetMeasure = 140, buf = buf)

    // Verify pixels are lit in all 3 bands (Top y=0..8, Middle y=10..19, Bottom y=22..31)
    var topPixels = 0
    var midPixels = 0
    var botPixels = 0
    for (x <- 0 until OledView.WidthPx) {
      for (y <- 0 to 7) if (OledView.getPixel(buf, x, y)) topPixels += 1
      for (y <- 10 to 19) if (OledView.getPixel(buf, x, y)) midPixels += 1
      for (y <- 22 to 31) if (OledView.getPixel(buf, x, y)) botPixels += 1
    }

    assert(topPixels > 40, s"Expected top lamp band to have >40 lit pixels, got $topPixels")
    assert(midPixels > 80, s"Expected middle progress bar band to have >80 lit pixels, got $midPixels")
    assert(botPixels > 40, s"Expected bottom telemetry band to have >40 lit pixels, got $botPixels")
  }
}
