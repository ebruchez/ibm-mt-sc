package mtsc.cli

import mtsc.types.*
import mtsc.cpu.{Ccu, MockIoBus, StepResult}
import mtsc.memory.CoreMemory
import mtsc.diag.{DiagnosticPrograms, EscapementRoutine}
import mtsc.console.{AnsiTerminal, CeConsoleState, CeConsoleView, CeConsoleMode}

/**
 * Main Interactive Customer Engineer Console Debugger for the IBM MT/SC CCU.
 *
 * Supports single-step execution, continuous run, breakpoints, program loading,
 * memory inspection, and live hardware display lamps.
 */
object Main {

  def main(args: Array[String]): Unit = {
    println("Initializing IBM MT/SC CCU 6320 Emulator...")

    val memory = CoreMemory.empty()
    val ioBus = new MockIoBus()
    val cpu = new Ccu(memory, ioBus)
    val consoleState = new CeConsoleState()
    val terminal = new AnsiTerminal(width = 104, height = 38)

    // Load initial demo: Escapement & Justification
    loadProgram(DiagnosticPrograms.EscapementAndJustifyDemo, cpu, consoleState)

    // Interactive event loop
    runInteractiveLoop(cpu, consoleState, terminal)
  }

  def loadProgram(
    prog: DiagnosticPrograms.ProgramInfo,
    cpu: Ccu,
    consoleState: CeConsoleState
  ): Unit = {
    val img = prog.assemble()
    cpu.memory.loadFromImage(img)
    cpu.reset()

    // If it's the justification demo or uses escapement, populate font table & sample text
    if (prog == DiagnosticPrograms.EscapementAndJustifyDemo) {
      EscapementRoutine.initializeTable(cpu.memory)
      val sampleText = "The logic in CCU is in the SLD family."
      for (i <- 0 until sampleText.length) {
        cpu.memory.writeByte(Address(316 + i), sampleText.charAt(i).toInt)
      }
      cpu.memory.writeByte(Address(316 + sampleText.length), 0) // Null terminator
    } else if (prog == DiagnosticPrograms.TapeBootstrapLoader) {
      cpu.memory.writeReg(RegId(8), Word(200))
      cpu.ioBus.asInstanceOf[MockIoBus].queueInput(
        Device.RightTape,
        'I'.toInt, 'B'.toInt, 'M'.toInt, ' '.toInt, 'M'.toInt, 'T'.toInt, '/', 'S'.toInt, 'C'.toInt, 0xFF
      )
    }

    consoleState.log(f"Program '${prog.name}' loaded at 0x${cpu.memory.psar.unsigned}%03X.")
    consoleState.statusMessage = s"Loaded ${prog.name}. Press [s] to step, [r] to run."
  }

  private def runInteractiveLoop(
    cpu: Ccu,
    consoleState: CeConsoleState,
    terminal: AnsiTerminal
  ): Unit = {
    var running = true

    // Enable raw terminal mode if possible
    val rawModeEnabled = trySetRawMode(enable = true)

    try {
      while (running) {
        CeConsoleView.render(terminal, cpu, consoleState)
        print(terminal.renderToString())
        System.out.flush()

        val input = readCommand(rawModeEnabled)
        running = handleCommand(input.trim, cpu, consoleState)
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
    cpu: Ccu,
    consoleState: CeConsoleState
  ): Boolean = {
    if (cmd.equalsIgnoreCase("q") || cmd.equalsIgnoreCase("quit") || cmd.equalsIgnoreCase("exit")) {
      return false
    }

    cmd.toLowerCase match {
      case "s" | "" =>
        // Single step
        val prevAddr = cpu.memory.psar
        val res = cpu.step()
        val insnStr = cpu.state.lastInsn.map(_.toString).getOrElse("")
        consoleState.log(f"Step 0x${prevAddr.unsigned}%03X: $insnStr")
        res match {
          case StepResult.Halted =>
            consoleState.statusMessage = f"Program HALTED at 0x${cpu.memory.psar.unsigned}%03X."
            consoleState.log("CPU entered halt loop.")
          case StepResult.Stalled =>
            consoleState.statusMessage = "I/O Stalled (Inhibit latch active - waiting for peripheral)."
          case StepResult.Error(err) =>
            consoleState.statusMessage = s"Execution Error: $err"
            consoleState.log(s"ERROR: $err")
          case StepResult.Normal =>
            consoleState.statusMessage = f"Stepped to 0x${cpu.memory.psar.unsigned}%03X."
        }

      case "r" | "run" =>
        // Run continuously until breakpoint or halt
        var steps = 0
        var hitBreakpoint = false
        var res: StepResult = StepResult.Normal

        while (res == StepResult.Normal && !hitBreakpoint && steps < 50000) {
          res = cpu.step()
          steps += 1
          if (consoleState.breakpoints.contains(cpu.memory.psar)) {
            hitBreakpoint = true
          }
        }

        if (hitBreakpoint) {
          consoleState.statusMessage = f"Breakpoint hit at 0x${cpu.memory.psar.unsigned}%03X after $steps steps."
          consoleState.log(f"Breakpoint hit at 0x${cpu.memory.psar.unsigned}%03X")
        } else if (res == StepResult.Halted) {
          consoleState.statusMessage = f"Program completed in $steps steps (HALTED)."
          consoleState.log(f"Execution halted after $steps cycles.")
        } else {
          consoleState.statusMessage = f"Ran $steps steps."
        }

      case "m" | "mode" =>
        consoleState.cycleMode()

      case "x" | "reset" =>
        cpu.reset()
        consoleState.log("CCU Reset to power-on state (PSAR = 0x222).")
        consoleState.statusMessage = "CPU Reset."

      case "p" | "pause" =>
        consoleState.statusMessage = "Execution paused."

      case "l" | "load" =>
        // Cycle to next demo program
        val all = DiagnosticPrograms.AllPrograms
        val currentIdx = all.indexWhere(p => p.source == cpu.state.lastInsn.toString)
        val nextProg = all((currentIdx + 1) % all.length)
        loadProgram(nextProg, cpu, consoleState)

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
        consoleState.statusMessage = s"Unknown command '$cmd'. Press [s] Step, [r] Run, [l] Load, [q] Quit."
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
