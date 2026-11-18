package scholarchain.tests.adversarial

import scala.collection.mutable
import munit.FunSuite
import scholarchain.evaluation.answer.AnswerEval
import scholarchain.evaluation.retrieval.{
  BenchmarkFiles,
  QuestionRec,
  RetrievalEval
}
import scholarchain.rag.{
  CollaborationWorkflow,
  RetrievalMode,
  Session,
  WorkflowResult
}
import P16W2Support.*

/** The benchmark file carries a stratum next to the question text, and the
  * labels carry ids, operations, accepted outcomes and expected codes. The
  * system must see only the question text (review 1, finding 14; benchmark
  * README "Questions file"). The labels of the fixture hold marker strings that
  * occur nowhere else, so any leak is visible at every boundary: the ask, the
  * session, the model request and the embedder.
  */
class P16W2TextOnlySuite extends FunSuite:

  private val questionIds = Questions.map(_._1)
  private val texts = Questions.map(_._2)

  /** Everything that must never reach the system. */
  private def secrets: Vector[String] =
    Markers ++ questionIds ++ Vector(
      "researcher-lookup",
      "coauthor-lookup",
      "shortest-path",
      "unanswerable",
      "out-of-scope",
      "unknown-researcher",
      "acceptedOutcomes",
      "expectedCodes",
      "abstainClass",
      "stratum"
    )

  private final case class Seen(
      text: String,
      session: Session,
      mode: RetrievalMode
  )

  private def spyAsk(
      w: scholarchain.evaluation.runtime.LaptopPorts.Wiring,
      seen: mutable.ArrayBuffer[Seen]
  ): (String, Session, RetrievalMode) => WorkflowResult =
    val workflow = new CollaborationWorkflow(w.ports, w.config)
    (text, session, mode) =>
      seen += Seen(text, session, mode)
      workflow.answer(text, session, mode)

  /** Strings only a label or the question file holds. The stratum names are not
    * here: the runtime uses some of them as operation names of its own, so a
    * request may say them legitimately.
    */
  private def labelOnly: Vector[String] =
    Markers ++ questionIds ++ Vector(
      "acceptedOutcomes",
      "expectedCodes",
      "abstainClass",
      "out-of-scope",
      "unknown-researcher"
    )

  private def assertNoSecret(where: String, value: String): Unit =
    secrets.foreach { s =>
      assert(!value.contains(s), s"$where carries label data: $s")
    }

  private def assertNoLabelData(where: String, value: String): Unit =
    labelOnly.foreach { s =>
      assert(!value.contains(s), s"$where carries label data: $s")
    }

  private def assertClean(
      seen: Seq[Seen],
      model: SpyModel,
      embedder: SpyEmbedder
  ): Unit =
    seen.foreach { s =>
      assertNoSecret("the question text", s.text)
      assertNoSecret("the session id", s.session.sessionId)
      assert(s.session.disambiguation.isEmpty, "the session carries a choice")
    }
    model.requests.foreach { r =>
      assertNoLabelData(s"the ${r.promptId} request", ujson.write(r.input))
    }
    embedder.texts.foreach(t => assertNoLabelData("an embedded query", t))

  test("QuestionRec can carry nothing but the id and the text") {
    assertEquals(
      classOf[QuestionRec].getDeclaredFields.map(_.getName).toSet,
      Set("questionId", "text")
    )
    val (q, _) = writeBenchmark(tempDir())
    val read = BenchmarkFiles.readQuestions(q)
    assertEquals(read.map(_.text), texts)
  }

  test("the question file's own stratum field never enters QuestionRec") {
    val dir = tempDir()
    val q = dir.resolve("q.jsonl")
    java.nio.file.Files.writeString(
      q,
      ujson.write(
        ujson.Obj(
          "questionId" -> "Q1",
          "version" -> "v1",
          "stratum" -> "zz-stratum-in-file",
          "text" -> "Who is Ada Quillfeather between 2000 and 2009?"
        )
      ) + "\n"
    )
    val rec = BenchmarkFiles.readQuestions(q).head
    assertEquals(
      rec,
      QuestionRec("Q1", "Who is Ada Quillfeather between 2000 and 2009?")
    )
  }

  test(
    "RetrievalEval asks the workflow for the text only, in every mode and repeat"
  ) {
    val dir = tempDir()
    val (q, l) = writeBenchmark(dir.resolve("bench"))
    val model = new SpyModel()
    val embedder = new SpyEmbedder
    val w = wiring(guard(), model, embedder, modelBillable = false)
    val seen = mutable.ArrayBuffer.empty[Seen]
    val cfg = RetrievalEval.Config(
      q,
      l,
      dir.resolve("out"),
      "g",
      repeats = 2,
      resamples = 20
    )
    val report = RetrievalEval.runWith(cfg, w, spyAsk(w, seen))
    assertEquals(report.status, "COMPLETE")
    // 6 questions x 3 modes x 2 repeats
    assertEquals(seen.size, 36)
    assertEquals(seen.map(_.text).distinct.toVector.sorted, texts.sorted)
    texts.foreach(t => assertEquals(seen.count(_.text == t), 6, t))
    assertEquals(
      seen.map(_.mode).toSet,
      Set(RetrievalMode.Graph, RetrievalMode.Vector, RetrievalMode.Fused)
    )
    assertClean(seen.toSeq, model, embedder)
    assert(embedder.calls > 0, "the vector modes embedded the question")
  }

  test(
    "RetrievalEval's raw record holds ids and counts, not the question text"
  ) {
    val dir = tempDir()
    val (q, l) = writeBenchmark(dir.resolve("bench"))
    val w =
      wiring(guard(), new SpyModel(), new SpyEmbedder, modelBillable = false)
    val cfg = RetrievalEval.Config(
      q,
      l,
      dir.resolve("out"),
      "g2",
      repeats = 1,
      resamples = 20
    )
    val report = RetrievalEval.run(cfg, w)
    val raw = allText(report.runDir)
    texts.foreach(t => assert(!raw.contains(t), s"the run holds the text: $t"))
    Vector("Quillfeather", "Okonkwo", "Zelda", "capital of France").foreach {
      n =>
        assert(!raw.contains(n), s"the run holds a name: $n")
    }
  }

  test("AnswerEval asks the workflow for the text only, primary and repeat") {
    val dir = tempDir()
    val (q, l) = writeBenchmark(dir.resolve("bench"))
    val model = new SpyModel()
    val embedder = new SpyEmbedder
    val w = wiring(guard(), model, embedder, modelBillable = false)
    val seen = mutable.ArrayBuffer.empty[Seen]
    val cfg = AnswerEval.Config(q, l, dir.resolve("out"), "h", repeatCount = 20)
    val report = AnswerEval.runWith(cfg, w, spyAsk(w, seen))
    assertEquals(report.status, "COMPLETE")
    assertEquals(seen.map(_.text).distinct.toVector, texts)
    assert(seen.size > texts.size, "the repeat pass asked some again")
    assert(seen.forall(_.mode == RetrievalMode.Fused))
    assertClean(seen.toSeq, model, embedder)
    assert(model.calls > 0)
  }

  test("the planner and the synthesis requests carry no label data") {
    val dir = tempDir()
    val (q, l) = writeBenchmark(dir.resolve("bench"))
    val model = new SpyModel()
    val embedder = new SpyEmbedder
    val w = wiring(guard(), model, embedder, modelBillable = false)
    AnswerEval.run(
      AnswerEval.Config(q, l, dir.resolve("out"), "h2", repeatCount = 0),
      w
    )
    assert(model.callsOf("planner-classify") >= 1, "the out-of-scope question")
    assert(model.callsOf("synthesis") >= 1)
    model.requests.foreach { r =>
      assertNoLabelData(s"the ${r.promptId} request", ujson.write(r.input))
      assertNoLabelData("the prompt id", r.promptId)
    }
  }

  test("the sessions of a run do not name the question, stratum or label") {
    val dir = tempDir()
    val (q, l) = writeBenchmark(dir.resolve("bench"))
    val w =
      wiring(guard(), new SpyModel(), new SpyEmbedder, modelBillable = false)
    val seen = mutable.ArrayBuffer.empty[Seen]
    RetrievalEval.runWith(
      RetrievalEval
        .Config(q, l, dir.resolve("out"), "g3", repeats = 1, resamples = 10),
      w,
      spyAsk(w, seen)
    )
    assert(seen.nonEmpty)
    seen.foreach { s =>
      assertNoSecret("the session id", s.session.sessionId)
      assert(s.session.ordinal >= 1)
    }
  }
