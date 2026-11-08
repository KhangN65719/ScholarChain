package scholarchain.tests.analytics

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

// Item 9 and the failure paths of items 4 and 12: degenerate and defective
// inputs. Worlds C1 (duplicate rows, an empty window), C2 (no accepted links
// at all) and refusals that must happen before any Spark work starts.
class SparkDegenerateInputsSuite extends SparkBase:
  private def c1: Output = done(Runs.c1)
  private def c2: Output = done(Runs.c2)

  private def c1Node(person: String): String =
    Spec.nodeId(Worlds.worldC1, person)

  private def metric(out: Output, window: String, id: String): Rec =
    only(
      out.table("researcher-metrics"),
      "window" -> window,
      "researcherId" -> id
    )

  private def deleteTree(path: Path): Unit =
    val stream = Files.walk(path)
    try
      val all = stream.iterator.asScala.toVector
      all.sortBy(_.toString)(Ordering[String].reverse).foreach { p =>
        Files.deleteIfExists(p)
      }
    finally stream.close()

  // ---- world C1 ----

  test("duplicate input rows do not change a researcher's measures") {
    val o = c1
    // e1 and e2: author-paper row of e1 and the pair e1-e2 given twice.
    // f1 and f2: the works row of dw1 given twice. h1 and h2: the link of h1
    // given twice. Each pair wrote one work: P = C = T = M = W = 1.
    Vector("e1", "e2", "f1", "f2", "h1", "h2").foreach { person =>
      val row = metric(o, "2001-2001", c1Node(person))
      assertEquals(row.long("publicationCount"), 1L, person)
      assertEquals(row.long("distinctCoauthorCount"), 1L, person)
      assertEquals(row.long("totalCoauthorOccurrences"), 1L, person)
      assertEquals(row.long("maximumCoauthorsOnOnePaper"), 1L, person)
      close(row.num("weightedDegree"), 1.0, person)
    }
    val edges = where(o.table("edges-coauthored"), "window" -> "2001-2001")
    // The path (3), e, f, h, k and u pairs (5).
    assertEquals(edges.size, 8)
    assert(edges.forall(_.long("jointPublicationCount") == 1L))
  }

  test(
    "a repeated author-paper row and pair row make no second AUTHORED edge"
  ) {
    val authored = c1.table("edges-authored")
    val own = authored.filter(_.text("workId") == Ids.work("dp1"))
    assertEquals(own.size, 2)
    assertEquals(own.map(_.text("mentionId")).toSet.size, 2)
  }

  test("a repeated works row is one work, not a merge of two records") {
    val o = c1
    val work = only(o.table("nodes-work"), "canonicalWorkId" -> Ids.work("dw1"))
    // One workId given twice is one record: memberCount is 1.
    assertEquals(work.long("memberCount"), 1L)
    // Only the clusters mc0 (mc1, mc2) and yr1 (yr1, yr2) merge two records.
    assertEquals(count(o, "canonical.mergedWorks"), 2L)
    // pa1 to pa3, dp1, dw1, dl1, cm1, lw1 and the clusters mc0 and yr1.
    assertEquals(count(o, "canonical.works"), 10L)
  }

  test("a repeated works row makes no second AUTHORED edge") {
    val authored = c1.table("edges-authored")
    val own = authored.filter(_.text("workId") == Ids.work("dw1"))
    assertEquals(own.map(_.text("mentionId")).toSet.size, 2)
    assertEquals(own.size, 2)
  }

  test("a repeated researcher link row makes no second AUTHORED edge") {
    val authored = c1.table("edges-authored")
    val own = authored.filter(_.text("workId") == Ids.work("dl1"))
    assertEquals(own.map(_.text("mentionId")).toSet.size, 2)
    assertEquals(own.size, 2)
  }

  test("a repeated row is not a repeated authorship anywhere") {
    val authored = c1.table("edges-authored")
    // 12 mentions of the first six works, one AUTHORED edge each, plus x1, y1
    // and the two mentions each of mc1, mc2, yr1 and yr2 (x2 and y2 are
    // dropped): 12 + 2 + 8 = 22.
    assertEquals(authored.map(_.text("mentionId")).toSet.size, 22)
    assertEquals(authored.size, 22)
  }

  test("a link to a canonical researcher that does not exist drops the edge") {
    val o = c1
    val dropped = Ids.mention("cm1", 2)
    val authored = o.table("edges-authored").map(_.text("mentionId")).toSet
    assert(!authored(dropped), "AUTHORED edge to a missing researcher kept")
    assert(authored(Ids.mention("cm1", 1)))
    val violation = only(
      o.table("integrity-violations"),
      "reason" -> "canonical-researcher-missing"
    )
    assertEquals(violation.text("mentionId"), dropped)
    assertEquals(violation.text("action"), "edge-dropped")
    val nodes = o.table("nodes-researcher").map(_.text("nodeId")).toSet
    assert(!nodes(Ids.researcher("x2")))
    // The pair x1-x2 is dropped too, so x1 is alone: P = 1 and no coauthor.
    val x1 = metric(o, "2001-2001", c1Node("x1"))
    assertEquals(x1.long("publicationCount"), 1L)
    assertEquals(x1.long("distinctCoauthorCount"), 0L)
    assertEquals(x1.long("totalCoauthorOccurrences"), 0L)
  }

  test("a link that names another work drops the edge and the pair") {
    val o = c1
    val authored = o.table("edges-authored").map(_.text("mentionId")).toSet
    assert(!authored(Ids.mention("lw1", 2)), "mismatched link was kept")
    assert(authored(Ids.mention("lw1", 1)))
    val violation = only(
      o.table("integrity-violations"),
      "reason" -> "link-work-mismatch"
    )
    assertEquals(violation.text("mentionId"), Ids.mention("lw1", 2))
    val y1 = metric(o, "2001-2001", c1Node("y1"))
    assertEquals(y1.long("publicationCount"), 1L)
    assertEquals(y1.long("distinctCoauthorCount"), 0L)
    // Two pairs (x1-x2 and y1-y2) had an endpoint that is not a node.
    val pairs = where(
      o.table("integrity-violations"),
      "reason" -> "pair-endpoint-unknown"
    )
    assertEquals(pairs.size, 2)
    assertEquals(count(o, "integrityViolations"), 4L)
  }

  test("a cluster with no record of its own uses its smallest member") {
    val o = c1
    // mc1 and mc2 link to mc0, which is not in the works export. mc1 has the
    // smaller ID, so its authors k1 and k2 are the authors of the work mc0.
    assert(Ids.work("mc1") < Ids.work("mc2"))
    val work = only(o.table("nodes-work"), "canonicalWorkId" -> Ids.work("mc0"))
    assertEquals(work.text("representativeWorkId"), Ids.work("mc1"))
    assertEquals(work.long("memberCount"), 2L)
    assertEquals(work.long("year"), 2001L)
    Vector("k1", "k2").foreach { person =>
      val row = metric(o, "2001-2001", c1Node(person))
      assertEquals(row.long("publicationCount"), 1L, person)
      assertEquals(row.long("distinctCoauthorCount"), 1L, person)
      assertEquals(row.strings("maximumTeamWorkIds"), Vector(Ids.work("mc0")))
    }
    // k3 is only in the other record: a node, but in no window.
    val nodes = o.table("nodes-researcher").map(_.text("nodeId")).toSet
    assert(nodes(Ids.researcher("k3")))
    val windowed = o.table("researcher-metrics").map(_.text("researcherId"))
    assert(!windowed.contains(Ids.researcher("k3")))
    val authored = o.table("edges-authored")
    val own = authored.filter(_.text("workId") == Ids.work("mc2"))
    assertEquals(own.size, 2)
    assert(own.forall(!_.flag("countedInMetrics")))
    val first = Vector(Ids.researcher("k1"), Ids.researcher("k2")).min
    val edge = where(
      o.table("edges-coauthored"),
      "window" -> "2001-2001",
      "researcherA" -> first
    )
    assertEquals(edge.size, 1)
    assertEquals(edge.head.long("jointPublicationCount"), 1L)
    assertEquals(
      edge.head.strings("representativeWorkIds"),
      Vector(Ids.work("mc0"))
    )
  }

  test("a work with no year takes the year of a duplicate record") {
    val o = c1
    // yr1 is the anchor record and has no year; its duplicate yr2 is dated
    // 2001, so the canonical work is dated 2001 and says so.
    val work = only(o.table("nodes-work"), "canonicalWorkId" -> Ids.work("yr1"))
    assertEquals(work.long("year"), 2001L)
    assert(work.flag("yearFromDuplicateRecord"))
    assertEquals(work.long("memberCount"), 2L)
    Vector("u1", "u2").foreach { person =>
      val row = metric(o, "2001-2001", c1Node(person))
      assertEquals(row.long("publicationCount"), 1L, person)
      assertEquals(row.strings("maximumTeamWorkIds"), Vector(Ids.work("yr1")))
    }
    assertEquals(count(o, "canonical.worksWithoutYear"), 0L)
  }

  test("an empty window yields no rows and a comparison of absences") {
    val o = c1
    // Nobody has a work dated 1990 to 1995.
    val empty = where(o.table("researcher-metrics"), "window" -> "1990-1995")
    assertEquals(empty.size, 0)
    assertEquals(
      where(o.table("edges-coauthored"), "window" -> "1990-1995").size,
      0
    )
    // 2001-2001 holds 16 researchers: the path of 4, the pairs e, f, h, k and
    // u, and x1 and y1 alone. 8 edges, 8 components (4, 2, 2, 2, 2, 2, 1, 1).
    val summary = only(
      o.table("component-summary"),
      "window" -> "2001-2001"
    )
    assertEquals(summary.long("nodeCount"), 16L)
    assertEquals(summary.long("edgeCount"), 8L)
    assertEquals(summary.long("componentCount"), 8L)
    close(summary.num("largestComponentFraction"), 4.0 / 16, "fraction")
    assertEquals(summary.long("singletonCount"), 2L)
    assertEquals(
      where(o.table("component-summary"), "window" -> "1990-1995").size,
      0
    )
    // The comparison 2001-2001 to 1990-1995: everyone is only in the first.
    val change = only(
      o.table("temporal-window-summary"),
      "fromWindow" -> "2001-2001",
      "toWindow" -> "1990-1995"
    )
    assertEquals(change.long("researchersInFrom"), 16L)
    assertEquals(change.long("researchersInTo"), 0L)
    assertEquals(change.long("researchersInBoth"), 0L)
    assertEquals(change.long("onlyInFrom"), 16L)
    assert(change.isNull("meanDistinctCoauthorChange"))
    val rows = where(o.table("temporal-comparison"), "toWindow" -> "1990-1995")
    assertEquals(rows.size, 16)
    assert(rows.forall(_.text("presence") == "only-from"))
  }

  test("an empty window is still a window of the run") {
    val config = c1
      .json("run-manifest.json")("config")("windows")
      .arr
      .map(_("label").str)
      .toVector
    assertEquals(config, Vector("2001-2001", "1990-1995", "all"))
    val all = where(c1.table("researcher-metrics"), "window" -> "all")
    assertEquals(all.size, 16)
    val partitions = c1
      .files("tables/researcher-metrics")
      .filter(_.getFileName.toString.endsWith(".parquet"))
      .map(_.getParent.getFileName.toString)
    assertEquals(partitions.toSet, Set("window=2001-2001", "window=all"))
  }

  test(
    "a component pass cap too small for a path fails the run, not the data"
  ) {
    val run = Runs.c1Capped
    // The path d1-d2-d3-d4 needs 4 passes of label propagation, the cap is 1.
    assertEquals(run.code, 1, run.text)
    assert(run.text.contains("SPARK-PIPELINE RESULT: FAILED"), run.text)
    assert(run.text.contains("did not converge"), run.text)
    assert(!Files.exists(run.runDir.resolve("_SUCCESS")))
    assert(!Files.exists(run.runDir.resolve("run-manifest.json")))
    // The same inputs without the cap are a different run (the cap is part of it).
    assertNotEquals(run.runId, Runs.c1.runId)
  }

  // ---- world C2 ----

  test("without accepted links every mention is its own node") {
    val o = c2
    val nodes = o.table("nodes-researcher")
    assertEquals(nodes.size, 12)
    assert(nodes.forall(_.text("nodeKind") == "unresolved-mention"))
    assert(nodes.forall(_.text("nodeId").startsWith("mention:")))
    val unresolved = o.table("unresolved-mentions")
    assertEquals(unresolved.size, 12)
    assert(unresolved.forall(_.text("reason") == "no-link-row"))
    assert(unresolved.forall(_.flag("isNode")))
    assertEquals(count(o, "canonical.researchers"), 12L)
  }

  test("the same person in two works is two nodes and two components") {
    val o = c2
    // d2 wrote pa1 and pa2, but with no links the two mentions are unrelated:
    // six pairs make six components of two, with no path across works.
    val summary = only(o.table("component-summary"), "window" -> "2001-2001")
    assertEquals(summary.long("nodeCount"), 12L)
    assertEquals(summary.long("edgeCount"), 6L)
    assertEquals(summary.long("componentCount"), 6L)
    assertEquals(summary.long("largestComponentSize"), 2L)
    close(summary.num("largestComponentFraction"), 2.0 / 12, "fraction")
    assertEquals(summary.long("singletonCount"), 0L)
    val second = Ids.mention("pa1", 2)
    val third = Ids.mention("pa2", 1)
    val membership = where(
      o.table("component-membership"),
      "window" -> "2001-2001"
    ).map(r => r.text("researcherId") -> r.text("componentId")).toMap
    assertNotEquals(membership(second), membership(third))
    o.table("researcher-metrics").foreach { m =>
      assertEquals(m.long("publicationCount"), 1L)
      assertEquals(m.long("distinctCoauthorCount"), 1L)
    }
  }

  test("without a pairs file the benchmark pairs are the top four by weight") {
    val o = c2
    // Every researcher has weighted degree 1, so the ranking falls back to the
    // ID: the top four are the four smallest IDs, and the pairs are the six
    // couples among them. Two mentions are adjacent only inside one work.
    val mentions = Worlds.worldC2.works.flatMap { work =>
      work.authors.indices.map(i => Ids.mention(work.key, i + 1) -> work.key)
    }
    val top = mentions.map(_._1).sorted.take(4)
    val workOf = mentions.toMap
    val rows = o.table("path-evidence")
    Vector("2001-2001", "all").foreach { window =>
      val ofWindow = where(rows, "window" -> window)
      assertEquals(ofWindow.size, 6, window)
      assert(ofWindow.forall(_.text("selection") == "auto-top-weighted-degree"))
      top.combinations(2).foreach { couple =>
        val key = s"$window|${couple(0)}|${couple(1)}"
        val found = only(ofWindow, "pairKey" -> key)
        val together = workOf(couple(0)) == workOf(couple(1))
        val state = if together then "found" else "unreachable-within-bound"
        assertEquals(found.text("status"), state, key)
        assertEquals(found.long("hopLimit"), 6L, key)
      }
    }
  }

  test("every missing link and missing work link is recorded, none dropped") {
    val o = c2
    // 12 mentions without a link and 6 works without a work link.
    assertEquals(count(o, "integrityViolations"), 18L)
    val violations = o.table("integrity-violations")
    assertEquals(where(violations, "reason" -> "mention-without-link").size, 12)
    assertEquals(where(violations, "reason" -> "work-without-link").size, 6)
    assertEquals(count(o, "canonical.works"), 6L)
    assertEquals(count(o, "canonical.mergedWorks"), 0L)
    assertNotEquals(Runs.c2.runId, Runs.c1.runId)
  }

  test(
    "reaching the iteration cap is recorded, and the labels stay a partition"
  ) {
    val o = c2
    val algorithms = o.json("run-manifest.json")("algorithms")
    // Components of pairs settle in two passes, so the cap of 2 is enough.
    val components = algorithms("connectedComponents")
    assertEquals(components("converged").bool, true)
    assertEquals(components("passes").num.toInt, 2)
    val communities = algorithms("communities")
    val perWindow = communities("perWindow").obj.values.toVector
    val settled = perWindow.forall(_("converged").bool)
    assertEquals(communities("allWindowsConverged").bool, settled)
    assertEquals(communities("passes").num.toInt <= 2, true)
    if !settled then
      assertEquals(communities("passes").num.toInt, 2)
      assert(perWindow.exists(!_("converged").bool))
    // Either way every researcher has one community.
    val memberships = o.table("community-membership")
    assertEquals(memberships.size, o.table("researcher-metrics").size)
    assertEquals(
      memberships
        .map(r => (r.text("window"), r.text("researcherId")))
        .toSet
        .size,
      memberships.size
    )
  }

  test("changing one input year changes the run ID and only its digests") {
    val first = done(Runs.c2)
    val second = done(Runs.c2b)
    // The same options and the same links: only the Hadoop files with a year.
    assertNotEquals(Runs.c2.runId, Runs.c2b.runId)
    def digests(o: Output): Map[String, String] =
      o.json("run-manifest.json")("inputs")("datasets")
        .arr
        .map(d => d("name").str -> d("sha256").str)
        .toMap
    val before = digests(first)
    val after = digests(second)
    assertEquals(before.keySet, after.keySet)
    val changed = before.keySet.filter(name => before(name) != after(name))
    assertEquals(
      changed,
      Set("works", "author-papers", "pair-candidates", "teams")
    )
    // The year moved x3 out of window 2001-2001: three works are still there.
    val summary = only(
      second.table("component-summary"),
      "window" -> "2001-2001"
    )
    assertEquals(summary.long("nodeCount"), 10L)
    assertEquals(
      only(first.table("component-summary"), "window" -> "2001-2001")
        .long("nodeCount"),
      12L
    )
  }

  // ---- refusals before any Spark work ----

  private def tiny(name: String): Fixtures.Inputs =
    Fixtures.write(Worlds.worldC2, Harness.root.resolve(name))

  private def refuse(in: Fixtures.Inputs, name: String, more: String*) =
    val out = Harness.root.resolve(s"$name-out")
    val run = Harness.refuse(Runs.base(in, out) ++ more)
    assert(!Files.exists(out), s"$name created an output directory")
    run

  test("a missing input directory is refused with exit code 2") {
    val in = Fixtures.Inputs(
      Harness.root.resolve("no-such-er"),
      Harness.root.resolve("no-such-hadoop")
    )
    val run = refuse(in, "gone")
    assertEquals(run.code, 2, run.text)
    assert(run.text.contains("SPARK-PIPELINE RESULT: FAILED"), run.text)
    assert(run.text.contains("Missing input artifact"), run.text)
  }

  test("a missing Hadoop dataset or ER marker names the artifact, exit 2") {
    val pairs = tiny("m-pairs")
    deleteTree(pairs.hadoopRoot.resolve("stage=team-expansion/pair-candidates"))
    val first = refuse(pairs, "m-pairs")
    assertEquals(first.code, 2, first.text)
    assert(first.text.contains("pair-candidates"), first.text)
    val marker = tiny("m-marker")
    Files.delete(marker.erRun.resolve("_SUCCESS"))
    val second = refuse(marker, "m-marker")
    assertEquals(second.code, 2, second.text)
    assert(second.text.contains("_SUCCESS"), second.text)
    val team = tiny("m-team")
    Files.delete(team.hadoopRoot.resolve("stage=team-expansion/_SUCCESS"))
    val third = refuse(team, "m-team")
    assertEquals(third.code, 2, third.text)
    val links = tiny("m-links")
    deleteTree(links.erRun.resolve("accepted-links/v1/work-links"))
    val fourth = refuse(links, "m-links")
    assertEquals(fourth.code, 2, fourth.text)
    assert(fourth.text.contains("work-links"), fourth.text)
  }

  test("a bad path pair file is refused with exit code 2") {
    val in = tiny("m-pairs-file")
    val dir = Harness.root.resolve("pair-files")
    Files.createDirectories(dir)
    def attempt(name: String, body: String, more: String*) =
      val file = dir.resolve(name)
      Files.writeString(file, body)
      val args = Vector("--path-pairs", file.toString) ++ more
      refuse(in, s"pairs-$name", args*)
    val malformed = attempt("malformed", "only,two\n")
    assertEquals(malformed.code, 2, malformed.text)
    assert(malformed.text.contains("Bad path pair"), malformed.text)
    val unknown =
      attempt("unknown", "1999-2000,a,b\n", "--windows", "2001-2001")
    assertEquals(unknown.code, 2, unknown.text)
    val empty = attempt("blank-end", "all,a,\n")
    assertEquals(empty.code, 2, empty.text)
    val big = attempt("big", "all,a,b\nall,c,d\n", "--driver-row-cap", "1")
    assertEquals(big.code, 2, big.text)
    assert(big.text.contains("more than"), big.text)
  }
