package scholarchain.tests.adversarial

import java.nio.file.{Files, Path, Paths}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scholarchain.core.MatchOutcome
import scholarchain.er.llm.*
import scholarchain.er.matching.*
import scholarchain.tests.adversarial.ErFixtures.*

/** LLM adjudication: strict schema, bounded band, abstention, cache keys. Only
  * the offline stub is used.
  */
class AdversarialErLlmSuite extends FunSuite:

  private val left = person("m1", "John Smith", Vector("Alice Wong"))
  private val right = person("m2", "John Smith", Vector("Alice Wong"))
  private val base = decide(left, right)

  private def answer(
      decision: String,
      confidence: Double = 0.9,
      extra: String = ""
  ) =
    s"""{"decision":"$decision","confidence":$confidence,"reasonCodes":["same-coauthor"]$extra}"""

  private def adjudicator(
      json: String,
      cache: AdjudicationCache = InMemoryAdjudicationCache(),
      model: String = "stub-1",
      pol: Policy = policy
  ) = LlmAdjudicator(OfflineStubModel.fixed(model, json), cache, pol, run)

  private def at(score: Double): ResolutionRecord =
    base.copy(decision = base.decision.copy(confidence = score))

  test("the base fixture is unresolved, probabilistic and in the band") {
    assertEquals(base.decision.decision, MatchOutcome.Unresolved)
    assertEquals(base.path, MatchPath.Probabilistic)
    assert(policy.inLlmBand(base.score), s"score ${base.score}")
  }

  test("strict parse rejects every off-schema shape") {
    val ids = Set("m1")
    val bad = Vector(
      null,
      "",
      "{}",
      "[]",
      "null",
      "\"accept\"",
      "{\"decision\":\"accept\"} trailing",
      """{"decision":"Accept","confidence":0.9,"reasonCodes":["x"]}""",
      """{"decision":["accept"],"confidence":0.9,"reasonCodes":["x"]}""",
      """{"decision":"accept","confidence":-0.01,"reasonCodes":["x"]}""",
      """{"decision":"accept","confidence":1.0000001,"reasonCodes":["x"]}""",
      """{"decision":"accept","confidence":null,"reasonCodes":["x"]}""",
      """{"decision":"accept","confidence":0.9,"reasonCodes":[1]}""",
      """{"decision":"accept","confidence":0.9,"reasonCodes":["UPPER"]}""",
      """{"decision":"accept","confidence":0.9,"reasonCodes":["a b"]}""",
      """{"decision":"accept","confidence":0.9,"reasonCodes":["x"],"extra":1}""",
      s"""{"decision":"accept","confidence":0.9,"reasonCodes":[${Vector
          .fill(9)("\"x\"")
          .mkString(",")}]}""",
      s"""{"decision":"accept","confidence":0.9,"reasonCodes":["${"a" * 65}"]}""",
      s"""{"decision":"accept","confidence":0.9,"reasonCodes":["x"],"evidenceIds":[${Vector
          .fill(17)("\"m1\"")
          .mkString(",")}]}""",
      """{"decision":"accept","confidence":0.9,"reasonCodes":["x"],"evidenceIds":"m1"}"""
    )
    bad.foreach { text =>
      assert(AdjudicationResponse.parse(text, ids).isLeft, String.valueOf(text))
    }
  }

  test("strict parse accepts the boundary values") {
    val ids = Set("m1")
    Vector(0.0, 1.0).foreach { c =>
      assert(AdjudicationResponse.parse(answer("accept", c), ids).isRight)
    }
    val eight = Vector.fill(8)("\"x\"").mkString(",")
    assert(
      AdjudicationResponse
        .parse(
          s"""{"decision":"reject","confidence":0.5,"reasonCodes":[$eight],"evidenceIds":["m1","m1"]}""",
          ids
        )
        .isRight
    )
    val tooLong = answer("accept") + " " * AdjudicationResponse.maxTextLength
    assert(AdjudicationResponse.parse(tooLong, ids).isLeft)
  }

  test("an invented identifier is rejected and becomes an abstention") {
    val json =
      answer("accept", 0.99, ""","evidenceIds":["orcid:0000-0000-0000-0000"]""")
    assert(
      AdjudicationResponse.parse(json, Set("m1", "m2")).isLeft
    )
    val result = adjudicator(json).refine(base, left, right)
    assertEquals(result.decision.decision, MatchOutcome.Unresolved)
    assert(result.decision.reasonCodes.contains("llm.invalid-response"))
    assertEquals(result.leftId, base.leftId)
    assertEquals(result.rightId, base.rightId)
  }

  test("a cited candidate identifier is accepted") {
    val json = answer(
      "accept",
      0.9,
      s""","evidenceIds":["${left.id}","${right.sourceRecordId}"]"""
    )
    val result = adjudicator(json).refine(base, left, right)
    assertEquals(result.decision.decision, MatchOutcome.Accepted)
    assertEquals(result.path, MatchPath.LlmAdjudicated)
    assertEquals(result.decision.sourceId, base.decision.sourceId)
    assertEquals(result.decision.targetId, base.decision.targetId)
    assertEquals(result.decision.modelVersion, Some("stub-1"))
    assertEquals(
      result.decision.promptVersion,
      Some(AdjudicationPrompt.version)
    )
  }

  test("abstain, however confident, leaves the pair unresolved") {
    val result = adjudicator(answer("abstain", 1.0)).refine(base, left, right)
    assertEquals(result.decision.decision, MatchOutcome.Unresolved)
    assertEquals(result.path, MatchPath.Probabilistic)
    assert(result.decision.reasonCodes.contains("llm.abstained"))
  }

  test("the confidence floor is inclusive") {
    val floor = policy.llmMinConfidence
    assertEquals(
      adjudicator(answer("accept", floor))
        .refine(base, left, right)
        .decision
        .decision,
      MatchOutcome.Accepted
    )
    val below =
      adjudicator(answer("accept", floor - 0.001)).refine(base, left, right)
    assertEquals(below.decision.decision, MatchOutcome.Unresolved)
    assert(below.decision.reasonCodes.contains("llm.below-confidence-floor"))
    assertEquals(
      adjudicator(answer("reject", floor))
        .refine(base, left, right)
        .decision
        .decision,
      MatchOutcome.Rejected
    )
  }

  test("a throwing model and an oversize answer both abstain") {
    val boom = LlmAdjudicator(
      OfflineStubModel("boom", _ => throw new RuntimeException("offline")),
      InMemoryAdjudicationCache(),
      policy,
      run
    )
    val r1 = boom.refine(base, left, right)
    assertEquals(r1.decision.decision, MatchOutcome.Unresolved)
    val huge = adjudicator("x" * 1000000).refine(base, left, right)
    assertEquals(huge.decision.decision, MatchOutcome.Unresolved)
  }

  test("the band edges: low edge in, just below out, accept edge out") {
    val low = policy.llmBandLow
    val high = policy.accept
    val cases = Vector(
      (low, true),
      (math.nextDown(low), false),
      (math.nextDown(high), true),
      (high, false),
      (policy.reject, false),
      (0.0, false),
      (1.0, false)
    )
    cases.foreach { (score, expected) =>
      val a = adjudicator(answer("accept"))
      assertEquals(a.eligible(at(score)), expected, s"score $score")
      a.refine(at(score), left, right)
      assertEquals(a.modelCalls, if expected then 1 else 0, s"score $score")
    }
  }

  test("only a probabilistic unresolved record is ever sent to the model") {
    val a = adjudicator(answer("accept"))
    val inBand = base.score
    Vector(MatchOutcome.Accepted, MatchOutcome.Rejected, MatchOutcome.Conflict)
      .foreach { o =>
        val r = base.copy(decision =
          base.decision.copy(decision = o, confidence = inBand)
        )
        assertEquals(a.refine(r, left, right), r, o.toString)
      }
    val exact = base.copy(path = MatchPath.ExactId)
    assertEquals(a.refine(exact, left, right), exact)
    val missing = base.copy(decision =
      base.decision.copy(reasonCodes =
        base.decision.reasonCodes :+ "input.side-missing"
      )
    )
    assertEquals(a.refine(missing, left, right), missing)
    assertEquals(a.modelCalls, 0)
  }

  test("a conflict between ORCIDs is never offered to the model") {
    val a = person("a", "Ada Lovelace", externalIds = Map("orcid" -> orcidA))
    val b = person("b", "Ada Lovelace", externalIds = Map("orcid" -> orcidB))
    val conflict = decide(a, b)
    val ad = adjudicator(answer("accept", 1.0))
    assertEquals(ad.refine(conflict, a, b), conflict)
    assertEquals(ad.modelCalls, 0)
  }

  test("a repeated pair is served from the cache, including abstentions") {
    val cache = InMemoryAdjudicationCache()
    val a = adjudicator(answer("abstain", 0.5), cache)
    a.refine(base, left, right)
    a.refine(base, left, right)
    assertEquals(a.modelCalls, 1)
    assertEquals(cache.size, 1)
  }

  test("changing model, policy or input misses the cache; same parts hit") {
    val cache = InMemoryAdjudicationCache()
    adjudicator(answer("abstain"), cache).refine(base, left, right)
    val newModel = adjudicator(answer("abstain"), cache, model = "stub-2")
    newModel.refine(base, left, right)
    assertEquals(newModel.modelCalls, 1)
    val newPolicy =
      adjudicator(
        answer("abstain"),
        cache,
        pol = policy.copy(version = policy.version + "+x")
      )
    newPolicy.refine(base, left, right)
    assertEquals(newPolicy.modelCalls, 1)
    val edited = right.copy(title = right.title + " changed")
    val newInput = adjudicator(answer("abstain"), cache)
    newInput.refine(base, left, edited)
    assertEquals(newInput.modelCalls, 1)
    val same = adjudicator(answer("abstain"), cache)
    same.refine(base, left, right)
    assertEquals(same.modelCalls, 0)
    assertEquals(cache.size, 4)
  }

  test("each of the four cache key parts changes the digest") {
    val k = CacheKey("h", "p", "m", "v")
    Vector(
      k.copy(inputHash = "h2"),
      k.copy(promptVersion = "p2"),
      k.copy(modelVersion = "m2"),
      k.copy(policyVersion = "v2")
    ).foreach(other => assertNotEquals(other.digest, k.digest, other.toString))
    assertEquals(k.digest, CacheKey("h", "p", "m", "v").digest)
  }

  test("cache key parts cannot be shifted across a boundary or left blank") {
    assertNotEquals(
      CacheKey("ab", "c", "m", "v").digest,
      CacheKey("a", "bc", "m", "v").digest
    )
    intercept[IllegalArgumentException](CacheKey("", "p", "m", "v"))
    intercept[IllegalArgumentException](CacheKey("h", " ", "m", "v"))
  }

  test("the input hash tracks content but not set-like ordering") {
    val req = AdjudicationRequest(base, left, right)
    val again = AdjudicationRequest(base, left, right)
    assertEquals(req.inputHash, again.inputHash)
    val l1 = left.copy(citationNeighbors = Vector("c", "a", "b", "a"))
    val l2 = left.copy(citationNeighbors = Vector("a", "b", "c"))
    assertEquals(
      AdjudicationRequest(base, l1, right).inputHash,
      AdjudicationRequest(base, l2, right).inputHash
    )
    Vector(
      left.copy(name = "Jon Smith"),
      left.copy(year = Some(1999)),
      left.copy(affiliations = Vector("X")),
      left.copy(externalIds = Map("orcid" -> orcidA))
    ).foreach { changed =>
      assertNotEquals(
        AdjudicationRequest(base, changed, right).inputHash,
        req.inputHash,
        changed.toString
      )
    }
    assertNotEquals(
      AdjudicationRequest(at(0.8), left, right).inputHash,
      AdjudicationRequest(at(0.9), left, right).inputHash
    )
  }

  test("a request must describe the decision it adjudicates") {
    intercept[IllegalArgumentException](AdjudicationRequest(base, right, left))
  }

  test("the adjudication module reaches no network") {
    val pkg = Paths.get(
      "modules",
      "entity-resolution",
      "src",
      "main",
      "scala",
      "scholarchain",
      "er"
    )
    val roots = Vector(pkg.resolve("matching"), pkg.resolve("llm"))
    roots.foreach(r => assert(Files.isDirectory(r), s"missing $r"))
    val forbidden = Vector(
      "java.net",
      "HttpClient",
      "okhttp",
      "sttp",
      "requests.",
      "URLConnection",
      "Socket"
    )
    val files: List[Path] =
      roots.toList.flatMap { root =>
        Files
          .walk(root)
          .iterator
          .asScala
          .filter(_.toString.endsWith(".scala"))
          .toList
      }
    assert(files.nonEmpty)
    val hits = for
      f <- files
      text = Files.readString(f)
      word <- forbidden if text.contains(word)
    yield s"$f: $word"
    assertEquals(hits, Nil)
  }
