package scholarchain.rag

import scholarchain.core.{
  CollaborationMetric,
  ContractSchema,
  Provenance,
  ResearcherMetrics,
  SourceType
}

/** Injected time source. The workflow never reads the system clock directly, so
  * latency and the audit TTL are deterministic in tests.
  */
trait RuntimeClock:
  def nowMillis(): Long

object RuntimeClock:
  val system: RuntimeClock = new RuntimeClock:
    def nowMillis(): Long = System.currentTimeMillis()

/** A clock that advances by a fixed step on every read. */
final class FixedStepClock(start: Long = 1000L, step: Long = 5L)
    extends RuntimeClock:
  private var next = start

  def nowMillis(): Long = synchronized {
    val current = next
    next += step
    current
  }

object RagRuntime:
  val Version = "rag-runtime/1.0.0"
  val MaxQuestionChars = 2000

  /** Provenance of a record this runtime derives at query time. */
  def provenance(
      runId: String,
      recordId: String,
      uri: String,
      snapshot: String = "rag-query-time"
  ): Provenance =
    Provenance(
      ContractSchema.currentVersion,
      SourceType.Derived,
      snapshot,
      recordId,
      uri,
      Version,
      runId
    )

/** Stored metric values by the name the claims and facts use. */
object MetricValues:
  def of(m: ResearcherMetrics, metric: CollaborationMetric): Option[Double] =
    metric match
      case CollaborationMetric.PublicationCount      => Some(m.publicationCount)
      case CollaborationMetric.DistinctCoauthorCount =>
        Some(m.distinctCoauthorCount)
      case CollaborationMetric.TotalCoauthorOccurrences =>
        Some(m.totalCoauthorOccurrences)
      case CollaborationMetric.AverageCoauthorsPerPaper =>
        Some(m.averageCoauthorsPerPaper)
      case CollaborationMetric.MaximumCoauthorsOnOnePaper =>
        Some(m.maximumCoauthorsOnOnePaper)
      case CollaborationMetric.WeightedDegree     => Some(m.weightedDegree)
      case CollaborationMetric.ComponentSize      => Some(m.componentSize)
      case CollaborationMetric.BridgeScore        => m.bridgeScore
      case CollaborationMetric.Coreness           => m.coreness.map(_.toDouble)
      case CollaborationMetric.ShortestPathLength => None

  /** The key of the metric in a `metric` fact. */
  def field(metric: CollaborationMetric): Option[String] = metric match
    case CollaborationMetric.PublicationCount      => Some("publicationCount")
    case CollaborationMetric.DistinctCoauthorCount =>
      Some("distinctCoauthorCount")
    case CollaborationMetric.TotalCoauthorOccurrences =>
      Some("totalCoauthorOccurrences")
    case CollaborationMetric.AverageCoauthorsPerPaper =>
      Some("averageCoauthorsPerPaper")
    case CollaborationMetric.MaximumCoauthorsOnOnePaper =>
      Some("maximumCoauthorsOnOnePaper")
    case CollaborationMetric.WeightedDegree     => Some("weightedDegree")
    case CollaborationMetric.ComponentSize      => Some("componentSize")
    case CollaborationMetric.BridgeScore        => Some("bridgeScore")
    case CollaborationMetric.Coreness           => Some("coreness")
    case CollaborationMetric.ShortestPathLength => None

  def phrase(metric: CollaborationMetric): String = metric match
    case CollaborationMetric.PublicationCount      => "publications"
    case CollaborationMetric.DistinctCoauthorCount =>
      "distinct coauthors (collaboration breadth)"
    case CollaborationMetric.TotalCoauthorOccurrences =>
      "total coauthor occurrences"
    case CollaborationMetric.AverageCoauthorsPerPaper =>
      "coauthors per paper on average"
    case CollaborationMetric.MaximumCoauthorsOnOnePaper =>
      "coauthors on the largest team paper (team size)"
    case CollaborationMetric.WeightedDegree =>
      "weighted degree (collaboration intensity)"
    case CollaborationMetric.ComponentSize =>
      "nodes in its connected component (connectivity)"
    case CollaborationMetric.BridgeScore =>
      "bridge score (structural bridge importance)"
    case CollaborationMetric.Coreness           => "coreness"
    case CollaborationMetric.ShortestPathLength => "hops on the shortest path"

/** Number and window wording shared by the stub model and the verifier, so a
  * weakened claim is rendered exactly as an honest model would write it.
  */
object Fmt:
  def number(value: Double): String =
    if value.isWhole && value.abs < 1e15 then value.toLong.toString
    else BigDecimal(value).setScale(2, BigDecimal.RoundingMode.HALF_UP).toString

  def window(label: String): String =
    if label == "all" then "the full period" else label
