package scholarchain.rag

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scholarchain.storage.*

class IngestMainSuite extends munit.FunSuite:
  import RagFixtures.*

  private def tmp(): Path = Files.createTempDirectory("rag-ingest-")

  private final class Captured:
    val lines = mutable.ArrayBuffer.empty[String]
    def out: String => Unit = lines += _
    def result: String =
      lines
        .find(_.startsWith(IngestMain.ResultPrefix))
        .getOrElse("no result line")

  private def run(
      exportDir: Path,
      outDir: Path,
      bound: Option[Int] = None
  ): (IngestMain.Outcome, Captured) =
    val captured = new Captured
    val outcome =
      IngestMain.execute(
        IngestMain.Args(exportDir, outDir, bound),
        captured.out
      )
    (outcome, captured)

  private def report(outDir: Path): ujson.Value =
    ujson.read(Files.readString(outDir.resolve(IngestMain.ReportName)))

  test("a tiny export ingests: counts, objects, samples and a result line") {
    val exportDir = writeExport(tmp())
    val outDir = tmp().resolve("out")
    val (outcome, captured) = run(exportDir, outDir)
    assert(outcome.success, captured.lines.mkString("\n"))
    assertEquals(captured.result, "PHASE8-INGEST RESULT: SUCCESS")
    val json = report(outDir)
    assertEquals(json("result").str, "SUCCESS")
    assertEquals(json("sourceRunId").str, runId)
    assertEquals(json("embeddingModel").str, "hash-embed-v1")
    assertEquals(json("dimension").num.toInt, 384)
    assertEquals(json("summaryRowsInExport").num.toInt, 12)
    assertEquals(json("summaryRowsIngested").num.toInt, 12)
    assertEquals(json("bound"), ujson.Null)
    assertEquals(json("vectorsTotal").num.toInt, 12)
    val perIndex = json("perIndex")
    assertEquals(
      Vector(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1"
      ).map(name => perIndex(name)("vectorsInIndex").num.toInt),
      Vector(4, 3, 3, 2, 0)
    )
    assertEquals(json("rejected").num.toInt, 0)
    assertEquals(json("failedBatches").num.toInt, 0)
    assertEquals(json("objects")("documents")("count").num.toInt, 12)
    assert(json("objects")("documents")("bytes").num > 0)
    assertEquals(json("objects")("embedding-work")("count").num.toInt, 1)
    assertEquals(json("objects")("all-objects")("count").num.toInt, 13)
    assertEquals(
      json("templateVersions")("work").str,
      "rag-template/work/1"
    )
    assert(Files.isRegularFile(outDir.resolve(IngestMain.RunName)))
    assert(Files.isDirectory(outDir.resolve(IngestMain.ObjectsDir)))
    val text = captured.lines.mkString("\n")
    assert(text.contains("LOCAL"))
    Vector(
      "researcher-profile-v1",
      "collaboration-community-v1",
      "paper-abstract-v1",
      "collaboration-path-v1"
    ).foreach { index =>
      assert(
        text.contains(s"SAMPLE $index: LOCAL latency"),
        s"no sample for $index"
      )
    }
    assertEquals(
      captured.lines.count(_.contains("nearest is the source row: true")),
      4
    )
    assertEquals(
      captured.lines.count(_.contains("fetchText: textHash verified")),
      4
    )
    assertEquals(captured.lines.count(_.startsWith("SAMPLE ")), 4)
  }

  test("a wide sample per index lists neighbours and finds the source row") {
    val exportDir = writeExport(tmp())
    val outDir = tmp().resolve("out")
    val (outcome, captured) = run(exportDir, outDir)
    assert(outcome.success, captured.lines.mkString("\n"))
    val wide = captured.lines.filter(_.startsWith("WIDE-SAMPLE "))
    assertEquals(wide.size, 4)
    wide.foreach { line =>
      assert(line.contains("LOCAL latency"), line)
      assert(line.contains("other candidates: true"), line)
      assert(!line.contains("source row rank: absent"), line)
    }
    val hitLines = captured.lines.filter(_.startsWith("  wide hit "))
    // researcher-profile 4 rows, community 3, work 3, path 2 (all at most 5)
    assertEquals(hitLines.size, 4 + 3 + 3 + 2)
    hitLines.foreach(l =>
      assert(l.contains("summary:v1:") && l.contains("distance"))
    )
    assert(
      wide.exists(_.contains("4 hits among 4 rows of this type")),
      wide.mkString("\n")
    )
    // the narrow sample is untouched and no wide line passes for it
    assert(
      captured.lines
        .filter(_.startsWith("SAMPLE "))
        .forall(_.contains("nearest is the source row: true"))
    )
  }

  test("the local cost is stated in the output and the report") {
    val exportDir = writeExport(tmp())
    val outDir = tmp().resolve("out")
    val (outcome, captured) = run(exportDir, outDir)
    assert(outcome.success)
    val cost =
      "local cost: $0 (in-process fakes); AWS cost deferred to Phase 12"
    assertEquals(IngestMain.LocalCost, cost)
    assert(captured.lines.contains(cost))
    assertEquals(report(outDir)("localCost").str, cost)
    val at = captured.lines.indexOf(cost)
    assert(captured.lines(at - 1).startsWith("objects all-objects:"))
  }

  test("a rerun gives the identical report") {
    val exportDir = writeExport(tmp())
    val outDir = tmp().resolve("out")
    val (first, firstOut) = run(exportDir, outDir)
    val before = Files.readAllBytes(outDir.resolve(IngestMain.ReportName))
    val (second, secondOut) = run(exportDir, outDir)
    assert(first.success && second.success)
    assertEquals(
      Files.readAllBytes(outDir.resolve(IngestMain.ReportName)).toVector,
      before.toVector
    )
    assertEquals(secondOut.result, firstOut.result)
    val stable = (c: Captured) =>
      c.lines.filterNot(l =>
        l.contains("LOCAL timing") || l.contains("latency")
      )
    assertEquals(stable(secondOut), stable(firstOut))
    val run2 = ujson.read(Files.readString(outDir.resolve(IngestMain.RunName)))
    assertEquals(run2("documentsCreated").num.toInt, 0)
    assertEquals(run2("documentsUnchanged").num.toInt, 12)
  }

  test("a bound ingests the first rows only") {
    val exportDir = writeExport(tmp())
    val outDir = tmp().resolve("out")
    val (outcome, captured) = run(exportDir, outDir, Some(5))
    assert(outcome.success, captured.lines.mkString("\n"))
    val json = report(outDir)
    assertEquals(json("bound").num.toInt, 5)
    assertEquals(json("summaryRowsInExport").num.toInt, 12)
    assertEquals(json("summaryRowsIngested").num.toInt, 5)
    assertEquals(json("vectorsTotal").num.toInt, 5)
    assert(captured.lines.exists(_.contains("no work row in this run")))
  }

  test("an output directory of a different bound is refused") {
    val exportDir = writeExport(tmp())
    val outDir = tmp().resolve("out")
    assert(run(exportDir, outDir, Some(5))._1.success)
    val (outcome, captured) = run(exportDir, outDir, None)
    assert(!outcome.success)
    assert(captured.result.startsWith("PHASE8-INGEST RESULT: FAILED"))
    assert(outcome.reason.contains("different export or bound"))
    assertEquals(report(outDir)("bound").num.toInt, 5)
  }

  test("a missing export is refused") {
    val (outcome, captured) = run(tmp().resolve("nope"), tmp().resolve("out"))
    assert(!outcome.success)
    assert(captured.result.startsWith("PHASE8-INGEST RESULT: FAILED"))
    assert(outcome.reason.contains("MissingSuccessMarker"))
  }

  test("an export without _SUCCESS is refused and nothing is written") {
    val exportDir = writeExport(tmp())
    Files.delete(exportDir.resolve("_SUCCESS"))
    val outDir = tmp().resolve("out")
    val (outcome, captured) = run(exportDir, outDir)
    assert(!outcome.success)
    assert(outcome.reason.contains("MissingSuccessMarker"))
    assert(captured.result.startsWith("PHASE8-INGEST RESULT: FAILED"))
    assert(!Files.exists(outDir))
  }

  test("an incomplete export is refused: a changed part file") {
    val exportDir = writeExport(tmp())
    val part = exportDir.resolve("semantic-summaries/part-00000.jsonl")
    Files.writeString(part, Files.readString(part) + "\n")
    val outDir = tmp().resolve("out")
    val (outcome, _) = run(exportDir, outDir)
    assert(!outcome.success)
    assert(!Files.exists(outDir))
  }

  test("an output directory inside the export, or a foreign one, is refused") {
    val exportDir = writeExport(tmp())
    val (inside, _) = run(exportDir, exportDir.resolve("out"))
    assert(!inside.success)
    assert(inside.reason.contains("overlaps"))
    val foreign = tmp()
    Files.writeString(foreign.resolve("notes.txt"), "mine")
    val (other, _) = run(exportDir, foreign)
    assert(!other.success)
    assert(other.reason.contains(IngestMain.ReportName))
    assertEquals(Files.readString(foreign.resolve("notes.txt")), "mine")
    val (parent, _) = run(exportDir, exportDir.getParent)
    assert(!parent.success)
  }

  test("a rejected summary makes the run FAILED, with the count") {
    val bad = summaries :+ row(
      "z1",
      "researcher-profile",
      ada,
      "Researcher with a work ID that is far too long for metadata.",
      None,
      None,
      Vector("w" * 300),
      ujson.Arr(),
      Vector.empty
    )
    val exportDir = writeExport(tmp(), tables(bad))
    val (outcome, captured) = run(exportDir, tmp().resolve("out"))
    assert(!outcome.success)
    assert(captured.result.startsWith("PHASE8-INGEST RESULT: FAILED"))
    assert(captured.lines.exists(_.startsWith("rejected=1")))
  }

  test("the arguments are parsed and bad ones are refused") {
    val parsed = IngestMain.parseArgs(Array("/a", "/b"))
    assertEquals(parsed.bound, None)
    assertEquals(IngestMain.parseArgs(Array("/a", "/b", "40")).bound, Some(40))
    Vector(
      Array("/a"),
      Array("/a", "/b", "0"),
      Array("/a", "/b", "-3"),
      Array("/a", "/b", "many"),
      Array("/a", "/b", "1", "2")
    ).foreach { args =>
      intercept[StorageException](IngestMain.parseArgs(args))
    }
  }

  test("main returns on success and throws on a failed run") {
    val exportDir = writeExport(tmp())
    IngestMain.main(Array(exportDir.toString, tmp().resolve("out").toString))
    intercept[IllegalStateException](
      IngestMain.main(
        Array(tmp().resolve("nope").toString, tmp().resolve("o").toString)
      )
    )
  }
