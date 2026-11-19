package scholarchain.rag

import scala.util.matching.Regex
import scholarchain.core.{
  CollaborationMetric,
  ContractSchema,
  Provenance,
  QueryOperation,
  QueryPlan,
  StableIds,
  YearWindow
}

/** `maxLimit` bounds a top-N request. A ranking answer may hold `maxLimit + 2`
  * claims, which must fit the synthesis output limit (P16-41).
  */
final case class PlannerConfig(
    defaultWindow: YearWindow = YearWindow(1, 9999),
    maxNames: Int = 4,
    defaultLimit: Int = 5,
    maxLimit: Int = 12
)

/** The plan before researcher names are resolved. A core `QueryPlan` carries
  * researcher IDs only, so `toQueryPlan` is called once resolution is done.
  * Nothing in a draft is query text: names, a window, a topic and a metric are
  * parameters, and the operation is one of the core `QueryOperation` cases.
  */
final case class PlanDraft(
    question: String,
    queryId: String,
    operation: QueryOperation,
    names: Vector[String],
    explicitIds: Vector[String],
    window: YearWindow,
    windowExplicit: Boolean,
    comparisonWindow: Option[YearWindow],
    metrics: Vector[CollaborationMetric],
    rankMetric: Option[CollaborationMetric],
    resultLimit: Int,
    topic: Option[String],
    venue: Option[String],
    institution: Option[String],
    flags: Vector[String],
    notes: Vector[String],
    classifiedBy: String
):
  def toQueryPlan(
      researcherIds: Vector[String],
      provenance: Provenance
  ): QueryPlan =
    QueryPlan(
      ContractSchema.currentVersion,
      queryId,
      operation,
      researcherIds,
      Some(window),
      comparisonWindow,
      metrics,
      resultLimit,
      provenance
    )

enum PlannerResult:
  case Planned(draft: PlanDraft, usage: ModelUsage)
  case Refused(reason: String, flags: Vector[String], usage: ModelUsage)

/** Question to plan. Deterministic rules decide first. Only when they find no
  * operation does the model port classify, and its answer must be one of the
  * closed `QueryOperation` wire values or null: anything else is malformed
  * output, is retried within the bound, and then refused.
  */
