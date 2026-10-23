package mtsc.cpu

import mtsc.types.*
import mtsc.isa.Instruction

/**
 * Encapsulates the latches and runtime status of the CCU.
 */
final class CcuState {
  // Condition Latches
  var low: Boolean = false    // Set when calculation result is negative (< 0)
  var equal: Boolean = false  // Set when calculation result is zero (= 0)

  // Control Latches
  var inhibit: Boolean = false   // Hardware stall latch (suppresses PSAR increment)
  var multiplex: Boolean = false // Background Multiplex printing mode active
  var halted: Boolean = false    // CPU stopped

  // Hardware Latches exposed on the Customer Engineer Display Matrix (ILD Page 0312)
  var mal: Address = Address(0)  // 11-bit Memory Address Latches (Y 11..1, Display 1)
  var outputReg: Int = 0         // 8-bit Output Register (O 8..1, Display 2)
  var opCodeLatches: Int = 0     // 7-bit Operational Code Latches (OP 7..1, Display 3)
  var qBuffer: Int = 0           // 4-bit Q-Buffer Sense Amp Triggers (QB 4..1, Display 4)
  var pBuffer: Int = 0           // 4-bit P-Buffer Sense Amp Triggers (PB 4..1, Display 4)
  var insnTimeCtr: Int = 0       // 4-bit Instruction Time Counter (ID, IC, IB, IA: I-0..I-9)
  var bitTimeCtr: Int = 0        // 2-bit Bit Time Counter (B, A: 0..3 -> Bit 1/4..13/16)

  // Telemetry & Diagnostic state
  var cycleCount: Long = 0L
  var lastInsn: Option[Instruction] = None

  def isHi: Boolean = !low && !equal
  def isLo: Boolean = low
  def isEqual: Boolean = equal
  def isNotEqual: Boolean = !equal

  /**
   * Returns true if MAL bit n (1..11, corresponding to Y 1..Y 11) is set.
   */
  def yBit(n: Int): Boolean =
    n >= 1 && n <= 11 && (((mal.unsigned >> (n - 1)) & 1) == 1)

  /**
   * Returns true if Output Register bit n (1..8, corresponding to O 1..O 8) is set.
   */
  def outBit(n: Int): Boolean =
    n >= 1 && n <= 8 && (((outputReg >> (n - 1)) & 1) == 1)

  /**
   * Returns true if Operational Code Latch n (1..7, corresponding to OP 1..OP 7) is set.
   */
  def opBit(n: Int): Boolean =
    n >= 1 && n <= 7 && (((opCodeLatches >> (n - 1)) & 1) == 1)

  /**
   * Returns true if Q-Buffer bit n (1..4, corresponding to QB 1..QB 4) is set.
   */
  def qbBit(n: Int): Boolean =
    n >= 1 && n <= 4 && (((qBuffer >> (n - 1)) & 1) == 1)

  /**
   * Returns true if P-Buffer bit n (1..4, corresponding to PB 1..PB 4) is set.
   */
  def pbBit(n: Int): Boolean =
    n >= 1 && n <= 4 && (((pBuffer >> (n - 1)) & 1) == 1)

  def reset(): Unit = {
    low = false
    equal = false
    inhibit = false
    multiplex = false
    halted = false
    mal = Address(0)
    outputReg = 0
    opCodeLatches = 0
    qBuffer = 0
    pBuffer = 0
    insnTimeCtr = 0
    bitTimeCtr = 0
    cycleCount = 0L
    lastInsn = None
  }
}
