package scholarchain.hadoop.pipeline

import java.util.Locale

/** What kind of thing an identifier names.
  *
  * The scope decides how a group of claims on one identifier is judged, and
  * getting it wrong manufactures false findings:
  *
  *   - For a `Work` identifier, two claims from one source are a duplicate and
  *     two different titles are a conflict, because a DOI names one paper.
  *   - For a `Person` identifier, both are entirely normal: one ORCID
  *     legitimately appears on many papers from the same source, with a
  *     different title each time.
  */
enum IdScope:
  case Work, Person, Organization, Venue

/** The exact-identifier namespaces used for cross-source joining. */
enum IdNamespace(val wire: String, val scope: IdScope):
  case Doi extends IdNamespace("doi", IdScope.Work)
  case Arxiv extends IdNamespace("arxiv", IdScope.Work)
  case Orcid extends IdNamespace("orcid", IdScope.Person)
  case OpenAlexWork extends IdNamespace("openalex-work", IdScope.Work)
  case OpenAlexAuthor extends IdNamespace("openalex-author", IdScope.Person)
  case OpenAlexInstitution
      extends IdNamespace("openalex-institution", IdScope.Organization)
  case OpenAlexTopic extends IdNamespace("openalex-topic", IdScope.Venue)
  case SemanticScholar extends IdNamespace("semantic-scholar", IdScope.Work)
  case Ror extends IdNamespace("ror", IdScope.Organization)
  case Issn extends IdNamespace("issn", IdScope.Venue)
  case DblpKey extends IdNamespace("dblp-key", IdScope.Work)

object IdNamespace:
  private val byWire = values.iterator.map(value => value.wire -> value).toMap
  def fromWire(wire: String): Option[IdNamespace] = byWire.get(wire)

/** Normalization of the exact identifiers that carry a join. Each function
  * returns `None` for a value that does not match the registered form, so an
  * unrecognized string becomes a counted miss rather than a silent bad key.
  *
  * Normalization is intentionally conservative: it strips known resolver
  * prefixes and case-folds only where the registry defines the identifier as
  * case-insensitive. DOIs are case-insensitive by registry rule, so they are
  * lowercased; arXiv IDs and ORCID iDs are not, so their structure is checked
  * instead.
  */
