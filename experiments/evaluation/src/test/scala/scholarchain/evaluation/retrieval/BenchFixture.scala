package scholarchain.evaluation.retrieval

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scholarchain.core.{StableIds, YearWindow}
import scholarchain.evaluation.benchmark.*
import scholarchain.rag.DemoWorld

/** A tiny benchmark over the bundled demo world, written with the generator's
  * own `Question` and `Label` classes so the files have the real layout.
  */
object BenchFixture:
  private val ids = DemoWorld.ids
  private val early = YearWindow(2000, 2009)
  private val policy = DemoWorld.EdgePolicy

  private def edge(a: String, b: String): String =
    StableIds.coauthorEdge(a, b, early, policy)

  private def source(table: String): LabelSource =
    LabelSource(table, "run:v1:demo", None, Vector.empty)

  private def label(
      q: Question,
      subjects: Vector[String],
      expected: Expected,
      vector: Option[Boolean] = Some(true)
  ): Label =
    Label(
      q.questionId,
      q.version,
      q.stratum,
      q.stratum,
      subjects,
      Some("2000-2009"),
      Vector("2000-2009"),
      expected,
      vector,
      "available",
      0,
      0,
      source("fixture")
    )

  private def question(id: String, stratum: String, text: String): Question =
    Question(id, "v1", stratum, text)

  val Items: Vector[BenchItem] =
    val lookup = question(
      "Q-RL-01",
      "researcher-lookup",
      "Who is Ada Quillfeather between 2000 and 2009?"
    )
    val coauthors = question(
      "Q-CA-01",
      "coauthor-lookup",
      "Who are the coauthors of Ada Quillfeather between 2000 and 2009?"
    )
    val path = question(
      "Q-SP-01",
      "shortest-path",
      "What is the shortest path between Ada Quillfeather and Chidi Okonkwo between 2000 and 2009?"
    )
    val absent = question(
      "Q-SP-02",
      "shortest-path",
      "What is the shortest path between Ada Quillfeather and Gus Holloway between 2000 and 2009?"
    )
    val outOfScope =
      question("Q-UN-01", "unanswerable", "What is the capital of France?")
    val unknown = question(
      "Q-UN-02",
      "unanswerable",
      "Who are the coauthors of Zelda Nonexistent between 2000 and 2009?"
    )
    Vector(
      BenchItem(
        lookup,
        label(
          lookup,
          Vector(ids.ada),
          Expected(
            Vector(
              ExpectedItem(ids.ada, 2),
              ExpectedItem(ids.boris, 1),
              ExpectedItem(ids.fiona, 1)
            ),
            Vector.empty,
            false,
            None
          )
        )
      ),
      BenchItem(
        coauthors,
        label(
          coauthors,
          Vector(ids.ada),
          Expected(
            Vector(ExpectedItem(ids.boris, 3), ExpectedItem(ids.fiona, 1)),
            Vector.empty,
            false,
            None
          )
        )
      ),
      BenchItem(
        path,
        label(
          path,
          Vector(ids.ada, ids.chidi),
          Expected(
            Vector(
              ExpectedItem(edge(ids.ada, ids.boris), 1),
              ExpectedItem(edge(ids.boris, ids.chidi), 1)
            ),
            Vector.empty,
            false,
            None
          )
        )
      ),
      BenchItem(
        absent,
        label(
          absent,
          Vector(ids.ada, ids.gus),
          Expected(
            Vector.empty,
            Vector.empty,
            false,
            None,
            Vector(
              "pathStatus" -> ujson.Str("NotPrecomputed"),
              "acceptedOutcomes" -> ujson.Arr(
                "path-status:NotPrecomputed",
                "abstain"
              )
            )
          )
        )
      ),
      BenchItem(
        outOfScope,
        label(
          outOfScope,
          Vector.empty,
          Expected(
            Vector.empty,
            Vector.empty,
            true,
            Some("out-of-scope"),
            Vector("expectedCodes" -> ujson.Arr("planner-refused"))
          ),
          None
        )
      ),
      BenchItem(
        unknown,
        label(
          unknown,
          Vector.empty,
          Expected(
            Vector.empty,
            Vector.empty,
            true,
            Some("unknown-researcher"),
            Vector("expectedCodes" -> ujson.Arr("identity-unresolved"))
          ),
          None
        )
      )
    )

  /** Writes `questions-v1.jsonl` and `labels-v1.jsonl` into `dir`. */
  def write(dir: Path): (Path, Path) =
    Files.createDirectories(dir)
    val questions = dir.resolve("questions-v1.jsonl")
    val labels = dir.resolve("labels-v1.jsonl")
    Files.writeString(
      questions,
      Items.map(i => ujson.write(i.question.toJson)).mkString("", "\n", "\n"),
      UTF_8
    )
    Files.writeString(
      labels,
      Items.map(i => ujson.write(i.label.toJson)).mkString("", "\n", "\n"),
      UTF_8
    )
    (questions, labels)
