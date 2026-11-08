package scholarchain.er

import scholarchain.core.{ContractWire, MatchOutcome, SourceType}
import scholarchain.er.Fixtures.*
import scholarchain.er.matching.*

class MatcherSuite extends munit.FunSuite:
  private val matcher = Matcher(Policy.v1, runInfo)

  private def decide(a: SideRecord, b: SideRecord): ResolutionRecord =
    matcher.decide(MatchInput.of(a, b, "block-1"))

  private def outcome(a: SideRecord, b: SideRecord): MatchOutcome =
    decide(a, b).decision.decision

  private val sharedCoauthors = Vector("Alice Wong", "Bob Chen")

  test("two different ORCIDs on a pair are a CONFLICT, never ACCEPTED") {
    val a = mention(
      "m1",
      "Jane Doe",
      sharedCoauthors,
      Vector("MIT"),
      Map("orcid" -> validOrcidA)
    )
    val b = mention(
      "m2",
      "Jane Doe",
      sharedCoauthors,
      Vector("MIT"),
      Map("orcid" -> validOrcidB)
    )
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Conflict)
    assertEquals(record.path, MatchPath.ExactId)
    assert(record.decision.reasonCodes.contains("conflict.id:orcid"))
    assert(record.score > 0.9, "content alone would have merged this pair")
  }

  test("two different DOIs on a pair are a CONFLICT") {
    val a = work(
      "w1",
      "Deep residual learning for image recognition",
      Vector("Kaiming He"),
      externalIds = Map("doi" -> "10.1000/one")
    )
    val b = work(
      "w2",
      "Deep residual learning for image recognition",
      Vector("Kaiming He"),
      externalIds = Map("doi" -> "10.1000/two")
    )
    assertEquals(outcome(a, b), MatchOutcome.Conflict)
  }

  test("an agreeing and a disagreeing identifier is flagged as contradictory") {
    val a = work(
      "w1",
      "Some shared title of a paper",
      externalIds = Map("doi" -> "10.1000/one", "arxiv" -> "2101.00001")
    )
    val b = work(
      "w2",
      "Some shared title of a paper",
      externalIds = Map("doi" -> "10.1000/two", "arxiv" -> "2101.00001")
    )
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Conflict)
    assert(
      record.decision.reasonCodes.contains("conflict.contradictory-identifiers")
    )
  }

  test("an agreeing strong identifier is accepted on the exact-id path") {
    val a = mention("m1", "Jane Doe", externalIds = Map("orcid" -> validOrcidA))
    val b = mention(
      "m2",
      "J. Doe",
      externalIds = Map("orcid" -> s"https://orcid.org/$validOrcidA")
    )
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
    assertEquals(record.path, MatchPath.ExactId)
    assert(record.decision.rulesFired.contains("rule.id-agreement:orcid"))
  }

  test("a strong identifier agreement that content contradicts is a CONFLICT") {
    val a = mention("m1", "Jane Doe", externalIds = Map("orcid" -> validOrcidA))
    val b =
      mention("m2", "Zhang Wei", externalIds = Map("orcid" -> validOrcidA))
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Conflict)
    assert(
      record.decision.reasonCodes
        .contains("conflict.id-agreement-contradicted-by-content")
    )
  }

  test("a DOI agreement that the titles contradict is a CONFLICT") {
    val a = work(
      "w1",
      "Deep residual learning for image recognition",
      externalIds = Map("doi" -> "10.1000/one")
    )
    val b = work(
      "w2",
      "Medieval pottery trade networks",
      externalIds = Map("doi" -> "10.1000/one")
    )
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Conflict)
    assert(
      record.decision.reasonCodes
        .contains("conflict.id-agreement-contradicted-by-content")
    )
    val similar = work(
      "w3",
      "Deep residual learning for image recognition.",
      externalIds = Map("doi" -> "10.1000/one")
    )
    assertEquals(outcome(a, similar), MatchOutcome.Accepted)
  }

  test("an agreeing aggregator researcher ID never merges on its own") {
    val alone = decide(
      mention("m1", "Jane Doe", externalIds = Map("openalex" -> "A123")),
      mention("m2", "Jane Doe", externalIds = Map("openalex" -> "A123"))
    )
    assertEquals(alone.decision.decision, MatchOutcome.Unresolved)
    assert(alone.score >= Policy.v1.accept, "the ID raised the score")
    assert(alone.decision.reasonCodes.contains(Matcher.CapCorroboration))
    assert(
      alone.decision.reasonCodes.contains("id.aggregator-agree:openalex-author")
    )
    val mismatched = decide(
      mention("m1", "Jane Doe", externalIds = Map("openalex" -> "A123")),
      mention("m2", "Mary Major", externalIds = Map("openalex" -> "A123"))
    )
    assertEquals(mismatched.decision.decision, MatchOutcome.Rejected)
  }

  test("an agreeing aggregator ID plus corroboration is a scored acceptance") {
    val record = decide(
      mention(
        "m1",
        "Jane Doe",
        sharedCoauthors,
        Vector("MIT"),
        Map("openalex" -> "A123")
      ),
      mention(
        "m2",
        "Jane Doe",
        sharedCoauthors,
        Vector("MIT"),
        Map("openalex" -> "A123")
      )
    )
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
    assertEquals(record.path, MatchPath.Probabilistic)
  }

  test("differing aggregator researcher IDs lower the score, not conflict") {
    def pair(leftIds: Map[String, String], rightIds: Map[String, String]) =
      decide(
        mention("m1", "Jane Doe", sharedCoauthors, Vector("MIT"), leftIds),
        mention("m2", "Jane Doe", sharedCoauthors, Vector("MIT"), rightIds)
      )
    val baseline = pair(Map.empty, Map.empty)
    val openAlex = pair(Map("openalex" -> "A1"), Map("openalex" -> "A2"))
    val dblp = pair(Map("dblp-author" -> "w/1"), Map("dblp-author" -> "w/2"))
    Vector(openAlex, dblp).foreach { record =>
      assertNotEquals(record.decision.decision, MatchOutcome.Conflict)
      assertEquals(record.path, MatchPath.Probabilistic)
    }
    assertEqualsDouble(
      baseline.decision.features("score.logit") - openAlex.decision
        .features("score.logit"),
      1.5,
      1e-9
    )
    assert(
      openAlex.decision.reasonCodes
        .contains("id.aggregator-conflict:openalex-author")
    )
    assert(
      dblp.decision.reasonCodes.contains("id.aggregator-conflict:dblp-author")
    )
  }

  test("an ORCID agreement is not undone by differing aggregator IDs") {
    val record = decide(
      mention(
        "m1",
        "Jane Doe",
        externalIds = Map("orcid" -> validOrcidA, "openalex" -> "A1")
      ),
      mention(
        "m2",
        "Jane Doe",
        externalIds = Map("orcid" -> validOrcidA, "openalex" -> "A2")
      )
    )
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
    assertEquals(record.path, MatchPath.ExactId)
  }

  test("work DOI and arXiv disagreements still conflict") {
    val a = work("w1", "Some title", externalIds = Map("arxiv" -> "2101.00001"))
    val b = work("w2", "Some title", externalIds = Map("arxiv" -> "2102.00002"))
    assertEquals(outcome(a, b), MatchOutcome.Conflict)
  }

  test("an invalid identifier is recorded and never used to conflict") {
    val a = mention("m1", "Jane Doe", externalIds = Map("orcid" -> validOrcidA))
    val b = mention(
      "m2",
      "Jane Doe",
      externalIds = Map("orcid" -> "0000-0002-1825-0098")
    )
    val record = decide(a, b)
    assertNotEquals(record.decision.decision, MatchOutcome.Conflict)
    assert(record.decision.reasonCodes.exists(_.startsWith("id.invalid:orcid")))
  }

  test("source-local record keys never produce an identifier conflict") {
    val a = work(
      "w1",
      "Deep residual learning for image recognition",
      Vector("Kaiming He"),
      externalIds = Map("source-record-id" -> "a", "dblp-key" -> "k1")
    )
    val b = work(
      "w2",
      "Deep residual learning for image recognition",
      Vector("Kaiming He"),
      externalIds = Map("source-record-id" -> "b", "dblp-key" -> "k2")
    )
    assertEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("a preprint DOI and a journal DOI meet on the arXiv identifier") {
    val journal = work(
      "w1",
      "Some title",
      externalIds = Map("doi" -> "10.1000/journal", "arxiv" -> "2101.00001v2")
    )
    val preprint =
      work(
        "w2",
        "Some title",
        externalIds = Map("doi" -> "10.48550/arXiv.2101.00001")
      )
    val record = decide(journal, preprint)
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
    assertEquals(record.path, MatchPath.ExactId)
  }

  test("DOIs found in links are compared like keyed DOIs") {
    val a = work("w1", "T", links = Vector("https://doi.org/10.1000/A"))
    val b = work("w2", "T", externalIds = Map("doi" -> "10.1000/a"))
    assertEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("two mentions of one work are distinct people and are rejected") {
    val a = mention("m1", "Wei Wang", workId = Some("w"), position = 1)
    val b = mention("m2", "Wei Wang", workId = Some("w"), position = 2)
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Rejected)
    assertEquals(record.path, MatchPath.DeterministicBibliographic)
  }

  test("a family name far from equal is rejected by rule") {
    assertEquals(
      outcome(mention("m1", "John Smith"), mention("m2", "John Nguyen")),
      MatchOutcome.Rejected
    )
  }

  test("common-name trap: same name, no shared context, is not accepted") {
    val a = mention(
      "m1",
      "Wei Wang",
      Vector("Li Na", "Zhou Ping"),
      Vector("Tsinghua University")
    )
    val b = mention(
      "m2",
      "Wei Wang",
      Vector("Kim Jun", "Park Min"),
      Vector("University of Toronto"),
      venue = Some("venue-b")
    )
    assertEquals(outcome(a, b), MatchOutcome.Rejected)
  }

  test("a name match alone is never accepted, even when the score is forced") {
    val forced = Policy.v1.copy(researcherPrior = 8.0)
    val record = Matcher(forced, runInfo).decide(
      MatchInput.of(
        mention("m1", "Wei Wang"),
        mention("m2", "Wei Wang", workId = Some("other")),
        "b"
      )
    )
    assertEquals(record.decision.decision, MatchOutcome.Unresolved)
    assert(record.score >= forced.accept)
    assert(record.decision.reasonCodes.contains(Matcher.CapCorroboration))
  }

  test("name variants with strong shared context are accepted") {
    val a = mention(
      "m1",
      "John Smith",
      sharedCoauthors,
      Vector("MIT"),
      year = Some(2019)
    )
    val b = mention("m2", "J. Smith", sharedCoauthors, Vector("MIT"))
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
    assertEquals(record.path, MatchPath.Probabilistic)
    assert(record.score >= Policy.v1.accept)
  }

  test("a swapped name order is matched but flagged for review") {
    val a = mention("m1", "Wei Wang", sharedCoauthors, Vector("MIT"))
    val b = mention("m2", "Wang Wei", sharedCoauthors, Vector("MIT"))
    val record = decide(a, b)
    assert(record.decision.reasonCodes.contains("name.order-swapped"))
    assertNotEquals(record.decision.decision, MatchOutcome.Rejected)
  }

  test("one shared coauthor and no affiliation lands in the unresolved band") {
    val a = mention("m1", "John Smith", Vector("Alice Wong"))
    val b = mention("m2", "John Smith", Vector("Alice Wong"))
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Unresolved)
    assert(Policy.v1.inLlmBand(record.score), s"score ${record.score}")
    assert(
      record.decision.reasonCodes.contains("feature.missing:affiliation.compat")
    )
  }

  test("same title and authors across sources is accepted by rule") {
    val a = work(
      "w1",
      "Deep Residual Learning for Image Recognition",
      Vector("Kaiming He", "Xiangyu Zhang"),
      Some(2015)
    )
    val b = work(
      "w2",
      "Deep residual learning for image recognition.",
      Vector("He, Kaiming", "Zhang, Xiangyu"),
      Some(2016),
      source = SourceType.Crossref
    )
    val record = decide(a, b)
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
    assertEquals(record.path, MatchPath.DeterministicBibliographic)
  }

  test("a generic title is not accepted on title and year alone") {
    val a = work("w1", "Introduction")
    val b = work("w2", "Introduction")
    assertNotEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("the same long title with different authors is not accepted") {
    val a = work(
      "w1",
      "A survey of methods for the analysis of data",
      Vector("Ann Lee", "Bo Ray")
    )
    val b = work(
      "w2",
      "A survey of methods for the analysis of data",
      Vector("Carl Fox", "Dee Gray")
    )
    assertNotEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("unrelated titles are rejected") {
    val a = work("w1", "Graph neural networks: a review", Vector("Ann Lee"))
    val b = work("w2", "Attention is all you need", Vector("Carl Fox"))
    assertEquals(outcome(a, b), MatchOutcome.Rejected)
  }

  test("a decision does not depend on which side is listed first") {
    def pair(firstId: String, secondId: String) =
      (
        mention(
          firstId,
          "John Smith",
          sharedCoauthors,
          Vector("MIT"),
          title = "T1"
        ),
        mention(
          secondId,
          "J. Smith",
          sharedCoauthors,
          Vector("MIT"),
          title = "T2"
        )
      )
    val (a, b) = pair("m1", "m2")
    val (swappedA, swappedB) = pair("m2", "m1")
    val forward = matcher.decide(MatchInput.of(a, b, "x"))
    assertEquals(matcher.decide(MatchInput.of(b, a, "x")), forward)
    // The same two contents with their IDs exchanged put them on opposite
    // sides of the ordered pair; the features must not notice.
    val exchanged = matcher.decide(MatchInput.of(swappedA, swappedB, "x"))
    assertEquals(exchanged.decision.features, forward.decision.features)
    assertEquals(exchanged.decision.decision, forward.decision.decision)
    assertEquals(matcher.decide(MatchInput.of(a, b, "x")), forward)
  }

  test("every decision is a valid core MatchDecision with full provenance") {
    val record = decide(
      mention("m1", "John Smith", sharedCoauthors),
      mention("m2", "J. Smith", sharedCoauthors)
    )
    val decision = record.decision
    assertEquals(decision.policyVersion, Policy.v1.version)
    assertEquals(decision.runId, runId)
    assertEquals(decision.provenance.runId, runId)
    assertEquals(decision.sourceId, "m1")
    assertEquals(decision.targetId, "m2")
    assert(decision.reasonCodes.nonEmpty)
    assert(decision.rulesFired.head.startsWith("path:"))
    assert(decision.features.contains("score.logit"))
    assertEquals(decision.modelVersion, None)
    assertEquals(
      ContractWire.decode[scholarchain.core.MatchDecision](
        ContractWire.encode(decision)
      ),
      decision
    )
    assertEquals(
      upickle.default.read[ResolutionRecord](upickle.default.write(record)),
      record
    )
  }

  test("policy v1 score is frozen, so a weight edit must change the version") {
    val record = decide(
      mention("m1", "John Smith", Vector("Alice Wong")),
      mention("m2", "John Smith", Vector("Alice Wong"))
    )
    assertEquals(Policy.v1.version, "scholarchain.er.policy/1.4.0")
    assertEqualsDouble(record.decision.features("score.logit"), 2.3, 1e-9)
  }
