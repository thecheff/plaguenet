package com.indeni.ssh

import java.io.IOException

import com.indeni.ssh.utils.KeyPairUtils
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.future.{AuthFuture, ConnectFuture}
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.future.{CloseFuture, SshFutureListener}
import org.apache.sshd.common.io.IoSession

import scala.concurrent.{Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

object SessionHelper{
  case class Credentials(username: String,
                         password: Option[String],
                         privateKey: Option[String],
                         passphrase: Option[String])

  case class SshTarget(address: String, port: Int, credentials: Credentials)
}

trait SessionHelper {

  def createSession(client: SshClient, username: String, host: String, port: Int): Future[ClientSession] = {
    val p = Promise[ClientSession]()
    Try {
      client.connect(username, host, port).addListener {
        new SshFutureListener[ConnectFuture] {
          override def operationComplete(future: ConnectFuture) = {
            if (future.isConnected) {
              val session: ClientSession = future.getSession
              val ioSession: IoSession = session.getIoSession
              if (ioSession.isOpen) {
                p.success(session)
              } else {
                p.failure(new IOException("Failed to open session ", future.getException))
              }
            }
            else
              p.failure(new IOException("Could not connect to client ", future.getException))
          }
        }
      }
    } recover {
      case NonFatal(e) =>
        p.failure(e)
    }
    p.future
  }

  implicit class SessionImplicits(session: ClientSession) {

    import SessionHelper._
    def authenticate(credentials: Credentials): Future[ClientSession] = {
      val p = Promise[ClientSession]()

      credentials.password.foreach(pwd => session.addPasswordIdentity(pwd))
      credentials.privateKey.foreach { pk =>
        KeyPairUtils
          .createKeyPair(credentials.privateKey.get, credentials.passphrase)
          .foreach(kp => session.addPublicKeyIdentity(kp))
      }
      try {
        session.auth.addListener {
          new SshFutureListener[AuthFuture] {
            override def operationComplete(future: AuthFuture): Unit = {
              try {
                future.verify()
                p.success(session)
              } catch {
                case NonFatal(e) =>
                  p.failure(e)
              }
            }
          }
        }
      } catch {
        case e: Exception => {
          p.failure(e)
        }
      }
      p.future
    }

    def closeSession(immediate: Boolean): Future[Boolean] = {
      val p = Promise[Boolean]()
      session.close(immediate).addListener {
        new SshFutureListener[CloseFuture] {
          override def operationComplete(future: CloseFuture): Unit = {
            if (future.isClosed)
              p.success(true)
            else
              p.failure(new IOException(s"could not close session $session"))
          }
        }
      }
      p.future
    }
  }

}
