package scholarchain.hadoop.pipeline

import scholarchain.core.{
  AuthorshipRecord,
  ContractSchema,
  CoauthorPairCandidate,
  PublicationTeam,
  WorkRecord
}

/** The three artifacts job 6 derives from one retained work. */
final case class ExpandedTeam(
    team: PublicationTeam,
    authorPapers: Vector[AuthorshipRecord],
    pairs: Vector[CoauthorPairCandidate],
    expectedPairCount: Long,
    oversize: Boolean
)

/** Job 6: publication team extraction and coauthor-pair expansion.
  *
  * The quadratic step is the one that can take the pipeline down, so the bound
  * is explicit rather than implicit:
  *
  *   - a team of `n` authors yields exactly `n * (n - 1) / 2` unordered pairs
  *     before duplicate consolidation;
  *   - `n` above `maxAuthorTeamSize` is counted as an oversize team, which is a
  *     report, not a refusal;
  *   - `n * (n - 1) / 2` above `maxPairsPerWork` suppresses pair expansion for
  *     that work entirely. The team and author-paper records are still emitted,
  *     so the work is not lost — only its pair fan-out is withheld, and the
  *     withholding is counted and written to the reject output.
  *
  * A hyperauthorship paper with 3,000 authors would otherwise produce 4,498,500
  * pairs from one record, which is the collapse this guard exists to prevent.
  */
object TeamExpansion:
  val stage = "team-expansion"

  /** `n * (n - 1) / 2`, computed in `Long` so a large team cannot overflow the
    * bound check it is being measured against.
    */
  def expectedPairCount(authorCount: Int): Long =
    val n = authorCount.toLong
    if n < 2 then 0L else n * (n - 1L) / 2L

  def expand(work: WorkRecord, run: RunContext): ExpandedTeam =
    val mentions = work.authors
    val team = PublicationTeam(
      schemaVersion = ContractSchema.currentVersion,
      workId = work.workId,
      year = work.year,
      orderedMentionIds = mentions.map(_.mentionId),
      provenance = work.provenance
    )
    val authorPapers = mentions.map { mention =>
      AuthorshipRecord(
        schemaVersion = ContractSchema.currentVersion,
        workId = work.workId,
        mentionId = mention.mentionId,
        // Resolution to a canonical researcher is the entity-resolution
        // module's decision, so it is left open here rather than guessed.
        researcherId = None,
        authorPosition = mention.authorPosition,
        year = work.year,
        provenance = mention.provenance
      )
    }
    val expected = expectedPairCount(mentions.size)
    val oversize = mentions.size > run.maxAuthorTeamSize
    val pairs =
      if expected > run.maxPairsPerWork then Vector.empty
      else
        mentions.indices.iterator.flatMap { index =>
          (index + 1 until mentions.size).iterator.map { other =>
            val left = mentions(index)
            val right = mentions(other)
            // Contracts require ascending mention-ID order, which is
            // independent of author position, so the positions travel with
            // the endpoints they belong to rather than being re-derived.
            val (a, b) =
              if left.mentionId < right.mentionId then (left, right)
              else (right, left)
            CoauthorPairCandidate(
              schemaVersion = ContractSchema.currentVersion,
              workId = work.workId,
              mentionA = a.mentionId,
              mentionB = b.mentionId,
              authorPositionA = a.authorPosition,
              authorPositionB = b.authorPosition,
              year = work.year,
              provenance = work.provenance
            )
          }
        }.toVector
    ExpandedTeam(team, authorPapers, pairs, expected, oversize)

  /** Emits the team, author-paper and pair artifacts, keeping the counters in
    * step with what was actually written.
    *
    * The author-paper key is the mention ID so that a downstream reducer can
    * group one author's papers; the pair key is the ordered mention pair so
    * duplicates across works consolidate on one reducer.
    */
  def emit(work: WorkRecord, sink: TaskSink, run: RunContext): Unit =
    val expanded = expand(work, run)
    sink.emitRecord(TaskSink.teams, work.workId, expanded.team)
    expanded.authorPapers.foreach { authorship =>
      sink.emitRecord(TaskSink.authorPapers, authorship.mentionId, authorship)
    }
    // Zero authors is a different quality finding from one author, so the two
    // must not share a counter.
    if work.authors.isEmpty then sink.count(PipelineCounter.ZeroAuthorWorks)
    else if work.authors.size == 1 then
      sink.count(PipelineCounter.SingleAuthorWorks)
    if expanded.oversize then
      sink.count(PipelineCounter.OversizeAuthorTeams)
      sink.reject(
        RejectRecord(
          stage = stage,
          reason = RejectReason.OversizeAuthorTeam,
          detail =
            s"Work has ${work.authors.size} authors, above the bound of ${run.maxAuthorTeamSize}; expected pair count ${expanded.expectedPairCount}",
          sourceRecordId = work.sourceRecordId,
          payload = work.workId,
          provenance = work.provenance
        )
      )
    if expanded.pairs.isEmpty && expanded.expectedPairCount > 0 then
      sink.count(PipelineCounter.SuppressedPairs, expanded.expectedPairCount)
      sink.reject(
        RejectRecord(
          stage = stage,
          reason = RejectReason.OversizeAuthorTeam,
          detail =
            s"Pair expansion suppressed: ${expanded.expectedPairCount} pairs exceed the per-work bound of ${run.maxPairsPerWork}",
          sourceRecordId = work.sourceRecordId,
          payload = work.workId,
          provenance = work.provenance
        )
      )
    expanded.pairs.foreach { pair =>
      sink.emitRecord(
        TaskSink.pairs,
        s"${pair.mentionA}|${pair.mentionB}",
        pair
      )
      // Map-side expansion, so this is `ExpandedPairs`. `EmittedPairs` is the
      // reducer's, counted after consolidation; incrementing it here too would
      // double it, because one Hadoop counter accumulates over both phases.
      sink.count(PipelineCounter.ExpandedPairs)
    }

  /** Reduce side: consolidate duplicate pair records for one mention pair.
    *
    * Duplicates arise when the same work reaches the reducer more than once,
    * for example through a reingest. One record is kept per work and the result
    * is ordered by work ID. Within a work the retained record is the minimum
    * under [[retentionOrder]], a total order over the candidate's content, so
    * the result is a function of the set of candidates and not of the order the
    * shuffle delivered them in. The duplicate count is the number of candidates
    * dropped.
    */
  def consolidate(
      pairKey: String,
      candidates: Vector[CoauthorPairCandidate]
  ): (Vector[CoauthorPairCandidate], Long) =
    val byWork = candidates
      .groupBy(_.workId)
      .toVector
      .sortBy(_._1)
      .map { case (_, group) => group.min(using retentionOrder) }
    (byWork, (candidates.size - byWork.size).toLong)

  /** Lowest run ID first, then source record ID, then every remaining
    * provenance field and the pair's own fields, so two distinct candidates
    * never tie.
    */
  val retentionOrder: Ordering[CoauthorPairCandidate] =
    Ordering.by { candidate =>
      val provenance = candidate.provenance
      (
        (provenance.runId, provenance.sourceRecordId, provenance.source.wire),
        (
          provenance.snapshotId,
          provenance.sourceUri,
          provenance.transformVersion
        ),
        (candidate.mentionA, candidate.mentionB),
        (candidate.authorPositionA, candidate.authorPositionB, candidate.year)
      )
    }
