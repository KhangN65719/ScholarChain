package scholarchain.evaluation.benchmark

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scholarchain.evaluation.io.*

/** Whether a subject still has a vector for a window after the Phase 15
  * deletion of the chunk set C keys.
  *
  * Statuses: `available` (a summary of the subject and window exists in the
  * vector export and is not deleted), `deleted` (every such summary is among
  * the deleted keys), `absent` (the vector export has no summary of that
  * subject and window), `unknown` (no vector export was given).
  */
trait VectorCoverage:
  def status(subjectId: String, window: String): String
  def describe: ujson.Value

  /** Named checks of the rebuild against the chunk set manifest: sha256, bytes,
    * rows, by_summary_type, by_window. Empty when nothing was checked.
    */
  def checks: Vector[(String, Boolean)] = Vector.empty

/** How far the subjects of the graph (A) export and the vector (S) export
  * overlap. `shared` counts distinct `subjectId` values of `semantic-summaries`
  * present in both exports; `graphResearchersWithVectorSubject` counts
  * `nodes-researcher` ids of the graph export that are a `subjectId` of the
  * vector export.
  */
final case class SubjectOverlap(
    graphSummarySubjects: Long,
    vectorSummarySubjects: Long,
    shared: Long,
    graphResearchers: Long,
    graphResearchersWithVectorSubject: Long
):
  def toJson: ujson.Value =
    Js.obj(
      Vector(
        "graphSummarySubjects" -> Js.l(graphSummarySubjects),
        "vectorSummarySubjects" -> Js.l(vectorSummarySubjects),
        "shared" -> Js.l(shared),
        "graphResearchers" -> Js.l(graphResearchers),
        "graphResearchersWithVectorSubject" ->
          Js.l(graphResearchersWithVectorSubject)
      )
    )

