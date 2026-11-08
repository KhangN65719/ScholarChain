package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.core.SourceType
import scholarchain.core.pairing.{BlockKind, BlockStrategy}
import scholarchain.hadoop.pipeline.BlockMember
import scholarchain.hadoop.pipeline.Blocking
import scholarchain.hadoop.pipeline.BoundedBlockSelector
import scholarchain.hadoop.pipeline.RecordingSink
import scholarchain.hadoop.pipeline.TaskSink
import scholarchain.hadoop.pipeline.TextNormalizer

/** The pathological common-name case the specification names: "prevent
  * pathological common-name or title blocks from overwhelming reducers".
  *
  * `BlockingAndCandidateSuite` tests the bound with 50 synthetic members handed
  * straight to `Blocking.bound`. These cases drive the whole map side with
  * `Blocking.emit`, which is what decides how many records reach one reducer,
  * and they use the name that actually floods DBLP.
  *
  * Bounds chosen here, and why. 800 works by one name: DBLP currently
  * disambiguates several hundred people named "Wei Wang", so 800 is the right
  * order of magnitude, it makes the salt fan-out provably insufficient by the
  * pigeonhole principle against 8 buckets and a bound of 50, and 800 works emit
  * about 4,000 records, which runs in milliseconds. 120 members split 60/60
  * across two sources is the smallest split that overruns the default bound of
  * 50 from one source alone.
  */
