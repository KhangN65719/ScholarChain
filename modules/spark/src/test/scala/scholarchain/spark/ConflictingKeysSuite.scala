package scholarchain.spark

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scholarchain.spark.SyntheticRun.{researcherIds, workId, mentionId}

/** Pipeline-level runs over the synthetic world with one injected conflicting
  * key. The clean world has no repeated key, so every duplicate count below is
  * exactly the injected rows.
  */
class ConflictingKeysSuite extends SparkSuite:
  private final case class Outcome(code: Int, output: String, runDir: Path):
    def manifest: ujson.Value =
      ujson.read(Files.readString(runDir.resolve("run-manifest.json"), UTF_8))
    def counts: ujson.Value = manifest("counts")
    def invariant(name: String): ujson.Value =
      manifest("invariants").arr.find(_("name").str == name).get

  private def inject(
      directory: Path,
      lines: Vector[ujson.Obj]
  ): Unit =
    Files.writeString(
      directory.resolve("part-00001"),
      lines.map(ujson.write(_)).mkString("\n") + "\n",
      UTF_8
    )

  private def run(
      mutate: InputLayout => Unit
  ): Outcome =
    val root = Files.createTempDirectory("scholarchain-spark-conflict-")
    val layout = SyntheticRun.write(root.resolve("in"), defects = false)
    mutate(layout)
    val buffer = new ByteArrayOutputStream()
    val code = CollaborationPipeline.run(
      Vector(
        "--er-run",
        layout.erRun.toString,
        "--hadoop-root",
        layout.hadoopRoot.toString,
        "--out-root",
        root.resolve("out").toString,
        "--windows",
        "2000-2004,2005-2009,all",
        "--master",
        "local[2]",
        "--partitions",
        "4",
        "--max-iterations",
        "30",
        "--sample-size",
        "10",
        "--hop-limit",
        "4",
        "--paper-summaries",
        "2"
      ),
      new PrintStream(buffer, true, "UTF-8"),
      Some(session)
    )
    val out = root.resolve("out")
    val runs = Files.list(out).iterator.asScala.toVector
    Outcome(code, buffer.toString("UTF-8"), runs.head)

  private val datasets = Vector(
    "researcherLinks",
    "workLinks",
    "canonicalResearchers",
    "works",
    "mentions",
    "authorPapers",
    "pairCandidates"
  )

  private def assertDuplicates(
      outcome: Outcome,
      expected: Map[String, Long]
  ): Unit =
    datasets.foreach { name =>
      val n = expected.getOrElse(name, 0L)
      assertEquals(
        outcome.counts(s"input.duplicateKeyRows.$name").num.toLong,
        n,
        s"duplicateKeyRows $name"
      )
      assertEquals(
        outcome.counts(s"input.conflictingDuplicateKeys.$name").num.toLong,
        n,
        s"conflictingDuplicateKeys $name"
      )
    }

  // The injected works row repeats the key of W1 and changes only the title.
  // Hand derivation: the clean world has 8 works rows, so the raw count is 9;
  // key W1 has 2 rows (1 beyond the first, 2 distinct contents): works has
  // duplicateKeyRows 1 and conflictingDuplicateKeys 1; every other dataset has
  // no repeated key, so 0 and 0. The warning invariant is the sum over the four
  // Hadoop datasets = 1 and the identity invariant is 0. The smallest row by
  // struct order keeps "Paper W1", so the canonical works stay 7.
  private lazy val hadoopConflict = run { layout =>
    inject(
      layout.works,
      Vector(
        ujson.Obj(
          "workId" -> workId("W1"),
          "source" -> "dblp",
          "sourceRecordId" -> "W1",
          "title" -> "Paper W1 (variant)",
          "year" -> 2001,
          "venueId" -> "venue:v1:dblp:V1",
          "doi" -> "10.1/W1"
        )
      )
    )
  }

  test("a conflicting key in a Hadoop input is counted, warned and not fatal") {
    val o = hadoopConflict
    assertEquals(o.code, 0, o.output.slice(0, 3000))
    assertEquals(
      o.output.trim.linesIterator.toVector.last,
      "SPARK-PIPELINE RESULT: SUCCESS"
    )
    assert(Files.exists(o.runDir.resolve("_SUCCESS")))
    assertEquals(o.counts("input.works").num.toLong, 9L)
    assertDuplicates(o, Map("works" -> 1L))
    assertEquals(o.counts("canonical.works").num.toLong, 7L)
    val warning = o.invariant("conflictingDuplicateInputKeys")
    assertEquals(warning("status").str, "warn")
    assertEquals(warning("value").num.toLong, 1L)
    assert(warning("detail").str.contains("(works=1)"), warning("detail").str)
    val identity = o.invariant("conflictingIdentityInputKeys")
    assertEquals(identity("status").str, "pass")
    assertEquals(identity("value").num.toLong, 0L)
  }

  // The injected link repeats mention W5#2 (researcher E in the clean world)
  // and names researcher F. Hand derivation: the key mentionId has 2 rows with
  // 2 distinct canonicalResearcherId values, so researcherLinks has
  // duplicateKeyRows 1 and conflictingDuplicateKeys 1, the identity invariant
  // is 1 with status fail and the run ends with exit code 1. No Hadoop dataset
  // repeats a key, so the warning invariant is 0.
  private lazy val linkConflict = run { layout =>
    inject(
      layout.researcherLinks,
      Vector(
        ujson.Obj(
          "mentionId" -> mentionId("W5", 2),
          "workId" -> workId("W5"),
          "canonicalResearcherId" -> researcherIds("F"),
          "status" -> "merged",
          "idRule" -> "orcid",
          "clusterAnchor" -> mentionId("W5", 2),
          "clusterSize" -> 1
        )
      )
    )
  }

  test("a conflicting canonicalResearcherId in a link input fails the run") {
    val o = linkConflict
    assertEquals(o.code, 1, o.output.slice(0, 3000))
    assert(
      o.output.contains(
        "SPARK-PIPELINE RESULT: FAILED invariants conflictingIdentityInputKeys"
      ),
      o.output.slice(0, 3000)
    )
    assert(!Files.exists(o.runDir.resolve("_SUCCESS")))
    assertDuplicates(o, Map("researcherLinks" -> 1L))
    val identity = o.invariant("conflictingIdentityInputKeys")
    assertEquals(identity("status").str, "fail")
    assertEquals(identity("value").num.toLong, 1L)
    assert(
      identity("detail").str.contains("(researcherLinks=1)"),
      identity("detail").str
    )
    assertEquals(
      o.invariant("conflictingDuplicateInputKeys")("status").str,
      "pass"
    )
    val failed = o.manifest("invariants").arr.filter(_("status").str == "fail")
    assertEquals(
      failed.map(_("name").str).toVector,
      Vector("conflictingIdentityInputKeys")
    )
  }

  // One conflict in each of the other two identity inputs. Hand derivation:
  // the work link of W5 is repeated with canonical work W4 instead of W5
  // (key workId, 2 distinct rows: 1 and 1), and the canonical researcher A is
  // repeated with memberCount 2 instead of 1 (key canonicalResearcherId, 2
  // distinct rows: 1 and 1). The identity invariant is 1 + 1 = 2, status fail,
  // exit code 1; the Hadoop warning stays 0.
  private lazy val otherIdentityConflicts = run { layout =>
    inject(
      layout.workLinks,
      Vector(
        ujson.Obj(
          "workId" -> workId("W5"),
          "canonicalWorkId" -> workId("W4"),
          "status" -> "unlinked",
          "clusterAnchor" -> workId("W4"),
          "clusterSize" -> 1
        )
      )
    )
    inject(
      layout.canonicalResearchers,
      Vector(
        ujson.Obj(
          "canonicalResearcherId" -> researcherIds("A"),
          "idRule" -> "orcid",
          "orcid" -> SyntheticRun.orcids("A"),
          "status" -> "external-id",
          "memberCount" -> 2,
          "inResearchersExport" -> false
        )
      )
    )
  }

  test("conflicts in work links and canonical researchers fail the run") {
    val o = otherIdentityConflicts
    assertEquals(o.code, 1, o.output.slice(0, 3000))
    assertDuplicates(
      o,
      Map("workLinks" -> 1L, "canonicalResearchers" -> 1L)
    )
    val identity = o.invariant("conflictingIdentityInputKeys")
    assertEquals(identity("status").str, "fail")
    assertEquals(identity("value").num.toLong, 2L)
    val detail = identity("detail").str
    assert(detail.contains("workLinks=1"), detail)
    assert(detail.contains("canonicalResearchers=1"), detail)
    assertEquals(
      o.invariant("conflictingDuplicateInputKeys")("status").str,
      "pass"
    )
  }

  test("an exact repeat is counted but is neither a warning nor an error") {
    val o = run { layout =>
      val first = Files
        .readAllLines(layout.researcherLinks.resolve("part-00000"), UTF_8)
        .asScala
        .head
      Files.writeString(
        layout.researcherLinks.resolve("part-00001"),
        first + "\n",
        UTF_8
      )
    }
    // The first link row is repeated exactly: 1 row beyond the first, 0
    // conflicting keys.
    assertEquals(o.code, 0, o.output.slice(0, 3000))
    assertEquals(
      o.counts("input.duplicateKeyRows.researcherLinks").num.toLong,
      1L
    )
    assertEquals(
      o.counts("input.conflictingDuplicateKeys.researcherLinks").num.toLong,
      0L
    )
    assertEquals(
      o.invariant("conflictingIdentityInputKeys")("status").str,
      "pass"
    )
    assertEquals(
      o.invariant("conflictingDuplicateInputKeys")("status").str,
      "pass"
    )
  }

  test("the code entry says when the sources digest is unavailable") {
    val missing = CodeInfo.manifestEntry("head-1", "unavailable")
    assertEquals(missing("inRunIdSeed").bool, false)
    assert(
      missing("runIdTracksCode").str.contains("does not change with code"),
      missing("runIdTracksCode").str
    )
    assertEquals(missing("sparkMainSourcesSha256").str, "unavailable")
    val present = CodeInfo.manifestEntry("head-1", "ab" * 32)
    assertEquals(present("inRunIdSeed").bool, true)
    assert(present("runIdTracksCode").str.startsWith("yes"))
  }

  test("_work is removed after success and kept after a failure") {
    val kept = Files.createTempDirectory("scholarchain-spark-work-")
    Files.createDirectories(kept.resolve("_work/local"))
    Files.writeString(kept.resolve("_work/local/f"), "x", UTF_8)
    CollaborationPipeline.finishWork(kept, succeeded = false)
    assert(Files.exists(kept.resolve("_work/local/f")))
    CollaborationPipeline.finishWork(kept, succeeded = true)
    assert(!Files.exists(kept.resolve("_work")))
    assert(Files.isDirectory(kept))
    CollaborationPipeline.finishWork(kept, succeeded = true)
  }
