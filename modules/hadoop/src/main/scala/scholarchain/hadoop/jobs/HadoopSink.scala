package scholarchain.hadoop.jobs

import org.apache.hadoop.io.{NullWritable, Text}
import org.apache.hadoop.mapreduce.TaskInputOutputContext
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
import scholarchain.core.{ContractSchema, ContractWire}
import scholarchain.hadoop.pipeline.{
  Artifact,
  PipelineCounter,
  RejectRecord,
  StreamCounter,
  StreamKey,
  TaskSink
}

/** Binds [[TaskSink]] to a live Hadoop task.
  *
  * One instance belongs to one task attempt and is created in `setup` and
  * closed in `cleanup`. Nothing is static and nothing is shared, which is the
  * specification's "no mutable shared state across map or reduce tasks"
  * requirement: two attempts of the same task write through two independent
  * sinks into two independent attempt directories, and the output committer
  * promotes exactly one.
  */
final class MapOutputSink[KEYIN, VALUEIN](
    context: TaskInputOutputContext[KEYIN, VALUEIN, Text, Text]
) extends TaskSink:
  private val outKey = new Text
  private val outValue = new Text

  def emit(label: String, key: String, value: String): Unit =
    // The label rides on the key so a reducer can route by stream while the
    // partitioner still groups on the logical key. See `StreamKey`.
    outKey.set(StreamKey.encode(label, key))
    outValue.set(value)
    context.write(outKey, outValue)

  /** A map-detected reject travels the shuffle to its stage's reducer, which
    * commits it through [[ArtifactSink.commitReject]].
    *
    * A mapper cannot commit it directly: the reject named output is registered
    * with `NullWritable` keys while a mapper's output key is `Text`, so writing
    * it from here would mean either a mistyped `MultipleOutputs` or a reject
    * line prefixed with its own key. Routing it through the shuffle costs one
    * extra record per reject — rare by construction — and keeps every committed
    * reject under the stage's single reject prefix.
    *
    * The count happens here, at the point of detection, and must not be
    * repeated by the reducer: one Hadoop counter spans both phases.
    */
  def reject(record: RejectRecord): Unit =
    count(PipelineCounter.RejectedRecords)
    outKey.set(StreamKey.encode(RejectRecord.stream, record.reason.code))
    outValue.set(ContractWire.encode(record))
    context.write(outKey, outValue)

  def count(counter: PipelineCounter, delta: Long = 1L): Unit =
    context.getCounter(counter.group, counter.name).increment(delta)

/** Writes final artifacts to their own named outputs, each under its own
  * versioned path, and rejects to the versioned reject path.
  *
  * `MultipleOutputs` is used rather than one flat output so that Spark can read
  * a single artifact class without filtering, and so a reject never mingles
  * with an accepted record.
  */
final class ArtifactSink[KEYIN, VALUEIN](
    context: TaskInputOutputContext[KEYIN, VALUEIN, NullWritable, Text],
    artifacts: Map[String, Artifact],
    stage: String
) extends TaskSink:
  private val outputs =
    new MultipleOutputs[NullWritable, Text](context)
  private val outValue = new Text
  private val schemaSegment = s"v${ContractSchema.currentVersionNumber}"

  def emit(label: String, key: String, value: String): Unit =
    val artifact = artifacts.getOrElse(
      label,
      throw new IllegalStateException(
        s"Stage $stage emitted the unregistered stream '$label'"
      )
    )
    require(
      artifact.owningStage == stage,
      s"Stage $stage may not write ${artifact.segment}, owned by ${artifact.owningStage}"
    )
    outValue.set(value)
    // Relative to the job's output directory, which is this stage's directory,
    // so the committed path matches `RunContext.artifactPath`.
    outputs.write(
      artifact.namedOutput,
      NullWritable.get(),
      outValue,
      s"${artifact.segment}/$schemaSegment/part"
    )
    context.getCounter(StreamCounter.group, label).increment(1L)

  def reject(record: RejectRecord): Unit =
    count(PipelineCounter.RejectedRecords)
    commitReject(record.reason.code, ContractWire.encode(record))

  /** Commits an already-counted reject that a mapper detected.
    *
    * Every reducer routes the reject stream here before interpreting its own
    * values, because a reject's JSON is a `RejectRecord` and would not decode
    * as whatever that stage's reducer expects. It deliberately does **not**
    * increment `RejectedRecords`: the mapper counted it at detection, and one
    * Hadoop counter spans map and reduce.
    */
  def commitReject(reasonCode: String, encoded: String): Unit =
    outValue.set(encoded)
    outputs.write(
      RejectRecord.stream,
      NullWritable.get(),
      outValue,
      s"${RejectRecord.stream}/$schemaSegment/$reasonCode/part"
    )

  def count(counter: PipelineCounter, delta: Long = 1L): Unit =
    context.getCounter(counter.group, counter.name).increment(delta)

  def close(): Unit = outputs.close()

// `StreamKey` lives in the pipeline package so the Hadoop layer, the local
// runner and the tests share one definition of the shuffle key encoding.
