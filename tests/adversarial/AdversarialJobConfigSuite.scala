package scholarchain.tests.adversarial

import munit.FunSuite
import org.apache.hadoop.conf.Configuration
import scholarchain.hadoop.jobs.JobConfig
import scholarchain.hadoop.pipeline.RunContext

/** Hostile run parameters through the Hadoop job configuration.
  *
  * This suite is also the first use of the root project's widened test
  * classpath: it constructs an `org.apache.hadoop.conf.Configuration`, which
  * needs `hadoop-client-api` for the type and `hadoop-client-runtime` for the
  * shaded XML parser the constructor reaches for. Both were added to the root
  * project at Test scope by the Phase 2 F2 repair and had no caller until now.
  * If this suite fails to compile or fails at construction, the finding belongs
  * to build-engineer, not to hadoop-engineer.
  *
  * `JobWiringSuite` covers the round-trip for well-formed values and both
  * refusal paths. The cases here are the values that survive the round-trip as
  * something else.
  */
class AdversarialJobConfigSuite extends FunSuite:
  private def emptyConfiguration(): Configuration = new Configuration(false)

  private def context(
      snapshots: Map[String, String],
      partitionCount: Int = 4,
      maxBlockSize: Int = 50,
      maxAuthorTeamSize: Int = 50,
      maxPairsPerWork: Int = 10000
  ): RunContext =
    RunContext(
      runId = AdversarialFixtures.runId,
      transformVersion = RunContext.transformVersion,
      datasetSnapshots = snapshots,
      outputRoot = "s3://local-curated-example/curated",
      partitionCount = partitionCount,
      maxBlockSize = maxBlockSize,
      maxAuthorTeamSize = maxAuthorTeamSize,
      maxPairsPerWork = maxPairsPerWork
    )

  test("hyperauthorship bounds survive a configuration round-trip") {
    val run = context(
      RunContext.localSnapshots,
      partitionCount = 64,
      maxBlockSize = 2,
      maxAuthorTeamSize = 3000,
      maxPairsPerWork = 4498500
    )
    val configuration = emptyConfiguration()
    JobConfig.write(configuration, run)
    val restored = JobConfig.read(configuration)
    assertEquals(restored, run)
    assertEquals(restored.maxAuthorTeamSize, 3000)
    assertEquals(restored.maxPairsPerWork, 4498500)
    assertEquals(restored.snapshotId, run.snapshotId)
  }

  test("a snapshot version containing a comma is silently discarded") {
    // DEFECT, owner hadoop-engineer. The snapshot map is serialized as
    // `name=value` joined by commas and parsed back by splitting on both,
    // with no escaping and no validation. A comma inside a version value
    // makes the second half parse as a separate malformed entry, which is
    // dropped. The run reads back a different snapshot set, which changes
    // the snapshot digest, which changes every output path and every
    // provenance record, and nothing reports it.
    // Required: refuse a delimiter-bearing dataset name or version at write
    // time, or encode the map in a form that survives its own values.
    val run = context(Map("dblp" -> "2026-11-01,beta"))
    val configuration = emptyConfiguration()
    JobConfig.write(configuration, run)
    val restored = JobConfig.read(configuration)
    assertEquals(
      restored.datasetSnapshots,
      Map("dblp" -> "2026-11-01")
    )
    assert(restored.datasetSnapshots != run.datasetSnapshots)
    assert(restored.snapshotId != run.snapshotId)
    assert(restored.runPath != run.runPath)
  }

  test("a dataset name containing an equals sign is silently renamed") {
    // The same defect from the other side: the split takes the first `=`, so
    // the name loses its tail and the version gains it. The round-trip
    // produces a plausible-looking snapshot set that pins nothing real.
    val run = context(Map("db=lp" -> "2026-11-01"))
    val configuration = emptyConfiguration()
    JobConfig.write(configuration, run)
    val restored = JobConfig.read(configuration)
    assertEquals(
      restored.datasetSnapshots,
      Map("db" -> "lp=2026-11-01")
    )
  }

  test("a renamed dataset moves the snapshot digest and the output prefix") {
    // INVERTED, because the F12 digest collision is closed. Length framing
    // at `RunContext.scala:58-67` frames the original map as
    // `(5,"db=lp")(10,"2026-11-01")` and the round-tripped one as
    // `(2,"db")(13,"lp=2026-11-01")`, so the digests now differ. The
    // corrected invariant: a distinct snapshot map yields a distinct digest
    // and therefore a distinct output prefix. The run-9 history behind this
    // case lives in test-report.md and is not repeated here.
    //
    // Worth the reviewer's attention, because closing the collision WIDENED
    // the observable consequence of F12's still-open serialization half. The
    // review's point was that `JobSupport.configureOutput:46-51` sets the
    // output path client-side from the original `RunContext` while each
    // task's provenance comes from the round-tripped one. Until now the
    // equals half was invisible there, since the colliding digest made the
    // two agree. It no longer does, so an equals-bearing dataset name would
    // commit records whose `provenance.snapshotId` disagrees with the
    // `snapshot=` segment they are stored under -- the CLAUDE.md provenance
    // violation the review attributed to the comma half alone now reaches
    // this half too. Argues for fixing the serialization half rather than
    // carrying it further; its two open `.fail` twins are at the end of
    // this suite.
    val run = context(Map("db=lp" -> "2026-11-01"))
    val configuration = emptyConfiguration()
    JobConfig.write(configuration, run)
    val restored = JobConfig.read(configuration)
    assert(restored.snapshotId != run.snapshotId, restored.snapshotId)
    assert(restored.runPath != run.runPath, restored.runPath)
    // The digest is a pure function of the map, with no round-trip involved.
    assertEquals(
      restored.snapshotId,
      context(Map("db" -> "lp=2026-11-01")).snapshotId
    )
  }

  test("the renamed dataset breaks the snapshot lookup instead") {
    // Where the rename actually bites: `RunContext.snapshotOf` (:54-58) is a
    // keyed lookup that throws on a miss. After the round-trip the map is
    // keyed "db", so a stage asking for the dataset it was configured with
    // fails outright, and a stage asking for the truncated name receives a
    // corrupted version string as its provenance value. The consequence is a
    // hard failure or a wrong provenance field, not a silent path move.
    val run = context(Map("db=lp" -> "2026-11-01"))
    val configuration = emptyConfiguration()
    JobConfig.write(configuration, run)
    val restored = JobConfig.read(configuration)
    assertEquals(run.snapshotOf("db=lp"), "2026-11-01")
    intercept[IllegalArgumentException] {
      restored.snapshotOf("db=lp")
    }
    assertEquals(restored.snapshotOf("db"), "lp=2026-11-01")
  }

  test("a version containing an equals sign does round-trip") {
    // The bound on the defect above: the value split is limited to two
    // parts, so only the name's delimiter is lossy.
    val run = context(Map("dblp" -> "2026-11-01=rc1"))
    val configuration = emptyConfiguration()
    JobConfig.write(configuration, run)
    val restored = JobConfig.read(configuration)
    assertEquals(restored.datasetSnapshots, run.datasetSnapshots)
    assertEquals(restored.snapshotId, run.snapshotId)
  }

  test(
    "comma-bearing snapshot versions are preserved or explicitly refused".fail
  ) {
    val run = context(Map("dblp" -> "2026-11-01,beta"))
    val configuration = emptyConfiguration()
    val transported = scala.util.Try {
      JobConfig.write(configuration, run)
      JobConfig.read(configuration).datasetSnapshots
    }
    transported match
      case scala.util.Failure(_: IllegalArgumentException) => ()
      case scala.util.Failure(error)                       => throw error
      case scala.util.Success(snapshots)                   =>
        assertEquals(snapshots, run.datasetSnapshots)
  }

  test(
    "equals-bearing dataset names are preserved or explicitly refused".fail
  ) {
    val run = context(Map("db=lp" -> "2026-11-01"))
    val configuration = emptyConfiguration()
    val transported = scala.util.Try {
      JobConfig.write(configuration, run)
      JobConfig.read(configuration).datasetSnapshots
    }
    transported match
      case scala.util.Failure(_: IllegalArgumentException) => ()
      case scala.util.Failure(error)                       => throw error
      case scala.util.Success(snapshots)                   =>
        assertEquals(snapshots, run.datasetSnapshots)
  }

  test("distinct snapshot maps have distinct digests") {
    // `.fail` tag removed: the twin tripped, which is the mechanism working.
    // `hadoop-engineer` closed the F12 digest collision at
    // `RunContext.scala:58-67` by replacing the `s"$dataset=$version\u0000"`
    // interpolation with length framing -- each component digested as a
    // four-byte big-endian length then its UTF-8 bytes, the scheme
    // `StableIds.digestId:183-193` already used. The two maps below now feed
    // `(3,"a=b")(1,"c")` and `(1,"a")(3,"b=c")`, so no two splits of one
    // character sequence can collide. Framing removes the possibility
    // instead of forbidding the character, which is why no write-time
    // `require` was needed and why the two serialization twins immediately
    // above this case stay green: they compare `datasetSnapshots` only and
    // never read `snapshotId`.
    assert(
      context(Map("a=b" -> "c")).snapshotId !=
        context(Map("a" -> "b=c")).snapshotId
    )
  }
