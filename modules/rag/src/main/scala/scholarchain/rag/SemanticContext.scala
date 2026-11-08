package scholarchain.rag

import java.nio.charset.StandardCharsets.UTF_8
import scholarchain.storage.{
  ErrorCode,
  FetchedText,
  Fail,
  MetaValue,
  VectorKeys,
  VectorMatch
}

/** A retrieved vector with the verified text behind its `textRef`. */
final case class RetrievedHit(
    index: String,
    hit: VectorMatch,
    text: FetchedText
)

/** One retrieved item of the semantic context. `window`, `windowStart` and
  * `windowEnd` are `null` in the JSON when the vector has none. The snippet is
  * the rendered text, or its leading words when it is longer than
  * `SemanticContext.MaxSnippetChars`; then `snippetTruncated` is true,
  * `textChars` is the full length and the whole text is fetched through
  * `textRef`. A cut is never silent.
  */
final case class ContextItem(
    vectorKey: String,
    index: String,
    documentType: String,
    distance: Double,
    window: Option[String],
    windowStart: Option[Int],
    windowEnd: Option[Int],
    subjectId: String,
    evidenceWorkIds: Vector[String],
    evidenceNodeIds: Vector[String],
    textRef: String,
    templateVersion: String,
    textHash: String,
    snippet: String,
    snippetTruncated: Boolean,
    textChars: Int
):
  VectorKeys.validate(vectorKey)
  Fail.unless(
    distance.isFinite,
    ErrorCode.MalformedRow,
    "A context item needs a finite distance"
  )
  Fail.unless(
    Vector(index, documentType, subjectId, textRef, templateVersion)
      .forall(_.trim.nonEmpty),
    ErrorCode.MalformedRow,
    s"Context item $vectorKey has a blank field"
  )
  Fail.unless(
    textHash.matches("[0-9a-f]{64}"),
    ErrorCode.MalformedRow,
    s"Context item $vectorKey has a malformed textHash"
  )
  Fail.unless(
    evidenceWorkIds.size <= SemanticContext.MaxEvidenceIds &&
      evidenceNodeIds.size <= SemanticContext.MaxEvidenceIds,
    ErrorCode.BoundExceeded,
    s"Context item $vectorKey lists more than " +
      s"${SemanticContext.MaxEvidenceIds} evidence IDs"
  )
  Fail.unless(
    snippet.length <= SemanticContext.MaxSnippetChars,
    ErrorCode.BoundExceeded,
    s"Context item $vectorKey has a snippet of ${snippet.length} characters; " +
      s"the maximum is ${SemanticContext.MaxSnippetChars}"
  )
  Fail.unless(
    if snippetTruncated then snippet.length < textChars
    else snippet.length == textChars,
    ErrorCode.MalformedRow,
    s"Context item $vectorKey: snippet length, textChars and snippetTruncated " +
      "disagree"
  )

/** The compact semantic context handed to the synthesis agent: retrieved items
  * and an explicit statement that deterministic metrics are not included.
  *
  * JSON schema `scholarchain.rag.semantic-context/1` (every key required, no
  * other key accepted):
  *
  * {{{
  * { "schema": "scholarchain.rag.semantic-context/1",
  *   "embeddingModel": "hash-embed-v1",
  *   "query": "<query text, at most 500 characters>",
  *   "deterministicMetricsIncluded": false,
  *   "metricsNotice": "<the fixed notice>",
  *   "items": [ { "vectorKey", "index", "documentType", "distance",
  *       "window", "windowStart", "windowEnd", "subjectId",
  *       "evidenceWorkIds", "evidenceNodeIds", "textRef", "templateVersion",
  *       "textHash", "snippet", "snippetTruncated", "textChars" } ] }
  * }}}
  *
  * Hard caps: at most `MaxItems` (20) items, `MaxSnippetChars` (1500)
  * characters per snippet, `MaxTotalSnippetBytes` (24 KiB) UTF-8 bytes of
  * snippets in all, `MaxEvidenceIds` evidence IDs per list and `MaxQueryChars`
  * query characters. The caps agree: `fromHits` gives each of its n items a
  * snippet budget of `MaxTotalSnippetBytes / n` bytes (`snippetBytesFor`) on
  * top of the character cap, so n snippets always fit the total and a long
  * document at the item cap is cut and marked, never a reason to refuse the
  * context. The per-item budget is 1500 characters (ASCII) for up to 16 items
  * and 1228 bytes for 20; a non-ASCII text hits the byte share first. Input
  * over a cap that arrives another way (a context built from items directly, or
  * read from JSON) is refused with `BoundExceeded`; the only cut is the marked
  * snippet cut above.
  */
