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
        f".WORD   0x${word.unsigned}%04X"

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
      f"$mnem%-8s 0x${target.unsigned}%03X"

    case Instruction.IncrementalBranch(forward, onBitSet, bit, reg, steps) =>
      val mnem = if (onBitSet) "IJMP.ON" else "IJMP.OFF"
      val sign = if (forward) "+" else "-"
      val targetOffset = if (forward) steps * 2 else -steps * 2
      val targetAddr = (pc.unsigned + 2 + targetOffset) & 0x7ff
      f"$mnem%-8s $reg, $bit, $sign$steps (0x$targetAddr%03X)"

    case Instruction.BitControl(op, bit, reg) =>
      val mnem = op match {
        case BitOp.Reset    => "RBIT"
        case BitOp.Set      => "SBIT"
        case BitOp.Preserve => "PBIT"
        case BitOp.Test     => "TBIT"
      }
      f"$mnem%-8s $reg, $bit"

    case Instruction.ImmediateArith(op, reg, value) =>
      val mnem = op match {
        case ArithOp.Subtract => "SUBI"
        case ArithOp.Compare  => "CMPI"
        case ArithOp.Add      => "ADDI"
        case ArithOp.Transfer => "MOVI"
      }
      f"$mnem%-8s $reg, $value"

    case Instruction.RegisterArith(op, pReg, qReg) =>
      val mnem = op match {
        case ArithOp.Subtract => "SUB"
        case ArithOp.Compare  => "CMP"
        case ArithOp.Add      => "ADD"
        case ArithOp.Transfer => "MOV"
      }
      f"$mnem%-8s $pReg, $qReg"

    case Instruction.ByteIndirect(op, pReg, pInd, qReg, qInd, bump) =>
      val mnem = if (op == ArithOp.Compare) "CMPB" else "MOVB"
      val pStr = if (pInd) s"[$pReg]${if (bump) "++" else ""}" else pReg.toString
      val qStr = if (qInd) s"[$qReg]${if (bump && !pInd) "++" else ""}" else qReg.toString
      f"$mnem%-8s $pStr, $qStr"

    case Instruction.Input(device, targetReg) =>
      val devStr = device match {
        case Device.RightTape => "TAPE_R"
        case Device.LeftTape  => "TAPE_L"
        case Device.PaperTape => "PAPER"
        case Device.Keyboard  => "KB"
        case other            => other.toString
      }
      f"IN      $devStr, $targetReg"

    case Instruction.Output(device, sourceReg, indirect, multiplex) =>
      val mnem = if (multiplex) "OUTM" else "OUT"
      val devStr = if (device == Device.Translator) "TSLTR" else "LTS"
      val srcStr = if (indirect) s"[$sourceReg]" else sourceReg.toString
      f"$mnem%-8s $devStr, $srcStr"
  }
}
