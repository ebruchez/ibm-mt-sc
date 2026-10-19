package mtsc.isa

import mtsc.types.*

class IsaEncodingSuite extends munit.FunSuite {

  test("Branch: round-trip encode and decode") {
    val branches = List(
      Instruction.Branch(Condition.Unconditional, link = false, Address(546)),
      Instruction.Branch(Condition.Hi, link = false, Address(800)),
      Instruction.Branch(Condition.Lo, link = false, Address(1024)),
      Instruction.Branch(Condition.Equal, link = false, Address(0)),
      Instruction.Branch(Condition.NotEqual, link = false, Address(2046)),
      Instruction.Branch(Condition.Unconditional, link = true, Address(820)) // Jump with Link
    )

    for (insn <- branches) {
      val encoded = Encoder.encode(insn)
      val decoded = Decoder.decode(encoded)
      assertEquals(decoded, Right(insn), s"Failed for $insn (encoded: $encoded)")
    }
  }

  test("Incremental Branch: round-trip encode and decode") {
    val incrBranches = List(
      Instruction.IncrementalBranch(forward = true, onBitSet = true, bit = 0, reg = RegId(0), incrementSteps = 1),
      Instruction.IncrementalBranch(forward = true, onBitSet = false, bit = 7, reg = RegId(1), incrementSteps = 16),
      Instruction.IncrementalBranch(forward = false, onBitSet = true, bit = 15, reg = RegId(2), incrementSteps = 14),
      Instruction.IncrementalBranch(forward = false, onBitSet = false, bit = 3, reg = RegId(3), incrementSteps = 8)
    )

    for (insn <- incrBranches) {
      val encoded = Encoder.encode(insn)
      val decoded = Decoder.decode(encoded)
      assertEquals(decoded, Right(insn), s"Failed for $insn")
    }
  }

  test("Bit Control: round-trip encode and decode") {
    for {
      op <- List(BitOp.Reset, BitOp.Set, BitOp.Preserve, BitOp.Test)
      bit <- List(0, 5, 11, 15)
      reg <- List(RegId(0), RegId(12), RegId(31))
    } {
      val insn = Instruction.BitControl(op, bit, reg)
      val encoded = Encoder.encode(insn)
      val decoded = Decoder.decode(encoded)
      assertEquals(decoded, Right(insn), s"Failed for $insn")
    }
  }

  test("Immediate Arithmetic: round-trip encode and decode") {
    for {
      op <- List(ArithOp.Subtract, ArithOp.Compare, ArithOp.Add, ArithOp.Transfer)
      reg <- List(RegId(0), RegId(4), RegId(8), RegId(30)) // Even registers
      imm <- List(0, 1, 64, 132, 255)
    } {
      val insn = Instruction.ImmediateArith(op, reg, imm)
      val encoded = Encoder.encode(insn)
      val decoded = Decoder.decode(encoded)
      assertEquals(decoded, Right(insn), s"Failed for $insn")
    }
  }

  test("Register Direct Arithmetic: round-trip encode and decode") {
    for {
      op <- List(ArithOp.Subtract, ArithOp.Compare, ArithOp.Add, ArithOp.Transfer)
      pReg <- List(RegId(0), RegId(7), RegId(31))
      qReg <- List(RegId(1), RegId(14), RegId(25))
    } {
      val insn = Instruction.RegisterArith(op, pReg, qReg)
      val encoded = Encoder.encode(insn)
      val decoded = Decoder.decode(encoded)
      assertEquals(decoded, Right(insn), s"Failed for $insn")
    }
  }

  test("Byte Indirect Arithmetic: round-trip encode and decode") {
    val indirects = List(
      Instruction.ByteIndirect(ArithOp.Transfer, RegId(4), pIndirect = false, RegId(8), qIndirect = true, bump = false),
      Instruction.ByteIndirect(ArithOp.Transfer, RegId(8), pIndirect = true, RegId(4), qIndirect = false, bump = true),
      Instruction.ByteIndirect(ArithOp.Compare, RegId(10), pIndirect = true, RegId(12), qIndirect = true, bump = true)
    )

    for (insn <- indirects) {
      val encoded = Encoder.encode(insn)
      val decoded = Decoder.decode(encoded)
      assertEquals(decoded, Right(insn), s"Failed for $insn")
    }
  }

  test("Input / Output: round-trip encode and decode") {
    val ioInsns = List(
      Instruction.Input(Device.RightTape, RegId(4)),
      Instruction.Input(Device.Keyboard, RegId(2)),
      Instruction.Output(Device.Translator, RegId(0), indirect = false, multiplex = false),
      Instruction.Output(Device.Lights, RegId(5), indirect = false, multiplex = false),
      Instruction.Output(Device.Translator, RegId(0), indirect = true, multiplex = true) // Multiplex mode!
    )

    for (insn <- ioInsns) {
      val encoded = Encoder.encode(insn)
      val decoded = Decoder.decode(encoded)
      assertEquals(decoded, Right(insn), s"Failed for $insn")
    }
  }
}
