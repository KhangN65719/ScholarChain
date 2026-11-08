package scholarchain.er.sampling

import scholarchain.core.pairing.BlockStrategy
import scholarchain.er.matching.{
  EntityKind,
  Identifiers,
  Names,
  SideRecord,
  Text
}

/** The three evaluation strata. The order is the order in which a total that
  * does not divide by three hands out its remainder.
  */
enum Stratum(val wire: String):
  case ExactId extends Stratum("exact-id")
  case HardCommonName extends Stratum("hard-common-name")
  case ClearNonMatch extends Stratum("clear-non-match")

/** Why a candidate is not eligible for any stratum. */
enum Exclusion(val wire: String):
  case SameWork extends Exclusion("same-work-mentions")
  case AgreeingContext extends Exclusion("same-name-agreeing-context")
  case Unclassified extends Exclusion("unclassified")
  case MissingRecord extends Exclusion("missing-record")

enum Context:
  case Agreeing, Ambiguous, Conflicting

/** Assigns a stratum from the candidate's records and block strategy only. It
  * reads identifiers, names, titles, years, venues, affiliations and coauthors.
  * It never reads a match score, a decision, a path or a reason, so the sample
  * cannot be steered by the matcher it is meant to evaluate.
  *
  * Rules, applied in this order:
  *
  *   1. Two mentions of one work are distinct people by construction and are
  *      excluded. 2. exact-id: the records share a normalized identifier in any
  *      registry namespace (DOI, arXiv, ORCID, OpenAlex, Semantic Scholar, DBLP
  *      author, repository URL). 3. clear-non-match, by identifier: researchers
  *      whose authoritative identifiers (ORCID) disagree. 4. researchers with
  *      the same family name and compatible forenames (equal, or an initial
  *      against a name) and no shared identifier are hard-common-name when
  *      their context is ambiguous or conflicting. When the context agrees (two
  *      shared coauthors, or one plus the same venue or an affiliation overlap)
  *      the pair is excluded unless `includeAgreeingContext`, because the
  *      sample would otherwise hold only pairs a reviewer is likely to call
  *      different. 5. clear-non-match, by name: a weak or name-led block, no
  *      shared identifier, and a family name with Jaro-Winkler similarity below
  *      0.85, or the same family with conflicting forenames. 6. works: a title
  *      content-token Jaccard of at least 0.6 with no shared identifier is
  *      hard-common-name unless the context agrees (author overlap of 0.8 or
  *      more and years within one); a disagreeing authoritative identifier or a
  *      title Jaccard below 0.2 with a weak or name-led block is
  *      clear-non-match. Everything else is unclassified and excluded.
  *
  * A weak block is one the pipeline marks non-selective. A name-led block is a
  * strategy whose wire name starts with `name-`.
  */
