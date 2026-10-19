package mtsc.isa

import mtsc.types.*

/**
 * Binary encoder for IBM MT/SC 16-bit instructions.
 *
 * Bit Layout (15 down to 0, matching hardware bits 16 down to 1):
 * - Branch: [00] [Cond: 3] [Link: 1] [Address / 2: 10]
 * - Incremental Branch: [10] [F: 1] [0] [B: 1] [Bit: 4] [Reg: 2] [Steps: 4] [0]
 * - Bit Control: [011] [Op: 2] [Bit: 4] [Reg: 5] [0] [1]
 * - Immediate Arithmetic: [11] [Op: 2] [Reg/2: 4] [Imm: 8]
 * - Register Arithmetic: [0100] [Op: 2] [P-Reg: 5] [Q-Reg: 5]
 * - Byte Indirect: [0101] [Op: 1] [Bump: 1] [P-Ind: 1] [P-Reg: 4] [Q-Ind: 1] [Q-Reg: 4]
 * - Input: [00011] [Dev: 3] [0] [Reg: 5] [00]
 * - Output: [00010] [M: 1] [Dev: 2] [Ind: 1] [Reg: 5] [00]
 */
object Encoder {

  def encode(instruction: Instruction): Word = instruction match {
    case Instruction.Branch(cond, link, target) =>
      val condBits = cond match {
        case Condition.Unconditional => 0 // 000
        case Condition.Lo            => 4 // 100 (Negative)
        case Condition.Equal         => 5 // 101 (Zero)
        case Condition.Hi            => 6 // 110 (Non-negative)
        case Condition.NotEqual      => 7 // 111 (Non-zero)
      }
      val linkBit = if (link) 1 else 0
      val addrBits = (target.unsigned >> 1) & 0x3ff
      val raw = (0 << 14) | (condBits << 11) | (linkBit << 10) | addrBits
      Word(raw)

    case Instruction.IncrementalBranch(forward, onBitSet, bit, reg, steps) =>
      val fBit = if (forward) 1 else 0
      val bBit = if (onBitSet) 1 else 0
      val regBits = reg.index & 0x3 // Subset of registers (0..3)
      val stepBits = (steps - 1) & 0xf
      val raw = (2 << 14) | (fBit << 13) | (0 << 12) | (bBit << 11) |
                ((bit & 0xf) << 7) | (regBits << 5) | (stepBits << 1)
      Word(raw)

    case Instruction.BitControl(op, bit, reg) =>
      val opBits = op match {
        case BitOp.Reset    => 0
        case BitOp.Set      => 1
        case BitOp.Preserve => 2
        case BitOp.Test     => 3
      }
      val raw = (3 << 13) | (opBits << 11) | ((bit & 0xf) << 7) | ((reg.index & 0x1f) << 2) | 1
      Word(raw)

    case Instruction.ImmediateArith(op, reg, value) =>
      val opBits = op match {
        case ArithOp.Subtract => 0
        case ArithOp.Compare  => 1
        case ArithOp.Add      => 2
        case ArithOp.Transfer => 3
      }
      val regBits = (reg.index >> 1) & 0xf // Div by 2 (even register indexing)
      val raw = (3 << 14) | (opBits << 12) | (regBits << 8) | (value & 0xff)
      Word(raw)

    case Instruction.RegisterArith(op, pReg, qReg) =>
      val opBits = op match {
        case ArithOp.Subtract => 0
        case ArithOp.Compare  => 1
        case ArithOp.Add      => 2
        case ArithOp.Transfer => 3
      }
      val raw = (1 << 14) | (0 << 12) | (opBits << 10) | ((pReg.index & 0x1f) << 5) | (qReg.index & 0x1f)
      Word(raw)

    case Instruction.ByteIndirect(op, pReg, pInd, qReg, qInd, bump) =>
      val opBit = if (op == ArithOp.Compare) 0 else 1 // Compare vs Transfer
      val bumpBit = if (bump) 1 else 0
      val pIndBit = if (pInd) 1 else 0
      val qIndBit = if (qInd) 1 else 0
      val raw = (1 << 14) | (1 << 12) | (opBit << 11) | (bumpBit << 10) |
                (pIndBit << 9) | ((pReg.index & 0xf) << 5) |
                (qIndBit << 4) | (qReg.index & 0xf)
      Word(raw)

    case Instruction.Input(device, targetReg) =>
      val devBits = device match {
        case Device.RightTape => 0
        case Device.LeftTape  => 1
        case Device.PaperTape => 2
        case Device.Keyboard  => 3
        case _                => 0
      }
      val raw = (3 << 11) | (devBits << 8) | ((targetReg.index & 0x1f) << 2)
      Word(raw)

    case Instruction.Output(device, sourceReg, indirect, multiplex) =>
      val mBit = if (multiplex) 1 else 0
      val devBits = device match {
        case Device.Translator => 0
        case Device.Lights     => 1
        case _                 => 0
      }
      val indBit = if (indirect) 1 else 0
      val raw = (2 << 11) | (mBit << 10) | (devBits << 8) | (indBit << 7) |
                ((sourceReg.index & 0x1f) << 2)
      Word(raw)
  }
}
