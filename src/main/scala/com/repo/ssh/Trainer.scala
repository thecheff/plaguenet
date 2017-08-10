package com.indeni.ssh

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import com.indeni.ssh.common.PromptStr
import com.indeni.ssh.utils.FutureOps
import org.apache.sshd.common.util.buffer.ByteArrayBuffer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Trainer {
  def props(channel: Channel) = Props(new Trainer(channel))

  case class TrainingSessionComplete( prompts:Option[PromptStr])
  /**
    *
    * @param pipeTo
    * @param maxTrainingCycles - number of times to run the training command (i.e new line)
    * @param minForDeduction - min same prompts that consider consistent enough for detection
    */
  case class StartTraining(pipeTo: ActorRef, maxTrainingCycles: Int = 10, minForDeduction: Int = 3)

}

class Trainer(channel: Channel) extends Actor
  with SshdClient2 with FutureOps with ActorLogging {

  import Trainer._

  override implicit val ec: ExecutionContext = context.dispatcher
  implicit val scheduler = context.system.scheduler
  val buffer = new ByteArrayBuffer
  val CMD = "\n"
  var dataBuffer = new StringBuilder()

  override def receive: Receive = ready

  def ready: Receive = {
    case StartTraining(pipeTo,   maxTrainingCycles, minForDeduction) =>
      context become training(sender, maxTrainingCycles, minForDeduction)
      self ! RunCommands(maxTrainingCycles)
  }

  def training(pipeTo:ActorRef, maxTrainingCycles: Int, minToDetect: Int ): Receive = {

    case RunCommands(count) =>
      val delays: List[FiniteDuration] = List(200 millis, 200 millis, 500 millis)
      retry(executeCommand(channel, CMD), delay = delays, retries = 5, defaultDelay = 300 millis).onComplete {
        case Success(i) =>
          if (count > 0 ){
            log.info(s"Training Executing cmd cycle# $count")
            context.system.scheduler.scheduleOnce(1 second, self, RunCommands(count - 1))
          } else {
            self ! ReadTrainingData(0)
          }

        case Failure(ex) =>
          log.error(s"Failed to execute command ${ex.getMessage}", ex.getCause)
          self ! Status.Failure(ex)
      }

    case ReadTrainingData(counter) =>
      log.info(s"Start reading from channel, $channel cycle $counter")

      channel.listenOnChannelOut(buffer).retry(delay = 100 millis, retries = 5, timeout = 5 second).onComplete {
        case Success(bfr) =>
          log.info(s" $counter completed ${bfr.array().length}")
          assert(channel.channelShell.isOpen)
          val data: String = new String(buffer.array, buffer.rpos, buffer.available)
          dataBuffer.append(data)

          val str = dataBuffer.toString()
          log.info(s"===> buffer: $str")
          val p = PromptStr(analyze(str))

          if (p.prompts.nonEmpty) {
            log.info(s"Reached expected - mission complete $p")
            pipeTo ! TrainingSessionComplete(Some(p))
          } else {
            buffer.clear()
            context.system.scheduler.scheduleOnce(2 seconds, self, ReadTrainingData(counter + 1))
          }

        case Failure(e) =>
          log.error(s"Failed to listen to channel ${e.getMessage}", e.getCause)
          pipeTo ! TrainingSessionComplete(None)

      }

      def analyze(str: String): List[String] = {
        val nonEmptyLines = str.lines.toList.filter(_.trim.nonEmpty).toArray.takeRight(minToDetect)
        val grouped = nonEmptyLines.groupBy(l => l)
        val prompts = grouped.filter(_._2.length >= minToDetect).keys
        prompts.toList

      }
  }

  case class ReadTrainingData(counter: Int)

  case class RunCommands(counter: Int)
}

