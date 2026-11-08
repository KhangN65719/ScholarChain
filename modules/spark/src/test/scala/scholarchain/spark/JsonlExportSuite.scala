package scholarchain.spark

import java.io.{ByteArrayOutputStream, PrintStream}
import java.math.BigDecimal as JBigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.apache.spark.sql.{DataFrame, Row}
import scala.jdk.CollectionConverters.*
import scholarchain.core.{ContractSchema, Provenance, SourceType}

class JsonlExportSuite extends SparkSuite:
  private val runId = "run:v1:11111111-2222-4333-8444-555555555555"
  private val transformVersion = "spark-analytics/1.1.0"
  private val summaryUri = "scholarchain:spark-analytics/semantic-summaries"

  private val provenanceDdl =
    "provenance STRUCT<schemaVersion: INT, source: STRING, " +
      "snapshotId: STRING, sourceRecordId: STRING, sourceUri: STRING, " +
      "transformVersion: STRING, runId: STRING>"

  private def prov(record: String, uri: String): Row =
    Row(
      1,
      "derived",
      "hadoop-snapshot:abc",
      record,
      uri,
      transformVersion,
      runId
    )

  private def summaries: DataFrame =
    def row(
        id: String,
        kind: String,
        window: String,
        start: Integer,
        end: Integer,
        node: String,
        text: String,
        works: Seq[String],
        metrics: Seq[String],
        nodes: Seq[String]
    ): Row =
      Row(
        id,
        kind,
        window,
        start,
        end,
        "subject-" + id,
        node,
        text,
        works,
        metrics,
        nodes,
        prov(id, summaryUri)
      )
    frame(
      "summaryId STRING, summaryType STRING, window STRING, " +
        "windowStart INT, windowEnd INT, subjectId STRING, nodeKind STRING, " +
        "summaryText STRING, evidenceWorkIds ARRAY<STRING>, " +
        "evidenceMetrics ARRAY<STRING>, evidenceNodeIds ARRAY<STRING>, " +
        provenanceDdl
    )(
      row(
        "summary:v1:c",
        "community",
        "all",
        1,
        9999,
        null,
        "Community \"k1\"\nline two ü – 😀",
        Seq.empty[String],
        null,
        Seq("r1", "r2")
      ),
      row(
        "summary:v1:a",
        "researcher-profile",
        "2000-2004",
        2000,
        2004,
        "resolved",
        "Researcher A",
        Seq("work-1", "work-2"),
        Seq("publicationCount"),
        Seq("r1")
      ),
      row(
        "summary:v1:e",
        "work",
        "all",
        null,
        null,
        null,
        "Work E",
        Seq("work-9"),
        Seq("publicationCount"),
        Seq("r9")
      ),
      row(
        "summary:v1:b",
        "collaboration-path",
        "2000-2004",
        2000,
        2004,
        null,
        "Path B",
        Seq.empty[String],
        Seq("hops"),
        Seq("r1", "r2")
      ),
      row(
        "summary:v1:d",
        "researcher-profile",
        "all",
        1,
        9999,
        "unresolved-mention",
        "Mention D",
        Seq("work-3"),
        Seq("publicationCount"),
        Seq("mention:v1:d")
      )
    )

  private def nodesWork: DataFrame =
    frame(
      "canonicalWorkId STRING, year INT, source STRING, title STRING, " +
        provenanceDdl
    )(
      Row(
        "work:v1:2",
        null,
        "dblp",
        "T2",
        prov("work:v1:2", "scholarchain:spark-analytics/nodes-work")
      ),
      Row(
        "work:v1:1",
        2001,
        "openalex",
        "T1",
        prov("work:v1:1", "scholarchain:spark-analytics/nodes-work")
      )
    )

  private def metrics: DataFrame =
    def row(id: String, window: String, community: String): Row =
      Row(
        id,
        window,
        community,
        3,
        1.5,
        Seq("work-1"),
        prov(id, "scholarchain:spark-analytics/researcher-metrics")
      )
    frame(
      "researcherId STRING, window STRING, communityId STRING, " +
        "coreness INT, weightedDegree DOUBLE, " +
        "maximumTeamWorkIds ARRAY<STRING>, " + provenanceDdl
    )(
      row("r2", "all", "k2"),
      row("r1", "all", "k1"),
      row("r1", "2000-2004", null)
    )

  private def edgeFrame(edges: Seq[(String, String, String)]): DataFrame =
    frame(
      "edgeId STRING, researcherA STRING, researcherB STRING, " +
        "jointPublicationCount BIGINT, window STRING"
    )(edges.map((id, a, b) => Row(id, a, b, 1L, "all"))*)

  private val plainEdges = Seq(
    ("mention-coauthor:v1:aa", "mention:v1:a", "researcher:v1:x"),
    ("mention-coauthor:v1:bb", "researcher:v1:x", "researcher:v1:y"),
    ("coauthor:v1:cc", "researcher:v1:x", "researcher:v1:y"),
    ("coauthor:v1:dd", "researcher:v1:x", "researcher:v1:z"),
    ("coauthor:v1:ee", "researcher:v1:y", "researcher:v1:z"),
    ("edge:v1:ff", "researcher:v1:x", "researcher:v1:z")
  )

  private def write(runDir: Path, name: String, data: DataFrame): Unit =
    val writer = data.write.mode("errorifexists")
    val configured =
      if data.columns.contains("window") then writer.partitionBy("window")
      else writer
    configured.parquet(runDir.resolve("tables").resolve(name).toUri.toString)

  private def makeRun(
      edges: Seq[(String, String, String)] = plainEdges,
      success: Boolean = true,
      skip: Set[String] = Set.empty,
      metricsFrame: DataFrame = metrics,
      worksFrame: DataFrame = nodesWork
  ): Path =
    val root = Files.createTempDirectory("scholarchain-jsonl-run-")
    val runDir = root.resolve(RunContext.directoryName(runId))
    Files.createDirectories(runDir)
    val tables = Map(
      "semantic-summaries" -> summaries,
      "nodes-work" -> worksFrame,
      "researcher-metrics" -> metricsFrame,
      "edges-coauthored" -> edgeFrame(edges)
    )
    tables.filterNot((name, _) => skip.contains(name)).foreach { (name, data) =>
      write(runDir, name, data)
    }
    Files.writeString(
      runDir.resolve("run-manifest.json"),
      ujson.write(
        ujson.Obj("runId" -> runId, "transformVersion" -> transformVersion)
      ),
      UTF_8
    )
    val neptune = ujson.Obj(
      "edges" -> ujson.Arr(
        ujson.Obj(
          "table" -> "edges-coauthored",
          "fromColumn" -> "researcherA",
          "toColumn" -> "researcherB"
        )
      )
    )
    Files.createDirectories(runDir.resolve("load-manifests"))
    Files.writeString(
      runDir.resolve("load-manifests").resolve("neptune.json"),
      ujson.write(neptune),
      UTF_8
    )
    if success then Files.writeString(runDir.resolve("_SUCCESS"), "", UTF_8)
    runDir

  private def outDir: Path =
    Files
      .createTempDirectory("scholarchain-jsonl-out-")
      .resolve(RunContext.directoryName(runId))

  private def runExport(
      runDir: Path,
      out: Path,
      extra: Vector[String] = Vector.empty
  ): (Int, String) =
    val buffer = new ByteArrayOutputStream()
    val code = JsonlExport.run(
      Vector(runDir.toString, out.toString) ++ extra,
      new PrintStream(buffer, true, "UTF-8"),
      Some(session)
    )
    (code, buffer.toString("UTF-8"))

  private def tree(root: Path): Map[String, String] =
    val stream = Files.walk(root)
    try
      stream.iterator.asScala.toVector
        .filter(Files.isRegularFile(_))
        .map(p => root.relativize(p).toString -> Digests.sha256File(p))
        .toMap
    finally stream.close()

  private def lines(path: Path): Vector[ujson.Value] =
    Files.readAllLines(path, UTF_8).asScala.toVector.map(ujson.read(_))

  private def tableLines(out: Path, table: String): Vector[ujson.Value] =
    val stream = Files.list(out.resolve(table))
    val files =
      try stream.iterator.asScala.toVector.sortBy(_.getFileName.toString)
      finally stream.close()
    files.flatMap(lines)

  private def manifest(out: Path): ujson.Value =
    ujson.read(Files.readString(out.resolve("export-manifest.json"), UTF_8))

  test(
    "summaries round trip: every column type, sorted, split in fixed files"
  ) {
    val run = makeRun()
    val before = tree(run)
    val out = outDir
    val (code, text) = runExport(run, out, Vector("--rows-per-file", "2"))
    assertEquals(code, 0, text)
    assertEquals(tree(run), before, "the run directory is read only")
    val names = Files.list(out.resolve("semantic-summaries")).iterator.asScala
    assertEquals(
      names.map(_.getFileName.toString).toVector.sorted,
      Vector("part-00000.jsonl", "part-00001.jsonl", "part-00002.jsonl")
    )
    val rows = tableLines(out, "semantic-summaries")
    assertEquals(
      rows.map(_("summaryId").str),
      Vector("a", "b", "c", "d", "e").map("summary:v1:" + _)
    )
    assertEquals(
      rows.head.obj.keys.toVector,
      Vector(
        "summaryId",
        "summaryType",
        "windowStart",
        "windowEnd",
        "subjectId",
        "nodeKind",
        "summaryText",
        "evidenceWorkIds",
        "evidenceMetrics",
        "evidenceNodeIds",
        "provenance",
        "window"
      )
    )
    val c = rows(2)
    assertEquals(c("summaryType").str, "community")
    assertEquals(c("window").str, "all")
    assertEquals(c("windowStart"), ujson.Num(1))
    assertEquals(c("windowEnd"), ujson.Num(9999))
    assertEquals(c("nodeKind"), ujson.Null)
    assertEquals(c("evidenceMetrics"), ujson.Null)
    assertEquals(c("evidenceWorkIds"), ujson.Arr())
    assertEquals(c("evidenceNodeIds"), ujson.Arr("r1", "r2"))
    assertEquals(
      c("summaryText").str,
      "Community \"k1\"\nline two ü – 😀"
    )
    assertEquals(
      upickle.default.read[Provenance](c("provenance")),
      Provenance(
        ContractSchema.currentVersion,
        SourceType.Derived,
        "hadoop-snapshot:abc",
        "summary:v1:c",
        summaryUri,
        transformVersion,
        runId
      )
    )
    val e = rows(4)
    assertEquals(e("windowStart"), ujson.Null)
    assertEquals(e("evidenceWorkIds"), ujson.Arr("work-9"))
    assertEquals(rows(1)("evidenceMetrics"), ujson.Arr("hops"))
    assertEquals(rows(0)("nodeKind").str, "resolved")
  }

  test("nodes-work and researcher-metrics carry their facet columns") {
    val run = makeRun()
    val out = outDir
    assertEquals(runExport(run, out)._1, 0)
    val works = tableLines(out, "nodes-work")
    assertEquals(
      works.map(_("canonicalWorkId").str),
      Vector("work:v1:1", "work:v1:2")
    )
    assertEquals(works(0)("year"), ujson.Num(2001))
    assertEquals(works(0)("source").str, "openalex")
    assertEquals(works(1)("year"), ujson.Null)
    assertEquals(
      upickle.default.read[Provenance](works(1)("provenance")).sourceRecordId,
      "work:v1:2"
    )
    val rows = tableLines(out, "researcher-metrics")
    assertEquals(
      rows.map(r => (r("researcherId").str, r("window").str)),
      Vector(("r1", "2000-2004"), ("r1", "all"), ("r2", "all"))
    )
    assertEquals(rows(0)("communityId"), ujson.Null)
    assertEquals(rows(1)("communityId").str, "k1")
    assertEquals(rows(1)("weightedDegree"), ujson.Num(1.5))
    assertEquals(rows(1)("coreness"), ujson.Num(3))
  }

  test("a repeat run gives byte-identical files and manifest") {
    val run = makeRun()
    val first = outDir
    val second = outDir
    assertEquals(runExport(run, first, Vector("--rows-per-file", "2"))._1, 0)
    assertEquals(runExport(run, second, Vector("--rows-per-file", "2"))._1, 0)
    val one = tree(first)
    assert(one.contains("export-manifest.json") && one.contains("_SUCCESS"))
    assertEquals(tree(second), one)
    val (code, text) = runExport(run, first, Vector("--rows-per-file", "2"))
    assertEquals(code, 0, text)
    assert(text.contains("identical; nothing written"), text)
    assertEquals(tree(first), one)
  }

  test("the manifest records the run, the version, counts and file digests") {
    val run = makeRun()
    val out = outDir
    val (code, text) = runExport(run, out, Vector("--rows-per-file", "2"))
    assertEquals(code, 0, text)
    val m = manifest(out)
    assertEquals(m("sourceRunId").str, runId)
    assertEquals(m("transformVersion").str, transformVersion)
    assertEquals(m("exportVersion").str, JsonlExport.exportVersion)
    assertEquals(m("sourceSuccessSeen"), ujson.True)
    assertEquals(m("tables")("semantic-summaries")("rows"), ujson.Num(5))
    assertEquals(m("tables")("nodes-work")("rows"), ujson.Num(2))
    assertEquals(m("tables")("researcher-metrics")("rows"), ujson.Num(3))
    val files = m("tables")("semantic-summaries")("files").arr
    assertEquals(files.map(_("rows").num.toInt).toVector, Vector(2, 2, 1))
    files.foreach { file =>
      val path = out.resolve(file("path").str)
      assertEquals(file("bytes").num.toLong, Files.size(path))
      assertEquals(file("sha256").str, Digests.sha256File(path))
    }
    assert(text.contains(s"transformVersion: $transformVersion"), text)
    assert(text.contains("SPARK-EXPORT RESULT: SUCCESS"), text)
  }

  test("a run without _SUCCESS is refused and nothing is written") {
    val run = makeRun(success = false)
    val out = outDir
    val (code, text) = runExport(run, out)
    assertEquals(code, 2)
    assert(text.contains("_SUCCESS"), text)
    assert(text.contains("SPARK-EXPORT RESULT: FAILED"), text)
    assert(!Files.exists(out))
  }

  test("a different export is refused and the existing output is untouched") {
    val run = makeRun()
    val out = outDir
    assertEquals(runExport(run, out, Vector("--rows-per-file", "2"))._1, 0)
    val before = tree(out)
    val (code, text) = runExport(run, out, Vector("--rows-per-file", "3"))
    assertEquals(code, 2)
    assert(text.contains("manifest differs"), text)
    assertEquals(tree(out), before)
    val stray = outDir
    Files.createDirectories(stray)
    Files.writeString(stray.resolve("keep.txt"), "mine", UTF_8)
    val (strayCode, strayText) = runExport(run, stray)
    assertEquals(strayCode, 2)
    assert(strayText.contains("not empty"), strayText)
    assertEquals(tree(stray).keySet, Set("keep.txt"))
  }

  test("an empty existing output directory is filled") {
    val run = makeRun()
    val out = outDir
    Files.createDirectories(out)
    assertEquals(runExport(run, out)._1, 0)
    assert(Files.isRegularFile(out.resolve("_SUCCESS")))
  }

  test("edge ID prefixes are counted from the table and printed") {
    val run = makeRun()
    val out = outDir
    val (code, text) = runExport(run, out)
    assertEquals(code, 0, text)
    val ids = manifest(out)("coauthorEdgeIds")
    assertEquals(ids("fromColumn").str, "researcherA")
    assertEquals(ids("toColumn").str, "researcherB")
    assertEquals(ids("totalEdges"), ujson.Num(6))
    assertEquals(ids("mentionCoauthorEdges"), ujson.Num(2))
    assertEquals(ids("coauthorEdges"), ujson.Num(3))
    assertEquals(ids("otherPrefixEdges"), ujson.Num(1))
    assertEquals(ids("coauthorEdgesWithMentionEndpoint"), ujson.Num(0))
    assertEquals(ids("anyCoauthorEdgeHasMentionEndpoint"), ujson.False)
    assert(text.contains("edges-coauthored total=6"), text)
    assert(text.contains("mention-coauthor:v1: count=2"), text)
    assert(text.contains("edges-coauthored coauthor:v1: count=3"), text)
    assert(text.contains("other-prefix count=1"), text)
    assert(text.contains("endpoint: false (count=0)"), text)
  }

  test("a coauthor edge with a mention endpoint is flagged") {
    val edges = plainEdges :+
      ("coauthor:v1:gg", "mention:v1:m", "researcher:v1:z")
    val out = outDir
    val (code, text) = runExport(makeRun(edges), out)
    assertEquals(code, 0, text)
    val ids = manifest(out)("coauthorEdgeIds")
    assertEquals(ids("coauthorEdges"), ujson.Num(4))
    assertEquals(ids("coauthorEdgesWithMentionEndpoint"), ujson.Num(1))
    assertEquals(ids("anyCoauthorEdgeHasMentionEndpoint"), ujson.True)
    assert(text.contains("endpoint: true (count=1)"), text)
  }

  test("a missing table, a missing column and an unsupported type fail") {
    val noWork = outDir
    val (c1, t1) = runExport(makeRun(skip = Set("nodes-work")), noWork)
    assertEquals(c1, 2)
    assert(t1.contains("Missing table nodes-work"), t1)
    assert(!Files.exists(noWork))
    val noEdges = outDir
    val (c2, t2) = runExport(makeRun(skip = Set("edges-coauthored")), noEdges)
    assertEquals(c2, 2)
    assert(t2.contains("Missing table edges-coauthored"), t2)
    val thin = frame("researcherId STRING, window STRING, " + provenanceDdl)(
      Row("r1", "all", prov("r1", "u"))
    )
    val (c3, t3) = runExport(makeRun(metricsFrame = thin), outDir)
    assertEquals(c3, 2)
    assert(t3.contains("lacks column(s) communityId"), t3)
    val priced = frame(
      "canonicalWorkId STRING, year INT, source STRING, price DECIMAL(10,2), " +
        provenanceDdl
    )(Row("w", 2000, "dblp", new JBigDecimal("1.50"), prov("w", "u")))
    val (c4, t4) = runExport(makeRun(worksFrame = priced), outDir)
    assertEquals(c4, 2)
    assert(t4.contains("Unsupported type decimal(10,2)"), t4)
    assert(t4.contains("nodes-work.price"), t4)
  }

  test("output inside the run directory or an unusable argument is refused") {
    val run = makeRun()
    val (c1, t1) = runExport(run, run.resolve("jsonl"))
    assertEquals(c1, 2)
    assert(t1.contains("overlap"), t1)
    val (c2, t2) = runExport(run, outDir, Vector("--rows-per-file", "0"))
    assertEquals(c2, 2)
    assert(t2.contains("--rows-per-file"), t2)
  }

  test("one row per file puts each row in its own file in id order") {
    val out = outDir
    val (code, text) = runExport(makeRun(), out, Vector("--rows-per-file", "1"))
    assertEquals(code, 0, text)
    val files = manifest(out)("tables")("semantic-summaries")("files").arr
    assertEquals(files.map(_("rows").num.toInt).toVector, Vector.fill(5)(1))
    val first = lines(out.resolve("semantic-summaries/part-00000.jsonl"))
    val last = lines(out.resolve("semantic-summaries/part-00004.jsonl"))
    assertEquals(first.map(_("summaryId").str), Vector("summary:v1:a"))
    assertEquals(last.map(_("summaryId").str), Vector("summary:v1:e"))
  }

  private def link(target: Path): Path =
    val holder = Files.createTempDirectory("scholarchain-jsonl-link-")
    Files.createSymbolicLink(holder.resolve("link"), target)

  private def stagingOf(out: Path): Path =
    out.resolveSibling(s".staging-${out.getFileName}")

  private def validMarker: String =
    s"scholarchain-export-staging ${JsonlExport.exportVersion} " +
      s"${java.util.UUID.randomUUID}\n"

  test("an output path that reaches the run directory by a link is refused") {
    val run = makeRun()
    val before = tree(run)
    val inside = runExport(run, link(run).resolve("jsonl"))
    assertEquals(inside._1, 2, inside._2)
    assert(inside._2.contains("overlap"), inside._2)
    val same = runExport(run, link(run))
    assertEquals(same._1, 2, same._2)
    assert(same._2.contains("overlap"), same._2)
    val contains = runExport(run, link(run.getParent))
    assertEquals(contains._1, 2, contains._2)
    assert(contains._2.contains("overlap"), contains._2)
    assertEquals(tree(run), before, "nothing was written to the run")
    assert(!Files.exists(run.resolve("jsonl")))
    assert(!Files.exists(stagingOf(run.resolve("jsonl"))))
  }

  test("an output link into the repository or a target directory is refused") {
    val run = makeRun()
    val repo = OutputPolicy
      .repositoryRoot(java.nio.file.Paths.get(""))
      .getOrElse(fail("the repository root was not found"))
    val name = "scholarchain-export-must-not-exist"
    val (c1, t1) = runExport(run, link(repo).resolve(name))
    assertEquals(c1, 2, t1)
    assert(t1.contains("repository"), t1)
    assert(!Files.exists(repo.resolve(name)))
    assert(!Files.exists(repo.resolve(s".staging-$name")))
    val holder = Files.createTempDirectory("scholarchain-jsonl-build-")
    val build = Files.createDirectories(holder.resolve("target"))
    val (c2, t2) = runExport(run, link(build).resolve("export"))
    assertEquals(c2, 2, t2)
    assert(t2.contains("target"), t2)
    val (c3, t3) = runExport(run, link(build))
    assertEquals(c3, 2, t3)
    assert(t3.contains("target"), t3)
    assertEquals(tree(holder), Map.empty[String, String])
    assert(!Files.exists(build.resolve("export")))
    assert(!Files.exists(holder.resolve(".staging-export")))
  }

  test("a staging directory without the marker is kept and the export stops") {
    val run = makeRun()
    val out = outDir
    val staging = stagingOf(out)
    Files.createDirectories(staging)
    Files.writeString(staging.resolve("keep.txt"), "mine", UTF_8)
    val (code, text) = runExport(run, out)
    assertEquals(code, 2, text)
    assert(text.contains("staging marker"), text)
    assertEquals(tree(staging).keySet, Set("keep.txt"))
    assert(!Files.exists(out))
    val forged = outDir
    val forgedStaging = stagingOf(forged)
    Files.createDirectories(forgedStaging)
    Files.writeString(
      forgedStaging.resolve(JsonlExport.stagingMarkerName),
      "not the marker\n",
      UTF_8
    )
    Files.writeString(forgedStaging.resolve("keep.txt"), "mine", UTF_8)
    val (c2, t2) = runExport(run, forged)
    assertEquals(c2, 2, t2)
    assertEquals(
      tree(forgedStaging).keySet,
      Set(JsonlExport.stagingMarkerName, "keep.txt")
    )
    assert(!Files.exists(forged))
  }

  test("a marked staging directory with a foreign entry is kept") {
    val run = makeRun()
    val out = outDir
    val staging = stagingOf(out)
    Files.createDirectories(staging)
    Files.writeString(
      staging.resolve(JsonlExport.stagingMarkerName),
      validMarker,
      UTF_8
    )
    Files.writeString(staging.resolve("keep.txt"), "mine", UTF_8)
    val (code, text) = runExport(run, out)
    assertEquals(code, 2, text)
    assert(text.contains("unexpected entries"), text)
    assertEquals(
      tree(staging).keySet,
      Set(JsonlExport.stagingMarkerName, "keep.txt")
    )
    assert(!Files.exists(out))
  }

  test("a staging link is refused and its target is not deleted") {
    val run = makeRun()
    val out = outDir
    val victim = Files.createTempDirectory("scholarchain-jsonl-victim-")
    Files.writeString(victim.resolve("keep.txt"), "mine", UTF_8)
    Files.writeString(
      victim.resolve(JsonlExport.stagingMarkerName),
      validMarker,
      UTF_8
    )
    Files.createSymbolicLink(stagingOf(out), victim)
    val (code, text) = runExport(run, out)
    assertEquals(code, 2, text)
    assert(text.contains("symbolic link"), text)
    assertEquals(
      tree(victim).keySet,
      Set(JsonlExport.stagingMarkerName, "keep.txt")
    )
    assert(!Files.exists(out))
  }

  test("a stale staging directory with the marker is cleaned and it proceeds") {
    val run = makeRun()
    val out = outDir
    val staging = stagingOf(out)
    Files.createDirectories(staging.resolve("semantic-summaries"))
    Files.createDirectories(staging.resolve("_work"))
    Files.writeString(
      staging.resolve("semantic-summaries/part-00000.jsonl"),
      "stale\n",
      UTF_8
    )
    Files.writeString(staging.resolve("_work/x"), "stale", UTF_8)
    Files.writeString(
      staging.resolve(JsonlExport.stagingMarkerName),
      validMarker,
      UTF_8
    )
    val (code, text) = runExport(run, out, Vector("--rows-per-file", "2"))
    assertEquals(code, 0, text)
    assert(!Files.exists(staging))
    val files = tree(out).keySet
    assert(files.contains("_SUCCESS") && files.contains("export-manifest.json"))
    assert(!files.contains(JsonlExport.stagingMarkerName), files.toString)
    assertEquals(
      tableLines(out, "semantic-summaries").size,
      5,
      "no stale line remains"
    )
  }
