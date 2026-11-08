package scholarchain.hadoop.pipeline

import scholarchain.core.{ContractSchema, Provenance, SourceType}
import upickle.default.ReadWriter

/** The counter inventory required by the specification.
  *
  * Counters are declared once here so the Hadoop jobs and the local bounded
  * runner report the same names, and so a counter cannot be introduced by a
  * string literal that nothing else knows about.
  */
enum PipelineCounter(val group: String):
  case InputRecords extends PipelineCounter("scholarchain.input")
  case AcceptedRecords extends PipelineCounter("scholarchain.outcome")
  case RejectedRecords extends PipelineCounter("scholarchain.outcome")
  case DuplicateRecords extends PipelineCounter("scholarchain.outcome")
  case ConflictRecords extends PipelineCounter("scholarchain.outcome")
  case OversizeBlocks extends PipelineCounter("scholarchain.bounds")
  case OversizeAuthorTeams extends PipelineCounter("scholarchain.bounds")

  /** Pairs expanded on the **map** side, before consolidation. */
  case ExpandedPairs extends PipelineCounter("scholarchain.pairs")

  /** Pairs written to the final artifact, counted on the **reduce** side only.
    *
    * Kept distinct from [[ExpandedPairs]] because a Hadoop counter accumulates
    * across both phases of one job: incrementing the same counter in the mapper
    * and the reducer reports double the pairs that actually exist.
    */
  case EmittedPairs extends PipelineCounter("scholarchain.pairs")
  case SuppressedPairs extends PipelineCounter("scholarchain.pairs")

  /** Coauthor-context blocking keys withheld because one work's fan-out
    * exceeded the per-work quadratic bound.
    */
  case SuppressedBlockingKeys extends PipelineCounter("scholarchain.bounds")
  case MissingKeyRecords extends PipelineCounter("scholarchain.quality")
  case SchemaFailureRecords extends PipelineCounter("scholarchain.quality")
  case SingleAuthorWorks extends PipelineCounter("scholarchain.pairs")

  /** Accepted map-output records before consolidation; reducers do not count
    * this again. Final pairs have the separate `EmittedPairs` counter.
    */
  case EmittedRecords extends PipelineCounter("scholarchain.output")

  def name: String = toString

/** Why a record was rejected. The code is part of the reject record, so a
  * downstream reader can triage without parsing a message string.
  */
enum RejectReason(val code: String):
  case MalformedXml extends RejectReason("MALFORMED_XML")
  case MalformedJson extends RejectReason("MALFORMED_JSON")
  case UnsupportedRecordType extends RejectReason("UNSUPPORTED_RECORD_TYPE")
  case MissingRequiredField extends RejectReason("MISSING_REQUIRED_FIELD")
  case MissingJoinKey extends RejectReason("MISSING_JOIN_KEY")
  case SchemaViolation extends RejectReason("SCHEMA_VIOLATION")
  case IdentifierConflict extends RejectReason("IDENTIFIER_CONFLICT")
  case OversizeBlock extends RejectReason("OVERSIZE_BLOCK")
  case OversizeAuthorTeam extends RejectReason("OVERSIZE_AUTHOR_TEAM")

  /** One work's coauthor-context blocking fan-out exceeded the per-work bound.
    *
    * Distinct from [[OversizeAuthorTeam]] on purpose: reusing one code for two
    * reject classes is the F13b defect, and it would put two unrelated causes
    * in one reject directory.
    */
  case OversizeCoauthorFanOut extends RejectReason("OVERSIZE_COAUTHOR_FAN_OUT")

object RejectReason:
  given ReadWriter[RejectReason] =
    upickle.default
      .readwriter[String]
      .bimap[RejectReason](
        _.code,
        code =>
          values
            .find(_.code == code)
            .getOrElse(
              throw new IllegalArgumentException(s"Unknown reject code: $code")
            )
      )

/** A rejected input, retained with enough context to reproduce the failure.
  *
  * The payload is truncated rather than dropped: the reject output exists to be
  * read by a human, and an unbounded copy of a pathological record would defeat
  * the bound the rejecting job was enforcing.
  */
final case class RejectRecord(
    schemaVersion: ContractSchema.Version,
    stage: String,
    reason: RejectReason,
    detail: String,
    sourceRecordId: String,
    payloadExcerpt: String,
    provenance: Provenance
) derives ReadWriter:
  // `ContractSchema.requireSupported` is core-private, so the same invariant is
  // asserted here against the public current version value.
  require(
    schemaVersion == ContractSchema.currentVersion,
    "Unsupported reject schema version"
  )
  require(stage.trim.nonEmpty, "A reject must name its stage")
  require(provenance != null, "A reject must carry provenance")

