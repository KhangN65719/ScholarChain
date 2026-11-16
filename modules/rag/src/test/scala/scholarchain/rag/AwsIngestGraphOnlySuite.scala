package scholarchain.rag

import scala.collection.mutable
import scholarchain.storage.*

/** The `--graph-only` mode, the OutOfMemoryError handling and the graph
  * sub-phase markers of the AWS loader, against stubbed ports. No AWS call is
  * made and no real memory is exhausted: an `OutOfMemoryError` is thrown by an
  * injected function.
  */
class AwsIngestGraphOnlySuite extends munit.FunSuite:
  import AwsLoaderFixtures.*

  private val GraphOnly = AwsIngestMain.GraphOnlyFlag
  private val UsageLine = "usage: AwsIngestMain <runId> [--graph-only]"

  private def result(lines: Vector[String]): Vector[String] =
    lines.filter(_.startsWith("LOADER_RESULT="))

  private def phaseNames(lines: Vector[String]): Vector[String] =
    lines
      .filter(_.startsWith("LOADER_PHASE "))
      .map(_.split(" ").slice(1, 3).mkString(" "))

  /** Records every call that a graph-only run must never make. */
  private final class Touches:
    val log = mutable.ArrayBuffer.empty[String]
    def hit(what: String): Nothing =
      log += what
      throw new AssertionError(s"a graph-only run touched $what")

  private final class ForbiddenEmbedder(touches: Touches) extends Embedder:
    val modelVersion: String = TitanEmbedder.ModelVersion
    val dimension: Int = TitanEmbedder.Dimension
    def embed(text: String): Vector[Float] = touches.hit("the embedder")

  private final class ForbiddenBucket(touches: Touches)
      extends VectorBucketPort:
    def ensureIndex(spec: VectorIndexSpec): Unit =
      touches.hit("the vector bucket (ensureIndex)")
    def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
      touches.hit("the vector bucket (putVectors)")
    def getVectors(index: String, keys: Vector[String]): Vector[VectorRecord] =
      touches.hit("the vector bucket (getVectors)")
    def count(index: String): Int =
      touches.hit("the vector bucket (count)")
    def query(request: VectorQuery): Vector[VectorMatch] =
      touches.hit("the vector bucket (query)")

  private final class ForbiddenTable(
      touches: Touches,
      val spec: DynamoTableSpec
  ) extends DynamoTable:
    def putIfAbsent(item: Item): Unit = touches.hit("the ledger (putIfAbsent)")
    def putIfVersion(item: Item, expectedVersion: Long): Unit =
      touches.hit("the ledger (putIfVersion)")
    def get(pk: String, sk: String): Option[Item] =
      touches.hit("the ledger (get)")
    def query(pk: String, skPrefix: Option[String], limit: Int): Vector[Item] =
      touches.hit("the ledger (query)")
    def queryIndex(
        index: String,
        pk: String,
        skPrefix: Option[String],
        limit: Int
    ): Vector[Item] = touches.hit("the ledger (queryIndex)")

  private def untouchable(
      world: World,
      touches: Touches
  ): IngestMain.Backend =
    world
      .backend()
      .copy(
        embedder = new ForbiddenEmbedder(touches),
        bucket = new ForbiddenBucket(touches),
        ledger = new ForbiddenTable(touches, world.ledger.spec)
      )

  private def graphOnlyArgs(world: World): Array[String] =
    Array(world.runId, GraphOnly)

  private def graphKey(world: World, name: String): String =
    s"${world.config.reportBase}/$name"

  // --- argument parsing -----------------------------------------------------

  test("the run id stays the one positional; the flag may come anywhere") {
    val plain = AwsIngestMain.parseConfig(Array("run-1"), validEnv)
    assert(!plain.graphOnly)
    Vector(
      Array("run-1", GraphOnly),
      Array(GraphOnly, "run-1")
    ).foreach { args =>
      val config = AwsIngestMain.parseConfig(args, validEnv)
      assert(config.graphOnly, args.mkString(" "))
      assertEquals(config.runId, "run-1")
      assertEquals(config.exportPrefix, plain.exportPrefix)
      assertEquals(config.reportBase, plain.reportBase)
    }
  }

  test("an unknown option or a wrong positional count is refused before AWS") {
    val bad = Vector(
      Array("run-1", "--graph-onyl"),
      Array("run-1", "--force"),
      Array("--graph-only=true", "run-1"),
      Array("run-1", "--"),
      Array("run-1", "run-2"),
      Array("run-1", "run-2", GraphOnly),
      Array("run-1", GraphOnly, GraphOnly),
      Array(GraphOnly),
      Array.empty[String]
    )
    bad.foreach { args =>
      var built = 0
      val lines = mutable.ArrayBuffer.empty[String]
      val code = AwsIngestMain.run(
        args,
        validEnv,
        lines += _,
        _ =>
          built += 1
          throw new AssertionError("no wiring for a refused command line")
      )
      val shown = args.mkString(" ")
      assertEquals(code, 2, shown)
      assertEquals(built, 0, shown)
      assertEquals(lines.size, 1, shown)
      assert(
        lines.head.startsWith("LOADER_RESULT=FAILED reason=InvalidQuery: "),
        lines.head
      )
      assert(lines.head.contains(UsageLine), lines.head)
    }
  }

  test("graph-only without a Neptune host is refused, not a silent no-op") {
    val env = validEnv - "SCHOLARCHAIN_NEPTUNE_HOST" -
      "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN"
    val error = intercept[StorageException](
      AwsIngestMain.parseConfig(Array("run-1", GraphOnly), env)
    )
    assert(error.getMessage.contains("SCHOLARCHAIN_NEPTUNE_HOST"), error)
  }

  // --- graph-only behaviour -------------------------------------------------

  test(
    "graph-only skips the ingest and the samples and touches no ingest port"
  ) {
    val world = new World()
    val touches = new Touches
    val (code, lines) =
      world.run(untouchable(world, touches), args = graphOnlyArgs(world))
    val text = lines.mkString("\n")
    assertEquals(touches.log.toVector, Vector.empty[String], text)
    assertEquals(code, 0, text)
    assertEquals(world.embedder.calls, 0L)
    assertEquals(lines.head, "LOADER_MODE=graph-only")
    assertEquals(
      lines.count(_ == "INGEST_STEP=SKIPPED(graph-only)"),
      1,
      text
    )
    val forbidden = Vector(
      "LOADER_PROGRESS",
      "LOADER_INDEX",
      "LOADER_TOTAL",
      "LOADER_EMBEDDING",
      "LOADER_BATCH_FAILED",
      "WIDE-SAMPLE"
    )
    forbidden.foreach { prefix =>
      assert(!lines.exists(_.startsWith(prefix)), s"$prefix in:\n$text")
    }
    assert(!text.contains("nearest is the source row"), text)
    assert(!text.contains("INGEST_STEP=SUCCESS"), text)
    assert(!text.contains("vectors-and-graph"), text)
    assert(
      lines.exists(_.startsWith("LOADER_SCOPE=graph-only (")),
      text
    )
    assert(lines.exists(_.startsWith("NEPTUNE_LOAD=LOADED")), text)
    assert(
      lines.contains(
        "RUN_ID_MATCH=true export_run=" + world.runId +
          " argument=" + world.runId
      ),
      text
    )
    assertEquals(result(lines), Vector("LOADER_RESULT=SUCCESS"), text)
    assertEquals(lines.last, "LOADER_RESULT=SUCCESS")
    assertEquals(world.neptune.vertices.size, 2)
    assertEquals(world.neptune.edges.size, 1)
  }

  test("graph-only phases: download, graph with its four steps, report") {
    val world = new World()
    val (code, lines) =
      world.run(untouchable(world, new Touches), args = graphOnlyArgs(world))
    assertEquals(code, 0, lines.mkString("\n"))
    assertEquals(
      phaseNames(lines),
      Vector(
        "name=download state=start",
        "name=download state=done",
        "name=graph state=start",
        "name=graph-open state=start",
        "name=graph-open state=done",
        "name=graph-project state=start",
        "name=graph-project state=done",
        "name=graph-export state=start",
        "name=graph-export state=done",
        "name=graph-load state=start",
        "name=graph-load state=done",
        "name=graph state=done",
        "name=report state=start",
        "name=report state=done"
      ),
      lines.mkString("\n")
    )
    val pattern =
      "LOADER_PHASE name=[a-z-]+ state=(start|done) elapsed_s=[0-9]+"
    val markers = lines.filter(_.startsWith("LOADER_PHASE "))
    markers.foreach(m => assert(m.matches(pattern), m))
    val seconds = markers.map(_.split("elapsed_s=").last.toLong)
    assertEquals(seconds, seconds.sorted)
  }

  test("a full run brackets the graph steps inside the graph phase, in order") {
    val world = new World()
    val (code, lines) = world.run()
    assertEquals(code, 0, lines.mkString("\n"))
    val names = phaseNames(lines)
    val from = names.indexOf("name=graph state=start")
    val to = names.indexOf("name=graph state=done")
    assertEquals(
      names.slice(from, to + 1),
      Vector(
        "name=graph state=start",
        "name=graph-open state=start",
        "name=graph-open state=done",
        "name=graph-project state=start",
        "name=graph-project state=done",
        "name=graph-export state=start",
        "name=graph-export state=done",
        "name=graph-load state=start",
        "name=graph-load state=done",
        "name=graph state=done"
      )
    )
    // The ingest phase and the four top-level phases are all still there.
    assert(names.contains("name=ingest state=done"), names.toString)
  }

  test("a failing graph step prints start and no done for that step only") {
    val world = new World()
    val (code, lines) = world.run(world.backend(loader = new FailingLoader))
    assertEquals(code, 1, lines.mkString("\n"))
    val names = phaseNames(lines)
    assert(names.contains("name=graph-load state=start"), names.toString)
    assert(!names.contains("name=graph-load state=done"), names.toString)
    assert(names.contains("name=graph-export state=done"), names.toString)
    assert(names.contains("name=graph state=done"), names.toString)
  }

  test("graph-only writes its own reports and leaves the ingest report alone") {
    val world = new World()
    val (firstCode, _) = world.run()
    assertEquals(firstCode, 0)
    val ingestSha = world.store.head(world.reportKey).get.sha256
    val runSha = world.store.head(world.runKey).get.sha256
    val ingestBytes = world.store.get(world.reportKey).get.toVector
    val touches = new Touches
    val (code, lines) =
      world.run(untouchable(world, touches), args = graphOnlyArgs(world))
    assertEquals(code, 0, lines.mkString("\n"))
    assertEquals(touches.log.toVector, Vector.empty[String])
    assertEquals(world.store.head(world.reportKey).get.sha256, ingestSha)
    assertEquals(world.store.head(world.runKey).get.sha256, runSha)
    assertEquals(world.store.get(world.reportKey).get.toVector, ingestBytes)
    assertEquals(
      world.store.list(s"${world.config.reportBase}/").map(_.key).sorted,
      Vector(
        graphKey(world, AwsIngestMain.GraphReportName),
        graphKey(world, AwsIngestMain.GraphRunName),
        world.reportKey,
        world.runKey
      ).sorted
    )
    val report = world.json(graphKey(world, AwsIngestMain.GraphReportName))
    assertEquals(report("reportVersion").str, AwsIngestMain.GraphReportVersion)
    assertEquals(report("loaderMode").str, "graph-only")
    assertEquals(report("ingest").str, "SKIPPED(graph-only)")
    assertEquals(report("ingestPerformed").bool, false)
    assertEquals(report("scope").str, "graph-only")
    assertEquals(report("result").str, "SUCCESS")
    assertEquals(report("graph")("status").str, "LOADED")
    assertEquals(report("runIdArgument").str, world.runId)
    Vector("perIndex", "vectorsTotal", "samples", "embeddingModel").foreach {
      key =>
        assert(!report.obj.contains(key), s"$key in ${ujson.write(report)}")
    }
    val run = world.json(graphKey(world, AwsIngestMain.GraphRunName))
    assertEquals(run("loaderMode").str, "graph-only")
    assertEquals(run("result").str, "SUCCESS")
    assert(!run.obj.contains("embeddingCalls"))
    assert(
      lines.contains(
        s"LOADER_REPORT=s3://sc-data-test/${graphKey(world, AwsIngestMain.GraphReportName)}"
      )
    )
  }

  test("graph-only alone writes no ingest report and no fake of one") {
    val world = new World()
    val (code, _) =
      world.run(untouchable(world, new Touches), args = graphOnlyArgs(world))
    assertEquals(code, 0)
    assertEquals(world.store.head(world.reportKey), None)
    assertEquals(world.store.head(world.runKey), None)
    assert(
      world.store.head(graphKey(world, AwsIngestMain.GraphReportName)).isDefined
    )
  }

  test("a failed Neptune load in graph-only gives FAILED and graph-report") {
    val world = new World()
    val touches = new Touches
    val (code, lines) = world.run(
      untouchable(world, touches)
        .copy(graph = Some(GraphLoad(new FailingLoader, _ => projection))),
      args = graphOnlyArgs(world)
    )
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assertEquals(touches.log.toVector, Vector.empty[String])
    assertEquals(result(lines).size, 1)
    assert(result(lines).head.contains("Neptune load failed"), text)
    assert(text.contains("NEPTUNE_LOAD=FAILED"), text)
    assert(text.contains("INGEST_STEP=SKIPPED(graph-only)"), text)
    assertEquals(world.store.head(world.reportKey), None)
    val report = world.json(graphKey(world, AwsIngestMain.GraphReportName))
    assertEquals(report("result").str, "FAILED")
    assertEquals(report("loaderMode").str, "graph-only")
    assertEquals(report("graph")("status").str, "FAILED")
  }

  test("graph-only on an export without the graph tables is FAILED") {
    val world = new World(withGraph = false)
    val (code, lines) = world.run(
      untouchable(world, new Touches).copy(graph =
        Some(GraphLoad(new UnreachableLoader, GraphLoad.sparkProjection))
      ),
      args = graphOnlyArgs(world)
    )
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assert(result(lines).head.contains("the graph table(s)"), text)
    assert(!text.contains("LOADER_RESULT=SUCCESS"))
  }

  test("a missing export in graph-only records FAILED in graph-report only") {
    val world = new World(seeded = false)
    val (code, lines) = world.run(args = graphOnlyArgs(world))
    assertEquals(code, 1, lines.mkString("\n"))
    assertEquals(lines.head, "LOADER_MODE=graph-only")
    assert(result(lines).head.contains("No export objects under"))
    assertEquals(world.store.head(world.reportKey), None)
    val record = world.json(graphKey(world, AwsIngestMain.GraphReportName))
    assertEquals(record("result").str, "FAILED")
    assertEquals(record("loaderMode").str, "graph-only")
    assertEquals(record("ingestPerformed").bool, false)
  }

  test("a full run is unchanged: no mode line, no graph-only field") {
    val world = new World()
    val (code, lines) = world.run()
    assertEquals(code, 0, lines.mkString("\n"))
    assert(!lines.exists(_.startsWith("LOADER_MODE=")))
    assert(!lines.contains("INGEST_STEP=SKIPPED(graph-only)"))
    assert(lines.contains("LOADER_SCOPE=vectors-and-graph"))
    assert(lines.exists(_.startsWith("LOADER_EMBEDDING ")))
    val report = world.json(world.reportKey)
    Vector("loaderMode", "ingest", "ingestPerformed", "note").foreach { key =>
      assert(!report.obj.contains(key), key)
    }
    assertEquals(report("reportVersion").str, IngestMain.ReportVersion)
    assertEquals(report("scope").str, "vectors-and-graph")
    assertEquals(
      world.store.list(s"${world.config.reportBase}/").map(_.key).sorted,
      Vector(world.reportKey, world.runKey).sorted
    )
  }

  // --- OutOfMemoryError ----------------------------------------------------

  private def oom(): Nothing = throw new OutOfMemoryError("injected for a test")

  test(
    "an OOM in the graph projection is FAILED reason=OOM and exits nonzero"
  ) {
    val world = new World()
    val (code, lines) =
      world.run(world.backend(project = _ => oom()))
    val text = lines.mkString("\n")
    assertNotEquals(code, 0, text)
    assertEquals(code, AwsIngestMain.ExitOom, text)
    assertEquals(result(lines).size, 1, text)
    assertEquals(
      lines.last,
      "LOADER_RESULT=FAILED reason=OOM phase=graph-project"
    )
    val short =
      lines.indexWhere(_.startsWith("LOADER_OOM phase=graph-project "))
    assert(short >= 0 && short < lines.size - 1, text)
    assert(!text.contains("LOADER_RESULT=SUCCESS"))
    assert(!text.contains("NEPTUNE_LOAD=LOADED"))
    // Recorded as every other failure: a FAILED report beside the run.
    val record = world.json(world.reportKey)
    assertEquals(record("result").str, "FAILED")
    assert(record("reason").str.contains("OutOfMemoryError"), record.toString)
    assert(record("reason").str.contains("graph-project"), record.toString)
    assert(
      lines.contains(s"LOADER_REPORT=s3://sc-data-test/${world.reportKey}"),
      text
    )
    // The ingest that finished before it is not undone.
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 4)
  }

  test("an OOM in the ingest phase names the ingest phase") {
    final class OomEmbedder extends Embedder:
      val modelVersion: String = TitanEmbedder.ModelVersion
      val dimension: Int = TitanEmbedder.Dimension
      def embed(text: String): Vector[Float] = oom()
    val world = new World()
    val (code, lines) =
      world.run(world.backend().copy(embedder = new OomEmbedder))
    val text = lines.mkString("\n")
    assertEquals(code, AwsIngestMain.ExitOom, text)
    assertEquals(lines.last, "LOADER_RESULT=FAILED reason=OOM phase=ingest")
    assertEquals(result(lines).size, 1, text)
    assertEquals(world.json(world.reportKey)("result").str, "FAILED")
  }

  test("an OOM in a graph-only run is recorded in graph-report only") {
    val world = new World()
    val (code, lines) = world.run(
      untouchable(world, new Touches)
        .copy(graph = Some(GraphLoad(world.neptune, _ => oom()))),
      args = graphOnlyArgs(world)
    )
    val text = lines.mkString("\n")
    assertEquals(code, AwsIngestMain.ExitOom, text)
    assertEquals(lines.head, "LOADER_MODE=graph-only")
    assertEquals(
      lines.last,
      "LOADER_RESULT=FAILED reason=OOM phase=graph-project"
    )
    assertEquals(world.store.head(world.reportKey), None)
    val record = world.json(graphKey(world, AwsIngestMain.GraphReportName))
    assertEquals(record("result").str, "FAILED")
    assertEquals(record("loaderMode").str, "graph-only")
  }

  /** Writes under the given prefixes throw an `OutOfMemoryError`. */
  private final class OomOnWrite(inner: ObjectStore, prefixes: Vector[String])
      extends ObjectStore:
    protected def write(key: String, bytes: Array[Byte], ref: String): Unit =
      if prefixes.exists(key.startsWith) then oom()
      else inner.putImmutable(key, bytes, ref)
    def head(key: String): Option[ObjectMeta] = inner.head(key)
    def get(key: String): Option[Array[Byte]] = inner.get(key)
    def list(prefix: String): Vector[ObjectMeta] = inner.list(prefix)

  test("an OOM while recording the OOM still ends with the result and exit 3") {
    val world = new World()
    val store = new OomOnWrite(world.store, Vector("graph/", "reports/"))
    val (code, lines) = world.run(
      world.backend().copy(store = store),
      args = graphOnlyArgs(world)
    )
    val text = lines.mkString("\n")
    assertEquals(code, AwsIngestMain.ExitOom, text)
    assertEquals(
      lines.last,
      "LOADER_RESULT=FAILED reason=OOM phase=graph-export"
    )
    assertEquals(result(lines).size, 1, text)
    assert(lines.exists(_.startsWith("LOADER_OOM phase=graph-export ")), text)
    assert(!lines.exists(_.startsWith("LOADER_REPORT=")), text)
    assertEquals(world.store.list("reports/").size, 0)
  }
