package mtsc.cli

import mtsc.types.*
import mtsc.cpu.StepResult
import mtsc.memory.CoreMemory
import mtsc.periph.{MtscSystem, SystemIoBus, TapeCartridge, TapeReader}
import mtsc.diag.{DiagnosticPrograms, EscapementRoutine}
import mtsc.console.{AnsiTerminal, CeConsoleState, CeConsoleView, CeConsoleMode}

/**
 * Main Interactive Customer Engineer Console Debugger for the IBM MT/SC CCU.
 *
 * Supports single-step execution, continuous run, breakpoints, program loading,
 * memory inspection, live hardware display lamps, dual tape readers,
 * and Selectric Composer camera-ready galley proofs.
 */
object Main {

  private var currentProgramIndex: Int = 0

  def main(args: Array[String]): Unit = {
    println("Initializing IBM MT/SC CCU 6320 Emulator...")

    val system = new MtscSystem()
    val consoleState = new CeConsoleState()
    val terminal = new AnsiTerminal(width = 104, height = 38)

    // Load initial demo: End-to-End Composition Demo
    loadProgram(DiagnosticPrograms.AllPrograms(0), system, consoleState)

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

    if (prog == DiagnosticPrograms.EndToEndCompositionDemo || prog == DiagnosticPrograms.TapeBootstrapLoader) {
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
      system.memory.writeByte(Address(316 + sampleText.length), 0) // Null terminator
    }

    consoleState.log(f"Program '${prog.name}' loaded at 0x${system.memory.psar.unsigned}%03X.")
    consoleState.statusMessage = s"Loaded ${prog.name}. Press [s] to step, [r] to run."
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
        running = handleCommand(input.trim, system, consoleState)
      }
    } finally {
      if (rawModeEnabled) {
        trySetRawMode(enable = false)
      }
      println("\nIBM MT/SC Emulator session ended.")
    }
  }

  private def handleCommand(
    cmd: String,
    system: MtscSystem,
    consoleState: CeConsoleState
  ): Boolean = {
    if (cmd.equalsIgnoreCase("q") || cmd.equalsIgnoreCase("quit") || cmd.equalsIgnoreCase("exit")) {
      return false
    }

    cmd.toLowerCase match {
      case "s" | "" =>
        // Single step
        val prevAddr = system.memory.psar
        val res = system.step()
        val insnStr = system.cpu.state.lastInsn.map(_.toString).getOrElse("")
        consoleState.log(f"Step 0x${prevAddr.unsigned}%03X: $insnStr")
        res match {
          case StepResult.Halted =>
            consoleState.statusMessage = f"Program HALTED at 0x${system.memory.psar.unsigned}%03X."
            consoleState.log("CPU entered halt loop.")
          case StepResult.Stalled =>
            consoleState.statusMessage = "I/O Stalled (Inhibit latch active - waiting for peripheral)."
          case StepResult.Error(err) =>
            consoleState.statusMessage = s"Execution Error: $err"
            consoleState.log(s"ERROR: $err")
          case StepResult.Normal =>
            consoleState.statusMessage = f"Stepped to 0x${system.memory.psar.unsigned}%03X."
        }

      case "r" | "run" =>
        // Run continuously until breakpoint, halt, or maxCycles
        var steps = 0
        var hitBreakpoint = false
        var res: StepResult = StepResult.Normal

        while (res == StepResult.Normal && !hitBreakpoint && steps < 50000) {
          val prevPsar = system.memory.psar
          res = system.step()
          steps += 1
          if (consoleState.breakpoints.contains(system.memory.psar)) {
            hitBreakpoint = true
          }
          if (!system.cpu.state.multiplex && system.memory.psar == prevPsar && !system.cpu.state.inhibit) {
            system.cpu.state.halted = true
            res = StepResult.Halted
          }
        }

        if (hitBreakpoint) {
          consoleState.statusMessage = f"Breakpoint hit at 0x${system.memory.psar.unsigned}%03X after $steps steps."
          consoleState.log(f"Breakpoint hit at 0x${system.memory.psar.unsigned}%03X")
        } else if (res == StepResult.Halted) {
          consoleState.statusMessage = f"Program completed in $steps steps (HALTED)."
          consoleState.log(f"Execution halted after $steps cycles.")
        } else {
          consoleState.statusMessage = f"Ran $steps steps."
        }

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
        val sampleText = "The electronic logic used in the CCU is in the SLD family."
        system.loadTape(Device.RightTape, TapeCartridge.fromText(sampleText, "MTSC Test Tape"))
        consoleState.log("Mounted sample tape cartridge on Right Tape Reader.")
        consoleState.statusMessage = "Mounted sample tape cartridge on Right Tape Reader."

      case "e" =>
        if (system.rightTape.injectParityError()) {
          consoleState.log(f"Injected parity error on track ${system.rightTape.headPosition} of Right Tape.")
          consoleState.statusMessage = f"Parity error injected on track ${system.rightTape.headPosition}. [ERR] lamp will light on next read."
        } else {
          consoleState.statusMessage = "Cannot inject parity error: no tape mounted or end reached."
        }

      case "m" | "mode" =>
        consoleState.cycleMode()

      case "x" | "reset" =>
        system.reset()
        consoleState.log("CCU & I/O Bus Reset to power-on state (PSAR = 0x222).")
        consoleState.statusMessage = "CPU Reset."

      case "p" | "pause" =>
        consoleState.statusMessage = "Execution paused."

      case "l" | "load" =>
        val all = DiagnosticPrograms.AllPrograms
        currentProgramIndex = (currentProgramIndex + 1) % all.length
        val nextProg = all(currentProgramIndex)
        loadProgram(nextProg, system, consoleState)

      case s if s.startsWith("b ") || s.startsWith("bp ") =>
        val parts = s.split("\\s+")
        if (parts.length > 1) {
          try {
            val addrInt = Integer.parseInt(parts(1).replace("0x", ""), 16)
            consoleState.toggleBreakpoint(Address(addrInt))
            consoleState.statusMessage = f"Toggled breakpoint at 0x$addrInt%03X."
          } catch {
            case _: Exception => consoleState.statusMessage = "Invalid hex address."
          }
        }

      case s if s.startsWith("j ") || s.startsWith("mem ") =>
        val parts = s.split("\\s+")
        if (parts.length > 1) {
          try {
            val addrInt = Integer.parseInt(parts(1).replace("0x", ""), 16)
            consoleState.memoryInspectionAddress = Address(addrInt)
            consoleState.statusMessage = f"Memory view set to 0x$addrInt%03X."
          } catch {
            case _: Exception => consoleState.statusMessage = "Invalid hex address."
          }
        }

      case _ =>
        consoleState.statusMessage = s"Unknown command '$cmd'. Press [s] Step, [r] Run, [v] Galley, [t] Tape, [e] Err, [l] Load, [q] Quit."
    }
    true
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
