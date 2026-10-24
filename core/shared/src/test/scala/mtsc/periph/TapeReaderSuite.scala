package mtsc.periph

import mtsc.types.*
import mtsc.cpu.{StepResult, IoResult}
import mtsc.asm.Assembler

class TapeReaderSuite extends munit.FunSuite {

  test("TapeCartridge encodes characters with authentic odd parity") {
    val cart = TapeCartridge.fromText("IBM")
    // Frame 0 is FeedCode (0x00): with odd parity, bit 7 is set -> 0x80 (1 one-bit = odd)
    val f0 = cart.getFrame(0).get
    assert(f0.hasOddParity, "FeedCode must have odd parity")
    assertEquals(f0.rawByte, 0x80)

    // 'I' = 73 (0b01001001) -> 3 ones (already odd, bit 7 is 0) -> 0x49
    val f1 = cart.getFrame(1).get
    assert(f1.hasOddParity)
    assertEquals(f1.data7, 'I'.toInt)

    // 'B' = 66 (0b01000010) -> 2 ones (even, so bit 7 is 1) -> 0xC2
    val f2 = cart.getFrame(2).get
    assert(f2.hasOddParity)
    assertEquals(f2.data7, 'B'.toInt)
    assertEquals(f2.rawByte, 66 | 0x80)
  }

  test("TapeReader reads frames sequentially and updates head position") {
    val reader = new TapeReader(Device.RightTape)
    val cart = TapeCartridge.fromText("AB")
    reader.mount(cart)

    assertEquals(reader.headPosition, 0)

    // Read 0: FeedCode (0)
    val r0 = reader.read()
    assertEquals(r0, IoResult.Ready(TapeCartridge.FeedCode))
    assertEquals(reader.headPosition, 1)

    // Read 1: 'A'
    val r1 = reader.read()
    assertEquals(r1, IoResult.Ready('A'.toInt))
    assertEquals(reader.headPosition, 2)

    // Read 2: 'B'
    val r2 = reader.read()
    assertEquals(r2, IoResult.Ready('B'.toInt))
    assertEquals(reader.headPosition, 3)

    // Read 3: EndOfBlock (0xFF)
    val r3 = reader.read()
    assertEquals(r3, IoResult.Ready(TapeCartridge.EndOfBlock))
    assert(!reader.parityError)
  }

  test("TapeReader detects parity error and refuses to step tape") {
    val reader = new TapeReader(Device.RightTape)
    val cart = TapeCartridge.fromText("Z")
    // Corrupt parity of 'Z' (index 1)
    cart.corruptParityAt(1)
    reader.mount(cart)

    // Read FeedCode (ok)
    assertEquals(reader.read(), IoResult.Ready(TapeCartridge.FeedCode))
    assertEquals(reader.headPosition, 1)

    // Attempt to read corrupted 'Z': should fail parity check
    val errResult = reader.read()
    assertEquals(errResult, IoResult.Busy)
    assert(reader.parityError, "Parity error latch must be asserted")
    assertEquals(reader.headPosition, 1, "Tape must NOT advance on parity error")

    // Attempting to read again still fails at same position
    assertEquals(reader.read(), IoResult.Busy)
    assertEquals(reader.headPosition, 1)
  }

