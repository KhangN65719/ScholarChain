package scholarchain.storage

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  HeadObjectRequest,
  HeadObjectResponse,
  ListObjectsV2Request,
  PutObjectRequest
}

/** `ObjectStore` over one ordinary S3 bucket.
  *
  *   - The bucket comes from the constructor and the region from the client, so
  *     the class reads no environment.
  *   - Objects use the bucket's default encryption only. No request carries an
  *     SSE header, and SSE-C is never used (the buckets block it).
  *   - `head` returns `None` only for a 404 that names a missing key. A 403 is
  *     never read as "absent": it raises `AwsAccessDenied` (see AwsErrors).
  *   - A write carries the content SHA-256 and the source reference as user
  *     metadata, so `head` answers without reading the body. An object written
  *     by another tool has no SHA-256 metadata; `head` then reads and hashes
  *     the body.
  *   - `write` is a conditional put (`If-None-Match: *`), so two writers racing
  *     on one immutable key cannot overwrite each other.
  *   - `list` describes every object with one HEAD each, because ListObjectsV2
  *     returns neither SHA-256 nor source reference. `maxListed` bounds it;
  *     `listKeys` and `listSizes` are the cheap forms (no HEAD).
  */
final class AwsObjectStore(
    client: S3Client,
    val bucket: String,
    maxListed: Int = 10000
) extends ObjectStore:
  Fail.unless(
    bucket.trim.nonEmpty,
    ErrorCode.InvalidKey,
    "An S3 bucket name is required"
  )
  Fail.unless(maxListed >= 1, ErrorCode.BoundExceeded, "maxListed is below 1")

  private val Sha = "sha256"
  private val Ref = "sourceref"

  private def encode(value: String): String = URLEncoder.encode(value, UTF_8)
  private def decode(value: String): String = URLDecoder.decode(value, UTF_8)

  protected def write(
      key: String,
      bytes: Array[Byte],
      sourceRef: String
  ): Unit =
    ObjectKeys.requireValid(key)
    val sha = Hashing.sha256(bytes)
    val request = PutObjectRequest
      .builder()
      .bucket(bucket)
      .key(key)
      .ifNoneMatch("*")
      .metadata(Map(Sha -> sha, Ref -> encode(sourceRef)).asJava)
      .build()
    try
      AwsErrors.call("S3 PutObject")(
        client.putObject(request, RequestBody.fromBytes(bytes))
      )
    catch
      case error: StorageException
          if error.code == ErrorCode.AwsRejected &&
            (error.getMessage.contains("HTTP 412") ||
              error.getMessage.contains("HTTP 409")) =>
        // Another writer created the key first: the same content is fine,
        // different content is the immutable-key conflict.
        head(key) match
          case Some(existing) if existing.sha256 == sha => ()
          case Some(existing)                           =>
            Fail(
              ErrorCode.ImmutableKeyConflict,
              s"Key $key holds ${existing.sha256}, refusing to replace with $sha"
            )
          case None => throw error

  private def metaOf(key: String, response: HeadObjectResponse): ObjectMeta =
    val user: Map[String, String] =
      Option(response.metadata).map(_.asScala.toMap).getOrElse(Map.empty)
    val sha = user
      .get(Sha)
      .filter(_.matches("[0-9a-f]{64}"))
      .getOrElse(get(key).map(Hashing.sha256).getOrElse(""))
    ObjectMeta(
      key,
      Option(response.contentLength).map(_.longValue).getOrElse(0L),
      sha,
      user.get(Ref).map(decode).getOrElse("")
    )

  def head(key: String): Option[ObjectMeta] =
    AwsErrors
      .optional("S3 HeadObject")(
        client.headObject(
          HeadObjectRequest.builder().bucket(bucket).key(key).build()
        )
      )
      .map(metaOf(key, _))

  def get(key: String): Option[Array[Byte]] =
    AwsErrors
      .optional("S3 GetObject")(
        client.getObjectAsBytes(
          GetObjectRequest.builder().bucket(bucket).key(key).build()
        )
      )
      .map(_.asByteArray)

  override def listKeys(prefix: String): Vector[String] =
    listObjects(prefix).map(_._1)

  /** (key, size) for every object under the prefix, following continuation
    * tokens. More than `maxListed` objects fails; nothing is truncated.
    */
  private def listObjects(prefix: String): Vector[(String, Long)] =
    val found = Vector.newBuilder[(String, Long)]
    var count = 0
    var token: Option[String] = None
    var more = true
    while more do
      val builder =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
      token.foreach(builder.continuationToken)
      val page =
        AwsErrors.call("S3 ListObjectsV2")(
          client.listObjectsV2(builder.build())
        )
      page.contents.asScala.foreach { entry =>
        count += 1
        Fail.unless(
          count <= maxListed,
          ErrorCode.BoundExceeded,
          s"More than $maxListed objects under $prefix"
        )
        found += ((
          entry.key,
          Option(entry.size).map(_.longValue).getOrElse(0L)
        ))
      }
      val next = Option(page.nextContinuationToken)
      more = Option(page.isTruncated).exists(_.booleanValue) && next.isDefined
      Fail.unless(
        !more || next != token,
        ErrorCode.AwsRejected,
        "S3 ListObjectsV2 repeated its continuation token"
      )
      token = next
    found.result().sortBy(_._1)

  /** Key and size from the ListObjectsV2 response alone: no HEAD per object,
    * the same `maxListed` bound and the same error classification as `list`.
    */
  override def listSizes(prefix: String): Vector[(String, Long)] =
    listObjects(prefix)

  def list(prefix: String): Vector[ObjectMeta] =
    listObjects(prefix).flatMap { case (key, _) => head(key) }
