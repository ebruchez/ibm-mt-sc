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

  // Telemetry & Diagnostic state
  var cycleCount: Long = 0L
  var lastInsn: Option[Instruction] = None

  def isHi: Boolean = !low && !equal
  def isLo: Boolean = low
  def isEqual: Boolean = equal
  def isNotEqual: Boolean = !equal

  def reset(): Unit = {
    low = false
    equal = false
    inhibit = false
    multiplex = false
    halted = false
    cycleCount = 0L
    lastInsn = None
  }
}
