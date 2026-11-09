package scholarchain.services

import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable
import scholarchain.core.{EvidenceBundle, EvidenceItem}
import scholarchain.rag.{CollaborationWorkflow, Session, WorkflowResult}
import scholarchain.storage.{
  AuditTrail,
  CoauthorContext,
  CoauthorLink,
  DynamoTable,
  NeptuneQueryPort,
  ResearcherCandidate,
  ResearcherDirectoryPort
}

// Read ports of the fast service ------------------------------------------

final case class StoredEvidence(
    item: EvidenceItem,
    queryId: String,
    bundleId: String
)

trait EvidenceReadPort:
  def find(evidenceId: String): Option[StoredEvidence]
  def ready(): Unit

final case class StoredExplain(
    queryId: String,
    payload: ujson.Value,
    bundle: Option[EvidenceBundle]
)

trait ExplainReadPort:
  def find(queryId: String): Option[StoredExplain]
  def ready(): Unit

trait GraphReadPort:
  def node(nodeId: String): Option[ResearcherCandidate]
  def coauthors(
      nodeId: String,
      window: String,
      limit: Int
  ): Vector[CoauthorLink]
  def ready(): Unit

/** The slow service reaches the Phase 9 workflow only through this port. */
trait WorkflowPort:
  def answer(question: String, session: Session): WorkflowResult
  def ready(): Unit

// Query records ---------------------------------------------------------------

/** Where an answered query can be found again: its audit turn and its bundle.
  * Phase 12 keeps this index in DynamoDB; the local fake is bounded.
  */
final case class QueryRecord(
    queryId: String,
    sessionId: String,
    ordinal: Int,
    bundleId: String,
    evidenceIds: Vector[String],
    createdAt: Instant
)

trait QueryRecordStore:
  def put(record: QueryRecord): Unit
  def byQuery(queryId: String): Option[QueryRecord]
  def byEvidence(evidenceId: String): Option[QueryRecord]
  def ready(): Unit

final class InMemoryQueryRecordStore(retained: Int) extends QueryRecordStore:
  private val records = mutable.LinkedHashMap.empty[String, QueryRecord]
  private val evidence = mutable.HashMap.empty[String, String]

  private def unindex(record: QueryRecord): Unit =
    record.evidenceIds.foreach { id =>
      if evidence.get(id).contains(record.queryId) then evidence.remove(id)
    }

  def put(record: QueryRecord): Unit = synchronized {
    records.remove(record.queryId).foreach(unindex)
    records.update(record.queryId, record)
    record.evidenceIds.foreach(evidence.update(_, record.queryId))
    while records.size > retained do
      val oldest = records.head._2
      records.remove(oldest.queryId)
      unindex(oldest)
  }

  def byQuery(queryId: String): Option[QueryRecord] = synchronized {
    records.get(queryId)
  }

  def byEvidence(evidenceId: String): Option[QueryRecord] = synchronized {
    evidence.get(evidenceId).flatMap(records.get)
  }

  def ready(): Unit = ()

// Store-backed ports --------------------------------------------------------

final class StoreEvidencePort(records: QueryRecordStore, bundles: DynamoTable)
    extends EvidenceReadPort:
  def find(evidenceId: String): Option[StoredEvidence] =
    records.byEvidence(evidenceId).flatMap { record =>
      AuditTrail.bundle(bundles, record.bundleId).flatMap { bundle =>
        bundle.items
          .find(_.evidenceId == evidenceId)
          .map(StoredEvidence(_, record.queryId, record.bundleId))
      }
    }

  def ready(): Unit =
    records.ready()
    bundles.get("BUNDLE#ready", "v1")
    ()

final class StoreExplainPort(
    records: QueryRecordStore,
    sessions: DynamoTable,
    bundles: DynamoTable
) extends ExplainReadPort:
  def find(queryId: String): Option[StoredExplain] =
    records.byQuery(queryId).flatMap { record =>
      AuditTrail.turn(sessions, record.sessionId, record.ordinal).map { turn =>
        StoredExplain(
          queryId,
          ujson.read(turn.payloadJson),
          AuditTrail.bundle(bundles, record.bundleId)
        )
      }
    }

  def ready(): Unit =
    records.ready()
    sessions.get("SESSION#ready", "TURN#000000")
    ()

