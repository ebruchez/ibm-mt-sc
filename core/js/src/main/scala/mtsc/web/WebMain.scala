package mtsc.web

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import mtsc.types.*
import mtsc.isa.{Decoder, Instruction}
import mtsc.cpu.{CcuEngineMode, StepResult}
import mtsc.cpu.ild.{IldScopeChannel, IldTimingSample, OscSubPhase}
import mtsc.asm.Disassembler
import mtsc.diag.{DiagnosticPrograms, EscapementRoutine}
import mtsc.periph.*
import mtsc.console.*

/**
 * Scala.js Browser Frontend Controller for the IBM MT/SC (Model 6310/6320) Emulator.
 *
 * Drives:
 * 1. The Dual-Layer Console Unit (Operator Faceplate overlay <-> Exposed CE Chassis)
 * 2. The 8-Button Magnetic Tape Transport Console
 * 3. The True-Proportional Selectric Composer Galley Proof Sheet (14 cps real-time pacing)
 * 4. The Customer Engineer Diagnostic Drawer (32 Working Registers, Disassembly, Core Memory, Event Log)
 */
object WebMain {

  /**
   * Default CCU execution engine for the Scala.js Web simulator:
   * - [[CcuEngineMode.Ild]]: Hardware-accurate gate- and latch-level ILD engine (`0303`–`0312`)
   * - [[CcuEngineMode.Behavioral]]: High-level ISA behavioral engine
   *
   * Can also be overridden in the browser via URL query parameter `?engine=ild` or `?engine=behavioral`.
   */
  val DefaultEngineMode: CcuEngineMode = CcuEngineMode.Ild

  private val system = new MtscSystem(initialEngineMode = DefaultEngineMode)
  private val consoleState = new CeConsoleState()
  private var currentProgramIdx: Int = 0
  private var runTimerId: Option[Int] = None
  private var soundEnabled: Boolean = true
  private var audioCtx: Option[js.Dynamic] = None
  private var memoryOverlayLayout: String = "2x1k"
  private var showAsciiInOverlay: Boolean = false
  private var selectedTapeTarget: Device = Device.RightTape
  private var scopePreset: String = "0601"
  private var scopeSpan: Int = 96

  def main(args: Array[String]): Unit = {
    if (js.typeOf(js.Dynamic.global.document) == "undefined") return
    val doc = js.Dynamic.global.document
    val root = doc.getElementById("mtsc-app")
    if (root == null || js.isUndefined(root)) return

    // Optional URL query override: ?engine=ild or ?engine=behavioral
    if (js.typeOf(js.Dynamic.global.window) != "undefined" && !js.isUndefined(js.Dynamic.global.window.location)) {
      val search = js.Dynamic.global.window.location.search.toString.toLowerCase
      if (search.contains("engine=behavioral") || search.contains("engine=isa")) {
        system.setEngineMode(CcuEngineMode.Behavioral)
      } else if (search.contains("engine=ild") || search.contains("engine=gate")) {
        system.setEngineMode(CcuEngineMode.Ild)
      }
    }
    consoleState.log(s"CCU Engine Mode: ${system.engineMode.label}.")

    // Start in Operator Faceplate mode by default so the user can unlatch it to reveal the CE chassis
    consoleState.faceplateInstalled = true
    consoleState.viewGalley = true

    // Default CE Test 4..1 Logic Probes to useful backplane signals
    consoleState.testProbes(3) = CeTestProbe.Prmt
    consoleState.testProbes(2) = CeTestProbe.Taken
    consoleState.testProbes(1) = CeTestProbe.CarOver
    consoleState.testProbes(0) = CeTestProbe.WrAcc
    system.cpu.ild.probeMaskEvaluator = s => consoleState.computeTestProbeMask(s)

    loadProgram(0)
    populateTestProbeSelects(doc)
    bindEvents(doc)
    renderAll(doc)
  }

  private def populateTestProbeSelects(doc: js.Dynamic): Unit = {
    for (testNum <- 1 to 4) {
      val sel = doc.getElementById(s"ce-probe-select-$testNum")
      if (sel != null && !js.isUndefined(sel)) {
        val sb = new StringBuilder()
        val currentProbe = consoleState.getTestProbe(testNum)
        for (p <- CeTestProbe.values) {
          val selectedAttr = if (p == currentProbe) " selected" else ""
          val escLabel = escapeHtml(s"${p.label} [ILD ${p.sheet}]")
          sb.append(s"""<option value="${p.id}"$selectedAttr>$escLabel</option>""")
        }
        sel.innerHTML = sb.toString()
        sel.addEventListener("change", (_: js.Dynamic) => {
          val chosenId = sel.value.toString
          CeTestProbe.fromId(chosenId).foreach { probe =>
            consoleState.setTestProbe(testNum, probe)
          }
          if (system.cpu.ild.timingTrace.isPreview) {
            system.cpu.ild.timingTrace.clear()
            system.cpu.ild.seedPreviewTraceIfEmpty()
          }
          playClickSound(400)
          renderAll(doc)
        })
      }
    }
  }

  private def loadProgram(idx: Int): Unit = {
    val all = DiagnosticPrograms.AllPrograms
    currentProgramIdx = ((idx % all.length) + all.length) % all.length
    val prog = all(currentProgramIdx)

    val img = prog.assemble()
    system.loadProgram(img)
    EscapementRoutine.initializeTable(system.memory)

    if (prog == DiagnosticPrograms.ContinuousCompositionDemo) {
      system.operatorPanel.measure = 140
      system.memory.writeReg(RegId(30), Word(140))
      system.memory.writeReg(RegId(28), Word(110))
      if (!system.rightTape.isTapeLoaded && !system.leftTape.isTapeLoaded) {
        system.loadTape(
          Device.RightTape,
          TapeCartridge.fromText(TapeCartridge.HistoricalSampleText, "Historical MT Tape")
        )
      }
      val targetStation = if (system.leftTape.isTapeLoaded && !system.rightTape.isTapeLoaded) Device.LeftTape else selectedTapeTarget
      system.setInputStation(targetStation)
    } else if (prog == DiagnosticPrograms.EndToEndCompositionDemo || prog == DiagnosticPrograms.TapeBootstrapLoader) {
      system.operatorPanel.measure = 100
      system.memory.writeReg(RegId(30), Word(100))
      system.memory.writeReg(RegId(28), Word(70))
      val sample = "The electronic logic used in the CCU is in the SLD family."
      val targetStation = if (system.leftTape.isTapeLoaded && !system.rightTape.isTapeLoaded) Device.LeftTape else selectedTapeTarget
      system.loadTape(targetStation, TapeCartridge.fromText(sample, "MTSC Test Tape"))
      if (prog == DiagnosticPrograms.TapeBootstrapLoader) {
        DiagnosticPrograms.TapeBootstrapLoader.setInputStation(system.memory, targetStation)
      } else {
        DiagnosticPrograms.EndToEndCompositionDemo.setInputStation(system.memory, targetStation)
      }
    }

    if (prog == DiagnosticPrograms.TapeBootstrapLoader) {
      system.memory.writeReg(RegId(8), Word(200))
    }

    if (prog == DiagnosticPrograms.EscapementAndJustifyDemo) {
      val sample = "The logic in CCU is in the SLD family."
      for (i <- 0 until sample.length) {
        system.memory.writeByte(Address(316 + i), sample.charAt(i).toInt)
      }
      system.memory.writeByte(Address(316 + sample.length), 0)
    }

    system.cpu.ild.timingTrace.clear()
    system.cpu.ild.seedPreviewTraceIfEmpty()

    consoleState.log(f"Loaded '${prog.name}' at PSAR 0x${system.memory.psar.unsigned}%03X.")
    consoleState.statusMessage = s"Loaded ${prog.name}. Press Run or START to begin composition."
  }

