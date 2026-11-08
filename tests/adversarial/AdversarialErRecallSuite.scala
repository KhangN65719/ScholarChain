package scholarchain.tests.adversarial

import java.text.Normalizer
import munit.FunSuite
import scholarchain.core.{MatchOutcome, SourceType}
import scholarchain.er.matching.*
import scholarchain.tests.adversarial.ErFixtures.*

/** The same person or work under name variants must survive when evidence is
  * strong, and is never rejected on the variant alone.
  */
class AdversarialErRecallSuite extends FunSuite:

  private val shared = Vector("Alice Wong", "Bo Chen")
  private val affiliation = Vector("Massachusetts Institute of Technology")

  private def strong(id: String, name: String) =
    person(id, name, shared, affiliation)

  test("a shared ORCID accepts initials, order swaps and diacritics") {
    val base =
      person("a", "Jean-Pierre Müller", externalIds = Map("orcid" -> orcidA))
    Vector(
      "J. Muller",
      "Müller, Jean-Pierre",
      "J-P Muller",
      Normalizer.normalize("Jean-Pierre Müller", Normalizer.Form.NFD),
      "JEAN-PIERRE MULLER"
    ).zipWithIndex.foreach { (name, i) =>
      val other = person(s"b$i", name, externalIds = Map("orcid" -> orcidA))
      val r = decide(base, other)
      assertEquals(r.decision.decision, MatchOutcome.Accepted, name)
      assertEquals(r.path, MatchPath.ExactId, name)
    }
  }

  test("a shared ORCID on a single-letter initial against a full name") {
    val a = person("a", "A. Lovelace", externalIds = Map("orcid" -> orcidA))
    val b = person("b", "Ada Lovelace", externalIds = Map("orcid" -> orcidA))
    assertEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("NFC and NFD spellings with shared coauthors are not rejected") {
    val nfc = Normalizer.normalize("José García", Normalizer.Form.NFC)
    val nfd = Normalizer.normalize("José García", Normalizer.Form.NFD)
    assertNotEquals(
      outcome(strong("a", nfc), strong("b", nfd)),
      MatchOutcome.Rejected
    )
    assertEquals(
      outcome(strong("a", nfc), strong("b", nfd)),
      MatchOutcome.Accepted
    )
  }

  test("name order variants with strong context are never rejected") {
    assertNotEquals(
      outcome(strong("a", "Wei Zhang"), strong("b", "Zhang Wei")),
      MatchOutcome.Rejected
    )
    assertNotEquals(
      outcome(strong("a", "Ada Lovelace"), strong("b", "Lovelace, Ada")),
      MatchOutcome.Rejected
    )
  }

  test("initials against full names with strong context are never rejected") {
    assertNotEquals(
      outcome(strong("a", "J. Smith"), strong("b", "John Smith")),
      MatchOutcome.Rejected
    )
    assertNotEquals(
      outcome(
        strong("a", "J. R. R. Tolkien"),
        strong("b", "John Ronald Reuel Tolkien")
      ),
      MatchOutcome.Rejected
    )
  }

  test("a middle name on one side is neutral") {
    assertNotEquals(
      outcome(strong("a", "Alice B. Wong"), strong("b", "Alice Wong")),
      MatchOutcome.Rejected
    )
  }

  test("a DBLP homonym ordinal and a suffix do not split a person") {
    assertNotEquals(
      outcome(strong("a", "Wei Wang 0014"), strong("b", "Wei Wang")),
      MatchOutcome.Rejected
    )
    assertNotEquals(
      outcome(strong("a", "John Smith Jr."), strong("b", "John Smith")),
      MatchOutcome.Rejected
    )
  }

  test("apostrophe variants of a family name agree") {
    assertNotEquals(
      outcome(strong("a", "Sean O'Brien"), strong("b", "Sean O’Brien")),
      MatchOutcome.Rejected
    )
  }

  test("work: DOI prefix, case and title-markup variants are accepted") {
    val a = work(
      "w1",
      "<i>Graph</i> Resolution",
      externalIds = Map("doi" -> "https://doi.org/10.1000/ABC")
    )
    val b = work(
      "w2",
      "Graph resolution (extended abstract)",
      externalIds = Map("doi" -> "doi:10.1000/abc")
    )
    val r = decide(a, b)
    assertEquals(r.decision.decision, MatchOutcome.Accepted)
    assertEquals(r.path, MatchPath.ExactId)
  }

  test("work: a preprint and its published version are not rejected") {
    val preprint = work(
      "w1",
      "Attention Is All You Need Preprint Edition",
      Vector("Ashish Vaswani", "Noam Shazeer"),
      Some(2017),
      None,
      Map("arxiv" -> "1706.03762v5"),
      source = SourceType.Arxiv
    )
    val journal = work(
      "w2",
      "Attention Is All You Need Preprint Edition",
      Vector("Vaswani, Ashish", "Shazeer, Noam"),
      Some(2018),
      Some("NeurIPS"),
      Map("doi" -> "10.1000/neurips.1")
    )
    assertNotEquals(outcome(preprint, journal), MatchOutcome.Rejected)
    assertNotEquals(outcome(preprint, journal), MatchOutcome.Conflict)
  }

  test("work: a title typo with the same DOI is still accepted") {
    val a = work(
      "w1",
      "Graph Resolutoin Revisited",
      externalIds = Map("doi" -> "10.1000/xyz")
    )
    val b = work(
      "w2",
      "Graph Resolution Revisited",
      externalIds = Map("doi" -> "10.1000/xyz")
    )
    assertEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("work: author order and punctuation variants meet by rule") {
    val title = "Entity Resolution For Scholarly Graphs At Scale"
    val a = work("w1", title, Vector("Ada Lovelace", "Grace Hopper"))
    val b = work(
      "w2",
      title.toUpperCase + ".",
      Vector("Lovelace, Ada", "Hopper, Grace")
    )
    assertEquals(outcome(a, b), MatchOutcome.Accepted)
  }
