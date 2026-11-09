package scholarchain.services

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors
import munit.FunSuite
import scholarchain.rag.{AllowlistedNeptune, CollaborationWorkflow, DemoWorld}

/** Phase 11 repair round 1: the uncaught-exception handler (m-1) and the
  * interrupt and fatal-log branches that had no test (m-8).
  */
class Phase11RepairSuite extends FunSuite:
  private val tokens = Map(
    "auth.queryToken" -> "query-token-0123456789",
    "auth.readToken" -> "read-token-0123456789"
  )
  private val config =
    ServiceConfig.from(tokens + ("retry.backoffMillis" -> "0"))
  private val oneWorker =
    ServiceConfig.from(tokens + ("jobs.executorSize" -> "1"))

  private val canary = "CANARY-SECRET-7f3a9c"
  private val causeCanary = "CANARY-CAUSE-1d8e42"

  private final class CanaryFatal
      extends VirtualMachineError(s"leaked $canary here"):
    initCause(new RuntimeException(causeCanary))

  // slf4j-simple writes to System.err on every call, so redirecting it taps the
  // log. The redirect is global, so it is serialized and always restored.
  private def tapped(body: ByteArrayOutputStream => Unit): String =
    PrintStreamLock.synchronized {
      val buffer = new ByteArrayOutputStream
      val original = System.err
      System.setErr(new PrintStream(buffer, true, UTF_8))
      try body(buffer)
      finally System.setErr(original)
      new String(buffer.toByteArray, UTF_8)
    }

  private object PrintStreamLock

  private def until(what: String)(done: => Boolean): Unit =
    val deadline = System.nanoTime() + 20L * 1000000000L
    while !done && System.nanoTime() < deadline do Thread.sleep(10)
    assert(done, s"timed out waiting for $what")

  private val request = QueryRequest(
    "q",
    "q",
    None,
    QueryFilters(None, None, None),
    QueryMode.Async,
    None
  )

  // m-1 ---------------------------------------------------------------------

  test("m-1: a worker thread dying fatally logs the class and no message") {
    val jobs = new JobService(oneWorker, new InMemoryJobStore(100), new Metrics)
    try
      val text = tapped { buffer =>
        val first = jobs.submit(request, _ => throw new CanaryFatal)
        until("the first job to fail")(jobs.get(first.jobId).state.finished)
        until("the uncaught handler line")(
          new String(buffer.toByteArray, UTF_8).contains("uncaught throwable")
        )
        // The only worker died: the pool must replace it and keep serving.
        val second = jobs.submit(
          request,
          _ => throw new ApiException(ErrorCode.Overloaded, "busy")
        )
        until("the second job to finish")(jobs.get(second.jobId).state.finished)
        assertEquals(jobs.get(second.jobId).state, JobState.Failed)
        assertEquals(
          jobs.get(second.jobId).error.map(_.code),
          Some(ErrorCode.Overloaded)
        )
      }
      assert(text.contains("uncaught throwable"), text)
      assert(text.contains(classOf[CanaryFatal].getName), text)
      assert(!text.contains(canary), "the message reached the log")
      assert(!text.contains(causeCanary), "the cause message reached the log")
      assert(!text.contains("Exception in thread"), "the JDK handler ran")
      assert(!text.contains("\tat "), "a stack trace reached the log")
    finally jobs.close()
  }

  test("m-1: every daemon thread carries the class-only handler") {
    val thread = Threads.daemon("probe").newThread(() => ())
    val handler = thread.getUncaughtExceptionHandler
    assert(!handler.isInstanceOf[ThreadGroup], "the default group handler")
    val text = tapped { _ =>
      handler.uncaughtException(thread, new CanaryFatal)
    }
    assert(text.contains(classOf[CanaryFatal].getName), text)
    assert(!text.contains(canary), text)
    assert(!text.contains(causeCanary), text)
  }

  // m-8 ---------------------------------------------------------------------

  private final class ObservingStore extends JobStore:
    private val inner = new InMemoryJobStore(100)
    @volatile var interruptedAtFailed: Option[Boolean] = None
    def create(job: Job): Unit = inner.create(job)
    def get(jobId: String): Option[Job] = inner.get(jobId)
    def update(jobId: String)(change: Job => Job): Option[Job] =
      val updated = inner.update(jobId)(change)
      if updated.exists(_.state == JobState.Failed) then
        interruptedAtFailed = Some(Thread.currentThread().isInterrupted)
      updated
    def remove(jobId: String): Unit = inner.remove(jobId)
    def ready(): Unit = ()

  test("m-8: an interrupted job fails, restores the flag and has no result") {
    val store = new ObservingStore
    val jobs = new JobService(oneWorker, store, new Metrics)
    try
      val a = jobs.submit(request, _ => throw new InterruptedException("stop"))
      until("the job to finish")(jobs.get(a.jobId).state.finished)
      val job = jobs.get(a.jobId)
      assertEquals(job.state, JobState.Failed)
      assertEquals(job.error.map(_.code), Some(ErrorCode.Internal))
      assertEquals(job.result, None)
      // The flag was set again before the Failed update was written.
      assertEquals(store.interruptedAtFailed, Some(true))
      val e = intercept[ApiException](jobs.result(a.jobId))
      assertEquals(e.code, ErrorCode.JobFailed)
    finally jobs.close()
  }

  private final class InterruptedRecords extends QueryRecordStore:
    private val inner = new InMemoryQueryRecordStore(100)
    def put(record: QueryRecord): Unit =
      throw new InterruptedException("record store interrupted")
    def byQuery(queryId: String): Option[QueryRecord] = inner.byQuery(queryId)
    def byEvidence(evidenceId: String): Option[QueryRecord] =
      inner.byEvidence(evidenceId)
    def ready(): Unit = ()

  test("m-8: an InterruptedException on the sync path releases the key") {
    val world = DemoWorld.build()
    val ports =
      world.ports.copy(neptune = new AllowlistedNeptune(world.neptune))
    val workflow = new CollaborationWorkflow(ports, world.config)
    val pool = Threads.bounded("m8", 2, 4)
    val idempotency = new InMemoryIdempotency(100)
    val jobs = new JobService(config, new InMemoryJobStore(100), new Metrics)
    val service = new QueryService(
      config,
      new LocalWorkflowPort(workflow, () => ()),
      new Downstream(
        "workflow",
        config,
        pool,
        new Metrics,
        new CircuitBreaker(100, 60000L)
      ),
      new InterruptedRecords,
      idempotency,
      jobs
    )
    try
      val bytes = ujson
        .write(ujson.Obj("question" -> Fixture.ada, "mode" -> "sync"))
        .getBytes(UTF_8)
      val parsed = QueryRequests.parse(bytes, config)
      val resolved = QueryRequests.resolve(parsed, false, config)
      val fp = QueryRequests.fingerprint(bytes, resolved.mode)
      val thrown =
        try
          service.submit(resolved, Some("key-m8"), fp, new Deadline(20000))
          None
        catch case e: InterruptedException => Some(e)
      assertEquals(thrown.map(_.getMessage), Some("record store interrupted"))
      idempotency.claim("key-m8", fp) match
        case Claim.Owner(_, _)  => ()
        case Claim.Replay(done) => fail(s"key still held, done=${done.isDone}")
    finally
      pool.shutdownNow()
      jobs.close()
  }

  test("m-8: Downstream logs the class of a fatal Error, never its message") {
    val pool = Executors.newFixedThreadPool(2)
    try
      val d = new Downstream(
        "evidence",
        config,
        pool,
        new Metrics,
        new CircuitBreaker(100, 60000L)
      )
      val code = scala.collection.mutable.ArrayBuffer.empty[ErrorCode]
      val text = tapped { _ =>
        val e = intercept[ApiException](
          d.call(new Deadline(5000), idempotent = false)(
            throw new CanaryFatal
          )
        )
        code += e.code
        assert(!e.getMessage.contains(canary))
      }
      assertEquals(code.toVector, Vector(ErrorCode.DownstreamUnavailable))
      assert(text.contains("fatal failure"), text)
      assert(text.contains("port=evidence"), text)
      assert(text.contains(classOf[CanaryFatal].getName), text)
      assert(!text.contains(canary), "the message reached the log")
      assert(!text.contains(causeCanary), "the cause message reached the log")
    finally pool.shutdownNow()
  }
