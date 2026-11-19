package scholarchain.rag

import scala.collection.mutable
import scala.util.Try
import scholarchain.core.{EvidenceBundle, EvidenceType}

/** Text-free structure of one model reply. Numbers only: no part of the reply
  * is kept.
  *
  *   - `chars`: length of the whole reply as received (for Bedrock the full
  *     Converse text, not the `rawJson` trimmed to the first `{` and last `}`).
  *   - `claimsStarted`: claim objects opened inside the top-level `claims`
  *     array, counted in a partial or invalid reply too.
  *   - `repeatRatio`: share of the reply covered by repeats, 0.0 to 1.0, four
  *     decimals. See `ReplyMeasures.repeatRatio`.
  */
final case class ReplyShape(chars: Int, claimsStarted: Int, repeatRatio: Double)

/** Text-free structure of the evidence sent with one call. `evidenceItems` is
  * the bundle size. The ranking fields are set only when the bundle holds a
  * ranking fact: `rankingEntries` entries passed in, `rankedTotal` researchers
  * ranked in the store, `cutoffTies` how many of the passed entries hold the
  * value of the last (cut-off) entry, and `tieAtCutoff` the retriever's own
  * flag that more equal values were left out.
  */
final case class InputShape(
    evidenceItems: Option[Int] = None,
    rankingEntries: Option[Int] = None,
    rankedTotal: Option[Int] = None,
    cutoffTies: Option[Int] = None,
    tieAtCutoff: Option[Boolean] = None
):
  def isEmpty: Boolean = this == InputShape.none

object InputShape:
  val none: InputShape = InputShape()

  def of(bundle: EvidenceBundle): InputShape =
    val ranking = bundle.items.iterator
      .filter(_.evidenceType != EvidenceType.TextChunk)
      .flatMap(i => i.text.flatMap(t => Try(ujson.read(t)).toOption))
      .find(f => Facts.kind(f).contains("ranking"))
    val count = Some(bundle.items.size)
    ranking match
      case None       => InputShape(evidenceItems = count)
      case Some(fact) =>
        val entries = Facts.list(fact, "entries")
        val values = entries.flatMap(e => Facts.n(e, "value"))
        InputShape(
          count,
          Some(entries.size),
          Facts.n(fact, "rankedTotal").map(_.toInt),
          values.lastOption.map(last => values.count(_ == last)),
          Facts.b(fact, "tieAtCutoff")
        )

object ReplyMeasures:
  /** Length of the substring whose earlier occurrence counts as a repeat. */
  val RepeatWindow = 32

  /** Replies longer than this are measured on their first characters only,
    * which is above `ModelRetryPolicy.maxOutputChars`.
    */
  val MaxScanChars = 65536

  def of(raw: String): ReplyShape =
    ReplyShape(raw.length, claimsStarted(raw), repeatRatio(raw))

  /** The fraction of the reply covered by repeated 32-character windows. Window
    * `i` (characters `i` to `i + 31`) is a repeat when the same 32 characters
    * began at some earlier position `j < i`, overlap allowed. The covered
    * characters are the union of all repeat windows, so a reply that loops one
    * sentence approaches 1.0 and a reply whose every 32-character window is new
    * is 0.0. A reply shorter than 32 characters is 0.0. One pass, a hash set of
    * the windows seen, deterministic. The JSON frame repeats too (key names,
    * `"kind":"ranking"`), so a healthy reply is above 0.
    */
  def repeatRatio(raw: String): Double =
    val text = raw.take(MaxScanChars)
    val n = text.length
    if n < RepeatWindow then 0.0
    else
      val seen = mutable.HashSet.empty[String]
      var coveredUntil = 0
      var covered = 0
      var i = 0
      while i + RepeatWindow <= n do
        if !seen.add(text.substring(i, i + RepeatWindow)) then
          val end = i + RepeatWindow
          covered += end - math.max(i, coveredUntil)
          coveredUntil = end
        i += 1
      math.round(covered.toDouble / n * 10000.0) / 10000.0

  /** Objects opened directly inside the array under the top-level key `claims`,
    * found by scanning the text with a bracket stack and string state. A reply
    * cut mid-claim counts the claim it was cut in. A `claims` key anywhere but
    * the top level, and brackets inside strings, do not count. Nothing from the
    * text is kept; the key check buffers at most eight characters of a
    * top-level key while it is read.
    */
  def claimsStarted(raw: String): Int =
    var depth = 0
    var inString = false
    var escaped = false
    var capturing = false
    val key = new StringBuilder
    var pending = false
    var claimsDepth = -1
    var done = false
    var count = 0
    var i = 0
    while i < raw.length do
      val c = raw.charAt(i)
      if inString then
        if escaped then
          escaped = false
          if capturing then key.append('?')
        else if c == '\\' then
          escaped = true
          if capturing then key.append('?')
        else if c == '"' then
          inString = false
          if capturing then
            pending = key.toString == "claims"
            capturing = false
        else if capturing && key.length < 8 then key.append(c)
      else
        c match
          case '"' =>
            inString = true
            pending = false
            capturing = depth == 1 && claimsDepth < 0 && !done
            key.clear()
          case '[' =>
            depth += 1
            if pending && depth == 2 then claimsDepth = 2
            pending = false
          case '{' =>
            if claimsDepth == 2 && depth == 2 then count += 1
            depth += 1
            pending = false
          case ']' | '}' =>
            depth -= 1
            if claimsDepth > 0 && depth < claimsDepth then
              claimsDepth = -1
              done = true
            pending = false
          case ':' | ' ' | '\n' | '\r' | '\t' => ()
          case _                              => pending = false
      i += 1
    count