  private def bindEvents(doc: js.Dynamic): Unit = {
    def onClick(id: String, fn: () => Unit): Unit = {
      val el = doc.getElementById(id)
      if (el != null && !js.isUndefined(el)) {
        el.addEventListener("click", (_: js.Dynamic) => {
          fn()
          renderAll(doc)
        })
      }
    }

    // Faceplate toggle (Operator Faceplate <-> CE Chassis)
    onClick("btn-toggle-faceplate", () => {
      consoleState.toggleFaceplate()
      playClickSound(220)
    })

    // Power Rocker Switch (RESET / ON / OFF)
    onClick("pwr-reset", () => {
      stopContinuousRun()
      system.operatorPanel.powerSwitch = PowerSwitchPosition.Reset
      system.reset()
      system.cpu.ild.seedPreviewTraceIfEmpty()
      system.operatorPanel.powerSwitch = PowerSwitchPosition.On
      consoleState.powerOn = true
      consoleState.log("Power Rocker momentary RESET: Master Reset (PSAR = 0x222).")
      consoleState.statusMessage = "Master Reset complete."
      playClickSound(300)
    })
    onClick("pwr-on", () => {
      system.operatorPanel.powerSwitch = PowerSwitchPosition.On
      consoleState.powerOn = true
      consoleState.statusMessage = "System Power ON."
      playClickSound(380)
    })
    onClick("pwr-off", () => {
      stopContinuousRun()
      system.operatorPanel.powerSwitch = PowerSwitchPosition.Off
      consoleState.powerOn = false
      consoleState.statusMessage = "System Power OFF."
      playClickSound(180)
    })

    // CE Top Pushbuttons (Step, Display 4, 3, 2, 1, Clear, ClockDefault)
    onClick("ce-btn-step", () => {
      stopContinuousRun()
      stepOnce(doc)
      playClickSound(520)
    })
    onClick("ce-btn-dply-4", () => {
      consoleState.selectDisplayMode(CeDisplayMode.Display4)
      playClickSound(480)
    })
    onClick("ce-btn-dply-3", () => {
      consoleState.selectDisplayMode(CeDisplayMode.Display3)
      playClickSound(480)
    })
    onClick("ce-btn-dply-2", () => {
      consoleState.selectDisplayMode(CeDisplayMode.Display2)
      playClickSound(480)
    })
    onClick("ce-btn-dply-1", () => {
      consoleState.selectDisplayMode(CeDisplayMode.Display1)
      playClickSound(480)
    })
    onClick("ce-btn-dply-0", () => {
      consoleState.selectDisplayMode(CeDisplayMode.ClockDefault)
      playClickSound(480)
    })
    onClick("ce-btn-clear", () => {
      stopContinuousRun()
      consoleState.pressCeClear(system.cpu)
      playClickSound(440)
    })
    onClick("ce-btn-zero-ram", () => {
      stopContinuousRun()
      system.clearMemory()
      consoleState.memoryInspectionAddress = Address(0)
      consoleState.log("Zeroed all 2,048 bytes of Core Memory (0x000..0x7FF = 0x00, PSAR = 0x000).")
      consoleState.statusMessage =
        "Core Memory zeroed (0x000..0x7FF = 0x00). Key INITIAL LOADER (40..47), mount Program Tape, and click Run to cold boot!"
      playClickSound(420)
    })
    onClick("ce-btn-init-loader", () => {
      stopContinuousRun()
      InitialLoaderTable.loadIntoMemory(system.memory, paperTape = false, leftStation = false)
      system.cpu.state.mal = Address(42)
      system.cpu.state.halted = false
      if (system.cpu.isIldMode) {
        system.cpu.ild.syncSignalsFromState()
      }
      consoleState.memoryInspectionAddress = Address(32)
      consoleState.log("Keyed 8-byte INITIAL LOADER for Right Station into Core Memory (bytes 40..47, PSAR=42).")
      consoleState.statusMessage = "INITIAL LOADER (Right Station, m=0) loaded into Core Memory (PSAR = 42 / 0x02A)."
      playClickSound(600)
    })
    onClick("ce-btn-init-loader-left", () => {
      stopContinuousRun()
      InitialLoaderTable.loadIntoMemory(system.memory, paperTape = false, leftStation = true)
      system.cpu.state.mal = Address(42)
      system.cpu.state.halted = false
      if (system.cpu.isIldMode) {
        system.cpu.ild.syncSignalsFromState()
      }
      consoleState.memoryInspectionAddress = Address(32)
      consoleState.log("Keyed 8-byte INITIAL LOADER for Left Station (m=1) into Core Memory (bytes 40..47, PSAR=42).")
      consoleState.statusMessage = "INITIAL LOADER (Left Station, m=1) loaded into Core Memory (PSAR = 42 / 0x02A)."
      playClickSound(600)
    })

    // Clicking any of the 11 lamps in Operator mode selects its Setup parameter for interactive entry
    val lampParamMap = Map(
      5  -> (SetupParam.Clear, SetupParam.LeaderSpace),
      6  -> (SetupParam.Merge, SetupParam.MinIw),
      7  -> (SetupParam.Mode, SetupParam.MaxIw),
      8  -> (SetupParam.LineCount, SetupParam.MaxQuad),
      9  -> (SetupParam.Measure, SetupParam.MinHyphen),
      10 -> (SetupParam.LineIndent, SetupParam.DeadKey),
      11 -> (SetupParam.ParaIndent, SetupParam.ParaIndent)
    )
    onClick("lamp-4", () => {
      if (consoleState.faceplateInstalled) {
        // Toggle between primary (white) and secondary (red SETUP) parameter rows
        val next = if (system.operatorPanel.isSetupLampOn) SetupParam.Clear else SetupParam.LeaderSpace
        system.operatorPanel.selectSetupParam(next)
        consoleState.statusMessage = s"SETUP mode toggled: prompting [${next.label}] (${next.description})."
        playClickSound(500)
      }
    })
    for ((lampNum, (primaryParam, secondaryParam)) <- lampParamMap) {
      onClick(s"lamp-$lampNum", () => {
        if (consoleState.faceplateInstalled) {
          val chosen = if (system.operatorPanel.isSetupLampOn) secondaryParam else primaryParam
          system.operatorPanel.selectSetupParam(chosen)
          consoleState.statusMessage = s"Selected [${chosen.label}]: ${chosen.description}. Key digits then press START."
          playClickSound(500)
        }
      })
    }

    // 12-Key Console Keyboard (START, 0..9, R)
    val keyMappings: Seq[(String, ConsoleKey)] = Seq(
      ("key-start", ConsoleKey.Start),
      ("key-0",     ConsoleKey.Key0),
      ("key-1",     ConsoleKey.Key1),
      ("key-2",     ConsoleKey.Key2),
      ("key-3",     ConsoleKey.Key3),
      ("key-4",     ConsoleKey.Key4),
      ("key-5",     ConsoleKey.Key5),
      ("key-6",     ConsoleKey.Key6),
      ("key-7",     ConsoleKey.Key7),
      ("key-8",     ConsoleKey.Key8),
      ("key-9",     ConsoleKey.Key9),
      ("key-r",     ConsoleKey.KeyR)
    )
    for ((domId, keyEnum) <- keyMappings) {
      onClick(domId, () => {
        handleConsoleKeyPress(keyEnum, doc)
      })
    }

    // CE Slide Switches (Left: Bit/Word/P.S./Jump/Auto, Right: Proc/Edit/Addr/Data/Prog)
    for (g <- CeStepGranularity.values) {
      onClick(s"slider-left-${g.ordinal}", () => {
        consoleState.stepGranularity = g
        consoleState.singleStepMode = g != CeStepGranularity.Auto
        if (system.cpu.isIldMode) {
          system.cpu.ild.signals.clockSwitch = g
        }
        consoleState.log(s"Left CE slide switch set to [${g.label}]")
        consoleState.statusMessage = g match {
          case CeStepGranularity.Bit =>
            "Step granularity [Bit]: click [Step] to advance 1 hardware Bit Time (B1-4 -> B5-8 -> B9-12 -> B13-16)."
          case CeStepGranularity.Word =>
            "Step granularity [Word]: click [Step] to advance 1 hardware Instruction Time (I-0 -> I-1 -> ... -> I-9)."
          case CeStepGranularity.ProgramStep =>
            "Step granularity [P.S.]: click [Step] to execute 1 full Program Step (I-0 -> I-0)."
          case CeStepGranularity.Jump =>
            "Step granularity [Jump]: click [Step] to run until the next taken Branch / Incremental Jump instruction."
          case CeStepGranularity.Auto =>
            "Step granularity [Auto]: continuous clock enabled."
        }
        playClickSound(340)
      })
    }
    onClick("btn-probe-view-clock", () => {
      consoleState.faceplateInstalled = false
      consoleState.selectDisplayMode(CeDisplayMode.ClockDefault)
      consoleState.statusMessage =
        "CE Chassis exposed in [Clock / Test] mode (Display 0): Lamps 2..5 now display Test 4..1 logic probes."
      playClickSound(460)
    })
    for (e <- CeEditSelector.values) {
      onClick(s"slider-right-${e.ordinal}", () => {
        stopContinuousRun()
        consoleState.selectEditSelector(e, Some(system.cpu))
        playClickSound(340)
      })
    }

    // 8 Magnetic Tape Transport Console Pushbuttons
    val tapeButtons: Seq[(String, TapeConsoleButton)] = Seq(
      ("tape-btn-reread",       TapeConsoleButton.Reread),
      ("tape-btn-skip",         TapeConsoleButton.Skip),
      ("tape-btn-search",       TapeConsoleButton.Search),
      ("tape-btn-cover-unlock", TapeConsoleButton.CoverUnlock),
      ("tape-btn-unload-left",  TapeConsoleButton.UnloadLeft),
      ("tape-btn-load-left",    TapeConsoleButton.LoadLeft),
      ("tape-btn-unload-right", TapeConsoleButton.UnloadRight),
      ("tape-btn-load-right",   TapeConsoleButton.LoadRight)
    )
    for ((domId, btnEnum) <- tapeButtons) {
      onClick(domId, () => {
        val msg = system.pressTapeButton(btnEnum)
        consoleState.statusMessage = msg
        consoleState.log(s"Tape Console [${btnEnum.topLabel} ${btnEnum.bottomLabel}]: $msg")
        playClickSound(410)
      })
    }

    // Execution & Toolbar Controls
    onClick("btn-run-toggle", () => {
      if (runTimerId.isDefined) {
        stopContinuousRun()
        consoleState.statusMessage = f"Execution paused at 0x${system.memory.psar.unsigned}%03X."
      } else {
        startContinuousRun(doc)
      }
    })
    onClick("btn-step", () => {
      stopContinuousRun()
      stepOnce(doc)
      playClickSound(520)
    })
    onClick("btn-setup-seq", () => {
      val next = system.operatorPanel.advanceSetupPrompt(includeSecondary = true)
      next match {
        case Some(p) =>
          consoleState.statusMessage = s"Operator Setup [${p.label}]: ${p.description}. Key digits then press START."
        case None =>
          consoleState.statusMessage = "Operator Setup sequence complete."
      }
      playClickSound(460)
    })
    onClick("pill-station-right", () => {
      selectedTapeTarget = Device.RightTape
      system.setInputStation(Device.RightTape)
      consoleState.statusMessage = "Target Tape Station set to Right Station (Primary Input)."
      playClickSound(400)
      renderAll(doc)
    })
    onClick("pill-station-left", () => {
      selectedTapeTarget = Device.LeftTape
      system.setInputStation(Device.LeftTape)
      consoleState.statusMessage = "Target Tape Station set to Left Station (Merge / Revision Tape)."
      playClickSound(400)
      renderAll(doc)
    })
    def mountFactoryProgram(station: Device): Unit = {
      stopContinuousRun()
      val prog = DiagnosticPrograms.AllPrograms(currentProgramIdx)
      val cart = MtscSystem.createFactoryProgramTape(station, prog, system.operatorPanel.measure)
      system.loadTape(station, cart)
      selectedTapeTarget = station
      val stName = if (station == Device.LeftTape) "Left Station" else "Right Station"
      val keyBtn = if (station == Device.LeftTape) "Key Left" else "Key Right"
      consoleState.log(s"Mounted ${cart.label} (${cart.length} binary 8-bit tracks) on $stName.")
      if (system.memory.psar.unsigned >= 42 && system.memory.psar.unsigned <= 47) {
        consoleState.statusMessage =
          s"Mounted ${cart.label} on $stName. INITIAL LOADER is ready at 0x02A — click Run to boot Core Memory!"
      } else {
        consoleState.statusMessage =
          s"Mounted ${cart.label} (${cart.length} tracks) on $stName. Open CE Console, click '$keyBtn' (or key 40..47 manually), then Run!"
      }
      playClickSound(560)
      renderAll(doc)
    }

    onClick("btn-quick-prog-left", () => {
      mountFactoryProgram(Device.LeftTape)
    })
    onClick("btn-quick-prog-right", () => {
      mountFactoryProgram(Device.RightTape)
    })
    onClick("btn-mount-program-tape", () => {
      mountFactoryProgram(selectedTapeTarget)
    })
    onClick("btn-quick-sample-left", () => {
      system.loadTape(Device.LeftTape, TapeCartridge.fromText(TapeCartridge.HistoricalMergeSampleText, "Merge Revision Tape"))
      selectedTapeTarget = Device.LeftTape
      system.setInputStation(Device.LeftTape)
      consoleState.log("Mounted sample revision tape on Left Station.")
      consoleState.statusMessage = "Sample revision tape mounted and loaded on Left Station."
      playClickSound(550)
      renderAll(doc)
    })
    onClick("btn-quick-sample-right", () => {
      system.loadTape(Device.RightTape, TapeCartridge.fromText(TapeCartridge.HistoricalSampleText, "Historical MT Tape"))
      selectedTapeTarget = Device.RightTape
      system.setInputStation(Device.RightTape)
      consoleState.log("Mounted historical 4-paragraph tape on Right Station.")
      consoleState.statusMessage = "Historical manuscript tape mounted and loaded on Right Station."
      playClickSound(550)
      renderAll(doc)
    })
    onClick("btn-mount-sample-tape", () => {
      val (sampleText, label, name) = if (selectedTapeTarget == Device.LeftTape) {
        (TapeCartridge.HistoricalMergeSampleText, "Merge Revision Tape", "Left Station")
      } else {
        (TapeCartridge.HistoricalSampleText, "Historical MT Tape", "Right Station")
      }
      system.loadTape(selectedTapeTarget, TapeCartridge.fromText(sampleText, label))
      system.setInputStation(selectedTapeTarget)
      consoleState.log(s"Mounted $label on $name.")
      consoleState.statusMessage = s"Mounted $label on $name. Ready for composition."
      playClickSound(550)
      renderAll(doc)
    })
    onClick("btn-inject-parity", () => {
      val target = if (selectedTapeTarget == Device.LeftTape) system.leftTape else system.rightTape
      val name = if (selectedTapeTarget == Device.LeftTape) "Left Tape" else "Right Tape"
      if (target.injectParityError()) {
        consoleState.log(f"Injected parity fault on $name track ${target.headPosition}.")
        consoleState.statusMessage = f"Parity fault injected on $name track ${target.headPosition}. Next read will stall with RESET/ERR lamp."
      } else {
        consoleState.statusMessage = s"Cannot inject parity error: mount and load $name first."
      }
      playClickSound(300)
      renderAll(doc)
    })
    onClick("btn-clear-galley", () => {
      system.paper.clear()
      val vp = doc.getElementById("galley-scroll-viewport")
      if (vp != null && !js.isUndefined(vp)) {
        vp.scrollTop = 0
      }
      consoleState.statusMessage = "Fresh camera-ready galley sheet loaded into Selectric Composer."
    })
    onClick("btn-engine-toggle", () => {
      val mode = system.toggleEngineMode()
      consoleState.log(s"Switched CCU Engine to ${mode.label}.")
      consoleState.statusMessage = s"CCU Execution Engine switched to ${mode.label}."
      playClickSound(440)
    })
    onClick("btn-sound-toggle", () => {
      soundEnabled = !soundEnabled
      consoleState.statusMessage = s"Mechanical acoustics ${if (soundEnabled) "ENABLED" else "MUTED"}."
    })

    // Speed buttons
    onClick("speed-1x", () => {
      consoleState.realTimePacing = true
      consoleState.pacingSpeed = 1.0
      consoleState.statusMessage = "Speed set to 1x (14 cps authentic Selectric Composer speed)."
    })
    onClick("speed-2x", () => {
      consoleState.realTimePacing = true
      consoleState.pacingSpeed = 2.0
      consoleState.statusMessage = "Speed set to 2x (28 cps)."
    })
    onClick("speed-5x", () => {
      consoleState.realTimePacing = true
      consoleState.pacingSpeed = 5.0
      consoleState.statusMessage = "Speed set to 5x (70 cps)."
    })
    onClick("speed-max", () => {
      consoleState.realTimePacing = false
      consoleState.statusMessage = "Speed set to MAX (instant execution)."
    })

    // Program selector dropdown
    val progSelect = doc.getElementById("select-program")
    if (progSelect != null && !js.isUndefined(progSelect)) {
      progSelect.addEventListener("change", (ev: js.Dynamic) => {
        stopContinuousRun()
        val idx = ev.target.value.toString.toIntOption.getOrElse(0)
        loadProgram(idx)
        renderAll(doc)
      })
    }

    // Custom Tape Loader Modal / Textarea
    onClick("btn-mount-custom-tape", () => {
      val ta = doc.getElementById("custom-tape-text")
      if (ta != null && !js.isUndefined(ta)) {
        val text = ta.value.toString
        if (text.trim.nonEmpty) {
          stopContinuousRun()
          val name = if (selectedTapeTarget == Device.LeftTape) "Left Station" else "Right Station"
          val label = if (selectedTapeTarget == Device.LeftTape) "Custom Revision Tape" else "Custom Copy Tape"
          if (system.memory.readWord(Address.PowerOn) == Word.Zero || currentProgramIdx != 0) {
            loadProgram(0)
          } else {
            system.cpu.state.halted = false
            system.memory.psar = Address.PowerOn
          }
          system.loadTape(selectedTapeTarget, TapeCartridge.fromText(text, label))
          system.setInputStation(selectedTapeTarget)
          consoleState.log(s"Mounted $label (${text.length} chars) on $name.")
          consoleState.statusMessage = s"Mounted $label (${text.length} chars) on $name. Press Run or 0/TAPE to compose!"
          playClickSound(550)
          renderAll(doc)
        }
      }
    })

    // Core Storage "Zero Core Memory" & "Jump to PSAR" buttons
    onClick("btn-mem-zero", () => {
      stopContinuousRun()
      system.clearMemory()
      consoleState.memoryInspectionAddress = Address(0)
      consoleState.log("Zeroed all 2,048 bytes of Core Memory (0x000..0x7FF = 0x00, PSAR = 0x000).")
      consoleState.statusMessage =
        "Core Memory zeroed (0x000..0x7FF = 0x00). Key INITIAL LOADER (40..47), mount Program Tape, and click Run to cold boot!"
      playClickSound(420)
    })
    onClick("btn-mem-psar", () => {
      val memEl = doc.getElementById("ce-memory-dump")
      if (memEl != null && !js.isUndefined(memEl)) {
        val psar = system.memory.psar.unsigned
        val row = psar / 16
        val targetScroll = math.max(0.0, (row - 3) * 18.5)
        memEl.scrollTop = targetScroll
      }
    })

    // Core Storage Full Map Overlay Dialog
    val overlayDialog = doc.getElementById("mem-overlay-dialog")
    onClick("btn-mem-expand", () => {
      if (overlayDialog != null && !js.isUndefined(overlayDialog)) {
        val psar = system.memory.psar.unsigned
        val mal  = system.cpu.state.mal.unsigned
        renderMemoryOverlay(doc, psar, mal)
        overlayDialog.showModal()
      }
    })
    onClick("btn-close-mem-overlay", () => {
      if (overlayDialog != null && !js.isUndefined(overlayDialog)) {
        overlayDialog.close()
      }
    })
    onClick("btn-mem-layout-2x1k", () => {
      memoryOverlayLayout = "2x1k"
      val b1 = doc.getElementById("btn-mem-layout-2x1k")
      val b2 = doc.getElementById("btn-mem-layout-64x32")
      if (b1 != null && !js.isUndefined(b1)) b1.className = "btn-chip active"
      if (b2 != null && !js.isUndefined(b2)) b2.className = "btn-chip"
      val psar = system.memory.psar.unsigned
      val mal  = system.cpu.state.mal.unsigned
      renderMemoryOverlay(doc, psar, mal)
    })
    onClick("btn-mem-layout-64x32", () => {
      memoryOverlayLayout = "64x32"
      val b1 = doc.getElementById("btn-mem-layout-2x1k")
      val b2 = doc.getElementById("btn-mem-layout-64x32")
      if (b1 != null && !js.isUndefined(b1)) b1.className = "btn-chip"
      if (b2 != null && !js.isUndefined(b2)) b2.className = "btn-chip active"
      val psar = system.memory.psar.unsigned
      val mal  = system.cpu.state.mal.unsigned
      renderMemoryOverlay(doc, psar, mal)
    })
    onClick("btn-mem-toggle-ascii", () => {
      showAsciiInOverlay = !showAsciiInOverlay
      val btn = doc.getElementById("btn-mem-toggle-ascii")
      if (btn != null && !js.isUndefined(btn)) {
        btn.textContent = if (showAsciiInOverlay) "ASCII: On" else "ASCII: Off"
        btn.className = if (showAsciiInOverlay) "btn-chip active" else "btn-chip"
      }
      val psar = system.memory.psar.unsigned
      val mal  = system.cpu.state.mal.unsigned
      renderMemoryOverlay(doc, psar, mal)
    })

    // Fallback for backdrop click dismissing dialog
    if (overlayDialog != null && !js.isUndefined(overlayDialog)) {
      overlayDialog.addEventListener("click", (ev: js.Dynamic) => {
        if (ev.target == overlayDialog) {
          val rect = overlayDialog.getBoundingClientRect()
          val cy = ev.clientY.asInstanceOf[Double]
          val cx = ev.clientX.asInstanceOf[Double]
          val isContent =
            rect.top.asInstanceOf[Double] <= cy && cy <= rect.bottom.asInstanceOf[Double] &&
            rect.left.asInstanceOf[Double] <= cx && cx <= rect.right.asInstanceOf[Double]
          if (!isContent) {
            overlayDialog.close()
          }
        }
      })
    }

    // 240 kHz Single-Shot (SSA/SSB) Oscilloscope / Timing Diagram Controls
    val scopePresetEl = doc.getElementById("ild-scope-preset")
    if (scopePresetEl != null && !js.isUndefined(scopePresetEl)) {
      scopePresetEl.addEventListener("change", (ev: js.Dynamic) => {
        scopePreset = ev.target.value.toString
        playClickSound(420)
        renderIldOscilloscope(doc)
      })
    }

    val scopeSpanEl = doc.getElementById("ild-scope-span")
    if (scopeSpanEl != null && !js.isUndefined(scopeSpanEl)) {
      scopeSpanEl.addEventListener("change", (ev: js.Dynamic) => {
        scopeSpan = ev.target.value.toString.toIntOption.getOrElse(96)
        playClickSound(420)
        renderIldOscilloscope(doc)
      })
    }

    onClick("btn-scope-step-bit", () => {
      stopContinuousRun()
      if (!system.cpu.isIldMode) {
        system.setEngineMode(CcuEngineMode.Ild)
      }
      val _ = system.stepByGranularity(CeStepGranularity.Bit)
      val s = system.cpu.ild.signals
      consoleState.statusMessage =
        f"[Scope +1 Bit Time] I-${s.insnTime} • ${bitTimeLabel(s.bitTimeIndex)} | PSAR: 0x${system.memory.psar.unsigned}%03X | MAL: 0x${s.malValue}%03X | PB: 0x${s.pBufferNibble}%X QB: 0x${s.qBufferNibble}%X ACC: 0x${s.accNibble}%X"
      playClickSound(540)
    })

    onClick("btn-scope-step-word", () => {
      stopContinuousRun()
      if (!system.cpu.isIldMode) {
        system.setEngineMode(CcuEngineMode.Ild)
      }
      val _ = system.stepByGranularity(CeStepGranularity.Word)
      val s = system.cpu.ild.signals
      consoleState.statusMessage =
        f"[Scope +1 Word Time] I-${s.insnTime} • ${bitTimeLabel(s.bitTimeIndex)} | PSAR: 0x${system.memory.psar.unsigned}%03X | MAL: 0x${s.malValue}%03X | PB: 0x${s.pBufferNibble}%X QB: 0x${s.qBufferNibble}%X ACC: 0x${s.accNibble}%X"
      playClickSound(500)
    })

    onClick("btn-scope-clear", () => {
      system.cpu.ild.timingTrace.clear()
      consoleState.statusMessage = "Oscilloscope trace buffer cleared. Step by Bit or Word to capture 240 kHz SSA/SSB waveforms."
      playClickSound(380)
    })

    // Keyboard shortcuts
    js.Dynamic.global.window.addEventListener("keydown", (ev: js.Dynamic) => {
      val tag = if (!js.isUndefined(ev.target) && ev.target != null && !js.isUndefined(ev.target.tagName))
        ev.target.tagName.toString.toUpperCase
      else ""
      if (tag != "TEXTAREA" && tag != "INPUT" && tag != "SELECT") {
        val keyStr = ev.key.toString
        keyStr match {
          case "f" | "F" =>
            consoleState.toggleFaceplate()
            playClickSound(220)
            renderAll(doc)
          case "Escape" =>
            val overlay = doc.getElementById("mem-overlay-dialog")
            if (overlay != null && !js.isUndefined(overlay)) {
              val isOpen = if (!js.isUndefined(overlay.open)) overlay.open.asInstanceOf[Boolean] else false
              if (isOpen) overlay.close()
            }
          case "s" | "S" =>
            stopContinuousRun()
            stepOnce(doc)
            playClickSound(520)
            renderAll(doc)
          case "Enter" =>
            handleConsoleKeyPress(ConsoleKey.Start, doc)
            renderAll(doc)
          case "Backspace" | "Delete"
              if !consoleState.faceplateInstalled &&
                (consoleState.editSelector == CeEditSelector.Addr || consoleState.editSelector == CeEditSelector.Data) =>
            stopContinuousRun()
            consoleState.pressCeClear(system.cpu)
            playClickSound(440)
            renderAll(doc)
          case "r" | "R"
              if !consoleState.faceplateInstalled &&
                (consoleState.editSelector == CeEditSelector.Addr || consoleState.editSelector == CeEditSelector.Data) =>
            handleConsoleKeyPress(ConsoleKey.KeyR, doc)
            renderAll(doc)
          case "r" | "R" if consoleState.faceplateInstalled && system.operatorPanel.activeSetupParam.isDefined =>
            handleConsoleKeyPress(ConsoleKey.KeyR, doc)
            renderAll(doc)
          case d if d.length == 1 && d.charAt(0).isDigit =>
            val digit = d.charAt(0) - '0'
            if (
              consoleState.faceplateInstalled ||
              consoleState.editSelector == CeEditSelector.Addr ||
              consoleState.editSelector == CeEditSelector.Data
            ) {
              ConsoleKey.fromDigit(digit).foreach(k => handleConsoleKeyPress(k, doc))
              renderAll(doc)
            } else {
              digit match {
                case 0 => consoleState.selectDisplayMode(CeDisplayMode.ClockDefault); renderAll(doc)
                case 1 => consoleState.selectDisplayMode(CeDisplayMode.Display1); renderAll(doc)
                case 2 => consoleState.selectDisplayMode(CeDisplayMode.Display2); renderAll(doc)
                case 3 => consoleState.selectDisplayMode(CeDisplayMode.Display3); renderAll(doc)
                case 4 => consoleState.selectDisplayMode(CeDisplayMode.Display4); renderAll(doc)
                case _ => ()
              }
            }
          case _ => ()
        }
      }
    })
  }

