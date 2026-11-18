package scholarchain.evaluation.benchmark

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Random
import scholarchain.evaluation.io.*

/** Question wording helpers shared by the generator and its tests. */
object Wording:
  /** Words the planner's name extraction skips, copied so the generator never
    * builds a name the planner would split (the planner's own list is private).
    */
  val PlannerStopWords: Set[String] = Set(
    "Who",
    "What",
    "Which",
    "How",
    "Show",
    "Find",
    "List",
    "Compare",
    "Tell",
    "Is",
    "Are",
    "Does",
    "Did",
    "Do",
    "Has",
    "Have",
    "The",
    "A",
    "An",
    "Between",
    "From",
    "And",
    "Or",
    "In",
    "On",
    "Of",
    "To",
    "Since",
    "Give",
    "Please",
    "Why",
    "Where",
    "When",
    "Rank",
    "Ranking",
    "Top",
    "Explain",
    "Describe",
    "Identify",
    "Name",
    "Return",
    "Count",
    "Dr",
    "Prof",
    "Professor",
    "Mr",
    "Ms",
    "Mrs",
    "Can",
    "Could",
    "Would",
    "Will",
    "Also",
    "Ignore",
    "Disregard",
    "Forget",
    "Reveal",
    "Print",
    "Repeat",
    "Say",
    "State",
    "Report",
    "Answer",
    "Run",
    "System",
    "Assistant",
    "Developer",
    "Then",
    "Now",
    "Instead",
    "Treat",
    "Consider",
    "Use",
    "Summarize",
    "Summarise",
    "Get",
    "Make",
    "Let"
  )

  private val orgWords = Vector(
    "Conference",
    "Journal",
    "Symposium",
    "Workshop",
    "Proceedings",
    "University",
    "Institute",
    "College",
    "Laboratory",
    "Lab",
    "School",
    "Academy"
  )

  private val namePattern =
    java.util.regex.Pattern.compile("^\\p{Lu}\\p{Ll}+( \\p{Lu}\\p{Ll}+){1,2}$")

  /** A display name the planner reads back as one researcher name. */
  def cleanName(name: String): Boolean =
    namePattern.matcher(name).matches() &&
      !name.split(" ").exists(PlannerStopWords.contains) &&
      !orgWords.exists(w => name.endsWith(w))

  /** The phrase that makes the planner read a window label back. */
  def windowPhrase(label: String): Option[String] =
    if label == "all" then Some("over all time")
    else if label.matches("\\d{4}-\\d{4}") then
      val parts = label.split("-")
      Some(s"between ${parts(0)} and ${parts(1)}")
    else None

/** Builds the benchmark questions and their labels from the export tables, with
  * no model and no prompt. Same tables, config and coverage give the same
  * output.
  */
