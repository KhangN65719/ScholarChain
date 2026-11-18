package scholarchain.storage

import java.lang.management.{ManagementFactory, MemoryType}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Measures the heap the read models of a real local tier-S export need. It
  * runs only when `SCHOLARCHAIN_S_EXPORT_DIR` names an export directory
  * (read-only, never copied); otherwise it is skipped, so `sbt quick` and
  * `sbt check` do not depend on any machine's data.
  *
  * The tables the read models need but a real export may lack (the graph
  * partition tables) are served as empty tables through an overlay manifest, so
  * the measure covers the production path over the real rows of
  * `nodes-researcher`, `nodes-work` and `researcher-metrics`.
  */
class ExportHeapMeasureSuite extends munit.FunSuite:
  private val manifestName = JsonlExportSource.ManifestName

  private def overlayManifest(
      dir: Path
  ): (Array[Byte], String, Vector[String]) =
    val manifest = ujson.read(Files.readAllBytes(dir.resolve(manifestName)))
    val tables = manifest("tables").obj
    val empty = AwsReadModels.RequiredTables.filterNot(tables.contains)
    empty.foreach { name =>
      tables(name) = ujson.Obj(
        "rows" -> 0,
        "idColumns" -> ujson.Arr(),
        "columns" -> ujson.Arr(),
        "sourceFiles" -> ujson.Arr(),
        "files" -> ujson.Arr(
          ujson.Obj(
            "path" -> emptyPart(name),
            "rows" -> 0,
            "bytes" -> 0,
            "sha256" -> Hashing.sha256(Array.emptyByteArray)
          )
        )
      )
    }
    (
      ujson.write(manifest).getBytes(UTF_8),
      manifest("sourceRunId").str,
      empty
    )

  private def emptyPart(table: String): String = s"$table/part-00000.jsonl"

  private def collect(): Unit =
    (1 to 3).foreach { _ =>
      System.gc()
      Thread.sleep(50)
    }

  private def used(): Long =
    val rt = Runtime.getRuntime
    rt.totalMemory - rt.freeMemory

  test("heap and time to load the real tier-S export") {
    val dirName = sys.env.get("SCHOLARCHAIN_S_EXPORT_DIR")
    assume(dirName.isDefined, "SCHOLARCHAIN_S_EXPORT_DIR is not set")
    val dir = Path.of(dirName.get).toAbsolutePath.normalize()
    val (manifest, runId, overlay) = overlayManifest(dir)
    // A local directory has no pipeline slug; the slug the real run ID maps to
    // stands in, and only names the key prefix of this in-memory store.
    val slug = AwsReadModels.slugOf(runId)
    val store = new LocalExportStore(
      dir,
      AwsReadModels.exportPrefixOf(slug),
      Some(manifest),
      overlay.map(emptyPart)
    )
    val heapPools = ManagementFactory.getMemoryPoolMXBeans.asScala
      .filter(_.getType == MemoryType.HEAP)
    collect()
    val before = used()
    heapPools.foreach(_.resetPeakUsage())
    val started = System.nanoTime
    val loaded =
      AwsReadModels.fromExport(store, runId, exportRunId = Some(slug))
    val millis = (System.nanoTime - started) / 1000000L
    val peakPools = heapPools.map(_.getPeakUsage.getUsed).sum
    collect()
    val retained = used() - before
    java.lang.ref.Reference.reachabilityFence(loaded)
    def mib(n: Long): String = f"${n / 1048576.0}%.1f MiB"
    println(
      s"HEAP-MEASURE load=${millis} ms, retained after GC=${mib(retained)}, " +
        s"peak (sum of pool peaks, an upper bound)=${mib(peakPools)}, " +
        s"max heap=${mib(Runtime.getRuntime.maxMemory)}, " +
        s"objects=${loaded.summary.objects}, " +
        s"bytes loaded=${loaded.summary.bytes}, " +
        s"skipped tables=${loaded.summary.skippedTables.mkString(",")}, " +
        s"skipped bytes=${loaded.summary.skippedBytes}, " +
        s"rows=${loaded.summary.tables.map(t => s"${t.table}:${t.rows}").mkString(",")}"
    )
    assert(loaded.metrics.nonEmpty)
  }