  private def handleConsoleKeyPress(key: ConsoleKey, doc: js.Dynamic): Unit = {
    playClickSound(450)
    // Authentic CE Edit Addr / Edit Data hardware bit entry (ILD 0308 & Page 0603)
    if (consoleState.pressCeKey(key, system.cpu).isDefined) {
      return
    }

    val hadActiveSetup = system.operatorPanel.activeSetupParam.isDefined
    val msg = system.operatorPanel.pressKey(key)
    consoleState.statusMessage = msg
    consoleState.log(s"Keypad [${key.topLegend}]: $msg")

    // Sync measure into R30/R28 if Measure was just updated for the Continuous Composer demo
    if (hadActiveSetup && system.memory.readWord(Address(546)).unsigned != 0) {
      val m = math.min(240, math.max(60, system.operatorPanel.measure))
      system.memory.writeReg(RegId(30), Word(m))
      system.memory.writeReg(RegId(28), Word(math.max(40, m - 30)))
    }

    // If START or 0/TAPE was pressed with no active setup prompt, start composition!
    if (!hadActiveSetup && (key == ConsoleKey.Start || key == ConsoleKey.Key0)) {
      if (system.cpu.state.halted) {
        if (system.memory.readWord(Address.PowerOn) != Word.Zero) {
          system.cpu.state.halted = false
          system.memory.psar = Address.PowerOn
        } else {
          loadProgram(currentProgramIdx)
        }
      }
      startContinuousRun(doc)
    }
  }

  private def bitTimeLabel(idx: Int): String = idx match {
    case 0 => "B1-4"
    case 1 => "B5-8"
    case 2 => "B9-12"
    case _ => "B13-16"
  }

  private def toNibbleBin(n: Int): String = {
    val v = n & 0xf
    s"${(v >> 3) & 1}${(v >> 2) & 1}${(v >> 1) & 1}${v & 1}"
  }

