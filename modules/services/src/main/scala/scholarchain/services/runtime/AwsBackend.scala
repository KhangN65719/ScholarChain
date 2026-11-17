package scholarchain.services.runtime

import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal
import scholarchain.rag.{
  AwsIngestMain,
  BedrockAnswerModel,
  DeterministicStubModel,
  EmbeddingModels,
  InMemoryMetricRanking,
  MetricRanking,
  ModelPort,
  RuntimeClock,
  SdkConverseClient,
  SemanticRetrieval,
  TableWorkCatalog,
  WorkCatalog,
  WorkflowPorts
}
import scholarchain.storage.{
  AwsClients,
  AwsDynamoTable,
  AwsObjectStore,
  AwsReadModelSettings,
  AwsReadModels,
  AwsVectorBucket,
  CommunityReadPort,
  ComponentReadPort,
  DynamoTable,
  EvidenceBundleTable,
  ExportSummary,
  NeptuneQueryPort,
  PathEvidencePort,
  QuerySession,
  ResearcherDirectoryPort,
  ResearcherMetricCache,
  StorageException,
  TableNames,
  TitanEmbedder,
  TransientStorageException
}

/** The read models the workflow needs besides DynamoDB and S3 Vectors: the
  * Neptune query port and the six reads that Spark's tables back. They are one
  * value because they come from one source. `metricCache`, when present, is the
  * metric cache the metrics agent reads instead of the DynamoDB table (the live
  * source fills it from the export). `close` releases what the source holds.
  */
final case class ReadModels(
    neptune: NeptuneQueryPort,
    ranking: MetricRanking,
    paths: PathEvidencePort,
    components: ComponentReadPort,
    communities: CommunityReadPort,
    directory: ResearcherDirectoryPort,
    works: WorkCatalog,
    metricCache: Option[DynamoTable] = None,
    close: () => Unit = () => ()
)

/** The result of building the ports: the workflow ports and a way to close the
  * clients behind them.
  */
final case class AwsWiring(ports: WorkflowPorts, close: () => Unit = () => ())

object AwsWiring:
  /** A close hook that runs `close` at most once, whoever calls it first. */
  def once(close: () => Unit): () => Unit =
    val done = new AtomicBoolean(false)
    () => if done.compareAndSet(false, true) then close()

/** Builds the workflow ports of one runtime from its settings. */
trait AwsBackend:
  def build(settings: AwsSettings): AwsWiring

/** Raised at start when the read models cannot be built: the export or the
  * settings are bad, or a call to AWS failed. The message names a code or an
  * exception class, never AWS message text.
  */
final class ReadModelsUnavailable(message: String)
    extends RuntimeException(message)

object AwsBackend:
  private val log = LoggerFactory.getLogger("scholarchain.services.bedrock")

  /** The only place the answer model is chosen. `stub` is the deterministic
    * offline model and the default; `bedrock` builds the Bedrock adapter
    * through `bedrock`, with the model ID and call cap from the settings.
    */
  def answerModel(
      settings: AwsSettings,
      bedrock: BedrockModelRequest => ModelPort
  ): ModelPort =
    settings.answerModel match
      case AnswerModelKind.Stub    => new DeterministicStubModel
      case AnswerModelKind.Bedrock =>
        bedrock(
          settings.bedrockRequest.getOrElse(
            throw new IllegalArgumentException(
              s"${AwsSettings.EnvBedrockModelId} is required"
            )
          )
        )

  /** The Bedrock adapter over the SDK client, with the rag deadlines (attempt
    * 30 s, call 40 s) and no SDK retry. The slow service's own deadline
    * (`SCHOLARCHAIN_SERVICES_SLOW_DEADLINE_MILLIS`, default 10 s) answers the
    * HTTP caller first; raise it for a Bedrock run. The client lives as long as
    * the process.
    */
  def liveBedrock(request: BedrockModelRequest): ModelPort =
    new BedrockAnswerModel(
      new SdkConverseClient(BedrockAnswerModel.sdkClient(request.region)),
      request.modelId,
      request.maxCalls,
      line => log.info(line)
    )

  /** DynamoDB, S3 Vectors and Titan from the settings; the read models from
    * `readModels`. No client contacts AWS until the first call. The metrics
    * agent reads the read models' in-memory metric cache when they carry one.
    *
    * If anything fails after the read models were built, the clients built so
    * far and the read models are closed before the failure propagates. The
    * close hook of a finished build runs once.
    */
  def live(
      readModels: AwsSettings => ReadModels,
      bedrock: BedrockModelRequest => ModelPort = liveBedrock
  ): AwsBackend = new AwsBackend:
    def build(settings: AwsSettings): AwsWiring =
      val models = readModels(settings)
      val closeables = Vector.newBuilder[AutoCloseable]
      def release(): Unit =
        closeables
          .result()
          .foreach(c =>
            try c.close()
            catch case NonFatal(_) => ()
          )
        try models.close()
        catch case NonFatal(_) => ()
      try
        val clients = AwsClients.forRegion(settings.region)
        val names = TableNames(settings.tablePrefix)
        val dynamo = clients.dynamo()
        closeables += dynamo
        val semantic =
          if settings.bedrockEnabled then
            val runtime = clients.bedrockRuntime()
            closeables += runtime
            val s3 = clients.s3()
            closeables += s3
            val vectors = clients.s3Vectors()
            closeables += vectors
            Some(
              new SemanticRetrieval(
                new TitanEmbedder(runtime),
                new AwsVectorBucket(vectors, settings.vectorBucket),
                new AwsObjectStore(
                  s3,
                  settings.dataBucket,
                  AwsIngestMain.MaxListedObjects
                ),
                EmbeddingModels.TitanTextV2D256
              )
            )
          else None
        val metricCache = models.metricCache.getOrElse(
          new AwsDynamoTable(dynamo, ResearcherMetricCache.spec(names))
        )
        val ports = WorkflowPorts(
          metricCache,
          models.ranking,
          models.neptune,
          models.paths,
          models.components,
          models.communities,
          models.directory,
          models.works,
          semantic,
          answerModel(settings, bedrock),
          new AwsDynamoTable(dynamo, QuerySession.spec(names)),
          new AwsDynamoTable(dynamo, EvidenceBundleTable.spec(names)),
          RuntimeClock.system
        )
        AwsWiring(ports, AwsWiring.once(() => release()))
      catch
        case NonFatal(e) =>
          release()
          throw e

