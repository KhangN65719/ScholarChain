package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scholarchain.rag.RagTemplates
import scholarchain.storage.*

/** Independent Phase 8 fixtures. Everything here is written from the Phase 8
  * specification and the report-01 export shape, not from the owners' tests.
  */
object P8:
  val runId = "run:v1:4600a8f4-d438-4683-8852-db8743eb23f0"
  val transform = "spark-analytics/1.1.0"
  val startedAt = "2026-11-08T00:00:00Z"
  val window1 = "2015-2019"
  val window2 = "2020-2024"

  def sha(text: String): String = Hashing.sha256(text)

  /** A real-style ID: a prefix, `v1` and a 64-hex digest. */
  def id(prefix: String, seed: String): String = s"$prefix:v1:${sha(seed)}"

  def s(v: String): ujson.Value = ujson.Str(v)
  def n(v: Long): ujson.Value = ujson.Num(v.toDouble)
  def strs(vs: Seq[String]): ujson.Value = ujson.Arr.from(vs.map(ujson.Str(_)))

  def prov(
      subject: String,
      run: String = runId,
      transformVersion: String = transform
  ): ujson.Value =
    ujson.Obj(
      "schemaVersion" -> 1,
      "source" -> "derived",
      "snapshotId" -> s"spark-run:$run",
      "sourceRecordId" -> subject,
      "sourceUri" -> "scholarchain:spark/summaries",
      "transformVersion" -> transformVersion,
      "runId" -> run
    )

  final case class Spec(
      label: String,
      kind: String,
      window: Option[(String, Int, Int)],
      subject: String,
      text: String,
      works: Seq[String],
      metrics: Seq[String],
      nodes: Seq[String],
      nodeKind: Option[String] = None,
      windowLabelOnly: Option[String] = None
  ):
    def windowLabel: Option[String] = windowLabelOnly.orElse(window.map(_._1))
    def summaryId: String =
      id("summary", s"$kind|${windowLabel.getOrElse("-")}|$subject")

  def summaryRow(spec: Spec, run: String = runId): Row =
    Map(
      "summaryId" -> s(spec.summaryId),
      "summaryType" -> s(spec.kind),
      "window" -> spec.windowLabel.fold(ujson.Null: ujson.Value)(s),
      "windowStart" -> spec.window.fold(ujson.Null: ujson.Value)(w => n(w._2)),
      "windowEnd" -> spec.window.fold(ujson.Null: ujson.Value)(w => n(w._3)),
      "subjectId" -> s(spec.subject),
      "nodeKind" -> spec.nodeKind.fold(ujson.Null: ujson.Value)(s),
      "summaryText" -> s(spec.text),
      "evidenceWorkIds" -> strs(spec.works),
      "evidenceMetrics" -> strs(spec.metrics),
      "evidenceNodeIds" -> strs(spec.nodes),
      "provenance" -> prov(spec.summaryId, run)
    )

  /** A hand-built, Spark-shaped world: Unicode names, real-style IDs, a work
    * with no year and no source, a window-less work, and an unreachable path.
    */
  object World:
    val ada: String = id("researcher", "ada")
    val zoe: String = id("researcher", "zoe")
    val grace: String = id("researcher", "grace")
    val workA: String = id("work", "lattice")
    val workB: String = id("work", "genome")
    val workC: String = id("work", "orphan")
    val comm1: String = id("community", "c1")
    val comm2: String = id("community", "c2")
    val adaName = "Ada Lovelace-Müller"
    val zoeName = "Zoë 张伟"
    val graceName = "Grace Hopper"
    val w1: Option[(String, Int, Int)] = Some((window1, 2015, 2019))
    val w2: Option[(String, Int, Int)] = Some((window2, 2020, 2024))
    private val metricNames =
      Seq("publicationCount", "distinctCoauthorCount", "weightedDegree")

    val specs: Vector[Spec] = Vector(
      Spec(
        "adaW1",
        "researcher-profile",
        w1,
        ada,
        s"Researcher $adaName in window $window1: 4 publications, 2 distinct " +
          "coauthors, weighted degree 7.",
        Seq(workA),
        metricNames,
        Seq(ada),
        Some("researcher")
      ),
      Spec(
        "adaW2",
        "researcher-profile",
        w2,
        ada,
        s"Researcher $adaName in window $window2: 6 publications, 3 distinct " +
          "coauthors, weighted degree 11.",
        Seq(workB),
        metricNames,
        Seq(ada),
        Some("researcher")
      ),
      Spec(
        "zoeW1",
        "researcher-profile",
        w1,
        zoe,
        s"Researcher $zoeName in window $window1: 2 publications, 1 distinct " +
          "coauthors, weighted degree 3.",
        Seq(workA),
        metricNames,
        Seq(zoe),
        Some("researcher")
      ),
      Spec(
        "graceW2",
        "researcher-profile",
        w2,
        grace,
        s"Researcher $graceName in window $window2: 9 publications, 4 " +
          "distinct coauthors, weighted degree 20.",
        Seq(workB),
        metricNames,
        Seq(grace),
        Some("researcher")
      ),
      Spec(
        "comm1",
        "community",
        w1,
        comm1,
        s"Community $comm1 in window $window1 has 5 nodes, 4 internal " +
          s"collaboration edges. Most connected members: $adaName; $zoeName.",
        Seq(workA),
        Seq("communitySize", "internalEdges"),
        Seq(ada, zoe)
      ),
      Spec(
        "comm2",
        "community",
        w2,
        comm2,
        s"Community $comm2 in window $window2 has 3 nodes, 2 internal " +
          s"collaboration edges. Most connected members: $graceName; $adaName.",
        Seq(workB),
        Seq("communitySize", "internalEdges"),
        Seq(grace, ada)
      ),
      Spec(
        "workA",
        "work",
        None,
        workA,
        "Work Lattice quantum chromodynamics simulation, 2016, team of 3 " +
          "authors.",
        Seq(workA),
        Seq("teamSize"),
        Seq(ada, zoe)
      ),
      Spec(
        "workB",
        "work",
        None,
        workB,
        "Work Bayesian nonparametric clustering of genomes, 2021, team of 2 " +
          "authors.",
        Seq(workB),
        Seq("teamSize"),
        Seq(grace, ada)
      ),
      Spec(
        "workC",
        "work",
        None,
        workC,
        "Work Orphan manuscript without publication year, team of 1 author.",
        Seq(workC),
        Seq("teamSize"),
        Seq(zoe),
        windowLabelOnly = Some("not-windowed")
      ),
      Spec(
        "pathAG",
        "collaboration-path",
        w2,
        s"pair:$ada|$grace",
        s"Collaboration path between $adaName and $graceName in window " +
          s"$window2: connected by 1 hop, supported by work $workB.",
        Seq(workB),
        Seq("pathLength"),
        Seq(ada, grace)
      ),
      Spec(
        "pathAZ",
        "collaboration-path",
        w1,
        s"pair:$ada|$zoe",
        s"No collaboration path between $adaName and $zoeName in window " +
          s"$window1 within the hop limit of 3.",
        Seq.empty,
        Seq("pathLength"),
        Seq(ada, zoe)
      )
    )

    val label: Map[String, Spec] = specs.map(sp => sp.label -> sp).toMap
    def idOf(l: String): String = label(l).summaryId
    val summaries: Vector[Row] = specs.map(summaryRow(_))

    val nodesWork: Vector[Row] = Vector(
      Map(
        "canonicalWorkId" -> s(workA),
        "year" -> n(2016),
        "source" -> s("dblp"),
        "provenance" -> prov(workA)
      ),
      Map(
        "canonicalWorkId" -> s(workB),
        "year" -> n(2021),
        "source" -> s("openalex"),
        "provenance" -> prov(workB)
      ),
      Map(
        "canonicalWorkId" -> s(workC),
        "year" -> ujson.Null,
        "source" -> ujson.Null,
        "provenance" -> prov(workC)
      )
    )

    private def metricRow(who: String, window: String, community: String): Row =
      Map(
        "researcherId" -> s(who),
        "window" -> s(window),
        "communityId" -> s(community),
        "provenance" -> prov(who)
      )

    val metrics: Vector[Row] = Vector(
      metricRow(ada, window1, comm1),
      metricRow(zoe, window1, comm1),
      metricRow(ada, window2, comm2),
      metricRow(grace, window2, comm2)
    )

    val tables: Map[String, Vector[Row]] = Map(
      "semantic-summaries" -> summaries,
      "nodes-work" -> nodesWork,
      "researcher-metrics" -> metrics
    )

    def source: InMemoryTableSource = new InMemoryTableSource(tables)

  // JSON-lines export writer -------------------------------------------------

  private def sortKey(table: String, row: Row): String =
    def t(c: String) = row.get(c).flatMap(_.strOpt).getOrElse("")
    table match
      case "semantic-summaries" => t("summaryId")
      case "nodes-work"         => t("canonicalWorkId")
      case _                    => t("researcherId") + "|" + t("window")

  def jsonLine(row: Row): String = ujson.write(ujson.Obj.from(row.toSeq))

  def utf8(text: String): Array[Byte] = text.getBytes(UTF_8)

  def partBytes(lines: Seq[String]): Array[Byte] =
    utf8(lines.mkString("", "\n", "\n"))

  def partPath(table: String, index: Int): String =
    f"$table/part-$index%05d.jsonl"

  /** Writes an export directory the way report 01 describes it: rows sorted by
    * id, fixed split into part files, `export-manifest.json` and `_SUCCESS`.
    */
  def writeExport(
      dir: Path,
      tables: Map[String, Vector[Row]] = World.tables,
      rowsPerFile: Int = 3,
      run: String = runId,
      success: Boolean = true
  ): Path =
    Files.createDirectories(dir)
    val described = tables.toVector.sortBy(_._1).map { case (name, rows) =>
      val lines = rows.sortBy(sortKey(name, _)).map(jsonLine)
      val groups = lines.grouped(rowsPerFile).toVector
      val entries = groups.zipWithIndex.map { case (group, index) =>
        val bytes = partBytes(group)
        val target = dir.resolve(partPath(name, index))
        Files.createDirectories(target.getParent)
        Files.write(target, bytes)
        ujson.Obj(
          "path" -> partPath(name, index),
          "rows" -> group.size,
          "bytes" -> bytes.length,
          "sha256" -> Hashing.sha256(bytes)
        )
      }
      name -> ujson.Obj(
        "rows" -> rows.size,
        "files" -> ujson.Arr.from(entries)
      )
    }
    val manifest = ujson.Obj(
      "manifestType" -> JsonlExportSource.ManifestType,
      "exportVersion" -> "spark-jsonl-export/1.0.0",
      "sourceRunId" -> run,
      "sourceSuccessSeen" -> true,
      "transformVersion" -> transform,
      "tables" -> ujson.Obj.from(described)
    )
    Files.write(
      dir.resolve(JsonlExportSource.ManifestName),
      utf8(ujson.write(manifest, indent = 2))
    )
    if success then Files.write(dir.resolve("_SUCCESS"), Array.emptyByteArray)
    dir

  def readManifest(dir: Path): ujson.Value =
    ujson.read(Files.readAllBytes(dir.resolve(JsonlExportSource.ManifestName)))

  def writeManifest(dir: Path, manifest: ujson.Value): Unit =
    Files.write(
      dir.resolve(JsonlExportSource.ManifestName),
      utf8(ujson.write(manifest, indent = 2))
    )

  /** Replaces a part file and keeps the manifest consistent with it. */
  def replacePartConsistently(
      dir: Path,
      table: String,
      index: Int,
      bytes: Array[Byte],
      rows: Int
  ): Unit =
    Files.write(dir.resolve(partPath(table, index)), bytes)
    val manifest = readManifest(dir)
    val files = manifest("tables")(table)("files").arr
    val old = files(index).obj("rows").num.toInt
    files(index) = ujson.Obj(
      "path" -> partPath(table, index),
      "rows" -> rows,
      "bytes" -> bytes.length,
      "sha256" -> Hashing.sha256(bytes)
    )
    manifest("tables")(table)("rows") =
      ujson.Num(manifest("tables")(table)("rows").num - old + rows)
    writeManifest(dir, manifest)

  def tempDir(name: String = "p8"): Path = Files.createTempDirectory(name)

  def code(body: => Any): ErrorCode = Phase7Storage.code(body)

  // Probes and fakes ---------------------------------------------------------

  /** Object store that a test can tamper with. */
  final class MutableStore extends ObjectStore:
    private val objects = TrieMap.empty[String, (Array[Byte], ObjectMeta)]

    protected def write(
        key: String,
        bytes: Array[Byte],
        sourceRef: String
    ): Unit =
      objects.put(
        key,
        (
          bytes.clone(),
          ObjectMeta(key, bytes.length.toLong, Hashing.sha256(bytes), sourceRef)
        )
      )

    def head(key: String): Option[ObjectMeta] = objects.get(key).map(_._2)
    def get(key: String): Option[Array[Byte]] =
      objects.get(key).map(_._1.clone())
    def list(prefix: String): Vector[ObjectMeta] =
      objects.values
        .map(_._2)
        .filter(_.key.startsWith(prefix))
        .toVector
        .sortBy(_.key)
    def overwrite(key: String, bytes: Array[Byte]): Unit =
      write(key, bytes, "tampered")
    def delete(key: String): Unit = objects.remove(key)
    def snapshot: Map[String, String] =
      list("").map(m => m.key -> m.sha256).toMap

  /** Vector bucket that counts every call and can inject failures. `onPut` runs
    * before the real write with the 1-based call number.
    */
  final class ProbeBucket(
      val inner: InMemoryVectorBucket = new InMemoryVectorBucket,
      var onPut: (Int, String, Vector[VectorRecord]) => Unit = (_, _, _) => ()
  ) extends VectorBucketPort:
    var putCalls = 0
    val attempted = mutable.ArrayBuffer.empty[(String, String)]
    val landed = mutable.ArrayBuffer.empty[(String, String)]

    def ensureIndex(spec: VectorIndexSpec): Unit = inner.ensureIndex(spec)
    def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
      putCalls += 1
      attempted ++= batch.map(r => (index, r.key))
      onPut(putCalls, index, batch)
      inner.putVectors(index, batch)
      landed ++= batch.map(r => (index, r.key))
    def getVectors(index: String, keys: Vector[String]): Vector[VectorRecord] =
      inner.getVectors(index, keys)
    def count(index: String): Int = inner.count(index)
    def query(request: VectorQuery): Vector[VectorMatch] =
      inner.query(request)

  /** Another model with the same dimension. */
  final class RenamedEmbedder(val modelVersion: String) extends Embedder:
    private val base = new HashEmbedder
    val dimension: Int = base.dimension
    def embed(text: String): Vector[Float] = base.embed(text)

  final class SmallEmbedder extends Embedder:
    val modelVersion = "hash-embed-v1"
    val dimension = 8
    def embed(text: String): Vector[Float] =
      Vector.tabulate(8)(i => if i == text.length % 8 then 1f else 0f)

  /** One ingest rig: the real classes wired as `IngestMain` wires them. */
  final class Rig(
      val renderer: SummaryRenderer = RagTemplates,
      val embedder: Embedder = new HashEmbedder,
      val bucket: ProbeBucket = new ProbeBucket,
      val store: MutableStore = new MutableStore,
      val table: InMemoryDynamoTable = new InMemoryDynamoTable(
        RunLedger.spec(TableNames("p8"))
      )
  ):
    def ingest(
        source: TableSource,
        label: String = "1",
        batch: Int = 500,
        retry: RetryPolicy = RetryPolicy(5, 1, 4),
        run: String = runId
    ): IngestReport =
      new EmbeddingIngest(embedder, renderer, bucket, store, table).run(
        source,
        SourceRun(run, transform),
        IngestConfig(
          startedAt,
          batch,
          retry,
          executionLabel = label
        )
      )

    def runStatus(report: IngestReport): Option[String] =
      table
        .get(s"RUN#${report.executionId}", "META")
        .flatMap(Attr.optS(_, "status"))

    def batchItems(report: IngestReport): Vector[Item] =
      table.query(s"RUN#${report.executionId}", Some("STAGE#batch#"), 1000)

    def probe: Vector[Float] =
      Vector.tabulate(HashEmbedder.Dimension)(i => if i == 0 then 1f else 0f)

    /** Every vector of an index (the fake returns all of them for a big topK).
      */
    def vectors(index: String): Vector[VectorRecord] =
      val hits =
        bucket.query(VectorQuery(index, probe, Limits.S3Vectors.MaxTopK))
      bucket.getVectors(index, hits.map(_.key).take(100)) ++
        hits.drop(100).grouped(100).flatMap { g =>
          bucket.getVectors(index, g.map(_.key))
        }

  val indexNames: Vector[String] = Vector(
    VectorIndexes.ResearcherProfile,
    VectorIndexes.CollaborationCommunity,
    VectorIndexes.PaperAbstract,
    VectorIndexes.CollaborationPath,
    VectorIndexes.TopicInstitutionSummary
  )

  /** Metric names that must never be vector metadata or context keys. */
  val metricKeys: Set[String] = Set(
    "publicationCount",
    "distinctCoauthorCount",
    "totalCoauthorOccurrences",
    "averageCoauthorsPerPaper",
    "maximumCoauthorsOnOnePaper",
    "weightedDegree",
    "componentId",
    "componentSize",
    "communitySize",
    "bridgeScore",
    "coreness",
    "participationCoefficient",
    "unresolvedCoauthorOccurrences",
    "jointPublicationCount",
    "pathLength",
    "hopCount",
    "internalEdges",
    "crossEdges",
    "degree",
    "shortestPath"
  )

  def metaStr(md: Map[String, MetaValue], key: String): Option[String] =
    md.get(key) match
      case Some(MetaValue.Str(v)) => Some(v)
      case _                      => None
