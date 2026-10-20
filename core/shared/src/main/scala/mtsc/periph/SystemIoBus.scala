package mtsc.periph

import mtsc.types.Device
import mtsc.cpu.{IoBus, IoResult}

/**
 * Hardware I/O Bus connecting the CCU to the MT Reader stations, Composer Translator,
 * and Console lamps.
 */
class SystemIoBus(
  val rightTape: TapeReader = new TapeReader(Device.RightTape),
  val leftTape: TapeReader = new TapeReader(Device.LeftTape),
  val translator: ComposerTranslator = new ComposerTranslator(),
  val operatorPanel: OperatorPanelState = new OperatorPanelState()
) extends IoBus {

  var consoleLights: Int = 0

  override def read(device: Device): IoResult[Int] = device match {
    case Device.RightTape => rightTape.read()
    case Device.LeftTape  => leftTape.read()
    case Device.Keyboard  => operatorPanel.readKeyboardCode()
    case _                => IoResult.Ready(0)
  }

  override def write(device: Device, byte: Int, multiplex: Boolean): IoResult[Unit] = device match {
    case Device.Translator =>
      translator.write(byte, multiplex)

    case Device.Lights =>
      consoleLights = byte & 0xff
      IoResult.Ready(())

    case _ =>
      IoResult.Ready(())
  }

  override def isTranslatorReady: Boolean =
    translator.isReady

  /**
   * Advances peripheral mechanical cycles (called once per CCU instruction cycle).
   */
  def stepCycle(): Unit = {
    rightTape.stepCycle()
    leftTape.stepCycle()
    translator.stepCycle()
  }

  def reset(): Unit = {
    rightTape.reset()
    leftTape.reset()
    translator.reset()
    operatorPanel.reset()
    consoleLights = 0
  }
}
