package mtsc.asm

import mtsc.types.*
import mtsc.isa.*

class AssemblerSuite extends munit.FunSuite {

  test("assemble directives: .org, .word, .byte, .space") {
    val src =
      """
        .org 0x100
        .word 0x1234
        .byte 0x55
        .space 2
        .byte 0xAA
      """
    val res = Assembler.assemble(src)
    assert(res.isRight, s"Assembly failed: $res")
    val img = res.toOption.get
    assertEquals(img.readWord(Address(0x100)), Word(0x1234))
    assertEquals(img.readByte(Address(0x102)), 0x55)
    assertEquals(img.readByte(Address(0x103)), 0x00)
    assertEquals(img.readByte(Address(0x104)), 0x00)
    assertEquals(img.readByte(Address(0x105)), 0xAA)
  }

  test("assemble the authentic 3-step bootstrap loader") {
    val src =
      """
        ; IBM MT/SC 3-Step Program Tape Bootstrap Loader
        .org 546
        LOAD_LOOP:
            IN      TAPE_R, R4       ; Read tape character into R4
            MOVB    [R8]++, R4       ; Indirect store into memory [R8], bump pointer
            JMP     LOAD_LOOP        ; Loop until entire program is loaded
      """
    val res = Assembler.assemble(src)
    assert(res.isRight, s"Assembly failed: $res")
    val img = res.toOption.get

    // Verify word at 546 is IN TAPE_R, R4
    val insn1 = Decoder.decode(img.readWord(Address(546)))
    assertEquals(insn1, Right(Instruction.Input(Device.RightTape, RegId(4))))

    // Verify word at 548 is MOVB [R8]++, R4
    val insn2 = Decoder.decode(img.readWord(Address(548)))
    assertEquals(insn2, Right(Instruction.ByteIndirect(ArithOp.Transfer, RegId(8), pIndirect = true, RegId(4), qIndirect = false, bump = true)))

    // Verify word at 550 is JMP 546
    val insn3 = Decoder.decode(img.readWord(Address(550)))
    assertEquals(insn3, Right(Instruction.Branch(Condition.Unconditional, link = false, Address(546))))
  }

  test("assemble proportional font lookup routine from manual") {
    val src =
      """
        .org 748
        MOVI    R8, 255          ; Set base pointer low byte
        ADDI    R8, 61           ; Target buffer address: 255 + 61 = 316
        IN      TAPE_R, R4       ; Input character from tape
        MOVB    [R8], R4         ; Store into input buffer
        ADDI    R4, 64           ; Add table offset 64 to character code
        MOVB    R10, [R4]        ; Look up proportional escapement units
        ADD     R20, R10         ; Accumulate line measure in R20
        CMP     R20, R22         ; Compare with maximum measure
        JMP.LO  748              ; Continue reading line if measure not exceeded
      """
    val res = Assembler.assemble(src)
    assert(res.isRight, s"Assembly failed: $res")
  }

  test("assemble subroutine with Jump and Link") {
    val src =
      """
        .org 600
        MAIN:
            JMPL    SUBROUTINE
            JMP     MAIN

        .org 820
        SUBROUTINE:
            .word   0x0000       ; Slot for dynamic return jump written by JMPL
            ADD     R2, R3
            JMP     SUBROUTINE   ; Return to caller via return jump at 820
      """
    val res = Assembler.assemble(src)
    assert(res.isRight, s"Assembly failed: $res")
    val img = res.toOption.get

    // 600: JMPL 820
    val jmpl = Decoder.decode(img.readWord(Address(600)))
    assertEquals(jmpl, Right(Instruction.Branch(Condition.Unconditional, link = true, Address(820))))
  }

  test("incremental branch with forward and backward jumps") {
    val src =
      """
        .org 546
        START:
            IJMP.ON   R0, 3, FORWARD_TARGET
            NOP_STEP:
            ADDI      R2, 1
        FORWARD_TARGET:
            IJMP.OFF  R1, 7, START
      """
    val res = Assembler.assemble(src)
    assert(res.isRight, s"Assembly failed: $res")
  }

