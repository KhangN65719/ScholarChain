package scholarchain.rag

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scholarchain.core.{
  ContractSchema,
  EvidenceBundle,
  EvidenceItem,
  EvidenceType,
  Provenance,
  SourceType,
  StableIds,
  YearWindow
}
import scholarchain.storage.{
  CachedMetrics,
  Hashing,
  NonPathKind,
  PathLookup,
  WindowLabels
}

/** Bundle limits. A bundle that would exceed one is refused with
  * `EvidenceCapExceeded`; nothing is dropped silently.
  *
  * `maxBytes` is 32 KiB so that a bundle within the cap also fits the model's
  * input budget (`ModelRetryPolicy.maxInputTokens`, 12000 estimated tokens, 4
  * characters each, about 48000 characters). The model input carries each
  * item's fact (never more than the item's own bytes) plus a fixed frame, the
  * question (at most `RagRuntime.MaxQuestionChars`) and the known gaps, and the
  * rest of the budget is that frame. It was 96 KiB, which allowed a bundle that
  * could not be sent. Lowering the cap makes the refusal earlier, not weaker.
  */
final case class EvidenceCaps(
    maxItems: Int = 60,
    maxBytes: Int = 32 * 1024,
    maxItemBytes: Int = 8 * 1024
)

final class EvidenceCapExceeded(message: String)
    extends RuntimeException(message)

/** Collects evidence for one query and builds the core `EvidenceBundle`.
  *
  * Deterministic evidence (`Metric`, `GraphPath`, `Publication`,
  * `IdentityLink`) carries a JSON fact in `text` and keeps the provenance of
  * the stored record it came from. Semantic items are `TextChunk` only, hold
  * the retrieved snippet as data, and say they are descriptive. IDs come from
  * `StableIds.evidence`, so the same record always has the same ID.
  */
