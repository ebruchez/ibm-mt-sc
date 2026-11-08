package mtsc.cli

import mtsc.types.*
import mtsc.cpu.{CcuEngineMode, StepResult}
import mtsc.memory.CoreMemory
import mtsc.periph.{ConsoleKey, MtscSystem, SystemIoBus, TapeCartridge, TapeConsoleButton, TapeReader}
import mtsc.diag.{ContinuousComposerRoutine, DiagnosticPrograms, EscapementRoutine}
import mtsc.console.{AnsiTerminal, CeConsoleMode, CeConsoleState, CeConsoleView, CeDisplayMode, CeEditSelector, InitialLoaderTable}

/**
 * Main Interactive Customer Engineer Console Debugger for the IBM MT/SC CCU.
 *
 * Supports single-step execution, continuous run, real-time mechanical pacing (14 cps),
 * breakpoints, program loading, memory inspection, live hardware display lamps,
 * dual tape readers, arbitrary text file ingestion, and camera-ready galley proofs.
 */
object Main {

  /** Default execution engine for JVM and Scala Native CLI (`CcuEngineMode.Ild` or `CcuEngineMode.Behavioral`). */
  val DefaultEngineMode: CcuEngineMode = CcuEngineMode.Default

  private var currentProgramIndex: Int = 0
  private var currentTapeButtonIndex: Int = 0

  /**
   * Parses command-line arguments, applying engine mode (`--ild` / `--behavioral`),
   * pacing speed (`--max`, `--2x`, `--5x`), and console faceplate (`--operator`) options.
   *
   * Returns an optional initial tape file path if a non-flag argument was supplied.
   */
  def parseArgs(
    args: Array[String],
    system: MtscSystem,
    consoleState: CeConsoleState
  ): Option[String] = {
    var initialFile: Option[String] = None
    var i = 0
    while (i < args.length) {
      val arg = args(i)
      val lower = Fmt.asciiLower(arg)
      if (lower == "--ild" || lower == "--gate" || lower == "--gate-level" || lower == "--engine=ild" || lower == "--engine=gate") {
        system.setEngineMode(CcuEngineMode.Ild)
      } else if (lower == "--behavioral" || lower == "--isa" || lower == "--engine=behavioral" || lower == "--engine=isa") {
        system.setEngineMode(CcuEngineMode.Behavioral)
      } else if (lower == "--engine" && i + 1 < args.length) {
        CcuEngineMode.fromString(args(i + 1)).foreach(system.setEngineMode)
        i += 1
      } else if (lower == "--max" || lower == "--turbo" || lower == "--instant") {
        consoleState.realTimePacing = false
      } else if (lower == "--fast" || lower == "--2x") {
        consoleState.pacingSpeed = 2.0
      } else if (lower == "--5x") {
        consoleState.pacingSpeed = 5.0
      } else if (lower == "--operator" || lower == "--panel") {
        consoleState.faceplateInstalled = true
      } else if (!arg.startsWith("-")) {
        initialFile = Some(arg)
      }
      i += 1
    }
    initialFile
  }

  def main(args: Array[String]): Unit = {
    println("Initializing IBM MT/SC CCU 6320 Emulator...")

    val system = new MtscSystem(initialEngineMode = DefaultEngineMode)
    val consoleState = new CeConsoleState()
    consoleState.viewGalley = true
    val terminal = new AnsiTerminal(width = 104, height = 38)

    // Check command line arguments for initial text file or flags
    val initialFile = parseArgs(args, system, consoleState)
    consoleState.log(s"CCU Engine Mode: ${system.engineMode.label} (${system.engineMode.cliFlag}).")

    // Load initial demo: Continuous Document Composition
    loadProgram(DiagnosticPrograms.AllPrograms(0), system, consoleState)

    // If an initial file was specified, mount it
    initialFile.foreach { path =>
      mountFile(path, Device.RightTape, system, consoleState)
    }

    // Interactive event loop
    runInteractiveLoop(system, consoleState, terminal)
  }

