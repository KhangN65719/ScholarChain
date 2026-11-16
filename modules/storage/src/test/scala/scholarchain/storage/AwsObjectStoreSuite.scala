package scholarchain.storage

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  GetObjectResponse,
  HeadObjectRequest,
  HeadObjectResponse,
  ListObjectsV2Request,
  ListObjectsV2Response,
  NoSuchKeyException,
  PutObjectRequest,
  PutObjectResponse,
  S3Object
}

class AwsObjectStoreSuite extends munit.FunSuite:
  private val key = "curated/t/v=v1/run=r/a.json"
  private val body = "hello".getBytes(UTF_8)
  private val sha = Hashing.sha256(body)

  private def stub = new SdkStub(classOf[S3Client])

  private def store(s: SdkStub[S3Client]) =
    new AwsObjectStore(s.client, "bucket-x")

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def headResponse(
      length: Long,
      meta: Map[String, String]
  ): HeadObjectResponse =
    HeadObjectResponse
      .builder()
      .contentLength(length)
      .metadata(meta.asJava)
      .build()

  private val notFound = (_: AnyRef) => throw SdkErrors.error(404, "NotFound")
  private val putOk = (_: AnyRef) => PutObjectResponse.builder().build()

  test("putImmutable writes once with default encryption, sha and source") {
    val s = stub.on("headObject")(notFound).on("putObject")(putOk)
    val result = store(s).putImmutable(key, body, "spark-run:run:v1:x/a")
    assert(result.created)
    assertEquals(s.names, Vector("headObject", "putObject"))
    val put = s.requests("putObject").head.asInstanceOf[PutObjectRequest]
    assertEquals(put.bucket, "bucket-x")
    assertEquals(put.key, key)
    assertEquals(put.ifNoneMatch, "*")
    assertEquals(put.metadata.get("sha256"), sha)
    assertEquals(
      URLDecoder.decode(put.metadata.get("sourceref"), UTF_8),
      "spark-run:run:v1:x/a"
    )
    val sent = s
      .secondArguments("putObject")
      .head
      .asInstanceOf[RequestBody]
      .contentStreamProvider
      .newStream()
      .readAllBytes()
    assertEquals(sent.toVector, body.toVector)
    // Default encryption only: no SSE of any kind on the request.
    assertEquals(put.serverSideEncryption, null)
    assertEquals(put.sseCustomerAlgorithm, null)
    assertEquals(put.sseCustomerKey, null)
    assertEquals(put.ssekmsKeyId, null)
  }

  test("an existing key with the same content is a no-op, no put") {
    val s = stub.on("headObject")(_ =>
      headResponse(5, Map("sha256" -> sha, "sourceref" -> "r"))
    )
    val result = store(s).putImmutable(key, body, "other")
    assert(!result.created)
    assertEquals(s.requests("putObject"), Vector.empty)
  }

  test("an existing key with other content is an immutable-key conflict") {
    val s =
      stub.on("headObject")(_ => headResponse(5, Map("sha256" -> ("0" * 64))))
    assertEquals(
      code(store(s).putImmutable(key, body, "r")),
      ErrorCode.ImmutableKeyConflict
    )
    assertEquals(s.requests("putObject"), Vector.empty)
  }

  private def lostRace(existingSha: String): Either[ErrorCode, Boolean] =
    var heads = 0
    val s = stub
      .on("headObject") { _ =>
        heads += 1
        if heads == 1 then throw SdkErrors.error(404, "NotFound")
        else headResponse(5, Map("sha256" -> existingSha))
      }
      .on("putObject")(_ => throw SdkErrors.error(412, "PreconditionFailed"))
    try Right(store(s).putImmutable(key, body, "r").created)
    catch case error: StorageException => Left(error.code)

  test("a lost write race: same content passes, other content conflicts") {
    assertEquals(lostRace(sha), Right(true))
    assertEquals(lostRace("0" * 64), Left(ErrorCode.ImmutableKeyConflict))
  }

  test("head: 404 gives None, 403 is access denied and never None") {
    val missing = stub.on("headObject")(notFound)
    assertEquals(store(missing).head(key), None)
    val noSuchKey = stub.on("headObject")(_ =>
      throw NoSuchKeyException.builder().statusCode(404).build()
    )
    assertEquals(store(noSuchKey).head(key), None)
    val forbidden =
      stub.on("headObject")(_ => throw SdkErrors.error(403, "Forbidden"))
    assertEquals(code(store(forbidden).head(key)), ErrorCode.AwsAccessDenied)
    val noBucket =
      stub.on("headObject")(_ => throw SdkErrors.error(404, "NoSuchBucket"))
    assertEquals(code(store(noBucket).head(key)), ErrorCode.AwsRejected)
  }

  test("head reads sha and source from metadata, or hashes the body") {
    val tagged = stub.on("headObject")(_ =>
      headResponse(
        5,
        Map(
          "sha256" -> sha,
          "sourceref" -> URLEncoder.encode("spark-run:é", UTF_8)
        )
      )
    )
    assertEquals(
      store(tagged).head(key),
      Some(ObjectMeta(key, 5L, sha, "spark-run:é"))
    )
    val foreign = stub
      .on("headObject")(_ => headResponse(5, Map.empty))
      .on("getObjectAsBytes")(_ =>
        ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), body)
      )
    assertEquals(
      store(foreign).head(key),
      Some(ObjectMeta(key, 5L, sha, ""))
    )
  }

  test("get: bytes, 404 gives None, 403 is denied") {
    val ok = stub.on("getObjectAsBytes")(_ =>
      ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), body)
    )
    assertEquals(store(ok).get(key).map(_.toVector), Some(body.toVector))
    val request = ok.requests("getObjectAsBytes").head
    assertEquals(request.asInstanceOf[GetObjectRequest].key, key)
    val missing = stub.on("getObjectAsBytes")(_ =>
      throw NoSuchKeyException.builder().statusCode(404).build()
    )
    assertEquals(store(missing).get(key), None)
    val denied = stub.on("getObjectAsBytes")(_ =>
      throw SdkErrors.error(403, "AccessDenied")
    )
    assertEquals(code(store(denied).get(key)), ErrorCode.AwsAccessDenied)
  }

  private def page(
      keys: Vector[String],
      next: Option[String]
  ): ListObjectsV2Response =
    val objects = keys.map(k => S3Object.builder().key(k).size(5L).build())
    val builder = ListObjectsV2Response
      .builder()
      .contents(objects.asJava)
      .isTruncated(next.isDefined)
    next.foreach(builder.nextContinuationToken)
    builder.build()

  test("list follows continuation tokens under a prefix and describes each") {
    val s = stub
      .on("listObjectsV2") { request =>
        Option(
          request.asInstanceOf[ListObjectsV2Request].continuationToken
        ) match
          case None       => page(Vector("p/b", "p/a"), Some("t1"))
          case Some("t1") => page(Vector("p/c"), None)
          case other      => fail(s"unexpected token $other")
      }
      .on("headObject") { request =>
        val k = request.asInstanceOf[HeadObjectRequest].key
        headResponse(5, Map("sha256" -> Hashing.sha256(k), "sourceref" -> k))
      }
    val listed = store(s).list("p/")
    assertEquals(listed.map(_.key), Vector("p/a", "p/b", "p/c"))
    assertEquals(listed.map(_.sha256), listed.map(m => Hashing.sha256(m.key)))
    val requests =
      s.requests("listObjectsV2").map(_.asInstanceOf[ListObjectsV2Request])
    assertEquals(requests.map(_.prefix), Vector("p/", "p/"))
    assertEquals(requests.map(_.bucket), Vector("bucket-x", "bucket-x"))
    assertEquals(
      requests.map(r => Option(r.continuationToken)),
      Vector(None, Some("t1"))
    )
  }

  test("listKeys lists without a HEAD per object") {
    val s = stub.on("listObjectsV2")(_ => page(Vector("a", "b"), None))
    assertEquals(store(s).listKeys(""), Vector("a", "b"))
    assertEquals(s.requests("headObject"), Vector.empty)
  }

  test("listSizes follows tokens, sorts, and makes no HEAD per object") {
    val s = stub.on("listObjectsV2") { request =>
      Option(request.asInstanceOf[ListObjectsV2Request].continuationToken) match
        case None       => page(Vector("p/b", "p/a"), Some("t1"))
        case Some("t1") => page(Vector("p/c"), None)
        case other      => fail(s"unexpected token $other")
    }
    assertEquals(
      store(s).listSizes("p/"),
      Vector(("p/a", 5L), ("p/b", 5L), ("p/c", 5L))
    )
    assertEquals(s.requests("headObject"), Vector.empty)
    assertEquals(s.requests("getObjectAsBytes"), Vector.empty)
    val requests =
      s.requests("listObjectsV2").map(_.asInstanceOf[ListObjectsV2Request])
    assertEquals(requests.map(_.prefix), Vector("p/", "p/"))
    assertEquals(
      requests.map(r => Option(r.continuationToken)),
      Vector(None, Some("t1"))
    )
  }

  test("listSizes answers what list answers, without the HEADs") {
    val s = stub
      .on("listObjectsV2")(_ => page(Vector("p/b", "p/a"), None))
      .on("headObject") { request =>
        val k = request.asInstanceOf[HeadObjectRequest].key
        headResponse(5, Map("sha256" -> Hashing.sha256(k), "sourceref" -> k))
      }
    val viaList = store(s).list("p/").map(m => (m.key, m.bytes))
    val headsAfterList = s.requests("headObject").size
    assertEquals(headsAfterList, 2)
    assertEquals(store(s).listSizes("p/"), viaList)
    assertEquals(s.requests("headObject").size, headsAfterList)
  }

  test("listSizes is bounded, refuses a repeated token, classifies errors") {
    val big = stub.on("listObjectsV2")(_ => page(Vector("a", "b", "c"), None))
    assertEquals(
      code(new AwsObjectStore(big.client, "b", 2).listSizes("")),
      ErrorCode.BoundExceeded
    )
    val loop = stub.on("listObjectsV2")(_ => page(Vector("a"), Some("same")))
    assertEquals(code(store(loop).listSizes("")), ErrorCode.AwsRejected)
    val throttled =
      stub.on("listObjectsV2")(_ => throw SdkErrors.error(503, "SlowDown"))
    intercept[TransientStorageException](store(throttled).listSizes(""))
    val denied =
      stub.on("listObjectsV2")(_ => throw SdkErrors.error(403, "AccessDenied"))
    assertEquals(code(store(denied).listSizes("")), ErrorCode.AwsAccessDenied)
  }

  test("list is bounded and a repeated token is refused") {
    val big = stub.on("listObjectsV2")(_ => page(Vector("a", "b", "c"), None))
    assertEquals(
      code(new AwsObjectStore(big.client, "b", 2).listKeys("")),
      ErrorCode.BoundExceeded
    )
    val loop = stub.on("listObjectsV2")(_ => page(Vector("a"), Some("same")))
    assertEquals(code(store(loop).listKeys("")), ErrorCode.AwsRejected)
  }

  test("throttling and 5xx are transient, other 4xx are not retried") {
    Vector(
      (429, "SlowDown"),
      (503, "ServiceUnavailable"),
      (500, "InternalError")
    )
      .foreach { case (status, errorCode) =>
        val s = stub.on("getObjectAsBytes")(_ =>
          throw SdkErrors.error(status, errorCode)
        )
        intercept[TransientStorageException](store(s).get(key))
      }
    val bad = stub.on("getObjectAsBytes")(_ =>
      throw SdkErrors.error(400, "InvalidRequest")
    )
    assertEquals(code(store(bad).get(key)), ErrorCode.AwsRejected)
    assertEquals(bad.requests("getObjectAsBytes").size, 1)
  }

  test("an empty bucket name is refused") {
    assertEquals(
      code(new AwsObjectStore(stub.client, " ")),
      ErrorCode.InvalidKey
    )
  }
