package mtsc.console

import mtsc.types.*
import mtsc.cpu.Ccu
import mtsc.memory.CoreMemory
import mtsc.periph.{ConsoleLampSpec, OperatorPanelState, SystemIoBus}
import scala.collection.mutable.ArrayBuffer

/**
 * Operating mode selected on the Customer Engineer maintenance console.
 * Kept for backwards compatibility with existing CLI / tests while mapped to the hardware slide switches.
 */
enum CeConsoleMode(val displayName: String) {
  case Process     extends CeConsoleMode("PROCESS")
  case EditAddress extends CeConsoleMode("EDIT ADDRESS")
  case EditData    extends CeConsoleMode("EDIT DATA")
  case SingleStep  extends CeConsoleMode("SINGLE STEP")
}

/**
 * Top-row Display Selector buttons on the exposed CE Chassis (ILD Page 0312):
 * [4] — [3] — Display — [2] — [1], plus ClockDefault when no Display button is depressed.
 */
enum CeDisplayMode(val buttonNumber: Option[Int], val rowLabel: String, val description: String) {
  case ClockDefault extends CeDisplayMode(None,    "Clock / Test", "Reset | Test 4..1 | ID IC IB IA | B A")
  case Display1     extends CeDisplayMode(Some(1), "Display 1",    "Memory Address Latches (Y 11 .. Y 1)")
  case Display2     extends CeDisplayMode(Some(2), "Display 2",    "EOT | Busy | Output Register (O 8 .. O 1)")
  case Display3     extends CeDisplayMode(Some(3), "Display 3",    "Inh | -Low | Eq | Op Code (OP 7 .. OP 1)")
  case Display4     extends CeDisplayMode(Some(4), "Display 4",    "Rdy | Mpx | Q-Buffer (QB 4..1) | P-Buffer (PB 4..1)")
}

/**
 * Left 5-position slide switch at the bottom of the CE Chassis:
 * Controls clock stepping granularity when [Step] is pressed.
 */
enum CeStepGranularity(val label: String) {
  case Bit         extends CeStepGranularity("Bit")   // Step 1 Bit Time (1/4, 5/8, 9/12, 13/16)
  case Word        extends CeStepGranularity("Word")  // Step 1 Instruction Time (I-0 .. I-9)
  case ProgramStep extends CeStepGranularity("P.S.")  // Step 1 complete Program Step
  case Jump        extends CeStepGranularity("Jump")  // Run until next Jump instruction
  case Auto        extends CeStepGranularity("Auto")  // Normal continuous clock
}

/**
 * Right 5-position slide switch at the bottom of the CE Chassis (ILD Page 0312):
 * "EDIT MEMORY SELECTOR" controlling normal processing vs manual address/data editing.
 */
enum CeEditSelector(val label: String) {
  case Proc extends CeEditSelector("Proc") // Normal Process mode
  case Edit extends CeEditSelector("Edit") // Edit mode
  case Addr extends CeEditSelector("Addr") // Edit PSAR Address via keyboard at I-0 time
  case Data extends CeEditSelector("Data") // Edit Core Memory Data via keyboard at I-1 time
  case Prog extends CeEditSelector("Prog") // Program load / diagnostic mode
}

/**
 * Represents one row of the silk-screened "INITIAL LOADER" table on the upper right
 * of the CE Chassis, for memory byte addresses 40 through 47.
 */
final case class InitialLoaderRow(
  byteAddress: Int,
  nibbleB: String, // High nibble (Bit 5/8)
  nibbleA: String  // Low nibble (Bit 1/4)
) {
  /**
   * Resolves the byte value, substituting `p` (1 = Paper Tape, 0 = Mag Tape)
   * and `m` (1 = Left/Merge Station, 0 = Right Station).
   */
  def resolveByte(paperTape: Boolean = false, leftStation: Boolean = false): Int = {
    def parseNibble(s: String): Int = {
      val bits = s
        .replace('p', if (paperTape) '1' else '0')
        .replace('m', if (leftStation) '1' else '0')
      Integer.parseInt(bits, 2) & 0xf
    }
    (parseNibble(nibbleB) << 4) | parseNibble(nibbleA)
  }
}

