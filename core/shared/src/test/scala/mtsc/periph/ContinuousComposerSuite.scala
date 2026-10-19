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
  }
}
