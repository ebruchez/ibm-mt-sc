package mtsc.cpu.ild

import munit.FunSuite
import mtsc.asm.Assembler
import mtsc.cpu.{CcuEngineMode, StepResult}
import mtsc.periph.{MtscSystem, TapeCartridge, TapeConsoleButton, TapeFrame}
import mtsc.types.{Address, Device, RegId, Word}

class Ild0301To0302MagneticTapeSuite extends FunSuite {

  test("ILD 0302: 5-bit MT Counter (MT CTR A..E) steps through exact Count 0..10 sequence on MPS A & MPS B") {
    val s = new BackplaneSignals()

    // Reset to Count 0 via SET 0
    s.mtHome = true
    s.mtReread = true
    Ild0301To0302MagneticTape.updateCounter(s)
    s.mtReread = false
    Ild0301To0302MagneticTape.evaluateCombinational(s)

    assertEquals(s.mtCounterBits, 0x02) // 00010 (Count 0)
    assertEquals(s.mtCounterNumber, 0)
    assert(s.mtCount0)

    val expectedSequence = Vector(
      (true,  0x10, 1),  // MPS A #1 -> 10000 (Count 1 -> DL1)
      (false, 0x18, 2),  // MPS B #1 -> 11000 (Count 2 -> DL2)
      (true,  0x0c, 3),  // MPS A #2 -> 01100 (Count 3 -> DL3)
      (false, 0x04, 4),  // MPS B #2 -> 00100 (Count 4 -> DL4)
      (true,  0x16, 5),  // MPS A #3 -> 10110 (Count 5 -> DL8)
      (false, 0x1e, 6),  // MPS B #3 -> 11110 (Count 6 -> DL5)
      (true,  0x0b, 7),  // MPS A #4 -> 01011 (Count 7 -> DL6)
      (false, 0x03, 8),  // MPS B #4 -> 00011 (Count 8 -> DL9)
      (true,  0x11, 9),  // MPS A #5 -> 10001 (Count 9 -> DL7)
      (false, 0x19, 10)  // MPS B #5 -> 11001 (10 Count)
    )

    for ((isMpsA, expectedBits, expectedCount) <- expectedSequence) {
      Ild0301To0302MagneticTape.stepEmitterPulse(s, isMpsA)
      assertEquals(s.mtCounterBits, expectedBits, s"Count $expectedCount bits mismatch")
      assertEquals(s.mtCounterNumber, expectedCount, s"Count $expectedCount number mismatch")
    }

    assert(s.mtTenCount)
  }

  test("ILD 0302: Preset buses SET 15 (Load), SET 14 (Search), and COM RES (13 Count Unload)") {
    val s = new BackplaneSignals()

    // SET 15 (Load Right) -> 01010 (15 Count)
    s.mtLdRt = true
    Ild0301To0302MagneticTape.updateCounter(s)
    s.mtLdRt = false
    assertEquals(s.mtCounterBits, 0x0a)
    assertEquals(s.mtCounterNumber, 15)
    assert(s.mtCount15)

    // SET 14 (Search Key) -> 11111 (14 Count)
    s.mtSchKey = true
    Ild0301To0302MagneticTape.updateCounter(s)
    s.mtSchKey = false
    assertEquals(s.mtCounterBits, 0x1f)
    assertEquals(s.mtCounterNumber, 14)
    assert(s.mtCount14)
    assert(!s.mtCount13)

    // COM RES (EOT + UNLD) -> 11111 (13 Count Unload)
    s.mtEotOrUnld = true
    Ild0301To0302MagneticTape.updateCounter(s)
    assertEquals(s.mtCounterBits, 0x1f)
    assertEquals(s.mtCounterNumber, 13)
    assert(s.mtCount13)
    assert(s.errorLite)
  }

  test("ILD 0302: 9-bit Data Latches (DL 1..9), MT BITS permutation, and 3-Group (X/Y/Z) Odd Parity Checker") {
    val s = new BackplaneSignals()
    s.mtCde = true

    // Sweep every possible 8-bit value (0..255) with valid 9th-track odd parity
    for (byte <- 0 to 255) {
      val frame = TapeFrame.encodeByte8WithOddParity(byte)
      Ild0301To0302MagneticTape.sweepFrame(s, frame)

      assert(s.mtTenCount, s"Must land at 10 Count for byte 0x${byte.toHexString}")
      assertEquals(s.mtDecodedByte, byte, s"MT BITS AA211 must reconstruct 0x${byte.toHexString}")
      assertEquals(s.mtXParity, s.mtData1 ^ s.mtData2 ^ s.mtData3)
      assertEquals(s.mtYParity, s.mtData4 ^ s.mtData5 ^ s.mtData6)
      assertEquals(s.mtZParity, s.mtData7 ^ s.mtData8 ^ s.mtData9)
      assert(s.mtOddParity, s"Valid frame 0x${byte.toHexString} must satisfy ODD parity")
      assert(!s.errorLite, s"Error light must be off for valid frame 0x${byte.toHexString}")
      assert(s.mtIncr, s"INCR must set at 10 Count on valid odd parity")
      assert(s.charRdy, s"CHAR RDY must be asserted at 10 Count on valid odd parity")
    }

    // Corrupt parity on frame 0x5A: must fail odd parity, light ERROR LITE, and inhibit INCR and CHAR RDY
    val badFrame = TapeFrame.encodeWithCorruptParity(0x5a)
    Ild0301To0302MagneticTape.sweepFrame(s, badFrame)
    assert(s.mtTenCount)
    assert(!s.mtOddParity)
    assert(s.errorLite)
    assert(!s.mtIncr)
    assert(!s.charRdy)
  }

