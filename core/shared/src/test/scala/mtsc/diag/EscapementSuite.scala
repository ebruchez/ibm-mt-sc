package mtsc.diag

import mtsc.types.*
import mtsc.memory.CoreMemory
import mtsc.cpu.{Ccu, MockIoBus, StepResult}
import mtsc.asm.Assembler

class EscapementSuite extends munit.FunSuite {

  test("Escapement table initialization verifies proportional unit widths") {
    val mem = CoreMemory.empty()
    EscapementRoutine.initializeTable(mem)

    // Verify key characters from IBM Selectric Composer manual
    // 'T' -> 7 units at byte 64 + 84 = 148 (or ASCII 84)
    assertEquals(mem.readByte(Address(64 + 'T'.toInt)), 7, "'T' escapement should be 7 units")
    // 'h' -> 6 units at byte 64 + 104 = 168
    assertEquals(mem.readByte(Address(64 + 'h'.toInt)), 6, "'h' escapement should be 6 units")
    // 'e' -> 5 units at byte 64 + 101 = 165
    assertEquals(mem.readByte(Address(64 + 'e'.toInt)), 5, "'e' escapement should be 5 units")
    // ' ' -> 4 units at byte 64 + 32 = 96
    assertEquals(mem.readByte(Address(64 + ' '.toInt)), 4, "space escapement should be 4 units")
    // 'i' -> 3 units
    assertEquals(mem.readByte(Address(64 + 'i'.toInt)), 3, "'i' escapement should be 3 units")
    // 'M' -> 9 units
    assertEquals(mem.readByte(Address(64 + 'M'.toInt)), 9, "'M' escapement should be 9 units")
  }

  test("LookupLoop: calculates 'The ' sentence prefix matching manual page 4") {
    // According to IBM manual page 4:
    // 'T' (7) + 'h' (6) + 'e' (5) + ' ' (4) = 22 units
    val src = EscapementRoutine.LookupLoopSource
    val res = Assembler.assemble(src)
    assert(res.isRight, s"Assembly failed: $res")

    val mem = CoreMemory.fromImage(res.toOption.get)
    EscapementRoutine.initializeTable(mem)

    // Write "The " (ASCII 84, 104, 101, 32) followed by null terminator (0) at InputBuffer (316)
    val inputAddr = Address(316)
    mem.writeByte(inputAddr, 'T'.toInt)
    mem.writeByte(inputAddr + 1, 'h'.toInt)
    mem.writeByte(inputAddr + 2, 'e'.toInt)
    mem.writeByte(inputAddr + 3, ' '.toInt)
    mem.writeByte(inputAddr + 4, 0) // Null terminator

    // Initialize registers:
    // R8 = input pointer (316)
    // R20 = 0 (accumulated units)
    // R22 = 0 (spaces)
    // R30 = 150 (line measure)
    mem.writeReg(RegId(8), Word(316))
    mem.writeReg(RegId(20), Word(0))
    mem.writeReg(RegId(22), Word(0))
    mem.writeReg(RegId(30), Word(150))

    val io = new MockIoBus()
    val cpu = new Ccu(mem, io)
    val result = cpu.run(maxCycles = 500)
    assertEquals(result, StepResult.Halted)

    // Verify accumulated units: 7 + 6 + 5 + 4 = 22
    assertEquals(mem.readReg(RegId(20)).unsigned, 22, "Accumulated line measure should be 22 units")
    // Verify space count: 1 space
    assertEquals(mem.readReg(RegId(22)).unsigned, 1, "Space count should be 1")
    // Verify flag: R24 = 0 (reached end of input without exceeding 150)
    assertEquals(mem.readReg(RegId(24)).unsigned, 0, "Line filled flag should be 0 (within measure)")
  }

  test("LookupLoop: detects line filled when exceeding target measure") {
    val src = EscapementRoutine.LookupLoopSource
    val res = Assembler.assemble(src)
    assert(res.isRight, s"Assembly failed: $res")

    val mem = CoreMemory.fromImage(res.toOption.get)
    EscapementRoutine.initializeTable(mem)

    // Target measure is small: 15 units
    // Text is "The " (22 units) -> should exceed 15 on 'e' (18 units)
    val inputAddr = Address(316)
    mem.writeByte(inputAddr, 'T'.toInt)
    mem.writeByte(inputAddr + 1, 'h'.toInt)
    mem.writeByte(inputAddr + 2, 'e'.toInt)
    mem.writeByte(inputAddr + 3, ' '.toInt)
    mem.writeByte(inputAddr + 4, 0)

    mem.writeReg(RegId(8), Word(316))
    mem.writeReg(RegId(20), Word(0))
    mem.writeReg(RegId(22), Word(0))
    mem.writeReg(RegId(30), Word(15)) // Small measure: 15 units

    val io = new MockIoBus()
    val cpu = new Ccu(mem, io)
    val result = cpu.run(maxCycles = 500)
    assertEquals(result, StepResult.Halted)

    // Line filled flag R24 should be 1
    assertEquals(mem.readReg(RegId(24)).unsigned, 1, "Line filled flag should be 1")
    // Units should be 18 (7 + 6 + 5), which exceeded 15
    assertEquals(mem.readReg(RegId(20)).unsigned, 18)
  }
}