object InitialLoaderTable {
  /**
   * Exact 8-byte (4-instruction) bootstrap loader silk-screened on the CE Console:
   *
   *   40: 0000 0000
   *   41: 0000 0000
   *   42: p000 0010
   *   43: 0001 100m
   *   44: 0000 0011
   *   45: 0111 1010
   *   46: 0010 1010
   *   47: 0000 0000
   */
  val Rows: Vector[InitialLoaderRow] = Vector(
    InitialLoaderRow(40, "0000", "0000"),
    InitialLoaderRow(41, "0000", "0000"),
    InitialLoaderRow(42, "p000", "0010"),
    InitialLoaderRow(43, "0001", "100m"),
    InitialLoaderRow(44, "0000", "0011"),
    InitialLoaderRow(45, "0111", "1010"),
    InitialLoaderRow(46, "0010", "1010"),
    InitialLoaderRow(47, "0000", "0000")
  )

  /**
   * Writes the 8-byte Initial Loader into Core Memory addresses 40..47 and sets PSAR to 42.
   */
  def loadIntoMemory(
    memory: CoreMemory,
    paperTape: Boolean = false,
    leftStation: Boolean = false
  ): Unit = {
    for (row <- Rows) {
      memory.writeByte(Address(row.byteAddress), row.resolveByte(paperTape, leftStation))
    }
    memory.psar = Address(42)
  }
}

/**
 * Computed visual state for one of the 11 console indicator lamps.
 */
final case class LampState(
  spec: ConsoleLampSpec,
  activeLabel: String,
  isOn: Boolean
)

/**
 * State of the Customer Engineer console, physical switches, and interactive debugger.
 */
class CeConsoleState {

  // Faceplate state: false = Exposed CE Chassis, true = Operator Faceplate ON
  var faceplateInstalled: Boolean = false

  // CE Console top buttons & bottom slide switches
  var displayMode: CeDisplayMode = CeDisplayMode.Display1
  var stepGranularity: CeStepGranularity = CeStepGranularity.Auto
  var editSelector: CeEditSelector = CeEditSelector.Proc
  var ceTestInputs: Int = 0 // 4-bit test input mask (Test 1..4)

  // Legacy mode property synchronized with slide switches
  var mode: CeConsoleMode = CeConsoleMode.Process
  var breakpoints: Set[Address] = Set.empty
  var memoryInspectionAddress: Address = Address(0)
  var statusMessage: String = "Ready. Press [s] to step, [r] to run, [f] to toggle Operator/CE faceplate."
  var powerOn: Boolean = true
  var singleStepMode: Boolean = false
  var viewGalley: Boolean = false
  var realTimePacing: Boolean = true
  var pacingSpeed: Double = 1.0 // 1.0 = 14 cps (~71 ms/char), 2.0 = 28 cps, 5.0 = 70 cps, 0.0 = instant

  def printDelayMs: Long =
    if (!realTimePacing || pacingSpeed <= 0.0) 0L
    else math.max(5L, (71.4 / pacingSpeed).toLong)

  def tapeDelayMs: Long =
    if (!realTimePacing || pacingSpeed <= 0.0) 0L
    else math.max(3L, (50.0 / pacingSpeed).toLong)

  val eventLog: ArrayBuffer[String] = ArrayBuffer(
    "IBM MT/SC CCU 6320 CE Console initialized.",
    "Clock oscillator: 240 kHz (nominal).",
    "Core storage: 2,048 core memory bytes online."
  )

  def log(msg: String): Unit = {
    eventLog += msg
    if (eventLog.length > 30) {
      eventLog.remove(0)
    }
  }

  def toggleFaceplate(): Boolean = {
    faceplateInstalled = !faceplateInstalled
    val viewName = if (faceplateInstalled) "Operator Faceplate ON" else "CE Chassis Exposed"
    log(s"Console cover changed: $viewName")
    statusMessage = s"Switched to $viewName."
    faceplateInstalled
  }

