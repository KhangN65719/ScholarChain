package scholarchain.tests.adversarial

/** The ten required tests of the Phase 10 course list, driven over real HTTP
  * against `LocalRuntime`. Written from spec.md; written, not executed.
  */
class Phase10CourseSuite extends P10Suite:
  private val clean = Questions.candidates.head

  // 1. Successful synchronous query ----------------------------------------------

  test("course 1: a sync query returns 200 with an evidence-backed answer") {
    val (question, r) = api.answered()
    assertEquals(r.status, 200, question)
    assert(r.contentType.toLowerCase.contains("json"))
    assertTraceHeaders(r, "sync query")
    assertClean(r, api, "sync query")
    val j = r.json
    assert(Json.queryId(j).exists(_.nonEmpty), "queryId")
    val bundle = Json.evidenceItems(j).map(i => i("evidenceId").str).toSet
    assert(bundle.nonEmpty, "evidence bundle")
    val cited = Json.citedEvidenceIds(j)
    assert(cited.nonEmpty, "claims cite evidence")
    assert(cited.forall(bundle.contains), s"cited outside bundle: $cited")
    Json.claims(j).foreach { c =>
      val status = Json.firstString(c, "verificationStatus")
      if status.contains("verified") then
        assert(
          Json.firstValue(c, "evidenceIds").exists(Json.strings(_).nonEmpty)
        )
    }
  }

  // 2. Asynchronous query path ---------------------------------------------------

  private val jobKeys =
    Set("state", "createdAt", "updatedAt", "stage", "error", "resultLink")

  test("course 2: mode async returns 202 and a job that reaches a result") {
    val accepted = api.askAsync(clean)
    assertEquals(accepted.status, 202, accepted.body)
    assertTraceHeaders(accepted, "202")
    val jobId = Json
      .firstString(accepted.json, "jobId")
      .getOrElse(
        fail(s"202 body has no jobId: ${accepted.body}")
      )
    val status = api.get(s"/v1/jobs/$jobId")
    assertEquals(status.status, 200, status.body)
    assertTraceHeaders(status, "job")
    val missing = jobKeys.filterNot(k => Json.keys(status.json).contains(k))
    assertEquals(missing, Set.empty[String], status.body)
    val done = api.awaitJob(jobId)
    assertEquals(done.status, 200, done.body)
    val state = Json.firstString(done.json, "state").getOrElse("")
    assert(!api.isActive(state), s"job never left the active states: $state")
    val result = api.get(s"/v1/jobs/$jobId/result")
    assertEquals(result.status, 200, result.body)
    assert(Json.answerObject(result.json).isDefined, result.body)
    assert(Json.queryId(result.json).exists(_.nonEmpty))
  }

  test("course 2: Prefer respond-async also returns 202 with a job") {
    val r = api.ask(clean, headers = Map("Prefer" -> "respond-async"))
    assertEquals(r.status, 202, r.body)
    assert(Json.firstString(r.json, "jobId").exists(_.nonEmpty), r.body)
  }

  test("course 2: a job result requested too early is 409, unknown jobs 404") {
    val slow = launch(Faults.workflow(Faults.Slow))
    val accepted = slow.askAsync(clean)
    assertEquals(accepted.status, 202, accepted.body)
    val jobId = Json.firstString(accepted.json, "jobId").get
    val early = slow.get(s"/v1/jobs/$jobId/result")
    assertTypedError(early, 409, slow, "result before finish")
    val unknown = "job-00000000-0000-4000-8000-000000000000"
    assertTypedError(api.get(s"/v1/jobs/$unknown"), 404, api, "unknown job")
    assertTypedError(
      api.get(s"/v1/jobs/$unknown/result"),
      404,
      api,
      "unknown job result"
    )
  }

  // 3. Invalid JSON --------------------------------------------------------------

  test("course 3: invalid JSON and invalid fields are 400 with a typed body") {
    val bodies = Vector(
      "{not json",
      "",
      "   ",
      "{\"question\":",
      "[]",
      "\"just a string\"",
      "null",
      "{}",
      "{\"question\":\"\"}",
      "{\"question\":\"   \"}",
      "{\"question\":5}",
      "{\"question\":null}",
      "{\"question\":[\"a\"]}",
      ujson.write(ujson.Obj("question" -> ("x" * 20000))),
      "{\"question\":\"ok\",\"window\":\"2000-2009\"}",
      "{\"question\":\"ok\",\"window\":{\"startYear\":\"a\",\"endYear\":2009}}",
      "{\"question\":\"ok\",\"filters\":[1]}",
      "{\"question\":\"ok\",\"mode\":5}"
    )
    bodies.foreach { b =>
      val r = api.post("/v1/query", b)
      assertTypedError(r, 400, api, s"body ${b.take(40)}")
    }
  }

  // 4. Unsupported query constraint ---------------------------------------------

  test("course 4: an unsupported constraint is 422 UNSUPPORTED_CONSTRAINT") {
    val cases: Vector[(String, Map[String, ujson.Value])] = Vector(
      "unknown filter key" -> Map(
        "filters" -> ujson.Obj("shoeSize" -> "42")
      ),
      "window start after end" -> Map(
        "window" -> ujson.Obj("startYear" -> 2019, "endYear" -> 2000)
      ),
      "disallowed mode" -> Map("mode" -> ujson.Str("turbo"))
    )
    cases.foreach { (label, extra) =>
      val r = api.ask(clean, extra)
      val code = assertTypedError(r, 422, api, label)
      assertEquals(code, "UNSUPPORTED_CONSTRAINT", label)
    }
  }

  // D13: a constraint the workflow cannot apply is refused, never ignored. The
  // topic, venue and institution filters, limits.maxResults and a window that
  // conflicts with the years in the question are all 422.
  test("course 4: topic, venue and institution filters are 422, not ignored") {
    val cases: Vector[(String, Map[String, ujson.Value])] = Vector(
      "topic" -> Map("filters" -> ujson.Obj("topic" -> "citation analysis")),
      "venue" -> Map("filters" -> ujson.Obj("venue" -> "dblp")),
      "institution" -> Map("filters" -> ujson.Obj("institution" -> "none")),
      "venue and institution" -> Map(
        "filters" -> ujson.Obj("venue" -> "dblp", "institution" -> "none")
      ),
      "all three" -> Map(
        "filters" -> ujson.Obj(
          "topic" -> "graphs",
          "venue" -> "dblp",
          "institution" -> "example"
        )
      )
    )
    cases.foreach { (label, extra) =>
      val r = api.ask(clean, extra)
      val code = assertTypedError(r, 422, api, s"filter $label")
      assertEquals(code, "UNSUPPORTED_CONSTRAINT", label)
      assert(
        Json.answerObject(r.json).isEmpty,
        s"$label: an answer came back with the constraint ignored"
      )
    }
  }

  test("course 4: a window that conflicts with the question years is 422") {
    val r = api.ask(
      clean,
      Map("window" -> ujson.Obj("startYear" -> 2010, "endYear" -> 2019))
    )
    val code = assertTypedError(r, 422, api, "conflicting window")
    assertEquals(code, "UNSUPPORTED_CONSTRAINT")
    val partial = api.ask(
      clean,
      Map("window" -> ujson.Obj("startYear" -> 2000, "endYear" -> 2005))
    )
    assertEquals(
      assertTypedError(partial, 422, api, "partly overlapping window"),
      "UNSUPPORTED_CONSTRAINT"
    )
  }

  test("course 4: limits.maxResults is 422 UNSUPPORTED_CONSTRAINT") {
    val cases: Vector[(String, ujson.Value)] = Vector(
      "one" -> ujson.Obj("maxResults" -> 1),
      "five" -> ujson.Obj("maxResults" -> 5),
      "zero" -> ujson.Obj("maxResults" -> 0),
      "above the maximum" -> ujson.Obj("maxResults" -> 1000000),
      "unknown limit" -> ujson.Obj("maxRows" -> 1)
    )
    cases.foreach { (label, limits) =>
      val r = api.ask(clean, Map("limits" -> limits))
      val code = assertTypedError(r, 422, api, s"limits $label")
      assertEquals(code, "UNSUPPORTED_CONSTRAINT", label)
      assert(Json.answerObject(r.json).isEmpty, label)
    }
    val notWhole = api.ask(
      clean,
      Map("limits" -> ujson.Obj("maxResults" -> 1.5))
    )
    assertTypedError(notWhole, 400, api, "fractional maxResults")
  }

  test("course 4: constraints that are applied are accepted and echoed") {
    val plain = api.ask(clean)
    assertEquals(plain.status, 200, plain.body)
    val withWindow = api.ask(
      clean,
      Map("window" -> ujson.Obj("startYear" -> 2000, "endYear" -> 2009))
    )
    assertEquals(withWindow.status, 200, withWindow.body)
    assertEquals(
      withWindow.json("request")("window")("startYear").num.toInt,
      2000
    )
    assertEquals(
      withWindow.json("request")("window")("endYear").num.toInt,
      2009
    )
    assertEquals(
      Json.answerText(withWindow.json),
      Json.answerText(plain.json),
      "the window the question already names changes nothing"
    )
    val yearless = "How many distinct coauthors does Ada Quillfeather have?"
    val applied = api.ask(
      yearless,
      Map("window" -> ujson.Obj("startYear" -> 2000, "endYear" -> 2009))
    )
    assertEquals(applied.status, 200, applied.body)
    assertEquals(applied.json("request")("window")("startYear").num.toInt, 2000)
    val sync = api.ask(clean, Map("mode" -> ujson.Str("sync")))
    assertEquals(sync.status, 200, sync.body)
    assertEquals(sync.json("request")("mode").str, "sync")
    val emptyLimits = api.ask(clean, Map("limits" -> ujson.Obj()))
    assertEquals(emptyLimits.status, 200, emptyLimits.body)
    val noFilters = api.ask(clean, Map("filters" -> ujson.Obj()))
    assertEquals(noFilters.status, 200, noFilters.body)
  }

  test(
    "course 4: a refused constraint is not stored under an idempotency key"
  ) {
    val key = "key-" + java.util.UUID.randomUUID()
    val h = Map("Idempotency-Key" -> key)
    val body = Map("filters" -> ujson.Obj("topic" -> "graphs"))
    assertTypedError(api.ask(clean, body, h), 422, api, "first refusal")
    assertTypedError(api.ask(clean, body, h), 422, api, "second refusal")
  }

  // 5. Missing evidence ----------------------------------------------------------

  private val unknownEvidence = "evidence:v1:" + ("0" * 64)

  test("course 5: unknown evidence, query and node are 404 with a typed body") {
    assertTypedError(
      api.get(s"/v1/evidence/$unknownEvidence"),
      404,
      api,
      "evidence"
    )
    assertTypedError(
      api.get("/v1/explain/query:v1:" + ("0" * 64)),
      404,
      api,
      "explain"
    )
    assertTypedError(
      api.get("/v1/graph/nodes/researcher:v1:" + ("0" * 64)),
      404,
      api,
      "node"
    )
  }

  test(
    "course 5: a question with no stored evidence yields no verified claim"
  ) {
    val r = api.ask(Questions.nobody)
    assertEquals(r.status, 200, r.body)
    val j = r.json
    assert(!Json.isBacked(j), s"unbacked answer claims verification: ${r.body}")
    Json.claims(j).foreach { c =>
      val cites =
        Json.firstValue(c, "evidenceIds").exists(Json.strings(_).nonEmpty)
      val status = Json.firstString(c, "verificationStatus")
      assert(!status.contains("verified") || cites)
    }
  }

  test("course 5: an empty evidence store makes every evidence lookup 404") {
    val empty = launch(Faults.evidence(Faults.Empty))
    assertTypedError(
      empty.get(s"/v1/evidence/$unknownEvidence"),
      404,
      empty,
      "empty store"
    )
  }

  // 6. Idempotent repeated request ----------------------------------------------

  test("course 6: the same Idempotency-Key and body replays the same answer") {
    val key = "key-" + java.util.UUID.randomUUID()
    val h = Map("Idempotency-Key" -> key)
    val first = api.ask(clean, headers = h)
    val second = api.ask(clean, headers = h)
    assertEquals(first.status, 200, first.body)
    assertEquals(second.status, first.status, second.body)
    assertEquals(Json.queryId(second.json), Json.queryId(first.json))
    assert(Json.queryId(first.json).exists(_.nonEmpty))
    assertEquals(Json.answerText(second.json), Json.answerText(first.json))
    assertEquals(
      Json.citedEvidenceIds(second.json),
      Json.citedEvidenceIds(first.json)
    )
  }

  test("course 6: an async request repeated under one key keeps one job") {
    val h = Map("Idempotency-Key" -> ("key-" + java.util.UUID.randomUUID()))
    val a = api.ask(clean, Map("mode" -> ujson.Str("async")), h)
    val b = api.ask(clean, Map("mode" -> ujson.Str("async")), h)
    assertEquals(a.status, 202, a.body)
    assertEquals(b.status, 202, b.body)
    assertEquals(
      Json.firstString(b.json, "jobId"),
      Json.firstString(a.json, "jobId")
    )
  }

  // 7. Timeout and downstream failure -------------------------------------------

  test("course 7: a slow workflow is 504 and a failing one is 503") {
    val slow = launch(Faults.workflow(Faults.Slow))
    assertTypedError(slow.ask(clean), 504, slow, "slow workflow")
    val failing = launch(Faults.workflow(Faults.Fail))
    assertTypedError(failing.ask(clean), 503, failing, "failing workflow")
  }

  test("course 7: a slow or failing evidence store is 504 or 503") {
    val slow = launch(Faults.evidence(Faults.Slow))
    assertTypedError(
      slow.get(s"/v1/evidence/$unknownEvidence"),
      504,
      slow,
      "slow evidence"
    )
    val failing = launch(Faults.evidence(Faults.Fail))
    assertTypedError(
      failing.get(s"/v1/evidence/$unknownEvidence"),
      503,
      failing,
      "failing evidence"
    )
  }

  test("course 7: a failing graph store is 503 and a slow one is 504") {
    val node = "/v1/graph/nodes/researcher:v1:" + ("1" * 64)
    val failing = launch(Faults.graph(Faults.Fail))
    assertTypedError(failing.get(node), 503, failing, "failing graph")
    assertTypedError(
      failing.get(node + "/neighbors"),
      503,
      failing,
      "failing neighbors"
    )
    val slow = launch(Faults.graph(Faults.Slow))
    assertTypedError(slow.get(node), 504, slow, "slow graph")
  }

  test("course 7: a downstream failure never reaches the caller raw") {
    val failing = launch(Faults.everything(Faults.Fail))
    val rs = Vector(
      failing.ask(clean),
      failing.get(s"/v1/evidence/$unknownEvidence"),
      failing.get("/v1/graph/nodes/researcher:v1:" + ("1" * 64))
    )
    rs.foreach { r =>
      assert(r.status == 503, r.body)
      val text = r.body.toLowerCase
      Vector("injected", "exception", "inmemory", "stack").foreach(w =>
        assert(!text.contains(w), s"$w in ${r.body}")
      )
    }
  }

  test("course 7: a failing downstream keeps answering 503, never 500") {
    val failing = launch(Faults.evidence(Faults.Fail))
    val statuses =
      (1 to 12).map(_ => failing.get(s"/v1/evidence/$unknownEvidence").status)
    assertEquals(statuses.toSet, Set(503))
  }

  // 8. Unauthorized request ------------------------------------------------------

  test("course 8: every /v1 route without a token is 401") {
    val gets = Vector(
      "/v1/jobs/job-x",
      "/v1/jobs/job-x/result",
      s"/v1/evidence/$unknownEvidence",
      "/v1/graph/nodes/researcher:v1:x",
      "/v1/graph/nodes/researcher:v1:x/neighbors",
      "/v1/explain/query:v1:x"
    )
    gets.foreach { p =>
      assertTypedError(api.anonymous(p), 401, api, s"GET $p")
    }
    val unauth = Http.send(
      "POST",
      api.url("/v1/query"),
      Some(api.queryBody(clean)),
      Map("Content-Type" -> "application/json")
    )
    assertTypedError(unauth, 401, api, "POST query")
  }

  // 9. Health versus readiness ---------------------------------------------------

  test("course 9: healthy runtime is live and ready, with no token needed") {
    Vector(api.rt.fastUrl, api.rt.slowUrl).distinct.foreach { base =>
      val live = Http.send("GET", base + "/healthz", None, Map.empty)
      assertEquals(live.status, 200, live.body)
      assertTraceHeaders(live, "healthz")
      val ready = Http.send("GET", base + "/readyz", None, Map.empty)
      assertEquals(ready.status, 200, ready.body)
      assertTraceHeaders(ready, "readyz")
      assert(Json.keys(ready.json).contains("components"), ready.body)
    }
  }

  test(
    "course 9: with every downstream failing, healthz stays 200, readyz 503"
  ) {
    val down = launch(Faults.everything(Faults.Fail))
    Vector(down.rt.fastUrl, down.rt.slowUrl).distinct.foreach { base =>
      val live = Http.send("GET", base + "/healthz", None, Map.empty)
      assertEquals(live.status, 200, live.body)
      val ready = Http.send("GET", base + "/readyz", None, Map.empty)
      assertEquals(ready.status, 503, ready.body)
      assertTraceHeaders(ready, "readyz down")
      assert(Json.keys(ready.json).contains("components"), ready.body)
    }
  }
