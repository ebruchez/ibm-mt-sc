package mtsc.cpu

import mtsc.types.*
import mtsc.isa.*
import mtsc.memory.CoreMemory
import mtsc.asm.Assembler

class CcuSuite extends munit.FunSuite {

  private def createCpu(src: String): (Ccu, MockIoBus) = {
    val res = Assembler.assemble(src)
    assert(res.isRight, s"Assembly failed: $res")
    val img = res.toOption.get
    val mem = CoreMemory.fromImage(img)
    val io = new MockIoBus()
    val cpu = new Ccu(mem, io)
    (cpu, io)
  }

  test("Immediate Arithmetic and condition latches") {
    val src =
      """
        .org 546
        MOVI    R2, 10
        ADDI    R2, 15
        CMPI    R2, 25
        JMP.EQ  NEXT
        MOVI    R4, 99           ; Should be skipped
      NEXT:
        MOVI    R4, 42
        SUBI    R4, 50           ; 42 - 50 = -8 (negative, LO set)
      """
    val (cpu, _) = createCpu(src)

    // Execute step by step
    assertEquals(cpu.step(), StepResult.Normal) // MOVI R2, 10
    assertEquals(cpu.memory.readReg(RegId(2)).unsigned, 10)

    assertEquals(cpu.step(), StepResult.Normal) // ADDI R2, 15 -> 25
    assertEquals(cpu.memory.readReg(RegId(2)).unsigned, 25)
    assert(!cpu.state.low)
    assert(!cpu.state.equal)
    assert(cpu.state.isHi)

    assertEquals(cpu.step(), StepResult.Normal) // CMPI R2, 25 -> equal
    assert(cpu.state.equal)
    assert(!cpu.state.low)

    assertEquals(cpu.step(), StepResult.Normal) // JMP.EQ NEXT (taken)
    assertEquals(cpu.memory.psar, Address(556))

    assertEquals(cpu.step(), StepResult.Normal) // MOVI R4, 42
    assertEquals(cpu.memory.readReg(RegId(4)).unsigned, 42)

    assertEquals(cpu.step(), StepResult.Normal) // SUBI R4, 50
    assert(cpu.state.low, "LO should be set on negative result")
    assert(!cpu.state.equal)
  }

  test("Register Direct Arithmetic: ADD, SUB, CMP, MOV") {
    val src =
      """
        .org 546
        MOVI    R2, 100
        MOVI    R6, 40
        MOV     R4, R2           ; R4 = 100
        ADD     R4, R6           ; R4 = 140
        SUB     R4, R6           ; R4 = 100
        CMP     R4, R2           ; 100 - 100 = 0 (EQ set, R4 preserved)
      DONE:
        JMP     DONE
      """
    val (cpu, _) = createCpu(src)
    cpu.run(maxCycles = 10)

    assertEquals(cpu.memory.readReg(RegId(4)).unsigned, 100)
    assert(cpu.state.equal)
    assert(!cpu.state.low)
    assertEquals(cpu.memory.bWord, Word.Zero, "B-Word must be cleared after instruction")
  }

  test("Bit Control: Set, Reset, Test") {
    val src =
      """
        .org 546
        MOVI    R2, 0
        SBIT    R2, 3            ; Set bit 3 -> 0x08
        TBIT    R2, 3            ; Test bit 3 -> bit on (EQ=1, LO=0)
        RBIT    R2, 3            ; Reset bit 3 -> 0
        TBIT    R2, 3            ; Test bit 3 -> bit off (EQ=0, LO=1)
      DONE:
        JMP     DONE
      """
    val (cpu, _) = createCpu(src)

    cpu.step() // MOVI R2, 0
    cpu.step() // SBIT R2, 3
    assertEquals(cpu.memory.readReg(RegId(2)).unsigned, 8)

    cpu.step() // TBIT R2, 3
    assert(cpu.state.equal && !cpu.state.low, "Bit on should set EQ=1, LO=0")

    cpu.step() // RBIT R2, 3
    assertEquals(cpu.memory.readReg(RegId(2)).unsigned, 0)

    cpu.step() // TBIT R2, 3
    assert(!cpu.state.equal && cpu.state.low, "Bit off should set EQ=0, LO=1")
  }

