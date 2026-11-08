package scholarchain.er.matching

import java.text.Normalizer
import java.util.Locale
import scala.util.matching.Regex

/** Text folding used only to build matching keys. Source records keep their
  * original characters; nothing here is written back to a record.
  *
  * Letters outside the Latin script are kept, so a name written in Han,
  * Cyrillic or Arabic script folds to itself rather than to an empty key that
  * would compare equal to every other empty key.
  */
object Text:
  private val marks = "\\p{M}+".r
  private val separators = "[^\\p{L}\\p{N}]+".r
  private val tags = "<[^>]*>".r
  private val latexCommand = "\\\\[a-zA-Z]+".r
  private val entityRef = "&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);".r
  private val apostrophes = "['’ʼ‘`]".r

  private val letterMap = Map(
    'ø' -> "o",
    'ß' -> "ss",
    'ł' -> "l",
    'đ' -> "d",
    'æ' -> "ae",
    'œ' -> "oe",
    'ð' -> "d",
    'þ' -> "th",
    'ı' -> "i"
  )

  private val namedEntities = Map(
    "amp" -> "&",
    "lt" -> "<",
    "gt" -> ">",
    "quot" -> "\"",
    "apos" -> "'",
    "nbsp" -> " "
  )

  /** NFKC, lowercase, diacritics removed, remaining non letter or digit runs
    * collapsed to one space.
    */
  def fold(value: String): String =
    val composed =
      Normalizer.normalize(Option(value).getOrElse(""), Normalizer.Form.NFKC)
    val lowered = composed.toLowerCase(Locale.ROOT)
    val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
    val unmarked = marks.replaceAllIn(decomposed, "")
    val mapped =
      unmarked.flatMap(char => letterMap.getOrElse(char, char.toString))
    separators.replaceAllIn(mapped, " ").trim

  /** Folds a personal name. Apostrophes are removed rather than turned into a
    * space, so O'Brien and OBrien meet at one key.
    */
  def foldName(value: String): String =
    fold(apostrophes.replaceAllIn(Option(value).getOrElse(""), ""))

  def unescape(value: String): String =
    entityRef.replaceAllIn(
      value,
      found =>
        Regex.quoteReplacement(decodeEntity(found.group(1), found.matched))
    )

  private def decodeEntity(body: String, original: String): String =
    if body.startsWith("#") then
      val parsed =
        if body.length > 1 && (body(1) == 'x' || body(1) == 'X') then
          scala.util.Try(Integer.parseInt(body.drop(2), 16)).toOption
        else body.drop(1).toIntOption
      parsed
        .filter(code => Character.isValidCodePoint(code) && code != 0)
        .fold(original)(code => new String(Character.toChars(code)))
    else namedEntities.getOrElse(body.toLowerCase(Locale.ROOT), original)

  /** Removes inline markup tags, entity references and LaTeX commands. */
  def stripMarkup(title: String): String =
    val untagged = tags.replaceAllIn(Option(title).getOrElse(""), "")
    latexCommand.replaceAllIn(unescape(untagged), " ")

  def titleKey(title: String): String = fold(stripMarkup(title))

  def tokens(key: String): Vector[String] =
    if key.isEmpty then Vector.empty else key.split(" ").toVector

  private val stopwords = Set(
    "a",
    "an",
    "the",
    "of",
    "for",
    "and",
    "in",
    "on",
    "to",
    "with",
    "by",
    "from",
    "at",
    "is",
    "are",
    "using",
    "via",
    "its"
  )

  /** Tokens that carry topic: stopwords and one or two letter tokens dropped.
    */
  def contentTokens(key: String): Set[String] =
    tokens(key).filter(token => token.length > 2 && !stopwords(token)).toSet

  def jaccard[A](left: Set[A], right: Set[A]): Double =
    if left.isEmpty || right.isEmpty then 0.0
    else
      val shared = left.intersect(right).size.toDouble
      shared / (left.size + right.size - shared)

  /** Normalized Levenshtein similarity over at most 400 characters. */
  def editSimilarity(first: String, second: String): Double =
    val left = first.take(400)
    val right = second.take(400)
    if left.isEmpty && right.isEmpty then 1.0
    else if left.isEmpty || right.isEmpty then 0.0
    else
      var previous = Array.tabulate(right.length + 1)(identity)
      var current = new Array[Int](right.length + 1)
      for row <- 1 to left.length do
        current(0) = row
        for column <- 1 to right.length do
          val cost = if left(row - 1) == right(column - 1) then 0 else 1
          current(column) = math.min(
            previous(column - 1) + cost,
            math.min(previous(column) + 1, current(column - 1) + 1)
          )
        val swap = previous
        previous = current
        current = swap
      1.0 - previous(right.length).toDouble / math.max(
        left.length,
        right.length
      )

  def jaroWinkler(left: String, right: String): Double =
    if left == right then 1.0
    else if left.isEmpty || right.isEmpty then 0.0
    else
      val window = math.max(0, math.max(left.length, right.length) / 2 - 1)
      val leftHit = new Array[Boolean](left.length)
      val rightHit = new Array[Boolean](right.length)
      var matches = 0
      for i <- left.indices do
        val to = math.min(right.length - 1, i + window)
        var j = math.max(0, i - window)
        var found = false
        while j <= to && !found do
          if !rightHit(j) && left(i) == right(j) then
            leftHit(i) = true
            rightHit(j) = true
            matches += 1
            found = true
          j += 1
      if matches == 0 then 0.0
      else
        var swaps = 0
        var k = 0
        for i <- left.indices if leftHit(i) do
          while !rightHit(k) do k += 1
          if left(i) != right(k) then swaps += 1
          k += 1
        val m = matches.toDouble
        val jaro =
          (m / left.length + m / right.length + (m - swaps / 2.0) / m) / 3.0
        val prefix =
          left.zip(right).takeWhile((a, b) => a == b).size.min(4)
        jaro + prefix * 0.1 * (1.0 - jaro)
