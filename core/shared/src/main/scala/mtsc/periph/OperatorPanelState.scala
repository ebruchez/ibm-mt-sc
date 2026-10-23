package mtsc.periph

import mtsc.cpu.IoResult

/**
 * 3-position red Power Rocker Switch on the left of the MT/SC console (Operator Panel & CE Console).
 */
enum PowerSwitchPosition(val label: String) {
  case Reset extends PowerSwitchPosition("RESET") // Momentary up (Master Reset)
  case On    extends PowerSwitchPosition("ON")    // Center normal operating position
  case Off   extends PowerSwitchPosition("OFF")   // Down power off
}

/**
 * Typography composition modes selectable via keys 4..8 when the MODE lamp is lit.
 */
enum CompositionMode(val keyDigit: Int, val codeLetter: String, val displayName: String) {
  case Justify    extends CompositionMode(4, "J", "Justified")
  case FlushLeft  extends CompositionMode(5, "L", "Flush Left")
  case Center     extends CompositionMode(6, "C", "Centered")
  case FlushRight extends CompositionMode(7, "R", "Flush Right")
  case DotLeader  extends CompositionMode(8, "D", "Dot Leader")
}

object CompositionMode {
  def fromDigit(digit: Int): Option[CompositionMode] =
    values.find(_.keyDigit == digit)
}

/**
 * Setup parameters prompted by the 11 indicator lamps in Operator Mode.
 *
 * Lamps 5..11 have two rows of legends:
 * - Primary (White text above the lamps, active when Lamp 4 SETUP is OFF)
 * - Secondary (Red text below the lamps, active when Lamp 4 SETUP is ON)
 */
enum SetupParam(
  val lampIndex: Int, // 1-based lamp index (1..11)
  val isSecondaryRed: Boolean,
  val label: String,
  val description: String
) {
  // Primary row (White legends above lamps 5..11, SETUP lamp OFF)
  case Clear      extends SetupParam(5,  false, "CLEAR",        "Clear memory & settings (1=NO, 2=YES)")
  case Merge      extends SetupParam(6,  false, "MERGE",        "Two-tape merge mode (1=NO, 2=YES)")
  case Mode       extends SetupParam(7,  false, "MODE",         "Composition mode (4=J, 5=L, 6=C, 7=R, 8=D)")
  case LineCount  extends SetupParam(8,  false, "LINE COUNT",   "Output lines per page (1..32000)")
  case Measure    extends SetupParam(9,  false, "MEASURE",      "Line measure in units (max 940)")
  case LineIndent extends SetupParam(10, false, "LINE INDENT",  "Line indent in units (1=NO or 2..Measure)")
  case ParaIndent extends SetupParam(11, false, "PARA INDENT",  "Paragraph indent in units (1=NO or 2..255)")

  // Secondary row (Red legends below lamps 5..10, SETUP lamp ON)
  case LeaderSpace  extends SetupParam(5,  true, "LEADER SPACE", "Leader space in units (3..255)")
  case MinIw        extends SetupParam(6,  true, "MIN IW",       "Minimum interword space in units (2..255)")
  case MaxIw        extends SetupParam(7,  true, "MAX IW",       "Maximum interword space in units (2..255)")
  case MaxQuad      extends SetupParam(8,  true, "MAX QUAD",     "Maximum quad space in units (2..255)")
  case MinHyphen    extends SetupParam(9,  true, "MIN HYPHEN",   "Minimum hyphenation (1=NO, 2=YES)")
  case DeadKey      extends SetupParam(10, true, "DEAD KEY",     "Dead key disconnect (1=NO, 2=YES)")
}

object SetupParam {
  val PrimarySequence: Vector[SetupParam] = Vector(
    Clear, Merge, Mode, LineCount, Measure, LineIndent, ParaIndent
  )

  val SecondarySequence: Vector[SetupParam] = Vector(
    LeaderSpace, MinIw, MaxIw, MaxQuad, MinHyphen, DeadKey
  )

  val AllSequence: Vector[SetupParam] = PrimarySequence ++ SecondarySequence
}

