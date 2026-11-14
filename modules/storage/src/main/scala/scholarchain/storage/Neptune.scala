package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scholarchain.core.{
  CoauthorEdge,
  ContractSchema,
  EvidenceType,
  GraphEdgeType,
  SourceType,
  StableIds,
  YearWindow
}

/** A typed property value for the Neptune openCypher bulk-load CSV. */
enum Prop:
  case Str(value: String)
  case I32(value: Int)
  case I64(value: Long)
  case Dbl(value: Double)
  case Flag(value: Boolean)
  case StrList(values: Vector[String])

  def typeName: String = this match
    case Str(_)  => "String"
    case I32(_)  => "Int"
    case I64(_)  => "Long"
    case Dbl(_)  => "Double"
    case Flag(_) => "Bool"
    // The openCypher bulk-load page documents no array type, so a list is
    // written as one String joined by `;` (see NeptuneExporter.ListColumns).
    case StrList(_) => "String"

  def cell: String = this match
    case Str(v)     => v
    case I32(v)     => v.toString
    case I64(v)     => v.toString
    case Dbl(v)     => v.toString
    case Flag(v)    => v.toString
    case StrList(v) =>
      Fail.unless(
        !v.exists(_.contains(NeptuneExporter.ListSeparator)),
        ErrorCode.MalformedRow,
        s"A list element contains the separator ${NeptuneExporter.ListSeparator}"
      )
      v.mkString(NeptuneExporter.ListSeparator)

final case class GraphVertex(
    id: String,
    label: String,
    props: Map[String, Prop]
)

final case class GraphEdgeRow(
    id: String,
    from: String,
    to: String,
    label: String,
    props: Map[String, Prop]
)

final case class GraphProjection(
    vertices: Vector[GraphVertex],
    edges: Vector[GraphEdgeRow],
    normalizedValues: Int
)

/** Which relationship types a projection loads.
  *
  *   - `Base`: AUTHORED, COAUTHORED_WITH and PUBLISHED_IN, the three types the
  *     first Phase 7 round loaded. It stays selectable explicitly.
  *   - `Extended` (the default): Base plus RESOLVES_TO (resolved mention to
  *     canonical researcher, from `edges-authored`) and DERIVED_FROM (work to
  *     the `DatasetSnapshot` named by its provenance). It adds vertices and
  *     edges.
  *
  * AFFILIATED_WITH, HAS_TOPIC and SUPPORTED_BY are in neither scope; the reason
  * for each is in modules/storage/DESIGN.md.
  */
enum GraphScope:
  case Base, Extended

object NeptuneLabels:
  val Researcher = "Researcher"
  val AuthorMention = "AuthorMention"
  val Work = "Work"
  val Venue = "Venue"
  val Institution = "Institution"
  val Topic = "Topic"
  val DatasetSnapshot = "DatasetSnapshot"
  val Authored = "AUTHORED"
  val CoauthoredWith = "COAUTHORED_WITH"
  val PublishedIn = "PUBLISHED_IN"
  val ResolvesTo = "RESOLVES_TO"
  val DerivedFrom = "DERIVED_FROM"
  val LegacyCoauthorPrefix = "coauthor:v1:"
  val MentionPrefix = "mention:"
  val EdgePolicyVersion = "scholarchain.storage.edge-policy/1"
  val IdDerivation = "core:StableIds.graphEdge"

/** Projects the Spark output tables onto Neptune vertices and edges.
  *
  * Vertex and edge IDs are the Spark IDs, which are core IDs: researcher,
  * mention, work, venue, topic and institution node IDs, and the core coauthor
  * edge ID. AUTHORED and PUBLISHED_IN rows carry no Spark edge ID, so their IDs
  * are core `StableIds.graphEdge` IDs (`edge:v1:`) over the endpoints and a
  * core evidence ID minted from the row keys; the derivation is recorded in the
  * `idDerivation` property.
  *
  * Every coauthor edge is rebuilt as a core `CoauthorEdge`, which re-derives
  * its ID. A legacy `coauthor:v1:` ID on a mention endpoint is rejected with
  * the reason, and every edge is traced to supporting works through the
  * `edge-support` table.
  */
