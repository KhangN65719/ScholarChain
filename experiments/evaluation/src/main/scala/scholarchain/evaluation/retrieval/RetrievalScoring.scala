package scholarchain.evaluation.retrieval

import scala.util.Try
import scala.util.hashing.MurmurHash3
import scholarchain.core.{EvidenceItem, EvidenceType}
import scholarchain.evaluation.io.{J, Js}
import scholarchain.evaluation.metrics.{Bootstrap, RetrievalMetrics}
import scholarchain.rag.Facts

/** One vector hit as the evaluation keeps it: the index, the `subjectId` of the
  * vector (the id the labels key on), the distance and the document type.
  */
final case class VectorHit(
    index: String,
    subjectId: String,
    distance: Double,
    documentType: String
):
  def toJson: ujson.Value =
    ujson.Obj(
      "index" -> index,
      "subjectId" -> subjectId,
      "distance" -> distance,
      "documentType" -> documentType
    )

object VectorHit:
  def fromJson(v: ujson.Value): VectorHit =
    VectorHit(
      J.str(v, "index").getOrElse(""),
      J.str(v, "subjectId").getOrElse(""),
      J.num(v, "distance").getOrElse(Double.NaN),
      J.str(v, "documentType").getOrElse("")
    )

/** The raw record of one question in one mode in one repeat: what was
  * retrieved, before any metric. `graphIds` are the ids the graph evidence
  * asserts, in evidence order (see `EvidenceIds`); `vectorDeployed` are the
  * hits the deployed workflow asked (topK 2 per index); `vectorDirect` are the
  * hits of the direct searches at the larger K.
  */
final case class RankedRecord(
    questionId: String,
    mode: String,
    repeat: Int,
    abstained: Boolean,
    abstentionReason: Option[String],
    graphIds: Vector[String],
    pathStatuses: Vector[String],
    vectorDeployed: Vector[VectorHit],
    vectorDirect: Vector[(Int, Vector[VectorHit])],
    evidenceCount: Int,
    operation: Option[String],
    classifiedBy: Option[String],
    modelCalls: Int,
    latencyMillis: Long,
    directNote: Option[String],
    costStopped: Boolean,
    error: Option[String]
):
  def usable: Boolean = !costStopped && error.isEmpty

  def toJson: ujson.Value =
    Js.obj(
      Vector(
        "questionId" -> Js.s(questionId),
        "mode" -> Js.s(mode),
        "repeat" -> Js.l(repeat.toLong),
        "abstained" -> Js.b(abstained),
        "abstentionReason" -> Js.opt(abstentionReason),
        "graphIds" -> Js.arr(graphIds.map(Js.s)),
        "pathStatuses" -> Js.arr(pathStatuses.map(Js.s)),
        "vectorDeployed" -> Js.arr(vectorDeployed.map(_.toJson)),
        "vectorDirect" -> Js.arr(vectorDirect.map { (k, hits) =>
          Js.obj(
            Vector("k" -> Js.l(k.toLong), "hits" -> Js.arr(hits.map(_.toJson)))
          )
        }),
        "evidenceCount" -> Js.l(evidenceCount.toLong),
        "operation" -> Js.opt(operation),
        "classifiedBy" -> Js.opt(classifiedBy),
        "modelCalls" -> Js.l(modelCalls.toLong),
        "latencyMillis" -> Js.l(latencyMillis),
        "directNote" -> Js.opt(directNote),
        "costStopped" -> Js.b(costStopped),
        "error" -> Js.opt(error)
      )
    )

