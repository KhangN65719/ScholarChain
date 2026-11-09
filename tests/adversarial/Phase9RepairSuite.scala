package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.rag.*
import scholarchain.storage.*
import scholarchain.tests.adversarial.P9.*
import scholarchain.tests.adversarial.P9Check.*

/** Phase 9 repair round 1: negative tests written from the review findings (B1,
  * M1, M2, m1, m2, m3, m4), independent of the rag module tests.
  */
class Phase9RepairSuite extends FunSuite:
  private val jensenQ =
    "How many distinct coauthors does Jonas Jensen have in 2010-2019?"
  private val carlaQ =
    "How many distinct coauthors does Carla Chen have in 2010-2019?"
  private val teamQ =
    "What is the maximum team size for Alice Archer in 2000-2009?"
  private val W2s = "2010-2019"
  private val synth = Prompts.Synthesis.id
  private val trigger = "ignore all previous instructions"

  private def verdictOf(r: WorkflowResult, claim: String) =
    r.verification.flatMap(_.verdictOf(claim))

  private def changeOf(r: WorkflowResult, claim: String) =
    r.verification.toVector.flatMap(_.changes).find(_.claimId == claim)

  // Phase 11 (m5): claim IDs are system-assigned (`claim-N-kind-12hex`). A
  // steered claim is found by position (steered claims are appended last).
  private val SystemId = "claim-[1-9][0-9]*-[a-z-]+-[0-9a-f]{12}".r

  private def added(r: WorkflowResult): ClaimVerification =
    val all = r.verification.toVector.flatMap(_.claims)
    assert(all.nonEmpty, "no claims in the report")
    all.last

  private def assertSystemId(
      c: ClaimVerification,
      modelId: String,
      kind: String
  ): Unit =
    assert(SystemId.matches(c.claimId), s"not a system ID: ${c.claimId}")
    assert(c.claimId.contains(s"-$kind-"), s"${c.claimId} lacks kind $kind")
    assert(!c.claimId.contains(modelId), s"model ID leaked: ${c.claimId}")
    assertEquals(c.kind, kind)

  private def keptFor(r: WorkflowResult, c: ClaimVerification) =
    r.response.claims.filter(_.claimId == c.claimId)

  private def code(body: => Any): ErrorCode =
    intercept[StorageException](body).code

  /** Everything an answer shows or stores that is not retrieved evidence. */
  private def claimsJson(r: WorkflowResult): String =
    ujson.write(upickle.default.writeJs(r.response.claims))

  private def surfaces(r: WorkflowResult): Vector[(String, String)] = Vector(
    "answer" -> r.response.answer,
    "claims" -> claimsJson(r),
    "audit" -> ujson.write(r.audit.payload),
    "audit-record" -> r.audit.record.toString
  )

  private def assertAbsent(r: WorkflowResult, needle: String): Unit =
    surfaces(r).foreach { (where, text) =>
      assert(
        !text.toLowerCase.contains(needle.toLowerCase),
        s"model-authored text '$needle' reached $where: $text"
      )
    }

  private def recordedInAudit(r: WorkflowResult, claimId: String): Boolean =
    r.audit.payload("verification")("changes").arr.exists { c =>
      c("claimId").str == claimId
    }

  // B1: free-text claim kinds -----------------------------------------------------

  private def semanticSteer(id: String, text: String) =
    P9Steer.steerSynthesis { (out, input) =>
      P9Steer.addClaims(
        out,
        P9Steer.claim(
          id,
          "semantic",
          text,
          P9Steer.chunkIds(input).take(1),
          ujson.Obj("kind" -> "semantic")
        )
      )
    }

  test(
    "B1a: a semantic claim asserting coauthorship and a bridge is not shown"
  ) {
    val world = new World()
    val text =
      "Jonas Jensen and Alice Archer are direct coauthors and the main bridge between the communities."
    val r = world.ask(jensenQ, semanticSteer("b1a", text))
    assert(!r.abstained, r.response.answer)
    assertAbsent(r, "main bridge between the communities")
    assertAbsent(r, "are direct coauthors and")
    val x = added(r)
    assertSystemId(x, "b1a", "semantic")
    assertNotEquals(x.verdict, VerificationVerdict.Verified)
    // any trace of the claim is the system's wording, never the model's
    keptFor(r, x).foreach { c =>
      assertEquals(c.text, Rendering.SemanticText)
    }
    assert(
      changeOf(r, x.claimId).isDefined,
      "the removal or rewording is unrecorded"
    )
    assert(
      recordedInAudit(r, x.claimId),
      "the change is missing from the audit"
    )
    assertEquals(r.verdict.contains(VerificationVerdict.Verified), false)
    assertEquals(evidenceViolations(r), Vector.empty)
  }

  test(
    "B1b: a semantic claim with a spelled number above twenty is not shown"
  ) {
    val world = new World()
    Vector(
      "Researchers in this area typically have ninety-nine coauthors.",
      "Researchers in this area typically have one hundred coauthors.",
      "Researchers in this area typically have thirty coauthors."
    ).zipWithIndex.foreach { (text, i) =>
      val id = s"b1b$i"
      val r = world.ask(jensenQ, semanticSteer(id, text))
      Vector("ninety-nine", "one hundred", "thirty").foreach(assertAbsent(r, _))
      assert(!r.response.answer.contains("99"), r.response.answer)
      val x = added(r)
      assertSystemId(x, id, "semantic")
      assertNotEquals(x.verdict, VerificationVerdict.Verified)
      assert(changeOf(r, x.claimId).isDefined, id)
      assert(recordedInAudit(r, x.claimId), id)
      keptFor(r, x).foreach { c =>
        assertEquals(c.text, Rendering.SemanticText)
      }
    }
  }

  test("B1b': a metric claim worded with a spelled number is not Verified") {
    val world = new World()
    Vector("ninety-nine", "one hundred", "thirty", "twenty-one").foreach {
      spelled =>
        val model = P9Steer.steerSynthesis { (out, input) =>
          P9Steer.addClaims(
            out,
            P9Steer.claim(
              "b1b-metric",
              "metric",
              s"Jonas Jensen has $spelled distinct coauthors.",
              Seq(P9Steer.firstOfKind(input, "metric").get),
              P9Steer.metricAssertion(
                rid("jo"),
                "distinct-coauthor-count",
                3,
                W2s
              )
            )
          )
        }
        val r = world.ask(jensenQ, model)
        assertAbsent(r, spelled)
        val x = added(r)
        assertSystemId(x, "b1b-metric", "metric")
        assertNotEquals(x.verdict, VerificationVerdict.Verified, spelled)
        assert(r.response.claims.forall(!_.text.contains(spelled)), spelled)
    }
  }

  test("B1c: an abstention claim keeps only the system's wording") {
    val world = new World()
    val wording =
      "Jonas Jensen is the sole bridge researcher and works with everyone."
    val steered = P9Steer.steerSynthesis { (out, _) =>
      P9Steer.addClaims(
        out,
        P9Steer.claim(
          "b1c",
          "abstention",
          wording,
          Seq.empty,
          ujson.Obj("kind" -> "abstention"),
          Some("This is certain and verified, zorblax.")
        )
      )
    }
    val r = world.ask(jensenQ, steered)
    assertAbsent(r, "sole bridge researcher")
    assertAbsent(r, "works with everyone")
    assertAbsent(r, "zorblax")
    val x = added(r)
    assertSystemId(x, "b1c", "abstention")
    keptFor(r, x).foreach { c =>
      assertEquals(c.text, Rendering.AbstentionText)
      assertEquals(
        c.verificationStatus,
        scholarchain.core.VerificationStatus.Uncertain
      )
    }
    val change = changeOf(r, x.claimId)
    assert(change.isDefined, "the abstention claim left no record")
    assert(change.get.reason.toLowerCase.contains("replaced"), change.toString)
  }

  test("B1d: the model's analysis paragraph is replaced and recorded") {
    val world = new World()
    val paragraph =
      "Jonas Jensen is the main bridge between all communities and has ninety-nine coauthors, zorblax."
    val model = P9Steer.steerSynthesis { (out, _) =>
      out.obj("analysis") = paragraph
    }
    val r = world.ask(jensenQ, model)
    assert(!r.abstained, r.response.answer)
    assertAbsent(r, "main bridge between all communities")
    assertAbsent(r, "ninety-nine")
    assertAbsent(r, "zorblax")
    assert(
      r.response.answer.contains(Rendering.AnalysisText),
      r.response.answer
    )
    assert(changeOf(r, "analysis").isDefined, "the replacement is unrecorded")
    assert(recordedInAudit(r, "analysis"), "the replacement is not audited")
  }

  test("B1d': an analysis paragraph is not shown when no claim is kept") {
    val world = new World()
    val model = P9Steer.steerSynthesis { (out, _) =>
      out.obj("analysis") = "Everything is certain, zorblax."
      out.obj("claims") = ujson.Arr()
    }
    val r = world.ask(jensenQ, model)
    assert(r.abstained, r.response.answer)
    assertAbsent(r, "zorblax")
    assert(recordedInAudit(r, "analysis"))
  }

  test(
    "B1e: per-claim and top-level model uncertainty never reach the output"
  ) {
    val world = new World()
    val model = P9Steer.steerSynthesis { (out, _) =>
      out("claims").arr.foreach { c =>
        c.obj("uncertainty") =
          "Roughly 4,242 coauthors and 17 bridges, definitely certain, zorblax."
      }
      out.obj("uncertainty") = "Top-level 9,999 verified, zorblax-top."
    }
    val r = world.ask(jensenQ, model)
    assert(!r.abstained, r.response.answer)
    assert(r.response.claims.nonEmpty)
    assertAbsent(r, "zorblax")
    assertAbsent(r, "4,242")
    assertAbsent(r, "9,999")
    assertEquals(evidenceViolations(r), Vector.empty)
  }

  // M1: a number must match the asserted value, not any number in the fact -------

  test("M1: a number from another field of the cited fact is not shown") {
    val world = new World()
    // independent facts about the fixture, from the oracle
    assertEquals(o2.distinctCount("ca"), 3)
    assertEquals(o2.pubs("ca"), 2)
    Vector(
      ("2 distinct coauthors", "the publication count"),
      ("2019 coauthors", "the window end"),
      ("2010 coauthors", "the window start"),
      ("4 distinct coauthors", "a number in no field")
    ).zipWithIndex.foreach { case ((phrase, why), i) =>
      val id = s"m1-$i"
      val model = P9Steer.steerSynthesis { (out, input) =>
        P9Steer.addClaims(
          out,
          P9Steer.claim(
            id,
            "metric",
            s"Carla Chen has $phrase.",
            Seq(P9Steer.firstOfKind(input, "metric").get),
            P9Steer.metricAssertion(
              rid("ca"),
              "distinct-coauthor-count",
              3,
              W2s
            )
          )
        )
      }
      val r = world.ask(carlaQ, model)
      assert(!r.abstained, r.response.answer)
      val x = added(r)
      assertSystemId(x, id, "metric")
      assertNotEquals(x.verdict, VerificationVerdict.Verified, why)
      assert(!r.response.answer.contains(phrase), s"$why: ${r.response.answer}")
      assert(r.response.claims.forall(!_.text.contains(phrase)), why)
      // the asserted value is what the answer states
      assert(
        r.response.answer.contains("3 distinct coauthors"),
        r.response.answer
      )
      keptFor(r, x).foreach { c =>
        assertNotEquals(
          c.verificationStatus,
          scholarchain.core.VerificationStatus.Verified
        )
      }
    }
  }

  test("M1: the honest wording of the same assertion is Verified") {
    val world = new World()
    val model = P9Steer.steerSynthesis { (out, input) =>
      P9Steer.addClaims(
        out,
        P9Steer.claim(
          "m1-honest",
          "metric",
          "Carla Chen has 3 distinct coauthors.",
          Seq(P9Steer.firstOfKind(input, "metric").get),
          P9Steer.metricAssertion(rid("ca"), "distinct-coauthor-count", 3, W2s)
        )
      )
    }
    val clean = world.ask(carlaQ)
    val r = world.ask(carlaQ, model)
    val x = added(r)
    // the steered claim was appended: one more claim than the clean run, and
    // the last claim is not one the clean run already had
    assertEquals(
      r.verification.toVector.flatMap(_.claims).size,
      clean.verification.toVector.flatMap(_.claims).size + 1
    )
    assert(
      !clean.verification.toVector
        .flatMap(_.claims)
        .exists(_.claimId == x.claimId),
      s"the last claim ${x.claimId} existed in the clean run"
    )
    assertSystemId(x, "m1-honest", "metric")
    assertEquals(x.verdict, VerificationVerdict.Verified)
  }

  // M2: data strings are untrusted in the model input ---------------------------

  private def stringsWithPath(
      v: ujson.Value,
      path: Vector[String] = Vector.empty
  ): Vector[(Vector[String], String)] = v match
    case ujson.Str(s) => Vector(path -> s)
    case ujson.Obj(m) =>
      m.toVector.flatMap((k, x) => stringsWithPath(x, path :+ k))
    case ujson.Arr(a) =>
      a.toVector.zipWithIndex.flatMap((x, i) =>
        stringsWithPath(x, path :+ s"[$i]")
      )
    case _ => Vector.empty

  private def injectedPaths(rec: RecordingModel): Vector[Vector[String]] =
    rec.requests.toVector
      .filter(_.promptId == synth)
      .flatMap(req =>
        stringsWithPath(P9Steer.unwrap(req.input)).collect {
          case (path, s) if s.toLowerCase.contains(trigger) => path
        }
      )

  private def auditFlags(r: WorkflowResult): Vector[String] =
    r.audit.payload("flags").arr.toVector.map(_.str)

  test("M2: an injected display name is under an untrusted key and flagged") {
    val world = new World(WorldOptions(injectName = true))
    val rec = new RecordingModel(new DeterministicStubModel)
    val r = world.ask(
      s"How many distinct coauthors does ${rid("hi")} have in $W2s?",
      rec
    )
    assert(!r.abstained, r.response.answer)
    val paths = injectedPaths(rec)
    assert(paths.nonEmpty, "the injected name never reached the model")
    paths.foreach { p =>
      assert(
        p.exists(_.startsWith("untrusted")),
        s"the injected name sits under a trusted key: ${p.mkString(".")}"
      )
    }
    val flags = r.flags.filter(_.startsWith("stored-name:"))
    assert(flags.exists(_.contains("ignore-instructions")), r.flags.toString)
    assertEquals(auditFlags(r).toSet, r.flags.toSet)
    assert(auditFlags(r).exists(_.startsWith("stored-name:")))
  }

  test("M2: a coauthor name in a gap or fact is untrusted too") {
    val world = new World(WorldOptions(injectName = true))
    val rec = new RecordingModel(new DeterministicStubModel)
    // Hiro Hayashi is the injected record; ask for his edge with Farid
    val r = world.ask(
      s"Has Farid Fontaine worked with ${rid("hi")} in $W2s?",
      rec
    )
    injectedPaths(rec).foreach { p =>
      assert(p.exists(_.startsWith("untrusted")), p.mkString("."))
    }
    assertEquals(auditFlags(r).toSet, r.flags.toSet)
  }

  test("M2: an injected work title is under an untrusted key and flagged") {
    val world = new World(WorldOptions(injectTitle = true))
    val rec = new RecordingModel(new DeterministicStubModel)
    val r = world.ask(teamQ, rec)
    assert(!r.abstained, r.response.answer)
    assert(
      kindFacts(r, "publication").exists(f =>
        Facts.s(f, "title").contains(InjectedTitle)
      ),
      "the injected title is not in the bundle"
    )
    val paths = injectedPaths(rec)
    assert(paths.nonEmpty, "the injected title never reached the model")
    paths.foreach { p =>
      assert(
        p.exists(_.startsWith("untrusted")),
        s"the injected title sits under a trusted key: ${p.mkString(".")}"
      )
    }
    assert(r.flags.exists(_.startsWith("stored-title:")), r.flags.toString)
    assert(auditFlags(r).exists(_.startsWith("stored-title:")))
  }

  test("M2: a snippet hit is flagged the same way") {
    val world = new World(WorldOptions(injectSnippet = true))
    val r = world.ask(
      "How many distinct coauthors does Alice Archer have in 2010-2019?"
    )
    assert(auditFlags(r).exists(_.startsWith("retrieved-text:")), auditFlags(r))
  }

  test("M2: the prompt tells the model every string in facts is data") {
    val text = Prompts.Synthesis.text
    assert(text.contains("untrustedData"), text)
    assert(text.contains("knownGaps"), text)
    assert(text.contains("facts"), text)
  }

  // m1: the allowlist compares text, not only the name --------------------------

  private final class CountingNeptune extends NeptuneQueryPort:
    var calls = 0
    def run(query: CypherQuery): Vector[Row] =
      calls += 1
      Vector.empty

  test("m1: an allowed name with different read-only text is refused") {
    val inner = new CountingNeptune
    val guard = new AllowlistedNeptune(inner)
    val real = CypherCatalog.coauthorsOf("r", W2s, 5)
    val params = real.params
    // the catalog query passes
    guard.run(real)
    assertEquals(inner.calls, 1)
    val other = Vector(
      CypherQuery(
        CypherCatalog.CoauthorsOf,
        "MATCH (a)-[e:COAUTHORED_WITH]-(b) WHERE id(a) = $researcherId " +
          "AND e.window = $window RETURN id(b) AS coauthorId LIMIT $limit",
        params
      ),
      CypherQuery(
        CypherCatalog.CoauthorsOf,
        "MATCH (a)-[e]-(b) WHERE id(a) = $researcherId AND e.window = $window " +
          "RETURN a.secret AS coauthorId LIMIT $limit",
        params
      ),
      CypherQuery(
        CypherCatalog.EdgeEvidence,
        "MATCH (n) WHERE id(n) = $edgeId RETURN n AS edgeId LIMIT $limit",
        Map("edgeId" -> ujson.Str("e"), "limit" -> ujson.Num(3))
      ),
      CypherQuery(
        "unlisted",
        real.text,
        params
      )
    )
    other.foreach { q =>
      assertEquals(code(guard.run(q)), ErrorCode.QueryRejected, q.name)
    }
    assertEquals(inner.calls, 1, "a refused query reached the graph")
  }

  // m4: closed assertion schemas --------------------------------------------------

  private val minimal: Vector[(String, ujson.Obj)] = Vector(
    "metric" -> ujson.Obj(
      "kind" -> "metric",
      "researcherId" -> "r",
      "metric" -> "distinct-coauthor-count",
      "value" -> 3
    ),
    "ranking" -> ujson.Obj(
      "kind" -> "ranking",
      "metric" -> "distinct-coauthor-count",
      "rank" -> 1,
      "researcherId" -> "r"
    ),
    "coauthor" -> ujson.Obj(
      "kind" -> "coauthor",
      "researcherId" -> "r",
      "coauthorId" -> "c"
    ),
    "path" -> ujson.Obj("kind" -> "path", "source" -> "a", "target" -> "b"),
    "path-status" -> ujson.Obj(
      "kind" -> "path-status",
      "source" -> "a",
      "target" -> "b",
      "noPathExists" -> false
    ),
    "component" -> ujson.Obj("kind" -> "component", "researcherId" -> "r"),
    "community" -> ujson.Obj("kind" -> "community", "researcherId" -> "r"),
    "publication" -> ujson.Obj("kind" -> "publication", "workId" -> "w"),
    "identity" -> ujson.Obj("kind" -> "identity", "researcherId" -> "r"),
    "semantic" -> ujson.Obj("kind" -> "semantic"),
    "definition" -> ujson.Obj("kind" -> "definition", "term" -> "breadth"),
    "abstention" -> ujson.Obj("kind" -> "abstention")
  )

  private def synthesisReply(kind: String, assertion: ujson.Value) =
    ujson.Obj(
      "schema" -> Schemas.SynthesisOutputId,
      "analysis" -> "",
      "claims" -> ujson.Arr(
        ujson.Obj(
          "claimId" -> "c1",
          "text" -> "text",
          "kind" -> kind,
          "evidenceIds" -> ujson.Arr("evidence:v1:" + "0" * 64),
          "assertion" -> assertion,
          "uncertainty" -> ujson.Null
        )
      ),
      "missingEvidence" -> ujson.Arr(),
      "uncertainty" -> ujson.Null
    )

  test("m4: every claim kind accepts its assertion and rejects an extra key") {
    assertEquals(minimal.map(_._1).toSet, Schemas.ClaimKinds.toSet)
    minimal.foreach { (kind, assertion) =>
      assertEquals(
        SchemaCheck.validate(
          Schemas.SynthesisOutput,
          synthesisReply(kind, assertion)
        ),
        Vector.empty[String],
        s"the valid $kind assertion was refused"
      )
      Vector("reasoning", "thoughts", "extra").foreach { key =>
        val withExtra = ujson.read(ujson.write(assertion)).obj
        withExtra(key) = "because"
        assert(
          SchemaCheck
            .validate(Schemas.SynthesisOutput, synthesisReply(kind, withExtra))
            .nonEmpty,
          s"$kind accepted the extra assertion key $key"
        )
      }
    }
  }

  test("m4: an assertion that names another kind or misses a key is refused") {
    val wrongKind = synthesisReply("metric", minimal.toMap.apply("semantic"))
    assert(SchemaCheck.validate(Schemas.SynthesisOutput, wrongKind).nonEmpty)
    val missing = ujson.read(ujson.write(minimal.toMap.apply("metric"))).obj
    missing.remove("value")
    assert(
      SchemaCheck
        .validate(Schemas.SynthesisOutput, synthesisReply("metric", missing))
        .nonEmpty
    )
  }

  test(
    "m4: a steered assertion with a reasoning key is retried, then abstains"
  ) {
    val world = new World()
    val rec = new RecordingModel(
      P9Steer.steerSynthesis { (out, _) =>
        out("claims")(0)("assertion").obj("reasoning") =
          "my chain of thought, zorblax"
      }
    )
    val r = world.ask(jensenQ, rec)
    assert(r.abstained)
    assertEquals(r.abstentionReason, Some("synthesis-failed"))
    assertEquals(rec.callsOf(synth), ModelRetryPolicy().maxAttempts)
    assertAbsent(r, "zorblax")
    assertAbsent(r, "chain of thought")
    assert(r.response.claims.isEmpty)
    assertEquals(reasoningKeysIn(responseJson(r)), Set.empty[String])
  }

  // m2: pinned literal hashes -----------------------------------------------------

  private val pinnedPrompts: Map[(String, String), String] = Map(
    ("planner-classify", "1.0.0") ->
      "408c5c03311abab8f71a49e8d7646d4fb0d1774e75402c9e240a9371c1dce0a1",
    ("synthesis", "1.1.0") ->
      "40c6597d381abb03df6a26a4b2b2984a5f95df5eb507a26041361d1de7cba7c7"
  )
  private val pinnedSchemas: Map[String, String] = Map(
    "scholarchain.rag.plan-output/1" ->
      "b55d8e903f6f2084079c9c7cb66d5bd78623ea1293d534e03a05818ba354503a",
    "scholarchain.rag.synthesis-output/3" ->
      "3ff03e974a310c6399465499474c70c1242bed7520e9db8f45b42129bbb89226",
    "scholarchain.rag.verifier-report/1" ->
      "e3272063134813c56e480d638e8490ec3e7d34c556c82d8610ce1c6cb0a5e00a"
  )

  test(
    "m2: the shipped prompt and schema versions are pinned to literal hashes"
  ) {
    val seenPrompts =
      Prompts.all.map(p => (p.id, p.version) -> p.hash).toMap
    assertEquals(seenPrompts, pinnedPrompts)
    assertEquals(Schemas.hashes, pinnedSchemas)
  }

  test("m2 (Phase 11): the pinned literals equal an independent sha256") {
    def sha(text: String): String =
      java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(text.getBytes("UTF-8"))
        .map(b => f"${b & 0xff}%02x")
        .mkString
    // recomputed here with the JDK, not with the production hashing helper
    val recomputed = Schemas.byId.map((id, s) => id -> sha(ujson.write(s)))
    assertEquals(recomputed, pinnedSchemas)
    Prompts.all.foreach(p =>
      assertEquals(pinnedPrompts((p.id, p.version)), sha(p.text), p.id)
    )
    // the old /2 schema is gone
    assert(!Schemas.byId.contains("scholarchain.rag.synthesis-output/2"))
  }

  test("m2: the synthesis prompt and schema moved past the round-0 versions") {
    assertNotEquals(Prompts.Synthesis.version, "1.0.0")
    assertNotEquals(
      Schemas.SynthesisOutputId,
      "scholarchain.rag.synthesis-output/1"
    )
    assertEquals(Prompts.Synthesis.schemaId, Schemas.SynthesisOutputId)
  }

  test("m2: an audit record carries the pinned hashes") {
    val world = new World()
    val r = world.ask(
      "How many distinct coauthors does Alice Archer have in 2010-2019?"
    )
    val prompts = r.audit
      .payload("prompts")
      .arr
      .map { p =>
        (p("id").str, p("version").str) -> p("hash").str
      }
      .toMap
    prompts.foreach((k, v) => assertEquals(pinnedPrompts.get(k), Some(v), k))
    val schemas =
      r.audit.payload("schemaHashes").obj.map((k, v) => k -> v.str).toMap
    assertEquals(schemas, pinnedSchemas)
  }

  // m3: a bundle within the caps fits the token budget -------------------------

  test("m3: a bundle filled to the caps still fits maxInputTokens") {
    val world = new World()
    val caps = EvidenceCaps()
    val budget = ModelRetryPolicy().maxInputTokens
    val queryId = "query:v1:" + "0" * 64
    val builder = new EvidenceBuilderAgent(
      queryId,
      queryRun,
      graphRun,
      id => world.directory.byId(id).map(_.displayName),
      caps
    )
    var filled = 0
    try
      world.allMetrics.foreach { m =>
        builder.metrics(
          CachedMetrics(
            m,
            graphRun,
            sparkVersion,
            Some(StorageProvenance.TransformVersion),
            Some(s"spark-run:$graphRun")
          )
        )
        filled += 1
      }
    catch case _: EvidenceCapExceeded => ()
    val gaps = Vector.fill(20)("g" * 280)
    val bundle = builder.build(gaps)
    val bytes = bundle.items
      .map(i => upickle.default.write(i).getBytes("UTF-8").length)
      .sum
    assert(bytes <= caps.maxBytes, s"$bytes bytes")
    assert(bytes * 2 > caps.maxBytes, s"the bundle is not near the cap: $bytes")
    val pad = "How many distinct coauthors does Alice Archer have in 2010-2019?"
    val question =
      pad + " " + "x" * (RagRuntime.MaxQuestionChars - pad.length - 1)
    val planner =
      new QueryPlannerAgent(
        new DeterministicStubModel,
        PlannerConfig(),
        ModelRetryPolicy()
      )
    val draft = planner.plan(question, queryId) match
      case PlannerResult.Planned(d, _) => d
      case other                       => fail(s"not planned: $other")
    val input = new AnswerSynthesisAgent(new DeterministicStubModel)
      .input(draft, bundle, gaps)
    val tokens = TokenEstimate.of(ujson.write(input))
    assert(
      tokens <= budget,
      s"a bundle of $bytes bytes (cap ${caps.maxBytes}) needs $tokens tokens; budget $budget"
    )
  }

  test("m3: every default-cap workflow input fits, none abstains on budget") {
    val world = new World()
    val budget = ModelRetryPolicy().maxInputTokens
    val questions = Vector(
      "Show the top 20 researchers by distinct coauthors in 2010-2019.",
      "Show the top 20 researchers by weighted degree in 2000-2009.",
      "Who are the bridge researchers in 2010-2019?",
      "What is the shortest path between Alice Archer and Greta Garcia in 2010-2019?",
      "List the coauthors of Alice Archer in 2010-2019.",
      teamQ,
      "Compare Alice Archer in 2000-2009 and 2010-2019."
    )
    questions.foreach { q =>
      val rec = new RecordingModel(new DeterministicStubModel)
      val r = world.ask(q, rec)
      assertNotEquals(r.abstentionReason, Some("input-over-budget"), q)
      rec.requests.foreach { req =>
        val tokens = TokenEstimate.of(ujson.write(req.input))
        assert(tokens <= budget, s"$q: $tokens tokens")
      }
    }
  }

  test("m3: a refused budget names its own reason and is audited") {
    val world = new World()
    val rec = new RecordingModel(new DeterministicStubModel)
    val r = world.ask(
      jensenQ,
      rec,
      cfg = world.config(retry = ModelRetryPolicy(maxInputTokens = 50))
    )
    assertEquals(r.abstentionReason, Some("input-over-budget"))
    assertEquals(rec.callsOf(synth), 0)
    assert(!r.response.answer.contains("0 attempt"), r.response.answer)
    assertEquals(
      r.audit.payload("abstentionReason"),
      ujson.Str("input-over-budget")
    )
  }
