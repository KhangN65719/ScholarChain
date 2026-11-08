package scholarchain.tests.adversarial

import java.util.concurrent.{Callable, CountDownLatch, Executors}
import munit.FunSuite
import scholarchain.core.{Provenance, ResearcherMetrics, StableIds}
import scholarchain.storage.*
import Phase7Storage.*

/** Independent adversarial checks of the DynamoDB tables, size and key rules,
  * conditional writes, TTL policy and access-pattern keys.
  */
class Phase7DynamoSuite extends FunSuite:
  private val names = TableNames("p7")
  private val limit = 400 * 1024

  private def plain(ttl: TtlPolicy): DynamoTable =
    new InMemoryDynamoTable(
      DynamoTableSpec("p7-plain", "pk", "sk", ttl, Vector.empty, Vector.empty)
    )

  private def blob(pk: String, sk: String, text: String): Item =
    Map("pk" -> s(pk), "sk" -> s(sk), "blob" -> s(text))

  private def race[A](n: Int)(f: Int => A): Vector[Either[Throwable, A]] =
    val pool = Executors.newFixedThreadPool(n)
    val start = new CountDownLatch(1)
    val jobs = (0 until n).map { i =>
      pool.submit(
        new Callable[Either[Throwable, A]]:
          def call(): Either[Throwable, A] =
            start.await()
            try Right(f(i))
            catch case t: Throwable => Left(t)
      )
    }
    start.countDown()
    val results = jobs.map(_.get()).toVector
    pool.shutdown()
    results

  // 6. The 400 KB boundary, in bytes -----------------------------------------

  test("an item of exactly 400 KB is accepted and one byte more is refused"):
    // "pk"+"p" + "sk"+"s" + "blob" = 10 bytes of overhead.
    val fits = blob("p", "s", "x" * (limit - 10))
    plain(TtlPolicy.Forbidden).putIfAbsent(fits)
    val over = blob("p", "s", "x" * (limit - 10 + 1))
    assertEquals(
      code(plain(TtlPolicy.Forbidden).putIfAbsent(over)),
      ErrorCode.ItemTooLarge
    )

  test("the boundary is in UTF-8 bytes, not characters"):
    val two = "é" // 2 bytes
    val three = "€" // 3 bytes
    val four = new String(Character.toChars(0x1f600)) // 4 bytes
    plain(TtlPolicy.Forbidden).putIfAbsent(
      blob("p", "s", two * ((limit - 10) / 2))
    )
    assertEquals(
      code(
        plain(TtlPolicy.Forbidden)
          .putIfAbsent(blob("p", "s", two * ((limit - 10) / 2 + 1)))
      ),
      ErrorCode.ItemTooLarge
    )
    plain(TtlPolicy.Forbidden).putIfAbsent(
      blob("p", "s", three * ((limit - 10) / 3))
    )
    assertEquals(
      code(
        plain(TtlPolicy.Forbidden)
          .putIfAbsent(blob("p", "s", three * ((limit - 10) / 3 + 1)))
      ),
      ErrorCode.ItemTooLarge
    )
    plain(TtlPolicy.Forbidden).putIfAbsent(
      blob("p", "s", four * ((limit - 10) / 4))
    )
    assertEquals(
      code(
        plain(TtlPolicy.Forbidden)
          .putIfAbsent(blob("p", "s", four * ((limit - 10) / 4 + 1)))
      ),
      ErrorCode.ItemTooLarge
    )
    // 300,000 characters of a 2-byte letter is fewer than 400 K chars but too big.
    assertEquals(
      code(
        plain(TtlPolicy.Forbidden).putIfAbsent(blob("p", "s", two * 300000))
      ),
      ErrorCode.ItemTooLarge
    )

  test("attribute names count toward the size"):
    val longName = "n" * 1000
    val item = Map(
      "pk" -> s("p"),
      "sk" -> s("s"),
      longName -> s("x" * (limit - 6 - 1000))
    )
    // overhead 2+1+2+1 = 6; name 1000; value limit-6-1000 gives exactly limit.
    plain(TtlPolicy.Forbidden).putIfAbsent(item)
    assertEquals(
      code(
        plain(TtlPolicy.Forbidden)
          .putIfAbsent(item.updated(longName, s("x" * (limit - 6 - 1000 + 1))))
      ),
      ErrorCode.ItemTooLarge
    )
    // Many small attributes also add up.
    val many = (0 until 5000).map(i => s"a$i" -> s("y" * 100)).toMap
    assertEquals(
      code(
        plain(TtlPolicy.Forbidden)
          .putIfAbsent(many ++ Map("pk" -> s("p"), "sk" -> s("s")))
      ),
      ErrorCode.ItemTooLarge
    )

  test("a refused item leaves no trace"):
    val table = plain(TtlPolicy.Forbidden)
    code(table.putIfAbsent(blob("p", "s", "x" * limit)))
    assertEquals(table.get("p", "s"), None)
    table.putIfAbsent(blob("p", "s", "ok"))
    assertEquals(table.get("p", "s").map(_("blob")), Some(s("ok")))

  test("partition and sort key limits are bytes: 2048 and 1024"):
    val t = plain(TtlPolicy.Forbidden)
    t.putIfAbsent(blob("p" * 2048, "s" * 1024, "v"))
    assertEquals(
      code(t.putIfAbsent(blob("p" * 2049, "s", "v"))),
      ErrorCode.InvalidItem
    )
    assertEquals(
      code(t.putIfAbsent(blob("p", "s" * 1025, "v"))),
      ErrorCode.InvalidItem
    )
    // 1024 two-byte characters are 2048 bytes: fine for pk, too long for sk.
    val e = "é"
    t.putIfAbsent(blob(e * 1024, "s2", "v"))
    assertEquals(
      code(t.putIfAbsent(blob("p3", e * 513, "v"))),
      ErrorCode.InvalidItem
    )
    t.putIfAbsent(blob("p4", e * 512, "v"))

  test("empty, missing and non-string keys are refused"):
    val t = plain(TtlPolicy.Forbidden)
    assertEquals(code(t.putIfAbsent(blob("", "s", "v"))), ErrorCode.InvalidItem)
    assertEquals(code(t.putIfAbsent(blob("p", "", "v"))), ErrorCode.InvalidItem)
    assertEquals(
      code(t.putIfAbsent(Map("sk" -> s("s")))),
      ErrorCode.InvalidItem
    )
    assertEquals(
      code(t.putIfAbsent(Map("pk" -> n(1), "sk" -> s("s")))),
      ErrorCode.InvalidItem
    )
    assertEquals(
      code(t.putIfAbsent(Map("pk" -> ujson.Null, "sk" -> s("s")))),
      ErrorCode.InvalidItem
    )

  test("table names obey the 3 to 255 character rule"):
    def make(name: String) =
      DynamoTableSpec(
        name,
        "pk",
        "sk",
        TtlPolicy.Forbidden,
        Vector.empty,
        Vector.empty
      )
    make("abc")
    make("a" * 255)
    make("A_b-c.9")
    Vector("", "ab", "a" * 256, "has space", "slash/name", "ué", "semi;colon")
      .foreach { bad =>
        assertEquals(code(make(bad)), ErrorCode.InvalidItem, bad)
      }

  // Conditional writes and races --------------------------------------------

  test("second writer loses a putIfAbsent race: exactly one winner"):
    val t = plain(TtlPolicy.Forbidden)
    val results = race(32)(i => t.putIfAbsent(blob("p", "s", s"writer-$i")))
    val wins = results.indices.filter(i => results(i).isRight)
    assertEquals(wins.size, 1)
    results.foreach {
      case Left(e: StorageException) =>
        assertEquals(e.code, ErrorCode.ConditionalCheckFailed)
      case Left(other) => fail(s"unexpected $other")
      case Right(_)    => ()
    }
    assertEquals(
      t.get("p", "s").map(_("blob")),
      Some(s(s"writer-${wins.head}"))
    )

  test("putIfVersion: one winner per version, absent and stale versions lose"):
    val t = plain(TtlPolicy.Forbidden)
    assertEquals(
      code(t.putIfVersion(blob("p", "s", "x") + ("version" -> n(2)), 1)),
      ErrorCode.ConditionalCheckFailed
    )
    t.putIfAbsent(blob("p", "s", "v0") + ("version" -> n(1)))
    val results = race(16)(i =>
      t.putIfVersion(blob("p", "s", s"v$i") + ("version" -> n(2)), 1)
    )
    assertEquals(results.count(_.isRight), 1)
    assertEquals(
      code(t.putIfVersion(blob("p", "s", "late") + ("version" -> n(3)), 1)),
      ErrorCode.ConditionalCheckFailed
    )
    assertEquals(t.get("p", "s").map(_("version")), Some(n(2)))

  test(
    "Idempotent.put: identical is a no-op, different is refused, race has one Created"
  ):
    val t = plain(TtlPolicy.Forbidden)
    val item = blob("p", "s", "same")
    assertEquals(Idempotent.put(t, item), WriteOutcome.Created)
    assertEquals(Idempotent.put(t, item), WriteOutcome.Unchanged)
    assertEquals(
      code(Idempotent.put(t, blob("p", "s", "other"))),
      ErrorCode.ConditionalCheckFailed
    )
    assertEquals(t.get("p", "s"), Some(item))
    val t2 = plain(TtlPolicy.Forbidden)
    val results = race(24)(_ => Idempotent.put(t2, item))
    assertEquals(
      results.collect { case Right(WriteOutcome.Created) => 1 }.size,
      1
    )
    assertEquals(
      results.collect { case Right(WriteOutcome.Unchanged) => 1 }.size,
      23
    )

  test("Idempotent.put does not hide a size or key violation behind Unchanged"):
    val t = plain(TtlPolicy.Forbidden)
    assertEquals(
      code(Idempotent.put(t, blob("p", "s", "x" * limit))),
      ErrorCode.ItemTooLarge
    )
    assertEquals(
      code(Idempotent.put(t, blob("", "s", "v"))),
      ErrorCode.InvalidItem
    )

  // TTL ----------------------------------------------------------------------

  test("TTL policy per table: disposable tables require it, records forbid it"):
    val forbidden = Vector(
      RunLedger.spec(names),
      EvidenceBundleTable.spec(names),
      ArtifactRegistry.spec(names)
    )
    val required = Vector(
      EntityLinkCache.spec(names),
      QuerySession.spec(names),
      ResearcherMetricCache.spec(names)
    )
    forbidden.foreach(spec =>
      assertEquals(spec.ttl, TtlPolicy.Forbidden, spec.name)
    )
    required.foreach(spec =>
      assertEquals(spec.ttl, TtlPolicy.Required, spec.name)
    )

  test("a ttl attribute is rejected on every non-disposable table"):
    Vector(
      RunLedger.spec(names),
      EvidenceBundleTable.spec(names),
      ArtifactRegistry.spec(names)
    )
      .foreach { spec =>
        val t = new InMemoryDynamoTable(spec)
        assertEquals(
          code(t.putIfAbsent(blob("p", "s", "v") + ("ttl" -> n(1900000000L)))),
          ErrorCode.TtlNotAllowed,
          spec.name
        )
        assertEquals(
          code(t.putIfAbsent(blob("p", "s", "v") + ("ttl" -> ujson.Null))),
          ErrorCode.TtlNotAllowed,
          spec.name
        )
        t.putIfAbsent(blob("p", "s", "v"))
      }

  test("a whole-number ttl is required on every disposable table"):
    Vector(
      EntityLinkCache.spec(names),
      QuerySession.spec(names),
      ResearcherMetricCache.spec(names)
    )
      .foreach { spec =>
        val t = new InMemoryDynamoTable(spec)
        val base = blob("p", "s", "v")
        assertEquals(
          code(t.putIfAbsent(base)),
          ErrorCode.TtlRequired,
          spec.name
        )
        assertEquals(
          code(t.putIfAbsent(base + ("ttl" -> s("1900000000")))),
          ErrorCode.TtlRequired
        )
        assertEquals(
          code(t.putIfAbsent(base + ("ttl" -> ujson.Num(1.5)))),
          ErrorCode.TtlRequired
        )
        assertEquals(
          code(t.putIfAbsent(base + ("ttl" -> ujson.Null))),
          ErrorCode.TtlRequired
        )
        assertEquals(
          code(t.putIfAbsent(base + ("ttl" -> ujson.Bool(true)))),
          ErrorCode.TtlRequired
        )
        t.putIfAbsent(base + ("ttl" -> n(1900000000L)))
      }

  test("the helper writers set ttl only where it is allowed"):
    val link = new InMemoryDynamoTable(EntityLinkCache.spec(names))
    EntityLinkCache.put(
      link,
      "dblp:1",
      "pol-1",
      Some(r1),
      "ACCEPT",
      1900000000L
    )
    assert(link.get("LINK#dblp:1", "POLICY#pol-1").exists(_.contains("ttl")))
    val reg = new InMemoryDynamoTable(ArtifactRegistry.spec(names))
    ArtifactRegistry.register(reg, "graph", runId, "f", "graph/k", "ab" * 32, 3)
    assert(reg.query("ARTIFACT#graph", None, 10).forall(!_.contains("ttl")))
    val ledger = new InMemoryDynamoTable(RunLedger.spec(names))
    RunLedger.start(
      ledger,
      runId,
      "spark-analytics",
      "tx",
      "2026-11-08T00:00:00Z"
    )
    RunLedger.recordStage(ledger, runId, "pairs", "DONE")
    assert(ledger.query(s"RUN#$runId", None, 10).forall(!_.contains("ttl")))

  // Lifecycle and access patterns -------------------------------------------

  test("run ledger: start is idempotent, a different identity is refused"):
    val t = new InMemoryDynamoTable(RunLedger.spec(names))
    assertEquals(
      RunLedger.start(t, runId, "k", "tx", "2026-11-08T00:00:00Z"),
      WriteOutcome.Created
    )
    assertEquals(
      RunLedger.start(t, runId, "k", "tx", "2026-11-08T00:00:00Z"),
      WriteOutcome.Unchanged
    )
    assertEquals(
      code(RunLedger.start(t, runId, "k", "tx", "2027-01-01T00:00:00Z")),
      ErrorCode.ConditionalCheckFailed
    )
    assertEquals(
      code(
        RunLedger.start(t, runId, "other-kind", "tx", "2026-11-08T00:00:00Z")
      ),
      ErrorCode.ConditionalCheckFailed
    )
    assertEquals(
      code(RunLedger.start(t, runId, "k", "tx-2", "2026-11-08T00:00:00Z")),
      ErrorCode.ConditionalCheckFailed
    )

  test("run ledger: a transition is applied once even under a race"):
    val t = new InMemoryDynamoTable(RunLedger.spec(names))
    RunLedger.start(t, runId, "k", "tx", "2026-11-08T00:00:00Z")
    val results =
      race(12)(_ => RunLedger.transition(t, runId, "STARTED", "LOADED"))
    assertEquals(results.count(_.isRight), 1)
    val item = t.get(s"RUN#$runId", "META").get
    assertEquals(item("status"), s("LOADED"))
    assertEquals(item("version"), n(2))
    assertEquals(
      code(RunLedger.transition(t, runId, "STARTED", "FAILED")),
      ErrorCode.ConditionalCheckFailed
    )
    assertEquals(
      code(RunLedger.transition(t, otherRun, "STARTED", "LOADED")),
      ErrorCode.ConditionalCheckFailed
    )
    // restart of a loaded run is progress, not a different run
    assertEquals(
      RunLedger.start(t, runId, "k", "tx", "2026-11-08T00:00:00Z"),
      WriteOutcome.Unchanged
    )
    assertEquals(t.get(s"RUN#$runId", "META").get("status"), s("LOADED"))

  test("access patterns A1 to A3 are served by the stated keys"):
    val t = new InMemoryDynamoTable(RunLedger.spec(names))
    RunLedger.start(t, runId, "k", "tx", "2026-11-08T00:00:00Z")
    RunLedger.start(t, otherRun, "k", "tx", "2026-11-09T00:00:00Z")
    RunLedger.recordStage(t, runId, "b-stage", "DONE")
    RunLedger.recordStage(t, runId, "a-stage", "DONE")
    assertEquals(
      RunLedger.recordStage(t, runId, "a-stage", "DONE"),
      WriteOutcome.Unchanged
    )
    assertEquals(
      code(RunLedger.recordStage(t, runId, "a-stage", "FAILED")),
      ErrorCode.ConditionalCheckFailed
    )
    assert(t.get(s"RUN#$runId", "META").isDefined)
    val stages = t.query(s"RUN#$runId", Some("STAGE#"), 10).map(_("stage").str)
    assertEquals(stages, Vector("a-stage", "b-stage"))
    val started = t
      .queryIndex("status-index", "STATUS#STARTED", None, 10)
      .map(_("runId").str)
    assertEquals(started, Vector(runId, otherRun))
    RunLedger.transition(t, runId, "STARTED", "LOADED")
    assertEquals(
      t.queryIndex("status-index", "STATUS#STARTED", None, 10)
        .map(_("runId").str),
      Vector(otherRun)
    )
    assertEquals(
      code(t.queryIndex("no-such-index", "x", None, 1)),
      ErrorCode.InvalidItem
    )
    assertEquals(t.query(s"RUN#$runId", Some("STAGE#"), 1).size, 1)

  test("query session turns come back in order, zero padded"):
    val t = new InMemoryDynamoTable(QuerySession.spec(names))
    Vector(10, 2, 1, 100).foreach(i =>
      QuerySession.appendTurn(t, "sess", i, "{}", 1900000000L)
    )
    val sks = t.query("SESSION#sess", Some("TURN#"), 10).map(_("sk").str)
    assertEquals(
      sks,
      Vector("TURN#000001", "TURN#000002", "TURN#000010", "TURN#000100")
    )
    assertEquals(
      QuerySession.appendTurn(t, "sess", 1, "{}", 1900000000L),
      WriteOutcome.Unchanged
    )
    assertEquals(
      code(
        QuerySession.appendTurn(t, "sess", 1, "{\"changed\":true}", 1900000000L)
      ),
      ErrorCode.ConditionalCheckFailed
    )

  test("entity link cache: same decision is a no-op, a changed one is refused"):
    val t = new InMemoryDynamoTable(EntityLinkCache.spec(names))
    assertEquals(
      EntityLinkCache.put(t, "k", "p1", None, "REJECT", 1900000000L),
      WriteOutcome.Created
    )
    assertEquals(
      EntityLinkCache.put(t, "k", "p1", None, "REJECT", 1900000000L),
      WriteOutcome.Unchanged
    )
    assertEquals(
      code(EntityLinkCache.put(t, "k", "p1", Some(r1), "ACCEPT", 1900000000L)),
      ErrorCode.ConditionalCheckFailed
    )
    assertEquals(
      EntityLinkCache.put(t, "k", "p2", Some(r1), "ACCEPT", 1900000000L),
      WriteOutcome.Created
    )

  // Metrics rows, provenance and IDs ----------------------------------------

  private def metricsRow(id: String, p: Provenance = provenance()): Row =
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

  private def metricKey(id: String, run: String = runId) =
    (s"RESEARCHER#$id", s"WINDOW#2019-2023#RUN#${run.replace(':', '-')}")

  test("metrics rows keep their core researcher or mention ID in the key"):
    val t = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    Vector(r1, m1).foreach { id =>
      assertEquals(
        DynamoRunLoader.putMetrics(
          t,
          metricsRow(id) + ("nodeKind" -> s("AuthorMention")),
          runId,
          1900000000L
        ),
        WriteOutcome.Created
      )
      val (pk, sk) = metricKey(id)
      assert(t.get(pk, sk).isDefined, s"$pk / $sk")
    }
    // F2: all windows of a researcher
    assertEquals(t.query(s"RESEARCHER#$r1", Some("WINDOW#"), 10).size, 1)
    // other run, same researcher and window: a separate item
    DynamoRunLoader.putMetrics(
      t,
      metricsRow(r1, provenance(run = otherRun)),
      otherRun,
      1900000000L
    )
    assertEquals(t.query(s"RESEARCHER#$r1", Some("WINDOW#"), 10).size, 2)

  test("metrics provenance survives into the cached item"):
    val t = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    val p =
      provenance(snapshot = "snap-ü", record = "rec/日", transform = "tx/5")
    DynamoRunLoader.putMetrics(t, metricsRow(r1, p), runId, 1900000000L)
    val (pk, sk) = metricKey(r1)
    val item = t.get(pk, sk).get
    assertEquals(item("runId"), s(runId))
    val back = upickle.default.read[ResearcherMetrics](item("metricsJson").str)
    assertEquals(back.provenance, p)
    assertEquals(back.provenance.runId, runId)
    assertEquals(back.provenance.snapshotId, "snap-ü")
    assertEquals(back.provenance.sourceRecordId, "rec/日")
    assertEquals(back.provenance.transformVersion, "tx/5")

  test(
    "metrics: reload is a no-op, a changed value under the same key is refused"
  ):
    val t = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    DynamoRunLoader.putMetrics(t, metricsRow(r1), runId, 1900000000L)
    assertEquals(
      DynamoRunLoader.putMetrics(t, metricsRow(r1), runId, 1900000000L),
      WriteOutcome.Unchanged
    )
    val changed = metricsRow(r1) + ("weightedDegree" -> ujson.Num(2.0))
    assertEquals(
      code(DynamoRunLoader.putMetrics(t, changed, runId, 1900000000L)),
      ErrorCode.ConditionalCheckFailed
    )
    val (pk, sk) = metricKey(r1)
    assertEquals(
      upickle.default
        .read[ResearcherMetrics](t.get(pk, sk).get("metricsJson").str)
        .weightedDegree,
      1.0
    )

  test("metrics rows with malformed IDs or contract breaks are refused"):
    val t = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    Vector(
      "",
      "researcher:v1:zz",
      w1,
      "mention:mention-1",
      "mention:v1:" + "AB" * 32
    ).foreach { bad =>
      assertEquals(
        code(
          DynamoRunLoader.putMetrics(t, metricsRow(bad), runId, 1900000000L)
        ),
        ErrorCode.MalformedRow,
        bad
      )
    }
    val missing = metricsRow(r1) - "componentId"
    assertEquals(
      code(DynamoRunLoader.putMetrics(t, missing, runId, 1900000000L)),
      ErrorCode.MalformedRow
    )
    assertEquals(t.query(s"RESEARCHER#$r1", None, 10), Vector.empty)

  test("metrics row with an inverted window is a MalformedRow"):
    val t = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    val badWindow = metricsRow(r1) + ("windowStart" -> n(2030))
    assertEquals(
      code(DynamoRunLoader.putMetrics(t, badWindow, runId, 1900000000L)),
      ErrorCode.MalformedRow
    )

  test("metrics row without provenance is a MalformedRow"):
    val t = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    val noProv = metricsRow(r1) - "provenance"
    assertEquals(
      code(DynamoRunLoader.putMetrics(t, noProv, runId, 1900000000L)),
      ErrorCode.MalformedRow
    )
    val nullProv = metricsRow(r1) + ("provenance" -> ujson.Null)
    assertEquals(
      code(DynamoRunLoader.putMetrics(t, nullProv, runId, 1900000000L)),
      ErrorCode.MalformedRow
    )

  // Source and transformation provenance, storage and Spark kept apart -----

  private val storageVersion = StorageProvenance.TransformVersion

  test("artifact registry: storage version always, Spark version when known"):
    val t = new InMemoryDynamoTable(ArtifactRegistry.spec(names))
    ArtifactRegistry.register(
      t,
      "graph",
      runId,
      "a",
      "graph/k1",
      "ab" * 32,
      3,
      Some("tx/9")
    )
    ArtifactRegistry.register(t, "graph", runId, "b", "graph/k2", "cd" * 32, 4)
    val items = t.query("ARTIFACT#graph", None, 10)
    assertEquals(items.size, 2)
    val withSpark = items.find(_("name") == s("a")).get
    val without = items.find(_("name") == s("b")).get
    assertEquals(withSpark("storageTransformVersion"), s(storageVersion))
    assertEquals(withSpark("sparkTransformVersion"), s("tx/9"))
    assertEquals(withSpark("sourceRef"), s(s"spark-run:$runId/a"))
    assertEquals(without("storageTransformVersion"), s(storageVersion))
    assert(!without.contains("sparkTransformVersion"))
    // never one version in place of the other
    assertNotEquals(
      withSpark("storageTransformVersion"),
      withSpark("sparkTransformVersion")
    )
    // a changed Spark version under the same key is a different payload
    assertEquals(
      code(
        ArtifactRegistry.register(
          t,
          "graph",
          runId,
          "a",
          "graph/k1",
          "ab" * 32,
          3,
          Some("tx/10")
        )
      ),
      ErrorCode.ConditionalCheckFailed
    )

  test(
    "run ledger keeps Spark's version and records the storage version beside it"
  ):
    val t = new InMemoryDynamoTable(RunLedger.spec(names))
    RunLedger.start(
      t,
      runId,
      "spark-analytics",
      "spark-analytics/1.1.0",
      "2026-11-08T00:00:00Z"
    )
    val meta = t.get(s"RUN#$runId", "META").get
    assertEquals(meta("kind"), s("spark-analytics"))
    assertEquals(meta("transformVersion"), s("spark-analytics/1.1.0"))
    assertEquals(meta("sparkTransformVersion"), s("spark-analytics/1.1.0"))
    assertEquals(meta("storageTransformVersion"), s(storageVersion))
    assertEquals(meta("sourceRef"), s(s"spark-run:$runId"))
    // the storage version is not mixed into Spark's field
    assertNotEquals(meta("transformVersion"), s(storageVersion))
    // a status change keeps both
    RunLedger.transition(t, runId, "STARTED", "LOADED")
    val moved = t.get(s"RUN#$runId", "META").get
    assertEquals(moved("sparkTransformVersion"), s("spark-analytics/1.1.0"))
    assertEquals(moved("storageTransformVersion"), s(storageVersion))
    // a stage record has storage provenance and no Spark version of its own
    RunLedger.recordStage(t, runId, "pairs", "DONE")
    val stage = t.get(s"RUN#$runId", "STAGE#pairs").get
    assertEquals(stage("storageTransformVersion"), s(storageVersion))
    assert(!stage.contains("sparkTransformVersion"))

  test(
    "researcher metric cache carries the row's Spark version and the storage one"
  ):
    val t = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    val p = provenance(transform = "scholarchain.spark/7")
    DynamoRunLoader.putMetrics(t, metricsRow(r1, p), runId, 1900000000L)
    val (pk, sk) = metricKey(r1)
    val item = t.get(pk, sk).get
    assertEquals(item("sparkTransformVersion"), s("scholarchain.spark/7"))
    assertEquals(item("storageTransformVersion"), s(storageVersion))
    assertEquals(item("sourceRef"), s(s"spark-run:$runId"))
    // Spark's version also survives inside the embedded metrics record
    val back = upickle.default.read[ResearcherMetrics](item("metricsJson").str)
    assertEquals(back.provenance.transformVersion, "scholarchain.spark/7")

  test("entity link cache and query session carry storage provenance only"):
    val link = new InMemoryDynamoTable(EntityLinkCache.spec(names))
    EntityLinkCache.put(
      link,
      "dblp:42",
      "pol-1",
      Some(r1),
      "ACCEPT",
      1900000000L
    )
    val li = link.get("LINK#dblp:42", "POLICY#pol-1").get
    assertEquals(li("storageTransformVersion"), s(storageVersion))
    assertEquals(li("sourceRef"), s("entity-link:dblp:42"))
    assert(!li.contains("sparkTransformVersion"))
    assertEquals(li("linkPolicyVersion"), s("pol-1"))
    val session = new InMemoryDynamoTable(QuerySession.spec(names))
    QuerySession.appendTurn(session, "sess", 1, "{}", 1900000000L)
    val si = session.get("SESSION#sess", "TURN#000001").get
    assertEquals(si("storageTransformVersion"), s(storageVersion))
    assertEquals(si("sourceRef"), s("query-session:sess"))
    assert(!si.contains("sparkTransformVersion"))

  test(
    "a metrics key from a core ID needs no escaping and stays under the key limits"
  ):
    val id = StableIds.researcherFromExternal("openalex", "A" * 400)
    val t = new InMemoryDynamoTable(ResearcherMetricCache.spec(names))
    DynamoRunLoader.putMetrics(t, metricsRow(id), runId, 1900000000L)
    val (pk, sk) = metricKey(id)
    assert(t.get(pk, sk).isDefined)
    assert(
      pk.getBytes("UTF-8").length <= 2048 && sk.getBytes("UTF-8").length <= 1024
    )
