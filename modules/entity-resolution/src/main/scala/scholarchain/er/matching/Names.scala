package scholarchain.er.matching

/** A personal name split into a folded family name and folded forename tokens.
  * `forenames` holds one token per name part or initial, so "Jean-Pierre" and
  * "J. P." both become two tokens.
  */
final case class ParsedName(family: String, forenames: Vector[String]):
  def initials: String = forenames.flatMap(_.headOption).mkString
  def isEmpty: Boolean = family.isEmpty

  /** Family name plus first initial, the coarse key used for coauthor sets. */
  def coarseKey: String =
    if family.isEmpty then "" else s"$family|${initials.take(1)}"

object Names:
  private val particles = Set(
    "van",
    "von",
    "de",
    "der",
    "den",
    "del",
    "della",
    "di",
    "da",
    "dos",
    "das",
    "du",
    "la",
    "le",
    "bin",
    "ibn",
    "al",
    "el",
    "ten",
    "ter",
    "st",
    "y"
  )
  private val suffixes = Set("jr", "sr", "ii", "iii", "iv")
  private val homonymOrdinal = "\\s[0-9]{4}$".r
  private val allCapsInitials = "\\p{Lu}{2,3}".r

  /** Parses "Given Family" and "Family, Given". A trailing four digit ordinal,
    * which DBLP appends to separate homonyms, is dropped because it is a source
    * artifact and not part of the name.
    */
  def parse(raw: String): ParsedName =
    val cleaned = homonymOrdinal.replaceFirstIn(
      Option(raw).getOrElse("").trim,
      ""
    )
    val parts = cleaned.split(",", -1).map(_.trim)
    if parts.length == 2 && parts(0).nonEmpty then
      ParsedName(
        Text.foldName(parts(0)),
        forenameTokens(parts(1).split("\\s+").toVector)
      )
    else
      val units = cleaned.split("\\s+").toVector.filter(_.nonEmpty)
      val trimmed =
        if units.size > 1 then
          units.filterNot(unit => suffixes(Text.foldName(unit)))
        else units
      if trimmed.isEmpty then ParsedName("", Vector.empty)
      else
        var start = trimmed.size - 1
        while start > 1 && particles(Text.foldName(trimmed(start - 1))) do
          start -= 1
        val family = trimmed
          .drop(start)
          .map(Text.foldName)
          .filter(_.nonEmpty)
          .mkString(" ")
        ParsedName(family, forenameTokens(trimmed.take(start)))

  private def forenameTokens(units: Vector[String]): Vector[String] =
    units
      .filter(_.nonEmpty)
      .flatMap { unit =>
        if allCapsInitials.matches(unit) then unit.toVector.map(_.toString)
        else Vector(unit)
      }
      .flatMap(unit => Text.tokens(Text.foldName(unit)))

/** How two parsed names relate. */
final case class NameComparison(
    family: Double,
    forename: Option[Double],
    initials: Option[Double],
    swapped: Boolean
)

object NameComparison:
  /** Graded forename agreement: 1.0 identical, 0.9 near identical spelling,
    * 0.75 an initial against a full name, 0.0 a conflict. A part present on one
    * side only (a middle name) neither helps nor hurts.
    */
  private def forenameScore(
      left: Vector[String],
      right: Vector[String]
  ): Option[Double] =
    if left.isEmpty || right.isEmpty then None
    else
      val pairs = left.zip(right).map { (a, b) =>
        if a == b then 1.0
        else if (a.length == 1 || b.length == 1) && a.head == b.head then 0.75
        else if a.length > 1 && b.length > 1 && Text.jaroWinkler(a, b) >= 0.9
        then 0.9
        else 0.0
      }
      if pairs.contains(0.0) then Some(0.0) else Some(pairs.sum / pairs.size)

  private def initialsScore(
      left: Vector[String],
      right: Vector[String]
  ): Option[Double] =
    if left.isEmpty || right.isEmpty then None
    else
      val agree = left.zip(right).forall((a, b) => a.head == b.head)
      Some(if agree then 1.0 else 0.0)

  private def direct(left: ParsedName, right: ParsedName): NameComparison =
    NameComparison(
      Text.jaroWinkler(left.family, right.family),
      forenameScore(left.forenames, right.forenames),
      initialsScore(left.forenames, right.forenames),
      swapped = false
    )

  /** Only a one-forename, one-family name can be written in either order, so
    * only that shape is tried reversed. The reversed reading is used only when
    * it produces a family match that the direct reading lacks.
    */
  private def reversed(name: ParsedName): Option[ParsedName] =
    Option.when(name.forenames.size == 1 && !name.family.contains(" "))(
      ParsedName(name.forenames.head, Vector(name.family))
    )

  def compare(left: ParsedName, right: ParsedName): NameComparison =
    val base = direct(left, right)
    val flips = reversed(right).map(direct(left, _)).toVector ++
      reversed(left).map(direct(_, right)).toVector
    flips.maxByOption(_.family) match
      case Some(other) if other.family >= 0.97 && base.family < 0.97 =>
        other.copy(swapped = true)
      case _ => base
