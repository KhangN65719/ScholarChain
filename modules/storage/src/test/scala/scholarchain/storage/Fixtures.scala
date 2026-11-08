package scholarchain.storage

import java.nio.file.{Files, Path}
import scholarchain.core.{StableIds, YearWindow}

/** A hand-built Spark-shaped world: two researchers, one unresolved mention,
  * three works, one venue. Edge IDs come from `StableIds.coauthorEdgeFor`, the
  * function Spark's `EdgeIds.edgeId` calls.
  */
object Fixtures:
  val runId = "run:v1:11111111-1111-4111-8111-111111111111"
  val policy = "scholarchain.spark.coauthor-policy/1"

  val w1: String = StableIds.work("dblp", "conf/a/1")
  val w2: String = StableIds.work("dblp", "conf/a/2")
  val w3: String = StableIds.work("dblp", "conf/a/3")
  val r1: String = StableIds.researcherFromExternal("orcid", "0000-0001")
  val r2: String = StableIds.researcherFromExternal("orcid", "0000-0002")
  val m1: String = StableIds.mention(w1, 2)
  val venue = "venue:dblp:conf-a"

  def provenance(table: String, record: String): ujson.Value =
    ujson.Obj(
      "schemaVersion" -> 1,
      "source" -> "derived",
      "snapshotId" -> "hadoop-snapshot:s1",
      "sourceRecordId" -> record,
      "sourceUri" -> s"scholarchain:spark-analytics/$table",
      "transformVersion" -> "spark-analytics/1.1.0",
      "runId" -> runId
    )

  def window: YearWindow = YearWindow(2000, 2009)

  def edgeId(a: String, b: String): String =
    val (x, y) = StableIds.orderedPair(a, b)
    StableIds.coauthorEdgeFor(x, y, window, policy)

  def coauthorRow(
      a: String,
      b: String,
      count: Long,
      first: Int,
      last: Int,
      works: Vector[String],
      id: Option[String] = None
  ): Row =
    val (x, y) = StableIds.orderedPair(a, b)
    val resolvedId = id.getOrElse(edgeId(x, y))
    Map(
      "edgeId" -> ujson.Str(resolvedId),
      "window" -> ujson.Str("2000-2009"),
      "windowStart" -> ujson.Num(2000),
      "windowEnd" -> ujson.Num(2009),
      "researcherA" -> ujson.Str(x),
      "researcherB" -> ujson.Str(y),
      "policyVersion" -> ujson.Str(policy),
      "jointPublicationCount" -> ujson.Num(count.toDouble),
      "firstCollaborationYear" -> ujson.Num(first),
      "lastCollaborationYear" -> ujson.Num(last),
      "representativeWorkIds" -> ujson.Arr.from(works.map(ujson.Str(_))),
      "supportTable" -> ujson.Str("edge-support"),
      "endpointAUnresolved" -> ujson.Bool(x.startsWith("mention:")),
      "endpointBUnresolved" -> ujson.Bool(y.startsWith("mention:")),
      "provenance" -> provenance("edges-coauthored", resolvedId)
    )

  def supportRow(
      edge: String,
      a: String,
      b: String,
      work: String,
      year: Int
  ): Row =
    val (x, y) = StableIds.orderedPair(a, b)
    Map(
      "edgeId" -> ujson.Str(edge),
      "window" -> ujson.Str("2000-2009"),
      "researcherA" -> ujson.Str(x),
      "researcherB" -> ujson.Str(y),
      "canonicalWorkId" -> ujson.Str(work),
      "year" -> ujson.Num(year),
      "provenance" -> provenance("edge-support", s"$edge|$work")
    )

  def researcherNode(id: String, kind: String, name: String): Row =
    Map(
      "nodeId" -> ujson.Str(id),
      "nodeKind" -> ujson.Str(kind),
      "displayName" -> ujson.Str(name),
      "orcid" -> ujson.Null,
      "memberCount" -> ujson.Num(1),
      "mentionCount" -> ujson.Num(1),
      "provenance" -> provenance("nodes-researcher", id)
    )

  def workNode(id: String, title: String, year: Int): Row =
    Map(
      "canonicalWorkId" -> ujson.Str(id),
      "title" -> ujson.Str(title),
      "year" -> ujson.Num(year),
      "venueId" -> ujson.Str(venue),
      "doi" -> ujson.Null,
      "source" -> ujson.Str("dblp"),
      "memberCount" -> ujson.Num(1),
      "provenance" -> provenance("nodes-work", id)
    )

  def authored(researcher: String, work: String, mention: String): Row =
    Map(
      "researcherNodeId" -> ujson.Str(researcher),
      "nodeKind" -> ujson.Str("canonical"),
      "canonicalWorkId" -> ujson.Str(work),
      "mentionId" -> ujson.Str(mention),
      "authorPosition" -> ujson.Num(1),
      "year" -> ujson.Num(2001),
      "countedInMetrics" -> ujson.Bool(true),
      "provenance" -> provenance("edges-authored", mention)
    )

  /** A consistent graph. R1-R2 share W1 (2001) and W2 (2005); M1-R1 share W1.
    */
  def graphTables(): Map[String, Vector[Row]] =
    val rr = edgeId(r1, r2)
    val mr = edgeId(m1, r1)
    Map(
      "nodes-researcher" -> Vector(
        researcherNode(r1, "canonical", "Ada"),
        researcherNode(r2, "canonical", "Bo, \"B\" Chen"),
        researcherNode(m1, "unresolved-mention", "A. Unknown")
      ),
      "nodes-work" -> Vector(
        workNode(w1, "First paper", 2001),
        workNode(w2, "Second\npaper", 2005),
        workNode(w3, "Third paper", 2007)
      ),
      "nodes-venue" -> Vector(
        Map(
          "venueId" -> ujson.Str(venue),
          "name" -> ujson.Str("Conf A"),
          "provenance" -> provenance("nodes-venue", venue)
        )
      ),
      "nodes-topic" -> Vector.empty,
      "nodes-institution" -> Vector.empty,
      "edges-authored" -> Vector(
        authored(r1, w1, StableIds.mention(w1, 1)),
        authored(r2, w1, StableIds.mention(w1, 3)),
        authored(r1, w2, StableIds.mention(w2, 1)),
        authored(r2, w2, StableIds.mention(w2, 2)),
        authored(m1, w1, m1)
      ),
      "edges-published-in" -> Vector(
        Map(
          "fromId" -> ujson.Str(w1),
          "toId" -> ujson.Str(venue),
          "provenance" -> provenance("edges-published-in", w1)
        )
      ),
      "edges-coauthored" -> Vector(
        coauthorRow(r1, r2, 2, 2001, 2005, Vector(w1, w2)),
        coauthorRow(m1, r1, 1, 2001, 2001, Vector(w1))
      ),
      "edge-support" -> Vector(
        supportRow(rr, r1, r2, w1, 2001),
        supportRow(rr, r1, r2, w2, 2005),
        supportRow(mr, m1, r1, w1, 2001)
      )
    )

  def source(tables: Map[String, Vector[Row]] = graphTables()): TableSource =
    new InMemoryTableSource(tables)

  /** A run directory with a complete s3 load manifest and the given files. */
  def writeRunDir(root: Path, success: Boolean): Path =
    val dir = root.resolve(s"run=${RunDirectory.segment(runId)}")
    val table = dir.resolve("tables").resolve("nodes-work")
    Files.createDirectories(table)
    Files.createDirectories(dir.resolve("load-manifests"))
    val part = "tables/nodes-work/part-00000.parquet"
    val bytes = "parquet-bytes-stand-in".getBytes("UTF-8")
    Files.write(dir.resolve(part), bytes)
    val manifest = ujson.Obj(
      "runId" -> runId,
      "tables" -> ujson.Arr(
        ujson.Obj(
          "table" -> "nodes-work",
          "files" -> ujson.Arr(
            ujson.Obj(
              "path" -> part,
              "bytes" -> bytes.length,
              "sha256" -> Hashing.sha256(bytes)
            )
          )
        )
      )
    )
    Files.writeString(
      dir.resolve("load-manifests/s3.json"),
      ujson.write(manifest)
    )
    Files.writeString(dir.resolve("run-manifest.json"), "{}")
    if success then Files.writeString(dir.resolve("_SUCCESS"), "")
    dir

  def metricsRow(id: String, window: String, start: Int, end: Int): Row =
    Map(
      "window" -> ujson.Str(window),
      "windowStart" -> ujson.Num(start),
      "windowEnd" -> ujson.Num(end),
      "researcherId" -> ujson.Str(id),
      "nodeKind" -> ujson.Str("canonical"),
      "publicationCount" -> ujson.Num(2),
      "distinctCoauthorCount" -> ujson.Num(1),
      "totalCoauthorOccurrences" -> ujson.Num(2),
      "averageCoauthorsPerPaper" -> ujson.Num(1.0),
      "maximumCoauthorsOnOnePaper" -> ujson.Num(1),
      "maximumTeamWorkIds" -> ujson.Arr(ujson.Str(w1)),
      "weightedDegree" -> ujson.Num(2.0),
      "unresolvedCoauthorOccurrences" -> ujson.Num(0),
      "distinctUnresolvedCoauthorCount" -> ujson.Num(0),
      "componentId" -> ujson.Str("component-1"),
      "componentSize" -> ujson.Num(2),
      "communityId" -> ujson.Str("community-1"),
      "bridgeScore" -> ujson.Num(0.0),
      "coreness" -> ujson.Num(1),
      "provenance" -> provenance("researcher-metrics", s"$id|$window")
    )

  def summaryRow(kind: String, id: String, text: String): Row =
    Map(
      "summaryId" -> ujson.Str(s"summary:v1:$id"),
      "summaryType" -> ujson.Str(kind),
      "window" -> ujson.Str("2000-2009"),
      "windowStart" -> ujson.Num(2000),
      "windowEnd" -> ujson.Num(2009),
      "subjectId" -> ujson.Str(r1),
      "nodeKind" -> ujson.Str("canonical"),
      "summaryText" -> ujson.Str(text),
      "evidenceWorkIds" -> ujson.Arr.from(
        Vector(w1, w2, w3, w1, w2, w3, w1).map(ujson.Str(_))
      ),
      "evidenceMetrics" -> ujson.Arr(ujson.Str("publicationCount")),
      "evidenceNodeIds" -> ujson.Arr(ujson.Str(r1)),
      "provenance" -> provenance("semantic-summaries", id)
    )

  val longText: String =
    "Researcher Ada in window 2000-2009 has many publications and coauthors. " * 30

  final class FixedEmbedder(val dimension: Int) extends Embedder:
    val modelVersion = "test-embedder/1"
    def embed(text: String): Vector[Float] =
      Vector.tabulate(dimension)(i => ((text.length + i) % 7).toFloat)
