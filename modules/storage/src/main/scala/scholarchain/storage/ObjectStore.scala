package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*
import scholarchain.core.Provenance
import upickle.default.{ReadWriter, macroRW}

/** The ordinary S3 prefixes of the project. */
enum S3Prefix(val dir: String):
  case Raw extends S3Prefix("raw")
  case Curated extends S3Prefix("curated")
  case Graph extends S3Prefix("graph")
  case SemanticContext extends S3Prefix("semantic-context")
  case Reports extends S3Prefix("reports")
  case Logs extends S3Prefix("logs")

final case class ObjectMeta(
    key: String,
    bytes: Long,
    sha256: String,
    sourceRef: String
)

final case class PutResult(meta: ObjectMeta, created: Boolean)

/** Port for ordinary S3. The AWS-backed adapter is DEFERRED (see AwsAdapters);
  * the local fakes below implement the same contract.
  */
trait ObjectStore:
  protected def write(key: String, bytes: Array[Byte], sourceRef: String): Unit
  def head(key: String): Option[ObjectMeta]
  def get(key: String): Option[Array[Byte]]
  def list(prefix: String): Vector[ObjectMeta]

  /** The keys under a prefix. An adapter that can list keys more cheaply than
    * it can describe objects overrides this.
    */
  def listKeys(prefix: String): Vector[String] = list(prefix).map(_.key)

  /** Versioned keys are immutable: the same content is a no-op, different
    * content under the same key is an error.
    */
  final def putImmutable(
      key: String,
      bytes: Array[Byte],
      sourceRef: String
  ): PutResult =
    ObjectKeys.requireValid(key)
    val sha = Hashing.sha256(bytes)
    head(key) match
      case Some(existing) if existing.sha256 == sha =>
        PutResult(existing, created = false)
      case Some(existing) =>
        Fail(
          ErrorCode.ImmutableKeyConflict,
          s"Key $key holds ${existing.sha256}, refusing to replace with $sha"
        )
      case None =>
        write(key, bytes, sourceRef)
        PutResult(ObjectMeta(key, bytes.length.toLong, sha, sourceRef), true)

object ObjectKeys:
  private val allowed = "[A-Za-z0-9._=:#/-]+".r

  def requireValid(key: String): Unit =
    Fail.unless(
      key.nonEmpty && Text.utf8Size(key) <= Limits.S3.MaxKeyBytes,
      ErrorCode.InvalidKey,
      s"Key is empty or longer than ${Limits.S3.MaxKeyBytes} bytes"
    )
    Fail.unless(
      allowed.matches(key) && !key.startsWith("/"),
      ErrorCode.InvalidKey,
      s"Key has characters outside the allowed set: $key"
    )
    Fail.unless(
      !key.endsWith("/") &&
        !key
          .split("/", -1)
          .exists(part => part.isEmpty || part == ".." || part == "."),
      ErrorCode.InvalidKey,
      s"Key has an empty or parent segment: $key"
    )

  /** `<prefix>/<dataset>/v=<version>/run=<run segment>/<relative>`. */
  def versioned(
      prefix: S3Prefix,
      dataset: String,
      version: String,
      runId: String,
      relative: String
  ): String =
    val run = RunDirectory.segment(runId)
    val key = s"${prefix.dir}/$dataset/v=$version/run=$run/$relative"
    requireValid(key)
    key

/** In-memory fake. */
final class InMemoryObjectStore extends ObjectStore:
  private val objects = TrieMap.empty[String, (Array[Byte], ObjectMeta)]

  protected def write(
      key: String,
      bytes: Array[Byte],
      sourceRef: String
  ): Unit =
    val meta =
      ObjectMeta(key, bytes.length.toLong, Hashing.sha256(bytes), sourceRef)
    objects.put(key, (bytes.clone(), meta))

  def head(key: String): Option[ObjectMeta] = objects.get(key).map(_._2)

  def get(key: String): Option[Array[Byte]] = objects.get(key).map(_._1.clone())

  def list(prefix: String): Vector[ObjectMeta] =
    objects.values
      .map(_._2)
      .filter(_.key.startsWith(prefix))
      .toVector
      .sortBy(_.key)

/** Local filesystem fake: objects are files under `root`, with a sidecar
  * `<key>.meta.json` holding the source reference.
  */
final class LocalFsObjectStore(root: Path) extends ObjectStore:
  private val base = root.toAbsolutePath.normalize()
  private val sidecar = ".meta.json"

  private def locate(key: String): Path =
    ObjectKeys.requireValid(key)
    val resolved = base.resolve(key).normalize()
    Fail.unless(
      resolved.startsWith(base),
      ErrorCode.InvalidKey,
      s"Key escapes the store root: $key"
    )
    resolved

  private def atomicWrite(target: Path, bytes: Array[Byte]): Unit =
    Files.createDirectories(target.getParent)
    val pending = Files.createTempFile(target.getParent, ".put-", ".tmp")
    try
      Files.write(pending, bytes)
      Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE)
    finally Files.deleteIfExists(pending)

  protected def write(
      key: String,
      bytes: Array[Byte],
      sourceRef: String
  ): Unit =
    val target = locate(key)
    val meta = ujson.Obj("sourceRef" -> sourceRef)
    atomicWrite(target, bytes)
    atomicWrite(
      Path.of(target.toString + sidecar),
      ujson.write(meta).getBytes(UTF_8)
    )

  private def metaOf(path: Path, key: String): ObjectMeta =
    val bytes = Files.readAllBytes(path)
    val side = Path.of(path.toString + sidecar)
    val ref =
      if Files.isRegularFile(side) then
        ujson.read(Files.readAllBytes(side))("sourceRef").str
      else ""
    ObjectMeta(key, bytes.length.toLong, Hashing.sha256(bytes), ref)

  def head(key: String): Option[ObjectMeta] =
    val path = locate(key)
    if Files.isRegularFile(path) then Some(metaOf(path, key)) else None

  def get(key: String): Option[Array[Byte]] =
    val path = locate(key)
    if Files.isRegularFile(path) then Some(Files.readAllBytes(path)) else None

  def list(prefix: String): Vector[ObjectMeta] =
    if !Files.isDirectory(base) then Vector.empty
    else
      val stream = Files.walk(base)
      try
        val files = stream.iterator.asScala.toVector
        val keys = files
          .filter(Files.isRegularFile(_))
          .filterNot(_.getFileName.toString.endsWith(sidecar))
          .filterNot(_.getFileName.toString.startsWith(".put-"))
          .map(path => base.relativize(path).toString)
          .filter(_.startsWith(prefix))
          .sorted
        keys.flatMap(head)
      finally stream.close()

