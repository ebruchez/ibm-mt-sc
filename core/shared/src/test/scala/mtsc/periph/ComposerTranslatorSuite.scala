package mtsc.periph

import mtsc.types.*
import mtsc.cpu.StepResult
import mtsc.asm.Assembler

class ComposerTranslatorSuite extends munit.FunSuite {

  test("ComposerTranslator records characters and carrier return on galley paper") {
    val translator = new ComposerTranslator()

    translator.write('H'.toInt, multiplex = false)
    translator.write('i'.toInt, multiplex = false)
    translator.write(0x0D, multiplex = false) // Carrier Return
    translator.write('O'.toInt, multiplex = false)
    translator.write('K'.toInt, multiplex = false)

    val lines = translator.paper.lines
    assertEquals(lines.length, 2)
    assertEquals(lines(0), "Hi")
    assertEquals(lines(1), "OK")
    assertEquals(translator.printCount, 5L)
  }

  test("ComposerTranslator mechanical cycle causes pipeline stall on direct output") {
    val system = new MtscSystem()
    system.translator.cycleDelay = 4

    val src =
      """
        .org 546
        MOVI R4, 65          ; 'A'
        OUT  R4, TRANSLATOR  ; Prints 'A', starts 4-cycle mechanical delay
        MOVI R6, 66          ; 'B'
        OUT  R6, TRANSLATOR  ; Should stall until cycle completes
      DONE:
        JMP DONE
      """
    system.loadProgram(Assembler.assemble(src).toOption.get)

    assertEquals(system.step(), StepResult.Normal) // MOVI R4, 65
    assertEquals(system.step(), StepResult.Normal) // OUT R4 -> starts delay
    assertEquals(system.step(), StepResult.Normal) // MOVI R6, 66 (foreground executes while translator is busy)
    assertEquals(system.step(), StepResult.Stalled) // OUT R6 -> stalls because translator is busy!

    assert(system.cpu.state.inhibit)
    assertEquals(system.memory.psar, Address(552))

    // Step out remaining delay cycles
    for (_ <- 0 until 3) {
      system.step()
    }

    // Now translator is ready, OUT R6 executes!
    assertEquals(system.step(), StepResult.Normal)
    assertEquals(system.paper.lines, List("AB"))
  }
}
