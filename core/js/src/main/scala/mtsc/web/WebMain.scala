package mtsc.web

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import mtsc.types.*
import mtsc.cpu.StepResult
import mtsc.asm.Disassembler
import mtsc.diag.{DiagnosticPrograms, EscapementRoutine}
import mtsc.periph.*
import mtsc.console.*

/**
 * Scala.js Browser Frontend Controller for the IBM MT/SC (Model 6310/6320) Emulator.
 *
 * Drives:
 * 1. The Dual-Layer Console Unit (Picture 1 Operator Faceplate overlay <-> Picture 3 Exposed CE Chassis)
 * 2. The 8-Button Magnetic Tape Transport Console (Picture 2)
 * 3. The True-Proportional Selectric Composer Galley Proof Sheet (14 cps real-time pacing)
 * 4. The Customer Engineer Diagnostic Drawer (32 Working Registers, Disassembly, Core Memory, Event Log)
 */
object WebMain {

  private val system = new MtscSystem()
  private val consoleState = new CeConsoleState()
  private var currentProgramIdx: Int = 0
  private var runTimerId: Option[Int] = None
  private var soundEnabled: Boolean = true
  private var audioCtx: Option[js.Dynamic] = None
  private var memoryOverlayLayout: String = "2x1k"
  private var showAsciiInOverlay: Boolean = false

  def main(args: Array[String]): Unit = {
    if (js.typeOf(js.Dynamic.global.document) == "undefined") return
    val doc = js.Dynamic.global.document
    val root = doc.getElementById("mtsc-app")
    if (root == null || js.isUndefined(root)) return

    // Start in Operator Faceplate mode (Picture 1) by default so the user can unlatch it to reveal Picture 3
    consoleState.faceplateInstalled = true
    consoleState.viewGalley = true

    loadProgram(0)
    bindEvents(doc)
    renderAll(doc)
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
      if (!system.rightTape.isTapeLoaded) {
        system.loadTape(
          Device.RightTape,
          TapeCartridge.fromText(TapeCartridge.HistoricalSampleText, "Historical MT Tape")
        )
      }
    } else if (prog == DiagnosticPrograms.EndToEndCompositionDemo || prog == DiagnosticPrograms.TapeBootstrapLoader) {
      system.operatorPanel.measure = 100
      system.memory.writeReg(RegId(30), Word(100))
      system.memory.writeReg(RegId(28), Word(70))
      val sample = "The electronic logic used in the CCU is in the SLD family."
      system.loadTape(Device.RightTape, TapeCartridge.fromText(sample, "MTSC Test Tape"))
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

    // Faceplate toggle (Picture 1 <-> Picture 3)
    onClick("btn-toggle-faceplate", () => {
      consoleState.toggleFaceplate()
      playClickSound(220)
    })

    // Power Rocker Switch (RESET / ON / OFF)
    onClick("pwr-reset", () => {
      stopContinuousRun()
      system.operatorPanel.powerSwitch = PowerSwitchPosition.Reset
      system.reset()
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

    // CE Top Pushbuttons (Picture 3: Step, Display 4, 3, 2, 1, Clear, ClockDefault)
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
      if (consoleState.editSelector == CeEditSelector.Data) {
        system.memory.writeWord(system.memory.psar, Word.Zero)
        consoleState.log(f"CE Clear: cleared word at 0x${system.memory.psar.unsigned}%03X.")
        consoleState.statusMessage = f"Cleared word at 0x${system.memory.psar.unsigned}%03X."
      } else if (consoleState.editSelector == CeEditSelector.Addr) {
        system.memory.psar = Address(0)
        system.cpu.state.mal = Address(0)
        consoleState.log("CE Clear: cleared PSAR & MAL to 0x000.")
        consoleState.statusMessage = "Cleared PSAR to 0x000."
      } else {
        system.cpu.state.inhibit = false
        system.cpu.state.low = false
        system.cpu.state.equal = false
        system.rightTape.parityError = false
        system.leftTape.parityError = false
        consoleState.log("CE Clear: reset condition and error indicators (RES INDS).")
        consoleState.statusMessage = "Indicators cleared."
      }
      playClickSound(440)
    })
    onClick("ce-btn-init-loader", () => {
      stopContinuousRun()
      InitialLoaderTable.loadIntoMemory(system.memory, paperTape = false, leftStation = false)
      system.cpu.state.mal = Address(42)
      consoleState.memoryInspectionAddress = Address(32)
      consoleState.log("Keyed 8-byte INITIAL LOADER into Core Memory (bytes 40..47, PSAR=42).")
      consoleState.statusMessage = "INITIAL LOADER (40..47) loaded into Core Memory (PSAR = 42 / 0x02A)."
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
        consoleState.log(s"Left CE slide switch set to [${g.label}]")
        consoleState.statusMessage = s"Step granularity switch set to ${g.label}."
        playClickSound(340)
      })
    }
    for (e <- CeEditSelector.values) {
      onClick(s"slider-right-${e.ordinal}", () => {
        consoleState.editSelector = e
        consoleState.mode = e match {
          case CeEditSelector.Proc => CeConsoleMode.Process
          case CeEditSelector.Addr => CeConsoleMode.EditAddress
          case CeEditSelector.Data => CeConsoleMode.EditData
          case _                   => CeConsoleMode.SingleStep
        }
        consoleState.log(s"Right CE slide switch set to [${e.label}]")
        consoleState.statusMessage = s"Edit Memory Selector switch set to ${e.label}."
        playClickSound(340)
      })
    }

