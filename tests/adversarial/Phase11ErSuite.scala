package scholarchain.tests.adversarial

import java.text.Normalizer
import munit.FunSuite
import scholarchain.core.MatchOutcome
import scholarchain.er.matching.*
import scholarchain.tests.adversarial.ErFixtures.*

/** Phase 11 entity resolution: confusable (homoglyph) author names must not
  * merge silently into one researcher, and a reordered author list must not
  * change a decision. Controls with the Latin spelling prove that the evidence
  * around each pair is strong enough to merge, so a refusal is the homoglyph's
  * doing and not a weak fixture.
  */
class Phase11ErSuite extends FunSuite:
  private val shared = Vector("Alice Wong", "Bo Chen", "Carla Diaz")
  private val affiliation = Vector("Massachusetts Institute of Technology")

  private def strong(id: String, name: String, team: Vector[String] = shared) =
    person(id, name, team, affiliation)

  private val cyrA = "а" // Cyrillic small a
  private val cyrE = "е" // Cyrillic small ie
  private val cyrO = "о" // Cyrillic small o
  private val cyrCapA = "А"

  test(
    "the control: the Latin spelling and its NFD form merge on this evidence"
  ) {
    val nfd = Normalizer.normalize("José García", Normalizer.Form.NFD)
    assertEquals(
      outcome(strong("a", "José García"), strong("b", nfd)),
      MatchOutcome.Accepted
    )
    assertEquals(
      outcome(strong("a", "Ann Lee"), strong("b", "Ann Lee")),
      MatchOutcome.Accepted
    )
  }

  test("a homoglyph in the given name, family name or both is not accepted") {
    val spoofs = Vector(
      s"${cyrCapA}nn Lee",
      s"Ann L${cyrE}e",
      s"${cyrCapA}nn L${cyrE}${cyrE}",
      s"Ann Le${cyrE}",
      "Ann Lee".replace("e", cyrE),
      "Wei Zhang".replace("a", cyrA),
      "José García".replace("a", cyrA)
    )
    val bases = Vector(
      "Ann Lee",
      "Ann Lee",
      "Ann Lee",
      "Ann Lee",
      "Ann Lee",
      "Wei Zhang",
      "José García"
    )
    spoofs.zip(bases).foreach { (spoof, base) =>
      assertNotEquals(spoof, base, "the spoof must differ from the base")
      val result = decide(strong("a", base), strong("b", spoof))
      assertNotEquals(
        result.decision.decision,
        MatchOutcome.Accepted,
        s"$spoof was merged with $base: ${result.decision.reasonCodes}"
      )
      assert(result.decision.reasonCodes.nonEmpty, spoof)
      // the order of the pair does not change the verdict
      assertEquals(
        outcome(strong("b", spoof), strong("a", base)),
        result.decision.decision,
        spoof
      )
    }
  }

  test("a homoglyph with no other evidence is not accepted either") {
    assertNotEquals(
      outcome(person("a", "Wei Zhang"), person("b", s"Wei Zh${cyrA}ng")),
      MatchOutcome.Accepted
    )
    assertNotEquals(
      outcome(person("a", "Bo Chen"), person("b", s"B${cyrO} Chen")),
      MatchOutcome.Accepted
    )
  }

  test("an ORCID in common is the only thing that merges a spoofed name") {
    val a = person("a", "Ann Lee", externalIds = Map("orcid" -> orcidA))
    val b =
      person("b", s"${cyrCapA}nn Lee", externalIds = Map("orcid" -> orcidA))
    val r = decide(a, b)
    // an authoritative ID may merge, but never off the name: the path says why
    assertEquals(
      r.decision.decision,
      MatchOutcome.Accepted,
      s"a shared ORCID was dropped: ${r.decision.reasonCodes}"
    )
    assertEquals(r.path, MatchPath.ExactId)
    assert(r.decision.reasonCodes.nonEmpty)
    val noId = decide(person("c", "Ann Lee"), person("d", s"${cyrCapA}nn Lee"))
    assertNotEquals(noId.decision.decision, MatchOutcome.Accepted)
  }

  test("a reordered coauthor list does not change the decision") {
    val names = Vector("Ann Lee", "Wei Zhang", "José García")
    val teams = Vector(shared, shared.reverse, shared.sorted, shared.rotate(1))
    names.foreach { name =>
      val reference = decide(strong("a", name), strong("b", name, shared))
      teams.foreach { team =>
        val r = decide(strong("a", name, team), strong("b", name, shared))
        assertEquals(
          r.decision.decision,
          reference.decision.decision,
          s"$name $team"
        )
        assertEquals(
          r.decision.confidence,
          reference.decision.confidence,
          s"$name $team"
        )
        assertEquals(
          r.decision.reasonCodes,
          reference.decision.reasonCodes,
          s"$name $team"
        )
        val flipped = decide(strong("b", name, shared), strong("a", name, team))
        assertEquals(flipped.decision.decision, reference.decision.decision)
      }
    }
  }

  test(
    "name order, not just list order: the same person in either name order"
  ) {
    val forward =
      outcome(strong("a", "Ada Lovelace"), strong("b", "Lovelace, Ada"))
    val backward =
      outcome(strong("b", "Lovelace, Ada"), strong("a", "Ada Lovelace"))
    assertEquals(forward, backward)
    assertNotEquals(forward, MatchOutcome.Rejected)
  }

  extension [A](v: Vector[A])
    private def rotate(n: Int): Vector[A] = v.drop(n) ++ v.take(n)
