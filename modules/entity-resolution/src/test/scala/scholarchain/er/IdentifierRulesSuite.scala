package scholarchain.er

import scholarchain.core.MatchOutcome
import scholarchain.er.Fixtures.*
import scholarchain.er.matching.*

/** Pins human decision 4: which identifier disagreements are a CONFLICT. */
class IdentifierRulesSuite extends munit.FunSuite:
  private val matcher = Matcher(Policy.v1, runInfo)

  private def outcome(a: SideRecord, b: SideRecord): MatchOutcome =
    matcher.decide(MatchInput.of(a, b, "block-1")).decision.decision

  private val coauthors = Vector("Alice Wong", "Bob Chen")

  private def researcherPair(
      left: Map[String, String],
      right: Map[String, String]
  ) =
    (
      mention("m1", "Jane Doe", coauthors, Vector("MIT"), left),
      mention("m2", "Jane Doe", coauthors, Vector("MIT"), right)
    )

  test(
    "the policy version is 1.4.0, bumped for the same-record position CONFLICT"
  ) {
    assertEquals(Policy.v1.version, "scholarchain.er.policy/1.4.0")
  }

  test("researchers: an ORCID mismatch is a CONFLICT") {
    val (a, b) = researcherPair(
      Map("orcid" -> validOrcidA),
      Map("orcid" -> validOrcidB)
    )
    assertEquals(outcome(a, b), MatchOutcome.Conflict)
  }

  test("researchers: an OpenAlex author ID mismatch alone is not a CONFLICT") {
    val (a, b) =
      researcherPair(Map("openalex" -> "A1"), Map("openalex" -> "A2"))
    assertNotEquals(outcome(a, b), MatchOutcome.Conflict)
  }

  test("researchers: a DBLP author ID mismatch alone is not a CONFLICT") {
    val (a, b) = researcherPair(
      Map("dblp-author" -> "w/1"),
      Map("dblp-author" -> "w/2")
    )
    assertNotEquals(outcome(a, b), MatchOutcome.Conflict)
  }

  test("works: a DOI mismatch is a CONFLICT") {
    val a =
      work("w1", "Some shared title", externalIds = Map("doi" -> "10.1000/a"))
    val b =
      work("w2", "Some shared title", externalIds = Map("doi" -> "10.1000/b"))
    assertEquals(outcome(a, b), MatchOutcome.Conflict)
  }

  test("pending human ruling: other work identifier mismatches also conflict") {
    val cases = Vector(
      "openalex" -> ("W1" -> "W2"),
      "semantic-scholar" -> ("a" * 40 -> "b" * 40),
      "repository-url" -> ("https://github.com/o/r1" -> "https://github.com/o/r2"),
      "arxiv" -> ("2101.00001" -> "2102.00002")
    )
    cases.foreach { case (key, (left, right)) =>
      val a = work("w1", "Some shared title", externalIds = Map(key -> left))
      val b = work("w2", "Some shared title", externalIds = Map(key -> right))
      assertEquals(outcome(a, b), MatchOutcome.Conflict, key)
    }
  }