final class QueryPlannerAgent(
    model: ModelPort,
    config: PlannerConfig = PlannerConfig(),
    retry: ModelRetryPolicy = ModelRetryPolicy()
):
  import QueryPlannerAgent.*

  def plan(question: String, queryId: String): PlannerResult =
    val text = question.trim
    val flags = InjectionScreen.scan(text).map(label => s"question:$label")
    val ids = idPattern
      .findAllIn(text)
      .toVector
      .filter(StableIds.isCoauthorNode)
      .distinct
    val bare = idPattern.replaceAllIn(text, " ")
    val spans = windowsOf(bare)
    if spans.exists((from, to) => from < 1 || to > 9999 || from > to) then
      PlannerResult.Refused(
        "the time window is not a valid range of years",
        flags :+ "invalid-window",
        ModelUsage.zero
      )
    else
      val windows = spans.map((from, to) => YearWindow(from, to))
      val (names, orgs) = namesOf(bare)
      if names.size + ids.size > config.maxNames then
        PlannerResult.Refused(
          s"the question names more than ${config.maxNames} researchers",
          flags :+ "too-many-names",
          ModelUsage.zero
        )
      else
        val lower = bare.toLowerCase
        val ranked = rankRe.findFirstIn(lower).isDefined
        val found = metricsOf(lower)
        val composite = compositeCue.findFirstIn(lower).isDefined
        val rule = operationOf(lower, windows.size, names.size + ids.size)
        val (operation, by, usage, refusal) = rule match
          case Some(op) => (Some(op), "rules", ModelUsage.zero, None)
          case None     => classify(text)
        operation match
          case None =>
            PlannerResult.Refused(
              refusal.getOrElse("no allowed operation fits the question"),
              flags,
              usage
            )
          case Some(op) =>
            val noNames = names.isEmpty && ids.isEmpty
            val rankMetric =
              if !noNames then None
              else if op == QueryOperation.BridgeAnalysis then
                Some(CollaborationMetric.BridgeScore)
              else if ranked then found.headOption
              else None
            val keepsDirectDegree =
              op == QueryOperation.BridgeAnalysis ||
                op == QueryOperation.Community
            val metrics =
              if found.isEmpty then defaultMetrics(op)
              else if keepsDirectDegree && !noNames then
                (found ++ defaultMetrics(op)).distinct
              else found
            val notes =
              (if composite then
                 Vector(
                   "No composite score is computed: each requested metric is reported separately."
                 )
               else Vector.empty) ++
                (if orgs.nonEmpty then
                   Vector(
                     "Venue and institution filters are recorded only: the graph has no venue or institution linkage to apply them to."
                   )
                 else Vector.empty)
            PlannerResult.Planned(
              PlanDraft(
                text,
                queryId,
                op,
                names,
                ids,
                windows.headOption.getOrElse(config.defaultWindow),
                windows.nonEmpty,
                windows.lift(1),
                metrics,
                rankMetric,
                limitOf(lower),
                topicOf(bare),
                orgs.find(o => venueWords.exists(o.endsWith)),
                orgs.find(o => institutionWords.exists(o.endsWith)),
                flags ++ (if composite then Vector("composite-score-requested")
                          else Vector.empty),
                notes,
                by
              ),
              usage
            )

  private def classify(
      text: String
  ): (Option[QueryOperation], String, ModelUsage, Option[String]) =
    val input = ujson.Obj(
      "instructions" -> Prompts.Planner.text,
      "allowedOperations" -> Facts.strs(QueryOperation.values.map(_.wire)),
      "untrusted" -> ujson.Obj("question" -> text)
    )
    val request = ModelRequest(
      Prompts.Planner.id,
      Prompts.Planner.version,
      Prompts.Planner.schemaId,
      input,
      200
    )
    StructuredCall.run(model, request, retry) match
      case StructuredOutcome.Valid(json, _, usage) =>
        Facts.s(json, "operation") match
          case Some(wire) =>
            QueryOperation.values.find(_.wire == wire) match
              case Some(op) =>
                (
                  Some(op),
                  s"model:${usage.modelVersion.getOrElse("unknown")}",
                  usage,
                  None
                )
              case None =>
                (
                  None,
                  "model",
                  usage,
                  Some(
                    "the classifier named an operation outside the allowed set"
                  )
                )
          case None =>
            (
              None,
              "model",
              usage,
              Some("no allowed operation fits the question")
            )
      case StructuredOutcome.Failed(reasons, attempts, usage) =>
        (
          None,
          "model",
          usage,
          Some(
            s"the classifier gave no valid answer after $attempts attempt(s): ${reasons.headOption.getOrElse("unknown")}"
          )
        )

  private def limitOf(lower: String): Int =
    limitPattern
      .findFirstMatchIn(lower)
      .flatMap(m => Option(m.group(1)).orElse(Option(m.group(2))))
      .flatMap(_.toIntOption)
      .map(n => math.max(1, math.min(n, config.maxLimit)))
      .getOrElse(config.defaultLimit)