/** Where the read models of the live runtime come from. */
object ReadModelSource:
  private val log = LoggerFactory.getLogger("scholarchain.services.startup")

  /** Loads the read models of a graph run; `AwsReadModels.live` in production.
    */
  type Loader = (AwsReadModelSettings, String => Unit) => AwsReadModels

  def storageSettings(settings: AwsSettings): AwsReadModelSettings =
    AwsReadModelSettings(
      settings.region,
      settings.dataBucket,
      settings.neptuneHost,
      settings.graphRunId,
      neptunePort = settings.neptunePort,
      exportRunId = settings.exportRunId
    )

  /** Storage's read models as the services' `ReadModels`. The metric cache and
    * the close hook come along: the metrics agent reads the export's cache, and
    * closing the runtime releases the Neptune client.
    */
  def mapping(m: AwsReadModels): ReadModels =
    ReadModels(
      m.neptune,
      new InMemoryMetricRanking(m.metrics),
      m.paths,
      m.components,
      m.communities,
      m.directory,
      new TableWorkCatalog(m.workRows),
      Some(m.metricCache),
      m.close
    )

  /** The one start-up line: the run, the export versions and the counts. No
    * bucket name, host or object key.
    */
  def startupLine(s: ExportSummary): String =
    val tables = s.tables.map(t => s"${t.table}=${t.rows}").mkString(",")
    s"startup read-models loaded runId=${s.runId} " +
      s"transform=${s.transformVersion} exportVersion=${s.exportVersion} " +
      s"manifestSha256=${s.manifestSha256} objects=${s.objects} " +
      s"bytes=${s.bytes} rowsByTable=$tables " +
      s"worksWithoutTitle=${s.worksWithoutTitle.size}"

  /** What a failed load says: a storage code or an exception class, never the
    * message, which can carry AWS text, keys or bucket names.
    */
  private def reason(e: Throwable): String = e match
    case s: StorageException          => s"storage-code=${s.code}"
    case _: TransientStorageException => "aws-call-failed-transiently"
    case other => s"exception=${other.getClass.getSimpleName}"

  /** A source that loads with `load`, logs the start-up line once and maps the
    * result. A failed load raises `ReadModelsUnavailable` with a safe reason;
    * `load` cleans up after itself, so nothing is left to close.
    */
  def from(
      load: Loader,
      info: String => Unit = line => log.info(line)
  ): AwsSettings => ReadModels = settings =>
    val loaded =
      try load(storageSettings(settings), info)
      catch
        case NonFatal(e) =>
          val why = reason(e)
          log.error(s"startup read-models failed $why")
          throw new ReadModelsUnavailable(s"READ_MODELS_FAILED $why")
    info(startupLine(loaded.summary))
    mapping(loaded)

  /** The live source: the Spark export in the data bucket and the Neptune query
    * adapter. The load is slow (S3 download and verification); the runtime
    * listens only after it ends.
    */
  val live: AwsSettings => ReadModels =
    from((s, logLine) => AwsReadModels.live(s, logLine))
