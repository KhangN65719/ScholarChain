package scholarchain.er.eval.assess

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scholarchain.er.eval.GoldFile
import scholarchain.er.eval.KindReport
import scholarchain.er.eval.OutcomeRow
import scholarchain.er.eval.PathMetrics
import scholarchain.er.eval.SensitivityPoint
import scholarchain.er.eval.Verdict
import scholarchain.er.eval.labeling.GoldLines
import scholarchain.er.eval.labeling.LabelNames
import scholarchain.er.matching.Policy
import scholarchain.er.matching.RecordIndex
import scholarchain.er.matching.RunInfo
import scholarchain.er.pairs.AtomicFile
import scholarchain.er.pairs.Cli
import scholarchain.er.pairs.Hashing
import scholarchain.er.pairs.InputFiles
import scholarchain.er.pairs.Locations
import scholarchain.er.pairs.PairsFile
import scholarchain.er.sampling.PairsExport

/** The plain-text form of a [[FullReport]]. */
object ReportText:
  private def num(v: Option[Double]): String =
    v.fold("n/a")(x => f"$x%.3f")

  private def span(i: Option[Interval]): String =
    i.fold("")(x => f" [${x.low}%.3f, ${x.high}%.3f]")

  private def cell(n: Int, width: Int): String =
    n.toString.reverse.padTo(width, ' ').reverse

  private val head =
    "             ACCEPTED  REJECTED  UNRESOLVED  CONFLICT  MISSING"

  private def row(name: String, r: OutcomeRow): String =
    val cols = Vector(r.accepted, r.rejected, r.unresolved, r.conflict)
    val body = cols.zip(Vector(10, 10, 12, 10)).map((n, w) => cell(n, w))
    "  " + name.padTo(10, ' ') + body.mkString + cell(r.missing, 9)

  private def threeWay(t: ThreeWay): Vector[String] =
    Vector(
      head,
      row("same", t.same),
      row("different", t.different),
      row("unsure", t.unsure)
    )

  private def ratesLines(r: Rates): Vector[String] =
    threeWay(r.table) ++ Vector(
      s"  precision ${num(r.precision)}${span(r.precisionInterval)}",
      s"  recall    ${num(r.recall)}${span(r.recallInterval)}",
      s"  F1        ${num(r.f1)}"
    )

  private def pathLine(p: PathMetrics): String =
    s"  path ${p.path}: accepted ${p.accepted} (TP ${p.truePositive}, " +
      s"FP ${p.falsePositive}) precision ${num(p.precision)}; rejected " +
      s"${p.rejected} (TN ${p.trueNegative}, FN ${p.falseNegative}) " +
      s"NPV ${num(p.negativePredictiveValue)}"

  private def kindLines(k: KindReport): Vector[String] =
    val m = k.metrics
    Vector(
      s"-- ${k.kind}: ${k.labeledPairs} scored, ${k.unsureExcluded} unsure " +
        "excluded (rows are gold, unsure not shown)",
      head,
      row("same", k.confusion.goldMatch),
      row("different", k.confusion.goldNonMatch),
      s"  precision ${num(m.precision)}  recall ${num(m.recall)}  " +
        s"F1 ${num(m.f1)}  decided-recall ${num(m.decidedRecall)}",
      s"  coverage ${num(m.coverage)}  unresolved-rate " +
        s"${num(m.unresolvedRate)}  conflict-rate ${num(m.conflictRate)}"
    ) ++ k.byPath.map(pathLine)

  private def errorLine(e: ErrorRow): String =
    val score = e.score.fold("-")(s => f"$s%.3f")
    s"  ${e.category} ${e.pairId} ${e.kind} ${e.stratum} gold=${e.gold} " +
      s"${e.outcome} ${e.path.getOrElse("-")} score=$score " +
      s"[${e.leftName} | ${e.rightName}] " +
      s"${e.leftSourceRecordId} ${e.rightSourceRecordId} " +
      e.reasonCodes.mkString(",")

  private def sweepLine(p: SensitivityPoint): String =
    val mark = if p.isPolicyThreshold then "  <- policy" else ""
    s"  accept ${p.accept} reject ${p.reject}: accepted ${p.accepted} " +
      s"precision ${num(p.precision)} recall ${num(p.recall)} " +
      s"F1 ${num(p.f1)} unresolved ${num(p.unresolvedRate)}$mark"

  private def sweepLines(r: FullReport, kind: String): Vector[String] =
    val points = r.base.sensitivity.filter(_.kind == kind)
    val byAccept = points.filter(_.reject == r.reject)
    val byReject = points.filter(_.accept == r.accept)
    Vector(s"-- sweep, $kind, reject fixed at ${r.reject}") ++
      byAccept.map(sweepLine) ++
      Vector(s"-- sweep, $kind, accept fixed at ${r.accept}") ++
      byReject.map(sweepLine)

  private def passTitle(p: PassResult, thirdPass: Boolean): String =
    p.pass match
      case 1 =>
        s"-- pass 1: ${p.rates.pairs} pairs CARRIED from the first labeling " +
          "pass (made with suggestions, NOT re-reviewed)"
      case 2 if thirdPass =>
        s"-- pass 2: ${p.rates.pairs} blind (relabeled BLIND, no " +
          "suggestion, no stratum)"
      case 2 =>
        s"-- pass 2: ${p.rates.pairs} pairs relabeled BLIND (no suggestion, " +
          "no stratum)"
      case 3 =>
        s"-- pass 3: ${p.rates.pairs} blind re-review of pass-1 pairs " +
          "(BLIND, no suggestion, no stratum)"
      case other => s"-- pass $other: ${p.rates.pairs} pairs"

  /** Present only for a multi-pass gold file, so a carried label is never shown
    * as a freshly reviewed one.
    */
  private def passLines(r: FullReport): Vector[String] =
    val thirdPass = r.passes.exists(_.pass == 3)
    if r.passes.isEmpty then Vector.empty
    else
      Vector(
        "",
        if thirdPass then
          "== by pass (every label in this file is blind: pass 2 relabeled " +
            "and pass 3 re-reviewed; none was made with a suggestion visible)"
        else
          "== by pass (the pooled rows above mix both passes; only pass 2 " +
            "was reviewed again, blind)"
      ) ++ r.passes.flatMap(p => passTitle(p, thirdPass) +: ratesLines(p.rates))

  def render(r: FullReport): String =
    val b = r.band
    val lines = Vector(
      "ScholarChain entity-resolution gold evaluation",
      s"policy ${r.policyVersion}: accept ${r.accept}, reject ${r.reject}, " +
        s"adjudication band [${b.low}, ${b.high})",
      "thresholds are the shipped provisional values, not tuned on this set"
    ) ++ r.inputs.toVector.sorted.map((k, v) => s"input $k: $v") ++
      Vector("", "WARNINGS") ++ r.warnings.map("  " + _) ++
      Vector(
        "",
        "== pooled over researcher and work (gold rows include unsure)"
      ) ++
      ratesLines(r.pooled) ++
      passLines(r) ++
      Vector("", "== separate by kind (unsure excluded)") ++
      r.base.kinds.flatMap(kindLines) ++
      Vector("", "== by stratum (private key, report only)") ++
      r.strata.flatMap { s =>
        s"-- ${s.stratum}: ${s.rates.pairs} pairs" +: ratesLines(s.rates)
      } ++
      Vector(
        "",
        "== adjudication band, never auto-labeled",
        s"  UNRESOLVED in band by gold: same ${b.same}, different " +
          s"${b.different}, unsure ${b.unsure}",
        s"  eligible for adjudication ${b.eligibleForAdjudication}, " +
          s"abstained by ${b.model}: ${b.abstainedByStub}, " +
          s"model calls ${b.modelCalls}"
      ) ++
      Vector("", "== errors by pair_id, false accepts first") ++
      r.errors.map(errorLine) ++
      Vector("", "== threshold sensitivity") ++
      r.base.kinds.flatMap(k => sweepLines(r, k.kind)) ++
      Vector("", "== uncertainty") ++ r.uncertainty.map("  " + _)
    lines.mkString("\n")

