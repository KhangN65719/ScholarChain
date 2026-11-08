package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** The facts of the export that the pipeline records. */
final case class SourceRun(runId: String, transformVersion: String)

/** `TableSource` over the Spark JSON-lines export (`export-manifest.json` plus
  * part files under `<table>/`). `open` refuses a directory without `_SUCCESS`
  * and checks every file against the manifest (existence, byte size, SHA-256,
  * row count) and every table's row total, so a bad export fails before any row
  * is used. `rows` checks the file again as it reads it. Bounded by
  * `LoadBounds` (rows per table, files, bytes).
  */
final class JsonlExportSource private (
    val directory: Path,
    val sourceRun: SourceRun,
    val exportVersion: String,
    val manifestSha256: String,
    plan: Map[String, Vector[JsonlExportSource.PlannedFile]]
) extends TableSource:
  def tableNames: Set[String] = plan.keySet

  def rows(table: String): Vector[Row] =
    plan
      .getOrElse(
        table,
        Fail(ErrorCode.MalformedManifest, s"Table $table is absent")
      )
      .flatMap { file =>
        val bytes = JsonlExportSource.verified(directory, table, file)
        JsonlExportSource.parse(bytes, table, file.path)
      }

object JsonlExportSource:
  val ManifestName = "export-manifest.json"
  val ManifestType = "scholarchain.spark.jsonl-export-manifest/1"

  private[storage] final case class PlannedFile(
      path: String,
      rows: Long,
      bytes: Long,
      sha256: String
  )

  private def bad(message: String): Nothing =
    Fail(ErrorCode.MalformedManifest, message)

  private type Fields = upickle.core.LinkedHashMap[String, ujson.Value]

  private def objectOf(value: ujson.Value, what: String): Fields =
    value.objOpt.getOrElse(bad(s"$what must be a JSON object"))

  private def text(
      fields: Fields,
      name: String,
      what: String
  ): String =
    fields.get(name).flatMap(_.strOpt).filter(_.nonEmpty).getOrElse {
      bad(s"$what has no usable '$name'")
    }

  private def whole(
      fields: Fields,
      name: String,
      what: String
  ): Long =
    fields
      .get(name)
      .flatMap(_.numOpt)
      .filter(n => n.isWhole && n >= 0)
      .map(_.toLong)
      .getOrElse(bad(s"$what has no usable '$name'"))

  private def lines(bytes: Array[Byte]): Vector[String] =
    new String(bytes, UTF_8).linesIterator.toVector

  /** Reads one part file and checks it against its manifest entry. */
  private[storage] def verified(
      directory: Path,
      table: String,
      file: PlannedFile
  ): Array[Byte] =
    val path = directory.resolve(file.path).normalize()
    Fail.unless(
      path.startsWith(directory) && path != directory,
      ErrorCode.InvalidKey,
      s"Path escapes the export directory: ${file.path}"
    )
    val tableDir = directory.resolve(table).normalize()
    Fail.unless(
      tableDir.getParent == directory && path.startsWith(tableDir) &&
        path != tableDir,
      ErrorCode.InvalidKey,
      s"$table file ${file.path} is not under its own $table/ directory"
    )
    Fail.unless(
      Files.isRegularFile(path),
      ErrorCode.MalformedManifest,
      s"$table file ${file.path} listed in the manifest is missing"
    )
    // A symlinked part file is refused even when its content matches, and so
    // is any path whose real location is outside the real table directory.
    Fail.unless(
      !Files.isSymbolicLink(path) &&
        path
          .toRealPath()
          .startsWith(
            directory.toRealPath().resolve(table)
          ),
      ErrorCode.InvalidKey,
      s"$table file ${file.path} is a symbolic link or resolves outside " +
        s"the export's $table/ directory"
    )
    val bytes = Files.readAllBytes(path)
    Fail.unless(
      bytes.length.toLong == file.bytes,
      ErrorCode.ChecksumMismatch,
      s"${file.path} has ${bytes.length} bytes, the manifest says ${file.bytes}"
    )
    Fail.unless(
      Hashing.sha256(bytes) == file.sha256,
      ErrorCode.ChecksumMismatch,
      s"${file.path} does not match the SHA-256 in $ManifestName"
    )
    val count = lines(bytes).size.toLong
    Fail.unless(
      count == file.rows,
      ErrorCode.RowCountMismatch,
      s"${file.path} has $count rows, the manifest says ${file.rows}"
    )
    bytes

  private[storage] def parse(
      bytes: Array[Byte],
      table: String,
      path: String
  ): Vector[Row] =
    lines(bytes).zipWithIndex.map { case (line, index) =>
      val parsed =
        try ujson.read(line)
        catch
          case _: Exception =>
            Fail(
              ErrorCode.MalformedRow,
              s"$table $path line ${index + 1} is not JSON"
            )
      parsed.objOpt match
        case Some(fields) => fields.toMap
        case None         =>
          Fail(
            ErrorCode.MalformedRow,
            s"$table $path line ${index + 1} is not a JSON object"
          )
    }

  def open(
      path: Path,
      bounds: LoadBounds = LoadBounds.Default
  ): JsonlExportSource =
    val directory = path.toAbsolutePath.normalize()
    Fail.unless(
      Files.isDirectory(directory) &&
        Files.isRegularFile(directory.resolve(RunDirectory.SuccessMarker)),
      ErrorCode.MissingSuccessMarker,
      s"Export has no ${RunDirectory.SuccessMarker}; refusing to read $directory"
    )
    val manifestPath = directory.resolve(ManifestName)
    Fail.unless(
      Files.isRegularFile(manifestPath),
      ErrorCode.MalformedManifest,
      s"Export has no $ManifestName"
    )
    val manifestBytes = Files.readAllBytes(manifestPath)
    val manifest =
      try objectOf(ujson.read(manifestBytes), ManifestName)
      catch
        case error: StorageException => throw error
        case _: Exception            => bad(s"$ManifestName is not JSON")
    Fail.unless(
      manifest.get("manifestType").flatMap(_.strOpt).contains(ManifestType),
      ErrorCode.MalformedManifest,
      s"$ManifestName has an unknown manifestType"
    )
    Fail.unless(
      manifest.get("sourceSuccessSeen").contains(ujson.True),
      ErrorCode.MalformedManifest,
      s"$ManifestName does not record the source _SUCCESS"
    )
    val run = SourceRun(
      text(manifest, "sourceRunId", ManifestName),
      text(manifest, "transformVersion", ManifestName)
    )
    val exportVersion = text(manifest, "exportVersion", ManifestName)
    val tables = objectOf(
      manifest.getOrElse("tables", bad(s"$ManifestName has no tables")),
      "tables"
    )
    val plan = tables.toVector.map { case (name, value) =>
      val fields = objectOf(value, s"table $name")
      val files = fields
        .get("files")
        .flatMap(_.arrOpt)
        .getOrElse(bad(s"table $name has no files list"))
        .toVector
        .map { entry =>
          val f = objectOf(entry, s"a file of $name")
          PlannedFile(
            text(f, "path", s"a file of $name"),
            whole(f, "rows", s"a file of $name"),
            whole(f, "bytes", s"a file of $name"),
            text(f, "sha256", s"a file of $name")
          )
        }
      val declared = whole(fields, "rows", s"table $name")
      bounds.checkRows(declared, name)
      Fail.unless(
        files.map(_.rows).sum == declared,
        ErrorCode.RowCountMismatch,
        s"Table $name lists $declared rows but its files hold " +
          s"${files.map(_.rows).sum}"
      )
      name -> files
    }
    val allFiles = plan.flatMap { case (name, files) => files.map(name -> _) }
    Fail.unless(
      allFiles.size <= bounds.maxFiles,
      ErrorCode.BoundExceeded,
      s"${allFiles.size} files, bound is ${bounds.maxFiles}"
    )
    val total = allFiles.map { case (_, file) =>
      val actual = directory.resolve(file.path).normalize()
      val onDisk =
        if actual.startsWith(directory) && Files.isRegularFile(actual) then
          Files.size(actual)
        else 0L
      math.max(file.bytes, onDisk)
    }.sum
    Fail.unless(
      total <= bounds.maxBytes,
      ErrorCode.BoundExceeded,
      s"$total bytes, bound is ${bounds.maxBytes}"
    )
    allFiles.foreach { case (name, file) => verified(directory, name, file) }
    new JsonlExportSource(
      directory,
      run,
      exportVersion,
      Hashing.sha256(manifestBytes),
      plan.toMap
    )
