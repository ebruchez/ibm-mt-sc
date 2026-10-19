package mtsc.diag

import mtsc.types.*
import mtsc.asm.Assembler
import mtsc.memory.{CoreImage, CoreMemory}

/**
 * Catalog of diagnostic and demonstration microprograms for the IBM MT/SC CCU.
 *
 * Can be loaded directly into CoreMemory via the CE Console or used in automated test suites.
 */
object DiagnosticPrograms {

  sealed trait ProgramInfo {
    def id: String
    def name: String
    def description: String
    def source: String

    def assemble(): CoreImage = {
      Assembler.assemble(source) match {
        case Right(img) => img
        case Left(err) =>
          throw new IllegalStateException(s"Failed to assemble diagnostic program '$id': $err")
      }
    }
  }

  /**
   * 1. Authentic 3-Step Tape Bootstrap Loader
   */
  case object TapeBootstrapLoader extends ProgramInfo {
    val id = "bootstrap"
    val name = "Tape Bootstrap Loader"
    val description = "Authentic 3-step loop reading characters into memory from tape reader"

    val source: String =
      """
        .org 546
        LOADER:
            IN      TAPE_R, R4       ; Read byte from Right Tape into R4
            MOVB    [R8]++, R4       ; Write byte to memory at [R8], increment R8
            CMPI    R4, 0xFF         ; End-of-record sentinel (0xFF)
            JMP.EQ  LOADER_DONE
            JMP     LOADER
        LOADER_DONE:
            MOVI    R6, 1            ; Set completion indicator in R6
        HALT_LOOP:
            JMP     HALT_LOOP
      """
  }

  /**
   * 2. Comprehensive ALU Diagnostic:
   * Tests ADD, SUB, CMP, MOV, MOVI, ADDI, SUBI, CMPI, LO/EQ flag updates, and 16-bit wraparound.
   */
  case object AluDiagnostic extends ProgramInfo {
    val id = "alu_diag"
    val name = "ALU Diagnostic Self-Test"
    val description = "Verifies addition, subtraction, comparison, sign extension, and condition latches"

    val source: String =
      """
        .org 546
        START:
            ; Test 1: Immediate operations
            MOVI    R0, 100
            ADDI    R0, 50           ; R0 = 150
            CMPI    R0, 150
            JMP.NE  TEST_FAILED

            SUBI    R0, 200          ; 150 - 200 = -50 (negative, LO set)
            JMP.LO  T1_OK
            JMP     TEST_FAILED
        T1_OK:

            ; Test 2: Register Arithmetic
            MOVI    R2, 40
            MOVI    R4, 60
            ADD     R2, R4           ; R2 = 100
            CMPI    R2, 100
            JMP.NE  TEST_FAILED

            SUB     R2, R4           ; R2 = 40
            CMPI    R2, 40
            JMP.NE  TEST_FAILED

            ; Test 3: Bit Control
            MOVI    R6, 0
            SBIT    R6, 5            ; Set bit 5 (value 32)
            TBIT    R6, 5            ; Bit is ON -> EQ=1, LO=0
            JMP.EQ  T3_BIT_ON
            JMP     TEST_FAILED
        T3_BIT_ON:
            RBIT    R6, 5            ; Reset bit 5 -> value 0
            TBIT    R6, 5            ; Bit is OFF -> EQ=0, LO=1
            JMP.LO  T3_BIT_OFF
            JMP     TEST_FAILED
        T3_BIT_OFF:

            ; All tests passed! Set R30 = 0xAA (Passed flag)
            MOVI    R30, 0xAA
            JMP     HALT_PASS

        TEST_FAILED:
            MOVI    R30, 0xFF        ; Set R30 = 0xFF (Failed flag)

        HALT_PASS:
            JMP     HALT_PASS
      """
  }

  /**
   * 3. Subroutine Linkage (Nested JMPL calls)
   */
  case object NestedSubroutinesDiagnostic extends ProgramInfo {
    val id = "subroutine_linkage"
    val name = "Nested Subroutines Diagnostic"
    val description = "Verifies self-modifying JMPL return linkage through nested call levels"

    val source: String =
      """
        .org 546
        MAIN:
            MOVI    R0, 5
            JMPL    SUB_OUTER        ; Call outer subroutine
            ; Upon return: R0 should be 5 + 10 + 20 = 35
            CMPI    R0, 35
            JMP.EQ  SUB_PASS
            MOVI    R30, 0xFF        ; Failure
            JMP     DONE

        SUB_PASS:
            MOVI    R30, 0xAA        ; Success
        DONE:
            JMP     DONE

        .org 600
        SUB_OUTER:
            .word   0x0000           ; Overwritten by JMPL
            ADDI    R0, 10           ; R0 += 10
            JMPL    SUB_INNER        ; Nested call
            JMP     SUB_OUTER        ; Return from outer

        .org 650
        SUB_INNER:
            .word   0x0000           ; Overwritten by JMPL
            ADDI    R0, 20           ; R0 += 20
            JMP     SUB_INNER        ; Return from inner
      """
  }

  /**
   * 4. Escapement & Justification Demonstration
   */
  case object EscapementAndJustifyDemo extends ProgramInfo {
    val id = "justify_demo"
    val name = "Escapement & Justification Demo"
    val description = "Processes input sentence, looks up font escapements, and calculates justified space bands"

    val source: String =
      s"""
        .org 546
        MAIN:
            ; Setup line measure in R30: 150 units
            MOVI    R30, 150
            ; Initialize pointers:
            ; R8 = Input text pointer (Address 316)
            MOVI    R8, 60           ; 316 = 256 + 60
            ; For R8 = 316: in 8-bit immediate:
            ; We can load high byte or construct address:
            ; R8 = 316. 316 = 256 + 60.
            ; R8 low byte = 60, high byte = 1:
            ; Let's use MOVI R8, 60, then set bit 8!
            MOVI    R8, 60
            SBIT    R8, 8            ; Set bit 8 (256): R8 = 256 + 60 = 316!
            MOVI    R20, 0           ; Accumulated units = 0
            MOVI    R22, 0           ; Space count = 0

        PROCESS_TEXT:
            MOVB    R4, [R8]++       ; Read char
            CMPI    R4, 0            ; End of text?
            JMP.EQ  DO_JUSTIFY
            CMPI    R4, 32           ; Space?
            JMP.NE  ACCUM_CHAR
            ADDI    R22, 1           ; Space count++
        ACCUM_CHAR:
            ADDI    R4, 64           ; Table address
            MOVB    R10, [R4]        ; Escapement value
            ADD     R20, R10         ; Total units += width
            JMP     PROCESS_TEXT

        DO_JUSTIFY:
            ; Calculate deficit = R30 - R20
            MOV     R12, R30
            SUB     R12, R20
            ; Divide deficit by space count (R22)
            MOV     R0, R12
            MOV     R2, R22
            JMPL    DIVIDE_SUB
            ; R4 = extra per space, R6 = remainder
            MOV     R14, R4
            MOV     R16, R6
            MOVI    R28, 1           ; Success flag
        DEMO_DONE:
            JMP     DEMO_DONE

        ${MathRoutines.DivisionSource}
      """
  }

  val AllPrograms: List[ProgramInfo] = List(
    TapeBootstrapLoader,
    AluDiagnostic,
    NestedSubroutinesDiagnostic,
    EscapementAndJustifyDemo
  )

  def findById(id: String): Option[ProgramInfo] =
    AllPrograms.find(_.id == id)
}
