package scholarchain.spark

import java.nio.file.Files
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types.StructType
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

/** One local session shared by every suite of the module. */
object TestSpark:
  lazy val session: SparkSession =
    val dir = Files.createTempDirectory("scholarchain-spark-test-")
    val built = SparkSession
      .builder()
      .master("local[2]")
      .appName("scholarchain-spark-tests")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.default.parallelism", "2")
      .config("spark.sql.adaptive.enabled", "false")
      .config("spark.sql.autoBroadcastJoinThreshold", "-1")
      .config("spark.buffer.pageSize", "1m")
      .config("spark.sql.session.timeZone", "UTC")
      .config(
        "spark.sql.warehouse.dir",
        dir.resolve("warehouse").toUri.toString
      )
      .config("spark.local.dir", dir.resolve("local").toString)
      .getOrCreate()
    built.sparkContext.setLogLevel("ERROR")
    built

abstract class SparkSuite extends munit.FunSuite:
  override val munitTimeout: Duration = Duration(20, "min")

  override def afterAll(): Unit =
    session.catalog.clearCache()
    super.afterAll()

  def session: SparkSession = TestSpark.session

  def algo(maxIterations: Int = 100): Algo =
    val dir = Files.createTempDirectory("scholarchain-spark-checkpoint-")
    new Algo(session, new Telemetry(), maxIterations, 42L, 5000, dir)

  def frame(ddl: String)(rows: Row*): DataFrame =
    session.createDataFrame(rows.asJava, StructType.fromDDL(ddl))

  def rows(data: DataFrame): Vector[Row] =
    DriverGuard.rows(data, 10000, "test")

/** Hand-built window graphs: the node and edge frames the algorithms read. */
final case class Graph(nodes: DataFrame, adj: DataFrame, edges: DataFrame)

object Graph:
  /** Each entry is (window, nodes, edges as (a, b, weight)). */
  def build(
      session: SparkSession,
      windows: Vector[(String, Vector[String], Vector[(String, String, Long)])]
  ): Graph =
    val nodeRows = windows.flatMap((w, nodes, _) => nodes.map(n => Row(w, n)))
    val edgeRows = windows.flatMap { (w, _, edges) =>
      edges.map { (x, y, weight) =>
        val (a, b) = if x < y then (x, y) else (y, x)
        Row(
          w,
          s"edge-$a-$b",
          a,
          b,
          weight,
          Seq(s"work-$a$b"),
          2000,
          2000,
          2000,
          2004
        )
      }
    }
    val adjRows = windows.flatMap { (w, _, edges) =>
      edges.flatMap((a, b, weight) =>
        Vector(Row(w, a, b, weight), Row(w, b, a, weight))
      )
    }
    def make(ddl: String, data: Vector[Row]): DataFrame =
      session.createDataFrame(data.asJava, StructType.fromDDL(ddl))
    Graph(
      make("window STRING, nodeId STRING", nodeRows),
      make("window STRING, src STRING, dst STRING, weight BIGINT", adjRows),
      make(
        "window STRING, edgeId STRING, researcherA STRING, researcherB STRING, " +
          "jointPublicationCount BIGINT, representativeWorkIds ARRAY<STRING>, " +
          "firstCollaborationYear INT, lastCollaborationYear INT, " +
          "windowStart INT, windowEnd INT",
        edgeRows
      )
    )
