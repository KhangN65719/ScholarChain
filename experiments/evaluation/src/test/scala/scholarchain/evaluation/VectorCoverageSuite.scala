package scholarchain.evaluation

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.evaluation.benchmark.*
import scholarchain.evaluation.io.Digests

/** The vector coverage rebuild and its checks against the chunk set manifest,
  * on a tiny hand-built vector export.
  */
class VectorCoverageSuite extends FunSuite:
  private val salt = "test-salt"
  private val deleteCount = 3
  private val part = "semantic-summaries/part-00000.jsonl"

  private final case class Row(
      id: String,
      kind: String,
      subject: String,
      window: Option[String]
  ):
    def json: String =
      val w = window.fold("")(x => s""","window":"$x"""")
      s"""{"summaryId":"$id","summaryType":"$kind","subjectId":"$subject"$w}"""

  private val rows: Vector[Row] = (1 to 12).toVector.map { n =>
    Row(
      s"summary:v1:$n",
      if n % 3 == 0 then "community" else "researcher-profile",
      s"researcher:v1:s${n % 5}",
      if n % 4 == 0 then None
      else Some(if n % 2 == 0 then "all" else "2015-2019")
    )
  }

  /** The deleted set by the manifest's own rule, computed independently. */
  private val deleted: Set[String] =
    rows
      .map(r => (Digests.sha256(s"$salt|${r.id}"), r.id))
      .sorted
      .take(deleteCount)
      .map(_._2)
      .toSet

  private def counts(f: Row => String): Map[String, Long] =
    rows
      .filter(r => deleted.contains(r.id))
      .groupBy(f)
      .map((k, v) => k -> v.size.toLong)

  private def windowOf(r: Row): String = r.window.getOrElse("not-windowed")

  private def writeVectorExport(): Path =
    val dir = Files.createTempDirectory("vc-s-")
    val table = Files.createDirectory(dir.resolve("semantic-summaries"))
    val text = rows.map(_.json).mkString("", "\n", "\n")
    Files.write(table.resolve("part-00000.jsonl"), text.getBytes(UTF_8))
    dir

  private def obj(m: Map[String, Long]): String =
    m.map((k, v) => s""""$k":$v""").mkString("{", ",", "}")

  private def writeManifest(
      vecDir: Path,
      fileSha: Option[String] = None,
      byWindow: Option[Map[String, Long]] = None,
      fileBytes: Option[Long] = None,
      fileRows: Option[Long] = None
  ): Path =
    val file = vecDir.resolve(part)
    val sha = fileSha.getOrElse(Digests.sha256File(file))
    val window = byWindow.getOrElse(counts(windowOf))
    val bytes = fileBytes.getOrElse(Files.size(file))
    val fileRowCount = fileRows.getOrElse(rows.size.toLong)
    val text =
      s"""{"composition":{"by_summary_type":${obj(counts(_.kind))},""" +
        s""""by_window":${obj(window)}},""" +
        s""""selection":{"count":$deleteCount,"id_field":"summaryId","salt":"$salt"},""" +
        s""""source":{"rows":${rows.size},"files":[{"path":"$part",""" +
        s""""bytes":$bytes,"rows":$fileRowCount,"sha256":"$sha"}]}}"""
    val path = Files.createTempFile("chunkset-", ".json")
    Files.write(path, text.getBytes(UTF_8))
    path

  test("a matching export passes every check against the manifest"):
    val vec = writeVectorExport()
    val d = VectorCoverage.fromExports(vec, writeManifest(vec)).describe
    assertEquals(d("sourceFilesMatchManifest").bool, true)
    assertEquals(d("vectorExportRowsMatchManifest").bool, true)
    assertEquals(d("deletedByTypeMatchesManifest").bool, true)
    assertEquals(d("deletedByWindowMatchesManifest").bool, true)
    assertEquals(d("deletedRebuilt").num.toInt, deleteCount)
    assertEquals(d("sourceFiles").arr.head("sha256Matches").bool, true)

  test("a file whose sha256 differs from the manifest is reported"):
    val vec = writeVectorExport()
    val manifest = writeManifest(vec, fileSha = Some("0" * 64))
    val d = VectorCoverage.fromExports(vec, manifest).describe
    assertEquals(d("sourceFilesMatchManifest").bool, false)
    assertEquals(d("sourceFiles").arr.head("sha256Matches").bool, false)
    assertEquals(d("sourceFiles").arr.head("expectedSha256").str, "0" * 64)

  private def flagsOf(c: VectorCoverage): Map[String, Boolean] = c.checks.toMap

  test("a matching export has every check flag true"):
    val vec = writeVectorExport()
    val c = VectorCoverage.fromExports(vec, writeManifest(vec))
    assertEquals(
      flagsOf(c),
      Map(
        "sha256" -> true,
        "bytes" -> true,
        "rows" -> true,
        "by_summary_type" -> true,
        "by_window" -> true
      )
    )
    assertEquals(c.describe("sourceFiles").arr.head("rowsMatch").bool, true)
    assertEquals(c.describe("sourceFiles").arr.head("bytesMatch").bool, true)

  test("a byte size that differs from the manifest is a mismatch"):
    val vec = writeVectorExport()
    val manifest = writeManifest(vec, fileBytes = Some(1L))
    val c = VectorCoverage.fromExports(vec, manifest)
    val d = c.describe
    assertEquals(d("sourceFiles").arr.head("bytesMatch").bool, false)
    assertEquals(d("sourceFiles").arr.head("sha256Matches").bool, true)
    assertEquals(d("sourceFilesBytesMatch").bool, false)
    assertEquals(d("sourceFilesMatchManifest").bool, false)
    assertEquals(flagsOf(c)("bytes"), false)
    assertEquals(flagsOf(c)("sha256"), true)

  test("a row count of a file that differs from the manifest is a mismatch"):
    val vec = writeVectorExport()
    val manifest = writeManifest(vec, fileRows = Some(99L))
    val c = VectorCoverage.fromExports(vec, manifest)
    val d = c.describe
    assertEquals(d("sourceFiles").arr.head("rowsMatch").bool, false)
    assertEquals(d("sourceFiles").arr.head("actualRows").num.toLong, 12L)
    assertEquals(d("sourceFilesRowsMatch").bool, false)
    assertEquals(d("sourceFilesMatchManifest").bool, false)
    assertEquals(flagsOf(c)("rows"), false)
    assertEquals(flagsOf(c)("bytes"), true)

  test("the command-line lines print the flags and name a mismatch"):
    val vec = writeVectorExport()
    val good = VectorCoverage.fromExports(vec, writeManifest(vec))
    assertEquals(
      BenchmarkGen.coverageLines(good, Some(true)),
      Vector(
        "VECTOR_COVERAGE sha256=true bytes=true rows=true by_summary_type=true by_window=true summaryOverlap=true"
      )
    )
    val bad = VectorCoverage.fromExports(
      vec,
      writeManifest(vec, fileBytes = Some(1L), fileRows = Some(99L))
    )
    val lines = BenchmarkGen.coverageLines(bad, Some(false))
    assertEquals(lines.size, 2)
    assert(lines(0).contains("bytes=false") && lines(0).contains("rows=false"))
    assert(lines(0).contains("summaryOverlap=false"), lines(0))
    assertEquals(lines(1), "VECTOR_COVERAGE MISMATCH: bytes, rows")

  test(
    "a summary overlap of false alone is not a mismatch, no export is unchecked"
  ):
    val vec = writeVectorExport()
    val good = VectorCoverage.fromExports(vec, writeManifest(vec))
    assert(
      !BenchmarkGen
        .coverageLines(good, Some(false))
        .exists(_.contains("MISMATCH"))
    )
    val none = BenchmarkGen.coverageLines(VectorCoverage.unknown, None)
    assertEquals(none.size, 1)
    assert(none.head.contains("not checked"), none.head)

  test("summary overlap is complete only when every graph summary is shared"):
    val all = SubjectOverlap(2L, 5L, 2L, 3L, 2L)
    assert(BenchmarkGen.summaryOverlapComplete(4L, 4L, all))
    assert(!BenchmarkGen.summaryOverlapComplete(3L, 4L, all))
    assert(
      !BenchmarkGen.summaryOverlapComplete(4L, 4L, all.copy(shared = 1L))
    )
    assert(!BenchmarkGen.summaryOverlapComplete(0L, 0L, all))

  private def outDir(): Path =
    Files.createTempDirectory("bench-out-").resolve("out")

  private def written(out: Path): Boolean =
    Files.exists(out) && Files.list(out).findAny().isPresent

  private def args(
      graph: Path,
      out: Path,
      vec: Path,
      manifest: Path,
      more: Vector[String] = Vector.empty
  ): Vector[String] =
    Vector(
      graph.toString,
      out.toString,
      "--vector-export",
      vec.toString,
      "--chunkset-manifest",
      manifest.toString,
      "--generator-commit",
      "test",
      "--generator-dirty",
      "no"
    ) ++ more

  test("a coverage mismatch exits non-zero and writes no file"):
    val vec = writeVectorExport()
    val manifest = writeManifest(vec, fileBytes = Some(1L))
    val out = outDir()
    val code = BenchmarkGen.cli(
      args(Files.createTempDirectory("g-"), out, vec, manifest)
    )
    assertEquals(code, BenchmarkGen.ExitMismatch)
    assert(!written(out), "no file may be written on a mismatch")

  test("a mismatch with the override writes and records the failing checks"):
    val vec = writeVectorExport()
    val manifest = writeManifest(vec, fileBytes = Some(1L))
    val out = outDir()
    val code = BenchmarkGen.cli(
      args(
        Files.createTempDirectory("g-"),
        out,
        vec,
        manifest,
        Vector("--accept-coverage-mismatch", "yes")
      )
    )
    assertEquals(code, 0)
    val m =
      ujson.read(Files.readString(out.resolve("BENCHMARK-MANIFEST-v1.json")))
    val o = m("coverageMismatchOverride")
    assertEquals(o("accepted").bool, true)
    assertEquals(o("failedChecks").arr.map(_.str).toVector, Vector("bytes"))
    assertEquals(m("vectorCoverage")("checks")("bytes").bool, false)

  test("a matching run records no accepted override"):
    val vec = writeVectorExport()
    val out = outDir()
    val code = BenchmarkGen.cli(
      args(Files.createTempDirectory("g-"), out, vec, writeManifest(vec))
    )
    assertEquals(code, 0)
    val m =
      ujson.read(Files.readString(out.resolve("BENCHMARK-MANIFEST-v1.json")))
    assertEquals(m("coverageMismatchOverride")("accepted").bool, false)

  test("a given vector export that is missing is an error and writes nothing"):
    val vec = writeVectorExport()
    val out = outDir()
    val code = BenchmarkGen.cli(
      args(
        Files.createTempDirectory("g-"),
        out,
        vec.resolve("no-such-dir"),
        writeManifest(vec)
      )
    )
    assertEquals(code, BenchmarkGen.ExitInputs)
    assert(!written(out))

  private def raw(out: Path, more: Vector[String]): Vector[String] =
    Vector(Files.createTempDirectory("g-").toString, out.toString) ++ more

  test("an unknown option is refused, named, and nothing is written"):
    val vec = writeVectorExport()
    val out = outDir()
    val code = BenchmarkGen.cli(
      args(
        Files.createTempDirectory("g-"),
        out,
        vec,
        writeManifest(vec),
        Vector("--vector-exprot", "x")
      )
    )
    assertEquals(code, BenchmarkGen.ExitInputs)
    assert(!written(out))
    assertEquals(
      BenchmarkGen.optionError(Map("--vector-exprot" -> "x")),
      Some("unknown option --vector-exprot")
    )

  test("a run with neither --vector-export nor --no-vector-export is refused"):
    val out = outDir()
    val code = BenchmarkGen.cli(raw(out, Vector("--seed", "7")))
    assertEquals(code, BenchmarkGen.ExitInputs)
    assert(!written(out))
    assert(BenchmarkGen.optionError(Map("--seed" -> "7")).isDefined)

  test("--no-vector-export yes writes and the manifest records the mode"):
    val out = outDir()
    val code = BenchmarkGen.cli(
      raw(
        out,
        Vector(
          "--no-vector-export",
          "yes",
          "--generator-commit",
          "t",
          "--generator-dirty",
          "no"
        )
      )
    )
    assertEquals(code, 0)
    val m =
      ujson.read(Files.readString(out.resolve("BENCHMARK-MANIFEST-v1.json")))
    assertEquals(m("vectorCoverage")("mode").str, "none-by-flag")
    assertEquals(m("coverageMismatchOverride")("checked").bool, false)
    assertEquals(m("coverageMismatchOverride")("accepted").bool, false)

  test("a checked run records checked true and mode checked"):
    val vec = writeVectorExport()
    val out = outDir()
    val code = BenchmarkGen.cli(
      args(Files.createTempDirectory("g-"), out, vec, writeManifest(vec))
    )
    assertEquals(code, 0)
    val m =
      ujson.read(Files.readString(out.resolve("BENCHMARK-MANIFEST-v1.json")))
    assertEquals(m("vectorCoverage")("mode").str, "checked")
    assertEquals(m("coverageMismatchOverride")("checked").bool, true)

  test("--no-vector-export takes only yes and excludes --vector-export"):
    assert(
      BenchmarkGen.optionError(Map("--no-vector-export" -> "no")).isDefined
    )
    assert(
      BenchmarkGen
        .optionError(
          Map("--no-vector-export" -> "yes", "--vector-export" -> "d")
        )
        .isDefined
    )
    assertEquals(
      BenchmarkGen.optionError(Map("--no-vector-export" -> "yes")),
      None
    )

  test("--chunkset-manifest without --vector-export is refused"):
    val out = outDir()
    val code = BenchmarkGen.cli(
      raw(
        out,
        Vector("--no-vector-export", "yes", "--chunkset-manifest", "m.json")
      )
    )
    assertEquals(code, BenchmarkGen.ExitInputs)
    assert(!written(out))
    assertEquals(
      BenchmarkGen.optionError(Map("--chunkset-manifest" -> "m.json")),
      Some("--vector-export DIR is required, or --no-vector-export yes")
    )

  test("a coverage computation that throws exits 2 and writes nothing"):
    val vec = writeVectorExport()
    val broken = Files.createTempFile("chunkset-broken-", ".json")
    Files.write(broken, "{ not json".getBytes(UTF_8))
    val out = outDir()
    val code = BenchmarkGen.cli(
      args(Files.createTempDirectory("g-"), out, vec, broken)
    )
    assertEquals(code, BenchmarkGen.ExitInputs)
    assert(!written(out))

  test("a given vector export with a missing chunk set manifest is an error"):
    val vec = writeVectorExport()
    val out = outDir()
    val code = BenchmarkGen.cli(
      args(Files.createTempDirectory("g-"), out, vec, vec.resolve("none.json"))
    )
    assertEquals(code, BenchmarkGen.ExitInputs)
    assert(!written(out))

  test("an export changed after the manifest was written fails the sha256"):
    val vec = writeVectorExport()
    val manifest = writeManifest(vec)
    Files.writeString(
      vec.resolve(part),
      rows.map(_.json).mkString("", "\n", "\n") + rows.head.json + "\n",
      UTF_8
    )
    val d = VectorCoverage.fromExports(vec, manifest).describe
    assertEquals(d("sourceFilesMatchManifest").bool, false)
    assertEquals(d("vectorExportRowsMatchManifest").bool, false)

  test("a missing source file is reported as not found"):
    val vec = writeVectorExport()
    val manifest = writeManifest(vec)
    val before = VectorCoverage.fromExports(vec, manifest)
    assertEquals(before.describe("sourceFilesMatchManifest").bool, true)
    val moved = Files.createTempDirectory("vc-moved-")
    Files.createDirectory(moved.resolve("semantic-summaries"))
    // the same rows under another file name: the named file is not there
    Files.write(
      moved.resolve("semantic-summaries/part-00001.jsonl"),
      Files.readAllBytes(vec.resolve(part))
    )
    val d = VectorCoverage.fromExports(moved, manifest).describe
    assertEquals(d("sourceFiles").arr.head("found").bool, false)
    assertEquals(d("sourceFilesMatchManifest").bool, false)

  test("deleted rows by window that differ from by_window are reported"):
    val vec = writeVectorExport()
    val wrong = counts(windowOf).map((k, v) => k -> (v + 1))
    val d = VectorCoverage
      .fromExports(vec, writeManifest(vec, byWindow = Some(wrong)))
      .describe
    assertEquals(d("deletedByWindowMatchesManifest").bool, false)
    assertEquals(d("deletedByTypeMatchesManifest").bool, true)

  test("the status of a subject follows the rebuilt deleted set"):
    val vec = writeVectorExport()
    val cov = VectorCoverage.fromExports(vec, writeManifest(vec))
    rows.foreach { r =>
      val same =
        rows.filter(x => x.subject == r.subject && windowOf(x) == windowOf(r))
      val expected =
        if same.exists(x => !deleted.contains(x.id)) then
          VectorCoverage.Available
        else VectorCoverage.Deleted
      assertEquals(cov.status(r.subject, windowOf(r)), expected, r.id)
    }
    assertEquals(
      cov.status("researcher:v1:nobody", "all"),
      VectorCoverage.Absent
    )

  private def writeGraphExport(
      researchers: Vector[String],
      summarySubjects: Vector[String]
  ): Path =
    val dir = Files.createTempDirectory("vc-a-")
    def table(name: String, lines: Vector[String]): Unit =
      val d = Files.createDirectory(dir.resolve(name))
      Files.write(
        d.resolve("part-00000.jsonl"),
        lines.map(_ + "\n").mkString.getBytes(UTF_8)
      )
    table("nodes-researcher", researchers.map(id => s"""{"nodeId":"$id"}"""))
    table(
      "semantic-summaries",
      summarySubjects.zipWithIndex.map((s, i) =>
        s"""{"summaryId":"summary:v1:g$i","subjectId":"$s"}"""
      )
    )
    dir

  test("the subject overlap of the two exports is counted"):
    val vec = writeVectorExport()
    // vector subjects are researcher:v1:s0 .. s4
    val graph = writeGraphExport(
      Vector("researcher:v1:s0", "researcher:v1:s1", "researcher:v1:other"),
      Vector("researcher:v1:s0", "researcher:v1:s0", "researcher:v1:zzz")
    )
    val o = VectorCoverage.subjectIdOverlap(graph, vec)
    assertEquals(o.graphSummarySubjects, 2L)
    assertEquals(o.vectorSummarySubjects, 5L)
    assertEquals(o.shared, 1L)
    assertEquals(o.graphResearchers, 3L)
    assertEquals(o.graphResearchersWithVectorSubject, 2L)
    assertEquals(o.toJson("shared").num.toLong, 1L)
