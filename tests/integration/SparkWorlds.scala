package scholarchain.tests.analytics

// Hand-checkable worlds. Every expectation in the suites is derived from these
// definitions on paper, never from a pipeline run.
// format: off
object Worlds:
  val namesA: Map[String, String] = Map(
    "Ann" -> "Ann Lee", "UnA" -> "Ann Lee", "UnB" -> "Ann Lee",
    "Orc1" -> "Orc Person", "OrcPerson" -> "Orc Person"
  )

  private def au(token: String): Au = Spec.au(token, namesA)

  def w(key: String, year: Int, people: String*): Wk =
    Wk(key, Some(year), people.toVector.map(au))

  def undated(key: String, people: String*): Wk =
    Wk(key, None, people.toVector.map(au))

  // Two-author works, one per listed pair, keyed prefix1, prefix2, ...
  def links(prefix: String, year: Int, mode: PairMode, pairs: (String, String)*): Vector[Wk] =
    pairs.toVector.zipWithIndex.map { (pair, index) =>
      w(prefix + (index + 1), year, pair._1, pair._2).copy(pairs = mode)
    }

  def clique(prefix: String, year: Int, mode: PairMode, people: String*): Vector[Wk] =
    val all = people.indices.toVector.flatMap { i =>
      ((i + 1) until people.size).map(j => (people(i), people(j)))
    }
    links(prefix, year, mode, all*)

  val windowsA = "2000-2004,2005-2009,2003-2006,all"

  val worksA: Vector[Wk] =
    Vector(
      w("r1", 2000, "Rep", "X", "Y", "Z").copy(venue = Some("venue-a")),
      w("r2", 2001, "Rep", "X", "Y", "Z").copy(venue = Some("venue-a")),
      w("r3", 2001, "Rep", "X", "Y", "Z"),
      w("r4", 2002, "Rep", "X", "Y", "Z").copy(pairs = PairMode.Both),
      w("r5", 2003, "Rep", "X", "Y", "Z"),
      w("r6", 2004, "Rep", "X", "Y", "Z"),
      w("r7", 2010, "Rep", "X"),
      w("v1", 2001, "Wide", "c1", "c2", "c3", "c4"),
      w("v2", 2002, "Wide", "c5", "c6", "c7", "c8"),
      w("m1", 2002, "Mrg1", "Mrg2").copy(pairs = PairMode.Both),
      w("m1dup", 2002, "Mrg1", "Mrg2", "u:DupU")
        .copy(canonicalKey = Some("m1"), source = "crossref"),
      w("s1", 2001, "Sam", "Sam", "Tom"),
      w("un1", 2001, "Ann", "u:UnA", "f:UnB", "Ula"),
      w("un2", 2002, "f:Orc1", "Pat"),
      w("un3", 2002, "OrcPerson", "Quin"),
      w("so1", 2001, "solo"),
      w("gi1", 2001, "g1", "g2", "g3", "g4", "g5", "g6", "g7", "g8"),
      w("bd1", 2005, "bl1", "bl2"),
      w("bd2", 2009, "bl3", "bl4"),
      w("old1", 1999, "o1", "o2"),
      undated("ny1", "Ned", "Nora"),
      w("lx1", 2001, "Lia", "n:Lonely")
    ) ++
      clique("ka", 2001, PairMode.Reversed, "a1", "a2", "a3", "a4") ++
      clique("kb", 2001, PairMode.Reversed, "b1", "b2", "b3", "b4") ++
      links("br", 2006, PairMode.Normal, "a4" -> "b1") ++
      links("pa", 2001, PairMode.Normal, "p1" -> "p2", "p2" -> "p3", "p3" -> "p4") ++
      links("tr", 2001, PairMode.Normal, "t1" -> "t2", "t2" -> "t3", "t1" -> "t3", "t3" -> "t4") ++
      links("lo", 2001, PairMode.Normal, "q1" -> "q2", "q2" -> "q3", "q1" -> "q3", "q1" -> "qw", "qw" -> "qu") ++
      links("ln", 2001, PairMode.Normal, "l1" -> "l2", "l2" -> "l3", "l3" -> "l4", "l4" -> "l5") ++
      links("di", 2001, PairMode.Normal, "ds" -> "dx", "ds" -> "dy", "dx" -> "dt", "dy" -> "dt")

  // One pair whose second mention does not exist, one whose mentions belong to
  // two different works, and a work link for a work that has no record.
  val worldA: World = World(
    worksA,
    extraPairs = Vector(
      PairRow("r2", Ids.mention("r2", 1), "mention:v1:" + "ab" * 32, 1, 2),
      PairRow("r3", Ids.mention("r3", 1), Ids.mention("r2", 2), 1, 2)
    ),
    ghostWorkLinks = Vector("ghost")
  )

  def a(person: String): String = Spec.nodeId(worldA, person)

  // window, from, to
  val pathPairsA: Vector[(String, String, String)] = Vector(
    ("2000-2004", a("p1"), a("p4")),
    ("2000-2004", a("p4"), a("p1")),
    ("2000-2004", a("ds"), a("dt")),
    ("2000-2004", a("p1"), a("t1")),
    ("2000-2004", a("l1"), a("l5")),
    ("2000-2004", a("l1"), a("l4")),
    ("2000-2004", a("p1"), a("p1")),
    ("2000-2004", a("Rep"), a("X")),
    ("2000-2004", a("a4"), a("b1")),
    ("2000-2004", a("p1"), Ids.researcher("not-in-the-graph")),
    ("2005-2009", a("p1"), a("p2")),
    ("2003-2006", a("a4"), a("b1")),
    ("all", a("a1"), a("b4"))
  )

  val windowsC1 = "2001-2001,1990-1995,all"

  // Duplicate input rows: a repeated author-paper row and pair row (dp1), a
  // repeated works row (dw1) and a repeated researcher link row (dl1). Then
  // defective links: x2 of cm1 has no row in the canonical table, the link of
  // y2 in lw1 names another work, and the cluster mc0 has no record of its
  // own (mc1 and mc2 are its members, mc1 has the smaller ID).
  val worldC1: World = World(
    links("pa", 2001, PairMode.Normal, "d1" -> "d2", "d2" -> "d3", "d3" -> "d4") ++
      Vector(
        w("dp1", 2001, "e1", "e2"),
        w("dw1", 2001, "f1", "f2"),
        w("dl1", 2001, "h1", "h2"),
        w("cm1", 2001, "x1", "x2"),
        w("lw1", 2001, "y1", "y2"),
        w("mc1", 2001, "k1", "k2").copy(canonicalKey = Some("mc0")),
        w("mc2", 2001, "k1", "k3").copy(canonicalKey = Some("mc0")),
        undated("yr1", "u1", "u2"),
        w("yr2", 2001, "u1", "u2").copy(canonicalKey = Some("yr1"))
      ),
    duplicateWorkRows = Vector("dw1"),
    duplicateAuthorRows = Vector(("dp1", 1)),
    duplicatePairRows = Vector(("dp1", 1, 2)),
    duplicateLinkRows = Vector(("dl1", 1)),
    orphans = Vector("x2"),
    wrongLinkWork = Vector(("lw1", 2))
  )

  val windowsC2 = "2001-2001,all"

  // The same shape with no accepted links at all.
  val worldC2: World = World(
    links("pa", 2001, PairMode.Normal, "d1" -> "d2", "d2" -> "d3", "d3" -> "d4") ++
      Vector(
        w("x1", 2001, "e1", "e2"),
        w("x2", 2001, "f1", "f2"),
        w("x3", 2001, "h1", "h2")
      ),
    emptyLinks = true
  )

  // The world C2 with one work dated a year later: the only change.
  val worldC2b: World = worldC2.copy(
    works = worldC2.works.map(x => if x.key == "x3" then x.copy(year = Some(2002)) else x)
  )