  private def stepOnce(doc: js.Dynamic): StepResult = {
    if (!consoleState.powerOn) {
      consoleState.statusMessage = "System Power is OFF. Switch Power to ON."
      return StepResult.Halted
    }

    val prevPrintCount = system.translator.printCount
    val prevLines = system.paper.lines.length
    val prevAddr = system.memory.psar
    val gran = consoleState.stepGranularity
    val res = system.stepByGranularity(gran)

    if (system.translator.printCount > prevPrintCount) {
      if (system.paper.lines.length > prevLines) {
        playCarrierReturnSound()
      } else {
        playTypeStrikeSound()
      }
    }

    res match {
      case StepResult.Halted =>
        consoleState.statusMessage = f"Program HALTED at 0x${system.memory.psar.unsigned}%03X."
      case StepResult.Stalled =>
        consoleState.statusMessage = "I/O Stalled (Inhibit latch active)."
      case StepResult.Error(err) =>
        consoleState.statusMessage = s"Execution Error: $err"
      case StepResult.Normal =>
        if (system.cpu.isIldMode) {
          val s = system.cpu.ild.signals
          val btLbl = bitTimeLabel(s.bitTimeIndex)
          gran match {
            case CeStepGranularity.Bit | CeStepGranularity.Word if system.cpu.ild.isMidInstruction =>
              consoleState.statusMessage =
                f"[${gran.label}] I-${s.insnTime} • $btLbl | PSAR: 0x${system.memory.psar.unsigned}%03X | MAL: 0x${s.malValue}%03X | PB: 0x${s.pBufferNibble}%X QB: 0x${s.qBufferNibble}%X ACC: 0x${s.accNibble}%X"
            case CeStepGranularity.Jump =>
              consoleState.statusMessage =
                f"[Jump] Taken branch reached: 0x${prevAddr.unsigned}%03X -> 0x${system.memory.psar.unsigned}%03X."
            case _ =>
              consoleState.statusMessage =
                f"Stepped 0x${prevAddr.unsigned}%03X -> 0x${system.memory.psar.unsigned}%03X (I-${s.insnTime}, $btLbl)."
          }
        } else {
          consoleState.statusMessage = f"Stepped 0x${prevAddr.unsigned}%03X -> 0x${system.memory.psar.unsigned}%03X."
        }
    }
    res
  }

  private def startContinuousRun(doc: js.Dynamic): Unit = {
    if (runTimerId.isDefined) return
    if (!consoleState.powerOn) {
      consoleState.statusMessage = "Cannot run: Power Switch is OFF."
      return
    }
    if (consoleState.editSelector != CeEditSelector.Proc) {
      consoleState.selectEditSelector(CeEditSelector.Proc, Some(system.cpu))
    }
    system.finishCurrentInstruction()
    if (system.cpu.state.halted) {
      if (system.memory.readWord(Address.PowerOn) != Word.Zero) {
        system.cpu.state.halted = false
        system.memory.psar = Address.PowerOn
      } else {
        loadProgram(currentProgramIdx)
      }
    }

    val bootingFromLoader = system.memory.psar.unsigned >= 42 && system.memory.psar.unsigned <= 47

    if (!consoleState.realTimePacing) {
      // Instant execution
      if (bootingFromLoader) {
        val bootRes = system.runBootLoader()
        consoleState.statusMessage = bootRes match {
          case StepResult.Normal =>
            f"Cold boot complete! Program loaded from tape — CPU ready at 0x${system.memory.psar.unsigned}%03X. Mount a manuscript tape and press Run."
          case StepResult.Stalled =>
            "Initial Loader stalled on I/O (mount & load a Program Tape on the selected station)."
          case other =>
            s"Cold boot stopped: $other"
        }
        renderAll(doc)
        return
      }

      val res = system.run(maxCycles = 100000L)
      consoleState.statusMessage = res match {
        case StepResult.Halted =>
          s"Completed composition (${system.paper.lines.length} lines printed on galley proof)."
        case other =>
          s"Run finished: $other"
      }
      renderAll(doc)
      return
    }

    val intervalMs = math.max(12, consoleState.printDelayMs.toInt)
    consoleState.statusMessage =
      if (bootingFromLoader) "Cold-booting Core Memory from Factory Program Tape via INITIAL LOADER (0x02A)..."
      else f"Composing at ${consoleState.pacingSpeed}%.0fx (${(14 * consoleState.pacingSpeed).toInt} cps)..."

    val handle = js.Dynamic.global.window.setInterval(() => {
      var stepsThisTick = 0
      var printedChar = false
      var stopped = false

      while (!printedChar && !stopped && stepsThisTick < 600) {
        val prevPrints = system.translator.printCount
        val prevLines = system.paper.lines.length
        val prevPsar = system.memory.psar
        val res = system.step()
        stepsThisTick += 1

        // Update simulated clock counters for CE lamps when in Behavioral mode
        if (!system.cpu.isIldMode) {
          system.cpu.state.insnTimeCtr = (stepsThisTick % 6)
          system.cpu.state.bitTimeCtr = (stepsThisTick & 3)
        }

        if (bootingFromLoader && (system.memory.psar.unsigned < 42 || system.memory.psar.unsigned > 47)) {
          // Step past page-0 trampoline `JMP entryPoint` if present so PSAR lands directly on `0x222`
          if (res == StepResult.Normal && !system.cpu.state.halted && system.memory.psar.unsigned < 256) {
            Decoder.decode(system.memory.readWord(system.memory.psar)) match {
              case Right(Instruction.Branch(Condition.Unconditional, false, target))
                  if target != system.memory.psar && target.unsigned >= 256 =>
                val _ = system.step()
              case _ => ()
            }
          }
          stopped = true
          consoleState.log(f"Cold boot complete: Factory Program Tape loaded, CPU ready at 0x${system.memory.psar.unsigned}%03X.")
          consoleState.statusMessage =
            f"Cold boot complete! Program loaded from tape — CPU ready at 0x${system.memory.psar.unsigned}%03X. Mount a manuscript tape and press Run!"
        } else if (system.translator.printCount > prevPrints) {
          printedChar = true
          if (system.paper.lines.length > prevLines) {
            playCarrierReturnSound()
            // Check hardware Line Stop latch at end of line!
            if (system.operatorPanel.lineStopLatch) {
              system.operatorPanel.lineStopLatch = false
              stopped = true
              consoleState.statusMessage = "Stopped at end of line (LINE STOP latch)."
              consoleState.log("LINE STOP completed at end of line.")
            }
          } else {
            playTypeStrikeSound()
          }
        }

        if (consoleState.breakpoints.contains(system.memory.psar)) {
          stopped = true
          consoleState.statusMessage = f"Breakpoint hit at 0x${system.memory.psar.unsigned}%03X."
        } else if (!system.cpu.state.multiplex && system.memory.psar == prevPsar && !system.cpu.state.inhibit) {
          system.cpu.state.halted = true
          stopped = true
          consoleState.statusMessage =
            s"Composition complete (${system.paper.lines.length} lines on galley proof)."
        } else if (res != StepResult.Normal) {
          stopped = true
          consoleState.statusMessage = res match {
            case StepResult.Stalled =>
              "Execution stalled on I/O (check Tape Reader / Parity Error)."
            case StepResult.Halted =>
              s"Composition complete (${system.paper.lines.length} lines on galley proof)."
            case StepResult.Error(msg) =>
              s"CPU Error: $msg"
            case StepResult.Normal => ""
          }
        }
      }

      if (stopped) {
        stopContinuousRun()
      }
      renderAll(doc)
    }, intervalMs)

    runTimerId = Some(handle.asInstanceOf[Int])
  }

  private def stopContinuousRun(): Unit = {
    runTimerId.foreach { id =>
      js.Dynamic.global.window.clearInterval(id)
    }
    runTimerId = None
  }

  private def renderAll(doc: js.Dynamic): Unit = {
    def setText(id: String, text: String): Unit = {
      val el = doc.getElementById(id)
      if (el != null && !js.isUndefined(el)) el.textContent = text
    }

    def setClassFlag(id: String, cls: String, active: Boolean): Unit = {
      val el = doc.getElementById(id)
      if (el != null && !js.isUndefined(el)) {
        if (active) el.classList.add(cls) else el.classList.remove(cls)
      }
    }

    // 1. Faceplate state (Operator Faceplate vs CE Chassis)
    setClassFlag("console-unit", "faceplate-removed", !consoleState.faceplateInstalled)
    setText(
      "btn-toggle-faceplate",
      if (consoleState.faceplateInstalled) "Unlatch Operator Faceplate (Reveal CE Console)"
      else "Install Operator Faceplate (Show Operator Console)"
    )
    setText(
      "console-mode-badge",
      if (consoleState.faceplateInstalled)
        s"OPERATOR MODE (FACEPLATE INSTALLED • ENGINE: ${system.engineMode.label.toUpperCase})"
      else
        s"CUSTOMER ENGINEER MODE (EXPOSED CHASSIS • ${consoleState.displayMode.rowLabel.toUpperCase} • ENGINE: ${system.engineMode.label.toUpperCase})"
    )

    // 2. Power Rocker Switch
    setClassFlag("pwr-reset", "active", system.operatorPanel.powerSwitch == PowerSwitchPosition.Reset)
    setClassFlag("pwr-on",    "active", consoleState.powerOn && system.operatorPanel.powerSwitch == PowerSwitchPosition.On)
    setClassFlag("pwr-off",   "active", !consoleState.powerOn || system.operatorPanel.powerSwitch == PowerSwitchPosition.Off)

    // 3. 11 Indicator Lamps
    val lamps = consoleState.computeLamps(system.cpu)
    for (i <- lamps.indices) {
      val lampNum = i + 1
      val lampState = lamps(i)
      setClassFlag(s"lamp-$lampNum", "lit", lampState.isOn)
      setClassFlag(
        s"lamp-$lampNum",
        "setup-selected",
        consoleState.faceplateInstalled && system.operatorPanel.activeSetupParam.exists(_.lampIndex == lampNum)
      )
    }

    // Highlight active CE Display row
    setClassFlag("ce-btn-dply-4", "active", consoleState.displayMode == CeDisplayMode.Display4)
    setClassFlag("ce-btn-dply-3", "active", consoleState.displayMode == CeDisplayMode.Display3)
    setClassFlag("ce-btn-dply-2", "active", consoleState.displayMode == CeDisplayMode.Display2)
    setClassFlag("ce-btn-dply-1", "active", consoleState.displayMode == CeDisplayMode.Display1)
    setClassFlag("ce-btn-dply-0", "active", consoleState.displayMode == CeDisplayMode.ClockDefault)

    setClassFlag("ce-row-dply-4", "active-row", consoleState.displayMode == CeDisplayMode.Display4)
    setClassFlag("ce-row-dply-3", "active-row", consoleState.displayMode == CeDisplayMode.Display3)
    setClassFlag("ce-row-dply-2", "active-row", consoleState.displayMode == CeDisplayMode.Display2)
    setClassFlag("ce-row-dply-1", "active-row", consoleState.displayMode == CeDisplayMode.Display1)
    setClassFlag("ce-row-lip",    "active-row", consoleState.displayMode == CeDisplayMode.ClockDefault)

    // 4. CE Bottom Slide Switches
    for (g <- CeStepGranularity.values) {
      setClassFlag(s"slider-left-${g.ordinal}", "active", consoleState.stepGranularity == g)
    }
    val leftThumb = doc.getElementById("slider-left-thumb")
    if (leftThumb != null && !js.isUndefined(leftThumb)) {
      leftThumb.style.setProperty("--pos", consoleState.stepGranularity.ordinal.toString)
    }

    for (e <- CeEditSelector.values) {
      setClassFlag(s"slider-right-${e.ordinal}", "active", consoleState.editSelector == e)
    }
    val rightThumb = doc.getElementById("slider-right-thumb")
    if (rightThumb != null && !js.isUndefined(rightThumb)) {
      rightThumb.style.setProperty("--pos", consoleState.editSelector.ordinal.toString)
    }

    // 5. Operator Setup Telemetry Bar
    val op = system.operatorPanel
    setText("op-val-mode",    s"${op.mode.codeLetter} (${op.mode.displayName})")
    setText("op-val-measure", s"${op.measure} u")
    setText("op-val-lines",   s"${op.lineCount}")
    setText("op-val-indent",  s"L:${op.lineIndent}u / P:${op.paraIndent}u")
    setText("op-val-iw",      s"${op.minInterword}..${op.maxInterword} u (Quad ${op.maxQuad}u)")
    val promptInfo = op.activeSetupParam match {
      case Some(p) =>
        val buf = if (op.entryBuffer.isEmpty) "—" else op.entryBuffer
        s"PROMPT [${p.label}]: ${p.description} • Keyed: $buf"
      case None =>
        if (op.lineStopLatch) "LINE STOP LATCH ARMED (will stop at end of line)"
        else "Click any lamp label or press [Setup Prompt] to configure job parameters"
    }
    setText("op-active-prompt", promptInfo)

    // 6. 8-Button Magnetic Tape Transport Console
    val rTape = system.rightTape
    val lTape = system.leftTape
    val coverUnlocked = rTape.coverUnlocked && lTape.coverUnlocked

    setClassFlag("tape-btn-cover-unlock", "lit-amber", coverUnlocked)
    setClassFlag("tape-btn-load-left",    "lit-green", lTape.isTapeLoaded)
    setClassFlag("tape-btn-unload-left",  "lit-amber", !lTape.isTapeLoaded && lTape.mountedCartridge.isDefined)
    setClassFlag("tape-btn-load-right",   "lit-green", rTape.isTapeLoaded)
    setClassFlag("tape-btn-unload-right", "lit-amber", !rTape.isTapeLoaded && rTape.mountedCartridge.isDefined)
    setClassFlag("tape-btn-reread",       "lit-red",   rTape.parityError || lTape.parityError)
    setClassFlag("pill-station-right",    "active",    selectedTapeTarget == Device.RightTape)
    setClassFlag("pill-station-left",     "active",    selectedTapeTarget == Device.LeftTape)
    val pillRight = doc.getElementById("pill-station-right")
    if (pillRight != null && !js.isUndefined(pillRight)) {
      pillRight.setAttribute("aria-pressed", (selectedTapeTarget == Device.RightTape).toString)
    }
    val pillLeft = doc.getElementById("pill-station-left")
    if (pillLeft != null && !js.isUndefined(pillLeft)) {
      pillLeft.setAttribute("aria-pressed", (selectedTapeTarget == Device.LeftTape).toString)
    }

    def stationSummary(r: TapeReader): String = r.mountedCartridge match {
      case None => "No Cartridge"
      case Some(c) =>
        val st = if (r.parityError) "PARITY FAULT"
                 else if (r.isLoadedOnHub) s"LOADED • Track ${r.headPosition}/${c.length}"
                 else "UNLOADED (In Cartridge)"
        s"${c.label} ($st)"
    }
    setText("tape-left-status",  stationSummary(lTape))
    setText("tape-right-status", stationSummary(rTape))
    setText("tape-cover-status", if (coverUnlocked) "COVER UNLOCKED" else "COVER PIN LOCKED")

    // 7. Toolbar Status & Run Button
    setText("btn-run-toggle", if (runTimerId.isDefined) "Pause Composition" else "Run Composition")
    setClassFlag("btn-run-toggle", "running", runTimerId.isDefined)
    setClassFlag("speed-1x",  "active", consoleState.realTimePacing && consoleState.pacingSpeed == 1.0)
    setClassFlag("speed-2x",  "active", consoleState.realTimePacing && consoleState.pacingSpeed == 2.0)
    setClassFlag("speed-5x",  "active", consoleState.realTimePacing && consoleState.pacingSpeed == 5.0)
    setClassFlag("speed-max", "active", !consoleState.realTimePacing)
    setText("btn-engine-toggle", s"Engine: ${system.engineMode.shortLabel}")
    setClassFlag("btn-engine-toggle", "active", system.cpu.isIldMode)
    setText("btn-sound-toggle", if (soundEnabled) "Sound: ON" else "Sound: OFF")
    setText("status-banner", consoleState.statusMessage)

    // 8. Selectric Composer Camera-Ready Galley Sheet
    renderGalleySheet(doc)

    // 9. Customer Engineer Diagnostic Drawer (PSAR, AW, BW, MAL, 32 Regs, Disassembly, Core Memory)
    renderCeDiagnostics(doc)
  }

