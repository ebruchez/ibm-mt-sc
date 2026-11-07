package mtsc.console

import mtsc.types.*
import mtsc.cpu.Ccu
import mtsc.asm.Disassembler
import mtsc.periph.ConsoleLampSpec

/**
 * Renderer for the IBM MT/SC Customer Engineer Console.
 *
 * When `consoleState.faceplateInstalled` is true, delegates to `OperatorConsoleView`
 * to render the Operator Control Panel and Tape Reader Console.
 *
 * When `consoleState.faceplateInstalled` is false, renders the exposed CE chassis:
 * - Top buttons: [Step] [4] [3] ─Display─ [2] [1] [Clear] & INITIAL LOADER (40..47)
 * - 11 Multiplexed CE Display Lamps with nibble dividers (Display 1..4 & Clock ID..IA B A)
 * - Bottom 5-position Slide Switches: [Bit|Word|P.S.|Jump|Auto] & [Proc|Edit|Addr|Data|Prog]
 * - 32 Working Registers in Hex & Decimal
 * - Centered live disassembly with current step indicator
 * - Core Memory Hex + ASCII dump or Selectric Composer Galley Proof
 * - Console event log and interactive command shortcuts
 */
object CeConsoleView {

  private val emptyPaper = new mtsc.periph.ComposerPaper()

  def render(
    terminal: AnsiTerminal,
    cpu: Ccu,
    consoleState: CeConsoleState
  ): Unit = {
    if (consoleState.faceplateInstalled) {
      OperatorConsoleView.render(terminal, cpu, consoleState)
      return
    }

    terminal.clear()

    renderHeader(terminal, cpu, consoleState)
    renderDisplayLamps(terminal, cpu, consoleState)
    renderRegisters(terminal, cpu, consoleState)
    renderDisassembly(terminal, cpu, consoleState)

    if (consoleState.viewGalley) {
      val paper = cpu.ioBus match {
        case sys: mtsc.periph.SystemIoBus => sys.translator.paper
        case _ => emptyPaper
      }
      renderGalleyProof(terminal, paper, consoleState)
    } else {
      renderMemoryDumpBox(terminal, cpu, consoleState)
    }

    renderLogBox(terminal, consoleState)
    renderFooter(terminal, consoleState)
  }

  private def renderHeader(
    terminal: AnsiTerminal,
    cpu: Ccu,
    state: CeConsoleState
  ): Unit = {
    val cyan = Some(AnsiColor.Cyan)
    terminal.drawBox(0, 0, terminal.width, 3, "", cyan)
    var tx = terminal.writeText(2, 0, " IBM 72 COMPOSER CONTROL UNIT (CCU) MODEL 6320 ─ CE CONSOLE [", cyan, bold = true)
    tx = terminal.writeText(tx, 0, state.stepGranularity.label, cyan, bold = true)
    tx = terminal.writeChar(tx, 0, '/', cyan, bold = true)
    tx = terminal.writeText(tx, 0, state.editSelector.label, cyan, bold = true)
    tx = terminal.writeChar(tx, 0, '│', cyan, bold = true)
    tx = terminal.writeText(tx, 0, cpu.engineMode.shortLabel, cyan, bold = true)
    terminal.writeText(tx, 0, "] ", cyan, bold = true)

    val psarRaw = cpu.memory.psarRaw
    val awRaw = cpu.memory.aWordRaw
    val bwRaw = cpu.memory.bWordRaw
    val cycles = cpu.state.cycleCount
    val white = Some(AnsiColor.BrightWhite)

    // " MODE: [PROCESS]   " (19 chars starting at x = 2 -> ends at x = 21)
    var hx = terminal.writeText(2, 1, " MODE: [", white, bold = true)
    hx = terminal.writeText(hx, 1, state.mode.displayName, white, bold = true)
    hx = terminal.writeChar(hx, 1, ']', white, bold = true)
    while (hx < 21) {
      hx = terminal.writeChar(hx, 1, ' ', white, bold = true)
    }

    hx = terminal.writeText(hx, 1, " PSAR: 0x", white, bold = true)
    hx = terminal.writeHex3(hx, 1, psarRaw, white, bold = true)
    hx = terminal.writeText(hx, 1, " (", white, bold = true)
    hx = terminal.writeDec(hx, 1, psarRaw, 4, ' ', white, bold = true)
    hx = terminal.writeText(hx, 1, ")  AW: 0x", white, bold = true)
    hx = terminal.writeHex4(hx, 1, awRaw, white, bold = true)
    hx = terminal.writeText(hx, 1, "  BW: 0x", white, bold = true)
    hx = terminal.writeHex4(hx, 1, bwRaw, white, bold = true)
    hx = terminal.writeText(hx, 1, "  CYC:", white, bold = true)
    hx = terminal.writeDecLong(hx, 1, cycles, 6, '0', white, bold = true)
    hx = terminal.writeText(hx, 1, "  [", white, bold = true)
    if (!state.realTimePacing || state.pacingSpeed <= 0.0) {
      hx = terminal.writeText(hx, 1, "MAX", white, bold = true)
    } else {
      hx = terminal.writeDec(hx, 1, state.pacingSpeed.toInt, 0, ' ', white, bold = true)
      hx = terminal.writeText(hx, 1, "x (", white, bold = true)
      hx = terminal.writeDec(hx, 1, (14 * state.pacingSpeed).toInt, 0, ' ', white, bold = true)
      hx = terminal.writeText(hx, 1, " cps)", white, bold = true)
    }
    hx = terminal.writeChar(hx, 1, '│', white, bold = true)
    hx = terminal.writeText(hx, 1, cpu.engineMode.shortLabel, white, bold = true)
    terminal.writeChar(hx, 1, ']', white, bold = true)
  }

