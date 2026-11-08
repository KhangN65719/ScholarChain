package scholarchain.storage

import MetaValue.{Num, Str, Strs}

/** QueryVectors port and its metadata filters, against the local fake. */
class VectorQuerySuite extends munit.FunSuite:
  import Fixtures.*

  private val dim = 3
  private val index = "idx-q"
  private val query = Vector(1f, 0f, 0f)

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def meta(
      subject: String,
      extra: (String, MetaValue)*
  ): Map[String, MetaValue] =
    Map(
      "summaryType" -> Str("researcher-profile"),
      "subjectId" -> Str(subject),
      "textRef" -> Str("ref#x"),
      "runId" -> Str(runId)
    ) ++ extra

  private def windowed(start: Int, end: Int): Vector[(String, MetaValue)] =
    Vector(
      "window" -> Str(s"$start-$end"),
      "windowStart" -> Num(start.toDouble),
      "windowEnd" -> Num(end.toDouble)
    )

  private def rec(
      key: String,
      embedding: Vector[Float],
      subject: String,
      extra: (String, MetaValue)*
  ): VectorRecord =
    VectorRecord(key, embedding, meta(subject, extra*))

  private def bucketOf(
      records: Vector[VectorRecord],
      metric: String = "cosine"
  ): InMemoryVectorBucket =
    val bucket = new InMemoryVectorBucket
    bucket.ensureIndex(VectorIndexSpec(index, dim, metric, None))
    bucket.putVectors(index, records)
    bucket

  private def ask(
      bucket: VectorBucketPort,
      filter: VectorFilter,
      topK: Int = 100
  ): Set[String] =
    bucket.query(VectorQuery(index, query, topK, filter)).map(_.key).toSet

  // Four vectors, each with a different facet profile.
  private def facetBucket: InMemoryVectorBucket =
    bucketOf(
      Vector(
        rec(
          "a",
          Vector(1f, 0f, 0f),
          r1,
          (windowed(2000, 2004) ++ Vector(
            "source" -> Strs(Vector("dblp")),
            "communityId" -> Str("c1"),
            "documentType" -> Str("researcher-profile")
          ))*
        ),
        rec(
          "b",
          Vector(0f, 1f, 0f),
          r2,
          (windowed(2005, 2009) ++ Vector(
            "source" -> Strs(Vector("openalex")),
            "communityId" -> Str("c2"),
            "documentType" -> Str("community"),
            "evidenceNodeIds" -> Strs(Vector(r1, m1))
          ))*
        ),
        rec(
          "c",
          Vector(1f, 1f, 0f),
          w1,
          "year" -> Num(2003),
          "source" -> Strs(Vector("dblp", "crossref")),
          "documentType" -> Str("work"),
          "topic" -> Strs(Vector("topic:ml")),
          "institution" -> Strs(Vector("inst:mit"))
        ),
        rec("d", Vector(1f, 0f, 1f), r2, windowed(1, 9999)*)
      )
    )

  test("each filter key selects exactly the vectors that carry it") {
    val b = facetBucket
    // researcher: the subject, or a listed evidence node
    assertEquals(ask(b, VectorFilter(researcher = Some(r1))), Set("a", "b"))
    assertEquals(ask(b, VectorFilter(researcher = Some(r2))), Set("b", "d"))
    assertEquals(ask(b, VectorFilter(researcher = Some(w1))), Set("c"))
    assertEquals(ask(b, VectorFilter(researcher = Some(m1))), Set("b"))
    // year window: contained windows, or the year of an unwindowed vector
    assertEquals(
      ask(b, VectorFilter(yearFrom = Some(2000), yearTo = Some(2004))),
      Set("a", "c")
    )
    assertEquals(
      ask(b, VectorFilter(yearFrom = Some(2000), yearTo = Some(2009))),
      Set("a", "b", "c")
    )
    // source
    assertEquals(ask(b, VectorFilter(source = Some("dblp"))), Set("a", "c"))
    assertEquals(ask(b, VectorFilter(source = Some("crossref"))), Set("c"))
    assertEquals(ask(b, VectorFilter(source = Some("openalex"))), Set("b"))
    // topic and institution
    assertEquals(ask(b, VectorFilter(topic = Some("topic:ml"))), Set("c"))
    assertEquals(
      ask(b, VectorFilter(institution = Some("inst:mit"))),
      Set("c")
    )
    // community
    assertEquals(ask(b, VectorFilter(community = Some("c1"))), Set("a"))
    assertEquals(ask(b, VectorFilter(community = Some("c2"))), Set("b"))
    // document type
    assertEquals(
      ask(b, VectorFilter(documentType = Some("community"))),
      Set("b")
    )
    assertEquals(
      ask(b, VectorFilter(documentType = Some("work"))),
      Set("c")
    )
  }

  test("combined filters are an AND of their constraints") {
    val b = facetBucket
    assertEquals(
      ask(b, VectorFilter(researcher = Some(r2), source = Some("openalex"))),
      Set("b")
    )
    assertEquals(
      ask(
        b,
        VectorFilter(
          yearFrom = Some(2000),
          yearTo = Some(2009),
          source = Some("dblp")
        )
      ),
      Set("a", "c")
    )
    assertEquals(
      ask(
        b,
        VectorFilter(
          community = Some("c1"),
          yearFrom = Some(2005),
          yearTo = Some(2009)
        )
      ),
      Set.empty[String]
    )
    assertEquals(
      ask(
        b,
        VectorFilter(
          documentType = Some("researcher-profile"),
          researcher = Some(r1),
          community = Some("c1"),
          source = Some("dblp"),
          yearFrom = Some(2000),
          yearTo = Some(2004)
        )
      ),
      Set("a")
    )
    assertEquals(
      VectorFilter(
        researcher = Some(r1),
        yearTo = Some(2000),
        source = Some("x")
      ).constraintCount,
      3
    )
  }

  test("year window boundaries are inclusive and a window must be contained") {
    val b = bucketOf(
      Vector(
        rec("w", Vector(1f, 0f, 0f), r1, windowed(2000, 2004)*),
        rec("w-wide", Vector(1f, 0f, 0f), r1, windowed(2000, 2005)*),
        rec("w-early", Vector(1f, 0f, 0f), r1, windowed(1999, 2004)*),
        rec("y1999", Vector(1f, 0f, 0f), w1, "year" -> Num(1999)),
        rec("y2000", Vector(1f, 0f, 0f), w1, "year" -> Num(2000)),
        rec("y2004", Vector(1f, 0f, 0f), w1, "year" -> Num(2004)),
        rec("y2005", Vector(1f, 0f, 0f), w1, "year" -> Num(2005)),
        rec("none", Vector(1f, 0f, 0f), w1)
      )
    )
    def years(from: Option[Int], to: Option[Int]) =
      ask(b, VectorFilter(yearFrom = from, yearTo = to))
    assertEquals(
      years(Some(2000), Some(2004)),
      Set("w", "y2000", "y2004")
    )
    assertEquals(
      years(Some(2000), None),
      Set("w", "w-wide", "y2000", "y2004", "y2005")
    )
    assertEquals(
      years(None, Some(2004)),
      Set("w", "w-early", "y1999", "y2000", "y2004")
    )
    assertEquals(years(Some(2004), Some(2004)), Set("y2004"))
    assertEquals(years(Some(2006), Some(2010)), Set.empty[String])
    // a vector with neither window nor year never matches a year filter, and
    // an empty filter matches everything
    assert(!years(Some(1), Some(9999)).contains("none"))
    assertEquals(ask(b, VectorFilter.empty).size, 8)
  }

  test("the all-years window matches only a range that covers it") {
    val b = bucketOf(Vector(rec("all", query, r1, windowed(1, 9999)*)))
    assertEquals(
      ask(b, VectorFilter(yearFrom = Some(2000), yearTo = Some(2009))),
      Set.empty[String]
    )
    assertEquals(ask(b, VectorFilter(yearFrom = Some(1))), Set("all"))
    assertEquals(ask(b, VectorFilter(yearTo = Some(9999))), Set("all"))
  }

  test("topK is bounded by the QueryVectors limit and returns the nearest") {
    assertEquals(Limits.S3Vectors.MaxTopK, 10000)
    val b = bucketOf(
      (1 to 5).toVector.map(i => rec(s"k$i", Vector(1f, i.toFloat, 0f), r1))
    )
    val two = b.query(VectorQuery(index, query, 2))
    assertEquals(two.map(_.key), Vector("k1", "k2"))
    assertEquals(b.query(VectorQuery(index, query, 10000)).size, 5)
    assertEquals(
      code(VectorQuery(index, query, 10001)),
      ErrorCode.BoundExceeded
    )
    assertEquals(code(VectorQuery(index, query, 0)), ErrorCode.InvalidQuery)
    assertEquals(code(VectorQuery(index, query, -3)), ErrorCode.InvalidQuery)
    assertEquals(Limits.S3Vectors.QueryPageSize, 100)
    assertEquals(Limits.S3Vectors.MaxFilterConstraints, 100)
  }

  test("ties are ordered by ascending key, whatever the insertion order") {
    val keys = Vector("k3", "k1", "k2", "k5", "k4")
    def run(order: Vector[String]): Vector[String] =
      bucketOf(order.map(k => rec(k, Vector(2f, 0f, 0f), r1)))
        .query(VectorQuery(index, query, 100))
        .map(_.key)
    val expected = Vector("k1", "k2", "k3", "k4", "k5")
    assertEquals(run(keys), expected)
    assertEquals(run(keys.reverse), expected)
    // and a bounded query keeps the smallest keys
    val bounded = bucketOf(keys.map(k => rec(k, Vector(1f, 0f, 0f), r1)))
      .query(VectorQuery(index, query, 2))
    assertEquals(bounded.map(_.key), Vector("k1", "k2"))
    assert(bounded.forall(_.distance == 0.0))
  }

  test("cosine and euclidean rank by their own distance") {
    val records = Vector(
      rec("same", Vector(5f, 0f, 0f), r1),
      rec("near", Vector(1f, 1f, 0f), r1),
      rec("far", Vector(0f, 1f, 0f), r1)
    )
    val cosine = bucketOf(records).query(VectorQuery(index, query, 10))
    assertEquals(cosine.map(_.key), Vector("same", "near", "far"))
    assertEqualsDouble(cosine(0).distance, 0.0, 1e-9)
    assertEqualsDouble(cosine(1).distance, 1.0 - 1.0 / math.sqrt(2.0), 1e-9)
    assertEqualsDouble(cosine(2).distance, 1.0, 1e-9)
    val euclid =
      bucketOf(records, "euclidean").query(VectorQuery(index, query, 10))
    assertEquals(euclid.map(_.key), Vector("near", "far", "same"))
    assertEqualsDouble(euclid(0).distance, 1.0, 1e-9)
    assertEqualsDouble(euclid(1).distance, math.sqrt(2.0), 1e-9)
    assertEqualsDouble(euclid(2).distance, 4.0, 1e-9)
  }

  test("a hit carries the stored metadata and a filter never reorders") {
    val b = facetBucket
    val hits = b.query(
      VectorQuery(index, query, 10, VectorFilter(source = Some("dblp")))
    )
    assertEquals(hits.map(_.key), Vector("a", "c"))
    assertEquals(hits.head.metadata("communityId"), Str("c1"))
  }

  test("missing optional keys are absence, never a match or a failure") {
    val b = bucketOf(
      Vector(
        rec("bare", Vector(1f, 0f, 0f), r1),
        rec("topical", Vector(0f, 1f, 0f), r1, "topic" -> Strs(Vector("t")))
      )
    )
    assertEquals(ask(b, VectorFilter(topic = Some("t"))), Set("topical"))
    assertEquals(
      ask(b, VectorFilter(institution = Some("i"))),
      Set.empty[String]
    )
    assertEquals(ask(b, VectorFilter(community = Some("c"))), Set.empty[String])
    assertEquals(ask(b, VectorFilter(source = Some("s"))), Set.empty[String])
    assertEquals(ask(b, VectorFilter.empty), Set("bare", "topical"))
  }

  test("an unknown filter key is refused by name, a typed error") {
    val error = intercept[StorageException](
      VectorFilter.parse(ujson.Obj("researcher" -> r1, "color" -> "red"))
    )
    assertEquals(error.code, ErrorCode.UnknownFilterKey)
    assert(error.getMessage.contains("color"))
    assertEquals(
      code(VectorFilter.parse(ujson.Obj("textRef" -> "x"))),
      ErrorCode.UnknownFilterKey
    )
    assertEquals(
      code(VectorFilter.parse(ujson.Obj("subjectId" -> r1))),
      ErrorCode.UnknownFilterKey
    )
  }

  test("malformed filters are refused with typed errors") {
    def bad(json: ujson.Value, expected: ErrorCode, label: String): Unit =
      assertEquals(code(VectorFilter.parse(json)), expected, label)
    bad(ujson.Arr(1), ErrorCode.InvalidFilter, "array")
    bad(ujson.Str("x"), ErrorCode.InvalidFilter, "string")
    bad(ujson.Null, ErrorCode.InvalidFilter, "null")
    bad(ujson.Obj("researcher" -> ujson.Null), ErrorCode.InvalidFilter, "null")
    bad(ujson.Obj("researcher" -> 5), ErrorCode.InvalidFilter, "number")
    bad(ujson.Obj("source" -> ujson.Arr("a")), ErrorCode.InvalidFilter, "list")
    bad(ujson.Obj("yearFrom" -> "2000"), ErrorCode.InvalidFilter, "text year")
    bad(ujson.Obj("yearFrom" -> 2000.5), ErrorCode.InvalidFilter, "fraction")
    bad(
      ujson.Obj("yearTo" -> ujson.Bool(true)),
      ErrorCode.InvalidFilter,
      "bool"
    )
    bad(ujson.Obj("yearFrom" -> 0), ErrorCode.InvalidFilter, "year 0")
    bad(ujson.Obj("yearTo" -> 10000), ErrorCode.InvalidFilter, "year 10000")
    bad(
      ujson.Obj("yearFrom" -> 2005, "yearTo" -> 2000),
      ErrorCode.InvalidFilter,
      "reversed"
    )
    bad(ujson.Obj("topic" -> ""), ErrorCode.InvalidFilter, "empty")
    bad(ujson.Obj("topic" -> " x"), ErrorCode.InvalidFilter, "untrimmed")
    bad(ujson.Obj("topic" -> "a\nb"), ErrorCode.InvalidFilter, "control")
    bad(
      ujson.Obj("topic" -> ("t" * 201)),
      ErrorCode.InvalidFilter,
      "too long"
    )
    bad(ujson.Obj("topic" -> "a\u0000b"), ErrorCode.NullCharacter, "nul")
    // the typed constructor applies the same rules
    assertEquals(
      code(VectorFilter(yearFrom = Some(3000), yearTo = Some(2000))),
      ErrorCode.InvalidFilter
    )
    assertEquals(
      code(VectorFilter(source = Some(" "))),
      ErrorCode.InvalidFilter
    )
    // and a good one round-trips
    assertEquals(
      VectorFilter.parse(
        ujson.Obj(
          "researcher" -> r1,
          "yearFrom" -> 2000,
          "yearTo" -> 2009,
          "source" -> "dblp",
          "topic" -> "t",
          "institution" -> "i",
          "community" -> "c",
          "documentType" -> "work"
        )
      ),
      VectorFilter(
        Some(r1),
        Some(2000),
        Some(2009),
        Some("dblp"),
        Some("t"),
        Some("i"),
        Some("c"),
        Some("work")
      )
    )
    assertEquals(VectorFilter.parse(ujson.Obj()), VectorFilter.empty)
    assertEquals(
      VectorFilter.Keys,
      Set(
        "researcher",
        "yearFrom",
        "yearTo",
        "source",
        "topic",
        "institution",
        "community",
        "documentType"
      )
    )
  }

  test("a bad query is refused: index, dimension, embedding, metric") {
    val b = bucketOf(Vector(rec("a", query, r1)))
    assertEquals(
      code(b.query(VectorQuery("ghost", query, 1))),
      ErrorCode.InvalidKey
    )
    assertEquals(
      code(b.query(VectorQuery(index, Vector(1f, 0f), 1))),
      ErrorCode.EmbeddingInvalid
    )
    assertEquals(
      code(VectorQuery(index, Vector(1f, Float.NaN, 0f), 1)),
      ErrorCode.EmbeddingInvalid
    )
    assertEquals(
      code(VectorQuery(index, Vector.empty, 1)),
      ErrorCode.EmbeddingInvalid
    )
    assertEquals(code(VectorQuery(" ", query, 1)), ErrorCode.InvalidQuery)
    assertEquals(
      code(b.query(VectorQuery(index, Vector(0f, 0f, 0f), 1))),
      ErrorCode.InvalidQuery
    )
    // a zero query is fine under euclidean
    val e = bucketOf(Vector(rec("a", query, r1)), "euclidean")
    assertEquals(e.query(VectorQuery(index, Vector(0f, 0f, 0f), 1)).size, 1)
    val odd = new InMemoryVectorBucket
    odd.ensureIndex(VectorIndexSpec(index, dim, "manhattan", None))
    assertEquals(
      code(odd.query(VectorQuery(index, query, 1))),
      ErrorCode.InvalidQuery
    )
  }

  test("D7 holds: a changed vector under an existing key is still refused") {
    val bucket = new InMemoryVectorBucket
    val spec = VectorIndexSpec(index, dim, "cosine", None)
    val writer = new VectorWriter(bucket, RetryPolicy(1, 1, 1), NoSleep)
    writer.write(spec, Vector(rec("a", Vector(1f, 0f, 0f), r1)))
    val again = writer.write(spec, Vector(rec("a", Vector(0f, 1f, 0f), r1)))
    assertEquals(
      again.rejected.map(_.code),
      Vector(ErrorCode.ChangedVectorContent)
    )
    val hit = bucket.query(VectorQuery(index, query, 5)).head
    assertEquals(hit.key, "a")
    assertEqualsDouble(hit.distance, 0.0, 1e-9)
    val changedMeta = writer.write(
      spec,
      Vector(rec("a", Vector(1f, 0f, 0f), r1, "source" -> Strs(Vector("x"))))
    )
    assertEquals(
      changedMeta.rejected.map(_.code),
      Vector(ErrorCode.ChangedVectorContent)
    )
  }

  test("facet keys are typed, bounded and never carry long text") {
    def bad(extra: (String, MetaValue)): ErrorCode =
      code(VectorMetadata.validate(meta(r1, extra)))
    assertEquals(bad("year" -> Num(1999.5)), ErrorCode.MetadataInvalid)
    assertEquals(bad("year" -> Num(0)), ErrorCode.MetadataInvalid)
    assertEquals(bad("year" -> Num(10000)), ErrorCode.MetadataInvalid)
    assertEquals(bad("year" -> Str("2000")), ErrorCode.MetadataInvalid)
    assertEquals(bad("source" -> Str("dblp")), ErrorCode.MetadataInvalid)
    assertEquals(bad("topic" -> Str("t")), ErrorCode.MetadataInvalid)
    assertEquals(bad("institution" -> Str("i")), ErrorCode.MetadataInvalid)
    assertEquals(
      bad("communityId" -> Strs(Vector("c"))),
      ErrorCode.MetadataInvalid
    )
    assertEquals(bad("documentType" -> Num(1)), ErrorCode.MetadataInvalid)
    assertEquals(
      bad("communityId" -> Str("c" * 201)),
      ErrorCode.MetadataInvalid
    )
    assertEquals(
      bad("source" -> Strs(Vector("a", "b", "c", "d", "e", "f"))),
      ErrorCode.MetadataInvalid
    )
    assertEquals(
      bad("topic" -> Strs(Vector("x" * 201))),
      ErrorCode.MetadataInvalid
    )
    VectorMetadata.validate(
      meta(
        r1,
        "year" -> Num(2000),
        "source" -> Strs(Vector("dblp")),
        "topic" -> Strs(Vector("t")),
        "institution" -> Strs(Vector("i")),
        "communityId" -> Str("c"),
        "documentType" -> Str("work")
      )
    )
    // the 50-key and 2 KB limits still apply with the new keys present
    val many = meta(r1, "communityId" -> Str("c")) ++
      (1 to 50).map(i => s"k$i" -> (Str("v"): MetaValue))
    assertEquals(code(VectorMetadata.validate(many)), ErrorCode.MetadataInvalid)
    val big = meta(
      r1,
      "source" -> Strs(Vector.fill(5)("s" * 199)),
      "topic" -> Strs(Vector.fill(5)("t" * 199)),
      "institution" -> Strs(Vector.fill(5)("i" * 199))
    )
    assert(
      VectorMetadata.sizeBytes(
        big
      ) > Limits.S3Vectors.MaxFilterableMetadataBytes
    )
    assertEquals(code(VectorMetadata.validate(big)), ErrorCode.MetadataInvalid)
  }

  test("the semantic loader sets facets from Spark columns, never invents") {
    val fdim = 4
    val tables = graphTables() +
      ("researcher-metrics" -> Vector(metricsRow(r1, "2000-2009", 2000, 2009)))
    val facets = SummaryFacets.fromTables(source(tables), LoadBounds.Default)
    assertEquals(facets.workYear(w1), 2001)
    assertEquals(facets.workSource(w2), "dblp")
    assertEquals(facets.communityOf((r1, "2000-2009")), "community-1")
    val work = summaryRow("work", "w1", "t") ++ Map(
      "subjectId" -> ujson.Str(w1),
      "window" -> ujson.Str("not-windowed"),
      "evidenceWorkIds" -> ujson.Arr(ujson.Str(w1)),
      "windowStart" -> ujson.Null,
      "windowEnd" -> ujson.Null
    )
    val rows = Vector(
      summaryRow("researcher-profile", "p1", "t"),
      summaryRow("community", "c1", "t") +
        ("subjectId" -> ujson.Str("community-1")),
      summaryRow("collaboration-path", "x1", "t"),
      work
    )
    val bucket = new InMemoryVectorBucket
    val report = SemanticVectorLoader.load(
      rows,
      VectorIndexes.all(fdim, "cosine"),
      new FixedEmbedder(fdim),
      "semantic-context/summaries",
      new VectorWriter(bucket, RetryPolicy(1, 1, 1), NoSleep),
      LoadBounds.Default,
      facets
    )
    assertEquals(report.perIndex.flatMap(_.rejected), Vector.empty)
    assertEquals(report.perIndex.map(_.written), Vector(1, 1, 1, 1, 0))
    def stored(idx: String, id: String) =
      bucket.getVectors(idx, Vector(s"summary:v1:$id")).head.metadata
    val profile = stored(VectorIndexes.ResearcherProfile, "p1")
    assertEquals(profile("documentType"), Str("researcher-profile"))
    assertEquals(profile("communityId"), Str("community-1"))
    assertEquals(profile("source"), Strs(Vector("dblp")))
    assert(!profile.contains("year"))
    val community = stored(VectorIndexes.CollaborationCommunity, "c1")
    assertEquals(community("communityId"), Str("community-1"))
    assertEquals(community("documentType"), Str("community"))
    val path = stored(VectorIndexes.CollaborationPath, "x1")
    assert(!path.contains("communityId"))
    assertEquals(path("documentType"), Str("collaboration-path"))
    val paper = stored(VectorIndexes.PaperAbstract, "w1")
    assertEquals(paper("year"), Num(2001))
    assertEquals(paper("source"), Strs(Vector("dblp")))
    assertEquals(paper("documentType"), Str("work"))
    // no topic or institution data exists in Spark, so none is written
    Vector(profile, community, path, paper).foreach { md =>
      assert(!md.contains("topic"), md.toString)
      assert(!md.contains("institution"), md.toString)
      assert(
        VectorMetadata.sizeBytes(md) <=
          Limits.S3Vectors.MaxFilterableMetadataBytes
      )
      assert(!md.keySet.exists(VectorMetadata.TextKeys.contains))
    }
    // and the facets answer queries
    val probe = Vector(1f, 2f, 3f, 4f)
    def hits(idx: String, filter: VectorFilter): Vector[String] =
      bucket.query(VectorQuery(idx, probe, 10, filter)).map(_.key)
    assertEquals(
      hits(
        VectorIndexes.ResearcherProfile,
        VectorFilter(community = Some("community-1"), researcher = Some(r1))
      ),
      Vector("summary:v1:p1")
    )
    assertEquals(
      hits(
        VectorIndexes.CollaborationCommunity,
        VectorFilter(community = Some("community-1"))
      ),
      Vector("summary:v1:c1")
    )
    assertEquals(
      hits(
        VectorIndexes.PaperAbstract,
        VectorFilter(yearFrom = Some(2001), yearTo = Some(2001))
      ),
      Vector("summary:v1:w1")
    )
    assertEquals(
      hits(VectorIndexes.PaperAbstract, VectorFilter(yearTo = Some(2000))),
      Vector.empty
    )
    assertEquals(
      hits(
        VectorIndexes.ResearcherProfile,
        VectorFilter(yearFrom = Some(2000), yearTo = Some(2009))
      ),
      Vector("summary:v1:p1")
    )
    Vector(
      VectorFilter(topic = Some("topic:ml")),
      VectorFilter(institution = Some("inst:mit"))
    ).foreach { filter =>
      VectorIndexes
        .all(fdim, "cosine")
        .foreach(spec => assertEquals(hits(spec.name, filter), Vector.empty))
    }
  }

  test("without facet tables the loader still loads, with documentType only") {
    val bucket = new InMemoryVectorBucket
    val report = SemanticVectorLoader.load(
      Vector(summaryRow("researcher-profile", "p1", "t")),
      VectorIndexes.all(4, "cosine"),
      new FixedEmbedder(4),
      "base",
      new VectorWriter(bucket, RetryPolicy(1, 1, 1), NoSleep),
      LoadBounds.Default
    )
    assertEquals(report.perIndex.flatMap(_.rejected), Vector.empty)
    val md = bucket
      .getVectors(VectorIndexes.ResearcherProfile, Vector("summary:v1:p1"))
      .head
      .metadata
    assertEquals(md("documentType"), Str("researcher-profile"))
    assert(!md.contains("communityId"))
    assert(!md.contains("source"))
    assert(
      SummaryFacets.fromTables(source(Map.empty), LoadBounds.Default) ==
        SummaryFacets.empty
    )
  }

  test("a deferred AWS bucket refuses to query") {
    val deferred = new DeferredAwsVectorBucket(AwsProfile("p"))
    assertEquals(
      code(deferred.query(VectorQuery(index, query, 1))),
      ErrorCode.DeferredAdapter
    )
  }
