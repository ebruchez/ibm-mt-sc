package mtsc.rp2040

import scala.scalanative.libc.stdio
import scala.scalanative.runtime.GC
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import mtsc.cli.Main
import mtsc.console.{AnsiTerminal, CeConsoleState, CeConsoleView}
import mtsc.cpu.{CcuEngineMode, StepResult}
import mtsc.diag.DiagnosticPrograms
import mtsc.periph.MtscSystem
import mtsc.types.Device

/**
 * C wrappers for Pico SDK GPIO, PIO WS2812 NeoPixel, and non-blocking USB CDC / UART serial I/O
 * provided by `firmware/main.c`.
 */
@extern
object PicoSdk {
  def pico_default_led_pin(): CUnsignedInt = extern
  def pico_led_init(pin: CUnsignedInt): Unit = extern
  def pico_led_put(pin: CUnsignedInt, value: CBool): Unit = extern
  def pico_neopixel_init(): Unit = extern
  def pico_neopixel_set_rgb(r: CUnsignedInt, g: CUnsignedInt, b: CUnsignedInt): Unit = extern
  def pico_getchar_timeout_us(timeoutUs: CUnsignedInt): CInt = extern
  def pico_stdio_usb_connected(): CBool = extern
  def sleep_ms(ms: CUnsignedInt): Unit = extern
}

/**
 * Bare-metal Stage 1 entry point for the **Adafruit Feather RP2040** (`thumbv6m-none-eabi`, Cortex-M0+, 264 KB SRAM).
 *
 * Implements Stage 1 (Zero Extra Hardware — Feather RP2040 + USB-C Cable):
 * 1. **Interactive ANSI Customer Engineer (CE) Console over USB-C Serial**:
 *    Routes [`AnsiTerminal`] and [`CeConsoleView`] over USB CDC (`stdio_usb`) and UART0 (`stdio_uart`).
 * 2. **Onboard RGB NeoPixel (`WS2812`, GPIO 16) + Red Activity LED (`GPIO 13`) Status Lamps**:
 *    - **Red (`LITE 9` / `+RESET`)**: Parity Error (`ERR 2`) or Instruction Check Error (`ERR 3`)
 *    - **Blue pulse (`+MT CALL`)**: Magnetic Tape `6310` stepping a frame
 *    - **Amber (`LITE 11`)**: Waiting for Operator (`Inhibit` latch active or Setup prompt active)
 *    - **Green (`+RUN`)**: `240 kHz` Oscillator / CCU Clock running
 *    - **Dim Cyan**: Ready / idle at Customer Engineer console
 * 3. **RP2040 SRAM & Immix GC Tuning**:
 *    - Disables the Web UI oscilloscope ring buffer (`ild.timingTrace.enabled = false`) for a zero-allocation
 *      `stepBitTime()` hot loop.
 *    - Caps retained galley lines (`paper.maxRetainedLines = 24`) and disables per-character `GalleyGlyph`
 *      allocations (`paper.recordGlyphs = false`).
 */
object Rp2040Main {

  private var ledPin: CUnsignedInt = 13.toUInt
  private var ledToggle: Boolean = false

  /**
   * Updates the Feather RP2040 onboard NeoPixel (`WS2812` on GPIO 16) and Red LED (`GPIO 13`)
   * from the live `6320` CCU backplane signals.
   */
  private def updateHardwareLamps(
    system: MtscSystem,
    isRunningLoop: Boolean = false,
    mtPulse: Boolean = false
  ): Unit = {
    val s = system.cpu.ild.signals
    val hasError =
      s.errorLite || s.lite9 || system.rightTape.parityError || system.leftTape.parityError
    val isInhibit =
      s.inh || s.lite11 || system.cpu.state.inhibit || system.operatorPanel.activeSetupParam.isDefined
    val isMtActive = mtPulse || s.mtRd || s.mtSch || s.mtCycClutch || s.mtStepMags
    val isRun = isRunningLoop || (s.start && !s.halt && !system.cpu.state.halted)

    if (hasError) {
      // Red (LITE 9 / +RESET): Parity or Instruction Check Error
      PicoSdk.pico_neopixel_set_rgb(64.toUInt, 0.toUInt, 0.toUInt)
      PicoSdk.pico_led_put(ledPin, true)
    } else if (isMtActive) {
      // Blue pulse (+MT CALL): Magnetic Tape 6310 stepping a frame
      PicoSdk.pico_neopixel_set_rgb(0.toUInt, 16.toUInt, 80.toUInt)
      PicoSdk.pico_led_put(ledPin, true)
    } else if (isInhibit) {
      // Amber (LITE 11): Waiting for Operator (Inhibit)
      PicoSdk.pico_neopixel_set_rgb(64.toUInt, 20.toUInt, 0.toUInt)
      PicoSdk.pico_led_put(ledPin, true)
    } else if (isRun) {
      // Green (+RUN): 240 kHz Clock Running
      PicoSdk.pico_neopixel_set_rgb(0.toUInt, 48.toUInt, 0.toUInt)
      PicoSdk.pico_led_put(ledPin, ledToggle)
    } else {
      // Dim Cyan: Ready in Customer Engineer console
      PicoSdk.pico_neopixel_set_rgb(0.toUInt, 8.toUInt, 14.toUInt)
      PicoSdk.pico_led_put(ledPin, false)
    }
  }

