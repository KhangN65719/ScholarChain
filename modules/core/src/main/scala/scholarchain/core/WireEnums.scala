package scholarchain.core

import upickle.default.{ReadWriter, readwriter}

private object EnumWire:
  def codec[A](values: Array[A], wire: A => String): ReadWriter[A] =
    val lookup = values.iterator.map(value => wire(value) -> value).toMap
    require(lookup.size == values.length, "Enum wire values must be unique")
    readwriter[String].bimap[A](
      wire,
      key =>
        lookup.getOrElse(
          key,
          throw new IllegalArgumentException(s"Unsupported enum value: $key")
        )
    )

enum SourceType(val wire: String):
  case Dblp extends SourceType("dblp")
  case OpenAlex extends SourceType("openalex")
  case Orcid extends SourceType("orcid")
  case Crossref extends SourceType("crossref")
  case Arxiv extends SourceType("arxiv")
  case SemanticScholar extends SourceType("semantic-scholar")
  case Derived extends SourceType("derived")

object SourceType:
  given ReadWriter[SourceType] = EnumWire.codec(values, _.wire)

enum MatchOutcome(val wire: String):
  case Accepted extends MatchOutcome("ACCEPTED")
  case Rejected extends MatchOutcome("REJECTED")
  case Unresolved extends MatchOutcome("UNRESOLVED")
  case Conflict extends MatchOutcome("CONFLICT")

object MatchOutcome:
  given ReadWriter[MatchOutcome] = EnumWire.codec(values, _.wire)

enum EvidenceType(val wire: String):
  case Publication extends EvidenceType("publication")
  case GraphPath extends EvidenceType("graph-path")
  case Metric extends EvidenceType("metric")
  case TextChunk extends EvidenceType("text-chunk")
  case IdentityLink extends EvidenceType("identity-link")

object EvidenceType:
  given ReadWriter[EvidenceType] = EnumWire.codec(values, _.wire)

enum CollaborationMetric(val wire: String):
  case PublicationCount extends CollaborationMetric("publication-count")
  case DistinctCoauthorCount
      extends CollaborationMetric("distinct-coauthor-count")
  case TotalCoauthorOccurrences
      extends CollaborationMetric("total-coauthor-occurrences")
  case AverageCoauthorsPerPaper
      extends CollaborationMetric("average-coauthors-per-paper")
  case MaximumCoauthorsOnOnePaper
      extends CollaborationMetric("maximum-coauthors-on-one-paper")
  case WeightedDegree extends CollaborationMetric("weighted-degree")
  case ComponentSize extends CollaborationMetric("component-size")
  case ShortestPathLength extends CollaborationMetric("shortest-path-length")
  case BridgeScore extends CollaborationMetric("bridge-score")
  case Coreness extends CollaborationMetric("coreness")

object CollaborationMetric:
  given ReadWriter[CollaborationMetric] = EnumWire.codec(values, _.wire)

enum QueryOperation(val wire: String):
  case ResearcherLookup extends QueryOperation("researcher-lookup")
  case CollaborationMetrics extends QueryOperation("collaboration-metrics")
  case CoauthorLookup extends QueryOperation("coauthor-lookup")
  case ShortestPath extends QueryOperation("shortest-path")
  case Component extends QueryOperation("component")
  case Community extends QueryOperation("community")
  case BridgeAnalysis extends QueryOperation("bridge-analysis")
  case TemporalComparison extends QueryOperation("temporal-comparison")
  case Topic extends QueryOperation("topic")
  case Institution extends QueryOperation("institution")
  case EvidenceLookup extends QueryOperation("evidence-lookup")

object QueryOperation:
  given ReadWriter[QueryOperation] = EnumWire.codec(values, _.wire)

enum VerificationStatus(val wire: String):
  case Verified extends VerificationStatus("verified")
  case Unsupported extends VerificationStatus("unsupported")
  case Uncertain extends VerificationStatus("uncertain")

object VerificationStatus:
  given ReadWriter[VerificationStatus] = EnumWire.codec(values, _.wire)

enum GraphNodeType(val wire: String):
  case Researcher extends GraphNodeType("Researcher")
  case AuthorMention extends GraphNodeType("AuthorMention")
  case Work extends GraphNodeType("Work")
  case Venue extends GraphNodeType("Venue")
  case Institution extends GraphNodeType("Institution")
  case Topic extends GraphNodeType("Topic")
  case TextChunk extends GraphNodeType("TextChunk")
  case DatasetSnapshot extends GraphNodeType("DatasetSnapshot")

object GraphNodeType:
  given ReadWriter[GraphNodeType] = EnumWire.codec(values, _.wire)

enum GraphEdgeType(val wire: String):
  case Authored extends GraphEdgeType("AUTHORED")
  case ResolvesTo extends GraphEdgeType("RESOLVES_TO")
  case CoauthoredWith extends GraphEdgeType("COAUTHORED_WITH")
  case PublishedIn extends GraphEdgeType("PUBLISHED_IN")
  case AffiliatedWith extends GraphEdgeType("AFFILIATED_WITH")
  case HasTopic extends GraphEdgeType("HAS_TOPIC")
  case DerivedFrom extends GraphEdgeType("DERIVED_FROM")
  case SupportedBy extends GraphEdgeType("SUPPORTED_BY")

object GraphEdgeType:
  given ReadWriter[GraphEdgeType] = EnumWire.codec(values, _.wire)
