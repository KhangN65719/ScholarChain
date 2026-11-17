package scholarchain.storage

import scala.collection.mutable

/** One received queue message. `receiveCount` is the queue's own count of
  * deliveries (SQS `ApproximateReceiveCount`), this delivery included.
  * `sentAtMillis` is the enqueue time when the queue reports it.
  */
final case class QueueMessage(
    messageId: String,
    receiptHandle: String,
    body: String,
    receiveCount: Int,
    sentAtMillis: Option[Long] = None
)

/** The small queue port the embedding worker needs. The SQS adapter is
  * `AwsSqsWorkQueue`; `InMemoryWorkQueue` is the local fake.
  */
trait WorkQueue:
  /** Long-polls for at most `maxMessages`, waiting at most `waitSeconds`, and
    * hides every returned message for `visibilitySeconds`.
    */
  def receive(
      maxMessages: Int,
      waitSeconds: Int,
      visibilitySeconds: Int
  ): Vector[QueueMessage]

  def delete(message: QueueMessage): Unit

  /** Sets the time until the message is visible again, from now. Zero releases
    * it at once.
    */
  def changeVisibility(message: QueueMessage, seconds: Int): Unit

  def close(): Unit = ()

object WorkQueues:
  val SqsAdapterClass = "scholarchain.storage.AwsSqsWorkQueue"

  /** Builds the SQS adapter by name. The adapter needs the AWS SDK `sqs`
    * artifact; looking it up by name keeps this module compiling and its tests
    * running while that artifact is not on the class path. A missing adapter is
    * reported by what is missing, never by a stack trace.
    */
  def sqs(clients: AwsClients, queueUrl: String): WorkQueue =
    val adapter =
      try Class.forName(SqsAdapterClass)
      catch
        case _: ClassNotFoundException | _: NoClassDefFoundError =>
          Fail(
            ErrorCode.DeferredAdapter,
            "The SQS adapter is not on the class path; it needs the AWS SDK " +
              "artifact software.amazon.awssdk:sqs"
          )
    try
      adapter
        .getConstructor(classOf[AwsClients], classOf[String])
        .newInstance(clients, queueUrl)
        .asInstanceOf[WorkQueue]
    catch
      case _: NoClassDefFoundError =>
        Fail(
          ErrorCode.DeferredAdapter,
          "The SQS adapter is on the class path but the AWS SDK artifact " +
            "software.amazon.awssdk:sqs is not"
        )

/** Local fake with SQS behaviour that matters to the worker: a received message
  * is hidden until its visibility passes, every delivery counts, and a message
  * that has been delivered `maxReceiveCount` times without a delete moves to
  * `deadLetters` on the next receive (the redrive policy, `maxReceiveCount` 3
  * in Terraform). `now` is the fake clock in milliseconds.
  */
final class InMemoryWorkQueue(
    now: () => Long,
    maxReceiveCount: Int = 3
) extends WorkQueue:
  private final case class Entry(
      id: String,
      body: String,
      sentAt: Long,
      var visibleAt: Long,
      var receives: Int,
      var handle: String
  )

  private val entries = mutable.ArrayBuffer.empty[Entry]
  private val dead = mutable.ArrayBuffer.empty[(String, String)]
  private var counter = 0

  def send(body: String): String = synchronized {
    counter += 1
    val id = s"msg-$counter"
    entries += Entry(id, body, now(), 0L, 0, "")
    id
  }

  /** Message IDs and bodies that moved to the dead-letter queue. */
  def deadLetters: Vector[(String, String)] = synchronized { dead.toVector }

  /** Messages still in the queue, visible or not. */
  def depth: Int = synchronized { entries.size }

  def receiveCountOf(messageId: String): Int = synchronized {
    entries.find(_.id == messageId).map(_.receives).getOrElse(0)
  }

  def receive(
      maxMessages: Int,
      waitSeconds: Int,
      visibilitySeconds: Int
  ): Vector[QueueMessage] = synchronized {
    val time = now()
    val due = entries.filter(_.visibleAt <= time)
    val (exhausted, live) = due.partition(_.receives >= maxReceiveCount)
    exhausted.foreach { entry =>
      dead += ((entry.id, entry.body))
      entries -= entry
    }
    live.take(maxMessages).toVector.map { entry =>
      entry.receives += 1
      counter += 1
      entry.handle = s"handle-${entry.id}-${entry.receives}-$counter"
      entry.visibleAt = time + visibilitySeconds * 1000L
      QueueMessage(
        entry.id,
        entry.handle,
        entry.body,
        entry.receives,
        Some(entry.sentAt)
      )
    }
  }

  def delete(message: QueueMessage): Unit = synchronized {
    entries.indexWhere(e => e.id == message.messageId) match
      case -1    => ()
      case index =>
        if entries(index).handle == message.receiptHandle then
          entries.remove(index)
  }

  def changeVisibility(message: QueueMessage, seconds: Int): Unit =
    synchronized {
      entries
        .find(e =>
          e.id == message.messageId && e.handle == message.receiptHandle
        )
        .foreach(_.visibleAt = now() + seconds * 1000L)
    }
