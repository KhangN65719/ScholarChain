package scholarchain.core

import scala.util.control.NonFatal

/** Captures a rejection with its whole cause chain so a test can pin a failure
  * to the validation that produced it. A bare `intercept[Exception]` lets one
  * over-broad failure stand in for several distinct invariants.
  */
object Rejection:
  /** Returns the rejection text, or the empty string when nothing was thrown.
    * An empty result never matches a nonempty expectation, so a check that
    * failed to reject at all still fails the assertion.
    */
  def textOf(action: => Any): String =
    try
      action
      ""
    catch case NonFatal(error) => chain(error)

  private def chain(error: Throwable): String =
    Iterator
      .iterate(error)(_.getCause)
      .takeWhile(_ != null)
      .take(8)
      .map(cause => s"${cause.getClass.getName}: ${cause.getMessage}")
      .mkString(" <- ")
