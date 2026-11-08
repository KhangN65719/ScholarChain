package scholarchain.er

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scholarchain.core.{ContractSchema, SourceType}
import scholarchain.er.Fixtures.*
import scholarchain.er.eval.{GoldFile, Verdict}
import scholarchain.er.eval.labeling.*
import scholarchain.er.eval.validation.*
import scholarchain.er.matching.{EntityKind, SideRecord}
import scholarchain.er.pairs.{Hashing, PairItem, PairSide, PairsFile}

/** Pass 3: a blind re-review of the 153 pairs that pass 2 did not touch.
  *
  * Gate items and the tests that prove them:
  *   1. pass-3 subset is the complement of the pass-2 subset, in a fixed
  *      stratum-independent shuffle with its own salt: the "pass-3 subset"
  *      tests.
  *   2. the labeling tool writes pass-3 rows blind and carries the pass-2 rows
  *      unchanged from gold-v2: the "pass 3 session" and "page" tests.
  *   3. GoldValidator --mode v3: the "validator v3" tests.
  */
class Pass3Suite extends munit.FunSuite:
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

  private val firstPass = Vector(
    Verdict.Match,
    Verdict.NonMatch,
    Verdict.Match,
    Verdict.Unsure,
    Verdict.NonMatch,
    Verdict.Match
  )
  private val subset2 = Vector(ids(4), ids(2), ids(3))
  private val blind2 = Map(
    ids(4) -> Verdict.Match,
    ids(2) -> Verdict.NonMatch,
    ids(3) -> Verdict.Unsure
  )
  private val blind3 = Map(
    ids(0) -> Verdict.NonMatch,
    ids(1) -> Verdict.NonMatch,
    ids(5) -> Verdict.Unsure
  )

  private def word(v: Verdict): String = LabelNames.name(v)

  private def stepClock(start: String): () => Instant =
    var n = 0
    () =>
      n += 1
      Instant.parse(start).plusSeconds(10L * n)

  private final case class Dir(root: Path, pairs: Path):
    def file(name: String): Path = root.resolve(name)

  private def lines(path: Path): Vector[String] =
    Files.readAllLines(path, UTF_8).asScala.toVector

  private def bytes(path: Path): Vector[Byte] =
    Files.readAllBytes(path).toVector

  private def freshDir(): Dir =
    val root = Files.createTempDirectory("er-third")
    val pairs = root.resolve("pairs.jsonl")
    PairsFile.write(pairs, items)
    Dir(root, pairs)

  private def csv: String =
    "pair_id,suggested_label,reason\n" + items
      .zip(firstPass)
      .map((p, v) => s"${p.pairId},${word(v)},because ${word(v)}")
      .mkString("\n") + "\n"

  private def firstPassFile(dir: Dir): Unit =
    val file = dir.file("suggestions.csv")
    Files.writeString(file, csv, UTF_8)
    val session = LabelSession
      .open(
        dir.pairs,
        dir.file("gold.jsonl"),
        Some(file),
        "tester",
        stepClock("2026-11-07T10:00:00Z")
      )
      .fold(m => fail(m), identity)
    items.zip(firstPass).foreach((p, v) => session.label(p.pairId, v, None))

  private def secondFile(dir: Dir): Unit =
    val session = LabelSession
      .open(
        dir.pairs,
        dir.file("gold-v2.jsonl"),
        None,
        "tester",
        stepClock("2026-11-07T11:00:00Z"),
        Some(SecondPass(subset2, dir.file("gold.jsonl")))
      )
      .fold(m => fail(m), identity)
    subset2.foreach(id => session.label(id, blind2(id), None))

  private val pass3 = Pass3Subset.select(ids, subset2).toOption.get.pass3

  private def thirdSession(
      dir: Dir,
      gold: String = "gold-v3.jsonl",
      only: Vector[String] = pass3,
      carry: String = "gold-v2.jsonl",
      suggestions: Option[Path] = None
  ): Either[String, LabelSession] =
    LabelSession.open(
      dir.pairs,
      dir.file(gold),
      suggestions,
      "tester",
      stepClock("2026-11-08T09:00:00Z"),
      Some(SecondPass(only, dir.file(carry), 3))
    )

  private def labelThird(session: LabelSession): Unit =
    pass3.foreach(id => session.label(id, blind3(id), None).toOption.get)

  private def prepared(): Dir =
    val dir = freshDir()
    firstPassFile(dir)
    secondFile(dir)
    dir

  private val shared = prepared()
  private val third = thirdSession(shared).fold(m => fail(m), identity)
  labelThird(third)
  private val v3Lines = lines(shared.file("gold-v3.jsonl"))
  private val v2Lines = lines(shared.file("gold-v2.jsonl"))
  private val goldLines = lines(shared.file("gold.jsonl"))
  private val pairLines = lines(shared.pairs)
  private val v2Bytes = Files.readAllBytes(shared.file("gold-v2.jsonl"))
  private val goldBytes = Files.readAllBytes(shared.file("gold.jsonl"))

  // ------------------------------------------------------ the pass-3 subset

  test("the pass-3 subset is the complement of the pass-2 subset") {
    val chosen = Pass3Subset.select(ids, subset2).toOption.get
    assertEquals(chosen.pass3.toSet, ids.toSet -- subset2.toSet)
    assertEquals(chosen.pass3.size + chosen.pass2.size, ids.size)
    assertEquals(chosen.pass3.toSet.intersect(chosen.pass2.toSet), Set.empty)
    assertEquals(chosen.pass3.distinct, chosen.pass3)
  }

  test("the order is a SHA-256 shuffle with its own salt, blind to stratum") {
    assertNotEquals(Pass3Subset.Salt, SubsetSelection.Salt)
    val a = Pass3Subset.select(ids, subset2).toOption.get.pass3
    val b = Pass3Subset.select(ids.reverse, subset2.reverse).toOption.get.pass3
    assertEquals(a, b, "independent of pairs.jsonl order")
    val expected =
      a.sortBy(id => Hashing.sha256Hex(Pass3Subset.Salt + "\n" + id))
    assertEquals(a, expected)
    val other = Pass3Subset.select(ids, subset2, "another-salt/1").toOption.get
    assertEquals(other.pass3.toSet, a.toSet)
  }

  test(
    "the same salt as pass 2 is refused, and so is input that does not fit"
  ) {
    assert(Pass3Subset.select(ids, subset2, SubsetSelection.Salt).isLeft)
    assert(Pass3Subset.select(ids, subset2 :+ "pair:v1:nope").isLeft)
    assert(Pass3Subset.select(ids, subset2 :+ subset2.head).isLeft)
    assert(Pass3Subset.select(ids, ids).isLeft, "nothing left for pass 3")
    assert(Pass3Subset.select(ids :+ ids.head, subset2).isLeft)
  }

  test("the selection needs no strata key and the counts are printed") {
    val chosen = Pass3Subset.select(ids, subset2).toOption.get
    val text = Pass3Subset.summary(chosen, ids.size).mkString("\n")
    assert(text.contains("pass-2 subset: 3"), text)
    assert(text.contains("pass-3 subset: 3"), text)
    assert(text.contains("3 + 3 = 6 of 6 pairs"), text)
    assert(text.contains("disjoint"), text)
    assert(!text.contains("stratum key"), text)
  }

  test("the subset file round trips in order") {
    val out = shared.file("pass3-subset.txt")
    Pass3Subset.write(out, Pass3Subset.select(ids, subset2).toOption.get)
    assertEquals(SubsetFile.read(out).toOption.get, pass3)
  }

  test("pass-3 subset arguments: pairs, pass-2 subset and out are required") {
    val ok = Pass3Subset
      .parseArgs(
        Seq("--pairs", "p", "--pass2-subset", "s", "--out", "o")
      )
      .toOption
      .get
    assertEquals(
      (ok.pairs.toString, ok.pass2.toString, ok.out.toString),
      ("p", "s", "o")
    )
    assertEquals(ok.salt, Pass3Subset.Salt)
    assert(Pass3Subset.parseArgs(Seq("--pairs", "p", "--out", "o")).isLeft)
    assert(
      Pass3Subset
        .parseArgs(
          Seq("--pairs", "p", "--pass2-subset", "s", "--out", "o", "--x", "1")
        )
        .isLeft
    )
    assert(
      Pass3Subset
        .parseArgs(
          Seq("--pairs", "p", "--pass2-subset", "s", "--out", "o") ++
            Seq("--strata-key", "k")
        )
        .isLeft,
      "the strata key is not an input"
    )
  }

  // ----------------------------------------------------- pass 3 session

  test(
    "a pass 3 session shows the subset in order and writes nothing on open"
  ) {
    val dir = prepared()
    val before = bytes(dir.file("gold-v2.jsonl"))
    val session = thirdSession(dir).fold(m => fail(m), identity)
    assertEquals(session.pairs.map(_.pairId), pass3)
    assertEquals(session.size, 3)
    assertEquals(session.firstUnlabeled, 0)
    assertEquals(session.carriedCount, 3)
    assert(session.isSubset)
    assert(!Files.exists(dir.file("gold-v3.jsonl")))
    labelThird(session)
    assertEquals(bytes(dir.file("gold-v2.jsonl")), before, "gold-v2 untouched")
  }

  test("gold.jsonl and gold-v2.jsonl are never written") {
    assertEquals(
      Files.readAllBytes(shared.file("gold-v2.jsonl")).toVector,
      v2Bytes.toVector
    )
    assertEquals(
      Files.readAllBytes(shared.file("gold.jsonl")).toVector,
      goldBytes.toVector
    )
  }

  test(
    "pass-3 rows are blind, human, marked pass 3 and keep the pass-1 label"
  ) {
    val rows = v3Lines.map(l => ujson.read(l).obj)
    assertEquals(
      rows.map(_("pair_id").str),
      ids,
      "all pairs, pairs.jsonl order"
    )
    assertEquals(rows.map(_("pass").num.toInt), Vector(3, 3, 2, 2, 2, 3))
    rows.filter(_("pass").num == 3.0).foreach { row =>
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
          .isAfter(Instant.parse("2026-11-08T08:59:00Z"))
      )
      val index = ids.indexOf(row("pair_id").str)
      assertEquals(row("pass1_label").str, word(firstPass(index)))
      assertEquals(row("verdict").str, blind3(ids(index)).wire)
      assert(!row.value.contains("carried_from"))
    }
  }

  test("nothing of the replaced pass-1 rows is carried into pass-3 rows") {
    val text = v3Lines.zipWithIndex
      .filter((l, _) => ujson.read(l)("pass").num == 3.0)
      .map(_._1)
      .mkString("\n")
    assert(!text.contains("because"), text)
    assert(!text.contains("suggestions.csv"), text)
    assert(!text.contains("gold.jsonl"), text)
  }

  test("the 77-style pass-2 rows are carried value for value from gold-v2") {
    val sha = Hashing.sha256Bytes(v2Bytes)
    val rows = v3Lines.map(l => ujson.read(l).obj)
    rows.zip(v2Lines).filter(_._1("pass").num == 2.0).foreach { (row, orig) =>
      val before = ujson.read(orig).obj
      assertEquals(before("pass").num, 2.0)
      before.value.foreach((k, v) => assertEquals(row(k), v, k))
      assertEquals(row("carried_from").str, "gold-v2.jsonl")
      assertEquals(row("carried_from_sha256").str, sha)
    }
    assertEquals(rows.count(_("pass").num == 2.0), 3)
  }

  test("the v3 file is read by eval.GoldFile") {
    val labels = GoldFile.read(shared.file("gold-v3.jsonl"))
    assertEquals(labels.map(_.itemId), ids)
  }

  test("the file is saved after every answer and a reopened session resumes") {
    val dir = prepared()
    val first = thirdSession(dir).fold(m => fail(m), identity)
    first.label(pass3(0), Verdict.Match, None)
    assertEquals(lines(dir.file("gold-v3.jsonl")).size, 4, "3 carried + 1")
    val resumed = thirdSession(dir).fold(m => fail(m), identity)
    assertEquals(resumed.firstUnlabeled, 1)
    assertEquals(resumed.stats.labeled, 1)
    resumed.label(pass3(1), Verdict.Unsure, None)
    assertEquals(lines(dir.file("gold-v3.jsonl")).size, 5)
  }

  test("a pair outside the pass-3 subset cannot be labeled") {
    val dir = prepared()
    val session = thirdSession(dir).fold(m => fail(m), identity)
    assert(session.label(subset2.head, Verdict.Match, None).isLeft)
    assert(!Files.exists(dir.file("gold-v3.jsonl")))
  }

  test("guards: the gold path may not be the carry file or the first pass") {
    val dir = prepared()
    val before = bytes(dir.file("gold-v2.jsonl"))
    val same = thirdSession(dir, gold = "gold-v2.jsonl")
    assert(same.left.toOption.get.contains("carry"), same.toString)
    assert(thirdSession(dir, gold = "./gold-v2.jsonl").isLeft)
    val first = thirdSession(dir, gold = "gold.jsonl")
    assert(first.left.toOption.get.contains("overwrite"), first.toString)
    assertEquals(bytes(dir.file("gold-v2.jsonl")), before)
    assertEquals(bytes(dir.file("gold.jsonl")), goldBytes.toVector)
  }

  test("guards: a gold file that is not from this mode is not resumed") {
    val dir = prepared()
    Files.copy(dir.file("gold-v2.jsonl"), dir.file("copy-v2.jsonl"))
    val v2 = thirdSession(dir, gold = "copy-v2.jsonl")
    assert(v2.left.toOption.get.contains("pass"), v2.toString)
    Files.copy(dir.file("gold.jsonl"), dir.file("copy-v1.jsonl"))
    val v1 = thirdSession(dir, gold = "copy-v1.jsonl")
    assert(v1.left.toOption.get.contains("pass"), v1.toString)
    Files.writeString(dir.file("junk.jsonl"), "{nope\n", UTF_8)
    assert(thirdSession(dir, gold = "junk.jsonl").isLeft)
    assertEquals(lines(dir.file("junk.jsonl")), Vector("{nope"))
  }

  test(
    "guards: the carry file must be a second-pass file for exactly this subset"
  ) {
    val dir = prepared()
    val plain = thirdSession(dir, carry = "gold.jsonl")
    assert(plain.left.toOption.get.contains("pass"), plain.toString)
    val withPass2 = thirdSession(dir, only = pass3 :+ subset2.head)
    assert(withPass2.left.toOption.get.contains("subset"), withPass2.toString)
    val short = thirdSession(dir, only = pass3.drop(1))
    assert(short.left.toOption.get.contains("subset"), short.toString)
    assert(thirdSession(dir, only = pass3 :+ pass3.head).isLeft)
    assert(thirdSession(dir, only = pass3 :+ "pair:v1:nope").isLeft)
    assert(thirdSession(dir, carry = "missing.jsonl").isLeft)
    val csvFile = Some(dir.file("suggestions.csv"))
    val hinted = thirdSession(dir, suggestions = csvFile)
    assert(hinted.left.toOption.get.contains("blind"), hinted.toString)
  }

  test("the pass-2 invocation is unchanged and defaults to pass 2") {
    val only = shared.file("subset.txt")
    Files.writeString(only, subset2.mkString("\n") + "\n", UTF_8)
    val pick = Map(
      "--only" -> only.toString,
      "--carry-from" -> shared.file("gold.jsonl").toString
    )
    assertEquals(LabelServer.secondPass(pick).toOption.get.get.relabelPass, 2)
    val two = LabelServer.secondPass(pick + ("--relabel-pass" -> "2"))
    assertEquals(two.toOption.get.get.relabelPass, 2)
    assertEquals(SecondPass(subset2, only).relabelPass, 2)
  }

  test("--relabel-pass 3 is explicit, goes with --only and --carry-from") {
    val only = shared.file("subset3.txt")
    Files.writeString(only, pass3.mkString("\n") + "\n", UTF_8)
    val pick = Map(
      "--only" -> only.toString,
      "--carry-from" -> shared.file("gold-v2.jsonl").toString
    )
    val ok = LabelServer.secondPass(pick + ("--relabel-pass" -> "3"))
    assertEquals(ok.toOption.get.get.relabelPass, 3)
    assertEquals(ok.toOption.get.get.subset, pass3)
    assert(LabelServer.secondPass(pick + ("--relabel-pass" -> "4")).isLeft)
    assert(LabelServer.secondPass(pick + ("--relabel-pass" -> "x")).isLeft)
    assert(LabelServer.secondPass(Map("--relabel-pass" -> "3")).isLeft)
    assert(
      LabelServer
        .secondPass(
          pick + ("--relabel-pass" -> "3") + ("--suggestions" -> "s.csv")
        )
        .isLeft
    )
  }

  // ----------------------------------------------------------------- the page

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
    "page data: pair k of 153, no suggestion, stratum, pass-1 label or output"
  ) {
    val dir = prepared()
    val session = thirdSession(dir).fold(m => fail(m), identity)
    val server = new LabelServer(session, 0, "tok")
    server.start()
    try
      val client = HttpClient.newHttpClient()
      val info = call(client, server, "/api/info")._2
      assertEquals(ujson.read(info)("subset").bool, true)
      assertEquals(ujson.read(info)("relabel_pass").num, 3.0)
      assertEquals(ujson.read(info)("suggestions_file"), ujson.Null)
      assertEquals(ujson.read(info)("total").num, 3.0)
      val shown = call(client, server, "/api/pair?i=0")._2
      val view = ujson.read(shown)
      assertEquals(view("pair_id").str, pass3(0))
      assertEquals(view("index").num, 0.0)
      assertEquals(view("total").num, 3.0)
      assertEquals(view("label"), ujson.Null)
      assertEquals(view("suggestion"), ujson.Null)
      assertEquals(view("has_suggestion").bool, false)
      Vector(info, shown).foreach { text =>
        Vector(
          "pass1",
          "carried",
          "stratum",
          "hard-common-name",
          "because",
          "suggested",
          "outcome",
          "score",
          "ACCEPTED",
          "REJECTED"
        ).foreach(w => assert(!text.contains(w), s"$w in $text"))
      }
      assertEquals(
        call(
          client,
          server,
          "/api/label",
          Some(s"""{"pair_id":"${pass3(0)}","label":"same"}""")
        )._1,
        200
      )
      assertEquals(
        call(
          client,
          server,
          "/api/label",
          Some(s"""{"pair_id":"${subset2(0)}","label":"same"}""")
        )._1,
        400
      )
    finally server.stop()
  }

  test("the page text says pair k of n (subset) and has no reveal for pass 3") {
    val html = LabelPage.html
    assert(html.contains("'Pair ' + (data.index + 1) + ' of ' + data.total"))
    assert(html.contains("(subset)"))
    assert(html.contains("""id="suggestKeys" hidden"""))
    assert(html.contains("relabel_pass"))
  }

  test(
    "the labeling sources hold nothing from the error analysis or decisions"
  ) {
    val relative = "modules/entity-resolution/src/main/scala/scholarchain/er"
    val dir = Vector(
      Paths.get(relative, "eval/labeling"),
      Paths.get("src/main/scala/scholarchain/er", "eval/labeling")
    ).find(Files.isDirectory(_)).getOrElse(fail("cannot locate labeling"))
    val texts = Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".scala"))
      .toVector
      .map(p => p -> Files.readString(p, UTF_8))
    assert(texts.nonEmpty)
    val forbidden = Vector(
      "suspect",
      "decisions.jsonl",
      "evaluation-report",
      "EvaluationReport",
      "error analysis",
      "false-reject",
      "strata-key",
      "stratum-key"
    )
    val found = for
      (p, t) <- texts
      w <- forbidden
      if t.contains(w)
    yield s"${p.getFileName} mentions $w"
    assertEquals(found, Vector.empty[String])
  }

  // -------------------------------------------------------------- validation

  private val expect =
    V2Expectations(6, None, None, None, snapshot, Some("tester"))

  private val goldFile = ("gold.jsonl", goldBytes)
  private val v2File = ("gold-v2.jsonl", v2Bytes)

  private def sources(
      gold: Vector[String] = v3Lines,
      first: Option[(String, Array[Byte])] = Some(goldFile),
      second: Option[(String, Array[Byte])] = Some(v2File),
      sub: Option[Vector[String]] = Some(pass3),
      sub2: Option[Vector[String]] = Some(subset2),
      strata: Option[Vector[String]] = None
  ): Sources =
    Sources(gold, pairLines, None, strata, None, first, sub, second, sub2)

  private def run(s: Sources = sources(), e: V2Expectations = expect) =
    GoldValidator.validateV3(s, e)

  private def named(r: Report): Map[String, Check] =
    r.checks.map(c => c.name -> c).toMap

  private def edit(rows: Vector[String], i: Int)(
      change: ujson.Value => Unit
  ): Vector[String] =
    val json = ujson.read(rows(i))
    change(json)
    rows.updated(i, ujson.write(json))

  private def strataJson: Vector[String] =
    val s = Map(
      ids(0) -> "exact-id",
      ids(1) -> "clear-non-match",
      ids(5) -> "clear-non-match"
    )
    ids.map(id =>
      s"""{"pair_id":"$id","stratum":"${s
          .getOrElse(id, "hard-common-name")}"}"""
    )

  test("validator v3: a correct file passes and asks for no totals") {
    val report = run()
    assertEquals(report.failed, Vector.empty[Check], report.render)
    assertEquals(named(report)("label-totals").status, Status.Skip)
    Vector(
      "entry-count",
      "pass-field",
      "pass1-file",
      "pass2-file",
      "pass3-is-subset",
      "subsets-partition",
      "pass3-blind",
      "pass2-carried",
      "no-suggestion-labels"
    ).foreach(n => assertEquals(named(report)(n).status, Status.Pass, n))
  }

  test("validator v3: review time per pass is printed first, then counts") {
    val report = run()
    val shown = report.render.linesIterator.toVector
    assert(shown.head.startsWith("REVIEW TIME AND PASSES"), shown.head)
    val text = shown.take(6).mkString("\n")
    assert(text.contains("REVIEW TIME pass 1, all 6 rows of gold.jsonl"), text)
    assert(text.contains("labeled with suggestions"), text)
    assert(text.contains("span 0.8 min"), text)
    assert(text.contains("REVIEW TIME pass 2, 3 blind relabels"), text)
    assert(text.contains("gold-v2.jsonl"), text)
    assert(text.contains("REVIEW TIME pass 3, 3 blind relabels"), text)
    assert(text.contains("median gap 10.0 s"), text)
    assert(text.contains("0 gaps under 2 s"), text)
    assert(text.contains("pass 2 3 (same 1, different 1, unsure 1)"), text)
    assert(text.contains("pass 3 3 (same 0, different 2, unsure 1)"), text)
    assert(
      text.contains("pass 2: 3 labels, pass 3: 3 labels were reviewed blind"),
      text
    )
    assert(text.contains("no label in v3 is a with-suggestion label"), text)
    assertEquals(ujson.read(report.toJson)("headline").arr.size, 6)
  }

  test(
    "validator v3: a pass 3 row with a suggestion or a wrong pass-1 label fails"
  ) {
    val broken = Vector(
      "suggested_label" -> ujson.Str("same"),
      "suggestion_reason" -> ujson.Str("because"),
      "suggestion_source_file" -> ujson.Str("sugg.csv"),
      "suggestion_source_sha256" -> ujson.Str("0" * 64),
      "changed_from_suggestion" -> ujson.True
    )
    broken.foreach { (key, value) =>
      val rows = edit(v3Lines, 0)(j => j(key) = value)
      val check = named(run(sources(gold = rows)))("pass3-blind")
      assertEquals(check.status, Status.Fail, key)
      assert(check.detail.contains("line 1") && check.detail.contains(key))
    }
    val wrong = edit(v3Lines, 0)(j => j("pass1_label") = "different")
    assertEquals(
      named(run(sources(gold = wrong)))("pass3-blind").status,
      Status.Fail
    )
    val gone = edit(v3Lines, 0)(j => j.obj.remove("pass1_label"))
    assertEquals(
      named(run(sources(gold = gone)))("pass3-blind").status,
      Status.Fail
    )
    val bare = edit(v3Lines, 0) { j =>
      j.obj.remove("suggested_label")
      j.obj.remove("changed_from_suggestion")
    }
    assertEquals(
      named(run(sources(gold = bare)))("pass3-blind").status,
      Status.Pass
    )
  }

  test("validator v3: pass-1 labels are checked against gold.jsonl") {
    val other = ("gold.jsonl", goldBytes.toArray)
    val shortFirst =
      run(sources(first = Some(("gold.jsonl", Array.empty[Byte]))))
    assertEquals(named(shortFirst)("pass1-file").status, Status.Fail)
    assertEquals(
      named(run(sources(first = None)))("pass3-blind").status,
      Status.Fail
    )
    assertEquals(
      named(run(sources(first = Some(other))))("pass3-blind").status,
      Status.Pass
    )
  }

  test("validator v3: pass-2 rows must equal gold-v2 and point at it") {
    Vector(
      "verdict" -> ujson.Str("match"),
      "reviewer" -> ujson.Str("someone"),
      "reviewedAt" -> ujson.Str("2026-11-09T00:00:00Z"),
      "pass1_label" -> ujson.Str("unsure"),
      "carried_from" -> ujson.Str("elsewhere.jsonl"),
      "carried_from_sha256" -> ujson.Str("0" * 64)
    ).foreach { (key, value) =>
      val rows = edit(v3Lines, 2)(j => j(key) = value)
      val check = named(run(sources(gold = rows)))("pass2-carried")
      assertEquals(check.status, Status.Fail, key)
      assert(
        check.detail.contains("line 3") && check.detail.contains(key),
        check.detail
      )
    }
    val dropped = edit(v3Lines, 2)(j => j.obj.remove("pass1_label"))
    assertEquals(
      named(run(sources(gold = dropped)))("pass2-carried").status,
      Status.Fail
    )
    val otherDigest = ("gold-v2.jsonl", v2Bytes ++ "\n".getBytes(UTF_8))
    assertEquals(
      named(run(sources(second = Some(otherDigest))))("pass2-carried").status,
      Status.Fail,
      "the digest must be that of the gold-v2 file given"
    )
    assertEquals(
      named(run(sources(second = None)))("pass2-carried").status,
      Status.Fail
    )
  }

  test("validator v3: pass-3 rows are exactly the subset") {
    val missing = run(sources(sub = Some(pass3 :+ ids(2))))
    assert(named(missing)("pass3-is-subset").detail.contains("not relabeled"))
    val extra = run(sources(sub = Some(pass3.drop(1))))
    assert(
      named(extra)("pass3-is-subset").detail.contains("outside the subset")
    )
    assertEquals(
      named(run(sources(sub = None)))("pass3-is-subset").status,
      Status.Fail
    )
    val repeated = run(sources(sub = Some(pass3 :+ pass3.head)))
    assertEquals(named(repeated)("pass3-is-subset").status, Status.Fail)
  }

  test("validator v3: the two subsets are disjoint and cover every pair") {
    val overlap = run(sources(sub2 = Some(subset2 :+ pass3.head)))
    assertEquals(named(overlap)("subsets-partition").status, Status.Fail)
    val lacking = run(sources(sub2 = Some(subset2.drop(1))))
    assertEquals(named(lacking)("subsets-partition").status, Status.Fail)
    val without = run(sources(sub2 = None))
    assertEquals(named(without)("subsets-partition").status, Status.Pass)
    val noFile = run(sources(second = None, sub2 = None))
    assertEquals(named(noFile)("subsets-partition").status, Status.Fail)
    val wrong = run(sources(sub = Some(pass3.drop(1) :+ subset2.head)))
    assertEquals(named(wrong)("subsets-partition").status, Status.Fail)
  }

  test("validator v3: every row is pass 2 or pass 3") {
    Vector(
      edit(v3Lines, 0)(j => j("pass") = ujson.Num(1)),
      edit(v3Lines, 0)(j => j.obj.remove("pass")),
      edit(v3Lines, 0)(j => j("pass") = ujson.Num(4))
    ).foreach { rows =>
      val check = named(run(sources(gold = rows)))("pass-field")
      assertEquals(check.status, Status.Fail)
      assert(check.detail.contains("line 1"), check.detail)
    }
  }

  test("validator v3: a suggestion anywhere is reported and not claimed away") {
    val rows = edit(v3Lines, 2)(j => j("suggested_label") = "same")
    val report = run(sources(gold = rows))
    assertEquals(named(report)("no-suggestion-labels").status, Status.Fail)
    val head = report.headline.mkString("\n")
    assert(!head.contains("no label in v3 is a with-suggestion label"), head)
    assert(head.contains("WARNING"), head)
    assert(!report.passed)
  }

  test(
    "validator v3: the other checks still fail on the faults they must catch"
  ) {
    def failing(rows: Vector[String], p: Vector[String] = pairLines) =
      GoldValidator
        .validateV3(
          Sources(
            rows,
            p,
            None,
            None,
            None,
            Some(goldFile),
            Some(pass3),
            Some(v2File),
            Some(subset2)
          ),
          expect
        )
        .failed
        .map(_.name)
        .toSet
    assert(failing(v3Lines.drop(1)).contains("entry-count"))
    assert(failing(v3Lines.drop(1)).contains("gold-pairs-coverage"))
    assert(failing(v3Lines :+ v3Lines.head).contains("gold-pair-ids"))
    assert(
      failing(edit(v3Lines, 0)(j => j("reviewMethod") = "model"))
        .contains("human-review-fields")
    )
    assert(
      failing(edit(v3Lines, 5)(j => j("left_snapshot_id") = "dblp:2020"))
        .contains("dblp-snapshot")
    )
    assert(failing(v3Lines, pairLines :+ pairLines.head).contains("pairs-file"))
    assert(failing(v3Lines.updated(1, "{nope")).contains("loads-as-GoldLabel"))
  }

  test("validator v3: totals are optional and checked when given") {
    val right = run(
      e = expect.copy(same = Some(1), different = Some(3), unsure = Some(2))
    )
    assertEquals(named(right)("label-totals").status, Status.Pass, right.render)
    assertEquals(
      named(run(e = expect.copy(same = Some(95))))("label-totals").status,
      Status.Fail
    )
  }

  test("validator v3: pass 1 against pass 3 is descriptive and lists changes") {
    val report = run()
    assert(report.passed, report.render)
    val text = report.compare.mkString("\n")
    assert(text.contains("descriptive only, not a threshold input"), text)
    assert(
      text.contains("all 3 pass-1 pairs were re-reviewed blind in pass 3"),
      text
    )
    assert(text.contains("exact agreement 1 of 3"), text)
    assert(text.contains("n 2, excluded for unsure: 1"), text)
    assert(text.contains("kappa 0.000"), text)
    assert(
      text.contains(
        "changes pass 1->pass 3: same->different 1, same->unsure 1"
      ),
      text
    )
    assert(text.contains("disagreements: 2"), text)
    val d = report.compare.filter(_.startsWith("disagree "))
    assert(
      d.exists(l =>
        l.contains(ids(0)) && l.contains("first=same second=different")
      )
    )
    assert(
      d.exists(l =>
        l.contains(ids(5)) && l.contains("first=same second=unsure")
      )
    )
    assert(!text.contains("stratum"), "no strata without --strata-key")
    assert(
      report.render.contains("COMPARE descriptive only, not a threshold input")
    )
  }

  test("validator v3: stratum-wise changes appear only with a strata key") {
    val text = run(sources(strata = Some(strataJson))).compare.mkString("\n")
    assert(
      text.contains("stratum exact-id: 1 pairs, 0 agree, 1 disagree"),
      text
    )
    assert(
      text.contains("stratum clear-non-match: 2 pairs, 1 agree, 1 disagree"),
      text
    )
  }

  test("validator v3: the comparison never changes pass or fail") {
    val a = run()
    val bare = a.copy(compare = Vector.empty)
    assertEquals(a.passed, bare.passed)
    assertEquals(a.checks, bare.checks)
  }

  test("validator v3 reads no matcher output") {
    val relative = "modules/entity-resolution/src/main/scala/scholarchain/er"
    val dir = Vector(
      Paths.get(relative, "eval/validation"),
      Paths.get("src/main/scala/scholarchain/er", "eval/validation")
    ).find(Files.isDirectory(_)).getOrElse(fail("cannot locate validation"))
    val texts = Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".scala"))
      .toVector
      .map(p => p -> Files.readString(p, UTF_8))
    assert(texts.nonEmpty)
    val found = for
      (p, t) <- texts
      w <- Vector(
        "Matcher",
        "ResolutionRecord",
        "MatchOutcome",
        "decisions.jsonl"
      )
      if t.contains(w)
    yield s"${p.getFileName} mentions $w"
    assertEquals(found, Vector.empty[String])
  }

  // ---------------------------------------------------------------- arguments

  private val base = Seq("--pairs", "p", "--gold", "g")
  private val v3 = Seq(
    "--mode",
    "v3",
    "--pass1",
    "first",
    "--pass2file",
    "second",
    "--subset",
    "s3"
  )

  test("v3 arguments: first pass, gold-v2 and subset are required") {
    val ok = GoldValidator.parseArgs(base ++ v3).toOption.get.v3.get
    assertEquals(
      (ok.pass1.toString, ok.pass2File.toString, ok.subset.toString),
      ("first", "second", "s3")
    )
    assertEquals(ok.pass2Subset, None)
    assertEquals(ok.expect.total, 230)
    assertEquals(ok.expect.dblpSnapshot, "dblp:2026-11-05")
    val full = GoldValidator
      .parseArgs(
        base ++ v3 ++ Seq("--pass2-subset", "s2", "--strata-key", "k") ++
          Seq("--reviewer", "r", "--expect-same", "3")
      )
      .toOption
      .get
    assertEquals(full.v3.get.pass2Subset.map(_.toString), Some("s2"))
    assertEquals(full.strata.map(_.toString), Some("k"))
    assertEquals(full.v3.get.expect.reviewer, Some("r"))
    assertEquals(full.v3.get.expect.same, Some(3))
    assertEquals(GoldValidator.parseArgs(base ++ v3).toOption.get.v2, None)
  }

  test("v3 arguments: missing, mixed or confusing options are refused") {
    assert(GoldValidator.parseArgs(base ++ Seq("--mode", "v3")).isLeft)
    Vector("--pass1", "--pass2file", "--subset").foreach { drop =>
      val kept = v3.grouped(2).toVector.flatMap {
        case Seq(`drop`, _) => Seq.empty
        case other          => other
      }
      assert(GoldValidator.parseArgs(base ++ kept).isLeft, drop)
    }
    assert(
      GoldValidator.parseArgs(base ++ v3 ++ Seq("--expect-changed", "1")).isLeft
    )
    assert(
      GoldValidator.parseArgs(base ++ v3 ++ Seq("--suggestions", "c")).isLeft
    )
    assert(GoldValidator.parseArgs(base ++ Seq("--pass2file", "f")).isLeft)
    assert(GoldValidator.parseArgs(base ++ Seq("--pass2-subset", "f")).isLeft)
    val v2 = Seq("--mode", "v2", "--pass1", "f", "--subset", "s")
    assert(
      GoldValidator.parseArgs(base ++ v2 ++ Seq("--pass2file", "x")).isLeft
    )
    val sameAsGold = Seq("--pairs", "p", "--gold", "second") ++ v3.drop(0)
    assert(
      GoldValidator.parseArgs(sameAsGold).isLeft,
      "gold may not be pass2file"
    )
    val firstIsGold = Seq("--pairs", "p", "--gold", "first") ++ v3
    assert(GoldValidator.parseArgs(firstIsGold).isLeft, "gold may not be pass1")
    val twins =
      Seq("--mode", "v3", "--pass1", "f", "--pass2file", "f", "--subset", "s")
    assert(GoldValidator.parseArgs(base ++ twins).isLeft)
  }

  test("the v3 files are named in the printed digests") {
    val a = GoldValidator
      .parseArgs(
        Seq(
          "--pairs",
          shared.pairs.toString,
          "--gold",
          shared.file("gold-v3.jsonl").toString
        ) ++
          Seq("--mode", "v3", "--pass1", shared.file("gold.jsonl").toString) ++
          Seq("--pass2file", shared.file("gold-v2.jsonl").toString) ++
          Seq("--subset", shared.file("pass3.txt").toString)
      )
      .toOption
      .get
    Files.writeString(
      shared.file("pass3.txt"),
      pass3.mkString("\n") + "\n",
      UTF_8
    )
    val shown = GoldValidator.digests(a).mkString("\n")
    assert(shown.contains("gold-v2.jsonl"), shown)
    assert(shown.contains("gold.jsonl"), shown)
    assert(shown.contains("pass3.txt"), shown)
    val sources = GoldValidator.readSources(a)
    assertEquals(sources.pass1.map(_._1), Some("gold.jsonl"))
    assertEquals(sources.pass2File.map(_._1), Some("gold-v2.jsonl"))
    assertEquals(sources.subset, Some(pass3))
  }
