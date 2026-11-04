package mtsc.console

import mtsc.cpu.Ccu
import mtsc.periph.*

/**
 * Renderer for the IBM MT/SC Operator Control Panel and
 * Magnetic Tape Reader Control Panel.
 *
 * Activated when the dark gray Operator Faceplate is installed over the CE chassis
 * (`consoleState.faceplateInstalled == true`).
 */
object OperatorConsoleView {

  def render(
    terminal: AnsiTerminal,
    cpu: Ccu,
    state: CeConsoleState
  ): Unit = {
    terminal.clear()

    val sysBusOpt = cpu.ioBus match {
      case s: SystemIoBus => Some(s)
      case _              => None
    }
    val opPanel = sysBusOpt.map(_.operatorPanel).getOrElse(new OperatorPanelState())

    renderOperatorFaceplate(terminal, cpu, state, opPanel)
    renderTapeConsole(terminal, sysBusOpt, state)

    if (state.viewGalley) {
      val paper = sysBusOpt.map(_.translator.paper).getOrElse(new ComposerPaper())
      renderGalleyProof(terminal, paper)
    } else {
      CeConsoleView.renderMemoryDumpBox(terminal, cpu, state)
    }

    CeConsoleView.renderLogBox(terminal, state)
    renderFooter(terminal, state, opPanel)
  }

