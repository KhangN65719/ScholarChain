package scholarchain.storage

import java.nio.file.Files

class S3Suite extends munit.FunSuite:
  private def tempDir() = Files.createTempDirectory("storage-s3-")

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private val bounds = LoadBounds.Default

  private def stores: Vector[(String, () => ObjectStore)] = Vector(
    ("in-memory", () => new InMemoryObjectStore),
    ("local-fs", () => new LocalFsObjectStore(tempDir()))
  )

  stores.foreach { case (label, make) =>
    test(s"$label: loader refuses a run directory without _SUCCESS") {
      val dir = Fixtures.writeRunDir(tempDir(), success = false)
      assertEquals(
        code(RunDirectory.open(dir)),
        ErrorCode.MissingSuccessMarker
      )
    }

    test(s"$label: load twice leaves the same state (idempotent)") {
      val store = make()
      val run = RunDirectory.open(
        Fixtures.writeRunDir(tempDir(), success = true)
      )
      val first = S3RunLoader.load(run, store, "v1", bounds)
      val before = store.list("")
      val second = S3RunLoader.load(run, store, "v1", bounds)
      assertEquals(store.list(""), before)
      assertEquals(second.created, 0)
      assertEquals(second.unchanged, first.created)
      assertEquals(second.manifestKey, first.manifestKey)
    }

    test(s"$label: manifest round-trips with hashes, source refs, provenance") {
      val store = make()
      val run = RunDirectory.open(
        Fixtures.writeRunDir(tempDir(), success = true)
      )
      val report = S3RunLoader.load(run, store, "v1", bounds)
      val manifest = S3RunManifest.read(store, report.manifestKey)
      assertEquals(manifest.runId, Fixtures.runId)
      assert(manifest.objects.nonEmpty)
      manifest.objects.foreach { entry =>
        val stored = store.get(entry.key).get
        assertEquals(Hashing.sha256(stored), entry.sha256)
        assertEquals(entry.bytes, stored.length.toLong)
        assert(entry.sourceRef.startsWith("spark-run:"))
      }
      assertEquals(manifest.provenance.transformVersion, "storage/1.0.0")
      assertEquals(manifest.provenance.runId, Fixtures.runId)
      val graphKey = manifest.objects.find(_.key.startsWith("graph/")).get.key
      assert(graphKey.contains("/v=v1/run=run-v1-"))
    }
  }

  test("a changed source file fails the SHA-256 check before any write") {
    val store = new InMemoryObjectStore
    val dir = Fixtures.writeRunDir(tempDir(), success = true)
    Files.writeString(
      dir.resolve("tables/nodes-work/part-00000.parquet"),
      "tampered"
    )
    val run = RunDirectory.open(dir)
    assertEquals(
      code(S3RunLoader.load(run, store, "v1", bounds)),
      ErrorCode.ChecksumMismatch
    )
    assertEquals(store.list(""), Vector.empty)
  }

  test("a versioned key is immutable: different bytes are refused") {
    val store = new InMemoryObjectStore
    val key = ObjectKeys.versioned(
      S3Prefix.Curated,
      "t",
      "v1",
      Fixtures.runId,
      "a.bin"
    )
    assert(store.putImmutable(key, Array[Byte](1), "ref").created)
    assert(!store.putImmutable(key, Array[Byte](1), "ref").created)
    assertEquals(
      code(store.putImmutable(key, Array[Byte](2), "ref")),
      ErrorCode.ImmutableKeyConflict
    )
  }

  test("keys are validated: traversal, empty segments and NUL are refused") {
    Vector("../x", "a//b", "/abs", "a/\u0000b", "").foreach { key =>
      assertEquals(code(ObjectKeys.requireValid(key)), ErrorCode.InvalidKey)
    }
    val local = new LocalFsObjectStore(tempDir())
    assertEquals(code(local.get("a/../../x")), ErrorCode.InvalidKey)
  }

  test("every project prefix has a place and a table maps to one") {
    assertEquals(
      S3Prefix.values.map(_.dir).toVector,
      Vector(
        "raw",
        "curated",
        "graph",
        "semantic-context",
        "reports",
        "logs"
      )
    )
    assertEquals(S3RunLoader.prefixFor("nodes-work"), S3Prefix.Graph)
    assertEquals(
      S3RunLoader.prefixFor("semantic-summaries"),
      S3Prefix.SemanticContext
    )
    assertEquals(S3RunLoader.prefixFor("researcher-metrics"), S3Prefix.Curated)
  }

  test("a load over the file bound fails before any write") {
    val store = new InMemoryObjectStore
    val run = RunDirectory.open(
      Fixtures.writeRunDir(tempDir(), success = true)
    )
    val tight = LoadBounds(10L, 0, 1L)
    assertEquals(
      code(S3RunLoader.load(run, store, "v1", tight)),
      ErrorCode.BoundExceeded
    )
    assertEquals(store.list(""), Vector.empty)
  }
