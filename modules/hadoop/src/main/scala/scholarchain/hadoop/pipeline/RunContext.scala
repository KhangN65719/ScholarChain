package scholarchain.hadoop.pipeline

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat
import scholarchain.core.{ContractSchema, Provenance, RunManifest, SourceType}

/** Identifies one pipeline execution and the exact dataset snapshots it read.
  *
  * Every emitted record carries a [[Provenance]] minted here, so source and
  * transformation provenance travel with the data rather than with the logs.
  *
  * Output paths are derived from the same values, so a prefix is a pure
  * function of the run ID and the snapshot digest: the same run ID over the
  * same snapshot set always addresses the same prefix, and a different snapshot
  * set cannot land on it.
  *
  * That is addressing, not overwrite semantics. Hadoop's `FileOutputFormat`
  * rejects an existing output directory at submission with
  * `FileAlreadyExistsException`, so **rerunning with the same run ID fails
  * rather than replacing anything**. The rerun policy is explicit: either
  * delete the prefix first and rerun, or mint a new run ID and leave the
  * previous run's evidence intact. Preferring a new run ID keeps prior output
  * auditable, which is why [[RunContext]] never reuses one implicitly.
  */
final case class RunContext(
    runId: String,
    transformVersion: String,
    datasetSnapshots: Map[String, String],
    outputRoot: String,
    partitionCount: Int,
    maxBlockSize: Int,
    maxAuthorTeamSize: Int,
    maxPairsPerWork: Int
):
  require(datasetSnapshots.nonEmpty, "A run must declare its input snapshots")
  require(partitionCount > 0, "Partition count must be positive")
  require(maxBlockSize > 1, "A bounded block must admit at least two records")
  require(maxAuthorTeamSize > 1, "An author team bound must admit a pair")
  require(maxPairsPerWork > 0, "The pair bound must be positive")

  /** A short, order-independent digest of the exact snapshot set. The full map
    * stays in the run manifest; the digest keeps the path component bounded.
    *
    * **Length framing, not a delimiter.** Each component is digested as a
    * four-byte big-endian length followed by its UTF-8 bytes, the scheme
    * `StableIds.digestId` already uses.
    *
    * The previous form interpolated `s"$dataset=$version\u0000"`, which made
    * `=` the separator while being a character the components can contain. So
    * `Map("a=b" -> "c")` and `Map("a" -> "b=c")` both fed `"a=b=c\u0000"` and
    * two genuinely different snapshot sets addressed one output prefix. Framing
    * removes the possibility rather than forbidding the character: the length
    * prefix fixes where each component ends, so no two splits of the same
    * character sequence can produce the same digest input.
    */
  val snapshotId: String =
    val digest = MessageDigest.getInstance("SHA-256")
    datasetSnapshots.toVector.sorted.foreach { case (dataset, version) =>
      Vector(dataset, version).foreach { component =>
        val bytes = component.getBytes(UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array())
        digest.update(bytes)
      }
    }
    HexFormat.of().formatHex(digest.digest()).take(12)

  val runSlug: String = runId.replace(':', '-')

  def snapshotOf(dataset: String): String =
    datasetSnapshots.getOrElse(
      dataset,
      throw new IllegalArgumentException(s"Unpinned dataset snapshot: $dataset")
    )

  def provenance(
      source: SourceType,
      sourceRecordId: String,
      sourceUri: String,
      dataset: String
  ): Provenance =
    Provenance(
      schemaVersion = ContractSchema.currentVersion,
      source = source,
      snapshotId = s"$dataset:${snapshotOf(dataset)}",
      sourceRecordId = sourceRecordId,
      sourceUri = sourceUri,
      transformVersion = transformVersion,
      runId = runId
    )

  private val schemaSegment = s"v${ContractSchema.currentVersionNumber}"

  /** The run's prefix, derived only from the run ID and the snapshot digest.
    *
    * Deterministic addressing: the same pair always yields this exact string,
    * on every node and every rerun. It does not imply overwrite — see the class
    * comment for the rerun policy.
    */
  def runPath: String = s"$outputRoot/run=$runSlug/snapshot=$snapshotId"

  /** One job's output directory. Every artifact a job writes lives beneath its
    * own stage directory, which keeps Hadoop's requirement that a job output
    * directory not already exist compatible with the one-writer-per-artifact
    * rule: a stage directory has exactly one producing job.
    */
  def stagePath(stage: String): String = s"$runPath/stage=$stage"

  /** A stage directory further divided by input dataset.
    *
    * The source-normalization stage runs once per dataset, and two Hadoop jobs
    * cannot share one output directory. Qualifying the directory by dataset
    * keeps each job's output separate while leaving the artifact's owning
    * stage, and therefore its single writer, unchanged.
    */
  def stagePath(stage: String, dataset: String): String =
    s"${stagePath(stage)}/dataset=$dataset"

  /** `<root>/run=<slug>/snapshot=<digest>/stage=<owner>/<artifact>/v1`. The
    * schema segment lets a later schema version land beside this one instead of
    * overwriting it.
    *
    * Refuses a dataset-scoped artifact. Its writing job runs once per dataset
    * and commits beneath a `dataset=` segment, so returning a path without one
    * would hand a reader — most likely the Spark side — a prefix that nothing
    * ever writes. Use [[artifactPath(artifact:*,dataset:*)]] for those.
    */
  def artifactPath(artifact: Artifact): String =
    require(
      !artifact.datasetScoped,
      s"${artifact.segment} is written once per dataset; pass the dataset"
    )
    s"${stagePath(artifact.owningStage)}/${artifact.segment}/$schemaSegment"

  /** The committed path of a dataset-scoped artifact, matching exactly what
    * `ArtifactSink` writes beneath the per-dataset job output directory.
    */
  def artifactPath(artifact: Artifact, dataset: String): String =
    require(
      artifact.datasetScoped,
      s"${artifact.segment} is not dataset-scoped; omit the dataset"
    )
    require(dataset.trim.nonEmpty, "A dataset-scoped path needs a dataset")
    s"${stagePath(artifact.owningStage, dataset)}/${artifact.segment}/$schemaSegment"

  /** The reject output for one stage, versioned alongside its accepted records
    * so the two are read with the same schema assumption.
    */
  def rejectPath(stage: String): String =
    s"${stagePath(stage)}/rejects/$schemaSegment"

  def manifest(configurationVersion: String): RunManifest =
    RunManifest(
      schemaVersion = ContractSchema.currentVersion,
      runId = runId,
      datasetSnapshots = datasetSnapshots,
      configurationVersion = configurationVersion,
      provenance = provenance(
        SourceType.Derived,
        runId,
        s"scholarchain:run/$runSlug",
        datasetSnapshots.keys.toVector.sorted.head
      )
    )

