package scholarchain.spark

import org.apache.spark.sql.{DataFrame, SparkSession}

/** Job 9: change between consecutive windows.
  *
  * Consecutive windows are the configured windows in order, the all-years
  * window excluded. Every window graph is built independently from the works
  * dated inside it.
  *
  * Inclusion rules. A researcher is in a window when the researcher has at
  * least one dated canonical work in it. A comparison row exists for every
  * researcher present in at least one of the two windows; `presence` is `both`,
  * `only-from` or `only-to`, and every change column is NULL unless the
  * presence is `both`: a researcher absent from a window has no value to
  * compare, so no zero is invented. Component and community IDs are
  * window-local labels, so membership change is the Jaccard overlap of the
  * researcher's member set in the first window and in the second, over all
  * members of each set (members absent from the other window included).
  *
  * Where windows are not comparable. Windows may differ in length (see
  * `sameWindowLength`), so counts are not rates; works with no year are in no
  * window; an unresolved mention is a window-local node that is never matched
  * across windows; and the graph itself changes between windows, so a change in
  * a community or bridge value can come from the community method rather than
  * from behaviour.
  */
object Temporal:
  val inclusionRule: String =
    "Researchers with at least one dated work in a window are in it; " +
      "change columns are filled only when the researcher is present in both " +
      "windows; component and community membership change is the Jaccard " +
      "overlap of member sets because their IDs are window-local."
  val limitations: String =
    "Windows can differ in length so counts are not rates; works with no year " +
      "are in no window; unresolved mentions are window-local nodes never " +
      "matched across windows; community and bridge values depend on the " +
      "community method and on the changing graph."

  private val compared = Vector(
    "publicationCount",
    "distinctCoauthorCount",
    "averageCoauthorsPerPaper",
    "maximumCoauthorsOnOnePaper",
    "weightedDegree",
    "participationCoefficient",
    "crossCommunityDegree",
    "distinctCommunitiesConnected",
    "coreness"
  )

  def windowPairs(
      session: SparkSession,
      windows: Vector[NamedWindow]
  ): DataFrame =
    import org.apache.spark.sql.Row
    import org.apache.spark.sql.types.StructType
    import scala.jdk.CollectionConverters.*
    val rows = Windows
      .consecutivePairs(windows)
      .map((from, to) => Row(from.label, to.label))
    session.createDataFrame(
      rows.asJava,
      StructType.fromDDL("fromWindow STRING, toWindow STRING")
    )

  def compare(
      algo: Algo,
      metrics: DataFrame,
      pairs: DataFrame,
      ctx: RunContext
  ): DataFrame =
    val columns = compared
      .map(m =>
        s"""f.$m AS ${m}From, t.$m AS ${m}To, t.$m - f.$m AS ${m}Change"""
      )
      .mkString(",\n  ")
    val result = algo.sql(
      s"""WITH f AS (
         |  SELECT p.fromWindow, p.toWindow, m.* FROM {pairs} p
         |  JOIN {metrics} m ON m.window = p.fromWindow),
         |t AS (
         |  SELECT p.fromWindow, p.toWindow, m.* FROM {pairs} p
         |  JOIN {metrics} m ON m.window = p.toWindow),
         |both AS (
         |  SELECT f.fromWindow, f.toWindow, f.researcherId,
         |    f.componentId AS fComp, t.componentId AS tComp,
         |    f.communityId AS fComm, t.communityId AS tComm
         |  FROM f JOIN t ON f.fromWindow = t.fromWindow
         |    AND f.toWindow = t.toWindow AND f.researcherId = t.researcherId),
         |compShared AS (
         |  SELECT fromWindow, toWindow, fComp, tComp, count(*) AS shared
         |  FROM both GROUP BY fromWindow, toWindow, fComp, tComp),
         |commShared AS (
         |  SELECT fromWindow, toWindow, fComm, tComm, count(*) AS shared
         |  FROM both GROUP BY fromWindow, toWindow, fComm, tComm),
         |joined AS (
         |  SELECT COALESCE(f.fromWindow, t.fromWindow) AS fromWindow,
         |    COALESCE(f.toWindow, t.toWindow) AS toWindow,
         |    COALESCE(f.researcherId, t.researcherId) AS researcherId,
         |    CASE WHEN f.researcherId IS NOT NULL AND t.researcherId IS NOT NULL
         |      THEN 'both' WHEN f.researcherId IS NOT NULL THEN 'only-from'
         |      ELSE 'only-to' END AS presence,
         |    $columns,
         |    CAST(cs.shared AS DOUBLE)
         |      / (f.componentSize + t.componentSize - cs.shared)
         |      AS componentOverlap,
         |    CAST(ms.shared AS DOUBLE)
         |      / (f.communitySize + t.communitySize - ms.shared)
         |      AS communityOverlap
         |  FROM f FULL OUTER JOIN t
         |    ON f.fromWindow = t.fromWindow AND f.toWindow = t.toWindow
         |    AND f.researcherId = t.researcherId
         |  LEFT JOIN compShared cs ON cs.fromWindow = f.fromWindow
         |    AND cs.toWindow = f.toWindow AND cs.fComp = f.componentId
         |    AND cs.tComp = t.componentId
         |  LEFT JOIN commShared ms ON ms.fromWindow = f.fromWindow
         |    AND ms.toWindow = f.toWindow AND ms.fComm = f.communityId
         |    AND ms.tComm = t.communityId)
         |SELECT *, presence = 'both' AS comparable,
         |  ${ctx.provenance(
          "temporal-comparison",
          "concat(fromWindow, '|', toWindow, '|', researcherId)"
        )}
         |FROM joined""".stripMargin,
      "pairs" -> pairs,
      "metrics" -> metrics
    )
    algo.telemetry.plan("temporal-comparison", result)
    result

  def summary(
      algo: Algo,
      comparison: DataFrame,
      windows: DataFrame,
      worksWithoutYear: Long,
      ctx: RunContext
  ): DataFrame =
    algo.sql(
      s"""SELECT c.fromWindow, c.toWindow,
         |  sum(CASE WHEN c.presence = 'both' THEN 1 ELSE 0 END)
         |    AS researchersInBoth,
         |  sum(CASE WHEN c.presence <> 'only-to' THEN 1 ELSE 0 END)
         |    AS researchersInFrom,
         |  sum(CASE WHEN c.presence <> 'only-from' THEN 1 ELSE 0 END)
         |    AS researchersInTo,
         |  sum(CASE WHEN c.presence = 'only-from' THEN 1 ELSE 0 END)
         |    AS onlyInFrom,
         |  sum(CASE WHEN c.presence = 'only-to' THEN 1 ELSE 0 END) AS onlyInTo,
         |  avg(c.distinctCoauthorCountChange) AS meanDistinctCoauthorChange,
         |  avg(c.averageCoauthorsPerPaperChange) AS meanAverageTeamSizeChange,
         |  avg(c.participationCoefficientChange) AS meanBridgeChange,
         |  avg(c.componentOverlap) AS meanComponentOverlap,
         |  avg(c.communityOverlap) AS meanCommunityOverlap,
         |  fw.windowEnd - fw.windowStart + 1 AS fromYears,
         |  tw.windowEnd - tw.windowStart + 1 AS toYears,
         |  (fw.windowEnd - fw.windowStart) = (tw.windowEnd - tw.windowStart)
         |    AS sameWindowLength,
         |  CAST($worksWithoutYear AS BIGINT) AS worksWithoutYearExcluded,
         |  '$inclusionRule' AS inclusionRule,
         |  '$limitations' AS limitations,
         |  ${ctx.provenance(
          "temporal-window-summary",
          "concat(c.fromWindow, '|', c.toWindow)"
        )}
         |FROM {comparison} c
         |JOIN {windows} fw ON c.fromWindow = fw.window
         |JOIN {windows} tw ON c.toWindow = tw.window
         |GROUP BY c.fromWindow, c.toWindow, fw.windowStart, fw.windowEnd,
         |  tw.windowStart, tw.windowEnd""".stripMargin,
      "comparison" -> comparison,
      "windows" -> windows
    )