object RejectRecord:
  val payloadLimit = 2048

  /** The named output stream that carries rejects. Kept apart from every
    * accepted-record stream so a reject is never read as data.
    */
  val stream = "rejects"

  def apply(
      stage: String,
      reason: RejectReason,
      detail: String,
      sourceRecordId: String,
      payload: String,
      provenance: Provenance
  ): RejectRecord =
    RejectRecord(
      schemaVersion = ContractSchema.currentVersion,
      stage = stage,
      reason = reason,
      detail = TextNormalizer.collapseWhitespace(detail).take(512),
      sourceRecordId = sourceRecordId,
      payloadExcerpt = Option(payload).getOrElse("").take(payloadLimit),
      provenance = provenance
    )

/** Everything a transformation needs from its task, and nothing more.
  *
  * The transformations are written against this interface rather than against
  * `Mapper.Context`, which is what lets the same code run under a real Hadoop
  * task and under a unit test. Implementations hold per-task state only; no
  * implementation is shared between tasks.
  */
trait TaskSink:
  /** Emits a keyed intermediate or final record. */
  def emit(label: String, key: String, value: String): Unit

  def reject(record: RejectRecord): Unit

  def count(counter: PipelineCounter, delta: Long = 1L): Unit

  final def emitRecord[A: ReadWriter](
      label: String,
      key: String,
      record: A
  ): Unit =
    emit(label, key, scholarchain.core.ContractWire.encode(record))
    count(PipelineCounter.EmittedRecords)

/** An in-memory sink for unit tests and for the local bounded runner. Each
  * instance belongs to exactly one logical task.
  */
final class RecordingSink extends TaskSink:
  private val emitted =
    scala.collection.mutable.ArrayBuffer.empty[(String, String, String)]
  private val rejected =
    scala.collection.mutable.ArrayBuffer.empty[RejectRecord]
  private val counters =
    scala.collection.mutable.LinkedHashMap.empty[PipelineCounter, Long]

  def emit(label: String, key: String, value: String): Unit =
    emitted += ((label, key, value))

  def reject(record: RejectRecord): Unit =
    rejected += record
    count(PipelineCounter.RejectedRecords)

  def count(counter: PipelineCounter, delta: Long = 1L): Unit =
    counters.updateWith(counter)(previous =>
      Some(previous.getOrElse(0L) + delta)
    )

  def records: Vector[(String, String, String)] = emitted.toVector

  def recordsLabelled(label: String): Vector[(String, String)] =
    emitted.iterator.collect {
      case (actual, key, value) if actual == label => key -> value
    }.toVector

  def rejects: Vector[RejectRecord] = rejected.toVector

  def counterValues: Map[PipelineCounter, Long] = counters.toMap

  def counterValue(counter: PipelineCounter): Long =
    counters.getOrElse(counter, 0L)

  /** Counters rendered in a stable order for reporting. */
  def counterReport: Vector[(String, Long)] =
    PipelineCounter.values.toVector
      .map(counter => counter.name -> counters.getOrElse(counter, 0L))
      .filter(_._2 != 0L)

/** Per-stream written-record counters.
  *
  * A reducer increments one counter per final record it commits, named by the
  * stream label. The driver reads these off the completed job to learn which
  * artifacts the job really wrote, so it never has to probe a directory (which
  * could not tell "legitimately empty" from "the job lost its output").
  */
object StreamCounter:
  val group = "scholarchain.streams"

object TaskSink:
  /** Intermediate and final output labels. A label names exactly one artifact
    * stream, which is what the Hadoop jobs bind to a named output.
    */
  val works = "works"
  val mentions = "author-mentions"
  val researchers = "researchers"
  val teams = "teams"
  val authorPapers = "author-papers"
  val pairs = "pair-candidates"
  val identifierKeys = "identifier-keys"
  val blockingGroups = "blocking-groups"

  val matchCandidates = "match-candidates"
  val topics = "topics"
  val institutions = "institutions"
  val venues = "venues"
  val blockingMembers = "blocking-members"

  /** Intermediate shuffle streams. These carry map output to a reducer and are
    * never written as a final artifact.
    */
  val blockingKeys = "blocking-keys"
  val identityLinkInputs = "identity-link-inputs"

  /** Every label a job may emit, used to validate that a job registered a named
    * output for each stream its transformations actually write.
    */
  val all: Vector[String] = Vector(
    works,
    mentions,
    researchers,
    teams,
    authorPapers,
    pairs,
    identifierKeys,
    blockingGroups,
    blockingMembers,
    matchCandidates,
    topics,
    institutions,
    venues
  )

  def sourceDataset(source: SourceType): String = source.wire
