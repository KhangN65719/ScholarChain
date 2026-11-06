package scholarchain.core

import java.nio.file.{Files, Path}
import munit.FunSuite

class ConfigSuite extends FunSuite:
  private val example =
    Path.of(getClass.getResource("/scholarchain-local.v1.yaml").toURI)
  private val yaml = Files.readString(example)

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
      "SCHOLARCHAIN_BRIDGE_SAMPLE_SIZE" -> "no",
      "SCHOLARCHAIN_GRAPH_END_YEAR" -> "1999",
      "SCHOLARCHAIN_MATCH_ACCEPT_THRESHOLD" -> "NaN",
      "SCHOLARCHAIN_MATCH_REJECT_THRESHOLD" -> "0.99",
      "SCHOLARCHAIN_GRAPH_ENDPOINT" -> "https://name:placeholder@example.invalid",
      "SCHOLARCHAIN_VECTOR_ENDPOINT" -> "https://example.invalid/?token=placeholder",
      "SCHOLARCHAIN_RAW_BUCKET" -> " "
    ).foreach { setting =>
      intercept[Exception](ConfigLoader.parse(yaml, Map(setting)))
    }

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
