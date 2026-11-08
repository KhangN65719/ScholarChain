package scholarchain.tests.analytics

import java.nio.file.{Files, Paths}

// Items 3, 4, 5 and 7: components, communities, bridge measures and coreness
// on the hand-checkable shapes of world A, per window.
class SparkGraphMeasuresSuite extends SparkBase:
  private def out: Output = done(Runs.a)

  private def node(person: String): String = Worlds.a(person)

  private def ids(people: Vector[String]): Vector[String] = people.map(node)

  private def range(prefix: String, to: Int): Vector[String] =
    (1 to to).toVector.map(i => s"$prefix$i")

  private val rep = Vector("Rep", "X", "Y", "Z")
  private val wide = Vector("Wide") ++ range("c", 8)
  private val un1 = Vector("Ann", "UnA", "UnB", "Ula")
  private val path = range("p", 4)
  private val tri = range("t", 4)
  private val lolli = Vector("q1", "q2", "q3", "qw", "qu")
  private val ka = range("a", 4)
  private val kb = range("b", 4)
  private val long5 = range("l", 5)
  private val diamond = Vector("ds", "dx", "dy", "dt")
  private val giant = range("g", 8)
  private val pairs = Vector(
    Vector("Mrg1", "Mrg2"),
    Vector("Sam", "Tom"),
    Vector("Orc1", "Pat"),
    Vector("OrcPerson", "Quin"),
    Vector("Lia", "Lonely")
  )

  // Connected groups of window 2000-2004: 17 groups, 66 researchers.
  private val groups2000: Vector[Vector[String]] =
    Vector(rep, wide, un1, path, tri, lolli, ka, kb, long5, diamond, giant) ++
      pairs ++ Vector(Vector("solo"))

  // Window all: the bridge a4-b1 joins ka and kb; three more pairs appear.
  private val groupsAll: Vector[Vector[String]] =
    Vector(rep, wide, un1, path, tri, lolli, ka ++ kb, long5, diamond, giant) ++
      pairs ++ Vector(
        Vector("solo"),
        Vector("bl1", "bl2"),
        Vector("bl3", "bl4")
      ) ++
      Vector(Vector("o1", "o2"))

  private val groups2005 = Vector(
    Vector("a4", "b1"),
    Vector("bl1", "bl2"),
    Vector("bl3", "bl4")
  )

  private val groups2003 = Vector(rep, Vector("bl1", "bl2"), Vector("a4", "b1"))

  private val allWindows = Vector("2000-2004", "2005-2009", "2003-2006", "all")

  private def membership(window: String): Map[String, String] =
    where(out.table("component-membership"), "window" -> window)
      .map(r => r.text("researcherId") -> r.text("componentId"))
      .toMap

  private def communityOf(window: String): Map[String, String] =
    where(out.table("community-membership"), "window" -> window)
      .map(r => r.text("researcherId") -> r.text("communityId"))
      .toMap

  private def edgesOf(window: String): Vector[(String, String, Long)] =
    where(out.table("edges-coauthored"), "window" -> window).map { e =>
      (
        e.text("researcherA"),
        e.text("researcherB"),
        e.long("jointPublicationCount")
      )
    }

  private def nodesOf(window: String): Set[String] =
    where(out.table("researcher-metrics"), "window" -> window)
      .map(_.text("researcherId"))
      .toSet

  private def checkGroups(
      window: String,
      groups: Vector[Vector[String]]
  ): Unit =
    val of = membership(window)
    assertEquals(of.size, groups.map(_.size).sum, s"nodes of $window")
    val comps = groups.map { group =>
      val labels = ids(group).map(of).toSet
      assertEquals(labels.size, 1, s"$window one component for $group")
      assertEquals(labels.head, ids(group).min, s"smallest id names $group")
      labels.head
    }
    assertEquals(comps.toSet.size, groups.size, s"$window distinct components")
    val sizes = where(out.table("components"), "window" -> window)
    assertEquals(sizes.size, groups.size, s"component rows of $window")
    groups.zip(comps).foreach { (group, comp) =>
      val row = only(sizes, "componentId" -> comp)
      assertEquals(row.long("componentSize"), group.size.toLong, group.toString)
    }

  test("components of window 2000-2004: path, triangle, cliques, singleton") {
    checkGroups("2000-2004", groups2000)
  }

  test("components of window all: the bridge joins two cliques") {
    checkGroups("all", groupsAll)
  }

  test("components of the narrow windows") {
    checkGroups("2005-2009", groups2005)
    checkGroups("2003-2006", groups2003)
  }

  test("component summary: sizes, largest fraction and singletons") {
    val summary = out.table("component-summary")
    // Window 2000-2004: 66 researchers, 97 edges, 17 components, largest 9
    // (the two K5 of Wide), one singleton (solo).
    val w2000 = only(summary, "window" -> "2000-2004")
    assertEquals(w2000.long("nodeCount"), 66L)
    assertEquals(w2000.long("edgeCount"), 97L)
    assertEquals(w2000.long("componentCount"), 17L)
    assertEquals(w2000.long("largestComponentSize"), 9L)
    close(w2000.num("largestComponentFraction"), 9.0 / 66, "fraction 2000-2004")
    assertEquals(w2000.long("singletonCount"), 1L)
    // Window all: 72 researchers, 101 edges, 19 components, largest 9.
    val all = only(summary, "window" -> "all")
    assertEquals(all.long("nodeCount"), 72L)
    assertEquals(all.long("edgeCount"), 101L)
    assertEquals(all.long("componentCount"), 19L)
    assertEquals(all.long("largestComponentSize"), 9L)
    close(all.num("largestComponentFraction"), 9.0 / 72, "fraction all")
    assertEquals(all.long("singletonCount"), 1L)
    // Window 2005-2009: three pairs, no singleton, fraction 2 / 6.
    val w2005 = only(summary, "window" -> "2005-2009")
    assertEquals(w2005.long("componentCount"), 3L)
    close(w2005.num("largestComponentFraction"), 2.0 / 6, "fraction 2005-2009")
    assertEquals(w2005.long("singletonCount"), 0L)
    // Window 2003-2006: components of 4, 2 and 2; fraction 4 / 8.
    val w2003 = only(summary, "window" -> "2003-2006")
    assertEquals(w2003.long("nodeCount"), 8L)
    assertEquals(w2003.long("edgeCount"), 8L)
    assertEquals(w2003.long("componentCount"), 3L)
    close(w2003.num("largestComponentFraction"), 0.5, "fraction 2003-2006")
  }

  test("exactly one component is the largest in each window") {
    val rows = out.table("components")
    allWindows.foreach { window =>
      val ofWindow = where(rows, "window" -> window)
      val largest = where(ofWindow, "isLargest" -> true)
      assertEquals(largest.size, 1, window)
      val top = ofWindow.map(_.long("componentSize")).max
      assertEquals(largest.head.long("componentSize"), top, window)
    }
    val sizes = where(rows, "window" -> "all").map(_.long("componentSize"))
    // 9, 8 (ka+kb), 8 (giant), 5 (lolli), 5 (long), five 4s, eight 2s, one 1.
    val expected =
      Vector(9, 8, 8, 5, 5, 4, 4, 4, 4, 4, 2, 2, 2, 2, 2, 2, 2, 2, 1)
    assertEquals(sizes.sorted.reverse, expected.map(_.toLong))
  }

  test("a reference union of the edge table gives the same components") {
    allWindows.foreach { window =>
      val edges = edgesOf(window).map((a, b, _) => (a, b))
      val groups = Oracles.components(nodesOf(window), edges)
      val of = membership(window)
      groups.foreach { group =>
        assertEquals(group.map(of).size, 1, s"$window $group")
      }
      assertEquals(groups.size, of.values.toSet.size, window)
      // Both endpoints of every edge share a component.
      edges.foreach((a, b) => assertEquals(of(a), of(b), window))
    }
  }

  // ---- communities ----

  test("every researcher has exactly one community, sizes add up") {
    allWindows.foreach { window =>
      val of = communityOf(window)
      assertEquals(of.keySet, nodesOf(window), window)
      val rows = where(out.table("community-membership"), "window" -> window)
      assertEquals(rows.size, of.size, s"one row per researcher in $window")
      val table = where(out.table("communities"), "window" -> window)
      assertEquals(
        table.map(_.long("communitySize")).sum,
        of.size.toLong,
        window
      )
      table.foreach { c =>
        val members = of.count(_._2 == c.text("communityId"))
        assertEquals(c.long("communitySize"), members.toLong, c.toString)
      }
      rows.foreach { r =>
        val members = of.count(_._2 == r.text("communityId"))
        assertEquals(r.long("communitySize"), members.toLong, r.toString)
      }
    }
  }

  test("a community never spans two components") {
    allWindows.foreach { window =>
      val comp = membership(window)
      val byCommunity = communityOf(window).groupBy(_._2)
      byCommunity.values.foreach { members =>
        val comps = members.keys.map(comp).toSet
        assertEquals(comps.size, 1, s"$window community spans components")
      }
    }
  }

  test("cliques, pairs and a triangle with a pendant are one community") {
    // A clique has one valid fixpoint, and so does an edge or a triangle with
    // a pendant node attached: a node needs its own label among the heaviest.
    val of = communityOf("2000-2004")
    val one = Vector(rep, un1, ka, kb, giant, tri) ++ pairs
    one.foreach { group =>
      assertEquals(ids(group).map(of).toSet.size, 1, group.toString)
    }
    val k8 = of(node("g1"))
    assertEquals(of.count(_._2 == k8), 8)
    val tableRow = only(
      out.table("communities"),
      "window" -> "2000-2004",
      "communityId" -> of(node("a1"))
    )
    // The ka clique: 4 researchers, 6 internal edges, none leaving.
    assertEquals(tableRow.long("communitySize"), 4L)
    assertEquals(tableRow.long("internalEdges"), 6L)
    assertEquals(tableRow.long("crossEdges"), 0L)
    assertEquals(tableRow.long("internalWeight"), 6L)
  }

  test("a path has several valid labelings: only the invariants hold") {
    // p1 can only follow p2 and p4 can only follow p3. The path is either one
    // community of 4 or two communities of 2.
    val of = communityOf("2000-2004")
    assertEquals(of(node("p1")), of(node("p2")))
    assertEquals(of(node("p3")), of(node("p4")))
    val sizes = path.map(node).map(of).groupBy(identity).values.map(_.size)
    assert(
      sizes.toVector.sorted == Vector(4) || sizes.toVector.sorted == Vector(
        2,
        2
      )
    )
    // The same holds for the four-cycle ds, dx, dt, dy and the lolli tail.
    val cycle = diamond.map(node).map(of).groupBy(identity).values.map(_.size)
    assert(
      cycle.toVector.sorted == Vector(4) || cycle.toVector.sorted == Vector(
        2,
        2
      )
    )
    assertEquals(of(node("qu")), of(node("qw")))
  }

  test(
    "the run reports convergence per window, and a fixpoint when converged"
  ) {
    val o = out
    val communities = o.json("run-manifest.json")("algorithms")("communities")
    val perWindow = communities("perWindow").obj
    assertEquals(perWindow.keySet.toSet, allWindows.toSet)
    perWindow.foreach { (window, state) =>
      assert(state("converged").bool, s"$window did not converge")
      assert(state("passesToFixpoint").num >= 1, window)
    }
    assertEquals(communities("allWindowsConverged").bool, true)
    assertEquals(communities("maxIterations").num.toInt, 100)
    allWindows.foreach { window =>
      val labels = communityOf(window)
      val votes = edgesOf(window).flatMap { (a, b, w) =>
        Vector((a, labels(b), w), (b, labels(a), w))
      }
      votes.groupBy(_._1).foreach { (node, all) =>
        val perLabel = all.groupBy(_._2).view.mapValues(_.map(_._3).sum).toMap
        val own = perLabel.getOrElse(labels(node), 0L)
        assert(perLabel.values.max <= own, s"$window: $node would move")
      }
    }
  }

  test("cross-community edges agree with the membership and the edge table") {
    allWindows.foreach { window =>
      val of = communityOf(window)
      val edges = edgesOf(window)
      val crossing = edges.filter((a, b, _) => of(a) != of(b))
      val communities = where(out.table("communities"), "window" -> window)
      assertEquals(
        communities.map(_.long("crossEdges")).sum,
        2L * crossing.size
      )
      val internal = edges.size - crossing.size
      assertEquals(
        communities.map(_.long("internalEdges")).sum,
        internal.toLong
      )
      val cross = where(out.table("community-cross-edges"), "window" -> window)
      assertEquals(cross.map(_.long("edgeCount")).sum, crossing.size.toLong)
      assertEquals(
        cross.map(_.long("totalWeight")).sum,
        crossing.map(_._3).sum,
        window
      )
      cross.foreach(c => assert(c.text("communityA") < c.text("communityB")))
    }
  }

  // ---- bridge measures ----

  // The documented formulas, computed here from the membership and edge tables.
  private def bridgeOf(window: String, who: String): Vector[Double] =
    val of = communityOf(window)
    val strengths = edgesOf(window)
      .flatMap((a, b, w) => Vector((a, b, w), (b, a, w)))
      .filter(_._1 == who)
    val byCommunity = strengths.groupBy(t => of(t._2))
    val perCommunity = byCommunity.view.mapValues(_.map(_._3).sum).toMap
    val total = perCommunity.values.sum.toDouble
    val outside = strengths.filter(t => of(t._2) != of(who))
    val others = perCommunity.keySet - of(who)
    val shares = perCommunity.values.map(s => (s / total) * (s / total))
    val participation = if total == 0 then 0.0 else 1.0 - shares.sum
    Vector(
      outside.size.toDouble,
      outside.map(_._3).sum.toDouble,
      others.size.toDouble,
      perCommunity.size.toDouble,
      participation
    )

  test("bridge measures equal the documented formulas on every researcher") {
    allWindows.foreach { window =>
      val rows = where(out.table("bridge"), "window" -> window)
      assertEquals(rows.size, nodesOf(window).size, window)
      rows.foreach { row =>
        val who = row.text("researcherId")
        val expected = bridgeOf(window, who)
        val clue = s"$window $who"
        close(row.num("crossCommunityDegree"), expected(0), s"degree $clue")
        close(row.num("crossCommunityStrength"), expected(1), s"strength $clue")
        close(row.num("distinctCommunitiesConnected"), expected(2), s"c $clue")
        close(row.num("distinctNeighborCommunities"), expected(3), s"n $clue")
        close(row.num("participationCoefficient"), expected(4), s"p $clue")
        close(row.num("bridgeScore"), expected(4), s"score $clue")
      }
    }
  }

  test("bridge values on two cliques joined by one edge, both valid outcomes") {
    // Window all: ka and kb are K4 and a4-b1 has weight 1. a4 sees a1, a2, a3
    // (weight 3 together) and b1 (weight 1), so a4 must follow ka. The only
    // fixpoints are one community of 8 or ka and kb apart.
    val of = communityOf("all")
    val split = of(node("a1")) != of(node("b1"))
    val eight = (ka ++ kb).map(node).map(of).toSet
    assertEquals(eight.size, if split then 2 else 1)
    assertEquals(ka.map(node).map(of).toSet.size, 1)
    assertEquals(kb.map(node).map(of).toSet.size, 1)
    val a4 = only(
      out.table("bridge"),
      "window" -> "all",
      "researcherId" -> node("a4")
    )
    val a1 = only(
      out.table("bridge"),
      "window" -> "all",
      "researcherId" -> node("a1")
    )
    // Apart: a4 has degree 4 and strength 4; cross degree 1, cross strength 1,
    // one other community, two neighbouring communities, participation
    // 1 - (3 / 4)^2 - (1 / 4)^2 = 1 - 10 / 16 = 0.375. a1 is inside: 0 and 0.
    // One community: every cross measure is 0 and every participation is 0.
    val expected =
      if split then Vector(1L, 1L, 1L, 2L) else Vector(0L, 0L, 0L, 1L)
    assertEquals(a4.long("crossCommunityDegree"), expected(0))
    assertEquals(a4.long("crossCommunityStrength"), expected(1))
    assertEquals(a4.long("distinctCommunitiesConnected"), expected(2))
    assertEquals(a4.long("distinctNeighborCommunities"), expected(3))
    close(
      a4.num("participationCoefficient"),
      if split then 0.375 else 0.0,
      "a4"
    )
    assertEquals(a1.long("crossCommunityDegree"), 0L)
    assertEquals(a1.long("distinctCommunitiesConnected"), 0L)
    assertEquals(a1.long("distinctNeighborCommunities"), 1L)
    close(a1.num("participationCoefficient"), 0.0, "a1")
    assertEquals(a4.long("degree"), 4L)
    assertEquals(a4.long("strength"), 4L)
  }

  test("bridge values in the windows where communities are forced") {
    // Window 2000-2004: ka and kb are separate components, so no researcher
    // has a cross-community coauthor. Window 2005-2009: a4-b1 is one pair.
    val rows = where(out.table("bridge"), "window" -> "2000-2004")
    (ka ++ kb).foreach { who =>
      val row = only(rows, "researcherId" -> node(who))
      assertEquals(row.long("crossCommunityDegree"), 0L, who)
      assertEquals(row.long("distinctCommunitiesConnected"), 0L, who)
      assertEquals(row.long("distinctNeighborCommunities"), 1L, who)
      close(row.num("participationCoefficient"), 0.0, who)
    }
    val a4 = only(
      out.table("bridge"),
      "window" -> "2005-2009",
      "researcherId" -> node("a4")
    )
    assertEquals(a4.long("degree"), 1L)
    assertEquals(a4.long("crossCommunityDegree"), 0L)
    assertEquals(a4.long("distinctNeighborCommunities"), 1L)
    close(a4.num("participationCoefficient"), 0.0, "pair")
  }

  test("the module defines every bridge measure and states its limits") {
    val bridge = out.json("run-manifest.json")("algorithms")("bridge")
    val defined = bridge("definitions").obj.keySet.toSet
    val measures = Set(
      "crossCommunityDegree",
      "crossCommunityStrength",
      "distinctCommunitiesConnected",
      "distinctNeighborCommunities",
      "participationCoefficient",
      "bridgeScore"
    )
    assertEquals(defined, measures)
    measures.foreach { m =>
      assert(bridge("definitions")(m).str.trim.nonEmpty, m)
    }
    val limits = bridge("limitations").str.toLowerCase
    assert(limits.contains("community"), limits)
    assert(limits.contains("betweenness"), limits)
    assert(limits.contains("local"), limits)
    val design = Files.readString(Paths.get("modules/spark/DESIGN.md"))
    measures.foreach(m => assert(design.contains(m), s"DESIGN.md misses $m"))
    assert(design.contains("Limitations"), "DESIGN.md states no limitations")
  }

  // ---- coreness ----

  private def coreOf(window: String): Map[String, Int] =
    where(out.table("coreness"), "window" -> window)
      .map(r => r.text("researcherId") -> r.long("coreness").toInt)
      .toMap

  private def checkCore(
      window: String,
      expected: Vector[(Vector[String], Int)]
  ): Unit =
    val core = coreOf(window)
    expected.foreach { (group, k) =>
      group.foreach { who =>
        assertEquals(core(node(who)), k, s"coreness of $who in $window")
      }
    }
    assertEquals(core.size, expected.map(_._1.size).sum, window)

  test("coreness: K4, K8, K5 pair, triangle with pendant, path, isolate") {
    // Hand derived by peeling: K4 gives 3, K8 gives 7, the two K5 sharing
    // Wide give 4 to every node, a triangle gives 2 and its pendant 1, a
    // 4-cycle gives 2, every tree and edge gives 1, an isolate gives 0.
    checkCore(
      "2000-2004",
      Vector(
        (rep, 3),
        (wide, 4),
        (un1, 3),
        (ka, 3),
        (kb, 3),
        (giant, 7),
        (path, 1),
        (long5, 1),
        (diamond, 2),
        (Vector("t1", "t2", "t3"), 2),
        (Vector("t4"), 1),
        (Vector("q1", "q2", "q3"), 2),
        (Vector("qw", "qu"), 1),
        (Vector("solo"), 0),
        (pairs.flatten, 1)
      )
    )
  }

  test("coreness needing several rounds: a node whose neighbour peels first") {
    // lolli: triangle q1 q2 q3, then q1-qw and qw-qu. qw starts with degree 2
    // but at level 2 qu (degree 1) goes first, which leaves qw with degree 1:
    // so qw is 1, not 2. The path p1..p4 takes two rounds the same way.
    val core = coreOf("2000-2004")
    assertEquals(core(node("qu")), 1)
    assertEquals(core(node("qw")), 1)
    assertEquals(core(node("q1")), 2)
    path.foreach(p => assertEquals(core(node(p)), 1))
  }

  test("coreness in the other windows") {
    // With the bridge a4-b1 the K4 cliques keep coreness 3 and a4, b1 are
    // peeled with the others at level 4: 8 nodes of coreness 3.
    checkCore(
      "all",
      Vector(
        (rep, 3),
        (wide, 4),
        (un1, 3),
        (ka ++ kb, 3),
        (giant, 7),
        (path, 1),
        (long5, 1),
        (diamond, 2),
        (Vector("t1", "t2", "t3"), 2),
        (Vector("t4"), 1),
        (Vector("q1", "q2", "q3"), 2),
        (Vector("qw", "qu"), 1),
        (Vector("solo"), 0),
        (pairs.flatten, 1),
        (Vector("bl1", "bl2", "bl3", "bl4", "o1", "o2"), 1)
      )
    )
    checkCore("2005-2009", Vector((groups2005.flatten, 1)))
    checkCore(
      "2003-2006",
      Vector((rep, 3), (Vector("bl1", "bl2", "a4", "b1"), 1))
    )
  }

  test("a reference peeling of the edge table agrees on every researcher") {
    allWindows.foreach { window =>
      val edges = edgesOf(window).map((a, b, _) => (a, b))
      val expected = Oracles.coreness(nodesOf(window), edges)
      assertEquals(coreOf(window), expected, window)
      where(out.table("researcher-metrics"), "window" -> window).foreach { m =>
        val who = m.text("researcherId")
        assertEquals(m.long("coreness").toInt, expected(who), window)
      }
    }
  }

  // ---- sampled path length ----

  test("sampled path length and effective diameter, exact when all sampled") {
    val rows = out.table("path-length-estimates")
    // Window 2000-2004: the largest component is Wide with c1..c8: 9 nodes.
    // Distances: Wide to each c is 1, c to c of its own K5 is 1, c to c of
    // the other K5 is 2. Per source: Wide 8 * 1 = 8; each c: 1 + 3 + 4 * 2 = 12.
    // Sum over 9 sources = 8 + 8 * 12 = 104 over 72 ordered pairs, with 40 at
    // distance 1 and 32 at distance 2: the 90 percent mark needs distance 2.
    Vector("2000-2004", "all").foreach { window =>
      val row = only(rows, "window" -> window)
      assertEquals(row.long("componentSize"), 9L, window)
      assert(row.flag("allNodesSampled"), window)
      assertEquals(row.long("pairsMeasured"), 72L, window)
      close(row.num("averagePathLength"), 104.0 / 72, window)
      assertEquals(row.long("effectiveDiameter90"), 2L, window)
      assertEquals(row.long("maxObservedDistance"), 2L, window)
      assertEquals(row.long("unreachedPairsDueToDepthCap"), 0L, window)
    }
    // Window 2005-2009 has pairs only: 2 ordered pairs, all at distance 1.
    val pair = only(rows, "window" -> "2005-2009")
    assertEquals(pair.long("componentSize"), 2L)
    assertEquals(pair.long("pairsMeasured"), 2L)
    close(pair.num("averagePathLength"), 1.0, "pair")
    assertEquals(pair.long("effectiveDiameter90"), 1L)
    // Window 2003-2006: the K4 of Rep: 12 ordered pairs at distance 1.
    val k4 = only(rows, "window" -> "2003-2006")
    assertEquals(k4.long("componentSize"), 4L)
    assertEquals(k4.long("pairsMeasured"), 12L)
    close(k4.num("averagePathLength"), 1.0, "k4")
    assertEquals(k4.long("maxObservedDistance"), 1L)
  }
