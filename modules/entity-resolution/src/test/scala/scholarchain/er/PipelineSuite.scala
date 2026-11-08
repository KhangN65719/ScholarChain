package scholarchain.er

import scholarchain.core.{
  ConfigLoader,
  ContractSchema,
  ContractWire,
  MatchOutcome,
  SourceType
}
import scholarchain.core.pairing.{BlockKind, BlockStrategy, MatchCandidate}
import scholarchain.er.Fixtures.*
import scholarchain.er.matching.*

class PipelineSuite extends munit.FunSuite:
  private val matcher = Matcher(Policy.v1, runInfo)

  private def candidate(
      leftId: String,
      rightId: String,
      block: String,
      kind: EntityKind = EntityKind.Researcher
  ): MatchCandidate =
    MatchCandidate(
      ContractSchema.currentVersion,
      MatchCandidate.recordTypeName,
      if kind == EntityKind.Work then BlockKind.Work else BlockKind.Researcher,
      leftId,
      rightId,
      SourceType.Dblp,
      SourceType.Dblp,
      block,
      BlockStrategy.NameYear,
      Vector.empty,
      Vector.empty,
      Map("name.exact" -> 1.0),
      provenance(SourceType.Derived, s"$leftId|$rightId")
    )

  test("the policy thresholds equal the shipped project configuration") {
    val yaml = scala.io.Source
      .fromResource("scholarchain-local.v1.yaml")
      .mkString
    val config = ConfigLoader.parse(yaml, Map.empty)
    assertEquals(Policy.v1.accept, config.matchAcceptThreshold)
    assertEquals(Policy.v1.reject, config.matchRejectThreshold)
    val derived = Policy.fromConfig(config)
    assertEquals(derived.accept, 0.95)
    assertEquals(derived.reject, 0.35)
    assert(
      derived.llmBandLow > derived.reject && derived.llmBandLow < derived.accept
    )
  }

  test("an inconsistent threshold set is refused") {
    intercept[IllegalArgumentException](Policy.v1.copy(reject = 0.8))
    intercept[IllegalArgumentException](Policy.v1.copy(llmBandLow = 0.99))
    intercept[IllegalArgumentException](Policy.v1.copy(version = " "))
  }

  test("the adjudication band is bounded below the accept threshold") {
    val policy = Policy.v1
    assert(policy.inLlmBand(policy.llmBandLow))
    assert(!policy.inLlmBand(policy.accept))
    assert(!policy.inLlmBand(policy.llmBandLow - 0.01))
    assert(policy.llmBandLow > policy.reject)
  }

  // Copied from core's MatchCandidateSuite, the frozen Hadoop wire example.
  private val frozen =
    """{"schemaVersion":1,"recordType":"scholarchain.hadoop.MatchCandidate/v1","kind":"researcher","leftId":"mention:v1:0000000000000000000000000000000000000000000000000000000000000001","rightId":"mention:v1:0000000000000000000000000000000000000000000000000000000000000002","leftSource":"dblp","rightSource":"openalex","blockKey":"researcher|name-initials|smith|j","blockStrategy":"name-initials","exactIdentifierAgreements":["orcid"],"exactIdentifierConflicts":["doi"],"features":{"name.exact":1,"title.jaccard":0.5,"coauthor.sharedCount":2},"provenance":{"schemaVersion":1,"source":"derived","snapshotId":"fixture-v1","sourceRecordId":"a|b","sourceUri":"scholarchain:block/researcher|name-initials|smith|j","transformVersion":"candidate-features/1","runId":"run:v1:550e8400-e29b-41d4-a716-446655440000"}}"""
  private val frozenLeft =
    "mention:v1:0000000000000000000000000000000000000000000000000000000000000001"
  private val frozenRight =
    "mention:v1:0000000000000000000000000000000000000000000000000000000000000002"

  test("a frozen Hadoop wire example parses and flows through the pipeline") {
    val parsed = ContractWire.decode[MatchCandidate](frozen)
    assertEquals(parsed.kind, BlockKind.Researcher)
    assertEquals(parsed.leftId, frozenLeft)
    assertEquals(parsed.blockKey, "researcher|name-initials|smith|j")
    assertEquals(parsed.exactIdentifierConflicts, Vector("doi"))
    assertEquals(ContractWire.encode(parsed), frozen)
    val shared = Vector("Alice Wong", "Bob Chen")
    val a = mention(frozenLeft, "John Smith", shared, Vector("MIT"))
    val b = mention(frozenRight, "J. Smith", shared, Vector("MIT"))
    val index = RecordIndex(Map(a.id -> a, b.id -> b))
    val records = matcher.resolve(Vector(parsed), index)
    assertEquals(records.size, 1)
    val record = records.head
    assertEquals(record.kind, EntityKind.Researcher)
    assertEquals(record.decision.sourceId, parsed.leftId)
    assertEquals(record.decision.targetId, parsed.rightId)
    assertEquals(record.decision.decision, MatchOutcome.Accepted)
    // Hadoop's own conflict list is not trusted: the "doi" entry here is a
    // researcher candidate and must not conflict the pair.
    assertNotEquals(record.decision.decision, MatchOutcome.Conflict)
  }

  test("a candidate with an unexpected record type is left unresolved") {
    val parsed = ContractWire.decode[MatchCandidate](frozen)
    val foreign = parsed.copy(recordType = "something.else/v9")
    val record = matcher.resolve(Vector(foreign), RecordIndex(Map.empty)).head
    assertEquals(record.decision.decision, MatchOutcome.Unresolved)
    assert(record.decision.reasonCodes.contains(Matcher.InvalidCandidate))
  }

  test("core records build an index whose works resolve by identifier") {
    val a = coreWork(
      "dblp",
      "journals/x/1",
      "Deep residual learning",
      Vector("Kaiming He", "Jian Sun"),
      Some("10.1000/abc")
    )
    val b = coreWork(
      "crossref",
      "10.1000/ABC",
      "Deep Residual Learning",
      Vector("He, Kaiming", "Sun, Jian"),
      Some("https://doi.org/10.1000/ABC"),
      SourceType.Crossref
    )
    val index = RecordIndex.fromCore(Vector(a, b))
    assertEquals(index.size, 6)
    val pair = if a.workId < b.workId then (a, b) else (b, a)
    val records = matcher.resolve(
      Vector(candidate(pair(0).workId, pair(1).workId, "k", EntityKind.Work)),
      index
    )
    assertEquals(
      records.map(_.decision.decision),
      Vector(MatchOutcome.Accepted)
    )
    assertEquals(records.head.path, MatchPath.ExactId)
    assertEquals(records.head.leftSourceRecordId, pair(0).sourceRecordId)
  }

  test(
    "a pair reached from several blocks is decided once, deterministically"
  ) {
    val a = coreWork("dblp", "a", "Title A", Vector("Ann Lee"), None)
    val b = coreWork("dblp", "b", "Title B", Vector("Bob Ray"), None)
    val index = RecordIndex.fromCore(Vector(a, b))
    val (x, y) = if a.workId < b.workId then (a, b) else (b, a)
    val blocks = Vector(
      candidate(x.workId, y.workId, "z-block", EntityKind.Work),
      candidate(x.workId, y.workId, "a-block", EntityKind.Work)
    )
    val forward = matcher.resolve(blocks, index)
    val reversed = matcher.resolve(blocks.reverse, index)
    assertEquals(forward.size, 1)
    assertEquals(forward, reversed)
  }

  test("a candidate whose records are missing is left unresolved") {
    val index = RecordIndex.fromCore(Vector.empty)
    val record = matcher.resolve(Vector(candidate("m1", "m2", "k")), index).head
    assertEquals(record.decision.decision, MatchOutcome.Unresolved)
    assert(record.decision.reasonCodes.contains(Matcher.SideMissing))
    assertEquals(record.score, 0.0)
  }

  test("a candidate whose kind disagrees with its records is not decided") {
    val a = coreWork("dblp", "a", "Title A", Vector("Ann Lee"), None)
    val b = coreWork("dblp", "b", "Title B", Vector("Bob Ray"), None)
    val index = RecordIndex.fromCore(Vector(a, b))
    val (x, y) = if a.workId < b.workId then (a, b) else (b, a)
    val record = matcher
      .resolve(Vector(candidate(x.workId, y.workId, "k")), index)
      .head
    assert(record.decision.reasonCodes.contains(Matcher.SideMissing))
  }

  test("original characters survive matching; only keys are folded") {
    val a = mention("m1", "Zoë Müller", Vector("Álvaro Ñúñez"))
    val b = mention("m2", "Zoe Muller", Vector("Alvaro Nunez"))
    val record = matcher.decide(MatchInput.of(a, b, "k"))
    assertEquals(a.name, "Zoë Müller")
    assertEquals(a.authorNames, Vector("Zoë Müller", "Álvaro Ñúñez"))
    assertEquals(record.decision.features("f.name.family"), 1.0)
    assertEquals(record.decision.features("f.name.forename"), 1.0)
  }
