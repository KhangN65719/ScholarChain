package scholarchain.er.matching

import java.util.Locale
import upickle.default.{ReadWriter, readwriter}

enum EntityKind(val wire: String):
  case Researcher extends EntityKind("researcher")
  case Work extends EntityKind("work")

object EntityKind:
  given ReadWriter[EntityKind] = readwriter[String].bimap[EntityKind](
    _.wire,
    wire =>
      values
        .find(_.wire == wire)
        .getOrElse(
          throw new IllegalArgumentException(s"Unknown entity kind: $wire")
        )
  )

/** `Strong` identifiers are issued by a registry that checks them (DOI, arXiv,
  * ORCID). `Corroborated` identifiers are cluster assignments made by another
  * aggregator, so an agreement on one is accepted only when the record content
  * does not contradict it.
  */
enum IdTier:
  case Strong, Corroborated

enum IdNamespace(val wire: String, val kind: EntityKind, val tier: IdTier):
  case Orcid extends IdNamespace("orcid", EntityKind.Researcher, IdTier.Strong)
  case OpenAlexAuthor
      extends IdNamespace(
        "openalex-author",
        EntityKind.Researcher,
        IdTier.Corroborated
      )
  case SemanticScholarAuthor
      extends IdNamespace(
        "semantic-scholar-author",
        EntityKind.Researcher,
        IdTier.Corroborated
      )
  case DblpAuthor
      extends IdNamespace(
        "dblp-author",
        EntityKind.Researcher,
        IdTier.Corroborated
      )
  case Doi extends IdNamespace("doi", EntityKind.Work, IdTier.Strong)
  case Arxiv extends IdNamespace("arxiv", EntityKind.Work, IdTier.Strong)
  case OpenAlexWork
      extends IdNamespace("openalex-work", EntityKind.Work, IdTier.Corroborated)
  case SemanticScholarPaper
      extends IdNamespace(
        "semantic-scholar",
        EntityKind.Work,
        IdTier.Corroborated
      )
  case RepositoryUrl
      extends IdNamespace(
        "repository-url",
        EntityKind.Work,
        IdTier.Corroborated
      )

/** The identifiers a record carries, normalized. A namespace holds a set
  * because one record can legitimately list several DOIs or links.
  */
final case class IdSet(
    values: Map[IdNamespace, Set[String]],
    invalid: Vector[String]
)

final case class IdComparison(
    agreements: Vector[IdNamespace],
    conflicts: Vector[IdNamespace]
):
  def strongAgreement: Boolean = agreements.exists(_.tier == IdTier.Strong)

  /** Namespaces whose disagreement is a CONFLICT and whose agreement is an
    * identifier decision: every work identifier and the ORCID iD. Researcher
    * identifiers issued by aggregators (OpenAlex, Semantic Scholar, DBLP) are
    * cluster assignments that are often wrong, so they are scored as features.
    */
  def authoritative: IdComparison =
    IdComparison(
      agreements.filter(IdComparison.isAuthoritative),
      conflicts.filter(IdComparison.isAuthoritative)
    )

  def aggregator: IdComparison =
    IdComparison(
      agreements.filterNot(IdComparison.isAuthoritative),
      conflicts.filterNot(IdComparison.isAuthoritative)
    )

object IdComparison:
  val empty: IdComparison = IdComparison(Vector.empty, Vector.empty)

  def isAuthoritative(namespace: IdNamespace): Boolean =
    namespace.kind == EntityKind.Work || namespace.tier == IdTier.Strong

