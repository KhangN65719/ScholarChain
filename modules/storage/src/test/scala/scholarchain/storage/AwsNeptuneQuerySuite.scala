package scholarchain.storage

import scala.collection.mutable
import software.amazon.awssdk.awscore.exception.{
  AwsErrorDetails,
  AwsServiceException
}
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException
}
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  ExecuteOpenCypherQueryResponse
}

/** The Neptune query adapter over a stubbed SDK client: what it sends, how it
  * reads the answer, what it refuses and how it names a failure.
  */
class AwsNeptuneQuerySuite extends munit.FunSuite:
  import Fixtures.*

  private val window = "2000-2009"

  private def answer(rows: ujson.Value*): ExecuteOpenCypherQueryResponse =
    ExecuteOpenCypherQueryResponse
      .builder()
      .results(VectorDocuments.fromJson(ujson.Arr.from(rows)))
      .build()

  private def stubbed(
      handler: ExecuteOpenCypherQueryRequest => AnyRef
  ): SdkStub[NeptunedataClient] =
    new SdkStub(classOf[NeptunedataClient]).on("executeOpenCypherQuery") {
      request => handler(request.asInstanceOf[ExecuteOpenCypherQueryRequest])
    }

  private def coauthorRowJson(other: String, edge: String): ujson.Value =
    ujson.Obj(
      "coauthorId" -> other,
      "edgeId" -> edge,
      "jointPublicationCount" -> 2,
      "firstCollaborationYear" -> 2001,
      "lastCollaborationYear" -> 2005,
      "evidencePointer" -> s"edge-support:edgeId=$edge"
    )

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def failure(status: Int, errorCode: String, message: String) =
    AwsServiceException
      .builder()
      .statusCode(status)
      .message(message)
      .awsErrorDetails(
        AwsErrorDetails
          .builder()
          .errorCode(errorCode)
          .errorMessage(message)
          .build()
      )
      .build()

  private def params(request: ExecuteOpenCypherQueryRequest): ujson.Value =
    ujson.read(request.parameters())

  test("coauthors-of: the template is the text and the values are parameters") {
    val edge = edgeId(r1, r2)
    val s = stubbed(_ => answer(coauthorRowJson(r2, edge)))
    val neptune = new AwsNeptuneQuery(s.client)
    val query = CypherCatalog.coauthorsOf(r1, window, 25)
    neptune.run(query)
    val request = s
      .requests("executeOpenCypherQuery")
      .head
      .asInstanceOf[ExecuteOpenCypherQueryRequest]
    assertEquals(request.openCypherQuery(), query.text)
    assert(!request.openCypherQuery().contains(r1))
    assert(!request.openCypherQuery().contains(window))
    val sent = params(request)
    assertEquals(sent("researcherId").str, r1)
    assertEquals(sent("window").str, window)
    assertEquals(sent("limit").num, 25.0)
    assertEquals(sent.obj.keySet, Set("researcherId", "window", "limit"))
  }

  test("coauthors-of: rows come back as the port's coauthor links") {
    val edge = edgeId(r1, r2)
    val s = stubbed(_ => answer(coauthorRowJson(r2, edge)))
    val links =
      CoauthorContext.of(new AwsNeptuneQuery(s.client), r1, window, 25)
    assertEquals(
      links,
      Vector(
        CoauthorLink(r2, edge, 2L, 2001, 2005, s"edge-support:edgeId=$edge")
      )
    )
  }

  test("coauthors-of: the widest read the rag direct-edge lookup makes") {
    val s = stubbed(_ => answer())
    val neptune = new AwsNeptuneQuery(s.client)
    assertEquals(
      CoauthorContext
        .of(neptune, r1, window, CypherQuery.MaxLimit),
      Vector.empty
    )
    val request = s
      .requests("executeOpenCypherQuery")
      .head
      .asInstanceOf[ExecuteOpenCypherQueryRequest]
    assertEquals(params(request)("limit").num, 1000.0)
  }

  test("edge-evidence: the joined work IDs become the JSON array rag reads") {
    val edge = edgeId(r1, r2)
    val s = stubbed(_ =>
      answer(
        ujson.Obj(
          "edgeId" -> edge,
          "evidencePointer" -> s"edge-support:edgeId=$edge",
          "representativeWorkIds" -> s"$w1;$w2"
        )
      )
    )
    val rows = new AwsNeptuneQuery(s.client).run(
      CypherCatalog.edgeEvidence(edge, 1)
    )
    assertEquals(rows.size, 1)
    assertEquals(
      Rows.strList(rows.head, "representativeWorkIds"),
      Vector(w1, w2)
    )
    assertEquals(Rows.str(rows.head, "edgeId"), edge)
    val request = s
      .requests("executeOpenCypherQuery")
      .head
      .asInstanceOf[ExecuteOpenCypherQueryRequest]
    assertEquals(params(request)("edgeId").str, edge)
    assertEquals(params(request)("limit").num, 1.0)
  }

  test("edge-evidence: no list stays absent, an empty answer is no rows") {
    val edge = edgeId(r1, r2)
    val absent = stubbed(_ =>
      answer(
        ujson.Obj(
          "edgeId" -> edge,
          "evidencePointer" -> ujson.Null,
          "representativeWorkIds" -> ujson.Null
        )
      )
    )
    val rows =
      new AwsNeptuneQuery(absent.client)
        .run(CypherCatalog.edgeEvidence(edge, 1))
    assertEquals(Rows.strList(rows.head, "representativeWorkIds"), Vector.empty)
    val none = stubbed(_ => answer())
    assertEquals(
      new AwsNeptuneQuery(none.client).run(CypherCatalog.edgeEvidence(edge, 1)),
      Vector.empty
    )
  }

  test("every catalog template is accepted and only those are sent") {
    val s = stubbed(_ => answer())
    val neptune = new AwsNeptuneQuery(s.client)
    val queries = Vector(
      CypherCatalog.coauthorsOf(r1, window, 1),
      CypherCatalog.coauthorsOf(r1, window, 25),
      CypherCatalog.coauthorsOf(r1, window, CypherQuery.MaxLimit),
      CypherCatalog.edgeEvidence(edgeId(r1, r2), 1)
    )
    queries.foreach(neptune.run)
    assertEquals(s.requests("executeOpenCypherQuery").size, queries.size)
    s.requests("executeOpenCypherQuery")
      .map(_.asInstanceOf[ExecuteOpenCypherQueryRequest].openCypherQuery())
      .foreach { text =>
        assert(text.startsWith("MATCH"), text)
        assert(!text.exists(c => c == '\'' || c == '"'), text)
      }
  }

  test("a write clause or a literal never reaches the client") {
    val s = stubbed(_ => answer())
    val neptune = new AwsNeptuneQuery(s.client)
    val limit = Map("limit" -> ujson.Num(1))
    Vector("CREATE", "MERGE", "DELETE", "DETACH", "SET", "REMOVE", "CALL")
      .foreach { word =>
        assertEquals(
          code(
            neptune.run(
              CypherQuery(
                "w",
                s"MATCH (a) $word a RETURN a LIMIT $$limit",
                limit
              )
            )
          ),
          ErrorCode.QueryRejected,
          word
        )
      }
    assertEquals(
      code(
        neptune.run(
          CypherQuery(
            "l",
            "MATCH (a) WHERE id(a) = 'x' RETURN a LIMIT $limit",
            limit
          )
        )
      ),
      ErrorCode.QueryRejected
    )
    assertEquals(
      code(
        neptune.run(
          CypherQuery(
            "l",
            "MATCH (a) RETURN a LIMIT $limit; MATCH (b) RETURN b",
            limit
          )
        )
      ),
      ErrorCode.QueryRejected
    )
    assertEquals(s.requests("executeOpenCypherQuery"), Vector.empty)
  }

  test("a response above the query's limit is refused, never cut") {
    val edge = edgeId(r1, r2)
    val s =
      stubbed(_ => answer(coauthorRowJson(r2, edge), coauthorRowJson(r1, edge)))
    assertEquals(
      code(
        new AwsNeptuneQuery(s.client)
          .run(CypherCatalog.coauthorsOf(r1, window, 1))
      ),
      ErrorCode.BoundExceeded
    )
  }

  test("a response that is not a list of objects is refused") {
    val notList = stubbed(_ =>
      ExecuteOpenCypherQueryResponse
        .builder()
        .results(VectorDocuments.fromJson(ujson.Str("nope")))
        .build()
    )
    assertEquals(
      code(
        new AwsNeptuneQuery(notList.client)
          .run(CypherCatalog.edgeEvidence("e", 1))
      ),
      ErrorCode.MalformedRow
    )
    val notObject = stubbed(_ => answer(ujson.Str("row")))
    assertEquals(
      code(
        new AwsNeptuneQuery(notObject.client)
          .run(CypherCatalog.edgeEvidence("e", 1))
      ),
      ErrorCode.MalformedRow
    )
  }

  private def thrown(error: Throwable): Throwable =
    val s = stubbed(_ => throw error)
    intercept[Throwable](
      new AwsNeptuneQuery(s.client)
        .run(CypherCatalog.coauthorsOf(r1, window, 5))
    )

  test("errors: a time limit is a transient timeout") {
    val e = thrown(failure(500, "TimeLimitExceededException", "slow"))
    assert(e.isInstanceOf[TransientStorageException])
    assert(e.getMessage.startsWith("NEPTUNE_TIMEOUT"), e.getMessage)
    assert(
      thrown(failure(408, "RequestTimeout", "slow")).getMessage
        .startsWith("NEPTUNE_TIMEOUT")
    )
  }

  test("errors: throttling is transient and named") {
    val e = thrown(failure(429, "ThrottlingException", "slow down"))
    assert(e.isInstanceOf[TransientStorageException])
    assert(e.getMessage.startsWith("NEPTUNE_THROTTLED"), e.getMessage)
    assert(
      thrown(failure(500, "MemoryLimitExceededException", "m")).getMessage
        .startsWith("NEPTUNE_THROTTLED")
    )
  }

  test("errors: access denied is AwsAccessDenied") {
    Vector(
      failure(403, "AccessDeniedException", "no"),
      failure(401, "MissingAuthenticationTokenException", "no")
    ).foreach { error =>
      val e = thrown(error)
      assertEquals(
        e.asInstanceOf[StorageException].code,
        ErrorCode.AwsAccessDenied
      )
      assert(e.getMessage.contains("NEPTUNE_ACCESS_DENIED"), e.getMessage)
    }
  }

  test("errors: a malformed query is QueryRejected") {
    Vector(
      failure(400, "MalformedQueryException", "bad"),
      failure(400, "SomethingNew", "bad")
    ).foreach { error =>
      val e = thrown(error)
      assertEquals(
        e.asInstanceOf[StorageException].code,
        ErrorCode.QueryRejected
      )
      assert(e.getMessage.contains("NEPTUNE_BAD_QUERY"), e.getMessage)
    }
  }

  test(
    "errors: a server fault is transient, another client fault is rejected"
  ) {
    val unavailable = thrown(failure(503, "ServiceUnavailableException", "x"))
    assert(unavailable.isInstanceOf[TransientStorageException])
    assert(unavailable.getMessage.startsWith("NEPTUNE_UNAVAILABLE"))
    val other = thrown(failure(404, "ResourceNotFoundException", "x"))
    assertEquals(
      other.asInstanceOf[StorageException].code,
      ErrorCode.AwsRejected
    )
  }

  test("errors: a client-side deadline or an I/O failure is a timeout") {
    Vector[Throwable](
      ApiCallTimeoutException.create(1000L),
      ApiCallAttemptTimeoutException.create(500L),
      SdkClientException
        .builder()
        .message("connect")
        .cause(new java.io.IOException("refused"))
        .build()
    ).foreach { error =>
      val e = thrown(error)
      assert(e.isInstanceOf[TransientStorageException], e.toString)
      assert(e.getMessage.startsWith("NEPTUNE_TIMEOUT"), e.getMessage)
    }
    val other =
      thrown(SdkClientException.builder().message("no region").build())
    assertEquals(
      other.asInstanceOf[StorageException].code,
      ErrorCode.AwsRejected
    )
  }

  test("errors and log lines never carry a parameter value or the AWS text") {
    val sentinel = "orcid-0000-0001-SENTINEL"
    val lines = mutable.ArrayBuffer.empty[String]
    val query = CypherCatalog.coauthorsOf(sentinel, window, 5)
    Vector(
      failure(
        400,
        "MalformedQueryException",
        s"bad value $sentinel in the query"
      ),
      failure(403, "AccessDeniedException", s"denied for $sentinel"),
      failure(429, "ThrottlingException", s"throttled $sentinel"),
      failure(500, "TimeLimitExceededException", s"slow $sentinel")
    ).foreach { error =>
      val s = stubbed(_ => throw error)
      val e = intercept[Throwable](
        new AwsNeptuneQuery(s.client, lines += _).run(query)
      )
      assert(!e.getMessage.contains(sentinel), e.getMessage)
      assert(!e.getMessage.contains("bad value"), e.getMessage)
      assert(e.getMessage.contains(query.name), e.getMessage)
    }
    val ok = stubbed(_ => answer())
    new AwsNeptuneQuery(ok.client, lines += _).run(query)
    assertEquals(lines.size, 5)
    lines.foreach(line => assert(!line.contains(sentinel), line))
    assert(lines.last.startsWith("NEPTUNE_QUERY name=coauthors-of rows=0 ms="))
    assert(
      lines.head.startsWith("NEPTUNE_QUERY_FAILED name=coauthors-of kind=")
    )
  }

  test("close releases what the adapter owns, once per call") {
    var closed = 0
    val s = stubbed(_ => answer())
    val neptune = new AwsNeptuneQuery(s.client, closer = () => closed += 1)
    neptune.close()
    assertEquals(closed, 1)
  }

  test("endpoint: https on the cluster host and port, a bad host is refused") {
    assertEquals(
      AwsNeptuneQuery
        .endpoint("graph.cluster-x.us-east-2.neptune.amazonaws.com")
        .toString,
      "https://graph.cluster-x.us-east-2.neptune.amazonaws.com:8182"
    )
    assertEquals(
      AwsNeptuneQuery.endpoint("graph", 9000).toString,
      "https://graph:9000"
    )
    assertEquals(code(AwsNeptuneQuery.endpoint("a b")), ErrorCode.InvalidQuery)
    assertEquals(code(AwsNeptuneQuery.endpoint("a/b")), ErrorCode.InvalidQuery)
    assertEquals(
      code(AwsNeptuneQuery.endpoint("graph", 0)),
      ErrorCode.InvalidQuery
    )
  }

  test("timeouts must be positive and the call at least the attempt") {
    import java.time.Duration.ofMillis
    assertEquals(
      code(
        NeptuneQueryTimeouts(ofMillis(0), ofMillis(1), ofMillis(1), ofMillis(1))
      ),
      ErrorCode.InvalidQuery
    )
    assertEquals(
      code(
        NeptuneQueryTimeouts(ofMillis(1), ofMillis(1), ofMillis(5), ofMillis(4))
      ),
      ErrorCode.InvalidQuery
    )
    assert(
      NeptuneQueryTimeouts.Default.call.compareTo(
        java.time.Duration.ofSeconds(10)
      ) <= 0
    )
  }
