package mtsc.rp2040

import scala.scalanative.libc.stdio
import scala.scalanative.runtime.GC
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import mtsc.cli.Main
import mtsc.console.{AnsiTerminal, CeConsoleState, CeConsoleView}
import mtsc.cpu.{CcuEngineMode, StepResult}
import mtsc.diag.{DiagnosticPrograms, EscapementRoutine}
import mtsc.periph.{MtscSystem, TapeCartridge}
import mtsc.types.{Device, Fmt}

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
  def pico_set_boot_stage(stage: CUnsignedInt): Unit = extern
  def pico_getchar_timeout_us(timeoutUs: CUnsignedInt): CInt = extern
  def pico_stdio_usb_connected(): CBool = extern
  def pico_time_us_32(): CUnsignedInt = extern
  def pico_serial_print(s: Object): Unit = extern
  def pico_serial_flush(): Unit = extern
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

  // Keep top-level emulator state rooted in module fields so Immix GC always marks them
  private var systemRef: MtscSystem = null
  private var consoleStateRef: CeConsoleState = null
  private var terminalRef: AnsiTerminal = null

  private def flushTerminal(terminal: AnsiTerminal, forceFull: Boolean = false): Int = {
    val rows = terminal.renderDirtyRows(forceFull, row => PicoSdk.pico_serial_print(row))
    PicoSdk.pico_serial_flush()
    rows
  }

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
      PicoSdk.pico_neopixel_set_rgb(80.toUInt, 0.toUInt, 0.toUInt)
      PicoSdk.pico_led_put(ledPin, true)
    } else if (isMtActive) {
      // Blue pulse (+MT CALL): Magnetic Tape 6310 stepping a frame
      PicoSdk.pico_neopixel_set_rgb(0.toUInt, 24.toUInt, 96.toUInt)
      PicoSdk.pico_led_put(ledPin, true)
    } else if (isInhibit) {
      // Amber (LITE 11): Waiting for Operator (Inhibit)
      PicoSdk.pico_neopixel_set_rgb(64.toUInt, 24.toUInt, 0.toUInt)
      PicoSdk.pico_led_put(ledPin, true)
    } else if (isRun) {
      // Bright Green (+RUN): 240 kHz Clock Running
      PicoSdk.pico_neopixel_set_rgb(0.toUInt, 64.toUInt, 0.toUInt)
      PicoSdk.pico_led_put(ledPin, ledToggle)
    } else if (PicoSdk.pico_stdio_usb_connected()) {
      // Dim Green: USB Serial connected & Ready in Customer Engineer console
      PicoSdk.pico_neopixel_set_rgb(0.toUInt, 16.toUInt, 0.toUInt)
      PicoSdk.pico_led_put(ledPin, false)
    } else {
      // Dim Cyan: Ready & waiting for USB Serial terminal connection
      PicoSdk.pico_neopixel_set_rgb(0.toUInt, 8.toUInt, 24.toUInt)
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
    PicoSdk.pico_set_boot_stage(2.toUInt)
    ledPin = PicoSdk.pico_default_led_pin()
    PicoSdk.pico_led_init(ledPin)
    PicoSdk.pico_neopixel_init()
    // Stage 2 (White): Entered mtsc_rp2040_main, constructing MtscSystem
    PicoSdk.pico_neopixel_set_rgb(24.toUInt, 24.toUInt, 24.toUInt)

    val system = new MtscSystem(initialEngineMode = CcuEngineMode.Ild)
    systemRef = system
    // Stage 3: Initializing IldCcu lazy val and SRAM optimizations
    PicoSdk.pico_set_boot_stage(3.toUInt)
    system.cpu.ild.timingTrace.enabled = false
    system.paper.maxRetainedLines = 24
    system.paper.recordGlyphs = false

    // Stage 4: Constructing CeConsoleState & AnsiTerminal
    PicoSdk.pico_set_boot_stage(4.toUInt)
    val consoleState = new CeConsoleState()
    consoleStateRef = consoleState
    consoleState.viewGalley = true
    val terminal = new AnsiTerminal(width = 104, height = 38)
    terminalRef = terminal

    // Stage 5: Loading DiagnosticPrograms.AllPrograms(0)
    PicoSdk.pico_set_boot_stage(5.toUInt)
    PicoSdk.pico_neopixel_set_rgb(32.toUInt, 32.toUInt, 0.toUInt)
    val initialProg = DiagnosticPrograms.AllPrograms(0)

    // Stage 6: initialProg.assemble()
    PicoSdk.pico_set_boot_stage(6.toUInt)
    val img = initialProg.assemble()

    // Stage 7: system.loadProgram(img)
    PicoSdk.pico_set_boot_stage(7.toUInt)
    system.loadProgram(img)

    // Stage 8: EscapementRoutine.initializeTable(system.memory)
    PicoSdk.pico_set_boot_stage(8.toUInt)
    EscapementRoutine.initializeTable(system.memory)

    // Stage 9: TapeCartridge.fromText(HistoricalSampleText)
    PicoSdk.pico_set_boot_stage(9.toUInt)
    val tape = TapeCartridge.fromText(TapeCartridge.HistoricalSampleText, "Historical MT Tape")

    // Stage 10: system.loadTape(Device.RightTape, tape)
    PicoSdk.pico_set_boot_stage(10.toUInt)
    system.loadTape(Device.RightTape, tape)
    consoleState.log("Mounted historical 4-paragraph MT cartridge on Right Tape Reader.")

    // Stage 11: Formatting initial status message & updating lamps
    PicoSdk.pico_set_boot_stage(11.toUInt)
    consoleState.log(s"Program '${initialProg.name}' loaded at 0x${Fmt.hex3(system.memory.psar.unsigned)}.")
    consoleState.log(s"Adafruit Feather RP2040 Stage 1 (${system.engineMode.label}). ${heapSummary()}")
    consoleState.statusMessage =
      s"Feather RP2040 Ready (${heapSummary()}). Press [r] Run, [s] Step, [n] Engine, [b] Bench, [a] Pacing."

    var usbWasConnected = PicoSdk.pico_stdio_usb_connected()
    var needsRender = usbWasConnected
    var forceFullRender = true

    // Stage 12..16: Interactive loop
    while (true) {
      try {
        PicoSdk.pico_set_boot_stage(12.toUInt)
        updateHardwareLamps(system, isRunningLoop = false)

        val usbConnectedNow = PicoSdk.pico_stdio_usb_connected()
        if (usbConnectedNow && !usbWasConnected) {
          // Fresh USB CDC terminal connection detected: brief settle delay then redraw console
          PicoSdk.sleep_ms(120.toUInt)
          needsRender = true
          forceFullRender = true
        }
        usbWasConnected = usbConnectedNow

        if (needsRender) {
          PicoSdk.pico_set_boot_stage(13.toUInt)
          CeConsoleView.render(terminal, system.cpu, consoleState)
          PicoSdk.pico_set_boot_stage(14.toUInt)
          flushTerminal(terminal, forceFull = forceFullRender)
          needsRender = false
          forceFullRender = false
        }

        PicoSdk.pico_set_boot_stage(15.toUInt)
        val ch = PicoSdk.pico_getchar_timeout_us(10000.toUInt) // 10 ms poll
        if (ch >= 0) {
          PicoSdk.pico_set_boot_stage(16.toUInt)
          val cmd = readSerialCommand(ch)
          handleRp2040Command(cmd, system, consoleState, terminal)
          needsRender = true
        }
      } catch {
        case t: Throwable =>
          val msg = s"\r\n[EXCEPTION] ${t.getClass.getName}: ${t.getMessage}\r\n"
          PicoSdk.pico_serial_print(msg)
          PicoSdk.pico_serial_flush()
          consoleState.log(s"ERR: ${t.getClass.getSimpleName}: ${t.getMessage}")
          consoleState.statusMessage = s"Exception: ${t.getClass.getSimpleName} (${heapSummary()})"
          PicoSdk.sleep_ms(500.toUInt)
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
      PicoSdk.pico_serial_print("\r\nCommand> ")
      PicoSdk.pico_serial_flush()
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
            PicoSdk.pico_serial_print("\b \b")
            PicoSdk.pico_serial_flush()
          }
        } else if (c >= 32 && c <= 126) {
          sb.append(c.toChar)
          stdio.fputc(c, stdio.stdout)
          PicoSdk.pico_serial_flush()
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
    val lower = Fmt.asciiLower(cmd.trim)
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

      case "n" | "engine" =>
        val mode = system.toggleEngineMode()
        consoleState.log(s"Switched CCU Engine to ${mode.label} (${mode.cliFlag}).")
        consoleState.statusMessage = s"CCU Engine switched to ${mode.label} (${heapSummary()})."

      case "b" | "bench" | "benchmark" =>
        runHardwareBenchmark(system, consoleState, terminal)

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
          val hexPsar = Fmt.hex3(system.memory.psar.unsigned)
          consoleState.log(s"Cold boot complete: CPU at 0x$hexPsar (${heapSummary()}).")
          consoleState.statusMessage =
            s"Cold boot complete! CPU ready at 0x$hexPsar. Mount text tape [t] and [r] to compose."
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
      PicoSdk.pico_serial_print("\u001b[?25l")
      PicoSdk.pico_serial_flush()
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
          flushTerminal(terminal, forceFull = false)

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
        PicoSdk.pico_serial_print("\u001b[?25h")
        PicoSdk.pico_serial_flush()
      }
    }

    val hexPsar = Fmt.hex3(system.memory.psar.unsigned)
    if (interrupted) {
      consoleState.statusMessage =
        s"Paused by user at 0x$hexPsar after $steps steps (${heapSummary()})."
      consoleState.log(s"Execution paused at 0x$hexPsar.")
    } else if (hitBreakpoint) {
      consoleState.statusMessage =
        s"Breakpoint hit at 0x$hexPsar after $steps steps."
      consoleState.log(s"Breakpoint hit at 0x$hexPsar.")
    } else if (res == StepResult.Halted) {
      consoleState.statusMessage =
        s"Composition completed in $steps cycles (${system.paper.lines.length} lines, ${heapSummary()})."
      consoleState.log(s"Program HALTED after $steps cycles. ${heapSummary()}")
    } else {
      consoleState.statusMessage = s"Ran $steps steps (${heapSummary()})."
    }
  }

  /**
   * Profiles RP2040 execution using the 1 MHz hardware timer (`time_us_32()`):
   * 1. `CeConsoleView.render` (CPU formatting into the 104x38 screen buffer)
   * 2. `flushTerminal` (full 38-row USB CDC frame vs delta dirty-row frame)
   * 3. `IldCcu.stepBitTime()` (`240 kHz` gate-level nibble evaluation across 1,000 bit-times)
   */
  private def runHardwareBenchmark(
    system: MtscSystem,
    consoleState: CeConsoleState,
    terminal: AnsiTerminal
  ): Unit = {
    // 1. Measure CeConsoleView.render
    val t0 = PicoSdk.pico_time_us_32().toInt
    CeConsoleView.render(terminal, system.cpu, consoleState)
    val t1 = PicoSdk.pico_time_us_32().toInt
    val renderUs = t1 - t0

    // 2. Measure full 38-row USB serial flush
    val t2 = PicoSdk.pico_time_us_32().toInt
    val fullRows = flushTerminal(terminal, forceFull = true)
    val t3 = PicoSdk.pico_time_us_32().toInt
    val flushFullUs = t3 - t2

    // 3. Measure 1,000 gate-level ILD bit-times (saving and restoring state/memory PSAR)
    val savedPsar = system.memory.psar
    val savedAw = system.memory.aWord
    val savedBw = system.memory.bWord
    system.cpu.ild.timingTrace.enabled = false
    val t4 = PicoSdk.pico_time_us_32().toInt
    var bt = 0
    while (bt < 1000) {
      val _ = system.cpu.ild.stepBitTime()
      bt += 1
    }
    val t5 = PicoSdk.pico_time_us_32().toInt
    val ild1000BtUs = t5 - t4
    val usPerBt = ild1000BtUs / 1000
    val effKhz = if (ild1000BtUs > 0) 1000000 / ild1000BtUs else 0
    system.memory.psar = savedPsar
    system.memory.aWord = savedAw
    system.memory.bWord = savedBw
    mtsc.cpu.ild.Ild0303Clock.reset(system.cpu.ild.signals)
    system.cpu.ild.syncAllCombinational()
    system.cpu.ild.syncStateFromSignals()

    val summary =
      s"Bench: render=${renderUs / 1000}ms, usbFlush(${fullRows}r)=${flushFullUs / 1000}ms, ILD=${usPerBt}us/bt (${effKhz}kHz, ~${usPerBt * 12}us/insn)"
    consoleState.log(summary)
    consoleState.statusMessage = s"$summary │ ${heapSummary()}"
  }
}
