package scholarchain.spark

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import scala.collection.mutable

/** The one place a table may cross to the driver. Every collect in the module
  * goes through `rows`, which refuses a table larger than the cap instead of
  * truncating it.
  */
object DriverGuard:
  final class RowCapExceeded(message: String) extends RuntimeException(message)

  def rows(frame: DataFrame, cap: Int, what: String): Vector[Row] =
    require(cap > 0, "The driver row cap must be positive")
    val fetched = frame.limit(cap + 1).collect().toVector
    if fetched.length > cap then
      throw new RowCapExceeded(s"Refusing to collect $what: over $cap rows")
    fetched

  def count(frame: DataFrame): Long = frame.count()

/** Spark SQL over named DataFrames. Each `{name}` placeholder is replaced by a
  * uniquely named temporary view, so the statement stays declarative and the
  * result is an ordinary distributed DataFrame.
  *
  * Every input is renamed to itself (`toDF`), which gives its columns new
  * attribute IDs. Frames derived from one another, or checkpointed from one
  * another, otherwise share IDs, and a join between two such frames inside one
  * statement fails to resolve a column.
  */
object Sql:
  private val counter = new AtomicLong(0L)

  def run(
      session: SparkSession,
      query: String,
      inputs: (String, DataFrame)*
  ): DataFrame =
    val id = counter.incrementAndGet()
    val views = inputs.map((name, frame) => (name, s"v${id}_$name", frame))
    views.foreach((_, view, frame) =>
      frame.toDF(frame.columns*).createOrReplaceTempView(view)
    )
    val text =
      views.foldLeft(query)((acc, v) => acc.replace(s"{${v._1}}", v._2))
    try session.sql(text)
    finally views.foreach((_, view, _) => session.catalog.dropTempView(view))

/** Counters and plans gathered while the pipeline runs. */
final class Telemetry:
  val checkpoints = new AtomicLong(0L)
  val plans = mutable.LinkedHashMap.empty[String, String]
  val facts = mutable.LinkedHashMap.empty[String, ujson.Value]

  def plan(label: String, frame: DataFrame): Unit =
    if !plans.contains(label) then plans(label) = Telemetry.explain(frame)

  def fact(name: String, value: ujson.Value): Unit = facts(name) = value

object Telemetry:
  def explain(frame: DataFrame): String =
    val buffer = new ByteArrayOutputStream()
    Console.withOut(buffer)(frame.explain("formatted"))
    buffer.toString("UTF-8")

/** Shared context of the iterative algorithms.
  *
  * A checkpoint materializes the frame as Parquet under `checkpointDirectory`
  * (one `ckpt-<n>` directory each) and reads it back, so the lineage of the
  * returned frame is one file scan however many passes came before. It is
  * eager: the write is an action. Spark's own `Dataset.checkpoint` is not used
  * because on Spark 4.0.5 it fails with `key not found` while rewriting the
  * constraints of a union whose children carry the same column names (seen on
  * the path walk-back), and a checkpoint that throws on a valid plan is no
  * checkpoint. The schema is passed to the read, so an empty frame, which Spark
  * writes without a data file, reads back empty instead of failing.
  */
final class Algo(
    val session: SparkSession,
    val telemetry: Telemetry,
    val maxIterations: Int,
    val seed: Long,
    val driverRowCap: Int,
    val checkpointDirectory: Path
):
  def checkpoint(frame: DataFrame): DataFrame =
    val number = telemetry.checkpoints.incrementAndGet()
    val target = checkpointDirectory.resolve(f"ckpt-$number%06d")
    val location = Inputs.fileUri(target)
    frame.write.mode("errorifexists").parquet(location)
    session.read.schema(frame.schema).parquet(location)

  def sql(query: String, inputs: (String, DataFrame)*): DataFrame =
    Sql.run(session, query, inputs*)

  def rows(frame: DataFrame, what: String): Vector[Row] =
    DriverGuard.rows(frame, driverRowCap, what)
