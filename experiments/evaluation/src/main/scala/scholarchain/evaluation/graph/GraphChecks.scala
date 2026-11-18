package scholarchain.evaluation.graph

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.util.Random
import scholarchain.evaluation.io.*

/** One author row that supports a traced edge. */
final case class TraceAuthored(
    researcherId: String,
    mentionId: Option[String],
    authorPosition: Option[Int],
    sourceRecordId: Option[String],
    where: String
)

/** One supporting work of a traced edge, with its node and authored rows. */
final case class TraceWork(
    workId: String,
    workWhere: Option[String],
    workSource: Option[String],
    workSourceRecordId: Option[String],
    authored: Vector[TraceAuthored]
)

/** Edge -> supporting works -> authored edges -> source record ids. */
final case class TraceEntry(
    edgeId: String,
    window: String,
    researcherA: String,
    researcherB: String,
    jointPublicationCount: Long,
    edgeWhere: String,
    edgeSourceRecordId: Option[String],
    supportWorkCount: Int,
    works: Vector[TraceWork],
    problems: Vector[String]
):
  def complete: Boolean = problems.isEmpty

/** Counters of the offline graph checks. Counter semantics:
  *
  *   - `danglingEdgeEndpoints`: an endpoint of `edges-coauthored` (researcherA,
  *     researcherB) or `researcherNodeId` of `edges-authored` that is in no
  *     `nodes-researcher` row, per endpoint. Endpoints named by `edge-support`
  *     and `path-evidence` are reported in `detail` only.
  *   - `danglingWorkRefs`: `canonicalWorkId` of `edge-support` or
  *     `edges-authored` that is in no `nodes-work` row.
  *   - `edgesWithoutSupport`: an `edges-coauthored` row whose edgeId has no
  *     `edge-support` row.
  *   - `jointCountMismatches`: an edge with at least one support row whose
  *     `jointPublicationCount` differs from its number of distinct supporting
  *     works. An edge with no support row is counted only in
  *     `edgesWithoutSupport`, so the two counters never overlap.
  *   - `supportRowsToMissingEdges`: `edge-support` rows whose edgeId is no
  *     edgeId of `edges-coauthored` (an orphan support row), per row. Other
  *     counters never include them.
  *   - `pathHopsNotInEdges`: a `path-evidence` hop row (one with an edgeId)
  *     whose edgeId is not an edgeId of `edges-coauthored`, or whose endpoints
  *     {fromId, toId} differ from the edge's {researcherA, researcherB}, or
  *     whose window differs from the edge's window. The match is on the exact
  *     edgeId: nothing is normalised, so a mismatch is reported, not repaired.
  *     Each bad row counts once; `detail` splits the reasons. Status rows (null
  *     edgeId) are not hops.
  *   - `provenanceRunIdMismatches`: rows of any table whose `provenance.runId`
  *     differs from the expected run id (the manifest `sourceRunId`, else the
  *     most frequent runId). Rows with no provenance are `provenanceMissing`.
  *   - `orphanResearchers` / `orphanWorks`: distinct `nodes-researcher` ids or
  *     `nodes-work` ids with no `edges-authored` row.
  *   - `duplicateIds`: per table, rows minus distinct id keys.
  */
