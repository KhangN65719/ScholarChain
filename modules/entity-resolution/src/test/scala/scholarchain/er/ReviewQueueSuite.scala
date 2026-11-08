package scholarchain.er

import scholarchain.core.MatchOutcome
import scholarchain.er.Fixtures.*
import scholarchain.er.matching.*
import scholarchain.er.review.*
import upickle.default.write

class ReviewQueueSuite extends munit.FunSuite:
  private val matcher = Matcher(Policy.v1, runInfo)

  private def pairs(
      count: Int,
      make: Int => (SideRecord, SideRecord)
  ): Vector[(SideRecord, SideRecord)] =
    (1 to count).toVector.map(make)

  private val conflicting = pairs(
    6,
    i =>
      (
        mention(
          f"a$i%02d",
          "Jane Doe",
          externalIds = Map("orcid" -> validOrcidA)
        ),
        mention(
          f"b$i%02d",
          "Jane Doe",
          externalIds = Map("orcid" -> validOrcidB)
        )
      )
  )
  private val agreeing = pairs(
    6,
    i =>
      (
        mention(
          f"c$i%02d",
          "Jane Doe",
          externalIds = Map("orcid" -> validOrcidA)
        ),
        mention(f"d$i%02d", "J. Doe", externalIds = Map("orcid" -> validOrcidA))
      )
  )
  private val different = pairs(
    6,
    i => (mention(f"e$i%02d", "John Smith"), mention(f"f$i%02d", "John Nguyen"))
  )
  private val uncertain = pairs(
    6,
    i =>
      (
        mention(f"g$i%02d", "John Smith", Vector("Alice Wong")),
        mention(f"h$i%02d", "John Smith", Vector("Alice Wong"))
      )
  )
  private val all = conflicting ++ agreeing ++ different ++ uncertain
  private val index =
    RecordIndex(all.flatMap((a, b) => Vector(a.id -> a, b.id -> b)).toMap)
  private val records =
    all.map((a, b) => matcher.decide(MatchInput.of(a, b, "k")))
  private val plan = ReviewPlan("salt-1", 3, 0.05, 0.95, 0.35)

  test("the queue caps each stratum and always covers conflicts") {
    val queue = ReviewQueue.build(records, index, plan)
    val strata = queue.selections.groupBy(_.stratum).view.mapValues(_.size)
    assertEquals(strata(ReviewQueue.ConflictStratum), 3)
    assertEquals(strata(ReviewQueue.UnresolvedStratum), 3)
    assert(strata.keys.exists(_.startsWith("control-accepted")))
    assert(strata.keys.exists(_.startsWith("control-rejected")))
    assert(strata.values.forall(_ <= 3))
  }

  test("selection is deterministic for a salt and varies with the salt") {
    val first = ReviewQueue.build(records, index, plan)
    assertEquals(ReviewQueue.build(records, index, plan), first)
    assertEquals(ReviewQueue.build(records.reverse, index, plan), first)
    val other = ReviewQueue.build(records, index, plan.copy(salt = "salt-2"))
    assertNotEquals(other.items.map(_.itemId), first.items.map(_.itemId))
  }

  test("every item traces to source records and hides the matcher output") {
    val queue = ReviewQueue.build(records, index, plan)
    assert(queue.items.nonEmpty)
    queue.items.foreach { item =>
      Vector(item.left, item.right).foreach { side =>
        assert(side.sourceRecordId.nonEmpty)
        assert(side.sourceUri.nonEmpty)
        assert(side.snapshotId.nonEmpty)
      }
      val json = write(item)
      Vector(
        "ACCEPTED",
        "REJECTED",
        "UNRESOLVED",
        "CONFLICT",
        "score",
        "confidence"
      )
        .foreach(word => assert(!json.contains(word), s"$word leaked: $json"))
    }
  }

  test("selections join to items by ID and record why a pair was queued") {
    val queue = ReviewQueue.build(records, index, plan)
    assertEquals(queue.items.map(_.itemId), queue.selections.map(_.itemId))
    queue.selections.foreach { selection =>
      assertEquals(selection.policyVersion, Policy.v1.version)
      assert(selection.outcome.nonEmpty && selection.path.nonEmpty)
    }
    assertEquals(queue.items.map(_.itemId).distinct.size, queue.items.size)
  }

  test("aggregator ID disagreements are queued as their own stratum") {
    val a = mention(
      "p1",
      "Jane Doe",
      sharedCoauthors,
      Vector("MIT"),
      Map("openalex" -> "A1")
    )
    val b = mention(
      "p2",
      "Jane Doe",
      sharedCoauthors,
      Vector("MIT"),
      Map("openalex" -> "A2")
    )
    val record = matcher.decide(MatchInput.of(a, b, "k"))
    assertNotEquals(record.decision.decision, MatchOutcome.Conflict)
    val queue = ReviewQueue.build(
      Vector(record),
      RecordIndex(Map(a.id -> a, b.id -> b)),
      plan
    )
    assertEquals(
      queue.selections.map(_.stratum),
      Vector(ReviewQueue.AggregatorDisagreementStratum)
    )
  }

  private val sharedCoauthors = Vector("Alice Wong", "Bob Chen")

  test("a pair whose records cannot be found is not queued") {
    val partial = RecordIndex(Map.empty)
    assertEquals(ReviewQueue.build(records, partial, plan).items, Vector.empty)
  }
