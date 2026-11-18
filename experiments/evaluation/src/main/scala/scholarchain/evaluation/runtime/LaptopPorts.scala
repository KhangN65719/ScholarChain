package scholarchain.evaluation.runtime

import java.nio.file.Path
import java.security.MessageDigest
import scala.util.control.NonFatal
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import scholarchain.evaluation.io.{ExportFiles, J}
import scholarchain.rag.{
  AwsIngestMain,
  BedrockAnswerModel,
  DemoWorld,
  DeterministicStubModel,
  EmbeddingModelSpec,
  EmbeddingModels,
  InMemoryMetricRanking,
  ModelPort,
  RuntimeClock,
  SdkConverseClient,
  SemanticRetrieval,
  TableWorkCatalog,
  WorkflowConfig,
  WorkflowPorts
}
import scholarchain.storage.{
  AwsClients,
  AwsObjectStore,
  AwsReadModels,
  AwsTimeouts,
  AwsVectorBucket,
  Embedder,
  EvidenceBundleTable,
  HashEmbedder,
  InMemoryDynamoTable,
  InMemoryNeptune,
  InMemoryObjectStore,
  JsonlExportSource,
  LoadBounds,
  LocalExportStore,
  NeptuneExporter,
  ObjectStore,
  QuerySession,
  SparkGraphProjector,
  TableNames,
  TitanEmbedder,
  VectorBucketPort
}

/** The laptop runtime: `WorkflowPorts` built without `AwsSettings`, a Neptune
  * host, ECS or an ALB (plan finding 4). The graph port is `InMemoryNeptune`,
  * the reference implementation of the same two catalog queries, loaded from
  * the export through the projector path of `GraphRehearsal`. The read models
  * come from `AwsReadModels.fromExport` over a `LocalExportStore`. Semantic
  * retrieval and the answer model are the production adapters (Titan, S3
  * Vectors, S3 GetObject, Bedrock Converse) behind the cost guard.
  *
  * The region is fixed to us-east-2: anything else is refused. Bucket names and
  * the model id are arguments, with no default that points at a real resource,
  * and no secret is read or stored.
  */
