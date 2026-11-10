package mtsc.rp2040

import scala.scalanative.libc.stdio
import scala.scalanative.runtime.GC
import scala.scalanative.unsafe.*

import mtsc.cli.Main
import mtsc.console.{AnsiTerminal, CeConsoleState, CeConsoleView}
import mtsc.cpu.{CcuEngineMode, StepResult}
import mtsc.diag.{DiagnosticPrograms, EscapementRoutine}
import mtsc.periph.{GalleyFont, MtscSystem, OledView, TapeCartridge}
import mtsc.types.{Address, Device, Fmt, RegId, Word}

/**
 * C wrappers for Pico SDK GPIO, PIO WS2812 NeoPixel, SSD1680/IL0373 eInk FeatherWing (#4777),
 * SSD1306 128x32 OLED FeatherWing (#2900), and non-blocking USB CDC / UART serial I/O provided by
 * `firmware/main.c`, `firmware/epd_ssd1680.c`, and `firmware/oled_ssd1306.c`.
 *
 * Note: Uses `CInt` (`Int`) instead of `CUnsignedInt` (`UInt`) across all FFI signatures so Scala 3.3.8
 * never emits `Boxes.boxToUInt` heap allocations in the 10 ms polling or emulation loops.
 */
@extern
object PicoSdk {
  def pico_default_led_pin(): CInt = extern
  def pico_led_init(pin: CInt): Unit = extern
  def pico_led_put(pin: CInt, value: CBool): Unit = extern
  def pico_neopixel_init(): Unit = extern
  def pico_neopixel_set_rgb(r: CInt, g: CInt, b: CInt): Unit = extern
  def pico_set_boot_stage(stage: CInt): Unit = extern
  def pico_getchar_timeout_us(timeoutUs: CInt): CInt = extern
  def pico_stdio_usb_connected(): CBool = extern
  def pico_time_us_32(): CInt = extern
  def pico_serial_print(s: Object): Unit = extern
  def pico_serial_print_chars(chars: Object, count: CInt): Unit = extern
  def pico_serial_flush(): Unit = extern
  def sleep_ms(ms: CInt): Unit = extern
  def pico_epd_init(): Unit = extern
  def pico_epd_is_busy(): CBool = extern
  def pico_epd_power_off(): Unit = extern
  def pico_epd_set_controller(ctrlType: CInt): Unit = extern
  def pico_epd_upload_planes(bwPlane: Ptr[Byte], redPlane: Ptr[Byte], mode: CInt): Unit = extern
  def pico_epd_poll_buttons(): CInt = extern
  def pico_epd_get_diag(index: CInt): CInt = extern
  def pico_oled_init(): Unit = extern
  def pico_oled_is_present(): CBool = extern
  def pico_oled_flush(frameBuf: Ptr[Byte]): Unit = extern
  def pico_oled_set_flip(flip180: CBool): Unit = extern
  def pico_oled_set_power(on: CBool): Unit = extern
  def pico_oled_get_diag(index: CInt): CInt = extern
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

  private var ledPin: Int = 13
  private var ledToggle: Boolean = false
  private var lastNeoPackedRgb: Int = -1
  private var lastLedOn: Int = -1

  @inline private def setNeoAndLed(r: Int, g: Int, b: Int, ledOn: Boolean): Unit = {
    val packed = (r << 16) | (g << 8) | b
    if (packed != lastNeoPackedRgb) {
      lastNeoPackedRgb = packed
      PicoSdk.pico_neopixel_set_rgb(r, g, b)
    }
    val ledBit = if (ledOn) 1 else 0
    if (ledBit != lastLedOn) {
      lastLedOn = ledBit
      PicoSdk.pico_led_put(ledPin, ledOn)
    }
  }

  // Keep top-level emulator state rooted in module fields so Immix GC always marks them
  private var systemRef: MtscSystem = null
  private var consoleStateRef: CeConsoleState = null
  private var terminalRef: AnsiTerminal = null
  private var defaultTapeRef: TapeCartridge = null

