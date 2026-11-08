package scholarchain.tests.analytics

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Path, Paths}
import org.apache.spark.sql.{Row, SparkSession}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scholarchain.spark.CollaborationPipeline

type Rec = Map[String, Any]

extension (record: Rec)
  def text(key: String): String = String.valueOf(record(key))
  def num(key: String): Double = record(key).asInstanceOf[Number].doubleValue
  def long(key: String): Long = record(key).asInstanceOf[Number].longValue
  def flag(key: String): Boolean = record(key).asInstanceOf[Boolean]
  def isNull(key: String): Boolean = record(key) == null
  def strings(key: String): Vector[String] =
    record(key).asInstanceOf[scala.collection.Seq[Any]].toVector.map(_.toString)

def where(rows: Vector[Rec], keys: (String, Any)*): Vector[Rec] =
  rows.filter(row => keys.forall((key, value) => row(key) == value))

def only(rows: Vector[Rec], keys: (String, Any)*): Rec =
  val found = where(rows, keys*)
  if found.size != 1 then
    throw new AssertionError(s"expected one row for $keys, found ${found.size}")
  found.head

final case class RunResult(code: Int, text: String):
  def lines: Vector[String] = text.linesIterator.toVector

  def value(prefix: String): String =
    lines.find(_.startsWith(prefix)).map(_.drop(prefix.length)).getOrElse("")

  def runDir: Path = Paths.get(value("output: "))

  def runId: String = value("run-id: ")

  lazy val out: Output = new Output(runDir)

final class Output(val dir: Path):
  private val cache = mutable.Map.empty[String, Vector[Rec]]

  private def toRec(row: Row): Rec =
    row.schema.fieldNames.toVector.map(n => n -> row.getAs[Any](n)).toMap

  def table(name: String): Vector[Rec] =
    cache.getOrElseUpdate(name, read(name))

  private def read(name: String): Vector[Rec] =
    val location = dir.resolve("tables").resolve(name).toUri.toString
    val frame = Harness.session.read.parquet(location)
    frame.collect().toVector.map(toRec)

  def json(relative: String): ujson.Value =
    ujson.read(Files.readString(dir.resolve(relative)))

  def files(relative: String): Vector[Path] =
    val stream = Files.walk(dir.resolve(relative))
    try stream.iterator.asScala.toVector.filter(Files.isRegularFile(_))
    finally stream.close()

object Harness:
  val root: Path = Files.createTempDirectory("sc-spark-it")

  private def deleteTree(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try
        val all = stream.iterator.asScala.toVector
        all.sortBy(_.toString)(Ordering[String].reverse).foreach { p =>
          Files.deleteIfExists(p)
        }
      finally stream.close()

  // One local session for every Spark suite of this JVM. The pipeline sets its
  // own SQL configuration on a provided session.
  lazy val session: SparkSession =
    val built = SparkSession
      .builder()
      .appName("scholarchain-independent-tests")
      .master("local[2]")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.sql.warehouse.dir", root.resolve("wh").toUri.toString)
      .config("spark.local.dir", root.resolve("local").toString)
      .config("spark.buffer.pageSize", "1m")
      .getOrCreate()
    built.conf.set("spark.sql.session.timeZone", "UTC")
    built.conf.set(
      "spark.sql.sources.partitionColumnTypeInference.enabled",
      "false"
    )
    sys.addShutdownHook {
      built.stop()
      deleteTree(root)
    }
    built

  def execute(args: Vector[String]): RunResult =
    val buffer = new ByteArrayOutputStream()
    val stream = new PrintStream(buffer, true, "UTF-8")
    val code = CollaborationPipeline.run(args, stream, Some(session))
    stream.flush()
    RunResult(code, buffer.toString("UTF-8"))

  // For failures that must be refused before a session is ever needed.
  def refuse(args: Vector[String]): RunResult =
    val buffer = new ByteArrayOutputStream()
    val stream = new PrintStream(buffer, true, "UTF-8")
    val code = CollaborationPipeline.run(args, stream, None)
    stream.flush()
    RunResult(code, buffer.toString("UTF-8"))

// The four pipeline runs shared by the Spark suites. Each is a lazy value, so
// the first suite that needs a run pays for it and the rest reuse it.
object Runs:
  private def dir(name: String): Path = Harness.root.resolve(name)

  def base(in: Fixtures.Inputs, out: Path): Vector[String] =
    Vector(
      "--er-run",
      in.erRun.toString,
      "--hadoop-root",
      in.hadoopRoot.toString,
      "--out-root",
      out.toString
    )

  lazy val inputsA: Fixtures.Inputs = Fixtures.write(Worlds.worldA, dir("a"))

  lazy val pairsFileA: Path =
    val lines = Worlds.pathPairsA.map((w, from, to) => s"$w,$from,$to")
    val file = dir("a").resolve("pairs.csv")
    Files.writeString(file, lines.mkString("\n") + "\n")
    file

  def argsA(out: Path, partitions: Int, sample: Int): Vector[String] =
    base(inputsA, out) ++ Vector(
      "--windows",
      Worlds.windowsA,
      "--path-pairs",
      pairsFileA.toString,
      "--hop-limit",
      "3",
      "--partitions",
      partitions.toString,
      "--sample-size",
      sample.toString
    )

  def outA: Path = dir("a-out")

  lazy val a: RunResult = Harness.execute(argsA(outA, 8, 20))

  // The same inputs, but 3 shuffle partitions and a sample of 3 sources.
  lazy val a3: RunResult = Harness.execute(argsA(dir("a3-out"), 3, 3))

  lazy val inputsC1: Fixtures.Inputs =
    Fixtures.write(Worlds.worldC1, dir("c1"))

  def argsC1(out: Path): Vector[String] =
    base(inputsC1, out) ++ Vector("--windows", Worlds.windowsC1)

  lazy val c1: RunResult = Harness.execute(argsC1(dir("c1-out")))

  // The component pass cap of 1 cannot cover a path of 3 edges.
  lazy val c1Capped: RunResult =
    Harness.execute(
      argsC1(dir("c1-cap-out")) ++ Vector("--max-iterations", "1")
    )

  lazy val inputsC2: Fixtures.Inputs =
    Fixtures.write(Worlds.worldC2, dir("c2"))

  // Every component is a pair, which two component passes can settle, so a cap
  // of 2 leaves the run valid while it may stop the community passes early.
  private def argsC2(in: Fixtures.Inputs, out: Path): Vector[String] =
    base(in, out) ++
      Vector("--windows", Worlds.windowsC2, "--max-iterations", "2")

  lazy val c2: RunResult = Harness.execute(argsC2(inputsC2, dir("c2-out")))

  lazy val inputsC2b: Fixtures.Inputs =
    Fixtures.write(Worlds.worldC2b, dir("c2b"))

  // The same options as c2 over inputs with one changed year.
  lazy val c2b: RunResult = Harness.execute(argsC2(inputsC2b, dir("c2b-out")))
