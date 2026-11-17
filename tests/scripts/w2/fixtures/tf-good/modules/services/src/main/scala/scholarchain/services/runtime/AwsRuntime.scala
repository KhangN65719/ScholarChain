package scholarchain.services.runtime

// Fixture for tests/scripts/w2/tfcheck.py. Not compiled.
object AwsRuntime:
  val EnvRegion = "AWS_REGION"
  val EnvTablePrefix = "SCHOLARCHAIN_TABLE_PREFIX"
  val EnvDataBucket = "SCHOLARCHAIN_DATA_BUCKET"
  val EnvVectorBucket = "SCHOLARCHAIN_VECTOR_BUCKET"
  val EnvNeptuneHost = "SCHOLARCHAIN_NEPTUNE_HOST"
  val EnvGraphRunId = "SCHOLARCHAIN_GRAPH_RUN_ID"
  val EnvExportRunId = "SCHOLARCHAIN_EXPORT_RUN_ID"
  val EnvAnswerModel = "SCHOLARCHAIN_ANSWER_MODEL"
  val EnvBedrockMaxCalls = "SCHOLARCHAIN_BEDROCK_MAX_CALLS"
  val EnvRole = "SCHOLARCHAIN_RUNTIME_ROLE"
  val EnvBedrock = "SCHOLARCHAIN_BEDROCK"
  val ServicePrefix = "SCHOLARCHAIN_SERVICES_"

  def main(args: Array[String]): Unit = ()
