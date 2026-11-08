package scholarchain.spark

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scholarchain.core.StableIds

final case class AuthorSpec(
    researcher: Option[String],
    status: String,
    name: String
)

final case class WorkSpec(
    key: String,
    year: Option[Int],
    canonical: String,
    venue: Option[String],
    authors: Vector[AuthorSpec]
)

/** A tiny run directory in the layout of the real ER and Hadoop outputs.
  *
  * Works (canonical researchers A, B, C, E, F; unresolved mentions D, X and U):
  *   - W1 2001: A B C
  *   - W2 2002: A B; W2b is its crossref copy (same canonical work): A B U
  *   - W3 2005: A A D (the two A mentions are one canonical researcher)
  *   - W4 no year: A B
  *   - W5 2000: B E
  *   - W6 2004: A X (X is an integrity-failure mention)
  *   - W7 2006: A F
  * The accepted work links also carry one row for a work missing from the works
  * export, the one integrity violation of the clean run.
  */
object SyntheticRun:
  val erRunId = "run:v1:11111111-1111-4111-8111-111111111111"
  val hadoopRunId = "run:v1:22222222-2222-4222-8222-222222222222"
  val snapshot = "abc123def456"
  val policy = "scholarchain.er.policy/1.4.0"

  val orcids: Map[String, String] = Map(
    "A" -> "0000-0001-0000-0001",
    "B" -> "0000-0001-0000-0002",
    "C" -> "0000-0001-0000-0003",
    "E" -> "0000-0001-0000-0005",
    "F" -> "0000-0001-0000-0006"
  )
  val researcherIds: Map[String, String] =
    orcids.map((key, orcid) =>
      key -> StableIds.researcherFromExternal("orcid", orcid)
    )
  private val names = Map(
    "A" -> "Ada A",
    "B" -> "Bob B",
    "C" -> "Cy C",
    "E" -> "Eve E",
    "F" -> "Fay F"
  )

  private def known(key: String): AuthorSpec =
    AuthorSpec(Some(key), "merged", names(key))
  private def unlinked(name: String): AuthorSpec =
    AuthorSpec(None, "unlinked", name)

  val works: Vector[WorkSpec] = Vector(
    WorkSpec(
      "W1",
      Some(2001),
      "W1",
      Some("V1"),
      Vector(known("A"), known("B"), known("C"))
    ),
    WorkSpec("W2", Some(2002), "W2", None, Vector(known("A"), known("B"))),
    WorkSpec(
      "W2b",
      Some(2002),
      "W2",
      None,
      Vector(known("A"), known("B"), unlinked("Una U"))
    ),
    WorkSpec(
      "W3",
      Some(2005),
      "W3",
      None,
      Vector(known("A"), known("A"), unlinked("Dora D"))
    ),
    WorkSpec("W4", None, "W4", None, Vector(known("A"), known("B"))),
    WorkSpec("W5", Some(2000), "W5", None, Vector(known("B"), known("E"))),
    WorkSpec(
      "W6",
      Some(2004),
      "W6",
      None,
      Vector(
        known("A"),
        AuthorSpec(None, "integrity-failure", "Xavier X")
      )
    ),
    WorkSpec("W7", Some(2006), "W7", None, Vector(known("A"), known("F")))
  )

  def workId(key: String): String =
    StableIds.work(if key == "W2b" then "crossref" else "dblp", key)
  def mentionId(key: String, position: Int): String =
    StableIds.mention(workId(key), position)
  def venueId(key: String): String = s"venue:v1:dblp:$key"
  val ghostResearcher: String =
    StableIds.researcherFromExternal("orcid", "0000-0009-9999-9999")

  /** Label of a node ID: A, B, C, E, F, or D, X, U for unresolved mentions. */
  val labels: Map[String, String] =
    researcherIds.map((key, id) => id -> key) ++ Map(
      mentionId("W3", 3) -> "D",
      mentionId("W6", 2) -> "X",
      mentionId("W2b", 3) -> "U"
    )

  private def obj(fields: (String, ujson.Value)*): String =
    ujson.write(ujson.Obj.from(fields))

  private def writeLines(directory: Path, lines: Seq[String]): Unit =
    Files.createDirectories(directory)
    Files.writeString(
      directory.resolve("part-00000"),
      lines.mkString("\n") + "\n",
      UTF_8
    )

  private def optionalInt(value: Option[Int]): ujson.Value =
    value.fold(ujson.Null: ujson.Value)(v => ujson.Num(v))

  private def optionalString(value: Option[String]): ujson.Value =
    value.fold(ujson.Null: ujson.Value)(v => ujson.Str(v))

  /** Writes the directory trees and returns the input layout. With `defects`
    * the run also carries a mention with no link, a link to an unknown
    * canonical researcher and a mention of an unknown work, plus a pair that
    * touches the dropped mention.
    */
  def write(root: Path, defects: Boolean): InputLayout =
    val er = root
      .resolve("er")
      .resolve(s"run=${erRunId.replace(':', '-')}")
      .resolve(s"snapshot=$snapshot")
    val hadoop = root
      .resolve("hadoop")
      .resolve(s"run=${hadoopRunId.replace(':', '-')}")
      .resolve(s"snapshot=$snapshot")
    val layout = InputLayout(er, hadoop)
    val unknownWork = StableIds.work("dblp", "W9")
    val orphanWork = StableIds.work("dblp", "ORPHAN")

    val mentionRows = for
      work <- works
      (author, index) <- work.authors.zipWithIndex
    yield (work, index + 1, author)

    writeLines(
      layout.works,
      works.map { w =>
        obj(
          "workId" -> workId(w.key),
          "source" -> (if w.key == "W2b" then "crossref" else "dblp"),
          "sourceRecordId" -> w.key,
          "title" -> s"Paper ${w.key}",
          "year" -> optionalInt(w.year),
          "venueId" -> optionalString(w.venue.map(venueId)),
          "doi" -> s"10.1/${w.key}"
        )
      }
    )
    val mentionLines = mentionRows.map { (w, position, author) =>
      obj(
        "mentionId" -> mentionId(w.key, position),
        "workId" -> workId(w.key),
        "name" -> author.name,
        "normalizedName" -> author.name.toLowerCase
      )
    }
    val extraMentions =
      if defects then
        Vector(
          obj(
            "mentionId" -> mentionId("W1", 4),
            "workId" -> workId("W1"),
            "name" -> "Nolink N",
            "normalizedName" -> "nolink n"
          )
        )
      else Vector.empty
    writeLines(layout.authorMentions, mentionLines ++ extraMentions)

    val paperLines = mentionRows.map { (w, position, _) =>
      obj(
        "workId" -> workId(w.key),
        "mentionId" -> mentionId(w.key, position),
        "authorPosition" -> position,
        "year" -> optionalInt(w.year)
      )
    }
    val extraPapers =
      if defects then
        Vector(
          obj(
            "workId" -> workId("W1"),
            "mentionId" -> mentionId("W1", 4),
            "authorPosition" -> 4,
            "year" -> 2001
          ),
          obj(
            "workId" -> workId("W2"),
            "mentionId" -> mentionId("W2", 3),
            "authorPosition" -> 3,
            "year" -> 2002
          ),
          obj(
            "workId" -> unknownWork,
            "mentionId" -> StableIds.mention(unknownWork, 1),
            "authorPosition" -> 1,
            "year" -> 2003
          )
        )
      else Vector.empty
    writeLines(layout.authorPapers, paperLines ++ extraPapers)

    def pair(
        a: String,
        b: String,
        pa: Int,
        pb: Int,
        w: String,
        y: Option[Int]
    ) =
      val (x, z) = if a < b then (a, b) else (b, a)
      val (px, pz) = if a < b then (pa, pb) else (pb, pa)
      obj(
        "workId" -> w,
        "mentionA" -> x,
        "mentionB" -> z,
        "authorPositionA" -> px,
        "authorPositionB" -> pz,
        "year" -> optionalInt(y)
      )
    val pairLines = works.flatMap { w =>
      for
        i <- 1 to w.authors.size
        j <- (i + 1) to w.authors.size
      yield pair(
        mentionId(w.key, i),
        mentionId(w.key, j),
        i,
        j,
        workId(w.key),
        w.year
      )
    }
    val extraPairs =
      if defects then
        Vector(
          pair(
            mentionId("W2", 1),
            mentionId("W2", 3),
            1,
            3,
            workId("W2"),
            Some(2002)
          )
        )
      else Vector.empty
    writeLines(layout.pairCandidates, pairLines ++ extraPairs)

    writeLines(
      layout.teams,
      works.map { w =>
        obj(
          "workId" -> workId(w.key),
          "year" -> optionalInt(w.year),
          "orderedMentionIds" -> ujson.Arr.from(
            w.authors.indices.map(i => ujson.Str(mentionId(w.key, i + 1)))
          )
        )
      }
    )

    val linkLines = mentionRows.map { (w, position, author) =>
      val id = mentionId(w.key, position)
      obj(
        "mentionId" -> id,
        "workId" -> workId(w.key),
        "canonicalResearcherId" ->
          optionalString(author.researcher.map(researcherIds)),
        "status" -> author.status,
        "idRule" -> optionalString(author.researcher.map(_ => "orcid")),
        "clusterAnchor" -> id,
        "clusterSize" -> 1
      )
    }
    def link(mention: String, work: String, researcher: String): String =
      obj(
        "mentionId" -> mention,
        "workId" -> work,
        "canonicalResearcherId" -> researcher,
        "status" -> "merged",
        "idRule" -> "orcid",
        "clusterAnchor" -> mention,
        "clusterSize" -> 1
      )
    val extraLinks =
      if defects then
        Vector(
          link(
            mentionId("W2", 3),
            workId("W2"),
            ghostResearcher
          ),
          link(
            StableIds.mention(unknownWork, 1),
            unknownWork,
            researcherIds("A")
          )
        )
      else Vector.empty
    writeLines(layout.researcherLinks, linkLines ++ extraLinks)

    val workLinkLines = works.map { w =>
      obj(
        "workId" -> workId(w.key),
        "canonicalWorkId" -> workId(w.canonical),
        "status" -> (if w.key.startsWith("W2") then "merged" else "unlinked"),
        "clusterAnchor" -> workId(w.canonical),
        "clusterSize" -> (if w.key.startsWith("W2") then 2 else 1)
      )
    }
    val orphanLink = obj(
      "workId" -> orphanWork,
      "canonicalWorkId" -> orphanWork,
      "status" -> "unlinked",
      "clusterAnchor" -> orphanWork,
      "clusterSize" -> 1
    )
    writeLines(layout.workLinks, workLinkLines :+ orphanLink)

    writeLines(
      layout.canonicalResearchers,
      orcids.toVector.sortBy(_._1).map { (key, orcid) =>
        obj(
          "canonicalResearcherId" -> researcherIds(key),
          "idRule" -> "orcid",
          "orcid" -> orcid,
          "status" -> "external-id",
          "memberCount" -> 1,
          "inResearchersExport" -> false
        )
      }
    )

    def provenance(source: String, record: String): ujson.Value =
      ujson.Obj(
        "source" -> source,
        "snapshotId" -> s"$source:2026-11-07",
        "sourceRecordId" -> record,
        "sourceUri" -> s"https://example.org/$record"
      )
    writeLines(
      layout.venues,
      Vector(
        obj(
          "venueId" -> venueId("V1"),
          "name" -> "Venue One",
          "issns" -> ujson.Arr("1234-5678"),
          "provenance" -> provenance("dblp", "W1")
        )
      )
    )
    writeLines(
      layout.topics,
      Vector(
        obj(
          "topicId" -> "topic:v1:openalex:T1",
          "name" -> "Topic One",
          "parentTopicId" -> ujson.Null,
          "provenance" -> provenance("openalex", "W1")
        )
      )
    )
    writeLines(
      layout.institutions,
      Vector(
        obj(
          "institutionId" -> "institution:v1:openalex:I1",
          "name" -> "Institute One",
          "countryCode" -> ujson.Null,
          "externalIds" -> ujson.Obj("ror" -> "r1"),
          "provenance" -> provenance("openalex", "W1")
        )
      )
    )

    Files.writeString(er.resolve("_SUCCESS"), "", UTF_8)
    Files.writeString(
      er.resolve("manifest.json"),
      ujson.write(
        ujson.Obj(
          "schemaVersion" -> 1,
          "runId" -> erRunId,
          "snapshot" -> snapshot,
          "policyVersion" -> policy
        ),
        indent = 2
      ),
      UTF_8
    )
    Files.writeString(
      hadoop.resolve("stage=team-expansion").resolve("_SUCCESS"),
      "",
      UTF_8
    )
    layout
