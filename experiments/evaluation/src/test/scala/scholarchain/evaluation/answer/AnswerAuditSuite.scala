package scholarchain.evaluation.answer

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable
import munit.FunSuite
import scholarchain.core.CollaborationMetric
import scholarchain.evaluation.answer.AuditFixture.*
import scholarchain.evaluation.retrieval.LabelRec
import scholarchain.rag.MetricValues

class AnswerAuditSuite extends FunSuite:
  private val tol = Tolerance()

  private lazy val index: ExportIndex =
    ExportIndex.load(writeExport(Files.createTempDirectory("audit-export-")))

  private def checks(fact: ujson.Value): Vector[Check] =
    AuditRules.evidenceChecks(item("E", fact), index, tol)

  private def problems(fact: ujson.Value): Vector[String] =
    checks(fact).flatMap(_.problem)

  private def audit(a: AnswerRecord): AnswerAuditResult =
    AuditRules.auditAnswer(a, index, tol)

  private def label(
      id: String,
      abstain: Boolean = false,
      codes: Vector[String] = Vector.empty,
      accepted: Vector[String] = Vector.empty,
      items: Vector[(String, Int)] = Vector.empty
  ): LabelRec =
    LabelRec(
      id,
      "ranking",
      "ranking",
      items,
      Vector.empty,
      abstain,
      None,
      accepted,
      codes,
      None,
      None,
      Vector.empty,
      None
    )

  // --- evidence validity against the export ---

  test(
    "a coauthor edge that exists with its endpoints, window and count is valid"
  ) {
    assertEquals(
      checks(coauthorFact()),
      Vector(Check("coauthorEdge", true, None))
    )
  }

  test(
    "a coauthor edge is invalid when the id, endpoints, window or count is wrong"
  ) {
    assertEquals(
      problems(coauthorFact(edge = "coauthor:v1:nope")),
      Vector("edge-missing")
    )
    assertEquals(problems(coauthorFact(b = R3)), Vector("endpoints"))
    assertEquals(problems(coauthorFact(window = "2010-2019")), Vector("window"))
    assertEquals(problems(coauthorFact(joint = 4)), Vector("joint-count"))
    assertEquals(
      problems(coauthorFact(b = R3, joint = 9)),
      Vector("endpoints+joint-count")
    )
  }

  test(
    "a path whose hops are real edges that chain is valid, with supporting works"
  ) {
    val cs = checks(pathFact())
    assertEquals(cs.count(_.category == "pathChain"), 1)
    assertEquals(cs.count(_.category == "pathHop"), 2)
    assertEquals(cs.count(_.category == "supportingWork"), 2)
    assert(cs.forall(_.ok), cs.toString)
  }

  test(
    "a broken chain, a missing edge, a wrong count or a foreign work is caught"
  ) {
    val broken = pathFact(hops =
      Vector(
        hopOf(1, R2, R1, E12, 3, Vector(W1)),
        hopOf(2, R2, R3, E13, 1, Vector(W4))
      )
    )
    assert(checks(broken).exists(c => c.category == "pathChain" && !c.ok))
    assert(checks(broken).exists(c => c.category == "pathHop" && !c.ok))
    val noEdge = pathFact(hops =
      Vector(
        hopOf(1, R2, R1, "coauthor:v1:nope", 3, Vector(W1)),
        hopOf(2, R1, R3, E13, 1, Vector(W4))
      )
    )
    assertEquals(
      checks(noEdge)
        .filter(c => c.category == "pathHop" && !c.ok)
        .flatMap(_.problem),
      Vector("edge-missing")
    )
    assert(
      checks(pathFact(hopCount = 3)).exists(c =>
        c.category == "pathChain" && !c.ok
      )
    )
    val wrongJoint = pathFact(hops =
      Vector(
        hopOf(1, R2, R1, E12, 2, Vector(W1)),
        hopOf(2, R1, R3, E13, 1, Vector(W4))
      )
    )
    assertEquals(
      checks(wrongJoint).filter(!_.ok).flatMap(_.problem),
      Vector("joint-count")
    )
    val foreign = pathFact(hops =
      Vector(
        hopOf(1, R2, R1, E12, 3, Vector(W4)),
        hopOf(2, R1, R3, E13, 1, Vector(W4))
      )
    )
    assertEquals(
      checks(foreign).filter(!_.ok).flatMap(_.problem),
      Vector("not-in-edge-support")
    )
    assert(checks(pathFact(hops = Vector.empty, hopCount = 0)).exists(!_.ok))
  }

  test("a publication must exist with its stored title") {
    assert(checks(publicationFact()).forall(_.ok))
    assertEquals(
      problems(publicationFact(title = "Another title")),
      Vector("title")
    )
    assertEquals(
      problems(publicationFact(work = "work:v1:nope")),
      Vector("work-missing")
    )
  }

  test("a metric fact equals the metrics row within the stated tolerance") {
    assert(checks(metricFact()).forall(_.ok), "1.33 is within 0.01 of 4/3")
    assertEquals(
      problems(metricFact(publications = 4)),
      Vector("differs:publicationCount")
    )
    assertEquals(
      problems(metricFact(average = 1.5)),
      Vector("differs:averageCoauthorsPerPaper")
    )
    assertEquals(
      problems(metricFact(id = "researcher:v1:zzz")),
      Vector("row-missing")
    )
  }

  test("a ranking must match the rows, not increase and exclude mentions") {
    assert(checks(rankingFact()).forall(_.ok))
    assertEquals(
      problems(rankingFact(Vector(R2 -> 5.0, R1 -> 4.0, R3 -> 1.0))),
      Vector("value-differs")
    )
    assertEquals(
      problems(rankingFact(Vector(R1 -> 3.0, R2 -> 5.0))),
      Vector("order")
    )
    assertEquals(
      problems(rankingFact(Vector(M1 -> 5.0))),
      Vector("row-missing")
    )
    assertEquals(problems(rankingFact(Vector.empty)), Vector("empty"))
  }

  test("a ranking in the runtime's kebab-case metric names validates") {
    val byMetric = Vector(
      "publication-count" -> Vector(R2 -> 5.0, R1 -> 3.0, R3 -> 1.0),
      "distinct-coauthor-count" -> Vector(R1 -> 2.0, R2 -> 1.0, R3 -> 1.0),
      "weighted-degree" -> Vector(R1 -> 4.0, R2 -> 3.0, R3 -> 1.0),
      "maximum-coauthors-on-one-paper" -> Vector(R1 -> 2.0)
    )
    byMetric.foreach { (metric, entries) =>
      assertEquals(
        checks(rankingFact(entries, metric)),
        Vector(Check("ranking", true, None)),
        metric
      )
    }
  }

  test("a kebab-case ranking with a wrong value still fails as value-differs") {
    assertEquals(
      problems(rankingFact(Vector(R1 -> 9.0), "weighted-degree")),
      Vector("value-differs")
    )
  }

  test("a ranking row or key missing from the export has its own codes") {
    assertEquals(
      problems(rankingFact(Vector(R1 -> 3.0, "researcher:v1:zzz" -> 1.0))),
      Vector("row-missing")
    )
    assertEquals(
      problems(
        rankingFact(
          Vector(R1 -> 1.33, R2 -> 1.0),
          "average-coauthors-per-paper"
        )
      ),
      Vector("key-missing"),
      "R2 has a row but no averageCoauthorsPerPaper"
    )
    assertEquals(
      problems(rankingFact(Vector(R1 -> 1.0), "bridge-score")),
      Vector("key-missing")
    )
  }

  test("an unmapped metric name is unknown-metric, not value-differs") {
    Vector("publicationCount", "shortest-path-length", "").foreach { metric =>
      assertEquals(
        problems(rankingFact(metric = metric)),
        Vector("unknown-metric"),
        metric
      )
    }
  }

  test("every runtime metric the table names maps to a camelCase export key") {
    assertEquals(AuditRules.RankingMetricKeys.size, 9)
    AuditRules.RankingMetricKeys.foreach { (wire, key) =>
      val parts = wire.split('-').toVector
      assertEquals(parts.head + parts.tail.map(_.capitalize).mkString, key)
    }
  }

  test("RankingMetricKeys equals the runtime's metric table") {
    val runtime = CollaborationMetric.values
      .flatMap(m => MetricValues.field(m).map(m.wire -> _))
      .toMap
    assertEquals(AuditRules.RankingMetricKeys, runtime)
  }

  test("exportNumbers returns the export's ranking values for kebab-case") {
    val numbers = AuditRules.exportNumbers(item("R", rankingFact()), index)
    assertEquals(
      numbers.toSet,
      Set(1.0, 2.0, 3.0, 5.0, 2000.0, 2009.0),
      "ranks, export values and the window years"
    )
    val wrongStated =
      AuditRules.exportNumbers(
        item("R", rankingFact(Vector(R2 -> 9.0, R1 -> 3.0, R3 -> 1.0))),
        index
      )
    assert(
      !wrongStated.contains(9.0),
      "the export value is used, not the stated one"
    )
    assert(wrongStated.contains(5.0))
    val weighted = AuditRules.exportNumbers(
      item("R", rankingFact(Vector(R1 -> 4.0), "weighted-degree")),
      index
    )
    assert(weighted.contains(4.0))
  }

  test(
    "a ranking claim's numbers match the export, and its evidence is valid"
  ) {
    val a = answer(
      claims = Vector(
        claim("c", s"Rank 1 by publications: $R2 with 5.", Vector("K"))
      ),
      evidence = Vector(item("K", rankingFact()))
    )
    val c = audit(a).claims.head
    assertEquals(c.numbersExportMatched, 2, "rank 1 and 5")
    assertEquals(c.numbersEvidenceOnly, 0)
    assertEquals(c.numbersUnmatched, 0)
    assert(!c.citesInvalidEvidence)
  }

  test("a component or community size equals its membership row") {
    assert(checks(groupFact("component")).forall(_.ok))
    assert(checks(groupFact("community")).forall(_.ok))
    assertEquals(problems(groupFact("component", 4)), Vector("size"))
    assertEquals(problems(groupFact("community", 2)), Vector("size"))
  }

  test("an identity names a researcher of the export") {
    assert(checks(identityFact()).forall(_.ok))
    assertEquals(
      problems(identityFact("researcher:v1:zzz")),
      Vector("researcher-missing")
    )
  }

  test(
    "semantic snippets and path-status facts are not checked against the export"
  ) {
    assertEquals(
      AuditRules.evidenceChecks(snippet("S"), index, tol),
      Vector.empty
    )
    assertEquals(
      checks(
        ujson.Obj("kind" -> "path-status", "pathKind" -> "NotPrecomputed")
      ),
      Vector.empty
    )
  }

  // --- claims: structure, identifiers, numbers, contradictions ---

  test("a factual claim that cites nothing or only a snippet is flagged") {
    val a = answer(
      claims = Vector(
        claim("c1", "Ada is a researcher.", Vector.empty),
        claim("c2", "A snippet says so.", Vector("S1")),
        claim("c3", "A definition.", Vector.empty),
        claim("c4", "Supported.", Vector("E1"))
      ),
      evidence = Vector(snippet("S1"), item("E1", identityFact())),
      verification = Some(
        verification(
          Vector(
            ("c1", "identity", "UNSUPPORTED"),
            ("c2", "semantic", "UNSUPPORTED"),
            ("c3", "definition", "VERIFIED"),
            ("c4", "identity", "VERIFIED")
          )
        )
      )
    )
    val cs = audit(a).claims.map(c => c.claimId -> c).toMap
    assert(cs("c1").citesNothing)
    assert(cs("c2").onlySemanticSupport)
    assert(
      !cs("c3").citesNothing && !cs("c3").factual,
      "a definition is not factual"
    )
    assert(!cs("c4").citesNothing && !cs("c4").onlySemanticSupport)
  }

  test(
    "a claim the verifier calls a definition is still audited when it states data"
  ) {
    val a = answer(
      claims = Vector(
        claim(
          "c1",
          "Breadth means the number of distinct coauthors.",
          Vector.empty
        ),
        claim("c2", "The researcher has 7 publications.", Vector.empty),
        claim("c3", s"$R1 wrote with $R2.", Vector.empty),
        claim("c4", "Joint work with a known coauthor.", Vector("E1")),
        claim("c5", "No evidence was found for this.", Vector.empty)
      ),
      evidence = Vector(item("E1", coauthorFact())),
      verification = Some(
        verification(
          Vector(
            ("c1", "definition", "VERIFIED"),
            ("c2", "definition", "VERIFIED"),
            ("c3", "definition", "VERIFIED"),
            ("c4", "definition", "VERIFIED"),
            ("c5", "abstention", "VERIFIED")
          )
        )
      )
    )
    val cs = audit(a).claims.map(c => c.claimId -> c).toMap
    // A bare definition and a bare abstention stay out, and are counted.
    assert(!cs("c1").factual && cs("c1").excludedByVerifierKind)
    assert(!cs("c5").factual && cs("c5").excludedByVerifierKind)
    // A number, an identifier or deterministic evidence makes it factual.
    Vector("c2", "c3", "c4").foreach { id =>
      assert(cs(id).factual && cs(id).reclassifiedFactual, id)
      assert(!cs(id).excludedByVerifierKind, id)
    }
    // The mislabelled claims now count in the structural and number checks.
    assert(cs("c2").citesNothing)
    assert(cs("c3").citesNothing)
    assert(!cs("c4").citesNothing)
    val summary =
      AnswerAudit.summarize(Vector(audit(a)), Vector(a), Map.empty, tol)
    val support = summary("structuralSupport")
    assertEquals(support("claims").num.toInt, 5)
    assertEquals(support("factualClaims").num.toInt, 3)
    assertEquals(support("reclassifiedFactualByAudit").num.toInt, 3)
    assertEquals(support("excludedByVerifierKind").num.toInt, 2)
    assertEquals(support("citingNothing").num.toInt, 2)
    val back = ujson.read(ujson.write(audit(a).claims.head.toJson))
    assertEquals(back("excludedByVerifierKind").bool, true)
  }

  test(
    "an identifier that is not in the export is unknown, and the claim contradicts it"
  ) {
    val a = answer(
      claims = Vector(
        claim("c1", s"See $R1 and $W1 and $E12.", Vector("E1")),
        claim("c2", s"See researcher:v1:ghost999, and $R2.", Vector("E1"))
      ),
      evidence = Vector(item("E1", identityFact()))
    )
    val cs = audit(a).claims.map(c => c.claimId -> c).toMap
    assertEquals(cs("c1").unknownIds, Vector.empty[String])
    assertEquals(cs("c1").idsChecked, 3)
    assert(!cs("c1").contradicted)
    assertEquals(cs("c2").unknownIds, Vector("researcher:v1:ghost999"))
    assert(cs("c2").contradicted)
  }

  test(
    "a number is matched to the export, then to the evidence, else unmatched"
  ) {
    val ev = Vector(item("M", metricFact()), item("G", groupFact("community")))
    def claimOf(text: String, cites: String) =
      audit(
        answer(claims = Vector(claim("c", text, Vector(cites))), evidence = ev)
      ).claims.head
    val ok =
      claimOf("The researcher has 3 publications from 2000 to 2009.", "M")
    assertEquals(ok.numbersExportMatched, 3, "3, 2000 and 2009")
    assertEquals(ok.numbersUnmatched, 0)
    assert(!ok.contradicted)
    val rounded = claimOf("An average of 1.33 coauthors per paper.", "M")
    assertEquals(rounded.numbersExportMatched, 1)
    val wrong = claimOf("The researcher has 7 publications.", "M")
    assertEquals(wrong.numbersUnmatched, 1)
    assert(wrong.checkableCitation)
    assert(wrong.contradicted)
    // 7 cross edges is in the evidence but not in any export table the audit
    // reads: evidence-only, never a contradiction.
    val soft = claimOf("The community has 7 edges to other communities.", "G")
    assertEquals(soft.numbersEvidenceOnly, 1)
    assertEquals(soft.numbersUnmatched, 0)
    assert(!soft.contradicted)
  }

  test("ids and ORCIDs are not read as numbers") {
    val c = audit(
      answer(
        claims = Vector(
          claim(
            "c",
            s"$R1 (0000-0002-1825-0097) has 3 publications.",
            Vector("M")
          )
        ),
        evidence = Vector(item("M", metricFact()))
      )
    ).claims.head
    assertEquals(
      c.numbersExportMatched + c.numbersEvidenceOnly + c.numbersUnmatched,
      1
    )
    assertEquals(c.numbersUnmatched, 0)
  }

  test(
    "an unmatched number is not a contradiction when nothing citable can check it"
  ) {
    val c = audit(
      answer(
        claims = Vector(claim("c", "There are 99 of them.", Vector("I"))),
        evidence = Vector(item("I", identityFact()))
      )
    ).claims.head
    assertEquals(c.numbersUnmatched, 1)
    assert(!c.checkableCitation)
    assert(!c.contradicted)
  }

  test(
    "a claim citing invalid evidence is flagged apart from the contradiction rule"
  ) {
    val a = answer(
      claims = Vector(claim("c", "Joint work.", Vector("E"))),
      evidence = Vector(item("E", coauthorFact(joint = 9)))
    )
    val c = audit(a).claims.head
    assert(c.citesInvalidEvidence)
    assert(!c.contradicted)
  }

  test("the verifier's verdicts are kept apart as self-reported") {
    val a = answer(
      claims = Vector(
        claim("c1", "The researcher has 7 publications.", Vector("M")),
        claim("c2", "The researcher has 3 publications.", Vector("M"))
      ),
      evidence = Vector(item("M", metricFact())),
      verification = Some(
        verification(
          Vector(
            ("c1", "metric", "VERIFIED"),
            ("c2", "metric", "VERIFIED"),
            ("c3", "metric", "CONFLICTING")
          ),
          "PARTIALLY_SUPPORTED"
        )
      )
    )
    val summary =
      AnswerAudit.summarize(Vector(audit(a)), Vector(a), Map.empty, tol)
    val self = summary("verifierSelfReported")
    assertEquals(self("verdicts")("VERIFIED").num.toInt, 2)
    assertEquals(self("verdicts")("CONFLICTING").num.toInt, 1)
    assertEquals(self("claimsVerifiedButAuditContradicted").num.toInt, 1)
    assert(self("note").str.contains("not independent"))
    assertEquals(summary("contradictions")("claimsContradicted").num.toInt, 1)
    assertEquals(summary("contradictions")("contradictionRate").num, 0.5)
  }

  test("evidence validity is counted per category over answered questions") {
    val a = answer(
      evidence = Vector(
        item("E1", coauthorFact()),
        item("E2", coauthorFact(joint = 9)),
        item("P", pathFact()),
        item("U", publicationFact(title = "Wrong"))
      )
    )
    val v = AnswerAudit.summarize(Vector(audit(a)), Vector(a), Map.empty, tol)(
      "evidenceValidity"
    )
    assertEquals(v("coauthorEdges")("checked").num.toInt, 2)
    assertEquals(v("coauthorEdges")("valid").num.toInt, 1)
    assertEquals(v("pathHops")("valid").num.toInt, 2)
    assertEquals(v("supportingWorks")("checked").num.toInt, 2)
    assertEquals(v("publications")("valid").num.toInt, 0)
    assertEquals(v("metrics")("validRate"), ujson.Null)
  }

  test("an abstained answer is not audited for claims or evidence") {
    val a = answer(
      abstained = true,
      reason = Some("x"),
      evidence = Vector(item("E", coauthorFact(joint = 9)))
    )
    val s = AnswerAudit.summarize(Vector(audit(a)), Vector(a), Map.empty, tol)
    assertEquals(s("answers")("answered").num.toInt, 0)
    assertEquals(s("evidenceValidity")("coauthorEdges")("checked").num.toInt, 0)
  }

  // --- abstention, variability ---

  test(
    "abstention recall, reason-code match, false abstention and accepted outcomes"
  ) {
    val labels = Map(
      "U1" -> label(
        "U1",
        abstain = true,
        codes = Vector("identity-unresolved")
      ),
      "U2" -> label("U2", abstain = true, codes = Vector("planner-refused")),
      "U3" -> label("U3", abstain = true, codes = Vector("c")),
      "A1" -> label("A1", items = Vector("x" -> 1)),
      "A2" -> label("A2", items = Vector("y" -> 1)),
      "P1" -> label(
        "P1",
        accepted = Vector("path-status:NotPrecomputed", "abstain")
      ),
      "P2" -> label(
        "P2",
        accepted = Vector("path-status:NotPrecomputed", "abstain")
      )
    )
    val status = EvidenceRec(
      "S",
      "GraphPath",
      Some("path-status"),
      None,
      Vector.empty,
      1.0,
      Some(
        ujson.write(
          ujson.Obj("kind" -> "path-status", "pathKind" -> "NotPrecomputed")
        )
      ),
      None
    )
    val records = Vector(
      answer("U1", abstained = true, reason = Some("identity-unresolved")),
      answer("U2", abstained = true, reason = Some("synthesis-failed")),
      answer("U3"),
      answer("A1", abstained = true, reason = Some("storage-error")),
      answer("A2"),
      answer("P1", evidence = Vector(status)),
      answer("P2")
    )
    val block =
      AnswerAudit.summarize(records.map(audit), records, labels, tol)(
        "abstention"
      )
    assertEquals(block("unanswerable").num.toInt, 3)
    assertEqualsDouble(block("abstentionRecall").num, 2.0 / 3.0, 1e-9)
    assertEquals(block("unanswerableAnswered").num.toInt, 1)
    assertEqualsDouble(block("reasonCodeMatchAmongAbstained").num, 0.5, 1e-9)
    assertEquals(block("answerable").num.toInt, 2)
    assertEqualsDouble(block("falseAbstentionRate").num, 0.5, 1e-9)
    assertEquals(block("acceptedOutcomeRecords").num.toInt, 2)
    assertEqualsDouble(block("acceptedOutcomeCorrect").num, 0.5, 1e-9)
  }

  test("repeat variability compares the repeat answer with the primary one") {
    val all = Vector(
      answer("Q1", "primary", hash = "a"),
      answer("Q1", "repeat", hash = "a"),
      answer("Q2", "primary", hash = "b"),
      answer("Q2", "repeat", hash = "c"),
      answer("Q3", "primary", hash = "d")
    )
    val s = AnswerAudit.summarize(Vector.empty, all, Map.empty, tol)(
      "repeatVariability"
    )
    assertEquals(s("questionsRepeated").num.toInt, 2)
    assertEquals(s("sameAnswerHash").num, 0.5)
    assertEquals(s("sameAbstention").num, 1.0)
  }

  // --- the run ---

  private def tempDir(): Path = Files.createTempDirectory("audit-run-")

  test("a run audits usable primary answers only and writes no answer text") {
    val dir = tempDir()
    val marker = "UNIQUE-CLAIM-TEXT-MARKER"
    val records = Vector(
      answer(
        "Q1",
        claims =
          Vector(claim("c1", s"$marker has 3 publications.", Vector("M"))),
        evidence = Vector(item("M", metricFact())),
        text = "UNIQUE-ANSWER-TEXT-MARKER"
      ),
      answer("Q2", costStopped = true),
      answer("Q3", run = "repeat")
    )
    val answers = dir.resolve("answers.jsonl")
    Files.writeString(
      answers,
      records.map(r => ujson.write(r.toJson)).mkString("", "\n", "\n"),
      UTF_8
    )
    val labels = dir.resolve("labels.jsonl")
    Files.writeString(labels, "", UTF_8)
    val report =
      AnswerAudit.run(
        answers,
        labels,
        index,
        dir.resolve("out"),
        "audit-1",
        tol
      )
    assertEquals(report.audited, 1)
    Vector(
      "audit-claims.jsonl",
      "audit-answers.jsonl",
      "audit-summary.json",
      "run-manifest.json",
      "SHA256SUMS"
    ).foreach(n => assert(Files.exists(report.runDir.resolve(n)), n))
    Vector(
      "audit-claims.jsonl",
      "audit-answers.jsonl",
      "audit-summary.json",
      "run-manifest.json"
    )
      .foreach { n =>
        val text = Files.readString(report.runDir.resolve(n))
        assert(!text.contains(marker), s"$n holds claim text")
        assert(
          !text.contains("UNIQUE-ANSWER-TEXT-MARKER"),
          s"$n holds answer text"
        )
      }
    val summary =
      ujson.read(Files.readString(report.runDir.resolve("audit-summary.json")))
    assertEquals(summary("tolerance")("abs").num, 0.01)
    assertEquals(summary("structuralSupport")("factualClaims").num.toInt, 1)
    intercept[IllegalStateException](
      AnswerAudit.run(
        answers,
        labels,
        index,
        dir.resolve("out"),
        "audit-1",
        tol
      )
    )
  }

  test("the command line refuses an unknown option") {
    val lines = mutable.ArrayBuffer.empty[String]
    assertEquals(AnswerAudit.execute(Seq("--nope", "x"), lines += _), 2)
    assertEquals(AnswerAudit.execute(Seq("--answers", "a"), lines += _), 2)
  }

  test("tolerance: absolute for small values, relative for large ones") {
    val t = Tolerance()
    assert(t.near(1.33, 4.0 / 3.0))
    assert(!t.near(1.35, 4.0 / 3.0))
    assert(t.near(1000.5, 1000.0), "0.5 is within 0.01 + 1")
    assert(!t.near(1002.0, 1000.0))
  }