final class BenchmarkGen(
    t: BenchTables,
    cfg: BenchConfig,
    coverage: VectorCoverage
):
  import Wording.*

  private val preferredWindows = Vector(
    "2020-2029",
    "2015-2019",
    "2010-2014",
    "2005-2009",
    "2000-2004",
    "all"
  )
  private val presentWindows: Set[String] = t.metrics.map(_.window).toSet
  private val windows: Vector[String] =
    val chosen = preferredWindows.filter(presentWindows.contains)
    if chosen.nonEmpty then chosen else presentWindows.toVector.sorted

  private val byId: Map[String, ResearcherRow] =
    t.researchers.map(r => r.id -> r).toMap
  private val canonical =
    t.researchers.filter(r => !r.id.startsWith("mention:"))
  private val nameCounts: Map[String, Int] =
    canonical.groupBy(_.displayName.trim.toLowerCase).map((k, v) => k -> v.size)
  private val allNames: Set[String] =
    t.researchers.map(_.displayName.trim.toLowerCase).toSet
  private val eligible: Vector[ResearcherRow] = canonical
    .filter(r =>
      r.id.startsWith("researcher:") && cleanName(r.displayName) &&
        nameCounts.getOrElse(r.displayName.trim.toLowerCase, 0) == 1
    )
    .sortBy(_.id)
  private val metricsIdx: Map[(String, String), MetricsRow] =
    t.metrics.map(m => (m.researcherId, m.window) -> m).toMap
  private val supportIdx: Map[String, Vector[String]] =
    t.support
      .groupBy(_.edgeId)
      .map((k, v) => k -> v.map(_.workId).distinct.sorted)

  private final case class Nbr(other: String, joint: Int, edge: CoauthorEdge)

  // Every row of edges-coauthored, `coauthor:v1:` and `mention-coauthor:v1:`
  // alike: the runtime `coauthors-of` template matches every COAUTHORED_WITH
  // edge, so a label that left the mention edges out would miss coauthors the
  // runtime returns. Ordered as the runtime orders them (joint count, then
  // coauthor id); one entry per coauthor.
  private val adjacency: Map[(String, String), Vector[Nbr]] =
    t.edges
      .filter(e => e.a != e.b)
      .flatMap(e =>
        Vector(
          (e.a, e.window) -> Nbr(e.b, e.joint, e),
          (e.b, e.window) -> Nbr(e.a, e.joint, e)
        )
      )
      .groupBy(_._1)
      .map((k, v) =>
        k -> v
          .map(_._2)
          .sortBy(n => (-n.joint, n.other, n.edge.edgeId))
          .distinctBy(_.other)
      )

  private val commIdx: Map[(String, String), MemberRow] =
    t.communities.map(m => (m.researcherId, m.window) -> m).toMap
  private val compIdx: Map[(String, String), MemberRow] =
    t.components.map(m => (m.researcherId, m.window) -> m).toMap
  private def groupMembers(
      rows: Vector[MemberRow]
  ): Map[(String, String), Vector[MemberRow]] =
    rows.groupBy(m => (m.window, m.groupId))
  private val commGroups = groupMembers(t.communities)
  private val compGroups = groupMembers(t.components)

  private val used = mutable.HashSet.empty[String]
  private val shortfalls = Vector.newBuilder[String]

  private def count(stratum: String): Int = cfg.counts.getOrElse(stratum, 0)

  private def rng(index: Int): Random =
    new Random(cfg.seed * 1000003L + index.toLong)

  private def rotate[A](xs: Vector[A], by: Int): Vector[A] =
    if xs.isEmpty then xs
    else
      val k = ((by % xs.size) + xs.size) % xs.size
      xs.drop(k) ++ xs.take(k)

  private def win(window: String): String =
    windowPhrase(window).getOrElse(window)

  private def vecStatus(subject: String, window: String): String =
    coverage.status(subject, window)

  private def vecAvailable(status: String): Option[Boolean] =
    if status == VectorCoverage.Unknown then None
    else Some(status == VectorCoverage.Available)

  private def itemCoverage(
      items: Vector[ExpectedItem],
      window: String
  ): (Int, Int) =
    val avail =
      items.count(i => vecStatus(i.id, window) == VectorCoverage.Available)
    (avail, items.size)

  private def qid(code: String, n: Int): String = f"Q-$code-$n%02d"

  /** Picks (researcher, window) pairs in a seeded order, one window per
    * question in rotation, never reusing a researcher.
    */
  private def choose(
      stratum: String,
      n: Int,
      offset: Int,
      order: Vector[ResearcherRow]
  )(ok: (ResearcherRow, String) => Boolean): Vector[(ResearcherRow, String)] =
    val cycle = rotate(windows, offset)
    val out = Vector.newBuilder[(ResearcherRow, String)]
    var made = 0
    var k = 0
    while k < n && cycle.nonEmpty do
      val w = cycle(k % cycle.size)
      order.find(r => !used.contains(r.id) && ok(r, w)) match
        case Some(r) =>
          used += r.id
          out += ((r, w))
          made += 1
        case None => ()
      k += 1
    if made < n then shortfalls += s"$stratum: wanted $n, produced $made"
    out.result()

  private def ref(row: MetricsRow): SourceRef = row.ref

  private def source(
      table: String,
      primary: Option[SourceRef],
      rows: Vector[SourceRef]
  ): LabelSource =
    LabelSource(table, t.sourceRunId, primary.map(_.rowSha), rows)

  private def numbersOf(row: MetricsRow): Vector[(String, Double)] =
    row.values.toVector.sortBy(_._1)

  private def researcherLookup(): Vector[BenchItem] =
    val n = count("researcher-lookup")
    val order = rng(1).shuffle(eligible)
    val picked = choose("researcher-lookup", n, 0, order) { (r, w) =>
      metricsIdx
        .get((r.id, w))
        .exists(
          _.values.getOrElse("publicationCount", 0.0) >= 1.0
        ) && adjacency.contains((r.id, w))
    }
    picked.zipWithIndex.map { case ((r, w), i) =>
      val row = metricsIdx((r.id, w))
      // 25 is the coauthor-lookup limit, not a limit of this operation: the
      // runtime's default retrieve branch returns metrics only (README,
      // "Threats to validity").
      val nbrs = adjacency((r.id, w)).take(RuntimeLimits.CoauthorLimit)
      val items = ExpectedItem(r.id, 2) +:
        nbrs.map(x => ExpectedItem(x.other, 1))
      val status = vecStatus(r.id, w)
      val (avail, total) = itemCoverage(items, w)
      val q = Question(
        qid("RL", i + 1),
        cfg.version,
        "researcher-lookup",
        s"Who is ${r.displayName} ${win(w)}?"
      )
      BenchItem(
        q,
        Label(
          q.questionId,
          cfg.version,
          q.stratum,
          "researcher-lookup",
          Vector(r.id),
          Some(w),
          Vector(w),
          Expected(items, numbersOf(row), false, None),
          vecAvailable(status),
          status,
          avail,
          total,
          source(
            "researcher-metrics",
            Some(row.ref),
            row.ref +: nbrs.map(_.edge.ref)
          )
        )
      )
    }

  private def tier(joint: Int): Int = Grades.jointTier(joint)

  private def coauthorLookup(): Vector[BenchItem] =
    val n = count("coauthor-lookup")
    val order = rng(2).shuffle(eligible)
    // A subject with more coauthors than the runtime lists (25) is not used, so
    // the label never holds coauthors the runtime cannot return.
    val picked = choose("coauthor-lookup", n, 1, order) { (r, w) =>
      adjacency
        .get((r.id, w))
        .exists(a => a.size >= 2 && a.size <= RuntimeLimits.CoauthorLimit)
    }
    picked.zipWithIndex.map { case ((r, w), i) =>
      val nbrs = adjacency((r.id, w))
      val items = nbrs.map(x => ExpectedItem(x.other, tier(x.joint)))
      val status = vecStatus(r.id, w)
      val (avail, total) = itemCoverage(items, w)
      val edgeInfo = nbrs.map { x =>
        Js.obj(
          Vector(
            "edgeId" -> Js.s(x.edge.edgeId),
            "other" -> Js.s(x.other),
            "joint" -> Js.l(x.joint.toLong),
            "workIds" -> Js.arr(
              supportIdx.getOrElse(x.edge.edgeId, Vector.empty).map(Js.s)
            )
          )
        )
      }
      val q = Question(
        qid("CA", i + 1),
        cfg.version,
        "coauthor-lookup",
        s"Who are the coauthors of ${r.displayName} ${win(w)}?"
      )
      BenchItem(
        q,
        Label(
          q.questionId,
          cfg.version,
          q.stratum,
          "coauthor-lookup",
          Vector(r.id),
          Some(w),
          Vector(w),
          Expected(
            items,
            Vector(
              "coauthorCount" -> nbrs.size.toDouble,
              "maxJoint" -> nbrs.map(_.joint).max.toDouble
            ),
            false,
            None,
            Vector("edges" -> Js.arr(edgeInfo))
          ),
          vecAvailable(status),
          status,
          avail,
          total,
          source(
            "edges-coauthored",
            nbrs.headOption.map(_.edge.ref),
            nbrs.map(_.edge.ref)
          )
        )
      )
    }

  private val rankMetrics: Vector[(String, String)] = Vector(
    "publicationCount" -> "number of publications",
    "distinctCoauthorCount" -> "number of distinct coauthors",
    "weightedDegree" -> "weighted degree",
    "maximumCoauthorsOnOnePaper" -> "largest team size",
    "coreness" -> "coreness",
    "bridgeScore" -> "bridge score"
  )

  private val rankLimits = Vector(5, 5, 3, 10, 5)

  private val metricsByWindow: Map[String, Vector[MetricsRow]] =
    t.metrics.groupBy(_.window)

  /** Top-N of one metric in one window as the runtime ranks it
    * (`InMemoryMetricRanking.top`, `Ports.scala:64-71`): mentions left out,
    * exactly the first N entries by (value descending, researcher id), a rank
    * of 1 + the number of strictly larger values, so equal values inside the
    * top N share a rank. The label holds exactly N items.
    *
    * None (the metric and window pair is refused) when the cut-off N falls
    * inside a tie. The runtime breaks that tie by researcher id
    * (`Ports.scala:66-67`), so it is deterministic, but the members inside the
    * top N would then be decided by id order and not by the metric; when fewer
    * than N researchers are ranked; or when the top value is not positive.
    */
  def ranking(
      metric: String,
      window: String,
      n: Int
  ): Option[(Vector[(MetricsRow, Double, Int)], Int)] =
    val scored = metricsByWindow
      .getOrElse(window, Vector.empty)
      .filter(m => !m.researcherId.startsWith("mention:"))
      .flatMap(m => m.values.get(metric).map(v => (m, v)))
      .sortBy((m, v) => (-v, m.researcherId))
    if n < 1 || scored.size < n then None
    else if scored.size > n && scored(n)._2 == scored(n - 1)._2 then None
    else
      val values = scored.map(_._2)
      val entries =
        scored.take(n).map((m, v) => (m, v, 1 + values.indexOf(v)))
      if entries.head._2 <= 0.0 then None else Some((entries, scored.size))

  private def rankingQuestions(): Vector[BenchItem] =
    val n = count("ranking")
    val out = Vector.newBuilder[BenchItem]
    val seen = mutable.HashSet.empty[(String, String)]
    var made = 0
    var k = 0
    while k < n do
      val (metric, phrase) = rankMetrics(k % rankMetrics.size)
      val limit = rankLimits(k % rankLimits.size)
      val cycle = rotate(windows, k)
      val found = cycle.iterator
        .filter(w => !seen.contains((metric, w)))
        .map(w => (w, ranking(metric, w, limit)))
        .collectFirst { case (w, Some(r)) => (w, r) }
      found.foreach { case (w, (entries, total)) =>
        seen += ((metric, w))
        made += 1
        val items =
          entries.map((m, _, rank) =>
            ExpectedItem(m.researcherId, Grades.ranking(rank))
          )
        val q = Question(
          qid("RK", made),
          cfg.version,
          "ranking",
          s"Who are the top $limit researchers by $phrase ${win(w)}?"
        )
        val (avail, itemTotal) = itemCoverage(items, w)
        out += BenchItem(
          q,
          Label(
            q.questionId,
            cfg.version,
            q.stratum,
            if metric == "bridgeScore" then "bridge-analysis"
            else "collaboration-metrics",
            Vector.empty,
            Some(w),
            Vector(w),
            Expected(
              items,
              Vector(
                "N" -> limit.toDouble,
                "rankedTotal" -> total.toDouble,
                "entriesReturned" -> entries.size.toDouble,
                "topValue" -> entries.head._2
              ),
              false,
              None,
              Vector(
                "metric" -> Js.s(metric),
                "ranking" -> Js.arr(entries.map { (m, v, rank) =>
                  Js.obj(
                    Vector(
                      "id" -> Js.s(m.researcherId),
                      "rank" -> Js.l(rank.toLong),
                      "value" -> Js.d(v)
                    )
                  )
                })
              )
            ),
            None,
            "not-applicable",
            avail,
            itemTotal,
            source(
              "researcher-metrics",
              Some(entries.head._1.ref),
              entries.map(_._1.ref)
            )
          )
        )
      }
      k += 1
    val result = out.result()
    if result.size < n then
      shortfalls += s"ranking: wanted $n, produced ${result.size}"
    result

  private def displayOrId(id: String): String =
    byId.get(id) match
      case Some(r)
          if r.id.startsWith("researcher:") && cleanName(r.displayName) &&
            nameCounts.getOrElse(r.displayName.trim.toLowerCase, 0) == 1 =>
        r.displayName
      case _ => id

  private def pathQuestions(): Vector[BenchItem] =
    val n = count("shortest-path")
    val absentN = n / 3
    val foundN = n - absentN
    val byPair = t.paths.groupBy(_.pairKey)
    val foundPairs = byPair.toVector
      .filter((_, rows) => rows.exists(r => r.edgeId.isDefined))
      .sortBy(_._1)
    val shuffled = rng(4).shuffle(foundPairs)
    val windowsSeen = mutable.HashSet.empty[String]
    val firstPass = shuffled.filter { (_, rows) =>
      windowsSeen.add(rows.head.window)
    }
    val chosen =
      (firstPass ++ shuffled.filterNot(p => firstPass.contains(p))).take(foundN)
    if chosen.size < foundN then
      shortfalls += s"shortest-path (found): wanted $foundN, produced ${chosen.size}"
    val found = chosen.zipWithIndex.map { case ((_, rows), i) =>
      val hops = rows.filter(_.edgeId.isDefined).sortBy(_.hopIndex.getOrElse(0))
      val head = hops.head
      val w = head.window
      val items = hops.map(h => ExpectedItem(h.edgeId.get, 1))
      val q = Question(
        qid("SP", i + 1),
        cfg.version,
        "shortest-path",
        s"What is the shortest path between ${displayOrId(head.sourceId)} and " +
          s"${displayOrId(head.targetId)} ${win(w)}?"
      )
      val status = vecStatus(head.sourceId, w)
      BenchItem(
        q,
        Label(
          q.questionId,
          cfg.version,
          q.stratum,
          "shortest-path",
          Vector(head.sourceId, head.targetId),
          Some(w),
          Vector(w),
          Expected(
            items,
            Vector("pathLength" -> hops.size.toDouble),
            false,
            None,
            Vector(
              "path" -> Js.arr(hops.map { h =>
                Js.obj(
                  Vector(
                    "from" -> Js.opt(h.fromId),
                    "to" -> Js.opt(h.toId),
                    "edgeId" -> Js.opt(h.edgeId)
                  )
                )
              })
            )
          ),
          vecAvailable(status),
          status,
          0,
          0,
          source("path-evidence", Some(head.ref), hops.map(_.ref))
        )
      )
    }
    val pairSet: Set[(String, String, String)] = t.paths.map { r =>
      val (a, b) =
        if r.sourceId <= r.targetId then (r.sourceId, r.targetId)
        else (r.targetId, r.sourceId)
      (r.window, a, b)
    }.toSet
    val endpoints: Map[String, Vector[String]] = t.paths
      .flatMap(r => Vector((r.window, r.sourceId), (r.window, r.targetId)))
      .distinct
      .groupBy(_._1)
      .map((w, xs) => w -> xs.map(_._2).sorted)
    val candidates = endpoints.toVector.sortBy(_._1).flatMap { (w, ids) =>
      for
        i <- ids.indices.toVector
        j <- (i + 1) until ids.size
        if !pairSet.contains((w, ids(i), ids(j)))
      yield (w, ids(i), ids(j))
    }
    val absentWindows = mutable.HashSet.empty[String]
    val shuffledAbsent = rng(5).shuffle(candidates)
    val firstAbsent = shuffledAbsent.filter(c => absentWindows.add(c._1))
    val absentPicked =
      (firstAbsent ++ shuffledAbsent.filterNot(c => firstAbsent.contains(c)))
        .take(absentN)
    if absentPicked.size < absentN then
      shortfalls += s"shortest-path (absent): wanted $absentN, produced ${absentPicked.size}"
    val absent = absentPicked.zipWithIndex.map { case ((w, a, b), i) =>
      val q = Question(
        qid("SP", found.size + i + 1),
        cfg.version,
        "shortest-path",
        s"What is the shortest path between ${displayOrId(a)} and ${displayOrId(b)} ${win(w)}?"
      )
      val status = vecStatus(a, w)
      BenchItem(
        q,
        Label(
          q.questionId,
          cfg.version,
          q.stratum,
          "shortest-path",
          Vector(a, b),
          Some(w),
          Vector(w),
          // The runtime answers a pair that path-evidence does not hold with a
          // `path-status` evidence item of kind NotPrecomputed
          // (`EvidenceBuilder.scala:390-394`), which says nothing about whether
          // a path exists. So the pair is not an abstention: the expected
          // outcome is that status, and an abstention is also scored correct.
          Expected(
            Vector.empty,
            Vector.empty,
            false,
            None,
            Vector(
              "pathStatus" -> Js.s("NotPrecomputed"),
              "acceptedOutcomes" -> Js.arr(
                Vector(
                  Js.s("path-status:NotPrecomputed"),
                  Js.s("abstain")
                )
              )
            )
          ),
          vecAvailable(status),
          status,
          0,
          0,
          LabelSource("path-evidence", t.sourceRunId, None, Vector.empty)
        )
      )
    }
    found ++ absent

  private def groupQuestions(): Vector[BenchItem] =
    val n = count("community-component")
    val componentN = n / 3
    val communityN = n - componentN
    val order = rng(6).shuffle(eligible)
    def build(
        kind: String,
        code: String,
        offset: Int,
        wanted: Int,
        idx: Map[(String, String), MemberRow],
        groups: Map[(String, String), Vector[MemberRow]],
        crossGrade: Boolean,
        firstIndex: Int
    ): Vector[BenchItem] =
      val picked = choose(kind, wanted, offset, order) { (r, w) =>
        idx
          .get((r.id, w))
          .exists(m =>
            m.groupSize >= 3 && m.groupSize <= RuntimeLimits.MaxGroupSize
          )
      }
      picked.zipWithIndex.map { case ((r, w), i) =>
        val m = idx((r.id, w))
        val others = groups
          .getOrElse((w, m.groupId), Vector.empty)
          .map(_.researcherId)
          .filter(_ != r.id)
          .sorted
          .take(RuntimeLimits.MaxGroupSize)
        val cross =
          if crossGrade then
            adjacency
              .getOrElse((r.id, w), Vector.empty)
              .filter(x => idx.get((x.other, w)).exists(_.groupId != m.groupId))
              .map(_.other)
              .distinct
              .filterNot(others.contains)
              .take(RuntimeLimits.CoauthorLimit)
          else Vector.empty
        val items =
          others.map(ExpectedItem(_, 2)) ++ cross.map(ExpectedItem(_, 1))
        val status = vecStatus(r.id, w)
        val (avail, total) = itemCoverage(items, w)
        val text =
          if kind == "community" then
            s"Which community does ${r.displayName} belong to ${win(w)}?"
          else s"What is the connected component of ${r.displayName} ${win(w)}?"
        val q = Question(
          qid(code, firstIndex + i),
          cfg.version,
          "community-component",
          text
        )
        BenchItem(
          q,
          Label(
            q.questionId,
            cfg.version,
            q.stratum,
            kind,
            Vector(r.id),
            Some(w),
            Vector(w),
            Expected(
              items,
              Vector(
                "groupSize" -> m.groupSize.toDouble,
                "membersListed" -> others.size.toDouble
              ),
              false,
              None,
              Vector("groupId" -> Js.s(m.groupId))
            ),
            vecAvailable(status),
            status,
            avail,
            total,
            source(
              if kind == "community" then "community-membership"
              else "component-membership",
              Some(m.ref),
              m.ref +: groups
                .getOrElse((w, m.groupId), Vector.empty)
                .filter(_.researcherId != r.id)
                .sortBy(_.researcherId)
                .take(RuntimeLimits.MaxGroupSize)
                .map(_.ref)
            )
          )
        )
      }
    build("community", "CC", 2, communityN, commIdx, commGroups, true, 1) ++
      build(
        "component",
        "CC",
        3,
        componentN,
        compIdx,
        compGroups,
        false,
        communityN + 1
      )

  private val windowPairs = Vector(
    ("2010-2014", "2015-2019"),
    ("2015-2019", "2020-2029"),
    ("2005-2009", "2010-2014"),
    ("2000-2004", "2005-2009"),
    ("2010-2014", "2020-2029")
  )

  private def temporalQuestions(): Vector[BenchItem] =
    val n = count("temporal-comparison")
    val order = rng(7).shuffle(eligible)
    val out = Vector.newBuilder[BenchItem]
    var made = 0
    var k = 0
    while k < n do
      def has(r: ResearcherRow, w: String): Boolean =
        metricsIdx
          .get((r.id, w))
          .exists(
            _.values.getOrElse("publicationCount", 0.0) >= 1.0
          )
      val found = rotate(windowPairs, k).iterator
        .map((p1, p2) =>
          (
            p1,
            p2,
            order.find(r => !used.contains(r.id) && has(r, p1) && has(r, p2))
          )
        )
        .collectFirst { case (p1, p2, Some(r)) => (p1, p2, r) }
      found match
        case Some((w1, w2, r)) =>
          used += r.id
          made += 1
          val a = metricsIdx((r.id, w1))
          val b = metricsIdx((r.id, w2))
          val numbers = Vector(
            "publicationCount",
            "distinctCoauthorCount",
            "weightedDegree"
          )
            .flatMap { m =>
              Vector(w1 -> a, w2 -> b).flatMap { (w, row) =>
                row.values.get(m).map(v => s"$m[$w]" -> v)
              }
            }
          val items = Vector(ExpectedItem(r.id, 2))
          val status = vecStatus(r.id, w1)
          val q = Question(
            qid("TC", made),
            cfg.version,
            "temporal-comparison",
            s"Compare the publications of ${r.displayName} ${win(w1)} and ${win(w2)}."
          )
          out += BenchItem(
            q,
            Label(
              q.questionId,
              cfg.version,
              q.stratum,
              "temporal-comparison",
              Vector(r.id),
              Some(w1),
              Vector(w1, w2),
              Expected(items, numbers, false, None),
              vecAvailable(status),
              status,
              Vector(w1, w2).count(w =>
                vecStatus(r.id, w) == VectorCoverage.Available
              ),
              2,
              source("researcher-metrics", Some(a.ref), Vector(a.ref, b.ref))
            )
          )
        case None => ()
      k += 1
    val result = out.result()
    if result.size < n then
      shortfalls += s"temporal-comparison: wanted $n, produced ${result.size}"
    result

  private val firstNames = Vector(
    "Zephyrine",
    "Quillon",
    "Marwenna",
    "Thessaly",
    "Orbert",
    "Valdemar",
    "Ysolde",
    "Cadogan"
  )
  private val lastNames = Vector(
    "Pellinghast",
    "Brackenridge",
    "Tolliver",
    "Quarrington",
    "Hasselbeck",
    "Ravenscroft",
    "Lindqvist",
    "Aldermere"
  )

  private def unknownName(r: Random, avoid: Set[String]): Option[String] =
    val names = for f <- firstNames; l <- lastNames yield s"$f $l"
    r.shuffle(names).find(n => !avoid.contains(n.toLowerCase))

  private def abstain(
      code: Int,
      cls: String,
      text: String,
      op: String,
      subjects: Vector[String],
      window: Option[String],
      table: String
  ): BenchItem =
    val q = Question(qid("UA", code), cfg.version, "unanswerable", text)
    BenchItem(
      q,
      Label(
        q.questionId,
        cfg.version,
        q.stratum,
        op,
        subjects,
        window,
        window.toVector,
        Expected(
          Vector.empty,
          Vector.empty,
          true,
          Some(cls),
          Vector(
            "expectedCodes" -> Js.arr(AbstentionCodes.codesFor(cls).map(Js.s))
          )
        ),
        None,
        "not-applicable",
        0,
        0,
        LabelSource(table, t.sourceRunId, None, Vector.empty)
      )
    )

  private def unanswerable(): Vector[BenchItem] =
    val n = math.min(count("unanswerable"), 8)
    val r = rng(8)
    val w = windows.headOption.getOrElse("2015-2019")
    val ambiguous = canonical
      .filter(x =>
        x.id.startsWith("researcher:") && cleanName(x.displayName) &&
          nameCounts.getOrElse(x.displayName.trim.toLowerCase, 0) >= 2
      )
      .map(_.displayName)
      .distinct
      .sorted
    val known = r.shuffle(eligible).headOption
    val avoid = allNames
    val ghost1 = unknownName(r, avoid)
    val ghost2 = unknownName(r, avoid ++ ghost1.map(_.toLowerCase))
    val slots: Vector[Option[BenchItem]] = Vector(
      ghost1.map(g =>
        abstain(
          1,
          "unknown-researcher",
          s"Who are the coauthors of $g ${win(w)}?",
          "coauthor-lookup",
          Vector.empty,
          Some(w),
          "nodes-researcher"
        )
      ),
      Some(
        abstain(
          2,
          "future-window",
          "Who are the top 5 researchers by number of publications between 2031 and 2035?",
          "collaboration-metrics",
          Vector.empty,
          None,
          "researcher-metrics"
        )
      ),
      r.shuffle(ambiguous)
        .headOption
        .map(a =>
          abstain(
            3,
            "ambiguous-name",
            s"Who are the coauthors of $a ${win(w)}?",
            "coauthor-lookup",
            Vector.empty,
            Some(w),
            "nodes-researcher"
          )
        ),
      Some(
        abstain(
          4,
          "out-of-scope",
          "What is the capital of France?",
          "none",
          Vector.empty,
          None,
          "none"
        )
      ),
      Some(
        abstain(
          5,
          "unrankable-metric",
          s"Who are the top 5 researchers by citation count ${win(w)}?",
          "collaboration-metrics",
          Vector.empty,
          Some(w),
          "researcher-metrics"
        )
      ),
      known.map(k =>
        abstain(
          6,
          "missing-second-window",
          s"Compare the publications of ${k.displayName} ${win(w)}.",
          "temporal-comparison",
          Vector(k.id),
          Some(w),
          "researcher-metrics"
        )
      ),
      ghost2.map(g =>
        abstain(
          7,
          "unknown-researcher",
          s"Who is $g ${win(w)}?",
          "researcher-lookup",
          Vector.empty,
          Some(w),
          "nodes-researcher"
        )
      ),
      // A researcher lookup in a future window still answers from the identity
      // record alone (`lookupOnly` in `Workflow.pipeline`), so it is no
      // abstention. A coauthor lookup needs graph evidence, and the window has
      // none.
      known.map(k =>
        abstain(
          8,
          "future-window",
          s"Who are the coauthors of ${k.displayName} between 2040 and 2049?",
          "coauthor-lookup",
          Vector(k.id),
          None,
          "edges-coauthored"
        )
      )
    )
    val wanted = slots.take(n)
    val made = wanted.flatten
    if made.size < n then
      shortfalls += s"unanswerable: wanted $n, produced ${made.size}"
    made

  private def freeFormSlots(): Vector[ujson.Value] =
    (1 to count("free-form")).toVector.map { i =>
      Js.obj(
        Vector(
          "questionId" -> Js.s(qid("FF", i)),
          "version" -> Js.s(cfg.version),
          "stratum" -> Js.s("free-form"),
          "text" -> ujson.Null,
          "intendedOperation" -> ujson.Null,
          "subject" -> ujson.Null,
          "window" -> ujson.Null,
          "status" -> Js.s("pending-human")
        )
      )
    }

  def generate(): BenchResult =
    val items = researcherLookup() ++ coauthorLookup() ++ rankingQuestions() ++
      pathQuestions() ++ groupQuestions() ++ temporalQuestions() ++
      unanswerable()
    BenchResult(items, shortfalls.result(), freeFormSlots())