final case class GraphCheckReport(
    exportDir: String,
    expectedRunId: String,
    runIdBasis: String,
    missingTables: Vector[String],
    rowTotals: Map[String, Long],
    duplicateIds: Map[String, Long],
    danglingEdgeEndpoints: Long,
    danglingWorkRefs: Long,
    edgesTotal: Long,
    edgesWithoutSupport: Long,
    jointCountMismatches: Long,
    supportRowsToMissingEdges: Long,
    pathHopsChecked: Long,
    pathHopsNotInEdges: Long,
    provenanceRunIdMismatches: Long,
    provenanceMissing: Long,
    provenanceByTable: Map[String, Long],
    orphanResearchers: Long,
    orphanWorks: Long,
    detail: Map[String, Long],
    reconciliation: Map[String, Long],
    traceSampleSize: Int,
    trace: Vector[TraceEntry]
):
  def traceIncomplete: Long = trace.count(!_.complete).toLong

  /** True when a required table is missing: the counters then say nothing about
    * that table, and the run must not read as a pass.
    */
  def incomplete: Boolean = missingTables.nonEmpty

  def toJson: ujson.Value =
    val researchersTotal = rowTotals.getOrElse("nodes-researcher", 0L)
    val worksTotal = rowTotals.getOrElse("nodes-work", 0L)
    val authoredTotal = rowTotals.getOrElse("edges-authored", 0L)
    val edgeRows = rowTotals.getOrElse("edges-coauthored", 0L)
    val allRows = rowTotals.values.sum
    Js.obj(
      Vector(
        "exportDir" -> Js.s(exportDir),
        "expectedRunId" -> Js.s(expectedRunId),
        "runIdBasis" -> Js.s(runIdBasis),
        "status" -> Js.s(if incomplete then "INCOMPLETE" else "COMPLETE"),
        "missingTables" -> Js.arr(missingTables.map(Js.s)),
        "rowTotals" -> Js.counts(rowTotals),
        "duplicateIds" -> Js.counts(duplicateIds),
        "counters" -> Js.counts(
          Map(
            "danglingEdgeEndpoints" -> danglingEdgeEndpoints,
            "danglingWorkRefs" -> danglingWorkRefs,
            "edgesTotal" -> edgesTotal,
            "edgesWithoutSupport" -> edgesWithoutSupport,
            "jointCountMismatches" -> jointCountMismatches,
            "supportRowsToMissingEdges" -> supportRowsToMissingEdges,
            "pathHopsChecked" -> pathHopsChecked,
            "pathHopsNotInEdges" -> pathHopsNotInEdges,
            "provenanceRunIdMismatches" -> provenanceRunIdMismatches,
            "provenanceMissing" -> provenanceMissing,
            "orphanResearchers" -> orphanResearchers,
            "orphanWorks" -> orphanWorks,
            "traceIncomplete" -> traceIncomplete
          )
        ),
        "rates" -> Js.obj(
          Vector(
            "edgeSupportRate" ->
              Js.rate(edgesTotal - edgesWithoutSupport, edgesTotal),
            "jointCountAgreementRate" -> Js.rate(
              edgesTotal - edgesWithoutSupport - jointCountMismatches,
              edgesTotal - edgesWithoutSupport
            ),
            "orphanResearcherRate" ->
              Js.rate(orphanResearchers, researchersTotal),
            "orphanWorkRate" -> Js.rate(orphanWorks, worksTotal),
            // The checked endpoints are two per edges-coauthored row and one
            // per edges-authored row, so the denominator counts rows, not the
            // distinct edge ids of `edgesTotal`.
            "danglingEndpointRate" -> Js.rate(
              danglingEdgeEndpoints,
              2L * edgeRows + authoredTotal
            ),
            "pathHopFailureRate" -> Js
              .rate(pathHopsNotInEdges, pathHopsChecked),
            "provenanceMismatchRate" ->
              Js.rate(provenanceRunIdMismatches, allRows)
          ) ++ rowTotals.toVector.sortBy(_._1).map { (table, total) =>
            s"duplicateRate[$table]" ->
              Js.rate(duplicateIds.getOrElse(table, 0L), total)
          }
        ),
        "provenanceMismatchesByTable" -> Js.counts(provenanceByTable),
        "detail" -> Js.counts(detail),
        "reconciliation" -> Js.counts(reconciliation),
        "traceSampleSize" -> Js.l(traceSampleSize.toLong),
        "traceSampled" -> Js.l(trace.size.toLong)
      )
    )

