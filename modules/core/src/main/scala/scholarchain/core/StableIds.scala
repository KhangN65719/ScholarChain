package scholarchain.core

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.{Base64, HexFormat, UUID}

object StableIds:
  private val namespacePattern = "[a-z][a-z0-9-]*"
  private val hashPattern = "[0-9a-f]{64}"
  private val uuidPattern =
    "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder

  def work(namespace: String, sourceId: String): String =
    external("work", namespace, sourceId)

  def workFromFingerprint(
      namespace: String,
      sourceVersion: String,
      fingerprint: String
  ): String =
    require(namespace.matches(namespacePattern), "Malformed source namespace")
    digestId("work", namespace, sourceVersion, fingerprint)

  /** Call after the source identifier has been normalized and validated by its
    * producer.
    */
  def researcherFromExternal(namespace: String, canonicalId: String): String =
    external("researcher", namespace, canonicalId)

  def researcherFromIdentity(
      identityVersion: String,
      identityKey: String
  ): String =
    digestId("researcher", identityVersion, identityKey)

  def mention(workId: String, authorPosition: Int): String =
    requireKind("work", workId)
    require(authorPosition > 0, "Author position must be one-based")
    digestId("mention", workId, authorPosition.toString)

  def orderedPair(left: String, right: String): (String, String) =
    require(left != right, "An undirected pair requires distinct endpoints")
    if left < right then (left, right) else (right, left)

  def coauthorEdge(
      left: String,
      right: String,
      window: YearWindow,
      policyVersion: String
  ): String =
    requireKind("researcher", left)
    requireKind("researcher", right)
    val (a, b) = orderedPair(left, right)
    digestId(
      "coauthor",
      a,
      b,
      window.startYear.toString,
      window.endYear.toString,
      policyVersion
    )

  def chunk(
      entityId: String,
      sourceVersion: String,
      documentType: String,
      ordinal: Option[Int],
      text: String
  ): String =
    require(ordinal.forall(_ >= 0), "Chunk ordinal must be nonnegative")
    val textHash = HexFormat
      .of()
      .formatHex(
        MessageDigest.getInstance("SHA-256").digest(utf8Bytes(text))
      )
    digestId(
      "chunk",
      entityId,
      sourceVersion,
      documentType,
      ordinal.fold("none")(_.toString),
      textHash
    )

  def graphEdge(
      edgeType: GraphEdgeType,
      from: String,
      to: String,
      policyVersion: String,
      sourceEvidenceIds: Vector[String]
  ): String =
    require(sourceEvidenceIds.nonEmpty, "A graph edge requires source evidence")
    sourceEvidenceIds.foreach(requireKind("evidence", _))
    val (a, b) =
      if edgeType == GraphEdgeType.CoauthoredWith then orderedPair(from, to)
      else (from, to)
    digestId(
      "edge",
      (Vector(
        edgeType.wire,
        a,
        b,
        policyVersion
      ) ++ sourceEvidenceIds.distinct.sorted)*
    )

  def evidence(
      source: SourceType,
      sourceVersion: String,
      sourceRecordId: String,
      kind: EvidenceType,
      locator: String
  ): String =
    digestId(
      "evidence",
      source.wire,
      sourceVersion,
      sourceRecordId,
      kind.wire,
      locator
    )

  def newRun(): String = s"run:v1:${UUID.randomUUID()}"

  private[core] def requireKind(kind: String, value: String): Unit =
    require(isKind(kind, value), s"Malformed $kind identifier")

  private def isKind(kind: String, value: String): Boolean =
    if value == null then false
    else
      value.split(":", -1).toList match
        case actual :: "v1" :: payload :: Nil =>
          actual == kind && payload.matches(
            if kind == "run" then uuidPattern else hashPattern
          )
        case actual :: "v1" :: namespace :: payload :: Nil
            if actual == kind && Set("work", "researcher").contains(kind) =>
          namespace.matches(namespacePattern) && isCanonicalPayload(payload)
        case _ => false

  private def isCanonicalPayload(payload: String): Boolean =
    try
      val bytes = decoder.decode(payload)
      val decoded = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString
      decoded.nonEmpty && decoded == decoded.trim && !decoded.exists(
        _.isControl
      ) &&
      encoder.encodeToString(bytes) == payload
    catch
      case _: IllegalArgumentException | _: CharacterCodingException => false

  private def external(
      kind: String,
      namespace: String,
      canonicalId: String
  ): String =
    require(namespace.matches(namespacePattern), "Malformed source namespace")
    require(
      canonicalId.nonEmpty && canonicalId == canonicalId.trim && !canonicalId
        .exists(_.isControl),
      "Malformed canonical source identifier"
    )
    s"$kind:v1:$namespace:${encoder.encodeToString(utf8Bytes(canonicalId))}"

  private def utf8Bytes(value: String): Array[Byte] =
    try
      val encoded = UTF_8.newEncoder().encode(CharBuffer.wrap(value))
      val bytes = new Array[Byte](encoded.remaining())
      encoded.get(bytes)
      bytes
    catch
      case error: CharacterCodingException =>
        throw new IllegalArgumentException(
          "Identifier input contains malformed Unicode",
          error
        )

  // Length framing prevents tuples with shared delimiters from hashing to the same input.
  private def digestId(kind: String, parts: String*): String =
    val digest = MessageDigest.getInstance("SHA-256")
    (Vector(kind, "v1") ++ parts).foreach { part =>
      require(
        part != null && part.trim.nonEmpty,
        "Identifier components must be nonblank"
      )
      val bytes = utf8Bytes(part)
      digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array())
      digest.update(bytes)
    }
    s"$kind:v1:${HexFormat.of().formatHex(digest.digest())}"
