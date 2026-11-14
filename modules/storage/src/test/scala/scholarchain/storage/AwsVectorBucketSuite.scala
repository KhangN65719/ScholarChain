package scholarchain.storage

import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  GetIndexRequest,
  GetIndexResponse,
  GetOutputVector,
  GetVectorsRequest,
  GetVectorsResponse,
  Index,
  ListOutputVector,
  ListVectorsRequest,
  ListVectorsResponse,
  MetadataConfiguration,
  PutInputVector,
  PutVectorsRequest,
  PutVectorsResponse,
  QueryOutputVector,
  QueryVectorsRequest,
  QueryVectorsResponse,
  VectorData
}

class AwsVectorBucketSuite extends munit.FunSuite:
  private val name = VectorIndexes.ResearcherProfile
  private val spec =
    VectorIndexSpec(name, 4, "cosine", Some("researcher-profile"))

  private def stub = new SdkStub(classOf[S3VectorsClient])

  private def bucket(s: SdkStub[S3VectorsClient]) =
    new AwsVectorBucket(s.client, "vec-bucket")

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def indexResponse(
      dimension: Int = 4,
      dataType: String = "float32",
      metric: String = "cosine",
      keys: Vector[String] = Vector("textRef", "textHash"),
      mode: String = "ENHANCED"
  ): GetIndexResponse =
    GetIndexResponse
      .builder()
      .index(
        Index
          .builder()
          .indexName(name)
          .dimension(dimension)
          .dataType(dataType)
          .distanceMetric(metric)
          .indexMode(mode)
          .metadataConfiguration(
            MetadataConfiguration
              .builder()
              .nonFilterableMetadataKeys(keys.asJava)
              .build()
          )
          .build()
      )
      .build()

  private def withIndex(response: GetIndexResponse) =
    stub.on("getIndex")(_ => response)

  private def record(key: String, size: Int = 4): VectorRecord =
    VectorRecord(
      key,
      Vector.fill(size)(0.5f),
      Map(
        "summaryType" -> MetaValue.Str("researcher-profile"),
        "subjectId" -> MetaValue.Str("r1"),
        "textRef" -> MetaValue.Str("ref"),
        "runId" -> MetaValue.Str("run"),
        "year" -> MetaValue.Num(2005),
        "source" -> MetaValue.Strs(Vector("dblp", "orcid")),
        "flag" -> MetaValue.Flag(true)
      )
    )

  // ensureIndex ---------------------------------------------------------

  test("ensureIndex passes when GetIndex matches the spec") {
    val s = withIndex(indexResponse())
    bucket(s).ensureIndex(spec)
    val request = s.requests("getIndex").head.asInstanceOf[GetIndexRequest]
    assertEquals(request.vectorBucketName, "vec-bucket")
    assertEquals(request.indexName, name)
  }

  test("ensureIndex reports the index mode GetIndex returns") {
    val s = withIndex(indexResponse(mode = "CLASSIC"))
    assertEquals(bucket(s).describe(name).indexMode, Some("CLASSIC"))
    val e = withIndex(indexResponse(mode = "ENHANCED"))
    assertEquals(bucket(e).describe(name).indexMode, Some("ENHANCED"))
  }

  test("ensureIndex refuses a different dimension, type, metric or keys") {
    val cases = Vector(
      indexResponse(dimension = 8) -> "dimension 8",
      indexResponse(dataType = "float16") -> "data type float16",
      indexResponse(metric = "euclidean") -> "distance metric euclidean",
      indexResponse(keys =
        Vector("textRef")
      ) -> "non-filterable keys [textRef]",
      indexResponse(keys = Vector("textRef", "textHash", "year")) ->
        "non-filterable keys [textHash,textRef,year]"
    )
    cases.foreach { case (response, expected) =>
      val error =
        intercept[StorageException](
          bucket(withIndex(response)).ensureIndex(spec)
        )
      assertEquals(error.code, ErrorCode.IndexSpecMismatch)
      assert(error.getMessage.contains(expected), error.getMessage)
    }
  }

  test("ensureIndex on a missing index fails and never creates one") {
    val s =
      stub.on("getIndex")(_ => throw SdkErrors.error(404, "NotFoundException"))
    assertEquals(code(bucket(s).ensureIndex(spec)), ErrorCode.IndexMissing)
    assertEquals(s.names, Vector("getIndex"))
  }

  test("no adapter call is ever CreateIndex, DeleteIndex or a bucket call") {
    val s = stub
      .on("getIndex")(_ => indexResponse())
      .on("putVectors")(_ => PutVectorsResponse.builder().build())
      .on("getVectors")(_ => GetVectorsResponse.builder().build())
      .on("listVectors")(_ => ListVectorsResponse.builder().build())
      .on("queryVectors")(_ => QueryVectorsResponse.builder().build())
    val b = bucket(s)
    b.ensureIndex(spec)
    b.putVectors(name, Vector(record("k")))
    b.getVectors(name, Vector("k"))
    b.count(name)
    b.query(VectorQuery(name, Vector(1f, 0f, 0f, 0f), 1))
    val forbidden = Set(
      "createIndex",
      "deleteIndex",
      "createVectorBucket",
      "deleteVectorBucket",
      "deleteVectors"
    )
    assertEquals(s.names.filter(forbidden.contains), Vector.empty)
  }

  // putVectors ----------------------------------------------------------

  test("putVectors sends batches of at most 500") {
    val s = stub
      .on("getIndex")(_ => indexResponse())
      .on("putVectors")(_ => PutVectorsResponse.builder().build())
    val b = bucket(s)
    b.ensureIndex(spec)
    b.putVectors(name, (1 to 1200).toVector.map(i => record(s"k$i")))
    val requests =
      s.requests("putVectors").map(_.asInstanceOf[PutVectorsRequest])
    assertEquals(requests.map(_.vectors.size), Vector(500, 500, 200))
    assert(requests.forall(_.vectorBucketName == "vec-bucket"))
    assert(requests.forall(_.indexName == name))
  }

  test("putVectors carries float32 data and typed metadata") {
    val s = stub.on("putVectors")(_ => PutVectorsResponse.builder().build())
    bucket(s).putVectors(name, Vector(record("k1")))
    val sent: PutInputVector = s
      .requests("putVectors")
      .head
      .asInstanceOf[PutVectorsRequest]
      .vectors
      .get(0)
    assertEquals(sent.key, "k1")
    assertEquals(
      sent.data.float32.asScala.map(_.floatValue).toVector,
      Vector.fill(4)(0.5f)
    )
    val md = VectorDocuments.metadataOf(sent.metadata)
    assertEquals(md, record("k1").metadata)
  }

  test("putVectors refuses a wrong dimension once the index is verified") {
    val s = stub.on("getIndex")(_ => indexResponse())
    val b = bucket(s)
    b.ensureIndex(spec)
    assertEquals(
      code(b.putVectors(name, Vector(record("k", size = 3)))),
      ErrorCode.EmbeddingInvalid
    )
    assertEquals(s.requests("putVectors"), Vector.empty)
  }

  test("throttling is transient, a validation error is not retried") {
    val throttled = stub.on("putVectors")(_ =>
      throw SdkErrors.error(429, "TooManyRequestsException")
    )
    intercept[TransientStorageException](
      bucket(throttled).putVectors(name, Vector(record("k")))
    )
    val busy = stub.on("putVectors")(_ =>
      throw SdkErrors.error(503, "ServiceUnavailableException")
    )
    intercept[TransientStorageException](
      bucket(busy).putVectors(name, Vector(record("k")))
    )
    val invalid = stub.on("putVectors")(_ =>
      throw SdkErrors.error(400, "ValidationException")
    )
    assertEquals(
      code(bucket(invalid).putVectors(name, Vector(record("k")))),
      ErrorCode.AwsRejected
    )
    assertEquals(invalid.requests("putVectors").size, 1)
  }

  test("VectorWriter retries a throttled batch through the adapter") {
    var calls = 0
    val s = stub
      .on("getIndex")(_ => indexResponse())
      .on("getVectors")(_ => GetVectorsResponse.builder().build())
      .on("putVectors") { _ =>
        calls += 1
        if calls < 3 then throw SdkErrors.error(429, "TooManyRequestsException")
        else PutVectorsResponse.builder().build()
      }
    val writer = new VectorWriter(bucket(s), RetryPolicy(5, 1, 1), NoSleep)
    val plain = record("summary:v1:a")
    val report = writer.write(
      spec,
      Vector(plain.copy(metadata = plain.metadata - "flag"))
    )
    assertEquals(report.written, 1)
    assertEquals(report.retries, 2)
    assertEquals(report.rejected, Vector.empty)
  }

  // getVectors ----------------------------------------------------------

  test("getVectors asks for data and metadata in batches of 100") {
    val s = stub.on("getVectors") { request =>
      val keys = request.asInstanceOf[GetVectorsRequest].keys.asScala.toVector
      val found = keys.filterNot(_ == "gone").map { k =>
        GetOutputVector
          .builder()
          .key(k)
          .data(
            VectorData.fromFloat32(
              Vector(1f, 2f, 3f, 4f).map(java.lang.Float.valueOf(_)).asJava
            )
          )
          .metadata(VectorDocuments.metadata(record(k).metadata))
          .build()
      }
      GetVectorsResponse.builder().vectors(found.asJava).build()
    }
    val keys = (1 to 249).toVector.map(i => s"k$i") :+ "gone"
    val got = bucket(s).getVectors(name, keys)
    assertEquals(got.size, 249)
    val requests =
      s.requests("getVectors").map(_.asInstanceOf[GetVectorsRequest])
    assertEquals(requests.map(_.keys.size), Vector(100, 100, 50))
    assert(requests.forall(r => r.returnData && r.returnMetadata))
    // Non-filterable keys come back with returnMetadata.
    assertEquals(got.head.metadata("textRef"), MetaValue.Str("ref"))
    assertEquals(got.head.embedding, Vector(1f, 2f, 3f, 4f))
  }

  // count ---------------------------------------------------------------

  test("count pages ListVectors and does not read GetIndex") {
    def vectors(n: Int) =
      (1 to n).map(i => ListOutputVector.builder().key(s"k$i").build()).asJava
    val s = stub.on("listVectors") { request =>
      Option(request.asInstanceOf[ListVectorsRequest].nextToken) match
        case None =>
          ListVectorsResponse
            .builder()
            .vectors(vectors(1000))
            .nextToken("t1")
            .build()
        case Some("t1") =>
          ListVectorsResponse
            .builder()
            .vectors(vectors(1000))
            .nextToken("t2")
            .build()
        case Some("t2") =>
          ListVectorsResponse.builder().vectors(vectors(37)).build()
        case other => fail(s"unexpected token $other")
    }
    assertEquals(bucket(s).count(name), 2037)
    assertEquals(s.names, Vector("listVectors", "listVectors", "listVectors"))
    val first = s.requests("listVectors").head.asInstanceOf[ListVectorsRequest]
    assertEquals(first.maxResults.intValue, 1000)
    assertEquals(first.indexName, name)
  }

  test("count of a missing index is IndexMissing, a repeated token fails") {
    val s = stub.on("listVectors")(_ =>
      throw SdkErrors.error(404, "NotFoundException")
    )
    assertEquals(code(bucket(s).count(name)), ErrorCode.IndexMissing)
    val loop = stub.on("listVectors")(_ =>
      ListVectorsResponse.builder().nextToken("same").build()
    )
    assertEquals(code(bucket(loop).count(name)), ErrorCode.AwsRejected)
  }

  // query ---------------------------------------------------------------

  private def hit(key: String, distance: Float): QueryOutputVector =
    QueryOutputVector
      .builder()
      .key(key)
      .distance(distance)
      .metadata(VectorDocuments.metadata(record(key).metadata))
      .build()

  test("query pages with nextToken, sorts and keeps topK") {
    val s = stub.on("queryVectors") { request =>
      Option(request.asInstanceOf[QueryVectorsRequest].nextToken) match
        case None =>
          QueryVectorsResponse
            .builder()
            .vectors(hit("b", 0.2f), hit("a", 0.2f))
            .nextToken("t1")
            .build()
        case Some("t1") =>
          QueryVectorsResponse
            .builder()
            .vectors(hit("c", 0.1f), hit("d", 0.9f))
            .build()
        case other => fail(s"unexpected token $other")
    }
    val hits = bucket(s).query(VectorQuery(name, Vector(1f, 0f, 0f, 0f), 3))
    assertEquals(hits.map(_.key), Vector("c", "a", "b"))
    val first =
      s.requests("queryVectors").head.asInstanceOf[QueryVectorsRequest]
    assertEquals(first.topK.intValue, 3)
    assert(first.returnMetadata && first.returnDistance)
    assertEquals(first.filter, null)
    assertEquals(hits.head.metadata("subjectId"), MetaValue.Str("r1"))
  }

  test("query stops paging once topK hits are in") {
    val s = stub.on("queryVectors")(_ =>
      QueryVectorsResponse
        .builder()
        .vectors(hit("a", 0.1f), hit("b", 0.2f))
        .nextToken("more")
        .build()
    )
    val hits = bucket(s).query(VectorQuery(name, Vector(1f, 0f, 0f, 0f), 2))
    assertEquals(hits.size, 2)
    assertEquals(s.requests("queryVectors").size, 1)
  }

  test("query sends the translated filter and checks the dimension") {
    val s = stub
      .on("getIndex")(_ => indexResponse())
      .on("queryVectors")(_ => QueryVectorsResponse.builder().build())
    val b = bucket(s)
    b.ensureIndex(spec)
    val filter = VectorFilter(documentType = Some("work"))
    b.query(VectorQuery(name, Vector(1f, 0f, 0f, 0f), 5, filter))
    val sent = s.requests("queryVectors").head.asInstanceOf[QueryVectorsRequest]
    assertEquals(
      sent.filter,
      VectorDocuments.fromJson(
        ujson.Obj("documentType" -> ujson.Obj("$eq" -> "work"))
      )
    )
    assertEquals(
      code(b.query(VectorQuery(name, Vector(1f, 0f), 5))),
      ErrorCode.EmbeddingInvalid
    )
  }

  test("query maps throttling to transient and validation to a refusal") {
    val q = VectorQuery(name, Vector(1f, 0f, 0f, 0f), 1)
    val throttled = stub.on("queryVectors")(_ =>
      throw SdkErrors.error(429, "TooManyRequestsException")
    )
    intercept[TransientStorageException](bucket(throttled).query(q))
    val invalid = stub.on("queryVectors")(_ =>
      throw SdkErrors.error(400, "ValidationException")
    )
    assertEquals(code(bucket(invalid).query(q)), ErrorCode.AwsRejected)
    assertEquals(invalid.requests("queryVectors").size, 1)
  }

  // filter translation --------------------------------------------------

  private def eq(field: String, value: String) =
    ujson.Obj(field -> ujson.Obj("$eq" -> value))

  test("filter translation table") {
    def json(f: VectorFilter) = S3VectorsFilters.toJson(f)
    assertEquals(json(VectorFilter.empty), None)
    assertEquals(
      json(VectorFilter(documentType = Some("work"))),
      Some(eq("documentType", "work"))
    )
    assertEquals(
      json(VectorFilter(community = Some("c1"))),
      Some(eq("communityId", "c1"))
    )
    assertEquals(
      json(VectorFilter(source = Some("dblp"))),
      Some(eq("source", "dblp"))
    )
    assertEquals(json(VectorFilter(topic = Some("t"))), Some(eq("topic", "t")))
    assertEquals(
      json(VectorFilter(institution = Some("i"))),
      Some(eq("institution", "i"))
    )
    // researcher OR evidenceNodeIds list-contains: $or of two $eq.
    assertEquals(
      json(VectorFilter(researcher = Some("r1"))),
      Some(
        ujson.Obj(
          "$or" -> ujson.Arr(eq("subjectId", "r1"), eq("evidenceNodeIds", "r1"))
        )
      )
    )
  }

  test("year window: windowed vectors by window, unwindowed by year") {
    val window = (from: Int, to: Int) =>
      ujson.Obj(
        "$or" -> ujson.Arr(
          ujson.Obj(
            "$and" -> ujson.Arr(
              ujson.Obj("windowStart" -> ujson.Obj("$gte" -> from)),
              ujson.Obj("windowEnd" -> ujson.Obj("$lte" -> to))
            )
          ),
          ujson.Obj(
            "$and" -> ujson.Arr(
              ujson.Obj("windowStart" -> ujson.Obj("$exists" -> false)),
              ujson.Obj("year" -> ujson.Obj("$gte" -> from, "$lte" -> to))
            )
          )
        )
      )
    assertEquals(
      S3VectorsFilters.toJson(
        VectorFilter(yearFrom = Some(2000), yearTo = Some(2009))
      ),
      Some(window(2000, 2009))
    )
    assertEquals(
      S3VectorsFilters.toJson(VectorFilter(yearFrom = Some(2000))),
      Some(window(2000, Limits.S3Vectors.MaxYear))
    )
    assertEquals(
      S3VectorsFilters.toJson(VectorFilter(yearTo = Some(2009))),
      Some(window(Limits.S3Vectors.MinYear, 2009))
    )
  }

  test("several fields combine with $and; $startsWith is never used") {
    val all = VectorFilter(
      researcher = Some("r1"),
      yearFrom = Some(2000),
      yearTo = Some(2009),
      source = Some("dblp"),
      topic = Some("t"),
      institution = Some("i"),
      community = Some("c"),
      documentType = Some("work")
    )
    val json = S3VectorsFilters.toJson(all).get
    assertEquals(json.obj.keySet, Set("$and"))
    assertEquals(json("$and").arr.size, 7)
    assert(!ujson.write(json).contains("$startsWith"))
    assert(!ujson.write(json).contains("$in"))
  }

  test("a filter stays within 100 constraints; more is refused") {
    val all = VectorFilter(
      researcher = Some("r1"),
      yearFrom = Some(2000),
      yearTo = Some(2009),
      source = Some("dblp"),
      topic = Some("t"),
      institution = Some("i"),
      community = Some("c"),
      documentType = Some("work")
    )
    val count =
      S3VectorsFilters.constraintCount(S3VectorsFilters.toJson(all).get)
    assertEquals(count, 12)
    assert(count <= Limits.S3Vectors.MaxFilterConstraints)
    val many =
      ujson.Obj("$and" -> ujson.Arr.from((1 to 101).map(i => eq(s"k$i", "v"))))
    assertEquals(S3VectorsFilters.constraintCount(many), 101)
    assertEquals(
      code(S3VectorsFilters.requireWithinLimit(many)),
      ErrorCode.BoundExceeded
    )
    S3VectorsFilters.requireWithinLimit(
      ujson.Obj("$and" -> ujson.Arr.from((1 to 100).map(i => eq(s"k$i", "v"))))
    )
  }

  test(
    "filter documents keep whole numbers integral and metadata round trips"
  ) {
    val doc =
      VectorDocuments.fromJson(ujson.Obj("year" -> ujson.Obj("$gte" -> 2000)))
    val number = doc.asMap.get("year").asMap.get("$gte")
    assert(number.isNumber)
    assertEquals(number.asNumber.stringValue, "2000")
    val metadata = record("k").metadata
    assertEquals(
      VectorDocuments.metadataOf(VectorDocuments.metadata(metadata)),
      metadata
    )
    assertEquals(VectorDocuments.metadataOf(null: Document), Map.empty)
  }

  test("an empty bucket name is refused") {
    assertEquals(
      code(new AwsVectorBucket(stub.client, " ")),
      ErrorCode.InvalidKey
    )
  }
