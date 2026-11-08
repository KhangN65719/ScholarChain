package scholarchain.rag

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scholarchain.core.StableIds
import scholarchain.storage.*

/** A hand-built Spark-shaped world with clearly distinct vocabulary: three
  * researchers and a fourth, three works, three communities, two paths. The
  * summary texts follow the Spark templates of `Summaries.scala` (names, titles
  * and numbers come from computed columns).
  */
object RagFixtures:
  val runId = "run:v1:22222222-2222-4222-8222-222222222222"
  val transformVersion = "spark-analytics/1.1.0"

  val ada: String = StableIds.researcherFromExternal("orcid", "0000-0001")
  val boris: String = StableIds.researcherFromExternal("orcid", "0000-0002")
  val chidi: String = StableIds.researcherFromExternal("orcid", "0000-0003")
  val dana: String = StableIds.researcherFromExternal("orcid", "0000-0004")
  val w1: String = StableIds.work("dblp", "conf/a/1")
  val w2: String = StableIds.work("dblp", "conf/a/2")
  val w3: String = StableIds.work("dblp", "conf/a/3")

  def provenance(table: String, record: String): ujson.Value =
    ujson.Obj(
      "schemaVersion" -> 1,
      "source" -> "derived",
      "snapshotId" -> "hadoop-snapshot:s1",
      "sourceRecordId" -> record,
      "sourceUri" -> s"scholarchain:spark-analytics/$table",
      "transformVersion" -> transformVersion,
      "runId" -> runId
    )

  private val profileMetrics = ujson.Arr(
    "publicationCount",
    "distinctCoauthorCount",
    "weightedDegree"
  )

  def row(
      id: String,
      kind: String,
      subject: String,
      text: String,
      window: Option[(String, Int, Int)],
      nodeKind: Option[String],
      works: Vector[String],
      metrics: ujson.Value,
      nodes: Vector[String]
  ): Row =
    Map(
      "summaryId" -> ujson.Str(s"summary:v1:$id"),
      "summaryType" -> ujson.Str(kind),
      "window" -> window.fold[ujson.Value](ujson.Str("not-windowed"))(w =>
        ujson.Str(w._1)
      ),
      "windowStart" -> window.fold[ujson.Value](ujson.Null)(w =>
        ujson.Num(w._2)
      ),
      "windowEnd" -> window.fold[ujson.Value](ujson.Null)(w => ujson.Num(w._3)),
      "subjectId" -> ujson.Str(subject),
      "nodeKind" -> nodeKind.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "summaryText" -> ujson.Str(text),
      "evidenceWorkIds" -> ujson.Arr.from(works.map(ujson.Str(_))),
      "evidenceMetrics" -> metrics,
      "evidenceNodeIds" -> ujson.Arr.from(nodes.map(ujson.Str(_))),
      "provenance" -> provenance("semantic-summaries", id)
    )

  private val early = Some(("2000-2009", 2000, 2009))
  private val late = Some(("2010-2019", 2010, 2019))

  private def profile(
      id: String,
      who: String,
      name: String,
      window: Some[(String, Int, Int)],
      papers: Int,
      work: String
  ): Row =
    row(
      id,
      "researcher-profile",
      who,
      s"Researcher $name in window ${window.value._1}: $papers publications, " +
        s"2 distinct coauthors, 4 coauthor occurrences, 1.50 average " +
        s"coauthors per paper, at most 3 coauthors on one paper (work " +
        s"$work), weighted degree 4, component of 5 nodes, community of 3 " +
        "nodes, coreness 2, participation coefficient 0.250. Nodes are " +
        "canonical researchers and unresolved author mentions.",
      window,
      Some("canonical"),
      Vector(work),
      profileMetrics,
      Vector(who)
    )

  val summaries: Vector[Row] = Vector(
    profile("p1", ada, "Ada Quillfeather", early, 7, w1),
    profile("p2", ada, "Ada Quillfeather", late, 9, w3),
    profile("p3", boris, "Boris Marlowe", early, 5, w2),
    profile("p4", chidi, "Chidi Okonkwo", early, 4, w2),
    row(
      "c1",
      "community",
      "community-1",
      "Community community-1 in window 2000-2009 has 6 nodes (canonical " +
        "researchers and unresolved author mentions), 8 internal " +
        "collaboration edges and 2 edges to other communities. Most " +
        "connected members by weighted degree: Ada Quillfeather; Boris " +
        "Marlowe.",
      early,
      None,
      Vector(w1),
      ujson.Arr("communitySize", "internalEdges"),
      Vector(ada, boris)
    ),
    row(
      "c2",
      "community",
      "community-2",
      "Community community-2 in window 2010-2019 has 4 nodes (canonical " +
        "researchers and unresolved author mentions), 3 internal " +
        "collaboration edges and 1 edges to other communities. Most " +
        "connected members by weighted degree: Ada Quillfeather; Dana " +
        "Whitcombe.",
      late,
      None,
      Vector(w3),
      ujson.Arr("communitySize", "internalEdges"),
      Vector(ada, dana)
    ),
    row(
      "c3",
      "community",
      "community-3",
      "Community community-3 in window 2000-2009 has 3 nodes (canonical " +
        "researchers and unresolved author mentions), 2 internal " +
        "collaboration edges and 1 edges to other communities. Most " +
        "connected members by weighted degree: Chidi Okonkwo.",
      early,
      None,
      Vector(w2),
      ujson.Arr("communitySize", "internalEdges"),
      Vector(chidi)
    ),
    row(
      "w1",
      "work",
      w1,
      s"Work Graph neural networks for citation analysis ($w1, year 2001) " +
        "has 3 authors, of which 0 are unresolved author mentions. Venue: " +
        "venue:dblp:conf-a. DOI: none recorded.",
      None,
      None,
      Vector(w1),
      ujson.Arr("teamSize", "unresolvedAuthors", "year"),
      Vector.empty
    ),
    row(
      "w2",
      "work",
      w2,
      s"Work Protein folding with molecular dynamics ($w2, year 2005) has " +
        "4 authors, of which 1 are unresolved author mentions. Venue: " +
        "venue:dblp:conf-a. DOI: none recorded.",
      None,
      None,
      Vector(w2),
      ujson.Arr("teamSize", "unresolvedAuthors", "year"),
      Vector.empty
    ),
    row(
      "w3",
      "work",
      w3,
      s"Work Quantum error correction on superconducting qubits ($w3, year " +
        "2012) has 6 authors, of which 0 are unresolved author mentions. " +
        "Venue: venue:dblp:conf-a. DOI: none recorded.",
      None,
      None,
      Vector(w3),
      ujson.Arr("teamSize", "unresolvedAuthors", "year"),
      Vector.empty
    ),
    row(
      "x1",
      "collaboration-path",
      "pair:ada-chidi",
      "In window 2000-2009 the shortest collaboration path from Ada " +
        "Quillfeather to Chidi Okonkwo has 2 hops: Ada Quillfeather and " +
        "Boris Marlowe share 3 joint publication(s) (works " +
        s"$w1); Boris Marlowe and Chidi Okonkwo share 1 joint " +
        s"publication(s) (works $w2).",
      early,
      None,
      Vector(w1, w2),
      ujson.Arr("pathLength", "jointPublicationCount"),
      Vector(ada, chidi)
    ),
    row(
      "x2",
      "collaboration-path",
      "pair:boris-dana",
      "In window 2010-2019 Boris Marlowe and Dana Whitcombe are in " +
        "different connected components of the collaboration graph, so no " +
        "collaboration path connects them.",
      late,
      None,
      Vector.empty,
      ujson.Arr("pathLength", "jointPublicationCount"),
      Vector(boris, dana)
    )
  )

  def id(short: String): String = s"summary:v1:$short"

  val nodesWork: Vector[Row] = Vector(w1 -> 2001, w2 -> 2005, w3 -> 2012).map {
    case (work, year) =>
      Map(
        "canonicalWorkId" -> ujson.Str(work),
        "title" -> ujson.Str(s"Title of $work"),
        "year" -> ujson.Num(year),
        "source" -> ujson.Str("dblp"),
        "provenance" -> provenance("nodes-work", work)
      )
  }

  private def metrics(who: String, window: String, community: String): Row =
    Map(
      "researcherId" -> ujson.Str(who),
      "window" -> ujson.Str(window),
      "communityId" -> ujson.Str(community),
      "provenance" -> provenance("researcher-metrics", s"$who|$window")
    )

  val researcherMetrics: Vector[Row] = Vector(
    metrics(ada, "2000-2009", "community-1"),
    metrics(ada, "2010-2019", "community-2"),
    metrics(boris, "2000-2009", "community-1"),
    metrics(chidi, "2000-2009", "community-3")
  )

  def tables(rows: Vector[Row] = summaries): Map[String, Vector[Row]] =
    Map(
      "semantic-summaries" -> rows,
      "nodes-work" -> nodesWork,
      "researcher-metrics" -> researcherMetrics
    )

  def source(rows: Vector[Row] = summaries): TableSource =
    new InMemoryTableSource(tables(rows))

  /** A hand-built JSON-lines export in the shape of the Spark export job:
    * `<table>/part-NNNNN.jsonl`, `export-manifest.json`, then `_SUCCESS`.
    */
  def writeExport(
      dir: Path,
      tableRows: Map[String, Vector[Row]] = tables(),
      rowsPerFile: Int = 4
  ): Path =
    val entries = tableRows.toVector.sortBy(_._1).map { case (name, rows) =>
      val chunks =
        if rows.isEmpty then Vector(Vector.empty[Row])
        else rows.grouped(rowsPerFile).toVector
      Files.createDirectories(dir.resolve(name))
      val files = chunks.zipWithIndex.map { case (chunk, index) =>
        val relative = f"$name/part-$index%05d.jsonl"
        val body = chunk
          .map(r => ujson.write(ujson.Obj.from(r)))
          .mkString("", "\n", if chunk.isEmpty then "" else "\n")
        val bytes = body.getBytes(UTF_8)
        Files.write(dir.resolve(relative), bytes)
        ujson.Obj(
          "path" -> relative,
          "rows" -> chunk.size,
          "bytes" -> bytes.length,
          "sha256" -> Hashing.sha256(bytes)
        )
      }
      name -> ujson.Obj(
        "rows" -> rows.size,
        "idColumns" -> ujson.Arr(),
        "columns" -> ujson.Arr(),
        "sourceFiles" -> ujson.Arr(),
        "files" -> ujson.Arr.from(files)
      )
    }
    val manifest = ujson.Obj(
      "manifestType" -> JsonlExportSource.ManifestType,
      "exportVersion" -> "spark-jsonl-export/1.0.0",
      "sourceRunId" -> runId,
      "sourceDirectory" -> s"run=${RunDirectory.segment(runId)}",
      "sourceSuccessSeen" -> true,
      "sourceRunManifestSha256" -> Hashing.sha256("run-manifest"),
      "transformVersion" -> transformVersion,
      "rowsPerFile" -> rowsPerFile,
      "tables" -> ujson.Obj.from(entries)
    )
    Files.writeString(
      dir.resolve(JsonlExportSource.ManifestName),
      ujson.write(manifest, indent = 2) + "\n"
    )
    Files.write(dir.resolve("_SUCCESS"), Array.emptyByteArray)
    dir

  /** Ingests `rows` into fresh local fakes with the rag templates. */
  final class Env(
      val bucket: InMemoryVectorBucket = new InMemoryVectorBucket,
      val store: ObjectStore = new InMemoryObjectStore
  ):
    val embedder = new HashEmbedder
    val ledger = new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    val ingest =
      new EmbeddingIngest(embedder, RagTemplates, bucket, store, ledger)
    val retrieval = new SemanticRetrieval(embedder, bucket, store)

    def run(
        rows: Vector[Row] = summaries,
        label: String = "1"
    ): IngestReport =
      ingest.run(
        source(rows),
        SourceRun(runId, transformVersion),
        IngestConfig("2026-11-08T00:00:00Z", executionLabel = label)
      )
