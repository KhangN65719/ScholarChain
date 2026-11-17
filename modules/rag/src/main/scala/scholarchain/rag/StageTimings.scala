package scholarchain.rag

/** The six stages of one answer that are timed. Retrieval is split by retriever
  * so a mode that skips one can say so.
  *
  *   - `Planning`: the planner and the resolution of researcher names.
  *   - `GraphRetrieval`: metric, path, community and ranking reads.
  *   - `VectorRetrieval`: embedding the query and the vector index reads.
  *   - `Fusion`: assembling the evidence bundle from what retrieval returned
  *     and deciding whether it is enough to answer.
  *   - `Synthesis`: the model call that writes the claims.
  *   - `Verification`: the claim verifier.
  */
enum PipelineStage(val wire: String, val logKey: String):
  case Planning extends PipelineStage("planning", "planningMs")
  case GraphRetrieval
      extends PipelineStage("graph-retrieval", "graphRetrievalMs")
  case VectorRetrieval
      extends PipelineStage("vector-retrieval", "vectorRetrievalMs")
  case Fusion extends PipelineStage("fusion", "fusionMs")
  case Synthesis extends PipelineStage("synthesis", "synthesisMs")
  case Verification extends PipelineStage("verification", "verificationMs")

/** One stage's outcome. A stage that did not run (a retriever the mode skips,
  * or a stage after an early abstention) is `Skipped`, never `Ran(0)`: zero
  * milliseconds is a measurement, and a skipped stage was not measured.
  */
enum StageTiming:
  case Ran(millis: Long)
  case Skipped

/** Monotonic nanosecond source for stage timing. It is separate from
  * `RuntimeClock`, whose reads the audit record and its TTL depend on, so
  * timing a stage never changes the audit.
  */
trait StageClock:
  def nanos(): Long

object StageClock:
  val system: StageClock = new StageClock:
    def nanos(): Long = System.nanoTime()

/** Per-stage timings of one answer, in milliseconds. They travel with the
  * workflow result and are not part of the core `AnswerResponse` or of the
  * audit payload.
  */
final case class StageTimings(byStage: Map[PipelineStage, StageTiming]):
  def of(stage: PipelineStage): StageTiming =
    byStage.getOrElse(stage, StageTiming.Skipped)

  /** `Some(ms)` when the stage ran, `None` when it was skipped. */
  def millis(stage: PipelineStage): Option[Long] = of(stage) match
    case StageTiming.Ran(ms) => Some(ms)
    case StageTiming.Skipped => None

  def ran(stage: PipelineStage): Boolean = millis(stage).isDefined

  /** Every stage, in pipeline order. */
  def all: List[(PipelineStage, StageTiming)] =
    PipelineStage.values.toList.map(s => s -> of(s))

  /** Log fields in pipeline order: a number of milliseconds, or `skipped`. */
  def logFields: List[(String, String)] =
    all.map { (stage, timing) =>
      stage.logKey -> (timing match
        case StageTiming.Ran(ms) => ms.toString
        case StageTiming.Skipped => "skipped")
    }

  /** A JSON object keyed by stage wire name; skipped stages are the string
    * `skipped`.
    */
  def toJson: ujson.Value =
    ujson.Obj.from(all.map { (stage, timing) =>
      stage.wire -> (timing match
        case StageTiming.Ran(ms) => ujson.Num(ms.toDouble): ujson.Value
        case StageTiming.Skipped => ujson.Str("skipped"): ujson.Value)
    })

object StageTimings:
  val empty: StageTimings = StageTimings(Map.empty)
