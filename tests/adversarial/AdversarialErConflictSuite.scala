package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.core.{MatchOutcome, SourceType}
import scholarchain.er.matching.*
import scholarchain.tests.adversarial.ErFixtures.*

/** Gate: exact-ID conflicts never silently merge. */
class AdversarialErConflictSuite extends FunSuite:

  private val coauthors = Vector("Alice Wong", "Bo Chen")

  test("two different valid ORCIDs on identical people are a CONFLICT") {
    val a = person(
      "a",
      "Ada Lovelace",
      coauthors,
      Vector("MIT"),
      Map("orcid" -> orcidA)
    )
    val b = person(
      "b",
      "Ada Lovelace",
      coauthors,
      Vector("MIT"),
      Map("orcid" -> orcidB)
    )
    val r = decide(a, b)
    assertEquals(r.decision.decision, MatchOutcome.Conflict)
    assertEquals(r.path, MatchPath.ExactId)
    assert(r.decision.reasonCodes.contains("conflict.id:orcid"))
  }

  test("the conflict is symmetric in argument order") {
    val a = person("a", "Ada Lovelace", externalIds = Map("orcid" -> orcidA))
    val b = person("b", "Ada Lovelace", externalIds = Map("orcid" -> orcidB))
    assertEquals(outcome(a, b), MatchOutcome.Conflict)
    assertEquals(outcome(b, a), MatchOutcome.Conflict)
  }

  test("ORCID spelled with a URL prefix or lowercase X is the same iD") {
    val a = person(
      "a",
      "Ada Lovelace",
      externalIds = Map("orcid" -> s"https://orcid.org/$orcidX")
    )
    val b = person(
      "b",
      "Ada Lovelace",
      externalIds = Map("orcid" -> orcidX.toLowerCase)
    )
    assertEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test(
    "different DOIs on works with identical title, authors and year conflict"
  ) {
    val title = "A Sufficiently Long Title About Graph Resolution"
    val a = work(
      "w1",
      title,
      Vector("Ada Lovelace"),
      externalIds = Map("doi" -> "10.1000/aaa")
    )
    val b = work(
      "w2",
      title,
      Vector("Ada Lovelace"),
      externalIds = Map("doi" -> "10.1000/bbb")
    )
    assertEquals(outcome(a, b), MatchOutcome.Conflict)
  }

  test("DOI case and resolver prefix differences are not a conflict") {
    val a = work(
      "w1",
      "Graph Resolution Revisited Once More",
      Vector("Ada Lovelace"),
      externalIds = Map("doi" -> "https://doi.org/10.1000/ABC.1")
    )
    val b = work(
      "w2",
      "Graph Resolution Revisited Once More",
      Vector("Ada Lovelace"),
      externalIds = Map("doi" -> "DOI:10.1000/abc.1")
    )
    assertEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("a DOI list that overlaps agrees, and a disjoint list conflicts") {
    val overlap1 = work(
      "w1",
      "Overlap Case Title For Lists",
      links = Vector("doi:10.1000/one", "doi:10.1000/two", "doi:10.1000/two")
    )
    val overlap2 = work(
      "w2",
      "Overlap Case Title For Lists",
      links = Vector("https://doi.org/10.1000/TWO")
    )
    assertEquals(outcome(overlap1, overlap2), MatchOutcome.Accepted)
    val disjoint = work(
      "w3",
      "Overlap Case Title For Lists",
      links = Vector("doi:10.1000/three", "doi:10.1000/four")
    )
    assertEquals(outcome(overlap1, disjoint), MatchOutcome.Conflict)
  }

  test("a preprint DOI is an arXiv identifier, not a DOI that disagrees") {
    val preprint = work(
      "w1",
      "Attention Is All You Need Preprint Edition",
      Vector("Ashish Vaswani"),
      externalIds = Map("doi" -> "10.48550/arXiv.1706.03762")
    )
    val journal = work(
      "w2",
      "Attention Is All You Need Preprint Edition",
      Vector("Ashish Vaswani"),
      externalIds = Map("doi" -> "10.1000/journal.1")
    )
    assertNotEquals(outcome(preprint, journal), MatchOutcome.Conflict)
    val arxivSide = work(
      "w3",
      "Attention Is All You Need Preprint Edition",
      externalIds = Map("arxiv" -> "1706.03762v7")
    )
    assertEquals(outcome(preprint, arxivSide), MatchOutcome.Accepted)
    val otherArxiv = work(
      "w4",
      "Attention Is All You Need Preprint Edition",
      externalIds = Map("arxiv" -> "1706.03763")
    )
    assertEquals(outcome(preprint, otherArxiv), MatchOutcome.Conflict)
  }

  test("one agreeing and one disagreeing authoritative ID is a CONFLICT") {
    val a = work(
      "w1",
      "Mixed Evidence Title For Identifier Tests",
      externalIds = Map("doi" -> "10.1000/same", "arxiv" -> "1706.03762")
    )
    val b = work(
      "w2",
      "Mixed Evidence Title For Identifier Tests",
      externalIds = Map("doi" -> "10.1000/same", "arxiv" -> "1805.00001")
    )
    val r = decide(a, b)
    assertEquals(r.decision.decision, MatchOutcome.Conflict)
    assert(
      r.decision.reasonCodes.contains("conflict.contradictory-identifiers")
    )
  }

  test("a strong ID agreement contradicted by the names is a CONFLICT") {
    val a = person("a", "Alice Smith", externalIds = Map("orcid" -> orcidA))
    val b = person("b", "Zhao Wei", externalIds = Map("orcid" -> orcidA))
    assertEquals(outcome(a, b), MatchOutcome.Conflict)
  }

  test("an aggregator ID agreement is never accepted against the names") {
    val a = person("a", "Alice Smith", externalIds = Map("openalex" -> "A123"))
    val b = person("b", "Zhao Wei", externalIds = Map("openalex" -> "a123"))
    assertNotEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("differing aggregator author IDs are evidence, not a CONFLICT") {
    val a = person("a", "Alice Smith", externalIds = Map("openalex" -> "A123"))
    val b = person("b", "Alice Smith", externalIds = Map("openalex" -> "A124"))
    assertNotEquals(outcome(a, b), MatchOutcome.Conflict)
    assertNotEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("an ORCID with a bad check digit is dropped, never matched on") {
    val bad = "0000-0002-1825-0098"
    val a = person("a", "Alice Smith", externalIds = Map("orcid" -> bad))
    val b = person("b", "Zhao Wei", externalIds = Map("orcid" -> bad))
    val r = decide(a, b)
    assertNotEquals(r.decision.decision, MatchOutcome.Accepted)
    assert(r.decision.reasonCodes.contains(s"id.invalid:orcid:$bad"))
  }

  test(
    "an invalid ORCID against a valid different one is silence, not conflict"
  ) {
    val a = person(
      "a",
      "Alice Smith",
      externalIds = Map("orcid" -> "0000-0002-1825-0098")
    )
    val b = person("b", "Alice Smith", externalIds = Map("orcid" -> orcidA))
    assertNotEquals(outcome(a, b), MatchOutcome.Conflict)
    assertNotEquals(decide(a, b).path, MatchPath.ExactId)
  }

  test("a source-local key is not authoritative, so it cannot conflict") {
    val a = person("a", "Alice Smith", externalIds = Map("dblp" -> "k/Smith:A"))
    val b = person("b", "Alice Smith", externalIds = Map("dblp" -> "k/Smith:B"))
    assertNotEquals(outcome(a, b), MatchOutcome.Conflict)
  }

  test("ORCID on only one side is silence, not a conflict or an accept") {
    val a = person("a", "Alice Smith", externalIds = Map("orcid" -> orcidA))
    val b = person("b", "Alice Smith")
    val r = decide(a, b)
    assertNotEquals(r.decision.decision, MatchOutcome.Conflict)
    assertNotEquals(r.path, MatchPath.ExactId)
  }

  test("a conflict outranks every later accept rule on a source mix") {
    val a = person(
      "a",
      "Ada Lovelace",
      Vector("Alice Wong", "Bo Chen"),
      Vector("MIT"),
      Map("orcid" -> orcidA),
      source = SourceType.Orcid
    )
    val b = person(
      "b",
      "Ada Lovelace",
      Vector("Alice Wong", "Bo Chen"),
      Vector("MIT"),
      Map("orcid" -> orcidB),
      source = SourceType.OpenAlex
    )
    assertEquals(outcome(a, b), MatchOutcome.Conflict)
  }
