package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.document.Document

/** The PutVectors rules that apply to one record on the wire, checked on the
  * SDK `Document` that carries its metadata. The local fakes call this so that
  * a shape the real service refuses is refused locally too (S run 7 failed
  * three of four indexes on a rule the fake did not know).
  *
  * Each rule is labelled by how it is known:
  *
  *   - CONFIRMED: seen on the real service. Empty arrays are not allowed in
  *     metadata (S run 7: `ValidationException Invalid record for key ...:
  *     Empty arrays are not allowed in metadata`).
  *   - DOCUMENTED: a figure of docs/agent-runs/phase-07/service-limits.md,
  *     captured from the public AWS pages on 2026-11-08: key up to 1,024
  *     characters, up to 50 metadata keys, filterable metadata up to 2 KB,
  *     total metadata up to 40 KB.
  *   - INFERRED: not confirmed by AWS. A value is a string, a number, a boolean
  *     or a list of strings (the shapes `MetaValue` can take); null, a map, a
  *     nested list or a list of non-strings is refused; a number is finite; and
  *     a size counts the UTF-8 bytes of each key plus the compact JSON of its
  *     value, the same count as `VectorMetadata.sizeBytes`. How AWS counts a
  *     byte is not documented.
  *
  * A failure is `AwsRejected`, the code the adapter gives a 400 from the
  * service. A record with no metadata at all passes (INFERRED).
  */
object S3VectorsRules:
  val EmptyArrayMessage = "Empty arrays are not allowed in metadata"

  private def jsonText(value: Document): String =
    if value.isString then ujson.write(ujson.Str(value.asString))
    else if value.isNumber then value.asNumber.stringValue
    else if value.isBoolean then value.asBoolean.toString
    else value.asList.asScala.map(jsonText).mkString("[", ",", "]")

  private def entryBytes(key: String, value: Document): Long =
    Text.utf8Size(key).toLong + jsonText(value).getBytes(UTF_8).length

  def check(
      key: String,
      metadata: Document,
      nonFilterableKeys: Set[String]
  ): Unit =
    def reject(reason: String): Nothing =
      Fail(
        ErrorCode.AwsRejected,
        s"local S3VectorsRules: Invalid record for key '$key': $reason"
      )
    if key.isEmpty || key.length > Limits.S3Vectors.MaxVectorKeyChars then
      reject(
        s"the key must have 1 to ${Limits.S3Vectors.MaxVectorKeyChars} " +
          "characters"
      )
    if metadata != null then
      if !metadata.isMap then reject("metadata must be a map")
      val entries = metadata.asMap.asScala.toVector.sortBy(_._1)
      if entries.size > Limits.S3Vectors.MaxMetadataKeys then
        reject(
          s"${entries.size} metadata keys, the limit is " +
            Limits.S3Vectors.MaxMetadataKeys
        )
      entries.foreach { case (name, value) =>
        if value == null || value.isNull then reject(s"metadata $name is null")
        else if value.isMap then reject(s"metadata $name is a map")
        else if value.isList then
          val items = value.asList.asScala.toVector
          if items.isEmpty then reject(s"$EmptyArrayMessage ($name)")
          if !items.forall(i => i != null && i.isString) then
            reject(s"metadata $name must be a list of strings")
        else if value.isNumber then
          val number = value.asNumber.doubleValue
          if number.isNaN || number.isInfinite then
            reject(s"metadata $name is not a finite number")
        else if !value.isString && !value.isBoolean then
          reject(s"metadata $name has an unsupported type")
      }
      val total = entries.map((name, value) => entryBytes(name, value)).sum
      val filterable = entries
        .filterNot((name, _) => nonFilterableKeys.contains(name))
        .map((name, value) => entryBytes(name, value))
        .sum
      if filterable > Limits.S3Vectors.MaxFilterableMetadataBytes then
        reject(
          s"filterable metadata is $filterable bytes, the limit is " +
            Limits.S3Vectors.MaxFilterableMetadataBytes
        )
      if total > Limits.S3Vectors.MaxTotalMetadataBytes then
        reject(
          s"metadata is $total bytes, the limit is " +
            Limits.S3Vectors.MaxTotalMetadataBytes
        )
