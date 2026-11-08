package scholarchain.spark

import org.apache.spark.sql.Row

class TemporalSuite extends SparkSuite:
  private val ddl =
    "window STRING, researcherId STRING, publicationCount BIGINT, " +
      "distinctCoauthorCount BIGINT, averageCoauthorsPerPaper DOUBLE, " +
      "maximumCoauthorsOnOnePaper BIGINT, weightedDegree DOUBLE, " +
      "participationCoefficient DOUBLE, crossCommunityDegree BIGINT, " +
      "distinctCommunitiesConnected BIGINT, coreness INT, componentId STRING, " +
      "componentSize BIGINT, communityId STRING, communitySize BIGINT"

  private def metric(
      window: String,
      id: String,
      p: Long,
      c: Long,
      a: Double,
      m: Long,
      w: Double,
      part: Double,
      cross: Long,
      connected: Long,
      core: Int,
      component: (String, Long),
      community: (String, Long)
  ): Row =
    Row(
      window,
      id,
      p,
      c,
      a,
      m,
      w,
      part,
      cross,
      connected,
      core,
      component._1,
      component._2,
      community._1,
      community._2
    )

  private val windows = Windows.parse("2000-2004,2005-2009,all").toOption.get

  private lazy val metrics = frame(ddl)(
    metric(
      "2000-2004",
      "r1",
      2,
      1,
      1.0,
      1,
      2.0,
      0.0,
      0,
      0,
      1,
      ("c1", 3),
      ("k1", 2)
    ),
    metric(
      "2000-2004",
      "r2",
      1,
      1,
      1.0,
      1,
      1.0,
      0.0,
      0,
      0,
      1,
      ("c1", 3),
      ("k1", 2)
    ),
    metric(
      "2000-2004",
      "r3",
      1,
      1,
      1.0,
      1,
      1.0,
      0.0,
      0,
      0,
      1,
      ("c1", 3),
      ("k3", 1)
    ),
    metric(
      "2005-2009",
      "r1",
      3,
      2,
      1.5,
      2,
      3.0,
      0.5,
      1,
      1,
      2,
      ("c9", 4),
      ("k2", 2)
    ),
    metric(
      "2005-2009",
      "r2",
      1,
      1,
      1.0,
      1,
      1.0,
      0.0,
      0,
      0,
      1,
      ("c9", 4),
      ("k2", 2)
    ),
    metric(
      "2005-2009",
      "r4",
      1,
      1,
      1.0,
      1,
      1.0,
      0.0,
      0,
      0,
      1,
      ("c9", 4),
      ("k4", 1)
    ),
    metric(
      "2005-2009",
      "r5",
      1,
      1,
      1.0,
      1,
      1.0,
      0.0,
      0,
      0,
      1,
      ("c9", 4),
      ("k5", 1)
    )
  )

  test("consecutive window changes and inclusion rules") {
    assertEquals(
      Windows.consecutivePairs(windows).map((f, t) => (f.label, t.label)),
      Vector(("2000-2004", "2005-2009"))
    )
    val context = algo()
    val pairs = Temporal.windowPairs(session, windows)
    val comparison = Temporal.compare(context, metrics, pairs, Fixtures.context)
    val byId =
      rows(comparison).map(r => r.getAs[String]("researcherId") -> r).toMap
    assertEquals(byId.keySet, Set("r1", "r2", "r3", "r4", "r5"))
    assertEquals(
      byId.view.mapValues(_.getAs[String]("presence")).toMap,
      Map(
        "r1" -> "both",
        "r2" -> "both",
        "r3" -> "only-from",
        "r4" -> "only-to",
        "r5" -> "only-to"
      )
    )
    val r1 = byId("r1")
    assertEquals(r1.getAs[Long]("publicationCountChange"), 1L)
    assertEquals(r1.getAs[Long]("distinctCoauthorCountChange"), 1L)
    assertEqualsDouble(
      r1.getAs[Double]("averageCoauthorsPerPaperChange"),
      0.5,
      1e-12
    )
    assertEquals(r1.getAs[Long]("maximumCoauthorsOnOnePaperChange"), 1L)
    assertEqualsDouble(r1.getAs[Double]("weightedDegreeChange"), 1.0, 1e-12)
    assertEqualsDouble(
      r1.getAs[Double]("participationCoefficientChange"),
      0.5,
      1e-12
    )
    assertEquals(r1.getAs[Long]("crossCommunityDegreeChange"), 1L)
    assertEquals(r1.getAs[Long]("distinctCommunitiesConnectedChange"), 1L)
    assertEquals(r1.getAs[Int]("corenessChange"), 1)
    assertEqualsDouble(r1.getAs[Double]("componentOverlap"), 0.4, 1e-12)
    assertEqualsDouble(r1.getAs[Double]("communityOverlap"), 1.0, 1e-12)
    assertEquals(r1.getAs[Boolean]("comparable"), true)
    val r2 = byId("r2")
    assertEquals(r2.getAs[Long]("distinctCoauthorCountChange"), 0L)
    assertEqualsDouble(r2.getAs[Double]("componentOverlap"), 0.4, 1e-12)
    val r3 = byId("r3")
    assertEquals(r3.getAs[Boolean]("comparable"), false)
    Vector(
      "publicationCountChange",
      "distinctCoauthorCountChange",
      "averageCoauthorsPerPaperChange",
      "participationCoefficientChange",
      "componentOverlap",
      "communityOverlap"
    ).foreach { column =>
      assert(r3.isNullAt(r3.fieldIndex(column)), s"$column is null for r3")
    }
    assert(byId("r4").isNullAt(byId("r4").fieldIndex("publicationCountChange")))
    assertEquals(r3.getAs[Long]("publicationCountFrom"), 1L)
    assert(r3.isNullAt(r3.fieldIndex("publicationCountTo")))

    val summary = rows(
      Temporal.summary(
        context,
        comparison,
        Windowing.frame(session, windows),
        7L,
        Fixtures.context
      )
    )
    assertEquals(summary.size, 1)
    val s = summary.head
    assertEquals(s.getAs[Long]("researchersInBoth"), 2L)
    assertEquals(s.getAs[Long]("researchersInFrom"), 3L)
    assertEquals(s.getAs[Long]("researchersInTo"), 4L)
    assertEquals(s.getAs[Long]("onlyInFrom"), 1L)
    assertEquals(s.getAs[Long]("onlyInTo"), 2L)
    assertEqualsDouble(
      s.getAs[Double]("meanDistinctCoauthorChange"),
      0.5,
      1e-12
    )
    assertEqualsDouble(
      s.getAs[Double]("meanAverageTeamSizeChange"),
      0.25,
      1e-12
    )
    assertEqualsDouble(s.getAs[Double]("meanBridgeChange"), 0.25, 1e-12)
    assertEqualsDouble(s.getAs[Double]("meanComponentOverlap"), 0.4, 1e-12)
    assertEqualsDouble(s.getAs[Double]("meanCommunityOverlap"), 1.0, 1e-12)
    assertEquals(s.getAs[Int]("fromYears"), 5)
    assertEquals(s.getAs[Boolean]("sameWindowLength"), true)
    assertEquals(s.getAs[Long]("worksWithoutYearExcluded"), 7L)
    assertEquals(s.getAs[String]("inclusionRule"), Temporal.inclusionRule)
    assertEquals(s.getAs[String]("limitations"), Temporal.limitations)
  }
