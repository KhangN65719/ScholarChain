package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8

/** The document a summary row becomes: the text that is hashed, stored in
  * ordinary S3 and embedded, and the version of the template that made it.
  */
final case class RenderedDocument(text: String, templateVersion: String)

/** Port: a summary row to its document. Storage ships the pass-through default;
  * the rag module supplies richer templates. Storage never depends on rag.
  */
trait SummaryRenderer:
  def render(row: Row): RenderedDocument

/** Template `spark-summary/1`: the Spark `summaryText` unchanged. */
object PassThroughRenderer extends SummaryRenderer:
  val TemplateVersion = "spark-summary/1"

  def render(row: Row): RenderedDocument =
    RenderedDocument(Rows.str(row, "summaryText"), TemplateVersion)

/** A document fetched through a vector's `textRef` and verified. */
final case class FetchedText(
    text: String,
    key: String,
    summaryId: String,
    runId: String,
    documentType: String,
    templateVersion: String,
    textHash: String
)

/** Documents in ordinary S3 under `semantic-context/documents/`. One JSON
  * object per summary and text: the text with its source reference (summary ID,
  * run ID, template version). The key file name is `<16 hex of
  * SHA-256(summaryId)>-<16 hex of textHash>-<8 hex of
  * SHA-256(templateVersion)>.json`. It is immutable, so a changed text or a new
  * template version is a different key, never an overwrite. Only a short key
  * goes into vector metadata (`textRef`); the text never does.
  */
object SemanticDocuments:
  val Schema = "scholarchain.storage.semantic-document/1"
  val Dataset = "documents"
  val Version = "v1"

  def prefix: String = s"${S3Prefix.SemanticContext.dir}/$Dataset/"

  def key(
      runId: String,
      summaryId: String,
      textHash: String,
      templateVersion: String
  ): String =
    ObjectKeys.versioned(
      S3Prefix.SemanticContext,
      Dataset,
      Version,
      runId,
      s"${Hashing.sha256(summaryId).take(16)}-${textHash.take(16)}-" +
        s"${Hashing.sha256(templateVersion).take(8)}.json"
    )

  def sourceRef(runId: String, summaryId: String): String =
    s"spark-run:$runId/semantic-summaries#$summaryId"

  def put(
      store: ObjectStore,
      runId: String,
      summaryId: String,
      documentType: String,
      document: RenderedDocument,
      textHash: String
  ): (String, PutResult) =
    Text.requireNoNul(document.text, "Document text")
    val body = ujson.write(
      ujson.Obj(
        "schema" -> Schema,
        "summaryId" -> summaryId,
        "runId" -> runId,
        "documentType" -> documentType,
        "templateVersion" -> document.templateVersion,
        "textHash" -> textHash,
        "text" -> document.text
      )
    )
    val objectKey = key(runId, summaryId, textHash, document.templateVersion)
    (
      objectKey,
      store.putImmutable(
        objectKey,
        body.getBytes(UTF_8),
        sourceRef(runId, summaryId)
      )
    )

  private def metaText(hit: VectorMatch, name: String): String =
    hit.metadata.get(name) match
      case Some(MetaValue.Str(v)) => v
      case _                      =>
        Fail(ErrorCode.MissingText, s"Vector ${hit.key} has no $name")

  /** Follows `textRef` of a vector result through the object store. The key
    * must lie under `semantic-context/`, the document must be this vector's
    * (summary ID), and the hash of the stored text must equal the vector's
    * `textHash`. Any difference is `TextHashMismatch`.
    */
  def fetch(store: ObjectStore, hit: VectorMatch): FetchedText =
    val ref = metaText(hit, "textRef")
    val expected = metaText(hit, "textHash")
    ObjectKeys.requireValid(ref)
    Fail.unless(
      ref.startsWith(S3Prefix.SemanticContext.dir + "/"),
      ErrorCode.InvalidKey,
      s"textRef of ${hit.key} points outside ${S3Prefix.SemanticContext.dir}/"
    )
    val bytes = store
      .get(ref)
      .getOrElse(
        Fail(ErrorCode.MissingText, s"No document at $ref for ${hit.key}")
      )
    val doc =
      try ujson.read(bytes).obj
      catch
        case _: Exception =>
          Fail(ErrorCode.TextHashMismatch, s"Document $ref is not valid JSON")
    def field(name: String): String =
      doc
        .get(name)
        .flatMap(_.strOpt)
        .getOrElse(
          Fail(ErrorCode.TextHashMismatch, s"Document $ref has no $name")
        )
    val text = field("text")
    Fail.unless(
      field("schema") == Schema && field("summaryId") == hit.key,
      ErrorCode.TextHashMismatch,
      s"Document $ref does not belong to vector ${hit.key}"
    )
    Fail.unless(
      Hashing.sha256(text) == expected && field("textHash") == expected,
      ErrorCode.TextHashMismatch,
      s"Document $ref does not match the textHash of ${hit.key}"
    )
    FetchedText(
      text,
      ref,
      hit.key,
      field("runId"),
      field("documentType"),
      field("templateVersion"),
      expected
    )
