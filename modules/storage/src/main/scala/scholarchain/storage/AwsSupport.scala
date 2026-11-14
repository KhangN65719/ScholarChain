package scholarchain.storage

import java.io.IOException
import java.net.URI
import software.amazon.awssdk.auth.credentials.{
  AwsCredentialsProvider,
  DefaultCredentialsProvider
}
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException
}
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3vectors.S3VectorsClient

/** Sleeps the calling thread. The adapters take a `Sleeper` so tests never
  * wait.
  */
object ThreadSleeper extends Sleeper:
  def sleep(millis: Long): Unit = if millis > 0 then Thread.sleep(millis)

/** The one place that turns SDK exceptions into storage exceptions.
  *
  *   - Throttling, request timeouts and 5xx are `TransientStorageException`:
  *     the caller may retry.
  *   - 401 and 403 are `AwsAccessDenied`. A 403 is never read as "absent": S3
  *     answers a HEAD or GET for a missing key with 403 when the caller lacks
  *     `s3:ListBucket`, so only a 404 whose error code names a missing key or
  *     resource becomes `AwsNotFound`.
  *   - Every other 4xx is `AwsRejected` and is never retried.
  *   - A client-side failure caused by an I/O error or a timeout is transient;
  *     any other client-side failure (no region, no credentials) is
  *     `AwsRejected`.
  */
object AwsErrors:
  val TransientStatuses: Set[Int] = Set(408, 429, 500, 502, 503, 504)

  val TransientCodes: Set[String] = Set(
    "Throttling",
    "ThrottlingException",
    "ThrottledException",
    "RequestThrottled",
    "RequestThrottledException",
    "TooManyRequestsException",
    "ProvisionedThroughputExceededException",
    "RequestLimitExceeded",
    "SlowDown",
    "ServiceUnavailable",
    "ServiceUnavailableException",
    "InternalServerException",
    "InternalFailure",
    "ModelNotReadyException",
    "ModelTimeoutException",
    "RequestTimeout",
    "RequestTimeoutException"
  )

  /** A 404 means "that key or resource is not there" unless its error code
    * names a missing container: a HEAD answers with no body, so its code can be
    * empty, `NotFound` or numeric and must still count as a missing key.
    */
  val ContainerMissingCodes: Set[String] =
    Set("NoSuchBucket", "NoSuchUpload", "NoSuchVersion", "NoSuchAccessPoint")

  def errorCode(error: AwsServiceException): String =
    Option(error.awsErrorDetails)
      .flatMap(details => Option(details.errorCode))
      .getOrElse("")

  def isTransient(error: AwsServiceException): Boolean =
    error.isThrottlingException ||
      TransientStatuses.contains(error.statusCode) ||
      TransientCodes.contains(errorCode(error))

  private def describe(operation: String, error: AwsServiceException): String =
    val requestId = Option(error.requestId).getOrElse("none")
    s"$operation: HTTP ${error.statusCode} ${errorCode(error)} " +
      s"${Option(error.getMessage).getOrElse("")} (request $requestId)"

  private def hasIoCause(error: Throwable): Boolean =
    var current = error
    var depth = 0
    var found = false
    while current != null && depth < 10 && !found do
      found = current.isInstanceOf[IOException]
      current = current.getCause
      depth += 1
    found

  private def transient(message: String, cause: Throwable): Nothing =
    val failure = new TransientStorageException(message)
    failure.initCause(cause)
    throw failure

  def call[A](operation: String)(thunk: => A): A =
    try thunk
    catch
      case error: AwsServiceException =>
        if isTransient(error) then transient(describe(operation, error), error)
        else if error.statusCode == 401 || error.statusCode == 403 then
          Fail(ErrorCode.AwsAccessDenied, describe(operation, error))
        else if error.statusCode == 404 &&
          !ContainerMissingCodes.contains(errorCode(error))
        then Fail(ErrorCode.AwsNotFound, describe(operation, error))
        else Fail(ErrorCode.AwsRejected, describe(operation, error))
      case error: SdkClientException =>
        val timeout =
          error.isInstanceOf[ApiCallTimeoutException] ||
            error.isInstanceOf[ApiCallAttemptTimeoutException]
        val text = s"$operation: client error ${error.getMessage}"
        if timeout || hasIoCause(error) then transient(text, error)
        else Fail(ErrorCode.AwsRejected, text)

  /** `Some` result, or `None` when the call ended in `AwsNotFound`. */
  def optional[A](operation: String)(thunk: => A): Option[A] =
    try Some(call(operation)(thunk))
    catch
      case error: StorageException if error.code == ErrorCode.AwsNotFound =>
        None

/** The only way the adapters get SDK clients. It takes a Region and nothing
  * else: no access key, no secret and no profile name. Credentials come from
  * the SDK default provider chain (ECS task role or EMR instance profile in
  * AWS). Every builder sets the same `UrlConnectionHttpClient` explicitly, the
  * only HTTP client on the classpath. Nothing here reads the environment: the
  * caller chooses the region (and, for Neptune, the endpoint).
  */
final class AwsClients(val region: Region):
  val credentials: AwsCredentialsProvider = DefaultCredentialsProvider.create()
  val httpClient: SdkHttpClient = UrlConnectionHttpClient.create()

  def s3(): S3Client =
    S3Client
      .builder()
      .region(region)
      .credentialsProvider(credentials)
      .httpClient(httpClient)
      .build()

  def s3Vectors(): S3VectorsClient =
    S3VectorsClient
      .builder()
      .region(region)
      .credentialsProvider(credentials)
      .httpClient(httpClient)
      .build()

  def bedrockRuntime(): BedrockRuntimeClient =
    BedrockRuntimeClient
      .builder()
      .region(region)
      .credentialsProvider(credentials)
      .httpClient(httpClient)
      .build()

  /** `endpoint` is the Neptune cluster endpoint, `https://<host>:8182`. */
  def neptune(endpoint: URI): NeptunedataClient =
    NeptunedataClient
      .builder()
      .region(region)
      .endpointOverride(endpoint)
      .credentialsProvider(credentials)
      .httpClient(httpClient)
      .build()

  def dynamo(): DynamoDbClient =
    DynamoDbClient
      .builder()
      .region(region)
      .credentialsProvider(credentials)
      .httpClient(httpClient)
      .build()

object AwsClients:
  def forRegion(name: String): AwsClients =
    Fail.unless(
      name.trim.nonEmpty,
      ErrorCode.AwsRejected,
      "An explicit AWS region is required"
    )
    new AwsClients(Region.of(name.trim))