final class SparkGraphProjector(
    tables: TableSource,
    bounds: LoadBounds,
    scope: GraphScope = GraphScope.Extended
):
  import NeptuneLabels.*

  private var normalized = 0
  private val authorshipChecks = mutable.ArrayBuffer.empty[() => Unit]

  private def clean(value: String, what: String): String =
    Text.requireNoNul(value, what)
    Fail.unless(
      Text.utf8Size(value).toLong <= Limits.Neptune.MaxPropertyBytes,
      ErrorCode.BoundExceeded,
      s"$what exceeds the Neptune property limit"
    )
    if value.exists(c => c == '\n' || c == '\r' || c == '\t') then
      normalized += 1
      value.map(c => if c == '\n' || c == '\r' || c == '\t' then ' ' else c)
    else value

  /** IDs are never rewritten: a tab, newline or NUL in an ID is refused. */
  private def cleanId(value: String, what: String): String =
    Text.requireNoNul(value, what)
    Fail.unless(
      !value.exists(c => c == '\n' || c == '\r' || c == '\t'),
      ErrorCode.MalformedRow,
      s"$what contains a tab or line break; IDs are not normalized"
    )
    value

  private def rowsOf(table: String): Vector[Row] =
    val rows = tables.rows(table)
    bounds.checkRows(rows.size.toLong, table)
    rows

  private def put(
      props: mutable.LinkedHashMap[String, Prop],
      name: String,
      value: Option[Prop]
  ): Unit =
    value.foreach(prop => props.update(name, prop))

  private def text(row: Row, column: String): Option[Prop] =
    Rows.optStr(row, column).map(v => Prop.Str(clean(v, column)))

  private def provenanceProps(
      props: mutable.LinkedHashMap[String, Prop],
      row: Row
  ): Unit =
    val provenance = Rows.provenance(row)
    props.update("runId", Prop.Str(provenance.runId))
    props.update(
      "snapshotId",
      Prop.Str(clean(provenance.snapshotId, "snapshot"))
    )
    props.update(
      "sourceRecordId",
      Prop.Str(clean(provenance.sourceRecordId, "sourceRecordId"))
    )
    props.update("transformVersion", Prop.Str(provenance.transformVersion))

  private def vertexRows(
      table: String,
      idColumn: String,
      label: Row => String,
      extra: (Row, mutable.LinkedHashMap[String, Prop]) => Unit
  ): Vector[GraphVertex] =
    rowsOf(table).map { row =>
      val id = cleanId(Rows.str(row, idColumn), idColumn)
      val props = mutable.LinkedHashMap.empty[String, Prop]
      extra(row, props)
      provenanceProps(props, row)
      GraphVertex(id, label(row), props.toMap)
    }

  private def researcherLabel(row: Row): String =
    val id = Rows.str(row, "nodeId")
    Fail.unless(
      StableIds.isCoauthorNode(id),
      ErrorCode.MalformedRow,
      s"Researcher node ID is not a core ID: $id"
    )
    if id.startsWith(MentionPrefix) then AuthorMention else Researcher

  private def researcherVertices: Vector[GraphVertex] =
    vertexRows(
      "nodes-researcher",
      "nodeId",
      researcherLabel,
      (row, props) =>
        put(props, "displayName", text(row, "displayName"))
        put(props, "nodeKind", text(row, "nodeKind"))
        put(props, "orcid", text(row, "orcid"))
        put(props, "linkStatus", text(row, "linkStatus"))
        put(props, "idRule", text(row, "idRule"))
        put(
          props,
          "memberCount",
          Rows.optLong(row, "memberCount").map(Prop.I64(_))
        )
        put(
          props,
          "mentionCount",
          Rows.optLong(row, "mentionCount").map(Prop.I64(_))
        )
    )

  private def workVertices: Vector[GraphVertex] =
    vertexRows(
      "nodes-work",
      "canonicalWorkId",
      _ => Work,
      (row, props) =>
        put(props, "title", text(row, "title"))
        put(
          props,
          "year",
          Rows.optLong(row, "year").map(v => Prop.I32(v.toInt))
        )
        put(props, "venueId", text(row, "venueId"))
        put(props, "doi", text(row, "doi"))
        put(props, "source", text(row, "source"))
        put(
          props,
          "memberCount",
          Rows.optLong(row, "memberCount").map(Prop.I64(_))
        )
    )

  private def simpleVertices(
      table: String,
      idColumn: String,
      label: String,
      columns: Vector[String]
  ): Vector[GraphVertex] =
    vertexRows(
      table,
      idColumn,
      _ => label,
      (row, props) => columns.foreach(c => put(props, c, text(row, c)))
    )

  /** Core `StableIds.graphEdge` ID. Spark emits no ID for these rows, so the
    * source evidence is a core evidence ID minted from the row's own keys
    * (record and locator), never from provenance, so a rerun keeps the ID.
    */
  private def coreEdgeId(
      edgeType: GraphEdgeType,
      from: String,
      to: String,
      recordId: String,
      locator: String
  ): String =
    val evidence = StableIds.evidence(
      SourceType.Derived,
      EdgePolicyVersion,
      recordId,
      EvidenceType.Publication,
      locator
    )
    StableIds.graphEdge(edgeType, from, to, EdgePolicyVersion, Vector(evidence))

  private def authoredEdges: Vector[GraphEdgeRow] =
    rowsOf("edges-authored").map { row =>
      val from = cleanId(Rows.str(row, "researcherNodeId"), "researcherNodeId")
      val to = cleanId(Rows.str(row, "canonicalWorkId"), "canonicalWorkId")
      val mention = cleanId(Rows.str(row, "mentionId"), "mentionId")
      val props = mutable.LinkedHashMap.empty[String, Prop]
      props.update("idDerivation", Prop.Str(IdDerivation))
      props.update("mentionId", Prop.Str(mention))
      put(
        props,
        "authorPosition",
        Rows.optLong(row, "authorPosition").map(v => Prop.I32(v.toInt))
      )
      put(props, "year", Rows.optLong(row, "year").map(v => Prop.I32(v.toInt)))
      put(
        props,
        "countedInMetrics",
        Rows.optBool(row, "countedInMetrics").map(Prop.Flag(_))
      )
      put(props, "nodeKind", text(row, "nodeKind"))
      provenanceProps(props, row)
      val id = coreEdgeId(GraphEdgeType.Authored, from, to, mention, to)
      GraphEdgeRow(id, from, to, Authored, props.toMap)
    }

  private def publishedInEdges: Vector[GraphEdgeRow] =
    rowsOf("edges-published-in").map { row =>
      val from = cleanId(Rows.str(row, "fromId"), "fromId")
      val to = cleanId(Rows.str(row, "toId"), "toId")
      val props = mutable.LinkedHashMap.empty[String, Prop]
      props.update("idDerivation", Prop.Str(IdDerivation))
      provenanceProps(props, row)
      val id = coreEdgeId(GraphEdgeType.PublishedIn, from, to, from, to)
      GraphEdgeRow(id, from, to, PublishedIn, props.toMap)
    }

  /** A mention that an accepted link resolved: `edges-authored` rows whose
    * mention differs from the researcher node. Spark makes only unresolved
    * mentions nodes, so these mentions become `AuthorMention` vertices here,
    * built from the row's own columns, and each gets one RESOLVES_TO edge to
    * its canonical researcher. An unresolved mention is its own node and
    * resolves to nothing, so it gets no edge.
    */
  private def resolution(
      rows: Vector[Row]
  ): (Vector[GraphVertex], Vector[GraphEdgeRow]) =
    val resolved = rows.filter { row =>
      Rows.str(row, "mentionId") != Rows.str(row, "researcherNodeId")
    }
    val pairs = resolved.map { row =>
      val mention = cleanId(Rows.str(row, "mentionId"), "mentionId")
      val researcher =
        cleanId(Rows.str(row, "researcherNodeId"), "researcherNodeId")
      Fail.unless(
        mention.startsWith(MentionPrefix) && StableIds.isCoauthorNode(mention),
        ErrorCode.MalformedRow,
        s"mentionId is not a core mention ID: $mention"
      )
      Fail.unless(
        !researcher.startsWith(MentionPrefix),
        ErrorCode.MalformedRow,
        s"Mention $mention differs from its researcher node $researcher, " +
          "which is itself a mention"
      )
      val vertexProps = mutable.LinkedHashMap.empty[String, Prop]
      vertexProps.update("resolution", Prop.Str("resolved"))
      vertexProps.update(
        "canonicalWorkId",
        Prop.Str(cleanId(Rows.str(row, "canonicalWorkId"), "canonicalWorkId"))
      )
      put(
        vertexProps,
        "authorPosition",
        Rows.optLong(row, "authorPosition").map(v => Prop.I32(v.toInt))
      )
      provenanceProps(vertexProps, row)
      val edgeProps = mutable.LinkedHashMap.empty[String, Prop]
      edgeProps.update("idDerivation", Prop.Str(IdDerivation))
      put(
        edgeProps,
        "countedInMetrics",
        Rows.optBool(row, "countedInMetrics").map(Prop.Flag(_))
      )
      provenanceProps(edgeProps, row)
      (
        GraphVertex(mention, AuthorMention, vertexProps.toMap),
        GraphEdgeRow(
          coreEdgeId(
            GraphEdgeType.ResolvesTo,
            mention,
            researcher,
            mention,
            researcher
          ),
          mention,
          researcher,
          ResolvesTo,
          edgeProps.toMap
        )
      )
    }
    // A mention resolves to at most one researcher. Two rows that send one
    // mention to different researchers are a conflicting resolution: refused,
    // never merged and never resolved by picking one.
    pairs.map(_._2).groupBy(_.from).foreach { case (mention, edges) =>
      val targets = edges.map(_.to).distinct.sorted
      Fail.unless(
        targets.size == 1,
        ErrorCode.DuplicateId,
        s"Conflicting resolution: mention $mention resolves to " +
          s"${targets.size} researchers (${targets.mkString(", ")})"
      )
    }
    (pairs.map(_._1), pairs.map(_._2))

  /** The `DatasetSnapshot` context node of every work's provenance, and a
    * DERIVED_FROM edge from the work to it. The snapshot ID is the provenance
    * `snapshotId`, a column every Spark row carries. The vertex holds only what
    * is common to all rows of the snapshot (its ID, run and transform), so two
    * works that disagree on the run of one snapshot are refused as a duplicate
    * vertex with differing content.
    */
  private def derivation(
      works: Vector[GraphVertex]
  ): (Vector[GraphVertex], Vector[GraphEdgeRow]) =
    def prop(work: GraphVertex, name: String): String = work.props(name) match
      case Prop.Str(v) => v
      case other       =>
        Fail(ErrorCode.MalformedRow, s"Work ${work.id} $name is $other")
    val pairs = works.map { work =>
      val snapshot = cleanId(prop(work, "snapshotId"), "snapshotId")
      val vertexProps = Map(
        "snapshotId" -> Prop.Str(snapshot),
        "sourceRecordId" -> Prop.Str(snapshot),
        "runId" -> work.props("runId"),
        "transformVersion" -> work.props("transformVersion")
      )
      val edgeProps = Map(
        "idDerivation" -> Prop.Str(IdDerivation),
        "snapshotId" -> Prop.Str(snapshot),
        "sourceRecordId" -> work.props("sourceRecordId"),
        "runId" -> work.props("runId"),
        "transformVersion" -> work.props("transformVersion")
      )
      (
        GraphVertex(snapshot, DatasetSnapshot, vertexProps),
        GraphEdgeRow(
          coreEdgeId(
            GraphEdgeType.DerivedFrom,
            work.id,
            snapshot,
            work.id,
            snapshot
          ),
          work.id,
          snapshot,
          DerivedFrom,
          edgeProps
        )
      )
    }
    (pairs.map(_._1), pairs.map(_._2))

  private def supportByEdge(
      coauthorIds: Set[String]
  ): Map[String, Vector[Row]] =
    val support = rowsOf("edge-support")
    support.foreach { row =>
      val id = Rows.str(row, "edgeId")
      rejectLegacy(
        id,
        Rows.str(row, "researcherA"),
        Rows.str(row, "researcherB")
      )
      Fail.unless(
        coauthorIds.contains(id),
        ErrorCode.MissingEvidence,
        s"edge-support references unknown edge $id"
      )
    }
    support.groupBy(row => Rows.str(row, "edgeId"))

  private def rejectLegacy(edgeId: String, a: String, b: String): Unit =
    val mentionEnd = a.startsWith(MentionPrefix) || b.startsWith(MentionPrefix)
    Fail.unless(
      !(mentionEnd && edgeId.startsWith(LegacyCoauthorPrefix)),
      ErrorCode.LegacyEdgeId,
      s"Legacy Spark-local ID $edgeId on a mention endpoint; expected " +
        "mention-coauthor:v1:. The run predates the core ID change; " +
        "rerun Spark before loading"
    )

  /** The row as a core `CoauthorEdge`, which re-derives and checks its ID. A
    * legacy ID is reported as such before the generic ID error.
    */
  private def coreEdge(row: Row): CoauthorEdge =
    val id = Rows.str(row, "edgeId")
    val a = Rows.str(row, "researcherA")
    val b = Rows.str(row, "researcherB")
    rejectLegacy(id, a, b)
    try
      CoauthorEdge(
        ContractSchema.currentVersion,
        id,
        a,
        b,
        YearWindow(Rows.int(row, "windowStart"), Rows.int(row, "windowEnd")),
        Rows.str(row, "policyVersion"),
        Rows.long(row, "jointPublicationCount"),
        Rows.int(row, "firstCollaborationYear"),
        Rows.int(row, "lastCollaborationYear"),
        Rows.strList(row, "representativeWorkIds"),
        Rows.provenance(row)
      )
    catch
      case error: IllegalArgumentException =>
        Fail(ErrorCode.InvalidEdgeId, s"Edge $id: ${error.getMessage}")

  /** Spark's window label: `all` for years 1 to 9999, else `<start>-<end>`. */
  private def windowLabel(window: YearWindow): String =
    if window.startYear == 1 && window.endYear == 9999 then "all"
    else s"${window.startYear}-${window.endYear}"

  private def coauthorEdges(
      workIds: Set[String]
  ): Vector[GraphEdgeRow] =
    val rows = rowsOf("edges-coauthored")
    val cores = rows.map(row => (row, coreEdge(row)))
    val ids = rows.map(row => Rows.str(row, "edgeId")).toSet
    val support = supportByEdge(ids)
    val authoredBy = rowsOf("edges-authored")
      .filter(r => Rows.optBool(r, "countedInMetrics").forall(identity))
      .groupMap(r => Rows.str(r, "canonicalWorkId"))(r =>
        Rows.str(r, "researcherNodeId")
      )
      .view
      .mapValues(_.toSet)
      .toMap
    cores.map { case (row, core) =>
      val id = core.edgeId
      val a = core.researcherA
      val b = core.researcherB
      val window = core.window
      val representative = core.representativeWorkIds
      val count = core.jointPublicationCount
      val first = core.firstCollaborationYear
      val last = core.lastCollaborationYear
      val rowsForEdge = support.getOrElse(id, Vector.empty)
      val supportWorks = rowsForEdge.map(r => Rows.str(r, "canonicalWorkId"))
      val distinctWorks = supportWorks.toSet
      Fail.unless(
        rowsForEdge.nonEmpty,
        ErrorCode.MissingEvidence,
        s"Coauthor edge $id has no edge-support rows"
      )
      Fail.unless(
        distinctWorks.size.toLong == core.jointPublicationCount,
        ErrorCode.MissingEvidence,
        s"Edge $id counts ${core.jointPublicationCount} joint works but " +
          s"edge-support lists ${distinctWorks.size}"
      )
      Fail.unless(
        core.representativeWorkIds.forall(distinctWorks.contains),
        ErrorCode.MissingEvidence,
        s"Edge $id representative works are not all in edge-support"
      )
      Fail.unless(
        distinctWorks.forall(workIds.contains),
        ErrorCode.MissingEndpoint,
        s"Edge $id is supported by a work that is not a Work vertex"
      )
      val label = Rows.str(row, "window")
      Fail.unless(
        label == windowLabel(window),
        ErrorCode.InvalidEdgeId,
        s"Edge $id window text '$label' does not equal its years $window"
      )
      rowsForEdge.foreach { r =>
        Fail.unless(
          Rows.str(r, "researcherA") == a && Rows.str(r, "researcherB") == b,
          ErrorCode.MissingEvidence,
          s"edge-support row of $id names different endpoints than the edge"
        )
        Fail.unless(
          Rows.optStr(r, "window").forall(_ == label),
          ErrorCode.MissingEvidence,
          s"edge-support row of $id is for a different window than the edge"
        )
      }
      // Both endpoints must have authored every supporting work. A mention
      // endpoint is checked too: Spark keeps the representative record's
      // unresolved mentions in AUTHORED. Only counted AUTHORED rows count
      // (countedInMetrics = false marks a duplicate record, which cannot
      // support a pair).
      // Run after the vertex endpoint checks, so a missing vertex is reported
      // as MissingEndpoint first.
      authorshipChecks += { () =>
        distinctWorks.foreach { work =>
          val authors = authoredBy.getOrElse(work, Set.empty[String])
          Fail.unless(
            authors.contains(a) && authors.contains(b),
            ErrorCode.MissingEvidence,
            s"Edge $id cites work $work, which not both endpoints authored"
          )
        }
      }
      val years = rowsForEdge.map(r => Rows.int(r, "year"))
      Fail.unless(
        years.min == first && years.max == last,
        ErrorCode.MissingEvidence,
        s"Edge $id years do not match the years of its supporting works"
      )
      val props = mutable.LinkedHashMap.empty[String, Prop]
      props.update("jointPublicationCount", Prop.I64(count))
      props.update("firstCollaborationYear", Prop.I32(first))
      props.update("lastCollaborationYear", Prop.I32(last))
      props.update("window", Prop.Str(Rows.str(row, "window")))
      props.update("windowStart", Prop.I32(window.startYear))
      props.update("windowEnd", Prop.I32(window.endYear))
      props.update("policyVersion", Prop.Str(core.policyVersion))
      props.update("evidencePointer", Prop.Str(s"edge-support:edgeId=$id"))
      props.update("representativeWorkIds", Prop.StrList(representative))
      put(
        props,
        "endpointAUnresolved",
        Rows.optBool(row, "endpointAUnresolved").map(Prop.Flag(_))
      )
      put(
        props,
        "endpointBUnresolved",
        Rows.optBool(row, "endpointBUnresolved").map(Prop.Flag(_))
      )
      provenanceProps(props, row)
      GraphEdgeRow(id, a, b, CoauthoredWith, props.toMap)
    }

  private def dedupe[A](
      items: Vector[A],
      key: A => String,
      what: String
  ): Vector[A] =
    val seen = mutable.LinkedHashMap.empty[String, A]
    items.foreach { item =>
      val id = key(item)
      seen.get(id) match
        case Some(existing) if existing == item => ()
        case Some(_)                            =>
          Fail(
            ErrorCode.DuplicateId,
            s"$what $id appears with differing content"
          )
        case None => seen.update(id, item)
    }
    seen.values.toVector

  def project(): GraphProjection =
    normalized = 0
    authorshipChecks.clear()
    val researchers = researcherVertices
    val works = workVertices
    val venues = simpleVertices("nodes-venue", "venueId", Venue, Vector("name"))
    val topics = simpleVertices(
      "nodes-topic",
      "topicId",
      Topic,
      Vector("name", "parentTopicId")
    )
    val institutions = simpleVertices(
      "nodes-institution",
      "institutionId",
      Institution,
      Vector("name", "countryCode")
    )
    val (mentionVertices, resolvesEdges) =
      if scope == GraphScope.Extended then resolution(rowsOf("edges-authored"))
      else (Vector.empty[GraphVertex], Vector.empty[GraphEdgeRow])
    val (snapshotVertices, derivedEdges) =
      if scope == GraphScope.Extended then derivation(works)
      else (Vector.empty[GraphVertex], Vector.empty[GraphEdgeRow])
    val vertices = dedupe(
      researchers ++ works ++ venues ++ topics ++ institutions ++
        mentionVertices ++ snapshotVertices,
      _.id,
      "Vertex"
    )
    val researcherIds = researchers.map(_.id).toSet
    val workIds = works.map(_.id).toSet
    val venueIds = venues.map(_.id).toSet
    val authored = authoredEdges
    val published = publishedInEdges
    val coauthored = coauthorEdges(workIds)
    authored.foreach { e =>
      Fail.unless(
        researcherIds.contains(e.from) && workIds.contains(e.to),
        ErrorCode.MissingEndpoint,
        s"AUTHORED edge ${e.id} has an endpoint that is not a vertex"
      )
    }
    published.foreach { e =>
      Fail.unless(
        workIds.contains(e.from) && venueIds.contains(e.to),
        ErrorCode.MissingEndpoint,
        s"PUBLISHED_IN edge ${e.id} has an endpoint that is not a vertex"
      )
    }
    coauthored.foreach { e =>
      Fail.unless(
        researcherIds.contains(e.from) && researcherIds.contains(e.to),
        ErrorCode.MissingEndpoint,
        s"COAUTHORED_WITH edge ${e.id} has an endpoint that is not a vertex"
      )
    }
    resolvesEdges.foreach { e =>
      Fail.unless(
        researcherIds.contains(e.to),
        ErrorCode.MissingEndpoint,
        s"RESOLVES_TO edge ${e.id} targets a researcher that is not a vertex"
      )
    }
    authorshipChecks.foreach(check => check())
    val edges = dedupe(
      authored ++ published ++ coauthored ++ resolvesEdges ++ derivedEdges,
      _.id,
      "Edge"
    )
    GraphProjection(
      vertices.sortBy(_.id),
      edges.sortBy(_.id),
      normalized
    )