  def loadProgram(
    prog: DiagnosticPrograms.ProgramInfo,
    system: MtscSystem,
    consoleState: CeConsoleState
  ): Unit = {
    val img = prog.assemble()
    system.loadProgram(img)
    EscapementRoutine.initializeTable(system.memory)

    if (prog == DiagnosticPrograms.ContinuousCompositionDemo) {
      if (!system.rightTape.isTapeLoaded) {
        system.loadTape(Device.RightTape, TapeCartridge.fromText(TapeCartridge.HistoricalSampleText, "Historical MT Tape"))
        consoleState.log("Mounted historical 4-paragraph MT cartridge on Right Tape Reader.")
      }
    } else if (prog == DiagnosticPrograms.EndToEndCompositionDemo || prog == DiagnosticPrograms.TapeBootstrapLoader) {
      val sampleText = "The electronic logic used in the CCU is in the SLD family."
      system.loadTape(Device.RightTape, TapeCartridge.fromText(sampleText, "MTSC Test Tape"))
      consoleState.log("Mounted sample tape cartridge on Right Tape Reader.")
    }

    if (prog == DiagnosticPrograms.TapeBootstrapLoader) {
      system.memory.writeReg(RegId(8), Word(200))
    }

    if (prog == DiagnosticPrograms.EscapementAndJustifyDemo) {
      val sampleText = "The logic in CCU is in the SLD family."
      for (i <- 0 until sampleText.length) {
        system.memory.writeByte(Address(316 + i), sampleText.charAt(i).toInt)
      }
      system.memory.writeByte(Address(316 + sampleText.length), 0)
    }

    consoleState.log(s"Program '${prog.name}' loaded at 0x${Fmt.hex3(system.memory.psar.unsigned)}.")
    val speedNote = if (consoleState.realTimePacing) " [Real-time 14 cps]" else " [Instant]"
    consoleState.statusMessage = s"Loaded ${prog.name}.$speedNote Press [r] to run, [a] to toggle pacing."
  }

  def mountFile(
    path: String,
    station: Device,
    system: MtscSystem,
    consoleState: CeConsoleState
  ): Boolean = {
    try {
      val content = scala.io.Source.fromFile(path).mkString
      val filename = new java.io.File(path).getName
      system.loadTape(station, TapeCartridge.fromText(content, filename))
      val stName = if (station == Device.LeftTape) "Left" else "Right"
      consoleState.log(s"Mounted '$filename' (${content.length} chars) on $stName Tape Reader.")
      consoleState.statusMessage = s"Tape mounted on $stName: '$filename' (${content.length} chars). Press [r] to compose."
      true
    } catch {
      case e: Exception =>
        consoleState.log(s"Error loading file '$path': ${e.getMessage}")
        consoleState.statusMessage = s"Failed to load file '$path'."
        false
    }
  }

  private def runInteractiveLoop(
    system: MtscSystem,
    consoleState: CeConsoleState,
    terminal: AnsiTerminal
  ): Unit = {
    var running = true

    // Enable raw terminal mode if possible
    val rawModeEnabled = trySetRawMode(enable = true)

    try {
      while (running) {
        CeConsoleView.render(terminal, system.cpu, consoleState)
        print(terminal.renderToString())
        System.out.flush()

        val input = readCommand(rawModeEnabled)
        running = handleCommand(
          input.trim,
          system,
          consoleState,
          terminal,
          rawModeEnabled,
          mountFile,
          () => runPaced(system, consoleState, terminal, rawModeEnabled)
        )
      }
    } finally {
      if (rawModeEnabled) {
        trySetRawMode(enable = false)
      }
      println("\nIBM MT/SC Emulator session ended.")
    }
  }