    // Picture 2: 8 Magnetic Tape Transport Console Pushbuttons
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
    onClick("btn-inject-parity", () => {
      if (system.rightTape.injectParityError()) {
        consoleState.log(f"Injected parity fault on Right Tape track ${system.rightTape.headPosition}.")
        consoleState.statusMessage = f"Parity fault injected on track ${system.rightTape.headPosition}. Next read will stall with RESET/ERR lamp."
      } else {
        consoleState.statusMessage = "Cannot inject parity error: mount and load Right Tape first."
      }
    })
    onClick("btn-clear-galley", () => {
      system.paper.clear()
      val vp = doc.getElementById("galley-scroll-viewport")
      if (vp != null && !js.isUndefined(vp)) {
        vp.scrollTop = 0
      }
      consoleState.statusMessage = "Fresh camera-ready galley sheet loaded into Selectric Composer."
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
          system.loadTape(Device.RightTape, TapeCartridge.fromText(text, "Custom Copy Tape"))
          loadProgram(0) // Load Continuous Composition program
          system.loadTape(Device.RightTape, TapeCartridge.fromText(text, "Custom Copy Tape"))
          consoleState.log(s"Mounted Custom Copy Tape (${text.length} chars) on Right Station.")
          consoleState.statusMessage = s"Mounted Custom Copy Tape (${text.length} chars). Press Run or 0/TAPE to compose!"
        }
      }
    })

    // Core Storage "Jump to PSAR" button
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
          case "r" | "R" if consoleState.faceplateInstalled && system.operatorPanel.activeSetupParam.isDefined =>
            handleConsoleKeyPress(ConsoleKey.KeyR, doc)
            renderAll(doc)
          case d if d.length == 1 && d.charAt(0).isDigit =>
            val digit = d.charAt(0) - '0'
            if (consoleState.faceplateInstalled) {
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
    // If CE Edit Address or Edit Data is active on the right slide switch, allow direct nibble entry
    if (!consoleState.faceplateInstalled && consoleState.editSelector == CeEditSelector.Addr) {
      key.topLegend.toIntOption.foreach { d =>
        val nextAddr = ((system.memory.psar.unsigned << 4) | (d & 0xf)) & 0x7fe
        system.memory.psar = Address(nextAddr)
        system.cpu.state.mal = Address(nextAddr)
        consoleState.statusMessage = f"CE Edit Address: PSAR set to 0x$nextAddr%03X ($nextAddr)."
      }
      return
    }
    if (!consoleState.faceplateInstalled && consoleState.editSelector == CeEditSelector.Data) {
      key.topLegend.toIntOption.foreach { d =>
        val addr = system.memory.psar
        val cur = system.memory.readWord(addr).unsigned
        val updated = ((cur << 4) | (d & 0xf)) & 0xffff
        system.memory.writeWord(addr, Word(updated))
        system.cpu.state.pBuffer = d & 0xf
        consoleState.statusMessage = f"CE Edit Data at 0x${addr.unsigned}%03X: word = 0x$updated%04X."
      }
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
        loadProgram(currentProgramIdx)
      }
      startContinuousRun(doc)
    }
  }

  private def stepOnce(doc: js.Dynamic): StepResult = {
    if (!consoleState.powerOn) {
      consoleState.statusMessage = "System Power is OFF. Switch Power to ON."
      return StepResult.Halted
    }

    // Advance Bit / I-Time counters for CE ClockDefault display
    system.cpu.state.bitTimeCtr = (system.cpu.state.bitTimeCtr + 1) & 0x3
    if (system.cpu.state.bitTimeCtr == 0) {
      system.cpu.state.insnTimeCtr = (system.cpu.state.insnTimeCtr + 1) % 10
    }

    val prevPrintCount = system.translator.printCount
    val prevLines = system.paper.lines.length
    val prevAddr = system.memory.psar
    val res = system.step()

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
        consoleState.statusMessage = f"Stepped 0x${prevAddr.unsigned}%03X -> 0x${system.memory.psar.unsigned}%03X."
    }
    res
  }

  private def startContinuousRun(doc: js.Dynamic): Unit = {
    if (runTimerId.isDefined) return
    if (!consoleState.powerOn) {
      consoleState.statusMessage = "Cannot run: Power Switch is OFF."
      return
    }
    if (system.cpu.state.halted) {
      loadProgram(currentProgramIdx)
    }

    if (!consoleState.realTimePacing) {
      // Instant execution
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
      f"Composing at ${consoleState.pacingSpeed}%.0fx (${(14 * consoleState.pacingSpeed).toInt} cps)..."

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

        // Update simulated clock counters for CE lamps
        system.cpu.state.insnTimeCtr = (stepsThisTick % 6)
        system.cpu.state.bitTimeCtr = (stepsThisTick & 3)

        if (system.translator.printCount > prevPrints) {
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

    // 1. Faceplate state (Picture 1 vs Picture 3)
    setClassFlag("console-unit", "faceplate-removed", !consoleState.faceplateInstalled)
    setText(
      "btn-toggle-faceplate",
      if (consoleState.faceplateInstalled) "Unlatch Operator Faceplate (Reveal Picture 3 CE Console)"
      else "Install Operator Faceplate (Show Picture 1 Operator Console)"
    )
    setText(
      "console-mode-badge",
      if (consoleState.faceplateInstalled) "OPERATOR MODE (FACEPLATE INSTALLED — PICTURE 1)"
      else s"CUSTOMER ENGINEER MODE (EXPOSED CHASSIS — PICTURE 3 • ${consoleState.displayMode.rowLabel.toUpperCase})"
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

    // Highlight active CE Display row in Picture 3
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

    // 6. Picture 2: 8-Button Magnetic Tape Transport Console
    val rTape = system.rightTape
    val lTape = system.leftTape
    val coverUnlocked = rTape.coverUnlocked && lTape.coverUnlocked

    setClassFlag("tape-btn-cover-unlock", "lit-amber", coverUnlocked)
    setClassFlag("tape-btn-load-left",    "lit-green", lTape.isTapeLoaded)
    setClassFlag("tape-btn-unload-left",  "lit-amber", !lTape.isTapeLoaded && lTape.mountedCartridge.isDefined)
    setClassFlag("tape-btn-load-right",   "lit-green", rTape.isTapeLoaded)
    setClassFlag("tape-btn-unload-right", "lit-amber", !rTape.isTapeLoaded && rTape.mountedCartridge.isDefined)
    setClassFlag("tape-btn-reread",       "lit-red",   rTape.parityError || lTape.parityError)

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

    setText("ce-reg-psar", f"0x$psar%03X ($psar%4d)")
    setText("ce-reg-mal",  f"0x$mal%03X ($mal%4d)")
    setText("ce-reg-aw",   f"0x$aw%04X")
    setText("ce-reg-bw",   f"0x$bw%04X")
    setText("ce-reg-out",  f"0x$out%02X")
    setText("ce-reg-cyc",  f"${system.cpu.state.cycleCount}")

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
