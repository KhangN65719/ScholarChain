package scholarchain.er.eval.validation

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scholarchain.er.eval.GoldLabel
import scholarchain.er.eval.Verdict
import scholarchain.er.eval.labeling.LabelNames
import scholarchain.er.eval.labeling.SuggestionSource
import scholarchain.er.eval.labeling.Suggestions
import scholarchain.er.pairs.AtomicFile
import scholarchain.er.pairs.Cli
import scholarchain.er.pairs.Hashing
import scholarchain.er.pairs.Locations
import scholarchain.er.pairs.PairItem
import scholarchain.er.pairs.PairSide
import scholarchain.er.pairs.PairsFile

enum Status(val wire: String):
  case Pass extends Status("PASS")
  case Fail extends Status("FAIL")
  case Skip extends Status("SKIP")

final case class Check(name: String, status: Status, detail: String)

final case class Expectations(
    total: Int,
    same: Int,
    different: Int,
    unsure: Int,
    changed: Int,
    dblpSnapshot: String,
    reviewer: Option[String]
)

object Expectations:
  val phase5: Expectations =
    Expectations(230, 95, 131, 4, 64, "dblp:2026-11-05", None)

/** What a finished second-pass file is held to. The totals are optional: pass 2
  * makes new facts, so the first pass's 95, 131 and 4 do not apply.
  */
final case class V2Expectations(
    total: Int,
    same: Option[Int],
    different: Option[Int],
    unsure: Option[Int],
    dblpSnapshot: String,
    reviewer: Option[String]
)

/** The raw text of every input. Nothing here is parsed yet. */
final case class Sources(
    gold: Vector[String],
    pairs: Vector[String],
    suggestions: Option[(String, Array[Byte])],
    strata: Option[Vector[String]],
    manifest: Option[String],
    pass1: Option[(String, Array[Byte])] = None,
    subset: Option[Vector[String]] = None,
    pass2File: Option[(String, Array[Byte])] = None,
    pass2Subset: Option[Vector[String]] = None
)

/** `headline` is printed first: review time and pass counts, reported and never
  * judged. `compare` is the descriptive comparison of two passes. Neither
  * changes whether the report passed.
  */
final case class Report(
    checks: Vector[Check],
    info: Vector[String],
    headline: Vector[String] = Vector.empty,
    compare: Vector[String] = Vector.empty
):
  def failed: Vector[Check] = checks.filter(_.status == Status.Fail)

  def passed: Boolean = failed.isEmpty

  def render: String =
    val lines = checks.map(c => s"${c.status.wire} ${c.name}: ${c.detail}")
    val skipped = checks.count(_.status == Status.Skip)
    val word = if passed then "PASSED" else "FAILED"
    val tail = s"VALIDATION $word (${failed.size} failed, $skipped skipped)"
    (headline ++ lines ++ info.map("INFO " + _) ++
      compare.map("COMPARE " + _) :+ tail).mkString("\n")

  def toJson: String =
    val items = checks.map(Report.checkJson)
    ujson.write(
      ujson.Obj(
        "passed" -> passed,
        "headline" -> ujson.Arr.from(headline),
        "checks" -> ujson.Arr.from(items),
        "info" -> ujson.Arr.from(info),
        "compare" -> ujson.Arr.from(compare)
      ),
      indent = 2
    )

object Report:
  def checkJson(c: Check): ujson.Obj =
    ujson.Obj(
      "name" -> c.name,
      "status" -> c.status.wire,
      "detail" -> c.detail
    )

/** One non-blank line of the label file. `label` is the line loaded through
  * `GoldLabel`, or the reason it was refused.
  */
final case class Row(
    line: Int,
    json: ujson.Obj,
    label: Either[String, GoldLabel]
):
  def text(key: String): Option[String] =
    json.value.get(key).collect { case ujson.Str(s) => s }

  def pairId: Option[String] = text("pair_id")

  def verdict: Option[Verdict] = label.toOption.map(_.verdict)

  def suggested: Option[Verdict] =
    text("suggested_label").flatMap(LabelNames.parse)

  def storedChange: Option[Boolean] =
    json.value.get("changed_from_suggestion").map(_ == ujson.True)

  def changed: Option[Boolean] =
    suggested.zip(verdict).map((s, v) => s != v)

  def pass: Option[Int] =
    json.value.get("pass").collect {
      case ujson.Num(n) if n == 1.0 || n == 2.0 => n.toInt
    }

  /** The pass of a row of a third-pass file: 2 or 3, nothing else. */
  def passOfV3: Option[Int] =
    json.value.get("pass").collect {
      case ujson.Num(n) if n == 2.0 || n == 3.0 => n.toInt
    }

/** Checks a finished label file against the pairs file it was made from, the
  * suggestion file it was made with and the facts the reviewer reported. Each
  * check is independent of the others and a failure never stops the rest, so
  * one run lists every discrepancy.
  */