/**
 * The 12-key keyboard at the bottom of the MT/SC console (Operator Panel & CE Console).
 *
 * Per IBM Manual 241-5429-0 (Page 57 & ILD 0308 on Page 107), depressing a key
 * generates a 4-bit code (KB CDE A, B, C, D) plus KB CDE RDY.
 */
enum ConsoleKey(
  val keyNumber: Int,
  val topLegend: String,
  val bottomLegend: String,
  val kbCodeABCD: Int // 4-bit code: bit 3=A, bit 2=B, bit 1=C, bit 0=D
) {
  case Start extends ConsoleKey(12, "START", "",          0x0)
  case Key0  extends ConsoleKey(10, "0",     "TAPE",      0xA)
  case Key1  extends ConsoleKey(1,  "1",     "NO",        0x9) // A & D
  case Key2  extends ConsoleKey(2,  "2",     "YES",       0x4) // B
  case Key3  extends ConsoleKey(3,  "3",     "",          0x2) // C
  case Key4  extends ConsoleKey(4,  "4",     "J",         0x3) // C & D
  case Key5  extends ConsoleKey(5,  "5",     "L",         0x1) // D
  case Key6  extends ConsoleKey(6,  "6",     "C",         0x5) // B & D
  case Key7  extends ConsoleKey(7,  "7",     "R",         0x6) // B & C
  case Key8  extends ConsoleKey(8,  "8",     "D",         0x7) // B, C & D
  case Key9  extends ConsoleKey(9,  "9",     "",          0x8) // A
  case KeyR  extends ConsoleKey(11, "R",     "LINE STOP", 0x3) // C & D (sets Line Stop Latch)
}

object ConsoleKey {
  def fromDigit(digit: Int): Option[ConsoleKey] = digit match {
    case 0 => Some(Key0)
    case 1 => Some(Key1)
    case 2 => Some(Key2)
    case 3 => Some(Key3)
    case 4 => Some(Key4)
    case 5 => Some(Key5)
    case 6 => Some(Key6)
    case 7 => Some(Key7)
    case 8 => Some(Key8)
    case 9 => Some(Key9)
    case _ => None
  }
}

/**
 * The 8 pushbuttons on the Magnetic Tape Reader Console.
 */
enum TapeConsoleButton(val row: Int, val col: Int, val topLabel: String, val bottomLabel: String) {
  // Top row (left to right)
  case Reread      extends TapeConsoleButton(0, 0, "REREAD", "")
  case Skip        extends TapeConsoleButton(0, 1, "SKIP",   "")
  case Search      extends TapeConsoleButton(0, 2, "SEARCH", "")
  case CoverUnlock extends TapeConsoleButton(0, 3, "COVER",  "UNLOCK")

  // Bottom row (left to right)
  case UnloadLeft  extends TapeConsoleButton(1, 0, "UNLOAD", "LEFT")
  case LoadLeft    extends TapeConsoleButton(1, 1, "LOAD",   "LEFT")
  case UnloadRight extends TapeConsoleButton(1, 2, "UNLOAD", "RIGHT")
  case LoadRight   extends TapeConsoleButton(1, 3, "LOAD",   "RIGHT")
}

/**
 * Static metadata for each of the 11 indicator lamps on the console (Operator Panel & CE Console).
 *
 * Includes both the Operator Faceplate legends and the exposed
 * Customer Engineer (CE) silk-screened legends (ILD Page 0312).
 */
final case class ConsoleLampSpec(
  number: Int,            // 1..11 (left to right)
  isRedLens: Boolean,     // Lamp 4 has a red lens
  operatorTop: String,    // White legend above/below lamp on Operator bezel
  operatorBottom: String, // Secondary/Red legend below lamp on Operator bezel
  ceTopLip: String,       // Legend on upper metal lip (Reset, Test 4..1, ID..IA, B, A)
  ceDisplay4: String,     // Row Display 4 (Rdy, Mpx, QB 4..1, PB 4..1)
  ceDisplay3: String,     // Row Display 3 (Inh, -Low, Eq, OP 7..1)
  ceDisplay2: String,     // Row Display 2 (EOT, Busy, O 8..1)
  ceDisplay1: String      // Row Display 1 (Y 11..1)
)

