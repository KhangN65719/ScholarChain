package scholarchain.services

import java.util.UUID
import java.util.concurrent.{
  CountDownLatch,
  Executors,
  RejectedExecutionException,
  ThreadPoolExecutor,
  TimeUnit
}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, DurationInt}

/** Phase 10 repair round 1: bounded pools and isolation (D14), the job and
  * idempotency fixes, the cursor secret and authentication before 404 or 405.
  */
class RepairSuite extends FunSuite:
  override def munitTimeout: Duration = 120.seconds

  private val tokens = Map(
    "auth.queryToken" -> "query-token-0123456789",
    "auth.readToken" -> "read-token-0123456789"
  )
  private val config =
    ServiceConfig.from(tokens + ("retry.backoffMillis" -> "0"))

  private var started = Vector.empty[Rt]
  private def launch(overrides: Map[String, String] = Map.empty): Rt =
    val rt = Fixture.start(overrides)
    synchronized { started = started :+ rt }
    rt

  override def afterAll(): Unit = started.foreach(_.close())

  private def code(thunk: => Any): ErrorCode =
    intercept[ApiException](thunk).code

  private def downstream(
      pool: ThreadPoolExecutor,
      threshold: Int = 2
  ): Downstream =
    new Downstream(
      "evidence",
      config,
      pool,
      new Metrics,
      new CircuitBreaker(threshold, 60000L)
    )

  /** Fills a one-thread, one-slot pool: one task runs, one waits. */
  private def occupy(pool: ThreadPoolExecutor, release: CountDownLatch): Unit =
    val running = new CountDownLatch(1)
    pool.execute(() =>
      running.countDown()
      release.await()
    )
    assert(running.await(5, TimeUnit.SECONDS))
    pool.execute(() => release.await())

  // Bounded pools ------------------------------------------------------------

  test("a bounded pool refuses work once its threads and queue are full") {
    val rejected = new AtomicInteger
    val pool = Threads.bounded("t", 1, 1, () => rejected.incrementAndGet())
    val release = new CountDownLatch(1)
    try
      occupy(pool, release)
      intercept[RejectedExecutionException](pool.execute(() => ()))
      assertEquals(rejected.get, 1)
    finally
      release.countDown()
      pool.shutdownNow()
  }

  test("a full call pool is 503 OVERLOADED and never a breaker failure") {
    val pool = Threads.bounded("t", 1, 1)
    val release = new CountDownLatch(1)
    try
      occupy(pool, release)
      val d = downstream(pool, threshold = 1)
      (1 to 5).foreach { _ =>
        val e = intercept[ApiException](
          d.call(new Deadline(1000), idempotent = true)(1)
        )
        assertEquals(e.code, ErrorCode.Overloaded)
        assertEquals(e.code.status, 503)
        assertEquals(e.headers.get("Retry-After"), Option("1"))
      }
      assertEquals(d.breaker.state, "closed")
    finally
      release.countDown()
      pool.shutdownNow()
  }

  test("a call that timed out in the queue gives its queue slot back") {
    val pool = Threads.bounded("t", 1, 1)
    val release = new CountDownLatch(1)
    val running = new CountDownLatch(1)
    try
      pool.execute(() =>
        running.countDown()
        release.await()
      )
      assert(running.await(5, TimeUnit.SECONDS))
      val d = downstream(pool, threshold = 100)
      assertEquals(
        code(d.call(new Deadline(100), idempotent = false)(1)),
        ErrorCode.Timeout
      )
      assertEquals(pool.getQueue.size, 0)
      assertEquals(
        code(d.call(new Deadline(100), idempotent = false)(1)),
        ErrorCode.Timeout
      )
    finally
      release.countDown()
      pool.shutdownNow()
  }

  test("an interrupted call is not retried and does not trip the breaker") {
    val pool = Executors.newFixedThreadPool(2)
    try
      val d = new Downstream(
        "evidence",
        config,
        pool,
        new Metrics,
        new CircuitBreaker(1, 60000L)
      )
      val calls = new AtomicInteger
      val outcome = new AtomicReference[ErrorCode]
      val caller = new Thread(() =>
        try
          d.call(new Deadline(10000), idempotent = true) {
            calls.incrementAndGet()
            Thread.sleep(5000)
          }
        catch case e: ApiException => outcome.set(e.code)
      )
      caller.start()
      while calls.get == 0 do Thread.sleep(5)
      caller.interrupt()
      caller.join(5000)
      assert(!caller.isAlive)
      assertEquals(outcome.get, ErrorCode.DownstreamUnavailable)
      assertEquals(calls.get, 1)
      assertEquals(d.breaker.state, "closed")
    finally pool.shutdownNow()
  }

  test("the queue capacities are configuration, bounded and validated") {
    assertEquals(config.httpQueueCapacity, 64)
    assertEquals(config.callQueueCapacity, 32)
    assert(ServiceConfig.Keys.contains("http.queueCapacity"))
    assert(ServiceConfig.Keys.contains("calls.queueCapacity"))
    Vector("http.queueCapacity", "calls.queueCapacity").foreach { key =>
      Vector("0", "-1", "100000", "x").foreach { value =>
        intercept[IllegalArgumentException](
          ServiceConfig.from(tokens + (key -> value))
        )
      }
    }
  }

  // Jobs ---------------------------------------------------------------------

  private final class RecordingStore extends JobStore:
    private val inner = new InMemoryJobStore(100)
    val created = mutable.ArrayBuffer.empty[String]
    val removed = mutable.ArrayBuffer.empty[String]

    def create(job: Job): Unit =
      synchronized { created += job.jobId }
      inner.create(job)
    def get(jobId: String): Option[Job] = inner.get(jobId)
    def update(jobId: String)(change: Job => Job): Option[Job] =
      inner.update(jobId)(change)
    def remove(jobId: String): Unit =
      synchronized { removed += jobId }
      inner.remove(jobId)
    def ready(): Unit = ()

  test("a job refused at submission leaves no Failed record behind") {
    val small = ServiceConfig.from(
      tokens ++ Map("jobs.executorSize" -> "1", "jobs.queueCapacity" -> "1")
    )
    val store = new RecordingStore
    val jobs = new JobService(small, store, new Metrics)
    val release = new CountDownLatch(1)
    val request = QueryRequest(
      "q",
      "q",
      None,
      QueryFilters(None, None, None),
      QueryMode.Async,
      None
    )
    def block(r: QueryRequest): QueryOutcome =
      release.await()
      throw new IllegalStateException("released")
    try
      jobs.submit(request, block)
      jobs.submit(request, block)
      val e = intercept[ApiException](jobs.submit(request, block))
      assertEquals(e.code, ErrorCode.Overloaded)
      assertEquals(store.created.size, 3)
      assertEquals(store.removed.toVector, Vector(store.created.last))
      assertEquals(store.get(store.created.last), None)
      assert(store.get(store.created.head).isDefined)
    finally
      release.countDown()
      jobs.close()
  }

  // Cursor secret ------------------------------------------------------------

  test("the cursor secret is validated at configuration load") {
    Vector("", "short", "a" * 15, "bad\u0000secret-padding-1234").foreach { s =>
      intercept[IllegalArgumentException](
        ServiceConfig.from(tokens + ("auth.cursorSecret" -> s))
      )
    }
    val ok =
      ServiceConfig.from(tokens + ("auth.cursorSecret" -> "0123456789abcdef"))
    val codec = CursorCodec(ok.cursorSecret)
    assertEquals(codec.verify(codec.issue("binding", 5), "binding"), 5)
    intercept[IllegalArgumentException](CursorCodec(Some("")))
    intercept[IllegalArgumentException](CursorCodec(Some("short")))
    val random = CursorCodec(None)
    assertEquals(random.verify(random.issue("binding", 7), "binding"), 7)
  }

  // Authentication before 404 and 405 -------------------------------------------

  test("a /v1 path is authenticated before 404 or 405 is decided") {
    val rt = launch()
    val unknown = rt.anonymous(rt.fast, "/v1/no-such-route")
    assertEquals(unknown.status, 401)
    assertEquals(unknown.json("error")("code").str, "UNAUTHORIZED")
    assertEquals(TestHttp.send("DELETE", rt.slow + "/v1/query").status, 401)
    assertEquals(TestHttp.send("DELETE", rt.slow + "/v1/jobs/x").status, 401)
    assertEquals(rt.getFast("/v1/no-such-route").status, 404)
    val queryScope =
      rt.getFast("/v1/no-such-route", Some(rt.runtime.queryToken))
    assertEquals(queryScope.status, 404)
    val wrongMethod = TestHttp.send(
      "DELETE",
      rt.slow + "/v1/query",
      None,
      Map("Authorization" -> s"Bearer ${rt.runtime.queryToken}")
    )
    assertEquals(wrongMethod.status, 405)
    assertEquals(wrongMethod.header("allow"), Some("POST"))
    assertEquals(TestHttp.send("GET", rt.fast + "/nothing").status, 404)
  }

  // Idempotency ---------------------------------------------------------------

  test("a replayed 202 reports the job's current state") {
    val rt = launch()
    val h = Map("Idempotency-Key" -> ("key-" + UUID.randomUUID()))
    val first = rt.ask(Fixture.ada, Map("mode" -> ujson.Str("async")), h)
    assertEquals(first.status, 202, first.body)
    val jobId = first.json("jobId").str
    val deadline = System.nanoTime() + 30L * 1000000000L
    var state = ""
    while state != "succeeded" && System.nanoTime() < deadline do
      Thread.sleep(25)
      state = rt.getSlow(s"/v1/jobs/$jobId").json("state").str
    assertEquals(state, "succeeded")
    val again = rt.ask(Fixture.ada, Map("mode" -> ujson.Str("async")), h)
    assertEquals(again.status, 202, again.body)
    assertEquals(again.json("jobId").str, jobId)
    assertEquals(again.json("state").str, "succeeded")
    assertEquals(again.json("createdAt").str, first.json("createdAt").str)
  }

  test("the idempotency fingerprint is byte-exact") {
    val rt = launch()
    val h = Map("Idempotency-Key" -> ("key-" + UUID.randomUUID()))
    val a = s"""{"question":"${Fixture.ada}","mode":"sync"}"""
    val b = s"""{"mode":"sync","question":"${Fixture.ada}"}"""
    val first = rt.post(a, h)
    assertEquals(first.status, 200, first.body)
    assertEquals(rt.post(a, h).json("queryId").str, first.json("queryId").str)
    val reordered = rt.post(b, h)
    assertEquals(reordered.status, 422, reordered.body)
    assertEquals(
      reordered.json("error")("code").str,
      "IDEMPOTENCY_KEY_REUSED"
    )
  }

  // Isolation (D14) -------------------------------------------------------------

  test(
    "a stuck workflow neither delays an evidence read nor trips its breaker"
  ) {
    val rt = launch(
      Map(
        "fault.workflow" -> "slow",
        "calls.poolSize" -> "1",
        "calls.queueCapacity" -> "1"
      )
    )
    val pool = Executors.newFixedThreadPool(4)
    try
      given ExecutionContext = ExecutionContext.fromExecutor(pool)
      val asks = (1 to 4).map(_ => Future(rt.ask(Fixture.ada)))
      Thread.sleep(500)
      val missing = "evidence:v1:" + ("0" * 64)
      (1 to 10).foreach { _ =>
        val t0 = System.nanoTime()
        val read = rt.getFast(s"/v1/evidence/$missing")
        assertEquals(read.status, 404, read.body)
        assert((System.nanoTime() - t0) / 1000000 < 1000)
      }
      val ready = TestHttp.send("GET", rt.fast + "/readyz")
      assertEquals(ready.status, 200, ready.body)
      val answers = Await.result(Future.sequence(asks), 60.seconds)
      assert(answers.exists(_.status == 503), answers.map(_.status).toString)
      assert(answers.forall(r => r.status == 503 || r.status == 504))
      answers
        .filter(_.status == 503)
        .foreach(r =>
          assertEquals(r.json("error")("code").str, "OVERLOADED", r.body)
        )
    finally pool.shutdownNow()
  }
