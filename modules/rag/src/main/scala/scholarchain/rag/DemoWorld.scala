package scholarchain.rag

import scala.collection.mutable
import scholarchain.core.{
  ContractSchema,
  Provenance,
  ResearcherMetrics,
  SourceType,
  StableIds,
  YearWindow
}
import scholarchain.storage.{
  CachedMetrics,
  CommunityInfo,
  CommunityLink,
  ComponentInfo,
  CypherCatalog,
  CypherQuery,
  DynamoTable,
  EmbeddingIngest,
  EvidenceBundleTable,
  HashEmbedder,
  IngestConfig,
  InMemoryCommunities,
  InMemoryComponents,
  InMemoryDynamoTable,
  InMemoryObjectStore,
  InMemoryPathEvidence,
  InMemoryResearcherDirectory,
  InMemoryVectorBucket,
  InMemoryTableSource,
  NeptuneQueryPort,
  ObjectStore,
  PathEvidenceRow,
  QuerySession,
  ResearcherMetricCache,
  Row,
  SourceRun,
  StorageProvenance,
  TableNames,
  VectorBucketPort,
  WindowLabels
}

/** IDs of the demo world. */
final case class DemoIds(
    ada: String,
    boris: String,
    chidi: String,
    fiona: String,
    dana: String,
    gus: String,
    weiA: String,
    weiB: String,
    priya: String,
    priyaAlt: String,
    ines: String,
    works: Map[String, String]
)

/** The Cypher read model of the demo world: it answers the two catalog
  * templates by name and records every query it is asked, so a test can check
  * that only allowlisted, parameterized queries ever ran.
  */
final class DemoNeptune(edges: Vector[DemoEdge]) extends NeptuneQueryPort:
  private val log = mutable.ArrayBuffer.empty[CypherQuery]

  def queries: Vector[CypherQuery] = log.toVector

  private def row(e: DemoEdge, other: String): Row =
    Map(
      "coauthorId" -> ujson.Str(other),
      "edgeId" -> ujson.Str(e.edgeId),
      "jointPublicationCount" -> ujson.Num(e.count.toDouble),
      "firstCollaborationYear" -> ujson.Num(e.first),
      "lastCollaborationYear" -> ujson.Num(e.last),
      "evidencePointer" -> ujson.Str(e.pointer)
    )

  def run(query: CypherQuery): Vector[Row] =
    log += query
    query.name match
      case CypherCatalog.CoauthorsOf =>
        val id = query.params("researcherId").str
        val window = query.params("window").str
        val limit = query.params("limit").num.toInt
        edges
          .filter(e =>
            WindowLabels.of(e.window) == window && (e.a == id || e.b == id)
          )
          .map(e => (if e.a == id then e.b else e.a, e))
          .sortBy((other, e) => (-e.count, other))
          .take(limit)
          .map((other, e) => row(e, other))
      case CypherCatalog.EdgeEvidence =>
        val id = query.params("edgeId").str
        edges
          .filter(_.edgeId == id)
          .map(e =>
            Map(
              "edgeId" -> ujson.Str(e.edgeId),
              "evidencePointer" -> ujson.Str(e.pointer),
              "representativeWorkIds" -> Facts.strs(e.works)
            ): Row
          )
      case other =>
        throw new IllegalArgumentException(s"Unknown template $other")

final case class DemoEdge(
    window: YearWindow,
    a: String,
    b: String,
    count: Long,
    first: Int,
    last: Int,
    works: Vector[String]
):
  val edgeId: String =
    StableIds.coauthorEdge(a, b, window, DemoWorld.EdgePolicy)
  def pointer: String = s"edge-support:edgeId=$edgeId"

/** Everything the offline demo and the feature tests share. */
final class DemoWorld(
    val ports: WorkflowPorts,
    val config: WorkflowConfig,
    val ids: DemoIds,
    val neptune: DemoNeptune,
    val cache: DynamoTable,
    val vectorBucket: VectorBucketPort,
    val objectStore: ObjectStore
):
  def workflow: CollaborationWorkflow = new CollaborationWorkflow(ports, config)
  def sessions: DynamoTable = ports.sessions
  def bundles: DynamoTable = ports.bundles

