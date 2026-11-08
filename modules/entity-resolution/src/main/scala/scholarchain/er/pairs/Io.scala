package scholarchain.er.pairs

import java.io.{BufferedReader, InputStream}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{
  Files,
  Path,
  Paths,
  StandardCopyOption,
  StandardOpenOption
}
import java.security.MessageDigest
import java.util.HexFormat
import scala.jdk.CollectionConverters.*

object Hashing:
  def sha256Hex(text: String): String =
    HexFormat.of().formatHex(sha256().digest(text.getBytes(UTF_8)))

  private def sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

  private def digestStream(in: InputStream): String =
    val digest = sha256()
    val buffer = new Array[Byte](65536)
    var read = in.read(buffer)
    while read >= 0 do
      digest.update(buffer, 0, read)
      read = in.read(buffer)
    HexFormat.of().formatHex(digest.digest())

  def sha256File(path: Path): String =
    val in = Files.newInputStream(path)
    try digestStream(in)
    finally in.close()

  def sha256Bytes(bytes: Array[Byte]): String =
    HexFormat.of().formatHex(sha256().digest(bytes))

/** Input files that may be one file or a directory of part files.
  *
  * A directory is read depth first in name order. Names that start with `_` or
  * `.` (Hadoop's `_SUCCESS`, `_temporary` and `.part-r-00000.crc`) are skipped,
  * whether file or directory.
  */
object InputFiles:
  private def visible(path: Path): Boolean =
    val name = path.getFileName.toString
    !name.startsWith("_") && !name.startsWith(".")

  def files(path: Path): Vector[Path] =
    if Files.isDirectory(path) then
      val listing = Files.list(path)
      val children =
        try listing.iterator().asScala.filter(visible).toVector
        finally listing.close()
      children
        .sortBy(_.getFileName.toString)
        .flatMap { child =>
          if Files.isDirectory(child) then files(child)
          else if Files.isRegularFile(child) then Vector(child)
          else Vector.empty
        }
    else Vector(path)

  /** The files to read, failing when a directory holds none, so a path that
    * names the wrong level cannot silently produce an empty input.
    */
  def dataFiles(path: Path): Vector[Path] =
    val found = files(path)
    if found.isEmpty then
      throw new IllegalArgumentException(
        s"No data files under $path (names starting with '_' or '.' are skipped)"
      )
    found

  def lines(path: Path): Vector[String] =
    files(path).flatMap(file => Files.readAllLines(file, UTF_8).asScala)

  /** Every line with the file it came from, read lazily one file at a time. The
    * readers are closed when `use` returns or throws.
    */
  def withLines[A](path: Path)(use: Iterator[(String, Path)] => A): A =
    val open = scala.collection.mutable.ListBuffer.empty[BufferedReader]
    def linesOf(file: Path): Iterator[(String, Path)] =
      val reader = Files.newBufferedReader(file, UTF_8)
      open += reader
      Iterator
        .continually(reader.readLine())
        .takeWhile(_ != null)
        .map(line => (line, file))
    try use(dataFiles(path).iterator.flatMap(linesOf))
    finally open.foreach(_.close())

  /** The digest of a file, or of a directory's part paths and digests. */
  def sha256(path: Path): String =
    if Files.isDirectory(path) then
      Hashing.sha256Hex(
        files(path)
          .map(file => s"${path.relativize(file)}:${Hashing.sha256File(file)}")
          .mkString("\n")
      )
    else Hashing.sha256File(path)

/** Where a tool may write. Review files, pairs and exports hold data from the
  * run, so they live outside the repository and outside any `target` directory,
  * and an export never writes into the input it read.
  */
object Locations:
  private def normal(path: Path): Path = path.toAbsolutePath.normalize

  /** The path with symbolic links resolved. A path that does not exist yet is
    * resolved through its nearest existing ancestor, so a link anywhere above
    * it is followed and the rest is appended unchanged.
    */
  def real(path: Path): Path =
    val start = normal(path)
    val existing = Iterator
      .iterate(Option(start))(_.flatMap(p => Option(p.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(p => Files.exists(p))
    existing
      .map { found =>
        try found.toRealPath().resolve(found.relativize(start)).normalize
        catch case _: java.io.IOException => start
      }
      .getOrElse(start)

  /** The nearest directory at or above `from` that holds a `build.sbt`. */
  def repoRoot(from: Path): Option[Path] =
    Iterator
      .iterate(Option(normal(from)))(_.flatMap(dir => Option(dir.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(dir => Files.exists(dir.resolve("build.sbt")))

  /** Why `path` may not be written, or None when it may. */
  def violation(
      path: Path,
      workingDirectory: Path,
      inputs: Vector[Path] = Vector.empty
  ): Option[String] =
    val target = normal(path)
    val resolved = real(path)
    val targets = Vector(target, resolved).distinct
    val roots = Vector(
      repoRoot(workingDirectory),
      repoRoot(real(workingDirectory)),
      repoRoot(target),
      repoRoot(resolved)
    ).flatten.flatMap(root => Vector(root, real(root))).distinct
    val inRepo = roots.find(root => targets.exists(_.startsWith(root)))
    val inTarget = targets.exists(
      _.iterator().asScala.exists(_.toString == "target")
    )
    val inInput = inputs
      .flatMap(input => Vector(normal(input), real(input)))
      .distinct
      .find(input => targets.exists(_.startsWith(input)))
    inRepo
      .map(root => s"$target is inside the repository $root")
      .orElse(
        Option.when(inTarget)(s"$target is under a target directory")
      )
      .orElse(inInput.map(dir => s"$target is inside the input $dir"))

  def check(
      path: Path,
      workingDirectory: Path = Paths.get(""),
      inputs: Vector[Path] = Vector.empty
  ): Either[String, Path] =
    violation(path, workingDirectory, inputs).toLeft(path)

/** Writes a file so that a reader sees the old complete file or the new
  * complete file, never a partial one: the content goes to a temporary file in
  * the same directory, is forced to disk, then replaces the target by an atomic
  * move. A failure while producing the lines leaves the target untouched.
  */
object AtomicFile:
  def writeLines(path: Path, lines: Iterable[String]): Unit =
    val target = path.toAbsolutePath
    val directory = target.getParent
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, ".atomic-", ".tmp")
    try
      val channel = FileChannel.open(temporary, StandardOpenOption.WRITE)
      try
        lines.foreach { line =>
          val bytes = ByteBuffer.wrap((line + "\n").getBytes(UTF_8))
          while bytes.hasRemaining do channel.write(bytes)
        }
        channel.force(true)
      finally channel.close()
      Files.move(
        temporary,
        target,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    finally Files.deleteIfExists(temporary)

/** `--name value` arguments. A name without a value, or a repeated name, is an
  * error so a typo is never silently ignored.
  */
object Cli:
  def parse(args: Seq[String]): Either[String, Map[String, String]] =
    def loop(
        rest: List[String],
        found: Map[String, String]
    ): Either[String, Map[String, String]] =
      rest match
        case Nil => Right(found)
        case name :: value :: tail
            if name.startsWith("--") && !value.startsWith("--") =>
          if found.contains(name) then Left(s"$name given twice")
          else loop(tail, found + (name -> value))
        case name :: _ => Left(s"Expected '--name value' but found '$name'")
    loop(args.toList, Map.empty)

  def unknown(
      found: Map[String, String],
      allowed: Set[String]
  ): Option[String] =
    found.keySet.diff(allowed).toVector.sorted.headOption
