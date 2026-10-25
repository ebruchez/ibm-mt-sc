package mtsc.console

import mtsc.types.*
import mtsc.cpu.{Ccu, MockIoBus}
import mtsc.memory.CoreMemory
import mtsc.diag.DiagnosticPrograms

class CeConsoleSuite extends munit.FunSuite {

  test("AnsiTerminal draws boxes and text correctly") {
    val term = new AnsiTerminal(width = 40, height = 10)
    term.drawBox(0, 0, 40, 10, "TEST BOX")
    val rendered = term.renderToString()
    assert(rendered.contains("TEST BOX"))
    assert(rendered.contains("┌"))
    assert(rendered.contains("┐"))
    assert(rendered.contains("└"))
    assert(rendered.contains("┘"))
  }

  test("CeConsoleView renders full CE console layout without errors") {
    val img = DiagnosticPrograms.AluDiagnostic.assemble()
    val mem = CoreMemory.fromImage(img)
    val io = new MockIoBus()
    val cpu = new Ccu(mem, io)
    val state = new CeConsoleState()

    val term = new AnsiTerminal(width = 104, height = 38)
    CeConsoleView.render(term, cpu, state)
    val rendered = term.renderToString()

    // Verify key console elements are present in output
    assert(rendered.contains("IBM 72 COMPOSER CONTROL UNIT"))
    assert(rendered.contains("CE DISPLAY LAMPS"))
    assert(rendered.contains("32 WORKING REGISTERS"))
    assert(rendered.contains("DISASSEMBLY"))
    assert(rendered.contains("CORE MEMORY"))
    assert(rendered.contains("CONSOLE LOG & PERIPHERAL EVENTS"))
    assert(rendered.contains("STATUS & COMMANDS"))
    assert(rendered.contains("PSAR: 0x222"))
    assert(rendered.contains("MODE: [PROCESS]"))
  }

  test("CeConsoleView lamps reflect latches accurately") {
    val mem = CoreMemory.empty()
    val io = new MockIoBus()
    val cpu = new Ccu(mem, io)
    val state = new CeConsoleState()

    // Set Low and Inhibit latches
    cpu.state.low = true
    cpu.state.inhibit = true
    cpu.state.halted = false

    val term = new AnsiTerminal(width = 104, height = 38)
    CeConsoleView.render(term, cpu, state)
    val rendered = term.renderToString()

    assert(rendered.contains("[  LO]"), "LO lamp must be rendered")
    assert(rendered.contains("[ INH]"), "INH lamp must be rendered")
    assert(rendered.contains("[ RUN]"), "RUN lamp must be rendered")
    assert(rendered.contains("[ PWR]"), "PWR lamp must be rendered")
  }

  test("CeConsoleState rotary switch cycles modes") {
    val state = new CeConsoleState()
    assertEquals(state.mode, CeConsoleMode.Process)

    assertEquals(state.cycleMode(), CeConsoleMode.SingleStep)
    assertEquals(state.cycleMode(), CeConsoleMode.EditAddress)
    assertEquals(state.cycleMode(), CeConsoleMode.EditData)
    assertEquals(state.cycleMode(), CeConsoleMode.Process)
  }

  test("CeConsoleState manages breakpoints") {
    val state = new CeConsoleState()
    val addr = Address(550)
    assert(!state.breakpoints.contains(addr))

    assert(state.toggleBreakpoint(addr), "Breakpoint should be added")
    assert(state.breakpoints.contains(addr))

    assert(!state.toggleBreakpoint(addr), "Breakpoint should be removed")
    assert(!state.breakpoints.contains(addr))
  }

