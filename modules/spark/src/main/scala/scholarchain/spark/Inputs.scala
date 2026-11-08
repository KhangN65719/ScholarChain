package scholarchain.spark

import java.nio.file.{Files, Path}
import org.apache.spark.sql.{DataFrame, SparkSession}
import scholarchain.core.{ContractSchema, Provenance, SourceType}

/** The two input snapshot directories and the artifacts read from each. Only
  * accepted-links may define identity: no decisions or gold path appears here.
  */
final case class InputLayout(erRun: Path, hadoopRoot: Path):
  val researcherLinks: Path =
    erRun.resolve("accepted-links/v1/researcher-links")
  val workLinks: Path = erRun.resolve("accepted-links/v1/work-links")
  val canonicalResearchers: Path =
    erRun.resolve("accepted-links/v1/canonical-researchers")
  val erManifest: Path = erRun.resolve("manifest.json")
  val erSuccess: Path = erRun.resolve("_SUCCESS")
  val works: Path = hadoopRoot.resolve("stage=graph-export/works/v1")
  val authorMentions: Path =
    hadoopRoot.resolve("stage=graph-export/author-mentions/v1")
  val institutions: Path =
    hadoopRoot.resolve("stage=graph-export/institutions/v1")
  val topics: Path = hadoopRoot.resolve("stage=graph-export/topics/v1")
  val venues: Path = hadoopRoot.resolve("stage=graph-export/venues/v1")
  val authorPapers: Path =
    hadoopRoot.resolve("stage=team-expansion/author-papers/v1")
  val pairCandidates: Path =
    hadoopRoot.resolve("stage=team-expansion/pair-candidates/v1")
  val teams: Path = hadoopRoot.resolve("stage=team-expansion/teams/v1")
  val teamExpansionSuccess: Path =
    hadoopRoot.resolve("stage=team-expansion/_SUCCESS")

  def datasets: Vector[(String, Path)] =
    Vector(
      "researcher-links" -> researcherLinks,
      "work-links" -> workLinks,
      "canonical-researchers" -> canonicalResearchers,
      "works" -> works,
      "author-mentions" -> authorMentions,
      "institutions" -> institutions,
      "topics" -> topics,
      "venues" -> venues,
      "author-papers" -> authorPapers,
      "pair-candidates" -> pairCandidates,
      "teams" -> teams
    )

  /** A missing required artifact, named, or None when every input exists. */
  def missing: Option[String] =
    val dirs = datasets.collect {
      case (name, path) if !Files.isDirectory(path) => s"$name ($path)"
    }
    val files = Vector(erManifest, erSuccess, teamExpansionSuccess).collect {
      case path if !Files.isRegularFile(path) => path.toString
    }
    val all = dirs ++ files
    Option.when(all.nonEmpty)(s"Missing input artifact: ${all.mkString(", ")}")

/** Identity of one pipeline run, derived from its inputs and configuration. */
final case class RunContext(
    runId: String,
    snapshotId: String,
    erRunId: String,
    hadoopRunId: String,
    transformVersion: String
):
  /** A Spark SQL expression building the core Provenance shape. */
  def provenance(table: String, key: String): String =
    val record = s"CAST($key AS STRING)"
    s"""named_struct(
       |  'schemaVersion', 1,
       |  'source', 'derived',
       |  'snapshotId', '$snapshotId',
       |  'sourceRecordId', $record,
       |  'sourceUri', 'scholarchain:spark-analytics/$table',
       |  'transformVersion', '$transformVersion',
       |  'runId', '$runId') AS provenance""".stripMargin

  /** Builds the core record so a malformed run or snapshot ID fails early. */
  def validated(table: String): Provenance =
    Provenance(
      ContractSchema.currentVersion,
      SourceType.Derived,
      snapshotId,
      "validation",
      s"scholarchain:spark-analytics/$table",
      transformVersion,
      runId
    )

