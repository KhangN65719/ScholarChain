package scholarchain.tests.adversarial

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scholarchain.rag.{
  AllowlistedNeptune,
  CollaborationWorkflow,
  DemoWorld,
  DeterministicStubModel,
  ModelPort,
  ModelRequest,
  ModelResponse,
  Prompts,
  Session,
  WorkflowPorts,
  WorkflowResult
}
import scholarchain.services.{
  Authenticator,
  CircuitBreaker,
  Downstream,
  InMemoryIdempotency,
  InMemoryJobStore,
  InMemoryQueryRecordStore,
  JobService,
  LocalWorkflowPort,
  Metrics,
  OpsService,
  QueryRecord,
  QueryRecordStore,
  QueryService,
  Router,
  Routes,
  ServiceConfig,
  ServiceServer,
  Threads,
  WorkflowPort
}
import scholarchain.storage.Row

/** An Error that `NonFatal` does not match and that is safe to throw in a test.
  */
final class P11Fatal extends VirtualMachineError("p11 test fatal")

/** A query record store whose first `fatalPuts` writes throw a fatal Error. */
final class P11FatalRecords(fatalPuts: Int) extends QueryRecordStore:
  private val inner = new InMemoryQueryRecordStore(100)
  private val left = new AtomicInteger(fatalPuts)
  def put(record: QueryRecord): Unit =
    if left.getAndDecrement() > 0 then throw new P11Fatal
    else inner.put(record)
  def byQuery(queryId: String): Option[QueryRecord] = inner.byQuery(queryId)
  def byEvidence(evidenceId: String): Option[QueryRecord] =
    inner.byEvidence(evidenceId)
  def ready(): Unit = ()

/** Counts how many times the workflow really ran. */
final class P11CountingWorkflow(inner: WorkflowPort) extends WorkflowPort:
  val runs = new AtomicInteger(0)
  def answer(question: String, session: Session): WorkflowResult =
    runs.incrementAndGet()
    inner.answer(question, session)
  def ready(): Unit = inner.ready()

/** The offline stub model, with a hook that may edit every synthesis reply and
  * a record of every request it was sent.
  */
final class P11Model(
    inner: ModelPort = new DeterministicStubModel,
    edit: (ModelRequest, ujson.Value) => Unit = (_, _) => ()
) extends ModelPort:
  val seen = new CopyOnWriteArrayList[ModelRequest]
  def complete(request: ModelRequest): ModelResponse =
    seen.add(request)
    val response = inner.complete(request)
    if request.promptId == Prompts.Synthesis.id then
      val out = ujson.read(response.rawJson)
      edit(request, out)
      response.copy(rawJson = ujson.write(out))
    else response

/** One slow service (query, jobs) over real HTTP on a loopback port, wired like
  * `LocalRuntime` but with seams a black-box test needs: a model, the semantic
  * summaries, a port adjuster (to break Neptune, the audit tables or the vector
  * bucket), a fatal query-record store and config overrides. Uses only public
  * production classes and local fakes.
  */
final class P11Rig(
    model: ModelPort = new DeterministicStubModel,
    summaries: Vector[Row] = DemoWorld.defaultSummaries,
    adjust: (DemoWorld, WorkflowPorts) => WorkflowPorts = (_, p) => p,
    fatalPuts: Int = 0,
    settings: Map[String, String] = Map.empty
):
  val config: ServiceConfig = ServiceConfig.from(
    Map(
      "auth.queryToken" -> P11Rig.QueryToken,
      "auth.readToken" -> P11Rig.ReadToken,
      "auth.cursorSecret" -> P11Rig.CursorSecret,
      "retry.backoffMillis" -> "0",
      "slow.deadlineMillis" -> "8000"
    ) ++ settings
  )
  private val world = DemoWorld.build(model = model, summaries = summaries)
  private val ports = adjust(
    world,
    world.ports.copy(neptune = new AllowlistedNeptune(world.neptune))
  )
  private val workflow = new CollaborationWorkflow(ports, world.config)
  private val metrics = new Metrics
  private val pool = Threads.bounded("p11-calls", 4, 16)
  private val records = new P11FatalRecords(fatalPuts)
  private val counting =
    new P11CountingWorkflow(new LocalWorkflowPort(workflow, () => ()))
  val idempotency = new InMemoryIdempotency(100)
  val jobs = new JobService(config, new InMemoryJobStore(100), metrics)
  private val downstream = new Downstream(
    "workflow",
    config,
    pool,
    metrics,
    new CircuitBreaker(100, 60000L)
  )
  private val queries =
    new QueryService(config, counting, downstream, records, idempotency, jobs)
  private val ops = new OpsService("slow", Vector.empty, config, metrics)
  private val router = new Router(
    Routes.slow(config, queries, jobs, ops),
    new Authenticator(config.queryToken, config.readToken)
  )
  private val server = ServiceServer.start("slow", router, config, metrics)

  def workflowRuns: Int = counting.runs.get

  def url: String = server.baseUrl

  def queryBody(
      question: String,
      extra: Map[String, ujson.Value] = Map.empty
  ): String =
    val o = ujson.Obj("question" -> question)
    extra.foreach((k, v) => o(k) = v)
    ujson.write(o)

  def post(
      body: String,
      headers: Map[String, String] = Map.empty,
      token: Option[String] = Some(P11Rig.QueryToken)
  ): Resp =
    val auth = token.map(t => "Authorization" -> s"Bearer $t").toMap
    Http.send(
      "POST",
      url + "/v1/query",
      Some(body),
      auth ++ Map("Content-Type" -> "application/json") ++ headers
    )

  def get(path: String, token: Option[String] = Some(P11Rig.ReadToken)): Resp =
    val auth = token.map(t => "Authorization" -> s"Bearer $t").toMap
    Http.send("GET", url + path, None, auth)

  /** Polls a job until it is no longer queued or running. */
  def awaitJob(jobId: String, limitMillis: Long = 20000L): Resp =
    val deadline = System.nanoTime() + limitMillis * 1000000L
    var last = get(s"/v1/jobs/$jobId")
    while last.status == 200 &&
      Set("queued", "running").contains(last.json("state").str) &&
      System.nanoTime() < deadline
    do
      Thread.sleep(25)
      last = get(s"/v1/jobs/$jobId")
    last

  def close(): Unit =
    try server.close()
    finally
      pool.shutdownNow()
      jobs.close()

object P11Rig:
  val QueryToken = "p11-query-token-secretvalue-0123456789"
  val ReadToken = "p11-read-token-secretvalue-9876543210"
  val CursorSecret = "p11-cursor-secretvalue-abcdef0123456789"

  def typedCode(r: Resp): String = r.json("error")("code").str