object LaptopPorts:
  val ApprovedRegion = "us-east-2"

  def requireRegion(region: String): String =
    if region != ApprovedRegion then
      throw new IllegalArgumentException(
        s"the region must be $ApprovedRegion; refusing '$region'"
      )
    region

  /** A run id of the form `run:v1:<uuid4>`, derived from a label, so the same
    * label gives the same query run id.
    */
  def runIdFor(label: String): String =
    val hex = MessageDigest
      .getInstance("SHA-256")
      .digest(label.getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x")
      .mkString
    s"run:v1:${hex.substring(0, 8)}-${hex.substring(8, 12)}-4" +
      s"${hex.substring(13, 16)}-8${hex.substring(17, 20)}-" +
      hex.substring(20, 32)

  /** What a run needs: the ports, the config, the guard, a factory for direct
    * searches and a description that holds no secret and no bucket name.
    */
  final case class Wiring(
      ports: WorkflowPorts,
      config: WorkflowConfig,
      guard: CostGuard,
      recorder: Option[RecordingVectorBucket],
      directBucket: Option[RecordingVectorBucket],
      direct: Option[() => SemanticRetrieval],
      description: ujson.Value,
      close: () => Unit
  ):
    def vectorQueries: Long =
      recorder.map(_.queryCount).getOrElse(0L) +
        directBucket.map(_.queryCount).getOrElse(0L)

  /** The semantic side and the model behind the guard, over base ports that
    * already hold the graph and the read models.
    */
  def assemble(
      base: WorkflowPorts,
      config: WorkflowConfig,
      guard: CostGuard,
      embedder: Embedder,
      embedderBillable: Boolean,
      bucket: Option[VectorBucketPort],
      store: ObjectStore,
      spec: EmbeddingModelSpec,
      model: ModelPort,
      modelBillable: Boolean,
      description: ujson.Value,
      close: () => Unit
  ): Wiring =
    val guardedEmbedder = new GuardedEmbedder(embedder, guard, embedderBillable)
    val workflowBucket = bucket.map(b => new RecordingVectorBucket(b))
    val directBucket = bucket.map(b => new RecordingVectorBucket(b))
    val semantic = workflowBucket.map(b =>
      new SemanticRetrieval(guardedEmbedder, b, store, spec)
    )
    val direct = directBucket.map(b =>
      () =>
        new SemanticRetrieval(new MemoEmbedder(guardedEmbedder), b, store, spec)
    )
    val ports = base.copy(
      semantic = semantic,
      model = new GuardedModel(model, guard, modelBillable)
    )
    Wiring(
      ports,
      config,
      guard,
      workflowBucket,
      directBucket,
      direct,
      description,
      close
    )

  /** The offline world: the bundled demo world (hash embedder, in-memory
    * vectors, in-memory graph) with the deterministic stub model. No network
    * and no credential. `model` replaces the stub (a test passes a fake that
    * reports usage tokens, with `modelBillable = true`).
    */
  def offline(
      guard: CostGuard,
      model: Option[ModelPort] = None,
      modelBillable: Boolean = false
  ): Wiring =
    val world = DemoWorld.build()
    assemble(
      world.ports,
      world.config,
      guard,
      new HashEmbedder,
      embedderBillable = false,
      Some(world.vectorBucket),
      world.objectStore,
      EmbeddingModels.HashEmbedV1,
      model.getOrElse(new DeterministicStubModel),
      modelBillable,
      ujson.Obj(
        "mode" -> "offline",
        "graph" -> "demo-world",
        "embedder" -> HashEmbedder.ModelVersion,
        "answerModel" -> model.fold("stub")(_ => "injected")
      ),
      () => ()
    )

  /** The arguments of the live runtime. No default names a real resource. */
  final case class LiveSettings(
      exportDir: Path,
      region: String,
      dataBucket: String,
      vectorBucket: String,
      answerModelId: Option[String],
      useVectors: Boolean = true
  ):
    requireRegion(region)
    require(
      !useVectors || (dataBucket.trim.nonEmpty && vectorBucket.trim.nonEmpty),
      "vector retrieval needs the data bucket and the vector bucket"
    )

  /** The Titan client's SDK settings: the storage module's Bedrock deadlines
    * (60 s per attempt, 180 s per call) and no SDK retry. The storage client
    * keeps the SDK default retry, so one admitted call could make several
    * requests and the call count and the USD estimate would undercount. With no
    * retry one admitted call is one request; a failed embed fails the question
    * (the run does not retry, as `run.sh` says).
    */
  def titanOverride: ClientOverrideConfiguration =
    ClientOverrideConfiguration
      .builder()
      .apiCallAttemptTimeout(AwsTimeouts.BedrockAttempt)
      .apiCallTimeout(AwsTimeouts.BedrockCall)
      .retryPolicy(RetryPolicy.none())
      .build()

  def titanClient(clients: AwsClients): BedrockRuntimeClient =
    BedrockRuntimeClient
      .builder()
      .region(clients.region)
      .credentialsProvider(clients.credentials)
      .httpClient(clients.httpClient)
      .overrideConfiguration(titanOverride)
      .build()

  /** The InMemoryNeptune of an export: project, export to an in-memory store,
    * bulk-load. About 6 GB of heap for tier S (`GraphRehearsal.scala:14-16`).
    */
  def inMemoryGraph(exportDir: Path): InMemoryNeptune =
    val source = JsonlExportSource.open(exportDir)
    val projection =
      new SparkGraphProjector(source, LoadBounds.Default).project()
    val store = new InMemoryObjectStore
    val report =
      NeptuneExporter.exportTo(projection, source.sourceRun.runId, store)
    val graph = new InMemoryNeptune(store)
    graph.load(report.manifestKey)
    graph

  /** The live laptop runtime over a local Spark export. Reads the export from
    * disk, builds the in-memory graph, and (when `useVectors`) connects Titan,
    * S3 Vectors and the data bucket in us-east-2. The answer model is Bedrock
    * when `answerModelId` is given, the stub otherwise.
    */
  def live(settings: LiveSettings, guard: CostGuard): Wiring =
    val manifest =
      ExportFiles.readJson(settings.exportDir.resolve("export-manifest.json"))
    val graphRunId = J
      .str(manifest, "sourceRunId")
      .getOrElse(
        throw new IllegalArgumentException(
          "export-manifest.json has no sourceRunId"
        )
      )
    val slug = AwsReadModels.slugOf(graphRunId)
    val exported = AwsReadModels.fromExport(
      new LocalExportStore(
        settings.exportDir,
        AwsReadModels.exportPrefixOf(slug)
      ),
      graphRunId,
      exportRunId = Some(slug)
    )
    val graph = inMemoryGraph(settings.exportDir)
    val names = TableNames("laptop-eval")
    val closeables = Vector.newBuilder[AutoCloseable]
    def release(): Unit =
      closeables
        .result()
        .foreach(c =>
          try c.close()
          catch case NonFatal(_) => ()
        )
    try
      lazy val clients = new AwsClients(Region.of(settings.region))
      val base = WorkflowPorts(
        exported.metricCache,
        new InMemoryMetricRanking(exported.metrics),
        graph,
        exported.paths,
        exported.components,
        exported.communities,
        exported.directory,
        new TableWorkCatalog(exported.workRows),
        None,
        new DeterministicStubModel,
        new InMemoryDynamoTable(QuerySession.spec(names)),
        new InMemoryDynamoTable(EvidenceBundleTable.spec(names)),
        RuntimeClock.system
      )
      val config =
        WorkflowConfig(graphRunId, runIdFor(s"laptop-eval|$graphRunId"))
      val (model, billable) = settings.answerModelId match
        case Some(id) =>
          val sdk = BedrockAnswerModel.sdkClient(settings.region)
          closeables += sdk
          val bedrock = new BedrockAnswerModel(
            new SdkConverseClient(sdk),
            id,
            math.max(1, guard.limits.haikuMaxCalls),
            _ => ()
          )
          (bedrock: ModelPort, true)
        case None => (new DeterministicStubModel: ModelPort, false)
      val description = ujson.Obj(
        "mode" -> "live",
        "region" -> settings.region,
        "graph" -> "InMemoryNeptune over the local export",
        "graphRunId" -> graphRunId,
        "vectors" -> settings.useVectors,
        "embedder" -> TitanEmbedder.ModelVersion,
        "answerModel" -> settings.answerModelId.getOrElse("stub")
      )
      if settings.useVectors then
        val runtime = titanClient(clients)
        closeables += runtime
        val s3 = clients.s3()
        closeables += s3
        val vectors = clients.s3Vectors()
        closeables += vectors
        assemble(
          base,
          config,
          guard,
          new TitanEmbedder(runtime),
          embedderBillable = true,
          Some(new AwsVectorBucket(vectors, settings.vectorBucket)),
          new AwsObjectStore(
            s3,
            settings.dataBucket,
            AwsIngestMain.MaxListedObjects
          ),
          EmbeddingModels.TitanTextV2D256,
          model,
          billable,
          description,
          () => release()
        )
      else
        assemble(
          base,
          config,
          guard,
          new HashEmbedder,
          embedderBillable = false,
          None,
          new InMemoryObjectStore,
          EmbeddingModels.HashEmbedV1,
          model,
          billable,
          description,
          () => release()
        )
    catch
      case NonFatal(e) =>
        release()
        throw e
