package mtsc.asm

import mtsc.types.*
import mtsc.isa.*
import mtsc.memory.CoreImage

case class AsmError(lineNo: Int, message: String)

/**
 * Two-pass Assembler for IBM MT/SC assembly language.
 */
object Assembler {

  private case class SourceLine(lineNo: Int, text: String)

  private sealed trait Statement
  private case class DirectiveOrg(addr: Int) extends Statement
  private case class DirectiveWord(expr: String) extends Statement
  private case class DirectiveByte(value: Int) extends Statement
  private case class DirectiveSpace(count: Int) extends Statement
  private case class AsmInstruction(mnemonic: String, args: List[String]) extends Statement

  def assemble(source: String): Either[List[AsmError], CoreImage] = {
    val errors = List.newBuilder[AsmError]
    val lines = source.linesIterator.zipWithIndex.map { case (text, idx) =>
      SourceLine(idx + 1, text)
    }.toList

    // Pass 1: Parse statements, track PC, record labels
    var currentPc = 546 // Default start address (Address.PowerOn)
    val symbols = collection.mutable.Map[String, Int]()
    val statements = List.newBuilder[(SourceLine, Int, Statement)]

    for (line <- lines) {
      val stripped = line.text.split(';').headOption.getOrElse("").trim
      if (stripped.nonEmpty) {
        // Check for label
        val (labelOpt, rest) = if (stripped.contains(':')) {
          val parts = stripped.split(":", 2)
          (Some(parts(0).trim), parts(1).trim)
        } else {
          (None, stripped)
        }

        labelOpt.foreach { label =>
          if (symbols.contains(label)) {
            errors += AsmError(line.lineNo, s"Duplicate label '$label'")
          } else {
            symbols(label) = currentPc
          }
        }

        if (rest.nonEmpty) {
          parseStatement(rest, line.lineNo) match {
            case Left(err) => errors += err
            case Right(stmt) =>
              statements += ((line, currentPc, stmt))
              stmt match {
                case DirectiveOrg(newPc) =>
                  currentPc = newPc
                case DirectiveWord(_) =>
                  currentPc += 2
                case DirectiveByte(_) =>
                  currentPc += 1
                case DirectiveSpace(count) =>
                  currentPc += count
                case AsmInstruction(_, _) =>
                  currentPc += 2
              }
          }
        }
      }
    }

    if (errors.result().nonEmpty) {
      return Left(errors.result())
    }

    // Pass 2: Emit binary into CoreImage
    val image = CoreImage.empty()
    val parsedStmts = statements.result()

    for ((line, pc, stmt) <- parsedStmts) {
      stmt match {
        case DirectiveOrg(_) =>
          // PC updated during pass 1

        case DirectiveWord(expr) =>
          parseNumberOrSymbol(expr, symbols.toMap) match {
            case Right(num) =>
              image.writeWord(Address(pc), Word(num & 0xffff))
            case Left(err) =>
              errors += AsmError(line.lineNo, err)
          }

        case DirectiveByte(value) =>
          image.writeByte(Address(pc), value)

        case DirectiveSpace(count) =>
          for (i <- 0 until count) {
            image.writeByte(Address(pc + i), 0)
          }

        case AsmInstruction(mnemonic, args) =>
          encodeInstruction(mnemonic, args, pc, symbols.toMap, line.lineNo) match {
            case Right(insn) =>
              val word = Encoder.encode(insn)
              image.writeWord(Address(pc), word)
            case Left(err) =>
              errors += err
          }
      }
    }

    val finalErrors = errors.result()
    if (finalErrors.nonEmpty) Left(finalErrors) else Right(image)
  }

  private def parseStatement(lineText: String, lineNo: Int): Either[AsmError, Statement] = {
    val tokens = lineText.trim.split("\\s+", 2)
    val op = tokens(0).toUpperCase
    val argsStr = if (tokens.length > 1) tokens(1).trim else ""

    op match {
      case ".ORG" =>
        parseNumber(argsStr).map(DirectiveOrg.apply)
          .toRight(AsmError(lineNo, s"Invalid .org argument: '$argsStr'"))

      case ".WORD" =>
        Right(DirectiveWord(argsStr))

      case ".BYTE" =>
        parseNumber(argsStr).map(DirectiveByte.apply)
          .toRight(AsmError(lineNo, s"Invalid .byte argument: '$argsStr'"))

      case ".SPACE" =>
        parseNumber(argsStr).map(DirectiveSpace.apply)
          .toRight(AsmError(lineNo, s"Invalid .space argument: '$argsStr'"))

      case mnemonic =>
        val argList = if (argsStr.isEmpty) Nil else splitArgs(argsStr)
        Right(AsmInstruction(mnemonic, argList))
    }
  }