  test("ConsoleLampSpec accurately maps all 11 lamps across Operator and CE views") {
    import mtsc.periph.ConsoleLampSpec

    val lamps = ConsoleLampSpec.AllLamps
    assertEquals(lamps.length, 11)

    // Lamp 1: Reset -> only Y 11 in Display 1 (no Display 2..4 signals)
    val lamp1 = lamps(0)
    assertEquals(lamp1.ceTopLip, "Reset")
    assertEquals(lamp1.ceDisplay4, "")
    assertEquals(lamp1.ceDisplay3, "")
    assertEquals(lamp1.ceDisplay2, "")
    assertEquals(lamp1.ceDisplay1, "Y 11")

    // Lamp 2: Test 4 -> Rdy, Inh, EOT, Y 10
    val lamp2 = lamps(1)
    assertEquals(lamp2.ceTopLip, "Test 4")
    assertEquals(lamp2.ceDisplay4, "Rdy")
    assertEquals(lamp2.ceDisplay3, "Inh")
    assertEquals(lamp2.ceDisplay2, "EOT")
    assertEquals(lamp2.ceDisplay1, "Y 10")

    // Lamp 4: Red SETUP lens -> Test 2, QB 4, Eq, O 8, Y 8
    val lamp4 = lamps(3)
    assert(lamp4.isRedLens)
    assertEquals(lamp4.operatorBottom, "SETUP")
    assertEquals(lamp4.ceTopLip, "Test 2")
    assertEquals(lamp4.ceDisplay4, "QB 4")
    assertEquals(lamp4.ceDisplay3, "Eq")
    assertEquals(lamp4.ceDisplay2, "O 8")
    assertEquals(lamp4.ceDisplay1, "Y 8")
  }

  test("OperatorPanelState handles setup decisions, red SETUP mode, and Line Stop latch") {
    import mtsc.periph.{OperatorPanelState, SetupParam, ConsoleKey, CompositionMode}

    val panel = new OperatorPanelState()
    assert(!panel.isSetupLampOn)

    // Select MODE (Lamp 7) and key '6' (Center) then START
    panel.selectSetupParam(SetupParam.Mode)
    assert(!panel.isSetupLampOn)
    panel.pressKey(ConsoleKey.Key6)
    panel.pressKey(ConsoleKey.Start)
    assertEquals(panel.mode, CompositionMode.Center)

    // Automatically advanced to LINE COUNT (Lamp 8) -> key 4, 5, START
    assertEquals(panel.activeSetupParam, Some(SetupParam.LineCount))
    panel.pressKey(ConsoleKey.Key4)
    panel.pressKey(ConsoleKey.Key5)
    panel.pressKey(ConsoleKey.Start)
    assertEquals(panel.lineCount, 45)

    // Select secondary red parameter MIN IW (Lamp 6 with red SETUP lamp ON)
    panel.selectSetupParam(SetupParam.MinIw)
    assert(panel.isSetupLampOn)
    panel.pressKey(ConsoleKey.Key8)
    panel.pressKey(ConsoleKey.Start)
    assertEquals(panel.minInterword, 8)

    // Pressing 'R' outside setup entry sets hardware Line Stop latch
    panel.activeSetupParam = None
    panel.entryBuffer = ""
    panel.pressKey(ConsoleKey.KeyR)
    assert(panel.lineStopLatch)
  }

  test("Tape Transport Console 8 buttons enforce cover lock interlock and reread/skip") {
    import mtsc.periph.{MtscSystem, TapeCartridge, TapeConsoleButton}

    val sys = new MtscSystem()
    val cart = TapeCartridge.fromText("First line.\nSecond line.")
    sys.loadTape(Device.RightTape, cart)

    // Cover is locked while tape is loaded on the hub
    assert(sys.rightTape.isTapeLoaded)
    assert(!sys.rightTape.coverUnlocked)
    assert(!sys.rightTape.pressCoverUnlock(), "Cover cannot unlock while tape is loaded")

    // Advance tape with SKIP past first line
    val skippedPos = sys.rightTape.pressSkip()
    assert(skippedPos > 0)

    // REREAD backsteps to start of current line
    sys.rightTape.headPosition = 5
    assertEquals(sys.rightTape.pressReread(), 0)

    // UNLOAD RIGHT rewinds into cartridge, allowing COVER UNLOCK
    sys.pressTapeButton(TapeConsoleButton.UnloadRight)
    assert(!sys.rightTape.isTapeLoaded)
    assert(sys.rightTape.pressCoverUnlock(), "Cover unlocks after tape is unloaded")
  }

