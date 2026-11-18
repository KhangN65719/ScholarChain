package scholarchain.evaluation.er

import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scholarchain.evaluation.io.*

/** One input file as the report records it. */
final case class InputRecord(
    label: String,
    path: String,
    sha256: String,
    rows: Long,
    manifestCheck: String
)

final case class Analysis(
    seed: Long,
    resamples: Int,
    level: Double,
    inputs: Vector[InputRecord],
    joined: Joined,
    pooled: Tally,
    byStratum: Vector[(String, Tally)],
    perPath: PerPath,
    pooledBootstrap: ErBootstrap.Result,
    manifest: ManifestInfo,
    baseline: ScenarioResult,
    suspectSets: Vector[SuspectSet],
    population: Population
):
  def incomplete: Boolean = population.incomplete

  /** The pooled numbers against the recorded replay (`ErReference`). */
  def referenceCheck: ErReference.Check = ErReference.check(pooled)

  def warnings: Vector[String] =
    val mismatched = inputs.filter(_.manifestCheck == "MISMATCH")
    val llm = perPath.rows.find(_.path == "llm-adjudicated")
    Vector(
      mismatched.map(i =>
        s"input ${i.label} differs from the sha256 in GOLD-MANIFEST.json"
      ),
      Vector(joined.goldWithoutDecision.size)
        .filter(_ > 0)
        .map(n => s"$n gold pairs have no decision (counted as not accepted)"),
      Vector(joined.decisionsWithoutGold.size)
        .filter(_ > 0)
        .map(n => s"$n decisions have no gold label (not scored)"),
      Vector(joined.goldWithoutStratum.size)
        .filter(_ > 0)
        .map(n => s"$n gold pairs have no stratum (stratum unknown)"),
      llm
        .filter(_.pairs == 0L)
        .toVector
        .map(_ =>
          "llm-adjudicated decided no pair: per-path recall for it is " +
            "undefined (the replay's adjudicator is the offline stub that " +
            "always abstains)"
        ),
      Vector(!population.nodes.present)
        .filter(identity)
        .map(_ => s"export table ${ErPopulation.NodeTable} is missing"),
      Vector(!population.edges.present)
        .filter(identity)
        .map(_ => s"export table ${ErPopulation.EdgeTable} is missing")
    ).flatten

