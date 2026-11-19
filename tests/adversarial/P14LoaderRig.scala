package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scholarchain.core.StableIds
import scholarchain.rag.{AwsIngestMain, CountingEmbedder, GraphLoad, GraphStep}
import scholarchain.rag.{EmbeddingModelSpec, EmbeddingModels, IngestMain}
import scholarchain.storage.*

/** Stubbed ports for the Phase 14 loader failure tests. The rag module's own
  * fixtures are test-scope inside modules/rag and are not visible from tests/,
  * so this rig builds its own export, embedder, failing ports and world. No AWS
  * client is built and no network is used.
  */
object P14Loader:
  val runId = "run:v1:44444444-4444-4444-8444-444444444444"
  val transformVersion = "spark-analytics/1.1.0"

  // Built at runtime so no credential-shaped literal sits in the source; the values are unchanged.
  val fakeAkia = Vector("AKIA", "P14FAKEFAKEFAKE7").mkString
  val fakeSecretKey =
    Vector("p14-fake-secret-", "access-key-0123456789abcdefABCDEF").mkString
  val fakeSession = Vector("p14-fake-session-", "token-FQoGZXIvYXdzE").mkString
  val fakeBearer =
    Vector(
      "Bearer ",
      "eyJhbGciOiJIUzI1NiJ9.",
      "p14fakepayload.p14fakesig"
    ).mkString

  val secrets: Vector[String] =
    Vector(fakeAkia, fakeSecretKey, fakeSession, fakeBearer)

  val validEnv: Map[String, String] = Map(
    "AWS_REGION" -> "us-east-2",
    "SCHOLARCHAIN_DATA_BUCKET" -> "sc-data-p14",
    "SCHOLARCHAIN_VECTOR_BUCKET" -> "sc-vectors-p14",
    "SCHOLARCHAIN_TABLE_PREFIX" -> "sc-course",
    "SCHOLARCHAIN_NEPTUNE_HOST" -> "neptune.example.internal",
    "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN" ->
      "arn:aws:iam::123456789012:role/loader"
  )

  val withSecrets: Map[String, String] = validEnv ++ Map(
    "AWS_ACCESS_KEY_ID" -> fakeAkia,
    "AWS_SECRET_ACCESS_KEY" -> fakeSecretKey,
    "AWS_SESSION_TOKEN" -> fakeSession,
    "SCHOLARCHAIN_API_TOKEN" -> fakeBearer
  )

  val vectorsOnlyEnv: Map[String, String] =
    validEnv - "SCHOLARCHAIN_NEPTUNE_HOST" -
      "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN"

  /** A 256-dimension stand-in for Titan. */
  final class FakeTitan extends Embedder:
    val modelVersion: String = TitanEmbedder.ModelVersion
    val dimension: Int = TitanEmbedder.Dimension

    def embed(text: String): Vector[Float] =
      val base = new HashEmbedder().embed(text).take(dimension)
      val norm = math.sqrt(base.map(f => f.toDouble * f).sum)
      if norm == 0.0 then Vector.fill(dimension)(1.0f / 16.0f)
      else base.map(f => (f / norm).toFloat)

  final class FailingBucket(inner: VectorBucketPort) extends VectorBucketPort:
    def ensureIndex(spec: VectorIndexSpec): Unit = inner.ensureIndex(spec)
    def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
      Fail(ErrorCode.AwsRejected, "stubbed PutVectors rejection")
    def getVectors(index: String, keys: Vector[String]): Vector[VectorRecord] =
      inner.getVectors(index, keys)
    def count(index: String): Int = inner.count(index)
    def query(request: VectorQuery): Vector[VectorMatch] = inner.query(request)

  final class FailingLoader extends NeptuneBulkLoader:
    def load(manifestKey: String): BulkLoadResult =
      Fail(
        ErrorCode.BulkLoadFailed,
        "s3://sc-data-p14/graph/neptune/vertices-Work.csv load load-1 " +
          "ended LOAD_FAILED: records 2, parsing errors 1"
      )

  final class UnreachableLoader extends NeptuneBulkLoader:
    val calls = mutable.ArrayBuffer.empty[String]
    def load(manifestKey: String): BulkLoadResult =
      calls += manifestKey
      throw new AssertionError("the Neptune loader must not be reached")

  private def ref(id: String): ujson.Value =
    ujson.Obj(
      "schemaVersion" -> 1,
      "source" -> "derived",
      "snapshotId" -> "hadoop-snapshot:p14",
      "sourceRecordId" -> id,
      "sourceUri" -> "scholarchain:spark-analytics/p14",
      "transformVersion" -> transformVersion,
      "runId" -> runId
    )

  val ada: String = StableIds.researcherFromExternal("orcid", "0000-1001")
  val boris: String = StableIds.researcherFromExternal("orcid", "0000-1002")
  val w1: String = StableIds.work("dblp", "conf/p14/1")
  val w2: String = StableIds.work("dblp", "conf/p14/2")

  private def summary(
      id: String,
      kind: String,
      subject: String,
      text: String,
      window: Option[(String, Int, Int)],
      nodeKind: Option[String],
      works: Vector[String],
      metrics: Vector[String],
      nodes: Vector[String]
  ): Row =
    Map(
      "summaryId" -> ujson.Str(s"summary:v1:$id"),
      "summaryType" -> ujson.Str(kind),
      "window" -> window.fold[ujson.Value](ujson.Str("not-windowed"))(w =>
        ujson.Str(w._1)
      ),
      "windowStart" -> window.fold[ujson.Value](ujson.Null)(w =>
        ujson.Num(w._2)
      ),
      "windowEnd" -> window.fold[ujson.Value](ujson.Null)(w => ujson.Num(w._3)),
      "subjectId" -> ujson.Str(subject),
      "nodeKind" -> nodeKind.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "summaryText" -> ujson.Str(text),
      "evidenceWorkIds" -> ujson.Arr.from(works.map(ujson.Str(_))),
      "evidenceMetrics" -> ujson.Arr.from(metrics.map(ujson.Str(_))),
      "evidenceNodeIds" -> ujson.Arr.from(nodes.map(ujson.Str(_))),
      "provenance" -> ref(id)
    )

  private val early = Some(("2000-2009", 2000, 2009))

  private def profile(id: String, who: String, name: String, work: String) =
    summary(
      id,
      "researcher-profile",
      who,
      s"Researcher $name in window 2000-2009: 7 publications, 2 distinct " +
        "coauthors, 4 coauthor occurrences, 1.50 average coauthors per " +
        s"paper, at most 3 coauthors on one paper (work $work), weighted " +
        "degree 4, component of 5 nodes, community of 3 nodes, coreness 2, " +
        "participation coefficient 0.250. Nodes are canonical researchers " +
        "and unresolved author mentions.",
      early,
      Some("canonical"),
      Vector(work),
      Vector("publicationCount", "distinctCoauthorCount", "weightedDegree"),
      Vector(who)
    )

  val summaries: Vector[Row] = Vector(
    profile("p1", ada, "Ada Quillfeather", w1),
    profile("p2", boris, "Boris Marlowe", w2),
    summary(
      "c1",
      "community",
      "community-1",
      "Community community-1 in window 2000-2009 has 6 nodes (canonical " +
        "researchers and unresolved author mentions), 8 internal " +
        "collaboration edges and 2 edges to other communities. Most " +
        "connected members by weighted degree: Ada Quillfeather; Boris " +
        "Marlowe.",
      early,
      None,
      Vector(w1),
      Vector("communitySize", "internalEdges"),
      Vector(ada, boris)
    ),
    summary(
      "w1",
      "work",
      w1,
      s"Work Graph neural networks for citation analysis ($w1, year 2001) " +
        "has 3 authors, of which 0 are unresolved author mentions. Venue: " +
        "venue:dblp:conf-a. DOI: none recorded.",
      None,
      None,
      Vector(w1),
      Vector("teamSize", "unresolvedAuthors", "year"),
      Vector.empty
    ),
    summary(
      "x1",
      "collaboration-path",
      "pair:ada-boris",
      "In window 2000-2009 the shortest collaboration path from Ada " +
        "Quillfeather to Boris Marlowe has 1 hops: Ada Quillfeather and " +
        "Boris Marlowe share 3 joint publication(s) (works " +
        s"$w1).",
      early,
      None,
      Vector(w1),
      Vector("pathLength", "jointPublicationCount"),
      Vector(ada, boris)
    )
  )

  val nodesWork: Vector[Row] = Vector(w1 -> 2001, w2 -> 2005).map {
    case (work, year) =>
      Map(
        "canonicalWorkId" -> ujson.Str(work),
        "title" -> ujson.Str(s"Title of $work"),
        "year" -> ujson.Num(year),
        "source" -> ujson.Str("dblp"),
        "provenance" -> ref(work)
      )
  }

  val researcherMetrics: Vector[Row] = Vector(ada, boris).map { who =>
    Map(
      "researcherId" -> ujson.Str(who),
      "window" -> ujson.Str("2000-2009"),
      "communityId" -> ujson.Str("community-1"),
      "provenance" -> ref(s"$who|2000-2009")
    )
  }

  def threeTables: Map[String, Vector[Row]] = Map(
    "semantic-summaries" -> summaries,
    "nodes-work" -> nodesWork,
    "researcher-metrics" -> researcherMetrics
  )

  val graphOnlyTables: Vector[String] =
    GraphStep.RequiredTables.filterNot(_ == "nodes-work")

  def exportRows(withGraph: Boolean): Map[String, Vector[Row]] =
    threeTables ++
      (if withGraph then graphOnlyTables.map(_ -> Vector.empty[Row]).toMap
       else Map.empty[String, Vector[Row]])

  /** `<table>/part-NNNNN.jsonl`, `export-manifest.json`, then `_SUCCESS`. */
  def writeExport(dir: Path, tableRows: Map[String, Vector[Row]]): Path =
    val entries = tableRows.toVector.sortBy(_._1).map { case (name, rows) =>
      val chunks =
        if rows.isEmpty then Vector(Vector.empty[Row])
        else rows.grouped(4).toVector
      Files.createDirectories(dir.resolve(name))
      val files = chunks.zipWithIndex.map { case (chunk, index) =>
        val relative = f"$name/part-$index%05d.jsonl"
        val body = chunk
          .map(r => ujson.write(ujson.Obj.from(r)))
          .mkString("", "\n", if chunk.isEmpty then "" else "\n")
        val bytes = body.getBytes(UTF_8)
        Files.write(dir.resolve(relative), bytes)
        ujson.Obj(
          "path" -> relative,
          "rows" -> chunk.size,
          "bytes" -> bytes.length,
          "sha256" -> Hashing.sha256(bytes)
        )
      }
      name -> ujson.Obj(
        "rows" -> rows.size,
        "idColumns" -> ujson.Arr(),
        "columns" -> ujson.Arr(),
        "sourceFiles" -> ujson.Arr(),
        "files" -> ujson.Arr.from(files)
      )
    }
    val manifest = ujson.Obj(
      "manifestType" -> JsonlExportSource.ManifestType,
      "exportVersion" -> "spark-jsonl-export/1.0.0",
      "sourceRunId" -> runId,
      "sourceDirectory" -> s"run=${RunDirectory.segment(runId)}",
      "sourceSuccessSeen" -> true,
      "sourceRunManifestSha256" -> Hashing.sha256("run-manifest"),
      "transformVersion" -> transformVersion,
      "rowsPerFile" -> 4,
      "tables" -> ujson.Obj.from(entries)
    )
    Files.writeString(
      dir.resolve(JsonlExportSource.ManifestName),
      ujson.write(manifest, indent = 2) + "\n"
    )
    Files.write(dir.resolve("_SUCCESS"), Array.emptyByteArray)
    dir

  def seed(store: ObjectStore, exportDir: Path, prefix: String): Int =
    val stream = Files.walk(exportDir)
    val files =
      try stream.iterator.asScala.filter(Files.isRegularFile(_)).toVector
      finally stream.close()
    files.foreach { file =>
      val relative = exportDir.relativize(file).toString.replace('\\', '/')
      store.putImmutable(prefix + relative, Files.readAllBytes(file), "p14")
    }
    files.size

  val projection: GraphProjection = GraphProjection(
    Vector(
      GraphVertex(
        "researcher:a",
        "Researcher",
        Map("displayName" -> Prop.Str("Ada"))
      ),
      GraphVertex(w1, "Work", Map("year" -> Prop.I32(2001)))
    ),
    Vector(
      GraphEdgeRow(
        "edge:e1",
        "researcher:a",
        w1,
        "AUTHORED",
        Map("authorPosition" -> Prop.I32(1))
      )
    ),
    0
  )

  /** One loader world: an in-memory data bucket seeded with an export, an
    * in-memory vector bucket, ledger and Neptune, and a counting embedder.
    */
  final class World(
      withGraph: Boolean = true,
      seeded: Boolean = true,
      val embedder: CountingEmbedder = new CountingEmbedder(new FakeTitan),
      val model: EmbeddingModelSpec = EmbeddingModels.TitanTextV2D256
  ):
    val store = new InMemoryObjectStore
    val bucket = new InMemoryVectorBucket
    val ledger = new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    val neptune = new InMemoryNeptune(store)
    val config: AwsIngestMain.Config =
      AwsIngestMain.parseConfig(Array(runId), validEnv)
    if seeded then
      val dir = writeExport(
        Files.createTempDirectory("p14-loader-export-"),
        exportRows(withGraph)
      )
      seed(store, dir, config.exportPrefix)

    def backend(
        bucketPort: VectorBucketPort = bucket,
        loader: NeptuneBulkLoader = neptune,
        project: TableSource => GraphProjection = _ => projection,
        on: ObjectStore = store
    ): IngestMain.Backend =
      IngestMain.Backend(
        embedder,
        model,
        on,
        bucketPort,
        ledger,
        graph = Some(GraphLoad(loader, project)),
        aws = true,
        label = IngestMain.AwsLabel,
        embedCalls = () => Some(embedder.calls)
      )

    private var tick = 0L

    private def stepClock(): Instant =
      tick += 1
      Instant.parse("2026-11-14T12:00:00Z").plusSeconds(tick * 60)

    def run(
        backendFor: => IngestMain.Backend = backend(),
        env: Map[String, String] = validEnv,
        args: Array[String] = Array(runId),
        clock: () => Instant = () => stepClock()
    ): (Int, Vector[String]) =
      val lines = Vector.newBuilder[String]
      val code = AwsIngestMain.run(
        args,
        env,
        lines += _,
        _ => AwsIngestMain.Wiring(backendFor),
        clock
      )
      (code, lines.result())

    def json(key: String): ujson.Value =
      ujson.read(
        store.get(key).getOrElse(throw new AssertionError(s"no object $key"))
      )

    def reportKey: String = s"${config.reportBase}/${IngestMain.ReportName}"
    def runKey: String = s"${config.reportBase}/${IngestMain.RunName}"

    /** Every byte the world's data bucket holds, as text. */
    def everythingStored: String =
      store
        .list("")
        .map(m => new String(store.get(m.key).get, UTF_8))
        .mkString("\n")
