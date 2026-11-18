package scholarchain.evaluation

import munit.FunSuite
import scholarchain.evaluation.benchmark.*
import scholarchain.rag.{DemoWorld, RetrievalMode, Session, WorkflowResult}

/** Runs every unanswerable template of the benchmark through the real
  * `CollaborationWorkflow` (the offline demo world and the deterministic stub
  * model, no AWS) and pins the abstention code each one produces. The pinned
  * codes below are written out here on purpose, apart from `AbstentionCodes`,
  * so the committed mapping and the observed behaviour are two separate facts
  * that must agree.
  *
  * The names of the templates come from the demo world's directory, so the
  * generator fills them exactly as it does for the real export.
  */
class AbstentionMappingSuite extends FunSuite:
  private val ids = DemoWorld.ids
  private val w = "2000-2009"

  private def ref(table: String, id: String): SourceRef =
    SourceRef(table, s"$table/part-00000.jsonl", 1, "sha-" + id, id)

  // Priya Raman is left out: her node shares an ORCID with a second node, so
  // the resolver would give a low-confidence answer, which is not what the
  // known-researcher templates are meant to exercise.
  private val people: Vector[(String, String)] = Vector(
    ids.ada -> "Ada Quillfeather",
    ids.boris -> "Boris Marlowe",
    ids.chidi -> "Chidi Okonkwo",
    ids.fiona -> "Fiona Park",
    ids.dana -> "Dana Whitcombe",
    ids.gus -> "Gus Holloway",
    ids.weiA -> "Wei Zhang",
    ids.weiB -> "Wei Zhang",
    ids.ines -> "Ines Calder"
  )

  private val tables = BenchTables(
    "run:v1:demo",
    people.map((id, name) =>
      ResearcherRow(
        id,
        if id.startsWith("mention:") then "unresolved-mention" else "canonical",
        name,
        ref("nodes-researcher", id)
      )
    ),
    DemoWorld.allMetrics.map { c =>
      val id = c.metrics.researcherId
      val window = s"${c.metrics.window.startYear}-${c.metrics.window.endYear}"
      MetricsRow(
        id,
        window,
        Map("publicationCount" -> c.metrics.publicationCount.toDouble),
        ref("researcher-metrics", s"$id|$window")
      )
    },
    Vector.empty,
    Vector.empty,
    Vector.empty,
    Vector.empty,
    Vector.empty
  )

  /** The code each class must produce, independent of `AbstentionCodes`. */
  private val pinned: Map[String, String] = Map(
    "unknown-researcher" -> "identity-unresolved",
    "ambiguous-name" -> "identity-unresolved",
    "future-window" -> "no-deterministic-evidence",
    "out-of-scope" -> "planner-refused",
    "unrankable-metric" -> "researcher-count",
    "missing-second-window" -> "needs-two-windows"
  )

  private val unanswerable: Vector[BenchItem] =
    BenchmarkGen
      .generate(
        tables,
        BenchConfig(counts = Map("unanswerable" -> 8)),
        VectorCoverage.unknown
      )
      .items

  private def answer(
      text: String,
      mode: RetrievalMode,
      ordinal: Int
  ): WorkflowResult =
    DemoWorld
      .build()
      .workflow
      .answer(text, Session(s"abstention-${mode.wire}", ordinal), mode)

  test("the demo-world fixture yields all eight unanswerable templates"):
    assertEquals(unanswerable.size, 8)
    assert(unanswerable.forall(_.label.expected.abstain))
    assertEquals(
      unanswerable.flatMap(_.label.expected.abstainClass).toSet,
      pinned.keySet
    )

  test("the committed mapping agrees with the pinned codes"):
    pinned.foreach((cls, code) =>
      assertEquals(AbstentionCodes.byClass(cls), Vector(code), cls)
    )

  Vector(RetrievalMode.Graph, RetrievalMode.Fused).foreach { mode =>
    test(
      s"each unanswerable template abstains with its pinned code in ${mode.wire} mode"
    ):
      unanswerable.zipWithIndex.foreach { (item, i) =>
        val cls = item.label.expected.abstainClass.get
        val result = answer(item.question.text, mode, i + 1)
        val note =
          s"${item.question.questionId} [$cls] \"${item.question.text}\" -> " +
            s"abstained=${result.abstained} code=${result.abstentionReason}"
        assert(result.abstained, note)
        assertEquals(result.abstentionReason, Some(pinned(cls)), note)
      }
  }

  test(
    "a researcher lookup in a future window is not a no-evidence abstention"
  ):
    // the reason the former template 8 was replaced: the identity record alone
    // carries a lookup past the evidence stage
    val known = people.head._2
    val result = answer(
      s"Who is $known between 2040 and 2049?",
      RetrievalMode.Fused,
      99
    )
    assert(
      !result.abstentionReason.contains("no-deterministic-evidence"),
      result.abstentionReason.toString
    )
    assert(
      result.response.evidence.items
        .exists(_.text.exists(_.contains("identity")))
    )

  test("an absent path pair is answered with a NotPrecomputed status"):
    val result = answer(
      s"What is the shortest path between Ada Quillfeather and Gus Holloway between 2000 and 2009?",
      RetrievalMode.Fused,
      98
    )
    val statuses = result.response.evidence.items
      .flatMap(_.text)
      .filter(_.contains("path-status"))
    assert(statuses.exists(_.contains("NotPrecomputed")), statuses.toString)
    assert(
      !result.abstentionReason.exists(code =>
        Set(
          "planner-refused",
          "identity-unresolved",
          "no-deterministic-evidence",
          "researcher-count",
          "needs-two-windows"
        ).contains(code)
      ),
      result.abstentionReason.toString
    )
