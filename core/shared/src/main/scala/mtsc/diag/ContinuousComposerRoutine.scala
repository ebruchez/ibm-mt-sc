package mtsc.diag

import mtsc.types.*
import mtsc.memory.CoreMemory

/**
 * Continuous Multi-Line Document Composition Microprogram.
 *
 * Continuously reads tape input, wraps lines at proportional measure threshold,
 * computes line deficit, runs 16-bit division across space bands,
 * outputs camera-ready justified lines to the Selectric Composer,
 * and loops until End-of-Block sentinel.
 */
object ContinuousComposerRoutine {

  /**
   * Address of the `MOVI R28, imm` wrap-threshold instruction at `INIT` (byte 546).
   */
  val WrapThresholdInstructionAddress: Address = Address(546)

  /**
   * Address of the `MOVI R30, imm` target-measure instruction at `INIT` (byte 548).
   */
  val TargetMeasureInstructionAddress: Address = Address(548)

  /**
   * Address of the `IN R4, TAPE_R` instruction within the microprogram (byte 558).
   * Bit 8 (the `m` bit) selects Right Tape (0) vs Left Tape (1).
   */
  val TapeInputInstructionAddress: Address = Address(558)

  /**
   * Updates both the working registers (`R28` wrap threshold, `R30` target measure)
   * and the `INIT` immediate instructions at addresses 546 (`MOVI R28, imm`) and 548 (`MOVI R30, imm`)
   * when `ContinuousComposerRoutine` is loaded in `memory`.
   */
  def setMeasure(memory: CoreMemory, targetMeasure: Int): Unit = {
    val clampedMeasure = math.max(30, math.min(254, targetMeasure))
    val wrapThreshold = math.max(20, clampedMeasure - 6)
    memory.writeReg(RegId(28), Word(wrapThreshold))
    memory.writeReg(RegId(30), Word(clampedMeasure))
    // In little-endian CoreMemory, MOVI R28 (0xFE) has its opcode at 547 and immediate at 546;
    // MOVI R30 (0xFF) has its opcode at 549 and immediate at 548.
    if (
      memory.readByte(WrapThresholdInstructionAddress + 1) == 0xfe &&
      memory.readByte(TargetMeasureInstructionAddress + 1) == 0xff
    ) {
      memory.writeByte(WrapThresholdInstructionAddress, wrapThreshold)
      memory.writeByte(TargetMeasureInstructionAddress, clampedMeasure)
    }
  }

  def getMeasure(memory: CoreMemory): Int = {
    if (memory.readByte(TargetMeasureInstructionAddress + 1) == 0xff) {
      memory.readByte(TargetMeasureInstructionAddress)
    } else {
      memory.readReg(RegId(30)).unsigned
    }
  }

  /**
   * Patches the input instruction at address 558 to read from either RightTape or LeftTape.
   */
  def setInputStation(memory: CoreMemory, station: Device): Unit = {
    require(station == Device.RightTape || station == Device.LeftTape, "Station must be RightTape or LeftTape")
    val cur = memory.readWord(TapeInputInstructionAddress)
    val updated = if (station == Device.LeftTape) cur.withBit(8, true) else cur.withBit(8, false)
    memory.writeWord(TapeInputInstructionAddress, updated)
  }

  def getInputStation(memory: CoreMemory): Device = {
    val word = memory.readWord(TapeInputInstructionAddress)
    if (word.bit(8)) Device.LeftTape else Device.RightTape
  }