  def selectDisplayMode(newMode: CeDisplayMode): Unit = {
    displayMode = newMode
    log(s"CE Display Selector: ${newMode.rowLabel} (${newMode.description})")
    statusMessage = s"CE Lamps showing ${newMode.rowLabel}: ${newMode.description}"
  }

  def cycleStepGranularity(): CeStepGranularity = {
    val all = CeStepGranularity.values
    val nextIdx = (all.indexOf(stepGranularity) + 1) % all.length
    stepGranularity = all(nextIdx)
    singleStepMode = stepGranularity != CeStepGranularity.Auto
    log(s"Left CE slide switch set to [${stepGranularity.label}]")
    statusMessage = s"Step granularity switch: ${stepGranularity.label}"
    stepGranularity
  }

  def cycleEditSelector(): CeEditSelector = {
    val all = CeEditSelector.values
    val nextIdx = (all.indexOf(editSelector) + 1) % all.length
    editSelector = all(nextIdx)
    mode = editSelector match {
      case CeEditSelector.Proc => CeConsoleMode.Process
      case CeEditSelector.Addr => CeConsoleMode.EditAddress
      case CeEditSelector.Data => CeConsoleMode.EditData
      case _                   => CeConsoleMode.SingleStep
    }
    log(s"Right CE slide switch (Edit Selector) set to [${editSelector.label}]")
    statusMessage = s"Edit Memory Selector switch: ${editSelector.label}"
    editSelector
  }

  def toggleBreakpoint(addr: Address): Boolean = {
    if (breakpoints.contains(addr)) {
      breakpoints -= addr
      log(f"Breakpoint cleared at 0x${addr.unsigned}%03X")
      false
    } else {
      breakpoints += addr
      log(f"Breakpoint set at 0x${addr.unsigned}%03X")
      true
    }
  }

  def cycleMode(): CeConsoleMode = {
    mode = mode match {
      case CeConsoleMode.Process     => CeConsoleMode.SingleStep
      case CeConsoleMode.SingleStep  => CeConsoleMode.EditAddress
      case CeConsoleMode.EditAddress => CeConsoleMode.EditData
      case CeConsoleMode.EditData    => CeConsoleMode.Process
    }
    editSelector = mode match {
      case CeConsoleMode.Process     => CeEditSelector.Proc
      case CeConsoleMode.SingleStep  => CeEditSelector.Edit
      case CeConsoleMode.EditAddress => CeEditSelector.Addr
      case CeConsoleMode.EditData    => CeEditSelector.Data
    }
    log(s"Rotary switch turned to ${mode.displayName}")
    mode
  }