  private def renderGalleySheet(doc: js.Dynamic): Unit = {
    val container = doc.getElementById("galley-lines")
    if (container == null || js.isUndefined(container)) return

    val rulerEl = doc.getElementById("galley-ruler")
    val gLines = system.paper.galleyLines
    val prog = DiagnosticPrograms.AllPrograms(currentProgramIdx)
    val isCompositionProgram =
      prog == DiagnosticPrograms.ContinuousCompositionDemo ||
      prog == DiagnosticPrograms.EndToEndCompositionDemo ||
      prog == DiagnosticPrograms.EscapementAndJustifyDemo

    val r30 = system.cpu.memory.readReg(RegId(30)).unsigned
    val targetMeasure =
      if (isCompositionProgram && r30 >= 30 && r30 <= 940) r30
      else if (system.operatorPanel.measure >= 30 && system.operatorPanel.measure <= 940) system.operatorPanel.measure
      else 140
    system.operatorPanel.measure = targetMeasure

    // 1. Render Typographic Pica Ruler with Right Measure Line
    if (rulerEl != null && !js.isUndefined(rulerEl)) {
      val rsb = new StringBuilder()
      // Pica ticks every 3 picas (36 units) up to 21 picas (252 units)
      val picas = List(0, 3, 6, 9, 12, 15, 18, 21)
      for (p <- picas) {
        val u = p * 12
        rsb.append(s"""<div class="ruler-tick" style="left: calc(var(--unit, 2.4px) * $u);"><span>${p}p</span></div>""")
      }
      // Target Measure Marker & Right Margin Guide
      rsb.append(s"""<div class="ruler-measure-marker" style="left: calc(var(--unit, 2.4px) * $targetMeasure);" title="Target Measure: $targetMeasure units">""")
      rsb.append(s"""<span class="measure-marker-label">▼ ${targetMeasure}u</span>""")
      rsb.append("</div>")
      rulerEl.innerHTML = rsb.toString()
    }

    // 2. Render Camera-Ready Positioned Lines
    val sb = new StringBuilder()
    if (gLines.isEmpty) {
      sb.append("""<div class="galley-placeholder">Camera-ready galley sheet loaded in the IBM Selectric Composer.<br>Click <strong>Run Composition</strong> or press <strong>START</strong> on the console to watch justified copy type out at 14 characters per second.</div>""")
    } else {
      // Continuous vertical guideline down the entire scroll content
      sb.append(s"""<div id="galley-measure-guide" class="galley-measure-guide" style="left: calc(var(--unit, 2.4px) * $targetMeasure + 36px);" title="Target Measure Margin: $targetMeasure units"></div>""")

      val isRunning = runTimerId.isDefined
      for (i <- gLines.indices) {
        val gLine = gLines(i)
        val isLast = i == gLines.length - 1
        val num = f"${i + 1}%02d"
        val justAttr = if (gLine.isJustified) "justified" else "ragged"

        sb.append(s"""<div class="galley-line" data-align="$justAttr">""")
        sb.append(s"""<span class="galley-linenum">$num</span>""")
        sb.append(s"""<div class="galley-text-frame" style="--line-measure: $targetMeasure;">""")

        // Invisible selectable text layer for clean copying & screen-readers
        val plainEsc = escapeHtml(gLine.text)
        sb.append(s"""<span class="galley-copy-layer" aria-hidden="false">$plainEsc</span>""")

        // Exact physical escapement glyph positioning
        for (g <- gLine.glyphs) {
          if (g.ch == ' ') {
            val title = s"Space Band: ${g.widthUnits} units (x: ${g.xUnits}..${g.xUnits + g.widthUnits})"
            sb.append(s"""<span class="galley-space" style="left: calc(var(--unit, 2.4px) * ${g.xUnits}); width: calc(var(--unit, 2.4px) * ${g.widthUnits});" title="$title"></span>""")
          } else {
            val esc = escapeHtml(g.ch.toString)
            val title = s"${g.ch} (${g.widthUnits} units, x: ${g.xUnits})"
            sb.append(s"""<span class="galley-glyph" style="left: calc(var(--unit, 2.4px) * ${g.xUnits}); width: calc(var(--unit, 2.4px) * ${g.widthUnits});" title="$title">$esc</span>""")
          }
        }

        // Live Selectric typing element cursor (red bullet)
        if (isLast && isRunning) {
          val curX = system.paper.currentXUnits
          sb.append(s"""<span class="typeball-cursor" style="left: calc(var(--unit, 2.4px) * $curX);" title="Selectric Typing Element (Escapement: $curX units)">●</span>""")
        }

        sb.append("</div></div>")
      }
    }
    container.innerHTML = sb.toString()

    // 3. Auto-scroll to follow typing element when running
    if (runTimerId.isDefined) {
      val vp = doc.getElementById("galley-scroll-viewport")
      if (vp != null && !js.isUndefined(vp)) {
        vp.scrollTop = vp.scrollHeight
      }
    }

    val metaEl = doc.getElementById("galley-meta")
    if (metaEl != null && !js.isUndefined(metaEl)) {
      val pica = targetMeasure / 12
      val remU = targetMeasure % 12
      metaEl.textContent =
        s"Lines: ${gLines.length} • Printed Chars: ${system.translator.printCount} • Measure: $targetMeasure units (${pica}p ${remU}u)"
    }
  }

  private def renderCeDiagnostics(doc: js.Dynamic): Unit = {
    def setText(id: String, text: String): Unit = {
      val el = doc.getElementById(id)
      if (el != null && !js.isUndefined(el)) el.textContent = text
    }

    val psar = system.memory.psar.unsigned
    val mal  = system.cpu.state.mal.unsigned
    val aw   = system.memory.aWord.unsigned
    val bw   = system.memory.bWord.unsigned
    val out  = system.cpu.state.outputReg

    setText("ce-reg-engine", system.engineMode.shortLabel)
    setText("ce-reg-psar", f"0x$psar%03X ($psar%4d)")
    setText("ce-reg-mal",  f"0x$mal%03X ($mal%4d)")
    setText("ce-reg-aw",   f"0x$aw%04X")
    setText("ce-reg-bw",   f"0x$bw%04X")
    setText("ce-reg-out",  f"0x$out%02X")
    setText("ce-reg-cyc",  f"${system.cpu.state.cycleCount}")

    renderIldTelemetry(doc)

    // 32 Working Registers
    val regsEl = doc.getElementById("ce-registers-grid")
    if (regsEl != null && !js.isUndefined(regsEl)) {
      val sb = new StringBuilder()
      for (i <- 0 until 32) {
        val w = system.memory.readReg(RegId(i)).unsigned
        val activeCls = if (w != 0) "reg-cell non-zero" else "reg-cell"
        val tip = f"Register R$i%02d: 0x$w%04X ($w) | Hi: 0x${w >> 8}%02X, Lo: 0x${w & 0xff}%02X"
        sb.append(f"""<div class="$activeCls" title="$tip"><span class="reg-name">R$i%02d</span><span class="reg-hex">0x$w%04X</span><span class="reg-dec">$w</span></div>""")
      }
      regsEl.innerHTML = sb.toString()
    }

    // Live Disassembly
    val disEl = doc.getElementById("ce-disassembly-list")
    if (disEl != null && !js.isUndefined(disEl)) {
      val startAddr = Address(math.max(0, psar - 8) & ~1)
      val sb = new StringBuilder()
      for (row <- 0 until 10) {
        val addr = startAddr + (row * 2)
        val rawWord = system.memory.readWord(addr)
        val disasm = escapeHtml(Disassembler.disassemble(rawWord, addr))
        val isCur = addr.unsigned == psar
        val cls = if (isCur) "disasm-row current" else "disasm-row"
        val marker = if (isCur) "▶" else " "
        sb.append(f"""<div class="$cls"><span class="disasm-marker">$marker</span><span class="disasm-addr">0x${addr.unsigned}%03X</span><span class="disasm-word">${rawWord.unsigned}%04X</span><span class="disasm-text">$disasm</span></div>""")
      }
      disEl.innerHTML = sb.toString()
    }

    // Core Memory Hex Dump: entire 2,048 bytes (128 rows x 16 bytes = 0x000..0x7FF)
    val memEl = doc.getElementById("ce-memory-dump")
    if (memEl != null && !js.isUndefined(memEl)) {
      val prevScroll = if (!js.isUndefined(memEl.scrollTop)) memEl.scrollTop.asInstanceOf[Double] else 0.0
      val sb = new StringBuilder(16384)
      for (r <- 0 until 128) {
        val rowAddr = r * 16
        val hexSb = new StringBuilder(128)
        val ascSb = new StringBuilder(32)
        for (b <- 0 until 16) {
          val a = rowAddr + b
          val v = system.memory.readByte(Address(a))
          val isPsar = a == psar || a == psar + 1
          val byteCls = if (isPsar) "mem-byte psar-byte" else if (v != 0) "mem-byte nz" else "mem-byte"
          hexSb.append(f"""<span class="$byteCls" title="0x$a%03X ($a): 0x$v%02X ($v)">$v%02X</span>""")
          val ch = if (v >= 32 && v <= 126) escapeHtml(v.toChar.toString) else "·"
          ascSb.append(ch)
        }
        sb.append(f"""<div class="mem-row" id="mem-row-$r"><span class="mem-addr">0x$rowAddr%03X</span><span class="mem-hex">$hexSb</span><span class="mem-ascii">$ascSb</span></div>""")
      }
      memEl.innerHTML = sb.toString()
      memEl.scrollTop = prevScroll
    }

    // Update Full Map Memory Overlay if open
    val overlayDialog = doc.getElementById("mem-overlay-dialog")
    if (overlayDialog != null && !js.isUndefined(overlayDialog)) {
      val isOpen = if (!js.isUndefined(overlayDialog.open)) overlayDialog.open.asInstanceOf[Boolean] else false
      if (isOpen) {
        renderMemoryOverlay(doc, psar, mal)
      }
    }
  }

