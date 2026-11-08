package scholarchain.storage

import java.nio.file.Files
import scholarchain.core.StableIds

class RepairSuite extends munit.FunSuite:
  import Fixtures.*

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def pathRow(
      edge: Option[String],
      from: String,
      to: String,
      status: Option[String] = None,
      reason: Option[String] = None
  ): Row =
    Map(
      "pairKey" -> ujson.Str("p1"),
      "window" -> ujson.Str("2000-2009"),
      "sourceId" -> ujson.Str(r1),
      "targetId" -> ujson.Str(r2),
      "status" -> status.fold(ujson.Null: ujson.Value)(ujson.Str(_)),
      "unreachableReason" -> reason.fold(ujson.Null: ujson.Value)(ujson.Str(_)),
      "hopIndex" -> edge.fold(ujson.Null: ujson.Value)(_ => ujson.Num(1)),
      "fromId" -> ujson.Str(from),
      "toId" -> ujson.Str(to),
      "edgeId" -> edge.fold(ujson.Null: ujson.Value)(ujson.Str(_)),
      "supportingWorkIds" -> ujson.Arr(ujson.Str(w1)),
      "provenance" -> provenance("path-evidence", "p1")
    )

  private def read(rows: Vector[Row]) =
    PathEvidence.read(source(Map("path-evidence" -> rows)), LoadBounds.Default)

  test("missing or null provenance is MalformedRow, never an NPE") {
    val noProv = graphTables()("nodes-work").map(_ - "provenance")
    val nullProv =
      graphTables()("nodes-work").map(_ + ("provenance" -> ujson.Null))
    Vector(noProv, nullProv).foreach { rows =>
      val tables = graphTables().updated("nodes-work", rows)
      assertEquals(
        code(
          new SparkGraphProjector(source(tables), LoadBounds.Default).project()
        ),
        ErrorCode.MalformedRow
      )
    }
  }

  test("an inverted metrics window is MalformedRow") {
    val bad = metricsRow(r1, "x", 2009, 2000)
    val cache = new InMemoryDynamoTable(
      ResearcherMetricCache.spec(TableNames("t"))
    )
    assertEquals(
      code(DynamoRunLoader.putMetrics(cache, bad, runId, 1)),
      ErrorCode.MalformedRow
    )
  }

  test("a changed embedding or metadata under an existing key is refused") {
    val spec = VectorIndexSpec("researcher-profile-v1", 2, "cosine", None)
    val meta: Map[String, MetaValue] = Map(
      "summaryType" -> MetaValue.Str("researcher-profile"),
      "subjectId" -> MetaValue.Str(r1),
      "textRef" -> MetaValue.Str("ref"),
      "runId" -> MetaValue.Str(runId)
    )
    val bucket = new InMemoryVectorBucket
    val writer = new VectorWriter(bucket, RetryPolicy(1, 1, 1), NoSleep)
    val first = VectorRecord("k", Vector(1f, 2f), meta)
    assertEquals(writer.write(spec, Vector(first)).written, 1)
    assertEquals(writer.write(spec, Vector(first)).rejected, Vector.empty)
    val changed = first.copy(embedding = Vector(9f, 9f))
    val other = first.copy(metadata = meta + ("nodeKind" -> MetaValue.Str("x")))
    Vector(changed, other).foreach { record =>
      val report = writer.write(spec, Vector(record))
      assertEquals(report.written, 0)
      assertEquals(
        report.rejected.map(_.code),
        Vector(ErrorCode.ChangedVectorContent)
      )
    }
    assertEquals(bucket.getVectors(spec.name, Vector("k")), Vector(first))
    assertEquals(
      code(bucket.putVectors(spec.name, Vector(changed))),
      ErrorCode.ChangedVectorContent
    )
  }

  test("retry delay never overflows and stays within the cap") {
    assertEquals(RetryPolicy(100, 1L << 50, 1L << 55).delayAfter(14), 1L << 55)
    assertEquals(RetryPolicy(100, 100, 1000).delayAfter(1000), 1000L)
    assertEquals(RetryPolicy(100, 100, 1000).delayAfter(0), 100L)
    (1 to 200).foreach { n =>
      val d = RetryPolicy(300, Long.MaxValue, Long.MaxValue).delayAfter(n)
      assert(d >= 0)
    }
  }

  test("the byte bound uses actual file sizes, not the declared ones") {
    val dir = Fixtures.writeRunDir(Files.createTempDirectory("rp-"), true)
    val path = dir.resolve("load-manifests/s3.json")
    val manifest = ujson.read(Files.readAllBytes(path))
    manifest("tables")(0)("files")(0)("bytes") = 1
    Files.writeString(path, ujson.write(manifest))
    val run = RunDirectory.open(dir)
    val store = new InMemoryObjectStore
    assertEquals(
      code(S3RunLoader.load(run, store, "v1", LoadBounds(10L, 10, 5L))),
      ErrorCode.BoundExceeded
    )
    assertEquals(store.list(""), Vector.empty)
  }

  test("keys with a trailing slash or any empty segment are refused") {
    Vector("a/b/", "a//b", "a/", "/a").foreach { key =>
      assertEquals(code(ObjectKeys.requireValid(key)), ErrorCode.InvalidKey)
    }
    ObjectKeys.requireValid("a/b")
  }

  private val mentionEdge = edgeId(m1, r1)
  private val legacy = "coauthor:v1:" + ("a" * 64)

  test("path-evidence: valid hop and status rows are read, reason carried") {
    val rows = Vector(
      pathRow(Some(edgeId(r1, r2)), r1, r2),
      pathRow(Some(mentionEdge), m1, r1),
      pathRow(
        None,
        r1,
        r2,
        Some("unreachable-within-bound"),
        Some("different-component")
      ),
      pathRow(None, r1, r2, Some("trivial"))
    )
    val read1 = read(rows)
    assertEquals(read1.size, 4)
    assertEquals(read1(2).unreachableReason, Some("different-component"))
    assertEquals(read1(3).unreachableReason, None)
  }

  test("path-evidence: legacy ID on a mention endpoint is refused") {
    assertEquals(
      code(read(Vector(pathRow(Some(legacy), m1, r1)))),
      ErrorCode.LegacyEdgeId
    )
  }

  test("path-evidence: wrong form, bad reason, missing support are refused") {
    assertEquals(
      code(read(Vector(pathRow(Some(mentionEdge), r1, r2)))),
      ErrorCode.InvalidEdgeId
    )
    assertEquals(
      code(
        read(
          Vector(
            pathRow(None, r1, r2, Some("trivial"), Some("beyond-hop-limit"))
          )
        )
      ),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(read(Vector(pathRow(None, r1, r2, Some("mystery"))))),
      ErrorCode.MalformedRow
    )
    val noSupport = pathRow(Some(edgeId(r1, r2)), r1, r2) +
      ("supportingWorkIds" -> ujson.Arr())
    assertEquals(code(read(Vector(noSupport))), ErrorCode.MissingEvidence)
    assertEquals(
      code(read(Vector(pathRow(Some(edgeId(r1, r2)), r1, r2) - "provenance"))),
      ErrorCode.MalformedRow
    )
  }

  test("semantic-summaries nodeKind reaches vector metadata when present") {
    val withKind = summaryRow("researcher-profile", "n1", "t")
    val bare = withKind - "nodeKind"
    val embedder = new FixedEmbedder(4)
    assertEquals(
      SemanticVectorLoader
        .record(withKind, embedder, "b")
        .metadata
        .get("nodeKind"),
      Some(MetaValue.Str("canonical"))
    )
    assertEquals(
      SemanticVectorLoader.record(bare, embedder, "b").metadata.get("nodeKind"),
      None
    )
    assert(StableIds.isCoauthorNode(r1))
  }