  test("TapeReader cycle delay causes pipeline stall in CCU") {
    val system = new MtscSystem()
    // Configure RightTape with 5 cycles mechanical delay
    system.rightTape.cycleDelay = 5

    val cart = TapeCartridge.fromText("X")
    system.loadTape(Device.RightTape, cart)

    val src =
      """
        .org 546
        IN R4, TAPE_R        ; Read FeedCode
        IN R6, TAPE_R        ; Read 'X'
      DONE:
        JMP DONE
      """
    system.loadProgram(Assembler.assemble(src).toOption.get)

    // First IN instruction executes: reads FeedCode and triggers 5-cycle delay
    val step1 = system.step()
    assertEquals(step1, StepResult.Normal)
    assertEquals(system.memory.readReg(RegId(4)).unsigned, TapeCartridge.FeedCode)

    // Second IN instruction at 548 stalls because tape is busy stepping
    val step2 = system.step()
    assertEquals(step2, StepResult.Stalled)
    assert(system.cpu.state.inhibit, "Inhibit latch should be set during stall")
    assertEquals(system.memory.psar, Address(548), "PSAR should not advance during stall")

    // Run remaining stepping cycles
    for (_ <- 0 until 4) {
      system.step()
    }

    // Now tape is ready, instruction at 548 completes
    val stepReady = system.step()
    assertEquals(stepReady, StepResult.Normal)
    assertEquals(system.memory.readReg(RegId(6)).unsigned, 'X'.toInt)
  }

  test("Left TapeReader reads frames sequentially and updates head position") {
    val reader = new TapeReader(Device.LeftTape)
    val cart = TapeCartridge.fromText("XY")
    reader.mount(cart)

    assertEquals(reader.headPosition, 0)
    assertEquals(reader.read(), IoResult.Ready(TapeCartridge.FeedCode))
    assertEquals(reader.headPosition, 1)

    assertEquals(reader.read(), IoResult.Ready('X'.toInt))
    assertEquals(reader.headPosition, 2)

    assertEquals(reader.read(), IoResult.Ready('Y'.toInt))
    assertEquals(reader.headPosition, 3)

    assertEquals(reader.read(), IoResult.Ready(TapeCartridge.EndOfBlock))
    assert(!reader.parityError)
  }

  test("Left TapeReader detects parity error and halts stepping") {
    val reader = new TapeReader(Device.LeftTape)
    val cart = TapeCartridge.fromText("M")
    cart.corruptParityAt(1)
    reader.mount(cart)

    assertEquals(reader.read(), IoResult.Ready(TapeCartridge.FeedCode))
    assertEquals(reader.read(), IoResult.Busy)
    assert(reader.parityError)
    assertEquals(reader.headPosition, 1)
  }

  test("Left TapeReader cycle delay causes CCU pipeline stall with IN R, TAPE_L") {
    val system = new MtscSystem()
    system.leftTape.cycleDelay = 3

    val cart = TapeCartridge.fromText("Q")
    system.loadTape(Device.LeftTape, cart)

    val src =
      """
        .org 546
        IN R10, TAPE_L       ; Read FeedCode from Left Tape
        IN R12, TAPE_L       ; Read 'Q' from Left Tape
      DONE:
        JMP DONE
      """
    system.loadProgram(Assembler.assemble(src).toOption.get)

    val s1 = system.step()
    assertEquals(s1, StepResult.Normal)
    assertEquals(system.memory.readReg(RegId(10)).unsigned, TapeCartridge.FeedCode)

    // Stalled on mechanical cycle
    val s2 = system.step()
    assertEquals(s2, StepResult.Stalled)
    assert(system.cpu.state.inhibit)

    system.step()
    system.step()

    val sReady = system.step()
    assertEquals(sReady, StepResult.Normal)
    assertEquals(system.memory.readReg(RegId(12)).unsigned, 'Q'.toInt)
  }

  test("Dual Tape Console: Search searches both stations forward when both are loaded") {
    val system = new MtscSystem()
    val rightCart = TapeCartridge.fromText("R1\nR2\n")
    val leftCart  = TapeCartridge.fromText("L1\nL2\n")

    system.loadTape(Device.RightTape, rightCart)
    system.loadTape(Device.LeftTape, leftCart)

    assert(system.rightTape.isTapeLoaded)
    assert(system.leftTape.isTapeLoaded)

    val msg = system.pressTapeButton(TapeConsoleButton.Search)
    assert(msg.contains("Dual Tape SEARCH"))
    assert(system.rightTape.headPosition > 0)
    assert(system.leftTape.headPosition > 0)
  }

