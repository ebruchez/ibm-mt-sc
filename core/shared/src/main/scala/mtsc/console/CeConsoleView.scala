package mtsc.console

import mtsc.types.*
import mtsc.cpu.Ccu
import mtsc.isa.Decoder
import mtsc.asm.Disassembler
import mtsc.memory.CoreMemory

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
        case _ => new mtsc.periph.ComposerPaper()
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
    val title =
      s"IBM 72 COMPOSER CONTROL UNIT (CCU) MODEL 6320 ─ CE CONSOLE [${state.stepGranularity.label}/${state.editSelector.label}]"
    terminal.drawBox(0, 0, terminal.width, 3, title, Some(AnsiColor.Cyan))

    val psar = cpu.memory.psar
    val aw = cpu.memory.aWord
    val bw = cpu.memory.bWord
    val cycles = cpu.state.cycleCount
    val modeStr = s"MODE: [${state.mode.displayName}]"
    val psarStr = f"PSAR: 0x${psar.unsigned}%03X (${psar.unsigned}%4d)"
    val awStr   = f"AW: 0x${aw.unsigned}%04X"
    val bwStr   = f"BW: 0x${bw.unsigned}%04X"
    val speedStr = if (!state.realTimePacing || state.pacingSpeed <= 0.0) "MAX"
                   else f"${state.pacingSpeed}%.0fx (${(14 * state.pacingSpeed).toInt} cps)"
    val headerLine = f" $modeStr%-18s $psarStr  $awStr  $bwStr  CYC:$cycles%06d  [$speedStr]"
    terminal.writeText(2, 1, headerLine, Some(AnsiColor.BrightWhite), bold = true)
  }

  private def renderDisplayLamps(
    terminal: AnsiTerminal,
    cpu: Ccu,
    state: CeConsoleState
  ): Unit = {
    val boxTitle = s"CE DISPLAY LAMPS ─ [${state.displayMode.rowLabel}: ${state.displayMode.description}]"
    terminal.drawBox(0, 3, terminal.width, 3, boxTitle, Some(AnsiColor.Cyan))

    // Render the 11 multiplexed CE lamps (with nibble dividers between 3|4 and 7|8)
    val ceLamps = state.computeLamps(cpu)
    var x = 2
    for (idx <- ceLamps.indices) {
      val lamp = ceLamps(idx)
      val rawLbl = if (lamp.activeLabel.nonEmpty) lamp.activeLabel else lamp.spec.ceTopLip
      val (fg, bold, inv) = if (lamp.isOn) {
        val c = if (lamp.spec.isRedLens) AnsiColor.BrightRed else AnsiColor.BrightYellow
        (Some(c), true, true)
      } else {
        val c = if (lamp.spec.isRedLens) AnsiColor.Red else AnsiColor.Gray
        (Some(c), false, false)
      }
      terminal.writeText(x, 4, f"[$rawLbl%4s]", fg, bold = bold, inverse = inv)
      x += 6
      if (idx == 2 || idx == 6) {
        terminal.writeText(x, 4, "│", Some(AnsiColor.Cyan), bold = true)
        x += 1
      }
    }

    // Core status badges on the right of the lamp bar (LO, EQ, INH, MPX, RUN, PWR)
    val loOn  = cpu.state.low
    val inhOn = cpu.state.inhibit
    val runOn = !cpu.state.halted
    val pwrOn = state.powerOn
    val summaryBadges = List(
      ("LO",  loOn,  AnsiColor.BrightYellow),
      ("INH", inhOn, AnsiColor.BrightRed),
      ("RUN", runOn, AnsiColor.BrightGreen),
      ("PWR", pwrOn, AnsiColor.BrightGreen)
    )
    var bx = 72
    for ((label, on, activeColor) <- summaryBadges) {
      val (fg, bold, inv) = if (on) (Some(activeColor), true, true) else (Some(AnsiColor.Gray), false, false)
      terminal.writeText(bx, 4, f"[$label%4s]", fg, bold = bold, inverse = inv)
      bx += 7
    }
  }

  private def renderRegisters(
    terminal: AnsiTerminal,
    cpu: Ccu,
    state: CeConsoleState
  ): Unit = {
    val sliderInfo =
      s"32 WORKING REGISTERS (BYTES 0..63) ─ Sliders: [${state.stepGranularity.label}] [${state.editSelector.label}] │ Display:[1-4/0]"
    terminal.drawBox(0, 6, terminal.width, 10, sliderInfo, Some(AnsiColor.Cyan))

    // 4 columns x 8 rows
    val colWidth = 25
    for (row <- 0 until 8) {
      for (col <- 0 until 4) {
        val regIdx = col * 8 + row
        val regId = RegId(regIdx)
        val word = cpu.memory.readReg(regId)
        val text = f"R$regIdx%02d: 0x${word.unsigned}%04X (${word.unsigned}%5d)"
        val x = 2 + col * colWidth
        val y = 7 + row

        val isHighlighted = word.unsigned != 0
        val fg = if (isHighlighted) Some(AnsiColor.BrightWhite) else Some(AnsiColor.Gray)
        terminal.writeText(x, y, text, fg)
      }
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

    val currentPsar = cpu.memory.psar
    // Show 9 instructions around current PSAR (e.g. 4 before, current, 4 after)
    val startAddr = Address(math.max(0, currentPsar.unsigned - 8) & ~1)

    for (row <- 0 until 9) {
      val addr = startAddr + (row * 2)
      val rawWord = cpu.memory.readWord(addr)
      val isCurrent = addr.unsigned == currentPsar.unsigned
      val hasBp = state.breakpoints.contains(addr)

      val disasmStr = Disassembler.disassemble(rawWord, addr)

      val marker = if (isCurrent) ">>>" else if (hasBp) " * " else "   "
      val line = f"$marker 0x${addr.unsigned}%03X: ${rawWord.unsigned}%04X  $disasmStr%-26s"

      val (fg, bold) = if (isCurrent) {
        (Some(AnsiColor.BrightYellow), true)
      } else if (hasBp) {
        (Some(AnsiColor.BrightRed), true)
      } else {
        (Some(AnsiColor.White), false)
      }

      terminal.writeText(2, 17 + row, line.take(boxW - 4), fg, bold = bold)
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
    val title = f"CORE MEMORY (0x$baseAddr%03X - $regionName)"
    terminal.drawBox(boxX, 16, boxW, boxH, title, Some(AnsiColor.Cyan))

    for (row <- 0 until 9) {
      val rowAddr = (baseAddr + row * 16) & 0x7ff
      val sbHex = new StringBuilder()
      val sbAscii = new StringBuilder()

      for (b <- 0 until 16) {
        val byteVal = cpu.memory.readByte(Address(rowAddr + b))
        sbHex.append(f"$byteVal%02X ")
        val ch = if (byteVal >= 32 && byteVal <= 126) byteVal.toChar else '.'
        sbAscii.append(ch)
      }

      val line = f"0x$rowAddr%03X: ${sbHex.toString()} │${sbAscii.toString()}│"
      terminal.writeText(boxX + 1, 17 + row, line.take(boxW - 2), Some(AnsiColor.White))
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

    val lines = paper.lines
    if (lines.isEmpty) {
      terminal.writeText(boxX + 2, 18, "[Galley blank - no text printed yet]", Some(AnsiColor.Gray))
      terminal.writeText(boxX + 2, 20, "Output from TRANSLATOR (OUT / OUTM) will", Some(AnsiColor.Gray))
      terminal.writeText(boxX + 2, 21, "appear here as camera-ready justified copy.", Some(AnsiColor.Gray))
      terminal.writeText(boxX + 2, 23, "Press [v] to toggle back to Core Memory view.", Some(AnsiColor.BrightYellow))
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

    val recentLogs = state.eventLog.takeRight(5)
    for (i <- recentLogs.indices) {
      terminal.writeText(2, boxY + 1 + i, recentLogs(i).take(terminal.width - 4), Some(AnsiColor.BrightWhite))
    }
  }

  private def renderFooter(terminal: AnsiTerminal, state: CeConsoleState): Unit = {
    val boxY = 34
    val boxH = 4
    terminal.drawBox(0, boxY, terminal.width, boxH, "STATUS & COMMANDS", Some(AnsiColor.Cyan))

    val cmds = "[f] Faceplate(Op)  [1-4/0] Display  [s] Step  [r] Run  [g/m] Sliders  [i] InitLdr  [v] Galley/Mem  [q] Quit"
    terminal.writeText(2, boxY + 1, cmds.take(terminal.width - 4), Some(AnsiColor.BrightCyan), bold = true)

    val statusText = s"Status: ${state.statusMessage}"
    terminal.writeText(2, boxY + 2, statusText.take(terminal.width - 4), Some(AnsiColor.BrightYellow))
  }
}
