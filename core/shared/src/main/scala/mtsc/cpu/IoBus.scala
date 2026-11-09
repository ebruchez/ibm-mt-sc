package mtsc.cpu

import mtsc.types.*

sealed trait IoResult[+A]
object IoResult {
  final case class Ready[A](value: A) extends IoResult[A]
  object Ready {
    private val unitInstance: Ready[Unit] = new Ready[Unit](())
    private val byteCache: Array[Ready[Int]] = {
      val arr = new Array[Ready[Int]](256)
      var i = 0
      while (i < 256) {
        arr(i) = new Ready[Int](i)
        i += 1
      }
      arr
    }

    @inline def apply(value: Int): Ready[Int] =
      if (value >= 0 && value < 256) byteCache(value) else new Ready[Int](value)

    @inline def apply(value: Unit): Ready[Unit] =
      unitInstance
  }
  case object Busy extends IoResult[Nothing]
}

/**
 * Interface representing external peripheral connections to the CCU.
 */
trait IoBus {
  /**
   * Read character from an input device.
   * Returns IoResult.Ready(byte) if data is available, or IoResult.Busy if the device is busy/cycling.
   */
  def read(device: Device): IoResult[Int]

  /**
   * Write byte to an output device.
   * Returns IoResult.Ready(()) if accepted, or IoResult.Busy if the device cannot accept data.
   */
  def write(device: Device, byte: Int, multiplex: Boolean): IoResult[Unit]

  /**
   * Returns true if the Translator (Selectric Composer) is idle and ready to print a new character.
   */
  def isTranslatorReady: Boolean
}

/**
 * A mock / stub implementation of IoBus useful for testing CPU execution.
 */
class MockIoBus extends IoBus {
  val inputQueues: collection.mutable.Map[Device, collection.mutable.Queue[Int]] =
    collection.mutable.Map()

  val outputLogs: collection.mutable.Map[Device, collection.mutable.ListBuffer[Int]] =
    collection.mutable.Map()

  var translatorReady: Boolean = true

  def queueInput(device: Device, bytes: Int*): Unit = {
    inputQueues.getOrElseUpdate(device, collection.mutable.Queue.empty[Int]).enqueueAll(bytes)
  }

  def getOutput(device: Device): List[Int] =
    outputLogs.getOrElse(device, collection.mutable.ListBuffer.empty[Int]).toList

  override def read(device: Device): IoResult[Int] = {
    val q = inputQueues.getOrElseUpdate(device, collection.mutable.Queue.empty[Int])
    if (q.nonEmpty) IoResult.Ready(q.dequeue())
    else IoResult.Busy
  }

  override def write(device: Device, byte: Int, multiplex: Boolean): IoResult[Unit] = {
    outputLogs.getOrElseUpdate(device, collection.mutable.ListBuffer.empty[Int]) += byte
    IoResult.Ready(())
  }

  override def isTranslatorReady: Boolean = translatorReady
}
