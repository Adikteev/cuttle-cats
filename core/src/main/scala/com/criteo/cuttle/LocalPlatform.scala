package com.criteo.cuttle

import java.nio.{ByteBuffer}

import com.zaxxer.nuprocess._

import java.util.UUID

import scala.concurrent.{Future, Promise}
import scala.concurrent.stm.{atomic, Ref}
import scala.collection.{SortedSet}
import scala.concurrent.ExecutionContext.Implicits.global

case class LocalPlatform[S <: Scheduling](maxTasks: Int)(implicit contextOrdering: Ordering[S#Context])
    extends ExecutionPlatform[S]
    with LocalPlatformApp[S] {
  private[cuttle] val running = Ref(Set.empty[(LocalProcess, Execution[S])])
  private[cuttle] val waiting = Ref(
    SortedSet.empty[(LocalProcess, Execution[S], () => Future[Unit], Promise[Unit])](Ordering.by(x =>
      (x._2.context, x._2.job.id))))

  private def runNext(): Unit = {
    val maybeToRun = atomic { implicit txn =>
      if (running().size < 1) {
        val maybeNext = waiting().headOption
        maybeNext.foreach {
          case x @ (l, e, _, _) =>
            running() = running() + (l -> e)
            waiting() = waiting() - x
        }
        maybeNext
      } else {
        None
      }
    }

    maybeToRun.foreach {
      case x @ (l, e, f, p) =>
        val fEffect = try { f() } catch {
          case t: Throwable =>
            Future.failed(t)
        }
        p.completeWith(fEffect)
        fEffect.andThen {
          case _ =>
            atomic { implicit txn =>
              running() = running() - (l -> e)
            }
            runNext()
        }
    }
  }

  private[cuttle] def runInPool(l: LocalProcess, e: Execution[S])(f: () => Future[Unit]): Future[Unit] = {
    val p = Promise[Unit]()
    val entry = (l, e, f, p)
    atomic { implicit txn =>
      waiting() = waiting() + entry
    }
    e.onCancelled(() => {
      atomic { implicit txn =>
        waiting() = waiting() - entry
      }
      p.tryFailure(ExecutionCancelled)
    })
    runNext()
    p.future
  }

}

object LocalPlatform {
  def fork(command: String) = new LocalProcess(new NuProcessBuilder("sh", "-c", command)) {
    override def toString = command
  }
}

trait LocalPlatformApp[S <: Scheduling] { self: LocalPlatform[S] =>

  import lol.http._
  import lol.json._

  import io.circe._
  import io.circe.syntax._

  import JsonApi._

  implicit val encoder = new Encoder[(LocalProcess, Execution[S])] {
    override def apply(x: (LocalProcess, Execution[S])) = x match {
      case (process, execution) =>
        Json.obj(
          "id" -> process.id.asJson,
          "command" -> process.toString.asJson,
          "execution" -> execution.toExecutionLog(ExecutionRunning).asJson
        )
    }
  }

  override lazy val routes: PartialService = {
    case GET at url"/api/local/tasks/running" =>
      Ok(this.running.single.get.toSeq.asJson)
    case GET at url"/api/local/tasks/waiting" =>
      Ok(this.waiting.single.get.toSeq.map(x => (x._1, x._2)).asJson)
  }
}

class LocalProcess(private val process: NuProcessBuilder) {
  val id = UUID.randomUUID().toString

  def exec[S <: Scheduling]()(implicit execution: Execution[S]): Future[Unit] = {
    val streams = execution.streams
    streams.debug(s"Waiting available resources to fork:")
    streams.debug(this.toString)
    streams.debug("...")

    ExecutionPlatform
      .lookup[LocalPlatform[S]]
      .getOrElse(sys.error("No local execution platform configured"))
      .runInPool(this, execution) { () =>
        streams.debug("Running")
        val result = Promise[Unit]()
        val handler = new NuAbstractProcessHandler() {
          override def onStdout(buffer: ByteBuffer, closed: Boolean) = {
            val bytes = Array.ofDim[Byte](buffer.remaining)
            buffer.get(bytes)
            streams.info(new String(bytes))
          }
          override def onStderr(buffer: ByteBuffer, closed: Boolean) = {
            val bytes = Array.ofDim[Byte](buffer.remaining)
            buffer.get(bytes)
            streams.error(new String(bytes))
          }
          override def onExit(statusCode: Int) =
            statusCode match {
              case 0 =>
                result.success(())
              case n =>
                result.failure(new Exception(s"Process exited with code $n"))
            }
        }
        process.setProcessListener(handler)
        val fork = process.start()
        execution.onCancelled(() => {
          fork.destroy(true)
        })
        result.future
      }
  }
}