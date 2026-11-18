package scholarchain.evaluation.benchmark

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import scala.util.Try
import scala.util.control.NonFatal
import scholarchain.evaluation.io.*

/** One human-written free-form question: its text, what the human meant it to
  * ask, and the facts the label is computed from. `intendedOperation` is the
  * human's word and goes to the slots file; `labelOperation` is the operation
  * of the label line (one the scorers know, see `EvidenceIds.Strata`).
  */
final case class FreeFormSpec(
    questionId: String,
    text: String,
    intendedOperation: String,
    labelOperation: String,
    subject: Option[String],
    windows: Vector[String],
    metric: Option[String] = None,
    limit: Option[Int] = None,
    abstainClass: Option[String] = None,
    absentName: Option[String] = None
):
  def slotJson(version: String): ujson.Value =
    Js.obj(
      Vector(
        "questionId" -> Js.s(questionId),
        "version" -> Js.s(version),
        "stratum" -> Js.s("free-form"),
        "text" -> Js.s(text),
        "intendedOperation" -> Js.s(intendedOperation),
        "subject" -> Js.opt(subject),
        "window" -> Js.opt(windows.headOption),
        "status" -> Js.s("filled"),
        "windows" -> Js.arr(windows.map(Js.s)),
        "metric" -> Js.opt(metric),
        "n" -> limit.fold[ujson.Value](ujson.Null)(n => Js.l(n.toLong)),
        "abstainClass" -> Js.opt(abstainClass)
      )
    )

/** `partial` is true only when some of the four files were already replaced. */
final case class FillRefusal(
    code: Int,
    reason: String,
    partial: Boolean = false
)

/** A failure between the four file moves: the files in `moved` are replaced and
  * the files in `notMoved` are not, so the directory is in a mixed state.
  */
final class PartialFillException(
    val moved: Vector[String],
    val notMoved: Vector[String],
    cause: Throwable
) extends RuntimeException(
      s"the move failed part-way: ${moved.mkString(", ")} replaced, " +
        s"${notMoved.mkString(", ")} NOT replaced; the benchmark directory " +
        s"is in a mixed state (${cause.getClass.getSimpleName}: " +
        s"${Option(cause.getMessage).getOrElse("no message")})",
      cause
    )

/** Fills the 8 free-form slots of a written benchmark version (decisions P16-23
  * and P16-26): labels the human's questions by the v1 rules from the export
  * tables alone, appends the question and label lines to the two benchmark
  * files, writes the filled slots file and records the change in the manifest.
  *
  * It writes only after every check has passed, and it writes once: it refuses
  * when a free-form line is already present, and unless the sha256 and row
  * count of the questions, labels and slots files match the manifest, so the 48
  * generated lines are known to be unchanged and are never rewritten (the new
  * files are the old bytes plus the appended lines).
  */
