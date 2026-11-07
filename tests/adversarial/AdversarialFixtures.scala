package scholarchain.tests.adversarial

import scholarchain.core.AuthorMention
import scholarchain.core.ContractSchema
import scholarchain.core.Provenance
import scholarchain.core.SourceType
import scholarchain.core.StableIds
import scholarchain.core.WorkRecord
import scholarchain.hadoop.pipeline.RunContext
import scholarchain.hadoop.pipeline.TextNormalizer

/** Independent fixtures for the Phase 4 adversarial suites.
  *
  * These suites live in `tests/adversarial`, which is independent test
  * territory, and reach the hadoop module through the root project's
  * `hadoop % "test->compile"` edge. The module's own `TestRun` helper is in the
  * module's test scope, which that edge does not carry, so the run context is
  * built here instead of reused.
  *
  * The run IDs are constants rather than fresh UUIDs, so provenance and path
  * assertions stay stable across runs.
  *
  * Only datasets pinned in `RunContext.localSnapshots` may be used as a source,
  * because `RunContext.provenance` refuses an unpinned dataset. That is `dblp`,
  * `openalex`, `orcid` and `crossref`.
  */
object AdversarialFixtures:
  val runId = "run:v1:3c1d2e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f"
  val secondRunId = "run:v1:7b8c9d0e-1f2a-4b3c-9d4e-5f6a7b8c9d0e"

  def run(
      runIdentifier: String = runId,
      partitionCount: Int = 4,
      maxBlockSize: Int = 50,
      maxAuthorTeamSize: Int = 50,
      maxPairsPerWork: Int = 10000
  ): RunContext =
    RunContext(
      runId = runIdentifier,
      transformVersion = RunContext.transformVersion,
      datasetSnapshots = RunContext.localSnapshots,
      outputRoot = "s3://local-curated-example/curated",
      partitionCount = partitionCount,
      maxBlockSize = maxBlockSize,
      maxAuthorTeamSize = maxAuthorTeamSize,
      maxPairsPerWork = maxPairsPerWork
    )

  /** A work carrying one mention per entry in `names`.
    *
    * The caller must supply a title that folds to a nonblank key, because
    * `WorkRecord` refuses a blank normalized title, and names that fold to
    * nonblank keys, because `AuthorMention` refuses a blank normalized name.
    * Hostile titles and names are driven through `DblpParser` instead, which is
    * where those refusals actually bite.
    */
  def work(
      key: String,
      names: Vector[String],
      year: Option[Int] = Some(2020),
      venueId: Option[String] = Some("venue:v1:dblp:jcdl"),
      title: String = "A Bounded Adversarial Title.",
      source: SourceType = SourceType.Dblp,
      context: RunContext = run()
  ): WorkRecord =
    val workId = StableIds.work(source.wire, key)
    val provenance = context.provenance(
      source,
      key,
      s"https://example.org/rec/$key",
      source.wire
    )
    WorkRecord(
      schemaVersion = ContractSchema.currentVersion,
      workId = workId,
      source = source,
      sourceRecordId = key,
      title = title,
      normalizedTitle = TextNormalizer.titleKey(title),
      year = year,
      venueId = venueId,
      doi = None,
      arxivId = None,
      authors = names.zipWithIndex.map { case (name, index) =>
        mention(workId, index + 1, name, provenance)
      },
      abstractTextRef = None,
      electronicEditionUris = Vector.empty,
      sourceUris = Vector.empty,
      recordType = "inproceedings",
      provenance = provenance
    )

  def mention(
      workId: String,
      position: Int,
      name: String,
      provenance: Provenance
  ): AuthorMention =
    AuthorMention(
      schemaVersion = ContractSchema.currentVersion,
      mentionId = StableIds.mention(workId, position),
      workId = workId,
      authorPosition = position,
      name = name,
      normalizedName = TextNormalizer.nameKey(name),
      externalIds = Map.empty,
      affiliations = Vector.empty,
      provenance = provenance
    )
