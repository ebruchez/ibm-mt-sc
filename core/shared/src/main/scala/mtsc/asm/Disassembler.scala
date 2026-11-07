package mtsc.asm

import mtsc.types.*
import mtsc.isa.*
import mtsc.memory.CoreImage

/**
 * Disassembler for IBM MT/SC 16-bit instructions.
 */
object Disassembler {

  def disassemble(word: Word, pc: Address = Address(0)): String = {
    Decoder.decode(word) match {
      case Left(_) =>
        s".WORD   0x${Fmt.hex4(word.unsigned)}"

      case Right(insn) =>
        formatInstruction(insn, pc)
    }
  }

  def disassembleImage(image: CoreImage, start: Address, lengthWords: Int): List[(Address, Word, String)] = {
    val result = List.newBuilder[(Address, Word, String)]
    var addr = start
    for (_ <- 0 until lengthWords) {
      if (addr.unsigned < CoreImage.Size - 1) {
        val word = image.readWord(addr)
        val text = disassemble(word, addr)
        result += ((addr, word, text))
        addr = addr + 2
      }
    }
    result.result()
  }

  private def formatInstruction(insn: Instruction, pc: Address): String = insn match {
    case Instruction.Branch(cond, link, target) =>
      val mnem = if (link) "JMPL" else cond match {
        case Condition.Unconditional => "JMP"
        case Condition.Hi            => "JMP.HI"
        case Condition.Lo            => "JMP.LO"
        case Condition.Equal         => "JMP.EQ"
        case Condition.NotEqual      => "JMP.NE"
      }
      s"${Fmt.padRight(mnem, 8)} 0x${Fmt.hex3(target.unsigned)}"

    case Instruction.IncrementalBranch(forward, onBitSet, bit, reg, steps) =>
      val mnem = if (onBitSet) "IJMP.ON" else "IJMP.OFF"
      val sign = if (forward) "+" else "-"
      val targetOffset = if (forward) steps * 2 else -steps * 2
      val targetAddr = (pc.unsigned + 2 + targetOffset) & 0x7ff
      s"${Fmt.padRight(mnem, 8)} $reg, $bit, $sign$steps (0x${Fmt.hex3(targetAddr)})"

    case Instruction.BitControl(op, bit, reg) =>
      val mnem = op match {
        case BitOp.Reset    => "RBIT"
        case BitOp.Set      => "SBIT"
        case BitOp.Preserve => "PBIT"
        case BitOp.Test     => "TBIT"
      }
      s"${Fmt.padRight(mnem, 8)} $reg, $bit"

    case Instruction.ImmediateArith(op, reg, value) =>
      val mnem = op match {
        case ArithOp.Subtract => "SUBI"
        case ArithOp.Compare  => "CMPI"
        case ArithOp.Add      => "ADDI"
        case ArithOp.Transfer => "MOVI"
      }
      s"${Fmt.padRight(mnem, 8)} $reg, $value"

    case Instruction.RegisterArith(op, pReg, qReg) =>
      val mnem = op match {
        case ArithOp.Subtract => "SUB"
        case ArithOp.Compare  => "CMP"
        case ArithOp.Add      => "ADD"
        case ArithOp.Transfer => "MOV"
      }
      s"${Fmt.padRight(mnem, 8)} $pReg, $qReg"

    case Instruction.ByteIndirect(op, pReg, pInd, qReg, qInd, bump) =>
      val mnem = if (op == ArithOp.Compare) "CMPB" else "MOVB"
      val pStr = if (pInd) s"[$pReg]${if (bump) "++" else ""}" else pReg.toString
      val qStr = if (qInd) s"[$qReg]${if (bump && !pInd) "++" else ""}" else qReg.toString
      s"${Fmt.padRight(mnem, 8)} $pStr, $qStr"

    case Instruction.Input(device, targetReg) =>
      val devStr = device match {
        case Device.RightTape => "TAPE_R"
        case Device.LeftTape  => "TAPE_L"
        case Device.PaperTape => "PAPER"
        case Device.Keyboard  => "KB"
        case other            => other.toString
      }
      s"IN      $devStr, $targetReg"

    case Instruction.Output(device, sourceReg, indirect, multiplex) =>
      val mnem = if (multiplex) "OUTM" else "OUT"
      val devStr = if (device == Device.Translator) "TSLTR" else "LTS"
      val srcStr = if (indirect) s"[$sourceReg]" else sourceReg.toString
      s"${Fmt.padRight(mnem, 8)} $devStr, $srcStr"
  }
}
