package scholarchain.tests.adversarial

import java.io.{OutputStream, PrintStream}
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration as JDuration
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try
import munit.FunSuite
import scholarchain.services.runtime.LocalRuntime

/** Independent black-box harness for the Phase 10 services, written from
  * `docs/agent-runs/phase-10/spec.md` only. Everything that depends on a name
  * the specification leaves to the service-engineer lives in this file: the
  * accessors of `LocalRuntime` (read by reflection, so a renamed accessor fails
  * with a clear message instead of a compile error), the fault-injection
  * override keys (`Faults`) and the candidate questions of the fixture world
  * (`Questions`).
  */
final case class Resp(status: Int, headers: Map[String, String], body: String):
  def header(name: String): Option[String] = headers.get(name.toLowerCase)
  def json: ujson.Value = ujson.read(body)
  def contentType: String = header("content-type").getOrElse("")

/** The running local runtime and its placeholder credentials. */
final class Rt(val runtime: LocalRuntime):
  private def raw(name: String): Option[Any] =
    Try(runtime.getClass.getMethod(name).invoke(runtime)).toOption

  private def text(name: String): Option[String] =
    raw(name).flatMap {
      case s: String       => Some(s)
      case Some(s: String) => Some(s)
      case u: URI          => Some(u.toString)
      case _               => None
    }

  private def fromMap(scope: String): Option[String] =
    raw("tokens").flatMap {
      case m: scala.collection.Map[?, ?] =>
        m.collectFirst {
          case (k, v: String) if k.toString.equalsIgnoreCase(scope) => v
        }
      case _ => None
    }

  private def trimmed(url: String): String = url.stripSuffix("/")

  private def url(primary: String): String =
    text(primary)
      .orElse(text("baseUrl"))
      .map(trimmed)
      .getOrElse(
        throw new AssertionError(
          s"LocalRuntime exposes neither $primary nor baseUrl"
        )
      )

  def fastUrl: String = url("fastUrl")
  def slowUrl: String = url("slowUrl")

  /** Scope is "query" or "read". */
  def token(scope: String): String =
    text(s"${scope}Token")
      .orElse(text(s"token${scope.capitalize}"))
      .orElse(fromMap(scope))
      .getOrElse(
        throw new AssertionError(
          s"LocalRuntime exposes no $scope token (tried ${scope}Token, " +
            s"token${scope.capitalize} and tokens)"
        )
      )

  def close(): Unit = runtime.close()

/** Override keys of the fault-injection hook. The specification leaves their
  * naming to the service-engineer, so this is the single place to reconcile.
  * Assumed scheme: `fault.<port>` with the value `slow`, `fail` or `empty`.
  */
object Faults:
  val Slow = "slow"
  val Fail = "fail"
  val Empty = "empty"
  val EvidencePort = "fault.evidence"
  val GraphPort = "fault.graph"
  val WorkflowPort = "fault.workflow"
  val MetricsFlag = "metrics.enabled"

  def evidence(mode: String): Map[String, String] = Map(EvidencePort -> mode)
  def graph(mode: String): Map[String, String] = Map(GraphPort -> mode)
  def workflow(mode: String): Map[String, String] = Map(WorkflowPort -> mode)
  def everything(mode: String): Map[String, String] =
    evidence(mode) ++ graph(mode) ++ workflow(mode)

object Questions:
  /** Questions the Phase 9 workflow answers over a bounded fixture world. The
    * demo world (Ada Quillfeather, Boris Marlowe) and the independent Phase 9
    * world (Alice Archer) are both listed, so the tests do not depend on which
    * one the local runtime wires.
    */
  val candidates: Vector[String] = Vector(
    "How many distinct coauthors does Ada Quillfeather have in 2000-2009?",
    "How many distinct coauthors does Boris Marlowe have in 2000-2009?",
    "How many distinct coauthors does Alice Archer have in 2010-2019?"
  )
  val coauthors: Vector[String] = Vector(
    "Who are the coauthors of Ada Quillfeather in 2000-2009?",
    "Who are the coauthors of Alice Archer in 2010-2019?"
  )
  val nobody: String =
    "How many distinct coauthors does Zzyzx Qwertyuiop have in 2000-2009?"