object Csv:
  def escape(value: String): String =
    if value.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then
      "\"" + value.replace("\"", "\"\"") + "\""
    else value

  def line(cells: Seq[String]): String = cells.map(escape).mkString(",")

  /** RFC 4180 parser for the files this module writes. */
  def parse(content: String): Vector[Vector[String]] =
    val rows = Vector.newBuilder[Vector[String]]
    val row = Vector.newBuilder[String]
    val cell = new StringBuilder
    var quoted = false
    var index = 0
    var pending = false
    while index < content.length do
      val c = content.charAt(index)
      if quoted then
        if c == '"' && index + 1 < content.length && content.charAt(
            index + 1
          ) == '"'
        then
          cell.append('"')
          index += 1
        else if c == '"' then quoted = false
        else cell.append(c)
      else if c == '"' then
        quoted = true
        pending = true
      else if c == ',' then
        row += cell.toString
        cell.clear()
        pending = true
      else if c == '\n' then
        row += cell.toString
        cell.clear()
        rows += row.result()
        row.clear()
        pending = false
      else
        cell.append(c)
        pending = true
      index += 1
    if pending then
      row += cell.toString
      rows += row.result()
    rows.result()

final case class NeptuneFile(
    key: String,
    kind: String,
    label: String,
    rows: Int,
    sha256: String
)

