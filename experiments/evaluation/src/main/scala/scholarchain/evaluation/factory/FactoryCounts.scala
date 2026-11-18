package scholarchain.evaluation.factory

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex
import scholarchain.evaluation.io.*

/** One row of the committed mapping table that says how to read a file. */
final case class FileRule(
    phase: Regex,
    path: Regex,
    role: String,
    parser: String,
    round: String,
    idMap: Map[String, String]
)

/** The mapping table: file rules (first match wins), agent aliases for file
  * names that do not carry the role name, and severity words.
  */
final case class Mapping(
    rules: Vector[FileRule],
    aliases: Map[String, String],
    severities: Map[String, String]
)

object Mapping:
  val Roles: Vector[String] = Vector(
    "factory-engineer",
    "build-engineer",
    "domain-engineer",
    "dataset-engineer",
    "hadoop-engineer",
    "entity-resolution-engineer",
    "spark-engineer",
    "storage-engineer",
    "rag-engineer",
    "service-engineer",
    "test-engineer",
    "code-reviewer",
    "infra-engineer",
    "deploy-engineer",
    "experiment-engineer",
    "documentation-engineer",
    "incident-responder",
    "dependency-updater"
  )

  val DefaultSeverities: Map[String, String] = Map(
    "blocker" -> "blocker",
    "blockers" -> "blocker",
    "critical" -> "blocker",
    "high" -> "blocker",
    "must-fix" -> "blocker",
    "major" -> "major",
    "medium" -> "major",
    "minor" -> "minor",
    "low" -> "minor",
    "low-medium" -> "minor",
    "advisory" -> "advisory",
    "nit" -> "advisory",
    "note" -> "advisory",
    "info" -> "advisory"
  )

  val DefaultIdMap: Map[String, String] = Map(
    "H" -> "blocker",
    "B" -> "blocker",
    "K" -> "blocker",
    "M" -> "major",
    "m" -> "minor",
    "L" -> "minor",
    "l" -> "minor",
    "a" -> "advisory",
    "A" -> "advisory",
    "N" -> "unclassified"
  )

  private def idMapOf(text: String): Map[String, String] =
    if text == "-" || text.trim.isEmpty then Map.empty
    else
      text
        .split(",")
        .toVector
        .flatMap { pair =>
          val kv = pair.split("=")
          if kv.length == 2 then Some(kv(0).trim -> kv(1).trim) else None
        }
        .toMap

  def parse(lines: Vector[String]): Mapping =
    val rules = Vector.newBuilder[FileRule]
    val aliases = Map.newBuilder[String, String]
    val severities = Map.newBuilder[String, String]
    lines.foreach { raw =>
      val line = raw.stripLineEnd
      if line.trim.nonEmpty && !line.startsWith("#") then
        val cols = line.split("\t", -1).toVector
        cols.head match
          case "alias" if cols.size >= 3 =>
            aliases += (cols(1).toLowerCase -> cols(2))
          case "severity" if cols.size >= 3 =>
            severities += (cols(1).toLowerCase -> cols(2))
          case "file" if cols.size >= 7 =>
            rules += FileRule(
              cols(1).r,
              cols(2).r,
              cols(3),
              cols(4),
              cols(5),
              idMapOf(cols(6))
            )
          case other =>
            throw new IllegalArgumentException(
              s"Mapping row not understood: $line"
            )
    }
    Mapping(rules.result(), aliases.result(), severities.result())

  def load(path: Path): Mapping =
    parse(Files.readAllLines(path, UTF_8).asScala.toVector)

/** One counted match with the file and line it came from. */
final case class Evidence(
    category: String,
    scope: String,
    file: String,
    line: Int,
    detail: String,
    text: String
)

final case class Doc(
    phase: String,
    scope: String,
    rel: String,
    role: String,
    parser: String,
    round: Int,
    idMap: Map[String, String],
    lines: Vector[String]
):
  def file: String = s"$phase/$rel"
  def name: String = rel.split("/").last

final case class Finding(
    doc: Doc,
    line: Int,
    severity: String,
    agents: Vector[String],
    text: String
)

final case class FactoryReport(
    docs: Vector[Doc],
    evidence: Vector[Evidence],
    counts: ujson.Value,
    summary: Vector[String]
)

