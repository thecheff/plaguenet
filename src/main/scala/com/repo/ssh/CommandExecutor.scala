package com.indeni.ssh

import java.io.IOException

import akka.actor.{Actor, ActorLogging, ActorRef, Kill, Props, Status}
import com.indeni.ssh.common.IndTypes._
import com.indeni.ssh.common._
import com.indeni.ssh.utils.FutureOps
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.util.buffer.{Buffer, ByteArrayBuffer}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object CommandExecutor{
  def props(session: ClientSession, expect: Prompt) = Props(new CommandExecutor(session, expect))
}

class CommandExecutor(session: ClientSession, expect: Prompt) extends Actor
  with SshdClient2 with FutureOps with ActorLogging{

  override implicit val ec: ExecutionContext = context.dispatcher
  val longerString: ( String, String) => String = (s1, s2) => if (s1.length > s2.length) s1 else s2
  implicit val scheduler =  context.system.scheduler
  val delays = List(200 millis, 200 millis, 500 millis, 1 seconds, 2 seconds)
  val cnl = createChannel(self.path.name, session).retry(delays, 5, 500 millis).recoverWith {
    case e =>
      log.error(s"Failed to create channel ${e.getMessage}", e.getCause)
      self ! Kill
      Future.failed(e)
  }
  var dataBuffer = new StringBuilder()

  override def postStop(): Unit = {
    cnl.foreach { channel =>
      channel.closeChannel(false).retry(delays, delays.length, 300 millis).onFailure { case e =>
        log.error(s"Failed to close channel ${e.getMessage}", e.getCause)
      }
    }
  }

  def receive: Receive = close orElse {

    case ProcessPrompts(discussionMap) =>
      val origSender = sender()
      context become processing(discussionMap, origSender, new ByteArrayBuffer)
      self ! DoProcess

    case cmd: SimpleCommand =>
      val origSender = sender()
      context become processing(Map.empty[Prompt, ShellCommand], origSender, new ByteArrayBuffer)
      self ! cmd

    case other =>
      throw new IOException(s"didn't expect $other in state receive")

  }

  def processing(discussionMap: Map[Prompt, ShellCommand], replyTo: ActorRef, buffer: Buffer): Receive =
    close orElse {
    case DoProcess =>

      cnl.foreach { channel =>
        channel.listenOnChannelOut(buffer).retry(delay = 100 millis, retries = 5, timeout = 5 second).onComplete {
          case Success(bfr) =>
            assert(channel.channelShell.isOpen)
            val data: String = new String(buffer.array, buffer.rpos, buffer.available)
            dataBuffer.append(data)
            val dataStr = dataBuffer.toString()
            if (expect.matches(dataStr)) {
              log.info(s"Reached expected prompt $expect- negotiation complete ")
              replyTo ! DoneProcessing
              context become receive
            }

            else {
              discussionMap.find(_._1.matches(dataStr)) match {
                case Some(p) =>
                  log.info(s"Negotiating ${p._1} executing")
                  self ! SimpleCommand(p._2)
                case _ =>
                  dataBuffer.clear()
                  dataBuffer.append(dataStr.lines.toArray.last)
                  buffer.clear()
                  context.system.scheduler.scheduleOnce(1 seconds, self, DoProcess)
              }
            }

          case Failure(ex) =>
            ex match {
              case e =>
                log.error(s"Failed to listen to channel ${e.getMessage}", e)
                self ! Status.Failure(e)
            }
        }
      }

    case SimpleCommand(cmd) =>
      cnl.foreach { channel =>
        executeCommand(channel, cmd).retry(delays, 5, 300 millis).onComplete {
          case Success(i) =>
            log.info(s"executing command $cmd")
            self ! DoProcess
          case Failure(ex) =>
            log.error(s"Failed to execute $cmd", ex)
            self ! Status.Failure(ex)
        }
      }

    case other => throw new IOException(s"didn't expect $other in state processing")

  }

  def close: Receive = {
    case CloseChannel =>
      context stop self

  }
  case class ReadData(pipeTo: ActorRef, buffer: Buffer)

  case object DoProcess

}