object RunContext:
  val transformVersion = "hadoop-pipeline:v1"

  /** The snapshot versions pinned by `datasets/dataset-manifest.yaml` at the
    * Phase 3 baseline. Overridable so a later snapshot does not need a code
    * change, but recorded here so a local run is reproducible without the
    * manifest file on the classpath.
    */
  val localSnapshots: Map[String, String] = Map(
    "dblp" -> "2026-11-01",
    "openalex" -> "2026-10-14",
    "orcid" -> "2026-10-13-v1",
    "crossref" -> "2026-03"
  )

/** Stage names. A stage is one MapReduce job and owns the artifacts written
  * beneath its directory.
  */
object Stage:
  val dblpExtraction = "dblp-extraction"
  val sourceNormalization = "source-normalization"
  val identifierJoin = "identifier-join"
  val blocking = "blocking"
  val candidateFeatures = "candidate-features"
  val teamExpansion = "team-expansion"
  val graphExport = "graph-export"

  val ordered: Vector[String] = Vector(
    dblpExtraction,
    sourceNormalization,
    identifierJoin,
    blocking,
    candidateFeatures,
    teamExpansion,
    graphExport
  )

/** Output artifact classes.
  *
  * `owningStage` encodes the one-writer rule in the path itself: no two jobs
  * can write the same artifact prefix, because the prefix contains the name of
  * the single job entitled to write it.
  *
  * `namedOutput` is the Hadoop `MultipleOutputs` name. Hadoop restricts those
  * to alphanumerics, so it is a separate value from the hyphenated path segment
  * rather than derived from it.
  *
  * `datasetScoped` marks an artifact whose writing job runs once per input
  * dataset and therefore commits beneath a `dataset=` segment. It is declared
  * on the artifact rather than inferred from the stage so that
  * [[RunContext.artifactPath]] can refuse to compose a path nothing writes.
  */
enum Artifact(
    val segment: String,
    val namedOutput: String,
    val owningStage: String,
    val datasetScoped: Boolean = false
):
  case Works extends Artifact("works", "works", Stage.graphExport)
  case AuthorMentions
      extends Artifact("author-mentions", "authormentions", Stage.graphExport)
  case Researchers
      extends Artifact("researchers", "researchers", Stage.graphExport)
  case Teams extends Artifact("teams", "teams", Stage.teamExpansion)
  case AuthorPapers
      extends Artifact("author-papers", "authorpapers", Stage.teamExpansion)
  case PairCandidates
      extends Artifact("pair-candidates", "paircandidates", Stage.teamExpansion)
  case IdentifierKeys
      extends Artifact(
        "identifier-keys",
        "identifierkeys",
        Stage.identifierJoin
      )
  case BlockingGroups
      extends Artifact("blocking-groups", "blockinggroups", Stage.blocking)
  case BlockingMembers
      extends Artifact("blocking-members", "blockingmembers", Stage.blocking)
  case MatchCandidates
      extends Artifact(
        "match-candidates",
        "matchcandidates",
        Stage.candidateFeatures
      )
  case Topics extends Artifact("topics", "topics", Stage.graphExport)
  case Institutions
      extends Artifact("institutions", "institutions", Stage.graphExport)
  case Venues extends Artifact("venues", "venues", Stage.graphExport)
  // Stage-scoped ingestion outputs. The segment name repeats across stages,
  // but the full path does not, because it carries the owning stage. That is
  // what keeps one writer per artifact path while letting both the DBLP and
  // the external-source stages publish their own venues and researchers.
  //
  // All five are dataset-scoped: source normalization runs once per dataset,
  // so each commits beneath `dataset=<name>`.
  case NormalizedWorks
      extends Artifact("works", "works", Stage.sourceNormalization, true)
  case NormalizedResearchers
      extends Artifact(
        "researchers",
        "researchers",
        Stage.sourceNormalization,
        true
      )
  case NormalizedTopics
      extends Artifact("topics", "topics", Stage.sourceNormalization, true)
  case NormalizedInstitutions
      extends Artifact(
        "institutions",
        "institutions",
        Stage.sourceNormalization,
        true
      )
  case NormalizedVenues
      extends Artifact("venues", "venues", Stage.sourceNormalization, true)
  case DblpWorks extends Artifact("works", "works", Stage.dblpExtraction)
  case DblpResearchers
      extends Artifact("researchers", "researchers", Stage.dblpExtraction)
  case DblpVenues extends Artifact("venues", "venues", Stage.dblpExtraction)
  case Manifest
      extends Artifact("run-manifest", "runmanifest", Stage.graphExport)