final case class NeptuneExportReport(
    runId: String,
    files: Vector[NeptuneFile],
    vertexCount: Int,
    edgeCount: Int,
    normalizedValues: Int,
    manifestKey: String,
    created: Int,
    unchanged: Int
)

/** Writes the openCypher bulk-load CSV files and their manifest to the object
  * store. Files are sorted by ID, so an export is byte-identical when repeated.
  */
object NeptuneExporter:
  val Version = "v1"

  /** The load files live under `graph/neptune/v=<version>/run=<run>/` and hold
    * CSV only. The manifest is a different dataset, so it is never under a
    * prefix that a prefix load of the CSV files would read.
    */
  val DataDataset = "neptune"
  val ManifestDataset = "neptune-manifest"

  /** A list property is flattened to one `String` column joined by this
    * separator; only the columns named here may hold a list, and the CSV reader
    * turns exactly these columns back into lists.
    */
  val ListSeparator = ";"
  val ListColumns: Set[String] = Set("representativeWorkIds")

  private def columns(props: Seq[Map[String, Prop]]): Vector[(String, String)] =
    val names = props.flatMap(_.keys).distinct.sorted.toVector
    names.map { name =>
      val lists = props.flatMap(_.get(name)).exists {
        case Prop.StrList(_) => true
        case _               => false
      }
      Fail.unless(
        !lists || ListColumns.contains(name),
        ErrorCode.MalformedRow,
        s"Property $name holds a list but is not a flattened list column"
      )
      val kinds = props.flatMap(_.get(name)).map(_.typeName).distinct
      Fail.unless(
        kinds.size == 1,
        ErrorCode.MalformedRow,
        s"Property $name has mixed types: ${kinds.mkString(",")}"
      )
      (name, kinds.head)
    }

  private def render(
      header: Vector[String],
      props: Vector[(String, String)],
      lines: Vector[Vector[String]]
  ): String =
    val head = header ++ props.map { case (name, kind) => s"$name:$kind" }
    (Csv.line(head) +: lines.map(Csv.line)).mkString("", "\n", "\n")

  def exportTo(
      projection: GraphProjection,
      runId: String,
      store: ObjectStore
  ): NeptuneExportReport =
    val ref = s"spark-run:$runId"
    val vertexFiles = projection.vertices.groupBy(_.label).toVector.sortBy(_._1)
    val vertexOut = vertexFiles.map { case (label, group) =>
      val cols = columns(group.map(_.props))
      val lines = group.map { v =>
        Vector(v.id, v.label) ++ cols.map { case (name, _) =>
          v.props.get(name).map(_.cell).getOrElse("")
        }
      }
      val content = render(Vector(":ID", ":LABEL"), cols, lines)
      (s"vertices-$label.csv", "vertex", label, group.size, content)
    }
    val edgeFiles = projection.edges.groupBy(_.label).toVector.sortBy(_._1)
    val edgeOut = edgeFiles.map { case (label, group) =>
      val cols = columns(group.map(_.props))
      val lines = group.map { e =>
        Vector(e.id, e.from, e.to, e.label) ++ cols.map { case (name, _) =>
          e.props.get(name).map(_.cell).getOrElse("")
        }
      }
      val header = Vector(":ID", ":START_ID", ":END_ID", ":TYPE")
      (
        s"edges-$label.csv",
        "edge",
        label,
        group.size,
        render(header, cols, lines)
      )
    }
    val results = (vertexOut ++ edgeOut).map {
      case (name, kind, label, count, content) =>
        val key = ObjectKeys.versioned(
          S3Prefix.Graph,
          DataDataset,
          Version,
          runId,
          name
        )
        val put = store.putImmutable(key, content.getBytes(UTF_8), ref)
        (NeptuneFile(key, kind, label, count, put.meta.sha256), put)
    }
    val files = results.map(_._1)
    val manifest = ujson.Obj(
      "manifestType" -> "scholarchain.storage.neptune-load/1",
      "format" -> "opencypher",
      "runId" -> runId,
      "transformVersion" -> StorageProvenance.TransformVersion,
      "sourceRef" -> ref,
      "normalizedValues" -> projection.normalizedValues,
      "files" -> ujson.Arr.from(files.map { f =>
        ujson.Obj(
          "key" -> f.key,
          "kind" -> f.kind,
          "label" -> f.label,
          "rows" -> f.rows,
          "sha256" -> f.sha256
        )
      })
    )
    val manifestKey = ObjectKeys.versioned(
      S3Prefix.Graph,
      ManifestDataset,
      Version,
      runId,
      "load-manifest.json"
    )
    val manifestPut = store.putImmutable(
      manifestKey,
      (ujson.write(manifest, indent = 2) + "\n").getBytes(UTF_8),
      ref
    )
    val puts = results.map(_._2) :+ manifestPut
    NeptuneExportReport(
      runId,
      files,
      projection.vertices.size,
      projection.edges.size,
      projection.normalizedValues,
      manifestKey,
      puts.count(_.created),
      puts.count(!_.created)
    )

