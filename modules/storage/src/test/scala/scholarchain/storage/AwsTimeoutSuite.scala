package scholarchain.storage

import java.net.URI
import java.time.Duration
import scala.collection.mutable
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException
}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse

/** Work order 85 item 2: SDK deadlines on every client, and a timed-out call
  * classified as transient. Nothing here reaches AWS: building a client opens
  * no connection, and the embedder runs against a stubbed client.
  */
class AwsTimeoutSuite extends munit.FunSuite:
  private def attemptTimeout =
    ApiCallAttemptTimeoutException.create(60000L)
  private def callTimeout = ApiCallTimeoutException.create(180000L)

  private def seconds(d: Option[Duration]): Option[Long] = d.map(_.getSeconds)

  private def optional[A](o: java.util.Optional[A]): Option[A] =
    if o.isPresent then Some(o.get) else None

  private def deadlines(
      config: ClientOverrideConfiguration
  ): (Option[Long], Option[Long]) =
    (
      seconds(optional(config.apiCallAttemptTimeout)),
      seconds(optional(config.apiCallTimeout))
    )

  test(
    "the timeout constants are 60 s and 180 s for Bedrock, 60 s and 120 s otherwise"
  ) {
    assertEquals(AwsTimeouts.BedrockAttempt, Duration.ofSeconds(60))
    assertEquals(AwsTimeouts.BedrockCall, Duration.ofSeconds(180))
    assertEquals(AwsTimeouts.StandardAttempt, Duration.ofSeconds(60))
    assertEquals(AwsTimeouts.StandardCall, Duration.ofSeconds(120))
    assertEquals(deadlines(AwsTimeouts.Bedrock), (Some(60L), Some(180L)))
    assertEquals(deadlines(AwsTimeouts.Standard), (Some(60L), Some(120L)))
  }

  test("every built client carries both deadlines") {
    val clients = new AwsClients(Region.US_EAST_2)
    val s3 = clients.s3()
    val vectors = clients.s3Vectors()
    val bedrock = clients.bedrockRuntime()
    val neptune =
      clients.neptune(URI.create("https://neptune.invalid:8182"))
    val dynamo = clients.dynamo()
    try
      val built = Vector(
        "s3" -> s3.serviceClientConfiguration.overrideConfiguration,
        "s3Vectors" -> vectors.serviceClientConfiguration.overrideConfiguration,
        "bedrockRuntime" ->
          bedrock.serviceClientConfiguration.overrideConfiguration,
        "neptune" -> neptune.serviceClientConfiguration.overrideConfiguration,
        "dynamo" -> dynamo.serviceClientConfiguration.overrideConfiguration
      ).toMap
      assertEquals(built.size, 5)
      built.foreach { (name, config) =>
        val expected =
          if name == "bedrockRuntime" then (Some(60L), Some(180L))
          else (Some(60L), Some(120L))
        assertEquals(deadlines(config), expected, name)
      }
    finally
      Vector[AutoCloseable](s3, vectors, bedrock, neptune, dynamo)
        .foreach(_.close())
  }

  test("an attempt timeout and a call timeout are transient") {
    Vector(attemptTimeout, callTimeout).foreach { timeout =>
      val error = intercept[TransientStorageException](
        AwsErrors.call("Bedrock InvokeModel")(throw timeout)
      )
      assert(error.getMessage.contains("Bedrock InvokeModel"), error.getMessage)
      assertEquals(error.getCause, timeout)
    }
  }

  test(
    "a client failure that is not a timeout or an I/O error stays rejected"
  ) {
    val noRegion = SdkClientException.create("Unable to load region")
    val error = intercept[StorageException](
      AwsErrors.call("S3 GetObject")(throw noRegion)
    )
    assertEquals(error.code, ErrorCode.AwsRejected)
  }

  private final class RecordingSleeper extends Sleeper:
    val delays = mutable.ArrayBuffer.empty[Long]
    def sleep(millis: Long): Unit = delays += millis

  private def reply(): InvokeModelResponse =
    InvokeModelResponse
      .builder()
      .body(
        SdkBytes.fromUtf8String(
          ujson.write(
            ujson.Obj(
              "embedding" -> ujson.Arr.from(Seq.fill(256)(ujson.Num(0.0625)))
            )
          )
        )
      )
      .build()

  test(
    "a timed-out embed is retried five times, then fails with RetryExhausted"
  ) {
    val stub = new SdkStub(classOf[BedrockRuntimeClient])
      .on("invokeModel")(_ => throw attemptTimeout)
    val sleeper = new RecordingSleeper
    val error = intercept[StorageException](
      new TitanEmbedder(stub.client, RetryPolicy(5, 200, 5000), sleeper)
        .embed("a summary")
    )
    assertEquals(error.code, ErrorCode.RetryExhausted)
    assertEquals(stub.requests("invokeModel").size, 5)
    assertEquals(sleeper.delays.toVector, Vector(200L, 400L, 800L, 1600L))
    assert(error.getMessage.contains("5 attempts"), error.getMessage)
  }

  test("a timed-out embed that recovers is retried and succeeds") {
    var calls = 0
    val stub = new SdkStub(classOf[BedrockRuntimeClient]).on("invokeModel") {
      _ =>
        calls += 1
        if calls == 1 then throw callTimeout else reply()
    }
    val sleeper = new RecordingSleeper
    val vector =
      new TitanEmbedder(stub.client, RetryPolicy(5, 200, 5000), sleeper)
        .embed("a summary")
    assertEquals(vector.size, 256)
    assertEquals(calls, 2)
    assertEquals(sleeper.delays.toVector, Vector(200L))
  }
