package scholarchain.services.runtime

import scholarchain.services.ServiceConfig

enum AnswerModelKind(val wire: String):
  case Stub extends AnswerModelKind("stub")
  case Bedrock extends AnswerModelKind("bedrock")

enum RuntimeRole(val wire: String):
  case Fast extends RuntimeRole("fast")
  case Slow extends RuntimeRole("slow")
  case Both extends RuntimeRole("both")

  def runsFast: Boolean = this != RuntimeRole.Slow
  def runsSlow: Boolean = this != RuntimeRole.Fast

/** What `AwsRuntime` needs to build the Bedrock answer model. The model ID is a
  * setting, never a code constant.
  */
final case class BedrockModelRequest(
    region: String,
    modelId: String,
    maxCalls: Int
)

/** Every setting of the AWS runtime, parsed once from the environment. The
  * secrets (the two bearer tokens and the cursor secret) live in `service`,
  * whose `toString` is redacted; nothing else here is secret.
  */
final case class AwsSettings(
    region: String,
    tablePrefix: String,
    dataBucket: String,
    vectorBucket: String,
    bedrockEnabled: Boolean,
    neptuneHost: String,
    neptunePort: Int,
    answerModel: AnswerModelKind,
    bedrockModelId: Option[String],
    bedrockMaxCalls: Int,
    graphRunId: String,
    exportRunId: String,
    queryRunId: String,
    role: RuntimeRole,
    bindHost: String,
    fastPort: Int,
    slowPort: Int,
    service: ServiceConfig
):
  def bedrockRequest: Option[BedrockModelRequest] =
    bedrockModelId.map(BedrockModelRequest(region, _, bedrockMaxCalls))

