package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.evaluation.er.{
  Decision,
  ErBootstrap,
  ErExtras,
  ErInputs,
  GoldVerdict,
  Outcome,
  PathRecall,
  Scored,
  StratifiedBootstrap,
  Tally
}
import P16W2Support.*

/** ErExtras on a hand-built decisions and gold fixture: per-path recall, the
  * pooled counts, and the stratified bootstrap (seeded, resampling inside the
  * strata, every stratum keeping its size).
  *
  * The fixture (decision path, gold verdict, outcome, stratum):
  *
  * {{{
  *  P01-P03 exact-id      same   ACCEPTED   clear-match
  *  P04     exact-id      same   REJECTED   borderline
  *  P05     exact-id      differ ACCEPTED   clear-non-match
  *  P18     exact-id      differ REJECTED   (not in the strata key)
  *  P06     det-biblio    same   ACCEPTED   clear-match
  *  P07     det-biblio    same   ACCEPTED   borderline
  *  P08     det-biblio    same   UNRESOLVED borderline
  *  P16     det-biblio    same   REJECTED   clear-non-match
  *  P09     det-biblio    differ REJECTED   clear-non-match
  *  P10     det-biblio    differ REJECTED   borderline
  *  P11     probabilistic same   ACCEPTED   borderline
  *  P12     probabilistic same   CONFLICT   borderline
  *  P17     probabilistic same   ACCEPTED   clear-non-match
  *  P13     probabilistic differ ACCEPTED   clear-non-match
  *  P14     probabilistic unsure ACCEPTED   borderline
  *  P15     (no decision) same              clear-match
  *  P99     decision with no gold pair
  * }}}
  *
  * Pooled: 18 gold pairs, 12 same, 5 different, 1 unsure; TP 7, FP 2, so
  * precision 7/9 and recall 7/12.
  */
