package scholarchain.spark

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import org.apache.spark.scheduler.{
  SparkListener,
  SparkListenerJobStart,
  SparkListenerStageCompleted
}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions as F
import scala.jdk.CollectionConverters.*

/** Observes jobs and stages: task counts and shuffle volumes per stage, and the
  * stage label each job was submitted under.
  */
final class StageMetricsListener extends SparkListener:
  private val stages = new ConcurrentLinkedQueue[ujson.Obj]()
  private val jobs = new ConcurrentLinkedQueue[ujson.Obj]()
  val events = new AtomicLong(0L)

  override def onJobStart(event: SparkListenerJobStart): Unit =
    val label = Option(event.properties)
      .flatMap(p => Option(p.getProperty("spark.job.description")))
      .getOrElse("unlabelled")
    jobs.add(
      ujson.Obj(
        "jobId" -> event.jobId,
        "label" -> label,
        "stageIds" -> ujson.Arr.from(event.stageIds.map(id => ujson.Num(id)))
      )
    )
    events.incrementAndGet()

  override def onStageCompleted(event: SparkListenerStageCompleted): Unit =
    val info = event.stageInfo
    val metrics = Option(info.taskMetrics)
    def read(f: org.apache.spark.executor.TaskMetrics => Long): Long =
      metrics.map(f).getOrElse(0L)
    stages.add(
      ujson.Obj(
        "stageId" -> info.stageId,
        "name" -> info.name.linesIterator.nextOption().getOrElse(""),
        "tasks" -> info.numTasks,
        "shuffleReadBytes" -> ujson.Num(
          read(_.shuffleReadMetrics.totalBytesRead).toDouble
        ),
        "shuffleWriteBytes" -> ujson.Num(
          read(_.shuffleWriteMetrics.bytesWritten).toDouble
        ),
        "inputBytes" -> ujson.Num(read(_.inputMetrics.bytesRead).toDouble),
        "inputRecords" -> ujson.Num(read(_.inputMetrics.recordsRead).toDouble)
      )
    )
    events.incrementAndGet()

  /** Waits until the asynchronous listener bus has delivered what it holds. */
  def settle(): Unit =
    var last = -1L
    var stable = 0
    var waited = 0
    while stable < 5 && waited < 100 do
      Thread.sleep(100)
      waited += 1
      val now = events.get()
      if now == last then stable += 1
      else
        stable = 0
        last = now

  def snapshot(): ujson.Obj =
    val stageRows = stages.asScala.toVector.sortBy(_("stageId").num)
    val jobRows = jobs.asScala.toVector.sortBy(_("jobId").num)
    val labelOf = jobRows.flatMap { job =>
      job("stageIds").arr.map(id => id.num.toInt -> job("label").str)
    }.toMap
    val byLabel = stageRows
      .groupBy(stage => labelOf.getOrElse(stage("stageId").num.toInt, "other"))
      .toVector
      .sortBy(_._1)
      .map { (label, group) =>
        label -> ujson.Obj(
          "stages" -> group.size,
          "tasks" -> ujson.Num(group.map(_("tasks").num).sum),
          "shuffleReadBytes" -> ujson
            .Num(group.map(_("shuffleReadBytes").num).sum),
          "shuffleWriteBytes" -> ujson.Num(
            group.map(_("shuffleWriteBytes").num).sum
          )
        )
      }
    ujson.Obj(
      "totals" -> ujson.Obj(
        "jobs" -> jobRows.size,
        "stages" -> stageRows.size,
        "tasks" -> ujson.Num(stageRows.map(_("tasks").num).sum),
        "shuffleReadBytes" -> ujson.Num(
          stageRows.map(_("shuffleReadBytes").num).sum
        ),
        "shuffleWriteBytes" -> ujson.Num(
          stageRows.map(_("shuffleWriteBytes").num).sum
        )
      ),
      "byStageLabel" -> ujson.Obj.from(byLabel),
      "jobs" -> ujson.Arr.from(jobRows),
      "stages" -> ujson.Arr.from(stageRows)
    )

object Stages:
  /** Runs `body` with every job it submits labelled `label`. */
  def run[A](session: SparkSession, label: String)(body: => A): A =
    session.sparkContext.setJobDescription(label)
    try body
    finally session.sparkContext.setJobDescription(null)

final case class FileInfo(path: String, bytes: Long, sha256: String)

