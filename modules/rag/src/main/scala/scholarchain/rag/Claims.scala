package scholarchain.rag

import scholarchain.core.CollaborationMetric
import scholarchain.storage.Hashing

/** A claim as the synthesis stage proposes it. `assertion` is the structured
  * statement the verifier checks against the cited facts; `text` is the prose.
  * `kind` is one of `Schemas.ClaimKinds`.
  */
final case class ClaimDraft(
    claimId: String,
    text: String,
    kind: String,
    evidenceIds: Vector[String],
    assertion: ujson.Obj,
    uncertainty: Option[String]
)

object ClaimDraft:
  /** The claim ID is assigned here and never read from the model. It is the
    * 1-based position, the kind and a digest of the kind, the cited evidence
    * IDs and the structured assertion, so it is unique within an answer (the
    * position differs), identical across identical replays, and unchanged by
    * the model's `claimId`, `text` or `uncertainty`. A `claimId` the model
    * still sends is ignored.
    */
  def systemId(
      position: Int,
      kind: String,
      evidenceIds: Vector[String],
      assertion: ujson.Value
  ): String =
    val digest = Hashing.sha256(
      kind + "\n" + evidenceIds.mkString(",") + "\n" + ujson.write(assertion)
    )
    s"claim-$position-$kind-${digest.take(12)}"

  def fromJson(value: ujson.Value, position: Int): ClaimDraft =
    val kind = Facts.s(value, "kind").get
    val evidenceIds = Facts.list(value, "evidenceIds").map(_.str)
    val assertion = ujson.Obj.from(value("assertion").obj.toSeq)
    ClaimDraft(
      systemId(position, kind, evidenceIds, assertion),
      Facts.s(value, "text").get,
      kind,
      evidenceIds,
      assertion,
      Facts.s(value, "uncertainty")
    )

/** Fixed definitions of the ideas the answer must keep apart. A `definition`
  * claim must carry exactly this text, so a model cannot slip a statement about
  * data into one. No definition holds a digit.
  */
object Glossary:
  val terms: Map[String, String] = Map(
    "breadth" ->
      "Collaboration breadth is the number of distinct coauthors a researcher has (C_r). It counts different people and ignores how often they work together.",
    "intensity" ->
      "Collaboration intensity is the weighted degree (W_r). It grows with repeated joint publications, so it separates a few close ties from many one-off ones.",
    "team-size" ->
      "Team size is the largest number of coauthors on a single paper (M_r). It describes one paper, not how many people a researcher works with.",
    "connectivity" ->
      "Connectivity is the size of the connected component a researcher belongs to. It says how many people can be reached through chains of coauthorship.",
    "bridge" ->
      "Bridge importance is a stored structural score for sitting between communities. It is a different measure from direct degree: a researcher can have many direct coauthors and a low bridge score, or few coauthors and a high one."
  )

  def text(term: String): Option[String] = terms.get(term)

  /** The terms an answer explains for the metrics it reports. */
  def termsFor(metric: CollaborationMetric): Option[String] = metric match
    case CollaborationMetric.DistinctCoauthorCount      => Some("breadth")
    case CollaborationMetric.WeightedDegree             => Some("intensity")
    case CollaborationMetric.MaximumCoauthorsOnOnePaper => Some("team-size")
    case CollaborationMetric.ComponentSize              => Some("connectivity")
    case CollaborationMetric.BridgeScore                => Some("bridge")
    case _                                              => None

/** Prose for a claim, written from the assertion and the cited facts. The stub
  * model writes claims with it and the verifier rewrites a partly supported
  * claim with it, so a weakened claim keeps only what the facts support.
  */
