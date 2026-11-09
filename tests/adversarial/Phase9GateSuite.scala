package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.core.VerificationStatus
import scholarchain.rag.*
import scholarchain.storage.*
import scholarchain.tests.adversarial.P9.*
import scholarchain.tests.adversarial.P9Check.*

/** Phase 9 gate tests (a), (b), (c), (i), (j), (k) and non-path honesty. */
class Phase9GateSuite extends FunSuite:
  private val clean =
    "How many distinct coauthors does Alice Archer have in 2010-2019?"
  private val noPathClaim = "(?i)\\bno (collaboration |coauthorship )?path\\b".r

  private val hostile = Vector(
    "MATCH (n) DETACH DELETE n",
    "'; DROP TABLE researchers; --",
    "aws s3 rm s3://scholarchain-prod --recursive",
    "{\"a\":\"\\\"}]}; \\u0000 $(rm -rf /)",
    "CALL db.labels() YIELD label RETURN label",
    "1 UNION SELECT password FROM users"
  )
  private val fragments = Vector(
    "DETACH",
    "DROP TABLE",
    "aws s3",
    "rm -rf",
    "UNION",
    "db.labels",
    "YIELD"
  )

  private def code(body: => Any): ErrorCode =
    intercept[StorageException](body).code

  private def planKey(r: WorkflowResult) =
    r.plan.map(p =>
      (
        p.operation,
        p.researcherIds,
        p.window,
        p.comparisonWindow,
        p.metrics,
        p.resultLimit
      )
    )

  private def shape(w: World) =
    w.neptune.log.toVector.map(q => (q.name, q.text, q.params))

  private def clearLogs(w: World, rec: RecordingModel): Unit =
    w.neptune.log.clear()
    w.paths.log.clear()
    w.bucket.queries.clear()
    rec.requests.clear()
    rec.responses.clear()

  // (a) User text cannot become database code ------------------------------------

  test("hostile text beside a real question changes no query, plan or answer") {
    val world = new World()
    val rec = new RecordingModel(new DeterministicStubModel)
    val base = world.ask(clean, rec)
    assert(!base.abstained, base.response.answer)
    val baseShape = shape(world)
    val baseVectors =
      world.bucket.queries.toVector.map(q => (q.index, q.topK, q.filter))
    hostile.foreach { h =>
      clearLogs(world, rec)
      val r = world.ask(s"$clean $h", rec)
      assert(!r.abstained, s"$h: ${r.response.answer}")
      world.neptune.log.foreach { q =>
        assert(AllowlistedNeptune.Templates.contains(q.name), q.name)
        val template =
          if q.name == CypherCatalog.CoauthorsOf then
            CypherCatalog.coauthorsOf("x", "w", 1).text
          else CypherCatalog.edgeEvidence("x", 1).text
        assertEquals(q.text, template)
        val values = q.params.values.map(v => ujson.write(v)).mkString(" ")
        fragments.foreach(f => assert(!values.contains(f), s"$f in $values"))
      }
      assertEquals(shape(world), baseShape, h)
      assertEquals(world.paths.log.toVector, Vector.empty)
      assertEquals(
        world.bucket.queries.toVector.map(q => (q.index, q.topK, q.filter)),
        baseVectors
      )
      assertEquals(planKey(r), planKey(base), h)
      assertEquals(r.response.answer, base.response.answer, h)
      rec.requests.foreach { req =>
        val input = P9Steer.unwrap(req.input)
        assertEquals(
          input("instructions").str,
          if req.promptId == Prompts.Synthesis.id then Prompts.Synthesis.text
          else Prompts.Planner.text
        )
        val outside = ujson.write(
          ujson.Obj.from(input.obj.toSeq.filterNot(_._1 == "untrusted"))
        )
        fragments.foreach(f =>
          assert(!outside.contains(f), s"$f in model input")
        )
      }
    }
  }

  test("a question that is only hostile text reaches no port") {
    val world = new World()
    val rec = new RecordingModel(new DeterministicStubModel)
    (hostile ++ Vector("   ", "x" * 2001, "")).foreach { q =>
      clearLogs(world, rec)
      val r = world.ask(q, rec)
      assert(r.abstained, q)
      assert(r.response.claims.isEmpty, q)
      assertEquals(world.neptune.log.toVector, Vector.empty, q)
      assertEquals(world.paths.log.toVector, Vector.empty, q)
      assertEquals(world.bucket.queries.toVector, Vector.empty, q)
      assertEquals(rec.callsOf(Prompts.Synthesis.id), 0, q)
      assert(kindFacts(r, "metric").isEmpty, q)
    }
  }

  test("a NUL character and quotes in the question do not break the audit") {
    val world = new World()
    val r = world.ask(clean + " \u0000 \" ' \\")
    assert(!r.abstained, r.response.answer)
    assert(
      AuditTrail.turn(world.sessions, r.audit.record.sessionId, 1).isDefined
    )
  }

  test("the runtime allowlist is exactly the two catalog templates") {
    val world = new World()
    assertEquals(
      AllowlistedNeptune.Templates,
      Set(CypherCatalog.CoauthorsOf, CypherCatalog.EdgeEvidence)
    )
    val custom = CypherQuery(
      "custom-template",
      "MATCH (a) RETURN a LIMIT $limit",
      Map("limit" -> ujson.Num(1))
    )
    assertEquals(
      code(new AllowlistedNeptune(world.neptune).run(custom)),
      ErrorCode.QueryRejected
    )
    assertEquals(
      code(
        CypherQuery(
          "x",
          "MATCH (n) DETACH DELETE n LIMIT $limit",
          Map("limit" -> ujson.Num(1))
        )
      ),
      ErrorCode.QueryRejected
    )
    assertEquals(
      code(
        CypherQuery(
          "x",
          "MATCH (n) WHERE n.name = 'Alice' RETURN n LIMIT $limit",
          Map("limit" -> ujson.Num(1))
        )
      ),
      ErrorCode.QueryRejected
    )
  }

  // (b) Ambiguous names ----------------------------------------------------------

  private val weiQ =
    "How many distinct coauthors does Wei Zhang have in 2010-2019?"

  test("a shared name returns every candidate and chooses none") {
    val world = new World()
    val r = world.ask(weiQ)
    assert(r.abstained, r.response.answer)
    assertEquals(r.abstentionReason, Some("identity-unresolved"))
    val res = r.resolutions.head
    assertEquals(res.status, ResolutionStatus.Ambiguous)
    assertEquals(res.candidates.map(_.nodeId).toSet, Set(rid("wa"), rid("wb")))
    assert(res.chosen.isEmpty)
    assert(!res.resolved)
    assert(r.plan.forall(_.researcherIds.isEmpty))
    assertEquals(kindFacts(r, "metric"), Vector.empty)
    assert(r.response.answer.contains(rid("wa")))
    assert(r.response.answer.contains(rid("wb")))
    assert(
      r.response.claims.forall(
        _.verificationStatus != VerificationStatus.Verified
      )
    )
    res.candidates.foreach { c =>
      assert(c.candidate.orcid.isDefined)
      assert(c.confidence > 0.0 && c.confidence <= 1.0)
    }
    assert(res.candidates.exists(_.coauthorContext.nonEmpty))
    assertEquals(world.paths.log.toVector, Vector.empty)
  }

  test(
    "only a user choice among the listed candidates resolves a shared name"
  ) {
    val world = new World()
    val key = ResearcherResolverAgent.key("Wei Zhang")
    val a = world.ask(weiQ, disambiguation = Map(key -> rid("wa")))
    assert(!a.abstained, a.response.answer)
    assert(a.resolutions.head.selectedByUser)
    assertEquals(a.plan.map(_.researcherIds), Some(Vector(rid("wa"))))
    val facts = kindFacts(a, "metric")
    assert(facts.forall(f => Facts.s(f, "researcherId").contains(rid("wa"))))
    assert(
      facts.exists(f =>
        Facts
          .n(f, "distinctCoauthorCount")
          .contains(
            o2.distinctCount("wa").toDouble
          )
      )
    )
    val b = world.ask(weiQ, disambiguation = Map(key -> rid("wb")))
    assert(
      kindFacts(b, "metric").exists(f =>
        Facts.s(f, "researcherId").contains(rid("wb")) &&
          Facts
            .n(f, "distinctCoauthorCount")
            .contains(
              o2.distinctCount("wb").toDouble
            )
      )
    )
    assertNotEquals(o2.distinctCount("wa"), o2.distinctCount("wb"))
    // a choice that is not one of the candidates does not resolve the name
    Vector(rid("al"), mentionId, "researcher:v1:nobody").foreach { other =>
      val r = world.ask(weiQ, disambiguation = Map(key -> other))
      assert(r.abstained, other)
      assertEquals(r.abstentionReason, Some("identity-unresolved"))
      assertEquals(kindFacts(r, "metric"), Vector.empty)
    }
  }

  test("a mention, an unknown name and a mention ID abstain") {
    val world = new World()
    val cases = Vector(
      "How many publications does Mara Ortega have in 2000-2009?" ->
        ResolutionStatus.MentionOnly,
      "How many publications does Zelda Zephyr have in 2000-2009?" ->
        ResolutionStatus.NotFound,
      s"How many publications does $mentionId have in 2000-2009?" ->
        ResolutionStatus.MentionOnly
    )
    cases.foreach { case (q, status) =>
      val r = world.ask(q)
      assert(r.abstained, q)
      assertEquals(r.abstentionReason, Some("identity-unresolved"), q)
      assertEquals(r.resolutions.head.status, status, q)
      assertEquals(kindFacts(r, "metric"), Vector.empty, q)
      assert(!r.response.answer.contains("50 distinct"), q)
      assert(
        r.response.claims.forall(
          _.verificationStatus != VerificationStatus.Verified
        ),
        q
      )
    }
  }

  test("a unique canonical name resolves with a stated confidence") {
    val world = new World()
    val r = world.ask(clean)
    val res = r.resolutions.head
    assertEquals(res.status, ResolutionStatus.Resolved)
    assertEquals(res.chosen.map(_.nodeId), Some(rid("al")))
    assert(res.chosen.exists(c => c.confidence > 0.5 && c.confidence <= 1.0))
    assert(
      kindFacts(r, "identity").exists(f =>
        Facts.s(f, "researcherId").contains(rid("al")) &&
          Facts.n(f, "confidence").isDefined
      )
    )
  }

  // (c) Every numeric and path claim has resolving evidence IDs --------------------

  private val battery = Vector(
    clean,
    "What is the shortest path between Alice Archer and Greta Garcia in 2010-2019?",
    "Who has the most distinct coauthors in 2010-2019?",
    "Which community is Jonas Jensen in during 2010-2019?",
    "Compare Alice Archer between 2000-2009 and 2010-2019.",
    "List the coauthors of Jonas Jensen in 2010-2019.",
    "What is the shortest path between Alice Archer and Kofi Kamara in 2010-2019?",
    "How many distinct coauthors does Jonas Jensen have in 2010-2019?"
  )

  test("every numeric and path claim cites evidence that resolves") {
    val world = new World()
    battery.foreach { q =>
      val r = world.ask(q)
      assert(!r.abstained, q)
      assertEquals(evidenceViolations(r), Vector.empty, q)
      val ids = r.response.evidence.items.map(_.evidenceId).toSet
      assert(r.response.claims.nonEmpty, q)
      r.response.claims.foreach { c =>
        assert(c.evidenceIds.forall(ids.contains), s"$q ${c.claimId}")
        if c.verificationStatus == VerificationStatus.Verified then
          assert(c.evidenceIds.nonEmpty, c.claimId)
      }
      // a path claim cites the path evidence item, not only a metric
      if q.contains("shortest path") then
        val cited = r.response.claims
          .filter(_.text.toLowerCase.contains("path"))
          .flatMap(_.evidenceIds)
        assert(cited.nonEmpty, q)
    }
  }

  // (i) Audit --------------------------------------------------------------------

  private val hex64 = "[0-9a-f]{64}"

  test(
    "the audit record holds plan, ids, versions, runs, model, latency, hash"
  ) {
    val world = new World()
    val wf = world.workflow(clock = new ScriptedClock(Vector(1000L, 1042L)))
    val r = wf.answer(clean, Session("audit-1", 7))
    val p = r.audit.payload
    assertEquals(p("sessionId").str, "audit-1")
    assertEquals(p("ordinal").num.toInt, 7)
    assertEquals(p("queryPlan")("operation").str, "collaboration-metrics")
    assertEquals(
      p("queryPlan")("researcherIds").arr.map(_.str).toVector,
      Vector(rid("al"))
    )
    assertEquals(p("resolutions")(0)("resolvedId").str, rid("al"))
    assertEquals(
      p("evidenceIds").arr.map(_.str).toVector,
      r.response.evidence.items.map(_.evidenceId)
    )
    assert(p("metricVersions").arr.map(_.str).contains(sparkVersion))
    assertEquals(p("graphRunId").str, graphRun)
    assertEquals(p("queryRunId").str, queryRun)
    val prompt = p("prompts").arr.find(_("id").str == Prompts.Synthesis.id).get
    assertEquals(prompt("version").str, Prompts.Synthesis.version)
    assert(prompt("hash").str.matches(hex64))
    assertEquals(prompt("schemaId").str, Schemas.SynthesisOutputId)
    assert(p("schemaHashes").obj.nonEmpty)
    assert(
      p("models").arr.map(_.str).contains(DeterministicStubModel.Version)
    )
    assert(p("modelCalls").num >= 1)
    assertEquals(p("tokens")("estimated").bool, true)
    assertEquals(p("latencyMillis").num, 42.0)
    assertEquals(p("verification")("overall").str, "VERIFIED")
    assert(p("answerHash").str.matches(hex64))
    assertEquals(p("answerHash").str, r.audit.answerHash)
    assertEquals(p("abstained").bool, false)
    // what was stored is what the workflow reports
    val stored = AuditTrail.turn(world.sessions, "audit-1", 7).get
    assertEquals(ujson.read(stored.payloadJson), p)
    assertEquals(
      AuditTrail.bundle(world.bundles, p("bundleId").str),
      Some(r.response.evidence)
    )
  }

  test("an abstained answer is audited with its reason and candidates") {
    val world = new World()
    val r = world.workflow().answer(weiQ, Session("audit-2", 1))
    val p = r.audit.payload
    assertEquals(p("abstained").bool, true)
    assertEquals(p("abstentionReason").str, "identity-unresolved")
    assertEquals(
      p("resolutions")(0)("candidateIds").arr.map(_.str).toSet,
      Set(rid("wa"), rid("wb"))
    )
    assert(p("resolutions")(0)("resolvedId") == ujson.Null)
    assert(AuditTrail.turn(world.sessions, "audit-2", 1).isDefined)
    assert(p("answerHash").str.matches(hex64))
  }

  // (j) Rerun determinism --------------------------------------------------------

  test("a rerun gives the same answer, claims, bundle, audit and hash") {
    val questions = battery ++ Vector(weiQ, "Who is Alice Archer?")
    questions.foreach { q =>
      def run(): WorkflowResult =
        new World()
          .workflow(clock = new ScriptedClock(Vector(1000L, 1042L)))
          .answer(q, Session("det", 1))
      val a = run()
      val b = run()
      assertEquals(a.response, b.response, q)
      assertEquals(a.audit.payload, b.audit.payload, q)
      assertEquals(a.audit.answerHash, b.audit.answerHash, q)
      assertEquals(a.abstained, b.abstained, q)
    }
  }

  test("asking again in one world is idempotent and gives the same hash") {
    val world = new World()
    val first = world.workflow().answer(clean, Session("same", 1))
    val again = world.workflow().answer(clean, Session("same", 1))
    assertEquals(again.audit.outcome.turn, WriteOutcome.Unchanged)
    assertEquals(again.audit.answerHash, first.audit.answerHash)
    val other = world.workflow().answer(clean, Session("other-session", 2))
    assertEquals(other.audit.answerHash, first.audit.answerHash)
    assertEquals(other.response.answer, first.response.answer)
  }

  test("a different question gives a different hash") {
    val world = new World()
    val a = world.ask(clean)
    val b = world.ask(
      "How many distinct coauthors does Jonas Jensen have in 2010-2019?"
    )
    assertNotEquals(a.audit.answerHash, b.audit.answerHash)
  }

  // (k) No chain of thought ------------------------------------------------------

  test("no output, audit record, schema or prompt carries chain of thought") {
    val world = new World()
    val rec = new RecordingModel(new DeterministicStubModel)
    (battery ++ Vector(weiQ, "Compare Alice Archer over time.")).foreach { q =>
      val r = world.ask(q, rec)
      assertEquals(reasoningKeysIn(responseJson(r)), Set.empty[String], q)
      assertEquals(reasoningKeysIn(r.audit.payload), Set.empty[String], q)
      assertEquals(
        reasoningKeysIn(ujson.read(r.audit.record.payloadJson)),
        Set.empty[String],
        q
      )
    }
    rec.responses.foreach { resp =>
      assertEquals(reasoningKeysIn(ujson.read(resp.rawJson)), Set.empty[String])
    }
    Schemas.byId.foreach { case (id, schema) =>
      assertEquals(reasoningKeysIn(schema), Set.empty[String], id)
    }
    Prompts.all.foreach { p =>
      val text = p.text.toLowerCase
      Vector(
        "step by step",
        "chain of thought",
        "show your work",
        "think aloud"
      )
        .foreach(w => assert(!text.contains(w), s"${p.id} asks for $w"))
    }
  }

  // Non-path honesty --------------------------------------------------------------

  private val pathQ =
    "What is the shortest path between Alice Archer and Greta Garcia in 2010-2019?"

  test("a timed-out path lookup never says there is no path") {
    val world = new World(WorldOptions(pathCost = _ => 5000L))
    val r = world.ask(pathQ)
    assert(!r.abstained, r.response.answer)
    assert(
      kindFacts(r, "path-status").exists(f =>
        Facts.s(f, "pathKind").contains("TimedOut") &&
          Facts.b(f, "noPathExists").contains(false)
      )
    )
    assert(
      noPathClaim.findFirstIn(r.response.answer).isEmpty,
      r.response.answer
    )
    assert(kindFacts(r, "path").isEmpty)
  }

  test("a path longer than the depth or result bound is not called absent") {
    val world = new World()
    val depth = world.ask(
      "Find the shortest path from Alice Archer to Ines Iyer in 2010-2019.",
      cfg = world.config().copy(paths = PathLimits(maxDepth = 3))
    )
    assert(
      kindFacts(depth, "path-status").exists(f =>
        Facts.s(f, "pathKind").contains("ExceedsDepth") &&
          Facts.b(f, "noPathExists").contains(false)
      ),
      depth.response.answer
    )
    assert(noPathClaim.findFirstIn(depth.response.answer).isEmpty)
    val results =
      world.ask(
        pathQ,
        cfg = world.config().copy(paths = PathLimits(maxResults = 2))
      )
    assert(
      kindFacts(results, "path-status").exists(f =>
        Facts.s(f, "pathKind").contains("ExceedsResults") &&
          Facts.b(f, "noPathExists").contains(false)
      ),
      results.response.answer
    )
    assert(noPathClaim.findFirstIn(results.response.answer).isEmpty)
  }

  test("path reads carry the configured depth, result and time bounds") {
    val world = new World()
    world.ask(pathQ)
    val limits = PathLimits()
    assert(world.paths.log.nonEmpty)
    world.paths.log.foreach { q =>
      assertEquals(q.maxDepth, limits.maxDepth)
      assertEquals(q.maxResults, limits.maxResults)
      assertEquals(q.timeoutMillis, limits.timeoutMillis)
      assertEquals(q.window, "2010-2019")
    }
    world.neptune.log.foreach { q =>
      assert(q.params("limit").num <= CypherQuery.MaxLimit)
    }
  }

  test("only a proven different-component pair is reported as no path") {
    val world = new World()
    val cases = Vector(
      ("Alice Archer", "Kofi Kamara", true),
      ("Alice Archer", "Wei Zhang", false)
    )
    // the second pair is ambiguous, so it abstains instead of answering
    val a = world.ask(
      s"What is the shortest path between ${cases(0)._1} and ${cases(0)._2} in 2010-2019?"
    )
    assert(
      kindFacts(a, "path-status").forall(f =>
        Facts.b(f, "noPathExists").contains(true)
      )
    )
    val b = world.ask(
      s"What is the shortest path between ${cases(1)._1} and ${cases(1)._2} in 2010-2019?"
    )
    assert(b.abstained)
    assert(kindFacts(b, "path-status").isEmpty)
    assert(noPathClaim.findFirstIn(b.response.answer).isEmpty)
  }

  test("the verdict mapping to the core status is the documented one") {
    import VerificationVerdict.*
    assertEquals(toCore(Verified), VerificationStatus.Verified)
    assertEquals(toCore(PartiallySupported), VerificationStatus.Uncertain)
    assertEquals(toCore(Unsupported), VerificationStatus.Unsupported)
    assertEquals(toCore(Conflicting), VerificationStatus.Unsupported)
  }
