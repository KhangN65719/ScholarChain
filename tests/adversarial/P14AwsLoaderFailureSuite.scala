package scholarchain.tests.adversarial

import scala.collection.mutable
import scholarchain.rag.{AwsIngestMain, CountingEmbedder, EmbeddingModels}
import scholarchain.rag.{GraphLoad, GraphStep, IngestMain}
import scholarchain.storage.*

/** Work order 35, item 2: the AWS loader entry point against stubbed ports.
  * Every scenario holds the output contract: exactly one `LOADER_RESULT=` line
  * and it is last.
  */
class P14AwsLoaderFailureSuite extends munit.FunSuite:
  import P14Loader.*

  private def results(lines: Vector[String]): Vector[String] =
    lines.filter(_.startsWith("LOADER_RESULT="))

  private def contract(lines: Vector[String]): Unit =
    val text = lines.mkString("\n")
    assertEquals(results(lines).size, 1, text)
    assertEquals(lines.last, results(lines).head, text)

  private def failed(code: Int, lines: Vector[String], expected: Int = 1) =
    val text = lines.mkString("\n")
    contract(lines)
    assertEquals(code, expected, text)
    assert(lines.last.startsWith("LOADER_RESULT=FAILED"), text)
    assert(!text.contains("LOADER_RESULT=SUCCESS"), text)

  private def noSecrets(clue: String, text: String): Unit =
    secrets.foreach(s => assert(!text.contains(s), s"$clue leaked $s"))

  test("a failed vector batch is FAILED with exit 1 and the graph untouched") {
    val world = new World()
    val (code, lines) =
      world.run(world.backend(bucketPort = new FailingBucket(world.bucket)))
    failed(code, lines)
    val text = lines.mkString("\n")
    assert(text.contains("INGEST_STEP=FAILED"), text)
    assert(!text.contains("RESULT: SUCCESS"), text)
    assert(text.contains("NEPTUNE_LOAD=SKIPPED"), text)
    assertEquals(world.neptune.vertices.size, 0)
    assertEquals(world.json(world.reportKey)("result").str, "FAILED")
  }

  test("a missing export is FAILED with exit 1 before any embedding") {
    val world = new World(seeded = false)
    val (code, lines) = world.run()
    failed(code, lines)
    assert(lines.last.contains("No export objects under"), lines.last)
    assertEquals(world.embedder.calls, 0L)
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 0)
    assertEquals(world.json(world.reportKey)("result").str, "FAILED")
  }

  test("an export without _SUCCESS is FAILED before any embedding") {
    val world = new World()
    val prefix = world.config.exportPrefix
    val other = new InMemoryObjectStore
    world.store
      .list(prefix)
      .filterNot(_.key.endsWith("/_SUCCESS"))
      .foreach(m =>
        other.putImmutable(m.key, world.store.get(m.key).get, "p14")
      )
    assert(world.store.list(prefix).exists(_.key.endsWith("/_SUCCESS")))
    assert(!other.list(prefix).exists(_.key.endsWith("/_SUCCESS")))
    val (code, lines) = world.run(world.backend(on = other))
    failed(code, lines)
    assert(lines.last.contains("_SUCCESS"), lines.last)
    assertEquals(world.embedder.calls, 0L)
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 0)
  }

  test("a 384-d embedder is refused against the 256-d Titan registry entry") {
    val world = new World(embedder = new CountingEmbedder(new HashEmbedder))
    val (code, lines) = world.run()
    failed(code, lines)
    val text = lines.mkString("\n")
    assert(text.contains("384") && text.contains("256"), text)
    assertEquals(world.embedder.calls, 0L)
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 0)
  }

  test("a 384-d embedder is refused against pre-existing 256-d indexes") {
    val world = new World(
      embedder = new CountingEmbedder(new HashEmbedder),
      model = EmbeddingModels.HashEmbedV1
    )
    VectorIndexes.all(256, "cosine").foreach(world.bucket.ensureIndex)
    val (code, lines) = world.run()
    failed(code, lines)
    assert(lines.mkString("\n").contains("exists differently"), lines.mkString)
    assertEquals(world.embedder.calls, 0L)
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 0)
  }

  test("a Neptune host with the graph tables missing is FAILED, vectors kept") {
    val world = new World(withGraph = false)
    val loader = new UnreachableLoader
    val (code, lines) = world.run(
      world.backend(loader = loader, project = GraphLoad.sparkProjection)
    )
    failed(code, lines)
    val text = lines.mkString("\n")
    assert(lines.last.contains("the graph table(s)"), lines.last)
    assert(text.contains("NEPTUNE_LOAD=SKIPPED"), text)
    assert(
      lines.contains("LOADER_SCOPE=vectors-only (the graph was NOT loaded)"),
      text
    )
    assert(!text.contains("NEPTUNE_LOAD=LOADED"), text)
    assertEquals(loader.calls.size, 0)
    assertEquals(world.store.list("graph/").size, 0)
    val report = world.json(world.reportKey)
    assertEquals(report("result").str, "FAILED")
    assert(report("graphRequired").bool)
    assertEquals(report("graph")("status").str, "SKIPPED")
    assertEquals(
      report("graph")("missingTables").arr.map(_.str).toVector.sorted,
      GraphStep.RequiredTables.filterNot(_ == "nodes-work").sorted
    )
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 2)
  }

  test("a failed Neptune job is FAILED after a good vector ingest") {
    val world = new World()
    val (code, lines) = world.run(world.backend(loader = new FailingLoader))
    failed(code, lines)
    val text = lines.mkString("\n")
    assert(text.contains("INGEST_STEP=SUCCESS"), text)
    assert(text.contains("NEPTUNE_LOAD=FAILED"), text)
    assert(text.contains("LOAD_FAILED"), text)
    assert(!text.contains("NEPTUNE_LOAD=LOADED"), text)
    val report = world.json(world.reportKey)
    assertEquals(report("result").str, "FAILED")
    assertEquals(report("graph")("status").str, "FAILED")
  }

  test(
    "a projector refusal with a Neptune host is FAILED, loader not reached"
  ) {
    val world = new World()
    val loader = new UnreachableLoader
    val (code, lines) = world.run(
      world.backend(
        loader = loader,
        project = _ => throw new IllegalStateException("projector refused")
      )
    )
    failed(code, lines)
    assert(lines.last.contains("projector refused"), lines.last)
    assertEquals(loader.calls.size, 0)
  }

  test("no host is a vectors-only SUCCESS and says so") {
    val world = new World(withGraph = false)
    val loader = new UnreachableLoader
    val (code, lines) =
      world.run(world.backend(loader = loader), env = vectorsOnlyEnv)
    val text = lines.mkString("\n")
    contract(lines)
    assertEquals(code, 0, text)
    assertEquals(lines.last, "LOADER_RESULT=SUCCESS")
    assert(
      lines.contains("LOADER_SCOPE=vectors-only (the graph was NOT loaded)"),
      text
    )
    assertEquals(
      lines.filter(_.startsWith("NEPTUNE_LOAD=")),
      Vector("NEPTUNE_LOAD=SKIPPED reason=no Neptune host configured")
    )
    assertEquals(loader.calls.size, 0)
    val report = world.json(world.reportKey)
    assertEquals(report("result").str, "SUCCESS")
    assertEquals(report("scope").str, "vectors-only")
    assert(!report("graphRequired").bool)
  }

  test("a blank or empty host is a configuration error exiting 2") {
    Vector("", " ", "   ", "\t").foreach { blank =>
      var wired = 0
      val lines = mutable.ArrayBuffer.empty[String]
      val code = AwsIngestMain.run(
        Array(runId),
        validEnv.updated("SCHOLARCHAIN_NEPTUNE_HOST", blank),
        lines += _,
        _ =>
          wired += 1
          throw new AssertionError("no wiring for a bad configuration")
      )
      assertEquals(code, 2, s"host '$blank'")
      assertEquals(wired, 0)
      assertEquals(lines.size, 1, lines.mkString("\n"))
      assert(lines.head.startsWith("LOADER_RESULT=FAILED"), lines.head)
      assert(lines.head.contains("SCHOLARCHAIN_NEPTUNE_HOST"), lines.head)
    }
  }

  test("a host without the load role is a configuration error exiting 2") {
    val lines = mutable.ArrayBuffer.empty[String]
    val code = AwsIngestMain.run(
      Array(runId),
      validEnv - "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN",
      lines += _,
      _ => throw new AssertionError("no wiring for a bad configuration")
    )
    assertEquals(code, 2)
    assertEquals(lines.size, 1)
    assert(lines.head.contains("SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN"))
  }

  test("a client that cannot be built is FAILED exit 1 with one result line") {
    val lines = mutable.ArrayBuffer.empty[String]
    val code = AwsIngestMain.run(
      Array(runId),
      validEnv,
      lines += _,
      _ => throw new IllegalStateException("cannot build a client")
    )
    failed(code, lines.toVector)
    assert(lines.last.contains("cannot build a client"), lines.last)
  }

  test("a secret-looking value never reaches the output or any report") {
    // Success, a failed batch and a failed Neptune job, each with the fake
    // access key, secret, session token and bearer token in the environment.
    val scenarios = Vector[(String, World => IngestMain.Backend)](
      "success" -> (w => w.backend()),
      "failed batch" -> (w =>
        w.backend(bucketPort = new FailingBucket(w.bucket))
      ),
      "failed neptune" -> (w => w.backend(loader = new FailingLoader))
    )
    scenarios.foreach { case (name, make) =>
      val world = new World()
      val (_, lines) = world.run(make(world), env = withSecrets)
      contract(lines)
      noSecrets(s"$name output", lines.mkString("\n"))
      noSecrets(s"$name stored objects", world.everythingStored)
    }
    val cfg = AwsIngestMain.parseConfig(Array("r"), withSecrets)
    noSecrets("config toString", cfg.toString)
  }

  test("a malformed variable holding a secret is rejected without echoing it") {
    Vector(
      "SCHOLARCHAIN_DATA_BUCKET" -> fakeAkia,
      "SCHOLARCHAIN_VECTOR_BUCKET" -> fakeBearer,
      "SCHOLARCHAIN_TABLE_PREFIX" -> (fakeSecretKey + " with space"),
      "SCHOLARCHAIN_NEPTUNE_HOST" -> fakeBearer,
      "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN" -> fakeSession,
      "AWS_REGION" -> fakeAkia
    ).foreach { case (name, value) =>
      val lines = mutable.ArrayBuffer.empty[String]
      val code = AwsIngestMain.run(
        Array(runId),
        withSecrets.updated(name, value),
        lines += _,
        _ => throw new AssertionError("no wiring for a bad configuration")
      )
      assertEquals(code, 2, name)
      assertEquals(lines.size, 1, name)
      assert(lines.head.contains(name), lines.head)
      noSecrets(name, lines.mkString("\n"))
    }
    // The run id is an argument, not an environment value; a secret-looking
    // run id with a disallowed character is refused without being echoed.
    val bad = fakeBearer
    val lines = mutable.ArrayBuffer.empty[String]
    val code = AwsIngestMain.run(
      Array(bad),
      withSecrets,
      lines += _,
      _ => throw new AssertionError("no wiring for a bad run id")
    )
    assertEquals(code, 2)
    noSecrets("run id", lines.mkString("\n"))
  }

  test(
    "a rerun with different counts keeps the first report and adds a new key"
  ) {
    val world = new World()
    val (firstCode, first) = world.run()
    assertEquals(firstCode, 0, first.mkString("\n"))
    contract(first)
    val reportSha = world.store.head(world.reportKey).get.sha256
    val runSha = world.store.head(world.runKey).get.sha256
    val firstJson = world.json(world.reportKey)
    val (secondCode, second) = world.run()
    assertEquals(secondCode, 0, second.mkString("\n"))
    contract(second)
    val total = second.find(_.startsWith("LOADER_TOTAL ")).get
    assert(total.contains("vectors_written=0"), total)
    assert(total.contains("skipped_unchanged="), total)
    // The first report and run record are byte-identical after the rerun.
    assertEquals(world.store.head(world.reportKey).get.sha256, reportSha)
    assertEquals(world.store.head(world.runKey).get.sha256, runSha)
    assertEquals(world.json(world.reportKey), firstJson)
    val reports = world.store
      .list(s"${world.config.reportBase}/")
      .map(_.key)
      .filter(_.endsWith(IngestMain.ReportName))
    assertEquals(reports.size, 2, reports.mkString("\n"))
    val added = reports.filterNot(_ == world.reportKey).head
    assert(
      added.startsWith(s"${world.config.reportBase}/20261114T"),
      added
    )
    assertNotEquals(
      world.store.head(added).get.sha256,
      reportSha,
      "the added report differs from the first"
    )
    assert(
      second.contains(s"LOADER_REPORT=s3://sc-data-p14/$added"),
      second.mkString("\n")
    )
  }

  test("two reruns in the same clock second never overwrite the first report") {
    val world = new World()
    val fixed = () => java.time.Instant.parse("2026-11-14T12:00:00Z")
    val (firstCode, first) = world.run(clock = fixed)
    assertEquals(firstCode, 0, first.mkString("\n"))
    val reportSha = world.store.head(world.reportKey).get.sha256
    val runSha = world.store.head(world.runKey).get.sha256
    val (secondCode, second) = world.run(clock = fixed)
    val (thirdCode, third) = world.run(clock = fixed)
    // Observed: the ledger execution label is the second-resolution stamp, so
    // a rerun in the same UTC second is refused (FAILED, exit 1), never
    // silently merged. Reported to rag-engineer as a finding.
    assertEquals(secondCode, 1, second.mkString("\n"))
    assertEquals(thirdCode, 1, third.mkString("\n"))
    assert(second.mkString.contains("ConditionalCheckFailed"), second.mkString)
    // Whatever the outcome of the reruns, the safety property holds.
    contract(second)
    contract(third)
    assertEquals(world.store.head(world.reportKey).get.sha256, reportSha)
    assertEquals(world.store.head(world.runKey).get.sha256, runSha)
    noSecrets("same-second reruns", second.mkString + third.mkString)
  }
