package scholarchain.hadoop

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import org.apache.hadoop.conf.Configuration
import scholarchain.hadoop.jobs.{JobConfig, PipelineTool}
import scholarchain.hadoop.pipeline.RunContext

/** Phase 5 review finding 10. The command-line tool must not silently drop an
  * argument, give an unknown dataset an empty expectation, or fall back to the
  * default snapshot ids without saying so.
  */
class PipelineToolArgumentsSuite extends FunSuite:
  private final class Captured:
    private val outBytes = new ByteArrayOutputStream
    private val errBytes = new ByteArrayOutputStream
    val out = new PrintStream(outBytes, true, UTF_8)
    val err = new PrintStream(errBytes, true, UTF_8)
    def stdout: String = outBytes.toString(UTF_8)
    def stderr: String = errBytes.toString(UTF_8)

  private val pinned =
    "dblp=2026-11-05,openalex=2026-11-07,crossref=2026-11-07,orcid=2026-11-07"

  private def configuration(snapshots: Option[String]): Configuration =
    val conf = new Configuration(false)
    snapshots.foreach(conf.set(JobConfig.snapshots, _))
    conf

  private val arguments =
    Array("out", "dblp.xml", "openalex=o.jsonl", "crossref=c.jsonl")

  test("dataset=path arguments are parsed and grouped") {
    assertEquals(
      PipelineTool.parseExternal(
        Vector("openalex=a", "openalex=b", "orcid=c")
      ),
      Map("openalex" -> Vector("a", "b"), "orcid" -> Vector("c"))
    )
  }

  test("an argument without = is an error that names it") {
    val error = intercept[IllegalArgumentException] {
      PipelineTool.parseExternal(Vector("openalex=a", "crossref.jsonl"))
    }
    assert(error.getMessage.contains("crossref.jsonl"), error.getMessage)
  }

  test("an empty dataset or an empty path is an error that names it") {
    Vector("=path", "openalex=", " =x").foreach { argument =>
      val error = intercept[IllegalArgumentException] {
        PipelineTool.parseExternal(Vector(argument))
      }
      assert(error.getMessage.contains(argument), error.getMessage)
    }
  }

  test("an unknown dataset name is an error that names it") {
    val error = intercept[IllegalArgumentException] {
      PipelineTool.parseExternal(Vector("openalex=a", "scopus=b"))
    }
    assert(error.getMessage.contains("scopus"), error.getMessage)
    assert(error.getMessage.contains("openalex"), "valid names are listed")
  }

  test("the tool run returns a failure code for a bad argument") {
    val tool = new PipelineTool
    tool.setConf(configuration(Some(pinned)))
    assertEquals(
      tool.run(Array("out", "dblp.xml", "openalex=o", "stray")),
      2
    )
    assertEquals(tool.run(Array("out", "dblp.xml", "scopus=x")), 2)
    assertEquals(tool.run(Array("only-one")), 2)
  }

  test("absent snapshots print a WARNING with the defaults to both streams") {
    val captured = new Captured
    val plan = PipelineTool.plan(
      configuration(None),
      arguments,
      captured.out,
      captured.err
    )
    assertEquals(plan.context.datasetSnapshots, RunContext.localSnapshots)
    assertEquals(plan.warnings.size, 1)
    Vector(captured.stdout, captured.stderr).foreach { text =>
      assert(text.contains("WARNING"), text)
      assert(text.contains(JobConfig.snapshots), text)
      RunContext.localSnapshots.foreach { case (dataset, id) =>
        assert(text.contains(s"$dataset=$id"), text)
      }
    }
    assert(captured.stderr.linesIterator.exists(_.startsWith("WARNING")))
  }

  test("a blank snapshot setting is treated as absent and warns") {
    val captured = new Captured
    val plan = PipelineTool.plan(
      configuration(Some("  ")),
      arguments,
      captured.out,
      captured.err
    )
    assertEquals(plan.warnings.size, 1)
    assert(captured.stderr.contains("WARNING"), captured.stderr)
  }

  test("explicit snapshots print no warning and are used") {
    val captured = new Captured
    val plan = PipelineTool.plan(
      configuration(Some("dblp=2026-11-05,openalex=2026-11-07,crossref=x")),
      arguments,
      captured.out,
      captured.err
    )
    assertEquals(plan.warnings, Vector.empty[String])
    assert(!captured.stderr.contains("WARNING"), captured.stderr)
    assert(!captured.stdout.contains("WARNING"), captured.stdout)
    assertEquals(plan.context.snapshotOf("crossref"), "x")
    assert(captured.stdout.contains("openalex=2026-11-07"), captured.stdout)
  }

  test("the stdout summary names the effective snapshots and the datasets") {
    val captured = new Captured
    PipelineTool.plan(
      configuration(Some(pinned)),
      Array("out", "dblp.xml", "openalex=o", "crossref=c", "orcid=r"),
      captured.out,
      captured.err
    )
    assert(captured.stdout.contains("dblp=2026-11-05"), captured.stdout)
    assert(captured.stdout.contains("crossref"), captured.stdout)
  }
