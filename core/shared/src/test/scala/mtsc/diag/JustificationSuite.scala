package mtsc.diag

import mtsc.types.*
import mtsc.memory.CoreMemory
import mtsc.cpu.{Ccu, MockIoBus, StepResult}
import mtsc.asm.Assembler

class JustificationSuite extends munit.FunSuite {

  private def runJustification(targetMeasure: Int, accumulatedUnits: Int, spaceCount: Int): (Int, Int, Int, Int) = {
    val src = JustificationRoutine.JustificationProgramSource
    val res = Assembler.assemble(src)
    assert(res.isRight, s"Assembly failed: $res")

    val mem = CoreMemory.fromImage(res.toOption.get)
    mem.writeReg(RegId(30), Word(targetMeasure))
    mem.writeReg(RegId(20), Word(accumulatedUnits))
    mem.writeReg(RegId(22), Word(spaceCount))

    val io = new MockIoBus()
    val cpu = new Ccu(mem, io)
    val result = cpu.run(maxCycles = 1000)
    assertEquals(result, StepResult.Halted)

    val deficit = mem.readReg(RegId(12)).unsigned
    val extraBase = mem.readReg(RegId(14)).unsigned
    val remainder = mem.readReg(RegId(16)).unsigned
    val success = mem.readReg(RegId(18)).unsigned
    (deficit, extraBase, remainder, success)
  }

  test("Justification: Exact distribution (150 measure, 138 units, 4 spaces)") {
    // Deficit = 12, 12 / 4 = 3 extra units per space, remainder = 0
    val (deficit, extraBase, remainder, success) = runJustification(150, 138, 4)
    assertEquals(deficit, 12, "Deficit should be 12 units")
    assertEquals(extraBase, 3, "Extra base space should be 3 units (total space 7)")
    assertEquals(remainder, 0, "Remainder should be 0")
    assertEquals(success, 1, "Justification should succeed")
  }

  test("Justification: Uneven distribution with remainder (150 measure, 135 units, 4 spaces)") {
    // Deficit = 15, 15 / 4 = 3 with remainder 3
    // First 3 spaces get 4 + 3 + 1 = 8 units, 4th space gets 4 + 3 = 7 units
    val (deficit, extraBase, remainder, success) = runJustification(150, 135, 4)
    assertEquals(deficit, 15, "Deficit should be 15 units")
    assertEquals(extraBase, 3, "Extra base space should be 3 units")
    assertEquals(remainder, 3, "Remainder should be 3")
    assertEquals(success, 1, "Justification should succeed")
  }

  test("Justification: Rejection when space exceeds maximum 9 units") {
    // Deficit = 50, 50 / 4 = 12 extra units -> space would be 16 units > 9
    // Must fail and require hyphenation
    val (_, _, _, success) = runJustification(150, 100, 4)
    assertEquals(success, 0, "Justification should fail when space exceeds 9 units")
  }

  test("Pure Scala model computes correct space band allocations") {
    val res = JustificationRoutine.calculateJustification(
      targetMeasure = 150,
      lineUnitsWithoutLastWord = 135,
      spaceCount = 4,
      defaultSpaceWidth = 4
    )
    assert(res.success)
    assertEquals(res.deficit, 15)
    assertEquals(res.spaceWidths, List(8, 8, 8, 7), "Spaces should be [8, 8, 8, 7] summing to 31")
    // Check total width: 135 - (4 * 4) + 31 = 119 + 31 = 150!
    val total = (135 - 16) + res.spaceWidths.sum
    assertEquals(total, 150, "Total justified line length must equal 150 units exactly")
  }
}
