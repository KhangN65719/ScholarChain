package scholarchain.tests.adversarial

import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.core.{Provenance, ResearcherMetrics, StableIds}
import scholarchain.storage.*
import Phase7Storage.*

/** Run directories, the `_SUCCESS` gate, bounds, the S3 loader and the
  * cross-adapter agreement of IDs and provenance for one run.
  */
class Phase7RunLoadSuite extends FunSuite:
  private val names = TableNames("p7")

  private def files(
      extra: Vector[(String, String, Array[Byte])] = Vector.empty
  ) =
    Vector(
      ("nodes-work", "part-0.json", utf8("{\"a\":1}\n")),
      ("nodes-work", "part-1.json", utf8("{\"a\":2}\n")),
      ("edges-coauthored", "part-0.json", utf8("{\"b\":1}\n")),
      ("semantic-summaries", "part-0.json", utf8("{\"c\":1}\n")),
      ("researcher-metrics", "part-0.json", utf8("{\"d\":1}\n"))
    ) ++ extra

  private def snapshot(store: ObjectStore) =
    store.list("").map(m => (m.key, m.sha256, m.sourceRef))

  // 7. _SUCCESS, partial and empty runs --------------------------------------

  test("a run without _SUCCESS is refused and nothing is read or written"):
    val dir = writeRun(tempRoot(), runId, files(), success = false)
    assertEquals(code(RunDirectory.open(dir)), ErrorCode.MissingSuccessMarker)

  test("_SUCCESS must be a file; an empty or absent directory is refused"):
    val root = tempRoot()
    val dir = writeRun(root, runId, files(), success = false)
    Files.createDirectory(dir.resolve("_SUCCESS"))
    assertEquals(code(RunDirectory.open(dir)), ErrorCode.MissingSuccessMarker)
    val empty = Files.createDirectory(tempRoot().resolve("empty"))
    assertEquals(code(RunDirectory.open(empty)), ErrorCode.MissingSuccessMarker)
    assertEquals(
      code(RunDirectory.open(tempRoot().resolve("nope"))),
      ErrorCode.MissingSuccessMarker
    )
    // a regular file in place of the directory
    val plain = Files.createFile(tempRoot().resolve("afile"))
    assertEquals(code(RunDirectory.open(plain)), ErrorCode.MissingSuccessMarker)

  test(
    "_SUCCESS without a manifest, or with a manifest lacking a run ID, is refused"
  ):
    val none = writeRun(tempRoot(), runId, files(), manifest = false)
    assertEquals(code(RunDirectory.open(none)), ErrorCode.MalformedManifest)
    Vector("", "not-a-run", "run:v2:x", "mention:v1:abc").foreach { bad =>
      val dir = writeRun(tempRoot(), bad, files())
      assertEquals(
        code(RunDirectory.open(dir)),
        ErrorCode.MalformedManifest,
        bad
      )
    }
    val noKey = writeRun(tempRoot(), runId, files())
    Files.write(
      noKey.resolve("load-manifests/s3.json"),
      utf8("{\"tables\":[]}")
    )
    assertEquals(code(RunDirectory.open(noKey)), ErrorCode.MalformedManifest)

  test("a manifest that is not JSON fails; it is not treated as empty"):
    val dir = writeRun(tempRoot(), runId, files())
    Files.write(dir.resolve("load-manifests/s3.json"), utf8("{ not json"))
    intercept[Exception](RunDirectory.open(dir))

  test("a run with an empty file list loads to just the manifest"):
    val dir = writeRun(tempRoot(), runId, Vector.empty)
    val run = RunDirectory.open(dir)
    val store = new InMemoryObjectStore
    val report = S3RunLoader.load(run, store, "v1", Bounds)
    assertEquals(report.created, 1)
    assertEquals(
      S3RunManifest.read(store, report.manifestKey).objects,
      Vector.empty
    )

  test("a listed file missing on disk writes nothing"):
    val dir = writeRun(tempRoot(), runId, files())
    Files.delete(dir.resolve("tables/nodes-work/part-1.json"))
    val store = new InMemoryObjectStore
    intercept[Exception](
      S3RunLoader.load(RunDirectory.open(dir), store, "v1", Bounds)
    )
    assertEquals(store.list(""), Vector.empty)

  test("a corrupted file fails its checksum and no earlier file is written"):
    val bad = "tables/semantic-summaries/part-0.json"
    val dir = writeRun(
      tempRoot(),
      runId,
      files(),
      tamper = Map(bad -> utf8("{\"c\":2}\n"))
    )
    val store = new InMemoryObjectStore
    assertEquals(
      code(S3RunLoader.load(RunDirectory.open(dir), store, "v1", Bounds)),
      ErrorCode.ChecksumMismatch
    )
    assertEquals(store.list(""), Vector.empty)

  test("a truncated file is a checksum failure, an empty file too"):
    Vector(Array.emptyByteArray, utf8("{\"a\"")).foreach { cut =>
      val dir = writeRun(
        tempRoot(),
        runId,
        files(),
        tamper = Map("tables/nodes-work/part-0.json" -> cut)
      )
      val store = new InMemoryObjectStore
      assertEquals(
        code(S3RunLoader.load(RunDirectory.open(dir), store, "v1", Bounds)),
        ErrorCode.ChecksumMismatch
      )
      assertEquals(store.list(""), Vector.empty)
    }

  test("exceeding the file or byte bound writes nothing"):
    val dir = writeRun(tempRoot(), runId, files())
    val run = RunDirectory.open(dir)
    val store = new InMemoryObjectStore
    assertEquals(
      code(S3RunLoader.load(run, store, "v1", LoadBounds(10, 4, 1L << 30))),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(S3RunLoader.load(run, store, "v1", LoadBounds(10, 100, 10))),
      ErrorCode.BoundExceeded
    )
    assertEquals(store.list(""), Vector.empty)
    // exactly at the bound passes
    val total = files().map(_._3.length.toLong).sum
    S3RunLoader.load(run, store, "v1", LoadBounds(10, 5, total))
    assert(store.list("").nonEmpty)

  test(
    "the byte bound uses the real file size, not a size the manifest declares"
  ):
    val dir = writeRun(tempRoot(), runId, files(), declaredBytes = Some(1))
    val store = new InMemoryObjectStore
    val outcome =
      try
        Right(
          S3RunLoader.load(
            RunDirectory.open(dir),
            store,
            "v1",
            LoadBounds(10, 100, 10)
          )
        )
      catch case e: StorageException => Left(e.code)
    assertEquals(
      outcome.left.toOption,
      Some(ErrorCode.BoundExceeded),
      s"wrote ${store.list("").size} objects"
    )
    assertEquals(store.list(""), Vector.empty)

  test("a manifest path cannot leave the run directory"):
    val root = tempRoot()
    val dir = writeRun(root, runId, files())
    Files.write(root.resolve("outside.json"), utf8("{}"))
    Vector("../outside.json", "tables/../../outside.json", "/etc/hosts")
      .foreach { escape =>
        val manifest = ujson.Obj(
          "runId" -> runId,
          "tables" -> ujson.Arr(
            ujson.Obj(
              "table" -> "nodes-work",
              "files" -> ujson.Arr(
                ujson.Obj(
                  "path" -> escape,
                  "bytes" -> 2.0,
                  "sha256" -> Hashing.sha256("{}")
                )
              )
            )
          )
        )
        Files.write(
          dir.resolve("load-manifests/s3.json"),
          utf8(ujson.write(manifest))
        )
        val store = new InMemoryObjectStore
        assertEquals(
          code(S3RunLoader.load(RunDirectory.open(dir), store, "v1", Bounds)),
          ErrorCode.InvalidKey,
          escape
        )
        assertEquals(store.list(""), Vector.empty)
      }

  // 3. Idempotent S3 loads ---------------------------------------------------

  test(
    "object keys: empty, absolute, traversal, odd characters and long keys are refused"
  ):
    Vector(
      "",
      "/abs",
      "a//b",
      "a/../b",
      "a/b/",
      "a b",
      "a\nb",
      "a\u0000b",
      "café/x",
      "a\\b",
      "a?b",
      "a*b",
      "k" * 1025
    ).foreach { bad =>
      val accepted =
        try
          ObjectKeys.requireValid(bad)
          true
        catch case e: StorageException => e.code != ErrorCode.InvalidKey
      assert(!accepted, s"accepted key: '${bad.take(12)}'")
    }
    ObjectKeys.requireValid("k" * 1024)
    ObjectKeys.requireValid("graph/neptune/v=v1/run=run-v1-x/a_b.c-d=e:f#g")

  test("loading a run twice, and interleaved with another run, is stable"):
    val a = RunDirectory.open(writeRun(tempRoot(), runId, files()))
    val b = RunDirectory.open(
      writeRun(
        tempRoot(),
        otherRun,
        files(Vector(("nodes-venue", "part-0.json", utf8("{}\n"))))
      )
    )
    val store = new InMemoryObjectStore
    val first = S3RunLoader.load(a, store, "v1", Bounds)
    assertEquals(first.unchanged, 0)
    val afterA = snapshot(store)
    val second = S3RunLoader.load(b, store, "v1", Bounds)
    assertEquals(second.unchanged, 0)
    val afterB = snapshot(store)
    assertEquals(afterB.size, afterA.size + 7)
    val third = S3RunLoader.load(a, store, "v1", Bounds)
    assertEquals(third.created, 0)
    assertEquals(third.unchanged, first.created)
    val fourth = S3RunLoader.load(b, store, "v1", Bounds)
    assertEquals(fourth.created, 0)
    assertEquals(snapshot(store), afterB)
    assertEquals(third.manifestKey, first.manifestKey)

  test(
    "a changed file under the same run ID is refused and the original stays"
  ):
    val one = RunDirectory.open(writeRun(tempRoot(), runId, files()))
    val store = new InMemoryObjectStore
    S3RunLoader.load(one, store, "v1", Bounds)
    val before = snapshot(store)
    val changed =
      files().updated(0, ("nodes-work", "part-0.json", utf8("{\"a\":999}\n")))
    val two = RunDirectory.open(writeRun(tempRoot(), runId, changed))
    assertEquals(
      code(S3RunLoader.load(two, store, "v1", Bounds)),
      ErrorCode.ImmutableKeyConflict
    )
    assertEquals(snapshot(store), before)

  test("the same run under a new version prefix is a separate immutable copy"):
    val run = RunDirectory.open(writeRun(tempRoot(), runId, files()))
    val store = new InMemoryObjectStore
    val v1 = S3RunLoader.load(run, store, "v1", Bounds)
    val v2 = S3RunLoader.load(run, store, "v2", Bounds)
    assertNotEquals(v1.manifestKey, v2.manifestKey)
    assertEquals(v2.unchanged, 0)

  test("prefix routing and the run segment in every key"):
    val run = RunDirectory.open(
      writeRun(
        tempRoot(),
        runId,
        files(),
        side = Map(
          "run-manifest.json" -> utf8("{}"),
          "summary.txt" -> utf8("s"),
          "stage-metrics.json" -> utf8("{}")
        )
      )
    )
    val store = new InMemoryObjectStore
    val report = S3RunLoader.load(run, store, "v1", Bounds)
    val seg = "run=" + runId.replace(':', '-')
    val keys = store.list("").map(_.key)
    keys.foreach(k => assert(k.contains(seg), k))
    def prefixOf(part: String) =
      keys.find(_.contains(s"/$part/")).get.takeWhile(_ != '/')
    assertEquals(prefixOf("nodes-work"), "graph")
    assertEquals(prefixOf("edges-coauthored"), "graph")
    assertEquals(prefixOf("semantic-summaries"), "semantic-context")
    assertEquals(prefixOf("researcher-metrics"), "curated")
    assert(
      keys.exists(k =>
        k.startsWith("reports/spark-run/") && k.endsWith("run-manifest.json")
      )
    )
    assert(
      keys.exists(k =>
        k.startsWith("logs/spark-run/") && k.endsWith("stage-metrics.json")
      )
    )
    assert(report.manifestKey.startsWith("reports/s3-run-manifest/"))

  test(
    "the local filesystem store honours the same immutability and key rules"
  ):
    val root = tempRoot().resolve("store")
    val store = new LocalFsObjectStore(root)
    val put =
      store.putImmutable("curated/x/v=v1/run=r/a.json", utf8("{}"), "ref-1")
    assert(put.created)
    assertEquals(
      store
        .putImmutable("curated/x/v=v1/run=r/a.json", utf8("{}"), "ref-2")
        .created,
      false
    )
    assertEquals(
      store.head("curated/x/v=v1/run=r/a.json").get.sourceRef,
      "ref-1"
    )
    assertEquals(
      code(
        store.putImmutable("curated/x/v=v1/run=r/a.json", utf8("{ }"), "ref-3")
      ),
      ErrorCode.ImmutableKeyConflict
    )
    assertEquals(
      new String(store.get("curated/x/v=v1/run=r/a.json").get, "UTF-8"),
      "{}"
    )
    assertEquals(
      code(store.putImmutable("../escape", utf8("x"), "r")),
      ErrorCode.InvalidKey
    )
    assertEquals(code(store.get("a/../../escape")), ErrorCode.InvalidKey)
    assertEquals(
      store.list("").map(_.key),
      Vector("curated/x/v=v1/run=r/a.json")
    )
    assert(!Files.exists(root.getParent.resolve("escape")))

  test("a full run loads the same way into the local filesystem store"):
    val run = RunDirectory.open(writeRun(tempRoot(), runId, files()))
    val local = new LocalFsObjectStore(tempRoot().resolve("store"))
    val memory = new InMemoryObjectStore
    val a = S3RunLoader.load(run, local, "v1", Bounds)
    val b = S3RunLoader.load(run, memory, "v1", Bounds)
    assertEquals(snapshot(local), snapshot(memory))
    assertEquals(S3RunLoader.load(run, local, "v1", Bounds).created, 0)
    assertEquals(a.bytes, b.bytes)

  // 9 and 1. Provenance and ID agreement across adapters ---------------------

  test("the S3 manifest carries run, transform and source provenance"):
    val run = RunDirectory.open(writeRun(tempRoot(), runId, files()))
    val store = new InMemoryObjectStore
    val report = S3RunLoader.load(run, store, "v1", Bounds)
    val m = S3RunManifest.read(store, report.manifestKey)
    assertEquals(m.runId, runId)
    assertEquals(m.provenance.runId, runId)
    assertEquals(
      m.provenance.transformVersion,
      StorageProvenance.TransformVersion
    )
    assertEquals(m.provenance.sourceRecordId, runId)
    assertEquals(m.provenance.snapshotId, s"spark-run:$runId")
    m.objects.foreach { o =>
      assertEquals(o.sourceRef, s"spark-run:$runId/${o.relativePath}")
      assertEquals(o.sha256, Hashing.sha256(store.get(o.key).get))
      assertEquals(o.bytes, store.get(o.key).get.length.toLong)
    }
    assertEquals(m.objects.map(_.key), m.objects.map(_.key).sorted)
    assertEquals(
      store.head(m.objects.head.key).get.sourceRef,
      m.objects.head.sourceRef
    )

  test(
    "one run: Neptune, DynamoDB and S3 agree on run segment, IDs and provenance"
  ):
    val p =
      provenance(snapshot = "snap-x", record = "rec/é", transform = "tx/2")
    val w = world(p)
    val store = new InMemoryObjectStore
    val projection = project(w)
    val neptuneReport = NeptuneExporter.exportTo(projection, runId, store)
    val tables = w.tables.updated(
      "researcher-metrics",
      Vector(metricsFor(r1, p), metricsFor(m1, p))
    )
    val dir = writeRun(
      tempRoot(),
      runId,
      tables.toVector.collect {
        case (t, rows) if rows.nonEmpty =>
          (
            t,
            "part-0.json",
            utf8(
              rows
                .map(r => ujson.write(ujson.Obj.from(r)))
                .mkString("\n") + "\n"
            )
          )
      }
    )
    val run = RunDirectory.open(dir)
    val s3 = S3RunLoader.load(run, store, "v1", Bounds)
    val s3Manifest = S3RunManifest.read(store, s3.manifestKey)
    val ledger = new InMemoryDynamoTable(RunLedger.spec(names))
    val registry = new InMemoryDynamoTable(ArtifactRegistry.spec(names))
    val cache = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    val report = DynamoRunLoader.load(
      run,
      s3Manifest,
      new InMemoryTableSource(tables),
      ledger,
      registry,
      cache,
      "2026-11-08T00:00:00Z",
      1900000000L,
      Bounds
    )
    assertEquals(report.ledgerCreated, 1)
    assertEquals(report.metricsCreated, 2)
    assertEquals(report.artifactsCreated, s3Manifest.objects.size)
    val seg = runId.replace(':', '-')
    // run segment is identical in all three
    neptuneReport.files.foreach(f => assert(f.key.contains(s"run=$seg"), f.key))
    s3Manifest.objects.foreach(o => assert(o.key.contains(s"run=$seg"), o.key))
    registry
      .query("ARTIFACT#graph", None, 100)
      .foreach(i => assert(i("sk").str.contains(seg)))
    // researcher and mention IDs are the same strings in Neptune and DynamoDB
    val vertexIds = projection.vertices.map(_.id).toSet
    val cacheItems =
      Vector(r1, m1).flatMap(id => cache.query(s"RESEARCHER#$id", None, 10))
    assertEquals(cacheItems.size, 2)
    cacheItems.foreach { item =>
      val metrics =
        upickle.default.read[ResearcherMetrics](item("metricsJson").str)
      assert(vertexIds.contains(metrics.researcherId), metrics.researcherId)
      assertEquals(item("pk").str, s"RESEARCHER#${metrics.researcherId}")
      assertEquals(metrics.provenance, p)
    }
    // the artifact registry points at keys that exist in the store with the same hash
    s3Manifest.objects.foreach { o =>
      val item = registry
        .query(s"ARTIFACT#${o.key.takeWhile(_ != '/')}", None, 100)
        .find(_("s3Key") == s(o.key))
        .get
      assertEquals(item("sha256").str, store.head(o.key).get.sha256)
      assertEquals(item("bytes"), n(o.bytes))
      assertEquals(item("runId").str, runId)
    }
    // ledger
    val meta = ledger.get(s"RUN#$runId", "META").get
    assertEquals(meta("status").str, "LOADED")
    // Spark's transform version is kept as Spark's; the storage version sits
    // beside it under its own attribute and the two never replace each other.
    assertEquals(meta("transformVersion").str, "tx/2")
    assertEquals(meta("sparkTransformVersion").str, "tx/2")
    assertEquals(
      meta("storageTransformVersion").str,
      StorageProvenance.TransformVersion
    )
    assertNotEquals(
      meta("transformVersion").str,
      StorageProvenance.TransformVersion
    )
    assertEquals(meta("kind").str, "spark-analytics")
    assertEquals(meta("sourceRef").str, s"spark-run:$runId")
    registry.query("ARTIFACT#graph", None, 100).foreach { item =>
      assertEquals(item("sparkTransformVersion").str, "tx/2")
      assertEquals(
        item("storageTransformVersion").str,
        StorageProvenance.TransformVersion
      )
    }
    cacheItems.foreach { item =>
      assertEquals(item("sparkTransformVersion").str, "tx/2")
      assertEquals(
        item("storageTransformVersion").str,
        StorageProvenance.TransformVersion
      )
    }
    // Neptune provenance props equal the row provenance
    val firstVertex = projection.vertices.find(_.id == r1).get
    assertEquals(firstVertex.props("snapshotId"), Prop.Str("snap-x"))
    assertEquals(firstVertex.props("sourceRecordId"), Prop.Str("rec/é"))
    assertEquals(firstVertex.props("transformVersion"), Prop.Str("tx/2"))
    assertEquals(firstVertex.props("runId"), Prop.Str(runId))
    // second pass over everything creates nothing
    val again = DynamoRunLoader.load(
      run,
      s3Manifest,
      new InMemoryTableSource(tables),
      ledger,
      registry,
      cache,
      "2026-11-08T00:00:00Z",
      1900000000L,
      Bounds
    )
    assertEquals(
      (again.ledgerCreated, again.artifactsCreated, again.metricsCreated),
      (0, 0, 0)
    )
    assertEquals(again.artifactsUnchanged, s3Manifest.objects.size)
    assertEquals(again.metricsUnchanged, 2)
    assertEquals(ledger.get(s"RUN#$runId", "META").get("status").str, "LOADED")
    assertEquals(NeptuneExporter.exportTo(projection, runId, store).created, 0)
    assertEquals(S3RunLoader.load(run, store, "v1", Bounds).created, 0)

  test(
    "a bound on researcher-metrics rows stops the DynamoDB load before any write"
  ):
    val p = provenance()
    val tables =
      Map("researcher-metrics" -> Vector(metricsFor(r1, p), metricsFor(r2, p)))
    val run = RunDirectory.open(writeRun(tempRoot(), runId, files()))
    val store = new InMemoryObjectStore
    val manifest = S3RunManifest.read(
      store,
      S3RunLoader.load(run, store, "v1", Bounds).manifestKey
    )
    val ledger = new InMemoryDynamoTable(RunLedger.spec(names))
    val registry = new InMemoryDynamoTable(ArtifactRegistry.spec(names))
    val cache = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    assertEquals(
      code(
        DynamoRunLoader.load(
          run,
          manifest,
          new InMemoryTableSource(tables),
          ledger,
          registry,
          cache,
          "t",
          1900000000L,
          LoadBounds(1, 10, 1000)
        )
      ),
      ErrorCode.BoundExceeded
    )
    assertEquals(ledger.get(s"RUN#$runId", "META"), None)
    assertEquals(registry.query("ARTIFACT#graph", None, 10), Vector.empty)

  test(
    "a malformed metrics row stops the DynamoDB load before the ledger is touched"
  ):
    val p = provenance()
    val bad = metricsFor(r1, p) - "componentId"
    val run = RunDirectory.open(writeRun(tempRoot(), runId, files()))
    val store = new InMemoryObjectStore
    val manifest = S3RunManifest.read(
      store,
      S3RunLoader.load(run, store, "v1", Bounds).manifestKey
    )
    val ledger = new InMemoryDynamoTable(RunLedger.spec(names))
    val outcome = code(
      DynamoRunLoader.load(
        run,
        manifest,
        new InMemoryTableSource(
          Map("researcher-metrics" -> Vector(metricsFor(r2, p), bad))
        ),
        ledger,
        new InMemoryDynamoTable(ArtifactRegistry.spec(names)),
        new InMemoryDynamoTable(ResearcherMetricCache.spec(names)),
        "t",
        1900000000L,
        Bounds
      )
    )
    assertEquals(outcome, ErrorCode.MalformedRow)
    assertEquals(ledger.get(s"RUN#$runId", "META"), None)

  private def metricsFor(id: String, p: Provenance): Row =
    Map(
      "researcherId" -> s(id),
      "windowStart" -> n(2019),
      "windowEnd" -> n(2023),
      "publicationCount" -> n(1),
      "distinctCoauthorCount" -> n(1),
      "totalCoauthorOccurrences" -> n(1),
      "averageCoauthorsPerPaper" -> ujson.Num(1.0),
      "maximumCoauthorsOnOnePaper" -> n(1),
      "maximumTeamWorkIds" -> arr(w1),
      "weightedDegree" -> ujson.Num(1.0),
      "componentId" -> s("component-1"),
      "componentSize" -> n(2),
      "provenance" -> provJson(p)
    )

  test(
    "core mention IDs used as keys in Neptune and DynamoDB are identical strings"
  ):
    val id = StableIds.mention(w2, 3)
    val projection = project(
      world().replace(
        "nodes-researcher",
        world().rows("nodes-researcher") :+ researcherRow(
          id,
          "Late Mention",
          provenance()
        )
      )
    )
    assert(
      projection.vertices.exists(v => v.id == id && v.label == "AuthorMention")
    )
    val cache = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    DynamoRunLoader.putMetrics(
      cache,
      metricsFor(id, provenance()),
      runId,
      1900000000L
    )
    assertEquals(cache.query(s"RESEARCHER#$id", None, 5).size, 1)
