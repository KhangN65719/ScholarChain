package scholarchain.services

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import scholarchain.core.YearWindow
import scholarchain.rag.{AllowlistedNeptune, CollaborationWorkflow, DemoWorld}
import scholarchain.rag.{Session, WorkflowResult}

/** Phase 11 service carry-overs: an idempotency key is released after a fatal
  * Throwable (P11-S1), and a 4-digit number is a year only in a year context
  * (P11-S2).
  */
class Phase11Suite extends FunSuite:
  private val config = ServiceConfig.from(
    Map(
      "auth.queryToken" -> "query-token-0123456789",
      "auth.readToken" -> "read-token-0123456789",
      "retry.backoffMillis" -> "0"
    )
  )

  // An Error that NonFatal does not match, and that is safe to throw in a test.
  private final class TestFatal extends VirtualMachineError("test fatal")

  private final class FatalRecords(fatalPuts: Int) extends QueryRecordStore:
    private val inner = new InMemoryQueryRecordStore(100)
    private val left = new AtomicInteger(fatalPuts)
    def put(record: QueryRecord): Unit =
      if left.getAndDecrement() > 0 then throw new TestFatal
      else inner.put(record)
    def byQuery(queryId: String): Option[QueryRecord] = inner.byQuery(queryId)
    def byEvidence(evidenceId: String): Option[QueryRecord] =
      inner.byEvidence(evidenceId)
    def ready(): Unit = ()

  private final class FatalWorkflow(inner: WorkflowPort, fatalCalls: Int)
      extends WorkflowPort:
    private val left = new AtomicInteger(fatalCalls)
    def answer(question: String, session: Session): WorkflowResult =
      if left.getAndDecrement() > 0 then throw new TestFatal
      else inner.answer(question, session)
    def ready(): Unit = inner.ready()

  private final class Rig(fatalPuts: Int = 0, fatalWorkflowCalls: Int = 0):
    private val world = DemoWorld.build()
    private val ports =
      world.ports.copy(neptune = new AllowlistedNeptune(world.neptune))
    private val workflow = new CollaborationWorkflow(ports, world.config)
    private val pool = Threads.bounded("p11", 2, 4)
    val records = new FatalRecords(fatalPuts)
    val idempotency = new InMemoryIdempotency(100)
    val jobs = new JobService(config, new InMemoryJobStore(100), new Metrics)
    val service = new QueryService(
      config,
      new FatalWorkflow(
        new LocalWorkflowPort(workflow, () => ()),
        fatalWorkflowCalls
      ),
      new Downstream(
        "workflow",
        config,
        pool,
        new Metrics,
        new CircuitBreaker(100, 60000L)
      ),
      records,
      idempotency,
      jobs
    )
    def close(): Unit =
      pool.shutdownNow()
      jobs.close()

  // munit's intercept does not catch a fatal Throwable, so catch it here.
  private def fatal(thunk: => Any): Option[TestFatal] =
    try
      thunk
      None
    catch case t: TestFatal => Some(t)

  private def body(mode: String): Array[Byte] =
    ujson
      .write(ujson.Obj("question" -> Fixture.ada, "mode" -> mode))
      .getBytes(UTF_8)

  private def request(bytes: Array[Byte]): QueryRequest =
    QueryRequests.resolve(QueryRequests.parse(bytes, config), false, config)

  private def submit(rig: Rig, bytes: Array[Byte], key: String): StoredResult =
    val r = request(bytes)
    rig.service.submit(
      r,
      Some(key),
      QueryRequests.fingerprint(bytes, r.mode),
      new Deadline(20000)
    )

  private def answered(result: StoredResult): QueryOutcome = result match
    case Answered(outcome) => outcome
    case other             => fail(s"expected an answer, got $other")

  test("P11-S1: a fatal Throwable releases the key and a replay re-runs") {
    val rig = new Rig(fatalPuts = 1)
    try
      val bytes = body("sync")
      assert(fatal(submit(rig, bytes, "key-fatal-sync")).isDefined)
      val second = answered(submit(rig, bytes, "key-fatal-sync"))
      assert(second.queryId.nonEmpty)
      // The re-run is now the stored result: a third call replays it.
      val third = answered(submit(rig, bytes, "key-fatal-sync"))
      assertEquals(third.queryId, second.queryId)
    finally rig.close()
  }

  test("P11-S1: a fatal Throwable is rethrown, never swallowed or stored") {
    val rig = new Rig(fatalPuts = 1)
    try
      val bytes = body("sync")
      val t = fatal(submit(rig, bytes, "key-fatal-rethrow"))
      assertEquals(t.map(_.getMessage), Some("test fatal"))
      // The key is free: the next claim owns it instead of replaying.
      val fp = QueryRequests.fingerprint(bytes, QueryMode.Sync)
      rig.idempotency.claim("key-fatal-rethrow", fp) match
        case Claim.Owner(_, _)  => ()
        case Claim.Replay(done) => fail(s"key still held, done=${done.isDone}")
    finally rig.close()
  }

  test("P11-S1: a fatal Error in the workflow is 503 and the key is free") {
    val rig = new Rig(fatalWorkflowCalls = 1)
    try
      val bytes = body("sync")
      val e = intercept[ApiException](submit(rig, bytes, "key-fatal-flow"))
      assertEquals(e.code, ErrorCode.DownstreamUnavailable)
      assertEquals(e.code.status, 503)
      val again = answered(submit(rig, bytes, "key-fatal-flow"))
      assert(again.queryId.nonEmpty)
    finally rig.close()
  }

  test("P11-S1: a fatal Throwable in an async job fails the job") {
    val rig = new Rig(fatalPuts = 1)
    try
      val bytes = body("async")
      val first = submit(rig, bytes, "key-fatal-async") match
        case a: Accepted => a
        case other       => fail(s"expected 202, got $other")
      val deadline = System.nanoTime() + 20L * 1000000000L
      while !rig.jobs.get(first.jobId).state.finished &&
        System.nanoTime() < deadline
      do Thread.sleep(10)
      val job = rig.jobs.get(first.jobId)
      assertEquals(job.state, JobState.Failed)
      assertEquals(job.error.map(_.code), Some(ErrorCode.Internal))
      assertEquals(job.result, None)
      val e = intercept[ApiException](rig.jobs.result(first.jobId))
      assertEquals(e.code, ErrorCode.JobFailed)
      // A replay of the key reports the stored job reference, now failed,
      // instead of a job that stays running forever.
      val replay = submit(rig, bytes, "key-fatal-async") match
        case a: Accepted => a
        case other       => fail(s"expected 202, got $other")
      assertEquals(replay.jobId, first.jobId)
      assert(replay.replayed)
      assertEquals(rig.jobs.stateOf(replay), JobState.Failed)
    finally rig.close()
  }

  // P11-S2 -------------------------------------------------------------------

  test("P11-S2: only a year in a year context is a year") {
    assertEquals(QueryRequests.years("since 2015"), Set(2015))
    assertEquals(QueryRequests.years("between 2010 and 2015"), Set(2010, 2015))
    assertEquals(QueryRequests.years("in 1999"), Set(1999))
    assertEquals(QueryRequests.years("Who in 2000-2009?"), Set(2000, 2009))
    assertEquals(
      QueryRequests.years("top 1000 coauthors since 2015"),
      Set(2015)
    )
    assertEquals(QueryRequests.years("top 1000 coauthors"), Set.empty[Int])
    assertEquals(QueryRequests.years("1000 papers"), Set.empty[Int])
    assertEquals(QueryRequests.years("2015 coauthors"), Set.empty[Int])
    assertEquals(QueryRequests.years("top 2000 coauthors"), Set.empty[Int])
    assertEquals(QueryRequests.years("first 1950 papers"), Set.empty[Int])
    assertEquals(QueryRequests.years("at least 2015 papers"), Set.empty[Int])
    assertEquals(QueryRequests.years("limit 2000"), Set.empty[Int])
    assertEquals(QueryRequests.years("top 2000 coauthors in 2005"), Set(2005))
    assertEquals(QueryRequests.years("a 2015 award"), Set.empty[Int])
    assertEquals(QueryRequests.years("in 1850"), Set.empty[Int])
    assertEquals(QueryRequests.years("id 12345 in 20150"), Set.empty[Int])
  }

  test("P11-S2: a count is not a year for the structured window") {
    val w = Some(YearWindow(2000, 2009))
    assertEquals(
      QueryRequests.compose("top 1000 coauthors", w),
      "top 1000 coauthors from 2000-2009"
    )
    assertEquals(
      QueryRequests.compose("2015 coauthors", w),
      "2015 coauthors from 2000-2009"
    )
    assertEquals(
      QueryRequests.compose("1000 papers", w),
      "1000 papers from 2000-2009"
    )
    // Repair 1 (M-1): this case once expected the question to pass unchanged
    // with window (2015, 2015). The planner reads "since 2015" as 2015-9999,
    // so it is now 422 (see the M-1 tests below).
    val e = intercept[ApiException](
      QueryRequests.compose(
        "top 1000 coauthors since 2015",
        Some(YearWindow(2015, 2015))
      )
    )
    assertEquals(e.code, ErrorCode.UnsupportedConstraint)
  }

  private def refused(q: String, w: YearWindow): Unit =
    val e = intercept[ApiException](QueryRequests.compose(q, Some(w)))
    assertEquals(e.code, ErrorCode.UnsupportedConstraint, q)
    assertEquals(e.code.status, 422, q)

  test("M-1: 'since Y' with a window is 422, never a reinterpreted window") {
    val q = "How many distinct coauthors does Ada Quillfeather have since 2015?"
    refused(q, YearWindow(2015, 2015))
    refused(q, YearWindow(2000, 2009))
    // The planner reads "since 2015" as 2015-9999, so only that window is
    // proven equal and the question passes unchanged.
    assertEquals(QueryRequests.compose(q, Some(YearWindow(2015, 9999))), q)
  }

  test("M-1: 'in Y and Z' with a window is 422") {
    val q =
      "How many distinct coauthors does Ada Quillfeather have in 2005 and 2009?"
    refused(q, YearWindow(2005, 2009))
    refused(q, YearWindow(2005, 2005))
  }

  test("M-1: an unprovable or out-of-range year with a window is 422") {
    val w = YearWindow(2000, 2009)
    Vector(
      "How many distinct coauthors does Ada Quillfeather have in 1850?",
      "How many distinct coauthors does Ada Quillfeather have in 2015 publications?",
      "Coauthors of Ada before 2015",
      "Coauthors of Ada after 2001",
      "Coauthors of Ada until 2005",
      "Coauthors of Ada from 2000",
      "Coauthors of Ada overall",
      "Coauthors of Ada in 2000 and 2009",
      "Coauthors of Ada in 2000-2009 and 2012",
      "Coauthors of Ada for 1000 papers",
      "Coauthors of Ada, limit of 1000",
      "Coauthors of Ada in 2000-2005"
    ).foreach(refused(_, w))
  }

  test("M-1: a question the planner reads as the same window passes") {
    val w = YearWindow(2000, 2009)
    Vector(
      "Coauthors of Ada in 2000-2009?",
      "Coauthors of Ada between 2000 and 2009?",
      "Coauthors of Ada from 2000 to 2009?"
    ).foreach(q => assertEquals(QueryRequests.compose(q, Some(w)), q))
    assertEquals(
      QueryRequests.compose(
        "Coauthors of Ada in 2015?",
        Some(YearWindow(2015, 2015))
      ),
      "Coauthors of Ada in 2015?"
    )
    assertEquals(
      QueryRequests.compose(
        "Coauthors of Ada during 2015?",
        Some(YearWindow(2015, 2015))
      ),
      "Coauthors of Ada during 2015?"
    )
  }

  test("M-1: counts are not years, so the window is appended") {
    val w = Some(YearWindow(2000, 2009))
    Vector(
      "top 1000 coauthors",
      "the first 2015 papers",
      "at least 1500 papers",
      "2015 coauthors",
      "1000 papers"
    ).foreach(q =>
      assertEquals(QueryRequests.compose(q, w), s"$q from 2000-2009", q)
    )
  }

  test("M-1: a researcher ID with digits is not a year") {
    val w = Some(YearWindow(2000, 2009))
    val q = "Coauthors of researcher:v1:orcid:0000-0002-1825-0097"
    assertEquals(QueryRequests.compose(q, w), s"$q from 2000-2009")
  }

  test("M-1: without a structured window nothing changes") {
    Vector("since 2015", "in 1850", "in 2005 and 2009", "overall").foreach(q =>
      assertEquals(QueryRequests.compose(q, None), q)
    )
  }

  test("M-1: over HTTP, 422 for 'since' and 200 with an exact echo") {
    val rt = Fixture.start()
    try
      def ask(q: String, w: (Int, Int)) =
        rt.ask(
          q,
          Map("window" -> ujson.Obj("startYear" -> w._1, "endYear" -> w._2))
        )
      val since = ask(
        "How many distinct coauthors does Ada Quillfeather have since 2015?",
        (2015, 2015)
      )
      assertEquals(since.status, 422, since.body)
      assertEquals(
        since.json("error")("code").str,
        "UNSUPPORTED_CONSTRAINT"
      )
      val old = ask(
        "How many distinct coauthors does Ada Quillfeather have in 1850?",
        (2000, 2009)
      )
      assertEquals(old.status, 422, old.body)
      val ok = ask(
        "How many distinct coauthors does Ada Quillfeather have in 2000-2009?",
        (2000, 2009)
      )
      assertEquals(ok.status, 200, ok.body)
      assertEquals(ok.json("request")("window")("startYear").num.toInt, 2000)
      assertEquals(ok.json("request")("window")("endYear").num.toInt, 2009)
    finally rt.close()
  }

  // N-1 (repair 2). The planner reads only four-digit years, so the appended
  // clause is zero-padded: "0001-2009" is read as exactly (1, 2009).
  private val subThousand = Vector(
    (1, 2009),
    (500, 1500),
    (999, 2009),
    (1, 9999),
    (1000, 2009),
    (2000, 9999)
  )

  test("N-1: compose zero-pads the appended window to four digits") {
    val q = "How many distinct coauthors does Ada Quillfeather have?"
    subThousand.foreach { (s, e) =>
      val expected = f"$q from $s%04d-$e%04d"
      assertEquals(
        QueryRequests.compose(q, Some(YearWindow(s, e))),
        expected,
        s"$s-$e"
      )
    }
    assertEquals(
      QueryRequests.compose(q, Some(YearWindow(1, 2009))),
      s"$q from 0001-2009"
    )
  }

  test("N-1: a question window below 1000 passes only when it is the same") {
    val q = "Coauthors of Ada in 0500-1500?"
    assertEquals(QueryRequests.compose(q, Some(YearWindow(500, 1500))), q)
    refused(q, YearWindow(500, 1501))
    refused(q, YearWindow(1, 1500))
    val one = "Coauthors of Ada in 0999?"
    assertEquals(QueryRequests.compose(one, Some(YearWindow(999, 999))), one)
    refused(one, YearWindow(999, 1000))
    refused("Coauthors of Ada since 0500?", YearWindow(500, 500))
  }

  test("N-1: over HTTP the echoed window is the window the planner applied") {
    val rt = Fixture.start()
    try
      def window(v: ujson.Value): (Int, Int) = v match
        case a: ujson.Arr if a.value.size == 1 => window(a.value.head)
        case o => (o("startYear").num.toInt, o("endYear").num.toInt)
      subThousand.foreach { (s, e) =>
        val r = rt.ask(
          "How many distinct coauthors does Ada Quillfeather have?",
          Map("window" -> ujson.Obj("startYear" -> s, "endYear" -> e))
        )
        assertEquals(r.status, 200, s"$s-$e ${r.body}")
        assertEquals(window(r.json("request")("window")), (s, e))
        val id = r.json("queryId").str
        val fast = rt.getFast(s"/v1/explain/$id")
        val explained =
          if fast.status == 200 then fast else rt.getSlow(s"/v1/explain/$id")
        assertEquals(explained.status, 200, s"$s-$e ${explained.body}")
        val plan = explained.json("queryPlan")
        assertEquals(window(plan("window")), (s, e), s"$s-$e applied")
        assert(
          plan.obj.get("comparisonWindow").forall(_ == ujson.Null),
          s"$s-$e second window"
        )
      }
    finally rt.close()
  }

  test("P11-S2: a real year that conflicts with the window is still 422") {
    val w = Some(YearWindow(2000, 2009))
    Vector(
      "top 1000 coauthors since 2015",
      "since 2015",
      "between 2010 and 2015",
      "in 1999"
    ).foreach { q =>
      val e = intercept[ApiException](QueryRequests.compose(q, w))
      assertEquals(e.code, ErrorCode.UnsupportedConstraint, q)
    }
  }

  test("P11-S2: 'top 1000 coauthors' with a structured window is 200") {
    val rt = Fixture.start()
    try
      val r = rt.ask(
        "Who are the top 1000 coauthors of Ada Quillfeather?",
        Map(
          "window" -> ujson.Obj("startYear" -> 2000, "endYear" -> 2009)
        )
      )
      assertEquals(r.status, 200, r.body)
    finally rt.close()
  }
