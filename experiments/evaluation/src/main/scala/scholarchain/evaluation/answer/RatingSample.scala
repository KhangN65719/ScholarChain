package scholarchain.evaluation.answer

import scala.util.Random
import scala.util.hashing.MurmurHash3
import scholarchain.evaluation.io.Js
import scholarchain.evaluation.retrieval.LabelRec

/** The human rating sample of run H (plan rubric): 20 answers, stratified,
  * shown with the question and the evidence and without any metric or stratum.
  *
  * Allocation: up to 3 abstentions and up to 2 free-form answers; the rest is
  * shared over the answerable strata, 2 each and then one more for the largest
  * strata in turn (with 20 and all strata present that is 2 or 3 each). A
  * stratum with fewer answers than its share gives what it has and the rest
  * moves to the others. Only usable primary answers are drawn. The draw inside
  * a stratum and the final order are seeded shuffles.
  */
object RatingSample:
  /** No `questionId`: the generated ids carry the stratum (`Q-UA-03`,
    * `Q-FF-01`) and would show the rater which answers should abstain. The key
    * file maps `sampleId` to the question.
    */
  val Columns: Vector[String] = Vector(
    "sampleId",
    "question",
    "answer",
    "evidence",
    "usefulness_1to5",
    "clarity_1to5",
    "supported_yes_no",
    "abstention_appropriate_yes_no_na",
    "note"
  )

  val AbstentionShare = 3
  val FreeFormShare = 2

  final case class Picked(
      sampleId: String,
      record: AnswerRecord,
      label: LabelRec
  )

  private def group(label: LabelRec): String =
    if label.abstain then "abstention"
    else if label.stratum == "free-form" then "free-form"
    else label.stratum

  private def shuffled[A](
      items: Vector[A],
      seed: Long,
      key: String
  ): Vector[A] =
    new Random(seed + MurmurHash3.stringHash(key).toLong).shuffle(items)

  /** How many to draw from each answerable stratum. */
  private[answer] def shares(
      available: Vector[(String, Int)],
      total: Int
  ): Map[String, Int] =
    if available.isEmpty || total <= 0 then Map.empty
    else
      val base = total / available.size
      var take = available.map((s, n) => s -> math.min(base, n)).toMap
      var left = total - take.values.sum
      // Largest strata first, one at a time, so 15 over 6 strata is 2, 2, 2,
      // 3, 3, 3 with the three biggest getting the extra one.
      val order = available.sortBy((s, n) => (-n, s))
      var progress = true
      while left > 0 && progress do
        progress = false
        order.foreach { (s, n) =>
          if left > 0 && take(s) < n then
            take = take.updated(s, take(s) + 1)
            left -= 1
            progress = true
        }
      take

  def select(
      candidates: Vector[(AnswerRecord, LabelRec)],
      size: Int,
      seed: Long
  ): Vector[Picked] =
    val usable = candidates.filter((r, _) => r.usable && r.run == "primary")
    val byGroup = usable.groupBy((_, l) => group(l))
    def draw(name: String, n: Int): Vector[(AnswerRecord, LabelRec)] =
      shuffled(
        byGroup.getOrElse(name, Vector.empty).sortBy(_._1.questionId),
        seed,
        name
      ).take(n)
    val abstentions = draw("abstention", AbstentionShare)
    val freeForm = draw("free-form", FreeFormShare)
    val strata = byGroup.toVector
      .filter((g, _) => g != "abstention" && g != "free-form")
      .map((g, v) => g -> v.size)
      .sortBy(_._1)
    val answerable =
      shares(strata, size - abstentions.size - freeForm.size).toVector
        .sortBy(_._1)
        .flatMap((g, n) => draw(g, n))
    val chosen = abstentions ++ freeForm ++ answerable
    shuffled(chosen.sortBy(_._1.questionId), seed, "order").zipWithIndex.map {
      case ((r, l), i) => Picked(f"S${i + 1}%02d", r, l)
    }

  private def csvField(text: String): String =
    "\"" + text.replace("\"", "\"\"") + "\""

  /** The evidence as the rater reads it: one numbered line per item, type and
    * kind, then the fact or snippet. No score and no metric.
    */
  def evidenceText(record: AnswerRecord): String =
    if record.evidence.isEmpty then "(no evidence)"
    else
      record.evidence.zipWithIndex
        .map { (e, i) =>
          val tag = e.kind.getOrElse(e.evidenceType)
          s"[E${i + 1}] $tag: ${e.text.getOrElse("")}"
        }
        .mkString("\n")

  def toCsv(picked: Vector[Picked]): String =
    val header = Columns.mkString(",")
    val rows = picked.map { p =>
      Vector(
        p.sampleId,
        p.record.question,
        p.record.answer,
        evidenceText(p.record),
        "",
        "",
        "",
        "",
        ""
      ).map(csvField).mkString(",")
    }
    (header +: rows).mkString("", "\n", "\n")

  /** Which question each sample row is. Kept apart from the CSV so the rater
    * does not see the stratum.
    */
  def keyJson(picked: Vector[Picked]): ujson.Value =
    Js.arr(picked.map { p =>
      Js.obj(
        Vector(
          "sampleId" -> Js.s(p.sampleId),
          "questionId" -> Js.s(p.record.questionId),
          "group" -> Js.s(group(p.label)),
          "stratum" -> Js.s(p.label.stratum),
          "abstained" -> Js.b(p.record.abstained)
        )
      )
    })
