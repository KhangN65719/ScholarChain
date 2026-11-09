package scholarchain.services

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scholarchain.rag.RagRuntime
import scholarchain.storage.{CypherQuery, ReadLimits}

/** Every policy value of the services. Nothing here is a secret default: the
  * two tokens have no default and come from configuration (an environment
  * variable name in the repository, a placeholder for local runs, Secrets
  * Manager in Phase 12). `toString` hides them so a log line cannot leak them.
  */
final case class ServiceConfig(
    queryToken: String,
    readToken: String,
    cursorSecret: Option[String],
    bodyLimitBytes: Int,
    maxUriChars: Int,
    httpThreads: Int,
    httpQueueCapacity: Int,
    maxQuestionChars: Int,
    asyncEnabled: Boolean,
    maxResultLimit: Int,
    fastDeadlineMillis: Long,
    slowDeadlineMillis: Long,
    readyDeadlineMillis: Long,
    maxRetries: Int,
    retryBackoffMillis: Long,
    breakerFailureThreshold: Int,
    breakerOpenMillis: Long,
    callPoolSize: Int,
    callQueueCapacity: Int,
    graphDefaultWindow: String,
    graphMaxDepth: Int,
    graphDefaultLimit: Int,
    graphMaxLimit: Int,
    graphMaxFanout: Int,
    graphMaxExpansions: Int,
    evidenceMaxContentChars: Int,
    jobExecutorSize: Int,
    jobQueueCapacity: Int,
    jobsRetained: Int,
    idempotencyEntries: Int,
    recordsRetained: Int,
    metricsEnabled: Boolean
):
  override def toString: String = "ServiceConfig(<redacted>)"

object ServiceConfig:
  val EnvPrefix = "SCHOLARCHAIN_SERVICES_"

  // A configured cursor secret holds at least this many UTF-8 bytes (an
  // HMAC-SHA256 key is 32 bytes; 16 is the floor). Absent, the service draws a
  // random per-process key, so cursors do not survive a restart.
  val MinCursorSecretBytes = 16

  /** Reads typed values and remembers which keys were asked for. */
  private final class Reader(values: Map[String, String]):
    val used: mutable.LinkedHashSet[String] = mutable.LinkedHashSet.empty

    private def raw(key: String): Option[String] =
      used += key
      values.get(key)

    private def bad(key: String): Nothing =
      throw new IllegalArgumentException(s"Invalid configuration value: $key")

    def required(key: String): String =
      raw(key).getOrElse(
        throw new IllegalArgumentException(s"Missing configuration: $key")
      )

    def optional(key: String): Option[String] = raw(key)

    def long(key: String, default: Long, min: Long, max: Long): Long =
      val value =
        raw(key).fold(default)(v => v.toLongOption.getOrElse(bad(key)))
      if value < min || value > max then bad(key)
      value

    def int(key: String, default: Int, min: Int, max: Int): Int =
      long(key, default.toLong, min.toLong, max.toLong).toInt

    def bool(key: String, default: Boolean): Boolean =
      raw(key) match
        case None          => default
        case Some("true")  => true
        case Some("false") => false
        case Some(_)       => bad(key)

    def unknownKeys: Set[String] = values.keySet -- used

  private def tokenOk(token: String): Boolean =
    token.length >= 16 && token.length <= 256 && token.forall(c =>
      c > ' ' && c < '\u007f'
    )

  private def secretOk(secret: String): Boolean =
    val longEnough = secret.getBytes(UTF_8).length >= MinCursorSecretBytes
    longEnough && !secret.exists(Character.isISOControl(_))

  private def build(r: Reader): ServiceConfig =
    val queryToken = r.required("auth.queryToken")
    val readToken = r.required("auth.readToken")
    if !tokenOk(queryToken) || !tokenOk(readToken) then
      throw new IllegalArgumentException("Invalid configuration value: tokens")
    if queryToken == readToken then
      throw new IllegalArgumentException("The two scope tokens must differ")
    val window = r.optional("graph.defaultWindow").getOrElse("all")
    if !GraphWindow.valid(window) then
      throw new IllegalArgumentException(
        "Invalid configuration value: graph.defaultWindow"
      )
    val cursorSecret = r.optional("auth.cursorSecret")
    if !cursorSecret.forall(secretOk) then
      throw new IllegalArgumentException(
        "Invalid configuration value: auth.cursorSecret"
      )
    val graphMaxLimit = r.int("graph.maxLimit", 50, 1, ReadLimits.MaxPage)
    ServiceConfig(
      queryToken,
      readToken,
      cursorSecret,
      r.int("http.bodyLimitBytes", 65536, 256, 1048576),
      r.int("http.maxUriChars", 4096, 64, 65536),
      r.int("http.threads", 8, 1, 64),
      r.int("http.queueCapacity", 64, 1, 10000),
      r.int("query.maxQuestionChars", 1000, 1, RagRuntime.MaxQuestionChars),
      r.bool("query.asyncEnabled", true),
      r.int("query.maxResultLimit", 20, 1, 100),
      r.long("fast.deadlineMillis", 2000L, 20L, 60000L),
      r.long("slow.deadlineMillis", 10000L, 20L, 300000L),
      r.long("ready.deadlineMillis", 1000L, 20L, 30000L),
      r.int("retry.max", 2, 0, 2),
      r.long("retry.backoffMillis", 20L, 0L, 1000L),
      r.int("breaker.failureThreshold", 5, 1, 100),
      r.long("breaker.openMillis", 5000L, 10L, 600000L),
      r.int("calls.poolSize", 16, 1, 128),
      r.int("calls.queueCapacity", 32, 1, 10000),
      window,
      r.int("graph.maxDepth", 2, 1, 4),
      r.int("graph.defaultLimit", 20, 1, graphMaxLimit),
      graphMaxLimit,
      r.int("graph.maxFanout", 100, 1, CypherQuery.MaxLimit),
      r.int("graph.maxExpansions", 25, 1, 200),
      r.int("evidence.maxContentChars", 500, 16, 5000),
      r.int("jobs.executorSize", 2, 1, 16),
      r.int("jobs.queueCapacity", 16, 1, 1000),
      r.int("jobs.retained", 1000, 1, 100000),
      r.int("idempotency.entries", 1000, 1, 100000),
      r.int("records.retained", 500, 1, 100000),
      r.bool("metrics.enabled", false)
    )

  /** Every key the services read, taken from the reader itself. */
  val Keys: Vector[String] =
    val probe = new Reader(
      Map("auth.queryToken" -> "q" * 16, "auth.readToken" -> "r" * 16)
    )
    build(probe)
    probe.used.toVector

  /** Builds the configuration from dotted keys. An unknown key is an error, so
    * a misspelled policy value cannot pass silently.
    */
  def from(values: Map[String, String]): ServiceConfig =
    val reader = new Reader(values)
    val config = build(reader)
    val unknown = reader.unknownKeys
    if unknown.nonEmpty then
      throw new IllegalArgumentException(
        s"Unknown configuration keys: ${unknown.toVector.sorted.mkString(", ")}"
      )
    config

  def envName(key: String): String =
    val spaced = key.replace('.', '_').replaceAll("([a-z0-9])([A-Z])", "$1_$2")
    EnvPrefix + spaced.toUpperCase

  /** Reads `SCHOLARCHAIN_SERVICES_<KEY>` variables, for example
    * `SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN`.
    */
  def fromEnvironment(env: Map[String, String] = sys.env): ServiceConfig =
    val values = Keys.flatMap(k => env.get(envName(k)).map(k -> _)).toMap
    from(values)
