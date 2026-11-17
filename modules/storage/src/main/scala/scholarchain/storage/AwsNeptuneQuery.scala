package scholarchain.storage

import java.net.URI
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException
}
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.DefaultRetryStrategy
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.ExecuteOpenCypherQueryRequest

/** The deadlines of the Neptune query client. `connect` and `read` bound the
  * socket, `attempt` one HTTP attempt and `call` the whole call. The defaults
  * keep a call under the slow service's own deadline (10 s) so the adapter
  * answers before the service gives up.
  */
final case class NeptuneQueryTimeouts(
    connect: Duration,
    read: Duration,
    attempt: Duration,
    call: Duration
):
  Fail.unless(
    Vector(connect, read, attempt, call).forall(d =>
      !d.isNegative && !d.isZero
    ),
    ErrorCode.InvalidQuery,
    "Every Neptune query timeout must be positive"
  )
  Fail.unless(
    call.compareTo(attempt) >= 0,
    ErrorCode.InvalidQuery,
    "The Neptune call timeout must not be shorter than the attempt timeout"
  )

object NeptuneQueryTimeouts:
  val Default: NeptuneQueryTimeouts = NeptuneQueryTimeouts(
    Duration.ofSeconds(3),
    Duration.ofSeconds(8),
    Duration.ofSeconds(9),
    Duration.ofSeconds(10)
  )

/** A Neptune document value as JSON. Depth is bounded, so a hostile response
  * cannot recurse without end.
  */
object DocumentJson:
  val MaxDepth = 8

  def toJson(document: Document, depth: Int = 0): ujson.Value =
    Fail.unless(
      depth <= MaxDepth,
      ErrorCode.MalformedRow,
      s"A Neptune result value is nested deeper than $MaxDepth levels"
    )
    if document == null || document.isNull then ujson.Null
    else if document.isString then ujson.Str(document.asString)
    else if document.isBoolean then ujson.Bool(document.asBoolean.booleanValue)
    else if document.isNumber then ujson.Num(document.asNumber.doubleValue)
    else if document.isList then
      ujson.Arr.from(document.asList.asScala.map(toJson(_, depth + 1)))
    else if document.isMap then
      ujson.Obj.from(
        document.asMap.asScala.toVector.map { case (key, value) =>
          key -> toJson(value, depth + 1)
        }
      )
    else Fail(ErrorCode.MalformedRow, "A Neptune result value has no JSON form")

/** `NeptuneQueryPort` over the Neptune data API `ExecuteOpenCypherQuery` (HTTPS
  * `POST /opencypher`, signed with SigV4 for the service `neptune-db` by the
  * SDK; the cluster has IAM database authentication on).
  *
  *   - Read-only by construction: a `CypherQuery` is validated when it is built
  *     (no write clause, no literal, one statement, a bounded `$limit`) and is
  *     validated again here, before any request.
  *   - Parameterized: the query text is the fixed template and every value
  *     travels in the `parameters` JSON, never in the text.
  *   - Bounded: the clients carry connect, read, attempt and call deadlines, no
  *     SDK retry, and a response with more rows than the query's `$limit` is
  *     refused, never cut.
  *   - Errors are mapped from the status and error code only. The AWS message
  *     can echo the query, so it is never copied: a failure names the query
  *     template, the HTTP status, the error code and the request ID. No
  *     parameter value appears in a failure or in the log line.
  *
  * Failure kinds (the text starts with the marker): `NEPTUNE_TIMEOUT` and
  * `NEPTUNE_THROTTLED` are `TransientStorageException` (the caller may retry
  * later); an access failure is `AwsAccessDenied`; a malformed or refused query
  * is `QueryRejected`; anything else is `AwsRejected` (4xx) or transient (5xx).
  *
  * `representativeWorkIds` is stored in Neptune as one `;`-joined string (the
  * bulk-load CSV has no list type); the `edge-evidence` result turns it back
  * into the JSON array the in-memory graph returns.
  */
