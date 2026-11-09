package scholarchain.tests.contracts

import scholarchain.tests.adversarial.*

/** Frozen request and response examples for the Phase 10 wire contract. Each
  * example below is the contract: the top-level body of a live response must
  * carry every key of its example with the same JSON type. An example `null`
  * accepts any type, so it appears only on a field whose type really varies (an
  * optional value); every other field has a typed example value. A nested
  * object is never searched for a match. Answer, claim, evidence and provenance
  * shapes are the Phase 1 core contracts, which the specification says win.
  */
class Phase10WireSuite extends P10Suite:
  private val provenance =
    """{"schemaVersion":1,"source":"derived","snapshotId":"s","sourceRecordId":"r","sourceUri":"u","transformVersion":"v","runId":"run:v1:x"}"""

  private val answerExample = ujson.read(s"""{
    "schemaVersion":1,
    "queryId":"query:v1:x",
    "answer":"text",
    "claims":[{
      "schemaVersion":1,"claimId":"c","text":"t","evidenceIds":["evidence:v1:x"],
      "verificationStatus":"verified","uncertainty":null,"provenance":$provenance
    }],
    "evidence":{
      "schemaVersion":1,"bundleId":"b","uncertainty":null,"provenance":$provenance,
      "items":[{
        "schemaVersion":1,"evidenceId":"evidence:v1:x","evidenceType":"metric",
        "workId":null,"sourceUri":"u","chunkId":null,"graphPath":null,"score":0.5,
        "confidence":null,"text":null,"uncertainty":null,"provenance":$provenance
      }]
    },
    "provenance":$provenance,
    "abstained":false,
    "abstentionReason":null,
    "request":{"mode":"sync","window":null}
  }""")

  // 202 on POST /v1/query. The Location header repeats statusLink.
  private val acceptedExample = ujson.read(
    """{"jobId":"job-x","state":"queued","createdAt":"2026-01-01T00:00:00Z","statusLink":"/v1/jobs/job-x"}"""
  )

  // error, queryId and resultLink are null until the job ends, then typed.
  private val jobStatusExample = ujson.read(
    """{"jobId":"job-x","state":"running","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","stage":"running","error":null,"queryId":null,"resultLink":null}"""
  )

  // workId, chunkId, graphPath, textPointer, confidence, uncertainty and
  // content are optional: null or their type, checked separately below.
  private val evidenceExample = ujson.read(
    s"""{"evidenceId":"evidence:v1:x","evidenceType":"metric","sourceUri":"u","workId":null,"chunkId":null,"graphPath":null,"textPointer":null,"score":0.5,"confidence":null,"uncertainty":null,"content":null,"contentTruncated":false,"sourceRecord":{"sourceRecordId":"r","snapshotId":"s","source":"derived"},"provenance":$provenance,"queryId":"query:v1:x","bundleId":"b"}"""
  )

  private val nodeExample = ujson.read(
    s"""{"nodeId":"researcher:v1:x","nodeType":"researcher","displayName":"n","nodeKind":"k","orcid":null,"linkStatus":null,"idRule":null,"memberCount":1,"mentionCount":1,"sharesOrcidWith":0,"provenance":$provenance}"""
  )

  private val neighborsExample = ujson.read(
    """{"nodeId":"researcher:v1:x","window":"2000-2009","depth":1,"limit":1,"edgeTypes":["COAUTHORED_WITH"],"neighbors":[{"neighborId":"researcher:v1:y","distance":1,"via":"researcher:v1:x","edgeType":"COAUTHORED_WITH","edgeId":"e","jointPublicationCount":1,"firstCollaborationYear":2000,"lastCollaborationYear":2001,"evidencePointer":"p"}],"total":2,"truncated":false,"nextCursor":"c"}"""
  )

  private val errorExample = ujson.read(
    """{"error":{"code":"NOT_FOUND","message":"m","traceId":"t","requestId":"r","details":[]}}"""
  )

  private def firstEvidenceId(r: Resp): String =
    Json.citedEvidenceIds(r.json).headOption.getOrElse(fail(r.body))

  private def assertConforms(
      example: ujson.Value,
      actual: ujson.Value,
      label: String
  ): Unit =
    assertEquals(Json.mismatch(example, actual), None, label)
    assert(Json.conformsTop(example, actual), label)

  // Requests -------------------------------------------------------------------

  test("frozen request examples are accepted") {
    val examples = Vector(
      """{"question":"How many distinct coauthors does Ada Quillfeather have in 2000-2009?"}""",
      """{"question":"How many distinct coauthors does Ada Quillfeather have?","window":{"startYear":2000,"endYear":2009}}""",
      """{"question":"How many distinct coauthors does Ada Quillfeather have in 2000-2009?","mode":"sync"}""",
      """{"question":"How many distinct coauthors does Ada Quillfeather have in 2000-2009?","mode":"sync","window":{"startYear":2000,"endYear":2009}}"""
    )
    examples.foreach { body =>
      val r = api.post("/v1/query", body)
      assertEquals(r.status, 200, s"$body -> ${r.body}")
      assertTraceHeaders(r, body)
    }
    val asyncBody =
      """{"question":"How many distinct coauthors does Ada Quillfeather have in 2000-2009?","mode":"async"}"""
    val accepted = api.post("/v1/query", asyncBody)
    assertEquals(accepted.status, 202, accepted.body)
    assertTraceHeaders(accepted, "async example")
  }

  test("frozen request examples with an unappliable constraint are 422") {
    val q =
      "How many distinct coauthors does Ada Quillfeather have in 2000-2009?"
    val examples = Vector(
      s"""{"question":"$q","filters":{"topic":"graphs","venue":"dblp","institution":"example"},"mode":"sync","window":{"startYear":2000,"endYear":2009}}""",
      s"""{"question":"$q","filters":{"topic":"graphs"}}""",
      s"""{"question":"$q","limits":{"maxResults":1}}""",
      s"""{"question":"$q","window":{"startYear":1990,"endYear":1999}}"""
    )
    examples.foreach { body =>
      val r = api.post("/v1/query", body)
      val code = assertTypedError(r, 422, api, body)
      assertEquals(code, "UNSUPPORTED_CONSTRAINT", body)
    }
  }

  // Responses ------------------------------------------------------------------

  test("sync query response matches the frozen answer example") {
    val (_, r) = api.answered()
    assertEquals(r.status, 200)
    assertConforms(answerExample, r.json, r.body)
    assert(r.contentType.toLowerCase.contains("json"), r.contentType)
    assert(r.json("abstained").bool == false)
    assertEquals(r.json("request")("mode").str, "sync")
  }

  test("202 carries Location, statusLink, state and createdAt; the job ends") {
    val accepted = api.askAsync(Questions.candidates.head)
    assertEquals(accepted.status, 202, accepted.body)
    assertConforms(acceptedExample, accepted.json, accepted.body)
    assertEquals(
      accepted.json.obj.keySet.toSet,
      acceptedExample.obj.keySet.toSet,
      accepted.body
    )
    val jobId = accepted.json("jobId").str
    assertEquals(accepted.json("state").str, "queued")
    assertEquals(accepted.json("statusLink").str, s"/v1/jobs/$jobId")
    assertEquals(accepted.header("location"), Some(s"/v1/jobs/$jobId"))
    assert(java.time.Instant.parse(accepted.json("createdAt").str) != null)

    val status = api.get(s"/v1/jobs/$jobId")
    assertEquals(status.status, 200, status.body)
    assertConforms(jobStatusExample, status.json, status.body)
    assertEquals(status.json("jobId").str, jobId)
    val done = api.awaitJob(jobId)
    assertConforms(jobStatusExample, done.json, done.body)
    assertEquals(done.json("state").str, "succeeded", done.body)
    assertEquals(done.json("error"), ujson.Null)
    assertEquals(done.json("resultLink").str, s"/v1/jobs/$jobId/result")
    assert(done.json("queryId").str.nonEmpty, done.body)
    val result = api.get(s"/v1/jobs/$jobId/result")
    assertEquals(result.status, 200, result.body)
    assertConforms(answerExample, result.json, result.body)
    assertEquals(result.json("queryId").str, done.json("queryId").str)
  }

  test("evidence response matches the frozen evidence example") {
    val (_, r) = api.answered()
    val id = firstEvidenceId(r)
    val e = api.get(s"/v1/evidence/${api.enc(id)}")
    assertEquals(e.status, 200, e.body)
    assertConforms(evidenceExample, e.json, e.body)
    assertEquals(e.json("evidenceId").str, id)
  }

  test("evidence graphPath, textPointer and content hold their types") {
    val answers = (Questions.candidates ++ Questions.coauthors)
      .map(q => api.ask(q))
      .filter(r => r.status == 200 && Json.isBacked(r.json))
    assert(answers.nonEmpty, "no fixture question was answered")
    val ids = answers.flatMap(a => Json.citedEvidenceIds(a.json)).distinct
    val views = ids.map(id => api.get(s"/v1/evidence/${api.enc(id)}").json)
    assert(views.nonEmpty)
    views.foreach(v => assertConforms(evidenceExample, v, v.toString))
    val withPath = views.filter(_("graphPath") != ujson.Null)
    assert(withPath.nonEmpty, "no cited evidence carries a graph path")
    withPath.foreach { v =>
      val hops = v("graphPath").arr.toVector
      assert(hops.nonEmpty && hops.forall(_.strOpt.isDefined), v.toString)
      assertEquals(
        v("textPointer"),
        ujson.Null,
        "a path item has no text pointer"
      )
    }
    val withText = views.filter(_("textPointer") != ujson.Null)
    assert(withText.nonEmpty, "no cited evidence is a text chunk")
    withText.foreach { v =>
      assertEquals(v("textPointer").str, v("sourceUri").str)
      assert(v("content").strOpt.isDefined, "a text chunk shows its content")
      assertEquals(v("graphPath"), ujson.Null)
    }
    views.foreach { v =>
      assert(v("score").numOpt.isDefined, v.toString)
      assert(v("contentTruncated").boolOpt.isDefined, v.toString)
      assert(v("provenance")("runId").strOpt.isDefined, v.toString)
    }
  }

  test("graph node and neighbors match their frozen examples") {
    val (_, r) = api.answered()
    val ids = Json.researcherIds(r.body)
    val withPage = ids.iterator
      .map(id =>
        id -> api.get(s"/v1/graph/nodes/${api.enc(id)}/neighbors?limit=1")
      )
      .find((_, p) =>
        p.status == 200 && p.json.objOpt
          .flatMap(_.value.get("nextCursor"))
          .exists(_.strOpt.isDefined)
      )
      .getOrElse(fail("no answer node has a second neighbors page"))
    val (id, page) = withPage
    val node = api.get(s"/v1/graph/nodes/${api.enc(id)}")
    assertEquals(node.status, 200, node.body)
    assertConforms(nodeExample, node.json, node.body)
    // Phase 11: sharesOrcidWith is a count (0 when no other node shares the
    // ORCID), never null
    assert(node.json("sharesOrcidWith").numOpt.isDefined, node.body)
    assert(node.json("sharesOrcidWith").num >= 0, node.body)
    assertEquals(node.json("nodeId").str, id)
    assertEquals(page.status, 200, page.body)
    assertConforms(neighborsExample, page.json, page.body)
    val j = page.json
    assertEquals(j("nodeId").str, id)
    assertEquals(j("limit").num.toInt, 1)
    assertEquals(j("neighbors").arr.size, 1, page.body)
    assert(j("depth").num.toInt >= 1, page.body)
    assert(j("total").num.toInt >= 2, page.body)
    assert(j("truncated").boolOpt.isDefined, page.body)
    assert(j("nextCursor").str.nonEmpty, page.body)
    assert(j("edgeTypes").arr.forall(_.strOpt.isDefined), page.body)
    assert(j("window").str.nonEmpty, page.body)
    val whole = api.get(s"/v1/graph/nodes/${api.enc(id)}/neighbors?limit=50")
    assertEquals(whole.status, 200, whole.body)
    assertEquals(
      whole.json("nextCursor"),
      ujson.Null,
      "last page has no cursor"
    )
    assert(
      whole.json.obj.contains("nextCursor"),
      "the key stays present on the last page"
    )
    assertEquals(whole.json("total").num.toInt, j("total").num.toInt)
  }

  test("explain response carries the frozen sections and no reasoning") {
    val (_, r) = api.answered()
    val queryId = Json.queryId(r.json).get
    val x = api.get(s"/v1/explain/${api.enc(queryId)}")
    assertEquals(x.status, 200, x.body)
    assertEquals(x.json("queryId").str, queryId)
    val keys = Json.keys(x.json).map(_.toLowerCase)
    Vector(
      "plan",
      "stage",
      "prompt",
      "model",
      "evidence",
      "counter",
      "verification"
    )
      .foreach(part =>
        assert(keys.exists(_.contains(part)), s"no $part section: $keys")
      )
    assertEquals(P9Check.reasoningKeysIn(x.json), Set.empty[String])
  }

  test("every error status carries the frozen error example shape") {
    val rs = Vector(
      api.get("/v1/evidence/evidence:v1:" + ("0" * 64)),
      api.anonymous("/v1/jobs/job-x"),
      api.post("/v1/query", "{"),
      api.ask("x", Map("mode" -> ujson.Str("turbo"))),
      api.ask("x", Map("filters" -> ujson.Obj("topic" -> "t")))
    )
    rs.foreach { r =>
      assert(r.status >= 400 && r.status < 500, r.body)
      assertConforms(errorExample, r.json, r.body)
      assertEquals(r.json("error").obj.keySet.size, 5, r.body)
    }
  }

  test("the matcher rejects a wrong type, a missing key and a nested match") {
    val example = ujson.read("""{"a":"s","b":1,"c":[{"d":true}]}""")
    assert(Json.conformsTop(example, ujson.read("""{"a":"x","b":2,"c":[]}""")))
    assert(!Json.conformsTop(example, ujson.read("""{"a":1,"b":2,"c":[]}""")))
    assert(!Json.conformsTop(example, ujson.read("""{"a":"x","c":[]}""")))
    assert(
      !Json.conformsTop(
        example,
        ujson.read("""{"a":"x","b":2,"c":[{"d":1}]}""")
      )
    )
    assert(
      !Json.conformsTop(
        example,
        ujson.read("""{"wrapper":{"a":"x","b":2,"c":[]}}""")
      ),
      "a match nested inside another object must not pass"
    )
    assert(
      Json.mismatch(example, ujson.read("""{"a":1,"b":2,"c":[]}""")).isDefined
    )
  }