  private def heapSummary(): String = {
    val usedKb = GC.getUsedHeapSize().toLong / 1024L
    val maxKb = GC.getMaxHeapSize().toLong / 1024L
    val collections = GC.getStatsCollectionTotal()
    s"Heap: ${usedKb}KB/${maxKb}KB (GC #$collections)"
  }

  @exported
  def mtsc_rp2040_main(): Unit = {
    ledPin = PicoSdk.pico_default_led_pin()
    PicoSdk.pico_led_init(ledPin)
    PicoSdk.pico_neopixel_init()
    PicoSdk.pico_neopixel_set_rgb(0.toUInt, 12.toUInt, 24.toUInt)

    val system = new MtscSystem(initialEngineMode = CcuEngineMode.Ild)
    // Stage 1 SRAM / Immix GC optimizations for RP2040 (264 KB SRAM):
    // 1. Disable 240 kHz sub-phase oscilloscope sample allocation in IldCcu.stepBitTime()
    system.cpu.ild.timingTrace.enabled = false
    // 2. Cap retained galley lines and skip Web-UI GalleyGlyph coordinate lists
    system.paper.maxRetainedLines = 24
    system.paper.recordGlyphs = false

    val consoleState = new CeConsoleState()
    consoleState.viewGalley = true
    val terminal = new AnsiTerminal(width = 104, height = 38)

    Main.loadProgram(DiagnosticPrograms.AllPrograms(0), system, consoleState)
    consoleState.log(s"Adafruit Feather RP2040 Stage 1 (${system.engineMode.label}). ${heapSummary()}")
    consoleState.statusMessage =
      s"Feather RP2040 Ready (${heapSummary()}). Press [r] Run, [s] Step, [w] Prog Tape, [:] Cmd."

    var usbWasConnected = PicoSdk.pico_stdio_usb_connected()
    var needsRender = true

    while (true) {
      updateHardwareLamps(system, isRunningLoop = false)

      val usbConnectedNow = PicoSdk.pico_stdio_usb_connected()
      if (usbConnectedNow && !usbWasConnected) {
        // Fresh USB CDC terminal connection detected: brief settle delay then redraw console
        PicoSdk.sleep_ms(120.toUInt)
        needsRender = true
      }
      usbWasConnected = usbConnectedNow

      if (needsRender) {
        CeConsoleView.render(terminal, system.cpu, consoleState)
        print(terminal.renderToString())
        stdio.fflush(stdio.stdout)
        needsRender = false
      }

      val ch = PicoSdk.pico_getchar_timeout_us(100000.toUInt) // 100 ms poll
      if (ch >= 0) {
        val cmd = readSerialCommand(ch)
        handleRp2040Command(cmd, system, consoleState, terminal)
        needsRender = true
      }
    }
  }

  /**
   * Reads either an immediate single-key command or, when `:` / `/` is pressed,
   * an interactive multi-character command line echoed on the bottom row.
   */
  private def readSerialCommand(firstChar: Int): String = {
    if (firstChar == '\r' || firstChar == '\n' || firstChar == ' ') {
      return "s"
    }
    if (firstChar == ':' || firstChar == '/') {
      print("\r\nCommand> ")
      stdio.fflush(stdio.stdout)
      val sb = new java.lang.StringBuilder()
      var done = false
      while (!done) {
        val c = PicoSdk.pico_getchar_timeout_us(1000000.toUInt)
        if (c == '\r' || c == '\n') {
          done = true
        } else if (c == 27) { // ESC cancels command entry
          sb.setLength(0)
          done = true
        } else if (c == 8 || c == 127) { // Backspace / DEL
          if (sb.length() > 0) {
            sb.setLength(sb.length() - 1)
            print("\b \b")
            stdio.fflush(stdio.stdout)
          }
        } else if (c >= 32 && c <= 126) {
          sb.append(c.toChar)
          stdio.fputc(c, stdio.stdout)
          stdio.fflush(stdio.stdout)
        }
      }
      return sb.toString().trim
    }
    firstChar.toChar.toString
  }

