package mtsc.console

import mtsc.types.*
import mtsc.cpu.Ccu
import mtsc.cpu.ild.{BackplaneSignals, Ild0312ConsoleLights, IldCcu}
import mtsc.memory.CoreMemory
import mtsc.periph.{ConsoleKey, ConsoleLampSpec, OperatorPanelState, SystemIoBus}
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

  private def toNibbleBinary(nibble: Int): String = {
    val n = nibble & 0xf
    s"${(n >> 3) & 1}${(n >> 2) & 1}${(n >> 1) & 1}${n & 1}"
  }

  /**
   * Sets the `EDIT MEMORY SELECTOR` 5-position slide switch per ILD 0312 & Page 0603:
   * - `Addr` physically selects `Display 1` (`Y 11..Y 1`) and recirculates `PSAR -> MAL`.
   * - `Data` physically selects `Display 2` (`O 8..O 1`) and recirculates `MEM LOC -> O REG`.
   * - `Edit` recirculates `PSAR -> MAL (+0)` and `MEM LOC -> A WORD`.
   * - `Proc` resumes normal instruction execution.
   */
  def selectEditSelector(newSelector: CeEditSelector, cpuOpt: Option[Ccu] = None): CeEditSelector = {
    editSelector = newSelector
    mode = editSelector match {
      case CeEditSelector.Proc => CeConsoleMode.Process
      case CeEditSelector.Addr => CeConsoleMode.EditAddress
      case CeEditSelector.Data => CeConsoleMode.EditData
      case _                   => CeConsoleMode.SingleStep
    }

    editSelector match {
      case CeEditSelector.Addr =>
        displayMode = CeDisplayMode.Display1
        cpuOpt.foreach { cpu =>
          if (cpu.isIldMode) {
            cpu.ild.setEditSelector(CeEditSelector.Addr)
          } else {
            val addr = cpu.memory.psar
            cpu.state.mal = addr
            cpu.memory.aWord = cpu.memory.readWord(Address(addr.unsigned & 0x7fe))
            cpu.state.outputReg = cpu.memory.readByte(addr)
          }
        }
        log("Right CE slide switch set to [Addr] (Display 1: Y 11..Y 1)")
        statusMessage = cpuOpt match {
          case Some(cpu) =>
            val a = cpu.memory.psar.unsigned
            f"Edit Selector [Addr]: Display 1 (Y 11..Y 1). PSAR = $a (0x$a%03X). Press Clear to zero, or keys 0..9/R to set Y bits."
          case None =>
            "Edit Memory Selector switch: Addr (Display 1)"
        }

      case CeEditSelector.Data =>
        displayMode = CeDisplayMode.Display2
        cpuOpt.foreach { cpu =>
          if (cpu.isIldMode) {
            cpu.ild.setEditSelector(CeEditSelector.Data)
          } else {
            val addr = cpu.memory.psar
            val b = cpu.memory.readByte(addr)
            cpu.state.mal = addr
            cpu.state.outputReg = b
          }
        }
        log("Right CE slide switch set to [Data] (Display 2: O 8..O 1)")
        statusMessage = cpuOpt match {
          case Some(cpu) =>
            val a = cpu.memory.psar.unsigned
            val b = cpu.state.outputReg & 0xff
            val binStr = s"${toNibbleBinary(b >> 4)} ${toNibbleBinary(b)}"
            f"Edit Selector [Data]: Display 2 (O 8..O 1). Byte at $a (0x$a%03X) = $binStr (0x$b%02X). Press Clear to zero, or keys 3..9/R to set O bits."
          case None =>
            "Edit Memory Selector switch: Data (Display 2)"
        }

      case CeEditSelector.Edit =>
        cpuOpt.foreach { cpu =>
          if (cpu.isIldMode) {
            cpu.ild.setEditSelector(CeEditSelector.Edit)
          } else {
            val addr = cpu.memory.psar
            cpu.state.mal = addr
            cpu.memory.aWord = cpu.memory.readWord(Address(addr.unsigned & 0x7fe))
          }
        }
        log("Right CE slide switch set to [Edit]")
        statusMessage = "Edit Memory Selector switch: Edit"

      case CeEditSelector.Proc =>
        cpuOpt.foreach { cpu =>
          if (cpu.isIldMode) {
            cpu.ild.setEditSelector(CeEditSelector.Proc)
          }
          cpu.state.halted = false
          cpu.state.mal = cpu.memory.psar
        }
        log("Right CE slide switch set to [Proc]")
        statusMessage = cpuOpt match {
          case Some(cpu) =>
            val a = cpu.memory.psar.unsigned
            if ((a & 1) != 0)
              f"Edit Selector [Proc]: Note — PSAR is at odd byte $a (0x$a%03X); set Addr to an even instruction address (e.g. 42 / 0x02A)."
            else
              f"Edit Memory Selector switch: Proc (ready at PSAR $a / 0x$a%03X)."
          case None =>
            "Edit Memory Selector switch: Proc"
        }

      case CeEditSelector.Prog =>
        cpuOpt.foreach { cpu =>
          if (cpu.isIldMode) {
            cpu.ild.setEditSelector(CeEditSelector.Prog)
          }
        }
        log("Right CE slide switch set to [Prog]")
        statusMessage = "Edit Memory Selector switch: Prog"
    }

    editSelector
  }

  def cycleEditSelector(cpuOpt: Option[Ccu] = None): CeEditSelector = {
    val all = CeEditSelector.values
    val nextIdx = (all.indexOf(editSelector) + 1) % all.length
    selectEditSelector(all(nextIdx), cpuOpt)
  }

  /**
   * Handles the CE `Clear` plunger button per Page 0603 (`INH RD IF CLR`):
   * - In `Addr` mode: inhibits reading PSAR at `I-0`, clearing PSAR & MAL to `0x000`.
   * - In `Data` mode: inhibits reading MEM LOC at `I-1`, clearing the single byte at PSAR & O REG to `0x00`.
   * - Otherwise: resets condition and tape parity error indicators (`RES INDS`).
   */
  def pressCeClear(cpu: Ccu): String = {
    if (cpu.isIldMode) {
      return pressCeClear(cpu.ild)
    }
    val msg = editSelector match {
      case CeEditSelector.Addr =>
        cpu.memory.psar = Address(0)
        cpu.state.mal = Address(0)
        cpu.state.outputReg = cpu.memory.readByte(Address(0))
        log("CE Clear [Addr]: cleared PSAR & MAL to 0x000 (0).")
        "Cleared PSAR & MAL to 0 (0x000)."

      case CeEditSelector.Data =>
        val addr = cpu.memory.psar
        cpu.memory.writeByte(addr, 0)
        cpu.state.mal = addr
        cpu.state.outputReg = 0
        log(f"CE Clear [Data]: cleared byte at ${addr.unsigned} (0x${addr.unsigned}%03X) to 0x00.")
        f"Cleared byte at ${addr.unsigned} (0x${addr.unsigned}%03X) to 0000 0000 (0x00)."

      case _ =>
        cpu.state.inhibit = false
        cpu.state.low = false
        cpu.state.equal = false
        cpu.ioBus match {
          case s: SystemIoBus =>
            s.rightTape.parityError = false
            s.leftTape.parityError = false
          case _ => ()
        }
        log("CE Clear: reset condition and error indicators (RES INDS).")
        "Indicators cleared."
    }
    statusMessage = msg
    msg
  }

  /**
   * Handles pressing one of the 12 console keys when the CE chassis is exposed
   * and `EDIT MEMORY SELECTOR` is in `Addr` or `Data` position (ILD 0308 & Page 0603).
   *
   * Returns `Some(status)` if consumed by CE manual entry, or `None` for normal processing.
   */
  def pressCeKey(key: ConsoleKey, cpu: Ccu): Option[String] = {
    if (cpu.isIldMode) {
      return pressCeKey(key, cpu.ild)
    }
    if (faceplateInstalled) return None

    editSelector match {
      case CeEditSelector.Addr =>
        displayMode = CeDisplayMode.Display1
        val msg = key.addrBitIndex match {
          case Some(bit) =>
            val updated = (cpu.memory.psar.unsigned | (1 << bit)) & 0x7ff
            val addr = Address(updated)
            cpu.memory.psar = addr
            cpu.state.mal = addr
            cpu.memory.aWord = cpu.memory.readWord(Address(updated & 0x7fe))
            cpu.state.outputReg = cpu.memory.readByte(addr)
            cpu.state.pBuffer = 1 << (bit & 3)
            val m = f"CE Edit Addr [Key ${key.topLegend} -> Y ${bit + 1} (+${1 << bit})]: PSAR = $updated (0x$updated%03X)."
            log(m)
            m

          case None =>
            "CE Edit Addr: START has no Y-latch bit (use keys 0..9, R for Y 11..Y 1; press Clear to zero)."
        }
        statusMessage = msg
        Some(msg)

      case CeEditSelector.Data =>
        displayMode = CeDisplayMode.Display2
        val msg = key.dataBitIndex match {
          case Some(bit) =>
            val addr = cpu.memory.psar
            val cur = cpu.memory.readByte(addr)
            val updated = (cur | (1 << bit)) & 0xff
            cpu.memory.writeByte(addr, updated)
            cpu.state.mal = addr
            cpu.state.outputReg = updated
            cpu.state.pBuffer = 1 << (bit & 3)
            val binStr = s"${toNibbleBinary(updated >> 4)} ${toNibbleBinary(updated)}"
            val m = f"CE Edit Data [Key ${key.topLegend} -> O ${bit + 1}] at ${addr.unsigned} (0x${addr.unsigned}%03X): $binStr (0x$updated%02X)."
            log(m)
            m

          case None =>
            s"CE Edit Data: Key ${key.topLegend} ignored by -BT CTR B gate (use keys 3..9, R for O 8..O 1; press Clear to zero byte)."
        }
        statusMessage = msg
        Some(msg)

      case _ =>
        None
    }
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
    if (cpu.isIldMode) {
      return computeLamps(cpu.ild)
    }
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
      val s = new BackplaneSignals()
      s.setMalValue(cpu.state.mal.unsigned)
      s.setOutputByte(cpu.state.outputReg)
      s.oplCde1 = cpu.state.opBit(1)
      s.oplCde2 = cpu.state.opBit(2)
      s.oplCde3 = cpu.state.opBit(3)
      s.oplCde4 = cpu.state.opBit(4)
      s.oplCde5 = cpu.state.opBit(5)
      s.oplCde6 = cpu.state.opBit(6)
      s.oplCde7 = cpu.state.opBit(7)
      s.qBfr1   = cpu.state.qbBit(1)
      s.qBfr2   = cpu.state.qbBit(2)
      s.qBfr3   = cpu.state.qbBit(3)
      s.qBfr4   = cpu.state.qbBit(4)
      s.pBfr1   = cpu.state.pbBit(1)
      s.pBfr2   = cpu.state.pbBit(2)
      s.pBfr3   = cpu.state.pbBit(3)
      s.pBfr4   = cpu.state.pbBit(4)
      s.insnTCtrA = (cpu.state.insnTimeCtr & 1) == 1
      s.insnTCtrB = ((cpu.state.insnTimeCtr >> 1) & 1) == 1
      s.insnTCtrC = ((cpu.state.insnTimeCtr >> 2) & 1) == 1
      s.insnTCtrD = ((cpu.state.insnTimeCtr >> 3) & 1) == 1
      s.btCtrA    = (cpu.state.bitTimeCtr & 1) == 1
      s.btCtrB    = ((cpu.state.bitTimeCtr >> 1) & 1) == 1
      s.low       = cpu.state.low
      s.eq        = cpu.state.equal
      s.inh       = cpu.state.inhibit
      s.mpx       = cpu.state.multiplex
      s.rdy       = readySignal
      s.busy      = translatorBusy
      s.errorLite = eotActive
      s.masterReset = displayMode == CeDisplayMode.ClockDefault && parityErr
      s.setCeTestInputs(ceTestInputs)
      s.editSwitch = CeEditSelector.Proc
      Ild0312ConsoleLights.applyDisplayModeButtons(s, displayMode)
      s.autoOrStep = displayMode != CeDisplayMode.ClockDefault
      Ild0312ConsoleLights.evaluateCombinational(s)

      ConsoleLampSpec.AllLamps.zip(s.lampsLeftToRight).map { case (spec, on) =>
        val label = displayMode match {
          case CeDisplayMode.Display1     => spec.ceDisplay1
          case CeDisplayMode.Display2     => spec.ceDisplay2
          case CeDisplayMode.Display3     => spec.ceDisplay3
          case CeDisplayMode.Display4     => spec.ceDisplay4
          case CeDisplayMode.ClockDefault => spec.ceTopLip
        }
        LampState(spec, label, on && powerOn)
      }
    }
  }

  /**
   * Computes all 11 console indicator lamps directly from the gate-level [[IldCcu]] and [[Ild0312ConsoleLights]].
   */
  def computeLamps(ild: IldCcu): Vector[LampState] =
    ild.computeLamps(displayMode, ceTestInputs, faceplateInstalled, powerOn)

  /**
   * Sets the `EDIT MEMORY SELECTOR` 5-position slide switch on a gate-level [[IldCcu]],
   * driving `AID 1..3`, `DPLY 1..4`, and the `I-0 -> I-1 -> I-0` Edit cycle.
   */
  def selectEditSelector(newSelector: CeEditSelector, ild: IldCcu): CeEditSelector = {
    editSelector = newSelector
    mode = editSelector match {
      case CeEditSelector.Proc => CeConsoleMode.Process
      case CeEditSelector.Addr => CeConsoleMode.EditAddress
      case CeEditSelector.Data => CeConsoleMode.EditData
      case _                   => CeConsoleMode.SingleStep
    }
    editSelector match {
      case CeEditSelector.Addr => displayMode = CeDisplayMode.Display1
      case CeEditSelector.Data => displayMode = CeDisplayMode.Display2
      case CeEditSelector.Prog => displayMode = CeDisplayMode.Display3
      case _                   => ()
    }
    ild.setEditSelector(newSelector)
    editSelector
  }

  /**
   * Presses the CE `Clear` plunger button on a gate-level [[IldCcu]].
   */
  def pressCeClear(ild: IldCcu): String = {
    ild.pressCeClear()
    val msg = editSelector match {
      case CeEditSelector.Addr =>
        log("CE Clear [Addr]: cleared PSAR & MAL to 0x000 (0).")
        "Cleared PSAR & MAL to 0 (0x000)."
      case CeEditSelector.Data =>
        val addr = ild.memory.psar
        log(f"CE Clear [Data]: cleared byte at ${addr.unsigned} (0x${addr.unsigned}%03X) to 0x00.")
        f"Cleared byte at ${addr.unsigned} (0x${addr.unsigned}%03X) to 0000 0000 (0x00)."
      case _ =>
        log("CE Clear: reset condition and error indicators (RES INDS).")
        "Indicators cleared."
    }
    statusMessage = msg
    msg
  }

  /**
   * Presses one of the 12 console keys on a gate-level [[IldCcu]] in CE `Addr`, `Data`, `Edit`, or `Prog` mode.
   */
  def pressCeKey(key: ConsoleKey, ild: IldCcu): Option[String] = {
    if (faceplateInstalled) return None
    editSelector match {
      case CeEditSelector.Addr =>
        displayMode = CeDisplayMode.Display1
        ild.pressCeKey(key)
        val msg = key.addrBitIndex match {
          case Some(bit) =>
            val updated = ild.memory.psar.unsigned
            f"CE Edit Addr [Key ${key.topLegend} -> Y ${bit + 1} (+${1 << bit})]: PSAR = $updated (0x$updated%03X)."
          case None =>
            "CE Edit Addr: START has no Y-latch bit (use keys 0..9, R for Y 11..Y 1; press Clear to zero)."
        }
        log(msg)
        statusMessage = msg
        Some(msg)

      case CeEditSelector.Data =>
        displayMode = CeDisplayMode.Display2
        ild.pressCeKey(key)
        val msg = key.dataBitIndex match {
          case Some(bit) =>
            val addr = ild.memory.psar
            val updated = ild.state.outputReg & 0xff
            val binStr = s"${toNibbleBinary(updated >> 4)} ${toNibbleBinary(updated)}"
            f"CE Edit Data [Key ${key.topLegend} -> O ${bit + 1}] at ${addr.unsigned} (0x${addr.unsigned}%03X): $binStr (0x$updated%02X)."
          case None =>
            s"CE Edit Data: Key ${key.topLegend} ignored by -BT CTR B gate (use keys 3..9, R for O 8..O 1; press Clear to zero byte)."
        }
        log(msg)
        statusMessage = msg
        Some(msg)

      case CeEditSelector.Edit | CeEditSelector.Prog if key == ConsoleKey.Start =>
        ild.pressCeKey(key)
        val updated = ild.memory.psar.unsigned
        val msg = f"CE Edit [${editSelector.label}]: START advanced PSAR by +1 to $updated (0x$updated%03X)."
        log(msg)
        statusMessage = msg
        Some(msg)

      case _ =>
        None
    }
  }
}
