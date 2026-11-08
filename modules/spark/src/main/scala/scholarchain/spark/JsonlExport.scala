package scholarchain.spark

import java.io.{IOException, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  Paths,
  SimpleFileVisitor,
  StandardCopyOption
}
import org.apache.spark.Partitioner
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.{ArrayType, DataType, MapType, StructType}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** A refusal or an invalid input of the export, reported by name. */
final class ExportFailure(message: String) extends RuntimeException(message)

final case class ExportArgs(runDir: Path, outDir: Path, rowsPerFile: Int)

object ExportArgs:
  val defaultRowsPerFile = 20000
  val maxFiles = 64
  val usage: String =
    "Usage: JsonlExport <run directory> <output directory> " +
      "[--rows-per-file <n>]"

  def parse(args: Vector[String]): Either[String, ExportArgs] =
    args match
      case Vector(run, out) =>
        Right(ExportArgs(Paths.get(run), Paths.get(out), defaultRowsPerFile))
      case Vector(run, out, "--rows-per-file", count) =>
        count.toIntOption
          .filter(_ >= 1)
          .toRight(s"--rows-per-file must be a positive integer, not '$count'")
          .map(n => ExportArgs(Paths.get(run), Paths.get(out), n))
      case _ => Left("Expected <run directory> <output directory>")

/** A table of the run that is exported, with its ordering key and the columns
  * the storage readers need.
  */
final case class ExportTable(
    name: String,
    idColumns: Vector[String],
    required: Vector[String]
)

object ExportTables:
  private val provenance = "provenance"

  val all: Vector[ExportTable] = Vector(
    ExportTable(
      "semantic-summaries",
      Vector("summaryId"),
      Vector(
        "summaryId",
        "summaryType",
        "window",
        "windowStart",
        "windowEnd",
        "subjectId",
        "nodeKind",
        "summaryText",
        "evidenceWorkIds",
        "evidenceMetrics",
        "evidenceNodeIds",
        provenance
      )
    ),
    ExportTable(
      "nodes-work",
      Vector("canonicalWorkId"),
      Vector("canonicalWorkId", "year", "source", provenance)
    ),
    ExportTable(
      "researcher-metrics",
      Vector("researcherId", "window"),
      Vector("researcherId", "window", "communityId", provenance)
    )
  )

  val edgesTable = "edges-coauthored"
  val edgeIdColumn = "edgeId"

/** Converts Spark rows to one JSON line each. Nulls stay explicit, arrays are
  * JSON arrays, structs are JSON objects with the fields in schema order and
  * maps are objects with sorted keys. A type with no JSON form is an error.
  */
