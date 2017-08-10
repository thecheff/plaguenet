package com.indeni.ssh

import java.io.IOException

import com.indeni.ssh.common.IndTypes._
import org.apache.sshd.client.channel.{ChannelShell, ClientChannel}
import org.apache.sshd.client.future.OpenFuture
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.future.{CloseFuture, SshFutureListener}
import org.apache.sshd.common.io.IoReadFuture
import org.apache.sshd.common.util.buffer.Buffer

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Try}
case class Channel(id: ChannelId, channelShell: ChannelShell)

trait ChannelHelper {
  def createChannel(id: ChannelId, session: ClientSession): Future[Channel] = {
    val p = Promise[Channel]()
    try {
      val channel = session.createShellChannel
      channel.setStreaming(ClientChannel.Streaming.Async)
      channel.open.addListener {
        new SshFutureListener[OpenFuture] {
          override def operationComplete(future: OpenFuture): Unit = {
            val tryChannel = Try(future.verify).recoverWith {
              case e =>
                Failure(e)
            }.map(_ => Channel(id, channel))
            p.complete(tryChannel)
          }
        }
      }
    } catch {
      case NonFatal(e) =>
        p.failure(e)
    }
    p.future

  }

  implicit class ChannelImplicits(cnl: Channel) {
    def listenOnChannelOut( buf: Buffer): Future[Buffer] = {
      val p = Promise[Buffer]()
      val channelShell = cnl.channelShell

      channelShell.getAsyncOut.read(buf).addListener {
        new SshFutureListener[IoReadFuture] {
          override def operationComplete(future: IoReadFuture): Unit = {
            try {
                future.verify()
                val buffer = future.getBuffer
                p.success(buffer)

            } catch {
              case NonFatal(e) =>
                e.printStackTrace()
                if (!channelShell.isClosing)
                  channelShell.close(false)
                p.failure(e)
            }
          }
        }

      }
      p.future
    }

    def closeChannel(immediate: Boolean): Future[Boolean] = {
      val p = Promise[Boolean]()
      val channel = cnl.channelShell
      if (channel.isOpen) {
        channel.close(immediate).addListener {
          new SshFutureListener[CloseFuture] {
            override def operationComplete(future: CloseFuture): Unit = {
              if (future.isClosed)
                p.success(true)
              else
                p.failure(new IOException(s"could not close channel ${cnl.id}"))
            }
          }
        }
      } else {
        p.success(true)
      }
      p.future
    }
  }

}
