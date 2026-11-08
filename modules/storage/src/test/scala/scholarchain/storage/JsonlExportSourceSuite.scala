package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

class JsonlExportSourceSuite extends munit.FunSuite:
  import Fixtures.*

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private val summaries = Vector(
    summaryRow("researcher-profile", "p1", "Ada works on graphs"),
    summaryRow("community", "c1", "A community of graph researchers"),
    summaryRow("work", "w1", "A paper about graphs"),
    summaryRow("collaboration-path", "x1", "A path between Ada and Bo"),
    summaryRow("researcher-profile", "p2", "Bo studies proteins")
  )

  private def exported() =
    ExportFixtures.write(
      ExportFixtures.newDir(),
      Map(
        "semantic-summaries" -> summaries,
        "nodes-work" -> graphTables()("nodes-work"),
        "researcher-metrics" -> Vector(metricsRow(r1, "2000-2009", 2000, 2009))
      )
    )

  test("a complete export reads back row for row, with its run facts") {
    val source = JsonlExportSource.open(exported())
    assertEquals(
      source.tableNames,
      Set("semantic-summaries", "nodes-work", "researcher-metrics")
    )
    assertEquals(source.rows("semantic-summaries"), summaries)
    assertEquals(source.rows("nodes-work"), graphTables()("nodes-work"))
    assertEquals(source.sourceRun, SourceRun(runId, "spark-analytics/1.1.0"))
    assertEquals(source.exportVersion, "spark-jsonl-export/1.0.0")
    assertEquals(source.manifestSha256.length, 64)
    assertEquals(
      Rows.provenance(source.rows("semantic-summaries").head).runId,
      runId
    )
  }

  test("an empty table is a file with no rows and reads as no rows") {
    val dir = ExportFixtures.write(
      ExportFixtures.newDir(),
      Map("semantic-summaries" -> Vector.empty)
    )
    assertEquals(
      JsonlExportSource.open(dir).rows("semantic-summaries"),
      Vector.empty
    )
  }

  test("an export without _SUCCESS is refused") {
    val dir = exported()
    Files.delete(dir.resolve("_SUCCESS"))
    assertEquals(
      code(JsonlExportSource.open(dir)),
      ErrorCode.MissingSuccessMarker
    )
    assertEquals(
      code(JsonlExportSource.open(dir.resolve("nowhere"))),
      ErrorCode.MissingSuccessMarker
    )
  }

  test("a changed file fails the SHA-256 check, a shorter one the size check") {
    val dir = exported()
    val part = dir.resolve("semantic-summaries/part-00000.jsonl")
    val bytes = Files.readAllBytes(part)
    val flipped = bytes.clone()
    flipped(10) = (flipped(10) + 1).toByte
    Files.write(part, flipped)
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.ChecksumMismatch)
    Files.write(part, bytes.take(bytes.length - 3))
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.ChecksumMismatch)
  }

  test("a file edited after open is caught when its rows are read") {
    val dir = exported()
    val source = JsonlExportSource.open(dir)
    val part = dir.resolve("semantic-summaries/part-00001.jsonl")
    Files.writeString(part, "{}\n")
    assertEquals(
      code(source.rows("semantic-summaries")),
      ErrorCode.ChecksumMismatch
    )
  }

  test("a row count that disagrees with the manifest is refused") {
    val sum = exported()
    ExportFixtures.editManifest(sum) { m =>
      m("tables")("semantic-summaries")("files")(0)("rows") = 99
    }
    assertEquals(code(JsonlExportSource.open(sum)), ErrorCode.RowCountMismatch)

    val total = exported()
    ExportFixtures.editManifest(total) { m =>
      m("tables")("semantic-summaries")("rows") = 4
    }
    assertEquals(
      code(JsonlExportSource.open(total)),
      ErrorCode.RowCountMismatch
    )

    // File and table agree with each other but not with the lines in the file.
    val lines = exported()
    ExportFixtures.editManifest(lines) { m =>
      m("tables")("nodes-work")("files")(0)("rows") = 5
      m("tables")("nodes-work")("rows") = 5
    }
    assertEquals(
      code(JsonlExportSource.open(lines)),
      ErrorCode.RowCountMismatch
    )
  }

  test("a bad manifest is refused by name") {
    val missing = exported()
    Files.delete(missing.resolve(JsonlExportSource.ManifestName))
    assertEquals(
      code(JsonlExportSource.open(missing)),
      ErrorCode.MalformedManifest
    )

    val wrongType = exported()
    ExportFixtures.editManifest(wrongType)(m => m("manifestType") = "other/1")
    assertEquals(
      code(JsonlExportSource.open(wrongType)),
      ErrorCode.MalformedManifest
    )

    val notJson = exported()
    Files.writeString(notJson.resolve(JsonlExportSource.ManifestName), "{oops")
    assertEquals(
      code(JsonlExportSource.open(notJson)),
      ErrorCode.MalformedManifest
    )

    val noSuccessSeen = exported()
    ExportFixtures.editManifest(noSuccessSeen)(m =>
      m("sourceSuccessSeen") = false
    )
    assertEquals(
      code(JsonlExportSource.open(noSuccessSeen)),
      ErrorCode.MalformedManifest
    )

    val missingFile = exported()
    Files.delete(missingFile.resolve("nodes-work/part-00000.jsonl"))
    assertEquals(
      code(JsonlExportSource.open(missingFile)),
      ErrorCode.MalformedManifest
    )
  }

  test("a manifest path cannot leave the export directory") {
    val dir = exported()
    ExportFixtures.editManifest(dir) { m =>
      m("tables")("nodes-work")("files")(0)("path") = "../outside.jsonl"
    }
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.InvalidKey)
  }

  test("bounds on rows, files and bytes fail the open") {
    val dir = exported()
    assertEquals(
      code(JsonlExportSource.open(dir, LoadBounds(2, 100, 1L << 30))),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(JsonlExportSource.open(dir, LoadBounds(100, 3, 1L << 30))),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(JsonlExportSource.open(dir, LoadBounds(100, 100, 10))),
      ErrorCode.BoundExceeded
    )
    JsonlExportSource.open(dir, LoadBounds(100, 100, 1L << 30))
  }

  test("a line that is not a JSON object is a malformed row") {
    val dir = exported()
    val part = dir.resolve("nodes-work/part-00000.jsonl")
    val bad = "not json\n[1]\n".getBytes(UTF_8)
    Files.write(part, bad)
    ExportFixtures.editManifest(dir) { m =>
      val f = m("tables")("nodes-work")("files")(0)
      f("bytes") = bad.length
      f("sha256") = Hashing.sha256(bad)
    }
    val source = JsonlExportSource.open(dir)
    assertEquals(code(source.rows("nodes-work")), ErrorCode.MalformedRow)
  }

  test("a symlinked part file is refused even when its SHA-256 matches") {
    val dir = exported()
    val part = dir.resolve("nodes-work/part-00000.jsonl")
    val outside = Files.createTempFile("outside-part-", ".jsonl")
    Files.write(outside, Files.readAllBytes(part))
    Files.delete(part)
    Files.createSymbolicLink(part, outside)
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.InvalidKey)
  }

  test("a part file reached through a symlinked table directory is refused") {
    val dir = exported()
    val real = dir.resolve("nodes-work")
    val elsewhere = Files.createTempDirectory("outside-table-")
    Files.list(real).forEach { file =>
      Files.move(file, elsewhere.resolve(file.getFileName))
    }
    Files.delete(real)
    Files.createSymbolicLink(real, elsewhere)
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.InvalidKey)
  }

  test("a part file listed under another table's directory is refused") {
    val dir = exported()
    val bytes = Files.readAllBytes(dir.resolve("nodes-work/part-00000.jsonl"))
    Files.write(dir.resolve("semantic-summaries/stray.jsonl"), bytes)
    ExportFixtures.editManifest(dir) { m =>
      m("tables")("nodes-work")("files")(0)("path") =
        "semantic-summaries/stray.jsonl"
    }
    assertEquals(code(JsonlExportSource.open(dir)), ErrorCode.InvalidKey)

    val atRoot = exported()
    Files.write(atRoot.resolve("stray.jsonl"), bytes)
    ExportFixtures.editManifest(atRoot) { m =>
      m("tables")("nodes-work")("files")(0)("path") = "stray.jsonl"
    }
    assertEquals(code(JsonlExportSource.open(atRoot)), ErrorCode.InvalidKey)
  }

  test("a symlink swapped in after open is refused when the rows are read") {
    val dir = exported()
    val source = JsonlExportSource.open(dir)
    val part = dir.resolve("nodes-work/part-00000.jsonl")
    val outside = Files.createTempFile("outside-part-", ".jsonl")
    Files.write(outside, Files.readAllBytes(part))
    Files.delete(part)
    Files.createSymbolicLink(part, outside)
    assertEquals(code(source.rows("nodes-work")), ErrorCode.InvalidKey)
  }

  test("an unknown table is named, not guessed") {
    assertEquals(
      code(JsonlExportSource.open(exported()).rows("nodes-nothing")),
      ErrorCode.MalformedManifest
    )
  }
