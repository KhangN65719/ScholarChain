package scholarchain.hadoop.pipeline

import scholarchain.core.{ContractSchema, Provenance, SourceType}
import upickle.default.ReadWriter

/** A candidate pair of records to be adjudicated, with its deterministic
  * features.
  *
  * This record deliberately carries **no decision and no score**. The
  * accept/reject policy, the thresholds and the resulting `MatchDecision`
  * belong to the entity-resolution module; this job's obligation is to publish
  * the evidence as data, not to bury a guess in a log line.
  *
  * Contract-ownership note: `modules/core` defines `MatchDecision` but no
  * `MatchCandidate`, so this type is declared in the Hadoop module and
  * exchanged as a versioned JSON artifact. It is **not** a compile-time
  * dependency for any other module. Promoting it into a core contract is a
  * domain-engineer change and is reported as a blocker, not performed here.
  */
final case class MatchCandidate(
    schemaVersion: ContractSchema.Version,
    recordType: String,
    kind: BlockKind,
    leftId: String,
    rightId: String,
    leftSource: SourceType,
    rightSource: SourceType,
    blockKey: String,
    blockStrategy: BlockStrategy,
    exactIdentifierAgreements: Vector[String],
    exactIdentifierConflicts: Vector[String],
    features: Map[String, Double],
    provenance: Provenance
) derives ReadWriter:
  require(
    schemaVersion == ContractSchema.currentVersion,
    "Unsupported match candidate schema version"
  )
  require(
    leftId < rightId,
    "A candidate's endpoints must be distinct and in ascending ID order"
  )
  require(features.values.forall(_.isFinite), "Features must be finite")
  require(provenance != null, "A candidate must carry provenance")

object MatchCandidate:
  val recordTypeName = "scholarchain.hadoop.MatchCandidate/v1"

/** Job 5: candidate feature construction.
  *
  * Features are pure functions of two [[BlockMember]] projections. Every
  * feature is in `[0, 1]`, finite, and symmetric in its arguments, so the
  * candidate's feature map does not depend on which member the shuffle happened
  * to deliver first.
  */
