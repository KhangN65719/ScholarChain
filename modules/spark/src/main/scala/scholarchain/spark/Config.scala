package scholarchain.spark

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.HexFormat
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scholarchain.core.YearWindow

/** A year window with the label that every output row carries. */
final case class NamedWindow(label: String, years: YearWindow):
  def start: Int = years.startYear
  def end: Int = years.endYear

object Windows:
  val allLabel = "all"
  val allYears: NamedWindow = NamedWindow(allLabel, YearWindow(1, 9999))
  val defaultSpec: String =
    "1900-1999,2000-2004,2005-2009,2010-2014,2015-2019,2020-2029,all"

  def parse(spec: String): Either[String, Vector[NamedWindow]] =
    val parsed = spec.split(",", -1).toVector.map(part => parseOne(part.trim))
    val errors = parsed.collect { case Left(error) => error }
    val windows = parsed.collect { case Right(window) => window }
    if errors.nonEmpty then Left(errors.mkString("; "))
    else if windows.isEmpty then Left("At least one window is required")
    else if windows.map(_.label).distinct.size != windows.size then
      Left("Window labels must be distinct")
    else Right(windows)

  private def parseOne(text: String): Either[String, NamedWindow] =
    if text == allLabel then Right(allYears)
    else
      val years = text.split("-", -1).toVector.map(_.toIntOption)
      if years.length != 2 || years.exists(_.isEmpty) then
        Left(s"Window '$text' must be <startYear>-<endYear> or 'all'")
      else
        Try(YearWindow(years(0).get, years(1).get)).toEither.left
          .map(error => s"Window '$text': ${error.getMessage}")
          .map(window => NamedWindow(text, window))

  /** Windows in configured order without the all-years window. */
  def consecutivePairs(
      windows: Vector[NamedWindow]
  ): Vector[(NamedWindow, NamedWindow)] =
    val dated = windows.filterNot(_.label == allLabel)
    dated.zip(dated.drop(1))

final case class PipelineArgs(
    erRun: Path,
    hadoopRoot: Path,
    outRoot: Path,
    windows: Vector[NamedWindow],
    pathPairs: Option[Path],
    sampleSize: Int,
    master: String,
    partitions: Int,
    maxIntegrityViolations: Long,
    hopLimit: Int,
    autoPairTop: Int,
    seed: Long,
    maxIterations: Int,
    representativeWorkLimit: Int,
    driverRowCap: Int,
    paperSummaryLimit: Int,
    eventLogDir: Option[Path],
    overwrite: Boolean,
    explicitMaster: Option[String] = None
)

/** Which Spark master a job asks its session for. `spark-submit` sets
  * `spark.master` as a system property before `main` runs; a builder `.master`
  * call would override it, so a master is set only when the caller named one or
  * when nothing else supplies one.
  */
object SparkMaster:
  val localDefault = "local[2]"
  val property = "spark.master"

  /** The master to set on the builder, or None to leave the one that
    * `spark-submit` or `spark.master` already provides.
    */
  def resolve(
      explicit: Option[String],
      submitted: Option[String]
  ): Option[String] =
    explicit
      .filter(_.nonEmpty)
      .orElse(
        if submitted.exists(_.nonEmpty) then None else Some(localDefault)
      )

  def forBuilder(explicit: Option[String]): Option[String] =
    resolve(explicit, Option(System.getProperty(property)))

  def apply(
      builder: org.apache.spark.sql.SparkSession.Builder,
      explicit: Option[String]
  ): org.apache.spark.sql.SparkSession.Builder =
    forBuilder(explicit).fold(builder)(builder.master)