object StoreGraphPort:
  /** A well-formed core ID that no data uses, for readiness probes. */
  val ProbeNode: String = "researcher:v1:" + ("0" * 64)

final class StoreGraphPort(
    directory: ResearcherDirectoryPort,
    neptune: NeptuneQueryPort
) extends GraphReadPort:
  def node(nodeId: String): Option[ResearcherCandidate] = directory.byId(nodeId)

  def coauthors(
      nodeId: String,
      window: String,
      limit: Int
  ): Vector[CoauthorLink] = CoauthorContext.of(neptune, nodeId, window, limit)

  def ready(): Unit =
    directory.byId(StoreGraphPort.ProbeNode)
    CoauthorContext.of(neptune, StoreGraphPort.ProbeNode, "all", 1)
    ()

/** The local fakes are not thread-safe, so the local workflow runs one query at
  * a time. A real adapter in Phase 12 has no such lock.
  */
final class LocalWorkflowPort(
    workflow: CollaborationWorkflow,
    probe: () => Unit
) extends WorkflowPort:
  // The wait for the lock is interruptible. A call that timed out is cancelled
  // with an interrupt, so a caller queued behind a slow query leaves the queue
  // and frees its pool thread; a plain `synchronized` ignores the interrupt and
  // keeps the thread. The query that holds the lock runs inside the rag
  // workflow, which this module cannot interrupt: it runs to its end, and the
  // lock is released then. Only a real adapter in Phase 12, with no lock and a
  // client-side timeout, removes that last case.
  private val lock = new ReentrantLock

  def answer(question: String, session: Session): WorkflowResult =
    lock.lockInterruptibly()
    try workflow.answer(question, session)
    finally lock.unlock()

  def ready(): Unit = probe()

/** A neptune port made safe for concurrent callers. */
final class SynchronizedNeptune(inner: NeptuneQueryPort)
    extends NeptuneQueryPort:
  def run(query: scholarchain.storage.CypherQuery) = synchronized {
    inner.run(query)
  }

// Fault injection -------------------------------------------------------------

enum Fault:
  case Off, Slow, Fail, Empty

final class InjectedFailure
    extends RuntimeException("injected fault", null, false, false)

/** Makes one port slow, failing or empty so that timeout, downstream failure,
  * open breaker, missing evidence and not-ready cases can be driven over HTTP.
  */
final class FaultGate(fault: Fault, slowMillis: Long):
  def apply(): Unit = fault match
    case Fault.Slow => Thread.sleep(slowMillis)
    case Fault.Fail => throw new InjectedFailure
    case _          => ()

  def empty: Boolean = fault == Fault.Empty

final class FaultyEvidence(inner: EvidenceReadPort, gate: FaultGate)
    extends EvidenceReadPort:
  def find(evidenceId: String): Option[StoredEvidence] =
    gate()
    if gate.empty then None else inner.find(evidenceId)

  def ready(): Unit =
    gate()
    inner.ready()

final class FaultyExplain(inner: ExplainReadPort, gate: FaultGate)
    extends ExplainReadPort:
  def find(queryId: String): Option[StoredExplain] =
    gate()
    if gate.empty then None else inner.find(queryId)

  def ready(): Unit =
    gate()
    inner.ready()

final class FaultyGraph(inner: GraphReadPort, gate: FaultGate)
    extends GraphReadPort:
  def node(nodeId: String): Option[ResearcherCandidate] =
    gate()
    if gate.empty then None else inner.node(nodeId)

  def coauthors(
      nodeId: String,
      window: String,
      limit: Int
  ): Vector[CoauthorLink] =
    gate()
    if gate.empty then Vector.empty else inner.coauthors(nodeId, window, limit)

  def ready(): Unit =
    gate()
    inner.ready()

