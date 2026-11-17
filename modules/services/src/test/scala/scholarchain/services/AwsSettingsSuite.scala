package scholarchain.services

import munit.FunSuite
import scholarchain.rag.DemoWorld
import scholarchain.services.runtime.*

object AwsTestEnv:
  val QueryToken = "aws-test-query-token-0001"
  val ReadToken = "aws-test-read-token-00002"
  val ModelId = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
  val ExportRunId = "run-v1-0f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a"

  val base: Map[String, String] = Map(
    "AWS_REGION" -> "us-east-2",
    "SCHOLARCHAIN_TABLE_PREFIX" -> "demo",
    "SCHOLARCHAIN_DATA_BUCKET" -> "demo-data-bucket",
    "SCHOLARCHAIN_VECTOR_BUCKET" -> "demo-vector-bucket",
    "SCHOLARCHAIN_NEPTUNE_HOST" -> "neptune.example.internal",
    "SCHOLARCHAIN_GRAPH_RUN_ID" -> DemoWorld.GraphRunId,
    "SCHOLARCHAIN_EXPORT_RUN_ID" -> ExportRunId,
    "SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN" -> QueryToken,
    "SCHOLARCHAIN_SERVICES_AUTH_READ_TOKEN" -> ReadToken
  )

  /** Loopback and ephemeral ports, for a runtime that a test starts. */
  val local: Map[String, String] = base ++ Map(
    "SCHOLARCHAIN_BIND_HOST" -> "127.0.0.1",
    "SCHOLARCHAIN_FAST_PORT" -> "0",
    "SCHOLARCHAIN_SLOW_PORT" -> "0"
  )

  val required: Vector[String] = Vector(
    "AWS_REGION",
    "SCHOLARCHAIN_TABLE_PREFIX",
    "SCHOLARCHAIN_DATA_BUCKET",
    "SCHOLARCHAIN_VECTOR_BUCKET",
    "SCHOLARCHAIN_NEPTUNE_HOST",
    "SCHOLARCHAIN_GRAPH_RUN_ID",
    "SCHOLARCHAIN_EXPORT_RUN_ID"
  )

