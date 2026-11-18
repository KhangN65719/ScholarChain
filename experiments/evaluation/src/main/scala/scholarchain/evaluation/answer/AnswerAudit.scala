package scholarchain.evaluation.answer

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scholarchain.evaluation.benchmark.{BenchTables, CoauthorEdge, MemberRow}
import scholarchain.evaluation.io.{Digests, ExportFiles, J, Js, RunOutput}
import scholarchain.evaluation.retrieval.{BenchmarkFiles, LabelRec}
import scholarchain.evaluation.runtime.EvalArgs
import scholarchain.rag.Facts

/** The tolerance of the numeric checks: a stated number is accepted when it is
  * within `abs + rel * |expected|` of an export value. The system renders at
  * most two decimals (`Fmt.number`), so 0.01 absolute covers a rounded average.
  */
final case class Tolerance(abs: Double = 0.01, rel: Double = 0.001):
  def near(stated: Double, expected: Double): Boolean =
    math.abs(stated - expected) <= abs + rel * math.abs(expected)

/** The export tables the audit checks answers against. Nothing here reads the
  * system verifier or any runtime store: only A's JSONL export.
  */
final class ExportIndex(
    val tables: BenchTables,
    val workTitles: Map[String, String]
):
  val researcherIds: Set[String] = tables.researchers.map(_.id).toSet
  val workIds: Set[String] = workTitles.keySet
  val edgeById: Map[String, CoauthorEdge] =
    tables.edges.map(e => e.edgeId -> e).toMap
  val supportByEdge: Map[String, Set[String]] =
    tables.support.groupBy(_.edgeId).map((k, v) => k -> v.map(_.workId).toSet)
  val metrics: Map[(String, String), Map[String, Double]] =
    tables.metrics.map(m => (m.researcherId, m.window) -> m.values).toMap
  val communities: Map[(String, String), MemberRow] =
    tables.communities.map(m => (m.researcherId, m.window) -> m).toMap
  val components: Map[(String, String), MemberRow] =
    tables.components.map(m => (m.researcherId, m.window) -> m).toMap

object ExportIndex:
  def load(exportDir: Path): ExportIndex =
    val works = ExportFiles
      .collect(exportDir, "nodes-work") { row =>
        for
          id <- J.str(row.json, "canonicalWorkId")
          title <- J.str(row.json, "title")
        yield id -> title
      }
      .toMap
    new ExportIndex(BenchTables.load(exportDir), works)

/** One atomic check of an evidence item against the export. */
final case class Check(category: String, ok: Boolean, problem: Option[String])

final case class ItemAudit(
    evidenceId: String,
    kind: String,
    checks: Vector[Check]
):
  def checkable: Boolean = checks.nonEmpty
  def valid: Boolean = checks.nonEmpty && checks.forall(_.ok)
  def invalid: Boolean = checks.exists(!_.ok)

final case class ClaimAudit(
    questionId: String,
    run: String,
    claimId: String,
    kind: Option[String],
    factual: Boolean,
    reclassifiedFactual: Boolean,
    excludedByVerifierKind: Boolean,
    citesNothing: Boolean,
    onlySemanticSupport: Boolean,
    citesInvalidEvidence: Boolean,
    unknownIds: Vector[String],
    idsChecked: Int,
    numbersExportMatched: Int,
    numbersEvidenceOnly: Int,
    numbersUnmatched: Int,
    checkableCitation: Boolean,
    contradicted: Boolean,
    verifierVerdict: Option[String]
):
  def toJson: ujson.Value =
    Js.obj(
      Vector(
        "questionId" -> Js.s(questionId),
        "run" -> Js.s(run),
        "claimId" -> Js.s(claimId),
        "kind" -> Js.opt(kind),
        "factual" -> Js.b(factual),
        "reclassifiedFactual" -> Js.b(reclassifiedFactual),
        "excludedByVerifierKind" -> Js.b(excludedByVerifierKind),
        "citesNothing" -> Js.b(citesNothing),
        "onlySemanticSupport" -> Js.b(onlySemanticSupport),
        "citesInvalidEvidence" -> Js.b(citesInvalidEvidence),
        "unknownIds" -> Js.arr(unknownIds.map(Js.s)),
        "idsChecked" -> Js.l(idsChecked.toLong),
        "numbersExportMatched" -> Js.l(numbersExportMatched.toLong),
        "numbersEvidenceOnly" -> Js.l(numbersEvidenceOnly.toLong),
        "numbersUnmatched" -> Js.l(numbersUnmatched.toLong),
        "checkableCitation" -> Js.b(checkableCitation),
        "contradicted" -> Js.b(contradicted),
        "verifierVerdict" -> Js.opt(verifierVerdict)
      )
    )

