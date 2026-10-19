package mtsc.diag

import mtsc.types.*
import mtsc.memory.CoreMemory
import mtsc.cpu.{Ccu, MockIoBus, StepResult}

class DiagnosticProgramsSuite extends munit.FunSuite {

  test("ALU Diagnostic passes all self-tests") {
    val img = DiagnosticPrograms.AluDiagnostic.assemble()
    val mem = CoreMemory.fromImage(img)
    val io = new MockIoBus()
    val cpu = new Ccu(mem, io)

    val res = cpu.run(maxCycles = 500)
    assertEquals(res, StepResult.Halted)
    assertEquals(mem.readReg(RegId(30)).unsigned, 0xAA, "R30 should contain PASS indicator 0xAA")
  }

  test("Nested Subroutines Diagnostic passes and returns correct value") {
    val img = DiagnosticPrograms.NestedSubroutinesDiagnostic.assemble()
    val mem = CoreMemory.fromImage(img)
    val io = new MockIoBus()
    val cpu = new Ccu(mem, io)

    val res = cpu.run(maxCycles = 500)
    assertEquals(res, StepResult.Halted)
    assertEquals(mem.readReg(RegId(0)).unsigned, 35, "R0 should contain 35")
    assertEquals(mem.readReg(RegId(30)).unsigned, 0xAA, "R30 should contain PASS indicator 0xAA")
  }

  test("Tape Bootstrap Loader reads until EOF character (0xFF)") {
    val img = DiagnosticPrograms.TapeBootstrapLoader.assemble()
    val mem = CoreMemory.fromImage(img)
    val io = new MockIoBus()

    // Initialize R8 pointer to destination 200
    mem.writeReg(RegId(8), Word(200))

    // Stream 4 bytes: 'M', 'T', 'S', 0xFF (EOF)
    io.queueInput(Device.RightTape, 'M'.toInt, 'T'.toInt, 'S'.toInt, 0xFF)

    val cpu = new Ccu(mem, io)
    val res = cpu.run(maxCycles = 500)
    assertEquals(res, StepResult.Halted)

    assertEquals(mem.readByte(Address(200)), 'M'.toInt)
    assertEquals(mem.readByte(Address(201)), 'T'.toInt)
    assertEquals(mem.readByte(Address(202)), 'S'.toInt)
    assertEquals(mem.readByte(Address(203)), 0xFF)
    assertEquals(mem.readReg(RegId(6)).unsigned, 1, "R6 completion indicator should be 1")
  }

  test("Escapement and Justification Demo processes sentence") {
    val img = DiagnosticPrograms.EscapementAndJustifyDemo.assemble()
    val mem = CoreMemory.fromImage(img)
    EscapementRoutine.initializeTable(mem)

    // Write text at 316: "The logic in CCU"
    // 'T' (7), 'h' (6), 'e' (5), ' ' (4), 'l' (3), 'o' (6), 'g' (6), 'i' (3), 'c' (5), ' ' (4),
    // 'i' (3), 'n' (6), ' ' (4), 'C' (7), 'C' (7), 'U' (7)
    // Sum = 7+6+5+4 + 3+6+6+3+5+4 + 3+6+4 + 7+7+7 = 22 + 27 + 13 + 21 = 83 units
    // Spaces = 3
    // Line measure = 150
    // Deficit = 150 - 83 = 67
    // Extra per space = 67 / 3 = 22
    // Remainder = 67 % 3 = 1
    val text = "The logic in CCU"
    for (i <- 0 until text.length) {
      mem.writeByte(Address(316 + i), text.charAt(i).toInt)
    }
    mem.writeByte(Address(316 + text.length), 0) // Terminator

    val io = new MockIoBus()
    val cpu = new Ccu(mem, io)
    val res = cpu.run(maxCycles = 1500)
    assertEquals(res, StepResult.Halted)

    assertEquals(mem.readReg(RegId(20)).unsigned, 83, "Total accumulated units should be 83")
    assertEquals(mem.readReg(RegId(22)).unsigned, 3, "Space count should be 3")
    assertEquals(mem.readReg(RegId(12)).unsigned, 67, "Deficit should be 67")
    assertEquals(mem.readReg(RegId(14)).unsigned, 22, "Quotient extra per space should be 22")
    assertEquals(mem.readReg(RegId(16)).unsigned, 1, "Remainder should be 1")
    assertEquals(mem.readReg(RegId(28)).unsigned, 1, "Success flag should be 1")
  }
}
