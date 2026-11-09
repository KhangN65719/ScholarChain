package scholarchain.tests.adversarial

import java.util.concurrent.Executors
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** 401 versus 403, headers, constant-shape errors on every status in the spec,
  * idempotency conflicts and concurrency, method and media-type checks, and the
  * optional metrics endpoint. Written from spec.md; not executed.
  */
class Phase10ApiContractSuite extends P10Suite:
  private val clean = Questions.candidates.head
  private val node = "researcher:v1:" + ("1" * 64)
  private val readRoutes = Vector(
    "/v1/jobs/job-x",
    "/v1/jobs/job-x/result",
    "/v1/evidence/evidence:v1:" + ("0" * 64),
    s"/v1/graph/nodes/$node",
    s"/v1/graph/nodes/$node/neighbors",
    "/v1/explain/query:v1:" + ("0" * 64)
  )

  // 401 versus 403 ---------------------------------------------------------------

  test("401: missing, malformed, wrong and truncated credentials") {
    val good = api.readToken
    val bad = Vector(
      Map.empty[String, String],
      Map("Authorization" -> ""),
      Map("Authorization" -> "Bearer"),
      Map("Authorization" -> "Bearer "),
      Map("Authorization" -> "Bearer wrong-token-value"),
      Map("Authorization" -> s"Basic $good"),
      Map("Authorization" -> good),
      Map("Authorization" -> s"Bearer ${good.dropRight(1)}"),
      Map("Authorization" -> s"Bearer ${good}x"),
      Map("Authorization" -> s"Bearer ${good.toUpperCase}x"),
      Map("Authorization" -> s"Token $good")
    )
    readRoutes.foreach { path =>
      bad.foreach { h =>
        val r = Http.send("GET", api.url(path), None, h)
        assertTypedError(r, 401, api, s"GET $path with $h")
      }
    }
  }

  test("403: a valid token of the wrong scope is rejected on every route") {
    readRoutes.foreach { path =>
      val r = api.get(path, token = Some(api.queryToken))
      assertTypedError(r, 403, api, s"query token on GET $path")
    }
    val post = api.post(
      "/v1/query",
      api.queryBody(clean),
      token = Some(api.readToken)
    )
    assertTypedError(post, 403, api, "read token on POST")
  }

  test("401 and 403 codes differ from each other and from 400, 404 and 422") {
    val c401 = assertTypedError(api.anonymous(readRoutes.head), 401, api, "401")
    val c403 = assertTypedError(
      api.get(readRoutes.head, token = Some(api.queryToken)),
      403,
      api,
      "403"
    )
    val c404 = assertTypedError(api.get(readRoutes.head), 404, api, "404")
    val c400 = assertTypedError(api.post("/v1/query", "{"), 400, api, "400")
    val c422 = assertTypedError(
      api.ask(clean, Map("mode" -> ujson.Str("turbo"))),
      422,
      api,
      "422"
    )
    assertEquals(Set(c401, c403, c404, c400, c422).size, 5)
  }

  test("authorization is checked before the body is read or validated") {
    val noToken = Http.send(
      "POST",
      api.url("/v1/query"),
      Some("{not json"),
      Map("Content-Type" -> "application/json")
    )
    assertTypedError(noToken, 401, api, "invalid JSON, no token")
    val wrongScope = api.post(
      "/v1/query",
      "{not json",
      token = Some(api.readToken)
    )
    assertTypedError(wrongScope, 403, api, "invalid JSON, wrong scope")
  }

  test("health and readiness need no token, even a wrong one") {
    Vector("/healthz", "/readyz").foreach { p =>
      val r = Http.send(
        "GET",
        api.rt.fastUrl + p,
        None,
        Map("Authorization" -> "Bearer wrong-token-value")
      )
      assertEquals(r.status, 200, p)
      assertClean(r, api, p)
    }
  }

  // Headers ----------------------------------------------------------------------

  test("every response carries X-Request-Id and X-Trace-Id") {
    val rs = Vector(
      api.ask(clean),
      api.get(readRoutes(2)),
      api.anonymous(readRoutes(2)),
      api.get(readRoutes(2), token = Some(api.queryToken)),
      api.post("/v1/query", "{"),
      api.method("DELETE", "/v1/query", Some(api.queryToken)),
      api.get("/v1/no-such-route"),
      Http.send("GET", api.rt.fastUrl + "/healthz", None, Map.empty),
      Http.send("GET", api.rt.fastUrl + "/readyz", None, Map.empty)
    )
    rs.zipWithIndex.foreach((r, i) => assertTraceHeaders(r, s"response $i"))
  }

  test("a valid X-Request-Id is echoed; an invalid one is replaced") {
    val ok = "req-abc-123"
    val echoed = api.get(readRoutes(2), headers = Map("X-Request-Id" -> ok))
    assertEquals(echoed.header("x-request-id"), Some(ok))
    val bodyId = echoed.json("error")("requestId").str
    assertEquals(bodyId, ok)
    val invalid = Vector(
      "has spaces and \"quotes\"",
      "<script>alert(1)</script>",
      "a" * 500,
      "'; DROP TABLE x; --"
    )
    invalid.foreach { v =>
      val r = api.get(readRoutes(2), headers = Map("X-Request-Id" -> v))
      val id = r.header("x-request-id").getOrElse("")
      assertNotEquals(id, v)
      assert(id.nonEmpty && id.length <= 200, s"generated id: $id")
      assert(!r.body.contains(v), "invalid request id reflected in the body")
    }
  }

  test("two requests without an id get different generated ids") {
    val a = api.get(readRoutes(2)).header("x-request-id")
    val b = api.get(readRoutes(2)).header("x-request-id")
    assert(a.isDefined && b.isDefined)
    assertNotEquals(a, b)
  }

  // Constant-shape errors on every status ---------------------------------------

  test("one error shape for 400, 401, 403, 404, 405, 409, 413, 415, 422") {
    val slow = launch(Faults.workflow(Faults.Slow))
    val accepted = slow.askAsync(clean)
    val jobId = Json.firstString(accepted.json, "jobId").getOrElse("job-x")
    val big = ujson.write(ujson.Obj("question" -> ("q" * (3 * 1024 * 1024))))
    val cases: Vector[(String, Int, () => Resp, Api)] = Vector(
      ("400", 400, () => api.post("/v1/query", "{"), api),
      ("401", 401, () => api.anonymous(readRoutes(2)), api),
      (
        "403",
        403,
        () => api.get(readRoutes(2), token = Some(api.queryToken)),
        api
      ),
      ("404", 404, () => api.get(readRoutes(2)), api),
      ("404 route", 404, () => api.get("/v1/no-such-route"), api),
      (
        "405",
        405,
        () => api.method("DELETE", "/v1/query", Some(api.queryToken)),
        api
      ),
      (
        "405 get on post route",
        405,
        () => api.get("/v1/query", token = Some(api.queryToken)),
        api
      ),
      (
        "405 post on get route",
        405,
        () => api.post(readRoutes(2), "{}", token = Some(api.readToken)),
        api
      ),
      ("409", 409, () => slow.get(s"/v1/jobs/$jobId/result"), slow),
      ("413", 413, () => api.post("/v1/query", big), api),
      (
        "415",
        415,
        () => api.post("/v1/query", "question=x", contentType = "text/plain"),
        api
      ),
      (
        "422",
        422,
        () => api.ask(clean, Map("mode" -> ujson.Str("turbo"))),
        api
      )
    )
    val codes = cases.map { (label, status, call, target) =>
      label -> assertTypedError(call(), status, target, label)
    }
    val byStatus = codes.map((l, c) => l.take(3) -> c).toMap
    assertEquals(
      byStatus.values.toSet.size,
      byStatus.size,
      s"one code per condition: $codes"
    )
  }

  test("503 and 504 use the same typed body with trace ids") {
    val down = launch(Faults.workflow(Faults.Fail))
    val c503 = assertTypedError(down.ask(clean), 503, down, "503")
    val slow = launch(Faults.workflow(Faults.Slow))
    val c504 = assertTypedError(slow.ask(clean), 504, slow, "504")
    assertNotEquals(c503, c504)
  }

  test("the frozen error example from the specification is matched") {
    val example = ujson.read(
      """{"error":{"code":"UNSUPPORTED_CONSTRAINT","message":"m","traceId":"t","requestId":"r","details":[]}}"""
    )
    val r = api.ask(clean, Map("mode" -> ujson.Str("turbo")))
    assertEquals(r.status, 422)
    assert(Json.conforms(example, r.json), r.body)
    assertEquals(r.json("error")("code").str, "UNSUPPORTED_CONSTRAINT")
  }

  // Method and media type --------------------------------------------------------

  test("non-JSON content types on POST are 415") {
    Vector("text/plain", "application/xml", "multipart/form-data").foreach {
      ct =>
        val r = api.post("/v1/query", api.queryBody(clean), contentType = ct)
        assertTypedError(r, 415, api, ct)
    }
  }

  test("a JSON content type with a charset is accepted") {
    val r = api.post(
      "/v1/query",
      api.queryBody(clean),
      contentType = "application/json; charset=utf-8"
    )
    assertEquals(r.status, 200, r.body)
  }

  test("a wrong method is 405 on every route") {
    Vector("PUT", "DELETE", "PATCH").foreach { verb =>
      Vector("/v1/query", readRoutes(2), "/v1/graph/nodes/x").foreach { p =>
        val token =
          if p == "/v1/query" then api.queryToken else api.readToken
        val r = api.method(verb, p, Some(token))
        assertTypedError(r, 405, api, s"$verb $p")
      }
    }
  }

  // Idempotency ------------------------------------------------------------------

  test("422: an Idempotency-Key reused with a different body") {
    val key = "key-" + java.util.UUID.randomUUID()
    val h = Map("Idempotency-Key" -> key)
    val first = api.ask(clean, headers = h)
    assertEquals(first.status, 200, first.body)
    val other = api.ask(Questions.candidates.last + " ", headers = h)
    val code = assertTypedError(other, 422, api, "key reuse")
    assertNotEquals(code, "UNSUPPORTED_CONSTRAINT")
    val again = api.ask(clean, headers = h)
    assertEquals(Json.queryId(again.json), Json.queryId(first.json))
  }

  test("a key reused with a different mode or window is also a conflict") {
    val key = "key-" + java.util.UUID.randomUUID()
    val h = Map("Idempotency-Key" -> key)
    assertEquals(api.ask(clean, headers = h).status, 200)
    val async = api.ask(clean, Map("mode" -> ujson.Str("async")), h)
    assertTypedError(async, 422, api, "mode change under one key")
    val window = api.ask(
      clean,
      Map("window" -> ujson.Obj("startYear" -> 2000, "endYear" -> 2009)),
      h
    )
    assertTypedError(window, 422, api, "window added under one key")
  }

  test("different keys are independent") {
    val a = api.ask(clean, headers = Map("Idempotency-Key" -> "key-a-1"))
    val b = api.ask(clean, headers = Map("Idempotency-Key" -> "key-b-1"))
    assertEquals(a.status, 200)
    assertEquals(b.status, 200)
  }

  test("an invalid request under a key does not poison the key") {
    val key = "key-" + java.util.UUID.randomUUID()
    val h = Map("Idempotency-Key" -> key)
    val bad = api.post("/v1/query", "{", headers = h)
    assertTypedError(bad, 400, api, "invalid under key")
    val good = api.ask(clean, headers = h)
    assertEquals(good.status, 200, good.body)
  }

  private def concurrently[A](n: Int)(call: Int => A): Vector[A] =
    val pool = Executors.newFixedThreadPool(n)
    try
      given ExecutionContext = ExecutionContext.fromExecutor(pool)
      val fs = (0 until n).map(i => Future(call(i)))
      Await.result(Future.sequence(fs), 90.seconds).toVector
    finally pool.shutdownNow()

  test("concurrent requests under one key and body agree on one result") {
    val h = Map("Idempotency-Key" -> ("key-" + java.util.UUID.randomUUID()))
    val rs = concurrently(8)(_ => api.ask(clean, headers = h))
    assertEquals(rs.map(_.status).toSet, Set(200), rs.map(_.body).toString)
    val ids = rs.flatMap(r => Json.queryId(r.json)).toSet
    assertEquals(ids.size, 1, ids.toString)
    assertEquals(rs.flatMap(r => Json.answerText(r.json)).toSet.size, 1)
  }

  test("concurrent async requests under one key create one job") {
    val h = Map("Idempotency-Key" -> ("key-" + java.util.UUID.randomUUID()))
    val rs =
      concurrently(8)(_ => api.ask(clean, Map("mode" -> ujson.Str("async")), h))
    assertEquals(rs.map(_.status).toSet, Set(202))
    assertEquals(
      rs.flatMap(r => Json.firstString(r.json, "jobId")).toSet.size,
      1
    )
  }

  test("concurrent requests under one key with different bodies: one wins") {
    val h = Map("Idempotency-Key" -> ("key-" + java.util.UUID.randomUUID()))
    val questions = Questions.candidates
    val rs =
      concurrently(8)(i => api.ask(questions(i % questions.size), headers = h))
    val statuses = rs.map(_.status)
    assert(statuses.forall(s => s == 200 || s == 422), statuses.toString)
    assert(statuses.contains(200), "nobody won the key")
    val winners = rs.filter(_.status == 200).flatMap(r => Json.queryId(r.json))
    assertEquals(winners.toSet.size, 1)
    rs.filter(_.status == 422)
      .foreach(r => assertTypedError(r, 422, api, "losing body"))
  }

  test("concurrent mixed traffic never produces a 5xx") {
    val rs = concurrently(16) { i =>
      if i % 3 == 0 then api.ask(clean)
      else if i % 3 == 1 then api.get(readRoutes(2))
      else api.post("/v1/query", "{")
    }
    assert(rs.forall(_.status < 500), rs.map(_.status).toString)
    rs.foreach(r => assertTraceHeaders(r, "mixed"))
  }

  // Metrics ----------------------------------------------------------------------

  test("metrics is 404 with a typed body when disabled (the default)") {
    assertTypedError(api.get("/metrics"), 404, api, "metrics off")
  }

  test("metrics enabled: 200 text behind the read scope, no secrets") {
    val on = launch(Map(Faults.MetricsFlag -> "true"))
    val r = on.get("/metrics")
    assertEquals(r.status, 200, r.body)
    assert(r.contentType.toLowerCase.startsWith("text/plain"), r.contentType)
    assertTraceHeaders(r, "metrics")
    assertClean(r, on, "metrics")
    assertTypedError(on.anonymous("/metrics"), 401, on, "metrics no token")
    val wrong = on.get("/metrics", token = Some(on.queryToken))
    assertTypedError(wrong, 403, on, "metrics wrong scope")
  }
