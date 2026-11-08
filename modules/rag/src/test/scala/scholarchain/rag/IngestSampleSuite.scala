package scholarchain.rag

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scholarchain.storage.*

/** Work order 11 (finding N6): the ingest sample query is the FULL rendered
  * text of the source row, passes only when the source row is in the top-score
  * group, and reports the size of that group.
  */
class IngestSampleSuite extends munit.FunSuite:
  import RagFixtures.*

  private val kind = "collaboration-path"
  private val index = "collaboration-path-v1"
  private val early = Some(("2000-2009", 2000, 2009))

  // Twelve opening words and more, identical in every path summary.
  private val shared =
    "In window 2000-2009 the shortest collaboration path in the graph of " +
      "the research community has"

  private def path(id: String, subject: String, tail: String): Row =
    row(
      id,
      kind,
      subject,
      s"$shared $tail",
      early,
      None,
      Vector(w1),
      ujson.Arr("pathLength", "jointPublicationCount"),
      Vector(ada, chidi)
    )

  private val longTail =
    (1 to 60).map(i => s"unique$i").mkString(" ") + " hops in total."

  /** The source row (x1, first in export order) has a long tail of its own
    * words, the others a short one: the 12-word prefix is nearer to the short
    * ones.
    */
  private val templated = Vector(
    path("x1", "pair:one", longTail),
    path("x2", "pair:two", "2 hops."),
    path("x3", "pair:three", "3 hops.")
  )

  /** x1 and x2 differ only in their summary ID: same rendered text. */
  private val duplicated = Vector(
    path("x1", "pair:one", "2 hops between them."),
    path("x2", "pair:one", "2 hops between them."),
    path("x3", "pair:three", "3 hops.")
  )

  private val sourceKey = id("x1")

  private def tmp(): Path = Files.createTempDirectory("rag-sample-")

  private def oldQuery(r: Row): String =
    Rows.str(r, "summaryText").split("\\s+").take(12).mkString(" ")

  private def run(
      rows: Vector[Row],
      outDir: Path = tmp().resolve("out"),
      tamper: VectorBucketPort => VectorBucketPort = identity
  ): (IngestMain.Outcome, Vector[String], Path) =
    val lines = mutable.ArrayBuffer.empty[String]
    val exportDir = writeExport(tmp(), tables(rows))
    val outcome = IngestMain.execute(
      IngestMain.Args(exportDir, outDir, None),
      lines += _,
      tamper
    )
    (outcome, lines.toVector, outDir)

  private def report(outDir: Path): ujson.Value =
    ujson.read(Files.readString(outDir.resolve(IngestMain.ReportName)))

  private def pathSample(json: ujson.Value): ujson.Value =
    json("samples").arr.find(_("index").str == index).get

  /** Changes what the sample queries see of the path index, not what was
    * ingested.
    */
  private final class Tampered(
      inner: VectorBucketPort,
      change: Vector[VectorMatch] => Vector[VectorMatch]
  ) extends VectorBucketPort:
    def ensureIndex(spec: VectorIndexSpec): Unit = inner.ensureIndex(spec)
    def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
      inner.putVectors(index, batch)
    def getVectors(index: String, keys: Vector[String]): Vector[VectorRecord] =
      inner.getVectors(index, keys)
    def count(index: String): Int = inner.count(index)
    def query(request: VectorQuery): Vector[VectorMatch] =
      change(inner.query(request))

  private def dropSource(inner: VectorBucketPort): VectorBucketPort =
    new Tampered(inner, _.filterNot(_.key == sourceKey))

  private def alterSource(inner: VectorBucketPort): VectorBucketPort =
    new Tampered(
      inner,
      _.map(m =>
        if m.key == sourceKey then m.copy(distance = m.distance + 0.5) else m
      ).sortBy(m => (m.distance, m.key))
    )

  test(
    "regression: the 12-word query misses the source row, full text hits it"
  ) {
    assertEquals(templated.map(oldQuery).distinct.size, 1)
    assert(oldQuery(templated.head).split(" ").length == 12)
    val env = new Env
    env.run(templated)
    val filter = VectorFilter(documentType = Some(kind))
    val old = env.retrieval.search(index, oldQuery(templated.head), 3, filter)
    assertNotEquals(old.head.hit.key, sourceKey, "precondition: old query")
    val full = env.retrieval
      .search(index, IngestMain.queryText(templated.head), 3, filter)
    assertEquals(full.head.hit.key, sourceKey)
    assert(full.head.hit.distance < 1e-6, full.head.hit.distance.toString)
    assert(full(1).hit.distance > 1e-3)
  }

  test("templated path summaries pass the run, tie size 1, in the report") {
    val (outcome, lines, outDir) = run(templated)
    assert(outcome.success, lines.mkString("\n"))
    val sample = pathSample(report(outDir))
    assertEquals(sample("queryKind").str, "full-rendered-text")
    assertEquals(
      sample("queryChars").num.toInt,
      IngestMain.queryText(templated.head).length
    )
    Vector("narrow", "wide").foreach { which =>
      assertEquals(sample(which)("tieSize").num.toInt, 1)
      assertEquals(sample(which)("sourceRank").num.toInt, 1)
      assertEquals(sample(which)("nearest").bool, true)
    }
    val narrow = lines.find(_.startsWith(s"SAMPLE $index:")).get
    assert(narrow.contains("nearest is the source row: true"), narrow)
    assert(narrow.contains("top-score tie size 1"), narrow)
  }

  test("an exact-duplicate text pair is a tie of size 2 and passes") {
    val (outcome, lines, outDir) = run(duplicated)
    assert(outcome.success, lines.mkString("\n"))
    val sample = pathSample(report(outDir))
    Vector("narrow", "wide").foreach { which =>
      assertEquals(sample(which)("tieSize").num.toInt, 2, which)
      assertEquals(sample(which)("nearest").bool, true)
    }
    assert(
      lines.exists(l =>
        l.startsWith(s"SAMPLE $index:") && l.contains("top-score tie size 2")
      ),
      lines.mkString("\n")
    )
    assert(
      lines.exists(l =>
        l.startsWith(s"WIDE-SAMPLE $index:") &&
          l.contains("top-score tie size 2") &&
          l.contains("in the top-score group: true")
      ),
      lines.mkString("\n")
    )
  }

  test("the report with a tie is deterministic on a rerun") {
    val exportDir = writeExport(tmp(), tables(duplicated))
    val outDir = tmp().resolve("out")
    val go = () =>
      IngestMain.execute(IngestMain.Args(exportDir, outDir, None), _ => ())
    assert(go().success)
    val before = Files.readAllBytes(outDir.resolve(IngestMain.ReportName))
    assert(go().success)
    assertEquals(
      Files.readAllBytes(outDir.resolve(IngestMain.ReportName)).toVector,
      before.toVector
    )
  }

  test("a source row missing from the index FAILS the run") {
    val (outcome, lines, outDir) = run(templated, tamper = dropSource)
    assert(!outcome.success)
    assertEquals(
      lines.find(_.startsWith(IngestMain.ResultPrefix)).get,
      "PHASE8-INGEST RESULT: FAILED a sample query failed"
    )
    val sample = pathSample(report(outDir))
    assertEquals(sample("narrow")("nearest").bool, false)
    assertEquals(sample("narrow")("sourceRank").num.toInt, 0)
    assertEquals(sample("wide")("nearest").bool, false)
    assertEquals(report(outDir)("result").str, "FAILED")
  }

  test("a source row whose vector is altered FAILS the run") {
    val (outcome, lines, outDir) = run(templated, tamper = alterSource)
    assert(!outcome.success)
    assert(lines.exists(_.contains("nearest is the source row: false")))
    val sample = pathSample(report(outDir))
    assertEquals(sample("narrow")("nearest").bool, false)
    assertEquals(sample("wide")("nearest").bool, false)
  }

  test("a tie that does not include the source row FAILS the run") {
    val (outcome, lines, outDir) = run(duplicated, tamper = alterSource)
    assert(!outcome.success, lines.mkString("\n"))
    val sample = pathSample(report(outDir))
    // the twin x2 is alone at the best score; the source row ranks below it
    assertEquals(sample("narrow")("tieSize").num.toInt, 1)
    assert(sample("narrow")("sourceRank").num.toInt >= 2)
    assertEquals(sample("narrow")("nearest").bool, false)
    assertEquals(sample("wide")("nearest").bool, false)
  }

  test("a FAILED sample run can be rerun into the same directory") {
    val exportDir = writeExport(tmp(), tables(templated))
    val outDir = tmp().resolve("out")
    val failed = IngestMain.execute(
      IngestMain.Args(exportDir, outDir, None),
      _ => (),
      dropSource
    )
    assert(!failed.success)
    assertEquals(report(outDir)("result").str, "FAILED")
    val again =
      IngestMain.execute(IngestMain.Args(exportDir, outDir, None), _ => ())
    assert(again.success, again.reason)
    assertEquals(report(outDir)("result").str, "SUCCESS")
  }

  test("the printed query line is a labelled display prefix") {
    val (outcome, lines, _) = run(templated)
    assert(outcome.success)
    val full = IngestMain.queryText(templated.head)
    val line = lines.dropWhile(!_.startsWith(s"SAMPLE $index:"))(1)
    assertEquals(line, s"  query: ${IngestMain.displayPrefix(full)}")
    assert(line.contains("display prefix (first 12 words of the"), line)
    assert(line.contains(s"${full.length}-character rendered text"), line)
    val shown = line.substring(line.indexOf("used): ") + "used): ".length)
    assert(shown.split("\\s+").length <= IngestMain.DisplayWords)
    assert(full.startsWith(shown.split("\\s+").head))
    assert(line.length < full.length)
  }

  test("standing: the top-score group is the prefix at the best distance") {
    val env = new Env
    env.run(duplicated)
    val hits = env.retrieval.search(
      index,
      IngestMain.queryText(duplicated.head),
      IngestMain.MaxHits,
      VectorFilter(documentType = Some(kind))
    )
    val st = IngestMain.standing(hits, sourceKey)
    assertEquals(st.tieSize, 2)
    assertEquals(st.sourceRank, 1)
    assert(st.nearest)
    val third = IngestMain.standing(hits, id("x3"))
    assertEquals(third.sourceRank, 3)
    assert(!third.nearest)
    assert(!IngestMain.standing(hits, "summary:v1:absent").nearest)
    assert(!IngestMain.standing(Vector.empty, sourceKey).nearest)
  }
