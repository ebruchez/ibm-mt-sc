package mtsc.isa

import mtsc.types.*

/**
 * Algebraic Data Type representing all instructions supported by the MT/SC CCU.
 */
sealed trait Instruction

object Instruction {

  /**
   * Full Branch (Jump / Jump with Link).
   * @param cond Jump condition (Unconditional, Hi, Lo, Equal, NotEqual)
   * @param link If true, installs return jump at target address and jumps to target + 2
   * @param target Target word address (0 to 2046, must be word-aligned)
   */
  final case class Branch(
    cond: Condition,
    link: Boolean,
    target: Address
  ) extends Instruction {
    require(target.isWordAligned, s"Branch target address must be even, got $target")
  }

  /**
   * Incremental Branch.
   * Performs relative branch based on testing a single bit in a register.
   * @param forward If true, jumps forward (+1 to +16 steps); if false, backward (-1 to -14 steps)
   * @param onBitSet If true, branch when sampled bit is 1; if false, branch when bit is 0
   * @param bit Bit index within the register (0 to 15)
   * @param reg Target register (typically Reg 0, 1, 2, or 3)
   * @param incrementSteps Number of steps (1 to 16 forward, 1 to 14 backward)
   */
  final case class IncrementalBranch(
    forward: Boolean,
    onBitSet: Boolean,
    bit: Int,
    reg: RegId,
    incrementSteps: Int
  ) extends Instruction {
    require(bit >= 0 && bit < 16, s"Bit index must be 0..15, got $bit")
    require(incrementSteps >= 1 && incrementSteps <= 16, s"Increment steps must be 1..16, got $incrementSteps")
  }

  /**
   * Program Control (Bit manipulation).
   * Tests, sets, resets, or preserves a bit in any of the 32 working registers.
   * Also updates condition latches based on sampled bit.
   */
  final case class BitControl(
    op: BitOp,
    bit: Int,
    reg: RegId
  ) extends Instruction {
    require(bit >= 0 && bit < 16, s"Bit index must be 0..15, got $bit")
  }

  /**
   * Immediate Arithmetic.
   * SCAT operation between an 8-bit literal and a working register.
   * @param op SCAT operation (Subtract, Compare, Add, Transfer)
   * @param reg Target working register (0 to 31)
   * @param value 8-bit unsigned immediate value (0 to 255)
   */
  final case class ImmediateArith(
    op: ArithOp,
    reg: RegId,
    value: Int
  ) extends Instruction {
    require(value >= 0 && value <= 255, s"Immediate value must be 0..255, got $value")
  }

  /**
   * Register Direct Arithmetic.
   * SCAT operation between two working registers.
   * Result is stored in pReg (unless Compare, which preserves pReg and only sets condition latches).
   * qReg is always preserved.
   */
  final case class RegisterArith(
    op: ArithOp,
    pReg: RegId,
    qReg: RegId
  ) extends Instruction

  /**
   * Byte Indirect Arithmetic.
   * Transfers or compares bytes in core memory using registers as pointers.
   * @param op Compare or Transfer
   * @param pReg Pointer register for P operand
   * @param pIndirect If true, operand is memory byte pointed by pReg; if false, pReg itself
   * @param qReg Pointer register for Q operand
   * @param qIndirect If true, operand is memory byte pointed by qReg; if false, qReg itself
   * @param bump If true, automatically increments indirect pointer(s) by +1 upon access
   */
  final case class ByteIndirect(
    op: ArithOp,
    pReg: RegId,
    pIndirect: Boolean,
    qReg: RegId,
    qIndirect: Boolean,
    bump: Boolean
  ) extends Instruction {
    require(pIndirect || qIndirect, "At least one operand must be indirect in ByteIndirect")
  }

  /**
   * Peripheral Input.
   * Reads one character from an input device into a working register.
   */
  final case class Input(
    device: Device,
    targetReg: RegId
  ) extends Instruction

  /**
   * Peripheral Output.
   * Transmits one character from memory or register to an output device.
   * @param device Output device (Translator or Display Lights)
   * @param sourceReg Source register (or pointer register if indirect)
   * @param indirect If true, outputs byte pointed to by sourceReg
   * @param multiplex If true, enables background Multiplex printing
   */
  final case class Output(
    device: Device,
    sourceReg: RegId,
    indirect: Boolean,
    multiplex: Boolean
  ) extends Instruction
}
