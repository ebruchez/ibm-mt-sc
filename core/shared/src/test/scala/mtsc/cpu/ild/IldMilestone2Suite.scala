package mtsc.cpu.ild

import munit.FunSuite
import mtsc.asm.Assembler
import mtsc.cpu.{Ccu, MockIoBus, StepResult}
import mtsc.diag.DiagnosticPrograms
import mtsc.isa.*
import mtsc.memory.CoreMemory
import mtsc.types.*

class IldMilestone2Suite extends FunSuite {

  /**
   * Runs a program in lockstep on both the behavioral [[Ccu]] and the gate-level [[IldCcu]],
   * asserting after every single instruction step that:
   * - `StepResult` matches
   * - `PSAR` and `B-Word` match
   * - `low`, `equal`, `inhibit`, and `multiplex` latches match
   * - All 2,048 bytes of Core Memory (including `R0..R31`) match byte-for-byte
   * - All peripheral outputs on `IoBus` match
   */
  private def assertLockstepProgram(
    src: String,
    maxSteps: Int = 500,
    setup: (CoreMemory, MockIoBus) => Unit = (_, _) => ()
  ): (Ccu, IldCcu, MockIoBus, MockIoBus) = {
    val assembled = Assembler.assemble(src)
    assert(assembled.isRight, s"Assembly failed: $assembled")
    val img = assembled.toOption.get

    val memRef = CoreMemory.fromImage(img)
    val ioRef = new MockIoBus()
    setup(memRef, ioRef)
    val ccuRef = new Ccu(memRef, ioRef)

    val memIld = CoreMemory.fromImage(img)
    val ioIld = new MockIoBus()
    setup(memIld, ioIld)
    val ccuIld = new IldCcu(memIld, ioIld)
    ccuIld.timingTrace.enabled = false

    var stepIdx = 0
    var done = false
    while (!done && stepIdx < maxSteps) {
      val prevPsar = ccuRef.memory.psar
      val resRef = ccuRef.step()
      val resIld = ccuIld.step()
      stepIdx += 1

      assertEquals(resIld, resRef, s"StepResult mismatch at step $stepIdx (PSAR before step: $prevPsar)")
      assertEquals(ccuIld.memory.psar, ccuRef.memory.psar, s"PSAR mismatch at step $stepIdx")
      assertEquals(ccuIld.memory.bWord, Word.Zero, s"B-Word must be cleared after step $stepIdx")
      assertEquals(ccuIld.state.low, ccuRef.state.low, s"LOW latch mismatch at step $stepIdx (insn: ${ccuRef.state.lastInsn})")
      assertEquals(ccuIld.state.equal, ccuRef.state.equal, s"EQUAL latch mismatch at step $stepIdx (insn: ${ccuRef.state.lastInsn})")
      assertEquals(ccuIld.state.inhibit, ccuRef.state.inhibit, s"INHIBIT latch mismatch at step $stepIdx")
      assertEquals(ccuIld.state.multiplex, ccuRef.state.multiplex, s"MULTIPLEX latch mismatch at step $stepIdx")

      // Compare all 2,048 bytes of core storage
      var addr = 0
      while (addr < CoreMemory.Size) {
        val bRef = ccuRef.memory.readByte(Address(addr))
        val bIld = ccuIld.memory.readByte(Address(addr))
        if (bIld != bRef) {
          fail(s"Core memory mismatch at byte $addr on step $stepIdx (${ccuRef.state.lastInsn}): expected $bRef, got $bIld")
        }
        addr += 1
      }

      for (dev <- List(Device.Translator, Device.Lights)) {
        assertEquals(ioIld.getOutput(dev), ioRef.getOutput(dev), s"Output mismatch for $dev at step $stepIdx")
      }

      if (resRef != StepResult.Normal || (!ccuRef.state.multiplex && ccuRef.memory.psar == prevPsar && !ccuRef.state.inhibit)) {
        done = true
      }
    }

    (ccuRef, ccuIld, ioRef, ioIld)
  }

