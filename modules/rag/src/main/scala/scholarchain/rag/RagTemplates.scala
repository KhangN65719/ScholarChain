package scholarchain.rag

import scholarchain.storage.*

/** Deterministic rendering templates for the four summary types that feed the
  * semantic indexes. Each is a pure function of the Spark row: no clock, no
  * randomness, no locale and no formatting call. The fixed wording holds no
  * digit, so every number in a rendered document is a number of the row. The
  * text restructures what the row holds (type, subject, window, node kind, the
  * Spark `summaryText`, the evidence lists) and adds no metric, ID, name or
  * claim. A missing field is stated as missing, never guessed.
  *
  * Field order, fixed:
  *
  * {{{
  * <heading>
  * Subject: <subjectId>
  * Window: <window>
  * Window years: <windowStart> to <windowEnd>
  * Node kind: <nodeKind>
  * Summary: <summaryText>
  * Metrics named by the evidence: <evidenceMetrics>
  * Evidence works: <evidenceWorkIds>
  * Evidence nodes: <evidenceNodeIds>
  * }}}
  *
  * Template versions, one per document type: `rag-template/<type>/1`. A change
  * to any wording or field order is a new version, which changes the text hash
  * and so is refused under an existing vector key (a new key or index version
  * is needed).
  */
object RagTemplates extends SummaryRenderer:
  val NotRecorded = "not recorded in the source row"
  val NoneRecorded = "none recorded in the source row"

  private final case class Template(
      summaryType: String,
      version: String,
      heading: String
  )

  private val templates = Vector(
    Template(
      "researcher-profile",
      "rag-template/researcher-profile/1",
      "Researcher profile"
    ),
    Template(
      "community",
      "rag-template/community/1",
      "Community profile"
    ),
    Template(
      "work",
      "rag-template/work/1",
      "Representative paper"
    ),
    Template(
      "collaboration-path",
      "rag-template/collaboration-path/1",
      "Collaboration path"
    )
  )

  private val byType = templates.map(t => t.summaryType -> t).toMap

  /** Summary type to template version. */
  val versions: Map[String, String] =
    templates.map(t => t.summaryType -> t.version).toMap

  val summaryTypes: Vector[String] = templates.map(_.summaryType)

  private def list(row: Row, column: String): String =
    val values = Rows.strList(row, column)
    if values.isEmpty then NoneRecorded else values.mkString(", ")

  def render(row: Row): RenderedDocument =
    val kind = Rows.str(row, "summaryType")
    val template = byType.getOrElse(
      kind,
      Fail(
        ErrorCode.UnmappedSummary,
        s"No rag template for summary type '$kind'; known types: " +
          summaryTypes.mkString(", ")
      )
    )
    val subject = Rows.str(row, "subjectId")
    val summary = Rows.str(row, "summaryText")
    Fail.unless(
      summary.trim.nonEmpty,
      ErrorCode.MalformedRow,
      s"Summary $subject has a blank summaryText"
    )
    val window = Rows.optStr(row, "window").getOrElse(NotRecorded)
    val years =
      (Rows.optLong(row, "windowStart"), Rows.optLong(row, "windowEnd")) match
        case (Some(start), Some(end)) => s"$start to $end"
        case _                        => NotRecorded
    val nodeKind = Rows.optStr(row, "nodeKind").getOrElse(NotRecorded)
    val text = Vector(
      template.heading,
      s"Subject: $subject",
      s"Window: $window",
      s"Window years: $years",
      s"Node kind: $nodeKind",
      s"Summary: $summary",
      s"Metrics named by the evidence: ${list(row, "evidenceMetrics")}",
      s"Evidence works: ${list(row, "evidenceWorkIds")}",
      s"Evidence nodes: ${list(row, "evidenceNodeIds")}"
    ).mkString("\n")
    RenderedDocument(text, template.version)
