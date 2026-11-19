package scholarchain.evaluation.answer

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import scholarchain.evaluation.retrieval.{BenchFixture, RetrievalEval}
import scholarchain.evaluation.runtime.{
  CostGuard,
  CostLimits,
  LaptopPorts,
  RagIdentity,
  Rates
}
import scholarchain.rag.{PlannerConfig, PromptSpec, Prompts, Schemas}

/** Review 10 finding 6: every run records the prompt and schema identity it
  * actually used, read from the rag module at run time.
  */
class RunIdentitySuite extends FunSuite:
  private def sha(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def guard() =
    new CostGuard(CostLimits(1000, 1000, 1.0), Rates(1.0, 5.0, 0.02))

  private def tempDir(): Path = Files.createTempDirectory("run-identity-")

  private def promptOf(identity: ujson.Value, id: String): ujson.Value =
    identity("prompts").arr.find(_("id").str == id).get

  private def manifestOf(runDir: Path): ujson.Value =
    ujson.read(Files.readString(runDir.resolve("run-manifest.json")))

  test("the identity holds each live prompt's version, hash and schema hash") {
    val identity = RagIdentity.json()
    assertEquals(
      identity("prompts").arr.map(_("id").str).toVector,
      Prompts.all.map(_.id)
    )
    Prompts.all.foreach { p =>
      val entry = promptOf(identity, p.id)
      assertEquals(entry("version").str, p.version)
      assertEquals(entry("sha256").str, sha(p.text))
      assertEquals(entry("schemaId").str, p.schemaId)
    }
    val synthesis = promptOf(identity, "synthesis")
    assertEquals(synthesis("version").str, "1.2.0")
    assertEquals(
      synthesis("schemaSha256").str,
      sha(ujson.write(Schemas.SynthesisOutput))
    )
    assertEquals(
      identity("schemas")(Schemas.SynthesisOutputId).str,
      synthesis("schemaSha256").str
    )
    assertEquals(identity("synthesisMaxOutputTokens").num.toInt, 4096)
    assertEquals(identity("plannerMaxLimit").num.toInt, 12)
  }

  test("a changed version, text, schema hash or bound changes the record") {
    val base = RagIdentity.json()
    val synthesis = Prompts.Synthesis
    def withSynthesis(p: PromptSpec) =
      RagIdentity.json(prompts = Vector(Prompts.Planner, p))
    val bumped = withSynthesis(synthesis.copy(version = "1.3.0"))
    assertEquals(promptOf(bumped, "synthesis")("version").str, "1.3.0")
    assertNotEquals(bumped, base)
    val edited = withSynthesis(synthesis.copy(text = synthesis.text + " x"))
    assertEquals(
      promptOf(edited, "synthesis")("sha256").str,
      sha(synthesis.text + " x")
    )
    assertNotEquals(promptOf(edited, "synthesis"), promptOf(base, "synthesis"))
    val schemaChanged = RagIdentity.json(schemaHashes =
      Schemas.hashes.updated(Schemas.SynthesisOutputId, "0" * 64)
    )
    assertEquals(
      promptOf(schemaChanged, "synthesis")("schemaSha256").str,
      "0" * 64
    )
    assertNotEquals(RagIdentity.json(synthesisMaxOutputTokens = 2000), base)
    assertNotEquals(RagIdentity.json(plannerMaxLimit = 20), base)
  }

  test("an AnswerEval run manifest carries the live identity, bound included") {
    val dir = tempDir()
    val (q, l) = BenchFixture.write(dir.resolve("bench"))
    val wiring = LaptopPorts.offline(guard())
    val narrow = wiring.copy(config =
      wiring.config.copy(planner = PlannerConfig(maxLimit = 7))
    )
    val report = AnswerEval.run(
      AnswerEval.Config(q, l, dir.resolve("out"), "run-h", repeatCount = 0),
      narrow
    )
    val identity = manifestOf(report.runDir)("ragIdentity")
    assertEquals(identity("plannerMaxLimit").num.toInt, 7)
    assertEquals(
      identity,
      RagIdentity.json(plannerMaxLimit = 7)
    )
    assertEquals(
      promptOf(identity, "synthesis")("sha256").str,
      sha(Prompts.Synthesis.text)
    )
  }

  test("a diagnostic manifest and the per-answer records carry the prompts") {
    val dir = tempDir()
    val (q, l) = BenchFixture.write(dir.resolve("bench"))
    val report = AnswerEval.run(
      AnswerEval.Config(
        q,
        l,
        dir.resolve("out"),
        "run-d",
        repeatCount = 0,
        diagnostic = true
      ),
      LaptopPorts.offline(
        new CostGuard(CostLimits(1000, 4, 0.15), Rates(1.0, 5.0, 0.02))
      )
    )
    assertEquals(
      manifestOf(report.runDir)("ragIdentity"),
      RagIdentity.json()
    )
    val lines = Files
      .readAllLines(report.runDir.resolve("diagnostic.jsonl"), UTF_8)
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(ujson.read(_))
    assert(lines.nonEmpty)
    assert(
      lines.exists(_("prompts").arr.exists(_("id").str == "synthesis")),
      "an answered question lists the synthesis prompt it used"
    )

    val full = AnswerEval.run(
      AnswerEval.Config(q, l, dir.resolve("out"), "run-f", repeatCount = 0),
      LaptopPorts.offline(guard())
    )
    val records = Files
      .readAllLines(full.runDir.resolve("answers.jsonl"), UTF_8)
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => AnswerRecord.fromJson(ujson.read(l)))
    val synthesised = records.filter(_.prompts.exists(_.id == "synthesis"))
    assert(synthesised.nonEmpty)
    synthesised.foreach { r =>
      val p = r.prompts.find(_.id == "synthesis").get
      assertEquals(p.version, Prompts.Synthesis.version)
      assertEquals(p.sha256, Prompts.Synthesis.hash)
      assertEquals(p.schemaId, Prompts.Synthesis.schemaId)
    }
  }

  test("a RetrievalEval run manifest carries the live identity") {
    val dir = tempDir()
    val (q, l) = BenchFixture.write(dir.resolve("bench"))
    val report = RetrievalEval.run(
      RetrievalEval.Config(
        q,
        l,
        dir.resolve("out"),
        "run-g",
        repeats = 1,
        resamples = 10
      ),
      LaptopPorts.offline(
        new CostGuard(CostLimits(1000, 1000, 1.0), Rates.Zero)
      )
    )
    assertEquals(manifestOf(report.runDir)("ragIdentity"), RagIdentity.json())
  }