  test("CeConsoleState multiplexes 11 lamps across Display 1..4 and loads Initial Loader") {
    import mtsc.periph.MtscSystem

    val sys = new MtscSystem()
    val state = new CeConsoleState()
    state.faceplateInstalled = false

    // Set MAL to 0x501 (Y 11 = 1, Y 9 = 1, Y 1 = 1)
    sys.cpu.state.mal = Address(0x501)
    state.selectDisplayMode(CeDisplayMode.Display1)
    val d1 = state.computeLamps(sys.cpu)
    assert(d1(0).isOn, "Lamp 1 (Y 11) should be ON")
    assert(!d1(1).isOn, "Lamp 2 (Y 10) should be OFF")
    assert(d1(2).isOn, "Lamp 3 (Y 9) should be ON")
    assert(d1(10).isOn, "Lamp 11 (Y 1) should be ON")

    // Display 3: Inhibit on Lamp 2, -Low on Lamp 3, Eq on Lamp 4
    sys.cpu.state.inhibit = true
    sys.cpu.state.low = false
    sys.cpu.state.equal = true
    state.selectDisplayMode(CeDisplayMode.Display3)
    val d3 = state.computeLamps(sys.cpu)
    assert(!d3(0).isOn, "Lamp 1 has no signal in Display 3")
    assert(d3(1).isOn, "Lamp 2 (Inh) should be ON")
    assert(d3(2).isOn, "Lamp 3 (-Low) should be ON")
    assert(d3(3).isOn, "Lamp 4 (Eq) should be ON")

    // Initial Loader Table loads bytes 40..47 and sets PSAR to 42
    InitialLoaderTable.loadIntoMemory(sys.memory, paperTape = false, leftStation = false)
    assertEquals(sys.memory.psar, Address(42))
    assertEquals(sys.memory.readWord(Address(40)), Word(0x0000))
    assertEquals(sys.memory.readByte(Address(42)), 0x02)
    assertEquals(sys.memory.readByte(Address(43)), 0x18)

    // Verify word at byte 42 (0x02A) is 0x1802 and disassembles to IN RTAPE, R1
    val word42 = sys.memory.readWord(Address(42))
    assertEquals(word42, Word(0x1802))
    assertEquals(
      mtsc.isa.Decoder.decode(word42),
      Right(mtsc.isa.Instruction.Input(mtsc.types.Device.RightTape, RegId(1)))
    )
    assertEquals(mtsc.asm.Disassembler.disassemble(word42, Address(42)), "IN      TAPE_R, R1")

    // Verify word at byte 44 (0x02C) is 0x7A03 and disassembles to MOVB [R20]+, R1
    val word44 = sys.memory.readWord(Address(44))
    assertEquals(word44, Word(0x7a03))
    assertEquals(
      mtsc.isa.Decoder.decode(word44),
      Right(mtsc.isa.Instruction.ByteIndirect(mtsc.types.ArithOp.Transfer, RegId(20), pIndirect = true, RegId(1), qIndirect = false, bump = true))
    )
    assertEquals(mtsc.asm.Disassembler.disassemble(word44, Address(44)), "MOVB     [R20]++, R1")

    // Verify word at byte 46 (0x02E) is 0x002A and disassembles to JMP 0x02A (looping to 42)
    val word46 = sys.memory.readWord(Address(46))
    assertEquals(word46, Word(0x002a))
    val decoded46 = mtsc.isa.Decoder.decode(word46)
    assertEquals(decoded46, Right(mtsc.isa.Instruction.Branch(mtsc.types.Condition.Unconditional, link = false, Address(42))))
    val dis46 = mtsc.asm.Disassembler.disassemble(word46, Address(46))
    assertEquals(dis46, "JMP      0x02A")

    // Verify the Initial Loader actually boots a program from tape:
    // Place a small program at byte 4 (MOVI R4, 0x42; JMP 0x006) on tape, redirect R20 at byte 40 to 46,
    // and overwrite bytes 46..47 with JMP 0x004 (0x0004).
    val moviR4 = mtsc.isa.Encoder.encode(mtsc.isa.Instruction.ImmediateArith(mtsc.types.ArithOp.Transfer, RegId(4), 0x42))
    val jmpSelf = mtsc.isa.Encoder.encode(mtsc.isa.Instruction.Branch(mtsc.types.Condition.Unconditional, link = false, Address(6)))
    val bootTape = Array.fill[Int](43)(0)
    bootTape(4) = moviR4.lowByte
    bootTape(5) = moviR4.highByte
    bootTape(6) = jmpSelf.lowByte
    bootTape(7) = jmpSelf.highByte
    bootTape(40) = 46   // Redirect R20 to byte 46 (JMP instruction of Initial Loader)
    bootTape(41) = 0x04 // Overwrite byte 46 with 0x04 (JMP 0x004 low byte)
    bootTape(42) = 0x00 // Overwrite byte 47 with 0x00 (JMP 0x004 high byte)

    sys.rightTape.mount(mtsc.periph.TapeCartridge.fromProgramBytes(bootTape.toIndexedSeq, "BOOT"))

    // Execute 43 iterations * 3 instructions + 1 instruction at 0x004 (MOVI R4, 0x42)
    var steps = 0
    while (steps < 43 * 3 + 1) {
      sys.cpu.step()
      steps += 1
    }
    assertEquals(sys.memory.readReg(RegId(4)), Word(0x0042))
    assertEquals(sys.memory.psar, Address(6))
  }

