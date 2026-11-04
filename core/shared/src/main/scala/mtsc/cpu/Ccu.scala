package mtsc.cpu

import mtsc.types.*
import mtsc.isa.*
import mtsc.memory.CoreMemory
import mtsc.cpu.ild.IldCcu

sealed trait StepResult
object StepResult {
  case object Normal extends StepResult
  case object Stalled extends StepResult
  case object Halted extends StepResult
  final case class Error(message: String) extends StepResult
}

/**
 * Selects between the high-level ISA behavioral engine and the gate- and latch-level
 * Intermediate Logic Diagram (`0303`–`0312`) hardware engine.
 */
enum CcuEngineMode(val label: String, val shortLabel: String, val cliFlag: String) {
  case Behavioral extends CcuEngineMode("Behavioral (ISA)", "ISA", "--behavioral")
  case Ild        extends CcuEngineMode("Gate-Level (ILD 0303-0312)", "ILD", "--ild")
}

object CcuEngineMode {
  /** Default execution engine for the full system simulator (CLI & Web UI). */
  val Default: CcuEngineMode = CcuEngineMode.Ild

  def fromString(raw: String): Option[CcuEngineMode] = raw.trim.toLowerCase match {
    case "ild" | "gate" | "gate-level" | "hardware" | "--ild" | "--gate" =>
      Some(CcuEngineMode.Ild)
    case "behavioral" | "isa" | "high-level" | "fast" | "--behavioral" | "--isa" =>
      Some(CcuEngineMode.Behavioral)
    case _ =>
      None
  }
}

/**
 * Execution Engine for the IBM MT/SC Composer Control Unit (CCU) Model 6320.
 *
 * Can execute in either high-level [[CcuEngineMode.Behavioral]] mode or gate- and latch-level
 * [[CcuEngineMode.Ild]] mode ([[IldCcu]]).
 */