object VectorCoverage:
  val Available = "available"
  val Deleted = "deleted"
  val Absent = "absent"
  val Unknown = "unknown"

  val unknown: VectorCoverage = new VectorCoverage:
    def status(subjectId: String, window: String): String = Unknown
    def describe: ujson.Value =
      Js.obj(Vector("basis" -> Js.s("no vector export given")))

  /** No vector export, by the explicit `--no-vector-export yes` flag: nothing
    * is checked and every status is `unknown`. The manifest records the mode.
    */
  val noneByFlag: VectorCoverage = new VectorCoverage:
    def status(subjectId: String, window: String): String = Unknown
    def describe: ujson.Value =
      Js.obj(
        Vector(
          "mode" -> Js.s("none-by-flag"),
          "basis" -> Js.s(
            "no vector export, by the flag --no-vector-export yes; nothing checked"
          )
        )
      )

  /** Fixed statuses, for tests: key (subject, window) -> status. */
  def fixed(statuses: Map[(String, String), String]): VectorCoverage =
    new VectorCoverage:
      def status(subjectId: String, window: String): String =
        statuses.getOrElse((subjectId, window), Absent)
      def describe: ujson.Value =
        Js.obj(Vector("basis" -> Js.s("fixed statuses (test)")))

  /** Non-blank lines of a file, counted as `ExportFiles.scan` counts rows. */
  private def countRows(file: Path): Long =
    val lines = Files.lines(file)
    try lines.filter(l => l.trim.nonEmpty).count()
    finally lines.close()

  /** Rebuilds the deleted set by the chunk set's own selection rule: rank every
    * vector-export summary by sha256 of `salt|summaryId` (ties by summaryId)
    * and take the `count` smallest. The rebuild is checked against the chunk
    * set manifest three ways, and `describe` reports each:
    *   - the vector export files: sha256, byte size and row count of every file
    *     named in `source.files`, and the total row count `source.rows` (a
    *     rebuild over a different export would delete a different set);
    *   - the deleted rows by summary type (`composition.by_summary_type`);
    *   - the deleted rows by window (`composition.by_window`, a summary with no
    *     window counted under `not-windowed`).
    */
  def fromExports(
      vectorExportDir: Path,
      chunksetManifest: Path
  ): VectorCoverage =
    val manifest = ExportFiles.readJson(chunksetManifest)
    val selection = manifest("selection")
    val salt = selection("salt").str
    val count = selection("count").num.toInt
    val idField = selection("id_field").str
    require(idField == "summaryId", s"unsupported chunk set id field $idField")
    val rows = ExportFiles.collect(vectorExportDir, "semantic-summaries") {
      row =>
        J.str(row.json, "summaryId").map { id =>
          (
            id,
            J.str(row.json, "summaryType").getOrElse(""),
            J.str(row.json, "subjectId").getOrElse(""),
            J.str(row.json, "window").getOrElse("not-windowed")
          )
        }
    }
    val ranked = rows
      .map(r => (Digests.sha256(s"$salt|${r._1}"), r._1))
      .sorted
    val deleted: Set[String] = ranked.take(count).map(_._2).toSet
    val deletedRows = rows.filter(r => deleted.contains(r._1))
    val byTypeActual: Map[String, Long] =
      deletedRows.groupBy(_._2).map((k, v) => k -> v.size.toLong)
    val byTypeExpected: Map[String, Long] =
      manifest("composition")("by_summary_type").obj
        .map((k, v) => k -> v.num.toLong)
        .toMap
    val byWindowActual: Map[String, Long] =
      deletedRows.groupBy(_._4).map((k, v) => k -> v.size.toLong)
    val byWindowExpected: Map[String, Long] =
      manifest("composition")("by_window").obj
        .map((k, v) => k -> v.num.toLong)
        .toMap
    val source = manifest("source")
    val root = vectorExportDir.toAbsolutePath.normalize()
    val fileChecks: Vector[ujson.Value] = source("files").arr.toVector.map {
      f =>
        val rel = f("path").str
        val expectedSha = f("sha256").str
        val file = root.resolve(rel).normalize()
        val found = file.startsWith(root) && Files.isRegularFile(file)
        val actualSha = if found then Some(Digests.sha256File(file)) else None
        val actualBytes: Option[Long] =
          if found then Some(Files.size(file)) else None
        val actualRows: Option[Long] =
          if found then Some(countRows(file)) else None
        val expectedBytes = f("bytes").num.toLong
        val expectedRows = f("rows").num.toLong
        Js.obj(
          Vector(
            "path" -> Js.s(rel),
            "found" -> Js.b(found),
            "expectedSha256" -> Js.s(expectedSha),
            "actualSha256" -> Js.opt(actualSha),
            "sha256Matches" -> Js.b(actualSha.contains(expectedSha)),
            "expectedBytes" -> Js.l(expectedBytes),
            "actualBytes" -> actualBytes.fold[ujson.Value](ujson.Null)(Js.l),
            "bytesMatch" -> Js.b(actualBytes.contains(expectedBytes)),
            "expectedRows" -> Js.l(expectedRows),
            "actualRows" -> actualRows.fold[ujson.Value](ujson.Null)(Js.l),
            "rowsMatch" -> Js.b(actualRows.contains(expectedRows))
          )
        )
    }
    def allFiles(key: String): Boolean =
      fileChecks.nonEmpty && fileChecks.forall(c => c(key).bool)
    val shaMatch = allFiles("sha256Matches")
    val bytesMatch = allFiles("bytesMatch")
    val filesRowsMatch = allFiles("rowsMatch")
    val filesMatch = shaMatch && bytesMatch && filesRowsMatch
    val rowsExpected = source("rows").num.toLong
    val totals = mutable.HashMap.empty[(String, String), (Int, Int)]
    rows.foreach { (id, _, subject, window) =>
      val (all, gone) = totals.getOrElse((subject, window), (0, 0))
      totals((subject, window)) =
        (all + 1, gone + (if deleted.contains(id) then 1 else 0))
    }
    val summaryIds = rows.map(_._1).toSet
    val totalRowsMatch = filesRowsMatch && rows.size.toLong == rowsExpected
    val flags = Vector(
      "sha256" -> shaMatch,
      "bytes" -> bytesMatch,
      "rows" -> totalRowsMatch,
      "by_summary_type" -> (byTypeActual == byTypeExpected),
      "by_window" -> (byWindowActual == byWindowExpected)
    )
    new VectorCoverage:
      override def checks: Vector[(String, Boolean)] = flags
      def status(subjectId: String, window: String): String =
        totals.get((subjectId, window)) match
          case None              => Absent
          case Some((all, gone)) => if gone < all then Available else Deleted
      def describe: ujson.Value =
        Js.obj(
          Vector(
            "mode" -> Js.s("checked"),
            "basis" -> Js.s(
              "deleted set rebuilt from the chunk set selection rule over the vector export"
            ),
            "vectorExportDir" -> Js.s(vectorExportDir.toString),
            "vectorExportSummaries" -> Js.l(rows.size.toLong),
            "vectorExportRowsManifest" -> Js.l(rowsExpected),
            "vectorExportRowsMatchManifest" -> Js.b(
              rows.size.toLong == rowsExpected
            ),
            "sourceFiles" -> Js.arr(fileChecks),
            "sourceFilesMatchManifest" -> Js.b(filesMatch),
            "sourceFilesSha256Match" -> Js.b(shaMatch),
            "sourceFilesBytesMatch" -> Js.b(bytesMatch),
            "sourceFilesRowsMatch" -> Js.b(filesRowsMatch),
            "checks" -> Js.obj(flags.map((k, v) => k -> Js.b(v))),
            "deletedRebuilt" -> Js.l(deleted.size.toLong),
            "deletedByTypeRebuilt" -> Js.counts(byTypeActual),
            "deletedByTypeManifest" -> Js.counts(byTypeExpected),
            "deletedByTypeMatchesManifest" -> Js.b(
              byTypeActual == byTypeExpected
            ),
            "deletedByWindowRebuilt" -> Js.counts(byWindowActual),
            "deletedByWindowManifest" -> Js.counts(byWindowExpected),
            "deletedByWindowMatchesManifest" -> Js.b(
              byWindowActual == byWindowExpected
            ),
            "summaryIds" -> Js.l(summaryIds.size.toLong)
          )
        )

  /** Share of `graphSummaryIds` that the vector export also has: a low value
    * means labels cannot be keyed on summaryId.
    */
  def summaryIdOverlap(
      graphExportDir: Path,
      vectorExportDir: Path
  ): (Long, Long) =
    val vector = mutable.HashSet.empty[String]
    ExportFiles.scan(vectorExportDir, "semantic-summaries") { row =>
      J.str(row.json, "summaryId").foreach(id => vector += id)
    }
    var graph = 0L
    var both = 0L
    ExportFiles.scan(graphExportDir, "semantic-summaries") { row =>
      graph += 1L
      if J.str(row.json, "summaryId").exists(vector.contains) then both += 1L
    }
    (both, graph)

  /** The `subjectId` overlap of the two exports. Labels key on researcher ids
    * of the graph export, so what matters is how many of them are a subject of
    * a vector-export summary (`graphResearchersWithVectorSubject`) and how many
    * summary subjects the two exports have in common (`shared`).
    */
  def subjectIdOverlap(
      graphExportDir: Path,
      vectorExportDir: Path
  ): SubjectOverlap =
    val vector = mutable.HashSet.empty[String]
    ExportFiles.scan(vectorExportDir, "semantic-summaries") { row =>
      J.str(row.json, "subjectId").foreach(id => vector += id)
    }
    val graph = mutable.HashSet.empty[String]
    ExportFiles.scan(graphExportDir, "semantic-summaries") { row =>
      J.str(row.json, "subjectId").foreach(id => graph += id)
    }
    val researchers = mutable.HashSet.empty[String]
    ExportFiles.scan(graphExportDir, "nodes-researcher") { row =>
      J.str(row.json, "nodeId").foreach(id => researchers += id)
    }
    SubjectOverlap(
      graph.size.toLong,
      vector.size.toLong,
      graph.count(vector.contains).toLong,
      researchers.size.toLong,
      researchers.count(vector.contains).toLong
    )
