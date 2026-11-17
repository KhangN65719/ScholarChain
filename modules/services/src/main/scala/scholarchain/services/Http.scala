package scholarchain.services

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.net.{InetAddress, InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.{
  ArrayBlockingQueue,
  ExecutorService,
  RejectedExecutionException,
  ThreadPoolExecutor,
  TimeUnit
}
import org.slf4j.LoggerFactory
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** A finished response. The body is already encoded. */
final case class Response(
    status: Int,
    contentType: String,
    body: Array[Byte],
    headers: Map[String, String] = Map.empty
)

object Response:
  val JsonType = "application/json; charset=utf-8"
  val TextType = "text/plain; version=0.0.4; charset=utf-8"

  def json(
      status: Int,
      value: ujson.Value,
      headers: Map[String, String] = Map.empty
  ): Response =
    Response(status, JsonType, ujson.write(value).getBytes(UTF_8), headers)

  def text(
      status: Int,
      value: String,
      headers: Map[String, String] = Map.empty
  ): Response =
    Response(status, TextType, value.getBytes(UTF_8), headers)

/** Identifier hygiene. A header or path value that does not match is replaced
  * (request ID) or refused (path ID, idempotency key), never echoed.
  */
object Ids:
  private val requestPattern = "[A-Za-z0-9._-]{1,128}".r
  private val pathPattern = "[A-Za-z0-9:_.=-]{1,300}".r
  private val keyPattern = "[A-Za-z0-9._:=-]{1,128}".r

  def requestId(incoming: Option[String]): String =
    incoming
      .filter(requestPattern.matches(_))
      .getOrElse(UUID.randomUUID().toString)

  def traceId(): String = UUID.randomUUID().toString.replace("-", "")

  def pathId(raw: String): String =
    if pathPattern.matches(raw) then raw
    else
      throw new ApiException(
        ErrorCode.InvalidRequest,
        "The identifier in the path is not valid"
      )

  def idempotencyKey(raw: String): String =
    if keyPattern.matches(raw) then raw
    else
      throw new ApiException(
        ErrorCode.InvalidRequest,
        "The Idempotency-Key is not valid"
      )

/** One parsed request. The body is read only when a handler asks for it, and
  * never past the configured limit.
  */
final class Request(
    val method: String,
    val segments: Vector[String],
    val query: Map[String, String],
    headers: Map[String, String],
    readBody: () => Array[Byte],
    val ctx: RequestContext
):
  var route: String = "unmatched"

  // Filled by the query handler for the latency log. Never the question text.
  var retrievalMode: Option[String] = None
  var executionMode: Option[String] = None
  var stageTimings: Vector[(String, Option[Long])] = Vector.empty

  def header(name: String): Option[String] = headers.get(name.toLowerCase)

  lazy val body: Array[Byte] = readBody()

  def requireJson(): Unit =
    val mediaType =
      header("content-type").map(_.takeWhile(_ != ';').trim.toLowerCase)
    if !mediaType.exists(m => m == "application/json" || m.endsWith("+json"))
    then
      throw new ApiException(
        ErrorCode.UnsupportedMediaType,
        "The request body must be application/json"
      )

final case class Route(
    method: String,
    pattern: String,
    scope: Option[Scope],
    handler: (Request, Vector[String]) => Response
):
  private val parts: Vector[String] =
    pattern.stripPrefix("/").split("/", -1).toVector

  def matches(segments: Vector[String]): Option[Vector[String]] =
    if segments.size != parts.size then None
    else
      val pairs = parts.zip(segments)
      val ok = pairs.forall { (part, seg) =>
        if part.startsWith("{") then seg.nonEmpty else part == seg
      }
      if ok then
        Some(pairs.collect { case (part, seg) if part.startsWith("{") => seg })
      else None

// Path and method dispatch. Authentication and authorization run before any
// handler code, and handlers are the only code that reads a body, so the router
// never reads a body for an unauthenticated caller. A /v1 path is authenticated
// first, even when no route matches: an anonymous caller gets 401 for every
// /v1 path, so 404 and 405 cannot be used to map the routes. Paths outside /v1
// (the health checks, /metrics and anything else) keep 404 and 405 without a
// token; they reveal nothing the unauthenticated health routes do not.
final class Router(routes: Vector[Route], auth: Authenticator):
  private def authenticateApiPath(request: Request): Unit =
    if request.segments.headOption.contains("v1") then
      auth.authenticate(request.header("authorization"))

  def handle(request: Request): Response =
    val matched =
      routes.flatMap(r => r.matches(request.segments).map(p => (r, p)))
    if matched.isEmpty then
      authenticateApiPath(request)
      throw new ApiException(ErrorCode.NotFound, "No such route")
    matched.find(_._1.method == request.method) match
      case None =>
        authenticateApiPath(request)
        val allow = matched.map(_._1.method).distinct.sorted.mkString(", ")
        throw new ApiException(
          ErrorCode.MethodNotAllowed,
          "The method is not allowed for this route",
          Vector.empty,
          Map("Allow" -> allow)
        )
      case Some((route, params)) =>
        request.route = s"${route.method} ${route.pattern}"
        route.scope.foreach(auth.authorize(request.header("authorization"), _))
        route.handler(request, params)

object Threads:
  private[services] val log =
    LoggerFactory.getLogger("scholarchain.services.threads")

  // Decision M-1/m-1 (repair 1): a Throwable that ends a worker thread would
  // reach the JDK default handler, which prints its message and stack to
  // stderr unredacted. This handler logs the thread name and the class only.
  def daemon(prefix: String): java.util.concurrent.ThreadFactory =
    val counter = new java.util.concurrent.atomic.AtomicInteger
    (task: Runnable) =>
      val thread = new Thread(task, s"$prefix-${counter.incrementAndGet()}")
      thread.setDaemon(true)
      thread.setUncaughtExceptionHandler((t, e) =>
        log.error(
          "uncaught throwable thread={} type={}",
          t.getName,
          e.getClass.getName
        )
      )
      thread

  // A pool with a fixed number of threads and a bounded queue. A task that
  // finds every thread busy and the queue full is refused with a
  // RejectedExecutionException, after `onReject` has counted it. Nothing in
  // the services uses an unbounded queue.
  def bounded(
      prefix: String,
      size: Int,
      queueCapacity: Int,
      onReject: () => Unit = () => ()
  ): ThreadPoolExecutor =
    new ThreadPoolExecutor(
      size,
      size,
      0L,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue[Runnable](queueCapacity),
      daemon(prefix),
      (_: Runnable, _: ThreadPoolExecutor) =>
        onReject()
        throw new RejectedExecutionException(s"$prefix is saturated")
    )

/** One HTTP service on the JDK server. Binds the given host (loopback by
  * default) and an ephemeral port unless one is given.
  */
final class ServiceServer private (
    val name: String,
    router: Router,
    config: ServiceConfig,
    metrics: Metrics,
    server: HttpServer,
    pool: ExecutorService,
    requestLog: RequestLog
):
  private val log = LoggerFactory.getLogger(s"scholarchain.services.$name")

  // Reading the rest of a body that is no longer needed keeps the connection clean
  // for the client. Two fixed caps, none scaled by configuration:
  // - smallDrain: after any response, a body whose declared length is within
  //   this cap is read and dropped. A larger or undeclared body is not read at
  //   all, so an unauthenticated caller cannot make the server read it.
  // - oversizeDrain: a 413 for a body over the limit. Only a caller that passed
  //   authentication and the scope check reaches it, because a handler is the
  //   only code that reads a body.
  private val smallDrain = 65536L
  private val oversizeDrain = 4L * 1024 * 1024

  def port: Int = server.getAddress.getPort
  def baseUrl: String = s"http://127.0.0.1:$port"

  def close(): Unit =
    try server.stop(0)
    finally pool.shutdownNow()

  private def decodeSegment(raw: String): String =
    try URLDecoder.decode(raw.replace("+", "%2B"), UTF_8)
    catch
      case _: IllegalArgumentException =>
        throw new ApiException(
          ErrorCode.InvalidRequest,
          "The request target is not valid"
        )

  private def decodeQuery(raw: String): String =
    try URLDecoder.decode(raw, UTF_8)
    catch
      case _: IllegalArgumentException =>
        throw new ApiException(
          ErrorCode.InvalidRequest,
          "The request target is not valid"
        )

  private def parseQuery(raw: String): Map[String, String] =
    if raw == null || raw.isEmpty then Map.empty
    else
      raw.split("&", -1).foldLeft(Map.empty[String, String]) { (acc, pair) =>
        val at = pair.indexOf('=')
        val (k, v) =
          if at < 0 then (pair, "") else (pair.take(at), pair.drop(at + 1))
        val key = decodeQuery(k)
        if key.isEmpty || acc.contains(key) then
          throw new ApiException(
            ErrorCode.InvalidRequest,
            "The query string is not valid"
          )
        acc.updated(key, decodeQuery(v))
      }

  private def discard(in: InputStream, cap: Long): Unit =
    val buffer = new Array[Byte](8192)
    var total = 0L
    var read = in.read(buffer)
    while read >= 0 && total <= cap do
      total += read
      read = in.read(buffer)

  private def declaredLength(ex: HttpExchange): Option[Long] =
    Option(ex.getRequestHeaders.getFirst("Content-Length"))
      .flatMap(_.toLongOption)

  private def drainSmall(ex: HttpExchange): Unit =
    try
      if declaredLength(ex).exists(_ <= smallDrain) then
        discard(ex.getRequestBody, smallDrain)
    catch case _: IOException => ()

  private def readBody(ex: HttpExchange): Array[Byte] =
    val limit = config.bodyLimitBytes
    val declared = declaredLength(ex)
    def tooLarge(): Nothing =
      if declared.forall(_ <= oversizeDrain) then
        discard(ex.getRequestBody, oversizeDrain)
      throw new ApiException(
        ErrorCode.PayloadTooLarge,
        s"The request body is larger than $limit bytes"
      )
    if declared.exists(_ > limit) then tooLarge()
    val out = new ByteArrayOutputStream
    val in = ex.getRequestBody
    val buffer = new Array[Byte](8192)
    var read = in.read(buffer)
    while read >= 0 do
      out.write(buffer, 0, read)
      if out.size() > limit then tooLarge()
      read = in.read(buffer)
    out.toByteArray

  private def build(ex: HttpExchange, ctx: RequestContext): Request =
    val uri = ex.getRequestURI
    val rawPath = Option(uri.getRawPath).getOrElse("")
    val rawQuery = Option(uri.getRawQuery).getOrElse("")
    if rawPath.length + rawQuery.length > config.maxUriChars then
      throw new ApiException(
        ErrorCode.InvalidRequest,
        "The request target is too long"
      )
    val segments =
      if rawPath.isEmpty || rawPath == "/" then Vector.empty
      else rawPath.stripPrefix("/").split("/", -1).toVector.map(decodeSegment)
    val headers = ex.getRequestHeaders
      .entrySet()
      .asScala
      .filter(e => !e.getValue.isEmpty)
      .map(e => e.getKey.toLowerCase -> e.getValue.get(0))
      .toMap
    new Request(
      ex.getRequestMethod,
      segments,
      parseQuery(rawQuery),
      headers,
      () => readBody(ex),
      ctx
    )

  private def write(
      ex: HttpExchange,
      response: Response,
      ctx: RequestContext
  ): Unit =
    val out = ex.getResponseHeaders
    out.set("Content-Type", response.contentType)
    out.set("X-Request-Id", ctx.requestId)
    out.set("X-Trace-Id", ctx.traceId)
    out.set("Cache-Control", "no-store")
    out.set("X-Content-Type-Options", "nosniff")
    response.headers.foreach((k, v) => out.set(k, v))
    val bytes = response.body
    ex.sendResponseHeaders(
      response.status,
      if bytes.isEmpty then -1L else bytes.length.toLong
    )
    if bytes.nonEmpty then ex.getResponseBody.write(bytes)

  private def internal(): ApiException =
    new ApiException(ErrorCode.Internal, "The request could not be completed")

  private def handle(ex: HttpExchange): Unit =
    val started = System.nanoTime()
    val ctx = RequestContext(
      Ids.requestId(Option(ex.getRequestHeaders.getFirst("X-Request-Id"))),
      Ids.traceId()
    )
    var request: Request = null
    var failure: Throwable = null
    val response =
      try
        request = build(ex, ctx)
        val result = router.handle(request)
        if request.header("idempotency-key").isDefined then
          log.debug(
            "idempotencyKey={}",
            Redact.header("idempotency-key", "present")
          )
        result
      catch
        case e: ApiException => ApiError.response(e, ctx)
        case t: Throwable    =>
          failure = t
          log.error(
            "unexpected failure id={} type={}",
            ctx.requestId,
            t.getClass.getName
          )
          ApiError.response(internal(), ctx)
    // Any Throwable, an interrupt included, still gets the typed error body
    // with its trace ID. A set interrupt flag would close the channel under the
    // write, so it is cleared for the write and restored after it.
    val wasInterrupted =
      Thread.interrupted() || failure.isInstanceOf[InterruptedException]
    try
      drainSmall(ex)
      write(ex, response, ctx)
    catch case _: IOException => ()
    finally
      ex.close()
      if wasInterrupted then Thread.currentThread().interrupt()
    val millis = (System.nanoTime() - started) / 1000000
    val route = if request == null then "unmatched" else request.route
    metrics.inc(
      "http_requests_total",
      "service" -> name,
      "route" -> route,
      "status" -> response.status.toString
    )
    log.info(
      "request id={} route={} status={} millis={}",
      ctx.requestId,
      route,
      response.status,
      millis
    )
    if request != null && route == RequestLog.QueryRoute then
      requestLog.query(
        ctx.requestId,
        request.retrievalMode,
        request.executionMode,
        response.status,
        millis,
        request.stageTimings
      )
    // A fatal error (out of memory, a linkage error) is answered above and then
    // rethrown; an interrupt is not an error, its flag is already restored.
    if failure != null && !wasInterrupted && !NonFatal(failure) then
      throw failure

object ServiceServer:
  def start(
      name: String,
      router: Router,
      config: ServiceConfig,
      metrics: Metrics,
      host: String = "127.0.0.1",
      port: Int = 0,
      requestLog: RequestLog = RequestLog.default
  ): ServiceServer =
    // The transport queue is bounded. When it is full the JDK server drops the
    // connection without a response (the server gives the refused task no
    // exchange to answer on), so it is counted and the caller sees a closed
    // connection. A typed 503 OVERLOADED comes from the call pools behind it.
    val pool = Threads.bounded(
      s"http-$name",
      config.httpThreads,
      config.httpQueueCapacity,
      () => metrics.inc("http_rejected_total", "service" -> name)
    )
    val server =
      HttpServer.create(
        new InetSocketAddress(InetAddress.getByName(host), port),
        0
      )
    val service =
      new ServiceServer(name, router, config, metrics, server, pool, requestLog)
    server.createContext("/", (ex: HttpExchange) => service.handle(ex))
    server.setExecutor(pool)
    server.start()
    service
