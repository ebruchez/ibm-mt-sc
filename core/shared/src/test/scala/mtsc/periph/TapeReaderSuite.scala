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
}
