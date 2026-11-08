package scholarchain.tests.analytics

// Item 14: semantic summaries contain no number that is not in the evidence
// columns they cite. A number is read from the text after the identifiers,
// names, titles and window labels have been removed from it.
class SparkSummariesSuite extends SparkBase:
  private def out: Output = done(Runs.a)

  private val windows = Vector("2000-2004", "2005-2009", "2003-2006", "all")

  private lazy val summaries: Vector[Rec] = out.table("semantic-summaries")

  private def ofType(kind: String): Vector[Rec] =
    where(summaries, "summaryType" -> kind)

  private lazy val names: Vector[String] =
    out
      .table("nodes-researcher")
      .filter(!_.isNull("displayName"))
      .map(_.text("displayName"))
      .distinct

  private lazy val titles: Vector[String] =
    out.table("nodes-work").map(_.text("title")).distinct

  private val number = """-?\d+(?:\.\d+)?""".r

  private def strip(row: Rec): Vector[String] =
    val own = Vector(row.text("subjectId")) ++
      row.strings("evidenceWorkIds") ++ row.strings("evidenceNodeIds")
    (own ++ names ++ titles ++ windows).distinct

  // The numbers of the text that match none of the allowed values.
  private def unexplained(row: Rec, allowed: Vector[Double]): Vector[String] =
    val longestFirst = strip(row).filter(_.nonEmpty).sortBy(s => -s.length)
    val cleaned = longestFirst.foldLeft(row.text("summaryText")) { (t, s) =>
      t.replace(s, " ")
    }
    val found = number.findAllIn(cleaned).toVector
    val unmatched = found.filter { n =>
      !allowed.exists(v => Math.abs(v - n.toDouble) < 0.0051)
    }
    unmatched.map(n => s"$n in '${row.text("summaryText")}'")

  test("summaries are built for every researcher, community, path and work") {
    val kinds = summaries.map(_.text("summaryType")).toSet
    assertEquals(
      kinds,
      Set("researcher-profile", "community", "collaboration-path", "work")
    )
    // One profile per researcher and window: 66 + 6 + 8 + 72.
    assertEquals(ofType("researcher-profile").size, 152)
    // One summary per selected pair: 13 lines of the pairs file.
    assertEquals(ofType("collaboration-path").size, 13)
    assertEquals(ofType("work").size, 20)
    val communities = out.table("communities")
    assertEquals(ofType("community").size, communities.size)
  }

  test("every summary lists the evidence it used, and the evidence exists") {
    val workIds = out.table("nodes-work").map(_.text("canonicalWorkId")).toSet
    val nodeIds = out.table("nodes-researcher").map(_.text("nodeId")).toSet
    // A path summary names the two researchers asked about, even when one of
    // them is not in the graph, so those are allowed as well.
    val asked = Worlds.pathPairsA.flatMap((_, from, to) => Vector(from, to))
    summaries.foreach { row =>
      val clue = s"${row.text("summaryType")} ${row.text("summaryId")}"
      val known =
        if row.text("summaryType") == "collaboration-path" then nodeIds ++ asked
        else nodeIds
      assert(row.text("summaryText").trim.nonEmpty, clue)
      assert(row.strings("evidenceMetrics").nonEmpty, clue)
      assert(row.strings("evidenceWorkIds").forall(workIds), clue)
      assert(row.strings("evidenceNodeIds").forall(known), clue)
      assert(row.text("summaryId").startsWith("summary:v1:"), clue)
    }
    val ids = summaries.map(_.text("summaryId"))
    assertEquals(ids.toSet.size, ids.size, "summary IDs are unique")
    // A profile cites the works behind its largest team.
    val wide = only(
      ofType("researcher-profile"),
      "window" -> "2000-2004",
      "subjectId" -> Worlds.a("Wide")
    )
    assertEquals(wide.strings("evidenceWorkIds").toSet, works("v1", "v2"))
  }

  test("researcher profile numbers are the metrics the row cites") {
    val metrics = out.table("researcher-metrics")
    val bad = ofType("researcher-profile").flatMap { row =>
      val m = only(
        metrics,
        "window" -> row.text("window"),
        "researcherId" -> row.text("subjectId")
      )
      val allowed = row.strings("evidenceMetrics").map(name => m.num(name))
      unexplained(row, allowed)
    }
    assertEquals(bad, Vector.empty[String], bad.take(4).mkString("\n"))
  }

  test("community summary numbers are the metrics the row cites") {
    val communities = out.table("communities")
    val metrics = out.table("researcher-metrics")
    val bad = ofType("community").flatMap { row =>
      val c = only(
        communities,
        "window" -> row.text("window"),
        "communityId" -> row.text("subjectId")
      )
      val members = row.strings("evidenceNodeIds").map { id =>
        only(metrics, "window" -> row.text("window"), "researcherId" -> id)
      }
      val allowed = Vector(
        c.num("communitySize"),
        c.num("internalEdges"),
        c.num("crossEdges")
      ) ++ members.map(_.num("weightedDegree"))
      unexplained(row, allowed)
    }
    assertEquals(bad, Vector.empty[String], bad.take(4).mkString("\n"))
  }

  private def pathAllowed(row: Rec): Vector[Double] =
    val rows =
      where(out.table("path-evidence"), "pairKey" -> row.text("subjectId"))
    rows.flatMap { r =>
      Vector("pathLength", "jointPublicationCount")
        .filter(c => !r.isNull(c))
        .map(c => r.num(c))
    }

  private def pathStatus(row: Rec): String =
    where(out.table("path-evidence"), "pairKey" -> row.text("subjectId")).head
      .text("status")

  test("found path summaries cite their length and hop weights") {
    val found =
      ofType("collaboration-path").filter(r => pathStatus(r) == "found")
    assertEquals(found.size, 7)
    val bad = found.flatMap(row => unexplained(row, pathAllowed(row)))
    assertEquals(bad, Vector.empty[String], bad.take(4).mkString("\n"))
  }

  test("an unreachable path summary cites every number it states") {
    // The text says "no path of at most N hops": N is the hop limit, so the
    // row has to cite it among its evidence metrics.
    val rows = ofType("collaboration-path").filter { r =>
      pathStatus(r) == "unreachable-within-bound"
    }
    assertEquals(rows.size, 3)
    val bad = rows.flatMap(row => unexplained(row, pathAllowed(row)))
    assertEquals(bad, Vector.empty[String], bad.take(4).mkString("\n"))
  }

  test("trivial and out-of-window path summaries state no number") {
    val rows = ofType("collaboration-path").filter { r =>
      Set("trivial", "endpoint-not-in-window").contains(pathStatus(r))
    }
    assertEquals(rows.size, 3)
    val bad = rows.flatMap(row => unexplained(row, pathAllowed(row)))
    assertEquals(bad, Vector.empty[String], bad.mkString("\n"))
  }

  test("work summaries state the team size, unresolved authors and year") {
    val world = Worlds.worldA
    val dated =
      world.works.filter(w => w.year.isDefined && w.canonicalKey.isEmpty)
    def nodes(w: Wk): Vector[String] =
      w.authors.map(a => Spec.nodeId(world, a.person)).distinct
    def team(w: Wk): Int = nodes(w).size
    def unresolved(w: Wk): Int = nodes(w).count(_.startsWith("mention:"))
    // The 20 works with the largest teams, ties by ID: the giant work (8),
    // v1 and v2 (5), r1 to r6 and un1 (4), then the smallest IDs of the teams of 2.
    val ranked = dated.sortBy(w => (-team(w), Ids.work(w.key)))
    val expected = ranked.take(20).map(w => Ids.work(w.key)).toSet
    val rows = ofType("work")
    assertEquals(rows.map(_.text("subjectId")).toSet, expected)
    val bad = rows.flatMap { row =>
      val w = dated.find(d => Ids.work(d.key) == row.text("subjectId")).get
      val allowed =
        Vector(team(w).toDouble, unresolved(w).toDouble, w.year.get.toDouble)
      unexplained(row, allowed)
    }
    assertEquals(bad, Vector.empty[String], bad.take(4).mkString("\n"))
  }
