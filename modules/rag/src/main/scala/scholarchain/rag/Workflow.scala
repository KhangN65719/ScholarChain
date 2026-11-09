package scholarchain.rag

import scala.collection.mutable
import scholarchain.core.{
  AnswerClaim,
  AnswerResponse,
  CollaborationMetric,
  ContractSchema,
  EvidenceBundle,
  EvidenceType,
  Provenance,
  QueryOperation,
  QueryPlan,
  VerificationStatus,
  YearWindow
}
import scholarchain.storage.{
  AuditOutcome,
  AuditRecord,
  AuditTrail,
  CachedMetrics,
  CommunityReadPort,
  ComponentReadPort,
  DynamoTable,
  Hashing,
  NeptuneQueryPort,
  PathEvidencePort,
  PathLookup,
  ResearcherDirectoryPort,
  StorageException,
  WindowLabels
}

/** One turn of a conversation. `disambiguation` maps a normalized name
  * (`ResearcherResolverAgent.key`) to the node ID the user chose from an
  * earlier list of candidates. It is the only way an ambiguous name resolves.
  */
final case class Session(
    sessionId: String,
    ordinal: Int,
    disambiguation: Map[String, String] = Map.empty
)

/** Everything the workflow reads or writes, as ports. Nothing here reaches a
  * network: the local fakes and the stub model satisfy every port.
  */
final case class WorkflowPorts(
    metricCache: DynamoTable,
    ranking: MetricRanking,
    neptune: NeptuneQueryPort,
    paths: PathEvidencePort,
    components: ComponentReadPort,
    communities: CommunityReadPort,
    directory: ResearcherDirectoryPort,
    works: WorkCatalog,
    semantic: Option[SemanticRetrieval],
    model: ModelPort,
    sessions: DynamoTable,
    bundles: DynamoTable,
    clock: RuntimeClock
)

/** `graphRunId` pins metric reads and labels graph evidence. `queryRunId` is
  * the run ID the audit and the evidence bundle carry; the caller mints it, so
  * a rerun with the same inputs is identical.
  */
final case class WorkflowConfig(
    graphRunId: String,
    queryRunId: String,
    planner: PlannerConfig = PlannerConfig(),
    resolver: ResolverConfig = ResolverConfig(),
    retry: ModelRetryPolicy = ModelRetryPolicy(),
    paths: PathLimits = PathLimits(),
    caps: EvidenceCaps = EvidenceCaps(),
    auditTtlSeconds: Long = 7L * 24 * 3600
)

final case class AuditEntry(
    record: AuditRecord,
    outcome: AuditOutcome,
    answerHash: String,
    payload: ujson.Value
)

final case class WorkflowResult(
    response: AnswerResponse,
    plan: Option[QueryPlan],
    planDraft: Option[PlanDraft],
    resolutions: Vector[Resolution],
    verification: Option[VerificationReport],
    abstained: Boolean,
    abstentionReason: Option[String],
    flags: Vector[String],
    audit: AuditEntry
):
  def verdict: Option[VerificationVerdict] = verification.flatMap(_.overall)

/** The runtime collaboration-analysis workflow. `answer` composes the stages
  * over the injected ports: plan, resolve, retrieve (metrics, paths,
  * communities), semantic context, evidence bundle, synthesis, verification and
  * audit. Any stage that cannot proceed ends in an abstaining answer, which is
  * audited like any other. No HTTP lives here (Phase 10).
  */