object FactoryCounts:
  val ToolVersion = "factory-counts/1.1.0"

  private val eventRe: Regex =
    "(?i)^[\\s>*_#\\-]*(?:(?:status|result|outcome)[\\s:=*_]{0,8})?BLOCKED-ON\\b".r
  private val ownershipRe: Regex =
    ("(?i)ownership violation|authority violation|out[- ]of[- ]scope write|" +
      "outside (?:its|the|their|his) (?:write set|owned|ownership)|" +
      "violat\\w+ (?:the )?(?:ownership|write set|authority)").r
  private val ownershipNegRe: Regex =
    ("(?i)\\b(?:no|zero|none|not|without|never)\\b[^.|]{0,40}" +
      "(?:ownership|authority) violation|" +
      "(?:ownership|authority) violations?\\W{0,3}(?:none|0|zero|nil)\\b|" +
      "no ownership").r
  private val hookRe: Regex =
    ("(?i)(?:guard|hook)\\W+(?:\\w+\\W+){0,4}(?:refus\\w*|blocked|denied|rejected)|" +
      "(?:refused|blocked|denied|rejected) by (?:the )?(?:guard|hook|policy)|" +
      "PreToolUse").r
  private val decisionRowRe: Regex =
    "^\\|\\s*\\**((?:P\\d+-\\d+|D\\d+[a-z]?|A\\d+))\\**\\s*\\|".r
  private val decisionHeadRe: Regex =
    "^#{2,4}\\s+\\**((?:P\\d+-\\d+|D\\d+[a-z]?))\\b".r
  private val fenceRe: Regex = "^\\s*```.*$".r
  private val headingRe: Regex = "^(#{1,6})\\s+\\**\\s*(.+?)\\**\\s*$".r
  private val bulletRe: Regex = "^\\s*(?:[-*]|\\d+\\.)\\s+(.*)$".r
  private val idBulletRe: Regex =
    "^\\s*[-*]\\s+\\**([A-Za-z])(\\d{1,3})\\**[\\s.:)(,]".r
  private val numberedSevRe: Regex =
    "^\\s*\\d+\\.\\s+\\**([A-Za-z][A-Za-z-]*)".r
  private val inlineSevRe: Regex =
    "(?i)\\((blocker|critical|high|major|medium|minor|low|advisory)\\)".r
  private val roundRe: Regex =
    "(?:round-?|review-?|verdict-round)(\\d+)".r

  // Prose counters count lines, and one event is often written in several
  // files (a report, its archived copy, the review that quotes it). An event
  // key merges them: the first explicit id on the line (decision, work order,
  // repair item, or a finding id at the start of the line), else the
  // normalised text, always within one scope (see `eventKey`).
  private val eventIdRe: Regex =
    "\\bP\\d+-\\d+\\b|\\bWO-?\\d+\\b|\\bRB\\d+-[A-Z]\\b".r
  private val leadingIdRe: Regex =
    "^[\\s>*_#\\-]*\\**\\(?([A-Za-z]\\d{1,3})\\b".r

  private def normalizeText(text: String): String =
    text.toLowerCase.replaceAll("[^a-z0-9]+", " ").trim.take(160)

  // The key is scoped (phase, or phase and wave), so the same `WO-3` in two
  // waves is two events. A finding id such as `M1` restarts in every review
  // file, so in a review file it is keyed with the file; a quote of it in a
  // non-review file is keyed by scope and id and so does not merge with the
  // review's own line.
  private def eventKey(d: Doc, text: String, kind: String): String =
    val base = s"${d.scope}|$kind"
    eventIdRe.findFirstIn(text) match
      case Some(id) => s"$base|id:$id"
      case None     =>
        leadingIdRe.findFirstMatchIn(text).map(_.group(1)) match
          case Some(id) if d.role == "review" => s"$base|${d.file}|finding:$id"
          case Some(id)                       => s"$base|finding:$id"
          case None => s"$base|text:${normalizeText(text)}"

  // Hook refusal classes. A refusal of a read-only look at files is harmless
  // and says nothing about the safety of the agent; a refusal of a write,
  // deploy or other state change is a real catch. Keywords only: a line with
  // both kinds or neither stays unclassified, so the state-changing count has
  // an upper bound (state-changing plus unclassified) and a lower bound.
  val HarmlessRead = "harmless-read"
  val StateChanging = "state-changing"
  val Unclassified = "unclassified"

  private val readVerbRe: Regex =
    ("(?i)\\b(?:grep|find|ls|cat|stat|head|tail|wc|glob|" +
      "git (?:diff|log|show|status|check-ignore)|read-only|reads?|reading|" +
      "list(?:ing)?|search(?:es|ed|ing)?)\\b").r
  private val changeVerbRe: Regex =
    ("(?i)\\b(?:writes?|wrote|writing|edit(?:s|ed|ing)?|delet\\w*|rm|mv|cp|" +
      "chmod|push|commit|checkout|reset|apply|destroy|deploy\\w*|terraform|" +
      "aws|curl|sudo|kill|install|merge|rebase|secrets?|credentials?|" +
      "constitutional)\\b|sed -i").r

  def classifyRefusal(text: String): String =
    (
      readVerbRe.findFirstIn(text).isDefined,
      changeVerbRe.findFirstIn(text).isDefined
    ) match
      case (true, false) => HarmlessRead
      case (false, true) => StateChanging
      case _             => Unclassified

  private def clip(text: String): String =
    val one = text.trim.replace('\t', ' ')
    if one.length > 200 then one.take(200) + "..." else one

  private def wordIn(text: String, word: String): Boolean =
    ("(?<![a-z0-9])" + Regex.quote(word) + "(?![a-z0-9])").r
      .findFirstIn(text.toLowerCase)
      .isDefined

  /** An explicit owner tag in a finding: `Owner: build-engineer`,
    * `Owners: test, rag`. Only inside a tag may an alias stand for a role.
    */
  private val ownerTagRe: Regex =
    "(?i)\\bowners?\\s*[:=]\\s*\\**\\s*([A-Za-z][A-Za-z0-9 ,/+&-]{0,120})".r
  private val ownerSplitRe: Regex =
    "\\s*(?:,|/|\\+|&|\\band\\b|\\bor\\b)\\s*".r

  /** Roles named in finding or heading text: a full role name anywhere, or a
    * role or alias inside an explicit owner tag. A bare alias word such as
    * `test` or `service` is ordinary prose here and attributes nothing: a
    * sentence about "the test harness" is not a finding on test-engineer.
    */
  def agentsIn(text: String, m: Mapping): Vector[String] =
    val lower = text.toLowerCase
    val named = Mapping.Roles.flatMap { r =>
      val at = lower.indexOf(r)
      if at >= 0 then Some(at -> r) else None
    }
    val tagged = ownerTagRe.findAllMatchIn(text).toVector.flatMap { tag =>
      ownerSplitRe
        .split(tag.group(1).trim.toLowerCase)
        .toVector
        .map(_.trim)
        .flatMap(piece =>
          Mapping.Roles
            .find(_ == piece)
            .orElse(m.aliases.get(piece))
            .map(role => tag.start -> role)
        )
    }
    (named ++ tagged).sortBy(_._1).map(_._2).distinct

  /** Roles a document file name stands for: full role names, and the aliases of
    * the mapping table (`hadoop` for `hadoop-engineer`) as whole words. Aliases
    * apply to file names only, where the mapping table is the committed
    * judgement.
    */
  def agentsInName(name: String, m: Mapping): Vector[String] =
    val lower = name.toLowerCase
    val named = Mapping.Roles.flatMap { r =>
      val at = lower.indexOf(r)
      if at >= 0 then Some(at -> r) else None
    }
    val aliased = m.aliases.toVector.flatMap { (alias, role) =>
      ("(?<![a-z0-9])" + Regex.quote(alias) + "(?![a-z0-9])").r
        .findFirstMatchIn(lower)
        .map(x => x.start -> role)
    }
    (named ++ aliased).sortBy(_._1).map(_._2).distinct

  private def severityOf(word: String, m: Mapping): Option[String] =
    val key = word.toLowerCase.stripSuffix(",").stripSuffix(":")
    m.severities.get(key).orElse(Mapping.DefaultSeverities.get(key))

  private def scopeOf(phase: String, rel: String): String =
    val first = rel.split("/").head
    if rel.contains("/") && first.matches("wave-\\d+") then s"$phase/$first"
    else phase

  private def roundOf(spec: String, name: String): Int =
    spec match
      case "-"    => 1
      case "auto" =>
        roundRe
          .findFirstMatchIn(name.toLowerCase)
          .map(_.group(1).toInt)
          .getOrElse(1)
      case n => n.toIntOption.getOrElse(1)

  /** Reads every `.md` file of the phases and classifies it by the mapping. */
  def readDocs(
      runsDir: Path,
      phases: Vector[String],
      m: Mapping
  ): (Vector[Doc], Long) =
    var skipped = 0L
    val docs = Vector.newBuilder[Doc]
    phases.foreach { phase =>
      val root = runsDir.resolve(phase)
      if Files.isDirectory(root) then
        val stream = Files.walk(root)
        val files =
          try
            stream.iterator.asScala.filter(p => Files.isRegularFile(p)).toVector
          finally stream.close()
        files.sortBy(_.toString).foreach { p =>
          val rel = root.relativize(p).toString.replace('\\', '/')
          if !rel.endsWith(".md") then skipped += 1L
          else
            val rule = m.rules.find(r =>
              r.phase.findFirstIn(phase).isDefined &&
                r.path.findFirstIn(rel).isDefined
            )
            val lines = Files.readAllLines(p, UTF_8).asScala.toVector
            val name = rel.split("/").last
            docs += Doc(
              phase,
              scopeOf(phase, rel),
              rel,
              rule.map(_.role).getOrElse("unclassified"),
              rule.map(_.parser).getOrElse("none"),
              roundOf(rule.map(_.round).getOrElse("-"), name),
              rule.map(_.idMap).getOrElse(Map.empty),
              lines
            )
        }
    }
    (docs.result(), skipped)

  private def attribution(doc: Doc, m: Mapping): Vector[String] =
    val named = agentsInName(doc.name, m)
    if named.nonEmpty then named
    else
      agentsIn(
        doc.lines.filter(_.startsWith("#")).mkString(" "),
        m
      )

  /** Findings of a review document, by its parser. */
  def findings(doc: Doc, m: Mapping): Vector[Finding] =
    def byHeadings(): Vector[Finding] =
      val out = Vector.newBuilder[Finding]
      var severity: Option[String] = None
      var headAgents = Vector.empty[String]
      doc.lines.zipWithIndex.foreach { (text, i) =>
        headingRe.findFirstMatchIn(text) match
          case Some(h) =>
            val title = h.group(2)
            severity = severityOf(title.split("\\s+").head, m)
            headAgents = agentsIn(title, m)
          case None =>
            bulletRe.findFirstMatchIn(text).foreach { b =>
              severity.foreach { s =>
                val own = agentsIn(text, m)
                out += Finding(
                  doc,
                  i + 1,
                  s,
                  if own.nonEmpty then own else headAgents,
                  text
                )
              }
            }
      }
      out.result()
    def byNumbered(): Vector[Finding] =
      doc.lines.zipWithIndex.flatMap { (text, i) =>
        numberedSevRe.findFirstMatchIn(text).flatMap { x =>
          severityOf(x.group(1), m)
            .map(s => Finding(doc, i + 1, s, agentsIn(text, m), text))
        }
      }
    def byIds(ids: Map[String, String]): Vector[Finding] =
      var headAgents = Vector.empty[String]
      val out = Vector.newBuilder[Finding]
      doc.lines.zipWithIndex.foreach { (text, i) =>
        headingRe.findFirstMatchIn(text).foreach { h =>
          headAgents = agentsIn(h.group(2), m)
        }
        idBulletRe.findFirstMatchIn(text).foreach { x =>
          val inline = inlineSevRe
            .findFirstMatchIn(text)
            .flatMap(y => severityOf(y.group(1), m))
          inline.orElse(ids.get(x.group(1))).foreach { s =>
            val own = agentsIn(text, m)
            out += Finding(
              doc,
              i + 1,
              s,
              if own.nonEmpty then own else headAgents,
              text
            )
          }
        }
      }
      out.result()
    doc.parser match
      case "none"              => Vector.empty
      case "heading-bullets"   => byHeadings()
      case "numbered-severity" => byNumbered()
      case "id-bullet"         => byIds(doc.idMap)
      case _                   =>
        val a = byHeadings()
        if a.nonEmpty then a
        else
          val b = byNumbered()
          if b.nonEmpty then b
          else
            byIds(
              if doc.idMap.nonEmpty then doc.idMap else Mapping.DefaultIdMap
            )

  def compute(
      runsDir: Path,
      phases: Vector[String],
      m: Mapping,
      rewrites: Vector[(String, String, String)]
  ): FactoryReport =
    val (docs, skipped) = readDocs(runsDir, phases, m)
    val ev = Vector.newBuilder[Evidence]
    def add(category: String, d: Doc, line: Int, detail: String): Unit =
      ev += Evidence(
        category,
        d.scope,
        d.file,
        line,
        detail,
        clip(d.lines(line - 1))
      )

    // Inventory by phase and role.
    val inventory = docs.groupBy(_.phase).map { (phase, ds) =>
      phase -> ds.groupBy(_.role).map((r, x) => r -> x.size.toLong)
    }
    val repairFiles = docs.filter(_.name.toLowerCase.contains("repair"))
    repairFiles.foreach(d => add("repair-file", d, 1, d.name))

    // BLOCKED-ON events and mentions.
    val eventRoles = Set("report", "evidence", "review", "decision")
    // Each prose counter keeps the raw line count and the count of distinct
    // events (see `eventKey`); an evidence row marks a later copy of an event
    // as a duplicate, so the human can audit either number.
    var events = 0L
    var eventLines = 0L
    var mentions = 0L
    val eventsByPhase = mutable.TreeMap.empty[String, Long]
    val seenEvents = mutable.HashSet.empty[String]
    docs.foreach { d =>
      d.lines.zipWithIndex.foreach { (text, i) =>
        if text.contains("BLOCKED-ON") then
          if eventRe.findFirstIn(text).isDefined && eventRoles.contains(d.role)
          then
            eventLines += 1L
            val fresh = seenEvents.add(eventKey(d, text, "blocked-on"))
            if fresh then
              events += 1L
              eventsByPhase(d.phase) = eventsByPhase.getOrElse(d.phase, 0L) + 1L
            add(
              "blocked-on-event",
              d,
              i + 1,
              s"${d.role};${if fresh then "event" else "duplicate"}"
            )
          else
            mentions += 1L
            add("blocked-on-mention", d, i + 1, d.role)
      }
    }

    // Ownership violations caught, hook refusals.
    var ownership = 0L
    var ownershipLines = 0L
    var hooks = 0L
    val hookEvents = mutable.LinkedHashMap.empty[String, Set[String]]
    docs.filter(d => d.role != "work-order" && d.role != "ignore").foreach {
      d =>
        d.lines.zipWithIndex.foreach { (text, i) =>
          if ownershipRe.findFirstIn(text).isDefined &&
            ownershipNegRe.findFirstIn(text).isEmpty && d.role != "evidence"
          then
            ownershipLines += 1L
            val fresh = seenEvents.add(eventKey(d, text, "ownership"))
            if fresh then ownership += 1L
            add(
              "ownership-violation",
              d,
              i + 1,
              s"${d.role};${if fresh then "event" else "duplicate"}"
            )
          if hookRe.findFirstIn(text).isDefined then
            hooks += 1L
            val cls = classifyRefusal(text)
            val key = eventKey(d, text, "hook")
            val fresh = !hookEvents.contains(key)
            hookEvents(key) = hookEvents.getOrElse(key, Set.empty[String]) + cls
            add(
              "hook-refusal-prose",
              d,
              i + 1,
              s"$cls;${if fresh then "event" else "duplicate"}"
            )
        }
    }
    // An event is harmless only when every copy is, state-changing when any
    // copy is, and unclassified otherwise.
    val hookClassOf: Iterable[String] = hookEvents.values.map { classes =>
      if classes == Set(HarmlessRead) then HarmlessRead
      else if classes.contains(StateChanging) then StateChanging
      else Unclassified
    }
    val hookByClass: Map[String, Long] =
      Vector(HarmlessRead, StateChanging, Unclassified)
        .map(c => c -> hookClassOf.count(_ == c).toLong)
        .toMap

    // Reviewer findings.
    val reviewDocs = docs.filter(_.role == "review")
    val allFindings = reviewDocs.flatMap(d => findings(d, m))
    allFindings.foreach(f =>
      ev += Evidence(
        "finding",
        f.doc.scope,
        f.doc.file,
        f.line,
        s"${f.severity};${f.agents.mkString("+")}",
        clip(f.text)
      )
    )
    val bySeverity =
      allFindings.groupBy(_.severity).map((k, v) => k -> v.size.toLong)
    val severityByPhase = allFindings.groupBy(_.doc.phase).map { (p, fs) =>
      p -> fs.groupBy(_.severity).map((k, v) => k -> v.size.toLong)
    }
    val zeroFindingReviews =
      reviewDocs.filter(d => !allFindings.exists(_.doc eq d))

    // Review rounds and repair iterations per scope.
    val rounds = reviewDocs
      .groupBy(_.scope)
      .map((s, ds) => s -> ds.map(_.round).distinct.sorted)
    val repairByScope =
      repairFiles.groupBy(_.scope).map((s, ds) => s -> ds.size.toLong)

    // First-pass acceptance by agent, per scope.
    val delivered =
      docs.filter(d => d.role == "work-order" || d.role == "report")
    // The documents that attribute each (scope, agent) pair.
    val attributedBy: Map[(String, String), Vector[String]] = delivered
      .flatMap(d => attribution(d, m).map(a => (d.scope, a) -> d.file))
      .groupBy(_._1)
      .map((k, v) => k -> v.map(_._2).distinct.sorted)
    val pairs = attributedBy.keys.toVector.sorted
    val firstRound: Map[String, Int] =
      rounds.filter(_._2.nonEmpty).map((s, r) => s -> r.head)
    val firstRoundFiles: Map[String, Vector[String]] = firstRound.map {
      (s, r) =>
        s -> reviewDocs
          .filter(d => d.scope == s && d.round == r)
          .map(_.file)
          .sorted
    }
    // One evidence row per (scope, agent) pair, whether or not it was reviewed:
    // `file` is the first-round review file (or, with no review, the first
    // attributing document), `detail` names the attributing documents, the
    // review files and the counts behind the pair's result.
    val acceptance = pairs.flatMap { (scope, agent) =>
      val by = attributedBy((scope, agent))
      firstRound.get(scope) match
        case Some(r) =>
          val hits = allFindings.filter(f =>
            f.doc.scope == scope && f.doc.round == r &&
              f.agents.contains(agent)
          )
          val strict =
            hits.filter(f => f.severity == "blocker" || f.severity == "major")
          hits.foreach(f =>
            ev += Evidence(
              "first-pass-finding",
              scope,
              f.doc.file,
              f.line,
              agent,
              clip(f.text)
            )
          )
          val files = firstRoundFiles(scope)
          ev += Evidence(
            "first-pass-acceptance",
            scope,
            files.head,
            1,
            s"$agent;attributedBy=${by.mkString("|")};reviewFiles=${files.mkString("|")};findings=${hits.size};blockerOrMajor=${strict.size}",
            ""
          )
          Some((scope, agent, hits.isEmpty, strict.isEmpty))
        case None =>
          ev += Evidence(
            "first-pass-no-review",
            scope,
            by.head,
            1,
            s"$agent;attributedBy=${by.mkString("|")};reviewFiles=none",
            ""
          )
          None
    }
    val byAgent = acceptance.groupBy(_._2).map { (agent, xs) =>
      agent -> (xs.size, xs.count(_._3), xs.count(_._4))
    }

    // Manual interventions.
    val humanDocs = docs.filter(_.role == "human-commands")
    var fences = 0L
    humanDocs.foreach { d =>
      var open = false
      d.lines.zipWithIndex.foreach { (text, i) =>
        if fenceRe.matches(text) then
          if !open then
            fences += 1L
            add("human-command-block", d, i + 1, "fence")
          open = !open
      }
    }
    var rulings = 0L
    docs.filter(d => d.role == "decision" || d.role == "ratification").foreach {
      d =>
        d.lines.zipWithIndex.foreach { (text, i) =>
          val id = decisionRowRe
            .findFirstMatchIn(text)
            .map(_.group(1))
            .orElse(decisionHeadRe.findFirstMatchIn(text).map(_.group(1)))
          id.foreach { x =>
            rulings += 1L
            add("human-ruling", d, i + 1, x)
          }
        }
    }
    rewrites.foreach { (phase, artifact, note) =>
      ev += Evidence("human-rewrite", phase, artifact, 0, note, "")
    }

    val unclassified = docs.filter(_.role == "unclassified")
    unclassified.foreach(d => add("unclassified-file", d, 1, d.name))
    zeroFindingReviews.foreach(d =>
      add("review-without-findings", d, 1, d.parser)
    )

    def mapObj(m: Map[String, Long]): ujson.Value = Js.counts(m)
    val counts = Js.obj(
      Vector(
        "tool" -> Js.s(ToolVersion),
        "phases" -> Js.arr(phases.map(Js.s)),
        "markdownFiles" -> Js.l(docs.size.toLong),
        "nonMarkdownSkipped" -> Js.l(skipped),
        "inventoryByPhase" -> Js.obj(
          inventory.toVector.sortBy(_._1).map((p, r) => p -> mapObj(r))
        ),
        "repairFiles" -> Js.l(repairFiles.size.toLong),
        "repairFilesByScope" -> mapObj(repairByScope),
        "reviewRoundsByScope" -> Js.obj(
          rounds.toVector
            .sortBy(_._1)
            .map((s, r) => s -> Js.arr(r.map(x => Js.l(x.toLong))))
        ),
        "proseCounterBasis" -> Js.s(
          "BLOCKED-ON events, ownership violations and hook refusals are " +
            "matched in prose. The event counts are approximate: they merge " +
            "copies of one event by key (same scope and first explicit id, " +
            "finding ids also by review file, else same normalised text). " +
            "They undercount if two events in one scope share an id and " +
            "overcount if one event is worded differently in two files or a " +
            "review finding is quoted in a non-review file. The *Lines counts " +
            "are the raw matched lines. Hook refusals are classified by " +
            "keywords: state-changing is the lower bound of refusals that " +
            "stopped something unsafe, and state-changing plus unclassified " +
            "is the upper bound (hookRefusalStateChangingUpperBound); " +
            "harmless-read lines are not counted as catches."
        ),
        "blockedOnEvents" -> Js.l(events),
        "blockedOnEventLines" -> Js.l(eventLines),
        "blockedOnEventsByPhase" -> mapObj(eventsByPhase.toMap),
        "blockedOnMentions" -> Js.l(mentions),
        "ownershipViolationsCaught" -> Js.l(ownership),
        "ownershipViolationLines" -> Js.l(ownershipLines),
        "reviewerFindingsBySeverity" -> mapObj(bySeverity),
        "reviewerFindingsBySeverityByPhase" -> Js.obj(
          severityByPhase.toVector.sortBy(_._1).map((p, r) => p -> mapObj(r))
        ),
        "reviewFilesWithoutParsedFindings" -> Js.arr(
          zeroFindingReviews.map(d => Js.s(d.file))
        ),
        "firstPassAcceptanceByAgent" -> Js.obj(
          byAgent.toVector.sortBy(_._1).map { (agent, t) =>
            agent -> Js.obj(
              Vector(
                "scopesDelivered" -> Js.l(t._1.toLong),
                "acceptedNoFinding" -> Js.l(t._2.toLong),
                "acceptedNoBlockerOrMajor" -> Js.l(t._3.toLong)
              )
            )
          }
        ),
        "humanCommandFiles" -> Js.l(humanDocs.size.toLong),
        "humanCommandBlocks" -> Js.l(fences),
        "humanRulings" -> Js.l(rulings),
        "hookRefusalMentionsProse" -> Js.l(hooks),
        "hookRefusalEvents" -> Js.l(hookEvents.size.toLong),
        "hookRefusalEventsByClass" -> mapObj(hookByClass),
        "hookRefusalStateChangingUpperBound" -> Js.l(
          hookByClass(StateChanging) + hookByClass(Unclassified)
        ),
        "firstPassPairs" -> Js.l(pairs.size.toLong),
        "firstPassPairsReviewed" -> Js.l(acceptance.size.toLong),
        "firstPassPairsWithoutReview" ->
          Js.l((pairs.size - acceptance.size).toLong),
        "humanRewrites" -> Js.l(rewrites.size.toLong),
        "unclassifiedFiles" -> Js.arr(unclassified.map(d => Js.s(d.file)))
      )
    )
    val summary = Vector(
      s"markdown files read: ${docs.size} (non-markdown skipped: $skipped)",
      s"BLOCKED-ON events (status lines in report-like files; approximate, deduplicated by key): $events from $eventLines lines; other mentions: $mentions",
      s"ownership violations caught (approximate, deduplicated by key; negations excluded): $ownership from $ownershipLines lines",
      s"reviewer findings parsed: ${allFindings.size} by severity ${bySeverity.toVector.sortBy(_._1)}",
      s"review files with no parsed finding (audit these): ${zeroFindingReviews.size}",
      s"repair files: ${repairFiles.size}; scopes with review rounds: ${rounds.size}",
      s"human command files: ${humanDocs.size}, blocks: $fences; human rulings: $rulings",
      s"hook or guard refusal prose: $hooks lines, ${hookEvents.size} events; harmless reads ${hookByClass(HarmlessRead)}, state-changing ${hookByClass(StateChanging)}, unclassified ${hookByClass(Unclassified)} (partial; keyword classes)",
      s"first-pass pairs (scope, agent): ${pairs.size}, reviewed ${acceptance.size}",
      s"human-annotated rewrites: ${rewrites.size}",
      s"unclassified files: ${unclassified.size}"
    ) ++ byAgent.toVector.sortBy(_._1).map { (a, t) =>
      s"first pass $a: ${t._2}/${t._1} with no finding, ${t._3}/${t._1} with no blocker or major"
    }
    FactoryReport(docs, ev.result(), counts, summary)

  def readRewrites(path: Path): Vector[(String, String, String)] =
    if !Files.isRegularFile(path) then Vector.empty
    else
      Files
        .readAllLines(path, UTF_8)
        .asScala
        .toVector
        .filter(l => l.trim.nonEmpty && !l.startsWith("#"))
        .map { l =>
          val c = l.split("\t", -1).toVector
          (
            c.headOption.getOrElse(""),
            c.lift(1).getOrElse(""),
            c.lift(2).getOrElse("")
          )
        }

  private def phaseNames(range: String): Either[String, Vector[String]] =
    val parts = range.split("-")
    (
      parts.headOption.flatMap(_.toIntOption),
      parts.lift(1).flatMap(_.toIntOption)
    ) match
      case (Some(a), Some(b)) if a <= b =>
        Right((a to b).toVector.map(n => f"phase-$n%02d"))
      case _ => Left(s"--phases must look like 1-15, not $range")

  val Usage: String =
    "Usage: FactoryCounts <agent-runs dir> <mapping tsv> <output base dir> " +
      "[--rewrites FILE] [--phases 1-15] [--run-id ID]"

  def main(args: Array[String]): Unit =
    System.exit(cli(args.toVector))

  def cli(args: Vector[String]): Int =
    def options(
        rest: Vector[String],
        acc: Map[String, String]
    ): Option[Map[String, String]] =
      rest match
        case Vector()                                       => Some(acc)
        case flag +: value +: tail if flag.startsWith("--") =>
          options(tail, acc.updated(flag, value))
        case _ => None
    args match
      case runs +: mapping +: out +: rest =>
        options(rest, Map.empty) match
          case None =>
            println(Usage)
            2
          case Some(opts) =>
            phaseNames(opts.getOrElse("--phases", "1-15")) match
              case Left(error) =>
                println(error)
                2
              case Right(phases) =>
                val m = Mapping.load(Paths.get(mapping))
                val rewrites = opts
                  .get("--rewrites")
                  .map(p => readRewrites(Paths.get(p)))
                  .getOrElse(Vector.empty)
                val report = compute(Paths.get(runs), phases, m, rewrites)
                val runId = opts.getOrElse(
                  "--run-id",
                  s"factorycounts-${RunOutput.stamp()}"
                )
                val dir = RunOutput.createRunDir(Paths.get(out), runId)
                RunOutput.write(
                  dir,
                  "factory-counts.json",
                  ujson.write(report.counts, indent = 2) + "\n"
                )
                RunOutput.write(
                  dir,
                  "factory-evidence.tsv",
                  ("category\tscope\tfile\tline\tdetail\ttext\n" +
                    report.evidence
                      .map(e =>
                        s"${e.category}\t${e.scope}\t${e.file}\t${e.line}\t${e.detail}\t${e.text}"
                      )
                      .mkString("\n") + "\n")
                )
                RunOutput.write(
                  dir,
                  "factory-summary.txt",
                  report.summary.mkString("", "\n", "\n")
                )
                RunOutput.write(
                  dir,
                  "run-info.json",
                  ujson.write(
                    Js.obj(
                      Vector(
                        "tool" -> Js.s(ToolVersion),
                        "runId" -> Js.s(runId),
                        "mappingSha256" -> Js.s(
                          Digests.sha256File(Paths.get(mapping))
                        ),
                        "startedAt" -> Js.s(java.time.Instant.now().toString)
                      )
                    ),
                    indent = 2
                  ) + "\n"
                )
                RunOutput.seal(dir)
                println(s"FACTORYCOUNTS RESULT: run directory $dir")
                report.summary.foreach(println)
                0
      case _ =>
        println(Usage)
        2
