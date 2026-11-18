package scholarchain.evaluation.runtime

import java.nio.file.Paths
import munit.FunSuite
import scholarchain.rag.{CollaborationWorkflow, RetrievalMode, Session}

class LaptopPortsSuite extends FunSuite:
  private val question = "Who is Ada Quillfeather between 2000 and 2009?"

  private def guard = new CostGuard(CostLimits(100, 100, 1.0), Rates.Zero)

  test("a run id is a run:v1 uuid4 and the same label gives the same id") {
    val a = LaptopPorts.runIdFor("one")
    assert(
      a.matches(
        "run:v1:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
      ),
      a
    )
    assertEquals(a, LaptopPorts.runIdFor("one"))
    assertNotEquals(a, LaptopPorts.runIdFor("two"))
  }

  test(
    "the Titan client settings turn the SDK retry off and keep the deadlines"
  ) {
    val settings = LaptopPorts.titanOverride
    assertEquals(settings.retryPolicy().get().numRetries().toInt, 0)
    assertEquals(
      settings.apiCallAttemptTimeout().get(),
      scholarchain.storage.AwsTimeouts.BedrockAttempt
    )
    assertEquals(
      settings.apiCallTimeout().get(),
      scholarchain.storage.AwsTimeouts.BedrockCall
    )
  }

  test("any region other than us-east-2 is refused") {
    assertEquals(LaptopPorts.requireRegion("us-east-2"), "us-east-2")
    intercept[IllegalArgumentException](LaptopPorts.requireRegion("us-west-2"))
    intercept[IllegalArgumentException](LaptopPorts.requireRegion(""))
    intercept[IllegalArgumentException](
      LaptopPorts.LiveSettings(
        Paths.get("/nowhere"),
        "eu-west-1",
        "d",
        "v",
        None
      )
    )
  }

  test("vector retrieval needs both bucket names, which have no default") {
    intercept[IllegalArgumentException](
      LaptopPorts.LiveSettings(
        Paths.get("/nowhere"),
        "us-east-2",
        "",
        "v",
        None
      )
    )
    intercept[IllegalArgumentException](
      LaptopPorts.LiveSettings(
        Paths.get("/nowhere"),
        "us-east-2",
        "d",
        " ",
        None
      )
    )
    val graphOnly = LaptopPorts.LiveSettings(
      Paths.get("/nowhere"),
      "us-east-2",
      "",
      "",
      None,
      useVectors = false
    )
    assert(!graphOnly.useVectors)
  }

  test("the offline wiring answers through the guarded stub and embedder") {
    val g = guard
    val w = LaptopPorts.offline(g)
    val workflow = new CollaborationWorkflow(w.ports, w.config)
    val result =
      workflow.answer(question, Session("lp", 1), RetrievalMode.Fused)
    assert(!result.abstained, result.abstentionReason.toString)
    assert(g.haikuCallCount >= 1, "synthesis went through the guarded model")
    assert(g.titanCallCount >= 1, "the query text went through the embedder")
    assertEquals(g.usd, 0.0)
    assertEquals(w.description("mode").str, "offline")
  }

  test("graph mode embeds nothing and reads no vector index") {
    val g = guard
    val w = LaptopPorts.offline(g)
    val workflow = new CollaborationWorkflow(w.ports, w.config)
    workflow.answer(question, Session("lp", 2), RetrievalMode.Graph)
    assertEquals(g.titanCallCount, 0)
    assertEquals(w.recorder.get.queryCount, 0L)
  }

  test("the recorder returns the hits of the workflow's vector queries") {
    val w = LaptopPorts.offline(guard)
    val workflow = new CollaborationWorkflow(w.ports, w.config)
    workflow.answer(question, Session("lp", 3), RetrievalMode.Vector)
    val recorded = w.recorder.get.drain()
    assert(recorded.nonEmpty)
    assert(recorded.forall(_.topK == 2), "the deployed workflow asks topK 2")
    assert(recorded.exists(_.hits.nonEmpty))
    assert(w.recorder.get.drain().isEmpty, "drain empties the buffer")
  }

  test("a direct search shares one embed across indexes and K values") {
    val g = guard
    val w = LaptopPorts.offline(g)
    val before = g.titanCallCount
    val retrieval = w.direct.get()
    retrieval.search("researcher-profile-v1", "Ada Quillfeather", 5)
    retrieval.search("researcher-profile-v1", "Ada Quillfeather", 10)
    retrieval.search("collaboration-community-v1", "Ada Quillfeather", 5)
    assertEquals(g.titanCallCount - before, 1)
    assertEquals(w.directBucket.get.queryCount, 3L)
  }

  test("the Titan cap stops a workflow call before an embed is made") {
    val g = new CostGuard(CostLimits(0, 100, 1.0), Rates.Zero)
    val w = LaptopPorts.offline(g)
    val workflow = new CollaborationWorkflow(w.ports, w.config)
    intercept[CostStopped](
      workflow.answer(question, Session("lp", 4), RetrievalMode.Fused)
    )
    assertEquals(g.stopReason, Some("titan-call-cap"))
  }

  test("the Haiku cap turns synthesis into a refusal, never a call") {
    val g = new CostGuard(CostLimits(100, 0, 1.0), Rates.Zero)
    val w = LaptopPorts.offline(g)
    val workflow = new CollaborationWorkflow(w.ports, w.config)
    val result =
      workflow.answer(question, Session("lp", 5), RetrievalMode.Fused)
    assert(result.abstained)
    assertEquals(result.abstentionReason, Some("synthesis-failed"))
    assertEquals(g.stopReason, Some("haiku-call-cap"))
    assertEquals(g.haikuCallCount, 0)
  }

  test("the description holds no bucket name and no secret") {
    val text = ujson.write(LaptopPorts.offline(guard).description)
    assert(!text.toLowerCase.contains("bucket"))
    assert(!text.toLowerCase.contains("secret"))
  }