/** Reads the CSV files back into vertices and edges. */
object NeptuneCsvReader:
  private def parseProp(name: String, kind: String, cell: String): Prop =
    kind match
      case "String" if NeptuneExporter.ListColumns.contains(name) =>
        Prop.StrList(cell.split(NeptuneExporter.ListSeparator).toVector)
      case other => parseScalar(kind, cell)

  private def parseScalar(kind: String, cell: String): Prop = kind match
    case "String"   => Prop.Str(cell)
    case "Int"      => Prop.I32(cell.toInt)
    case "Long"     => Prop.I64(cell.toLong)
    case "Double"   => Prop.Dbl(cell.toDouble)
    case "Bool"     => Prop.Flag(cell.toBoolean)
    case "String[]" => Prop.StrList(cell.split(';').toVector)
    case other => Fail(ErrorCode.MalformedRow, s"Unknown column type $other")

  private def props(
      header: Vector[String],
      from: Int,
      cells: Vector[String]
  ): Map[String, Prop] =
    header.indices
      .drop(from)
      .flatMap { i =>
        val cell = if i < cells.size then cells(i) else ""
        if cell.isEmpty then None
        else
          val Array(name, kind) = header(i).split(":", 2)
          Some(name -> parseProp(name, kind, cell))
      }
      .toMap

  def vertices(content: String): Vector[GraphVertex] =
    val rows = Csv.parse(content)
    val header = rows.head
    rows.tail.map(cells =>
      GraphVertex(cells(0), cells(1), props(header, 2, cells))
    )

  def edges(content: String): Vector[GraphEdgeRow] =
    val rows = Csv.parse(content)
    val header = rows.head
    rows.tail.map { cells =>
      GraphEdgeRow(
        cells(0),
        cells(1),
        cells(2),
        cells(3),
        props(header, 4, cells)
      )
    }

