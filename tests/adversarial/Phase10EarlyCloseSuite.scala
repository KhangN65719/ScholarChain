package scholarchain.tests.adversarial

import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration
import scala.util.{Failure, Success, Try}

/** Proves the classifier behind `assertOversizedRefused` (work order 13, the m1
  * race). The race itself, a server that refuses early and closes while the
  * client library is still writing, depends on timing, so here it is forced
  * with a fake server: it must be recognised, and a timeout, a refused
  * connection and a non-IO error must not be.
  */
class Phase10EarlyCloseSuite extends P10Suite:
  private def loopback = InetAddress.getLoopbackAddress

  private val toks = Vector("TOKEN-NEVER-SENT")
  private val mib = 1024L * 1024

  // A scriptable server: `handler(connectionIndex, socket)` runs on its own
  // thread per connection, so a hanging connection never blocks the next one.
  private final class Fake(handler: (Int, Socket) => Unit):
    val server = new ServerSocket(0, 50, loopback)
    val count = new AtomicInteger
    private val sockets = new ConcurrentLinkedQueue[Socket]
    private val acceptor = new Thread(() =>
      try
        while !server.isClosed do
          val s = server.accept()
          val i = count.getAndIncrement()
          sockets.add(s)
          val h = new Thread(() =>
            Try(handler(i, s))
            ()
          )
          h.setDaemon(true)
          h.start()
      catch case _: java.io.IOException => ()
    )
    acceptor.setDaemon(true)
    acceptor.start()

    def url: String =
      s"http://${loopback.getHostAddress}:${server.getLocalPort}/v1/query"

    def close(): Unit =
      Try(server.close())
      sockets.forEach(s =>
        Try(s.close())
        ()
      )

  private def withFake[A](handler: (Int, Socket) => Unit)(use: Fake => A): A =
    val f = new Fake(handler)
    try use(f)
    finally f.close()

  private def readHead(s: Socket): String =
    val in = s.getInputStream
    val sb = new java.lang.StringBuilder
    while !sb.toString.endsWith("\r\n\r\n") do
      val b = in.read()
      if b < 0 then throw new java.io.EOFException("no request head")
      sb.append(b.toChar)
    sb.toString

  private def contentLength(head: String): Long =
    head.linesIterator
      .collectFirst {
        case l if l.toLowerCase.startsWith("content-length:") =>
          l.split(":", 2)(1).trim.toLong
      }
      .getOrElse(0L)

  private val idHeaders = Vector(
    "Content-Type: application/json",
    "X-Request-Id: req-1",
    "X-Trace-Id: trace-1",
    "Connection: close"
  )

  private def statusHead(
      status: Int,
      reason: String,
      headers: Vector[String]
  ): Array[Byte] =
    ((s"HTTP/1.1 $status $reason" +: headers).mkString("\r\n") + "\r\n\r\n")
      .getBytes("US-ASCII")

  private def typedBody(code: String, message: String): String =
    ujson.write(
      ujson.Obj(
        "error" -> ujson.Obj(
          "code" -> code,
          "message" -> message,
          "traceId" -> "trace-1",
          "requestId" -> "req-1",
          "details" -> ujson.Arr()
        )
      )
    )

  private def typed(
      status: Int,
      reason: String,
      code: String,
      message: String = "refused"
  ): Array[Byte] =
    val b = typedBody(code, message).getBytes(UTF_8)
    statusHead(
      status,
      reason,
      idHeaders :+ s"Content-Length: ${b.length}"
    ) ++ b

  private def typed401: Array[Byte] = typed(401, "Unauthorized", "UNAUTHORIZED")

  private def chunkedResponse(
      parts: Vector[Array[Byte]],
      extension: String,
      codings: String
  ): Array[Byte] =
    val body = parts.flatMap { p =>
      (Integer.toHexString(p.length) + extension + "\r\n").getBytes(
        "US-ASCII"
      ) ++ p ++ "\r\n".getBytes("US-ASCII")
    }.toArray ++ "0\r\nX-Trailer: t\r\n\r\n".getBytes("US-ASCII")
    statusHead(
      401,
      "Unauthorized",
      idHeaders :+ s"Transfer-Encoding: $codings"
    ) ++ body

  private def drainQuietly(s: Socket): Unit =
    s.setSoTimeout(500)
    val buf = new Array[Byte](8192)
    try while s.getInputStream.read(buf) >= 0 do ()
    catch case _: java.io.IOException => ()

  // Handlers ---------------------------------------------------------------

  /** Reads the head, answers, half-closes and drains, so the close is clean and
    * the client does not lose the answer to a reset.
    */
  private def answer(bytes: Array[Byte])(s: Socket): Unit =
    readHead(s)
    s.getOutputStream.write(bytes)
    s.getOutputStream.flush()
    s.shutdownOutput()
    drainQuietly(s)
    s.close()

  private def resetNoAnswer(s: Socket): Unit =
    readHead(s)
    s.setSoLinger(true, 0)
    s.close()

  private def resetAfter401(delayMillis: Long)(s: Socket): Unit =
    readHead(s)
    s.getOutputStream.write(typed401)
    s.getOutputStream.flush()
    Thread.sleep(delayMillis)
    s.setSoLinger(true, 0)
    s.close()

  private def hang(s: Socket): Unit =
    readHead(s)
    while s.getInputStream.read() >= 0 do ()

  private def holdUnread(s: Socket): Unit =
    readHead(s)
    s.getOutputStream.write(typed401)
    s.getOutputStream.flush()
    var n = 0
    while !s.isClosed && n < 300 do
      Thread.sleep(100)
      n += 1

  private def consumeThenAnswer(s: Socket): Unit =
    val head = readHead(s)
    var left = contentLength(head)
    val buf = new Array[Byte](64 * 1024)
    while left > 0 do
      val k =
        s.getInputStream.read(buf, 0, math.min(left, buf.length.toLong).toInt)
      if k < 0 then left = 0 else left -= k
    s.getOutputStream.write(typed401)
    s.getOutputStream.flush()
    s.close()

  private def answerThenConsume(s: Socket): Unit =
    readHead(s)
    s.getOutputStream.write(typed401)
    s.getOutputStream.flush()
    val buf = new Array[Byte](64 * 1024)
    while s.getInputStream.read(buf) >= 0 do ()

  private val big = "q" * (5 * 1024 * 1024)

  // F6: 401 sent, then reset ---------------------------------------------------

  test(
    "a 401 followed by a reset under a 5 MiB write is a 401 or an early close"
  ) {
    // Fails if the client library reports anything other than the typed 401 or
    // a classified early close (for example an EOF the classifier no longer
    // accepts, or a non-401 answer).
    withFake((_, s) => resetAfter401(0)(s)) { f =>
      val r = Try(
        Http.send(
          "POST",
          f.url,
          Some(big),
          Map("Content-Type" -> "application/json")
        )
      )
      r match
        case Success(resp) =>
          println("EARLY-CLOSE-PROBE direct send: the 401 won the race")
          assertTypedErrorWith(resp, 401, toks, "401 then reset")
        case Failure(e) =>
          println(s"EARLY-CLOSE-PROBE direct send failed with: $e")
          assert(isEarlyClose(e), s"not classified as an early close: $e")
    }
  }

  test(
    "postOversized returns the typed 401 when the 401 is followed by a reset"
  ) {
    // Whichever way the race goes, the result is the typed 401. Fails if the
    // early-close fallback or the replay rejects a correct refusal.
    withFake((i, s) =>
      if i == 0 then resetAfter401(0)(s) else answer(typed401)(s)
    ) { f =>
      val code = postOversized(
        f.url,
        ujson.write(ujson.Obj("question" -> big)),
        None,
        401,
        toks,
        "401 then reset"
      )
      println(s"EARLY-CLOSE-PROBE flow: connections=${f.count.get}")
      assertEquals(code, "UNAUTHORIZED")
      assert(f.count.get <= 2, s"${f.count.get} connections")
    }
  }

  // F6: the replay guard ---------------------------------------------------------

  private def guarded(
      replay: (Int, Socket) => Unit,
      status: Int = 401,
      timeoutMillis: Int = 5000
  ): (Try[String], Int) =
    withFake((i, s) => if i == 0 then resetNoAnswer(s) else replay(i, s)) {
      fake =>
        val r = Try(
          postOversized(
            fake.url,
            ujson.write(ujson.Obj("question" -> big)),
            None,
            status,
            toks,
            "guard",
            timeoutMillis
          )
        )
        (r, fake.count.get)
    }

  /** The first connection is reset (an early close); the replay is answered by
    * `replay`. The guard must reject it with an AssertionError whose message
    * has `needle`, and the early-close branch must have run (two connections).
    */
  private def assertGuardRejects(
      replay: (Int, Socket) => Unit,
      needle: String,
      timeoutMillis: Int = 5000
  ): Unit =
    val (r, connections) = guarded(replay, timeoutMillis = timeoutMillis)
    assertEquals(connections, 2, "the early-close branch did not run")
    r match
      case Failure(e: AssertionError) =>
        assert(e.getMessage.contains(needle), e.getMessage)
      case Failure(e)    => fail(s"expected an AssertionError, got $e")
      case Success(code) =>
        fail(s"the guard accepted a bad replay and returned $code")

  test("replay guard: a reset, then a typed 401 on the replay, passes") {
    withFake((i, s) =>
      if i == 0 then resetNoAnswer(s) else answer(typed401)(s)
    ) { fake =>
      val code = postOversized(
        fake.url,
        ujson.write(ujson.Obj("question" -> big)),
        None,
        401,
        toks,
        "control"
      )
      assertEquals(code, "UNAUTHORIZED")
      assertEquals(fake.count.get, 2, "the early-close branch did not run")
    }
  }

  test("replay guard: an early close then a 200 on the replay FAILS") {
    val ok = statusHead(
      200,
      "OK",
      idHeaders :+ "Content-Length: 11"
    ) ++ """{"ok":true}""".getBytes(UTF_8)
    assertGuardRejects((_, s) => answer(ok)(s), "200")
  }

  test("replay guard: an early close then a typed 500 on the replay FAILS") {
    assertGuardRejects(
      (_, s) => answer(typed(500, "Internal Server Error", "INTERNAL"))(s),
      "500"
    )
  }

  test("replay guard: an early close then a hang on the replay FAILS") {
    assertGuardRejects((_, s) => hang(s), "hangs", timeoutMillis = 700)
  }

  test("replay guard: an early close then a reset on the replay FAILS") {
    // The reset surfaces as an IOException on the raw socket, which
    // rawHeadAnswer reports as a failed connection on the replayed head.
    assertGuardRejects(
      (_, s) => resetNoAnswer(s),
      "the connection failed on the replayed head: java.net.SocketException"
    )
  }

  test("replay guard: an early close then an untyped 401 body FAILS (F2)") {
    // Right status and code, but no X-Request-Id, X-Trace-Id, message, ids or
    // details: the old replay accepted this, assertTypedErrorWith does not.
    val body = """{"error":{"code":"UNAUTHORIZED"}}""".getBytes(UTF_8)
    val untyped = statusHead(
      401,
      "Unauthorized",
      Vector(
        "Content-Type: application/json",
        s"Content-Length: ${body.length}",
        "Connection: close"
      )
    ) ++ body
    assertGuardRejects((_, s) => answer(untyped)(s), "X-Request-Id")
  }

  test("replay guard: an early close then a non-JSON 401 FAILS (F2)") {
    val body = "nope".getBytes(UTF_8)
    val plain = statusHead(
      401,
      "Unauthorized",
      Vector(
        "Content-Type: text/plain",
        "X-Request-Id: req-1",
        "X-Trace-Id: trace-1",
        s"Content-Length: ${body.length}",
        "Connection: close"
      )
    ) ++ body
    assertGuardRejects((_, s) => answer(plain)(s), "text/plain")
  }

  test("replay guard: a 413 gets no early-close fallback (F3)") {
    // Fails if a closed connection on a 413 is excused by a replay.
    withFake((_, s) => resetNoAnswer(s)) { fake =>
      intercept[java.io.IOException](
        postOversized(
          fake.url,
          ujson.write(ujson.Obj("question" -> big)),
          Some("t"),
          413,
          toks,
          "413 no fallback"
        )
      )
      assertEquals(fake.count.get, 1, "the replay ran for a 413")
    }
  }

  // F5: rawHeadAnswer framing ----------------------------------------------------

  test("rawHeadAnswer counts Content-Length in bytes, not characters") {
    val msg = "réfusé ✓ ✓ ✓"
    withFake((_, s) =>
      answer(typed(401, "Unauthorized", "UNAUTHORIZED", msg))(s)
    ) { f =>
      val r = RawHttp.rawHeadAnswer(f.url, 1000, None, 2000)
      assertEquals(r.status, 401)
      assertEquals(r.json("error")("message").str, msg)
    }
  }

  test(
    "rawHeadAnswer reads every chunk, ignores extensions, joins split UTF-8"
  ) {
    val whole = typedBody("UNAUTHORIZED", "réfusé ✓").getBytes(UTF_8)
    val cut = whole.indexOf(0xc3.toByte) + 1 // between the two bytes of é
    val parts = Vector(
      whole.slice(0, cut),
      whole.slice(cut, cut + 9),
      whole.drop(cut + 9)
    )
    withFake((_, s) =>
      answer(chunkedResponse(parts, ";ext=1;x=\"y\"", "gzip, chunked"))(s)
    ) { f =>
      val r = RawHttp.rawHeadAnswer(f.url, 1000, None, 2000)
      assertEquals(r.body, new String(whole, UTF_8))
      assertEquals(r.json("error")("code").str, "UNAUTHORIZED")
    }
  }

  test("rawHeadAnswer: a non-chunked Transfer-Encoding uses Content-Length") {
    val b = typedBody("UNAUTHORIZED", "refused").getBytes(UTF_8)
    val resp = statusHead(
      401,
      "Unauthorized",
      idHeaders ++ Vector(
        "Transfer-Encoding: identity",
        s"Content-Length: ${b.length}"
      )
    ) ++ b
    withFake((_, s) => answer(resp)(s)) { f =>
      val r = RawHttp.rawHeadAnswer(f.url, 1000, None, 2000)
      assertEquals(r.body, new String(b, UTF_8))
    }
  }

  test("rawHeadAnswer reads an unframed body to EOF") {
    val resp = (statusHead(401, "Unauthorized", Vector("Connection: close")) ++
      "tail".getBytes(UTF_8))
    withFake((_, s) => answer(resp)(s)) { f =>
      assertEquals(RawHttp.rawHeadAnswer(f.url, 1000, None, 2000).body, "tail")
    }
  }

  test("rawHeadAnswer: a hang, a reset and garbage are typed failures") {
    withFake((_, s) => hang(s)) { f =>
      val e = intercept[AssertionError](
        RawHttp.rawHeadAnswer(f.url, 1000, None, 600)
      )
      assert(e.getMessage.contains("hangs"), e.getMessage)
    }
    val partial =
      "HTTP/1.1 401 Unauthorized\r\nContent-Length: 500\r\n\r\npartial"
        .getBytes(
          UTF_8
        )
    withFake((_, s) =>
      readHead(s)
      s.getOutputStream.write(partial)
      s.getOutputStream.flush()
      Thread.sleep(100)
      s.setSoLinger(true, 0)
      s.close()
    ) { f =>
      val e = intercept[AssertionError](
        RawHttp.rawHeadAnswer(f.url, 1000, None, 2000)
      )
      // A reset after 7 of 500 body bytes is either an EOF mid-body or a
      // failed connection; both name the truncated read, never an empty text.
      assert(
        e.getMessage.contains("EOF after 7 of 500 body bytes") ||
          e.getMessage.contains("the connection failed on the replayed head"),
        e.getMessage
      )
    }
    // A clean close after the same partial body is always an EOF mid-body.
    withFake((_, s) =>
      readHead(s)
      s.getOutputStream.write(partial)
      s.getOutputStream.flush()
      s.shutdownOutput()
      drainQuietly(s)
      s.close()
    ) { f =>
      val e = intercept[AssertionError](
        RawHttp.rawHeadAnswer(f.url, 1000, None, 2000)
      )
      assertEquals(e.getMessage, "EOF after 7 of 500 body bytes")
    }
    withFake((_, s) => answer("garbage\r\n\r\n".getBytes(UTF_8))(s)) { f =>
      val e = intercept[AssertionError](
        RawHttp.rawHeadAnswer(f.url, 1000, None, 2000)
      )
      assert(e.getMessage.contains("status line"), e.getMessage)
    }
  }

  // F1: the streamed-body helper -------------------------------------------------

  test("streamOversized: a server that answers and never reads passes") {
    withFake((_, s) => holdUnread(s)) { f =>
      val r = RawHttp.streamOversized(f.url, 64 * mib, None)
      assertTypedErrorWith(r.resp, 401, toks, "hold")
      assertBodyNotConsumed(r, "hold")
      assert(r.afterGrace < r.declared)
    }
  }

  test(
    "streamOversized: a 401 then a reset is tolerated after the 401 is read"
  ) {
    withFake((_, s) => resetAfter401(200)(s)) { f =>
      val r = RawHttp.streamOversized(f.url, 64 * mib, None)
      assertEquals(r.resp.status, 401)
      assertBodyNotConsumed(r, "reset")
      assert(r.writerError.isDefined, "the writer never saw the reset")
    }
  }

  test(
    "streamOversized: a server that reads the whole body, then answers, FAILS"
  ) {
    withFake((_, s) => consumeThenAnswer(s)) { f =>
      val r =
        RawHttp.streamOversized(f.url, 64 * mib, None, timeoutMillis = 30000)
      intercept[AssertionError](assertBodyNotConsumed(r, "consume"))
    }
  }

  test("streamOversized: a server that answers, then reads the body, FAILS") {
    withFake((_, s) => answerThenConsume(s)) { f =>
      val r = RawHttp.streamOversized(f.url, 64 * mib, None, graceMillis = 1500)
      intercept[AssertionError](assertBodyNotConsumed(r, "answer then consume"))
    }
  }

  /** Reads exactly `n` body bytes (the real `oversizeDrain` is 4 MiB), answers
    * 401 and then holds without reading more.
    */
  private def readNThenAnswer(n: Long)(s: Socket): Unit =
    val head = readHead(s)
    assert(contentLength(head) > n)
    var left = n
    val buf = new Array[Byte](64 * 1024)
    while left > 0 do
      val k =
        s.getInputStream.read(buf, 0, math.min(left, buf.length.toLong).toInt)
      if k < 0 then left = 0 else left -= k
    holdUnreadAfterHead(s)

  private def holdUnreadAfterHead(s: Socket): Unit =
    s.getOutputStream.write(typed401)
    s.getOutputStream.flush()
    var n = 0
    while !s.isClosed && n < 300 do
      Thread.sleep(100)
      n += 1

  test(
    "streamOversized: a server that reads 4 MiB of the body, then answers, FAILS (M10)"
  ) {
    // The oversizeDrain size. Fails to fail (and so fails this test) if the
    // threshold ever rises above the baseline plus the 4 MiB the server read.
    withFake((_, s) => readNThenAnswer(4 * mib)(s)) { f =>
      val r = RawHttp.streamOversized(f.url, 64 * mib, None)
      assertTypedErrorWith(r.resp, 401, toks, "read 4 MiB")
      assert(r.atAnswer >= 4 * mib, s"the fake read less: ${r.atAnswer}")
      val e = intercept[AssertionError](assertBodyNotConsumed(r, "read 4 MiB"))
      assert(e.getMessage.contains("limit"), e.getMessage)
    }
  }

  /** Answers 401 at once, waits `delayMillis` (so the client has sampled the
    * at-answer count first), reads exactly `n` body bytes, then holds.
    */
  private def answerThenReadN(n: Long, delayMillis: Long)(s: Socket): Unit =
    readHead(s)
    s.getOutputStream.write(typed401)
    s.getOutputStream.flush()
    Thread.sleep(delayMillis)
    var left = n
    val buf = new Array[Byte](64 * 1024)
    while left > 0 do
      val k =
        s.getInputStream.read(buf, 0, math.min(left, buf.length.toLong).toInt)
      if k < 0 then left = 0 else left -= k
    var i = 0
    while !s.isClosed && i < 300 do
      Thread.sleep(100)
      i += 1

  test(
    "streamOversized: a server that answers, then reads 4 MiB, FAILS the after-grace check (N2)"
  ) {
    // Fails to fail (and so fails this test) if the after-grace limit climbs
    // above baseline plus 4 MiB, or if the at-answer check is what caught it.
    withFake((_, s) => answerThenReadN(4 * mib, 200)(s)) { f =>
      val r = RawHttp.streamOversized(
        f.url,
        64 * mib,
        None,
        graceMillis = RawHttp.DefaultGraceMillis
      )
      assertTypedErrorWith(r.resp, 401, toks, "answer then read 4 MiB")
      val la = BodyBaseline.limitAtAnswer(r.declared)
      val lg = BodyBaseline.limitAfterGrace(r.declared)
      println(
        s"N2-CONTROL atAnswer=${r.atAnswer} afterGrace=${r.afterGrace} " +
          s"limitAtAnswer=$la limitAfterGrace=$lg " +
          s"baselineAtAnswer=${BodyBaseline.measured.atAnswer} " +
          s"baselineAfterGrace=${BodyBaseline.measured.afterGrace}"
      )
      assert(
        r.atAnswer < la,
        s"the at-answer check would fire: ${r.atAnswer} >= $la"
      )
      assert(
        r.afterGrace >= lg,
        s"the after-grace check would not fire: ${r.afterGrace} < $lg"
      )
      assert(
        r.afterGrace - r.atAnswer >= 3 * mib,
        s"the fake did not read about 4 MiB after answering: ${r.atAnswer} to ${r.afterGrace}"
      )
      val e = intercept[AssertionError](
        assertBodyNotConsumed(r, "answer then read 4 MiB")
      )
      assert(e.getMessage.contains("after the grace period"), e.getMessage)
      assert(!e.getMessage.contains("when the answer arrived"), e.getMessage)
    }
  }

  test(
    "streamOversized: the limit sits between a never-reading and a 4 MiB reader (M10)"
  ) {
    val base = BodyBaseline.measured
    assert(base.atAnswer < 64 * mib / 4, s"baseline ${base.atAnswer}")
    assert(
      BodyBaseline.margin < 4 * mib,
      "the margin no longer separates a 4 MiB reader from the baseline"
    )
  }

  test("streamOversized: a reset with no answer fails and names the writer") {
    withFake((_, s) => resetNoAnswer(s)) { f =>
      val e = intercept[AssertionError](
        RawHttp.streamOversized(f.url, 64 * mib, None)
      )
      assert(e.getMessage.contains("writer"), e.getMessage)
    }
  }

  /** Accepts one connection, reads the head, then resets the connection while
    * the client is still writing its body.
    */
  private def resettingServer(): (ServerSocket, Thread) =
    val server = new ServerSocket(0, 1, loopback)
    val t = new Thread(
      new Runnable:
        def run(): Unit =
          Try {
            val s = server.accept()
            s.setSoLinger(true, 0)
            val in = s.getInputStream
            val buf = new Array[Byte](512)
            in.read(buf)
            s.close()
          }
          ()
    )
    t.setDaemon(true)
    t.start()
    (server, t)

  test("a connection reset while the 5 MiB body is written is an early close") {
    val (server, _) = resettingServer()
    try
      val big = "q" * (5 * 1024 * 1024)
      val r = Try(
        Http.send(
          "POST",
          s"http://${loopback.getHostAddress}:${server.getLocalPort}/v1/query",
          Some(big),
          Map("Content-Type" -> "application/json")
        )
      )
      r match
        case Failure(e) =>
          assert(isEarlyClose(e), s"not classified as an early close: $e")
        case Success(resp) =>
          fail(s"a reset connection produced a response: ${resp.status}")
    finally server.close()
  }

  test("a refused connection is not an early close") {
    val server = new ServerSocket(0, 1, loopback)
    val port = server.getLocalPort
    server.close()
    val r = Try(
      Http.send(
        "POST",
        s"http://${loopback.getHostAddress}:$port/v1/query",
        Some("{}"),
        Map.empty
      )
    )
    r match
      case Failure(e) =>
        assert(
          causeChain(e).exists(_.isInstanceOf[java.net.ConnectException]),
          s"expected a ConnectException, got $e"
        )
        assert(!isEarlyClose(e), s"misclassified: $e")
      case Success(_) => fail("nothing listens on that port")
  }

  test("a request timeout is not an early close") {
    val server = new ServerSocket(0, 1, loopback)
    try
      // HttpClient.close() is Java 21 only; shutting down the client's own
      // executor releases its threads on the project's Java 17 release.
      val pool = java.util.concurrent.Executors.newCachedThreadPool()
      val client = HttpClient.newBuilder().executor(pool).build()
      val req = HttpRequest
        .newBuilder(
          java.net.URI.create(
            s"http://${loopback.getHostAddress}:${server.getLocalPort}/"
          )
        )
        .timeout(JDuration.ofMillis(400))
        .POST(HttpRequest.BodyPublishers.ofString("{}"))
        .build()
      val r =
        try Try(client.send(req, HttpResponse.BodyHandlers.ofString()))
        finally pool.shutdownNow()
      r match
        case Failure(e) =>
          assert(
            e.isInstanceOf[java.net.http.HttpTimeoutException],
            s"expected a timeout, got $e"
          )
          assert(!isEarlyClose(e), s"a timeout was classified as a refusal: $e")
        case Success(_) => fail("a silent server answered")
    finally server.close()
  }

  test("a non-IO exception and a bare IOException are not early closes") {
    assert(!isEarlyClose(new IllegalStateException("Broken pipe")))
    assert(!isEarlyClose(new java.io.IOException("something else entirely")))
    assert(isEarlyClose(new java.io.IOException("Broken pipe")))
    // F4: a server that read the whole body and then dropped the connection,
    // and a bare "closed", are not a head-time refusal.
    assert(
      !isEarlyClose(
        new java.io.IOException(
          "HTTP/1.1 header parser received no bytes",
          new java.io.EOFException("EOF reached while reading")
        )
      )
    )
    assert(!isEarlyClose(new java.io.IOException("closed")))
    assert(!isEarlyClose(new java.io.EOFException()))
    assert(
      isEarlyClose(
        new java.io.IOException(
          "fixed content-length: 187, bytes received: 0",
          new java.io.IOException("Broken pipe")
        )
      )
    )
  }
