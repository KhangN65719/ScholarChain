package scholarchain.tests.endtoend

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.regex.Pattern
import munit.FunSuite
import scholarchain.core.YearWindow
import scholarchain.rag.*
import scholarchain.storage.*
import scholarchain.tests.adversarial.*
import scholarchain.tests.adversarial.P9.*
import scholarchain.tests.adversarial.P9Check.*

/** The Phase 9 benchmark-query suite. Every question has expected facts
  * computed by `P9.Oracle` from the fixture paper list. Deterministic graph
  * retrieval (table 1) and semantic retrieval (table 2) are scored apart.
  */
class Phase9BenchmarkSuite extends FunSuite:
  private lazy val world = new World()

  sealed trait Outcome
  case object Answered extends Outcome
  final case class Abstained(reason: String) extends Outcome

  final case class Check(desc: String, test: WorkflowResult => Boolean)

  final case class Q(
      id: String,
      category: String,
      question: String,
      outcome: Outcome,
      checks: Vector[Check],
      says: Vector[String] = Vector.empty,
      avoids: Vector[String] = Vector.empty
  )

  // Fact checks -----------------------------------------------------------------

  private def id(k: String): String = rid(k)
  private def has(kind: String)(p: ujson.Value => Boolean)(
      r: WorkflowResult
  ): Boolean = kindFacts(r, kind).exists(p)
  private def num(v: Double): String =
    "\\b" + Pattern.quote(Fmt.number(v)) + "\\b"
  private def sOf(f: ujson.Value, k: String) = Facts.s(f, k)
  private def nOf(f: ujson.Value, k: String, v: Double): Boolean =
    Facts.n(f, k).exists(approx(_, v))
  private def at(f: ujson.Value, w: YearWindow): Boolean =
    sOf(f, "window").contains(label(w))

  private def metric(k: String, w: YearWindow, field: String): Check =
    val v = oracle(w).valueOf(field, k).get
    Check(
      s"metric $k ${label(w)} $field=$v",
      has("metric")(f =>
        sOf(f, "researcherId").contains(id(k)) && at(f, w) && nOf(f, field, v)
      )
    )

  private def coauthor(a: String, b: String, w: YearWindow): Check =
    val o = oracle(w)
    val (first, last) = o.edgeYears(a, b)
    Check(
      s"coauthor $a $b ${label(w)} joint=${o.joint(a, b)}",
      has("coauthor")(f =>
        Set(sOf(f, "researcherId"), sOf(f, "coauthorId")) ==
          Set(Some(id(a)), Some(id(b))) && at(f, w) &&
          nOf(f, "jointPublicationCount", o.joint(a, b).toDouble) &&
          nOf(f, "firstCollaborationYear", first.toDouble) &&
          nOf(f, "lastCollaborationYear", last.toDouble)
      )
    )

  private def path(a: String, b: String, w: YearWindow): Check =
    val nodes = oracle(w).uniquePath(a, b)
    Check(
      s"path $a to $b ${label(w)} = ${nodes.mkString("-")}",
      has("path")(f =>
        sOf(f, "source").contains(id(a)) && sOf(f, "target").contains(id(b)) &&
          at(f, w) && nOf(f, "hopCount", (nodes.size - 1).toDouble) && {
            val hops = Facts.list(f, "hops")
            hops.size == nodes.size - 1 &&
            hops.zip(nodes.sliding(2).toVector).zipWithIndex.forall {
              case ((hop, Vector(x, y)), i) =>
                sOf(hop, "from").contains(id(x)) &&
                sOf(hop, "to").contains(id(y)) &&
                nOf(hop, "hopIndex", (i + 1).toDouble) &&
                nOf(
                  hop,
                  "jointPublicationCount",
                  oracle(w).joint(x, y).toDouble
                ) &&
                sOf(hop, "edgeId").contains(edgeId(x, y, w))
              case _ => false
            }
          }
      )
    )

  private def pathStatus(
      a: String,
      b: String,
      w: YearWindow,
      kind: String,
      noPath: Boolean
  ): Check =
    Check(
      s"path-status $a $b ${label(w)} $kind noPath=$noPath",
      has("path-status")(f =>
        sOf(f, "source").contains(id(a)) && sOf(f, "target").contains(id(b)) &&
          at(f, w) && sOf(f, "pathKind").contains(kind) &&
          Facts.b(f, "noPathExists").contains(noPath)
      )
    )

  private def component(k: String, w: YearWindow): Check =
    val o = oracle(w)
    Check(
      s"component $k ${label(w)} size=${o.componentSize(k)}",
      has("component")(f =>
        sOf(f, "researcherId").contains(id(k)) && at(f, w) &&
          nOf(f, "componentSize", o.componentSize(k).toDouble) &&
          sOf(f, "componentId").contains(o.componentId(k)) &&
          Facts.b(f, "isLargest").contains(o.isLargest(k))
      )
    )

  private def community(k: String, w: YearWindow): Check =
    val o = oracle(w)
    val c = o.community(k)
    val neighbors = o
      .crossEdgeList(c)
      .flatMap { case (a, bb) =>
        Vector(o.community(a), o.community(bb))
      }
      .filterNot(_ == c)
      .distinct
    Check(
      s"community $k ${label(w)} $c size=${o.communityMembers(c).size}",
      has("community")(f =>
        sOf(f, "researcherId").contains(id(k)) && at(f, w) &&
          sOf(f, "communityId").contains(communityId(w, c)) &&
          nOf(f, "communitySize", o.communityMembers(c).size.toDouble) &&
          nOf(f, "internalEdges", o.internalEdges(c).size.toDouble) &&
          nOf(f, "crossEdges", o.crossEdgeList(c).size.toDouble) &&
          nOf(f, "neighborsTotal", neighbors.size.toDouble) &&
          Facts.list(f, "neighbors").forall { nb =>
            val other = o.communityNames
              .find(x => sOf(nb, "communityId").contains(communityId(w, x)))
            other.exists(x =>
              nOf(
                nb,
                "edgeCount",
                o.crossEdgeList(c)
                  .count { case (p, q) =>
                    o.community(p) == x || o.community(q) == x
                  }
                  .toDouble
              )
            )
          } && {
            val direct = f("directDegree")
            val structural = f("structuralBridge")
            nOf(direct, "distinctCoauthorCount", o.distinctCount(k).toDouble) &&
            nOf(structural, "bridgeScore", bridge(w)(k))
          }
      )
    )

  private def publication(workKey: String): Check =
    Check(
      s"publication $workKey",
      has("publication")(f =>
        sOf(f, "workId").contains(wid(workKey)) &&
          sOf(f, "title").contains(paper(workKey).title)
      )
    )

  private def identity(k: String): Check =
    Check(
      s"identity $k",
      has("identity")(f =>
        sOf(f, "researcherId").contains(id(k)) &&
          sOf(f, "displayName").contains(person(k).name)
      )
    )

  private def ranking(
      w: YearWindow,
      wire: String,
      field: String,
      limit: Int
  ): Check =
    val o = oracle(w)
    val all = o.rankedValues(field)
    val sorted = all.sortBy(x => (-x._2, id(x._1)))
    val cut = math.min(limit, all.size)
    val tie = sorted.size > limit && sorted(limit)._2 == sorted(limit - 1)._2
    Check(
      s"ranking $wire ${label(w)} top $limit",
      has("ranking")(f =>
        sOf(f, "metric").contains(wire) && at(f, w) && {
          val entries = Facts.list(f, "entries")
          val keysOf = entries.map(e =>
            P9.keys.find(k => sOf(e, "researcherId").contains(id(k)))
          )
          entries.size == cut &&
          keysOf.forall(_.isDefined) &&
          entries.zip(sorted.take(cut)).forall { case (e, (_, v)) =>
            nOf(e, "value", v)
          } &&
          entries.zip(keysOf).forall { case (e, k) =>
            nOf(e, "rank", o.rankOf(field, k.get).toDouble) &&
            nOf(e, "value", o.valueOf(field, k.get).get)
          } &&
          !entries.exists(e => sOf(e, "researcherId").contains(mentionId)) &&
          nOf(f, "rankedTotal", all.size.toDouble) &&
          nOf(f, "mentionsExcluded", if w == W1 then 1.0 else 0.0) &&
          Facts.b(f, "tieAtCutoff").contains(tie)
        }
      )
    )

  private def noFact(kind: String): Check =
    Check(s"no $kind fact", r => kindFacts(r, kind).isEmpty)

  private def note(text: String): Check =
    Check(
      s"bundle or answer says: $text",
      r =>
        r.response.answer.contains(text) ||
          r.response.evidence.uncertainty.exists(_.contains(text))
    )

  // The question list ---------------------------------------------------------

  private val W1s = "2000-2009"
  private val W2s = "2010-2019"
  private val noPathClaim = "(?i)\\bno (collaboration |coauthorship )?path\\b"

  private val questions: Vector[Q] = Vector(
    Q(
      "B01",
      "breadth",
      s"How many distinct coauthors does Alice Archer have in $W2s?",
      Answered,
      Vector(metric("al", W2, "distinctCoauthorCount")),
      says = Vector(num(o2.distinctCount("al")))
    ),
    Q(
      "B02",
      "breadth",
      s"How many distinct coauthors does Farid Fontaine have in $W1s?",
      Answered,
      Vector(metric("fa", W1, "distinctCoauthorCount")),
      says = Vector(num(o1.distinctCount("fa")))
    ),
    Q(
      "B03",
      "breadth",
      s"Who has the most distinct coauthors in $W2s?",
      Answered,
      Vector(
        ranking(W2, "distinct-coauthor-count", "distinctCoauthorCount", 5)
      ),
      says = Vector("(?i)rank 1")
    ),
    Q(
      "B04",
      "breadth",
      s"Show the top 3 researchers by distinct coauthors in $W1s.",
      Answered,
      Vector(
        ranking(W1, "distinct-coauthor-count", "distinctCoauthorCount", 3)
      ),
      avoids = Vector(Pattern.quote(mentionName))
    ),
    Q(
      "B05",
      "breadth",
      s"How many publications does Greta Garcia have in $W2s?",
      Answered,
      Vector(metric("gr", W2, "publicationCount")),
      says = Vector(num(o2.pubs("gr")))
    ),
    Q(
      "T01",
      "team-size",
      s"What is the maximum team size for Alice Archer in $W1s?",
      Answered,
      Vector(
        metric("al", W1, "maximumCoauthorsOnOnePaper"),
        publication("p04")
      ),
      says = Vector(num(o1.maxCo("al")), Pattern.quote(paper("p04").title))
    ),
    Q(
      "T02",
      "team-size",
      s"What is the largest team size for Greta Garcia in $W2s?",
      Answered,
      Vector(
        metric("gr", W2, "maximumCoauthorsOnOnePaper"),
        publication("q06")
      ),
      says = Vector(num(o2.maxCo("gr")))
    ),
    Q(
      "T03",
      "team-size",
      s"Who has the largest team size in $W1s?",
      Answered,
      Vector(
        ranking(
          W1,
          "maximum-coauthors-on-one-paper",
          "maximumCoauthorsOnOnePaper",
          5
        )
      )
    ),
    Q(
      "D01",
      "direct",
      s"Has Alice Archer worked with Bruno Basu in $W2s?",
      Answered,
      Vector(coauthor("al", "br", W2)),
      says = Vector(num(2011), num(2015))
    ),
    Q(
      "D02",
      "direct",
      s"Has Alice Archer worked with Farid Fontaine in $W2s?",
      Answered,
      Vector(
        noFact("coauthor"),
        note("No direct coauthor edge between Alice Archer and Farid Fontaine")
      ),
      avoids = Vector("(?i)co-authored")
    ),
    Q(
      "D03",
      "direct",
      s"List the coauthors of Alice Archer in $W2s.",
      Answered,
      o2.coauthors("al").map(c => coauthor("al", c, W2))
    ),
    Q(
      "D04",
      "direct",
      s"Who are the collaborators of Jonas Jensen in $W2s?",
      Answered,
      o2.coauthors("jo").map(c => coauthor("jo", c, W2))
    ),
    Q(
      "S01",
      "path",
      s"What is the shortest path between Alice Archer and Greta Garcia in $W2s?",
      Answered,
      Vector(path("al", "gr", W2)),
      says =
        Vector("Alice Archer -> Dmitri Dvorak -> Jonas Jensen -> Greta Garcia")
    ),
    Q(
      "S02",
      "path",
      s"What is the shortest path between Greta Garcia and Alice Archer in $W2s?",
      Answered,
      Vector(path("gr", "al", W2)),
      says =
        Vector("Greta Garcia -> Jonas Jensen -> Dmitri Dvorak -> Alice Archer")
    ),
    Q(
      "S03",
      "path",
      s"How are Alice Archer and Farid Fontaine connected in $W2s?",
      Answered,
      Vector(path("al", "fa", W2))
    ),
    Q(
      "S04",
      "path",
      s"Find the shortest path from Alice Archer to Ines Iyer in $W2s.",
      Answered,
      Vector(path("al", "in", W2))
    ),
    Q(
      "S05",
      "path",
      s"Find the shortest path from Dmitri Dvorak to Farid Fontaine in $W2s.",
      Answered,
      Vector(path("dm", "fa", W2))
    ),
    Q(
      "S06",
      "path",
      s"What is the shortest path between Alice Archer and Wei Zhang in $W1s?",
      Abstained("identity-unresolved"),
      Vector(noFact("path"), noFact("path-status"))
    ),
    Q(
      "S07",
      "path",
      s"What is the shortest path between Alice Archer and Kofi Kamara in $W2s?",
      Answered,
      Vector(pathStatus("al", "ko", W2, "DifferentComponent", noPath = true)),
      says = Vector("(?i)different connected components")
    ),
    Q(
      "S08",
      "path",
      s"What is the shortest path between Bruno Basu and Ines Iyer in $W2s?",
      Answered,
      Vector(pathStatus("br", "in", W2, "BeyondHopLimit", noPath = false)),
      avoids = Vector(noPathClaim)
    ),
    Q(
      "S09",
      "path",
      s"What is the shortest path between Bruno Basu and Hiro Hayashi in $W2s?",
      Answered,
      Vector(
        pathStatus("br", "hi", W2, "UnreachableUnexplained", noPath = false)
      ),
      avoids = Vector(noPathClaim)
    ),
    Q(
      "S10",
      "path",
      s"What is the shortest path between Carla Chen and Ines Iyer in $W2s?",
      Answered,
      Vector(pathStatus("ca", "in", W2, "NotPrecomputed", noPath = false)),
      avoids = Vector(noPathClaim)
    ),
    Q(
      "C01",
      "component",
      s"What connected component is Alice Archer in during $W2s?",
      Answered,
      Vector(component("al", W2)),
      says = Vector(num(o2.componentSize("al")))
    ),
    Q(
      "C02",
      "component",
      s"What is the component of Kofi Kamara in $W2s?",
      Answered,
      Vector(component("ko", W2)),
      says = Vector(num(o2.componentSize("ko")))
    ),
    Q(
      "C03",
      "component",
      s"What component is Farid Fontaine in $W1s?",
      Answered,
      Vector(component("fa", W1)),
      says = Vector(num(o1.componentSize("fa")))
    ),
    Q(
      "M01",
      "community",
      s"Which community is Alice Archer in during $W2s?",
      Answered,
      Vector(community("al", W2)),
      says = Vector(num(o2.communityMembers("a").size))
    ),
    Q(
      "M02",
      "community",
      s"Describe the community of Ines Iyer in $W2s.",
      Answered,
      Vector(community("in", W2))
    ),
    Q(
      "M03",
      "community",
      s"Which community is Kofi Kamara in during $W1s?",
      Answered,
      Vector(community("ko", W1))
    ),
    Q(
      "R01",
      "bridge",
      s"Who are the top 3 bridge researchers in $W2s?",
      Answered,
      Vector(ranking(W2, "bridge-score", "bridgeScore", 3)),
      says = Vector("(?i)rank 1")
    ),
    Q(
      "R02",
      "bridge",
      s"What is the bridge score of Jonas Jensen in $W2s compared with his number of coauthors?",
      Answered,
      Vector(
        metric("jo", W2, "bridgeScore"),
        metric("jo", W2, "distinctCoauthorCount"),
        community("jo", W2)
      ),
      says = Vector(
        num(bridge(W2)("jo")),
        "Bridge importance is a stored structural score",
        "Collaboration breadth is the number of distinct coauthors"
      )
    ),
    Q(
      "R03",
      "bridge",
      s"Is Alice Archer a bridge researcher in $W2s?",
      Answered,
      Vector(
        metric("al", W2, "bridgeScore"),
        metric("al", W2, "distinctCoauthorCount")
      ),
      says = Vector(num(bridge(W2)("al")), num(o2.distinctCount("al")))
    ),
    Q(
      "R04",
      "bridge",
      s"Who are the top 2 bridge researchers in $W1s?",
      Answered,
      Vector(ranking(W1, "bridge-score", "bridgeScore", 2))
    ),
    Q(
      "P01",
      "temporal",
      s"Compare Alice Archer between $W1s and $W2s.",
      Answered,
      Vector(
        metric("al", W1, "publicationCount"),
        metric("al", W2, "publicationCount"),
        metric("al", W1, "weightedDegree"),
        metric("al", W2, "weightedDegree")
      ),
      says = Vector(
        num(o1.pubs("al")),
        num(o2.pubs("al")),
        num(o1.weighted("al")),
        num(o2.weighted("al"))
      )
    ),
    Q(
      "P02",
      "temporal",
      s"How did Farid Fontaine change from $W1s to $W2s?",
      Answered,
      Vector(
        metric("fa", W1, "weightedDegree"),
        metric("fa", W2, "weightedDegree")
      ),
      says = Vector(num(o1.weighted("fa")), num(o2.weighted("fa")))
    ),
    Q(
      "P03",
      "temporal",
      "Compare Alice Archer over time.",
      Abstained("needs-two-windows"),
      Vector(noFact("metric"))
    ),
    Q(
      "P04",
      "temporal",
      s"Compare Kofi Kamara between $W1s and $W2s.",
      Answered,
      Vector(
        metric("ko", W1, "publicationCount"),
        metric("ko", W2, "publicationCount")
      )
    ),
    Q(
      "P05",
      "temporal",
      s"Compare Jonas Jensen between $W1s and $W2s.",
      Answered,
      Vector(
        metric("jo", W2, "publicationCount"),
        Check(
          "no metric for Jonas Jensen in the first window",
          r =>
            !kindFacts(r, "metric").exists(f =>
              sOf(f, "researcherId").contains(id("jo")) && at(f, W1)
            )
        ),
        note("No stored metrics for Jonas Jensen in window 2000-2009")
      )
    ),
    Q(
      "O01",
      "topic",
      s"Which topics is Alice Archer associated with in $W2s?",
      Answered,
      Vector(
        metric("al", W2, "publicationCount"),
        note("a topic or institution filter cannot be applied")
      ),
      avoids = Vector("(?i)topics? (of|include|are)")
    ),
    Q(
      "O02",
      "topic",
      s"Who works on the topic of genomic sequencing in $W2s?",
      Abstained("researcher-count"),
      Vector(noFact("metric"), noFact("ranking"), noFact("identity"))
    ),
    Q(
      "I01",
      "institution",
      s"Which institution is Alice Archer affiliated with in $W2s?",
      Answered,
      Vector(note("a topic or institution filter cannot be applied")),
      avoids = Vector("(?i)university|institute|college")
    ),
    Q(
      "I02",
      "institution",
      s"Which universities are researchers from in $W2s?",
      Abstained("researcher-count"),
      Vector(noFact("metric"), noFact("ranking"))
    ),
    Q(
      "E01",
      "evidence",
      s"What evidence supports Alice Archer's largest team paper in $W1s?",
      Answered,
      Vector(
        publication("p04"),
        metric("al", W1, "maximumCoauthorsOnOnePaper")
      ),
      says = Vector(Pattern.quote(paper("p04").title))
    ),
    Q(
      "E02",
      "evidence",
      s"Cite the sources for the largest team of Greta Garcia in $W2s.",
      Answered,
      Vector(publication("q06")),
      says = Vector(Pattern.quote(paper("q06").title))
    ),
    Q(
      "E03",
      "evidence",
      s"Show the evidence for the path between Alice Archer and Dmitri Dvorak in $W2s.",
      Answered,
      Vector(path("al", "dm", W2), publication("q12")),
      says = Vector(Pattern.quote(paper("q12").title))
    ),
    Q(
      "L01",
      "lookup",
      "Who is Alice Archer?",
      Answered,
      Vector(identity("al")),
      says = Vector("Alice Archer")
    )
  )

  // Scoring ---------------------------------------------------------------------

  final case class Scored(
      q: Q,
      outcomeOk: Boolean,
      failedChecks: Vector[String],
      answerProblems: Vector[String]
  ):
    def factsOk: Boolean = failedChecks.isEmpty
    def answerOk: Boolean = answerProblems.isEmpty
    def pass: Boolean = outcomeOk && factsOk && answerOk

  private val cache =
    scala.collection.mutable.LinkedHashMap.empty[String, Scored]

  private def score(q: Q): Scored = cache.getOrElseUpdate(
    q.id, {
      val r = world.ask(q.question)
      val outcomeOk = q.outcome match
        case Answered       => !r.abstained
        case Abstained(why) => r.abstained && r.abstentionReason.contains(why)
      val failed = q.checks.filterNot(_.test(r)).map(_.desc)
      val problems = Vector.newBuilder[String]
      if !outcomeOk then
        problems += s"outcome: abstained=${r.abstained} reason=${r.abstentionReason} answer=${r.response.answer}"
      if q.outcome == Answered then
        evidenceViolations(r).foreach(problems += _)
        if !r.verdict.contains(VerificationVerdict.Verified) then
          problems += s"verdict ${r.verdict}"
      if reasoningKeysIn(responseJson(r)).nonEmpty ||
        reasoningKeysIn(r.audit.payload).nonEmpty
      then problems += "reasoning field in output"
      q.says
        .filterNot(rx => rx.r.findFirstIn(r.response.answer).isDefined)
        .foreach(rx => problems += s"answer lacks /$rx/: ${r.response.answer}")
      q.avoids
        .filter(rx => rx.r.findFirstIn(r.response.answer).isDefined)
        .foreach(rx =>
          problems += s"answer contains /$rx/: ${r.response.answer}"
        )
      Scored(q, outcomeOk, failed, problems.result())
    }
  )

  questions.foreach { q =>
    test(s"benchmark ${q.id} (${q.category}): ${q.question}") {
      val s = score(q)
      assert(s.outcomeOk, s.answerProblems.mkString("\n"))
      assert(s.factsOk, s"failed fact checks: ${s.failedChecks.mkString("; ")}")
      assert(s.answerOk, s.answerProblems.mkString("\n"))
    }
  }

  // Semantic retrieval, scored apart ------------------------------------------

  enum Mode:
    case Top1, SetEquals, Empty

  final case class SQ(
      id: String,
      index: String,
      query: String,
      filter: VectorFilter,
      expected: Vector[String],
      mode: Mode,
      topK: Int = 5
  )

  private val profiles = VectorIndexes.ResearcherProfile
  private val communitiesIx = VectorIndexes.CollaborationCommunity
  private val papersIx = VectorIndexes.PaperAbstract
  private val pathsIx = VectorIndexes.CollaborationPath

  private val semanticQs: Vector[SQ] = Vector(
    SQ(
      "SQ01",
      profiles,
      "marine biology plankton Kofi Kamara",
      VectorFilter.empty,
      Vector("ko_w2"),
      Mode.Top1
    ),
    SQ(
      "SQ02",
      profiles,
      "genomics sequencing Greta Garcia",
      VectorFilter.empty,
      Vector("gr_w2"),
      Mode.Top1
    ),
    SQ(
      "SQ03",
      profiles,
      "archival manuscripts",
      VectorFilter(researcher = Some(rid("al"))),
      Vector("al_w1", "al_w2"),
      Mode.SetEquals
    ),
    SQ(
      "SQ04",
      profiles,
      "archival manuscripts",
      VectorFilter(
        researcher = Some(rid("al")),
        yearFrom = Some(2010),
        yearTo = Some(2019)
      ),
      Vector("al_w2"),
      Mode.SetEquals
    ),
    SQ(
      "SQ05",
      communitiesIx,
      "genomics sequencing proteomics laboratory",
      VectorFilter.empty,
      Vector("cb_w2"),
      Mode.Top1
    ),
    SQ(
      "SQ06",
      communitiesIx,
      "archival manuscripts digital libraries preservation",
      VectorFilter.empty,
      Vector("ca_w1", "ca_w2"),
      Mode.SetEquals,
      topK = 2
    ),
    SQ(
      "SQ07",
      communitiesIx,
      "marine biology plankton ecology",
      VectorFilter.empty,
      Vector("ck_w2"),
      Mode.Top1
    ),
    SQ(
      "SQ08",
      papersIx,
      "archive networks collaboration manuscript collections",
      VectorFilter.empty,
      Vector("w_p04"),
      Mode.Top1
    ),
    SQ(
      "SQ09",
      papersIx,
      "proteomics pipelines sequencing laboratory",
      VectorFilter.empty,
      Vector("w_q06"),
      Mode.Top1
    ),
    SQ(
      "SQ10",
      papersIx,
      "plankton imaging data reuse marine",
      VectorFilter.empty,
      Vector("w_q14"),
      Mode.Top1
    ),
    SQ(
      "SQ11",
      pathsIx,
      "Alice Archer Greta Garcia Dmitri Dvorak Jonas Jensen connected",
      VectorFilter.empty,
      Vector("pa_al_gr"),
      Mode.Top1
    ),
    SQ(
      "SQ12",
      pathsIx,
      "different components plankton marine Kofi Kamara",
      VectorFilter.empty,
      Vector("pa_al_ko"),
      Mode.Top1
    ),
    SQ(
      "SQ13",
      VectorIndexes.TopicInstitutionSummary,
      "topic genomics institution",
      VectorFilter.empty,
      Vector.empty,
      Mode.Empty
    )
  )

  private val semanticCache =
    scala.collection.mutable.LinkedHashMap.empty[String, (Boolean, String)]

  private def scoreSemantic(sq: SQ): (Boolean, String) =
    semanticCache.getOrElseUpdate(
      sq.id, {
        val hits =
          world.retrieval.search(sq.index, sq.query, sq.topK, sq.filter)
        val got = hits.map(_.hit.key)
        val want = sq.expected.map(world.keyOf)
        val ok = sq.mode match
          case Mode.Top1      => got.headOption == want.headOption
          case Mode.SetEquals => got.toSet == want.toSet
          case Mode.Empty     => got.isEmpty
        (ok, s"got ${got.map(k => world.specs.find(_._2.summaryId == k).map(_._1).getOrElse(k))} want ${sq.expected}")
      }
    )

  semanticQs.foreach { sq =>
    test(s"semantic ${sq.id}: ${sq.index} '${sq.query}'") {
      val (ok, detail) = scoreSemantic(sq)
      assert(ok, detail)
    }
  }

  final case class WS(id: String, question: String, wantChunks: Vector[String])

  private val workflowSemantic: Vector[WS] = Vector(
    WS("WS1", s"Who is Kofi Kamara in $W2s?", Vector("ko_w2")),
    WS("WS2", s"Who is Alice Archer in $W2s?", Vector("al_w2")),
    WS("WS3", s"Who is Greta Garcia in $W2s?", Vector("gr_w2")),
    WS(
      "WS4",
      s"What is the shortest path between Alice Archer and Greta Garcia in $W2s?",
      Vector("pa_al_gr")
    )
  )

  private val workflowCache =
    scala.collection.mutable.LinkedHashMap.empty[String, (Boolean, String)]

  private def scoreWorkflowSemantic(ws: WS): (Boolean, String) =
    workflowCache.getOrElseUpdate(
      ws.id, {
        val r = world.ask(ws.question)
        val present = chunksOf(r).map(_.provenance.sourceRecordId).toSet
        val want = ws.wantChunks.map(world.keyOf)
        val allCaveated = chunksOf(r).forall(
          _.uncertainty.exists(_.contains("Descriptive only"))
        )
        (
          !r.abstained && want.forall(present.contains) && allCaveated,
          s"present ${present.size}, wanted ${ws.wantChunks}, caveated $allCaveated"
        )
      }
    )

  workflowSemantic.foreach { ws =>
    test(s"semantic in workflow ${ws.id}: ${ws.question}") {
      val (ok, detail) = scoreWorkflowSemantic(ws)
      assert(ok, detail)
    }
  }

  // Tables ----------------------------------------------------------------------

  test("negative controls: the checks fail on wrong expectations") {
    val r =
      world.ask(s"How many distinct coauthors does Alice Archer have in $W2s?")
    assert(metric("al", W2, "distinctCoauthorCount").test(r))
    val wrong = metric("al", W2, "distinctCoauthorCount").copy(test =
      has("metric")(f =>
        sOf(f, "researcherId")
          .contains(id("al")) && nOf(f, "distinctCoauthorCount", 5.0)
      )
    )
    assert(!wrong.test(r))
    assert(!metric("al", W1, "distinctCoauthorCount").test(r))
    assert(!metric("br", W2, "distinctCoauthorCount").test(r))
    assert(
      !ranking(W2, "distinct-coauthor-count", "distinctCoauthorCount", 5).test(
        r
      )
    )
    assert(!coauthor("al", "br", W2).test(r))
    assert(!note("not a sentence in this answer").test(r))
    assertEquals(evidenceViolations(r), Vector.empty)
    val badClaim = r.response.claims.head.copy(
      text = "Alice Archer has 77 distinct coauthors.",
      evidenceIds = Vector.empty,
      verificationStatus = scholarchain.core.VerificationStatus.Uncertain,
      uncertainty = Some("no evidence")
    )
    val tampered = r.copy(response = r.response.copy(claims = Vector(badClaim)))
    assert(evidenceViolations(tampered).nonEmpty)
    val unsupportedNumber = r.response.claims.head.copy(
      text = "Alice Archer has 77 distinct coauthors."
    )
    val tampered2 =
      r.copy(response = r.response.copy(claims = Vector(unsupportedNumber)))
    assert(evidenceViolations(tampered2).exists(_.contains("77")))
    assert(
      reasoningKeysIn(ujson.Obj("a" -> ujson.Obj("Reasoning" -> "x"))).nonEmpty
    )
  }

  test("oracle self-check against hand-computed values") {
    assertEquals(o2.distinctCount("al"), 4)
    assertEquals(o2.pubs("al"), 4)
    assertEquals(o2.weighted("al"), 5.0)
    assertEquals(o1.pubs("al"), 5)
    assertEquals(o1.maxCo("al"), 4)
    assertEquals(o1.maxWorks("al"), Vector("p04"))
    assertEquals(o1.weighted("fa"), 5.0)
    assertEquals(o2.componentSize("al"), 10)
    assertEquals(o2.componentSize("ko"), 3)
    assertEquals(o2.uniquePath("al", "gr"), Vector("al", "dm", "jo", "gr"))
    assertEquals(o2.uniquePath("al", "in").size - 1, 4)
    assertEquals(o2.shortestPaths("br", "in").head.size - 1, 5)
    assertEquals(o2.reach("al").contains("ko"), false)
    assertEquals(o2.crossEdgeList("a").size, 2)
    assertEquals(o2.internalEdges("b").size, 5)
  }

  test("benchmark tables: graph retrieval and semantic retrieval") {
    assert(questions.size >= 30, s"only ${questions.size} questions")
    val scored = questions.map(score)
    val categories = questions.map(_.category).distinct
    val lines = Vector.newBuilder[String]
    lines += "TABLE 1: deterministic graph retrieval (stub model, local fakes)"
    lines += f"${"category"}%-12s ${"n"}%3s ${"outcome"}%8s ${"facts"}%6s ${"answer"}%7s ${"pass"}%5s"
    categories.foreach { c =>
      val rows = scored.filter(_.q.category == c)
      lines += f"$c%-12s ${rows.size}%3d ${rows.count(_.outcomeOk)}%8d ${rows.count(_.factsOk)}%6d ${rows.count(_.answerOk)}%7d ${rows.count(_.pass)}%5d"
    }
    lines += f"${"TOTAL"}%-12s ${scored.size}%3d ${scored.count(_.outcomeOk)}%8d ${scored.count(_.factsOk)}%6d ${scored.count(_.answerOk)}%7d ${scored.count(_.pass)}%5d"
    scored.filterNot(_.pass).foreach { s =>
      lines += s"FAIL ${s.q.id}: facts=${s.failedChecks} problems=${s.answerProblems}"
    }
    val sem = semanticQs.map(sq => sq -> scoreSemantic(sq))
    val wf = workflowSemantic.map(ws => ws -> scoreWorkflowSemantic(ws))
    lines += ""
    lines += "TABLE 2: semantic retrieval (hash embedder, local vector fake)"
    lines += f"${"kind"}%-22s ${"n"}%3s ${"pass"}%5s"
    lines += f"${"index top-k / filters"}%-22s ${sem.size}%3d ${sem.count(_._2._1)}%5d"
    lines += f"${"workflow chunk present"}%-22s ${wf.size}%3d ${wf.count(_._2._1)}%5d"
    sem.filterNot(_._2._1).foreach(x => lines += s"FAIL ${x._1.id}: ${x._2._2}")
    wf.filterNot(_._2._1).foreach(x => lines += s"FAIL ${x._1.id}: ${x._2._2}")
    val text = lines.result().mkString("\n")
    println(text)
    val out = Paths.get("target", "phase9-benchmark.txt")
    Files.createDirectories(out.getParent)
    Files.write(out, text.getBytes(UTF_8))
    assertEquals(scored.count(_.pass), scored.size, text)
  }