  test("ILD 0301-0302 & 0309: SKIP button sets MT SET LO, forces ODD, clears parity stall, and sets CCU +LOW LATCH at I-5") {
    val system = new MtscSystem(initialEngineMode = CcuEngineMode.Ild)
    val cart = TapeCartridge.fromText("AB")
    // Frame 0 is FeedCode, Frame 1 is 'A' (corrupt it), Frame 2 is 'B'
    cart.corruptParityAt(1)
    system.loadTape(Device.RightTape, cart)

    val src =
      """
        .org 546
        IN R4, TAPE_R        ; Read Frame 0 (FeedCode)
        IN R6, TAPE_R        ; Read Frame 1 (corrupted 'A'; after SKIP reads 'B' and sets LOW)
        JMP.LO SKIP_DETECTED
        MOVI R8, 1
        JMP DONE
      SKIP_DETECTED:
        MOVI R8, 99
      DONE:
        JMP DONE
      """
    system.loadProgram(Assembler.assemble(src).toOption.get)

    // 1. First IN reads Frame 0 (FeedCode) normally
    assertEquals(system.step(), StepResult.Normal)
    assert(!system.cpu.state.low)

    // 2. Second IN hits parity error on Frame 1 ('A'): stalls CCU, lights ERROR LITE on ILD 0302
    assertEquals(system.step(), StepResult.Stalled)
    assert(system.rightTape.parityError)
    assert(system.cpu.ild.signals.errorLite)
    assert(!system.cpu.ild.signals.mtOddParity)

    // 3. Operator presses SKIP on the Magnetic Tape Reader Console:
    //    Sets MT SET LO (s.setLo = true), clears parity error, and advances tape
    system.pressTapeButton(TapeConsoleButton.Skip)
    assert(system.cpu.ild.signals.setLo, "SKIP must latch MT SET LO (AA193)")
    assert(!system.cpu.ild.signals.errorLite, "SKIP must clear ERROR LITE")

    // 4. Retry the stalled IN instruction: completes, transfers MT SET LO into CCU +LOW LATCH at I-5,
    //    and RD COM & MT CDE at I-5, B13-16 resets MT SET LO!
    assertEquals(system.step(), StepResult.Normal)
    assert(system.cpu.state.low, "MT SET LO must set CCU +LOW LATCH during I-5 of INPUT (ILD 0309)")
    assert(!system.cpu.ild.signals.setLo, "RD COM & MT CDE at I-5, B13-16 must reset MT SET LO")

    // 5. JMP.LO takes the branch to SKIP_DETECTED
    assertEquals(system.step(), StepResult.Normal)
    assertEquals(system.step(), StepResult.Normal)
    assertEquals(system.memory.readReg(RegId(8)), Word(99))
  }

  test("ILD 0301-0302: Load, Dual-Station Search (TRF SCH STA), Reread, and Unload sequences") {
    val system = new MtscSystem(initialEngineMode = CcuEngineMode.Ild)
    val rightCart = TapeCartridge.fromText("Line1\nLine2")
    val leftCart  = TapeCartridge.fromText("Left1\nLeft2")

    system.loadTape(Device.RightTape, rightCart)
    system.loadTape(Device.LeftTape, leftCart)

    val s = system.cpu.ild.signals
    assert(s.mtTenCount, "After Load, MT CTR should be parked at 10 Count with first frame latched")
    assert(!s.mtRd, "MT RD flip-flop should be reset by CAR RET at end of Load")

    // Dual-Station Search: exercises SET 14 -> TRF SCH STA -> STA RT -> STA LF -> CAR RET
    val searchMsg = system.pressTapeButton(TapeConsoleButton.Search)
    assert(searchMsg.contains("Dual Tape SEARCH"))
    assert(!s.trSchSta, "TRF SCH STA must be reset after Left Station search completes")
    assert(!s.mtRd, "MT RD must be reset after Search completes")
    assert(s.mtTenCount)

    // Unload Right: sets 13 Count (11111) and lights ERROR LITE
    system.pressTapeButton(TapeConsoleButton.UnloadRight)
    assertEquals(s.mtCounterNumber, 13)
    assert(s.mtCount13)
    assert(s.errorLite)
    assert(s.mtHiBias)
  }
}
