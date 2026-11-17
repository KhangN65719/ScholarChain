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
      "edgeId STRING, window STRING, windowStart INT, windowEnd INT, " +
        "researcherA STRING, researcherB STRING, policyVersion STRING, " +
        "jointPublicationCount BIGINT, firstCollaborationYear INT, " +
        "lastCollaborationYear INT, representativeWorkIds ARRAY<STRING>, " +
        "endpointAUnresolved BOOLEAN, endpointBUnresolved BOOLEAN, " +
        provenanceDdl
    )(
      edges.map((id, a, b) =>
        Row(
          id,
          "all",
          1,
          9999,
          a,
          b,
          "scholarchain.spark.coauthor-policy/1",
          1L,
          2001,
          2001,
          Seq("work:v1:1"),
          a.startsWith("mention:"),
          b.startsWith("mention:"),
          prov(id, "scholarchain:spark-analytics/edges-coauthored")
        )
      )*
    )

  private def uri(table: String): String =
    s"scholarchain:spark-analytics/$table"

  private def supportFrame(edges: Seq[(String, String, String)]): DataFrame =
    frame(
      "edgeId STRING, window STRING, windowStart INT, windowEnd INT, " +
        "researcherA STRING, researcherB STRING, canonicalWorkId STRING, " +
        "year INT, " + provenanceDdl
    )(
      edges.map((id, a, b) =>
        Row(
          id,
          "all",
          1,
          9999,
          a,
          b,
          "work:v1:1",
          2001,
          prov(id, uri("edge-support"))
        )
      )*
    )

  private def researcherNodes: DataFrame =
    frame(
      "nodeId STRING, nodeKind STRING, idRule STRING, orcid STRING, " +
        "linkStatus STRING, memberCount BIGINT, inResearchersExport BOOLEAN, " +
        "displayName STRING, mentionCount BIGINT, " + provenanceDdl
    )(
      Row(
        "researcher:v1:y",
        "canonical",
        "orcid",
        "0000-0002-1825-0097",
        "accepted",
        2L,
        true,
        "Y Name",
        2L,
        prov("researcher:v1:y", uri("nodes-researcher"))
      ),
      Row(
        "mention:v1:a",
        "unresolved-mention",
        null,
        null,
        "no-link-row",
        1L,
        null,
        "A\tMention",
        1L,
        prov("mention:v1:a", uri("nodes-researcher"))
      ),
      Row(
        "researcher:v1:x",
        "canonical",
        "name",
        null,
        "accepted",
        1L,
        true,
        "X Name",
        1L,
        prov("researcher:v1:x", uri("nodes-researcher"))
      )
    )

  private def venueNodes: DataFrame =
    frame(
      "venueId STRING, name STRING, issns ARRAY<STRING>, " +
        "canonicalWorkCount BIGINT, " + provenanceDdl
    )(
      Row(
        "venue:v1:2",
        "Venue Two",
        Seq.empty[String],
        1L,
        prov("venue:v1:2", uri("nodes-venue"))
      ),
      Row(
        "venue:v1:1",
        "Venue One",
        Seq("1234-5678"),
        1L,
        prov("venue:v1:1", uri("nodes-venue"))
      )
    )

  private def topicNodes: DataFrame =
    frame(
      "topicId STRING, name STRING, parentTopicId STRING, " + provenanceDdl
    )(
      Row("topic:v1:1", "Topic", null, prov("topic:v1:1", uri("nodes-topic")))
    )

  private def institutionNodes: DataFrame =
    frame(
      "institutionId STRING, name STRING, countryCode STRING, " +
        "externalIdsJson STRING, " + provenanceDdl
    )(
      Row(
        "institution:v1:1",
        "Institute",
        "NL",
        "{}",
        prov("institution:v1:1", uri("nodes-institution"))
      )
    )

  private def authoredEdges: DataFrame =
    frame(
      "researcherNodeId STRING, nodeKind STRING, canonicalWorkId STRING, " +
        "workId STRING, mentionId STRING, authorPosition INT, year INT, " +
        "countedInMetrics BOOLEAN, " + provenanceDdl
    )(
      Row(
        "researcher:v1:y",
        "canonical",
        "work:v1:1",
        "work:v1:1",
        "mention:v1:b",
        1,
        2001,
        true,
        prov("mention:v1:b", uri("edges-authored"))
      ),
      Row(
        "researcher:v1:x",
        "canonical",
        "work:v1:1",
        "work:v1:1",
        "mention:v1:c",
        0,
        2001,
        true,
        prov("mention:v1:c", uri("edges-authored"))
      ),
      Row(
        "mention:v1:a",
        "unresolved-mention",
        "work:v1:2",
        "work:v1:2",
        "mention:v1:a",
        0,
        null,
        true,
        prov("mention:v1:a", uri("edges-authored"))
      )
    )

  private def publishedInEdges: DataFrame =
    frame("fromId STRING, toId STRING, " + provenanceDdl)(
      Row("work:v1:2", "venue:v1:2", prov("w2|v2", uri("edges-published-in"))),
      Row("work:v1:1", "venue:v1:1", prov("w1|v1", uri("edges-published-in")))
    )

  private def componentsFrame: DataFrame =
    frame(
      "window STRING, componentId STRING, componentSize BIGINT, " +
        "isLargest BOOLEAN, " + provenanceDdl
    )(
      Seq(
        ("all", "r1", 2L, true),
        ("all", "r9", 1L, false),
        ("2000-2004", "r1", 1L, true)
      ).map((w, c, n, largest) =>
        Row(w, c, n, largest, prov(s"$w|$c", uri("components")))
      )*
    )

  private def componentMembershipFrame: DataFrame =
    frame(
      "window STRING, researcherId STRING, componentId STRING, " +
        "componentSize BIGINT, " + provenanceDdl
    )(
      Seq(
        ("all", "r1", "r1", 2L),
        ("all", "r2", "r1", 2L),
        ("all", "r9", "r9", 1L),
        ("2000-2004", "r1", "r1", 1L)
      ).map((w, r, c, n) =>
        Row(w, r, c, n, prov(s"$w|$r", uri("component-membership")))
      )*
    )

  private def communitiesFrame: DataFrame =
    frame(
      "window STRING, communityId STRING, communitySize BIGINT, " +
        "internalEdges BIGINT, crossEdges BIGINT, internalWeight BIGINT, " +
        "crossWeight BIGINT, " + provenanceDdl
    )(
      Seq(
        ("all", "k1", 2L, 1L, 1L, 3L, 2L),
        ("all", "k2", 1L, 0L, 1L, 0L, 2L),
        ("2000-2004", "k1", 1L, 0L, 0L, 0L, 0L)
      ).map((w, k, size, i, c, iw, cw) =>
        Row(w, k, size, i, c, iw, cw, prov(s"$w|$k", uri("communities")))
      )*
    )

  private def communityMembershipFrame: DataFrame =
    frame(
      "window STRING, researcherId STRING, communityId STRING, " +
        "communitySize BIGINT, " + provenanceDdl
    )(
      Seq(
        ("all", "r1", "k1", 2L),
        ("all", "r2", "k1", 2L),
        ("all", "r9", "k2", 1L),
        ("2000-2004", "r1", "k1", 1L)
      ).map((w, r, k, n) =>
        Row(w, r, k, n, prov(s"$w|$r", uri("community-membership")))
      )*
    )

  private def communityCrossFrame: DataFrame =
    frame(
      "window STRING, communityA STRING, communityB STRING, " +
        "edgeCount BIGINT, totalWeight BIGINT, " + provenanceDdl
    )(
      Row(
        "all",
        "k1",
        "k2",
        1L,
        2L,
        prov("all|k1|k2", uri("community-cross-edges"))
      )
    )

  /** Two hop rows of a found pair and one status row each of an unreachable and
    * a trivial pair: a status row has a null `hopIndex` and `pathLength`.
    */
  private def pathEvidenceFrame: DataFrame =
    def hop(index: Int, from: String, to: String, work: String): Row =
      Row(
        "all|r1|r3",
        "all",
        "r1",
        "r3",
        "found",
        null,
        4,
        2,
        index,
        from,
        to,
        s"coauthor:v1:$from$to",
        1L,
        2001,
        2003,
        Seq(work),
        prov(s"all|r1|r3|$index", uri("path-evidence"))
      )
    def status(
        key: String,
        window: String,
        target: String,
        state: String,
        reason: String,
        length: Integer
    ): Row =
      Row(
        key,
        window,
        "r1",
        target,
        state,
        reason,
        4,
        length,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        prov(key, uri("path-evidence"))
      )
    frame(
      "pairKey STRING, window STRING, sourceId STRING, targetId STRING, " +
        "status STRING, unreachableReason STRING, hopLimit INT, " +
        "pathLength INT, hopIndex INT, fromId STRING, toId STRING, " +
        "edgeId STRING, jointPublicationCount BIGINT, " +
        "firstCollaborationYear INT, lastCollaborationYear INT, " +
        "supportingWorkIds ARRAY<STRING>, " + provenanceDdl
    )(
      hop(1, "r2", "r3", "work-23"),
      hop(0, "r1", "r2", "work-12"),
      status(
        "all|r1|r9",
        "all",
        "r9",
        "unreachable-within-bound",
        "no path within 4 hops",
        null
      ),
      status("2000-2004|r1|r1", "2000-2004", "r1", "trivial", null, 0)
    )

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
      "edges-coauthored" -> edgeFrame(edges),
      "nodes-researcher" -> researcherNodes,
      "nodes-venue" -> venueNodes,
      "nodes-topic" -> topicNodes,
      "nodes-institution" -> institutionNodes,
      "edges-authored" -> authoredEdges,
      "edges-published-in" -> publishedInEdges,
      "edge-support" -> supportFrame(edges),
      "path-evidence" -> pathEvidenceFrame,
      "components" -> componentsFrame,
      "component-membership" -> componentMembershipFrame,
      "communities" -> communitiesFrame,
      "community-membership" -> communityMembershipFrame,
      "community-cross-edges" -> communityCrossFrame
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

  test("the data files of the fixture have fixed names and digests") {
    val run = makeRun()
    val out = outDir
    assertEquals(runExport(run, out, Vector("--rows-per-file", "2"))._1, 0)
    val files = manifest(out)("tables").obj.toVector.sortBy(_._1).flatMap {
      (name, table) =>
        table("files").arr
          .map(f => s"$name/${f("path").str} ${f("sha256").str}")
    }
    val original = Set("nodes-work", "researcher-metrics", "semantic-summaries")
    val unchanged = files.filter(f => original.contains(f.takeWhile(_ != '/')))
    assertEquals(
      unchanged.map(_.takeWhile(_ != ' ')),
      Vector(
        "nodes-work/nodes-work/part-00000.jsonl",
        "researcher-metrics/researcher-metrics/part-00000.jsonl",
        "researcher-metrics/researcher-metrics/part-00001.jsonl",
        "semantic-summaries/semantic-summaries/part-00000.jsonl",
        "semantic-summaries/semantic-summaries/part-00001.jsonl",
        "semantic-summaries/semantic-summaries/part-00002.jsonl"
      )
    )
    // The digest the export of these three tables had before the graph tables
    // were added: their bytes did not change.
    assertEquals(
      Digests.sha256Hex(unchanged.mkString("\n")),
      "e94b999f0e1c7ba0b8ae9da4903a11a1a562232b112f817ad26a35c1b88f6c71"
    )
    // The eleven tables exported before the six analytics tables were added:
    // same files, same order, same bytes. The digest below was pinned on the
    // code before that change and is not recomputed here.
    val eleven = files.filter { f =>
      val table = f.takeWhile(_ != '/')
      graphIds.contains(table) || original.contains(table)
    }
    assertEquals(
      files.filterNot(eleven.contains).map(_.takeWhile(_ != ' ')),
      Vector(
        "communities/communities/part-00000.jsonl",
        "communities/communities/part-00001.jsonl",
        "community-cross-edges/community-cross-edges/part-00000.jsonl",
        "community-membership/community-membership/part-00000.jsonl",
        "community-membership/community-membership/part-00001.jsonl",
        "component-membership/component-membership/part-00000.jsonl",
        "component-membership/component-membership/part-00001.jsonl",
        "components/components/part-00000.jsonl",
        "components/components/part-00001.jsonl",
        "path-evidence/path-evidence/part-00000.jsonl",
        "path-evidence/path-evidence/part-00001.jsonl"
      )
    )
    assertEquals(
      eleven.map(_.takeWhile(_ != ' ')),
      Vector(
        "edge-support/edge-support/part-00000.jsonl",
        "edge-support/edge-support/part-00001.jsonl",
        "edge-support/edge-support/part-00002.jsonl",
        "edges-authored/edges-authored/part-00000.jsonl",
        "edges-authored/edges-authored/part-00001.jsonl",
        "edges-coauthored/edges-coauthored/part-00000.jsonl",
        "edges-coauthored/edges-coauthored/part-00001.jsonl",
        "edges-coauthored/edges-coauthored/part-00002.jsonl",
        "edges-published-in/edges-published-in/part-00000.jsonl",
        "nodes-institution/nodes-institution/part-00000.jsonl",
        "nodes-researcher/nodes-researcher/part-00000.jsonl",
        "nodes-researcher/nodes-researcher/part-00001.jsonl",
        "nodes-topic/nodes-topic/part-00000.jsonl",
        "nodes-venue/nodes-venue/part-00000.jsonl",
        "nodes-work/nodes-work/part-00000.jsonl",
        "researcher-metrics/researcher-metrics/part-00000.jsonl",
        "researcher-metrics/researcher-metrics/part-00001.jsonl",
        "semantic-summaries/semantic-summaries/part-00000.jsonl",
        "semantic-summaries/semantic-summaries/part-00001.jsonl",
        "semantic-summaries/semantic-summaries/part-00002.jsonl"
      )
    )
    assertEquals(
      Digests.sha256Hex(eleven.mkString("\n")),
      "822da06dbe651e3eb4e1eb010e5d8a2a411f8075e4a2267c151063b7c050603e"
    )
    val text = Files.readString(out.resolve("export-manifest.json"), UTF_8)
    assert(!text.contains("master") && !text.contains("deployMode"), text)
  }

  private val graphIds = Map(
    "nodes-researcher" -> Vector("nodeId"),
    "nodes-venue" -> Vector("venueId"),
    "nodes-topic" -> Vector("topicId"),
    "nodes-institution" -> Vector("institutionId"),
    "edges-authored" -> Vector(
      "researcherNodeId",
      "canonicalWorkId",
      "mentionId"
    ),
    "edges-published-in" -> Vector("fromId", "toId"),
    "edges-coauthored" -> Vector("edgeId"),
    "edge-support" -> Vector("edgeId", "canonicalWorkId")
  )

  private val analyticsIds = Map(
    "path-evidence" -> Vector("pairKey"),
    "components" -> Vector("window", "componentId"),
    "component-membership" -> Vector("window", "researcherId"),
    "communities" -> Vector("window", "communityId"),
    "community-membership" -> Vector("window", "researcherId"),
    "community-cross-edges" -> Vector("window", "communityA", "communityB")
  )

  test("the export holds the eight graph tables the projector reads") {
    val out = outDir
    val (code, text) = runExport(makeRun(), out, Vector("--rows-per-file", "2"))
    assertEquals(code, 0, text)
    val m = manifest(out)
    assertEquals(
      m("tables").obj.keySet.toSet,
      graphIds.keySet ++ analyticsIds.keySet ++ Set(
        "nodes-work",
        "researcher-metrics",
        "semantic-summaries"
      )
    )
    val rows = Map(
      "nodes-researcher" -> 3,
      "nodes-venue" -> 2,
      "nodes-topic" -> 1,
      "nodes-institution" -> 1,
      "edges-authored" -> 3,
      "edges-published-in" -> 2,
      "edges-coauthored" -> 6,
      "edge-support" -> 6
    )
    graphIds.foreach { (name, ids) =>
      val table = m("tables")(name)
      assertEquals(table("idColumns").arr.map(_.str).toVector, ids, name)
      assertEquals(table("rows").num.toInt, rows(name), name)
      val required = ExportTables.all.find(_.name == name).get.required
      val columns = table("columns").arr.map(_("name").str).toSet
      assert(
        required.forall(columns.contains),
        s"$name lacks a required column"
      )
      assertEquals(tableLines(out, name).size, rows(name), name)
      tableLines(out, name).foreach { row =>
        val p = upickle.default.read[Provenance](row("provenance"))
        assertEquals(p.runId, runId, name)
      }
      table("files").arr.foreach { file =>
        val path = out.resolve(file("path").str)
        assertEquals(file("bytes").num.toLong, Files.size(path), name)
        assertEquals(file("sha256").str, Digests.sha256File(path), name)
      }
    }
    val researchers = tableLines(out, "nodes-researcher")
    assertEquals(
      researchers.map(_("nodeId").str),
      Vector("mention:v1:a", "researcher:v1:x", "researcher:v1:y")
    )
    assertEquals(researchers(0)("orcid"), ujson.Null)
    assertEquals(researchers(0)("inResearchersExport"), ujson.Null)
    assertEquals(researchers(2)("memberCount"), ujson.Num(2))
    val authored = tableLines(out, "edges-authored")
    assertEquals(
      authored.map(_("mentionId").str),
      Vector("mention:v1:a", "mention:v1:c", "mention:v1:b")
    )
    assertEquals(authored(0)("year"), ujson.Null)
    assertEquals(authored(1)("countedInMetrics"), ujson.True)
    val coauthored = tableLines(out, "edges-coauthored")
    assertEquals(
      coauthored.map(_("edgeId").str),
      plainEdges.map(_._1).sorted
    )
    val first = coauthored.head
    assertEquals(first("window").str, "all")
    assertEquals(first("windowStart"), ujson.Num(1))
    assertEquals(first("representativeWorkIds"), ujson.Arr("work:v1:1"))
    assertEquals(first("endpointAUnresolved"), ujson.False)
    assertEquals(coauthored(4)("edgeId").str, "mention-coauthor:v1:aa")
    assertEquals(coauthored(4)("endpointAUnresolved"), ujson.True)
    val support = tableLines(out, "edge-support")
    assertEquals(
      support.map(r => (r("edgeId").str, r("canonicalWorkId").str)),
      plainEdges.map(e => (e._1, "work:v1:1")).sorted
    )
    assert(text.contains("table edge-support rows=6 files=3"), text)
  }

  test("the graph tables export identically twice, digests included") {
    val run = makeRun()
    val first = outDir
    val second = outDir
    assertEquals(runExport(run, first, Vector("--rows-per-file", "2"))._1, 0)
    assertEquals(runExport(run, second, Vector("--rows-per-file", "2"))._1, 0)
    def digests(out: Path): Map[String, String] =
      graphIds.keys.toVector.sorted.flatMap { name =>
        manifest(out)("tables")(name)("files").arr
          .map(f => f("path").str -> f("sha256").str)
      }.toMap
    val one = digests(first)
    assert(one.size >= graphIds.size, one.toString)
    assertEquals(digests(second), one)
    assertEquals(
      Files.readAllBytes(second.resolve("export-manifest.json")).toVector,
      Files.readAllBytes(first.resolve("export-manifest.json")).toVector
    )
  }

  test("the six analytics tables carry their required columns and provenance") {
    val out = outDir
    val (code, text) = runExport(makeRun(), out, Vector("--rows-per-file", "2"))
    assertEquals(code, 0, text)
    val m = manifest(out)
    val rows = Map(
      "path-evidence" -> 4,
      "components" -> 3,
      "component-membership" -> 4,
      "communities" -> 3,
      "community-membership" -> 4,
      "community-cross-edges" -> 1
    )
    analyticsIds.foreach { (name, ids) =>
      val table = m("tables")(name)
      assertEquals(table("idColumns").arr.map(_.str).toVector, ids, name)
      assertEquals(table("rows").num.toInt, rows(name), name)
      val required = ExportTables.all.find(_.name == name).get.required
      val columns = table("columns").arr.map(_("name").str).toSet
      assertEquals(required.filterNot(columns.contains), Vector.empty[String])
      val lines = tableLines(out, name)
      assertEquals(lines.size, rows(name), name)
      lines.foreach { row =>
        assert(required.forall(row.obj.contains), s"$name row lacks a column")
        val p = upickle.default.read[Provenance](row("provenance"))
        assertEquals(p.runId, runId, name)
        assertEquals(p.sourceUri, uri(name), name)
      }
      table("files").arr.foreach { file =>
        val path = out.resolve(file("path").str)
        assertEquals(file("sha256").str, Digests.sha256File(path), name)
      }
    }
    assertEquals(
      tableLines(out, "components").map(r =>
        (r("window").str, r("componentId").str)
      ),
      Vector(("2000-2004", "r1"), ("all", "r1"), ("all", "r9"))
    )
    assertEquals(tableLines(out, "components")(1)("isLargest"), ujson.True)
    assertEquals(
      tableLines(out, "components")(1)("componentSize"),
      ujson.Num(2)
    )
    val cross = tableLines(out, "community-cross-edges").head
    assertEquals(cross("communityA").str, "k1")
    assertEquals(cross("communityB").str, "k2")
    assertEquals(cross("edgeCount"), ujson.Num(1))
    assertEquals(cross("totalWeight"), ujson.Num(2))
    assertEquals(
      tableLines(out, "communities")(1)("internalWeight"),
      ujson.Num(3)
    )
    assertEquals(
      tableLines(out, "community-membership").map(_("researcherId").str),
      Vector("r1", "r1", "r2", "r9")
    )
    assertEquals(
      tableLines(out, "component-membership").map(_("componentId").str),
      Vector("r1", "r1", "r1", "r9")
    )
    assert(text.contains("table path-evidence rows=4 files=2"), text)
  }

  test("a path-evidence status row with a null hopIndex exports") {
    val out = outDir
    val (code, text) = runExport(makeRun(), out, Vector("--rows-per-file", "2"))
    assertEquals(code, 0, text)
    assert(text.contains("SPARK-EXPORT RESULT: SUCCESS"), text)
    val rows = tableLines(out, "path-evidence")
    assertEquals(
      rows.map(r => (r("pairKey").str, r("hopIndex"))),
      Vector(
        ("2000-2004|r1|r1", ujson.Null),
        ("all|r1|r3", ujson.Num(0)),
        ("all|r1|r3", ujson.Num(1)),
        ("all|r1|r9", ujson.Null)
      )
    )
    val unreachable = rows(3)
    assertEquals(unreachable("status").str, "unreachable-within-bound")
    assertEquals(unreachable("pathLength"), ujson.Null)
    assertEquals(unreachable("fromId"), ujson.Null)
    assertEquals(unreachable("supportingWorkIds"), ujson.Null)
    assertEquals(unreachable("unreachableReason").str, "no path within 4 hops")
    val trivial = rows(0)
    assertEquals(trivial("status").str, "trivial")
    assertEquals(trivial("pathLength"), ujson.Num(0))
    assertEquals(trivial("window").str, "2000-2004")
    assertEquals(rows(1)("fromId").str, "r1")
    assertEquals(rows(2)("supportingWorkIds"), ujson.Arr("work-23"))
    assertEquals(rows(2)("jointPublicationCount"), ujson.Num(1))
    assertEquals(rows(2)("hopLimit"), ujson.Num(4))
  }

  test("the manifest lists all 17 tables and is deterministic across runs") {
    val run = makeRun()
    val first = outDir
    val second = outDir
    assertEquals(runExport(run, first, Vector("--rows-per-file", "2"))._1, 0)
    assertEquals(runExport(run, second, Vector("--rows-per-file", "2"))._1, 0)
    val expected = Set(
      "semantic-summaries",
      "nodes-work",
      "researcher-metrics",
      "nodes-researcher",
      "nodes-venue",
      "nodes-topic",
      "nodes-institution",
      "edges-authored",
      "edges-published-in",
      "edges-coauthored",
      "edge-support",
      "path-evidence",
      "components",
      "component-membership",
      "communities",
      "community-membership",
      "community-cross-edges"
    )
    assertEquals(expected.size, 17)
    assertEquals(ExportTables.all.map(_.name).toSet, expected)
    assertEquals(ExportTables.all.size, 17)
    assertEquals(manifest(first)("tables").obj.keySet.toSet, expected)
    assertEquals(
      Files.readAllBytes(second.resolve("export-manifest.json")).toVector,
      Files.readAllBytes(first.resolve("export-manifest.json")).toVector
    )
    assertEquals(tree(second), tree(first))
  }

  test("a missing analytics table or a missing required column is refused") {
    analyticsIds.keys.toVector.sorted.foreach { name =>
      val out = outDir
      val (code, text) = runExport(makeRun(skip = Set(name)), out)
      assertEquals(code, 2, text)
      assert(text.contains(s"Missing table $name"), text)
      assert(!Files.exists(out), name)
    }
    val thin = frame("pairKey STRING, window STRING, " + provenanceDdl)(
      Row("p", "all", prov("p", "u"))
    )
    val root = makeRun(skip = Set("path-evidence"))
    write(root, "path-evidence", thin)
    val (code, text) = runExport(root, outDir)
    assertEquals(code, 2, text)
    assert(text.contains("Table path-evidence lacks column(s)"), text)
    assert(text.contains("hopIndex"), text)
  }

  test("a missing graph table or a missing projector column is refused") {
    graphIds.keys.toVector.sorted.foreach { name =>
      val out = outDir
      val (code, text) = runExport(makeRun(skip = Set(name)), out)
      assertEquals(code, 2, text)
      assert(text.contains(s"Missing table $name"), text)
      assert(!Files.exists(out), name)
    }
    val thin = frame("edgeId STRING, window STRING, " + provenanceDdl)(
      Row("e", "all", prov("e", "u"))
    )
    val root = makeRun(skip = Set("edge-support"))
    write(root, "edge-support", thin)
    val (code, text) = runExport(root, outDir)
    assertEquals(code, 2, text)
    assert(text.contains("Table edge-support lacks column(s)"), text)
    assert(text.contains("canonicalWorkId"), text)
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
