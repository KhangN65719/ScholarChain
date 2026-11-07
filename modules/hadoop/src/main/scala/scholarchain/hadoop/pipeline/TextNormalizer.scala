package scholarchain.hadoop.pipeline

import java.text.Normalizer
import java.util.Locale

/** Deterministic, locale-independent normalization shared by titles, names,
  * venues and blocking keys.
  *
  * Unicode form NFKC is applied before case folding so that a composed and a
  * decomposed spelling of the same name collapse to one key. `Locale.ROOT`
  * keeps case folding independent of the task JVM's default locale, which is
  * the usual source of nondeterminism across cluster nodes.
  */
object TextNormalizer:
  private val diacritical = "\\p{M}+".r
  private val nonAlphanumeric = "[^0-9a-z]+".r
  private val whitespace = "\\s+".r

  def collapseWhitespace(value: String): String =
    whitespace.replaceAllIn(Option(value).getOrElse("").trim, " ")

  /** NFKC, lowercase, diacritics stripped, non-alphanumerics folded to single
    * spaces. The result is stable across runs and platforms.
    */
  def fold(value: String): String =
    val composed =
      Normalizer.normalize(Option(value).getOrElse(""), Normalizer.Form.NFKC)
    val lowered = composed.toLowerCase(Locale.ROOT)
    val decomposed =
      Normalizer.normalize(lowered, Normalizer.Form.NFD)
    val stripped = diacritical.replaceAllIn(decomposed, "")
    nonAlphanumeric.replaceAllIn(stripped, " ").trim

  def titleKey(title: String): String = fold(title)

  /** DBLP disambiguates homonyms with a trailing four-digit ordinal, as in "Wei
    * Wang 0014". The ordinal is a DBLP artifact rather than part of the name,
    * so it is removed before the name is folded.
    */
  def stripHomonymOrdinal(name: String): String =
    val trimmed = collapseWhitespace(name)
    if trimmed.matches(".*\\s[0-9]{4}$") then trimmed.dropRight(5).trim
    else trimmed

  def nameKey(name: String): String = fold(stripHomonymOrdinal(name))

  def nameTokens(name: String): Vector[String] =
    val key = nameKey(name)
    if key.isEmpty then Vector.empty else key.split(" ").toVector

  /** Last token, treated as the family name. DBLP, Crossref and OpenAlex all
    * present Western-ordered display names in the pinned samples; a record
    * whose family name is known structurally should use [[nameKey]] on that
    * field directly instead of relying on this heuristic.
    */
  def familyToken(name: String): String =
    nameTokens(name).lastOption.getOrElse("")

  def initials(name: String): String =
    nameTokens(name).flatMap(_.headOption).mkString

  /** Family name plus given-name initials, the standard coarse name block. */
  def nameBlockKey(name: String): String =
    val tokens = nameTokens(name)
    if tokens.isEmpty then ""
    else
      val family = tokens.last
      val givenInitials = tokens.init.flatMap(_.headOption).mkString
      if givenInitials.isEmpty then family else s"$family|$givenInitials"

  def venueKey(namespace: String, venueName: String): String =
    val slug = fold(venueName).replace(' ', '-')
    if slug.isEmpty then "" else s"venue:v1:$namespace:$slug"
