package scholarchain.tests.adversarial

import java.io.{BufferedReader, IOException, InputStreamReader}
import java.net.{InetSocketAddress, Socket, SocketTimeoutException, URI}
import java.util.concurrent.Executors
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}
import scholarchain.services.ServiceConfig
import scholarchain.services.runtime.LocalRuntime

/** Repair round 1 of Phase 10 (review-round-1.md): isolation of the fast and
  * slow services (M2, D14), the 503 OVERLOADED answer, the failed-job result
  * (D15, m3), unauthenticated oversized bodies (m1), a request in flight at
  * shutdown (m2), cursor secret validation (m4), the replayed 202 and the
  * 404/405 versus authentication order (nits).
  */
class Phase10RepairSuite extends P10Suite:
  private val clean = Questions.candidates.head
  private val missingEvidence = "evidence:v1:" + ("0" * 64)
  private val missingQuery = "query:v1:" + ("0" * 64)
  private val missingNode = "researcher:v1:" + ("0" * 64)

  private def concurrently[A](n: Int)(call: Int => A): Vector[A] =
    val pool = Executors.newFixedThreadPool(n)
    try
      given ExecutionContext = ExecutionContext.fromExecutor(pool)
      val fs = (0 until n).map(i => Future(call(i)))
      Await.result(Future.sequence(fs), 90.seconds).toVector
    finally pool.shutdownNow()

  private def errorCode(r: Resp): String = r.json("error")("code").str

  // M2 and D14: isolation -------------------------------------------------------

  test("M2: a slow workflow does not slow, fail or open the evidence reads") {
    val iso = launch(
      Faults.workflow(Faults.Slow) ++ Map(
        "calls.poolSize" -> "2",
        "calls.queueCapacity" -> "2",
        "fault.slowMillis" -> "6000"
      )
    )
    val pool = Executors.newFixedThreadPool(8)
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      val queries = (0 until 8).map(_ => Future(iso.ask(clean)))
      Thread.sleep(500)
      // More failures than the breaker threshold (5) would be needed to open it
      // if these reads shared the workflow's fate: every read must be a 404.
      (1 to 12).foreach { i =>
        val started = System.nanoTime()
        val r = iso.get(s"/v1/evidence/$missingEvidence")
        val millis = (System.nanoTime() - started) / 1000000
        assertEquals(
          assertTypedError(r, 404, iso, s"evidence read $i"),
          "NOT_FOUND"
        )
        assert(millis < 1000, s"evidence read $i took $millis ms")
      }
      assertTypedError(
        iso.get(s"/v1/explain/$missingQuery"),
        404,
        iso,
        "explain read"
      )
      assertTypedError(
        iso.get(s"/v1/graph/nodes/$missingNode"),
        404,
        iso,
        "graph read"
      )
      val ready = Http.send("GET", iso.rt.fastUrl + "/readyz", None, Map.empty)
      assertEquals(ready.status, 200, ready.body)
      val components = ready.json("components").obj
      assertEquals(components("evidence").str, "up", ready.body)
      assertEquals(components("graph").str, "up", ready.body)
      assertEquals(components("explain").str, "up", ready.body)
      assertEquals(ready.json("status").str, "ready", ready.body)
      val rs = Await.result(Future.sequence(queries), 60.seconds)
      rs.foreach(r => assertTypedError(r, r.status, iso, "slow query"))
      assert(
        rs.forall(r => r.status == 503 || r.status == 504),
        rs.map(_.status).toString
      )
      // The breaker is still closed after the slow queries drain.
      assertTypedError(
        iso.get(s"/v1/evidence/$missingEvidence"),
        404,
        iso,
        "evidence read after the queries"
      )
    finally pool.shutdownNow()
  }

  test("M2: a full call pool and queue is 503 OVERLOADED with a trace ID") {
    val tight = launch(
      Faults.workflow(Faults.Slow) ++ Map(
        "calls.poolSize" -> "1",
        "calls.queueCapacity" -> "1",
        "fault.slowMillis" -> "6000"
      )
    )
    val rs = concurrently(6)(_ => tight.ask(clean))
    val overloaded = rs.filter(_.status == 503)
    assert(
      overloaded.nonEmpty,
      s"no 503 among ${rs.map(_.status)}: a saturated pool did not shed load"
    )
    overloaded.foreach { r =>
      assertEquals(
        assertTypedError(r, 503, tight, "overloaded"),
        "OVERLOADED"
      )
      assertEquals(r.header("retry-after"), Some("1"))
    }
    assert(
      rs.forall(r => r.status == 503 || r.status == 504),
      rs.map(_.status).toString
    )
    rs.filter(_.status == 504)
      .foreach(r => assertEquals(errorCode(r), "TIMEOUT"))
  }

  test(
    "M2: a full job queue is 503 OVERLOADED and the job is not left behind"
  ) {
    val tight = launch(
      Faults.workflow(Faults.Slow) ++ Map(
        "jobs.executorSize" -> "1",
        "jobs.queueCapacity" -> "1",
        "fault.slowMillis" -> "6000"
      )
    )
    val rs = concurrently(6)(_ => tight.askAsync(clean))
    val statuses = rs.map(_.status)
    assert(statuses.forall(s => s == 202 || s == 503), statuses.toString)
    assert(statuses.contains(202), statuses.toString)
    val refused = rs.filter(_.status == 503)
    assert(refused.nonEmpty, s"the job queue never filled: $statuses")
    refused.foreach { r =>
      assertEquals(
        assertTypedError(r, 503, tight, "job queue full"),
        "OVERLOADED"
      )
    }
  }

  // D15 and m3 ------------------------------------------------------------------

  private def failedJob(a: Api): (String, Resp) =
    val accepted = a.askAsync(clean)
    assertEquals(accepted.status, 202, accepted.body)
    val jobId = Json.firstString(accepted.json, "jobId").get
    val done = a.awaitJob(jobId)
    assertEquals(done.status, 200, done.body)
    assertEquals(done.json("state").str, "failed", done.body)
    (jobId, done)

  test(
    "D15: a failed job's result is 409 JOB_FAILED with the cause in details"
  ) {
    val failing = launch(Faults.workflow(Faults.Fail))
    val (jobId, done) = failedJob(failing)
    val original = done.json("error")("code").str
    assertEquals(original, "DOWNSTREAM_UNAVAILABLE")
    val result = failing.get(s"/v1/jobs/$jobId/result")
    assertEquals(assertTypedError(result, 409, failing, "failed"), "JOB_FAILED")
    assertEquals(
      result.json("error")("details").arr.map(_.str).toVector,
      Vector(original)
    )
  }

  test("D15: a timed-out job's result is 409 JOB_FAILED, never a 504 replay") {
    val slow = launch(Faults.workflow(Faults.Slow))
    val (jobId, done) = failedJob(slow)
    val original = done.json("error")("code").str
    assertEquals(original, "TIMEOUT")
    val result = slow.get(s"/v1/jobs/$jobId/result")
    assertEquals(
      assertTypedError(result, 409, slow, "timed-out job"),
      "JOB_FAILED"
    )
    assertEquals(
      result.json("error")("details").arr.map(_.str).toVector,
      Vector("TIMEOUT")
    )
    // The status route still reports the original failure.
    val status = slow.get(s"/v1/jobs/$jobId")
    assertEquals(status.status, 200, status.body)
    assertEquals(status.json("error")("code").str, "TIMEOUT")
    assertEquals(status.json("resultLink"), ujson.Null)
  }

  test("D15: JOB_FAILED differs from RESULT_NOT_READY and the job is stable") {
    val failing = launch(Faults.workflow(Faults.Fail))
    val (jobId, _) = failedJob(failing)
    val first = failing.get(s"/v1/jobs/$jobId/result")
    val second = failing.get(s"/v1/jobs/$jobId/result")
    assertEquals(errorCode(first), "JOB_FAILED")
    assertEquals(errorCode(second), "JOB_FAILED")
    assertEquals(first.status, second.status)
    assertNotEquals(errorCode(first), "RESULT_NOT_READY")
  }

  // m1: an unauthenticated oversized body ---------------------------------------

  private def rawExchange(
      url: String,
      head: Vector[String],
      body: Array[Byte],
      soTimeoutMillis: Int = 5000
  ): (Option[String], Long) =
    val uri = URI.create(url)
    val socket = new Socket
    val started = System.nanoTime()
    try
      socket.connect(new InetSocketAddress(uri.getHost, uri.getPort), 5000)
      socket.setSoTimeout(soTimeoutMillis)
      val out = socket.getOutputStream
      out.write((head.mkString("\r\n") + "\r\n\r\n").getBytes("US-ASCII"))
      out.write(body)
      out.flush()
      val in = new BufferedReader(
        new InputStreamReader(socket.getInputStream, "US-ASCII")
      )
      val line =
        try Option(in.readLine())
        catch
          case _: SocketTimeoutException =>
            fail(s"no response within $soTimeoutMillis ms: the server hangs")
      (line, (System.nanoTime() - started) / 1000000)
    finally socket.close()

  private def oversizedHead(
      a: Api,
      length: Long,
      token: Option[String]
  ): Vector[String] =
    Vector(
      "POST /v1/query HTTP/1.1",
      s"Host: ${URI.create(a.url("/v1/query")).getAuthority}",
      "Content-Type: application/json",
      s"Content-Length: $length",
      "Connection: close"
    ) ++ token.map(t => s"Authorization: Bearer $t")

  test("m1: an unauthenticated oversized body is 401 at once, body unread") {
    val declared = 64L * 1024 * 1024
    val (line, millis) = rawExchange(
      api.url("/v1/query"),
      oversizedHead(api, declared, None),
      Array.fill(2048)('x'.toByte)
    )
    assert(line.exists(_.startsWith("HTTP/1.1 401")), s"status line: $line")
    assert(millis < 3000, s"the refusal took $millis ms")
  }

  test("m1: a wrong-scope caller with an oversized body is 403 at once") {
    val (line, millis) = rawExchange(
      api.url("/v1/query"),
      oversizedHead(api, 64L * 1024 * 1024, Some(api.readToken)),
      Array.fill(2048)('x'.toByte)
    )
    assert(line.exists(_.startsWith("HTTP/1.1 403")), s"status line: $line")
    assert(millis < 3000, s"the refusal took $millis ms")
  }

  test("m1: an authorized caller declaring a huge body is 413 at once") {
    val (line, millis) = rawExchange(
      api.url("/v1/query"),
      oversizedHead(api, 64L * 1024 * 1024, Some(api.queryToken)),
      Array.empty[Byte]
    )
    assert(line.exists(_.startsWith("HTTP/1.1 413")), s"status line: $line")
    assert(millis < 3000, s"the refusal took $millis ms")
  }

  test("m1: a 5 MiB body from an unauthenticated client library is refused") {
    // The server may answer 401 and close while the client is still writing:
    // the client then reports a broken pipe. Both outcomes are the refusal; the
    // helper proves the body was never read (see assertOversizedRefused).
    assertEquals(
      assertOversizedRefused(
        api,
        "/v1/query",
        "M1MARK-" + java.util.UUID.randomUUID(),
        5 * 1024 * 1024,
        None,
        401,
        "oversized anonymous"
      ),
      "UNAUTHORIZED"
    )
    // The server is still serving afterwards.
    assertEquals(api.get(s"/v1/evidence/$missingEvidence").status, 404)
  }

  // m2: a request in flight at close() ------------------------------------------

  test(
    "m2: requests in flight at close() end with a typed error or a clean failure"
  ) {
    val rt = launch(
      Faults.workflow(Faults.Slow) ++ Faults.evidence(Faults.Slow) ++ Map(
        "fault.explain" -> Faults.Slow,
        "fault.slowMillis" -> "20000",
        "slow.deadlineMillis" -> "30000",
        "fast.deadlineMillis" -> "30000"
      )
    )
    val pool = Executors.newFixedThreadPool(4)
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      val inFlight = Vector(
        Future(Try(rt.ask(clean))),
        Future(Try(rt.get(s"/v1/evidence/$missingEvidence"))),
        Future(Try(rt.askAsync(clean))),
        Future(Try(rt.get(s"/v1/explain/$missingQuery")))
      )
      Thread.sleep(700)
      val closing = System.nanoTime()
      rt.rt.close()
      val results =
        try Await.result(Future.sequence(inFlight), 25.seconds)
        catch
          case _: TimeoutException =>
            fail("a request in flight at close() never finished: it hangs")
      val millis = (System.nanoTime() - closing) / 1000000
      assert(millis < 20000, s"close() to the last answer took $millis ms")
      results.zipWithIndex.foreach {
        case (Success(r), i) =>
          if r.status == 202 then
            assert(Json.firstString(r.json, "jobId").isDefined, r.body)
          else
            assert(
              Set(500, 503, 504).contains(r.status),
              s"request $i: ${r.status} ${r.body}"
            )
            assertTypedError(r, r.status, rt, s"in flight $i")
        case (Failure(e), i) =>
          assert(
            e.isInstanceOf[IOException],
            s"request $i failed with ${e.getClass.getName}, not a clean connection failure"
          )
      }
    finally pool.shutdownNow()
  }

  // m4: cursor secret -----------------------------------------------------------

  private val tokens = Map(
    "auth.queryToken" -> "q" * 20,
    "auth.readToken" -> "r" * 20
  )

  test("m4: an empty or too-short cursor secret fails configuration load") {
    Vector("", " ", "a", "short", "fifteen-bytes!!").foreach { secret =>
      val e = intercept[IllegalArgumentException](
        ServiceConfig.from(tokens + ("auth.cursorSecret" -> secret))
      )
      assert(e.getMessage.contains("auth.cursorSecret"), e.getMessage)
      if secret.trim.length > 3 then
        assert(!e.getMessage.contains(secret), "the message echoes the secret")
      val started = intercept[IllegalArgumentException](
        LocalRuntime.start(Map("auth.cursorSecret" -> secret))
      )
      assert(
        started.getMessage.contains("auth.cursorSecret"),
        started.getMessage
      )
    }
  }

  test(
    "m4: a control character in the cursor secret fails configuration load"
  ) {
    val e = intercept[IllegalArgumentException](
      ServiceConfig.from(
        tokens + ("auth.cursorSecret" -> "0123456789abcdef\u0000x")
      )
    )
    assert(e.getMessage.contains("auth.cursorSecret"), e.getMessage)
  }

  test(
    "m4: a 16-byte secret loads and signs cursors; a missing one is random"
  ) {
    val ok = ServiceConfig.from(
      tokens + ("auth.cursorSecret" -> "0123456789abcdef")
    )
    assertEquals(ok.cursorSecret, Some("0123456789abcdef"))
    // Eight two-byte characters are 16 UTF-8 bytes.
    val wide = ServiceConfig.from(
      tokens + ("auth.cursorSecret" -> ("é" * 8))
    )
    assert(wide.cursorSecret.isDefined)
    assertEquals(ServiceConfig.from(tokens).cursorSecret, None)
    val a = launch(Map("auth.cursorSecret" -> "0123456789abcdef"))
    assertEquals(a.get("/healthz").status, 200)
  }

  test("m4: a configured secret never reaches a response or a log") {
    val secret = "cursor-secret-canary-0123456789"
    val a = launch(Map("auth.cursorSecret" -> secret))
    val rs = Vector(
      a.get("/v1/graph/nodes/researcher:v1:x/neighbors?cursor=abc"),
      a.get(s"/v1/graph/nodes/$missingNode/neighbors"),
      a.anonymous("/v1/graph/nodes/x")
    )
    rs.foreach(r => assert(!r.body.contains(secret)))
    assert(!tap.text.contains(secret), "the cursor secret reached a log line")
  }

  // Nits: replayed 202 ----------------------------------------------------------

  test("nit: a replayed 202 reports the job's current state, not queued") {
    val h = Map("Idempotency-Key" -> ("key-" + java.util.UUID.randomUUID()))
    val body = Map("mode" -> ujson.Str("async"))
    val first = api.ask(clean, body, h)
    assertEquals(first.status, 202, first.body)
    assertEquals(first.json("state").str, "queued")
    val jobId = first.json("jobId").str
    val done = api.awaitJob(jobId)
    assertEquals(done.json("state").str, "succeeded", done.body)
    val replay = api.ask(clean, body, h)
    assertEquals(replay.status, 202, replay.body)
    assertEquals(replay.json("jobId").str, jobId)
    assertEquals(replay.json("state").str, "succeeded", replay.body)
    assertEquals(replay.json("statusLink").str, s"/v1/jobs/$jobId")
    assertEquals(replay.header("location"), first.header("location"))
    assertEquals(first.header("location"), Some(s"/v1/jobs/$jobId"))
  }

  // Nits: 404 and 405 versus authentication -------------------------------------

  test("nit: an anonymous caller gets 401, never 404 or 405, under /v1") {
    Vector(
      ("GET", "/v1/no-such-route"),
      ("GET", "/v1/graph/nodes"),
      ("GET", "/v1/evidence/a/b/c"),
      ("DELETE", "/v1/query"),
      ("GET", "/v1/query"),
      ("POST", s"/v1/evidence/$missingEvidence"),
      ("PUT", "/v1/jobs/job-x")
    ).foreach { (verb, path) =>
      val r = Http.send(verb, api.url(path), None, Map.empty)
      assertEquals(
        assertTypedError(r, 401, api, s"anonymous $verb $path"),
        "UNAUTHORIZED"
      )
      assertEquals(r.header("allow"), None, s"$verb $path leaks the methods")
    }
    val badToken = Http.send(
      "GET",
      api.url("/v1/no-such-route"),
      None,
      Map("Authorization" -> "Bearer not-a-valid-token-at-all")
    )
    assertTypedError(badToken, 401, api, "invalid token on an unknown route")
  }

  test("nit: an authenticated caller gets 404 for a route and 405 with Allow") {
    Vector(api.readToken, api.queryToken).foreach { token =>
      val r = api.get("/v1/no-such-route", token = Some(token))
      assertEquals(assertTypedError(r, 404, api, "unknown route"), "NOT_FOUND")
    }
    val wrong = api.method("DELETE", "/v1/query", Some(api.queryToken))
    assertEquals(
      assertTypedError(wrong, 405, api, "DELETE /v1/query"),
      "METHOD_NOT_ALLOWED"
    )
    assert(
      wrong.header("allow").exists(_.contains("POST")),
      wrong.headers.toString
    )
    val get =
      api.method("POST", s"/v1/evidence/$missingEvidence", Some(api.readToken))
    assertEquals(
      assertTypedError(get, 405, api, "POST evidence"),
      "METHOD_NOT_ALLOWED"
    )
    assert(get.header("allow").exists(_.contains("GET")), get.headers.toString)
  }

  test("nit: outside /v1 an anonymous caller still gets 404 and 405") {
    val unknown =
      Http.send("GET", api.rt.fastUrl + "/nothing-here", None, Map.empty)
    assertEquals(
      assertTypedError(unknown, 404, api, "outside /v1"),
      "NOT_FOUND"
    )
    val post =
      Http.send("POST", api.rt.fastUrl + "/healthz", Some("{}"), Map.empty)
    assertEquals(
      assertTypedError(post, 405, api, "POST /healthz"),
      "METHOD_NOT_ALLOWED"
    )
    assert(
      post.header("allow").exists(_.contains("GET")),
      post.headers.toString
    )
    val metrics = api.anonymous("/metrics")
    assertTypedError(metrics, 401, api, "metrics with no token")
  }