final case class SemanticContext(
    schema: String,
    embeddingModel: String,
    query: String,
    deterministicMetricsIncluded: Boolean,
    metricsNotice: String,
    items: Vector[ContextItem]
):
  SemanticContext.validate(this)

  def toJson: ujson.Value = SemanticContext.toJson(this)

object SemanticContext:
  val Schema = "scholarchain.rag.semantic-context/1"
  val MaxItems = 20
  val MaxSnippetChars = 1500
  val MaxTotalSnippetBytes = 24 * 1024
  val MaxEvidenceIds = 5
  val MaxQueryChars = 500

  val MetricsNotice: String =
    "Deterministic metrics (exact counts, shortest paths, components and " +
      "bridge metrics) are not included in this context and are not answered " +
      "by vector similarity: they come from graph or metric stores, not from " +
      "this retrieval. Numbers inside a snippet restate precomputed template " +
      "text and must not be quoted as metrics."

  private val topKeys = Vector(
    "schema",
    "embeddingModel",
    "query",
    "deterministicMetricsIncluded",
    "metricsNotice",
    "items"
  )

  private val itemKeys = Vector(
    "vectorKey",
    "index",
    "documentType",
    "distance",
    "window",
    "windowStart",
    "windowEnd",
    "subjectId",
    "evidenceWorkIds",
    "evidenceNodeIds",
    "textRef",
    "templateVersion",
    "textHash",
    "snippet",
    "snippetTruncated",
    "textChars"
  )

  def validate(context: SemanticContext): Unit =
    Fail.unless(
      context.schema == Schema,
      ErrorCode.MalformedRow,
      s"Unknown context schema ${context.schema}"
    )
    Fail.unless(
      !context.deterministicMetricsIncluded &&
        context.metricsNotice == MetricsNotice,
      ErrorCode.MalformedRow,
      "A context must state that deterministic metrics are not included"
    )
    Fail.unless(
      context.embeddingModel.trim.nonEmpty,
      ErrorCode.MalformedRow,
      "A context names its embedding model"
    )
    Fail.unless(
      context.query.length <= MaxQueryChars,
      ErrorCode.BoundExceeded,
      s"Query of ${context.query.length} characters; maximum $MaxQueryChars"
    )
    Fail.unless(
      context.items.size <= MaxItems,
      ErrorCode.BoundExceeded,
      s"${context.items.size} context items; maximum $MaxItems"
    )
    val bytes = context.items.map(i => i.snippet.getBytes(UTF_8).length).sum
    Fail.unless(
      bytes <= MaxTotalSnippetBytes,
      ErrorCode.BoundExceeded,
      s"$bytes snippet bytes; maximum $MaxTotalSnippetBytes"
    )

  private def metaStr(hit: VectorMatch, name: String): Option[String] =
    hit.metadata.get(name) match
      case Some(MetaValue.Str(v)) => Some(v)
      case _                      => None

  private def metaInt(hit: VectorMatch, name: String): Option[Int] =
    hit.metadata.get(name) match
      case Some(MetaValue.Num(v)) if v.isWhole && v.abs < Int.MaxValue =>
        Some(v.toInt)
      case _ => None

  private def metaStrs(hit: VectorMatch, name: String): Vector[String] =
    hit.metadata.get(name) match
      case Some(MetaValue.Strs(v)) => v
      case _                       => Vector.empty

  private def utf8Length(text: String): Int =
    text.getBytes(UTF_8).length

  /** The per-item snippet budget in UTF-8 bytes when a context holds `count`
    * items: an equal share of `MaxTotalSnippetBytes`, so `count` snippets never
    * exceed the total cap. It is 1536 bytes or more for up to 16 items and 1228
    * bytes for 20.
    */
  def snippetBytesFor(count: Int): Int =
    MaxTotalSnippetBytes / math.max(count, 1)

  /** The end of the longest prefix that holds at most `maxChars` characters and
    * `maxBytes` UTF-8 bytes and ends on a code point boundary.
    */
  private def prefixEnd(text: String, maxChars: Int, maxBytes: Int): Int =
    var i = 0
    var bytes = 0
    var go = true
    while go && i < text.length do
      val cp = text.codePointAt(i)
      val width = Character.charCount(cp)
      val size =
        if cp < 0x80 then 1
        else if cp < 0x800 then 2
        else if cp < 0x10000 then 3
        else 4
      if i + width > maxChars || bytes + size > maxBytes then go = false
      else
        i += width
        bytes += size
    i

  /** The snippet and whether it is a cut. The snippet holds at most
    * `MaxSnippetChars` characters and `maxBytes` UTF-8 bytes. A cut ends at a
    * word boundary when the leading part has one, otherwise on a code point
    * boundary (never inside a surrogate pair).
    */
  private[rag] def snippetOf(
      text: String,
      maxBytes: Int = MaxTotalSnippetBytes
  ): (String, Boolean) =
    if text.length <= MaxSnippetChars && utf8Length(text) <= maxBytes then
      (text, false)
    else
      val limit = prefixEnd(text, MaxSnippetChars, maxBytes)
      val head = text.substring(0, limit)
      val at =
        if limit < text.length && Character.isWhitespace(text.charAt(limit))
        then limit
        else head.lastIndexWhere(Character.isWhitespace)
      val cut = if at > 0 then head.take(at).trim else head
      (if cut.isEmpty then head else cut, true)

  def item(
      retrieved: RetrievedHit,
      maxSnippetBytes: Int = MaxTotalSnippetBytes
  ): ContextItem =
    val hit = retrieved.hit
    def required(name: String): String =
      metaStr(hit, name).getOrElse(
        Fail(ErrorCode.MalformedRow, s"Vector ${hit.key} has no $name")
      )
    val (snippet, truncated) =
      snippetOf(retrieved.text.text, maxSnippetBytes)
    ContextItem(
      hit.key,
      retrieved.index,
      required("documentType"),
      hit.distance,
      metaStr(hit, "window"),
      metaInt(hit, "windowStart"),
      metaInt(hit, "windowEnd"),
      required("subjectId"),
      metaStrs(hit, "evidenceWorkIds"),
      metaStrs(hit, "evidenceNodeIds"),
      required("textRef"),
      retrieved.text.templateVersion,
      retrieved.text.textHash,
      snippet,
      truncated,
      retrieved.text.text.length
    )

  /** Builds a context from retrieved hits, refusing more than `MaxItems`. */
  def fromHits(
      query: String,
      embeddingModel: String,
      hits: Vector[RetrievedHit]
  ): SemanticContext =
    Fail.unless(
      hits.size <= MaxItems,
      ErrorCode.BoundExceeded,
      s"${hits.size} hits; a context holds at most $MaxItems"
    )
    SemanticContext(
      Schema,
      embeddingModel,
      query,
      deterministicMetricsIncluded = false,
      MetricsNotice,
      hits.map(item(_, snippetBytesFor(hits.size)))
    )

  private def opt[A](value: Option[A])(f: A => ujson.Value): ujson.Value =
    value.fold[ujson.Value](ujson.Null)(f)

  private def itemJson(i: ContextItem): ujson.Value =
    ujson.Obj(
      "vectorKey" -> i.vectorKey,
      "index" -> i.index,
      "documentType" -> i.documentType,
      "distance" -> i.distance,
      "window" -> opt(i.window)(ujson.Str(_)),
      "windowStart" -> opt(i.windowStart)(v => ujson.Num(v.toDouble)),
      "windowEnd" -> opt(i.windowEnd)(v => ujson.Num(v.toDouble)),
      "subjectId" -> i.subjectId,
      "evidenceWorkIds" -> ujson.Arr.from(i.evidenceWorkIds.map(ujson.Str(_))),
      "evidenceNodeIds" -> ujson.Arr.from(i.evidenceNodeIds.map(ujson.Str(_))),
      "textRef" -> i.textRef,
      "templateVersion" -> i.templateVersion,
      "textHash" -> i.textHash,
      "snippet" -> i.snippet,
      "snippetTruncated" -> i.snippetTruncated,
      "textChars" -> i.textChars
    )

  def toJson(context: SemanticContext): ujson.Value =
    ujson.Obj(
      "schema" -> context.schema,
      "embeddingModel" -> context.embeddingModel,
      "query" -> context.query,
      "deterministicMetricsIncluded" -> context.deterministicMetricsIncluded,
      "metricsNotice" -> context.metricsNotice,
      "items" -> ujson.Arr.from(context.items.map(itemJson))
    )

  def write(context: SemanticContext): String = ujson.write(toJson(context))

  private def bad(message: String): Nothing =
    Fail(ErrorCode.MalformedRow, s"Semantic context: $message")

  private type Fields = scala.collection.Map[String, ujson.Value]

  private def fieldsOf(
      value: ujson.Value,
      keys: Vector[String],
      what: String
  ): Fields =
    val fields = value.objOpt.getOrElse(bad(s"$what must be a JSON object"))
    val unknown = fields.keySet.diff(keys.toSet)
    Fail.unless(
      unknown.isEmpty,
      ErrorCode.MalformedRow,
      s"Semantic context: $what has unknown keys " +
        unknown.toVector.sorted.mkString(",")
    )
    val missing = keys.filterNot(fields.contains)
    Fail.unless(
      missing.isEmpty,
      ErrorCode.MalformedRow,
      s"Semantic context: $what lacks ${missing.mkString(",")}"
    )
    fields

  private def text(f: Fields, name: String): String =
    f(name).strOpt.getOrElse(bad(s"$name must be a string"))

  private def optText(f: Fields, name: String): Option[String] =
    f(name) match
      case ujson.Null => None
      case v          => Some(v.strOpt.getOrElse(bad(s"$name must be text")))

  private def whole(value: ujson.Value, name: String): Int =
    value.numOpt
      .filter(n => n.isWhole && n.abs < Int.MaxValue)
      .map(_.toInt)
      .getOrElse(bad(s"$name must be a whole number"))

  private def texts(f: Fields, name: String): Vector[String] =
    f(name).arrOpt
      .getOrElse(bad(s"$name must be an array"))
      .toVector
      .map(_.strOpt.getOrElse(bad(s"$name must hold strings")))

  private def readItem(value: ujson.Value): ContextItem =
    val f = fieldsOf(value, itemKeys, "an item")
    ContextItem(
      text(f, "vectorKey"),
      text(f, "index"),
      text(f, "documentType"),
      f("distance").numOpt.getOrElse(bad("distance must be a number")),
      optText(f, "window"),
      Option(f("windowStart"))
        .filter(_ != ujson.Null)
        .map(whole(_, "windowStart")),
      Option(f("windowEnd")).filter(_ != ujson.Null).map(whole(_, "windowEnd")),
      text(f, "subjectId"),
      texts(f, "evidenceWorkIds"),
      texts(f, "evidenceNodeIds"),
      text(f, "textRef"),
      text(f, "templateVersion"),
      text(f, "textHash"),
      text(f, "snippet"),
      f("snippetTruncated").boolOpt.getOrElse(
        bad("snippetTruncated must be a boolean")
      ),
      whole(f("textChars"), "textChars")
    )

  /** Reads and validates a context. Every cap and key rule applies. */
  def read(json: String): SemanticContext =
    val parsed =
      try ujson.read(json)
      catch case _: Exception => bad("not valid JSON")
    val f = fieldsOf(parsed, topKeys, "the context")
    val items = f("items").arrOpt
      .getOrElse(bad("items must be an array"))
      .toVector
    Fail.unless(
      items.size <= MaxItems,
      ErrorCode.BoundExceeded,
      s"${items.size} context items; maximum $MaxItems"
    )
    SemanticContext(
      text(f, "schema"),
      text(f, "embeddingModel"),
      text(f, "query"),
      f("deterministicMetricsIncluded").boolOpt.getOrElse(
        bad("deterministicMetricsIncluded must be a boolean")
      ),
      text(f, "metricsNotice"),
      items.map(readItem)
    )
