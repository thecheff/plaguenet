package com.repo.plague

import javafx.concurrent.Worker

import akka.actor.{Actor, Props, Terminated}
import akka.routing.{ActorRefRoutee, Router}

import scala.collection.immutable
import akka.routing.RoundRobinRoutingLogic
import akka.routing.RoutingLogic
import akka.routing.Routee
import akka.routing.SeveralRoutees
import com.sun.corba.se.spi.orbutil.threadpool.Work
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}

case class RedundancyRoutingLogic(nbrCopies: Int) extends RoutingLogic {
  val roundRobin = RoundRobinRoutingLogic()
  def select(message: Any, routees: immutable.IndexedSeq[Routee]): Routee = {
    val targets = (1 to nbrCopies).map(_ => roundRobin.select(message, routees))
    SeveralRoutees(targets)
  }
}

class Map[A, B](f: A => B) extends GraphStage[FlowShape[A, B]] { //Rebuild file using map structure, AYNC drop TODO: ADD TEST GROUP
                                                                 //Map the entire file to a graph, then write from pre-sorted group
  val info = new Info {
    override val _installDir: String = _
  }
  val file: String = info.installDir
  val in: Inlet[A] = Inlet[A](file)
  val out: Outlet[B] = Outlet[B](file)

  override val shape: FlowShape[A, B] = FlowShape.of(in, out)

  override def createLogic(attr: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          push(out, f(grab(in)))
        }
      })
      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
    }
}
class Master extends Actor {
  var router: Router = {
    val routees = Vector.fill(5) {
      val r = context.actorOf(Props[Worker])
      context watch r
      ActorRefRoutee(r)
    }
    Router(RedundancyRoutingLogic(1), routees)
  } //TODO: GET ACTUAL ROUTING #

  def receive: PartialFunction[Any, Unit] = {
    case w: Work =>
      router.route(w, sender())
    case Terminated(a) =>
      router = router.removeRoutee(a)
      val r = context.actorOf(Props[Worker])
      context watch r
      router = router.addRoutee(r)
  }
}


