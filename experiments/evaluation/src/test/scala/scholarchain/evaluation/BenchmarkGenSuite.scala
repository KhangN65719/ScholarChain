package scholarchain.evaluation

import java.nio.file.Files
import munit.FunSuite
import scholarchain.evaluation.benchmark.*

class BenchmarkGenSuite extends FunSuite:
  private val run = "run:v1:fx"
  private val w15 = "2015-2019"
  private val w20 = "2020-2029"

  private def ref(table: String, id: String): SourceRef =
    SourceRef(table, s"$table/part-00000.jsonl", 1, "sha-" + id, id)

  private def rid(n: Int): String = s"researcher:v1:r$n"

  private val names = Vector(
    "Alice Smith",
    "Bob Jones",
    "Carol White",
    "Dan Brown",
    "Eve Black",
    "Frank Green",
    "Zoe Adams",
    "Zoe Adams"
  )

  private val people: Vector[ResearcherRow] =
    names.zipWithIndex.map((n, i) =>
      ResearcherRow(
        rid(i + 1),
        "researcher",
        n,
        ref("nodes-researcher", rid(i + 1))
      )
    ) :+ ResearcherRow(
      "mention:v1:m1",
      "unresolved-mention",
      "Mia Mention",
      ref("nodes-researcher", "mention:v1:m1")
    )

  private val pubs = Map(
    1 -> 10.0,
    2 -> 10.0,
    3 -> 7.0,
    4 -> 5.0,
    5 -> 5.0,
    6 -> 1.0,
    7 -> 2.0,
    8 -> 2.0
  )

  private def metric(id: String, window: String, pub: Double): MetricsRow =
    MetricsRow(
      id,
      window,
      Map("publicationCount" -> pub, "distinctCoauthorCount" -> 2.0),
      ref("researcher-metrics", s"$id|$window")
    )

  private val metrics: Vector[MetricsRow] =
    (1 to 8).toVector.flatMap(n =>
      Vector(w15, w20).map(w => metric(rid(n), w, pubs(n)))
    ) :+ metric("mention:v1:m1", w15, 99.0)

  private def edge(n: Int, a: Int, b: Int, joint: Int): CoauthorEdge =
    CoauthorEdge(
      s"coauthor:v1:e$n",
      rid(a),
      rid(b),
      w15,
      joint,
      ref("edges-coauthored", s"e$n")
    )

  private val edges = Vector(
    edge(1, 1, 2, 3),
    edge(2, 1, 3, 2),
    edge(3, 1, 4, 1),
    edge(4, 2, 3, 1)
  )

  private def member(
      n: Int,
      group: String,
      size: Long,
      window: String
  ): MemberRow =
    MemberRow(rid(n), window, group, size, ref("community-membership", rid(n)))

  private def members(prefix: String): Vector[MemberRow] =
    Vector(w15, w20).flatMap(w =>
      (1 to 3).toVector.map(n => member(n, s"${prefix}1", 3, w))
    ) :+ member(4, s"${prefix}2", 1, w15)

  private def path(pair: String, a: Int, b: Int, edgeId: String): PathRow =
    PathRow(
      s"$w15|$pair",
      w15,
      rid(a),
      rid(b),
      "found",
      Some(1),
      Some(1),
      Some(rid(a)),
      Some(rid(b)),
      Some(edgeId),
      ref("path-evidence", pair)
    )

  private val statusRow = PathRow(
    s"$w15|d",
    w15,
    rid(1),
    rid(4),
    "unreachable-within-bound",
    None,
    None,
    None,
    None,
    None,
    ref("path-evidence", "d")
  )

  private val tables = BenchTables(
    run,
    people,
    metrics,
    edges,
    Vector(
      SupportRow("coauthor:v1:e1", "work:v1:w1"),
      SupportRow("coauthor:v1:e1", "work:v1:w2")
    ),
    Vector(
      path("a", 1, 2, "coauthor:v1:e1"),
      path("b", 1, 3, "coauthor:v1:e2"),
      path("c", 2, 3, "coauthor:v1:e4"),
      statusRow
    ),
    members("c"),
    members("k")
  )

  private def only(stratum: String, n: Int): BenchConfig =
    BenchConfig(counts = Map(stratum -> n))

  private def gen(stratum: String, n: Int): BenchResult =
    BenchmarkGen.generate(tables, only(stratum, n), VectorCoverage.unknown)

  private val joint: Map[(String, String), Int] =
    edges
      .flatMap(e => Vector((e.a, e.b) -> e.joint, (e.b, e.a) -> e.joint))
      .toMap

  test("names the planner cannot read back are not used"):
    assert(Wording.cleanName("Alice Smith"))
    assert(Wording.cleanName("Mary Anne Jones"))
    assert(!Wording.cleanName("Will Smith"))
    assert(!Wording.cleanName("Madonna"))
    assert(!Wording.cleanName("Sun University"))
    assertEquals(Wording.windowPhrase(w15), Some("between 2015 and 2019"))
    assertEquals(Wording.windowPhrase("all"), Some("over all time"))

  test("coauthor labels grade items by the joint count tier"):
    val r = gen("coauthor-lookup", 1)
    assertEquals(r.items.size, 1)
    val label = r.items.head.label
    val subject = label.subjectIds.head
    assert(label.expected.items.nonEmpty)
    label.expected.items.foreach { i =>
      val j = joint((subject, i.id))
      assertEquals(i.grade, if j >= 3 then 3 else if j == 2 then 2 else 1)
    }
    assertEquals(label.window, Some(w15))
    assert(!label.expected.abstain)

  test("ranking shares ranks on ties, leaves out mentions and grades by rank"):
    val g = new BenchmarkGen(tables, only("ranking", 0), VectorCoverage.unknown)
    val (entries, total) = g.ranking("publicationCount", w15, 3).get
    assertEquals(
      entries.map(_._1.researcherId),
      Vector(rid(1), rid(2), rid(3))
    )
    assertEquals(entries.map(_._3), Vector(1, 1, 3))
    assertEquals(total, 8)

  test("ranking labels grade by the tiers 3 / 2 / 1 and hold exactly N items"):
    val r = gen("ranking", 1)
    assertEquals(r.items.size, 1)
    val label = r.items.head.label
    assertEquals(label.stratum, "ranking")
    val n = label.expected.numbers.toMap.apply("N").toInt
    assertEquals(label.expected.items.size, n)
    val ranking = label.expected.extra.toMap.apply("ranking")
    ranking.arr.foreach { e =>
      val id = e("id").str
      val rank = e("rank").num.toInt
      assertEquals(
        label.expected.items.find(_.id == id).get.grade,
        Grades.ranking(rank)
      )
    }
    assert(!label.expected.items.exists(_.id.startsWith("mention:")))

  test("the ranking tiers are 3 for ranks 1-3, 2 for 4-6, 1 for 7-10"):
    assertEquals(
      (1 to 10).map(Grades.ranking).toVector,
      Vector(3, 3, 3, 2, 2, 2, 1, 1, 1, 1)
    )

  test("the first generated ranking of the fixture has ranks 1,1,3,4,4"):
    val label = gen("ranking", 1).items.head.label
    assertEquals(label.expected.items.map(_.grade), Vector(3, 3, 3, 2, 2))
    val ranks = label.expected.extra.toMap
      .apply("ranking")
      .arr
      .map(_("rank").num.toInt)
      .toVector
    assertEquals(ranks, Vector(1, 1, 3, 4, 4))

  test("a ranking of ten has grades 3,3,3,2,2,2,1,1,1,1 and ten items"):
    val values = (1 to 12).toVector
    val six = Vector(
      "publicationCount",
      "distinctCoauthorCount",
      "weightedDegree",
      "maximumCoauthorsOnOnePaper",
      "coreness",
      "bridgeScore"
    )
    val ranked = tables.copy(metrics = values.flatMap { n =>
      Vector(w15, w20).map(w =>
        MetricsRow(
          rid(n),
          w,
          six.map(_ -> (13.0 - n)).toMap,
          ref("researcher-metrics", s"${rid(n)}|$w")
        )
      )
    })
    val items = BenchmarkGen
      .generate(ranked, only("ranking", 4), VectorCoverage.unknown)
      .items
    assertEquals(items.size, 4)
    val ten = items(3).label
    assertEquals(ten.expected.numbers.toMap.apply("N").toInt, 10)
    assertEquals(
      ten.expected.items.map(_.grade),
      Vector(3, 3, 3, 2, 2, 2, 1, 1, 1, 1)
    )

  test("a ranking whose ties fill the answer is refused"):
    val flat = tables.copy(metrics =
      (1 to 8).toVector.map(n => metric(rid(n), w15, 4.0))
    )
    val g = new BenchmarkGen(flat, only("ranking", 0), VectorCoverage.unknown)
    assertEquals(g.ranking("publicationCount", w15, 1), None)

  test("a tie that crosses the cut-off N is refused, a tie inside N is kept"):
    // sorted values: 10, 10, 7, 5, 5, 2, 2, 1 (the mention at 99 is left out)
    val g = new BenchmarkGen(tables, only("ranking", 0), VectorCoverage.unknown)
    assertEquals(g.ranking("publicationCount", w15, 4), None)
    val (five, _) = g.ranking("publicationCount", w15, 5).get
    assertEquals(five.map(_._3), Vector(1, 1, 3, 4, 4))
    val (two, _) = g.ranking("publicationCount", w15, 2).get
    assertEquals(two.map(_._3), Vector(1, 1))
    assertEquals(g.ranking("publicationCount", w15, 9), None)

  test("no generated ranking has a tie at its cut-off"):
    val items = BenchmarkGen
      .generate(tables, only("ranking", 6), VectorCoverage.unknown)
      .items
    items.foreach { i =>
      val n = i.label.expected.numbers.toMap.apply("N").toInt
      assertEquals(i.label.expected.items.size, n)
    }

  test(
    "a path question labels the hop edges, an absent pair is NotPrecomputed"
  ):
    val r = gen("shortest-path", 3)
    val found = r.items.filter(i => i.label.expected.items.nonEmpty)
    val absent = r.items.filter(i => i.label.expected.items.isEmpty)
    assertEquals(found.size, 2)
    assertEquals(absent.size, 1)
    found.foreach(i => assert(i.label.expected.items.forall(_.grade == 1)))
    found.foreach(i => assert(!i.label.expected.abstain))
    val label = absent.head.label
    assert(!label.expected.abstain)
    assertEquals(label.expected.abstainClass, None)
    val extra = label.expected.extra.toMap
    assertEquals(extra("pathStatus").str, "NotPrecomputed")
    assertEquals(
      extra("acceptedOutcomes").arr.map(_.str).toVector,
      Vector("path-status:NotPrecomputed", "abstain")
    )
    val known = Set(
      Set(rid(1), rid(2)),
      Set(rid(1), rid(3)),
      Set(rid(2), rid(3)),
      Set(rid(1), rid(4))
    )
    assert(!known.contains(label.subjectIds.toSet))

  private val mentionNode = "mention:v1:m1"

  private def mentionEdge(
      n: Int,
      a: Int,
      other: String,
      joint: Int,
      window: String = w15
  ) =
    CoauthorEdge(
      s"mention-coauthor:v1:e$n",
      rid(a),
      other,
      window,
      joint,
      ref("edges-coauthored", s"me$n")
    )

  test("coauthor labels include mention-coauthor edges and coauthor edges"):
    // only r1 has two coauthors, one through each edge kind
    val t = tables.copy(edges =
      Vector(edge(1, 1, 2, 3), mentionEdge(2, 1, mentionNode, 2))
    )
    val items = BenchmarkGen
      .generate(t, only("coauthor-lookup", 1), VectorCoverage.unknown)
      .items
    assertEquals(items.size, 1)
    val label = items.head.label
    assertEquals(label.subjectIds, Vector(rid(1)))
    assertEquals(
      label.expected.items.map(i => i.id -> i.grade).toMap,
      Map(rid(2) -> 3, mentionNode -> 2)
    )
    val kinds = label.expected.extra.toMap
      .apply("edges")
      .arr
      .map(_("edgeId").str.takeWhile(_ != ':'))
      .toSet
    assertEquals(kinds, Set("coauthor", "mention-coauthor"))

  test("a researcher lookup lists every coauthor up to the runtime limit"):
    // the researcher lookup tries the first window of the preferred order
    // (2020-2029) first, so the edges sit there
    val many =
      (1 to 20).toVector.map(n => mentionEdge(n, 1, s"mention:v1:x$n", 1, w20))
    val t = tables.copy(edges = many)
    val items = BenchmarkGen
      .generate(t, only("researcher-lookup", 1), VectorCoverage.unknown)
      .items
    assertEquals(items.size, 1)
    // the subject plus 20 coauthors; the cap was 10
    assertEquals(items.head.label.expected.items.size, 21)

  test("a coauthor lookup labels all 25 coauthors and skips a subject with 26"):
    def withCoauthors(k: Int) = tables.copy(edges =
      (1 to k).toVector.map(n => mentionEdge(n, 1, s"mention:v1:y$n", 1))
    )
    val ok = BenchmarkGen.generate(
      withCoauthors(RuntimeLimits.CoauthorLimit),
      only("coauthor-lookup", 1),
      VectorCoverage.unknown
    )
    assertEquals(ok.items.head.label.expected.items.size, 25)
    val over = BenchmarkGen.generate(
      withCoauthors(RuntimeLimits.CoauthorLimit + 1),
      only("coauthor-lookup", 1),
      VectorCoverage.unknown
    )
    assertEquals(over.items.size, 0)
    assert(over.shortfalls.exists(_.startsWith("coauthor-lookup")))

  test("a community label lists every member of the largest allowed group"):
    val size = RuntimeLimits.MaxGroupSize
    val ids = rid(1) +: (100 until 100 + size - 1).toVector.map(rid)
    val rows = Vector(w15, w20).flatMap(w =>
      ids.map(id =>
        MemberRow(id, w, "big", size.toLong, ref("community-membership", id))
      )
    )
    val t = tables.copy(communities = rows, components = Vector.empty)
    val items = BenchmarkGen
      .generate(t, only("community-component", 3), VectorCoverage.unknown)
      .items
      .filter(_.label.operation == "community")
    assert(items.nonEmpty)
    // 29 other members, cap 30 (it was 20)
    assertEquals(items.head.label.expected.items.size, size - 1)

  test("the manifest records the commit and whether the tree was dirty"):
    val flagged = GeneratorState.info(
      Map("--generator-commit" -> "abc123", "--generator-dirty" -> "yes"),
      () => fail("git must not be called when both flags are given")
    )
    assertEquals(flagged.toMap.apply("generatorCommit").str, "abc123")
    assertEquals(flagged.toMap.apply("generatorTreeDirty").bool, true)
    val probed =
      GeneratorState.info(Map.empty, () => (Some("def456"), Some(false)))
    assertEquals(probed.toMap.apply("generatorCommit").str, "def456")
    assertEquals(probed.toMap.apply("generatorTreeDirty").bool, false)
    val unknown = GeneratorState.info(Map.empty, () => (None, None))
    assertEquals(unknown.toMap.apply("generatorCommit").str, "UNRECORDED")
    assert(unknown.toMap.apply("generatorTreeDirty").isNull)
    // a commit flag alone still asks git for the dirty state
    val partly = GeneratorState.info(
      Map("--generator-commit" -> "abc123"),
      () => (Some("ignored"), Some(true))
    )
    assertEquals(partly.toMap.apply("generatorCommit").str, "abc123")
    assertEquals(partly.toMap.apply("generatorTreeDirty").bool, true)

  test("the written manifest carries the dirty flag fields"):
    val r = gen("researcher-lookup", 1)
    val dir = Files.createTempDirectory("bench-dirty-")
    BenchmarkGen.writeOutputs(
      r,
      only("researcher-lookup", 1),
      run,
      None,
      VectorCoverage.unknown,
      GeneratorState.info(
        Map("--generator-commit" -> "abc", "--generator-dirty" -> "no")
      ),
      dir
    )
    val manifest =
      ujson.read(Files.readString(dir.resolve("BENCHMARK-MANIFEST-v1.json")))
    assertEquals(manifest("generatorCommit").str, "abc")
    assertEquals(manifest("generatorTreeDirty").bool, false)

  test("community items are same-group members at 2 and cross neighbours at 1"):
    val r = gen("community-component", 3)
    assertEquals(r.items.size, 3)
    val group = Set(rid(1), rid(2), rid(3))
    r.items.filter(_.label.operation == "community").foreach { i =>
      val s = i.label.subjectIds.head
      i.label.expected.items.foreach { x =>
        assertEquals(x.grade, if group.contains(x.id) then 2 else 1)
        assertNotEquals(x.id, s)
      }
    }
    assertEquals(r.items.count(_.label.operation == "component"), 1)

  test("temporal questions carry both windows and their numbers"):
    val r = gen("temporal-comparison", 1)
    assertEquals(r.items.size, 1)
    val label = r.items.head.label
    assertEquals(label.windows, Vector(w15, w20))
    assert(label.expected.numbers.exists(_._1 == s"publicationCount[$w15]"))
    assert(label.expected.numbers.exists(_._1 == s"publicationCount[$w20]"))

  test("unanswerable questions are all abstentions of six classes"):
    val r = gen("unanswerable", 8)
    assertEquals(r.items.size, 8)
    assert(r.items.forall(_.label.expected.abstain))
    assertEquals(
      r.items.flatMap(_.label.expected.abstainClass).toSet,
      Set(
        "unknown-researcher",
        "future-window",
        "ambiguous-name",
        "out-of-scope",
        "unrankable-metric",
        "missing-second-window"
      )
    )
    // every label names the runtime codes of its class, from the committed table
    r.items.foreach { i =>
      val cls = i.label.expected.abstainClass.get
      assertEquals(
        i.label.expected.extra.toMap
          .apply("expectedCodes")
          .arr
          .map(_.str)
          .toVector,
        AbstentionCodes.byClass(cls)
      )
    }
    // a lookup of a known researcher in a future window answers from the
    // identity record, so no future-window template is a researcher lookup
    assert(
      !r.items
        .filter(_.label.expected.abstainClass.contains("future-window"))
        .exists(_.label.operation == "researcher-lookup")
    )
    val known = names.map(_.toLowerCase).toSet
    r.items
      .filter(_.label.expected.abstainClass.contains("unknown-researcher"))
      .foreach(i =>
        assert(!known.exists(n => i.question.text.toLowerCase.contains(n)))
      )

  test("the abstention table maps every class to runtime codes that exist"):
    assertEquals(
      AbstentionCodes.byClass.keySet,
      Set(
        "unknown-researcher",
        "ambiguous-name",
        "future-window",
        "out-of-scope",
        "unrankable-metric",
        "missing-second-window"
      )
    )
    assertEquals(AbstentionCodes.RuntimeCodes.size, 12)
    AbstentionCodes.byClass.values.flatten.foreach(code =>
      assert(AbstentionCodes.RuntimeCodes.contains(code), code)
    )

  test("an empty export reports a shortfall instead of failing"):
    val empty = BenchTables(
      run,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty
    )
    val r = BenchmarkGen.generate(
      empty,
      only("researcher-lookup", 2),
      VectorCoverage.unknown
    )
    assertEquals(r.items.size, 0)
    assert(r.shortfalls.contains("researcher-lookup: wanted 2, produced 0"))

  test("vector coverage reaches the label"):
    val cov = new VectorCoverage:
      def status(subjectId: String, window: String): String =
        if subjectId == rid(1) && window == w15 then VectorCoverage.Available
        else VectorCoverage.Deleted
      def describe: ujson.Value = ujson.Null
    val g = new BenchmarkGen(tables, only("researcher-lookup", 2), cov)
    val items = g.generate().items
    assert(items.nonEmpty)
    items.foreach { i =>
      val s = i.label.subjectIds.head
      assertEquals(i.label.vectorAvailable, Some(s == rid(1)))
      assertEquals(
        i.label.vectorStatus,
        if s == rid(1) then "available" else "deleted"
      )
    }

  test("the same inputs give the same output and questions hold no label"):
    val a = gen("researcher-lookup", 3)
    val b = gen("researcher-lookup", 3)
    assertEquals(
      a.items.map(_.label.toJson.toString),
      b.items.map(_.label.toJson.toString)
    )
    val q = a.items.head.question.toJson
    assert(q.obj.get("expected").isEmpty && q.obj.get("operation").isEmpty)

  test("free-form slots are left for the human"):
    val r = BenchmarkGen.generate(
      tables,
      BenchConfig(counts = Map("free-form" -> 8)),
      VectorCoverage.unknown
    )
    assertEquals(r.freeFormSlots.size, 8)
    assertEquals(r.freeFormSlots.head("status").str, "pending-human")

  test("outputs are written once with a manifest and never overwritten"):
    val r = gen("researcher-lookup", 2)
    val dir = Files.createTempDirectory("bench-")
    val cfg = only("researcher-lookup", 2)
    BenchmarkGen.writeOutputs(
      r,
      cfg,
      run,
      None,
      VectorCoverage.unknown,
      Vector.empty,
      dir
    )
    assert(Files.isRegularFile(dir.resolve("questions-v1.jsonl")))
    assert(Files.isRegularFile(dir.resolve("labels-v1.jsonl")))
    val manifest =
      ujson.read(Files.readString(dir.resolve("BENCHMARK-MANIFEST-v1.json")))
    assertEquals(manifest("sourceRunId").str, run)
    assertEquals(manifest("seed").num.toLong, cfg.seed)
    assertEquals(manifest("files").arr.size, 3)
    intercept[IllegalStateException](
      BenchmarkGen.writeOutputs(
        r,
        cfg,
        run,
        None,
        VectorCoverage.unknown,
        Vector.empty,
        dir
      )
    )
