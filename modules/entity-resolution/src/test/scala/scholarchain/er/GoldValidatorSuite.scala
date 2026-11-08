package scholarchain.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scholarchain.core.{ContractSchema, SourceType}
import scholarchain.er.Fixtures.*
import scholarchain.er.eval.Verdict
import scholarchain.er.eval.labeling.LabelSession
import scholarchain.er.eval.validation.*
import scholarchain.er.matching.{EntityKind, SideRecord}
import scholarchain.er.pairs.{PairItem, PairSide, PairsFile}

class GoldValidatorSuite extends munit.FunSuite:
  private val snapshot = "snap-1"
  private val expect = Expectations(6, 3, 2, 1, 2, snapshot, Some("tester"))

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

  // pair, suggestion, final label
  private val plan = Vector(
    (items(0), "same", Verdict.Match),
    (items(1), "different", Verdict.NonMatch),
    (items(2), "different", Verdict.Match),
    (items(3), "same", Verdict.Unsure),
    (items(4), "different", Verdict.NonMatch),
    (items(5), "same", Verdict.Match)
  )

  private val csvText =
    "pair_id,suggested_label,reason\n" + plan
      .map((p, s, _) => s"${p.pairId},$s,because $s")
      .mkString("\n") + "\n"

  private final case class Fixture(
      gold: Vector[String],
      pairs: Vector[String],
      csv: (String, Array[Byte]),
      strata: Vector[String]
  )

  private def build(): Fixture =
    val dir = Files.createTempDirectory("er-validate")
    val pairsPath = dir.resolve("pairs.jsonl")
    val goldPath = dir.resolve("gold.jsonl")
    val csvPath = dir.resolve("suggestions.csv")
    PairsFile.write(pairsPath, items)
    Files.writeString(csvPath, csvText, UTF_8)
    val clock = () => Instant.parse("2026-11-07T10:00:00Z")
    val session = LabelSession
      .open(pairsPath, goldPath, Some(csvPath), "tester", clock)
      .fold(message => fail(message), identity)
    plan.foreach((p, _, v) => session.label(p.pairId, v, None))
    val strata = items.zipWithIndex.map { (p, i) =>
      val s = if i % 2 == 0 then "exact-id" else "clear-non-match"
      s"""{"pair_id":"${p.pairId}","stratum":"$s"}"""
    }
    Fixture(
      Files.readAllLines(goldPath, UTF_8).asScala.toVector,
      Files.readAllLines(pairsPath, UTF_8).asScala.toVector,
      ("suggestions.csv", Files.readAllBytes(csvPath)),
      strata
    )

  private val fx = build()

  private def sources(
      gold: Vector[String] = fx.gold,
      pairs: Vector[String] = fx.pairs,
      csv: (String, Array[Byte]) = fx.csv,
      strata: Vector[String] = fx.strata
  ): Sources = Sources(gold, pairs, Some(csv), Some(strata), None)

  private def run(
      s: Sources = sources(),
      e: Expectations = expect
  ): Map[String, Check] =
    GoldValidator.validate(s, e).checks.map(c => c.name -> c).toMap

  private def failures(checks: Map[String, Check]): Set[String] =
    checks.collect { case (name, c) if c.status == Status.Fail => name }.toSet

  private def edit(lines: Vector[String], i: Int)(
      change: ujson.Value => Unit
  ): Vector[String] =
    val json = ujson.read(lines(i))
    change(json)
    lines.updated(i, ujson.write(json))

  test("the fixture really has the shape the expectations describe") {
    val labels = fx.gold.map(l => ujson.read(l)("verdict").str)
    assertEquals(labels.count(_ == "match"), 3)
    assertEquals(labels.count(_ == "non-match"), 2)
    assertEquals(labels.count(_ == "unsure"), 1)
    val changed =
      fx.gold.count(l => ujson.read(l)("changed_from_suggestion").bool)
    assertEquals(changed, 2)
  }

  test("a clean label file passes every check and none is skipped") {
    val report = GoldValidator.validate(sources(), expect)
    assertEquals(report.failed, Vector.empty[Check], report.render)
    assert(report.passed)
    assert(report.checks.forall(_.status == Status.Pass), report.render)
    assert(report.render.contains("VALIDATION PASSED"))
  }

  test("optional inputs that are absent are skipped, not passed") {
    val s = Sources(fx.gold, fx.pairs, None, None, None)
    val checks = GoldValidator.validate(s, expect).checks.map(c => c.name -> c)
    val skipped = checks.collect { case (n, c) if c.status == Status.Skip => n }
    assertEquals(skipped.toSet, Set("suggestions-csv", "strata-key"))
  }

  test("a wrong entry count fails") {
    val checks = run(e = expect.copy(total = 230))
    assertEquals(checks("entry-count").status, Status.Fail)
    assert(checks("entry-count").detail.contains("6 entries, expected 230"))
  }

  test("a repeated gold pair_id fails") {
    val checks = run(sources(gold = fx.gold :+ fx.gold.head))
    assert(failures(checks).contains("gold-pair-ids"))
    assert(checks("gold-pair-ids").detail.contains(items(0).pairId))
  }

  test("an itemId that differs from pair_id fails") {
    val gold = edit(fx.gold, 1)(j => j("pair_id") = items(0).pairId)
    assert(failures(run(sources(gold = gold))).contains("gold-pair-ids"))
  }

  test("a pair with no gold row, and a gold row with no pair, both fail") {
    val missing = run(sources(gold = fx.gold.drop(1)))
    assertEquals(missing("gold-pairs-coverage").status, Status.Fail)
    assert(missing("gold-pairs-coverage").detail.contains("no gold"))
    val stray = edit(fx.gold, 0) { j =>
      j("pair_id") = "pair:v1:stray"
      j("itemId") = "pair:v1:stray"
    }
    val extra = run(sources(gold = fx.gold.tail :+ stray(0)))
    assertEquals(extra("gold-pairs-coverage").status, Status.Fail)
    assert(extra("gold-pairs-coverage").detail.contains("no pair"))
  }

  test("a duplicated pairs file fails and the dependent checks say why") {
    val checks = run(sources(pairs = fx.pairs :+ fx.pairs.head))
    assertEquals(checks("pairs-file").status, Status.Fail)
    assertEquals(checks("gold-pairs-coverage").status, Status.Fail)
    assert(
      checks("gold-pairs-coverage").detail.contains("needs the pairs file")
    )
  }

  test("every bad row is reported with its line number, not only the first") {
    val first = edit(fx.gold, 1)(j => j("reviewMethod") = "model")
    val second = edit(first, 3)(j => j("reviewedAt") = "yesterday")
    val broken = second.updated(4, "{not json")
    val checks = run(sources(gold = broken))
    val detail = checks("loads-as-GoldLabel").detail
    assertEquals(checks("loads-as-GoldLabel").status, Status.Fail)
    assert(detail.contains("line 2") && detail.contains("line 4"), detail)
    assert(detail.contains("line 5"), detail)
    assertEquals(checks("entry-count").status, Status.Pass)
  }

  test("a label that is not human, unsigned or untimed fails the human check") {
    val method = edit(fx.gold, 0)(j => j("reviewMethod") = "model")
    assert(
      failures(run(sources(gold = method))).contains("human-review-fields")
    )
    val signer = edit(fx.gold, 0)(j => j("reviewer") = " ")
    assert(
      failures(run(sources(gold = signer))).contains("human-review-fields")
    )
    val time = edit(fx.gold, 0)(j => j("reviewedAt") = "soon")
    assert(failures(run(sources(gold = time))).contains("human-review-fields"))
    val source = edit(fx.gold, 0)(j => j("leftSourceRecordId") = "")
    assert(
      failures(run(sources(gold = source))).contains("human-review-fields")
    )
  }

  test("an unexpected reviewer fails when a reviewer is expected") {
    val checks = run(e = expect.copy(reviewer = Some("someone-else")))
    assertEquals(checks("human-review-fields").status, Status.Fail)
  }

  test(
    "a DBLP side on another snapshot fails, a non-DBLP side is only listed"
  ) {
    val old = edit(fx.gold, 0)(j => j("left_snapshot_id") = "dblp:2026-11-01")
    assert(failures(run(sources(gold = old))).contains("dblp-snapshot"))
    val other = edit(fx.gold, 5)(j => j("right_snapshot_id") = "openalex:other")
    val checks = run(sources(gold = other))
    assertEquals(checks("dblp-snapshot").status, Status.Pass)
    assertEquals(checks("gold-agrees-with-pairs").status, Status.Fail)
    val wrongPairs = fx.pairs.map(_.replace(snapshot, "dblp:2026-11-01"))
    val relabeled = run(sources(pairs = wrongPairs))
    assertEquals(relabeled("dblp-snapshot").status, Status.Fail)
  }

  test("the snapshots found per source are reported as information") {
    val info = GoldValidator.validate(sources(), expect).info
    assert(info.exists(_.startsWith("snapshots of openalex: openalex:2026")))
    assert(info.exists(_.startsWith("snapshots of dblp: snap-1")))
  }

  test("label totals that differ from the reported facts fail") {
    val checks = run(e = expect.copy(same = 4, different = 1))
    assertEquals(checks("label-totals").status, Status.Fail)
    assert(checks("label-totals").detail.contains("3/2/1"))
  }

  test("a row without a usable suggestion fails") {
    val gone = edit(fx.gold, 2) { j =>
      j("suggested_label") = ujson.Null
    }
    val checks = run(sources(gold = gone))
    assertEquals(checks("suggestion-on-every-row").status, Status.Fail)
    assert(checks("suggestion-on-every-row").detail.contains("line 3"))
    val junk = edit(fx.gold, 2)(j => j("suggested_label") = "maybe")
    assert(
      failures(run(sources(gold = junk))).contains("suggestion-on-every-row")
    )
  }

  test("the changed count is recomputed from the labels, not trusted") {
    assertEquals(run()("changed-from-suggestion").status, Status.Pass)
    val lying = edit(fx.gold, 2)(j => j("changed_from_suggestion") = false)
    val checks = run(sources(gold = lying))
    assertEquals(checks("changed-from-suggestion").status, Status.Fail)
    assert(checks("changed-from-suggestion").detail.contains("1 stored flags"))
    val recount = run(e = expect.copy(changed = 64))
    assertEquals(recount("changed-from-suggestion").status, Status.Fail)
    assert(recount("changed-from-suggestion").detail.contains("recomputed 2"))
  }

  test("the suggestion source must be one file and one SHA-256 on every row") {
    assertEquals(run()("suggestion-source").status, Status.Pass)
    val other = "0" * 64
    val split = edit(fx.gold, 1)(j => j("suggestion_source_sha256") = other)
    assertEquals(
      run(sources(gold = split))("suggestion-source").status,
      Status.Fail
    )
    val short = edit(fx.gold, 1)(j => j("suggestion_source_sha256") = "abc")
    val allShort = fx.gold.indices.foldLeft(fx.gold) { (lines, i) =>
      edit(lines, i)(j => j("suggestion_source_sha256") = "abc")
    }
    assertEquals(
      run(sources(gold = short))("suggestion-source").status,
      Status.Fail
    )
    assertEquals(
      run(sources(gold = allShort))("suggestion-source").status,
      Status.Fail
    )
    val noFile = edit(fx.gold, 1)(j => j("suggestion_source_file") = ujson.Null)
    assertEquals(
      run(sources(gold = noFile))("suggestion-source").status,
      Status.Fail
    )
  }

  test("a suggestion file that differs from the one the gold records fails") {
    val bytes =
      (csvText.replace("because same", "because other")).getBytes(UTF_8)
    val changedReason = run(sources(csv = ("suggestions.csv", bytes)))
    assertEquals(changedReason("suggestions-csv").status, Status.Fail)
    val renamed = run(sources(csv = ("other.csv", fx.csv._2)))
    assertEquals(renamed("suggestions-csv").status, Status.Fail)
    assert(renamed("suggestions-csv").detail.contains("file name"))
    val relabeled = csvText.replaceFirst(",same,", ",unsure,")
    val checks =
      run(sources(csv = ("suggestions.csv", relabeled.getBytes(UTF_8))))
    assertEquals(checks("suggestions-csv").status, Status.Fail)
    assert(checks("suggestions-csv").detail.contains("label differs"))
  }

  test("a malformed suggestion file fails with the parser's reason") {
    val checks = run(sources(csv = ("suggestions.csv", "nope".getBytes(UTF_8))))
    assertEquals(checks("suggestions-csv").status, Status.Fail)
  }

  test("the private strata key must cover the same pairs") {
    assertEquals(run()("strata-key").status, Status.Pass)
    val checks = run(sources(strata = fx.strata.drop(1)))
    assertEquals(checks("strata-key").status, Status.Fail)
    val bad = run(sources(strata = Vector("{")))
    assertEquals(bad("strata-key").status, Status.Fail)
  }

  test("the review time spread is reported without being judged") {
    val info = GoldValidator.validate(sources(), expect).info
    assert(
      info.exists(_.startsWith("reviewedAt 2026-11-07T10:00:00Z")),
      info.toString
    )
    assert(info.exists(_.startsWith("suggestion->final: ")))
    assert(info.exists(_.startsWith("reviewers: tester")))
  }

  test("the report renders and serializes with every check named") {
    val report = GoldValidator.validate(sources(), expect)
    val json = ujson.read(report.toJson)
    assertEquals(json("passed").bool, true)
    assertEquals(json("checks").arr.size, report.checks.size)
    val names = report.checks.map(_.name)
    assertEquals(names.distinct.size, names.size)
    Vector("entry-count", "label-totals", "changed-from-suggestion").foreach {
      name => assert(names.contains(name), name)
    }
  }

  test("arguments are checked: required, unknown and non-numeric options") {
    val ok = GoldValidator.parseArgs(Seq("--pairs", "p", "--gold", "g"))
    assertEquals(ok.map(_.expect), Right(Expectations.phase5))
    assert(GoldValidator.parseArgs(Seq("--gold", "g")).isLeft)
    assert(
      GoldValidator
        .parseArgs(Seq("--pairs", "p", "--gold", "g", "--x", "1"))
        .isLeft
    )
    val numeric = Seq("--pairs", "p", "--gold", "g", "--expect-total", "many")
    assert(GoldValidator.parseArgs(numeric).isLeft)
  }

  test("the phase 5 facts are the defaults of the validator") {
    val e = Expectations.phase5
    assertEquals((e.total, e.same, e.different, e.unsure), (230, 95, 131, 4))
    assertEquals((e.changed, e.dblpSnapshot), (64, "dblp:2026-11-05"))
  }

  test("the validation sources do not touch matching or adjudication code") {
    val dir = Vector(
      Paths.get(
        "modules/entity-resolution/src/main/scala/scholarchain/er/eval/validation"
      ),
      Paths.get("src/main/scala/scholarchain/er/eval/validation")
    ).find(Files.isDirectory(_)).getOrElse(fail("cannot locate the sources"))
    val files = Files.list(dir).iterator().asScala.toVector
    assert(files.nonEmpty)
    val forbidden = Vector(
      "scholarchain.er.matching",
      "scholarchain.er.llm",
      "Matcher",
      "ResolutionRecord"
    )
    val found = for
      file <- files
      text = Files.readString(file, UTF_8)
      word <- forbidden if text.contains(word)
    yield s"${file.getFileName} mentions $word"
    assertEquals(found, Vector.empty[String])
  }
