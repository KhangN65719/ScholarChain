package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.evaluation.benchmark.*

/** Independent oracles for the benchmark label rules of plan.md "Benchmark
  * questions and labels" and decisions P16-10 and RB1-A, on hand-built export
  * directories (the `JsonlExport` layout: `<table>/part-00000.jsonl` plus
  * `export-manifest.json`) read through `BenchTables.load`.
  *
  * Every expectation is computed here from the fixture rows, never taken from
  * the generator. Where the specification leaves a choice (a subject the
  * generator may decline to label, for example) a test says so and accepts
  * either a refusal or a complete label, never a truncated one.
  *
  * The abstention class-to-runtime-code mapping is the published table of
  * experiments/benchmark/README.md ("Abstention classes and runtime codes"),
  * written out again below; the abstention tests check every unanswerable
  * label's `expectedCodes` against that table.
  */
class P16LabelRulesSuite extends FunSuite:
  private val run = "run:v1:fixture"
  private val w15 = "2015-2019"
  private val w20 = "2020-2029"
  private val metricNames = Vector(
    "publicationCount",
    "distinctCoauthorCount",
    "weightedDegree",
    "maximumCoauthorsOnOnePaper",
    "coreness",
    "bridgeScore"
  )
  private val sixClasses = Set(
    "unknown-researcher",
    "future-window",
    "ambiguous-name",
    "out-of-scope",
    "unrankable-metric",
    "missing-second-window"
  )

  private def rid(n: Int): String = f"researcher:v1:orcid:R$n%02d"

  // ---- rows -------------------------------------------------------------

  private def opt(s: Option[String]): ujson.Value =
    s.fold[ujson.Value](ujson.Null)(x => ujson.Str(x))
  private def optInt(i: Option[Int]): ujson.Value =
    i.fold[ujson.Value](ujson.Null)(x => ujson.Num(x.toDouble))

  private def researcher(
      id: String,
      name: String,
      kind: String = "researcher"
  ): ujson.Value =
    ujson.Obj(
      "nodeId" -> ujson.Str(id),
      "nodeKind" -> ujson.Str(kind),
      "displayName" -> ujson.Str(name)
    )

  private def metrics(
      id: String,
      window: String,
      values: Map[String, Double]
  ): ujson.Value =
    val o = ujson.Obj(
      "researcherId" -> ujson.Str(id),
      "window" -> ujson.Str(window)
    )
    values.foreach((k, v) => o(k) = ujson.Num(v))
    o

  private def edge(
      id: String,
      a: String,
      b: String,
      window: String,
      joint: Int
  ): ujson.Value =
    ujson.Obj(
      "edgeId" -> ujson.Str(id),
      "researcherA" -> ujson.Str(a),
      "researcherB" -> ujson.Str(b),
      "window" -> ujson.Str(window),
      "jointPublicationCount" -> ujson.Num(joint.toDouble)
    )

  private def hopRow(
      pair: String,
      window: String,
      src: String,
      tgt: String,
      status: String,
      length: Option[Int],
      hop: Option[Int],
      from: Option[String],
      to: Option[String],
      edgeId: Option[String]
  ): ujson.Value =
    ujson.Obj(
      "pairKey" -> ujson.Str(pair),
      "window" -> ujson.Str(window),
      "sourceId" -> ujson.Str(src),
      "targetId" -> ujson.Str(tgt),
      "status" -> ujson.Str(status),
      "pathLength" -> optInt(length),
      "hopIndex" -> optInt(hop),
      "fromId" -> opt(from),
      "toId" -> opt(to),
      "edgeId" -> opt(edgeId)
    )

  private def member(
      id: String,
      window: String,
      group: String,
      size: Int,
      kind: String
  ): ujson.Value =
    ujson.Obj(
      "researcherId" -> ujson.Str(id),
      "window" -> ujson.Str(window),
      s"${kind}Id" -> ujson.Str(group),
      s"${kind}Size" -> ujson.Num(size.toDouble)
    )

  private val tableNames = Vector(
    "nodes-researcher",
    "researcher-metrics",
    "edges-coauthored",
    "edge-support",
    "path-evidence",
    "community-membership",
    "component-membership"
  )

  private def writeWorld(tables: Map[String, Vector[ujson.Value]]): Path =
    val dir = Files.createTempDirectory("p16-labels-")
    tableNames.foreach { name =>
      val d = Files.createDirectory(dir.resolve(name))
      val rows = tables.getOrElse(name, Vector.empty)
      Files.write(
        d.resolve("part-00000.jsonl"),
        rows.map(r => ujson.write(r) + "\n").mkString.getBytes(UTF_8)
      )
    }
    Files.write(
      dir.resolve("export-manifest.json"),
      s"""{"sourceRunId":"$run","tables":{}}""".getBytes(UTF_8)
    )
    dir

  private def gen(
      dir: Path,
      counts: Map[String, Int],
      seed: Long = BenchConfig.DefaultSeed
  ): BenchResult =
    BenchmarkGen.generate(
      BenchTables.load(dir),
      BenchConfig(seed, "v1", counts),
      VectorCoverage.unknown
    )

  private def tier3(joint: Int): Int =
    if joint >= 3 then 3 else if joint == 2 then 2 else 1

  // ---- coauthor worlds: one clean-named hub, unreadable neighbour names so
  // only the hub is eligible ---------------------------------------------------

  private def edgeIdOf(i: Int): String =
    if i % 2 == 0 then s"coauthor:v1:e$i" else s"mention-coauthor:v1:e$i"
  private def jointOf(i: Int): Int = 1 + (i % 3)

  /** Hub R01 with `n` neighbours R02..R(n+1) alternating both edge prefixes,
    * plus one edge in another window that must not be listed.
    */
  private def hubWorld(n: Int): Path =
    val hub = rid(1)
    val nbrs = (2 to n + 1).toVector
    writeWorld(
      Map(
        "nodes-researcher" -> (researcher(hub, "Alice Smith") +:
          nbrs.map(i => researcher(rid(i), f"neighbor$i%02d"))),
        "researcher-metrics" -> (hub +: nbrs.map(rid)).map(id =>
          metrics(id, w15, Map("publicationCount" -> 3.0))
        ),
        "edges-coauthored" -> (nbrs.map(i =>
          edge(edgeIdOf(i), hub, rid(i), w15, jointOf(i))
        ) :+ edge("mention-coauthor:v1:other", hub, rid(99), w20, 3))
      )
    )

  private def hubNeighbours(n: Int): Map[String, Int] =
    (2 to n + 1).map(i => rid(i) -> jointOf(i)).toMap

  test("coauthor lookup lists coauthor: and mention-coauthor: edges, tiered"):
    val r = gen(hubWorld(4), Map("coauthor-lookup" -> 1))
    assertEquals(r.items.size, 1, s"${r.shortfalls}")
    val l = r.items.head.label
    assertEquals(l.subjectIds, Vector(rid(1)))
    val got = l.expected.items.map(i => i.id -> i.grade).toMap
    val want = hubNeighbours(4).map((id, j) => id -> tier3(j))
    assertEquals(got, want)
    assertEquals(l.expected.items.size, 4)
    assert(!got.contains(rid(99)), "an edge of another window was listed")
    assert(!l.expected.abstain)

  test("researcher lookup grades the subject 2 and every coauthor edge 1"):
    val r = gen(hubWorld(4), Map("researcher-lookup" -> 1))
    assertEquals(r.items.size, 1, s"${r.shortfalls}")
    val got = r.items.head.label.expected.items.map(i => i.id -> i.grade).toMap
    val want = hubNeighbours(4).keys.map(_ -> 1).toMap + (rid(1) -> 2)
    assertEquals(got, want)

  for n <- Vector(12, 20, 25, 30) do
    test(
      s"coauthor lookup is not truncated below the runtime limit of 25 (n=$n)"
    ):
      val r = gen(hubWorld(n), Map("coauthor-lookup" -> 1))
      // The generator accepts 2 to 25 coauthors, so those hubs must be
      // labelled; only 26 or more may be refused.
      if n <= 25 then assertEquals(r.items.size, 1, s"${r.shortfalls}")
      r.items.foreach { i =>
        val ids = i.label.expected.items.map(_.id)
        assertEquals(ids.distinct.size, ids.size)
        assert(ids.size >= math.min(n, 25), s"only ${ids.size} of $n coauthors")
        if n <= 25 then assertEquals(ids.toSet, hubNeighbours(n).keySet)
      }

  for n <- Vector(12, 20, 25, 30) do
    test(
      s"researcher lookup keeps every coauthor up to the limit of 25 (n=$n)"
    ):
      val r = gen(hubWorld(n), Map("researcher-lookup" -> 1))
      if n <= 25 then assertEquals(r.items.size, 1, s"${r.shortfalls}")
      r.items.foreach { i =>
        val ids = i.label.expected.items.map(_.id).filter(_ != rid(1))
        assert(ids.size >= math.min(n, 25), s"only ${ids.size} of $n coauthors")
        if n <= 25 then assertEquals(ids.toSet, hubNeighbours(n).keySet)
      }

  private def communityWorld(size: Int): Path =
    val ids = (1 to size).map(rid)
    writeWorld(
      Map(
        "nodes-researcher" -> (researcher(rid(1), "Alice Smith") +:
          (2 to size).toVector.map(i => researcher(rid(i), f"member$i%02d"))),
        "researcher-metrics" -> Vector(
          metrics(rid(1), w15, Map("publicationCount" -> 3.0))
        ),
        "community-membership" -> ids.toVector.map(id =>
          member(id, w15, "c1", size, "community")
        )
      )
    )

  for size <- Vector(8, 25, 30) do
    test(s"community label lists every other member of a group of $size"):
      val r = gen(communityWorld(size), Map("community-component" -> 1))
      if size == 8 then assertEquals(r.items.size, 1, s"${r.shortfalls}")
      r.items.filter(_.label.operation == "community").foreach { i =>
        val others = i.label.expected.items.filter(_.grade == 2).map(_.id)
        assertEquals(others.toSet, (2 to size).map(rid).toSet)
        assertEquals(others.size, size - 1)
      }

  // ---- ranking ------------------------------------------------------------

  private val distinct =
    Vector(40, 37, 35, 31, 30, 26, 25, 22, 18, 15, 13, 9, 6, 3).map(_.toDouble)
  private val paired =
    Vector(12, 12, 10, 10, 8, 8, 6, 6, 4, 4, 2, 2).map(_.toDouble)

  /** `base` rotated by `shift * metricIndex` per metric, plus one mention
    * researcher with a huge value that must never be ranked.
    */
  private def rankWorld(base: Vector[Double], shift: Int): Path =
    val n = base.size
    val ids = (1 to n).map(rid)
    val rows = ids.zipWithIndex.map { (id, i) =>
      metrics(
        id,
        w15,
        metricNames.zipWithIndex
          .map((m, j) => m -> base((i + shift * j) % n))
          .toMap
      )
    }.toVector :+ metrics(
      "mention:v1:m1",
      w15,
      metricNames.map(_ -> 999.0).toMap
    )
    writeWorld(
      Map(
        "nodes-researcher" -> (ids.toVector.zipWithIndex.map((id, i) =>
          researcher(id, f"Name$i%02d Person")
        ) :+ researcher("mention:v1:m1", "Mia Mention", "unresolved-mention")),
        "researcher-metrics" -> rows
      )
    )

  private def values(
      base: Vector[Double],
      shift: Int,
      m: String
  ): Map[String, Double] =
    val j = metricNames.indexOf(m)
    base.indices.map(i => rid(i + 1) -> base((i + shift * j) % base.size)).toMap

  /** (id, rank) with ties sharing 1 + the number of strictly larger values. */
  private def oracle(v: Map[String, Double], n: Int): Vector[(String, Int)] =
    val sorted = v.toVector.sortBy((id, x) => (-x, id))
    sorted
      .map((id, x) => id -> (1 + v.values.count(_ > x)))
      .filter(_._2 <= n)

  private def tierRank(rank: Int): Int =
    if rank <= 3 then 3 else if rank <= 6 then 2 else 1

  private def askedN(text: String): Int =
    """top (\d+) researchers""".r.findFirstMatchIn(text).get.group(1).toInt

  private def rankingLabels(r: BenchResult): Vector[BenchItem] =
    r.items.filter(_.label.stratum == "ranking")

  private val metricPhrases = Vector(
    "number of publications" -> "publicationCount",
    "number of distinct coauthors" -> "distinctCoauthorCount",
    "weighted degree" -> "weightedDegree",
    "largest team size" -> "maximumCoauthorsOnOnePaper",
    "coreness" -> "coreness",
    "bridge score" -> "bridgeScore"
  )

  private def namedMetric(text: String): Option[String] =
    metricPhrases.filter((p, _) => text.contains(p)).map(_._2) match
      case Vector(m) => Some(m)
      case _         => None

  /** Checks one ranking label against the oracle of the metric its question
    * names (not any metric); returns that metric when the label matches it and
    * an empty vector otherwise.
    */
  private def matching(
      item: BenchItem,
      base: Vector[Double],
      shift: Int
  ): Vector[String] =
    val n = askedN(item.question.text)
    val got = item.label.expected.items.map(i => i.id -> i.grade)
    val named = namedMetric(item.question.text)
    assert(named.isDefined, s"no single metric named in ${item.question.text}")
    named.toVector.filter { m =>
      val want =
        oracle(values(base, shift, m), n).map((id, rk) => id -> tierRank(rk))
      got == want
    }

  test(
    "ranking labels exactly N items with grades 3 for ranks 1-3, 2 for 4-6, 1 for 7-10"
  ):
    val r = gen(rankWorld(distinct, 1), Map("ranking" -> 5))
    val labels = rankingLabels(r)
    assertEquals(labels.size, 5, s"${r.shortfalls}")
    labels.foreach { i =>
      val n = askedN(i.question.text)
      assertEquals(i.label.expected.items.size, n, i.question.text)
      assert(
        matching(i, distinct, 1).nonEmpty,
        s"no metric's tiered top $n matches ${i.label.expected.items}"
      )
      assert(!i.label.expected.items.exists(_.id.startsWith("mention:")))
      assert(!i.label.expected.abstain)
    }
    assert(
      labels.exists(i => askedN(i.question.text) == 10),
      "no top-10 question, so the grade-1 tier was not exercised"
    )

  test(
    "a pair whose tie crosses the cut-off N is refused, never padded or cut"
  ):
    // every value appears twice, so the cut-off at N = 3 or 5 splits a tie
    val r = gen(rankWorld(paired, 2), Map("ranking" -> 5))
    val labels = rankingLabels(r)
    // No count is pinned: how many questions the generator asks for depends on
    // its private limits. What the plan and this fixture fix is that a label,
    // when there is one, has N <= 10 items and its cut-off (between the values
    // at places N and N + 1) does not split a tied pair. Dependency: that
    // `labels.nonEmpty` holds depends on the generator asking an even N on the
    // `paired` fixture (an odd N splits a pair); its N values come from the
    // private `rankLimits` (BenchmarkGen.scala:406), and plan.md names no N.
    assert(labels.nonEmpty, s"no label at all: ${r.shortfalls}")
    labels.foreach { i =>
      val n = askedN(i.question.text)
      assert(n >= 1 && n <= 10, i.question.text)
      val sortedVals =
        values(paired, 2, namedMetric(i.question.text).get).values.toVector
          .sorted(using Ordering[Double].reverse)
      assert(
        sortedVals(n - 1) != sortedVals(n),
        s"label for $n splits a tied pair: ${i.question.text}"
      )
      assertEquals(i.label.expected.items.size, n, i.question.text)
      assert(matching(i, paired, 2).nonEmpty, s"${i.label.expected.items}")
    }

  test("a tie inside the top N is kept: ranks share, exactly N items, tiered"):
    val base = Vector(40.0, 40.0) ++ distinct.drop(2)
    val r = gen(rankWorld(base, 0), Map("ranking" -> 5))
    val labels = rankingLabels(r)
    assertEquals(labels.size, 5, s"${r.shortfalls}")
    labels.foreach { i =>
      val n = askedN(i.question.text)
      assertEquals(i.label.expected.items.size, n)
      assert(matching(i, base, 0).nonEmpty, s"${i.label.expected.items}")
    }
    val five = labels.filter(i => askedN(i.question.text) == 5).head
    assertEquals(five.label.expected.items.map(_.grade), Vector(3, 3, 3, 2, 2))

  // ---- paths --------------------------------------------------------------

  private val e12 = "coauthor:v1:e12"
  private val e23 = "coauthor:v1:e23"

  private def pathWorld: Path =
    val ids = (1 to 4).map(rid)
    def hop(pair: String, a: Int, b: Int, e: String) =
      hopRow(
        s"$w15|$pair",
        w15,
        rid(a),
        rid(b),
        "found",
        Some(1),
        Some(1),
        Some(rid(a)),
        Some(rid(b)),
        Some(e)
      )
    writeWorld(
      Map(
        "nodes-researcher" -> ids.toVector
          .zip(
            Vector("Alice Smith", "Bob Jones", "Carol White", "Dan Brown")
          )
          .map((id, n) => researcher(id, n)),
        "researcher-metrics" -> ids.toVector.map(id =>
          metrics(id, w15, Map("publicationCount" -> 3.0))
        ),
        "path-evidence" -> Vector(
          hop("a", 1, 2, e12),
          hop("b", 2, 3, e23),
          hopRow(
            s"$w15|c",
            w15,
            rid(3),
            rid(4),
            "unreachable",
            None,
            None,
            None,
            None,
            None
          )
        )
      )
    )

  private def pathLabels: Vector[Label] =
    gen(pathWorld, Map("shortest-path" -> 3)).items.map(_.label)

  private val knownPairs: Map[Set[String], Vector[String]] = Map(
    Set(rid(1), rid(2)) -> Vector(e12),
    Set(rid(2), rid(3)) -> Vector(e23)
  )

  test("a path between precomputed endpoints labels its hop edges at grade 1"):
    val found = pathLabels.filter(l => knownPairs.contains(l.subjectIds.toSet))
    assertEquals(found.size, 2)
    found.foreach { l =>
      assertEquals(
        l.expected.items.map(i => i.id -> i.grade),
        knownPairs(l.subjectIds.toSet).map(_ -> 1)
      )
      assert(!l.expected.abstain)
    }

  test("an absent-pair path label does not abstain and expects NotPrecomputed"):
    val absent =
      pathLabels.filterNot(l => knownPairs.contains(l.subjectIds.toSet))
    assertEquals(absent.size, 1, s"$pathLabels")
    val l = absent.head
    assert(!l.expected.abstain, "the absent pair must not be an abstention")
    assertEquals(l.expected.abstainClass, None)
    assert(
      ujson.write(l.expected.toJson).contains("NotPrecomputed"),
      ujson.write(l.expected.toJson)
    )
    assertEquals(l.subjectIds.size, 2)
    // the pair with a computed "unreachable" status row is not absent
    assertNotEquals(l.subjectIds.toSet, Set(rid(3), rid(4)))

  // ---- the full world ---------------------------------------------------------

  private val cleanNames = Vector(
    "Alice Smith",
    "Bob Jones",
    "Carol White",
    "Dan Brown",
    "Eve Black",
    "Frank Green",
    "Grace Hall",
    "Henry Lake",
    "Irene Moss",
    "Jack Reed",
    "Kate Stone",
    "Liam Ford",
    "Maya Price",
    "Noah Cole",
    "Olive Hart",
    "Peter Lowe",
    "Quinn Ross",
    "Rita Shaw",
    "Sam Wood",
    "Tina Webb"
  )
  private val twins = Vector(rid(21), rid(22))
  private val allNames: Set[String] =
    (cleanNames :+ "Zoe Adams" :+ "Mia Mention").map(_.toLowerCase).toSet

  private lazy val fullWorld: Path =
    val ids = (1 to 20).map(rid)
    val everyone = ids ++ twins
    val ringEdges =
      for
        w <- Vector(w15, w20)
        i <- 1 to 20
        (k, j) <- Vector(("r", i % 20 + 1), ("c", (i + 4) % 20 + 1))
      yield
        val prefix =
          if (i + (if k == "r" then 0 else 1)) % 2 == 0 then "coauthor:v1:"
          else "mention-coauthor:v1:"
        edge(s"$prefix$k$i-$w", rid(i), rid(j), w, 1 + (i % 3))
    val hops =
      (1 to 6).toVector.map { i =>
        val w = if i % 2 == 0 then w15 else w20
        hopRow(
          s"$w|p$i",
          w,
          rid(i),
          rid(i + 1),
          "found",
          Some(1),
          Some(1),
          Some(rid(i)),
          Some(rid(i + 1)),
          Some(s"coauthor:v1:r$i-$w")
        )
      } :+ hopRow(
        s"$w15|s",
        w15,
        rid(1),
        rid(8),
        "unreachable",
        None,
        None,
        None,
        None,
        None
      )
    writeWorld(
      Map(
        "nodes-researcher" ->
          (ids.toVector.zip(cleanNames).map((id, n) => researcher(id, n)) ++
            twins.map(id => researcher(id, "Zoe Adams")) :+
            researcher("mention:v1:m1", "Mia Mention", "unresolved-mention")),
        "researcher-metrics" -> (for
          w <- Vector(w15, w20)
          (id, i) <- everyone.zipWithIndex.toVector
        yield metrics(
          id,
          w,
          Map(
            "publicationCount" -> (30.0 - i),
            "distinctCoauthorCount" -> ((i * 5) % 17 + 1.0),
            "weightedDegree" -> ((i * 3) % 19 + 1.5),
            "maximumCoauthorsOnOnePaper" -> ((i * 11) % 23 + 1.0),
            "coreness" -> (i % 5 + 1.0),
            "bridgeScore" -> ((i * 13) % 29 + 1.0)
          )
        )),
        "edges-coauthored" -> ringEdges,
        "path-evidence" -> hops,
        "community-membership" -> (for
          w <- Vector(w15, w20)
          (id, i) <- ids.zipWithIndex.toVector
        yield member(id, w, s"c${i / 4}", 4, "community")),
        "component-membership" -> (for
          w <- Vector(w15, w20)
          (id, i) <- ids.zipWithIndex.toVector
        yield member(id, w, s"k${i / 5}", 5, "component"))
      )
    )

  private def full(seed: Long = BenchConfig.DefaultSeed): BenchResult =
    gen(fullWorld, BenchConfig.DefaultCounts, seed)

  private def unanswerable: Vector[BenchItem] =
    gen(fullWorld, Map("unanswerable" -> 8)).items

  test("unanswerable questions abstain, in the six committed classes"):
    val u = unanswerable
    assert(u.size >= 6 && u.size <= 8, s"${u.size} unanswerable questions")
    assert(u.forall(_.label.expected.abstain))
    assert(u.forall(_.label.expected.abstainClass.isDefined))
    assertEquals(u.flatMap(_.label.expected.abstainClass).toSet, sixClasses)

  /** The published table of experiments/benchmark/README.md. */
  private val publishedCodes: Map[String, Vector[String]] = Map(
    "unknown-researcher" -> Vector("identity-unresolved"),
    "ambiguous-name" -> Vector("identity-unresolved"),
    "future-window" -> Vector("no-deterministic-evidence"),
    "out-of-scope" -> Vector("planner-refused"),
    "unrankable-metric" -> Vector("researcher-count"),
    "missing-second-window" -> Vector("needs-two-windows")
  )

  test(
    "every unanswerable label lists the published runtime codes of its class"
  ):
    val u = unanswerable
    assert(u.nonEmpty)
    u.foreach { i =>
      val cls = i.label.expected.abstainClass.get
      val codes = i.label.expected.toJson.obj
        .get("expectedCodes")
        .map(_.arr.toVector.map(_.str))
      assertEquals(
        codes,
        publishedCodes.get(cls),
        s"$cls: ${i.question.text}"
      )
    }

  test("an unknown-researcher question names nobody in the export"):
    val u = unanswerable.filter(
      _.label.expected.abstainClass.contains("unknown-researcher")
    )
    assert(u.nonEmpty)
    u.foreach(i =>
      assert(
        !allNames.exists(n => i.question.text.toLowerCase.contains(n)),
        i.question.text
      )
    )

  test(
    "a future-window question asks only for years after the last export window"
  ):
    val u = unanswerable.filter(
      _.label.expected.abstainClass.contains("future-window")
    )
    assert(u.nonEmpty)
    u.foreach { i =>
      val years =
        """\b(?:19|20)\d{2}\b""".r
          .findAllIn(i.question.text)
          .map(_.toInt)
          .toVector
      assert(years.nonEmpty && years.forall(_ > 2029), i.question.text)
    }

  test("an ambiguous-name question uses a name two researchers share"):
    val u = unanswerable.filter(
      _.label.expected.abstainClass.contains("ambiguous-name")
    )
    assert(u.nonEmpty)
    u.foreach(i =>
      assert(i.question.text.contains("Zoe Adams"), i.question.text)
    )

  test("an out-of-scope question names no researcher and no window"):
    val u = unanswerable.filter(
      _.label.expected.abstainClass.contains("out-of-scope")
    )
    assert(u.nonEmpty)
    u.foreach { i =>
      assert(!allNames.exists(n => i.question.text.toLowerCase.contains(n)))
      assert(i.label.expected.items.isEmpty)
    }

  test("only the unanswerable stratum abstains, an absent path pair included"):
    val r = full()
    r.items.foreach { i =>
      assertEquals(
        i.label.expected.abstain,
        i.question.stratum == "unanswerable",
        s"${i.question.questionId} ${i.question.text}"
      )
    }

  // ---- questions carry no label ---------------------------------------------

  private val questionKeys = Set("questionId", "version", "stratum", "text")

  test(
    "every question object has exactly questionId, version, stratum and text"
  ):
    val r = full()
    assert(r.items.nonEmpty)
    r.items.foreach(i =>
      assertEquals(i.question.toJson.obj.keySet.toSet, questionKeys)
    )
    r.items.foreach { i =>
      assertEquals(i.label.questionId, i.question.questionId)
      assertEquals(i.label.stratum, i.question.stratum)
    }

  test("the written questions file carries no field beyond stratum"):
    val r = full()
    val dir = Files.createTempDirectory("p16-out-")
    BenchmarkGen.writeOutputs(
      r,
      BenchConfig(),
      run,
      None,
      VectorCoverage.unknown,
      Vector.empty,
      dir
    )
    val lines = Files
      .readAllLines(dir.resolve("questions-v1.jsonl"))
      .toArray
      .toVector
      .map(_.toString)
      .filter(_.trim.nonEmpty)
    assertEquals(lines.size, r.items.size)
    lines.foreach(l =>
      assertEquals(ujson.read(l).obj.keySet.toSet, questionKeys, l)
    )
    val labelLines = Files
      .readAllLines(dir.resolve("labels-v1.jsonl"))
      .toArray
      .toVector
      .map(_.toString)
      .filter(_.trim.nonEmpty)
    assertEquals(labelLines.size, lines.size)

  // ---- determinism ----------------------------------------------------------

  private def serial(r: BenchResult): Vector[String] =
    r.items.map(i =>
      ujson.write(i.question.toJson) + "\n" + ujson.write(i.label.toJson)
    )

  test("the same export and seed give identical questions and labels"):
    val a = full()
    val b = full()
    assert(a.items.size >= 40, s"${a.items.size} questions, ${a.shortfalls}")
    assertEquals(serial(a), serial(b))
    assertEquals(a.shortfalls, b.shortfalls)

  test(
    "the written questions and labels files are byte-identical for one seed"
  ):
    def write(): (String, String) =
      val dir = Files.createTempDirectory("p16-det-")
      BenchmarkGen.writeOutputs(
        full(),
        BenchConfig(),
        run,
        None,
        VectorCoverage.unknown,
        Vector.empty,
        dir
      )
      (
        Files.readString(dir.resolve("questions-v1.jsonl")),
        Files.readString(dir.resolve("labels-v1.jsonl"))
      )
    assertEquals(write(), write())

  test("a different seed changes the selection"):
    val a = full(1L).items.map(_.question.text)
    val b = full(2L).items.map(_.question.text)
    assertNotEquals(a, b)
