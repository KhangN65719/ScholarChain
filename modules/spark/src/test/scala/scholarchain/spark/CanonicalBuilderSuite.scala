package scholarchain.spark

import org.apache.spark.sql.Row
import scholarchain.spark.Fixtures.{label, string, strings}

class CanonicalBuilderSuite extends SparkSuite:
  private lazy val clean =
    Canonical.build(
      session,
      Inputs.load(session, Fixtures.clean),
      Fixtures.context
    )

  test("resolved mentions share one canonical researcher node") {
    val nodes = rows(clean.researchers).map(r => string(r, "nodeId") -> r).toMap
    assertEquals(nodes.size, 7)
    SyntheticRun.researcherIds.foreach { (key, id) =>
      assertEquals(string(nodes(id), "nodeKind"), "canonical", key)
    }
    val ada = nodes(SyntheticRun.researcherIds("A"))
    assertEquals(ada.getAs[Long]("mentionCount"), 8L)
    assertEquals(string(ada, "displayName"), "Ada A")
    assertEquals(string(ada, "orcid"), SyntheticRun.orcids("A"))
    val authored = rows(clean.authored)
    val adaEdges = authored.filter(r =>
      string(r, "researcherNodeId") == SyntheticRun.researcherIds("A")
    )
    assertEquals(adaEdges.size, 8)
    assertEquals(
      adaEdges.map(string(_, "mentionId")).distinct.size,
      8
    )
  }

  test("an unresolved mention stays its own node and is never merged") {
    val nodes = rows(clean.researchers).map(r => string(r, "nodeId") -> r).toMap
    val dora = SyntheticRun.mentionId("W3", 3)
    val xavier = SyntheticRun.mentionId("W6", 2)
    Vector(dora, xavier).foreach { id =>
      assertEquals(string(nodes(id), "nodeKind"), "unresolved-mention")
      assert(nodes(id).isNullAt(nodes(id).fieldIndex("orcid")))
    }
    assertEquals(string(nodes(dora), "linkStatus"), "unlinked")
    assertEquals(string(nodes(xavier), "linkStatus"), "integrity-failure")
    assertEquals(string(nodes(dora), "displayName"), "Dora D")
    val canonicalIds = SyntheticRun.researcherIds.values.toSet
    assert(!canonicalIds.contains(dora))
    assert(!canonicalIds.contains(xavier))
    val authored =
      rows(clean.authored).map(r => string(r, "mentionId") -> r).toMap
    assertEquals(
      string(authored(dora), "researcherNodeId"),
      dora
    )
    assertEquals(string(authored(xavier), "nodeKind"), "unresolved-mention")
  }

  test("a merged work cluster yields one canonical work") {
    val works =
      rows(clean.works).map(r => string(r, "canonicalWorkId") -> r).toMap
    assertEquals(works.size, 7)
    val merged = works(SyntheticRun.workId("W2"))
    assertEquals(merged.getAs[Long]("memberCount"), 2L)
    assertEquals(
      strings(merged, "memberWorkIds"),
      Vector(SyntheticRun.workId("W2"), SyntheticRun.workId("W2b")).sorted
    )
    assertEquals(
      string(merged, "representativeWorkId"),
      SyntheticRun.workId("W2")
    )
    assert(!works.contains(SyntheticRun.workId("W2b")))
    assert(
      works(SyntheticRun.workId("W4")).isNullAt(
        works(SyntheticRun.workId("W4")).fieldIndex("year")
      )
    )
    val authored = rows(clean.authored)
    assertEquals(authored.size, 18)
    val notCounted = authored.filterNot(_.getAs[Boolean]("countedInMetrics"))
    assertEquals(
      notCounted.map(string(_, "workId")).distinct,
      Vector(SyntheticRun.workId("W2b"))
    )
    assertEquals(notCounted.size, 2)
    val venues = rows(clean.venues)
    assertEquals(venues.size, 1)
    assertEquals(venues.head.getAs[Long]("canonicalWorkCount"), 1L)
    val published = rows(clean.publishedIn)
    assertEquals(
      published.map(r => (string(r, "fromId"), string(r, "toId"))),
      Vector((SyntheticRun.workId("W1"), SyntheticRun.venueId("V1")))
    )
    assertEquals(rows(clean.topics).size, 1)
    assertEquals(
      string(rows(clean.institutions).head, "externalIdsJson"),
      """{"ror":"r1"}"""
    )
  }

  test("a repeated input row is one identity, chosen deterministically") {
    val rowsIn = frame("workId STRING, title STRING")(
      Row("a", "t"),
      Row("a", "t"),
      Row("b", "u"),
      Row("c", "z"),
      Row("c", "y")
    )
    val out = rows(Canonical.distinctByKey(session, rowsIn, Vector("workId")))
      .map(r => string(r, "workId") -> string(r, "title"))
      .sortBy(_._1)
    assertEquals(out, Vector("a" -> "t", "b" -> "u", "c" -> "y"))
    val keysOnly = frame("workId STRING")(Row("a"), Row("a"), Row("b"))
    assertEquals(
      rows(Canonical.distinctByKey(session, keysOnly, Vector("workId"))).size,
      2
    )
  }

  test("repeated keys are counted, and keys with differing rows are flagged") {
    val rowsIn = frame("workId STRING, title STRING")(
      Row("a", "t"),
      Row("a", "t"),
      Row("a", "t"),
      Row("b", "u"),
      Row("c", "z"),
      Row("c", "y"),
      Row("d", null),
      Row("d", "x")
    )
    val stats =
      rows(Canonical.duplicateStats(session, rowsIn, Vector("workId")))
    assertEquals(stats.size, 1)
    // a repeats twice, c once, d once; of those c and d differ.
    assertEquals(stats.head.getAs[Long]("duplicateKeyRows"), 4L)
    assertEquals(stats.head.getAs[Long]("conflictingKeys"), 2L)
    val keysOnly = frame("workId STRING")(Row("a"), Row("a"), Row("b"))
    val keyStats =
      rows(Canonical.duplicateStats(session, keysOnly, Vector("workId"))).head
    assertEquals(keyStats.getAs[Long]("duplicateKeyRows"), 1L)
    assertEquals(keyStats.getAs[Long]("conflictingKeys"), 0L)
    val none = rows(
      Canonical.duplicateStats(
        session,
        frame("workId STRING, title STRING")(Row("a", "t"), Row("b", "u")),
        Vector("workId")
      )
    ).head
    assertEquals(none.getAs[Long]("duplicateKeyRows"), 0L)
    assertEquals(none.getAs[Long]("conflictingKeys"), 0L)
    // The clean fixture repeats no key.
    val raw = Inputs.load(session, Fixtures.clean)
    Canonical.duplicateSpecs(raw).foreach { (name, key, data) =>
      val row = rows(Canonical.duplicateStats(session, data, key)).head
      assertEquals(row.getAs[Long]("conflictingKeys"), 0L, name)
    }
  }

  test(
    "the unresolved-mentions table carries unlinked and integrity-failure reasons"
  ) {
    val unresolved = rows(clean.unresolved)
      .map(r => label(string(r, "mentionId")) -> r)
      .toMap
    assertEquals(unresolved.keySet, Set("D", "X", "U"))
    assertEquals(string(unresolved("D"), "reason"), "unlinked")
    assertEquals(string(unresolved("X"), "reason"), "integrity-failure")
    assertEquals(string(unresolved("U"), "reason"), "unlinked")
    assertEquals(unresolved("D").getAs[Boolean]("isNode"), true)
    assertEquals(unresolved("X").getAs[Boolean]("isNode"), true)
    assertEquals(unresolved("U").getAs[Boolean]("isNode"), false)
  }

  test(
    "integrity violations are reported with a reason and counts, not dropped"
  ) {
    val cleanViolations = rows(clean.violations)
    assertEquals(cleanViolations.size, 1)
    assertEquals(
      string(cleanViolations.head, "reason"),
      "work-link-without-record"
    )
    assertEquals(string(cleanViolations.head, "action"), "ignored")

    val raw = Inputs.load(session, Fixtures.defective)
    val canon = Canonical.build(session, raw, Fixtures.context)
    val windows = Windowing.frame(session, Vector(Windows.allYears))
    val coauthor =
      Coauthorship.build(session, raw, canon, windows, Fixtures.context, 5)
    val all = rows(canon.violations) ++ rows(coauthor.pairViolations)
    val counts = all
      .groupBy(r => (string(r, "reason"), string(r, "action")))
      .map((k, v) => k -> v.size)
    assertEquals(
      counts,
      Map(
        ("mention-without-link", "kept-as-unresolved-node") -> 1,
        ("canonical-researcher-missing", "edge-dropped") -> 1,
        ("work-record-missing", "edge-dropped") -> 1,
        ("work-link-without-record", "ignored") -> 1,
        ("pair-endpoint-unknown", "pair-dropped") -> 1
      )
    )
    val dropped = rows(canon.authored).map(string(_, "mentionId")).toSet
    assert(!dropped.contains(SyntheticRun.mentionId("W2", 3)))
    assert(dropped.contains(SyntheticRun.mentionId("W1", 4)))
  }
