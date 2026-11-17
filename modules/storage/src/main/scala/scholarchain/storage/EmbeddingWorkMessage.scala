package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8

/** One SQS message of the `embedding-work` queue: one `EmbeddingWorkItem`, the
  * run and subject it belongs to, and the chunk text itself (inline, so the
  * message is self-contained; a pointer form is not defined in version 1).
  */
final case class EmbeddingWorkMessage(
    runId: String,
    subjectId: String,
    item: EmbeddingWorkItem,
    text: String
)

/** Wire schema `scholarchain.storage.embedding-work-message`, version 1: one
  * flat JSON object, UTF-8, at most 256 KiB (the SQS limit). Every key below is
  * required and no other key is accepted.
  *
  * {{{
  * schemaVersion    number  1
  * runId            string  Spark run or load label the chunk belongs to
  * subjectId        string  vector metadata `subjectId` (at most 200 chars)
  * summaryId        string  the vector key
  * index            string  a vector index name, for example paper-abstract-v1
  * documentType     string  the summary type that index takes, for example work
  * templateVersion  string  version of the template that made `text`
  * embeddingModel   string  must equal the worker's embedder model version
  * textHash         string  lowercase hex SHA-256 of `text` (UTF-8)
  * sourceRef        string  where the chunk came from (provenance)
  * text             string  the text to embed, at most 50,000 UTF-8 bytes
  * }}}
  *
  * A message the schema or the worker's checks refuse is a poison message: the
  * worker never deletes it, so the queue's `maxReceiveCount` moves it to the
  * dead-letter queue.
  */
object EmbeddingWorkMessage:
  val SchemaVersion = 1
  val MaxBodyBytes = 262144
  val MaxTextBytes = 50000
  val MaxFieldChars = 300

  val Fields: Vector[String] = Vector(
    "schemaVersion",
    "runId",
    "subjectId",
    "summaryId",
    "index",
    "documentType",
    "templateVersion",
    "embeddingModel",
    "textHash",
    "sourceRef",
    "text"
  )

  private val hexDigest = "[0-9a-f]{64}"

  def toJson(message: EmbeddingWorkMessage): ujson.Value =
    ujson.Obj(
      "schemaVersion" -> SchemaVersion,
      "runId" -> message.runId,
      "subjectId" -> message.subjectId,
      "summaryId" -> message.item.summaryId,
      "index" -> message.item.index,
      "documentType" -> message.item.documentType,
      "templateVersion" -> message.item.templateVersion,
      "embeddingModel" -> message.item.embeddingModel,
      "textHash" -> message.item.textHash,
      "sourceRef" -> message.item.sourceRef,
      "text" -> message.text
    )

  def encode(message: EmbeddingWorkMessage): String =
    ujson.write(toJson(message))

  /** The message for a chunk of text: the hash is computed here. */
  def of(
      runId: String,
      subjectId: String,
      summaryId: String,
      index: String,
      documentType: String,
      templateVersion: String,
      embeddingModel: String,
      sourceRef: String,
      text: String
  ): EmbeddingWorkMessage =
    EmbeddingWorkMessage(
      runId,
      subjectId,
      EmbeddingWorkItem(
        summaryId,
        index,
        documentType,
        Hashing.sha256(text),
        templateVersion,
        embeddingModel,
        sourceRef
      ),
      text
    )

  /** A parse refusal. `reason` is a short fixed token: it never holds message
    * content, so it is safe to log.
    */
  final case class Refused(reason: String)

  def parse(body: String): Either[Refused, EmbeddingWorkMessage] =
    if body.getBytes(UTF_8).length > MaxBodyBytes then
      Left(Refused("body_too_large"))
    else
      val json =
        try Right(ujson.read(body))
        catch case _: Exception => Left(Refused("not_json"))
      json.flatMap(fromJson)

  private def fromJson(
      json: ujson.Value
  ): Either[Refused, EmbeddingWorkMessage] =
    json.objOpt match
      case None         => Left(Refused("not_object"))
      case Some(fields) =>
        if !fields.keySet.subsetOf(Fields.toSet) then
          Left(Refused("unknown_field"))
        else
          fields.get("schemaVersion") match
            case None => Left(Refused("missing_field:schemaVersion"))
            case Some(ujson.Num(v)) if v == SchemaVersion.toDouble =>
              build(fields)
            case Some(_) => Left(Refused("unsupported_version"))

  private def text(
      fields: scala.collection.mutable.Map[String, ujson.Value],
      name: String
  ): Either[Refused, String] =
    fields.get(name) match
      case None               => Left(Refused(s"missing_field:$name"))
      case Some(ujson.Str(v)) =>
        if v.contains(Text.Nul) then Left(Refused("nul_character"))
        else Right(v)
      case Some(_) => Left(Refused(s"wrong_type:$name"))

  private def build(
      fields: scala.collection.mutable.Map[String, ujson.Value]
  ): Either[Refused, EmbeddingWorkMessage] =
    for
      runId <- short(fields, "runId")
      subjectId <- short(fields, "subjectId")
      summaryId <- short(fields, "summaryId")
      index <- short(fields, "index")
      documentType <- short(fields, "documentType")
      templateVersion <- short(fields, "templateVersion")
      model <- short(fields, "embeddingModel")
      textHash <- text(fields, "textHash")
      sourceRef <- short(fields, "sourceRef")
      body <- text(fields, "text")
      _ <- Either.cond(body.trim.nonEmpty, (), Refused("empty_text"))
      _ <- Either.cond(
        body.getBytes(UTF_8).length <= MaxTextBytes,
        (),
        Refused("text_too_large")
      )
      _ <- Either.cond(
        textHash.matches(hexDigest) && Hashing.sha256(body) == textHash,
        (),
        Refused("text_hash_mismatch")
      )
    yield EmbeddingWorkMessage(
      runId,
      subjectId,
      EmbeddingWorkItem(
        summaryId,
        index,
        documentType,
        textHash,
        templateVersion,
        model,
        sourceRef
      ),
      body
    )

  private def short(
      fields: scala.collection.mutable.Map[String, ujson.Value],
      name: String
  ): Either[Refused, String] =
    text(fields, name).flatMap { value =>
      if value.trim.isEmpty then Left(Refused(s"empty_field:$name"))
      else if value.length > MaxFieldChars then
        Left(Refused(s"field_too_long:$name"))
      else Right(value)
    }
