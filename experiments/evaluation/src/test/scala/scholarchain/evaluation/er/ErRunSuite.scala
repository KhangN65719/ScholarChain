package scholarchain.evaluation.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import scholarchain.evaluation.io.{Digests, ExportFiles}

class ErRunSuite extends FunSuite:
  import ErFixtures.*

  private val tol = 1e-12

  test("readers refuse a bad verdict, a bad outcome and a duplicate pair id"):
    val badVerdict = intercept[IllegalArgumentException](
      ErInputs.gold("g", Vector("""{"itemId":"a","verdict":"maybe"}"""))
    )
    assert(badVerdict.getMessage.contains("g:1"), badVerdict.getMessage)
    val badOutcome = intercept[IllegalArgumentException](
      ErInputs.decisions(
        "d",
        Vector("""{"pair_id":"a","outcome":"WAT","path":"exact-id"}""")
      )
    )
    assert(badOutcome.getMessage.contains("outcome WAT"), badOutcome.getMessage)
    val dup = intercept[IllegalArgumentException](
      ErInputs.strata(
        "s",
        Vector(
          """{"pair_id":"a","stratum":"x"}""",
          """{"pair_id":"a","stratum":"y"}"""
        )
      )
    )
    assert(dup.getMessage.contains("duplicate pair id a"), dup.getMessage)
    val notJson = intercept[IllegalArgumentException](
      ErInputs.decisions("d", Vector("", "{oops"))
    )
    assert(notJson.getMessage.contains("d:2"), notJson.getMessage)

  test(
    "join keeps gold-only pairs, lists decisions with no gold and unknown strata"
  ):
    val gold = Vector(
      GoldRow("a", GoldVerdict.Same),
      GoldRow("b", GoldVerdict.Different)
    )
    val decisions = Vector(
      Decision("a", "researcher", Outcome.Accepted, "exact-id", None),
      Decision("z", "researcher", Outcome.Rejected, "probabilistic", None)
    )
    val j = ErInputs.join(gold, decisions, Map("a" -> "exact-id"))
    assertEquals(
      j.items.map(i => (i.pairId, i.stratum, i.accepted)),
      Vector(
        ("a", "exact-id", true),
        ("b", "unknown", false)
      )
    )
    assertEquals(j.goldWithoutDecision, Vector("b"))
    assertEquals(j.decisionsWithoutGold, Vector("z"))
    assertEquals(j.goldWithoutStratum, Vector("b"))

  test("manifest: a claimed count without pair ids is not identifiable"):
    val text =
      """{"final_gold_set_description":"230 pairs. the human reports 12 clear-non-match pairs labeled same are likely labeling errors",
        |"files":[{"path":"gold/gold-v3.jsonl","sha256":"abc"},{"path":"gold/eval-v3-r3/decisions.jsonl","sha256":"def"}]}""".stripMargin
    val m = ManifestInfo.parse(text)
    assertEquals(m.claimedMislabelled, Some(12))
    assertEquals(m.identifiedPairIds, Vector.empty[String])
    assert(!m.identifiesPairs)
    assertEquals(m.check(Path.of("/x/gold/gold-v3.jsonl"), "abc"), "match")
    assertEquals(m.check(Path.of("/x/gold/gold-v3.jsonl"), "zzz"), "MISMATCH")
    assertEquals(
      m.check(Path.of("/x/eval-v3-r3/decisions.jsonl"), "def"),
      "match"
    )
    assertEquals(m.check(Path.of("/x/other/file.jsonl"), "def"), "not-listed")

  test("manifest: pair ids under a suspect or mislabel key are identified"):
    val m = ManifestInfo.parse(
      """{"suspectedMislabelledPairs":["p1","p2","p1"],"files":[]}"""
    )
    assertEquals(m.identifiedPairIds, Vector("p1", "p2"))
    assert(m.identifiesPairs)
    assertEquals(m.claimedMislabelled, None)

  test("population: link status distribution and unresolved endpoint rates"):
    withTmp { root =>
      writeExport(root)
      val p = ErPopulation.read(root)
      assert(!p.incomplete)
      assertEquals(p.nodes.rows, 6L)
      assertEquals(
        p.nodes.byLinkStatus,
        Map(
          "linked" -> 2L,
          "unlinked" -> 2L,
          "ambiguous" -> 1L,
          "(absent)" -> 1L
        )
      )
      assertEquals(
        p.nodes.byNodeKind,
        Map("resolved" -> 3L, "unresolved-mention" -> 3L)
      )
      assertEquals(p.nodes.byKindAndStatus("resolved/(absent)"), 1L)
      val all = p.edges.overall
      assertEquals(
        (all.rows, all.aKnown, all.aUnresolved, all.bKnown, all.bUnresolved),
        (4L, 4L, 2L, 3L, 1L)
      )
      assertEquals(
        (all.pairKnown, all.eitherUnresolved, all.bothUnresolved),
        (3L, 2L, 1L)
      )
      val w = p.edges.byWindow
      assertEquals(w.keySet, Set("all", "2020-2029"))
      assertEquals(
        (w("all").rows, w("all").aUnresolved, w("all").bUnresolved),
        (2L, 1L, 0L)
      )
      assertEquals(
        (
          w("2020-2029").rows,
          w("2020-2029").bKnown,
          w("2020-2029").bothUnresolved
        ),
        (2L, 1L, 1L)
      )
    }

  test("population: a missing table is reported, not treated as zero rates"):
    withTmp { root =>
      write(
        root.resolve("nodes-researcher/part-00000.jsonl"),
        Vector("""{"linkStatus":"linked"}""")
      )
      val p = ErPopulation.read(root)
      assert(p.nodes.present && !p.edges.present && p.incomplete)
      assertEquals(p.edges.overall.rows, 0L)
    }

  test("window falls back to windowStart-windowEnd"):
    val v = ujson.read("""{"windowStart":2020,"windowEnd":2029}""")
    assertEquals(ErPopulation.windowOf(v), "2020-2029")
    assertEquals(ErPopulation.windowOf(ujson.read("{}")), "(absent)")

  private final case class Files3(
      decisions: Path,
      gold: Path,
      strata: Path,
      manifest: Path,
      exportDir: Path,
      out: Path
  )

  private def setup(root: Path, goldSha: Option[String] = None): Files3 =
    val d = write(root.resolve("in/decisions.jsonl"), decisionLines)
    val g = write(root.resolve("in/gold.jsonl"), goldLines)
    val s = write(root.resolve("in/strata.jsonl"), strataLines)
    writeExport(root.resolve("export"))
    val manifest = ujson.write(
      ujson.Obj(
        "final_gold_set_description" ->
          "the human reports 12 clear-non-match pairs labeled same are likely labeling errors",
        "files" -> ujson.Arr(
          ujson.Obj(
            "path" -> "in/decisions.jsonl",
            "sha256" -> Digests.sha256File(d)
          ),
          ujson.Obj(
            "path" -> "in/gold.jsonl",
            "sha256" -> goldSha.getOrElse(Digests.sha256File(g))
          )
        )
      )
    )
    val m = root.resolve("in/GOLD-MANIFEST.json")
    Files.writeString(m, manifest, UTF_8)
    Files3(d, g, s, m, root.resolve("export"), root.resolve("out"))

  private def args(f: Files3, extra: String*): Vector[String] =
    Vector(
      "--decisions",
      f.decisions.toString,
      "--gold",
      f.gold.toString,
      "--strata-key",
      f.strata.toString,
      "--gold-manifest",
      f.manifest.toString,
      "--export",
      f.exportDir.toString,
      "--out",
      f.out.toString,
      "--resamples",
      "60"
    ) ++ extra

  private def num(v: ujson.Value): Double = v.num

  test("a run writes the report once, with SHA256SUMS over every file"):
    withTmp { root =>
      val f = setup(root)
      assertEquals(ErExtras.cli(args(f, "--run-id", "run1")), 0)
      val dir = f.out.resolve("run1")
      val names = Vector(
        "bootstrap-resamples.csv",
        "er-extras-report.json",
        "er-extras-report.txt",
        "run-info.json"
      )
      names.foreach(n => assert(Files.isRegularFile(dir.resolve(n)), n))
      val sums = Files
        .readAllLines(dir.resolve("SHA256SUMS"))
        .toArray
        .toVector
        .map(_.toString)
      assertEquals(sums.map(_.split("  ")(1)), names)
      sums.foreach { line =>
        val Array(sha, name) = line.split("  ")
        assertEquals(Digests.sha256File(dir.resolve(name)), sha)
      }
      val report =
        ujson.read(Files.readString(dir.resolve("er-extras-report.json")))
      assertEquals(report("status").str, "COMPLETE")
      assertEquals(report("pooled")("truePositive").num.toLong, 5L)
      assertEqualsDouble(num(report("pooled")("recall")), 5.0 / 11.0, tol)
      val paths =
        report("perPath")("paths").arr.map(p => p("path").str -> p).toMap
      assertEquals(paths.keySet, PathRecall.KnownPaths.toSet)
      assertEqualsDouble(num(paths("exact-id")("recall")), 0.75, tol)
      assertEquals(paths("llm-adjudicated")("recall"), ujson.Null)
      val boot = report("pooledBootstrap")
      assertEquals(boot("seed").num.toLong, 16L)
      assert(num(boot("recall")("low")) <= num(boot("recall")("high")))
      val manifest = report("sensitivity")("manifest")
      assertEquals(manifest("status").str, "not-identifiable")
      assertEquals(manifest("claimedMislabelledPairs").num.toInt, 12)
      val sets = report("sensitivity")("suspectSets").arr
        .map(s => s("name").str -> s)
        .toMap
      assertEquals(sets.keySet, Set("proxy-gold-same-in-clear-non-match"))
      assertEquals(sets.head._2("size").num.toInt, 2)
      assertEquals(
        report("tierSPopulation")("researcherNodes")("rows").num.toLong,
        6L
      )
      val checks = report("inputs").arr
        .map(i => i("label").str -> i("goldManifestCheck").str)
        .toMap
      assertEquals(checks("decisions"), "match")
      assertEquals(checks("gold"), "match")
      assertEquals(checks("strata-key"), "not-listed")
      // csv: header plus one row per resample
      assertEquals(
        Files.readAllLines(dir.resolve("bootstrap-resamples.csv")).size,
        61
      )
    }

  test("the pooled numbers are compared with the recorded replay"):
    // The tally of evaluation-report.txt: same 96, different 131, unsure 3.
    val replay = ErReference.check(Tally(77L, 0L, 96L, 131L, 3L))
    assert(replay.matches, replay.line)
    assertEquals(replay.status, "MATCH")
    assert(replay.line.contains("TP=77 FP=0"), replay.line)
    assert(replay.line.contains("precision=1.000 recall=0.802 F1=0.890"))
    assert(replay.line.contains(ErReference.Source))
    // One accepted pair fewer, or one false positive, is a difference.
    assert(!ErReference.check(Tally(76L, 0L, 96L, 131L, 3L)).matches)
    assert(!ErReference.check(Tally(77L, 1L, 96L, 131L, 3L)).matches)
    withTmp { root =>
      val f = setup(root)
      assertEquals(ErExtras.cli(args(f, "--run-id", "ref")), 0)
      val report =
        ujson.read(Files.readString(f.out.resolve("ref/er-extras-report.json")))
      // the fixture is not the replay: TP 5, FP 1
      assertEquals(report("referenceCheck")("status").str, "DIFFERS")
      assert(report("referenceCheck")("actual").str.contains("TP=5 FP=1"))
      val text = Files.readString(f.out.resolve("ref/er-extras-report.txt"))
      assert(text.contains("ER_REFERENCE_CHECK status=DIFFERS"), text)
    }

  test(
    "a second run with the same run id is refused and the first stays unchanged"
  ):
    withTmp { root =>
      val f = setup(root)
      assertEquals(ErExtras.cli(args(f, "--run-id", "run1")), 0)
      val before =
        Digests.sha256File(f.out.resolve("run1/er-extras-report.json"))
      assertEquals(ErExtras.cli(args(f, "--run-id", "run1")), 2)
      assertEquals(
        Digests.sha256File(f.out.resolve("run1/er-extras-report.json")),
        before
      )
    }

  test(
    "two runs with the same seed give the same bootstrap and a new seed gives another"
  ):
    withTmp { root =>
      val f = setup(root)
      assertEquals(ErExtras.cli(args(f, "--run-id", "a")), 0)
      assertEquals(ErExtras.cli(args(f, "--run-id", "b")), 0)
      assertEquals(ErExtras.cli(args(f, "--run-id", "c", "--seed", "17")), 0)
      def csv(id: String) =
        Files.readString(f.out.resolve(s"$id/bootstrap-resamples.csv"))
      assertEquals(csv("a"), csv("b"))
      assertNotEquals(csv("a"), csv("c"))
    }

  test("a suspect-pairs file adds its own sensitivity set"):
    withTmp { root =>
      val f = setup(root)
      val suspects = write(
        root.resolve("in/suspects.txt"),
        Vector("# note", "c3", "", "ghost")
      )
      assertEquals(
        ErExtras.cli(
          args(f, "--run-id", "s", "--suspect-pairs", suspects.toString)
        ),
        0
      )
      val report =
        ujson.read(Files.readString(f.out.resolve("s/er-extras-report.json")))
      val sets = report("sensitivity")("suspectSets").arr
        .map(s => s("name").str -> s)
        .toMap
      val file = sets("suspect-pairs-file")
      assertEquals(file("pairIds").arr.map(_.str).toVector, Vector("c3"))
      assertEquals(file("notInGold").arr.map(_.str).toVector, Vector("ghost"))
    }

  test("a gold file that differs from the manifest is flagged, not refused"):
    withTmp { root =>
      val f = setup(root, goldSha = Some("0" * 64))
      assertEquals(ErExtras.cli(args(f, "--run-id", "m")), 0)
      val report =
        ujson.read(Files.readString(f.out.resolve("m/er-extras-report.json")))
      assert(
        report("warnings").arr.exists(_.str.contains("differs from the sha256"))
      )
    }

  test("a missing export table keeps the run directory and exits 3"):
    withTmp { root =>
      val f = setup(root)
      deleteTree(f.exportDir.resolve("edges-coauthored"))
      assertEquals(
        ErExtras.cli(args(f, "--run-id", "inc")),
        ErExtras.IncompleteExit
      )
      val report =
        ujson.read(Files.readString(f.out.resolve("inc/er-extras-report.json")))
      assertEquals(report("status").str, "INCOMPLETE")
      assert(Files.isRegularFile(f.out.resolve("inc/SHA256SUMS")))
    }

  test("bad arguments and bad inputs exit 2 and write nothing"):
    withTmp { root =>
      val f = setup(root)
      assertEquals(ErExtras.cli(args(f, "--sede", "1")), 2)
      assertEquals(ErExtras.cli(args(f, "--seed", "x")), 2)
      assertEquals(ErExtras.cli(args(f, "--resamples", "0")), 2)
      assertEquals(ErExtras.cli(args(f, "--seed", "1", "--seed", "2")), 2)
      assertEquals(ErExtras.cli(args(f).take(4)), 2)
      Files.writeString(
        f.gold,
        """{"itemId":"a","verdict":"maybe"}""" + "\n",
        UTF_8
      )
      assertEquals(ErExtras.cli(args(f, "--run-id", "bad")), 2)
      assert(
        !Files.exists(f.out),
        "no output directory is created on a refusal"
      )
    }

  test(
    "the export reader tolerates the part-file layout used by the Spark export"
  ):
    withTmp { root =>
      writeExport(root)
      assertEquals(
        ExportFiles.tables(root),
        Vector("edges-coauthored", "nodes-researcher")
      )
    }