  private def renderDisplayLamps(
    terminal: AnsiTerminal,
    cpu: Ccu,
    state: CeConsoleState
  ): Unit = {
    val cyan = Some(AnsiColor.Cyan)
    terminal.drawBox(0, 3, terminal.width, 3, "", cyan)
    var tx = terminal.writeText(2, 3, " CE DISPLAY LAMPS ─ [", cyan, bold = true)
    tx = terminal.writeText(tx, 3, state.displayMode.rowLabel, cyan, bold = true)
    tx = terminal.writeText(tx, 3, ": ", cyan, bold = true)
    tx = terminal.writeText(tx, 3, state.displayMode.description, cyan, bold = true)
    terminal.writeText(tx, 3, "] ", cyan, bold = true)

    // Render the 11 multiplexed CE lamps (with nibble dividers between 3|4 and 7|8)
    val lampsMask = state.computeLampsMask(cpu)
    val allSpecs = ConsoleLampSpec.AllLamps
    val dispMode = state.displayMode
    var x = 2
    var idx = 0
    while (idx < 11) {
      val spec = allSpecs(idx)
      val isOn = ((lampsMask >> idx) & 1) != 0
      val modeLbl = dispMode match {
        case CeDisplayMode.Display1     => spec.ceDisplay1
        case CeDisplayMode.Display2     => spec.ceDisplay2
        case CeDisplayMode.Display3     => spec.ceDisplay3
        case CeDisplayMode.Display4     => spec.ceDisplay4
        case CeDisplayMode.ClockDefault => spec.ceTopLip
      }
      val rawLbl = if (modeLbl.nonEmpty) modeLbl else spec.ceTopLip
      val fg = if (isOn) {
        Some(if (spec.isRedLens) AnsiColor.BrightRed else AnsiColor.BrightYellow)
      } else {
        Some(if (spec.isRedLens) AnsiColor.Red else AnsiColor.Gray)
      }
      terminal.writeChar(x, 4, '[', fg, bold = isOn, inverse = isOn)
      terminal.writePadded(x + 1, 4, rawLbl, 4, leftAlign = false, fg, bold = isOn, inverse = isOn)
      terminal.writeChar(x + 5, 4, ']', fg, bold = isOn, inverse = isOn)
      x += 6
      if (idx == 2 || idx == 6) {
        terminal.writeChar(x, 4, '│', cyan, bold = true)
        x += 1
      }
      idx += 1
    }

    // Core status badges on the right of the lamp bar (LO, INH, RUN, PWR)
    @inline def writeBadge(bx: Int, label: String, on: Boolean, activeColor: AnsiColor): Unit = {
      val fg = if (on) Some(activeColor) else Some(AnsiColor.Gray)
      terminal.writeText(bx, 4, label, fg, bold = on, inverse = on)
    }
    writeBadge(72, "[  LO]", cpu.state.low, AnsiColor.BrightYellow)
    writeBadge(79, "[ INH]", cpu.state.inhibit, AnsiColor.BrightRed)
    writeBadge(86, "[ RUN]", !cpu.state.halted, AnsiColor.BrightGreen)
    writeBadge(93, "[ PWR]", state.powerOn, AnsiColor.BrightGreen)
  }

