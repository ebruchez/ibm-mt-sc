package mtsc.cpu.ild

import mtsc.console.{CeConsoleState, CeDisplayMode, CeEditSelector, InitialLoaderTable}
import mtsc.cpu.{Ccu, MockIoBus}
import mtsc.memory.CoreMemory
import mtsc.periph.ConsoleKey
import mtsc.types.{Address, Word}

class IldMilestone3Suite extends munit.FunSuite {

  test("ILD 0311: Output Register (OUT 1..8), LD OUT LTH, RES OUT LTH, and 0xFF Halt decode") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    // Pre-populate OUT 1..8 with non-zero bits to verify RES OUT LTH clears OUT 5..8 at B1-4
    s.setOutputByte(0xa5)
    assert(!s.halt)

    // At I-5, B1-4, CEN BT & B of an OUTPUT instruction when READY:
    // ldOutLth and resOutLth are both active; OUT 1..4 load from P BFR 1..4 while OUT 5..8 clear to 0
    s.output = true
    s.rdy = true
    s.insnT5 = true
    s.cenBt = true
    s.b = true
    s.b1_4 = true
    s.b5_8 = false
    s.pBfr1 = false
    s.pBfr2 = true
    s.pBfr3 = true
    s.pBfr4 = false // Low nibble = 0x6

    Ild0311OutputIo.updateOutputLatches(s)
    assert(s.ldOutLth, "LD OUT LTH must be active at I-5, CEN BT of OUTPUT")
    assert(s.resOutLth, "RES OUT LTH must be active at B1-4 & B & LD OUT LTH")
    assertEquals(s.outputByte, 0x06, "B1-4 must load low nibble 0x6 and clear high nibble to 0x0")

    // At I-5, B5-8, CEN BT & B: resOutLth is false; OUT 5..8 load from P BFR 1..4 while OUT 1..4 hold
    s.b1_4 = false
    s.b5_8 = true
    s.pBfr1 = true
    s.pBfr2 = false
    s.pBfr3 = true
    s.pBfr4 = true // High nibble = 0xD

    Ild0311OutputIo.updateOutputLatches(s)
    assert(s.ldOutLth)
    assert(!s.resOutLth)
    assertEquals(s.outputByte, 0xd6, "B5-8 must load high nibble 0xD while holding low nibble 0x6")
    assert(!s.halt)

