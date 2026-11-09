package scholarchain.services.runtime

import java.util.concurrent.{ExecutorService, ThreadPoolExecutor}
import scala.util.control.NonFatal
import scholarchain.rag.{AllowlistedNeptune, CollaborationWorkflow, DemoWorld}
import scholarchain.services.*

/** The local runtime: both services on loopback ports, wired over the rag
  * workflow, the offline stub model and the storage local fakes (the bounded
  * demo world). No network, no credentials, no cloud.
  *
  * Tokens are fixed placeholders for local runs. Real tokens come from
  * configuration (`SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN` and
  * `SCHOLARCHAIN_SERVICES_AUTH_READ_TOKEN`); Phase 12 injects them from AWS
  * Secrets Manager.
  *
  * Override keys of `LocalRuntime.start(overrides)`:
  *
  *   - Any `ServiceConfig.Keys` entry, such as `metrics.enabled`,
  *     `http.bodyLimitBytes`, `fast.deadlineMillis`, `breaker.failureThreshold`
  *     or `graph.defaultWindow`. An unknown key is an error.
  *   - `fault.evidence`, `fault.graph`, `fault.explain` with `slow`, `fail` or
  *     `empty`.
  *   - `fault.workflow` with `slow` or `fail`.
  *   - `fault.slowMillis`, how long a `slow` port sleeps (default 60000).
  *
  * `slow` makes the port exceed its deadline (504), `fail` makes it throw (503,
  * then an open breaker), `empty` makes it answer with nothing (404 or no
  * neighbors). Faults hit the port calls of requests and of `/readyz`, never
  * `/healthz`.
  *
  * Local defaults differ from the production defaults only where a local run
  * needs them: shorter deadlines (fast 1500 ms, slow 3000 ms, ready 500 ms) so
  * a timeout test is quick, and the demo graph window `2000-2009` as the
  * default window of graph reads.
  *
  * Capacity is bounded everywhere. Each service has its own call pool
  * (`calls.poolSize` threads, `calls.queueCapacity` waiting calls) and its own
  * HTTP executor (`http.threads`, `http.queueCapacity`). A full call pool is
  * 503 `OVERLOADED` and counts against no breaker; a slow workflow call can
  * hold only slow-service threads, never an evidence, graph or explain read.
  */
final class LocalRuntime private (
    fast: ServiceServer,
    slow: ServiceServer,
    val queryToken: String,
    val readToken: String,
    val config: ServiceConfig,
    closers: Vector[() => Unit]
):
  def fastUrl: String = fast.baseUrl
  def slowUrl: String = slow.baseUrl
  def tokens: Map[String, String] =
    Map("query" -> queryToken, "read" -> readToken)

  def close(): Unit =
    closers.foreach(c =>
      try c()
      catch case NonFatal(_) => ()
    )