  test("ILD 0306-0307 & CoreMatrix3D: 1-to-1 X/Y Driver and Switch decoding across all 2,048 byte addresses and special words") {
    val mem = CoreMemory.empty()
    val matrix = new CoreMatrix3D(mem)
    val s = new BackplaneSignals()

    // 1. Verify special words: PSAR -> Y DRV 8 (7), A-Word -> Y DRV 7 (6), B-Word -> Y DRV 6 (5)
    s.insnT0 = true
    s.pTime = true
    Ild0306To0307Address.evaluateCombinational(s)
    assert(s.selProgStepAddr && s.selMemSpec)
    assertEquals(s.activeXDriver, 7)
    assertEquals(s.activeXSwitch, 7)
    assertEquals(s.activeYDriver, 7) // Y DRV 8 = PSAR

    s.insnT0 = false
    s.insnT2 = true
    s.insnTCtrB = true
    Ild0306To0307Address.evaluateCombinational(s)
    assert(s.selAWd && s.selMemSpec)
    assertEquals(s.activeYDriver, 6) // Y DRV 7 = A-Word

    s.insnT2 = false
    s.insnTCtrB = false
    s.insnT4 = true
    s.insnTCtrC = true
    Ild0306To0307Address.evaluateCombinational(s)
    assert(s.selBWd && s.selMemSpec)
    assertEquals(s.activeYDriver, 5) // Y DRV 6 = B-Word

    // 2. Verify all 2,048 general memory byte addresses decode bijectively when selMem && selMemUp
    s.insnT4 = false
    s.insnTCtrC = false
    s.insnT1 = true
    s.insnTCtrA = true
    s.pTime = true
    s.btCtrB = false

    for (addr <- 0 until CoreMemory.Size) {
      s.setMalValue(addr)
      Ild0306To0307Address.evaluateCombinational(s)
      assert(s.selectMemory && s.selMemUp)
      val decoded = matrix.decodedByteAddress(s.activeXDriver, s.activeXSwitch, s.activeYDriver, s.activeYSwitch)
      assertEquals(decoded, addr, s"Decode mismatch for byte address $addr")
    }
  }

  test("ILD 0305, 0308, 0309, 0310: 8-bit CMPB borrow propagation through suppressed upper byte (B9-12, B13-16)") {
    val src =
      """
        .org 546
        MOVI    R2, 200          ; Pointer to byte 200 (value 10)
        MOVI    R4, 201          ; Pointer to byte 201 (value 25)
        CMPB    [R2], [R4]       ; 10 - 25 < 0 -> LOW=1, EQ=0
        JMP.LO  IS_LOW
        MOVI    R6, 99
      IS_LOW:
        CMPB    [R4], [R2]       ; 25 - 10 > 0 -> LOW=0, EQ=0 (HI)
        JMP.HI  IS_HIGH
        MOVI    R6, 88
      IS_HIGH:
        CMPB    [R2], [R2]       ; 10 - 10 == 0 -> LOW=0, EQ=1
        JMP.EQ  IS_EQ
        MOVI    R6, 77
      IS_EQ:
        MOVI    R6, 42
      DONE:
        JMP     DONE
      """
    val (_, ccuIld, _, _) = assertLockstepProgram(
      src,
      setup = (mem, _) => {
        mem.writeByte(Address(200), 10)
        mem.writeByte(Address(201), 25)
      }
    )
    assertEquals(ccuIld.memory.readReg(RegId(6)).unsigned, 42)
  }

  test("ILD 0306, 0309, 0310: Hardware Incremental Jump (IJMP) special registers (16, 24, 48, 56) and bit-5 write bypass") {
    // Verify CE Manual p. 55 hardware behavior for Incremental Jump (opcode 10):
    // - Bits 6..5 select one of the 4 special registers at byte addresses 16 (R8), 24 (R12), 48 (R24), 56 (R28)
    //   because Y5 (weight 16) is forced on by (oplCde1 && insnTCtrB), Y4 (weight 8) loads from bit 6, and Y6 (weight 32) loads from bit 5.
    // - Bits 4..1 hold the byte displacement (2..30) added to or subtracted from PSAR (after I-0's +2 bump).
    val mem = CoreMemory.empty()
    val io = new MockIoBus()
    val ild = new IldCcu(mem, io)

    // Set bit 11 of R8 (byte address 16, encoded by bit 6 = 0, bit 5 = 0 -> Y6=0, Y5=1, Y4=0 = 16)
    mem.writeReg(RegId(8), Word(1 << 11))

    // Construct forward IJMP.ON testing bit 11 of special register 00 (R8 = byte 16) with byte offset +6 (3 steps forward -> 546 + 2 + 6 = 554)
    val ijmpForward = Word((2 << 14) | (1 << 13) | (1 << 11) | (11 << 7) | (0 << 5) | (3 << 1))
    mem.writeWord(Address(546), ijmpForward)

    assertEquals(ild.step(), StepResult.Normal)
    assert(ild.state.equal && !ild.state.low, "Sampled bit 11 is 1 -> EQ=1, LOW=0")
    assertEquals(ild.memory.psar, Address(554), "PSAR should jump to 546 + 2 + 6 = 554")
    // Verify R8 was preserved unchanged
    assertEquals(ild.memory.readReg(RegId(8)), Word(1 << 11))
  }