object ConsoleLampSpec {
  /**
   * Complete specification of all 11 console indicator lamps (left to right),
   * verified against IBM Manual 241-5429-0 ILD Page 0312.
   *
   * Note on Lamp 2 ("Test 4") and Lamp 9 ("IA"):
   * Circular mounting holes sit under Lamp 2 and Lamp 9 on the exposed CE chassis across the
   * Display 3 and Display 2 rows, so "Inh"/"EOT" (Lamp 2) and "OP 3"/"O 3" (Lamp 9)
   * are silk-screened just to the left edge of those holes.
   */
  val AllLamps: Vector[ConsoleLampSpec] = Vector(
    ConsoleLampSpec(1,  false, "RESET",       "HYPHEN",       "Reset",  "",     "",     "",     "Y 11"),
    ConsoleLampSpec(2,  false, "EOT",         "TYPE",         "Test 4", "Rdy",  "Inh",  "EOT",  "Y 10"),
    ConsoleLampSpec(3,  false, "",            "",             "Test 3", "Mpx",  "-Low", "Busy", "Y 9"),
    ConsoleLampSpec(4,  true,  "",            "SETUP",        "Test 2", "QB 4", "Eq",   "O 8",  "Y 8"),
    ConsoleLampSpec(5,  false, "CLEAR",       "LEADER SPACE", "Test 1", "QB 3", "OP 7", "O 7",  "Y 7"),
    ConsoleLampSpec(6,  false, "MERGE",       "MIN IW",       "ID",     "QB 2", "OP 6", "O 6",  "Y 6"),
    ConsoleLampSpec(7,  false, "MODE",        "MAX IW",       "IC",     "QB 1", "OP 5", "O 5",  "Y 5"),
    ConsoleLampSpec(8,  false, "LINE COUNT",  "MAX QUAD",     "IB",     "PB 4", "OP 4", "O 4",  "Y 4"),
    ConsoleLampSpec(9,  false, "MEASURE",     "MIN HYPHEN",   "IA",     "PB 3", "OP 3", "O 3",  "Y 3"),
    ConsoleLampSpec(10, false, "LINE INDENT", "DEAD KEY",     "B",      "PB 2", "OP 2", "O 2",  "Y 2"),
    ConsoleLampSpec(11, false, "PARA INDENT", "",             "A",      "PB 1", "OP 1", "O 1",  "Y 1")
  )
}

/**
 * Runtime state of the Operator Control Panel and Console Keyboard.
 */
class OperatorPanelState {

  // Power rocker switch
  var powerSwitch: PowerSwitchPosition = PowerSwitchPosition.On

  // Primary typesetting setup parameters (White legends)
  var clearDecision: Boolean = false
  var mergeDecision: Boolean = false
  var mode: CompositionMode = CompositionMode.Justify
  var lineCount: Int = 60
  var measure: Int = 140       // 140 units (~1.94 inches / 11.67 picas, standard Selectric Composer column measure)
  var lineIndent: Int = 0
  var paraIndent: Int = 18     // 18 units (1.5 ems)

  // Secondary typesetting setup parameters (Red legends when SETUP lamp is on)
  var leaderSpace: Int = 6
  var minInterword: Int = 4
  var maxInterword: Int = 16
  var maxQuad: Int = 36
  var minHyphen: Boolean = false
  var deadKey: Boolean = false

  // Interactive Setup state
  var activeSetupParam: Option[SetupParam] = None
  var entryBuffer: String = ""
  var hyphenationPromptActive: Boolean = false
  var typeInterventionActive: Boolean = false

  // Hardware Keyboard latches (Manual p. 57-58, ILD 0308)
  var lastKeyPressed: Option[ConsoleKey] = None
  var keyboardSingleEntry: Boolean = false
  var lineStopLatch: Boolean = false
  private val inputQueue: scala.collection.mutable.Queue[Int] = scala.collection.mutable.Queue.empty

  /**
   * True when the red Lamp 4 (SETUP) should be illuminated in Operator mode.
   */
  def isSetupLampOn: Boolean =
    activeSetupParam.exists(_.isSecondaryRed)

