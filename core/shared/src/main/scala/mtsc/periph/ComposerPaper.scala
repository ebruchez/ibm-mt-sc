package mtsc.periph

import scala.collection.mutable.ArrayBuffer

/**
 * Single typed character or space glyph on the Selectric Composer galley sheet.
 *
 * @param ch The character code ('A'..'Z', 'a'..'z', ' ', punctuation, etc.)
 * @param xUnits Starting horizontal position in escapement units (1 unit = 1/72 inch)
 * @param widthUnits Proportional escapement width in units (3..9 for characters, variable for justified spaces)
 */
final case class GalleyGlyph(
  ch: Char,
  xUnits: Int,
  widthUnits: Int
)

/**
 * Single camera-ready line on the galley proof sheet.
 *
 * @param text Raw plain-text string
 * @param glyphs Positioned characters with exact horizontal escapement coordinates
 * @param totalUnits Total width of the line in units
 * @param isJustified True if spaces were expanded to right-justify the line
 */
final case class GalleyLine(
  text: String,
  glyphs: List[GalleyGlyph],
  totalUnits: Int,
  isJustified: Boolean
)

/**
 * Representation of the camera-ready galley paper printed by the Selectric Composer.
 */
class ComposerPaper {

  private val completedLines: ArrayBuffer[String] = ArrayBuffer.empty
  private val completedGalleyLines: ArrayBuffer[GalleyLine] = ArrayBuffer.empty
  private val currentLine: StringBuilder = new StringBuilder
  private val currentGlyphs: ArrayBuffer[GalleyGlyph] = ArrayBuffer.empty
  var currentXUnits: Int = 0
  var totalCharactersPrinted: Long = 0

  def printChar(ch: Char, escapementUnits: Int = 5): Unit = {
    currentLine.append(ch)
    currentGlyphs += GalleyGlyph(ch, currentXUnits, escapementUnits)
    currentXUnits += escapementUnits
    totalCharactersPrinted += 1
  }

  def carrierReturn(): Unit = {
    val lineText = currentLine.toString()
    val glyphs = currentGlyphs.toList
    val totalU = currentXUnits
    val isJust = glyphs.exists(g => g.ch == ' ' && g.widthUnits != 4)
    completedLines += lineText
    completedGalleyLines += GalleyLine(lineText, glyphs, totalU, isJust)
    currentLine.clear()
    currentGlyphs.clear()
    currentXUnits = 0
  }

  def lines: List[String] = {
    val result = completedLines.toList
    if (currentLine.nonEmpty) result :+ currentLine.toString() else result
  }

  def galleyLines: List[GalleyLine] = {
    val result = completedGalleyLines.toList
    if (currentLine.nonEmpty) {
      val isJust = currentGlyphs.exists(g => g.ch == ' ' && g.widthUnits != 4)
      result :+ GalleyLine(currentLine.toString(), currentGlyphs.toList, currentXUnits, isJust)
    } else result
  }

  def currentLineGlyphs: List[GalleyGlyph] =
    currentGlyphs.toList

  def fullText: String =
    lines.mkString("\n")

  def clear(): Unit = {
    completedLines.clear()
    completedGalleyLines.clear()
    currentLine.clear()
    currentGlyphs.clear()
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