object PipelineArgs:
  val usage: String =
    "Usage: CollaborationPipeline --er-run <snapshot dir> " +
      "--hadoop-root <snapshot dir> --out-root <dir> [--windows <spec>] " +
      "[--path-pairs <file>] [--sample-size <n>] [--master <url>] " +
      "[--partitions <n>] [--max-integrity-violations <n>] " +
      "[--hop-limit <n>] [--auto-pair-top <n>] [--seed <n>] " +
      "[--max-iterations <n>] [--representative-works <n>] " +
      "[--driver-row-cap <n>] [--paper-summaries <n>] " +
      "[--event-log-dir <dir>] [--overwrite]"

  private val valued = Set(
    "--er-run",
    "--hadoop-root",
    "--out-root",
    "--windows",
    "--path-pairs",
    "--sample-size",
    "--master",
    "--partitions",
    "--max-integrity-violations",
    "--hop-limit",
    "--auto-pair-top",
    "--seed",
    "--max-iterations",
    "--representative-works",
    "--driver-row-cap",
    "--paper-summaries",
    "--event-log-dir"
  )
  private val flags = Set("--overwrite")

  private def options(
      args: Vector[String]
  ): Either[String, Map[String, String]] =
    var found = Map.empty[String, String]
    var error = Option.empty[String]
    var index = 0
    while error.isEmpty && index < args.length do
      val name = args(index)
      if found.contains(name) then error = Some(s"Duplicate option $name")
      else if flags.contains(name) then
        found = found.updated(name, "true")
        index += 1
      else if !valued.contains(name) then
        error = Some(s"Unknown option or stray argument $name")
      else if index + 1 >= args.length || args(index + 1).startsWith("--") then
        error = Some(s"Option $name needs a value")
      else
        found = found.updated(name, args(index + 1))
        index += 2
    error.toLeft(found)

  private def number(
      found: Map[String, String],
      name: String,
      default: Long,
      min: Long,
      max: Long
  ): Either[String, Long] =
    found.get(name) match
      case None       => Right(default)
      case Some(text) =>
        text.toLongOption
          .filter(n => n >= min && n <= max)
          .toRight(s"$name must be an integer from $min to $max, not '$text'")

  def parse(args: Vector[String]): Either[String, PipelineArgs] =
    for
      found <- options(args)
      erRun <- found.get("--er-run").toRight("--er-run is required")
      hadoop <- found.get("--hadoop-root").toRight("--hadoop-root is required")
      out <- found.get("--out-root").toRight("--out-root is required")
      windows <- Windows.parse(
        found.getOrElse("--windows", Windows.defaultSpec)
      )
      sample <- number(found, "--sample-size", 20, 1, 100000)
      parts <- number(found, "--partitions", 8, 1, 10000)
      ceiling <- number(
        found,
        "--max-integrity-violations",
        1000,
        0,
        Long.MaxValue
      )
      hops <- number(found, "--hop-limit", 6, 1, 64)
      top <- number(found, "--auto-pair-top", 4, 0, 50)
      seed <- number(found, "--seed", 42, 0, Long.MaxValue)
      iterations <- number(found, "--max-iterations", 100, 1, 100000)
      reps <- number(found, "--representative-works", 5, 1, 1000)
      cap <- number(found, "--driver-row-cap", 5000, 1, 10000000)
      papers <- number(found, "--paper-summaries", 20, 0, 100000)
      master = found.getOrElse("--master", SparkMaster.localDefault)
      _ <- Either.cond(master.nonEmpty, (), "--master must not be empty")
    yield PipelineArgs(
      Paths.get(erRun),
      Paths.get(hadoop),
      Paths.get(out),
      windows,
      found.get("--path-pairs").map(Paths.get(_)),
      sample.toInt,
      master,
      parts.toInt,
      ceiling,
      hops.toInt,
      top.toInt,
      seed,
      iterations.toInt,
      reps.toInt,
      cap.toInt,
      papers.toInt,
      found.get("--event-log-dir").map(Paths.get(_)),
      found.contains("--overwrite"),
      found.get("--master")
    )

/** Output may never land in the repository or in a build directory. */
object OutputPolicy:
  def repositoryRoot(start: Path): Option[Path] =
    Iterator
      .iterate(Option(start.toAbsolutePath.normalize))(
        _.flatMap(p => Option(p.getParent))
      )
      .takeWhile(_.isDefined)
      .flatten
      .find(p => Files.exists(p.resolve(".git")))

  /** The real path of `path`; when it does not exist yet, its nearest existing
    * ancestor is resolved and the missing names are appended.
    */
  def resolved(path: Path): Path =
    val absolute = path.toAbsolutePath.normalize
    val existing = Iterator
      .iterate(Option(absolute))(_.flatMap(p => Option(p.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(p => Files.exists(p))
    existing match
      case Some(base) =>
        base.toRealPath().resolve(base.relativize(absolute)).normalize
      case None => absolute

  def check(out: Path, repository: Option[Path]): Either[String, Path] =
    val absolute = out.toAbsolutePath.normalize
    val real = resolved(absolute)
    val inTarget = Vector(absolute, real).exists(
      _.iterator.asScala.exists(_.toString == "target")
    )
    val inRepo = repository.exists(root => real.startsWith(root.toRealPath()))
    if absolute.getParent == null then Left("Refusing the filesystem root")
    else if inTarget then
      Left(s"Refusing to write under a target directory: $absolute")
    else if inRepo then
      Left(s"Refusing to write inside the repository: $absolute")
    else Right(absolute)

object Digests:
  def sha256Hex(text: String): String =
    HexFormat
      .of()
      .formatHex(
        MessageDigest
          .getInstance("SHA-256")
          .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      )

  def sha256File(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = Files.newInputStream(path)
    try
      val buffer = new Array[Byte](1 << 16)
      var read = stream.read(buffer)
      while read >= 0 do
        digest.update(buffer, 0, read)
        read = stream.read(buffer)
    finally stream.close()
    HexFormat.of().formatHex(digest.digest())

  /** Data files of a flat directory: no hidden or underscore files. */
  def dataFiles(directory: Path): Vector[Path] =
    val stream = Files.list(directory)
    try
      stream.iterator.asScala.toVector
        .filter(p => Files.isRegularFile(p))
        .filterNot(p => p.getFileName.toString.matches("[._].*"))
        .sortBy(_.getFileName.toString)
    finally stream.close()

  /** One digest for a directory: names, sizes and file digests, in order. */
  def directory(directory: Path): String =
    val lines = dataFiles(directory).map { file =>
      s"${file.getFileName} ${Files.size(file)} ${sha256File(file)}"
    }
    sha256Hex(lines.mkString("\n"))
