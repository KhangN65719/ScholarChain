package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.awscore.exception.{
  AwsErrorDetails,
  AwsServiceException
}
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  GetLoaderJobStatusRequest,
  GetLoaderJobStatusResponse,
  Parallelism,
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
      maxPolls: Int = 10,
      failOnError: Boolean = true,
      log: String => Unit = _ => (),
      edgeParallelism: Parallelism = Parallelism.LOW
  ) =
    new AwsNeptuneBulkLoader(
      s.client,
      store,
      bucket,
      "us-east-2",
      role,
      sleeper,
      1500L,
      maxPolls,
      failOnError,
      edgeParallelism,
      log
    )

  private val done = (_: String) => Vector(statusDoc("LOAD_COMPLETED"))

  private def sources(s: SdkStub[NeptunedataClient]): Vector[String] =
    s.requests("startLoaderJob")
      .map(_.asInstanceOf[StartLoaderJobRequest].source)

  private def wantsErrors(request: GetLoaderJobStatusRequest): Boolean =
    Option(request.errors).exists(_.booleanValue)

  private def errorCalls(
      s: SdkStub[NeptunedataClient]
  ): Vector[GetLoaderJobStatusRequest] =
    s.requests("getLoaderJobStatus")
      .map(_.asInstanceOf[GetLoaderJobStatusRequest])
      .filter(wantsErrors)

  private final class Captured:
    val all = mutable.ArrayBuffer.empty[String]
    def add(line: String): Unit = all += line

    def starting(prefix: String): Vector[String] =
      all.toVector.filter(_.startsWith(prefix))

  private val edgeFile =
    "s3://graph-bucket/graph/neptune/v=v1/run=r/edges-COAUTHORED_WITH.csv"

  private def errorLog(
      record: Int,
      message: String,
      code: String = "E",
      file: String = edgeFile
  ): ujson.Value =
    ujson.Obj(
      "errorCode" -> code,
      "errorMessage" -> message,
      "fileName" -> file,
      "recordNum" -> record
    )

  private def payloadOf(json: ujson.Value): GetLoaderJobStatusResponse =
    GetLoaderJobStatusResponse
      .builder()
      .status("200 OK")
      .payload(VectorDocuments.fromJson(json))
      .build()

  private def errorsPayload(
      logs: Vector[ujson.Value]
  ): GetLoaderJobStatusResponse =
    payloadOf(
      ujson.Obj("errors" -> ujson.Obj("errorLogs" -> ujson.Arr.from(logs)))
    )

  private def logPage(
      from: Int,
      count: Int,
      message: Int => String = i => s"m$i"
  ): GetLoaderJobStatusResponse =
    errorsPayload(
      (from until from + count).toVector.map(i => errorLog(i, message(i)))
    )

  /** A stub that answers a status poll with `status(loadId)` and an error-page
    * request (`errors=true`) with `errorPage(request)`.
    */
  private def paged(
      status: String => GetLoaderJobStatusResponse,
      errorPage: GetLoaderJobStatusRequest => GetLoaderJobStatusResponse
  ): SdkStub[NeptunedataClient] =
    var started = 0
    new SdkStub(classOf[NeptunedataClient])
      .on("startLoaderJob") { _ =>
        started += 1
        StartLoaderJobResponse
          .builder()
          .status("200 OK")
          .payload(Map("loadId" -> s"load-$started").asJava)
          .build()
      }
      .on("getLoaderJobStatus") { r =>
        val request = r.asInstanceOf[GetLoaderJobStatusRequest]
        if wantsErrors(request) then errorPage(request)
        else status(request.loadId)
      }

  private def serviceError(status: Int, message: String) =
    AwsServiceException
      .builder()
      .statusCode(status)
      .message(message)
      .awsErrorDetails(
        AwsErrorDetails
          .builder()
          .errorCode("AccessDeniedException")
          .errorMessage(message)
          .build()
      )
      .build()
      .asInstanceOf[AwsServiceException]

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
    // Routine polls ask for status only; error records have their own call.
    assert(poll.details.booleanValue && !wantsErrors(poll))
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

  // WO 112: error records, failOnError, edge parallelism ---------------------

  private def lastJob(report: NeptuneExportReport): String =
    s"load-${report.files.size}"

  test("a failed load fetches its error records into lines and text") {
    val (store, report) = exported()
    val lines = new Captured
    val three = Vector(
      errorLog(
        7,
        "Either from vertex, 'a', or to vertex, 'b', is not present.",
        "FROM_OR_TO_VERTEX_ARE_MISSING"
      ),
      errorLog(8, "second"),
      errorLog(9, "third")
    )
    val s = paged(
      _ => statusDoc("LOAD_FAILED", total = 92256, insert = 3),
      _ => errorsPayload(three)
    )
    val error = intercept[StorageException](
      loader(s, store, log = lines.add).load(report.manifestKey)
    )
    assertEquals(error.code, ErrorCode.BulkLoadFailed)
    assertEquals(
      lines.all.toVector,
      Vector(
        "NEPTUNE_LOAD_ERROR job=1 file=edges-COAUTHORED_WITH.csv " +
          "code=FROM_OR_TO_VERTEX_ARE_MISSING record=7 message=Either from " +
          "vertex, 'a', or to vertex, 'b', is not present.",
        "NEPTUNE_LOAD_ERROR job=1 file=edges-COAUTHORED_WITH.csv code=E " +
          "record=8 message=second",
        "NEPTUNE_LOAD_ERROR job=1 file=edges-COAUTHORED_WITH.csv code=E " +
          "record=9 message=third",
        "NEPTUNE_LOAD_ERRORS_TOTAL n=3 named=3 job=1"
      )
    )
    val text = error.getMessage
    assert(text.contains("LOAD_FAILED") && text.contains("insert errors 3"))
    assert(
      text.contains(
        "FROM_OR_TO_VERTEX_ARE_MISSING edges-COAUTHORED_WITH.csv record 7: " +
          "Either from vertex"
      ),
      text
    )
    assert(text.contains("record 9: third"), text)
    assert(!text.contains("\n"))
    // One error call, with the documented parameters; polls ask for none.
    val calls = errorCalls(s)
    assertEquals(calls.size, 1)
    assertEquals(calls.head.loadId, "load-1")
    assertEquals(calls.head.page.intValue, 1)
    assertEquals(calls.head.errorsPerPage.intValue, 20)
    assert(calls.head.details.booleanValue)
    assertEquals(s.requests("startLoaderJob").size, 1)
  }

  test("a failed error fetch is logged and the original failure still raised") {
    val (store, report) = exported()
    val id = "123456789012"
    val message =
      s"User: arn:aws:sts::$id:assumed-role/loader/i is not authorized to " +
        "perform: neptune-db:GetLoaderStatus " + ("x" * 400)
    Vector(
      serviceError(403, message),
      SdkErrors.error(429, "TooManyRequestsException"),
      new IllegalStateException(message)
    ).foreach { failure =>
      val lines = new Captured
      val s = paged(
        _ => statusDoc("LOAD_FAILED", total = 9, insert = 3),
        _ => throw failure
      )
      val error = intercept[StorageException](
        loader(s, store, log = lines.add).load(report.manifestKey)
      )
      assertEquals(error.code, ErrorCode.BulkLoadFailed)
      assert(error.getMessage.contains("LOAD_FAILED"), error.getMessage)
      assert(error.getMessage.contains("insert errors 3"), error.getMessage)
      assert(error.getMessage.contains("error list is incomplete"))
      val failed = lines.starting("NEPTUNE_LOAD_ERROR_FETCH_FAILED reason=")
      assertEquals(failed.size, 1)
      assert(
        failed.head
          .stripPrefix("NEPTUNE_LOAD_ERROR_FETCH_FAILED reason=")
          .length <= 300
      )
      assertEquals(
        lines.starting("NEPTUNE_LOAD_ERROR "),
        Vector.empty[String]
      )
      assertEquals(
        lines.starting("NEPTUNE_LOAD_ERRORS_TOTAL"),
        Vector("NEPTUNE_LOAD_ERRORS_TOTAL n=3 named=0 job=1")
      )
      (lines.all.toVector :+ error.getMessage).foreach { text =>
        assert(!text.contains(id), text)
      }
      if Option(failure.getMessage).exists(_.contains(id)) then
        assert(failed.head.contains("<account-id>"), failed.head)
    }
  }

  test("a clean success makes no error call and prints no error line") {
    val (store, report) = exported()
    val lines = new Captured
    val s = neptune(done)
    loader(s, store, log = lines.add).load(report.manifestKey)
    assertEquals(
      s.requests("getLoaderJobStatus").size,
      report.files.size
    )
    assertEquals(errorCalls(s), Vector.empty)
    assertEquals(lines.all.toVector, Vector.empty[String])
  }

  test("account IDs are masked in the lines and in the failure text") {
    val (store, report) = exported()
    val lines = new Captured
    val id = "123456789012"
    val s = paged(
      _ => statusDoc("LOAD_FAILED", insert = 1),
      _ =>
        errorsPayload(
          Vector(
            errorLog(
              1,
              s"User arn:aws:sts::$id:role/x denied",
              "AccessDenied",
              s"s3://b/edges-$id.csv"
            )
          )
        )
    )
    val error = intercept[StorageException](
      loader(s, store, log = lines.add).load(report.manifestKey)
    )
    (lines.all.toVector :+ error.getMessage).foreach { text =>
      assert(!text.contains(id), text)
    }
    assert(
      lines.all.head.contains(
        "file=edges-<account-id>.csv code=AccessDenied record=1 " +
          "message=User arn:aws:sts::<account-id>:role/x denied"
      ),
      lines.all.head
    )
    assert(error.getMessage.contains("arn:aws:sts::<account-id>:role/x"))
  }

  test("at most 20 lines are printed; the text names 20 and counts the rest") {
    val (store, report) = exported()
    val lines = new Captured
    val s = paged(
      _ => statusDoc("LOAD_FAILED", insert = 25),
      _ => logPage(1, 25)
    )
    val error = intercept[StorageException](
      loader(s, store, log = lines.add).load(report.manifestKey)
    )
    val shown = lines.starting("NEPTUNE_LOAD_ERROR ")
    assertEquals(shown.size, 20)
    assert(shown.last.endsWith("record=20 message=m20"), shown.last)
    assertEquals(
      lines.starting("NEPTUNE_LOAD_ERRORS_TOTAL"),
      Vector("NEPTUNE_LOAD_ERRORS_TOTAL n=25 named=25 job=1")
    )
    assert(error.getMessage.contains("record 20: m20"))
    assert(!error.getMessage.contains("record 21: m21"))
    assert(error.getMessage.contains("(+5 more error records not shown here)"))
  }

  test("a message is cut at 300 characters and the text at its total cap") {
    val (store, report) = exported()
    val lines = new Captured
    val s = paged(
      _ => statusDoc("LOAD_FAILED", insert = 20),
      _ => logPage(1, 20, i => s"$i " + ("z" * 500))
    )
    val error = intercept[StorageException](
      loader(s, store, log = lines.add).load(report.manifestKey)
    )
    val shown = lines.starting("NEPTUNE_LOAD_ERROR ")
    assertEquals(shown.size, 20)
    shown.foreach(l => assertEquals(l.split("message=", 2)(1).length, 300))
    val text = error.getMessage
    assert(text.contains("(+15 more error records not shown here)"), text)
    assert(text.length < 3000, text.length.toString)
  }

  test("edge jobs run at LOW parallelism and vertex jobs send none") {
    val (store, report) = exported()
    val s = neptune(done)
    loader(s, store).load(report.manifestKey)
    val requests =
      s.requests("startLoaderJob").map(_.asInstanceOf[StartLoaderJobRequest])
    val (edges, vertices) =
      requests.partition(_.source.contains("/edges-"))
    assert(edges.nonEmpty && vertices.nonEmpty)
    edges.foreach(r => assertEquals(r.parallelismAsString, "LOW"))
    vertices.foreach(r => assertEquals(r.parallelismAsString, null))
    val medium = neptune(done)
    loader(medium, store, edgeParallelism = Parallelism.MEDIUM)
      .load(report.manifestKey)
    medium
      .requests("startLoaderJob")
      .map(_.asInstanceOf[StartLoaderJobRequest])
      .filter(_.source.contains("/edges-"))
      .foreach(r => assertEquals(r.parallelismAsString, "MEDIUM"))
  }

  test("failOnError reaches every start request; the default is true") {
    val (store, report) = exported()
    val off = neptune(done)
    loader(off, store, failOnError = false).load(report.manifestKey)
    off
      .requests("startLoaderJob")
      .foreach(r =>
        assertEquals(
          r.asInstanceOf[StartLoaderJobRequest].failOnError.booleanValue,
          false
        )
      )
    val on = neptune(done)
    loader(on, store).load(report.manifestKey)
    on
      .requests("startLoaderJob")
      .foreach(r =>
        assertEquals(
          r.asInstanceOf[StartLoaderJobRequest].failOnError.booleanValue,
          true
        )
      )
  }

  /** The last (edge) job completes with 3 insert errors named by the stub. */
  private def tolerated(
      report: NeptuneExportReport,
      errorPage: GetLoaderJobStatusRequest => GetLoaderJobStatusResponse,
      insert: Int = 3,
      state: String = "LOAD_COMPLETED"
  ) =
    paged(
      id =>
        if id == lastJob(report) then
          statusDoc(
            state,
            total = 100,
            duplicates = 2,
            insert = insert
          )
        else statusDoc("LOAD_COMPLETED"),
      errorPage
    )

  test("failOnError false: completed with errors returns counts and records") {
    val (store, report) = exported()
    val lines = new Captured
    val s = tolerated(report, _ => logPage(1, 3))
    val result = loader(s, store, failOnError = false, log = lines.add)
      .load(report.manifestKey)
    val n = report.files.size
    assertEquals(result.jobs.size, n)
    assertEquals(result.totalRecords, 10L * (n - 1) + 100L)
    assertEquals(result.insertErrors, 3L)
    assertEquals(result.parsingErrors, 0L)
    assertEquals(result.datatypeMismatchErrors, 0L)
    assertEquals(result.totalErrors, 3L)
    assertEquals(
      result.errorRecords,
      (1 to 3).toVector.map(i =>
        LoadErrorRecord("E", "edges-COAUTHORED_WITH.csv", i.toString, s"m$i")
      )
    )
    assertEquals(result.errorsTruncated, false)
    assertEquals(result.allErrorsNamed, true)
    assertEquals(result.jobs.last.status, "LOAD_COMPLETED")
    assertEquals(result.jobs.last.errorRecords.size, 3)
    assertEquals(result.jobs.head.errorRecords, Vector.empty)
    // Records neither duplicated nor in error.
    assertEquals(result.inserted, 10 * (n - 1) + 100 - 2 - 3)
    assertEquals(
      lines.starting("NEPTUNE_LOAD_ERRORS_TOTAL"),
      Vector(s"NEPTUNE_LOAD_ERRORS_TOTAL n=3 named=3 job=$n")
    )
    assertEquals(lines.starting("NEPTUNE_LOAD_ERROR ").size, 3)
    // Only the failing job fetched errors.
    assertEquals(errorCalls(s).size, 1)
  }

  test("failOnError false returns a LOAD_FAILED job that counted errors") {
    val (store, report) = exported()
    val s = tolerated(report, _ => logPage(1, 3), state = "LOAD_FAILED")
    val result = loader(s, store, failOnError = false).load(report.manifestKey)
    val n = report.files.size
    // The real status is kept, per job and on the aggregate.
    assertEquals(result.jobs.last.status, "LOAD_FAILED")
    assertEquals(result.jobs.last.errorRecords.size, 3)
    assertEquals(result.jobStatuses.size, n)
    assertEquals(
      result.jobStatuses.last,
      (result.jobs.last.source, "LOAD_FAILED")
    )
    assertEquals(
      result.jobStatuses.init.map(_._2).toSet,
      Set("LOAD_COMPLETED")
    )
    assertEquals(result.jobStatuses.map(_._1), result.jobs.map(_.source))
    assertEquals(result.totalErrors, 3L)
    assertEquals(result.allErrorsNamed, true)
    // The same job with failOnError true throws, as it always did.
    val strict = tolerated(report, _ => logPage(1, 3), state = "LOAD_FAILED")
    assertEquals(
      code(loader(strict, store).load(report.manifestKey)),
      ErrorCode.BulkLoadFailed
    )
  }

  test("every start request asks for mode NEW") {
    val (store, report) = exported()
    Vector(true, false).foreach { strict =>
      val s = neptune(done)
      loader(s, store, failOnError = strict).load(report.manifestKey)
      val requests = s.requests("startLoaderJob")
      assertEquals(requests.size, report.files.size)
      requests.foreach(r =>
        assertEquals(r.asInstanceOf[StartLoaderJobRequest].modeAsString, "NEW")
      )
    }
  }

  test("a server that ignores the page number does not cover the count") {
    val (store, report) = exported()
    val lines = new Captured
    // Every page is the same 20 records; 45 errors are counted.
    val s = tolerated(report, _ => logPage(1, 20), insert = 45)
    val result = loader(s, store, failOnError = false, log = lines.add)
      .load(report.manifestKey)
    assertEquals(result.errorRecords.size, 20)
    assertEquals(result.totalErrors, 45L)
    assertEquals(result.allErrorsNamed, false)
    assertEquals(errorCalls(s).size, 2)
    assertEquals(
      lines.starting("NEPTUNE_LOAD_ERRORS_TOTAL"),
      Vector(
        s"NEPTUNE_LOAD_ERRORS_TOTAL n=45 named=20 job=${report.files.size}"
      )
    )
    // Duplicates inside one page are named once too.
    val twice = tolerated(
      report,
      _ => errorsPayload(Vector(errorLog(1, "a"), errorLog(1, "a"))),
      insert = 2
    )
    val one = loader(twice, store, failOnError = false).load(report.manifestKey)
    assertEquals(one.errorRecords.size, 1)
    assertEquals(one.allErrorsNamed, false)
    // The same record number in another file or code is a different record.
    val different = tolerated(
      report,
      _ =>
        errorsPayload(
          Vector(
            errorLog(1, "a"),
            errorLog(1, "a", code = "F"),
            errorLog(1, "a", file = "s3://b/other.csv")
          )
        ),
      insert = 3
    )
    val three =
      loader(different, store, failOnError = false).load(report.manifestKey)
    assertEquals(three.errorRecords.size, 3)
    assertEquals(three.allErrorsNamed, true)
  }

  test("failOnError false still throws on a genuine failure") {
    val (store, report) = exported()
    // LOAD_FAILED with counted errors is returned (next test); with none it
    // throws, and so does every other status, counted errors or not.
    Vector(
      statusDoc("LOAD_FAILED"),
      statusDoc("LOAD_FAILED", total = 9),
      statusDoc("LOAD_CANCELLED_DUE_TO_ERRORS", insert = 1),
      statusDoc("LOAD_CANCELLED_BY_USER"),
      statusDoc("LOAD_COMMITTED_W_WRITE_CONFLICTS", insert = 1),
      statusDoc("LOAD_S3_READ_ERROR", insert = 1),
      statusDoc("LOAD_S3_ACCESS_DENIED_ERROR", insert = 1),
      statusDoc("LOAD_DATA_DEADLOCK", insert = 1),
      statusDoc("LOAD_DATA_FAILED_DUE_TO_FEED_MODIFIED_OR_DELETED", insert = 1),
      statusDoc("LOAD_FAILED_BECAUSE_DEPENDENCY_NOT_SATISFIED", insert = 1),
      statusDoc("LOAD_FAILED_INVALID_REQUEST", insert = 1),
      statusDoc("LOAD_UNEXPECTED_ERROR", insert = 1),
      statusDoc("SOMETHING_NEW", insert = 1)
    ).foreach { response =>
      val s = neptune(_ => Vector(response))
      assertEquals(
        code(loader(s, store, failOnError = false).load(report.manifestKey)),
        ErrorCode.BulkLoadFailed
      )
      assertEquals(s.requests("startLoaderJob").size, 1)
    }
    val stuck = neptune(_ => Vector(statusDoc("LOAD_IN_PROGRESS")))
    assertEquals(
      code(
        loader(stuck, store, maxPolls = 3, failOnError = false)
          .load(report.manifestKey)
      ),
      ErrorCode.BulkLoadFailed
    )
  }

  test("error pages are read until the counted errors are covered") {
    val (store, report) = exported()
    val lines = new Captured
    val s = tolerated(
      report,
      request =>
        request.page.intValue match
          case 1 => logPage(1, 20)
          case 2 => logPage(21, 20)
          case 3 => logPage(41, 5)
          case _ => logPage(0, 0)
      ,
      insert = 45
    )
    val result = loader(s, store, failOnError = false, log = lines.add)
      .load(report.manifestKey)
    assertEquals(result.errorRecords.size, 45)
    assertEquals(
      result.errorRecords.map(_.record),
      (1 to 45).toVector.map(_.toString)
    )
    assertEquals(errorCalls(s).map(_.page.intValue), Vector(1, 2, 3))
    assertEquals(result.errorsTruncated, false)
    assertEquals(result.allErrorsNamed, true)
    assertEquals(lines.starting("NEPTUNE_LOAD_ERROR ").size, 20)
    assertEquals(
      lines.starting("NEPTUNE_LOAD_ERRORS_TOTAL"),
      Vector(
        s"NEPTUNE_LOAD_ERRORS_TOTAL n=45 named=45 job=${report.files.size}"
      )
    )
  }

  test("the fetch stops at 500 records and says it was truncated") {
    val (store, report) = exported()
    val s = tolerated(
      report,
      request => logPage((request.page.intValue - 1) * 20 + 1, 20),
      insert = 600
    )
    val result = loader(s, store, failOnError = false).load(report.manifestKey)
    assertEquals(result.errorRecords.size, 500)
    assertEquals(result.totalErrors, 600L)
    assertEquals(result.errorsTruncated, true)
    assertEquals(result.allErrorsNamed, false)
    assertEquals(errorCalls(s).size, 25)
    // Exactly 500 counted is not a truncation.
    val exact = tolerated(
      report,
      request => logPage((request.page.intValue - 1) * 20 + 1, 20),
      insert = 500
    )
    val full =
      loader(exact, store, failOnError = false).load(report.manifestKey)
    assertEquals(full.errorRecords.size, 500)
    assertEquals(full.errorsTruncated, false)
    assertEquals(full.allErrorsNamed, true)
  }

  test("a page that fails to fetch leaves the list truncated and unnamed") {
    val (store, report) = exported()
    val lines = new Captured
    val s = tolerated(
      report,
      request =>
        if request.page.intValue == 1 then logPage(1, 20)
        else throw SdkErrors.error(500, "InternalServerException"),
      insert = 45
    )
    val result = loader(s, store, failOnError = false, log = lines.add)
      .load(report.manifestKey)
    assertEquals(result.errorRecords.size, 20)
    assertEquals(result.errorsTruncated, true)
    assertEquals(result.allErrorsNamed, false)
    assertEquals(lines.starting("NEPTUNE_LOAD_ERROR_FETCH_FAILED").size, 1)
    val total = tolerated(report, _ => throw serviceError(403, "denied"))
    val none =
      loader(total, store, failOnError = false).load(report.manifestKey)
    assertEquals(none.errorRecords, Vector.empty)
    assertEquals(none.totalErrors, 3L)
    assertEquals(none.errorsTruncated, true)
    assertEquals(none.allErrorsNamed, false)
  }

  test("fewer records than counted errors is not named, but not truncated") {
    val (store, report) = exported()
    val s = tolerated(report, _ => logPage(1, 2))
    val result = loader(s, store, failOnError = false).load(report.manifestKey)
    assertEquals(result.errorRecords.size, 2)
    assertEquals(result.errorsTruncated, false)
    assertEquals(result.allErrorsNamed, false)
  }

  test("an empty first page is read again; a persistent one is reported") {
    val (store, report) = exported()
    var calls = 0
    val sleeper = new RecordingSleeper
    val again = tolerated(
      report,
      _ =>
        calls += 1
        if calls == 1 then errorsPayload(Vector.empty) else logPage(1, 3)
    )
    val result = loader(again, store, sleeper, failOnError = false)
      .load(report.manifestKey)
    assertEquals(result.errorRecords.size, 3)
    assertEquals(errorCalls(again).size, 2)
    assertEquals(sleeper.delays.toVector, Vector(1500L))
    val lines = new Captured
    val empty = tolerated(report, _ => errorsPayload(Vector.empty))
    val bare = loader(empty, store, failOnError = false, log = lines.add)
      .load(report.manifestKey)
    assertEquals(bare.errorRecords, Vector.empty)
    assertEquals(bare.allErrorsNamed, false)
    assertEquals(
      lines.starting("NEPTUNE_LOAD_ERROR_NONE"),
      Vector(
        s"NEPTUNE_LOAD_ERROR_NONE job=${report.files.size} " +
          s"load=${lastJob(report)} counted=3 payload_keys=errors " +
          "errors_keys=errorLogs"
      )
    )
  }

  test("a result with no jobs has no errors and counts as named") {
    val result = BulkLoadResult(0, 0, 0)
    assertEquals(result.totalErrors, 0L)
    assertEquals(result.totalRecords, 0L)
    assertEquals(result.errorRecords, Vector.empty)
    assertEquals(result.errorsTruncated, false)
    assertEquals(result.allErrorsNamed, true)
  }