object GoldValidator:
  private type Pairs = Either[String, Vector[PairItem]]

  private val RequiredKeys =
    Vector("leftSourceRecordId", "rightSourceRecordId", "snapshotId")

  private val Known = Set(
    "--pairs",
    "--gold",
    "--suggestions",
    "--strata-key",
    "--manifest",
    "--out",
    "--expect-total",
    "--expect-same",
    "--expect-different",
    "--expect-unsure",
    "--expect-changed",
    "--dblp-snapshot",
    "--reviewer",
    "--mode",
    "--pass1",
    "--subset",
    "--pass2file",
    "--pass2-subset"
  )

  private def parseJson(text: String): ujson.Obj =
    try ujson.read(text).obj
    catch case _: Exception => ujson.Obj()

  private def loadLabel(text: String): Either[String, GoldLabel] =
    try Right(upickle.default.read[GoldLabel](text))
    catch case e: Exception => Left(String.valueOf(e.getMessage))

  private def readRow(entry: (String, Int)): Option[Row] =
    val (text, index) = entry
    if text.trim.isEmpty then None
    else Some(Row(index + 1, parseJson(text), loadLabel(text)))

  private def loadPairs(lines: Vector[String]): Pairs =
    try Right(PairsFile.parse(lines))
    catch case e: Exception => Left(String.valueOf(e.getMessage))

  private def instant(text: String): Option[Instant] =
    scala.util.Try(Instant.parse(text)).toOption

  private def some(items: Vector[String], limit: Int = 8): String =
    val more = items.size - limit
    val shown = items.take(limit).mkString("; ")
    if more > 0 then s"$shown; and $more more" else shown

  private def flag(name: String, ok: Boolean, detail: String): Check =
    Check(name, if ok then Status.Pass else Status.Fail, detail)

  private def violations(
      name: String,
      found: Vector[String],
      pass: String
  ): Check =
    if found.isEmpty then Check(name, Status.Pass, pass)
    else Check(name, Status.Fail, s"${found.size} problems: ${some(found)}")

  private def needPairs(name: String, why: String): Check =
    Check(name, Status.Fail, s"needs the pairs file: $why")

  private def tag(row: Row, problems: Vector[String]): Vector[String] =
    problems.map(p => s"line ${row.line}: $p")

  private def countCheck(gold: Vector[Row], want: Int): Check =
    val got = gold.size
    flag("entry-count", got == want, s"$got entries, expected $want")

  private def loadCheck(gold: Vector[Row]): Check =
    val bad = gold.collect { case Row(n, _, Left(why)) => s"line $n: $why" }
    violations("loads-as-GoldLabel", bad, "every row loads as a GoldLabel")

  private def itemDrift(row: Row): Boolean =
    row.label.toOption.exists(l => row.pairId.exists(_ != l.itemId))

  private def idCheck(gold: Vector[Row]): Check =
    val noId = gold.filter(_.pairId.isEmpty).map(r => s"line ${r.line} no id")
    val ids = gold.flatMap(_.pairId)
    val twice = ids.diff(ids.distinct).distinct.map(id => s"repeated $id")
    val drift = gold.filter(itemDrift).map(r => s"line ${r.line}: itemId")
    violations("gold-pair-ids", noId ++ twice ++ drift, s"${ids.size} unique")

  private def pairsCheck(pairs: Pairs, want: Int): Check =
    pairs match
      case Left(why)    => Check("pairs-file", Status.Fail, s"unreadable: $why")
      case Right(items) =>
        val got = items.size
        flag("pairs-file", got == want, s"$got unique pairs, expected $want")

  private def coverageCheck(gold: Vector[Row], pairs: Pairs): Check =
    pairs match
      case Left(why)    => needPairs("gold-pairs-coverage", why)
      case Right(items) =>
        val want = items.map(_.pairId).toSet
        val have = gold.flatMap(_.pairId).toSet
        val missing = (want -- have).toVector.sorted.map("no gold " + _)
        val extra = (have -- want).toVector.sorted.map("no pair " + _)
        violations(
          "gold-pairs-coverage",
          missing ++ extra,
          s"${want.size} pair_ids in both files"
        )

  private def fields(row: Row, p: PairItem, g: GoldLabel): Vector[String] =
    val l = p.left
    val r = p.right
    val joined =
      if l.snapshotId == r.snapshotId then l.snapshotId
      else s"${l.snapshotId}+${r.snapshotId}"
    val same = Vector(
      "kind" -> (g.kind == p.kind),
      "leftId" -> (g.leftId == l.recordId),
      "rightId" -> (g.rightId == r.recordId),
      "leftSource" -> (g.leftSourceRecordId == l.sourceRecordId),
      "rightSource" -> (g.rightSourceRecordId == r.sourceRecordId),
      "snapshotId" -> (g.snapshotId == joined),
      "leftSnapshot" -> row.text("left_snapshot_id").contains(l.snapshotId),
      "rightSnapshot" -> row.text("right_snapshot_id").contains(r.snapshotId)
    )
    same.collect { case (name, false) => s"${p.pairId}: $name differs" }

  private def disagreements(
      row: Row,
      byId: Map[String, PairItem]
  ): Vector[String] =
    val pair = row.pairId.flatMap(byId.get)
    val gold = row.label.toOption
    pair.zip(gold).toVector.flatMap((p, g) => fields(row, p, g))

  private def agreementCheck(gold: Vector[Row], pairs: Pairs): Check =
    pairs match
      case Left(why)    => needPairs("gold-agrees-with-pairs", why)
      case Right(items) =>
        val byId = items.map(p => p.pairId -> p).toMap
        val bad = gold.flatMap(r => disagreements(r, byId))
        violations("gold-agrees-with-pairs", bad, "all fields agree")

  private def humanProblems(row: Row, want: Option[String]): Vector[String] =
    val who = row.text("reviewer").map(_.trim).filter(_.nonEmpty)
    val stamp = row.text("reviewedAt").flatMap(instant)
    val blank = RequiredKeys.filter(k => row.text(k).forall(_.trim.isEmpty))
    val found = Vector(
      Option.when(!row.text("reviewMethod").contains("human"))("not human"),
      Option.when(who.isEmpty)("no reviewer"),
      Option.when(want.exists(w => !who.contains(w)))("other reviewer"),
      Option.when(stamp.isEmpty)("bad reviewedAt")
    ).flatten
    tag(row, found ++ blank.map(k => s"blank $k"))

  private def humanCheck(gold: Vector[Row], want: Option[String]): Check =
    val bad = gold.flatMap(r => humanProblems(r, want))
    violations("human-review-fields", bad, "method, reviewer, time, sources")

  private def badSnapshot(
      side: PairSide,
      saved: Option[String],
      want: String
  ): Boolean =
    side.source == "dblp" && (side.snapshotId != want || !saved.contains(want))

  private def snapshotProblems(
      row: Row,
      byId: Map[String, PairItem],
      want: String
  ): Vector[String] =
    val pair = row.pairId.flatMap(byId.get).toVector
    pair.flatMap { p =>
      val left = badSnapshot(p.left, row.text("left_snapshot_id"), want)
      val right = badSnapshot(p.right, row.text("right_snapshot_id"), want)
      Vector(
        Option.when(left)(s"${p.pairId}: left DBLP snapshot"),
        Option.when(right)(s"${p.pairId}: right DBLP snapshot")
      ).flatten
    }

  private def snapshotCheck(
      gold: Vector[Row],
      pairs: Pairs,
      want: String
  ): Check =
    pairs match
      case Left(why)    => needPairs("dblp-snapshot", why)
      case Right(items) =>
        val byId = items.map(p => p.pairId -> p).toMap
        val bad = gold.flatMap(r => snapshotProblems(r, byId, want))
        violations("dblp-snapshot", bad, s"every DBLP record is $want")

  private def totalsCheck(gold: Vector[Row], e: Expectations): Check =
    val kinds = Vector(Verdict.Match, Verdict.NonMatch, Verdict.Unsure)
    val got = kinds.map(v => gold.count(_.verdict.contains(v)))
    val want = Vector(e.same, e.different, e.unsure)
    val detail = s"same/different/unsure ${got.mkString("/")}"
    flag(
      "label-totals",
      got == want,
      s"$detail, expected ${want.mkString("/")}"
    )

  private def suggestionCheck(gold: Vector[Row]): Check =
    val bad = gold.filter(_.suggested.isEmpty)
    val shown = bad.map(r => s"line ${r.line}: no usable suggested_label")
    violations("suggestion-on-every-row", shown, s"${gold.size} rows have one")

  private def sourceCheck(gold: Vector[Row]): Check =
    val keys = Vector("suggestion_source_file", "suggestion_source_sha256")
    val seen = gold.map(r => keys.map(r.text)).distinct
    val hex = "[0-9a-f]{64}"
    val whole = seen.forall(_.forall(_.isDefined))
    val onlyOne = seen.size == 1
    val shaOk = seen.headOption.exists(_.last.exists(_.matches(hex)))
    val show = seen.map(_.map(_.getOrElse("none")).mkString(" ")).mkString("; ")
    flag("suggestion-source", whole && onlyOne && shaOk, show)

  private def csvDifferences(
      row: Row,
      csv: Map[String, scholarchain.er.eval.labeling.Suggestion],
      sha: String
  ): Vector[String] =
    val entry = row.pairId.flatMap(csv.get)
    val found = Vector(
      Option.when(!row.text("suggestion_source_sha256").contains(sha))("sha"),
      Option.when(entry.isEmpty)("pair not in csv"),
      Option.when(entry.map(_.label) != row.suggested)("label differs"),
      Option.when(entry.flatMap(_.reason) != row.text("suggestion_reason"))(
        "reason differs"
      )
    ).flatten
    tag(row, found)

  private def compareCsv(
      gold: Vector[Row],
      name: String,
      bytes: Array[Byte]
  ): Check =
    val sha = Hashing.sha256Bytes(bytes)
    val ids = gold.flatMap(_.pairId).toSet
    val source = SuggestionSource(name, sha)
    Suggestions.parse(new String(bytes, UTF_8), source, ids) match
      case Left(problems) =>
        Check("suggestions-csv", Status.Fail, some(problems))
      case Right(set) =>
        val bad = gold.flatMap(r => csvDifferences(r, set.byPair, sha))
        val extra = set.unknownPairIds.map("csv only: " + _)
        val named = gold.flatMap(_.text("suggestion_source_file")).distinct
        val rename = Vector(s"file name ${named.mkString(",")}")
        val names = if named == Vector(name) then Vector.empty else rename
        violations(
          "suggestions-csv",
          bad ++ extra ++ names,
          s"$name sha256 $sha matches every row"
        )

  private def csvCheck(
      gold: Vector[Row],
      csv: Option[(String, Array[Byte])]
  ): Check =
    csv match
      case None => Check("suggestions-csv", Status.Skip, "no --suggestions")
      case Some((name, bytes)) => compareCsv(gold, name, bytes)

  private def changedCheck(gold: Vector[Row], want: Int): Check =
    val count = gold.count(_.changed.contains(true))
    val lying =
      gold.filter(r => r.changed.exists(c => !r.storedChange.contains(c)))
    val first = s"recomputed $count changed (expected $want)"
    val second = s"${lying.size} stored flags disagree"
    val ok = count == want && lying.isEmpty
    flag("changed-from-suggestion", ok, s"$first; $second")

  private def stratumLine(line: String): (String, String) =
    val json = ujson.read(line)
    (json("pair_id").str, json("stratum").str)

  private def strataFrom(
      found: Vector[String],
      pairs: Pairs,
      manifest: Option[String]
  ): Check =
    val name = "strata-key"
    try
      val keyed = found.filter(_.trim.nonEmpty).map(stratumLine)
      val counts = keyed.groupBy(_._2).map((s, v) => s -> v.size)
      val ids = keyed.map(_._1).toSet
      val fromManifest = manifest.map(m => selected(m)).getOrElse(counts)
      val want = pairs.toOption.map(_.map(_.pairId).toSet)
      val ok = want.forall(_ == ids) && fromManifest == counts
      val shown = counts.toVector.sorted.map((s, n) => s"$s $n").mkString(", ")
      flag(name, ok, s"${ids.size} keys: $shown")
    catch case e: Exception => Check(name, Status.Fail, e.getMessage)

  private def selected(manifest: String): Map[String, Int] =
    val strata = ujson.read(manifest)("strata").arr
    strata.map(s => s("stratum").str -> s("selected").num.toInt).toMap

  private def strataCheck(
      lines: Option[Vector[String]],
      pairs: Pairs,
      manifest: Option[String]
  ): Check =
    lines match
      case None        => Check("strata-key", Status.Skip, "no --strata-key")
      case Some(found) => strataFrom(found, pairs, manifest)

  private def label(v: Option[Verdict]): String =
    v.fold("?")(LabelNames.name)

  private def kindLine(gold: Vector[Row]): String =
    val kinds = gold.flatMap(_.label.toOption).map(_.kind.wire)
    val counts = kinds.groupBy(identity).toVector.sortBy(_._1)
    "kinds: " + counts.map((k, v) => s"$k ${v.size}").mkString(", ")

  private def reviewerLine(gold: Vector[Row]): String =
    val who = gold.flatMap(_.text("reviewer")).distinct.sorted
    s"reviewers: ${who.mkString(", ")}"

  private def matrixLine(gold: Vector[Row]): String =
    val moves = gold.map(r => s"${label(r.suggested)}->${label(r.verdict)}")
    val counts = moves.groupBy(identity).toVector.sortBy(_._1)
    "suggestion->final: " + counts.map((m, v) => s"$m ${v.size}").mkString(", ")

  private def timingLine(gold: Vector[Row]): String =
    val labels = gold.flatMap(_.label.toOption)
    val times = labels.flatMap(l => instant(l.reviewedAt)).map(_.toEpochMilli)
    val ms = times.sorted
    if ms.size < 2 then "reviewedAt: fewer than two timestamps"
    else
      val gaps = ms.zip(ms.tail).map((a, b) => b - a).sorted
      val span = (ms.last - ms.head) / 60000.0
      val fast = gaps.count(_ < 2000)
      val first = s"reviewedAt ${Instant.ofEpochMilli(ms.head)}"
      val last = s"${Instant.ofEpochMilli(ms.last)}"
      val gap = f"median gap ${gaps(gaps.size / 2) / 1000.0}%.1f s"
      f"$first .. $last, span $span%.1f min, $gap, $fast gaps under 2 s"

  private def noteLine(gold: Vector[Row]): String =
    val noted = gold.count(_.text("notes").exists(_.trim.nonEmpty))
    s"rows with a note: $noted"

  private def sourceLines(pairs: Pairs): Vector[String] =
    pairs.toOption.toVector.flatMap { items =>
      val sides = items.flatMap(p => Vector(p.left, p.right))
      val by = sides.groupBy(_.source).toVector.sortBy(_._1)
      by.map((s, v) => s"snapshots of $s: ${snaps(v)}")
    }

  private def snaps(sides: Vector[PairSide]): String =
    sides.map(_.snapshotId).distinct.sorted.mkString(", ")

  def validate(sources: Sources, e: Expectations): Report =
    val gold = sources.gold.zipWithIndex.flatMap(readRow)
    val pairs = loadPairs(sources.pairs)
    val checks = Vector(
      countCheck(gold, e.total),
      loadCheck(gold),
      idCheck(gold),
      pairsCheck(pairs, e.total),
      coverageCheck(gold, pairs),
      agreementCheck(gold, pairs),
      humanCheck(gold, e.reviewer),
      snapshotCheck(gold, pairs, e.dblpSnapshot),
      totalsCheck(gold, e),
      suggestionCheck(gold),
      sourceCheck(gold),
      csvCheck(gold, sources.suggestions),
      changedCheck(gold, e.changed),
      strataCheck(sources.strata, pairs, sources.manifest)
    )
    val info = Vector(
      kindLine(gold),
      reviewerLine(gold),
      matrixLine(gold),
      timingLine(gold),
      noteLine(gold)
    ) ++ sourceLines(pairs)
    Report(checks, info)

  // ------------------------------------------------------------ second pass

  private final case class PassFile(
      name: String,
      sha: String,
      rows: Vector[Row]
  )

  private def passFile(
      source: Option[(String, Array[Byte])]
  ): Option[PassFile] =
    source.map { (name, bytes) =>
      val text = new String(bytes, UTF_8)
      PassFile(
        name,
        Hashing.sha256Bytes(bytes),
        text.linesIterator.toVector.zipWithIndex.flatMap(readRow)
      )
    }

  private val SuggestionKeys = Vector(
    "suggested_label",
    "suggestion_reason",
    "suggestion_source_file",
    "suggestion_source_sha256"
  )

  private def needPass1(name: String): Check =
    Check(name, Status.Fail, "needs the first-pass file (--pass1)")

  private def passFieldCheck(gold: Vector[Row]): Check =
    val bad = gold
      .filter(_.pass.isEmpty)
      .map(r => s"line ${r.line}: pass is missing or not 1 or 2")
    violations("pass-field", bad, s"${gold.size} rows are marked pass 1 or 2")

  private def pass1FileCheck(first: Option[PassFile], pairs: Pairs): Check =
    first match
      case None    => needPass1("pass1-file")
      case Some(f) =>
        val bad = f.rows.collect { case Row(n, _, Left(why)) =>
          s"line $n: $why"
        }
        val ids = f.rows.flatMap(_.pairId)
        val twice = ids.diff(ids.distinct).distinct.map(id => s"repeated $id")
        val missing = pairs.toOption.toVector.flatMap { items =>
          (items.map(_.pairId).toSet -- ids).toVector.sorted
            .map("no label " + _)
        }
        violations(
          "pass1-file",
          bad ++ twice ++ missing,
          s"${ids.size} labels in ${f.name}"
        )

  private def subsetCheck(
      gold: Vector[Row],
      subset: Option[Vector[String]]
  ): Check =
    subset match
      case None       => Check("pass2-is-subset", Status.Fail, "needs --subset")
      case Some(file) =>
        val want = file.map(_.trim).filter(_.nonEmpty)
        val twice = want.diff(want.distinct).distinct.map("subset repeats " + _)
        val got = gold.filter(_.pass.contains(2)).flatMap(_.pairId).toSet
        val missing =
          (want.toSet -- got).toVector.sorted.map("not relabeled " + _)
        val extra =
          (got -- want).toVector.sorted.map("relabeled outside the subset " + _)
        violations(
          "pass2-is-subset",
          twice ++ missing ++ extra,
          s"${want.size} pass-2 rows are exactly the subset"
        )

  private def leaks(row: Row): Vector[String] =
    val present =
      SuggestionKeys.filter(k => row.json.value.get(k).exists(_ != ujson.Null))
    val changed = row.json.value
      .get("changed_from_suggestion")
      .filter(v => v != ujson.Null && v != ujson.False)
    present.map(k => s"$k is set") ++
      changed.map(_ => "changed_from_suggestion is set").toVector

  private def blindProblems(
      row: Row,
      first: Map[String, Row]
  ): Vector[String] =
    val before = row.text("pass1_label").flatMap(LabelNames.parse)
    val expected = row.pairId.flatMap(first.get).flatMap(_.verdict)
    val stored = Option.when(before.isEmpty || before != expected)(
      "pass1_label is missing or differs from the first-pass file"
    )
    tag(row, leaks(row) ++ stored.toVector)

  private def blindCheck(gold: Vector[Row], first: Option[PassFile]): Check =
    first match
      case None    => needPass1("pass2-blind")
      case Some(f) =>
        val byId = f.rows.flatMap(r => r.pairId.map(_ -> r)).toMap
        val rows = gold.filter(_.pass.contains(2))
        violations(
          "pass2-blind",
          rows.flatMap(blindProblems(_, byId)),
          s"${rows.size} pass-2 rows carry no suggestion"
        )

  private def carriedProblems(
      row: Row,
      original: Map[String, ujson.Obj],
      file: PassFile
  ): Vector[String] =
    row.pairId.flatMap(original.get) match
      case None        => tag(row, Vector("pair is not in the first-pass file"))
      case Some(first) =>
        val differs = first.value.collect {
          case (k, v) if !row.json.value.get(k).contains(v) =>
            s"$k differs from ${file.name}"
        }.toVector
        val marks = Vector(
          Option.when(!row.text("carried_from").contains(file.name))(
            "carried_from is not " + file.name
          ),
          Option.when(!row.text("carried_from_sha256").contains(file.sha))(
            "carried_from_sha256 is not the digest of " + file.name
          )
        ).flatten
        tag(row, differs ++ marks)

  private def carriedCheck(gold: Vector[Row], first: Option[PassFile]): Check =
    first match
      case None    => needPass1("pass1-carried")
      case Some(f) =>
        val original = f.rows.flatMap(r => r.pairId.map(_ -> r.json)).toMap
        val rows = gold.filter(_.pass.contains(1))
        violations(
          "pass1-carried",
          rows.flatMap(carriedProblems(_, original, f)),
          s"${rows.size} pass-1 rows equal their lines in ${f.name}"
        )

  private def recomputedCheck(sources: Sources, pairs: Pairs): Check =
    val name = "subset-recomputed"
    (sources.strata, sources.suggestions, sources.subset, pairs) match
      case (Some(key), Some((file, bytes)), Some(subset), Right(items)) =>
        val ids = items.map(_.pairId)
        val source = SuggestionSource(file, Hashing.sha256Bytes(bytes))
        try
          Suggestions.parse(new String(bytes, UTF_8), source, ids.toSet) match
            case Left(problems) => Check(name, Status.Fail, some(problems))
            case Right(set)     =>
              SubsetSelection.select(
                ids,
                SubsetSelection.strataOf(key),
                set.byPair.view.mapValues(_.label).toMap,
                SubsetSelection.HardStratum,
                SubsetSelection.Salt
              ) match
                case Left(why)  => Check(name, Status.Fail, why)
                case Right(sel) =>
                  val listed = subset.map(_.trim).filter(_.nonEmpty).toSet
                  flag(
                    name,
                    sel.union == listed,
                    s"recomputed ${sel.union.size} pairs, subset file " +
                      s"${listed.size}"
                  )
        catch case e: Exception => Check(name, Status.Fail, e.getMessage)
      case _ =>
        Check(
          name,
          Status.Skip,
          "needs --strata-key, --suggestions and --subset"
        )

  private def v2TotalsCheck(gold: Vector[Row], e: V2Expectations): Check =
    val kinds = Vector(Verdict.Match, Verdict.NonMatch, Verdict.Unsure)
    val got = kinds.map(v => gold.count(_.verdict.contains(v)))
    val want = Vector(e.same, e.different, e.unsure)
    val seen = s"same/different/unsure ${got.mkString("/")}"
    if want.forall(_.isEmpty) then
      Check(
        "label-totals",
        Status.Skip,
        s"$seen; no expected totals given, shown for the record"
      )
    else
      val wrong = got.zip(want).collect { case (g, Some(w)) if g != w => w }
      flag(
        "label-totals",
        wrong.isEmpty,
        s"$seen, expected ${want.map(_.fold("any")(_.toString)).mkString("/")}"
      )

  private def counts(rows: Vector[Row]): String =
    def n(v: Verdict) = rows.count(_.verdict.contains(v))
    s"same ${n(Verdict.Match)}, different ${n(Verdict.NonMatch)}, " +
      s"unsure ${n(Verdict.Unsure)}"

  private def v2Headline(
      first: Option[PassFile],
      one: Vector[Row],
      two: Vector[Row]
  ): Vector[String] =
    val firstTime = first.fold("no first-pass file")(f =>
      s"all ${f.rows.size} rows of ${f.name}, labeled with suggestions: " +
        timingLine(f.rows)
    )
    Vector(
      "REVIEW TIME AND PASSES (reported, not a pass or fail criterion)",
      s"REVIEW TIME pass 1, $firstTime",
      s"REVIEW TIME pass 2, ${two.size} blind relabels: ${timingLine(two)}",
      s"LABELS by pass: pass 1 ${one.size} (${counts(one)}); " +
        s"pass 2 ${two.size} (${counts(two)})",
      s"NOT RE-REVIEWED: ${one.size} pairs carry first-pass labels made with " +
        s"suggestions; only ${two.size} pairs were reviewed blind"
    )

  private def comparePairs(
      two: Vector[Row],
      first: Option[PassFile]
  ): Vector[PassComparison.Pair] =
    val before = first.toVector
      .flatMap(_.rows)
      .flatMap(r => r.pairId.zip(r.verdict))
      .toMap
    two.flatMap(r =>
      for
        id <- r.pairId
        second <- r.verdict
        one <- before.get(id)
      yield PassComparison.Pair(id, one, second)
    )

  private def strataMap(sources: Sources): Option[Map[String, String]] =
    sources.strata.flatMap(lines =>
      scala.util.Try(SubsetSelection.strataOf(lines)).toOption
    )

  /** The check for a finished second-pass file: pairs relabeled blind and the
    * rest carried from the first pass. It requires no totals and no suggestion
    * on a pass 2 row, and it requires every pass 1 row to equal the first-pass
    * file.
    */
  def validateV2(sources: Sources, e: V2Expectations): Report =
    val gold = sources.gold.zipWithIndex.flatMap(readRow)
    val pairs = loadPairs(sources.pairs)
    val first = passFile(sources.pass1)
    val one = gold.filter(_.pass.contains(1))
    val two = gold.filter(_.pass.contains(2))
    val checks = Vector(
      countCheck(gold, e.total),
      loadCheck(gold),
      idCheck(gold),
      pairsCheck(pairs, e.total),
      coverageCheck(gold, pairs),
      agreementCheck(gold, pairs),
      humanCheck(gold, e.reviewer),
      snapshotCheck(gold, pairs, e.dblpSnapshot),
      passFieldCheck(gold),
      pass1FileCheck(first, pairs),
      subsetCheck(gold, sources.subset),
      recomputedCheck(sources, pairs),
      blindCheck(gold, first),
      carriedCheck(gold, first),
      v2TotalsCheck(gold, e),
      strataCheck(sources.strata, pairs, sources.manifest)
    )
    val info = Vector(
      kindLine(gold),
      reviewerLine(gold),
      noteLine(gold)
    ) ++ sourceLines(pairs)
    val compare = first.fold(Vector.empty[String])(_ =>
      PassComparison.lines(
        comparePairs(two, first),
        strataMap(sources),
        one.size
      )
    )
    Report(checks, info, v2Headline(first, one, two), compare)

  // ------------------------------------------------------------- third pass

  private def v3PassFieldCheck(gold: Vector[Row]): Check =
    val bad = gold
      .filter(_.passOfV3.isEmpty)
      .map(r => s"line ${r.line}: pass is missing or not 2 or 3")
    violations("pass-field", bad, s"${gold.size} rows are marked pass 2 or 3")

  private def pass2FileCheck(second: Option[PassFile], pairs: Pairs): Check =
    second match
      case None    => Check("pass2-file", Status.Fail, "needs --pass2file")
      case Some(f) =>
        val bad = f.rows.collect { case Row(n, _, Left(why)) =>
          s"line $n: $why"
        }
        val ids = f.rows.flatMap(_.pairId)
        val twice = ids.diff(ids.distinct).distinct.map(id => s"repeated $id")
        val marks = f.rows
          .filter(_.pass.isEmpty)
          .map(r => s"line ${r.line}: pass is missing or not 1 or 2")
        val missing = pairs.toOption.toVector.flatMap { items =>
          (items.map(_.pairId).toSet -- ids).toVector.sorted
            .map("no label " + _)
        }
        violations(
          "pass2-file",
          bad ++ twice ++ marks ++ missing,
          s"${ids.size} labels in ${f.name}, " +
            s"${f.rows.count(_.pass.contains(2))} of them pass 2"
        )

  private def pass3SubsetCheck(
      gold: Vector[Row],
      subset: Option[Vector[String]]
  ): Check =
    subset match
      case None       => Check("pass3-is-subset", Status.Fail, "needs --subset")
      case Some(file) =>
        val want = file.map(_.trim).filter(_.nonEmpty)
        val twice = want.diff(want.distinct).distinct.map("subset repeats " + _)
        val got = gold.filter(_.passOfV3.contains(3)).flatMap(_.pairId).toSet
        val missing =
          (want.toSet -- got).toVector.sorted.map("not relabeled " + _)
        val extra =
          (got -- want).toVector.sorted.map("relabeled outside the subset " + _)
        violations(
          "pass3-is-subset",
          twice ++ missing ++ extra,
          s"${want.size} pass-3 rows are exactly the subset"
        )

  private def partitionCheck(
      sources: Sources,
      second: Option[PassFile],
      pairs: Pairs
  ): Check =
    val name = "subsets-partition"
    (second, sources.subset, pairs) match
      case (Some(f), Some(file), Right(items)) =>
        val three = file.map(_.trim).filter(_.nonEmpty).toSet
        val two = f.rows.filter(_.pass.contains(2)).flatMap(_.pairId).toSet
        val all = items.map(_.pairId).toSet
        val listed = sources.pass2Subset.map(_.map(_.trim).filter(_.nonEmpty))
        val problems = Vector(
          listed
            .filter(l => l.toSet != two || l.size != l.distinct.size)
            .map(_ =>
              s"the pass-2 subset file differs from the pass-2 rows of ${f.name}"
            ),
          Option.when(two.intersect(three).nonEmpty)(
            s"${two.intersect(three).size} pairs are in both subsets"
          ),
          Option.when((all -- two -- three).nonEmpty)(
            s"${(all -- two -- three).size} pairs are in neither subset"
          ),
          Option.when(((two ++ three) -- all).nonEmpty)(
            s"${((two ++ three) -- all).size} subset pairs are not in the pairs file"
          )
        ).flatten
        violations(
          name,
          problems,
          s"pass-2 ${two.size} + pass-3 ${three.size} = ${two.size + three.size} " +
            s"of ${all.size} pairs, disjoint and complete"
        )
      case _ =>
        Check(
          name,
          Status.Fail,
          "needs --pass2file, --subset and the pairs file"
        )

  private def pass3BlindCheck(
      gold: Vector[Row],
      first: Option[PassFile]
  ): Check =
    first match
      case None    => needPass1("pass3-blind")
      case Some(f) =>
        val byId = f.rows.flatMap(r => r.pairId.map(_ -> r)).toMap
        val rows = gold.filter(_.passOfV3.contains(3))
        violations(
          "pass3-blind",
          rows.flatMap(blindProblems(_, byId)),
          s"${rows.size} pass-3 rows carry no suggestion and the pass-1 label of ${f.name}"
        )

  private def pass2CarriedCheck(
      gold: Vector[Row],
      second: Option[PassFile]
  ): Check =
    second match
      case None    => Check("pass2-carried", Status.Fail, "needs --pass2file")
      case Some(f) =>
        val original = f.rows
          .filter(_.pass.contains(2))
          .flatMap(r => r.pairId.map(_ -> r.json))
          .toMap
        val rows = gold.filter(_.passOfV3.contains(2))
        val absent =
          (original.keySet -- rows.flatMap(_.pairId).toSet).toVector.sorted
            .map("not carried " + _)
        violations(
          "pass2-carried",
          rows.flatMap(carriedProblems(_, original, f)) ++ absent,
          s"${rows.size} pass-2 rows equal their lines in ${f.name}"
        )

  private def suggestionFree(gold: Vector[Row]): Vector[String] =
    gold.flatMap(r => tag(r, leaks(r)))

  private def noSuggestionCheck(gold: Vector[Row]): Check =
    violations(
      "no-suggestion-labels",
      suggestionFree(gold),
      s"${gold.size} rows carry no suggestion field"
    )

  private def v3Headline(
      first: Option[PassFile],
      second: Option[PassFile],
      two: Vector[Row],
      three: Vector[Row],
      free: Boolean,
      leaking: Int
  ): Vector[String] =
    val firstTime = first.fold("no first-pass file")(f =>
      s"all ${f.rows.size} rows of ${f.name}, labeled with suggestions: " +
        timingLine(f.rows)
    )
    val twoRows = second.fold(two)(_.rows.filter(_.pass.contains(2)))
    val twoSource = second.fold("rows of this file")(f => s"rows of ${f.name}")
    val claim =
      if free then "no label in v3 is a with-suggestion label"
      else s"WARNING: $leaking rows carry a suggestion field"
    Vector(
      "REVIEW TIME AND PASSES (reported, not a pass or fail criterion)",
      s"REVIEW TIME pass 1, $firstTime",
      s"REVIEW TIME pass 2, ${twoRows.size} blind relabels ($twoSource): " +
        timingLine(twoRows),
      s"REVIEW TIME pass 3, ${three.size} blind relabels of the pass-1 " +
        s"pairs: ${timingLine(three)}",
      s"LABELS by pass: pass 2 ${two.size} (${counts(two)}); " +
        s"pass 3 ${three.size} (${counts(three)})",
      s"BLIND REVIEW: pass 2: ${two.size} labels, pass 3: ${three.size} " +
        s"labels were reviewed blind; $claim"
    )

  /** The check for a finished third-pass file: the pairs of the first pass
    * relabeled blind as pass 3, and the pass 2 rows carried unchanged from the
    * second-pass file. It reads no matcher output and no strata beyond the
    * optional key, which is used only to group the agreement lines.
    */
  def validateV3(sources: Sources, e: V2Expectations): Report =
    val gold = sources.gold.zipWithIndex.flatMap(readRow)
    val pairs = loadPairs(sources.pairs)
    val first = passFile(sources.pass1)
    val second = passFile(sources.pass2File)
    val two = gold.filter(_.passOfV3.contains(2))
    val three = gold.filter(_.passOfV3.contains(3))
    val leaking = suggestionFree(gold).size
    val checks = Vector(
      countCheck(gold, e.total),
      loadCheck(gold),
      idCheck(gold),
      pairsCheck(pairs, e.total),
      coverageCheck(gold, pairs),
      agreementCheck(gold, pairs),
      humanCheck(gold, e.reviewer),
      snapshotCheck(gold, pairs, e.dblpSnapshot),
      v3PassFieldCheck(gold),
      pass1FileCheck(first, pairs),
      pass2FileCheck(second, pairs),
      pass3SubsetCheck(gold, sources.subset),
      partitionCheck(sources, second, pairs),
      pass3BlindCheck(gold, first),
      pass2CarriedCheck(gold, second),
      noSuggestionCheck(gold),
      v2TotalsCheck(gold, e),
      strataCheck(sources.strata, pairs, sources.manifest)
    )
    val info = Vector(
      kindLine(gold),
      reviewerLine(gold),
      noteLine(gold)
    ) ++ sourceLines(pairs)
    val before = first.toVector
      .flatMap(_.rows)
      .flatMap(r => r.pairId.zip(r.verdict))
      .toMap
    val compared = three.flatMap(r =>
      for
        id <- r.pairId
        later <- r.verdict
        earlier <- before.get(id)
      yield PassComparison.Pair(id, earlier, later)
    )
    val compare = first.fold(Vector.empty[String])(_ =>
      PassComparison.linesPass3(compared, strataMap(sources), two.size)
    )
    Report(
      checks,
      info,
      v3Headline(first, second, two, three, leaking == 0, leaking),
      compare
    )

  final case class V2Setup(expect: V2Expectations, pass1: Path, subset: Path)

  final case class V3Setup(
      expect: V2Expectations,
      pass1: Path,
      pass2File: Path,
      subset: Path,
      pass2Subset: Option[Path]
  )

  final case class Args(
      pairs: Path,
      gold: Path,
      suggestions: Option[Path],
      strata: Option[Path],
      manifest: Option[Path],
      out: Option[Path],
      expect: Expectations,
      v2: Option[V2Setup] = None,
      v3: Option[V3Setup] = None
  )

  private val usage =
    "Usage: GoldValidator --pairs <file> --gold <file> [--suggestions <csv>] " +
      "[--strata-key <file>] [--manifest <file>] [--out <dir>] " +
      "[--expect-total n] [--expect-same n] [--expect-different n] " +
      "[--expect-unsure n] [--expect-changed n] [--dblp-snapshot id] " +
      "[--reviewer name]\n" +
      "Second pass: add --mode v2 --pass1 <first-pass gold.jsonl> " +
      "--subset <subset.txt>. Expected totals are then optional, " +
      "--expect-changed is refused, and --suggestions with --strata-key " +
      "re-derives the subset.\n" +
      "Third pass: add --mode v3 --pass1 <first-pass gold.jsonl> " +
      "--pass2file <second-pass gold-v2.jsonl> --subset <pass3-subset.txt> " +
      "[--pass2-subset <pass2-subset.txt>] [--strata-key <file>]. " +
      "--suggestions and --expect-changed are refused."

  private def number(
      o: Map[String, String],
      key: String,
      default: Int
  ): Either[String, Int] =
    o.get(key)
      .map(v => v.toIntOption.toRight(s"$key must be an integer"))
      .getOrElse(Right(default))

  private def expectations(
      o: Map[String, String]
  ): Either[String, Expectations] =
    val d = Expectations.phase5
    for
      total <- number(o, "--expect-total", d.total)
      same <- number(o, "--expect-same", d.same)
      different <- number(o, "--expect-different", d.different)
      unsure <- number(o, "--expect-unsure", d.unsure)
      changed <- number(o, "--expect-changed", d.changed)
    yield Expectations(
      total,
      same,
      different,
      unsure,
      changed,
      o.getOrElse("--dblp-snapshot", d.dblpSnapshot),
      o.get("--reviewer")
    )

  private def optionalNumber(
      o: Map[String, String],
      key: String
  ): Either[String, Option[Int]] =
    o.get(key) match
      case None    => Right(None)
      case Some(v) =>
        v.toIntOption.map(Some(_)).toRight(s"$key must be an integer")

  private def v2Setup(
      o: Map[String, String],
      gold: String
  ): Either[String, Option[V2Setup]] =
    o.get("--mode") match
      case None =>
        Either.cond(
          !o.contains("--pass1") && !o.contains("--subset") &&
            !o.contains("--pass2file") && !o.contains("--pass2-subset"),
          None,
          "--pass1, --subset, --pass2file and --pass2-subset belong to " +
            "--mode v2 or --mode v3"
        )
      case Some("v3") => Right(None)
      case Some("v2") =>
        for
          _ <- Either.cond(
            !o.contains("--pass2file") && !o.contains("--pass2-subset"),
            (),
            "--pass2file and --pass2-subset belong to --mode v3"
          )
          pass1 <- o.get("--pass1").toRight("--mode v2 needs --pass1")
          subset <- o.get("--subset").toRight("--mode v2 needs --subset")
          _ <- Either.cond(
            !o.contains("--expect-changed"),
            (),
            "--expect-changed has no meaning in --mode v2"
          )
          _ <- Either.cond(
            Paths.get(pass1).toAbsolutePath.normalize !=
              Paths.get(gold).toAbsolutePath.normalize,
            (),
            "--pass1 must be the first-pass file, not the file being checked"
          )
          total <- number(o, "--expect-total", Expectations.phase5.total)
          same <- optionalNumber(o, "--expect-same")
          different <- optionalNumber(o, "--expect-different")
          unsure <- optionalNumber(o, "--expect-unsure")
        yield Some(
          V2Setup(
            V2Expectations(
              total,
              same,
              different,
              unsure,
              o.getOrElse("--dblp-snapshot", Expectations.phase5.dblpSnapshot),
              o.get("--reviewer")
            ),
            Paths.get(pass1),
            Paths.get(subset)
          )
        )
      case Some(other) => Left(s"--mode must be v2 or v3, not $other")

  private def sameFile(a: String, b: String): Boolean =
    Paths.get(a).toAbsolutePath.normalize == Paths
      .get(b)
      .toAbsolutePath
      .normalize

  private def v3Setup(
      o: Map[String, String],
      gold: String
  ): Either[String, Option[V3Setup]] =
    if !o.get("--mode").contains("v3") then Right(None)
    else
      for
        pass1 <- o
          .get("--pass1")
          .toRight("--mode v3 needs --pass1 (the first-pass gold.jsonl)")
        second <- o
          .get("--pass2file")
          .toRight(
            "--mode v3 needs --pass2file (the second-pass gold-v2.jsonl)"
          )
        subset <- o
          .get("--subset")
          .toRight("--mode v3 needs --subset (the pass-3 subset file)")
        _ <- Either.cond(
          !o.contains("--expect-changed"),
          (),
          "--expect-changed has no meaning in --mode v3"
        )
        _ <- Either.cond(
          !o.contains("--suggestions"),
          (),
          "--suggestions has no meaning in --mode v3: no label is made with one"
        )
        _ <- Either.cond(
          !sameFile(pass1, gold) && !sameFile(second, gold) &&
            !sameFile(pass1, second),
          (),
          "--pass1, --pass2file and the checked --gold must be three files"
        )
        total <- number(o, "--expect-total", Expectations.phase5.total)
        same <- optionalNumber(o, "--expect-same")
        different <- optionalNumber(o, "--expect-different")
        unsure <- optionalNumber(o, "--expect-unsure")
      yield Some(
        V3Setup(
          V2Expectations(
            total,
            same,
            different,
            unsure,
            o.getOrElse("--dblp-snapshot", Expectations.phase5.dblpSnapshot),
            o.get("--reviewer")
          ),
          Paths.get(pass1),
          Paths.get(second),
          Paths.get(subset),
          o.get("--pass2-subset").map(s => Paths.get(s))
        )
      )

  def parseArgs(args: Seq[String]): Either[String, Args] =
    for
      o <- Cli.parse(args)
      _ <- Cli
        .unknown(o, Known)
        .toLeft(())
        .left
        .map(name => s"Unknown option $name")
      pairs <- o.get("--pairs").toRight("--pairs missing")
      gold <- o.get("--gold").toRight("--gold missing")
      expect <- expectations(o)
      v2 <- v2Setup(o, gold)
      v3 <- v3Setup(o, gold)
    yield Args(
      Paths.get(pairs),
      Paths.get(gold),
      o.get("--suggestions").map(s => Paths.get(s)),
      o.get("--strata-key").map(s => Paths.get(s)),
      o.get("--manifest").map(s => Paths.get(s)),
      o.get("--out").map(s => Paths.get(s)),
      expect,
      v2,
      v3
    )

  private def linesOf(path: Path): Vector[String] =
    Files.readAllLines(path, UTF_8).asScala.toVector

  private def csvOf(path: Path): (String, Array[Byte]) =
    (path.getFileName.toString, Files.readAllBytes(path))

  def readSources(a: Args): Sources =
    Sources(
      linesOf(a.gold),
      linesOf(a.pairs),
      a.suggestions.map(csvOf),
      a.strata.map(linesOf),
      a.manifest.map(p => Files.readString(p, UTF_8)),
      a.v2.map(v => csvOf(v.pass1)).orElse(a.v3.map(v => csvOf(v.pass1))),
      a.v2.map(v => linesOf(v.subset)).orElse(a.v3.map(v => linesOf(v.subset))),
      a.v3.map(v => csvOf(v.pass2File)),
      a.v3.flatMap(_.pass2Subset).map(linesOf)
    )

  def digests(a: Args): Vector[String] =
    val files = Vector(Some(a.pairs), Some(a.gold), a.suggestions)
    val second = Vector(
      a.v2.map(_.pass1),
      a.v2.map(_.subset),
      a.v3.map(_.pass1),
      a.v3.map(_.pass2File),
      a.v3.map(_.subset),
      a.v3.flatMap(_.pass2Subset)
    )
    val all = files ++ Vector(a.strata, a.manifest) ++ second
    all.flatten.map(p => s"sha256 ${Hashing.sha256File(p)}  $p")

  def main(args: Array[String]): Unit =
    parseArgs(args.toSeq) match
      case Left(message) =>
        Console.err.println(s"$message\n$usage")
        sys.exit(2)
      case Right(a) =>
        a.out.foreach(dir => refuse(dir))
        val sources =
          try readSources(a)
          catch
            case e: java.io.IOException =>
              Console.err.println(s"Cannot read an input: $e")
              sys.exit(2)
        val report = a.v3 match
          case Some(v) => validateV3(sources, v.expect)
          case None    =>
            a.v2.fold(validate(sources, a.expect))(v =>
              validateV2(sources, v.expect)
            )
        digests(a).foreach(println)
        println(report.render)
        a.out.foreach(dir => save(dir, report))
        if !report.passed then sys.exit(1)

  private def refuse(dir: Path): Unit =
    Locations.check(dir) match
      case Left(message) =>
        Console.err.println(s"Refusing to write there: $message")
        sys.exit(2)
      case Right(_) => ()

  private def save(dir: Path, report: Report): Unit =
    val text = dir.resolve("gold-validation.txt")
    val json = dir.resolve("gold-validation.json")
    AtomicFile.writeLines(text, Vector(report.render))
    AtomicFile.writeLines(json, Vector(report.toJson))
    println(s"Wrote $text and $json")