  def handleCommand(
    cmd: String,
    system: MtscSystem,
    consoleState: CeConsoleState,
    terminal: AnsiTerminal,
    rawMode: Boolean,
    mountFileFn: (String, Device, MtscSystem, CeConsoleState) => Boolean = (_, _, _, _) => false,
    runFn: () => Unit = () => ()
  ): Boolean = {
    val lower = Fmt.asciiLower(cmd)
    if (lower == "q" || lower == "quit" || lower == "exit") {
      return false
    }

    // If CE Chassis is exposed and Edit Selector is in Addr or Data position, route console keys 0..9 and R to hardware bit entry
    if (
      !consoleState.faceplateInstalled &&
      (consoleState.editSelector == CeEditSelector.Addr || consoleState.editSelector == CeEditSelector.Data) &&
      lower.length == 1
    ) {
      val ch = lower.charAt(0)
      if (ch.isDigit || ch == 'r') {
        ConsoleKey.fromChar(ch).foreach { key =>
          consoleState.pressCeKey(key, system.cpu)
        }
        return true
      }
    }

    // If Operator Faceplate is installed and digits 0..9 or START/R are pressed during Setup
    if (consoleState.faceplateInstalled && lower.length == 1 && lower.charAt(0).isDigit) {
      val digit = lower.charAt(0) - '0'
      ConsoleKey.fromDigit(digit).foreach { key =>
        val msg = system.operatorPanel.pressKey(key)
        consoleState.statusMessage = msg
        consoleState.log(s"Operator Keypad: $msg")
      }
      return true
    }

    if (consoleState.faceplateInstalled && (lower == "" || lower == "c" || lower == "start") && system.operatorPanel.activeSetupParam.isDefined) {
      val msg = system.operatorPanel.pressKey(ConsoleKey.Start)
      consoleState.statusMessage = msg
      consoleState.log(s"Operator START: $msg")
      return true
    }

    lower match {
      case "f" | "faceplate" =>
        consoleState.toggleFaceplate()

      case "s" | "" =>
        // Single step
        val prevAddr = system.memory.psar
        val res = system.step()
        val insnStr = system.cpu.state.lastInsn.map(_.toString).getOrElse("")
        consoleState.log(s"Step 0x${Fmt.hex3(prevAddr.unsigned)}: $insnStr")
        res match {
          case StepResult.Halted =>
            consoleState.statusMessage = s"Program HALTED at 0x${Fmt.hex3(system.memory.psar.unsigned)}."
            consoleState.log("CPU entered halt loop.")
          case StepResult.Stalled =>
            consoleState.statusMessage = "I/O Stalled (Inhibit latch active - waiting for peripheral)."
          case StepResult.Error(err) =>
            consoleState.statusMessage = s"Execution Error: $err"
            consoleState.log(s"ERROR: $err")
          case StepResult.Normal =>
            consoleState.statusMessage = s"Stepped to 0x${Fmt.hex3(system.memory.psar.unsigned)}."
        }

      case "r" | "run" =>
        runFn()

      case "a" | "anim" | "pacing" =>
        consoleState.customPrintDelayMs = 0
        if (!consoleState.realTimePacing) {
          consoleState.realTimePacing = true
          consoleState.pacingSpeed = 1.0
        } else if (consoleState.pacingSpeed == 1.0) {
          consoleState.pacingSpeed = 2.0
        } else if (consoleState.pacingSpeed == 2.0) {
          consoleState.pacingSpeed = 5.0
        } else {
          consoleState.realTimePacing = false
        }
        val stateStr = if (consoleState.realTimePacing) {
          s"ENABLED at ${consoleState.pacingSpeed.toInt}x (${(14 * consoleState.pacingSpeed).toInt} cps)"
        } else {
          "DISABLED (instant/max speed)"
        }
        consoleState.statusMessage = s"Mechanical pacing: $stateStr."
        consoleState.log(s"Pacing $stateStr")

      case s if s.startsWith("delay ") || s.startsWith("d ") =>
        val arg = s.substring(s.indexOf(' ') + 1).trim
        try {
          val ms = Integer.parseInt(arg)
          if (ms <= 0) {
            consoleState.customPrintDelayMs = 0
            consoleState.realTimePacing = false
            consoleState.statusMessage = "Character typing delay set to 0 ms (instant)."
            consoleState.log("Character typing delay disabled (0 ms).")
          } else {
            consoleState.customPrintDelayMs = ms
            consoleState.realTimePacing = true
            val msg = s"Character typing delay set to ${ms} ms/char."
            consoleState.statusMessage = msg
            consoleState.log(msg)
          }
        } catch {
          case _: Exception =>
            consoleState.statusMessage = s"Invalid delay '$arg'. Usage: delay <ms> (e.g. delay 220)."
        }

      case "1" =>
        consoleState.selectDisplayMode(CeDisplayMode.Display1)

      case "2" =>
        consoleState.selectDisplayMode(CeDisplayMode.Display2)

      case "3" =>
        consoleState.selectDisplayMode(CeDisplayMode.Display3)

      case "4" =>
        consoleState.selectDisplayMode(CeDisplayMode.Display4)

      case "0" =>
        consoleState.selectDisplayMode(CeDisplayMode.ClockDefault)

      case "5" =>
        consoleState.realTimePacing = true
        consoleState.pacingSpeed = 5.0
        consoleState.statusMessage = "Speed set to 5x (70 cps fast forward)."
        consoleState.log("Speed set to 5x (70 cps)")

      case "max" =>
        consoleState.realTimePacing = false
        consoleState.statusMessage = "Pacing disabled (instant execution)."
        consoleState.log("Pacing disabled (instant)")

      case "g" | "granularity" =>
        consoleState.cycleStepGranularity()

      case "m" | "mode" =>
        consoleState.cycleEditSelector(Some(system.cpu))

      case "clr" | "clear" =>
        consoleState.pressCeClear(system.cpu)

      case "z" | "zero" =>
        system.clearMemory()
        consoleState.memoryInspectionAddress = Address(0)
        consoleState.log("Zeroed all 2,048 bytes of Core Memory (0x000..0x7FF = 0x00, PSAR = 0x000).")
        consoleState.statusMessage = "Core Memory zeroed (PSAR = 0x000). Key INITIAL LOADER [i] and mount Program Tape [t prog] to cold boot."

      case s if s == "i" || s == "init" || s == "loader" || s == "i right" || s == "i left" =>
        val isLeft = s.endsWith("left") || (s == "i" && system.leftTape.isTapeLoaded && !system.rightTape.isTapeLoaded)
        InitialLoaderTable.loadIntoMemory(system.memory, paperTape = false, leftStation = isLeft)
        system.cpu.state.mal = Address(42)
        val stName = if (isLeft) "Left Station (m=1)" else "Right Station (m=0)"
        consoleState.log(s"Keyed 8-byte INITIAL LOADER for $stName into Core Memory (bytes 40..47, PSAR=42).")
        consoleState.statusMessage = s"INITIAL LOADER ($stName) keyed into memory (PSAR = 0x02A)."

      case "p" | "setup" =>
        val nextParam = system.operatorPanel.advanceSetupPrompt(includeSecondary = true)
        nextParam match {
          case Some(param) =>
            consoleState.statusMessage = s"Setup [${param.label}]: ${param.description}. Key digits then Enter/c (START)."
            consoleState.log(s"Operator Setup prompt: ${param.label}")
          case None =>
            consoleState.statusMessage = "Operator Setup sequence complete. Press [r] to run composition."
            consoleState.log("Operator Setup sequence complete.")
        }

      case "k" | "linestop" =>
        val msg = system.operatorPanel.pressKey(ConsoleKey.KeyR)
        consoleState.statusMessage = msg
        consoleState.log(msg)

      case "u" | "transport" =>
        val buttons = Array(
          TapeConsoleButton.Reread,
          TapeConsoleButton.Skip,
          TapeConsoleButton.Search,
          TapeConsoleButton.UnloadLeft,
          TapeConsoleButton.LoadLeft,
          TapeConsoleButton.UnloadRight,
          TapeConsoleButton.CoverUnlock,
          TapeConsoleButton.LoadRight
        )
        val btn = buttons(currentTapeButtonIndex % buttons.length)
        currentTapeButtonIndex += 1
        val msg = system.pressTapeButton(btn)
        consoleState.statusMessage = msg
        consoleState.log(s"Tape Console [${btn.topLabel} ${btn.bottomLabel}]: $msg")

      case "v" =>
        consoleState.viewGalley = !consoleState.viewGalley
        if (consoleState.viewGalley) {
          consoleState.statusMessage = "Switched right panel to Selectric Composer Galley Paper Proof."
          consoleState.log("Display switched to live Galley Proof.")
        } else {
          consoleState.statusMessage = "Switched right panel to Core Storage Hex/ASCII Dump."
          consoleState.log("Display switched to Core Storage Dump.")
        }

      case "t" =>
        system.loadTape(Device.RightTape, TapeCartridge.fromText(TapeCartridge.HistoricalSampleText, "Historical MT Tape"))
        consoleState.log("Mounted historical 4-paragraph tape on Right Tape Reader.")
        consoleState.statusMessage = "Mounted historical 4-paragraph tape cartridge on Right Tape Reader."

      case "t prog" | "tape prog" | "t right prog" | "tape right prog" =>
        val cart = MtscSystem.createFactoryProgramTape(Device.RightTape)
        system.loadTape(Device.RightTape, cart)
        consoleState.log(s"Mounted ${cart.label} (${cart.length} tracks) on Right Tape Reader.")
        consoleState.statusMessage = s"Mounted ${cart.label} on Right Station. Key [i right] and [r] to cold boot."

      case "t left prog" | "tape left prog" =>
        val cart = MtscSystem.createFactoryProgramTape(Device.LeftTape)
        system.loadTape(Device.LeftTape, cart)
        consoleState.log(s"Mounted ${cart.label} (${cart.length} tracks) on Left Tape Reader.")
        consoleState.statusMessage = s"Mounted ${cart.label} on Left Station. Key [i left] and [r] to cold boot."

      case "t left" | "tape left" =>
        system.loadTape(Device.LeftTape, TapeCartridge.fromText(TapeCartridge.HistoricalMergeSampleText, "Merge Correction Tape"))
        consoleState.log("Mounted correction tape on Left Tape Reader.")
        consoleState.statusMessage = "Mounted correction tape cartridge on Left Tape Reader."

      case "t right" | "tape right" =>
        system.loadTape(Device.RightTape, TapeCartridge.fromText(TapeCartridge.HistoricalSampleText, "Historical MT Tape"))
        consoleState.log("Mounted historical 4-paragraph tape on Right Tape Reader.")
        consoleState.statusMessage = "Mounted historical 4-paragraph tape cartridge on Right Tape Reader."

      case s if s.startsWith("t ") || s.startsWith("tape ") =>
        val rest = s.substring(s.indexOf(' ') + 1).trim
        val (station, path) =
          if (rest.startsWith("left ")) (Device.LeftTape, rest.substring(5).trim)
          else if (rest.startsWith("right ")) (Device.RightTape, rest.substring(6).trim)
          else (Device.RightTape, rest)
        mountFileFn(path, station, system, consoleState)

      case "e" =>
        val targetTape = if (system.leftTape.isTapeLoaded && !system.rightTape.isTapeLoaded) system.leftTape else system.rightTape
        val targetName = if (targetTape eq system.leftTape) "Left" else "Right"
        if (targetTape.injectParityError()) {
          consoleState.log(s"Injected parity error on track ${targetTape.headPosition} of $targetName Tape.")
          consoleState.statusMessage = s"Parity error injected on $targetName track ${targetTape.headPosition}. [ERR] lamp will light on next read."
        } else {
          consoleState.statusMessage = s"Cannot inject parity error: no $targetName tape mounted or end reached."
        }

      case "e left" =>
        if (system.leftTape.injectParityError()) {
          consoleState.log(s"Injected parity error on track ${system.leftTape.headPosition} of Left Tape.")
          consoleState.statusMessage = s"Parity error injected on track ${system.leftTape.headPosition} of Left Tape."
        } else {
          consoleState.statusMessage = "Cannot inject parity error: no Left Tape mounted or end reached."
        }

      case "e right" =>
        if (system.rightTape.injectParityError()) {
          consoleState.log(s"Injected parity error on track ${system.rightTape.headPosition} of Right Tape.")
          consoleState.statusMessage = s"Parity error injected on track ${system.rightTape.headPosition} of Right Tape."
        } else {
          consoleState.statusMessage = "Cannot inject parity error: no Right Tape mounted or end reached."
        }

      case "x" | "reset" =>
        system.reset()
        consoleState.log("CCU & I/O Bus Reset to power-on state (PSAR = 0x222).")
        consoleState.statusMessage = "CPU Reset."

      case "engine" =>
        val mode = system.toggleEngineMode()
        consoleState.log(s"Switched CCU Engine to ${mode.label} (${mode.cliFlag}).")
        consoleState.statusMessage = s"CCU Engine switched to ${mode.label}."

      case "ild" | "engine ild" | "engine gate" =>
        system.setEngineMode(CcuEngineMode.Ild)
        consoleState.log(s"Switched CCU Engine to ${CcuEngineMode.Ild.label} (--ild).")
        consoleState.statusMessage = s"CCU Engine set to ${CcuEngineMode.Ild.label}."

      case "behavioral" | "isa" | "engine behavioral" | "engine isa" =>
        system.setEngineMode(CcuEngineMode.Behavioral)
        consoleState.log(s"Switched CCU Engine to ${CcuEngineMode.Behavioral.label} (--behavioral).")
        consoleState.statusMessage = s"CCU Engine set to ${CcuEngineMode.Behavioral.label}."

      case "l" | "load" =>
        val all = DiagnosticPrograms.AllPrograms
        currentProgramIndex = (currentProgramIndex + 1) % all.length
        val nextProg = all(currentProgramIndex)
        loadProgram(nextProg, system, consoleState)

      case s if s.startsWith("b ") || s.startsWith("bp ") =>
        val arg = s.substring(s.indexOf(' ') + 1).trim
        if (arg.nonEmpty) {
          try {
            val addrInt = Integer.parseInt(arg.replace("0x", "").replace("0X", ""), 16)
            consoleState.toggleBreakpoint(Address(addrInt))
            consoleState.statusMessage = s"Toggled breakpoint at 0x${Fmt.hex3(addrInt)}."
          } catch {
            case _: Exception => consoleState.statusMessage = "Invalid hex address."
          }
        }

      case s if s.startsWith("j ") || s.startsWith("mem ") =>
        val arg = s.substring(s.indexOf(' ') + 1).trim
        if (arg.nonEmpty) {
          try {
            val addrInt = Integer.parseInt(arg.replace("0x", "").replace("0X", ""), 16)
            consoleState.memoryInspectionAddress = Address(addrInt)
            consoleState.statusMessage = s"Memory view set to 0x${Fmt.hex3(addrInt)}."
          } catch {
            case _: Exception => consoleState.statusMessage = "Invalid hex address."
          }
        }

      case _ =>
        consoleState.statusMessage = s"Unknown command '$cmd'. Press [f] Faceplate, [1-4/0] Display, [s] Step, [r] Run, [p] Setup, [u] Tape, [q] Quit."
    }
    true
  }