final class FaultyWorkflow(inner: WorkflowPort, gate: FaultGate)
    extends WorkflowPort:
  def answer(question: String, session: Session): WorkflowResult =
    gate()
    inner.answer(question, session)

  def ready(): Unit =
    gate()
    inner.ready()

// Jobs ------------------------------------------------------------------------

enum JobState(val wire: String):
  case Queued extends JobState("queued")
  case Running extends JobState("running")
  case Succeeded extends JobState("succeeded")
  case Failed extends JobState("failed")

  def finished: Boolean = this == Succeeded || this == Failed

final case class JobError(code: ErrorCode, message: String)

final case class Job(
    jobId: String,
    state: JobState,
    createdAt: Instant,
    updatedAt: Instant,
    stage: String,
    error: Option[JobError],
    result: Option[QueryOutcome]
)

trait JobStore:
  def create(job: Job): Unit
  def get(jobId: String): Option[Job]
  def update(jobId: String)(change: Job => Job): Option[Job]
  def remove(jobId: String): Unit
  def ready(): Unit

/** Bounded: past `retained` jobs the oldest finished job is dropped. A job that
  * is still queued or running is never dropped.
  */
final class InMemoryJobStore(retained: Int) extends JobStore:
  private val jobs = mutable.LinkedHashMap.empty[String, Job]

  def create(job: Job): Unit = synchronized {
    jobs.update(job.jobId, job)
    while jobs.size > retained && jobs.exists(_._2.state.finished) do
      jobs.find(_._2.state.finished).foreach((id, _) => jobs.remove(id))
  }

  def get(jobId: String): Option[Job] = synchronized { jobs.get(jobId) }

  def remove(jobId: String): Unit = synchronized {
    jobs.remove(jobId)
    ()
  }

  def update(jobId: String)(change: Job => Job): Option[Job] = synchronized {
    jobs.get(jobId).map { job =>
      val next = change(job)
      jobs.update(jobId, next)
      next
    }
  }

  def ready(): Unit = ()

// Idempotency -----------------------------------------------------------------

sealed trait StoredResult
final case class Answered(outcome: QueryOutcome) extends StoredResult
// `replayed` is true when an idempotent repeat is handed the stored reference
// of the first request, so the response can report the job's current state.
final case class Accepted(
    jobId: String,
    createdAt: Instant,
    replayed: Boolean = false
) extends StoredResult

enum Claim:
  case Owner(key: String, future: CompletableFuture[StoredResult])
  case Replay(future: CompletableFuture[StoredResult])

trait IdempotencyPort:
  /** The first caller of a key owns it. A later caller with the same
    * fingerprint replays the owner's result. A different fingerprint is 422.
    */
  def claim(key: String, fingerprint: String): Claim
  def release(key: String, future: CompletableFuture[StoredResult]): Unit
  def ready(): Unit

final class InMemoryIdempotency(maxEntries: Int) extends IdempotencyPort:
  private final class Entry(
      val fingerprint: String,
      val future: CompletableFuture[StoredResult]
  )
  private val entries = mutable.LinkedHashMap.empty[String, Entry]

  def claim(key: String, fingerprint: String): Claim = synchronized {
    entries.get(key) match
      case Some(e) if e.fingerprint != fingerprint =>
        throw new ApiException(
          ErrorCode.IdempotencyKeyReused,
          "The Idempotency-Key was used with a different request"
        )
      case Some(e) => Claim.Replay(e.future)
      case None    =>
        if entries.size >= maxEntries then
          entries.find(_._2.future.isDone) match
            case Some((oldest, _)) => entries.remove(oldest)
            case None              =>
              throw new ApiException(
                ErrorCode.Overloaded,
                "Too many requests are in flight",
                Vector.empty,
                Map("Retry-After" -> "1")
              )
        val future = new CompletableFuture[StoredResult]
        entries.update(key, new Entry(fingerprint, future))
        Claim.Owner(key, future)
  }

  def release(key: String, future: CompletableFuture[StoredResult]): Unit =
    synchronized {
      if entries.get(key).exists(_.future eq future) then entries.remove(key)
    }

  def ready(): Unit = ()
