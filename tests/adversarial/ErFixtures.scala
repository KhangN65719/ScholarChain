package scholarchain.tests.adversarial

import java.time.Instant
import scholarchain.core.{
  ContractSchema,
  MatchDecision,
  MatchOutcome,
  Provenance,
  SourceType,
  StableIds
}
import scholarchain.er.matching.*

/** Independent builders for entity-resolution pairs. They are written from the
  * `SideRecord` contract and share nothing with the module's own fixtures.
  */
object ErFixtures:
  val runId: String = StableIds.newRun()
  val run: RunInfo = RunInfo(runId, Instant.parse("2026-11-07T00:00:00Z"))
  val policy: Policy = Policy.v1
  val matcher: Matcher = Matcher(policy, run)

  val orcidA = "0000-0002-1825-0097"
  val orcidB = "0000-0001-5109-3700"
  val orcidX = "0000-0002-1694-233X"

  private val topics = Vector(
    "Quantum lattice chromodynamics methods",
    "Medieval pottery trade networks",
    "Protein folding energy landscapes",
    "Urban traffic flow forecasting"
  )

  def person(
      id: String,
      name: String,
      coauthors: Vector[String] = Vector.empty,
      affiliations: Vector[String] = Vector.empty,
      externalIds: Map[String, String] = Map.empty,
      title: String = "",
      year: Option[Int] = Some(2020),
      venue: Option[String] = Some("venue-a"),
      source: SourceType = SourceType.Dblp,
      workId: Option[String] = None
  ): SideRecord =
    SideRecord(
      id = id,
      kind = EntityKind.Researcher,
      source = source,
      sourceRecordId = s"rec-$id",
      sourceUri = s"https://example.org/$id",
      snapshotId = "snap-1",
      workId = workId.getOrElse(s"work-of-$id"),
      name = name,
      title =
        if title.nonEmpty then title
        else topics(Math.floorMod(id.hashCode, topics.size)),
      year = year,
      venue = venue,
      affiliations = affiliations,
      authorNames = name +: coauthors,
      authorPosition = Some(1),
      externalIds = externalIds,
      links = Vector.empty,
      citationNeighbors = Vector.empty
    )

  def work(
      id: String,
      title: String,
      authors: Vector[String] = Vector.empty,
      year: Option[Int] = Some(2020),
      venue: Option[String] = Some("venue-a"),
      externalIds: Map[String, String] = Map.empty,
      links: Vector[String] = Vector.empty,
      source: SourceType = SourceType.Dblp
  ): SideRecord =
    SideRecord(
      id = id,
      kind = EntityKind.Work,
      source = source,
      sourceRecordId = s"rec-$id",
      sourceUri = s"https://example.org/$id",
      snapshotId = "snap-1",
      workId = id,
      name = "",
      title = title,
      year = year,
      venue = venue,
      affiliations = Vector.empty,
      authorNames = authors,
      authorPosition = None,
      externalIds = externalIds,
      links = links,
      citationNeighbors = Vector.empty
    )

  def decide(a: SideRecord, b: SideRecord): ResolutionRecord =
    matcher.decide(MatchInput.of(a, b, "k"))

  def outcome(a: SideRecord, b: SideRecord): MatchOutcome =
    decide(a, b).decision.decision

  def provenance(snapshot: String = "snap-1"): Provenance =
    Provenance(
      ContractSchema.currentVersion,
      SourceType.Derived,
      snapshot,
      "x|y",
      "scholarchain:er/test",
      "test-1",
      runId
    )

  /** A hand-built matcher output, so evaluation can be checked without the
    * matcher choosing the answers.
    */
  def record(
      kind: EntityKind,
      left: String,
      right: String,
      outcome: MatchOutcome,
      path: MatchPath,
      score: Double,
      reasons: Vector[String] = Vector.empty
  ): ResolutionRecord =
    ResolutionRecord(
      ContractSchema.currentVersion,
      kind,
      path,
      s"rec-$left",
      s"rec-$right",
      MatchDecision(
        schemaVersion = ContractSchema.currentVersion,
        sourceId = left,
        targetId = right,
        features = Map.empty,
        rulesFired = Vector(s"path:${path.wire}"),
        decision = outcome,
        confidence = score,
        reasonCodes =
          if reasons.isEmpty then Vector("test.fixture") else reasons,
        policyVersion = policy.version,
        modelVersion = None,
        promptVersion = None,
        decidedAt = run.decidedAtText,
        runId = runId,
        provenance = provenance()
      )
    )
