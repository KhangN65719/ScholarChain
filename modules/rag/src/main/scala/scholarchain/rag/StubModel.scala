package scholarchain.rag

import scala.collection.mutable
import scholarchain.core.{CollaborationMetric, QueryOperation}

/** The offline model of Phase 9 (decision P3). It is rule-based, reads no
  * network and is bit-for-bit repeatable. Token counts are estimates (a
  * character count over four) and are labelled as such.
  *
  * Planner prompt: a looser keyword classifier than the planner's own rules,
  * used only when those find nothing. Synthesis prompt: claims written from the
  * deterministic facts in the input with the same `Rendering` the verifier
  * uses, so each claim carries the evidence ID of the fact it states. Snippets
  * are never read for numbers or quoted; the stub ignores any instruction
  * inside them because it never interprets them.
  */
final class DeterministicStubModel(
    val modelVersion: String = DeterministicStubModel.Version
) extends ModelPort:
  import DeterministicStubModel.*

  def complete(request: ModelRequest): ModelResponse =
    val input =
      if request.input.objOpt.exists(_.contains("original")) then
        request.input("original")
      else request.input
    val reply =
      if request.promptId == Prompts.Planner.id then planner(input)
      else if request.promptId == Prompts.Synthesis.id then synthesis(input)
      else ujson.Obj()
    val raw = ujson.write(reply)
    ModelResponse(
      raw,
      modelVersion,
      TokenEstimate.of(ujson.write(request.input)),
      TokenEstimate.of(raw),
      tokensEstimated = true
    )