  test("OperatorConsoleView renders Operator Panel and Tape Console") {
    import mtsc.periph.MtscSystem

    val sys = new MtscSystem()
    val state = new CeConsoleState()
    state.faceplateInstalled = true
    state.viewGalley = true

    val term = new AnsiTerminal(width = 104, height = 38)
    CeConsoleView.render(term, sys.cpu, state)
    val rendered = term.renderToString()

    // Operator Control Panel elements
    assert(rendered.contains("OPERATOR CONTROL PANEL (FACEPLATE ON)"))
    assert(rendered.contains("POWER"))
    assert(rendered.contains("CLEAR"))
    assert(rendered.contains("MERGE"))
    assert(rendered.contains("MEASURE"))
    assert(rendered.contains("SETUP"))
    assert(rendered.contains("LEADER"))
    assert(rendered.contains("MIN IW"))
    assert(rendered.contains("MAX IW"))
    assert(rendered.contains("[START]"))

    // Magnetic Tape Console 8 pushbuttons
    assert(rendered.contains("MAGNETIC TAPE CONSOLE"))
    assert(rendered.contains("REREAD"))
    assert(rendered.contains("SEARCH"))
    assert(rendered.contains("UNLOCK"))
    assert(rendered.contains("UNLOAD"))
  }

  test("Main.handleCommand wires interactive CE and Operator console shortcuts") {
    import mtsc.cli.Main
    import mtsc.periph.{MtscSystem, SetupParam}

    val sys = new MtscSystem()
    val state = new CeConsoleState()
    val term = new AnsiTerminal(width = 104, height = 38)

    // In CE mode: [1]..[4]/[0] select Display modes, [g]/[m] cycle sliders, [i] loads Initial Loader
    Main.handleCommand("3", sys, state, term, rawMode = true)
    assertEquals(state.displayMode, CeDisplayMode.Display3)

    Main.handleCommand("0", sys, state, term, rawMode = true)
    assertEquals(state.displayMode, CeDisplayMode.ClockDefault)

    Main.handleCommand("g", sys, state, term, rawMode = true)
    assertEquals(state.stepGranularity, CeStepGranularity.Bit)

    Main.handleCommand("m", sys, state, term, rawMode = true)
    assertEquals(state.editSelector, CeEditSelector.Edit)

    Main.handleCommand("i", sys, state, term, rawMode = true)
    assertEquals(sys.memory.psar, Address(42))

    // [f] installs the Operator Faceplate
    Main.handleCommand("f", sys, state, term, rawMode = true)
    assert(state.faceplateInstalled)

    // [p] starts Operator Setup prompt (CLEAR), '2' keys YES, 'c' (START) commits and advances to MERGE
    Main.handleCommand("p", sys, state, term, rawMode = true)
    assertEquals(sys.operatorPanel.activeSetupParam, Some(SetupParam.Clear))

    Main.handleCommand("2", sys, state, term, rawMode = true)
    assertEquals(sys.operatorPanel.entryBuffer, "2")

    Main.handleCommand("c", sys, state, term, rawMode = true)
    assert(sys.operatorPanel.clearDecision)
    assertEquals(sys.operatorPanel.activeSetupParam, Some(SetupParam.Merge))
  }

