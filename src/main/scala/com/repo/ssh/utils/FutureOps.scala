package com.repo.ssh.utils

import java.util.concurrent.TimeoutException

import akka.actor.Scheduler
import akka.pattern.after

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try


trait FutureOps {

  /**
    *
    * @param f
    * @param delay
    * @param retries
    * @param ec
    * @param s
    * @tparam T
    * @return
    * @example
    * retry(future, 100 millis, 3)
    */
  def retry[T](f: => Future[T], delay: FiniteDuration, retries: Int)(implicit ec: ExecutionContext, s: Scheduler): Future[T] = {
    f recoverWith { case _ if retries > 0 => after(delay, s)(retry(f, delay, retries - 1 )) }
  }


  def retry[T](f: => Future[T], delay: Seq[FiniteDuration], retries: Int, defaultDelay: FiniteDuration )(implicit ec: ExecutionContext, s: Scheduler): Future[T] = {
    f recoverWith {
      case _ if retries > 0 => after(delay.headOption.getOrElse(defaultDelay), s)(retry(f, Try {
        delay.tail
      }.getOrElse(List(defaultDelay)), retries - 1, defaultDelay))
    }
  }


  def retry[T](f: => Future[T], delay: FiniteDuration, retries: Int, timeout: FiniteDuration )(implicit ec: ExecutionContext, s: Scheduler): Future[T] = {
    val timeoutFuture: Future[Nothing] = after(timeout, s) { Future.failed(new TimeoutException("timed out")) }
    Future.firstCompletedOf(Seq(f,timeoutFuture)) recoverWith {
      case e:TimeoutException =>
        Future.failed(e)
      case _ if retries > 0 =>
      after(delay, s)(retry(f, delay, retries - 1 , timeout))
    }
  }


  implicit class FutureImplicits[T](f: => Future[T]) extends FutureOps {

    def retry(delay: FiniteDuration, retries: Int)(implicit ec: ExecutionContext, s: Scheduler): Future[T] = super.retry(f, delay, retries)

    def retry(delay: FiniteDuration, retries: Int, timeout: FiniteDuration)(implicit ec: ExecutionContext, s: Scheduler): Future[T] = super.retry(f, delay, retries, timeout)

    def retry(delay: Seq[FiniteDuration], retries: Int, defaultDelay: FiniteDuration)(implicit
    ec: ExecutionContext, s: Scheduler): Future[T] = super.retry(f, delay, retries, defaultDelay)


  }

}