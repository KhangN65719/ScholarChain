package scholarchain.rag

import scholarchain.storage.{
  ErrorCode,
  HashEmbedder,
  InMemoryVectorBucket,
  StorageException,
  TransientStorageException,
  VectorMatch,
  VectorQuery
}

/** Phase 11 D1: a failed vector read degrades the answer, never fails it. */
class Phase11SemanticDegradeSuite extends munit.FunSuite:
  import RuntimeTestSupport.*

  private val canary = "p11-canary-secret-7731"

  private final class FailingBucket(failure: () => Throwable)
      extends InMemoryVectorBucket:
    var queries = 0
    override def query(request: VectorQuery): Vector[VectorMatch] =
      queries += 1
      throw failure()

  private def withFailure(failure: () => Throwable) =
    val world = DemoWorld.build()
    val bucket = new FailingBucket(failure)
    val ports = world.ports.copy(semantic =
      Some(new SemanticRetrieval(new HashEmbedder, bucket, world.objectStore))
    )
    (new CollaborationWorkflow(ports, world.config), bucket)

  private val transient: () => Throwable =
    () => new TransientStorageException(s"ThrottlingException $canary")
  private val exhausted: () => Throwable =
    () => new StorageException(ErrorCode.RetryExhausted, s"retries $canary")

  private def ask(wf: CollaborationWorkflow, q: String): WorkflowResult =
    wf.answer(q, Session("test-session", 0))

  private val metricQuestion =
    "How many distinct coauthors does Ada Quillfeather have in 2000-2009?"

  private def everything(r: WorkflowResult): String =
    upickle.default.write(r.response) + r.audit.payload.render() +
      r.flags.mkString(" ")

  Vector("transient" -> transient, "retry exhausted" -> exhausted).foreach {
    (label, failure) =>
      test(s"$label vector failure: deterministic answer stays verified") {
        val (wf, bucket) = withFailure(failure)
        val r = ask(wf, metricQuestion)
        assert(bucket.queries > 0, "the failing bucket was never queried")
        assert(!r.abstained, r.response.answer)
        assertEquals(r.verdict, Some(VerificationVerdict.Verified))
        assert(r.response.claims.nonEmpty)
        val gaps = Vector(
          scholarchain.storage.VectorIndexes.ResearcherProfile,
          scholarchain.storage.VectorIndexes.PaperAbstract
        ).map(SemanticContextAgent.degradedNote)
        val uncertainty = r.response.evidence.uncertainty.getOrElse("")
        gaps.foreach(g => assert(uncertainty.contains(g), uncertainty))
        assert(
          r.audit.payload("stages").arr.exists { s =>
            s("name").str == "semantic" && s("outcome").str.startsWith(
              "degraded"
            )
          },
          r.audit.payload("stages").render()
        )
      }

      test(
        s"$label vector failure: no exception text reaches output or audit"
      ) {
        val (wf, _) = withFailure(failure)
        val r = ask(wf, metricQuestion)
        assert(!everything(r).contains(canary))
        assert(!everything(r).contains("ThrottlingException"))
        assert(!everything(r).contains("TransientStorageException"))
      }
  }

  test("both failure kinds produce the identical known gap") {
    val a = ask(withFailure(transient)._1, metricQuestion)
    val b = ask(withFailure(exhausted)._1, metricQuestion)
    assertEquals(
      a.response.evidence.uncertainty,
      b.response.evidence.uncertainty
    )
    assertEquals(a.response.answer, b.response.answer)
  }

  test(
    "no deterministic evidence plus a failed vector read abstains honestly"
  ) {
    val (wf, _) = withFailure(transient)
    val r = ask(
      wf,
      "How many distinct coauthors does Ada Quillfeather have in 1990-1999?"
    )
    assert(r.abstained, r.response.answer)
    assert(r.response.claims.forall(_.text != Rendering.SemanticText))
    assert(!everything(r).contains(canary))
  }

  test("an unrelated programming error is not swallowed") {
    val (wf, _) = withFailure(() => new IllegalStateException(canary))
    intercept[IllegalStateException](ask(wf, metricQuestion))
  }
