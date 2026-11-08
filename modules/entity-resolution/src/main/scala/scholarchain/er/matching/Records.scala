package scholarchain.er.matching

import java.time.Instant
import scholarchain.core.{
  AuthorMention,
  ContractSchema,
  MatchDecision,
  Provenance,
  SourceType,
  WorkRecord
}
import scholarchain.core.pairing.BlockKind
import upickle.default.{ReadWriter, readwriter}

/** The route by which a decision was reached, reported per path in evaluation.
  */
enum MatchPath(val wire: String):
  case ExactId extends MatchPath("exact-id")
  case DeterministicBibliographic
      extends MatchPath("deterministic-bibliographic")
  case Probabilistic extends MatchPath("probabilistic")
  case LlmAdjudicated extends MatchPath("llm-adjudicated")

object MatchPath:
  given ReadWriter[MatchPath] = readwriter[String].bimap[MatchPath](
    _.wire,
    wire =>
      values
        .find(_.wire == wire)
        .getOrElse(
          throw new IllegalArgumentException(s"Unknown match path: $wire")
        )
  )

/** One side of a pair, reduced to what matching reads. Original characters are
  * kept; folding happens only when a key is built from these fields.
  *
  * `workDoi` is the normalized DOI of the work a mention belongs to (empty for
  * a work record, whose DOI is among its identifiers). With `workArxiv`, the
  * normalized arXiv ID of the work (from the arXiv field or the arXiv-prefixed
  * DOI form), it is the only link that survives across sources, because
  * `workId` is minted per source record.
  */
final case class SideRecord(
    id: String,
    kind: EntityKind,
    source: SourceType,
    sourceRecordId: String,
    sourceUri: String,
    snapshotId: String,
    workId: String,
    name: String,
    title: String,
    year: Option[Int],
    venue: Option[String],
    affiliations: Vector[String],
    authorNames: Vector[String],
    authorPosition: Option[Int],
    externalIds: Map[String, String],
    links: Vector[String],
    citationNeighbors: Vector[String],
    workDoi: Option[String] = None,
    workArxiv: Option[String] = None
) derives ReadWriter:
  require(id.trim.nonEmpty, "A side record needs an ID")
  require(
    sourceRecordId.trim.nonEmpty,
    "A side record needs a source record ID"
  )
  require(snapshotId.trim.nonEmpty, "A side record needs a snapshot ID")

  /** The other authors of the same work, in order, for a mention. */
  def coauthors: Vector[String] =
    authorPosition match
      case Some(position)
          if kind == EntityKind.Researcher && position >= 1 &&
            position <= authorNames.size =>
        authorNames.patch(position - 1, Nil, 1)
      case _ => authorNames

  def identifiers: IdSet = Identifiers.collect(kind, externalIds, links)

  /** The record with its set-like fields in a fixed order, used for hashing. */
  def canonical: SideRecord =
    copy(citationNeighbors = citationNeighbors.distinct.sorted)

object SideRecord:
  def fromMention(
      mention: AuthorMention,
      work: WorkRecord,
      citations: Map[String, Set[String]] = Map.empty
  ): SideRecord =
    SideRecord(
      id = mention.mentionId,
      kind = EntityKind.Researcher,
      source = work.source,
      sourceRecordId = mention.provenance.sourceRecordId,
      sourceUri = mention.provenance.sourceUri,
      snapshotId = mention.provenance.snapshotId,
      workId = work.workId,
      name = mention.name,
      title = work.title,
      year = work.year,
      venue = work.venueId,
      affiliations = mention.affiliations,
      authorNames = work.authors.map(_.name),
      authorPosition = Some(mention.authorPosition),
      externalIds = mention.externalIds,
      links = Vector.empty,
      citationNeighbors =
        citations.getOrElse(work.workId, Set.empty).toVector.sorted,
      workDoi = work.doi.flatMap(Identifiers.workDoi),
      workArxiv = Identifiers.workArxiv(work.arxivId, work.doi)
    )

  def fromWork(
      work: WorkRecord,
      citations: Map[String, Set[String]] = Map.empty
  ): SideRecord =
    SideRecord(
      id = work.workId,
      kind = EntityKind.Work,
      source = work.source,
      sourceRecordId = work.provenance.sourceRecordId,
      sourceUri = work.provenance.sourceUri,
      snapshotId = work.provenance.snapshotId,
      workId = work.workId,
      name = "",
      title = work.title,
      year = work.year,
      venue = work.venueId,
      affiliations = work.authors.flatMap(_.affiliations).distinct,
      authorNames = work.authors.map(_.name),
      authorPosition = None,
      externalIds = work.doi.map("doi" -> _).toMap ++
        work.arxivId.map("arxiv" -> _).toMap,
      links = work.electronicEditionUris ++ work.sourceUris,
      citationNeighbors =
        citations.getOrElse(work.workId, Set.empty).toVector.sorted
    )

/** Resolves candidate endpoint IDs to the records they name. */
final class RecordIndex(records: Map[String, SideRecord]):
  def get(id: String): Option[SideRecord] = records.get(id)
  def size: Int = records.size

object RecordIndex:
  def fromCore(
      works: Iterable[WorkRecord],
      citations: Map[String, Set[String]] = Map.empty
  ): RecordIndex =
    val entries = works.iterator.flatMap { work =>
      Iterator(work.workId -> SideRecord.fromWork(work, citations)) ++
        work.authors.iterator.map(mention =>
          mention.mentionId -> SideRecord.fromMention(mention, work, citations)
        )
    }
    RecordIndex(entries.toMap)

extension (kind: BlockKind)
  /** The core candidate's block kind as this module's entity kind. */
  def entityKind: EntityKind =
    kind match
      case BlockKind.Researcher => EntityKind.Researcher
      case BlockKind.Work       => EntityKind.Work

/** A pair to decide. The endpoints are stored in ascending ID order so the
  * decision cannot depend on which side was listed first.
  */
final case class MatchInput(
    left: SideRecord,
    right: SideRecord,
    blockKey: String
):
  require(left.kind == right.kind, "A pair compares one kind of entity")
  require(left.id < right.id, "Pair endpoints must be in ascending ID order")
  def kind: EntityKind = left.kind

object MatchInput:
  def of(first: SideRecord, second: SideRecord, blockKey: String): MatchInput =
    require(first.id != second.id, "A pair needs two distinct records")
    if first.id < second.id then MatchInput(first, second, blockKey)
    else MatchInput(second, first, blockKey)

/** A matcher output: the core `MatchDecision` plus the entity kind and the
  * match path that evaluation groups by. `decision.confidence` is the match
  * score in [0, 1] whatever the outcome, so a rejection carries a low value.
  */
final case class ResolutionRecord(
    schemaVersion: ContractSchema.Version,
    kind: EntityKind,
    path: MatchPath,
    leftSourceRecordId: String,
    rightSourceRecordId: String,
    decision: MatchDecision
) derives ReadWriter:
  require(
    schemaVersion == ContractSchema.currentVersion,
    "Unsupported resolution record schema version"
  )
  def leftId: String = decision.sourceId
  def rightId: String = decision.targetId
  def score: Double = decision.confidence

/** Run context stamped on every decision. */
final case class RunInfo(runId: String, decidedAt: Instant):
  def decidedAtText: String = decidedAt.toString
