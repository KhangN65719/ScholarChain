package scholarchain.tests.endtoend

import java.lang.reflect.{Method, Modifier, ParameterizedType}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.rag.*
import scholarchain.storage.*
import scholarchain.tests.adversarial.P8
import scholarchain.tests.adversarial.P8.*

/** Spec items 9 to 11: a Spark-shaped export on disk goes through the real
  * composition (the same classes `IngestMain` wires), then storage answers
  * filtered neighbour queries and `fetchText` returns the template text.
  */
class Phase8EndToEndSuite extends FunSuite:
  private lazy val exportDir: Path =
    writeExport(tempDir("p8-e2e").resolve("export"), rowsPerFile = 3)

  private final class Wired(val dir: Path):
    val embedder = new HashEmbedder
    val store = new LocalFsObjectStore(dir.resolve("objects"))
    val bucket = new InMemoryVectorBucket
    val ledger = new InMemoryDynamoTable(RunLedger.spec(TableNames("local")))
    val source: JsonlExportSource = JsonlExportSource.open(exportDir)
    val report: IngestReport =
      new EmbeddingIngest(embedder, RagTemplates, bucket, store, ledger).run(
        source,
        source.sourceRun,
        IngestConfig(startedAt)
      )
    val retrieval = new SemanticRetrieval(embedder, bucket, store)

  private lazy val wired = new Wired(tempDir("p8-e2e-out"))

  private def keys(found: Vector[RetrievedHit]): Set[String] =
    found.map(_.hit.key).toSet

  private def ids(labels: String*): Set[String] = labels.map(World.idOf).toSet

  private def search(
      index: String,
      query: String,
      filter: VectorFilter = VectorFilter.empty,
      topK: Int = 20
  ): Vector[RetrievedHit] = wired.retrieval.search(index, query, topK, filter)

  // Item 9: end to end ------------------------------------------------------

  test("the export on disk ingests cleanly through the real classes"):
    val report = wired.report
    assertEquals(report.rejected, Vector.empty)
    assertEquals(report.unmapped, Vector.empty)
    assertEquals(report.failedBatches, Vector.empty)
    assertEquals(report.written, World.summaries.size)
    assertEquals(report.manifestItems, World.summaries.size)
    assertEquals(
      report.perIndex.map(i => i.index -> i.vectorsInIndex),
      Vector(
        "researcher-profile-v1" -> 4,
        "collaboration-community-v1" -> 2,
        "paper-abstract-v1" -> 3,
        "collaboration-path-v1" -> 2,
        "topic-institution-summary-v1" -> 0
      )
    )
    assertEquals(
      wired.ledger
        .get(s"RUN#${report.executionId}", "META")
        .flatMap(Attr.optS(_, "status")),
      Some("COMPLETED")
    )

  test("IngestMain writes the same objects as the same composition"):
    val out = tempDir("p8-e2e-main").resolve("out")
    val lines = Vector.newBuilder[String]
    val outcome =
      IngestMain.execute(IngestMain.Args(exportDir, out, None), lines += _)
    assert(outcome.success, lines.result().mkString("\n"))
    val theirs = new LocalFsObjectStore(out.resolve(IngestMain.ObjectsDir))
    def listing(store: ObjectStore) =
      store.list("").map(m => m.key -> m.sha256).toMap
    assertEquals(listing(theirs), listing(wired.store))
    val text = lines.result()
    assertEquals(text.count(_.startsWith("SAMPLE ")), 4)
    assert(
      text
        .filter(_.startsWith("SAMPLE "))
        .forall(_.contains("source row: true"))
    )
    assertEquals(text.count(_.contains("fetchText: textHash verified")), 4)
    val wide = text.filter(_.startsWith("WIDE-SAMPLE "))
    assertEquals(wide.size, 4)
    assert(wide.forall(!_.contains("FAILED")), wide.toString)
    assert(wide.forall(!_.contains("source row rank: absent")), wide.toString)
    assert(wide.forall(_.contains("other candidates: true")), wide.toString)
    assertEquals(text.count(_.startsWith("index ")), 5)
    assert(
      text.exists(_.contains("index topic-institution-summary-v1: planned=0"))
    )

  test("fetchText yields the template text for every vector"):
    val byId = World.summaries.map(r => r("summaryId").str -> r).toMap
    indexNames.foreach { index =>
      search(index, "researcher community work path").foreach { found =>
        val source = byId(found.hit.key)
        val expected = RagTemplates.render(source)
        assertEquals(found.text.text, expected.text)
        assertEquals(found.text.templateVersion, expected.templateVersion)
        assertEquals(found.text.textHash, sha(expected.text))
        assert(found.text.text.contains(source("summaryText").str))
        assertEquals(found.text.summaryId, found.hit.key)
        assertEquals(found.text.runId, runId)
      }
    }

  test("researcher filter finds the researcher's own documents"):
    val q = "collaboration researcher"
    assertEquals(
      keys(
        search(
          VectorIndexes.ResearcherProfile,
          q,
          VectorFilter(researcher = Some(World.ada))
        )
      ),
      ids("adaW1", "adaW2")
    )
    assertEquals(
      keys(
        search(
          VectorIndexes.CollaborationCommunity,
          q,
          VectorFilter(researcher = Some(World.ada))
        )
      ),
      ids("comm1", "comm2")
    )
    assertEquals(
      keys(
        search(
          VectorIndexes.CollaborationPath,
          q,
          VectorFilter(researcher = Some(World.zoe))
        )
      ),
      ids("pathAZ")
    )
    assertEquals(
      keys(
        search(
          VectorIndexes.PaperAbstract,
          q,
          VectorFilter(researcher = Some(World.zoe))
        )
      ),
      ids("workA", "workC")
    )
    assertEquals(
      search(
        VectorIndexes.ResearcherProfile,
        q,
        VectorFilter(researcher = Some("researcher:v1:nobody"))
      ),
      Vector.empty
    )

  test("year window filter keeps windows inside the range and skips no-year"):
    def inYears(index: String, from: Int, to: Int): Set[String] =
      keys(
        search(
          index,
          "work researcher",
          VectorFilter(yearFrom = Some(from), yearTo = Some(to))
        )
      )
    val profiles = VectorIndexes.ResearcherProfile
    assertEquals(inYears(profiles, 2020, 2024), ids("adaW2", "graceW2"))
    assertEquals(inYears(profiles, 2015, 2019), ids("adaW1", "zoeW1"))
    assertEquals(
      inYears(profiles, 2015, 2024),
      ids("adaW1", "adaW2", "zoeW1", "graceW2")
    )
    assertEquals(inYears(profiles, 2016, 2024), ids("adaW2", "graceW2"))
    assertEquals(inYears(profiles, 1990, 2014), Set.empty[String])
    val papers = VectorIndexes.PaperAbstract
    assertEquals(inYears(papers, 2010, 2017), ids("workA"))
    assertEquals(inYears(papers, 2021, 2021), ids("workB"))
    assertEquals(inYears(papers, 1, 9999), ids("workA", "workB"))
    // Open-ended ranges behave the same way; the no-year work never matches.
    assertEquals(
      keys(
        search(papers, "work", VectorFilter(yearFrom = Some(2017)))
      ),
      ids("workB")
    )
    assertEquals(
      keys(search(papers, "work", VectorFilter(yearTo = Some(2016)))),
      ids("workA")
    )

  test("source filter follows the work source and skips unknown sources"):
    def from(index: String, source: String): Set[String] =
      keys(search(index, "work", VectorFilter(source = Some(source))))
    assertEquals(from(VectorIndexes.PaperAbstract, "dblp"), ids("workA"))
    assertEquals(from(VectorIndexes.PaperAbstract, "openalex"), ids("workB"))
    assertEquals(
      from(VectorIndexes.ResearcherProfile, "dblp"),
      ids("adaW1", "zoeW1")
    )
    assertEquals(
      from(VectorIndexes.ResearcherProfile, "openalex"),
      ids("adaW2", "graceW2")
    )
    assertEquals(
      from(VectorIndexes.CollaborationCommunity, "dblp"),
      ids("comm1")
    )
    assertEquals(
      from(VectorIndexes.CollaborationPath, "openalex"),
      ids("pathAG")
    )
    assertEquals(from(VectorIndexes.PaperAbstract, "arxiv"), Set.empty[String])

  test("community filter selects by community ID"):
    val q = "researcher community"
    val c1 = VectorFilter(community = Some(World.comm1))
    val c2 = VectorFilter(community = Some(World.comm2))
    assertEquals(
      keys(search(VectorIndexes.ResearcherProfile, q, c1)),
      ids("adaW1", "zoeW1")
    )
    assertEquals(
      keys(search(VectorIndexes.ResearcherProfile, q, c2)),
      ids("adaW2", "graceW2")
    )
    assertEquals(
      keys(search(VectorIndexes.CollaborationCommunity, q, c1)),
      ids("comm1")
    )
    assertEquals(
      keys(search(VectorIndexes.CollaborationCommunity, q, c2)),
      ids("comm2")
    )
    assertEquals(search(VectorIndexes.PaperAbstract, q, c1), Vector.empty)
    assertEquals(search(VectorIndexes.CollaborationPath, q, c1), Vector.empty)

  test("documentType filter selects by type and never crosses indexes"):
    val q = "summary"
    def byType(index: String, kind: String): Set[String] =
      keys(search(index, q, VectorFilter(documentType = Some(kind))))
    assertEquals(
      byType(VectorIndexes.ResearcherProfile, "researcher-profile"),
      ids("adaW1", "adaW2", "zoeW1", "graceW2")
    )
    assertEquals(
      byType(VectorIndexes.PaperAbstract, "work"),
      ids("workA", "workB", "workC")
    )
    assertEquals(
      byType(VectorIndexes.ResearcherProfile, "work"),
      Set.empty[String]
    )
    assertEquals(
      byType(VectorIndexes.PaperAbstract, "community"),
      Set.empty[String]
    )

  test("filters combine as AND and a topic or institution finds nothing"):
    val papers = VectorIndexes.PaperAbstract
    val profiles = VectorIndexes.ResearcherProfile
    assertEquals(
      keys(
        search(
          profiles,
          "researcher",
          VectorFilter(
            researcher = Some(World.ada),
            yearFrom = Some(2020),
            yearTo = Some(2024),
            source = Some("openalex"),
            community = Some(World.comm2),
            documentType = Some("researcher-profile")
          )
        )
      ),
      ids("adaW2")
    )
    assertEquals(
      search(
        profiles,
        "researcher",
        VectorFilter(researcher = Some(World.ada), source = Some("arxiv"))
      ),
      Vector.empty
    )
    assertEquals(
      search(papers, "work", VectorFilter(topic = Some("databases"))),
      Vector.empty
    )
    assertEquals(
      search(papers, "work", VectorFilter(institution = Some("MIT"))),
      Vector.empty
    )
    assertEquals(
      code(VectorFilter(yearFrom = Some(2024), yearTo = Some(2020))),
      ErrorCode.InvalidFilter
    )

  test("null and absent facets are absence, not wildcards"):
    val orphan = wired.bucket
      .getVectors(VectorIndexes.PaperAbstract, Vector(World.idOf("workC")))
      .head
    assert(!orphan.metadata.contains("year"))
    assert(!orphan.metadata.contains("source"))
    assert(!orphan.metadata.contains("windowStart"))
    assertEquals(metaStr(orphan.metadata, "window"), Some("not-windowed"))
    val unreachable = wired.bucket
      .getVectors(VectorIndexes.CollaborationPath, Vector(World.idOf("pathAZ")))
      .head
    assert(!unreachable.metadata.contains("source"))
    assertEquals(
      unreachable.metadata("evidenceWorkCount"),
      MetaValue.Num(0.0)
    )
    val anySource = Vector("dblp", "openalex", "arxiv", "x")
    anySource.foreach { s =>
      val found =
        search(
          VectorIndexes.CollaborationPath,
          "no path",
          VectorFilter(source = Some(s))
        )
      assert(!keys(found).contains(World.idOf("pathAZ")))
    }

  test("an export without the facet tables ingests, with no facet keys"):
    val bare = writeExport(
      tempDir("p8-bare").resolve("export"),
      Map("semantic-summaries" -> World.summaries)
    )
    val source = JsonlExportSource.open(bare)
    val bucket = new InMemoryVectorBucket
    val store = new MutableStore
    val report = new EmbeddingIngest(
      new HashEmbedder,
      RagTemplates,
      bucket,
      store,
      new InMemoryDynamoTable(RunLedger.spec(TableNames("p8")))
    ).run(source, source.sourceRun, IngestConfig(startedAt))
    assertEquals(report.rejected, Vector.empty)
    assertEquals(report.written, World.summaries.size)
    val retrieval = new SemanticRetrieval(new HashEmbedder, bucket, store)
    val all = indexNames.flatMap(index =>
      retrieval.search(index, "researcher work community path", 20)
    )
    assertEquals(all.size, World.summaries.size)
    all.foreach { found =>
      val keySet = found.hit.metadata.keySet
      assert(
        !keySet.contains("communityId") || found.hit.key == World.idOf(
          "comm1"
        ) || found.hit.key == World.idOf("comm2")
      )
      assert(!keySet.contains("source"))
      assert(!keySet.contains("year"))
    }
    assertEquals(
      retrieval.search(
        VectorIndexes.ResearcherProfile,
        "researcher",
        5,
        VectorFilter(source = Some("dblp"))
      ),
      Vector.empty
    )
    assertEquals(
      retrieval.search(
        VectorIndexes.PaperAbstract,
        "work",
        5,
        VectorFilter(yearFrom = Some(1))
      ),
      Vector.empty
    )

  // Item 10: retrieval smoke with known neighbours --------------------------

  test("distinct vocabularies find their own nearest neighbour first"):
    def top(index: String, query: String): String =
      search(index, query, topK = 3).head.hit.key
    val papers = VectorIndexes.PaperAbstract
    assertEquals(
      top(papers, "quantum chromodynamics lattice"),
      World.idOf("workA")
    )
    assertEquals(
      top(papers, "Bayesian clustering of genomes"),
      World.idOf("workB")
    )
    assertEquals(top(papers, "orphan manuscript"), World.idOf("workC"))
    val profiles = VectorIndexes.ResearcherProfile
    assertEquals(top(profiles, "Grace Hopper"), World.idOf("graceW2"))
    assertEquals(top(profiles, "Zoë 张伟"), World.idOf("zoeW1"))
    assert(
      Set(World.idOf("adaW1"), World.idOf("adaW2"))
        .contains(top(profiles, "Ada Lovelace-Müller"))
    )
    val adaTwo =
      search(profiles, "Ada Lovelace-Müller", topK = 2).map(_.hit.key)
    assertEquals(adaTwo.toSet, ids("adaW1", "adaW2"))
    val communities = VectorIndexes.CollaborationCommunity
    assertEquals(top(communities, "张伟"), World.idOf("comm1"))
    assertEquals(top(communities, "Hopper"), World.idOf("comm2"))
    val paths = VectorIndexes.CollaborationPath
    assertEquals(
      top(paths, "No collaboration path within the hop limit"),
      World.idOf("pathAZ")
    )
    assertEquals(
      top(paths, "connected by hop supported by work"),
      World.idOf("pathAG")
    )

  test("results are ordered by distance and a topK bounds the list"):
    val found = search(VectorIndexes.PaperAbstract, "work authors", topK = 2)
    assertEquals(found.size, 2)
    assert(found.head.hit.distance <= found.last.hit.distance)
    val all = search(VectorIndexes.PaperAbstract, "work authors", topK = 20)
    assertEquals(all.size, 3, "topK above the population returns all of it")
    all.zip(all.tail).foreach { (a, b) =>
      assert(a.hit.distance <= b.hit.distance)
    }

  test("a query whose words appear nowhere returns a list, no exception"):
    val query = "zzyzx qwertyuiop plugh xyzzy"
    indexNames.foreach { index =>
      val found = search(index, query, topK = 5)
      if index == VectorIndexes.TopicInstitutionSummary then
        assertEquals(found, Vector.empty)
      else
        assert(found.nonEmpty, index)
        found.zip(found.tail).foreach { (a, b) =>
          assert(a.hit.distance <= b.hit.distance, "ordered by distance")
        }
        // No claim of relevance: nothing is a near match.
        found.foreach { f =>
          assert(f.hit.distance > 0.3, s"${f.hit.distance} looks relevant")
          assert(f.hit.distance <= 2.0 + 1e-9)
        }
    }
    val context = wired.retrieval.context(
      query,
      search(VectorIndexes.PaperAbstract, query, topK = 3)
    )
    assert(!context.deterministicMetricsIncluded)
    assertEquals(context.items.size, 3)

  test("a featureless query is refused, not embedded"):
    assertEquals(
      code(search(VectorIndexes.PaperAbstract, "!!! --- ???")),
      ErrorCode.EmbeddingInvalid
    )
    assertEquals(
      code(search(VectorIndexes.PaperAbstract, "")),
      ErrorCode.EmbeddingInvalid
    )

  test("retrieval refuses wrong index generation and oversize topK"):
    assertEquals(
      code(search("paper-abstract-v2", "work")),
      ErrorCode.InvalidQuery
    )
    assertEquals(
      code(search(VectorIndexes.PaperAbstract, "work", topK = 21)),
      ErrorCode.BoundExceeded
    )

  test("retrieval detects a tampered document and a foreign embedding model"):
    val rig = new Rig()
    rig.ingest(World.source)
    val retrieval = new SemanticRetrieval(rig.embedder, rig.bucket, rig.store)
    val victim = rig.vectors(VectorIndexes.PaperAbstract).head
    val ref = metaStr(victim.metadata, "textRef").get
    rig.store.overwrite(ref, utf8("{\"schema\":\"x\"}"))
    assertEquals(
      code(retrieval.search(VectorIndexes.PaperAbstract, "work", 5)),
      ErrorCode.TextHashMismatch
    )
    val other = new Rig(embedder = new RenamedEmbedder("hash-embed-v2"))
    other.ingest(World.source)
    val mismatched =
      new SemanticRetrieval(
        new HashEmbedder,
        other.bucket,
        other.store
      )
    assertEquals(
      code(mismatched.search(VectorIndexes.PaperAbstract, "work", 5)),
      ErrorCode.EmbeddingInvalid
    )
    assertEquals(
      code(
        new SemanticRetrieval(
          new RenamedEmbedder("hash-embed-v2"),
          rig.bucket,
          rig.store
        )
      ),
      ErrorCode.EmbeddingInvalid
    )

  // Item 11: nothing answers exact metrics from vector similarity -----------

  private def publicMethods(c: Class[?]): Set[String] =
    c.getDeclaredMethods.toSet
      .filter(m => Modifier.isPublic(m.getModifiers) && !m.isSynthetic)
      .map(_.getName)
      .filterNot(_.contains("$"))

  private def fields(c: Class[?]): Set[String] =
    c.getDeclaredFields.toSet
      .filterNot(_.isSynthetic)
      .map(_.getName)
      .filterNot(_ == "MODULE$")

  test("the retrieval surface returns keys, distances, metadata and text only"):
    assertEquals(
      publicMethods(classOf[SemanticRetrieval]),
      Set("search", "context")
    )
    val search: Method =
      classOf[SemanticRetrieval].getDeclaredMethods
        .find(_.getName == "search")
        .get
    val generic = search.getGenericReturnType.asInstanceOf[ParameterizedType]
    assertEquals(
      generic.getActualTypeArguments.toVector,
      Vector[java.lang.reflect.Type](classOf[RetrievedHit])
    )
    assertEquals(fields(classOf[RetrievedHit]), Set("index", "hit", "text"))
    assertEquals(
      fields(classOf[VectorMatch]),
      Set("key", "distance", "metadata")
    )
    assertEquals(
      fields(classOf[FetchedText]),
      Set(
        "text",
        "key",
        "summaryId",
        "runId",
        "documentType",
        "templateVersion",
        "textHash"
      )
    )
    assertEquals(
      publicMethods(classOf[VectorBucketPort]),
      Set("ensureIndex", "putVectors", "getVectors", "count", "query")
    )
    val query =
      classOf[VectorBucketPort].getMethods.find(_.getName == "query").get
    assertEquals(
      query.getParameterTypes.toVector,
      Vector[Class[?]](classOf[VectorQuery])
    )
    val count =
      classOf[VectorBucketPort].getMethods.find(_.getName == "count").get
    assertEquals(count.getReturnType, java.lang.Integer.TYPE)
    assertEquals(
      fields(classOf[ContextItem]),
      Set(
        "vectorKey",
        "index",
        "documentType",
        "distance",
        "window",
        "windowStart",
        "windowEnd",
        "subjectId",
        "evidenceWorkIds",
        "evidenceNodeIds",
        "textRef",
        "templateVersion",
        "textHash",
        "snippet",
        "snippetTruncated",
        "textChars"
      )
    )

  test(
    "no public name in retrieval, ingest or context speaks of graph metrics"
  ):
    val forbidden = Vector(
      "shortest",
      "component",
      "bridge",
      "coreness",
      "weighteddegree",
      "publicationcount",
      "coauthorcount",
      "pagerank",
      "betweenness",
      "hops",
      "pathlength"
    )
    val classes: Vector[Class[?]] = Vector(
      classOf[SemanticRetrieval],
      classOf[RetrievedHit],
      classOf[SemanticContext],
      classOf[ContextItem],
      classOf[EmbeddingIngest],
      classOf[IngestReport],
      classOf[VectorBucketPort],
      classOf[InMemoryVectorBucket],
      classOf[VectorQuery],
      classOf[VectorFilter],
      classOf[VectorMatch]
    )
    classes.foreach { c =>
      (publicMethods(c) ++ fields(c)).foreach { name =>
        forbidden.foreach { word =>
          assert(
            !name.toLowerCase.contains(word),
            s"${c.getSimpleName}.$name suggests a graph metric"
          )
        }
      }
    }
    val filterKeys = VectorFilter.Keys
    assertEquals(
      filterKeys,
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

  test("a metric question gets text and an explicit no-metrics statement"):
    val question = "what is the shortest path and bridge score of Ada"
    val found = search(VectorIndexes.CollaborationPath, question, topK = 2)
    val context = wired.retrieval.context(question, found)
    val json = context.toJson
    assertEquals(json("deterministicMetricsIncluded"), ujson.False)
    json("items").arr.foreach { item =>
      val numeric = item.obj.collect { case (k, ujson.Num(_)) => k }.toSet
      assert(
        numeric.subsetOf(
          Set("distance", "windowStart", "windowEnd", "textChars")
        ),
        numeric.toString
      )
      assert(item.obj.keySet.intersect(metricKeys).isEmpty)
    }
    found.foreach { f =>
      val md = f.hit.metadata.keySet
      assert(md.intersect(metricKeys).isEmpty, md.toString)
    }