object Json:
  def objects(v: ujson.Value): Vector[ujson.Obj] = v match
    case o: ujson.Obj =>
      Vector(o) ++ o.value.values.toVector.flatMap(objects)
    case a: ujson.Arr => a.value.toVector.flatMap(objects)
    case _            => Vector.empty

  def strings(v: ujson.Value): Vector[String] = v match
    case ujson.Str(s) => Vector(s)
    case o: ujson.Obj => o.value.values.toVector.flatMap(strings)
    case a: ujson.Arr => a.value.toVector.flatMap(strings)
    case _            => Vector.empty

  def keys(v: ujson.Value): Set[String] = P9Check.keysDeep(v)

  def firstString(v: ujson.Value, key: String): Option[String] =
    objects(v).flatMap(_.value.get(key)).collectFirst { case ujson.Str(s) =>
      s
    }

  def firstValue(v: ujson.Value, key: String): Option[ujson.Value] =
    objects(v).flatMap(_.value.get(key)).headOption

  /** The object that carries an answer: the one holding both claims and
    * evidence, wherever the service nests it.
    */
  def answerObject(v: ujson.Value): Option[ujson.Obj] =
    objects(v).find(o =>
      o.value.contains("claims") && o.value.contains("evidence")
    )

  def claims(v: ujson.Value): Vector[ujson.Value] =
    answerObject(v)
      .flatMap(_.value.get("claims"))
      .collect { case a: ujson.Arr => a.value.toVector }
      .getOrElse(Vector.empty)

  def evidenceItems(v: ujson.Value): Vector[ujson.Obj] =
    answerObject(v)
      .flatMap(_.value.get("evidence"))
      .map(objects)
      .getOrElse(Vector.empty)
      .filter(o =>
        o.value.contains("evidenceId") && o.value.contains("sourceUri")
      )

  def queryId(v: ujson.Value): Option[String] =
    answerObject(v).flatMap(_.value.get("queryId")).collect {
      case ujson.Str(s) => s
    }

  def answerText(v: ujson.Value): Option[String] =
    answerObject(v).flatMap(_.value.get("answer")).collect {
      case ujson.Str(s) => s
    }

  /** Every evidence ID the answer cites in a claim. */
  def citedEvidenceIds(v: ujson.Value): Vector[String] =
    claims(v)
      .flatMap(c => firstValue(c, "evidenceIds").toVector)
      .flatMap(strings)
      .distinct

  def isBacked(v: ujson.Value): Boolean =
    claims(v).exists { c =>
      firstString(c, "verificationStatus").contains("verified") &&
      firstValue(c, "evidenceIds").exists(strings(_).nonEmpty)
    }

  private val researcherId = "researcher:[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*".r

  def researcherIds(text: String): Vector[String] =
    researcherId.findAllIn(text).toVector.distinct

  /** Does `actual` carry every key of `example`, with the same JSON type? An
    * example `null` accepts any type; an example array describes its first
    * element and accepts any array whose elements all have that shape.
    */
  def conforms(example: ujson.Value, actual: ujson.Value): Boolean =
    (example, actual) match
      case (ujson.Null, _)              => true
      case (e: ujson.Obj, a: ujson.Obj) =>
        e.value.forall((k, ev) => a.value.get(k).exists(av => conforms(ev, av)))
      case (e: ujson.Arr, a: ujson.Arr) =>
        e.value.headOption.forall(shape => a.value.forall(conforms(shape, _)))
      case (_: ujson.Str, _: ujson.Str)   => true
      case (_: ujson.Num, _: ujson.Num)   => true
      case (_: ujson.Bool, _: ujson.Bool) => true
      case _                              => false

  /** The whole response body must match the example. No nested object is
    * searched: an example that matched only somewhere inside the body would
    * pass for a response that lost its top-level fields.
    */
  def conformsTop(example: ujson.Value, actual: ujson.Value): Boolean =
    conforms(example, actual)

  /** Why `actual` does not match `example`, as a path, or None when it does. An
    * example `null` accepts any type, so use it only for a field whose type
    * varies; give every other field a typed example value.
    */
  def mismatch(
      example: ujson.Value,
      actual: ujson.Value,
      at: String = "$"
  ): Option[String] =
    (example, actual) match
      case (ujson.Null, _)              => None
      case (e: ujson.Obj, a: ujson.Obj) =>
        e.value.iterator.flatMap { (k, ev) =>
          a.value.get(k) match
            case None     => Some(s"$at.$k is missing")
            case Some(av) => mismatch(ev, av, s"$at.$k")
        }.nextOption
      case (e: ujson.Arr, a: ujson.Arr) =>
        e.value.headOption.flatMap(shape =>
          a.value.zipWithIndex.iterator
            .flatMap((v, i) => mismatch(shape, v, s"$at[$i]"))
            .nextOption
        )
      case (_: ujson.Str, _: ujson.Str)   => None
      case (_: ujson.Num, _: ujson.Num)   => None
      case (_: ujson.Bool, _: ujson.Bool) => None
      case (e, a)                         =>
        Some(
          s"$at is ${a.getClass.getSimpleName}, example is ${e.getClass.getSimpleName}"
        )