  private def renderRegisters(
    terminal: AnsiTerminal,
    cpu: Ccu,
    state: CeConsoleState
  ): Unit = {
    val cyan = Some(AnsiColor.Cyan)
    terminal.drawBox(0, 6, terminal.width, 10, "", cyan)
    var tx = terminal.writeText(2, 6, " 32 WORKING REGISTERS (BYTES 0..63) ─ Sliders: [", cyan, bold = true)
    tx = terminal.writeText(tx, 6, state.stepGranularity.label, cyan, bold = true)
    tx = terminal.writeText(tx, 6, "] [", cyan, bold = true)
    tx = terminal.writeText(tx, 6, state.editSelector.label, cyan, bold = true)
    terminal.writeText(tx, 6, "] │ Display:[1-4/0] ", cyan, bold = true)

    // 4 columns x 8 rows
    val colWidth = 25
    val mem = cpu.memory
    var row = 0
    while (row < 8) {
      val y = 7 + row
      var col = 0
      while (col < 4) {
        val regIdx = (col << 3) + row
        val byteAddr = regIdx << 1
        val w = mem.readByteAt(byteAddr) | (mem.readByteAt(byteAddr + 1) << 8)
        val x = 2 + col * colWidth
        val fg = if (w != 0) Some(AnsiColor.BrightWhite) else Some(AnsiColor.Gray)
        terminal.writeChar(x, y, 'R', fg)
        terminal.writeDec(x + 1, y, regIdx, 2, '0', fg)
        terminal.writeText(x + 3, y, ": 0x", fg)
        terminal.writeHex4(x + 7, y, w, fg)
        terminal.writeText(x + 11, y, " (", fg)
        terminal.writeDec(x + 13, y, w, 5, ' ', fg)
        terminal.writeChar(x + 18, y, ')', fg)
        col += 1
      }
      row += 1
    }
  }

  private def renderDisassembly(
    terminal: AnsiTerminal,
    cpu: Ccu,
    state: CeConsoleState
  ): Unit = {
    val boxW = 50
    val boxH = 11
    terminal.drawBox(0, 16, boxW, boxH, "DISASSEMBLY", Some(AnsiColor.Cyan))

    val currentPsar = cpu.memory.psarRaw
    // Show 9 instructions around current PSAR (e.g. 4 before, current, 4 after)
    val startAddrRaw = math.max(0, currentPsar - 8) & ~1
    val hasAnyBp = state.breakpoints.nonEmpty

    var row = 0
    while (row < 9) {
      val addrRaw = (startAddrRaw + (row << 1)) & 0x7ff
      val rawWordInt = cpu.memory.readByteAt(addrRaw) | (cpu.memory.readByteAt((addrRaw + 1) & 0x7ff) << 8)
      val isCurrent = addrRaw == currentPsar
      val hasBp = hasAnyBp && state.breakpoints.contains(Address(addrRaw))

      val marker = if (isCurrent) ">>> " else if (hasBp) " *  " else "    "
      val (fg, bold) = if (isCurrent) {
        (Some(AnsiColor.BrightYellow), true)
      } else if (hasBp) {
        (Some(AnsiColor.BrightRed), true)
      } else {
        (Some(AnsiColor.White), false)
      }

      val y = 17 + row
      var cx = terminal.writeText(2, y, marker, fg, bold = bold)
      cx = terminal.writeText(cx, y, "0x", fg, bold = bold)
      cx = terminal.writeHex3(cx, y, addrRaw, fg, bold = bold)
      cx = terminal.writeText(cx, y, ": ", fg, bold = bold)
      cx = terminal.writeHex4(cx, y, rawWordInt, fg, bold = bold)
      cx = terminal.writeText(cx, y, "  ", fg, bold = bold)
      Disassembler.writeDisassembled(terminal, cx, y, rawWordInt, addrRaw, fg, bold = bold)
      row += 1
    }
  }

  def renderMemoryDumpBox(
    terminal: AnsiTerminal,
    cpu: Ccu,
    state: CeConsoleState
  ): Unit = {
    val boxX = 51
    val boxW = terminal.width - boxX
    val boxH = 11

    val baseAddr = state.memoryInspectionAddress.unsigned & 0x7f0
    val regionName = getMemoryRegionName(baseAddr)
    val cyan = Some(AnsiColor.Cyan)
    terminal.drawBox(boxX, 16, boxW, boxH, "", cyan)
    var tx = terminal.writeText(boxX + 2, 16, " CORE MEMORY (0x", cyan, bold = true)
    tx = terminal.writeHex3(tx, 16, baseAddr, cyan, bold = true)
    tx = terminal.writeText(tx, 16, " - ", cyan, bold = true)
    tx = terminal.writeText(tx, 16, regionName, cyan, bold = true)
    terminal.writeText(tx, 16, ") ", cyan, bold = true)

    val white = Some(AnsiColor.White)
    val mem = cpu.memory
    var row = 0
    while (row < 9) {
      val rowAddr = (baseAddr + (row << 4)) & 0x7ff
      val y = 17 + row
      var hx = terminal.writeText(boxX + 1, y, "0x", white)
      hx = terminal.writeHex3(hx, y, rowAddr, white)
      hx = terminal.writeText(hx, y, ": ", white)

      var ax = hx + 49
      terminal.writeChar(ax - 1, y, '│', white)
      var b = 0
      while (b < 16) {
        val byteVal = mem.readByteAt((rowAddr + b) & 0x7ff)
        hx = terminal.writeHex2(hx, y, byteVal, white)
        hx = terminal.writeChar(hx, y, ' ', white)
        val ch = if (byteVal >= 32 && byteVal <= 126) byteVal.toChar else '.'
        if (ax < boxX + boxW - 1) {
          terminal.writeChar(ax, y, ch, white)
        }
        ax += 1
        b += 1
      }
      if (ax < boxX + boxW - 1) {
        terminal.writeChar(ax, y, '│', white)
      }
      row += 1
    }
  }

