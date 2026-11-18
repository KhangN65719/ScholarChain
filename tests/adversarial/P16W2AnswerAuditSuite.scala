package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.evaluation.answer.{
  AnswerAudit,
  AnswerAuditResult,
  AnswerRecord,
  AuditRules,
  ClaimRec,
  EvidenceRec,
  ExportIndex,
  Tolerance
}
import scholarchain.evaluation.retrieval.LabelRec
import P16W2Support.*

/** Oracle tests of the AnswerAudit rules on hand-built answers over a
  * hand-built export. The world is chosen here and is not the one of the
  * module's own tests; every expectation is worked out from the export rows
  * below.
  *
  * Window 2010-2014. Researchers alpha, beta, gamma, delta and a mention. Edge
  * eab joins alpha and beta (4 joint works w1 to w4), ebc joins beta and gamma
  * (2 joint works w5 and w6), ecd joins gamma and delta (1 joint work w7).
  */
class P16W2AnswerAuditSuite extends FunSuite:
  private val win = "2010-2014"
  private val alpha = "researcher:v1:alpha"
  private val beta = "researcher:v1:beta"
  private val gamma = "researcher:v1:gamma"
  private val delta = "researcher:v1:delta"
  private val ghost = "mention:v1:ghost"
  private val eab = "coauthor:v1:eab"
  private val ebc = "coauthor:v1:ebc"
  private val ecd = "coauthor:v1:ecd"
  private def w(n: Int) = s"work:v1:w$n"

  private def rows(dir: Path, table: String, rs: Seq[ujson.Value]): Unit =
    val t = dir.resolve(table)
    Files.createDirectories(t)
    Files.writeString(
      t.resolve("part-00000.jsonl"),
      rs.map(ujson.write(_)).mkString("", "\n", "\n"),
      UTF_8
    )

  private def metricRow(
      id: String,
      pubs: Double,
      distinct: Double,
      degree: Double,
      average: Double
  ): ujson.Value =
    ujson.Obj(
      "researcherId" -> id,
      "window" -> win,
      "windowStart" -> 2010,
      "windowEnd" -> 2014,
      "publicationCount" -> pubs,
      "distinctCoauthorCount" -> distinct,
      "weightedDegree" -> degree,
      "averageCoauthorsPerPaper" -> average
    )

  private lazy val exportDir: Path =
    val dir = tempDir("p16w2-export-")
    Files.writeString(
      dir.resolve("export-manifest.json"),
      """{"sourceRunId":"run:v1:p16w2"}""",
      UTF_8
    )
    rows(
      dir,
      "nodes-researcher",
      Vector(alpha, beta, gamma, delta, ghost).map(id =>
        ujson.Obj(
          "nodeId" -> id,
          "nodeKind" -> (if id == ghost then "unresolved-mention"
                         else "canonical"),
          "displayName" -> s"Name $id"
        )
      )
    )
    rows(
      dir,
      "researcher-metrics",
      Vector(
        metricRow(alpha, 10, 1, 4, 2.5),
        metricRow(beta, 7, 2, 6, 1.75),
        metricRow(gamma, 1000, 2, 3, 3.0),
        metricRow(delta, 4, 1, 1, 1.0),
        metricRow(ghost, 9, 1, 1, 1.0)
      )
    )
    def edge(id: String, a: String, b: String, joint: Int) = ujson.Obj(
      "edgeId" -> id,
      "researcherA" -> a,
      "researcherB" -> b,
      "window" -> win,
      "jointPublicationCount" -> joint
    )
    rows(
      dir,
      "edges-coauthored",
      Vector(
        edge(eab, alpha, beta, 4),
        edge(ebc, beta, gamma, 2),
        edge(ecd, gamma, delta, 1)
      )
    )
    def support(e: String, ws: Seq[Int]) =
      ws.map(n =>
        ujson.Obj("edgeId" -> e, "canonicalWorkId" -> w(n)): ujson.Value
      )
    rows(
      dir,
      "edge-support",
      support(eab, 1 to 4) ++ support(ebc, 5 to 6) ++ support(ecd, 7 to 7)
    )
    rows(
      dir,
      "nodes-work",
      (1 to 7).map(n =>
        ujson.Obj("canonicalWorkId" -> w(n), "title" -> s"Title of work $n")
      )
    )
    rows(
      dir,
      "community-membership",
      Vector(
        ujson.Obj(
          "researcherId" -> alpha,
          "window" -> win,
          "communityId" -> "c",
          "communitySize" -> 2
        )
      )
    )
    rows(
      dir,
      "component-membership",
      Vector(
        ujson.Obj(
          "researcherId" -> alpha,
          "window" -> win,
          "componentId" -> "k",
          "componentSize" -> 4
        )
      )
    )
    dir

  private lazy val ix: ExportIndex = ExportIndex.load(exportDir)
  private val tol = Tolerance()

  // ---- fact builders -----------------------------------------------------------

  private def fact(kind: String, fields: (String, ujson.Value)*): ujson.Value =
    ujson.Obj.from(("kind" -> ujson.Str(kind)) +: fields)

  private val windowFields: Seq[(String, ujson.Value)] = Seq(
    "window" -> ujson.Str(win),
    "windowStart" -> ujson.Num(2010),
    "windowEnd" -> ujson.Num(2014)
  )

  private def coauthorFact(
      edge: String = eab,
      a: String = alpha,
      b: String = beta,
      joint: Double = 4,
      window: String = win
  ): ujson.Value =
    fact(
      "coauthor",
      Seq(
        "researcherId" -> ujson.Str(a),
        "coauthorId" -> ujson.Str(b),
        "edgeId" -> ujson.Str(edge),
        "window" -> ujson.Str(window),
        "jointPublicationCount" -> ujson.Num(joint)
      )*
    )

  private def hop(
      i: Int,
      from: String,
      to: String,
      edge: String,
      joint: Double,
      works: Seq[String]
  ): ujson.Value =
    ujson.Obj(
      "hopIndex" -> i,
      "from" -> from,
      "to" -> to,
      "edgeId" -> edge,
      "jointPublicationCount" -> joint,
      "supportingWorkIds" -> ujson.Arr.from(works.map(ujson.Str(_)))
    )

  private val goodHops = Vector(
    hop(1, alpha, beta, eab, 4, Seq(w(1))),
    hop(2, beta, gamma, ebc, 2, Seq(w(5)))
  )

  private def pathFact(
      hops: Vector[ujson.Value] = goodHops,
      source: String = alpha,
      target: String = gamma,
      hopCount: Double = 2,
      window: String = win
  ): ujson.Value =
    fact(
      "path",
      Seq(
        "source" -> ujson.Str(source),
        "target" -> ujson.Str(target),
        "hopCount" -> ujson.Num(hopCount),
        "hops" -> ujson.Arr.from(hops),
        "window" -> ujson.Str(window)
      )*
    )

  private def publicationFact(work: String, title: String): ujson.Value =
    fact(
      "publication",
      "workId" -> ujson.Str(work),
      "title" -> ujson.Str(title)
    )

  private def metricFact(
      id: String = alpha,
      pubs: Double = 10,
      average: Double = 2.5
  ): ujson.Value =
    fact(
      "metric",
      Seq(
        "researcherId" -> ujson.Str(id),
        "publicationCount" -> ujson.Num(pubs),
        "averageCoauthorsPerPaper" -> ujson.Num(average)
      ) ++ windowFields*
    )

  private def rankingFact(
      entries: Seq[(String, Double)],
      metric: String = "publicationCount"
  ): ujson.Value =
    fact(
      "ranking",
      Seq(
        "metric" -> ujson.Str(metric),
        "entries" -> ujson.Arr.from(entries.zipWithIndex.map {
          case ((id, v), i) =>
            ujson.Obj("rank" -> (i + 1), "researcherId" -> id, "value" -> v)
        })
      ) ++ windowFields*
    )

  private def groupFact(kind: String, size: Double, id: String = alpha) =
    fact(
      kind,
      Seq(
        "researcherId" -> ujson.Str(id),
        (if kind == "component" then "componentSize" else "communitySize") ->
          ujson.Num(size),
        "crossEdges" -> ujson.Num(7)
      ) ++ windowFields*
    )

  private def item(id: String, f: ujson.Value): EvidenceRec =
    EvidenceRec(
      id,
      "Metric",
      scholarchain.rag.Facts.kind(f),
      None,
      Vector.empty,
      1.0,
      Some(ujson.write(f)),
      None
    )

  private def snippet(id: String): EvidenceRec =
    EvidenceRec(
      id,
      "TextChunk",
      None,
      None,
      Vector.empty,
      0.1,
      Some("some text"),
      None
    )

  private def claim(id: String, text: String, ev: String*): ClaimRec =
    ClaimRec(id, text, ev.toVector, "Verified", None)

  private def report(claims: (String, String, String)*): ujson.Value =
    ujson.Obj(
      "schema" -> "scholarchain.rag.verifier-report/1",
      "overall" -> "VERIFIED",
      "materialClaims" -> claims.size,
      "claims" -> ujson.Arr.from(claims.map { (id, kind, verdict) =>
        ujson.Obj(
          "claimId" -> id,
          "kind" -> kind,
          "verdict" -> verdict,
          "reasons" -> ujson.Arr()
        ): ujson.Value
      }),
      "changes" -> ujson.Arr()
    )

  private def answer(
      qid: String,
      claims: Vector[ClaimRec] = Vector.empty,
      evidence: Vector[EvidenceRec] = Vector.empty,
      verification: Option[ujson.Value] = None,
      abstained: Boolean = false,
      reason: Option[String] = None,
      run: String = "primary",
      costStopped: Boolean = false,
      text: String = "An answer."
  ): AnswerRecord =
    AnswerRecord(
      qid,
      run,
      "fused",
      s"s-$run",
      1,
      s"Question $qid?",
      text,
      abstained,
      reason,
      claims,
      evidence,
      verification,
      Vector.empty,
      1,
      10,
      10,
      tokensEstimated = false,
      5L,
      Vector.empty,
      s"hash-$qid",
      None,
      costStopped,
      None
    )

  private def audit(a: AnswerRecord): AnswerAuditResult =
    AuditRules.auditAnswer(a, ix, tol)

  /** The checks of the single evidence item `E` of an answer holding `f`. */
  private def checksOf(f: ujson.Value) =
    audit(answer("Q", evidence = Vector(item("E", f)))).items.head.checks

  private def problems(f: ujson.Value): Vector[String] =
    checksOf(f).flatMap(_.problem)

  private def categories(f: ujson.Value): Vector[String] =
    checksOf(f).map(_.category)

  // ---- coauthor edge ----------------------------------------------------------

  test("a coauthor fact that equals the export row is valid") {
    val r = audit(answer("Q", evidence = Vector(item("E", coauthorFact()))))
    assert(r.items.head.valid)
    assertEquals(r.items.head.checks.map(_.category), Vector("coauthorEdge"))
  }

  test("the endpoints of a coauthor edge are unordered") {
    assert(problems(coauthorFact(a = beta, b = alpha)).isEmpty)
  }

  test("a coauthor fact naming an edge the export lacks is edge-missing") {
    assertEquals(
      problems(coauthorFact(edge = "coauthor:v1:nope")),
      Vector("edge-missing")
    )
  }

  test("a coauthor fact with a wrong endpoint is an endpoints problem") {
    assertEquals(problems(coauthorFact(b = delta)), Vector("endpoints"))
  }

  test("a coauthor fact in the wrong window is a window problem") {
    assertEquals(problems(coauthorFact(window = "2015-2019")), Vector("window"))
  }

  test("a coauthor fact with the wrong joint count is a joint-count problem") {
    assertEquals(problems(coauthorFact(joint = 5)), Vector("joint-count"))
  }

  test("several wrong parts of a coauthor fact are all reported") {
    assertEquals(
      problems(coauthorFact(b = delta, joint = 9)).sorted,
      Vector("endpoints+joint-count")
    )
  }

  test("a coauthor joint count inside the tolerance is accepted") {
    assert(problems(coauthorFact(joint = 4.005)).isEmpty)
    assertEquals(problems(coauthorFact(joint = 4.5)), Vector("joint-count"))
  }

  // ---- path hops ----------------------------------------------------------------

  test("a path whose hops are real edges, chained, is valid in every check") {
    val cs = checksOf(pathFact())
    assert(cs.forall(_.ok), cs.toString)
    assertEquals(
      cs.map(_.category).sorted,
      Vector(
        "pathChain",
        "pathHop",
        "pathHop",
        "supportingWork",
        "supportingWork"
      )
    )
  }

  test("a hop on an edge the export lacks is edge-missing") {
    val hops = Vector(hop(1, alpha, beta, "coauthor:v1:nope", 4, Seq(w(1))))
    val cs = checksOf(pathFact(hops, target = beta, hopCount = 1))
    assert(
      cs.exists(c =>
        c.category == "pathHop" && c.problem.contains("edge-missing")
      )
    )
  }

  test("a hop whose endpoints are not the edge's is an endpoints problem") {
    val hops = Vector(
      hop(1, alpha, delta, eab, 4, Seq(w(1))),
      hop(2, delta, gamma, ebc, 2, Seq(w(5)))
    )
    val cs = checksOf(pathFact(hops))
    assert(cs.count(c => !c.ok && c.category == "pathHop") >= 1)
    assert(cs.flatMap(_.problem).exists(_.contains("endpoints")))
  }

  test("a hop with the wrong joint count is flagged") {
    val hops = Vector(
      hop(1, alpha, beta, eab, 3, Seq(w(1))),
      hop(2, beta, gamma, ebc, 2, Seq(w(5)))
    )
    assert(problems(pathFact(hops)).exists(_.contains("joint-count")))
  }

  test("a path in the wrong window is flagged on every hop") {
    val cs = checksOf(pathFact(window = "2015-2019"))
    assertEquals(cs.count(c => c.category == "pathHop" && !c.ok), 2)
  }

  test("hops that do not chain are a broken pathChain") {
    val hops = Vector(
      hop(1, alpha, beta, eab, 4, Seq(w(1))),
      hop(2, gamma, delta, ecd, 1, Seq(w(7)))
    )
    val cs = checksOf(pathFact(hops, target = delta))
    assert(
      cs.exists(c => c.category == "pathChain" && c.problem.contains("broken"))
    )
  }

  test("a path that does not start at its source is a broken pathChain") {
    assert(problems(pathFact(source = beta)).contains("broken"))
  }

  test("a path that does not end at its target is a broken pathChain") {
    assert(problems(pathFact(target = delta)).contains("broken"))
  }

  test("a wrong hop count is a broken pathChain") {
    assert(problems(pathFact(hopCount = 3)).contains("broken"))
  }

  test("an empty hop list is a broken pathChain, not valid") {
    val r = audit(
      answer(
        "Q",
        evidence = Vector(item("E", pathFact(Vector.empty, hopCount = 0)))
      )
    )
    assert(r.items.head.invalid)
  }

  test("a supporting work of another edge is not-in-edge-support") {
    val hops = Vector(
      hop(1, alpha, beta, eab, 4, Seq(w(7))),
      hop(2, beta, gamma, ebc, 2, Seq(w(5)))
    )
    val cs = checksOf(pathFact(hops))
    assertEquals(
      cs.filter(_.category == "supportingWork").flatMap(_.problem),
      Vector("not-in-edge-support")
    )
  }

  test("a supporting work that does not exist at all is not supported") {
    val hops = Vector(
      hop(1, alpha, beta, eab, 4, Seq("work:v1:ghostwork")),
      hop(2, beta, gamma, ebc, 2, Seq(w(5)))
    )
    assert(problems(pathFact(hops)).contains("not-in-edge-support"))
  }

  // ---- supporting publication --------------------------------------------------

  test("a publication fact with the export title is valid") {
    assert(problems(publicationFact(w(3), "Title of work 3")).isEmpty)
    assertEquals(
      categories(publicationFact(w(3), "Title of work 3")),
      Vector("publication")
    )
  }

  test("a publication id outside the export is work-missing") {
    assertEquals(
      problems(publicationFact("work:v1:w99", "Title of work 99")),
      Vector("work-missing")
    )
  }

  test("a publication with another work's title is a title problem") {
    assertEquals(
      problems(publicationFact(w(3), "Title of work 4")),
      Vector("title")
    )
  }

  test("a publication title is compared after trimming outer whitespace") {
    assert(problems(publicationFact(w(3), "  Title of work 3 ")).isEmpty)
  }

  // ---- metric, ranking, group, identity ----------------------------------------

  test("a metric fact equal to the export row is valid") {
    assert(problems(metricFact()).isEmpty)
  }

  test("a metric fact with a wrong count names the differing metric") {
    assertEquals(
      problems(metricFact(pubs = 11)),
      Vector("differs:publicationCount")
    )
  }

  test("a metric average within the absolute tolerance is accepted") {
    assert(problems(metricFact(average = 2.504)).isEmpty)
    assertEquals(
      problems(metricFact(average = 2.52)),
      Vector("differs:averageCoauthorsPerPaper")
    )
  }

  test("the tolerance has a relative part: 1000 accepts 1000.9 not 1002") {
    assert(
      problems(metricFact(id = gamma, pubs = 1000.9, average = 3.0)).isEmpty
    )
    assertEquals(
      problems(metricFact(id = gamma, pubs = 1002, average = 3.0)),
      Vector("differs:publicationCount")
    )
  }

  test("a metric fact for a window the export lacks is row-missing") {
    val f = fact(
      "metric",
      "researcherId" -> ujson.Str(alpha),
      "publicationCount" -> ujson.Num(10),
      "window" -> ujson.Str("1990-1994")
    )
    assertEquals(problems(f), Vector("row-missing"))
  }

  test("Tolerance.near follows abs + rel * |expected|") {
    val t = Tolerance(abs = 0.5, rel = 0.1)
    assert(t.near(10.5, 10.0))
    assert(t.near(11.4, 10.0))
    assert(!t.near(11.6, 10.0))
    assert(Tolerance(0.0, 0.0).near(3.0, 3.0))
    assert(!Tolerance(0.0, 0.0).near(3.0000001, 3.0))
  }

  test("a ranking equal to the export, in descending order, is valid") {
    assert(
      problems(rankingFact(Seq(alpha -> 10, beta -> 7, delta -> 4))).isEmpty
    )
  }

  test("a ranking with a wrong value is value-differs") {
    assertEquals(
      problems(rankingFact(Seq(alpha -> 12, beta -> 7))),
      Vector("value-differs")
    )
  }

  test("a ranking in ascending order is an order problem") {
    assertEquals(
      problems(rankingFact(Seq(delta -> 4, beta -> 7, alpha -> 10))),
      Vector("order")
    )
  }

  test("a ranking that lists an unresolved mention is mention-ranked") {
    assertEquals(
      problems(rankingFact(Seq(alpha -> 10, ghost -> 9, beta -> 7))),
      Vector("mention-ranked")
    )
  }

  test("an empty ranking is not valid") {
    assertEquals(problems(rankingFact(Seq.empty)), Vector("empty"))
  }

  test("a component and a community size are checked against the export") {
    assert(problems(groupFact("component", 4)).isEmpty)
    assert(problems(groupFact("community", 2)).isEmpty)
    assertEquals(problems(groupFact("component", 5)), Vector("size"))
    assertEquals(problems(groupFact("community", 3)), Vector("size"))
    assertEquals(
      problems(groupFact("component", 4, beta)),
      Vector("row-missing")
    )
  }

  test("an identity fact needs a researcher the export holds") {
    assert(
      problems(fact("identity", "researcherId" -> ujson.Str(alpha))).isEmpty
    )
    assertEquals(
      problems(
        fact("identity", "researcherId" -> ujson.Str("researcher:v1:nobody"))
      ),
      Vector("researcher-missing")
    )
  }

  test("a semantic snippet is not checkable and never a valid fact") {
    val r = audit(answer("Q", evidence = Vector(snippet("S"))))
    assert(!r.items.head.checkable)
    assert(!r.items.head.valid)
    assert(!r.items.head.invalid)
  }

  // ---- structural support and identifiers ---------------------------------------

  private def only(a: AnswerRecord) = audit(a).claims.head

  test("a factual claim that cites nothing is citesNothing") {
    val c = only(
      answer("Q", claims = Vector(claim("C1", "Alpha has 10 publications.")))
    )
    assert(c.factual)
    assert(c.citesNothing)
    assert(!c.onlySemanticSupport)
  }

  test(
    "a factual claim that cites only a text snippet is onlySemanticSupport"
  ) {
    val c = only(
      answer(
        "Q",
        claims = Vector(claim("C1", "Alpha has 10 publications.", "S")),
        evidence = Vector(snippet("S"))
      )
    )
    assert(c.onlySemanticSupport)
    assert(!c.citesNothing)
  }

  test("a claim that cites a deterministic fact is structurally supported") {
    val c = only(
      answer(
        "Q",
        claims = Vector(claim("C1", "Alpha has 10 publications.", "E")),
        evidence = Vector(item("E", metricFact()))
      )
    )
    assert(!c.citesNothing && !c.onlySemanticSupport)
    assert(!c.citesInvalidEvidence)
  }

  test(
    "a claim citing an evidence item that is invalid is citesInvalidEvidence"
  ) {
    val c = only(
      answer(
        "Q",
        claims = Vector(claim("C1", "Alpha has 11 publications.", "E")),
        evidence = Vector(item("E", metricFact(pubs = 11)))
      )
    )
    assert(c.citesInvalidEvidence)
  }

  test("an identifier the export does not hold makes the claim contradicted") {
    val c = only(
      answer(
        "Q",
        claims = Vector(
          claim("C1", s"$alpha coauthored with researcher:v1:nobody.", "E")
        ),
        evidence = Vector(item("E", coauthorFact()))
      )
    )
    assertEquals(c.unknownIds, Vector("researcher:v1:nobody"))
    assert(c.contradicted)
    assertEquals(c.idsChecked, 2)
  }

  test(
    "known identifiers of every kind pass, and trailing punctuation is cut"
  ) {
    val text = s"$alpha, $beta; ($eab) and ${w(2)}."
    assertEquals(AuditRules.idsIn(text), Vector(alpha, beta, eab, w(2)))
    val c = only(
      answer(
        "Q",
        claims = Vector(claim("C1", text, "E")),
        evidence = Vector(item("E", coauthorFact()))
      )
    )
    assertEquals(c.unknownIds, Vector.empty)
    assert(!c.contradicted)
  }

  test("a mention id the export lacks is unknown, one it holds is known") {
    val known = only(answer("Q", claims = Vector(claim("C1", s"See $ghost."))))
    assertEquals(known.unknownIds, Vector.empty)
    val unknown =
      only(answer("Q", claims = Vector(claim("C1", "See mention:v1:phantom."))))
    assertEquals(unknown.unknownIds, Vector("mention:v1:phantom"))
  }

  // ---- numeric accuracy ----------------------------------------------------------

  private def numeric(text: String, f: ujson.Value) =
    only(
      answer(
        "Q",
        claims = Vector(claim("C1", text, "E")),
        evidence = Vector(item("E", f))
      )
    )

  test("a stated number equal to the export value is export-matched") {
    val c = numeric("Alpha has 10 publications.", metricFact())
    assertEquals(c.numbersExportMatched, 1)
    assertEquals(c.numbersUnmatched, 0)
    assert(!c.contradicted)
  }

  test("a stated number that equals no value is unmatched and contradicts") {
    val c = numeric("Alpha has 11 publications.", metricFact())
    assertEquals(c.numbersUnmatched, 1)
    assert(c.checkableCitation)
    assert(c.contradicted)
  }

  test("a number within the stated tolerance of the export is matched") {
    val c = numeric("Alpha averages 2.50 coauthors per paper.", metricFact())
    assertEquals(c.numbersExportMatched, 1)
    val off = numeric("Alpha averages 2.6 coauthors per paper.", metricFact())
    assertEquals(off.numbersUnmatched, 1)
  }

  test("window years and ranks are structural numbers, not claims") {
    val c = numeric(
      "Between 2010 and 2014 alpha ranks 1.",
      rankingFact(Seq(alpha -> 10, beta -> 7))
    )
    assertEquals(c.numbersUnmatched, 0)
    assert(!c.contradicted)
  }

  test("a number only in the evidence is evidenceOnly, not a contradiction") {
    // crossEdges 7 is a figure of the evidence the export tables do not cover.
    val c =
      numeric("The community has 7 cross edges.", groupFact("community", 2))
    assertEquals(c.numbersEvidenceOnly, 1)
    assertEquals(c.numbersUnmatched, 0)
    assert(!c.contradicted)
  }

  test(
    "a wrong number cited to evidence the export cannot check is not a contradiction"
  ) {
    val c = only(
      answer(
        "Q",
        claims = Vector(claim("C1", "Alpha has 99 items.", "E")),
        evidence = Vector(
          item("E", fact("identity", "researcherId" -> ujson.Str(alpha)))
        )
      )
    )
    assertEquals(c.numbersUnmatched, 1)
    assert(!c.checkableCitation)
    assert(!c.contradicted)
  }

  test("digits inside an identifier or an ORCID are not read as numbers") {
    val nums = AuditRules.numbersIn(
      "researcher:v1:orcid:0000-0002-1825-0097 is work:v1:w12 with 3 papers."
    )
    assertEquals(nums, Vector(3.0))
    assertEquals(
      AuditRules.numbersIn("ORCID 0000-0002-1825-009X, 4.5 average"),
      Vector(4.5)
    )
  }

  // ---- the verifier mislabels a claim (review m2) --------------------------------

  test("a factual claim the verifier calls a definition is still audited") {
    val a = answer(
      "Q",
      claims = Vector(claim("C1", "Alpha has 11 publications.", "E")),
      evidence = Vector(item("E", metricFact())),
      verification = Some(report(("C1", "definition", "VERIFIED")))
    )
    val c = only(a)
    assertEquals(c.kind, Some("definition"))
    assert(c.factual, "the audit classifies on the claim, not on the verifier")
    assert(c.reclassifiedFactual)
    assert(!c.excludedByVerifierKind)
    assert(c.contradicted, "the wrong number must not hide behind the label")
  }

  test(
    "a claim the verifier calls an abstention but that states an id is audited"
  ) {
    val a = answer(
      "Q",
      claims = Vector(claim("C1", "No data on researcher:v1:nobody.")),
      verification = Some(report(("C1", "abstention", "VERIFIED")))
    )
    val c = only(a)
    assert(c.factual && c.reclassifiedFactual)
    assert(c.contradicted)
  }

  test(
    "a real definition, with no id, number or deterministic citation, is left out and counted"
  ) {
    val a = answer(
      "Q",
      claims =
        Vector(claim("C1", "Weighted degree is a measure of intensity.")),
      verification = Some(report(("C1", "definition", "VERIFIED")))
    )
    val c = only(a)
    assert(!c.factual)
    assert(c.excludedByVerifierKind)
    assert(!c.reclassifiedFactual)
    assert(!c.citesNothing, "a non-factual claim is not scored for structure")
    val summary =
      AnswerAudit.summarize(Vector(audit(a)), Vector(a), Map.empty, tol)
    assertEquals(
      summary("structuralSupport")("excludedByVerifierKind").num.toInt,
      1
    )
    assertEquals(summary("structuralSupport")("factualClaims").num.toInt, 0)
  }

  test(
    "the summary counts a reclassified claim and a verifier-verified contradiction"
  ) {
    val a = answer(
      "Q",
      claims = Vector(claim("C1", "Alpha has 11 publications.", "E")),
      evidence = Vector(item("E", metricFact())),
      verification = Some(report(("C1", "definition", "VERIFIED")))
    )
    val s = AnswerAudit.summarize(Vector(audit(a)), Vector(a), Map.empty, tol)
    assertEquals(
      s("structuralSupport")("reclassifiedFactualByAudit").num.toInt,
      1
    )
    assertEquals(
      s("verifierSelfReported")("claimsVerifiedButAuditContradicted").num.toInt,
      1
    )
    assertEquals(s("verifierSelfReported")("verdicts")("VERIFIED").num.toInt, 1)
  }

  // ---- contradiction rate and summary ----------------------------------------------

  test(
    "the contradiction rate is contradicted factual claims over factual claims"
  ) {
    val ok = answer(
      "Q1",
      claims = Vector(
        claim("A1", "Alpha has 10 publications.", "E"),
        claim("A2", "Alpha averages 2.5 coauthors per paper.", "E"),
        claim("A3", "Alpha has 12 publications.", "E")
      ),
      evidence = Vector(item("E", metricFact()))
    )
    val second = answer(
      "Q2",
      claims = Vector(claim("B1", "See researcher:v1:nobody.", "E")),
      evidence = Vector(item("E", coauthorFact()))
    )
    val abstained = answer(
      "Q3",
      claims = Vector(claim("X1", "Never counted: researcher:v1:nobody.")),
      abstained = true,
      reason = Some("claims-unsupported")
    )
    val results = Vector(ok, second, abstained).map(audit)
    val s = AnswerAudit.summarize(
      results,
      Vector(ok, second, abstained),
      Map.empty,
      tol
    )
    assertEquals(s("answers")("audited").num.toInt, 3)
    assertEquals(s("answers")("answered").num.toInt, 2)
    assertEquals(s("answers")("abstained").num.toInt, 1)
    assertEquals(s("structuralSupport")("factualClaims").num.toInt, 4)
    assertEquals(s("contradictions")("claimsContradicted").num.toInt, 2)
    assertEqualsDouble(s("contradictions")("contradictionRate").num, 0.5, 1e-12)
    assertEquals(s("contradictions")("answersWithContradiction").num.toInt, 2)
  }

  test("evidence validity rates are valid over checked, per category") {
    val a = answer(
      "Q",
      evidence = Vector(
        item("E1", coauthorFact()),
        item("E2", coauthorFact(joint = 9)),
        item("E3", coauthorFact(edge = ebc, a = beta, b = gamma, joint = 2)),
        item("E4", publicationFact(w(1), "Title of work 1"))
      )
    )
    val s = AnswerAudit.summarize(Vector(audit(a)), Vector(a), Map.empty, tol)
    val edges = s("evidenceValidity")("coauthorEdges")
    assertEquals(edges("checked").num.toInt, 3)
    assertEquals(edges("valid").num.toInt, 2)
    assertEqualsDouble(edges("validRate").num, 2.0 / 3.0, 1e-12)
    assertEquals(s("evidenceValidity")("publications")("valid").num.toInt, 1)
  }

  test("the summary states its tolerance and keeps verifier verdicts apart") {
    val s = AnswerAudit.summarize(
      Vector.empty,
      Vector.empty,
      Map.empty,
      Tolerance(0.02, 0.0)
    )
    assertEqualsDouble(s("tolerance")("abs").num, 0.02, 1e-12)
    assertEqualsDouble(s("tolerance")("rel").num, 0.0, 1e-12)
    assert(s("verifierSelfReported")("note").str.contains("not independent"))
    assertEquals(s("contradictions")("contradictionRate"), ujson.Null)
  }

  // ---- abstention recall, false abstention, reason-code match ------------------------

  private def lab(
      id: String,
      abstain: Boolean = false,
      codes: Vector[String] = Vector.empty,
      accepted: Vector[String] = Vector.empty
  ): (String, LabelRec) =
    id -> LabelRec(
      id,
      if abstain then "unanswerable" else "ranking",
      "ranking",
      if abstain || accepted.nonEmpty then Vector.empty else Vector(("x", 1)),
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

  private def pathStatusItem(kind: String): EvidenceRec =
    item(
      "PS",
      fact(
        "path-status",
        "pathKind" -> ujson.Str(kind),
        "noPathExists" -> ujson.Bool(false)
      )
    )

  private val abstentionAnswers = Vector(
    answer("U1", abstained = true, reason = Some("identity-unresolved")),
    answer("U2", abstained = true, reason = Some("claims-unsupported")),
    answer("U3"),
    answer("U4", abstained = true, reason = Some("anything")),
    answer("A1", abstained = true, reason = Some("synthesis-failed")),
    answer("A2"),
    answer("A3"),
    answer("P1", evidence = Vector(pathStatusItem("NotPrecomputed"))),
    answer("P2", abstained = true, reason = Some("no-deterministic-evidence")),
    answer("P3"),
    answer("P4", evidence = Vector(pathStatusItem("Reachable")))
  )

  private val abstentionLabels: Map[String, LabelRec] = Map(
    lab("U1", abstain = true, codes = Vector("identity-unresolved")),
    lab("U2", abstain = true, codes = Vector("planner-refused")),
    lab("U3", abstain = true, codes = Vector("planner-refused")),
    lab("U4", abstain = true),
    lab("A1"),
    lab("A2"),
    lab("A3"),
    lab("P1", accepted = Vector("path-status:NotPrecomputed", "abstain")),
    lab("P2", accepted = Vector("path-status:NotPrecomputed", "abstain")),
    lab("P3", accepted = Vector("path-status:NotPrecomputed", "abstain")),
    lab("P4", accepted = Vector("path-status:NotPrecomputed", "abstain"))
  )

  private lazy val abstentionSummary =
    AnswerAudit
      .summarize(
        abstentionAnswers.map(audit),
        abstentionAnswers,
        abstentionLabels,
        tol
      )("abstention")

  test("abstention recall is abstained over unanswerable") {
    // U1 U2 U4 abstained, U3 answered.
    assertEquals(abstentionSummary("unanswerable").num.toInt, 4)
    assertEqualsDouble(
      abstentionSummary("abstentionRecall").num,
      3.0 / 4.0,
      1e-12
    )
    assertEquals(abstentionSummary("unanswerableAnswered").num.toInt, 1)
  }

  test("reason-code match counts only abstentions whose label lists codes") {
    // U1 matches, U2 does not; U4 has no expectedCodes and is not counted.
    assertEqualsDouble(
      abstentionSummary("reasonCodeMatchAmongAbstained").num,
      0.5,
      1e-12
    )
  }

  test(
    "false abstention is an answerable question answered with an abstention"
  ) {
    // A1 abstained, A2 and A3 answered.
    assertEquals(abstentionSummary("answerable").num.toInt, 3)
    assertEqualsDouble(
      abstentionSummary("falseAbstentionRate").num,
      1.0 / 3.0,
      1e-12
    )
  }

  test(
    "an accepted-outcome label is correct on a matching path status or an abstention"
  ) {
    // P1 status NotPrecomputed ok, P2 abstained ok, P3 answered no, P4 Reachable no.
    assertEquals(abstentionSummary("acceptedOutcomeRecords").num.toInt, 4)
    assertEqualsDouble(
      abstentionSummary("acceptedOutcomeCorrect").num,
      0.5,
      1e-12
    )
  }

  test("an accepted-outcome abstention is not a false abstention") {
    // P2 abstained; it must not enter the answerable denominator.
    assertEquals(abstentionSummary("answerable").num.toInt, 3)
  }

  test("answers without a label are left out of the abstention block") {
    val extra = answer("NOLABEL", abstained = true, reason = Some("x"))
    val s = AnswerAudit
      .summarize(Vector(audit(extra)), Vector(extra), abstentionLabels, tol)(
        "abstention"
      )
    assertEquals(s("unanswerable").num.toInt, 0)
    assertEquals(s("abstentionRecall"), ujson.Null)
    assertEquals(s("falseAbstentionRate"), ujson.Null)
  }

  // ---- the audit run on files --------------------------------------------------------

  test(
    "a run audits usable primary answers only and writes no claim or answer text"
  ) {
    val base = tempDir("p16w2-audit-")
    val secretClaim = "ZZSECRETCLAIMTEXT alpha has 10 publications."
    val secretAnswer = "ZZSECRETANSWERTEXT"
    val good = answer(
      "Q1",
      claims = Vector(claim("C1", secretClaim, "E")),
      evidence = Vector(item("E", metricFact())),
      text = secretAnswer
    )
    val repeat = good.copy(run = "repeat")
    val stopped = good.copy(questionId = "Q2", costStopped = true)
    val failed = good.copy(questionId = "Q3", error = Some("Boom"))
    val answers = base.resolve("answers.jsonl")
    Files.writeString(
      answers,
      Vector(good, repeat, stopped, failed)
        .map(r => ujson.write(r.toJson))
        .mkString("", "\n", "\n"),
      UTF_8
    )
    val (_, labels) = writeBenchmark(base.resolve("bench"))
    val report =
      AnswerAudit.run(answers, labels, ix, base.resolve("out"), "audit-1")
    assertEquals(report.audited, 1)
    val dir = report.runDir
    Vector(
      "audit-claims.jsonl",
      "audit-answers.jsonl",
      "audit-summary.json",
      "run-manifest.json",
      "SHA256SUMS"
    )
      .foreach(n => assert(Files.exists(dir.resolve(n)), n))
    val text = allText(dir)
    assert(
      !text.contains("ZZSECRET"),
      "the audit output holds claim or answer text"
    )
    assert(!text.contains(good.question))
    val summary = ujson.read(readAll(dir.resolve("audit-summary.json")))
    assertEquals(summary("answers")("audited").num.toInt, 1)
    assertEquals(summary("structuralSupport")("factualClaims").num.toInt, 1)
  }

  test("the audit command line refuses a missing option with exit 2") {
    val printed = scala.collection.mutable.ArrayBuffer.empty[String]
    assertEquals(AnswerAudit.execute(Seq("--answers", "a"), printed += _), 2)
    assertEquals(AnswerAudit.execute(Seq("--bogus", "1"), printed += _), 2)
  }