/** The one process-wide lock for swapping System.out and System.err. A binary
  * semaphore, not a reentrant lock, because a tap may be closed on another
  * thread than the one that opened it. Acquisition times out loudly so a tap
  * that is never closed fails the next suite instead of hanging it.
  */
object StreamSwapLock:
  private val permit = new java.util.concurrent.Semaphore(1, true)

  def acquire(): Unit =
    if !permit.tryAcquire(120, java.util.concurrent.TimeUnit.SECONDS) then
      throw new AssertionError(
        "System.out/System.err are held by another LogTap (never closed, or nested)"
      )

  def release(): Unit = permit.release()

/** Captures what the process writes to stdout and stderr while it is open. It
  * holds `StreamSwapLock` from construction to `close()`, so taps in tests/
  * suites cannot nest or overlap.
  */
final class LogTap:
  StreamSwapLock.acquire()
  private val sink = new java.io.ByteArrayOutputStream
  private val oldOut = System.out
  private val oldErr = System.err
  private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)

  private def tee(old: PrintStream): PrintStream =
    val out = new OutputStream:
      override def write(b: Int): Unit =
        sink.synchronized(sink.write(b))
        old.write(b)
    new PrintStream(out, true, "UTF-8")

  System.setOut(tee(oldOut))
  System.setErr(tee(oldErr))

  def text: String = sink.synchronized(sink.toString("UTF-8"))

  def close(): Unit =
    if closed.compareAndSet(false, true) then
      try
        System.setOut(oldOut)
        System.setErr(oldErr)
      finally StreamSwapLock.release()

object Http:
  private val client = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(JDuration.ofSeconds(5))
    .build()

  def send(
      method: String,
      url: String,
      body: Option[String],
      headers: Map[String, String]
  ): Resp =
    val publisher = body.fold(HttpRequest.BodyPublishers.noBody())(b =>
      HttpRequest.BodyPublishers.ofString(b, UTF_8)
    )
    val builder = HttpRequest
      .newBuilder(URI.create(url))
      .timeout(JDuration.ofSeconds(60))
      .method(method, publisher)
    headers.foreach((k, v) => builder.header(k, v))
    val response =
      client.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8))
    val hs = response
      .headers()
      .map()
      .asScala
      .toMap
      .collect { case (k, v) if !v.isEmpty => k.toLowerCase -> v.get(0) }
    Resp(response.statusCode(), hs, response.body())

/** One running local runtime seen over HTTP. Query and job paths go to the slow
  * service and everything else to the fast service.
  */
