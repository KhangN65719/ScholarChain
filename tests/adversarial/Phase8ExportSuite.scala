package scholarchain.tests.adversarial

import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.rag.IngestMain
import scholarchain.storage.*
import P8.*

/** Spec item 7: the JSON-lines export is refused when it is not exactly what
  * its manifest says. Each case builds its own export and breaks one thing.
  */
class Phase8ExportSuite extends FunSuite:
  private def fresh(rowsPerFile: Int = 3): Path =
    writeExport(
      tempDir("p8-export").resolve("export"),
      rowsPerFile = rowsPerFile
    )

  private def firstPart(dir: Path, table: String = "semantic-summaries") =
    dir.resolve(partPath(table, 0))

  test("an intact multi-part export opens and reads back every row"):
    val dir = fresh()
    val opened = JsonlExportSource.open(dir)
    assertEquals(opened.tableNames, World.tables.keySet)
    assertEquals(opened.sourceRun, SourceRun(runId, transform))
    val summaries = opened.rows("semantic-summaries")
    assertEquals(summaries.size, World.summaries.size)
    assertEquals(
      summaries.map(_("summaryId").str),
      World.summaries.map(_("summaryId").str).sorted
    )
    // Unicode survives, nulls stay null, arrays stay arrays.
    val zoe = summaries.find(_("summaryId").str == World.idOf("zoeW1")).get
    assert(zoe("summaryText").str.contains("Zoë 张伟"))
    val orphan = summaries.find(_("summaryId").str == World.idOf("workC")).get
    assertEquals(orphan("windowStart"), ujson.Null)
    assertEquals(orphan("evidenceWorkIds").arr.size, 1)
    val noYear =
      opened
        .rows("nodes-work")
        .find(r => r("canonicalWorkId").str == World.workC)
    assertEquals(noYear.get("year"), ujson.Null)

  test("a wrong sha256 in the manifest is refused"):
    val dir = fresh()
    val manifest = readManifest(dir)
    manifest("tables")("semantic-summaries")("files")(0)("sha256") = "0" * 64
    writeManifest(dir, manifest)
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.ChecksumMismatch)

  test("a wrong byte size in the manifest is refused"):
    val dir = fresh()
    val manifest = readManifest(dir)
    manifest("tables")("semantic-summaries")("files")(0)("bytes") = 5
    writeManifest(dir, manifest)
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.ChecksumMismatch)

  test("a wrong row count in a file entry is refused"):
    val dir = fresh()
    val manifest = readManifest(dir)
    manifest("tables")("semantic-summaries")("files")(0)("rows") = 99
    manifest("tables")("semantic-summaries")("rows") = 99 + manifest("tables")(
      "semantic-summaries"
    )("files")(1)("rows").num.toInt + manifest("tables")("semantic-summaries")(
      "files"
    )(2)("rows").num.toInt + manifest("tables")("semantic-summaries")("files")(
      3
    )("rows").num.toInt
    writeManifest(dir, manifest)
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.RowCountMismatch)

  test("a table total that disagrees with its files is refused"):
    val dir = fresh()
    val manifest = readManifest(dir)
    manifest("tables")("nodes-work")("rows") = 1000
    writeManifest(dir, manifest)
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.RowCountMismatch)

  test("a file whose row count differs from its entry is refused"):
    val dir = fresh()
    // Two lines on disk, the entry says three, and the hash is consistent.
    val lines = Vector(
      jsonLine(World.summaries(0)),
      jsonLine(World.summaries(1))
    )
    val bytes = partBytes(lines)
    Files.write(firstPart(dir), bytes)
    val manifest = readManifest(dir)
    val entry = manifest("tables")("semantic-summaries")("files")(0)
    entry("bytes") = bytes.length
    entry("sha256") = Hashing.sha256(bytes)
    writeManifest(dir, manifest)
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.RowCountMismatch)

  test("a missing _SUCCESS is refused, by open and by IngestMain"):
    val dir = fresh()
    Files.delete(dir.resolve("_SUCCESS"))
    assertEquals(
      code(JsonlExportSource.open(dir)),
      ErrorCode.MissingSuccessMarker
    )
    val out = tempDir("p8-out").resolve("out")
    val lines = Vector.newBuilder[String]
    val outcome =
      IngestMain.execute(IngestMain.Args(dir, out, None), lines += _)
    assert(!outcome.success)
    assert(lines.result().exists(_.contains("FAILED")))
    assert(!Files.exists(out.resolve(IngestMain.ReportName)))

  test("a missing directory or a file instead of a directory is refused"):
    val root = tempDir("p8-missing")
    assertEquals(
      code(JsonlExportSource.open(root.resolve("nope"))),
      ErrorCode.MissingSuccessMarker
    )
    val file = Files.write(root.resolve("a-file"), Array[Byte](1))
    assertEquals(
      code(JsonlExportSource.open(file)),
      ErrorCode.MissingSuccessMarker
    )

  test("an edited part file is refused, same length or not"):
    val sameLength = fresh()
    val original = Files.readAllBytes(firstPart(sameLength))
    val flipped = original.clone()
    flipped(40) = (flipped(40) ^ 0x01).toByte
    Files.write(firstPart(sameLength), flipped)
    assertEquals(
      code(JsonlExportSource.open(sameLength)),
      ErrorCode.ChecksumMismatch
    )
    val longer = fresh()
    Files.write(
      firstPart(longer),
      Files.readAllBytes(firstPart(longer)) ++ utf8("\n")
    )
    assertEquals(
      code(JsonlExportSource.open(longer)),
      ErrorCode.ChecksumMismatch
    )
    val shorter = fresh()
    val cut = Files.readAllBytes(firstPart(shorter)).dropRight(30)
    Files.write(firstPart(shorter), cut)
    assertEquals(
      code(JsonlExportSource.open(shorter)),
      ErrorCode.ChecksumMismatch
    )

  test("a part file edited after open is caught when the rows are read"):
    val dir = fresh()
    val opened = JsonlExportSource.open(dir)
    val edited = Files.readAllBytes(firstPart(dir))
    edited(60) = (edited(60) ^ 0x01).toByte
    Files.write(firstPart(dir), edited)
    assertEquals(
      code(opened.rows("semantic-summaries")),
      ErrorCode.ChecksumMismatch
    )
    Files.delete(firstPart(dir))
    assertEquals(
      code(opened.rows("semantic-summaries")),
      ErrorCode.MalformedManifest
    )

  test("a truncated line with a self-consistent manifest is a malformed row"):
    val dir = fresh()
    val good = World.summaries.take(3).map(jsonLine)
    val cutLine = good.last.take(good.last.length / 2)
    val bytes = partBytes(good.init :+ cutLine)
    replacePartConsistently(dir, "semantic-summaries", 0, bytes, 3)
    assertEquals(
      code(JsonlExportSource.open(dir).rows("semantic-summaries")),
      ErrorCode.MalformedRow
    )

  test("a line that is JSON but not an object is a malformed row"):
    Vector("[1,2,3]", "\"text\"", "42", "null", "").foreach { bad =>
      val dir = fresh()
      val bytes = partBytes(Vector(jsonLine(World.summaries.head), bad))
      replacePartConsistently(dir, "semantic-summaries", 0, bytes, 2)
      assertEquals(
        code(JsonlExportSource.open(dir).rows("semantic-summaries")),
        ErrorCode.MalformedRow,
        s"line '$bad'"
      )
    }

  test("a path that escapes the export directory is refused"):
    val root = tempDir("p8-escape")
    val dir = writeExport(root.resolve("export"))
    // A real, correctly hashed file outside the export that the manifest names.
    val outside = root.resolve("outside.jsonl")
    val bytes = partBytes(World.summaries.take(1).map(jsonLine))
    Files.write(outside, bytes)
    Vector("../outside.jsonl", "semantic-summaries/../../outside.jsonl")
      .foreach { escape =>
        val manifest = readManifest(dir)
        val files = manifest("tables")("semantic-summaries")("files")
        files(0) = ujson.Obj(
          "path" -> escape,
          "rows" -> 1,
          "bytes" -> bytes.length,
          "sha256" -> Hashing.sha256(bytes)
        )
        manifest("tables")("semantic-summaries")("rows") =
          1 + files(1)("rows").num + files(2)("rows").num + files(3)("rows").num
        val copy = tempDir("p8-escape-copy")
        val target = writeExport(copy.resolve("export"))
        Files.copy(
          root.resolve("outside.jsonl"),
          copy.resolve("outside.jsonl")
        )
        writeManifest(target, manifest)
        assertEquals(
          code(JsonlExportSource.open(target)),
          ErrorCode.InvalidKey,
          escape
        )
      }

  test("an absolute path in the manifest is refused"):
    val dir = fresh()
    val manifest = readManifest(dir)
    manifest("tables")("nodes-work")("files")(0)("path") = "/etc/hosts"
    writeManifest(dir, manifest)
    val failure = code(JsonlExportSource.open(dir))
    assert(
      failure == ErrorCode.InvalidKey || failure == ErrorCode.ChecksumMismatch,
      failure.toString
    )

  // L2: symbolic links and files under the wrong table ----------------------

  test("a part file that is a symlink to an outside file is refused"):
    val root = tempDir("p8-symlink")
    val dir = writeExport(root.resolve("export"))
    val part = firstPart(dir)
    // The outside file has exactly the bytes, and so the SHA, the manifest names.
    val outside = root.resolve("outside.jsonl")
    Files.write(outside, Files.readAllBytes(part))
    Files.delete(part)
    Files.createSymbolicLink(part, outside)
    assertEquals(
      Files.readAllBytes(part).toVector,
      Files.readAllBytes(outside).toVector
    )
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.InvalidKey)

  test("a symlink swapped in after open is refused when the rows are read"):
    val root = tempDir("p8-symlink-late")
    val dir = writeExport(root.resolve("export"))
    val opened = JsonlExportSource.open(dir)
    val part = firstPart(dir)
    val outside = root.resolve("outside.jsonl")
    Files.write(outside, Files.readAllBytes(part))
    Files.delete(part)
    Files.createSymbolicLink(part, outside)
    assertEquals(code(opened.rows("semantic-summaries")), ErrorCode.InvalidKey)

  test(
    "a table directory that is a symlink to an outside directory is refused"
  ):
    val root = tempDir("p8-symlink-dir")
    val dir = writeExport(root.resolve("export"))
    val real = dir.resolve("nodes-work")
    val moved = root.resolve("elsewhere")
    Files.move(real, moved)
    Files.createSymbolicLink(real, moved)
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.InvalidKey)

  test("a part file listed under the wrong table directory is refused"):
    // The file is real and its bytes, rows and SHA match its entry; only its
    // table is wrong.
    Vector(
      partPath("researcher-metrics", 0),
      "researcher-metrics/../researcher-metrics/part-00000.jsonl",
      "./researcher-metrics/part-00000.jsonl"
    ).foreach { listed =>
      val dir = fresh()
      val manifest = readManifest(dir)
      val foreign = manifest("tables")("researcher-metrics")("files")(0)
      val target = manifest("tables")("nodes-work")
      target("files") = ujson.Arr(
        ujson.Obj(
          "path" -> listed,
          "rows" -> foreign("rows"),
          "bytes" -> foreign("bytes"),
          "sha256" -> foreign("sha256")
        )
      )
      target("rows") = foreign("rows")
      writeManifest(dir, manifest)
      assertEquals(
        code(JsonlExportSource.open(dir)),
        ErrorCode.InvalidKey,
        listed
      )
    }

  test("a part file in the export root or another table is refused"):
    val dir = fresh()
    val rootFile = dir.resolve("loose.jsonl")
    val bytes = partBytes(World.nodesWork.take(1).map(jsonLine))
    Files.write(rootFile, bytes)
    val manifest = readManifest(dir)
    manifest("tables")("nodes-work")("files") = ujson.Arr(
      ujson.Obj(
        "path" -> "loose.jsonl",
        "rows" -> 1,
        "bytes" -> bytes.length,
        "sha256" -> Hashing.sha256(bytes)
      )
    )
    manifest("tables")("nodes-work")("rows") = 1
    writeManifest(dir, manifest)
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.InvalidKey)

  test("an unknown table is refused, not treated as empty"):
    val opened = JsonlExportSource.open(fresh())
    assertEquals(
      code(opened.rows("edges-secret")),
      ErrorCode.MalformedManifest
    )
    assert(!opened.tableNames.contains("edges-secret"))

  test("a part file listed but missing is refused"):
    val dir = fresh()
    Files.delete(dir.resolve(partPath("researcher-metrics", 0)))
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.MalformedManifest)

  test("a damaged manifest is refused"):
    val notJson = fresh()
    Files.write(notJson.resolve(JsonlExportSource.ManifestName), utf8("{nope"))
    assertEquals(
      code(JsonlExportSource.open(notJson)),
      ErrorCode.MalformedManifest
    )
    val missing = fresh()
    Files.delete(missing.resolve(JsonlExportSource.ManifestName))
    assertEquals(
      code(JsonlExportSource.open(missing)),
      ErrorCode.MalformedManifest
    )
    val mutations: Vector[(String, ujson.Value => Unit)] = Vector(
      "wrong manifestType" -> (m => m("manifestType") = "other/1"),
      "source _SUCCESS not seen" -> (m => m("sourceSuccessSeen") = false),
      "no run id" -> (m => m.obj.remove("sourceRunId")),
      "blank run id" -> (m => m("sourceRunId") = ""),
      "no transform" -> (m => m.obj.remove("transformVersion")),
      "no tables" -> (m => m.obj.remove("tables")),
      "tables not an object" -> (m => m("tables") = ujson.Arr()),
      "negative rows" -> (m => m("tables")("nodes-work")("rows") = -1),
      "fractional bytes" -> (m =>
        m("tables")("nodes-work")("files")(0)("bytes") = 1.5
      ),
      "no files list" -> (m => m("tables")("nodes-work").obj.remove("files"))
    )
    mutations.foreach { (name, change) =>
      val dir = fresh()
      val manifest = readManifest(dir)
      change(manifest)
      writeManifest(dir, manifest)
      val failure = code(JsonlExportSource.open(dir))
      assert(
        failure == ErrorCode.MalformedManifest ||
          failure == ErrorCode.RowCountMismatch ||
          failure == ErrorCode.ChecksumMismatch,
        s"$name gave $failure"
      )
    }

  test("the load bounds are enforced before reading"):
    val dir = fresh()
    assertEquals(
      code(JsonlExportSource.open(dir, LoadBounds(2, 100, 1L << 30))),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(JsonlExportSource.open(dir, LoadBounds(1000, 2, 1L << 30))),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(JsonlExportSource.open(dir, LoadBounds(1000, 100, 10))),
      ErrorCode.BoundExceeded
    )

  test("a NUL escape in a summary text fails the run with a counted reject"):
    val poisoned = World.summaries.map { r =>
      if r("summaryId").str == World.idOf("workA") then
        r.updated("summaryText", s("bad\u0000text"))
      else r
    }
    val dir = writeExport(
      tempDir("p8-nul").resolve("export"),
      World.tables.updated("semantic-summaries", poisoned)
    )
    val out = tempDir("p8-out").resolve("out")
    val lines = Vector.newBuilder[String]
    val outcome =
      IngestMain.execute(IngestMain.Args(dir, out, None), lines += _)
    assert(!outcome.success)
    val report =
      ujson.read(Files.readAllBytes(out.resolve(IngestMain.ReportName)))
    assertEquals(report("result").str, "FAILED")
    assertEquals(report("rejected").num.toInt, 1)
    assertEquals(
      report("vectorsTotal").num.toInt,
      World.summaries.size - 1,
      "the other rows are still ingested"
    )
    assert(lines.result().exists(_.contains("NullCharacter")))

  test("IngestMain refuses a damaged export and writes nothing"):
    val dir = fresh()
    val edited = Files.readAllBytes(firstPart(dir))
    edited(50) = (edited(50) ^ 0x01).toByte
    Files.write(firstPart(dir), edited)
    val out = tempDir("p8-out").resolve("out")
    val outcome = IngestMain.execute(IngestMain.Args(dir, out, None), _ => ())
    assert(!outcome.success)
    assert(outcome.reason.contains("ChecksumMismatch"), outcome.reason)
    assert(!Files.exists(out.resolve(IngestMain.ReportName)))
