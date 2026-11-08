package scholarchain.er.matching

/** One piece of evidence about a pair.
  *
  * `value` is the feature as measured, in [0, 1]. `llr` is its contribution to
  * the log-odds that the pair is a match: positive for support, negative for
  * contradiction, zero for a measurement that is reported but not scored. A
  * feature that cannot be measured because a side lacks the field is omitted,
  * never recorded as a zero, so absence of data is not scored as disagreement.
  */
final case class Feature(name: String, value: Double, llr: Double)

/** Feature functions for researcher mentions and for works. All are pure and
  * symmetric in their two arguments.
  */
object Features:
  val titleSimilarity = "title.similarity"
  val nameFamily = "name.family"
  val nameForename = "name.forename"
  val coauthorShared = "coauthor.shared"
  val affiliationCompat = "affiliation.compat"
  val citationShared = "citation.shared"
  val authorOverlap = "author.overlap"
  val authorOrder = "author.order"
  val venueCompat = "venue.compat"
  val aggregatorId = "id.aggregator"

  private def sigmoidClamp(logit: Double): Double =
    math.max(-30.0, math.min(30.0, logit))

  def clamp(logit: Double): Double = sigmoidClamp(logit)

  def probability(logit: Double): Double =
    1.0 / (1.0 + math.exp(-sigmoidClamp(logit)))

  /** Title similarity: the mean of token overlap and edit similarity over the
    * markup stripped, folded title. None when either title folds to nothing.
    */
  def titleScore(left: SideRecord, right: SideRecord): Option[Double] =
    val a = Text.titleKey(left.title)
    val b = Text.titleKey(right.title)
    Option.when(a.nonEmpty && b.nonEmpty)(
      0.5 * Text.jaccard(Text.tokens(a).toSet, Text.tokens(b).toSet) +
        0.5 * Text.editSimilarity(a, b)
    )

  private def yearGap(left: SideRecord, right: SideRecord): Option[Int] =
    left.year.zip(right.year).map((a, b) => math.abs(a - b))

  private def venueFeature(
      left: SideRecord,
      right: SideRecord,
      mismatchLlr: Double
  ): Option[Feature] =
    for
      a <- left.venue.map(Text.fold).filter(_.nonEmpty)
      b <- right.venue.map(Text.fold).filter(_.nonEmpty)
    yield
      if a == b then Feature(venueCompat, 1.0, 0.8)
      else Feature(venueCompat, 0.0, mismatchLlr)

  private def citationFeature(
      left: SideRecord,
      right: SideRecord
  ): Option[Feature] =
    val a = left.citationNeighbors.toSet
    val b = right.citationNeighbors.toSet
    Option.when(a.nonEmpty && b.nonEmpty) {
      val shared = a.intersect(b).size
      val llr = if shared >= 3 then 3.0 else if shared >= 1 then 2.0 else -0.2
      Feature(citationShared, Text.jaccard(a, b), llr)
    }

  private def reliabilityFeature(
      left: SideRecord,
      right: SideRecord,
      policy: Policy
  ): Feature =
    val value =
      math.min(
        policy.reliability(left.source),
        policy.reliability(right.source)
      )
    Feature("source.reliability", value, (value - 0.8) * 2.0)

  private val institutionStopwords = Set(
    "university",
    "universite",
    "universitat",
    "universidad",
    "of",
    "the",
    "and",
    "de",
    "institute",
    "institut",
    "department",
    "dept",
    "school",
    "college",
    "laboratory",
    "lab",
    "faculty",
    "center",
    "centre"
  )

  private def institutionTokens(raw: String): Set[String] =
    Text.tokens(Text.fold(raw)).filterNot(institutionStopwords).toSet

  private def affiliationPair(left: String, right: String): Double =
    val a = institutionTokens(left)
    val b = institutionTokens(right)
    if a.isEmpty || b.isEmpty then 0.0
    else if a == b then 1.0
    else if (a.subsetOf(b) || b.subsetOf(a)) && math.min(a.size, b.size) >= 2
    then 1.0
    else Text.jaccard(a, b)

  /** Best agreement over all pairs of listed affiliations. Affiliations change
    * over a career, so a mismatch is only mild evidence against.
    */
  private def affiliationFeature(
      left: SideRecord,
      right: SideRecord
  ): Option[Feature] =
    val pairs =
      for
        a <- left.affiliations
        b <- right.affiliations
      yield affiliationPair(a, b)
    Option.when(pairs.nonEmpty) {
      val best = pairs.max
      val llr = if best >= 0.99 then 2.0 else if best >= 0.5 then 1.0 else -0.3
      Feature(affiliationCompat, best, llr)
    }

  /** Researcher identifiers issued by aggregators. Agreement raises the score
    * and disagreement lowers it; neither decides a pair, and neither counts as
    * the corroboration an acceptance needs.
    */
  private def aggregatorFeature(ids: IdComparison): Option[Feature] =
    (ids.agreements.nonEmpty, ids.conflicts.nonEmpty) match
      case (true, false) => Some(Feature(aggregatorId, 1.0, 3.0))
      case (false, true) => Some(Feature(aggregatorId, 0.0, -1.5))
      case (true, true)  => Some(Feature(aggregatorId, 0.5, 0.0))
      case _             => None

  def researcher(
      left: SideRecord,
      right: SideRecord,
      policy: Policy,
      aggregatorIds: IdComparison = IdComparison.empty
  ): Vector[Feature] =
    val name = NameComparison.compare(
      Names.parse(left.name),
      Names.parse(right.name)
    )
    val ownKey = Names.parse(left.name).coarseKey
    val otherKey = Names.parse(right.name).coarseKey
    def coauthorKeys(side: SideRecord, own: String): Set[String] =
      side.coauthors
        .map(Names.parse(_).coarseKey)
        .filter(_.nonEmpty)
        .toSet - own
    val keysA = coauthorKeys(left, ownKey)
    val keysB = coauthorKeys(right, otherKey)
    val title = titleScore(left, right)
    val topical = Text.jaccard(
      Text.contentTokens(Text.titleKey(left.title)),
      Text.contentTokens(Text.titleKey(right.title))
    )
    val sameWork = title.exists(_ >= 0.9) &&
      yearGap(left, right).forall(_ <= 1)
    val position = for
      a <- left.authorPosition
      b <- right.authorPosition
    yield (a, b)
    Vector(
      aggregatorFeature(aggregatorIds),
      Some(
        Feature(
          nameFamily,
          name.family,
          if name.family >= 0.97 then 0.5
          else if name.family >= policy.familyNearMatchBelow then -0.5
          else -3.0
        )
      ),
      name.forename.map { score =>
        val llr =
          if score >= 0.99 then 1.5
          else if score >= 0.85 then 1.0
          else if score >= 0.7 then 0.6
          else -4.0
        Feature(nameForename, score, llr)
      },
      name.initials.map(Feature("name.initials", _, 0.0)),
      Option.when(name.swapped)(Feature("name.orderSwapped", 1.0, -0.5)),
      Option.when(keysA.nonEmpty && keysB.nonEmpty) {
        val shared = keysA.intersect(keysB).size
        val llr = if shared >= 2 then 3.5 else if shared == 1 then 2.0 else -0.7
        Feature(
          coauthorShared,
          math.min(1.0, shared.toDouble / policy.coauthorSaturation),
          llr
        )
      },
      affiliationFeature(left, right),
      venueFeature(left, right, -0.1),
      yearGap(left, right).map { gap =>
        val llr = if gap <= 2 then 0.3 else if gap <= 10 then 0.0 else -0.3
        Feature("year.proximity", 1.0 - math.min(gap, 20) / 20.0, llr)
      },
      Option.when(title.isDefined) {
        val llr = if sameWork then 2.0 else if topical >= 0.25 then 0.6 else 0.0
        Feature(titleSimilarity, title.getOrElse(0.0), llr)
      },
      Option.when(position.isDefined && sameWork) {
        val (a, b) = position.getOrElse((0, 0))
        Feature(
          authorOrder,
          if a == b then 1.0 else 0.0,
          if a == b then 1.0 else -1.0
        )
      },
      citationFeature(left, right),
      Some(reliabilityFeature(left, right, policy))
    ).flatten

  private def isGeneric(left: SideRecord, right: SideRecord, policy: Policy) =
    Text
      .tokens(Text.titleKey(left.title))
      .size <= policy.genericTitleMaxTokens ||
      Text
        .tokens(Text.titleKey(right.title))
        .size <= policy.genericTitleMaxTokens

  private def familySequence(side: SideRecord): Vector[String] =
    side.authorNames.map(Names.parse(_).family).filter(_.nonEmpty)

  private def longestCommonSubsequence(
      left: Vector[String],
      right: Vector[String]
  ): Int =
    var previous = Array.fill(right.size + 1)(0)
    for a <- left do
      val current = Array.fill(right.size + 1)(0)
      for j <- right.indices do
        current(j + 1) =
          if a == right(j) then previous(j) + 1
          else math.max(previous(j + 1), current(j))
      previous = current
    previous(right.size)

  def work(
      left: SideRecord,
      right: SideRecord,
      policy: Policy
  ): Vector[Feature] =
    val title = titleScore(left, right)
    val authorsA =
      left.authorNames.map(Names.parse(_).coarseKey).filter(_.nonEmpty)
    val authorsB =
      right.authorNames.map(Names.parse(_).coarseKey).filter(_.nonEmpty)
    val familiesA = familySequence(left)
    val familiesB = familySequence(right)
    Vector(
      title.map { score =>
        val raw =
          if score >= 0.98 then 5.0
          else if score >= 0.90 then 3.0
          else if score >= 0.75 then 1.0
          else if score >= 0.50 then -1.5
          else -5.0
        val llr =
          if isGeneric(left, right, policy) then math.min(raw, 1.0) else raw
        Feature(titleSimilarity, score, llr)
      },
      yearGap(left, right).map { gap =>
        val llr =
          if gap == 0 then 1.0
          else if gap == 1 then 0.5
          else if gap == 2 then -1.0
          else -2.5
        Feature("year.proximity", math.max(0.0, 1.0 - gap / 4.0), llr)
      },
      Option.when(authorsA.nonEmpty && authorsB.nonEmpty) {
        val overlap = Text.jaccard(authorsA.toSet, authorsB.toSet)
        val llr =
          if overlap >= 0.9 then 3.0
          else if overlap >= 0.6 then 1.5
          else if overlap >= 0.3 then -0.5
          else -3.0
        Feature(authorOverlap, overlap, llr)
      },
      Option.when(familiesA.nonEmpty && familiesB.nonEmpty) {
        val ratio = longestCommonSubsequence(familiesA, familiesB).toDouble /
          math.max(familiesA.size, familiesB.size)
        val llr =
          if ratio >= 0.9 then 0.5 else if ratio < 0.5 then -0.5 else 0.0
        Feature(authorOrder, ratio, llr)
      },
      venueFeature(left, right, -0.2),
      citationFeature(left, right),
      Some(reliabilityFeature(left, right, policy))
    ).flatten
