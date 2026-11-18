package scholarchain.evaluation.runtime

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.evaluation.io.Digests
import scholarchain.rag.{CollaborationWorkflow, RetrievalMode, Session}

/** The live wiring over a local export, with no vectors and the stub model, so
  * nothing reaches AWS. The export is the smallest one the loader accepts:
  * every table the read models and the graph projector read, with no rows. It
  * proves the path export, `LocalExportStore`, `AwsReadModels.fromExport`,
  * projector and `InMemoryNeptune` end to end. It does not prove it on real
  * data.
  */
class LaptopLiveSuite extends FunSuite:
  private val RunId = "run:v1:12345678-1234-4234-8234-123456789012"

  private val Tables = Vector(
    "nodes-researcher",
    "nodes-work",
    "researcher-metrics",
    "path-evidence",
    "components",
    "component-membership",
    "communities",
    "community-membership",
    "community-cross-edges",
    "nodes-venue",
    "nodes-topic",
    "nodes-institution",
    "edges-authored",
    "edges-published-in",
    "edges-coauthored",
    "edge-support"
  )

  private def writeEmptyExport(dir: Path): Path =
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("_SUCCESS"), "", UTF_8)
    val empty = Digests.sha256("")
    Tables.foreach { t =>
      Files.createDirectories(dir.resolve(t))
      Files.writeString(dir.resolve(t).resolve("part-00000.jsonl"), "", UTF_8)
    }
    val tables = ujson.Obj.from(Tables.map { t =>
      t -> (ujson.Obj(
        "rows" -> 0,
        "files" -> ujson.Arr(
          ujson.Obj(
            "path" -> s"$t/part-00000.jsonl",
            "rows" -> 0,
            "bytes" -> 0,
            "sha256" -> empty
          )
        )
      ): ujson.Value)
    })
    Files.writeString(
      dir.resolve("export-manifest.json"),
      ujson.write(
        ujson.Obj(
          "manifestType" -> "scholarchain.spark.jsonl-export-manifest/1",
          "sourceSuccessSeen" -> true,
          "sourceRunId" -> RunId,
          "transformVersion" -> "spark-analytics/test",
          "exportVersion" -> "1",
          "tables" -> tables
        )
      ),
      UTF_8
    )
    dir

  test(
    "the live wiring loads a local export and answers through the real ports"
  ) {
    val dir = writeEmptyExport(Files.createTempDirectory("laptop-live-"))
    val guard = new CostGuard(CostLimits(10, 10, 1.0), Rates.Zero)
    val wiring = LaptopPorts.live(
      LaptopPorts.LiveSettings(
        dir,
        "us-east-2",
        "",
        "",
        None,
        useVectors = false
      ),
      guard
    )
    try
      assertEquals(wiring.config.graphRunId, RunId)
      assertEquals(wiring.description("mode").str, "live")
      assertEquals(wiring.description("vectors").bool, false)
      assertEquals(wiring.ports.semantic, None)
      assertEquals(wiring.recorder, None)
      val workflow = new CollaborationWorkflow(wiring.ports, wiring.config)
      val result = workflow.answer(
        "Who is Ada Quillfeather between 2000 and 2009?",
        Session("live", 1),
        RetrievalMode.Graph
      )
      // The export holds no researcher, so the directory cannot resolve the
      // name: the answer abstains and says why, through the real read models.
      assert(result.abstained)
      assertEquals(result.abstentionReason, Some("identity-unresolved"))
      val text = ujson.write(wiring.description)
      assert(!text.toLowerCase.contains("bucket"))
    finally wiring.close()
  }

  test(
    "a live run is refused for a region other than us-east-2 before any read"
  ) {
    intercept[IllegalArgumentException](
      LaptopPorts.LiveSettings(
        Files.createTempDirectory("laptop-live-none-"),
        "us-west-2",
        "",
        "",
        None,
        useVectors = false
      )
    )
  }

  test("an export without a manifest is refused") {
    val dir = Files.createTempDirectory("laptop-live-bad-")
    val guard = new CostGuard(CostLimits(10, 10, 1.0), Rates.Zero)
    intercept[Exception](
      LaptopPorts.live(
        LaptopPorts
          .LiveSettings(dir, "us-east-2", "", "", None, useVectors = false),
        guard
      )
    )
  }
