package mtsc.asm

import mtsc.types.*
import mtsc.isa.*

class DisassemblerSuite extends munit.FunSuite {

  test("disassemble instructions") {
    val branch = Instruction.Branch(Condition.Equal, link = false, Address(0x320))
    val w1 = Encoder.encode(branch)
    val d1 = Disassembler.disassemble(w1, Address(0x100))
    assert(d1.contains("JMP.EQ"), s"Expected JMP.EQ in '$d1'")
    assert(d1.contains("0x320"), s"Expected 0x320 in '$d1'")

    val jmpl = Instruction.Branch(Condition.Unconditional, link = true, Address(820))
    val w2 = Encoder.encode(jmpl)
    val d2 = Disassembler.disassemble(w2, Address(600))
    assert(d2.contains("JMPL"), s"Expected JMPL in '$d2'")

    val movi = Instruction.ImmediateArith(ArithOp.Transfer, RegId(8), 255)
    val w3 = Encoder.encode(movi)
    val d3 = Disassembler.disassemble(w3)
    assert(d3.contains("MOVI"), s"Expected MOVI in '$d3'")
    assert(d3.contains("R8"), s"Expected R8 in '$d3'")
    assert(d3.contains("255"), s"Expected 255 in '$d3'")

    val input = Instruction.Input(Device.RightTape, RegId(4))
    val w4 = Encoder.encode(input)
    val d4 = Disassembler.disassemble(w4)
    assert(d4.contains("IN"), s"Expected IN in '$d4'")
    assert(d4.contains("TAPE_R"), s"Expected TAPE_R in '$d4'")
    assert(d4.contains("R4"), s"Expected R4 in '$d4'")

    val outm = Instruction.Output(Device.Translator, RegId(0), indirect = true, multiplex = true)
    val w5 = Encoder.encode(outm)
    val d5 = Disassembler.disassemble(w5)
    assert(d5.contains("OUTM"), s"Expected OUTM in '$d5'")
    assert(d5.contains("[R0]"), s"Expected [R0] in '$d5'")
  }
}