final class EvidenceBuilderAgent(
    queryId: String,
    queryRunId: String,
    graphRunId: String,
    nameOf: String => Option[String],
    caps: EvidenceCaps = EvidenceCaps()
):
  private val items = mutable.LinkedHashMap.empty[String, EvidenceItem]
  private var totalBytes = 0
  private val noteBuffer = mutable.ArrayBuffer.empty[String]
  private val flagBuffer = mutable.ArrayBuffer.empty[String]

  def note(text: String): Unit =
    if !noteBuffer.contains(text) then noteBuffer += text

  def notes: Vector[String] = noteBuffer.toVector
  def flags: Vector[String] = flagBuffer.toVector.distinct

  /** Names and titles are stored data, so they get the same screen as a
    * snippet. A hit is flagged and the text stays data.
    */
  private def screen(origin: String, text: String): String =
    InjectionScreen.scan(text).foreach(h => flagBuffer += s"$origin:$h")
    text

  private def name(id: String): String =
    nameOf(id).map(screen("stored-name", _)).getOrElse("")

  private def graphProvenance(recordId: String, uri: String): Provenance =
    RagRuntime.provenance(
      graphRunId,
      recordId,
      uri,
      s"graph-run:$graphRunId"
    )

  private def add(item: EvidenceItem): String =
    items.get(item.evidenceId) match
      case Some(_) => item.evidenceId
      case None    =>
        val size = upickle.default.write(item).getBytes(UTF_8).length
        if items.size + 1 > caps.maxItems then
          throw new EvidenceCapExceeded(
            s"the evidence would exceed ${caps.maxItems} items"
          )
        if size > caps.maxItemBytes then
          throw new EvidenceCapExceeded(
            s"one evidence item is $size bytes; the limit is ${caps.maxItemBytes}"
          )
        if totalBytes + size > caps.maxBytes then
          throw new EvidenceCapExceeded(
            s"the evidence would exceed ${caps.maxBytes} bytes"
          )
        totalBytes += size
        items.update(item.evidenceId, item)
        item.evidenceId

  private def deterministic(
      evidenceType: EvidenceType,
      recordId: String,
      locator: String,
      provenance: Provenance,
      fact: ujson.Value,
      graphPath: Option[Vector[String]] = None,
      workId: Option[String] = None,
      score: Double = 1.0,
      confidence: Option[Double] = None,
      uncertainty: Option[String] = None
  ): String =
    add(
      EvidenceItem(
        ContractSchema.currentVersion,
        StableIds.evidence(
          provenance.source,
          provenance.transformVersion,
          recordId,
          evidenceType,
          locator
        ),
        evidenceType,
        workId,
        provenance.sourceUri,
        None,
        graphPath,
        score,
        confidence,
        Some(Facts.write(fact)),
        uncertainty,
        provenance
      )
    )

  def identity(r: scholarchain.rag.ResolvedCandidate): String =
    val c = r.candidate
    val fact = Facts.obj(
      Seq(
        "kind" -> ujson.Str("identity"),
        "researcherId" -> ujson.Str(c.nodeId),
        "displayName" -> ujson.Str(screen("stored-name", c.displayName)),
        "nodeKind" -> ujson.Str(c.nodeKind),
        "orcid" -> Facts.optStr(c.orcid),
        "linkStatus" -> Facts.optStr(c.linkStatus),
        "isMention" -> ujson.Bool(c.isMention),
        "confidence" -> ujson.Num(r.confidence),
        "sharesOrcidWith" -> ujson.Num(c.sharesOrcidWith),
        "memberCount" -> ujson.Num(c.memberCount),
        "mentionCount" -> ujson.Num(c.mentionCount),
        "coauthorContext" -> ujson.Arr.from(
          r.coauthorContext.map(l =>
            ujson.Obj(
              "coauthorId" -> l.coauthorId,
              "coauthorName" -> name(l.coauthorId),
              "jointPublicationCount" -> l.jointPublicationCount.toDouble
            ): ujson.Value
          )
        )
      )
    )
    val caveats = Vector(
      if c.isMention then Some("an unresolved author mention") else None,
      if c.sharesOrcidWith > 0 then
        Some("the record shares its ORCID with other nodes")
      else None
    ).flatten
    deterministic(
      EvidenceType.IdentityLink,
      s"identity|${c.nodeId}",
      "identity",
      c.provenance,
      fact,
      score = r.confidence,
      confidence = Some(r.confidence),
      uncertainty =
        if caveats.isEmpty then None else Some(caveats.mkString("; "))
    )

  def metrics(cached: CachedMetrics): String =
    val m = cached.metrics
    val fact = Facts.obj(
      Seq(
        "kind" -> ujson.Str("metric"),
        "researcherId" -> ujson.Str(m.researcherId),
        "displayName" -> ujson.Str(name(m.researcherId))
      ) ++ Facts.windowFields(m.window) ++ Seq(
        "runId" -> ujson.Str(cached.runId),
        "metricVersion" -> ujson.Str(cached.sparkTransformVersion),
        "publicationCount" -> ujson.Num(m.publicationCount),
        "distinctCoauthorCount" -> ujson.Num(m.distinctCoauthorCount),
        "totalCoauthorOccurrences" -> ujson.Num(m.totalCoauthorOccurrences),
        "averageCoauthorsPerPaper" -> ujson.Num(m.averageCoauthorsPerPaper),
        "maximumCoauthorsOnOnePaper" -> ujson.Num(
          m.maximumCoauthorsOnOnePaper
        ),
        "maximumTeamWorkIds" -> Facts.strs(m.maximumTeamWorkIds),
        "weightedDegree" -> ujson.Num(m.weightedDegree),
        "componentId" -> ujson.Str(m.componentId),
        "componentSize" -> ujson.Num(m.componentSize),
        "communityId" -> Facts.optStr(m.communityId),
        "bridgeScore" -> Facts.optNum(m.bridgeScore),
        "coreness" -> Facts.optNum(m.coreness.map(_.toDouble))
      )
    )
    deterministic(
      EvidenceType.Metric,
      s"metrics|${m.researcherId}|${WindowLabels.of(m.window)}|${cached.runId}",
      "metric",
      m.provenance,
      fact
    )

  def ranking(r: Ranking): Option[String] =
    r.entries.headOption.map { first =>
      val fact = Facts.obj(
        Seq(
          "kind" -> ujson.Str("ranking"),
          "metric" -> ujson.Str(r.metric.wire)
        ) ++ Facts.windowFields(r.window) ++ Seq(
          "rankedTotal" -> ujson.Num(r.rankedTotal),
          "mentionsExcluded" -> ujson.Num(r.mentionsExcluded),
          "tieAtCutoff" -> ujson.Bool(r.tieAtCutoff),
          "entries" -> ujson.Arr.from(
            r.entries.map(e =>
              ujson.Obj(
                "rank" -> e.rank,
                "researcherId" -> e.cached.metrics.researcherId,
                "displayName" -> name(e.cached.metrics.researcherId),
                "value" -> e.value,
                "runId" -> e.cached.runId
              ): ujson.Value
            )
          )
        )
      )
      if r.tieAtCutoff then
        note(
          "The ranking cut falls inside a tie; equal values beyond it are not listed."
        )
      if r.mentionsExcluded > 0 then
        note("Unresolved author mentions are excluded from rankings.")
      deterministic(
        EvidenceType.Metric,
        s"ranking|${r.metric.wire}|${WindowLabels.of(r.window)}|${first.cached.runId}",
        "ranking",
        first.cached.metrics.provenance,
        fact
      )
    }

  def coauthors(page: CoauthorPage): Vector[String] =
    if page.mayBeTruncated then
      note(
        s"The coauthor list is limited to ${page.limit} entries and may be incomplete."
      )
    page.links.map(link => coauthorEdge(page.researcherId, link, page.window))

  def coauthorEdge(
      researcherId: String,
      link: scholarchain.storage.CoauthorLink,
      window: YearWindow
  ): String =
    val fact = Facts.obj(
      Seq(
        "kind" -> ujson.Str("coauthor"),
        "researcherId" -> ujson.Str(researcherId),
        "researcherName" -> ujson.Str(name(researcherId)),
        "coauthorId" -> ujson.Str(link.coauthorId),
        "coauthorName" -> ujson.Str(name(link.coauthorId)),
        "edgeId" -> ujson.Str(link.edgeId)
      ) ++ Facts.windowFields(window) ++ Seq(
        "jointPublicationCount" -> ujson.Num(link.jointPublicationCount),
        "firstCollaborationYear" -> ujson.Num(link.firstCollaborationYear),
        "lastCollaborationYear" -> ujson.Num(link.lastCollaborationYear),
        "evidencePointer" -> ujson.Str(link.evidencePointer)
      )
    )
    deterministic(
      EvidenceType.GraphPath,
      s"coauthor|${link.edgeId}",
      "coauthor-edge",
      graphProvenance(link.edgeId, link.evidencePointer),
      fact,
      graphPath = Some(Vector(researcherId, link.coauthorId)),
      score = link.jointPublicationCount.toDouble
    )

  private def reasonOf(kind: NonPathKind): String = kind match
    case NonPathKind.DifferentComponent =>
      "they are in different connected components"
    case NonPathKind.BeyondHopLimit =>
      "a path exists but is longer than the precomputed hop limit"
    case NonPathKind.UnreachableUnexplained =>
      "the precomputed table marks the pair unreachable within its bound without a reason"
    case NonPathKind.Trivial             => "both names are the same node"
    case NonPathKind.EndpointNotInWindow =>
      "one endpoint is not in the graph for this window"

  def path(
      lookup: PathLookup,
      source: String,
      target: String,
      window: YearWindow
  ): String =
    val label = WindowLabels.of(window)
    val head = Seq(
      "source" -> ujson.Str(source),
      "target" -> ujson.Str(target),
      "sourceName" -> ujson.Str(name(source)),
      "targetName" -> ujson.Str(name(target))
    ) ++ Facts.windowFields(window)
    def status(
        pathKind: String,
        reason: String,
        noPath: Boolean,
        extra: Facts.Fields,
        provenance: Provenance
    ): String =
      val fact = Facts.obj(
        Seq("kind" -> ujson.Str("path-status")) ++ head ++ Seq(
          "pathKind" -> ujson.Str(pathKind),
          "noPathExists" -> ujson.Bool(noPath),
          "reason" -> ujson.Str(reason)
        ) ++ extra
      )
      deterministic(
        EvidenceType.GraphPath,
        s"path-status|$source|$target|$label|$pathKind",
        "path-status",
        provenance,
        fact
      )
    def lookupProvenance: Provenance =
      graphProvenance(
        s"path|$source|$target|$label",
        "scholarchain:rag/path-lookup"
      )
    lookup match
      case PathLookup.Found(_, _, _, hops) =>
        val fact = Facts.obj(
          Seq("kind" -> ujson.Str("path")) ++ head ++ Seq(
            "hopCount" -> ujson.Num(hops.size),
            "hops" -> ujson.Arr.from(
              hops.zipWithIndex.map { (hop, i) =>
                ujson.Obj(
                  "hopIndex" -> (i + 1),
                  "from" -> hop.fromId,
                  "to" -> hop.toId,
                  "fromName" -> name(hop.fromId),
                  "toName" -> name(hop.toId),
                  "edgeId" -> hop.edgeId,
                  "jointPublicationCount" -> hop.jointPublicationCount.toDouble,
                  "supportingWorkIds" -> Facts.strs(hop.supportingWorkIds),
                  "firstCollaborationYear" -> Facts.optNum(
                    hop.firstCollaborationYear.map(_.toDouble)
                  ),
                  "lastCollaborationYear" -> Facts.optNum(
                    hop.lastCollaborationYear.map(_.toDouble)
                  )
                ): ujson.Value
              }
            )
          )
        )
        deterministic(
          EvidenceType.GraphPath,
          s"path|$source|$target|$label",
          "path",
          hops.headOption.map(_.provenance).getOrElse(lookupProvenance),
          fact,
          graphPath = Some(source +: hops.map(_.toId)),
          score = hops.size.toDouble
        )
      case PathLookup.NonPath(_, _, _, _, _, kind, provenance) =>
        status(
          kind.toString,
          reasonOf(kind),
          lookup.noPathExists,
          Seq("status" -> ujson.Str(kind.toString)),
          provenance
        )
      case PathLookup.NotPrecomputed(_, _, _) =>
        status(
          "NotPrecomputed",
          "the pair was not selected for the precomputed path table, which says nothing about whether a path exists",
          noPath = false,
          Seq.empty,
          lookupProvenance
        )
      case PathLookup.ExceedsDepth(length, max) =>
        status(
          "ExceedsDepth",
          "a path exists but it is longer than the requested depth bound",
          noPath = false,
          Seq(
            "pathLength" -> ujson.Num(length),
            "maxDepth" -> ujson.Num(max)
          ),
          lookupProvenance
        )
      case PathLookup.ExceedsResults(count, max) =>
        status(
          "ExceedsResults",
          "a path exists but it has more hops than the requested result bound",
          noPath = false,
          Seq(
            "pathLength" -> ujson.Num(count),
            "maxResults" -> ujson.Num(max)
          ),
          lookupProvenance
        )
      case PathLookup.TimedOut(limit, cost) =>
        status(
          "TimedOut",
          "the lookup exceeded its time bound, so nothing is known about the pair",
          noPath = false,
          Seq(
            "timeoutMillis" -> ujson.Num(limit.toDouble),
            "costMillis" -> ujson.Num(cost.toDouble)
          ),
          lookupProvenance
        )

  def component(analysis: CommunityAnalysis): Option[String] =
    analysis.component.map { c =>
      val fact = Facts.obj(
        Seq(
          "kind" -> ujson.Str("component"),
          "researcherId" -> ujson.Str(analysis.researcherId),
          "displayName" -> ujson.Str(name(analysis.researcherId))
        ) ++ Facts.windowFields(analysis.window) ++ Seq(
          "componentId" -> ujson.Str(c.componentId),
          "componentSize" -> ujson.Num(c.size.toDouble),
          "isLargest" -> ujson.Bool(c.isLargest),
          "runId" -> ujson.Str(c.provenance.runId),
          "metricVersion" -> ujson.Str(c.provenance.transformVersion)
        )
      )
      deterministic(
        EvidenceType.Metric,
        s"component|${analysis.researcherId}|${c.window}|${c.componentId}",
        "component",
        c.provenance,
        fact
      )
    }

  def community(analysis: CommunityAnalysis): Option[String] =
    analysis.community.map { c =>
      val fact = Facts.obj(
        Seq(
          "kind" -> ujson.Str("community"),
          "researcherId" -> ujson.Str(analysis.researcherId),
          "displayName" -> ujson.Str(name(analysis.researcherId))
        ) ++ Facts.windowFields(analysis.window) ++ Seq(
          "communityId" -> ujson.Str(c.communityId),
          "communitySize" -> ujson.Num(c.size.toDouble),
          "internalEdges" -> ujson.Num(c.internalEdges.toDouble),
          "crossEdges" -> ujson.Num(c.crossEdges.toDouble),
          "internalWeight" -> ujson.Num(c.internalWeight.toDouble),
          "crossWeight" -> ujson.Num(c.crossWeight.toDouble),
          "neighborsTotal" -> ujson.Num(analysis.neighborsTotal),
          "neighbors" -> ujson.Arr.from(
            analysis.neighbors.map(n =>
              ujson.Obj(
                "communityId" -> n.neighborId,
                "edgeCount" -> n.edgeCount.toDouble,
                "totalWeight" -> n.totalWeight.toDouble
              ): ujson.Value
            )
          ),
          "directDegree" -> ujson.Obj(
            "distinctCoauthorCount" -> Facts.optNum(
              analysis.distinctCoauthors.map(_.toDouble)
            ),
            "weightedDegree" -> Facts.optNum(analysis.weightedDegree)
          ),
          "structuralBridge" -> ujson.Obj(
            "bridgeScore" -> Facts.optNum(analysis.bridgeScore)
          ),
          "runId" -> ujson.Str(c.provenance.runId),
          "metricVersion" -> ujson.Str(c.provenance.transformVersion)
        )
      )
      deterministic(
        EvidenceType.Metric,
        s"community|${analysis.researcherId}|${c.window}|${c.communityId}",
        "community",
        c.provenance,
        fact
      )
    }

  def publication(info: WorkInfo): String =
    val fact = Facts.obj(
      Seq(
        "kind" -> ujson.Str("publication"),
        "workId" -> ujson.Str(info.workId),
        "title" -> ujson.Str(screen("stored-title", info.title)),
        "year" -> Facts.optNum(info.year.map(_.toDouble)),
        "source" -> Facts.optStr(info.source)
      )
    )
    deterministic(
      EvidenceType.Publication,
      s"publication|${info.workId}",
      "publication",
      info.provenance,
      fact,
      workId = Some(info.workId)
    )

  /** Semantic items. Each is a `TextChunk` that holds the snippet as data and
    * is marked descriptive. A snippet with instruction-like text is flagged and
    * stays data.
    */
  def semantic(context: SemanticContext): Vector[String] =
    context.items.filter(_.snippet.trim.nonEmpty).map { item =>
      val hits = InjectionScreen.scan(item.snippet)
      hits.foreach(h => flagBuffer += s"retrieved-text:$h")
      val caveat =
        "Descriptive only: semantic context scored by vector distance. It supports no number, coauthorship, path, component or bridge claim."
      val flagged =
        if hits.isEmpty then ""
        else
          s" It contains instruction-like text (${hits.mkString(", ")}), which is treated as data and ignored."
      val provenance = Provenance(
        ContractSchema.currentVersion,
        SourceType.Derived,
        "semantic-context",
        item.vectorKey,
        item.textRef,
        item.templateVersion,
        queryRunId
      )
      add(
        EvidenceItem(
          ContractSchema.currentVersion,
          StableIds.evidence(
            SourceType.Derived,
            item.templateVersion,
            item.vectorKey,
            EvidenceType.TextChunk,
            item.index
          ),
          EvidenceType.TextChunk,
          None,
          item.textRef,
          Some(
            StableIds.chunk(
              item.vectorKey,
              item.templateVersion,
              item.documentType,
              None,
              item.snippet
            )
          ),
          None,
          item.distance,
          None,
          Some(item.snippet),
          Some(caveat + flagged),
          provenance
        )
      )
    }

  def hasIdentity: Boolean =
    items.values.exists(_.evidenceType == EvidenceType.IdentityLink)

  def hasDeterministic: Boolean =
    items.values.exists(i =>
      i.evidenceType != EvidenceType.TextChunk &&
        i.evidenceType != EvidenceType.IdentityLink
    )

  def build(extraNotes: Vector[String]): EvidenceBundle =
    val all = (noteBuffer.toVector ++ extraNotes).distinct
    val ids = items.keys.toVector
    EvidenceBundle(
      ContractSchema.currentVersion,
      "bundle:v1:" + Hashing.sha256((queryId +: ids).mkString("|")),
      items.values.toVector,
      if all.isEmpty then None else Some(all.mkString(" ")),
      RagRuntime.provenance(
        queryRunId,
        queryId,
        "scholarchain:rag/evidence-bundle"
      )
    )
