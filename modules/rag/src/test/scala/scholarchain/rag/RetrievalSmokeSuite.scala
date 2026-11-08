package scholarchain.rag

import scholarchain.storage.*

class RetrievalSmokeSuite extends munit.FunSuite:
  import RagFixtures.*

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private lazy val env =
    val e = new Env()
    val report = e.run()
    assertEquals(report.rejected, Vector.empty[RejectedVector])
    assertEquals(report.failedBatches, Vector.empty[FailedBatch])
    e

  private def keys(hits: Vector[RetrievedHit]): Vector[String] =
    hits.map(_.hit.key)

  private def search(
      index: String,
      query: String,
      filter: VectorFilter = VectorFilter.empty,
      topK: Int = 5
  ) = env.retrieval.search(index, query, topK, filter)

  test("all five indexes exist; the topic index is empty") {
    val report = env.run(label = "check")
    assertEquals(
      report.perIndex.map(_.index),
      VectorIndexes.all(384, "cosine").map(_.name)
    )
    assertEquals(
      report.perIndex.map(_.vectorsInIndex),
      Vector(4, 3, 3, 2, 0)
    )
    assertEquals(
      search(VectorIndexes.TopicInstitutionSummary, "anything at all"),
      Vector.empty
    )
  }

  test("a researcher's name finds that researcher first") {
    val ada1 = search(VectorIndexes.ResearcherProfile, "Ada Quillfeather")
    assertEquals(keys(ada1).take(2).toSet, Set(id("p1"), id("p2")))
    assertEquals(ada1.head.hit.metadata("subjectId"), MetaValue.Str(ada))
    assertEquals(
      keys(search(VectorIndexes.ResearcherProfile, "Boris Marlowe")).head,
      id("p3")
    )
    assertEquals(
      keys(search(VectorIndexes.ResearcherProfile, "Chidi Okonkwo")).head,
      id("p4")
    )
  }

  test("a community is found by its members") {
    assertEquals(
      keys(search(VectorIndexes.CollaborationCommunity, "Dana Whitcombe")).head,
      id("c2")
    )
    assertEquals(
      keys(search(VectorIndexes.CollaborationCommunity, "Chidi Okonkwo")).head,
      id("c3")
    )
    assertEquals(
      keys(
        search(
          VectorIndexes.CollaborationCommunity,
          "Boris Marlowe Ada Quillfeather"
        )
      ).head,
      id("c1")
    )
  }

  test("a paper is found by the words of its title") {
    assertEquals(
      keys(
        search(VectorIndexes.PaperAbstract, "graph neural networks citation")
      ).head,
      id("w1")
    )
    assertEquals(
      keys(
        search(
          VectorIndexes.PaperAbstract,
          "protein folding molecular dynamics"
        )
      ).head,
      id("w2")
    )
    assertEquals(
      keys(
        search(VectorIndexes.PaperAbstract, "quantum error correction qubits")
      ).head,
      id("w3")
    )
  }

  test("a path is found by its endpoints") {
    assertEquals(
      keys(
        search(
          VectorIndexes.CollaborationPath,
          "path Ada Quillfeather Chidi Okonkwo"
        )
      ).head,
      id("x1")
    )
    assertEquals(
      keys(
        search(
          VectorIndexes.CollaborationPath,
          "Boris Marlowe Dana Whitcombe different components"
        )
      ).head,
      id("x2")
    )
  }

  test("filters narrow: year window") {
    val late = search(
      VectorIndexes.ResearcherProfile,
      "Ada Quillfeather",
      VectorFilter(yearFrom = Some(2010), yearTo = Some(2019))
    )
    assertEquals(keys(late), Vector(id("p2")))
    val early = search(
      VectorIndexes.ResearcherProfile,
      "Ada Quillfeather",
      VectorFilter(yearFrom = Some(2000), yearTo = Some(2009))
    )
    assertEquals(keys(early).toSet, Set(id("p1"), id("p3"), id("p4")))
    assertEquals(keys(early).head, id("p1"))
    // a work has a year, not a window
    val papers = search(
      VectorIndexes.PaperAbstract,
      "graph neural networks quantum",
      VectorFilter(yearFrom = Some(2010))
    )
    assertEquals(keys(papers), Vector(id("w3")))
  }

  test("filters narrow: community") {
    val one = search(
      VectorIndexes.ResearcherProfile,
      "Ada Quillfeather",
      VectorFilter(community = Some("community-1"))
    )
    assertEquals(keys(one).toSet, Set(id("p1"), id("p3")))
    val three = search(
      VectorIndexes.CollaborationCommunity,
      "community",
      VectorFilter(community = Some("community-3"))
    )
    assertEquals(keys(three), Vector(id("c3")))
    val none = search(
      VectorIndexes.ResearcherProfile,
      "Ada",
      VectorFilter(community = Some("community-9"))
    )
    assertEquals(none, Vector.empty)
  }

  test("filters narrow: researcher and document type") {
    val boris1 = search(
      VectorIndexes.ResearcherProfile,
      "Ada Quillfeather",
      VectorFilter(researcher = Some(boris))
    )
    assertEquals(keys(boris1), Vector(id("p3")))
    assertEquals(
      keys(
        search(
          VectorIndexes.CollaborationPath,
          "path",
          VectorFilter(researcher = Some(chidi))
        )
      ),
      Vector(id("x1"))
    )
    assertEquals(
      keys(
        search(
          VectorIndexes.CollaborationPath,
          "path",
          VectorFilter(researcher = Some(boris))
        )
      ),
      Vector(id("x2"))
    )
    assertEquals(
      search(
        VectorIndexes.PaperAbstract,
        "graph",
        VectorFilter(documentType = Some("community"))
      ),
      Vector.empty
    )
    assertEquals(
      search(
        VectorIndexes.PaperAbstract,
        "graph",
        VectorFilter(documentType = Some("work"))
      ).size,
      3
    )
  }

  test("filters combine") {
    val hits = search(
      VectorIndexes.ResearcherProfile,
      "Ada Quillfeather",
      VectorFilter(
        community = Some("community-1"),
        yearFrom = Some(2000),
        yearTo = Some(2009),
        documentType = Some("researcher-profile")
      )
    )
    assertEquals(keys(hits).head, id("p1"))
    assertEquals(keys(hits).toSet, Set(id("p1"), id("p3")))
  }

  test("fetchText returns the rendered text through the S3 pointer") {
    summaries.foreach { row =>
      val kind = Rows.str(row, "summaryType")
      val index = VectorIndexes
        .all(384, "cosine")
        .find(_.summaryType.contains(kind))
        .get
        .name
      val hit = env.bucket
        .getVectors(index, Vector(Rows.str(row, "summaryId")))
        .head
      val fetched = EmbeddingIngest.fetchText(
        env.store,
        VectorMatch(hit.key, 0.0, hit.metadata)
      )
      val rendered = RagTemplates.render(row)
      assertEquals(fetched.text, rendered.text)
      assertEquals(fetched.templateVersion, rendered.templateVersion)
      assertEquals(fetched.textHash, Hashing.sha256(rendered.text))
      assertEquals(fetched.documentType, kind)
    }
    val retrieved = search(VectorIndexes.PaperAbstract, "protein folding")
    assertEquals(
      retrieved.head.text.text,
      RagTemplates.render(summaries(8)).text
    )
    assert(retrieved.head.text.key.startsWith("semantic-context/documents/"))
  }

  test(
    "the model version and the template version are stored with every vector"
  ) {
    VectorIndexes.all(384, "cosine").foreach { spec =>
      env.bucket.getVectors(spec.name, Vector.empty)
    }
    summaries.foreach { row =>
      val kind = Rows.str(row, "summaryType")
      val index = VectorIndexes
        .all(384, "cosine")
        .find(_.summaryType.contains(kind))
        .get
        .name
      val md = env.bucket
        .getVectors(index, Vector(Rows.str(row, "summaryId")))
        .head
        .metadata
      assertEquals(md("embeddingModel"), MetaValue.Str("hash-embed-v1"))
      assertEquals(
        md("templateVersion"),
        MetaValue.Str(RagTemplates.versions(kind))
      )
      assertEquals(md("documentType"), MetaValue.Str(kind))
    }
  }

  test("the same key never refers to different text") {
    val fresh = new Env()
    fresh.run()
    val early = VectorFilter(yearFrom = Some(2000), yearTo = Some(2009))
    val before = fresh.retrieval
      .search(VectorIndexes.ResearcherProfile, "Ada Quillfeather", 1, early)
      .head
    assertEquals(before.hit.key, id("p1"))
    val changed = summaries.map { row =>
      if row("summaryId") == ujson.Str(id("p1")) then
        row + ("summaryText" -> ujson.Str("Researcher Somebody Else entirely."))
      else row
    }
    val report = fresh.run(changed, label = "2")
    assertEquals(
      report.rejected.map(r => (r.key, r.code)),
      Vector((id("p1"), ErrorCode.ChangedVectorContent))
    )
    val after = fresh.retrieval
      .search(VectorIndexes.ResearcherProfile, "Ada Quillfeather", 1, early)
      .head
    assertEquals(after.hit, before.hit)
    assertEquals(after.text, before.text)
    assertEquals(after.text.text, RagTemplates.render(summaries.head).text)
  }

  test("a changed template needs a new key or index, never an overwrite") {
    val fresh = new Env()
    fresh.run()
    val passThrough = new EmbeddingIngest(
      fresh.embedder,
      PassThroughRenderer,
      fresh.bucket,
      fresh.store,
      new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    )
    val report = passThrough.run(
      source(),
      SourceRun(runId, transformVersion),
      IngestConfig("2026-11-08T00:00:00Z")
    )
    assertEquals(report.written, 0)
    assertEquals(report.rejected.size, summaries.size)
    assert(report.rejected.forall(_.code == ErrorCode.ChangedVectorContent))
  }

  test("a rerun of unchanged text and model skips and writes nothing") {
    val fresh = new Env()
    fresh.run()
    val again = fresh.run(label = "2")
    assertEquals(again.written, 0)
    assertEquals(again.skipped, summaries.size)
    assertEquals(again.rejected, Vector.empty)
  }

  test("a vector made by another model is refused at query time") {
    val fresh = new Env()
    fresh.run()
    val specs = VectorIndexes.all(384, "cosine")
    val foreign = new InMemoryVectorBucket
    specs.foreach(foreign.ensureIndex)
    val record = fresh.bucket
      .getVectors(VectorIndexes.PaperAbstract, Vector(id("w1")))
      .head
    foreign.putVectors(
      VectorIndexes.PaperAbstract,
      Vector(
        record.copy(metadata =
          record.metadata + ("embeddingModel" -> MetaValue.Str("other-model"))
        )
      )
    )
    val retrieval = new SemanticRetrieval(fresh.embedder, foreign, fresh.store)
    assertEquals(
      code(retrieval.search(VectorIndexes.PaperAbstract, "graph", 1)),
      ErrorCode.EmbeddingInvalid
    )
  }

  test("an index of another model generation is refused") {
    assertEquals(
      code(env.retrieval.search("researcher-profile-v2", "Ada", 1)),
      ErrorCode.InvalidQuery
    )
  }

  private val metricNames = Set(
    "publicationCount",
    "distinctCoauthorCount",
    "totalCoauthorOccurrences",
    "averageCoauthorsPerPaper",
    "maximumCoauthorsOnOnePaper",
    "weightedDegree",
    "componentSize",
    "communitySize",
    "coreness",
    "participationCoefficient",
    "bridgeScore",
    "pathLength",
    "jointPublicationCount",
    "componentId",
    "shortestPath",
    "edgeCount"
  )

  test("exact graph metrics are not answered from vector similarity") {
    val queries = Vector(
      VectorIndexes.ResearcherProfile -> "how many publications does Ada Quillfeather have",
      VectorIndexes.CollaborationPath -> "shortest path length between Ada Quillfeather and Chidi Okonkwo",
      VectorIndexes.CollaborationCommunity -> "which component has the largest bridge score"
    )
    queries.foreach { (index, text) =>
      val hits = search(index, text, topK = 3)
      assert(hits.nonEmpty)
      hits.foreach { h =>
        assertEquals(
          h.hit.metadata.keySet.intersect(metricNames),
          Set.empty[String]
        )
        assert(h.hit.metadata.keySet.subsetOf(VectorMetadata.Allowed))
      }
      val context = env.retrieval.context(text, hits)
      val json = SemanticContext.write(context)
      assert(!context.deterministicMetricsIncluded)
      assert(json.contains("not from this retrieval"))
      val parsed = ujson.read(json)
      assertEquals(parsed.obj.keySet.intersect(metricNames), Set.empty[String])
      parsed("items").arr.foreach { item =>
        assertEquals(item.obj.keySet.intersect(metricNames), Set.empty[String])
      }
    }
  }

  test("a vector match holds a key, a distance and compact metadata only") {
    val hit =
      search(VectorIndexes.ResearcherProfile, "Ada Quillfeather").head.hit
    assert(hit.metadata.values.forall {
      case MetaValue.Str(v) =>
        v.length <= Limits.S3Vectors.MaxMetadataStringChars
      case MetaValue.Strs(v) => v.size <= Limits.S3Vectors.MaxMetadataListItems
      case _                 => true
    })
    assert(!hit.metadata.contains("summaryText"))
    assert(!hit.metadata.contains("text"))
  }
