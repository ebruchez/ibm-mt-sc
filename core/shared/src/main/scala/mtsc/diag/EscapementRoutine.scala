package mtsc.diag

import mtsc.types.*
import mtsc.memory.CoreMemory

/**
 * Proportional Escapement Table & Lookup Algorithm for the IBM MT/SC.
 *
 * In the MT/SC, bytes 64 through 197 in core memory contain the escapement values
 * (unit widths, typically 3 to 9 units) for character codes.
 *
 * When reading text:
 * 1. The character code is received in R4.
 * 2. An immediate add of 64 (`ADDI R4, 64`) calculates the table address.
 * 3. An indirect byte transfer (`MOVB R10, [R4]`) retrieves the escapement units into R10.
 * 4. The units in R10 are accumulated into the line length counter (`ADD R20, R10`).
 * 5. An arithmetic compare (`CMP R30, R20`) checks against the selected line measure.
 */
object EscapementRoutine {

  /** Base address of the character escapement table in core storage */
  val TableBaseAddress: Address = Address(64)
  val TableEndAddress: Address = Address(197)
  val TableSize: Int = 134

  /**
   * Default proportional unit widths (based on IBM Selectric Composer Press Roman font):
   * Values range from 3 (narrowest, e.g. 'i', '.') to 9 (widest, e.g. 'M', 'W').
   * Default interword space is 4 units.
   */
  def defaultUnitWidth(ch: Char): Int = ch match {
    // 3 units: narrow characters, punctuation
    case 'i' | 'l' | 'j' | 'I' | ' ' | '.' | ',' | '\'' | ':' | ';' | '!' | '|' | '`' => 3
    // 4 units: inter-word space default, slender lowercase
    case 'f' | 'r' | 't' | '-' | '(' | ')' | '[' | ']' | '"' => 4
    // 5 units: medium lowercase, numerals
    case 'a' | 'c' | 'e' | 's' | 'v' | 'x' | 'z' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' => 5
    // 6 units: wide lowercase, narrower uppercase
    case 'b' | 'd' | 'g' | 'h' | 'k' | 'n' | 'o' | 'p' | 'q' | 'u' | 'y' | 'J' | 'L' | 'S' => 6
    // 7 units: standard uppercase
    case 'A' | 'B' | 'C' | 'D' | 'E' | 'F' | 'G' | 'H' | 'K' | 'N' | 'O' | 'P' | 'R' | 'T' | 'U' | 'V' | 'X' | 'Y' | 'Z' => 7
    // 8 units: wide uppercase
    case 'w' | 'Q' | '&' | '%' | '$' => 8
    // 9 units: widest uppercase, em-dash, 'M', 'W'
    case 'm' | 'M' | 'W' | '@' | '#' | '_' => 9
    // Default fallback
    case _ => 5
  }

  /**
   * Populates the 64..197 table in CoreMemory with proportional escapement values
   * indexed by ASCII character code (for ASCII chars 0..127).
   */
  def initializeTable(memory: CoreMemory): Unit = {
    for (ascii <- 0 until 128) {
      val width = defaultUnitWidth(ascii.toChar)
      val addr = TableBaseAddress + ascii
      if (addr.unsigned <= TableEndAddress.unsigned) {
        memory.writeByte(addr, width)
      }
    }
    // Set standard inter-word space (code 32 = ' ') specifically to 4 units
    memory.writeByte(TableBaseAddress + 32, 4)
  }

  /**
   * Assembly routine for escapement accumulation loop:
   *   - Input text stream is stored at `InputBuffer` (Address 316)
   *   - Target line measure is in R30 (e.g. 150 units)
   *   - Accumulated units are kept in R20
   *   - Current character pointer in R8
   *   - R4 is used for character code / table address lookup
   *   - R10 receives the escapement value
   *   - When accumulated >= measure, branches to LINE_FILLED
   */
  val LookupLoopSource: String =
    """
      .org 546
      ESCAPEMENT_LOOP:
          MOVB    R4, [R8]++         ; Read character into R4, advance buffer pointer
          CMPI    R4, 0              ; Check for null terminator (end of input)
          JMP.EQ  END_OF_INPUT
          CMPI    R4, 32             ; Check if character is space (ASCII 32)
          JMP.NE  LOOKUP_CHAR
          ADDI    R22, 1             ; Increment space counter in R22
      LOOKUP_CHAR:
          ADDI    R4, 64             ; R4 = 64 + char_code (table byte address)
          MOVB    R10, [R4]          ; R10 = escapement unit width from table
          ADD     R20, R10           ; R20 += escapement units
          CMP     R30, R20           ; Compare target measure (R30) - accumulated (R20)
          JMP.LO  LINE_FILLED        ; If R30 < R20 (LO set), line measure exceeded!
          JMP     ESCAPEMENT_LOOP    ; Next character

      LINE_FILLED:
          ; Measure exceeded: R20 has total, R22 has space count
          MOVI    R24, 1             ; Set flag: line filled
          JMP     DONE

      END_OF_INPUT:
          MOVI    R24, 0             ; Set flag: end of input without exceeding measure
      DONE:
          JMP     DONE
    """
}