final case class AnswerAuditResult(
    record: AnswerRecord,
    items: Vector[ItemAudit],
    claims: Vector[ClaimAudit]
)

/** The independent audit rules. They share no code with the system's verifier
  * (`ClaimVerificationAgent`): claims are read as text and evidence as the
  * stored fact, and both are compared with the export.
  *
  *   - Structural support: a factual claim cites evidence (`citesNothing`), and
  *     not only semantic snippets (`onlySemanticSupport`). Which claims are
  *     factual is decided here and not by the verifier alone: a claim the
  *     verifier calls `definition` or `abstention` is left out only when its
  *     own text states no identifier and no number and it cites no
  *     deterministic evidence. One that does is `reclassifiedFactual` and is
  *     audited as a factual claim; the claims left out by the verifier's kind
  *     are counted (`excludedByVerifierKind`) so the exclusion is visible.
  *   - Evidence validity against the export: a coauthor edge exists with the
  *     stated endpoints, window and joint count; every hop of a path is a real
  *     edge with the stated endpoints, window and count, the hops chain from
  *     source to target and the hop count is right; every supporting work of a
  *     hop supports that edge; a publication exists with its title; a metric
  *     fact equals the researcher-metrics row; a ranking's values equal the
  *     rows and do not increase; a group size equals the membership row.
  *   - Identifier accuracy: every researcher, mention, work and coauthor id in
  *     a claim exists in the export.
  *   - Numeric accuracy: every number in a claim (ids, ORCIDs aside) is matched
  *     against export values of the evidence it cites, within the tolerance. A
  *     number that equals no export value but equals a number of the cited
  *     evidence is `evidenceOnly` (the export tables here do not cover that
  *     field, for example a community's cross-edge count). A number that equals
  *     neither is `unmatched`.
  *   - Contradiction: a claim with an unknown id, or with an unmatched number
  *     while it cites an item the export can check numerically.
  *
  * Spelled-out numbers are not read (a stated limit).
  */
