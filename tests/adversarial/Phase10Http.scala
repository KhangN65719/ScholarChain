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

/** Raw-socket HTTP/1.1 for the cases a client library cannot produce: a head
  * that declares far more body than is sent, and a body streamed by a writer
  * thread while the main thread watches for the answer. Failures are
  * `AssertionError`s with a message, never a bare socket exception.
  */
object RawHttp:
  import java.io.{BufferedInputStream, ByteArrayOutputStream, IOException}
  import java.io.InputStream
  import java.net.{InetSocketAddress, Socket, SocketTimeoutException}
  import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

  private val statusRe = "HTTP/1\\.[01] (\\d{3})(?: .*)?".r

  private def bad(msg: String): Nothing = throw new AssertionError(msg)

  private def readLine(in: InputStream): Option[String] =
    val sb = new ByteArrayOutputStream
    var b = in.read()
    if b < 0 then None
    else
      while b >= 0 && b != 10 do
        sb.write(b)
        b = in.read()
      if b < 0 then bad("EOF inside a protocol line")
      Some(sb.toString("ISO-8859-1").stripSuffix("\r"))

  private def readFully(in: InputStream, n: Int): Array[Byte] =
    if n > (1 << 20) then bad(s"a $n byte response body is not a typed error")
    val buf = new Array[Byte](n)
    var off = 0
    while off < n do
      val k = in.read(buf, off, n - off)
      if k < 0 then bad(s"EOF after $off of $n body bytes")
      off += k
    buf

  private def readToEof(in: InputStream): Array[Byte] =
    val sink = new ByteArrayOutputStream
    val buf = new Array[Byte](4096)
    var k = in.read(buf)
    while k >= 0 do
      sink.write(buf, 0, k)
      if sink.size > (1 << 20) then bad("a response body over 1 MiB")
      k = in.read(buf)
    sink.toByteArray

  /** One framed response: Content-Length counts bytes, `Transfer-Encoding`
    * counts as chunked only when its last coding is `chunked`, chunk extensions
    * are ignored, every chunk and the trailer are consumed, and a response with
    * neither framing is read to EOF.
    */
  def readResponse(in: InputStream): Resp =
    val statusLine =
      readLine(in).getOrElse(bad("the connection closed before a status line"))
    val status = statusLine match
      case statusRe(code) => code.toInt
      case _              => bad(s"not an HTTP/1.x status line: $statusLine")
    val pairs = Vector.newBuilder[(String, String)]
    var line = readLine(in).getOrElse(bad("EOF inside the headers"))
    while line.nonEmpty do
      val i = line.indexOf(':')
      if i > 0 then
        pairs += line.substring(0, i).trim.toLowerCase -> line
          .substring(i + 1)
          .trim
      line = readLine(in).getOrElse(bad("EOF inside the headers"))
    val hs = pairs.result()
    val length = hs.collectFirst { case ("content-length", v) => v }
    val codings = hs
      .collect { case ("transfer-encoding", v) => v }
      .flatMap(_.split(",").toVector)
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
    val chunked = codings.lastOption.contains("chunked")
    val bytes =
      if chunked then
        val sink = new ByteArrayOutputStream
        var size = -1
        while size != 0 do
          val sizeLine =
            readLine(in).getOrElse(bad("EOF before a chunk size line"))
          size =
            try Integer.parseInt(sizeLine.takeWhile(_ != ';').trim, 16)
            catch
              case _: NumberFormatException =>
                bad(s"bad chunk size line: $sizeLine")
          if size > 0 then
            sink.write(readFully(in, size))
            val end = readLine(in).getOrElse(bad("EOF after a chunk"))
            if end.nonEmpty then bad(s"no CRLF after a chunk: $end")
        var trailer = readLine(in).getOrElse("")
        while trailer.nonEmpty do trailer = readLine(in).getOrElse("")
        sink.toByteArray
      else if length.isDefined then
        val n =
          try length.get.toInt
          catch
            case _: NumberFormatException =>
              bad(s"bad Content-Length: ${length.get}")
        readFully(in, n)
      else readToEof(in)
    val headers = hs.reverse.toMap
    Resp(status, headers, new String(bytes, UTF_8))

  private def headBytes(
      target: String,
      authority: String,
      declared: Long,
      token: Option[String]
  ): Array[Byte] =
    val lines = Vector(
      s"POST $target HTTP/1.1",
      s"Host: $authority",
      "Content-Type: application/json",
      s"Content-Length: $declared",
      "Connection: close"
    ) ++ token.map(t => s"Authorization: Bearer $t")
    (lines.mkString("\r\n") + "\r\n\r\n").getBytes("US-ASCII")

  private def targetOf(uri: URI): String =
    Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/") +
      Option(uri.getRawQuery).fold("")("?" + _)

  /** The server's answer to a request head that declares `declared` bytes but
    * sends 2048: a raw socket, because a client library cannot be made to stop
    * writing. 5 s connect and read timeouts, closed in `finally`. A hang, a
    * reset or a malformed answer fails with a message naming it.
    */
  def rawHeadAnswer(
      url: String,
      declared: Long,
      token: Option[String],
      timeoutMillis: Int = 5000
  ): Resp =
    val uri = URI.create(url)
    val socket = new Socket
    try
      try
        socket.connect(
          new InetSocketAddress(uri.getHost, uri.getPort),
          timeoutMillis
        )
        socket.setSoTimeout(timeoutMillis)
        val out = socket.getOutputStream
        out.write(headBytes(targetOf(uri), uri.getAuthority, declared, token))
        out.write(Array.fill(2048)('x'.toByte))
        out.flush()
        readResponse(new BufferedInputStream(socket.getInputStream))
      catch
        case _: SocketTimeoutException =>
          bad(
            s"no refusal within $timeoutMillis ms on the replayed head: it hangs"
          )
        case e: IOException =>
          bad(s"the connection failed on the replayed head: $e")
    finally socket.close()

  /** What a streamed oversized body did: bytes the writer had handed to the
    * kernel when the answer arrived (`atAnswer`) and after a grace period
    * (`afterGrace`), and the writer's failure, if any.
    */
  final case class Streamed(
      resp: Resp,
      declared: Long,
      atAnswer: Long,
      afterGrace: Long,
      writerError: Option[Throwable]
  )

  /** Declare `declared` bytes and stream them in chunks from a writer thread
    * while the calling thread reads the answer. A writer failure is kept, and
    * is tolerated only because the answer was read: if no answer arrives, the
    * failure is part of the assertion message.
    */
  /** The grace period shared by the baseline, the real-service checks and the
    * N2 control, so the control proves the period the checks use.
    */
  val DefaultGraceMillis: Int = 500

  def streamOversized(
      url: String,
      declared: Long,
      token: Option[String],
      graceMillis: Int = DefaultGraceMillis,
      timeoutMillis: Int = 5000,
      chunkBytes: Int = 64 * 1024
  ): Streamed =
    val uri = URI.create(url)
    val socket = new Socket
    val written = new AtomicLong
    val failure = new AtomicReference[Throwable]
    var writer: Thread = null
    try
      socket.connect(
        new InetSocketAddress(uri.getHost, uri.getPort),
        timeoutMillis
      )
      socket.setSoTimeout(timeoutMillis)
      val out = socket.getOutputStream
      out.write(headBytes(targetOf(uri), uri.getAuthority, declared, token))
      out.flush()
      writer = new Thread(() =>
        try
          val chunk = Array.fill(chunkBytes)('x'.toByte)
          var left = declared
          while left > 0 do
            val n = math.min(left, chunk.length.toLong).toInt
            out.write(chunk, 0, n)
            written.addAndGet(n.toLong)
            left -= n
          out.flush()
        catch case e: Throwable => failure.set(e)
      )
      writer.setDaemon(true)
      writer.start()
      def state =
        s"the writer had sent ${written.get} bytes, failure ${failure.get}"
      val resp =
        try readResponse(new BufferedInputStream(socket.getInputStream))
        catch
          case _: SocketTimeoutException =>
            bad(s"no answer within $timeoutMillis ms: it hangs; $state")
          case e: IOException =>
            bad(s"no answer, the connection failed: $e; $state")
          case e: AssertionError =>
            throw new AssertionError(s"${e.getMessage}; $state", e)
      val atAnswer = written.get
      Thread.sleep(graceMillis.toLong)
      Streamed(resp, declared, atAnswer, written.get, Option(failure.get))
    finally
      socket.close()
      if writer != null then writer.join(2000)

