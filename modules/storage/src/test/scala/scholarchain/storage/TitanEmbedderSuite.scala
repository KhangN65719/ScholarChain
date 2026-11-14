package scholarchain.storage

import scala.collection.mutable
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.{
  InvokeModelRequest,
  InvokeModelResponse
}

class TitanEmbedderSuite extends munit.FunSuite:
  private val retry = RetryPolicy(3, 100, 1000)

  private final class RecordingSleeper extends Sleeper:
    val delays = mutable.ArrayBuffer.empty[Long]
    def sleep(millis: Long): Unit = delays += millis

  private def stub = new SdkStub(classOf[BedrockRuntimeClient])

  private def reply(body: String): InvokeModelResponse =
    InvokeModelResponse
      .builder()
      .body(SdkBytes.fromUtf8String(body))
      .contentType("application/json")
      .build()

  private def embeddingBody(values: Seq[Double]): String =
    ujson.write(
      ujson.Obj(
        "embedding" -> ujson.Arr.from(values.map(ujson.Num(_))),
        "inputTextTokenCount" -> 3
      )
    )

  private val good = embeddingBody(Seq.fill(256)(0.0625))

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  test("the request names the model, 256 dimensions and normalize true") {
    val s = stub.on("invokeModel")(_ => reply(good))
    val embedder = new TitanEmbedder(s.client, retry, new RecordingSleeper)
    val vector = embedder.embed("a summary of a researcher")
    assertEquals(vector.size, 256)
    assertEquals(vector.head, 0.0625f)
    val request =
      s.requests("invokeModel").head.asInstanceOf[InvokeModelRequest]
    assertEquals(request.modelId, "amazon.titan-embed-text-v2:0")
    assertEquals(request.contentType, "application/json")
    assertEquals(request.accept, "application/json")
    val sent = ujson.read(request.body.asUtf8String)
    assertEquals(sent("inputText").str, "a summary of a researcher")
    assertEquals(sent("dimensions").num, 256.0)
    assertEquals(sent("normalize").bool, true)
  }

  test("model version and dimension are fixed") {
    val embedder = new TitanEmbedder(stub.client)
    assertEquals(embedder.dimension, 256)
    assertEquals(
      embedder.modelVersion,
      "amazon.titan-embed-text-v2:0-d256-norm"
    )
  }

  test("empty or blank text is refused before any call") {
    val s = stub
    val embedder = new TitanEmbedder(s.client, retry, new RecordingSleeper)
    Vector("", "   ", "\n\t").foreach { text =>
      assertEquals(code(embedder.embed(text)), ErrorCode.EmbeddingInvalid)
    }
    assertEquals(s.names, Vector.empty)
  }

  test("a wrong length is refused") {
    Vector(255, 257, 0).foreach { n =>
      val s =
        stub.on("invokeModel")(_ => reply(embeddingBody(Seq.fill(n)(0.5))))
      val embedder = new TitanEmbedder(s.client, retry, new RecordingSleeper)
      assertEquals(code(embedder.embed("x")), ErrorCode.EmbeddingInvalid)
    }
  }

  test("a non-finite or non-numeric value is refused") {
    val huge = "[" + Vector.fill(255)("0.5").mkString(",") + ",1e999]"
    val bodies = Vector(
      s"""{"embedding":$huge}""",
      "{\"embedding\":[" + Vector.fill(255)("0.5").mkString(",") + ",\"x\"]}",
      """{"embedding":null}""",
      """{"other":1}""",
      "not json"
    )
    bodies.foreach { body =>
      val s = stub.on("invokeModel")(_ => reply(body))
      val embedder = new TitanEmbedder(s.client, retry, new RecordingSleeper)
      assertEquals(code(embedder.embed("x")), ErrorCode.EmbeddingInvalid)
    }
  }

  test("throttling is retried with backoff, then the embedding succeeds") {
    var calls = 0
    val s = stub.on("invokeModel") { _ =>
      calls += 1
      if calls <= 2 then throw SdkErrors.error(429, "ThrottlingException")
      else reply(good)
    }
    val sleeper = new RecordingSleeper
    val vector = new TitanEmbedder(s.client, retry, sleeper).embed("x")
    assertEquals(vector.size, 256)
    assertEquals(calls, 3)
    assertEquals(sleeper.delays.toVector, Vector(100L, 200L))
  }

  test(
    "exhausted retries are a StorageException the ingest turns into a reject"
  ) {
    val s = stub.on("invokeModel")(_ =>
      throw SdkErrors.error(503, "ServiceUnavailableException")
    )
    val sleeper = new RecordingSleeper
    val error = intercept[StorageException](
      new TitanEmbedder(s.client, retry, sleeper).embed("x")
    )
    assertEquals(error.code, ErrorCode.RetryExhausted)
    assertEquals(s.requests("invokeModel").size, 3)
    assertEquals(sleeper.delays.toVector, Vector(100L, 200L))
  }

  test("a validation error is not retried") {
    val s = stub.on("invokeModel")(_ =>
      throw SdkErrors.error(400, "ValidationException")
    )
    val sleeper = new RecordingSleeper
    assertEquals(
      code(new TitanEmbedder(s.client, retry, sleeper).embed("x")),
      ErrorCode.AwsRejected
    )
    assertEquals(s.requests("invokeModel").size, 1)
    assertEquals(sleeper.delays.toVector, Vector.empty)
  }

  test("an access-denied error is not retried") {
    val s = stub.on("invokeModel")(_ =>
      throw SdkErrors.error(403, "AccessDeniedException")
    )
    assertEquals(
      code(new TitanEmbedder(s.client, retry, new RecordingSleeper).embed("x")),
      ErrorCode.AwsAccessDenied
    )
    assertEquals(s.requests("invokeModel").size, 1)
  }
