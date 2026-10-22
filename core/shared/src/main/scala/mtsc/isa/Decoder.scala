package mtsc.isa

import mtsc.types.*

/**
 * Binary decoder for IBM MT/SC 16-bit instructions.
 */
object Decoder {

  def decode(word: Word): Either[String, Instruction] = {
    val raw = word.unsigned
    val top2 = (raw >> 14) & 0x3

    top2 match {
      case 0 => // 00: Branch, Bit Control (PROG. CTRL.), Input, or Output
        val op34 = (raw >> 12) & 0x3
        val op5  = (raw >> 11) & 0x1f
        val op7  = raw & 1

        if (op5 == 2 && op7 == 0) {
          // 00010 ... 0: Output (Figure 10: M=bit 10, Dev=bits 9..7, Ind=bit 6 (Op6), Reg=bits 5..1 (Y6..Y2), Op7=0)
          val m = ((raw >> 10) & 1) == 1
          val devBits = (raw >> 7) & 0x7
          val device = if (devBits == 0) Device.Translator else Device.Lights
          val ind = ((raw >> 6) & 1) == 1
          val reg = RegId((raw >> 1) & 0x1f)
          Right(Instruction.Output(device, reg, ind, m))
        } else if (op5 == 3 && op7 == 0) {
          // 00011 ... 0: Input (Figure 10 & CE Flange: Dev=bits 10..7, Op6=0, Reg=bits 5..1 (Y6..Y2), Op7=0)
          // Flange bits: bit 8 = m (1=LeftTape, 0=RightTape), bit 7 = p (1=PaperTape, 0=MagTape); 0111 = Keyboard
          val devBits = (raw >> 7) & 0xf
          val device = devBits match {
            case 0          => Device.RightTape
            case 2          => Device.LeftTape
            case 1 | 3      => Device.PaperTape
            case 7          => Device.Keyboard
            case d if (d & 1) != 0 => Device.PaperTape
            case d if (d & 2) != 0 => Device.LeftTape
            case _          => Device.RightTape
          }
          val reg = RegId((raw >> 1) & 0x1f)
          Right(Instruction.Input(device, reg))
        } else if (op34 == 3 && op7 == 1) {
          // 0011 ... 1: Bit Control / PROG. CTRL. (Figure 3 & Figure 10: Op1..4=0011, Op7=1)
          val bit11 = (raw >> 11) & 1
          val bit6  = (raw >> 6) & 1
          val op = (bit11, bit6) match {
            case (0, 0) => BitOp.Reset
            case (0, 1) => BitOp.Preserve
            case (1, 0) => BitOp.Set
            case (1, 1) => BitOp.Test
            case _      => BitOp.Test
          }
          val bit = (raw >> 7) & 0xf
          val reg = RegId((raw >> 1) & 0x1f)
          Right(Instruction.BitControl(op, bit, reg))
        } else {
          // Branch (Figure 8: Bits 15..14=00, Bits 13..12=Op3..4, Bit 11=Op5 Link, Bits 10..1=Address, Bit 0=Op7)
          val link = ((raw >> 11) & 1) == 1
          val cond = (op34, op7) match {
            case (0, _) => Condition.Unconditional
            case (1, 1) => Condition.Hi
            case (2, 0) => Condition.Lo
            case (2, 1) => Condition.Equal
            case (3, 0) => Condition.NotEqual
            case _      => Condition.Unconditional
          }
          val target = Address(raw & 0x7fe)
          Right(Instruction.Branch(cond, link, target))
        }

      case 1 => // 01: Register Direct Arithmetic or Byte Indirect (Figure 3 & Figure 10: ARITH. OPER.)
        val pReg = RegId((raw >> 7) & 0x1f)
        val qInd = ((raw >> 6) & 1) == 1
        val qReg = RegId((raw >> 1) & 0x1f)
        val pInd = (raw & 1) == 1

        if (!pInd && !qInd) {
          // Register Direct Arithmetic (both Op6 and Op7 are 0)
          val opBits = (raw >> 12) & 0x3
          val op = opBits match {
            case 0 => ArithOp.Subtract
            case 1 => ArithOp.Compare
            case 2 => ArithOp.Add
            case 3 => ArithOp.Transfer
          }
          Right(Instruction.RegisterArith(op, pReg, qReg))
        } else {
          // Byte Indirect (either Op6/qInd or Op7/pInd is 1)
          val opBit = (raw >> 13) & 1
          val op = if (opBit == 0) ArithOp.Compare else ArithOp.Transfer
          val bump = ((raw >> 12) & 1) == 1
          Right(Instruction.ByteIndirect(op, pReg, pInd, qReg, qInd, bump))
        }

      case 2 => // 10: Incremental Branch
        val forward = ((raw >> 13) & 1) == 1
        val onBitSet = ((raw >> 11) & 1) == 1
        val bit = (raw >> 7) & 0xf
        val reg = RegId((raw >> 5) & 0x3)
        val steps = ((raw >> 1) & 0xf) + 1
        Right(Instruction.IncrementalBranch(forward, onBitSet, bit, reg, steps))

      case 3 => // 11: Immediate Arithmetic
        val opBits = (raw >> 12) & 0x3
        val op = opBits match {
          case 0 => ArithOp.Subtract
          case 1 => ArithOp.Compare
          case 2 => ArithOp.Add
          case 3 => ArithOp.Transfer
        }
        val reg = RegId(((raw >> 8) & 0xf) * 2) // Even register indexing
        val imm = raw & 0xff
        Right(Instruction.ImmediateArith(op, reg, imm))
    }
  }
}
