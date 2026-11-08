package scholarchain.er.sampling

import java.nio.file.{Files, Path, Paths}
import scholarchain.core.{ContractSchema, WorkRecord}
import scholarchain.core.pairing.{BlockKind, BlockStrategy, MatchCandidate}
import scholarchain.er.matching.{
  EntityKind,
  RecordIndex,
  SideRecord,
  entityKind
}
import scholarchain.er.pairs.{
  AtomicFile,
  Cli,
  Hashing,
  InputFiles,
  Locations,
  PairItem,
  PairSide,
  PairsFile
}
import upickle.default.{ReadWriter, read as readJson, write as writeJson}
import upickle.implicits.key

/** The sampling plan. `total` is split into thirds over the three strata and
  * the remainder goes to exact-id, then hard-common-name.
  */
final case class SamplingPlan(
    seed: String,
    total: Int,
    includeAgreeingContext: Boolean
):
  require(seed.trim.nonEmpty, "A sampling seed is required")
  require(total > 0, "The sample size must be positive")

  def targets: Map[Stratum, Int] =
    Stratum.values.toVector.zipWithIndex.map { (stratum, index) =>
      stratum -> (total / 3 + (if index < total % 3 then 1 else 0))
    }.toMap

/** Private key: which stratum a pair was drawn from and why it was a candidate.
  * It is never read by the labeling tool.
  */
final case class StratumKey(
    @key("pair_id") pairId: String,
    stratum: String,
    kind: String,
    leftId: String,
    rightId: String,
    blockStrategy: String,
    blockKey: String
) derives ReadWriter

final case class StratumReport(
    stratum: String,
    target: Int,
    eligible: Int,
    selected: Int,
    shortfall: Int
) derives ReadWriter

final case class ExportManifest(
    exportVersion: String,
    seed: String,
    requestedTotal: Int,
    includeAgreeingContext: Boolean,
    candidatesInput: String,
    candidatesSha256: String,
    recordsInput: String,
    recordsSha256: String,
    candidateRunIds: Vector[String],
    candidatesRead: Int,
    distinctPairs: Int,
    excluded: Map[String, Int],
    strata: Vector[StratumReport],
    selectedByKind: Map[String, Int],
    totalSelected: Int,
    totalShortfall: Int,
    recordsIndexed: Int
) derives ReadWriter

final case class ExportInputs(
    candidatesInput: String,
    candidatesSha256: String,
    recordsInput: String,
    recordsSha256: String
)

final case class ExportResult(
    items: Vector[PairItem],
    keys: Vector[StratumKey],
    manifest: ExportManifest
)

final case class ExportPaths(pairs: Path, key: Path, manifest: Path)

/** Draws a blind, stratified, reproducible sample of candidate pairs.
  *
  * Pairs are chosen and ordered by a salted hash of the pair ID, within a
  * stratum for the draw and across all strata for the final order, so the order
  * does not reveal the stratum. A stratum with fewer eligible pairs than its
  * target gives all it has and the shortfall is reported; it is never topped up
  * from another stratum.
  */
