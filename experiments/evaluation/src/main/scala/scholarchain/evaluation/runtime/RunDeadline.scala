package scholarchain.evaluation.runtime

/** The wall-clock stop of an entry point.
  *
  * Two bounds can be given and the earlier one wins:
  *   - `maxMinutes`, counted from the moment the run loop starts (after sbt
  *     start, compile and the graph load);
  *   - an absolute deadline in epoch milliseconds, which the launcher computes
  *     from its own start, so sbt start, compile and the graph load are inside
  *     the window and cannot push the run past the outer kill.
  *
  * The deadline is checked between workflow calls, never inside one, so the run
  * can pass it by one question. The launcher therefore sets the absolute
  * deadline a margin before the outer kill (`run.sh`: 10 minutes, covering the
  * longest single question and the closing writes).
  */
object RunDeadline:
  /** The deadline on the `nanos` clock, or None when no bound is given. */
  def nanosAt(
      maxMinutes: Option[Int],
      absoluteEpochMillis: Option[Long],
      nanos: () => Long,
      wallMillis: () => Long
  ): Option[Long] =
    val now = nanos()
    val wall = wallMillis()
    val relative = maxMinutes.map(m => now + m * 60L * 1000000000L)
    val absolute = absoluteEpochMillis.map(e => now + (e - wall) * 1000000L)
    (relative.toVector ++ absolute.toVector).minOption
