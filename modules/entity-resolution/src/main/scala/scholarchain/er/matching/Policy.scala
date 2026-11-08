package scholarchain.er.matching

import scholarchain.core.{ScholarChainConfig, SourceType}

/** The scoring policy. Every number that changes a decision is here or in
  * [[Features]]; the version string must change whenever any of them does. A
  * test pins a frozen score so that an edit without a version change fails.
  *
  * Thresholds: a pair scoring at or above `accept` is accepted if its gates
  * hold; below `reject` it is rejected; the interval between is unresolved. The
  * adjudication band is the upper part of that interval, `[llmBandLow,
  * accept)`, so a model can only be asked about pairs that are close to
  * acceptance and never about pairs the policy already considers clear.
  *
  * The scores are not calibrated probabilities. They are log-odds sums with
  * hand-set weights; the evaluation module is the instrument that shows how far
  * the thresholds sit from the right place once labels exist.
  *
  * Version 1.2.0 (review round 1) adds one rule and changes no weight and no
  * threshold: two researcher mentions whose works share a normalized DOI, at
  * known and different positions of one aligned author list, are rejected with
  * `rule.same-doi-distinct-position`. It changes decisions for pairs that 1.1.0
  * scored, so the version moved.
  *
  * Version 1.3.0 (review round 2) changes no weight and no threshold. It
  * extends that rule to a shared normalized arXiv ID (rule code
  * `rule.same-arxiv-distinct-position`, reason code
  * `rejected.same-arxiv-distinct-position`; the DOI rule code
  * `rule.same-doi-distinct-position`, its reason code
  * `rejected.same-doi-distinct-position` and their meaning are unchanged) and
  * adds the CONFLICT below. Both change decisions for inputs that 1.2.0
  * decided, so the version moved.
  *
  * Version 1.4.0 (review round 3) changes no weight and no threshold. It
  * extends the position CONFLICT below to two slots of one work record with no
  * DOI and no arXiv ID, which 1.3.0 still ACCEPTED on an agreeing ORCID. That
  * changes decisions for inputs that 1.3.0 decided, so the version moved.
  *
  * Work-level key for the position rule: a shared normalized work DOI (an
  * arXiv-prefixed DOI is not a work DOI), or else a shared normalized arXiv ID.
  * The arXiv ID is read from `WorkRecord.arxivId` or from the arXiv-prefixed
  * DOI form `10.48550/arxiv.NNNN` (any DOI URL prefix, any case) and reduced to
  * the bare ID without version suffix, so `2101.00001v2`, `arXiv:2101.00001`
  * and `https://doi.org/10.48550/arXiv.2101.00001` are one key. The arXiv key
  * is not used when both sides carry a work DOI and the two differ. Preprint
  * and published version are one key only if both carry the arXiv ID. The
  * conditions are the same for both keys: both positions known and different,
  * author lists of equal length, the same person at every index. Identifier
  * rules (human decision 4, clarified 2026-11-07; and ruling 2 of review round
  * 1, 2026-11-07):
  *
  *   - Researchers: only an ORCID disagreement is a CONFLICT. Differing
  *     OpenAlex, Semantic Scholar or DBLP author IDs are cluster assignments
  *     made by aggregators; they are features that lower the score and add an
  *     `id.aggregator-conflict:*` reason, and they never conflict on their own.
  *   - Works: a DOI mismatch is a CONFLICT, because the DOI is authoritative
  *     for works.
  *   - Agree-but-contradicts (accepted ruling): a strong identifier that agrees
  *     while the content contradicts it is also a CONFLICT, reason
  *     `conflict.id-agreement-contradicted-by-content`. For researchers an
  *     ORCID agreement with family-name similarity below
  *     `contentConflictFamily` (0.60); for works a DOI or arXiv agreement with
  *     title similarity below `contentConflictTitle` (0.30). An aggregator
  *     identifier that agrees is accepted only when the content supports it,
  *     otherwise UNRESOLVED.
  *   - Agree-but-contradicts by position (ruling 2 of review round 2): when an
  *     agreeing ORCID, or an aggregator author ID that the content supports
  *     (family-name similarity at least `familyNearMatchBelow`, forename not
  *     below 0.7), sits on two mentions that the position rule says are
  *     distinct authors of one work (shared DOI or arXiv ID, different known
  *     positions of one aligned author list), the outcome is CONFLICT, reason
  *     `conflict.id-agreement-contradicted-by-position`, path exact-id, rules
  *     fired the `rule.id-agreement:*` codes plus the position rule's code. It
  *     is not ACCEPTED, because one identifier on two slots of one author list
  *     contradicts itself. Before 1.3.0 an agreeing ORCID won (ACCEPTED), and
  *     an agreeing aggregator ID was only a score feature, so the position rule
  *     already rejected the pair. An ORCID that agrees with no shared work key,
  *     or at the same position, is unchanged.
  *   - Same work record (ruling of review round 3, 1.4.0): an agreeing ORCID on
  *     two slots of the SAME work record is also this CONFLICT, whether or not
  *     the work has a DOI or an arXiv ID, with the same reason code
  *     `conflict.id-agreement-contradicted-by-position` (one reason code,
  *     because the contradiction is the same: one identifier on two author
  *     positions of one work). Two slots of one work record means: both
  *     mentions are researcher mentions, their work IDs are equal (one source
  *     record), both carry a known author position, and the positions differ.
  *     It needs no author list. Rules fired are the `rule.id-agreement:*` codes
  *     plus `rule.same-record-distinct-position`, or the DOI or arXiv position
  *     rule code when a shared DOI or arXiv ID supplied the link. The same
  *     position is the same slot and an unknown position is not guessed; both
  *     stay unchanged. Without an ORCID agreement the pair stays REJECTED by
  *     `rule.same-work-distinct-mention`. An agreeing aggregator ID on two
  *     slots of one record with no shared DOI or arXiv ID stays REJECTED (not
  *     extended: the ruling named ORCID).
  *   - Pending human ruling, behavior unchanged: a disagreement in any other
  *     work identifier (OpenAlex work ID, Semantic Scholar paper ID, repository
  *     URL, arXiv ID) currently also yields CONFLICT, because every work
  *     namespace counts as authoritative in `IdComparison.isAuthoritative`. An
  *     arXiv-prefixed DOI is stored as an arXiv ID, not as a DOI.
  */
