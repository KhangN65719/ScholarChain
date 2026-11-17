package scholarchain.services.runtime

import java.util.concurrent.{ExecutorService, ThreadPoolExecutor}
import scala.util.control.NonFatal
import scholarchain.rag.{CollaborationWorkflow, WorkflowConfig}
import scholarchain.services.*

/** The AWS runtime: the fast and slow services over the rag workflow, composed
  * from `AwsSettings` (environment variables) and an `AwsBackend` (DynamoDB, S3
  * Vectors, Titan, the read models and the answer model).
  *
  * Main class: `scholarchain.services.runtime.AwsRuntime`. Arguments: an
  * optional role (`fast`, `slow` or `both`, default `both`), `--help` (prints
  * the settings, exits 0, touches no AWS) or `--check-config` (parses the
  * environment, prints the non-secret settings, exits 0, touches no AWS).
  *
  * Health check for an internal ALB target group: `GET /healthz` on the
  * service's own port (fast 8080, slow 8081 by default). It needs no token,
  * calls no dependency and answers 200 `{"status":"live","service":...}`.
  * Neither port listens until `backend.build` has returned, and that build
  * loads the whole export from S3 (slow), so before it ends a connection is
  * refused and the target group marks the task unhealthy instead of routing to
  * it. `GET /readyz` asks each dependency and is not meant for the target
  * group.
  *
  * Every request to `POST /v1/query` writes one JSON line to the logger
  * `scholarchain.services.latency` (see `RequestLog`).
  *
  * Credentials: the AWS clients use the SDK default provider chain (the ECS
  * task role). The bearer tokens and the cursor secret arrive as environment
  * variables that the ECS secrets block fills from Secrets Manager. Nothing
  * secret is printed.
  *
  * Exit codes: 0 for `--help`, `--check-config` and a clean stop, 2 for a
  * configuration error, 3 when the read models cannot be built (the export is
  * missing or bad, or the data bucket or Neptune settings are refused; the
  * output names a storage code, never AWS text), 1 for any other failure.
  */
final class AwsRuntime private (
    fast: Option[ServiceServer],
    slow: Option[ServiceServer],
    val settings: AwsSettings,
    closers: Vector[() => Unit]
):
  def fastUrl: Option[String] = fast.map(_.baseUrl)
  def slowUrl: Option[String] = slow.map(_.baseUrl)
  def fastPort: Option[Int] = fast.map(_.port)
  def slowPort: Option[Int] = slow.map(_.port)

  def close(): Unit =
    closers.foreach(c =>
      try c()
      catch case NonFatal(_) => ()
    )

