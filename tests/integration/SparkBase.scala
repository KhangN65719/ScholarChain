package scholarchain.tests.analytics

import munit.FunSuite
import scala.concurrent.duration.*

// A pipeline run takes about a minute under local Spark, so the default munit
// limit of 30 seconds per test cannot hold.
abstract class SparkBase extends FunSuite:
  override def munitTimeout: Duration = 30.minutes

  // The output of a run that must have succeeded.
  def done(run: RunResult): Output =
    assertEquals(run.code, 0, run.text)
    run.out

  def count(out: Output, name: String): Long =
    out.json("run-manifest.json")("counts")(name).num.toLong

  def close(obtained: Double, expected: Double, clue: String): Unit =
    assertEqualsDouble(obtained, expected, 1e-9, clue)

  def works(keys: String*): Set[String] = keys.map(Ids.work).toSet
