package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.HexFormat
import scholarchain.core.{ContractSchema, Provenance, SourceType}

/** One row of a Spark output table: column name to JSON value. Arrays are JSON
  * arrays, structs are JSON objects, nulls are `ujson.Null`.
  */
type Row = Map[String, ujson.Value]

enum ErrorCode:
  case MissingSuccessMarker
  case MalformedManifest
  case ChecksumMismatch
  case ImmutableKeyConflict
  case InvalidKey
  case BoundExceeded
  case MalformedRow
  case NullCharacter
  case LegacyEdgeId
  case InvalidEdgeId
  case DuplicateId
  case MissingEndpoint
  case MissingEvidence
  case QueryRejected
  case ConditionalCheckFailed
  case ItemTooLarge
  case InvalidItem
  case TtlNotAllowed
  case TtlRequired
  case VectorKeyInvalid
  case MetadataInvalid
  case EmbeddingInvalid
  case DuplicateVectorKey
  case ChangedVectorContent
  case RetryExhausted
  case UnmappedSummary
  case UnknownFilterKey
  case InvalidFilter
  case InvalidQuery
  case DeferredAdapter
  case RowCountMismatch
  case MissingText
  case TextHashMismatch
  case ExecutionInputChanged
  case AmbiguousRun
  case AwsNotFound
  case AwsAccessDenied
  case AwsRejected
  case IndexMissing
  case IndexSpecMismatch
  case BulkLoadFailed

final class StorageException(val code: ErrorCode, message: String)
    extends RuntimeException(s"${code}: ${message}")

/** A retryable service condition such as throttling. */
final class TransientStorageException(message: String)
    extends RuntimeException(message)

object Fail:
  def apply(code: ErrorCode, message: String): Nothing =
    throw new StorageException(code, message)

  def unless(ok: Boolean, code: ErrorCode, message: => String): Unit =
    if !ok then apply(code, message)

