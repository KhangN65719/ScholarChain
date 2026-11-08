package scholarchain.hadoop.pipeline

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scholarchain.core.{AuthorMention, SourceType, WorkRecord}
import scholarchain.core.pairing.{BlockKind, BlockStrategy}
import upickle.default.ReadWriter

/** One member of a candidate block, carrying only the features the candidate
  * stage needs. Keeping this narrow bounds reducer memory for a large block.
  */
final case class BlockMember(
    kind: BlockKind,
    memberId: String,
    source: SourceType,
    displayName: String,
    normalizedName: String,
    normalizedTitle: String,
    year: Option[Int],
    venueId: Option[String],
    affiliations: Vector[String],
    coauthorNames: Vector[String],
    externalIds: Map[String, String],
    workId: String
) derives ReadWriter

/** A block member paired with the block that carried it, so the candidate stage
  * can re-group members by block after a shuffle without re-deriving blocking
  * keys.
  */
final case class BlockEnvelope(blockKey: String, member: BlockMember)
    derives ReadWriter

/** Retains the `limit` smallest members of a block by `(source, memberId)`
  * while counting every member received.
  *
  * Why a selection heap rather than "buffer some and truncate": truncating a
  * buffer filled in arrival order would make the retained subset depend on the
  * order the shuffle happened to deliver values, so two runs over identical
  * input could retain different members of an oversize block. Selecting by the
  * ordering makes the retained subset a function of the block's content alone,
  * while memory stays proportional to `limit` rather than to the block.
  *
  * A duplicate member ID that arrives after its twin has been evicted can
  * re-enter the heap, so [[retained]] is deduplicated on the way out and the
  * retained set stays distinct and deterministic.
  */
final class BoundedBlockSelector(limit: Int):
  require(limit > 0, "A bounded selector needs a positive limit")

  private val ordering: Ordering[BlockMember] =
    Ordering.by(member => (member.source.wire, member.memberId))
  private val heap =
    scala.collection.mutable.PriorityQueue.empty[BlockMember](ordering)
  private val retainedIds = scala.collection.mutable.HashSet.empty[String]
  private var received = 0L
  private var evictedAny = false

  def add(member: BlockMember): Unit =
    received += 1L
    if !retainedIds.contains(member.memberId) then
      heap.enqueue(member)
      retainedIds += member.memberId
      if heap.size > limit then
        val evicted = heap.dequeue()
        retainedIds -= evicted.memberId
        evictedAny = true

  /** Every member the reducer was handed, duplicates included.
    *
    * This is the single definition of a block's observed size across the whole
    * pipeline: the record count the reducer actually processed. It is the
    * load-bearing figure, because a reducer pays for every record delivered to
    * it whether or not two of them name the same member. An exact distinct
    * count is deliberately not offered — it would need memory proportional to
    * the block, which is the very thing the bound exists to avoid.
    */
  def observed: Long = received

  /** True only if a member was actually dropped.
    *
    * Ground truth from the eviction itself rather than inferred by comparing
    * counts, so a duplicate member ID can never make a complete block look
    * truncated.
    */
  def evicted: Boolean = evictedAny

  def retained: Vector[BlockMember] =
    heap.toVector.distinctBy(_.memberId).sorted(ordering)

/** A bounded candidate block as written to the blocking-group artifact. */
final case class BlockingGroup(
    blockKey: String,
    kind: BlockKind,
    strategy: BlockStrategy,
    observedSize: Int,
    retainedSize: Int,
    truncated: Boolean,
    memberIds: Vector[String]
) derives ReadWriter

/** Job 4: researcher and work blocking.
  *
  * Two properties matter and are enforced here rather than left to the
  * reducer's good luck:
  *
  *   1. Every key is **deterministic** — derived only from normalized record
  *      content, never from a hash seed, iteration order or task identity.
  *   2. Every block is **bounded** — a block above `maxBlockSize` is counted,
  *      recorded as a reject with its observed size, and deterministically
  *      truncated. Without this a common name such as a folded "Wang Wei" or a
  *      stock title block would hand one reducer a quadratic join.
  *
  * The non-selective strategies (`NameInitials`, `TitlePrefix`) are the ones
  * that go pathological, so they additionally carry a **salt fan-out**: their
  * keys are split across `saltBuckets` partitions by a stable digest of the
  * member ID. That trades a little recall for a hard cap on any one reducer's
  * share of a hot key, and the salt is derived from content so it is identical
  * on every rerun.
  */
