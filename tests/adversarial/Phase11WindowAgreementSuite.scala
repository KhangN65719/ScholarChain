package scholarchain.tests.adversarial

/** Phase 11 repair 1 (M-1, D7): the services keep a copy of the planner's
  * window reader, because `Planner.windowsOf` is not public. This suite is the
  * drift guard. For every question and structured window it asks the real
  * service stack and then reads the window the planner really used from the
  * explain endpoint's query plan (not from the `request` echo). The only two
  * accepted outcomes are a 422 UNSUPPORTED_CONSTRAINT or a 200 whose echoed
  * `request.window` equals the planner's applied window, with no second window.
  * Written from the reviewer's M-1 finding and the service-engineer's report
  * 06, independent of the module tests.
  */
class Phase11WindowAgreementSuite extends P10Suite:
  private val ada = "How many distinct coauthors does Ada Quillfeather have"

  /** A real researcher ID of the fixture world (they carry digits), read from
    * the resolution the runtime itself stored for Ada Quillfeather. A made-up
    * ID is not resolvable, so the planner stores no plan for it.
    */
  private lazy val someId: String =
    val r = api.ask(s"$ada in 2000-2009?")
    assertEquals(r.status, 200, r.body)
    val explained = api.get(s"/v1/explain/${Json.queryId(r.json).get}")
    val ids = Json.researcherIds(ujson.write(explained.json("resolutions")))
    assert(
      ids.nonEmpty,
      s"no researcher ID in the resolutions: ${explained.body}"
    )
    assert(ids.head.exists(_.isDigit), s"the ID carries no digit: ${ids.head}")
    ids.head

  private def w(s: Int, e: Int): Option[(Int, Int)] = Some((s, e))

  /** (question, structured window). The comment names the shape under test. */
  private lazy val table: Vector[(String, Option[(Int, Int)])] = Vector(
    // since Y
    s"$ada since 2000?" -> w(2000, 2000),
    s"$ada since 2000?" -> w(2000, 9999),
    s"$ada since 2015?" -> w(2015, 2015),
    s"$ada since 2000?" -> w(2000, 2009),
    // in Y and Z
    s"$ada in 2000 and 2009?" -> w(2000, 2009),
    s"$ada in 2000 and 2009?" -> w(2000, 2000),
    s"$ada in 2000-2009 and 2012?" -> w(2000, 2009),
    // years the planner reads differently or not at all
    s"$ada in 1850?" -> w(2000, 2009),
    s"$ada in 2015 publications?" -> w(2000, 2009),
    s"$ada before 2010?" -> w(2000, 2009),
    s"$ada after 2000?" -> w(2000, 2009),
    s"$ada until 2009?" -> w(2000, 2009),
    s"$ada from 2000?" -> w(2000, 2000),
    s"$ada overall?" -> w(2000, 2009),
    s"$ada for all time?" -> w(2000, 2009),
    // between A and B, A-B, from A to B
    s"$ada between 2000 and 2009?" -> w(2000, 2009),
    s"$ada between 2000 and 2009?" -> w(2001, 2009),
    s"$ada in 2000-2009?" -> w(2000, 2009),
    s"$ada in 2000-2009?" -> w(2000, 2008),
    s"$ada in 2000–2009?" -> w(2000, 2009),
    s"$ada from 2000 to 2009?" -> w(2000, 2009),
    s"$ada from 2000 to 2009?" -> w(2000, 2010),
    s"$ada in 2000 through 2009?" -> w(2000, 2009),
    // single-year cues
    s"$ada during 2005?" -> w(2005, 2005),
    s"$ada during 2005?" -> w(2005, 2006),
    s"$ada in 2005?" -> w(2005, 2005),
    s"$ada for 2005?" -> w(2005, 2005),
    s"$ada of 2005?" -> w(2005, 2005),
    s"$ada in year 2005?" -> w(2005, 2005),
    s"$ada in 9999?" -> w(9999, 9999),
    // counts are not years
    "Who are the top 1000 coauthors of Ada Quillfeather?" -> w(2000, 2009),
    "Who are the top 5 coauthors of Ada Quillfeather?" -> w(2000, 2009),
    "Who are the first 2015 coauthors of Ada Quillfeather?" -> w(2000, 2009),
    s"$ada, limit of 1000?" -> w(2000, 2009),
    s"$ada for 1000 papers?" -> w(2000, 2009),
    // researcher IDs with digits
    s"How many distinct coauthors does $someId have?" -> w(2000, 2009),
    s"How many distinct coauthors does $someId have in 2000-2009?" ->
      w(2000, 2009),
    s"How many distinct coauthors does $someId have in 2001-2009?" ->
      w(2000, 2009),
    // no window in the question: the clause is appended
    s"$ada?" -> w(2000, 2009),
    s"$ada?" -> w(2005, 2005),
    // no structured window at all: nothing is echoed
    s"$ada in 2000-2009?" -> None,
    s"$ada since 2000?" -> None,
    s"$ada?" -> None
  )

  /** N-1: windows whose start year has fewer than four digits, plus the 4-digit
    * edges. The planner reads only four-digit years, so these are the shapes
    * where an echoed window can differ from an applied one.
    */
  private val edgeWindows: Vector[(Int, Int)] =
    Vector(
      (1, 2009),
      (500, 1500),
      (999, 2009),
      (1, 9999),
      (1000, 2009),
      (2000, 9999)
    )

  private def pad(y: Int): String = f"$y%04d"

  private val noWindowQuestion = s"$ada?"

  private lazy val edgeTable: Vector[(String, Option[(Int, Int)])] =
    edgeWindows.flatMap { (s, e) =>
      Vector(
        // no window in the question
        noWindowQuestion -> w(s, e),
        // a question that already carries the same window (padded and plain)
        s"$ada in ${pad(s)}-${pad(e)}?" -> w(s, e),
        s"$ada between ${pad(s)} and ${pad(e)}?" -> w(s, e),
        // a conflicting window in the question
        s"$ada in 2000-2009?" -> w(s, e),
        s"$ada in ${pad(s)}-${pad(e + (if e < 9999 then 1 else -1))}?" ->
          w(s, e),
        s"$ada since 2005?" -> w(s, e)
      )
    }

  private def windowBody(win: Option[(Int, Int)]): Map[String, ujson.Value] =
    win.fold(Map.empty[String, ujson.Value]) { (s, e) =>
      Map("window" -> ujson.Obj("startYear" -> s, "endYear" -> e))
    }

  /** A window from the wire: an object, or the one-element array upickle uses
    * for an `Option`.
    */
  private def readWindow(v: ujson.Value): Option[(Int, Int)] = v match
    case o: ujson.Obj
        if o.value.contains("startYear") && o.value.contains("endYear") =>
      Some((o("startYear").num.toInt, o("endYear").num.toInt))
    case a: ujson.Arr if a.value.size == 1 => readWindow(a.value.head)
    case _                                 => None

  private def plan(queryId: String): ujson.Value =
    val explained = api.get(s"/v1/explain/$queryId")
    assertEquals(explained.status, 200, explained.body)
    explained.json("queryPlan")

  test("the table is wide enough to mean something") {
    assert(table.size >= 25, table.size.toString)
    assert(table.exists(_._2.isEmpty))
    assert(table.exists(_._2.exists(_ == (2000, 2009))))
  }

  test(
    "a structured window is refused (422) or the planner applied exactly that window"
  ) {
    var refused = 0
    var applied = 0
    val failures = Vector.newBuilder[String]
    (table ++ edgeTable).foreach { (question, win) =>
      val label = s"[$question | $win]"
      val r = api.ask(question, windowBody(win))
      r.status match
        case 422 =>
          if win.isEmpty then failures += s"$label: 422 without a window"
          else if r.json("error")("code").str != "UNSUPPORTED_CONSTRAINT" then
            failures += s"$label: 422 with code ${r.json("error")("code").str}"
          else refused += 1
        case 200 =>
          val echoed = readWindow(r.json("request")("window"))
          if echoed != win then
            failures += s"$label: echoed $echoed, asked for $win"
          else
            val queryId = Json.queryId(r.json).getOrElse("")
            val p = plan(queryId)
            if win.isEmpty then
              // nothing was requested, so nothing is echoed; the planner
              // still has its own window and the response must not invent one
              assertEquals(r.json("request")("window"), ujson.Null, label)
            else
              val applied0 = p.objOpt.flatMap(o => o.value.get("window"))
              val appliedWindow = applied0.flatMap(readWindow)
              val second = p.objOpt
                .flatMap(o => o.value.get("comparisonWindow"))
                .flatMap(readWindow)
              if appliedWindow != win then
                failures +=
                  s"$label: echoed $echoed but the planner applied $appliedWindow (plan $p)"
              else if second.isDefined then
                failures +=
                  s"$label: the planner also read a second window $second"
              else applied += 1
        case other =>
          failures += s"$label: status $other ${r.body.take(200)}"
    }
    val bad = failures.result()
    assertEquals(bad, Vector.empty[String], bad.mkString("\n"))
    // neither side of the invariant may be vacuous
    assert(refused >= 8, s"only $refused requests were refused")
    assert(applied >= 8, s"only $applied requests applied the echoed window")
  }

  test("N-1: the edge table has all six windows in six shapes each") {
    assertEquals(edgeWindows.size, 6)
    assertEquals(edgeTable.size, 36)
    assertEquals(edgeTable.map(_._2).distinct.size, 6)
  }

  test(
    "N-1: a window with no window in the question is applied, never refused"
  ) {
    val failures = Vector.newBuilder[String]
    var checked = 0
    edgeWindows.foreach { (s, e) =>
      val r = api.ask(noWindowQuestion, windowBody(w(s, e)))
      val label = s"[($s, $e)]"
      if r.status != 200 then
        failures += s"$label: status ${r.status} (over-refusal) ${r.body.take(200)}"
      else
        val echoed = readWindow(r.json("request")("window"))
        val appliedWindow = plan(Json.queryId(r.json).getOrElse("")).objOpt
          .flatMap(_.value.get("window"))
          .flatMap(readWindow)
        if echoed != w(s, e) then failures += s"$label: echoed $echoed"
        else if appliedWindow != w(s, e) then
          failures += s"$label: planner applied $appliedWindow"
        else checked += 1
    }
    val bad = failures.result()
    assertEquals(bad, Vector.empty[String], bad.mkString("\n"))
    assertEquals(checked, 6)
  }

  test("a window the planner reads in the question is the window it applies") {
    // the control that proves the plan is readable: no structured window, the
    // planner takes it from the question text alone
    val r = api.ask(s"$ada in 2000-2009?")
    assertEquals(r.status, 200, r.body)
    val p = plan(Json.queryId(r.json).get)
    assertEquals(
      p.objOpt.flatMap(_.value.get("window")).flatMap(readWindow),
      w(2000, 2009)
    )
    assertEquals(r.json("request")("window"), ujson.Null)
  }
