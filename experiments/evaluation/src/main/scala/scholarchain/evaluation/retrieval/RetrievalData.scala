package scholarchain.evaluation.retrieval

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scholarchain.evaluation.io.J

/** The only part of a question the system ever sees. `QuestionRec` carries no
  * stratum, no operation and no label field, so nothing but `text` can reach
  * the workflow (review 1, finding 14).
  */
final case class QuestionRec(questionId: String, text: String)

/** One label line of `labels-v1.jsonl`, the part the scorers read. */
final case class LabelRec(
    questionId: String,
    stratum: String,
    operation: String,
    items: Vector[(String, Int)],
    numbers: Vector[(String, Double)],
    abstain: Boolean,
    abstainClass: Option[String],
    acceptedOutcomes: Vector[String],
    expectedCodes: Vector[String],
    pathStatus: Option[String],
    vectorAvailable: Option[Boolean],
    subjectIds: Vector[String],
    window: Option[String]
):
  /** Id to grade; a repeated id keeps its highest grade. */
  def relevant: Map[String, Int] =
    items.groupMapReduce(_._1)(_._2)(math.max)

  def hasItems: Boolean = items.nonEmpty

object BenchmarkFiles:
  private def lines(path: Path): Vector[(Int, String)] =
    Files
      .readAllLines(path, UTF_8)
      .asScala
      .toVector
      .zipWithIndex
      .map((text, i) => (i + 1, text))
      .filter(_._2.trim.nonEmpty)

  private def parse(path: Path, number: Int, text: String): ujson.Value =
    try ujson.read(text)
    catch
      case e: Exception =>
        throw new IllegalArgumentException(
          s"${path.getFileName}:$number is not valid JSON: ${e.getMessage}",
          e
        )

  private def need(v: ujson.Value, key: String, where: String): String =
    J.str(v, key)
      .getOrElse(throw new IllegalArgumentException(s"$where has no '$key'"))

  def readQuestions(path: Path): Vector[QuestionRec] =
    lines(path).map { (n, text) =>
      val v = parse(path, n, text)
      val where = s"${path.getFileName}:$n"
      QuestionRec(need(v, "questionId", where), need(v, "text", where))
    }

  def readLabels(path: Path): Vector[LabelRec] =
    lines(path).map { (n, text) =>
      val v = parse(path, n, text)
      val where = s"${path.getFileName}:$n"
      val expected = J
        .field(v, "expected")
        .getOrElse(
          throw new IllegalArgumentException(s"$where has no 'expected'")
        )
      val items = J
        .field(expected, "items")
        .flatMap(_.arrOpt)
        .map(_.toVector)
        .getOrElse(Vector.empty)
        .map { i =>
          (
            need(i, "id", where),
            J.int(i, "grade")
              .getOrElse(
                throw new IllegalArgumentException(s"$where item has no grade")
              )
          )
        }
      val numbers = J
        .field(expected, "numbers")
        .flatMap(_.objOpt)
        .map(_.toVector.flatMap((k, x) => x.numOpt.map(k -> _)))
        .getOrElse(Vector.empty)
      LabelRec(
        need(v, "questionId", where),
        need(v, "stratum", where),
        need(v, "operation", where),
        items,
        numbers,
        J.field(expected, "abstain").flatMap(_.boolOpt).getOrElse(false),
        J.str(expected, "abstainClass"),
        J.strings(expected, "acceptedOutcomes"),
        J.strings(expected, "expectedCodes"),
        J.str(expected, "pathStatus"),
        J.field(v, "vectorAvailable").flatMap(_.boolOpt),
        J.strings(v, "subjectIds"),
        J.str(v, "window")
      )
    }

  /** Questions and labels joined by id. A question without a label, a label
    * without a question and a repeated id are errors, so a scorer never reads a
    * label for another question.
    */
  def pair(
      questions: Vector[QuestionRec],
      labels: Vector[LabelRec]
  ): Vector[(QuestionRec, LabelRec)] =
    val qIds = questions.map(_.questionId)
    val lIds = labels.map(_.questionId)
    require(
      qIds.distinct.size == qIds.size,
      "a questionId repeats in questions"
    )
    require(lIds.distinct.size == lIds.size, "a questionId repeats in labels")
    val missing = qIds.diff(lIds)
    val extra = lIds.diff(qIds)
    require(
      missing.isEmpty && extra.isEmpty,
      s"questions and labels differ: no label for ${missing.mkString(",")}; " +
        s"no question for ${extra.mkString(",")}"
    )
    val byId = labels.map(l => l.questionId -> l).toMap
    questions.map(q => (q, byId(q.questionId)))

/** A stratified subset: strata in first-appearance order, one question from
  * each in turn, so a trimmed run keeps every stratum.
  */
object Subsets:
  def stratified[A](
      items: Vector[A],
      stratumOf: A => String,
      limit: Int
  ): Vector[A] =
    require(limit >= 1, "the subset limit must be at least 1")
    if items.size <= limit then items
    else
      val order = items.map(stratumOf).distinct
      val groups = order.map(s => items.filter(stratumOf(_) == s))
      val picked = Vector.newBuilder[A]
      var round = 0
      var taken = 0
      while taken < limit do
        groups.foreach { g =>
          if taken < limit && round < g.size then
            picked += g(round)
            taken += 1
        }
        round += 1
      val chosen = picked.result().toSet
      items.filter(chosen.contains)
