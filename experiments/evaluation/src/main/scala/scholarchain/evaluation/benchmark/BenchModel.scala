package scholarchain.evaluation.benchmark

import java.nio.file.{Files, Path}
import scholarchain.evaluation.io.*

/** Where a label's ground truth sits in the export: table, file and line, and
  * the sha256 of the raw row, so a human can find and check it.
  */
final case class SourceRef(
    table: String,
    file: String,
    line: Int,
    rowSha: String,
    id: String
)

final case class ResearcherRow(
    id: String,
    kind: String,
    displayName: String,
    ref: SourceRef
)

final case class MetricsRow(
    researcherId: String,
    window: String,
    values: Map[String, Double],
    ref: SourceRef
)

final case class CoauthorEdge(
    edgeId: String,
    a: String,
    b: String,
    window: String,
    joint: Int,
    ref: SourceRef
)

final case class SupportRow(edgeId: String, workId: String)

final case class PathRow(
    pairKey: String,
    window: String,
    sourceId: String,
    targetId: String,
    status: String,
    pathLength: Option[Int],
    hopIndex: Option[Int],
    fromId: Option[String],
    toId: Option[String],
    edgeId: Option[String],
    ref: SourceRef
)

/** A community or component membership row. */
final case class MemberRow(
    researcherId: String,
    window: String,
    groupId: String,
    groupSize: Long,
    ref: SourceRef
)

/** The tables the generator reads, in memory. */
final case class BenchTables(
    sourceRunId: String,
    researchers: Vector[ResearcherRow],
    metrics: Vector[MetricsRow],
    edges: Vector[CoauthorEdge],
    support: Vector[SupportRow],
    paths: Vector[PathRow],
    communities: Vector[MemberRow],
    components: Vector[MemberRow]
)

object BenchTables:
  private val skipKeys = Set("windowStart", "windowEnd", "schemaVersion")

  private def ref(table: String, row: JsonRow, id: String): SourceRef =
    SourceRef(table, row.file, row.line, row.sha, id)

  private def members(
      root: Path,
      table: String,
      groupColumn: String,
      sizeColumn: String
  ): Vector[MemberRow] =
    ExportFiles.collect(root, table) { row =>
      for
        r <- J.str(row.json, "researcherId")
        w <- J.str(row.json, "window")
        g <- J.str(row.json, groupColumn)
      yield MemberRow(
        r,
        w,
        g,
        J.long(row.json, sizeColumn).getOrElse(0L),
        ref(table, row, r)
      )
    }

  def load(exportDir: Path): BenchTables =
    val manifestPath = exportDir.resolve("export-manifest.json")
    val runId =
      if Files.isRegularFile(manifestPath) then
        J.str(ExportFiles.readJson(manifestPath), "sourceRunId")
          .getOrElse("UNKNOWN")
      else "UNKNOWN"
    val researchers = ExportFiles.collect(exportDir, "nodes-researcher") {
      row =>
        J.str(row.json, "nodeId").map { id =>
          ResearcherRow(
            id,
            J.str(row.json, "nodeKind").getOrElse(""),
            J.str(row.json, "displayName").getOrElse(""),
            ref("nodes-researcher", row, id)
          )
        }
    }
    val metrics = ExportFiles.collect(exportDir, "researcher-metrics") { row =>
      for
        id <- J.str(row.json, "researcherId")
        window <- J.str(row.json, "window")
      yield
        val values: Map[String, Double] =
          row.json.objOpt
            .map { o =>
              o.iterator
                .filter((k, _) => !skipKeys.contains(k))
                .flatMap((k, v) => v.numOpt.map(n => k -> n))
                .toMap
            }
            .getOrElse(Map.empty[String, Double])
        MetricsRow(id, window, values, ref("researcher-metrics", row, id))
    }
    val edges = ExportFiles.collect(exportDir, "edges-coauthored") { row =>
      for
        id <- J.str(row.json, "edgeId")
        a <- J.str(row.json, "researcherA")
        b <- J.str(row.json, "researcherB")
        w <- J.str(row.json, "window")
      yield CoauthorEdge(
        id,
        a,
        b,
        w,
        J.int(row.json, "jointPublicationCount").getOrElse(0),
        ref("edges-coauthored", row, id)
      )
    }
    val support = ExportFiles.collect(exportDir, "edge-support") { row =>
      for
        e <- J.str(row.json, "edgeId")
        w <- J.str(row.json, "canonicalWorkId")
      yield SupportRow(e, w)
    }
    val paths = ExportFiles.collect(exportDir, "path-evidence") { row =>
      for
        key <- J.str(row.json, "pairKey")
        w <- J.str(row.json, "window")
        s <- J.str(row.json, "sourceId")
        t <- J.str(row.json, "targetId")
      yield PathRow(
        key,
        w,
        s,
        t,
        J.str(row.json, "status").getOrElse(""),
        J.int(row.json, "pathLength"),
        J.int(row.json, "hopIndex"),
        J.str(row.json, "fromId"),
        J.str(row.json, "toId"),
        J.str(row.json, "edgeId"),
        ref("path-evidence", row, key)
      )
    }
    BenchTables(
      runId,
      researchers,
      metrics,
      edges,
      support,
      paths,
      members(
        exportDir,
        "community-membership",
        "communityId",
        "communitySize"
      ),
      members(exportDir, "component-membership", "componentId", "componentSize")
    )

