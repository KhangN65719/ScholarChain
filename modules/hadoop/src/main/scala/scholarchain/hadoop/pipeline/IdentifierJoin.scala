package scholarchain.hadoop.pipeline

import scholarchain.core.{ContractSchema, Provenance, SourceType, WorkRecord}
import upickle.default.ReadWriter

/** One record's claim on one exact identifier. This is the map output of the
  * join-preparation job; the shuffle groups every claim on the same identifier
  * together.
  */
final case class IdentifierClaim(
    namespace: String,
    value: String,
    entityId: String,
    source: SourceType,
    sourceRecordId: String,
    normalizedTitle: String,
    year: Option[Int]
) derives ReadWriter

/** A resolved exact-identifier key: every record that claimed it, plus the
  * duplicate and conflict findings.
  *
  * Two distinct kinds of finding are separated deliberately:
  *
  *   - **duplicate**: several records from the *same* source claim one
  *     identifier. That is a defect in the snapshot, or an ingest replay.
  *   - **conflict**: records agreeing on the identifier disagree on content
  *     that the identifier is supposed to pin, such as the normalized title.
  *     That is evidence the identifier itself is wrong somewhere upstream.
  *
  * Collapsing them into one "bad key" counter would hide which of the two is
  * happening, so they are counted and reported apart.
  */
final case class IdentifierJoinKey(
    schemaVersion: ContractSchema.Version,
    namespace: String,
    value: String,
    joinKey: String,
    entityIds: Vector[String],
    sources: Vector[String],
    duplicateSources: Vector[String],
    conflictingTitles: Vector[String],
    provenance: Provenance
) derives ReadWriter:
  require(
    schemaVersion == ContractSchema.currentVersion,
    "Unsupported join key schema version"
  )
  require(entityIds.nonEmpty, "A join key requires at least one record")

/** Job 3: exact identifier extraction and join preparation. */
object IdentifierJoin:
  val stage = "identifier-join"

  /** Map side: one claim per normalized identifier the work carries. A work
    * with no exact identifier at all is counted as a missing key, because it
    * can only ever be linked by the fuzzy blocking path.
    */
  def claims(
      work: WorkRecord,
      extra: Vector[(IdNamespace, String)]
  ): Vector[(String, IdentifierClaim)] =
    val structural = Vector(
      work.doi.map(IdNamespace.Doi -> _),
      work.arxivId.map(IdNamespace.Arxiv -> _)
    ).flatten
    // Each source's own work identifier. It is recoverable from the persisted
    // record's `sourceRecordId`, so the native key still joins even though the
    // work contract has no dedicated field for it.
    val nativeKey = (work.source match
      case SourceType.Dblp            => Some(IdNamespace.DblpKey)
      case SourceType.OpenAlex        => Some(IdNamespace.OpenAlexWork)
      case SourceType.SemanticScholar => Some(IdNamespace.SemanticScholar)
      case SourceType.Crossref        => Some(IdNamespace.Doi)
      case SourceType.Arxiv           => Some(IdNamespace.Arxiv)
      case _                          => None
    ).map(_ -> work.sourceRecordId).toVector
    val authorOrcids = work.authors.flatMap(
      _.externalIds.get(IdNamespace.Orcid.wire).map(IdNamespace.Orcid -> _)
    )
    val authorOpenAlex = work.authors.flatMap(
      _.externalIds
        .get(IdNamespace.OpenAlexAuthor.wire)
        .map(IdNamespace.OpenAlexAuthor -> _)
    )
    (structural ++ nativeKey ++ extra ++ authorOrcids ++ authorOpenAlex).distinct
      .flatMap { case (namespace, raw) =>
        ExactIdentifiers.normalize(namespace, raw).map { canonical =>
          ExactIdentifiers.joinKey(namespace, canonical) ->
            IdentifierClaim(
              namespace = namespace.wire,
              value = canonical,
              // A person identifier names an author, not the paper, so the
              // claim is attributed to the mention that carries it. Attributing
              // it to the work would make every coauthor's identifier look
              // like a claim about the publication.
              entityId =
                if namespace.scope == IdScope.Person then
                  work.authors
                    .find(
                      _.externalIds.get(namespace.wire).contains(canonical)
                    )
                    .map(_.mentionId)
                    .getOrElse(work.workId)
                else work.workId,
              source = work.source,
              sourceRecordId = work.sourceRecordId,
              normalizedTitle = work.normalizedTitle,
              year = work.year
            )
        }
      }

  def emit(
      work: WorkRecord,
      extra: Vector[(IdNamespace, String)],
      sink: TaskSink
  ): Unit =
    val emitted = claims(work, extra)
    if emitted.isEmpty then sink.count(PipelineCounter.MissingKeyRecords)
    emitted.foreach { case (key, claim) =>
      sink.emitRecord(TaskSink.identityLinkInputs, key, claim)
    }

  /** Reduce side: resolve one identifier's claims.
    *
    * Returns the join key together with the duplicate and conflict counts, so
    * the caller increments counters from the same computation that produced the
    * record and the two cannot drift apart.
    */
  def resolve(
      joinKey: String,
      claimed: Vector[IdentifierClaim],
      run: RunContext
  ): Option[(IdentifierJoinKey, Long, Long)] =
    ExactIdentifiers.parseJoinKey(joinKey).flatMap { case (namespace, value) =>
      val distinct = claimed.distinct
      Option.when(distinct.nonEmpty) {
        val ordered = distinct.sortBy(claim =>
          (claim.source.wire, claim.entityId, claim.normalizedTitle, claim.year)
        )
        val workScoped = namespace.scope == IdScope.Work
        val sourceCounts =
          claimed.groupBy(_.source.wire).view.mapValues(_.size).toMap
        val duplicateSources =
          if !workScoped then Vector.empty
          else
            sourceCounts
              .collect { case (source, count) if count > 1 => source }
              .toVector
              .sorted
        val duplicates =
          if !workScoped then 0L
          else
            sourceCounts.valuesIterator
              .map(count => math.max(0, count - 1).toLong)
              .sum
        val titles = ordered.map(_.normalizedTitle).filter(_.nonEmpty).distinct
        val conflictingTitles =
          if workScoped && titles.size > 1 then titles.sorted
          else Vector.empty
        val record = IdentifierJoinKey(
          schemaVersion = ContractSchema.currentVersion,
          namespace = namespace.wire,
          value = value,
          joinKey = joinKey,
          entityIds = ordered.map(_.entityId).distinct,
          sources = ordered.map(_.source.wire).distinct.sorted,
          duplicateSources = duplicateSources,
          conflictingTitles = conflictingTitles,
          provenance = run.provenance(
            SourceType.Derived,
            joinKey,
            s"scholarchain:identifier/${namespace.wire}/$value",
            TaskSink.sourceDataset(ordered.head.source)
          )
        )
        (
          record,
          duplicates,
          // Disagreements, not disagreeing values: three titles that differ are
          // two conflicts, not three. One title is zero.
          math.max(0L, conflictingTitles.size.toLong - 1L)
        )
      }
    }
