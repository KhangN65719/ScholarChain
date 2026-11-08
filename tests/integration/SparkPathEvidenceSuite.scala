package scholarchain.tests.analytics

import scholarchain.core.StableIds

// Item 6: shortest paths on hand-checkable graphs, selected by a pairs file
// with a hop limit of 3. World A, windows 2000-2004, 2005-2009, 2003-2006, all.
class SparkPathEvidenceSuite extends SparkBase:
  private def out: Output = done(Runs.a)

  private def node(person: String): String = Worlds.a(person)

  private def key(window: String, from: String, to: String): String =
    s"$window|${node(from)}|${node(to)}"

  private def rowsOf(window: String, from: String, to: String): Vector[Rec] =
    where(out.table("path-evidence"), "pairKey" -> key(window, from, to))

  // The hop rows of a found path, first hop first.
  private def hops(window: String, from: String, to: String): Vector[Rec] =
    rowsOf(window, from, to).sortBy(_.long("hopIndex"))

  private def status(window: String, from: String, to: String): String =
    val rows = rowsOf(window, from, to)
    assertEquals(rows.map(_.text("status")).toSet.size, 1, s"$window $from $to")
    rows.head.text("status")

  private def ends(row: Rec): (String, String) =
    (row.text("fromId"), row.text("toId"))

  test("a unique shortest path lists every hop with its supporting work") {
    // p1-p2 (pa1), p2-p3 (pa2), p3-p4 (pa3): the only path, three hops.
    val found = hops("2000-2004", "p1", "p4")
    assertEquals(status("2000-2004", "p1", "p4"), "found")
    assertEquals(found.size, 3)
    assertEquals(found.map(_.long("pathLength")).toSet, Set(3L))
    assertEquals(found.map(_.long("hopIndex")), Vector(1L, 2L, 3L))
    assertEquals(
      found.map(ends),
      Vector(
        (node("p1"), node("p2")),
        (node("p2"), node("p3")),
        (node("p3"), node("p4"))
      )
    )
    val support = found.map(_.strings("supportingWorkIds").toSet)
    assertEquals(support, Vector(works("pa1"), works("pa2"), works("pa3")))
    found.foreach { hop =>
      assertEquals(hop.long("jointPublicationCount"), 1L)
      assertEquals(hop.long("firstCollaborationYear"), 2001L)
      assertEquals(hop.long("lastCollaborationYear"), 2001L)
      assertEquals(hop.text("sourceId"), node("p1"))
      assertEquals(hop.text("targetId"), node("p4"))
      assertEquals(hop.text("selection"), "config-file")
    }
  }

  test("the reversed query gives the same path read backwards") {
    val back = hops("2000-2004", "p4", "p1")
    assertEquals(back.size, 3)
    assertEquals(
      back.map(ends),
      Vector(
        (node("p4"), node("p3")),
        (node("p3"), node("p2")),
        (node("p2"), node("p1"))
      )
    )
    val support = back.map(_.strings("supportingWorkIds").toSet)
    assertEquals(support, Vector(works("pa3"), works("pa2"), works("pa1")))
  }

  test("a hop carries the edge it walks and its weight") {
    // Rep and X are adjacent with 6 joint works in 2000-2004; the evidence
    // keeps at most 5 of them and points at the edge by its ID.
    val found = hops("2000-2004", "Rep", "X")
    assertEquals(found.size, 1)
    val hop = found.head
    assertEquals(hop.long("pathLength"), 1L)
    assertEquals(hop.long("jointPublicationCount"), 6L)
    assertEquals(hop.long("firstCollaborationYear"), 2000L)
    assertEquals(hop.long("lastCollaborationYear"), 2004L)
    val shown = hop.strings("supportingWorkIds")
    assertEquals(shown.size, 5)
    assert(shown.toSet.subsetOf(works("r1", "r2", "r3", "r4", "r5", "r6")))
    val (a, b) = StableIds.orderedPair(node("Rep"), node("X"))
    val edge = only(
      out.table("edges-coauthored"),
      "window" -> "2000-2004",
      "researcherA" -> a,
      "researcherB" -> b
    )
    assertEquals(hop.text("edgeId"), edge.text("edgeId"))
  }

  test("two equal paths: one valid shortest path, chosen by smallest ID") {
    // ds-dx-dt (di1, di3) and ds-dy-dt (di2, di4) both have length 2.
    val found = hops("2000-2004", "ds", "dt")
    assertEquals(status("2000-2004", "ds", "dt"), "found")
    assertEquals(found.size, 2)
    assertEquals(found.map(_.long("pathLength")).toSet, Set(2L))
    val middle = found.head.text("toId")
    assert(Set(node("dx"), node("dy")).contains(middle), middle)
    assertEquals(found(1).text("fromId"), middle)
    assertEquals(found.head.text("fromId"), node("ds"))
    assertEquals(found(1).text("toId"), node("dt"))
    // The module documents the smallest predecessor as the tie break.
    assertEquals(middle, Vector(node("dx"), node("dy")).min)
    val expected =
      if middle == node("dx") then Vector(works("di1"), works("di3"))
      else Vector(works("di2"), works("di4"))
    assertEquals(found.map(_.strings("supportingWorkIds").toSet), expected)
  }

  test("an unreachable pair is reported with a status and no hops") {
    // p1 is on the path and t1 on the triangle: no connection in any window.
    val rows = rowsOf("2000-2004", "p1", "t1")
    assertEquals(rows.size, 1)
    assertEquals(rows.head.text("status"), "unreachable-within-bound")
    assert(rows.head.isNull("hopIndex"))
  }

  test("a pair farther apart than the hop limit is not found") {
    // l1-l2-l3-l4-l5 needs 4 hops with a limit of 3, but l1 to l4 needs 3.
    assertEquals(status("2000-2004", "l1", "l5"), "unreachable-within-bound")
    assertEquals(rowsOf("2000-2004", "l1", "l5").size, 1)
    assertEquals(rowsOf("2000-2004", "l1", "l5").head.long("hopLimit"), 3L)
    assertEquals(status("2000-2004", "l1", "l4"), "found")
    assertEquals(hops("2000-2004", "l1", "l4").size, 3)
  }

  test("source equal to target is trivial") {
    val rows = rowsOf("2000-2004", "p1", "p1")
    assertEquals(rows.size, 1)
    assertEquals(rows.head.text("status"), "trivial")
    assertEquals(rows.head.long("pathLength"), 0L)
    assert(rows.head.isNull("hopIndex"))
  }

  test("a pair outside the window or not in the graph has no path") {
    // p1 and p2 have no work dated 2005 to 2009.
    assertEquals(status("2005-2009", "p1", "p2"), "endpoint-not-in-window")
    // An identifier that is nowhere in the graph.
    val ghost = s"2000-2004|${node("p1")}|${Ids.researcher("not-in-the-graph")}"
    val rows = where(out.table("path-evidence"), "pairKey" -> ghost)
    assertEquals(rows.map(_.text("status")), Vector("endpoint-not-in-window"))
  }

  test("a pair in two windows is looked up in its own window graph") {
    // a4 and b1 are both researchers of 2000-2004 but in different cliques and
    // the bridge is dated 2006, so they are not connected in that window.
    assertEquals(status("2000-2004", "a4", "b1"), "unreachable-within-bound")
    // In 2003-2006 the bridge is the one work br1: one hop.
    val found = hops("2003-2006", "a4", "b1")
    assertEquals(found.size, 1)
    assertEquals(found.head.long("jointPublicationCount"), 1L)
    assertEquals(found.head.long("firstCollaborationYear"), 2006L)
    assertEquals(found.head.long("lastCollaborationYear"), 2006L)
    assertEquals(found.head.strings("supportingWorkIds").toSet, works("br1"))
  }

  test("a path across the bridge names the work behind each of its hops") {
    // a1 to b4 in window all: a1-a4 (ka3), a4-b1 (br1), b1-b4 (kb3). The
    // only path of length 3, since b1 is the only link between the cliques.
    val found = hops("all", "a1", "b4")
    assertEquals(status("all", "a1", "b4"), "found")
    assertEquals(
      found.map(ends),
      Vector(
        (node("a1"), node("a4")),
        (node("a4"), node("b1")),
        (node("b1"), node("b4"))
      )
    )
    val support = found.map(_.strings("supportingWorkIds").toSet)
    assertEquals(support, Vector(works("ka3"), works("br1"), works("kb3")))
  }

  test("path lengths agree with a breadth-first search of the edge table") {
    val edges = out.table("edges-coauthored")
    val selected = Worlds.pathPairsA
    selected.foreach { (window, from, to) =>
      val pairs = where(edges, "window" -> window)
        .map(e => (e.text("researcherA"), e.text("researcherB")))
      val adjacent = Oracles.neighbours(pairs)
      val distance = Oracles.distances(from, adjacent).get(to)
      val rows = where(
        out.table("path-evidence"),
        "pairKey" -> s"$window|$from|$to"
      )
      val state = rows.head.text("status")
      distance match
        case Some(d) if d > 0 && d <= 3 =>
          assertEquals(state, "found", s"$window $from $to")
          assertEquals(rows.head.long("pathLength"), d.toLong)
          assertEquals(rows.size, d)
        case Some(0) => assertEquals(state, "trivial")
        case Some(_) => assertEquals(state, "unreachable-within-bound")
        case None    => assert(state != "found", s"$window $from $to")
    }
  }

  test("every found path is a chain of existing edges from source to target") {
    val edges = out.table("edges-coauthored")
    val found = out.table("path-evidence").filter(_.text("status") == "found")
    val perPair = found.groupBy(_.text("pairKey"))
    assertEquals(perPair.size, 7)
    perPair.values.foreach { rows =>
      val chain = rows.sortBy(_.long("hopIndex"))
      assertEquals(chain.head.text("fromId"), chain.head.text("sourceId"))
      assertEquals(chain.last.text("toId"), chain.head.text("targetId"))
      chain.zip(chain.drop(1)).foreach { (left, right) =>
        assertEquals(left.text("toId"), right.text("fromId"))
      }
      chain.foreach { hop =>
        val (a, b) = StableIds.orderedPair(hop.text("fromId"), hop.text("toId"))
        val edge = only(
          edges,
          "window" -> hop.text("window"),
          "researcherA" -> a,
          "researcherB" -> b
        )
        assertEquals(hop.text("edgeId"), edge.text("edgeId"))
      }
    }
  }
