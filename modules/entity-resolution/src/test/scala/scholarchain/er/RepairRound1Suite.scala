package scholarchain.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scholarchain.core.ContractSchema
import scholarchain.er.Fixtures.*
import scholarchain.er.eval.assess.*
import scholarchain.er.eval.{GoldLabel, Verdict}
import scholarchain.er.matching.{EntityKind, RecordIndex, SideRecord}
import scholarchain.er.pairs.{Locations, PairItem, PairSide, PairsFile}

/** Review round 1: findings 2 (disclosure), 11 (decisions before labels), 16
  * (symlinks) and the before/after decision comparison.
  */
class RepairRound1Suite extends munit.FunSuite:

  // ------------------------------------------------------------ disclosure

  test("the uncertainty text says pooled precision misses the other paths") {
    val lines = GoldEvaluation.uncertainty(10, 1, 0)
    val text = lines.mkString("\n")
    assert(text.contains("Pooled precision does not measure the probabilistic"))
    assert(text.contains("includeAgreeingContext"))
    assert(text.contains("exact-id stratum is selected by the same shared"))
  }

  test("the export manifest's counts are printed when it carries them") {
    val json =
      """{"includeAgreeingContext":false,"excluded":{"unclassified":4358,""" +
        """"same-name-agreeing-context":5766}}"""
    val note = GoldEvaluation.exportNote(json).getOrElse(fail("no note"))
    assert(note.contains("includeAgreeingContext false"), note)
    assert(note.contains("5766"), note)
    assertEquals(
      GoldEvaluation
        .exportNote("""{"includeAgreeingContext":true}""")
        .isDefined,
      true
    )
    assertEquals(GoldEvaluation.exportNote("{}"), None)
    assertEquals(GoldEvaluation.exportNote("not json"), None)
  }

  // ------------------------------------------------------------ Locations

  private def repoAt(parent: Path, name: String): Path =
    val root = parent.resolve(name)
    Files.createDirectories(root)
    Files.writeString(root.resolve("build.sbt"), "", UTF_8)
    root

  test("a symlink into the repository does not hide the repository") {
    val parent = Files.createTempDirectory("er-links")
    val root = repoAt(parent, "repo")
    val elsewhere = Files.createTempDirectory("er-else")
    val link = Files.createSymbolicLink(parent.resolve("link"), root)
    val viaLink = link.resolve("gold/pairs.jsonl")
    assert(Locations.violation(viaLink, elsewhere).isDefined, "link to repo")
    assert(Locations.violation(Paths.get(viaLink.toString), root).isDefined)
  }

  test("a symlinked working directory still finds its repository") {
    val parent = Files.createTempDirectory("er-links")
    val root = repoAt(parent, "repo")
    val link = Files.createSymbolicLink(parent.resolve("wd"), root)
    val target = root.resolve("gold/pairs.jsonl")
    assert(Locations.violation(target, link).isDefined)
  }

  test("a path in a different repository is refused too") {
    val parent = Files.createTempDirectory("er-two")
    val mine = repoAt(parent, "mine")
    val other = repoAt(parent, "other")
    val message = Locations.violation(other.resolve("out.jsonl"), mine)
    assert(message.exists(_.contains("repository")), message.toString)
  }

  test("a link out of the repository to a free place is allowed") {
    val root = repoAt(Files.createTempDirectory("er-links"), "repo")
    val free = Files.createTempDirectory("er-free")
    val link = Files.createSymbolicLink(
      Files.createTempDirectory("er-l").resolve("l"),
      free
    )
    assertEquals(Locations.violation(link.resolve("x.jsonl"), root), None)
  }

  test("an input directory reached through a link is still protected") {
    val root = repoAt(Files.createTempDirectory("er-links"), "repo")
    val input = Files.createTempDirectory("er-input")
    val link = Files.createSymbolicLink(
      Files.createTempDirectory("er-l").resolve("in"),
      input
    )
    assert(
      Locations.violation(input.resolve("x"), root, Vector(link)).isDefined
    )
  }

  // ------------------------------------------- the run, end to end

  private val w1 =
    coreWork("dblp", "k1", "Title one alpha", Vector("Jane Doe", "Bo Li"), None)
  private val w2 = coreWork(
    "dblp",
    "k2",
    "Title two beta",
    Vector("Jane Doe", "Cy Zhou"),
    None
  )
  private val index = RecordIndex.fromCore(Vector(w1, w2))
  private val sides: (SideRecord, SideRecord) =
    val a = index.get(w1.authors.head.mentionId).get
    val b = index.get(w2.authors.head.mentionId).get
    if a.id < b.id then (a, b) else (b, a)
  private val pair = PairItem(
    ContractSchema.currentVersion,
    PairItem.idOf(EntityKind.Researcher, sides._1.id, sides._2.id),
    EntityKind.Researcher,
    runId,
    PairItem.exportVersion,
    PairSide.of(sides._1),
    PairSide.of(sides._2)
  )

  private def goldLine(gold: Verdict): String =
    upickle.default.write(
      GoldLabel(
        ContractSchema.currentVersion,
        pair.pairId,
        pair.kind,
        pair.left.recordId,
        pair.right.recordId,
        pair.left.sourceRecordId,
        pair.right.sourceRecordId,
        "snap-1",
        gold,
        "human",
        "tester",
        "2026-11-07T10:00:00Z",
        None
      )
    )

  private final case class Inputs(dir: Path, gold: Path):
    def args(out: Path, extra: Seq[String] = Seq.empty): Array[String] =
      (Seq(
        "--pairs",
        dir.resolve("pairs.jsonl").toString,
        "--gold",
        gold.toString,
        "--records",
        dir.resolve("works").toString,
        "--strata-key",
        dir.resolve("private/strata-key.jsonl").toString,
        "--out",
        out.toString
      ) ++ extra).toArray

  private def inputs(goldText: String): Inputs =
    val dir = Files.createTempDirectory("er-run")
    Files.createDirectories(dir.resolve("works"))
    Files.createDirectories(dir.resolve("private"))
    PairsFile.write(dir.resolve("pairs.jsonl"), Vector(pair))
    Files.writeString(
      dir.resolve("works/part-r-00000"),
      Vector(w1, w2)
        .map(w => upickle.default.write(w))
        .mkString("", "\n", "\n"),
      UTF_8
    )
    Files.writeString(
      dir.resolve("private/strata-key.jsonl"),
      s"""{"pair_id":"${pair.pairId}","stratum":"hard-common-name"}\n""",
      UTF_8
    )
    Files.writeString(
      dir.resolve("private/export-manifest.json"),
      """{"includeAgreeingContext":false,"excluded":""" +
        """{"same-name-agreeing-context":5766}}""",
      UTF_8
    )
    val gold = dir.resolve("gold.jsonl")
    Files.writeString(gold, goldText, UTF_8)
    Inputs(dir, gold)

  test("decisions are on disk before a corrupt label file is parsed") {
    val in = inputs("this is not json\n")
    val out = Files.createTempDirectory("er-out")
    intercept[Exception](EvaluationRun.main(in.args(out)))
    val decisions = out.resolve("decisions.jsonl")
    assert(Files.exists(decisions), "decisions were not written first")
    assertEquals(Files.readAllLines(decisions).size, 1)
    assert(!Files.exists(out.resolve("evaluation-report.txt")))
  }

  test("--compare-decisions lists changed decisions with their gold label") {
    val in = inputs(goldLine(Verdict.Match) + "\n")
    val first = Files.createTempDirectory("er-first")
    EvaluationRun.main(in.args(first))
    val firstText = Files.readString(first.resolve("evaluation-report.txt"))
    assert(!firstText.contains("matcher decisions compared"), firstText)
    assert(firstText.contains("includeAgreeingContext false"), firstText)
    assert(firstText.contains("5766"), firstText)

    val same = Files.createTempDirectory("er-same")
    EvaluationRun.main(
      in.args(
        same,
        Seq("--compare-decisions", first.resolve("decisions.jsonl").toString)
      )
    )
    val sameText = Files.readString(same.resolve("evaluation-report.txt"))
    assert(sameText.contains("== matcher decisions compared"), sameText)
    assert(
      sameText.contains(
        "decisions changed (outcome, path, score or reasons): 0 of 1"
      ),
      sameText
    )

    val line = Files.readAllLines(first.resolve("decisions.jsonl")).get(0)
    val edited = ujson.read(line)
    edited("outcome") = "EARLIER-OUTCOME"
    val earlier = first.resolve("earlier.jsonl")
    Files.writeString(earlier, ujson.write(edited) + "\n", UTF_8)
    val changed = Files.createTempDirectory("er-changed")
    EvaluationRun.main(
      in.args(changed, Seq("--compare-decisions", earlier.toString))
    )
    val text = Files.readString(changed.resolve("evaluation-report.txt"))
    assert(text.contains("changed (outcome, path, score or reasons): 1 of 1"))
    assert(text.contains("decision-change " + pair.pairId), text)
    assert(text.contains("gold=same"), text)
    assert(text.contains("by gold label: same 1"), text)
    assert(text.contains("EARLIER-OUTCOME->"), text)
  }

  test("a decisions file that is not a decision file is refused") {
    assert(DecisionComparison.parse(Vector("{nope")).isLeft)
    assert(DecisionComparison.parse(Vector("{}")).isLeft)
    val row =
      """{"pair_id":"p","kind":"researcher","outcome":"ACCEPTED",""" +
        """"path":"exact-id","score":0.99,"reasonCodes":["a"]}"""
    assert(DecisionComparison.parse(Vector(row, row)).isLeft, "repeated id")
    assertEquals(
      DecisionComparison.parse(Vector(row, "")).map(_.size),
      Right(1)
    )
  }

  test("a change in score, path or reasons alone counts as a change") {
    val base = DecisionRow(
      "p",
      "researcher",
      "REJECTED",
      "probabilistic",
      0.2,
      Vector("r")
    )
    assert(!DecisionComparison.changed(base, base.copy(score = 0.2 + 1e-12)))
    assert(DecisionComparison.changed(base, base.copy(score = 0.3)))
    assert(DecisionComparison.changed(base, base.copy(path = "exact-id")))
    assert(DecisionComparison.changed(base, base.copy(reasons = Vector("s"))))
    assert(DecisionComparison.changed(base, base.copy(outcome = "ACCEPTED")))
  }

  test("pairs present on one side only are counted, not compared") {
    val a = DecisionRow("a", "researcher", "REJECTED", "p", 0.1, Vector("r"))
    val b = a.copy(pairId = "b")
    val text = DecisionComparison
      .lines(
        "e.jsonl",
        "sha",
        Map("a" -> a, "b" -> b),
        Vector(a),
        Vector.empty,
        Map.empty
      )
      .mkString("\n")
    assert(text.contains("only earlier 1, only this run 0"), text)
    assert(text.contains("0 of 1"), text)
  }

  test("--compare-decisions is optional on the command line") {
    val base = Seq(
      "--pairs",
      "p",
      "--gold",
      "g",
      "--records",
      "r",
      "--strata-key",
      "k",
      "--out",
      "o"
    )
    assertEquals(
      EvaluationRun.parseArgs(base).toOption.get.compareDecisions,
      None
    )
    assertEquals(
      EvaluationRun
        .parseArgs(base ++ Seq("--compare-decisions", "d.jsonl"))
        .toOption
        .get
        .compareDecisions,
      Some(Paths.get("d.jsonl"))
    )
  }
