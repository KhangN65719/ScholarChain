package scholarchain.tests.endtoend

import scholarchain.rag.Prompts
import scholarchain.tests.adversarial.*

/** Phase 10 gate item 5: POST /v1/query, then GET /v1/evidence/{id} for every
  * evidence ID the answer cites, then GET /v1/explain/{queryId}, over real HTTP
  * against the local runtime and its bounded fixtures. Written from spec.md;
  * not executed.
  */
class Phase10EndToEndSuite extends P10Suite:
  private def lifecycle(label: String, r: Resp): Unit =
    val j = r.json
    val queryId = Json.queryId(j).getOrElse(fail(s"$label: no queryId"))
    val claims = Json.claims(j)
    assert(claims.nonEmpty, s"$label: no claims")
    assert(Json.isBacked(j), s"$label: no verified, evidence-citing claim")
    val bundle = Json.evidenceItems(j)
    val byId = bundle.map(i => i("evidenceId").str -> i).toMap
    val cited = Json.citedEvidenceIds(j)
    assert(cited.nonEmpty, label)
    assert(cited.forall(byId.contains), s"$label: cited outside the bundle")

    cited.foreach { id =>
      val e = api.get(s"/v1/evidence/${api.enc(id)}")
      assertEquals(e.status, 200, s"$label evidence $id: ${e.body}")
      assertTraceHeaders(e, s"$label evidence")
      assertClean(e, api, s"$label evidence")
      val item = byId(id)
      assertEquals(Json.firstString(e.json, "evidenceId"), Some(id))
      assert(e.body.contains(item("sourceUri").str), s"$label: sourceUri")
      val prov = item("provenance")
      Vector("sourceRecordId", "transformVersion", "runId").foreach { f =>
        val expected = Json.firstString(prov, f).get
        assert(e.body.contains(expected), s"$label: provenance $f of $id")
      }
      Json
        .firstValue(item, "chunkId")
        .flatMap(v => Json.strings(v).headOption)
        .foreach(chunk => assert(e.body.contains(chunk), s"$label: chunk"))
      Json
        .firstValue(item, "graphPath")
        .toVector
        .flatMap(Json.strings)
        .foreach(hop => assert(e.body.contains(hop), s"$label: path hop"))
      assert(e.body.length < 65536, s"$label: evidence is not compact")
    }

    val x = api.get(s"/v1/explain/${api.enc(queryId)}")
    assertEquals(x.status, 200, s"$label explain: ${x.body}")
    assertTraceHeaders(x, s"$label explain")
    assertClean(x, api, s"$label explain")
    assertEquals(Json.firstString(x.json, "queryId"), Some(queryId))
    cited.foreach(id =>
      assert(x.body.contains(id), s"$label: explain omits evidence $id")
    )
    assert(x.body.contains(Prompts.Planner.id), s"$label: planner prompt id")
    assert(
      x.body.contains(Prompts.Synthesis.id),
      s"$label: synthesis prompt id"
    )
    // The explain view lists every registered prompt, so presence of an ID
    // proves nothing. The synthesis prompt must be marked as called in this
    // query, and a prompt the query never used must not be.
    val prompts = x.json("prompts").arr.toVector
    val called = prompts
      .filter(p => p("calledInThisQuery").bool)
      .map(p => p("id").str)
      .toSet
    val synthesis = prompts
      .find(_("id").str == Prompts.Synthesis.id)
      .getOrElse(fail(s"$label: synthesis prompt not listed"))
    assertEquals(
      synthesis("calledInThisQuery").bool,
      true,
      s"$label: an answered query called the synthesis prompt"
    )
    assert(called.contains(Prompts.Synthesis.id), s"$label: $called")
    assertEquals(x.json("abstained").bool, false, label)
    assertEquals(P9Check.reasoningKeysIn(x.json), Set.empty[String], label)
    val keys = Json.keys(x.json).map(_.toLowerCase)
    Vector("plan", "stage", "model", "counter", "verification").foreach(part =>
      assert(keys.exists(_.contains(part)), s"$label: explain has no $part")
    )

  test("sync lifecycle: query, every cited evidence ID, then explain") {
    val (_, r) = api.answered()
    lifecycle("sync", r)
  }

  test("async lifecycle: job, result, every cited evidence ID, then explain") {
    val (question, _) = api.answered()
    val accepted = api.askAsync(question)
    assertEquals(accepted.status, 202, accepted.body)
    val jobId = Json.firstString(accepted.json, "jobId").get
    val done = api.awaitJob(jobId)
    assertEquals(done.status, 200, done.body)
    val result = api.get(s"/v1/jobs/$jobId/result")
    assertEquals(result.status, 200, result.body)
    lifecycle("async", result)
  }

  test(
    "a replayed idempotent query resolves to the same evidence and explain"
  ) {
    val (question, _) = api.answered()
    val h = Map("Idempotency-Key" -> ("key-" + java.util.UUID.randomUUID()))
    val a = api.ask(question, headers = h)
    val b = api.ask(question, headers = h)
    assertEquals(Json.queryId(b.json), Json.queryId(a.json))
    lifecycle("idempotent", b)
  }

  test("sync and async answers to one question cite the same evidence IDs") {
    val (question, sync) = api.answered()
    val accepted = api.askAsync(question)
    val jobId = Json.firstString(accepted.json, "jobId").get
    api.awaitJob(jobId)
    val async = api.get(s"/v1/jobs/$jobId/result")
    assertEquals(
      Json.citedEvidenceIds(async.json).toSet,
      Json.citedEvidenceIds(sync.json).toSet
    )
  }

  test("evidence and explain stay readable after later unrelated queries") {
    val (_, first) = api.answered()
    val id = Json.citedEvidenceIds(first.json).head
    val queryId = Json.queryId(first.json).get
    Questions.candidates.foreach(q => api.ask(q))
    api.ask(Questions.nobody)
    assertEquals(api.get(s"/v1/evidence/${api.enc(id)}").status, 200)
    assertEquals(api.get(s"/v1/explain/${api.enc(queryId)}").status, 200)
  }