object DeterministicStubModel:
  val Version = "stub-deterministic/1"

  private val classifier: Vector[(QueryOperation, String)] = Vector(
    QueryOperation.ShortestPath ->
      "\\b(link|connect|reach|separat)",
    QueryOperation.BridgeAnalysis -> "\\b(broker|bridg|gatekeeper)",
    QueryOperation.Community -> "\\b(cluster|group|circle)",
    QueryOperation.TemporalComparison ->
      "\\b(grow|shrink|evolv|earlier|later|decade)",
    QueryOperation.CoauthorLookup ->
      "\\b(collaborat|work(?:ed|s)? with|team up|partner)",
    QueryOperation.CollaborationMetrics ->
      "\\b(paper|publish|output|productiv)",
    QueryOperation.Topic -> "\\b(field|subject|area)\\b",
    QueryOperation.Institution -> "\\b(employer|school|lab|affiliat)",
    QueryOperation.ResearcherLookup -> "\\b(who is|profile|about)\\b"
  )

  private def planner(input: ujson.Value): ujson.Value =
    val question = input("untrusted")("question").str.toLowerCase
    val op = classifier.collectFirst {
      case (operation, pattern)
          if s"(?i)$pattern".r.findFirstIn(question).isDefined =>
        operation
    }
    ujson.Obj(
      "schema" -> Schemas.PlanOutputId,
      "operation" -> op.fold[ujson.Value](ujson.Null)(o => ujson.Str(o.wire)),
      "confidence" -> (if op.isDefined then 0.6 else 0.0)
    )

  private def synthesis(input: ujson.Value): ujson.Value =
    val plan = input("plan")
    val operation = Facts.s(plan, "operation").getOrElse("")
    val rankMetric = Facts.s(plan, "rankMetric")
    val wanted = Facts
      .list(plan, "metrics")
      .flatMap(m => Rendering.metricByWire(m.str))
    val evidence = Facts.list(input, "evidence")
    val claims = mutable.ArrayBuffer.empty[ujson.Value]
    var count = 0
    def add(
        kind: String,
        facts: Vector[ujson.Value],
        ids: Vector[String],
        assertion: ujson.Obj
    ): Unit =
      Rendering.render(kind, assertion, facts).foreach { text =>
        count += 1
        claims += ujson.Obj(
          "claimId" -> s"claim-$count",
          "text" -> text,
          "kind" -> kind,
          "evidenceIds" -> Facts.strs(ids),
          "assertion" -> assertion,
          "uncertainty" -> ujson.Null
        )
      }
    // The model reads the names and titles from "untrustedData" and treats
    // them as data; merging them back lets the stub write them into a claim.
    val facts = evidence
      .filter(e => Facts.b(e, "deterministic").contains(true))
      .map(e =>
        ujson.Obj(
          "evidenceId" -> e("evidenceId"),
          "facts" -> Facts.merge(e("facts"), e("untrustedData"))
        ): ujson.Value
      )
    def byKind(kind: String) =
      facts.filter(e => Facts.kind(e("facts")).contains(kind))
    def id(e: ujson.Value): Vector[String] = Vector(e("evidenceId").str)

    if operation == QueryOperation.ResearcherLookup.wire then
      byKind("identity").foreach { e =>
        val f = e("facts")
        add(
          "identity",
          Vector(f),
          id(e),
          ujson.Obj(
            "kind" -> "identity",
            "researcherId" -> f("researcherId"),
            "displayName" -> f("displayName")
          )
        )
      }
    if rankMetric.isEmpty then
      byKind("metric").foreach { e =>
        val f = e("facts")
        wanted.foreach { metric =>
          for
            field <- MetricValues.field(metric)
            value <- Facts.n(f, field)
          yield add(
            "metric",
            Vector(f),
            id(e),
            ujson.Obj(
              "kind" -> "metric",
              "researcherId" -> f("researcherId"),
              "metric" -> metric.wire,
              "value" -> value,
              "window" -> f("window")
            )
          )
        }
      }
    byKind("ranking").foreach { e =>
      val f = e("facts")
      Facts.list(f, "entries").foreach { entry =>
        add(
          "ranking",
          Vector(f),
          id(e),
          ujson.Obj(
            "kind" -> "ranking",
            "metric" -> f("metric"),
            "window" -> f("window"),
            "rank" -> entry("rank"),
            "researcherId" -> entry("researcherId"),
            "value" -> entry("value")
          )
        )
      }
    }
    byKind("coauthor").take(5).foreach { e =>
      val f = e("facts")
      add(
        "coauthor",
        Vector(f),
        id(e),
        ujson.Obj(
          "kind" -> "coauthor",
          "researcherId" -> f("researcherId"),
          "coauthorId" -> f("coauthorId"),
          "jointPublicationCount" -> f("jointPublicationCount")
        )
      )
    }
    byKind("path").foreach { e =>
      val f = e("facts")
      val base = Seq(
        "kind" -> (ujson.Str("path"): ujson.Value),
        "source" -> f("source"),
        "target" -> f("target")
      )
      add(
        "path",
        Vector(f),
        id(e),
        ujson.Obj.from(base :+ ("hopCount" -> f("hopCount")))
      )
      Facts.list(f, "hops").foreach { hop =>
        add(
          "path",
          Vector(f),
          id(e),
          ujson.Obj.from(
            base ++ Seq(
              "hopIndex" -> hop("hopIndex"),
              "from" -> hop("from"),
              "to" -> hop("to"),
              "jointPublicationCount" -> hop("jointPublicationCount")
            )
          )
        )
      }
    }
    byKind("path-status").foreach { e =>
      val f = e("facts")
      add(
        "path-status",
        Vector(f),
        id(e),
        ujson.Obj(
          "kind" -> "path-status",
          "source" -> f("source"),
          "target" -> f("target"),
          "noPathExists" -> f("noPathExists")
        )
      )
    }
    byKind("component").foreach { e =>
      val f = e("facts")
      add(
        "component",
        Vector(f),
        id(e),
        ujson.Obj(
          "kind" -> "component",
          "researcherId" -> f("researcherId"),
          "componentSize" -> f("componentSize")
        )
      )
    }
    byKind("community").foreach { e =>
      val f = e("facts")
      add(
        "community",
        Vector(f),
        id(e),
        ujson.Obj(
          "kind" -> "community",
          "researcherId" -> f("researcherId"),
          "communitySize" -> f("communitySize"),
          "crossEdges" -> f("crossEdges")
        )
      )
    }
    byKind("publication").take(3).foreach { e =>
      val f = e("facts")
      add(
        "publication",
        Vector(f),
        id(e),
        ujson.Obj(
          "kind" -> "publication",
          "workId" -> f("workId"),
          "title" -> f("title")
        )
      )
    }
    val chunks = evidence
      .filter(e => Facts.b(e, "descriptiveOnly").contains(true))
      .take(2)
      .map(e => e("evidenceId").str)
    if chunks.nonEmpty then
      count += 1
      claims += ujson.Obj(
        "claimId" -> s"claim-$count",
        "text" -> Rendering.SemanticText,
        "kind" -> "semantic",
        "evidenceIds" -> Facts.strs(chunks),
        "assertion" -> ujson.Obj("kind" -> "semantic"),
        "uncertainty" -> ujson.Null
      )
    val terms = (wanted ++ (
      if operation == QueryOperation.Community.wire ||
        operation == QueryOperation.BridgeAnalysis.wire
      then Vector(CollaborationMetric.BridgeScore)
      else Vector.empty
    )).flatMap(Glossary.termsFor).distinct
    terms.foreach { term =>
      count += 1
      claims += ujson.Obj(
        "claimId" -> s"claim-$count",
        "text" -> Glossary.terms(term),
        "kind" -> "definition",
        "evidenceIds" -> ujson.Arr(),
        "assertion" -> ujson.Obj("kind" -> "definition", "term" -> term),
        "uncertainty" -> ujson.Null
      )
    }
    val gaps = Facts.list(input("untrusted"), "knownGaps").map(_.str)
    ujson.Obj(
      "schema" -> Schemas.SynthesisOutputId,
      "analysis" -> Rendering.AnalysisText,
      "claims" -> ujson.Arr.from(claims),
      "missingEvidence" -> Facts.strs(gaps),
      "uncertainty" -> (if gaps.isEmpty then ujson.Null
                        else ujson.Str("Some requested evidence is missing."))
    )
