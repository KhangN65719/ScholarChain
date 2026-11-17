package scholarchain.rag

import scholarchain.core.EvidenceType
import scholarchain.storage.{DynamoTable, Embedder, HashEmbedder, Item}

/** WO-5: the retrieval mode switch and the per-stage timings. Each mode runs
  * only its retrievers, `Fused` is the behavior from before modes existed, a
  * skipped stage is `Skipped` and never zero milliseconds.
  */
class RetrievalModeSuite extends munit.FunSuite:

  private final class CountingEmbedder(inner: Embedder) extends Embedder:
    var calls = 0
    val modelVersion: String = inner.modelVersion
    val dimension: Int = inner.dimension
    def embed(text: String): scala.Vector[Float] =
      calls += 1
      inner.embed(text)

  private final class CountingTable(inner: DynamoTable) extends DynamoTable:
    var reads = 0
    def spec = inner.spec
    def putIfAbsent(item: Item): Unit = inner.putIfAbsent(item)
    def putIfVersion(item: Item, expectedVersion: Long): Unit =
      inner.putIfVersion(item, expectedVersion)
    def get(pk: String, sk: String): Option[Item] =
      reads += 1
      inner.get(pk, sk)
    def query(
        pk: String,
        skPrefix: Option[String],
        limit: Int
    ): scala.Vector[Item] =
      reads += 1
      inner.query(pk, skPrefix, limit)
    def queryIndex(
        index: String,
        pk: String,
        skPrefix: Option[String],
        limit: Int
    ): scala.Vector[Item] =
      reads += 1
      inner.queryIndex(index, pk, skPrefix, limit)

  /** Every read advances one millisecond, so a timed stage is exactly 1 ms. */
  private final class SteppingClock extends StageClock:
    private var t = 0L
    def nanos(): Long =
      t += 1000000L
      t

  private final class Rig(semantic: Boolean = true):
    val world: DemoWorld = DemoWorld.build(
      semantic = semantic,
      config = Some(
        WorkflowConfig(
          DemoWorld.GraphRunId,
          DemoWorld.QueryRunId,
          stageClock = new SteppingClock
        )
      )
    )
    val embedder = new CountingEmbedder(new HashEmbedder)
    val table = new CountingTable(world.cache)
    private val ports = world.ports.copy(
      metricCache = table,
      semantic =
        if semantic then
          Some(
            new SemanticRetrieval(
              embedder,
              world.vectorBucket,
              world.objectStore
            )
          )
        else None
    )
    val workflow = new CollaborationWorkflow(ports, world.config)

    def ask(question: String, mode: RetrievalMode): WorkflowResult =
      workflow.answer(question, Session("mode-session", 0), mode)

  private val question =
    "How many distinct coauthors does Ada Quillfeather have in 2000-2009?"
  private val emptyWindow =
    "How many distinct coauthors does Ada Quillfeather have in 1990-1999?"

  private def evidenceTypes(r: WorkflowResult): Set[EvidenceType] =
    r.response.evidence.items.map(_.evidenceType).toSet

  private def stageNames(r: WorkflowResult): scala.Vector[String] =
    r.audit.payload("stages").arr.toVector.map(_("name").str)

  test("Graph: graph evidence with IDs, no vector retriever ran") {
    val rig = new Rig
    val r = rig.ask(question, RetrievalMode.Graph)
    assert(!r.abstained, r.response.answer)
    assertEquals(r.mode, RetrievalMode.Graph)
    assertEquals(r.verdict, Some(VerificationVerdict.Verified))
    assert(r.response.evidence.items.nonEmpty)
    assert(!evidenceTypes(r).contains(EvidenceType.TextChunk))
    assert(evidenceTypes(r).contains(EvidenceType.Metric))
    assertEquals(rig.embedder.calls, 0)
    assert(rig.table.reads > 0)
    val known = r.response.evidence.items.map(_.evidenceId).toSet
    assert(r.response.claims.nonEmpty)
    assert(r.response.claims.forall(_.evidenceIds.forall(known.contains)))
    assertEquals(r.audit.payload("retrievalMode").str, "graph")
  }

  test("Vector: semantic evidence with IDs, no graph read of any kind") {
    val rig = new Rig
    val r = rig.ask(question, RetrievalMode.Vector)
    assert(!r.abstained, r.response.answer)
    assertEquals(r.mode, RetrievalMode.Vector)
    assert(rig.embedder.calls > 0)
    assertEquals(rig.table.reads, 0)
    assertEquals(rig.world.neptune.queries, scala.Vector.empty)
    val items = r.response.evidence.items
    val chunkIds =
      items.filter(_.evidenceType == EvidenceType.TextChunk).map(_.evidenceId)
    assert(chunkIds.nonEmpty, "vector mode returned no chunk evidence")
    assert(
      items.forall(i =>
        i.evidenceType == EvidenceType.TextChunk ||
          i.evidenceType == EvidenceType.IdentityLink
      ),
      "vector mode returned graph evidence"
    )
    assert(r.response.claims.nonEmpty)
    assert(
      r.response.claims.forall(_.evidenceIds.forall(chunkIds.contains)),
      "a vector claim cites something other than semantic context"
    )
    assert(
      r.response.evidence.uncertainty
        .exists(_.contains("Graph evidence was not retrieved")),
      r.response.evidence.uncertainty.toString
    )
    assertEquals(r.audit.payload("retrievalMode").str, "vector")
  }

  test("Fused: output is identical with and without the mode argument") {
    val a = new Rig().workflow
    val b = new Rig().workflow
    val s = Session("same", 0)
    val plain = a.answer(question, s)
    val explicit = b.answer(question, s, RetrievalMode.Fused)
    assertEquals(plain.response, explicit.response)
    assertEquals(
      ujson.write(plain.audit.payload),
      ujson.write(explicit.audit.payload)
    )
    assertEquals(plain.flags, explicit.flags)
    assertEquals(plain.mode, RetrievalMode.Fused)
    assert(plain.audit.payload.obj.get("retrievalMode").isEmpty)
    assertEquals(
      stageNames(plain),
      scala.Vector(
        "plan",
        "resolve",
        "retrieve",
        "semantic",
        "evidence",
        "synthesis",
        "verify"
      )
    )
    val types = evidenceTypes(plain)
    assert(types.contains(EvidenceType.Metric))
    assert(types.contains(EvidenceType.TextChunk))
  }

  test("Graph with no stored evidence: the existing insufficient result") {
    val r = new Rig().ask(emptyWindow, RetrievalMode.Graph)
    assert(r.abstained)
    assertEquals(r.abstentionReason, Some("no-deterministic-evidence"))
    assertEquals(r.response.claims, scala.Vector.empty)
  }

  test("Vector with no semantic index: the existing insufficient result") {
    val rig = new Rig(semantic = false)
    val r = rig.ask(question, RetrievalMode.Vector)
    assert(r.abstained)
    assertEquals(r.abstentionReason, Some("no-deterministic-evidence"))
    assertEquals(r.response.claims, scala.Vector.empty)
    assertEquals(rig.world.neptune.queries, scala.Vector.empty)
  }

  test("timings: Fused runs every stage and each is non-negative") {
    val r = new Rig().ask(question, RetrievalMode.Fused)
    PipelineStage.values.foreach { stage =>
      assertEquals(r.timings.of(stage), StageTiming.Ran(1L), stage.wire)
      assert(r.timings.millis(stage).exists(_ >= 0L))
    }
  }

  test("timings: Graph records the vector retriever as skipped, not 0 ms") {
    val t = new Rig().ask(question, RetrievalMode.Graph).timings
    assertEquals(t.of(PipelineStage.VectorRetrieval), StageTiming.Skipped)
    assertEquals(t.millis(PipelineStage.VectorRetrieval), None)
    assertEquals(t.of(PipelineStage.GraphRetrieval), StageTiming.Ran(1L))
    Seq(
      PipelineStage.Planning,
      PipelineStage.Fusion,
      PipelineStage.Synthesis,
      PipelineStage.Verification
    ).foreach(s => assert(t.ran(s), s.wire))
  }

  test("timings: Vector records the graph retriever as skipped, not 0 ms") {
    val t = new Rig().ask(question, RetrievalMode.Vector).timings
    assertEquals(t.of(PipelineStage.GraphRetrieval), StageTiming.Skipped)
    assertEquals(t.of(PipelineStage.VectorRetrieval), StageTiming.Ran(1L))
    Seq(
      PipelineStage.Planning,
      PipelineStage.Fusion,
      PipelineStage.Synthesis,
      PipelineStage.Verification
    ).foreach(s => assert(t.ran(s), s.wire))
  }

  test("timings: an abstention before synthesis skips the later stages") {
    val t = new Rig().ask(emptyWindow, RetrievalMode.Fused).timings
    assert(t.ran(PipelineStage.Planning))
    assert(t.ran(PipelineStage.GraphRetrieval))
    assert(t.ran(PipelineStage.Fusion))
    assertEquals(t.of(PipelineStage.Synthesis), StageTiming.Skipped)
    assertEquals(t.of(PipelineStage.Verification), StageTiming.Skipped)
  }

  test("timings: an invalid question runs no stage") {
    val t = new Rig().ask("", RetrievalMode.Fused).timings
    PipelineStage.values.foreach(s =>
      assertEquals(t.of(s), StageTiming.Skipped, s.wire)
    )
  }

  test("timings: log fields and JSON name every stage, skipped in words") {
    val t = new Rig().ask(question, RetrievalMode.Graph).timings
    assertEquals(
      t.logFields.map(_._1),
      List(
        "planningMs",
        "graphRetrievalMs",
        "vectorRetrievalMs",
        "fusionMs",
        "synthesisMs",
        "verificationMs"
      )
    )
    assertEquals(t.logFields.toMap.apply("vectorRetrievalMs"), "skipped")
    assertEquals(t.logFields.toMap.apply("planningMs"), "1")
    assertEquals(t.toJson("vector-retrieval").str, "skipped")
    assertEquals(t.toJson("graph-retrieval").num, 1.0)
  }

  test("RetrievalMode wire names and parsing") {
    assertEquals(RetrievalMode.wires, List("graph", "vector", "fused"))
    assertEquals(RetrievalMode.Default, RetrievalMode.Fused)
    assertEquals(RetrievalMode.fromWire("graph"), Some(RetrievalMode.Graph))
    assertEquals(RetrievalMode.fromWire("vector"), Some(RetrievalMode.Vector))
    assertEquals(RetrievalMode.fromWire("fused"), Some(RetrievalMode.Fused))
    assertEquals(RetrievalMode.fromWire("GRAPH"), None)
    assertEquals(RetrievalMode.fromWire(""), None)
    assertEquals(RetrievalMode.fromWire("hybrid"), None)
  }