object BenchmarkFill:
  val ToolVersion = "benchmark-fill/1.0.0"
  val ChangeId = "free-form-fill"
  val ExitRefused = 2
  val ExitMismatch = 3

  val Usage: String =
    "Usage: BenchmarkFill <export dir> <benchmark dir> [--version vN] " +
      "[--vector-export DIR] [--chunkset-manifest FILE] [--generator-commit SHA] " +
      "[--generator-dirty yes|no] " +
      "(either --vector-export DIR or --no-vector-export yes is required, as " +
      "for BenchmarkGen, and it must match the mode the manifest records)"

  val KnownOptions: Set[String] = Set(
    "--version",
    "--vector-export",
    "--chunkset-manifest",
    "--no-vector-export",
    "--generator-commit",
    "--generator-dirty"
  )

  /** The eight questions of decision P16-23, verbatim. */
  val v1Specs: Vector[FreeFormSpec] = Vector(
    FreeFormSpec(
      "Q-FF-01",
      "Who was Osvaldo Simeone writing papers with in the late 2000s?",
      "coauthor-lookup",
      "coauthor-lookup",
      Some("Osvaldo Simeone"),
      Vector("2005-2009")
    ),
    FreeFormSpec(
      "Q-FF-02",
      "Who was in Yuanqing Xia's research community in the 2020s?",
      "community",
      "community-component",
      Some("Yuanqing Xia"),
      Vector("2020-2029")
    ),
    FreeFormSpec(
      "Q-FF-03",
      "Did Yuanqing Xia work with more different coauthors in the 2020s than in the late 2010s?",
      "temporal-comparison",
      "temporal-comparison",
      Some("Yuanqing Xia"),
      Vector("2015-2019", "2020-2029"),
      metric = Some("distinctCoauthorCount")
    ),
    FreeFormSpec(
      "Q-FF-04",
      "Can you give me a quick snapshot of Wentao Li's publication and coauthor activity in the 2020s?",
      "researcher-lookup",
      "researcher-lookup",
      Some("Wentao Li"),
      Vector("2020-2029")
    ),
    FreeFormSpec(
      "Q-FF-05",
      "Which four researchers worked with the most different people in the late 2010s?",
      "ranking",
      "ranking",
      None,
      Vector("2015-2019"),
      metric = Some("distinctCoauthorCount"),
      limit = Some(4)
    ),
    FreeFormSpec(
      "Q-FF-06",
      "Across all the years in the graph, which three researchers did the most to connect different research communities, based on bridge score?",
      "ranking",
      "ranking",
      None,
      Vector("all"),
      metric = Some("bridgeScore"),
      limit = Some(3)
    ),
    FreeFormSpec(
      "Q-FF-07",
      "Who were Elara Moonwhistle's coauthors in the late 2010s?",
      "unanswerable",
      "coauthor-lookup",
      None,
      Vector("2015-2019"),
      abstainClass = Some("unknown-researcher"),
      absentName = Some("Elara Moonwhistle")
    ),
    FreeFormSpec(
      "Q-FF-08",
      "Whose papers picked up the most citations in the first half of the 2010s? Give me the top five.",
      "unanswerable",
      "ranking",
      None,
      Vector("2010-2014"),
      abstainClass = Some("unrankable-metric")
    )
  )

  private def jsonl(rows: Vector[ujson.Value]): String =
    rows.map(r => ujson.write(r)).mkString("", "\n", "\n")

  /** Moves each prepared temporary file over its target, in order. When a move
    * fails, the temporary files still waiting are deleted. If no file had been
    * replaced the original exception is rethrown (nothing was written);
    * otherwise a `PartialFillException` names the replaced and the pending
    * files, so the message never says "nothing written" over a mixed state.
    */
  private[evaluation] def moveAll(
      benchDir: Path,
      temps: Vector[(String, Path)],
      move: (Path, Path) => Unit
  ): Unit =
    var moved = Vector.empty[String]
    try
      temps.foreach { (name, tmp) =>
        move(tmp, benchDir.resolve(name))
        moved = moved :+ name
      }
    catch
      case NonFatal(e) =>
        val pending = temps.filterNot(t => moved.contains(t._1))
        pending.foreach(t => Try(Files.deleteIfExists(t._2)))
        if moved.isEmpty then throw e
        else throw new PartialFillException(moved, pending.map(_._1), e)

  private def rows(text: String): Long = text.count(_ == '\n').toLong

  private def ensure(
      ok: Boolean,
      code: Int,
      why: => String
  ): Either[FillRefusal, Unit] =
    if ok then Right(()) else Left(FillRefusal(code, why))

  private def ids(text: String): Vector[String] =
    text.linesIterator.toVector
      .filter(_.trim.nonEmpty)
      .flatMap(l => Try(ujson.read(l)).toOption.flatMap(J.str(_, "questionId")))

  /** The fill. `Right` holds the printed check values; `Left` is a refusal, and
    * nothing has been written. Every file is first written beside its target
    * and moved into place only when all of them were written.
    */
  def fill(
      tables: BenchTables,
      exportManifestSha: Option[String],
      coverage: VectorCoverage,
      vectorDir: Option[Path],
      benchDir: Path,
      version: String,
      specs: Vector[FreeFormSpec],
      command: String,
      now: Instant,
      genState: Vector[(String, ujson.Value)]
  ): Either[FillRefusal, Vector[String]] =
    val qName = s"questions-$version.jsonl"
    val lName = s"labels-$version.jsonl"
    val sName = s"free-form-slots-$version.jsonl"
    val mName = s"BENCHMARK-MANIFEST-$version.json"
    val paths = Vector(qName, lName, sName, mName).map(benchDir.resolve)
    val Vector(qPath, lPath, sPath, mPath) = paths: @unchecked
    val failedCoverage = BenchmarkGen.failedChecks(coverage)
    for
      _ <- ensure(
        failedCoverage.isEmpty,
        ExitMismatch,
        s"vector coverage differs from the chunk set manifest (${failedCoverage.mkString(", ")})"
      )
      _ <- ensure(
        paths.forall(Files.isRegularFile(_)),
        ExitRefused,
        s"a benchmark file is missing in $benchDir: ${paths.filterNot(Files.isRegularFile(_)).map(_.getFileName).mkString(", ")}"
      )
      qOld = Files.readAllBytes(qPath)
      lOld = Files.readAllBytes(lPath)
      sOld = Files.readAllBytes(sPath)
      qText = new String(qOld, UTF_8)
      lText = new String(lOld, UTF_8)
      sText = new String(sOld, UTF_8)
      mText = Files.readString(mPath, UTF_8)
      manifest <- Try(ujson.read(mText).obj).toEither.left.map(e =>
        FillRefusal(ExitRefused, s"$mName is not valid JSON: ${e.getMessage}")
      )
      wanted = specs.map(_.questionId)
      present = (ids(qText) ++ ids(lText)).filter(wanted.contains).distinct
      _ <- ensure(
        present.isEmpty && !sText.contains("\"status\":\"filled\"") &&
          !J.field(ujson.Obj.from(manifest), "changes")
            .exists(_.arr.exists(c => J.str(c, "id").contains(ChangeId))),
        ExitRefused,
        s"free-form lines are already present (${present.mkString(", ")}); the fill writes once"
      )
      fileEntries = J
        .field(ujson.Obj.from(manifest), "files")
        .flatMap(_.arrOpt)
        .map(_.toVector)
        .getOrElse(Vector.empty)
      _ <- Vector(
        (qName, qText, qOld),
        (lName, lText, lOld),
        (sName, sText, sOld)
      ).foldLeft[Either[FillRefusal, Unit]](Right(())) {
        case (acc, (name, text, bytes)) =>
          acc.flatMap { _ =>
            fileEntries.find(e => J.str(e, "name").contains(name)) match
              case None =>
                Left(FillRefusal(ExitRefused, s"$mName has no entry for $name"))
              case Some(e) =>
                val sha = Digests.hex(
                  java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                )
                ensure(
                  J.str(e, "sha256").contains(sha) &&
                    J.long(e, "rows").contains(rows(text)),
                  ExitRefused,
                  s"$name does not match $mName: sha256 ${J.str(e, "sha256").getOrElse("?")} rows ${J.long(e, "rows").getOrElse(-1L)} recorded, sha256 $sha rows ${rows(text)} on disk"
                )
          }
      }
      _ <- ensure(
        qText.endsWith("\n") && lText.endsWith("\n"),
        ExitRefused,
        s"$qName or $lName does not end with a newline; the lines cannot be appended"
      )
      _ <- ensure(
        ids(sText) == wanted && sText.linesIterator
          .filter(_.trim.nonEmpty)
          .forall(l =>
            Try(ujson.read(l)).toOption.exists(v =>
              J.str(v, "status").contains("pending-human") &&
                J.field(v, "text").isEmpty
            )
          ),
        ExitRefused,
        s"$sName does not hold exactly the pending slots ${wanted.mkString(", ")}"
      )
      _ <- ensure(
        J.str(ujson.Obj.from(manifest), "sourceRunId")
          .contains(tables.sourceRunId),
        ExitRefused,
        s"the export's sourceRunId ${tables.sourceRunId} is not the one in $mName"
      )
      _ <- ensure(
        exportManifestSha.isDefined &&
          J.str(ujson.Obj.from(manifest), "exportManifestSha256") ==
          exportManifestSha,
        ExitRefused,
        s"the export manifest sha256 ${exportManifestSha.getOrElse("(no export-manifest.json)")} is not the one in $mName"
      )
      mode = if coverage.checks.nonEmpty then "checked" else "none-by-flag"
      recorded = J
        .field(ujson.Obj.from(manifest), "vectorCoverage")
        .getOrElse(ujson.Obj())
      _ <- ensure(
        J.str(recorded, "mode").contains(mode),
        ExitRefused,
        s"the vector coverage mode is $mode, but $mName records ${J.str(recorded, "mode").getOrElse("?")}"
      )
      _ <- ensure(
        mode != "checked" ||
          J.str(recorded, "vectorExportDir") ==
          vectorDir.map(_.toAbsolutePath.normalize.toString),
        ExitRefused,
        s"--vector-export is not the directory $mName records (${J.str(recorded, "vectorExportDir").getOrElse("?")})"
      )
      _ <- ensure(
        Try(ujson.write(ujson.read(mText), indent = 2) + "\n").toOption
          .contains(mText),
        ExitRefused,
        s"$mName does not round-trip through the JSON writer; updating it would rewrite unrelated bytes"
      )
      gen = new BenchmarkGen(tables, BenchConfig(version = version), coverage)
      labelled = specs.map(gen.freeForm)
      reasons = labelled.collect { case Left(why) => why }
      _ <- ensure(reasons.isEmpty, ExitRefused, reasons.mkString("; "))
      okItems = labelled.collect { case Right(x) => x }
      items = okItems.map(_._1)
      checks = okItems.flatMap(_._2)
    yield
      val qNew = qText + jsonl(items.map(_.question.toJson))
      val lNew = lText + jsonl(items.map(_.label.toJson))
      val sNew = jsonl(specs.map(_.slotJson(version)))
      val newFiles = Vector(qName -> qNew, lName -> lNew, sName -> sNew)
      val updated = ujson.read(mText)
      val previousFiles = ujson.read(ujson.write(updated("files")))
      val previousStrata = ujson.read(ujson.write(updated("countsByStratum")))
      updated("files").arr.foreach { e =>
        newFiles.find(_._1 == e("name").str).foreach { (_, text) =>
          e("sha256") = Js.s(Digests.sha256(text))
          e("rows") = Js.l(rows(text))
        }
      }
      updated("countsByStratum")("free-form") = Js.l(items.size.toLong)
      updated("freeFormQuestionsFilled") = Js.l(items.size.toLong)
      val entry = Js.obj(
        Vector(
          "id" -> Js.s(ChangeId),
          "at" -> Js.s(now.toString),
          "tool" -> Js.s(ToolVersion),
          "command" -> Js.s(command),
          "decisions" -> Js.arr(Vector(Js.s("P16-23"), Js.s("P16-26"))),
          "what" -> Js.s(
            s"filled the ${items.size} free-form slots: free-form-slots wrote the questions with status filled; ${items.size} lines appended to questions and to labels (the existing lines are unchanged bytes); files sha256 and rows, countsByStratum[free-form] and freeFormQuestionsFilled updated"
          ),
          "previousFiles" -> previousFiles,
          "previousCountsByStratum" -> previousStrata,
          "newFiles" -> Js.arr(newFiles.map { (name, text) =>
            Js.obj(
              Vector(
                "name" -> Js.s(name),
                "sha256" -> Js.s(Digests.sha256(text)),
                "rows" -> Js.l(rows(text))
              )
            )
          }),
          "labelOperations" -> Js.obj(
            items.map(i => i.question.questionId -> Js.s(i.label.operation))
          ),
          "checks" -> Js.arr(checks.map(Js.s)),
          "scala3LibraryVersion" -> Js.s(
            ScalaVersions.scala3Library.getOrElse("UNRECORDED")
          ),
          "note" -> Js.s(
            "the scalaVersion field above is the Scala 2.13 standard library version, not the Scala 3 version of the build; it is left as generated"
          )
        ) ++ genState
      )
      val previousChanges = J
        .field(updated, "changes")
        .flatMap(_.arrOpt)
        .map(_.toVector)
        .getOrElse(Vector.empty)
      updated("changes") = Js.arr(previousChanges :+ entry)
      val mNew = ujson.write(updated, indent = 2) + "\n"
      val outputs = newFiles :+ (mName -> mNew)
      val temps = outputs.map { (name, text) =>
        val tmp = Files.createTempFile(benchDir, s".$name.", ".fill")
        Files.write(tmp, text.getBytes(UTF_8))
        name -> tmp
      }
      moveAll(
        benchDir,
        temps,
        (from, to) =>
          Files.move(
            from,
            to,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
          )
      )
      checks ++ outputs.map { (name, text) =>
        s"FILL WROTE $name rows=${rows(text)} sha256=${Digests.sha256(text)}"
      }

  def main(args: Array[String]): Unit =
    System.exit(cli(args.toVector))

  def cli(args: Vector[String]): Int =
    def error(why: String): Int =
      println(s"BENCHMARK ERROR: $why; nothing written")
      ExitRefused
    args match
      case dir +: bench +: rest =>
        BenchmarkGen.parseOptions(rest) match
          case Left(why)   => error(why)
          case Right(opts) =>
            val unknown =
              opts.keys.toVector.sorted.find(k => !KnownOptions.contains(k))
            val exportDir = Paths.get(dir)
            val chunkset = Paths.get(
              opts.getOrElse(
                "--chunkset-manifest",
                "datasets/tier-s/chunks-c/chunkset-manifest.json"
              )
            )
            val vectorDir = opts.get("--vector-export").map(Paths.get(_))
            val inputError: Option[String] = unknown
              .map(k => s"unknown option $k")
              .orElse(BenchmarkGen.optionError(opts))
              .orElse(
                opts
                  .get("--version")
                  .filter(_ != "v1")
                  .map(v =>
                    s"--version $v is refused: the fill writes the eight v1 " +
                      "questions (P16-23) and labels only the v1 files"
                  )
              )
              .orElse(
                if !Files.isRegularFile(
                    exportDir.resolve("export-manifest.json")
                  )
                then Some(s"$exportDir has no export-manifest.json")
                else None
              )
              .orElse(
                vectorDir.flatMap { d =>
                  if !Files.isDirectory(d) then
                    Some(s"--vector-export $d is not a directory")
                  else if !Files.isRegularFile(chunkset) then
                    Some(s"chunk set manifest $chunkset is not a file")
                  else None
                }
              )
            inputError match
              case Some(why) => error(why)
              case None      =>
                Try {
                  val coverage = vectorDir.fold(VectorCoverage.noneByFlag)(d =>
                    VectorCoverage.fromExports(d, chunkset)
                  )
                  val tables = BenchTables.load(exportDir)
                  (coverage, tables)
                }.toEither match
                  case Left(e) =>
                    error(s"the inputs could not be read: ${e.getMessage}")
                  case Right((coverage, tables)) =>
                    val result = Try(
                      fill(
                        tables,
                        Some(
                          Digests.sha256File(
                            exportDir.resolve("export-manifest.json")
                          )
                        ),
                        coverage,
                        vectorDir,
                        Paths.get(bench),
                        opts.getOrElse("--version", "v1"),
                        v1Specs,
                        s"BenchmarkFill ${args.mkString(" ")}",
                        Instant.now(),
                        GeneratorState.info(opts)
                      )
                    ).toEither.left
                      .map {
                        case p: PartialFillException =>
                          FillRefusal(ExitRefused, p.getMessage, partial = true)
                        case e =>
                          FillRefusal(
                            ExitRefused,
                            s"the fill failed before writing completed: ${e.getMessage}"
                          )
                      }
                      .flatMap(identity)
                    result match
                      case Left(FillRefusal(code, why, _))
                          if code == ExitMismatch =>
                        BenchmarkGen
                          .coverageLines(coverage, None)
                          .foreach(println)
                        println(s"BENCHMARK REFUSED: $why; nothing written")
                        code
                      case Left(FillRefusal(code, why, true)) =>
                        println(
                          s"BENCHMARK ERROR: $why. Restore the four files from " +
                            "the backup (or `git checkout -- experiments/benchmark`) " +
                            "before any rerun"
                        )
                        code
                      case Left(FillRefusal(_, why, _)) => error(why)
                      case Right(lines)                 =>
                        lines.foreach(println)
                        println(
                          s"BENCHMARK FILL RESULT: ${v1Specs.size} free-form questions labelled and appended"
                        )
                        0
      case _ =>
        println(Usage)
        ExitRefused
