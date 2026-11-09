package scholarchain.rag

import java.util.regex.Pattern
import scala.collection.mutable
import scala.util.Try
import scholarchain.core.{
  AnswerClaim,
  CollaborationMetric,
  ContractSchema,
  EvidenceBundle,
  EvidenceItem,
  EvidenceType,
  Provenance,
  VerificationStatus
}

/** The verdicts the README asks for. Core `VerificationStatus` has three
  * values, so the mapping is explicit (decision P2): Verified is Verified,
  * PartiallySupported is Uncertain, and Unsupported and Conflicting are both
  * Unsupported with the conflict named in the claim's `uncertainty`.
  */
enum VerificationVerdict(val wire: String):
  case Verified extends VerificationVerdict("VERIFIED")
  case PartiallySupported extends VerificationVerdict("PARTIALLY_SUPPORTED")
  case Unsupported extends VerificationVerdict("UNSUPPORTED")
  case Conflicting extends VerificationVerdict("CONFLICTING")

object VerificationVerdict:
  def toCore(verdict: VerificationVerdict): VerificationStatus = verdict match
    case Verified           => VerificationStatus.Verified
    case PartiallySupported => VerificationStatus.Uncertain
    case Unsupported        => VerificationStatus.Unsupported
    case Conflicting        => VerificationStatus.Unsupported

final case class ClaimVerification(
    claimId: String,
    kind: String,
    verdict: VerificationVerdict,
    reasons: Vector[String]
)

/** What happened to a claim in the final answer: `kept`, `weakened` (rewritten
  * to the part the evidence supports) or `removed`.
  */
final case class ClaimChange(claimId: String, action: String, reason: String)

final case class VerificationReport(
    overall: Option[VerificationVerdict],
    materialClaims: Int,
    claims: Vector[ClaimVerification],
    changes: Vector[ClaimChange]
):
  def verdictOf(claimId: String): Option[VerificationVerdict] =
    claims.find(_.claimId == claimId).map(_.verdict)

  def toJson: ujson.Value = ujson.Obj(
    "schema" -> Schemas.VerifierReportId,
    "overall" -> Facts.optStr(overall.map(_.wire)),
    "materialClaims" -> materialClaims,
    "claims" -> ujson.Arr.from(claims.map { c =>
      ujson.Obj(
        "claimId" -> c.claimId,
        "kind" -> c.kind,
        "verdict" -> c.verdict.wire,
        "reasons" -> Facts.strs(c.reasons)
      ): ujson.Value
    }),
    "changes" -> ujson.Arr.from(changes.map { c =>
      ujson.Obj(
        "claimId" -> c.claimId,
        "action" -> c.action,
        "reason" -> c.reason
      ): ujson.Value
    })
  )

final case class FinalAnswer(
    text: String,
    claims: Vector[AnswerClaim],
    report: VerificationReport,
    abstained: Boolean
)

/** Deterministic claim verification. No model is called. Every claim is checked
  * against the facts of the evidence items it cites, and only against facts:
  *
  *   - the cited evidence must exist in the bundle;
  *   - the structured assertion (researcher, publication title, coauthor, path
  *     hop, component, community, bridge score, team size, window, value) must
  *     match a cited fact of the right kind;
  *   - every number in the claim text must appear in the system-rendered form
  *     of the asserted value (F1), and the answer shows that rendered form, not
  *     the model's wording; semantic and abstention wording, the analysis
  *     paragraph and per-claim uncertainty are system-written too;
  *   - a semantic-context item (`TextChunk`) is never a fact: a claim whose
  *     only support is a snippet is Unsupported, and a number found only in a
  *     snippet is reported as such.
  *
  * Unsupported and Conflicting claims are removed from the answer, a partly
  * supported claim is rewritten to what the facts support, and each change is
  * recorded in the report.
  */
