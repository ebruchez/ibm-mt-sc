package mtsc.asm

import mtsc.types.*
import mtsc.isa.*
import mtsc.memory.CoreImage
import mtsc.console.{AnsiColor, AnsiTerminal}

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

  /**
   * Zero-allocation disassembler that decodes `rawWord` and writes the formatted disassembly directly
   * into `terminal` starting at `(x, y)` without allocating `Instruction`, `RegId`, or `String` objects.
   */
  def writeDisassembled(
    terminal: AnsiTerminal,
    x: Int,
    y: Int,
    rawWord: Int,
    pc: Int,
    fg: Option[AnsiColor],
    bold: Boolean
  ): Unit = {
    val raw = rawWord & 0xffff
    val top2 = (raw >> 14) & 0x3
    var col = x

    top2 match {
      case 0 =>
        val op34 = (raw >> 12) & 0x3
        val op5  = (raw >> 11) & 0x1f
        val op7  = raw & 1

        if (op5 == 2 && op7 == 0) {
          // Output
          val m = ((raw >> 10) & 1) == 1
          val devBits = (raw >> 7) & 0x7
          val ind = ((raw >> 6) & 1) == 1
          val regIdx = (raw >> 1) & 0x1f
          val mnem = if (m) "OUTM     " else "OUT      "
          val devStr = if (devBits == 0) "TSLTR, " else "LTS, "
          col = terminal.writeText(col, y, mnem, fg, bold = bold)
          col = terminal.writeText(col, y, devStr, fg, bold = bold)
          if (ind) col = terminal.writeChar(col, y, '[', fg, bold = bold)
          col = terminal.writeRegName(col, y, regIdx, fg, bold = bold)
          if (ind) col = terminal.writeChar(col, y, ']', fg, bold = bold)
        } else if (op5 == 3 && op7 == 0) {
          // Input
          val devBits = (raw >> 7) & 0xf
          val devStr = devBits match {
            case 0                 => "TAPE_R, "
            case 2                 => "TAPE_L, "
            case 1 | 3             => "PAPER, "
            case 7                 => "KB, "
            case d if (d & 1) != 0 => "PAPER, "
            case d if (d & 2) != 0 => "TAPE_L, "
            case _                 => "TAPE_R, "
          }
          val regIdx = (raw >> 1) & 0x1f
          col = terminal.writeText(col, y, "IN      ", fg, bold = bold)
          col = terminal.writeText(col, y, devStr, fg, bold = bold)
          col = terminal.writeRegName(col, y, regIdx, fg, bold = bold)
        } else if (op34 == 3 && op7 == 1) {
          // BitControl
          val bit11 = (raw >> 11) & 1
          val bit6  = (raw >> 6) & 1
          val mnem = (bit11, bit6) match {
            case (0, 0) => "RBIT     "
            case (0, 1) => "PBIT     "
            case (1, 0) => "SBIT     "
            case _      => "TBIT     "
          }
          val bit = (raw >> 7) & 0xf
          val regIdx = (raw >> 1) & 0x1f
          col = terminal.writeText(col, y, mnem, fg, bold = bold)
          col = terminal.writeRegName(col, y, regIdx, fg, bold = bold)
          col = terminal.writeText(col, y, ", ", fg, bold = bold)
          col = terminal.writeDec(col, y, bit, 0, ' ', fg, bold = bold)
        } else {
          // Branch
          val link = ((raw >> 11) & 1) == 1
          val mnem = if (link) "JMPL     " else (op34, op7) match {
            case (0, _) => "JMP      "
            case (1, 1) => "JMP.HI   "
            case (2, 0) => "JMP.LO   "
            case (2, 1) => "JMP.EQ   "
            case (3, 0) => "JMP.NE   "
            case _      => "JMP      "
          }
          val target = raw & 0x7fe
          col = terminal.writeText(col, y, mnem, fg, bold = bold)
          col = terminal.writeText(col, y, "0x", fg, bold = bold)
          col = terminal.writeHex3(col, y, target, fg, bold = bold)
        }

      case 1 =>
        val pRegIdx = (raw >> 7) & 0x1f
        val qInd = ((raw >> 6) & 1) == 1
        val qRegIdx = (raw >> 1) & 0x1f
        val pInd = (raw & 1) == 1

        if (!pInd && !qInd) {
          val opBits = (raw >> 12) & 0x3
          val mnem = opBits match {
            case 0 => "SUB      "
            case 1 => "CMP      "
            case 2 => "ADD      "
            case _ => "MOV      "
          }
          col = terminal.writeText(col, y, mnem, fg, bold = bold)
          col = terminal.writeRegName(col, y, pRegIdx, fg, bold = bold)
          col = terminal.writeText(col, y, ", ", fg, bold = bold)
          col = terminal.writeRegName(col, y, qRegIdx, fg, bold = bold)
        } else {
          val opBit = (raw >> 13) & 1
          val mnem = if (opBit == 0) "CMPB     " else "MOVB     "
          val bump = ((raw >> 12) & 1) == 1
          col = terminal.writeText(col, y, mnem, fg, bold = bold)
          if (pInd) col = terminal.writeChar(col, y, '[', fg, bold = bold)
          col = terminal.writeRegName(col, y, pRegIdx, fg, bold = bold)
          if (pInd) {
            col = terminal.writeChar(col, y, ']', fg, bold = bold)
            if (bump) col = terminal.writeText(col, y, "++", fg, bold = bold)
          }
          col = terminal.writeText(col, y, ", ", fg, bold = bold)
          if (qInd) col = terminal.writeChar(col, y, '[', fg, bold = bold)
          col = terminal.writeRegName(col, y, qRegIdx, fg, bold = bold)
          if (qInd) {
            col = terminal.writeChar(col, y, ']', fg, bold = bold)
            if (bump && !pInd) col = terminal.writeText(col, y, "++", fg, bold = bold)
          }
        }

      case 2 =>
        val forward = ((raw >> 13) & 1) == 1
        val onBitSet = ((raw >> 11) & 1) == 1
        val bit = (raw >> 7) & 0xf
        val regIdx = (raw >> 5) & 0x3
        val steps = ((raw >> 1) & 0xf) + 1
        val targetOffset = if (forward) steps * 2 else -steps * 2
        val targetAddr = (pc + 2 + targetOffset) & 0x7ff
        val mnem = if (onBitSet) "IJMP.ON  " else "IJMP.OFF "
        col = terminal.writeText(col, y, mnem, fg, bold = bold)
        col = terminal.writeRegName(col, y, regIdx, fg, bold = bold)
        col = terminal.writeText(col, y, ", ", fg, bold = bold)
        col = terminal.writeDec(col, y, bit, 0, ' ', fg, bold = bold)
        col = terminal.writeText(col, y, if (forward) ", +" else ", -", fg, bold = bold)
        col = terminal.writeDec(col, y, steps, 0, ' ', fg, bold = bold)
        col = terminal.writeText(col, y, " (0x", fg, bold = bold)
        col = terminal.writeHex3(col, y, targetAddr, fg, bold = bold)
        col = terminal.writeChar(col, y, ')', fg, bold = bold)

      case _ =>
        val opBits = (raw >> 12) & 0x3
        val mnem = opBits match {
          case 0 => "SUBI     "
          case 1 => "CMPI     "
          case 2 => "ADDI     "
          case _ => "MOVI     "
        }
        val regIdx = ((raw >> 8) & 0xf) * 2
        val imm = raw & 0xff
        col = terminal.writeText(col, y, mnem, fg, bold = bold)
        col = terminal.writeRegName(col, y, regIdx, fg, bold = bold)
        col = terminal.writeText(col, y, ", ", fg, bold = bold)
        col = terminal.writeDec(col, y, imm, 0, ' ', fg, bold = bold)
    }
  }
}
