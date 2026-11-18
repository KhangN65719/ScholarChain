package scholarchain.services

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import munit.FunSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scholarchain.rag.{
  AllowlistedNeptune,
  DemoWorld,
  DeterministicStubModel,
  ModelPort,
  RetrievalMode,
  Session,
  WorkflowResult
}
import scholarchain.services.runtime.*
import scholarchain.storage.{DynamoTable, DynamoTableSpec, Item}

/** Counts reads of one table and can make them fail. */
final class ProbeTable(inner: DynamoTable) extends DynamoTable:
  val gets = new AtomicInteger(0)
  val failing = new AtomicBoolean(false)

  def spec: DynamoTableSpec = inner.spec
  def putIfAbsent(item: Item): Unit = inner.putIfAbsent(item)
  def putIfVersion(item: Item, expectedVersion: Long): Unit =
    inner.putIfVersion(item, expectedVersion)
  def get(pk: String, sk: String): Option[Item] =
    gets.incrementAndGet()
    if failing.get then throw new IllegalStateException("table down")
    inner.get(pk, sk)
  def query(pk: String, skPrefix: Option[String], limit: Int): Vector[Item] =
    inner.query(pk, skPrefix, limit)
  def queryIndex(
      index: String,
      pk: String,
      skPrefix: Option[String],
      limit: Int
  ): Vector[Item] = inner.queryIndex(index, pk, skPrefix, limit)

/** A backend over the demo world: no AWS. It records what the runtime asked
  * for, so a test can check the answer model choice and the shutdown.
  */
final class DemoBackend extends AwsBackend:
  val bedrockRequests = new ConcurrentLinkedQueue[BedrockModelRequest]
  val closed = new AtomicBoolean(false)
  @volatile var model: Option[ModelPort] = None
  @volatile var sessions: Option[ProbeTable] = None

  def build(settings: AwsSettings): AwsWiring =
    val chosen = AwsBackend.answerModel(
      settings,
      request =>
        bedrockRequests.add(request)
        new DeterministicStubModel
    )
    model = Some(chosen)
    val world = DemoWorld.build(model = chosen)
    val probe = new ProbeTable(world.ports.sessions)
    sessions = Some(probe)
    val ports = world.ports.copy(
      neptune = new SynchronizedNeptune(new AllowlistedNeptune(world.neptune)),
      sessions = probe
    )
    AwsWiring(ports, () => closed.set(true))

/** Records the retrieval mode of every workflow call. */
final class RecordingPort(inner: WorkflowPort) extends WorkflowPort:
  val modes = new ConcurrentLinkedQueue[RetrievalMode]
  def answer(question: String, session: Session): WorkflowResult =
    inner.answer(question, session)
  override def answer(
      question: String,
      session: Session,
      mode: RetrievalMode
  ): WorkflowResult =
    modes.add(mode)
    inner.answer(question, session, mode)
  def ready(): Unit = inner.ready()