  /**
   * Cycles to the next setup parameter on the operator panel (simulating the PRECON setup sequence).
   */
  def advanceSetupPrompt(includeSecondary: Boolean = true): Option[SetupParam] = {
    val seq = if (includeSecondary) SetupParam.AllSequence else SetupParam.PrimarySequence
    entryBuffer = ""
    activeSetupParam = activeSetupParam match {
      case None => seq.headOption
      case Some(cur) =>
        val idx = seq.indexOf(cur)
        if (idx >= 0 && idx + 1 < seq.length) Some(seq(idx + 1))
        else None
    }
    activeSetupParam
  }

  /**
   * Selects a specific setup parameter directly for editing.
   */
  def selectSetupParam(param: SetupParam): Unit = {
    activeSetupParam = Some(param)
    entryBuffer = ""
  }

  /**
   * Handles pressing one of the 12 console keyboard keys (START, 0..9, R).
   * Returns a human-readable description of the action taken.
   */
  def pressKey(key: ConsoleKey): String = {
    lastKeyPressed = Some(key)
    keyboardSingleEntry = true
    inputQueue.enqueue(key.kbCodeABCD)

    key match {
      case ConsoleKey.KeyR =>
        // During setup entry, 'R' rejects/clears the current entry buffer;
        // during normal operation, 'R' sets the hardware Line Stop latch.
        if (activeSetupParam.isDefined && entryBuffer.nonEmpty) {
          entryBuffer = ""
          "Entry cleared (R)."
        } else {
          lineStopLatch = true
          "LINE STOP latch set — composer will stop at end of current line."
        }

      case ConsoleKey.Start =>
        commitCurrentEntry()

      case ConsoleKey.Key0 if activeSetupParam.isEmpty =>
        lineStopLatch = false
        "TAPE START depressed — automatic tape composition started."

      case digitKey =>
        val digitOpt = ConsoleKey.values.indexOf(digitKey) match {
          case _ =>
            digitKey.topLegend.toIntOption
        }
        digitOpt match {
          case Some(d) =>
            activeSetupParam match {
              case Some(param) =>
                entryBuffer = (entryBuffer + d.toString).take(5)
                s"Keyed '$d' for ${param.label} (buffer: $entryBuffer). Press START to store."
              case None =>
                entryBuffer = (entryBuffer + d.toString).take(5)
                s"Keyed '$d' (buffer: $entryBuffer)."
            }
          case None =>
            s"Key ${digitKey.topLegend} pressed."
        }
    }
  }

  /**
   * Releases the currently held console key, resetting the Keyboard Single Entry latch.
   */
  def releaseKey(): Unit = {
    keyboardSingleEntry = false
  }

  /**
   * Reads a pending character code from the Console Keyboard (Device.Keyboard).
   */
  def readKeyboardCode(): IoResult[Int] = {
    if (lineStopLatch) {
      // Per Manual p. 58: Line Stop sets P-Buffer 1 (value 1) and resets Line Stop latch at Bit 5/8
      lineStopLatch = false
      IoResult.Ready(1)
    } else if (inputQueue.nonEmpty) {
      IoResult.Ready(inputQueue.dequeue())
    } else {
      IoResult.Busy
    }
  }