  test("CE manual Addr/Data bit keying (ILD 0308/0312/0603) keys entire 8-byte Initial Loader byte-by-byte") {
    import mtsc.periph.{ConsoleKey, MtscSystem}

    val sys = new MtscSystem()
    sys.clearMemory()
    val state = new CeConsoleState()
    state.faceplateInstalled = false

    // Verify ILD 0308 contact-to-bit mapping (right-to-left: R=bit 0 .. 0=bit 10; Data gated to R..3 = bits 0..7)
    assertEquals(ConsoleKey.KeyR.addrBitIndex, Some(0))
    assertEquals(ConsoleKey.Key9.addrBitIndex, Some(1))
    assertEquals(ConsoleKey.Key0.addrBitIndex, Some(10))
    assertEquals(ConsoleKey.Start.addrBitIndex, None)

    assertEquals(ConsoleKey.KeyR.dataBitIndex, Some(0))
    assertEquals(ConsoleKey.Key3.dataBitIndex, Some(7))
    assertEquals(ConsoleKey.Key2.dataBitIndex, None)
    assertEquals(ConsoleKey.Key1.dataBitIndex, None)
    assertEquals(ConsoleKey.Key0.dataBitIndex, None)
    assertEquals(ConsoleKey.Start.dataBitIndex, None)

    // Helper to manually enter an 11-bit byte address using Addr + Clear + Keys 0..9/R
    val addrKeysByBit: Vector[ConsoleKey] = Vector(
      ConsoleKey.KeyR, ConsoleKey.Key9, ConsoleKey.Key8, ConsoleKey.Key7,
      ConsoleKey.Key6, ConsoleKey.Key5, ConsoleKey.Key4, ConsoleKey.Key3,
      ConsoleKey.Key2, ConsoleKey.Key1, ConsoleKey.Key0
    )
    val dataKeysByBit: Vector[ConsoleKey] = addrKeysByBit.take(8)

    def keyAddress(addr: Int): Unit = {
      state.selectEditSelector(CeEditSelector.Addr, Some(sys.cpu))
      assertEquals(state.displayMode, CeDisplayMode.Display1, "Addr mode must wire to Display 1 (Y 11..Y 1)")
      state.pressCeClear(sys.cpu)
      assertEquals(sys.memory.psar, Address(0))
      for (bit <- 0 until 11 if ((addr >> bit) & 1) == 1) {
        state.pressCeKey(addrKeysByBit(bit), sys.cpu)
      }
      assertEquals(sys.memory.psar, Address(addr))
      assertEquals(sys.cpu.state.mal, Address(addr))
    }

    def keyDataByte(byteVal: Int): Unit = {
      state.selectEditSelector(CeEditSelector.Data, Some(sys.cpu))
      assertEquals(state.displayMode, CeDisplayMode.Display2, "Data mode must wire to Display 2 (O 8..O 1)")
      state.pressCeClear(sys.cpu)
      assertEquals(sys.memory.readByte(sys.memory.psar), 0)
      for (bit <- 0 until 8 if ((byteVal >> bit) & 1) == 1) {
        state.pressCeKey(dataKeysByBit(bit), sys.cpu)
      }
      assertEquals(sys.memory.readByte(sys.memory.psar), byteVal & 0xff)
      assertEquals(sys.cpu.state.outputReg, byteVal & 0xff)
    }

    // Key all 8 bytes (40..47) of the Right-Station Initial Loader manually, including odd byte addresses!
    for (row <- InitialLoaderTable.Rows) {
      keyAddress(row.byteAddress)
      keyDataByte(row.resolveByte(paperTape = false, leftStation = false))
    }

    // Verify Clear in Data mode on odd byte 47 did NOT clobber even byte 46 (0x2A)!
    assertEquals(sys.memory.readByte(Address(40)), 0x00)
    assertEquals(sys.memory.readByte(Address(41)), 0x00)
    assertEquals(sys.memory.readByte(Address(42)), 0x02)
    assertEquals(sys.memory.readByte(Address(43)), 0x18)
    assertEquals(sys.memory.readByte(Address(44)), 0x03)
    assertEquals(sys.memory.readByte(Address(45)), 0x7a)
    assertEquals(sys.memory.readByte(Address(46)), 0x2a)
    assertEquals(sys.memory.readByte(Address(47)), 0x00)

    // Set PSAR = 42 using Key 5 (+32), Key 7 (+8), Key 9 (+2), then return switch to Proc
    state.selectEditSelector(CeEditSelector.Addr, Some(sys.cpu))
    state.pressCeClear(sys.cpu)
    state.pressCeKey(ConsoleKey.Key5, sys.cpu) // +32
    state.pressCeKey(ConsoleKey.Key7, sys.cpu) // +8
    state.pressCeKey(ConsoleKey.Key9, sys.cpu) // +2
    assertEquals(sys.memory.psar, Address(42))

    state.selectEditSelector(CeEditSelector.Proc, Some(sys.cpu))
    assertEquals(state.editSelector, CeEditSelector.Proc)
    assertEquals(sys.memory.psar, Address(42))
  }
}
