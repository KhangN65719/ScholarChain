package scholarchain.rag

import scala.collection.mutable
import scholarchain.storage.*
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.neptunedata.model.Parallelism

/** The `--tolerate-load-errors` option of the AWS loader and the policy it
  * applies, against stubbed ports. No AWS call is made: the loaders here return
  * a prepared `BulkLoadResult` (what the storage loader returns with
  * `failOnError` false) or throw `BulkLoadFailed`.
  */
class AwsIngestLoadErrorSuite extends munit.FunSuite:
  import AwsLoaderFixtures.*

  private val Tolerate = AwsIngestMain.TolerateFlag
  private val GraphOnly = AwsIngestMain.GraphOnlyFlag
  private val File = "edges-COAUTHORED_WITH.csv"
  private val Sentence =
    "The graph loaded with %d errors; the named records were skipped by " +
      "Neptune. This is NOT a clean load."

  private def results(lines: Vector[String]): Vector[String] =
    lines.filter(_.startsWith("LOADER_RESULT="))

  private def record(
      file: String,
      number: Int,
      message: String = "Cannot find the end vertex",
      code: String = "FROM_OR_TO_VERTEX_ARE_MISSING"
  ): LoadErrorRecord =
    LoadErrorRecord(code, file, number.toString, message)

  /** One job of `records` Neptune records with `errors` insert errors, of which
    * `named` are listed.
    */
  private def job(
      file: String,
      records: Long,
      errors: Long,
      named: Option[Int] = None,
      truncated: Boolean = false,
      message: String = "Cannot find the end vertex",
      code: String = "FROM_OR_TO_VERTEX_ARE_MISSING",
      status: String = "LOAD_COMPLETED"
  ): LoaderJobReport =
    val listed = named.getOrElse(errors.toInt)
    LoaderJobReport(
      s"s3://sc-data-test/graph/neptune/$file",
      s"load-$file",
      status,
      LoaderCounts(records, 0L, 0L, 0L, errors),
      Vector.empty,
      Vector.tabulate(listed)(i => record(file, i + 1, message, code)),
      truncated
    )

  private final class StubLoader(jobs: Vector[LoaderJobReport])
      extends NeptuneBulkLoader:
    def load(manifestKey: String): BulkLoadResult =
      BulkLoadResult(1, 0, 0, jobs)

  private def stub(jobs: LoaderJobReport*): NeptuneBulkLoader =
    new StubLoader(jobs.toVector)

  private def go(
      loader: NeptuneBulkLoader,
      options: Vector[String]
  ): (World, Int, Vector[String]) =
    val world = new World()
    val (code, lines) = world.run(
      world.backend(loader = loader),
      args = (world.runId +: options).toArray
    )
    (world, code, lines)

  private def graphOnly(loader: NeptuneBulkLoader) =
    go(loader, Vector(Tolerate, GraphOnly))

  private def graphReport(world: World): ujson.Value =
    world.json(s"${world.config.reportBase}/${AwsIngestMain.GraphReportName}")

  private def graphRun(world: World): ujson.Value =
    world.json(s"${world.config.reportBase}/${AwsIngestMain.GraphRunName}")

  // --- flag parsing ---------------------------------------------------------

  test("the flag is accepted alone, with graph-only, in either order") {
    val plain = AwsIngestMain.parseConfig(Array("run-1"), validEnv)
    assert(!plain.tolerateLoadErrors)
    val alone = AwsIngestMain.parseConfig(Array("run-1", Tolerate), validEnv)
    assert(alone.tolerateLoadErrors && !alone.graphOnly)
    Vector(
      Array("run-1", GraphOnly, Tolerate),
      Array(Tolerate, "run-1", GraphOnly),
      Array(Tolerate, GraphOnly, "run-1")
    ).foreach { args =>
      val config = AwsIngestMain.parseConfig(args, validEnv)
      assert(config.tolerateLoadErrors && config.graphOnly, args.mkString(" "))
      assertEquals(config.runId, "run-1")
      assertEquals(config.exportPrefix, plain.exportPrefix)
    }
    assert(
      !AwsIngestMain
        .parseConfig(Array("run-1", GraphOnly), validEnv)
        .tolerateLoadErrors
    )
  }

  test("a bad command line with the flag is refused before any AWS call") {
    val bad = Vector(
      Array("run-1", Tolerate, Tolerate),
      Array("run-1", Tolerate, GraphOnly, Tolerate),
      Array("run-1", s"$Tolerate=true"),
      Array("run-1", "--tolerate"),
      Array("run-1", Tolerate, "--force"),
      Array("run-1", "run-2", Tolerate),
      Array(Tolerate),
      Array(Tolerate, GraphOnly)
    )
    bad.foreach { args =>
      var built = 0
      val lines = mutable.ArrayBuffer.empty[String]
      val code = AwsIngestMain.run(
        args,
        validEnv,
        lines += _,
        _ =>
          built += 1
          throw new AssertionError("no wiring for a refused command line")
      )
      val shown = args.mkString(" ")
      assertEquals(code, 2, shown)
      assertEquals(built, 0, shown)
      assertEquals(lines.size, 1, shown)
      assert(
        lines.head.startsWith("LOADER_RESULT=FAILED reason=InvalidQuery: "),
        lines.head
      )
      assert(lines.head.contains(AwsIngestMain.Usage), lines.head)
    }
    assert(
      AwsIngestMain.Usage.contains(Tolerate) &&
        AwsIngestMain.Usage.contains(GraphOnly)
    )
  }

  test("the flag without a Neptune host is refused before any AWS call") {
    val env = validEnv - "SCHOLARCHAIN_NEPTUNE_HOST" -
      "SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN"
    Vector(Array("run-1", Tolerate), Array("run-1", Tolerate, GraphOnly))
      .foreach { args =>
        val error = intercept[StorageException](
          AwsIngestMain.parseConfig(args, env)
        )
        assert(
          error.getMessage.contains("SCHOLARCHAIN_NEPTUNE_HOST"),
          error.getMessage
        )
        var built = 0
        val lines = mutable.ArrayBuffer.empty[String]
        val code = AwsIngestMain.run(
          args,
          env,
          lines += _,
          _ =>
            built += 1
            throw new AssertionError("no wiring without a host")
        )
        assertEquals(code, 2, args.mkString(" "))
        assertEquals(built, 0)
        assertEquals(lines.size, 1)
      }
    // Without the flag, no host is still the vectors-only run.
    assert(
      !AwsIngestMain.parseConfig(Array("run-1"), env).tolerateLoadErrors
    )
  }

  private def bulkLoaderField(loader: NeptuneBulkLoader, name: String): Any =
    val field = classOf[AwsNeptuneBulkLoader].getDeclaredField(name)
    field.setAccessible(true)
    field.get(loader)

  test("the wiring builds the bulk loader with failOnError from the flag") {
    Vector(Array("run-1") -> true, Array("run-1", Tolerate) -> false).foreach {
      case (args, failOnError) =>
        val config = AwsIngestMain.parseConfig(args, validEnv)
        val wiring = AwsIngestMain.awsWiring(
          config,
          region => new AwsClients(Region.of(region))
        )
        try
          val loader = wiring.backend.graph.get.loader
          assert(loader.isInstanceOf[AwsNeptuneBulkLoader])
          assertEquals(
            bulkLoaderField(loader, "failOnError"),
            failOnError,
            args.mkString(" ")
          )
          assertEquals(
            bulkLoaderField(loader, "edgeParallelism"),
            Parallelism.LOW
          )
        finally wiring.close()
    }
  }

  // --- the policy -----------------------------------------------------------

  test("the threshold is errors * 1000 <= records in integers, no overflow") {
    import LoadErrorPolicy.withinThreshold
    assert(withinThreshold(0L, 0L))
    assert(!withinThreshold(1L, 0L))
    assert(!withinThreshold(1L, 999L))
    assert(withinThreshold(1L, 1000L))
    assert(withinThreshold(1L, 1999L))
    assert(!withinThreshold(2L, 1999L))
    assert(withinThreshold(100L, 100000L))
    assert(!withinThreshold(101L, 100000L))
    assert(!withinThreshold(Long.MaxValue, Long.MaxValue))
    assert(withinThreshold(Long.MaxValue / 1000L, Long.MaxValue))
  }

  private final case class Case(
      name: String,
      loader: NeptuneBulkLoader,
      tolerated: Boolean,
      neptune: String,
      last: String,
      why: String = ""
  )

  private val under = job(File, 200000L, 3L)
  private val cases = Vector(
    Case(
      "no errors",
      stub(job(File, 200000L, 0L)),
      tolerated = false,
      "NEPTUNE_LOAD=LOADED ",
      "LOADER_RESULT=SUCCESS"
    ),
    Case(
      "under the threshold, all named",
      stub(under),
      tolerated = true,
      "NEPTUNE_LOAD=LOADED_WITH_ERRORS errors=3 records=200000 named=3 " +
        "threshold=0.1%",
      "LOADER_RESULT=SUCCEEDED_WITH_WARNINGS warnings=graph-load-errors:3"
    ),
    Case(
      "exactly at the threshold",
      stub(job(File, 100000L, 100L)),
      tolerated = true,
      "NEPTUNE_LOAD=LOADED_WITH_ERRORS errors=100 records=100000 named=100 " +
        "threshold=0.1%",
      "LOADER_RESULT=SUCCEEDED_WITH_WARNINGS warnings=graph-load-errors:100"
    ),
    Case(
      "just over the threshold",
      stub(job(File, 100000L, 101L)),
      tolerated = false,
      "NEPTUNE_LOAD=FAILED reason=",
      "LOADER_RESULT=FAILED reason=the Neptune load failed: ",
      "over the 0.1% tolerance"
    ),
    Case(
      "errors truncated",
      stub(job(File, 200000L, 3L, truncated = true)),
      tolerated = false,
      "NEPTUNE_LOAD=FAILED reason=",
      "LOADER_RESULT=FAILED reason=the Neptune load failed: ",
      "the error list is incomplete"
    ),
    Case(
      "not every error named",
      stub(job(File, 200000L, 5L, named = Some(2))),
      tolerated = false,
      "NEPTUNE_LOAD=FAILED reason=",
      "LOADER_RESULT=FAILED reason=the Neptune load failed: ",
      "only 2 were named"
    ),
    Case(
      "errors in a load of no records",
      stub(job(File, 0L, 1L)),
      tolerated = false,
      "NEPTUNE_LOAD=FAILED reason=",
      "LOADER_RESULT=FAILED reason=the Neptune load failed: ",
      "over the 0.1% tolerance"
    ),
    Case(
      "a genuine job failure",
      new FailingLoader,
      tolerated = false,
      "NEPTUNE_LOAD=FAILED reason=",
      "LOADER_RESULT=FAILED reason=the Neptune load failed: ",
      "LOAD_FAILED"
    )
  )

  cases.foreach { c =>
    test(s"policy table, graph-only: ${c.name}") {
      val (world, code, lines) = graphOnly(c.loader)
      val text = lines.mkString("\n")
      assertEquals(results(lines).size, 1, text)
      assertEquals(lines.last, results(lines).head)
      assert(lines.last.startsWith(c.last), text)
      assertEquals(code, if c.last.contains("FAILED") then 1 else 0, text)
      assertEquals(lines.count(_.startsWith("NEPTUNE_LOAD=")), 1, text)
      assert(
        lines.exists(l =>
          l.startsWith("NEPTUNE_LOAD=") && l.startsWith(c.neptune)
        ),
        text
      )
      assert(lines.last.contains(c.why) || c.why.isEmpty, text)
      val report = graphReport(world)
      assertEquals(
        report("result").str,
        lines.last.stripPrefix("LOADER_RESULT=").takeWhile(_ != ' ')
      )
      if c.tolerated then
        assertEquals(report("graph")("status").str, "LOADED_WITH_ERRORS")
      assertEquals(
        lines.exists(_.startsWith("NEPTUNE_LOAD_SKIPPED ")),
        c.tolerated,
        text
      )
    }
  }

  test("the threshold is judged on the total of all jobs") {
    // The first file alone is 0.5% errors; over all jobs it is 0.0005%.
    val (_, code, lines) = graphOnly(
      stub(job("edges-A.csv", 1000L, 5L), job("edges-B.csv", 999000L, 0L))
    )
    assertEquals(code, 0, lines.mkString("\n"))
    assert(
      lines.contains(
        "NEPTUNE_LOAD=LOADED_WITH_ERRORS errors=5 records=1000000 named=5 " +
          "threshold=0.1%"
      ),
      lines.mkString("\n")
    )
    // Two files each 0.1% make exactly 0.1% together; one more error is over.
    val (_, atCode, _) = graphOnly(
      stub(job("edges-A.csv", 60000L, 60L), job("edges-B.csv", 60000L, 60L))
    )
    assertEquals(atCode, 0)
    val (_, overCode, _) = graphOnly(
      stub(job("edges-A.csv", 60000L, 60L), job("edges-B.csv", 60000L, 61L))
    )
    assertEquals(overCode, 1)
    // Errors in different jobs, one of them not fully named: refused.
    val (_, unnamedCode, _) = graphOnly(
      stub(
        job("edges-A.csv", 500000L, 2L),
        job("edges-B.csv", 500000L, 2L, named = Some(1))
      )
    )
    assertEquals(unnamedCode, 1)
  }

  // --- output, reports and wording -----------------------------------------

  test("a tolerated load prints each named record and one summary, no dupes") {
    val (_, code, lines) = graphOnly(
      stub(job(File, 200000L, 3L), job("edges-AUTHORED.csv", 200000L, 1L))
    )
    val text = lines.mkString("\n")
    assertEquals(code, 0, text)
    val skipped = lines.filter(_.startsWith("NEPTUNE_LOAD_SKIPPED "))
    assertEquals(skipped.size, 4, text)
    assertEquals(
      skipped.head,
      s"NEPTUNE_LOAD_SKIPPED file=$File code=FROM_OR_TO_VERTEX_ARE_MISSING " +
        "record=1 message=Cannot find the end vertex"
    )
    // Storage's live lines are its own; this loader adds none in that form.
    assert(!lines.exists(_.startsWith("NEPTUNE_LOAD_ERROR")), text)
    assertEquals(
      lines.filter(_.startsWith("NEPTUNE_LOAD_SKIPPED_SUMMARY ")),
      Vector(
        "NEPTUNE_LOAD_SKIPPED_SUMMARY total=4 insert=4 parsing=0 datatype=0 " +
          "records=400000 named=4 " +
          "unit=neptune-property-records-not-csv-lines " +
          s"by_file=edges-AUTHORED.csv:1,$File:3 " +
          "by_code=FROM_OR_TO_VERTEX_ARE_MISSING:4"
      )
    )
    assertEquals(
      lines.filter(_.startsWith("LOADER_WARNING ")),
      Vector("LOADER_WARNING " + Sentence.format(4))
    )
    assertEquals(
      lines.last,
      "LOADER_RESULT=SUCCEEDED_WITH_WARNINGS warnings=graph-load-errors:4"
    )
    assert(lines.exists(_.startsWith("NEPTUNE_JOB ")), text)
    assert(!text.contains("NEPTUNE_LOAD=LOADED "), text)
  }

  test("graph-report and graph-run carry the result with its errors") {
    val (world, code, lines) = graphOnly(stub(under))
    assertEquals(code, 0, lines.mkString("\n"))
    Vector(graphReport(world), graphRun(world)).foreach { body =>
      assertEquals(body("result").str, "SUCCEEDED_WITH_WARNINGS")
      assertEquals(body("warning").str, Sentence.format(3))
      assertEquals(
        body("warnings").arr.map(_.str).toVector,
        Vector("graph-load-errors:3")
      )
      assertEquals(body("loaderMode").str, "graph-only")
      val graph = body("graph")
      assertEquals(graph("status").str, "LOADED_WITH_ERRORS")
      assertEquals(graph("warning").str, Sentence.format(3))
      val errors = graph("errors")
      assertEquals(errors("insertErrors").num.toLong, 3L)
      assertEquals(errors("parsingErrors").num.toLong, 0L)
      assertEquals(errors("datatypeMismatchErrors").num.toLong, 0L)
      assertEquals(errors("totalErrors").num.toLong, 3L)
      assertEquals(errors("totalRecords").num.toLong, 200000L)
      assertEquals(errors("thresholdPercent").num, 0.1)
      assertEquals(
        errors("recordUnit").str,
        "Neptune records (property-level, not CSV lines)"
      )
      assertEquals(errors("errorsTruncated").bool, false)
      assertEquals(errors("allErrorsNamed").bool, true)
      val named = errors("errorRecords").arr
      assertEquals(named.size, 3)
      assertEquals(
        named.head.obj.keys.toVector,
        Vector("code", "file", "record", "message")
      )
      assertEquals(named.head("file").str, File)
      assertEquals(named.head("record").str, "1")
      assertEquals(named.map(_("record").str).toVector, Vector("1", "2", "3"))
    }
    assertEquals(world.store.head(world.reportKey), None)
  }

  test("the word clean appears only in the negated sentence") {
    val (world, code, lines) = graphOnly(stub(under))
    assertEquals(code, 0)
    val everything = lines.mkString("\n") + ujson.write(graphReport(world)) +
      ujson.write(graphRun(world))
    assert(everything.contains("This is NOT a clean load."))
    val rest = everything.replace("This is NOT a clean load.", "")
    assert(!rest.toLowerCase.contains("clean"), rest)
  }

  test("account IDs in an error record are masked in lines and reports") {
    val arn = "arn:aws:iam::123456789012:role/loader"
    val (world, code, lines) =
      graphOnly(stub(job(File, 200000L, 2L, message = s"denied for $arn")))
    assertEquals(code, 0, lines.mkString("\n"))
    val everything = lines.mkString("\n") + ujson.write(graphReport(world)) +
      ujson.write(graphRun(world))
    assert(!everything.contains("123456789012"), everything)
    assert(everything.contains("<account-id>"), everything)
  }

  test("a rejected load stays FAILED and its report keeps the named errors") {
    val (world, code, lines) =
      graphOnly(stub(job(File, 100000L, 101L)))
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assert(!text.contains("LOADED_WITH_ERRORS"), text)
    assert(!text.contains("SUCCEEDED_WITH_WARNINGS"), text)
    val report = graphReport(world)
    assertEquals(report("result").str, "FAILED")
    assert(report("reason").str.contains("101 errors in 100000 records"))
    assertEquals(report("graph")("status").str, "FAILED")
    assertEquals(report("graph")("errors")("totalErrors").num.toLong, 101L)
    assertEquals(report("graph")("errors")("errorRecords").arr.size, 101)
    assert(!report.obj.contains("warning"))
    assertEquals(world.neptune.vertices.size, 0)
  }

  // --- unchanged without the flag, and the full run -------------------------

  test("without the flag the graph step is judged as it always was") {
    val world = new World()
    val (code, lines) = world.run(
      world.backend(loader = stub(job(File, 100000L, 101L))),
      args = Array(world.runId)
    )
    assertEquals(code, 0, lines.mkString("\n"))
    assertEquals(lines.last, "LOADER_RESULT=SUCCESS")
    assert(lines.exists(_.startsWith("NEPTUNE_LOAD=LOADED ")))
    assert(!lines.exists(_.startsWith("NEPTUNE_LOAD_SKIPPED")))
    val report = world.json(world.reportKey)
    assertEquals(report("result").str, "SUCCESS")
    assertEquals(report("graph")("status").str, "LOADED")
    assert(!report("graph").obj.contains("errors"))
    assert(!report.obj.contains("warning"))
  }

  test("the flag with no load errors writes the same ingest report as none") {
    val plain = new World()
    assertEquals(plain.run()._1, 0)
    val flagged = new World()
    val (code, lines) =
      flagged.run(args = Array(flagged.runId, Tolerate))
    assertEquals(code, 0, lines.mkString("\n"))
    assertEquals(lines.last, "LOADER_RESULT=SUCCESS")
    assert(lines.exists(_.startsWith("NEPTUNE_LOAD=LOADED ")))
    assertEquals(
      flagged.store.get(flagged.reportKey).get.toVector,
      plain.store.get(plain.reportKey).get.toVector
    )
    assert(!flagged.json(flagged.reportKey).obj.contains("warning"))
  }

  test(
    "a full run with the flag and tolerated errors is SUCCEEDED_WITH_WARNINGS"
  ) {
    val (world, code, lines) = go(stub(under), Vector(Tolerate))
    val text = lines.mkString("\n")
    assertEquals(code, 0, text)
    assertEquals(
      lines.last,
      "LOADER_RESULT=SUCCEEDED_WITH_WARNINGS warnings=graph-load-errors:3"
    )
    assertEquals(results(lines).size, 1, text)
    assert(lines.contains("INGEST_STEP=SUCCESS"), text)
    assert(lines.exists(_.startsWith("LOADER_TOTAL ")), text)
    assert(
      lines.contains(
        "NEPTUNE_LOAD=LOADED_WITH_ERRORS errors=3 records=200000 named=3 " +
          "threshold=0.1%"
      ),
      text
    )
    val report = world.json(world.reportKey)
    val run = world.json(world.runKey)
    Vector(report, run).foreach { body =>
      assertEquals(body("result").str, "SUCCEEDED_WITH_WARNINGS")
      assertEquals(body("graph")("status").str, "LOADED_WITH_ERRORS")
      assertEquals(body("graph")("errors")("totalErrors").num.toLong, 3L)
      assertEquals(body("warning").str, Sentence.format(3))
    }
    assertEquals(report("embeddingModel").str, TitanEmbedder.ModelVersion)
    assertEquals(report("vectorsTotal").num.toInt, 12)
    assertEquals(report("scope").str, "vectors-and-graph")
    val everything = text + ujson.write(report) + ujson.write(run)
    assert(
      !everything.replace("This is NOT a clean load.", "").contains("clean")
    )
  }

  test("a full run with the flag and too many errors is FAILED") {
    val (world, code, lines) =
      go(stub(job(File, 100000L, 101L)), Vector(Tolerate))
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assertEquals(results(lines).size, 1, text)
    assert(
      lines.last.startsWith(
        "LOADER_RESULT=FAILED reason=the Neptune load failed"
      ),
      text
    )
    assert(text.contains("INGEST_STEP=SUCCESS"), text)
    val report = world.json(world.reportKey)
    assertEquals(report("result").str, "FAILED")
    assertEquals(report("graph")("status").str, "FAILED")
    assertEquals(report("scope").str, "vectors-only")
    assertEquals(world.bucket.count(VectorIndexes.ResearcherProfile), 4)
  }

  test("a full run with the flag and a failed job is FAILED") {
    val (world, code, lines) = go(new FailingLoader, Vector(Tolerate))
    assertEquals(code, 1, lines.mkString("\n"))
    assert(lines.last.startsWith("LOADER_RESULT=FAILED"), lines.last)
    assertEquals(world.json(world.reportKey)("graph")("status").str, "FAILED")
  }

  // --- error codes ----------------------------------------------------------

  private val NeverTolerated = Vector(
    "OUT_OF_MEMORY_ERROR",
    "S3_ACCESS_DENIED_ERROR",
    "FILE_MODIFICATION_OR_DELETION_ERROR"
  )

  private val Tolerated = Vector(
    "PARSING_ERROR",
    "FROM_OR_TO_VERTEX_ARE_MISSING",
    "ID_ASSIGNED_TO_MULTIPLE_EDGES",
    "SINGLE_CARDINALITY_VIOLATION",
    "INTERNAL_ERROR"
  )

  private def assertRejected(
      loader: NeptuneBulkLoader,
      mention: String
  ): World =
    val (world, code, lines) = graphOnly(loader)
    val text = lines.mkString("\n")
    assertEquals(code, 1, text)
    assertEquals(results(lines).size, 1, text)
    assert(lines.last.startsWith("LOADER_RESULT=FAILED reason="), text)
    assert(lines.last.contains(mention), lines.last)
    assertEquals(lines.count(_.startsWith("NEPTUNE_LOAD=FAILED")), 1, text)
    assert(!text.contains("LOADED_WITH_ERRORS"), text)
    assert(!lines.exists(_.startsWith("NEPTUNE_LOAD_SKIPPED")), text)
    val report = graphReport(world)
    assertEquals(report("result").str, "FAILED")
    assertEquals(report("graph")("status").str, "FAILED")
    assert(report("graph")("reason").str.contains(mention))
    world

  NeverTolerated.foreach { code =>
    test(s"error code $code is never tolerated, even within the threshold") {
      val world =
        assertRejected(stub(job(File, 200000L, 2L, code = code)), code)
      // The rejected report keeps the code that decided it.
      assertEquals(
        graphReport(world)("graph")("errors")("codes")(code).num.toInt,
        2
      )
    }
  }

  test("an unknown error code is not tolerated") {
    assertRejected(
      stub(job(File, 200000L, 1L, code = "SOME_NEW_ERROR")),
      "SOME_NEW_ERROR"
    )
  }

  Tolerated.foreach { code =>
    test(s"error code $code is tolerated within the threshold") {
      val (world, exit, lines) =
        graphOnly(stub(job(File, 200000L, 2L, code = code)))
      val text = lines.mkString("\n")
      assertEquals(exit, 0, text)
      assertEquals(
        lines.last,
        "LOADER_RESULT=SUCCEEDED_WITH_WARNINGS warnings=graph-load-errors:2"
      )
      assert(text.contains(s"NEPTUNE_LOAD_SKIPPED file=$File code=$code "))
      val report = graphReport(world)
      assertEquals(report("graph")("status").str, "LOADED_WITH_ERRORS")
      assertEquals(
        report("graph")("errors")("codes").obj.map((k, v) => k -> v.num.toInt),
        mutable.LinkedHashMap(code -> 2)
      )
    }
  }

  test("a tolerated code beside a never-tolerated one is FAILED") {
    val mixed = job(
      File,
      200000L,
      3L,
      code = "FROM_OR_TO_VERTEX_ARE_MISSING"
    ).copy(errorRecords =
      Vector(
        record(File, 1),
        record(File, 2, code = "PARSING_ERROR"),
        record(File, 3, code = "OUT_OF_MEMORY_ERROR")
      )
    )
    val world = assertRejected(stub(mixed), "OUT_OF_MEMORY_ERROR")
    val codes = graphReport(world)("graph")("errors")("codes")
    assertEquals(codes("FROM_OR_TO_VERTEX_ARE_MISSING").num.toInt, 1)
    assertEquals(codes("PARSING_ERROR").num.toInt, 1)
    assertEquals(codes("OUT_OF_MEMORY_ERROR").num.toInt, 1)
  }

  test("INTERNAL_ERROR records are named as unclassified in line and report") {
    val unclassified =
      "2 records were reported by Neptune as INTERNAL_ERROR, i.e. unclassified."
    val (world, code, lines) = graphOnly(
      stub(
        job(File, 200000L, 2L, code = "INTERNAL_ERROR"),
        job("edges-AUTHORED.csv", 200000L, 1L)
      )
    )
    assertEquals(code, 0, lines.mkString("\n"))
    val warning = lines.filter(_.startsWith("LOADER_WARNING ")).head
    assert(warning.contains("3 errors"), warning)
    assert(warning.contains(unclassified), warning)
    assert(warning.endsWith("This is NOT a clean load."), warning)
    Vector(graphReport(world), graphRun(world)).foreach { body =>
      assert(body("warning").str.contains(unclassified))
      assert(body("graph")("warning").str.contains(unclassified))
      assertEquals(
        body("graph")("errors")("codes")("INTERNAL_ERROR").num.toInt,
        2
      )
    }
    // Without such records the sentence says nothing about INTERNAL_ERROR.
    val (other, _, _) = graphOnly(stub(under))
    assert(!graphReport(other)("warning").str.contains("INTERNAL_ERROR"))
    assertEquals(graphReport(other)("warning").str, Sentence.format(3))
  }

  // --- job statuses ---------------------------------------------------------

  test("jobStatuses lists every job by base name, with its final status") {
    val (world, code, lines) = graphOnly(
      stub(job("edges-A.csv", 200000L, 2L), job("vertices-B.csv", 10L, 0L))
    )
    assertEquals(code, 0, lines.mkString("\n"))
    val statuses = graphReport(world)("graph")("errors")("jobStatuses").arr
    assertEquals(
      statuses.map(s => (s("source").str, s("status").str)).toVector,
      Vector(
        "edges-A.csv" -> "LOAD_COMPLETED",
        "vertices-B.csv" -> "LOAD_COMPLETED"
      )
    )
    assertEquals(
      graphRun(world)("graph")("errors")("jobStatuses").arr.size,
      2
    )
    assert(!lines.exists(_.startsWith("NEPTUNE_JOB_STATUS")), lines.mkString)
  }

  test("a LOAD_FAILED job with record-level errors is tolerated and says so") {
    val failed =
      job(File, 200000L, 2L, status = "LOAD_FAILED")
    val (world, code, lines) =
      graphOnly(stub(failed, job("edges-AUTHORED.csv", 200000L, 0L)))
    val text = lines.mkString("\n")
    assertEquals(code, 0, text)
    assertEquals(
      lines.last,
      "LOADER_RESULT=SUCCEEDED_WITH_WARNINGS warnings=graph-load-errors:2"
    )
    assertEquals(
      lines.filter(_.startsWith("NEPTUNE_JOB_STATUS")),
      Vector(s"NEPTUNE_JOB_STATUS source=$File status=LOAD_FAILED")
    )
    assert(
      lines.exists(l =>
        l.startsWith("LOADER_WARNING ") && l.contains(UnverifiedSentence)
      )
    )
    Vector(graphReport(world), graphRun(world)).foreach { body =>
      assertEquals(body("graph")("status").str, "LOADED_WITH_ERRORS")
      assertEquals(body("result").str, "SUCCEEDED_WITH_WARNINGS")
      assert(
        body("warning").str.contains(UnverifiedSentence),
        body("warning").str
      )
      assertEquals(
        body("graph")("errors")("jobStatuses").arr
          .map(s => s("status").str)
          .toVector,
        Vector("LOAD_FAILED", "LOAD_COMPLETED")
      )
    }
    val everything = text + ujson.write(graphReport(world)) +
      ujson.write(graphRun(world))
    assert(
      !everything.replace("This is NOT a clean load.", "").contains("clean")
    )
  }

  private val UnverifiedSentence =
    "1 job(s) ended LOAD_FAILED; Neptune reported only record-level errors " +
      "for them, but it is UNVERIFIED whether the rest of each file was " +
      "loaded; compare the NEPTUNE_JOB records= count of each job."

  test("a tolerated LOAD_FAILED job claims no skipped records, anywhere") {
    val (world, code, lines) = go(
      stub(
        job(File, 200000L, 2L, status = "LOAD_FAILED"),
        job("edges-AUTHORED.csv", 200000L, 0L)
      ),
      Vector(Tolerate)
    )
    val text = lines.mkString("\n")
    assertEquals(code, 0, text)
    val scope = lines.filter(_.startsWith("LOADER_SCOPE="))
    assertEquals(
      scope,
      Vector(
        "LOADER_SCOPE=vectors-and-graph (the graph load ended WITH ERRORS; " +
          UnverifiedSentence.stripSuffix(".") + ")"
      )
    )
    val warning = lines.filter(_.startsWith("LOADER_WARNING ")).head
    assert(warning.contains(UnverifiedSentence), warning)
    assert(warning.endsWith("This is NOT a clean load."), warning)
    // Records of the LOAD_FAILED job are named, not claimed skipped.
    assertEquals(
      lines.count(_.startsWith("NEPTUNE_LOAD_NAMED_UNVERIFIED file=")),
      2,
      text
    )
    assert(!lines.exists(_.startsWith("NEPTUNE_LOAD_SKIPPED ")), text)
    assert(
      lines.exists(l =>
        l.startsWith("NEPTUNE_LOAD_SKIPPED_SUMMARY ") &&
          l.endsWith(" load_completeness=UNVERIFIED failed_jobs=1")
      ),
      text
    )
    val report = world.json(world.reportKey)
    val run = world.json(world.runKey)
    Vector(report, run).foreach { body =>
      assertEquals(body("graph")("status").str, "LOADED_WITH_ERRORS")
      assertEquals(
        body("graph")("errors")("loadCompletenessVerified").bool,
        false
      )
      assert(body("warning").str.contains(UnverifiedSentence))
      assert(body("graph")("warning").str.contains(UnverifiedSentence))
    }
    val everything = text + ujson.write(report) + ujson.write(run)
    Vector("were skipped", "Neptune skipped", "were skipped by Neptune")
      .foreach { claim =>
        assert(!everything.contains(claim), claim)
      }
    assert(
      !everything.replace("This is NOT a clean load.", "").contains("clean")
    )
  }

  test("loadCompletenessVerified is absent when every job is LOAD_COMPLETED") {
    val (world, code, lines) = graphOnly(stub(under))
    assertEquals(code, 0, lines.mkString("\n"))
    Vector(graphReport(world), graphRun(world)).foreach { body =>
      assert(!body("graph")("errors").obj.contains("loadCompletenessVerified"))
      assert(!ujson.write(body).contains("UNVERIFIED"))
    }
    assert(!lines.exists(_.contains("UNVERIFIED")), lines.mkString("\n"))
    assert(lines.exists(_.startsWith("NEPTUNE_LOAD_SKIPPED file=")))
    // A rejected load never claims the field either.
    val (rejected, _, _) = graphOnly(stub(job(File, 100000L, 101L)))
    assert(
      !graphReport(rejected)("graph")("errors").obj
        .contains("loadCompletenessVerified")
    )
  }

  test(
    "a returned job with another status, or LOAD_FAILED without errors, fails"
  ) {
    Vector(
      job(File, 200000L, 2L, status = "LOAD_CANCELLED_BY_USER"),
      job(File, 200000L, 2L, status = "LOAD_COMMITTED_W_WRITE_CONFLICTS"),
      job(File, 200000L, 0L, status = "LOAD_FAILED")
    ).foreach { bad =>
      val (world, code, lines) = graphOnly(stub(bad))
      val text = lines.mkString("\n")
      assertEquals(code, 1, text)
      assert(lines.last.contains("the load job " + File + " ended"), text)
      assert(
        lines.contains(
          s"NEPTUNE_JOB_STATUS source=$File status=${bad.status}"
        ),
        text
      )
      assertEquals(graphReport(world)("graph")("status").str, "FAILED")
    }
  }