object GraphChecks:
  val ToolVersion = "graph-checks/1.1.0"
  val MaxTraceWorks = 5

  /** Exit code when a required table is missing (2 is a usage error). */
  val IncompleteExit = 3

  private val priority = Vector(
    "nodes-researcher",
    "nodes-work",
    "edges-coauthored",
    "edge-support",
    "edges-authored",
    "path-evidence"
  )

  private val required = priority

  /** Id columns when the manifest does not list them. `path-evidence` has
    * several rows per pair, so it is keyed on the pair and the hop index.
    */
  private val defaultIds: Map[String, Vector[String]] = Map(
    "nodes-researcher" -> Vector("nodeId"),
    "nodes-work" -> Vector("canonicalWorkId"),
    "nodes-venue" -> Vector("venueId"),
    "nodes-topic" -> Vector("topicId"),
    "nodes-institution" -> Vector("institutionId"),
    "edges-authored" ->
      Vector("researcherNodeId", "canonicalWorkId", "mentionId"),
    "edges-published-in" -> Vector("fromId", "toId"),
    "edges-coauthored" -> Vector("edgeId"),
    "edge-support" -> Vector("edgeId", "canonicalWorkId"),
    "semantic-summaries" -> Vector("summaryId"),
    "researcher-metrics" -> Vector("researcherId", "window"),
    "components" -> Vector("window", "componentId"),
    "component-membership" -> Vector("window", "researcherId"),
    "communities" -> Vector("window", "communityId"),
    "community-membership" -> Vector("window", "researcherId"),
    "community-cross-edges" -> Vector("window", "communityA", "communityB"),
    "path-evidence" -> Vector("pairKey", "hopIndex")
  )

  private val absentRunId = "<no provenance>"

  private final case class EdgeInfo(
      a: String,
      b: String,
      window: String,
      joint: Long,
      where: String,
      sourceRecordId: Option[String]
  )

  def run(
      exportDir: Path,
      sampleSeed: Long,
      sampleSize: Int
  ): GraphCheckReport =
    runWith(exportDir, sampleSeed, sampleSize, None, None)

  def runWith(
      exportDir: Path,
      sampleSeed: Long,
      sampleSize: Int,
      expectedVertices: Option[Long],
      expectedEdges: Option[Long]
  ): GraphCheckReport =
    val manifestPath = exportDir.resolve("export-manifest.json")
    val manifest: Option[ujson.Value] =
      if Files.isRegularFile(manifestPath) then
        Some(ExportFiles.readJson(manifestPath))
      else None
    val manifestRunId = manifest.flatMap(m => J.str(m, "sourceRunId"))
    val manifestIds: Map[String, Vector[String]] =
      manifest
        .flatMap(_.objOpt)
        .flatMap(_.get("tables"))
        .flatMap(_.objOpt)
        .map { tables =>
          tables.iterator
            .map((name, t) => name -> J.strings(t, "idColumns"))
            .filter(_._2.nonEmpty)
            .toMap
        }
        .getOrElse(Map.empty[String, Vector[String]])

    def idColumns(table: String): Vector[String] =
      if table == "path-evidence" then defaultIds(table)
      else
        manifestIds.getOrElse(table, defaultIds.getOrElse(table, Vector.empty))

    val present = ExportFiles.tables(exportDir)
    val missing = required.filterNot(present.contains)
    val order = priority.filter(present.contains) ++
      present.filterNot(priority.contains)

    val rowTotals = mutable.LinkedHashMap.empty[String, Long]
    val duplicates = mutable.LinkedHashMap.empty[String, Long]
    val runIds = mutable.HashMap.empty[String, mutable.HashMap[String, Long]]
    val researchers = mutable.HashMap.empty[String, String]
    val works = mutable.HashMap.empty[String, JsonRowInfo]
    val edges = mutable.HashMap.empty[String, EdgeInfo]
    val supports = mutable.HashMap.empty[String, mutable.HashSet[String]]
    val authoredResearchers = mutable.HashSet.empty[String]
    val authoredWorks = mutable.HashSet.empty[String]
    val detail = mutable.TreeMap.empty[String, Long]
    val windowCounts = mutable.TreeMap.empty[String, Long]
    var danglingEndpoints = 0L
    var danglingWorks = 0L
    var orphanSupportRows = 0L
    var hopsChecked = 0L
    var hopsBad = 0L

    def bump(key: String): Unit =
      detail(key) = detail.getOrElse(key, 0L) + 1L

    def endpointCheck(table: String, column: String, id: String): Boolean =
      val ok = researchers.contains(id)
      if !ok then bump(s"dangling:$table.$column")
      ok

    def perRow(table: String)(row: JsonRow): Unit =
      val json = row.json
      table match
        case "nodes-researcher" =>
          J.str(json, "nodeId").foreach { id =>
            researchers(id) = J.str(json, "nodeKind").getOrElse("unknown")
          }
        case "nodes-work" =>
          J.str(json, "canonicalWorkId").foreach { id =>
            works(id) = JsonRowInfo(
              row.where,
              J.str(json, "source"),
              J.sourceRecordId(json)
            )
          }
        case "edges-coauthored" =>
          val id = J.scalar(json, "edgeId")
          val a = J.scalar(json, "researcherA")
          val b = J.scalar(json, "researcherB")
          if !endpointCheck(table, "researcherA", a) then
            danglingEndpoints += 1L
          if !endpointCheck(table, "researcherB", b) then
            danglingEndpoints += 1L
          val window = J.scalar(json, "window")
          windowCounts(window) = windowCounts.getOrElse(window, 0L) + 1L
          edges(id) = EdgeInfo(
            a,
            b,
            window,
            J.long(json, "jointPublicationCount").getOrElse(-1L),
            row.where,
            J.sourceRecordId(json)
          )
        case "edge-support" =>
          val id = J.scalar(json, "edgeId")
          val work = J.scalar(json, "canonicalWorkId")
          supports.getOrElseUpdate(id, mutable.HashSet.empty[String]) += work
          if !works.contains(work) then
            danglingWorks += 1L
            bump("dangling:edge-support.canonicalWorkId")
          if !edges.contains(id) then
            orphanSupportRows += 1L
            bump("edge-support.edgeId-not-in-edges")
          Vector("researcherA", "researcherB").foreach { column =>
            J.str(json, column).foreach { r =>
              endpointCheck(table, column, r)
            }
          }
        case "edges-authored" =>
          val r = J.scalar(json, "researcherNodeId")
          val work = J.scalar(json, "canonicalWorkId")
          authoredResearchers += r
          authoredWorks += work
          if !endpointCheck(table, "researcherNodeId", r) then
            danglingEndpoints += 1L
          if !works.contains(work) then
            danglingWorks += 1L
            bump("dangling:edges-authored.canonicalWorkId")
        case "path-evidence" =>
          J.str(json, "edgeId").foreach { edgeId =>
            hopsChecked += 1L
            val from = J.scalar(json, "fromId")
            val to = J.scalar(json, "toId")
            Vector("fromId" -> from, "toId" -> to).foreach { (column, id) =>
              endpointCheck(table, column, id)
            }
            val problems = Vector.newBuilder[String]
            edges.get(edgeId) match
              case None       => problems += "path-hop:edge-missing"
              case Some(edge) =>
                if Set(edge.a, edge.b) != Set(from, to) then
                  problems += "path-hop:endpoints-differ"
                if edge.window != J.scalar(json, "window") then
                  problems += "path-hop:window-differs"
                val joint = J.long(json, "jointPublicationCount")
                if joint.exists(_ != edge.joint) then
                  bump("path-hop:joint-differs (informational)")
            val found = problems.result()
            if found.nonEmpty then
              hopsBad += 1L
              found.foreach(bump)
          }
        case _ => ()

    order.foreach { table =>
      val ids = idColumns(table)
      val seen = mutable.HashSet.empty[String]
      var dup = 0L
      val counts = mutable.HashMap.empty[String, Long]
      val total = ExportFiles.scan(exportDir, table) { row =>
        if ids.nonEmpty then
          val key = ids.map(c => J.scalar(row.json, c)).mkString("\u0000")
          if !seen.add(key) then dup += 1L
        val rid = J.runId(row.json).getOrElse(absentRunId)
        counts(rid) = counts.getOrElse(rid, 0L) + 1L
        perRow(table)(row)
      }
      rowTotals(table) = total
      duplicates(table) = dup
      runIds(table) = counts
    }

    // Provenance: the manifest run id, else the most frequent run id.
    val overall = mutable.HashMap.empty[String, Long]
    runIds.values.foreach(_.foreach { (k, v) =>
      if k != absentRunId then overall(k) = overall.getOrElse(k, 0L) + v
    })
    val (expected, basis) = manifestRunId match
      case Some(id) => (id, "export-manifest.json sourceRunId")
      case None     =>
        (
          overall.toVector
            .sortBy((k, v) => (-v, k))
            .headOption
            .map(_._1)
            .getOrElse("UNKNOWN"),
          "most frequent provenance.runId (no manifest sourceRunId)"
        )
    val provByTable = mutable.LinkedHashMap.empty[String, Long]
    var provMismatch = 0L
    var provMissing = 0L
    runIds.foreach { (table, counts) =>
      val absent = counts.getOrElse(absentRunId, 0L)
      val good = counts.getOrElse(expected, 0L)
      val bad = counts.values.sum - absent - good
      provByTable(table) = bad
      provMismatch += bad
      provMissing += absent
    }

    // Edge support.
    var withoutSupport = 0L
    var jointMismatch = 0L
    edges.foreach { (id, edge) =>
      val n = supports.get(id).map(_.size).getOrElse(0)
      if n == 0 then withoutSupport += 1L
      else if edge.joint != n.toLong then jointMismatch += 1L
    }
    edges.keys.groupBy(prefixOf).foreach { (prefix, ids) =>
      val unsupported = ids.count(id => !supports.contains(id)).toLong
      detail(s"edges[$prefix]") = ids.size.toLong
      detail(s"edgesWithoutSupport[$prefix]") = unsupported
    }

    // Orphans.
    val orphanR = researchers.keys.filterNot(authoredResearchers.contains)
    val orphanW = works.keys.filterNot(authoredWorks.contains)
    researchers
      .filter((id, _) => !authoredResearchers.contains(id))
      .groupBy(_._2)
      .foreach((kind, rows) =>
        detail(s"orphanResearchers[$kind]") = rows.size.toLong
      )

    // Reconciliation with the Neptune load: informational, not a pass or fail.
    val vertexTables =
      Vector("nodes-researcher", "nodes-work", "nodes-venue", "nodes-topic")
        ++ Vector("nodes-institution")
    val edgeTables =
      Vector("edges-authored", "edges-published-in", "edges-coauthored")
    val recon = mutable.TreeMap.empty[String, Long]
    recon("vertexTablesRowSum") =
      vertexTables.map(rowTotals.getOrElse(_, 0L)).sum
    recon("edgeTablesRowSum") = edgeTables.map(rowTotals.getOrElse(_, 0L)).sum
    windowCounts.foreach((w, n) => recon(s"edges-coauthored[window=$w]") = n)
    expectedVertices.foreach { v =>
      recon("expectedVertices") = v
      recon("vertexDifference") = recon("vertexTablesRowSum") - v
    }
    expectedEdges.foreach { e =>
      recon("expectedEdges") = e
      recon("edgeDifference") = recon("edgeTablesRowSum") - e
    }

    // Provenance trace sample.
    val sortedEdges = edges.keys.toVector.sorted
    val sampled =
      new Random(sampleSeed).shuffle(sortedEdges).take(math.max(0, sampleSize))
    val trace = buildTrace(exportDir, sampled, edges.toMap, supports, works)

    GraphCheckReport(
      exportDir.toString,
      expected,
      basis,
      missing,
      rowTotals.toMap,
      duplicates.toMap,
      danglingEndpoints,
      danglingWorks,
      edges.size.toLong,
      withoutSupport,
      jointMismatch,
      orphanSupportRows,
      hopsChecked,
      hopsBad,
      provMismatch,
      provMissing,
      provByTable.toMap,
      orphanR.size.toLong,
      orphanW.size.toLong,
      detail.toMap,
      recon.toMap,
      sampleSize,
      trace
    )

  private final case class JsonRowInfo(
      where: String,
      source: Option[String],
      sourceRecordId: Option[String]
  )

  private def prefixOf(edgeId: String): String =
    edgeId.split(":").take(2).mkString(":")

  private def buildTrace(
      exportDir: Path,
      sampled: Vector[String],
      edges: Map[String, EdgeInfo],
      supports: mutable.HashMap[String, mutable.HashSet[String]],
      works: mutable.HashMap[String, JsonRowInfo]
  ): Vector[TraceEntry] =
    def supportOf(id: String): Vector[String] =
      supports.get(id).map(_.toVector.sorted).getOrElse(Vector.empty)
    val shown: Map[String, Vector[String]] =
      sampled.map(id => id -> supportOf(id).take(MaxTraceWorks)).toMap
    val wanted = mutable.HashSet.empty[(String, String)]
    sampled.foreach { id =>
      val edge = edges(id)
      shown(id).foreach { w =>
        wanted += ((edge.a, w))
        wanted += ((edge.b, w))
      }
    }
    val authored =
      mutable.HashMap.empty[(String, String), Vector[TraceAuthored]]
    if wanted.nonEmpty then
      ExportFiles.scan(exportDir, "edges-authored") { row =>
        val r = J.scalar(row.json, "researcherNodeId")
        val w = J.scalar(row.json, "canonicalWorkId")
        if wanted.contains((r, w)) then
          val entry = TraceAuthored(
            r,
            J.str(row.json, "mentionId"),
            J.int(row.json, "authorPosition"),
            J.sourceRecordId(row.json),
            row.where
          )
          authored((r, w)) = authored.getOrElse((r, w), Vector.empty) :+ entry
      }
    sampled.map { id =>
      val edge = edges(id)
      val problems = Vector.newBuilder[String]
      if edge.sourceRecordId.isEmpty then problems += "edge-provenance-missing"
      if shown(id).isEmpty then problems += "no-support-rows"
      val traced = shown(id).map { w =>
        val info = works.get(w)
        if info.isEmpty then problems += s"work-node-missing:$w"
        val rows = Vector(edge.a, edge.b).flatMap { r =>
          val found = authored.getOrElse((r, w), Vector.empty)
          if found.isEmpty then problems += s"no-authored-row:$r:$w"
          found
        }
        TraceWork(
          w,
          info.map(_.where),
          info.flatMap(_.source),
          info.flatMap(_.sourceRecordId),
          rows
        )
      }
      TraceEntry(
        id,
        edge.window,
        edge.a,
        edge.b,
        edge.joint,
        edge.where,
        edge.sourceRecordId,
        supportOf(id).size,
        traced,
        problems.result()
      )
    }

  def traceJson(entry: TraceEntry): ujson.Value =
    Js.obj(
      Vector(
        "edgeId" -> Js.s(entry.edgeId),
        "window" -> Js.s(entry.window),
        "researcherA" -> Js.s(entry.researcherA),
        "researcherB" -> Js.s(entry.researcherB),
        "jointPublicationCount" -> Js.l(entry.jointPublicationCount),
        "edgeRow" -> Js.s(entry.edgeWhere),
        "edgeSourceRecordId" -> Js.opt(entry.edgeSourceRecordId),
        "supportWorkCount" -> Js.l(entry.supportWorkCount.toLong),
        "complete" -> Js.b(entry.complete),
        "problems" -> Js.arr(entry.problems.map(Js.s)),
        "works" -> Js.arr(entry.works.map { w =>
          Js.obj(
            Vector(
              "workId" -> Js.s(w.workId),
              "workRow" -> Js.opt(w.workWhere),
              "workSource" -> Js.opt(w.workSource),
              "workSourceRecordId" -> Js.opt(w.workSourceRecordId),
              "authored" -> Js.arr(w.authored.map { a =>
                Js.obj(
                  Vector(
                    "researcherId" -> Js.s(a.researcherId),
                    "mentionId" -> Js.opt(a.mentionId),
                    "authorPosition" ->
                      a.authorPosition.fold[ujson.Value](ujson.Null)(p =>
                        Js.l(p.toLong)
                      ),
                    "sourceRecordId" -> Js.opt(a.sourceRecordId),
                    "row" -> Js.s(a.where)
                  )
                )
              })
            )
          )
        })
      )
    )

  final case class Args(
      exportDir: Path,
      outBase: Path,
      seed: Long,
      sample: Int,
      runId: String,
      expectedVertices: Long,
      expectedEdges: Long
  )

  val Usage: String =
    "Usage: GraphChecks <export dir> <output base dir> [--seed N] " +
      "[--sample N] [--run-id ID] [--expect-vertices N] [--expect-edges N]"

  def parseArgs(args: Vector[String]): Either[String, Args] =
    def loop(
        rest: Vector[String],
        acc: Map[String, String]
    ): Either[String, Map[String, String]] =
      rest match
        case Vector() => Right(acc)
        case flag +: value +: tail
            if flag.startsWith("--") && !value.startsWith("--") =>
          loop(tail, acc.updated(flag, value))
        case other +: _ => Left(s"Unexpected argument: $other")
    args match
      case dir +: out +: rest =>
        loop(rest, Map.empty).flatMap { opts =>
          def long(flag: String, default: Long): Either[String, Long] =
            opts.get(flag) match
              case None    => Right(default)
              case Some(v) => v.toLongOption.toRight(s"$flag needs a number")
          for
            seed <- long("--seed", 16L)
            sample <- long("--sample", 50L)
            vertices <- long("--expect-vertices", 32225L)
            edgeCount <- long("--expect-edges", 135788L)
          yield Args(
            Paths.get(dir),
            Paths.get(out),
            seed,
            sample.toInt,
            opts.getOrElse("--run-id", s"graphchecks-${RunOutput.stamp()}"),
            vertices,
            edgeCount
          )
        }
      case _ => Left("Expected <export dir> <output base dir>")

  def main(args: Array[String]): Unit =
    System.exit(cli(args.toVector))

  def cli(args: Vector[String]): Int =
    parseArgs(args) match
      case Left(error) =>
        println(Usage)
        println(error)
        2
      case Right(a) =>
        val report = runWith(
          a.exportDir,
          a.seed,
          a.sample,
          Some(a.expectedVertices),
          Some(a.expectedEdges)
        )
        val dir = RunOutput.createRunDir(a.outBase, a.runId)
        RunOutput.write(
          dir,
          "graph-check-report.json",
          ujson.write(report.toJson, indent = 2) + "\n"
        )
        RunOutput.write(
          dir,
          "provenance-trace.jsonl",
          report.trace
            .map(e => ujson.write(traceJson(e)))
            .mkString("", "\n", "\n")
        )
        val manifestPath = a.exportDir.resolve("export-manifest.json")
        RunOutput.write(
          dir,
          "run-info.json",
          ujson.write(
            Js.obj(
              Vector(
                "tool" -> Js.s(ToolVersion),
                "runId" -> Js.s(a.runId),
                "exportDir" -> Js.s(a.exportDir.toString),
                "exportManifestSha256" -> Js.opt(
                  if Files.isRegularFile(manifestPath) then
                    Some(Digests.sha256File(manifestPath))
                  else None
                ),
                "seed" -> Js.l(a.seed),
                "sampleSize" -> Js.l(a.sample.toLong),
                "startedAt" -> Js.s(java.time.Instant.now().toString)
              )
            ),
            indent = 2
          ) + "\n"
        )
        RunOutput.seal(dir)
        if report.incomplete then
          // The run directory is kept as evidence, but the exit code is not 0:
          // a counter over a table that was not there proves nothing.
          println(
            s"GRAPHCHECKS INCOMPLETE: required table(s) missing: ${report.missingTables.mkString(", ")}; run directory $dir"
          )
        else println(s"GRAPHCHECKS RESULT: run directory $dir")
        println(ujson.write(report.toJson("counters"), indent = 2))
        if report.incomplete then IncompleteExit else 0