object RunContext:
  /** Bumped by hand for a change of the output contract. A change of any main
    * source file is covered separately: the sources digest is part of the run
    * ID seed.
    */
  val transformVersion = "spark-analytics/1.0.0"

  /** The text a run ID is derived from: input digests, the ER manifest, the
    * configuration, the transform version and the digest of the module's main
    * sources, so the same inputs under changed code give a new run ID.
    */
  def seed(
      datasets: Vector[(String, String)],
      erManifestDigest: String,
      configText: String,
      sourcesDigest: String
  ): String =
    datasets.map((name, digest) => s"$name=$digest").mkString("|") +
      s"|er-manifest=$erManifestDigest|$configText|$transformVersion" +
      s"|code=$sourcesDigest"

  /** A run ID of the shape the contracts require, derived from a seed. */
  def runIdFor(seed: String): String =
    val h = Digests.sha256Hex(seed)
    val head = s"${h.slice(0, 8)}-${h.slice(8, 12)}"
    val tail = s"8${h.slice(17, 20)}-${h.slice(20, 32)}"
    s"run:v1:$head-4${h.slice(13, 16)}-$tail"

  def directoryName(runId: String): String = s"run=${runId.replace(':', '-')}"

final case class RawInputs(
    links: DataFrame,
    workLinks: DataFrame,
    canonical: DataFrame,
    works: DataFrame,
    mentions: DataFrame,
    institutions: DataFrame,
    topics: DataFrame,
    venues: DataFrame,
    authorPapers: DataFrame,
    pairs: DataFrame,
    teams: DataFrame
)

object Inputs:
  private val provenanceDdl =
    "provenance STRUCT<source: STRING, snapshotId: STRING, " +
      "sourceRecordId: STRING, sourceUri: STRING>"

  private def json(session: SparkSession, path: Path, ddl: String): DataFrame =
    session.read
      .schema(ddl)
      .option("mode", "FAILFAST")
      .json(path.toAbsolutePath.toString)

  def load(session: SparkSession, layout: InputLayout): RawInputs =
    RawInputs(
      links = json(
        session,
        layout.researcherLinks,
        "mentionId STRING, workId STRING, canonicalResearcherId STRING, " +
          "status STRING, idRule STRING, clusterAnchor STRING, clusterSize BIGINT"
      ),
      workLinks = json(
        session,
        layout.workLinks,
        "workId STRING, canonicalWorkId STRING, status STRING, " +
          "clusterAnchor STRING, clusterSize BIGINT"
      ),
      canonical = json(
        session,
        layout.canonicalResearchers,
        "canonicalResearcherId STRING, idRule STRING, orcid STRING, " +
          "status STRING, memberCount BIGINT, inResearchersExport BOOLEAN"
      ),
      works = json(
        session,
        layout.works,
        "workId STRING, source STRING, sourceRecordId STRING, title STRING, " +
          "year INT, venueId STRING, doi STRING"
      ),
      mentions = json(
        session,
        layout.authorMentions,
        "mentionId STRING, workId STRING, name STRING, normalizedName STRING"
      ),
      institutions = json(
        session,
        layout.institutions,
        "institutionId STRING, name STRING, countryCode STRING, " +
          s"externalIds MAP<STRING, STRING>, $provenanceDdl"
      ),
      topics = json(
        session,
        layout.topics,
        s"topicId STRING, name STRING, parentTopicId STRING, $provenanceDdl"
      ),
      venues = json(
        session,
        layout.venues,
        s"venueId STRING, name STRING, issns ARRAY<STRING>, $provenanceDdl"
      ),
      authorPapers = json(
        session,
        layout.authorPapers,
        "workId STRING, mentionId STRING, authorPosition INT, year INT"
      ),
      pairs = json(
        session,
        layout.pairCandidates,
        "workId STRING, mentionA STRING, mentionB STRING, " +
          "authorPositionA INT, authorPositionB INT, year INT"
      ),
      teams = json(
        session,
        layout.teams,
        "workId STRING, year INT, orderedMentionIds ARRAY<STRING>"
      )
    )