  /**
   * Renders the dark gray Operator Control Panel with:
   * - Red 3-position Power Rocker Switch (RESET / ON / OFF)
   * - 11 Indicator Lamps with White Primary top legends and Red Secondary bottom legends
   * - 12-Key Console Keyboard (START, 0..9, R) with sub-labels (TAPE, NO, YES, J, L, C, R, D, LINE STOP)
   */
  private def renderOperatorFaceplate(
    terminal: AnsiTerminal,
    cpu: Ccu,
    state: CeConsoleState,
    opPanel: OperatorPanelState
  ): Unit = {
    val title = s"IBM MAGNETIC TAPE SELECTRIC COMPOSER ─ OPERATOR CONTROL PANEL (FACEPLATE ON) [${cpu.engineMode.shortLabel}]"
    terminal.drawBox(0, 0, terminal.width, 16, title, Some(AnsiColor.White))

    // 1. Left: Red 3-Position Power Rocker Switch (x = 2..12, y = 2..8)
    terminal.writeText(4, 2, "POWER", Some(AnsiColor.BrightWhite), bold = true)
    val rstActive = opPanel.powerSwitch == PowerSwitchPosition.Reset
    val onActive  = opPanel.powerSwitch == PowerSwitchPosition.On && state.powerOn
    val offActive = opPanel.powerSwitch == PowerSwitchPosition.Off || !state.powerOn

    terminal.writeText(2, 4, "RESET", Some(if (rstActive) AnsiColor.BrightYellow else AnsiColor.White))
    terminal.writeText(8, 4, if (rstActive) "[███]" else " ┌─┐ ", Some(AnsiColor.BrightRed), bold = true)
    terminal.writeText(3, 5, "  ON", Some(if (onActive) AnsiColor.BrightGreen else AnsiColor.White))
    terminal.writeText(8, 5, if (onActive)  "[███]" else " │ │ ", Some(AnsiColor.BrightRed), bold = true, inverse = onActive)
    terminal.writeText(2, 6, "  OFF", Some(if (offActive) AnsiColor.BrightRed else AnsiColor.White))
    terminal.writeText(8, 6, if (offActive) "[███]" else " └─┘ ", Some(AnsiColor.Red), bold = true)

    // 2. Middle: 11 Indicator Lamps (x = 15..101)
    // Lamp column X coordinates (8 chars per column for 11 columns = 88 chars: 14..101)
    def lampX(idx0: Int): Int = 14 + idx0 * 8

    // Top White Legends (above Lamps 5..11, indices 4..10)
    val topRow1 = Vector("", "", "", "", "CLEAR", "MERGE", "MODE", "LINE",  "MEASURE", "LINE",   "PARA")
    val topRow2 = Vector("", "", "", "", "",      "",      "",     "COUNT", "",        "INDENT", "INDENT")
    for (i <- 0 until 11) {
      val x = lampX(i)
      if (topRow1(i).nonEmpty) {
        terminal.writeText(x, 2, f"${topRow1(i)}%-7s", Some(AnsiColor.BrightWhite), bold = !opPanel.isSetupLampOn)
      }
      if (topRow2(i).nonEmpty) {
        terminal.writeText(x, 3, f"${topRow2(i)}%-7s", Some(AnsiColor.BrightWhite), bold = !opPanel.isSetupLampOn)
      }
    }

    // 11 Lamp Lenses at y = 4
    val computedLamps = state.computeLamps(cpu)
    for (i <- 0 until 11) {
      val x = lampX(i)
      val lamp = computedLamps(i)
      val isRedLens = lamp.spec.isRedLens
      val (glyph, fg, inv) = if (lamp.isOn) {
        val c = if (isRedLens) AnsiColor.BrightRed else AnsiColor.BrightYellow
        (" (●) ", Some(c), true)
      } else {
        val c = if (isRedLens) AnsiColor.Red else AnsiColor.Gray
        (" (○) ", Some(c), false)
      }
      terminal.writeText(x + 1, 4, glyph, fg, bold = lamp.isOn, inverse = inv)
    }

    // Bottom Legends below Lamps (y = 5, 6, 7):
    // Under Lamps 1..2: RESET / HYPHEN and EOT / TYPE in White
    // Under Lamp 4: SETUP in Red
    // Under Lamps 5..10: LEADER SPACE, MIN IW, MAX IW, MAX QUAD, MIN HYPHEN, DEAD KEY in Red
    terminal.writeText(lampX(0), 5, "RESET",  Some(AnsiColor.BrightWhite))
    terminal.writeText(lampX(0), 7, "HYPHEN", Some(AnsiColor.BrightWhite))
    terminal.writeText(lampX(1), 5, " EOT",   Some(AnsiColor.BrightWhite))
    terminal.writeText(lampX(1), 7, " TYPE",  Some(AnsiColor.BrightWhite))

    val redColor = if (opPanel.isSetupLampOn) AnsiColor.BrightRed else AnsiColor.Red
    terminal.writeText(lampX(3), 5, "SETUP",  Some(redColor), bold = true)

    val redRow1 = Vector((4, "LEADER"), (5, "MIN IW"), (6, "MAX IW"), (7, "MAX"),  (8, "MIN"),    (9, "DEAD"))
    val redRow2 = Vector((4, "SPACE"),  (5, ""),       (6, ""),       (7, "QUAD"), (8, "HYPHEN"), (9, "KEY"))
    for ((idx, text) <- redRow1 if text.nonEmpty) {
      terminal.writeText(lampX(idx), 5, f"$text%-7s", Some(redColor), bold = opPanel.isSetupLampOn)
    }
    for ((idx, text) <- redRow2 if text.nonEmpty) {
      terminal.writeText(lampX(idx), 6, f"$text%-7s", Some(redColor), bold = opPanel.isSetupLampOn)
    }

    // 3. Bottom: 12-Key Console Keyboard (y = 9..11)
    // Keys: START, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, R
    val keys = ConsoleKey.values
    var kx = 14
    for (k <- keys) {
      val isLastPressed = opPanel.lastKeyPressed.contains(k)
      val keyBox = if (k == ConsoleKey.Start) "[START]" else f"[  ${k.topLegend}  ]"
      val keyFg = if (isLastPressed) Some(AnsiColor.BrightYellow) else Some(AnsiColor.BrightWhite)
      terminal.writeText(kx, 9, keyBox, keyFg, bold = true, inverse = isLastPressed)

      if (k.bottomLegend.nonEmpty) {
        if (k == ConsoleKey.KeyR) {
          terminal.writeText(kx + 1, 10, "LINE", Some(AnsiColor.White))
          terminal.writeText(kx + 1, 11, "STOP", Some(AnsiColor.White))
        } else {
          terminal.writeText(kx + 2, 10, k.bottomLegend, Some(AnsiColor.White), bold = true)
        }
      }
      kx += (if (k == ConsoleKey.Start) 8 else 7)
    }

    // 4. Operator Setup Summary Bar (y = 13..14)
    terminal.drawHLine(1, 12, terminal.width - 2, Some(AnsiColor.Gray))
    val summary1 =
      f" MODE: ${opPanel.mode.codeLetter} (${opPanel.mode.displayName}%-10s) │ MEASURE: ${opPanel.measure}%3du │ LINE CNT: ${opPanel.lineCount}%3d │ INDENT L:${opPanel.lineIndent}%2du P:${opPanel.paraIndent}%2du │ MERGE: ${if (opPanel.mergeDecision) "YES" else "NO "}"
    val promptStr = opPanel.activeSetupParam match {
      case Some(p) =>
        val buf = if (opPanel.entryBuffer.isEmpty) "_" else opPanel.entryBuffer
        s"ACTIVE PROMPT: [${p.label}] -> Input: $buf (${p.description})"
      case None =>
        s"MIN/MAX IW: ${opPanel.minInterword}u/${opPanel.maxInterword}u │ MAX QUAD: ${opPanel.maxQuad}u │ LEADER: ${opPanel.leaderSpace}u │ LINE STOP: ${if (opPanel.lineStopLatch) "SET" else "OFF"}"
    }
    terminal.writeText(2, 13, summary1.take(terminal.width - 4), Some(AnsiColor.BrightCyan), bold = true)
    terminal.writeText(2, 14, s" $promptStr".take(terminal.width - 4), Some(AnsiColor.BrightYellow))
  }