final class ClaimVerificationAgent:
  private enum Check:
    case Ok(support: Vector[ujson.Value])
    case Bad(verdict: VerificationVerdict, reason: String)

  private val NonFactual = Set("definition", "abstention")
  private val numberPattern = "\\d+(?:\\.\\d+)?".r
  private val unitWords: Map[String, Long] = Vector(
    "zero",
    "one",
    "two",
    "three",
    "four",
    "five",
    "six",
    "seven",
    "eight",
    "nine",
    "ten",
    "eleven",
    "twelve",
    "thirteen",
    "fourteen",
    "fifteen",
    "sixteen",
    "seventeen",
    "eighteen",
    "nineteen"
  ).zipWithIndex.map((w, i) => w -> i.toLong).toMap
  private val tensWords: Map[String, Long] = Vector(
    "twenty",
    "thirty",
    "forty",
    "fifty",
    "sixty",
    "seventy",
    "eighty",
    "ninety"
  ).zipWithIndex.map((w, i) => w -> (i + 2) * 10L).toMap
  private val scaleWords: Map[String, Long] =
    Map("hundred" -> 100L, "thousand" -> 1000L, "million" -> 1000000L)
  private val wordPattern = "[A-Za-z]+".r

  /** Whether `next` continues the number `prev` begins: "twenty-one", "two
    * hundred", "one hundred and five". "two three" are two numbers.
    */
  private def joins(prev: String, next: String, between: String): Boolean =
    val plain = between.matches("[- ]+")
    val withAnd = between.matches("(?i)\\s+and\\s+")
    if scaleWords.contains(prev) then plain || withAnd
    else if scaleWords.contains(next) then plain
    else
      plain && tensWords.contains(prev) &&
      unitWords.get(next).exists(v => v >= 1 && v <= 9)

  private def isNumberWord(w: String): Boolean =
    unitWords.contains(w) || tensWords.contains(w) || scaleWords.contains(w)

  private def wordValue(words: Vector[String]): Long =
    var total = 0L
    var current = 0L
    words.foreach { w =>
      if unitWords.contains(w) then current += unitWords(w)
      else if tensWords.contains(w) then current += tensWords(w)
      else
        val scale = scaleWords(w)
        if scale == 100L then current = math.max(current, 1L) * 100L
        else
          total += math.max(current, 1L) * scale
          current = 0L
    }
    total + current

  /** Spelled quantities: "two" to "nineteen", the tens, "hundred", "thousand",
    * "million" and runs of them ("ninety-nine", "one hundred and five"). "zero"
    * counts. A lone "one" does not, because in ordinary prose it is mostly a
    * pronoun ("no one", "one of"); "one" counts inside a run ("twenty-one",
    * "one hundred"). Claim wording is never shown, so this scan only decides
    * whether a model's wording earns a Verified label.
    */
  private def spelled(text: String): Vector[BigDecimal] =
    val runs = mutable.ArrayBuffer.empty[mutable.ArrayBuffer[String]]
    var run: Option[mutable.ArrayBuffer[String]] = None
    var end = -1
    wordPattern.findAllMatchIn(text).foreach { m =>
      val w = m.matched.toLowerCase
      if isNumberWord(w) then
        val continues =
          run.exists(r => joins(r.last, w, text.substring(end, m.start)))
        if !continues then
          val fresh = mutable.ArrayBuffer.empty[String]
          runs += fresh
          run = Some(fresh)
        run.get += w
        end = m.end
      else if w != "and" then run = None
    }
    runs.toVector
      .map(_.toVector)
      .filterNot(_ == Vector("one"))
      .map(words => BigDecimal(wordValue(words)))

  private final case class Subject(
      claim: ClaimDraft,
      facts: Vector[ujson.Value],
      hasChunk: Boolean
  )

  private def approx(a: Double, b: Double): Boolean =
    math.abs(a - b) <= 1e-9 * math.max(1.0, math.max(math.abs(a), math.abs(b)))

  private def pair(
      f: ujson.Value,
      k1: String,
      k2: String,
      x: Option[String],
      y: Option[String]
  ): Boolean =
    val (a, b) = (Facts.s(f, k1), Facts.s(f, k2))
    x.isDefined && y.isDefined &&
    ((a == x && b == y) || (a == y && b == x))

  private def ofKind(s: Subject, kind: String): Vector[ujson.Value] =
    s.facts.filter(f => Facts.kind(f).contains(kind))

  private def noFact(s: Subject, what: String): Check =
    if s.facts.isEmpty && s.hasChunk then
      Check.Bad(
        VerificationVerdict.Unsupported,
        s"only semantic context is cited and it cannot support a $what claim (F1)"
      )
    else
      Check.Bad(
        VerificationVerdict.Unsupported,
        s"no cited evidence holds a $what record for this claim"
      )

  private def conflict(reason: String): Check =
    Check.Bad(VerificationVerdict.Conflicting, reason)

  private def windowOk(a: ujson.Value, f: ujson.Value): Boolean =
    Facts.s(a, "window").forall(w => Facts.s(f, "window").contains(w))

  private def sameNumber(
      a: ujson.Value,
      key: String,
      facts: Vector[ujson.Value],
      field: String,
      what: String
  ): Option[Check] =
    Facts
      .n(a, key)
      .flatMap { claimed =>
        if facts.flatMap(Facts.n(_, field)).exists(approx(claimed, _)) then None
        else Some(conflict(s"the stored $what differs from the claimed value"))
      }

  private def structural(s: Subject): Check =
    val a: ujson.Value = s.claim.assertion
    s.claim.kind match
      case "metric" =>
        val researcher = Facts.s(a, "researcherId")
        (
          Facts.s(a, "metric").flatMap(Rendering.metricByWire),
          researcher
        ) match
          case (Some(CollaborationMetric.ShortestPathLength), _) =>
            pathCheck(s, a)
          case (Some(metric), Some(id)) =>
            val mine = ofKind(s, "metric").filter(f =>
              Facts.s(f, "researcherId").contains(id)
            )
            if mine.isEmpty then noFact(s, "metric")
            else
              val inWindow = mine.filter(windowOk(a, _))
              val field = MetricValues.field(metric).get
              if inWindow.isEmpty then
                conflict("the cited metrics are for another time window")
              else if inWindow.flatMap(Facts.n(_, field)).isEmpty then
                Check.Bad(
                  VerificationVerdict.Unsupported,
                  s"the stored record has no value for ${metric.wire}"
                )
              else
                sameNumber(a, "value", inWindow, field, metric.wire)
                  .getOrElse(Check.Ok(narrow(inWindow, a, "value", field)))
          case _ =>
            Check.Bad(
              VerificationVerdict.Unsupported,
              "the metric assertion is incomplete"
            )
      case "ranking" =>
        val metric = Facts.s(a, "metric")
        val tables = ofKind(s, "ranking").filter(f =>
          Facts.s(f, "metric") == metric && windowOk(a, f)
        )
        if tables.isEmpty then noFact(s, "ranking")
        else
          // Equal values share a rank, so several entries can hold one rank.
          val atRank = tables
            .flatMap(f => Facts.list(f, "entries"))
            .filter(e => Facts.n(e, "rank") == Facts.n(a, "rank"))
          if atRank.isEmpty then
            Check.Bad(
              VerificationVerdict.Unsupported,
              "no cited ranked entry has that rank"
            )
          else
            atRank.find(e =>
              Facts.s(e, "researcherId") == Facts.s(a, "researcherId")
            ) match
              case None    => conflict("a different researcher holds that rank")
              case Some(e) =>
                sameNumber(a, "value", Vector(e), "value", "ranked value")
                  .getOrElse(Check.Ok(tables))
      case "coauthor" =>
        val mine = ofKind(s, "coauthor").filter(f =>
          pair(
            f,
            "researcherId",
            "coauthorId",
            Facts.s(a, "researcherId"),
            Facts.s(a, "coauthorId")
          ) && windowOk(a, f)
        )
        if mine.isEmpty then noFact(s, "coauthor")
        else
          sameNumber(
            a,
            "jointPublicationCount",
            mine,
            "jointPublicationCount",
            "joint publication count"
          ).getOrElse(
            Check.Ok(
              narrow(mine, a, "jointPublicationCount", "jointPublicationCount")
            )
          )
      case "path"        => pathCheck(s, a)
      case "path-status" =>
        val statuses = ofKind(s, "path-status").filter(f =>
          pair(
            f,
            "source",
            "target",
            Facts.s(a, "source"),
            Facts.s(a, "target")
          )
        )
        val claimed = Facts.b(a, "noPathExists")
        if statuses.isEmpty then
          if ofKind(s, "path").exists(f =>
              pair(
                f,
                "source",
                "target",
                Facts.s(a, "source"),
                Facts.s(a, "target")
              )
            )
          then conflict("the cited evidence shows that a path exists")
          else noFact(s, "path status")
        else if statuses.exists(f => Facts.b(f, "noPathExists") == claimed) then
          Check.Ok(statuses.filter(f => Facts.b(f, "noPathExists") == claimed))
        else if claimed.contains(true) then
          val reachable = statuses.exists(f =>
            Set("BeyondHopLimit", "ExceedsDepth", "ExceedsResults")
              .contains(Facts.s(f, "pathKind").getOrElse(""))
          )
          if reachable then
            conflict("the cited evidence shows that a path exists")
          else
            Check.Bad(
              VerificationVerdict.Unsupported,
              "the cited evidence does not establish that no path exists"
            )
        else conflict("the cited evidence shows that no path exists")
      case "component" =>
        val mine = ofKind(s, "component").filter(f =>
          Facts.s(f, "researcherId") == Facts.s(a, "researcherId") &&
            windowOk(a, f)
        )
        if mine.isEmpty then noFact(s, "component")
        else
          sameNumber(
            a,
            "componentSize",
            mine,
            "componentSize",
            "component size"
          )
            .getOrElse(
              Check.Ok(narrow(mine, a, "componentSize", "componentSize"))
            )
      case "community" =>
        val mine = ofKind(s, "community").filter(f =>
          Facts.s(f, "researcherId") == Facts.s(a, "researcherId") &&
            windowOk(a, f)
        )
        if mine.isEmpty then noFact(s, "community")
        else
          sameNumber(
            a,
            "communitySize",
            mine,
            "communitySize",
            "community size"
          )
            .orElse(
              sameNumber(
                a,
                "crossEdges",
                mine,
                "crossEdges",
                "cross-community edge count"
              )
            )
            .getOrElse(
              Check.Ok(
                narrow(
                  narrow(mine, a, "communitySize", "communitySize"),
                  a,
                  "crossEdges",
                  "crossEdges"
                )
              )
            )
      case "publication" =>
        val mine = ofKind(s, "publication").filter(f =>
          Facts.s(f, "workId") == Facts.s(a, "workId")
        )
        if mine.isEmpty then noFact(s, "publication")
        else if Facts
            .s(a, "title")
            .exists(t =>
              !mine.exists(f =>
                Facts.s(f, "title").exists(_.trim.equalsIgnoreCase(t.trim))
              )
            )
        then conflict("the stored title differs from the claimed title")
        else Check.Ok(mine)
      case "identity" =>
        val mine = ofKind(s, "identity").filter(f =>
          Facts.s(f, "researcherId") == Facts.s(a, "researcherId")
        )
        if mine.isEmpty then noFact(s, "identity")
        else if Facts
            .s(a, "displayName")
            .exists(n =>
              !mine.exists(f =>
                Facts
                  .s(f, "displayName")
                  .exists(_.trim.equalsIgnoreCase(n.trim))
              )
            )
        then conflict("the stored name differs from the claimed name")
        else Check.Ok(mine)
      case "semantic" =>
        if s.hasChunk then Check.Ok(Vector.empty)
        else
          Check.Bad(
            VerificationVerdict.Unsupported,
            "a semantic claim must cite semantic context"
          )
      case "definition" =>
        val term = Facts.s(a, "term")
        if term.flatMap(Glossary.text).contains(s.claim.text) then
          Check.Ok(Vector.empty)
        else
          Check.Bad(
            VerificationVerdict.Unsupported,
            "a definition must carry the fixed glossary text"
          )
      case "abstention" =>
        if s.claim.uncertainty.exists(_.trim.nonEmpty) then
          Check.Ok(Vector.empty)
        else
          Check.Bad(
            VerificationVerdict.Unsupported,
            "an abstention must state its reason"
          )
      case _ =>
        Check.Bad(VerificationVerdict.Unsupported, "unknown claim kind")

  private def pathCheck(s: Subject, a: ujson.Value): Check =
    val src = Facts.s(a, "source")
    val tgt = Facts.s(a, "target")
    val paths = ofKind(s, "path").filter(f =>
      pair(f, "source", "target", src, tgt) && windowOk(a, f)
    )
    if paths.isEmpty then
      val none = ofKind(s, "path-status").exists(f =>
        pair(f, "source", "target", src, tgt) &&
          Facts.b(f, "noPathExists").contains(true)
      )
      if none then conflict("the cited evidence shows that no path exists")
      else noFact(s, "path")
    else
      val hopCountBad =
        sameNumber(a, "hopCount", paths, "hopCount", "hop count")
      val hopBad = Facts.n(a, "hopIndex") match
        case None        => None
        case Some(index) =>
          val hops = paths.flatMap(f => Facts.list(f, "hops"))
          hops.find(h => Facts.n(h, "hopIndex").contains(index)) match
            case None    => Some(conflict("the cited path has no such hop"))
            case Some(h) =>
              if !pair(
                  h,
                  "from",
                  "to",
                  Facts.s(a, "from"),
                  Facts.s(a, "to")
                )
              then Some(conflict("the hop joins different researchers"))
              else
                sameNumber(
                  a,
                  "jointPublicationCount",
                  Vector(h),
                  "jointPublicationCount",
                  "hop publication count"
                )
      hopCountBad
        .orElse(hopBad)
        .getOrElse(Check.Ok(narrow(paths, a, "hopCount", "hopCount")))

  /** Every quantity in a text, digits and spelled. Names, titles and other fact
    * strings are set aside first, so digits inside a name or an identifier are
    * not read as quantities.
    */
  private def quantities(
      text: String,
      facts: Vector[ujson.Value]
  ): Vector[BigDecimal] =
    val strings = facts
      .flatMap(Facts.strings)
      .filter(_.length >= 3)
      .distinct
      .sortBy(s => -s.length)
    val stripped = strings.foldLeft(text) { (t, s) =>
      t.replaceAll("(?i)" + Pattern.quote(s), " ")
    }
    numberPattern.findAllIn(stripped).toVector.map(BigDecimal(_)) ++
      spelled(stripped)

  /** The quantities of the claim's own wording that the system-rendered form of
    * the asserted claim does not hold. The rendered form is written from the
    * fields the assertion names, so a figure that equals some other field of
    * the fact (a year, a count of something else) is still reported. A figure
    * written to fewer decimals than the rendered one is accepted when it is the
    * rounding of it.
    */
  private def unsupportedNumbers(
      text: String,
      rendered: String,
      facts: Vector[ujson.Value]
  ): Vector[String] =
    val allowed = quantities(rendered, facts)
    quantities(text, facts)
      .filterNot(n =>
        allowed.exists(a =>
          a == n ||
            (n.scale > 0 && a.setScale(
              n.scale,
              BigDecimal.RoundingMode.HALF_UP
            ) == n)
        )
      )
      .map(_.toString)
      .distinct

  /** Facts that hold the value the assertion claims under `key`, so the wording
    * the system renders is written from the fact that matched.
    */
  private def narrow(
      facts: Vector[ujson.Value],
      a: ujson.Value,
      key: String,
      field: String
  ): Vector[ujson.Value] =
    Facts.n(a, key) match
      case None          => facts
      case Some(claimed) =>
        val same =
          facts.filter(f => Facts.n(f, field).exists(approx(claimed, _)))
        if same.isEmpty then facts else same

  private final case class Checked(
      draft: ClaimDraft,
      verdict: VerificationVerdict,
      reasons: Vector[String],
      support: Vector[ujson.Value],
      existing: Vector[String],
      shown: Option[String] = None,
      uncertainty: Option[String] = None
  )

  private def factOf(item: EvidenceItem): Option[ujson.Value] =
    if item.evidenceType == EvidenceType.TextChunk then None
    else
      item.text
        .flatMap(t => Try(ujson.read(t)).toOption)
        .filter(_.objOpt.isDefined)

  private def check(
      draft: ClaimDraft,
      byId: Map[String, EvidenceItem]
  ): Checked =
    val existing = draft.evidenceIds.filter(byId.contains).distinct
    val missing = draft.evidenceIds.filterNot(byId.contains)
    val cited = existing.map(byId)
    val facts = cited.flatMap(factOf)
    val chunks = cited.filter(_.evidenceType == EvidenceType.TextChunk)
    val subject = Subject(draft, facts, chunks.nonEmpty)
    val reasons = mutable.ArrayBuffer.empty[String]
    if missing.nonEmpty then
      reasons += s"${missing.size} cited evidence ID(s) do not exist in the bundle"
    val needsEvidence = !NonFactual.contains(draft.kind)
    if needsEvidence && draft.evidenceIds.isEmpty then
      Checked(
        draft,
        VerificationVerdict.Unsupported,
        reasons.toVector :+ "the claim cites no evidence",
        Vector.empty,
        existing
      )
    else
      structural(subject) match
        case Check.Bad(verdict, reason) =>
          Checked(
            draft,
            verdict,
            reasons.toVector :+ reason,
            Vector.empty,
            existing
          )
        case Check.Ok(support) =>
          // The wording an answer shows is always system-written: fixed text for
          // semantic, definition and abstention claims, and the rendering of the
          // asserted value for every other kind. The model's wording only earns
          // or loses the Verified label.
          val shown: Option[String] = draft.kind match
            case "semantic"   => Some(Rendering.SemanticText)
            case "abstention" => Some(Rendering.AbstentionText)
            case "definition" => Some(draft.text)
            case kind => Rendering.render(kind, draft.assertion, support)
          shown match
            case None =>
              Checked(
                draft,
                VerificationVerdict.Unsupported,
                reasons.toVector :+ "no system-written wording exists for this assertion",
                Vector.empty,
                existing
              )
            case Some(wording) =>
              val readsNumbers =
                draft.kind != "definition" && draft.kind != "abstention"
              val bad =
                if readsNumbers then
                  unsupportedNumbers(draft.text, wording, facts)
                else Vector.empty
              if bad.nonEmpty then
                val inSnippet =
                  chunks.exists(c =>
                    bad.exists(n => c.text.exists(_.contains(n)))
                  )
                reasons += s"${bad.size} number(s) in the claim wording are not in the asserted value or any cited deterministic fact" +
                  (if inSnippet then
                     "; they appear only in semantic context, which cannot support a number (F1)"
                   else "")
              val reworded = draft.kind == "semantic" && draft.text != wording
              if reworded then
                reasons += "the wording of a semantic claim is system-written and the model's wording differs"
              val verdict =
                if bad.nonEmpty || missing.nonEmpty || reworded then
                  VerificationVerdict.PartiallySupported
                else VerificationVerdict.Verified
              val caveats =
                cited
                  .filter(_.evidenceType != EvidenceType.TextChunk)
                  .flatMap(_.uncertainty)
                  .distinct
              Checked(
                draft,
                verdict,
                reasons.toVector,
                support,
                existing,
                Some(wording),
                if caveats.isEmpty then None else Some(caveats.mkString("; "))
              )

  private def overall(
      material: Vector[ClaimVerification]
  ): Option[VerificationVerdict] =
    val verdicts = material.map(_.verdict)
    if verdicts.isEmpty then None
    else if verdicts.forall(_ == VerificationVerdict.Verified) then
      Some(VerificationVerdict.Verified)
    else if verdicts.contains(VerificationVerdict.Conflicting) then
      Some(VerificationVerdict.Conflicting)
    else if verdicts.forall(_ == VerificationVerdict.Unsupported) then
      Some(VerificationVerdict.Unsupported)
    else Some(VerificationVerdict.PartiallySupported)

  /** Verifies the drafts and builds the final answer. `gaps` are the system's
    * own notes on missing evidence; the model's `missingEvidence` is recorded
    * in the audit only, because it is model text.
    */
  def finalizeAnswer(
      bundle: EvidenceBundle,
      output: SynthesisOutput,
      gaps: Vector[String],
      provenance: String => Provenance
  ): FinalAnswer =
    val byId = bundle.items.map(i => i.evidenceId -> i).toMap
    val checked = output.claims.map(check(_, byId))
    val claims = mutable.ArrayBuffer.empty[AnswerClaim]
    val lines = mutable.ArrayBuffer.empty[String]
    val definitions = mutable.ArrayBuffer.empty[String]
    val changes = mutable.ArrayBuffer.empty[ClaimChange]
    var removed = 0
    var substantive = 0
    checked.foreach { c =>
      val d = c.draft
      val reason = c.reasons.mkString("; ")
      c.verdict match
        case VerificationVerdict.Verified =>
          val wording = c.shown.getOrElse(d.text)
          changes += ClaimChange(
            d.claimId,
            "kept",
            if wording == d.text then "verified against the cited evidence"
            else
              "verified against the cited evidence; the model's wording was replaced by the system-written wording"
          )
          if d.kind == "definition" then definitions += wording
          else if d.kind == "abstention" then
            claims += AnswerClaim(
              ContractSchema.currentVersion,
              d.claimId,
              wording,
              c.existing,
              VerificationStatus.Uncertain,
              Some(
                if gaps.isEmpty then Rendering.AbstentionUncertainty
                else "Not established: " + gaps.mkString(" ")
              ),
              provenance(d.claimId)
            )
            lines += wording
          else
            claims += AnswerClaim(
              ContractSchema.currentVersion,
              d.claimId,
              wording,
              c.existing,
              VerificationStatus.Verified,
              c.uncertainty,
              provenance(d.claimId)
            )
            lines += wording
            substantive += 1
        case VerificationVerdict.PartiallySupported =>
          val weakenable =
            !NonFactual.contains(d.kind) && d.kind != "semantic"
          c.shown.filter(_ => weakenable) match
            case Some(weak) if c.existing.nonEmpty =>
              changes += ClaimChange(
                d.claimId,
                "weakened",
                s"rewritten to the part the evidence supports: $reason"
              )
              claims += AnswerClaim(
                ContractSchema.currentVersion,
                d.claimId,
                weak,
                c.existing,
                VerificationStatus.Uncertain,
                Some(reason),
                provenance(d.claimId)
              )
              lines += s"(Partly supported) $weak"
              substantive += 1
            case _ =>
              removed += 1
              changes += ClaimChange(
                d.claimId,
                "removed",
                s"no supported part could be kept: $reason"
              )
        case VerificationVerdict.Unsupported |
            VerificationVerdict.Conflicting =>
          removed += 1
          changes += ClaimChange(
            d.claimId,
            "removed",
            s"${c.verdict.wire}: $reason"
          )
    }
    // An abstention claim says that something is missing; it answers nothing.
    // With no other claim kept the answer abstains, and the workflow records
    // the system-written reason code for it.
    val abstained = substantive == 0
    // The visible analysis is fixed system text. A model paragraph is never
    // shown, so a replacement is recorded whenever the model wrote another.
    val modelAnalysis = output.analysis.trim
    if modelAnalysis.nonEmpty && modelAnalysis != Rendering.AnalysisText then
      changes += ClaimChange(
        "analysis",
        if abstained then "removed" else "weakened",
        if abstained then
          "the model's analysis paragraph is not shown because no claim was kept"
        else
          "the model's analysis paragraph was replaced by the system-written analysis"
      )
    val report = VerificationReport(
      overall(
        checked
          .filterNot(c => NonFactual.contains(c.draft.kind))
          .map(c =>
            ClaimVerification(
              c.draft.claimId,
              c.draft.kind,
              c.verdict,
              c.reasons
            )
          )
      ),
      checked.count(c => !NonFactual.contains(c.draft.kind)),
      checked.map(c =>
        ClaimVerification(c.draft.claimId, c.draft.kind, c.verdict, c.reasons)
      ),
      changes.toVector
    )
    val body =
      if abstained then
        Vector(
          "The stored evidence does not support an answer to this question."
        )
      else
        lines.toVector ++ definitions ++
          (if modelAnalysis.nonEmpty then Vector(Rendering.AnalysisText)
           else Vector.empty)
    val missing =
      if gaps.isEmpty then Vector.empty
      else Vector("Not established: " + gaps.mkString(" "))
    val withheld =
      if removed == 0 then Vector.empty
      else
        Vector(
          s"$removed claim(s) were withheld because the stored evidence did not support them."
        )
    FinalAnswer(
      (body ++ missing ++ withheld).mkString("\n"),
      claims.toVector,
      report,
      abstained
    )