object RankedRecord:
  def fromJson(v: ujson.Value): RankedRecord =
    RankedRecord(
      J.str(v, "questionId").getOrElse(""),
      J.str(v, "mode").getOrElse(""),
      J.int(v, "repeat").getOrElse(1),
      J.field(v, "abstained").flatMap(_.boolOpt).getOrElse(false),
      J.str(v, "abstentionReason"),
      J.strings(v, "graphIds"),
      J.strings(v, "pathStatuses"),
      J.field(v, "vectorDeployed")
        .flatMap(_.arrOpt)
        .map(_.toVector.map(VectorHit.fromJson))
        .getOrElse(Vector.empty),
      J.field(v, "vectorDirect")
        .flatMap(_.arrOpt)
        .map(_.toVector.map { d =>
          (
            J.int(d, "k").getOrElse(0),
            J.field(d, "hits")
              .flatMap(_.arrOpt)
              .map(_.toVector.map(VectorHit.fromJson))
              .getOrElse(Vector.empty)
          )
        })
        .getOrElse(Vector.empty),
      J.int(v, "evidenceCount").getOrElse(0),
      J.str(v, "operation"),
      J.str(v, "classifiedBy"),
      J.int(v, "modelCalls").getOrElse(0),
      J.long(v, "latencyMillis").getOrElse(0L),
      J.str(v, "directNote"),
      J.field(v, "costStopped").flatMap(_.boolOpt).getOrElse(false),
      J.str(v, "error")
    )

/** Which ids a graph evidence item asserts as an answer item, by stratum. The
  * labels key on researcher ids and, for a path, on the hop edge id:
  *
  *   - researcher lookup and temporal comparison: the `identity` and `metric`
  *     facts' researcher id (and a `coauthor` fact's coauthor id);
  *   - coauthor lookup: the `coauthor` facts' coauthor id, in evidence order;
  *   - ranking: the `ranking` fact's entries, in rank order;
  *   - shortest path: the `path` fact's hop edge ids, in hop order;
  *   - community or component: none. The runtime returns the subject's own
  *     group figures and neighbouring community ids, never the member list, so
  *     the labelled members cannot be retrieved in graph mode (README);
  *   - any other stratum (free-form): every id-bearing kind above.
  *
  * Text chunks (semantic items) assert no id of their own and are skipped;
  * their ids come from the recorded vector hits.
  */
object EvidenceIds:
  val Strata: Set[String] = Set(
    "researcher-lookup",
    "coauthor-lookup",
    "ranking",
    "shortest-path",
    "community-component",
    "temporal-comparison"
  )

  /** A free-form label is scored by the operation the human named, when that is
    * one of the strata; otherwise by the generic rule.
    */
  def effectiveStratum(label: LabelRec): String =
    if label.stratum == "free-form" then
      if Strata.contains(label.operation) then label.operation else "free-form"
    else label.stratum

  def ofFact(stratum: String, fact: ujson.Value): Vector[String] =
    val kind = Facts.kind(fact).getOrElse("")
    def subject = Facts.s(fact, "researcherId").toVector
    def coauthor = Facts.s(fact, "coauthorId").toVector
    def ranking =
      Facts.list(fact, "entries").flatMap(e => Facts.s(e, "researcherId"))
    def hops = Facts.list(fact, "hops").flatMap(h => Facts.s(h, "edgeId"))
    stratum match
      case "researcher-lookup" | "temporal-comparison" =>
        kind match
          case "identity" | "metric" => subject
          case "coauthor"            => coauthor
          case _                     => Vector.empty
      case "coauthor-lookup" =>
        if kind == "coauthor" then coauthor else Vector.empty
      case "ranking" =>
        if kind == "ranking" then ranking else Vector.empty
      case "shortest-path" =>
        if kind == "path" then hops else Vector.empty
      case "community-component" => Vector.empty
      case _                     =>
        kind match
          case "identity" | "metric" => subject
          case "coauthor"            => coauthor
          case "ranking"             => ranking
          case "path"                => hops
          case _                     => Vector.empty

  private def factOf(item: EvidenceItem): Option[ujson.Value] =
    if item.evidenceType == EvidenceType.TextChunk then None
    else item.text.flatMap(t => Try(ujson.read(t)).toOption)

  /** The ids of the deterministic evidence items, in evidence order, first
    * occurrence only.
    */
  def graphIds(stratum: String, items: Vector[EvidenceItem]): Vector[String] =
    items.flatMap(i => factOf(i).toVector.flatMap(ofFact(stratum, _))).distinct

  /** The `pathKind` of every `path-status` item (for example `NotPrecomputed`).
    */
  def pathStatuses(items: Vector[EvidenceItem]): Vector[String] =
    items.flatMap { i =>
      factOf(i)
        .filter(f => Facts.kind(f).contains("path-status"))
        .flatMap(f => Facts.s(f, "pathKind"))
    }