  /**
   * Renders the 8-button Magnetic Tape Reader Control Panel
   * (REREAD, SKIP, SEARCH, COVER UNLOCK / UNLOAD LEFT, LOAD LEFT, UNLOAD RIGHT, LOAD RIGHT)
   * along with live Left & Right tape station status.
   */
  private def renderTapeConsole(
    terminal: AnsiTerminal,
    sysBusOpt: Option[SystemIoBus],
    state: CeConsoleState
  ): Unit = {
    val boxW = 50
    val boxH = 11
    terminal.drawBox(0, 16, boxW, boxH, "MAGNETIC TAPE CONSOLE", Some(AnsiColor.White))

    val rightOpt = sysBusOpt.map(_.rightTape)
    val leftOpt  = sysBusOpt.map(_.leftTape)

    val coverUnlocked = rightOpt.forall(_.coverUnlocked) && leftOpt.forall(_.coverUnlocked)
    val leftLoaded    = leftOpt.exists(_.isTapeLoaded)
    val rightLoaded   = rightOpt.exists(_.isTapeLoaded)
    val searching     = rightOpt.exists(_.searching) || leftOpt.exists(_.searching)

    // Top row of 4 square white pushbuttons (y = 18..19)
    val topButtons = Vector(
      ("REREAD", "",       false),
      (" SKIP ", "",       false),
      ("SEARCH", "",       searching),
      ("COVER ", "UNLOCK", coverUnlocked)
    )
    for (i <- topButtons.indices) {
      val (l1, l2, lit) = topButtons(i)
      val bx = 2 + i * 12
      val fg = if (lit) Some(AnsiColor.BrightYellow) else Some(AnsiColor.BrightWhite)
      terminal.writeText(bx, 18, f"[$l1%-6s]", fg, bold = true, inverse = lit)
      if (l2.nonEmpty) {
        terminal.writeText(bx, 19, f"[$l2%-6s]", fg, bold = true, inverse = lit)
      }
    }

    // Bottom row of 4 square white pushbuttons (y = 21..22)
    val botButtons = Vector(
      ("UNLOAD", " LEFT ", !leftLoaded && leftOpt.exists(_.mountedCartridge.isDefined)),
      (" LOAD ", " LEFT ", leftLoaded),
      ("UNLOAD", "RIGHT ", !rightLoaded && rightOpt.exists(_.mountedCartridge.isDefined)),
      (" LOAD ", "RIGHT ", rightLoaded)
    )
    for (i <- botButtons.indices) {
      val (l1, l2, lit) = botButtons(i)
      val bx = 2 + i * 12
      val fg = if (lit) Some(AnsiColor.BrightGreen) else Some(AnsiColor.BrightWhite)
      terminal.writeText(bx, 21, f"[$l1%-6s]", fg, bold = true, inverse = lit)
      terminal.writeText(bx, 22, f"[$l2%-6s]", fg, bold = true, inverse = lit)
    }

    // Tape station telemetry (y = 24..25)
    def formatStation(name: String, readerOpt: Option[TapeReader]): String = readerOpt match {
      case None => s"$name: Offline"
      case Some(r) =>
        r.mountedCartridge match {
          case None => s"$name: [Empty]"
          case Some(c) =>
            val stateStr = if (r.parityError) "PARITY ERR"
                           else if (r.isLoadedOnHub) f"LOADED trk ${r.headPosition}%3d/${c.length}"
                           else "UNLOADED (In Cart)"
            s"$name: ${c.label.take(12)} ($stateStr)"
        }
    }

    val coverPinStr = if (coverUnlocked) "UNLOCKED" else "LOCKED PIN"
    terminal.writeText(2, 24, formatStation("LEFT ", leftOpt).take(boxW - 4), Some(AnsiColor.Cyan))
    terminal.writeText(2, 25, s"${formatStation("RIGHT", rightOpt)} [$coverPinStr]".take(boxW - 4), Some(AnsiColor.BrightCyan))
  }