  /**
   * Computes the hardware lamp state for all 11 lamps according to ILD Page 0312 (Manual p. 111).
   *
   * - When `faceplateInstalled == true` (Operator Mode):
   *   Lamps show Operator prompts (`RESET/HYPHEN`, `EOT/TYPE`, `SETUP`, `CLEAR..PARA INDENT`,
   *   or `LEADER SPACE..DEAD KEY` when `SETUP` is lit).
   * - When `faceplateInstalled == false` (CE Mode):
   *   Lamps are multiplexed by `displayMode` (`Display 1..4` or `ClockDefault`).
   */
  def computeLamps(cpu: Ccu): Vector[LampState] = {
    val sysBusOpt = cpu.ioBus match {
      case s: SystemIoBus => Some(s)
      case _              => None
    }

    val parityErr = sysBusOpt.exists(s => s.rightTape.parityError || s.leftTape.parityError)
    val eotActive = sysBusOpt.exists(s =>
      (s.rightTape.isTapeLoaded && s.rightTape.isAtEnd) ||
      (s.leftTape.isTapeLoaded && s.leftTape.isAtEnd)
    )
    val translatorBusy = sysBusOpt.exists(s => s.translator.busy || s.translator.cycleRemaining > 0)
    val readySignal = !cpu.state.inhibit && !translatorBusy && !cpu.state.halted

    if (faceplateInstalled) {
      val opPanel = sysBusOpt.map(_.operatorPanel).getOrElse(new OperatorPanelState())
      val setupRedOn = opPanel.isSetupLampOn
      val activeLampNum = opPanel.activeSetupParam.map(_.lampIndex)
      val rawLights = sysBusOpt.map(_.consoleLights).getOrElse(0)

      ConsoleLampSpec.AllLamps.map { spec =>
        val isOn = spec.number match {
          case 1 => parityErr || opPanel.hyphenationPromptActive
          case 2 => eotActive || opPanel.typeInterventionActive
          case 3 => (rawLights & 0x80) != 0
          case 4 => setupRedOn
          case n => activeLampNum.contains(n) || ((rawLights >> (11 - n)) & 1) == 1
        }
        val label =
          if (spec.number == 4) "SETUP"
          else if (setupRedOn && spec.operatorBottom.nonEmpty) spec.operatorBottom
          else if (spec.operatorTop.nonEmpty) spec.operatorTop
          else s"L${spec.number}"
        LampState(spec, label, isOn && powerOn)
      }
    } else {
      ConsoleLampSpec.AllLamps.map { spec =>
        val (label, on) = displayMode match {
          case CeDisplayMode.Display1 =>
            val yIndex = 12 - spec.number // Lamp 1 -> Y 11, Lamp 11 -> Y 1
            (spec.ceDisplay1, cpu.state.yBit(yIndex))

          case CeDisplayMode.Display2 =>
            val isOn = spec.number match {
              case 1 => false
              case 2 => eotActive
              case 3 => translatorBusy
              case n => cpu.state.outBit(12 - n) // Lamp 4 -> O 8 .. Lamp 11 -> O 1
            }
            (spec.ceDisplay2, isOn)

          case CeDisplayMode.Display3 =>
            val isOn = spec.number match {
              case 1 => false
              case 2 => cpu.state.inhibit
              case 3 => !cpu.state.low
              case 4 => cpu.state.equal
              case n => cpu.state.opBit(12 - n) // Lamp 5 -> OP 7 .. Lamp 11 -> OP 1
            }
            (spec.ceDisplay3, isOn)

          case CeDisplayMode.Display4 =>
            val isOn = spec.number match {
              case 1  => false
              case 2  => readySignal
              case 3  => cpu.state.multiplex
              case 4  => cpu.state.qbBit(4)
              case 5  => cpu.state.qbBit(3)
              case 6  => cpu.state.qbBit(2)
              case 7  => cpu.state.qbBit(1)
              case 8  => cpu.state.pbBit(4)
              case 9  => cpu.state.pbBit(3)
              case 10 => cpu.state.pbBit(2)
              case 11 => cpu.state.pbBit(1)
              case _  => false
            }
            (spec.ceDisplay4, isOn)

          case CeDisplayMode.ClockDefault =>
            val isOn = spec.number match {
              case 1  => parityErr
              case 2  => ((ceTestInputs >> 3) & 1) == 1
              case 3  => ((ceTestInputs >> 2) & 1) == 1
              case 4  => ((ceTestInputs >> 1) & 1) == 1
              case 5  => (ceTestInputs & 1) == 1
              case 6  => ((cpu.state.insnTimeCtr >> 3) & 1) == 1 // ID
              case 7  => ((cpu.state.insnTimeCtr >> 2) & 1) == 1 // IC
              case 8  => ((cpu.state.insnTimeCtr >> 1) & 1) == 1 // IB
              case 9  => (cpu.state.insnTimeCtr & 1) == 1        // IA
              case 10 => ((cpu.state.bitTimeCtr >> 1) & 1) == 1  // B
              case 11 => (cpu.state.bitTimeCtr & 1) == 1         // A
              case _  => false
            }
            (spec.ceTopLip, isOn)
        }
        LampState(spec, label, on && powerOn)
      }
    }
  }
}