  // Pre-allocated 296x128 SSD1680 eInk bitplanes (2 * 4,736 = 9,472 bytes) rooted in module fields
  private val epdBwPlane: Array[Byte] = new Array[Byte](GalleyFont.PlaneBytes)
  private val epdRedPlane: Array[Byte] = new Array[Byte](GalleyFont.PlaneBytes)
  private var epdScrollOffsetLines: Int = 0
  private var epdFlip180: Boolean = false
  private var epdLastRenderedLines: Int = -1
  private var epdLastRenderedChars: Long = -1L
  private var epdLastStartLine: Int = 0
  private var epdPendingMode: Int = -1 // -1 = none, 0 = Fast Mono, 1 = Full Mono, 2 = 4-Grayscale

  private final val EpdModeFastMono: Int = 0
  private final val EpdModeFullMono: Int = 1
  private final val EpdModeGray4: Int = 2

  // Pre-allocated 128x32 SSD1306 OLED framebuffer (512 bytes) rooted in module field
  private val oledFrameBuf: Array[Byte] = new Array[Byte](OledView.BufferBytes)
  private var oledFlip180: Boolean = false
  private var lastOledUpdateUs: Int = 0
  private var oledLastActivityUs: Int = 0
  private var oledAsleep: Boolean = false
  private final val OledSleepTimeoutUs: Int = 30000000 // 30 seconds of inactivity before blanking OLED

  private def updateOledDisplay(system: MtscSystem, consoleState: CeConsoleState): Unit = {
    oledLastActivityUs = PicoSdk.pico_time_us_32()
    oledAsleep = false
    if (!PicoSdk.pico_oled_is_present()) {
      return
    }
    OledView.renderToBuffer(
      system = system,
      consoleState = consoleState,
      targetMeasure = currentTargetMeasure(system),
      buf = oledFrameBuf
    )
    PicoSdk.pico_oled_flush(oledFrameBuf.at(0))
  }

  private def currentTargetMeasure(system: MtscSystem): Int = {
    val r30 = system.memory.readWord(Address(60)).unsigned
    if (r30 >= 30 && r30 <= 240) r30
    else if (system.operatorPanel.measure >= 30 && system.operatorPanel.measure <= 240) system.operatorPanel.measure
    else 140
  }

  private def requestEpdRefresh(
    system: MtscSystem,
    mode: Int,
    showCursor: Boolean = false,
    forceNow: Boolean = false
  ): Unit = {
    if (mode > epdPendingMode) {
      epdPendingMode = mode
    }
    servicePendingEpdRefresh(system, showCursor, forceNow)
  }

  private def servicePendingEpdRefresh(
    system: MtscSystem,
    showCursor: Boolean = false,
    forceNow: Boolean = false
  ): Boolean = {
    if (epdPendingMode < 0 || (!forceNow && PicoSdk.pico_epd_is_busy())) {
      return false
    }
    val totalLines = system.paper.lineCount
    val newStartLine = GalleyFont.visibleStartLine(totalLines, epdScrollOffsetLines)
    // Whenever the visible top line shifts (e.g. advancing to a new 8-line page or scrolling),
    // promote FastMono to FullMono so the previous page's text is erased cleanly with zero ghosting.
    val modeToRun =
      if (epdPendingMode == EpdModeFastMono && newStartLine != epdLastStartLine) EpdModeFullMono
      else epdPendingMode
    epdPendingMode = -1
    epdLastStartLine = newStartLine
    epdLastRenderedLines = system.paper.completedLinesCount
    epdLastRenderedChars = system.paper.totalCharactersPrinted

    GalleyFont.renderGalleyToPlanes(
      paper = system.paper,
      targetMeasure = currentTargetMeasure(system),
      bwPlane = epdBwPlane,
      redPlane = epdRedPlane,
      scrollOffsetLines = epdScrollOffsetLines,
      showCursor = showCursor,
      flip180 = epdFlip180
    )
    PicoSdk.pico_epd_upload_planes(epdBwPlane.at(0), epdRedPlane.at(0), modeToRun)
    true
  }

  private val serialPrintCharsFn: (Array[Char], Int) => Unit =
    (buf, len) => PicoSdk.pico_serial_print_chars(buf, len)