object AwsRuntime:
  val Usage: String =
    "usage: AwsRuntime [fast|slow|both] | --help | --check-config"

  /** `decorate` wraps the workflow port; tests use it to record the calls. */
  def start(
      settings: AwsSettings,
      backend: AwsBackend,
      requestLog: RequestLog = RequestLog.default,
      decorate: WorkflowPort => WorkflowPort = identity
  ): AwsRuntime =
    val built = backend.build(settings)
    val wiring = AwsWiring(built.ports, AwsWiring.once(built.close))
    try compose(settings, wiring, requestLog, decorate)
    catch
      case NonFatal(e) =>
        try wiring.close()
        catch case NonFatal(_) => ()
        throw e

  private def compose(
      settings: AwsSettings,
      wiring: AwsWiring,
      requestLog: RequestLog,
      decorate: WorkflowPort => WorkflowPort
  ): AwsRuntime =
    val config = settings.service
    val ports = wiring.ports
    val workflow = new CollaborationWorkflow(
      ports,
      WorkflowConfig(settings.graphRunId, settings.queryRunId)
    )
    val records = new InMemoryQueryRecordStore(config.recordsRetained)
    val workflowPort = decorate(
      new AwsWorkflowPort(
        workflow,
        () =>
          ports.sessions.get("SESSION#ready", "TURN#000000").foreach(_ => ())
      )
    )
    val evidencePort = new StoreEvidencePort(records, ports.bundles)
    val explainPort =
      new StoreExplainPort(records, ports.sessions, ports.bundles)
    val graphPort = new StoreGraphPort(ports.directory, ports.neptune)
    val jobStore = new InMemoryJobStore(config.jobsRetained)
    val idempotency = new InMemoryIdempotency(config.idempotencyEntries)
    val metrics = new Metrics

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
    closers += (() => wiring.close())
    closers += (() => fastCalls.shutdownNow())
    closers += (() => slowCalls.shutdownNow())
    closers += (() => jobs.close())
    def release(): Unit =
      closers
        .result()
        .reverse
        .foreach(c =>
          try c()
          catch case NonFatal(_) => ()
        )
    try
      val fastServer =
        if settings.role.runsFast then
          val s = ServiceServer.start(
            "fast",
            new Router(fastRoutes, auth),
            config,
            metrics,
            settings.bindHost,
            settings.fastPort,
            requestLog
          )
          closers += (() => s.close())
          Some(s)
        else None
      val slowServer =
        if settings.role.runsSlow then
          val s = ServiceServer.start(
            "slow",
            new Router(slowRoutes, auth),
            config,
            metrics,
            settings.bindHost,
            settings.slowPort,
            requestLog
          )
          closers += (() => s.close())
          Some(s)
        else None
      new AwsRuntime(
        fastServer,
        slowServer,
        settings,
        closers.result().reverse
      )
    catch
      case NonFatal(e) =>
        release()
        throw e

  /** The whole program, minus the process exit. Returns the exit code. The
    * `started` hook receives the running runtime; `main` blocks there.
    */
  def run(
      args: Array[String],
      env: Map[String, String],
      out: String => Unit,
      backend: AwsBackend = AwsBackend.live(ReadModelSource.live),
      started: AwsRuntime => Unit = _ => ()
  ): Int =
    args.toVector match
      case Vector("--help") =>
        out(Usage)
        out("Settings (environment variables):")
        out(AwsSettings.helpText)
        0
      case Vector("--check-config") =>
        try
          val settings = AwsSettings.parse(env)
          AwsSettings.summary(settings).foreach(out)
          out("CONFIG=OK")
          0
        catch
          case e: IllegalArgumentException =>
            out(s"CONFIG=INVALID reason=${e.getMessage}")
            2
      case rest if rest.size <= 1 && !rest.exists(_.startsWith("--")) =>
        val settings =
          try Right(AwsSettings.parse(env, rest.headOption))
          catch case e: IllegalArgumentException => Left(e.getMessage)
        settings match
          case Left(message) =>
            out(s"RUNTIME_RESULT=FAILED reason=$message")
            2
          case Right(s) =>
            try
              val runtime = start(s, backend)
              out(s"RUNTIME_STARTED role=${s.role.wire}")
              runtime.fastPort.foreach(p => out(s"FAST_PORT=$p"))
              runtime.slowPort.foreach(p => out(s"SLOW_PORT=$p"))
              started(runtime)
              0
            catch
              case e: ReadModelsUnavailable =>
                out(s"RUNTIME_RESULT=FAILED reason=${e.getMessage}")
                3
              case e: IllegalArgumentException =>
                out(s"RUNTIME_RESULT=FAILED reason=${e.getMessage}")
                2
              case NonFatal(e) =>
                out(
                  s"RUNTIME_RESULT=FAILED reason=${e.getClass.getSimpleName}"
                )
                1
      case _ =>
        out(Usage)
        2

  def main(args: Array[String]): Unit =
    var running: Option[AwsRuntime] = None
    val code = run(
      args,
      sys.env,
      println,
      started = r =>
        running = Some(r)
        sys.addShutdownHook(r.close())
        ()
    )
    if code != 0 || running.isEmpty then sys.exit(code)
    else Thread.currentThread().join()