object JsonRows:
  private val maxSafeLong = 9007199254740992L

  private val scalars =
    Set("string", "boolean", "integer", "short", "byte", "long")
      ++ Set("double", "float")

  private def scalarName(dt: DataType): String =
    if dt.typeName.startsWith("string") then "string" else dt.typeName

  def check(dt: DataType, path: String): Unit =
    dt match
      case a: ArrayType  => check(a.elementType, path)
      case s: StructType =>
        s.fields.foreach(f => check(f.dataType, s"$path.${f.name}"))
      case m: MapType =>
        if scalarName(m.keyType) != "string" then
          throw new ExportFailure(s"Unsupported map key type at $path")
        check(m.valueType, path)
      case other =>
        if !scalars.contains(scalarName(other)) then
          throw new ExportFailure(
            s"Unsupported type ${other.simpleString} at $path"
          )

  private def scalarJson(dt: DataType, v: Any, path: String): ujson.Value =
    scalarName(dt) match
      case "string"  => ujson.Str(v.asInstanceOf[String])
      case "boolean" => ujson.Bool(v.asInstanceOf[Boolean])
      case "long"    =>
        val n = v.asInstanceOf[Long]
        if n > maxSafeLong || n < -maxSafeLong then
          throw new ExportFailure(s"Integer at $path is not exact in JSON")
        ujson.Num(n.toDouble)
      case _ =>
        val d = v.asInstanceOf[Number].doubleValue
        if d.isNaN || d.isInfinite then
          throw new ExportFailure(s"Non-finite number at $path")
        ujson.Num(d)

  def toJson(dt: DataType, v: Any, path: String): ujson.Value =
    if v == null then ujson.Null
    else
      dt match
        case a: ArrayType =>
          val items = v.asInstanceOf[scala.collection.Seq[Any]].toVector
          ujson.Arr.from(items.map(x => toJson(a.elementType, x, path)))
        case s: StructType =>
          val row = v.asInstanceOf[Row]
          val fields = s.fields.toVector.zipWithIndex.map { (f, i) =>
            f.name -> toJson(f.dataType, row.get(i), s"$path.${f.name}")
          }
          ujson.Obj.from(fields)
        case m: MapType =>
          val entries = v.asInstanceOf[scala.collection.Map[String, Any]]
          val fields = entries.toVector.sortBy(_._1).map { (k, x) =>
            k -> toJson(m.valueType, x, s"$path.$k")
          }
          ujson.Obj.from(fields)
        case other => scalarJson(other, v, path)

  def line(schema: StructType, row: Row): String =
    val fields = schema.fields.toVector.zipWithIndex.map { (f, i) =>
      f.name -> toJson(f.dataType, row.get(i), f.name)
    }
    ujson.write(ujson.Obj.from(fields))

  def idKey(row: Row, indexes: Vector[Int], names: Vector[String]): String =
    indexes
      .zip(names)
      .map { (i, name) =>
        if row.isNullAt(i) then
          throw new ExportFailure(s"Null value in id column $name")
        row.get(i).toString
      }
      .mkString("\u0000")

/** Row number to output file: a fixed run of `rowsPerFile` ranks per file. */
final class RankPartitioner(rowsPerFile: Long, files: Int) extends Partitioner:
  def numPartitions: Int = files

  def getPartition(key: Any): Int =
    math.min(key.asInstanceOf[Long] / rowsPerFile, files - 1L).toInt

final case class FileEntry(
    path: String,
    rows: Long,
    bytes: Long,
    sha256: String
)

final case class TableResult(
    name: String,
    rows: Long,
    columns: Vector[(String, String)],
    sourceFiles: Vector[FileInfo],
    files: Vector[FileEntry]
)

/** Counts of the edge ID prefixes of `edges-coauthored` (Phase 7 item D4). */
final case class EdgeIdStats(
    fromColumn: String,
    toColumn: String,
    total: Long,
    mentionCoauthor: Long,
    coauthor: Long,
    other: Long,
    coauthorWithMentionEndpoint: Long,
    sourceFiles: Vector[FileInfo]
)

/** Converts the tables of one finished Spark run to JSON lines for the storage
  * `TableSource` seam, and reports the edge ID prefix counts of the run.
  *
  * The run directory is only read. Output goes to a staging directory beside
  * the output directory and is moved into place after `_SUCCESS` is written, so
  * a failed export leaves no partial output. A non-empty output directory is
  * kept as it is unless the new manifest equals the existing one byte for byte.
  */
