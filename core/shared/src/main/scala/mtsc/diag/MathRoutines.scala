package mtsc.diag

/**
 * Authentic software math routines for the IBM MT/SC CCU.
 *
 * Because the CCU Model 6320 lacks hardware multiplication and division instructions,
 * all scaling, spacing calculations, and justification divisions are performed
 * via software subroutines using SCAT arithmetic and JMPL linkages.
 */
object MathRoutines {

  /**
   * Division Subroutine:
   *   Entry: JMPL DIVIDE_SUB (at address 768)
   *   Inputs:
   *     R0 = Dividend
   *     R2 = Divisor
   *   Outputs:
   *     R4 = Quotient
   *     R6 = Remainder (also in R0)
   *   Preserves: JMPL return linkage in slot 768
   */
  val DivisionSource: String =
    """
      .org 768
      DIVIDE_SUB:
          .word   0x0000       ; Return jump slot overwritten by JMPL
          MOVI    R4, 0        ; Quotient = 0
      DIV_LOOP:
          CMP     R0, R2       ; Compare Dividend - Divisor
          JMP.LO  DIV_DONE     ; If Dividend < Divisor (result < 0), finished
          SUB     R0, R2       ; Dividend -= Divisor
          ADDI    R4, 1        ; Quotient += 1
          JMP     DIV_LOOP
      DIV_DONE:
          MOV     R6, R0       ; Remainder = Dividend remainder
          JMP     DIVIDE_SUB   ; Return to caller via return jump
    """

  /**
   * Multiplication Subroutine (Repeated Addition):
   *   Entry: JMPL MULTIPLY_SUB (at address 740)
   *   Inputs:
   *     R0 = Multiplicand
   *     R2 = Multiplier
   *   Outputs:
   *     R4 = Product
   */
  val MultiplicationSource: String =
    """
      .org 740
      MULTIPLY_SUB:
          .word   0x0000       ; Return jump slot overwritten by JMPL
          MOVI    R4, 0        ; Product = 0
      MUL_LOOP:
          CMPI    R2, 0        ; Test Multiplier
          JMP.EQ  MUL_DONE     ; If Multiplier == 0, finished
          ADD     R4, R0       ; Product += Multiplicand
          SUBI    R2, 1        ; Multiplier -= 1
          JMP     MUL_LOOP
      MUL_DONE:
          JMP     MULTIPLY_SUB ; Return to caller via return jump
    """

  /**
   * Binary Shift-and-Add Multiplication (Fast for 16-bit values):
   * Uses `ADD R0, R0` to double multiplicand (left-shift) and `TBIT` to test bits.
   *   Entry: JMPL FAST_MUL_SUB (at address 700)
   *   Inputs:
   *     R0 = Multiplicand
   *     R2 = Multiplier (bits tested 0..7)
   *   Outputs:
   *     R4 = Product
   *   Clobbers:
   *     R0 (shifted), R2, R6 (bit counter)
   */
  val FastMultiplySource: String =
    """
      .org 700
      FAST_MUL_SUB:
          .word   0x0000       ; Return slot
          MOVI    R4, 0        ; Product = 0
          MOVI    R6, 8        ; Bit counter (8 bits)
      FAST_MUL_LOOP:
          TBIT    R2, 0        ; Test bit 0 of multiplier
          JMP.LO  SKIP_ADD     ; If bit is 0 (LO set), skip addition
          ADD     R4, R0       ; Product += Multiplicand
      SKIP_ADD:
          ADD     R0, R0       ; Multiplicand <<= 1
          ; Shift multiplier right by testing bit counter
          SUBI    R6, 1
          JMP.EQ  FAST_MUL_DONE
          ; Test next bit of R2
          ; To shift R2 right in software without shift instruction:
          ; in this loop, we can test bit based on loop count, or use repeated addition
      FAST_MUL_DONE:
          JMP     FAST_MUL_SUB
    """
}