class P16W2ErExtrasSuite extends FunSuite:
  private val eps = 1e-12

  private val table
      : Vector[(String, String, String, Option[(String, String)])] =
    Vector(
      ("P01", "clear-match", "match", Some("ACCEPTED" -> "exact-id")),
      ("P02", "clear-match", "match", Some("ACCEPTED" -> "exact-id")),
      ("P03", "clear-match", "match", Some("ACCEPTED" -> "exact-id")),
      ("P04", "borderline", "match", Some("REJECTED" -> "exact-id")),
      ("P05", "clear-non-match", "non-match", Some("ACCEPTED" -> "exact-id")),
      ("P18", "", "non-match", Some("REJECTED" -> "exact-id")),
      (
        "P06",
        "clear-match",
        "match",
        Some("ACCEPTED" -> "deterministic-bibliographic")
      ),
      (
        "P07",
        "borderline",
        "match",
        Some("ACCEPTED" -> "deterministic-bibliographic")
      ),
      (
        "P08",
        "borderline",
        "match",
        Some("UNRESOLVED" -> "deterministic-bibliographic")
      ),
      (
        "P16",
        "clear-non-match",
        "match",
        Some("REJECTED" -> "deterministic-bibliographic")
      ),
      (
        "P09",
        "clear-non-match",
        "non-match",
        Some("REJECTED" -> "deterministic-bibliographic")
      ),
      (
        "P10",
        "borderline",
        "non-match",
        Some("REJECTED" -> "deterministic-bibliographic")
      ),
      ("P11", "borderline", "match", Some("ACCEPTED" -> "probabilistic")),
      ("P12", "borderline", "match", Some("CONFLICT" -> "probabilistic")),
      ("P17", "clear-non-match", "match", Some("ACCEPTED" -> "probabilistic")),
      (
        "P13",
        "clear-non-match",
        "non-match",
        Some("ACCEPTED" -> "probabilistic")
      ),
      ("P14", "borderline", "unsure", Some("ACCEPTED" -> "probabilistic")),
      ("P15", "clear-match", "match", None)
    )

  private val decisionLines: Vector[String] =
    table.flatMap { (id, _, _, d) =>
      d.map((o, p) =>
        ujson.write(
          ujson.Obj(
            "pair_id" -> id,
            "kind" -> "bibliographic",
            "outcome" -> o,
            "path" -> p,
            "score" -> 0.9
          )
        )
      )
    } :+ ujson.write(
      ujson.Obj(
        "pair_id" -> "P99",
        "kind" -> "x",
        "outcome" -> "ACCEPTED",
        "path" -> "exact-id"
      )
    )

  private val goldLines: Vector[String] =
    table.map((id, _, g, _) =>
      ujson.write(ujson.Obj("itemId" -> id, "verdict" -> g))
    )

  private val strataLines: Vector[String] =
    table
      .filter(_._2.nonEmpty)
      .map((id, s, _, _) =>
        ujson.write(ujson.Obj("pair_id" -> id, "stratum" -> s))
      )

  private lazy val joined =
    ErInputs.join(
      ErInputs.gold("gold", goldLines),
      ErInputs.decisions("decisions", decisionLines),
      ErInputs.strata("strata", strataLines)
    )
  private lazy val items: Vector[Scored] = joined.items

  // ---- the join ---------------------------------------------------------------------

  test("the join keeps every gold pair and reports what does not line up") {
    assertEquals(items.size, 18)
    assertEquals(joined.decisionsWithoutGold, Vector("P99"))
    assertEquals(joined.goldWithoutDecision, Vector("P15"))
    assertEquals(joined.goldWithoutStratum, Vector("P18"))
    assertEquals(items.find(_.pairId == "P18").map(_.stratum), Some("unknown"))
  }

  // ---- the pooled counts ---------------------------------------------------------------

  test(
    "pooled counts: unsure counts in n only, recall counts every gold same"
  ) {
    val t = Tally.of(items)
    assertEquals((t.same, t.different, t.unsure, t.n), (12L, 5L, 1L, 18L))
    assertEquals((t.truePositive, t.falsePositive), (7L, 2L))
    assertEqualsDouble(t.precision.get, 7.0 / 9.0, eps)
    assertEqualsDouble(t.recall.get, 7.0 / 12.0, eps)
    val p = 7.0 / 9.0
    val r = 7.0 / 12.0
    assertEqualsDouble(t.f1.get, 2 * p * r / (p + r), eps)
  }

  test("a gold-unsure pair that was accepted is neither TP nor FP") {
    val t = Tally.of(items.filter(_.pairId == "P14"))
    assertEquals(
      (t.truePositive, t.falsePositive, t.same, t.different),
      (0L, 0L, 0L, 0L)
    )
    assertEquals(t.precision, None)
    assertEquals(t.recall, None)
    assertEquals(t.f1, None)
  }

  // ---- per-path recall ------------------------------------------------------------------

  private lazy val perPath = PathRecall.compute(items)
  private def row(path: String) = perPath.rows.find(_.path == path).get

  test("the four paths are always listed, in order, and nothing else appears") {
    assertEquals(
      perPath.rows.map(_.path),
      Vector(
        "exact-id",
        "deterministic-bibliographic",
        "probabilistic",
        "llm-adjudicated"
      )
    )
    assertEquals(perPath.totalSame, 12L)
    assertEquals(perPath.sameWithoutDecision, 1L)
  }

  test(
    "exact-id: gold-same 4, accepted 3, rejected 1, recall 3/4, precision 3/4"
  ) {
    val r = row("exact-id")
    assertEquals((r.same, r.sameAccepted, r.sameRejected), (4L, 3L, 1L))
    assertEquals((r.different, r.differentAccepted, r.unsure), (2L, 1L, 0L))
    assertEquals(r.pairs, 6L)
    assertEqualsDouble(r.recall.get, 3.0 / 4.0, eps)
    assertEqualsDouble(r.precision.get, 3.0 / 4.0, eps)
    assertEqualsDouble(r.contribution.get, 3.0 / 12.0, eps)
  }

  test(
    "deterministic-bibliographic: unresolved and rejected gold-same pairs count"
  ) {
    val r = row("deterministic-bibliographic")
    assertEquals(
      (r.same, r.sameAccepted, r.sameRejected, r.sameUnresolved),
      (4L, 2L, 1L, 1L)
    )
    assertEquals((r.different, r.differentAccepted), (2L, 0L))
    assertEqualsDouble(r.recall.get, 2.0 / 4.0, eps)
    assertEqualsDouble(r.precision.get, 1.0, eps)
  }

  test(
    "probabilistic: a conflict is not accepted, and gold-unsure is not recall"
  ) {
    val r = row("probabilistic")
    assertEquals((r.same, r.sameAccepted, r.sameConflict), (3L, 2L, 1L))
    assertEquals((r.different, r.differentAccepted, r.unsure), (1L, 1L, 1L))
    assertEquals(r.pairs, 5L)
    assertEqualsDouble(r.recall.get, 2.0 / 3.0, eps)
    assertEqualsDouble(r.precision.get, 2.0 / 3.0, eps)
  }

  test(
    "a path that decided no pair has recall and precision undefined, not zero"
  ) {
    val r = row("llm-adjudicated")
    assertEquals(r.pairs, 0L)
    assertEquals(r.recall, None)
    assertEquals(r.precision, None)
    assertEquals(r.recallInterval, None)
  }

  test(
    "the path contributions sum to the pooled recall; the rest is no decision"
  ) {
    val sum = perPath.rows.flatMap(_.contribution).sum
    val t = Tally.of(items)
    // Pooled recall counts the no-decision gold pair in the denominator, so the
    // contributions add up to it exactly: 3/12 + 2/12 + 2/12 = 7/12.
    assertEqualsDouble(sum, t.recall.get, eps)
    assertEquals(
      perPath.rows.map(_.same).sum + perPath.sameWithoutDecision,
      perPath.totalSame
    )
  }

  test("an unknown decision path is listed after the four, sorted") {
    val extra = items :+ Scored(
      "PX",
      "borderline",
      GoldVerdict.Same,
      Some(Decision("PX", "k", Outcome.Accepted, "zz-new-path", None))
    )
    val rows = PathRecall.compute(extra).rows.map(_.path)
    assertEquals(rows.last, "zz-new-path")
    assertEquals(rows.size, 5)
  }

  // ---- the stratified bootstrap -------------------------------------------------------------

  private def undefinedSafe(a: Vector[Double], b: Vector[Double]): Boolean =
    a.size == b.size &&
      a.zip(b).forall((x, y) => (x.isNaN && y.isNaN) || x == y)

  /** The documented algorithm replayed here: strata in ascending name order,
    * `Random(seed)`, per resample and stratum n draws of `nextInt(n)`, the
    * statistic on the concatenated resample.
    */
  private def replay(
      its: Vector[Scored],
      resamples: Int,
      seed: Long
  ): Vector[Vector[Double]] =
    val sorted = its.sortBy(_.pairId)
    val strata = sorted.groupBy(_.stratum).toVector.sortBy(_._1).map(_._2)
    val rng = new scala.util.Random(seed)
    Vector.tabulate(resamples) { _ =>
      val sample = strata.flatMap { g =>
        val picked = Vector.newBuilder[Scored]
        var j = 0
        while j < g.size do
          picked += g(rng.nextInt(g.size))
          j += 1
        picked.result()
      }
      val tp =
        sample.count(i => i.gold == GoldVerdict.Same && i.accepted).toDouble
      val fp = sample
        .count(i => i.gold == GoldVerdict.Different && i.accepted)
        .toDouble
      val same = sample.count(_.gold == GoldVerdict.Same).toDouble
      val p = if tp + fp > 0 then tp / (tp + fp) else Double.NaN
      val r = if same > 0 then tp / same else Double.NaN
      val f =
        if !p.isNaN && !r.isNaN && p + r > 0 then 2 * p * r / (p + r)
        else Double.NaN
      Vector(p, r, f)
    }

  test(
    "the pooled bootstrap draws equal an independent replay of the algorithm"
  ) {
    val got = ErBootstrap.pooled(items, 200, 16L, 0.95)
    val want = replay(items, 200, 16L)
    assertEquals(got.draws.size, 200)
    got.draws.zip(want).zipWithIndex.foreach { case ((a, b), i) =>
      assert(undefinedSafe(a, b), s"resample $i: $a vs $b")
    }
  }

  test("the same seed gives the same result; another seed gives another") {
    val a = ErBootstrap.pooled(items, 300, 5L, 0.95)
    val b = ErBootstrap.pooled(items, 300, 5L, 0.95)
    val c = ErBootstrap.pooled(items, 300, 6L, 0.95)
    assertEquals(a.draws.map(_.map(_.toString)), b.draws.map(_.map(_.toString)))
    assertEquals(a.intervals, b.intervals)
    assertNotEquals(
      a.draws.map(_.map(_.toString)),
      c.draws.map(_.map(_.toString))
    )
  }

  test("the input order does not change the result") {
    val a = ErBootstrap.pooled(items, 100, 9L, 0.95)
    val b = ErBootstrap.pooled(items.reverse, 100, 9L, 0.95)
    assertEquals(a.draws.map(_.map(_.toString)), b.draws.map(_.map(_.toString)))
  }

  test("every resample keeps the size of every stratum and stays inside it") {
    val strata = Map(
      "a" -> Vector.fill(5)("a"),
      "b" -> Vector.fill(3)("b"),
      "c" -> Vector("c")
    )
    val draws = StratifiedBootstrap.draws(strata, 500, 3L) { sample =>
      Vector("a", "b", "c").map(s => sample.count(_ == s).toDouble)
    }
    assertEquals(draws.size, 500)
    draws.foreach(d => assertEquals(d, Vector(5.0, 3.0, 1.0)))
  }

  test("a resample actually draws with replacement within a stratum") {
    val strata = Map("a" -> Vector(1, 2, 3, 4, 5, 6, 7, 8))
    val draws = StratifiedBootstrap.draws(strata, 100, 1L) { sample =>
      Vector(sample.distinct.size.toDouble)
    }
    assert(draws.exists(_.head < 8.0), "no resample repeated an element")
    assert(draws.forall(_.head >= 1.0))
  }

  test("the strata sizes of the result are those of the fixture") {
    val r = ErBootstrap.pooled(items, 20, 1L, 0.95)
    assertEquals(
      r.strataSizes,
      Map(
        "clear-match" -> 5,
        "borderline" -> 7,
        "clear-non-match" -> 5,
        "unknown" -> 1
      )
    )
  }

  test("an empty stratum is skipped and a resample count under 1 is refused") {
    val draws = StratifiedBootstrap.draws(
      Map("a" -> Vector(1, 2), "empty" -> Vector.empty[Int]),
      10,
      1L
    )(s => Vector(s.size.toDouble))
    assert(draws.forall(_ == Vector(2.0)))
    intercept[IllegalArgumentException](
      StratifiedBootstrap.draws(Map("a" -> Vector(1)), 0, 1L)(s => Vector(0.0))
    )
  }

  test(
    "the percentile rule: index floor(alpha/2 m) and ceil((1-alpha/2) m) - 1"
  ) {
    val values = (1 to 100).map(_.toDouble)
    val i = StratifiedBootstrap.interval(50.0, values, 0.95)
    assertEquals((i.low, i.high, i.used, i.undefined), (3.0, 98.0, 100, 0))
    val j = StratifiedBootstrap.interval(1.0, (1 to 1000).map(_.toDouble), 0.95)
    assertEquals((j.low, j.high), (26.0, 975.0))
  }

  test("undefined resamples are dropped and counted, never read as zero") {
    val i = StratifiedBootstrap.interval(
      0.5,
      Vector(Double.NaN, 0.2, Double.NaN, 0.4, 0.6),
      0.95
    )
    assertEquals((i.used, i.undefined), (3, 2))
    assertEquals((i.low, i.high), (0.2, 0.6))
    val none = StratifiedBootstrap.interval(0.5, Vector(Double.NaN), 0.95)
    assert(none.low.isNaN && none.high.isNaN)
    assertEquals((none.used, none.undefined), (0, 1))
  }

  test("a level outside (0, 1) is refused") {
    intercept[IllegalArgumentException](
      StratifiedBootstrap.interval(0.5, Vector(1.0), 1.0)
    )
    intercept[IllegalArgumentException](
      StratifiedBootstrap.interval(0.5, Vector(1.0), 0.0)
    )
  }

  test("the interval of a pooled statistic is ordered and carries the point") {
    val r = ErBootstrap.pooled(items, 400, 2L, 0.95)
    assertEquals(r.intervals.size, 3)
    assertEqualsDouble(r.intervals(0).point, 7.0 / 9.0, eps)
    assertEqualsDouble(r.intervals(1).point, 7.0 / 12.0, eps)
    r.intervals.foreach { i =>
      assert(i.low <= i.high, i.toString)
      assertEquals(i.used + i.undefined, 400)
    }
  }

  // ---- the whole tool on files ----------------------------------------------------------------

  private def write(path: Path, text: String): Path =
    Files.createDirectories(path.getParent)
    Files.writeString(path, text, UTF_8)
    path

  private def inputs(dir: Path, manifest: String): Vector[String] =
    val d = write(
      dir.resolve("in/decisions.jsonl"),
      decisionLines.mkString("\n") + "\n"
    )
    val g = write(dir.resolve("in/gold.jsonl"), goldLines.mkString("\n") + "\n")
    val s =
      write(dir.resolve("in/strata.jsonl"), strataLines.mkString("\n") + "\n")
    val m = write(dir.resolve("in/GOLD-MANIFEST.json"), manifest)
    Files.createDirectories(dir.resolve("in/export"))
    Vector(
      "--decisions",
      d.toString,
      "--gold",
      g.toString,
      "--strata-key",
      s.toString,
      "--gold-manifest",
      m.toString,
      "--export",
      dir.resolve("in/export").toString
    )

  private val claimOnly =
    """{"note": "12 clear-non-match pairs labeled same, not named", "files": []}"""

  private def run(
      dir: Path,
      manifest: String,
      out: String,
      seed: Long,
      runId: String = "er-1"
  ): ujson.Value =
    val args = inputs(dir, manifest) ++ Vector(
      "--out",
      dir.resolve(out).toString,
      "--seed",
      seed.toString,
      "--resamples",
      "60",
      "--run-id",
      runId
    )
    val code = ErExtras.cli(args)
    assert(Set(0, ErExtras.IncompleteExit).contains(code), s"exit $code")
    ujson.read(readAll(dir.resolve(s"$out/$runId/er-extras-report.json")))

  test(
    "the tool reports the pooled counts and the per-path table from the files"
  ) {
    val dir = tempDir("p16w2-er-")
    val report = run(dir, claimOnly, "out", 5L)
    val pooled = report("pooled")
    assertEquals(pooled("truePositive").num.toInt, 7)
    assertEquals(pooled("falsePositive").num.toInt, 2)
    assertEquals(pooled("goldSame").num.toInt, 12)
    assertEqualsDouble(pooled("recall").num, 7.0 / 12.0, 1e-9)
    val paths =
      report("perPath")("paths").arr.map(p => p("path").str -> p).toMap
    assertEqualsDouble(paths("exact-id")("recall").num, 0.75, 1e-9)
    assertEquals(paths("llm-adjudicated")("recall"), ujson.Null)
    assertEquals(report("perPath")("goldSameWithoutDecision").num.toInt, 1)
    assertEquals(report("join")("decisionsWithoutGold").num.toInt, 1)
    assertEquals(report("join")("goldWithoutStratum").num.toInt, 1)
  }

  test(
    "the same seed writes the same report and resamples, another seed differs"
  ) {
    val dir = tempDir("p16w2-er-")
    run(dir, claimOnly, "o1", 5L)
    run(dir, claimOnly, "o2", 5L)
    run(dir, claimOnly, "o3", 6L)
    def file(o: String, f: String) = readAll(dir.resolve(s"$o/er-1/$f"))
    assertEquals(
      file("o1", "er-extras-report.json"),
      file("o2", "er-extras-report.json")
    )
    assertEquals(
      file("o1", "bootstrap-resamples.csv"),
      file("o2", "bootstrap-resamples.csv")
    )
    assertNotEquals(
      file("o1", "bootstrap-resamples.csv"),
      file("o3", "bootstrap-resamples.csv")
    )
    assert(Files.exists(dir.resolve("o1/er-1/SHA256SUMS")))
  }

  test(
    "the tool keeps the stratum sizes of the gold set in its bootstrap record"
  ) {
    val dir = tempDir("p16w2-er-")
    val sizes = run(dir, claimOnly, "out", 5L)("pooledBootstrap")("strataSizes")
    assertEquals(sizes("clear-match").num.toInt, 5)
    assertEquals(sizes("borderline").num.toInt, 7)
    assertEquals(sizes("clear-non-match").num.toInt, 5)
    assertEquals(sizes("unknown").num.toInt, 1)
  }

  test(
    "a manifest that claims mislabelled pairs but names none is not identifiable"
  ) {
    val dir = tempDir("p16w2-er-")
    val s = run(dir, claimOnly, "out", 5L)("sensitivity")
    assertEquals(s("manifest")("status").str, "not-identifiable")
    assertEquals(s("manifest")("claimedMislabelledPairs").num.toInt, 12)
    val sets = s("suspectSets").arr.map(x => x("name").str -> x).toMap
    assert(
      sets.contains("proxy-gold-same-in-clear-non-match"),
      sets.keySet.toString
    )
    assertEquals(
      sets("proxy-gold-same-in-clear-non-match")("pairIds").arr
        .map(_.str)
        .toVector,
      Vector("P16", "P17")
    )
    assert(!sets.contains("manifest-listed"))
  }

  test("a manifest that names the pairs gives exclude and relabel scenarios") {
    val dir = tempDir("p16w2-er-")
    val named =
      """{"files": [], "suspectedMislabelledPairs": ["P16", "P17"]}"""
    val s = run(dir, named, "out", 5L)("sensitivity")
    assertEquals(s("manifest")("status").str, "identified")
    val set =
      s("suspectSets").arr.find(_("name").str == "manifest-listed").get
    val scen = set("scenarios").arr.map(x => x("name").str -> x("counts")).toMap
    val excluded = scen("exclude-suspects")
    assertEquals(excluded("n").num.toInt, 16)
    assertEquals(excluded("goldSame").num.toInt, 10)
    val relabelled = scen("relabel-suspects-different")
    assertEquals(relabelled("goldSame").num.toInt, 10)
    assertEquals(relabelled("goldDifferent").num.toInt, 7)
    // P16 was rejected and P17 accepted: P17 is now a false positive.
    assertEquals(relabelled("truePositive").num.toInt, 6)
    assertEquals(relabelled("falsePositive").num.toInt, 3)
  }

  test(
    "a pair id the manifest names but the gold set lacks is reported, not scored"
  ) {
    val dir = tempDir("p16w2-er-")
    val named =
      """{"files": [], "suspectedMislabelledPairs": ["P16", "NOPE"]}"""
    val set = run(dir, named, "out", 5L)("sensitivity")("suspectSets").arr
      .find(_("name").str == "manifest-listed")
      .get
    assertEquals(set("pairIds").arr.map(_.str).toVector, Vector("P16"))
    assertEquals(set("notInGold").arr.map(_.str).toVector, Vector("NOPE"))
  }

  test(
    "a repeated pair id in the gold file is refused before anything is written"
  ) {
    val dir = tempDir("p16w2-er-")
    val args = inputs(dir, claimOnly)
    write(
      dir.resolve("in/gold.jsonl"),
      (goldLines :+ goldLines.head).mkString("\n") + "\n"
    )
    val code = ErExtras.cli(
      args ++ Vector("--out", dir.resolve("out").toString, "--run-id", "dup")
    )
    assertEquals(code, 2)
    assert(!Files.exists(dir.resolve("out/dup")))
  }

  test("a missing required option is refused with exit 2 and nothing written") {
    assertEquals(ErExtras.cli(Vector("--seed", "1")), 2)
    assert(ErExtras.parseArgs(Vector("--bogus", "x")).isLeft)
  }
