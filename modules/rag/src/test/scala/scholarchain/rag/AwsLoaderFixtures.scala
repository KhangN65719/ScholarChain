package scholarchain.rag

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scholarchain.storage.*

/** Stubbed ports for the AWS loader tests: no AWS client, no network. */
object AwsLoaderFixtures:
  val validEnv: Map[String, String] = Map(
    "AWS_REGION" -> "us-east-2",
    "SCHOLARCHAIN_DATA_BUCKET" -> "sc-data-test",
    "SCHOLARCHAIN_VECTOR_BUCKET" -> "sc-vectors-test",
    "SCHOLARCHAIN_TABLE_PREFIX" -> "sc-course",
    "SCHOLARCHAIN_NEPTUNE_HOST" -> "neptune.example.internal",
    "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN" ->
      "arn:aws:iam::123456789012:role/loader"
  )

  /** A 256-dimension stand-in for Titan: the first 256 hash-embed values,
    * renormalized. The same text gives the same vector.
    */
  final class FakeTitan extends Embedder:
    val modelVersion: String = TitanEmbedder.ModelVersion
    val dimension: Int = TitanEmbedder.Dimension

    def embed(text: String): Vector[Float] =
      val base = new HashEmbedder().embed(text).take(dimension)
      val norm = math.sqrt(base.map(f => f.toDouble * f).sum)
      if norm == 0.0 then Vector.fill(dimension)(1.0f / 16.0f)
      else base.map(f => (f / norm).toFloat)

  val graphOnlyTables: Vector[String] =
    GraphStep.RequiredTables.filterNot(_ == "nodes-work")

  /** The export of the Spark job today (three tables), or with the graph tables
    * present but empty.
    */
  def exportRows(withGraph: Boolean): Map[String, Vector[Row]] =
    RagFixtures.tables() ++
      (if withGraph then graphOnlyTables.map(_ -> Vector.empty[Row]).toMap
       else Map.empty[String, Vector[Row]])

  val projection: GraphProjection = GraphProjection(
    Vector(
      GraphVertex(
        "researcher:a",
        "Researcher",
        Map("displayName" -> Prop.Str("Ada"))
      ),
      GraphVertex(RagFixtures.w1, "Work", Map("year" -> Prop.I32(2001)))
    ),
    Vector(
      GraphEdgeRow(
        "edge:e1",
        "researcher:a",
        RagFixtures.w1,
        "AUTHORED",
        Map("authorPosition" -> Prop.I32(1))
      )
    ),
    0
  )

  /** A vector bucket whose writes fail with a non-transient error. */
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
        "s3://sc-data-test/graph/neptune/vertices-Work.csv load " +
          "load-1 ended LOAD_FAILED: records 2, parsing errors 1"
      )

  /** Fails the test if the loader is ever reached. */
  final class UnreachableLoader extends NeptuneBulkLoader:
    val calls = mutable.ArrayBuffer.empty[String]
    def load(manifestKey: String): BulkLoadResult =
      calls += manifestKey
      throw new AssertionError("the Neptune loader must not be reached")

  def seed(store: ObjectStore, exportDir: Path, prefix: String): Int =
    val stream = Files.walk(exportDir)
    val files =
      try stream.iterator.asScala.filter(Files.isRegularFile(_)).toVector
      finally stream.close()
    files.foreach { file =>
      val relative = exportDir.relativize(file).toString.replace('\\', '/')
      store.putImmutable(prefix + relative, Files.readAllBytes(file), "test")
    }
    files.size

  /** One loader world: an in-memory data bucket seeded with an export, an
    * in-memory vector bucket and ledger, a counting fake Titan, an in-memory
    * Neptune.
    */
  final class World(
      withGraph: Boolean = true,
      seeded: Boolean = true,
      val embedder: CountingEmbedder = new CountingEmbedder(new FakeTitan),
      val model: EmbeddingModelSpec = EmbeddingModels.TitanTextV2D256
  ):
    val runId: String = RagFixtures.runId
    val store = new InMemoryObjectStore
    val bucket = new InMemoryVectorBucket
    val ledger = new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    val neptune = new InMemoryNeptune(store)
    val config: AwsIngestMain.Config =
      AwsIngestMain.parseConfig(Array(runId), validEnv)
    if seeded then
      val dir = RagFixtures.writeExport(
        Files.createTempDirectory("aws-loader-export-"),
        exportRows(withGraph)
      )
      seed(store, dir, config.exportPrefix)

    def backend(
        bucketPort: VectorBucketPort = bucket,
        loader: NeptuneBulkLoader = neptune,
        project: TableSource => GraphProjection = _ => projection
    ): IngestMain.Backend =
      IngestMain.Backend(
        embedder,
        model,
        store,
        bucketPort,
        ledger,
        graph = Some(GraphLoad(loader, project)),
        aws = true,
        label = IngestMain.AwsLabel,
        embedCalls = () => Some(embedder.calls)
      )

    private var tick = 0L

    private def clock(): Instant =
      tick += 1
      Instant.parse("2026-11-14T12:00:00Z").plusSeconds(tick * 60)

    def run(
        backendFor: => IngestMain.Backend = backend(),
        env: Map[String, String] = validEnv,
        args: Array[String] = Array(runId)
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
      ujson.read(store.get(key).getOrElse(fail(s"no object $key")))

    def reportKey: String = s"${config.reportBase}/${IngestMain.ReportName}"
    def runKey: String = s"${config.reportBase}/${IngestMain.RunName}"

  private def fail(message: String): Nothing = throw new AssertionError(message)
