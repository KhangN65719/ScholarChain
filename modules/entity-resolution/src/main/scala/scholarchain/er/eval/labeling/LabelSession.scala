package scholarchain.er.eval.labeling

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scholarchain.core.ContractSchema
import scholarchain.er.eval.{GoldLabel, Verdict}
import scholarchain.er.pairs.{AtomicFile, PairItem, PairsFile}

/** One gold line: the `GoldLabel` the evaluator reads, plus the audit trail of
  * any suggestion the reviewer saw.
  */
final case class GoldEntry(
    label: GoldLabel,
    suggestion: Option[Suggestion],
    source: Option[SuggestionSource],
    changedFromSuggestion: Boolean
):
  def pairId: String = label.itemId

/** The gold file is JSON Lines. Each line is a complete `GoldLabel` object, so
  * `eval.GoldFile.read` accepts the file as is, with these extra keys that
  * `GoldLabel` ignores: `pair_id`, `left_snapshot_id`, `right_snapshot_id`,
  * `suggested_label`, `suggestion_reason`, `suggestion_source_file`,
  * `suggestion_source_sha256` and `changed_from_suggestion`.
  *
  * A second-pass file adds `pass` (1 or 2) to every line. A pass 2 line also
  * has `pass1_label`, the label the same pair had in the first pass. A pass 1
  * line is the first-pass line unchanged plus `pass`, `carried_from` and
  * `carried_from_sha256`.
  */
object GoldLines:
  private def orNull(value: Option[String]): ujson.Value =
    value.map(v => ujson.Str(v): ujson.Value).getOrElse(ujson.Null)

  def render(entry: GoldEntry, pair: PairItem): String =
    val json = ujson.read(upickle.default.write(entry.label)).obj
    json("pair_id") = ujson.Str(entry.pairId)
    json("left_snapshot_id") = ujson.Str(pair.left.snapshotId)
    json("right_snapshot_id") = ujson.Str(pair.right.snapshotId)
    json("suggested_label") = orNull(
      entry.suggestion.map(s => LabelNames.name(s.label))
    )
    json("suggestion_reason") = orNull(entry.suggestion.flatMap(_.reason))
    json("suggestion_source_file") = orNull(entry.source.map(_.file))
    json("suggestion_source_sha256") = orNull(entry.source.map(_.sha256))
    json("changed_from_suggestion") = ujson.Bool(entry.changedFromSuggestion)
    ujson.write(ujson.Obj.from(json))

  /** A blind second-pass line. It records the first-pass label for later
    * agreement reporting; the labeling page never receives it.
    */
  def renderPass2(entry: GoldEntry, pair: PairItem, pass1: Verdict): String =
    renderBlind(entry, pair, pass1, 2)

  /** A blind relabel line of pass `pass` (2 or 3). `before` is the label the
    * same pair had in the first pass; it is stored and never shown.
    */
  def renderBlind(
      entry: GoldEntry,
      pair: PairItem,
      before: Verdict,
      pass: Int
  ): String =
    val json = ujson.read(render(entry, pair)).obj
    json("pass") = ujson.Num(pass.toDouble)
    json("pass1_label") = ujson.Str(LabelNames.name(before))
    ujson.write(ujson.Obj.from(json))

  def passOf(line: String): Option[Int] =
    ujson.read(line).obj.value.get("pass").collect {
      case ujson.Num(n) if n == 1.0 || n == 2.0 => n.toInt
    }

  /** The pass of a line when it is 1, 2 or 3. */
  def passOfAny(line: String): Option[Int] =
    passOfJson(ujson.read(line).obj)

  def passOfJson(json: ujson.Obj): Option[Int] =
    json.value.get("pass").collect {
      case ujson.Num(n) if n == 1.0 || n == 2.0 || n == 3.0 => n.toInt
    }

  def parse(line: String): GoldEntry =
    val label = upickle.default.read[GoldLabel](line)
    val json = ujson.read(line).obj
    def text(key: String): Option[String] =
      json.get(key).collect { case ujson.Str(value) => value }
    val suggestion =
      text("suggested_label").flatMap(LabelNames.parse).map { v =>
        Suggestion(label.itemId, v, text("suggestion_reason"))
      }
    val source = for
      file <- text("suggestion_source_file")
      sha <- text("suggestion_source_sha256")
    yield SuggestionSource(file, sha)
    val changed = json.get("changed_from_suggestion").exists {
      case ujson.True => true
      case _          => false
    }
    GoldEntry(label, suggestion, source, changed)

