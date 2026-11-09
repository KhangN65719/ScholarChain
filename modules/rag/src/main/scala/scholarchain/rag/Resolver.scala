package scholarchain.rag

import scala.util.control.NonFatal
import scholarchain.core.YearWindow
import scholarchain.storage.{
  CoauthorContext,
  CoauthorLink,
  Names,
  NameMatch,
  NeptuneQueryPort,
  ResearcherCandidate,
  ResearcherDirectoryPort,
  StorageException,
  WindowLabels
}

final case class ResolverConfig(
    candidateLimit: Int = 10,
    contextLimit: Int = 5,
    minConfidence: Double = 0.8
)

/** One candidate with the evidence a person would use to tell them apart:
  * ORCID, link status, record counts and the strongest coauthors in the window.
  * Affiliation is not stored (`affiliationAvailable` is false in storage), so
  * none is invented.
  */
final case class ResolvedCandidate(
    candidate: ResearcherCandidate,
    confidence: Double,
    coauthorContext: Vector[CoauthorLink],
    contextWindow: String
):
  def nodeId: String = candidate.nodeId
  def displayName: String = candidate.displayName

enum ResolutionStatus:
  /** One canonical record, or one chosen by the user from the candidates. */
  case Resolved

  /** More than one record matches; none is chosen. */
  case Ambiguous

  case NotFound

  /** The only match is an unresolved author mention, which is not an
    * established researcher.
    */
  case MentionOnly

  /** One match, but the confidence is under the threshold (for example the
    * record shares its ORCID with another node).
    */
  case LowConfidence

  case Invalid

final case class Resolution(
    name: String,
    status: ResolutionStatus,
    chosen: Option[ResolvedCandidate],
    candidates: Vector[ResolvedCandidate],
    selectedByUser: Boolean,
    note: String
):
  def resolved: Boolean =
    status == ResolutionStatus.Resolved && chosen.isDefined

/** Names to canonical researcher IDs through the storage directory port. The
  * agent returns every candidate and chooses one only when exactly one record
  * matches with enough confidence, or when the user names one of the candidates
  * (`Session.disambiguation`). It never picks among near-equal candidates.
  */
final class ResearcherResolverAgent(
    directory: ResearcherDirectoryPort,
    neptune: NeptuneQueryPort,
    config: ResolverConfig = ResolverConfig()
):
  import ResolutionStatus.*

  private val UserConfirmed = 0.9

  private def confidence(c: ResearcherCandidate): Double =
    val base = c.matchKind match
      case NameMatch.Exact    => 0.95
      case NameMatch.TokenSet => 0.85
    if c.isMention then 0.4
    else if c.sharesOrcidWith > 0 then base - 0.3
    else base

  private def withContext(
      c: ResearcherCandidate,
      window: YearWindow,
      score: Double
  ): ResolvedCandidate =
    val label = WindowLabels.of(window)
    val context =
      try CoauthorContext.of(neptune, c.nodeId, label, config.contextLimit)
      catch case NonFatal(_) => Vector.empty
    ResolvedCandidate(c, score, context, label)

  /** A researcher ID given in the question, checked against the directory. */
  def resolveId(id: String, window: YearWindow): Resolution =
    directory.byId(id) match
      case Some(c) if !c.isMention =>
        val r = withContext(c, window, 0.99)
        Resolution(id, Resolved, Some(r), Vector(r), false, "identifier given")
      case Some(c) =>
        val r = withContext(c, window, confidence(c))
        Resolution(
          id,
          MentionOnly,
          None,
          Vector(r),
          false,
          "the identifier is an unresolved author mention"
        )
      case None =>
        Resolution(
          id,
          NotFound,
          None,
          Vector.empty,
          false,
          "unknown identifier"
        )

  def resolveName(
      name: String,
      window: YearWindow,
      userChoice: Option[String] = None
  ): Resolution =
    val found =
      try Right(directory.candidates(name, config.candidateLimit))
      catch case e: StorageException => Left(e)
    found match
      case Left(_) =>
        Resolution(
          name,
          Invalid,
          None,
          Vector.empty,
          false,
          "the name is unusable"
        )
      case Right(result) if result.candidates.isEmpty =>
        Resolution(
          name,
          NotFound,
          None,
          Vector.empty,
          false,
          "no stored researcher has this name"
        )
      case Right(result) =>
        val all =
          result.candidates.map(c => withContext(c, window, confidence(c)))
        val truncated =
          if result.truncated then
            s"; ${result.total} records match and only ${all.size} are listed"
          else ""
        val picked = userChoice.flatMap(id =>
          all.find(r => r.nodeId == id && !r.candidate.isMention)
        )
        picked match
          case Some(r) =>
            val chosen = r.copy(confidence = UserConfirmed)
            Resolution(
              name,
              Resolved,
              Some(chosen),
              all,
              true,
              "chosen by the user from the candidates"
            )
          case None if all.size > 1 =>
            Resolution(
              name,
              Ambiguous,
              None,
              all,
              false,
              s"${result.total} stored records match this name$truncated"
            )
          case None =>
            val only = all.head
            if only.candidate.isMention then
              Resolution(
                name,
                MentionOnly,
                None,
                all,
                false,
                "the only match is an unresolved author mention"
              )
            else if only.confidence < config.minConfidence then
              Resolution(
                name,
                LowConfidence,
                None,
                all,
                false,
                "the record shares its ORCID with another node, so it may be split"
              )
            else
              Resolution(
                name,
                Resolved,
                Some(only),
                all,
                false,
                "one canonical record matches"
              )

object ResearcherResolverAgent:
  def key(name: String): String = Names.normalize(name)
