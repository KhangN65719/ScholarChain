package scholarchain.tests.analytics

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.HexFormat
import org.apache.spark.sql.Row

// Item 13: reproducibility. Run A (8 shuffle partitions, 20 sampled sources)
// and run A3 (3 partitions, 3 sources) read the same inputs. Their logical
// tables must be identical, except the sampled path length table, and a run
// directory is never silently overwritten.
class SparkReproducibilitySuite extends SparkBase:
  private def a: Output = done(Runs.a)
  private def a3: Output = done(Runs.a3)

  // Tables compared row for row, as sorted text, without the run ID.
  private val compared = Vector(
    "nodes-researcher",
    "nodes-work",
    "nodes-venue",
    "nodes-topic",
    "nodes-institution",
    "edges-authored",
    "edges-published-in",
    "edges-coauthored",
    "edge-support",
    "researcher-metrics",
    "component-membership",
    "components",
    "component-summary",
    "community-membership",
    "communities",
    "community-cross-edges",
    "bridge",
    "coreness",
    "path-evidence",
    "temporal-comparison",
    "temporal-window-summary",
    "semantic-summaries",
    "unresolved-mentions",
    "integrity-violations"
  )

  // Doubles are rounded: a sum over a different partition order may differ in
  // the last bit.
  private def canon(value: Any): String =
    value match
      case null     => "null"
      case row: Row =>
        val fields = row.schema.fieldNames.toVector.filter(_ != "runId")
        fields
          .map(f => s"$f=${canon(row.getAs[Any](f))}")
          .mkString("{", ",", "}")
      case seq: scala.collection.Seq[?] =>
        seq.map(canon).mkString("[", ",", "]")
      case d: java.lang.Double => f"$d%.9f"
      case other               => other.toString

  private def logical(rows: Vector[Rec]): Vector[String] =
    rows
      .map(r =>
        r.toVector.sortBy(_._1).map((k, v) => s"$k=${canon(v)}").mkString("|")
      )
      .sorted

  test("both runs succeed, with different run IDs and the same inputs") {
    assertEquals(Runs.a.code, 0, Runs.a.text)
    assertEquals(Runs.a3.code, 0, Runs.a3.text)
    // The configuration is part of the run identity.
    assertNotEquals(Runs.a.runId, Runs.a3.runId)
    val first = a.json("run-manifest.json")("inputs")("datasets")
    val second = a3.json("run-manifest.json")("inputs")("datasets")
    assertEquals(first, second, "the input digests are the same")
  }

  test("tables are identical across partition counts and sample sizes") {
    compared.foreach { name =>
      val left = logical(a.table(name))
      val right = logical(a3.table(name))
      assertEquals(left.size, right.size, s"$name row count")
      assertEquals(left, right, name)
    }
  }

  test("community labels and the tie-broken paths are the same") {
    // Seeded and independent of the partition count.
    val membership = (o: Output) =>
      o.table("community-membership")
        .map { r =>
          (r.text("window"), r.text("researcherId"), r.text("communityId"))
        }
        .toSet
    assertEquals(membership(a), membership(a3))
    val pa = a.json("run-manifest.json")("algorithms")("communities")
    val pb = a3.json("run-manifest.json")("algorithms")("communities")
    assertEquals(pa("perWindow"), pb("perWindow"))
    val diamond = Worlds.pathPairsA.filter(_._2 == Worlds.a("ds"))
    assertEquals(diamond.size, 1)
    val key = s"${diamond.head._1}|${diamond.head._2}|${diamond.head._3}"
    val paths = (o: Output) =>
      where(o.table("path-evidence"), "pairKey" -> key)
        .map(r => (r.long("hopIndex"), r.text("fromId"), r.text("toId")))
        .sorted
    assertEquals(paths(a), paths(a3))
  }

  test("a sample smaller than the component gives bounded, exact-form values") {
    val rows = a3.table("path-length-estimates")
    // Window 2000-2004 samples 3 of the 9 nodes of the Wide component. A
    // Wide source has distances summing to 8, a coauthor source 12, and at
    // most one source is Wide: the 24 pairs sum to 32 or 36.
    Vector("2000-2004", "all").foreach { window =>
      val row = only(rows, "window" -> window)
      assertEquals(row.long("sampledSources"), 3L, window)
      assert(!row.flag("allNodesSampled"), window)
      assertEquals(row.long("pairsMeasured"), 24L, window)
      val average = row.num("averagePathLength")
      assert(
        Math.abs(average - 32.0 / 24) < 1e-9 || Math.abs(
          average - 36.0 / 24
        ) < 1e-9,
        s"$window average $average"
      )
      assertEquals(row.long("effectiveDiameter90"), 2L, window)
      assertEquals(row.long("unreachedPairsDueToDepthCap"), 0L, window)
    }
    // Window 2003-2006 samples 3 of the 4 nodes of a K4: 9 pairs at distance 1.
    val k4 = only(rows, "window" -> "2003-2006")
    assertEquals(k4.long("sampledSources"), 3L)
    assert(!k4.flag("allNodesSampled"))
    assertEquals(k4.long("pairsMeasured"), 9L)
    close(k4.num("averagePathLength"), 1.0, "k4")
  }

  private def digest(path: Path): String =
    val sha = MessageDigest.getInstance("SHA-256")
    HexFormat.of().formatHex(sha.digest(Files.readAllBytes(path)))

  test(
    "identical inputs and options name the same run and are not overwritten"
  ) {
    val before = digest(Runs.a.runDir.resolve("run-manifest.json"))
    val again = Harness.refuse(Runs.argsA(Runs.outA, 8, 20))
    // Same inputs and configuration give the same directory name, which exists.
    assertEquals(again.code, 2, again.text)
    assert(again.text.contains(Runs.a.runDir.getFileName.toString), again.text)
    assert(again.text.contains("--overwrite"), again.text)
    assertEquals(digest(Runs.a.runDir.resolve("run-manifest.json")), before)
    assert(Files.isRegularFile(Runs.a.runDir.resolve("_SUCCESS")))
  }
