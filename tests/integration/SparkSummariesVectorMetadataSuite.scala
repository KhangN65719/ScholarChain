package scholarchain.tests.analytics

import java.nio.file.Files
import org.apache.spark.sql.{DataFrame, Row as SparkRow}
import org.apache.spark.sql.types.StructType
import scala.jdk.CollectionConverters.*
import scholarchain.core.YearWindow
import scholarchain.spark.{
  Algo,
  JsonRows,
  NamedWindow,
  RunContext,
  Summaries,
  Telemetry,
  Windowing
}
import scholarchain.storage.{
  AwsVectorBucket,
  ErrorCode,
  HashEmbedder,
  InMemoryTableSource,
  LoadBounds,
  MetaValue,
  Row,
  S3VectorsRules,
  SemanticVectorLoader,
  StorageException,
  SummaryFacets,
  VectorDocuments,
  VectorIndexes,
  VectorMetadata,
  VectorRecord
}
import scholarchain.tests.adversarial.P8

// Work order 102: S run 7 failed because S3 Vectors refuses an empty array in
// metadata. Spark `Summaries.build` is the producer of the lists that were empty
// (a singleton community with no heavy internal edge, a path that is trivial or
// unreachable, a work with no evidence nodes). This suite runs the real Summaries
// SQL on a tiny graph that has each of those shapes, converts the rows exactly as
// JsonlExport does, and passes every row through the storage metadata builder,
// the in-process validator and the wire rules checker.
class SparkSummariesVectorMetadataSuite extends SparkBase:
  private val ctx = RunContext(
    RunContext.runIdFor("work-order-102"),
    "hadoop-snapshot:abc123def456",
    "run:v1:11111111-1111-4111-8111-111111111111",
    "run:v1:22222222-2222-4222-8222-222222222222",
    RunContext.transformVersion
  )

  // A child session: same context, its own SQL configuration and temp views,
  // so the settings below leave the shared harness session as it was.
  private lazy val session =
    val child = Harness.session.newSession()
    child.conf.set("spark.sql.shuffle.partitions", "4")
    child.conf.set("spark.sql.adaptive.enabled", "false")
    child.conf.set("spark.sql.autoBroadcastJoinThreshold", "-1")
    child.conf.set("spark.sql.session.timeZone", "UTC")
    child

  private def frame(ddl: String)(rows: SparkRow*): DataFrame =
    session.createDataFrame(rows.asJava, StructType.fromDDL(ddl))

  // Window w. r1 and r2 are one community (k1) with an internal edge. r3 works
  // alone: a singleton community (k3) with no internal edge, so the community
  // summary has no representative work. The paths are one found, one trivial
  // (r3 to r3) and one unreachable (r1 to r3, different components); the last
  // two have no hop rows and so no supporting work.
  private val metrics = frame(
    "window STRING, windowStart INT, windowEnd INT, researcherId STRING, " +
      "publicationCount BIGINT, distinctCoauthorCount BIGINT, " +
      "totalCoauthorOccurrences BIGINT, averageCoauthorsPerPaper DOUBLE, " +
      "maximumCoauthorsOnOnePaper BIGINT, maximumTeamWorkIds ARRAY<STRING>, " +
      "weightedDegree DOUBLE, unresolvedCoauthorOccurrences BIGINT, " +
      "componentSize BIGINT, communityId STRING, communitySize BIGINT, " +
      "coreness INT, participationCoefficient DOUBLE"
  )(
    SparkRow(
      "w",
      2000,
      2004,
      "r1",
      1L,
      1L,
      1L,
      1.0,
      1L,
      Seq("work-1"),
      1.0,
      0L,
      2L,
      "k1",
      2L,
      1,
      0.0
    ),
    SparkRow(
      "w",
      2000,
      2004,
      "r2",
      1L,
      1L,
      1L,
      1.0,
      1L,
      Seq("work-1"),
      1.0,
      0L,
      2L,
      "k1",
      2L,
      1,
      0.0
    ),
    SparkRow(
      "w",
      2000,
      2004,
      "r3",
      1L,
      0L,
      0L,
      0.0,
      0L,
      Seq("work-3"),
      0.0,
      0L,
      1L,
      "k3",
      1L,
      0,
      0.0
    )
  )

  private val researchers = frame(
    "nodeId STRING, displayName STRING, nodeKind STRING, orcid STRING"
  )(
    SparkRow("r1", "Ada Lovelace", "canonical", null),
    SparkRow("r2", "Bob Bee", "canonical", null),
    SparkRow("r3", "Cy Solo", "canonical", null)
  )

  private val communities = frame(
    "window STRING, communityId STRING, communitySize BIGINT, " +
      "internalEdges BIGINT, crossEdges BIGINT"
  )(
    SparkRow("w", "k1", 2L, 1L, 0L),
    SparkRow("w", "k3", 1L, 0L, 0L)
  )

  private val edgeCommunities = frame(
    "window STRING, edgeId STRING, a STRING, b STRING, communityA STRING, " +
      "communityB STRING, internal BOOLEAN, weight BIGINT, " +
      "representativeWorkIds ARRAY<STRING>"
  )(SparkRow("w", "e1", "r1", "r2", "k1", "k1", true, 1L, Seq("work-1")))

  private val paths = frame(
    "pairKey STRING, window STRING, sourceId STRING, targetId STRING, " +
      "status STRING, unreachableReason STRING, hopLimit INT, " +
      "pathLength INT, hopIndex INT, fromId STRING, toId STRING, " +
      "jointPublicationCount BIGINT, supportingWorkIds ARRAY<STRING>"
  )(
    SparkRow(
      "w|r1|r2",
      "w",
      "r1",
      "r2",
      "found",
      null,
      6,
      1,
      1,
      "r1",
      "r2",
      1L,
      Seq("work-1")
    ),
    SparkRow(
      "w|r3|r3",
      "w",
      "r3",
      "r3",
      "trivial",
      null,
      6,
      0,
      null,
      null,
      null,
      null,
      null
    ),
    SparkRow(
      "w|r1|r3",
      "w",
      "r1",
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
    SparkRow("work-1", "Paper One", 2001, "V1", "10.1/x"),
    SparkRow("work-3", "Solo Paper", 2002, null, null)
  )

  private val team = frame(
    "canonicalWorkId STRING, teamSize BIGINT, unresolvedAuthors BIGINT"
  )(SparkRow("work-1", 2L, 0L), SparkRow("work-3", 1L, 0L))

  private def jsonRows(data: DataFrame): Vector[Row] =
    val schema = data.schema
    data.collect().toVector.map { r =>
      ujson.read(JsonRows.line(schema, r)).obj.toMap
    }

  // The real Summaries SQL; the rows as JsonlExport writes them.
  private lazy val summaries: Vector[Row] =
    val windows = Windowing.frame(
      session,
      Vector(NamedWindow("w", YearWindow(2000, 2004)))
    )
    val algo = new Algo(
      session,
      new Telemetry(),
      100,
      42L,
      5000,
      Files.createTempDirectory("sc-wo102-checkpoint-")
    )
    jsonRows(
      Summaries.build(
        algo,
        metrics,
        researchers,
        communities,
        edgeCommunities,
        paths,
        works,
        team,
        windows,
        10,
        ctx
      )
    )

  private def ids(row: Row, column: String): Vector[String] =
    row(column).arr.toVector.map(_.str)

  private def named(kind: String, subject: String): Row =
    val found = summaries.filter(r =>
      r("summaryType").str == kind && r("subjectId").str == subject
    )
    assertEquals(found.size, 1, s"$kind $subject")
    found.head

  private val facetTables: Map[String, Vector[Row]] = Map(
    "nodes-work" -> Vector(
      Map(
        "canonicalWorkId" -> ujson.Str("work-1"),
        "year" -> ujson.Num(2001),
        "source" -> ujson.Null
      ),
      Map(
        "canonicalWorkId" -> ujson.Str("work-3"),
        "year" -> ujson.Num(2002),
        "source" -> ujson.Null
      )
    ),
    "researcher-metrics" -> jsonRows(metrics)
  )

  private def tables: InMemoryTableSource =
    new InMemoryTableSource(facetTables + ("semantic-summaries" -> summaries))

  private def emptyLists(md: Map[String, MetaValue]): Vector[String] =
    md.toVector.collect { case (k, MetaValue.Strs(v)) if v.isEmpty => k }

  private def wire(record: VectorRecord): Unit =
    S3VectorsRules.check(
      record.key,
      VectorDocuments.metadata(record.metadata),
      AwsVectorBucket.NonFilterableKeys
    )

  // Item 1 ------------------------------------------------------------------

  test("Summaries.build yields the empty lists the service refuses") {
    assertEquals(summaries.size, 10)
    assertEquals(
      summaries
        .map(_("summaryType").str)
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap,
      Map(
        "researcher-profile" -> 3,
        "community" -> 2,
        "collaboration-path" -> 3,
        "work" -> 2
      )
    )
    // The singleton community, the trivial path and the unreachable path have
    // no supporting work; both work rows have no evidence node.
    val noWorks = summaries.filter(ids(_, "evidenceWorkIds").isEmpty)
    assertEquals(
      noWorks.map(r => (r("summaryType").str, r("subjectId").str)).toSet,
      Set(
        ("community", "k3"),
        ("collaboration-path", "w|r3|r3"),
        ("collaboration-path", "w|r1|r3")
      )
    )
    val noNodes = summaries.filter(ids(_, "evidenceNodeIds").isEmpty)
    assertEquals(
      noNodes.map(r => (r("summaryType").str, r("subjectId").str)).toSet,
      Set(("work", "work-1"), ("work", "work-3"))
    )
    // The singleton community is one member in one community of size 1.
    assertEquals(ids(named("community", "k3"), "evidenceNodeIds"), Vector("r3"))
    // The trivial path is the same node twice; it still names it.
    assertEquals(
      ids(named("collaboration-path", "w|r3|r3"), "evidenceNodeIds"),
      Vector("r3", "r3")
    )
  }

  // Item 2 ------------------------------------------------------------------

  test("every row's metadata has no empty list and passes the wire rules") {
    val facets = SummaryFacets.fromTables(tables, LoadBounds.Default)
    val embedder = new HashEmbedder
    summaries.foreach { row =>
      val clue = s"${row("summaryType").str} ${row("subjectId").str}"
      val record = SemanticVectorLoader.record(row, embedder, "ref", facets)
      val md = record.metadata
      assertEquals(emptyLists(md), Vector.empty, clue)
      VectorMetadata.validate(md)
      wire(record)
      // An empty Spark list is an absent key; a list with ids is kept (cut to
      // the reference bound) and the count of evidence works still reads true.
      Vector("evidenceWorkIds", "evidenceNodeIds").foreach { column =>
        val source = ids(row, column)
        assertEquals(md.contains(column), source.nonEmpty, s"$clue $column")
        if source.nonEmpty then
          md(column) match
            case MetaValue.Strs(kept) =>
              assertEquals(kept, source.take(kept.size), s"$clue $column")
              assert(kept.nonEmpty, s"$clue $column")
            case other => fail(s"$clue $column was $other")
      }
      assertEquals(
        md("evidenceWorkCount"),
        MetaValue.Num(ids(row, "evidenceWorkIds").size.toDouble),
        clue
      )
      // The facet keys obey the same rule: no source list without a source.
      assert(!md.contains("source"), clue)
      assert(!md.contains("topic") && !md.contains("institution"), clue)
    }
    val singleton =
      SemanticVectorLoader.record(
        named("community", "k3"),
        embedder,
        "ref",
        facets
      )
    assert(!singleton.metadata.contains("evidenceWorkIds"))
    assertEquals(
      singleton.metadata("evidenceNodeIds"),
      MetaValue.Strs(Vector("r3"))
    )
    val work = SemanticVectorLoader.record(
      named("work", "work-3"),
      embedder,
      "ref",
      facets
    )
    assert(!work.metadata.contains("evidenceNodeIds"))
    assertEquals(
      work.metadata("evidenceWorkIds"),
      MetaValue.Strs(Vector("work-3"))
    )
  }

  // Item 3 ------------------------------------------------------------------

  test("the checker is not vacuous: an empty list forced in is refused") {
    val facets = SummaryFacets.fromTables(tables, LoadBounds.Default)
    val record = SemanticVectorLoader.record(
      named("community", "k3"),
      new HashEmbedder,
      "ref",
      facets
    )
    Vector("evidenceWorkIds", "evidenceNodeIds").foreach { column =>
      val forced = record.copy(metadata =
        record.metadata + (column -> MetaValue.Strs(Vector.empty))
      )
      val invalid = intercept[StorageException](
        VectorMetadata.validate(forced.metadata)
      )
      assertEquals(invalid.code, ErrorCode.MetadataInvalid, column)
      val rejected = intercept[StorageException](wire(forced))
      assertEquals(rejected.code, ErrorCode.AwsRejected, column)
      assert(
        rejected.getMessage.contains(S3VectorsRules.EmptyArrayMessage),
        rejected.getMessage
      )
    }
  }

  // Item 4 ------------------------------------------------------------------

  test("ingesting the Spark summaries through the enforcing fakes writes all") {
    val rig = new P8.Rig()
    val report = rig.ingest(tables, run = ctx.runId)
    assertEquals(report.rejected, Vector.empty)
    assertEquals(report.failedBatches, Vector.empty)
    assertEquals(report.unmapped, Vector.empty)
    assertEquals(report.written, summaries.size)
    val perIndex = P8.indexNames.map { index =>
      index -> rig.vectors(index)
    }.toMap
    assertEquals(
      perIndex.view.mapValues(_.size).toMap,
      Map(
        VectorIndexes.ResearcherProfile -> 3,
        VectorIndexes.CollaborationCommunity -> 2,
        VectorIndexes.PaperAbstract -> 2,
        VectorIndexes.CollaborationPath -> 3,
        VectorIndexes.TopicInstitutionSummary -> 0
      )
    )
    val stored = perIndex.values.flatten.toVector
    assertEquals(stored.size, summaries.size)
    val bySummary = summaries.map(r => r("summaryId").str -> r).toMap
    stored.foreach { record =>
      assertEquals(emptyLists(record.metadata), Vector.empty, record.key)
      wire(record)
      val row = bySummary(record.key)
      Vector("evidenceWorkIds", "evidenceNodeIds").foreach { column =>
        assertEquals(
          record.metadata.contains(column),
          ids(row, column).nonEmpty,
          s"${record.key} $column"
        )
      }
    }
  }