object Hashing:
  def sha256(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    HexFormat.of().formatHex(digest.digest(bytes))

  def sha256(text: String): String = sha256(text.getBytes(UTF_8))

object Text:
  val Nul = '\u0000'

  def requireNoNul(value: String, what: String): Unit =
    Fail.unless(
      !value.contains(Nul),
      ErrorCode.NullCharacter,
      s"$what contains a null character"
    )

  def utf8Size(value: String): Int = value.getBytes(UTF_8).length

object Rows:
  private def malformed(column: String, expected: String): Nothing =
    Fail(ErrorCode.MalformedRow, s"Column $column must be $expected")

  def optStr(row: Row, column: String): Option[String] =
    row.get(column) match
      case None | Some(ujson.Null) => None
      case Some(ujson.Str(value))  => Some(value)
      case Some(_)                 => malformed(column, "a string")

  def str(row: Row, column: String): String =
    optStr(row, column).getOrElse(malformed(column, "a present string"))

  def optLong(row: Row, column: String): Option[Long] =
    row.get(column) match
      case None | Some(ujson.Null)                 => None
      case Some(ujson.Num(value)) if value.isWhole => Some(value.toLong)
      case Some(_) => malformed(column, "an integer")

  def long(row: Row, column: String): Long =
    optLong(row, column).getOrElse(malformed(column, "a present integer"))

  def int(row: Row, column: String): Int =
    val value = long(row, column)
    if value < Int.MinValue || value > Int.MaxValue then
      malformed(column, "a 32-bit integer")
    else value.toInt

  def optDouble(row: Row, column: String): Option[Double] =
    row.get(column) match
      case None | Some(ujson.Null) => None
      case Some(ujson.Num(value))  => Some(value)
      case Some(_)                 => malformed(column, "a number")

  def optBool(row: Row, column: String): Option[Boolean] =
    row.get(column) match
      case None | Some(ujson.Null) => None
      case Some(ujson.Bool(value)) => Some(value)
      case Some(_)                 => malformed(column, "a boolean")

  def strList(row: Row, column: String): Vector[String] =
    row.get(column) match
      case None | Some(ujson.Null) => Vector.empty
      case Some(ujson.Arr(values)) =>
        values.toVector.map {
          case ujson.Str(value) => value
          case _                => malformed(column, "an array of strings")
        }
      case Some(_) => malformed(column, "an array")

  def provenance(row: Row): Provenance =
    val value = row.getOrElse("provenance", ujson.Null)
    Fail.unless(
      value.objOpt.isDefined,
      ErrorCode.MalformedRow,
      "Row has no provenance struct"
    )
    try upickle.default.read[Provenance](value)
    catch
      case error: Exception =>
        Fail(
          ErrorCode.MalformedRow,
          s"Invalid provenance: ${error.getMessage}"
        )

/** Read access to the tables of one Spark run. */
trait TableSource:
  def tableNames: Set[String]
  def rows(table: String): Vector[Row]

final class InMemoryTableSource(tables: Map[String, Vector[Row]])
    extends TableSource:
  def tableNames: Set[String] = tables.keySet

  def rows(table: String): Vector[Row] =
    tables.getOrElse(
      table,
      Fail(ErrorCode.MalformedManifest, s"Table $table is absent")
    )

/** Upper bounds for one load. A bound that would be exceeded fails the load
  * before any write; nothing is truncated silently.
  */
final case class LoadBounds(maxRows: Long, maxFiles: Int, maxBytes: Long):
  def checkRows(count: Long, what: String): Unit =
    Fail.unless(
      count <= maxRows,
      ErrorCode.BoundExceeded,
      s"$what has $count rows, bound is $maxRows"
    )

object LoadBounds:
  // S3RunLoader reads each file into memory, so the default byte bound is a
  // sane 1 GiB. Streaming large files is out of scope for Phase 7; raise the
  // bound deliberately for a bigger run.
  val Default: LoadBounds =
    LoadBounds(1000000L, 10000, 1L * 1024 * 1024 * 1024)

/** Provenance of the storage transformations. */
object StorageProvenance:
  val TransformVersion = "storage/1.0.0"

  def forRun(runId: String, step: String, recordId: String): Provenance =
    Provenance(
      ContractSchema.currentVersion,
      SourceType.Derived,
      s"spark-run:$runId",
      recordId,
      s"scholarchain:storage/$step",
      TransformVersion,
      runId
    )

/** A Spark run directory that is complete. Opening one without the `_SUCCESS`
  * marker fails; every loader goes through this gate.
  */
final class RunDirectory private (val path: Path, val runId: String):
  def file(relative: String): Path =
    val resolved = path.resolve(relative).normalize()
    Fail.unless(
      resolved.startsWith(path),
      ErrorCode.InvalidKey,
      s"Path escapes the run directory: $relative"
    )
    resolved

  def exists(relative: String): Boolean = Files.isRegularFile(file(relative))

  def bytes(relative: String): Array[Byte] = Files.readAllBytes(file(relative))

  def loadManifest(target: String): ujson.Value =
    val relative = s"load-manifests/$target.json"
    Fail.unless(
      exists(relative),
      ErrorCode.MalformedManifest,
      s"Missing $relative"
    )
    ujson.read(bytes(relative))

object RunDirectory:
  val SuccessMarker = "_SUCCESS"

  def open(path: Path): RunDirectory =
    val absolute = path.toAbsolutePath.normalize()
    Fail.unless(
      Files.isDirectory(absolute),
      ErrorCode.MissingSuccessMarker,
      s"Not a run directory: $absolute"
    )
    Fail.unless(
      Files.isRegularFile(absolute.resolve(SuccessMarker)),
      ErrorCode.MissingSuccessMarker,
      s"Run directory has no $SuccessMarker; refusing to load $absolute"
    )
    val manifestPath = absolute.resolve("load-manifests").resolve("s3.json")
    Fail.unless(
      Files.isRegularFile(manifestPath),
      ErrorCode.MalformedManifest,
      "Run directory has no load-manifests/s3.json"
    )
    val manifest = ujson.read(Files.readAllBytes(manifestPath))
    val runId = manifest.obj.get("runId").flatMap(_.strOpt).getOrElse("")
    Fail.unless(
      runId.startsWith("run:v1:"),
      ErrorCode.MalformedManifest,
      "load-manifests/s3.json carries no valid runId"
    )
    new RunDirectory(absolute, runId)

  /** A run ID as a path or key segment: `run:v1:x` becomes `run-v1-x`. */
  def segment(runId: String): String = runId.replace(':', '-')
