package scholarchain.core

import java.net.URI
import java.nio.file.{Files, Path}
import upickle.default.{ReadWriter, read}

final case class ScholarChainConfig(
    schemaVersion: Int,
    rawBucket: String,
    curatedBucket: String,
    graphEndpoint: String,
    vectorEndpoint: String,
    awsAccountId: String,
    answerModelId: String,
    embeddingModelId: String,
    matchAcceptThreshold: Double,
    matchRejectThreshold: Double,
    graphStartYear: Int,
    graphEndYear: Int,
    bridgeAlgorithm: String,
    bridgeSampleSize: Int,
    bridgeRandomSeed: Long
) derives ReadWriter:
  require(
    schemaVersion == ContractSchema.currentVersionNumber,
    "Unsupported configuration schema version"
  )
  Vector(
    rawBucket -> "Raw bucket",
    curatedBucket -> "Curated bucket",
    answerModelId -> "Answer model ID",
    embeddingModelId -> "Embedding model ID",
    bridgeAlgorithm -> "Bridge algorithm"
  ).foreach { case (value, field) => ContractValidation.present(value, field) }
  require(
    awsAccountId.matches("[0-9]{12}"),
    "AWS account ID must contain 12 digits"
  )
  Vector(graphEndpoint, vectorEndpoint).foreach { value =>
    val endpoint = URI.create(value)
    require(
      Set("http", "https").contains(
        endpoint.getScheme
      ) && endpoint.getHost != null &&
        endpoint.getUserInfo == null && endpoint.getRawQuery == null && endpoint.getRawFragment == null,
      "Endpoints require an HTTP(S) host without credentials, query, or fragment"
    )
  }
  ContractValidation.confidence(matchAcceptThreshold)
  ContractValidation.confidence(matchRejectThreshold)
  require(
    matchRejectThreshold < matchAcceptThreshold,
    "Reject threshold must be below accept threshold"
  )
  require(bridgeSampleSize > 0, "Bridge sample size must be positive")

  val graphWindow: YearWindow = YearWindow(graphStartYear, graphEndYear)

object ConfigLoader:
  private val prefix = "SCHOLARCHAIN_"

  private val stringOverrides = Map(
    "RAW_BUCKET" -> "rawBucket",
    "CURATED_BUCKET" -> "curatedBucket",
    "GRAPH_ENDPOINT" -> "graphEndpoint",
    "VECTOR_ENDPOINT" -> "vectorEndpoint",
    "AWS_ACCOUNT_ID" -> "awsAccountId",
    "ANSWER_MODEL_ID" -> "answerModelId",
    "EMBEDDING_MODEL_ID" -> "embeddingModelId",
    "BRIDGE_ALGORITHM" -> "bridgeAlgorithm"
  )

  private val numericOverrides = Map(
    "MATCH_ACCEPT_THRESHOLD" -> (
      "matchAcceptThreshold",
      (value: String) =>
        value.toDoubleOption.filter(_.isFinite).map(ujson.Num(_))
    ),
    "MATCH_REJECT_THRESHOLD" -> (
      "matchRejectThreshold",
      (value: String) =>
        value.toDoubleOption.filter(_.isFinite).map(ujson.Num(_))
    ),
    "GRAPH_START_YEAR" -> ("graphStartYear", intValue),
    "GRAPH_END_YEAR" -> ("graphEndYear", intValue),
    "BRIDGE_SAMPLE_SIZE" -> ("bridgeSampleSize", intValue),
    "BRIDGE_RANDOM_SEED" -> (
      "bridgeRandomSeed",
      (value: String) => value.toLongOption.map(n => ujson.Str(n.toString))
    )
  )

  private val fileKeys =
    stringOverrides.values.toSet ++ numericOverrides.values.map(
      _._1
    ) + "schemaVersion"

  private def intValue(value: String): Option[ujson.Value] =
    value.toIntOption.map(n => ujson.Num(n.toDouble))

  /** Reads the JSON-compatible subset of YAML 1.2. Explicit environment values
    * take precedence.
    */
  def load(
      path: Path,
      environment: Map[String, String] = sys.env
  ): ScholarChainConfig =
    parse(Files.readString(path), environment)

  def parse(
      yaml: String,
      environment: Map[String, String]
  ): ScholarChainConfig =
    val document = ujson.read(yaml)
    val fields = document.obj
    require(
      fields.keySet.subsetOf(fileKeys),
      "Configuration contains an unknown key"
    )
    val version = fields.getOrElse(
      "schemaVersion",
      throw new IllegalArgumentException("Configuration requires schemaVersion")
    )
    require(
      version == ujson.Num(ContractSchema.currentVersionNumber),
      "Unsupported configuration schema version"
    )
    stringOverrides.foreach { case (key, field) =>
      environment
        .get(prefix + key)
        .foreach(value => fields(field) = ujson.Str(value))
    }
    numericOverrides.foreach { case (key, (field, convert)) =>
      environment.get(prefix + key).foreach { value =>
        fields(field) = convert(value).getOrElse(
          throw new IllegalArgumentException(s"Invalid ${prefix + key}")
        )
      }
    }
    // `document.obj` above already rejects a non-object document, so the
    // reader cannot reach upickle's null path and return null here.
    read[ScholarChainConfig](document)