final class CollaborationWorkflow(
    ports: WorkflowPorts,
    config: WorkflowConfig
):
  private val planner =
    new QueryPlannerAgent(ports.model, config.planner, config.retry)
  private val resolver = new ResearcherResolverAgent(
    ports.directory,
    ports.neptune,
    config.resolver
  )
  private val metricsAgent = new CollaborationMetricsAgent(
    ports.metricCache,
    ports.ranking,
    config.graphRunId
  )
  private val pathAgent =
    new PathFinderAgent(ports.neptune, ports.paths, config.paths)
  private val communityAgent =
    new CommunityAnalystAgent(ports.components, ports.communities)
  private val synthesis = new AnswerSynthesisAgent(ports.model, config.retry)
  private val verifier = new ClaimVerificationAgent
  private val semanticAgent = ports.semantic.map(new SemanticContextAgent(_))

  private final class Abstain(
      val code: String,
      val message: String,
      val claims: Vector[AnswerClaim]
  ) extends RuntimeException(message, null, false, false)

  def answer(question: String, session: Session): WorkflowResult =
    new Run(question, session).execute()

  private final class Run(question: String, session: Session):
    private val started = ports.clock.nowMillis()
    private val queryId = "query:v1:" + Hashing.sha256(
      s"${session.sessionId}|${session.ordinal}|$question"
    )
    private val names = mutable.Map.empty[String, Option[String]]
    private val builder = new EvidenceBuilderAgent(
      queryId,
      config.queryRunId,
      config.graphRunId,
      nameOf,
      config.caps
    )
    private val gaps = mutable.ArrayBuffer.empty[String]
    private val stages = mutable.ArrayBuffer.empty[ujson.Value]
    private val flags = mutable.ArrayBuffer.empty[String]
    private val resolutions = mutable.ArrayBuffer.empty[Resolution]
    private val prompts = mutable.LinkedHashMap.empty[String, PromptSpec]
    private var usage = ModelUsage.zero
    private var draft: Option[PlanDraft] = None
    private var plan: Option[QueryPlan] = None
    private var verification: Option[VerificationReport] = None
    private var synthesisOutput: Option[SynthesisOutput] = None

    private def nameOf(id: String): Option[String] =
      names.getOrElseUpdate(
        id, {
          val found =
            ports.directory.byId(id).map(_.displayName).filter(_.trim.nonEmpty)
          // A name goes into gap text that reaches the model, so it is screened
          // like any stored text; a hit is flagged and the name stays data.
          found.foreach(n =>
            InjectionScreen.scan(n).foreach(h => flags += s"stored-name:$h")
          )
          found
        }
      )

    private def display(id: String): String =
      nameOf(id).getOrElse("an unnamed researcher")

    private def stage(name: String, outcome: String): Unit =
      stages += ujson.Obj("name" -> name, "outcome" -> outcome)

    private def prov(recordId: String): Provenance =
      RagRuntime.provenance(
        config.queryRunId,
        recordId,
        "scholarchain:rag/answer"
      )

    private def abstain(
        code: String,
        message: String,
        claims: Vector[AnswerClaim] = Vector.empty
    ): Nothing =
      throw new Abstain(code, message, claims)

    private def addUsage(more: ModelUsage, prompt: PromptSpec): Unit =
      usage = usage + more
      if more.calls > 0 then prompts.update(prompt.id, prompt)

    def execute(): WorkflowResult =
      val outcome =
        try Right(pipeline())
        catch
          case a: Abstain          => Left(a)
          case e: StorageException =>
            Left(
              new Abstain(
                "storage-error",
                s"A stored-data read failed (${e.code}), so no answer was written.",
                Vector.empty
              )
            )
          case e: EvidenceCapExceeded =>
            Left(
              new Abstain(
                "evidence-cap",
                s"The evidence is larger than the allowed bundle (${e.getMessage}). Narrow the question.",
                Vector.empty
              )
            )
      val bundle = builder.build(gaps.toVector)
      val (text, claims, abstained, reason) = outcome match
        case Right(done) =>
          (done.text, done.claims, done.abstained, done.reason)
        case Left(a) => (a.message, a.claims, true, Some(a.code))
      stage("verify", if abstained then "abstained" else "answered")
      val response = AnswerResponse(
        ContractSchema.currentVersion,
        queryId,
        text,
        claims,
        bundle,
        prov(queryId)
      )
      val entry = writeAudit(response, bundle, abstained, reason)
      WorkflowResult(
        response,
        plan,
        draft,
        resolutions.toVector,
        verification,
        abstained,
        reason,
        (flags ++ builder.flags).toVector.distinct,
        entry
      )

    private final case class Done(
        text: String,
        claims: Vector[AnswerClaim],
        abstained: Boolean,
        reason: Option[String]
    )

    private def pipeline(): Done =
      if question.trim.isEmpty || question.length > RagRuntime.MaxQuestionChars
      then
        abstain(
          "invalid-question",
          s"The question must be 1 to ${RagRuntime.MaxQuestionChars} characters."
        )
      val d = planner.plan(question, queryId) match
        case PlannerResult.Refused(reason, f, u) =>
          flags ++= f
          addUsage(u, Prompts.Planner)
          stage("plan", "refused")
          abstain("planner-refused", s"The question was not planned: $reason.")
        case PlannerResult.Planned(planned, u) =>
          flags ++= planned.flags
          addUsage(u, Prompts.Planner)
          stage("plan", s"${planned.operation.wire} by ${planned.classifiedBy}")
          planned
      draft = Some(d)
      val ids = resolve(d)
      plan = Some(d.toQueryPlan(ids.distinct, prov(queryId)))
      retrieve(d, ids)
      semantic(d, ids)
      val lookupOnly =
        d.operation == QueryOperation.ResearcherLookup && builder.hasIdentity
      if !builder.hasDeterministic && !lookupOnly then
        stage("evidence", "no deterministic evidence")
        abstain(
          "no-deterministic-evidence",
          ("The stored graph and metrics hold no evidence that answers the question." +
            (if gaps.isEmpty then "" else " " + gaps.mkString(" "))).trim
        )
      val bundle = builder.build(gaps.toVector)
      stage("evidence", s"${bundle.items.size} item(s)")
      val output = synthesis.synthesize(d, bundle, gaps.toVector) match
        case SynthesisResult.Failed(reasons, attempts, u)
            if attempts == 0 && reasons.contains(StructuredCall.OverBudget) =>
          addUsage(u, Prompts.Synthesis)
          stage("synthesis", "input over token budget")
          abstain(
            "input-over-budget",
            s"The answer could not be written: input over token budget (the evidence and question together are larger than the model input allows). Narrow the question."
          )
        case SynthesisResult.Failed(reasons, attempts, u) =>
          addUsage(u, Prompts.Synthesis)
          stage("synthesis", s"failed after $attempts attempt(s)")
          abstain(
            "synthesis-failed",
            s"The answer could not be written: the model output was invalid after $attempts attempt(s)."
          )
        case SynthesisResult.Done(out, attempts, u) =>
          addUsage(u, Prompts.Synthesis)
          stage("synthesis", s"$attempts attempt(s)")
          out
      synthesisOutput = Some(output)
      val fin = verifier.finalizeAnswer(bundle, output, gaps.toVector, prov)
      verification = Some(fin.report)
      Done(
        fin.text,
        fin.claims,
        fin.abstained,
        if fin.abstained then Some("claims-unsupported") else None
      )

    private def resolve(d: PlanDraft): Vector[String] =
      val people = d.names.size + d.explicitIds.size
      val (min, max) = d.operation match
        case QueryOperation.ShortestPath   => (2, 2)
        case QueryOperation.CoauthorLookup => (1, 2)
        case QueryOperation.CollaborationMetrics |
            QueryOperation.BridgeAnalysis =>
          (if d.rankMetric.isDefined then 0 else 1, 4)
        case _ => (1, 4)
      if d.operation == QueryOperation.TemporalComparison &&
        d.comparisonWindow.isEmpty
      then
        abstain(
          "needs-two-windows",
          "A comparison needs two time windows, for example 2000-2009 and 2010-2019."
        )
      if people < min || people > max then
        stage("resolve", "wrong number of researchers")
        abstain(
          "researcher-count",
          if people == 0 && min == 1 && max == 4 then
            "Name a researcher, or name one metric to rank by (distinct coauthors, weighted degree, team size or bridge score). No composite score is computed."
          else
            s"This question needs ${
                if min == max then s"$min" else s"$min to $max"
              } researcher name(s); it names $people."
        )
      val found =
        d.explicitIds.map(resolver.resolveId(_, d.window)) ++
          d.names.map(n =>
            resolver.resolveName(
              n,
              d.window,
              session.disambiguation.get(ResearcherResolverAgent.key(n))
            )
          )
      resolutions ++= found
      val unresolved = found.filterNot(_.resolved)
      if unresolved.nonEmpty then
        stage("resolve", s"${unresolved.size} unresolved")
        abstain(
          "identity-unresolved",
          unresolved.map(identityText).mkString("\n") +
            "\nNo researcher was chosen. Name one candidate by identifier in a follow-up turn, or give a fuller name.",
          unresolved.zipWithIndex.map((r, i) => identityClaim(r, i + 1))
        )
      found.foreach(r => builder.identity(r.chosen.get))
      stage("resolve", s"${found.size} resolved")
      found.map(_.chosen.get.nodeId)

    private def describe(c: ResolvedCandidate): String =
      val x = c.candidate
      val extras = Vector(
        x.orcid.map(o => s"ORCID $o"),
        Some(s"id ${x.nodeId}"),
        if x.isMention then Some("unresolved author mention") else None,
        if c.coauthorContext.nonEmpty then
          Some(
            "coauthors " + c.coauthorContext
              .take(3)
              .map(l => display(l.coauthorId))
              .mkString(", ")
          )
        else None
      ).flatten
      s"${x.displayName} (${extras.mkString("; ")})"

    private def identityText(r: Resolution): String =
      val listing =
        if r.candidates.isEmpty then ""
        else " Candidates: " + r.candidates.map(describe).mkString(" | ") + "."
      s"""The name "${r.name}" was not resolved: ${r.note}.$listing"""

    private def identityClaim(r: Resolution, ordinal: Int): AnswerClaim =
      val evidence = r.candidates.map(c => builder.identity(c)).distinct
      AnswerClaim(
        ContractSchema.currentVersion,
        s"claim-identity-$ordinal",
        identityText(r),
        evidence,
        VerificationStatus.Uncertain,
        Some(s"${r.status}: ${r.note}"),
        prov(s"identity-$ordinal")
      )

    private def addMetrics(
        id: String,
        window: YearWindow
    ): Option[CachedMetrics] =
      metricsAgent.metrics(id, window) match
        case MetricLookup.Found(c) =>
          builder.metrics(c)
          Some(c)
        case MetricLookup.Absent(_, w) =>
          gaps += s"No stored metrics for ${display(id)} in window ${WindowLabels.of(w)}."
          None
        case MetricLookup.Failed(_, w, why) =>
          gaps += s"Stored metrics for ${display(id)} in window ${WindowLabels.of(w)} could not be read ($why)."
          None

    private def addWork(workId: String): Unit =
      ports.works.work(workId) match
        case Some(info) => builder.publication(info)
        case None       =>
          gaps += "A cited work has no stored title record."

    private def rank(d: PlanDraft): Unit =
      val metric = d.rankMetric.get
      val r = metricsAgent.rank(d.window, metric, d.resultLimit)
      if r.entries.isEmpty then
        gaps += s"No researchers are ranked by ${metric.wire} in window ${WindowLabels.of(d.window)}."
      else
        builder.ranking(r)
        r.entries.foreach(e => builder.metrics(e.cached))

    private def retrieve(d: PlanDraft, ids: Vector[String]): Unit =
      val window = d.window
      d.operation match
        case QueryOperation.ShortestPath =>
          val lookup =
            try pathAgent.shortestPath(ids(0), ids(1), window)
            catch
              case e: StorageException =>
                abstain(
                  "path-query-rejected",
                  s"The path request was refused (${e.code})."
                )
          builder.path(lookup, ids(0), ids(1), window)
          lookup match
            case PathLookup.Found(_, _, _, hops) =>
              hops
                .flatMap(_.supportingWorkIds.take(1))
                .distinct
                .take(4)
                .foreach(addWork)
            case _ => ()
        case QueryOperation.CoauthorLookup =>
          val first = ids.head
          if ids.size == 2 then
            val (edge, complete) = pathAgent.directEdge(ids(0), ids(1), window)
            edge match
              case Some(link) =>
                builder.coauthorEdge(ids(0), link, window)
                pathAgent.edgeWorks(link.edgeId).take(3).foreach(addWork)
              case None =>
                gaps += s"No direct coauthor edge between ${display(ids(0))} and ${display(ids(1))} was found in window ${WindowLabels.of(window)}" +
                  (if complete then "." else "; the read was not complete.")
          else
            val page = pathAgent.directCollaborators(first, window)
            builder.coauthors(page)
            if page.links.isEmpty then
              gaps += s"${display(first)} has no stored coauthors in window ${WindowLabels.of(window)}."
          addMetrics(first, window)
        case QueryOperation.Component =>
          ids.distinct.foreach { id =>
            val m = addMetrics(id, window)
            val analysis = communityAgent.analyze(id, window, m)
            if builder.component(analysis).isEmpty then
              gaps += s"No stored component for ${display(id)} in window ${WindowLabels.of(window)}."
          }
        case QueryOperation.Community | QueryOperation.BridgeAnalysis =>
          if ids.isEmpty then rank(d)
          else
            ids.distinct.foreach { id =>
              val m = addMetrics(id, window)
              val analysis = communityAgent.analyze(id, window, m)
              builder.component(analysis)
              if builder.community(analysis).isEmpty then
                gaps += s"No stored community for ${display(id)} in window ${WindowLabels.of(window)}."
            }
        case QueryOperation.TemporalComparison =>
          ids.distinct.foreach { id =>
            addMetrics(id, window)
            d.comparisonWindow.foreach(addMetrics(id, _))
          }
        case _ =>
          if d.operation == QueryOperation.Topic ||
            d.operation == QueryOperation.Institution
          then
            gaps += "The graph links no topic or institution to researchers, so a topic or institution filter cannot be applied; any such context is descriptive only."
          if ids.isEmpty then rank(d)
          else
            ids.distinct.foreach { id =>
              val m = addMetrics(id, window)
              val wantsTeam =
                d.metrics.contains(
                  CollaborationMetric.MaximumCoauthorsOnOnePaper
                ) || d.operation == QueryOperation.EvidenceLookup
              if wantsTeam then
                m.foreach(_.metrics.maximumTeamWorkIds.take(3).foreach(addWork))
            }
      stage("retrieve", s"${builder.hasDeterministic}")

    private def semantic(d: PlanDraft, ids: Vector[String]): Unit =
      semanticAgent.foreach { agent =>
        val text = (d.names ++ d.topic.toVector :+ d.question).mkString(" ")
        val (contexts, problems) = agent.context(
          d.operation,
          text,
          ids,
          if d.windowExplicit then Some(d.window) else None
        )
        contexts.foreach(builder.semantic)
        problems.foreach(builder.note)
        stage("semantic", s"${contexts.map(_.items.size).sum} item(s)")
      }

    private def writeAudit(
        response: AnswerResponse,
        bundle: EvidenceBundle,
        abstained: Boolean,
        reason: Option[String]
    ): AuditEntry =
      val finished = ports.clock.nowMillis()
      val answerHash = Hashing.sha256(
        response.answer + "\n" + upickle.default.write(response.claims)
      )
      val deterministic =
        bundle.items.filter(_.evidenceType != EvidenceType.TextChunk)
      val payload = ujson.Obj(
        "schema" -> "scholarchain.rag.audit/1",
        "sessionId" -> session.sessionId,
        "ordinal" -> session.ordinal,
        "queryId" -> queryId,
        "question" -> question.take(RagRuntime.MaxQuestionChars),
        "queryPlan" -> plan.fold[ujson.Value](ujson.Null)(
          upickle.default.writeJs(_)
        ),
        "planDetails" -> draft.fold[ujson.Value](ujson.Null)(planDetails),
        "resolutions" -> ujson.Arr.from(resolutions.toVector.map { r =>
          ujson.Obj(
            "name" -> r.name,
            "status" -> r.status.toString,
            "resolvedId" -> Facts.optStr(r.chosen.map(_.nodeId)),
            "candidateIds" -> Facts.strs(r.candidates.map(_.nodeId)),
            "selectedByUser" -> r.selectedByUser,
            "note" -> r.note
          ): ujson.Value
        }),
        "evidenceIds" -> Facts.strs(bundle.items.map(_.evidenceId)),
        "bundleId" -> bundle.bundleId,
        "metricVersions" -> Facts.strs(
          deterministic.map(_.provenance.transformVersion).distinct.sorted
        ),
        "evidenceRunIds" -> Facts.strs(
          deterministic.map(_.provenance.runId).distinct.sorted
        ),
        "graphRunId" -> config.graphRunId,
        "queryRunId" -> config.queryRunId,
        "prompts" -> ujson.Arr.from(prompts.values.toVector.map { p =>
          ujson.Obj(
            "id" -> p.id,
            "version" -> p.version,
            "hash" -> p.hash,
            "schemaId" -> p.schemaId
          ): ujson.Value
        }),
        "schemaHashes" -> ujson.Obj.from(
          Schemas.hashes.toVector
            .sortBy(_._1)
            .map((k, v) => k -> (ujson.Str(v): ujson.Value))
        ),
        "models" -> Facts.strs(usage.modelVersion.toVector),
        "modelCalls" -> usage.calls,
        "tokens" -> ujson.Obj(
          "promptTokens" -> usage.promptTokens,
          "completionTokens" -> usage.completionTokens,
          "estimated" -> usage.estimated,
          "note" -> "estimates from a character count, not a tokenizer"
        ),
        "latencyMillis" -> (finished - started).toDouble,
        "verification" -> verification.fold[ujson.Value](ujson.Null)(_.toJson),
        "modelMissingEvidence" -> Facts.strs(
          synthesisOutput.toVector.flatMap(_.missingEvidence)
        ),
        "abstained" -> abstained,
        "abstentionReason" -> Facts.optStr(reason),
        "answerHash" -> answerHash,
        "flags" -> Facts.strs((flags ++ builder.flags).toVector.distinct),
        "stages" -> ujson.Arr.from(stages)
      )
      val record = AuditRecord(
        session.sessionId,
        session.ordinal,
        ujson.write(payload),
        Some(bundle),
        queryId,
        config.queryRunId,
        started / 1000 + config.auditTtlSeconds
      )
      // A failed audit write throws out of `answer`: no answer is returned
      // without its audit, so the workflow fails closed.
      val outcome =
        AuditTrail.record(ports.sessions, ports.bundles, record)
      stage("audit", "stored")
      AuditEntry(record, outcome, answerHash, payload)

    private def planDetails(d: PlanDraft): ujson.Value =
      ujson.Obj(
        "operation" -> d.operation.wire,
        "names" -> Facts.strs(d.names),
        "explicitIds" -> Facts.strs(d.explicitIds),
        "window" -> WindowLabels.of(d.window),
        "windowExplicit" -> d.windowExplicit,
        "comparisonWindow" -> Facts.optStr(
          d.comparisonWindow.map(WindowLabels.of)
        ),
        "metrics" -> Facts.strs(d.metrics.map(_.wire)),
        "rankMetric" -> Facts.optStr(d.rankMetric.map(_.wire)),
        "resultLimit" -> d.resultLimit,
        "topic" -> Facts.optStr(d.topic),
        "venue" -> Facts.optStr(d.venue),
        "institution" -> Facts.optStr(d.institution),
        "classifiedBy" -> d.classifiedBy,
        "flags" -> Facts.strs(d.flags),
        "notes" -> Facts.strs(d.notes)
      )
