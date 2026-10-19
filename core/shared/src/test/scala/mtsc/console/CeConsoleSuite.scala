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
}