/** The bytes the loopback buffers take from a 64 MiB streamed body when the
  * server answers 401 at once and then holds the socket open without reading a
  * single body byte (it only sleeps until the socket is closed). Measured once
  * per JVM against a fake server, so the body-consumed limit follows the
  * machine's buffer sizes instead of assuming them. Because the fake never
  * reads, the baseline is only what the OS buffers swallow and does not depend
  * on how fast any read loop runs.
  */
object BodyBaseline:
  val margin: Long = 2L * 1024 * 1024

  def limitAtAnswer(declared: Long): Long =
    math.min(measured.atAnswer + margin, declared / 4)

  def limitAfterGrace(declared: Long): Long =
    math.min(measured.afterGrace + margin, declared / 4)

  lazy val measured: RawHttp.Streamed =
    import java.net.{InetAddress, ServerSocket}
    val server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress)
    val held = new java.util.concurrent.ConcurrentLinkedQueue[java.net.Socket]
    val acceptor = new Thread(() =>
      try
        val s = server.accept()
        held.add(s)
        val in = s.getInputStream
        val sb = new java.lang.StringBuilder
        while !sb.toString.endsWith("\r\n\r\n") do
          val b = in.read()
          if b < 0 then throw new java.io.EOFException("no head")
          sb.append(b.toChar)
        s.getOutputStream.write(
          "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            .getBytes("US-ASCII")
        )
        s.getOutputStream.flush()
        var n = 0
        while !s.isClosed && n < 300 do
          Thread.sleep(100)
          n += 1
      catch case _: java.io.IOException => ()
    )
    acceptor.setDaemon(true)
    acceptor.start()
    try
      val url =
        s"http://${InetAddress.getLoopbackAddress.getHostAddress}:${server.getLocalPort}/v1/query"
      val r = RawHttp.streamOversized(url, 64L * 1024 * 1024, None)
      assert(
        r.resp.status == 401,
        s"the calibration server answered ${r.resp.status}"
      )
      r
    finally
      server.close()
      held.forEach(s => Try(s.close()))

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

  protected def causeChain(e: Throwable): Vector[Throwable] =
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
      m.contains("fixed content-length")
    }

  private val headTimeRefusals = Set(401, 403)

  /** The replayed head: the same request head with a tiny body, answered over a
    * raw socket. It must check as much as the normal path, so the answer goes
    * through `assertTypedErrorWith` (content type, X-Request-Id, X-Trace-Id,
    * message, traceId, requestId, details and the leak checks). Returns the
    * error code.
    */
  protected def assertReplayRefused(
      url: String,
      declared: Long,
      token: Option[String],
      status: Int,
      tokens: Vector[String],
      label: String,
      timeoutMillis: Int = 5000
  ): String =
    val r = RawHttp.rawHeadAnswer(url, declared, token, timeoutMillis)
    assertTypedErrorWith(r, status, tokens, s"$label (replayed head)")

  /** POST `body` with the client library and require the typed `status`. The
    * one tolerated exception is a connection the server closed while the client
    * was still writing, and only for a head-time refusal (401 or 403): the
    * server answers from the head and never reads the body, so a client library
    * may see a broken pipe. Then the same head is replayed with a tiny body and
    * must yield the full typed `status` answer. A 413 gets no such fallback:
    * for an authorized caller the server reads and discards up to
    * `oversizeDrain` (4 MiB) before answering, so the client finishes writing
    * and a closed connection would be a defect, and a replay declaring about 3
    * MiB while sending 2048 bytes would only wait for the rest.
    */
  protected def postOversized(
      url: String,
      body: String,
      token: Option[String],
      status: Int,
      tokens: Vector[String],
      label: String,
      replayTimeoutMillis: Int = 5000
  ): String =
    val headers =
      Map("Content-Type" -> "application/json") ++
        token.map(t => "Authorization" -> s"Bearer $t")
    try
      val r = Http.send("POST", url, Some(body), headers)
      assertTypedErrorWith(r, status, tokens, label)
    catch
      case e: java.io.IOException
          if headTimeRefusals.contains(status) && isEarlyClose(e) =>
        println(s"EARLY-CLOSE-REPLAY $label: $e")
        assertReplayRefused(
          url,
          body.getBytes(UTF_8).length.toLong,
          token,
          status,
          tokens,
          label,
          replayTimeoutMillis
        )

  /** The body was not consumed. The limit is calibrated on this machine: a fake
    * server that answers 401 and never reads (`BodyBaseline`) shows how many
    * bytes the loopback buffers swallow with no reader, and the real service
    * may exceed that by at most `BodyBaseline.margin` (2 MiB), both when the
    * answer arrived and after the grace period. The real 4 MiB `oversizeDrain`
    * read is therefore detected, since a server that reads 4 MiB moves about 4
    * MiB more than the baseline (the negative control in
    * Phase10EarlyCloseSuite); a server that answers and then reads 4 MiB is
    * caught only by the after-grace check (control N2). `declared / 4` (16 MiB)
    * stays as a hard upper bound on the limit, derived from the buffers a
    * never-reading server can leave in flight (macOS about 0.5 MiB; Linux
    * autotuning at most about 4 MiB send plus 6 MiB receive, plus the 64 KiB
    * the JDK HttpServer drains), so a Linux baseline can never loosen the check
    * past 16 MiB. A server that reads the whole body, before or after
    * answering, moves 64 MiB within the grace period and exceeds both. The
    * measured values are printed on a `BODY-NOT-CONSUMED` line.
    */
  protected def assertBodyNotConsumed(
      s: RawHttp.Streamed,
      label: String
  ): Unit =
    val base = BodyBaseline.measured
    val limitAnswer = BodyBaseline.limitAtAnswer(s.declared)
    val limitGrace = BodyBaseline.limitAfterGrace(s.declared)
    val line =
      s"BODY-NOT-CONSUMED $label atAnswer=${s.atAnswer} afterGrace=${s.afterGrace} " +
        s"baselineAtAnswer=${base.atAnswer} baselineAfterGrace=${base.afterGrace} " +
        s"limitAtAnswer=$limitAnswer limitAfterGrace=$limitGrace"
    println(line)
    val _ = Try {
      val file = java.nio.file.Paths.get("target/phase10-body-measurements.txt")
      java.nio.file.Files.writeString(
        file,
        line + "\n",
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      )
    }
    assert(
      s.atAnswer < limitAnswer,
      s"$label: ${s.atAnswer} of ${s.declared} bytes were written when the answer arrived (limit $limitAnswer)"
    )
    assert(
      s.afterGrace < limitGrace,
      s"$label: ${s.afterGrace} of ${s.declared} bytes were written after the grace period (limit $limitGrace): the body is being consumed"
    )

  /** POST an oversized body that the server must refuse with `status`, and
    * return the typed error code. See `postOversized` for the tolerated early
    * close. In every case the body marker must be absent from the logs and the
    * service must still be healthy. (That the body is never read is proven
    * separately by `assertBodyNotConsumed`; the service logs no question text.)
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
    val started = System.nanoTime()
    val code =
      postOversized(a.url(path), body, token, status, tokensOf(a), label)
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
    assertCleanWith(r, tokensOf(a), label)

  protected def assertCleanWith(
      r: Resp,
      tokens: Vector[String],
      label: String
  ): Unit =
    val headerText = r.headers
      .filterNot(_._1 == "www-authenticate")
      .map((k, v) => s"$k: $v")
      .mkString("\n")
    val found = leaks(r.body + "\n" + headerText, tokens)
    assertEquals(found, Vector.empty[String], s"$label leaks")
    assert(
      !r.headers
        .get("www-authenticate")
        .exists(h => tokens.exists(h.contains)),
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
  ): String = assertTypedErrorWith(r, status, tokensOf(a), label)

  protected def assertTypedErrorWith(
      r: Resp,
      status: Int,
      tokens: Vector[String],
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
    assertCleanWith(r, tokens, label)
    code