  private def flushTerminal(terminal: AnsiTerminal, forceFull: Boolean = false): Int = {
    val rows = terminal.renderDirtyRowsChars(forceFull, serialPrintCharsFn)
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
      setNeoAndLed(80, 0, 0, ledOn = true)
    } else if (isMtActive) {
      // Blue pulse (+MT CALL): Magnetic Tape 6310 stepping a frame
      setNeoAndLed(0, 24, 96, ledOn = true)
    } else if (isInhibit) {
      // Amber (LITE 11): Waiting for Operator (Inhibit)
      setNeoAndLed(64, 24, 0, ledOn = true)
    } else if (isRun) {
      // Bright Green (+RUN): 240 kHz Clock Running
      setNeoAndLed(0, 64, 0, ledOn = ledToggle)
    } else if (oledAsleep) {
      // Low-power inactivity sleep: turn off both WS2812 NeoPixel and status LED
      setNeoAndLed(0, 0, 0, ledOn = false)
    } else if (PicoSdk.pico_stdio_usb_connected()) {
      // Dim Green: USB Serial connected & Ready in Customer Engineer console
      setNeoAndLed(0, 16, 0, ledOn = false)
    } else {
      // Dim Cyan: Ready & waiting for USB Serial terminal connection
      setNeoAndLed(0, 8, 24, ledOn = false)
    }
  }

  private def heapSummary(): String = {
    val usedKb = GC.getUsedHeapSize().toLong / 1024L
    val maxKb = GC.getMaxHeapSize().toLong / 1024L
    val collections = GC.getStatsCollectionTotal()
    s"Heap: ${usedKb}KB/${maxKb}KB (GC #$collections)"
  }

  private def hex32(v: Int): String =
    java.lang.Integer.toHexString(v)

  private def epdDiagSummary(): String = {
    val sramSt = hex32(PicoSdk.pico_epd_get_diag(0))
    val sramRd = hex32(PicoSdk.pico_epd_get_diag(1))
    val pullM  = hex32(PicoSdk.pico_epd_get_diag(3))
    val stReg  = hex32(PicoSdk.pico_epd_get_diag(4))
    val cnt    = PicoSdk.pico_epd_get_diag(5)
    val mode   = PicoSdk.pico_epd_get_diag(6)
    val ctrl   = PicoSdk.pico_epd_get_diag(7)
    val drv    = if (ctrl == 2) "IL0373" else "SSD1680"
    s"SPI0[drv=$drv PU=0x$pullM ST=0x$stReg SRAM=0x$sramRd(st=0x$sramSt) up=$cnt m=$mode]"
  }

  private def oledDiagSummary(): String = {
    val present = PicoSdk.pico_oled_get_diag(0) != 0
    val frames  = PicoSdk.pico_oled_get_diag(1)
    val lastRc  = PicoSdk.pico_oled_get_diag(2)
    val rcStr =
      if (lastRc > 0) s"ACK(${lastRc}B)"
      else if (lastRc == -1) "NACK(absent)"
      else if (lastRc == -2) "TIMEOUT"
      else if (lastRc == -100) "BUS_LOW"
      else lastRc.toString
    s"I2C1[0x3C present=$present rc=$rcStr frames=$frames]"
  }

  @exported
  def mtsc_rp2040_main(): Unit = {
    PicoSdk.pico_set_boot_stage(2)
    PicoSdk.pico_oled_init()
    PicoSdk.pico_epd_init()
    ledPin = PicoSdk.pico_default_led_pin()
    PicoSdk.pico_led_init(ledPin)
    PicoSdk.pico_neopixel_init()
    // Stage 2 (White): Entered mtsc_rp2040_main, constructing MtscSystem
    PicoSdk.pico_neopixel_set_rgb(24, 24, 24)

    val system = new MtscSystem(initialEngineMode = CcuEngineMode.Ild)
    systemRef = system
    // Stage 3: Initializing IldCcu lazy val and SRAM optimizations
    PicoSdk.pico_set_boot_stage(3)
    system.cpu.ild.timingTrace.enabled = false
    system.paper.maxRetainedLines = 64
    system.paper.recordGlyphs = true

    // Stage 4: Constructing CeConsoleState & AnsiTerminal
    PicoSdk.pico_set_boot_stage(4)
    val consoleState = new CeConsoleState()
    consoleStateRef = consoleState
    consoleState.viewGalley = true
    val terminal = new AnsiTerminal(width = 104, height = 38)
    terminalRef = terminal

    // Stage 5: Loading DiagnosticPrograms.AllPrograms(0)
    PicoSdk.pico_set_boot_stage(5)
    PicoSdk.pico_neopixel_set_rgb(32, 32, 0)
    val initialProg = DiagnosticPrograms.AllPrograms(0)

    // Stage 6: initialProg.assemble()
    PicoSdk.pico_set_boot_stage(6)
    val img = initialProg.assemble()

    // Stage 7: system.loadProgram(img)
    PicoSdk.pico_set_boot_stage(7)
    system.loadProgram(img)

    // Stage 8: EscapementRoutine.initializeTable(system.memory)
    PicoSdk.pico_set_boot_stage(8)
    EscapementRoutine.initializeTable(system.memory)

    // Stage 9: TapeCartridge.fromText(HistoricalSampleText)
    PicoSdk.pico_set_boot_stage(9)
    val tape = TapeCartridge.fromText(TapeCartridge.HistoricalSampleText, "Historical MT Tape")
    defaultTapeRef = tape

    // Stage 10: system.loadTape(Device.RightTape, tape)
    PicoSdk.pico_set_boot_stage(10)
    system.loadTape(Device.RightTape, tape)
    consoleState.log("Mounted historical 4-paragraph MT cartridge on Right Tape Reader.")

    // Stage 11: Formatting initial status message & updating lamps + eInk Galley + OLED
    PicoSdk.pico_set_boot_stage(11)
    consoleState.log(s"Program '${initialProg.name}' loaded at 0x${Fmt.hex3(system.memory.psar.unsigned)}.")
    // Render initial 4-Grayscale camera-ready galley sheet with pica ruler
    requestEpdRefresh(system, mode = EpdModeGray4, showCursor = false)
    updateOledDisplay(system, consoleState)
    consoleState.log(s"2.9\" eInk (#4777): ${epdDiagSummary()}")
    consoleState.log(s"128x32 OLED (#2900): ${oledDiagSummary()}")
    consoleState.statusMessage =
      s"Ready (${heapSummary()}). [r/BtnA] Run, [s] Step, [e/BtnB] eInk, [o] OLED, [BtnC] Reset."

    var usbWasConnected = PicoSdk.pico_stdio_usb_connected()
    var needsRender = usbWasConnected
    var forceFullRender = true

    // Stage 12..16: Interactive loop
    while (true) {
      try {
        PicoSdk.pico_set_boot_stage(12)
        updateHardwareLamps(system, isRunningLoop = false)
        servicePendingEpdRefresh(system, showCursor = false)

        val usbConnectedNow = PicoSdk.pico_stdio_usb_connected()
        if (usbConnectedNow && !usbWasConnected) {
          // Fresh USB CDC terminal connection detected: brief settle delay then redraw console
          oledLastActivityUs = PicoSdk.pico_time_us_32()
          oledAsleep = false
          updateOledDisplay(system, consoleState)
          updateHardwareLamps(system, isRunningLoop = false)
          PicoSdk.sleep_ms(120)
          needsRender = true
          forceFullRender = true
        }
        usbWasConnected = usbConnectedNow

        // Poll FeatherWing physical buttons A (D11/D9), B (D12/D6), C (D13/D5)
        val btnMask = PicoSdk.pico_epd_poll_buttons()
        if (btnMask != 0) {
          oledLastActivityUs = PicoSdk.pico_time_us_32()
          oledAsleep = false
          updateHardwareLamps(system, isRunningLoop = false)
          handleFeatherWingButtons(btnMask, system, consoleState, terminal)
          updateOledDisplay(system, consoleState)
          needsRender = true
        }

        if (needsRender) {
          PicoSdk.pico_set_boot_stage(13)
          CeConsoleView.render(terminal, system.cpu, consoleState)
          PicoSdk.pico_set_boot_stage(14)
          flushTerminal(terminal, forceFull = forceFullRender)
          needsRender = false
          forceFullRender = false
        }

        // Put OLED display, eInk charge pump, and status NeoPixel/LED into low-power sleep
        // after 30s without composition, button, or command activity
        if (!oledAsleep) {
          val idleUs = PicoSdk.pico_time_us_32() - oledLastActivityUs
          if (idleUs >= OledSleepTimeoutUs || idleUs < 0) {
            oledAsleep = true
            if (PicoSdk.pico_oled_is_present()) {
              PicoSdk.pico_oled_set_power(false)
            }
            if (!PicoSdk.pico_epd_is_busy()) {
              PicoSdk.pico_epd_power_off()
            }
            updateHardwareLamps(system, isRunningLoop = false)
          }
        }

        PicoSdk.pico_set_boot_stage(15)
        val ch = PicoSdk.pico_getchar_timeout_us(if (oledAsleep) 50000 else 10000)
        if (ch >= 0) {
          PicoSdk.pico_set_boot_stage(16)
          oledLastActivityUs = PicoSdk.pico_time_us_32()
          oledAsleep = false
          updateHardwareLamps(system, isRunningLoop = false)
          val prevLines = system.paper.completedLinesCount
          val prevChars = system.paper.totalCharactersPrinted
          val cmd = readSerialCommand(ch)
          handleRp2040Command(cmd, system, consoleState, terminal)
          if (system.paper.completedLinesCount != prevLines || system.paper.totalCharactersPrinted != prevChars) {
            requestEpdRefresh(system, mode = EpdModeFastMono, showCursor = true)
          }
          updateOledDisplay(system, consoleState)
          needsRender = true
        }
      } catch {
        case t: Throwable =>
          val msg = s"\r\n[EXCEPTION] ${t.getClass.getName}: ${t.getMessage}\r\n"
          PicoSdk.pico_serial_print(msg)
          PicoSdk.pico_serial_flush()
          consoleState.log(s"ERR: ${t.getClass.getSimpleName}: ${t.getMessage}")
          consoleState.statusMessage = s"Exception: ${t.getClass.getSimpleName} (${heapSummary()})"
          PicoSdk.sleep_ms(500)
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
        val c = PicoSdk.pico_getchar_timeout_us(1000000)
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

  /**
   * Handles physical tactile buttons A (`D11`), B (`D12`), and C (`D13`) on the
   * Adafruit 2.9" Grayscale eInk FeatherWing (#4777):
   * - **Button A (`0x01`)**: Start / Resume composition (or restart if already halted)
   * - **Button B (`0x02`)**: Scroll through completed galley lines (4 lines per press) or force 4-Grayscale refresh
   * - **Button C (`0x04`)**: Master Reset CCU, rewind tape cartridge, and clear galley sheet
   */
  private def handleFeatherWingButtons(
    btnMask: Int,
    system: MtscSystem,
    consoleState: CeConsoleState,
    terminal: AnsiTerminal
  ): Unit = {
    if ((btnMask & 0x01) != 0) {
      // Button A: Start / Resume Composition
      epdScrollOffsetLines = 0
      if (system.cpu.state.halted) {
        system.reset()
        EscapementRoutine.initializeTable(system.memory)
        if (defaultTapeRef != null) {
          system.loadTape(Device.RightTape, defaultTapeRef)
        }
        System.gc()
        epdLastRenderedLines = 0
        epdLastRenderedChars = 0L
      }
      consoleState.log("FeatherWing [Button A]: Starting composition...")
      consoleState.statusMessage = "FeatherWing [Button A]: Composing to 2.9\" eInk Galley..."
      runPacedRp2040(system, consoleState, terminal)
    } else if ((btnMask & 0x02) != 0) {
      // Button B: Scroll Galley or Force 4-Grayscale Proof Refresh
      val totalLines = system.paper.lineCount
      if (totalLines > GalleyFont.MaxVisibleLines) {
        val maxScroll = totalLines - GalleyFont.MaxVisibleLines
        if (epdScrollOffsetLines >= maxScroll) {
          epdScrollOffsetLines = 0
        } else {
          epdScrollOffsetLines = math.min(maxScroll, epdScrollOffsetLines + 4)
        }
        // Always use 4-Grayscale (EpdModeGray4) when scrolling via Button B so scrolled pages
        // render with full Press Roman grayscale anti-aliasing, gray line numbers, and zero ghosting.
        requestEpdRefresh(system, mode = EpdModeGray4, showCursor = false, forceNow = true)
        val msg = s"FeatherWing [Button B]: Galley scroll offset = -$epdScrollOffsetLines lines (of $totalLines)."
        consoleState.log(msg)
        consoleState.statusMessage = msg
      } else {
        requestEpdRefresh(system, mode = EpdModeGray4, showCursor = false)
        val msg = "FeatherWing [Button B]: Triggered 4-Grayscale camera-ready eInk refresh."
        consoleState.log(msg)
        consoleState.statusMessage = msg
      }
    } else if ((btnMask & 0x04) != 0) {
      // Button C: Master Reset & Rewind Tape
      epdScrollOffsetLines = 0
      system.reset()
      EscapementRoutine.initializeTable(system.memory)
      if (defaultTapeRef != null) {
        system.loadTape(Device.RightTape, defaultTapeRef)
      }
      System.gc()
      epdLastRenderedLines = 0
      epdLastRenderedChars = 0L
      requestEpdRefresh(system, mode = EpdModeGray4, showCursor = false)
      val msg = "FeatherWing [Button C]: Master Reset & tape rewound. Galley cleared."
      consoleState.log(msg)
      consoleState.statusMessage = msg
    }
  }

  private def handleRp2040Command(
    cmd: String,
    system: MtscSystem,
    consoleState: CeConsoleState,
    terminal: AnsiTerminal
  ): Unit = {
    val trimmed = cmd.trim
    val lower = Fmt.asciiLower(trimmed)
    if (trimmed == "E") {
      val cur = PicoSdk.pico_epd_get_diag(7)
      val next = if (cur == 2) 1 else 2
      PicoSdk.pico_epd_set_controller(next)
      requestEpdRefresh(system, mode = EpdModeGray4, showCursor = false, forceNow = true)
      val msg = s"Switched eInk driver -> ${epdDiagSummary()}"
      consoleState.log(msg)
      consoleState.statusMessage = msg
      return
    }
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

      case "e" | "epd" | "epd gray" =>
        requestEpdRefresh(system, mode = EpdModeGray4, showCursor = false, forceNow = true)
        val msg = s"4-Gray eInk refresh (${system.paper.lineCount} lines) — ${epdDiagSummary()}"
        consoleState.log(msg)
        consoleState.statusMessage = msg

      case "epd il0373" | "il0373" =>
        PicoSdk.pico_epd_set_controller(2)
        requestEpdRefresh(system, mode = EpdModeGray4, showCursor = false, forceNow = true)
        val msg = s"Switched to IL0373 (T5) 4-Gray eInk — ${epdDiagSummary()}"
        consoleState.log(msg)
        consoleState.statusMessage = msg

      case "epd ssd1680" | "ssd1680" =>
        PicoSdk.pico_epd_set_controller(1)
        requestEpdRefresh(system, mode = EpdModeGray4, showCursor = false, forceNow = true)
        val msg = s"Switched to SSD1680 4-Gray eInk — ${epdDiagSummary()}"
        consoleState.log(msg)
        consoleState.statusMessage = msg

      case "epd mono" =>
        requestEpdRefresh(system, mode = EpdModeFullMono, showCursor = false, forceNow = true)
        val msg = s"Mono eInk refresh (${system.paper.lineCount} lines) — ${epdDiagSummary()}"
        consoleState.log(msg)
        consoleState.statusMessage = msg

      case "epd flip" | "flip" =>
        epdFlip180 = !epdFlip180
        requestEpdRefresh(system, mode = EpdModeGray4, showCursor = false, forceNow = true)
        val msg = s"Toggled 2.9\" eInk Landscape 180° orientation (flip180=$epdFlip180)."
        consoleState.log(msg)
        consoleState.statusMessage = msg

      case "o" | "oled" =>
        PicoSdk.pico_oled_init()
        if (oledFlip180) {
          PicoSdk.pico_oled_set_flip(true)
        }
        updateOledDisplay(system, consoleState)
        val msg = s"128x32 SSD1306 OLED (#2900) — ${oledDiagSummary()}"
        consoleState.log(msg)
        consoleState.statusMessage = msg

      case "oled flip" =>
        oledFlip180 = !oledFlip180
        PicoSdk.pico_oled_set_flip(oledFlip180)
        updateOledDisplay(system, consoleState)
        val msg = s"Toggled 128x32 OLED 180° orientation (flip180=$oledFlip180)."
        consoleState.log(msg)
        consoleState.statusMessage = msg

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
      updateOledDisplay(system, consoleState)
      val bootRes = system.runBootLoader()
      updateHardwareLamps(system, isRunningLoop = false)
      updateOledDisplay(system, consoleState)
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
      updateOledDisplay(system, consoleState)
      lastOledUpdateUs = PicoSdk.pico_time_us_32()
      var lastPrintCount = system.translator.printCount
      var lastRightHead = system.rightTape.headPosition
      var lastLeftHead = system.leftTape.headPosition
      val hasBreakpoints = consoleState.breakpoints.nonEmpty
      val targetPeriodUs = delayMs.toInt * 1000
      var lastPrintDeadlineUs = lastOledUpdateUs

      while (res == StepResult.Normal && !hitBreakpoint && !interrupted && steps < 500000) {
        val prevPsar = system.memory.psarRaw
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
          if (PicoSdk.pico_oled_is_present()) {
            val nowUs = PicoSdk.pico_time_us_32()
            val dtUs = nowUs - lastOledUpdateUs
            if (dtUs >= 40000 || dtUs < 0) {
              lastOledUpdateUs = nowUs
              updateOledDisplay(system, consoleState)
            }
          }
        }

        // Progressive eInk update: whenever new characters are typed or a Carrier Return completes a line,
        // queue a non-flashing differential fast monochrome update.
        if (
          system.paper.totalCharactersPrinted != epdLastRenderedChars ||
          system.paper.completedLinesCount != epdLastRenderedLines
        ) {
          epdScrollOffsetLines = 0
          requestEpdRefresh(system, mode = EpdModeFastMono, showCursor = false)
        } else if (epdPendingMode >= 0 && (steps & 0x3f) == 0) {
          servicePendingEpdRefresh(system, showCursor = false)
        }

        if (hasBreakpoints && consoleState.hasBreakpointAt(system.memory.psarRaw)) {
          hitBreakpoint = true
        }

        if (consoleState.realTimePacing && delayMs > 0 && system.translator.printCount > lastPrintCount) {
          lastPrintCount = system.translator.printCount
          ledToggle = !ledToggle
          updateHardwareLamps(system, isRunningLoop = true, mtPulse = tapeMoved)
          if (PicoSdk.pico_oled_is_present()) {
            val nowUs = PicoSdk.pico_time_us_32()
            if (nowUs - lastOledUpdateUs >= 30000) {
              lastOledUpdateUs = nowUs
              updateOledDisplay(system, consoleState)
            }
          }

          if (PicoSdk.pico_stdio_usb_connected()) {
            CeConsoleView.render(terminal, system.cpu, consoleState)
            flushTerminal(terminal, forceFull = false)
          }

          // Wall-clock deadline pacing: subtract render + USB flush + CCU execution time from target period
          val nowUs = PicoSdk.pico_time_us_32()
          val targetUs = lastPrintDeadlineUs + targetPeriodUs
          val remainingUs = targetUs - nowUs
          if (remainingUs > 1000 && remainingUs <= targetPeriodUs) {
            PicoSdk.sleep_ms(remainingUs / 1000)
            lastPrintDeadlineUs = targetUs
          } else {
            lastPrintDeadlineUs = nowUs
          }

          if (PicoSdk.pico_getchar_timeout_us(0) >= 0 || (PicoSdk.pico_epd_poll_buttons() & 0x05) != 0) {
            interrupted = true
          }
        } else if ((steps & 0x1ff) == 0) {
          if (PicoSdk.pico_getchar_timeout_us(0) >= 0 || (PicoSdk.pico_epd_poll_buttons() & 0x05) != 0) {
            interrupted = true
          }
        }

        if (!system.cpu.state.multiplex && system.memory.psarRaw == prevPsar && !system.cpu.state.inhibit) {
          system.cpu.state.halted = true
          res = StepResult.Halted
        }
      }
    } finally {
      updateHardwareLamps(system, isRunningLoop = false)
      updateOledDisplay(system, consoleState)
      if (consoleState.realTimePacing && delayMs > 0) {
        PicoSdk.pico_serial_print("\u001b[?25h")
        PicoSdk.pico_serial_flush()
      }
      System.gc()
    }

    val hexPsar = Fmt.hex3(system.memory.psar.unsigned)
    if (interrupted) {
      requestEpdRefresh(system, mode = EpdModeFastMono, showCursor = true)
      consoleState.statusMessage =
        s"Paused by user at 0x$hexPsar after $steps steps (${heapSummary()})."
      consoleState.log(s"Execution paused at 0x$hexPsar.")
    } else if (hitBreakpoint) {
      requestEpdRefresh(system, mode = EpdModeFastMono, showCursor = true)
      consoleState.statusMessage =
        s"Breakpoint hit at 0x$hexPsar after $steps steps."
      consoleState.log(s"Breakpoint hit at 0x$hexPsar.")
    } else if (res == StepResult.Halted) {
      // Hybrid mode: trigger final crisp 4-Grayscale camera-ready proof refresh upon Halt!
      epdScrollOffsetLines = 0
      requestEpdRefresh(system, mode = EpdModeGray4, showCursor = false, forceNow = true)
      consoleState.statusMessage =
        s"Composition completed in $steps cycles (${system.paper.lineCount} lines -> 4-Gray eInk, ${heapSummary()})."
      consoleState.log(s"Program HALTED after $steps cycles. 4-Gray eInk refreshed. ${heapSummary()}")
    } else {
      requestEpdRefresh(system, mode = EpdModeFastMono, showCursor = true)
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
    val t0 = PicoSdk.pico_time_us_32()
    CeConsoleView.render(terminal, system.cpu, consoleState)
    val t1 = PicoSdk.pico_time_us_32()
    val renderUs = t1 - t0

    // 2. Measure full 38-row USB serial flush
    val t2 = PicoSdk.pico_time_us_32()
    val fullRows = flushTerminal(terminal, forceFull = true)
    val t3 = PicoSdk.pico_time_us_32()
    val flushFullUs = t3 - t2

    // 3. Measure 1,000 gate-level ILD bit-times on a self-contained non-destructive loop at 0x7f8
    val mem = system.memory
    val s = system.cpu.ild.signals
    val savedPsar = mem.psarRaw
    val savedAw = mem.aWordRaw
    val savedBw = mem.bWordRaw
    val savedR0L = mem.readByteAt(0)
    val savedR0H = mem.readByteAt(1)
    val savedB0 = mem.readByteAt(0x7f8)
    val savedB1 = mem.readByteAt(0x7f9)
    val savedB2 = mem.readByteAt(0x7fa)
    val savedB3 = mem.readByteAt(0x7fb)
    val savedLow = s.low
    val savedEq = s.eq
    val savedInh = s.inh
    val savedMpx = s.mpx
    val savedHalted = system.cpu.state.halted

    // Place ADDI R0, 1 (0x01d8 -> low=0xd8, high=0x01) and JMP 0x7f8 (0x07f8 -> low=0xf8, high=0x07) at 0x7f8
    mem.writeByteAt(0x7f8, 0xd8)
    mem.writeByteAt(0x7f9, 0x01)
    mem.writeByteAt(0x7fa, 0xf8)
    mem.writeByteAt(0x7fb, 0x07)
    mem.setPsarRaw(0x7f8)
    s.inh = false
    s.mpx = false
    s.masterReset = false
    mtsc.cpu.ild.Ild0303Clock.reset(s)
    system.cpu.ild.syncAllCombinational()

    system.cpu.ild.timingTrace.enabled = false
    val t4 = PicoSdk.pico_time_us_32()
    var bt = 0
    while (bt < 1000) {
      val _ = system.cpu.ild.stepBitTime()
      bt += 1
    }
    val t5 = PicoSdk.pico_time_us_32()
    val ild1000BtUs = t5 - t4
    val usPerBt = ild1000BtUs / 1000
    val effKhz = if (ild1000BtUs > 0) 1000000 / ild1000BtUs else 0

    // Restore all memory, registers, and signals
    mem.writeByteAt(0, savedR0L)
    mem.writeByteAt(1, savedR0H)
    mem.writeByteAt(0x7f8, savedB0)
    mem.writeByteAt(0x7f9, savedB1)
    mem.writeByteAt(0x7fa, savedB2)
    mem.writeByteAt(0x7fb, savedB3)
    mem.setPsarRaw(savedPsar)
    mem.setAWordRaw(savedAw)
    mem.setBWordRaw(savedBw)
    s.low = savedLow
    s.eq = savedEq
    s.inh = savedInh
    s.mpx = savedMpx
    system.cpu.state.halted = savedHalted
    mtsc.cpu.ild.Ild0303Clock.reset(s)
    system.cpu.ild.syncAllCombinational()
    system.cpu.ild.syncStateFromSignals()

    val summary =
      s"Bench: render=${renderUs / 1000}ms, usbFlush(${fullRows}r)=${flushFullUs / 1000}ms, ILD=${usPerBt}us/bt (${effKhz}kHz, ~${usPerBt * 12}us/insn)"
    consoleState.log(summary)
    consoleState.statusMessage = s"$summary │ ${heapSummary()}"
  }
}
