package scholarchain.evaluation.answer

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** A hand-built export (only the tables the audit reads) and the facts that
  * match it, so each rule can be broken on its own.
  *
  * Researchers R1, R2, R3 and one mention. Window 2000-2009. Edge E12 joins R1
  * and R2 (3 joint works, W1 W2 W3); edge E13 joins R1 and R3 (1 joint work,
  * W4). R1 has 3 publications, 2 distinct coauthors, weighted degree 4, an
  * average of 4/3 coauthors per paper. Community and component of R1: 3
  * members.
  */
object AuditFixture:
  val Window = "2000-2009"
  val R1 = "researcher:v1:aaa111"
  val R2 = "researcher:v1:bbb222"
  val R3 = "researcher:v1:ccc333"
  val M1 = "mention:v1:mmm999"
  val E12 = "coauthor:v1:e12e12"
  val E13 = "coauthor:v1:e13e13"
  val W1 = "work:v1:w1w1w1"
  val W2 = "work:v1:w2w2w2"
  val W3 = "work:v1:w3w3w3"
  val W4 = "work:v1:w4w4w4"
  val Title1 = "Graph neural networks for citation analysis"

  private def write(dir: Path, table: String, rows: Vector[ujson.Value]): Unit =
    val t = dir.resolve(table)
    Files.createDirectories(t)
    Files.writeString(
      t.resolve("part-00000.jsonl"),
      rows.map(ujson.write(_)).mkString("", "\n", "\n"),
      UTF_8
    )

  def writeExport(dir: Path): Path =
    Files.createDirectories(dir)
    Files.writeString(
      dir.resolve("export-manifest.json"),
      ujson.write(ujson.Obj("sourceRunId" -> "run:v1:fixture")),
      UTF_8
    )
    write(
      dir,
      "nodes-researcher",
      Vector(R1, R2, R3, M1).map(id =>
        ujson.Obj(
          "nodeId" -> id,
          "nodeKind" -> (if id == M1 then "unresolved-mention"
                         else "canonical"),
          "displayName" -> s"Name of $id"
        )
      )
    )
    write(
      dir,
      "researcher-metrics",
      Vector(
        ujson.Obj(
          "researcherId" -> R1,
          "window" -> Window,
          "windowStart" -> 2000,
          "windowEnd" -> 2009,
          "publicationCount" -> 3,
          "distinctCoauthorCount" -> 2,
          "weightedDegree" -> 4,
          "averageCoauthorsPerPaper" -> (4.0 / 3.0),
          "maximumCoauthorsOnOnePaper" -> 2
        ),
        ujson.Obj(
          "researcherId" -> R2,
          "window" -> Window,
          "publicationCount" -> 5,
          "distinctCoauthorCount" -> 1,
          "weightedDegree" -> 3
        ),
        ujson.Obj(
          "researcherId" -> R3,
          "window" -> Window,
          "publicationCount" -> 1,
          "distinctCoauthorCount" -> 1,
          "weightedDegree" -> 1
        )
      )
    )
    write(
      dir,
      "edges-coauthored",
      Vector(
        ujson.Obj(
          "edgeId" -> E12,
          "researcherA" -> R1,
          "researcherB" -> R2,
          "window" -> Window,
          "jointPublicationCount" -> 3
        ),
        ujson.Obj(
          "edgeId" -> E13,
          "researcherA" -> R1,
          "researcherB" -> R3,
          "window" -> Window,
          "jointPublicationCount" -> 1
        )
      )
    )
    write(
      dir,
      "edge-support",
      Vector(
        ujson.Obj("edgeId" -> E12, "canonicalWorkId" -> W1),
        ujson.Obj("edgeId" -> E12, "canonicalWorkId" -> W2),
        ujson.Obj("edgeId" -> E12, "canonicalWorkId" -> W3),
        ujson.Obj("edgeId" -> E13, "canonicalWorkId" -> W4)
      )
    )
    write(
      dir,
      "nodes-work",
      Vector(
        ujson.Obj("canonicalWorkId" -> W1, "title" -> Title1),
        ujson.Obj("canonicalWorkId" -> W2, "title" -> "Second work"),
        ujson.Obj("canonicalWorkId" -> W3, "title" -> "Third work"),
        ujson.Obj("canonicalWorkId" -> W4, "title" -> "Fourth work")
      )
    )
    write(
      dir,
      "community-membership",
      Vector(
        ujson.Obj(
          "researcherId" -> R1,
          "window" -> Window,
          "communityId" -> "c1",
          "communitySize" -> 3
        )
      )
    )
    write(
      dir,
      "component-membership",
      Vector(
        ujson.Obj(
          "researcherId" -> R1,
          "window" -> Window,
          "componentId" -> "k1",
          "componentSize" -> 3
        )
      )
    )
    dir

  private def fact(kind: String, fields: (String, ujson.Value)*): ujson.Value =
    ujson.Obj.from(("kind" -> ujson.Str(kind)) +: fields)

  private def windowFields: Seq[(String, ujson.Value)] = Seq(
    "window" -> ujson.Str(Window),
    "windowStart" -> ujson.Num(2000),
    "windowEnd" -> ujson.Num(2009)
  )

  def coauthorFact(
      edge: String = E12,
      a: String = R1,
      b: String = R2,
      joint: Double = 3,
      window: String = Window
  ): ujson.Value =
    fact(
      "coauthor",
      Seq(
        "researcherId" -> ujson.Str(a),
        "coauthorId" -> ujson.Str(b),
        "edgeId" -> ujson.Str(edge),
        "window" -> ujson.Str(window),
        "windowStart" -> ujson.Num(2000),
        "windowEnd" -> ujson.Num(2009),
        "jointPublicationCount" -> ujson.Num(joint)
      )*
    )

  private def hop(
      index: Int,
      from: String,
      to: String,
      edge: String,
      joint: Double,
      works: Vector[String]
  ): ujson.Value =
    ujson.Obj(
      "hopIndex" -> index,
      "from" -> from,
      "to" -> to,
      "edgeId" -> edge,
      "jointPublicationCount" -> joint,
      "supportingWorkIds" -> ujson.Arr.from(works.map(w => ujson.Str(w)))
    )

  /** R2 to R3 through R1. */
  def pathFact(
      hops: Vector[ujson.Value] = Vector(
        hop(1, R2, R1, E12, 3, Vector(W1)),
        hop(2, R1, R3, E13, 1, Vector(W4))
      ),
      hopCount: Double = 2
  ): ujson.Value =
    fact(
      "path",
      Seq(
        "source" -> ujson.Str(R2),
        "target" -> ujson.Str(R3),
        "hopCount" -> ujson.Num(hopCount),
        "hops" -> ujson.Arr.from(hops)
      ) ++ windowFields*
    )

  def hopOf(
      index: Int,
      from: String,
      to: String,
      edge: String,
      joint: Double,
      works: Vector[String]
  ): ujson.Value = hop(index, from, to, edge, joint, works)

  def publicationFact(work: String = W1, title: String = Title1): ujson.Value =
    fact(
      "publication",
      "workId" -> ujson.Str(work),
      "title" -> ujson.Str(title),
      "year" -> ujson.Num(2004)
    )

  def metricFact(
      id: String = R1,
      publications: Double = 3,
      average: Double = 1.33
  ): ujson.Value =
    fact(
      "metric",
      Seq(
        "researcherId" -> ujson.Str(id),
        "publicationCount" -> ujson.Num(publications),
        "distinctCoauthorCount" -> ujson.Num(2),
        "weightedDegree" -> ujson.Num(4),
        "averageCoauthorsPerPaper" -> ujson.Num(average),
        "maximumCoauthorsOnOnePaper" -> ujson.Num(2)
      ) ++ windowFields*
    )

  def rankingFact(
      entries: Vector[(String, Double)] =
        Vector(R2 -> 5.0, R1 -> 3.0, R3 -> 1.0),
      metric: String = "publicationCount"
  ): ujson.Value =
    fact(
      "ranking",
      Seq(
        "metric" -> ujson.Str(metric),
        "rankedTotal" -> ujson.Num(3),
        "entries" -> ujson.Arr.from(entries.zipWithIndex.map {
          case ((id, v), i) =>
            ujson.Obj("rank" -> (i + 1), "researcherId" -> id, "value" -> v)
        })
      ) ++ windowFields*
    )

  def groupFact(kind: String, size: Double = 3): ujson.Value =
    fact(
      kind,
      Seq(
        "researcherId" -> ujson.Str(R1),
        (if kind == "component" then "componentSize" else "communitySize") ->
          ujson.Num(size),
        "crossEdges" -> ujson.Num(7)
      ) ++ windowFields*
    )

  def identityFact(id: String = R1): ujson.Value =
    fact("identity", "researcherId" -> ujson.Str(id))

  /** A deterministic evidence item holding `fact`. */
  def item(id: String, fact: ujson.Value): EvidenceRec =
    EvidenceRec(
      id,
      "Metric",
      scholarchain.rag.Facts.kind(fact),
      None,
      Vector.empty,
      1.0,
      Some(ujson.write(fact)),
      None
    )

  def snippet(id: String, text: String = "A snippet of text."): EvidenceRec =
    EvidenceRec(
      id,
      "TextChunk",
      None,
      None,
      Vector.empty,
      0.2,
      Some(text),
      None
    )

  def claim(
      id: String,
      text: String,
      evidence: Vector[String],
      status: String = "Verified"
  ): ClaimRec =
    ClaimRec(id, text, evidence, status, None)

  def verification(
      claims: Vector[(String, String, String)],
      overall: String = "VERIFIED"
  ): ujson.Value =
    ujson.Obj(
      "schema" -> "scholarchain.rag.verifier-report/1",
      "overall" -> overall,
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

  def answer(
      questionId: String = "Q1",
      run: String = "primary",
      claims: Vector[ClaimRec] = Vector.empty,
      evidence: Vector[EvidenceRec] = Vector.empty,
      verification: Option[ujson.Value] = None,
      abstained: Boolean = false,
      reason: Option[String] = None,
      hash: String = "h1",
      costStopped: Boolean = false,
      text: String = "An answer."
  ): AnswerRecord =
    AnswerRecord(
      questionId,
      run,
      "fused",
      s"s-$run",
      1,
      s"Question $questionId?",
      text,
      abstained,
      reason,
      claims,
      evidence,
      verification,
      Vector.empty,
      1,
      100,
      50,
      tokensEstimated = false,
      10L,
      Vector.empty,
      hash,
      None,
      costStopped,
      None
    )
