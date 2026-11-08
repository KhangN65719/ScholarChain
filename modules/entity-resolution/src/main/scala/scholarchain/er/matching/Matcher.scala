package scholarchain.er.matching

import scholarchain.core.{ContractSchema, MatchDecision, MatchOutcome}
import scholarchain.core.{Provenance, SourceType}
import scholarchain.core.pairing.MatchCandidate

/** Decides a pair from its two records and the policy, and from nothing else.
  *
  * The decision ladder has seven rungs and runs in a fixed order; the first
  * rung that applies wins:
  *
  *   - Rung 1, ID conflict: authoritative identifiers that disagree give
  *     CONFLICT. Nothing later can override it, so a conflicting pair is never
  *     merged.
  *   - Rung 2, position or same-record CONFLICT: an agreeing ORCID on two
  *     mentions that are distinct authors of one work is a CONFLICT, and so is
  *     a content-supported aggregator ID when the work is linked by a shared
  *     DOI or arXiv ID. "Distinct authors of one work" means either two
  *     mentions of one work record (equal work ID, different known author
  *     positions, with or without any DOI or arXiv ID), or works that share a
  *     DOI or arXiv ID with the mentions at different positions of one aligned
  *     author list (see [[AuthorSlots]]).
  *   - Rung 3, ID agreement: identifiers that agree give an exact-identifier
  *     decision. A strong identifier is accepted unless the record content
  *     contradicts it, in which case the pair is a CONFLICT. An aggregator
  *     identifier is accepted only if the content supports it.
  *   - Rung 4, distinct mentions: two mentions of one work record, or of works
  *     linked by a shared DOI or arXiv ID at different positions of one aligned
  *     list, are distinct people and are rejected.
  *   - Rung 5, family name: a family name far from equal is a rejection.
  *   - Rung 6, same-title accept: a work pair with the same title, authors and
  *     a compatible year or venue is accepted by rule.
  *   - Rung 7, scoring: everything else is scored, accepted at or above the
  *     accept threshold when its gates hold, rejected below the reject
  *     threshold, and otherwise left unresolved.
  */
