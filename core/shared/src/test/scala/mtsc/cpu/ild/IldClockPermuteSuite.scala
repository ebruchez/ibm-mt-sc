package mtsc.cpu.ild

import munit.FunSuite
import mtsc.console.{CeEditSelector, CeStepGranularity}
import mtsc.isa.*
import mtsc.types.*

class IldClockPermuteSuite extends FunSuite {

  /**
   * Steps word-by-word through one complete Program Step starting at `I-0, B1-4`,
   * loading `OPL CDE 1..7` from `insnWord` upon entering `I-1`, and returns the
   * sequence of `(insnTime, singleShotPulses)` visited until returning to `I-0`.
   */
  private def runInstructionSequence(
    s: BackplaneSignals,
    insnWord: Word,
    low: Boolean = false,
    eq: Boolean = false
  ): Vector[(Int, Int)] = {
    s.low = low
    s.eq = eq
    Ild0304Permute.evaluateCombinational(s)

    val visited = Vector.newBuilder[(Int, Int)]
    var steps = 0
    var done = false

    while (!done && steps < 20) {
      val curInsnTime = s.insnTime
      if (curInsnTime == 1) {
        s.loadOpCodeFromWord(insnWord)
        Ild0303Clock.evaluateCombinational(s)
      }
      val pulses = Ild0303Clock.stepWordTime(s)
      visited += ((curInsnTime, pulses))
      steps += 1
      if (s.insnTime == 0) {
        done = true
      }
    }

    visited.result()
  }

  test("Timing Diagram 0601: 16 sub-phase columns of a P-Only Bit Time (33.3 us = 8 single-shots)") {
    val s = new BackplaneSignals()
    Ild0303Clock.resetBeforeFirstSsa(s)

    // Initially at I-0, B1-4 (P-Only = true)
    assertEquals(s.insnTime, 0)
    assertEquals(s.bitTimeIndex, 0)
    assert(s.pOnly)

    val subPhases = Vector(
      OscSubPhase.SsaOn, OscSubPhase.SsaOff, OscSubPhase.SsbOn, OscSubPhase.SsbOff, // Cycle 1: Indicator Load
      OscSubPhase.SsaOn, OscSubPhase.SsaOff, OscSubPhase.SsbOn, OscSubPhase.SsbOff, // Cycle 2: Read P-Time (X then Y)
      OscSubPhase.SsaOn, OscSubPhase.SsaOff, OscSubPhase.SsbOn, OscSubPhase.SsbOff, // Cycle 3: Write P-Time (BMP STRB T, TWR, STR LTH STRB)
      OscSubPhase.SsaOn, OscSubPhase.SsaOff, OscSubPhase.SsbOn, OscSubPhase.SsbOff  // Cycle 4: Post-Write Transition
    )

    val trace = subPhases.map { phase =>
      assert(Ild0303Clock.stepSubPhase(s, phase))
      (
        s.ssa, s.ssb,
        s.a, s.b, s.twr, s.pTime, s.cenBt,
        s.resInds, s.ldInds, s.bmpStrbT, s.strLthStrobe,
        s.csXLog, s.csYLog, s.rewo, s.rowe
      )
    }

    // Column 0 (SSA #1 ON): A=0, B=1, P TIME=1, CEN BT=0 -> RES INDS=1 (1 col), LD INDS=1, CS X/Y=0 (-P TIME* = 0)
    assertEquals(trace(0),  (true,  false, false, true,  false, true,  false, true,  true,  false, false, false, false, false, false))
    // Column 1 (SSA #1 OFF): RES INDS drops to 0, LD INDS stays 1 (2 cols wide!)
    assertEquals(trace(1),  (false, false, false, true,  false, true,  false, false, true,  false, false, false, false, false, false))
    // Column 2..3 (SSB #1 ON/OFF): A=0, B=0, P TIME=1, CEN BT=0
    assertEquals(trace(2),  (false, true,  false, false, false, true,  false, false, false, false, false, false, false, false, false))
    assertEquals(trace(3),  (false, false, false, false, false, true,  false, false, false, false, false, false, false, false, false))

    // Column 4..5 (SSA #2 ON/OFF): A=1, B=0, P TIME=1, CEN BT=1 -> Read P-Time X (CS X = 1)
    assertEquals(trace(4),  (true,  false, true,  false, false, true,  true,  false, false, false, false, true,  false, false, false))
    assertEquals(trace(5),  (false, false, true,  false, false, true,  true,  false, false, false, false, true,  false, false, false))
    // Column 6..7 (SSB #2 ON/OFF): A=1, B=1, P TIME=1, CEN BT=1 -> Read P-Time Y (CS Y = 1, REWO = 1)
    assertEquals(trace(6),  (false, true,  true,  true,  false, true,  true,  false, false, false, false, false, true,  true,  false))
    assertEquals(trace(7),  (false, false, true,  true,  false, true,  true,  false, false, false, false, false, true,  true,  false))

    // Column 8..9 (SSA #3 ON/OFF): A=0, B=1, CEN BT=1 -> BMP STRB T = 1, P TIME resets to 0, TWR sets to 1 (P-Only!), Write P-Time X (CS X = 1)
    assertEquals(trace(8),  (true,  false, false, true,  true,  false, true,  false, false, true,  false, true,  false, false, false))
    assertEquals(trace(9),  (false, false, false, true,  true,  false, true,  false, false, true,  false, true,  false, false, false))
    // Column 10..11 (SSB #3 ON/OFF): A=0, B=0, CEN BT=1, TWR=1 -> STR LTH STRB = 1, Write P-Time Y (CS Y = 1, REWO = 1)
    assertEquals(trace(10), (false, true,  false, false, true,  false, true,  false, false, false, true,  false, true,  true,  false))
    assertEquals(trace(11), (false, false, false, false, true,  false, true,  false, false, false, true,  false, true,  true,  false))

    // Steering latch A was set by STR LTH STRB during columns 10..11, while indicator btCtrA is still 0 (B1-4)
    assertEquals(s.strBtCtrA, true)
    assertEquals(s.strBtCtrB, false)
    assertEquals(s.bitTimeIndex, 0)

    // Column 12..13 (SSA #4 ON/OFF): A=1, B=0 -> P TIME sets to 1, CEN BT resets to 0, TWR resets to 0, CS X/Y = 0 (-P TIME* = 0)
    assertEquals(trace(12), (true,  false, true,  false, false, true,  false, false, false, false, false, false, false, false, false))
    assertEquals(trace(13), (false, false, true,  false, false, true,  false, false, false, false, false, false, false, false, false))
    // Column 14..15 (SSB #4 ON/OFF): A=1, B=1, P TIME=1, CEN BT=0 -> ready for SSA #1 of BIT 5-8
    assertEquals(trace(14), (false, true,  true,  true,  false, true,  false, false, false, false, false, false, false, false, false))
    assertEquals(trace(15), (false, false, true,  true,  false, true,  false, false, false, false, false, false, false, false, false))
  }

