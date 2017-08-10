package com.indeni.ssh

import com.indeni.ssh.common.IndTypes.ChannelId
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.{ClientFactoryManager, SshClient}
import org.apache.sshd.common.PropertyResolverUtils
import org.apache.sshd.common.future.SshFutureListener
import org.apache.sshd.common.io.IoWriteFuture
import org.apache.sshd.common.util.buffer.ByteArrayBuffer

import scala.concurrent.{ExecutionContext, Future, Promise}



trait SshdClient2 extends ChannelHelper{
  implicit val ec:ExecutionContext


  def createClient(workers: Int, keepAliveTime: String, keepAliveRequest: String): SshClient = {
    val client: SshClient = SshClient.setUpDefaultClient()
    client.setNioWorkers(workers)
    client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY)
    PropertyResolverUtils.updateProperty(client, ClientFactoryManager.HEARTBEAT_INTERVAL, keepAliveTime)
    PropertyResolverUtils.updateProperty(client, ClientFactoryManager.HEARTBEAT_REQUEST, keepAliveRequest)
    client.start()
    client
  }



  def executeCommand(cnl : Channel, command: String): Future[ChannelId] = {

    val channel = cnl.channelShell
    val id = cnl.id
    val p = Promise[ChannelId]()
    channel.getAsyncIn
      .write(new ByteArrayBuffer(command.getBytes()))
      .addListener{
        new SshFutureListener[IoWriteFuture] {
          override def operationComplete(future: IoWriteFuture): Unit = {
            try {
              if (future.isWritten)
                p.success(id)
              else
                p.failure(future.getException)
            } catch {
              case e: Exception =>
                if (!channel.isClosing)
                  channel.close(false)
                p.failure(e)
            }
          }
        }
      }
    p.future
  }



}