  test("Byte Indirect with Auto-Bump (++) pointer arithmetic") {
    val src =
      """
        .org 546
        MOVI    R8, 200          ; Dest pointer: 200
        MOVI    R4, 65           ; Character 'A' (65)
        MOVB    [R8]++, R4       ; Write 'A' to [200], R8 becomes 201
        MOVI    R4, 66           ; Character 'B' (66)
        MOVB    [R8]++, R4       ; Write 'B' to [201], R8 becomes 202
      DONE:
        JMP     DONE
      """
    val (cpu, _) = createCpu(src)
    cpu.run(maxCycles = 10)

    assertEquals(cpu.memory.readByte(Address(200)), 65)
    assertEquals(cpu.memory.readByte(Address(201)), 66)
    assertEquals(cpu.memory.readReg(RegId(8)).unsigned, 202)
  }

  test("Subroutine linkage with Jump with Link (JMPL)") {
    val src =
      """
        .org 546
        MAIN:
            MOVI    R2, 10
            JMPL    SUBROUTINE       ; Calls subroutine, writing return jump at 600
            MOVI    R4, 99           ; Returned successfully!
            JMP     DONE

        .org 600
        SUBROUTINE:
            .word   0x0000           ; Dynamically overwritten by JMPL with JMP 550
            ADDI    R2, 5            ; Subroutine body: R2 = 10 + 5 = 15
            JMP     SUBROUTINE       ; Returns to caller by jumping to 600

        .org 620
        DONE:
            MOVI    R6, 1
        HALT:
            JMP     HALT
      """
    val (cpu, _) = createCpu(src)
    cpu.run(maxCycles = 20)

    assertEquals(cpu.memory.readReg(RegId(2)).unsigned, 15)
    assertEquals(cpu.memory.readReg(RegId(4)).unsigned, 99)
    assertEquals(cpu.memory.readReg(RegId(6)).unsigned, 1)

    // Verify that the first word of SUBROUTINE (at 600) was modified to JMP 550
    val returnJumpWord = cpu.memory.readWord(Address(600))
    val decoded = Decoder.decode(returnJumpWord)
    assertEquals(decoded, Right(Instruction.Branch(Condition.Unconditional, link = false, Address(550))))
  }

  test("Run authentic 3-step tape bootstrap loader loop") {
    val src =
      """
        .org 546
        LOADER:
            IN      TAPE_R, R4       ; 546: Read byte from right tape
            MOVB    [R8]++, R4       ; 548: Store byte at [R8], increment R8
            CMPI    R8, 103          ; 550: Check if loaded 3 bytes (100, 101, 102)
            JMP.NE  LOADER           ; 552: Loop if not finished
            MOVI    R6, 1            ; 554: Finished flag
        DONE:
            JMP     DONE             ; 556: Park CPU
      """
    val (cpu, io) = createCpu(src)

    // Set destination pointer R8 to address 100
    cpu.memory.writeReg(RegId(8), Word(100))

    // Queue 3 bytes on simulated tape reader: 'H' (72), 'E' (69), 'Y' (89)
    io.queueInput(Device.RightTape, 72, 69, 89)

    cpu.run(maxCycles = 50)

    // Verify core memory contents at 100, 101, 102
    assertEquals(cpu.memory.readByte(Address(100)), 72)
    assertEquals(cpu.memory.readByte(Address(101)), 69)
    assertEquals(cpu.memory.readByte(Address(102)), 89)
    assertEquals(cpu.memory.readReg(RegId(8)).unsigned, 103)
    assertEquals(cpu.memory.readReg(RegId(6)).unsigned, 1)
  }

  test("I/O pipeline stall (Inhibit) when device is busy") {
    val src =
      """
        .org 546
        IN      TAPE_R, R4
        MOVI    R6, 123
      """
    val (cpu, io) = createCpu(src)

    // Initially no data available: device busy
    assertEquals(cpu.step(), StepResult.Stalled)
    assert(cpu.state.inhibit)
    assertEquals(cpu.memory.psar, Address(546), "PSAR should not advance when stalled")

    // Now supply data
    io.queueInput(Device.RightTape, 42)

    assertEquals(cpu.step(), StepResult.Normal)
    assert(!cpu.state.inhibit)
    assertEquals(cpu.memory.readReg(RegId(4)).unsigned, 42)
    assertEquals(cpu.memory.psar, Address(548))

    assertEquals(cpu.step(), StepResult.Normal)
    assertEquals(cpu.memory.readReg(RegId(6)).unsigned, 123)
  }
}