final class Api(val rt: Rt):
  def url(path: String): String =
    val slow = path.startsWith("/v1/query") || path.startsWith("/v1/jobs")
    (if slow then rt.slowUrl else rt.fastUrl) + path

  def readToken: String = rt.token("read")
  def queryToken: String = rt.token("query")

  private def auth(token: Option[String]): Map[String, String] =
    token.map(t => "Authorization" -> s"Bearer $t").toMap

  def get(
      path: String,
      token: Option[String] = None,
      headers: Map[String, String] = Map.empty
  ): Resp =
    val t =
      if token.isEmpty && !headers.contains("Authorization") then
        Some(readToken)
      else token
    Http.send("GET", url(path), None, auth(t) ++ headers)

  def anonymous(path: String): Resp =
    Http.send("GET", url(path), None, Map.empty)

  def post(
      path: String,
      body: String,
      token: Option[String] = None,
      headers: Map[String, String] = Map.empty,
      contentType: String = "application/json"
  ): Resp =
    val t =
      if token.isEmpty && !headers.contains("Authorization") then
        Some(queryToken)
      else token
    Http.send(
      "POST",
      url(path),
      Some(body),
      auth(t) ++ Map("Content-Type" -> contentType) ++ headers
    )

  def method(
      verb: String,
      path: String,
      token: Option[String]
  ): Resp =
    Http.send(verb, url(path), None, auth(token))

  def queryBody(
      question: String,
      extra: Map[String, ujson.Value] = Map.empty
  ): String =
    val o = ujson.Obj("question" -> question)
    extra.foreach((k, v) => o(k) = v)
    ujson.write(o)

  def ask(
      question: String,
      extra: Map[String, ujson.Value] = Map.empty,
      headers: Map[String, String] = Map.empty
  ): Resp =
    post("/v1/query", queryBody(question, extra), headers = headers)

  def askAsync(question: String): Resp =
    ask(question, Map("mode" -> ujson.Str("async")))

  /** Sync answers for the first fixture question that is evidence-backed. */
  def answered(): (String, Resp) =
    Questions.candidates.iterator
      .map(q => q -> ask(q))
      .collectFirst {
        case (q, r) if r.status == 200 && Json.isBacked(r.json) => (q, r)
      }
      .getOrElse(
        throw new AssertionError(
          "No fixture question produced a 200 answer with a verified claim " +
            "that cites evidence: " + Questions.candidates.mkString(" | ")
        )
      )

  private val active = Set(
    "queued",
    "pending",
    "accepted",
    "submitted",
    "created",
    "started",
    "running",
    "processing",
    "in_progress",
    "in-progress"
  )

  def isActive(state: String): Boolean = active.contains(state.toLowerCase)

  /** Poll a job until its state is no longer active, or fail after `limit`. */
  def awaitJob(jobId: String, limit: FiniteDuration = 30.seconds): Resp =
    val deadline = System.nanoTime() + limit.toNanos
    var last = get(s"/v1/jobs/$jobId")
    while last.status == 200 &&
      Json.firstString(last.json, "state").forall(isActive) &&
      System.nanoTime() < deadline
    do
      Thread.sleep(50)
      last = get(s"/v1/jobs/$jobId")
    last

  def enc(s: String): String = URLEncoder.encode(s, UTF_8)

