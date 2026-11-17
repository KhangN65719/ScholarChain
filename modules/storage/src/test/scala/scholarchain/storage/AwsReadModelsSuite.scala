package scholarchain.storage

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scholarchain.core.YearWindow

/** The export-backed read models of one graph run, loaded from the data bucket
  * (an in-memory object store stands in for it).
  */
class AwsReadModelsSuite extends munit.FunSuite:
  import Fixtures.*

  private val label = "2000-2009"
  private val otherRun = "run:v1:22222222-2222-4222-8222-222222222222"
  private val prefix = AwsReadModels.exportPrefix(runId)

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def message(thunk: => Any): String =
    intercept[StorageException](thunk).getMessage

  // ---- the fixture export ---------------------------------------------

  private def row(
      table: String,
      record: String,
      fields: (String, ujson.Value)*
  ) =
    (fields.toMap + ("provenance" -> provenance(table, record))): Row

  private def hop(source: String, target: String): Row =
    val key = s"$label|$source|$target"
    row(
      "path-evidence",
      s"$key|1",
      "pairKey" -> key,
      "window" -> label,
      "sourceId" -> source,
      "targetId" -> target,
      "status" -> ujson.Null,
      "hopIndex" -> 1,
      "fromId" -> source,
      "toId" -> target,
      "edgeId" -> edgeId(source, target),
      "jointPublicationCount" -> 2,
      "supportingWorkIds" -> ujson.Arr(w1, w2),
      "pathLength" -> 1,
      "hopLimit" -> 6,
      "firstCollaborationYear" -> 2001,
      "lastCollaborationYear" -> 2005
    )

  private def status(source: String, target: String): Row =
    val key = s"$label|$source|$target"
    row(
      "path-evidence",
      key,
      "pairKey" -> key,
      "window" -> label,
      "sourceId" -> source,
      "targetId" -> target,
      "status" -> "unreachable-within-bound",
      "unreachableReason" -> "different-component",
      "hopLimit" -> 6
    )

  private def tables(): Map[String, Vector[Row]] = Map(
    "nodes-researcher" -> Vector(
      researcherNode(r1, "canonical", "Ada Quill"),
      researcherNode(r2, "canonical", "Bo Chen"),
      researcherNode(m1, "unresolved-mention", "A. Unknown")
    ),
    "nodes-work" -> Vector(
      workNode(w1, "First paper", 2001),
      workNode(w2, "Second paper", 2005)
    ),
    "researcher-metrics" -> Vector(
      metricsRow(r1, label, 2000, 2009),
      metricsRow(r2, label, 2000, 2009),
      metricsRow(r1, "2010-2019", 2010, 2019)
    ),
    "path-evidence" -> Vector(hop(r1, r2), status(r1, m1)),
    "components" -> Vector(
      row(
        "components",
        s"$label|component-1",
        "window" -> label,
        "componentId" -> "component-1",
        "componentSize" -> 2,
        "isLargest" -> true
      )
    ),
    "component-membership" -> Vector(r1, r2).map { id =>
      row(
        "component-membership",
        s"$label|$id",
        "window" -> label,
        "researcherId" -> id,
        "componentId" -> "component-1",
        "componentSize" -> 2
      )
    },
    "communities" -> Vector(
      row(
        "communities",
        s"$label|community-1",
        "window" -> label,
        "communityId" -> "community-1",
        "communitySize" -> 2,
        "internalEdges" -> 1,
        "crossEdges" -> 0,
        "internalWeight" -> 2,
        "crossWeight" -> 0
      )
    ),
    "community-membership" -> Vector(r1, r2).map { id =>
      row(
        "community-membership",
        s"$label|$id",
        "window" -> label,
        "researcherId" -> id,
        "communityId" -> "community-1",
        "communitySize" -> 2
      )
    },
    "community-cross-edges" -> Vector.empty
  )

  /** The export files written as the Spark exporter lays them out, and the
    * directory they were written to.
    */
  private def exportDir(
      data: Map[String, Vector[Row]] = tables(),
      run: String = runId
  ): Path =
    ExportFixtures.write(ExportFixtures.newDir(), data, runId = run)

  private def files(dir: Path): Vector[(String, Array[Byte])] =
    val stream = Files.walk(dir)
    try
      stream.iterator.asScala.toVector
        .filter(Files.isRegularFile(_))
        .map(p => dir.relativize(p).toString -> Files.readAllBytes(p))
        .sortBy(_._1)
    finally stream.close()

  /** The export uploaded under the documented key layout of `forRun`. */
  private def upload(
      dir: Path,
      forRun: String = runId,
      skip: Set[String] = Set.empty,
      slugPrefix: String = ""
  ): InMemoryObjectStore =
    val store = new InMemoryObjectStore
    files(dir).filterNot(f => skip.contains(f._1)).foreach {
      case (name, bytes) =>
        store.putImmutable(
          (if slugPrefix.isEmpty then AwsReadModels.exportPrefix(forRun)
           else slugPrefix) + name,
          bytes,
          "test-upload"
        )
    }
    store

  private def uploaded(
      data: Map[String, Vector[Row]] = tables(),
      run: String = runId
  ): InMemoryObjectStore = upload(exportDir(data, run))

  /** Counts reads, so a refusal can be shown to precede any download. */
  private final class CountingStore(inner: ObjectStore) extends ObjectStore:
    var gets = 0
    var keys = Vector.empty[String]
    protected def write(key: String, bytes: Array[Byte], sourceRef: String) =
      throw new UnsupportedOperationException("read-only")
    def head(key: String): Option[ObjectMeta] = inner.head(key)
    def get(key: String): Option[Array[Byte]] =
      gets += 1
      keys :+= key
      inner.get(key)
    def list(prefix: String): Vector[ObjectMeta] = inner.list(prefix)

  private def pathQuery(source: String, target: String) =
    PathQuery(label, source, target, 6, 6, 2000L)

  // ---- the key layout ---------------------------------------------------

  test("the export key layout is the one the loader downloads") {
    assertEquals(
      prefix,
      s"semantic-context/runs/${AwsReadModels.slugOf(runId)}/export/"
    )
    assert(!prefix.contains(":"), prefix)
    assertEquals(
      code(AwsReadModels.exportPrefix("run-1")),
      ErrorCode.InvalidQuery
    )
    assertEquals(
      code(AwsReadModels.exportPrefix("run:v1:../../x")),
      ErrorCode.InvalidQuery
    )
  }

  // ---- the pipeline slug differs from the Spark run ID ---------------------

  private val slug = "run-v1-192de0fd-1111-4222-8333-444455556666"

  test("the export prefix is built from the pipeline slug, not the run ID") {
    assertEquals(
      AwsReadModels.exportPrefixOf(slug),
      s"semantic-context/runs/$slug/export/"
    )
    val store =
      upload(exportDir(), slugPrefix = AwsReadModels.exportPrefixOf(slug))
    val loaded =
      AwsReadModels.fromExport(store, runId, exportRunId = Some(slug))
    assertEquals(
      loaded.summary.exportPrefix,
      AwsReadModels.exportPrefixOf(slug)
    )
    assertEquals(loaded.summary.runId, runId)
    assertEquals(loaded.metrics.size, 3)
    // The run ID's own prefix holds nothing, so it is not found.
    assertEquals(
      code(AwsReadModels.fromExport(store, runId)),
      ErrorCode.MissingSuccessMarker
    )
  }

  test("a manifest whose sourceRunId is not the graph run ID is refused") {
    val store =
      upload(
        exportDir(run = otherRun),
        slugPrefix = AwsReadModels.exportPrefixOf(slug)
      )
    val text =
      message(AwsReadModels.fromExport(store, runId, exportRunId = Some(slug)))
    assert(text.contains(otherRun) && text.contains(runId), text)
    assertEquals(
      code(AwsReadModels.fromExport(store, runId, exportRunId = Some(slug))),
      ErrorCode.MalformedManifest
    )
  }

  test("an invalid export run ID is refused before any read") {
    val counting = new CountingStore(uploaded())
    Vector(
      "",
      runId,
      "run-v1-",
      "run-v1-../../x",
      "run-v1-192DE0FD-1111-4222-8333-444455556666",
      slug + "0"
    ).foreach { bad =>
      assertEquals(
        code(
          AwsReadModels.fromExport(counting, runId, exportRunId = Some(bad))
        ),
        ErrorCode.InvalidQuery,
        bad
      )
      assertEquals(
        code(AwsReadModels.exportPrefixOf(bad)),
        ErrorCode.InvalidQuery,
        bad
      )
    }
    assertEquals(counting.gets, 0)
    assertEquals(
      code(
        AwsReadModels.live(
          AwsReadModelSettings(
            "us-east-2",
            "a-bucket",
            "graph.example.internal",
            runId,
            exportRunId = "run-v1-nope"
          )
        )
      ),
      ErrorCode.InvalidQuery
    )
  }

  // ---- same read models as the existing in-memory path ------------------

  test("the same export gives the same read models as the in-memory path") {
    val dir = exportDir()
    val direct = JsonlExportSource.open(dir)
    val bounds = LoadBounds.Default
    val loaded = AwsReadModels.fromExport(upload(dir), runId)

    val components = InMemoryComponents.from(direct, bounds)
    assertEquals(
      loaded.components.component(label, "component-1"),
      components.component(label, "component-1")
    )
    assertEquals(
      loaded.components.componentOf(label, r2),
      components.componentOf(label, r2)
    )
    assertEquals(
      loaded.components.members(label, "component-1", None, 10),
      components.members(label, "component-1", None, 10)
    )
    val communities = InMemoryCommunities.from(direct, bounds)
    assertEquals(
      loaded.communities.community(label, "community-1"),
      communities.community(label, "community-1")
    )
    assertEquals(
      loaded.communities.neighbors(label, "community-1", 5),
      communities.neighbors(label, "community-1", 5)
    )
    assertEquals(
      loaded.communities.members(label, "community-1", None, 10),
      communities.members(label, "community-1", None, 10)
    )
    val directory = InMemoryResearcherDirectory.from(direct, bounds)
    assertEquals(loaded.directory.byId(r1), directory.byId(r1))
    assertEquals(loaded.directory.byId(m1), directory.byId(m1))
    assertEquals(
      loaded.directory.candidates("Ada Quill", 10),
      directory.candidates("Ada Quill", 10)
    )
    val paths = InMemoryPathEvidence.from(direct, bounds)
    Vector((r1, r2), (r2, r1), (r1, m1), (r2, m1)).foreach { (a, b) =>
      assertEquals(
        loaded.paths.shortestPath(pathQuery(a, b)),
        paths.shortestPath(pathQuery(a, b)),
        s"$a to $b"
      )
    }
    assertEquals(loaded.workRows, direct.rows("nodes-work"))
  }

  test("the loaded models answer: a path, a candidate, a component, metrics") {
    val loaded = AwsReadModels.fromExport(uploaded(), runId)
    loaded.paths.shortestPath(pathQuery(r1, r2)) match
      case PathLookup.Found(_, source, target, hops) =>
        assertEquals((source, target), (r1, r2))
        assertEquals(hops.map(_.edgeId), Vector(edgeId(r1, r2)))
        assertEquals(hops.head.supportingWorkIds, Vector(w1, w2))
      case other => fail(s"expected a path, got $other")
    assert(loaded.paths.shortestPath(pathQuery(r1, m1)).noPathExists)
    assertEquals(
      loaded.directory.candidates("bo chen", 5).candidates.map(_.nodeId),
      Vector(r2)
    )
    assertEquals(loaded.components.componentOf(label, r1).map(_.size), Some(2L))
    assertEquals(
      loaded.communities.communityOf(label, r2).map(_.communityId),
      Some("community-1")
    )
    val cached = ResearcherMetricCache
      .get(loaded.metricCache, r1, YearWindow(2000, 2009), Some(runId))
      .getOrElse(fail("no cached metrics"))
    assertEquals(cached.metrics.publicationCount, 2L)
    assertEquals(
      loaded.metrics.map(m => (m.metrics.researcherId, m.metrics.window)).toSet,
      Set(
        (r1, YearWindow(2000, 2009)),
        (r2, YearWindow(2000, 2009)),
        (r1, YearWindow(2010, 2019))
      )
    )
    assertEquals(loaded.workRows.size, 2)
  }

  // ---- provenance --------------------------------------------------------

  test("every loaded record keeps its provenance and source IDs") {
    val dir = exportDir()
    val loaded = AwsReadModels.fromExport(upload(dir), runId)
    val hopProvenance = loaded.paths.shortestPath(pathQuery(r1, r2)) match
      case PathLookup.Found(_, _, _, hops) => hops.head.provenance
      case other => fail(s"expected a path, got $other")
    assertEquals(hopProvenance.runId, runId)
    assertEquals(
      hopProvenance.sourceUri,
      "scholarchain:spark-analytics/path-evidence"
    )
    assertEquals(hopProvenance.sourceRecordId, s"$label|$r1|$r2|1")
    assertEquals(hopProvenance.transformVersion, "spark-analytics/1.1.0")
    assertEquals(
      loaded.directory.byId(r1).map(_.provenance.sourceRecordId),
      Some(r1)
    )
    assertEquals(
      loaded.components
        .component(label, "component-1")
        .map(_.provenance.sourceRecordId),
      Some(s"$label|component-1")
    )
    assertEquals(
      loaded.communities
        .community(label, "community-1")
        .map(_.provenance.sourceUri),
      Some("scholarchain:spark-analytics/communities")
    )
    loaded.metrics.foreach { m =>
      assertEquals(m.runId, runId)
      assertEquals(m.metrics.provenance.runId, runId)
      assertEquals(m.sparkTransformVersion, "spark-analytics/1.1.0")
      assertEquals(
        m.storageTransformVersion,
        Some(StorageProvenance.TransformVersion)
      )
      assertEquals(m.sourceRef, Some(s"spark-run:$runId"))
      assertEquals(
        m.metrics.provenance.sourceRecordId,
        s"${m.metrics.researcherId}|${m.metrics.window.startYear}-${m.metrics.window.endYear}"
      )
    }
    loaded.workRows.foreach { work =>
      val p = Rows.provenance(work)
      assertEquals(p.runId, runId)
      assertEquals(p.sourceRecordId, Rows.str(work, "canonicalWorkId"))
    }
    val manifest =
      Files.readAllBytes(dir.resolve(JsonlExportSource.ManifestName))
    assertEquals(loaded.summary.manifestSha256, Hashing.sha256(manifest))
    assertEquals(loaded.summary.runId, runId)
    assertEquals(
      loaded.summary.transformVersion,
      ExportFixtures.transformVersion
    )
    assertEquals(loaded.summary.exportVersion, "spark-jsonl-export/1.0.0")
    assertEquals(loaded.summary.exportPrefix, prefix)
    assertEquals(loaded.summary.tables.map(_.table).toSet, tables().keySet)
  }

  // ---- missing, partial and wrong exports --------------------------------

  test("a missing export fails clearly and names the prefix") {
    val text = message(AwsReadModels.fromExport(new InMemoryObjectStore, runId))
    assert(text.contains("MissingSuccessMarker"), text)
    assert(text.contains(prefix), text)
  }

  test("an export without _SUCCESS is refused") {
    val store = upload(exportDir(), skip = Set("_SUCCESS"))
    assertEquals(
      code(AwsReadModels.fromExport(store, runId)),
      ErrorCode.MissingSuccessMarker
    )
  }

  test("an export of another run under this run's prefix is refused") {
    val store = upload(exportDir(run = otherRun))
    val text = message(AwsReadModels.fromExport(store, runId))
    assert(text.contains(otherRun) && text.contains(runId), text)
    assertEquals(
      code(AwsReadModels.fromExport(store, runId)),
      ErrorCode.MalformedManifest
    )
  }

  test("an export with another run's objects only is not found for this run") {
    val store = upload(exportDir(run = otherRun), forRun = otherRun)
    assertEquals(
      code(AwsReadModels.fromExport(store, runId)),
      ErrorCode.MissingSuccessMarker
    )
  }

  test("a missing required table is named") {
    val store = uploaded(tables() - "path-evidence" - "components")
    val text = message(AwsReadModels.fromExport(store, runId))
    assert(text.contains("path-evidence") && text.contains("components"), text)
    assertEquals(
      code(AwsReadModels.fromExport(store, runId)),
      ErrorCode.MalformedManifest
    )
  }

  test("a file changed after the manifest was written fails its checksum") {
    val dir = exportDir()
    val part = dir.resolve("nodes-work/part-00000.jsonl")
    Files.write(part, Files.readAllBytes(part).reverse)
    assertEquals(
      code(AwsReadModels.fromExport(upload(dir), runId)),
      ErrorCode.ChecksumMismatch
    )
  }

  test("a metrics row that names another run is refused") {
    val p = provenance("researcher-metrics", s"$r1|$label")
    p("runId") = otherRun
    val bad = metricsRow(r1, label, 2000, 2009) + ("provenance" -> p)
    val store = uploaded(tables() + ("researcher-metrics" -> Vector(bad)))
    val text = message(AwsReadModels.fromExport(store, runId))
    assert(text.contains(otherRun), text)
    assertEquals(
      code(AwsReadModels.fromExport(store, runId)),
      ErrorCode.MalformedRow
    )
  }

  test(
    "works without a title are left out and named, a repeated work is refused"
  ) {
    val untitled = workNode(w3, "x", 2007) + ("title" -> ujson.Null)
    val store = uploaded(
      tables() + ("nodes-work" -> Vector(
        workNode(w1, "First paper", 2001),
        untitled
      ))
    )
    val loaded = AwsReadModels.fromExport(store, runId)
    assertEquals(
      loaded.workRows.map(Rows.str(_, "canonicalWorkId")),
      Vector(w1)
    )
    assertEquals(loaded.summary.worksWithoutTitle, Vector(w3))
    val twice = uploaded(
      tables() + ("nodes-work" -> Vector(
        workNode(w1, "First paper", 2001),
        workNode(w1, "First paper again", 2001)
      ))
    )
    assertEquals(
      code(AwsReadModels.fromExport(twice, runId)),
      ErrorCode.DuplicateId
    )
  }

  // ---- bounds --------------------------------------------------------------

  test("too many objects is refused before any object is read") {
    val counting = new CountingStore(uploaded())
    val limits = ExportLimits.Default.copy(maxObjects = 3)
    assertEquals(
      code(AwsReadModels.fromExport(counting, runId, limits)),
      ErrorCode.BoundExceeded
    )
    assertEquals(counting.gets, 0)
  }

  test("too many bytes is refused before any object is read") {
    val counting = new CountingStore(uploaded())
    val limits = ExportLimits.Default.copy(maxBytes = 100L)
    assertEquals(
      code(AwsReadModels.fromExport(counting, runId, limits)),
      ErrorCode.BoundExceeded
    )
    assertEquals(counting.gets, 0)
  }

  test("too many rows in one table or in all tables is refused") {
    val store = uploaded()
    assertEquals(
      code(
        AwsReadModels.fromExport(
          store,
          runId,
          ExportLimits.Default.copy(maxRowsPerTable = 2)
        )
      ),
      ErrorCode.BoundExceeded
    )
    val total = message(
      AwsReadModels.fromExport(
        store,
        runId,
        ExportLimits.Default.copy(maxTotalRows = 5)
      )
    )
    assert(
      total.contains("BoundExceeded") && total.contains("bound is 5"),
      total
    )
  }

  test("the default limit holds tier S with room to spare and is stated") {
    val limits = ExportLimits.Default
    assertEquals(limits.maxObjects, 1000)
    assertEquals(limits.maxBytes, 128L * 1024 * 1024)
    assertEquals(limits.maxRowsPerTable, 250000L)
    assertEquals(limits.maxTotalRows, 1000000L)
    assertEquals(code(ExportLimits(0, 1, 1, 1)), ErrorCode.BoundExceeded)
  }

  test("a loaded export under the limits reports what it read") {
    val loaded = AwsReadModels.fromExport(uploaded(), runId)
    assertEquals(
      loaded.summary.tables.map(_.rows).sum,
      tables().values.map(_.size.toLong).sum
    )
    assert(loaded.summary.objects > tables().size)
    assert(loaded.summary.bytes > 0L)
  }

  // ---- tables the read models do not use -----------------------------------

  private def unused(rows: Int = 1): Map[String, Vector[Row]] =
    Vector("edge-support", "edges-coauthored", "semantic-summaries").map {
      table =>
        table -> Vector.tabulate(rows)(i =>
          row(
            table,
            s"$table-$i",
            "edgeId" -> s"$table-$i",
            "filler" -> ("x" * 400)
          )
        )
    }.toMap

  test("tables the read models do not use are never downloaded or verified") {
    val dir = exportDir(tables() ++ unused())
    // An unused table that no longer matches its manifest would fail a load
    // that verified it; here it is not touched.
    Files.write(
      dir.resolve("edge-support/part-00000.jsonl"),
      "not json".getBytes
    )
    val counting = new CountingStore(upload(dir))
    val loaded = AwsReadModels.fromExport(counting, runId)
    val readTables =
      counting.keys.map(_.stripPrefix(prefix).takeWhile(_ != '/'))
    assertEquals(
      readTables.toSet,
      tables().keySet + "_SUCCESS" + JsonlExportSource.ManifestName
    )
    assertEquals(
      loaded.summary.skippedTables,
      Vector("edge-support", "edges-coauthored", "semantic-summaries")
    )
    assert(loaded.summary.skippedBytes > 0L)
    assertEquals(loaded.summary.tables.map(_.table).toSet, tables().keySet)
    assertEquals(loaded.workRows.size, 2)
    assertEquals(loaded.metrics.size, 3)
  }

  test("a loaded export keeps its manifest digest when tables are skipped") {
    val dir = exportDir(tables() ++ unused())
    val loaded = AwsReadModels.fromExport(upload(dir), runId)
    val manifest =
      Files.readAllBytes(dir.resolve(JsonlExportSource.ManifestName))
    assertEquals(loaded.summary.manifestSha256, Hashing.sha256(manifest))
    assertEquals(loaded.summary.runId, runId)
    assertEquals(
      loaded.directory.byId(r1).map(_.provenance.sourceRecordId),
      Some(r1)
    )
  }

  test("the byte bound counts the loaded tables only, from the listing") {
    val store = uploaded(tables() ++ unused(rows = 300))
    val needed = AwsReadModels.fromExport(store, runId).summary.bytes
    val whole = store.list(prefix).map(_.bytes).sum
    assert(whole > needed * 2, s"$whole vs $needed")
    assertEquals(
      AwsReadModels
        .fromExport(store, runId, ExportLimits.Default.copy(maxBytes = needed))
        .summary
        .bytes,
      needed
    )
    val counting = new CountingStore(store)
    assertEquals(
      code(
        AwsReadModels.fromExport(
          counting,
          runId,
          ExportLimits.Default.copy(maxBytes = needed - 1)
        )
      ),
      ErrorCode.BoundExceeded
    )
    assertEquals(counting.gets, 0)
  }

  test(
    "a missing required table is refused from the listing, reading nothing"
  ) {
    val counting = new CountingStore(uploaded(tables() - "components"))
    val text = message(AwsReadModels.fromExport(counting, runId))
    assert(text.contains("components"), text)
    assertEquals(counting.gets, 0)
  }

  test("open with a table list ignores the other tables, and rows stream") {
    val dir = exportDir(tables() ++ unused())
    Files.delete(dir.resolve("edge-support/part-00000.jsonl"))
    assertEquals(
      code(JsonlExportSource.open(dir)),
      ErrorCode.MalformedManifest
    )
    val source =
      JsonlExportSource.open(dir, LoadBounds.Default, Some(tables().keySet))
    assertEquals(source.tableNames, tables().keySet)
    val seen = Vector.newBuilder[Row]
    source.foreachRow("nodes-work")(seen += _)
    assertEquals(seen.result(), source.rows("nodes-work"))
    assertEquals(
      code(source.rows("edge-support")),
      ErrorCode.MalformedManifest
    )
  }

  // ---- the temporary copy --------------------------------------------------

  test("the temporary copy is deleted after a load and after a failure") {
    val root = Files.createTempDirectory("export-temp-root-")
    AwsReadModels.fromExport(uploaded(), runId, tempRoot = Some(root))
    assertEquals(Files.list(root).count(), 0L)
    val broken = upload(exportDir(tables() - "path-evidence"))
    intercept[StorageException](
      AwsReadModels.fromExport(broken, runId, tempRoot = Some(root))
    )
    assertEquals(Files.list(root).count(), 0L)
  }

  // ---- the wiring ----------------------------------------------------------

  test("assemble joins the export models with a query port and a closer") {
    val loaded = AwsReadModels.fromExport(uploaded(), runId)
    val neptune = new NeptuneQueryPort:
      def run(query: CypherQuery): Vector[Row] = Vector.empty
    var closed = 0
    val models = AwsReadModels.assemble(loaded, neptune, () => closed += 1)
    assert(models.neptune eq neptune)
    assert(models.paths eq loaded.paths)
    assert(models.metricCache eq loaded.metricCache)
    assertEquals(models.metrics, loaded.metrics)
    assertEquals(models.workRows, loaded.workRows)
    models.close()
    assertEquals(closed, 1)
  }

  test("live refuses a bad run ID or host before any AWS call") {
    def settings(host: String, run: String) =
      AwsReadModelSettings(
        "us-east-2",
        "a-bucket",
        host,
        run,
        exportRunId = AwsReadModels.slugOf(runId)
      )
    assertEquals(
      code(AwsReadModels.live(settings("graph.example.internal", "run-1"))),
      ErrorCode.InvalidQuery
    )
    assertEquals(
      code(AwsReadModels.live(settings("not a host", runId))),
      ErrorCode.InvalidQuery
    )
  }