/** What the Neptune loader reports for one load job (GetLoaderJobStatus
  * `overallStatus`), and nothing derived.
  */
final case class LoaderCounts(
    totalRecords: Long,
    totalDuplicates: Long,
    parsingErrors: Long,
    datatypeMismatchErrors: Long,
    insertErrors: Long
):
  def errors: Long = parsingErrors + datatypeMismatchErrors + insertErrors

/** One loader job: the S3 source, its load ID, the terminal status text, the
  * counts the loader reported and the first error entries it listed.
  */
final case class LoaderJobReport(
    source: String,
    loadId: String,
    status: String,
    counts: LoaderCounts,
    errorSamples: Vector[String]
)

/** `inserted`, `updated` and `unchanged` are what the in-memory graph can tell
  * exactly. The AWS loader does not report them, so for it `inserted` is the
  * records neither duplicated nor in error, `unchanged` the duplicates and
  * `updated` zero: derived, not reported. `jobs` carries what the loader did
  * report, per file; it is empty for the in-memory graph.
  */
final case class BulkLoadResult(
    inserted: Int,
    updated: Int,
    unchanged: Int,
    jobs: Vector[LoaderJobReport] = Vector.empty
)

/** Port for the Neptune bulk loader. The loader is non-ACID, so a load must be
  * idempotent by ID. `AwsNeptuneBulkLoader` is the AWS-backed adapter.
  */