/** A small hand-built world: six researchers in two windows, three Wei Zhang
  * style traps (two records of one name, a name split across two nodes sharing
  * an ORCID, an unresolved mention), edges, precomputed paths, components,
  * communities, metrics, work titles and semantic summaries. The numbers are
  * chosen so every figure can be checked by hand:
  *
  * {{{
  * 2000-2009   Ada--Boris x3 (works 1, 4, 5)   Ada--Fiona x1 (work 4)
  *             Boris--Fiona x1 (work 4)         Boris--Chidi x1 (work 2)
  *             Dana--Gus x2 (works 6, 7)
  * 2010-2019   Ada--Dana x1 (work 3)
  * }}}
  */
object DemoWorld:
  val GraphRunId = "run:v1:33333333-3333-4333-8333-333333333333"
  val QueryRunId = "run:v1:44444444-4444-4444-8444-444444444444"
  val SparkTransform = "spark-analytics/1.1.0"
  val EdgePolicy = "demo-edge-policy/1"
  val Early: YearWindow = YearWindow(2000, 2009)
  val Late: YearWindow = YearWindow(2010, 2019)

  private val Names = TableNames("demo")

  private def researcher(orcid: String): String =
    StableIds.researcherFromExternal("orcid", orcid)

  val ids: DemoIds =
    val workIds =
      (1 to 7).map(n => s"w$n" -> StableIds.work("dblp", s"conf/demo/$n")).toMap
    DemoIds(
      researcher("0000-0001"),
      researcher("0000-0002"),
      researcher("0000-0003"),
      researcher("0000-0004"),
      researcher("0000-0005"),
      researcher("0000-0006"),
      researcher("0000-0007"),
      researcher("0000-0008"),
      researcher("0000-0009"),
      researcher("0000-0010"),
      StableIds.mention(workIds("w1"), 3),
      workIds
    )

  private def provenance(table: String, record: String): Provenance =
    Provenance(
      ContractSchema.currentVersion,
      SourceType.Derived,
      "hadoop-snapshot:demo",
      record,
      s"scholarchain:spark-analytics/$table",
      SparkTransform,
      GraphRunId
    )

  private def provJson(table: String, record: String): ujson.Value =
    upickle.default.writeJs(provenance(table, record))

  private val directory: Vector[Row] =
    def node(
        id: String,
        name: String,
        orcid: Option[String],
        mention: Boolean = false
    ): Row =
      Map(
        "nodeId" -> ujson.Str(id),
        "displayName" -> ujson.Str(name),
        "nodeKind" -> ujson.Str(
          if mention then "unresolved-mention" else "canonical"
        ),
        "orcid" -> Facts.optStr(orcid),
        "linkStatus" -> ujson.Str(if mention then "unlinked" else "linked"),
        "idRule" -> ujson.Str("orcid"),
        "memberCount" -> ujson.Num(1),
        "mentionCount" -> ujson.Num(1),
        "provenance" -> provJson("nodes-researcher", id)
      )
    Vector(
      node(ids.ada, "Ada Quillfeather", Some("0000-0001-0000-0001")),
      node(ids.boris, "Boris Marlowe", Some("0000-0002-0000-0002")),
      node(ids.chidi, "Chidi Okonkwo", Some("0000-0003-0000-0003")),
      node(ids.fiona, "Fiona Park", Some("0000-0004-0000-0004")),
      node(ids.dana, "Dana Whitcombe", Some("0000-0005-0000-0005")),
      node(ids.gus, "Gus Holloway", Some("0000-0006-0000-0006")),
      node(ids.weiA, "Wei Zhang", Some("0000-0007-0000-0007")),
      node(ids.weiB, "Wei Zhang", Some("0000-0008-0000-0008")),
      node(ids.priya, "Priya Raman", Some("0000-0009-0000-0009")),
      node(ids.priyaAlt, "P Raman", Some("0000-0009-0000-0009")),
      node(ids.ines, "Ines Calder", None, mention = true)
    )

  private def metrics(
      id: String,
      window: YearWindow,
      pubs: Long,
      distinct: Long,
      occurrences: Long,
      average: Double,
      maxTeam: Long,
      teamWork: String,
      weighted: Double,
      component: String,
      componentSize: Long,
      community: String,
      bridge: Double,
      coreness: Int
  ): CachedMetrics =
    val m = ResearcherMetrics(
      ContractSchema.currentVersion,
      id,
      window,
      pubs,
      distinct,
      occurrences,
      average,
      maxTeam,
      Vector(ids.works(teamWork)),
      weighted,
      component,
      componentSize,
      Some(community),
      Some(bridge),
      Some(coreness),
      provenance("researcher-metrics", s"$id|${WindowLabels.of(window)}")
    )
    CachedMetrics(
      m,
      GraphRunId,
      SparkTransform,
      Some(StorageProvenance.TransformVersion),
      Some(s"spark-run:$GraphRunId")
    )

  val allMetrics: Vector[CachedMetrics] = Vector(
    metrics(
      ids.ada,
      Early,
      3,
      2,
      4,
      4.0 / 3,
      2,
      "w4",
      4,
      "component-1",
      4,
      "community-1",
      0.0,
      2
    ),
    metrics(
      ids.boris,
      Early,
      4,
      3,
      5,
      1.25,
      2,
      "w4",
      5,
      "component-1",
      4,
      "community-1",
      0.5,
      2
    ),
    metrics(
      ids.chidi,
      Early,
      1,
      1,
      1,
      1.0,
      1,
      "w2",
      1,
      "component-1",
      4,
      "community-3",
      0.0,
      1
    ),
    metrics(
      ids.fiona,
      Early,
      1,
      2,
      2,
      2.0,
      2,
      "w4",
      2,
      "component-1",
      4,
      "community-1",
      0.0,
      2
    ),
    metrics(
      ids.dana,
      Early,
      2,
      1,
      2,
      1.0,
      1,
      "w6",
      2,
      "component-2",
      2,
      "community-4",
      0.0,
      1
    ),
    metrics(
      ids.gus,
      Early,
      2,
      1,
      2,
      1.0,
      1,
      "w6",
      2,
      "component-2",
      2,
      "community-4",
      0.0,
      1
    ),
    metrics(
      ids.ines,
      Early,
      6,
      9,
      12,
      2.0,
      4,
      "w1",
      12,
      "component-4",
      10,
      "community-5",
      0.0,
      3
    ),
    metrics(
      ids.ada,
      Late,
      1,
      1,
      1,
      1.0,
      1,
      "w3",
      1,
      "component-3",
      2,
      "community-2",
      0.0,
      1
    ),
    metrics(
      ids.dana,
      Late,
      1,
      1,
      1,
      1.0,
      1,
      "w3",
      1,
      "component-3",
      2,
      "community-2",
      0.0,
      1
    )
  )

  private val edges: Vector[DemoEdge] = Vector(
    DemoEdge(
      Early,
      ids.ada,
      ids.boris,
      3,
      2001,
      2007,
      Vector("w1", "w4", "w5").map(ids.works)
    ),
    DemoEdge(Early, ids.ada, ids.fiona, 1, 2004, 2004, Vector(ids.works("w4"))),
    DemoEdge(
      Early,
      ids.boris,
      ids.fiona,
      1,
      2004,
      2004,
      Vector(ids.works("w4"))
    ),
    DemoEdge(
      Early,
      ids.boris,
      ids.chidi,
      1,
      2005,
      2005,
      Vector(ids.works("w2"))
    ),
    DemoEdge(
      Early,
      ids.dana,
      ids.gus,
      2,
      2003,
      2008,
      Vector("w6", "w7").map(ids.works)
    ),
    DemoEdge(Late, ids.ada, ids.dana, 1, 2012, 2012, Vector(ids.works("w3")))
  )

  private def edgeOf(w: YearWindow, x: String, y: String): DemoEdge =
    edges.find(e => e.window == w && Set(e.a, e.b) == Set(x, y)).get

  def pathRows: Vector[PathEvidenceRow] =
    def hops(
        w: YearWindow,
        source: String,
        target: String,
        chain: Vector[String]
    ) =
      val label = WindowLabels.of(w)
      val pairKey = s"$label|$source|$target"
      chain.zip(chain.tail).zipWithIndex.map { case ((from, to), i) =>
        val e = edgeOf(w, from, to)
        PathEvidenceRow(
          pairKey,
          label,
          source,
          target,
          None,
          None,
          Some(i + 1),
          Some(from),
          Some(to),
          Some(e.edgeId),
          Some(e.count),
          e.works,
          provenance("path-evidence", s"$pairKey|${i + 1}"),
          Some(chain.size - 1),
          Some(6),
          Some(e.first),
          Some(e.last)
        )
      }
    def status(
        w: YearWindow,
        source: String,
        target: String,
        status: String,
        reason: Option[String]
    ) =
      val label = WindowLabels.of(w)
      PathEvidenceRow(
        s"$label|$source|$target",
        label,
        source,
        target,
        Some(status),
        reason,
        None,
        None,
        None,
        None,
        None,
        Vector.empty,
        provenance("path-evidence", s"$label|$source|$target"),
        None,
        Some(6)
      )
    hops(Early, ids.ada, ids.chidi, Vector(ids.ada, ids.boris, ids.chidi)) ++
      hops(
        Early,
        ids.fiona,
        ids.chidi,
        Vector(ids.fiona, ids.boris, ids.chidi)
      ) ++
      Vector(
        status(
          Early,
          ids.ada,
          ids.dana,
          "unreachable-within-bound",
          Some("different-component")
        ),
        status(Late, ids.boris, ids.dana, "endpoint-not-in-window", None)
      )

  private def sorted(values: String*): Vector[String] = values.toVector.sorted

  private def components: InMemoryComponents =
    def info(w: YearWindow, id: String, size: Long, largest: Boolean) =
      ComponentInfo(
        WindowLabels.of(w),
        id,
        size,
        largest,
        provenance("components", s"${WindowLabels.of(w)}|$id")
      )
    new InMemoryComponents(
      Vector(
        info(Early, "component-1", 4, largest = true),
        info(Early, "component-2", 2, largest = false),
        info(Late, "component-3", 2, largest = true)
      ),
      Map(
        ("2000-2009", "component-1") ->
          sorted(ids.ada, ids.boris, ids.chidi, ids.fiona),
        ("2000-2009", "component-2") -> sorted(ids.dana, ids.gus),
        ("2010-2019", "component-3") -> sorted(ids.ada, ids.dana)
      )
    )

  private def communities: InMemoryCommunities =
    def info(
        w: YearWindow,
        id: String,
        size: Long,
        internal: Long,
        cross: Long,
        internalWeight: Long,
        crossWeight: Long
    ) =
      CommunityInfo(
        WindowLabels.of(w),
        id,
        size,
        internal,
        cross,
        internalWeight,
        crossWeight,
        provenance("communities", s"${WindowLabels.of(w)}|$id")
      )
    def link(a: String, b: String) =
      CommunityLink(
        "2000-2009",
        a,
        b,
        1,
        1,
        provenance("community-cross-edges", s"$a|$b")
      )
    new InMemoryCommunities(
      Vector(
        info(Early, "community-1", 3, 3, 1, 5, 1),
        info(Early, "community-3", 1, 0, 1, 0, 1),
        info(Early, "community-4", 2, 1, 0, 2, 0),
        info(Late, "community-2", 2, 1, 0, 1, 0)
      ),
      Map(
        ("2000-2009", "community-1") -> sorted(ids.ada, ids.boris, ids.fiona),
        ("2000-2009", "community-3") -> sorted(ids.chidi),
        ("2000-2009", "community-4") -> sorted(ids.dana, ids.gus),
        ("2010-2019", "community-2") -> sorted(ids.ada, ids.dana)
      ),
      Vector(
        link("community-1", "community-3"),
        link("community-3", "community-1")
      )
    )

  private val workTitles = Vector(
    "w1" -> ("Graph neural networks for citation analysis", 2001),
    "w2" -> ("Streaming algorithms for heavy hitters", 2005),
    "w3" -> ("Quantum error correction on superconducting qubits", 2012),
    "w4" -> ("Protein folding with molecular dynamics", 2004),
    "w5" -> ("Spectral methods for graph partitioning", 2007),
    "w6" -> ("Bridge detection in sparse networks", 2003),
    "w7" -> ("Community structure in citation networks", 2008)
  )

  private val workRows: Vector[Row] = workTitles.map {
    case (key, (title, year)) =>
      Map(
        "canonicalWorkId" -> ujson.Str(ids.works(key)),
        "title" -> ujson.Str(title),
        "year" -> ujson.Num(year),
        "source" -> ujson.Str("dblp"),
        "provenance" -> provJson("nodes-work", ids.works(key))
      )
  }

  private def summary(
      key: String,
      kind: String,
      subject: String,
      text: String,
      window: Option[(String, Int, Int)],
      works: Vector[String],
      nodes: Vector[String]
  ): Row =
    Map(
      "summaryId" -> ujson.Str(s"summary:v1:$key"),
      "summaryType" -> ujson.Str(kind),
      "window" -> window.fold[ujson.Value](ujson.Str("not-windowed"))(w =>
        ujson.Str(w._1)
      ),
      "windowStart" -> window.fold[ujson.Value](ujson.Null)(w =>
        ujson.Num(w._2)
      ),
      "windowEnd" -> window.fold[ujson.Value](ujson.Null)(w => ujson.Num(w._3)),
      "subjectId" -> ujson.Str(subject),
      "nodeKind" -> (if kind == "researcher-profile" then ujson.Str("canonical")
                     else ujson.Null),
      "summaryText" -> ujson.Str(text),
      "evidenceWorkIds" -> Facts.strs(works),
      "evidenceMetrics" -> Facts.strs(
        if kind == "work" then Vector("teamSize", "year")
        else Vector("publicationCount", "distinctCoauthorCount")
      ),
      "evidenceNodeIds" -> Facts.strs(nodes),
      "provenance" -> provJson("semantic-summaries", key)
    )

  /** The default semantic summaries. Their wording follows the Spark templates
    * and holds numbers on purpose: the verifier must still refuse them as
    * support for any figure.
    */
  val defaultSummaries: Vector[Row] =
    val early = Some(("2000-2009", 2000, 2009))
    val late = Some(("2010-2019", 2010, 2019))
    Vector(
      summary(
        "p-ada-early",
        "researcher-profile",
        ids.ada,
        "Researcher Ada Quillfeather in window 2000-2009: 3 publications, 2 distinct coauthors, weighted degree 4, component of 4 nodes.",
        early,
        Vector(ids.works("w4")),
        Vector(ids.ada)
      ),
      summary(
        "p-ada-late",
        "researcher-profile",
        ids.ada,
        "Researcher Ada Quillfeather in window 2010-2019: 1 publications, 1 distinct coauthors, weighted degree 1.",
        late,
        Vector(ids.works("w3")),
        Vector(ids.ada)
      ),
      summary(
        "p-boris-early",
        "researcher-profile",
        ids.boris,
        "Researcher Boris Marlowe in window 2000-2009: 4 publications, 3 distinct coauthors, weighted degree 5, bridge score 0.5.",
        early,
        Vector(ids.works("w4")),
        Vector(ids.boris)
      ),
      summary(
        "c1",
        "community",
        "community-1",
        "Community community-1 in window 2000-2009 has 3 nodes, 3 internal collaboration edges and 1 edges to other communities. Most connected members: Boris Marlowe; Ada Quillfeather.",
        early,
        Vector(ids.works("w4")),
        Vector(ids.ada, ids.boris)
      ),
      summary(
        "w1",
        "work",
        ids.works("w1"),
        s"Work Graph neural networks for citation analysis (${ids.works("w1")}, year 2001) has 2 authors.",
        None,
        Vector(ids.works("w1")),
        Vector.empty
      ),
      summary(
        "w4",
        "work",
        ids.works("w4"),
        s"Work Protein folding with molecular dynamics (${ids.works("w4")}, year 2004) has 3 authors.",
        None,
        Vector(ids.works("w4")),
        Vector.empty
      ),
      summary(
        "x1",
        "collaboration-path",
        "pair:ada-chidi",
        "In window 2000-2009 the shortest collaboration path from Ada Quillfeather to Chidi Okonkwo has 2 hops via Boris Marlowe.",
        early,
        Vector(ids.works("w1"), ids.works("w2")),
        Vector(ids.ada, ids.chidi)
      )
    )

  private def sparkRows(summaries: Vector[Row]): Map[String, Vector[Row]] =
    Map(
      "semantic-summaries" -> summaries,
      "nodes-work" -> workRows,
      "researcher-metrics" -> allMetrics.map { c =>
        Map(
          "researcherId" -> ujson.Str(c.metrics.researcherId),
          "window" -> ujson.Str(WindowLabels.of(c.metrics.window)),
          "communityId" -> Facts.optStr(c.metrics.communityId),
          "provenance" -> provJson("researcher-metrics", c.metrics.researcherId)
        ): Row
      }
    )

  /** Builds a fresh world with its own tables and stores. `summaries` replaces
    * the semantic summaries (a test can plant instruction-like text);
    * `semantic = false` leaves the vector side out; `displayNames` renames
    * researchers by node ID (a test can plant an instruction in a name).
    */
  def build(
      model: ModelPort = new DeterministicStubModel,
      semantic: Boolean = true,
      summaries: Vector[Row] = defaultSummaries,
      clock: RuntimeClock = new FixedStepClock,
      config: Option[WorkflowConfig] = None,
      displayNames: Map[String, String] = Map.empty
  ): DemoWorld =
    val cache = new InMemoryDynamoTable(ResearcherMetricCache.spec(Names))
    allMetrics.foreach { c =>
      ResearcherMetricCache.put(
        cache,
        c.metrics,
        GraphRunId,
        Map.empty,
        4102444800L
      )
    }
    val bucket = new InMemoryVectorBucket
    val store = new InMemoryObjectStore
    val retrieval =
      if semantic then
        val embedder = new HashEmbedder
        val ledger = new InMemoryDynamoTable(
          scholarchain.storage.RunLedger.spec(Names)
        )
        val ingest =
          new EmbeddingIngest(embedder, RagTemplates, bucket, store, ledger)
        ingest.run(
          new InMemoryTableSource(sparkRows(summaries)),
          SourceRun(GraphRunId, SparkTransform),
          IngestConfig("2026-11-08T00:00:00Z")
        )
        Some(new SemanticRetrieval(embedder, bucket, store))
      else None
    val neptune = new DemoNeptune(edges)
    val ports = WorkflowPorts(
      cache,
      new InMemoryMetricRanking(allMetrics),
      neptune,
      new InMemoryPathEvidence(pathRows),
      components,
      communities,
      new InMemoryResearcherDirectory(directory.map { row =>
        displayNames
          .get(row("nodeId").str)
          .fold(row)(n => row + ("displayName" -> ujson.Str(n)))
      }),
      new TableWorkCatalog(workRows),
      retrieval,
      model,
      new InMemoryDynamoTable(QuerySession.spec(Names)),
      new InMemoryDynamoTable(EvidenceBundleTable.spec(Names)),
      clock
    )
    new DemoWorld(
      ports,
      config.getOrElse(WorkflowConfig(GraphRunId, QueryRunId)),
      ids,
      neptune,
      cache,
      bucket,
      store
    )
