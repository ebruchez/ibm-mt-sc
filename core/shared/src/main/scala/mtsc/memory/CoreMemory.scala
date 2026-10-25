package mtsc.memory

import mtsc.types.*

/**
 * 2,048-byte Core Memory subsystem for the IBM MT/SC CCU.
 *
 * Includes:
 * - 2,048 bytes of general core storage ($0..2047)
 * - 32 Working Registers (mapped to byte addresses 0..63)
 * - Dedicated Special Words: PSAR (IAW), A-Word (AW), B-Word (BW)
 */
final class CoreMemory(private val storage: Array[Byte]) {
  require(storage.length == CoreMemory.Size, s"CoreMemory requires ${CoreMemory.Size} bytes")

  // Special-purpose registers
  var psar: Address = Address.PowerOn // Default power-on instruction address: 546
  var aWord: Word = Word.Zero         // Instruction hold & immediate data register
  var bWord: Word = Word.Zero         // Working operand buffer (always cleared after instruction)

  def readByte(addr: Address): Int =
    storage(addr.unsigned) & 0xff

  def writeByte(addr: Address, b: Int): Unit =
    storage(addr.unsigned) = (b & 0xff).toByte

  def readWord(addr: Address): Word = {
    val lo = readByte(addr)
    val hi = readByte(addr + 1)
    Word.fromBytes(hi, lo)
  }

  def writeWord(addr: Address, w: Word): Unit = {
    writeByte(addr, w.lowByte)
    writeByte(addr + 1, w.highByte)
  }

  // Working Registers 0..31 access (mapped to byte addresses reg * 2)
  def readReg(reg: RegId): Word =
    readWord(reg.byteAddress)

  def writeReg(reg: RegId, w: Word): Unit =
    writeWord(reg.byteAddress, w)

  def loadFromImage(image: CoreImage): Unit = {
    Array.copy(image.bytes, 0, storage, 0, CoreMemory.Size)
  }

  def toCoreImage: CoreImage =
    new CoreImage(storage.clone())

  def reset(): Unit = {
    psar = Address.PowerOn
    aWord = Word.Zero
    bWord = Word.Zero
  }

  /**
   * Clears all 2,048 bytes of core storage to 0x00 and resets PSAR, A-Word, and B-Word to 0.
   */
  def clear(): Unit = {
    var i = 0
    while (i < storage.length) {
      storage(i) = 0
      i += 1
    }
    psar = Address(0)
    aWord = Word.Zero
    bWord = Word.Zero
  }
}

object CoreMemory {
  val Size: Int = 2048

  def empty(): CoreMemory =
    new CoreMemory(new Array[Byte](Size))

  def fromImage(image: CoreImage): CoreMemory = {
    val mem = empty()
    mem.loadFromImage(image)
    mem
  }
}