  private def renderIldTelemetry(doc: js.Dynamic): Unit = {
    def setText(id: String, text: String): Unit = {
      val el = doc.getElementById(id)
      if (el != null && !js.isUndefined(el)) el.textContent = text
    }

    def setClassFlag(id: String, cls: String, active: Boolean): Unit = {
      val el = doc.getElementById(id)
      if (el != null && !js.isUndefined(el)) {
        if (active) el.classList.add(cls) else el.classList.remove(cls)
      }
    }

    val isIld = system.cpu.isIldMode
    setClassFlag("ild-mt-strip",         "behavioral-dim", !isIld)
    setClassFlag("ild-translator-strip", "behavioral-dim", !isIld)
    setClassFlag("ce-ild-inspector",     "behavioral-dim", !isIld)
    setClassFlag("ce-ild-scope-panel",   "behavioral-dim", !isIld)

    val s = system.cpu.ild.signals
    val curInsnTime = if (isIld) s.insnTime else system.cpu.state.insnTimeCtr
    val curBitTime  = if (isIld) s.bitTimeIndex else system.cpu.state.bitTimeCtr
    val strInsnTime = if (isIld) s.steeringInsnTime else ((curInsnTime + 1) % 10)
    val btLbl       = bitTimeLabel(curBitTime)
    val phaseTag    = if (s.pOnly) "P-Only" else "P&Q"

    setText("ce-reg-phase", s"I-$curInsnTime • $btLbl ($phaseTag)")
    setText("ild-str-itime", s"STR → I-$strInsnTime")

    // 1. Instruction Time Strip (I-0..I-9) and Bit Time Strip (B1-4..B13-16)
    for (i <- 0 to 9) {
      setClassFlag(s"ild-it-$i", "active",  curInsnTime == i)
      setClassFlag(s"ild-it-$i", "steered", strInsnTime == i && curInsnTime != i)
    }
    for (b <- 0 to 3) {
      setClassFlag(s"ild-bt-$b", "active", curBitTime == b)
    }

    // 2. Clock, Phase, Opcode Class & Permute Signals (ILD 0303-0304)
    setClassFlag("ild-sig-ponly",  "on", s.pOnly)
    setClassFlag("ild-sig-ptime",  "on", s.pTime)
    setClassFlag("ild-sig-cenbt",  "on", s.cenBt)
    setClassFlag("ild-sig-twr",    "on", s.twr)
    setClassFlag("ild-sig-ath",    "on", s.athOp)
    setClassFlag("ild-sig-imm",    "on", s.immediate)
    setClassFlag("ild-sig-jmp",    "on", s.jmp)
    setClassFlag("ild-sig-prgctl", "on", s.prgCtl)
    setClassFlag("ild-sig-in",     "on", s.input)
    setClassFlag("ild-sig-out",    "on", s.output)
    setClassFlag("ild-sig-prmt",   "on", s.prmt)
    setClassFlag("ild-sig-set9",   "on", s.set9)
    setClassFlag("ild-sig-tkn",    "on", s.tkn)
    setClassFlag("ild-sig-edit",   "on", s.edit)

    // 3. 4-Bit Serial Dataflow, Sense Amps & ALU (ILD 0305, 0308-0310)
    val pbNib  = if (isIld) s.pBufferNibble else (system.cpu.state.pBuffer & 0xf)
    val qbNib  = if (isIld) s.qBufferNibble else (system.cpu.state.qBuffer & 0xf)
    val accNib = s.accNibble
    val carNib =
      (if (s.car1) 1 else 0) |
      (if (s.car2) 2 else 0) |
      (if (s.car3) 4 else 0) |
      (if (s.car4) 8 else 0)
    val inhEvNib =
      (if (s.senseInhLineEv1) 1 else 0) |
      (if (s.senseInhLineEv2) 2 else 0) |
      (if (s.senseInhLineEv3) 4 else 0) |
      (if (s.senseInhLineEv4) 8 else 0)
    val inhOddNib =
      (if (s.senseInhLineOdd1) 1 else 0) |
      (if (s.senseInhLineOdd2) 2 else 0) |
      (if (s.senseInhLineOdd3) 4 else 0) |
      (if (s.senseInhLineOdd4) 8 else 0)

    setText("ild-df-pbfr",      f"${toNibbleBin(pbNib)} (0x$pbNib%X)")
    setText("ild-df-qbfr",      f"${toNibbleBin(qbNib)} (0x$qbNib%X)")
    setText("ild-df-acc",       f"${toNibbleBin(accNib)} (0x$accNib%X)")
    setText("ild-df-car",       f"${toNibbleBin(carNib)} • OVR: ${if (s.carOver) 1 else 0}")
    setText("ild-df-inh-lines", s"EV: ${toNibbleBin(inhEvNib)} • ODD: ${toNibbleBin(inhOddNib)}")

    setClassFlag("ild-sig-add",     "on", s.add)
    setClassFlag("ild-sig-sub",     "on", s.notAdd)
    setClassFlag("ild-sig-carover", "on", s.carOver)
    setClassFlag("ild-sig-acceqz",  "on", s.accEqualZero)
    setClassFlag("ild-sig-inhrd",   "on", s.inhRd)
    setClassFlag("ild-sig-sambt",   "on", s.samBt)
    setClassFlag("ild-sig-sambpos", "on", s.samBPos)
    setClassFlag("ild-sig-wrpbfr",  "on", s.wrPBfr)
    setClassFlag("ild-sig-wracc",   "on", s.wrAcc)

    // 4. 3D 4-Plane Coincident-Current Core Matrix (ILD 0306-0307)
    setClassFlag("ild-sig-selpsar",  "on", s.selProgStepAddr)
    setClassFlag("ild-sig-selawd",   "on", s.selAWd)
    setClassFlag("ild-sig-selbwd",   "on", s.selBWd)
    setClassFlag("ild-sig-selmemup", "on", s.selMemUp)
    setClassFlag("ild-sig-rewo",     "on", !s.btCtrA)
    setClassFlag("ild-sig-rowe",     "on", s.btCtrA)

    val xLine = (s.activeXDriver * 8) + s.activeXSwitch
    val yLine = (s.activeYDriver * 4) + s.activeYSwitch
    setText("ild-core-x", s"X-DRV ${s.activeXDriver + 1} × X-SW ${s.activeXSwitch + 1} (X: $xLine)")
    setText("ild-core-y", s"Y-DRV ${s.activeYDriver + 1} × Y-SW ${s.activeYSwitch + 1} (Y: $yLine)")

    val coreByte = system.cpu.ild.coreMatrix.peekSelectedByte(s)
    val hiLoLbl = if ((s.activeYSwitch & 1) != 0) "Hi" else "Lo"
    val targetDesc = if (s.selMemSpec) {
      val specName = s.activeYDriver match {
        case 7 => s"SPEC: PSAR $hiLoLbl"
        case 6 => s"SPEC: A-Word $hiLoLbl"
        case 5 => s"SPEC: B-Word $hiLoLbl"
        case d => s"SPEC: Y-Drv ${d + 1} $hiLoLbl"
      }
      f"$specName • Byte: 0x$coreByte%02X"
    } else {
      val byteAddr = system.cpu.ild.coreMatrix.decodedByteAddress(
        s.activeXDriver,
        s.activeXSwitch,
        s.activeYDriver,
        s.activeYSwitch
      )
      val regTag = if (byteAddr < 64) f" (R${byteAddr / 2}%02d)" else ""
      f"MEM 0x$byteAddr%03X$regTag • Byte: 0x$coreByte%02X"
    }
    setText("ild-core-target", targetDesc)
    setText(
      "ild-core-planes",
      s"EV: ${toNibbleBin(coreByte & 0xf)} • ODD: ${toNibbleBin((coreByte >> 4) & 0xf)}"
    )

    // 5. CE Test 4..1 Logic Probe LEDs (ILD 0312)
    for (testNum <- 1 to 4) {
      val probe = consoleState.getTestProbe(testNum)
      val level = probe.evaluate(s)
      setClassFlag(s"ce-probe-led-$testNum", "on", level)
      setText(s"ce-probe-led-$testNum", if (level) "1" else "0")
    }

    // 6. Selectric Composer Translator Magnet & Clutch Telemetry Strip (ILD 0311)
    val outByte = if (isIld) s.outputByte else (system.cpu.state.outputReg & 0xff)
    val magActive = s.tsltrPr || (s.tsltrSel && outByte != 0 && outByte != 0xff)
    val magBits = if (s.tsltrPr) s.translatorSelectMagnetsMask else (if (magActive) (outByte & 0x7f) else 0)
    for (m <- 1 to 7) {
      val on = ((magBits >> (m - 1)) & 1) == 1
      setClassFlag(s"ild-mag-$m", "on", on)
      setClassFlag(s"ild-mag-$m", "pill-amber", on)
    }
    setText("ild-tsltr-out-byte", f"OUT: 0x$outByte%02X")

    val isHaltByte = outByte == 0xff
    val isFnByte   = !isHaltByte && ((outByte & 0x04) != 0 || s.fn)
    val isTabCrn   = !isHaltByte && s.tabOrCrn
    val isOrdChar  = !isHaltByte && !isFnByte && !isTabCrn && outByte != 0
    val translatorBusy = system.translator.busy || system.translator.cycleRemaining > 0 || s.busy
    val readyLine = !system.cpu.state.inhibit && !translatorBusy && !system.cpu.state.halted

    setClassFlag("ild-tsltr-ord",    "on", isOrdChar)
    setClassFlag("ild-tsltr-fn",     "on", isFnByte)
    setClassFlag("ild-tsltr-tabcrn", "on", isTabCrn)
    setClassFlag("ild-tsltr-dummy",  "on", s.dummy || s.dmyFn)
    setClassFlag("ild-tsltr-halt",   "on", isHaltByte)
    setClassFlag("ild-tsltr-sel",    "on", s.tsltrSel)
    setClassFlag("ild-tsltr-cyccl",  "on", s.tsltrCycCl || (s.tsltrSel && translatorBusy))
    setClassFlag("ild-tsltr-pr",     "on", s.tsltrPr || (s.tsltrSel && translatorBusy && !s.dummy))
    setClassFlag("ild-tsltr-mpx",    "on", system.cpu.state.multiplex || s.mpx)
    setClassFlag("ild-tsltr-bsy",    "on", translatorBusy)
    setClassFlag("ild-tsltr-rdy",    "on", readyLine)

    // 7. Magnetic Tape Counter, Data Latches, 3-Group Parity & Deck Logic Strip (ILD 0301–0302)
    setClassFlag("ild-mt-ctr-a", "on", s.mtCtrA)
    setClassFlag("ild-mt-ctr-b", "on", s.mtCtrB)
    setClassFlag("ild-mt-ctr-c", "on", s.mtCtrC)
    setClassFlag("ild-mt-ctr-d", "on", s.mtCtrD)
    setClassFlag("ild-mt-ctr-e", "on", s.mtCtrE)

    val ctrBin =
      s"${if (s.mtCtrA) 1 else 0}${if (s.mtCtrB) 1 else 0}${if (s.mtCtrC) 1 else 0}${if (s.mtCtrD) 1 else 0}${if (s.mtCtrE) 1 else 0}"
    val ctrName = s.mtCounterNumber match {
      case 13 => "CT 13 UNLD"
      case 14 => "CT 14 SCH"
      case 15 => "CT 15 LOAD"
      case n if n >= 0 => s"CT $n"
      case _ => "CT ?"
    }
    setText("ild-mt-ctr-dec", s"$ctrName ($ctrBin)")

    val dlBits = Array(
      s.mtData1, s.mtData2, s.mtData3, s.mtData4, s.mtData5,
      s.mtData6, s.mtData7, s.mtData8, s.mtData9
    )
    for (dl <- 1 to 9) {
      val on = dlBits(dl - 1)
      setClassFlag(s"ild-mt-dl-$dl", "on", on)
      setClassFlag(s"ild-mt-dl-$dl", "pill-amber", on && dl == 9)
    }
    val mtByte = s.mtDecodedByte
    setText("ild-mt-byte", f"MT: 0x$mtByte%02X")

    setClassFlag("ild-mt-prty-x",   "on", s.mtXParity)
    setClassFlag("ild-mt-prty-y",   "on", s.mtYParity)
    setClassFlag("ild-mt-prty-z",   "on", s.mtZParity)
    setClassFlag("ild-mt-odd",      "on", s.mtOddParity)
    setClassFlag("ild-mt-char-rdy", "on", s.charRdy)
    setClassFlag("ild-mt-sta",      "on", s.mtStaRight || s.mtStaLeft)
    setText("ild-mt-sta", if (s.mtStaLeft) "STA LF" else "STA RT")
    setClassFlag("ild-mt-incr",     "on", s.mtIncr)
    setClassFlag("ild-mt-rd",       "on", s.mtRd)
    setClassFlag("ild-mt-sch",      "on", s.mtSch || s.trSchSta)
    setClassFlag("ild-mt-setlo",    "on", s.setLo)
    setClassFlag("ild-mt-err",      "on", s.errorLite)

    // 8. 240 kHz Single-Shot (SSA/SSB) Oscilloscope / Timing Diagram (MDM 0601–0602)
    renderIldOscilloscope(doc)
  }

