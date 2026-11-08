package scholarchain.tests.analytics

import scholarchain.core.StableIds

// Item 8: time windows. World A with windows 2000-2004, 2005-2009, 2003-2006
// (overlapping the first two) and all. The consecutive pairs the module
// compares are 2000-2004 to 2005-2009 and 2005-2009 to 2003-2006.
class SparkTemporalWindowsSuite extends SparkBase:
  private def out: Output = done(Runs.a)

  private def node(person: String): String = Worlds.a(person)

  private def hasEdge(window: String, x: String, y: String): Boolean =
    val (a, b) = StableIds.orderedPair(node(x), node(y))
    where(
      out.table("edges-coauthored"),
      "window" -> window,
      "researcherA" -> a,
      "researcherB" -> b
    ).nonEmpty

  private def compare(from: String, to: String, who: String): Rec =
    only(
      out.table("temporal-comparison"),
      "fromWindow" -> from,
      "toWindow" -> to,
      "researcherId" -> node(who)
    )

  private def summary(from: String, to: String): Rec =
    only(
      out.table("temporal-window-summary"),
      "fromWindow" -> from,
      "toWindow" -> to
    )

  test("boundary years are inclusive and overlapping windows share works") {
    // r1 is dated 2000 and r6 is dated 2004: both are in window 2000-2004.
    val rep = only(
      out.table("researcher-metrics"),
      "window" -> "2000-2004",
      "researcherId" -> node("Rep")
    )
    assertEquals(rep.long("publicationCount"), 6L)
    assertEquals(
      rep.strings("maximumTeamWorkIds").toSet,
      works("r1", "r6", "r2", "r3", "r4", "r5")
    )
    // bd1 (2005) is in 2005-2009 and in 2003-2006; bd2 (2009) only in the first.
    assert(hasEdge("2005-2009", "bl1", "bl2"))
    assert(hasEdge("2003-2006", "bl1", "bl2"))
    assert(hasEdge("2005-2009", "bl3", "bl4"))
    assert(!hasEdge("2003-2006", "bl3", "bl4"))
    assert(!hasEdge("2000-2004", "bl1", "bl2"))
    // r5 (2003) and r6 (2004) are in 2003-2006, r4 (2002) is not.
    val overlap = only(
      out.table("researcher-metrics"),
      "window" -> "2003-2006",
      "researcherId" -> node("Rep")
    )
    assertEquals(overlap.strings("maximumTeamWorkIds").toSet, works("r5", "r6"))
  }

  test("years outside every dated window are only in the all window") {
    // old1 is dated 1999 and r7 is dated 2010.
    assert(hasEdge("all", "o1", "o2"))
    Vector("2000-2004", "2005-2009", "2003-2006").foreach { window =>
      assert(!hasEdge(window, "o1", "o2"), window)
    }
    val metrics = out.table("researcher-metrics")
    val o1 = where(metrics, "researcherId" -> node("o1")).map(_.text("window"))
    assertEquals(o1, Vector("all"))
    // r7 adds a seventh work to Rep in all, and none in 2000-2004.
    val all = only(metrics, "window" -> "all", "researcherId" -> node("Rep"))
    assertEquals(all.long("publicationCount"), 7L)
  }

  test("a work with no year is in no window, all included, and is counted") {
    val metrics = out.table("researcher-metrics")
    Vector("Ned", "Nora").foreach { who =>
      assertEquals(where(metrics, "researcherId" -> node(who)).size, 0, who)
    }
    assertEquals(count(out, "canonical.worksWithoutYear"), 1L)
    Vector("2000-2004", "2005-2009", "2003-2006", "all").foreach { window =>
      assert(!hasEdge(window, "Ned", "Nora"), window)
    }
    // The comparison reports it as excluded.
    val first = summary("2000-2004", "2005-2009")
    assertEquals(first.long("worksWithoutYearExcluded"), 1L)
    // The researchers still exist as nodes.
    val nodes = out.table("nodes-researcher").map(_.text("nodeId")).toSet
    assert(nodes(node("Ned")) && nodes(node("Nora")))
  }

  test("every windowed table carries the window and its start and end") {
    val windowed = Vector(
      "edges-coauthored",
      "edge-support",
      "researcher-metrics",
      "component-membership",
      "components",
      "component-summary",
      "community-membership",
      "communities",
      "community-cross-edges",
      "bridge",
      "coreness",
      "path-evidence",
      "path-length-estimates",
      "semantic-summaries"
    )
    val bounds = Map(
      "2000-2004" -> (2000L, 2004L),
      "2005-2009" -> (2005L, 2009L),
      "2003-2006" -> (2003L, 2006L),
      "all" -> (1L, 9999L)
    )
    windowed.foreach { name =>
      val rows = out.table(name)
      // Cross edges may be none when every component is one community.
      assert(rows.nonEmpty || name == "community-cross-edges", s"$name empty")
      rows.foreach { row =>
        val label = row.text("window")
        if label != "not-windowed" then
          val expected = bounds(label)
          assertEquals(row.long("windowStart"), expected._1, name)
          assertEquals(row.long("windowEnd"), expected._2, name)
      }
      assertEquals(
        rows
          .map(_.text("window"))
          .toSet
          .subsetOf(bounds.keySet + "not-windowed"),
        true,
        name
      )
    }
    // Partitions are named by the window label on disk.
    val parquet = out
      .files("tables/researcher-metrics")
      .filter(_.getFileName.toString.endsWith(".parquet"))
    val parts = parquet.map(_.getParent.getFileName.toString)
    assertEquals(parts.toSet, bounds.keySet.map(w => s"window=$w"))
  }

  test("the comparison of 2000-2004 with 2005-2009 counts who is in which") {
    val row = summary("2000-2004", "2005-2009")
    // 66 researchers in 2000-2004, 6 in 2005-2009; a4 and b1 are in both.
    assertEquals(row.long("researchersInFrom"), 66L)
    assertEquals(row.long("researchersInTo"), 6L)
    assertEquals(row.long("researchersInBoth"), 2L)
    assertEquals(row.long("onlyInFrom"), 64L)
    assertEquals(row.long("onlyInTo"), 4L)
    assertEquals(row.long("fromYears"), 5L)
    assertEquals(row.long("toYears"), 5L)
    assert(row.flag("sameWindowLength"))
    // a4 and b1 each go from 3 to 1 coauthors; their team sizes stay 1.
    close(row.num("meanDistinctCoauthorChange"), -2.0, "coauthors")
    close(row.num("meanAverageTeamSizeChange"), 0.0, "team size")
    // Each moves from a K4 component or community of 4 into a pair; the
    // overlap is the one shared researcher over 4 + 2 - 1 = 5 members.
    close(row.num("meanComponentOverlap"), 0.2, "components")
    close(row.num("meanCommunityOverlap"), 0.2, "communities")
    close(row.num("meanBridgeChange"), 0.0, "bridge")
  }

  test("the comparison of 2005-2009 with 2003-2006 has unequal lengths") {
    val row = summary("2005-2009", "2003-2006")
    // 6 researchers in 2005-2009, 8 in 2003-2006; a4, b1, bl1 and bl2 in both.
    assertEquals(row.long("researchersInFrom"), 6L)
    assertEquals(row.long("researchersInTo"), 8L)
    assertEquals(row.long("researchersInBoth"), 4L)
    assertEquals(row.long("onlyInFrom"), 2L)
    assertEquals(row.long("onlyInTo"), 4L)
    assertEquals(row.long("fromYears"), 5L)
    assertEquals(row.long("toYears"), 4L)
    assert(!row.flag("sameWindowLength"))
    close(row.num("meanDistinctCoauthorChange"), 0.0, "coauthors")
    // The pairs a4-b1 and bl1-bl2 are the same two members in both windows.
    close(row.num("meanComponentOverlap"), 1.0, "components")
    close(row.num("meanCommunityOverlap"), 1.0, "communities")
  }

  test("a researcher in both windows has every change column filled") {
    val a4 = compare("2000-2004", "2005-2009", "a4")
    assertEquals(a4.text("presence"), "both")
    assert(a4.flag("comparable"))
    assertEquals(a4.long("distinctCoauthorCountFrom"), 3L)
    assertEquals(a4.long("distinctCoauthorCountTo"), 1L)
    close(a4.num("distinctCoauthorCountChange"), -2.0, "C")
    close(a4.num("publicationCountChange"), -2.0, "P")
    close(a4.num("weightedDegreeChange"), -2.0, "W")
    close(a4.num("corenessChange"), -2.0, "coreness")
    close(a4.num("averageCoauthorsPerPaperChange"), 0.0, "A")
    close(a4.num("componentOverlap"), 0.2, "component")
    close(a4.num("communityOverlap"), 0.2, "community")
    val stable = compare("2005-2009", "2003-2006", "a4")
    close(stable.num("distinctCoauthorCountChange"), 0.0, "C stable")
    close(stable.num("publicationCountChange"), 0.0, "P stable")
    close(stable.num("componentOverlap"), 1.0, "component stable")
  }

  test(
    "a researcher in one window only is flagged and has no invented change"
  ) {
    val gone = compare("2000-2004", "2005-2009", "Wide")
    assertEquals(gone.text("presence"), "only-from")
    assert(!gone.flag("comparable"))
    assertEquals(gone.long("distinctCoauthorCountFrom"), 8L)
    assert(gone.isNull("distinctCoauthorCountTo"))
    assert(gone.isNull("distinctCoauthorCountChange"))
    assert(gone.isNull("componentOverlap"))
    val arrived = compare("2000-2004", "2005-2009", "bl1")
    assertEquals(arrived.text("presence"), "only-to")
    assert(arrived.isNull("distinctCoauthorCountFrom"))
    assert(arrived.isNull("publicationCountChange"))
    val back = compare("2005-2009", "2003-2006", "Rep")
    assertEquals(back.text("presence"), "only-to")
    // 66 + 6 - 2 rows and 6 + 8 - 4 rows: everyone in either window.
    val rows = out.table("temporal-comparison")
    assertEquals(where(rows, "fromWindow" -> "2000-2004").size, 70)
    assertEquals(where(rows, "fromWindow" -> "2005-2009").size, 10)
    assertEquals(rows.size, 80)
  }

  test("the comparison states its inclusion rule and its limits") {
    val manifest = out.json("run-manifest.json")("algorithms")("temporal")
    val rule = manifest("inclusionRule").str
    val limits = manifest("limitations").str.toLowerCase
    assert(rule.toLowerCase.contains("both"), rule)
    assert(limits.contains("length"), limits)
    assert(limits.contains("unresolved"), limits)
    assert(limits.contains("community"), limits)
    assert(limits.contains("no year"), limits)
    val row = summary("2000-2004", "2005-2009")
    assertEquals(row.text("inclusionRule"), rule)
    assertEquals(row.text("limitations").toLowerCase, limits)
    // The all window is not compared with a dated window.
    val pairs = out.table("temporal-window-summary").map { r =>
      (r.text("fromWindow"), r.text("toWindow"))
    }
    assertEquals(
      pairs.toSet,
      Set(("2000-2004", "2005-2009"), ("2005-2009", "2003-2006"))
    )
  }