object AuditRules:
  val IdPattern =
    """(?:mention-coauthor|researcher|mention|work|coauthor):[A-Za-z0-9_.:\-]+""".r
  private val orcid = """\d{4}-\d{4}-\d{4}-\d{3}[\dX]""".r
  private val number = """\d+(?:\.\d+)?""".r
  private val nonFactualKinds = Set("definition", "abstention")
  private val checkableKinds =
    Set("metric", "ranking", "coauthor", "path", "component", "community")

  private def trimId(raw: String): String =
    raw.reverse.dropWhile(c => ".,;:)".contains(c)).reverse

  def idsIn(text: String): Vector[String] =
    IdPattern.findAllIn(text).toVector.map(trimId).distinct

  def numbersIn(text: String): Vector[Double] =
    val stripped = orcid.replaceAllIn(IdPattern.replaceAllIn(text, " "), " ")
    number.findAllIn(stripped).toVector.flatMap(_.toDoubleOption)

  private def ok(category: String): Check = Check(category, true, None)
  private def bad(category: String, why: String): Check =
    Check(category, false, Some(why))

  private def hopCheck(
      hop: ujson.Value,
      window: Option[String],
      ix: ExportIndex,
      tol: Tolerance
  ): Vector[Check] =
    val edgeId = Facts.s(hop, "edgeId").getOrElse("")
    val from = Facts.s(hop, "from").getOrElse("")
    val to = Facts.s(hop, "to").getOrElse("")
    val joint = Facts.n(hop, "jointPublicationCount")
    val edge = ix.edgeById.get(edgeId)
    val hopOk = edge match
      case None    => bad("pathHop", "edge-missing")
      case Some(e) =>
        val problems = Vector(
          if Set(e.a, e.b) == Set(from, to) then None else Some("endpoints"),
          if window.forall(_ == e.window) then None else Some("window"),
          if joint.forall(j => tol.near(j, e.joint.toDouble)) then None
          else Some("joint-count")
        ).flatten
        if problems.isEmpty then ok("pathHop")
        else bad("pathHop", problems.mkString("+"))
    val supported = ix.supportByEdge.getOrElse(edgeId, Set.empty)
    val works = Facts
      .list(hop, "supportingWorkIds")
      .flatMap(_.strOpt)
      .map(w =>
        if supported.contains(w) then ok("supportingWork")
        else bad("supportingWork", "not-in-edge-support")
      )
    hopOk +: works

  def evidenceChecks(
      e: EvidenceRec,
      ix: ExportIndex,
      tol: Tolerance
  ): Vector[Check] =
    e.fact match
      case None       => Vector.empty
      case Some(fact) =>
        val window = Facts.s(fact, "window")
        Facts.kind(fact).getOrElse("") match
          case "coauthor" =>
            val edge = ix.edgeById.get(Facts.s(fact, "edgeId").getOrElse(""))
            Vector(edge match
              case None    => bad("coauthorEdge", "edge-missing")
              case Some(x) =>
                val ends = Set(
                  Facts.s(fact, "researcherId").getOrElse(""),
                  Facts.s(fact, "coauthorId").getOrElse("")
                )
                val problems = Vector(
                  if Set(x.a, x.b) == ends then None else Some("endpoints"),
                  if window.forall(_ == x.window) then None
                  else Some("window"),
                  if Facts
                      .n(fact, "jointPublicationCount")
                      .forall(j => tol.near(j, x.joint.toDouble))
                  then None
                  else Some("joint-count")
                ).flatten
                if problems.isEmpty then ok("coauthorEdge")
                else bad("coauthorEdge", problems.mkString("+")))
          case "path" =>
            val hops = Facts.list(fact, "hops")
            val froms = hops.map(h => Facts.s(h, "from").getOrElse(""))
            val tos = hops.map(h => Facts.s(h, "to").getOrElse(""))
            val chain =
              hops.nonEmpty &&
                Facts.s(fact, "source").contains(froms.head) &&
                Facts.s(fact, "target").contains(tos.last) &&
                froms.tail == tos.init &&
                Facts.n(fact, "hopCount").forall(_ == hops.size.toDouble)
            (if chain then ok("pathChain") else bad("pathChain", "broken")) +:
              hops.flatMap(hopCheck(_, window, ix, tol))
          case "publication" =>
            val id = Facts.s(fact, "workId").getOrElse("")
            Vector(ix.workTitles.get(id) match
              case None    => bad("publication", "work-missing")
              case Some(t) =>
                if Facts.s(fact, "title").forall(_.trim == t.trim) then
                  ok("publication")
                else bad("publication", "title"))
          case "metric" =>
            val id = Facts.s(fact, "researcherId").getOrElse("")
            val row = window.flatMap(w => ix.metrics.get((id, w)))
            Vector(row match
              case None    => bad("metric", "row-missing")
              case Some(r) =>
                val compared =
                  r.keys.toVector.filter(k => Facts.n(fact, k).isDefined)
                val wrong =
                  compared.filter(k => !tol.near(Facts.n(fact, k).get, r(k)))
                if compared.isEmpty then bad("metric", "nothing-comparable")
                else if wrong.isEmpty then ok("metric")
                else bad("metric", "differs:" + wrong.sorted.mkString(",")))
          case "ranking" =>
            val metric = Facts.s(fact, "metric").getOrElse("")
            val entries = Facts.list(fact, "entries")
            val values = entries.map(en => Facts.n(en, "value"))
            val exportValues = entries.map { en =>
              for
                w <- window
                id <- Facts.s(en, "researcherId")
                v <- ix.metrics.get((id, w)).flatMap(_.get(metric))
              yield v
            }
            val matches = entries.indices.forall { i =>
              (values(i), exportValues(i)) match
                case (Some(a), Some(b)) => tol.near(a, b)
                case _                  => false
            }
            val canonical = entries.forall(en =>
              !Facts.s(en, "researcherId").getOrElse("").startsWith("mention:")
            )
            val sorted = values.flatten.sliding(2).forall {
              case Seq(a, b) => a >= b
              case _         => true
            }
            Vector(
              if entries.isEmpty then bad("ranking", "empty")
              else if !matches then bad("ranking", "value-differs")
              else if !sorted then bad("ranking", "order")
              else if !canonical then bad("ranking", "mention-ranked")
              else ok("ranking")
            )
          case k @ ("component" | "community") =>
            val id = Facts.s(fact, "researcherId").getOrElse("")
            val row = window.flatMap(w =>
              (if k == "component" then ix.components else ix.communities)
                .get((id, w))
            )
            val stated =
              Facts.n(
                fact,
                if k == "component" then "componentSize" else "communitySize"
              )
            Vector(row match
              case None    => bad("group", "row-missing")
              case Some(m) =>
                if stated.exists(s => tol.near(s, m.groupSize.toDouble)) then
                  ok("group")
                else bad("group", "size"))
          case "identity" =>
            val id = Facts.s(fact, "researcherId").getOrElse("")
            Vector(
              if ix.researcherIds.contains(id) then ok("identity")
              else bad("identity", "researcher-missing")
            )
          case _ => Vector.empty

  /** The numbers the export itself gives for an evidence item, plus the
    * structural numbers of the fact (its window years, hop positions, ranks and
    * list sizes), which carry no claim.
    */
  def exportNumbers(
      e: EvidenceRec,
      ix: ExportIndex
  ): Vector[Double] =
    e.fact.toVector.flatMap { fact =>
      val window = Facts.s(fact, "window")
      val years = Vector("windowStart", "windowEnd").flatMap(Facts.n(fact, _))
      years ++ (Facts.kind(fact).getOrElse("") match
        case "metric" =>
          val id = Facts.s(fact, "researcherId").getOrElse("")
          window
            .flatMap(w => ix.metrics.get((id, w)))
            .map(_.values.toVector)
            .getOrElse(Vector.empty)
        case "ranking" =>
          val metric = Facts.s(fact, "metric").getOrElse("")
          val entries = Facts.list(fact, "entries")
          entries.indices.map(i => (i + 1).toDouble) ++
            entries.flatMap { en =>
              for
                w <- window
                id <- Facts.s(en, "researcherId")
                v <- ix.metrics.get((id, w)).flatMap(_.get(metric))
              yield v
            }
        case "coauthor" =>
          Facts
            .s(fact, "edgeId")
            .flatMap(ix.edgeById.get)
            .map(_.joint.toDouble)
            .toVector
        case "path" =>
          val hops = Facts.list(fact, "hops")
          hops.indices.map(i => (i + 1).toDouble) ++ Vector(
            hops.size.toDouble
          ) ++
            hops.flatMap(h =>
              Facts
                .s(h, "edgeId")
                .flatMap(ix.edgeById.get)
                .map(_.joint.toDouble)
            )
        case k @ ("component" | "community") =>
          val id = Facts.s(fact, "researcherId").getOrElse("")
          window
            .flatMap(w =>
              (if k == "component" then ix.components else ix.communities)
                .get((id, w))
            )
            .map(_.groupSize.toDouble)
            .toVector
        case _ => Vector.empty[Double])
    }

  private def kindsByClaim(
      record: AnswerRecord
  ): Map[String, (Option[String], Option[String])] =
    record.verification
      .map(v =>
        Facts
          .list(v, "claims")
          .map(c =>
            Facts.s(c, "claimId").getOrElse("") ->
              ((Facts.s(c, "kind"), Facts.s(c, "verdict")))
          )
          .toMap
      )
      .getOrElse(Map.empty)

  def auditAnswer(
      record: AnswerRecord,
      ix: ExportIndex,
      tol: Tolerance
  ): AnswerAuditResult =
    val items = record.evidence.map(e =>
      ItemAudit(
        e.evidenceId,
        e.kind.getOrElse(e.evidenceType),
        evidenceChecks(e, ix, tol)
      )
    )
    val itemById = items.map(i => i.evidenceId -> i).toMap
    val evidenceById = record.evidence.map(e => e.evidenceId -> e).toMap
    val kinds = kindsByClaim(record)
    val claims = record.claims.map { c =>
      val (kind, verdict) = kinds.getOrElse(c.claimId, (None, None))
      val cited = c.evidenceIds.flatMap(evidenceById.get)
      val deterministic = cited.filterNot(_.isSemantic)
      val ids = idsIn(c.text)
      val verifierNonFactual = kind.exists(nonFactualKinds.contains)
      val statesData =
        ids.nonEmpty || numbersIn(c.text).nonEmpty || deterministic.nonEmpty
      val factual = !verifierNonFactual || statesData
      val unknown = ids.filterNot { id =>
        if id.startsWith("researcher:") || id.startsWith("mention:") then
          ix.researcherIds.contains(id)
        else if id.startsWith("work:") then ix.workIds.contains(id)
        else ix.edgeById.contains(id)
      }
      val fromExport = deterministic.flatMap(exportNumbers(_, ix))
      val fromEvidence =
        deterministic.flatMap(_.fact.toVector.flatMap(Facts.numbers))
      val stated = numbersIn(c.text)
      val matched = stated.count(n => fromExport.exists(tol.near(n, _)))
      val soft = stated.count(n =>
        !fromExport.exists(tol.near(n, _)) &&
          fromEvidence.exists(tol.near(n, _))
      )
      val unmatched = stated.size - matched - soft
      val checkable = deterministic.exists(e =>
        e.kind.exists(checkableKinds.contains) &&
          exportNumbers(e, ix).nonEmpty
      )
      ClaimAudit(
        record.questionId,
        record.run,
        c.claimId,
        kind,
        factual,
        reclassifiedFactual = verifierNonFactual && factual,
        excludedByVerifierKind = verifierNonFactual && !factual,
        citesNothing = factual && c.evidenceIds.isEmpty,
        onlySemanticSupport =
          factual && cited.nonEmpty && deterministic.isEmpty,
        citesInvalidEvidence =
          c.evidenceIds.exists(id => itemById.get(id).exists(_.invalid)),
        unknown,
        ids.size,
        matched,
        soft,
        unmatched,
        checkable,
        contradicted = unknown.nonEmpty || (unmatched > 0 && checkable),
        verdict
      )
    }
    AnswerAuditResult(record, items, claims)

