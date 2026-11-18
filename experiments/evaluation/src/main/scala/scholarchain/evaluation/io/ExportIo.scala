package scholarchain.evaluation.io

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

object Digests:
  def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString

  def sha256(text: String): String =
    hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)))

  def sha256File(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(path)
    try
      val buffer = new Array[Byte](65536)
      var read = in.read(buffer)
      while read >= 0 do
        digest.update(buffer, 0, read)
        read = in.read(buffer)
    finally in.close()
    hex(digest.digest())

/** One line of a JSONL table: its file (relative to the export), 1-based line
  * number, raw text and, on demand, the parsed value and the sha256 of the raw
  * text. The file and line are what a human uses to find the row again.
  */
final class JsonRow(val file: String, val line: Int, val raw: String):
  lazy val json: ujson.Value =
    try ujson.read(raw)
    catch
      case e: Exception =>
        throw new IllegalArgumentException(
          s"$file:$line is not valid JSON: ${e.getMessage}",
          e
        )

  lazy val sha: String = Digests.sha256(raw)

  def where: String = s"$file:$line"

/** Reading of the `JsonlExport` layout: `<root>/<table>/part-NNNNN.jsonl`. */
object ExportFiles:
  def partFiles(dir: Path): Vector[Path] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      val stream = Files.list(dir)
      try
        stream.iterator.asScala
          .filter(p => p.getFileName.toString.endsWith(".jsonl"))
          .toVector
          .sortBy(_.getFileName.toString)
      finally stream.close()

  def tables(root: Path): Vector[String] =
    if !Files.isDirectory(root) then Vector.empty
    else
      val stream = Files.list(root)
      try
        stream.iterator.asScala
          .filter(p => Files.isDirectory(p) && partFiles(p).nonEmpty)
          .map(_.getFileName.toString)
          .toVector
          .sorted
      finally stream.close()

  /** Calls `f` for every non-blank line of the table in file and line order and
    * returns the number of rows. A missing table has no rows.
    */
  def scan(root: Path, table: String)(f: JsonRow => Unit): Long =
    var count = 0L
    partFiles(root.resolve(table)).foreach { path =>
      val rel = s"$table/${path.getFileName}"
      val reader = Files.newBufferedReader(path, UTF_8)
      try
        var number = 0
        var text = reader.readLine()
        while text != null do
          number += 1
          if text.trim.nonEmpty then
            f(new JsonRow(rel, number, text))
            count += 1L
          text = reader.readLine()
      finally reader.close()
    }
    count

  def collect[A](root: Path, table: String)(
      f: JsonRow => Option[A]
  ): Vector[A] =
    val out = Vector.newBuilder[A]
    scan(root, table) { row => f(row).foreach(a => out += a) }
    out.result()

  def readJson(path: Path): ujson.Value =
    ujson.read(Files.readString(path, UTF_8))

/** Field access that treats an absent field and JSON null alike. */
object J:
  def field(v: ujson.Value, key: String): Option[ujson.Value] =
    v.objOpt.flatMap(_.get(key)).filter(_ != ujson.Null)

  def str(v: ujson.Value, key: String): Option[String] =
    field(v, key).flatMap(_.strOpt)

  def num(v: ujson.Value, key: String): Option[Double] =
    field(v, key).flatMap(_.numOpt)

  def long(v: ujson.Value, key: String): Option[Long] =
    num(v, key).map(_.toLong)

  def int(v: ujson.Value, key: String): Option[Int] =
    num(v, key).map(_.toInt)

  def strings(v: ujson.Value, key: String): Vector[String] =
    field(v, key)
      .flatMap(_.arrOpt)
      .map(_.toVector.flatMap(_.strOpt))
      .getOrElse(Vector.empty)

  private def number(n: Double): String =
    if n == math.rint(n) then n.toLong.toString else n.toString

  /** A scalar as text, for building composite id keys; absent gives "". */
  def scalar(v: ujson.Value, key: String): String =
    field(v, key) match
      case None    => ""
      case Some(x) =>
        x.strOpt
          .orElse(x.numOpt.map(number))
          .orElse(x.boolOpt.map(_.toString))
          .getOrElse("")

  def runId(v: ujson.Value): Option[String] =
    field(v, "provenance").flatMap(p => str(p, "runId"))

  def sourceRecordId(v: ujson.Value): Option[String] =
    field(v, "provenance").flatMap(p => str(p, "sourceRecordId"))

/** Small constructors so output code never relies on implicit conversions. */
object Js:
  def s(v: String): ujson.Value = ujson.Str(v)
  def d(v: Double): ujson.Value = ujson.Num(v)
  def l(v: Long): ujson.Value = ujson.Num(v.toDouble)
  def b(v: Boolean): ujson.Value = ujson.Bool(v)

  def opt(v: Option[String]): ujson.Value =
    v.fold[ujson.Value](ujson.Null)(x => ujson.Str(x))

  def arr(vs: Iterable[ujson.Value]): ujson.Value = ujson.Arr.from(vs)

  def obj(fields: Iterable[(String, ujson.Value)]): ujson.Value =
    ujson.Obj.from(fields)

  def counts(m: Map[String, Long]): ujson.Value =
    obj(m.toVector.sortBy(_._1).map((k, v) => k -> l(v)))

  /** A ratio, or JSON null when the denominator is 0. */
  def rate(num: Long, den: Long): ujson.Value =
    if den == 0L then ujson.Null else d(num.toDouble / den.toDouble)

/** An append-only raw record file. It is created new (an existing file is an
  * error) and every `append` goes straight to the operating system, with no
  * user-space buffer, so a process killed after an append keeps that line. The
  * caller hashes the file after `close`, before any summary is written.
  */
final class RawAppender(dir: Path, name: String) extends AutoCloseable:
  val path: Path = dir.resolve(name)
  private val out: java.io.OutputStream =
    Files.newOutputStream(
      path,
      java.nio.file.StandardOpenOption.CREATE_NEW,
      java.nio.file.StandardOpenOption.WRITE
    )
  private var closed = false

  def append(line: String): Unit =
    out.write((line + "\n").getBytes(UTF_8))
    out.flush()

  def close(): Unit =
    if !closed then
      closed = true
      out.close()

/** Write-once run directories with a SHA256SUMS seal. */
object RunOutput:
  def stamp(): String =
    java.time.format.DateTimeFormatter
      .ofPattern("yyyyMMdd'T'HHmmss'Z'")
      .withZone(java.time.ZoneOffset.UTC)
      .format(java.time.Instant.now())

  def createRunDir(base: Path, runId: String): Path =
    val dir = base.resolve(runId)
    if Files.exists(dir) then
      throw new IllegalStateException(s"Run directory already exists: $dir")
    Files.createDirectories(dir)
    dir

  def write(dir: Path, name: String, text: String): Unit =
    val target = dir.resolve(name)
    if Files.exists(target) then
      throw new IllegalStateException(s"Refusing to overwrite $target")
    Files.writeString(target, text, UTF_8)

  /** The digest of the raw record file, written before any summary. */
  def writeRawDigest(dir: Path, name: String): Unit =
    write(
      dir,
      "RAW-SHA256SUMS",
      s"${Digests.sha256File(dir.resolve(name))}  $name\n"
    )

  /** Writes `SHA256SUMS` over every other regular file of the directory. */
  def seal(dir: Path): Unit =
    val stream = Files.list(dir)
    val names =
      try
        stream.iterator.asScala
          .filter(p => Files.isRegularFile(p))
          .map(_.getFileName.toString)
          .filter(_ != "SHA256SUMS")
          .toVector
          .sorted
      finally stream.close()
    val lines = names.map(n => s"${Digests.sha256File(dir.resolve(n))}  $n")
    Files.writeString(
      dir.resolve("SHA256SUMS"),
      lines.mkString("", "\n", "\n"),
      UTF_8
    )
