package scholarchain.storage

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  GetIndexRequest,
  GetIndexResponse,
  GetVectorsResponse,
  Index,
  ListOutputVector,
  ListVectorsRequest,
  ListVectorsResponse,
  MetadataConfiguration,
  PutInputVector
}

/** Work order 101: S run 7 failed three of four indexes because a metadata list
  * was empty and S3 Vectors rejects empty arrays. An empty evidence list is now
  * an absent key; absent and empty read alike.
  */
class VectorEmptyListSuite extends munit.FunSuite:
  import Fixtures.*

  private val source = SourceRun(runId, "spark-analytics/1.1.0")
  private val model = HashEmbedder.ModelVersion

  private def arr(ids: Vector[String]): ujson.Value =
    ujson.Arr.from(ids.map(ujson.Str(_)))

  private def row(
      kind: String,
      id: String,
      text: String,
      works: Vector[String],
      nodes: Vector[String],
      subject: String = r1
  ): Row =
    summaryRow(kind, id, text)
      .updated("subjectId", ujson.Str(subject))
      .updated("evidenceWorkIds", arr(works))
      .updated("evidenceNodeIds", arr(nodes))

  private def metadataOf(r: Row): Map[String, MetaValue] =
    SemanticVectorLoader.metadataFor(r, model, "ref", SummaryFacets.empty)

  private def tablesOf(rows: Vector[Row]): TableSource =
    new InMemoryTableSource(
      Map(
        "semantic-summaries" -> rows,
        "nodes-work" -> graphTables()("nodes-work"),
        "researcher-metrics" -> Vector(metricsRow(r1, "2000-2009", 2000, 2009))
      )
    )

  private def emptyLists(md: Map[String, MetaValue]): Vector[String] =
    md.toVector.collect { case (k, MetaValue.Strs(v)) if v.isEmpty => k }

  // Item 1: omit empty lists ------------------------------------------------

  private val kinds =
    Vector("researcher-profile", "community", "work", "collaboration-path")

  private val shapes: Vector[(String, Row => Row)] = Vector(
    "both lists present" -> (r => r),
    "work list empty" -> (_.updated("evidenceWorkIds", arr(Vector.empty))),
    "node list empty" -> (_.updated("evidenceNodeIds", arr(Vector.empty))),
    "both lists empty" -> (
      _.updated("evidenceWorkIds", arr(Vector.empty))
        .updated("evidenceNodeIds", arr(Vector.empty))
    ),
    "both columns absent" -> (_ - "evidenceWorkIds" - "evidenceNodeIds"),
    "both columns null" -> (
      _.updated("evidenceWorkIds", ujson.Null)
        .updated("evidenceNodeIds", ujson.Null)
    )
  )

  test("metadataFor never yields an empty list, for every type and shape") {
    for
      kind <- kinds
      (label, change) <- shapes
    do
      val r = change(summaryRow(kind, "x", "text"))
      val md = metadataOf(r)
      val works = Rows.strList(r, "evidenceWorkIds")
      val nodes = Rows.strList(r, "evidenceNodeIds")
      val where = s"$kind / $label"
      assertEquals(emptyLists(md), Vector.empty, where)
      assertEquals(md.contains("evidenceWorkIds"), works.nonEmpty, where)
      assertEquals(md.contains("evidenceNodeIds"), nodes.nonEmpty, where)
      if works.nonEmpty then
        assertEquals(
          md("evidenceWorkIds"),
          MetaValue.Strs(works.take(5)),
          where
        )
      if nodes.nonEmpty then
        assertEquals(
          md("evidenceNodeIds"),
          MetaValue.Strs(nodes.take(5)),
          where
        )
      // The count stays, whatever the list.
      assertEquals(
        md("evidenceWorkCount"),
        MetaValue.Num(works.size.toDouble),
        where
      )
      VectorMetadata.validate(md)
  }

  private val goldenProfile: Map[String, MetaValue] = Map(
    "summaryType" -> MetaValue.Str("researcher-profile"),
    "subjectId" -> MetaValue.Str(r1),
    "textRef" -> MetaValue.Str("ref"),
    "runId" -> MetaValue.Str(runId),
    "embeddingModel" -> MetaValue.Str(model),
    "storageTransformVersion" -> MetaValue.Str("storage/1.0.0"),
    "sparkTransformVersion" -> MetaValue.Str("spark-analytics/1.1.0"),
    "evidenceWorkCount" -> MetaValue.Num(7),
    "evidenceWorkIds" -> MetaValue.Strs(Vector(w1, w2, w3, w1, w2)),
    "evidenceNodeIds" -> MetaValue.Strs(Vector(r1)),
    "window" -> MetaValue.Str("2000-2009"),
    "nodeKind" -> MetaValue.Str("canonical"),
    "windowStart" -> MetaValue.Num(2000),
    "windowEnd" -> MetaValue.Num(2009),
    "documentType" -> MetaValue.Str("researcher-profile")
  )

  private def canonicalJson(md: Map[String, MetaValue]): String =
    ujson.write(
      ujson.Obj.from(md.toVector.sortBy(_._1).map((k, v) => k -> v.json))
    )

  test("a researcher-profile row keeps exactly the metadata it had before") {
    val md = metadataOf(summaryRow("researcher-profile", "p1", "text"))
    assertEquals(md, goldenProfile)
    assertEquals(canonicalJson(md), canonicalJson(goldenProfile))
    assertEquals(
      Hashing.sha256(canonicalJson(md)),
      Hashing.sha256(canonicalJson(goldenProfile))
    )
    assertEquals(
      VectorDocuments.metadata(md),
      VectorDocuments.metadata(goldenProfile)
    )
  }

  test("the researcher-profile golden metadata survives the wire round trip") {
    assertEquals(
      VectorDocuments.metadataOf(VectorDocuments.metadata(goldenProfile)),
      goldenProfile
    )
  }

  /** What the loader wrote before work order 101 for a researcher-profile row
    * of the fixture: written out by hand, not derived from the new code.
    */
  private def storedBefore(r: Row, text: String): VectorRecord =
    val id = Rows.str(r, "summaryId")
    val hash = Hashing.sha256(text)
    VectorRecord(
      id,
      new HashEmbedder().embed(text),
      goldenProfile ++ Map(
        "textRef" -> MetaValue.Str(
          SemanticDocuments
            .key(runId, id, hash, PassThroughRenderer.TemplateVersion)
        ),
        "communityId" -> MetaValue.Str("community-1"),
        "source" -> MetaValue.Strs(Vector("dblp")),
        "textHash" -> MetaValue.Str(hash),
        "templateVersion" -> MetaValue.Str(PassThroughRenderer.TemplateVersion)
      )
    )

  private class CountingBucket extends InMemoryVectorBucket:
    var puts = 0
    override def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
      puts += 1
      super.putVectors(index, batch)

  private val profileSpec = VectorIndexes
    .all(384, "cosine")
    .find(_.name == VectorIndexes.ResearcherProfile)
    .get

  private val profiles: Vector[(Row, String)] = (1 to 12).toVector.map { i =>
    val text = s"researcher number $i studies topic $i in graph analysis"
    (summaryRow("researcher-profile", f"p$i%02d", text), text)
  }

  private def rerun(seeded: Vector[VectorRecord]) =
    val bucket = new CountingBucket
    bucket.ensureIndex(profileSpec)
    bucket.putVectors(profileSpec.name, seeded)
    bucket.puts = 0
    val report = new EmbeddingIngest(
      new HashEmbedder,
      PassThroughRenderer,
      bucket,
      new InMemoryObjectStore,
      new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    ).run(
      tablesOf(profiles.map(_._1)),
      source,
      IngestConfig("2026-11-08T00:00:00Z")
    )
    (bucket, report)

  test("vectors stored before the change are skipped as unchanged on rerun") {
    val (bucket, report) = rerun(profiles.map(storedBefore))
    assertEquals(report.written, 0)
    assertEquals(report.perIndex.head.skippedUnchanged, 12)
    assertEquals(report.metadataDrift, 0)
    assertEquals(report.rejected, Vector.empty)
    assertEquals(report.failedBatches, Vector.empty)
    assertEquals(bucket.puts, 0)
    assertEquals(bucket.count(profileSpec.name), 12)
  }

  test("the rerun check is sensitive: a changed stored list shows as drift") {
    val moved = profiles.map { (r, text) =>
      val stored = storedBefore(r, text)
      stored.copy(metadata =
        stored.metadata + ("evidenceNodeIds" -> MetaValue.Strs(Vector(r2)))
      )
    }
    val (bucket, report) = rerun(moved)
    assertEquals(report.written, 0)
    assertEquals(report.metadataDrift, 12)
    assert(report.metadataDriftKeys.forall(_.contains("evidenceNodeIds")))
    assertEquals(bucket.puts, 0)
  }

  // Item 2: validate rejects an empty list ---------------------------------

  private val required = Map(
    "summaryType" -> MetaValue.Str("work"),
    "subjectId" -> MetaValue.Str("r1"),
    "textRef" -> MetaValue.Str("ref"),
    "runId" -> MetaValue.Str("run")
  )

  test("validate rejects an empty Strs with the confirmed rule in the text") {
    VectorMetadata.validate(required)
    Vector("evidenceWorkIds", "evidenceNodeIds", "source", "topic").foreach {
      key =>
        val error = intercept[StorageException](
          VectorMetadata.validate(required + (key -> MetaValue.Strs(Vector())))
        )
        assertEquals(error.code, ErrorCode.MetadataInvalid, key)
        assert(error.getMessage.contains(key), error.getMessage)
        assert(
          error.getMessage.contains(
            "S3 Vectors rejects empty arrays; confirmed on the real service in S run 7"
          ),
          error.getMessage
        )
    }
    // A one-element list is still fine.
    VectorMetadata.validate(
      required + ("evidenceNodeIds" -> MetaValue.Strs(Vector("r1")))
    )
  }

  test("the writer rejects a record with an empty list and writes the rest") {
    val bucket = new InMemoryVectorBucket
    val spec = VectorIndexSpec("idx", 2, "cosine", None)
    val good = VectorRecord("summary:v1:good", Vector(1f, 0f), required)
    val bad = VectorRecord(
      "summary:v1:bad",
      Vector(0f, 1f),
      required + ("evidenceNodeIds" -> MetaValue.Strs(Vector()))
    )
    val report =
      new VectorWriter(bucket, RetryPolicy(1, 0, 0), NoSleep)
        .write(spec, Vector(good, bad))
    assertEquals(report.written, 1)
    assertEquals(
      report.rejected.map(r => (r.key, r.code)),
      Vector(("summary:v1:bad", ErrorCode.MetadataInvalid))
    )
  }

  // Item 3: absent equals empty --------------------------------------------

  test(
    "an absent evidence key reads as an empty list in every storage reader"
  ) {
    val md = metadataOf(
      row("work", "w", "text", Vector(w1), Vector.empty, subject = w1)
    )
    assert(!md.contains("evidenceNodeIds"))
    // VectorFilter.matches: the researcher is the subject or in the node list.
    assert(VectorFilter(researcher = Some(w1)).matches(md))
    assert(!VectorFilter(researcher = Some(r2)).matches(md))
    val listed = md + ("evidenceNodeIds" -> MetaValue.Strs(Vector(r2)))
    assert(VectorFilter(researcher = Some(r2)).matches(listed))
    // The wire round trip keeps an absent key absent.
    assertEquals(VectorDocuments.metadataOf(VectorDocuments.metadata(md)), md)
    assert(!VectorDocuments.metadata(md).asMap.containsKey("evidenceNodeIds"))
    // The same through the fake's QueryVectors.
    val bucket = new InMemoryVectorBucket
    val spec = VectorIndexSpec("idx", 2, "cosine", None)
    bucket.ensureIndex(spec)
    bucket.putVectors(
      "idx",
      Vector(
        VectorRecord("summary:v1:a", Vector(1f, 0f), required),
        VectorRecord(
          "summary:v1:b",
          Vector(0f, 1f),
          required + ("evidenceNodeIds" -> MetaValue.Strs(Vector(r2)))
        )
      )
    )
    def keys(filter: VectorFilter) =
      bucket.query(VectorQuery("idx", Vector(1f, 1f), 5, filter)).map(_.key)
    assertEquals(
      keys(VectorFilter(researcher = Some(r2))),
      Vector("summary:v1:b")
    )
    assertEquals(
      keys(VectorFilter(researcher = Some("r1"))).toSet,
      Set("summary:v1:a", "summary:v1:b")
    )
  }

  // Item 6: the four document types through the rules-enforcing fakes ------

  private val fixture = Vector(
    row(
      "researcher-profile",
      "p1",
      "Ada studies graph neural networks and communities",
      Vector(w1, w2),
      Vector(r1)
    ),
    row(
      "community",
      "c-single",
      "A singleton community holding only Ada",
      Vector.empty,
      Vector(r1)
    ),
    row(
      "community",
      "c-empty",
      "A community with no recorded evidence at all",
      Vector.empty,
      Vector.empty
    ),
    row(
      "work",
      "w-a",
      "A paper on graph neural networks with a known author list",
      Vector(w1),
      Vector.empty,
      subject = w1
    ),
    row(
      "work",
      "w-b",
      "A paper whose evidence lists are both empty",
      Vector.empty,
      Vector.empty,
      subject = w2
    ),
    row(
      "collaboration-path",
      "x-trivial",
      "A trivial path from Ada to Ada",
      Vector.empty,
      Vector(r1)
    ),
    row(
      "collaboration-path",
      "x-unreach",
      "No path between Ada and Bo within the hop bound",
      Vector.empty,
      Vector(r1, r2)
    ),
    row(
      "collaboration-path",
      "x-empty",
      "A path row that lists no evidence",
      Vector.empty,
      Vector.empty
    )
  )

  private def ingest(bucket: VectorBucketPort) =
    new EmbeddingIngest(
      new HashEmbedder,
      PassThroughRenderer,
      bucket,
      new InMemoryObjectStore,
      new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    ).run(
      tablesOf(fixture),
      source,
      IngestConfig("2026-11-08T00:00:00Z")
    )

  private def assertClean(report: IngestReport): Unit =
    assertEquals(report.rejected, Vector.empty)
    assertEquals(report.failedBatches, Vector.empty)
    assertEquals(report.unmapped, Vector.empty)
    assertEquals(report.written, fixture.size)
    assertEquals(
      report.perIndex.map(i => (i.index, i.written)),
      Vector(
        (VectorIndexes.ResearcherProfile, 1),
        (VectorIndexes.CollaborationCommunity, 2),
        (VectorIndexes.PaperAbstract, 2),
        (VectorIndexes.CollaborationPath, 3),
        (VectorIndexes.TopicInstitutionSummary, 0)
      )
    )

  private def assertLists(stored: String => Map[String, MetaValue]): Unit =
    def lists(id: String): (Boolean, Boolean) =
      val md = stored(s"summary:v1:$id")
      assertEquals(emptyLists(md), Vector.empty, id)
      (md.contains("evidenceWorkIds"), md.contains("evidenceNodeIds"))
    assertEquals(lists("p1"), (true, true))
    assertEquals(lists("c-single"), (false, true))
    assertEquals(lists("c-empty"), (false, false))
    assertEquals(lists("w-a"), (true, false))
    assertEquals(lists("w-b"), (false, false))
    assertEquals(lists("x-trivial"), (false, true))
    assertEquals(lists("x-unreach"), (false, true))
    assertEquals(lists("x-empty"), (false, false))

  test("four document types, with empty lists, write through the fake") {
    val bucket = new InMemoryVectorBucket
    assertClean(ingest(bucket))
    val byIndex = Map(
      "p1" -> VectorIndexes.ResearcherProfile,
      "c-single" -> VectorIndexes.CollaborationCommunity,
      "c-empty" -> VectorIndexes.CollaborationCommunity,
      "w-a" -> VectorIndexes.PaperAbstract,
      "w-b" -> VectorIndexes.PaperAbstract,
      "x-trivial" -> VectorIndexes.CollaborationPath,
      "x-unreach" -> VectorIndexes.CollaborationPath,
      "x-empty" -> VectorIndexes.CollaborationPath
    )
    assertLists { key =>
      val id = key.stripPrefix("summary:v1:")
      bucket.getVectors(byIndex(id), Vector(key)).head.metadata
    }
  }

  test(
    "the same fixture passes the rules-enforcing SDK stub, record by record"
  ) {
    val sent = mutable.ArrayBuffer.empty[(String, PutInputVector)]
    val stub = new SdkStub(classOf[S3VectorsClient])
      .on("getIndex") { request =>
        val name = request.asInstanceOf[GetIndexRequest].indexName
        GetIndexResponse
          .builder()
          .index(
            Index
              .builder()
              .indexName(name)
              .dimension(384)
              .dataType("float32")
              .distanceMetric("cosine")
              .indexMode("ENHANCED")
              .metadataConfiguration(
                MetadataConfiguration
                  .builder()
                  .nonFilterableMetadataKeys(
                    Vector("textRef", "textHash").asJava
                  )
                  .build()
              )
              .build()
          )
          .build()
      }
      .on("getVectors")(_ => GetVectorsResponse.builder().build())
      .on("putVectors")(S3VectorsWire.putHandler((i, v) => sent += ((i, v))))
      .on("listVectors") { request =>
        val name = request.asInstanceOf[ListVectorsRequest].indexName
        val listed = sent.toVector
          .filter(_._1 == name)
          .map(s => ListOutputVector.builder().key(s._2.key).build())
        ListVectorsResponse.builder().vectors(listed.asJava).build()
      }
    assertClean(ingest(new AwsVectorBucket(stub.client, "vec-bucket")))
    assertEquals(sent.size, fixture.size)
    val wire = sent.toVector.map(s => s._2.key -> s._2.metadata).toMap
    assertLists(key => VectorDocuments.metadataOf(wire(key)))
    // No Document of the run holds an empty list, the rule the service refused.
    sent.foreach { case (_, vector) =>
      vector.metadata.asMap.asScala.values.foreach { value =>
        assert(!value.isList || !value.asList.isEmpty, vector.key)
      }
    }
  }