trait NeptuneBulkLoader:
  def load(manifestKey: String): BulkLoadResult

/** A parameterized read-only openCypher query. The text is a fixed template;
  * every value travels as a parameter and is never concatenated into the text.
  */
final case class CypherQuery(
    name: String,
    text: String,
    params: Map[String, ujson.Value]
):
  CypherQuery.validate(this)

object CypherQuery:
  /** Upper bound of the `$limit` parameter of any runtime query. */
  val MaxLimit = 1000

  private val forbidden = Set(
    "CREATE",
    "MERGE",
    "DELETE",
    "DETACH",
    "SET",
    "REMOVE",
    "DROP",
    "LOAD",
    "FOREACH",
    "CALL"
  )
  private val paramPattern = "\\$([A-Za-z][A-Za-z0-9_]*)".r
  private val wordPattern = "[A-Za-z_]+".r

  def validate(query: CypherQuery): Unit =
    val text = query.text
    Fail.unless(
      !text.contains('\'') && !text.contains('"') && !text.contains('`'),
      ErrorCode.QueryRejected,
      "Query text must not contain literals or quoted identifiers"
    )
    Fail.unless(
      !text.contains(Text.Nul) && !text.contains(';'),
      ErrorCode.QueryRejected,
      "Query text must be a single statement"
    )
    val words = wordPattern.findAllIn(text).map(_.toUpperCase).toSet
    val bad = words.intersect(forbidden)
    Fail.unless(
      bad.isEmpty,
      ErrorCode.QueryRejected,
      s"Runtime queries are read-only; found ${bad.mkString(",")}"
    )
    val named = paramPattern.findAllMatchIn(text).map(_.group(1)).toSet
    Fail.unless(
      named == query.params.keySet,
      ErrorCode.QueryRejected,
      s"Parameters ${query.params.keySet} do not match placeholders $named"
    )
    Fail.unless(
      named.contains("limit"),
      ErrorCode.QueryRejected,
      "A runtime query must bind a $limit parameter"
    )
    val limit = query.params("limit").numOpt
    Fail.unless(
      limit.exists(v => v.isWhole && v >= 1 && v <= MaxLimit),
      ErrorCode.QueryRejected,
      s"The $$limit parameter must be a whole number from 1 to $MaxLimit"
    )