object Rendering:
  import Facts.{b, list, n, s}

  private def name(value: ujson.Value, key: String): String =
    s(value, key).filter(_.trim.nonEmpty).getOrElse("an unnamed researcher")

  private def window(fact: ujson.Value): String =
    Fmt.window(s(fact, "window").getOrElse("an unstated window"))

  private def num(fact: ujson.Value, key: String): String =
    n(fact, key).fold("an unrecorded number")(Fmt.number)

  def metricByWire(wire: String): Option[CollaborationMetric] =
    CollaborationMetric.values.find(_.wire == wire)

  def render(
      kind: String,
      assertion: ujson.Value,
      facts: Vector[ujson.Value]
  ): Option[String] =
    facts.headOption.flatMap { fact =>
      kind match
        case "metric" =>
          for
            metric <- s(assertion, "metric").flatMap(metricByWire)
            field <- MetricValues.field(metric)
            value <- n(fact, field)
          yield s"In window ${window(fact)}, ${name(fact, "displayName")} has " +
            s"${Fmt.number(value)} ${MetricValues.phrase(metric)}."
        case "ranking" =>
          for
            metric <- s(fact, "metric").flatMap(metricByWire)
            rank <- n(assertion, "rank")
            entry <- list(fact, "entries").find(e =>
              n(e, "rank").contains(rank) &&
                s(assertion, "researcherId").forall(id =>
                  s(e, "researcherId").contains(id)
                )
            )
          yield s"Rank ${Fmt.number(rank)} by ${MetricValues.phrase(metric)} " +
            s"in window ${window(fact)}: ${name(entry, "displayName")} with " +
            s"${num(entry, "value")}."
        case "coauthor" =>
          Some(
            s"${name(fact, "researcherName")} and ${name(fact, "coauthorName")} " +
              s"co-authored ${num(fact, "jointPublicationCount")} publication(s) " +
              s"between ${num(fact, "firstCollaborationYear")} and " +
              s"${num(fact, "lastCollaborationYear")} (window ${window(fact)})."
          )
        case "path" =>
          val hops = list(fact, "hops")
          n(assertion, "hopIndex") match
            case Some(index) =>
              hops.find(h => n(h, "hopIndex").contains(index)).map { hop =>
                s"Hop ${Fmt.number(index)}: ${name(hop, "fromName")} and " +
                  s"${name(hop, "toName")} share " +
                  s"${num(hop, "jointPublicationCount")} joint publication(s) " +
                  s"(${num(hop, "firstCollaborationYear")} to " +
                  s"${num(hop, "lastCollaborationYear")})."
              }
            case None =>
              val chain =
                hops.headOption.map(h => name(h, "fromName")).toVector ++
                  hops.map(h => name(h, "toName"))
              Some(
                s"The shortest stored path from ${name(fact, "sourceName")} to " +
                  s"${name(fact, "targetName")} in window ${window(fact)} has " +
                  s"${num(fact, "hopCount")} hop(s): ${chain.mkString(" -> ")}."
              )
        case "path-status" =>
          val a = name(fact, "sourceName")
          val c = name(fact, "targetName")
          if b(fact, "noPathExists").contains(true) then
            Some(
              s"No collaboration path connects $a and $c in window " +
                s"${window(fact)}: they are in different connected components."
            )
          else
            Some(
              s"The stored evidence does not establish whether a path connects " +
                s"$a and $c in window ${window(fact)}: " +
                s"${s(fact, "reason").getOrElse("no reason recorded")}."
            )
        case "component" =>
          Some(
            s"${name(fact, "displayName")} belongs to a connected component of " +
              s"${num(fact, "componentSize")} node(s) in window ${window(fact)}."
          )
        case "community" =>
          Some(
            s"${name(fact, "displayName")} belongs to a community of " +
              s"${num(fact, "communitySize")} node(s) in window ${window(fact)}; " +
              s"it has ${num(fact, "internalEdges")} internal edge(s) and " +
              s"${num(fact, "crossEdges")} edge(s) to other communities."
          )
        case "publication" =>
          val year = n(fact, "year").fold("")(y => s" in ${Fmt.number(y)}")
          s(fact, "title").map(t =>
            s"The work \"$t\" is a stored work record$year."
          )
        case "identity" =>
          Some(
            s"The name in the question was matched to the researcher record " +
              s"${name(fact, "displayName")}."
          )
        case _ => None
    }

  /** The one analysis paragraph an answer shows. It is fixed system text: a
    * model's own paragraph is never shown, so it cannot carry a claim.
    */
  val AnalysisText: String =
    "This analysis uses stored graph and metric records only. Semantic context is descriptive and is never used for a number."

  /** The wording of every abstention claim. */
  val AbstentionText: String =
    "Part of this question cannot be answered from the stored evidence."

  val AbstentionUncertainty: String =
    "The stored evidence does not establish the missing part."

  val SemanticText: String =
    "Descriptive semantic context was retrieved for this question. It is descriptive only and is not a metric, a coauthorship or a path."