  test("Lockstep Co-Simulation: Immediate & Register Direct Arithmetic, Bit Control, JMPL, and Byte Indirect") {
    val src =
      """
        .org 546
        MOVI    R2, 10
        ADDI    R2, 15
        CMPI    R2, 25
        JMP.EQ  STEP2
        MOVI    R2, 0
      STEP2:
        MOVI    R4, 42
        SUBI    R4, 50           ; 42 - 50 = -8 (LO=1)
        JMP.LO  STEP3
        MOVI    R2, 0
      STEP3:
        MOVI    R6, 100
        ADD     R6, R2           ; 100 + 25 = 125
        SUB     R6, R2           ; 125 - 25 = 100
        CMP     R6, R2           ; 100 - 25 > 0 (HI=1)
        SBIT    R6, 12           ; Set bit 12
        TBIT    R6, 12           ; Test bit 12 (EQ=1)
        RBIT    R6, 12           ; Reset bit 12
        TBIT    R6, 12           ; Test bit 12 (LO=1)
        MOVI    R8, 200
        MOVB    [R8]++, R6       ; [200] = 100, R8 = 201
        MOVB    [R8]++, R2       ; [201] = 25,  R8 = 202
        MOVI    R10, 200
        MOVB    R12, [R10]++     ; R12 = 100, R10 = 201
        MOVB    R14, [R10]++     ; R14 = 25,  R10 = 202
        JMPL    SUBR
        ADDI    R12, 1
      DONE:
        JMP     DONE

        .org 620
      SUBR:
        .word   0x0000
        ADD     R12, R14         ; R12 = 100 + 25 = 125
        JMP     SUBR
      """
    val (_, ccuIld, _, _) = assertLockstepProgram(src)
    assertEquals(ccuIld.memory.readReg(RegId(12)).unsigned, 126)
    assertEquals(ccuIld.memory.readByte(Address(200)), 100)
    assertEquals(ccuIld.memory.readByte(Address(201)), 25)
  }

  test("Lockstep Co-Simulation: Tape Input, Indirect Output, Pipeline Stall (Inhibit), and Multiplex Interrupt") {
    val src =
      """
        .org 546
        MOVI    R8, 200
      READ_LOOP:
        IN      TAPE_R, R4
        MOVB    [R8]++, R4
        CMPI    R8, 204
        JMP.NE  READ_LOOP

        ; Set R0 = 200 and trigger Multiplex Output
        MOVI    R0, 200
        OUTM    [R0], TRANSLATOR

        ; Foreground loop while Multiplex Interrupts drain [R0] ("HI!" + 0xFF)
        MOVI    R2, 0
      FG_LOOP:
        ADDI    R2, 1
        CMPI    R2, 6
        JMP.NE  FG_LOOP
      DONE:
        JMP     DONE
      """
    val (ccuRef, ccuIld, ioRef, ioIld) = assertLockstepProgram(
      src,
      setup = (_, io) => {
        io.queueInput(Device.RightTape, 'H'.toInt, 'I'.toInt, '!'.toInt, 0xff)
      }
    )

    assertEquals(ioIld.getOutput(Device.Translator), List('H'.toInt, 'I'.toInt, '!'.toInt))
    assertEquals(ioIld.getOutput(Device.Translator), ioRef.getOutput(Device.Translator))
    assert(!ccuIld.state.multiplex, "Multiplex latch must reset on 0xFF halt byte")
    assertEquals(ccuIld.memory.readReg(RegId(0)).unsigned, 204)
    assertEquals(ccuIld.memory.readReg(RegId(2)).unsigned, 6)
  }

