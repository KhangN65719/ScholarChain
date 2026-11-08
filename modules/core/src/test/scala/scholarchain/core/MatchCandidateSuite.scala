package scholarchain.core

import munit.FunSuite
import scholarchain.core.pairing.{BlockKind, BlockStrategy, MatchCandidate}

class MatchCandidateSuite extends FunSuite:
  private val frozen =
    """{"schemaVersion":1,"recordType":"scholarchain.hadoop.MatchCandidate/v1","kind":"researcher","leftId":"mention:v1:0000000000000000000000000000000000000000000000000000000000000001","rightId":"mention:v1:0000000000000000000000000000000000000000000000000000000000000002","leftSource":"dblp","rightSource":"openalex","blockKey":"researcher|name-initials|smith|j","blockStrategy":"name-initials","exactIdentifierAgreements":["orcid"],"exactIdentifierConflicts":["doi"],"features":{"name.exact":1,"title.jaccard":0.5,"coauthor.sharedCount":2},"provenance":{"schemaVersion":1,"source":"derived","snapshotId":"fixture-v1","sourceRecordId":"a|b","sourceUri":"scholarchain:block/researcher|name-initials|smith|j","transformVersion":"candidate-features/1","runId":"run:v1:550e8400-e29b-41d4-a716-446655440000"}}"""

  private def withField(json: String, from: String, to: String): String =
    require(json.contains(from), s"fixture lacks $from")
    json.replace(from, to)

  private def decode(json: String): MatchCandidate =
    ContractWire.decode[MatchCandidate](json)

  test("frozen Hadoop wire example decodes and re-encodes byte for byte") {
    val candidate = decode(frozen)
    assertEquals(candidate.recordType, MatchCandidate.recordTypeName)
    assertEquals(candidate.kind, BlockKind.Researcher)
    assertEquals(candidate.blockStrategy, BlockStrategy.NameInitials)
    assertEquals(candidate.leftSource, SourceType.Dblp)
    assertEquals(candidate.rightSource, SourceType.OpenAlex)
    assertEquals(candidate.exactIdentifierAgreements, Vector("orcid"))
    assertEquals(candidate.exactIdentifierConflicts, Vector("doi"))
    assertEquals(candidate.features("title.jaccard"), 0.5)
    assertEquals(ContractWire.encode(candidate), frozen)
  }

  test("every block kind and strategy round trips with its wire string") {
    val candidate = decode(frozen)
    for kind <- BlockKind.values; strategy <- BlockStrategy.values do
      val changed = candidate.copy(kind = kind, blockStrategy = strategy)
      val json = ContractWire.encode(changed)
      assert(json.contains(s""""kind":"${kind.wire}""""))
      assert(json.contains(s""""blockStrategy":"${strategy.wire}""""))
      assertEquals(decode(json), changed)
  }

  test("unknown fields are ignored on read") {
    val extended =
      withField(frozen, """"features":""", """"extra":{"x":1},"features":""")
    assertEquals(decode(extended), decode(frozen))
  }

  test("a missing required field is rejected") {
    val missing = withField(
      frozen,
      """"blockKey":"researcher|name-initials|smith|j",""",
      ""
    )
    assert(Rejection.textOf(decode(missing)).nonEmpty)
  }

  test("an unsupported schema version is rejected") {
    val future = withField(
      frozen,
      """{"schemaVersion":1,"recordType""",
      """{"schemaVersion":2,"recordType"""
    )
    assert(
      Rejection.textOf(decode(future)).contains("Unsupported schema version")
    )
  }

  test("endpoints must be distinct and in ascending order") {
    val candidate = decode(frozen)
    val ordering = "ascending ID order"
    assert(
      Rejection
        .textOf(
          candidate.copy(leftId = candidate.rightId, rightId = candidate.leftId)
        )
        .contains(ordering)
    )
    assert(
      Rejection
        .textOf(candidate.copy(rightId = candidate.leftId))
        .contains(ordering)
    )
    val reversed = withField(
      frozen,
      """"leftId":"mention:v1:0000000000000000000000000000000000000000000000000000000000000001","rightId":"mention:v1:0000000000000000000000000000000000000000000000000000000000000002"""",
      """"leftId":"mention:v1:0000000000000000000000000000000000000000000000000000000000000002","rightId":"mention:v1:0000000000000000000000000000000000000000000000000000000000000001""""
    )
    assert(Rejection.textOf(decode(reversed)).contains(ordering))
  }

  test("non-finite features are rejected") {
    val candidate = decode(frozen)
    for bad <- Seq(Double.NaN, Double.PositiveInfinity) do
      assert(
        Rejection
          .textOf(candidate.copy(features = Map("name.exact" -> bad)))
          .contains("Features must be finite")
      )
  }

  test("a null provenance is rejected") {
    val candidate = decode(frozen)
    assert(
      Rejection
        .textOf(candidate.copy(provenance = null))
        .contains("must carry provenance")
    )
  }

  test("unknown block kind and strategy values are rejected") {
    val kind =
      withField(frozen, """"kind":"researcher"""", """"kind":"venue"""")
    assert(Rejection.textOf(decode(kind)).contains("Unknown block kind: venue"))
    val strategy = withField(
      frozen,
      """"blockStrategy":"name-initials"""",
      """"blockStrategy":"soundex""""
    )
    assert(
      Rejection.textOf(decode(strategy)).contains("Unknown strategy: soundex")
    )
  }

  test("an unknown source type is rejected") {
    val source =
      withField(frozen, """"leftSource":"dblp"""", """"leftSource":"mystery"""")
    assert(
      Rejection.textOf(decode(source)).contains("Unsupported enum value")
    )
  }