object LocalRuntime:
  val QueryTokenPlaceholder = "local-placeholder-query-token-0001"
  val ReadTokenPlaceholder = "local-placeholder-read-token-0002"

  private val defaults = Map(
    "auth.queryToken" -> QueryTokenPlaceholder,
    "auth.readToken" -> ReadTokenPlaceholder,
    "fast.deadlineMillis" -> "1500",
    "slow.deadlineMillis" -> "3000",
    "ready.deadlineMillis" -> "500",
    "graph.defaultWindow" -> "2000-2009"
  )

  private val faultKeys = Set(
    "fault.evidence",
    "fault.graph",
    "fault.explain",
    "fault.workflow",
    "fault.slowMillis"
  )

  private def gate(
      overrides: Map[String, String],
      port: String,
      slowMillis: Long,
      allowEmpty: Boolean
  ): FaultGate =
    val fault = overrides.get(s"fault.$port") match
      case None                        => Fault.Off
      case Some("slow")                => Fault.Slow
      case Some("fail")                => Fault.Fail
      case Some("empty") if allowEmpty => Fault.Empty
      case Some(_)                     =>
        throw new IllegalArgumentException(s"Invalid value for fault.$port")
    new FaultGate(fault, slowMillis)

  def start(overrides: Map[String, String] = Map.empty): LocalRuntime =
    val unknown = overrides.keys.filter(k =>
      k.startsWith("fault.") && !faultKeys.contains(k)
    )
    if unknown.nonEmpty then
      throw new IllegalArgumentException(
        s"Unknown fault keys: ${unknown.toVector.sorted.mkString(", ")}"
      )
    val configValues = overrides.filterNot(_._1.startsWith("fault."))
    val config = ServiceConfig.from(defaults ++ configValues)
    val slowMillis = overrides
      .get("fault.slowMillis")
      .map(v =>
        v.toLongOption
          .filter(n => n >= 1 && n <= 600000L)
          .getOrElse(
            throw new IllegalArgumentException(
              "Invalid value for fault.slowMillis"
            )
          )
      )
      .getOrElse(60000L)

    val world = DemoWorld.build()
    val neptune =
      new SynchronizedNeptune(new AllowlistedNeptune(world.neptune))
    val ports = world.ports.copy(neptune = neptune)
    val workflow = new CollaborationWorkflow(ports, world.config)
    val records = new InMemoryQueryRecordStore(config.recordsRetained)

    val workflowPort = new FaultyWorkflow(
      new LocalWorkflowPort(
        workflow,
        () =>
          ports.sessions.get("SESSION#ready", "TURN#000000").foreach(_ => ())
      ),
      gate(overrides, "workflow", slowMillis, allowEmpty = false)
    )
    val evidencePort = new FaultyEvidence(
      new StoreEvidencePort(records, ports.bundles),
      gate(overrides, "evidence", slowMillis, allowEmpty = true)
    )
    val explainPort = new FaultyExplain(
      new StoreExplainPort(records, ports.sessions, ports.bundles),
      gate(overrides, "explain", slowMillis, allowEmpty = true)
    )
    val graphPort = new FaultyGraph(
      new StoreGraphPort(ports.directory, neptune),
      gate(overrides, "graph", slowMillis, allowEmpty = true)
    )
    val jobStore = new InMemoryJobStore(config.jobsRetained)
    val idempotency = new InMemoryIdempotency(config.idempotencyEntries)

    val metrics = new Metrics
    // One bounded call pool per service. The fast service's evidence, graph and
    // explain calls never queue behind the slow service's workflow calls, and a
    // full pool or queue is a 503 OVERLOADED, not a wait.
    def callPool(service: String): ThreadPoolExecutor =
      Threads.bounded(
        s"calls-$service",
        config.callPoolSize,
        config.callQueueCapacity,
        () => metrics.inc("calls_rejected_total", "service" -> service)
      )
    val fastCalls = callPool("fast")
    val slowCalls = callPool("slow")
    def downstream(name: String, pool: ExecutorService): Downstream =
      new Downstream(
        name,
        config,
        pool,
        metrics,
        new CircuitBreaker(
          config.breakerFailureThreshold,
          config.breakerOpenMillis
        )
      )
    val workflowCall = downstream("workflow", slowCalls)
    val jobsCall = downstream("jobs", slowCalls)
    val idempotencyCall = downstream("idempotency", slowCalls)
    val evidenceCall = downstream("evidence", fastCalls)
    val graphCall = downstream("graph", fastCalls)
    val explainCall = downstream("explain", fastCalls)

    val auth = new Authenticator(config.queryToken, config.readToken)
    val cursors = CursorCodec(config.cursorSecret)
    val jobs = new JobService(config, jobStore, metrics)
    val queries = new QueryService(
      config,
      workflowPort,
      workflowCall,
      records,
      idempotency,
      jobs
    )
    val fastOps = new OpsService(
      "fast",
      Vector(
        Probe("evidence", evidenceCall, () => evidencePort.ready()),
        Probe("graph", graphCall, () => graphPort.ready()),
        Probe("explain", explainCall, () => explainPort.ready())
      ),
      config,
      metrics
    )
    val slowOps = new OpsService(
      "slow",
      Vector(
        Probe("workflow", workflowCall, () => workflowPort.ready()),
        Probe("jobs", jobsCall, () => jobStore.ready()),
        Probe("idempotency", idempotencyCall, () => idempotency.ready())
      ),
      config,
      metrics
    )
    val fastRoutes = Routes.fast(
      config,
      new EvidenceService(evidencePort, evidenceCall, config),
      new GraphService(graphPort, graphCall, config, cursors),
      new ExplainService(explainPort, explainCall),
      fastOps
    )
    val slowRoutes = Routes.slow(config, queries, jobs, slowOps)

    val closers = Vector.newBuilder[() => Unit]
    closers += (() => fastCalls.shutdownNow())
    closers += (() => slowCalls.shutdownNow())
    closers += (() => jobs.close())
    try
      val fastServer = ServiceServer.start(
        "fast",
        new Router(fastRoutes, auth),
        config,
        metrics
      )
      closers += (() => fastServer.close())
      val slowServer = ServiceServer.start(
        "slow",
        new Router(slowRoutes, auth),
        config,
        metrics
      )
      closers += (() => slowServer.close())
      new LocalRuntime(
        fastServer,
        slowServer,
        config.queryToken,
        config.readToken,
        config,
        closers.result().reverse
      )
    catch
      case NonFatal(e) =>
        closers
          .result()
          .reverse
          .foreach(c =>
            try c()
            catch case NonFatal(_) => ()
          )
        throw e

  def main(args: Array[String]): Unit =
    val runtime = start()
    sys.addShutdownHook(runtime.close())
    println(s"fast service: ${runtime.fastUrl}")
    println(s"slow service: ${runtime.slowUrl}")
    println("tokens: the local placeholders in LocalRuntime")
    Thread.currentThread().join()