  test("Dual Tape Console: Reread clears Left Tape parity error when Right Tape is clean") {
    val system = new MtscSystem()
    val rightCart = TapeCartridge.fromText("Clean Right")
    val leftCart  = TapeCartridge.fromText("Corrupt Left")
    leftCart.corruptParityAt(1)

    system.loadTape(Device.RightTape, rightCart)
    system.loadTape(Device.LeftTape, leftCart)

    // Read until left tape parity error
    system.leftTape.read() // FeedCode
    system.leftTape.read() // Corrupt frame
    assert(system.leftTape.parityError)
    assert(!system.rightTape.parityError)

    val msg = system.pressTapeButton(TapeConsoleButton.Reread)
    assert(msg.contains("Left Tape REREAD"))
    assert(!system.leftTape.parityError, "Left tape parity error must be cleared by Reread")
  }

  test("Dual Tape Console: Cover Unlock operates with independent station interlocks") {
    val system = new MtscSystem()
    val rightCart = TapeCartridge.fromText("Right Tape")
    val leftCart  = TapeCartridge.fromText("Left Tape")

    system.loadTape(Device.RightTape, rightCart)
    system.loadTape(Device.LeftTape, leftCart)

    // Both loaded: cover unlock rejected
    val msg1 = system.pressTapeButton(TapeConsoleButton.CoverUnlock)
    assert(msg1.contains("Cover interlock active"))

    // Unload Right only
    system.pressTapeButton(TapeConsoleButton.UnloadRight)
    val msg2 = system.pressTapeButton(TapeConsoleButton.CoverUnlock)
    assert(msg2.contains("Right Tape Station Cover unlocked"))
    assert(system.rightTape.coverUnlocked)
    assert(!system.leftTape.coverUnlocked)

    // Unload Left as well
    system.pressTapeButton(TapeConsoleButton.UnloadLeft)
    val msg3 = system.pressTapeButton(TapeConsoleButton.CoverUnlock)
    assert(msg3.contains("Tape Station Cover unlocked"))
    assert(system.leftTape.coverUnlocked)
  }

  test("Initial Loader Table boots from Left Station (m=1) decoding IN R1, TAPE_L") {
    import mtsc.console.InitialLoaderTable

    val memory = mtsc.memory.CoreMemory.empty()
    InitialLoaderTable.loadIntoMemory(memory, paperTape = false, leftStation = true)

    assertEquals(memory.psar, Address(42))
    val word42 = memory.readWord(Address(42))
    assertEquals(word42, Word(0x1902)) // m=1 in bit 8
    assertEquals(
      mtsc.isa.Decoder.decode(word42),
      Right(mtsc.isa.Instruction.Input(Device.LeftTape, RegId(1)))
    )
  }

  test("ContinuousComposerRoutine: Full composition from Left Tape when set as input station") {
    import mtsc.diag.ContinuousComposerRoutine
    import mtsc.periph.ComposerTranslator

    val system = new MtscSystem()
    val sampleText = "This camera-ready text is read directly from the Left Tape Station on the IBM MT/SC."
    val cart = TapeCartridge.fromText(sampleText, "Left Station Test")
    system.loadTape(Device.LeftTape, cart)

    // Assemble and load continuous composer program
    val img = Assembler.assemble(ContinuousComposerRoutine.Source).toOption.get
    system.loadProgram(img)
    mtsc.diag.EscapementRoutine.initializeTable(system.memory)

    // Patch input station to LeftTape (address 558)
    ContinuousComposerRoutine.setInputStation(system.memory, Device.LeftTape)
    assertEquals(ContinuousComposerRoutine.getInputStation(system.memory), Device.LeftTape)

    // Run composition
    system.run(maxCycles = 500000L)

    assert(system.paper.lines.nonEmpty, "Composer paper must contain printed lines from Left Tape")
    val printed = system.paper.lines.mkString(" ")
    assert(printed.contains("camera-ready text"), s"Printed output was: $printed")
    assert(system.leftTape.headPosition > 1, "Left Tape reader head must have stepped forward")
  }
}