object ExactIdentifiers:
  private val doiPrefixes = Vector(
    "https://doi.org/",
    "http://doi.org/",
    "https://dx.doi.org/",
    "http://dx.doi.org/",
    "doi:",
    "info:doi/"
  )
  private val doiShape = "10\\.[0-9]{4,9}/\\S+".r
  private val arxivPrefixes = Vector(
    "https://arxiv.org/abs/",
    "http://arxiv.org/abs/",
    "https://www.arxiv.org/abs/",
    "arxiv:"
  )
  // Post-2007 identifiers, and the pre-2007 archive/subject-class form.
  private val arxivModern = "([0-9]{4}\\.[0-9]{4,5})(v[0-9]+)?".r
  private val arxivLegacy =
    "([a-z-]+(?:\\.[A-Z]{2})?/[0-9]{7})(v[0-9]+)?".r
  private val doiArxivPrefix = "10.48550/arxiv."
  private val orcidShape = "([0-9]{4})-([0-9]{4})-([0-9]{4})-([0-9]{3}[0-9X])".r
  private val openAlexShape = "([WAISTCPFGK])([0-9]+)".r
  private val semanticScholarShape = "[0-9a-f]{40}".r
  private val rorShape = "0[0-9a-hj-km-np-tv-z]{6}[0-9]{2}".r
  private val issnShape = "([0-9]{4})-([0-9]{3}[0-9X])".r

  private def stripPrefix(value: String, prefixes: Vector[String]): String =
    val trimmed = TextNormalizer.collapseWhitespace(value)
    val lowered = trimmed.toLowerCase(Locale.ROOT)
    prefixes
      .find(lowered.startsWith)
      .fold(trimmed)(prefix => trimmed.drop(prefix.length))

  /** Registry rule: the DOI prefix and suffix are case-insensitive, so the
    * canonical join key is lowercase.
    */
  def doi(value: String): Option[String] =
    val candidate =
      stripPrefix(Option(value).getOrElse(""), doiPrefixes)
        .toLowerCase(Locale.ROOT)
    Option.when(doiShape.matches(candidate))(candidate)

  /** arXiv IDs reachable from a DOI live under the 10.48550 prefix. Version
    * suffixes are dropped so that v1 and v3 of one preprint join.
    */
  def arxiv(value: String): Option[String] =
    val stripped = stripPrefix(Option(value).getOrElse(""), arxivPrefixes)
    val candidate =
      if stripped.toLowerCase(Locale.ROOT).startsWith(doiArxivPrefix) then
        stripped.drop(doiArxivPrefix.length)
      else stripped
    candidate match
      case arxivModern(core, _) => Some(core)
      case arxivLegacy(core, _) => Some(core.toLowerCase(Locale.ROOT))
      case _                    => None

  /** ORCID iDs carry an ISO 7064 MOD 11-2 check digit. Verifying it here keeps
    * a transposed or truncated iD from minting a researcher identity.
    */
  def orcid(value: String): Option[String] =
    val candidate = stripPrefix(
      Option(value).getOrElse(""),
      Vector("https://orcid.org/", "http://orcid.org/", "orcid:")
    ).toUpperCase(Locale.ROOT)
    candidate match
      case orcidShape(_, _, _, _) if checkDigitValid(candidate) =>
        Some(candidate)
      case _ => None

  private def checkDigitValid(orcidId: String): Boolean =
    val digits = orcidId.filter(character => character != '-')
    val base = digits.dropRight(1)
    val expected = digits.last
    val total = base.foldLeft(0)((accumulator, character) =>
      (accumulator + (character - '0')) * 2
    )
    val remainder = (12 - total % 11) % 11
    val computed = if remainder == 10 then 'X' else ('0' + remainder).toChar
    computed == expected

  def openAlex(value: String): Option[String] =
    val candidate = stripPrefix(
      Option(value).getOrElse(""),
      Vector("https://openalex.org/", "http://openalex.org/", "openalex:")
    ).toUpperCase(Locale.ROOT)
    Option.when(openAlexShape.matches(candidate))(candidate)

  def openAlexOf(prefix: Char, value: String): Option[String] =
    openAlex(value).filter(_.charAt(0) == prefix)

  def semanticScholar(value: String): Option[String] =
    val candidate = stripPrefix(
      Option(value).getOrElse(""),
      Vector(
        "https://www.semanticscholar.org/paper/",
        "https://api.semanticscholar.org/",
        "corpusid:"
      )
    ).toLowerCase(Locale.ROOT)
    Option.when(semanticScholarShape.matches(candidate))(candidate)

  def ror(value: String): Option[String] =
    val candidate = stripPrefix(
      Option(value).getOrElse(""),
      Vector("https://ror.org/", "http://ror.org/", "ror:")
    ).toLowerCase(Locale.ROOT)
    Option.when(rorShape.matches(candidate))(candidate)

  def issn(value: String): Option[String] =
    val candidate =
      TextNormalizer.collapseWhitespace(Option(value).getOrElse(""))
    Option.when(issnShape.matches(candidate.toUpperCase(Locale.ROOT)))(
      candidate.toUpperCase(Locale.ROOT)
    )

  /** DBLP `ee` links carry DOIs and arXiv IDs. A DBLP `crossref` element is a
    * parent DBLP record key, not a Crossref DOI, so it is never read here.
    */
  def fromElectronicEdition(uri: String): Vector[(IdNamespace, String)] =
    Vector(
      doi(uri).map(IdNamespace.Doi -> _),
      arxiv(uri).map(IdNamespace.Arxiv -> _),
      semanticScholar(uri).map(IdNamespace.SemanticScholar -> _)
    ).flatten

  def normalize(
      namespace: IdNamespace,
      value: String
  ): Option[String] =
    namespace match
      case IdNamespace.Doi                 => doi(value)
      case IdNamespace.Arxiv               => arxiv(value)
      case IdNamespace.Orcid               => orcid(value)
      case IdNamespace.OpenAlexWork        => openAlexOf('W', value)
      case IdNamespace.OpenAlexAuthor      => openAlexOf('A', value)
      case IdNamespace.OpenAlexInstitution => openAlexOf('I', value)
      case IdNamespace.OpenAlexTopic       => openAlexOf('T', value)
      case IdNamespace.SemanticScholar     => semanticScholar(value)
      case IdNamespace.Ror                 => ror(value)
      case IdNamespace.Issn                => issn(value)
      case IdNamespace.DblpKey             =>
        val candidate =
          TextNormalizer.collapseWhitespace(Option(value).getOrElse(""))
        Option.when(candidate.matches("[A-Za-z0-9/._+-]+"))(candidate)

  /** The reduce-side grouping key for an exact identifier. */
  def joinKey(namespace: IdNamespace, canonicalValue: String): String =
    s"${namespace.wire}|$canonicalValue"

  def parseJoinKey(key: String): Option[(IdNamespace, String)] =
    key.split("\\|", 2) match
      case Array(wire, value) =>
        IdNamespace.fromWire(wire).map(namespace => namespace -> value)
      case _ => None
