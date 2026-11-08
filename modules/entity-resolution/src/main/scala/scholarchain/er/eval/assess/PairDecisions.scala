package scholarchain.er.eval.assess

import scholarchain.er.llm.InMemoryAdjudicationCache
import scholarchain.er.llm.LlmAdjudicator
import scholarchain.er.llm.OfflineStubModel
import scholarchain.er.matching.MatchInput
import scholarchain.er.matching.Matcher
import scholarchain.er.matching.Policy
import scholarchain.er.matching.RecordIndex
import scholarchain.er.matching.ResolutionRecord
import scholarchain.er.matching.RunInfo
import scholarchain.er.matching.SideRecord
import scholarchain.er.pairs.Hashing
import scholarchain.er.pairs.PairItem

final case class Decided(pair: PairItem, record: ResolutionRecord)

/** What the matcher said about a pairs file. `unmatched` lists pairs whose
  * records were not in the index, which get no decision at all. `inBand` counts
  * decisions the adjudicator was eligible to see.
  */
final case class Decisions(
    decided: Vector[Decided],
    unmatched: Vector[String],
    inBand: Int,
    modelCalls: Int
):
  def records: Vector[ResolutionRecord] = decided.map(_.record)

/** The decision stage of an evaluation. It reads a pairs file and the records
  * and produces decisions. Its parameters are the pairs, the records, the
  * policy and the run, so nothing else can reach a decision. The only model is
  * the offline stub that always abstains, so nothing here calls a network.
  */
object PairDecisions:
  val BlockKey = "evaluation|pairs-file"
  val StubModel = "offline-stub/abstain-1"

  /** A run ID of the shape the contracts require, derived from a seed so that
    * the same inputs give the same run ID.
    */
  def runIdFor(seed: String): String =
    val h = Hashing.sha256Hex(seed)
    val head = s"${h.take(8)}-${h.slice(8, 12)}"
    val tail = s"8${h.slice(17, 20)}-${h.slice(20, 32)}"
    s"run:v1:$head-4${h.slice(13, 16)}-$tail"

  private def usable(pair: PairItem, l: SideRecord, r: SideRecord): Boolean =
    l.kind == pair.kind && r.kind == pair.kind && l.id != r.id

  private def sides(
      pair: PairItem,
      index: RecordIndex
  ): Option[(SideRecord, SideRecord)] =
    val left = index.get(pair.left.recordId)
    val right = index.get(pair.right.recordId)
    left.zip(right).filter((l, r) => usable(pair, l, r))

  def decide(
      pairs: Vector[PairItem],
      index: RecordIndex,
      policy: Policy,
      run: RunInfo
  ): Decisions =
    val matcher = Matcher(policy, run)
    val stub = OfflineStubModel.abstaining(StubModel)
    val judge = LlmAdjudicator(stub, InMemoryAdjudicationCache(), policy, run)
    val found = pairs.map(p => p -> sides(p, index))
    val unmatched = found.collect { case (p, None) => p.pairId }
    val inputs = found.collect { case (p, Some((l, r))) =>
      p -> MatchInput.of(l, r, BlockKey)
    }
    val first = inputs.map((p, in) => (p, in, matcher.decide(in)))
    val band = first.count((_, _, rec) => judge.eligible(rec))
    val decided = first.map { (p, in, rec) =>
      Decided(p, judge.refine(rec, in.left, in.right))
    }
    Decisions(decided, unmatched, band, judge.modelCalls)

  private def orNull(value: Option[String]): ujson.Value =
    value.map(v => ujson.Str(v): ujson.Value).getOrElse(ujson.Null)

  /** One line of the decisions file: the matcher's answer and nothing else. */
  def line(item: Decided): String =
    val d = item.record.decision
    ujson.write(
      ujson.Obj(
        "pair_id" -> item.pair.pairId,
        "kind" -> item.record.kind.wire,
        "outcome" -> d.decision.wire,
        "path" -> item.record.path.wire,
        "score" -> item.record.score,
        "rulesFired" -> ujson.Arr.from(d.rulesFired),
        "reasonCodes" -> ujson.Arr.from(d.reasonCodes),
        "policyVersion" -> d.policyVersion,
        "modelVersion" -> orNull(d.modelVersion)
      )
    )
