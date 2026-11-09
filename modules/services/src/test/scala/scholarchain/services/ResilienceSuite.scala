package scholarchain.services

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite

class ResilienceSuite extends FunSuite:
  private val config = ServiceConfig.from(
    Map(
      "auth.queryToken" -> "query-token-0123456789",
      "auth.readToken" -> "read-token-0123456789",
      "retry.backoffMillis" -> "0",
      "breaker.failureThreshold" -> "5"
    )
  )
  private val pool = Executors.newFixedThreadPool(4)
  override def afterAll(): Unit = pool.shutdownNow()

  private def downstream(
      threshold: Int = 5,
      clock: () => Long = () => System.currentTimeMillis()
  ): Downstream =
    new Downstream(
      "evidence",
      config,
      pool,
      new Metrics,
      new CircuitBreaker(threshold, 1000L, clock)
    )

  private def code(thunk: => Any): ErrorCode =
    intercept[ApiException](thunk).code

  test("a deadline counts down and never goes below zero") {
    var now = 0L
    val d = new Deadline(100, () => now)
    assertEquals(d.remainingMillis, 100L)
    now = 60L * 1000000L
    assertEquals(d.remainingMillis, 40L)
    now = 500L * 1000000L
    assertEquals(d.remainingMillis, 0L)
  }

  test("the breaker opens after the threshold and half-opens after the wait") {
    var now = 0L
    val b = new CircuitBreaker(3, 1000L, () => now)
    assertEquals(b.state, "closed")
    b.failure()
    b.failure()
    assertEquals(b.state, "closed")
    b.failure()
    assertEquals(b.state, "open")
    assert(!b.tryAcquire())
    now = 1000L
    assertEquals(b.state, "half-open")
    assert(b.tryAcquire(), "one trial call is allowed")
    assert(!b.tryAcquire(), "only one trial call")
    b.failure()
    assertEquals(b.state, "open")
    now = 2500L
    assert(b.tryAcquire())
    b.success()
    assertEquals(b.state, "closed")
  }

  test("a success resets the failure count") {
    val b = new CircuitBreaker(2, 1000L)
    b.failure()
    b.success()
    b.failure()
    assertEquals(b.state, "closed")
  }

  test("a successful call returns its value") {
    assertEquals(downstream().call(new Deadline(1000), true)(42), 42)
  }

  test("an idempotent read is retried up to the configured count") {
    val calls = new AtomicInteger
    val e = code(
      downstream().call(new Deadline(5000), idempotent = true) {
        calls.incrementAndGet()
        throw new IllegalStateException("boom")
      }
    )
    assertEquals(e, ErrorCode.DownstreamUnavailable)
    assertEquals(calls.get, 1 + config.maxRetries)
  }

  test("a non-idempotent call is never retried") {
    val calls = new AtomicInteger
    val e = code(
      downstream().call(new Deadline(5000), idempotent = false) {
        calls.incrementAndGet()
        throw new IllegalStateException("boom")
      }
    )
    assertEquals(e, ErrorCode.DownstreamUnavailable)
    assertEquals(calls.get, 1)
  }

  test("a call that outlives its deadline is 504 and is not retried") {
    val calls = new AtomicInteger
    val started = System.nanoTime()
    val e = code(
      downstream().call(new Deadline(100), idempotent = true) {
        calls.incrementAndGet()
        Thread.sleep(5000)
      }
    )
    val millis = (System.nanoTime() - started) / 1000000
    assertEquals(e, ErrorCode.Timeout)
    assertEquals(calls.get, 1)
    assert(millis < 2000, s"waited $millis ms")
  }

  test("a domain error passes through and does not count as a failure") {
    val d = downstream(threshold = 1)
    val e = code(
      d.call(new Deadline(1000), true)(
        throw new ApiException(ErrorCode.NotFound, "missing")
      )
    )
    assertEquals(e, ErrorCode.NotFound)
    assertEquals(d.breaker.state, "closed")
  }

  test(
    "repeated failures open the breaker, then calls are 503 without running"
  ) {
    val d = downstream(threshold = 2)
    val calls = new AtomicInteger
    def fail() = d.call(new Deadline(1000), idempotent = false) {
      calls.incrementAndGet()
      throw new IllegalStateException("boom")
    }
    assertEquals(code(fail()), ErrorCode.DownstreamUnavailable)
    assertEquals(code(fail()), ErrorCode.DownstreamUnavailable)
    assertEquals(d.breaker.state, "open")
    val before = calls.get
    assertEquals(code(fail()), ErrorCode.CircuitOpen)
    assertEquals(calls.get, before)
  }

  test("the downstream failure text never reaches the caller") {
    val e = intercept[ApiException](
      downstream().call(new Deadline(1000), false)(
        throw new IllegalStateException("jdbc:secret@host:5432 stack trace")
      )
    )
    assert(!e.getMessage.contains("jdbc"))
    assert(!e.getMessage.contains("secret"))
  }