  private final case class ScopeRowSpec(
    label: String,
    pin: String,
    color: String,
    eval: IldTimingSample => Boolean
  )

  private def channelColor(ch: IldScopeChannel): String = ch match {
    case IldScopeChannel.Ssa | IldScopeChannel.Ssb =>
      "#5ce1e6" // Cyan oscillator single-shot trace
    case IldScopeChannel.CsXLog | IldScopeChannel.CsYLog | IldScopeChannel.WrPBfr |
         IldScopeChannel.WrAccum | IldScopeChannel.InhRead | IldScopeChannel.ResBfr =>
      "#ffb443" // Amber core read/write current & buffer trace
    case IldScopeChannel.POnly | IldScopeChannel.SelPsar | IldScopeChannel.SelAWd |
         IldScopeChannel.SelBWd | IldScopeChannel.SelMemSpec | IldScopeChannel.LoadY |
         IldScopeChannel.Rewo | IldScopeChannel.Rowe | IldScopeChannel.CarOver =>
      "#68b5ff" // Sky-blue memory allocation & address decode trace
    case _ =>
      "#3ddc84" // P31 phosphor green clock & strobe trace
  }

  private def renderIldOscilloscope(doc: js.Dynamic): Unit = {
    val vp = doc.getElementById("ild-scope-viewport")
    if (vp == null || !(!js.isUndefined(vp))) return

    val cursorEl = doc.getElementById("ild-scope-cursor-info")
    def setCursorText(txt: String): Unit = {
      if (cursorEl != null && !js.isUndefined(cursorEl)) {
        cursorEl.textContent = txt
      }
    }

    val trace = system.cpu.ild.timingTrace
    val samples = trace.recentSamples(scopeSpan)

    if (samples.isEmpty) {
      vp.innerHTML =
        """<div style="padding: 18px; color: #7ca392; font-family: var(--font-mono); font-size: 11px; text-align: center;">Oscilloscope trace buffer is empty. Click <strong>+1 Bit Time</strong>, <strong>+1 Word Time</strong>, or <strong>Step</strong> to capture 240 kHz SSA/SSB sub-phase waveforms.</div>"""
      setCursorText("Trace empty — click [+1 Bit Time] or [+1 Word Time] to capture 240 kHz SSA/SSB waveforms.")
      return
    }

    val baseChannels: Vector[IldScopeChannel] = scopePreset match {
      case "0602"   => IldScopeChannel.Preset0602MemoryLogic
      case "probes" => IldScopeChannel.PresetClockReference
      case _        => IldScopeChannel.Preset0601BasicClock
    }

    val baseRows: Vector[ScopeRowSpec] = baseChannels.map { ch =>
      ScopeRowSpec(ch.label, ch.pin, channelColor(ch), smp => smp.channel(ch))
    }

    val rows: Vector[ScopeRowSpec] =
      if (scopePreset == "probes") {
        val probeRows = Vector(4, 3, 2, 1).map { tNum =>
          val p = consoleState.getTestProbe(tNum)
          val bitIdx = tNum - 1
          ScopeRowSpec(
            s"TEST $tNum: ${p.label}",
            s"ILD ${p.sheet}",
            "#e08aff",
            smp => ((smp.probeMask >> bitIdx) & 1) != 0
          )
        }
        baseRows ++ probeRows
      } else {
        baseRows
      }

    val n = samples.length
    val sampleW: Int = if (scopeSpan <= 24) 28 else if (scopeSpan <= 96) 10 else 6
    val labelColW = 152
    val headerH = 38
    val rowH = 22
    val footerH = 34
    val totalW = labelColW + n * sampleW + 10
    val totalH = headerH + rows.length * rowH + footerH

    // Compute Bit Time contiguous spans: (startIdx, endIdx)
    val bitSpans = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
    var spanStart = 0
    var i = 1
    while (i < n) {
      val prev = samples(i - 1)
      val cur  = samples(i)
      val isNewBit =
        cur.insnTime != prev.insnTime ||
        cur.bitTimeIndex != prev.bitTimeIndex ||
        (cur.pulseInBitTime == 1 && cur.subPhase == OscSubPhase.SsaOn)
      if (isNewBit) {
        bitSpans += ((spanStart, i - 1))
        spanStart = i
      }
      i += 1
    }
    bitSpans += ((spanStart, n - 1))

    val sb = new StringBuilder(32768)
    sb.append(
      s"""<svg id="ild-scope-svg" class="ild-scope-svg" width="$totalW" height="$totalH" viewBox="0 0 $totalW $totalH" xmlns="http://www.w3.org/2000/svg" style="cursor: crosshair; user-select: none;">"""
    )

    // 1. Background & Left Label Column Backing
    sb.append(s"""<rect x="0" y="0" width="$totalW" height="$totalH" fill="#060d0b"/>""")
    sb.append(s"""<rect x="0" y="0" width="$labelColW" height="$totalH" fill="#091411"/>""")
    sb.append(s"""<rect x="0" y="0" width="$totalW" height="$headerH" fill="#0b1814"/>""")
    sb.append(s"""<line x1="$labelColW" y1="0" x2="$labelColW" y2="$totalH" stroke="#234237" stroke-width="1"/>""")
    sb.append(s"""<line x1="0" y1="$headerH" x2="$totalW" y2="$headerH" stroke="#234237" stroke-width="1"/>""")

    // Header Left Labels
    sb.append(s"""<text x="8" y="13" fill="#88b8a5" font-family="IBM Plex Mono, monospace" font-size="9.5" font-weight="700">SIGNAL / B1 PIN</text>""")
    sb.append(s"""<text x="8" y="31" fill="#638c7c" font-family="IBM Plex Mono, monospace" font-size="8.5">240 kHz PULSE • CORE OP</text>""")

    // 2. Bit-Time Alternating Tints, Header Banners, and Bottom Bus Readouts
    val busY = headerH + rows.length * rowH
    sb.append(s"""<rect x="0" y="$busY" width="$totalW" height="$footerH" fill="#091310"/>""")
    sb.append(s"""<line x1="0" y1="$busY" x2="$totalW" y2="$busY" stroke="#234237" stroke-width="1"/>""")
    sb.append(s"""<text x="8" y="${busY + 14}" fill="#ffb443" font-family="IBM Plex Mono, monospace" font-size="9" font-weight="600">P-BFR / Q-BFR</text>""")
    sb.append(s"""<text x="8" y="${busY + 27}" fill="#3ddc84" font-family="IBM Plex Mono, monospace" font-size="9" font-weight="600">ACCUMULATOR (4b)</text>""")

    for (spanIdx <- bitSpans.indices) {
      val (sIdx, eIdx) = bitSpans(spanIdx)
      val firstSmp = samples(sIdx)
      val lastSmp  = samples(eIdx)
      val x0 = labelColW + sIdx * sampleW
      val w  = (eIdx - sIdx + 1) * sampleW
      val isPOnly = firstSmp.channel(IldScopeChannel.POnly)
      val isWordBoundary = firstSmp.bitTimeIndex == 0 && firstSmp.pulseInBitTime == 1 && firstSmp.subPhase == OscSubPhase.SsaOn

      // Alternating Bit-Time column tint
      if (spanIdx % 2 == 1) {
        sb.append(s"""<rect x="$x0" y="$headerH" width="$w" height="${rows.length * rowH}" fill="rgba(28, 66, 52, 0.14)"/>""")
      }

      // Top Bit-Time Banner Box
      val bannerFill = if (isPOnly) "#10241d" else "#1c2212"
      val bannerTextCol = if (isPOnly) "#7be0b5" else "#ffd275"
      sb.append(s"""<rect x="$x0" y="0" width="$w" height="18" fill="$bannerFill" stroke="#234237" stroke-width="0.6"/>""")
      if (w >= 46) {
        val modeTag = if (isPOnly) "P" else "P&amp;Q"
        val lbl = if (w >= 96) s"I-${firstSmp.insnTime} • ${firstSmp.bitTimeLabel} ($modeTag)" else s"I-${firstSmp.insnTime} ${firstSmp.bitTimeLabel}"
        sb.append(s"""<text x="${x0 + w / 2}" y="12.5" fill="$bannerTextCol" font-family="IBM Plex Mono, monospace" font-size="9.5" font-weight="700" text-anchor="middle">$lbl</text>""")
      }

      // Vertical boundary line
      val lineCol = if (isWordBoundary) "#4ca687" else "#1f3d32"
      val lineW   = if (isWordBoundary) "1.4" else "0.9"
      sb.append(s"""<line x1="$x0" y1="0" x2="$x0" y2="$totalH" stroke="$lineCol" stroke-width="$lineW"/>""")

      // Bottom Nibble Bus Readout Box
      if (w >= 38) {
        val pbHex  = f"0x${lastSmp.pBufferNibble}%X"
        val qbHex  = f"0x${lastSmp.qBufferNibble}%X"
        val accHex = f"0x${lastSmp.accNibble}%X"
        val pqTxt = if (w >= 72) s"PB:$pbHex QB:$qbHex" else s"P:${pbHex.drop(2)} Q:${qbHex.drop(2)}"
        val accTxt = if (w >= 72) s"ACC:$accHex (${toNibbleBin(lastSmp.accNibble)})" else s"ACC:$accHex"
        sb.append(s"""<text x="${x0 + w / 2}" y="${busY + 13}" fill="#ffca7a" font-family="IBM Plex Mono, monospace" font-size="8.8" text-anchor="middle">$pqTxt</text>""")
        sb.append(s"""<text x="${x0 + w / 2}" y="${busY + 26}" fill="#65e8a3" font-family="IBM Plex Mono, monospace" font-size="8.8" text-anchor="middle">$accTxt</text>""")
      }
    }

    // 3. Single-Shot Pulse Grid Lines & Core Op Badges (R-P, R-Q, W-Q, W-P)
    var k = 0
    while (k < n) {
      val smp = samples(k)
      val x = labelColW + k * sampleW

      // Vertical grid line at the start of each single-shot pulse (SsaOn or SsbOn)
      if (smp.subPhase == OscSubPhase.SsaOn || smp.subPhase == OscSubPhase.SsbOn) {
        sb.append(s"""<line x1="$x" y1="18" x2="$x" y2="$busY" stroke="#11241d" stroke-width="0.6"/>""")
        if (sampleW >= 10 && (smp.pulseInBitTime % 2 == 1 || sampleW >= 20)) {
          sb.append(s"""<text x="${x + sampleW}" y="31" fill="#4f7567" font-family="IBM Plex Mono, monospace" font-size="8" text-anchor="middle">${smp.pulseInBitTime}</text>""")
        }
      }

      // Core Read/Write Operation Badge in Header Row 2
      if (smp.coreOp.nonEmpty) {
        val isRead = smp.coreOp.startsWith("R")
        val badgeFill = if (isRead) "rgba(92, 225, 230, 0.22)" else "rgba(255, 180, 67, 0.25)"
        val badgeStroke = if (isRead) "#5ce1e6" else "#ffb443"
        val badgeText = if (isRead) "#8ef2f5" else "#ffd085"
        val bw = math.max(sampleW, 18)
        val bx = x + (sampleW - bw) / 2.0
        sb.append(s"""<rect x="$bx" y="19.5" width="$bw" height="15" rx="2.5" fill="$badgeFill" stroke="$badgeStroke" stroke-width="0.7"/>""")
        sb.append(s"""<text x="${x + sampleW / 2.0}" y="29.5" fill="$badgeText" font-family="IBM Plex Mono, monospace" font-size="8" font-weight="700" text-anchor="middle">${smp.coreOp}</text>""")
      }
      k += 1
    }

    // 4. Channel Rows & Square-Wave Paths
    for (rIdx <- rows.indices) {
      val row = rows(rIdx)
      val yTop = headerH + rIdx * rowH
      val yHigh = yTop + 5.0
      val yLow  = yTop + rowH - 4.0

      // Row divider & baseline
      sb.append(s"""<line x1="0" y1="${yTop + rowH}" x2="$totalW" y2="${yTop + rowH}" stroke="#12241d" stroke-width="0.7"/>""")
      sb.append(s"""<line x1="$labelColW" y1="$yLow" x2="${labelColW + n * sampleW}" y2="$yLow" stroke="#162b23" stroke-width="0.6" stroke-dasharray="2,2"/>""")

      // Left channel label & B1 backplane pin
      val escLbl = escapeHtml(row.label)
      val escPin = escapeHtml(row.pin)
      sb.append(s"""<text x="8" y="${yTop + 14.5}" fill="${row.color}" font-family="IBM Plex Mono, monospace" font-size="9.5" font-weight="600">$escLbl</text>""")
      sb.append(s"""<text x="${labelColW - 6}" y="${yTop + 14.5}" fill="#598273" font-family="IBM Plex Mono, monospace" font-size="8.5" text-anchor="end">$escPin</text>""")

      // Build square wave path and high-level fill rects
      val pathSb = new StringBuilder(512)
      var prevLevel = false
      var sIdx = 0
      while (sIdx < n) {
        val level = row.eval(samples(sIdx))
        val x0 = labelColW + sIdx * sampleW
        val x1 = x0 + sampleW
        val y  = if (level) yHigh else yLow

        if (level) {
          sb.append(s"""<rect x="$x0" y="$yHigh" width="$sampleW" height="${yLow - yHigh}" fill="${row.color}" fill-opacity="0.13"/>""")
        }

        if (sIdx == 0) {
          pathSb.append(s"M $x0 $y L $x1 $y")
        } else {
          if (level != prevLevel) {
            pathSb.append(s" L $x0 $y")
          }
          pathSb.append(s" L $x1 $y")
        }
        prevLevel = level
        sIdx += 1
      }

      sb.append(s"""<path d="$pathSb" fill="none" stroke="${row.color}" stroke-width="1.5" stroke-linejoin="miter"/>""")
    }

    // 5. Interactive Hover Crosshair Line
    sb.append(
      s"""<line id="ild-scope-crosshair" x1="-10" y1="0" x2="-10" y2="$totalH" stroke="#ffffff" stroke-width="1" stroke-dasharray="3,2" opacity="0.0" pointer-events="none"/>"""
    )

    sb.append("</svg>")
    vp.innerHTML = sb.toString()

    // Set default status summary on cursor bar
    val first = samples.head
    val last  = samples.last
    val spanUs = n * 2.0833333
    val previewBadge = if (trace.isPreview) "[INITIAL PREVIEW @ PSAR] " else "[LIVE CAPTURE] "
    val defaultSummary =
      f"$previewBadge$n sub-phases (${spanUs}%.1f µs @ 240 kHz) • I-${first.insnTime} ${first.bitTimeLabel} → I-${last.insnTime} ${last.bitTimeLabel} • Hover waveform to inspect sub-bit timing"
    setCursorText(defaultSummary)

    // Attach mousemove / mouseleave listener to the SVG for interactive sub-phase inspection
    val svgEl = doc.getElementById("ild-scope-svg")
    val crossEl = doc.getElementById("ild-scope-crosshair")
    if (svgEl != null && !js.isUndefined(svgEl)) {
      svgEl.addEventListener("mousemove", (ev: js.Dynamic) => {
        val rect = svgEl.getBoundingClientRect()
        val relX = ev.clientX.asInstanceOf[Double] - rect.left.asInstanceOf[Double]
        val idx = math.floor((relX - labelColW) / sampleW).toInt
        if (idx >= 0 && idx < n) {
          val smp = samples(idx)
          if (crossEl != null && !js.isUndefined(crossEl)) {
            val cx = labelColW + idx * sampleW + sampleW / 2.0
            crossEl.setAttribute("x1", cx.toString)
            crossEl.setAttribute("x2", cx.toString)
            crossEl.setAttribute("opacity", "0.75")
          }
          val timeUs = smp.sampleIndex * 2.0833333
          val phaseName = smp.subPhase match {
            case OscSubPhase.SsaOn  => "SSA ON (2.4µs)"
            case OscSubPhase.SsaOff => "SSA OFF (1.8µs)"
            case OscSubPhase.SsbOn  => "SSB ON (2.4µs)"
            case OscSubPhase.SsbOff => "SSB OFF (1.8µs)"
          }
          val maxPulses = if (smp.channel(IldScopeChannel.POnly)) 8 else 12
          val coreTag = if (smp.coreOp.nonEmpty) s" • Core: [${smp.coreOp}]" else ""
          val modeTag = if (smp.channel(IldScopeChannel.POnly)) "P-Only" else "P&Q"
          setCursorText(
            f"t = $timeUs%.1f µs (#${smp.sampleIndex}) • I-${smp.insnTime} ${smp.bitTimeLabel} ($modeTag) • Pulse ${smp.pulseInBitTime}/$maxPulses [$phaseName]$coreTag • PB:0x${smp.pBufferNibble}%X QB:0x${smp.qBufferNibble}%X ACC:0x${smp.accNibble}%X"
          )
        } else {
          if (crossEl != null && !js.isUndefined(crossEl)) {
            crossEl.setAttribute("opacity", "0.0")
          }
          setCursorText(defaultSummary)
        }
      })

      svgEl.addEventListener("mouseleave", (_: js.Dynamic) => {
        if (crossEl != null && !js.isUndefined(crossEl)) {
          crossEl.setAttribute("opacity", "0.0")
        }
        setCursorText(defaultSummary)
      })
    }
  }