/** One benchmark question as the system sees it: no label, no operation. */
final case class Question(
    questionId: String,
    version: String,
    stratum: String,
    text: String
):
  def toJson: ujson.Value =
    Js.obj(
      Vector(
        "questionId" -> Js.s(questionId),
        "version" -> Js.s(version),
        "stratum" -> Js.s(stratum),
        "text" -> Js.s(text)
      )
    )

final case class ExpectedItem(id: String, grade: Int)

/** Ground truth. `numbers` and `extra` keep insertion order. */
final case class Expected(
    items: Vector[ExpectedItem],
    numbers: Vector[(String, Double)],
    abstain: Boolean,
    abstainClass: Option[String],
    extra: Vector[(String, ujson.Value)] = Vector.empty
):
  def toJson: ujson.Value =
    Js.obj(
      Vector(
        "items" -> Js.arr(items.map { i =>
          Js.obj(Vector("id" -> Js.s(i.id), "grade" -> Js.l(i.grade.toLong)))
        }),
        "numbers" -> Js.obj(numbers.map((k, v) => k -> Js.d(v))),
        "abstain" -> Js.b(abstain),
        "abstainClass" -> Js.opt(abstainClass)
      ) ++ extra
    )

final case class LabelSource(
    table: String,
    sourceRunId: String,
    rowSha: Option[String],
    rows: Vector[SourceRef]
):
  def toJson: ujson.Value =
    Js.obj(
      Vector(
        "table" -> Js.s(table),
        "sourceRunId" -> Js.s(sourceRunId),
        "rowSha" -> Js.opt(rowSha),
        "rows" -> Js.arr(rows.map { r =>
          Js.obj(
            Vector(
              "table" -> Js.s(r.table),
              "file" -> Js.s(r.file),
              "line" -> Js.l(r.line.toLong),
              "rowSha" -> Js.s(r.rowSha),
              "id" -> Js.s(r.id)
            )
          )
        })
      )
    )

/** One line of `labels-vN.jsonl`. `vectorAvailable` is None when unknown or not
  * applicable (see `vectorStatus`); `vectorItemsAvailable` counts graded items
  * whose subject still has a vector for the window.
  */
final case class Label(
    questionId: String,
    version: String,
    stratum: String,
    operation: String,
    subjectIds: Vector[String],
    window: Option[String],
    windows: Vector[String],
    expected: Expected,
    vectorAvailable: Option[Boolean],
    vectorStatus: String,
    vectorItemsAvailable: Int,
    vectorItemsTotal: Int,
    source: LabelSource
):
  def toJson: ujson.Value =
    Js.obj(
      Vector(
        "questionId" -> Js.s(questionId),
        "version" -> Js.s(version),
        "stratum" -> Js.s(stratum),
        "operation" -> Js.s(operation),
        "subjectIds" -> Js.arr(subjectIds.map(Js.s)),
        "window" -> Js.opt(window),
        "windows" -> Js.arr(windows.map(Js.s)),
        "expected" -> expected.toJson,
        "vectorAvailable" ->
          vectorAvailable.fold[ujson.Value](ujson.Null)(Js.b),
        "vectorStatus" -> Js.s(vectorStatus),
        "vectorItems" -> Js.obj(
          Vector(
            "available" -> Js.l(vectorItemsAvailable.toLong),
            "total" -> Js.l(vectorItemsTotal.toLong)
          )
        ),
        "source" -> source.toJson
      )
    )