object QueryPlannerAgent:
  private val idPattern: Regex =
    "(?:researcher|mention):v1:[A-Za-z0-9_\\-]+(?::[A-Za-z0-9_\\-]+)?".r

  private val rankCue =
    "(?:\\bmost\\b|\\btop\\b|highest|largest|biggest|maximum|\\bmax\\b|\\brank|\\bbest\\b|strongest)"
  private val countCue =
    "(?:how many|number of|\\bcount\\b|average|" + rankCue
      .drop(3)
      .dropRight(1) + ")"
  private val compositeCue: Regex =
    "composite|combined score|overall score|influence score|single score".r

  private val limitPattern: Regex =
    "\\btop\\s+(\\d{1,3})\\b|\\b(\\d{1,3})\\s+(?:most|top|highest|largest)".r

  private def re(pattern: String): Regex = ("(?i)" + pattern).r

  private val pathCue = re(
    "shortest path|path (?:between|from|connecting)|degrees? of separation|how (?:is|are) .{1,80} connected|connected to|chain of (?:co)?author|link between|connection between"
  )
  private val bridgeCue = re("\\bbridg|\\bbroker|structural hole")
  private val communityCue = re("communit")
  private val componentCue = re("component")
  private val temporalCue =
    re(
      "compare|comparison|\\bchange|over time|\\btrend|evolv|\\bgrew|growth|difference"
    )
  private val coauthorCue =
    re(
      "co-?authors?|collaborators?|worked with|works with|collaborated with|collaborates with"
    )
  private val rankRe = re(rankCue)
  private val countRe = re(countCue)
  private val topicCue = re("\\btopics?\\b")
  private val institutionCue = re("institution|universit|affiliat")
  private val evidenceCue = re(
    "evidence|provenance|\\bsources?\\b|\\bcite|supporting"
  )
  private val metricCue = re(
    countCue + "|team size|weighted degree|publications?|papers?|coreness|degree|co-?authors?"
  )

  private val venueWords =
    Vector("Conference", "Journal", "Symposium", "Workshop", "Proceedings")
  private val institutionWords =
    Vector(
      "University",
      "Institute",
      "College",
      "Laboratory",
      "Lab",
      "School",
      "Academy"
    )

  private def operationOf(
      lower: String,
      windowCount: Int,
      people: Int
  ): Option[QueryOperation] =
    def has(r: Regex) = r.findFirstIn(lower).isDefined
    if has(pathCue) then Some(QueryOperation.ShortestPath)
    else if windowCount >= 2 then Some(QueryOperation.TemporalComparison)
    else if has(bridgeCue) then Some(QueryOperation.BridgeAnalysis)
    else if has(communityCue) then Some(QueryOperation.Community)
    else if has(componentCue) then Some(QueryOperation.Component)
    else if has(temporalCue) then Some(QueryOperation.TemporalComparison)
    else if people > 0 && has(coauthorCue) && !has(countRe) then
      Some(QueryOperation.CoauthorLookup)
    else if has(topicCue) then Some(QueryOperation.Topic)
    else if has(institutionCue) then Some(QueryOperation.Institution)
    else if has(evidenceCue) then Some(QueryOperation.EvidenceLookup)
    else if has(metricCue) then Some(QueryOperation.CollaborationMetrics)
    else if people > 0 then Some(QueryOperation.ResearcherLookup)
    else None

  private val metricPatterns: Vector[(CollaborationMetric, Regex)] = Vector(
    CollaborationMetric.AverageCoauthorsPerPaper ->
      re(
        "average\\s+(?:number of\\s+)?(?:co-?authors?(?:\\s+per\\s+paper)?|team)"
      ),
    CollaborationMetric.TotalCoauthorOccurrences ->
      re("total\\s+co-?authors?|coauthor occurrences"),
    CollaborationMetric.WeightedDegree ->
      re(
        "weighted degree|repeated|strongest|intensity|joint publications?|closest"
      ),
    CollaborationMetric.MaximumCoauthorsOnOnePaper ->
      re(
        "team size|largest team|biggest team|maximum team|max team|\\bteams?\\b"
      ),
    CollaborationMetric.Coreness -> re("coreness|core number|k-core"),
    CollaborationMetric.BridgeScore -> re("\\bbridg\\w*|\\bbroker\\w*"),
    CollaborationMetric.ComponentSize ->
      re("component size|size of (?:the |its )?(?:connected )?component"),
    CollaborationMetric.PublicationCount ->
      re("publications?|\\bpapers?\\b|articles?|published"),
    CollaborationMetric.DistinctCoauthorCount ->
      re("distinct co-?authors?|co-?authors?|breadth|collaborators?")
  )

  /** Each pattern consumes the words it matched, so "average coauthors per
    * paper" is one metric, not three.
    */
  private def metricsOf(lower: String): Vector[CollaborationMetric] =
    var remaining = lower
    metricPatterns.flatMap { (metric, regex) =>
      val hit = regex.findFirstIn(remaining).isDefined
      if hit then remaining = regex.replaceAllIn(remaining, " ")
      if hit then Some(metric) else None
    }

  private def defaultMetrics(op: QueryOperation): Vector[CollaborationMetric] =
    import CollaborationMetric.*
    op match
      case QueryOperation.ShortestPath => Vector(ShortestPathLength)
      case QueryOperation.Component    => Vector(ComponentSize)
      case QueryOperation.Community | QueryOperation.BridgeAnalysis =>
        Vector(BridgeScore, DistinctCoauthorCount, WeightedDegree)
      case QueryOperation.CoauthorLookup     => Vector(DistinctCoauthorCount)
      case QueryOperation.TemporalComparison =>
        Vector(PublicationCount, DistinctCoauthorCount, WeightedDegree)
      case _ =>
        Vector(
          PublicationCount,
          DistinctCoauthorCount,
          WeightedDegree,
          MaximumCoauthorsOnOnePaper
        )

  private def topicOf(text: String): Option[String] =
    "(?i)\\btopics?\\s+(?:of\\s+|on\\s+|:\\s*)?[\"“]?([A-Za-z][A-Za-z0-9 \\-]{2,40}?)[\"”]?\\s*(?:[?.,;]|$|\\b(?:in|since|between|during|for|at|from)\\b)".r
      .findFirstMatchIn(text)
      .map(_.group(1).trim)

  private val yearRanges: Vector[(Regex, Boolean)] = Vector(
    re("between\\s+(\\d{4})\\s+and\\s+(\\d{4})") -> true,
    re("\\b(\\d{4})\\s*(?:-|–|—|to|through)\\s*(\\d{4})\\b") -> true
  )
  private val sinceYear = re("since\\s+(\\d{4})")
  private val oneYear = re("\\b(?:in|during|for|of|year)\\s+(\\d{4})\\b")
  private val allTime = re(
    "all time|overall|all years|entire period|full period"
  )

  /** Windows in the order they appear. A span is used once, so a range is not
    * read again as two single years.
    */
  private[rag] def windowsOf(text: String): Vector[(Int, Int)] =
    val candidates: Vector[(Int, Int, (Int, Int))] =
      yearRanges.flatMap { (regex, _) =>
        regex
          .findAllMatchIn(text)
          .map(m => (m.start, m.end, (m.group(1).toInt, m.group(2).toInt)))
      } ++ sinceYear
        .findAllMatchIn(text)
        .map(m => (m.start, m.end, (m.group(1).toInt, 9999)))
        .toVector ++ oneYear
        .findAllMatchIn(text)
        .map(m => (m.start, m.end, (m.group(1).toInt, m.group(1).toInt)))
        .toVector ++ allTime
        .findAllMatchIn(text)
        .map(m => (m.start, m.end, (1, 9999)))
        .toVector
    // Candidates are in priority order (ranges first), so an earlier span is
    // kept and a later one that overlaps it is dropped.
    val accepted = candidates.foldLeft(Vector.empty[(Int, Int, (Int, Int))]) {
      (kept, next) =>
        if kept.exists(k => next._1 < k._2 && k._1 < next._2) then kept
        else kept :+ next
    }
    accepted.sortBy(_._1).map(_._3)

  private val stop = Set(
    "Who",
    "What",
    "Which",
    "How",
    "Show",
    "Find",
    "List",
    "Compare",
    "Tell",
    "Is",
    "Are",
    "Does",
    "Did",
    "Do",
    "Has",
    "Have",
    "The",
    "A",
    "An",
    "Between",
    "From",
    "And",
    "Or",
    "In",
    "On",
    "Of",
    "To",
    "Since",
    "Give",
    "Please",
    "Why",
    "Where",
    "When",
    "Rank",
    "Ranking",
    "Top",
    "Explain",
    "Describe",
    "Identify",
    "Name",
    "Return",
    "Count",
    "Dr",
    "Prof",
    "Professor",
    "Mr",
    "Ms",
    "Mrs",
    "Can",
    "Could",
    "Would",
    "Will",
    "Also",
    "Ignore",
    "Disregard",
    "Forget",
    "Reveal",
    "Print",
    "Repeat",
    "Say",
    "State",
    "Report",
    "Answer",
    "Run",
    "System",
    "Assistant",
    "Developer",
    "Then",
    "Now",
    "Instead",
    "Treat",
    "Consider",
    "Use",
    "Summarize",
    "Summarise",
    "Get",
    "Make",
    "Let",
    "Why"
  )
  private val tokenPattern: Regex = "\\p{L}[\\p{L}'’.\\-]*".r

  private def clean(token: String): String =
    val noPossessive =
      if token.endsWith("'s") || token.endsWith("’s") then token.dropRight(2)
      else token
    val trimmed = noPossessive.stripSuffix("-")
    if trimmed.length > 2 then trimmed.stripSuffix(".") else trimmed

  /** Capitalized word runs of two or more are researcher names, except runs
    * that end in an institution or venue word. Lowercase and inverted names
    * (`Smith, John`) are not found; the resolver sees only what is extracted.
    */
  private[rag] def namesOf(text: String): (Vector[String], Vector[String]) =
    val tokens = tokenPattern
      .findAllMatchIn(text)
      .toVector
      .map(m => (m.start, m.end, clean(m.matched)))
    val runs = Vector.newBuilder[Vector[String]]
    var current = Vector.empty[String]
    var previousEnd = -10
    tokens.foreach { (start, end, word) =>
      val isName = word.headOption.exists(_.isUpper) &&
        !stop.contains(word) && !(word.length > 1 && word.forall(_.isUpper))
      if isName && current.nonEmpty && text.substring(previousEnd, start) == " "
      then current = current :+ word
      else
        if current.nonEmpty then runs += current
        current = if isName then Vector(word) else Vector.empty
      previousEnd = end
    }
    if current.nonEmpty then runs += current
    val multi = runs.result().filter(_.size >= 2).map(_.mkString(" "))
    val (orgs, people) = multi.partition(run =>
      (venueWords ++ institutionWords).exists(w => run.endsWith(w))
    )
    (people.distinct, orgs.distinct)
