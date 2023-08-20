package protocols

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.*

import scala.reflect.ClassTag

object SelectiveReceive:
  def apply[T: ClassTag](bufferCapacity: Int, initialBehavior: Behavior[T]): Behavior[T] = {

    if (bufferCapacity == 0) {
      Behaviors.same
    }

    try {
      Behaviors.withStash(bufferCapacity)(intercept(bufferCapacity, _, Behavior.validateAsInitial(initialBehavior)))
    } catch {
      case _: IllegalArgumentException => initialBehavior
    }
  }


  private def intercept[T: ClassTag](bufferSize: Int, buffer: StashBuffer[T], started: Behavior[T]): Behavior[T] =
    Behaviors.receive { case (ctx, message) =>
      val nxtBehavior = Behavior.interpretMessage(started, ctx, message)
      if (Behavior.isUnhandled(nxtBehavior)) {
        buffer.stash(message)
        Behaviors.same
      } else {
        buffer.unstashAll(SelectiveReceive(bufferSize, Behavior.canonicalize(nxtBehavior, started, ctx)))
      }
    }