  private def renderGalleyProof(
    terminal: AnsiTerminal,
    paper: mtsc.periph.ComposerPaper,
    state: CeConsoleState
  ): Unit = {
    val boxX = 51
    val boxW = terminal.width - boxX
    val boxH = 11

    val title = "SELECTRIC COMPOSER ─ CAMERA-READY GALLEY PROOF"
    terminal.drawBox(boxX, 16, boxW, boxH, title, Some(AnsiColor.BrightGreen))

    val totalLines = paper.lineCount
    if (totalLines == 0) {
      terminal.writeText(boxX + 2, 18, "[Galley blank - no text printed yet]", Some(AnsiColor.Gray))
      terminal.writeText(boxX + 2, 20, "Output from TRANSLATOR (OUT / OUTM) will", Some(AnsiColor.Gray))
      terminal.writeText(boxX + 2, 21, "appear here as camera-ready justified copy.", Some(AnsiColor.Gray))
      terminal.writeText(boxX + 2, 23, "Press [v] to toggle back to Core Memory view.", Some(AnsiColor.BrightYellow))
    } else {
      val startIdx = math.max(0, totalLines - 9)
      val count = totalLines - startIdx
      val completedCount = paper.completedLinesCount
      val white = Some(AnsiColor.BrightWhite)
      val maxTextLen = boxW - 8
      var i = 0
      while (i < count) {
        val lineIdx = startIdx + i
        val num = lineIdx + 1
        val y = 17 + i
        var cx = terminal.writeDec(boxX + 2, y, num, 2, '0', white, bold = true)
        cx = terminal.writeText(cx, y, ": ", white, bold = true)
        if (lineIdx < completedCount) {
          terminal.writeText(cx, y, paper.completedLineAt(lineIdx), white, bold = true, maxLen = maxTextLen)
        } else {
          terminal.writeStringBuilder(cx, y, paper.currentLineBuilder, white, bold = true, maxLen = maxTextLen)
        }
        i += 1
      }
    }
  }

  private def getMemoryRegionName(addr: Int): String = {
    if (addr < 64) "Working Registers"
    else if (addr < 198) "Escapement Table"
    else if (addr < 316) "Output Block"
    else if (addr < 466) "Input Block"
    else if (addr < 2042) "Microprogram Steps"
    else "Special Registers (BW/AW/PSAR)"
  }

  def renderLogBox(terminal: AnsiTerminal, state: CeConsoleState): Unit = {
    val boxH = 7
    val boxY = 27
    terminal.drawBox(0, boxY, terminal.width, boxH, "CONSOLE LOG & PERIPHERAL EVENTS", Some(AnsiColor.Cyan))

    val logs = state.eventLog
    val total = logs.length
    val start = math.max(0, total - 5)
    val count = total - start
    val maxLen = terminal.width - 4
    var i = 0
    while (i < count) {
      terminal.writeText(2, boxY + 1 + i, logs(start + i), Some(AnsiColor.BrightWhite), maxLen = maxLen)
      i += 1
    }
  }

  private def renderFooter(terminal: AnsiTerminal, state: CeConsoleState): Unit = {
    val boxY = 34
    val boxH = 4
    terminal.drawBox(0, boxY, terminal.width, boxH, "STATUS & COMMANDS", Some(AnsiColor.Cyan))

    val cmds = "[f] Faceplate(Op)  [1-4/0] Display  [s] Step  [r] Run  [g/m] Sliders  [i] InitLdr  [v] Galley/Mem  [q] Quit"
    terminal.writeText(2, boxY + 1, cmds, Some(AnsiColor.BrightCyan), bold = true, maxLen = terminal.width - 4)

    val yellow = Some(AnsiColor.BrightYellow)
    val sx = terminal.writeText(2, boxY + 2, "Status: ", yellow)
    terminal.writeText(sx, boxY + 2, state.statusMessage, yellow, maxLen = terminal.width - 4 - 8)
  }
}
