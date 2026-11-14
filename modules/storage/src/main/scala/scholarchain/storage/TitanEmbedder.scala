package scholarchain.storage

import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest

object TitanEmbedder:
  val ModelId = "amazon.titan-embed-text-v2:0"
  val Dimension = 256

  /** The value stored as `embeddingModel` and in the work manifest. It names
    * the model, the dimension and the normalization, so a change of any of the
    * three is a new model version and therefore a new index.
    */
  val ModelVersion = "amazon.titan-embed-text-v2:0-d256-norm"

/** `Embedder` over Bedrock Titan Text Embeddings V2: `dimensions` 256,
  * `normalize` true, one InvokeModel call per text.
  *
  * Throttling and 5xx are retried here with the given backoff (the ingest path
  * turns any `StorageException` into a rejected record and does not retry).
  * When the attempts run out the failure is `RetryExhausted`, a
  * `StorageException`. Empty text, a validation error, a wrong length and a
  * non-finite value are refused without a retry.
  */
final class TitanEmbedder(
    client: BedrockRuntimeClient,
    retry: RetryPolicy = RetryPolicy(5, 200, 5000),
    sleeper: Sleeper = ThreadSleeper
) extends Embedder:
  val modelVersion: String = TitanEmbedder.ModelVersion
  val dimension: Int = TitanEmbedder.Dimension

  private def requestBody(text: String): SdkBytes =
    SdkBytes.fromUtf8String(
      ujson.write(
        ujson.Obj(
          "inputText" -> text,
          "dimensions" -> dimension,
          "normalize" -> true
        )
      )
    )

  private def parse(body: String): Vector[Float] =
    val json =
      try ujson.read(body)
      catch
        case error: Exception =>
          Fail(
            ErrorCode.EmbeddingInvalid,
            s"Titan returned a body that is not JSON: ${error.getMessage}"
          )
    val values = json.objOpt
      .flatMap(_.get("embedding"))
      .flatMap(_.arrOpt)
      .getOrElse(
        Fail(ErrorCode.EmbeddingInvalid, "Titan response has no embedding")
      )
      .toVector
      .map {
        case ujson.Num(v) => v
        case _            =>
          Fail(ErrorCode.EmbeddingInvalid, "Titan embedding has a non-number")
      }
    Fail.unless(
      values.size == dimension,
      ErrorCode.EmbeddingInvalid,
      s"Titan returned ${values.size} values, expected $dimension"
    )
    Fail.unless(
      values.forall(v => !v.isNaN && !v.isInfinite),
      ErrorCode.EmbeddingInvalid,
      "Titan embedding has a non-finite value"
    )
    val floats = values.map(_.toFloat)
    Fail.unless(
      floats.forall(f => !f.isNaN && !f.isInfinite),
      ErrorCode.EmbeddingInvalid,
      "Titan embedding has a value outside the float range"
    )
    floats

  def embed(text: String): Vector[Float] =
    Fail.unless(
      text.trim.nonEmpty,
      ErrorCode.EmbeddingInvalid,
      "Cannot embed empty text"
    )
    Text.requireNoNul(text, "Embedding text")
    val request = InvokeModelRequest
      .builder()
      .modelId(TitanEmbedder.ModelId)
      .contentType("application/json")
      .accept("application/json")
      .body(requestBody(text))
      .build()
    var attempt = 1
    var result: Option[Vector[Float]] = None
    while result.isEmpty do
      try
        val response =
          AwsErrors.call("Bedrock InvokeModel")(client.invokeModel(request))
        result = Some(parse(response.body.asUtf8String))
      catch
        case error: TransientStorageException =>
          if attempt >= retry.maxAttempts then
            Fail(
              ErrorCode.RetryExhausted,
              s"Titan embedding failed after ${retry.maxAttempts} attempts: " +
                error.getMessage
            )
          sleeper.sleep(retry.delayAfter(attempt))
          attempt += 1
    result.get
