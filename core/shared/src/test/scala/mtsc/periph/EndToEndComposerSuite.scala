package mtsc.periph

import mtsc.types.*
import mtsc.cpu.StepResult
import mtsc.diag.{EscapementRoutine, MathRoutines}
import mtsc.asm.Assembler

class EndToEndComposerSuite extends munit.FunSuite {

  test("End-to-End: Tape read -> Escapement lookup -> Justification -> Composer print") {
    val system = new MtscSystem()
    EscapementRoutine.initializeTable(system.memory)

    // Mount tape cartridge with text
    val inputText = "The CCU logic"
    val cartridge = TapeCartridge.fromText(inputText)
    system.loadTape(Device.RightTape, cartridge)

    // Assembly microprogram for complete end-to-end operation
    val src =
      s"""
        .org 546
        BOOTSTRAP:
            ; Setup R8 = 316 (Input block address: 256 + 60)
            MOVI    R8, 60
            SBIT    R8, 8

        READ_TAPE_LOOP:
            IN      R4, TAPE_R       ; Read character from tape
            MOVB    [R8]++, R4       ; Store at [R8]
            CMPI    R4, 0            ; Skip leader feed code
            JMP.EQ  SKIP_FEED
            CMPI    R4, 0xFF         ; End of block?
            JMP.EQ  START_COMPOSITION
        SKIP_FEED:
            JMP     READ_TAPE_LOOP

        START_COMPOSITION:
            ; Reset R8 back to 316
            MOVI    R8, 60
            SBIT    R8, 8

            ; Setup R20 = 0 (accumulated units), R22 = 0 (spaces), R30 = 100 (target measure)
            MOVI    R20, 0
            MOVI    R22, 0
            MOVI    R30, 100

        COMPOSITION_LOOP:
            MOVB    R4, [R8]++
            CMPI    R4, 0xFF         ; End of input?
            JMP.EQ  DO_JUSTIFY
            CMPI    R4, 0            ; Skip feed codes
            JMP.EQ  COMPOSITION_LOOP

            CMPI    R4, 32           ; Space?
            JMP.NE  LOOKUP_CHAR
            ADDI    R22, 1           ; Space count++

        LOOKUP_CHAR:
            ADDI    R4, 64           ; Table address
            MOVB    R10, [R4]        ; Escapement width
            ADD     R20, R10         ; Total line units += width
            JMP     COMPOSITION_LOOP

        DO_JUSTIFY:
            ; Deficit = Target (R30) - Accumulated (R20)
            MOV     R12, R30
            SUB     R12, R20

            ; Divide Deficit (R12) by Spaces (R22)
            MOV     R0, R12
            MOV     R2, R22
            JMPL    DIVIDE_SUB
            ; R4 = extra per space, R6 = remainder
            MOV     R14, R4
            MOV     R16, R6

            ; Transfer Input Block (316..) to Output Block (198..)
            ; R8 = Input ptr (316), R6 = Output ptr (198)
            MOVI    R8, 60
            SBIT    R8, 8            ; R8 = 316
            MOVI    R6, 198          ; R6 = 198

        TRANSFER_LOOP:
            MOVB    R4, [R8]++
            CMPI    R4, 0xFF
            JMP.EQ  TRANSFER_DONE
            CMPI    R4, 0            ; Skip 0 feed code
            JMP.EQ  TRANSFER_LOOP
            MOVB    [R6]++, R4       ; Store in output block
            JMP     TRANSFER_LOOP

        TRANSFER_DONE:
            ; Append Carrier Return (13) and Halt (255)
            MOVI    R4, 13
            MOVB    [R6]++, R4
            MOVI    R4, 255
            MOVB    [R6]++, R4

            ; Initiate background printing from Output Block (198) via R0
            MOVI    R0, 198
            OUTM    [R0], TRANSLATOR

        IDLE_LOOP:
            JMP     IDLE_LOOP

        ${MathRoutines.DivisionSource}
      """

    val img = Assembler.assemble(src) match {
      case Right(i) => i
      case Left(err) => fail(s"Assembly failed: $err")
    }

    system.loadProgram(img)
    // Re-initialize escapement table after loadProgram reset
    EscapementRoutine.initializeTable(system.memory)

    val result = system.run(maxCycles = 5000)
    assertEquals(result, StepResult.Halted)

    // Verify printed paper output contains the tape line
    assertEquals(system.paper.lines, List("The CCU logic"))

    // Verify units and justification values:
    // Text: "The CCU logic"
    // 'T'(7) + 'h'(6) + 'e'(5) + ' '(4) + 'C'(7) + 'C'(7) + 'U'(7) + ' '(4) + 'l'(3) + 'o'(6) + 'g'(6) + 'i'(3) + 'c'(5)
    // Total = 70 units
    // Measure = 100 units
    // Deficit = 30 units across 2 spaces
    // Extra per space = 15 units, remainder = 0
    assertEquals(system.memory.readReg(RegId(20)).unsigned, 70, "Accumulated units should be 70")
    assertEquals(system.memory.readReg(RegId(22)).unsigned, 2, "Space count should be 2")
    assertEquals(system.memory.readReg(RegId(12)).unsigned, 30, "Deficit should be 30")
    assertEquals(system.memory.readReg(RegId(14)).unsigned, 15, "Extra per space should be 15")
    assertEquals(system.memory.readReg(RegId(16)).unsigned, 0, "Remainder should be 0")
  }
}