  private def splitArgs(args: String): List[String] =
    args.split(',').map(_.trim).filter(_.nonEmpty).toList

  private def parseNumber(str: String): Option[Int] = {
    val s = str.trim
    if (s.startsWith("0x") || s.startsWith("0X")) {
      scala.util.Try(Integer.parseInt(s.substring(2), 16)).toOption
    } else {
      scala.util.Try(s.toInt).toOption
    }
  }

  private def parseNumberOrSymbol(expr: String, symbols: Map[String, Int]): Either[String, Int] = {
    val s = expr.trim
    symbols.get(s)
      .map(Right.apply)
      .getOrElse(parseNumber(s).toRight(s"Unknown symbol or invalid number: '$s'"))
  }

  private def parseReg(str: String): Option[RegId] = {
    val s = str.trim.toUpperCase
    if (s.startsWith("R")) {
      scala.util.Try(s.substring(1).toInt).toOption.filter(i => i >= 0 && i < 32).map(RegId.apply)
    } else None
  }

  private def parseDevice(str: String): Option[Device] = {
    str.trim.toUpperCase match {
      case "TAPE" | "TAPE_R" | "RIGHT_TAPE" => Some(Device.RightTape)
      case "TAPE_L" | "LEFT_TAPE"           => Some(Device.LeftTape)
      case "PAPER" | "PAPER_TAPE"           => Some(Device.PaperTape)
      case "KB" | "KEYBOARD"                => Some(Device.Keyboard)
      case "TSLTR" | "TRANSLATOR" | "COMP"  => Some(Device.Translator)
      case "LTS" | "LIGHTS"                 => Some(Device.Lights)
      case _                                => None
    }
  }

