package scholarchain.services

import java.util.concurrent.ConcurrentLinkedQueue
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scholarchain.rag.{
  CollaborationWorkflow,
  ModelAttempt,
  RetrievalMode,
  Session,
  WorkflowConfig,
  WorkflowResult
}
import scholarchain.services.runtime.*

/** The query service logs one line per model attempt of the workflow result. */
class QueryAttemptLogSuite extends FunSuite:
  import AwsTestEnv.*

  private val settings = AwsSettings.parse(local)
  private val config = settings.service

  private final class Fixtures(attempts: Vector[ModelAttempt]):
    val lines = new ConcurrentLinkedQueue[String]
    private val wiring = new DemoBackend().build(settings)
    private val inner = new AwsWorkflowPort(
      new CollaborationWorkflow(
        wiring.ports,
        WorkflowConfig(settings.graphRunId, settings.queryRunId)
      ),
      () => ()
    )
    private val port = new WorkflowPort:
      def answer(question: String, session: Session): WorkflowResult =
        inner.answer(question, session).copy(modelAttempts = attempts)
      override def answer(
          question: String,
          session: Session,
          mode: RetrievalMode
      ): WorkflowResult =
        inner.answer(question, session, mode).copy(modelAttempts = attempts)
      def ready(): Unit = ()

    private val metrics = new Metrics
    private val pool = Threads.bounded("test-calls", 2, 4)
    private val jobs =
      new JobService(config, new InMemoryJobStore(10), metrics)

    def service(sink: String => Unit): QueryService =
      new QueryService(
        config,
        port,
        new Downstream(
          "workflow",
          config,
          pool,
          metrics,
          new CircuitBreaker(5, 1000)
        ),
        new InMemoryQueryRecordStore(10),
        new InMemoryIdempotency(10),
        jobs,
        attemptSink = sink
      )

    def close(): Unit =
      pool.shutdownNow()
      jobs.close()
      wiring.close()

  private def request: QueryRequest =
    val body =
      ujson.write(ujson.Obj("question" -> Fixture.ada)).getBytes("UTF-8")
    QueryRequests.resolve(QueryRequests.parse(body, config), false, config)

  private val attempts = Vector(
    ModelAttempt(
      "synthesis",
      1,
      false,
      Vector("$.confidence: above the maximum"),
      Some("max_tokens"),
      900
    ),
    ModelAttempt("synthesis", 2, true, Vector.empty, Some("end_turn"), 300)
  )

  test("one line per attempt, in order, with the query ID and no text") {
    val f = new Fixtures(attempts)
    try
      val outcome = f
        .service(line => f.lines.add(line))
        .run(request, new Deadline(30000))
      assertEquals(
        f.lines.asScala.toVector,
        attempts.map(_.logLine(outcome.queryId))
      )
      val text = f.lines.asScala.mkString("\n")
      assert(text.contains(s"queryId=${outcome.queryId}"))
      assert(text.contains("stopReason=max_tokens"))
      assert(text.contains("rejections=$.confidence: above the maximum"))
      assert(!text.contains("Ada") && !text.contains("coauthors"), text)
    finally f.close()
  }

  test("a result with no attempt record writes no line") {
    val f = new Fixtures(Vector.empty)
    try
      f.service(line => f.lines.add(line)).run(request, new Deadline(30000))
      assert(f.lines.isEmpty)
    finally f.close()
  }

  test("a failing sink never fails the query") {
    val f = new Fixtures(attempts)
    try
      val outcome = f
        .service(_ => throw new IllegalStateException("sink down"))
        .run(request, new Deadline(30000))
      assert(outcome.queryId.nonEmpty)
    finally f.close()
  }
