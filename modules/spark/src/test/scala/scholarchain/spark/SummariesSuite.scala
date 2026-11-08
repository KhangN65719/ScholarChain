package scholarchain.spark

import org.apache.spark.sql.Row
import scholarchain.core.YearWindow
import scholarchain.spark.Fixtures.{string, strings}

class SummariesSuite extends SparkSuite:
  private val metrics = frame(
    "window STRING, windowStart INT, windowEnd INT, researcherId STRING, " +
      "publicationCount BIGINT, distinctCoauthorCount BIGINT, " +
      "totalCoauthorOccurrences BIGINT, averageCoauthorsPerPaper DOUBLE, " +
      "maximumCoauthorsOnOnePaper BIGINT, maximumTeamWorkIds ARRAY<STRING>, " +
      "weightedDegree DOUBLE, unresolvedCoauthorOccurrences BIGINT, " +
      "componentSize BIGINT, communityId STRING, communitySize BIGINT, " +
      "coreness INT, participationCoefficient DOUBLE"
  )(
    Row(
      "w",
      2000,
      2004,
      "r1",
      3L,
      2L,
      4L,
      4.0 / 3.0,
      2L,
      Seq("work-1"),
      4.0,
      1L,
      3L,
      "k1",
      2L,
      2,
      0.5
    ),
    Row(
      "w",
      2000,
      2004,
      "r2",
      2L,
      2L,
      3L,
      1.5,
      2L,
      Seq("work-2"),
      3.0,
      0L,
      3L,
      "k1",
      2L,
      2,
      0.0
    ),
    Row(
      "w",
      2000,
      2004,
      "mention:u1",
      1L,
      1L,
      1L,
      1.0,
      1L,
      Seq("work-2"),
      1.0,
      0L,
      3L,
      "k2",
      1L,
      1,
      0.0
    )
  )

  // r2 and r4 share an ORCID (a split person); mention:u1 is unresolved.
  private val researchers = frame(
    "nodeId STRING, displayName STRING, nodeKind STRING, orcid STRING"
  )(
    Row("r1", "Ada Lovelace", "canonical", "0000-0000-0000-0001"),
    Row("r2", "Bob Bee", "canonical", "0000-0000-0000-0002"),
    Row("r3", "Grace Hopper", "canonical", null),
    Row("r4", "Zed Zoe", "canonical", "0000-0000-0000-0002"),
    Row("mention:u1", "Una U", "unresolved-mention", null)
  )

  private val communities = frame(
    "window STRING, communityId STRING, communitySize BIGINT, " +
      "internalEdges BIGINT, crossEdges BIGINT"
  )(Row("w", "k1", 2L, 1L, 0L))

  private val edgeCommunities = frame(
    "window STRING, edgeId STRING, a STRING, b STRING, communityA STRING, " +
      "communityB STRING, internal BOOLEAN, weight BIGINT, " +
      "representativeWorkIds ARRAY<STRING>"
  )(Row("w", "e1", "r1", "r2", "k1", "k1", true, 2L, Seq("work-1", "work-2")))

  private val paths = frame(
    "pairKey STRING, window STRING, sourceId STRING, targetId STRING, " +
      "status STRING, unreachableReason STRING, hopLimit INT, " +
      "pathLength INT, hopIndex INT, " +
      "fromId STRING, toId STRING, jointPublicationCount BIGINT, " +
      "supportingWorkIds ARRAY<STRING>"
  )(
    Row(
      "w|r1|r3",
      "w",
      "r1",
      "r3",
      "found",
      null,
      6,
      2,
      1,
      "r1",
      "r2",
      2L,
      Seq("work-1", "work-2")
    ),
    Row(
      "w|r1|r3",
      "w",
      "r1",
      "r3",
      "found",
      null,
      6,
      2,
      2,
      "r2",
      "r3",
      1L,
      Seq("work-3")
    ),
    Row(
      "w|r1|r4",
      "w",
      "r1",
      "r4",
      "unreachable-within-bound",
      "beyond-hop-limit",
      6,
      null,
      null,
      null,
      null,
      null,
      null
    ),
    Row(
      "w|r4|r3",
      "w",
      "r4",
      "r3",
      "unreachable-within-bound",
      "different-component",
      6,
      null,
      null,
      null,
      null,
      null,
      null
    )
  )

  private val works = frame(
    "canonicalWorkId STRING, title STRING, year INT, venueId STRING, doi STRING"
  )(
    Row("work-1", "Paper One", 2001, "V1", "10.1/x"),
    Row("work-2", "Paper Two", 2002, null, null)
  )

  private val team = frame(
    "canonicalWorkId STRING, teamSize BIGINT, unresolvedAuthors BIGINT"
  )(Row("work-1", 3L, 1L), Row("work-2", 2L, 0L))

  private lazy val summaries: Map[(String, String), Row] =
    val windows =
      Windowing.frame(session, Vector(NamedWindow("w", YearWindow(2000, 2004))))
    val built = Summaries.build(
      algo(),
      metrics,
      researchers,
      communities,
      edgeCommunities,
      paths,
      works,
      team,
      windows,
      2,
      Fixtures.context
    )
    rows(built)
      .map(r => (string(r, "summaryType"), string(r, "subjectId")) -> r)
      .toMap

  test("summaries cite evidence and invent no numbers") {
    assertEquals(summaries.size, 9)
    assertEquals(
      string(summaries(("researcher-profile", "r1")), "summaryText"),
      "Researcher Ada Lovelace in window w: 3 publications, 2 distinct coauthors, " +
        "4 coauthor occurrences, 1.33 average coauthors per paper, at most 2 " +
        "coauthors on one paper (work work-1), weighted degree 4, component of 3 " +
        "nodes, community of 2 nodes, coreness 2, participation " +
        "coefficient 0.500. Nodes are canonical researchers and unresolved " +
        "author mentions. 1 of the 4 coauthor occurrences are unresolved " +
        "author mentions."
    )
    assertEquals(
      string(summaries(("researcher-profile", "r2")), "summaryText"),
      "Researcher Bob Bee in window w: 2 publications, 2 distinct coauthors, " +
        "3 coauthor occurrences, 1.50 average coauthors per paper, at most 2 " +
        "coauthors on one paper (work work-2), weighted degree 3, component of 3 " +
        "nodes, community of 2 nodes, coreness 2, participation " +
        "coefficient 0.000. Nodes are canonical researchers and unresolved " +
        "author mentions. Uncertainty: other canonical researchers share this " +
        "ORCID, so this profile may cover only part of the record of one person."
    )
    assertEquals(
      string(summaries(("researcher-profile", "mention:u1")), "summaryText"),
      "Unresolved author mention Una U in window w: 1 publications, 1 distinct " +
        "coauthors, 1 coauthor occurrences, 1.00 average coauthors per paper, " +
        "at most 1 coauthors on one paper (work work-2), weighted degree 1, " +
        "component of 3 nodes, community of 1 nodes, coreness 1, participation " +
        "coefficient 0.000. Nodes are canonical researchers and unresolved " +
        "author mentions. This node is one author mention that no accepted " +
        "link joins to a canonical researcher; the same person may appear as " +
        "other nodes."
    )
    assertEquals(
      string(summaries(("researcher-profile", "mention:u1")), "nodeKind"),
      "unresolved-mention"
    )
    assertEquals(
      string(summaries(("researcher-profile", "r1")), "nodeKind"),
      "canonical"
    )
    assert(
      summaries(("community", "k1")).isNullAt(
        summaries(("community", "k1")).fieldIndex("nodeKind")
      )
    )
    val profile = summaries(("researcher-profile", "r1"))
    assertEquals(strings(profile, "evidenceWorkIds"), Vector("work-1"))
    assert(strings(profile, "evidenceMetrics").contains("publicationCount"))
    assertEquals(strings(profile, "evidenceNodeIds"), Vector("r1"))
    assertEquals(string(profile, "window"), "w")

    val community = summaries(("community", "k1"))
    assertEquals(
      string(community, "summaryText"),
      "Community k1 in window w has 2 nodes (canonical researchers and " +
        "unresolved author mentions), 1 internal collaboration " +
        "edges and 0 edges to other communities. Most connected members by " +
        "weighted degree: Ada Lovelace; Bob Bee."
    )
    assertEquals(
      strings(community, "evidenceWorkIds"),
      Vector("work-1", "work-2")
    )
    assertEquals(strings(community, "evidenceNodeIds"), Vector("r1", "r2"))

    val found = summaries(("collaboration-path", "w|r1|r3"))
    assertEquals(
      string(found, "summaryText"),
      "In window w the shortest collaboration path from Ada Lovelace to Grace " +
        "Hopper has 2 hops: Ada Lovelace and Bob Bee share 2 joint " +
        "publication(s) (works work-1, work-2); Bob Bee and Grace Hopper share 1 " +
        "joint publication(s) (works work-3)."
    )
    assertEquals(
      strings(found, "evidenceWorkIds"),
      Vector("work-1", "work-2", "work-3")
    )
    val unreachable = summaries(("collaboration-path", "w|r1|r4"))
    assertEquals(
      string(unreachable, "summaryText"),
      "In window w no collaboration path within the configured hop limit " +
        "connects Ada Lovelace and Zed Zoe."
    )
    assertEquals(
      string(summaries(("collaboration-path", "w|r4|r3")), "summaryText"),
      "In window w Zed Zoe and Grace Hopper are in different connected " +
        "components of the collaboration graph, so no collaboration path " +
        "connects them."
    )
    assert(
      strings(summaries(("work", "work-1")), "evidenceMetrics")
        .contains("year")
    )
    assert(
      strings(
        summaries(("researcher-profile", "r1")),
        "evidenceMetrics"
      ).contains("unresolvedCoauthorOccurrences")
    )

    assertEquals(
      string(summaries(("work", "work-1")), "summaryText"),
      "Work Paper One (work-1, year 2001) has 3 authors, of which 1 are " +
        "unresolved author mentions. Venue: V1. DOI: 10.1/x."
    )
    assertEquals(
      string(summaries(("work", "work-2")), "summaryText"),
      "Work Paper Two (work-2, year 2002) has 2 authors, of which 0 are " +
        "unresolved author mentions. Venue: none recorded. DOI: none recorded."
    )
    assertEquals(
      string(summaries(("work", "work-1")), "window"),
      "not-windowed"
    )
    summaries.values.foreach { r =>
      val provenance = r.getAs[Row]("provenance")
      assertEquals(string(provenance, "runId"), Fixtures.context.runId)
      assertEquals(
        string(provenance, "sourceUri"),
        "scholarchain:spark-analytics/semantic-summaries"
      )
      assert(string(r, "summaryId").startsWith("summary:v1:"))
      assert(strings(r, "evidenceMetrics").nonEmpty)
    }
  }