object AnswerAudit:
  val ExitComplete = 0
  val ExitFailed = 1
  val ExitUsage = 2

  private def rate(num: Int, den: Int): ujson.Value =
    if den == 0 then ujson.Null else Js.d(num.toDouble / den.toDouble)

  private def pair(checked: Int, valid: Int): ujson.Value =
    Js.obj(
      Vector(
        "checked" -> Js.l(checked.toLong),
        "valid" -> Js.l(valid.toLong),
        "validRate" -> rate(valid, checked)
      )
    )

  private def verdictCounts(records: Vector[AnswerRecord]): Vector[String] =
    records.flatMap(r =>
      r.verification.toVector
        .flatMap(v => Facts.list(v, "claims"))
        .flatMap(c => Facts.s(c, "verdict"))
    )

  private def abstentionBlock(
      records: Vector[AnswerRecord],
      labels: Map[String, LabelRec]
  ): ujson.Value =
    val joined = records.flatMap(r => labels.get(r.questionId).map(r -> _))
    val unanswerable = joined.filter(_._2.abstain)
    val abstained = unanswerable.filter(_._1.abstained)
    val coded = abstained.filter(_._2.expectedCodes.nonEmpty)
    val answerable =
      joined.filter((_, l) => !l.abstain && l.acceptedOutcomes.isEmpty)
    val accepted =
      joined.filter((_, l) => !l.abstain && l.acceptedOutcomes.nonEmpty)
    def statusesOf(r: AnswerRecord): Vector[String] =
      r.evidence.flatMap(_.fact).flatMap { f =>
        if Facts.kind(f).contains("path-status") then
          Facts.s(f, "pathKind").map(k => s"path-status:$k")
        else None
      }
    val acceptedCorrect = accepted.count((r, l) =>
      (if r.abstained then Vector("abstain") else Vector.empty[String])
        .++(statusesOf(r))
        .exists(l.acceptedOutcomes.contains)
    )
    Js.obj(
      Vector(
        "unanswerable" -> Js.l(unanswerable.size.toLong),
        "abstentionRecall" -> rate(abstained.size, unanswerable.size),
        "unanswerableAnswered" ->
          Js.l(unanswerable.count(!_._1.abstained).toLong),
        "reasonCodeMatchAmongAbstained" -> rate(
          coded.count((r, l) =>
            r.abstentionReason.exists(l.expectedCodes.contains)
          ),
          coded.size
        ),
        "answerable" -> Js.l(answerable.size.toLong),
        "falseAbstentionRate" ->
          rate(answerable.count(_._1.abstained), answerable.size),
        "acceptedOutcomeRecords" -> Js.l(accepted.size.toLong),
        "acceptedOutcomeCorrect" -> rate(acceptedCorrect, accepted.size)
      )
    )

  private def variability(all: Vector[AnswerRecord]): ujson.Value =
    val primary = all.filter(r => r.run == "primary" && r.usable)
    val repeat = all.filter(r => r.run == "repeat" && r.usable)
    val pairs = repeat.flatMap(r =>
      primary.find(_.questionId == r.questionId).map(_ -> r)
    )
    def same(f: AnswerRecord => Any): ujson.Value =
      rate(pairs.count((a, b) => f(a) == f(b)), pairs.size)
    Js.obj(
      Vector(
        "questionsRepeated" -> Js.l(pairs.size.toLong),
        "sameAbstention" -> same(r => (r.abstained, r.abstentionReason)),
        "sameEvidenceIds" -> same(_.evidence.map(_.evidenceId)),
        "sameClaimCount" -> same(_.claims.size),
        "sameAnswerHash" -> same(_.answerHash)
      )
    )

  def summarize(
      results: Vector[AnswerAuditResult],
      all: Vector[AnswerRecord],
      labels: Map[String, LabelRec],
      tol: Tolerance
  ): ujson.Value =
    val answered = results.filter(r => !r.record.abstained)
    val claims = answered.flatMap(_.claims)
    val factual = claims.filter(_.factual)
    val items = answered.flatMap(_.items)
    val checks = items.flatMap(_.checks)
    def category(name: String): ujson.Value =
      val inCat = checks.filter(_.category == name)
      pair(inCat.size, inCat.count(_.ok))
    val numbers = factual
    val exportMatched = numbers.map(_.numbersExportMatched).sum
    val evidenceOnly = numbers.map(_.numbersEvidenceOnly).sum
    val unmatched = numbers.map(_.numbersUnmatched).sum
    val verdicts = verdictCounts(answered.map(_.record))
    val missedByVerifier =
      factual.count(c =>
        c.contradicted && c.verifierVerdict.contains("VERIFIED")
      )
    Js.obj(
      Vector(
        "schema" -> Js.s("scholarchain.evaluation.answer-audit/1"),
        "tolerance" -> Js.obj(
          Vector("abs" -> Js.d(tol.abs), "rel" -> Js.d(tol.rel))
        ),
        "answers" -> Js.obj(
          Vector(
            "audited" -> Js.l(results.size.toLong),
            "answered" -> Js.l(answered.size.toLong),
            "abstained" -> Js.l((results.size - answered.size).toLong)
          )
        ),
        "structuralSupport" -> Js.obj(
          Vector(
            "claims" -> Js.l(claims.size.toLong),
            "factualClaims" -> Js.l(factual.size.toLong),
            "reclassifiedFactualByAudit" ->
              Js.l(claims.count(_.reclassifiedFactual).toLong),
            "excludedByVerifierKind" ->
              Js.l(claims.count(_.excludedByVerifierKind).toLong),
            "citingNothing" -> Js.l(factual.count(_.citesNothing).toLong),
            "onlySemanticSupport" ->
              Js.l(factual.count(_.onlySemanticSupport).toLong),
            "supportedRate" -> rate(
              factual.count(c => !c.citesNothing && !c.onlySemanticSupport),
              factual.size
            )
          )
        ),
        "evidenceValidity" -> Js.obj(
          Vector(
            "coauthorEdges" -> category("coauthorEdge"),
            "pathHops" -> category("pathHop"),
            "pathChains" -> category("pathChain"),
            "supportingWorks" -> category("supportingWork"),
            "publications" -> category("publication"),
            "metrics" -> category("metric"),
            "rankings" -> category("ranking"),
            "groups" -> category("group"),
            "identities" -> category("identity")
          )
        ),
        "identifiers" -> Js.obj(
          Vector(
            "idsChecked" -> Js.l(factual.map(_.idsChecked).sum.toLong),
            "claimsWithUnknownId" ->
              Js.l(factual.count(_.unknownIds.nonEmpty).toLong)
          )
        ),
        "numbers" -> Js.obj(
          Vector(
            "checked" -> Js.l(
              (exportMatched + evidenceOnly + unmatched).toLong
            ),
            "exportMatched" -> Js.l(exportMatched.toLong),
            "evidenceOnly" -> Js.l(evidenceOnly.toLong),
            "unmatched" -> Js.l(unmatched.toLong),
            "exportMatchedRate" ->
              rate(exportMatched, exportMatched + evidenceOnly + unmatched)
          )
        ),
        "contradictions" -> Js.obj(
          Vector(
            "claimsContradicted" -> Js.l(factual.count(_.contradicted).toLong),
            "contradictionRate" ->
              rate(factual.count(_.contradicted), factual.size),
            "claimsCitingInvalidEvidence" ->
              Js.l(factual.count(_.citesInvalidEvidence).toLong),
            "answersWithContradiction" -> Js.l(
              answered.count(_.claims.exists(_.contradicted)).toLong
            )
          )
        ),
        "verifierSelfReported" -> Js.obj(
          Vector(
            "note" -> Js.s(
              "the system verifier's own verdicts, before it removed or weakened claims; not independent"
            ),
            "verdicts" -> Js.counts(
              verdicts.groupBy(identity).map((k, v) => k -> v.size.toLong)
            ),
            "overall" -> Js.counts(
              answered
                .flatMap(_.record.verification)
                .flatMap(v => Facts.s(v, "overall"))
                .groupBy(identity)
                .map((k, v) => k -> v.size.toLong)
            ),
            "claimsVerifiedButAuditContradicted" -> Js.l(
              missedByVerifier.toLong
            )
          )
        ),
        "abstention" -> abstentionBlock(results.map(_.record), labels),
        "repeatVariability" -> variability(all)
      )
    )

  val Options: Set[String] =
    Set("answers", "labels", "export-dir", "out-dir", "run-id")
  val Usage: String =
    "usage: AnswerAudit --answers answers.jsonl --labels labels-v1.jsonl " +
      "--export-dir DIR --out-dir DIR --run-id ID"

  def readAnswers(path: Path): Vector[AnswerRecord] =
    Files
      .readAllLines(path, UTF_8)
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l => AnswerRecord.fromJson(ujson.read(l)))

  final case class Report(runDir: Path, audited: Int)

  def run(
      answersPath: Path,
      labelsPath: Path,
      index: ExportIndex,
      outBase: Path,
      runId: String,
      tol: Tolerance = Tolerance()
  ): Report =
    val all = readAnswers(answersPath)
    val labels =
      BenchmarkFiles.readLabels(labelsPath).map(l => l.questionId -> l).toMap
    val primary = all.filter(r => r.run == "primary" && r.usable)
    val results = primary.map(AuditRules.auditAnswer(_, index, tol))
    val runDir = RunOutput.createRunDir(outBase, runId)
    RunOutput.write(
      runDir,
      "audit-claims.jsonl",
      results
        .flatMap(_.claims)
        .map(c => ujson.write(c.toJson))
        .mkString("", "\n", "\n")
    )
    RunOutput.write(
      runDir,
      "audit-answers.jsonl",
      results
        .map { r =>
          ujson.write(
            Js.obj(
              Vector(
                "questionId" -> Js.s(r.record.questionId),
                "abstained" -> Js.b(r.record.abstained),
                "abstentionReason" -> Js.opt(r.record.abstentionReason),
                "claims" -> Js.l(r.claims.size.toLong),
                "claimsContradicted" ->
                  Js.l(r.claims.count(_.contradicted).toLong),
                "items" -> Js.arr(r.items.map { i =>
                  Js.obj(
                    Vector(
                      "evidenceId" -> Js.s(i.evidenceId),
                      "kind" -> Js.s(i.kind),
                      "checks" -> Js.arr(i.checks.map { c =>
                        Js.obj(
                          Vector(
                            "category" -> Js.s(c.category),
                            "ok" -> Js.b(c.ok),
                            "problem" -> Js.opt(c.problem)
                          )
                        )
                      })
                    )
                  )
                })
              )
            )
          )
        }
        .mkString("", "\n", "\n")
    )
    RunOutput.write(
      runDir,
      "audit-summary.json",
      ujson.write(summarize(results, all, labels, tol), indent = 2) + "\n"
    )
    RunOutput.write(
      runDir,
      "run-manifest.json",
      ujson.write(
        Js.obj(
          Vector(
            "schema" -> Js.s("scholarchain.evaluation.answer-audit-run/1"),
            "runId" -> Js.s(runId),
            "finishedAt" -> Js.s(Instant.now().toString),
            "answersSha256" -> Js.s(Digests.sha256File(answersPath)),
            "labelsSha256" -> Js.s(Digests.sha256File(labelsPath)),
            "records" -> Js.l(all.size.toLong),
            "audited" -> Js.l(results.size.toLong)
          )
        ),
        indent = 2
      ) + "\n"
    )
    RunOutput.seal(runDir)
    Report(runDir, results.size)

  def main(args: Array[String]): Unit =
    sys.exit(execute(args.toSeq, println))

  def execute(args: Seq[String], out: String => Unit): Int =
    try
      val a = EvalArgs.parse(args, Options, Set.empty)
      val report = run(
        Paths.get(a.required("answers")),
        Paths.get(a.required("labels")),
        ExportIndex.load(Paths.get(a.required("export-dir"))),
        Paths.get(a.required("out-dir")),
        a.required("run-id")
      )
      out(s"AUDIT_RESULT status=COMPLETE audited=${report.audited}")
      out(s"AUDIT_RUN_DIR ${report.runDir}")
      ExitComplete
    catch
      case e: IllegalArgumentException =>
        out(s"AUDIT_USAGE_ERROR ${e.getMessage}")
        out(Usage)
        ExitUsage
      case NonFatal(e) =>
        out(s"AUDIT_RESULT status=FAILED error=${e.getClass.getSimpleName}")
        ExitFailed