object ErReport:
  private def num(d: Double): ujson.Value =
    if d.isNaN || d.isInfinite then ujson.Null else Js.d(d)

  private def opt(d: Option[Double]): ujson.Value =
    d.fold[ujson.Value](ujson.Null)(num)

  private def interval(i: StratifiedBootstrap.Interval): ujson.Value =
    Js.obj(
      Vector(
        "point" -> num(i.point),
        "low" -> num(i.low),
        "high" -> num(i.high),
        "resamplesUsed" -> Js.l(i.used.toLong),
        "resamplesUndefined" -> Js.l(i.undefined.toLong)
      )
    )

  private def tally(t: Tally): ujson.Value =
    Js.obj(
      Vector(
        "n" -> Js.l(t.n),
        "goldSame" -> Js.l(t.same),
        "goldDifferent" -> Js.l(t.different),
        "goldUnsure" -> Js.l(t.unsure),
        "truePositive" -> Js.l(t.truePositive),
        "falsePositive" -> Js.l(t.falsePositive),
        "precision" -> opt(t.precision),
        "recall" -> opt(t.recall),
        "f1" -> opt(t.f1)
      )
    )

  private def bootstrap(
      r: ErBootstrap.Result,
      a: Analysis
  ): ujson.Value =
    Js.obj(
      Vector(
        "method" -> Js.s(
          "percentile bootstrap, resampling pairs within strata, each " +
            "stratum keeps its size, seeded"
        ),
        "seed" -> Js.l(a.seed),
        "resamples" -> Js.l(a.resamples.toLong),
        "level" -> Js.d(a.level),
        "strataSizes" -> Js.counts(
          r.strataSizes.view.mapValues(_.toLong).toMap
        )
      ) ++ ErBootstrap.Names.zip(r.intervals).map((k, i) => k -> interval(i))
    )

  private def pathRow(p: PathRow): ujson.Value =
    Js.obj(
      Vector(
        "path" -> Js.s(p.path),
        "pairs" -> Js.l(p.pairs),
        "goldSame" -> Js.l(p.same),
        "goldSameAccepted" -> Js.l(p.sameAccepted),
        "goldSameRejected" -> Js.l(p.sameRejected),
        "goldSameUnresolved" -> Js.l(p.sameUnresolved),
        "goldSameConflict" -> Js.l(p.sameConflict),
        "goldDifferent" -> Js.l(p.different),
        "goldDifferentAccepted" -> Js.l(p.differentAccepted),
        "goldUnsure" -> Js.l(p.unsure),
        "recall" -> opt(p.recall),
        "recallWilson95" -> p.recallInterval.fold[ujson.Value](ujson.Null)(
          (lo, hi) => Js.obj(Vector("low" -> num(lo), "high" -> num(hi)))
        ),
        "precision" -> opt(p.precision),
        "contributionToPooledRecall" -> opt(p.contribution)
      )
    )

  private def scenario(s: ScenarioResult, a: Analysis): ujson.Value =
    Js.obj(
      Vector(
        "name" -> Js.s(s.name),
        "description" -> Js.s(s.description),
        "counts" -> tally(s.tally),
        "bootstrap" -> bootstrap(s.bootstrap, a)
      )
    )

  private def suspect(s: SuspectSet, a: Analysis): ujson.Value =
    Js.obj(
      Vector(
        "name" -> Js.s(s.name),
        "basis" -> Js.s(s.basis),
        "size" -> Js.l(s.pairIds.size.toLong),
        "pairIds" -> Js.arr(s.pairIds.map(Js.s)),
        "notInGold" -> Js.arr(s.notInGold.map(Js.s)),
        "scenarios" -> Js.arr(s.scenarios.map(scenario(_, a)))
      )
    )

  private def sensitivity(a: Analysis): ujson.Value =
    val m = a.manifest
    Js.obj(
      Vector(
        "manifest" -> Js.obj(
          Vector(
            "status" -> Js.s(
              if m.identifiesPairs then "identified" else "not-identifiable"
            ),
            "claimedMislabelledPairs" -> m.claimedMislabelled.fold[ujson.Value](
              ujson.Null
            )(n => Js.l(n.toLong)),
            "identifiedPairIds" -> Js.arr(m.identifiedPairIds.map(Js.s)),
            "note" -> Js.s(
              if m.identifiesPairs then
                "the manifest names the suspected pairs; see the manifest-listed set"
              else
                "GOLD-MANIFEST.json claims the count above but names no pair id, " +
                  "so the suspected pairs cannot be identified from it; a " +
                  "proxy set or a file given with --suspect-pairs is shown " +
                  "instead and is not an identification"
            )
          )
        ),
        "baseline" -> scenario(a.baseline, a),
        "suspectSets" -> Js.arr(a.suspectSets.map(suspect(_, a)))
      )
    )

  private def counts(c: EdgeCounts): ujson.Value =
    Js.obj(
      Vector(
        "rows" -> Js.l(c.rows),
        "endpointAUnresolved" -> Js.l(c.aUnresolved),
        "endpointAUnresolvedRate" -> Js.rate(c.aUnresolved, c.aKnown),
        "endpointBUnresolved" -> Js.l(c.bUnresolved),
        "endpointBUnresolvedRate" -> Js.rate(c.bUnresolved, c.bKnown),
        "eitherEndpointUnresolved" -> Js.l(c.eitherUnresolved),
        "eitherEndpointUnresolvedRate" -> Js.rate(
          c.eitherUnresolved,
          c.pairKnown
        ),
        "bothEndpointsUnresolved" -> Js.l(c.bothUnresolved),
        "bothEndpointsUnresolvedRate" -> Js.rate(c.bothUnresolved, c.pairKnown),
        "rowsWithBothFlags" -> Js.l(c.pairKnown)
      )
    )

  private def population(p: Population): ujson.Value =
    val n = p.nodes
    Js.obj(
      Vector(
        "scope" -> Js.s(
          "every row of the export (tier S), not a sample; linkStatus and " +
            "the unresolved flags are the export's, not gold-checked"
        ),
        "researcherNodes" -> Js.obj(
          Vector(
            "tablePresent" -> Js.b(n.present),
            "rows" -> Js.l(n.rows),
            "byNodeKind" -> Js.counts(n.byNodeKind),
            "byLinkStatus" -> Js.counts(n.byLinkStatus),
            "linkStatusShare" -> Js.obj(
              n.byLinkStatus.toVector
                .sortBy(_._1)
                .map((k, v) => k -> Js.rate(v, n.rows))
            ),
            "byNodeKindAndLinkStatus" -> Js.counts(n.byKindAndStatus)
          )
        ),
        "coauthorEdges" -> Js.obj(
          Vector(
            "tablePresent" -> Js.b(p.edges.present),
            "overall" -> counts(p.edges.overall),
            "byWindow" -> Js.obj(
              p.edges.byWindow.toVector
                .sortBy(_._1)
                .map((k, v) => k -> counts(v))
            )
          )
        )
      )
    )

  def toJson(a: Analysis, runId: String): ujson.Value =
    val j = a.joined
    Js.obj(
      Vector(
        "tool" -> Js.s(ErExtras.ToolVersion),
        "runId" -> Js.s(runId),
        "status" -> Js.s(if a.incomplete then "INCOMPLETE" else "COMPLETE"),
        "warnings" -> Js.arr(a.warnings.map(Js.s)),
        "inputs" -> Js.arr(
          a.inputs.map(i =>
            Js.obj(
              Vector(
                "label" -> Js.s(i.label),
                "path" -> Js.s(i.path),
                "sha256" -> Js.s(i.sha256),
                "rows" -> Js.l(i.rows),
                "goldManifestCheck" -> Js.s(i.manifestCheck)
              )
            )
          )
        ),
        "join" -> Js.obj(
          Vector(
            "goldPairs" -> Js.l(j.items.size.toLong),
            "goldWithoutDecision" -> Js.l(j.goldWithoutDecision.size.toLong),
            "decisionsWithoutGold" -> Js.l(j.decisionsWithoutGold.size.toLong),
            "goldWithoutStratum" -> Js.l(j.goldWithoutStratum.size.toLong),
            "strata" -> Js.obj(
              a.byStratum.map((k, t) => k -> tally(t))
            )
          )
        ),
        "pooled" -> tally(a.pooled),
        "referenceCheck" -> Js.obj(
          Vector(
            "status" -> Js.s(a.referenceCheck.status),
            "source" -> Js.s(ErReference.Source),
            "expected" -> Js.s(a.referenceCheck.expected),
            "actual" -> Js.s(a.referenceCheck.actual),
            "note" -> Js.s(
              "the pooled counts and rates must equal the recorded replay " +
                "when the inputs are those of the replay; DIFFERS means the " +
                "re-implementation or the inputs differ"
            )
          )
        ),
        "pooledBootstrap" -> bootstrap(a.pooledBootstrap, a),
        "perPath" -> Js.obj(
          Vector(
            "definition" -> Js.s(
              "recall = gold-same pairs ACCEPTED on the path / gold-same pairs " +
                "whose decision came from the path; contributionToPooledRecall " +
                "= accepted on the path / all gold-same pairs (sums to pooled " +
                "recall); a path with no gold-same pair has recall null"
            ),
            "goldSame" -> Js.l(a.perPath.totalSame),
            "goldSameWithoutDecision" -> Js.l(a.perPath.sameWithoutDecision),
            "paths" -> Js.arr(a.perPath.rows.map(pathRow))
          )
        ),
        "sensitivity" -> sensitivity(a),
        "tierSPopulation" -> population(a.population)
      )
    )

  private def f(d: Double): String =
    if d.isNaN then "n/a" else "%.3f".formatLocal(Locale.ROOT, d)

  private def fo(d: Option[Double]): String = d.fold("n/a")(f)

  private def ci(i: StratifiedBootstrap.Interval): String =
    s"${f(i.point)} [${f(i.low)}, ${f(i.high)}] " +
      s"(${i.used} resamples used, ${i.undefined} undefined)"

  private def pct(n: Long, d: Long): String =
    Ratio.of(n, d).fold("n/a")(r => "%.4f".formatLocal(Locale.ROOT, r))

  def toText(a: Analysis, runId: String): String =
    val m = a.manifest
    val head = Vector(
      s"ScholarChain ER extras ${ErExtras.ToolVersion}, run $runId",
      s"seed ${a.seed}, ${a.resamples} resamples, level ${f(a.level)}",
      s"status ${if a.incomplete then "INCOMPLETE" else "COMPLETE"}"
    ) ++ a.inputs.map(i =>
      s"input ${i.label}: ${i.sha256} (${i.rows} rows, gold manifest " +
        s"${i.manifestCheck})"
    ) ++ a.warnings.map("WARNING " + _)
    val pooled = Vector(
      "",
      "== pooled (gold-unsure excluded from precision and recall)",
      s"  pairs ${a.pooled.n}: same ${a.pooled.same}, different " +
        s"${a.pooled.different}, unsure ${a.pooled.unsure}",
      s"  TP ${a.pooled.truePositive}, FP ${a.pooled.falsePositive}",
      s"  precision ${fo(a.pooled.precision)}  recall ${fo(a.pooled.recall)}  " +
        s"F1 ${fo(a.pooled.f1)}",
      "  " + a.referenceCheck.line,
      "== stratified bootstrap 95% CI (resampled within strata)"
    ) ++ ErBootstrap.Names
      .zip(a.pooledBootstrap.intervals)
      .map((k, i) => s"  $k ${ci(i)}")
    val paths = Vector(
      "",
      "== per-path recall (gold-same accepted on the path / gold-same decided on the path)"
    ) ++ a.perPath.rows.map(p =>
      s"  ${p.path}: gold-same ${p.same}, accepted ${p.sameAccepted}, " +
        s"recall ${fo(p.recall)}, precision ${fo(p.precision)}, " +
        s"contribution to pooled recall ${fo(p.contribution)}"
    ) :+ s"  gold-same with no decision: ${a.perPath.sameWithoutDecision}"
    def scen(s: ScenarioResult): Vector[String] =
      Vector(
        s"  ${s.name} (n ${s.tally.n}, same ${s.tally.same}): ${s.description}"
      ) ++ ErBootstrap.Names
        .zip(s.bootstrap.intervals)
        .map((k, i) => s"    $k ${ci(i)}")
    val sens = Vector(
      "",
      "== sensitivity to suspected mislabelled pairs",
      s"  manifest claims ${m.claimedMislabelled.fold("no number")(_.toString)} " +
        s"pairs; it ${
            if m.identifiesPairs then "names them"
            else "names none: NOT IDENTIFIABLE from the manifest"
          }"
    ) ++ scen(a.baseline) ++ a.suspectSets.flatMap { s =>
      Vector(
        s"  set ${s.name} (${s.pairIds.size} pairs; ${s.basis})"
      ) ++ s.scenarios.flatMap(scen)
    }
    val pop = a.population
    val n = pop.nodes
    val popLines = Vector(
      "",
      "== tier S population view (every export row, not a sample)",
      s"  ${ErPopulation.NodeTable}: ${n.rows} rows"
    ) ++ n.byLinkStatus.toVector
      .sortBy(_._1)
      .map((k, v) => s"    linkStatus $k: $v (${pct(v, n.rows)})") ++ Vector(
      s"  ${ErPopulation.EdgeTable}: ${pop.edges.overall.rows} rows"
    ) ++
      (("overall" -> pop.edges.overall) +: pop.edges.byWindow.toVector
        .sortBy(_._1)).map { (k, c) =>
        s"    window $k: rows ${c.rows}, endpoint A unresolved " +
          s"${pct(c.aUnresolved, c.aKnown)}, B ${pct(c.bUnresolved, c.bKnown)}, " +
          s"either ${pct(c.eitherUnresolved, c.pairKnown)}"
      }
    (head ++ pooled ++ paths ++ sens ++ popLines).mkString("", "\n", "\n")