final class AwsNeptuneQuery(
    client: NeptunedataClient,
    log: String => Unit = _ => (),
    closer: () => Unit = () => ()
) extends NeptuneQueryPort
    with AutoCloseable:
  import AwsNeptuneQuery.*

  def run(query: CypherQuery): Vector[Row] =
    CypherQuery.validate(query)
    Fail.unless(
      query.text.length <= MaxQueryChars,
      ErrorCode.QueryRejected,
      s"Query text is longer than $MaxQueryChars characters"
    )
    val limit = query.params("limit").num.toInt
    val request = ExecuteOpenCypherQueryRequest
      .builder()
      .openCypherQuery(query.text)
      .parameters(ujson.write(ujson.Obj.from(query.params)))
      .build()
    val began = System.nanoTime()
    val results =
      try client.executeOpenCypherQuery(request).results()
      catch
        case NonFatal(error) =>
          val failure = classify(query.name, error)
          log(s"NEPTUNE_QUERY_FAILED name=${query.name} kind=${failure._1}")
          throw failure._2
    val rows =
      try rowsOf(results, limit)
      catch
        case error: StorageException =>
          log(s"NEPTUNE_QUERY_FAILED name=${query.name} kind=BAD_RESULT")
          throw error
    log(
      s"NEPTUNE_QUERY name=${query.name} rows=${rows.size} " +
        s"ms=${(System.nanoTime() - began) / 1000000L}"
    )
    shape(query.name, rows)

  def close(): Unit = closer()

