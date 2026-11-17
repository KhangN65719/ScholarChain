package scholarchain.storage

import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.util.control.NonFatal

/** The `embedding-work` queue consumer, run as `java -cp <lib wildcard>
  * scholarchain.storage.EmbeddingWorkerMain` with the settings below in the
  * environment. `--help` prints them and `--check-config` validates them; both
  * make no AWS call. This object is the only place that reads the process
  * environment (`main` passes `sys.env`). Every client takes Region us-east-2
  * and the bounded timeouts of `AwsTimeouts`; credentials are the SDK default
  * chain (the ECS task role); no endpoint is assumed to be public.
  *
  * Exit codes: 0 stopped (SIGTERM or `SCHOLARCHAIN_WORKER_MAX_MESSAGES`), 2
  * configuration error, 1 any other failure.
  */
object EmbeddingWorkerMain:
  val AllowedRegion = "us-east-2"
  val EnvRegion = "AWS_REGION"
  val EnvQueueUrl = "SCHOLARCHAIN_EMBEDDING_QUEUE_URL"
  val EnvDataBucket = "SCHOLARCHAIN_DATA_BUCKET"
  val EnvVectorBucket = "SCHOLARCHAIN_VECTOR_BUCKET"
  val EnvTablePrefix = "SCHOLARCHAIN_TABLE_PREFIX"
  val EnvWorkerId = "SCHOLARCHAIN_WORKER_ID"
  val EnvBatch = "SCHOLARCHAIN_WORKER_BATCH"
  val EnvWait = "SCHOLARCHAIN_WORKER_WAIT_SECONDS"
  val EnvVisibility = "SCHOLARCHAIN_WORKER_VISIBILITY_SECONDS"
  val EnvLease = "SCHOLARCHAIN_WORKER_LEASE_SECONDS"
  val EnvRetryDelay = "SCHOLARCHAIN_WORKER_RETRY_DELAY_SECONDS"
  val EnvMaxMessages = "SCHOLARCHAIN_WORKER_MAX_MESSAGES"
  val EnvGrace = "SCHOLARCHAIN_WORKER_SHUTDOWN_GRACE_SECONDS"
  val HelpFlag = "--help"
  val CheckFlag = "--check-config"
  val MaxListedObjects = 1000

  val Usage = s"usage: EmbeddingWorkerMain [$HelpFlag | $CheckFlag]"

  val Help: Vector[String] = Vector(
    Usage,
    "required environment (names only; values are never printed):",
    s"  $EnvRegion  must be $AllowedRegion",
    s"  $EnvQueueUrl  the embedding-work queue URL",
    s"  $EnvDataBucket  ordinary S3 bucket for the chunk documents",
    s"  $EnvVectorBucket  S3 Vectors bucket",
    s"  $EnvTablePrefix  DynamoDB table prefix (run-ledger table)",
    "optional environment (default; range):",
    s"  $EnvWorkerId  random  ledger owner name",
    s"  $EnvBatch  5  1-10 messages per receive",
    s"  $EnvWait  10  0-20 long-poll seconds",
    s"  $EnvVisibility  120  30-900 seconds a received message is hidden",
    s"  $EnvLease  100  10 to visibility-5 seconds a ledger claim is held",
    s"  $EnvRetryDelay  0  0-900 seconds before a failed message is retried (0: queue default)",
    s"  $EnvMaxMessages  0  stop after this many messages (0: run until SIGTERM)",
    s"  $EnvGrace  25  1-120 seconds SIGTERM waits for the message in flight"
  )

  final case class Config(
      region: String,
      queueUrl: String,
      dataBucket: String,
      vectorBucket: String,
      tablePrefix: String,
      settings: WorkerSettings
  )

  /** What `awsWiring` (or a test) supplies. */
  final case class Wiring(
      queue: WorkQueue,
      embedder: Embedder,
      store: ObjectStore,
      bucket: VectorBucketPort,
      ledger: DynamoTable,
      close: () => Unit = () => ()
  )

  private def refuse(message: String): Nothing =
    Fail(ErrorCode.InvalidQuery, message)

  private def required(env: Map[String, String], name: String): String =
    env.get(name).map(_.trim).filter(_.nonEmpty).getOrElse {
      refuse(s"Missing required environment variable $name")
    }

  private def checked(name: String, value: String, pattern: String): String =
    if value.matches(pattern) then value
    else refuse(s"Environment variable $name has an invalid format")

  private def number(
      env: Map[String, String],
      name: String,
      default: Long
  ): Long =
    env.get(name).map(_.trim) match
      case None | Some("") => default
      case Some(text)      =>
        text.toLongOption.getOrElse(
          refuse(s"Environment variable $name must be a whole number")
        )

  private val bucketPattern = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"
  private val prefixPattern = "[A-Za-z0-9_.-]{1,64}"
  private val idPattern = "[A-Za-z0-9._-]{1,64}"
  private val queuePattern =
    "https://sqs\\.us-east-2\\.amazonaws\\.com/[0-9]{12}/[A-Za-z0-9_-]{1,80}"

  /** Pure over its argument. Names the missing or malformed variable, never its
    * value.
    */
  def parseConfig(env: Map[String, String]): Config =
    val region = required(env, EnvRegion)
    Fail.unless(
      region == AllowedRegion,
      ErrorCode.InvalidQuery,
      s"Environment variable $EnvRegion must be $AllowedRegion"
    )
    val workerId = env
      .get(EnvWorkerId)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(checked(EnvWorkerId, _, idPattern))
      .getOrElse(s"worker-${UUID.randomUUID().toString.take(8)}")
    def int(name: String, default: Int): Int =
      val value = number(env, name, default.toLong)
      Fail.unless(
        value >= Int.MinValue && value <= Int.MaxValue,
        ErrorCode.InvalidQuery,
        s"Environment variable $name is out of range"
      )
      value.toInt
    val settings =
      try
        WorkerSettings(
          workerId,
          int(EnvBatch, 5),
          int(EnvWait, 10),
          int(EnvVisibility, 120),
          int(EnvLease, 100),
          int(EnvRetryDelay, 0),
          number(env, EnvMaxMessages, 0L),
          int(EnvGrace, 25)
        )
      catch
        case error: StorageException if error.code == ErrorCode.InvalidQuery =>
          // The message names the setting (an environment variable's value is
          // never in it).
          throw error
    Config(
      region,
      checked(EnvQueueUrl, required(env, EnvQueueUrl), queuePattern),
      checked(EnvDataBucket, required(env, EnvDataBucket), bucketPattern),
      checked(EnvVectorBucket, required(env, EnvVectorBucket), bucketPattern),
      checked(EnvTablePrefix, required(env, EnvTablePrefix), prefixPattern),
      settings
    )

  def awsWiring(
      config: Config,
      clientsFor: String => AwsClients = AwsClients.forRegion,
      queueFor: (AwsClients, String) => WorkQueue = WorkQueues.sqs
  ): Wiring =
    val clients = clientsFor(config.region)
    val s3 = clients.s3()
    val vectors = clients.s3Vectors()
    val bedrock = clients.bedrockRuntime()
    val dynamo = clients.dynamo()
    val spec =
      try RunLedger.spec(TableNames(config.tablePrefix))
      catch
        case _: StorageException =>
          refuse(s"Environment variable $EnvTablePrefix is not usable")
    val queue = queueFor(clients, config.queueUrl)
    Wiring(
      queue,
      new TitanEmbedder(bedrock, RetryPolicy(3, 200L, 2000L)),
      new AwsObjectStore(s3, config.dataBucket, MaxListedObjects),
      new AwsVectorBucket(vectors, config.vectorBucket),
      new AwsDynamoTable(dynamo, spec),
      () =>
        (Vector(s3, vectors, bedrock, dynamo): Vector[AutoCloseable]).foreach {
          client =>
            try client.close()
            catch case NonFatal(_) => ()
        }
    )

  /** Runs the worker and returns the exit code. `registerShutdown` receives the
    * stop action and a wait-until-finished action; `main` installs them as the
    * SIGTERM hook, so the message in flight finishes (or is released) in the
    * grace period.
    */
  def run(
      args: Array[String],
      env: Map[String, String],
      out: String => Unit,
      wiringFor: Config => Wiring = c => awsWiring(c),
      registerShutdown: (() => Unit, () => Unit) => Unit = (_, _) => ()
  ): Int =
    if args.toVector == Vector(HelpFlag) then
      Help.foreach(out)
      0
    else if args.nonEmpty && args.toVector != Vector(CheckFlag) then
      out(s"WORKER_RESULT=FAILED reason=$Usage")
      2
    else
      val config =
        try Right(parseConfig(env))
        catch case error: StorageException => Left(error.getMessage)
      config match
        case Left(message) =>
          out(s"WORKER_RESULT=FAILED reason=${message.take(300)}")
          2
        case Right(cfg) if args.nonEmpty =>
          out(
            s"WORKER_CONFIG ok region=${cfg.region} " +
              s"worker=${cfg.settings.workerId} batch=${cfg.settings.batchSize} " +
              s"wait_s=${cfg.settings.waitSeconds} " +
              s"visibility_s=${cfg.settings.visibilitySeconds} " +
              s"lease_s=${cfg.settings.leaseSeconds}"
          )
          0
        case Right(cfg) => runWorker(cfg, out, wiringFor, registerShutdown)

  private def runWorker(
      config: Config,
      out: String => Unit,
      wiringFor: Config => Wiring,
      registerShutdown: (() => Unit, () => Unit) => Unit
  ): Int =
    var close: () => Unit = () => ()
    try
      val wiring = wiringFor(config)
      close = () =>
        try wiring.queue.close()
        finally wiring.close()
      val worker = new EmbeddingWorker(
        wiring.queue,
        wiring.embedder,
        wiring.store,
        wiring.bucket,
        wiring.ledger,
        config.settings,
        out
      )
      val finished = new CountDownLatch(1)
      registerShutdown(
        () => worker.stop(),
        () =>
          finished.await(config.settings.shutdownGraceSeconds, TimeUnit.SECONDS)
          ()
      )
      try worker.run()
      finally finished.countDown()
      out("WORKER_RESULT=STOPPED")
      0
    catch
      case NonFatal(error) =>
        // Class name and storage code only: a message can hold an ARN.
        val why = error match
          case failure: StorageException => failure.code.toString
          case other                     => other.getClass.getSimpleName
        out(s"WORKER_RESULT=FAILED reason=$why")
        1
    finally
      try close()
      catch case NonFatal(_) => ()

  def main(args: Array[String]): Unit =
    val code = run(
      args,
      sys.env.toMap,
      println,
      registerShutdown = (stop, await) =>
        Runtime.getRuntime.addShutdownHook(new Thread(() =>
          stop()
          await()
        ))
    )
    sys.exit(code)