/** The generator's own git state for the manifest: the commit and whether the
  * working tree had uncommitted changes when the files were generated. The
  * generator is human-run code, so it may call `git`; a missing `git` or a
  * failed call leaves the value unrecorded, never guessed.
  */
object GeneratorState:
  private def git(args: Vector[String]): Option[String] =
    try
      val pb = new ProcessBuilder((Vector("git") ++ args).asJava)
      pb.redirectErrorStream(true)
      val p = pb.start()
      val out = new String(p.getInputStream.readAllBytes(), UTF_8)
      if p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) &&
        p.exitValue() == 0
      then Some(out.trim)
      else None
    catch case _: Exception => None

  /** (HEAD commit, tree dirty) from `git`; None where git cannot say. */
  def probe(): (Option[String], Option[Boolean]) =
    (
      git(Vector("rev-parse", "HEAD")).filter(_.nonEmpty),
      git(Vector("status", "--porcelain")).map(_.nonEmpty)
    )

  private def flag(text: String): Option[Boolean] =
    text.trim.toLowerCase match
      case "yes" | "true" => Some(true)
      case "no" | "false" => Some(false)
      case _              => None

  /** Manifest fields: `generatorCommit`, `generatorTreeDirty` (true, false or
    * null when unrecorded) and `generatorDirtyBasis`. The flags
    * `--generator-commit` and `--generator-dirty yes|no` win over the probe;
    * `git` is called only for what the flags leave open.
    */
  def info(
      opts: Map[String, String],
      probeGit: () => (Option[String], Option[Boolean]) = () => probe()
  ): Vector[(String, ujson.Value)] =
    val commitFlag = opts.get("--generator-commit")
    val dirtyFlag = opts.get("--generator-dirty").flatMap(flag)
    val probed: (Option[String], Option[Boolean]) =
      if commitFlag.isDefined && dirtyFlag.isDefined then (None, None)
      else probeGit()
    val dirty = dirtyFlag.orElse(probed._2)
    Vector(
      "generatorCommit" -> Js.s(
        commitFlag.orElse(probed._1).getOrElse("UNRECORDED")
      ),
      "generatorTreeDirty" -> dirty.fold[ujson.Value](ujson.Null)(Js.b),
      "generatorDirtyBasis" -> Js.s(
        if dirtyFlag.isDefined then "flag --generator-dirty"
        else if probed._2.isDefined then
          "git status --porcelain over the whole working tree at generation"
        else "not recorded: git unavailable and no --generator-dirty flag"
      )
    )