  private def encodeInstruction(
    mnemonic: String,
    args: List[String],
    pc: Int,
    symbols: Map[String, Int],
    lineNo: Int
  ): Either[AsmError, Instruction] = {

    def err(msg: String) = Left(AsmError(lineNo, msg))

    mnemonic match {
      // Branch instructions
      case "JMP" | "JMPL" | "JMP.HI" | "JMP.LO" | "JMP.EQ" | "JMP.NE" =>
        if (args.length != 1) return err(s"$mnemonic requires 1 target argument")
        val isLink = mnemonic == "JMPL"
        val cond = mnemonic match {
          case "JMP.HI" => Condition.Hi
          case "JMP.LO" => Condition.Lo
          case "JMP.EQ" => Condition.Equal
          case "JMP.NE" => Condition.NotEqual
          case _        => Condition.Unconditional
        }
        parseNumberOrSymbol(args(0), symbols) match {
          case Left(e) => err(e)
          case Right(target) =>
            if ((target & 1) != 0) err(s"Target address 0x${target.toHexString} must be word-aligned (even)")
            else Right(Instruction.Branch(cond, isLink, Address(target)))
        }

      // Incremental Branch: IJMP.ON Rq, bit, target / IJMP.OFF Rq, bit, target
      case "IJMP.ON" | "IJMP.OFF" =>
        if (args.length != 3) return err(s"$mnemonic requires 3 arguments: reg, bit, target")
        val onBitSet = mnemonic == "IJMP.ON"
        for {
          reg <- parseReg(args(0)).toRight(AsmError(lineNo, s"Invalid register: ${args(0)}"))
          bit <- parseNumber(args(1)).filter(b => b >= 0 && b < 16)
            .toRight(AsmError(lineNo, s"Invalid bit index: ${args(1)}"))
          target <- parseNumberOrSymbol(args(2), symbols).left.map(AsmError(lineNo, _))
        } yield {
          val diff = target - (pc + 2)
          val steps = (diff.abs / 2)
          val forward = diff >= 0
          Instruction.IncrementalBranch(forward, onBitSet, bit, reg, math.max(1, math.min(16, steps)))
        }

      // Bit Control: SBIT, RBIT, PBIT, TBIT
      case "SBIT" | "RBIT" | "PBIT" | "TBIT" =>
        if (args.length != 2) return err(s"$mnemonic requires 2 arguments: reg, bit")
        val op = mnemonic match {
          case "SBIT" => BitOp.Set
          case "RBIT" => BitOp.Reset
          case "PBIT" => BitOp.Preserve
          case "TBIT" => BitOp.Test
        }
        for {
          reg <- parseReg(args(0)).toRight(AsmError(lineNo, s"Invalid register: ${args(0)}"))
          bit <- parseNumber(args(1)).filter(b => b >= 0 && b < 16)
            .toRight(AsmError(lineNo, s"Invalid bit index: ${args(1)}"))
        } yield Instruction.BitControl(op, bit, reg)

      // Immediate Arithmetic: MOVI, ADDI, SUBI, CMPI
      case "MOVI" | "ADDI" | "SUBI" | "CMPI" =>
        if (args.length != 2) return err(s"$mnemonic requires 2 arguments: reg, imm")
        val op = mnemonic match {
          case "MOVI" => ArithOp.Transfer
          case "ADDI" => ArithOp.Add
          case "SUBI" => ArithOp.Subtract
          case "CMPI" => ArithOp.Compare
        }
        for {
          reg <- parseReg(args(0)).toRight(AsmError(lineNo, s"Invalid register: ${args(0)}"))
          _ <- Either.cond(
            (reg.index & 1) == 0,
            (),
            AsmError(lineNo, s"Immediate arithmetic ($mnemonic) only supports even registers (R0, R2, ..., R30), got $reg")
          )
          imm <- parseNumberOrSymbol(args(1), symbols).left.map(AsmError(lineNo, _))
        } yield Instruction.ImmediateArith(op, reg, imm & 0xff)

      // Register Direct or Byte Indirect Arithmetic: MOV, ADD, SUB, CMP, MOVB, CMPB
      case "MOV" | "ADD" | "SUB" | "CMP" =>
        if (args.length != 2) return err(s"$mnemonic requires 2 arguments: pReg, qReg")
        val op = mnemonic match {
          case "MOV" => ArithOp.Transfer
          case "ADD" => ArithOp.Add
          case "SUB" => ArithOp.Subtract
          case "CMP" => ArithOp.Compare
        }
        for {
          pReg <- parseReg(args(0)).toRight(AsmError(lineNo, s"Invalid P register: ${args(0)}"))
          qReg <- parseReg(args(1)).toRight(AsmError(lineNo, s"Invalid Q register: ${args(1)}"))
        } yield Instruction.RegisterArith(op, pReg, qReg)

      case "MOVB" | "CMPB" =>
        if (args.length != 2) return err(s"$mnemonic requires 2 arguments: dest, src")
        val op = if (mnemonic == "MOVB") ArithOp.Transfer else ArithOp.Compare
        val pStr = args(0)
        val qStr = args(1)

        val pInd = pStr.startsWith("[") && pStr.contains("]")
        val qInd = qStr.startsWith("[") && qStr.contains("]")
        val pBump = pStr.contains("++")
        val qBump = qStr.contains("++")
        val bump = pBump || qBump

        val pClean = pStr.replace("[", "").replace("]", "").replace("++", "").trim
        val qClean = qStr.replace("[", "").replace("]", "").replace("++", "").trim

        for {
          pReg <- parseReg(pClean).toRight(AsmError(lineNo, s"Invalid P register: $pStr"))
          qReg <- parseReg(qClean).toRight(AsmError(lineNo, s"Invalid Q register: $qStr"))
        } yield Instruction.ByteIndirect(op, pReg, pInd, qReg, qInd, bump)

      // Input: IN device, targetReg  or  IN targetReg, device
      case "IN" =>
        if (args.length != 2) return err("IN requires 2 arguments: device, targetReg")
        val parsed = parseDevice(args(0)).flatMap(dev => parseReg(args(1)).map(reg => (dev, reg)))
          .orElse(parseDevice(args(1)).flatMap(dev => parseReg(args(0)).map(reg => (dev, reg))))
        parsed match {
          case Some((dev, reg)) => Right(Instruction.Input(dev, reg))
          case None => err(s"IN requires a device and a register, got: ${args.mkString(", ")}")
        }

      // Output: OUT device, reg / OUT reg, device / OUTM device, [reg] / OUTM [reg], device
      case "OUT" | "OUTM" =>
        if (args.length != 2) return err(s"$mnemonic requires 2 arguments: device, source")
        val mpx = mnemonic == "OUTM"
        def parseOut(dStr: String, sStr: String): Option[Instruction.Output] = {
          for {
            dev <- parseDevice(dStr)
            ind = sStr.startsWith("[") && sStr.contains("]")
            regStr = sStr.replace("[", "").replace("]", "").trim
            reg <- parseReg(regStr)
          } yield Instruction.Output(dev, reg, ind, mpx)
        }
        parseOut(args(0), args(1)).orElse(parseOut(args(1), args(0))) match {
          case Some(insn) => Right(insn)
          case None => err(s"$mnemonic requires a device and a register/indirect source, got: ${args.mkString(", ")}")
        }

      case other =>
        err(s"Unrecognized mnemonic '$other'")
    }
  }
}