  test("Timing Diagram 0601: 24 sub-phase columns of a P & Q Bit Time (50.0 us = 12 single-shots)") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    // Advance 1 Word Time (I-0 -> I-1) so we are at I-1, B1-4 where P ONLY = false (P & Q cycle)
    val i0Pulses = Ild0303Clock.stepWordTime(s)
    assertEquals(i0Pulses, 32)
    assertEquals(s.insnTime, 1)
    assertEquals(s.bitTimeIndex, 0)
    assert(!s.pOnly)

    // Step through the 12 single-shots of I-1, B1-4 -> B5-8
    val pulses = Ild0303Clock.stepBitTime(s)
    assertEquals(pulses, 12)
    assertEquals(s.insnTime, 1)
    assertEquals(s.bitTimeIndex, 1) // B5-8
  }

  test("Timing Diagram 0601: Bit Time Counter steering latch B stays set from B5-8 until B13-16 PERMUTE TIME") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    // Start at B1-4: strBtCtr = (0, 0), btCtr = (0, 0)
    assert(s.b1_4 && !s.strBtCtrA && !s.strBtCtrB)

    // Step to B5-8: btCtr = (A=1, B=0)
    assertEquals(Ild0303Clock.stepBitTime(s), 8)
    assert(s.b5_8 && s.btCtrA && !s.btCtrB)

    // Step to B9-12: during B5-8's STR LTH STROBE, strBtCtrB was set and stays set!
    assertEquals(Ild0303Clock.stepBitTime(s), 8)
    assert(s.b9_12 && !s.btCtrA && s.btCtrB && s.strBtCtrB)

    // Step to B13-16: strBtCtrB was still set during B9-12 -> B13-16 transition, so btCtrB stays true!
    assertEquals(Ild0303Clock.stepBitTime(s), 8)
    assert(s.b13_16 && s.btCtrA && s.btCtrB)

    // Step to B1-4 of I-1: during B13-16's PERMUTE TIME, strBtCtrB was reset, so both btCtrA and btCtrB reset to 0!
    assertEquals(Ild0303Clock.stepBitTime(s), 8)
    assert(s.b1_4 && !s.btCtrA && !s.btCtrB && !s.strBtCtrB)
    assertEquals(s.insnTime, 1)
  }

  test("Timing Diagram 0603: Register Direct Arithmetic permutes I-0 -> I-1 -> I-2 -> I-5 -> I-6 -> I-9 -> I-0") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    for (op <- List(ArithOp.Add, ArithOp.Subtract, ArithOp.Compare, ArithOp.Transfer)) {
      val word = Encoder.encode(Instruction.RegisterArith(op, RegId(2), RegId(4)))
      val seq = runInstructionSequence(s, word)
      assertEquals(
        seq,
        Vector(
          (0, 32), // I-0: P-Only (32 pulses)
          (1, 48), // I-1: P & Q  (48 pulses)
          (2, 32), // I-2: P-Only (32 pulses) -> permutes to I-5
          (5, 32), // I-5: P-Only (32 pulses)
          (6, 32), // I-6: P-Only (32 pulses) -> SET 9 permutes to I-9
          (9, 48)  // I-9: P & Q  (48 pulses) -> permutes to I-0
        ),
        s"Failed for RegisterArith($op)"
      )
    }
  }

  test("Timing Diagram 0603: Q-Indirect Arithmetic permutes I-0 -> I-1 -> I-2 -> I-3 -> I-4 -> I-5 -> I-6 -> I-9 -> I-0") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    val word = Encoder.encode(
      Instruction.ByteIndirect(ArithOp.Transfer, RegId(1), pIndirect = false, qReg = RegId(2), qIndirect = true, bump = true)
    )
    val seq = runInstructionSequence(s, word)
    assertEquals(
      seq,
      Vector(
        (0, 32),
        (1, 48),
        (2, 32),
        (3, 32),
        (4, 32),
        (5, 32),
        (6, 32),
        (9, 48)
      )
    )
  }

  test("Timing Diagram 0603: P-Indirect Arithmetic permutes I-0 -> I-1 -> I-2 -> I-5 -> I-6 -> I-7 -> I-8 -> I-9 -> I-0") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    val word = Encoder.encode(
      Instruction.ByteIndirect(ArithOp.Compare, RegId(1), pIndirect = true, qReg = RegId(2), qIndirect = false, bump = false)
    )
    val seq = runInstructionSequence(s, word)
    assertEquals(
      seq,
      Vector(
        (0, 32),
        (1, 48),
        (2, 32),
        (5, 32),
        (6, 32),
        (7, 32),
        (8, 32),
        (9, 48)
      )
    )
  }

  test("Timing Diagram 0603: P & Q Indirect Arithmetic visits all 10 Instruction Times I-0 .. I-9") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    val word = Encoder.encode(
      Instruction.ByteIndirect(ArithOp.Transfer, RegId(3), pIndirect = true, qReg = RegId(5), qIndirect = true, bump = true)
    )
    val seq = runInstructionSequence(s, word)
    assertEquals(
      seq,
      Vector(
        (0, 32),
        (1, 48),
        (2, 32),
        (3, 32),
        (4, 32),
        (5, 32),
        (6, 32),
        (7, 32),
        (8, 32),
        (9, 48)
      )
    )
  }

  test("Timing Diagram 0603: Immediate Arithmetic permutes I-0 -> I-1 -> I-6 -> I-9 -> I-0 (even with odd immediate byte)") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    // Test both even (op7=0) and odd (op7=1) immediate constants to verify [AA061 5K-AZ] SET 9 override
    for (imm <- List(0x42, 0x43)) {
      val word = Encoder.encode(Instruction.ImmediateArith(ArithOp.Add, RegId(4), imm))
      val seq = runInstructionSequence(s, word)
      assertEquals(
        seq,
        Vector(
          (0, 32),
          (1, 48),
          (6, 32),
          (9, 48)
        ),
        s"Failed for ImmediateArith with imm=0x${imm.toHexString}"
      )
    }
  }

  test("Timing Diagram 0603: Input and Output permute I-0 -> I-1 -> I-2 -> I-5 -> I-0") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    val inWord  = Encoder.encode(Instruction.Input(Device.RightTape, RegId(1)))
    val outWord = Encoder.encode(Instruction.Output(Device.Translator, RegId(2), indirect = false, multiplex = false))

    assertEquals(runInstructionSequence(s, inWord),  Vector((0, 32), (1, 48), (2, 32), (5, 32)))
    assertEquals(runInstructionSequence(s, outWord), Vector((0, 32), (1, 48), (2, 32), (5, 32)))
  }

  test("Timing Diagram 0603 & AA131: Branch without Link (Taken -> I-0,I-1,I-9; Not Taken -> I-0,I-1) across all 5 conditions") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    val target = Address(0x120)
    // Condition latch states:
    // - Hi:  low = false, eq = false
    // - Lo:  low = true,  eq = false
    // - Eq:  low = false, eq = true
    val latchStates = List(
      ("Hi", false, false),
      ("Lo", true,  false),
      ("Eq", false, true)
    )

    val expectations: Map[(Condition, String), Boolean] = Map(
      (Condition.Unconditional, "Hi") -> true,
      (Condition.Unconditional, "Lo") -> true,
      (Condition.Unconditional, "Eq") -> true,
      (Condition.Hi,            "Hi") -> true,
      (Condition.Hi,            "Lo") -> false,
      (Condition.Hi,            "Eq") -> false,
      (Condition.Lo,            "Hi") -> false,
      (Condition.Lo,            "Lo") -> true,
      (Condition.Lo,            "Eq") -> false,
      (Condition.Equal,         "Hi") -> false,
      (Condition.Equal,         "Lo") -> false,
      (Condition.Equal,         "Eq") -> true,
      (Condition.NotEqual,      "Hi") -> true,
      (Condition.NotEqual,      "Lo") -> true,
      (Condition.NotEqual,      "Eq") -> false
    )

    for {
      cond <- List(Condition.Unconditional, Condition.Hi, Condition.Lo, Condition.Equal, Condition.NotEqual)
      (stateName, low, eq) <- latchStates
    } {
      val word = Encoder.encode(Instruction.Branch(cond, link = false, target))
      val expectedTaken = expectations((cond, stateName))
      val seq = runInstructionSequence(s, word, low = low, eq = eq)
      val expectedSeq =
        if (expectedTaken) Vector((0, 32), (1, 48), (9, 48))
        else Vector((0, 32), (1, 48))
      assertEquals(seq, expectedSeq, s"Branch($cond, link=false) in state $stateName")
    }
  }

  test("Timing Diagram 0603: Branch with Link uses P&Q at I-3 (I-0 -> I-1 -> I-2 -> I-3 [48 pulses] -> I-9 -> I-0)") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    val linkWord = Encoder.encode(Instruction.Branch(Condition.Unconditional, link = true, Address(0x200)))
    val seq = runInstructionSequence(s, linkWord)
    // Notice I-3 has 48 pulses (P & Q cycle) because P ONLY = -INSN T 1 & -INSN T 9 & (-JMP | -INSN T 3)!
    assertEquals(
      seq,
      Vector(
        (0, 32),
        (1, 48),
        (2, 32),
        (3, 48),
        (9, 48)
      )
    )
  }

  test("Timing Diagram 0603: Incremental Branch (Taken -> I-0..I-3,I-9; Not Taken -> I-0..I-3) uses P-Only at I-3") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    val incOnSetWord   = Encoder.encode(Instruction.IncrementalBranch(forward = true, onBitSet = true, bit = 3, reg = RegId(1), incrementSteps = 4))
    val incOnClearWord = Encoder.encode(Instruction.IncrementalBranch(forward = false, onBitSet = false, bit = 3, reg = RegId(1), incrementSteps = 2))

    // In hardware (AA132 / AA131), the sampled bit sets +EQUAL at I-3 if the bit is 1, or leaves -EQUAL if 0:
    // 1. onBitSet = true, eq = true (Taken) -> I-0, I-1, I-2, I-3 (32 pulses P-Only!), I-9
    assertEquals(
      runInstructionSequence(s, incOnSetWord, eq = true),
      Vector((0, 32), (1, 48), (2, 32), (3, 32), (9, 48))
    )
    // 2. onBitSet = true, eq = false (Not Taken) -> I-0, I-1, I-2, I-3
    assertEquals(
      runInstructionSequence(s, incOnSetWord, eq = false),
      Vector((0, 32), (1, 48), (2, 32), (3, 32))
    )
    // 3. onBitSet = false, eq = false (Taken) -> I-0, I-1, I-2, I-3, I-9
    assertEquals(
      runInstructionSequence(s, incOnClearWord, eq = false),
      Vector((0, 32), (1, 48), (2, 32), (3, 32), (9, 48))
    )
    // 4. onBitSet = false, eq = true (Not Taken) -> I-0, I-1, I-2, I-3
    assertEquals(
      runInstructionSequence(s, incOnClearWord, eq = true),
      Vector((0, 32), (1, 48), (2, 32), (3, 32))
    )
  }

  test("Timing Diagram 0603: Program Control (BitControl) permutes I-0 -> I-1 -> I-2 -> I-3 -> I-0") {
    val s = new BackplaneSignals()
    Ild0303Clock.reset(s)

    for (bitOp <- List(BitOp.Set, BitOp.Reset, BitOp.Preserve, BitOp.Test)) {
      val word = Encoder.encode(Instruction.BitControl(bitOp, bit = 5, RegId(3)))
      assertEquals(
        runInstructionSequence(s, word),
        Vector((0, 32), (1, 48), (2, 32), (3, 32)),
        s"Failed for BitControl($bitOp)"
      )
    }
  }

  test("Timing Diagram 0603: CE Edit mode forces I-0 -> I-1 -> I-0 regardless of opcode") {
    val s = new BackplaneSignals()
    s.editSwitch = CeEditSelector.Addr
    Ild0303Clock.reset(s)

    val arithWord = Encoder.encode(Instruction.RegisterArith(ArithOp.Add, RegId(1), RegId(2)))
    assertEquals(runInstructionSequence(s, arithWord), Vector((0, 32), (1, 48)))
  }

  test("AA071 Clock Control Switch: BIT mode single-steps one Bit Time per STEP button press with STP CLK 2 interlock") {
    val s = new BackplaneSignals()
    s.clockSwitch = CeStepGranularity.Bit
    Ild0303Clock.reset(s)

    // Clock stops immediately on SSA #1 of B1-4 because RES INDS sets STP CLK 1
    assert(s.stpClk1)
    assert(!s.stpClk2)
    assertEquals(s.insnTime, 0)
    assertEquals(s.bitTimeIndex, 0)
    assert(!Ild0303Clock.stepSingleShot(s))

    // Press and hold STEP button: resets STP CLK 1; STP CLK 2 sets on SSB & A; stops at B5-8 even while STEP is held!
    s.stepButtonPressed = true
    var pulses = 0
    while (Ild0303Clock.stepSingleShot(s) && pulses < 30) {
      pulses += 1
    }
    // 8 single-shots after SSA #1 of B1-4 (SSB #1..SSB #4 + SSA #1 of B5-8) bring us to SSA #1 of B5-8 (where STP CLK 1 sets again)
    assertEquals(pulses, 8)
    assert(s.stpClk1)
    assert(s.stpClk2) // Still set because STEP button is still held down!
    assertEquals(s.bitTimeIndex, 1) // B5-8

    // While STEP button remains held, oscillator cannot advance another bit time!
    assert(!Ild0303Clock.stepSingleShot(s))

    // Release STEP button: resets STP CLK 2, re-arming the button for the next press
    s.stepButtonPressed = false
    Ild0303Clock.evaluateStopClockLatches(s)
    assert(s.stpClk1)
    assert(!s.stpClk2)

    // Press STEP again to advance from B5-8 to B9-12
    s.stepButtonPressed = true
    pulses = 0
    while (Ild0303Clock.stepSingleShot(s) && pulses < 30) {
      pulses += 1
    }
    assertEquals(pulses, 8)
    assertEquals(s.bitTimeIndex, 2) // B9-12
  }

  test("AA071 Clock Control Switch: WORD mode single-steps one 16-bit Instruction Time per STEP button press") {
    val s = new BackplaneSignals()
    s.clockSwitch = CeStepGranularity.Word
    Ild0303Clock.reset(s)

    // Stopped at I-0, B1-4
    assert(s.stpClk1)
    assertEquals(s.insnTime, 0)
    assertEquals(s.bitTimeIndex, 0)

    // Press STEP to advance one full Word Time (I-0 -> I-1, B1-4)
    s.stepButtonPressed = true
    var pulses = 0
    while (Ild0303Clock.stepSingleShot(s) && pulses < 100) {
      pulses += 1
    }
    assertEquals(pulses, 32) // 32 single-shots after SSA #1 of I-0,B1-4 reach SSA #1 of I-1,B1-4
    assert(s.stpClk1)
    assertEquals(s.insnTime, 1)
    assertEquals(s.bitTimeIndex, 0)
  }

  test("AA071 Clock Control Switch: JMP mode runs through non-jump / untaken-jump steps and stops only on Taken Jump at I-1, B13-16") {
    val s = new BackplaneSignals()
    s.clockSwitch = CeStepGranularity.Jump
    Ild0303Clock.reset(s)

    // First instruction: Direct Add (not a jump -> should NOT stop at I-1, B13-16!)
    val addWord = Encoder.encode(Instruction.RegisterArith(ArithOp.Add, RegId(1), RegId(2)))
    // Second instruction: Jump on Equal when eq = false (untaken jump -> should NOT stop at I-1, B13-16!)
    val untakenJmpWord = Encoder.encode(Instruction.Branch(Condition.Equal, link = false, Address(0x100)))
    // Third instruction: Unconditional Jump (taken jump -> MUST stop at I-1, B13-16, CEN BT & -P TIME!)
    val takenJmpWord = Encoder.encode(Instruction.Branch(Condition.Unconditional, link = false, Address(0x200)))

    val program = Vector(addWord, untakenJmpWord, takenJmpWord)
    var currentStepIdx = 0
    s.loadOpCodeFromWord(program(0))
    Ild0303Clock.evaluateCombinational(s)

    var prevInsnT = s.insnTime
    var pulses = 0
    while (Ild0303Clock.stepSingleShot(s) && pulses < 2000) {
      if (s.insnTime != prevInsnT) {
        if (s.insnTime == 0) {
          currentStepIdx += 1
        } else if (s.insnTime == 1 && currentStepIdx < program.length) {
          s.loadOpCodeFromWord(program(currentStepIdx))
          Ild0303Clock.evaluateCombinational(s)
        }
        prevInsnT = s.insnTime
      }
      pulses += 1
    }

    // Stopped at the 3rd instruction (index 2 = takenJmpWord) at I-1, B13-16, CEN BT & -P TIME!
    assert(s.stpClk1)
    assertEquals(currentStepIdx, 2)
    assertEquals(s.insnTime, 1)
    assert(s.b13_16 && s.cenBt && !s.pTime && s.jmp && s.tkn)
  }

  test("AA071 Clock Control Switch: PRG STP mode stops at I-1, B13-16, CEN BT & -P TIME of each instruction") {
    val s = new BackplaneSignals()
    s.clockSwitch = CeStepGranularity.ProgramStep
    Ild0303Clock.reset(s)

    // Load a Direct Add instruction (I-0 -> I-1 -> I-2 -> I-5 -> I-6 -> I-9 -> I-0 -> I-1)
    s.loadOpCodeFromWord(Encoder.encode(Instruction.RegisterArith(ArithOp.Add, RegId(1), RegId(2))))

    // Run until clock stops at first Program Step stop point (I-1, B13-16, CEN BT & -P TIME)
    var pulses = 0
    while (Ild0303Clock.stepSingleShot(s) && pulses < 500) {
      pulses += 1
    }
    assert(s.stpClk1)
    assertEquals(s.insnTime, 1)
    assert(s.b13_16 && s.cenBt && !s.pTime)

    // Press STEP to execute the rest of the Add instruction (I-2, I-5, I-6, I-9) and fetch the next (I-0 -> I-1, B13-16)
    s.stepButtonPressed = true
    val visitedInsnTimes = scala.collection.mutable.ArrayBuffer[Int]()
    var prevI = s.insnTime
    pulses = 0
    while (Ild0303Clock.stepSingleShot(s) && pulses < 500) {
      if (s.insnTime != prevI) {
        visitedInsnTimes += s.insnTime
        prevI = s.insnTime
      }
      pulses += 1
    }
    assert(s.stpClk1)
    assertEquals(visitedInsnTimes.toVector, Vector(2, 5, 6, 9, 0, 1))
    assertEquals(s.insnTime, 1)
    assert(s.b13_16 && s.cenBt && !s.pTime)
  }
}
