package mtsc.diag

import mtsc.types.*
import mtsc.memory.CoreMemory
import mtsc.cpu.{Ccu, MockIoBus, StepResult}
import mtsc.asm.Assembler

class MathRoutinesSuite extends munit.FunSuite {

  private def runAssembly(src: String, maxCycles: Long = 1000): Ccu = {
    val res = Assembler.assemble(src)
    assert(res.isRight, s"Assembly failed: $res")
    val mem = CoreMemory.fromImage(res.toOption.get)
    val io = new MockIoBus()
    val cpu = new Ccu(mem, io)
    val result = cpu.run(maxCycles)
    assert(result == StepResult.Normal || result == StepResult.Halted, s"Execution ended with: $result")
    cpu
  }

  test("Division subroutine: 50 / 6 = 8 remainder 2") {
    val src = s"""
      .org 546
      MAIN:
          MOVI    R0, 50             ; Dividend = 50
          MOVI    R2, 6              ; Divisor = 6
          JMPL    DIVIDE_SUB         ; Call division subroutine
          ; Upon return: R4 should be 8, R6 should be 2
      DONE:
          JMP     DONE

      ${MathRoutines.DivisionSource}
    """
    val cpu = runAssembly(src)
    assertEquals(cpu.memory.readReg(RegId(4)).unsigned, 8, "Quotient should be 8")
    assertEquals(cpu.memory.readReg(RegId(6)).unsigned, 2, "Remainder should be 2")
  }

  test("Division subroutine: exact division 45 / 9 = 5 remainder 0") {
    val src = s"""
      .org 546
      MAIN:
          MOVI    R0, 45             ; Dividend = 45
          MOVI    R2, 9              ; Divisor = 9
          JMPL    DIVIDE_SUB
      DONE:
          JMP     DONE

      ${MathRoutines.DivisionSource}
    """
    val cpu = runAssembly(src)
    assertEquals(cpu.memory.readReg(RegId(4)).unsigned, 5, "Quotient should be 5")
    assertEquals(cpu.memory.readReg(RegId(6)).unsigned, 0, "Remainder should be 0")
  }

  test("Division subroutine: dividend < divisor 3 / 7 = 0 remainder 3") {
    val src = s"""
      .org 546
      MAIN:
          MOVI    R0, 3              ; Dividend = 3
          MOVI    R2, 7              ; Divisor = 7
          JMPL    DIVIDE_SUB
      DONE:
          JMP     DONE

      ${MathRoutines.DivisionSource}
    """
    val cpu = runAssembly(src)
    assertEquals(cpu.memory.readReg(RegId(4)).unsigned, 0, "Quotient should be 0")
    assertEquals(cpu.memory.readReg(RegId(6)).unsigned, 3, "Remainder should be 3")
  }

  test("Multiplication subroutine: 7 * 9 = 63") {
    val src = s"""
      .org 546
      MAIN:
          MOVI    R0, 7              ; Multiplicand = 7
          MOVI    R2, 9              ; Multiplier = 9
          JMPL    MULTIPLY_SUB
      DONE:
          JMP     DONE

      ${MathRoutines.MultiplicationSource}
    """
    val cpu = runAssembly(src)
    assertEquals(cpu.memory.readReg(RegId(4)).unsigned, 63, "Product should be 63")
  }

  test("Multiplication subroutine: multiply by zero 12 * 0 = 0") {
    val src = s"""
      .org 546
      MAIN:
          MOVI    R0, 12
          MOVI    R2, 0
          JMPL    MULTIPLY_SUB
      DONE:
          JMP     DONE

      ${MathRoutines.MultiplicationSource}
    """
    val cpu = runAssembly(src)
    assertEquals(cpu.memory.readReg(RegId(4)).unsigned, 0, "Product should be 0")
  }
}
