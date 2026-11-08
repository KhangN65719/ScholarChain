package scholarchain.er.eval.validation

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scholarchain.er.eval.Verdict
import scholarchain.er.eval.labeling.Suggestions
import scholarchain.er.pairs.{AtomicFile, Cli, Hashing, Locations, PairsFile}

/** The pairs a blind second pass relabels: the hard-common-name stratum plus
  * the pairs the first-pass suggestion called unsure. `ordered` is the union in
  * the order the reviewer will see, a fixed shuffle that depends only on the
  * salt and the pair_id, so position says nothing about the stratum.
  */
final case class Selection(
    hard: Set[String],
    unsure: Set[String],
    ordered: Vector[String]
):
  def overlap: Set[String] = hard.intersect(unsure)
  def union: Set[String] = hard.union(unsure)

/** Offline only. The strata key is read here to choose the subset and nowhere
  * in the labeling tool, which receives the finished list of pair_ids.
  */
object SubsetSelection:
  val HardStratum = "hard-common-name"
  val Salt = "scholarchain-phase5-pass2/1"

  def order(ids: Set[String], salt: String): Vector[String] =
    ids.toVector.sortBy(id => Hashing.sha256Hex(s"$salt\n$id"))

  def select(
      pairIds: Vector[String],
      strata: Map[String, String],
      suggested: Map[String, Verdict],
      hardStratum: String,
      salt: String
  ): Either[String, Selection] =
    val known = pairIds.toSet
    val problems = Vector(
      Option.when(strata.keySet != known)(
        s"the strata key and the pairs file differ: " +
          s"${strata.keySet.diff(known).size} only in the key, " +
          s"${known.diff(strata.keySet).size} only in the pairs"
      ),
      Option.when(!suggested.keySet.subsetOf(known))(
        s"${suggested.keySet.diff(known).size} suggested pair_ids are not " +
          "in the pairs file"
      ),
      Option.when(!strata.values.exists(_ == hardStratum))(
        s"no pair is in the stratum '$hardStratum'"
      )
    ).flatten
    if problems.nonEmpty then Left(problems.mkString("; "))
    else
      val hard = strata.collect { case (id, s) if s == hardStratum => id }.toSet
      val unsure =
        suggested.collect { case (id, Verdict.Unsure) => id }.toSet
      Right(Selection(hard, unsure, order(hard.union(unsure), salt)))

  def strataOf(lines: Iterable[String]): Map[String, String] =
    lines.iterator
      .filter(_.trim.nonEmpty)
      .map { line =>
        val json = ujson.read(line)
        json("pair_id").str -> json("stratum").str
      }
      .toMap

  private val Known =
    Set(
      "--pairs",
      "--strata-key",
      "--suggestions",
      "--out",
      "--stratum",
      "--salt"
    )

  private val usage =
    "Usage: SubsetSelection --pairs <pairs.jsonl> --strata-key <file> " +
      "--suggestions <csv> --out <subset.txt> [--stratum name] [--salt text]"

  def summary(s: Selection, total: Int): Vector[String] =
    Vector(
      s"hard-common-name pairs: ${s.hard.size}",
      s"suggested unsure pairs: ${s.unsure.size}",
      s"in both: ${s.overlap.size}",
      s"union, the pairs to relabel blind: ${s.union.size}",
      s"carried unchanged from the first pass: ${total - s.union.size}"
    )

  def main(args: Array[String]): Unit =
    val run = for
      o <- Cli.parse(args.toSeq)
      _ <- Cli
        .unknown(o, Known)
        .toLeft(())
        .left
        .map(name => s"Unknown option $name")
      pairs <- o.get("--pairs").toRight("--pairs missing")
      key <- o.get("--strata-key").toRight("--strata-key missing")
      csv <- o.get("--suggestions").toRight("--suggestions missing")
      out <- o.get("--out").toRight("--out missing")
      _ <- Locations.check(Paths.get(out)).left.map(m => s"--out: $m")
      ids = PairsFile.read(Paths.get(pairs)).map(_.pairId)
      loaded <- Suggestions
        .load(Paths.get(csv), ids.toSet)
        .left
        .map(_.mkString("; "))
      _ <- Either.cond(
        loaded.unknownPairIds.isEmpty,
        (),
        s"${loaded.unknownPairIds.size} suggested pair_ids are not in the pairs"
      )
      strata = strataOf(Files.readAllLines(Paths.get(key), UTF_8).asScala)
      chosen <- select(
        ids,
        strata,
        loaded.byPair.view.mapValues(_.label).toMap,
        o.getOrElse("--stratum", HardStratum),
        o.getOrElse("--salt", Salt)
      )
    yield (
      chosen,
      Paths.get(out),
      Vector(pairs, key, csv).map(Paths.get(_)),
      ids
    )
    run match
      case Left(message) =>
        Console.err.println(s"$message\n$usage")
        sys.exit(2)
      case Right((chosen, out, inputs, ids)) =>
        write(out, chosen)
        inputs.foreach(p => println(s"sha256 ${Hashing.sha256File(p)}  $p"))
        summary(chosen, ids.size).foreach(println)
        println(
          "order: SHA-256 of the salt and the pair_id, independent of stratum"
        )
        println(s"Wrote $out (sha256 ${Hashing.sha256File(out)})")

  def write(out: Path, chosen: Selection): Unit =
    AtomicFile.writeLines(out, chosen.ordered)
