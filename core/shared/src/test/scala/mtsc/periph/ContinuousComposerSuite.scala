package mtsc.periph

import mtsc.types.*
import mtsc.cpu.StepResult
import mtsc.diag.{ContinuousComposerRoutine, EscapementRoutine}
import mtsc.asm.Assembler

class ContinuousComposerSuite extends munit.FunSuite {

  test("ContinuousComposerRoutine: Multi-line document composition from tape") {
    val system = new MtscSystem()
    EscapementRoutine.initializeTable(system.memory)

    val sampleText =
      "The IBM Magnetic Tape Selectric Composer is a system for preparing camera-ready copy for publication.\n" +
      "Input data recorded on magnetic tape is read by the tape reader.\n" +
      "The Composer Control Unit stores characters and computes line justification automatically."

    val cartridge = TapeCartridge.fromText(sampleText, "Continuous Text Tape")
    system.loadTape(Device.RightTape, cartridge)

    val img = Assembler.assemble(ContinuousComposerRoutine.Source) match {
      case Right(i) => i
      case Left(err) => fail(s"Assembly failed: $err")
    }

    system.loadProgram(img)
    EscapementRoutine.initializeTable(system.memory)

    val res = system.run(maxCycles = 50000)
    assertEquals(res, StepResult.Halted)

    val printedLines = system.paper.lines
    assert(printedLines.length >= 10, s"Expected at least 10 lines, got ${printedLines.length}")
    assertEquals(printedLines.head, "The IBM Magnetic Tape")
    assertEquals(printedLines(1), "Selectric Composer is a")
    assert(printedLines.last.contains("automatically"), s"Last line should contain 'automatically', got '${printedLines.last}'")

    // Verify true Selectric Composer right-justification across space bands
    val galleyLines = system.paper.galleyLines
    assert(galleyLines.head.isJustified, "First line should be marked justified")
    assertEquals(galleyLines.head.totalUnits, 140, "First line total width should equal target measure 140 units")
    assert(galleyLines(1).isJustified, "Second line should be marked justified")
    assertEquals(galleyLines(1).totalUnits, 140, "Second line total width should equal target measure 140 units")

    // Check exact space band allocation for first line: 25 deficit across 3 spaces = [13, 12, 12] units
    val firstLineSpaces = galleyLines.head.glyphs.filter(_.ch == ' ').map(_.widthUnits)
    assertEquals(firstLineSpaces, List(13, 12, 12), "Spaces should be expanded to [13, 12, 12] units summing to 37 units")

    // Verify that resetting after EOT Halt rewinds the tape and sweeps track 0 into MT Data Latches
    // so a second run composes the full document again instead of halting on stale 0xFF EndOfBlock latches.
    system.reset()
    EscapementRoutine.initializeTable(system.memory)
    val res2 = system.run(maxCycles = 50000)
    assertEquals(res2, StepResult.Halted)
    assertEquals(system.paper.lines, printedLines)
  }

  test("ContinuousComposerRoutine.setMeasure updates INIT immediates and justifies strictly to 140u, 110u, and 90u") {
    val img = Assembler.assemble(ContinuousComposerRoutine.Source) match {
      case Right(i) => i
      case Left(err) => fail(s"Assembly failed: $err")
    }

    for (measure <- List(140, 110, 90)) {
      val system = new MtscSystem()
      system.loadProgram(img)
      EscapementRoutine.initializeTable(system.memory)
      ContinuousComposerRoutine.setMeasure(system.memory, measure)
      assertEquals(ContinuousComposerRoutine.getMeasure(system.memory), measure)

      val cartridge = TapeCartridge.fromText(TapeCartridge.HistoricalSampleText, "Historical Sample")
      system.loadTape(Device.RightTape, cartridge)

      val res = system.run(maxCycles = 250000)
      assertEquals(res, StepResult.Halted, s"Should halt cleanly at measure=$measure")
      assertEquals(system.memory.readReg(RegId(30)).unsigned, measure, s"R30 must remain $measure after INIT")

      val galleyLines = system.paper.galleyLines.filter(_.text.nonEmpty)
      assert(galleyLines.length >= 10, s"Expected >= 10 non-empty lines at measure=$measure, got ${galleyLines.length}")

      val justifiedLines = galleyLines.filter(_.isJustified)
      assert(justifiedLines.nonEmpty, s"Expected justified lines at measure=$measure")
      for ((line, idx) <- justifiedLines.zipWithIndex) {
        assertEquals(
          line.totalUnits,
          measure,
          s"Justified line #$idx ('${line.text}') at measure=$measure had totalUnits=${line.totalUnits}"
        )
      }
      for ((line, idx) <- galleyLines.zipWithIndex) {
        assert(
          line.totalUnits <= measure,
          s"Line #$idx ('${line.text}') overflowed measure=$measure with totalUnits=${line.totalUnits}"
        )
      }
    }
  }
}