    // Loading 0xFF asserts +HALT (-HALT active low on AA031 7G-AT)
    s.b1_4 = true
    s.b5_8 = false
    s.pBfr1 = true
    s.pBfr2 = true
    s.pBfr3 = true
    s.pBfr4 = true
    Ild0311OutputIo.updateOutputLatches(s)
    s.b1_4 = false
    s.b5_8 = true
    Ild0311OutputIo.updateOutputLatches(s)
    assertEquals(s.outputByte, 0xff)
    assert(s.halt, "All-ones (0xFF) in OUT 1..8 must assert HALT")
  }

  test("ILD 0311: MPX, PRE INH, INH, TSLTR SEL, BSY, RDY, and Translator Select Magnets 1..7") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    // 1. Set MPX at I-2, B13-16, CEN BT of OUTPUT when Y11 = 1 and RDY = 1
    s.output = true
    s.rdy = true
    s.y11 = true
    s.insnT0 = false
    s.insnT2 = true
    s.b1_4 = false
    s.b13_16 = true
    s.cenBt = true
    Ild0311OutputIo.updateControlLatches(s)
    assert(s.mpx, "MPX latch must set at I-2, B13-16, CEN BT when Y11 & OUTPUT & RDY")
    assert(s.tsltrSel, "TSLTR SEL must be set when MPX is active")

    // 2. Reset MPX at BEG PROG STEP when HALT (0xFF) is true
    s.setOutputByte(0xff)
    s.begPrgStp = true
    s.insnT2 = false
    s.insnT0 = true
    s.b13_16 = false
    s.b1_4 = true
    s.cenBt = false
    Ild0311OutputIo.updateControlLatches(s)
    assert(!s.mpx, "MPX latch must reset at BEG P.S. when HALT (0xFF) is active")

    // 3. Set BSY at I-5, B13-16, STR LTH STROBE of non-Lights OUTPUT, then reset RDY on TWR
    s.setOutputByte(0x55) // Ordinary character (out1=1, out3=0, out5=1, out7=1)
    s.tsltrSel = true
    s.lites = false
    s.output = true
    s.rdy = true
    s.insnT0 = false
    s.insnT5 = true
    s.b1_4 = false
    s.b13_16 = true
    s.strLthStrobe = true
    s.twr = true
    Ild0311OutputIo.updateControlLatches(s)
    assert(s.busy, "BSY latch must set at I-5, B13-16, STR LTH STROBE of Translator OUTPUT")
    assert(!s.rdy, "RDY latch must reset when BSY & TWR")
    assert(s.tsltrCycCl, "TSLTR CYC CL must engage when BSY & TSLTR SEL & -OUT INTF FDBK")
    assert(s.tsltrPr, "TSLTR PR must be active for non-dummy character output")
    assertEquals(s.translatorSelectMagnetsMask, 0x55, "TSLTR SEL MAG 1..7 must reflect OUT 1..7 (0x55)")

    // 4. In Edit mode, CEN BT sets PRE INH & INH; releasing START (kbCode A,B,D & !kbSingleEntry) resets PRE INH
    s.edit = true
    s.btCtrB = false
    s.cenBt = true
    s.twr = false
    s.strLthStrobe = false
    Ild0311OutputIo.updateControlLatches(s)
    assert(s.preInh, "PRE INH must set in Edit mode at CEN BT")
    assert(s.inh, "INH must set when PRE INH & -BT CTR B")

    // Release START key (0xD = A, B, D with kbSingleEntry = false) and apply TWR to clear INH
    s.setKeyboardCode(ConsoleKey.Start.kbCodeABCD, ready = false, singleEntry = false)
    s.twr = true
    Ild0311OutputIo.updateControlLatches(s)
    assert(!s.preInh, "Releasing START in Edit mode must reset PRE INH")
    assert(!s.inh, "TWR with -PRE INH must reset INH")
  }

  test("ILD 0312: 5-Position EDIT MEMORY SELECTOR wafers and DISP 1..4 pushbuttons") {
    val s = new BackplaneSignals()

    // 1. Proc mode: editSwitchContact = false, DISP 1..4 pushbuttons select DPLY 1..4
    s.editSwitch = CeEditSelector.Proc
    Ild0312ConsoleLights.applyDisplayModeButtons(s, CeDisplayMode.ClockDefault)
    assert(!s.editSwitchContact)
    assert(s.notAid)
    assert(!s.dply1 && !s.dply2 && !s.dply3 && !s.dply4)

    Ild0312ConsoleLights.applyDisplayModeButtons(s, CeDisplayMode.Display1)
    assert(s.dply1 && !s.dply2 && !s.dply3 && !s.dply4 && !s.notAid)

    Ild0312ConsoleLights.applyDisplayModeButtons(s, CeDisplayMode.Display2)
    assert(!s.dply1 && s.dply2 && !s.dply3 && !s.dply4 && !s.notAid)

    Ild0312ConsoleLights.applyDisplayModeButtons(s, CeDisplayMode.Display3)
    assert(!s.dply1 && !s.dply2 && s.dply3 && !s.dply4 && !s.notAid)

    Ild0312ConsoleLights.applyDisplayModeButtons(s, CeDisplayMode.Display4)
    assert(!s.dply1 && !s.dply2 && !s.dply3 && s.dply4 && !s.notAid)

    // 2. Addr mode: forces DPLY 1 (AID 2 = 1, AID 1 = 0, AID 3 = 0) regardless of DISP 1..4 buttons
    s.editSwitch = CeEditSelector.Addr
    s.disp4Pressed = true
    Ild0312ConsoleLights.evaluateSwitchAndDisplayDecodes(s)
    assert(s.editSwitchContact)
    assert(s.dply1 && !s.dply2 && !s.dply3 && !s.dply4)

    // 3. Data mode: forces DPLY 2 (AID 2 = 1, AID 3 = 1, AID 1 = 0) regardless of DISP 1..4 buttons
    s.editSwitch = CeEditSelector.Data
    s.disp1Pressed = true
    Ild0312ConsoleLights.evaluateSwitchAndDisplayDecodes(s)
    assert(s.editSwitchContact)
    assert(!s.dply1 && s.dply2 && !s.dply3 && !s.dply4)

    // 4. Prog mode: forces DPLY 3 (AID 1 = 1, AID 2 = 0, AID 3 = 0) regardless of DISP 1..4 buttons
    s.editSwitch = CeEditSelector.Prog
    s.disp2Pressed = true
    Ild0312ConsoleLights.evaluateSwitchAndDisplayDecodes(s)
    assert(s.editSwitchContact)
    assert(!s.dply1 && !s.dply2 && s.dply3 && !s.dply4)
  }

  test("ILD 0312: All 11 Console Indicator Lamps (LITE 1..11) across Display 1..4, Clock/Test, and LITES") {
    val s = new BackplaneSignals()
    s.editSwitch = CeEditSelector.Proc
    s.autoOrStep = true

    // Populate distinct bit patterns across every displayed register
    s.setMalValue(0x5a5) // Y11..Y1 = 101 1010 0101
    s.setOutputByte(0xc3) // O8..O1 = 1100 0011
    s.errorLite = true
    s.busy = false
    s.inh = true
    s.low = false // -LOW = true
    s.eq = true
    s.oplCde7 = true
    s.oplCde6 = false
    s.oplCde5 = true
    s.oplCde4 = false
    s.oplCde3 = true
    s.oplCde2 = false
    s.oplCde1 = true
    s.rdy = true
    s.mpx = false
    s.qBfr4 = true
    s.qBfr3 = false
    s.qBfr2 = true
    s.qBfr1 = false
    s.pBfr4 = false
    s.pBfr3 = true
    s.pBfr2 = false
    s.pBfr1 = true

    // 1. Display 1 (Y 11..1 = 101 1010 0101)
    Ild0312ConsoleLights.applyDisplayModeButtons(s, CeDisplayMode.Display1)
    assertEquals(
      s.lampsLeftToRight,
      Vector(true, false, true, true, false, true, false, false, true, false, true)
    )

    // 2. Display 2 (Reset=0, EOT=1, Busy=0, O8..O1 = 1100 0011)
    Ild0312ConsoleLights.applyDisplayModeButtons(s, CeDisplayMode.Display2)
    assertEquals(
      s.lampsLeftToRight,
      Vector(false, true, false, true, true, false, false, false, false, true, true)
    )

    // 3. Display 3 (Reset=0, Inh=1, -Low=1, Eq=1, OP7..OP1 = 1010101)
    Ild0312ConsoleLights.applyDisplayModeButtons(s, CeDisplayMode.Display3)
    assertEquals(
      s.lampsLeftToRight,
      Vector(false, true, true, true, true, false, true, false, true, false, true)
    )

    // 4. Display 4 (Reset=0, Rdy=1, Mpx=0, QB4..1 = 1010, PB4..1 = 0101)
    Ild0312ConsoleLights.applyDisplayModeButtons(s, CeDisplayMode.Display4)
    assertEquals(
      s.lampsLeftToRight,
      Vector(false, true, false, true, false, true, false, false, true, false, true)
    )

    // 5. Default Clock/Test Display (notAid && !autoOrStep): Reset, Test 4..1, ID..IA, B..A
    Ild0312ConsoleLights.applyDisplayModeButtons(s, CeDisplayMode.ClockDefault)
    s.autoOrStep = false
    s.masterReset = true
    s.setCeTestInputs(0x9) // Test 4=1, Test 3=0, Test 2=0, Test 1=1
    s.insnTCtrD = true
    s.insnTCtrC = false
    s.insnTCtrB = false
    s.insnTCtrA = true // I-9 (1001)
    s.btCtrB = true
    s.btCtrA = false // B9-12 (10)
    Ild0312ConsoleLights.evaluateCombinational(s)
    assertEquals(
      s.lampsLeftToRight,
      Vector(true, true, false, false, true, true, false, false, true, true, false)
    )

    // 6. Operator LITES mode (notAid && autoOrStep && lites): OUT REG DPLY gates BUSY & O8..O1
    s.masterReset = false
    s.autoOrStep = true
    s.edit = false
    s.lites = true
    s.busy = true
    Ild0312ConsoleLights.evaluateCombinational(s)
    assert(s.outRegDply, "OUT REG DPLY must be true when lites & notAid & autoOrStep")
    assertEquals(
      s.lampsLeftToRight,
      Vector(false, true, true, true, true, false, false, false, false, true, true)
    )
  }

  test("CE Console Addr & Data modes on IldCcu match behavioral Ccu across Clear, Key entry, and Bootstrap") {
    val memRef = CoreMemory.empty()
    val ioRef  = new MockIoBus()
    val ccuRef = new Ccu(memRef, ioRef)
    val conRef = new CeConsoleState()

    val memIld = CoreMemory.empty()
    val ioIld  = new MockIoBus()
    val ccuIld = new IldCcu(memIld, ioIld)
    val conIld = new CeConsoleState()

    // 1. Switch both consoles to [Addr] mode and press [Clear] to zero PSAR (0x222 -> 0x000)
    conRef.selectEditSelector(CeEditSelector.Addr, Some(ccuRef))
    conIld.selectEditSelector(CeEditSelector.Addr, ccuIld)
    assertEquals(memIld.psar, Address.PowerOn)
    assertEquals(ccuIld.state.mal, Address.PowerOn)

    conRef.pressCeClear(ccuRef)
    conIld.pressCeClear(ccuIld)
    assertEquals(memIld.psar, Address(0))
    assertEquals(ccuIld.state.mal, Address(0))
    assertEquals(conIld.computeLamps(ccuIld), conRef.computeLamps(ccuRef))

    // 2. Key in address 42 (0x02A = 32 + 8 + 2 -> Key5 [Y6=+32], Key7 [Y4=+8], Key9 [Y2=+2])
    for (key <- Seq(ConsoleKey.Key5, ConsoleKey.Key7, ConsoleKey.Key9)) {
      conRef.pressCeKey(key, ccuRef)
      conIld.pressCeKey(key, ccuIld)
      assertEquals(memIld.psar, memRef.psar)
      assertEquals(ccuIld.state.mal, ccuRef.state.mal)
      assertEquals(conIld.computeLamps(ccuIld), conRef.computeLamps(ccuRef))
    }
    assertEquals(memIld.psar, Address(42))

    // 3. Switch to [Data] mode, clear byte 42, and key in Initial Loader byte 42 (0x02 -> Key9 [O2=+2])
    conRef.selectEditSelector(CeEditSelector.Data, Some(ccuRef))
    conIld.selectEditSelector(CeEditSelector.Data, ccuIld)
    memRef.writeByte(Address(42), 0xff)
    memIld.writeByte(Address(42), 0xff)

    conRef.pressCeClear(ccuRef)
    conIld.pressCeClear(ccuIld)
    assertEquals(memIld.readByte(Address(42)), 0x00)
    assertEquals(ccuIld.state.outputReg, 0x00)
    assertEquals(conIld.computeLamps(ccuIld), conRef.computeLamps(ccuRef))

    // Verify that Key0, Key1, Key2, and Start are ignored by the hardware -BT CTR B gate in Data mode
    for (ignoredKey <- Seq(ConsoleKey.Key0, ConsoleKey.Key1, ConsoleKey.Key2, ConsoleKey.Start)) {
      conRef.pressCeKey(ignoredKey, ccuRef)
      conIld.pressCeKey(ignoredKey, ccuIld)
      assertEquals(memIld.readByte(Address(42)), 0x00, s"Key $ignoredKey must be ignored by -BT CTR B gate")
      assertEquals(ccuIld.state.outputReg, 0x00)
    }

    // Key in 0x92 (bits O8, O5, O2 -> Key3, Key6, Key9) at byte 42
    for (key <- Seq(ConsoleKey.Key3, ConsoleKey.Key6, ConsoleKey.Key9)) {
      conRef.pressCeKey(key, ccuRef)
      conIld.pressCeKey(key, ccuIld)
      assertEquals(memIld.readByte(Address(42)), memRef.readByte(Address(42)))
      assertEquals(ccuIld.state.outputReg, ccuRef.state.outputReg)
      assertEquals(conIld.computeLamps(ccuIld), conRef.computeLamps(ccuRef))
    }
    assertEquals(memIld.readByte(Address(42)), 0x92)

    // 4. Switch to [Edit] mode and press [START] to advance PSAR by +1 byte (42 -> 43, odd byte address!)
    conIld.selectEditSelector(CeEditSelector.Edit, ccuIld)
    conIld.pressCeKey(ConsoleKey.Start, ccuIld)
    assertEquals(memIld.psar, Address(43), "START in Edit mode must advance PSAR by +1 to odd byte 43")
    assertEquals(ccuIld.state.mal, Address(43))

    // Edit odd byte 43 in [Data] mode without disturbing even byte 42
    conIld.selectEditSelector(CeEditSelector.Data, ccuIld)
    conIld.pressCeClear(ccuIld)
    conIld.pressCeKey(ConsoleKey.Key6, ccuIld) // O5 (+16 = 0x10)
    conIld.pressCeKey(ConsoleKey.Key7, ccuIld) // O4 (+8  = 0x08) -> 0x18 (Initial Loader byte 43 for Right Tape)
    assertEquals(memIld.readByte(Address(43)), InitialLoaderTable.Rows(3).resolveByte())
    assertEquals(memIld.readByte(Address(42)), 0x92, "Editing odd byte 43 must not disturb even byte 42")
  }

  test("ILD 0311 & 0312: OUTPUT to Console Lights vs Translator sets LITES / TSLTR SEL latches") {
    val mem = CoreMemory.empty()
    val io  = new MockIoBus()
    val ild = new IldCcu(mem, io)

    // Store 0xA5 in R2 (low byte at address 4)
    mem.writeReg(mtsc.types.RegId(2), Word(0x00a5))

    // Assemble:
    //   546: OUT R2, LIGHTS     (device 7 -> Y8=1, Y9=1, Y10=1)
    //   548: OUT R2, TRANSLATOR (device 0 -> Y8=0, Y9=0, Y10=0)
    import mtsc.asm.Assembler
    val Right(img) = Assembler.assemble(
      """        .org 546
        |        OUT R2, LIGHTS
        |        OUT R2, TRANSLATOR
        |""".stripMargin
    ): @unchecked
    mem.loadFromImage(img)
    mem.writeReg(mtsc.types.RegId(2), Word(0x00a5))

    // 1. Execute OUT R2, LIGHTS (device 7)
    assertEquals(ild.step(), mtsc.cpu.StepResult.Normal)
    assert(ild.signals.lites, "OUT to device 7 (kbCde = 111) must set LITES latch")
    assert(!ild.signals.tsltrSel, "OUT to device 7 must keep TSLTR SEL false")
    assertEquals(ild.signals.outputByte, 0xa5)
    assertEquals(io.getOutput(mtsc.types.Device.Lights), List(0xa5))

    // Verify Operator LITES display gates O8..O1 (0xA5 = 1010 0101) onto LITE 8..1
    ild.signals.editSwitch = CeEditSelector.Proc
    ild.signals.autoOrStep = true
    Ild0312ConsoleLights.applyDisplayModeButtons(ild.signals, CeDisplayMode.ClockDefault)
    assert(ild.signals.outRegDply)
    assertEquals(
      ild.signals.lampsLeftToRight.drop(3), // LITE 8 .. LITE 1 (Lamps 4..11)
      Vector(true, false, true, false, false, true, false, true)
    )

    // 2. Execute OUT R2, Translator (device 0) -> DESEL OUT DEV resets LITES and sets TSLTR SEL
    assertEquals(ild.step(), mtsc.cpu.StepResult.Normal)
    assert(!ild.signals.lites, "OUT to device 0 (Translator) must reset LITES latch via DESEL OUT DEV")
    assert(ild.signals.tsltrSel, "OUT to device 0 (Translator) must set TSLTR SEL latch")
  }

  test("MtscSystem and CLI Main support selectable Behavioral (--behavioral) and Gate-Level (--ild) modes") {
    import mtsc.cli.Main
    import mtsc.console.AnsiTerminal
    import mtsc.cpu.{CcuEngineMode, StepResult}
    import mtsc.diag.{DiagnosticPrograms, EscapementRoutine}
    import mtsc.periph.MtscSystem

    val sys = new MtscSystem()
    val state = new CeConsoleState()
    val term = new AnsiTerminal(width = 104, height = 38)

    // Default engine mode for MtscSystem is Gate-Level ILD
    assertEquals(sys.engineMode, CcuEngineMode.Ild)
    assert(sys.cpu.isIldMode)

    // CLI flag --behavioral switches to Behavioral (ISA) mode
    Main.parseArgs(Array("--behavioral", "--max"), sys, state)
    assertEquals(sys.engineMode, CcuEngineMode.Behavioral)
    assert(!sys.cpu.isIldMode)
    assert(!state.realTimePacing)

    // CLI flag --ild switches back to Gate-Level ILD mode
    Main.parseArgs(Array("--ild"), sys, state)
    assertEquals(sys.engineMode, CcuEngineMode.Ild)
    assert(sys.cpu.isIldMode)

    // Interactive 'engine' command toggles between Ild and Behavioral
    Main.handleCommand("engine", sys, state, term, rawMode = false)
    assertEquals(sys.engineMode, CcuEngineMode.Behavioral)
    Main.handleCommand("engine", sys, state, term, rawMode = false)
    assertEquals(sys.engineMode, CcuEngineMode.Ild)

    // Verify full composition demo runs identically in both MtscSystem modes
    val sysBehavioral = new MtscSystem(initialEngineMode = CcuEngineMode.Behavioral)
    val sysIld        = new MtscSystem(initialEngineMode = CcuEngineMode.Ild)
    for (s <- Seq(sysBehavioral, sysIld)) {
      val st = new CeConsoleState()
      Main.loadProgram(DiagnosticPrograms.EndToEndCompositionDemo, s, st)
      assertEquals(s.run(maxCycles = 5000), StepResult.Halted)
    }
    assertEquals(sysIld.paper.lines, sysBehavioral.paper.lines)
    assertEquals(sysIld.memory.toCoreImage.bytes.toSeq, sysBehavioral.memory.toCoreImage.bytes.toSeq)
  }

  test("Sub-instruction stepping (Bit, Word, P.S., Jump) and CeTestProbe logic probes on IldCcu") {
    import mtsc.asm.Assembler
    import mtsc.console.{CeConsoleState, CeStepGranularity, CeTestProbe}
    import mtsc.periph.MtscSystem
    import mtsc.types.RegId

    val sys = new MtscSystem()
    val state = new CeConsoleState()
    state.faceplateInstalled = false
    state.selectDisplayMode(CeDisplayMode.ClockDefault)

    // Assemble a small program at 0x222:
    // 0x222: MOVI R2, 5
    // 0x224: ADDI R2, 3
    // 0x226: JMP 0x22A (unconditional branch)
    // 0x228: MOVI R2, 99 (skipped)
    // 0x22A: JMP 0x22A (self-loop halt)
    val src =
      """        .org 0x222
        |        MOVI R2, 5
        |        ADDI R2, 3
        |        JMP done
        |        MOVI R2, 99
        |done:   JMP done
        |""".stripMargin
    val Right(img) = Assembler.assemble(src): @unchecked
    sys.loadProgram(img)

    // 1. Step 4 Bit Times through I-0 (B1-4 -> B5-8 -> B9-12 -> B13-16 -> I-1, B1-4)
    assertEquals(sys.cpu.ild.signals.insnTime, 0)
    assertEquals(sys.cpu.ild.signals.bitTimeIndex, 0)

    sys.stepByGranularity(CeStepGranularity.Bit)
    assert(sys.cpu.ild.isMidInstruction)
    assertEquals(sys.cpu.ild.signals.insnTime, 0)
    assertEquals(sys.cpu.ild.signals.bitTimeIndex, 1) // B5-8

    sys.stepByGranularity(CeStepGranularity.Bit)
    assertEquals(sys.cpu.ild.signals.bitTimeIndex, 2) // B9-12

    sys.stepByGranularity(CeStepGranularity.Bit)
    assertEquals(sys.cpu.ild.signals.bitTimeIndex, 3) // B13-16

    // Connect Test 4 to +P ONLY and Test 3 to +PERMUTE and check ClockDefault lamps
    state.setTestProbe(4, CeTestProbe.POnly)
    state.setTestProbe(3, CeTestProbe.Prmt)
    val lampsAtI0B13_16 = state.computeLamps(sys.cpu)
    // Lamp 2 (index 1) is Test 4 (+P ONLY is true at I-0)
    assert(lampsAtI0B13_16(1).isOn)
    // Lamps 10, 11 (indices 9, 10) are Bit Ctr B, A = 1, 1 (B13-16)
    assert(lampsAtI0B13_16(9).isOn)
    assert(lampsAtI0B13_16(10).isOn)

    sys.stepByGranularity(CeStepGranularity.Bit)
    assertEquals(sys.cpu.ild.signals.insnTime, 1)
    assertEquals(sys.cpu.ild.signals.bitTimeIndex, 0) // I-1, B1-4

    // 2. Step by Word Time through I-1 -> I-6 (MOVI is an Immediate op, so I-1 permutes to I-6!)
    sys.stepByGranularity(CeStepGranularity.Word)
    assert(sys.cpu.ild.signals.immediate)
    assertEquals(sys.cpu.ild.signals.insnTime, 6)
    assertEquals(sys.cpu.ild.signals.bitTimeIndex, 0)

    // Finish the rest of MOVI R2, 5 via ProgramStep
    sys.stepByGranularity(CeStepGranularity.ProgramStep)
    assert(!sys.cpu.ild.isMidInstruction)
    assertEquals(sys.cpu.ild.signals.insnTime, 0)
    assertEquals(sys.cpu.ild.signals.bitTimeIndex, 0)
    assertEquals(sys.memory.readReg(RegId(2)), Word(5))
    assertEquals(sys.memory.psar, Address(0x224))

    // 3. Step by Jump: executes ADDI R2, 3 (at 0x224) and B done (at 0x226), stopping at 0x22A
    sys.stepByGranularity(CeStepGranularity.Jump)
    assertEquals(sys.memory.readReg(RegId(2)), Word(8))
    assertEquals(sys.memory.psar, Address(0x22a))
  }
}
