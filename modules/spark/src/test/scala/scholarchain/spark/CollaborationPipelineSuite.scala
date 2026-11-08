package scholarchain.spark

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.apache.spark.sql.{DataFrame, Row}
import scala.jdk.CollectionConverters.*
import scholarchain.core.{ContractSchema, Provenance, SourceType}
import scholarchain.spark.Fixtures.{label, string, strings}

class CollaborationPipelineSuite extends SparkSuite:
  private final case class Outcome(code: Int, output: String, runDir: Path)

  private val tableNames = Set(
    "nodes-researcher",
    "nodes-work",
    "nodes-venue",
    "nodes-topic",
    "nodes-institution",
    "edges-authored",
    "edges-published-in",
    "edges-coauthored",
    "edge-support",
    "researcher-metrics",
    "component-membership",
    "components",
    "component-summary",
    "community-membership",
    "communities",
    "community-cross-edges",
    "bridge",
    "coreness",
    "path-evidence",
    "path-length-estimates",
    "temporal-comparison",
    "temporal-window-summary",
    "semantic-summaries",
    "unresolved-mentions",
    "integrity-violations"
  )
  private val windowed = tableNames -- Set(
    "nodes-researcher",
    "nodes-work",
    "nodes-venue",
    "nodes-topic",
    "nodes-institution",
    "edges-authored",
    "edges-published-in",
    "temporal-comparison",
    "temporal-window-summary",
    "unresolved-mentions",
    "integrity-violations"
  )
  private val windowSpec = "1990-1999,2000-2004,2005-2009,all"

  private lazy val sharedPairs: Path =
    pathsFile(Files.createTempDirectory("scholarchain-spark-pairs-"))

  private def pathsFile(directory: Path): Path =
    val ids = SyntheticRun.researcherIds
    val file = directory.resolve("pairs.txt")
    val lines = Vector(
      "# window,from,to",
      s"all,${ids("C")},${ids("E")}",
      s"all,${SyntheticRun.mentionId("W3", 3)},${SyntheticRun.mentionId("W6", 2)}",
      s"2005-2009,${ids("A")},${ids("C")}",
      s"all,${ids("C")},${ids("A")}"
    )
    Files.writeString(file, lines.mkString("\n") + "\n", UTF_8)
    file

  private def runPipeline(
      layout: InputLayout,
      extra: Vector[String]
  ): Outcome =
    val directory = Files.createTempDirectory("scholarchain-spark-out-")
    val buffer = new ByteArrayOutputStream()
    val code = CollaborationPipeline.run(
      Vector(
        "--er-run",
        layout.erRun.toString,
        "--hadoop-root",
        layout.hadoopRoot.toString,
        "--out-root",
        directory.resolve("out").toString,
        "--windows",
        windowSpec,
        "--master",
        "local[2]",
        "--partitions",
        "4",
        "--max-iterations",
        "30",
        "--sample-size",
        "10",
        "--hop-limit",
        "4",
        "--paper-summaries",
        "2",
        "--path-pairs",
        sharedPairs.toString
      ) ++ extra,
      new PrintStream(buffer, true, "UTF-8"),
      Some(session)
    )
    val out = directory.resolve("out")
    val runs =
      if Files.isDirectory(out) then Files.list(out).iterator.asScala.toVector
      else Vector.empty
    Outcome(code, buffer.toString("UTF-8"), runs.headOption.getOrElse(out))

  private lazy val first = runPipeline(Fixtures.clean, Vector.empty)
  private lazy val second = runPipeline(Fixtures.clean, Vector.empty)
  private lazy val defective = runPipeline(
    Fixtures.defective,
    Vector("--max-integrity-violations", "4")
  )

  private def table(outcome: Outcome, name: String): DataFrame =
    session.read.parquet(
      outcome.runDir.resolve("tables").resolve(name).toString
    )

  private def json(outcome: Outcome, name: String): ujson.Value =
    ujson.read(Files.readString(outcome.runDir.resolve(name), UTF_8))

  private def count(outcome: Outcome, name: String): Long =
    DriverGuard.count(table(outcome, name))

  test("end to end run writes all tables and manifests") {
    val run = first
    assertEquals(run.code, 0, run.output.slice(0, 3000))
    assertEquals(
      run.output.trim.linesIterator.toVector.last,
      "SPARK-PIPELINE RESULT: SUCCESS"
    )
    Vector(
      "_SUCCESS",
      "run-manifest.json",
      "stage-metrics.json",
      "run-metrics.json",
      "summary.txt",
      "load-manifests/neptune.json",
      "load-manifests/dynamodb.json",
      "load-manifests/s3-vectors.json",
      "load-manifests/s3.json"
    ).foreach(name => assert(Files.exists(run.runDir.resolve(name)), name))
    assert(!Files.exists(run.runDir.resolve("_checkpoints")))
    val written = Files
      .list(run.runDir.resolve("tables"))
      .iterator
      .asScala
      .map(_.getFileName.toString)
      .toSet
    assertEquals(written, tableNames)
    assert(
      OutputPolicy
        .check(
          run.runDir,
          OutputPolicy.repositoryRoot(java.nio.file.Paths.get(""))
        )
        .isRight
    )

    val manifest = json(run, "run-manifest.json")
    val counts = manifest("counts")
    def number(name: String): Long = counts(name).num.toLong
    assertEquals(number("canonical.works"), 7L)
    assertEquals(number("canonical.worksWithoutYear"), 1L)
    assertEquals(number("canonical.mergedWorks"), 1L)
    assertEquals(number("canonical.unresolvedMentionNodes"), 2L)
    assertEquals(number("canonical.unresolvedMentionsInDuplicateRecords"), 1L)
    assertEquals(number("pairs.mapped"), 10L)
    assertEquals(number("pairs.self-pair"), 1L)
    assertEquals(number("pairs.duplicate-record"), 3L)
    assertEquals(number("edges.coauthored"), 14L)
    assertEquals(number("integrityViolations"), 1L)
    assert(manifest("invariants").arr.forall(_("status").str != "fail"))
    assertEquals(manifest("inputs")("erRunId").str, SyntheticRun.erRunId)
    assertEquals(
      manifest("inputs")("hadoopRunId").str,
      SyntheticRun.hadoopRunId
    )
    assertEquals(manifest("inputs")("datasets").arr.size, 11)
    assert(
      manifest("inputs")("datasets").arr.forall(_("sha256").str.length == 64)
    )
    assert(manifest("checkpointing")("checkpointsTaken").num > 0)
    assert(manifest("versions")("spark").str.nonEmpty)
    assertEquals(
      manifest("algorithms")("communities")("perWindow").obj.keySet,
      Set("2000-2004", "2005-2009", "all")
    )
    assertEquals(
      run.runDir.getFileName.toString,
      RunContext.directoryName(manifest("runId").str)
    )

    assertEquals(count(run, "edges-coauthored"), 14L)
    assertEquals(count(run, "researcher-metrics"), 15L)
    assertEquals(count(run, "nodes-researcher"), 7L)
    assertEquals(count(run, "nodes-work"), 7L)
    assertEquals(count(run, "edges-authored"), 18L)
    assertEquals(count(run, "unresolved-mentions"), 3L)
    assertEquals(count(run, "integrity-violations"), 1L)

    val summary = rows(table(run, "component-summary"))
      .map(r => string(r, "window") -> r)
      .toMap
    val all = summary("all")
    assertEquals(all.getAs[Long]("nodeCount"), 7L)
    assertEquals(all.getAs[Long]("edgeCount"), 7L)
    assertEquals(all.getAs[Long]("componentCount"), 1L)
    assertEqualsDouble(
      all.getAs[Double]("largestComponentFraction"),
      1.0,
      1e-12
    )
    assertEquals(all.getAs[Long]("singletonCount"), 0L)
    assertEquals(summary("2000-2004").getAs[Long]("largestComponentSize"), 5L)
    assertEquals(summary("2005-2009").getAs[Long]("largestComponentSize"), 3L)

    val coreness = rows(table(run, "coreness"))
      .filter(string(_, "window") == "all")
      .map(r => label(string(r, "researcherId")) -> r.getAs[Int]("coreness"))
      .toMap
    assertEquals(
      coreness,
      Map("A" -> 2, "B" -> 2, "C" -> 2, "E" -> 1, "X" -> 1, "D" -> 1, "F" -> 1)
    )
    val members = rows(table(run, "community-membership")).filter(
      string(_, "window") == "all"
    )
    assertEquals(members.size, 7)
  }

  test("every windowed table carries window columns") {
    val run = first
    tableNames.foreach { name =>
      val data = table(run, name)
      if windowed.contains(name) then
        assert(data.columns.contains("window"), name)
        assert(data.columns.contains("windowStart"), name)
        assert(data.columns.contains("windowEnd"), name)
        val labels =
          rows(data.select("window").distinct()).map(string(_, "window")).toSet
        val allowed = Set("2000-2004", "2005-2009", "all", "not-windowed")
        assert(labels.subsetOf(allowed), s"$name windows $labels")
      else if name.startsWith("temporal") then
        assert(data.columns.contains("fromWindow"), name)
        assert(data.columns.contains("toWindow"), name)
    }
    val windowsOfEdges =
      rows(table(first, "edges-coauthored").select("window").distinct())
        .map(string(_, "window"))
        .toSet
    assertEquals(windowsOfEdges, Set("2000-2004", "2005-2009", "all"))
    val temporal = rows(table(first, "temporal-window-summary"))
      .map(r => (string(r, "fromWindow"), string(r, "toWindow")))
      .toSet
    assertEquals(
      temporal,
      Set(("1990-1999", "2000-2004"), ("2000-2004", "2005-2009"))
    )
  }

  test("every output row carries core provenance") {
    val run = first
    val runId = json(run, "run-manifest.json")("runId").str
    tableNames.foreach { name =>
      val data = table(run, name)
      assert(data.columns.contains("provenance"), s"$name has provenance")
      DriverGuard.rows(data.limit(3), 3, name).foreach { r =>
        val p = r.getAs[Row]("provenance")
        assertEquals(p.getAs[Int]("schemaVersion"), 1)
        assertEquals(string(p, "source"), "derived")
        assertEquals(string(p, "runId"), runId)
        val core = Provenance(
          ContractSchema.currentVersion,
          SourceType.Derived,
          string(p, "snapshotId"),
          string(p, "sourceRecordId"),
          string(p, "sourceUri"),
          string(p, "transformVersion"),
          string(p, "runId")
        )
        assertEquals(core.transformVersion, RunContext.transformVersion)
      }
    }
  }

  test("plans and stage metrics are persisted") {
    val run = first
    val plans = Files
      .list(run.runDir.resolve("plans"))
      .iterator
      .asScala
      .map(p => p.getFileName.toString -> Files.readString(p, UTF_8))
      .toMap
    Vector(
      "components-pass.txt",
      "communities-pass.txt",
      "coreness-pass.txt",
      "bfs-level.txt",
      "path-evidence.txt",
      "path-length-estimates.txt",
      "temporal-comparison.txt",
      "researcher-metrics.txt",
      "table-edges-coauthored.txt"
    ).foreach { name =>
      assert(plans.contains(name), s"missing plan $name in ${plans.keySet}")
      assert(plans(name).nonEmpty, s"empty plan $name")
    }
    assert(plans("components-pass.txt").contains("Exchange"))
    val metrics = json(run, "stage-metrics.json")
    val totals = metrics("totals")
    assert(totals("stages").num > 0)
    assert(totals("tasks").num > 0)
    assert(totals("shuffleWriteBytes").num > 0)
    assert(totals("shuffleReadBytes").num > 0)
    val labels = metrics("byStageLabel").obj.keySet
    Vector(
      "canonical-builder",
      "connected-components",
      "communities",
      "coreness",
      "path-evidence"
    )
      .foreach(l => assert(labels.contains(l), s"stage label $l in $labels"))
    val design = java.nio.file.Paths.get("DESIGN.md")
    val designText =
      Vector(design, java.nio.file.Paths.get("modules/spark/DESIGN.md"))
        .find(Files.isRegularFile(_))
        .map(Files.readString(_, UTF_8))
        .getOrElse("")
    assert(
      designText.contains("Stage DAG"),
      "DESIGN.md documents the stage DAG"
    )
    assert(
      designText.contains("shuffle"),
      "DESIGN.md documents shuffle boundaries"
    )
  }

  test("the run reports duplicate keys, distinct pairs and path limits") {
    val manifest = json(first, "run-manifest.json")
    val counts = manifest("counts")
    Vector(
      "researcherLinks",
      "workLinks",
      "canonicalResearchers",
      "works",
      "mentions",
      "authorPapers",
      "pairCandidates"
    ).foreach { name =>
      assertEquals(counts(s"input.duplicateKeyRows.$name").num.toLong, 0L, name)
      assertEquals(
        counts(s"input.conflictingDuplicateKeys.$name").num.toLong,
        0L,
        name
      )
    }
    // 10 mapped candidate rows, 9 distinct (the A-D pair of W3 is given twice).
    assertEquals(counts("pairs.mapped").num.toLong, 10L)
    assertEquals(counts("pairs.mapped.distinct").num.toLong, 9L)
    assertEquals(counts("pairs.self-pair.distinct").num.toLong, 1L)
    assertEquals(counts("pairs.duplicate-record.distinct").num.toLong, 3L)
    val named = manifest("invariants").arr.find(
      _("name").str == "conflictingDuplicateInputKeys"
    )
    assertEquals(named.map(_("status").str), Some("pass"))
    val paths = manifest("algorithms")("paths")("limitations").str
    assert(paths.contains("different-component"), paths)
    assert(paths.contains("fragmented"), paths)
    assert(
      manifest("algorithms")("bridge")("limitations").str.contains("fragmented")
    )
    assertEquals(manifest("code")("inRunIdSeed").bool, true)
  }

  test("summary.txt names the columns of every table it prints") {
    val lines =
      Files.readAllLines(first.runDir.resolve("summary.txt"), UTF_8).asScala
    Vector("metrics", "components", "communities", "coreness").foreach { name =>
      val header = lines.indexWhere(_.startsWith(s"$name-columns ["))
      assert(header >= 0, s"no $name-columns line in summary.txt")
      assert(header + 1 < lines.size, name)
      assert(lines(header + 1).startsWith(s"$name ["), name)
    }
    assert(lines.exists(_.startsWith("metrics-columns [window, researchers,")))
    assert(lines.exists(_.startsWith("count pairs.mapped.distinct=")))
    assert(lines.exists(_.startsWith("count input.duplicateKeyRows.works=")))
  }

  test("summary.txt and the manifest carry the path results") {
    val lines =
      Files.readAllLines(first.runDir.resolve("summary.txt"), UTF_8).asScala
    assert(
      lines.contains(
        "path-status-columns [window, status, unreachableReason, pairs]"
      )
    )
    // Pairs file: all C-E, all D-X and all C-A are found; 2005-2009 A-C has an
    // endpoint outside the window. No pair is unreachable.
    val status = lines.filter(_.startsWith("path-status [")).toVector
    assertEquals(
      status,
      Vector(
        "path-status [2005-2009, endpoint-not-in-window, null, 1]",
        "path-status [all, found, null, 3]"
      )
    )
    val header =
      lines.indexWhere(_.startsWith("path-length-estimates-columns ["))
    assert(header >= 0)
    assert(
      lines(header).contains(
        "[window, componentId, componentSize, sampledSources, " +
          "requestedSampleSize, allNodesSampled, pairsMeasured, " +
          "averagePathLength, effectiveDiameter90, maxObservedDistance, " +
          "unreachedPairsDueToDepthCap, seed, depthCap, windowStart, windowEnd]"
      ),
      lines(header)
    )
    assertEquals(
      lines.count(_.startsWith("path-length-estimates [")),
      3
    )
    val results = json(first, "run-manifest.json")("pathResults")
    assertEquals(results("statusCounts").arr.size, 2)
    val found = results("statusCounts").arr.find(_("status").str == "found").get
    assertEquals(found("window").str, "all")
    assertEquals(found("pairs").num.toLong, 3L)
    assertEquals(found("unreachableReason"), ujson.Null)
    val sizes = results("lengthEstimates").arr
      .map(r => r("window").str -> r("componentSize").num.toLong)
      .toMap
    assertEquals(sizes, Map("2000-2004" -> 5L, "2005-2009" -> 3L, "all" -> 7L))
    assert(results("lengthEstimates").arr.forall(_("allNodesSampled").bool))
  }

  test("the run ID changes when the sources digest changes") {
    val datasets = Vector("works" -> "d1", "pairs" -> "d2")
    val base = RunContext.seed(datasets, "er", "{}", "code-a")
    assertEquals(base, RunContext.seed(datasets, "er", "{}", "code-a"))
    assertNotEquals(
      RunContext.runIdFor(base),
      RunContext.runIdFor(RunContext.seed(datasets, "er", "{}", "code-b"))
    )
    assertNotEquals(
      RunContext.runIdFor(base),
      RunContext.runIdFor(
        RunContext.seed(
          Vector("works" -> "d9", "pairs" -> "d2"),
          "er",
          "{}",
          "code-a"
        )
      )
    )
    val sources = CodeInfo.sourcesDigest(
      OutputPolicy.repositoryRoot(java.nio.file.Paths.get(""))
    )
    assertNotEquals(sources, "unavailable")
    assertEquals(
      json(first, "run-manifest.json")("code")("sparkMainSourcesSha256").str,
      sources
    )
  }

  test("path evidence reports found, trivial and out-of-window pairs") {
    val run = first
    val evidence = rows(table(run, "path-evidence"))
    val byPair = evidence.groupBy(r => string(r, "pairKey"))
    assertEquals(byPair.size, 4)
    def hops(pair: Vector[Row]): Vector[(String, String)] =
      pair
        .sortBy(r =>
          Option(r.getAs[Integer]("hopIndex")).map(_.intValue).getOrElse(0)
        )
        .map(r => (label(string(r, "fromId")), label(string(r, "toId"))))
    val ids = SyntheticRun.researcherIds
    val ce = byPair(s"all|${ids("C")}|${ids("E")}")
    assertEquals(hops(ce), Vector(("C", "B"), ("B", "E")))
    assertEquals(string(ce.head, "status"), "found")
    val dx = byPair(
      s"all|${SyntheticRun.mentionId("W3", 3)}|${SyntheticRun.mentionId("W6", 2)}"
    )
    assertEquals(hops(dx), Vector(("D", "A"), ("A", "X")))
    assertEquals(
      hops(byPair(s"all|${ids("C")}|${ids("A")}")),
      Vector(("C", "A"))
    )
    val missing = byPair(s"2005-2009|${ids("A")}|${ids("C")}")
    assertEquals(string(missing.head, "status"), "endpoint-not-in-window")
    assertEquals(
      rows(table(run, "path-length-estimates")).map(string(_, "window")).toSet,
      Set("2000-2004", "2005-2009", "all")
    )
  }

  test("semantic summaries come from computed columns") {
    val run = first
    val texts = rows(table(run, "semantic-summaries"))
    val byType =
      texts.groupBy(string(_, "summaryType")).map((k, v) => k -> v.size)
    assertEquals(byType("researcher-profile"), 15)
    assertEquals(byType("collaboration-path"), 4)
    assertEquals(byType("work"), 2)
    val ada = texts
      .find(r =>
        string(r, "summaryType") == "researcher-profile" &&
          string(r, "window") == "all" &&
          string(r, "subjectId") == SyntheticRun.researcherIds("A")
      )
      .get
    assert(
      string(ada, "summaryText").startsWith(
        "Researcher Ada A in window all: 5 publications, 5 distinct coauthors, " +
          "6 coauthor occurrences, 1.20 average coauthors per paper, at most 2 " +
          s"coauthors on one paper (work ${SyntheticRun.workId("W1")})"
      ),
      string(ada, "summaryText")
    )
    assertEquals(
      strings(ada, "evidenceWorkIds"),
      Vector(SyntheticRun.workId("W1"))
    )
  }

  test("load manifests list SHA-256 per file and contain no AWS identifiers") {
    val run = first
    val targets = Vector("neptune", "dynamodb", "s3-vectors", "s3")
    targets.foreach { target =>
      val path = run.runDir.resolve("load-manifests").resolve(s"$target.json")
      val text = Files.readString(path, UTF_8)
      Vector("s3://", "arn:", "amazonaws", ".aws", "https://").foreach {
        token =>
          assert(!text.contains(token), s"$target manifest contains $token")
      }
      val manifest = ujson.read(text)
      assertEquals(manifest("status").str, "planned-not-uploaded")
      assertEquals(manifest("target").str, target)
      val entries =
        Vector("vertices", "edges", "itemTables", "vectorSources", "tables")
          .flatMap(key =>
            manifest.obj.get(key).map(_.arr.toVector).getOrElse(Vector.empty)
          )
      assert(entries.nonEmpty, target)
      entries.foreach { entry =>
        assert(entry("schema").arr.nonEmpty)
        entry("files").arr.foreach { file =>
          val actual = Digests.sha256File(run.runDir.resolve(file("path").str))
          assertEquals(file("sha256").str, actual)
          assertEquals(
            file("bytes").num.toLong,
            Files.size(run.runDir.resolve(file("path").str))
          )
        }
      }
    }
    val s3 = ujson.read(
      Files.readString(run.runDir.resolve("load-manifests/s3.json"), UTF_8)
    )
    assertEquals(s3("tables").arr.size, tableNames.size)
    val edges = s3("tables").arr.find(_("table").str == "edges-coauthored").get
    assertEquals(edges("rows").num.toLong, 14L)
    assertEquals(
      edges("partitionedBy").arr.map(_.str).toVector,
      Vector("window")
    )
    val neptune = ujson.read(
      Files.readString(run.runDir.resolve("load-manifests/neptune.json"), UTF_8)
    )
    assertEquals(neptune("vertices").arr.size, 5)
    assertEquals(neptune("edges").arr.size, 3)
  }

  test("two runs give the same logical output") {
    val one = first
    val two = second
    assertEquals(two.code, 0, two.output.slice(0, 3000))
    assertEquals(
      one.runDir.getFileName.toString,
      two.runDir.getFileName.toString
    )
    tableNames.foreach { name =>
      val a = table(one, name)
      val b = table(two, name)
      assertEquals(DriverGuard.count(a), DriverGuard.count(b), name)
      assertEquals(
        DriverGuard.count(a.exceptAll(b)),
        0L,
        s"$name only in first"
      )
      assertEquals(
        DriverGuard.count(b.exceptAll(a)),
        0L,
        s"$name only in second"
      )
    }
    val m1 = json(one, "run-manifest.json")
    val m2 = json(two, "run-manifest.json")
    Vector(
      "counts",
      "invariants",
      "algorithms",
      "config",
      "inputs",
      "partitioning"
    )
      .foreach(key => assertEquals(m1(key), m2(key), key))
  }

  test("the integrity ceiling fails the run when exceeded") {
    val run = defective
    assertEquals(run.code, 1, run.output.slice(0, 3000))
    assert(run.output.contains("SPARK-PIPELINE RESULT: FAILED"))
    assert(run.output.contains("integrityViolationsAboveCeiling"))
    assert(!Files.exists(run.runDir.resolve("_SUCCESS")))
    val violations = rows(table(run, "integrity-violations"))
    assertEquals(violations.size, 5)
    assertEquals(
      violations.map(string(_, "reason")).toSet,
      Set(
        "mention-without-link",
        "canonical-researcher-missing",
        "work-record-missing",
        "work-link-without-record",
        "pair-endpoint-unknown"
      )
    )
    val failed = json(run, "run-manifest.json")("invariants").arr
      .filter(_("status").str == "fail")
    assertEquals(
      failed.map(_("name").str).toVector,
      Vector("integrityViolationsAboveCeiling")
    )
    assertEquals(failed.head("value").num.toLong, 1L)
  }