class AdversarialCommonNameSuite extends FunSuite:
  private val run = AdversarialFixtures.run()
  private val homonymCount = 800

  private def emitHomonyms(count: Int): RecordingSink =
    val sink = new RecordingSink
    (1 to count).foreach { index =>
      val subject = AdversarialFixtures.work(
        key = s"homonym/$index",
        names = Vector(f"Wei Wang $index%04d"),
        title = s"Paper Number $index."
      )
      Blocking.emit(subject, sink, run)
    }
    sink

  private def blockKeys(sink: RecordingSink): Vector[String] =
    sink.recordsLabelled(TaskSink.blockingKeys).map(_._1)

  private def member(id: String, source: SourceType): BlockMember =
    BlockMember(
      kind = BlockKind.Researcher,
      memberId = id,
      source = source,
      displayName = "Wei Wang",
      normalizedName = "wei wang",
      normalizedTitle = "a paper",
      year = Some(2020),
      venueId = Some("venue:v1:dblp:jcdl"),
      affiliations = Vector.empty,
      coauthorNames = Vector.empty,
      externalIds = Map.empty,
      workId = s"work:v1:dblp:$id"
    )

  test("DBLP's own disambiguation is discarded before blocking") {
    // The homonym ordinal is what DBLP uses to keep these people apart, and
    // `stripHomonymOrdinal` removes it before the fold. That is deliberate
    // and documented, and it is also what manufactures the pathological
    // block: 800 distinct researchers arrive at blocking as one name. The
    // tradeoff belongs in the phase report; nothing is asserted here as a
    // defect.
    val names =
      (1 to homonymCount).toVector.map(index => f"Wei Wang $index%04d")
    assertEquals(names.distinct.size, homonymCount)
    assertEquals(names.map(TextNormalizer.nameKey).distinct, Vector("wei wang"))
    assertEquals(
      names.map(TextNormalizer.nameBlockKey).distinct,
      Vector("wang|w")
    )
  }

  test("a selective strategy carries no salt, so one key takes every record") {
    // `BlockStrategy.NameYear` is marked selective, so `Blocking.salted`
    // leaves it alone. A common name in one publication year is therefore
    // delivered whole to a single reducer: one key, 800 records. The bound
    // limits what that reducer keeps, not what it is sent.
    // Owner hadoop-engineer, for the record rather than for repair: the
    // specification asks that pathological blocks not overwhelm reducers,
    // and the shuffle volume on a selective key is unbounded by design.
    val sink = emitHomonyms(homonymCount)
    val nameYear = blockKeys(sink).filter(_.contains("|name-year|"))
    assertEquals(nameYear.distinct, Vector("researcher|name-year|wang|w|2020"))
    assertEquals(nameYear.size, homonymCount)
  }

  test("the salt fan-out is a constant factor, not a bound") {
    // The non-selective strategy is salted across `Blocking.saltBuckets`
    // buckets. With 800 members and 8 buckets, the pigeonhole principle puts
    // at least 100 in some bucket, which is twice the default block bound of
    // 50. Salting reduces the hot key by a factor of 8 and then stops.
    val sink = emitHomonyms(homonymCount)
    val salted = blockKeys(sink).filter(_.contains("|name-initials|"))
    val buckets = salted.groupBy(identity).view.mapValues(_.size).toVector
    assertEquals(salted.size, homonymCount)
    assert(buckets.size <= Blocking.saltBuckets, s"buckets: ${buckets.size}")
    val largest = buckets.map(_._2).max
    assert(largest >= homonymCount / Blocking.saltBuckets, s"largest $largest")
    assert(largest > run.maxBlockSize, s"largest $largest")
    // The initials segment is `ww`, not `w`. `Blocking.researcherKeys` builds
    // this key from `familyToken` plus `initials`, and `TextNormalizer
    // .initials` (:59-60) folds over *every* token including the family name,
    // while `nameBlockKey` (:63-69) uses `tokens.init` and so yields `wang|w`.
    // Two initials conventions coexist in one key space. It is deterministic
    // and harmless -- the family initial is a constant function of the family
    // segment that already precedes it, so it partitions nothing further --
    // but it is why the two key shapes differ. Run 9 corrected this
    // expectation; the earlier `wang|w` was my error, not the module's.
    val prefix = "researcher|name-initials|wang|ww|s"
    assert(salted.forall(_.startsWith(prefix)), salted.head)
    assert(
      salted.forall(key => key.drop(prefix.length).forall(_.isDigit)),
      salted.head
    )
    assert(
      salted.forall(key =>
        key.drop(prefix.length).toInt < Blocking.saltBuckets
      ),
      salted.head
    )
  }

  test("the salt bucket is a pure function of the member ID") {
    // Asked directly, because the salt segment is the one part of a blocking
    // key derived through a digest rather than by folding text, and the gate
    // item "repeated runs with the same input produce equivalent logical
    // output" rests on it. `Blocking.digestBucket` (:172-176) is SHA-256 of
    // the member ID, first two bytes, mod `saltBuckets`: no run ID, no hash
    // seed, no iteration order, no clock. Two independent emissions of one
    // work must therefore produce byte-identical salted keys.
    val subject = AdversarialFixtures.work(
      key = "homonym/salt",
      names = Vector("Wei Wang 0001"),
      title = "A Salted Paper."
    )
    val otherRun =
      AdversarialFixtures.run(runIdentifier = AdversarialFixtures.secondRunId)
    val first = new RecordingSink
    val second = new RecordingSink
    Blocking.emit(subject, first, run)
    // A different run must not move the key, so the second emission uses a
    // different run ID. `Blocking.emit` reads no field of its run context at
    // all, which is what makes the keys run-independent as well as stable.
    Blocking.emit(subject, second, otherRun)
    assertEquals(blockKeys(first), blockKeys(second))
    val saltedKey =
      blockKeys(first).filter(_.contains("|name-initials|")).head
    assert(
      saltedKey.startsWith("researcher|name-initials|wang|ww|s"),
      saltedKey
    )
  }

  test("the bound truncates the output and not the input") {
    // Restating the distinction the specification cares about, with the
    // figures the reducer actually sees. 800 records in, 50 kept, and the
    // group record reports both, so the evidence of the flood survives.
    val selector = new BoundedBlockSelector(run.maxBlockSize)
    (1 to homonymCount).foreach { index =>
      selector.add(member(f"m-$index%04d", SourceType.Dblp))
    }
    val (group, retained) =
      Blocking.boundSelected("researcher|name-year|wang|w|2020", selector, run)
    assertEquals(selector.observed, homonymCount.toLong)
    assertEquals(group.observedSize, homonymCount)
    assertEquals(group.retainedSize, run.maxBlockSize)
    assertEquals(retained.size, run.maxBlockSize)
    assert(group.truncated)
    assertEquals(group.strategy, BlockStrategy.NameYear)
  }

  test("truncating a common-name block can starve a whole source") {
    // DEFECT, owner hadoop-engineer. `BoundedBlockSelector` orders members
    // by `(source.wire, memberId)` and keeps the smallest. Source is the
    // first component, so an oversize block is truncated along source
    // boundaries: 60 DBLP and 60 OpenAlex members at a bound of 50 retain 50
    // DBLP members and no OpenAlex member at all. The block exists to put
    // the same researcher's records from different sources in front of one
    // comparison, and on exactly the blocks that need the bound it can
    // retain one source only, so no cross-source candidate is generated.
    // The retained subset is deterministic, which is what the ordering was
    // chosen for, and it is also source-biased.
    // Required: select within source, or interleave sources, so truncation
    // preserves the cross-source pairing the block is for.
    val members =
      (1 to 60).toVector.map(index =>
        member(f"d-$index%03d", SourceType.Dblp)
      ) ++
        (1 to 60).toVector.map(index =>
          member(f"o-$index%03d", SourceType.OpenAlex)
        )
    val (group, retained) =
      Blocking.bound("researcher|name-year|wang|w|2020", members, run)
    assertEquals(group.observedSize, 120)
    assertEquals(group.retainedSize, 50)
    assert(group.truncated)
    assertEquals(retained.map(_.source).distinct, Vector(SourceType.Dblp))
    assertEquals(retained.count(_.source == SourceType.OpenAlex), 0)
  }

  test("coauthor context is the escape from a common-name block") {
    // The mitigation that works, asserted so a later change cannot remove it
    // quietly. Two papers by two different Wei Wangs land in two different
    // coauthor blocks of one member each.
    val first = AdversarialFixtures.work(
      key = "wang/1",
      names = Vector("Wei Wang 0001", "Michael Ley"),
      title = "First Collaboration."
    )
    val second = AdversarialFixtures.work(
      key = "wang/2",
      names = Vector("Wei Wang 0002", "Ralf Schenkel"),
      title = "Second Collaboration."
    )
    val sink = new RecordingSink
    Blocking.emit(first, sink, run)
    Blocking.emit(second, sink, run)
    val coauthor = blockKeys(sink).filter(_.contains("|name-coauthor|"))
    assertEquals(coauthor.distinct.size, 2)
    assertEquals(coauthor.size, 4)
    assert(coauthor.contains("researcher|name-coauthor|ley|m|wang|w"))
    assert(coauthor.contains("researcher|name-coauthor|schenkel|r|wang|w"))
  }

  test("homonymous authors retain coauthor context by mention identity") {
    val subject = AdversarialFixtures.work(
      key = "wang/3",
      names = Vector("Wei Wang 0003", "Wei Wang 0004", "Wei Wang 0005"),
      title = "A Homonym Collaboration."
    )
    val sink = new RecordingSink
    Blocking.emit(subject, sink, run)
    val keys = blockKeys(sink)
    assertEquals(keys.count(_.contains("|name-coauthor|")), 3)
    // The non-selective block still receives all three.
    assertEquals(keys.count(_.contains("|name-initials|")), 3)
  }