object Stratifier:
  private val familyFar = 0.85
  private val titleSimilar = 0.6
  private val titleDifferent = 0.2

  def weakOrNameLed(strategy: BlockStrategy): Boolean =
    !strategy.selective || strategy.wire.startsWith("name-")

  private def coarse(names: Vector[String]): Set[String] =
    names.map(Names.parse(_).coarseKey).filter(_.nonEmpty).toSet

  private def yearGap(left: SideRecord, right: SideRecord): Option[Int] =
    for a <- left.year; b <- right.year yield math.abs(a - b)

  private def venueKey(side: SideRecord): Option[String] =
    side.venue.map(Text.fold).filter(_.nonEmpty)

  private def affiliationTokens(side: SideRecord): Set[String] =
    side.affiliations.flatMap(a => Text.contentTokens(Text.fold(a))).toSet

  private def forenamesCompatible(
      left: Vector[String],
      right: Vector[String]
  ): Boolean =
    left.zip(right).forall { (a, b) =>
      a == b || ((a.length == 1 || b.length == 1) && a.head == b.head)
    }

  def researcherContext(left: SideRecord, right: SideRecord): Context =
    val shared = coarse(left.coauthors).intersect(coarse(right.coauthors)).size
    val leftVenue = venueKey(left)
    val rightVenue = venueKey(right)
    val sameVenue = leftVenue.isDefined && leftVenue == rightVenue
    val differentVenue =
      leftVenue.isDefined && rightVenue.isDefined && leftVenue != rightVenue
    val leftAffiliation = affiliationTokens(left)
    val rightAffiliation = affiliationTokens(right)
    val affiliationOverlap =
      leftAffiliation.intersect(rightAffiliation).nonEmpty
    val affiliationsPresent =
      leftAffiliation.nonEmpty && rightAffiliation.nonEmpty
    if shared >= 2 || (shared >= 1 && (sameVenue || affiliationOverlap)) then
      Context.Agreeing
    else if shared == 0 &&
      ((affiliationsPresent && !affiliationOverlap) ||
        (differentVenue && yearGap(left, right).exists(_ >= 5)))
    then Context.Conflicting
    else Context.Ambiguous

  def workContext(left: SideRecord, right: SideRecord): Context =
    val leftAuthors = coarse(left.authorNames)
    val rightAuthors = coarse(right.authorNames)
    val overlap = Text.jaccard(leftAuthors, rightAuthors)
    val gap = yearGap(left, right)
    if overlap >= 0.8 && gap.exists(_ <= 1) then Context.Agreeing
    else if (leftAuthors.nonEmpty && rightAuthors.nonEmpty && overlap < 0.3) ||
      gap.exists(_ >= 3)
    then Context.Conflicting
    else Context.Ambiguous

  def classify(
      kind: EntityKind,
      left: SideRecord,
      right: SideRecord,
      strategy: BlockStrategy,
      includeAgreeingContext: Boolean
  ): Either[Exclusion, Stratum] =
    val ids = Identifiers.compare(left.identifiers, right.identifiers)
    if kind == EntityKind.Researcher && left.workId == right.workId then
      Left(Exclusion.SameWork)
    else if ids.agreements.nonEmpty then Right(Stratum.ExactId)
    else
      kind match
        case EntityKind.Researcher =>
          researcher(
            left,
            right,
            ids.authoritative.conflicts.nonEmpty,
            strategy,
            includeAgreeingContext
          )
        case EntityKind.Work =>
          work(left, right, ids.authoritative.conflicts.nonEmpty, strategy)

  private def researcher(
      left: SideRecord,
      right: SideRecord,
      identifierConflict: Boolean,
      strategy: BlockStrategy,
      includeAgreeingContext: Boolean
  ): Either[Exclusion, Stratum] =
    val a = Names.parse(left.name)
    val b = Names.parse(right.name)
    if identifierConflict then Right(Stratum.ClearNonMatch)
    else if a.isEmpty || b.isEmpty then Left(Exclusion.Unclassified)
    else
      val sameFamily = a.family == b.family
      val compatible = forenamesCompatible(a.forenames, b.forenames)
      if sameFamily && compatible then
        researcherContext(left, right) match
          case Context.Agreeing if !includeAgreeingContext =>
            Left(Exclusion.AgreeingContext)
          case _ => Right(Stratum.HardCommonName)
      else if weakOrNameLed(strategy) &&
        (Text.jaroWinkler(a.family, b.family) < familyFar || sameFamily)
      then Right(Stratum.ClearNonMatch)
      else Left(Exclusion.Unclassified)

  private def work(
      left: SideRecord,
      right: SideRecord,
      identifierConflict: Boolean,
      strategy: BlockStrategy
  ): Either[Exclusion, Stratum] =
    val similarity = Text.jaccard(
      Text.contentTokens(Text.titleKey(left.title)),
      Text.contentTokens(Text.titleKey(right.title))
    )
    if similarity >= titleSimilar then
      if workContext(left, right) == Context.Agreeing then
        Left(Exclusion.AgreeingContext)
      else Right(Stratum.HardCommonName)
    else if identifierConflict then Right(Stratum.ClearNonMatch)
    else if similarity < titleDifferent && weakOrNameLed(strategy) then
      Right(Stratum.ClearNonMatch)
    else Left(Exclusion.Unclassified)