/** Shared base: starts runtimes, closes them, taps the log, checks errors. */
abstract class P10Suite extends FunSuite:
  override def munitTimeout: Duration = 120.seconds

  protected def overrides: Map[String, String] = Map.empty

  private var started = Vector.empty[Rt]
  protected var tap: LogTap = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    tap = new LogTap

  protected def launch(o: Map[String, String] = Map.empty): Api =
    val rt = new Rt(LocalRuntime.start(o))
    synchronized { started = started :+ rt }
    new Api(rt)

  protected lazy val api: Api = launch(overrides)

  override def afterAll(): Unit =
    started.foreach(r => Try(r.close()))
    if tap != null then tap.close()

  private val bearer =
    "(?i)bearer\\s+[A-Za-z0-9._~+/=-]{12,}".r
  private val keyLike = "AKIA[0-9A-Z]{16}|-----BEGIN|(?i:aws_secret)".r
  private val credAssign = "(?i)(password|passwd|secret)\\s*[=:]\\s*\\S".r
  private val stackLike =
    "(?s).*(\\bat [a-z][A-Za-z0-9_.$]*\\(|Exception|\\.scala:\\d+|\\.java:\\d+).*".r

  /** What a response (or a log capture) must never contain. */
  protected def leaks(text: String, tokens: Vector[String]): Vector[String] =
    tokens
      .filter(t => t.nonEmpty && text.contains(t))
      .map(_ => "token value") ++
      bearer.findFirstIn(text).map(m => s"bearer credential: $m") ++
      keyLike.findFirstIn(text).map(m => s"key material: $m") ++
      credAssign.findFirstIn(text).map(m => s"credential assignment: $m")

  protected def tokensOf(a: Api): Vector[String] =
    Vector(a.queryToken, a.readToken)

  private def causeChain(e: Throwable): Vector[Throwable] =
    Iterator
      .iterate(e)(_.getCause)
      .takeWhile(_ != null)
      .take(8)
      .toVector

  /** True only for a connection the server closed while the client was still
    * writing the body: an IOException that is neither a connect failure nor a
    * timeout, and whose chain says so. Anything else is not a refusal.
    */
  protected def isEarlyClose(e: Throwable): Boolean =
    val chain = causeChain(e)
    e.isInstanceOf[java.io.IOException] &&
    !chain.exists(c =>
      c.isInstanceOf[java.net.http.HttpTimeoutException] ||
        c.isInstanceOf[java.net.ConnectException] ||
        c.isInstanceOf[java.net.SocketTimeoutException]
    ) &&
    chain.exists { c =>
      val m = Option(c.getMessage).getOrElse("").toLowerCase
      m.contains("broken pipe") || m.contains("connection reset") ||
      m.contains("fixed content-length") || m.contains("eof") ||
      m.contains("closed")
    }

  /** The server's answer to the same request head with a tiny body: a raw
    * socket, because a client library cannot be made to stop writing.
    */
  private def rawHeadAnswer(
      a: Api,
      path: String,
      declared: Int,
      token: Option[String]
  ): (String, String) =
    val uri = URI.create(a.url(path))
    val socket = new java.net.Socket
    try
      socket.connect(
        new java.net.InetSocketAddress(uri.getHost, uri.getPort),
        5000
      )
      socket.setSoTimeout(5000)
      val head = Vector(
        s"POST $path HTTP/1.1",
        s"Host: ${uri.getAuthority}",
        "Content-Type: application/json",
        s"Content-Length: $declared",
        "Connection: close"
      ) ++ token.map(t => s"Authorization: Bearer $t")
      val out = socket.getOutputStream
      out.write((head.mkString("\r\n") + "\r\n\r\n").getBytes("US-ASCII"))
      out.write(Array.fill(2048)('x'.toByte))
      out.flush()
      // The server may keep the connection open while the body is unread, so
      // read the framed response (status, headers, one body) instead of to EOF.
      val in = new java.io.BufferedReader(
        new java.io.InputStreamReader(socket.getInputStream, "UTF-8")
      )
      val text =
        try
          val statusLine = in.readLine()
          val hs = Iterator
            .continually(in.readLine())
            .takeWhile(l => l != null && l.nonEmpty)
            .toVector
          val length = hs
            .find(_.toLowerCase.startsWith("content-length:"))
            .map(_.split(":", 2)(1).trim.toInt)
          val chunked =
            hs.exists(_.toLowerCase.startsWith("transfer-encoding:"))
          val body =
            if length.isDefined then
              val buf = new Array[Char](length.get)
              var n = 0
              while n < buf.length do
                val k = in.read(buf, n, buf.length - n)
                if k < 0 then n = buf.length else n += k
              new String(buf)
            else if chunked then
              val size = Integer.parseInt(in.readLine().trim, 16)
              val buf = new Array[Char](size)
              var n = 0
              while n < size do
                val k = in.read(buf, n, size - n)
                if k < 0 then n = size else n += k
              new String(buf)
            else ""
          statusLine + "\r\n" + hs.mkString("\r\n") + "\r\n\r\n" + body
        catch
          case _: java.net.SocketTimeoutException =>
            fail("no refusal within 5000 ms on the replayed head: it hangs")
      (text.linesIterator.nextOption.getOrElse(""), text)
    finally socket.close()

  /** POST an oversized body that the server must refuse with `status` before
    * reading it, and return the typed error code. Two outcomes count as the
    * refusal: the typed `status` answer, or a connection the server closed
    * while the client was still writing (a race the client library reports as a
    * broken pipe). Any other exception, status or error shape fails. In the
    * closed case the same head replayed with a tiny body must yield the typed
    * `status` answer, and in both cases the body marker must be absent from the
    * logs (the body was never read or logged) and the service must still be
    * healthy.
    */
  protected def assertOversizedRefused(
      a: Api,
      path: String,
      marker: String,
      sizeBytes: Int,
      token: Option[String],
      status: Int,
      label: String
  ): String =
    val body = ujson.write(
      ujson.Obj("question" -> (marker + ("q" * sizeBytes)))
    )
    val headers =
      Map("Content-Type" -> "application/json") ++
        token.map(t => "Authorization" -> s"Bearer $t")
    val started = System.nanoTime()
    val code =
      try
        val r = Http.send("POST", a.url(path), Some(body), headers)
        assertTypedError(r, status, a, label)
      catch
        case e: java.io.IOException if isEarlyClose(e) =>
          val (line, text) =
            rawHeadAnswer(a, path, body.getBytes(UTF_8).length, token)
          assert(
            line.startsWith(s"HTTP/1.1 $status"),
            s"$label: the connection closed early ($e) and the replayed head got: $line"
          )
          val from = text.indexOf('{')
          val to = text.lastIndexOf('}')
          assert(from >= 0 && to > from, s"$label: no JSON body in: $text")
          val j = ujson.read(text.substring(from, to + 1))
          assertEquals(j.obj.keySet.toSet, Set("error"), label)
          j("error")("code").str
    val millis = (System.nanoTime() - started) / 1000000
    assert(millis < 10000, s"$label: the refusal took $millis ms")
    assert(
      !tap.text.contains(marker),
      s"$label: the oversized body reached a log line"
    )
    assertEquals(
      Http.send("GET", a.rt.fastUrl + "/healthz", None, Map.empty).status,
      200,
      s"$label: the service is unhealthy afterwards"
    )
    code

  protected def assertClean(r: Resp, a: Api, label: String): Unit =
    val headerText = r.headers
      .filterNot(_._1 == "www-authenticate")
      .map((k, v) => s"$k: $v")
      .mkString("\n")
    val found = leaks(r.body + "\n" + headerText, tokensOf(a))
    assertEquals(found, Vector.empty[String], s"$label leaks")
    assert(
      !r.headers
        .get("www-authenticate")
        .exists(h => tokensOf(a).exists(h.contains)),
      s"$label: WWW-Authenticate carries a token"
    )

  protected def assertTraceHeaders(r: Resp, label: String): Unit =
    assert(r.header("x-request-id").exists(_.nonEmpty), s"$label: X-Request-Id")
    assert(r.header("x-trace-id").exists(_.nonEmpty), s"$label: X-Trace-Id")

  /** The constant-shape typed error body. Returns the error code. */
  protected def assertTypedError(
      r: Resp,
      status: Int,
      a: Api,
      label: String
  ): String =
    assertEquals(r.status, status, s"$label: ${r.body}")
    assert(
      r.contentType.toLowerCase.contains("json"),
      s"$label: ${r.contentType}"
    )
    assertTraceHeaders(r, label)
    val j = r.json
    assertEquals(j.obj.keySet.toSet, Set("error"), label)
    val e = j("error").obj
    assertEquals(
      e.keySet.toSet,
      Set("code", "message", "traceId", "requestId", "details"),
      label
    )
    val code = e("code").str
    assert(code.matches("[A-Z][A-Z0-9_]*"), s"$label: code $code")
    assert(e("message").str.trim.nonEmpty, s"$label: blank message")
    assert(!stackLike.pattern.matcher(e("message").str).matches(), label)
    assert(e("traceId").str.trim.nonEmpty, s"$label: blank traceId")
    assertEquals(Some(e("traceId").str), r.header("x-trace-id"), label)
    assertEquals(Some(e("requestId").str), r.header("x-request-id"), label)
    assert(e("details").isInstanceOf[ujson.Arr], s"$label: details")
    assertClean(r, a, label)
    code
