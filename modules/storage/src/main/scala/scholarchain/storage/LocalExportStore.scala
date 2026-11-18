package scholarchain.storage

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** A read-only [[ObjectStore]] over a flat local export directory: table
  * folders of `window=.../part-NNNNN.jsonl` files plus `export-manifest.json`.
  * Every file under `dir` is the object `keyPrefix + <relative path>`, so
  * `AwsReadModels.fromExport` reads it as it reads the data bucket.
  *
  * Writes are rejected (`UnsupportedOperationException`). A key with a `..`
  * segment, or one that resolves outside `dir` (including through a symbolic
  * link), is refused with `InvalidKey`. A key outside `keyPrefix`, or without a
  * file, is absent.
  *
  * An export that lacks tables the read models need can be completed without
  * touching the directory: `manifestOverride` replaces the bytes served for
  * `export-manifest.json`, and each `emptyObjects` entry (a relative path) is
  * served as a listed zero-byte object.
  */
final class LocalExportStore(
    dir: Path,
    keyPrefix: String,
    manifestOverride: Option[Array[Byte]] = None,
    emptyObjects: Vector[String] = Vector.empty
) extends ObjectStore:
  private val base = dir.toAbsolutePath.normalize()
  private val manifestName = JsonlExportSource.ManifestName

  // An entry that leaves the root (`..`, an absolute path) would be listed but
  // refused by `get`, so it is refused up front, as is an empty object that
  // names a real file (served empty by `get` but listed with its real size).
  emptyObjects.foreach { name =>
    val path = base.resolve(name).normalize()
    Fail.unless(
      name.nonEmpty && path.startsWith(base) && path != base,
      ErrorCode.InvalidKey,
      s"emptyObjects entry escapes the export root: $name"
    )
    Fail.unless(
      !name.startsWith("/"),
      ErrorCode.InvalidKey,
      s"emptyObjects entry is an absolute path: $name"
    )
    // Listed keys must be keys `get` serves, so `list` cannot throw on them.
    ObjectKeys.requireValid(keyPrefix + name)
    Fail.unless(
      !Files.isRegularFile(path) && !Files.isSymbolicLink(path),
      ErrorCode.InvalidKey,
      s"emptyObjects entry names an existing file: $name"
    )
  }

  protected def write(
      key: String,
      bytes: Array[Byte],
      sourceRef: String
  ): Unit =
    throw new UnsupportedOperationException(
      "LocalExportStore is read-only"
    )

  /** The path of a key under the root, or `None` when the key is not under the
    * key prefix. Refuses a key that escapes the root.
    */
  private def locate(key: String): Option[Path] =
    ObjectKeys.requireValid(key)
    if !key.startsWith(keyPrefix) then None
    else
      val relative = key.stripPrefix(keyPrefix)
      val resolved = base.resolve(relative).normalize()
      Fail.unless(
        relative.nonEmpty && resolved.startsWith(base) && resolved != base,
        ErrorCode.InvalidKey,
        s"Key escapes the export root: $key"
      )
      if Files.exists(resolved) then
        Fail.unless(
          resolved.toRealPath().startsWith(base.toRealPath()),
          ErrorCode.InvalidKey,
          s"Key escapes the export root through a link: $key"
        )
      Some(resolved)

  private def isEmptyObject(key: String): Boolean =
    emptyObjects.exists(name => keyPrefix + name == key)

  def get(key: String): Option[Array[Byte]] =
    val path = locate(key)
    if key == keyPrefix + manifestName && manifestOverride.isDefined then
      manifestOverride.map(_.clone())
    else if isEmptyObject(key) then Some(Array.emptyByteArray)
    else path.filter(Files.isRegularFile(_)).map(Files.readAllBytes)

  def head(key: String): Option[ObjectMeta] =
    get(key).map { bytes =>
      ObjectMeta(
        key,
        bytes.length.toLong,
        Hashing.sha256(bytes),
        s"local-export:$key"
      )
    }

  /** Sizes only: no file is read, and the overridden manifest reports its
    * override length.
    */
  override def listSizes(prefix: String): Vector[(String, Long)] =
    if !Files.isDirectory(base) then Vector.empty
    else
      val realBase = base.toRealPath()
      val stream = Files.walk(base)
      try
        val onDisk = stream.iterator.asScala.toVector
          .filter(Files.isRegularFile(_))
          .filter(_.toRealPath().startsWith(realBase))
          .map { file =>
            val rel = base.relativize(file).toString
            val size = manifestOverride match
              case Some(m) if rel == manifestName => m.length.toLong
              case _                              => Files.size(file)
            (keyPrefix + rel, size)
          }
        val onDiskKeys = onDisk.map(_._1).toSet
        val overridden = manifestOverride.toVector
          .map(m => (keyPrefix + manifestName, m.length.toLong))
        val extra = (overridden ++ emptyObjects.map(name =>
          (keyPrefix + name, 0L)
        )).filterNot(e => onDiskKeys.contains(e._1)).distinctBy(_._1)
        (onDisk ++ extra).filter(_._1.startsWith(prefix)).sortBy(_._1)
      finally stream.close()

  def list(prefix: String): Vector[ObjectMeta] =
    listSizes(prefix).flatMap { case (key, _) => head(key) }
