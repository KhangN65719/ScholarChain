package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scholarchain.core.{
  ContractSchema,
  Provenance,
  SourceType,
  StableIds,
  YearWindow
}
import scholarchain.storage.*

/** Spark-shaped tables built only through core `StableIds`, plus run
  * directories on disk, for the independent Phase 7 storage suites.
  */
object Phase7Storage:
  val version = ContractSchema.currentVersion
  val runId = "run:v1:123e4567-e89b-42d3-a456-426614174000"
  val otherRun = "run:v1:223e4567-e89b-42d3-a456-426614174001"
  val policy = "scholarchain.spark.coauthor-policy/1"
  val window = YearWindow(2019, 2023)
  val windowLabel = "2019-2023"

  def s(v: String): ujson.Value = ujson.Str(v)
  def n(v: Long): ujson.Value = ujson.Num(v.toDouble)
  def arr(vs: String*): ujson.Value = ujson.Arr(vs.map(ujson.Str(_))*)

  def provenance(
      run: String = runId,
      snapshot: String = "snap-1",
      record: String = "rec/1",
      transform: String = "scholarchain.spark/1"
  ): Provenance =
    Provenance(
      version,
      SourceType.Dblp,
      snapshot,
      record,
      "https://example.invalid/x",
      transform,
      run
    )

  def provJson(p: Provenance): ujson.Value = upickle.default.writeJs(p)

  val w1 = StableIds.work("dblp", "rec/1")
  val w2 = StableIds.work("dblp", "rec/2")
  val w3 = StableIds.work("dblp", "rec/3")
  val m1 = StableIds.mention(w1, 1)
  val m2 = StableIds.mention(w1, 2)
  val r1 = StableIds.researcherFromExternal("openalex", "A1")
  val r2 = StableIds.researcherFromExternal("openalex", "A2")
  val venue = "venue:dblp:vldb"

  final case class World(tables: Map[String, Vector[Row]]):
    def source: InMemoryTableSource = new InMemoryTableSource(tables)
    def replace(table: String, rows: Vector[Row]): World =
      World(tables.updated(table, rows))
    def rows(table: String): Vector[Row] = tables(table)

  def researcherRow(id: String, name: String, p: Provenance): Row =
    Map(
      "nodeId" -> s(id),
      "displayName" -> s(name),
      "provenance" -> provJson(p)
    )

  def workRow(id: String, year: Int, title: String, p: Provenance): Row =
    Map(
      "canonicalWorkId" -> s(id),
      "title" -> s(title),
      "year" -> n(year),
      "venueId" -> s(venue),
      "provenance" -> provJson(p)
    )

  def coauthorRow(
      a: String,
      b: String,
      count: Long,
      first: Int,
      last: Int,
      reps: Seq[String],
      p: Provenance,
      pol: String = policy,
      win: YearWindow = window
  ): Row =
    val (x, y) = StableIds.orderedPair(a, b)
    Map(
      "edgeId" -> s(StableIds.coauthorEdgeFor(x, y, win, pol)),
      "researcherA" -> s(x),
      "researcherB" -> s(y),
      "windowStart" -> n(win.startYear),
      "windowEnd" -> n(win.endYear),
      "window" -> s(s"${win.startYear}-${win.endYear}"),
      "policyVersion" -> s(pol),
      "jointPublicationCount" -> n(count),
      "firstCollaborationYear" -> n(first),
      "lastCollaborationYear" -> n(last),
      "representativeWorkIds" -> arr(reps*),
      "provenance" -> provJson(p)
    )

  def authoredRow(
      who: String,
      work: String,
      mention: String,
      p: Provenance,
      counted: Boolean = true
  ): Row =
    Map(
      "researcherNodeId" -> s(who),
      "canonicalWorkId" -> s(work),
      "mentionId" -> s(mention),
      "countedInMetrics" -> ujson.Bool(counted),
      "provenance" -> provJson(p)
    )

  def supportRow(edge: Row, work: String, year: Int): Row =
    Map(
      "edgeId" -> edge("edgeId"),
      "researcherA" -> edge("researcherA"),
      "researcherB" -> edge("researcherB"),
      "canonicalWorkId" -> s(work),
      "year" -> n(year)
    )

  def world(p: Provenance = provenance()): World =
    val mm = coauthorRow(m1, m2, 1, 2020, 2020, Seq(w1), p)
    val rr = coauthorRow(r1, r2, 2, 2020, 2021, Seq(w2, w3), p)
    val mr = coauthorRow(m2, r1, 1, 2020, 2020, Seq(w1), p)
    World(
      Map(
        "nodes-researcher" -> Vector(
          researcherRow(m1, "Mention One", p),
          researcherRow(m2, "Mention Two", p),
          researcherRow(r1, "Researcher One", p),
          researcherRow(r2, "Researcher Two", p)
        ),
        "nodes-work" -> Vector(
          workRow(w1, 2020, "Work one", p),
          workRow(w2, 2020, "Work two", p),
          workRow(w3, 2021, "Work three", p)
        ),
        "nodes-venue" -> Vector(
          Map(
            "venueId" -> s(venue),
            "name" -> s("VLDB"),
            "provenance" -> provJson(p)
          )
        ),
        "nodes-topic" -> Vector.empty,
        "nodes-institution" -> Vector.empty,
        // Every endpoint authored every work cited in edge-support, counted in
        // metrics: (r1,w2) (r2,w2) (r1,w3) (r2,w3) for the researcher pair,
        // (m1,w1) (m2,w1) for the mention pair, (m2,w1) (r1,w1) for the mixed.
        "edges-authored" -> Vector(
          authoredRow(r1, w2, StableIds.mention(w2, 1), p),
          authoredRow(r2, w2, StableIds.mention(w2, 2), p),
          authoredRow(r1, w3, StableIds.mention(w3, 1), p),
          authoredRow(r2, w3, StableIds.mention(w3, 2), p),
          authoredRow(r1, w1, StableIds.mention(w1, 3), p),
          Map(
            "researcherNodeId" -> s(m1),
            "canonicalWorkId" -> s(w1),
            "mentionId" -> s(m1),
            "provenance" -> provJson(p)
          ),
          Map(
            "researcherNodeId" -> s(m2),
            "canonicalWorkId" -> s(w1),
            "mentionId" -> s(m2),
            "provenance" -> provJson(p)
          )
        ),
        "edges-published-in" -> Vector(
          Map(
            "fromId" -> s(w1),
            "toId" -> s(venue),
            "provenance" -> provJson(p)
          )
        ),
        "edges-coauthored" -> Vector(mm, rr, mr),
        "edge-support" -> Vector(
          supportRow(mm, w1, 2020),
          supportRow(rr, w2, 2020),
          supportRow(rr, w3, 2021),
          supportRow(mr, w1, 2020)
        )
      )
    )

  val Bounds: LoadBounds = LoadBounds.Default

  /** Uses the projector's default scope (Extended) unless one is named. */
  def project(w: World, bounds: LoadBounds = Bounds): GraphProjection =
    new SparkGraphProjector(w.source, bounds).project()

  def projectIn(
      w: World,
      scope: GraphScope,
      bounds: LoadBounds = Bounds
  ): GraphProjection =
    new SparkGraphProjector(w.source, bounds, scope).project()

  def code(body: => Any): ErrorCode =
    try
      body
      throw new AssertionError(
        "expected a StorageException, nothing was thrown"
      )
    catch case e: StorageException => e.code

  def utf8(text: String): Array[Byte] = text.getBytes(UTF_8)

  /** Writes a Spark run directory. `tamper` replaces the on-disk bytes of a
    * relative path after its checksum was recorded; `declaredBytes` replaces
    * the size the manifest declares.
    */
  def writeRun(
      root: Path,
      run: String,
      files: Vector[(String, String, Array[Byte])],
      success: Boolean = true,
      side: Map[String, Array[Byte]] = Map.empty,
      tamper: Map[String, Array[Byte]] = Map.empty,
      declaredBytes: Option[Long] = None,
      manifest: Boolean = true
  ): Path =
    val dir = Files.createDirectories(root.resolve("rundir"))
    val tables =
      files.groupBy(_._1).toVector.sortBy(_._1).map { case (table, entries) =>
        ujson.Obj(
          "table" -> table,
          "files" -> ujson.Arr(entries.map { case (_, name, bytes) =>
            ujson.Obj(
              "path" -> s"tables/$table/$name",
              "bytes" -> declaredBytes.getOrElse(bytes.length.toLong).toDouble,
              "sha256" -> Hashing.sha256(bytes)
            )
          }*)
        )
      }
    files.foreach { case (table, name, bytes) =>
      val rel = s"tables/$table/$name"
      val target = dir.resolve(rel)
      Files.createDirectories(target.getParent)
      Files.write(target, tamper.getOrElse(rel, bytes))
    }
    side.foreach { case (name, bytes) =>
      Files.write(dir.resolve(name), bytes)
    }
    if manifest then
      Files.createDirectories(dir.resolve("load-manifests"))
      val body = ujson.Obj(
        "runId" -> run,
        "tables" -> ujson.Arr(tables*)
      )
      Files.write(
        dir.resolve("load-manifests/s3.json"),
        utf8(ujson.write(body))
      )
    if success then Files.write(dir.resolve("_SUCCESS"), Array.emptyByteArray)
    dir

  def tempRoot(): Path = Files.createTempDirectory("p7-storage")