object JsonlExport:
  val resultPrefix = "SPARK-EXPORT RESULT: "
  val exportVersion = "spark-jsonl-export/1.0.0"
  val manifestType = "scholarchain.spark.jsonl-export-manifest/1"
  val manifestName = "export-manifest.json"
  val mentionCoauthorPrefix = "mention-coauthor:v1:"
  val coauthorPrefix = "coauthor:v1:"
  val mentionNodePrefix = "mention:v1:"

  def main(args: Array[String]): Unit =
    System.exit(run(args.toVector, Console.out, None))

  private def failure(error: Throwable): Option[ExportFailure] =
    Iterator
      .iterate[Throwable](error)(_.getCause)
      .takeWhile(_ != null)
      .collectFirst { case e: ExportFailure => e }

  def run(
      args: Vector[String],
      out: PrintStream,
      provided: Option[SparkSession]
  ): Int =
    def fail(reason: String, code: Int): Int =
      val line = reason.linesIterator.nextOption().getOrElse(reason)
      out.println(s"${resultPrefix}FAILED ${line.slice(0, 600)}")
      code
    ExportArgs.parse(args) match
      case Left(error) =>
        out.println(ExportArgs.usage)
        fail(error, 2)
      case Right(config) =>
        try
          exportRun(config, out, provided)
          0
        catch
          case NonFatal(error) =>
            failure(error) match
              case Some(known) => fail(known.getMessage, 2)
              case None        =>
                val name = error.getClass.getSimpleName
                fail(s"$name: ${error.getMessage}", 1)

  private def refuse(message: String): Nothing =
    throw new ExportFailure(message)

  /** Deletes a tree without following a symbolic link: a link is removed as
    * itself and its target is left alone.
    */
  private def deleteTree(path: Path): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      Files.walkFileTree(
        path,
        new SimpleFileVisitor[Path]:
          override def visitFile(
              file: Path,
              attrs: BasicFileAttributes
          ): FileVisitResult =
            Files.delete(file)
            FileVisitResult.CONTINUE

          override def postVisitDirectory(
              dir: Path,
              error: IOException
          ): FileVisitResult =
            if error != null then throw error
            Files.delete(dir)
            FileVisitResult.CONTINUE
      )

  val stagingMarkerName = ".export-staging-marker"
  private val stagingMarkerPrefix = "scholarchain-export-staging"

  private val stagingMarkerPattern =
    java.util.regex.Pattern.compile(
      java.util.regex.Pattern.quote(s"$stagingMarkerPrefix $exportVersion ") +
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\n?"
    )

  private def stagingEntries: Set[String] =
    Set(stagingMarkerName, "_work", manifestName, "_SUCCESS") ++
      ExportTables.all.map(_.name) + ExportTables.edgesTable

  /** Creates a fresh staging directory carrying this tool's marker. An existing
    * directory of that name is removed only when it is a plain directory with a
    * valid marker of this export version and holds nothing but names the tool
    * writes; anything else is refused and left untouched.
    */
  private def prepareStaging(staging: Path): Unit =
    val noFollow = LinkOption.NOFOLLOW_LINKS
    if Files.exists(staging, noFollow) then
      def foreign(why: String): Nothing =
        refuse(s"Refusing to delete $staging: $why; remove it by hand")
      if Files.isSymbolicLink(staging) then foreign("it is a symbolic link")
      if !Files.isDirectory(staging, noFollow) then
        foreign("it is not a directory")
      val marker = staging.resolve(stagingMarkerName)
      val valid =
        Files.isRegularFile(marker, noFollow) &&
          Files.size(marker) < 256L &&
          stagingMarkerPattern.matcher(Files.readString(marker, UTF_8)).matches
      if !valid then foreign("it has no valid export staging marker")
      val stream = Files.list(staging)
      val names =
        try stream.iterator.asScala.map(_.getFileName.toString).toSet
        finally stream.close()
      val extra = names -- stagingEntries
      if extra.nonEmpty then
        foreign(s"it holds unexpected entries ${extra.toVector.sorted}")
      deleteTree(staging)
    Files.createDirectories(staging.getParent)
    Files.createDirectory(staging)
    val token = java.util.UUID.randomUUID.toString
    Files.writeString(
      staging.resolve(stagingMarkerName),
      s"$stagingMarkerPrefix $exportVersion $token\n",
      UTF_8
    )

  private def isEmptyDirectory(path: Path): Boolean =
    val stream = Files.list(path)
    try !stream.iterator.hasNext
    finally stream.close()

  private def readJson(path: Path): ujson.Value =
    ujson.read(Files.readString(path, UTF_8))

  private def textField(json: ujson.Value, name: String, what: String): String =
    json.obj.get(name).flatMap(_.strOpt) match
      case Some(value) if value.nonEmpty => value
      case _ => refuse(s"$what has no usable '$name'")

  private def newSession(work: Path): SparkSession =
    SparkSession
      .builder()
      .appName("scholarchain-spark-jsonl-export")
      .master("local[2]")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config(
        "spark.sql.warehouse.dir",
        work.resolve("warehouse").toUri.toString
      )
      .config("spark.local.dir", work.resolve("local").toString)
      .config("spark.buffer.pageSize", "1m")
      .getOrCreate()

  private def configure(session: SparkSession): Unit =
    session.conf.set("spark.sql.shuffle.partitions", "8")
    session.conf.set("spark.sql.adaptive.enabled", "false")
    session.conf.set("spark.sql.session.timeZone", "UTC")
    session.conf.set(
      "spark.sql.sources.partitionColumnTypeInference.enabled",
      "false"
    )

  private def lineCount(path: Path): Long =
    val reader = Files.newBufferedReader(path, UTF_8)
    try reader.lines().count()
    finally reader.close()

  /** Gives each part file a name that depends only on its position and removes
    * the checksum and marker files the local file system writes beside them.
    */
  private def finishParts(
      table: ExportTable,
      target: Path,
      staging: Path,
      rows: Long,
      files: Int,
      rowsPerFile: Int
  ): Vector[FileEntry] =
    val entries = (0 until files).toVector.map { index =>
      val spark = target.resolve(f"part-$index%05d")
      val named = target.resolve(f"part-$index%05d.jsonl")
      Files.move(spark, named)
      Files.deleteIfExists(target.resolve(f".part-$index%05d.crc"))
      val expected =
        math.min(rowsPerFile.toLong, rows - index.toLong * rowsPerFile)
      val actual = lineCount(named)
      if actual != expected then
        refuse(
          s"${table.name} file $index has $actual rows, expected $expected"
        )
      FileEntry(
        staging.relativize(named).toString,
        actual,
        Files.size(named),
        Digests.sha256File(named)
      )
    }
    Files.deleteIfExists(target.resolve("_SUCCESS"))
    Files.deleteIfExists(target.resolve("._SUCCESS.crc"))
    val left = Digests
      .dataFiles(target)
      .map(_.getFileName.toString)
      .filterNot(_.endsWith(".jsonl"))
    if left.nonEmpty then
      refuse(s"Unexpected files in ${table.name}: ${left.mkString(", ")}")
    entries

  private def exportTable(
      session: SparkSession,
      runDir: Path,
      staging: Path,
      table: ExportTable,
      rowsPerFile: Int
  ): TableResult =
    val source = runDir.resolve("tables").resolve(table.name)
    if !Files.isDirectory(source) then
      refuse(s"Missing table ${table.name}: $source")
    val frame = session.read.parquet(source.toUri.toString)
    val schema = frame.schema
    val missing = table.required.filterNot(schema.fieldNames.contains)
    if missing.nonEmpty then
      refuse(s"Table ${table.name} lacks column(s) ${missing.mkString(", ")}")
    schema.fields.foreach { f =>
      JsonRows.check(f.dataType, s"${table.name}.${f.name}")
    }
    table.idColumns.foreach { name =>
      if schema(name).dataType.typeName != "string" then
        refuse(s"Id column ${table.name}.$name must be a string")
    }
    val rows = Stages.run(session, s"jsonl-count:${table.name}")(frame.count())
    val files = math.max(1L, (rows + rowsPerFile - 1) / rowsPerFile)
    if files > ExportArgs.maxFiles then
      refuse(
        s"${table.name} has $rows rows, which needs $files files; " +
          s"the bound is ${ExportArgs.maxFiles}. Raise --rows-per-file."
      )
    val idNames = table.idColumns
    val idIndexes = idNames.map(schema.fieldIndex)
    val keyed = frame.rdd.map { row =>
      (JsonRows.idKey(row, idIndexes, idNames), JsonRows.line(schema, row))
    }
    val ranked = keyed
      .sortBy(pair => pair, true, files.toInt)
      .zipWithIndex()
      .map { case ((_, json), rank) => (rank, json) }
    val placed = ranked.repartitionAndSortWithinPartitions(
      new RankPartitioner(rowsPerFile.toLong, files.toInt)
    )
    val target = staging.resolve(table.name)
    Stages.run(session, s"jsonl-write:${table.name}") {
      placed.map(_._2).saveAsTextFile(target.toUri.toString)
    }
    val entries =
      finishParts(table, target, staging, rows, files.toInt, rowsPerFile)
    TableResult(
      table.name,
      rows,
      schema.fields.toVector.map(f => f.name -> f.dataType.simpleString),
      Export.files(runDir, source),
      entries
    )

  private def endpointColumns(runDir: Path): (String, String) =
    val path = runDir.resolve("load-manifests").resolve("neptune.json")
    if !Files.isRegularFile(path) then
      refuse("Run has no load-manifests/neptune.json to name edge endpoints")
    val edges = readJson(path).obj.get("edges").map(_.arr.toVector)
    val entry = edges.getOrElse(Vector.empty).find { e =>
      e.obj.get("table").flatMap(_.strOpt).contains(ExportTables.edgesTable)
    }
    entry match
      case Some(found) =>
        val from = textField(found, "fromColumn", "neptune.json edge entry")
        val to = textField(found, "toColumn", "neptune.json edge entry")
        (from, to)
      case None =>
        refuse(s"neptune.json lists no ${ExportTables.edgesTable} edge table")

  private def edgeStats(session: SparkSession, runDir: Path): EdgeIdStats =
    val source = runDir.resolve("tables").resolve(ExportTables.edgesTable)
    if !Files.isDirectory(source) then
      refuse(s"Missing table ${ExportTables.edgesTable}: $source")
    val (from, to) = endpointColumns(runDir)
    val frame = session.read.parquet(source.toUri.toString)
    val idColumn = ExportTables.edgeIdColumn
    Vector(idColumn, from, to).foreach { name =>
      val field = frame.schema.fields.find(_.name == name)
      if field.isEmpty then
        refuse(s"Table ${ExportTables.edgesTable} lacks column $name")
      if field.exists(_.dataType.typeName != "string") then
        refuse(s"Column ${ExportTables.edgesTable}.$name must be a string")
    }
    val m = mentionCoauthorPrefix
    val c = coauthorPrefix
    val n = mentionNodePrefix
    val query =
      s"""SELECT CAST(count(*) AS BIGINT) AS total,
         |  CAST(COALESCE(sum(CASE WHEN startswith(`$idColumn`, '$m')
         |    THEN 1 ELSE 0 END), 0) AS BIGINT) AS mentionCoauthor,
         |  CAST(COALESCE(sum(CASE WHEN startswith(`$idColumn`, '$c')
         |    THEN 1 ELSE 0 END), 0) AS BIGINT) AS coauthor,
         |  CAST(COALESCE(sum(CASE WHEN startswith(`$idColumn`, '$c')
         |    AND (startswith(`$from`, '$n') OR startswith(`$to`, '$n'))
         |    THEN 1 ELSE 0 END), 0) AS BIGINT) AS coauthorWithMention
         |FROM {e}""".stripMargin
    val result = Stages.run(session, "jsonl-edge-id-counts") {
      val counted = Sql.run(session, query, "e" -> frame)
      DriverGuard.rows(counted, 1, "edge id counts")(0)
    }
    val total = result.getLong(0)
    val mention = result.getLong(1)
    val coauthor = result.getLong(2)
    EdgeIdStats(
      from,
      to,
      total,
      mention,
      coauthor,
      total - mention - coauthor,
      result.getLong(3),
      Export.files(runDir, source)
    )

  private def fileJson(file: FileInfo): ujson.Obj =
    ujson.Obj(
      "path" -> file.path,
      "bytes" -> ujson.Num(file.bytes.toDouble),
      "sha256" -> file.sha256
    )

  private def tableJson(table: TableResult, ids: Vector[String]): ujson.Obj =
    ujson.Obj(
      "rows" -> ujson.Num(table.rows.toDouble),
      "idColumns" -> ujson.Arr.from(ids.map(ujson.Str(_))),
      "columns" -> ujson.Arr.from(table.columns.map { (name, tpe) =>
        ujson.Obj("name" -> name, "type" -> tpe)
      }),
      "sourceFiles" -> ujson.Arr.from(table.sourceFiles.map(fileJson)),
      "files" -> ujson.Arr.from(table.files.map { file =>
        ujson.Obj(
          "path" -> file.path,
          "rows" -> ujson.Num(file.rows.toDouble),
          "bytes" -> ujson.Num(file.bytes.toDouble),
          "sha256" -> file.sha256
        )
      })
    )

  private def edgeJson(stats: EdgeIdStats): ujson.Obj =
    ujson.Obj(
      "table" -> ExportTables.edgesTable,
      "edgeIdColumn" -> ExportTables.edgeIdColumn,
      "fromColumn" -> stats.fromColumn,
      "toColumn" -> stats.toColumn,
      "totalEdges" -> ujson.Num(stats.total.toDouble),
      "mentionCoauthorPrefix" -> mentionCoauthorPrefix,
      "mentionCoauthorEdges" -> ujson.Num(stats.mentionCoauthor.toDouble),
      "coauthorPrefix" -> coauthorPrefix,
      "coauthorEdges" -> ujson.Num(stats.coauthor.toDouble),
      "otherPrefixEdges" -> ujson.Num(stats.other.toDouble),
      "mentionNodePrefix" -> mentionNodePrefix,
      "coauthorEdgesWithMentionEndpoint" ->
        ujson.Num(stats.coauthorWithMentionEndpoint.toDouble),
      "anyCoauthorEdgeHasMentionEndpoint" ->
        ujson.Bool(stats.coauthorWithMentionEndpoint > 0L),
      "sourceFiles" -> ujson.Arr.from(stats.sourceFiles.map(fileJson))
    )

  private def manifestBytes(
      runId: String,
      transformVersion: String,
      runManifestSha: String,
      rowsPerFile: Int,
      tables: Vector[TableResult],
      stats: EdgeIdStats
  ): Array[Byte] =
    val ids = ExportTables.all.map(t => t.name -> t.idColumns).toMap
    val manifest = ujson.Obj(
      "manifestType" -> manifestType,
      "exportVersion" -> exportVersion,
      "sourceRunId" -> runId,
      "sourceDirectory" -> RunContext.directoryName(runId),
      "sourceSuccessSeen" -> true,
      "sourceRunManifestSha256" -> runManifestSha,
      "transformVersion" -> transformVersion,
      "rowsPerFile" -> rowsPerFile,
      "tables" -> ujson.Obj.from(
        tables.map(t => t.name -> tableJson(t, ids(t.name)))
      ),
      "coauthorEdgeIds" -> edgeJson(stats)
    )
    (ujson.write(manifest, indent = 2) + "\n").getBytes(UTF_8)

  /** The output of a finished export must still match its own manifest. */
  private def verifyExisting(outDir: Path, manifest: ujson.Value): Unit =
    if !Files.isRegularFile(outDir.resolve("_SUCCESS")) then
      refuse(s"Existing output has no _SUCCESS: $outDir")
    manifest("tables").obj.values.foreach { table =>
      table("files").arr.foreach { file =>
        val path = outDir.resolve(file("path").str)
        if !Files.isRegularFile(path) ||
          Digests.sha256File(path) != file("sha256").str
        then refuse(s"Existing output file differs from its manifest: $path")
      }
    }

  private def exportRun(
      config: ExportArgs,
      out: PrintStream,
      provided: Option[SparkSession]
  ): Unit =
    val runDir = config.runDir.toAbsolutePath.normalize
    val requested = config.outDir.toAbsolutePath.normalize
    def say(line: String): Unit = out.println(line)
    if !Files.isDirectory(runDir) then refuse(s"Not a run directory: $runDir")
    if !Files.isRegularFile(runDir.resolve("_SUCCESS")) then
      refuse(s"Run has no _SUCCESS; refusing to export $runDir")
    if Files.isSymbolicLink(requested) && !Files.exists(requested) then
      refuse(s"Output is a dangling symbolic link: $requested")
    val runReal = runDir.toRealPath()
    val outDir = OutputPolicy.resolved(requested)
    if outDir.startsWith(runReal) || runReal.startsWith(outDir) then
      refuse(s"Output and run directories overlap: $outDir")
    OutputPolicy
      .check(requested, OutputPolicy.repositoryRoot(Paths.get("")))
      .left
      .foreach(refuse)
    if Files.exists(outDir) && !Files.isDirectory(outDir) then
      refuse(s"Output exists and is not a directory: $outDir")
    val runManifestPath = runDir.resolve("run-manifest.json")
    if !Files.isRegularFile(runManifestPath) then
      refuse(s"Run has no run-manifest.json: $runDir")
    val runManifest = readJson(runManifestPath)
    val runId = textField(runManifest, "runId", "run-manifest.json")
    val version =
      textField(runManifest, "transformVersion", "run-manifest.json")
    if runDir.getFileName.toString != RunContext.directoryName(runId) then
      refuse(s"Run directory name does not match run ID $runId")
    say(s"source-run: $runId")
    say(s"source _SUCCESS: seen")
    say(s"transformVersion: $version")
    val staging = outDir.resolveSibling(s".staging-${outDir.getFileName}")
    prepareStaging(staging)
    val work = staging.resolve("_work")
    try
      val session = provided.getOrElse(newSession(work))
      configure(session)
      val (tables, stats) =
        try
          val results = ExportTables.all.map { table =>
            val result =
              exportTable(session, runDir, staging, table, config.rowsPerFile)
            val count = result.files.size
            say(s"table ${table.name} rows=${result.rows} files=$count")
            result
          }
          (results, edgeStats(session, runDir))
        finally if provided.isEmpty then session.stop()
      say(s"edges-coauthored total=${stats.total}")
      say(
        s"edges-coauthored $mentionCoauthorPrefix count=${stats.mentionCoauthor}"
      )
      say(s"edges-coauthored $coauthorPrefix count=${stats.coauthor}")
      say(s"edges-coauthored other-prefix count=${stats.other}")
      say(
        s"edges-coauthored $coauthorPrefix with $mentionNodePrefix endpoint: " +
          s"${stats.coauthorWithMentionEndpoint > 0L} " +
          s"(count=${stats.coauthorWithMentionEndpoint})"
      )
      deleteTree(work)
      val bytes = manifestBytes(
        runId,
        version,
        Digests.sha256File(runManifestPath),
        config.rowsPerFile,
        tables,
        stats
      )
      if Files.exists(outDir) && !isEmptyDirectory(outDir) then
        val existing = outDir.resolve(manifestName)
        if !Files.isRegularFile(existing) ||
          !java.util.Arrays.equals(Files.readAllBytes(existing), bytes)
        then
          refuse(
            s"Output directory is not empty and its manifest differs: $outDir"
          )
        verifyExisting(outDir, readJson(existing))
        say("output already complete and identical; nothing written")
      else
        Files.write(staging.resolve(manifestName), bytes)
        Files.write(staging.resolve("_SUCCESS"), Array.emptyByteArray)
        Files.delete(staging.resolve(stagingMarkerName))
        if Files.exists(outDir) then Files.delete(outDir)
        Files.move(staging, outDir, StandardCopyOption.ATOMIC_MOVE)
        say(s"output: $outDir")
      say(s"${resultPrefix}SUCCESS")
    finally deleteTree(staging)