final case class Policy(
    version: String,
    accept: Double,
    reject: Double,
    llmBandLow: Double,
    llmMinConfidence: Double,
    researcherPrior: Double,
    workPrior: Double,
    familyRejectBelow: Double,
    familyNearMatchBelow: Double,
    contentConflictFamily: Double,
    contentConflictTitle: Double,
    coauthorSaturation: Int,
    genericTitleMaxTokens: Int,
    deterministicTitleMinLength: Int
):
  require(version.trim.nonEmpty, "Policy version is required")
  require(
    0.0 <= reject && reject < llmBandLow && llmBandLow < accept && accept <= 1.0,
    "Thresholds must satisfy 0 <= reject < llmBandLow < accept <= 1"
  )
  require(
    llmMinConfidence > 0.0 && llmMinConfidence <= 1.0,
    "Adjudication confidence floor must be in (0, 1]"
  )
  require(coauthorSaturation >= 1, "Coauthor saturation must be positive")

  def inLlmBand(score: Double): Boolean = score >= llmBandLow && score < accept

  /** Prior reliability of a source's record, in [0, 1]. */
  def reliability(source: SourceType): Double =
    source match
      case SourceType.Orcid           => 1.0
      case SourceType.Crossref        => 0.95
      case SourceType.Dblp            => 0.90
      case SourceType.OpenAlex        => 0.80
      case SourceType.SemanticScholar => 0.80
      case SourceType.Arxiv           => 0.75
      case SourceType.Derived         => 0.70

object Policy:
  val v1Version = "scholarchain.er.policy/1.4.0"

  /** Thresholds follow the project configuration so that there is one source of
    * truth. The adjudication band starts 0.25 below acceptance, which keeps it
    * clear of the reject threshold at the shipped values.
    */
  def fromConfig(config: ScholarChainConfig): Policy =
    v1.copy(
      accept = config.matchAcceptThreshold,
      reject = config.matchRejectThreshold,
      llmBandLow = math.max(
        config.matchRejectThreshold + 0.05,
        config.matchAcceptThreshold - 0.25
      )
    )

  /** Defaults equal `scholarchain-local.v1.yaml`: accept 0.95, reject 0.35. */
  val v1: Policy = Policy(
    version = v1Version,
    accept = 0.95,
    reject = 0.35,
    llmBandLow = 0.70,
    llmMinConfidence = 0.80,
    researcherPrior = -3.0,
    workPrior = -2.0,
    familyRejectBelow = 0.80,
    familyNearMatchBelow = 0.88,
    contentConflictFamily = 0.60,
    contentConflictTitle = 0.30,
    coauthorSaturation = 2,
    genericTitleMaxTokens = 2,
    deterministicTitleMinLength = 20
  )