/** The runtime queries the graph model needs. */
object CypherCatalog:
  val CoauthorsOf = "coauthors-of"
  val EdgeEvidence = "edge-evidence"

  def coauthorsOf(
      researcherId: String,
      window: String,
      limit: Int
  ): CypherQuery =
    CypherQuery(
      CoauthorsOf,
      "MATCH (a)-[e:COAUTHORED_WITH]-(b) " +
        "WHERE id(a) = $researcherId AND e.window = $window " +
        "RETURN id(b) AS coauthorId, id(e) AS edgeId, " +
        "e.jointPublicationCount AS jointPublicationCount, " +
        "e.firstCollaborationYear AS firstCollaborationYear, " +
        "e.lastCollaborationYear AS lastCollaborationYear, " +
        "e.evidencePointer AS evidencePointer " +
        "ORDER BY jointPublicationCount DESC, coauthorId LIMIT $limit",
      Map(
        "researcherId" -> ujson.Str(researcherId),
        "window" -> ujson.Str(window),
        "limit" -> ujson.Num(limit)
      )
    )

  def edgeEvidence(edgeId: String, limit: Int): CypherQuery =
    CypherQuery(
      EdgeEvidence,
      "MATCH ()-[e]-() WHERE id(e) = $edgeId " +
        "RETURN id(e) AS edgeId, e.evidencePointer AS evidencePointer, " +
        "e.representativeWorkIds AS representativeWorkIds LIMIT $limit",
      Map("edgeId" -> ujson.Str(edgeId), "limit" -> ujson.Num(limit))
    )

/** Runtime port: read-only by construction, parameterized queries only. */
trait NeptuneQueryPort:
  def run(query: CypherQuery): Vector[Row]

/** In-memory graph that is both the bulk-load target and the query port. It
  * answers the catalog queries by name over their bound parameters.
  */
final class InMemoryNeptune(store: ObjectStore)
    extends NeptuneBulkLoader
    with NeptuneQueryPort:
  private val vertexById = mutable.LinkedHashMap.empty[String, GraphVertex]
  private val edgeById = mutable.LinkedHashMap.empty[String, GraphEdgeRow]

  def load(manifestKey: String): BulkLoadResult =
    val bytes = store
      .get(manifestKey)
      .getOrElse(Fail(ErrorCode.MalformedManifest, s"No $manifestKey"))
    val manifest = ujson.read(bytes)
    var inserted = 0
    var updated = 0
    var unchanged = 0
    def note[A](map: mutable.LinkedHashMap[String, A], id: String, item: A) =
      map.get(id) match
        case None =>
          inserted += 1
          map.update(id, item)
        case Some(existing) if existing == item => unchanged += 1
        case Some(_)                            =>
          updated += 1
          map.update(id, item)
    manifest("files").arr.foreach { file =>
      val key = file("key").str
      val content = new String(
        store.get(key).getOrElse(Fail(ErrorCode.MalformedManifest, key)),
        UTF_8
      )
      Fail.unless(
        Hashing.sha256(content) == file("sha256").str,
        ErrorCode.ChecksumMismatch,
        s"$key does not match the load manifest"
      )
      if file("kind").str == "vertex" then
        NeptuneCsvReader
          .vertices(content)
          .foreach(v => note(vertexById, v.id, v))
      else NeptuneCsvReader.edges(content).foreach(e => note(edgeById, e.id, e))
    }
    BulkLoadResult(inserted, updated, unchanged)

  def vertices: Vector[GraphVertex] = vertexById.values.toVector.sortBy(_.id)
  def edges: Vector[GraphEdgeRow] = edgeById.values.toVector.sortBy(_.id)

  private def propValue(prop: Prop): ujson.Value = prop match
    case Prop.Str(v)     => ujson.Str(v)
    case Prop.I32(v)     => ujson.Num(v)
    case Prop.I64(v)     => ujson.Num(v.toDouble)
    case Prop.Dbl(v)     => ujson.Num(v)
    case Prop.Flag(v)    => ujson.Bool(v)
    case Prop.StrList(v) => ujson.Arr.from(v.map(ujson.Str(_)))

  private def limitOf(query: CypherQuery): Int =
    query.params("limit").num.toInt

  def run(query: CypherQuery): Vector[Row] = query.name match
    case CypherCatalog.CoauthorsOf =>
      val id = query.params("researcherId").str
      val window = query.params("window").str
      val matches = edgeById.values.toVector.filter { e =>
        e.label == NeptuneLabels.CoauthoredWith &&
        (e.from == id || e.to == id) &&
        e.props.get("window").contains(Prop.Str(window))
      }
      val rows = matches.map { e =>
        val other = if e.from == id then e.to else e.from
        val count = e.props("jointPublicationCount") match
          case Prop.I64(v) => v
          case _           => 0L
        (other, e, count)
      }
      rows
        .sortBy { case (other, _, count) => (-count, other) }
        .take(limitOf(query))
        .map { case (other, e, _) =>
          Map(
            "coauthorId" -> ujson.Str(other),
            "edgeId" -> ujson.Str(e.id),
            "jointPublicationCount" -> propValue(
              e.props("jointPublicationCount")
            ),
            "firstCollaborationYear" -> propValue(
              e.props("firstCollaborationYear")
            ),
            "lastCollaborationYear" -> propValue(
              e.props("lastCollaborationYear")
            ),
            "evidencePointer" -> propValue(e.props("evidencePointer"))
          )
        }
    case CypherCatalog.EdgeEvidence =>
      val id = query.params("edgeId").str
      edgeById.get(id).toVector.take(limitOf(query)).map { e =>
        Map(
          "edgeId" -> ujson.Str(e.id),
          "evidencePointer" -> e.props
            .get("evidencePointer")
            .map(propValue)
            .getOrElse(ujson.Null),
          "representativeWorkIds" -> e.props
            .get("representativeWorkIds")
            .map(propValue)
            .getOrElse(ujson.Null)
        )
      }
    case other =>
      Fail(ErrorCode.QueryRejected, s"Unknown catalog query $other")