final class Matcher(policy: Policy, run: RunInfo):
  import Matcher.*

  def decide(input: MatchInput): ResolutionRecord =
    val left = input.left
    val right = input.right
    val leftIds = left.identifiers
    val rightIds = right.identifiers
    val allIds = Identifiers.compare(leftIds, rightIds)
    val ids = allIds.authoritative
    val aggregator = allIds.aggregator
    val features = input.kind match
      case EntityKind.Researcher =>
        Features.researcher(left, right, policy, aggregator)
      case EntityKind.Work => Features.work(left, right, policy)
    val byName = features.map(feature => feature.name -> feature).toMap
    val prior =
      if input.kind == EntityKind.Researcher then policy.researcherPrior
      else policy.workPrior
    val logit = Features.clamp(prior + features.map(_.llr).sum)
    val score = Features.probability(logit)
    val notes = (leftIds.invalid ++ rightIds.invalid).distinct
      .map(value => s"id.invalid:$value") ++
      aggregator.agreements.map(ns => s"id.aggregator-agree:${ns.wire}") ++
      aggregator.conflicts.map(ns => s"id.aggregator-conflict:${ns.wire}") ++
      byName.get("name.orderSwapped").map(_ => "name.order-swapped").toVector
    val verdict =
      ladder(input, byName, ids, aggregator, score).addReasons(notes)
    val withGaps =
      if verdict.outcome == MatchOutcome.Unresolved ||
        verdict.outcome == MatchOutcome.Conflict
      then verdict.addReasons(missingFeatureCodes(input.kind, byName))
      else verdict
    build(input, features, prior, logit, withGaps)

  def decideAll(inputs: Iterable[MatchInput]): Vector[ResolutionRecord] =
    inputs.iterator.map(decide).toVector

  /** Decides every candidate pair. A pair reached from several blocks is
    * decided once, under the smallest block key, so the output is the same
    * whatever order the blocks were delivered in. A pair whose records cannot
    * be found is left unresolved rather than guessed.
    */
  def resolve(
      candidates: Iterable[MatchCandidate],
      index: RecordIndex
  ): Vector[ResolutionRecord] =
    candidates.toVector
      .sortBy(candidate =>
        (candidate.leftId, candidate.rightId, candidate.blockKey)
      )
      .distinctBy(candidate => (candidate.leftId, candidate.rightId))
      .map { candidate =>
        val kind = candidate.kind.entityKind
        if candidate.recordType != MatchCandidate.recordTypeName then
          unresolvedInput(candidate, InvalidCandidate)
        else
          (index.get(candidate.leftId), index.get(candidate.rightId)) match
            case (Some(left), Some(right))
                if left.kind == kind && right.kind == kind &&
                  left.id != right.id =>
              decide(MatchInput.of(left, right, candidate.blockKey))
            case _ => unresolvedInput(candidate, SideMissing)
      }

  private def ladder(
      input: MatchInput,
      byName: Map[String, Feature],
      ids: IdComparison,
      aggregator: IdComparison,
      score: Double
  ): Verdict =
    val family = byName.get(Features.nameFamily).map(_.value)
    val forename = byName.get(Features.nameForename).map(_.value)
    val title = byName.get(Features.titleSimilarity).map(_.value)
    val researcher = input.kind == EntityKind.Researcher
    val contradicts =
      if researcher then family.exists(_ < policy.contentConflictFamily)
      else title.exists(_ < policy.contentConflictTitle)
    val supports =
      if researcher then
        family.exists(_ >= policy.familyNearMatchBelow) &&
        forename.forall(_ >= 0.7)
      else title.exists(_ >= 0.5)
    val position =
      if researcher then
        AuthorSlots.distinctMentionsOfOneWork(input.left, input.right)
      else None
    val sameRecord =
      researcher && AuthorSlots.distinctSlotsOfOneRecord(
        input.left,
        input.right
      )
    val positionRule: Option[String] =
      position.map(_.ruleCode).orElse(Option.when(sameRecord)(SameRecordRule))
    val agreeingByPosition =
      if position.isDefined then
        ids.agreements ++ (if supports then aggregator.agreements
                           else Vector.empty)
      else if sameRecord then ids.agreements
      else Vector.empty
    if ids.conflicts.nonEmpty then
      Verdict(
        MatchOutcome.Conflict,
        MatchPath.ExactId,
        score,
        Vector("rule.id-conflict"),
        ids.conflicts.map(ns => s"conflict.id:${ns.wire}") ++
          Option
            .when(ids.agreements.nonEmpty)("conflict.contradictory-identifiers")
            .toVector
      )
    else if positionRule.isDefined && agreeingByPosition.nonEmpty then
      Verdict(
        MatchOutcome.Conflict,
        MatchPath.ExactId,
        score,
        agreeingByPosition.map(ns => s"rule.id-agreement:${ns.wire}") :+
          positionRule.get,
        Vector(IdAgreementContradictedByPosition)
      )
    else if ids.agreements.nonEmpty then
      val names = ids.agreements.map(_.wire)
      val rules = names.map(name => s"rule.id-agreement:$name")
      if ids.strongAgreement then
        if contradicts then
          Verdict(
            MatchOutcome.Conflict,
            MatchPath.ExactId,
            score,
            rules,
            Vector("conflict.id-agreement-contradicted-by-content")
          )
        else
          Verdict(
            MatchOutcome.Accepted,
            MatchPath.ExactId,
            StrongIdConfidence,
            rules,
            names.map(name => s"accepted.id-agreement:$name")
          )
      else if supports then
        Verdict(
          MatchOutcome.Accepted,
          MatchPath.ExactId,
          AggregatorIdConfidence,
          rules,
          names.map(name => s"accepted.id-agreement:$name")
        )
      else
        Verdict(
          MatchOutcome.Unresolved,
          MatchPath.ExactId,
          score,
          rules,
          Vector("unresolved.id-agreement-unsupported-by-content")
        )
    else if researcher && input.left.workId == input.right.workId then
      Verdict(
        MatchOutcome.Rejected,
        MatchPath.DeterministicBibliographic,
        RuleRejectConfidence,
        Vector("rule.same-work-distinct-mention"),
        Vector("rejected.same-work-distinct-mention")
      )
    else if position.isDefined then
      Verdict(
        MatchOutcome.Rejected,
        MatchPath.DeterministicBibliographic,
        RuleRejectConfidence,
        Vector(position.get.ruleCode),
        Vector(position.get.reasonCode)
      )
    else if researcher && family.exists(_ < policy.familyRejectBelow) then
      Verdict(
        MatchOutcome.Rejected,
        MatchPath.DeterministicBibliographic,
        RuleRejectConfidence,
        Vector("rule.family-name-mismatch"),
        Vector("rejected.family-name-mismatch")
      )
    else if !researcher && sameBibliographicWork(input) then
      Verdict(
        MatchOutcome.Accepted,
        MatchPath.DeterministicBibliographic,
        RuleAcceptConfidence,
        Vector("rule.same-title-authors-year"),
        Vector("accepted.same-title-authors-year")
      )
    else probabilistic(input, byName, score)

  private def sameBibliographicWork(input: MatchInput): Boolean =
    val left = input.left
    val right = input.right
    val titleLeft = Text.titleKey(left.title)
    val authorsLeft = left.authorNames.map(Names.parse(_).family)
    val authorsRight = right.authorNames.map(Names.parse(_).family)
    val timeCompatible =
      (left.year, right.year) match
        case (Some(a), Some(b)) => math.abs(a - b) <= 1
        case _                  =>
          left.venue.map(Text.fold).filter(_.nonEmpty).isDefined &&
          left.venue.map(Text.fold) == right.venue.map(Text.fold)
    titleLeft.length >= policy.deterministicTitleMinLength &&
    titleLeft == Text.titleKey(right.title) &&
    authorsLeft.nonEmpty && authorsLeft.forall(_.nonEmpty) &&
    authorsLeft == authorsRight && timeCompatible

  private def probabilistic(
      input: MatchInput,
      byName: Map[String, Feature],
      score: Double
  ): Verdict =
    def positive(name: String): Boolean = byName.get(name).exists(_.llr > 0)
    val researcher = input.kind == EntityKind.Researcher
    val corroborated =
      if researcher then
        positive(Features.coauthorShared) ||
        positive(Features.affiliationCompat) ||
        positive(Features.citationShared) ||
        (positive(Features.titleSimilarity) &&
          byName.get(Features.authorOrder).exists(_.value == 1.0))
      else
        byName.get(Features.authorOverlap).exists(_.value >= 0.6) ||
        positive(Features.venueCompat) ||
        positive(Features.citationShared)
    val nameGate =
      !researcher ||
        (byName.get(Features.nameFamily).exists(_.value >= 0.97) &&
          byName.get(Features.nameForename).forall(_.value >= 0.7))
    val rules = Vector("rule.score-policy")
    if score >= policy.accept && corroborated && nameGate then
      Verdict(
        MatchOutcome.Accepted,
        MatchPath.Probabilistic,
        score,
        rules,
        Vector("accepted.score-at-or-above-accept")
      )
    else if score >= policy.accept then
      Verdict(
        MatchOutcome.Unresolved,
        MatchPath.Probabilistic,
        score,
        rules,
        Vector(
          if !nameGate then CapNameGate else CapCorroboration,
          "unresolved.score-capped-by-gate"
        )
      )
    else if score < policy.reject then
      Verdict(
        MatchOutcome.Rejected,
        MatchPath.Probabilistic,
        score,
        rules,
        Vector("rejected.score-below-reject")
      )
    else
      Verdict(
        MatchOutcome.Unresolved,
        MatchPath.Probabilistic,
        score,
        rules,
        Vector("unresolved.score-between-thresholds")
      )

  private def missingFeatureCodes(
      kind: EntityKind,
      byName: Map[String, Feature]
  ): Vector[String] =
    val watched =
      if kind == EntityKind.Researcher then
        Vector(
          Features.affiliationCompat,
          Features.coauthorShared,
          Features.nameForename,
          Features.citationShared
        )
      else
        Vector(
          Features.authorOverlap,
          Features.venueCompat,
          Features.citationShared
        )
    watched.filterNot(byName.contains).map(name => s"feature.missing:$name")

  private def provenanceFor(
      left: String,
      right: String,
      snapshot: String
  ): Provenance =
    Provenance(
      schemaVersion = ContractSchema.currentVersion,
      source = SourceType.Derived,
      snapshotId = snapshot,
      sourceRecordId = s"$left|$right",
      sourceUri = s"scholarchain:er/${policy.version}",
      transformVersion = s"entity-resolution/${policy.version}",
      runId = run.runId
    )

  private def build(
      input: MatchInput,
      features: Vector[Feature],
      prior: Double,
      logit: Double,
      verdict: Verdict
  ): ResolutionRecord =
    val left = input.left
    val right = input.right
    val snapshot =
      if left.snapshotId == right.snapshotId then left.snapshotId
      else Vector(left.snapshotId, right.snapshotId).sorted.mkString("+")
    val featureMap =
      features.map(f => s"f.${f.name}" -> f.value).toMap ++
        features.map(f => s"llr.${f.name}" -> f.llr).toMap ++
        Map("score.prior" -> prior, "score.logit" -> logit)
    val decision = MatchDecision(
      schemaVersion = ContractSchema.currentVersion,
      sourceId = left.id,
      targetId = right.id,
      features = featureMap,
      rulesFired = s"path:${verdict.path.wire}" +: verdict.rules,
      decision = verdict.outcome,
      confidence = verdict.confidence,
      reasonCodes = verdict.reasons,
      policyVersion = policy.version,
      modelVersion = None,
      promptVersion = None,
      decidedAt = run.decidedAtText,
      runId = run.runId,
      provenance = provenanceFor(left.id, right.id, snapshot)
    )
    ResolutionRecord(
      ContractSchema.currentVersion,
      input.kind,
      verdict.path,
      left.sourceRecordId,
      right.sourceRecordId,
      decision
    )

  private def unresolvedInput(
      candidate: MatchCandidate,
      reason: String
  ): ResolutionRecord =
    val decision = MatchDecision(
      schemaVersion = ContractSchema.currentVersion,
      sourceId = candidate.leftId,
      targetId = candidate.rightId,
      features = Map.empty,
      rulesFired = Vector(s"path:${MatchPath.Probabilistic.wire}"),
      decision = MatchOutcome.Unresolved,
      confidence = 0.0,
      reasonCodes = Vector(reason),
      policyVersion = policy.version,
      modelVersion = None,
      promptVersion = None,
      decidedAt = run.decidedAtText,
      runId = run.runId,
      provenance = provenanceFor(
        candidate.leftId,
        candidate.rightId,
        candidate.provenance.snapshotId
      )
    )
    ResolutionRecord(
      ContractSchema.currentVersion,
      candidate.kind.entityKind,
      MatchPath.Probabilistic,
      candidate.leftId,
      candidate.rightId,
      decision
    )

