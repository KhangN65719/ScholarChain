package scholarchain.tests.analytics

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scholarchain.core.StableIds

enum Kind:
  case Resolved, Unlinked, Failed, NoLink

enum PairMode:
  case Normal, Reversed, Both

final case class Au(person: String, kind: Kind, name: String)

final case class Wk(
    key: String,
    year: Option[Int],
    authors: Vector[Au],
    canonicalKey: Option[String] = None,
    source: String = "dblp",
    venue: Option[String] = None,
    pairs: PairMode = PairMode.Normal
)

final case class PairRow(
    workKey: String,
    mentionA: String,
    mentionB: String,
    positionA: Int,
    positionB: Int
)

final case class World(
    works: Vector[Wk],
    extraPairs: Vector[PairRow] = Vector.empty,
    ghostWorkLinks: Vector[String] = Vector.empty,
    emptyLinks: Boolean = false,
    duplicateWorkRows: Vector[String] = Vector.empty,
    duplicateAuthorRows: Vector[(String, Int)] = Vector.empty,
    duplicatePairRows: Vector[(String, Int, Int)] = Vector.empty,
    duplicateLinkRows: Vector[(String, Int)] = Vector.empty,
    // People whose canonical ID is in the links but not in the canonical table.
    orphans: Vector[String] = Vector.empty,
    // (work, position) whose link row names the work "pa1" instead.
    wrongLinkWork: Vector[(String, Int)] = Vector.empty
)

// Identifiers are built with the core contract functions, so the fixtures
// speak the same identifier language as the Hadoop and ER artifacts.
object Ids:
  def work(key: String): String = StableIds.work("dblp", key)

  def mention(key: String, position: Int): String =
    StableIds.mention(work(key), position)

  def researcher(person: String): String =
    StableIds.researcherFromExternal("orcid", person)

object Spec:
  private val kinds = Map(
    "u:" -> Kind.Unlinked,
    "f:" -> Kind.Failed,
    "n:" -> Kind.NoLink,
    "r:" -> Kind.Resolved
  )

  // "u:" unlinked, "f:" integrity-failure, "n:" no link row at all.
  def au(token: String, names: Map[String, String]): Au =
    val prefix = token.take(2)
    val kind = kinds.getOrElse(prefix, Kind.Resolved)
    val person = if kinds.contains(prefix) then token.drop(2) else token
    Au(person, kind, names.getOrElse(person, person))

  // The node a person is in the graph: the canonical researcher, or the
  // first mention of an unresolved person.
  def nodeId(world: World, person: String): String =
    val found = world.works.flatMap { work =>
      work.authors.zipWithIndex.collect {
        case (author, index) if author.person == person =>
          (work, author, index + 1)
      }
    }
    val (work, author, position) = found.head
    author.kind match
      case Kind.Resolved => Ids.researcher(person)
      case _             => Ids.mention(work.key, position)

