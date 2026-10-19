package mtsc.periph

import mtsc.types.*
import mtsc.cpu.StepResult
import mtsc.asm.Assembler

class MultiplexOutputSuite extends munit.FunSuite {

  test("Multiplex mode interleaves background printing with foreground execution") {
    val system = new MtscSystem()
    // Configure translator with 2 cycles mechanical delay per character
    system.translator.cycleDelay = 2

    // Set up output buffer at Address 200: "IBM" followed by Halt Code (0xFF)
    val outAddr = Address(200)
    system.memory.writeByte(outAddr, 'I'.toInt)
    system.memory.writeByte(outAddr + 1, 'B'.toInt)
    system.memory.writeByte(outAddr + 2, 'M'.toInt)
    system.memory.writeByte(outAddr + 3, 0xFF) // Halt code

    // Set R0 (multiplex pointer) to 200
    system.memory.writeReg(RegId(0), Word(200))

    val src =
      """
        .org 546
        ; Start multiplex printing from [R0]
        OUTM    [R0], TRANSLATOR

        ; Foreground program does counting work while background printing proceeds!
        MOVI    R2, 0
      COUNT_LOOP:
        ADDI    R2, 1
        CMPI    R2, 20
        JMP.NE  COUNT_LOOP
      DONE:
        JMP     DONE
      """
    system.loadProgram(Assembler.assemble(src).toOption.get)

    // Re-initialize R0 pointer to 200 (since loadProgram reset memory)
    system.memory.writeByte(outAddr, 'I'.toInt)
    system.memory.writeByte(outAddr + 1, 'B'.toInt)
    system.memory.writeByte(outAddr + 2, 'M'.toInt)
    system.memory.writeByte(outAddr + 3, 0xFF)
    system.memory.writeReg(RegId(0), Word(200))

    // Step 1: OUTM [R0], TRANSLATOR
    val step1 = system.step()
    assertEquals(step1, StepResult.Normal)
    assert(system.cpu.state.multiplex, "Multiplex latch must be set")

    // Run system until halt
    val result = system.run(maxCycles = 500)
    assertEquals(result, StepResult.Halted)

    // Verify background printing completed all characters: "IBM"
    assertEquals(system.paper.lines, List("IBM"))
    // Verify R0 pointer was stepped to 204
    assertEquals(system.memory.readReg(RegId(0)).unsigned, 204)
    // Verify Multiplex latch was reset by Halt Code (0xFF)
    assert(!system.cpu.state.multiplex, "Multiplex latch must reset on Halt Code 0xFF")
    // Verify foreground program completed its counting work: R2 = 20
    assertEquals(system.memory.readReg(RegId(2)).unsigned, 20)
  }
}
