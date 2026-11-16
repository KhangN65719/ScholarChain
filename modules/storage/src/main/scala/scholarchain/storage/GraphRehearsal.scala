package scholarchain.storage

import java.nio.file.Path
import scala.util.control.NonFatal

/** Runs the graph phase of the AWS loader on a local export directory, with no
  * AWS call: `JsonlExportSource.open` -> `SparkGraphProjector.project` ->
  * `NeptuneExporter.exportTo`, into a `LocalFsObjectStore` under the output
  * directory. It is the same three calls as `GraphStep.run` (modules/rag) with
  * the same default bounds and scope; only the sink differs (local files, where
  * the loader uses S3) and the Neptune bulk load is not made.
  *
  * Arguments: `<exportDir> <outDir>`. It reads no environment variable and no
  * credential file, and it creates no AWS client. Run it with an explicit heap
  * (see the `!` commands in the work order report):
  * `java -Xmx6g -cp <storage runtime classpath> scholarchain.storage.GraphRehearsal`.
  *
  * Markers, one per line, `REHEARSAL_RESULT=` last:
  * `REHEARSAL_INPUT table=<t> files=<n> rows=<n> bytes=<n>` per table and one
  * `REHEARSAL_INPUT total ...`; `REHEARSAL_PHASE name=<open|project|export>
  * state=<start|done> elapsed_s=<n> took_s=<n> peak_heap_mb=<n>`;
  * `REHEARSAL_LIVE_HEAP_MB after=project value=<n>` (used heap after a forced
  * GC with the projection still referenced: the size of the projection itself);
  * `REHEARSAL_OUTPUT ...` and `REHEARSAL_OUTPUT_FILE key=<k> bytes=<n>`;
  * `REHEARSAL_PEAK_HEAP_MB=<n> max_heap_mb=<n>`; and `REHEARSAL_RESULT=SUCCESS`
  * or `REHEARSAL_RESULT=FAILED reason=<text>`.
  *
  * The peak is the largest of `totalMemory - freeMemory` samples every 200 ms
  * and at each phase boundary. It counts garbage not yet collected, so it
  * overstates the live set; whether a heap is enough is decided by the result.
  *
  * Exit code: 0 success, 1 a failed invariant or any other error, 2 a usage
  * error, 3 OutOfMemoryError (`reason=OOM maxHeapMB=<n> phase=<name>`).
  */
