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

  private val Progress = "LOADER_PROGRESS "

  test("progress lines reach out while the ingest runs, before it finishes") {
    val exportDir = writeExport(tmp())
    val outDir = tmp().resolve("out")
    // Each line is stamped with whether the ingest report exists yet. The
    // report is written right after the ingest returns, so a progress line
    // seen with no report was delivered during the run, not after it.
    val seen = mutable.ArrayBuffer.empty[(String, Boolean)]
    val outcome = IngestMain.execute(
      IngestMain.Args(exportDir, outDir, None),
      line =>
        seen += line -> Files.exists(outDir.resolve(IngestMain.ReportName))
    )
    val lines = seen.map(_._1).toVector
    assert(outcome.success, lines.mkString("\n"))
    val progress = seen.filter(_._1.startsWith(Progress))
    assert(progress.nonEmpty, lines.mkString("\n"))
    assert(progress.forall(!_._2), "a progress line came after the report")
    assertEquals(
      lines.indexWhere(!_.startsWith(Progress)),
      progress.size,
      "every progress line precedes the first summary line"
    )
    assertEquals(
      lines.head,
      s"${Progress}start items=12 batches=${batches(outDir)}"
    )
    val perBatch = progress.map(_._1).filter(_.contains(" batch="))
    assertEquals(perBatch.size, batches(outDir))
    assert(perBatch.last.contains("items_done=12/12"), perBatch.last)
    assert(perBatch.forall(_.contains(" index=")), perBatch.toString)
    assert(
      lines.exists(_.startsWith(IngestMain.ResultPrefix)),
      "the result line is still printed"
    )
  }

  private def batches(outDir: Path): Int =
    report(outDir)("perIndex").obj.values.map(_("batches").num.toInt).sum

  test("a local main prints the progress lines too") {
    val exportDir = writeExport(tmp())
    val sink = new java.io.ByteArrayOutputStream
    Console.withOut(sink) {
      IngestMain.main(Array(exportDir.toString, tmp().resolve("out").toString))
    }
    val text = sink.toString("UTF-8")
    assert(text.contains(s"${Progress}start items=12 batches="), text)
    assert(text.contains(IngestMain.ResultPrefix + "SUCCESS"), text)
  }

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
    // Progress lines count what a run did (written, items done), so a resumed
    // rerun differs from the first run by design; every other line is equal.
    val stable = (c: Captured) =>
      c.lines.filterNot(l =>
        l.contains("LOCAL timing") || l.contains("latency") ||
          l.startsWith(Progress)
      )
    assertEquals(stable(secondOut), stable(firstOut))
    assert(firstOut.lines.exists(_.startsWith(Progress)))
    assert(secondOut.lines.exists(_.startsWith(Progress)))
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

  /** Records every listing call, so a test can see which form a caller used.
    * Only `list` is expensive on AWS (one HEAD per object).
    */
  private final class CountingStore(val inner: ObjectStore) extends ObjectStore:
    val calls = mutable.ArrayBuffer.empty[(String, String)]
    protected def write(key: String, bytes: Array[Byte], ref: String): Unit =
      inner.putImmutable(key, bytes, ref)
    def head(key: String): Option[ObjectMeta] = inner.head(key)
    def get(key: String): Option[Array[Byte]] = inner.get(key)
    def list(prefix: String): Vector[ObjectMeta] =
      calls += (("list", prefix))
      inner.list(prefix)
    override def listKeys(prefix: String): Vector[String] =
      calls += (("listKeys", prefix))
      inner.listKeys(prefix)
    override def listSizes(prefix: String): Vector[(String, Long)] =
      calls += (("listSizes", prefix))
      inner.listSizes(prefix)

  private val OwnedPrefixes = Vector(
    "documents" -> SemanticDocuments.prefix,
    "embedding-work" -> s"${S3Prefix.SemanticContext.dir}/embedding-work/",
    "vector-rejects" -> s"${S3Prefix.Logs.dir}/vector-rejects/"
  )

  private def countingBackend(
      store: CountingStore,
      aws: Boolean
  ): Args => IngestMain.Backend = _ =>
    IngestMain.Backend(
      new HashEmbedder,
      EmbeddingModels.HashEmbedV1,
      store,
      new InMemoryVectorBucket,
      new InMemoryDynamoTable(RunLedger.spec(TableNames("local"))),
      aws = aws
    )

  private type Args = IngestMain.Args

  /** What `objectGroups` answered before listSizes: `list` and its metadata. */
  private def oldGroups(
      store: ObjectStore,
      names: Vector[(String, String)]
  ): Vector[(String, Int, Long)] =
    names.map { case (name, prefix) =>
      val metas = store.list(prefix)
      (name, metas.size, metas.map(_.bytes).sum)
    }

  private def reportedGroups(outDir: Path): Vector[(String, Int, Long)] =
    report(outDir)("objects").obj.toVector.map { case (name, v) =>
      (name, v("count").num.toInt, v("bytes").num.toLong)
    }

  test("the AWS object groups come from listSizes: no list, same values") {
    val exportDir = writeExport(tmp())
    val outDir = tmp().resolve("out")
    val store = new CountingStore(new InMemoryObjectStore)
    val captured = new Captured
    val outcome = IngestMain.execute(
      IngestMain.Args(exportDir, outDir, None),
      captured.out,
      identity,
      countingBackend(store, aws = true)
    )
    assert(outcome.success, captured.lines.mkString("\n"))
    assertEquals(
      store.calls.filter(_._1 == "list").toVector,
      Vector.empty[(String, String)],
      "the ingest must not call list: one HEAD per object on AWS"
    )
    OwnedPrefixes.foreach { case (_, prefix) =>
      assert(
        store.calls.contains(("listSizes", prefix)),
        s"no listSizes($prefix): ${store.calls}"
      )
    }
    assert(
      !store.calls.contains(("listSizes", "")),
      "an AWS run lists only its own prefixes, never the whole bucket"
    )
    val expected = oldGroups(store.inner, OwnedPrefixes)
    assertEquals(reportedGroups(outDir), expected)
    assertEquals(expected.map(_._2), Vector(12, 1, 0))
    assert(expected.head._3 > 0 && expected(1)._3 > 0)
    expected.foreach { case (name, count, bytes) =>
      assert(
        captured.lines.contains(s"objects $name: count=$count bytes=$bytes"),
        captured.lines.mkString("\n")
      )
    }
  }

  test(
    "the local whole-store groups come from listSizes: no list, same values"
  ) {
    val exportDir = writeExport(tmp())
    val outDir = tmp().resolve("out")
    val store =
      new CountingStore(new LocalFsObjectStore(outDir.resolve("objects")))
    val captured = new Captured
    val outcome = IngestMain.execute(
      IngestMain.Args(exportDir, outDir, None),
      captured.out,
      identity,
      countingBackend(store, aws = false)
    )
    assert(outcome.success, captured.lines.mkString("\n"))
    assertEquals(
      store.calls.filter(_._1 == "list").toVector,
      Vector.empty[(String, String)]
    )
    assert(store.calls.contains(("listSizes", "")), store.calls.toString)
    val all = store.inner.list("")
    val expected = oldGroups(store.inner, OwnedPrefixes) :+
      (("all-objects", all.size, all.map(_.bytes).sum))
    assertEquals(reportedGroups(outDir), expected)
    assertEquals(expected.last._2, 13)
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

  // Failure reasons on the console and in ingest-run.json (S run 7 cut the
  // PutVectors reason at 160 characters, before the cause).

  /** A bucket whose `getVectors` throws, so every batch fails with `message`.
    */
  private final class ThrowingGets(inner: VectorBucketPort, message: String)
      extends VectorBucketPort:
    def ensureIndex(spec: VectorIndexSpec): Unit = inner.ensureIndex(spec)
    def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
      inner.putVectors(index, batch)
    def getVectors(index: String, keys: Vector[String]): Vector[VectorRecord] =
      throw new IllegalStateException(message)
    def count(index: String): Int = inner.count(index)
    def query(request: VectorQuery): Vector[VectorMatch] = inner.query(request)

  private val ClassPrefix = "IllegalStateException: "

  /** A message that makes the whole batch reason `total` characters long. The
    * digits cycle, so a cut at the wrong place shows in the text.
    */
  private def messageFor(total: Int): String =
    Iterator
      .continually("0123456789")
      .flatten
      .take(total - ClassPrefix.length)
      .mkString

  private def failingBackend(message: String): Args => IngestMain.Backend =
    args =>
      val local = IngestMain.localBackend(args)
      local.copy(bucket = new ThrowingGets(local.bucket, message))

  private def runFailing(
      exportDir: Path,
      outDir: Path,
      message: String
  ): (IngestMain.Outcome, Captured) =
    val captured = new Captured
    val outcome = IngestMain.execute(
      IngestMain.Args(exportDir, outDir, None),
      captured.out,
      identity,
      failingBackend(message)
    )
    (outcome, captured)

  private def runJson(outDir: Path): ujson.Value =
    ujson.read(Files.readString(outDir.resolve(IngestMain.RunName)))

  private val FirstIndex = "researcher-profile-v1"

  test("a rejected detail of several hundred characters is shown whole") {
    val key = "q" * 400
    val otherRun = "run:v1:99999999-9999-4999-8999-999999999999"
    val prov = provenance("semantic-summaries", key)
    prov.obj("runId") = otherRun
    val bad = row(
      key,
      "researcher-profile",
      ada,
      "Researcher whose provenance names another run.",
      None,
      None,
      Vector(w1),
      ujson.Arr(),
      Vector.empty
    ) + ("provenance" -> prov)
    val exportDir = writeExport(tmp(), tables(summaries :+ bad))
    val (outcome, captured) = run(exportDir, tmp().resolve("out"))
    assert(!outcome.success)
    val lines = captured.lines.filter(_.startsWith("  reject "))
    assertEquals(lines.size, 1, captured.lines.mkString("\n"))
    val summaryId = s"summary:v1:$key"
    val tail = s"$summaryId carries run $otherRun, the export is of $runId"
    assert(tail.length > 160, "the fixture must exceed the old cap")
    assert(lines.head.endsWith(tail), lines.head)
  }

  test("a failed batch reason of 600 characters is shown whole, 900 is cut") {
    Vector(600, 900).foreach { total =>
      val message = messageFor(total)
      val reason = ClassPrefix + message
      val outDir = tmp().resolve("out")
      val (outcome, captured) = runFailing(writeExport(tmp()), outDir, message)
      assert(!outcome.success)
      val shown = captured.lines.filter(_.startsWith("  failed batch "))
      assertEquals(shown.size, 4, captured.lines.mkString("\n"))
      assertEquals(
        shown.head,
        s"  failed batch $FirstIndex #0: ${reason.take(600)}"
      )
      assertEquals(shown.head.length - shown.head.indexOf(": ") - 2, 600)
      assert(!captured.lines.exists(_.contains(" more failed batches")))
      // The live line carries the same reason, ahead of the summary lines.
      val live = captured.lines.filter(_.startsWith("LOADER_BATCH_FAILED "))
      assertEquals(live.size, 4)
      assertEquals(
        live.head,
        s"LOADER_BATCH_FAILED index=$FirstIndex batch=1 reason=${reason.take(600)}"
      )
      assert(
        captured.lines.lastIndexWhere(
          _.startsWith("LOADER_BATCH_FAILED ")
        ) < captured.lines.indexWhere(_.startsWith("  failed batch "))
      )
      // ingest-run.json holds the same reasons, cut at 600 characters.
      val recorded = runJson(outDir)("failedBatches").arr.toVector
      assertEquals(recorded.size, 4)
      assertEquals(
        recorded.head.obj.keySet.toSet,
        Set("index", "ordinal", "reason")
      )
      assertEquals(recorded.head("index").str, FirstIndex)
      assertEquals(recorded.head("ordinal").num.toInt, 0)
      recorded.foreach(r => assertEquals(r("reason").str, reason.take(600)))
    }
  }

  test("25 failed batches print 20 lines and one line for the other 5") {
    val window = Some(("2000-2009", 2000, 2009))
    val extra = (1 to 10500).toVector.map { i =>
      row(
        f"big$i%05d",
        "researcher-profile",
        ada,
        s"Researcher number $i in window 2000-2009.",
        window,
        Some("canonical"),
        Vector(w1),
        ujson.Arr("publicationCount"),
        Vector(ada)
      )
    }
    val exportDir =
      writeExport(tmp(), tables(summaries ++ extra), rowsPerFile = 1000)
    val outDir = tmp().resolve("out")
    val message = messageFor(80)
    val (outcome, captured) = runFailing(exportDir, outDir, message)
    assert(!outcome.success)
    // 22 profile batches (10,504 rows of 500), plus community, work and path.
    assertEquals(report(outDir)("failedBatches").num.toInt, 25)
    assert(captured.lines.exists(_.contains("failedBatches=25")))
    val shown = captured.lines.filter(_.startsWith("  failed batch "))
    assertEquals(shown.size, 20)
    shown.zipWithIndex.foreach { case (line, i) =>
      assertEquals(
        line,
        s"  failed batch $FirstIndex #$i: $ClassPrefix$message"
      )
    }
    val more =
      "  ... 5 more failed batches (all of them are in ingest-run.json)"
    assertEquals(
      captured.lines.indexOf(more),
      captured.lines.lastIndexWhere(_.startsWith("  failed batch ")) + 1,
      captured.lines.mkString("\n")
    )
    assertEquals(captured.lines.count(_.contains(" more failed batches")), 1)
    // The record keeps all 25, not the 20 shown.
    assertEquals(runJson(outDir)("failedBatches").arr.size, 25)
    // The live line is printed for every failed batch, none left out.
    assertEquals(
      captured.lines.count(_.startsWith("LOADER_BATCH_FAILED ")),
      25
    )
  }

  test("ingest-run.json keeps its fields and gains an empty failedBatches") {
    val outDir = tmp().resolve("out")
    val (outcome, captured) = run(writeExport(tmp()), outDir)
    assert(outcome.success, captured.lines.mkString("\n"))
    val json = runJson(outDir)
    assertEquals(
      json.obj.keys.toVector,
      Vector(
        "label",
        "totalMillis",
        "vectorsPerSecond",
        "documentsCreated",
        "documentsUnchanged",
        "batchMillis",
        "failedBatches"
      )
    )
    assertEquals(json("failedBatches").arr.size, 0)
    assertEquals(
      json("batchMillis")(0).obj.keys.toVector,
      Vector("index", "ordinal", "items", "millis")
    )
  }

  // The SHA-256 of ingest-report.json, taken from the code as it was before
  // failedBatches reached ingest-run.json. The report is deterministic, so a
  // change to its bytes for the same input is a change to a stable contract.
  private val GoldenCleanReport =
    "ead5e080f42b56ed5df2c7da4c31be1f38644355ebaaa11540db74b1db069e08"
  private val GoldenFailedReport =
    "e346537dd4515f1635ba5074ed34969e15a77dbc24738461886b060871bf37c0"

  test(
    "a clean run's ingest-report.json is byte-identical to its earlier form"
  ) {
    val cleanOut = tmp().resolve("out")
    assert(run(writeExport(tmp()), cleanOut)._1.success)
    assertEquals(
      Hashing.sha256(
        Files.readAllBytes(cleanOut.resolve(IngestMain.ReportName))
      ),
      GoldenCleanReport
    )
  }

  test(
    "a failed run's ingest-report.json is byte-identical and holds no reason"
  ) {
    val failedOut = tmp().resolve("out")
    val (outcome, _) = runFailing(writeExport(tmp()), failedOut, "boom")
    assert(!outcome.success)
    val bytes = Files.readAllBytes(failedOut.resolve(IngestMain.ReportName))
    assertEquals(Hashing.sha256(bytes), GoldenFailedReport)
    val json = report(failedOut)
    assertEquals(json("failedBatches").num.toInt, 4)
    assert(!new String(bytes, "UTF-8").contains("boom"), "no reason text")
  }
