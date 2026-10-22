package mtsc.isa

import mtsc.types.*

/**
 * Binary encoder for IBM MT/SC 16-bit instructions.
 *
 * Bit Layout (15 down to 0, matching hardware bits 16 down to 1 / Figure 10 of 1967 CE Manual):
 * - Branch: [00] [Op3..4: 2] [Link: 1] [Address (bits 10..1): 10] [Op7: 1]
 * - Incremental Branch: [10] [F: 1] [0] [B: 1] [Bit: 4] [Reg: 2] [Steps: 4] [0]
 * - Bit Control (PROG. CTRL.): [0011] [Op5: 1] [Bit: 4] [Op6: 1] [Reg: 5] [1]
 * - Immediate Arithmetic: [11] [Op: 2] [Reg/2: 4] [Imm: 8]
 * - Register Arithmetic: [01] [Op: 2] [P-Reg: 5] [0] [Q-Reg: 5] [0]
 * - Byte Indirect: [01] [Op: 1] [Bump: 1] [P-Reg: 5] [Q-Ind: 1] [Q-Reg: 5] [P-Ind: 1]
 * - Input: [00011] [Dev: 4] [0] [Reg: 5] [0]
 * - Output: [00010] [M: 1] [Dev: 3] [Ind: 1] [Reg: 5] [0]
 */
object Encoder {

  def encode(instruction: Instruction): Word = instruction match {
    case Instruction.Branch(cond, link, target) =>
      val (op34, op7) = cond match {
        case Condition.Unconditional => (0, 0)
        case Condition.Hi            => (1, 1)
        case Condition.Lo            => (2, 0)
        case Condition.Equal         => (2, 1)
        case Condition.NotEqual      => (3, 0)
      }
      val linkBit = if (link) 1 else 0
      val addrBits = target.unsigned & 0x7fe
      val raw = (0 << 14) | (op34 << 12) | (linkBit << 11) | addrBits | op7
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
      val (bit11, bit6) = op match {
        case BitOp.Reset    => (0, 0)
        case BitOp.Preserve => (0, 1)
        case BitOp.Set      => (1, 0)
        case BitOp.Test     => (1, 1)
      }
      val raw = (3 << 12) | (bit11 << 11) | ((bit & 0xf) << 7) |
                (bit6 << 6) | ((reg.index & 0x1f) << 1) | 1
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
      val raw = (1 << 14) | (opBits << 12) | ((pReg.index & 0x1f) << 7) |
                ((qReg.index & 0x1f) << 1)
      Word(raw)

    case Instruction.ByteIndirect(op, pReg, pInd, qReg, qInd, bump) =>
      val opBit = if (op == ArithOp.Compare) 0 else 1 // Compare vs Transfer
      val bumpBit = if (bump) 1 else 0
      val pIndBit = if (pInd) 1 else 0
      val qIndBit = if (qInd) 1 else 0
      // Ensure at least one indirect bit is set so it is distinguished from RegisterArith
      val effectivePInd = if (!pInd && !qInd) 1 else pIndBit
      val raw = (1 << 14) | (opBit << 13) | (bumpBit << 12) |
                ((pReg.index & 0x1f) << 7) | (qIndBit << 6) |
                ((qReg.index & 0x1f) << 1) | effectivePInd
      Word(raw)

    case Instruction.Input(device, targetReg) =>
      val devBits = device match {
        case Device.RightTape => 0 // m=0, p=0
        case Device.LeftTape  => 2 // m=1, p=0
        case Device.PaperTape => 1 // m=0, p=1
        case Device.Keyboard  => 7 // 0111 (Control Panel)
        case _                => 0
      }
      val raw = (3 << 11) | (devBits << 7) | ((targetReg.index & 0x1f) << 1)
      Word(raw)

    case Instruction.Output(device, sourceReg, indirect, multiplex) =>
      val mBit = if (multiplex) 1 else 0
      val devBits = device match {
        case Device.Translator => 0
        case Device.Lights     => 7 // 111 (Lights)
        case _                 => 0
      }
      val indBit = if (indirect) 1 else 0
      val raw = (2 << 11) | (mBit << 10) | (devBits << 7) | (indBit << 6) |
                ((sourceReg.index & 0x1f) << 1)
      Word(raw)
  }
}