object Matcher:
  /** Reason code marking a pair whose records were not supplied. Evaluation
    * keeps such a record fixed when it re-applies thresholds.
    */
  val SideMissing = "input.side-missing"
  val InvalidCandidate = "input.invalid-candidate"
  val CapCorroboration = "cap.corroboration"
  val CapNameGate = "cap.name-gate"

  /** A strong or content-supported identifier agrees while the two mentions are
    * distinct authors of one work: the data contradicts itself. The same reason
    * code covers two slots of one work record, because in both cases the
    * contradiction is one identifier on two author positions of one work.
    */
  val IdAgreementContradictedByPosition =
    "conflict.id-agreement-contradicted-by-position"

  /** Rule code recorded when the two mentions are two slots of one work record
    * (equal work ID, different known author positions) and no shared DOI or
    * arXiv ID supplied the link. With a shared DOI or arXiv ID, the DOI or
    * arXiv rule code is recorded as before.
    */
  val SameRecordRule = "rule.same-record-distinct-position"

  /** Fixed confidences for rule outcomes, which have no score of their own.
    * They order the rules; they are not calibrated probabilities.
    */
  val StrongIdConfidence = 0.99
  val AggregatorIdConfidence = 0.95
  val RuleAcceptConfidence = 0.97
  val RuleRejectConfidence = 0.02

  private final case class Verdict(
      outcome: MatchOutcome,
      path: MatchPath,
      confidence: Double,
      rules: Vector[String],
      reasons: Vector[String]
  ):
    def addReasons(extra: Vector[String]): Verdict =
      copy(reasons = reasons ++ extra)
