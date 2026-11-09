package scholarchain.rag

import scala.collection.mutable

/** A model whose replies a test scripts. `script` sees each request and returns
  * the raw reply, or `None` to pass the request to the fallback model. It
  * counts calls per prompt.
  */
final class ScriptedModel(
    script: (ModelRequest, Int) => Option[String],
    fallback: ModelPort = new DeterministicStubModel
) extends ModelPort:
  val requests = mutable.ArrayBuffer.empty[ModelRequest]
  private val perPrompt = mutable.Map.empty[String, Int].withDefaultValue(0)

  def calls(promptId: String): Int = perPrompt(promptId)
  def totalCalls: Int = requests.size

  def complete(request: ModelRequest): ModelResponse =
    requests += request
    val index = perPrompt(request.promptId)
    perPrompt(request.promptId) = index + 1
    script(request, index) match
      case Some(raw) => ModelResponse(raw, "scripted/1", 10, 10, true)
      case None      => fallback.complete(request)

/** A model that behaves like the stub and then edits its synthesis reply, to
  * stand for a model that has been steered by hostile text.
  */
final class TamperingModel(edit: (ujson.Value, ujson.Value) => ujson.Value)
    extends ModelPort:
  private val inner = new DeterministicStubModel

  def complete(request: ModelRequest): ModelResponse =
    val reply = inner.complete(request)
    if request.promptId != Prompts.Synthesis.id then reply
    else
      val edited = edit(ujson.read(reply.rawJson), request.input)
      reply.copy(rawJson = ujson.write(edited))

/** A bundle that holds one item of every kind the demo world can produce, built
  * through the agents, with the evidence ID of each.
  */
final class RichEvidence(world: DemoWorld):
  import scholarchain.core.CollaborationMetric
  val ids: DemoIds = DemoWorld.ids
  val early: scholarchain.core.YearWindow = DemoWorld.Early
  val late: scholarchain.core.YearWindow = DemoWorld.Late
  val builder = new EvidenceBuilderAgent(
    "query-rich",
    DemoWorld.QueryRunId,
    DemoWorld.GraphRunId,
    id => world.ports.directory.byId(id).map(_.displayName)
  )
  private val resolver =
    new ResearcherResolverAgent(world.ports.directory, world.ports.neptune)
  private val metricsAgent = new CollaborationMetricsAgent(
    world.cache,
    world.ports.ranking,
    DemoWorld.GraphRunId
  )
  private val finder =
    new PathFinderAgent(world.ports.neptune, world.ports.paths)
  private val analyst = new CommunityAnalystAgent(
    world.ports.components,
    world.ports.communities
  )

  private def cached(id: String, w: scholarchain.core.YearWindow) =
    metricsAgent.metrics(id, w) match
      case MetricLookup.Found(c) => c
      case other => throw new AssertionError(s"no metrics: $other")

  val adaIdentity: String =
    builder.identity(resolver.resolveName("Ada Quillfeather", early).chosen.get)
  val adaMetrics: String = builder.metrics(cached(ids.ada, early))
  val borisMetrics: String = builder.metrics(cached(ids.boris, early))
  val adaLateMetrics: String = builder.metrics(cached(ids.ada, late))
  val ranking: String = builder
    .ranking(
      metricsAgent.rank(early, CollaborationMetric.DistinctCoauthorCount, 10)
    )
    .get
  val borisCoauthors: Vector[String] =
    builder.coauthors(finder.directCollaborators(ids.boris, early))
  val path: String = builder.path(
    finder.shortestPath(ids.ada, ids.chidi, early),
    ids.ada,
    ids.chidi,
    early
  )
  val noPath: String = builder.path(
    finder.shortestPath(ids.ada, ids.dana, early),
    ids.ada,
    ids.dana,
    early
  )
  val unknownPath: String = builder.path(
    finder.shortestPath(ids.chidi, ids.dana, early),
    ids.chidi,
    ids.dana,
    early
  )
  private val borisAnalysis =
    analyst.analyze(ids.boris, early, Some(cached(ids.boris, early)))
  val component: String = builder.component(borisAnalysis).get
  val community: String = builder.community(borisAnalysis).get
  val publication: String =
    builder.publication(world.ports.works.work(ids.works("w4")).get)
  val chunks: Vector[String] = builder.semantic(
    world.ports.semantic.get.context(
      "Ada Quillfeather",
      world.ports.semantic.get.search(
        scholarchain.storage.VectorIndexes.ResearcherProfile,
        "Ada Quillfeather",
        2,
        scholarchain.storage.VectorFilter(researcher = Some(ids.ada))
      )
    )
  )
  lazy val bundle: scholarchain.core.EvidenceBundle =
    builder.build(Vector.empty)

  def item(id: String): scholarchain.core.EvidenceItem =
    bundle.items.find(_.evidenceId == id).get

  def fact(id: String): ujson.Value = ujson.read(item(id).text.get)

  /** The chunk whose snippet states "3 publications". */
  def numberChunk: String =
    chunks.find(c => item(c).text.exists(_.contains("3 publications"))).get

object RuntimeTestSupport:
  def ask(
      world: DemoWorld,
      question: String,
      ordinal: Int = 0,
      disambiguation: Map[String, String] = Map.empty
  ): WorkflowResult =
    world.workflow.answer(
      question,
      Session("test-session", ordinal, disambiguation)
    )

  def claimTexts(result: WorkflowResult): Vector[String] =
    result.response.claims.map(_.text)

  /** The evidence ID of the first item whose fact has this kind. */
  def evidenceOfKind(
      bundle: scholarchain.core.EvidenceBundle,
      kind: String
  ): Option[scholarchain.core.EvidenceItem] =
    bundle.items.find(i =>
      i.evidenceType != scholarchain.core.EvidenceType.TextChunk &&
        i.text.exists(t => Facts.kind(ujson.read(t)).contains(kind))
    )

  def draft(
      id: String,
      kind: String,
      text: String,
      evidence: Vector[String],
      assertion: ujson.Obj,
      uncertainty: Option[String] = None
  ): ClaimDraft =
    ClaimDraft(id, text, kind, evidence, assertion, uncertainty)

  def output(
      drafts: Vector[ClaimDraft],
      analysis: String = ""
  ): SynthesisOutput =
    SynthesisOutput(analysis, drafts, Vector.empty, None)

  def provenance(id: String): scholarchain.core.Provenance =
    RagRuntime.provenance(DemoWorld.QueryRunId, id, "scholarchain:rag/test")

  /** Plans a question with the stub model. */
  def plan(question: String): PlannerResult =
    new QueryPlannerAgent(new DeterministicStubModel).plan(question, "query-1")

  def planned(question: String): PlanDraft =
    plan(question) match
      case PlannerResult.Planned(d, _)         => d
      case PlannerResult.Refused(reason, _, _) =>
        throw new AssertionError(s"refused: $reason")