final case class TableInfo(
    name: String,
    rows: Long,
    schema: Vector[(String, String)],
    windowed: Boolean,
    partitioned: Boolean,
    partitionsAtWrite: Int,
    files: Vector[FileInfo]
)

final case class TableSpec(
    name: String,
    frame: DataFrame,
    windowed: Boolean,
    sortKeys: Vector[String]
)

object Export:
  def write(
      session: SparkSession,
      telemetry: Telemetry,
      runDir: Path,
      spec: TableSpec
  ): TableInfo =
    Stages.run(session, s"export:${spec.name}") {
      telemetry.plan(s"table-${spec.name}", spec.frame)
      val cached = spec.frame.persist()
      val rows = cached.count()
      val partitions = cached.rdd.getNumPartitions
      val target = runDir.resolve("tables").resolve(spec.name)
      // A partitioned write of no rows leaves no file, so the table could not
      // be read back with its schema. An empty windowed table is written as one
      // schema-only file with `window` as an ordinary column instead.
      val partitioned = spec.windowed && rows > 0
      val shaped =
        if partitioned then cached.repartition(F.col("window"))
        else cached.repartition(1)
      val ordered = shaped.sortWithinPartitions(
        spec.sortKeys.head,
        spec.sortKeys.tail*
      )
      val writer = ordered.write.mode("errorifexists")
      val configured =
        if partitioned then writer.partitionBy("window") else writer
      configured.parquet(target.toUri.toString)
      cached.unpersist()
      renameParts(target)
      TableInfo(
        spec.name,
        rows,
        spec.frame.schema.fields.toVector.map(f =>
          f.name -> f.dataType.simpleString
        ),
        spec.windowed,
        partitioned,
        partitions,
        files(runDir, target)
      )
    }

  /** Gives every Parquet part file a name that depends only on its position.
    * Spark names a part after a random write UUID, so the names differed from
    * run to run, and a UUID tail that happens to be twelve digits reads as an
    * account number in a manifest. Task order is the sort order of the Spark
    * names (`part-NNNNN-`), so the position is stable. The checksum files that
    * the local file system writes beside the parts are removed with them.
    */
  private def renameParts(table: Path): Unit =
    if Files.isDirectory(table) then
      val stream = Files.walk(table)
      val all =
        try stream.iterator.asScala.toVector
        finally stream.close()
      all
        .filter(Files.isDirectory(_))
        .foreach { dir =>
          val parts = all
            .filter(p =>
              Files.isRegularFile(p) && p.getParent == dir &&
                p.getFileName.toString.endsWith(".parquet")
            )
            .sortBy(_.getFileName.toString)
          parts.zipWithIndex.foreach { (part, index) =>
            val name = part.getFileName.toString
            Files.deleteIfExists(dir.resolve(s".$name.crc"))
            Files.move(part, dir.resolve(f"part-$index%05d.parquet"))
          }
        }

  /** Parquet files under a table directory with size and SHA-256, in order. */
  def files(runDir: Path, directory: Path): Vector[FileInfo] =
    if !Files.isDirectory(directory) then Vector.empty
    else
      val stream = Files.walk(directory)
      try
        stream.iterator.asScala.toVector
          .filter(p => Files.isRegularFile(p))
          .filter(_.getFileName.toString.endsWith(".parquet"))
          .map(p => runDir.relativize(p).toString)
          .sorted
          .map { relative =>
            val file = runDir.resolve(relative)
            FileInfo(relative, Files.size(file), Digests.sha256File(file))
          }
      finally stream.close()

  def writeJson(path: Path, value: ujson.Value): Unit =
    Files.createDirectories(path.getParent)
    Files.writeString(path, ujson.write(value, indent = 2) + "\n", UTF_8)

