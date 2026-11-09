package mtsc.periph

import scala.collection.mutable.ArrayBuffer
import mtsc.types.Fmt

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
  // Each completed line stores a single packed `Array[Int]` where entry `i` encodes `(xUnits << 16) | (widthUnits & 0xffff)`.
  // This avoids allocating per-character `GalleyGlyph` and `List[::]` objects on the heap during composition.
  private val completedCoords: ArrayBuffer[Array[Int]] = ArrayBuffer.empty
  private val currentLine: StringBuilder = new StringBuilder
  private var currentCoords: Array[Int] = new Array[Int](128)
  private var currentCoordsLen: Int = 0
  var currentXUnits: Int = 0
  var totalCharactersPrinted: Long = 0

  /** Optional cap on retained completed lines (0 = unlimited; set e.g. to 16 on microcontroller targets). */
  var maxRetainedLines: Int = 0

  /** Whether to record per-character horizontal escapement coordinates (`xUnits`, `widthUnits`). */
  var recordGlyphs: Boolean = true

  def printChar(ch: Char, escapementUnits: Int = 5): Unit = {
    currentLine.append(ch)
    if (recordGlyphs) {
      if (currentCoordsLen >= currentCoords.length) {
        val next = new Array[Int](currentCoords.length * 2)
        System.arraycopy(currentCoords, 0, next, 0, currentCoordsLen)
        currentCoords = next
      }
      currentCoords(currentCoordsLen) = ((currentXUnits & 0xffff) << 16) | (escapementUnits & 0xffff)
      currentCoordsLen += 1
    }
    currentXUnits += escapementUnits
    totalCharactersPrinted += 1
  }

  def carrierReturn(): Unit = {
    val lineText = currentLine.toString()
    completedLines += lineText
    if (maxRetainedLines > 0 && completedLines.length > maxRetainedLines) {
      val drop = math.max(8, completedLines.length - maxRetainedLines)
      completedLines.remove(0, math.min(drop, completedLines.length))
    }
    if (recordGlyphs) {
      val lineCoords = new Array[Int](currentCoordsLen)
      System.arraycopy(currentCoords, 0, lineCoords, 0, currentCoordsLen)
      completedCoords += lineCoords
      if (maxRetainedLines > 0 && completedCoords.length > maxRetainedLines) {
        val drop = math.max(8, completedCoords.length - maxRetainedLines)
        completedCoords.remove(0, math.min(drop, completedCoords.length))
      }
      currentCoordsLen = 0
    }
    currentLine.clear()
    currentXUnits = 0
  }

  private def buildGalleyLine(lineText: String, coords: Array[Int], count: Int): GalleyLine = {
    val len = math.min(lineText.length, count)
    val lb = List.newBuilder[GalleyGlyph]
    var totalU = 0
    var isJust = false
    var i = 0
    while (i < len) {
      val ch = lineText.charAt(i)
      val packed = coords(i)
      val xU = (packed >>> 16) & 0xffff
      val wU = packed & 0xffff
      if (ch == ' ' && wU != 4) {
        isJust = true
      }
      totalU = xU + wU
      lb += GalleyGlyph(ch, xU, wU)
      i += 1
    }
    GalleyLine(lineText, lb.result(), totalU, isJust)
  }

  def completedLinesCount: Int = completedLines.length
  def completedLineAt(idx: Int): String = completedLines(idx)
  def completedGalleyLinesCount: Int = completedCoords.length
  def completedLineGlyphCount(lineIdx: Int): Int =
    math.min(completedLines(lineIdx).length, completedCoords(lineIdx).length)
  def completedLineGlyphXUnits(lineIdx: Int, glyphIdx: Int): Int =
    (completedCoords(lineIdx)(glyphIdx) >>> 16) & 0xffff
  def completedGalleyLineAt(idx: Int): GalleyLine =
    buildGalleyLine(completedLines(idx), completedCoords(idx), completedCoords(idx).length)
  def currentGlyphsCount: Int = math.min(currentLine.length, currentCoordsLen)
  def currentGlyphCharAt(idx: Int): Char = currentLine.charAt(idx)
  def currentGlyphXUnitsAt(idx: Int): Int = (currentCoords(idx) >>> 16) & 0xffff
  def currentGlyphAt(idx: Int): GalleyGlyph = {
    val packed = currentCoords(idx)
    GalleyGlyph(currentLine.charAt(idx), (packed >>> 16) & 0xffff, packed & 0xffff)
  }
  def currentLineBuilder: StringBuilder = currentLine
  def lineCount: Int = completedLines.length + (if (currentLine.nonEmpty) 1 else 0)

  def lines: List[String] = {
    val result = completedLines.toList
    if (currentLine.nonEmpty) result :+ currentLine.toString() else result
  }

  def galleyLines: List[GalleyLine] = {
    val lb = List.newBuilder[GalleyLine]
    val count = math.min(completedLines.length, completedCoords.length)
    var i = 0
    while (i < count) {
      val coords = completedCoords(i)
      lb += buildGalleyLine(completedLines(i), coords, coords.length)
      i += 1
    }
    if (currentLine.nonEmpty) {
      lb += buildGalleyLine(currentLine.toString(), currentCoords, currentCoordsLen)
    }
    lb.result()
  }

  def currentLineGlyphs: List[GalleyGlyph] = {
    val len = currentGlyphsCount
    val lb = List.newBuilder[GalleyGlyph]
    var i = 0
    while (i < len) {
      lb += currentGlyphAt(i)
      i += 1
    }
    lb.result()
  }

  def fullText: String =
    lines.mkString("\n")

  def clear(): Unit = {
    completedLines.clear()
    completedCoords.clear()
    currentLine.clear()
    currentCoordsLen = 0
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
        val num = Fmt.dec02(idx + 1)
        val truncatedLine = if (line.length > 58) line.take(55) + "..." else line
        sb.append(s"│ $num: ${Fmt.padRight(truncatedLine, 58)} │\n")
      }
    }
    sb.append("└─────────────────────────────────────────────────────────────────┘\n")
    sb.toString()
  }
}
