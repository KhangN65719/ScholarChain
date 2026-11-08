package scholarchain.er.eval.labeling

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scholarchain.er.eval.Verdict
import scholarchain.er.pairs.{Hashing, PairItem}

/** The pair_ids a blind second pass relabels, one per line, in the order the
  * reviewer sees them. The file says nothing about why a pair was chosen.
  */
object SubsetFile:
  def parse(lines: Iterable[String]): Either[String, Vector[String]] =
    val ids = lines.iterator.map(_.trim).filter(_.nonEmpty).toVector
    val repeated = ids.diff(ids.distinct).distinct.sorted
    if ids.isEmpty then Left("The subset file has no pair_id")
    else if repeated.nonEmpty then
      Left(s"The subset file repeats ${repeated.mkString(", ")}")
    else Right(ids)

  def read(path: Path): Either[String, Vector[String]] =
    try parse(Files.readAllLines(path, UTF_8).asScala)
    catch
      case NonFatal(error) =>
        Left(s"Cannot read the subset file $path: ${error.getMessage}")

/** The first pass, read only. Every pair of the first pass is kept as a gold
  * line marked `pass` 1 with the file name and SHA-256 it was carried from, and
  * its label is remembered so a second-pass row can record it. The first-pass
  * file is never written.
  */
final case class CarryOver(
    file: String,
    sha256: String,
    lines: Map[String, String],
    labels: Map[String, Verdict],
    carriedNames: Vector[String] = Vector.empty
)

object CarryOver:
  /** Loads a second-pass file for a third pass. Its pass 1 rows must be exactly
    * the pairs about to be relabeled; their labels are remembered and their
    * lines dropped, so nothing a first-pass reviewer saw is carried forward.
    * Its pass 2 rows are kept value for value and marked with this file's name
    * and SHA-256. The file is read only.
    */
  def loadSecondPass(
      path: Path,
      pairs: Vector[PairItem],
      relabel: Set[String]
  ): Either[String, CarryOver] =
    try
      val bytes = Files.readAllBytes(path)
      val sha = Hashing.sha256Bytes(bytes)
      val name = path.getFileName.toString
      val rows = new String(bytes, UTF_8).linesIterator.zipWithIndex
        .filter((line, _) => line.trim.nonEmpty)
        .map { (line, number) =>
          try
            val json = ujson.read(line).obj
            (GoldLines.parse(line), json, GoldLines.passOfJson(json))
          catch
            case NonFatal(error) =>
              throw new IllegalArgumentException(
                s"line ${number + 1}: ${error.getMessage}"
              )
        }
        .toVector
      val byId = pairs.map(p => p.pairId -> p).toMap
      val ids = rows.map(_._1.pairId)
      val repeated = ids.diff(ids.distinct).distinct.sorted
      val unmarked = rows.filter(r => !r._3.exists(p => p == 1 || p == 2))
      val foreign = rows.filter { (entry, _, _) =>
        byId.get(entry.pairId).forall { pair =>
          pair.left.sourceRecordId != entry.label.leftSourceRecordId ||
          pair.right.sourceRecordId != entry.label.rightSourceRecordId
        }
      }
      val missing = pairs.map(_.pairId).diff(ids)
      val firstRows = rows.filter(_._3.contains(1))
      val firstIds = firstRows.map(_._1.pairId).toSet
      val notRows = relabel.diff(firstIds)
      val notSubset = firstIds.diff(relabel)
      if repeated.nonEmpty then
        Left(s"$name repeats pair_id ${repeated.mkString(", ")}")
      else if unmarked.nonEmpty then
        Left(
          s"$name has ${unmarked.size} rows without pass 1 or 2; " +
            "this is not a second-pass file"
        )
      else if foreign.nonEmpty then
        Left(
          s"$name has labels that are not in this pairs file: " +
            foreign.map(_._1.pairId).mkString(", ")
        )
      else if missing.nonEmpty then
        Left(
          s"$name has no label for ${missing.size} pairs: " +
            missing.take(5).mkString(", ")
        )
      else if notRows.nonEmpty || notSubset.nonEmpty then
        Left(
          s"The subset must be exactly the pass-1 rows of $name: " +
            s"${notRows.size} subset pairs are not pass-1 rows, " +
            s"${notSubset.size} pass-1 rows are not in the subset"
        )
      else
        val kept = rows.filter(_._3.contains(2)).map { (entry, json, _) =>
          val copy = ujson.Obj.from(json.value.toSeq)
          copy("carried_from") = ujson.Str(name)
          copy("carried_from_sha256") = ujson.Str(sha)
          entry.pairId -> ujson.write(copy)
        }
        val named = firstRows.flatMap { (_, json, _) =>
          json.value.get("carried_from").collect { case ujson.Str(s) => s }
        }.distinct
        Right(
          CarryOver(
            name,
            sha,
            kept.toMap,
            firstRows.map((e, _, _) => e.pairId -> e.label.verdict).toMap,
            named
          )
        )
    catch
      case NonFatal(error) =>
        Left(s"Cannot read the carry file $path: ${error.getMessage}")

  def load(path: Path, pairs: Vector[PairItem]): Either[String, CarryOver] =
    try
      val bytes = Files.readAllBytes(path)
      val sha = Hashing.sha256Bytes(bytes)
      val name = path.getFileName.toString
      val rows = new String(bytes, UTF_8).linesIterator.zipWithIndex
        .filter((line, _) => line.trim.nonEmpty)
        .map { (line, number) =>
          try (GoldLines.parse(line), ujson.read(line).obj)
          catch
            case NonFatal(error) =>
              throw new IllegalArgumentException(
                s"line ${number + 1}: ${error.getMessage}"
              )
        }
        .toVector
      val byId = pairs.map(p => p.pairId -> p).toMap
      val repeated = rows.map(_._1.pairId).diff(rows.map(_._1.pairId).distinct)
      val marked = rows.filter(_._2.value.contains("pass"))
      val foreign = rows.filter { (entry, _) =>
        byId.get(entry.pairId).forall { pair =>
          pair.left.sourceRecordId != entry.label.leftSourceRecordId ||
          pair.right.sourceRecordId != entry.label.rightSourceRecordId
        }
      }
      val missing = pairs.map(_.pairId).diff(rows.map(_._1.pairId))
      if repeated.nonEmpty then
        Left(
          s"$name repeats pair_id ${repeated.distinct.sorted.mkString(", ")}"
        )
      else if marked.nonEmpty then
        Left(s"$name already has pass fields; carry from the first pass file")
      else if foreign.nonEmpty then
        Left(
          s"$name has labels that are not in this pairs file: " +
            foreign.map(_._1.pairId).mkString(", ")
        )
      else if missing.nonEmpty then
        Left(
          s"$name has no label for ${missing.size} pairs: " +
            missing.take(5).mkString(", ")
        )
      else
        val marks = rows.map { (entry, json) =>
          val copy = ujson.Obj.from(json.value.toSeq)
          copy("pass") = ujson.Num(1)
          copy("carried_from") = ujson.Str(name)
          copy("carried_from_sha256") = ujson.Str(sha)
          entry.pairId -> ujson.write(copy)
        }
        Right(
          CarryOver(
            name,
            sha,
            marks.toMap,
            rows.map((e, _) => e.pairId -> e.label.verdict).toMap
          )
        )
    catch
      case NonFatal(error) =>
        Left(s"Cannot read the carry file $path: ${error.getMessage}")