  test("Lockstep Co-Simulation: I/O pipeline stall (INH) when Input device is busy") {
    val src =
      """
        .org 546
        IN      TAPE_R, R4
        MOVI    R6, 77
      DONE:
        JMP     DONE
      """
    val img = Assembler.assemble(src).toOption.get
    val memRef = CoreMemory.fromImage(img)
    val ioRef = new MockIoBus()
    val ccuRef = new Ccu(memRef, ioRef)

    val memIld = CoreMemory.fromImage(img)
    val ioIld = new MockIoBus()
    val ccuIld = new IldCcu(memIld, ioIld)

    // Step 1: Tape is empty -> both stall at 546 with inhibit = true
    assertEquals(ccuRef.step(), StepResult.Stalled)
    assertEquals(ccuIld.step(), StepResult.Stalled)
    assert(ccuIld.state.inhibit)
    assertEquals(ccuIld.memory.psar, Address(546))

    // Queue byte 55 on both buses and step again
    ioRef.queueInput(Device.RightTape, 55)
    ioIld.queueInput(Device.RightTape, 55)

    assertEquals(ccuRef.step(), StepResult.Normal)
    assertEquals(ccuIld.step(), StepResult.Normal)
    assert(!ccuIld.state.inhibit)
    assertEquals(ccuIld.memory.readReg(RegId(4)).unsigned, 55)
    assertEquals(ccuIld.memory.psar, Address(548))

    assertEquals(ccuRef.step(), StepResult.Normal)
    assertEquals(ccuIld.step(), StepResult.Normal)
    assertEquals(ccuIld.memory.readReg(RegId(6)).unsigned, 77)
  }

  test("Lockstep Co-Simulation: DiagnosticPrograms (ALU, Nested Subroutines, Tape Bootstrap, Escapement & Justification) match step-by-step") {
    import mtsc.diag.EscapementRoutine

    // 1. ALU Diagnostic & Nested Subroutines Diagnostic
    for (prog <- List(DiagnosticPrograms.AluDiagnostic, DiagnosticPrograms.NestedSubroutinesDiagnostic)) {
      assertLockstepProgram(prog.source, maxSteps = 500)
    }

    // 2. Tape Bootstrap Loader
    val (_, ccuIldBoot, _, _) = assertLockstepProgram(
      DiagnosticPrograms.TapeBootstrapLoader.source,
      maxSteps = 500,
      setup = (mem, io) => {
        mem.writeReg(RegId(8), Word(200))
        io.queueInput(Device.RightTape, 'M'.toInt, 'T'.toInt, 'S'.toInt, 0xff)
      }
    )
    assertEquals(ccuIldBoot.memory.readByte(Address(200)), 'M'.toInt)
    assertEquals(ccuIldBoot.memory.readByte(Address(201)), 'T'.toInt)
    assertEquals(ccuIldBoot.memory.readByte(Address(202)), 'S'.toInt)
    assertEquals(ccuIldBoot.memory.readByte(Address(203)), 0xff)

    // 3. Escapement and Justification Demo ("The logic in CCU")
    val (_, ccuIldEsc, _, _) = assertLockstepProgram(
      DiagnosticPrograms.EscapementAndJustifyDemo.source,
      maxSteps = 1500,
      setup = (mem, _) => {
        EscapementRoutine.initializeTable(mem)
        val text = "The logic in CCU"
        var i = 0
        while (i < text.length) {
          mem.writeByte(Address(316 + i), text.charAt(i).toInt)
          i += 1
        }
        mem.writeByte(Address(316 + text.length), 0)
      }
    )
    assertEquals(ccuIldEsc.memory.readReg(RegId(20)).unsigned, 83)
    assertEquals(ccuIldEsc.memory.readReg(RegId(22)).unsigned, 3)
    assertEquals(ccuIldEsc.memory.readReg(RegId(12)).unsigned, 67)
    assertEquals(ccuIldEsc.memory.readReg(RegId(14)).unsigned, 22)
    assertEquals(ccuIldEsc.memory.readReg(RegId(16)).unsigned, 1)
    assertEquals(ccuIldEsc.memory.readReg(RegId(28)).unsigned, 1)
  }
}