/** The ranked id list of one record at one K. */
object Ranked:
  /** Hits merged across indexes by distance (smaller is nearer; ties keep index
    * order), then reduced to distinct subject ids.
    */
  def vectorIds(hits: Vector[VectorHit], k: Int): Vector[String] =
    hits.zipWithIndex
      .sortBy((h, i) => (h.distance, i))
      .map(_._1.subjectId)
      .filter(_.nonEmpty)
      .distinct
      .take(k)

  /** K = 2 is what the workflow retrieved (topK 2 per index); another K is the
    * direct search at that K. Graph mode cuts the graph evidence list at K;
    * fused mode lists the graph ids first, then the vector ids, cut at K.
    */
  def list(rec: RankedRecord, k: Int): Vector[String] =
    val vector =
      if k == RetrievalEval.DeployedK then rec.vectorDeployed
      else rec.vectorDirect.find(_._1 == k).map(_._2).getOrElse(Vector.empty)
    rec.mode match
      case "graph"  => rec.graphIds.take(k)
      case "vector" => vectorIds(vector, k)
      case _        => (rec.graphIds ++ vectorIds(vector, k)).distinct.take(k)

final case class KScore(
    k: Int,
    recall: Double,
    precision: Double,
    ndcg: Double
)

/** One scored record. A label with no graded item (unanswerable, or a path that
  * `path-evidence` does not hold) has no rank metric: every value is NaN, and
  * the abstention-aware `outcome` is scored instead:
  *
  *   - `label.abstain`: correct when the answer abstained; `codeMatch` is set
  *     only in graph and fused mode (vector mode is outside the mapping) and
  *     says whether the abstention code is one of `expectedCodes`;
  *   - `acceptedOutcomes` (a path the table does not hold): correct when the
  *     answer abstained (`abstain`) or its evidence holds a `path-status` of an
  *     accepted kind (`path-status:<kind>`);
  *   - otherwise `falseAbstention` is true when an answerable question was
  *     answered with an abstention.
  */
final case class ScoredRecord(
    rec: RankedRecord,
    label: LabelRec,
    byK: Vector[KScore],
    mrr: Double,
    outcome: Option[Boolean],
    codeMatch: Option[Boolean],
    falseAbstention: Boolean
)

object Scoring:
  def score(
      rec: RankedRecord,
      label: LabelRec,
      ks: Vector[Int]
  ): ScoredRecord =
    val relevant = label.relevant
    val scorable = label.hasItems && !label.abstain
    val byK = ks.map { k =>
      if scorable then
        val list = Ranked.list(rec, k)
        KScore(
          k,
          RetrievalMetrics.recallAtK(list, relevant, k),
          RetrievalMetrics.precisionAtK(list, relevant, k),
          RetrievalMetrics.ndcgAtK(list, relevant, k)
        )
      else KScore(k, Double.NaN, Double.NaN, Double.NaN)
    }
    val mrr =
      if scorable then
        RetrievalMetrics.reciprocalRank(Ranked.list(rec, ks.max), relevant)
      else Double.NaN
    val outcome =
      if label.abstain then Some(rec.abstained)
      else if label.acceptedOutcomes.nonEmpty then
        val tokens =
          (if rec.abstained then Vector("abstain") else Vector.empty) ++
            rec.pathStatuses.map(s => s"path-status:$s")
        Some(tokens.exists(label.acceptedOutcomes.contains))
      else None
    val codeMatch =
      if label.abstain && rec.abstained && rec.mode != "vector" then
        Some(rec.abstentionReason.exists(label.expectedCodes.contains))
      else None
    val falseAbstention =
      !label.abstain && label.acceptedOutcomes.isEmpty && rec.abstained
    ScoredRecord(rec, label, byK, mrr, outcome, codeMatch, falseAbstention)

