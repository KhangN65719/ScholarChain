package scholarchain.storage

import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{
  ChangeMessageVisibilityRequest,
  DeleteMessageRequest,
  MessageSystemAttributeName,
  ReceiveMessageRequest
}

/** `WorkQueue` over one SQS queue. It reuses the `AwsClients` Region,
  * credentials chain and HTTP client, and the standard bounded timeouts. The
  * long-poll wait (at most 20 s) is shorter than the 60 s attempt deadline.
  * Failures go through `AwsErrors.call`, so throttling and 5xx are transient.
  * `WorkQueues.sqs` finds this class by name.
  */
final class AwsSqsWorkQueue(clients: AwsClients, queueUrl: String)
    extends WorkQueue:
  Fail.unless(
    queueUrl.trim.nonEmpty,
    ErrorCode.InvalidKey,
    "A queue URL is required"
  )

  private val client: SqsClient =
    SqsClient
      .builder()
      .region(clients.region)
      .credentialsProvider(clients.credentials)
      .httpClient(clients.httpClient)
      .overrideConfiguration(AwsTimeouts.Standard)
      .build()

  def receive(
      maxMessages: Int,
      waitSeconds: Int,
      visibilitySeconds: Int
  ): Vector[QueueMessage] =
    val response = AwsErrors.call("SQS ReceiveMessage")(
      client.receiveMessage(
        ReceiveMessageRequest
          .builder()
          .queueUrl(queueUrl)
          .maxNumberOfMessages(maxMessages)
          .waitTimeSeconds(waitSeconds)
          .visibilityTimeout(visibilitySeconds)
          .messageSystemAttributeNames(
            MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
            MessageSystemAttributeName.SENT_TIMESTAMP
          )
          .build()
      )
    )
    response.messages.asScala.toVector.map { m =>
      val attributes = m.attributes.asScala
      QueueMessage(
        m.messageId,
        m.receiptHandle,
        m.body,
        attributes
          .get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
          .flatMap(_.toIntOption)
          .getOrElse(1),
        attributes
          .get(MessageSystemAttributeName.SENT_TIMESTAMP)
          .flatMap(_.toLongOption)
      )
    }

  def delete(message: QueueMessage): Unit =
    AwsErrors.call("SQS DeleteMessage")(
      client.deleteMessage(
        DeleteMessageRequest
          .builder()
          .queueUrl(queueUrl)
          .receiptHandle(message.receiptHandle)
          .build()
      )
    )
    ()

  def changeVisibility(message: QueueMessage, seconds: Int): Unit =
    AwsErrors.call("SQS ChangeMessageVisibility")(
      client.changeMessageVisibility(
        ChangeMessageVisibilityRequest
          .builder()
          .queueUrl(queueUrl)
          .receiptHandle(message.receiptHandle)
          .visibilityTimeout(seconds)
          .build()
      )
    )
    ()

  override def close(): Unit = client.close()
