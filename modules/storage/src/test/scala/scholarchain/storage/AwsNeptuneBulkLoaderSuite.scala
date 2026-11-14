package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  GetLoaderJobStatusRequest,
  GetLoaderJobStatusResponse,
  StartLoaderJobRequest,
  StartLoaderJobResponse
}

class AwsNeptuneBulkLoaderSuite extends munit.FunSuite:
  import Fixtures.*

  private val role = "arn:aws:iam::123456789012:role/neptune-bulk-load"
  private val bucket = "graph-bucket"

  private final class RecordingSleeper extends Sleeper:
    val delays = mutable.ArrayBuffer.empty[Long]
    def sleep(millis: Long): Unit = delays += millis

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def exported(): (InMemoryObjectStore, NeptuneExportReport) =
    val projection =
      new SparkGraphProjector(source(graphTables()), LoadBounds.Default)
        .project()
    val store = new InMemoryObjectStore
    (store, NeptuneExporter.exportTo(projection, runId, store))

  private def statusDoc(
      state: String,
      total: Long = 10,
      duplicates: Long = 0,
      parsing: Long = 0,
      mismatch: Long = 0,
      insert: Long = 0,
      errors: Vector[(String, String)] = Vector.empty
  ): GetLoaderJobStatusResponse =
    val overall = ujson.Obj(
      "status" -> state,
      "totalRecords" -> total,
      "totalDuplicates" -> duplicates,
      "parsingErrors" -> parsing,
      "datatypeMismatchErrors" -> mismatch,
      "insertErrors" -> insert
    )
    val base = ujson.Obj("overallStatus" -> overall)
    if errors.nonEmpty then
      base("errors") = ujson.Obj(
        "errorLogs" -> ujson.Arr.from(errors.map { case (c, m) =>
          ujson.Obj(
            "errorCode" -> c,
            "errorMessage" -> m,
            "fileName" -> "f.csv",
            "recordNum" -> 4
          )
        })
      )
    GetLoaderJobStatusResponse
      .builder()
      .status("200 OK")
      .payload(VectorDocuments.fromJson(base))
      .build()

  /** A stub whose jobs finish with `script(loadId)`, consumed in order. */
  private def neptune(
      script: String => Vector[GetLoaderJobStatusResponse]
  ): SdkStub[NeptunedataClient] =
    val remaining =
      mutable.Map.empty[String, mutable.Queue[GetLoaderJobStatusResponse]]
    var started = 0
    new SdkStub(classOf[NeptunedataClient])
      .on("startLoaderJob") { _ =>
        started += 1
        val id = s"load-$started"
        remaining(id) = mutable.Queue.from(script(id))
        StartLoaderJobResponse
          .builder()
          .status("200 OK")
          .payload(Map("loadId" -> id).asJava)
          .build()
      }
      .on("getLoaderJobStatus") { request =>
        val id = request.asInstanceOf[GetLoaderJobStatusRequest].loadId
        val queue = remaining(id)
        if queue.size > 1 then queue.dequeue() else queue.head
      }

  private def loader(
      s: SdkStub[NeptunedataClient],
      store: ObjectStore,
      sleeper: Sleeper = NoSleep,
      maxPolls: Int = 10
  ) =
    new AwsNeptuneBulkLoader(
      s.client,
      store,
      bucket,
      "us-east-2",
      role,
      sleeper,
      1500L,
      maxPolls
    )

  private val done = (_: String) => Vector(statusDoc("LOAD_COMPLETED"))

  private def sources(s: SdkStub[NeptunedataClient]): Vector[String] =
    s.requests("startLoaderJob")
      .map(_.asInstanceOf[StartLoaderJobRequest].source)

  test("vertex files load before edge files, one file per job") {
    val (store, report) = exported()
    val s = neptune(done)
    loader(s, store).load(report.manifestKey)
    val order = sources(s).map(_.stripPrefix(s"s3://$bucket/"))
    val kinds = order.map(key => report.files.find(_.key == key).get.kind)
    assertEquals(order.toSet, report.files.map(_.key).toSet)
    assertEquals(order.size, report.files.size)
    assertEquals(kinds, kinds.sortBy(k => if k == "vertex" then 0 else 1))
    assert(kinds.head == "vertex" && kinds.last == "edge")
  }

  test("each job asks for opencypher, the role, the region and edge IDs") {
    val (store, report) = exported()
    val s = neptune(done)
    loader(s, store).load(report.manifestKey)
    val requests =
      s.requests("startLoaderJob").map(_.asInstanceOf[StartLoaderJobRequest])
    assert(requests.nonEmpty)
    requests.foreach { r =>
      assertEquals(r.formatAsString, "opencypher")
      assertEquals(r.iamRoleArn, role)
      assertEquals(r.s3BucketRegionAsString, "us-east-2")
      assertEquals(r.userProvidedEdgeIds.booleanValue, true)
      assertEquals(r.failOnError.booleanValue, true)
      assertEquals(r.queueRequest.booleanValue, true)
      assert(r.source.startsWith(s"s3://$bucket/graph/neptune/"))
      assert(r.source.endsWith(".csv"))
    }
  }

  test("the manifest is never a load source and is outside the load prefix") {
    val (store, report) = exported()
    val s = neptune(done)
    loader(s, store).load(report.manifestKey)
    val all = sources(s)
    assert(!all.exists(_.contains("load-manifest.json")))
    val dataPrefixes =
      report.files.map(f => f.key.take(f.key.lastIndexOf('/') + 1)).toSet
    assertEquals(dataPrefixes.size, 1)
    assert(dataPrefixes.forall(p => !report.manifestKey.startsWith(p)))
    // Nothing but loadable CSV sits under the load prefix.
    val under = store.listKeys(dataPrefixes.head)
    assert(under.forall(_.endsWith(".csv")), under.toString)
    assertEquals(under.toSet, report.files.map(_.key).toSet)
  }

  test("polling runs to a terminal state with the poll interval") {
    val (store, report) = exported()
    val s = neptune(_ =>
      Vector(
        statusDoc("LOAD_NOT_STARTED"),
        statusDoc("LOAD_IN_QUEUE"),
        statusDoc("LOAD_IN_PROGRESS"),
        statusDoc("LOAD_COMPLETED")
      )
    )
    val sleeper = new RecordingSleeper
    loader(s, store, sleeper).load(report.manifestKey)
    assertEquals(sleeper.delays.toSet, Set(1500L))
    assertEquals(sleeper.delays.size, 3 * report.files.size)
    val poll = s
      .requests("getLoaderJobStatus")
      .head
      .asInstanceOf[GetLoaderJobStatusRequest]
    assert(poll.details.booleanValue && poll.errors.booleanValue)
  }

  test("the loader's own counts are kept per job and summed") {
    val (store, report) = exported()
    val s = neptune(_ =>
      Vector(statusDoc("LOAD_COMPLETED", total = 10, duplicates = 4))
    )
    val result = loader(s, store).load(report.manifestKey)
    val n = report.files.size
    assertEquals(result.jobs.size, n)
    assert(result.jobs.forall(_.counts == LoaderCounts(10, 4, 0, 0, 0)))
    assertEquals(result.jobs.map(_.status).toSet, Set("LOAD_COMPLETED"))
    assertEquals(
      result.jobs.map(_.loadId),
      (1 to n).toVector.map(i => s"load-$i")
    )
    // Derived, not reported: records minus duplicates, and the duplicates.
    assertEquals(result.inserted, 6 * n)
    assertEquals(result.unchanged, 4 * n)
    assertEquals(result.updated, 0)
  }

  test("a failed job stops the load and surfaces status, counts and errors") {
    val (store, report) = exported()
    val s = neptune { id =>
      if id == "load-2" then
        Vector(
          statusDoc(
            "LOAD_FAILED",
            total = 7,
            parsing = 2,
            mismatch = 1,
            insert = 3,
            errors = Vector(
              ("PARSING_ERROR", "Bad cell"),
              ("DATA_TYPE_MISMATCH_ERROR", "Not an Int")
            )
          )
        )
      else Vector(statusDoc("LOAD_COMPLETED"))
    }
    val error =
      intercept[StorageException](loader(s, store).load(report.manifestKey))
    assertEquals(error.code, ErrorCode.BulkLoadFailed)
    val text = error.getMessage
    assert(text.contains("LOAD_FAILED"), text)
    assert(text.contains("load-2"), text)
    assert(text.contains("parsing errors 2"), text)
    assert(text.contains("datatype mismatches 1"), text)
    assert(text.contains("insert errors 3"), text)
    assert(text.contains("PARSING_ERROR f.csv record 4: Bad cell"), text)
    assert(text.contains("DATA_TYPE_MISMATCH_ERROR"), text)
    assertEquals(s.requests("startLoaderJob").size, 2)
  }

  test("a completed job that reports errors, conflicts or odd states fails") {
    val (store, report) = exported()
    Vector(
      statusDoc("LOAD_COMPLETED", parsing = 1),
      statusDoc("LOAD_COMPLETED", mismatch = 1),
      statusDoc("LOAD_COMPLETED", insert = 1),
      statusDoc("LOAD_COMMITTED_W_WRITE_CONFLICTS"),
      statusDoc("LOAD_CANCELLED_DUE_TO_ERRORS"),
      statusDoc("LOAD_S3_ACCESS_DENIED_ERROR"),
      statusDoc("LOAD_UNEXPECTED_ERROR"),
      statusDoc("SOMETHING_NEW")
    ).foreach { response =>
      val s = neptune(_ => Vector(response))
      assertEquals(
        code(loader(s, store).load(report.manifestKey)),
        ErrorCode.BulkLoadFailed
      )
      assertEquals(s.requests("startLoaderJob").size, 1)
    }
  }

  test("a job that never finishes fails after the poll bound, uncancelled") {
    val (store, report) = exported()
    val s = neptune(_ => Vector(statusDoc("LOAD_IN_PROGRESS")))
    val sleeper = new RecordingSleeper
    val error = intercept[StorageException](
      loader(s, store, sleeper, maxPolls = 3).load(report.manifestKey)
    )
    assertEquals(error.code, ErrorCode.BulkLoadFailed)
    assert(error.getMessage.contains("load-1"))
    assert(error.getMessage.contains("not cancelled"))
    assertEquals(s.requests("getLoaderJobStatus").size, 3)
    assertEquals(sleeper.delays.size, 2)
  }

  test("throttling on start is transient; a missing loadId fails") {
    val (store, report) = exported()
    val throttled = new SdkStub(classOf[NeptunedataClient])
      .on("startLoaderJob")(_ =>
        throw SdkErrors.error(429, "TooManyRequestsException")
      )
    intercept[TransientStorageException](
      loader(throttled, store).load(report.manifestKey)
    )
    val blank = new SdkStub(classOf[NeptunedataClient]).on("startLoaderJob")(
      _ => StartLoaderJobResponse.builder().status("200 OK").build()
    )
    assertEquals(
      code(loader(blank, store).load(report.manifestKey)),
      ErrorCode.BulkLoadFailed
    )
    val invalid = new SdkStub(classOf[NeptunedataClient])
      .on("startLoaderJob")(_ =>
        throw SdkErrors.error(400, "BadRequestException")
      )
    assertEquals(
      code(loader(invalid, store).load(report.manifestKey)),
      ErrorCode.AwsRejected
    )
    assertEquals(invalid.requests("startLoaderJob").size, 1)
  }

  test("a missing, altered or non-CSV file in the manifest is refused") {
    val (store, report) = exported()
    val manifest = ujson.read(store.get(report.manifestKey).get)
    def manifestWith(edit: ujson.Value => Unit): String =
      val copy = ujson.read(ujson.write(manifest))
      edit(copy)
      val key = ObjectKeys.versioned(
        S3Prefix.Graph,
        "neptune-manifest-test",
        "v1",
        runId,
        s"m-${scala.util.Random.nextInt(1000000)}.json"
      )
      store.putImmutable(key, ujson.write(copy).getBytes(UTF_8), "t")
      key
    def refuse(key: String, expected: ErrorCode) =
      val s = neptune(done)
      assertEquals(code(loader(s, store).load(key)), expected)
      assertEquals(s.requests("startLoaderJob"), Vector.empty)
    refuse(
      manifestWith(m =>
        m("files")(0)("key") = m("files")(0)("key").str + "x.csv"
      ),
      ErrorCode.MalformedManifest
    )
    refuse(
      manifestWith(m => m("files")(0)("sha256") = "0" * 64),
      ErrorCode.ChecksumMismatch
    )
    refuse(
      manifestWith(m => m("files")(0)("key") = report.manifestKey),
      ErrorCode.MalformedManifest
    )
    refuse(
      manifestWith(m => m("files")(0)("kind") = "other"),
      ErrorCode.MalformedManifest
    )
    refuse("graph/no-such-manifest.json", ErrorCode.MalformedManifest)
  }

  test("constructor refuses a blank bucket or a non-role ARN") {
    val s = new SdkStub(classOf[NeptunedataClient])
    val store = new InMemoryObjectStore
    assertEquals(
      code(new AwsNeptuneBulkLoader(s.client, store, " ", "us-east-2", role)),
      ErrorCode.InvalidKey
    )
    assertEquals(
      code(
        new AwsNeptuneBulkLoader(
          s.client,
          store,
          bucket,
          "us-east-2",
          "arn:aws:iam::1:user/x"
        )
      ),
      ErrorCode.InvalidKey
    )
  }

  test("the payload parser reads the loader's overallStatus and errors") {
    val response = statusDoc(
      "LOAD_FAILED",
      total = 9,
      duplicates = 1,
      parsing = 2,
      errors = (1 to 8).toVector.map(i => ("E", s"m$i"))
    )
    val (state, counts, samples) = AwsNeptuneBulkLoader.parse(response.payload)
    assertEquals(state, "LOAD_FAILED")
    assertEquals(counts, LoaderCounts(9, 1, 2, 0, 0))
    assertEquals(samples.size, AwsNeptuneBulkLoader.MaxErrorSamples)
    val (unknown, zero, none) = AwsNeptuneBulkLoader.parse(Document.fromNull())
    assertEquals(unknown, "UNKNOWN")
    assertEquals(zero, LoaderCounts(0, 0, 0, 0, 0))
    assertEquals(none, Vector.empty)
  }
