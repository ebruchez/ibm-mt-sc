package mtsc.diag

import mtsc.types.*
import mtsc.memory.CoreMemory

/**
 * Line Justification Algorithm for the IBM MT/SC Selectric Composer.
 *
 * Implements the authentic calculation described in IBM Manual 241-5429-0 (pages 4-5)
 * and the 1968 IBM Journal of R&D paper (page 388):
 *
 * 1. Line Measure (e.g. 150 units) is set in R30.
 * 2. Words and inter-word spaces (default 4 units) are accumulated.
 * 3. When a word exceeds the measure, that word is dropped (backed up)
 *    and the last trailing space is subtracted from the count.
 * 4. The Deficit (Target Measure - Line Units) is computed.
 * 5. The Deficit is divided across the remaining inter-word space bands:
 *      Quotient = Deficit / SpaceCount
 *      Remainder = Deficit % SpaceCount
 * 6. The first `Remainder` spaces are allocated (4 + Quotient + 1) units,
 *    and the remaining spaces are allocated (4 + Quotient) units.
 * 7. If resulting space width is within limits (3 to 9 units), justification
 *    succeeds and output line is transferred to Output Block (bytes 198..315).
 */
object JustificationRoutine {

  val InputBlockAddress: Address = Address(316)
  val OutputBlockAddress: Address = Address(198)

  final case class JustifyResult(
    success: Boolean,
    lineUnits: Int,
    targetMeasure: Int,
    deficit: Int,
    spaceCount: Int,
    baseSpaceUnits: Int,
    remainderUnits: Int,
    spaceWidths: List[Int]
  )

  /**
   * Reference implementation in pure Scala for validation.
   */
  def calculateJustification(
    targetMeasure: Int,
    lineUnitsWithoutLastWord: Int,
    spaceCount: Int,
    defaultSpaceWidth: Int = 4,
    minSpace: Int = 3,
    maxSpace: Int = 9
  ): JustifyResult = {
    if (spaceCount <= 0) {
      return JustifyResult(
        success = false,
        lineUnits = lineUnitsWithoutLastWord,
        targetMeasure = targetMeasure,
        deficit = targetMeasure - lineUnitsWithoutLastWord,
        spaceCount = 0,
        baseSpaceUnits = defaultSpaceWidth,
        remainderUnits = 0,
        spaceWidths = Nil
      )
    }

    val deficit = targetMeasure - lineUnitsWithoutLastWord
    val extraPerSpace = deficit / spaceCount
    val remainder = deficit % spaceCount
    val baseSpace = defaultSpaceWidth + extraPerSpace

    val widths = (0 until spaceCount).map { i =>
      if (i < remainder) baseSpace + 1 else baseSpace
    }.toList

    val success = widths.forall(w => w >= minSpace && w <= maxSpace)

    JustifyResult(
      success = success,
      lineUnits = lineUnitsWithoutLastWord,
      targetMeasure = targetMeasure,
      deficit = deficit,
      spaceCount = spaceCount,
      baseSpaceUnits = baseSpace,
      remainderUnits = remainder,
      spaceWidths = widths
    )
  }

  /**
   * Complete CCU Assembly source for justification calculation:
   *
   * Inputs:
   *   R30 = Target Line Measure (e.g. 150)
   *   R20 = Accumulated Line Units (e.g. 138)
   *   R22 = Inter-word Space Count (e.g. 4)
   *
   * Outputs:
   *   R12 = Deficit (Target - Line Units, e.g. 12)
   *   R14 = Base Extra Units per space (Quotient = Deficit / Spaces, e.g. 3)
   *   R16 = Remainder Units (Remainder = Deficit % Spaces, e.g. 0)
   *   R18 = Success flag (1 if valid space width 3..9, 0 if out of range)
   */
  val JustificationProgramSource: String =
    s"""
      .org 546
      JUSTIFY_CALC:
          ; Step 1: Calculate Deficit = Target (R30) - Accumulated (R20)
          MOV     R12, R30           ; R12 = Target Measure
          SUB     R12, R20           ; R12 = Deficit
          JMP.LO  JUSTIFY_FAIL       ; If negative, line already exceeds measure!

          ; Step 2: Check space count in R22
          CMPI    R22, 0
          JMP.EQ  JUSTIFY_FAIL       ; Cannot justify with 0 spaces

          ; Step 3: Divide Deficit (R12) by Space Count (R22)
          ; Set up call to DIVIDE_SUB: Dividend in R0, Divisor in R2
          MOV     R0, R12
          MOV     R2, R22
          JMPL    DIVIDE_SUB         ; Call division subroutine
          ; Returned: R4 = Quotient (extra per space), R6 = Remainder

          MOV     R14, R4            ; R14 = Base extra units per space
          MOV     R16, R6            ; R16 = Remainder units to distribute

          ; Step 4: Validate total space width (Default 4 + Quotient) is <= 9
          MOVI    R18, 4
          ADD     R18, R14           ; R18 = 4 + Quotient
          CMPI    R18, 9             ; Compare SpaceWidth - 9
          ; If SpaceWidth > 9 (diff > 0, i.e. HI / Not Low and Not Equal), fails max width
          JMP.HI  JUSTIFY_FAIL
          CMPI    R18, 3             ; Compare SpaceWidth - 3
          JMP.LO  JUSTIFY_FAIL       ; If SpaceWidth < 3, fails min width

          ; Success! Set R18 = 1
          MOVI    R18, 1
          JMP     JUSTIFY_DONE

      JUSTIFY_FAIL:
          MOVI    R18, 0             ; Set R18 = 0 (Failure)

      JUSTIFY_DONE:
          JMP     JUSTIFY_DONE

      ${MathRoutines.DivisionSource}
    """
}