object EvaluationRun:
  private val Known =
    Set(
      "--pairs",
      "--gold",
      "--records",
      "--strata-key",
      "--out",
      "--compare-with",
      "--compare-decisions"
    )

  private val usage =
    "Usage: EvaluationRun --pairs <pairs.jsonl> --gold <gold.jsonl> " +
      "--records <works dir> --strata-key <strata-key.jsonl> --out <dir> " +
      "[--compare-with <earlier evaluation-report.json>] " +
      "[--compare-decisions <earlier decisions.jsonl>]"

  final case class Args(
      pairs: Path,
      gold: Path,
      records: Path,
      strata: Path,
      out: Path,
      compareWith: Option[Path] = None,
      compareDecisions: Option[Path] = None
  )

  def parseArgs(args: Seq[String]): Either[String, Args] =
    for
      o <- Cli.parse(args)
      _ <- Cli
        .unknown(o, Known)
        .toLeft(())
        .left
        .map(name => s"Unknown option $name")
      pairs <- o.get("--pairs").toRight("--pairs missing")
      gold <- o.get("--gold").toRight("--gold missing")
      records <- o.get("--records").toRight("--records missing")
      strata <- o.get("--strata-key").toRight("--strata-key missing")
      out <- o.get("--out").toRight("--out missing")
    yield Args(
      Paths.get(pairs),
      Paths.get(gold),
      Paths.get(records),
      Paths.get(strata),
      Paths.get(out),
      o.get("--compare-with").map(s => Paths.get(s)),
      o.get("--compare-decisions").map(s => Paths.get(s))
    )

  /** An earlier evaluation report, read back from its json. */
  def readPrevious(json: String): Either[String, FullReport] =
    try Right(upickle.default.read[FullReport](json))
    catch
      case e: Exception =>
        Left(s"not an evaluation report: ${e.getMessage}")

  private def linesOf(path: Path): Vector[String] =
    Files.readAllLines(path, UTF_8).asScala.toVector

  def readStrata(lines: Vector[String]): Map[String, String] =
    lines.filter(_.trim.nonEmpty).map(stratumOf).toMap

  private def stratumOf(line: String): (String, String) =
    val json = ujson.read(line)
    (json("pair_id").str, json("stratum").str)

  /** The pass of each pair in a two-pass gold file. Empty for a file with no
    * `pass` field, so a single-pass file reports exactly as before.
    */
  def passesOf(
      lines: Vector[String],
      allowed: Set[Int] = Set(1, 2)
  ): Map[String, Int] =
    lines
      .filter(_.trim.nonEmpty)
      .flatMap { line =>
        val json = ujson.read(line).obj
        for
          id <- json.value.get("pair_id").collect { case ujson.Str(s) => s }
          pass <- json.value.get("pass").collect {
            case ujson.Num(n) if allowed.contains(n.toInt) && n == n.toInt =>
              n.toInt
          }
        yield id -> pass
      }
      .toMap

  /** The pass-1 label stored on each pass-3 row, which is also the label the
    * pair had in the second-pass file. Rows of other passes give nothing.
    */
  def passOneLabels(lines: Vector[String]): Map[String, Verdict] =
    lines
      .filter(_.trim.nonEmpty)
      .flatMap { line =>
        val json = ujson.read(line).obj
        for
          id <- json.value.get("pair_id").collect { case ujson.Str(s) => s }
          _ <- json.value.get("pass").collect { case ujson.Num(3.0) => () }
          label <- json.value.get("pass1_label").collect { case ujson.Str(s) =>
            s
          }
          verdict <- LabelNames.parse(label)
        yield id -> verdict
      }
      .toMap

  /** How many labels differ from the suggestion the reviewer was shown. */
  def changedCount(lines: Vector[String]): Int =
    val entries = lines.filter(_.trim.nonEmpty).map(GoldLines.parse)
    entries.count(e => e.suggestion.exists(_.label != e.label.verdict))

  private def inputDigests(a: Args): Map[String, String] =
    Map(
      "pairs" -> Hashing.sha256File(a.pairs),
      "gold" -> Hashing.sha256File(a.gold),
      "strata-key" -> Hashing.sha256File(a.strata),
      "records (directory digest)" -> InputFiles.sha256(a.records)
    )

  def main(args: Array[String]): Unit =
    parseArgs(args.toSeq) match
      case Left(message) =>
        Console.err.println(s"$message\n$usage")
        sys.exit(2)
      case Right(a) =>
        Locations.check(a.out, inputs = Vector(a.records)) match
          case Left(message) =>
            Console.err.println(s"Refusing to write there: $message")
            sys.exit(2)
          case Right(_) => run(a)

  private def run(a: Args): Unit =
    val earlier = a.compareWith.map { path =>
      val bytes = Files.readAllBytes(path)
      readPrevious(new String(bytes, UTF_8)) match
        case Left(message) =>
          Console.err.println(s"--compare-with $path: $message")
          sys.exit(2)
        case Right(report) => (path, Hashing.sha256Bytes(bytes), report)
    }
    val earlierDecisions = a.compareDecisions.map { path =>
      val bytes = Files.readAllBytes(path)
      val text = new String(bytes, UTF_8)
      DecisionComparison.parse(text.linesIterator.toVector) match
        case Left(message) =>
          Console.err.println(s"--compare-decisions $path: $message")
          sys.exit(2)
        case Right(rows) => (path, Hashing.sha256Bytes(bytes), rows)
    }
    val manifestFile = Option(a.strata.toAbsolutePath.getParent)
      .map(_.resolve("export-manifest.json"))
      .filter(Files.isRegularFile(_))
    val exportNote = manifestFile.flatMap(file =>
      GoldEvaluation.exportNote(new String(Files.readAllBytes(file), UTF_8))
    )
    val digests = inputDigests(a)
    val pairs = PairsFile.read(a.pairs)
    val index = RecordIndex.fromCore(PairsExport.readWorks(a.records))
    val runId = PairDecisions.runIdFor(digests("pairs"))
    val info = RunInfo(runId, Instant.now())
    val policy = Policy.v1
    val decisions = PairDecisions.decide(pairs, index, policy, info)
    val decisionsFile = a.out.resolve("decisions.jsonl")
    AtomicFile.writeLines(
      decisionsFile,
      decisions.decided.map(PairDecisions.line)
    )
    println(s"decided ${decisions.decided.size} of ${pairs.size} pairs")
    // Label contents are parsed only here, after every decision exists on disk.
    // (inputDigests above only hashes the gold file's bytes.)
    val goldLines = linesOf(a.gold)
    val labels = GoldFile.parse(goldLines)
    val strata = readStrata(linesOf(a.strata))
    val passes = passesOf(goldLines, Set(1, 2, 3))
    val report = GoldEvaluation.evaluate(
      decisions,
      pairs,
      labels,
      strata,
      policy,
      digests,
      changedCount(goldLines),
      passes,
      exportNote
    )
    // Descriptive only: the comparison is appended to the text report after
    // every number above is final, and nothing reads it back.
    val comparison = earlier.toVector.flatMap { (path, sha, previous) =>
      val changes = ReportComparison.changes(
        decisions,
        labels,
        strata,
        passOneLabels(goldLines),
        passes
      )
      val again = passes.collect { case (id, 3) => id }.toVector.sorted
      ReportComparison.lines(
        path.getFileName.toString,
        sha,
        previous,
        report,
        again,
        changes
      )
    }
    val decisionComparison = earlierDecisions.toVector.flatMap {
      (path, sha, rows) =>
        DecisionComparison.lines(
          path.getFileName.toString,
          sha,
          rows,
          decisions.decided.map(DecisionComparison.rowOf),
          labels,
          strata
        )
    }
    val body = ReportText.render(report)
    val shown = Vector(
      body,
      comparison.mkString("\n"),
      decisionComparison.mkString("\n")
    ).filter(_.nonEmpty).mkString("\n\n")
    val text = a.out.resolve("evaluation-report.txt")
    val json = a.out.resolve("evaluation-report.json")
    AtomicFile.writeLines(text, Vector(shown))
    AtomicFile.writeLines(json, Vector(report.toJson))
    println(shown)
    println(s"Wrote $decisionsFile, $text and $json")
