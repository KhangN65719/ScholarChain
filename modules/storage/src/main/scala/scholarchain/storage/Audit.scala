package scholarchain.storage

import scholarchain.core.EvidenceBundle

/** One audited query turn: the opaque JSON the runtime wants kept, and the
  * evidence bundle that backs the answer, when there is one.
  */
final case class AuditRecord(
    sessionId: String,
    ordinal: Int,
    payloadJson: String,
    bundle: Option[EvidenceBundle],
    queryId: String,
    runId: String,
    ttlEpochSeconds: Long
)

final case class AuditOutcome(turn: WriteOutcome, bundle: Option[WriteOutcome])

/** Typed audit write over the existing `QuerySession` and `EvidenceBundleTable`
  * writers. The bundle is stored first, so a stored turn never points to a
  * bundle that is missing. Both writes are idempotent: repeating a record
  * changes nothing, and a different payload or bundle under the same key fails
  * with `ConditionalCheckFailed`. A turn that fails after its bundle was stored
  * leaves the bundle in place, which is harmless: bundles never expire.
  */
object AuditTrail:
  val MaxOrdinal = 999999

  def record(
      sessions: DynamoTable,
      bundles: DynamoTable,
      audit: AuditRecord
  ): AuditOutcome =
    Fail.unless(
      audit.sessionId.nonEmpty && audit.queryId.nonEmpty,
      ErrorCode.InvalidItem,
      "An audit record needs a session ID and a query ID"
    )
    Fail.unless(
      audit.ordinal >= 0 && audit.ordinal <= MaxOrdinal,
      ErrorCode.InvalidItem,
      s"Turn ordinal must be from 0 to $MaxOrdinal, got ${audit.ordinal}"
    )
    Fail.unless(
      audit.runId.startsWith("run:v1:"),
      ErrorCode.InvalidItem,
      s"Not a run ID: ${audit.runId}"
    )
    try ujson.read(audit.payloadJson)
    catch
      case error: Exception =>
        Fail(
          ErrorCode.InvalidItem,
          s"Audit payload is not JSON: ${error.getMessage}"
        )
    val stored = audit.bundle.map { bundle =>
      EvidenceBundleTable.store(bundles, bundle, audit.queryId, audit.runId)
    }
    val turn = QuerySession.appendTurn(
      sessions,
      audit.sessionId,
      audit.ordinal,
      audit.payloadJson,
      audit.ttlEpochSeconds
    )
    AuditOutcome(turn, stored)

  /** The audited turn, or `None`. */
  def turn(
      sessions: DynamoTable,
      sessionId: String,
      ordinal: Int
  ): Option[StoredTurn] =
    QuerySession.turn(sessions, sessionId, ordinal)

  /** The bundle stored for an audit record, or `None`. */
  def bundle(bundles: DynamoTable, bundleId: String): Option[EvidenceBundle] =
    EvidenceBundleTable.load(bundles, bundleId)