object Blocking:
  val stage = "blocking"
  val saltBuckets = 8

  private def digestBucket(value: String, buckets: Int): Int =
    val digest =
      MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8))
    val unsigned = ((digest(0) & 0xff) << 8) | (digest(1) & 0xff)
    unsigned % buckets

  def key(strategy: BlockStrategy, kind: BlockKind, parts: String*): String =
    s"${kind.wire}|${strategy.wire}|${parts.mkString("|")}"

  /** Adds a content-derived salt segment to a non-selective key. */
  private def salted(
      baseKey: String,
      strategy: BlockStrategy,
      memberId: String
  ): String =
    if strategy.selective then baseKey
    else s"$baseKey|s${digestBucket(memberId, saltBuckets)}"

  /** Researcher blocking keys for one author mention in the context of its
    * work. Each key is paired with the strategy that produced it.
    *
    * `coauthorContext` is the per-work fan-out bound from [[emit]]. When false
    * the quadratic `NameCoauthor` keys are withheld and the selective keys are
    * still produced. It defaults to true so a caller comparing one mention's
    * keys does not have to know about the bound.
    */
  def researcherKeys(
      mention: AuthorMention,
      work: WorkRecord,
      coauthorNames: Vector[String],
      coauthorContext: Boolean = true
  ): Vector[(BlockStrategy, String)] =
    val nameBlock = TextNormalizer.nameBlockKey(mention.name)
    if nameBlock.isEmpty then Vector.empty
    else
      val family = TextNormalizer.familyToken(mention.name)
      val initials = TextNormalizer.initials(mention.name)
      val base = Vector(
        work.year.map(year =>
          BlockStrategy.NameYear -> key(
            BlockStrategy.NameYear,
            BlockKind.Researcher,
            nameBlock,
            year.toString
          )
        ),
        work.venueId.map(venue =>
          BlockStrategy.NameVenue -> key(
            BlockStrategy.NameVenue,
            BlockKind.Researcher,
            nameBlock,
            venue
          )
        ),
        Some(
          BlockStrategy.NameInitials -> key(
            BlockStrategy.NameInitials,
            BlockKind.Researcher,
            family,
            initials
          )
        )
      ).flatten
      // Coauthor context is the strongest available signal for a common name,
      // so one key per distinct coauthor is emitted. The pair is ordered so
      // both members of a collaboration derive the identical key.
      //
      // A coauthor whose name block equals this mention's is **not** excluded.
      // Excluding it was the F7 defect: on a paper whose authors all fold to
      // one name this produced no coauthor keys at all, losing the signal in
      // precisely the common-name case the strategy exists to serve. Such a
      // team now yields the self-paired key `(block, block)`, and `.distinct`
      // collapses the repeats, so the fan-out stays linear for that shape.
      val coauthorKeys =
        if !coauthorContext then Vector.empty
        else
          coauthorNames
            .map(TextNormalizer.nameBlockKey)
            .filter(_.nonEmpty)
            .distinct
            .map { coauthor =>
              val (left, right) =
                if nameBlock < coauthor then (nameBlock, coauthor)
                else (coauthor, nameBlock)
              BlockStrategy.NameCoauthor -> key(
                BlockStrategy.NameCoauthor,
                BlockKind.Researcher,
                left,
                right
              )
            }
      val affiliationKeys = mention.affiliations
        .map(TextNormalizer.fold)
        .filter(_.nonEmpty)
        .distinct
        .map(affiliation =>
          BlockStrategy.NameAffiliation -> key(
            BlockStrategy.NameAffiliation,
            BlockKind.Researcher,
            nameBlock,
            affiliation
          )
        )
      (base ++ coauthorKeys ++ affiliationKeys).distinct

  /** Work blocking keys. The title prefix key is the non-selective fallback
    * that catches a year or venue disagreement between two sources.
    */
  def workKeys(work: WorkRecord): Vector[(BlockStrategy, String)] =
    val title = work.normalizedTitle
    if title.isEmpty then Vector.empty
    else
      // Cut on code points, so a supplementary-plane letter (CJK extension B,
      // for instance) is never split into a lone surrogate.
      val prefix =
        title.substring(
          0,
          title.offsetByCodePoints(
            0,
            math.min(24, title.codePointCount(0, title.length))
          )
        )
      Vector(
        work.year.map(year =>
          BlockStrategy.TitleYear -> key(
            BlockStrategy.TitleYear,
            BlockKind.Work,
            prefix,
            year.toString
          )
        ),
        Some(
          BlockStrategy.TitlePrefix -> key(
            BlockStrategy.TitlePrefix,
            BlockKind.Work,
            prefix
          )
        )
      ).flatten.distinct

  def workMember(work: WorkRecord): BlockMember =
    BlockMember(
      kind = BlockKind.Work,
      memberId = work.workId,
      source = work.source,
      displayName = work.title,
      normalizedName = "",
      normalizedTitle = work.normalizedTitle,
      year = work.year,
      venueId = work.venueId,
      affiliations = work.authors.flatMap(_.affiliations).distinct,
      coauthorNames = work.authors.map(_.normalizedName),
      externalIds = (work.doi.map(IdNamespace.Doi.wire -> _).toVector
        ++ work.arxivId.map(IdNamespace.Arxiv.wire -> _).toVector
        ++ Vector("source-record-id" -> work.sourceRecordId)).toMap,
      workId = work.workId
    )

  def researcherMember(
      mention: AuthorMention,
      work: WorkRecord,
      coauthorNames: Vector[String]
  ): BlockMember =
    BlockMember(
      kind = BlockKind.Researcher,
      memberId = mention.mentionId,
      source = work.source,
      displayName = mention.name,
      normalizedName = mention.normalizedName,
      normalizedTitle = work.normalizedTitle,
      year = work.year,
      venueId = work.venueId,
      affiliations = mention.affiliations,
      coauthorNames = coauthorNames,
      externalIds = mention.externalIds,
      workId = work.workId
    )

  /** Coauthor-context keys a work would emit if unbounded: one per ordered
    * (mention, coauthor) pair, so `n * (n - 1)`.
    *
    * Computed in `Long` so the bound check cannot itself overflow on the team
    * sizes it exists to catch.
    */
  def expectedCoauthorKeys(authorCount: Int): Long =
    val n = authorCount.toLong
    if n < 2 then 0L else n * (n - 1L)

  /** Map-side key emission for one work and all of its mentions.
    *
    * **The coauthor-context fan-out is bounded here.** Every other strategy
    * emits one key per mention, so a work costs `O(n)` records. `NameCoauthor`
    * emits one key per ordered pair, `n * (n - 1)`, and each record carries a
    * `coauthorNames` vector of width `n - 1` — so the bytes a single input
    * record expands to are cubic in the team size. Nothing else stopped it:
    * `maxPairsPerWork` guards only pair expansion, `maxAuthorTeamSize` only
    * counts, and `maxBlockSize` bounds what a block retains after the shuffle,
    * not the map output volume spread across `n * (n - 1) / 2` distinct keys.
    *
    * Above the bound the coauthor keys are suppressed, counted and written to
    * the reject output. The selective `O(n)` strategies are kept, so an
    * oversize team still reaches its name, year, venue and affiliation blocks
    * and is not dropped from blocking altogether — only its quadratic
    * contribution is withheld. The repeated coauthor feature vector is also
    * omitted above the bound, avoiding quadratic fallback payload growth.
    *
    * `maxPairsPerWork` is reused rather than adding a knob: it already means
    * "the per-work budget for quadratic expansion", and one bound for both
    * quadratic paths is easier to reason about than two that can disagree.
    */
  def emit(work: WorkRecord, sink: TaskSink, run: RunContext): Unit =
    workKeys(work).foreach { case (strategy, blockKey) =>
      sink.emitRecord(
        TaskSink.blockingKeys,
        salted(blockKey, strategy, work.workId),
        workMember(work)
      )
    }
    val expected = expectedCoauthorKeys(work.authors.size)
    val withinBound = expected <= run.maxPairsPerWork
    if !withinBound then
      sink.count(PipelineCounter.SuppressedBlockingKeys, expected)
      sink.reject(
        RejectRecord(
          stage = stage,
          reason = RejectReason.OversizeCoauthorFanOut,
          detail =
            s"Coauthor blocking suppressed: ${work.authors.size} authors would emit $expected keys, above the per-work bound of ${run.maxPairsPerWork}; selective strategies retained",
          sourceRecordId = work.sourceRecordId,
          payload = work.workId,
          provenance = work.provenance
        )
      )
    work.authors.foreach { mention =>
      // Excluded by mention identity, not by normalized name. Excluding by name
      // made a paper whose authors all fold to one name emit no coauthor keys
      // at all, which is exactly the common-name case the strategy exists for.
      val coauthors =
        if !withinBound then Vector.empty
        else
          work.authors
            .filterNot(_.mentionId == mention.mentionId)
            .map(_.normalizedName)
      researcherKeys(mention, work, coauthors, withinBound).foreach {
        case (strategy, blockKey) =>
          sink.emitRecord(
            TaskSink.blockingKeys,
            salted(blockKey, strategy, mention.mentionId),
            researcherMember(mention, work, coauthors)
          )
      }
    }

  def strategyOf(blockKey: String): Option[BlockStrategy] =
    blockKey
      .split("\\|")
      .lift(1)
      .flatMap(wire => BlockStrategy.values.find(_.wire == wire))

  def kindOf(blockKey: String): Option[BlockKind] =
    blockKey
      .split("\\|")
      .headOption
      .flatMap(wire => BlockKind.values.find(_.wire == wire))

  /** Builds the group summary from an already-bounded member set.
    *
    * The one place a [[BlockingGroup]] is constructed, so `observedSize` and
    * `truncated` cannot acquire two meanings in two call sites. `observedSize`
    * is always the record count the reducer received, duplicates included; see
    * [[BoundedBlockSelector.observed]].
    */
  private def group(
      blockKey: String,
      retained: Vector[BlockMember],
      observed: Long,
      truncated: Boolean
  ): BlockingGroup =
    BlockingGroup(
      blockKey = blockKey,
      kind = kindOf(blockKey).getOrElse(BlockKind.Researcher),
      strategy = strategyOf(blockKey).getOrElse(BlockStrategy.NameInitials),
      observedSize = math.min(observed, Int.MaxValue.toLong).toInt,
      retainedSize = retained.size,
      truncated = truncated,
      memberIds = retained.map(_.memberId)
    )

  /** Reduce-side bounding, streaming form. **This is the path both reducers
    * take**, so it is the behavior that ships.
    *
    * Both figures come from the selector: the raw received count, and eviction
    * as the ground truth for truncation.
    */
  def boundSelected(
      blockKey: String,
      selector: BoundedBlockSelector,
      run: RunContext
  ): (BlockingGroup, Vector[BlockMember]) =
    val retained = selector.retained
    (
      group(blockKey, retained, selector.observed, selector.evicted),
      retained
    )

  /** Whole-block form, for a caller that already holds every member.
    *
    * Equivalent to feeding the same members through a selector: members are
    * ordered by `(source, memberId)` before truncation, so the retained subset
    * of an oversize block is the same on every run regardless of delivery
    * order, and `observedSize` counts every member supplied.
    */
  def bound(
      blockKey: String,
      members: Vector[BlockMember],
      run: RunContext
  ): (BlockingGroup, Vector[BlockMember]) =
    val selector = new BoundedBlockSelector(run.maxBlockSize)
    members.foreach(selector.add)
    boundSelected(blockKey, selector, run)
