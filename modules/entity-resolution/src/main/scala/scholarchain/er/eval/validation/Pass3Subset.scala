package scholarchain.er.eval.validation

import java.nio.file.{Path, Paths}
import scholarchain.er.eval.labeling.SubsetFile
import scholarchain.er.pairs.{AtomicFile, Cli, Hashing, Locations, PairsFile}

/** The pairs pass 3 relabels blind: every pair the pass-2 subset does not
  * contain. `pass3` is in the order the reviewer will see it, a fixed shuffle
  * that depends only on the salt and the pair_id.
  */
final case class Pass3Selection(pass2: Vector[String], pass3: Vector[String])

/** Offline only, and it needs no strata key: the subset is a complement, so
  * nothing about a pair's stratum, suggestion or earlier label can enter the
  * choice or the order.
  */
object Pass3Subset:
  val Salt = "scholarchain-phase5-pass3/1"

  def select(
      pairIds: Vector[String],
      pass2: Vector[String],
      salt: String = Salt
  ): Either[String, Pass3Selection] =
    val known = pairIds.toSet
    val repeatedPairs = pairIds.diff(pairIds.distinct).distinct
    val repeatedSubset = pass2.diff(pass2.distinct).distinct
    val unknown = pass2.filterNot(known)
    val rest = pairIds.filterNot(pass2.toSet)
    val problems = Vector(
      Option.when(salt.trim.isEmpty)("the salt is blank"),
      Option.when(salt == SubsetSelection.Salt)(
        "the salt is the pass-2 salt; pass 3 needs its own"
      ),
      Option.when(repeatedPairs.nonEmpty)(
        s"the pairs file repeats ${repeatedPairs.size} pair_ids"
      ),
      Option.when(repeatedSubset.nonEmpty)(
        s"the pass-2 subset repeats ${repeatedSubset.size} pair_ids"
      ),
      Option.when(unknown.nonEmpty)(
        s"the pass-2 subset names ${unknown.size} pair_ids that are not in " +
          s"the pairs file: ${unknown.take(3).mkString(", ")}"
      ),
      Option.when(pass2.isEmpty)("the pass-2 subset is empty"),
      Option.when(rest.isEmpty)("no pair is left for pass 3")
    ).flatten
    if problems.nonEmpty then Left(problems.mkString("; "))
    else Right(Pass3Selection(pass2, SubsetSelection.order(rest.toSet, salt)))

  def summary(s: Pass3Selection, total: Int): Vector[String] =
    val disjoint = s.pass2.toSet.intersect(s.pass3.toSet).isEmpty
    val sum = s.pass2.size + s.pass3.size
    Vector(
      s"pass-2 subset: ${s.pass2.size}",
      s"pass-3 subset: ${s.pass3.size}",
      s"${s.pass2.size} + ${s.pass3.size} = $sum of $total pairs, " +
        (if disjoint then "disjoint" else "NOT DISJOINT") +
        (if sum == total then "" else ", NOT ALL PAIRS")
    )

  def write(out: Path, chosen: Pass3Selection): Unit =
    AtomicFile.writeLines(out, chosen.pass3)

  final case class Args(pairs: Path, pass2: Path, out: Path, salt: String)

  private val Known = Set("--pairs", "--pass2-subset", "--out", "--salt")

  private val usage =
    "Usage: Pass3Subset --pairs <pairs.jsonl> --pass2-subset <pass2-subset.txt> " +
      "--out <pass3-subset.txt> [--salt text]"

  def parseArgs(args: Seq[String]): Either[String, Args] =
    for
      o <- Cli.parse(args)
      _ <- Cli
        .unknown(o, Known)
        .toLeft(())
        .left
        .map(name => s"Unknown option $name")
      pairs <- o.get("--pairs").toRight("--pairs missing")
      pass2 <- o.get("--pass2-subset").toRight("--pass2-subset missing")
      out <- o.get("--out").toRight("--out missing")
    yield Args(
      Paths.get(pairs),
      Paths.get(pass2),
      Paths.get(out),
      o.getOrElse("--salt", Salt)
    )

  def main(args: Array[String]): Unit =
    val run = for
      a <- parseArgs(args.toSeq)
      _ <- Locations.check(a.out).left.map(m => s"--out: $m")
      ids = PairsFile.read(a.pairs).map(_.pairId)
      pass2 <- SubsetFile.read(a.pass2)
      chosen <- select(ids, pass2, a.salt)
    yield (a, ids, chosen)
    run match
      case Left(message) =>
        Console.err.println(s"$message\n$usage")
        sys.exit(2)
      case Right((a, ids, chosen)) =>
        write(a.out, chosen)
        Vector(a.pairs, a.pass2).foreach(p =>
          println(s"sha256 ${Hashing.sha256File(p)}  $p")
        )
        summary(chosen, ids.size).foreach(println)
        println(
          "order: SHA-256 of the salt and the pair_id, independent of " +
            "stratum and of the order of the pairs file"
        )
        println(s"Wrote ${a.out} (sha256 ${Hashing.sha256File(a.out)})")
