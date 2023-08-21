package followers

import akka.NotUsed
import akka.event.Logging
import akka.stream.scaladsl.{BroadcastHub, Flow, Framing, Keep, MergeHub, Sink, Source}
import akka.stream.{ActorAttributes, Materializer}
import akka.util.ByteString
import followers.model.Event.{Follow, Unfollow, Broadcast, PrivateMsg, StatusUpdate}
import followers.model.{Event, Followers, Identity}

import scala.collection.immutable.{Queue, SortedSet}
import scala.concurrent.{ExecutionContext, Future, Promise}

object Server extends ServerModuleInterface:

  val reframedFlow: Flow[ByteString, String, NotUsed] =
    Framing
      .delimiter(ByteString("\n"), maximumFrameLength = 999, allowTruncation = false)
      .map(_.utf8String)

  val eventParserFlow: Flow[ByteString, Event, NotUsed] =
    reframedFlow
      .map(Event.parse(_))

  val identityParserSink: Sink[ByteString, Future[Identity]] =
    reframedFlow
      .map(Identity.parse(_))
      .toMat(Sink.head)(Keep.right)

  val reintroduceOrdering: Flow[Event, Event, NotUsed] =
    Flow[Event].statefulMapConcat(() => {

      var nextSeqNumber = 1;
      var reorderingBuffer = Map[Int, Event]()

      def extractOrderedSequence(expectedSequenceNr: Int, orderedSequence: Queue[Event]) : Queue[Event] =
        if (reorderingBuffer.contains(expectedSequenceNr)) {
          val nextEvent = reorderingBuffer(expectedSequenceNr)
          reorderingBuffer -= expectedSequenceNr
          nextSeqNumber = expectedSequenceNr + 1
          extractOrderedSequence(expectedSequenceNr + 1, orderedSequence.enqueue(nextEvent))
        } else {
          orderedSequence
        }

      event => {
        reorderingBuffer += (event.sequenceNr -> event)
        extractOrderedSequence(nextSeqNumber, Queue.empty)
      }
    })

  val followersFlow: Flow[Event, (Event, Followers), NotUsed] =
    Flow[Event].statefulMapConcat(() => {

      var followers: Followers = Map().withDefault(k => Set())

      e => e match  {
        case Follow(_, fromUserId, toUserId) => {
          followers += (fromUserId -> (followers(fromUserId) + toUserId))
          (e, followers)::Nil
        }
        case Unfollow(_, fromUserId, toUserId) => {
          followers += (fromUserId -> (followers(fromUserId) - toUserId))
          (e, followers)::Nil
        }
        case _ => {
          (e, followers)::Nil
        }
      }
    })
  def isNotified(userId: Int)(eventAndFollowers: (Event, Followers)): Boolean = eventAndFollowers._1  match  {
    case Follow(_, fromUserId, toUserId) =>  toUserId == userId
    case Unfollow(_, fromUserId, toUserId) =>  false
    case Broadcast(_) => true
    case PrivateMsg(_, fromUserId, toUserId) =>  toUserId == userId
    case StatusUpdate(_, fromUserId) => eventAndFollowers._2.getOrElse(userId, Set()).contains(fromUserId)
  }

  // Utilities to temporarily have unimplemented parts of the program
  private def unimplementedFlow[A, B, C]: Flow[A, B, C] =
    Flow.fromFunction[A, B](_ => ???).mapMaterializedValue(_ => ??? : C)

  private def unimplementedSink[A, B]: Sink[A, B] = Sink.ignore.mapMaterializedValue(_ => ??? : B)

class Server(using ExecutionContext, Materializer)
  extends ServerInterface with ExtraStreamOps:
  import Server.*

  val (inboundSink, broadcastOut) =

    val flow: Flow[ByteString, (Event, Followers), NotUsed] =
      eventParserFlow
        .via(reintroduceOrdering)
        .via(followersFlow)

    // Wires the MergeHub and the BroadcastHub together and runs the graph
    MergeHub.source[ByteString](256)
      .via(flow)
      .toMat(BroadcastHub.sink(256))(Keep.both)
      .withAttributes(ActorAttributes.logLevels(Logging.DebugLevel, Logging.DebugLevel, Logging.DebugLevel))
      .run()

  val eventsFlow: Flow[ByteString, Nothing, NotUsed] =
    Flow.fromSinkAndSourceCoupled(inboundSink, Source.maybe)

  def outgoingFlow(userId: Int): Source[ByteString, NotUsed] =
    broadcastOut
      .filter(isNotified(userId))
      .map(_._1.render)

  def clientFlow(): Flow[ByteString, ByteString, NotUsed] =
    val promise = Promise[Identity]()
    val incoming: Sink[ByteString, NotUsed] =
      identityParserSink.mapMaterializedValue( v => {
        v.onComplete(promise.complete(_))
        NotUsed
      })

    val outgoing = Source.futureSource(promise.future.map { identity =>
      outgoingFlow(identity.userId)
    })

    Flow.fromSinkAndSource(incoming, outgoing)