/** A mean with its percentile bootstrap interval over questions. */
final case class Ci(mean: Double, low: Double, high: Double, n: Int):
  def toJson: ujson.Value =
    def num(d: Double): ujson.Value =
      if d.isNaN then ujson.Null else Js.d(d)
    Js.obj(
      Vector(
        "mean" -> num(mean),
        "low" -> num(low),
        "high" -> num(high),
        "n" -> Js.l(n.toLong)
      )
    )

object Aggregate:
  val Level = 0.95

  def ci(
      values: Seq[Double],
      resamples: Int,
      seed: Long,
      key: String
  ): Ci =
    val clean = values.filterNot(_.isNaN)
    val (m, lo, hi) =
      Bootstrap.meanCi(
        clean.toIndexedSeq,
        resamples,
        seed + MurmurHash3.stringHash(key).toLong,
        Level
      )
    Ci(m, lo, hi, clean.size)

  private def meanOf(values: Seq[Double]): Double =
    val clean = values.filterNot(_.isNaN)
    if clean.isEmpty then Double.NaN else clean.sum / clean.size

  /** One value per question: the mean over its repeats (NaN ignored). */
  private def perQuestion(
      scored: Vector[ScoredRecord]
  ): Vector[(LabelRec, Vector[ScoredRecord])] =
    scored
      .groupBy(_.label.questionId)
      .toVector
      .sortBy(_._1)
      .map((_, rs) => (rs.head.label, rs))

  private def metricValues(
      rs: Vector[ScoredRecord],
      ks: Vector[Int]
  ): Vector[(String, Double)] =
    ks.flatMap { k =>
      Vector(
        s"recall@$k" -> meanOf(rs.map(_.byK.find(_.k == k).get.recall)),
        s"precision@$k" -> meanOf(rs.map(_.byK.find(_.k == k).get.precision)),
        s"ndcg@$k" -> meanOf(rs.map(_.byK.find(_.k == k).get.ndcg))
      )
    } :+ ("mrr@" + ks.max -> meanOf(rs.map(_.mrr)))

  private def rate(num: Int, den: Int): ujson.Value =
    if den == 0 then ujson.Null else Js.d(num.toDouble / den.toDouble)

  private def metricsBlock(
      questions: Vector[(LabelRec, Vector[ScoredRecord])],
      ks: Vector[Int],
      resamples: Int,
      seed: Long,
      key: String
  ): ujson.Value =
    val perQ = questions.map((_, rs) => metricValues(rs, ks))
    val names =
      ks.flatMap(k => Vector(s"recall@$k", s"precision@$k", s"ndcg@$k")) :+
        ("mrr@" + ks.max)
    Js.obj(
      names.map { name =>
        name -> ci(
          perQ.map(_.find(_._1 == name).get._2),
          resamples,
          seed,
          s"$key|$name"
        ).toJson
      }
    )

  private def outcomeBlock(records: Vector[ScoredRecord]): ujson.Value =
    val abstainLabels = records.filter(_.label.abstain)
    val accepted =
      records.filter(r => !r.label.abstain && r.label.acceptedOutcomes.nonEmpty)
    val answerable =
      records.filter(r => !r.label.abstain && r.label.acceptedOutcomes.isEmpty)
    val abstained = abstainLabels.filter(_.rec.abstained)
    val coded = abstainLabels.filter(_.codeMatch.isDefined)
    Js.obj(
      Vector(
        "unanswerableRecords" -> Js.l(abstainLabels.size.toLong),
        "abstentionRecall" -> rate(abstained.size, abstainLabels.size),
        "reasonCodeMatchAmongAbstained" ->
          rate(coded.count(_.codeMatch.contains(true)), coded.size),
        "acceptedOutcomeRecords" -> Js.l(accepted.size.toLong),
        "acceptedOutcomeCorrect" ->
          rate(accepted.count(_.outcome.contains(true)), accepted.size),
        "answerableRecords" -> Js.l(answerable.size.toLong),
        "falseAbstentionRate" ->
          rate(answerable.count(_.falseAbstention), answerable.size)
      )
    )

  private def repeatStability(
      records: Vector[ScoredRecord]
  ): ujson.Value =
    val byQuestion = records.groupBy(_.label.questionId).filter(_._2.size >= 2)
    def same[A](f: RankedRecord => A): ujson.Value =
      rate(
        byQuestion.count((_, rs) => rs.map(r => f(r.rec)).distinct.size == 1),
        byQuestion.size
      )
    Js.obj(
      Vector(
        "questionsWithRepeats" -> Js.l(byQuestion.size.toLong),
        "identicalDeployedVectorHits" ->
          same(_.vectorDeployed.map(h => (h.index, h.subjectId, h.distance))),
        "identicalDirectVectorHits" ->
          same(
            _.vectorDirect.map((k, hs) =>
              (k, hs.map(h => (h.index, h.subjectId, h.distance)))
            )
          ),
        "identicalGraphIds" -> same(_.graphIds),
        "identicalAbstention" -> same(r => (r.abstained, r.abstentionReason))
      )
    )

  private def perRepeat(
      records: Vector[ScoredRecord],
      ks: Vector[Int]
  ): ujson.Value =
    Js.arr(
      records.map(_.rec.repeat).distinct.sorted.map { r =>
        val rs = records.filter(_.rec.repeat == r)
        Js.obj(
          Vector(
            "repeat" -> Js.l(r.toLong),
            "records" -> Js.l(rs.size.toLong)
          ) ++
            metricValues(rs, ks)
              .map((n, v) => n -> (if v.isNaN then ujson.Null else Js.d(v)))
        )
      }
    )

  /** The summary of a run, derived only from the usable raw records: per mode,
    * the pooled and per-stratum means with bootstrap intervals over questions
    * (each question's value is its mean over repeats), over all questions and
    * over the questions whose subject has a vector (`vectorAvailable`), the
    * abstention-aware outcomes, the per-repeat means and the repeat stability.
    */
  def summarize(
      scored: Vector[ScoredRecord],
      ks: Vector[Int],
      resamples: Int,
      seed: Long
  ): ujson.Value =
    val modes = scored.map(_.rec.mode).distinct
    Js.obj(
      modes.map { mode =>
        val inMode = scored.filter(_.rec.mode == mode)
        val scopes = Vector(
          "all" -> inMode,
          "vectorAvailable" -> inMode.filter(
            _.label.vectorAvailable.contains(true)
          )
        )
        mode -> Js.obj(
          Vector(
            "records" -> Js.l(inMode.size.toLong),
            "scopes" -> Js.obj(scopes.map { (scope, rs) =>
              val strata = rs.map(_.label.stratum).distinct
              val groups =
                ("pooled" -> perQuestion(rs)) +:
                  strata.map(s =>
                    s -> perQuestion(rs.filter(_.label.stratum == s))
                  )
              scope -> Js.obj(groups.map { (name, qs) =>
                name -> Js.obj(
                  Vector(
                    "questions" -> Js.l(qs.size.toLong),
                    "metrics" ->
                      metricsBlock(
                        qs,
                        ks,
                        resamples,
                        seed,
                        s"$mode|$scope|$name"
                      )
                  )
                )
              })
            }),
            "outcomes" -> outcomeBlock(inMode),
            "perRepeat" -> perRepeat(inMode, ks),
            "repeatStability" -> repeatStability(inMode)
          )
        )
      }
    )