object MatchFeatures:
  val stage = "candidate-features"

  /** Jaccard similarity over token sets. Symmetric, and 0 when either side is
    * empty rather than undefined.
    */
  def jaccard(left: Set[String], right: Set[String]): Double =
    if left.isEmpty || right.isEmpty then 0.0
    else
      val intersection = left.intersect(right).size.toDouble
      intersection / (left.size + right.size - intersection)

  /** Normalized Levenshtein similarity, computed with a two-row buffer so a
    * long title pair costs linear memory.
    */
  def editSimilarity(left: String, right: String): Double =
    if left.isEmpty && right.isEmpty then 1.0
    else if left.isEmpty || right.isEmpty then 0.0
    else
      val longest = math.max(left.length, right.length)
      var previous = Array.tabulate(right.length + 1)(identity)
      var current = new Array[Int](right.length + 1)
      var row = 1
      while row <= left.length do
        current(0) = row
        var column = 1
        while column <= right.length do
          val substitution =
            previous(column - 1) +
              (if left.charAt(row - 1) == right.charAt(column - 1) then 0
               else 1)
          current(column) = math.min(
            substitution,
            math.min(previous(column) + 1, current(column - 1) + 1)
          )
          column += 1
        val swap = previous
        previous = current
        current = swap
        row += 1
      1.0 - previous(right.length).toDouble / longest

  private def tokens(value: String): Set[String] =
    if value.isEmpty then Set.empty else value.split(" ").toSet

  /** Exact identifiers whose namespaces both members populate. An agreement is
    * strong positive evidence; a disagreement on the same namespace is a
    * conflict, which is counted and surfaced rather than averaged away.
    */
  def identifierComparison(
      left: Map[String, String],
      right: Map[String, String]
  ): (Vector[String], Vector[String]) =
    val shared = left.keySet.intersect(right.keySet).toVector.sorted
    val agreements =
      shared.filter(namespace => left(namespace) == right(namespace))
    val conflicts =
      shared.filterNot(namespace => left(namespace) == right(namespace))
    (agreements, conflicts)

  def features(left: BlockMember, right: BlockMember): Map[String, Double] =
    val yearGap = left.year.zip(right.year).map { case (a, b) =>
      math.abs(a - b).toDouble
    }
    Map(
      "name.exact" -> indicator(
        left.normalizedName.nonEmpty && left.normalizedName == right.normalizedName
      ),
      "name.jaccard" -> jaccard(
        tokens(left.normalizedName),
        tokens(right.normalizedName)
      ),
      "name.edit" -> editSimilarity(left.normalizedName, right.normalizedName),
      "name.familyExact" -> indicator(
        familyOf(left).nonEmpty && familyOf(left) == familyOf(right)
      ),
      "name.initialsExact" -> indicator(
        TextNormalizer.initials(left.displayName).nonEmpty &&
          TextNormalizer.initials(left.displayName) ==
          TextNormalizer.initials(right.displayName)
      ),
      "title.exact" -> indicator(
        left.normalizedTitle.nonEmpty && left.normalizedTitle == right.normalizedTitle
      ),
      "title.jaccard" -> jaccard(
        tokens(left.normalizedTitle),
        tokens(right.normalizedTitle)
      ),
      "title.edit" -> editSimilarity(
        left.normalizedTitle,
        right.normalizedTitle
      ),
      "year.known" -> indicator(left.year.isDefined && right.year.isDefined),
      "year.exact" -> indicator(yearGap.contains(0.0)),
      // Scaled so a decade's difference reaches 0; wider gaps clamp there.
      "year.proximity" -> yearGap.fold(0.0)(gap =>
        math.max(0.0, 1.0 - gap / 10.0)
      ),
      "venue.exact" -> indicator(
        left.venueId.isDefined && left.venueId == right.venueId
      ),
      "affiliation.jaccard" -> jaccard(
        left.affiliations.map(TextNormalizer.fold).toSet,
        right.affiliations.map(TextNormalizer.fold).toSet
      ),
      "coauthor.jaccard" -> jaccard(
        left.coauthorNames.toSet,
        right.coauthorNames.toSet
      ),
      "coauthor.sharedCount" -> left.coauthorNames.toSet
        .intersect(right.coauthorNames.toSet)
        .size
        .toDouble,
      "source.distinct" -> indicator(left.source != right.source),
      "sameWork" -> indicator(left.workId == right.workId)
    )

  private def familyOf(member: BlockMember): String =
    member.externalIds.getOrElse(
      "crossref-family",
      TextNormalizer.familyToken(member.displayName)
    )

  private def indicator(condition: Boolean): Double =
    if condition then 1.0 else 0.0

  /** Builds the candidate for an ordered pair drawn from one bounded block.
    *
    * A pair of mentions from the same work is never a researcher candidate: two
    * distinct authors on one paper are by construction different people, so the
    * pair is suppressed rather than scored.
    */
  def candidate(
      blockKey: String,
      left: BlockMember,
      right: BlockMember,
      run: RunContext
  ): Option[MatchCandidate] =
    if left.memberId == right.memberId then None
    else if left.kind != right.kind then None
    else if left.kind == BlockKind.Researcher && left.workId == right.workId
    then None
    else
      val (first, second) =
        if left.memberId < right.memberId then (left, right) else (right, left)
      val (agreements, conflicts) =
        identifierComparison(first.externalIds, second.externalIds)
      val provenance = run.provenance(
        SourceType.Derived,
        s"${first.memberId}|${second.memberId}",
        s"scholarchain:block/$blockKey",
        TaskSink.sourceDataset(first.source)
      )
      Some(
        MatchCandidate(
          schemaVersion = ContractSchema.currentVersion,
          recordType = MatchCandidate.recordTypeName,
          kind = first.kind,
          leftId = first.memberId,
          rightId = second.memberId,
          leftSource = first.source,
          rightSource = second.source,
          blockKey = blockKey,
          blockStrategy =
            Blocking.strategyOf(blockKey).getOrElse(BlockStrategy.NameInitials),
          exactIdentifierAgreements = agreements,
          exactIdentifierConflicts = conflicts,
          features = features(first, second),
          provenance = provenance
        )
      )

  /** All candidates from one bounded block. The members are already ordered
    * deterministically by [[Blocking.bound]], so the pair sequence is stable.
    */
  def candidates(
      blockKey: String,
      members: Vector[BlockMember],
      run: RunContext
  ): Vector[MatchCandidate] =
    members.indices.iterator
      .flatMap(index =>
        (index + 1 until members.size).iterator.flatMap(other =>
          candidate(blockKey, members(index), members(other), run)
        )
      )
      .toVector
