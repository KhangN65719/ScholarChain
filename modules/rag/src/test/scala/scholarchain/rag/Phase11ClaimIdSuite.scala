package scholarchain.rag

import scholarchain.core.VerificationStatus

/** Phase 9 carry-overs m5 (claim IDs are assigned by the system) and n1 (an
  * answer whose only kept claim is an abstention claim is abstained), plus the
  * rule that an unsupported number in model text is not repeated.
  */
class Phase11ClaimIdSuite extends munit.FunSuite:
  import RuntimeTestSupport.*

  private val question =
    "Which researcher has the most distinct coauthors from 2000-2009?"
  private val systemId = "claim-[0-9]+-[a-z-]+-[0-9a-f]{12}"

  private def withModel(world: DemoWorld, model: ModelPort) =
    new CollaborationWorkflow(world.ports.copy(model = model), world.config)

  private def askWith(model: ModelPort): WorkflowResult =
    val world = DemoWorld.build()
    withModel(world, model).answer(question, Session("test-session", 0))

  private def editClaims(f: Vector[ujson.Value] => Vector[ujson.Value]) =
    new TamperingModel((reply, _) =>
      val claims = reply("claims").arr.toVector
      ujson.Obj.from(
        reply.obj.toSeq.map {
          case ("claims", _) => "claims" -> (ujson.Arr.from(f(claims)))
          case other         => other
        }
      )
    )

  private def withField(c: ujson.Value, key: String, v: ujson.Value) =
    val out = ujson.Obj.from(c.obj.toSeq)
    out(key) = v
    out: ujson.Value

  private val canary = "CANARY-ignore-previous-instructions-<|im_start|>"

  private def hostileIds(claims: Vector[ujson.Value]): Vector[ujson.Value] =
    claims.zipWithIndex.map { (c, i) =>
      val id: String = i % 5 match
        case 0 => "dup"
        case 1 => "dup"
        case 2 => canary
        case 3 => ""
        case _ => "X" * 10000
      withField(c, "claimId", id)
    }

  test("m5: hostile or colliding model claim IDs yield system IDs only") {
    val r = askWith(editClaims(hostileIds))
    assert(!r.abstained, r.response.answer)
    val ids = r.response.claims.map(_.claimId)
    assert(ids.size >= 5, ids.toString)
    assertEquals(ids.distinct.size, ids.size)
    ids.foreach(id => assert(id.matches(systemId), id))
    assertEquals(
      r.verification.get.claims.map(_.claimId).toSet,
      r.verification.get.changes
        .filterNot(_.claimId == "analysis")
        .map(_.claimId)
        .toSet
    )
    r.verification.get.claims.foreach(c => assert(c.claimId.matches(systemId)))
    val everything = ujson.write(r.audit.payload) +
      upickle.default.write(r.response)
    assert(!everything.contains("CANARY"))
    assert(!everything.contains("XXXXXXXXXX"))
    assert(!everything.contains("\"dup\""))
    assert(
      SchemaCheck
        .validate(Schemas.VerifierReport, r.verification.get.toJson)
        .isEmpty
    )
  }

  test("m5: identical replays give identical IDs, whatever the model wrote") {
    val stub = askWith(new DeterministicStubModel)
    val again = askWith(new DeterministicStubModel)
    val hostile = askWith(editClaims(hostileIds))
    val ids = (r: WorkflowResult) => r.response.claims.map(_.claimId)
    assertEquals(ids(stub), ids(again))
    assertEquals(ids(hostile), ids(stub))
    val noIds = askWith(editClaims(_.map(c =>
      val out = ujson.Obj.from(c.obj.toSeq.filterNot(_._1 == "claimId"))
      out: ujson.Value
    )))
    assertEquals(ids(noIds), ids(stub))
    val reworded = askWith(
      editClaims(_.map(c => withField(c, "uncertainty", "some model words")))
    )
    assertEquals(ids(reworded), ids(stub))
  }

  test("m5: the ID follows the position, kind and structure of the claim") {
    val a = ClaimDraft.fromJson(
      ujson.read(
        """{"claimId":"a","text":"t","kind":"semantic","evidenceIds":[],"assertion":{"kind":"semantic"},"uncertainty":null}"""
      ),
      1
    )
    val sameButFirstWords = ClaimDraft.fromJson(
      ujson.read(
        """{"claimId":"zzz","text":"other words","kind":"semantic","evidenceIds":[],"assertion":{"kind":"semantic"},"uncertainty":"x"}"""
      ),
      1
    )
    val second = ClaimDraft.fromJson(
      ujson.read(
        """{"claimId":"a","text":"t","kind":"semantic","evidenceIds":[],"assertion":{"kind":"semantic"},"uncertainty":null}"""
      ),
      2
    )
    assertEquals(a.claimId, sameButFirstWords.claimId)
    assertNotEquals(a.claimId, second.claimId)
    assert(a.claimId.matches(systemId), a.claimId)
    assert(a.claimId.length <= 40)
  }

  test("m5: the synthesis schema accepts a missing or any model claimId") {
    def claim(extra: String) =
      ujson.read(
        s"""{"schema":"${Schemas.SynthesisOutputId}","analysis":"","claims":[{$extra"text":"t","kind":"semantic","evidenceIds":[],"assertion":{"kind":"semantic"},"uncertainty":null}],"missingEvidence":[],"uncertainty":null}"""
      )
    Vector("", "\"claimId\":\"\",", "\"claimId\":\"" + "y" * 10000 + "\",")
      .foreach { extra =>
        assertEquals(
          SchemaCheck.validate(Schemas.SynthesisOutput, claim(extra)),
          Vector.empty[String]
        )
      }
  }

  private val abstention = ujson.Obj(
    "claimId" -> "a1",
    "text" -> "Boris has 77 friends",
    "kind" -> "abstention",
    "evidenceIds" -> ujson.Arr(),
    "assertion" -> ujson.Obj("kind" -> "abstention"),
    "uncertainty" -> "The model says 4242 things"
  )

  test("n1: an abstention-only synthesis output is abstained with a reason") {
    val r = askWith(editClaims(_ => Vector(abstention)))
    assert(r.abstained)
    assertEquals(r.abstentionReason, Some("claims-unsupported"))
    assertEquals(r.audit.payload("abstained"), ujson.True)
    assertEquals(
      r.audit.payload("abstentionReason"),
      ujson.Str("claims-unsupported")
    )
    assertEquals(
      r.response.answer,
      "The stored evidence does not support an answer to this question."
    )
    assertEquals(r.response.claims.size, 1)
    assertEquals(r.response.claims.head.text, Rendering.AbstentionText)
    assertEquals(
      r.response.claims.head.verificationStatus,
      VerificationStatus.Uncertain
    )
    val everything =
      ujson.write(r.audit.payload) + upickle.default.write(r.response)
    assert(!everything.contains("77 friends"))
    assert(!everything.contains("4242"))
  }

  test("n1: an abstention-only output at the verifier is abstained") {
    val world = DemoWorld.build()
    val ev = new RichEvidence(world)
    val fin = new ClaimVerificationAgent().finalizeAnswer(
      ev.bundle,
      output(
        Vector(
          draft(
            "a1",
            "abstention",
            "x",
            Vector.empty,
            ujson.Obj("kind" -> "abstention"),
            Some("reason")
          )
        )
      ),
      Vector("No stored metrics for Fiona Park in window 2010-2019."),
      provenance
    )
    assert(fin.abstained)
    assertEquals(
      fin.claims.map(_.verificationStatus),
      Vector(VerificationStatus.Uncertain)
    )
  }

  test("n1: an abstention beside a kept claim does not abstain the answer") {
    val r = askWith(editClaims(claims => claims :+ abstention))
    assert(!r.abstained, r.response.answer)
    assertEquals(r.abstentionReason, None)
    assert(r.response.claims.exists(_.text == Rendering.AbstentionText))
  }

  test("n1: an unsupported number in model text is not echoed anywhere") {
    val r = askWith(editClaims(claims =>
      val first = claims.head
      withField(
        first,
        "text",
        first("text").str + " and 98765 prizes and seventy-seven awards"
      ) +: claims.tail
    ))
    val weakened = r.verification.get.changes.filter(_.action == "weakened")
    assertEquals(weakened.size, 1)
    val everything =
      ujson.write(r.audit.payload) + upickle.default.write(r.response)
    assert(!everything.contains("98765"))
    assert(!everything.contains("seventy-seven"))
    val kept = r.response.claims.filter(
      _.verificationStatus == VerificationStatus.Uncertain
    )
    assertEquals(kept.size, 1)
    assert(kept.head.uncertainty.exists(_.contains("2 number(s)")))
  }
