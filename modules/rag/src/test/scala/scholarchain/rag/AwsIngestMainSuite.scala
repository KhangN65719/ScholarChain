package scholarchain.rag

import scala.collection.mutable
import scholarchain.storage.*
import software.amazon.awssdk.regions.Region

/** The AWS loader entry point against stubbed ports. No AWS call is made: the
  * data bucket, vector bucket, ledger and Neptune are in memory and Titan is a
  * deterministic stand-in.
  */
class AwsIngestMainSuite extends munit.FunSuite:
  import AwsLoaderFixtures.*

  private def result(lines: Vector[String]): Vector[String] =
    lines.filter(_.startsWith("LOADER_RESULT="))

  private def only(lines: Vector[String], prefix: String): String =
    val found = lines.filter(_.startsWith(prefix))
    assertEquals(found.size, 1, lines.mkString("\n"))
    found.head

  test("end to end: counts, retrieval samples, graph load and SUCCESS") {
    val world = new World()
    val (code, lines) = world.run()
    val text = lines.mkString("\n")
    assertEquals(code, 0, text)
    assertEquals(result(lines), Vector("LOADER_RESULT=SUCCESS"), text)
    assertEquals(lines.last, "LOADER_RESULT=SUCCESS")
    assert(
      lines.exists(
        _.startsWith(
          "LOADER_INDEX name=researcher-profile-v1 planned=4 written=4 " +
            "skipped_unchanged=0 skipped_resumed=0 rejected=0 duplicates=0 " +
            "retries=0 failed_batches=0 vectors_in_index=4"
        )
      ),
      text
    )
    assert(
      lines.exists(
        _.startsWith("LOADER_INDEX name=paper-abstract-v1 planned=3")
      ),
      text
    )
    assert(
      lines.contains(
        "LOADER_TOTAL vectors_written=12 skipped_unchanged=0 " +
          "skipped_resumed=0 rejected=0 duplicates=0 retries=0 " +
          "failed_batches=0 vectors_in_index=12"
      ),
      text
    )
    // 12 documents embedded, then two queries for each of four summary types.
    assertEquals(world.embedder.calls, 20L)
    assert(
      lines.contains(
        "LOADER_EMBEDDING model=amazon.titan-embed-text-v2:0-d256-norm " +
          "dimension=256 calls=20"
      ),
      text
    )
    assertEquals(
      lines.count(_.contains("nearest is the source row: true")),
      4,
      text
    )
    assertEquals(lines.count(_.startsWith("WIDE-SAMPLE ")), 4)
    assertEquals(lines.count(_.contains("fetchText: textHash verified")), 4)
    assert(only(lines, "NEPTUNE_LOAD=").startsWith("NEPTUNE_LOAD=LOADED"))
    assert(lines.contains("LOADER_SCOPE=vectors-and-graph"), text)
    assert(lines.exists(_.startsWith("LOADER_ELAPSED_SECONDS=")))
  }

  private val PhasePattern =
    "LOADER_PHASE name=(download|ingest|graph|report) state=(start|done) " +
      "elapsed_s=([0-9]+)"

  private def phaseAt(
      lines: Vector[String],
      name: String,
      state: String
  ): Int =
    val prefix = s"LOADER_PHASE name=$name state=$state elapsed_s="
    val found = lines.zipWithIndex.filter(_._1.startsWith(prefix))
    assertEquals(found.size, 1, lines.mkString("\n"))
    found.head._2

  test("the four phases are bracketed by greppable markers, in order") {
    val world = new World()
    val (code, lines) = world.run()
    assertEquals(code, 0, lines.mkString("\n"))
    val markers = lines.filter(_.startsWith("LOADER_PHASE "))
    markers.foreach(m => assert(m.matches(PhasePattern), m))
    assertEquals(
      markers.map(_.split(" ").slice(1, 3).mkString(" ")),
      Vector(
        "name=download state=start",
        "name=download state=done",
        "name=ingest state=start",
        "name=ingest state=done",
        "name=graph state=start",
        "name=graph state=done",
        "name=report state=start",
        "name=report state=done"
      ),
      lines.mkString("\n")
    )
    val seconds = markers.map(_.split("elapsed_s=").last.toLong)
    assertEquals(seconds, seconds.sorted)
    val index = (name: String, state: String) => phaseAt(lines, name, state)
    // Each existing line sits inside the phase that produces it.
    val input = lines.indexWhere(_.startsWith("LOADER_INPUT "))
    assert(
      index("download", "start") < index("download", "done") &&
        index("download", "done") < input && input < index("ingest", "start")
    )
    val step = lines.indexWhere(_.startsWith("INGEST_STEP="))
    assert(index("ingest", "start") < step && step < index("ingest", "done"))
    val neptune = lines.indexWhere(_.startsWith("NEPTUNE_LOAD="))
    assert(
      index("graph", "start") < neptune && neptune < index("graph", "done")
    )
    val uploaded = lines.indexWhere(_.startsWith("LOADER_REPORT=s3://"))
    assert(
      index("report", "start") < uploaded && uploaded < index("report", "done")
    )
    assert(index("report", "done") < lines.size - 1)
    assertEquals(result(lines), Vector("LOADER_RESULT=SUCCESS"))
    assertEquals(lines.last, "LOADER_RESULT=SUCCESS")
  }

  test("progress lines pass through unchanged, inside the ingest phase") {
    val world = new World()
    val (code, lines) = world.run()
    assertEquals(code, 0, lines.mkString("\n"))
    val progress =
      lines.zipWithIndex.filter(_._1.startsWith("LOADER_PROGRESS "))
    assert(progress.nonEmpty, lines.mkString("\n"))
    // Unchanged: still LOADER_PROGRESS, not renamed to the INGEST_STEP form.
    assert(!lines.exists(_.startsWith("INGEST_STEP=LOADER_PROGRESS")))
    assertEquals(
      progress.head._1,
      s"LOADER_PROGRESS start items=12 batches=${progress.count(_._1.contains(" batch="))}"
    )
    val start = phaseAt(lines, "ingest", "start")
    val step = lines.indexWhere(_.startsWith("INGEST_STEP="))
    assert(progress.forall(p => p._2 > start && p._2 < step), progress.toString)
    assert(progress.last._1.contains("items_done=12/12"), progress.last._1)
    progress.foreach { case (line, _) =>
      assert(!line.contains("LOCAL") && !line.contains("$"), line)
    }
  }

  test("a missing export prints download start, no done, and one last result") {
    val world = new World(seeded = false)
    val (code, lines) = world.run()
    assertEquals(code, 1, lines.mkString("\n"))
    assert(phaseAt(lines, "download", "start") >= 0)
    assert(!lines.exists(_.startsWith("LOADER_PHASE name=download state=done")))
    assert(!lines.exists(_.startsWith("LOADER_PHASE name=ingest")))
    assert(!lines.exists(_.startsWith("LOADER_PROGRESS ")))
    assertEquals(result(lines).size, 1)
    assertEquals(lines.last, result(lines).head)
  }

  test("a failed vector batch still closes ingest, graph and report phases") {
    val world = new World()
    val (code, lines) =
      world.run(world.backend(bucketPort = new FailingBucket(world.bucket)))
    assertEquals(code, 1, lines.mkString("\n"))
    Vector("download", "ingest", "graph", "report").foreach { name =>
      assert(phaseAt(lines, name, "start") < phaseAt(lines, name, "done"))
    }
    assertEquals(result(lines).size, 1)
    assertEquals(lines.last, result(lines).head)
    assert(phaseAt(lines, "report", "done") < lines.size - 1)
  }

  test("the topic and institution index is reported as no source") {
    val world = new World()
    val (code, lines) = world.run()
    assertEquals(code, 0)
    val topic = only(lines, "LOADER_INDEX name=topic-institution-summary-v1")
    assert(topic.contains("planned=0"), topic)
    assert(topic.contains("vectors_in_index=0"), topic)
    assert(topic.contains("status=NO_SOURCE"), topic)
    assert(!topic.contains("FAILED"))
    val report = world.json(world.reportKey)
    assertEquals(
      report("perIndex")("topic-institution-summary-v1")("planned").num.toInt,
      0
    )
  }

  test("the reports carry AWS numbers only and are written to the bucket") {
    val world = new World()
    val (code, lines) = world.run()
    assertEquals(code, 0)
    val report = world.json(world.reportKey)
    val run = world.json(world.runKey)
    assertEquals(report("result").str, "SUCCESS")
    assertEquals(report("scope").str, "vectors-and-graph")
    assertEquals(report("embeddingModel").str, TitanEmbedder.ModelVersion)
    assertEquals(report("dimension").num.toInt, 256)
    assertEquals(report("label").str, IngestMain.AwsLabel)
    assertEquals(report("vectorsTotal").num.toInt, 12)
    assertEquals(report("graph")("status").str, "LOADED")
    assertEquals(report("graph")("vertices").num.toInt, 2)
    assertEquals(report("graph")("edges").num.toInt, 1)
    assertEquals(run("embeddingCalls").num.toInt, 20)
    assert(run("elapsedSeconds").num >= 0.0)
    assertEquals(run("label").str, IngestMain.AwsLabel)
    val everything =
      lines.mkString("\n") + ujson.write(report) + ujson.write(run)
    Vector("LOCAL", "localCost", "local cost", "in-process", "Phase 12")
      .foreach { word =>
        assert(!everything.contains(word), s"AWS output carries '$word'")
      }
    assertEquals(report("cost").str, IngestMain.AwsCost)
    assert(!everything.contains("$0"), "no cost figure is claimed")
    assert(
      lines.contains(
        s"LOADER_REPORT=s3://sc-data-test/${world.reportKey}"
      )
    )
  }

  test("retrieval hits are reported with their evidence ids") {
    val world = new World()
    assertEquals(world.run()._1, 0)
    val samples = world.json(world.reportKey)("samples").arr.toVector
    assertEquals(samples.size, 4)
    val profile = samples
      .find(_("summaryType").str == "researcher-profile")
      .getOrElse(fail("no researcher-profile sample"))
    val first = profile("narrowHits")(0)
    assertEquals(first("key").str, profile("sourceKey").str)
    assertEquals(first("subjectId").str, RagFixtures.ada)
    assert(first("evidenceNodeIds").arr.map(_.str).contains(RagFixtures.ada))
    assert(first("evidenceWorkIds").arr.map(_.str).contains(RagFixtures.w1))
    assert(first("textRef").str.startsWith("semantic-context/documents/"))
    assert(profile("wideHits").arr.nonEmpty)
    samples.foreach { s =>
      assert(s("narrow")("nearest").bool, ujson.write(s))
      assert(s("wide")("nearest").bool, ujson.write(s))
    }
  }

  test("the graph files and the manifest go under graph/neptune and apart") {
    val world = new World()
    assertEquals(world.run()._1, 0)
    val keys = world.store.list("graph/").map(_.key)
    val loadFiles = keys.filter(_.startsWith("graph/neptune/"))
    val manifests = keys.filter(_.startsWith("graph/neptune-manifest/"))
    assertEquals(loadFiles.size, 3, keys.mkString("\n"))
    assert(loadFiles.forall(_.endsWith(".csv")))
    assertEquals(manifests.size, 1)
    assert(manifests.head.endsWith("load-manifest.json"))
    assert(world.neptune.vertices.size == 2 && world.neptune.edges.size == 1)
  }

  test("a rerun writes nothing, skips everything and keeps the first report") {
    val world = new World()
    val (firstCode, first) = world.run()
    assertEquals(firstCode, 0)
    val firstSha = world.store.head(world.reportKey).get.sha256
    val (secondCode, second) = world.run()
    val text = second.mkString("\n")
    assertEquals(secondCode, 0, text)
    assertEquals(result(second), Vector("LOADER_RESULT=SUCCESS"))
    val total = only(second, "LOADER_TOTAL ")
    assert(total.contains("vectors_written=0"), total)
    assert(total.contains("skipped_unchanged=12"), total)
    assert(total.contains("skipped_resumed=0"), total)
    assert(total.contains("vectors_in_index=12"), total)
    assert(total.contains("rejected=0"), total)
    // The second run embeds only its own eight sample queries.
    assertEquals(world.embedder.calls, 28L)
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 4)
    assertEquals(world.store.head(world.reportKey).get.sha256, firstSha)
    val reports = world.store
      .list(s"${world.config.reportBase}/")
      .map(_.key)
      .filter(_.endsWith(IngestMain.ReportName))
    assertEquals(reports.size, 2, reports.mkString("\n"))
    val second2 = reports.filterNot(_ == world.reportKey).head
    assert(second2.contains("/20261114T"), second2)
    assertEquals(
      world.json(second2)("perIndex")("paper-abstract-v1")("written").num.toInt,
      0
    )
    assertEquals(
      first.count(_.startsWith("LOADER_RESULT=")),
      1
    )
  }

  test("a failed vector batch gives FAILED, a nonzero exit and no SUCCESS") {
    val world = new World()
    val (code, lines) =
      world.run(world.backend(bucketPort = new FailingBucket(world.bucket)))
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assertEquals(result(lines).size, 1, text)
    assert(result(lines).head.startsWith("LOADER_RESULT=FAILED"), text)
    assert(!text.contains("LOADER_RESULT=SUCCESS"))
    assert(!text.contains("RESULT: SUCCESS"), text)
    assert(text.contains("INGEST_STEP=FAILED"), text)
    assert(text.contains("failed batch"), text)
    assert(
      text.contains(
        "NEPTUNE_LOAD=SKIPPED reason=the vector ingest did not succeed"
      ),
      text
    )
    assertEquals(world.neptune.vertices.size, 0)
    assertEquals(world.json(world.reportKey)("result").str, "FAILED")
  }

  test("a failed Neptune job gives FAILED after a good vector ingest") {
    val world = new World()
    val (code, lines) =
      world.run(world.backend(loader = new FailingLoader))
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assertEquals(result(lines).size, 1)
    assert(result(lines).head.startsWith("LOADER_RESULT=FAILED"), text)
    assert(result(lines).head.contains("Neptune load failed"), text)
    assert(text.contains("INGEST_STEP=SUCCESS"), text)
    assert(text.contains("NEPTUNE_LOAD=FAILED"), text)
    assert(text.contains("LOAD_FAILED"), text)
    assert(!text.contains("NEPTUNE_LOAD=LOADED"))
    val report = world.json(world.reportKey)
    assertEquals(report("result").str, "FAILED")
    assertEquals(report("graph")("status").str, "FAILED")
    assertEquals(report("scope").str, "vectors-only")
  }

  /** A Neptune loader whose failure message is `size` digits long. */
  private final class LongMessageLoader(size: Int) extends NeptuneBulkLoader:
    val message: String =
      Iterator.continually("0123456789").flatten.take(size).mkString
    def load(manifestKey: String): BulkLoadResult =
      Fail(ErrorCode.BulkLoadFailed, message)

  test("the result line keeps up to 600 characters of the reason") {
    val prefix = "LOADER_RESULT=FAILED reason="
    // 480 characters of message plus the wrapping text is between the old cap
    // of 500 and the new one of 600, so it is shown whole.
    val world = new World()
    val whole = new LongMessageLoader(480)
    val (_, wholeLines) = world.run(world.backend(loader = whole))
    val wholeResult = result(wholeLines).head
    assert(wholeResult.length > prefix.length + 500, wholeResult)
    assert(wholeResult.length < prefix.length + 600, wholeResult)
    assert(wholeResult.endsWith(whole.message), wholeResult)
    // A longer reason is cut at exactly 600 characters.
    val other = new World()
    val (_, cutLines) =
      other.run(other.backend(loader = new LongMessageLoader(900)))
    assertEquals(result(cutLines).head.length, prefix.length + 600)
  }

  test(
    "a failed batch prints LOADER_BATCH_FAILED live, inside the ingest phase"
  ) {
    val world = new World()
    val (code, lines) =
      world.run(world.backend(bucketPort = new FailingBucket(world.bucket)))
    assertEquals(code, 1, lines.mkString("\n"))
    val failed =
      lines.zipWithIndex.filter(_._1.startsWith("LOADER_BATCH_FAILED "))
    assertEquals(failed.size, 4, lines.mkString("\n"))
    assert(
      failed.head._1.startsWith(
        "LOADER_BATCH_FAILED index=researcher-profile-v1 batch=1 reason="
      ),
      failed.head._1
    )
    failed.foreach { case (line, _) =>
      assert(line.contains("stubbed PutVectors rejection"), line)
    }
    val start = phaseAt(lines, "ingest", "start")
    val done = phaseAt(lines, "ingest", "done")
    val step = lines.indexWhere(_.startsWith("INGEST_STEP="))
    failed.foreach { case (line, at) =>
      assert(at > start && at < step && step < done, line)
    }
    // Passed through unchanged, not renamed into the INGEST_STEP form.
    assert(!lines.exists(_.startsWith("INGEST_STEP=LOADER_BATCH_FAILED")))
    // The uploaded run record holds the same batches and reasons.
    val recorded = world.json(world.runKey)("failedBatches").arr.toVector
    assertEquals(recorded.size, 4)
    assertEquals(recorded.head("index").str, "researcher-profile-v1")
    assertEquals(recorded.head("ordinal").num.toInt, 0)
    recorded.foreach(r =>
      assert(r("reason").str.contains("stubbed PutVectors rejection"))
    )
    // The deterministic report keeps the count only, never the reasons.
    assertEquals(world.json(world.reportKey)("failedBatches").num.toInt, 4)
  }

  test("a clean run uploads an empty failedBatches and no failure line") {
    val world = new World()
    val (code, lines) = world.run()
    assertEquals(code, 0, lines.mkString("\n"))
    assertEquals(world.json(world.runKey)("failedBatches").arr.size, 0)
    assert(!lines.exists(_.startsWith("LOADER_BATCH_FAILED ")))
  }

  test("a missing export gives FAILED and a nonzero exit") {
    val world = new World(seeded = false)
    val (code, lines) = world.run()
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assertEquals(result(lines).size, 1)
    assert(result(lines).head.contains("No export objects under"), text)
    assert(
      result(lines).head.contains(world.config.exportPrefix),
      result(lines).head
    )
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 0)
    assertEquals(world.embedder.calls, 0L)
    val record = world.json(world.reportKey)
    assertEquals(record("result").str, "FAILED")
    assert(record("reason").str.contains("No export objects"), record.toString)
  }

  test("an export without _SUCCESS is refused before any embedding") {
    val world = new World()
    val victim = world.store
      .list(world.config.exportPrefix)
      .map(_.key)
      .find(_.endsWith("/_SUCCESS"))
      .get
    val other = new InMemoryObjectStore
    world.store
      .list(world.config.exportPrefix)
      .filterNot(_.key == victim)
      .foreach { meta =>
        other.putImmutable(meta.key, world.store.get(meta.key).get, "test")
      }
    val lines = Vector.newBuilder[String]
    val code = AwsIngestMain.run(
      Array(world.runId),
      validEnv,
      lines += _,
      _ => AwsIngestMain.Wiring(world.backend().copy(store = other))
    )
    val text = lines.result().mkString("\n")
    assertEquals(code, 1, text)
    assert(text.contains("LOADER_RESULT=FAILED"), text)
    assert(text.contains("_SUCCESS"), text)
    assertEquals(world.embedder.calls, 0L)
  }

  test("a 384-dimension embedder is refused against the Titan registry entry") {
    val world = new World(embedder = new CountingEmbedder(new HashEmbedder))
    val (code, lines) = world.run()
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assertEquals(result(lines).size, 1)
    assert(result(lines).head.startsWith("LOADER_RESULT=FAILED"), text)
    assert(text.contains("384"), text)
    assert(text.contains("256"), text)
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 0)
    assertEquals(world.embedder.calls, 0L)
    assert(!text.contains("LOADER_RESULT=SUCCESS"))
  }

  test("a 384-dimension embedder is refused against existing 256 indexes") {
    val world = new World(
      embedder = new CountingEmbedder(new HashEmbedder),
      model = EmbeddingModels.HashEmbedV1
    )
    VectorIndexes
      .all(256, "cosine")
      .foreach(world.bucket.ensureIndex)
    val (code, lines) = world.run()
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assert(result(lines).head.startsWith("LOADER_RESULT=FAILED"), text)
    assert(text.contains("exists differently"), text)
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 0)
    assertEquals(world.embedder.calls, 0L)
  }

  test("a Neptune host and an export without the graph tables is FAILED") {
    val world = new World(withGraph = false)
    val loader = new UnreachableLoader
    val (code, lines) = world.run(
      world.backend(
        loader = loader,
        project = GraphLoad.sparkProjection
      )
    )
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assertEquals(result(lines).size, 1, text)
    assertEquals(lines.last, result(lines).head)
    assert(result(lines).head.startsWith("LOADER_RESULT=FAILED"), text)
    assert(result(lines).head.contains("the graph table(s)"), text)
    assert(!text.contains("LOADER_RESULT=SUCCESS"))
    assert(text.contains("INGEST_STEP=SUCCESS"), text)
    val skipped = only(lines, "NEPTUNE_LOAD=")
    assert(skipped.startsWith("NEPTUNE_LOAD=SKIPPED reason="), skipped)
    GraphStep.RequiredTables
      .filterNot(_ == "nodes-work")
      .foreach(table => assert(skipped.contains(table), s"$table: $skipped"))
    assert(!skipped.contains("nodes-work"), skipped)
    assert(
      lines.contains("LOADER_SCOPE=vectors-only (the graph was NOT loaded)")
    )
    assert(!text.contains("NEPTUNE_LOAD=LOADED"))
    assertEquals(loader.calls.size, 0)
    assertEquals(world.store.list("graph/").size, 0)
    val report = world.json(world.reportKey)
    assertEquals(report("graph")("status").str, "SKIPPED")
    assert(report("graph")("reason").str.contains("lacks the graph"))
    assertEquals(report("result").str, "FAILED")
    assert(report("reason").str.contains("required"), report.toString)
    assert(report("graphRequired").bool)
    assertEquals(report("scope").str, "vectors-only")
    assertEquals(
      report("graph")("missingTables").arr.map(_.str).toVector.sorted,
      GraphStep.RequiredTables.filterNot(_ == "nodes-work").sorted
    )
    // The vectors written stay, and the vector results are still reported.
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 4)
    assert(text.contains("LOADER_TOTAL vectors_written=12"), text)
  }

  test("a projector refusal with a Neptune host is FAILED") {
    val world = new World()
    val loader = new UnreachableLoader
    val (code, lines) = world.run(
      world.backend(
        loader = loader,
        project = _ => throw new IllegalStateException("projector refused")
      )
    )
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assertEquals(result(lines).size, 1, text)
    assert(result(lines).head.contains("projector refused"), text)
    assertEquals(loader.calls.size, 0)
    val report = world.json(world.reportKey)
    assertEquals(report("graph")("status").str, "FAILED")
    assertEquals(report("result").str, "FAILED")
  }

  test("no Neptune host is a vectors-only SUCCESS and says so") {
    val world = new World(withGraph = false)
    val loader = new UnreachableLoader
    val env = validEnv - "SCHOLARCHAIN_NEPTUNE_HOST" -
      "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN"
    val (code, lines) = world.run(world.backend(loader = loader), env = env)
    val text = lines.mkString("\n")
    assertEquals(code, 0, text)
    assertEquals(result(lines), Vector("LOADER_RESULT=SUCCESS"), text)
    assertEquals(lines.last, "LOADER_RESULT=SUCCESS")
    assertEquals(
      only(lines, "NEPTUNE_LOAD="),
      "NEPTUNE_LOAD=SKIPPED reason=no Neptune host configured"
    )
    assert(
      lines.contains("LOADER_SCOPE=vectors-only (the graph was NOT loaded)")
    )
    assertEquals(loader.calls.size, 0)
    assertEquals(world.store.list("graph/").size, 0)
    val report = world.json(world.reportKey)
    assertEquals(report("result").str, "SUCCESS")
    assertEquals(report("scope").str, "vectors-only")
    assertEquals(report("graph")("status").str, "SKIPPED")
    assertEquals(
      report("graph")("reason").str,
      "no Neptune host configured"
    )
    assert(!report("graphRequired").bool)
  }

  test("no Neptune host skips the graph even when tables could project") {
    val world = new World()
    val loader = new UnreachableLoader
    val env = validEnv - "SCHOLARCHAIN_NEPTUNE_HOST" -
      "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN"
    val (code, lines) = world.run(world.backend(loader = loader), env = env)
    assertEquals(code, 0, lines.mkString("\n"))
    assertEquals(loader.calls.size, 0)
    assertEquals(world.json(world.reportKey)("graph")("status").str, "SKIPPED")
  }

  test("a blank Neptune host is a configuration error, not vectors-only") {
    val error = intercept[StorageException](
      AwsIngestMain.parseConfig(
        Array("r"),
        validEnv.updated("SCHOLARCHAIN_NEPTUNE_HOST", "  ")
      )
    )
    assert(error.getMessage.contains("SCHOLARCHAIN_NEPTUNE_HOST"))
    val noRole = intercept[StorageException](
      AwsIngestMain.parseConfig(
        Array("r"),
        validEnv - "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN"
      )
    )
    assert(noRole.getMessage.contains("SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN"))
  }

  test("the missing tables are exactly those the export lacks today") {
    val tables = new InMemoryTableSource(RagFixtures.tables())
    assertEquals(
      GraphStep.missingTables(tables),
      Vector(
        "nodes-researcher",
        "nodes-venue",
        "nodes-topic",
        "nodes-institution",
        "edges-authored",
        "edges-published-in",
        "edges-coauthored",
        "edge-support"
      )
    )
  }

  test("a missing variable is named and its value is never printed") {
    // An absent host is the vectors-only run (tested apart); every other
    // variable, and the role once a host is set, is required.
    val names = (validEnv.keySet - "SCHOLARCHAIN_NEPTUNE_HOST").toVector.sorted
    assert(
      !AwsIngestMain
        .parseConfig(Array("run-1"), validEnv - "SCHOLARCHAIN_NEPTUNE_HOST")
        .graphRequired
    )
    names.foreach { name =>
      val error = intercept[StorageException](
        AwsIngestMain.parseConfig(Array("run-1"), validEnv - name)
      )
      assert(error.getMessage.contains(name), error.getMessage)
      val blank = intercept[StorageException](
        AwsIngestMain.parseConfig(Array("run-1"), validEnv.updated(name, "  "))
      )
      assert(blank.getMessage.contains(name), blank.getMessage)
    }
  }

  test("a malformed variable names the variable, never the value") {
    val bad = Vector(
      "AWS_REGION" -> "Not A Region 9",
      "SCHOLARCHAIN_DATA_BUCKET" -> "UPPER_secret_value!",
      "SCHOLARCHAIN_VECTOR_BUCKET" -> "x",
      "SCHOLARCHAIN_TABLE_PREFIX" -> "bad prefix with spaces",
      "SCHOLARCHAIN_NEPTUNE_HOST" -> "https://secret-host/path",
      "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN" -> "not-an-arn-secret"
    )
    bad.foreach { case (name, value) =>
      val error = intercept[StorageException](
        AwsIngestMain.parseConfig(Array("run-1"), validEnv.updated(name, value))
      )
      assert(error.getMessage.contains(name), error.getMessage)
      assert(!error.getMessage.contains(value), error.getMessage)
    }
  }

  test("a run id argument is required once and is checked") {
    Vector(Array.empty[String], Array("a", "b"), Array(" "), Array("a b"))
      .foreach { args =>
        intercept[StorageException](AwsIngestMain.parseConfig(args, validEnv))
      }
    val config = AwsIngestMain.parseConfig(Array("run:v1:abc"), validEnv)
    assertEquals(
      config.exportPrefix,
      "semantic-context/runs/run:v1:abc/export/"
    )
    assertEquals(config.reportBase, "reports/runs/run:v1:abc/loader")
  }

  test("the Neptune port defaults to 8182 and may follow the host") {
    val plain = AwsIngestMain.parseConfig(Array("r"), validEnv)
    assertEquals(plain.neptunePort, 8182)
    assertEquals(plain.neptuneHost, Some("neptune.example.internal"))
    val withPort = AwsIngestMain.parseConfig(
      Array("r"),
      validEnv.updated("SCHOLARCHAIN_NEPTUNE_HOST", "db.internal:8183")
    )
    assertEquals(withPort.neptunePort, 8183)
    assertEquals(withPort.neptuneHost, Some("db.internal"))
    intercept[StorageException](
      AwsIngestMain.parseConfig(
        Array("r"),
        validEnv.updated("SCHOLARCHAIN_NEPTUNE_HOST", "db.internal:99999")
      )
    )
  }

  test("a configuration error exits 2 before any client is built") {
    var built = 0
    val lines = mutable.ArrayBuffer.empty[String]
    val code = AwsIngestMain.run(
      Array("run-1"),
      validEnv - "SCHOLARCHAIN_VECTOR_BUCKET",
      lines += _,
      _ =>
        built += 1
        throw new AssertionError("no wiring for a bad configuration")
    )
    assertEquals(code, 2)
    assertEquals(built, 0)
    assertEquals(lines.size, 1)
    assert(
      lines.head.startsWith(
        "LOADER_RESULT=FAILED reason=InvalidQuery: Missing required " +
          "environment variable " +
          "SCHOLARCHAIN_VECTOR_BUCKET"
      ),
      lines.head
    )
  }

  test("credentials in the environment are never read into the output") {
    val world = new World()
    val secret = "AKIAFAKEFAKEFAKE12345"
    val env = validEnv ++ Map(
      "AWS_ACCESS_KEY_ID" -> secret,
      "AWS_SECRET_ACCESS_KEY" -> "super-secret-value-9876",
      "AWS_SESSION_TOKEN" -> "token-value-abcdef"
    )
    val (code, lines) = world.run(env = env)
    assertEquals(code, 0)
    val everything = lines.mkString("\n") +
      ujson.write(world.json(world.reportKey)) +
      ujson.write(world.json(world.runKey)) +
      AwsIngestMain.parseConfig(Array("r"), env).toString
    Vector(secret, "super-secret-value-9876", "token-value-abcdef").foreach {
      value => assert(!everything.contains(value), s"leaked $value")
    }
    val fields =
      classOf[AwsIngestMain.Config].getDeclaredFields.toVector.map(_.getName)
    val keyLike = fields.filter { n =>
      val lower = n.toLowerCase
      Vector("key", "secret", "token", "password", "credential")
        .exists(lower.contains)
    }
    assertEquals(keyLike, Vector.empty[String])
  }

  test("the AWS backend is built through AwsClients from a region alone") {
    val config = AwsIngestMain.parseConfig(Array("run-1"), validEnv)
    val regions = mutable.ArrayBuffer.empty[String]
    val wiring = AwsIngestMain.awsWiring(
      config,
      region =>
        regions += region
        new AwsClients(Region.of(region))
    )
    try
      assertEquals(regions.toVector, Vector("us-east-2"))
      val backend = wiring.backend
      assert(backend.aws)
      assert(backend.store.isInstanceOf[AwsObjectStore])
      assertEquals(
        backend.store.asInstanceOf[AwsObjectStore].bucket,
        "sc-data-test"
      )
      assert(backend.bucket.isInstanceOf[AwsVectorBucket])
      assert(backend.ledger.isInstanceOf[AwsDynamoTable])
      assert(backend.embedder.isInstanceOf[CountingEmbedder])
      assertEquals(backend.embedder.modelVersion, TitanEmbedder.ModelVersion)
      assertEquals(backend.embedder.dimension, 256)
      assertEquals(backend.model, EmbeddingModels.TitanTextV2D256)
      assert(backend.graph.exists(_.loader.isInstanceOf[AwsNeptuneBulkLoader]))
      assert(backend.sleeper eq ThreadSleeper)
      assertEquals(backend.embedCalls(), Some(0L))
      EmbeddingModels.requireMatches(backend.embedder, backend.model)
      EmbeddingModels.requireIndexSpecs(
        backend.model,
        VectorIndexes.all(backend.embedder.dimension, "cosine")
      )
    finally wiring.close()
  }

  test("AwsClients still takes a region and nothing else") {
    val constructors = classOf[AwsClients].getConstructors.toVector
    assertEquals(constructors.size, 1)
    assertEquals(
      constructors.head.getParameterTypes.toVector,
      Vector[Class[?]](classOf[Region])
    )
  }

  test(
    "the default factory is AwsClients.forRegion with the configured region"
  ) {
    val clients = AwsClients.forRegion("us-east-2")
    assertEquals(clients.region, Region.US_EAST_2)
  }
