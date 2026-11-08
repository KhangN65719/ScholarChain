package scholarchain.er.review

import scholarchain.core.{ContractSchema, MatchOutcome}
import scholarchain.er.llm.Digests
import scholarchain.er.matching.{
  EntityKind,
  MatchPath,
  RecordIndex,
  ResolutionRecord,
  SideRecord
}
import upickle.default.ReadWriter

/** What a human reviewer sees for one side of a pair. It carries the source
  * record ID, URI and snapshot so every label traces back to the source, and it
  * carries no matcher output.
  */
final case class ReviewSide(
    id: String,
    sourceRecordId: String,
    sourceUri: String,
    snapshotId: String,
    source: String,
    name: String,
    title: String,
    year: Option[Int],
    venue: Option[String],
    affiliations: Vector[String],
    authorNames: Vector[String],
    externalIds: Map[String, String]
) derives ReadWriter

object ReviewSide:
  def of(side: SideRecord): ReviewSide =
    ReviewSide(
      side.id,
      side.sourceRecordId,
      side.sourceUri,
      side.snapshotId,
      side.source.wire,
      side.name,
      side.title,
      side.year,
      side.venue,
      side.affiliations,
      side.authorNames,
      side.externalIds
    )

/** A pair for human labeling. The matcher's decision and score are left out on
  * purpose so a reviewer is not anchored by them; they are kept in the separate
  * [[ReviewSelection]] record, joined by `itemId`.
  */
final case class ReviewItem(
    schemaVersion: ContractSchema.Version,
    itemId: String,
    kind: EntityKind,
    left: ReviewSide,
    right: ReviewSide
) derives ReadWriter

/** Why a pair was queued, kept apart from the item shown to the reviewer. */
final case class ReviewSelection(
    schemaVersion: ContractSchema.Version,
    itemId: String,
    stratum: String,
    outcome: String,
    path: String,
    score: Double,
    policyVersion: String
) derives ReadWriter

final case class ReviewQueue(
    items: Vector[ReviewItem],
    selections: Vector[ReviewSelection]
)

/** Sampling plan. Every stratum is capped, and within a stratum pairs are taken
  * in order of a salted hash of the pair, so a rerun with the same salt picks
  * the same pairs and a different salt picks an independent sample.
  */
final case class ReviewPlan(
    salt: String,
    perStratum: Int,
    nearThresholdMargin: Double,
    acceptThreshold: Double,
    rejectThreshold: Double
):
  require(perStratum > 0, "Per-stratum cap must be positive")
  require(salt.trim.nonEmpty, "A sampling salt is required")

object ReviewQueue:
  val ConflictStratum = "conflict"
  val UnresolvedStratum = "unresolved"
  val LlmStratum = "llm-adjudicated"
  val AggregatorDisagreementStratum = "aggregator-id-disagreement"
  val NearAcceptStratum = "near-accept-threshold"
  val NearRejectStratum = "near-reject-threshold"

  def itemId(kind: EntityKind, leftId: String, rightId: String): String =
    "review:v1:" +
      Digests.sha256Hex(Vector(kind.wire, leftId, rightId)).take(32)

  private def stratumOf(
      record: ResolutionRecord,
      plan: ReviewPlan
  ): String =
    val decision = record.decision
    decision.decision match
      case MatchOutcome.Conflict => ConflictStratum
      case _
          if decision.reasonCodes.exists(
            _.startsWith("id.aggregator-conflict")
          ) =>
        AggregatorDisagreementStratum
      case MatchOutcome.Unresolved                      => UnresolvedStratum
      case _ if record.path == MatchPath.LlmAdjudicated => LlmStratum
      case MatchOutcome.Accepted
          if record.path == MatchPath.Probabilistic &&
            record.score - plan.acceptThreshold < plan.nearThresholdMargin =>
        NearAcceptStratum
      case MatchOutcome.Rejected
          if record.path == MatchPath.Probabilistic &&
            plan.rejectThreshold - record.score < plan.nearThresholdMargin =>
        NearRejectStratum
      case MatchOutcome.Accepted =>
        s"control-accepted-${record.path.wire}"
      case MatchOutcome.Rejected =>
        s"control-rejected-${record.path.wire}"

  /** Builds the queue. Controls (confident accepts and rejects from every path)
    * are sampled too, because a queue of only hard cases would make precision
    * look worse and recall look better than they are.
    */
  def build(
      records: Vector[ResolutionRecord],
      index: RecordIndex,
      plan: ReviewPlan
  ): ReviewQueue =
    val picked = records
      .groupBy(record => (record.kind, stratumOf(record, plan)))
      .toVector
      .sortBy((key, _) => (key._1.wire, key._2))
      .flatMap { case ((kind, stratum), group) =>
        group
          .sortBy(record =>
            Digests.sha256Hex(
              Vector(plan.salt, kind.wire, record.leftId, record.rightId)
            )
          )
          .take(plan.perStratum)
          .map(record => (record, stratum))
      }
    val rows = picked.flatMap { case (record, stratum) =>
      for
        left <- index.get(record.leftId)
        right <- index.get(record.rightId)
      yield (
        ReviewItem(
          ContractSchema.currentVersion,
          itemId(record.kind, record.leftId, record.rightId),
          record.kind,
          ReviewSide.of(left),
          ReviewSide.of(right)
        ),
        ReviewSelection(
          ContractSchema.currentVersion,
          itemId(record.kind, record.leftId, record.rightId),
          stratum,
          record.decision.decision.wire,
          record.path.wire,
          record.score,
          record.decision.policyVersion
        )
      )
    }
    ReviewQueue(rows.map(_._1), rows.map(_._2))
