package protocols

import akka.actor.typed.*
import akka.actor.typed.scaladsl.*
import akka.actor.typed.scaladsl.Behaviors.receiveMessagePartial

import scala.concurrent.duration.*

object Transactor:

  sealed trait PrivateCommand[T] extends Product with Serializable
  final case class Committed[T](session: ActorRef[Session[T]], value: T) extends PrivateCommand[T]
  final case class RolledBack[T](session: ActorRef[Session[T]]) extends PrivateCommand[T]

  sealed trait Command[T] extends PrivateCommand[T]
  final case class Begin[T](replyTo: ActorRef[ActorRef[Session[T]]]) extends Command[T]

  sealed trait Session[T] extends Product with Serializable
  final case class Extract[T, U](f: T => U, replyTo: ActorRef[U]) extends Session[T]
  final case class Modify[T, U](f: T => T, id: Long, reply: U, replyTo: ActorRef[U]) extends Session[T]
  final case class Commit[T, U](reply: U, replyTo: ActorRef[U]) extends Session[T]
  final case class Rollback[T]() extends Session[T]

  def apply[T](value: T, sessionTimeout: FiniteDuration): Behavior[Command[T]] =
    SelectiveReceive(30, idle[T](value, sessionTimeout).narrow)

  private def idle[T](value: T, sessionTimeout: FiniteDuration): Behavior[PrivateCommand[T]] =
    Behaviors.receive{
      case (ctx, Begin(replyTo)) => {
        val newSession = ctx.spawnAnonymous(sessionHandler[T](value, ctx.self, Set()))
        ctx.scheduleOnce(sessionTimeout, ctx.self, RolledBack(newSession))
        ctx.watchWith(newSession, RolledBack(newSession))
        replyTo ! newSession
        inSession(value, sessionTimeout, newSession)
      }
      case _ =>  Behaviors.same
    }

  private def inSession[T](rollbackValue: T,
                           sessionTimeout: FiniteDuration,
                           sessionRef: ActorRef[Session[T]]): Behavior[PrivateCommand[T]] = Behaviors.receivePartial{
    case (ctx, Committed(session, newValue)) => {
      if (sessionRef == session) {
        idle(newValue, sessionTimeout)
      } else {
        Behaviors.same
      }
    }
    case (ctx, RolledBack(session)) => {
      if (sessionRef == session) {
        ctx.stop(sessionRef)
        idle(rollbackValue, sessionTimeout)
      } else {
        Behaviors.same
      }
    }
  }

  private def sessionHandler[T](currentValue: T,
                                commit: ActorRef[Committed[T]],
                                done: Set[Long]): Behavior[Session[T]] = Behaviors.receive {
    case (ctx, Extract(f, replyTo)) => {
      replyTo ! f(currentValue)
      Behaviors.same
    }
    case (ctx, Modify(f, id, reply, replyTo)) => {
      if (!done.contains(id)) {
        val value = f(currentValue)
        replyTo ! reply
        sessionHandler(value, commit, done + id)
      } else {
        replyTo ! reply
        Behaviors.same
      }
    }
    case (ctx, Commit(reply, replyTo)) => {
      commit ! Committed(ctx.self, currentValue)
      replyTo ! reply
      Behaviors.stopped
    }
    case (ctx, Rollback()) => {
      Behaviors.stopped
    }
  }