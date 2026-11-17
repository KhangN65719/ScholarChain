package scholarchain.services

import java.net.ServerSocket
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import munit.FunSuite
import scala.util.Try
import scholarchain.core.{ContractSchema, Provenance, SourceType}
import scholarchain.rag.{DemoWorld, InMemoryMetricRanking, TableWorkCatalog}
import scholarchain.services.runtime.*
import scholarchain.storage.{
  AwsReadModels,
  ErrorCode,
  ExportSummary,
  JsonlExportSource,
  ResearcherMetricCache,
  Row,
  StorageException
}

/** The live read-model source and its wiring, over fakes: no AWS call is made.
  * `AwsBackend.live` builds SDK clients, which contact nothing until used.
  */
class AwsLiveReadModelsSuite extends FunSuite:
  import AwsTestEnv.*

  private val workId = DemoWorld.ids.works("w1")

  private val workRow: Row =
    Map(
      "canonicalWorkId" -> ujson.Str(workId),
      "title" -> ujson.Str("A work from the export"),
      "year" -> ujson.Num(2001),
      "source" -> ujson.Str("dblp"),
      "provenance" -> upickle.default.writeJs(
        Provenance(
          ContractSchema.currentVersion,
          SourceType.Derived,
          "hadoop-snapshot:demo",
          workId,
          "scholarchain:spark-analytics/nodes-work",
          DemoWorld.SparkTransform,
          DemoWorld.GraphRunId
        )
      )
    )

  private val summary = ExportSummary(
    DemoWorld.GraphRunId,
    DemoWorld.SparkTransform,
    "export/1",
    "a" * 64,
    "semantic-context/runs/x/export/",
    12,
    4096L,
    Vector(
      JsonlExportSource.TableStats("nodes-work", 1, 7L, 100L),
      JsonlExportSource.TableStats("researcher-metrics", 2, 9L, 200L)
    ),
    Vector("work:v1:untitled")
  )

  private final class Fixture0:
    val world = DemoWorld.build()
    val closes = new AtomicInteger(0)
    val loads = new AtomicInteger(0)
    val lines = Vector.newBuilder[String]
    val models: AwsReadModels = AwsReadModels(
      world.neptune,
      world.ports.paths,
      world.ports.components,
      world.ports.communities,
      world.ports.directory,
      DemoWorld.allMetrics,
      world.cache,
      Vector(workRow),
      summary,
      () => closes.incrementAndGet()
    )
    val source: AwsSettings => ReadModels = ReadModelSource.from(
      (_, _) =>
        loads.incrementAndGet()
        models
      ,
      line => synchronized { lines += line }
    )

  test("the live source maps every field of the export read models") {
    val f = new Fixture0
    val mapped = f.source(AwsSettings.parse(base))
    assertEquals(f.loads.get, 1)
    assert(mapped.neptune eq f.models.neptune)
    assert(mapped.paths eq f.models.paths)
    assert(mapped.components eq f.models.components)
    assert(mapped.communities eq f.models.communities)
    assert(mapped.directory eq f.models.directory)
    assert(mapped.ranking.isInstanceOf[InMemoryMetricRanking])
    assert(mapped.works.isInstanceOf[TableWorkCatalog])
    assertEquals(
      mapped.works.work(workId).map(_.title),
      Some(
        "A work from the export"
      )
    )
    assertEquals(mapped.works.work("work:v1:absent"), None)
    assert(mapped.metricCache.exists(_ eq f.models.metricCache))
    mapped.close()
    assertEquals(f.closes.get, 1)
  }

  test("the storage settings come from the runtime settings") {
    val s =
      AwsSettings.parse(base ++ Map("SCHOLARCHAIN_NEPTUNE_PORT" -> "9182"))
    val storage = ReadModelSource.storageSettings(s)
    assertEquals(storage.region, "us-east-2")
    assertEquals(storage.dataBucket, "demo-data-bucket")
    assertEquals(storage.neptuneHost, "neptune.example.internal")
    assertEquals(storage.graphRunId, DemoWorld.GraphRunId)
    assertEquals(storage.neptunePort, 9182)
  }

  test("one start-up line carries the counts and no bucket, host or key") {
    val f = new Fixture0
    f.source(AwsSettings.parse(base))
    val lines = f.lines.result()
    assertEquals(lines.size, 1)
    val line = lines.head
    assert(line.contains("objects=12"), line)
    assert(line.contains("bytes=4096"), line)
    assert(line.contains("nodes-work=7,researcher-metrics=9"), line)
    assert(line.contains("worksWithoutTitle=1"), line)
    assert(line.contains(s"runId=${DemoWorld.GraphRunId}"), line)
    assert(!line.contains("demo-data-bucket"), line)
    assert(!line.contains("neptune.example"), line)
    assert(!line.contains("semantic-context"), line)
  }

  test("the metrics agent reads the export's metric cache") {
    val f = new Fixture0
    val backend = AwsBackend.live(f.source)
    val wiring = backend.build(AwsSettings.parse(base))
    try
      assert(wiring.ports.metricCache eq f.models.metricCache)
      assert(wiring.ports.works.work(workId).isDefined)
      val cached = ResearcherMetricCache.get(
        wiring.ports.metricCache,
        DemoWorld.ids.ada,
        DemoWorld.Early,
        Some(DemoWorld.GraphRunId)
      )
      assertEquals(cached.map(_.metrics.researcherId), Some(DemoWorld.ids.ada))
    finally wiring.close()
  }

  test("close runs once on shutdown, however often it is called") {
    val f = new Fixture0
    val wiring = AwsBackend.live(f.source).build(AwsSettings.parse(base))
    assertEquals(f.closes.get, 0)
    wiring.close()
    wiring.close()
    assertEquals(f.closes.get, 1)
  }

  test("a failure after the read models were built closes them once") {
    val f = new Fixture0
    val env = base ++ Map(
      "SCHOLARCHAIN_BEDROCK" -> "on",
      "SCHOLARCHAIN_ANSWER_MODEL" -> "bedrock",
      "SCHOLARCHAIN_BEDROCK_MODEL_ID" -> ModelId
    )
    val backend = AwsBackend.live(
      f.source,
      _ => throw new IllegalStateException("bedrock client refused")
    )
    val e = intercept[IllegalStateException](
      backend.build(AwsSettings.parse(env))
    )
    assertEquals(e.getMessage, "bedrock client refused")
    assertEquals(f.closes.get, 1)
  }

  test("a failure while starting the servers closes the read models once") {
    val f = new Fixture0
    intercept[IllegalStateException](
      AwsRuntime.start(
        AwsSettings.parse(local),
        AwsBackend.live(f.source),
        decorate = _ => throw new IllegalStateException("compose failed")
      )
    )
    assertEquals(f.closes.get, 1)
  }

  test("closing a started runtime closes the read models once") {
    val f = new Fixture0
    val runtime =
      AwsRuntime.start(AwsSettings.parse(local), AwsBackend.live(f.source))
    runtime.close()
    runtime.close()
    assertEquals(f.closes.get, 1)
  }

  test("a bad export stops the runtime with exit 3 and a storage code only") {
    val out = Vector.newBuilder[String]
    val backend = AwsBackend.live(
      ReadModelSource.from(
        (_, _) =>
          throw new StorageException(
            ErrorCode.MissingSuccessMarker,
            "No export objects under s3://demo-data-bucket/semantic-context"
          ),
        _ => ()
      )
    )
    val code = AwsRuntime.run(Array.empty, base, line => out += line, backend)
    assertEquals(code, 3)
    val text = out.result().mkString("\n")
    assert(text.contains("storage-code=MissingSuccessMarker"), text)
    assert(!text.contains("demo-data-bucket"), text)
    assert(!text.contains("semantic-context"), text)
  }

  test(
    "no port listens until the read models are built, then /healthz is 200"
  ) {
    val probe = new ServerSocket(0)
    val port = probe.getLocalPort
    probe.close()
    val entered = new CountDownLatch(1)
    val gate = new CountDownLatch(1)
    val demo = new DemoBackend
    val slowBuild = new AwsBackend:
      def build(settings: AwsSettings): AwsWiring =
        entered.countDown()
        gate.await(30, TimeUnit.SECONDS)
        demo.build(settings)
    val env = local ++ Map(
      "SCHOLARCHAIN_RUNTIME_ROLE" -> "slow",
      "SCHOLARCHAIN_SLOW_PORT" -> port.toString
    )
    val result = new AtomicReference[Option[AwsRuntime]](None)
    val starter = new Thread(() =>
      result.set(Some(AwsRuntime.start(AwsSettings.parse(env), slowBuild)))
    )
    starter.start()
    try
      assert(entered.await(10, TimeUnit.SECONDS))
      val url = s"http://127.0.0.1:$port/healthz"
      assert(Try(TestHttp.send("GET", url)).isFailure, "listening before build")
      gate.countDown()
      starter.join(30000)
      val runtime = result.get.getOrElse(fail("runtime did not start"))
      try assertEquals(TestHttp.send("GET", url).status, 200)
      finally runtime.close()
    finally gate.countDown()
  }
