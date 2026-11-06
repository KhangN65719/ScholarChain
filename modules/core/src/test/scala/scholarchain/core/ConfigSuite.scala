package scholarchain.core

import java.nio.file.{Files, Path}
import munit.FunSuite

class ConfigSuite extends FunSuite:
  private val example =
    Path.of(getClass.getResource("/scholarchain-local.v1.yaml").toURI)
  private val yaml = Files.readString(example)

  private def rejects(expected: String)(action: => Any): Unit =
    val text = Rejection.textOf(action)
    assert(
      text.contains(expected),
      s"Expected a rejection mentioning '$expected' but got: '$text'"
    )

  test("load versioned local YAML with explicit environment precedence"):
    val config = ConfigLoader.load(
      example,
      Map(
        "SCHOLARCHAIN_RAW_BUCKET" -> "run-input-bucket",
        "SCHOLARCHAIN_GRAPH_START_YEAR" -> "2010",
        "SCHOLARCHAIN_MATCH_ACCEPT_THRESHOLD" -> "0.92",
        "SCHOLARCHAIN_BRIDGE_RANDOM_SEED" -> Long.MaxValue.toString,
        "OTHER_ENVIRONMENT_VARIABLE" -> "ignored"
      )
    )
    assertEquals(config.schemaVersion, 1)
    assertEquals(config.rawBucket, "run-input-bucket")
    assertEquals(config.curatedBucket, "local-curated-example")
    assertEquals(config.graphWindow, YearWindow(2010, 2025))
    assertEquals(config.matchAcceptThreshold, 0.92)
    assertEquals(config.bridgeRandomSeed, Long.MaxValue)

  test(
    "reject invalid environment numbers, windows, thresholds and secret-bearing endpoints"
  ):
    Vector(
      (
        "SCHOLARCHAIN_BRIDGE_SAMPLE_SIZE",
        "no",
        "Invalid SCHOLARCHAIN_BRIDGE_SAMPLE_SIZE"
      ),
      (
        "SCHOLARCHAIN_GRAPH_END_YEAR",
        "1999",
        "Time window start year must not exceed end year"
      ),
      (
        "SCHOLARCHAIN_MATCH_ACCEPT_THRESHOLD",
        "NaN",
        "Invalid SCHOLARCHAIN_MATCH_ACCEPT_THRESHOLD"
      ),
      (
        "SCHOLARCHAIN_MATCH_REJECT_THRESHOLD",
        "0.99",
        "Reject threshold must be below accept threshold"
      ),
      (
        "SCHOLARCHAIN_GRAPH_ENDPOINT",
        "https://name:placeholder@example.invalid",
        "Endpoints require an HTTP(S) host"
      ),
      (
        "SCHOLARCHAIN_VECTOR_ENDPOINT",
        "https://example.invalid/?token=placeholder",
        "Endpoints require an HTTP(S) host"
      ),
      ("SCHOLARCHAIN_RAW_BUCKET", " ", "Raw bucket must be nonblank")
    ).foreach { case (key, value, expected) =>
      rejects(expected)(ConfigLoader.parse(yaml, Map(key -> value)))
    }
    // The NaN case above is caught by the loader's own `.filter(_.isFinite)`
    // guard, which names the override. A finite out-of-range value instead
    // reaches ContractValidation.confidence, so the two are distinguishable
    // and the guard cannot vanish unnoticed.
    rejects("Confidence must be finite and in [0, 1]")(
      ConfigLoader
        .parse(yaml, Map("SCHOLARCHAIN_MATCH_ACCEPT_THRESHOLD" -> "1.5"))
    )

  test("reject unknown configuration keys and missing or unsupported versions"):
    val unknown = ujson.read(yaml)
    unknown("unknown") = ujson.Str("value")
    intercept[Exception](ConfigLoader.parse(ujson.write(unknown), Map.empty))
    val noVersion = ujson.read(yaml)
    noVersion.obj.remove("schemaVersion")
    intercept[Exception](ConfigLoader.parse(ujson.write(noVersion), Map.empty))
    val wrongVersion = ujson.read(yaml)
    wrongVersion("schemaVersion") = ujson.Num(2)
    intercept[Exception](
      ConfigLoader.parse(ujson.write(wrongVersion), Map.empty)
    )

  test("reject a configuration file missing a required non-version field"):
    val incomplete = ujson.read(yaml)
    incomplete.obj.remove("rawBucket")
    rejects("rawBucket")(
      ConfigLoader.parse(ujson.write(incomplete), Map.empty)
    )
    // An override may supply the absent setting, as the module README states.
    val supplied = ConfigLoader.parse(
      ujson.write(incomplete),
      Map("SCHOLARCHAIN_RAW_BUCKET" -> "override-bucket")
    )
    assertEquals(supplied.rawBucket, "override-bucket")

  test("configuration invariants reject each malformed field distinctly"):
    val base = ConfigLoader.parse(yaml, Map.empty)
    assertEquals(base.graphWindow, YearWindow(2000, 2025))
    rejects("Unsupported configuration schema version")(
      base.copy(schemaVersion = 2)
    )
    rejects("Raw bucket must be nonblank")(base.copy(rawBucket = " "))
    rejects("Curated bucket must be nonblank")(base.copy(curatedBucket = ""))
    rejects("Answer model ID must be nonblank")(base.copy(answerModelId = " "))
    rejects("Embedding model ID must be nonblank")(
      base.copy(embeddingModelId = " ")
    )
    rejects("Bridge algorithm must be nonblank")(
      base.copy(bridgeAlgorithm = " ")
    )
    rejects("AWS account ID must contain 12 digits")(
      base.copy(awsAccountId = "12345")
    )
    rejects("Endpoints require an HTTP(S) host")(
      base.copy(graphEndpoint = "ftp://example.invalid")
    )
    rejects("Endpoints require an HTTP(S) host")(
      base.copy(vectorEndpoint = "https://example.invalid/#fragment")
    )
    rejects("Confidence must be finite and in [0, 1]")(
      base.copy(matchAcceptThreshold = 1.5)
    )
    rejects("Reject threshold must be below accept threshold")(
      base.copy(matchRejectThreshold = 0.95)
    )
    rejects("Bridge sample size must be positive")(
      base.copy(bridgeSampleSize = 0)
    )
    rejects("Time window start year must not exceed end year")(
      base.copy(graphEndYear = 1999)
    )