object GraphRehearsal:
  val Usage = "usage: GraphRehearsal <exportDir> <outDir>"
  val ExitSuccess = 0
  val ExitFailed = 1
  val ExitUsage = 2
  val ExitOom = 3

  private val Mb = 1024L * 1024L

  def usedHeapBytes(): Long =
    val rt = Runtime.getRuntime
    rt.totalMemory - rt.freeMemory

  /** Tracks the largest `used()` seen, from a daemon thread and on demand. */
  final class HeapSampler(used: () => Long, everyMillis: Long)
      extends AutoCloseable:
    @volatile private var largest = 0L
    @volatile private var running = true
    private def loop(): Unit =
      while running do
        sample()
        try Thread.sleep(everyMillis)
        catch case _: InterruptedException => running = false

    private val thread = new Thread(() => loop(), "graph-rehearsal-sampler")
    thread.setDaemon(true)
    thread.start()

    def sample(): Unit =
      val now = used()
      if now > largest then largest = now

    def peakBytes: Long =
      sample()
      largest

    def close(): Unit =
      running = false
      thread.interrupt()

  private def oneLine(text: String): String =
    text.replaceAll("\\s*[\r\n]+\\s*", " ")

  private def mb(bytes: Long): Long = bytes / Mb

  def defaultProject(tables: TableSource): GraphProjection =
    new SparkGraphProjector(tables, LoadBounds.Default).project()

  type Exporter = (GraphProjection, String, ObjectStore) => NeptuneExportReport

  /** Returns the exit code. `project` and `exporter` are the seams a test uses
    * to raise an `OutOfMemoryError` without exhausting a heap.
    */
  def run(
      args: Array[String],
      out: String => Unit,
      project: TableSource => GraphProjection = defaultProject,
      exporter: Exporter = NeptuneExporter.exportTo,
      used: () => Long = () => usedHeapBytes(),
      sampleEveryMillis: Long = 200L
  ): Int =
    if args.length != 2 then
      out(s"REHEARSAL_RESULT=FAILED reason=$Usage")
      ExitUsage
    else
      runChecked(
        Path.of(args(0)),
        Path.of(args(1)),
        out,
        project,
        exporter,
        used,
        sampleEveryMillis
      )

  private def runChecked(
      exportDir: Path,
      outDir: Path,
      out: String => Unit,
      project: TableSource => GraphProjection,
      exporter: Exporter,
      used: () => Long,
      sampleEveryMillis: Long
  ): Int =
    val began = System.nanoTime()
    def elapsed: Double = (System.nanoTime() - began) / 1e9
    val sampler = new HeapSampler(used, sampleEveryMillis)
    var current = "start"
    var phaseBegan = began
    def phase(name: String, state: String): Unit =
      sampler.sample()
      current = name
      if state == "start" then phaseBegan = System.nanoTime()
      val took = (System.nanoTime() - phaseBegan) / 1e9
      out(
        f"REHEARSAL_PHASE name=$name state=$state elapsed_s=$elapsed%.1f " +
          f"took_s=$took%.1f peak_heap_mb=${mb(sampler.peakBytes)}"
      )
    def finish(code: Int, result: String): Int =
      out(
        s"REHEARSAL_PEAK_HEAP_MB=${mb(sampler.peakBytes)} " +
          s"max_heap_mb=${mb(Runtime.getRuntime.maxMemory)}"
      )
      out(result)
      sampler.close()
      code
    def failed(reason: String): Int =
      finish(ExitFailed, s"REHEARSAL_RESULT=FAILED reason=${oneLine(reason)}")
    try
      Fail.unless(
        !outDir.toAbsolutePath.normalize
          .startsWith(exportDir.toAbsolutePath.normalize),
        ErrorCode.InvalidKey,
        "The output directory must not be inside the export directory"
      )
      phase("open", "start")
      val source = JsonlExportSource.open(exportDir)
      val stats = source.tableStats
      stats.foreach { s =>
        out(
          s"REHEARSAL_INPUT table=${s.table} files=${s.files} rows=${s.rows} " +
            s"bytes=${s.bytes}"
        )
      }
      out(
        s"REHEARSAL_INPUT total tables=${stats.size} " +
          s"files=${stats.map(_.files).sum} rows=${stats.map(_.rows).sum} " +
          s"bytes=${stats.map(_.bytes).sum}"
      )
      phase("open", "done")
      phase("project", "start")
      val projection = project(source)
      phase("project", "done")
      System.gc()
      out(s"REHEARSAL_LIVE_HEAP_MB after=project value=${mb(used())}")
      phase("export", "start")
      val store = new LocalFsObjectStore(outDir)
      val report = exporter(projection, source.sourceRun.runId, store)
      phase("export", "done")
      out(
        s"REHEARSAL_OUTPUT vertices=${report.vertexCount} " +
          s"edges=${report.edgeCount} files=${report.files.size} " +
          s"normalized_values=${report.normalizedValues} " +
          s"created=${report.created} unchanged=${report.unchanged} " +
          s"manifest=${report.manifestKey}"
      )
      store.listSizes("graph/").foreach { case (key, bytes) =>
        out(s"REHEARSAL_OUTPUT_FILE key=$key bytes=$bytes")
      }
      finish(ExitSuccess, "REHEARSAL_RESULT=SUCCESS")
    catch
      case _: OutOfMemoryError =>
        // The frames that held the data are gone, so this line can be built.
        finish(
          ExitOom,
          s"REHEARSAL_RESULT=FAILED reason=OOM " +
            s"maxHeapMB=${mb(Runtime.getRuntime.maxMemory)} phase=$current"
        )
      case error: StorageException => failed(error.getMessage)
      case NonFatal(error)         =>
        failed(s"${error.getClass.getSimpleName}: ${error.getMessage}")

  def main(args: Array[String]): Unit =
    sys.exit(run(args, println))
