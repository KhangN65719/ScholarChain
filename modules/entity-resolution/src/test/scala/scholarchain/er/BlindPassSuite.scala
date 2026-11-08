package scholarchain.er

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scholarchain.core.{ContractSchema, SourceType}
import scholarchain.er.Fixtures.*
import scholarchain.er.eval.{GoldFile, Verdict}
import scholarchain.er.eval.labeling.*
import scholarchain.er.eval.validation.*
import scholarchain.er.matching.{EntityKind, SideRecord}
import scholarchain.er.pairs.{Hashing, PairItem, PairSide, PairsFile}

/** The blind second pass over a subset: choosing the subset, labeling it with
  * every other pair carried from the first pass, validating the result and
  * describing the agreement between the passes.
  */
class BlindPassSuite extends munit.FunSuite:
  private val snapshot = "snap-1"

  private def pairItem(i: Int): PairItem =
    val a: SideRecord = mention(f"p$i%02da", "Jane Doe")
    val b0: SideRecord = mention(f"p$i%02db", "Jane Doe")
    val b =
      if i == 6 then
        b0.copy(
          source = SourceType.OpenAlex,
          snapshotId = "openalex:2026-10-14"
        )
      else b0
    PairItem(
      ContractSchema.currentVersion,
      PairItem.idOf(EntityKind.Researcher, a.id, b.id),
      EntityKind.Researcher,
      runId,
      PairItem.exportVersion,
      PairSide.of(a),
      PairSide.of(b)
    )

  private val items = (1 to 6).toVector.map(pairItem)
  private val ids = items.map(_.pairId)

  // first pass, made with suggestions: pairs 0..5
  private val firstPass = Vector(
    Verdict.Match,
    Verdict.NonMatch,
    Verdict.Match,
    Verdict.Unsure,
    Verdict.NonMatch,
    Verdict.Match
  )

  // the subset, in the order the reviewer sees it, and the blind labels
  private val subset = Vector(ids(4), ids(2), ids(3))
  private val blindLabels = Map(
    ids(4) -> Verdict.Match,
    ids(2) -> Verdict.NonMatch,
    ids(3) -> Verdict.Unsure
  )

  private def word(v: Verdict): String = LabelNames.name(v)

  private def suggestionCsv: String =
    "pair_id,suggested_label,reason\n" + items
      .zip(firstPass)
      .map((p, v) => s"${p.pairId},${word(v)},because ${word(v)}")
      .mkString("\n") + "\n"

  private def stepClock(start: String): () => Instant =
    var n = 0
    () =>
      n += 1
      Instant.parse(start).plusSeconds(10L * n)

  private final case class Dir(root: Path, pairs: Path):
    def file(name: String): Path = root.resolve(name)

  private def freshDir(): Dir =
    val root = Files.createTempDirectory("er-second")
    val pairs = root.resolve("pairs.jsonl")
    PairsFile.write(pairs, items)
    Dir(root, pairs)

  private def lines(path: Path): Vector[String] =
    Files.readAllLines(path, UTF_8).asScala.toVector

  private def bytes(path: Path): Vector[Byte] =
    Files.readAllBytes(path).toVector

  private def firstPassFile(dir: Dir): Path =
    val csv = dir.file("suggestions.csv")
    Files.writeString(csv, suggestionCsv, UTF_8)
    val session = LabelSession
      .open(
        dir.pairs,
        dir.file("gold.jsonl"),
        Some(csv),
        "tester",
        stepClock("2026-11-07T10:00:00Z")
      )
      .fold(message => fail(message), identity)
    items.zip(firstPass).foreach((p, v) => session.label(p.pairId, v, None))
    dir.file("gold.jsonl")

  private def secondSession(
      dir: Dir,
      gold: String = "gold-v2.jsonl",
      only: Vector[String] = subset,
      carry: String = "gold.jsonl"
  ): Either[String, LabelSession] =
    LabelSession.open(
      dir.pairs,
      dir.file(gold),
      None,
      "tester",
      stepClock("2026-11-07T11:00:00Z"),
      Some(SecondPass(only, dir.file(carry)))
    )

  private def labelSubset(session: LabelSession): Unit =
    subset.foreach(id => session.label(id, blindLabels(id), None).toOption.get)

  private val shared = freshDir()
  private val firstPath = firstPassFile(shared)
  private val v2Session = secondSession(shared).fold(fail(_), identity)
  labelSubset(v2Session)
  private val v2Lines = lines(shared.file("gold-v2.jsonl"))
  private val firstLines = lines(firstPath)
  private val pairLines = lines(shared.pairs)
  private val firstBytes = Files.readAllBytes(firstPath)
  private val pass1 = ("gold.jsonl", firstBytes)

  // ------------------------------------------------------- subset selection

  private val strata = Map(
    ids(0) -> "exact-id",
    ids(1) -> "clear-non-match",
    ids(2) -> "hard-common-name",
    ids(3) -> "exact-id",
    ids(4) -> "hard-common-name",
    ids(5) -> "clear-non-match"
  )
  private val suggested = Map(
    ids(0) -> Verdict.Match,
    ids(1) -> Verdict.NonMatch,
    ids(2) -> Verdict.Match,
    ids(3) -> Verdict.Unsure,
    ids(4) -> Verdict.Unsure,
    ids(5) -> Verdict.Match
  )

  private def choose(
      s: Map[String, String] = strata,
      g: Map[String, Verdict] = suggested,
      pairIds: Vector[String] = ids
  ) =
    SubsetSelection.select(
      pairIds,
      s,
      g,
      SubsetSelection.HardStratum,
      SubsetSelection.Salt
    )

  test("the subset is hard-common-name plus suggested unsure, counted") {
    val chosen = choose().toOption.get
    assertEquals(chosen.hard, Set(ids(2), ids(4)))
    assertEquals(chosen.unsure, Set(ids(3), ids(4)))
    assertEquals(chosen.overlap, Set(ids(4)))
    assertEquals(chosen.union, Set(ids(2), ids(3), ids(4)))
    assertEquals(chosen.ordered.toSet, chosen.union)
    val text = SubsetSelection.summary(chosen, ids.size).mkString("\n")
    assert(text.contains("hard-common-name pairs: 2"), text)
    assert(text.contains("suggested unsure pairs: 2"), text)
    assert(text.contains("in both: 1"), text)
    assert(text.contains("union, the pairs to relabel blind: 3"), text)
    assert(text.contains("carried unchanged from the first pass: 3"), text)
  }

  test("the order is a fixed shuffle that ignores stratum and input order") {
    val a = choose().toOption.get.ordered
    val b = choose(pairIds = ids.reverse).toOption.get.ordered
    val flipped = strata.view
      .mapValues(_ => "exact-id")
      .toMap
      .updated(ids(2), "hard-common-name")
      .updated(ids(4), "hard-common-name")
    val c = choose(flipped).toOption.get.ordered
    assertEquals(a, b)
    assertEquals(a, c)
    val expected =
      a.toSet.toVector.sortBy(id =>
        Hashing.sha256Hex(SubsetSelection.Salt + "\n" + id)
      )
    assertEquals(a, expected)
  }

  test(
    "selection refuses a key, a suggestion list or a stratum that does not fit"
  ) {
    assert(choose(strata - ids(0)).isLeft)
    assert(choose(strata + ("pair:v1:other" -> "exact-id")).isLeft)
    assert(choose(g = suggested + ("pair:v1:other" -> Verdict.Unsure)).isLeft)
    val none = strata.view.mapValues(_ => "exact-id").toMap
    assert(choose(none).left.toOption.get.contains("hard-common-name"))
  }

  test("the subset file round trips in order and refuses repeats and blanks") {
    val path = shared.file("subset-out.txt")
    SubsetSelection.write(path, choose().toOption.get)
    assertEquals(
      SubsetFile.read(path).toOption.get,
      choose().toOption.get.ordered
    )
    assertEquals(
      SubsetFile.parse(Seq(" a ", "", "b")),
      Right(Vector("a", "b"))
    )
    assert(SubsetFile.parse(Seq("a", "a")).isLeft)
    assert(SubsetFile.parse(Seq("", " ")).isLeft)
  }

  test("strata lines are read by pair_id and stratum only") {
    val read = SubsetSelection.strataOf(
      Seq(
        s"""{"pair_id":"x","stratum":"hard-common-name","blockKey":"k"}""",
        ""
      )
    )
    assertEquals(read, Map("x" -> "hard-common-name"))
  }

  // ------------------------------------------------- labeling the subset blind

  test(
    "the second pass shows the subset in subset order and starts at its first"
  ) {
    val dir = freshDir()
    firstPassFile(dir)
    val before = bytes(dir.file("gold.jsonl"))
    val session = secondSession(dir).fold(fail(_), identity)
    assertEquals(session.pairs.map(_.pairId), subset)
    assertEquals(session.size, 3)
    assertEquals(session.firstUnlabeled, 0)
    assertEquals(session.stats.labeled, 0)
    assert(session.isSubset)
    assertEquals(session.carriedCount, 3)
    assert(!Files.exists(dir.file("gold-v2.jsonl")), "opening writes nothing")
    labelSubset(session)
    assertEquals(bytes(dir.file("gold.jsonl")), before, "first pass untouched")
    assertEquals(session.firstUnlabeled, 3)
  }

  test(
    "a labeled subset is a full 230-style file: pass 2 rows and pass 1 rows"
  ) {
    val rows = v2Lines.map(l => ujson.read(l).obj)
    assertEquals(
      rows.map(_("pair_id").str),
      ids,
      "pairs.jsonl order, all pairs"
    )
    val pass = rows.map(_("pass").num.toInt)
    assertEquals(pass, Vector(1, 1, 2, 2, 2, 1))
    rows.filter(_("pass").num == 2.0).foreach { row =>
      Vector(
        "suggested_label",
        "suggestion_reason",
        "suggestion_source_file",
        "suggestion_source_sha256"
      ).foreach(k => assertEquals(row(k), ujson.Null, k))
      assertEquals(row("changed_from_suggestion").bool, false)
      assertEquals(row("reviewMethod").str, "human")
      assertEquals(row("reviewer").str, "tester")
      assert(
        Instant
          .parse(row("reviewedAt").str)
          .isAfter(Instant.parse("2026-11-07T10:59:00Z"))
      )
      val index = ids.indexOf(row("pair_id").str)
      assertEquals(row("pass1_label").str, word(firstPass(index)))
      assert(!row.value.contains("carried_from"))
    }
    val sha = Hashing.sha256Bytes(firstBytes)
    rows.zip(firstLines).filter(_._1("pass").num == 1.0).foreach {
      (row, original) =>
        val first = ujson.read(original).obj
        first.value.foreach((k, v) => assertEquals(row(k), v, k))
        assertEquals(row("carried_from").str, "gold.jsonl")
        assertEquals(row("carried_from_sha256").str, sha)
        assert(!row.value.contains("pass1_label"))
    }
  }

  test(
    "the second-pass file is read by eval.GoldFile and carries no tag it needs"
  ) {
    val labels = GoldFile.read(shared.file("gold-v2.jsonl"))
    assertEquals(labels.map(_.itemId), ids)
    assertEquals(labels.map(_.verdict).count(_ == Verdict.Match), 3)
  }

  test("the file is saved after every answer and a reopened session resumes") {
    val dir = freshDir()
    firstPassFile(dir)
    val first = secondSession(dir).fold(fail(_), identity)
    first.label(subset(0), Verdict.Match, None)
    assertEquals(
      lines(dir.file("gold-v2.jsonl")).size,
      4,
      "3 carried + 1 relabeled"
    )
    val resumed = secondSession(dir).fold(fail(_), identity)
    assertEquals(resumed.firstUnlabeled, 1)
    assertEquals(resumed.stats.labeled, 1)
    resumed.label(subset(1), Verdict.Unsure, None)
    assertEquals(lines(dir.file("gold-v2.jsonl")).size, 5)
  }

  test("a pair outside the subset cannot be labeled in a second pass") {
    val dir = freshDir()
    firstPassFile(dir)
    val session = secondSession(dir).fold(fail(_), identity)
    assert(session.label(ids(0), Verdict.Match, None).isLeft)
    assert(!Files.exists(dir.file("gold-v2.jsonl")))
  }

  test("the second pass will not write over the first pass file") {
    val dir = freshDir()
    firstPassFile(dir)
    val before = bytes(dir.file("gold.jsonl"))
    assert(secondSession(dir, gold = "gold.jsonl").isLeft)
    assert(secondSession(dir, gold = "./gold.jsonl").isLeft)
    assert(
      secondSession(dir, gold = "other/../gold.jsonl").left.toOption.get
        .contains("carry")
    )
    assertEquals(bytes(dir.file("gold.jsonl")), before)
  }

  test("a second pass takes no suggestions and refuses unusable input") {
    val dir = freshDir()
    firstPassFile(dir)
    val withCsv = LabelSession.open(
      dir.pairs,
      dir.file("v2.jsonl"),
      Some(dir.file("suggestions.csv")),
      "tester",
      () => Instant.now(),
      Some(SecondPass(subset, dir.file("gold.jsonl")))
    )
    assert(withCsv.left.toOption.get.contains("blind"))
    assert(secondSession(dir, only = subset :+ "pair:v1:nope").isLeft)
    assert(secondSession(dir, carry = "missing.jsonl").isLeft)
    Files.writeString(dir.file("short.jsonl"), firstLines.head + "\n", UTF_8)
    val short = secondSession(dir, carry = "short.jsonl")
    assert(short.left.toOption.get.contains("no label"), short.toString)
    val again = secondSession(dir, gold = "again.jsonl")
    again.fold(fail(_), identity).label(subset(0), Verdict.Match, None)
    Files.copy(dir.file("again.jsonl"), dir.file("v2copy.jsonl"))
    val nested = secondSession(dir, gold = "x.jsonl", carry = "v2copy.jsonl")
    assert(nested.left.toOption.get.contains("pass fields"), nested.toString)
  }

  test("a first-pass file given as the second-pass gold file is refused") {
    val dir = freshDir()
    firstPassFile(dir)
    Files.copy(dir.file("gold.jsonl"), dir.file("copy.jsonl"))
    val refused = secondSession(dir, gold = "copy.jsonl")
    assert(refused.left.toOption.get.contains("pass"), refused.toString)
  }

  test(
    "a pass without suggestions refuses a gold file that holds suggestions"
  ) {
    val dir = freshDir()
    firstPassFile(dir)
    val before = bytes(dir.file("gold.jsonl"))
    val refused =
      LabelSession.open(dir.pairs, dir.file("gold.jsonl"), None, "t")
    assert(refused.left.toOption.get.contains("suggestions"), refused.toString)
    assertEquals(bytes(dir.file("gold.jsonl")), before)
  }

  private def call(
      client: HttpClient,
      server: LabelServer,
      path: String,
      body: Option[String] = None
  ): (Int, String) =
    val builder = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:${server.boundPort}$path"))
      .header("X-Label-Token", "tok")
    val request = body match
      case Some(text) =>
        builder.POST(HttpRequest.BodyPublishers.ofString(text)).build()
      case None => builder.GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  test(
    "the page data never carries a suggestion, a stratum or the first label"
  ) {
    val dir = freshDir()
    firstPassFile(dir)
    val session = secondSession(dir).fold(fail(_), identity)
    val server = new LabelServer(session, 0, "tok")
    server.start()
    try
      val client = HttpClient.newHttpClient()
      val info = call(client, server, "/api/info")._2
      assertEquals(ujson.read(info)("subset").bool, true)
      assertEquals(ujson.read(info)("suggestions_file"), ujson.Null)
      assertEquals(ujson.read(info)("total").num, 3.0)
      val first = call(client, server, "/api/pair?i=0")._2
      val view = ujson.read(first)
      assertEquals(view("pair_id").str, subset(0))
      assertEquals(view("label"), ujson.Null, "no pre-filled first-pass label")
      assertEquals(view("has_suggestion").bool, false)
      assertEquals(view("suggestion"), ujson.Null)
      assertEquals(view("total").num, 3.0)
      Vector(info, first).foreach { text =>
        Vector(
          "pass1",
          "carried",
          "stratum",
          "hard-common-name",
          "because",
          "suggested"
        ).foreach(w => assert(!text.contains(w), s"$w in $text"))
      }
      val done = call(
        client,
        server,
        "/api/label",
        Some(s"""{"pair_id":"${subset(0)}","label":"same"}""")
      )
      assertEquals(done._1, 200)
      val outside = call(
        client,
        server,
        "/api/label",
        Some(s"""{"pair_id":"${ids(0)}","label":"same"}""")
      )
      assertEquals(outside._1, 400)
      val early = call(
        client,
        server,
        "/api/accept",
        Some(s"""{"pair_id":"${subset(1)}"}""")
      )
      assertEquals(early._1, 400)
      val stats = ujson.read(call(client, server, "/api/info")._2)("stats")
      assertEquals((stats("labeled").num, stats("total").num), (1.0, 3.0))
    finally server.stop()
  }

  test(
    "the page hides suggestion keys, says subset, and has no reveal for a blind pass"
  ) {
    val html = LabelPage.html
    assert(html.contains("""id="suggestKeys" hidden"""), "legend hidden")
    assert(html.contains("suggestKeys').hidden = !info.suggestions_file"))
    assert(html.contains("withSuggestions"))
    assert(html.contains("(subset)"))
  }

  test("--only and --carry-from go together and exclude --suggestions") {
    val dir = freshDir()
    val onlyFile = dir.file("subset.txt")
    Files.writeString(onlyFile, subset.mkString("\n") + "\n", UTF_8)
    val only = "--only" -> onlyFile.toString
    val carry = "--carry-from" -> dir.file("gold.jsonl").toString
    assertEquals(LabelServer.secondPass(Map.empty), Right(None))
    assert(LabelServer.secondPass(Map(only)).isLeft)
    assert(LabelServer.secondPass(Map(carry)).isLeft)
    assert(
      LabelServer
        .secondPass(Map(only, carry, "--suggestions" -> "s.csv"))
        .isLeft
    )
    val ok = LabelServer.secondPass(Map(only, carry)).toOption.get.get
    assertEquals(ok.subset, subset)
    assertEquals(ok.carryFrom, dir.file("gold.jsonl"))
  }

  // -------------------------------------------------------------- validation

  private val expect =
    V2Expectations(6, None, None, None, snapshot, Some("tester"))

  private def sources(
      gold: Vector[String] = v2Lines,
      first: Option[(String, Array[Byte])] = Some(pass1),
      sub: Option[Vector[String]] = Some(subset),
      strataLines: Option[Vector[String]] = None,
      csv: Option[(String, Array[Byte])] = None
  ): Sources = Sources(gold, pairLines, csv, strataLines, None, first, sub)

  private def run(s: Sources = sources(), e: V2Expectations = expect) =
    GoldValidator.validateV2(s, e)

  private def named(report: Report): Map[String, Check] =
    report.checks.map(c => c.name -> c).toMap

  private def edit(rows: Vector[String], i: Int)(
      change: ujson.Value => Unit
  ): Vector[String] =
    val json = ujson.read(rows(i))
    change(json)
    rows.updated(i, ujson.write(json))

  private def strataJson: Vector[String] =
    ids.map(id => s"""{"pair_id":"$id","stratum":"${strata(id)}"}""")

  test("a correct second-pass file passes and asks for no totals") {
    val report = run()
    assertEquals(report.failed, Vector.empty[Check], report.render)
    assertEquals(named(report)("label-totals").status, Status.Skip)
    assert(named(report)("label-totals").detail.contains("3/2/1"))
    Vector(
      "pass-field",
      "pass1-file",
      "pass2-is-subset",
      "pass2-blind",
      "pass1-carried"
    )
      .foreach(n => assertEquals(named(report)(n).status, Status.Pass, n))
    assert(!report.checks.exists(_.name == "changed-from-suggestion"))
    assert(!report.checks.exists(_.name == "suggestion-on-every-row"))
  }

  test("expected totals are optional and checked when given") {
    val right = run(e =
      expect.copy(same = Some(3), different = Some(2), unsure = Some(1))
    )
    assertEquals(named(right)("label-totals").status, Status.Pass)
    val wrong = run(e = expect.copy(same = Some(95)))
    assertEquals(named(wrong)("label-totals").status, Status.Fail)
    val partial = run(e = expect.copy(unsure = Some(1)))
    assertEquals(named(partial)("label-totals").status, Status.Pass)
  }

  test("review time and label counts are printed first, separately by pass") {
    val report = run()
    val shown = report.render.linesIterator.toVector
    assert(shown.head.startsWith("REVIEW TIME AND PASSES"), shown.head)
    val text = shown.take(5).mkString("\n")
    assert(text.contains("REVIEW TIME pass 1, all 6 rows of gold.jsonl"), text)
    assert(text.contains("median gap 10.0 s"), text)
    assert(text.contains("0 gaps under 2 s"), text)
    assert(text.contains("span 0.8 min"), text)
    assert(text.contains("REVIEW TIME pass 2, 3 blind relabels"), text)
    assert(text.contains("span 0.3 min"), text)
    assert(text.contains("pass 1 3 (same 2, different 1, unsure 0)"), text)
    assert(text.contains("pass 2 3 (same 1, different 1, unsure 1)"), text)
    assert(
      text.contains("NOT RE-REVIEWED: 3 pairs carry first-pass labels"),
      text
    )
    assert(text.contains("only 3 pairs were reviewed blind"), text)
    assert(ujson.read(report.toJson)("headline").arr.size == 5)
  }

  test("a rushed second pass is reported, and does not fail the file") {
    val quick = Instant.parse("2026-11-07T11:00:00Z")
    val rushed = Vector(2, 3, 4).zipWithIndex.foldLeft(v2Lines) {
      case (rows, (i, n)) =>
        edit(rows, i)(j =>
          j("reviewedAt") = quick.plusMillis(300L * (n + 1)).toString
        )
    }
    val report = run(sources(gold = rushed))
    assert(report.render.contains("2 gaps under 2 s"), report.render)
    assert(report.passed, report.render)
  }

  test(
    "a pass 2 row with any suggestion field, or a wrong first label, fails"
  ) {
    val broken = Vector(
      "suggested_label" -> ujson.Str("same"),
      "suggestion_reason" -> ujson.Str("because"),
      "suggestion_source_file" -> ujson.Str("sugg.csv"),
      "suggestion_source_sha256" -> ujson.Str("0" * 64),
      "changed_from_suggestion" -> ujson.True
    )
    broken.foreach { (key, value) =>
      val rows = edit(v2Lines, 2)(j => j(key) = value)
      val check = named(run(sources(gold = rows)))("pass2-blind")
      assertEquals(check.status, Status.Fail, key)
      assert(check.detail.contains("line 3") && check.detail.contains(key))
    }
    val wrong = edit(v2Lines, 2)(j => j("pass1_label") = "different")
    assertEquals(
      named(run(sources(gold = wrong)))("pass2-blind").status,
      Status.Fail
    )
    val gone = edit(v2Lines, 2)(j => j.obj.remove("pass1_label"))
    assertEquals(
      named(run(sources(gold = gone)))("pass2-blind").status,
      Status.Fail
    )
  }

  test("null and absent suggestion keys on pass 2 rows are accepted") {
    val bare = edit(v2Lines, 2) { j =>
      j.obj.remove("suggested_label")
      j.obj.remove("changed_from_suggestion")
    }
    assertEquals(
      named(run(sources(gold = bare)))("pass2-blind").status,
      Status.Pass
    )
  }

  test("pass 1 rows must equal the first-pass file in every field") {
    Vector(
      "verdict" -> ujson.Str("non-match"),
      "reviewer" -> ujson.Str("someone"),
      "reviewedAt" -> ujson.Str("2026-11-08T00:00:00Z"),
      "suggested_label" -> ujson.Str("different"),
      "changed_from_suggestion" -> ujson.True,
      "carried_from" -> ujson.Str("elsewhere.jsonl"),
      "carried_from_sha256" -> ujson.Str("0" * 64)
    ).foreach { (key, value) =>
      val rows = edit(v2Lines, 0)(j => j(key) = value)
      val check = named(run(sources(gold = rows)))("pass1-carried")
      assertEquals(check.status, Status.Fail, key)
      assert(
        check.detail.contains("line 1") && check.detail.contains(key),
        check.detail
      )
    }
    val dropped = edit(v2Lines, 0)(j => j.obj.remove("suggested_label"))
    assertEquals(
      named(run(sources(gold = dropped)))("pass1-carried").status,
      Status.Fail
    )
    val other = ("gold.jsonl", firstBytes ++ "\n".getBytes(UTF_8))
    assertEquals(
      named(run(sources(first = Some(other))))("pass1-carried").status,
      Status.Fail,
      "the digest must be that of the first-pass file given"
    )
  }

  test("the pass 2 rows must be exactly the subset") {
    val missing = run(sources(sub = Some(subset :+ ids(0))))
    assertEquals(named(missing)("pass2-is-subset").status, Status.Fail)
    assert(named(missing)("pass2-is-subset").detail.contains("not relabeled"))
    val extra = run(sources(sub = Some(subset.drop(1))))
    assert(
      named(extra)("pass2-is-subset").detail.contains("outside the subset")
    )
    val none = run(sources(sub = None))
    assertEquals(named(none)("pass2-is-subset").status, Status.Fail)
    val repeated = run(sources(sub = Some(subset :+ subset.head)))
    assertEquals(named(repeated)("pass2-is-subset").status, Status.Fail)
  }

  test("every row needs pass 1 or 2") {
    val none = edit(v2Lines, 0)(j => j.obj.remove("pass"))
    assertEquals(
      named(run(sources(gold = none)))("pass-field").status,
      Status.Fail
    )
    val three = edit(v2Lines, 0)(j => j("pass") = ujson.Num(3))
    val bad = named(run(sources(gold = three)))("pass-field")
    assertEquals(bad.status, Status.Fail)
    assert(bad.detail.contains("line 1"))
  }

  test("without the first-pass file the dependent checks fail, not skip") {
    val report = run(sources(first = None))
    Vector("pass1-file", "pass2-blind", "pass1-carried").foreach(n =>
      assertEquals(named(report)(n).status, Status.Fail, n)
    )
    assert(report.compare.isEmpty)
  }

  test(
    "the subset is re-derived from the strata key and suggestions when given"
  ) {
    val csv = Some(
      ("suggestions.csv", Files.readAllBytes(shared.file("suggestions.csv")))
    )
    val ok = run(sources(strataLines = Some(strataJson), csv = csv))
    assertEquals(named(ok)("subset-recomputed").status, Status.Pass, ok.render)
    assertEquals(named(run())("subset-recomputed").status, Status.Skip)
    val other = Some(subset.drop(1))
    val bad =
      run(sources(strataLines = Some(strataJson), csv = csv, sub = other))
    assertEquals(named(bad)("subset-recomputed").status, Status.Fail)
  }

  test("the other checks still fail on the faults they must catch") {
    def failing(rows: Vector[String], p: Vector[String] = pairLines) =
      GoldValidator
        .validateV2(
          Sources(rows, p, None, None, None, Some(pass1), Some(subset)),
          expect
        )
        .failed
        .map(_.name)
        .toSet
    assert(failing(v2Lines.drop(1)).contains("entry-count"))
    assert(failing(v2Lines.drop(1)).contains("gold-pairs-coverage"))
    assert(failing(v2Lines :+ v2Lines.head).contains("gold-pair-ids"))
    assert(
      failing(edit(v2Lines, 2)(j => j("reviewMethod") = "model"))
        .contains("human-review-fields")
    )
    assert(
      failing(edit(v2Lines, 2)(j => j("reviewedAt") = "soon"))
        .contains("human-review-fields")
    )
    assert(
      failing(edit(v2Lines, 2)(j => j("left_snapshot_id") = "dblp:2020"))
        .contains("dblp-snapshot")
    )
    assert(failing(v2Lines, pairLines :+ pairLines.head).contains("pairs-file"))
    assert(failing(v2Lines.updated(1, "{nope")).contains("loads-as-GoldLabel"))
  }

  test(
    "the agreement is descriptive, on the subset only, and says what was not reviewed"
  ) {
    val report = run()
    assert(report.passed, report.render)
    val text = report.compare.mkString("\n")
    assert(text.contains("descriptive only"), text)
    assert(text.contains("not a threshold input"), text)
    assert(text.contains("only 3 subset pairs were re-reviewed blind"), text)
    assert(text.contains("3 pairs were NOT re-reviewed"), text)
    assert(text.contains("exact agreement 1 of 3"), text)
    assert(text.contains("n 2"), text)
    assert(text.contains("excluded for unsure: 1"), text)
    assert(text.contains("kappa -1.000"), text)
    assert(text.contains("disagreements: 2"), text)
    val d = report.compare.filter(_.startsWith("disagree "))
    assert(
      d.exists(l =>
        l.contains(ids(4)) && l.contains("first=different second=same")
      )
    )
    assert(
      d.exists(l =>
        l.contains(ids(2)) && l.contains("first=same second=different")
      )
    )
    assert(!text.contains("stratum"), "no strata without --strata-key")
    assert(report.render.contains("COMPARE descriptive only"))
  }

  test("stratum-wise agreement appears only with a strata key") {
    val report = run(sources(strataLines = Some(strataJson)))
    val text = report.compare.mkString("\n")
    assert(
      text.contains("stratum hard-common-name: 2 pairs, 0 agree, 2 disagree"),
      text
    )
    assert(
      text.contains("stratum exact-id: 1 pairs, 1 agree, 0 disagree"),
      text
    )
  }

  test("kappa is undefined when it has no meaning") {
    val same = Vector(
      PassComparison.Pair("a", Verdict.Match, Verdict.Match),
      PassComparison.Pair("b", Verdict.Match, Verdict.Match)
    )
    assert(PassComparison.kappaLine(same).contains("kappa undefined"))
    val unsure = Vector(PassComparison.Pair("a", Verdict.Unsure, Verdict.Match))
    assert(PassComparison.kappaLine(unsure).contains("kappa undefined"))
    val mixed = Vector(
      PassComparison.Pair("a", Verdict.Match, Verdict.Match),
      PassComparison.Pair("b", Verdict.NonMatch, Verdict.NonMatch),
      PassComparison.Pair("c", Verdict.Match, Verdict.NonMatch),
      PassComparison.Pair("d", Verdict.NonMatch, Verdict.Match),
      PassComparison.Pair("e", Verdict.Match, Verdict.Match)
    )
    assert(PassComparison.kappaLine(mixed).contains("kappa 0.167"))
  }

  test("the comparison never changes pass or fail") {
    val a = run()
    val bare = a.copy(compare = Vector.empty)
    assertEquals(a.passed, bare.passed)
    assertEquals(a.checks, bare.checks)
  }

  // ---------------------------------------------------------------- arguments

  private val base = Seq("--pairs", "p", "--gold", "g")
  private val v2 = Seq("--mode", "v2", "--pass1", "first", "--subset", "s")

  test("v2 arguments: first-pass file and subset required, totals optional") {
    val ok = GoldValidator.parseArgs(base ++ v2).toOption.get.v2.get
    assertEquals(
      (ok.expect.total, ok.expect.same, ok.expect.different, ok.expect.unsure),
      (230, None, None, None)
    )
    assertEquals(ok.expect.dblpSnapshot, "dblp:2026-11-05")
    assertEquals((ok.pass1.toString, ok.subset.toString), ("first", "s"))
    val withTotals = GoldValidator
      .parseArgs(base ++ v2 ++ Seq("--expect-same", "3", "--reviewer", "r"))
      .toOption
      .get
      .v2
      .get
    assertEquals(
      (withTotals.expect.same, withTotals.expect.reviewer),
      (Some(3), Some("r"))
    )
    assert(
      GoldValidator
        .parseArgs(base ++ Seq("--mode", "v2", "--subset", "s"))
        .isLeft
    )
    assert(
      GoldValidator
        .parseArgs(base ++ Seq("--mode", "v2", "--pass1", "f"))
        .isLeft
    )
    assert(
      GoldValidator
        .parseArgs(base ++ v2 ++ Seq("--expect-changed", "64"))
        .isLeft
    )
    assert(
      GoldValidator.parseArgs(base ++ v2 ++ Seq("--expect-same", "x")).isLeft
    )
    assert(GoldValidator.parseArgs(base ++ Seq("--mode", "v3")).isLeft)
    val same = Seq("--pairs", "p", "--gold", "f") ++
      Seq("--mode", "v2", "--pass1", "f", "--subset", "s")
    assert(
      GoldValidator.parseArgs(same).isLeft,
      "pass1 may not be the gold file"
    )
  }

  test("without --mode v2 the arguments and defaults are what they were") {
    val plain = GoldValidator.parseArgs(base).toOption.get
    assertEquals(plain.v2, None)
    assertEquals(plain.expect, Expectations.phase5)
    assert(GoldValidator.parseArgs(base ++ Seq("--pass1", "f")).isLeft)
    assert(GoldValidator.parseArgs(base ++ Seq("--subset", "f")).isLeft)
  }
