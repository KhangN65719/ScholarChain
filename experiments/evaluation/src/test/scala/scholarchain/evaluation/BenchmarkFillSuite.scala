package scholarchain.evaluation

import java.nio.file.{Files, Path}
import java.time.Instant
import munit.FunSuite
import scholarchain.evaluation.benchmark.*
import scholarchain.evaluation.io.*

class BenchmarkFillSuite extends FunSuite:
  private val run = "run:v1:fx"
  private val exportSha = "sha-of-export-manifest"
  private val w15 = "2015-2019"
  private val w20 = "2020-2029"

  private def rid(n: Int): String = s"researcher:v1:r$n"

  private def ref(table: String, id: String): SourceRef =
    SourceRef(table, s"$table/part-00000.jsonl", 1, "sha-" + id, id)

  private def person(n: Int, name: String): ResearcherRow =
    ResearcherRow(rid(n), "researcher", name, ref("nodes-researcher", rid(n)))

  private def metric(
      n: Int,
      window: String,
      kv: (String, Double)*
  ): MetricsRow =
    MetricsRow(
      rid(n),
      window,
      kv.toMap,
      ref("researcher-metrics", s"$n|$window")
    )

  private def edge(
      n: Int,
      a: Int,
      b: Int,
      window: String,
      joint: Int
  ): CoauthorEdge =
    CoauthorEdge(
      s"coauthor:v1:e$n",
      rid(a),
      rid(b),
      window,
      joint,
      ref("edges-coauthored", s"e$n")
    )

  private def member(n: Int): MemberRow =
    MemberRow(rid(n), w20, "c1", 3L, ref("community-membership", rid(n)))

  private val people: Vector[ResearcherRow] = Vector(
    person(1, "Osvaldo Simeone"),
    person(2, "Yuanqing Xia"),
    person(3, "Wentao Li"),
    person(4, "Aaron Ames"),
    person(5, "Dana Fox"),
    person(6, "Evan Gray"),
    person(7, "Fay Hale"),
    person(8, "Gus Iles"),
    person(9, "Hal Jax")
  ) :+ ResearcherRow(
    "mention:v1:m1",
    "unresolved-mention",
    "Mia Mention",
    ref("nodes-researcher", "mention:v1:m1")
  )

  private val distinct15 = Vector(30.0, 27.0, 26.0, 22.0, 21.0, 5.0)
  private val bridge = Vector(0.8772, 0.8327, 0.8298, 0.8109, 0.5)

  private val metrics: Vector[MetricsRow] =
    distinct15.zipWithIndex.map((v, i) =>
      metric(4 + i, w15, "distinctCoauthorCount" -> v)
    ) ++ bridge.zipWithIndex.map((v, i) =>
      metric(4 + i, "all", "bridgeScore" -> v)
    ) ++ Vector(
      metric(
        2,
        w15,
        "publicationCount" -> 1.0,
        "distinctCoauthorCount" -> 11.0
      ),
      metric(
        2,
        w20,
        "publicationCount" -> 3.0,
        "distinctCoauthorCount" -> 14.0
      ),
      metric(
        3,
        w20,
        "publicationCount" -> 2.0,
        "distinctCoauthorCount" -> 10.0
      ),
      metric(1, "2005-2009", "publicationCount" -> 1.0),
      metric(1, "2010-2014", "publicationCount" -> 1.0),
      MetricsRow(
        "mention:v1:m1",
        w15,
        Map("distinctCoauthorCount" -> 99.0),
        ref("researcher-metrics", "m1")
      )
    )

  private val edges = Vector(
    edge(1, 1, 4, "2005-2009", 2),
    edge(2, 1, 5, "2005-2009", 1),
    edge(3, 3, 4, w20, 1),
    edge(4, 2, 4, w20, 1)
  )

  private val tables = BenchTables(
    run,
    people,
    metrics,
    edges,
    Vector.empty,
    Vector.empty,
    Vector(member(2), member(4), member(5)),
    Vector.empty
  )

  private val baseCfg =
    BenchConfig(counts = Map("researcher-lookup" -> 1, "free-form" -> 8))

  private val names = Vector(
    "questions-v1.jsonl",
    "labels-v1.jsonl",
    "free-form-slots-v1.jsonl",
    "BENCHMARK-MANIFEST-v1.json"
  )

  /** A benchmark directory as BenchmarkGen writes it. */
  private def written(): Path =
    val dir = Files.createTempDirectory("bench-")
    BenchmarkGen.writeOutputs(
      BenchmarkGen.generate(tables, baseCfg, VectorCoverage.noneByFlag),
      baseCfg,
      run,
      Some(exportSha),
      VectorCoverage.noneByFlag,
      Vector.empty,
      dir
    )
    dir

  private def snapshot(dir: Path): Map[String, Vector[Byte]] =
    names.map(n => n -> Files.readAllBytes(dir.resolve(n)).toVector).toMap

  private def listing(dir: Path): Set[String] =
    val s = Files.list(dir)
    try
      import scala.jdk.CollectionConverters.*
      s.iterator.asScala.map(_.getFileName.toString).toSet
    finally s.close()

  private def doFill(
      dir: Path,
      t: BenchTables = tables,
      sha: Option[String] = Some(exportSha),
      coverage: VectorCoverage = VectorCoverage.noneByFlag,
      state: Vector[(String, ujson.Value)] = Vector.empty
  ): Either[FillRefusal, Vector[String]] =
    BenchmarkFill.fill(
      t,
      sha,
      coverage,
      None,
      dir,
      "v1",
      BenchmarkFill.v1Specs,
      "BenchmarkFill test",
      Instant.parse("2026-11-18T10:00:00Z"),
      state
    )

  private def lines(dir: Path, name: String): Vector[String] =
    Files.readString(dir.resolve(name)).linesIterator.toVector

  private def refused(
      dir: Path,
      expect: String,
      t: BenchTables = tables,
      sha: Option[String] = Some(exportSha),
      coverage: VectorCoverage = VectorCoverage.noneByFlag
  ): Unit =
    val before = snapshot(dir)
    val files = listing(dir)
    doFill(dir, t, sha, coverage) match
      case Right(_) => fail("the fill should have refused")
      case Left(r)  =>
        assert(
          r.reason.contains(expect),
          s"reason '${r.reason}' lacks '$expect'"
        )
    assertEquals(snapshot(dir), before, "a refused fill changed a file")
    assertEquals(listing(dir), files, "a refused fill left a stray file")

  private def label(dir: Path, id: String): ujson.Value =
    lines(dir, "labels-v1.jsonl")
      .map(ujson.read(_))
      .find(_("questionId").str == id)
      .get

  test("the fill appends the eight labelled questions and keeps the old lines"):
    val dir = written()
    val before = snapshot(dir)
    val qOld = lines(dir, "questions-v1.jsonl")
    val lOld = lines(dir, "labels-v1.jsonl")
    val oldManifest = ujson.read(Files.readString(dir.resolve(names(3))))
    val out = doFill(
      dir,
      state = Vector("generatorCommit" -> Js.s("abc123"))
    ).fold(r => fail(r.reason), identity)
    // byte-identical existing lines: the new file starts with the old bytes
    Vector(names(0), names(1)).foreach { n =>
      val now = Files.readAllBytes(dir.resolve(n)).toVector
      assertEquals(now.take(before(n).size), before(n))
    }
    assertEquals(lines(dir, names(0)).take(qOld.size), qOld)
    assertEquals(lines(dir, names(1)).take(lOld.size), lOld)
    assertEquals(lines(dir, names(0)).size, qOld.size + 8)
    assertEquals(lines(dir, names(1)).size, lOld.size + 8)
    val newIds = lines(dir, names(0)).drop(qOld.size).map(ujson.read(_))
    assertEquals(
      newIds.map(_("questionId").str),
      (1 to 8).toVector.map(i => f"Q-FF-$i%02d")
    )
    assert(newIds.forall(_("stratum").str == "free-form"))
    // slots are filled
    val slots = lines(dir, names(2)).map(ujson.read(_))
    assertEquals(slots.size, 8)
    assert(slots.forall(_("status").str == "filled"))
    assertEquals(slots(0)("text").str, BenchmarkFill.v1Specs(0).text)
    assertEquals(slots(0)("intendedOperation").str, "coauthor-lookup")
    assertEquals(slots(0)("subject").str, "Osvaldo Simeone")
    assertEquals(slots(0)("window").str, "2005-2009")
    assertEquals(slots(2)("windows").arr.map(_.str).toVector, Vector(w15, w20))
    // manifest: sha and rows of all three files, change entry, counts
    val m = ujson.read(Files.readString(dir.resolve(names(3))))
    Vector(names(0), names(1), names(2)).foreach { n =>
      val e = m("files").arr.find(_("name").str == n).get
      assertEquals(
        e("sha256").str,
        Digests.sha256File(dir.resolve(n)),
        n
      )
      assertEquals(e("rows").num.toLong, lines(dir, n).size.toLong, n)
      val old = oldManifest("files").arr.find(_("name").str == n).get
      assertEquals(
        m("changes")(0)("previousFiles").arr
          .find(_("name").str == n)
          .get("sha256")
          .str,
        old("sha256").str
      )
    }
    assertEquals(
      m("generatedQuestions").num.toInt,
      oldManifest("generatedQuestions").num.toInt
    )
    assertEquals(m("countsByStratum")("free-form").num.toInt, 8)
    val change = m("changes")(0)
    assertEquals(change("id").str, "free-form-fill")
    assertEquals(change("at").str, "2026-11-18T10:00:00Z")
    assertEquals(change("generatorCommit").str, "abc123")
    assert(change("command").str.contains("BenchmarkFill"))
    assert(change("scala3LibraryVersion").str.startsWith("3."))
    // only the fields the fill owns changed in the manifest
    val keep = oldManifest.obj.keys.filterNot(k =>
      Set("files", "countsByStratum").contains(k)
    )
    keep.foreach(k => assertEquals(m(k), oldManifest(k), k))
    // the printed check lines name the new shas
    assert(out.exists(_.startsWith("FILL WROTE questions-v1.jsonl")))
    assertEquals(
      listing(dir).filter(_.endsWith(".fill")),
      Set.empty[String]
    )

  test("labels follow the v1 rules and the operations the scorers know"):
    val dir = written()
    doFill(dir).fold(r => fail(r.reason), identity)
    val ops =
      (1 to 8).toVector.map(i => label(dir, f"Q-FF-$i%02d")("operation").str)
    assertEquals(
      ops,
      Vector(
        "coauthor-lookup",
        "community-component",
        "temporal-comparison",
        "researcher-lookup",
        "ranking",
        "ranking",
        "coauthor-lookup",
        "ranking"
      )
    )
    val ff1 = label(dir, "Q-FF-01")
    assertEquals(
      ff1("expected")("items").arr
        .map(i => i("id").str -> i("grade").num.toInt)
        .toVector,
      Vector(rid(4) -> 2, rid(5) -> 1)
    )
    assertEquals(ff1("window").str, "2005-2009")
    val ff2 = label(dir, "Q-FF-02")
    assertEquals(
      ff2("expected")("items").arr
        .map(i => i("id").str -> i("grade").num.toInt)
        .toVector,
      Vector(rid(4) -> 2, rid(5) -> 2)
    )
    val ff3 = label(dir, "Q-FF-03")
    assertEquals(ff3("windows").arr.map(_.str).toVector, Vector(w15, w20))
    assertEquals(
      ff3("expected")("numbers")("distinctCoauthorCount[2015-2019]").num,
      11.0
    )
    assertEquals(
      ff3("expected")("numbers")("distinctCoauthorCount[2020-2029]").num,
      14.0
    )
    val ff5 = label(dir, "Q-FF-05")
    assertEquals(
      ff5("expected")("items").arr
        .map(i => i("id").str -> i("grade").num.toInt)
        .toVector,
      Vector(rid(4) -> 3, rid(5) -> 3, rid(6) -> 3, rid(7) -> 2)
    )
    val ff6 = label(dir, "Q-FF-06")
    assertEquals(ff6("window").str, "all")
    assertEquals(ff6("expected")("items").arr.size, 3)
    val ff7 = label(dir, "Q-FF-07")
    assertEquals(ff7("expected")("abstain").bool, true)
    assertEquals(ff7("expected")("abstainClass").str, "unknown-researcher")
    assertEquals(
      ff7("expected")("expectedCodes").arr.map(_.str).toVector,
      Vector("identity-unresolved")
    )
    val ff8 = label(dir, "Q-FF-08")
    assertEquals(ff8("expected")("abstainClass").str, "unrankable-metric")
    assertEquals(
      ff8("expected")("expectedCodes").arr.map(_.str).toVector,
      Vector("researcher-count")
    )
    assertEquals(ff8("window").str, "2010-2014")

  test("the fill prints the computed check values"):
    val dir = written()
    val out = doFill(dir).fold(r => fail(r.reason), identity)
    def line(id: String): String = out.find(_.startsWith(id)).get
    assert(line("Q-FF-04").contains("publicationCount=2"))
    assert(line("Q-FF-04").contains("distinctCoauthorCount=10"))
    assert(line("Q-FF-04").contains("mentionsWithSameName=0"))
    assert(line("Q-FF-05").contains("values=30,27,26,22"))
    assert(line("Q-FF-05").contains("placeNPlus1=21"))
    assert(line("Q-FF-06").contains("values=0.8772,0.8327,0.8298"))
    assert(line("Q-FF-03").contains("distinctCoauthorCount[2015-2019]=11"))
    assert(line("Q-FF-03").contains("distinctCoauthorCount[2020-2029]=14"))
    assert(line("Q-FF-08").contains("citationKeys=0"))

  test("a second fill is refused and changes nothing"):
    val dir = written()
    doFill(dir).fold(r => fail(r.reason), identity)
    refused(dir, "already present")

  test(
    "a fill is refused when an existing line no longer matches the manifest"
  ):
    val dir = written()
    val q = dir.resolve(names(0))
    Files.writeString(q, Files.readString(q).replace("Who is", "Whom is"))
    refused(dir, "does not match BENCHMARK-MANIFEST-v1.json")

  test(
    "a fill is refused for an export other than the one that made the files"
  ):
    refused(written(), "sourceRunId", tables.copy(sourceRunId = "run:v1:other"))
    refused(written(), "export manifest sha256", sha = Some("other"))
    refused(written(), "export manifest sha256", sha = None)

  test("a vector coverage mode that differs from the manifest is refused"):
    val checked = new VectorCoverage:
      def status(subjectId: String, window: String): String = "available"
      def describe: ujson.Value = Js.obj(Vector.empty)
      override def checks: Vector[(String, Boolean)] = Vector("sha256" -> true)
    refused(written(), "vector coverage mode is checked", coverage = checked)

  test("a failed vector coverage check is refused with the mismatch exit"):
    val bad = new VectorCoverage:
      def status(subjectId: String, window: String): String = "available"
      def describe: ujson.Value = Js.obj(Vector.empty)
      override def checks: Vector[(String, Boolean)] = Vector("rows" -> false)
    val dir = written()
    doFill(dir, coverage = bad) match
      case Left(r)  => assertEquals(r.code, BenchmarkFill.ExitMismatch)
      case Right(_) => fail("should refuse")

  test(
    "a subject name that is not exactly one canonical researcher is refused"
  ):
    val none =
      tables.copy(researchers = people.filterNot(_.displayName == "Wentao Li"))
    refused(written(), "'Wentao Li' is not a canonical researcher", none)
    val twice = tables.copy(researchers = people :+ person(10, "Wentao Li"))
    refused(written(), "'Wentao Li' is ambiguous", twice)
    // a mention is not a canonical researcher
    val mentionOnly = tables.copy(researchers =
      people.filterNot(_.displayName == "Wentao Li") :+ ResearcherRow(
        "mention:v1:wl",
        "unresolved-mention",
        "Wentao Li",
        ref("nodes-researcher", "mention:v1:wl")
      )
    )
    refused(written(), "'Wentao Li' is not a canonical researcher", mentionOnly)

  test("a ranking tie across the cut-off is refused"):
    val tied = tables.copy(metrics =
      metrics.map(m =>
        if m.researcherId == rid(8) && m.window == w15 then
          m.copy(values = Map("distinctCoauthorCount" -> 22.0))
        else m
      )
    )
    refused(written(), "tie across the cut-off", tied)

  test("fewer than N ranked is refused"):
    val few = tables.copy(metrics =
      metrics.filterNot(m =>
        m.window == w15 && Set(rid(5), rid(6), rid(7), rid(8), rid(9)).contains(
          m.researcherId
        )
      )
    )
    refused(written(), "fewer than N ranked", few)

  test("a window with no data is refused"):
    val noWindow =
      tables.copy(metrics = metrics.filterNot(_.window == "2005-2009"))
    refused(written(), "window 2005-2009 has no data", noWindow)
    val noRows = tables.copy(edges = edges.filterNot(_.window == "2005-2009"))
    refused(written(), "has no coauthor edge in 2005-2009", noRows)

  test("an unknown-researcher name that is in the export is refused"):
    val known = tables.copy(researchers =
      people :+ ResearcherRow(
        "mention:v1:em",
        "unresolved-mention",
        "Elara Moonwhistle",
        ref("nodes-researcher", "mention:v1:em")
      )
    )
    refused(written(), "'Elara Moonwhistle' is in the export", known)

  test(
    "an export that holds a citation metric makes FF-08 rankable and is refused"
  ):
    val cited = tables.copy(metrics =
      metrics :+ metric(4, "2010-2014", "citationCount" -> 3.0)
    )
    refused(written(), "holds a citation metric", cited)

  test("a community larger than a label may hold is refused"):
    val big =
      tables.copy(communities = tables.communities.map(_.copy(groupSize = 31L)))
    refused(written(), "more than the 30 a label may hold", big)

  test("every problem is reported and nothing is written"):
    val bad = tables.copy(
      researchers = people.filterNot(_.displayName == "Wentao Li"),
      metrics = metrics.filterNot(_.window == "2010-2014")
    )
    val dir = written()
    doFill(dir, bad) match
      case Left(r) =>
        assert(r.reason.contains("Q-FF-04"))
        assert(r.reason.contains("Q-FF-08"))
      case Right(_) => fail("should refuse")

  test("a move that fails part-way names the replaced and the pending files"):
    val dir = Files.createTempDirectory("mv-")
    val targets = Vector("a", "b", "c")
    targets.foreach(n => Files.writeString(dir.resolve(n), "old"))
    val temps =
      targets.map(n => n -> Files.createTempFile(dir, s".$n.", ".fill"))
    var count = 0
    val e = intercept[PartialFillException](
      BenchmarkFill.moveAll(
        dir,
        temps,
        (from, to) =>
          count += 1
          if count == 3 then throw new java.io.IOException("disk full")
          Files.move(
            from,
            to,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
          )
      )
    )
    assertEquals(e.moved, Vector("a", "b"))
    assertEquals(e.notMoved, Vector("c"))
    assert(e.getMessage.contains("a, b replaced"), e.getMessage)
    assert(e.getMessage.contains("c NOT replaced"), e.getMessage)
    assert(e.getMessage.contains("mixed state"), e.getMessage)
    assert(!e.getMessage.contains("nothing written"), e.getMessage)
    assert(!Files.exists(temps(2)._2), "the pending temporary file is removed")
    assertEquals(Files.readString(dir.resolve("c")), "old")

  test(
    "a first move that fails rethrows the original error: nothing was written"
  ):
    val dir = Files.createTempDirectory("mv-")
    val temp = Files.createTempFile(dir, ".a.", ".fill")
    val e = intercept[java.io.IOException](
      BenchmarkFill.moveAll(
        dir,
        Vector("a" -> temp),
        (_, _) => throw new java.io.IOException("disk full")
      )
    )
    assertEquals(e.getMessage, "disk full")
    assert(!Files.exists(temp))

  test("a version other than v1 is refused before anything is read or written"):
    val dir = Files.createTempDirectory("v-")
    val buf = new java.io.ByteArrayOutputStream()
    val code = Console.withOut(buf)(
      BenchmarkFill.cli(
        Vector(
          dir.toString,
          dir.toString,
          "--version",
          "v2",
          "--no-vector-export",
          "yes"
        )
      )
    )
    val text = buf.toString
    assertEquals(code, 2)
    assert(text.contains("--version v2 is refused"), text)
    assert(text.contains("nothing written"), text)

  test("the command line refuses a missing export and an unknown option"):
    def run(args: Vector[String]): (Int, String) =
      val buf = new java.io.ByteArrayOutputStream()
      val code = Console.withOut(buf)(BenchmarkFill.cli(args))
      (code, buf.toString)
    val dir = Files.createTempDirectory("nothing-")
    val (c1, o1) =
      run(Vector(dir.toString, dir.toString, "--no-vector-export", "yes"))
    assertEquals(c1, 2)
    assert(o1.contains("BENCHMARK ERROR:"), o1)
    assert(o1.contains("nothing written"), o1)
    val (c2, o2) = run(Vector(dir.toString, dir.toString, "--seed", "1"))
    assertEquals(c2, 2)
    assert(o2.contains("unknown option --seed"), o2)
    val (c3, o3) = run(Vector(dir.toString, dir.toString, "--no-vector-export"))
    assertEquals(c3, 2)
    assert(o3.contains("needs a value"), o3)