object BenchmarkGen:
  def generate(
      t: BenchTables,
      cfg: BenchConfig,
      coverage: VectorCoverage
  ): BenchResult =
    new BenchmarkGen(t, cfg, coverage).generate()

  val Usage: String =
    "Usage: BenchmarkGen <export dir> <output dir> [--seed N] [--version vN] " +
      "[--vector-export DIR] [--chunkset-manifest FILE] [--generator-commit SHA] " +
      "[--generator-dirty yes|no] [--accept-coverage-mismatch yes] " +
      "(either --vector-export DIR or --no-vector-export yes is required; " +
      "--chunkset-manifest needs --vector-export)"

  val OverrideFlag = "--accept-coverage-mismatch"
  val NoVectorFlag = "--no-vector-export"
  val ExitInputs = 2
  val ExitMismatch = 3

  val KnownOptions: Set[String] = Set(
    "--seed",
    "--version",
    "--vector-export",
    "--chunkset-manifest",
    "--generator-commit",
    "--generator-dirty",
    OverrideFlag,
    NoVectorFlag
  )

  /** A refusal reason for the options, or None. Unknown options, a run with
    * neither `--vector-export` nor `--no-vector-export yes`, both together, and
    * `--chunkset-manifest` without `--vector-export` are all refused.
    */
  def optionError(opts: Map[String, String]): Option[String] =
    val hasVector = opts.contains("--vector-export")
    val noVector = opts.get(NoVectorFlag)
    opts.keys.toVector.sorted
      .find(k => !KnownOptions.contains(k))
      .map(k => s"unknown option $k")
      .orElse(
        if noVector.exists(_ != "yes") then
          Some(s"$NoVectorFlag takes only the value yes")
        else None
      )
      .orElse(
        if hasVector && noVector.isDefined then
          Some(s"--vector-export and $NoVectorFlag are mutually exclusive")
        else None
      )
      .orElse(
        if !hasVector && noVector.isEmpty then
          Some(s"--vector-export DIR is required, or $NoVectorFlag yes")
        else None
      )
      .orElse(
        if !hasVector && opts.contains("--chunkset-manifest") then
          Some("--chunkset-manifest needs --vector-export")
        else None
      )

  /** Names of the manifest checks of the coverage that are false. */
  def failedChecks(coverage: VectorCoverage): Vector[String] =
    coverage.checks.filterNot(_._2).map(_._1)

  /** True when every graph-export summary id and summary subject is also in the
    * vector export (and there is at least one summary). It looks at summaries
    * only; how many graph researchers have a vector subject is reported in the
    * manifest (`subjectIdOverlapWithVectorExport`).
    */
  def summaryOverlapComplete(
      summaryIdsShared: Long,
      graphSummaries: Long,
      subjects: SubjectOverlap
  ): Boolean =
    graphSummaries > 0L && summaryIdsShared == graphSummaries &&
      subjects.shared == subjects.graphSummarySubjects

  /** The command-line lines for the vector coverage checks: the match flags,
    * and a `VECTOR_COVERAGE MISMATCH` line naming every manifest check that is
    * false. `summaryOverlap` is informational and never part of the mismatch
    * line.
    */
  def coverageLines(
      coverage: VectorCoverage,
      summaryOverlap: Option[Boolean]
  ): Vector[String] =
    val checks = coverage.checks
    if checks.isEmpty then
      Vector(
        s"VECTOR_COVERAGE not checked: no vector export ($NoVectorFlag yes)"
      )
    else
      val flags = checks.map((k, v) => s"$k=$v") ++
        Vector(s"summaryOverlap=${summaryOverlap.fold("n/a")(_.toString)}")
      val failed = checks.filterNot(_._2).map(_._1)
      Vector(s"VECTOR_COVERAGE ${flags.mkString(" ")}") ++
        (if failed.isEmpty then Vector.empty
         else Vector(s"VECTOR_COVERAGE MISMATCH: ${failed.mkString(", ")}"))

  private def jsonl(rows: Vector[ujson.Value]): String =
    rows
      .map(r => ujson.write(r))
      .mkString("", "\n", if rows.isEmpty then "" else "\n")

  /** Writes the versioned files; refuses to overwrite any of them. */
  def writeOutputs(
      result: BenchResult,
      cfg: BenchConfig,
      sourceRunId: String,
      exportManifestSha: Option[String],
      coverage: VectorCoverage,
      extra: Vector[(String, ujson.Value)],
      outDir: Path
  ): Unit =
    Files.createDirectories(outDir)
    val v = cfg.version
    val files = Vector(
      s"questions-$v.jsonl" -> jsonl(result.items.map(_.question.toJson)),
      s"labels-$v.jsonl" -> jsonl(result.items.map(_.label.toJson)),
      s"free-form-slots-$v.jsonl" -> jsonl(result.freeFormSlots)
    )
    files.foreach((name, text) => RunOutput.write(outDir, name, text))
    val strata =
      result.items.groupBy(_.question.stratum).map((k, x) => k -> x.size.toLong)
    val manifest = Js.obj(
      Vector(
        "benchmarkVersion" -> Js.s(v),
        "generatorVersion" -> Js.s(BenchConfig.GeneratorVersion),
        "scalaVersion" -> Js.s(scala.util.Properties.versionNumberString),
        "seed" -> Js.l(cfg.seed),
        "sourceRunId" -> Js.s(sourceRunId),
        "exportManifestSha256" -> Js.opt(exportManifestSha),
        "files" -> Js.arr(files.map { (name, text) =>
          Js.obj(
            Vector(
              "name" -> Js.s(name),
              "sha256" -> Js.s(Digests.sha256(text)),
              "rows" -> Js.l(text.count(_ == '\n').toLong)
            )
          )
        }),
        "generatedQuestions" -> Js.l(result.items.size.toLong),
        "freeFormSlots" -> Js.l(result.freeFormSlots.size.toLong),
        "plannedTotal" ->
          Js.l(result.items.size.toLong + result.freeFormSlots.size.toLong),
        "countsByStratum" -> Js.counts(strata),
        "requestedCounts" -> Js.counts(cfg.counts.map((k, c) => k -> c.toLong)),
        "shortfalls" -> Js.arr(result.shortfalls.map(Js.s)),
        "vectorCoverage" -> coverage.describe
      ) ++ extra
    )
    RunOutput.write(
      outDir,
      s"BENCHMARK-MANIFEST-$v.json",
      ujson.write(manifest, indent = 2) + "\n"
    )

  def main(args: Array[String]): Unit =
    System.exit(cli(args.toVector))

  def cli(args: Vector[String]): Int =
    def options(
        rest: Vector[String],
        acc: Map[String, String]
    ): Option[Map[String, String]] =
      rest match
        case Vector()                                       => Some(acc)
        case flag +: value +: tail if flag.startsWith("--") =>
          options(tail, acc.updated(flag, value))
        case _ => None
    args match
      case dir +: out +: rest =>
        options(rest, Map.empty) match
          case None =>
            println(Usage)
            2
          case Some(opts) =>
            val exportDir = Paths.get(dir)
            val cfg = BenchConfig(
              opts
                .get("--seed")
                .flatMap(_.toLongOption)
                .getOrElse(BenchConfig.DefaultSeed),
              opts.getOrElse("--version", "v1")
            )
            val chunkset = Paths.get(
              opts.getOrElse(
                "--chunkset-manifest",
                "datasets/tier-s/chunks-c/chunkset-manifest.json"
              )
            )
            val vectorDir = opts.get("--vector-export").map(Paths.get(_))
            val inputError: Option[String] = optionError(opts).orElse(
              vectorDir.flatMap { d =>
                if !Files.isDirectory(d) then
                  Some(s"--vector-export $d is not a directory")
                else if !Files.isRegularFile(chunkset) then
                  Some(s"chunk set manifest $chunkset is not a file")
                else None
              }
            )
            val coverageTry: scala.util.Try[VectorCoverage] =
              inputError match
                case Some(_) => scala.util.Success(VectorCoverage.noneByFlag)
                case None    =>
                  scala.util.Try(
                    vectorDir.fold(VectorCoverage.noneByFlag)(d =>
                      VectorCoverage.fromExports(d, chunkset)
                    )
                  )
            val coverage = coverageTry.getOrElse(VectorCoverage.noneByFlag)
            val failed = failedChecks(coverage)
            val accepted = opts.get(OverrideFlag).contains("yes")
            if inputError.isDefined || coverageTry.isFailure then
              val why = inputError.getOrElse(
                s"vector coverage could not be computed: ${coverageTry.failed.get.getMessage}"
              )
              println(s"BENCHMARK ERROR: $why; nothing written")
              ExitInputs
            else if failed.nonEmpty && !accepted then
              val names = failed.mkString(", ")
              coverageLines(coverage, None).foreach(println)
              println(
                s"BENCHMARK REFUSED: vector coverage differs from the chunk set manifest ($names); nothing written. Fix the inputs, or pass $OverrideFlag yes to write and record the failing checks."
              )
              ExitMismatch
            else
              generateAndWrite(
                exportDir,
                out,
                opts,
                cfg,
                vectorDir,
                coverage,
                failed
              )
      case _ =>
        println(Usage)
        2

  private def generateAndWrite(
      exportDir: Path,
      out: String,
      opts: Map[String, String],
      cfg: BenchConfig,
      vectorDir: Option[Path],
      coverage: VectorCoverage,
      failed: Vector[String]
  ): Int =
    val tables = BenchTables.load(exportDir)
    val result = generate(tables, cfg, coverage)
    val manifestPath = exportDir.resolve("export-manifest.json")
    val overlapData = vectorDir.map { d =>
      (
        VectorCoverage.summaryIdOverlap(exportDir, d),
        VectorCoverage.subjectIdOverlap(exportDir, d)
      )
    }
    val overlap = overlapData.toVector.flatMap { (ids, subjects) =>
      Vector(
        "summaryIdOverlapWithVectorExport" -> Js.obj(
          Vector(
            "shared" -> Js.l(ids._1),
            "graphExportSummaries" -> Js.l(ids._2)
          )
        ),
        "subjectIdOverlapWithVectorExport" -> subjects.toJson
      )
    }
    val accepted = failed.nonEmpty && opts.get(OverrideFlag).contains("yes")
    val overrideRecord = Vector(
      "coverageMismatchOverride" -> Js.obj(
        Vector(
          "flag" -> Js.s(OverrideFlag),
          "checked" -> Js.b(coverage.checks.nonEmpty),
          "accepted" -> Js.b(accepted),
          "failedChecks" -> Js.arr(failed.map(Js.s))
        )
      )
    )
    writeOutputs(
      result,
      cfg,
      tables.sourceRunId,
      if Files.isRegularFile(manifestPath) then
        Some(Digests.sha256File(manifestPath))
      else None,
      coverage,
      GeneratorState.info(opts) ++ overlap ++ overrideRecord,
      Paths.get(out)
    )
    println(
      s"BENCHMARK RESULT: ${result.items.size} questions, ${result.freeFormSlots.size} free-form slots, shortfalls ${result.shortfalls.size}"
    )
    result.shortfalls.foreach(s => println(s"shortfall: $s"))
    coverageLines(
      coverage,
      overlapData.map((ids, subjects) =>
        summaryOverlapComplete(ids._1, ids._2, subjects)
      )
    ).foreach(println)
    if accepted then
      val names = failed.mkString(", ")
      println(
        s"VECTOR_COVERAGE OVERRIDE: wrote with $OverrideFlag yes; failing checks recorded in the manifest: $names"
      )
    0
