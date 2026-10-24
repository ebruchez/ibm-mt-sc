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
   * Address of the `IN R4, TAPE_R` instruction within the microprogram (byte 558).
   * Bit 8 (the `m` bit) selects Right Tape (0) vs Left Tape (1).
   */
  val TapeInputInstructionAddress: Address = Address(558)

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
          MOVI    R28, 110         ; Threshold for line wrap: 110 units
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

          ; Space encountered! Check if accumulated units reached threshold
          CMP     R20, R28
          JMP.LO  ACCUM_SPACE
          ; Line is full! Break line here (drop trailing space)
          JMP     JUSTIFY_LINE

      ACCUM_SPACE:
          ADDI    R22, 1           ; Space count++
          ; Fall through to PROCESS_CHAR for space

      PROCESS_CHAR:
          ; Store character into input buffer at [R8]++
          MOVB    [R8]++, R4

          ; Lookup character escapement width from table at R4 + 64
          MOV     R2, R4
          ADDI    R2, 64
          MOVB    R10, [R2]
          ADD     R20, R10         ; Total units += width
          JMP     READ_CHAR_LOOP

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
          MOVI    R14, 0
          MOVI    R16, 0
          JMP     TRANSFER_OUTPUT

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
          JMP     LINE_COMPLETED

      LINE_COMPLETED:
          ; Loop back to process next line
          JMP     NEW_LINE

      ALL_DONE:
      IDLE_HALT:
          JMP     IDLE_HALT

      ${MathRoutines.DivisionSource}
    """
}