class AwsRuntimeSuite extends FunSuite:
  import AwsTestEnv.*

  override def munitTimeout: Duration = 120.seconds

  private var started = Vector.empty[AwsRuntime]

  // Every launch owns its capture queue. A runtime of an earlier test stays up
  // until afterAll, and the server writes its `query_request` line after the
  // response is sent, so a late line of an earlier request must never reach the
  // queue of a later test.
  private final class Started(
      val runtime: AwsRuntime,
      val backend: DemoBackend,
      val recorder: RecordingPort,
      val lines: ConcurrentLinkedQueue[String]
  ):
    def slow: String = runtime.slowUrl.get
    def fast: String = runtime.fastUrl.get
    def post(
        fields: Map[String, ujson.Value],
        question: String = Fixture.ada
    ): Resp =
      val o = ujson.Obj("question" -> question)
      fields.foreach((k, v) => o(k) = v)
      TestHttp.send(
        "POST",
        slow + "/v1/query",
        Some(ujson.write(o)),
        Map(
          "Authorization" -> s"Bearer $QueryToken",
          "Content-Type" -> "application/json"
        )
      )
    def get(base: String, path: String, token: String = ReadToken): Resp =
      TestHttp.send(
        "GET",
        base + path,
        None,
        Map("Authorization" -> s"Bearer $token")
      )

  private def launch(extra: Map[String, String] = Map.empty): Started =
    val lines = new ConcurrentLinkedQueue[String]
    val backend = new DemoBackend
    var recorder: RecordingPort = null
    val runtime = AwsRuntime.start(
      AwsSettings.parse(local ++ extra),
      backend,
      new RequestLog(line => lines.add(line)),
      port =>
        recorder = new RecordingPort(port)
        recorder
    )
    synchronized { started = started :+ runtime }
    new Started(runtime, backend, recorder, lines)

  override def afterAll(): Unit = started.foreach(_.close())

  // Waits for `count` lines of this launch, then a short settle so a duplicate
  // line would still be counted.
  private def eventuallyLines(s: Started, count: Int): Vector[ujson.Value] =
    val deadline = System.nanoTime() + 5L * 1000000000L
    while s.lines.size < count && System.nanoTime() < deadline do
      Thread.sleep(10)
    Thread.sleep(200)
    s.lines.asScala.toVector.map(ujson.read(_))

  // Health --------------------------------------------------------------------

  test("/healthz is 200 without a token on both ports") {
    val s = launch()
    Vector(s.fast -> "fast", s.slow -> "slow").foreach { (base, name) =>
      val r = TestHttp.send("GET", base + "/healthz")
      assertEquals(r.status, 200, r.body)
      assertEquals(r.json("status").str, "live")
      assertEquals(r.json("service").str, name)
    }
  }

  test("/healthz makes no downstream call; /readyz does and reports it down") {
    val s = launch()
    val table = s.backend.sessions.get
    val before = table.gets.get
    (1 to 5).foreach { _ =>
      assertEquals(TestHttp.send("GET", s.slow + "/healthz").status, 200)
      assertEquals(TestHttp.send("GET", s.fast + "/healthz").status, 200)
    }
    assertEquals(table.gets.get, before)
    table.failing.set(true)
    assertEquals(TestHttp.send("GET", s.slow + "/healthz").status, 200)
    val ready = TestHttp.send("GET", s.slow + "/readyz")
    assertEquals(ready.status, 503, ready.body)
    assertEquals(ready.json("status").str, "not-ready")
    assert(table.gets.get > before)
  }

  // Retrieval mode ------------------------------------------------------------

  test("each retrievalMode value reaches the workflow; absent is fused") {
    val s = launch()
    Vector("graph", "vector", "fused").foreach { wire =>
      val r = s.post(Map("retrievalMode" -> ujson.Str(wire)))
      assertEquals(r.status, 200, s"$wire: ${r.body}")
    }
    assertEquals(s.post(Map.empty).status, 200)
    assertEquals(
      s.recorder.modes.asScala.toVector,
      Vector(
        RetrievalMode.Graph,
        RetrievalMode.Vector,
        RetrievalMode.Fused,
        RetrievalMode.Fused
      )
    )
  }

  test("an absent retrievalMode leaves the response shape unchanged") {
    val s = launch()
    val plain = s.post(Map.empty)
    val fused = s.post(Map("retrievalMode" -> ujson.Str("fused")))
    assertEquals(plain.status, 200)
    assertEquals(plain.json.obj.keySet.toSet, fused.json.obj.keySet.toSet)
    assert(!plain.json.obj.contains("retrievalMode"))
    assertEquals(plain.json("request").obj.keySet.toSet, Set("mode", "window"))
  }

  test("an unknown retrievalMode is 400 in the error shape and never runs") {
    val s = launch()
    Vector[ujson.Value](ujson.Str("hybrid"), ujson.Str("Graph"), ujson.Num(2))
      .foreach { v =>
        val r = s.post(Map("retrievalMode" -> v))
        assertEquals(r.status, 400, r.body)
        val e = r.json("error")
        assertEquals(e("code").str, "INVALID_REQUEST")
        assertEquals(
          e.obj.keySet.toSet,
          Set("code", "message", "traceId", "requestId", "details")
        )
      }
    assert(s.recorder.modes.isEmpty)
  }

  test("an async query carries its retrieval mode to the workflow") {
    val s = launch()
    val accepted = s.post(
      Map("mode" -> ujson.Str("async"), "retrievalMode" -> ujson.Str("graph"))
    )
    assertEquals(accepted.status, 202, accepted.body)
    val jobId = accepted.json("jobId").str
    val deadline = System.nanoTime() + 30L * 1000000000L
    var state = ""
    while state != "succeeded" && System.nanoTime() < deadline do
      Thread.sleep(25)
      state = s.get(s.slow, s"/v1/jobs/$jobId").json("state").str
    assertEquals(state, "succeeded")
    assertEquals(s.recorder.modes.asScala.toVector, Vector(RetrievalMode.Graph))
  }

  // Latency log ---------------------------------------------------------------

  test("one structured line per query request, with no question text") {
    val s = launch()
    val r = s.post(Map("retrievalMode" -> ujson.Str("graph")))
    assertEquals(r.status, 200, r.body)
    val requestId = r.header("x-request-id").get
    val logged = eventuallyLines(s, 1)
    assertEquals(logged.size, 1)
    assertEquals(
      logged.count(l =>
        l("event").str == "query_request" && l("requestId").str == requestId
      ),
      1
    )
    val line = logged.head
    assertEquals(line("event").str, "query_request")
    assertEquals(line("requestId").str, r.header("x-request-id").get)
    assertEquals(line("mode").str, "graph")
    assertEquals(line("executionMode").str, "sync")
    assertEquals(line("status").num.toInt, 200)
    assert(line("totalMs").num >= 0)
    assert(line("time").str.endsWith("Z"))
    Instant.parse(line("time").str)
    val stages = line("stagesMs").obj
    assertEquals(
      stages.keySet.toSet,
      Set(
        "planning",
        "graph-retrieval",
        "vector-retrieval",
        "fusion",
        "synthesis",
        "verification"
      )
    )
    assertEquals(stages("vector-retrieval").str, "skipped")
    stages.values.foreach {
      case ujson.Str(text) => assertEquals(text, "skipped")
      case ujson.Num(ms)   => assert(ms >= 0)
      case other           => fail(s"unexpected stage value $other")
    }
    val text = ujson.write(line)
    assert(!text.contains("Ada") && !text.contains("coauthors"), text)
    assert(!text.contains(QueryToken))
  }

  test("a refused query logs its status, no mode and no stages") {
    val s = launch()
    val r = s.post(Map("retrievalMode" -> ujson.Str("hybrid")))
    assertEquals(r.status, 400)
    val line = eventuallyLines(s, 1).head
    assertEquals(line("status").num.toInt, 400)
    assertEquals(line("requestId").str, r.header("x-request-id").get)
    assertEquals(line("stagesMs").obj.size, 0)
  }

  test("other routes write no latency line") {
    val s = launch()
    TestHttp.send("GET", s.slow + "/healthz")
    s.get(s.fast, "/metrics")
    Thread.sleep(100)
    assert(s.lines.isEmpty)
  }

  // Composition ---------------------------------------------------------------

  test("the answer model defaults to the stub and builds no Bedrock model") {
    val s = launch()
    assert(s.backend.model.exists(_.isInstanceOf[DeterministicStubModel]))
    assert(s.backend.bedrockRequests.isEmpty)
  }

  test("the bedrock answer model gets the model ID, Region and cap") {
    val s = launch(
      Map(
        "SCHOLARCHAIN_ANSWER_MODEL" -> "bedrock",
        "SCHOLARCHAIN_BEDROCK" -> "on",
        "SCHOLARCHAIN_BEDROCK_MODEL_ID" -> ModelId,
        "SCHOLARCHAIN_BEDROCK_MAX_CALLS" -> "4"
      )
    )
    assertEquals(
      s.backend.bedrockRequests.asScala.toVector,
      Vector(BedrockModelRequest("us-east-2", ModelId, 4))
    )
  }

  test(
    "the fast role starts only the fast service and the slow role only the slow"
  ) {
    val fast = launch(Map("SCHOLARCHAIN_RUNTIME_ROLE" -> "fast"))
    assert(fast.runtime.slowUrl.isEmpty)
    assertEquals(
      TestHttp
        .send("GET", fast.fast + "/healthz")
        .status,
      200
    )
    val slow = launch(Map("SCHOLARCHAIN_RUNTIME_ROLE" -> "slow"))
    assert(slow.runtime.fastUrl.isEmpty)
    assertEquals(slow.post(Map.empty).status, 200)
  }

  test("close releases the backend") {
    val s = launch()
    s.runtime.close()
    assert(s.backend.closed.get)
  }

  // Program -------------------------------------------------------------------

  private def program(
      args: Array[String],
      env: Map[String, String]
  ): (Int, Vector[String]) =
    val out = Vector.newBuilder[String]
    val code = AwsRuntime.run(args, env, line => out += line)
    (code, out.result())

  test("--help lists every setting and touches no AWS") {
    val (code, out) = program(Array("--help"), Map.empty)
    assertEquals(code, 0)
    val text = out.mkString("\n")
    AwsSettings.Docs.foreach(d => assert(text.contains(d.name), d.name))
  }

  test("--check-config prints the non-secret settings and no secret") {
    val (code, out) = program(Array("--check-config"), base)
    assertEquals(code, 0)
    assert(out.contains("CONFIG=OK"))
    val text = out.mkString("\n")
    assert(!text.contains(QueryToken) && !text.contains(ReadToken))
    val (bad, message) =
      program(Array("--check-config"), base - "AWS_REGION")
    assertEquals(bad, 2)
    assert(message.mkString.contains("AWS_REGION"))
  }

  test("a read-model load that fails stops the runtime with exit 3") {
    val backend = AwsBackend.live(
      ReadModelSource.from(
        (_, _) =>
          throw new IllegalStateException(
            "bucket demo-data-bucket AccessDenied"
          ),
        _ => ()
      )
    )
    val out = Vector.newBuilder[String]
    val code = AwsRuntime.run(Array.empty, base, line => out += line, backend)
    assertEquals(code, 3)
    val text = out.result().mkString("\n")
    assert(text.contains("READ_MODELS_FAILED"), text)
    assert(!text.contains("AccessDenied") && !text.contains("demo-data"), text)
  }

  test("a bad role argument or extra arguments exit 2") {
    assertEquals(program(Array("all"), base)._1, 2)
    assertEquals(program(Array("fast", "slow"), base)._1, 2)
    assertEquals(program(Array("--bogus"), base)._1, 2)
  }

  test("run starts the runtime on a backend and reports the ports") {
    val backend = new DemoBackend
    val out = Vector.newBuilder[String]
    var runtime: Option[AwsRuntime] = None
    val code = AwsRuntime.run(
      Array("both"),
      local,
      line => out += line,
      backend,
      r => runtime = Some(r)
    )
    runtime.foreach(r => synchronized { started = started :+ r })
    assertEquals(code, 0)
    assert(out.result().exists(_.startsWith("FAST_PORT=")))
    assert(out.result().exists(_.startsWith("SLOW_PORT=")))
  }