object PairsExport:
  /** What the sample needs from one distinct candidate pair. The 1.5 KB of
    * features a real candidate carries is dropped as each line is read, because
    * the real candidate output is hundreds of megabytes and strata never read a
    * feature.
    */
  private final case class Slim(
      kind: BlockKind,
      left: SideRecord,
      right: SideRecord,
      blockKey: String,
      strategy: BlockStrategy,
      runId: String
  )

  private case class Classified(
      slim: Slim,
      pairId: String,
      stratum: Stratum
  )

  /** Folds a candidate stream into its distinct pairs, in memory proportional
    * to the distinct pairs. A pair that several blocks propose is kept once,
    * with the smallest block key.
    */
  private final class Distinct(index: RecordIndex):
    private type Key = (BlockKind, String, String)
    private val found = scala.collection.mutable.HashMap.empty[Key, Slim]
    private val missing = scala.collection.mutable.HashSet.empty[Key]
    private val runIds = scala.collection.mutable.HashMap.empty[String, String]
    var read = 0

    def add(candidate: MatchCandidate): Unit =
      read += 1
      val runId = runIds.getOrElseUpdate(
        candidate.provenance.runId,
        candidate.provenance.runId
      )
      val kind = candidate.kind.entityKind
      (index.get(candidate.leftId), index.get(candidate.rightId)) match
        case (Some(left), Some(right))
            if left.kind == kind && right.kind == kind =>
          val key = (candidate.kind, left.id, right.id)
          val keep = found.get(key).forall(_.blockKey > candidate.blockKey)
          if keep then
            found.update(
              key,
              Slim(
                candidate.kind,
                left,
                right,
                candidate.blockKey,
                candidate.blockStrategy,
                runId
              )
            )
        case _ =>
          missing += ((candidate.kind, candidate.leftId, candidate.rightId))

    def pairs: Vector[Slim] = found.values.toVector
    def missingCount: Int = missing.size
    def distinctCount: Int = found.size + missing.size
    def runIdsSeen: Vector[String] = runIds.keys.toVector.sorted

  def run(
      candidates: Vector[MatchCandidate],
      index: RecordIndex,
      plan: SamplingPlan,
      inputs: ExportInputs
  ): ExportResult = runAll(candidates.iterator, index, plan, inputs)

  /** The sample from a candidate stream, which is consumed once. */
  def runAll(
      candidates: Iterator[MatchCandidate],
      index: RecordIndex,
      plan: SamplingPlan,
      inputs: ExportInputs
  ): ExportResult =
    val folded = new Distinct(index)
    candidates.foreach(folded.add)
    val classified = folded.pairs.map { slim =>
      val kind = slim.kind.entityKind
      Stratifier
        .classify(
          kind,
          slim.left,
          slim.right,
          slim.strategy,
          plan.includeAgreeingContext
        )
        .map(stratum =>
          Classified(
            slim,
            PairItem.idOf(kind, slim.left.id, slim.right.id),
            stratum
          )
        )
    }
    val eligible = classified.collect { case Right(found) => found }
    val excluded =
      classified
        .collect { case Left(reason) => reason.wire }
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap ++
        Option
          .when(folded.missingCount > 0)(
            Exclusion.MissingRecord.wire -> folded.missingCount
          )
    val targets = plan.targets
    val chosen = Stratum.values.toVector.flatMap { stratum =>
      eligible
        .filter(_.stratum == stratum)
        .map(c =>
          Hashing.sha256Hex(s"${plan.seed}|${stratum.wire}|${c.pairId}") -> c
        )
        .sortBy(_._1)
        .take(targets(stratum))
        .map(_._2)
    }
    val ordered =
      chosen.sortBy(c => Hashing.sha256Hex(s"${plan.seed}|order|${c.pairId}"))
    val items = ordered.map { c =>
      PairItem(
        ContractSchema.currentVersion,
        c.pairId,
        c.slim.kind.entityKind,
        c.slim.runId,
        PairItem.exportVersion,
        PairSide.of(c.slim.left),
        PairSide.of(c.slim.right)
      )
    }
    val keys = ordered.map { c =>
      StratumKey(
        c.pairId,
        c.stratum.wire,
        c.slim.kind.wire,
        c.slim.left.id,
        c.slim.right.id,
        c.slim.strategy.wire,
        c.slim.blockKey
      )
    }
    val reports = Stratum.values.toVector.map { stratum =>
      val available = eligible.count(_.stratum == stratum)
      val selected = chosen.count(_.stratum == stratum)
      StratumReport(
        stratum.wire,
        targets(stratum),
        available,
        selected,
        targets(stratum) - selected
      )
    }
    val manifest = ExportManifest(
      PairItem.exportVersion,
      plan.seed,
      plan.total,
      plan.includeAgreeingContext,
      inputs.candidatesInput,
      inputs.candidatesSha256,
      inputs.recordsInput,
      inputs.recordsSha256,
      folded.runIdsSeen,
      folded.read,
      folded.distinctCount,
      excluded,
      reports,
      ordered.groupBy(_.slim.kind.wire).view.mapValues(_.size).toMap,
      items.size,
      reports.map(_.shortfall).sum,
      index.size
    )
    ExportResult(items, keys, manifest)

  /** Writes the pairs file under `directory` and the private key file and
    * manifest under `directory/private`.
    */
  def write(directory: Path, result: ExportResult): ExportPaths =
    val paths = ExportPaths(
      directory.resolve("pairs.jsonl"),
      directory.resolve("private").resolve("strata-key.jsonl"),
      directory.resolve("private").resolve("export-manifest.json")
    )
    PairsFile.write(paths.pairs, result.items)
    AtomicFile.writeLines(paths.key, result.keys.map(key => writeJson(key)))
    AtomicFile.writeLines(
      paths.manifest,
      Vector(writeJson(result.manifest, indent = 2))
    )
    paths

  /** Decodes the nonblank lines lazily. The line number counts every line read
    * so far, blank ones included, and the error names the part file.
    */
  private def decoded[A: upickle.default.Reader](
      lines: Iterator[(String, Path)],
      what: String
  ): Iterator[A] =
    lines.zipWithIndex
      .filter { case ((line, _), _) => line.trim.nonEmpty }
      .map { case ((line, file), number) =>
        try readJson[A](line)
        catch
          case error: Exception =>
            throw new IllegalArgumentException(
              s"Invalid $what on line ${number + 1}: ${error.getMessage} " +
                s"(in ${file.getFileName})",
              error
            )
      }

  /** Streams the candidates of a file or a directory of part files, one at a
    * time, so the real candidate output (hundreds of megabytes) is never held
    * whole.
    */
  def withCandidates[A](input: Path)(
      use: Iterator[MatchCandidate] => A
  ): A =
    InputFiles.withLines(input)(lines =>
      use(decoded[MatchCandidate](lines, "candidate"))
    )

  def readCandidates(input: Path): Vector[MatchCandidate] =
    withCandidates(input)(_.toVector)

  def readWorks(input: Path): Vector[WorkRecord] =
    InputFiles.withLines(input)(lines =>
      decoded[WorkRecord](lines, "work record").toVector
    )

  /** Reads the records and the candidates and draws the sample. `records` is
    * one file or directory; the real graph-export works output holds every work
    * and, inside each, its author mentions, so one directory resolves both
    * kinds of candidate endpoint.
    */
  def exportFiles(
      candidates: Path,
      records: Path,
      plan: SamplingPlan
  ): ExportResult =
    val index = RecordIndex.fromCore(readWorks(records))
    withCandidates(candidates)(stream =>
      runAll(
        stream,
        index,
        plan,
        ExportInputs(
          candidates.toString,
          InputFiles.sha256(candidates),
          records.toString,
          InputFiles.sha256(records)
        )
      )
    )

  private val usage =
    "Usage: PairsExport --candidates <file-or-dir> --records <file-or-dir> " +
      "--out <dir> [--seed <text>] [--total <n>] " +
      "[--include-agreeing-context true]"

  def main(args: Array[String]): Unit =
    val outcome = for
      options <- Cli.parse(args.toSeq)
      _ <- Cli
        .unknown(
          options,
          Set(
            "--candidates",
            "--records",
            "--out",
            "--seed",
            "--total",
            "--include-agreeing-context"
          )
        )
        .toLeft(())
        .left
        .map(name => s"Unknown option $name")
      candidates <- options.get("--candidates").toRight("--candidates missing")
      records <- options.get("--records").toRight("--records missing")
      out <- options.get("--out").toRight("--out missing")
      total <- options
        .getOrElse("--total", "230")
        .toIntOption
        .filter(_ > 0)
        .toRight("--total must be a positive integer")
      plan <- scala.util
        .Try(
          SamplingPlan(
            options.getOrElse("--seed", "scholarchain-er-pairs-v1"),
            total,
            options.get("--include-agreeing-context").contains("true")
          )
        )
        .toEither
        .left
        .map(_.getMessage)
    yield (Paths.get(candidates), Paths.get(records), Paths.get(out), plan)
    outcome match
      case Left(message) =>
        Console.err.println(s"$message\n$usage")
        sys.exit(2)
      case Right((candidatePath, recordPath, outPath, plan)) =>
        Locations.check(
          outPath,
          inputs = Vector(candidatePath, recordPath)
        ) match
          case Left(message) =>
            Console.err.println(s"Refusing to write there: $message")
            sys.exit(2)
          case Right(_) => ()
        val result = exportFiles(candidatePath, recordPath, plan)
        val paths = write(outPath, result)
        val manifest = result.manifest
        println(
          s"records indexed: ${manifest.recordsIndexed}; candidates read: " +
            s"${manifest.candidatesRead}; distinct pairs: ${manifest.distinctPairs}"
        )
        result.manifest.strata.foreach { report =>
          val note =
            if report.shortfall > 0 then
              s" SHORT by ${report.shortfall} (only ${report.eligible} eligible)"
            else ""
          println(
            s"stratum ${report.stratum}: selected ${report.selected} " +
              s"of target ${report.target}, eligible ${report.eligible}$note"
          )
        }
        println(s"excluded: ${result.manifest.excluded}")
        println(s"Pairs file: ${paths.pairs.toAbsolutePath}")
        println(s"Private strata key: ${paths.key.toAbsolutePath}")
        println(s"Private manifest: ${paths.manifest.toAbsolutePath}")
