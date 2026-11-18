package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scholarchain.evaluation.runtime.{
  CostGuard,
  CostLimits,
  GuardedEmbedder,
  GuardedModel,
  LaptopPorts,
  Rates
}
import scholarchain.rag.{
  DemoWorld,
  DeterministicStubModel,
  EmbeddingModels,
  ModelPort,
  ModelRequest,
  ModelResponse
}
import scholarchain.storage.{Embedder, HashEmbedder}

/** Shared fixtures of the Phase 16 Wave 2 independent tests. Nothing here reads
  * the implementation of the evaluation code: it builds inputs and counts what
  * reaches the services behind the guards.
  */
object P16W2Support:

  /** The repository root: the first directory upward that holds build.sbt and
    * experiments/.
    */
  lazy val repoRoot: Path =
    var dir = Paths.get("").toAbsolutePath
    while dir != null &&
      !(Files.isRegularFile(dir.resolve("build.sbt")) &&
        Files.isDirectory(dir.resolve("experiments")))
    do dir = dir.getParent
    require(dir != null, "repository root not found")
    dir

  def tempDir(prefix: String = "p16w2-"): Path =
    Files.createTempDirectory(prefix)

  def readAll(path: Path): String = Files.readString(path, UTF_8)

  def lines(path: Path): Vector[String] =
    Files.readAllLines(path, UTF_8).asScala.toVector.filter(_.trim.nonEmpty)

  def allText(dir: Path): String =
    val files =
      Files.walk(dir).iterator.asScala.filter(Files.isRegularFile(_)).toVector
    files.map(readAll).mkString("\n")

  // ---- the guard ------------------------------------------------------------

  val rates: Rates = Rates(1.0, 5.0, 0.02)

  def guard(
      haiku: Int = 1000,
      usd: Double = 1.0,
      titan: Int = 1000
  ): CostGuard = new CostGuard(CostLimits(titan, haiku, usd), rates)

  /** A model that counts what reaches it. `reply` makes the answer; the default
    * is the deterministic stub with its estimated usage.
    */
  final class SpyModel(
      reply: ModelRequest => ModelResponse = r =>
        new DeterministicStubModel().complete(r)
  ) extends ModelPort:
    val requests = mutable.ArrayBuffer.empty[ModelRequest]
    def calls: Int = synchronized(requests.size)
    def callsOf(promptId: String): Int =
      synchronized(requests.count(_.promptId == promptId))
    def complete(request: ModelRequest): ModelResponse =
      synchronized { requests += request }
      reply(request)

  /** A reply that reports `tokens` of metered usage in and out. */
  def metered(tokens: Int, raw: String = "{}"): ModelResponse =
    ModelResponse(
      raw,
      "fake",
      tokens,
      tokens,
      tokensEstimated = false,
      Some("end_turn")
    )

  final class SpyEmbedder extends Embedder:
    private val inner = new HashEmbedder
    val modelVersion: String = inner.modelVersion
    val dimension: Int = inner.dimension
    val texts = mutable.ArrayBuffer.empty[String]
    def calls: Int = synchronized(texts.size)
    def embed(text: String): Vector[Float] =
      synchronized { texts += text }
      inner.embed(text)

  /** The offline demo world with a counting embedder and the given model, both
    * behind the guard exactly as `LaptopPorts.offline` builds them.
    */
  def wiring(
      g: CostGuard,
      model: ModelPort,
      embedder: Embedder,
      modelBillable: Boolean = true
  ): LaptopPorts.Wiring =
    val world = DemoWorld.build()
    LaptopPorts.assemble(
      world.ports,
      world.config,
      g,
      embedder,
      embedderBillable = false,
      Some(world.vectorBucket),
      world.objectStore,
      EmbeddingModels.HashEmbedV1,
      model,
      modelBillable,
      ujson.Obj("mode" -> "test"),
      () => ()
    )

  def guardedModel(
      inner: ModelPort,
      g: CostGuard,
      billable: Boolean = true
  ): GuardedModel = new GuardedModel(inner, g, billable)

  def guardedEmbedder(
      inner: Embedder,
      g: CostGuard,
      billable: Boolean = true
  ): GuardedEmbedder = new GuardedEmbedder(inner, g, billable)

  // ---- a tiny benchmark over the demo world -----------------------------------

  /** Marker strings that appear only in labels, never in a question. If any of
    * them reaches the system, the text-only rule is broken.
    */
  val Markers: Vector[String] = Vector(
    "researcher:v1:orcid:ZZMARKERONE",
    "researcher:v1:orcid:ZZMARKERTWO",
    "zz-label-operation",
    "zz-accepted-outcome",
    "zz-expected-code"
  )

  /** One label line in the benchmark layout. */
  def labelLine(
      questionId: String,
      stratum: String,
      operation: String,
      items: Seq[(String, Int)] = Seq.empty,
      abstain: Boolean = false,
      abstainClass: Option[String] = None,
      accepted: Seq[String] = Seq.empty,
      codes: Seq[String] = Seq.empty,
      vectorAvailable: Option[Boolean] = Some(true)
  ): String =
    val expected = ujson.Obj(
      "items" -> ujson.Arr.from(
        items.map((id, g) => ujson.Obj("id" -> id, "grade" -> g): ujson.Value)
      ),
      "numbers" -> ujson.Obj(),
      "abstain" -> abstain,
      "abstainClass" -> abstainClass.fold[ujson.Value](ujson.Null)(
        ujson.Str(_)
      ),
      "acceptedOutcomes" -> ujson.Arr.from(accepted.map(ujson.Str(_))),
      "expectedCodes" -> ujson.Arr.from(codes.map(ujson.Str(_)))
    )
    ujson.write(
      ujson.Obj(
        "questionId" -> questionId,
        "version" -> "v1",
        "stratum" -> stratum,
        "operation" -> operation,
        "subjectIds" -> ujson.Arr(),
        "window" -> "2000-2009",
        "expected" -> expected,
        "vectorAvailable" -> vectorAvailable.fold[ujson.Value](ujson.Null)(
          ujson.Bool(_)
        )
      )
    )

  def questionLine(id: String, text: String): String =
    ujson.write(
      ujson.Obj("questionId" -> id, "version" -> "v1", "text" -> text)
    )

  /** The six questions of the demo world, with labels that carry the markers.
    * The question texts are known to run offline: a researcher lookup, a
    * coauthor lookup, a path, a path the table does not hold, an out-of-scope
    * question (a planner rule miss, so the model is asked) and an unknown
    * researcher.
    */
  val Questions: Vector[(String, String)] = Vector(
    "Q-RL-01" -> "Who is Ada Quillfeather between 2000 and 2009?",
    "Q-CA-01" ->
      "Who are the coauthors of Ada Quillfeather between 2000 and 2009?",
    "Q-SP-01" ->
      "What is the shortest path between Ada Quillfeather and Chidi Okonkwo between 2000 and 2009?",
    "Q-SP-02" ->
      "What is the shortest path between Ada Quillfeather and Gus Holloway between 2000 and 2009?",
    "Q-UN-01" -> "What is the capital of France?",
    "Q-UN-02" ->
      "Who are the coauthors of Zelda Nonexistent between 2000 and 2009?"
  )

  def writeBenchmark(dir: Path): (Path, Path) =
    Files.createDirectories(dir)
    val q = dir.resolve("questions-v1.jsonl")
    val l = dir.resolve("labels-v1.jsonl")
    Files.writeString(
      q,
      Questions.map(questionLine).mkString("", "\n", "\n"),
      UTF_8
    )
    val labels = Vector(
      labelLine(
        "Q-RL-01",
        "researcher-lookup",
        "zz-label-operation",
        Seq(("researcher:v1:orcid:ZZMARKERONE", 2))
      ),
      labelLine(
        "Q-CA-01",
        "coauthor-lookup",
        "zz-label-operation",
        Seq(
          ("researcher:v1:orcid:ZZMARKERONE", 3),
          ("researcher:v1:orcid:ZZMARKERTWO", 1)
        )
      ),
      labelLine(
        "Q-SP-01",
        "shortest-path",
        "zz-label-operation",
        Seq(("researcher:v1:orcid:ZZMARKERTWO", 1))
      ),
      labelLine(
        "Q-SP-02",
        "shortest-path",
        "zz-label-operation",
        accepted = Seq("path-status:NotPrecomputed", "abstain")
      ),
      labelLine(
        "Q-UN-01",
        "unanswerable",
        "zz-label-operation",
        abstain = true,
        abstainClass = Some("out-of-scope"),
        codes = Seq("planner-refused"),
        vectorAvailable = None
      ),
      labelLine(
        "Q-UN-02",
        "unanswerable",
        "zz-label-operation",
        abstain = true,
        abstainClass = Some("unknown-researcher"),
        codes = Seq("identity-unresolved"),
        vectorAvailable = None
      )
    )
    Files.writeString(l, labels.mkString("", "\n", "\n"), UTF_8)
    (q, l)
