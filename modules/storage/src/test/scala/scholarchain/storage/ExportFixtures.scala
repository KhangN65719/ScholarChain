package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** Writes a hand-built JSON-lines export in the shape described in
  * docs/agent-runs/phase-08/report-01-spark-export.md:
  * `<table>/part-NNNNN.jsonl`, `export-manifest.json`, then `_SUCCESS`.
  */
object ExportFixtures:
  val transformVersion = "spark-analytics/1.1.0"

  def newDir(): Path = Files.createTempDirectory("export-fixture-")

  def write(
      dir: Path,
      tables: Map[String, Vector[Row]],
      rowsPerFile: Int = 2,
      runId: String = Fixtures.runId
  ): Path =
    val entries = tables.toVector.sortBy(_._1).map { case (name, rows) =>
      val chunks =
        if rows.isEmpty then Vector(Vector.empty[Row])
        else rows.grouped(rowsPerFile).toVector
      Files.createDirectories(dir.resolve(name))
      val files = chunks.zipWithIndex.map { case (chunk, index) =>
        val relative = f"$name/part-$index%05d.jsonl"
        val body = chunk
          .map(row => ujson.write(ujson.Obj.from(row)))
          .mkString("", "\n", if chunk.isEmpty then "" else "\n")
        val bytes = body.getBytes(UTF_8)
        Files.write(dir.resolve(relative), bytes)
        ujson.Obj(
          "path" -> relative,
          "rows" -> chunk.size,
          "bytes" -> bytes.length,
          "sha256" -> Hashing.sha256(bytes)
        )
      }
      name -> ujson.Obj(
        "rows" -> rows.size,
        "idColumns" -> ujson.Arr(),
        "columns" -> ujson.Arr(),
        "sourceFiles" -> ujson.Arr(),
        "files" -> ujson.Arr.from(files)
      )
    }
    val manifest = ujson.Obj(
      "manifestType" -> JsonlExportSource.ManifestType,
      "exportVersion" -> "spark-jsonl-export/1.0.0",
      "sourceRunId" -> runId,
      "sourceDirectory" -> s"run=${RunDirectory.segment(runId)}",
      "sourceSuccessSeen" -> true,
      "sourceRunManifestSha256" -> Hashing.sha256("run-manifest"),
      "transformVersion" -> transformVersion,
      "rowsPerFile" -> rowsPerFile,
      "tables" -> ujson.Obj.from(entries)
    )
    Files.writeString(
      dir.resolve(JsonlExportSource.ManifestName),
      ujson.write(manifest, indent = 2) + "\n"
    )
    Files.write(dir.resolve("_SUCCESS"), Array.emptyByteArray)
    dir

  def editManifest(dir: Path)(change: ujson.Value => Unit): Unit =
    val path = dir.resolve(JsonlExportSource.ManifestName)
    val manifest = ujson.read(Files.readString(path))
    change(manifest)
    Files.writeString(path, ujson.write(manifest, indent = 2))