  /**
   * Commits the current buffered keypad entry into the active SetupParam when START is pressed.
   */
  private def commitCurrentEntry(): String = {
    activeSetupParam match {
      case None =>
        val msg = "START depressed — ready for composition."
        entryBuffer = ""
        msg

      case Some(param) =>
        val numVal = entryBuffer.toIntOption
        val summary = param match {
          case SetupParam.Clear =>
            clearDecision = numVal.contains(2)
            if (clearDecision) {
              // Per Manual p. 152: CLEAR-YES resets Merge to NO and Line Indent to 0
              mergeDecision = false
              lineIndent = 0
            }
            s"CLEAR set to ${if (clearDecision) "YES (2)" else "NO (1)"}"

          case SetupParam.Merge =>
            mergeDecision = numVal.contains(2)
            s"MERGE set to ${if (mergeDecision) "YES (2)" else "NO (1)"}"

          case SetupParam.Mode =>
            val newMode = numVal.flatMap(CompositionMode.fromDigit).getOrElse(mode)
            mode = newMode
            s"MODE set to ${mode.codeLetter} (${mode.displayName})"

          case SetupParam.LineCount =>
            numVal.filter(v => v >= 1 && v <= 32000).foreach(lineCount = _)
            s"LINE COUNT set to $lineCount"

          case SetupParam.Measure =>
            numVal.filter(v => v >= 10 && v <= 940).foreach(measure = _)
            s"MEASURE set to $measure units"

          case SetupParam.LineIndent =>
            numVal match {
              case Some(1) => lineIndent = 0
              case Some(v) if v >= 2 && v < measure => lineIndent = v
              case _ => ()
            }
            s"LINE INDENT set to ${if (lineIndent == 0) "NO (0)" else s"$lineIndent units"}"

          case SetupParam.ParaIndent =>
            numVal match {
              case Some(1) => paraIndent = 0
              case Some(v) if v >= 2 && v <= 255 => paraIndent = v
              case _ => ()
            }
            s"PARA INDENT set to ${if (paraIndent == 0) "NO (0)" else s"$paraIndent units"}"

          case SetupParam.LeaderSpace =>
            numVal.filter(v => v >= 3 && v <= 255).foreach(leaderSpace = _)
            s"LEADER SPACE set to $leaderSpace units"

          case SetupParam.MinIw =>
            numVal.filter(v => v >= 2 && v <= 255).foreach(minInterword = _)
            s"MIN IW set to $minInterword units"

          case SetupParam.MaxIw =>
            numVal.filter(v => v >= 2 && v <= 255).foreach(maxInterword = _)
            s"MAX IW set to $maxInterword units"

          case SetupParam.MaxQuad =>
            numVal.filter(v => v >= 2 && v <= 255).foreach(maxQuad = _)
            s"MAX QUAD set to $maxQuad units"

          case SetupParam.MinHyphen =>
            minHyphen = numVal.contains(2)
            s"MIN HYPHEN set to ${if (minHyphen) "YES (2)" else "NO (1)"}"

          case SetupParam.DeadKey =>
            deadKey = numVal.contains(2)
            s"DEAD KEY set to ${if (deadKey) "YES (2)" else "NO (1)"}"
        }

        // Advance to next setup decision in sequence
        advanceSetupPrompt(includeSecondary = param.isSecondaryRed)
        summary
    }
  }

  /**
   * Formats the current value of a setup parameter for display.
   */
  def formatParamValue(param: SetupParam): String = param match {
    case SetupParam.Clear       => if (clearDecision) "YES" else "NO"
    case SetupParam.Merge       => if (mergeDecision) "YES" else "NO"
    case SetupParam.Mode        => s"${mode.codeLetter} (${mode.displayName})"
    case SetupParam.LineCount   => s"$lineCount"
    case SetupParam.Measure     => s"${measure}u"
    case SetupParam.LineIndent  => if (lineIndent == 0) "NO" else s"${lineIndent}u"
    case SetupParam.ParaIndent  => if (paraIndent == 0) "NO" else s"${paraIndent}u"
    case SetupParam.LeaderSpace => s"${leaderSpace}u"
    case SetupParam.MinIw       => s"${minInterword}u"
    case SetupParam.MaxIw       => s"${maxInterword}u"
    case SetupParam.MaxQuad     => s"${maxQuad}u"
    case SetupParam.MinHyphen   => if (minHyphen) "YES" else "NO"
    case SetupParam.DeadKey     => if (deadKey) "YES" else "NO"
  }

  def reset(): Unit = {
    powerSwitch = PowerSwitchPosition.On
    clearDecision = false
    mergeDecision = false
    mode = CompositionMode.Justify
    lineCount = 60
    measure = 140
    lineIndent = 0
    paraIndent = 18
    leaderSpace = 6
    minInterword = 4
    maxInterword = 16
    maxQuad = 36
    minHyphen = false
    deadKey = false
    activeSetupParam = None
    entryBuffer = ""
    hyphenationPromptActive = false
    typeInterventionActive = false
    lastKeyPressed = None
    keyboardSingleEntry = false
    lineStopLatch = false
    inputQueue.clear()
  }
}