object LoadManifests:
  val manifestType = "scholarchain.spark.load-manifest/1"

  private val neptuneVertices = Map(
    "nodes-researcher" -> ("Researcher", "nodeId"),
    "nodes-work" -> ("Work", "canonicalWorkId"),
    "nodes-venue" -> ("Venue", "venueId"),
    "nodes-topic" -> ("Topic", "topicId"),
    "nodes-institution" -> ("Institution", "institutionId")
  )
  private val neptuneEdges = Map(
    "edges-authored" -> ("AUTHORED", "researcherNodeId", "canonicalWorkId"),
    "edges-coauthored" -> ("COAUTHORED_WITH", "researcherA", "researcherB"),
    "edges-published-in" -> ("PUBLISHED_IN", "fromId", "toId")
  )
  private val dynamoKeys = Map(
    "researcher-metrics" -> ("researcherId", "window"),
    "components" -> ("window", "componentId"),
    "communities" -> ("window", "communityId"),
    "path-evidence" -> ("pairKey", "hopIndex"),
    "temporal-comparison" -> ("researcherId", "fromWindow"),
    "unresolved-mentions" -> ("mentionId", "workId")
  )

  private def tableJson(table: TableInfo): ujson.Obj =
    ujson.Obj(
      "table" -> table.name,
      "relativePrefix" -> s"tables/${table.name}/",
      "rows" -> ujson.Num(table.rows.toDouble),
      "partitionedBy" -> (if table.partitioned then ujson.Arr("window")
                          else ujson.Arr()),
      "schema" -> ujson.Arr.from(table.schema.map { (name, tpe) =>
        ujson.Obj("name" -> name, "type" -> tpe)
      }),
      "files" -> ujson.Arr.from(table.files.map { file =>
        ujson.Obj(
          "path" -> file.path,
          "bytes" -> ujson.Num(file.bytes.toDouble),
          "sha256" -> file.sha256
        )
      })
    )

  private def envelope(
      target: String,
      ctx: RunContext,
      body: (String, ujson.Value)*
  ): ujson.Obj =
    ujson
      .Obj(
        "manifestType" -> manifestType,
        "target" -> target,
        "runId" -> ctx.runId,
        "status" -> "planned-not-uploaded",
        "note" -> ("Nothing is uploaded or loaded. No bucket, endpoint, account " +
          "or other cloud identifier is recorded; the loader supplies the " +
          "destination. Prefixes are relative to the run directory.")
      )
      .tap(obj => body.foreach((k, v) => obj(k) = v))

  def write(
      runDir: Path,
      ctx: RunContext,
      tables: Vector[TableInfo]
  ): Vector[(String, Path)] =
    val byName = tables.map(t => t.name -> t).toMap
    def pick(names: Iterable[String]): Vector[TableInfo] =
      names.toVector.sorted.flatMap(byName.get)
    val neptune = envelope(
      "neptune",
      ctx,
      "vertices" -> ujson.Arr.from(pick(neptuneVertices.keys).map { t =>
        val (label, id) = neptuneVertices(t.name)
        tableJson(t).tap(o =>
          o("vertexLabel") = label
          o("idColumn") = id
        )
      }),
      "edges" -> ujson.Arr.from(pick(neptuneEdges.keys).map { t =>
        val (label, from, to) = neptuneEdges(t.name)
        tableJson(t).tap(o =>
          o("edgeLabel") = label
          o("fromColumn") = from
          o("toColumn") = to
        )
      })
    )
    val dynamo = envelope(
      "dynamodb",
      ctx,
      "itemTables" -> ujson.Arr.from(pick(dynamoKeys.keys).map { t =>
        val (partition, sort) = dynamoKeys(t.name)
        tableJson(t).tap(o =>
          o("partitionKeyColumn") = partition
          o("sortKeyColumn") = sort
        )
      })
    )
    val vectors = envelope(
      "s3-vectors",
      ctx,
      "vectorSources" -> ujson.Arr.from(pick(Vector("semantic-summaries")).map {
        t =>
          tableJson(t).tap(o =>
            o("idColumn") = "summaryId"
            o("textColumn") = "summaryText"
            o("metadataColumns") = ujson.Arr(
              "summaryType",
              "window",
              "subjectId",
              "nodeKind",
              "evidenceWorkIds",
              "evidenceMetrics",
              "evidenceNodeIds"
            )
          )
      })
    )
    val s3 = envelope(
      "s3",
      ctx,
      "tables" -> ujson.Arr.from(tables.sortBy(_.name).map(tableJson))
    )
    Vector(
      "neptune" -> neptune,
      "dynamodb" -> dynamo,
      "s3-vectors" -> vectors,
      "s3" -> s3
    )
      .map { (name, json) =>
        val path = runDir.resolve("load-manifests").resolve(s"$name.json")
        Export.writeJson(path, json)
        name -> path
      }

  extension [A](value: A)
    private def tap(f: A => Unit): A =
      f(value)
      value