object AwsSettings:
  val EnvRegion = "AWS_REGION"
  val EnvTablePrefix = "SCHOLARCHAIN_TABLE_PREFIX"
  val EnvDataBucket = "SCHOLARCHAIN_DATA_BUCKET"
  val EnvVectorBucket = "SCHOLARCHAIN_VECTOR_BUCKET"
  val EnvVectorDim = "SCHOLARCHAIN_VECTOR_DIM"
  val EnvBedrock = "SCHOLARCHAIN_BEDROCK"
  val EnvNeptuneHost = "SCHOLARCHAIN_NEPTUNE_HOST"
  val EnvNeptunePort = "SCHOLARCHAIN_NEPTUNE_PORT"
  val EnvAnswerModel = "SCHOLARCHAIN_ANSWER_MODEL"
  val EnvBedrockModelId = "SCHOLARCHAIN_BEDROCK_MODEL_ID"
  val EnvBedrockMaxCalls = "SCHOLARCHAIN_BEDROCK_MAX_CALLS"
  val EnvGraphRunId = "SCHOLARCHAIN_GRAPH_RUN_ID"
  val EnvExportRunId = "SCHOLARCHAIN_EXPORT_RUN_ID"
  val EnvQueryRunId = "SCHOLARCHAIN_QUERY_RUN_ID"
  val EnvRole = "SCHOLARCHAIN_RUNTIME_ROLE"
  val EnvBindHost = "SCHOLARCHAIN_BIND_HOST"
  val EnvFastPort = "SCHOLARCHAIN_FAST_PORT"
  val EnvSlowPort = "SCHOLARCHAIN_SLOW_PORT"

  val DefaultVectorDim = 256
  val DefaultNeptunePort = 8182
  val DefaultMaxCalls = 10
  val MaxCallsCeiling = 100
  val DefaultFastPort = 8080
  val DefaultSlowPort = 8081
  val DefaultBindHost = "0.0.0.0"

  /** The only Region the deployment runs in, like the embedding worker. */
  val AllowedRegion = "us-east-2"

  private val regionPattern = "[a-z]{2}(-[a-z]+)+-[0-9]{1,2}"
  private val exportRunIdPattern = "run-v1-[0-9a-f-]{36}"
  private val bucketPattern = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"
  private val prefixPattern = "[A-Za-z0-9_.-]{1,64}"
  private val hostPattern = "[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?"
  private val modelIdPattern = "[A-Za-z0-9._:/-]{1,200}"
  private val runIdPattern =
    "run:v1:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

  /** The variables read, with the default, whether one is required and what it
    * means. The `--help` text and the settings table are built from this list.
    */
  final case class Doc(
      name: String,
      default: String,
      required: String,
      meaning: String
  )

  val Docs: Vector[Doc] = Vector(
    Doc(EnvRegion, "none", "required", "AWS Region of every client"),
    Doc(EnvTablePrefix, "none", "required", "DynamoDB table name prefix"),
    Doc(EnvDataBucket, "none", "required", "S3 data bucket (text chunks)"),
    Doc(EnvVectorBucket, "none", "required", "S3 Vectors bucket"),
    Doc(
      EnvVectorDim,
      DefaultVectorDim.toString,
      "optional",
      "must equal 256, the only index dimension"
    ),
    Doc(
      EnvBedrock,
      "off",
      "optional",
      "on enables Titan query embedding (vector retrieval) and allows the bedrock answer model; off runs graph retrieval only and the stub model"
    ),
    Doc(EnvNeptuneHost, "none", "required", "Neptune cluster endpoint host"),
    Doc(
      EnvNeptunePort,
      DefaultNeptunePort.toString,
      "optional",
      "Neptune port"
    ),
    Doc(EnvAnswerModel, "stub", "optional", "stub or bedrock"),
    Doc(
      EnvBedrockModelId,
      "none",
      "required if bedrock",
      "Bedrock model or inference profile ID, never a code constant"
    ),
    Doc(
      EnvBedrockMaxCalls,
      DefaultMaxCalls.toString,
      "optional",
      s"hard cap on Bedrock answer calls per process, 1 to $MaxCallsCeiling"
    ),
    Doc(
      EnvGraphRunId,
      "none",
      "required",
      "graph run ID that pins metric reads"
    ),
    Doc(
      EnvExportRunId,
      "none",
      "required",
      "pipeline run slug (run-v1-<uuid>) the export is published under; never derived from the graph run ID"
    ),
    Doc(
      EnvQueryRunId,
      "minted per process",
      "optional",
      "run ID the audit and evidence bundle carry"
    ),
    Doc(
      EnvRole,
      "both",
      "optional",
      "fast, slow or both; the first program argument overrides it"
    ),
    Doc(EnvBindHost, DefaultBindHost, "optional", "listen address"),
    Doc(EnvFastPort, DefaultFastPort.toString, "optional", "fast service port"),
    Doc(EnvSlowPort, DefaultSlowPort.toString, "optional", "slow service port"),
    Doc(
      s"${ServiceConfig.EnvPrefix}AUTH_QUERY_TOKEN",
      "none",
      "required",
      "query bearer token (Secrets Manager through the ECS secrets block)"
    ),
    Doc(
      s"${ServiceConfig.EnvPrefix}AUTH_READ_TOKEN",
      "none",
      "required",
      "read bearer token (Secrets Manager)"
    ),
    Doc(
      s"${ServiceConfig.EnvPrefix}AUTH_CURSOR_SECRET",
      "random per process",
      "optional",
      "cursor HMAC secret (Secrets Manager)"
    ),
    Doc(
      s"${ServiceConfig.EnvPrefix}<KEY>",
      "see ServiceConfig",
      "optional",
      "any other service policy value, for example SLOW_DEADLINE_MILLIS"
    )
  )

  private def bad(name: String, why: String): Nothing =
    // Names the variable, never its value.
    throw new IllegalArgumentException(s"Environment variable $name $why")

  private def present(env: Map[String, String], name: String): Option[String] =
    env.get(name).map(_.trim).filter(_.nonEmpty)

  private def required(env: Map[String, String], name: String): String =
    present(env, name).getOrElse(bad(name, "is required"))

  private def matching(name: String, value: String, pattern: String): String =
    if value.matches(pattern) then value else bad(name, "has an invalid format")

  private def number(
      env: Map[String, String],
      name: String,
      default: Int,
      min: Int,
      max: Int
  ): Int =
    present(env, name).fold(default) { text =>
      val n = text.toIntOption.getOrElse(bad(name, "is not a whole number"))
      if n < min || n > max then bad(name, s"must be from $min to $max")
      n
    }

  def parseRole(text: String): Option[RuntimeRole] =
    RuntimeRole.values.find(_.wire == text)

  /** Pure over its arguments. `roleArg` is the first program argument. */
  def parse(
      env: Map[String, String],
      roleArg: Option[String] = None
  ): AwsSettings =
    val region = matching(EnvRegion, required(env, EnvRegion), regionPattern)
    if region != AllowedRegion then bad(EnvRegion, s"must be $AllowedRegion")
    val prefix =
      matching(EnvTablePrefix, required(env, EnvTablePrefix), prefixPattern)
    val dataBucket =
      matching(EnvDataBucket, required(env, EnvDataBucket), bucketPattern)
    val vectorBucket =
      matching(EnvVectorBucket, required(env, EnvVectorBucket), bucketPattern)
    val dim = number(
      env,
      EnvVectorDim,
      DefaultVectorDim,
      1,
      Int.MaxValue
    )
    if dim != DefaultVectorDim then
      bad(EnvVectorDim, s"must be $DefaultVectorDim")
    val bedrockEnabled = present(env, EnvBedrock) match
      case None | Some("off") => false
      case Some("on")         => true
      case Some(_)            => bad(EnvBedrock, "must be on or off")
    val host =
      matching(EnvNeptuneHost, required(env, EnvNeptuneHost), hostPattern)
    val answerModel = present(env, EnvAnswerModel) match
      case None | Some("stub") => AnswerModelKind.Stub
      case Some("bedrock")     => AnswerModelKind.Bedrock
      case Some(_)             => bad(EnvAnswerModel, "must be stub or bedrock")
    val modelId = present(env, EnvBedrockModelId).map(
      matching(EnvBedrockModelId, _, modelIdPattern)
    )
    if answerModel == AnswerModelKind.Bedrock then
      if modelId.isEmpty then
        bad(EnvBedrockModelId, "is required when the answer model is bedrock")
      if !bedrockEnabled then
        bad(EnvBedrock, "must be on when the answer model is bedrock")
    val maxCalls = number(
      env,
      EnvBedrockMaxCalls,
      DefaultMaxCalls,
      1,
      MaxCallsCeiling
    )
    val graphRun =
      matching(EnvGraphRunId, required(env, EnvGraphRunId), runIdPattern)
    val exportRun = matching(
      EnvExportRunId,
      required(env, EnvExportRunId),
      exportRunIdPattern
    )
    val queryRun = present(env, EnvQueryRunId)
      .map(matching(EnvQueryRunId, _, runIdPattern))
      .getOrElse("run:v1:" + java.util.UUID.randomUUID().toString)
    val role = roleArg
      .map(a =>
        parseRole(a).getOrElse(
          throw new IllegalArgumentException(
            "The runtime role argument must be fast, slow or both"
          )
        )
      )
      .orElse(
        present(env, EnvRole).map(t =>
          parseRole(t).getOrElse(bad(EnvRole, "must be fast, slow or both"))
        )
      )
      .getOrElse(RuntimeRole.Both)
    val bind = matching(
      EnvBindHost,
      present(env, EnvBindHost).getOrElse(DefaultBindHost),
      hostPattern
    )
    val fastPort = number(env, EnvFastPort, DefaultFastPort, 0, 65535)
    val slowPort = number(env, EnvSlowPort, DefaultSlowPort, 0, 65535)
    if fastPort != 0 && fastPort == slowPort && role == RuntimeRole.Both then
      bad(EnvSlowPort, "must differ from the fast port")
    AwsSettings(
      region,
      prefix,
      dataBucket,
      vectorBucket,
      bedrockEnabled,
      host,
      number(env, EnvNeptunePort, DefaultNeptunePort, 1, 65535),
      answerModel,
      modelId,
      maxCalls,
      graphRun,
      exportRun,
      queryRun,
      role,
      bind,
      fastPort,
      slowPort,
      ServiceConfig.fromEnvironment(env)
    )

  /** One line per setting, no value. For `--help`. */
  def helpText: String =
    Docs
      .map(d =>
        s"  ${d.name} [default: ${d.default}; ${d.required}] ${d.meaning}"
      )
      .mkString("\n")

  /** The non-secret settings as one line each, for `--check-config`. */
  def summary(s: AwsSettings): Vector[String] = Vector(
    s"region=${s.region}",
    s"tablePrefix=${s.tablePrefix}",
    s"answerModel=${s.answerModel.wire}",
    s"bedrockEnabled=${s.bedrockEnabled}",
    s"bedrockMaxCalls=${s.bedrockMaxCalls}",
    s"role=${s.role.wire}",
    s"fastPort=${s.fastPort}",
    s"slowPort=${s.slowPort}"
  )