class AwsSettingsSuite extends FunSuite:
  import AwsTestEnv.*

  private def refused(env: Map[String, String]): String =
    intercept[IllegalArgumentException](AwsSettings.parse(env)).getMessage

  test("defaults: stub model, cap 10, both services on 8080 and 8081") {
    val s = AwsSettings.parse(base)
    assertEquals(s.answerModel, AnswerModelKind.Stub)
    assertEquals(s.bedrockEnabled, false)
    assertEquals(s.bedrockMaxCalls, 10)
    assertEquals(s.role, RuntimeRole.Both)
    assertEquals(s.fastPort, 8080)
    assertEquals(s.slowPort, 8081)
    assertEquals(s.bindHost, "0.0.0.0")
    assertEquals(s.neptunePort, 8182)
    assertEquals(s.bedrockRequest, None)
    assert(s.queryRunId.startsWith("run:v1:"))
  }

  test("each required variable is named when missing or blank") {
    required.foreach { name =>
      assert(refused(base - name).contains(name), name)
      assert(refused(base + (name -> "  ")).contains(name), name)
    }
  }

  test("a malformed value names the variable and never echoes the value") {
    val bad = "Bad Value SECRETMARK"
    Vector(
      "AWS_REGION",
      "SCHOLARCHAIN_TABLE_PREFIX",
      "SCHOLARCHAIN_DATA_BUCKET",
      "SCHOLARCHAIN_VECTOR_BUCKET",
      "SCHOLARCHAIN_NEPTUNE_HOST",
      "SCHOLARCHAIN_GRAPH_RUN_ID",
      "SCHOLARCHAIN_EXPORT_RUN_ID",
      "SCHOLARCHAIN_BIND_HOST"
    ).foreach { name =>
      val message = refused(base + (name -> bad))
      assert(message.contains(name), name)
      assert(!message.contains("SECRETMARK"), name)
    }
  }

  test(
    "a valid export run slug is accepted and kept apart from the graph run"
  ) {
    val s = AwsSettings.parse(base)
    assertEquals(s.exportRunId, ExportRunId)
    assertEquals(s.graphRunId, DemoWorld.GraphRunId)
    assertNotEquals(s.exportRunId, s.graphRunId)
  }

  test("a missing, blank or colon-form export run id is refused by name") {
    val name = "SCHOLARCHAIN_EXPORT_RUN_ID"
    val colon = "run:v1:0f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a"
    Vector(None, Some(""), Some("   "), Some(colon), Some("run-v1-short"))
      .foreach { v =>
        val env = v.fold(base - name)(x => base + (name -> x))
        val message = refused(env)
        assert(message.contains(name), v.toString)
        assert(!message.contains(colon), v.toString)
      }
    // Never derived from the graph run, even when that one is valid.
    assert(refused(base - name).contains(name))
  }

  test("only us-east-2 is accepted as the Region") {
    assertEquals(
      AwsSettings.parse(base + ("AWS_REGION" -> "us-east-2")).region,
      "us-east-2"
    )
    Vector("us-east-1", "eu-west-1", "us-west-2").foreach { r =>
      val message = refused(base + ("AWS_REGION" -> r))
      assert(message.contains("AWS_REGION"), r)
      assert(message.contains("us-east-2"), r)
    }
  }

  test("the storage settings carry the export run id by name") {
    val storage = ReadModelSource.storageSettings(AwsSettings.parse(base))
    assertEquals(storage.exportRunId, ExportRunId)
    assertEquals(storage.graphRunId, DemoWorld.GraphRunId)
  }

  test("a missing or bad token is refused without echoing a token") {
    val noToken = refused(base - "SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN")
    assert(!noToken.contains(ReadToken))
    val same = refused(
      base + ("SCHOLARCHAIN_SERVICES_AUTH_READ_TOKEN" -> QueryToken)
    )
    assert(!same.contains(QueryToken))
  }

  test(
    "the bedrock answer model needs a model ID and SCHOLARCHAIN_BEDROCK on"
  ) {
    val wanted = base + ("SCHOLARCHAIN_ANSWER_MODEL" -> "bedrock")
    assert(
      refused(wanted + ("SCHOLARCHAIN_BEDROCK" -> "on"))
        .contains("SCHOLARCHAIN_BEDROCK_MODEL_ID")
    )
    assert(
      refused(wanted + ("SCHOLARCHAIN_BEDROCK_MODEL_ID" -> ModelId))
        .contains("SCHOLARCHAIN_BEDROCK")
    )
    assert(
      refused(
        wanted + ("SCHOLARCHAIN_BEDROCK_MODEL_ID" -> ModelId) +
          ("SCHOLARCHAIN_BEDROCK" -> "off")
      ).contains("must be on")
    )
  }

  test("bedrock settings are read: model ID, region and the call cap") {
    val s = AwsSettings.parse(
      base ++ Map(
        "SCHOLARCHAIN_ANSWER_MODEL" -> "bedrock",
        "SCHOLARCHAIN_BEDROCK" -> "on",
        "SCHOLARCHAIN_BEDROCK_MODEL_ID" -> ModelId
      )
    )
    assertEquals(s.answerModel, AnswerModelKind.Bedrock)
    assertEquals(
      s.bedrockRequest,
      Some(BedrockModelRequest("us-east-2", ModelId, 10))
    )
    val capped = AwsSettings.parse(
      base ++ Map(
        "SCHOLARCHAIN_ANSWER_MODEL" -> "bedrock",
        "SCHOLARCHAIN_BEDROCK" -> "on",
        "SCHOLARCHAIN_BEDROCK_MODEL_ID" -> ModelId,
        "SCHOLARCHAIN_BEDROCK_MAX_CALLS" -> "3"
      )
    )
    assertEquals(capped.bedrockMaxCalls, 3)
  }

  test("the call cap is bounded and the answer model is a closed set") {
    Vector("0", "101", "-1", "ten").foreach { v =>
      assert(
        refused(base + ("SCHOLARCHAIN_BEDROCK_MAX_CALLS" -> v))
          .contains("SCHOLARCHAIN_BEDROCK_MAX_CALLS"),
        v
      )
    }
    assert(
      refused(base + ("SCHOLARCHAIN_ANSWER_MODEL" -> "gpt"))
        .contains("SCHOLARCHAIN_ANSWER_MODEL")
    )
    assert(
      refused(base + ("SCHOLARCHAIN_BEDROCK" -> "maybe"))
        .contains("SCHOLARCHAIN_BEDROCK")
    )
  }

  test("the vector dimension must be 256") {
    assertEquals(
      AwsSettings.parse(base + ("SCHOLARCHAIN_VECTOR_DIM" -> "256")).region,
      "us-east-2"
    )
    assert(
      refused(base + ("SCHOLARCHAIN_VECTOR_DIM" -> "1024"))
        .contains("SCHOLARCHAIN_VECTOR_DIM")
    )
  }

  test("the role argument overrides the variable; both need distinct ports") {
    assertEquals(
      AwsSettings.parse(base + ("SCHOLARCHAIN_RUNTIME_ROLE" -> "slow")).role,
      RuntimeRole.Slow
    )
    assertEquals(
      AwsSettings
        .parse(base + ("SCHOLARCHAIN_RUNTIME_ROLE" -> "slow"), Some("fast"))
        .role,
      RuntimeRole.Fast
    )
    intercept[IllegalArgumentException](AwsSettings.parse(base, Some("all")))
    assert(
      refused(
        base ++ Map(
          "SCHOLARCHAIN_FAST_PORT" -> "9000",
          "SCHOLARCHAIN_SLOW_PORT" -> "9000"
        )
      ).contains("SCHOLARCHAIN_SLOW_PORT")
    )
  }

  test("the settings table documents every variable the parser reads") {
    val documented = AwsSettings.Docs.map(_.name).toSet
    Vector(
      AwsSettings.EnvRegion,
      AwsSettings.EnvTablePrefix,
      AwsSettings.EnvDataBucket,
      AwsSettings.EnvVectorBucket,
      AwsSettings.EnvVectorDim,
      AwsSettings.EnvBedrock,
      AwsSettings.EnvNeptuneHost,
      AwsSettings.EnvNeptunePort,
      AwsSettings.EnvAnswerModel,
      AwsSettings.EnvBedrockModelId,
      AwsSettings.EnvBedrockMaxCalls,
      AwsSettings.EnvGraphRunId,
      AwsSettings.EnvExportRunId,
      AwsSettings.EnvQueryRunId,
      AwsSettings.EnvRole,
      AwsSettings.EnvBindHost,
      AwsSettings.EnvFastPort,
      AwsSettings.EnvSlowPort
    ).foreach(n => assert(documented.contains(n), n))
  }