final case class SessionStats(
    total: Int,
    labeled: Int,
    same: Int,
    different: Int,
    unsure: Int,
    withSuggestion: Int,
    changedFromSuggestion: Int
):
  def summary: String =
    s"labeled $labeled of $total (same $same, different $different, " +
      s"unsure $unsure); with suggestion $withSuggestion, " +
      s"changed from suggestion $changedFromSuggestion"

/** A blind second pass over a subset: the pair_ids to relabel, in the order the
  * reviewer sees them, and the first-pass file the other pairs are carried
  * from.
  */
final case class SecondPass(
    subset: Vector[String],
    carryFrom: Path,
    relabelPass: Int = 2
):
  require(
    relabelPass == 2 || relabelPass == 3,
    "A blind relabel is pass 2 or pass 3"
  )

/** The labeling state: pairs, the gold file and the optional suggestions.
  *
  * Every accepted label is written to disk before the call returns, by an
  * atomic replace of the whole gold file, so a crash or a failed write leaves
  * the previous complete file. A label whose write fails is not kept in memory
  * either.
  *
  * Suggestions are hidden until `reveal` is called for that pair, and `accept`
  * works only on a revealed suggestion. This class reads no matcher output and
  * nothing here is read by matching code.
  *
  * In a second pass `pairs` is only the subset, in subset order, and the gold
  * file also holds every other pair as its first-pass line. The first-pass
  * label of a subset pair is kept out of every value this class hands to the
  * labeling page.
  */
final class LabelSession private (
    allPairs: Vector[PairItem],
    val pairs: Vector[PairItem],
    goldPath: Path,
    val suggestions: Option[SuggestionSet],
    val reviewer: String,
    clock: () => Instant,
    initial: Map[String, GoldEntry],
    carry: Option[CarryOver],
    val relabelPass: Int = 2
):
  private var entries: Map[String, GoldEntry] = initial
  private var revealed: Set[String] = Set.empty
  private val byId: Map[String, PairItem] = pairs.map(p => p.pairId -> p).toMap
  private val maxNoteLength = 2000

  def size: Int = pairs.size

  def isSubset: Boolean = carry.isDefined

  def carriedCount: Int = carry.fold(0)(_ => allPairs.size - pairs.size)

  def carriedFrom: Option[(String, String)] =
    carry.map(c => (c.file, c.sha256))

  def pairAt(index: Int): Option[PairItem] = pairs.lift(index)

  def firstUnlabeled: Int = synchronized {
    val index = pairs.indexWhere(p => !entries.contains(p.pairId))
    if index < 0 then pairs.size else index
  }

  def entryOf(pairId: String): Option[GoldEntry] = synchronized {
    entries.get(pairId)
  }

  def hasSuggestion(pairId: String): Boolean =
    suggestions.exists(_.byPair.contains(pairId))

  def reveal(pairId: String): Option[Suggestion] = synchronized {
    val found = suggestions.flatMap(_.byPair.get(pairId))
    if found.isDefined && byId.contains(pairId) then revealed += pairId
    found
  }

  /** The suggestion, only after it was revealed. */
  def shownSuggestion(pairId: String): Option[Suggestion] = synchronized {
    if revealed.contains(pairId) then suggestions.flatMap(_.byPair.get(pairId))
    else None
  }

  def stats: SessionStats = synchronized {
    val all = entries.values.toVector
    def count(verdict: Verdict) = all.count(_.label.verdict == verdict)
    SessionStats(
      pairs.size,
      all.size,
      count(Verdict.Match),
      count(Verdict.NonMatch),
      count(Verdict.Unsure),
      all.count(_.suggestion.isDefined),
      all.count(_.changedFromSuggestion)
    )
  }

  private def cleanNote(note: Option[String]): Either[String, Option[String]] =
    val trimmed = note.map(_.trim).filter(_.nonEmpty)
    if trimmed.exists(_.length > maxNoteLength) then
      Left(s"Note is longer than $maxNoteLength characters")
    else Right(trimmed)

  /** Records the human's verdict. Labeling a pair again revises it. */
  def label(
      pairId: String,
      verdict: Verdict,
      note: Option[String]
  ): Either[String, GoldEntry] = synchronized {
    for
      pair <- byId.get(pairId).toRight(s"Unknown pair_id $pairId")
      text <- cleanNote(note)
      entry <- scala.util
        .Try(build(pair, verdict, text))
        .toEither
        .left
        .map(_.getMessage)
      _ <- persist(entries.updated(pairId, entry))
    yield
      entries = entries.updated(pairId, entry)
      entry
  }

  /** Takes the revealed suggestion as the label. It fails when the pair has no
    * suggestion or the suggestion has not been revealed.
    */
  def accept(pairId: String, note: Option[String]): Either[String, GoldEntry] =
    synchronized {
      for
        _ <- byId.get(pairId).toRight(s"Unknown pair_id $pairId")
        found <- suggestions
          .flatMap(_.byPair.get(pairId))
          .toRight("There is no suggestion for this pair")
        _ <- Either.cond(
          revealed.contains(pairId),
          (),
          "Reveal the suggestion before accepting it"
        )
        entry <- label(pairId, found.label, note)
      yield entry
    }

  private def build(
      pair: PairItem,
      verdict: Verdict,
      note: Option[String]
  ): GoldEntry =
    val current = suggestions.flatMap(_.byPair.get(pair.pairId))
    val prior = entries.get(pair.pairId)
    val (suggestion, source) = current match
      case Some(found) => (Some(found), suggestions.map(_.source))
      case None        => (prior.flatMap(_.suggestion), prior.flatMap(_.source))
    val snapshot =
      if pair.left.snapshotId == pair.right.snapshotId then pair.left.snapshotId
      else s"${pair.left.snapshotId}+${pair.right.snapshotId}"
    val gold = GoldLabel(
      ContractSchema.currentVersion,
      pair.pairId,
      pair.kind,
      pair.left.recordId,
      pair.right.recordId,
      pair.left.sourceRecordId,
      pair.right.sourceRecordId,
      snapshot,
      verdict,
      GoldLabel.HumanMethod,
      reviewer,
      clock().toString,
      note
    )
    GoldEntry(gold, suggestion, source, suggestion.exists(_.label != verdict))

  private def lineOf(next: Map[String, GoldEntry])(
      pair: PairItem
  ): Option[String] =
    carry match
      case None        => next.get(pair.pairId).map(GoldLines.render(_, pair))
      case Some(first) =>
        if byId.contains(pair.pairId) then
          for
            entry <- next.get(pair.pairId)
            before <- first.labels.get(pair.pairId)
          yield GoldLines.renderBlind(entry, pair, before, relabelPass)
        else first.lines.get(pair.pairId)

  private def persist(next: Map[String, GoldEntry]): Either[String, Unit] =
    try
      AtomicFile.writeLines(goldPath, allPairs.flatMap(lineOf(next)))
      Right(())
    catch case NonFatal(error) => Left(s"Could not save: ${error.getMessage}")

