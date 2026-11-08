package scholarchain.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scholarchain.core.{ContractSchema, SourceType}
import scholarchain.core.pairing.{BlockKind, BlockStrategy, MatchCandidate}
import scholarchain.er.Fixtures.*
import scholarchain.er.matching.{RecordIndex, SideRecord}
import scholarchain.er.pairs.{PairItem, PairsFile}
import scholarchain.er.sampling.*
import upickle.default.read

class PairsExportSuite extends munit.FunSuite:
  private def candidate(
      left: SideRecord,
      right: SideRecord,
      strategy: BlockStrategy = BlockStrategy.NameInitials
  ): MatchCandidate =
    MatchCandidate(
      ContractSchema.currentVersion,
      MatchCandidate.recordTypeName,
      BlockKind.Researcher,
      left.id,
      right.id,
      SourceType.Dblp,
      SourceType.Dblp,
      s"key-${left.id}",
      strategy,
      Vector.empty,
      Vector.empty,
      Map.empty,
      provenance(SourceType.Dblp, s"cand-${left.id}")
    )

  private val orcids = Vector(validOrcidA, validOrcidB)

  private val exactPairs = (1 to 12).toVector.map { i =>
    val id = orcids(i % 2)
    (
      mention(f"e$i%02da", "Jane Doe", externalIds = Map("orcid" -> id)),
      mention(f"e$i%02db", "J. Doe", externalIds = Map("orcid" -> id))
    )
  }
  private val hardPairs = (1 to 12).toVector.map { i =>
    (
      mention(
        f"h$i%02da",
        "Wei Wang",
        Vector(s"Alice A$i", s"Bob B$i"),
        Vector("Tsinghua University")
      ),
      mention(
        f"h$i%02db",
        "Wei Wang",
        Vector(s"Carl C$i", s"Dora D$i"),
        Vector("University of Toronto")
      )
    )
  }
  private val clearPairs = (1 to 12).toVector.map { i =>
    (
      mention(f"c$i%02da", "John Smith"),
      mention(f"c$i%02db", "Maria Nguyen")
    )
  }
  private val sameWorkPairs = Vector(
    (
      mention("s01a", "Wei Wang", workId = Some("w-s"), position = 1),
      mention("s01b", "Wei Wang", workId = Some("w-s"), position = 2)
    )
  )
  private val agreeingPairs = Vector(
    (
      mention("g01a", "Li Zhang", Vector("Ann Lee", "Bo Ray"), Vector("MIT")),
      mention("g01b", "Li Zhang", Vector("Ann Lee", "Bo Ray"), Vector("MIT"))
    )
  )
  private val allPairs =
    exactPairs ++ hardPairs ++ clearPairs ++ sameWorkPairs ++ agreeingPairs
  private val index = RecordIndex(
    allPairs.flatMap((a, b) => Vector(a.id -> a, b.id -> b)).toMap
  )
  private val candidates = allPairs.map((a, b) => candidate(a, b))
  private val inputs = ExportInputs("c.jsonl", "aa", "w.jsonl", "bb")

  private def plan(total: Int, seed: String = "seed-1") =
    SamplingPlan(seed, total, includeAgreeingContext = false)

  private def runExport(total: Int, seed: String = "seed-1") =
    PairsExport.run(candidates, index, plan(total, seed), inputs)

  private def keysOf(value: ujson.Value): Vector[String] =
    value match
      case ujson.Obj(fields) =>
        fields.toVector.flatMap((k, v) => k +: keysOf(v))
      case ujson.Arr(items) => items.toVector.flatMap(keysOf)
      case _                => Vector.empty

  private def strataOf(
      pairs: Vector[(SideRecord, SideRecord)],
      total: Int
  ): Vector[Option[String]] =
    val byId = runExport(total).keys.map(k => k.pairId -> k.stratum).toMap
    pairs.map((a, b) => byId.get(PairItem.idOf(a.kind, a.id, b.id)))

  test("the pairs file holds evidence only: no strata, scores or decisions") {
    val result = runExport(30)
    assertEquals(result.items.size, 30)
    val banned = Vector(
      "stratum",
      "score",
      "decision",
      "reason",
      "confidence",
      "path",
      "outcome"
    )
    result.items.foreach { item =>
      val text = PairItem.render(item)
      keysOf(ujson.read(text)).foreach { key =>
        banned.foreach(word =>
          assert(!key.toLowerCase.contains(word), s"key $key in $text")
        )
      }
      Stratum.values.foreach(s => assert(!text.contains(s.wire), text))
    }
  }

  test("the strongest evidence comes first in each side") {
    val line = PairItem.render(runExport(30).items.head)
    val side = ujson.read(line)("left").obj.keys.toVector
    assertEquals(side.take(3), Vector("identifiers", "name", "coauthors"))
    assertEquals(side.last, "snapshotId")
  }

  test("the same input and seed give identical output, in any input order") {
    val first = runExport(30)
    assertEquals(runExport(30), first)
    val shuffled =
      PairsExport.run(candidates.reverse, index, plan(30), inputs)
    assertEquals(shuffled.items, first.items)
    assertEquals(shuffled.keys, first.keys)
  }

  test("a different seed draws a different sample") {
    val a = runExport(15, "seed-1").items.map(_.pairId).toSet
    val b = runExport(15, "seed-2").items.map(_.pairId).toSet
    assertNotEquals(a, b)
  }

  test("the pair order does not group pairs by stratum") {
    val strata = runExport(30).keys.map(_.stratum)
    assertNotEquals(strata, strata.sorted)
  }

  test("a total is split into thirds and each stratum reports its count") {
    assertEquals(plan(230).targets.values.toVector.sorted, Vector(76, 77, 77))
    val result = runExport(30)
    result.manifest.strata.foreach { r =>
      assertEquals(
        (r.target, r.eligible, r.selected, r.shortfall),
        (10, 12, 10, 0)
      )
    }
    assertEquals(
      result.keys.groupBy(_.stratum).view.mapValues(_.size).toMap,
      Stratum.values.map(_.wire -> 10).toMap
    )
  }

  test("a short stratum gives what it has, reports the shortfall, no top-up") {
    val result = runExport(45)
    result.manifest.strata.foreach { r =>
      assertEquals((r.target, r.selected, r.shortfall), (15, 12, 3))
    }
    assertEquals(result.items.size, 36)
    assertEquals(result.manifest.totalShortfall, 9)
  }

  test("same-work mentions and agreeing contexts are excluded and counted") {
    val manifest = runExport(30).manifest
    assertEquals(manifest.excluded("same-work-mentions"), 1)
    assertEquals(manifest.excluded("same-name-agreeing-context"), 1)
    val included = PairsExport.run(
      candidates,
      index,
      SamplingPlan("seed-1", 45, includeAgreeingContext = true),
      inputs
    )
    assertEquals(
      included.manifest.strata
        .find(_.stratum == "hard-common-name")
        .get
        .eligible,
      13
    )
  }

  test("strata follow raw features, not matcher output") {
    assertEquals(
      strataOf(exactPairs, 45).distinct,
      Vector(Some("exact-id"))
    )
    assertEquals(
      strataOf(hardPairs, 45).distinct,
      Vector(Some("hard-common-name"))
    )
    assertEquals(
      strataOf(clearPairs, 45).distinct,
      Vector(Some("clear-non-match"))
    )
  }

  test("every pair traces to source records, snapshot and run") {
    runExport(30).items.foreach { item =>
      assertEquals(item.runId, runId)
      assertEquals(item.exportVersion, PairItem.exportVersion)
      Vector(item.left, item.right).foreach { side =>
        assert(side.sourceRecordId.nonEmpty)
        assert(side.snapshotId.nonEmpty)
        assert(side.sourceUri.nonEmpty)
      }
    }
  }

  test(
    "files are written, the key is separate and the pairs file round trips"
  ) {
    val directory = Files.createTempDirectory("er-export")
    val result = runExport(30)
    val paths = PairsExport.write(directory, result)
    assertEquals(PairsFile.read(paths.pairs), result.items)
    assert(Files.exists(paths.key) && Files.exists(paths.manifest))
    assertEquals(paths.key.getParent.getFileName.toString, "private")
    assert(Files.readString(paths.key, UTF_8).contains("exact-id"))
    assert(!Files.readString(paths.pairs, UTF_8).contains("exact-id"))
    val manifest =
      read[ExportManifest](Files.readString(paths.manifest, UTF_8))
    assertEquals(manifest, result.manifest)
  }
