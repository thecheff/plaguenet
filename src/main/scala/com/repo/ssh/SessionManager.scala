package com.indeni.ssh

import java.io.IOException

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import com.indeni.ssh.Trainer.{StartTraining, TrainingSessionComplete}
import com.indeni.ssh.common.{Command, Prompt}
import com.indeni.ssh.utils.FutureOps
import com.indeni.ssh.utils.Randoms._
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object SessionManager extends SessionHelper {
  def props(client: SshClient, numOfExecutors: Int = 1) = Props(new SessionManager(client, numOfExecutors))

  import SessionHelper._

  /**
    *
    * @param target
    * @param forceAuth
    * @param expectedPrompt - getting this prompt means that this is the final command prompt and
    *                       now it's read to run the next command
    */
  case class StartSession(target: SshTarget, forceAuth: Boolean, expectedPrompt: Prompt)

  case class StartTrainingSession(target: SshTarget, forceAuth: Boolean)

  case class SessionCreationFailed(exception: Throwable)

  case class TrainingComplete(prompts: List[String])

  //events
  case object SessionStarted

}

/**
  *
  * @param client
  * @param numOfExecutors - number of channel to run in parallel
  */
class SessionManager(client: SshClient, numOfExecutors: Int) extends Actor with SshdClient2 with SessionHelper with FutureOps with ActorLogging {
  override implicit val ec: ExecutionContext = context.dispatcher

  import SessionManager._

  val commandsQueue = mutable.Queue.empty[CommandToExecute]

  def isValid(session: ClientSession): Boolean = !session.isClosed && !session.isClosing

  override def receive: Receive = init()

  def init(): Receive = {
    case StartTrainingSession(target, forceAuth)  =>

      val origSender = sender()
      val authenticatedSession: Future[ClientSession] = for {
        session <- createSession(client, target.credentials.username, target.address, target.port)
        authSession <- session.authenticate(target.credentials)
      } yield authSession

      authenticatedSession.onComplete {
        case Success(session) =>
          context become training(session, origSender)
          self forward StartTrainingSession(target, forceAuth)

        case Failure(e) =>
          origSender ! SessionCreationFailed(e)
      }

    case StartSession(target, forceAuth, prompts) =>
      val origSender = sender()
      val authenticatedSession: Future[ClientSession] = for {
        session <- createSession(client, target.credentials.username, target.address, target.port)
        authSession <- session.authenticate(target.credentials)
      } yield authSession

      authenticatedSession.onComplete {
        case Success(session) =>
          context.become(readyForCommand(prompts, session))
          origSender ! SessionStarted

        case Failure(e) =>
          origSender ! SessionCreationFailed(e)
      }

    case other =>
      throw new IOException(s"didn't expect $other in state init")
  }

  def training(session:ClientSession, pipeTo: ActorRef): Receive = {
    case StartTrainingSession(target, forceAuth) =>
      createChannel(uuid, session).foreach { channel =>
        val child = context.actorOf(Trainer.props(channel), s"Trainer-${channel.id}")
        child ! StartTraining(self, 10)
      }

    case TrainingSessionComplete( prompt) =>
      pipeTo ! TrainingComplete(prompt.fold(List.empty[String])(_.prompts))
      context become init

    case other =>
      throw new IOException(s"didn't expect $other in state training")
  }

  def readyForCommand(expectedPrompt: Prompt, session: ClientSession): Receive = {

    case ec: Command =>
      log.info(s"Got command $ec")
      commandsQueue.enqueue(CommandToExecute(ec, sender))
      self ! Execute
    case Execute =>
      if (context.children.size < numOfExecutors && commandsQueue.nonEmpty) {
        val child = context.actorOf(CommandExecutor.props(session, expectedPrompt), s"Executor-$uuid")
        context.watch(child)
        val exe = commandsQueue.dequeue
        log.info(s"Sending $exe to ${child.path.name}")
        child tell(exe.command, exe.pipeTo)
      }

    case Terminated(child) =>
      self ! Execute

    case other =>
      throw new IOException(s"didn't expect $other  in state readingFromChannel")

  }

  case class CommandToExecute(command: Command, pipeTo: ActorRef)

  case object Execute
}
