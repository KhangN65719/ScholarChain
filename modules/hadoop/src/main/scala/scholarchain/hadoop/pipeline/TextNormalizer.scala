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
  private val nonWord = "[^\\p{L}\\p{N}\\p{M}]+".r
  private val whitespace = "\\s+".r

  /** Letters that have no canonical decomposition, so mark stripping cannot
    * reduce them. Without this table they used to fall through to a word break
    * and split "Bjørn" into "bj" and "rn". The expansions are the conventional
    * ASCII spellings.
    */
  private val latinExpansions: Map[Char, String] = Map(
    'ø' -> "o",
    'đ' -> "d",
    'ð' -> "d",
    'ł' -> "l",
    'ħ' -> "h",
    'ŧ' -> "t",
    'ı' -> "i",
    'ĸ' -> "k",
    'ß' -> "ss",
    'æ' -> "ae",
    'œ' -> "oe",
    'þ' -> "th",
    // Greek final sigma, so a word-final and a standalone spelling agree.
    'ς' -> "σ"
  )

  /** Whether a combining mark is a diacritic to fold away. Marks are dropped
    * after scripts where they are optional decoration (Latin, Greek, Arabic,
    * Hebrew), after no base at all, and for the two Cyrillic marks that are
    * conventionally omitted (stress and diaeresis). They are kept elsewhere,
    * because in Japanese, Hangul, Indic and Thai text they carry meaning: a
    * Japanese dakuten turns か into が, and stripping it would merge distinct
    * names.
    */
  private def dropsMark(base: Character.UnicodeScript, mark: Int): Boolean =
    import Character.UnicodeScript.*
    base match
      case LATIN | GREEK | ARABIC | HEBREW | COMMON | INHERITED => true
      case CYRILLIC => mark == 0x0301 || mark == 0x0308
      case _        => false

  private def isMark(codePoint: Int): Boolean =
    Character.getType(codePoint) match
      case Character.NON_SPACING_MARK | Character.ENCLOSING_MARK |
          Character.COMBINING_SPACING_MARK =>
        true
      case _ => false

  def collapseWhitespace(value: String): String =
    whitespace.replaceAllIn(Option(value).getOrElse("").trim, " ")

  /** The matching key: NFKC, lowercase, script-aware diacritic folding,
    * non-letters and non-digits folded to single spaces. The result is stable
    * across runs and platforms.
    *
    * This is **a matching key only**. Stored records keep the original
    * characters; the folded form lives in `normalizedName` and
    * `normalizedTitle`.
    *
    * Coverage, stated precisely:
    *   - Latin, including letters with no decomposition (ø, ł, ß, æ, ...) and
    *     any accented form: folded to ASCII, so "Bjørn Ødegård" and "Bjorn
    *     Odegard" share a key.
    *   - Greek and Arabic/Hebrew: marks (tonos, harakat, niqqud) are stripped;
    *     letters are kept in their own script.
    *   - Every other script (CJK, Hangul, Cyrillic, Indic, Thai, ...): the
    *     NFKC-casefolded letters themselves are the key. These are same-script
    *     exact-match keys. There is no cross-script transliteration, so "李明"
    *     and "Li Ming" do not share a key. That would need a transliteration
    *     table or library, which the version ledger does not carry.
    */
  def fold(value: String): String =
    val composed =
      Normalizer.normalize(Option(value).getOrElse(""), Normalizer.Form.NFKC)
    val lowered = composed.toLowerCase(Locale.ROOT)
    val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
    val out = new java.lang.StringBuilder(decomposed.length)
    var base = Character.UnicodeScript.COMMON
    val iterator = decomposed.codePoints().iterator()
    while iterator.hasNext do
      val codePoint = iterator.nextInt()
      if isMark(codePoint) then
        if !dropsMark(base, codePoint) then out.appendCodePoint(codePoint)
      else
        base = Character.UnicodeScript.of(codePoint)
        latinExpansions.get(codePoint.toChar) match
          case Some(expansion) if codePoint < 0x10000 => out.append(expansion)
          case _ => out.appendCodePoint(codePoint)
    val recomposed = Normalizer.normalize(out, Normalizer.Form.NFC)
    nonWord.replaceAllIn(recomposed, " ").trim

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
