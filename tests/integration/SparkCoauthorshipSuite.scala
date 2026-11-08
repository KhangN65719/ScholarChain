package scholarchain.tests.analytics

import scholarchain.core.StableIds

// Items 1 and 2 of the work order: distinct coauthors, pair weights, identity.
// World A (see SparkWorlds). Hand derivations are next to each expectation.
class SparkCoauthorshipSuite extends SparkBase:
  private def out: Output = done(Runs.a)

  private def node(person: String): String = Worlds.a(person)

  private def metric(window: String, person: String): Rec =
    only(
      out.table("researcher-metrics"),
      "window" -> window,
      "researcherId" -> node(person)
    )

  private def edge(window: String, x: String, y: String): Rec =
    val (a, b) = StableIds.orderedPair(node(x), node(y))
    only(
      out.table("edges-coauthored"),
      "window" -> window,
      "researcherA" -> a,
      "researcherB" -> b
    )

  private def hasEdge(window: String, x: String, y: String): Boolean =
    val (a, b) = StableIds.orderedPair(node(x), node(y))
    where(
      out.table("edges-coauthored"),
      "window" -> window,
      "researcherA" -> a,
      "researcherB" -> b
    ).nonEmpty

  final case class Expect(
      window: String,
      person: String,
      p: Long,
      c: Long,
      t: Long,
      a: Double,
      m: Long,
      w: Double,
      maxWorks: Set[String]
  )

  private def check(e: Expect): Unit =
    val row = metric(e.window, e.person)
    val clue = s"${e.person} in ${e.window}"
    assertEquals(row.long("publicationCount"), e.p, s"P $clue")
    assertEquals(row.long("distinctCoauthorCount"), e.c, s"C $clue")
    assertEquals(row.long("totalCoauthorOccurrences"), e.t, s"T $clue")
    close(row.num("averageCoauthorsPerPaper"), e.a, s"A $clue")
    assertEquals(row.long("maximumCoauthorsOnOnePaper"), e.m, s"M $clue")
    close(row.num("weightedDegree"), e.w, s"W $clue")
    assertEquals(row.strings("maximumTeamWorkIds").toSet, e.maxWorks, clue)
    assertEquals(
      row.strings("maximumTeamWorkIds").size,
      e.maxWorks.size,
      s"M works listed once each $clue"
    )

  private val repWorks = works("r1", "r2", "r3", "r4", "r5", "r6")

  test("run A succeeds and reports the counts derived by hand") {
    val o = out
    // 55 works rows, one a duplicate record of m1, so 54 canonical works.
    assertEquals(count(o, "input.works"), 55L)
    assertEquals(count(o, "canonical.works"), 54L)
    assertEquals(count(o, "canonical.mergedWorks"), 1L)
    assertEquals(count(o, "canonical.worksWithoutYear"), 1L)
    // 137 author-paper rows; DupU (unlinked, duplicate record) is no node.
    assertEquals(count(o, "input.authorPapers"), 137L)
    assertEquals(count(o, "canonical.authored"), 136L)
    assertEquals(count(o, "canonical.authoredNotCounted"), 2L)
    // 70 canonical researchers plus UnA, UnB, Orc1 and Lonely.
    assertEquals(count(o, "canonical.researchers"), 74L)
    assertEquals(count(o, "canonical.unresolvedMentionNodes"), 4L)
    assertEquals(count(o, "canonical.unresolvedMentionsInDuplicateRecords"), 1L)
    // Nodes per window 66, 6, 8 and 72; edges 97, 3, 8 and 101.
    assertEquals(count(o, "nodes.windowed"), 152L)
    assertEquals(count(o, "edges.coauthored"), 209L)
  }

  test("breadth versus count: C, P, T, A, M and W per window and all") {
    // Rep: six papers with the same three coauthors, team of 4 each:
    // P = 6, C = 3, T = 6 * 3 = 18, A = 3, M = 3, W = 18 (3 edges of weight 6).
    check(Expect("2000-2004", "Rep", 6, 3, 18, 3.0, 3, 18, repWorks))
    check(Expect("2000-2004", "X", 6, 3, 18, 3.0, 3, 18, repWorks))
    // Window 2003-2006 holds only r5 (2003) and r6 (2004): P = 2, T = 6.
    check(Expect("2003-2006", "Rep", 2, 3, 6, 3.0, 3, 6, works("r5", "r6")))
    // all adds r7 (2010, Rep and X only): P = 7, T = 18 + 1, W = 7 + 6 + 6.
    check(Expect("all", "Rep", 7, 3, 19, 19.0 / 7, 3, 19, repWorks))
    check(Expect("all", "X", 7, 3, 19, 19.0 / 7, 3, 19, repWorks))
    // Y is in r1 to r6 only: P = 6, T = 18, W = 3 * 6.
    check(Expect("all", "Y", 6, 3, 18, 3.0, 3, 18, repWorks))
    // Wide: two papers of 5 authors with disjoint coauthors:
    // P = 2, C = 8, T = 8, A = 4, M = 4 on both papers, W = 8 edges of weight 1.
    check(Expect("2000-2004", "Wide", 2, 8, 8, 4.0, 4, 8, works("v1", "v2")))
    check(Expect("all", "Wide", 2, 8, 8, 4.0, 4, 8, works("v1", "v2")))
    check(Expect("2000-2004", "c1", 1, 4, 4, 4.0, 4, 4, works("v1")))
    check(Expect("2000-2004", "c5", 1, 4, 4, 4.0, 4, 4, works("v2")))
  }

  test("count and breadth rank researchers differently") {
    val rep = metric("2000-2004", "Rep")
    val wide = metric("2000-2004", "Wide")
    assert(rep.long("publicationCount") > wide.long("publicationCount"))
    assert(
      rep.long("distinctCoauthorCount") < wide.long("distinctCoauthorCount")
    )
  }

  test("researchers are present only in the windows their works fall in") {
    val windows = out.table("researcher-metrics")
    val rep =
      where(windows, "researcherId" -> node("Rep")).map(_.text("window"))
    assertEquals(rep.toSet, Set("2000-2004", "2003-2006", "all"))
    val wide =
      where(windows, "researcherId" -> node("Wide")).map(_.text("window"))
    assertEquals(wide.toSet, Set("2000-2004", "all"))
    val ned = where(windows, "researcherId" -> node("Ned"))
    assertEquals(ned, Vector.empty[Rec], "undated work: in no window")
  }

  test("ties in M list every work, and only the tied works") {
    // Rep in all: r1 to r6 have team 4 (3 coauthors); r7 has team 2.
    assertEquals(
      metric("all", "Rep").strings("maximumTeamWorkIds").toSet,
      repWorks
    )
    // Both Wide papers have 4 coauthors: a two way tie.
    assertEquals(
      metric("2000-2004", "Wide").strings("maximumTeamWorkIds").toSet,
      works("v1", "v2")
    )
    // A single author work: M = 0 and the work is listed.
    check(Expect("2000-2004", "solo", 1, 0, 0, 0.0, 0, 0, works("so1")))
  }

  test("a giant team expands to n(n-1)/2 pairs mapped to the right nodes") {
    val members = (1 to 8).map(i => node(s"g$i")).toSet
    val inside = out
      .table("edges-coauthored")
      .filter(e =>
        members(e.text("researcherA")) && members(e.text("researcherB"))
      )
    val ofWindow = where(inside, "window" -> "2000-2004")
    // 8 * 7 / 2 = 28 edges, each backed by the one work, weight 1.
    assertEquals(ofWindow.size, 28)
    assert(ofWindow.forall(_.long("jointPublicationCount") == 1L))
    val pairs =
      ofWindow.map(e => (e.text("researcherA"), e.text("researcherB")))
    assertEquals(pairs.toSet.size, 28)
    (1 to 8).foreach { i =>
      check(Expect("2000-2004", s"g$i", 1, 7, 7, 7.0, 7, 7, works("gi1")))
    }
  }

  test("pair weights, years and undirected identity") {
    // Rep and X share r1..r6 in 2000-2004; r7 (2010) makes 7 in all.
    val inWindow = edge("2000-2004", "Rep", "X")
    assertEquals(inWindow.long("jointPublicationCount"), 6L)
    assertEquals(inWindow.long("firstCollaborationYear"), 2000L)
    assertEquals(inWindow.long("lastCollaborationYear"), 2004L)
    val overlap = edge("2003-2006", "Rep", "X")
    assertEquals(overlap.long("jointPublicationCount"), 2L)
    assertEquals(overlap.long("firstCollaborationYear"), 2003L)
    assertEquals(overlap.long("lastCollaborationYear"), 2004L)
    val whole = edge("all", "Rep", "X")
    assertEquals(whole.long("jointPublicationCount"), 7L)
    assertEquals(whole.long("firstCollaborationYear"), 2000L)
    assertEquals(whole.long("lastCollaborationYear"), 2010L)
    assertEquals(edge("all", "Rep", "Y").long("jointPublicationCount"), 6L)
    assertEquals(edge("2000-2004", "X", "Y").long("jointPublicationCount"), 6L)
    val single = edge("2000-2004", "Wide", "c1")
    assertEquals(single.long("jointPublicationCount"), 1L)
    assertEquals(single.long("firstCollaborationYear"), 2001L)
    assertEquals(
      edge("2000-2004", "Wide", "c5").long("firstCollaborationYear"),
      2002L
    )
    assertEquals(edge("all", "o1", "o2").long("firstCollaborationYear"), 1999L)
  }

  test("a pair given in both orders is one undirected edge") {
    // r4 and m1 emit every pair in both orders, ka and kb emit them reversed.
    assertEquals(
      edge("2000-2004", "Rep", "X").long("jointPublicationCount"),
      6L
    )
    assertEquals(
      edge("2000-2004", "Mrg1", "Mrg2").long("jointPublicationCount"),
      1L
    )
    assertEquals(
      edge("2000-2004", "a1", "a2").long("jointPublicationCount"),
      1L
    )
    assertEquals(
      edge("2000-2004", "b3", "b4").long("jointPublicationCount"),
      1L
    )
    val edges = out.table("edges-coauthored")
    edges.foreach { e =>
      assert(e.text("researcherA") < e.text("researcherB"), e.toString)
    }
    val keys = edges.map(e =>
      (e.text("window"), e.text("researcherA"), e.text("researcherB"))
    )
    assertEquals(keys.toSet.size, keys.size, "one row per undirected pair")
    val perWindow = edges.groupBy(_.text("window")).view.mapValues(_.size).toMap
    // Edges per window, derived by hand in SparkWorlds: 97, 3, 8 and 101.
    assertEquals(
      perWindow,
      Map("2000-2004" -> 97, "2005-2009" -> 3, "2003-2006" -> 8, "all" -> 101)
    )
  }

  test("edge support points at the works behind each edge") {
    val whole = edge("all", "Rep", "X")
    // Five representative works, earliest first: r1, r2, r3 (2000 to 2001),
    // r4 (2002) and r5 (2003); r6 and r7 are left out by the limit of 5.
    assertEquals(
      whole.strings("representativeWorkIds").toSet,
      works("r1", "r2", "r3", "r4", "r5")
    )
    assertEquals(whole.text("supportTable"), "edge-support")
    val support = where(
      out.table("edge-support"),
      "window" -> "all",
      "edgeId" -> whole.text("edgeId")
    )
    // The support table holds all seven works of the edge.
    assertEquals(
      support.map(_.text("canonicalWorkId")).toSet,
      works("r1", "r2", "r3", "r4", "r5", "r6", "r7")
    )
  }

  test("a merged work cluster counts once") {
    // m1 and its duplicate record m1dup are one canonical work: both authors
    // have P = 1 and the edge has weight 1, not 2.
    check(Expect("2000-2004", "Mrg1", 1, 1, 1, 1.0, 1, 1, works("m1")))
    check(Expect("2000-2004", "Mrg2", 1, 1, 1, 1.0, 1, 1, works("m1")))
    val nodes = out.table("nodes-work")
    val merged = only(nodes, "canonicalWorkId" -> Ids.work("m1"))
    assertEquals(merged.long("memberCount"), 2L)
    assertEquals(
      merged.strings("memberWorkIds").toSet,
      works("m1", "m1dup")
    )
    assertEquals(where(nodes, "canonicalWorkId" -> Ids.work("m1dup")).size, 0)
    val support = where(
      out.table("edge-support"),
      "window" -> "2000-2004",
      "edgeId" -> edge("2000-2004", "Mrg1", "Mrg2").text("edgeId")
    )
    assertEquals(support.size, 1)
  }

  test("two mentions of one canonical researcher give no edge") {
    // s1 is [Sam, Sam, Tom]: the pair of Sam with Sam is dropped, and Sam with
    // Tom is given twice but backed by one work. Team size is 2, not 3.
    check(Expect("2000-2004", "Sam", 1, 1, 1, 1.0, 1, 1, works("s1")))
    check(Expect("2000-2004", "Tom", 1, 1, 1, 1.0, 1, 1, works("s1")))
    assertEquals(
      edge("2000-2004", "Sam", "Tom").long("jointPublicationCount"),
      1L
    )
    out.table("edges-coauthored").foreach { e =>
      assertNotEquals(e.text("researcherA"), e.text("researcherB"))
    }
    assertEquals(count(out, "pairs.self-pair"), 1L)
  }

  test("an unresolved mention is its own node, never merged by name") {
    val nodes = out.table("nodes-researcher")
    val named = nodes.filter(r => r.text("displayName") == "Ann Lee")
    // The canonical Ann plus two unresolved mentions of the same name.
    assertEquals(named.size, 3)
    val canonical = only(named, "nodeKind" -> "canonical")
    assertEquals(canonical.text("nodeId"), node("Ann"))
    val loose = where(named, "nodeKind" -> "unresolved-mention")
    assertEquals(
      loose.map(_.text("nodeId")).toSet,
      Set(node("UnA"), node("UnB"))
    )
    assert(loose.forall(_.text("nodeId").startsWith("mention:")))
    // Ann has 3 coauthors: UnA, UnB and Ula; two of them unresolved.
    val ann = metric("2000-2004", "Ann")
    assertEquals(ann.long("distinctCoauthorCount"), 3L)
    assertEquals(ann.long("unresolvedCoauthorOccurrences"), 2L)
    assertEquals(ann.long("distinctUnresolvedCoauthorCount"), 2L)
    val una = metric("2000-2004", "UnA")
    assertEquals(una.text("nodeKind"), "unresolved-mention")
    assertEquals(una.long("publicationCount"), 1L)
    assertEquals(una.long("unresolvedCoauthorOccurrences"), 1L)
    assertEquals(
      metric("2000-2004", "Ula").long("unresolvedCoauthorOccurrences"),
      2L
    )
  }

  test("unresolved and integrity-failure mentions keep their reasons") {
    val table = out.table("unresolved-mentions")
    val una = only(table, "mentionId" -> node("UnA"))
    assertEquals(una.text("reason"), "unlinked")
    assert(una.flag("isNode"))
    val unb = only(table, "mentionId" -> node("UnB"))
    assertEquals(unb.text("reason"), "integrity-failure")
    assertEquals(
      only(table, "mentionId" -> node("Orc1")).text("reason"),
      "integrity-failure"
    )
    assertEquals(
      only(table, "mentionId" -> node("Lonely")).text("reason"),
      "no-link-row"
    )
    // The unlinked mention of the duplicate record m1dup is listed, not a node.
    val dup = only(table, "mentionId" -> Ids.mention("m1dup", 3))
    assert(!dup.flag("isNode"))
    val ids = out.table("nodes-researcher").map(_.text("nodeId")).toSet
    assert(!ids(Ids.mention("m1dup", 3)))
  }

  test(
    "an ORCID bearing mention in an integrity-failed cluster is not merged"
  ) {
    // Orc1 has the name of OrcPerson but its cluster failed: it stays a
    // separate node and OrcPerson keeps only the work un3.
    val failed = node("Orc1")
    assert(failed.startsWith("mention:"))
    assertNotEquals(failed, node("OrcPerson"))
    check(Expect("2000-2004", "OrcPerson", 1, 1, 1, 1.0, 1, 1, works("un3")))
    val pat = metric("2000-2004", "Pat")
    assertEquals(pat.long("unresolvedCoauthorOccurrences"), 1L)
    assertEquals(
      metric("2000-2004", "Orc1").long("publicationCount"),
      1L
    )
    assert(hasEdge("2000-2004", "Orc1", "Pat"))
    assert(!hasEdge("2000-2004", "Orc1", "Quin"))
    assert(!hasEdge("2000-2004", "Orc1", "OrcPerson"))
  }

  test("an edge with an unresolved endpoint says so and keeps an edge ID") {
    val row = edge("2000-2004", "Ann", "UnA")
    val flags =
      Vector(row.flag("endpointAUnresolved"), row.flag("endpointBUnresolved"))
    assertEquals(flags.count(identity), 1)
    // An unresolved (mention) endpoint takes the core mention-coauthor ID, the
    // value StableIds.coauthorEdgeFor derives from the row's own endpoints,
    // window and policy; the retired Spark-local coauthor:v1 form is refused.
    val id = row.text("edgeId")
    assert(id.matches("mention-coauthor:v1:[0-9a-f]{64}"), id)
    assert(!id.startsWith("coauthor:v1:"), id)
    val expected = StableIds.coauthorEdgeFor(
      row.text("researcherA"),
      row.text("researcherB"),
      scholarchain.core.YearWindow(
        row.long("windowStart").toInt,
        row.long("windowEnd").toInt
      ),
      row.text("policyVersion")
    )
    assertEquals(id, expected)
    assert(
      row.text("researcherA").startsWith("mention:") ||
        row.text("researcherB").startsWith("mention:")
    )
    val plain = edge("2000-2004", "Rep", "X")
    assert(
      !plain.flag("endpointAUnresolved") && !plain.flag("endpointBUnresolved")
    )
    // A resolved pair keeps the researcher-pair form and its derived ID.
    val plainId = plain.text("edgeId")
    assert(plainId.matches("coauthor:v1:[0-9a-f]{64}"), plainId)
    assertEquals(
      plainId,
      StableIds.coauthorEdgeFor(
        plain.text("researcherA"),
        plain.text("researcherB"),
        scholarchain.core.YearWindow(
          plain.long("windowStart").toInt,
          plain.long("windowEnd").toInt
        ),
        plain.text("policyVersion")
      )
    )
  }

  test("defective pairs are counted and recorded, not turned into edges") {
    val o = out
    // Pair rows: 141 mapped (r4 and m1 twice, s1 twice), 1 self-pair, 3 pairs of
    // the duplicate record m1dup, 1 missing endpoint, 1 work mismatch.
    assertEquals(count(o, "pairs.mapped"), 141L)
    assertEquals(count(o, "pairs.self-pair"), 1L)
    assertEquals(count(o, "pairs.duplicate-record"), 3L)
    assertEquals(count(o, "pairs.endpoint-missing"), 1L)
    assertEquals(count(o, "pairs.work-mismatch"), 1L)
    assertEquals(count(o, "input.pairCandidates"), 147L)
    // Distinct mapped pairs equal the 133 pairs the teams imply.
    assertEquals(count(o, "pairs.expectedFromTeams"), 133L)
    assertEquals(count(o, "integrityViolations"), 4L)
    val reasons = o.table("integrity-violations").map(_.text("reason")).toSet
    assertEquals(
      reasons,
      Set(
        "pair-endpoint-unknown",
        "pair-work-mismatch",
        "mention-without-link",
        "work-link-without-record"
      )
    )
    // The mismatched pair was Rep with X in r3: the weight stays 6.
    assertEquals(
      edge("2000-2004", "Rep", "X").long("jointPublicationCount"),
      6L
    )
  }

  test("every authored edge points at an existing researcher and work") {
    val o = out
    val researchers = o.table("nodes-researcher").map(_.text("nodeId")).toSet
    val canonicalWorks =
      o.table("nodes-work").map(_.text("canonicalWorkId")).toSet
    val authored = o.table("edges-authored")
    assertEquals(authored.size, 136)
    assert(authored.forall(e => researchers(e.text("researcherNodeId"))))
    assert(authored.forall(e => canonicalWorks(e.text("canonicalWorkId"))))
    assertEquals(authored.map(_.text("mentionId")).toSet.size, 136)
    assertEquals(authored.count(e => !e.flag("countedInMetrics")), 2)
    assertEquals(researchers.size, 74)
    assertEquals(canonicalWorks.size, 54)
  }

  test("venue, topic and institution tables merge their input rows") {
    val o = out
    val venue = only(o.table("nodes-venue"), "venueId" -> "venue-a")
    assertEquals(venue.long("canonicalWorkCount"), 2L)
    assertEquals(o.table("edges-published-in").size, 2)
    // Two input rows for topic-a: the smaller name wins, both are counted.
    val topic = only(o.table("nodes-topic"), "topicId" -> "topic-a")
    assertEquals(topic.text("name"), "Alpha")
    assertEquals(topic.long("inputRows"), 2L)
    assertEquals(o.table("nodes-institution").size, 1)
  }
