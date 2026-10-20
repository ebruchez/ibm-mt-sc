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
    assertEquals(sys.memory.readByte(Address(42)), 0x02)
    assertEquals(sys.memory.readByte(Address(43)), 0x18)
  }
}