object AwsNeptuneQuery:
  /** The longest query text the adapter sends. The catalog templates are far
    * shorter.
    */
  val MaxQueryChars = 4000
  val DefaultPort = 8182

  private val BadQueryCodes = Set(
    "MalformedQueryException",
    "InvalidParameterException",
    "BadRequestException",
    "QueryTooLargeException",
    "UnsupportedOperationException",
    "ParsingException",
    "IllegalArgumentException",
    "InvalidNumericDataException",
    "ConstraintViolationException"
  )
  private val TimeoutCodes =
    Set("TimeLimitExceededException", "ClientTimeoutException")
  private val ThrottleCodes = Set(
    "ThrottlingException",
    "TooManyRequestsException",
    "QueryLimitExceededException",
    "MemoryLimitExceededException",
    "ConcurrentModificationException"
  )
  private val DeniedCodes = Set(
    "AccessDeniedException",
    "MissingAuthenticationTokenException",
    "UnauthorizedException",
    "ExpiredTokenException"
  )

  private def hasIoCause(error: Throwable): Boolean =
    var current = error
    var depth = 0
    var found = false
    while current != null && depth < 10 && !found do
      found = current.isInstanceOf[java.io.IOException]
      current = current.getCause
      depth += 1
    found

  private def transient(message: String, cause: Throwable): Exception =
    val failure = new TransientStorageException(message)
    failure.initCause(cause)
    failure

  /** The failure kind (for the log line) and the exception to throw. The text
    * never carries the AWS message or a parameter value.
    */
  private[storage] def classify(
      name: String,
      error: Throwable
  ): (String, Exception) = error match
    case stored: StorageException => ("STORAGE", stored)
    case e: AwsServiceException   =>
      val code = AwsErrors.errorCode(e)
      val request = Option(e.requestId).getOrElse("none")
      def text(marker: String): String =
        s"$marker: Neptune query $name: HTTP ${e.statusCode} $code " +
          s"(request $request)"
      if TimeoutCodes.contains(code) || e.statusCode == 408 then
        ("TIMEOUT", transient(text("NEPTUNE_TIMEOUT"), e))
      else if ThrottleCodes.contains(code) || e.isThrottlingException ||
        e.statusCode == 429
      then ("THROTTLED", transient(text("NEPTUNE_THROTTLED"), e))
      else if DeniedCodes.contains(code) || e.statusCode == 401 ||
        e.statusCode == 403
      then
        (
          "ACCESS_DENIED",
          new StorageException(
            ErrorCode.AwsAccessDenied,
            text("NEPTUNE_ACCESS_DENIED")
          )
        )
      else if BadQueryCodes.contains(code) || e.statusCode == 400 then
        (
          "BAD_QUERY",
          new StorageException(
            ErrorCode.QueryRejected,
            text("NEPTUNE_BAD_QUERY")
          )
        )
      else if AwsErrors.TransientStatuses.contains(e.statusCode) ||
        AwsErrors.TransientCodes.contains(code)
      then ("UNAVAILABLE", transient(text("NEPTUNE_UNAVAILABLE"), e))
      else
        (
          "REJECTED",
          new StorageException(ErrorCode.AwsRejected, text("NEPTUNE_REJECTED"))
        )
    case e: SdkClientException =>
      val timeout =
        e.isInstanceOf[ApiCallTimeoutException] ||
          e.isInstanceOf[ApiCallAttemptTimeoutException]
      if timeout || hasIoCause(e) then
        (
          "TIMEOUT",
          transient(
            s"NEPTUNE_TIMEOUT: Neptune query $name: no answer within the " +
              "client deadline or the connection failed",
            e
          )
        )
      else
        (
          "CLIENT",
          new StorageException(
            ErrorCode.AwsRejected,
            s"NEPTUNE_REJECTED: Neptune query $name: client error"
          )
        )
    case other =>
      (
        "UNEXPECTED",
        new StorageException(
          ErrorCode.AwsRejected,
          s"NEPTUNE_REJECTED: Neptune query $name: " +
            other.getClass.getSimpleName
        )
      )

  /** The rows of a response. The API answers `{"results": [ ... ]}`; the SDK
    * exposes the array, and a wrapper object is tolerated.
    */
  private[storage] def rowsOf(results: Document, limit: Int): Vector[Row] =
    val items: Vector[Document] =
      if results == null || results.isNull then Vector.empty
      else if results.isList then results.asList.asScala.toVector
      else if results.isMap && results.asMap.containsKey("results") &&
        results.asMap.get("results") != null &&
        results.asMap.get("results").isList
      then results.asMap.get("results").asList.asScala.toVector
      else
        Fail(ErrorCode.MalformedRow, "The Neptune answer holds no result list")
    Fail.unless(
      items.size <= limit,
      ErrorCode.BoundExceeded,
      s"Neptune returned ${items.size} rows for a query bounded to $limit"
    )
    items.map { item =>
      Fail.unless(
        item != null && item.isMap,
        ErrorCode.MalformedRow,
        "A Neptune result row is not an object"
      )
      item.asMap.asScala.toMap.map { case (column, value) =>
        column -> DocumentJson.toJson(value)
      }
    }

  /** Per-template result shaping. Only `representativeWorkIds` is touched: the
    * one `;`-joined string becomes a JSON array.
    */
  private[storage] def shape(name: String, rows: Vector[Row]): Vector[Row] =
    if name != CypherCatalog.EdgeEvidence then rows
    else
      rows.map { row =>
        row.get("representativeWorkIds") match
          case Some(ujson.Str(joined)) =>
            val ids =
              if joined.isEmpty then Vector.empty[ujson.Value]
              else
                joined
                  .split(NeptuneExporter.ListSeparator, -1)
                  .toVector
                  .map(ujson.Str(_))
            row + ("representativeWorkIds" -> ujson.Arr.from(ids))
          case _ => row
      }

  private def requireHost(host: String): String =
    Fail.unless(
      host.matches("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?"),
      ErrorCode.InvalidQuery,
      "The Neptune host is not a host name"
    )
    host

  /** `https://<host>:<port>`, the cluster endpoint. */
  def endpoint(host: String, port: Int = DefaultPort): URI =
    Fail.unless(
      port >= 1 && port <= 65535,
      ErrorCode.InvalidQuery,
      "The Neptune port is out of range"
    )
    URI.create(s"https://${requireHost(host)}:$port")

  private def overrides(timeouts: NeptuneQueryTimeouts) =
    ClientOverrideConfiguration
      .builder()
      .apiCallAttemptTimeout(timeouts.attempt)
      .apiCallTimeout(timeouts.call)
      .retryStrategy(DefaultRetryStrategy.doNotRetry())
      .build()

  /** Builds the SDK client and the adapter over it. The adapter owns the client
    * and its HTTP client and closes both. `endpoint` may be `http` for a test
    * server; production passes `endpoint(host, port)`.
    */
  def open(
      region: Region,
      credentials: AwsCredentialsProvider,
      endpoint: URI,
      timeouts: NeptuneQueryTimeouts = NeptuneQueryTimeouts.Default,
      log: String => Unit = _ => ()
  ): AwsNeptuneQuery =
    val http: SdkHttpClient = UrlConnectionHttpClient
      .builder()
      .connectionTimeout(timeouts.connect)
      .socketTimeout(timeouts.read)
      .build()
    val client = NeptunedataClient
      .builder()
      .region(region)
      .endpointOverride(endpoint)
      .credentialsProvider(credentials)
      .httpClient(http)
      .overrideConfiguration(overrides(timeouts))
      .build()
    new AwsNeptuneQuery(
      client,
      log,
      () =>
        try client.close()
        finally http.close()
    )

  def open(
      clients: AwsClients,
      endpoint: URI,
      timeouts: NeptuneQueryTimeouts,
      log: String => Unit
  ): AwsNeptuneQuery =
    open(clients.region, clients.credentials, endpoint, timeouts, log)