final case class BenchItem(question: Question, label: Label)

/** Size limits of the labels, all generator caps. Only the coauthor-lookup list
  * follows a runtime limit (`PathLimits.coauthorLimit`, `Retrieval.scala:70`).
  * The researcher-lookup coauthor cap reuses that number but the runtime
  * returns no coauthors for that operation (README, "Researcher lookup" and
  * "Threats to validity"). The community member cap and the cross-edge
  * neighbour cap are chosen by the generator: no runtime member limit of that
  * size was found, so a label of that size is not shown to be within what the
  * runtime returns.
  */
object RuntimeLimits:
  /** `PathLimits.coauthorLimit` (`Retrieval.scala:70`): the coauthor-lookup
    * limit, reused as the generator's researcher-lookup cap.
    */
  val CoauthorLimit = 25

  /** Largest community or component the generator picks a subject from; the
    * member list of a label is capped at this size, never below it.
    */
  val MaxGroupSize = 30

/** Relevance grades of the graded labels. */
object Grades:
  /** Ranking tiers (decision P16-10): ranks 1-3 grade 3, 4-6 grade 2, 7-10
    * grade 1 (the coauthor-lookup scale). Ties share a rank, so they share a
    * grade. A rank above 10 is never labelled and grades 1 here.
    */
  def ranking(rank: Int): Int =
    if rank <= 3 then 3 else if rank <= 6 then 2 else 1

  /** Coauthor lookup tier by joint publication count: 3 or more, 2, 1. */
  def jointTier(joint: Int): Int =
    if joint >= 3 then 3 else if joint == 2 then 2 else 1

/** The committed mapping from an abstention class of the benchmark to the
  * runtime abstention codes (`CollaborationWorkflow`, `Workflow.scala`) that
  * satisfy it. The stub-mode test `AbstentionMappingSuite` runs every
  * unanswerable template through the real workflow and pins the code each one
  * produces, in the modes `graph` and `fused`. Vector mode reads no graph and
  * can answer from semantic context, so it is outside this mapping.
  *
  * The classifier call on a planner rule miss goes to the model: the stub
  * refuses "What is the capital of France?" (`planner-refused`); a real model
  * is checked only by run H, which records the code actually produced.
  */
object AbstentionCodes:
  /** The twelve abstention codes the workflow can emit. */
  val RuntimeCodes: Vector[String] = Vector(
    "invalid-question",
    "planner-refused",
    "needs-two-windows",
    "researcher-count",
    "identity-unresolved",
    "no-deterministic-evidence",
    "input-over-budget",
    "synthesis-failed",
    "claims-unsupported",
    "storage-error",
    "evidence-cap",
    "path-query-rejected"
  )

  val byClass: Map[String, Vector[String]] = Map(
    "unknown-researcher" -> Vector("identity-unresolved"),
    "ambiguous-name" -> Vector("identity-unresolved"),
    "future-window" -> Vector("no-deterministic-evidence"),
    "out-of-scope" -> Vector("planner-refused"),
    "unrankable-metric" -> Vector("researcher-count"),
    "missing-second-window" -> Vector("needs-two-windows")
  )

  def codesFor(abstainClass: String): Vector[String] =
    byClass.getOrElse(abstainClass, Vector.empty)

final case class BenchResult(
    items: Vector[BenchItem],
    shortfalls: Vector[String],
    freeFormSlots: Vector[ujson.Value]
)

final case class BenchConfig(
    seed: Long = BenchConfig.DefaultSeed,
    version: String = "v1",
    counts: Map[String, Int] = BenchConfig.DefaultCounts
)

object BenchConfig:
  val DefaultSeed = 20261117L
  val GeneratorVersion = "benchmark-gen/1.0.0"

  /** 50 generated questions; the 8 free-form ones are written by the human. */
  val DefaultCounts: Map[String, Int] = Map(
    "researcher-lookup" -> 7,
    "coauthor-lookup" -> 8,
    "ranking" -> 10,
    "shortest-path" -> 6,
    "community-component" -> 6,
    "temporal-comparison" -> 5,
    "unanswerable" -> 8,
    "free-form" -> 8
  )