object Fixtures:
  val snapshot = "8e1be6626420"
  val erRunDir = "run=run-v1-2f4cd132-afcb-4bca-83c5-1a0ddf001ace"
  val hadoopRunDir = "run=run-v1-d4722bc7-0d02-42da-bdb6-088ee508a143"

  final case class Inputs(erRun: Path, hadoopRoot: Path)

  def write(world: World, base: Path): Inputs =
    val erBase = base.resolve("er").resolve(erRunDir)
    val er = erBase.resolve(s"snapshot=$snapshot")
    val hadoopBase = base.resolve("hadoop").resolve(hadoopRunDir)
    val hadoop = hadoopBase.resolve(s"snapshot=$snapshot")
    writeEr(world, er)
    writeHadoop(world, hadoop)
    Inputs(er, hadoop)

  // ---- ER side: accepted links only ----

  private def writeEr(world: World, er: Path): Unit =
    val links = er.resolve("accepted-links").resolve("v1")
    Files.createDirectories(er)
    Files.writeString(er.resolve("_SUCCESS"), "", UTF_8)
    val manifest = ujson.Obj(
      "runId" -> "run:v1:2f4cd132-afcb-4bca-83c5-1a0ddf001ace",
      "snapshot" -> snapshot,
      "policyVersion" -> "scholarchain.er.policy/1.4.0"
    )
    val manifestPath = er.resolve("manifest.json")
    Files.writeString(manifestPath, ujson.write(manifest), UTF_8)
    writeParts(links.resolve("researcher-links"), researcherLinks(world))
    writeParts(links.resolve("work-links"), workLinks(world))
    writeParts(links.resolve("canonical-researchers"), canonical(world))
    // Artifacts the pipeline must never read. They are not JSON, so a read
    // with the strict schema would fail the run.
    Vector("decisions/v1", "integrity-failures/v1", "gold").foreach { name =>
      val file = er.resolve(name).resolve("part-00000")
      Files.createDirectories(file.getParent)
      Files.writeString(file, poison, UTF_8)
    }

  val poison = "NOT JSON: a label, a decision or a candidate, never an input\n"

  private def statusOf(author: Au): String =
    author.kind match
      case Kind.Resolved => "external-id"
      case Kind.Unlinked => "unlinked"
      case Kind.Failed   => "integrity-failure"
      case Kind.NoLink   => "none"

  private def canonicalId(author: Au): ujson.Value =
    author.kind match
      case Kind.Resolved => ujson.Str(Ids.researcher(author.person))
      case _             => ujson.Null

  private def linkRow(
      world: World,
      work: Wk,
      seat: (Au, Int, String)
  ): ujson.Value =
    val (author, position, mention) = seat
    val wrong = world.wrongLinkWork.contains((work.key, position))
    val named = if wrong then "pa1" else work.key
    ujson.Obj(
      "mentionId" -> mention,
      "workId" -> Ids.work(named),
      "canonicalResearcherId" -> canonicalId(author),
      "status" -> statusOf(author),
      "idRule" -> "orcid",
      "clusterAnchor" -> mention,
      "clusterSize" -> 1
    )

  private def researcherLinks(world: World): Vector[ujson.Value] =
    val rows = world.works.flatMap { work =>
      val linked = seats(work).filter(_._1.kind != Kind.NoLink)
      linked.map(seat => linkRow(world, work, seat))
    }
    val repeated = world.duplicateLinkRows.flatMap { (key, position) =>
      val work = world.works.find(_.key == key).get
      val matching = seats(work).filter(_._2 == position)
      matching.map(seat => linkRow(world, work, seat))
    }
    (rows ++ repeated).filter(_ => !world.emptyLinks)

  private def workLinks(world: World): Vector[ujson.Value] =
    val sizes = world.works.groupBy(w => w.canonicalKey.getOrElse(w.key))
    val rows = world.works.map { work =>
      val canonicalKey = work.canonicalKey.getOrElse(work.key)
      val size = sizes(canonicalKey).size
      val status = if size > 1 then "merged" else "unlinked"
      ujson.Obj(
        "workId" -> Ids.work(work.key),
        "canonicalWorkId" -> Ids.work(canonicalKey),
        "status" -> status,
        "clusterAnchor" -> Ids.work(canonicalKey),
        "clusterSize" -> size
      )
    }
    val ghosts = world.ghostWorkLinks.map { key =>
      ujson.Obj(
        "workId" -> Ids.work(key),
        "canonicalWorkId" -> Ids.work(key),
        "status" -> "unlinked",
        "clusterAnchor" -> Ids.work(key),
        "clusterSize" -> 1
      )
    }
    (rows ++ ghosts).filter(_ => !world.emptyLinks)

  private def canonical(world: World): Vector[ujson.Value] =
    val authors = world.works.flatMap(_.authors)
    val resolved = authors.filter(_.kind == Kind.Resolved)
    val people = resolved.filterNot(a => world.orphans.contains(a.person))
    val counts = people.groupBy(_.person).view.mapValues(_.size).toMap
    val rows = counts.toVector.sortBy(_._1).map { (person, count) =>
      ujson.Obj(
        "canonicalResearcherId" -> Ids.researcher(person),
        "idRule" -> "orcid",
        "orcid" -> person,
        "status" -> "external-id",
        "memberCount" -> count,
        "inResearchersExport" -> false
      )
    }
    rows.filter(_ => !world.emptyLinks)

  // ---- Hadoop side ----

  private def seats(work: Wk): Vector[(Au, Int, String)] =
    work.authors.zipWithIndex.map { (author, index) =>
      (author, index + 1, Ids.mention(work.key, index + 1))
    }

  private def flip(row: PairRow): PairRow =
    PairRow(
      row.workKey,
      row.mentionB,
      row.mentionA,
      row.positionB,
      row.positionA
    )

  // Every unordered pair of authors, as the Hadoop team expansion emits them.
  def pairsOf(work: Wk): Vector[PairRow] =
    val seat = seats(work)
    val ordered = seat.indices.toVector.flatMap { i =>
      ((i + 1) until seat.size).map { j =>
        PairRow(work.key, seat(i)._3, seat(j)._3, i + 1, j + 1)
      }
    }
    work.pairs match
      case PairMode.Normal   => ordered
      case PairMode.Reversed => ordered.map(flip)
      case PairMode.Both     => ordered ++ ordered.map(flip)

  private def yearValue(year: Option[Int]): ujson.Value =
    year.fold[ujson.Value](ujson.Null)(y => ujson.Num(y.toDouble))

  private def optValue(value: Option[String]): ujson.Value =
    value.fold[ujson.Value](ujson.Null)(v => ujson.Str(v))

  private def workJson(work: Wk): ujson.Value =
    ujson.Obj(
      "workId" -> Ids.work(work.key),
      "source" -> work.source,
      "sourceRecordId" -> work.key,
      "title" -> s"Title of ${work.key}",
      "year" -> yearValue(work.year),
      "venueId" -> optValue(work.venue),
      "doi" -> ujson.Null
    )

  private def workRows(world: World): Vector[ujson.Value] =
    val extra = world.duplicateWorkRows.flatMap { key =>
      world.works.find(_.key == key).map(work => workJson(work))
    }
    world.works.map(work => workJson(work)) ++ extra

  private def mentionRows(world: World): Vector[ujson.Value] =
    world.works.flatMap { work =>
      seats(work).map { (author, _, mention) =>
        ujson.Obj(
          "mentionId" -> mention,
          "workId" -> Ids.work(work.key),
          "name" -> author.name,
          "normalizedName" -> author.name.toLowerCase
        )
      }
    }

  private def authorPaper(work: Wk, seat: (Au, Int, String)): ujson.Value =
    ujson.Obj(
      "workId" -> Ids.work(work.key),
      "mentionId" -> seat._3,
      "authorPosition" -> seat._2,
      "year" -> yearValue(work.year)
    )

  private def authorPaperRows(world: World): Vector[ujson.Value] =
    val rows = world.works.flatMap { work =>
      seats(work).map(seat => authorPaper(work, seat))
    }
    val repeated = world.duplicateAuthorRows.flatMap { (key, position) =>
      val work = world.works.find(_.key == key).get
      val matching = seats(work).filter(_._2 == position)
      matching.map(seat => authorPaper(work, seat))
    }
    rows ++ repeated

  private def pairJson(world: World, row: PairRow): ujson.Value =
    val work = world.works.find(_.key == row.workKey).get
    ujson.Obj(
      "workId" -> Ids.work(row.workKey),
      "mentionA" -> row.mentionA,
      "mentionB" -> row.mentionB,
      "authorPositionA" -> row.positionA,
      "authorPositionB" -> row.positionB,
      "year" -> yearValue(work.year)
    )

  private def pairRows(world: World): Vector[ujson.Value] =
    val repeated = world.duplicatePairRows.map { (key, i, j) =>
      PairRow(key, Ids.mention(key, i), Ids.mention(key, j), i, j)
    }
    val all = world.works.flatMap(pairsOf) ++ world.extraPairs ++ repeated
    all.map(row => pairJson(world, row))

  private def teamRows(world: World): Vector[ujson.Value] =
    world.works.map { work =>
      val ids = seats(work).map(seat => ujson.Str(seat._3))
      ujson.Obj(
        "workId" -> Ids.work(work.key),
        "year" -> yearValue(work.year),
        "orderedMentionIds" -> ujson.Arr.from(ids)
      )
    }

  private def provenance(recordId: String): ujson.Obj =
    ujson.Obj(
      "source" -> "dblp",
      "snapshotId" -> "fixture",
      "sourceRecordId" -> recordId,
      "sourceUri" -> "fixture:record"
    )

  private def venueRows: Vector[ujson.Value] =
    Vector(
      ujson.Obj(
        "venueId" -> "venue-a",
        "name" -> "Journal A",
        "issns" -> ujson.Arr("1234-5678"),
        "provenance" -> provenance("v1")
      )
    )

  // Two input rows for one topic: the best row is the smaller name.
  private def topicRows: Vector[ujson.Value] =
    Vector(
      ujson.Obj(
        "topicId" -> "topic-a",
        "name" -> "Zeta",
        "parentTopicId" -> ujson.Null,
        "provenance" -> provenance("t2")
      ),
      ujson.Obj(
        "topicId" -> "topic-a",
        "name" -> "Alpha",
        "parentTopicId" -> ujson.Null,
        "provenance" -> provenance("t1")
      )
    )

  private def institutionRows: Vector[ujson.Value] =
    Vector(
      ujson.Obj(
        "institutionId" -> "inst-a",
        "name" -> "Institute A",
        "countryCode" -> "US",
        "externalIds" -> ujson.Obj("ror" -> "ror-a"),
        "provenance" -> provenance("i1")
      )
    )

  private def writeHadoop(world: World, root: Path): Unit =
    val graph = root.resolve("stage=graph-export")
    val team = root.resolve("stage=team-expansion")
    writeParts(graph.resolve("works/v1"), workRows(world))
    writeParts(graph.resolve("author-mentions/v1"), mentionRows(world))
    writeParts(graph.resolve("institutions/v1"), institutionRows)
    writeParts(graph.resolve("topics/v1"), topicRows)
    writeParts(graph.resolve("venues/v1"), venueRows)
    writeParts(team.resolve("author-papers/v1"), authorPaperRows(world))
    writeParts(team.resolve("pair-candidates/v1"), pairRows(world))
    writeParts(team.resolve("teams/v1"), teamRows(world))
    Files.writeString(team.resolve("_SUCCESS"), "", UTF_8)
    val candidates = root.resolve("stage=candidate-features/match-candidates")
    Files.createDirectories(candidates.resolve("v1"))
    Files.writeString(candidates.resolve("v1/part-r-00000"), poison, UTF_8)

  private def writeLines(path: Path, rows: Vector[ujson.Value]): Unit =
    val text = rows.map(row => ujson.write(row) + "\n").mkString
    Files.createDirectories(path.getParent)
    Files.writeString(path, text, UTF_8)

  // Two part files, as a multi-reducer job leaves them; either may be empty.
  private def writeParts(dir: Path, rows: Vector[ujson.Value]): Unit =
    val indexed = rows.zipWithIndex
    val even = indexed.filter(_._2 % 2 == 0).map(_._1)
    val odd = indexed.filter(_._2 % 2 == 1).map(_._1)
    writeLines(dir.resolve("part-r-00000"), even)
    writeLines(dir.resolve("part-r-00001"), odd)
