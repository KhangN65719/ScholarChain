package scholarchain.rag

import scholarchain.core.{CollaborationMetric, Provenance, YearWindow}
import scholarchain.storage.{
  CachedMetrics,
  CypherCatalog,
  CypherQuery,
  ErrorCode,
  Fail,
  NeptuneQueryPort,
  ReadLimits,
  Row,
  Rows
}

/** One ranked researcher. Equal values share a rank. */
final case class RankedEntry(rank: Int, cached: CachedMetrics, value: Double)

/** The top of one metric in one window. `mentionsExcluded` counts unresolved
  * mentions left out, because a mention is not an established researcher.
  * `tieAtCutoff` is true when the entry after the last one returned has the
  * same value, so the cut would otherwise hide a tie.
  */
final case class Ranking(
    window: YearWindow,
    metric: CollaborationMetric,
    entries: Vector[RankedEntry],
    rankedTotal: Int,
    mentionsExcluded: Int,
    tieAtCutoff: Boolean
)

/** Ranking read port. Storage has no ranked access pattern yet, so the port is
  * defined here and backed by a local fake; a ranked index in the metric cache
  * is a Phase 12 storage item. Ranking by one named metric is the default and
  * there is no composite score.
  */
trait MetricRanking:
  def top(
      window: YearWindow,
      metric: CollaborationMetric,
      limit: Int
  ): Ranking

object MetricRanking:
  val MaxLimit = 20

final class InMemoryMetricRanking(all: Vector[CachedMetrics])
    extends MetricRanking:
  def top(
      window: YearWindow,
      metric: CollaborationMetric,
      limit: Int
  ): Ranking =
    ReadLimits.bounded(limit, MetricRanking.MaxLimit, "limit")
    Fail.unless(
      MetricValues.field(metric).isDefined,
      ErrorCode.InvalidQuery,
      s"${metric.wire} cannot be ranked from stored metrics"
    )
    val pool = all.filter(_.metrics.window == window)
    val (mentions, canonical) =
      pool.partition(_.metrics.researcherId.startsWith("mention:"))
    val scored = canonical
      .flatMap(c => MetricValues.of(c.metrics, metric).map(v => (c, v)))
      .sortBy { case (c, v) => (-v, c.metrics.researcherId) }
    val entries = scored.take(limit).map { case (c, v) =>
      RankedEntry(1 + scored.count(_._2 > v), c, v)
    }
    val tie = scored.size > limit && scored(limit)._2 == scored(limit - 1)._2
    Ranking(window, metric, entries, scored.size, mentions.size, tie)

/** A work record, from the Spark `nodes-work` table. */
final case class WorkInfo(
    workId: String,
    title: String,
    year: Option[Int],
    source: Option[String],
    provenance: Provenance
)

/** Work titles, the deterministic support of a publication claim. */
trait WorkCatalog:
  def work(workId: String): Option[WorkInfo]

final class TableWorkCatalog(rows: Vector[Row]) extends WorkCatalog:
  private val byId: Map[String, WorkInfo] = rows.map { row =>
    val info = WorkInfo(
      Rows.str(row, "canonicalWorkId"),
      Rows.str(row, "title"),
      Rows.optLong(row, "year").map(_.toInt),
      Rows.optStr(row, "source"),
      Rows.provenance(row)
    )
    info.workId -> info
  }.toMap

  def work(workId: String): Option[WorkInfo] = byId.get(workId)

/** The runtime runs the catalog templates only. This wrapper refuses a query
  * unless its name is on the allowlist and its text and parameter names equal
  * the `CypherCatalog` template of that name, so a caller cannot send other
  * read-only text under an allowed name. No text from a question can reach the
  * graph as code. Values travel as parameters of the catalog templates, which
  * `CypherQuery` validates (read-only, one statement, no literals, bounded
  * `$limit`).
  *
  * The storage-side in-memory graph still dispatches on the name alone; that
  * fake is storage's, and this wrapper is the rag-side check.
  */
final class AllowlistedNeptune(delegate: NeptuneQueryPort)
    extends NeptuneQueryPort:
  def run(query: CypherQuery): Vector[Row] =
    Fail.unless(
      AllowlistedNeptune.Templates.contains(query.name),
      ErrorCode.QueryRejected,
      s"Query template ${query.name} is not on the runtime allowlist"
    )
    Fail.unless(
      AllowlistedNeptune.matchesCatalog(query),
      ErrorCode.QueryRejected,
      s"Query ${query.name} does not match its catalog template"
    )
    delegate.run(query)

object AllowlistedNeptune:
  /** The catalog queries built once with placeholder values; only their text
    * and parameter names are compared.
    */
  private val reference: Map[String, CypherQuery] = Map(
    CypherCatalog.CoauthorsOf ->
      CypherCatalog.coauthorsOf("reference", "reference", 1),
    CypherCatalog.EdgeEvidence -> CypherCatalog.edgeEvidence("reference", 1)
  )

  val Templates: Set[String] = reference.keySet

  def matchesCatalog(query: CypherQuery): Boolean =
    reference
      .get(query.name)
      .exists(r =>
        r.text == query.text && r.params.keySet == query.params.keySet
      )