  test("assemble reference guide examples") {
    val examples = List(
      // Example 1: Bootstrap loader
      """
        .org 546
        BOOTSTRAP_LOADER:
            IN      TAPE_R, R4
            MOVB    [R8]++, R4
            JMP     BOOTSTRAP_LOADER
      """,
      // Example 2: Subroutine linkage JMPL
      """
        .org 546
        MAIN:
            MOVI    R0, 25
            JMPL    MULTIPLY_BY_FOUR
            CMPI    R0, 100
            JMP.EQ  SUCCESS
        FAIL_HALT:
            JMP     FAIL_HALT
        SUCCESS:
            MOVI    R30, 0xAA
        DONE:
            JMP     DONE

        .org 600
        MULTIPLY_BY_FOUR:
            .word   0x0000
            ADD     R0, R0
            ADD     R0, R0
            JMP     MULTIPLY_BY_FOUR
      """,
      // Example 3: Escapement table lookup
      """
        .org 546
        START_MEASURE:
            MOVI    R8, 60
            SBIT    R8, 8
            MOVI    R20, 0
            MOVI    R30, 150
        READ_LOOP:
            MOVB    R4, [R8]++
            CMPI    R4, 0
            JMP.EQ  LINE_COMPLETE
            ADDI    R4, 64
            MOVB    R10, [R4]
            ADD     R20, R10
            CMP     R30, R20
            JMP.LO  LINE_EXCEEDED
            JMP     READ_LOOP
        LINE_EXCEEDED:
            MOVI    R24, 1
            JMP     HALT
        LINE_COMPLETE:
            MOVI    R24, 0
        HALT:
            JMP     HALT
      """,
      // Example 4: Division subroutine
      """
        .org 768
        DIVIDE_SUB:
            .word   0x0000
            MOVI    R4, 0
        DIV_LOOP:
            CMP     R0, R2
            JMP.LO  DIV_DONE
            SUB     R0, R2
            ADDI    R4, 1
            JMP     DIV_LOOP
        DIV_DONE:
            MOV     R6, R0
            JMP     DIVIDE_SUB
      """,
      // Example 5: Justification
      """
        .org 546
        JUSTIFY_CALC:
            MOV     R12, R30
            SUB     R12, R20
            JMP.LO  JUSTIFY_FAIL
            CMPI    R22, 0
            JMP.EQ  JUSTIFY_FAIL
            MOV     R0, R12
            MOV     R2, R22
            JMPL    DIVIDE_SUB
            MOV     R14, R4
            MOV     R16, R6
            MOVI    R18, 4
            ADD     R18, R14
            CMPI    R18, 9
            JMP.HI  JUSTIFY_FAIL
            CMPI    R18, 3
            JMP.LO  JUSTIFY_FAIL
            MOVI    R18, 1
            JMP     JUSTIFY_DONE
        JUSTIFY_FAIL:
            MOVI    R18, 0
        JUSTIFY_DONE:
            JMP     JUSTIFY_DONE

        .org 768
        DIVIDE_SUB:
            .word   0x0000
            MOVI    R4, 0
        DIV_LOOP:
            CMP     R0, R2
            JMP.LO  DIV_FIN
            SUB     R0, R2
            ADDI    R4, 1
            JMP     DIV_LOOP
        DIV_FIN:
            MOV     R6, R0
            JMP     DIVIDE_SUB
      """,
      // Example 6: Multiplex print
      """
        .org 546
        PRINT_JUSTIFIED_LINE:
            MOVI    R0, 198
            OUTM    TSLTR, [R0]
        NEXT_TASK:
            ADDI    R26, 1
        IDLE_LOOP:
            JMP     IDLE_LOOP
      """
    )

    for ((src, idx) <- examples.zipWithIndex) {
      val res = Assembler.assemble(src)
      assert(res.isRight, s"Example ${idx + 1} failed to assemble: $res")
    }
  }
}