final case class S3ObjectEntry(
    key: String,
    relativePath: String,
    bytes: Long,
    sha256: String,
    sourceRef: String
) derives ReadWriter

/** The manifest of one run's objects in S3. Deterministic: no timestamp, so a
  * reload writes identical bytes.
  */
final case class S3RunManifest(
    manifestVersion: Int,
    runId: String,
    version: String,
    objects: Vector[S3ObjectEntry],
    provenance: Provenance
) derives ReadWriter

final case class S3LoadReport(
    created: Int,
    unchanged: Int,
    bytes: Long,
    manifestKey: String
)

/** Copies a complete Spark run into the S3 prefixes. Refuses a directory
  * without `_SUCCESS` (RunDirectory.open), verifies every file against the
  * SHA-256 recorded by Spark, and uses immutable versioned keys, so loading
  * twice leaves the same state.
  */
object S3RunLoader:
  val ManifestVersion = 1

  def prefixFor(table: String): S3Prefix =
    if table.startsWith("nodes-") || table.startsWith("edges-") then
      S3Prefix.Graph
    else if table == "semantic-summaries" then S3Prefix.SemanticContext
    else S3Prefix.Curated

  private val sideFiles = Vector(
    ("run-manifest.json", S3Prefix.Reports),
    ("summary.txt", S3Prefix.Reports),
    ("stage-metrics.json", S3Prefix.Logs)
  )

  def load(
      run: RunDirectory,
      store: ObjectStore,
      version: String,
      bounds: LoadBounds
  ): S3LoadReport =
    val manifest = run.loadManifest("s3")
    val tables = manifest("tables").arr.toVector
    val planned = tables.flatMap { table =>
      val name = table("table").str
      table("files").arr.toVector.map(file => (name, file))
    }
    Fail.unless(
      planned.size <= bounds.maxFiles,
      ErrorCode.BoundExceeded,
      s"${planned.size} files, bound is ${bounds.maxFiles}"
    )
    // The bound uses the larger of the declared and the actual size, so a
    // manifest that declares less than the real file cannot pass it.
    val total = planned.map { case (_, file) =>
      val declared = file("bytes").num.toLong
      val path = run.file(file("path").str)
      val actual = if Files.isRegularFile(path) then Files.size(path) else 0L
      math.max(declared, actual)
    }.sum
    Fail.unless(
      total <= bounds.maxBytes,
      ErrorCode.BoundExceeded,
      s"$total bytes, bound is ${bounds.maxBytes}"
    )
    val dataEntries = planned.map { case (name, file) =>
      val relative = file("path").str
      val content = run.bytes(relative)
      val sha = Hashing.sha256(content)
      Fail.unless(
        sha == file("sha256").str,
        ErrorCode.ChecksumMismatch,
        s"$relative does not match the SHA-256 in load-manifests/s3.json"
      )
      val inner = relative.stripPrefix(s"tables/$name/")
      val key = ObjectKeys.versioned(
        prefixFor(name),
        name,
        version,
        run.runId,
        inner
      )
      (key, relative, content)
    }
    val sideEntries =
      sideFiles.filter(f => run.exists(f._1)).map { case (relative, prefix) =>
        val key = ObjectKeys.versioned(
          prefix,
          "spark-run",
          version,
          run.runId,
          relative
        )
        (key, relative, run.bytes(relative))
      }
    val results = (dataEntries ++ sideEntries).map {
      case (key, relative, content) =>
        val ref = s"spark-run:${run.runId}/$relative"
        val put = store.putImmutable(key, content, ref)
        (
          put,
          S3ObjectEntry(key, relative, put.meta.bytes, put.meta.sha256, ref)
        )
    }
    val entries = results.map(_._2).sortBy(_.key)
    val record = StorageProvenance.forRun(run.runId, "s3-run-loader", run.runId)
    val s3Manifest =
      S3RunManifest(ManifestVersion, run.runId, version, entries, record)
    val manifestKey = ObjectKeys.versioned(
      S3Prefix.Reports,
      "s3-run-manifest",
      version,
      run.runId,
      "manifest.json"
    )
    val encoded = upickle.default.write(s3Manifest, indent = 2).getBytes(UTF_8)
    val manifestPut =
      store.putImmutable(manifestKey, encoded, s"spark-run:${run.runId}")
    val all = results.map(_._1) :+ manifestPut
    S3LoadReport(
      created = all.count(_.created),
      unchanged = all.count(!_.created),
      bytes = all.map(_.meta.bytes).sum,
      manifestKey = manifestKey
    )

object S3RunManifest:
  def read(store: ObjectStore, key: String): S3RunManifest =
    val bytes = store.get(key).getOrElse(Fail(ErrorCode.InvalidKey, key))
    upickle.default.read[S3RunManifest](new String(bytes, UTF_8))