/** Offline ER evaluation extras over the frozen gold set and the tier S export.
  * Reads the files directly (the entity-resolution module is not a dependency
  * of this project). Never calls a network and never writes outside its run
  * directory.
  */
object ErExtras:
  val ToolVersion = "er-extras/1.0.0"
  val IncompleteExit = 3

  val Usage: String =
    "Usage: ErExtras --decisions F --gold F --strata-key F --gold-manifest F " +
      "--export DIR --out BASE_DIR [--seed N] [--resamples N] [--level X] " +
      "[--suspect-pairs F] [--run-id ID]"

  private val Required =
    Vector(
      "--decisions",
      "--gold",
      "--strata-key",
      "--gold-manifest",
      "--export",
      "--out"
    )
  private val Optional =
    Vector("--seed", "--resamples", "--level", "--suspect-pairs", "--run-id")

  final case class Args(
      decisions: Path,
      gold: Path,
      strata: Path,
      goldManifest: Path,
      exportDir: Path,
      outBase: Path,
      seed: Long,
      resamples: Int,
      level: Double,
      suspects: Option[Path],
      runId: String
  )

  def parseArgs(args: Vector[String]): Either[String, Args] =
    def loop(
        rest: Vector[String],
        acc: Map[String, String]
    ): Either[String, Map[String, String]] =
      rest match
        case Vector() => Right(acc)
        case flag +: value +: tail
            if (Required ++ Optional)
              .contains(flag) && !value.startsWith("--") =>
          if acc.contains(flag) then Left(s"$flag given twice")
          else loop(tail, acc.updated(flag, value))
        case flag +: _ if (Required ++ Optional).contains(flag) =>
          Left(s"$flag needs a value")
        case other => Left(s"Unknown or unexpected argument: ${other.head}")
    loop(args, Map.empty).flatMap { o =>
      def value[A](
          flag: String,
          default: A,
          parse: String => Option[A],
          problem: String
      ): Either[String, A] =
        o.get(flag) match
          case None    => Right(default)
          case Some(v) => parse(v).toRight(problem)
      for
        _ <- Required
          .find(!o.contains(_))
          .toLeft(())
          .left
          .map(_ + " is required")
        seed <- value("--seed", 16L, _.toLongOption, "--seed needs a number")
        resamples <- value(
          "--resamples",
          1000,
          _.toIntOption.filter(_ >= 1),
          "--resamples needs a number >= 1"
        )
        level <- value(
          "--level",
          0.95,
          _.toDoubleOption.filter(x => x > 0.0 && x < 1.0),
          "--level needs a number between 0 and 1"
        )
      yield Args(
        Paths.get(o("--decisions")),
        Paths.get(o("--gold")),
        Paths.get(o("--strata-key")),
        Paths.get(o("--gold-manifest")),
        Paths.get(o("--export")),
        Paths.get(o("--out")),
        seed,
        resamples,
        level,
        o.get("--suspect-pairs").map(Paths.get(_)),
        o.getOrElse("--run-id", s"erextras-${RunOutput.stamp()}")
      )
    }

  /** One pair id per line; blank lines and `#` comment lines are skipped. */
  def readSuspects(path: Path): Vector[String] =
    ErInputs
      .lines(path)
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .distinct

  /** Reads every input and computes every number; writes nothing. */
  def analyse(a: Args): Analysis =
    val decisionLines = ErInputs.lines(a.decisions)
    val goldLines = ErInputs.lines(a.gold)
    val strataLines = ErInputs.lines(a.strata)
    val decisions = ErInputs.decisions(a.decisions.toString, decisionLines)
    val gold = ErInputs.gold(a.gold.toString, goldLines)
    val strata = ErInputs.strata(a.strata.toString, strataLines)
    val manifest = ManifestInfo.parse(
      Files.readString(a.goldManifest, java.nio.charset.StandardCharsets.UTF_8)
    )
    val joined = ErInputs.join(gold, decisions, strata)
    val items = joined.items
    def record(label: String, path: Path, rows: Long) =
      val sha = Digests.sha256File(path)
      InputRecord(label, path.toString, sha, rows, manifest.check(path, sha))
    val inputs = Vector(
      record("decisions", a.decisions, decisions.size.toLong),
      record("gold", a.gold, gold.size.toLong),
      record("strata-key", a.strata, strata.size.toLong),
      InputRecord(
        "gold-manifest",
        a.goldManifest.toString,
        Digests.sha256File(a.goldManifest),
        manifest.files.size.toLong,
        "n/a"
      )
    )
    val byStratum =
      items
        .groupBy(_.stratum)
        .toVector
        .sortBy(_._1)
        .map((k, v) => k -> Tally.of(v))
    val declared = manifest.identifiedPairIds
    val fromFile = a.suspects.map(readSuspects)
    val sets = Vector(
      Option.when(declared.nonEmpty)(
        Sensitivity.suspectSet(
          "manifest-listed",
          "pair ids named by GOLD-MANIFEST.json",
          declared,
          items,
          a.resamples,
          a.seed,
          a.level
        )
      ),
      fromFile.map(ids =>
        Sensitivity.suspectSet(
          "suspect-pairs-file",
          s"pair ids listed in ${a.suspects.get.getFileName}",
          ids,
          items,
          a.resamples,
          a.seed,
          a.level
        )
      ),
      Some(
        Sensitivity.suspectSet(
          "proxy-gold-same-in-clear-non-match",
          "PROXY, not an identification: gold-same pairs of the " +
            s"${Sensitivity.ProxyStratum} stratum",
          Sensitivity.proxyPairIds(items),
          items,
          a.resamples,
          a.seed,
          a.level
        )
      )
    ).flatten
    Analysis(
      a.seed,
      a.resamples,
      a.level,
      inputs,
      joined,
      Tally.of(items),
      byStratum,
      PathRecall.compute(items),
      ErBootstrap.pooled(items, a.resamples, a.seed, a.level),
      manifest,
      Sensitivity.baseline(items, a.resamples, a.seed, a.level),
      sets,
      ErPopulation.read(a.exportDir)
    )

  private def drawsCsv(a: Analysis): String =
    def cell(d: Double): String =
      if d.isNaN then "" else java.lang.Double.toString(d)
    val header = "resample," + ErBootstrap.Names.mkString(",")
    val lines = a.pooledBootstrap.draws.zipWithIndex.map((row, i) =>
      s"${i + 1}," + row.map(cell).mkString(",")
    )
    (header +: lines).mkString("", "\n", "\n")

  def main(args: Array[String]): Unit =
    System.exit(cli(args.toVector))

  def cli(args: Vector[String]): Int =
    parseArgs(args) match
      case Left(error) =>
        println(Usage)
        println(s"ERROR: $error; nothing written")
        2
      case Right(a) =>
        val analysis =
          try Right(analyse(a))
          catch
            case e: Exception =>
              Left(Option(e.getMessage).getOrElse(e.toString))
        analysis match
          case Left(message) =>
            println(s"ERROR: $message; nothing written")
            2
          case Right(result) =>
            try write(a, result)
            catch
              case e: IllegalStateException =>
                println(s"ERROR: ${e.getMessage}")
                2

  private def write(a: Args, result: Analysis): Int =
    val dir = RunOutput.createRunDir(a.outBase, a.runId)
    RunOutput.write(
      dir,
      "er-extras-report.json",
      ujson.write(ErReport.toJson(result, a.runId), indent = 2) + "\n"
    )
    RunOutput.write(
      dir,
      "er-extras-report.txt",
      ErReport.toText(result, a.runId)
    )
    RunOutput.write(dir, "bootstrap-resamples.csv", drawsCsv(result))
    val exportManifest = a.exportDir.resolve("export-manifest.json")
    RunOutput.write(
      dir,
      "run-info.json",
      ujson.write(
        Js.obj(
          Vector(
            "tool" -> Js.s(ToolVersion),
            "runId" -> Js.s(a.runId),
            "exportDir" -> Js.s(a.exportDir.toString),
            "exportManifestSha256" -> Js.opt(
              Option.when(Files.isRegularFile(exportManifest))(
                Digests.sha256File(exportManifest)
              )
            ),
            "seed" -> Js.l(a.seed),
            "resamples" -> Js.l(a.resamples.toLong),
            "level" -> Js.d(a.level),
            "startedAt" -> Js.s(java.time.Instant.now().toString)
          )
        ),
        indent = 2
      ) + "\n"
    )
    RunOutput.seal(dir)
    println(result.referenceCheck.line)
    if result.incomplete then
      println(
        s"ERREXTRAS INCOMPLETE: ${result.warnings.mkString("; ")}; run directory $dir"
      )
      IncompleteExit
    else
      println(s"ERREXTRAS RESULT: run directory $dir")
      0
