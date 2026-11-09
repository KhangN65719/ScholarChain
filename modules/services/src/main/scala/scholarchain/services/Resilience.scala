package scholarchain.services

import java.util.concurrent.{
  Callable,
  ExecutionException,
  ExecutorService,
  Future,
  RejectedExecutionException,
  ThreadPoolExecutor,
  TimeUnit,
  TimeoutException
}

/** The time one request may still spend. Every downstream call takes the
  * remaining budget, so a request never waits longer than its service deadline
  * in total, retries included.
  */
final class Deadline(budgetMillis: Long, nanoNow: () => Long = System.nanoTime):
  private val end = nanoNow() + budgetMillis * 1000000L

  def remainingMillis: Long = math.max(0L, (end - nanoNow()) / 1000000L)

/** Opens after `threshold` consecutive failures, refuses calls while open, and
  * lets one trial call through once `openMillis` has passed.
  */
final class CircuitBreaker(
    threshold: Int,
    openMillis: Long,
    nowMillis: () => Long = () => System.currentTimeMillis()
):
  private var failures = 0
  private var openUntil = 0L
  private var trial = false

  def tryAcquire(): Boolean = synchronized {
    if openUntil == 0L then true
    else if nowMillis() >= openUntil then
      if trial then false
      else
        trial = true
        true
    else false
  }

  def success(): Unit = synchronized {
    failures = 0
    openUntil = 0L
    trial = false
  }

  // A call that was refused or interrupted before it ran says nothing about the
  // dependency: it frees a half-open trial slot and counts as neither outcome.
  def abort(): Unit = synchronized {
    trial = false
  }

  def failure(): Unit = synchronized {
    failures += 1
    trial = false
    if failures >= threshold || openUntil != 0L then
      openUntil = nowMillis() + openMillis
  }

  def state: String = synchronized {
    if openUntil == 0L then "closed"
    else if nowMillis() >= openUntil then "half-open"
    else "open"
  }

/** Wraps every call to one downstream port with a deadline, bounded retries for
  * idempotent reads, and a circuit breaker. The caller sees only typed
  * `ApiException`s with fixed messages: a timeout is 504, a failure or an open
  * breaker is 503. The text of a downstream failure is never kept or passed on.
  */
final class Downstream(
    val name: String,
    config: ServiceConfig,
    pool: ExecutorService,
    metrics: Metrics,
    val breaker: CircuitBreaker
):
  private enum Outcome[+A]:
    case Ok(value: A)
    case Domain(error: ApiException)
    case Failed
    case TimedOut
    case Rejected
    case Interrupted

  private def count(outcome: String): Unit =
    metrics.inc("downstream_calls_total", "port" -> name, "outcome" -> outcome)

  // A call that gave up on its future cancels it, which interrupts a running
  // task, and takes a task that never started out of the queue, so a cancelled
  // task does not hold one of the bounded queue slots.
  private def abandon(future: Future[?]): Unit =
    future.cancel(true)
    pool match
      case bounded: ThreadPoolExecutor =>
        bounded.remove(future.asInstanceOf[Runnable])
      case _ => ()

  private def once[A](deadline: Deadline, task: () => A): Outcome[A] =
    val remaining = deadline.remainingMillis
    if remaining <= 0 then Outcome.TimedOut
    else
      val submitted =
        try
          Some(
            pool.submit(
              new Callable[A]:
                def call(): A = task()
            )
          )
        catch case _: RejectedExecutionException => None
      submitted match
        case None         => Outcome.Rejected
        case Some(future) =>
          try Outcome.Ok(future.get(remaining, TimeUnit.MILLISECONDS))
          catch
            case _: TimeoutException =>
              abandon(future)
              Outcome.TimedOut
            case e: ExecutionException =>
              e.getCause match
                case a: ApiException => Outcome.Domain(a)
                case _               => Outcome.Failed
            case _: InterruptedException =>
              abandon(future)
              Thread.currentThread().interrupt()
              Outcome.Interrupted

  private def interrupted(): ApiException =
    new ApiException(
      ErrorCode.DownstreamUnavailable,
      "The request was interrupted"
    )

  // The backoff between two attempts. An interrupt, such as the one a shutdown
  // sends, ends the call: it is never retried.
  private def pause(): Unit =
    try Thread.sleep(config.retryBackoffMillis)
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        count("interrupted")
        throw interrupted()

  def call[A](deadline: Deadline, idempotent: Boolean)(body: => A): A =
    val attempts = if idempotent then 1 + config.maxRetries else 1

    @scala.annotation.tailrec
    def go(attempt: Int): A =
      if !breaker.tryAcquire() then
        count("rejected")
        throw new ApiException(
          ErrorCode.CircuitOpen,
          s"The $name dependency is temporarily unavailable",
          Vector.empty,
          Map("Retry-After" -> "1")
        )
      once(deadline, () => body) match
        case Outcome.Ok(value) =>
          count("ok")
          breaker.success()
          value
        case Outcome.Domain(error) =>
          count("domain")
          breaker.success()
          throw error
        case Outcome.TimedOut =>
          count("timeout")
          breaker.failure()
          throw new ApiException(
            ErrorCode.Timeout,
            s"The $name dependency did not answer within its deadline"
          )
        case Outcome.Rejected =>
          count("overloaded")
          breaker.abort()
          throw new ApiException(
            ErrorCode.Overloaded,
            s"The $name dependency has too many calls waiting",
            Vector.empty,
            Map("Retry-After" -> "1")
          )
        case Outcome.Interrupted =>
          count("interrupted")
          breaker.abort()
          throw interrupted()
        case Outcome.Failed =>
          count("failure")
          breaker.failure()
          if attempt < attempts && deadline.remainingMillis > config.retryBackoffMillis
          then
            pause()
            go(attempt + 1)
          else
            throw new ApiException(
              ErrorCode.DownstreamUnavailable,
              s"The $name dependency failed"
            )

    go(1)