class Ccu(
  val memory: CoreMemory,
  val ioBus: IoBus,
  val state: CcuState = new CcuState(),
  var engineMode: CcuEngineMode = CcuEngineMode.Behavioral
) {

  /** Gate- and latch-level ILD (`0303`–`0312`) engine sharing `memory`, `ioBus`, and `state`. */
  lazy val ild: IldCcu = new IldCcu(memory, ioBus, state)

  def isIldMode: Boolean = engineMode == CcuEngineMode.Ild

  def setEngineMode(mode: CcuEngineMode): Unit = {
    engineMode = mode
    if (mode == CcuEngineMode.Ild) {
      ild.syncSignalsFromState()
    }
  }

  def toggleEngineMode(): CcuEngineMode = {
    val next = if (engineMode == CcuEngineMode.Ild) CcuEngineMode.Behavioral else CcuEngineMode.Ild
    setEngineMode(next)
    next
  }

  /**
   * Resets the CCU to power-on defaults.
   */
  def reset(): Unit = {
    if (engineMode == CcuEngineMode.Ild) {
      ild.reset()
    } else {
      memory.reset()
      state.reset()
    }
  }

  /**
   * Executes a single instruction step.
   */
  def step(): StepResult = {
    if (engineMode == CcuEngineMode.Ild) {
      return ild.step()
    }
    if (state.halted)
      return StepResult.Halted

    // Check for Multiplex interrupt prior to normal instruction fetch
    if (state.multiplex && ioBus.isTranslatorReady)
      handleMultiplexInterrupt()

    // I-0 Time: Read instruction from PSAR and advance PSAR by +2
    val fetchAddr = memory.psar
    state.mal = fetchAddr
    val rawWord = memory.readWord(fetchAddr)
    memory.aWord = rawWord // A-Word holds the instruction during execution
    memory.psar = fetchAddr + 2

    // I-1 Time: Load Operational Code Latches (OP 1..6 = bits 15..10, OP 7 = bit 0)
    val raw = rawWord.unsigned
    val op1 = (raw >> 15) & 1
    val op2 = (raw >> 14) & 1
    val op3 = (raw >> 13) & 1
    val op4 = (raw >> 12) & 1
    val op5 = (raw >> 11) & 1
    val op6 = (raw >> 10) & 1
    val op7 = raw & 1
    state.opCodeLatches = op1 | (op2 << 1) | (op3 << 2) | (op4 << 3) | (op5 << 4) | (op6 << 5) | (op7 << 6)
    state.pBuffer = raw & 0xf
    state.qBuffer = (raw >> 4) & 0xf

    // Decode instruction
    val insn = Decoder.decode(rawWord) match {
      case Left(err) =>
        return StepResult.Error(s"Decode error at $fetchAddr: $err (word: $rawWord)")
      case Right(i) => i
    }

    state.lastInsn = Some(insn)

    val result = executeInstruction(insn, fetchAddr)

    if (result == StepResult.Stalled) {
      memory.psar = fetchAddr
      state.inhibit = true
    } else {
      state.inhibit = false
    }

    // Hardware invariant: B-Word is always cleared after every instruction
    memory.bWord = Word.Zero
    state.cycleCount += 1

    result
  }

  /**
   * Runs until halted, error, maxCycles exceeded, or self-loop detected.
   */
  def run(maxCycles: Long = 1000000L, stopOnSelfLoop: Boolean = true): StepResult = {
    var cycles = 0L
    var result: StepResult = StepResult.Normal

    while (!state.halted && result == StepResult.Normal && cycles < maxCycles) {
      val prevPsar = memory.psar
      result = step()
      cycles += 1
      if (stopOnSelfLoop && !state.multiplex && memory.psar == prevPsar && !state.inhibit) {
        state.halted = true
        return StepResult.Halted
      }
    }

    if (cycles >= maxCycles && result == StepResult.Normal)
      StepResult.Error(s"Execution exceeded max cycles ($maxCycles)")
    else
      result
  }

  private def executeInstruction(insn: Instruction, fetchAddr: Address): StepResult = insn match {
    case Instruction.Branch(cond, link, target) =>
      val taken = cond match {
        case Condition.Unconditional => true
        case Condition.Hi            => state.isHi
        case Condition.Lo            => state.isLo
        case Condition.Equal         => state.isEqual
        case Condition.NotEqual      => state.isNotEqual
      }

      if (taken) {
        if (link) {
          // Hardware Jump with Link:
          // Write an unconditional Jump (to instruction after JMPL = fetchAddr + 2) into target entry slot
          val returnJump = Instruction.Branch(Condition.Unconditional, link = false, fetchAddr + 2)
          memory.writeWord(target, Encoder.encode(returnJump))
          // PSAR jumps to target + 2 (the first instruction of subroutine body)
          memory.psar = target + 2
        } else {
          memory.psar = target
        }
      }
      StepResult.Normal

    case Instruction.IncrementalBranch(forward, onBitSet, bit, reg, steps) =>
      val regVal = memory.readReg(reg)
      val bitVal = regVal.bit(bit)
      val taken = (onBitSet && bitVal) || (!onBitSet && !bitVal)

      if (taken) {
        val offset = if (forward) steps * 2 else -steps * 2
        memory.psar = (fetchAddr + 2) + offset
      }
      StepResult.Normal

    case Instruction.BitControl(op, bit, reg) =>
      val currentVal = memory.readReg(reg)
      val bitVal = currentVal.bit(bit)

      // Sampled bit updates condition latches
      if (bitVal) {
        state.equal = true
        state.low = false
      } else {
        state.equal = false
        state.low = true
      }

      // Apply operation
      val newVal = op match {
        case BitOp.Set      => currentVal.withBit(bit, true)
        case BitOp.Reset    => currentVal.withBit(bit, false)
        case BitOp.Preserve => currentVal
        case BitOp.Test     => currentVal
      }
      memory.writeReg(reg, newVal)
      StepResult.Normal

    case Instruction.ImmediateArith(op, reg, imm) =>
      val currentVal = memory.readReg(reg)
      val pLow = currentVal.lowByte

      op match {
        case ArithOp.Transfer =>
          // MOVI: load 8-bit unsigned value into register (clears high byte)
          val res = imm & 0xff
          memory.writeReg(reg, Word(res))
          state.equal = res == 0
          state.low = false

        case ArithOp.Add =>
          // ADDI: 16-bit add of immediate
          val res = (currentVal.unsigned + imm) & 0xffff
          memory.writeReg(reg, Word(res))
          updateFlags16(res)

        case ArithOp.Subtract =>
          // SUBI: 16-bit subtract of immediate
          val res = (currentVal.unsigned - imm) & 0xffff
          memory.writeReg(reg, Word(res))
          updateFlags16(res)

        case ArithOp.Compare =>
          // CMPI: 16-bit compare (flags only, register preserved)
          val diff = (currentVal.unsigned - imm) & 0xffff
          updateFlags16(diff)
      }
      StepResult.Normal

    case Instruction.RegisterArith(op, pReg, qReg) =>
      val pVal = memory.readReg(pReg).unsigned
      val qVal = memory.readReg(qReg).unsigned
      memory.bWord = Word(qVal) // Q register loaded into B-Word

      op match {
        case ArithOp.Transfer =>
          memory.writeReg(pReg, Word(qVal))
          state.equal = qVal == 0
          state.low = (qVal & 0x8000) != 0

        case ArithOp.Add =>
          val res = (pVal + qVal) & 0xffff
          memory.writeReg(pReg, Word(res))
          updateFlags16(res)

        case ArithOp.Subtract =>
          val res = (pVal - qVal) & 0xffff
          memory.writeReg(pReg, Word(res))
          updateFlags16(res)

        case ArithOp.Compare =>
          val diff = (pVal - qVal) & 0xffff
          updateFlags16(diff)
      }
      StepResult.Normal

    case Instruction.ByteIndirect(op, pReg, pInd, qReg, qInd, bump) =>
      // I4..I6: Resolve source value (and bump Q pointer in I4 if Q is indirect and bump is set)
      val qAddr = Address(memory.readReg(qReg).unsigned)
      if (bump && qInd) {
        val qNew = (qAddr.unsigned + 1) & 0x7ff
        memory.writeReg(qReg, Word(qNew))
      }
      val qByte = if (qInd) memory.readByte(qAddr) else memory.readReg(qReg).lowByte
      memory.bWord = Word(qByte)

      // I7..I9: Resolve destination address (and bump P pointer in I8 before I10 writeback)
      val pAddr = Address(memory.readReg(pReg).unsigned)
      if (bump && pInd) {
        val pNew = (pAddr.unsigned + 1) & 0x7ff
        memory.writeReg(pReg, Word(pNew))
      }

      // I10: Execute transfer or compare
      op match {
        case ArithOp.Transfer =>
          if (pInd) {
            memory.writeByte(pAddr, qByte)
          } else {
            // Write to register low byte
            val cur = memory.readReg(pReg).unsigned
            val newP = (cur & 0xff00) | (qByte & 0xff)
            memory.writeReg(pReg, Word(newP))
          }
          state.equal = qByte == 0
          state.low = false

        case _ => // Compare (or other SCAT)
          val pByte = if (pInd) memory.readByte(pAddr) else memory.readReg(pReg).lowByte
          val diff = pByte - qByte
          state.equal = diff == 0
          state.low = diff < 0
      }
      StepResult.Normal

    case Instruction.Input(device, targetReg) =>
      ioBus.read(device) match {
        case IoResult.Ready(byte) =>
          state.inhibit = false
          memory.writeReg(targetReg, Word(byte & 0xff))
          state.equal = (byte & 0xff) == 0
          state.low = false
          StepResult.Normal

        case IoResult.Busy =>
          // Device busy: stall pipeline and repeat instruction
          state.inhibit = true
          StepResult.Stalled
      }

    case Instruction.Output(device, sourceReg, indirect, multiplex) =>
      val byte = if (indirect) {
        val ptr = Address(memory.readReg(sourceReg).unsigned)
        state.mal = ptr
        memory.readByte(ptr)
      } else {
        state.mal = sourceReg.byteAddress
        memory.readReg(sourceReg).lowByte
      }

      state.outputReg = byte & 0xff
      state.pBuffer = byte & 0xf

      // Halt code: 0xFF terminates multiplex mode
      if (byte == 0xff) {
        state.multiplex = false
      } else if (multiplex) {
        state.multiplex = true
      }

      ioBus.write(device, byte, multiplex) match {
        case IoResult.Ready(_) =>
          state.inhibit = false
          if (indirect) {
            val nextPtr = (memory.readReg(sourceReg).unsigned + 1) & 0x7ff
            memory.writeReg(sourceReg, Word(nextPtr))
          }
          StepResult.Normal

        case IoResult.Busy =>
          state.inhibit = true
          StepResult.Stalled
      }
  }

  /**
   * Simulates the hardware multiplex interrupt:
   * When translator is ready, outputs character at [R0] and increments R0.
   */
  private def handleMultiplexInterrupt(): Unit = {
    val r0 = RegId(0)
    val ptr = Address(memory.readReg(r0).unsigned)
    val byte = memory.readByte(ptr)
    state.mal = ptr
    state.outputReg = byte & 0xff

    // Increment R0 pointer by 1
    val nextPtr = (memory.readReg(r0).unsigned + 1) & 0x7ff
    memory.writeReg(r0, Word(nextPtr))

    if (byte == 0xff) {
      state.multiplex = false // Halt code encountered
    } else {
      ioBus.write(Device.Translator, byte, multiplex = true)
    }
  }

  private def updateFlags16(result16: Int): Unit = {
    state.equal = result16 == 0
    state.low = (result16 & 0x8000) != 0 // Sign bit in 16-bit two's complement
  }
}
