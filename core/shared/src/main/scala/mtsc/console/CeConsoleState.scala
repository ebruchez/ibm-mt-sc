package mtsc.console

import mtsc.types.*
import scala.collection.mutable.ArrayBuffer

/**
 * Operating mode selected on the Customer Engineer maintenance console rotary switch.
 */
enum CeConsoleMode(val displayName: String) {
  case Process    extends CeConsoleMode("PROCESS")
  case EditAddress extends CeConsoleMode("EDIT ADDRESS")
  case EditData   extends CeConsoleMode("EDIT DATA")
  case SingleStep extends CeConsoleMode("SINGLE STEP")
}

/**
 * State of the Customer Engineer console and interactive debugger.
 */
class CeConsoleState {

  var mode: CeConsoleMode = CeConsoleMode.Process
  var breakpoints: Set[Address] = Set.empty
  var memoryInspectionAddress: Address = Address(0)
  var statusMessage: String = "Ready. Press [s] to step, [r] to run, [l] to load program."
  var powerOn: Boolean = true
  var singleStepMode: Boolean = false
  var viewGalley: Boolean = false
  var realTimePacing: Boolean = true
  var pacingSpeed: Double = 1.0 // 1.0 = 14 cps (~71 ms/char), 2.0 = 28 cps, 5.0 = 70 cps, 0.0 = instant

  def printDelayMs: Long =
    if (!realTimePacing || pacingSpeed <= 0.0) 0L
    else math.max(5L, (71.4 / pacingSpeed).toLong)

  def tapeDelayMs: Long =
    if (!realTimePacing || pacingSpeed <= 0.0) 0L
    else math.max(3L, (50.0 / pacingSpeed).toLong)

  val eventLog: ArrayBuffer[String] = ArrayBuffer(
    "IBM MT/SC CCU 6320 CE Console initialized.",
    "Clock oscillator: 240 kHz (nominal).",
    "Core storage: 2,048 ferrite bytes online."
  )

  def log(msg: String): Unit = {
    eventLog += msg
    if (eventLog.length > 30) {
      eventLog.remove(0)
    }
  }

  def toggleBreakpoint(addr: Address): Boolean = {
    if (breakpoints.contains(addr)) {
      breakpoints -= addr
      log(f"Breakpoint cleared at 0x${addr.unsigned}%03X")
      false
    } else {
      breakpoints += addr
      log(f"Breakpoint set at 0x${addr.unsigned}%03X")
      true
    }
  }

  def cycleMode(): CeConsoleMode = {
    mode = mode match {
      case CeConsoleMode.Process     => CeConsoleMode.SingleStep
      case CeConsoleMode.SingleStep  => CeConsoleMode.EditAddress
      case CeConsoleMode.EditAddress => CeConsoleMode.EditData
      case CeConsoleMode.EditData    => CeConsoleMode.Process
    }
    log(s"Rotary switch turned to ${mode.displayName}")
    mode
  }
}