object LabelSession:
  /** Opens a session. It refuses, and writes nothing, when the reviewer is
    * blank, the pairs file or suggestion CSV is malformed, or an existing gold
    * file is unreadable or does not belong to this pairs file.
    *
    * Without a suggestion file it also refuses an existing gold file that holds
    * suggestions, so a blind pass cannot resume or overwrite a pass that was
    * made with them. A second pass takes no suggestion file, refuses a gold
    * path that is the file it carries from, and reads that file only.
    */
  def open(
      pairsPath: Path,
      goldPath: Path,
      suggestionsPath: Option[Path],
      reviewer: String,
      clock: () => Instant = () => Instant.now(),
      second: Option[SecondPass] = None
  ): Either[String, LabelSession] =
    for
      _ <- Either.cond(
        reviewer.trim.nonEmpty,
        (),
        "A reviewer name is required"
      )
      pairs <- scala.util
        .Try(PairsFile.read(pairsPath))
        .toEither
        .left
        .map(_.getMessage)
      _ <- Either.cond(pairs.nonEmpty, (), "The pairs file has no pairs")
      _ <- Either.cond(
        second.isEmpty || suggestionsPath.isEmpty,
        (),
        "A second pass is blind and takes no suggestion file"
      )
      plan <- planOf(pairs, goldPath, second)
      (work, carry) = plan
      loaded <- loadSuggestions(suggestionsPath, pairs.map(_.pairId).toSet)
      existing <- readGold(
        goldPath,
        pairs,
        work,
        carry.isDefined,
        second.fold(2)(_.relabelPass)
      )
      _ <- Either.cond(
        carry.isDefined || suggestionsPath.isDefined ||
          !existing.values.exists(_.suggestion.isDefined),
        (),
        s"Gold file ${goldPath.getFileName} holds suggestions from another " +
          "pass. A pass without a suggestion file needs a fresh --gold path"
      )
    yield new LabelSession(
      pairs,
      work,
      goldPath,
      loaded,
      reviewer.trim,
      clock,
      existing,
      carry,
      second.fold(2)(_.relabelPass)
    )

  private def samePath(a: Path, b: Path): Boolean =
    a.toAbsolutePath.normalize == b.toAbsolutePath.normalize ||
      (Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b))

  private def planOf(
      pairs: Vector[PairItem],
      goldPath: Path,
      second: Option[SecondPass]
  ): Either[String, (Vector[PairItem], Option[CarryOver])] =
    second match
      case None        => Right((pairs, None))
      case Some(other) =>
        val byId = pairs.map(p => p.pairId -> p).toMap
        val unknown = other.subset.filterNot(byId.contains)
        for
          _ <- Either.cond(
            !samePath(goldPath, other.carryFrom),
            (),
            "--gold is the file to carry from; that would overwrite the " +
              "first pass"
          )
          _ <- Either.cond(
            unknown.isEmpty,
            (),
            s"The subset names ${unknown.size} pair_ids that are not in the " +
              s"pairs file: ${unknown.take(5).mkString(", ")}"
          )
          first <-
            if other.relabelPass == 3 then
              for
                _ <- Either.cond(
                  other.subset.distinct.size == other.subset.size,
                  (),
                  "The subset repeats a pair_id"
                )
                loaded <- CarryOver.loadSecondPass(
                  other.carryFrom,
                  pairs,
                  other.subset.toSet
                )
                _ <- Either.cond(
                  !loaded.carriedNames.exists(name =>
                    samePath(goldPath, other.carryFrom.resolveSibling(name))
                  ),
                  (),
                  "--gold names the first-pass file the carry file was made " +
                    "from; that would overwrite the first pass"
                )
              yield loaded
            else CarryOver.load(other.carryFrom, pairs)
        yield (other.subset.map(byId), Some(first))

  private def loadSuggestions(
      path: Option[Path],
      ids: Set[String]
  ): Either[String, Option[SuggestionSet]] =
    path match
      case None       => Right(None)
      case Some(file) =>
        scala.util
          .Try(Suggestions.load(file, ids))
          .toEither
          .left
          .map(error => s"Cannot read $file: ${error.getMessage}")
          .flatMap { parsed =>
            parsed.left
              .map { problems =>
                s"Malformed suggestion file ${file.getFileName}:\n" +
                  problems.map("  " + _).mkString("\n")
              }
              .map(Some(_))
          }

  private def readGold(
      path: Path,
      pairs: Vector[PairItem],
      work: Vector[PairItem],
      secondPass: Boolean,
      ownPass: Int
  ): Either[String, Map[String, GoldEntry]] =
    if !Files.exists(path) then Right(Map.empty)
    else
      try
        val parsed = Files
          .readAllLines(path, UTF_8)
          .asScala
          .zipWithIndex
          .filter((line, _) => line.trim.nonEmpty)
          .map { (line, number) =>
            try
              val entry = GoldLines.parse(line)
              if secondPass && ownPass == 3 then
                GoldLines.passOfAny(line) match
                  case Some(2)     => None
                  case Some(3)     => Some(entry)
                  case Some(other) =>
                    throw new IllegalArgumentException(
                      s"pass $other row; this is not a pass-3 file"
                    )
                  case None =>
                    throw new IllegalArgumentException(
                      "no pass field; this is not a pass-3 file"
                    )
              else if secondPass then
                GoldLines.passOf(line) match
                  case None =>
                    throw new IllegalArgumentException(
                      "no pass field; this is not a second-pass file"
                    )
                  case Some(1) => None
                  case _       => Some(entry)
              else Some(entry)
            catch
              case NonFatal(error) =>
                throw new IllegalArgumentException(
                  s"line ${number + 1}: ${error.getMessage}"
                )
          }
          .toVector
          .flatten
        val byId = pairs.map(p => p.pairId -> p).toMap
        val inWork = work.map(_.pairId).toSet
        val duplicated =
          parsed.groupBy(_.pairId).filter(_._2.size > 1).keys.toVector
        val foreign = parsed.filter { entry =>
          byId.get(entry.pairId).forall { pair =>
            pair.left.sourceRecordId != entry.label.leftSourceRecordId ||
            pair.right.sourceRecordId != entry.label.rightSourceRecordId
          } || (secondPass && !inWork(entry.pairId))
        }
        val hinted =
          parsed.filter(_ => secondPass).filter(_.suggestion.isDefined)
        if duplicated.nonEmpty then
          Left(
            s"Existing gold file ${path.getFileName} repeats pair_id " +
              duplicated.sorted.mkString(", ")
          )
        else if foreign.nonEmpty then
          Left(
            s"Existing gold file ${path.getFileName} has labels that are not " +
              s"in this pairs file or subset: ${foreign.map(_.pairId).mkString(", ")}"
          )
        else if hinted.nonEmpty then
          Left(
            s"Existing gold file ${path.getFileName} has second-pass rows " +
              s"with suggestions: ${hinted.map(_.pairId).mkString(", ")}"
          )
        else Right(parsed.map(e => e.pairId -> e).toMap)
      catch
        case NonFatal(error) =>
          Left(
            s"Existing gold file ${path.getFileName} is unreadable and was " +
              s"left untouched: ${error.getMessage}"
          )