  val Source: String =
    s"""
      .org 546
      INIT:
          MOVI    R28, 134         ; Threshold for immediate space wrap: 134 units (measure - 6)
          MOVI    R30, 140         ; Target measure: 140 units

      NEW_LINE:
          ; Reset input pointer R8 = 316 (256 + 60)
          MOVI    R8, 60
          SBIT    R8, 8
          MOVI    R20, 0           ; Accumulated units = 0
          MOVI    R22, 0           ; Space count = 0

      READ_CHAR_LOOP:
          IN      R4, TAPE_R       ; Read character from Right Tape
          CMPI    R4, 0            ; Skip leader feed code
          JMP.EQ  READ_CHAR_LOOP
          CMPI    R4, 255          ; End of tape?
          JMP.EQ  CHECK_EOF_LINE
          CMPI    R4, 13           ; Carrier Return / newline?
          JMP.EQ  LINE_ENDED_CR

          CMPI    R4, 32           ; Space?
          JMP.NE  PROCESS_CHAR

          ; Space encountered! Check if accumulated units reached immediate threshold
          CMP     R20, R28
          JMP.LO  ACCUM_SPACE
          ; Line is full! Break line here (drop trailing space)
          MOVI    R18, 0           ; No partial word carry
          JMP     JUSTIFY_LINE

      ACCUM_SPACE:
          MOV     R24, R8          ; Save buffer pointer at this space (word break point)
          MOV     R26, R20         ; Save accumulated units before this space
          ADDI    R22, 1           ; Space count++
          ; Fall through to PROCESS_CHAR for space

      PROCESS_CHAR:
          ; Store character into input buffer at [R8]++
          MOVB    [R8]++, R4

          ; Lookup character escapement width from table at R4 + 64
          ADDI    R4, 64
          MOVB    R10, [R4]
          ADD     R20, R10         ; Total units += width

          ; If this character pushed accumulated units past target measure R30, wrap at last space
          CMP     R30, R20
          JMP.LO  WORD_OVERFLOW
          JMP     READ_CHAR_LOOP

      WORD_OVERFLOW:
          ; If no space has been seen on this line yet (R22 == 0), keep reading single long word
          CMPI    R22, 0
          JMP.EQ  READ_CHAR_LOOP
          ; Rewind line to the last space (R24) and carry [R24+1 .. R8-1] to the next line
          MOV     R18, R8          ; Save end of partial word in R18
          MOV     R8, R24          ; Truncate current line at the space
          MOV     R20, R26         ; Restore accumulated units prior to the space
          SUBI    R22, 1           ; Exclude the dropped space from space count
          JMP     JUSTIFY_LINE

      LINE_ENDED_CR:
          ; Carrier return (paragraph break). If line has no text, print blank line
          CMPI    R20, 0
          JMP.EQ  PRINT_BLANK_LINE
          JMP     PRINT_RAW_LINE

      CHECK_EOF_LINE:
          CMPI    R20, 0
          JMP.EQ  ALL_DONE
          JMP     PRINT_RAW_LINE

      JUSTIFY_LINE:
          ; Deficit = Target (R30) - Accumulated (R20)
          MOV     R12, R30
          SUB     R12, R20
          JMP.LO  DEFICIT_ZERO

          ; Check if we have spaces to divide across
          CMPI    R22, 0
          JMP.EQ  PRINT_RAW_LINE

          ; Divide Deficit (R12) by Space Count (R22)
          MOV     R0, R12
          MOV     R2, R22
          JMPL    DIVIDE_SUB
          MOV     R14, R4          ; Extra units per space
          MOV     R16, R6          ; Remainder units
          JMP     TRANSFER_OUTPUT

      DEFICIT_ZERO:
      PRINT_RAW_LINE:
          MOVI    R14, 0
          MOVI    R16, 0

      TRANSFER_OUTPUT:
          ; Mark end of input buffer with 255
          MOVI    R4, 255
          MOVB    [R8], R4

          ; Reset R8 = 316 (input buffer) and R6 = 198 (output buffer)
          MOVI    R8, 60
          SBIT    R8, 8
          MOVI    R6, 198

      TRANSFER_LOOP:
          MOVB    R4, [R8]++
          CMPI    R4, 255
          JMP.EQ  TRANSFER_DONE
          CMPI    R4, 32           ; Space?
          JMP.NE  TRANSFER_CHAR

          ; Check if this line is justified (R14 > 0 or R16 > 0)
          MOV     R2, R14
          ADD     R2, R16
          CMPI    R2, 0
          JMP.EQ  TRANSFER_CHAR    ; Unjustified: keep standard space 32

          ; Justified line: calculate space code (128 + 4 + R14 + (1 if R16 > 0))
          MOVI    R2, 132          ; 128 + 4 = 132
          ADD     R2, R14
          CMPI    R16, 0
          JMP.EQ  WRITE_SPACE
          ADDI    R2, 1            ; Add 1 remainder unit
          SUBI    R16, 1           ; Remainder count--
      WRITE_SPACE:
          MOV     R4, R2
      TRANSFER_CHAR:
          MOVB    [R6]++, R4
          JMP     TRANSFER_LOOP

      TRANSFER_DONE:
          ; Append Carrier Return (13) and Sentinel (255) to output buffer
          MOVI    R4, 13
          MOVB    [R6]++, R4
          MOVI    R4, 255
          MOVB    [R6]++, R4

          ; Output line characters to Selectric Translator
          MOVI    R6, 198

      PRINT_CHAR_LOOP:
          MOVB    R4, [R6]++
          CMPI    R4, 255
          JMP.EQ  LINE_COMPLETED
          OUT     TRANSLATOR, R4
          JMP     PRINT_CHAR_LOOP

      PRINT_BLANK_LINE:
          MOVI    R4, 13
          OUT     TRANSLATOR, R4

      LINE_COMPLETED:
          ; If a partial word overflowed the previous line (R18 != 0),
          ; copy its characters [R24+1 .. R18-1] to the start of the new line buffer.
          CMPI    R18, 0
          JMP.EQ  NEW_LINE
          MOVI    R8, 60
          SBIT    R8, 8
          MOVI    R20, 0
          MOVI    R22, 0
          MOV     R6, R24
          ADDI    R6, 1            ; Advance past the sentinel at [R24]
      CARRY_LOOP:
          CMP     R6, R18
          JMP.EQ  CARRY_DONE
          MOVB    R4, [R6]++
          MOVB    [R8]++, R4
          ADDI    R4, 64
          MOVB    R10, [R4]
          ADD     R20, R10
          JMP     CARRY_LOOP
      CARRY_DONE:
          MOVI    R18, 0
          JMP     READ_CHAR_LOOP

      ALL_DONE:
      IDLE_HALT:
          JMP     IDLE_HALT

      ${MathRoutines.DivisionSource}
    """
}
