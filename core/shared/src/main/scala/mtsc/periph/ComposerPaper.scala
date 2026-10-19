package mtsc.periph

import scala.collection.mutable.ArrayBuffer

/**
 * Representation of the camera-ready galley paper printed by the Selectric Composer.
 */
class ComposerPaper {

  private val completedLines: ArrayBuffer[String] = ArrayBuffer.empty
  private val currentLine: StringBuilder = new StringBuilder
  var currentXUnits: Int = 0
  var totalCharactersPrinted: Long = 0

  def printChar(ch: Char, escapementUnits: Int = 5): Unit = {
    currentLine.append(ch)
    currentXUnits += escapementUnits
    totalCharactersPrinted += 1
  }

  def carrierReturn(): Unit = {
    completedLines += currentLine.toString()
    currentLine.clear()
    currentXUnits = 0
  }

  def lines: List[String] = {
    val result = completedLines.toList
    if (currentLine.nonEmpty) result :+ currentLine.toString() else result
  }

  def fullText: String =
    lines.mkString("\n")

  def clear(): Unit = {
    completedLines.clear()
    currentLine.clear()
    currentXUnits = 0
    totalCharactersPrinted = 0
  }

  /**
   * Formats the printed galley page with visual margins and line numbers.
   */
  def renderGalley(columnWidthUnits: Int = 150): String = {
    val sb = new StringBuilder
    sb.append("┌─ IBM SELECTRIC COMPOSER ─ CAMERA-READY GALLEY PROOF ────────────┐\n")
    if (lines.isEmpty) {
      sb.append("│ [Blank page - no output printed yet]                            │\n")
    } else {
      for ((line, idx) <- lines.zipWithIndex) {
        val num = f"${idx + 1}%02d"
        val truncatedLine = if (line.length > 58) line.take(55) + "..." else line
        sb.append(f"│ $num: $truncatedLine%-58s │\n")
      }
    }
    sb.append("└─────────────────────────────────────────────────────────────────┘\n")
    sb.toString()
  }
}