  private def renderGalleyProof(
    terminal: AnsiTerminal,
    paper: ComposerPaper
  ): Unit = {
    val boxX = 51
    val boxW = terminal.width - boxX
    val boxH = 11

    val title = "SELECTRIC COMPOSER ─ CAMERA-READY GALLEY PROOF"
    terminal.drawBox(boxX, 16, boxW, boxH, title, Some(AnsiColor.BrightGreen))

    val lines = paper.lines
    if (lines.isEmpty) {
      terminal.writeText(boxX + 2, 18, "[Galley blank - no text printed yet]", Some(AnsiColor.Gray))
      terminal.writeText(boxX + 2, 20, "Press [r] to run automatic typesetting or", Some(AnsiColor.Gray))
      terminal.writeText(boxX + 2, 21, "press [p] to step through Operator Setup.", Some(AnsiColor.Gray))
      terminal.writeText(boxX + 2, 23, "Press [f] to remove faceplate (CE Console).", Some(AnsiColor.BrightYellow))
    } else {
      val recentLines = lines.takeRight(9)
      val startIdx = lines.length - recentLines.length
      for (i <- recentLines.indices) {
        val line = recentLines(i)
        val num = startIdx + i + 1
        val text = f"$num%02d: $line"
        terminal.writeText(boxX + 2, 17 + i, text.take(boxW - 4), Some(AnsiColor.BrightWhite), bold = true)
      }
    }
  }

  private def renderFooter(
    terminal: AnsiTerminal,
    state: CeConsoleState,
    opPanel: OperatorPanelState
  ): Unit = {
    val boxY = 34
    val boxH = 4
    terminal.drawBox(0, boxY, terminal.width, boxH, "STATUS & COMMANDS (OPERATOR MODE)", Some(AnsiColor.White))

    val cmds = "[f] Remove Faceplate(CE)  [p] Setup Param  [0-9/Enter] Keypad/START  [u] Tape Panel  [r] Run  [q] Quit"
    terminal.writeText(2, boxY + 1, cmds.take(terminal.width - 4), Some(AnsiColor.BrightCyan), bold = true)

    val statusText = s"Status: ${state.statusMessage}"
    terminal.writeText(2, boxY + 2, statusText.take(terminal.width - 4), Some(AnsiColor.BrightYellow))
  }
}