  private def runPaced(
    system: MtscSystem,
    consoleState: CeConsoleState,
    terminal: AnsiTerminal,
    rawMode: Boolean
  ): Unit = {
    if (system.memory.psar.unsigned >= 42 && system.memory.psar.unsigned <= 47) {
      val bootRes = system.runBootLoader()
      bootRes match {
        case StepResult.Normal =>
          consoleState.log(s"Cold boot complete: loaded program from tape, CPU at 0x${Fmt.hex3(system.memory.psar.unsigned)}.")
          consoleState.statusMessage = s"Cold boot complete! CPU ready at 0x${Fmt.hex3(system.memory.psar.unsigned)}. Mount text tape [t] and [r] to compose."
        case other =>
          consoleState.statusMessage = s"Boot loader stopped: $other"
      }
      return
    }

    var steps = 0
    var hitBreakpoint = false
    var res: StepResult = StepResult.Normal
    var interrupted = false
    val delayMs = consoleState.printDelayMs

    if (consoleState.realTimePacing && delayMs > 0) {
      print("\u001b[?25l") // Hide cursor during animated typing
      System.out.flush()
    }

    try {
      var lastPrintCount = system.translator.printCount

      while (res == StepResult.Normal && !hitBreakpoint && !interrupted && steps < 100000) {
        val prevPsar = system.memory.psar
        res = system.step()
        steps += 1

        if (consoleState.breakpoints.contains(system.memory.psar)) {
          hitBreakpoint = true
        }

        // Animated typing refresh: whenever translator prints a character
        if (consoleState.realTimePacing && delayMs > 0 && system.translator.printCount > lastPrintCount) {
          lastPrintCount = system.translator.printCount

          CeConsoleView.render(terminal, system.cpu, consoleState)
          print(terminal.renderToString())
          System.out.flush()

          try {
            Thread.sleep(delayMs)
          } catch {
            case _: InterruptedException => interrupted = true
          }

          // Check if user hit a key to interrupt
          if (tryCheckInputAvailable()) {
            val _ = System.in.read()
            interrupted = true
          }
        }

        if (!system.cpu.state.multiplex && system.memory.psar == prevPsar && !system.cpu.state.inhibit) {
          system.cpu.state.halted = true
          res = StepResult.Halted
        }
      }
    } finally {
      if (consoleState.realTimePacing && delayMs > 0) {
        print("\u001b[?25h") // Restore cursor
        System.out.flush()
      }
    }

    if (interrupted) {
      consoleState.statusMessage = s"Execution paused by user at 0x${Fmt.hex3(system.memory.psar.unsigned)} after $steps steps."
      consoleState.log(s"Execution paused at 0x${Fmt.hex3(system.memory.psar.unsigned)}.")
    } else if (hitBreakpoint) {
      consoleState.statusMessage = s"Breakpoint hit at 0x${Fmt.hex3(system.memory.psar.unsigned)} after $steps steps."
      consoleState.log(s"Breakpoint hit at 0x${Fmt.hex3(system.memory.psar.unsigned)}.")
    } else if (res == StepResult.Halted) {
      consoleState.statusMessage = s"Composition completed in $steps cycles (${system.paper.lines.length} lines on galley proof)."
      consoleState.log(s"Program HALTED after $steps cycles. ${system.paper.lines.length} lines printed.")
    } else {
      consoleState.statusMessage = s"Ran $steps steps."
    }
  }

  private def tryCheckInputAvailable(): Boolean = {
    try {
      System.in.available() > 0
    } catch {
      case _: Exception => false
    }
  }

  private def trySetRawMode(enable: Boolean): Boolean = {
    try {
      val cmd = if (enable) Array("sh", "-c", "stty -echo -icanon min 1 < /dev/tty")
                else Array("sh", "-c", "stty sane < /dev/tty")
      val p = java.lang.Runtime.getRuntime.exec(cmd)
      p.waitFor() == 0
    } catch {
      case _: Exception => false
    }
  }

  private def readCommand(rawMode: Boolean): String = {
    try {
      if (rawMode) {
        val b = System.in.read()
        if (b < 0) "q" else b.toChar.toString
      } else {
        val line = scala.io.StdIn.readLine()
        if (line == null) "q" else line
      }
    } catch {
      case _: Exception => "q"
    }
  }
}