object Identifiers:
  private val doiPrefixes = Vector(
    "https://doi.org/",
    "http://doi.org/",
    "https://dx.doi.org/",
    "http://dx.doi.org/",
    "doi.org/",
    "doi:",
    "info:doi/"
  )
  private val doiShape = "10\\.[0-9]{4,9}/\\S+".r
  private val arxivDoiPrefix = "10.48550/arxiv."
  private val arxivPrefixes = Vector(
    "https://arxiv.org/abs/",
    "http://arxiv.org/abs/",
    "https://www.arxiv.org/abs/",
    "https://arxiv.org/pdf/",
    "arxiv.org/abs/",
    "arxiv:"
  )
  private val arxivModern = "([0-9]{4}\\.[0-9]{4,5})(v[0-9]+)?".r
  private val arxivLegacy = "([a-z-]+(?:\\.[a-z]{2})?/[0-9]{7})(v[0-9]+)?".r
  private val orcidShape = "[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]".r
  private val openAlexShape = "([WA])([0-9]+)".r
  private val semanticScholarPaper = "[0-9a-f]{40}".r
  private val semanticScholarAuthor = "[0-9]{1,15}".r
  private val repository =
    "(?:https?://)?(?:www\\.)?(github\\.com|gitlab\\.com|bitbucket\\.org)/([^/\\s#?]+)/([^/\\s#?]+)(?:[/#?].*)?".r

  private def stripPrefix(value: String, prefixes: Vector[String]): String =
    val trimmed = Option(value).getOrElse("").trim
    val lowered = trimmed.toLowerCase(Locale.ROOT)
    prefixes
      .find(lowered.startsWith)
      .fold(trimmed)(prefix => trimmed.drop(prefix.length))

  /** DOI names are case insensitive by registry rule, so the key is lowercase.
    */
  def doi(value: String): Option[String] =
    val candidate =
      stripPrefix(value, doiPrefixes).toLowerCase(Locale.ROOT)
    Option.when(doiShape.matches(candidate))(candidate)

  /** A DOI that names a work in its own right. A DOI under the arXiv prefix
    * names the preprint, which `collect` stores as an arXiv identifier, so it
    * is not a shared-DOI signal here either.
    */
  def workDoi(value: String): Option[String] =
    doi(value).filterNot(_.startsWith(arxivDoiPrefix))

  /** The arXiv ID a work is known by, as one key: the plain `arxivId` field, or
    * else the arXiv-prefixed DOI form (`10.48550/arxiv.NNNN`, any DOI URL
    * prefix, any case). Both forms reduce to the bare ID without version
    * suffix, so `2101.00001v2`, `arXiv:2101.00001` and
    * `https://doi.org/10.48550/arXiv.2101.00001` meet. A value that is not an
    * arXiv ID yields None.
    */
  def workArxiv(
      arxivId: Option[String],
      doiValue: Option[String]
  ): Option[String] =
    arxivId
      .flatMap(arxiv)
      .orElse(
        doiValue
          .flatMap(doi)
          .filter(_.startsWith(arxivDoiPrefix))
          .flatMap(arxiv)
      )

  def arxiv(value: String): Option[String] =
    val stripped = stripPrefix(value, arxivPrefixes)
    val candidate =
      if stripped.toLowerCase(Locale.ROOT).startsWith(arxivDoiPrefix) then
        stripped.drop(arxivDoiPrefix.length)
      else stripped
    candidate.toLowerCase(Locale.ROOT).stripSuffix(".pdf") match
      case arxivModern(core, _) => Some(core)
      case arxivLegacy(core, _) => Some(core)
      case _                    => None

  /** An ORCID iD is accepted only with a valid ISO 7064 MOD 11-2 check digit,
    * so a transposed or truncated iD never becomes an identity.
    */
  def orcid(value: String): Option[String] =
    val candidate = stripPrefix(
      value,
      Vector("https://orcid.org/", "http://orcid.org/", "orcid.org/", "orcid:")
    ).toUpperCase(Locale.ROOT)
    Option.when(orcidShape.matches(candidate) && checkDigitValid(candidate))(
      candidate
    )

  private def checkDigitValid(orcidId: String): Boolean =
    val digits = orcidId.filter(_ != '-')
    val total = digits
      .dropRight(1)
      .foldLeft(0)((sum, char) => (sum + (char - '0')) * 2)
    val remainder = (12 - total % 11) % 11
    val expected = if remainder == 10 then 'X' else ('0' + remainder).toChar
    expected == digits.last

  def openAlex(prefix: Char, value: String): Option[String] =
    val candidate = stripPrefix(
      value,
      Vector("https://openalex.org/", "http://openalex.org/", "openalex:")
    ).toUpperCase(Locale.ROOT)
    candidate match
      case openAlexShape(letter, _) if letter.head == prefix => Some(candidate)
      case _                                                 => None

  def semanticScholarPaperId(value: String): Option[String] =
    val candidate = stripPrefix(
      value,
      Vector(
        "https://www.semanticscholar.org/paper/",
        "https://api.semanticscholar.org/",
        "corpusid:"
      )
    ).toLowerCase(Locale.ROOT)
    Option.when(semanticScholarPaper.matches(candidate))(candidate)

  def semanticScholarAuthorId(value: String): Option[String] =
    val candidate = stripPrefix(
      value,
      Vector(
        "https://www.semanticscholar.org/author/",
        "semanticscholar:"
      )
    ).split("/").last
    Option.when(semanticScholarAuthor.matches(candidate))(candidate)

  /** Host plus owner plus repository, lowercase, without `.git` or a path. */
  def repositoryUrl(value: String): Option[String] =
    Option(value).getOrElse("").trim.toLowerCase(Locale.ROOT) match
      case repository(host, owner, name) =>
        val repo = name.stripSuffix(".git")
        Option.when(repo.nonEmpty)(s"$host/$owner/$repo")
      case _ => None

  /** Maps a source key to a namespace of the given kind. Keys outside the
    * registry, such as a source-local record key, return None: they are not
    * authoritative and must not produce a conflict.
    */
  def namespaceFor(kind: EntityKind, key: String): Option[IdNamespace] =
    val lowered = Option(key).getOrElse("").trim.toLowerCase(Locale.ROOT)
    (kind, lowered) match
      case (EntityKind.Researcher, "orcid") => Some(IdNamespace.Orcid)
      case (EntityKind.Researcher, "openalex" | "openalex-author") =>
        Some(IdNamespace.OpenAlexAuthor)
      case (EntityKind.Researcher, "semantic-scholar-author") =>
        Some(IdNamespace.SemanticScholarAuthor)
      case (EntityKind.Researcher, "dblp-author" | "dblp-pid") =>
        Some(IdNamespace.DblpAuthor)
      case (EntityKind.Work, "doi")      => Some(IdNamespace.Doi)
      case (EntityKind.Work, "arxiv")    => Some(IdNamespace.Arxiv)
      case (EntityKind.Work, "arxiv-id") => Some(IdNamespace.Arxiv)
      case (EntityKind.Work, "openalex" | "openalex-work") =>
        Some(IdNamespace.OpenAlexWork)
      case (EntityKind.Work, "semantic-scholar") =>
        Some(IdNamespace.SemanticScholarPaper)
      case (EntityKind.Work, "repository-url" | "repository") =>
        Some(IdNamespace.RepositoryUrl)
      case _ => None

  def normalize(namespace: IdNamespace, value: String): Option[String] =
    namespace match
      case IdNamespace.Orcid                 => orcid(value)
      case IdNamespace.OpenAlexAuthor        => openAlex('A', value)
      case IdNamespace.SemanticScholarAuthor => semanticScholarAuthorId(value)
      case IdNamespace.DblpAuthor            =>
        Option(value).map(_.trim).filter(_.matches("[A-Za-z0-9/._+-]+"))
      case IdNamespace.Doi                  => doi(value)
      case IdNamespace.Arxiv                => arxiv(value)
      case IdNamespace.OpenAlexWork         => openAlex('W', value)
      case IdNamespace.SemanticScholarPaper => semanticScholarPaperId(value)
      case IdNamespace.RepositoryUrl        => repositoryUrl(value)

  /** Collects normalized identifiers from keyed values and, for works, from
    * links. A DOI under the arXiv prefix names the preprint, so it is stored as
    * an arXiv identifier and not as a DOI: otherwise a preprint and its
    * published version would disagree on DOI while naming the same work. Values
    * that fail validation are listed in `invalid`, not silently used.
    */
  def collect(
      kind: EntityKind,
      keyed: Map[String, String],
      links: Vector[String]
  ): IdSet =
    val fromKeys = keyed.toVector.sortBy(_._1).map { (key, value) =>
      namespaceFor(kind, key) -> (key, value)
    }
    val invalid = Vector.newBuilder[String]
    val found = scala.collection.mutable.Map
      .empty[IdNamespace, Set[String]]
      .withDefaultValue(Set.empty)
    def add(namespace: IdNamespace, normalized: String): Unit =
      found(namespace) = found(namespace) + normalized
    def addDoi(raw: String, normalized: String): Unit =
      if normalized.startsWith(arxivDoiPrefix) then
        arxiv(normalized).foreach(add(IdNamespace.Arxiv, _))
      else add(IdNamespace.Doi, normalized)
    fromKeys.foreach {
      case (Some(namespace), (key, value)) =>
        normalize(namespace, value) match
          case Some(normalized) if namespace == IdNamespace.Doi =>
            addDoi(value, normalized)
          case Some(normalized) => add(namespace, normalized)
          case None             => invalid += s"${namespace.wire}:$value"
      case _ => ()
    }
    if kind == EntityKind.Work then
      links.foreach { link =>
        doi(link).foreach(addDoi(link, _))
        arxiv(link).foreach(add(IdNamespace.Arxiv, _))
        semanticScholarPaperId(link).foreach(
          add(IdNamespace.SemanticScholarPaper, _)
        )
        repositoryUrl(link).foreach(add(IdNamespace.RepositoryUrl, _))
      }
    IdSet(found.toMap, invalid.result())

  /** Only namespaces both sides populate are compared. Overlapping sets agree;
    * disjoint sets conflict. A namespace one side lacks is silence, not
    * disagreement.
    */
  def compare(left: IdSet, right: IdSet): IdComparison =
    val shared = left.values.keySet
      .intersect(right.values.keySet)
      .toVector
      .sortBy(_.wire)
    val (agree, conflict) = shared.partition(namespace =>
      left.values(namespace).intersect(right.values(namespace)).nonEmpty
    )
    IdComparison(agree, conflict)
