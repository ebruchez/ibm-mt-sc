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
      case 0 => // 00: Branch, Input, or Output
        val op5 = (raw >> 11) & 0x1f
        if (op5 == 2) {
          // 00010: Output
          val m = ((raw >> 10) & 1) == 1
          val devBits = (raw >> 8) & 0x3
          val device = if (devBits == 0) Device.Translator else Device.Lights
          val ind = ((raw >> 7) & 1) == 1
          val reg = RegId((raw >> 2) & 0x1f)
          Right(Instruction.Output(device, reg, ind, m))
        } else if (op5 == 3) {
          // 00011: Input
          val devBits = (raw >> 8) & 0x7
          val device = devBits match {
            case 0 => Device.RightTape
            case 1 => Device.LeftTape
            case 2 => Device.PaperTape
            case 3 => Device.Keyboard
            case _ => Device.RightTape
          }
          val reg = RegId((raw >> 2) & 0x1f)
          Right(Instruction.Input(device, reg))
        } else {
          // Branch
          val condBits = (raw >> 11) & 0x7
          val cond = condBits match {
            case 0 | 1 => Condition.Unconditional
            case 4     => Condition.Lo
            case 5     => Condition.Equal
            case 6     => Condition.Hi
            case 7     => Condition.NotEqual
            case _     => Condition.Unconditional
          }
          val link = ((raw >> 10) & 1) == 1
          val targetWord = raw & 0x3ff
          val target = Address(targetWord << 1)
          Right(Instruction.Branch(cond, link, target))
        }

      case 1 => // 01: Register Arith, Byte Indirect, or Bit Control
        val bit13 = (raw >> 13) & 1
        if (bit13 == 1) {
          // 011: Bit Control
          val opBits = (raw >> 11) & 0x3
          val op = opBits match {
            case 0 => BitOp.Reset
            case 1 => BitOp.Set
            case 2 => BitOp.Preserve
            case 3 => BitOp.Test
          }
          val bit = (raw >> 7) & 0xf
          val reg = RegId((raw >> 2) & 0x1f)
          Right(Instruction.BitControl(op, bit, reg))
        } else {
          val bit12 = (raw >> 12) & 1
          if (bit12 == 0) {
            // 0100: Register Direct Arithmetic
            val opBits = (raw >> 10) & 0x3
            val op = opBits match {
              case 0 => ArithOp.Subtract
              case 1 => ArithOp.Compare
              case 2 => ArithOp.Add
              case 3 => ArithOp.Transfer
            }
            val pReg = RegId((raw >> 5) & 0x1f)
            val qReg = RegId(raw & 0x1f)
            Right(Instruction.RegisterArith(op, pReg, qReg))
          } else {
            // 0101: Byte Indirect
            val opBit = (raw >> 11) & 1
            val op = if (opBit == 0) ArithOp.Compare else ArithOp.Transfer
            val bump = ((raw >> 10) & 1) == 1
            val pInd = ((raw >> 9) & 1) == 1
            val pReg = RegId((raw >> 5) & 0xf)
            val qInd = ((raw >> 4) & 1) == 1
            val qReg = RegId(raw & 0xf)
            Right(Instruction.ByteIndirect(op, pReg, pInd, qReg, qInd, bump))
          }
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