  private def handleRp2040Command(
    cmd: String,
    system: MtscSystem,
    consoleState: CeConsoleState,
    terminal: AnsiTerminal
  ): Unit = {
    val lower = cmd.trim.toLowerCase
    lower match {
      case "w" =>
        // Single-key convenience shortcut for 't prog' over raw USB serial
        val _ = Main.handleCommand(
          "t prog",
          system,
          consoleState,
          terminal,
          rawMode = true,
          runFn = () => runPacedRp2040(system, consoleState, terminal)
        )

      case "d" | "gc" | "heap" =>
        System.gc()
        val msg = s"RP2040 Immix GC & SRAM Status — ${heapSummary()}"
        consoleState.log(msg)
        consoleState.statusMessage = msg

      case "q" | "quit" | "exit" =>
        consoleState.statusMessage = s"Feather RP2040 appliance stays active (${heapSummary()}). Press [x] to reset CCU."

      case _ =>
        val _ = Main.handleCommand(
          cmd,
          system,
          consoleState,
          terminal,
          rawMode = true,
          mountFileFn = (_, _, _, cs) => {
            cs.statusMessage = "Host filesystem unavailable on bare-metal RP2040; use [t] or [w] (t prog)."
            false
          },
          runFn = () => runPacedRp2040(system, consoleState, terminal)
        )
    }
  }

  /**
   * Paced execution loop for the Feather RP2040, driving the onboard WS2812 NeoPixel
   * (`Green` = `+RUN`, `Blue` = `+MT CALL` tape frame read, `Amber` = `Inhibit`, `Red` = Error)
   * and polling USB CDC serial via `pico_getchar_timeout_us(0)` for instant keypress interruption.
   */
  private def runPacedRp2040(
    system: MtscSystem,
    consoleState: CeConsoleState,
    terminal: AnsiTerminal
  ): Unit = {
    if (system.memory.psar.unsigned >= 42 && system.memory.psar.unsigned <= 47) {
      updateHardwareLamps(system, isRunningLoop = true, mtPulse = true)
      val bootRes = system.runBootLoader()
      updateHardwareLamps(system, isRunningLoop = false)
      bootRes match {
        case StepResult.Normal =>
          consoleState.log(f"Cold boot complete: CPU at 0x${system.memory.psar.unsigned}%03X (${heapSummary()}).")
          consoleState.statusMessage =
            f"Cold boot complete! CPU ready at 0x${system.memory.psar.unsigned}%03X. Mount text tape [t] and [r] to compose."
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
      print("\u001b[?25l")
      stdio.fflush(stdio.stdout)
    }

    try {
      var lastPrintCount = system.translator.printCount
      var lastRightHead = system.rightTape.headPosition
      var lastLeftHead = system.leftTape.headPosition

      while (res == StepResult.Normal && !hitBreakpoint && !interrupted && steps < 100000) {
        val prevPsar = system.memory.psar
        res = system.step()
        steps += 1

        val tapeMoved =
          system.rightTape.headPosition != lastRightHead || system.leftTape.headPosition != lastLeftHead
        if (tapeMoved) {
          lastRightHead = system.rightTape.headPosition
          lastLeftHead = system.leftTape.headPosition
        }

        if ((steps & 0x3f) == 0 || tapeMoved) {
          updateHardwareLamps(system, isRunningLoop = true, mtPulse = tapeMoved)
        }

        if (consoleState.breakpoints.contains(system.memory.psar)) {
          hitBreakpoint = true
        }

        if (consoleState.realTimePacing && delayMs > 0 && system.translator.printCount > lastPrintCount) {
          lastPrintCount = system.translator.printCount
          ledToggle = !ledToggle
          updateHardwareLamps(system, isRunningLoop = true, mtPulse = tapeMoved)

          CeConsoleView.render(terminal, system.cpu, consoleState)
          print(terminal.renderToString())
          stdio.fflush(stdio.stdout)

          PicoSdk.sleep_ms(delayMs.toUInt)

          if (PicoSdk.pico_getchar_timeout_us(0.toUInt) >= 0) {
            interrupted = true
          }
        } else if ((steps & 0x1ff) == 0) {
          if (PicoSdk.pico_getchar_timeout_us(0.toUInt) >= 0) {
            interrupted = true
          }
        }

        if (!system.cpu.state.multiplex && system.memory.psar == prevPsar && !system.cpu.state.inhibit) {
          system.cpu.state.halted = true
          res = StepResult.Halted
        }
      }
    } finally {
      updateHardwareLamps(system, isRunningLoop = false)
      if (consoleState.realTimePacing && delayMs > 0) {
        print("\u001b[?25h")
        stdio.fflush(stdio.stdout)
      }
    }

    if (interrupted) {
      consoleState.statusMessage =
        f"Paused by user at 0x${system.memory.psar.unsigned}%03X after $steps steps (${heapSummary()})."
      consoleState.log(f"Execution paused at 0x${system.memory.psar.unsigned}%03X.")
    } else if (hitBreakpoint) {
      consoleState.statusMessage =
        f"Breakpoint hit at 0x${system.memory.psar.unsigned}%03X after $steps steps."
      consoleState.log(f"Breakpoint hit at 0x${system.memory.psar.unsigned}%03X.")
    } else if (res == StepResult.Halted) {
      consoleState.statusMessage =
        f"Composition completed in $steps cycles (${system.paper.lines.length} lines, ${heapSummary()})."
      consoleState.log(f"Program HALTED after $steps cycles. ${heapSummary()}")
    } else {
      consoleState.statusMessage = f"Ran $steps steps (${heapSummary()})."
    }
  }
}