  private def renderMemoryOverlay(doc: js.Dynamic, psar: Int, mal: Int): Unit = {
    def setText(id: String, text: String): Unit = {
      val el = doc.getElementById(id)
      if (el != null && !js.isUndefined(el)) el.textContent = text
    }

    val overlayBody = doc.getElementById("mem-overlay-body")
    if (overlayBody == null || js.isUndefined(overlayBody)) return

    var nzCount = 0

    def renderBlock(title: String, rangeLabel: String, startAddr: Int, numRows: Int): String = {
      val bsb = new StringBuilder(16384)
      bsb.append("""<div class="mo-block">""")
      bsb.append(s"""<div class="mo-block-title"><span>$title</span><span class="mo-range">$rangeLabel</span></div>""")

      // Column header with +00..+1F
      bsb.append("""<div class="mo-header-row"><span class="mo-addr">ADDR</span>""")
      for (col <- 0 until 32) {
        if (col == 16) bsb.append("""<span class="mo-mid-sep"></span>""")
        bsb.append(f"""<span class="mo-header-col">$col%02X</span>""")
      }
      bsb.append("""<span class="mo-ascii">ASCII</span></div>""")

      for (r <- 0 until numRows) {
        val rowAddr = startAddr + r * 32
        val hexSb = new StringBuilder(256)
        val ascSb = new StringBuilder(64)
        for (b <- 0 until 32) {
          if (b == 16) hexSb.append("""<span class="mo-mid-sep"></span>""")
          val a = rowAddr + b
          val v = system.memory.readByte(Address(a))
          if (v != 0) nzCount += 1

          val isPsar = a == psar || a == psar + 1
          val isMal = a == mal && !isPsar
          val isReg = a < 64
          val isLoader = a >= 40 && a <= 47

          var cls = "mo-byte"
          if (isPsar) cls += " psar-byte"
          else if (isMal) cls += " mal-byte"
          else if (isLoader) cls += " loader-byte"
          else if (isReg) cls += " reg-byte"
          if (v != 0) cls += " nz"

          val regInfo = if (a < 64) s" | R${a / 2}%02d (byte ${a % 2})" else ""
          val loaderInfo = if (isLoader) " | Initial Loader" else ""
          val pcInfo = if (isPsar) " | PC (PSAR)" else if (isMal) " | MAL" else ""
          val chPrint = if (v >= 32 && v <= 126) s"'${v.toChar}'" else "·"
          val tip = f"0x$a%03X ($a%4d): 0x$v%02X ($v%3d) $chPrint$regInfo$loaderInfo$pcInfo"

          hexSb.append(f"""<span class="$cls" title="$tip">$v%02X</span>""")
          val ch = if (v >= 32 && v <= 126) escapeHtml(v.toChar.toString) else "·"
          ascSb.append(ch)
        }
        bsb.append(f"""<div class="mo-row"><span class="mo-addr">0x$rowAddr%03X</span>$hexSb<span class="mo-ascii">$ascSb</span></div>""")
      }
      bsb.append("</div>")
      bsb.toString()
    }

    val bodyHtml = if (memoryOverlayLayout == "64x32") {
      renderBlock("Core Storage (2,048 Bytes)", "0x000–0x7FF", 0, 64)
    } else {
      val b0 = renderBlock("Bank 0 (1,024 Bytes)", "0x000–0x3FF", 0, 32)
      val b1 = renderBlock("Bank 1 (1,024 Bytes)", "0x400–0x7FF", 1024, 32)
      b0 + b1
    }

    setText("overlay-psar", f"0x$psar%03X ($psar)")
    setText("overlay-mal",  f"0x$mal%03X ($mal)")
    setText("overlay-nz-count", s"$nzCount")

    val asciiCls = if (showAsciiInOverlay) " show-ascii" else ""
    overlayBody.className = s"mem-overlay-body layout-$memoryOverlayLayout$asciiCls"
    overlayBody.innerHTML = bodyHtml
  }

  private def escapeHtml(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")

  // Synthesized mechanical Selectric & Console Switch acoustics via Web Audio API
  private def ensureAudioCtx(): Option[js.Dynamic] = {
    if (!soundEnabled) return None
    try {
      if (audioCtx.isEmpty) {
        val win = js.Dynamic.global.window
        val Ctx = if (!js.isUndefined(win.AudioContext)) win.AudioContext else win.webkitAudioContext
        if (!js.isUndefined(Ctx)) {
          audioCtx = Some(js.Dynamic.newInstance(Ctx)())
        }
      }
      audioCtx
    } catch {
      case _: Throwable => None
    }
  }

  private def playClickSound(freqHz: Double): Unit = {
    ensureAudioCtx().foreach { ctx =>
      try {
        val now = ctx.currentTime.asInstanceOf[Double]
        val osc = ctx.createOscillator()
        val gain = ctx.createGain()
        osc.`type` = "triangle"
        osc.frequency.setValueAtTime(freqHz, now)
        gain.gain.setValueAtTime(0.06, now)
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.025)
        osc.connect(gain)
        gain.connect(ctx.destination)
        osc.start(now)
        osc.stop(now + 0.025)
      } catch {
        case _: Throwable => ()
      }
    }
  }

  private def playTypeStrikeSound(): Unit = {
    ensureAudioCtx().foreach { ctx =>
      try {
        val now = ctx.currentTime.asInstanceOf[Double]
        val osc = ctx.createOscillator()
        val gain = ctx.createGain()
        osc.`type` = "square"
        osc.frequency.setValueAtTime(880.0 + (scala.util.Random.nextDouble() * 140.0), now)
        osc.frequency.exponentialRampToValueAtTime(160.0, now + 0.018)
        gain.gain.setValueAtTime(0.04, now)
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.018)
        osc.connect(gain)
        gain.connect(ctx.destination)
        osc.start(now)
        osc.stop(now + 0.018)
      } catch {
        case _: Throwable => ()
      }
    }
  }

  private def playCarrierReturnSound(): Unit = {
    ensureAudioCtx().foreach { ctx =>
      try {
        val now = ctx.currentTime.asInstanceOf[Double]
        val osc = ctx.createOscillator()
        val gain = ctx.createGain()
        osc.`type` = "sawtooth"
        osc.frequency.setValueAtTime(320.0, now)
        osc.frequency.linearRampToValueAtTime(110.0, now + 0.075)
        gain.gain.setValueAtTime(0.06, now)
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.075)
        osc.connect(gain)
        gain.connect(ctx.destination)
        osc.start(now)
        osc.stop(now + 0.075)
      } catch {
        case _: Throwable => ()
      }
    }
  }
}
