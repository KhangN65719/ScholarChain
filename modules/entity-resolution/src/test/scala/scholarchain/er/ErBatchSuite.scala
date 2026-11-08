package scholarchain.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scholarchain.core.{
  AuthorMention,
  CanonicalResearcher,
  ContractSchema,
  MatchOutcome,
  SourceType,
  StableIds,
  WorkRecord
}
import scholarchain.core.pairing.{BlockKind, BlockStrategy, MatchCandidate}
import scholarchain.er.Fixtures.{provenance, validOrcidA, validOrcidB}
import scholarchain.er.matching.{EntityKind, ResolutionRecord}
import upickle.default.{read as readJson, write as writeJson}

/** Gate tests for the batch run: tiny candidate sets whose every outcome can be
  * checked by hand. Researcher mentions are solo authors of their own works, so
  * no position rule fires; the outcomes come from ORCID agreement, ORCID
  * disagreement, a family-name mismatch, an invalid candidate and a missing
  * record, which the matcher decides without scoring.
  */
class ErBatchSuite extends munit.FunSuite:
  private val clock = () => Instant.parse("2026-01-01T00:00:00Z")

  private def author(
      name: String,
      ids: Map[String, String] = Map.empty
  ): (String, Map[String, String]) = (name, ids)

  private def work(
      source: String,
      title: String,
      authors: Vector[(String, Map[String, String])],
      doi: Option[String] = None
  ): WorkRecord =
    val workId = StableIds.work("dblp", source)
    val prov = provenance(SourceType.Dblp, source)
    val mentions = authors.zipWithIndex.map { case ((name, ids), index) =>
      AuthorMention(
        ContractSchema.currentVersion,
        StableIds.mention(workId, index + 1),
        workId,
        index + 1,
        name,
        name.toLowerCase,
        ids,
        Vector.empty,
        prov
      )
    }
    WorkRecord(
      ContractSchema.currentVersion,
      workId,
      SourceType.Dblp,
      source,
      title,
      title.toLowerCase,
      Some(2020),
      Some("venue-a"),
      doi,
      None,
      mentions,
      None,
      Vector.empty,
      Vector.empty,
      "article",
      prov
    )

  private def candidate(
      kind: BlockKind,
      a: String,
      b: String,
      block: String = "block-a",
      recordType: String = MatchCandidate.recordTypeName
  ): MatchCandidate =
    val (left, right) = StableIds.orderedPair(a, b)
    MatchCandidate(
      ContractSchema.currentVersion,
      recordType,
      kind,
      left,
      right,
      SourceType.Dblp,
      SourceType.Dblp,
      block,
      BlockStrategy.NameYear,
      Vector.empty,
      Vector.empty,
      Map("name.exact" -> 1.0),
      provenance(SourceType.Derived, s"$left|$right")
    )

  private def lines(directory: Path): Vector[String] =
    val listing = Files.list(directory)
    val files =
      try listing.iterator().asScala.toVector.sortBy(_.getFileName.toString)
      finally listing.close()
    files.flatMap(file => Files.readAllLines(file, UTF_8).asScala.toVector)

  private def put(path: Path, rows: Iterable[String]): Unit =
    Files.createDirectories(path.getParent)
    Files.write(path, rows.asJava, UTF_8)

  /** Mentions: m1 m2 m3 share ORCID A; m4 has ORCID B; m5 and m6 differ in
    * family name; m7 and m8 are joined only by an invalid candidate; m9 meets a
    * record that is not in the export. Works: wA wB wC share a DOI, wD has
    * another; wP wQ wR make a chain whose ends disagree on the DOI.
    */
  private final class Fixture(val base: Path):
    val runRoot: Path = base.resolve("phase4-out/run=run-x/snapshot=abc123")
    val works: Vector[WorkRecord] = Vector(
      work(
        "r1",
        "Alpha study one",
        Vector(author("Carol Diaz", Map("orcid" -> validOrcidA)))
      ),
      work(
        "r2",
        "Beta study two",
        Vector(author("Carol Diaz", Map("orcid" -> validOrcidA)))
      ),
      work(
        "r3",
        "Gamma study three",
        Vector(author("Carol Diaz", Map("orcid" -> validOrcidA)))
      ),
      work(
        "r4",
        "Delta study four",
        Vector(author("Carol Diaz", Map("orcid" -> validOrcidB)))
      ),
      work("r5", "Epsilon study five", Vector(author("Dan Zhang"))),
      work("r6", "Zeta study six", Vector(author("Eve Quist"))),
      work("r7", "Eta study seven", Vector(author("Fay Lund"))),
      work("r8", "Theta study eight", Vector(author("Fay Lund"))),
      work("r9", "Iota study nine", Vector(author("Gus Hale"))),
      work(
        "wA",
        "A shared title long enough for a rule",
        Vector(author("Una Fox")),
        Some("10.1000/aaa")
      ),
      work(
        "wB",
        "A shared title long enough for a rule",
        Vector(author("Una Fox")),
        Some("10.1000/aaa")
      ),
      work(
        "wC",
        "A shared title long enough for a rule",
        Vector(author("Una Fox")),
        Some("10.1000/aaa")
      ),
      work(
        "wD",
        "A shared title long enough for a rule",
        Vector(author("Una Fox")),
        Some("10.1000/bbb")
      ),
      work(
        "wP",
        "Chain title that is long enough to match",
        Vector(author("Ann Poe"), author("Ben Roe")),
        Some("10.1000/ppp")
      ),
      work(
        "wQ",
        "Chain title that is long enough to match",
        Vector(author("Ann Poe"), author("Ben Roe"))
      ),
      work(
        "wR",
        "Chain title that is long enough to match",
        Vector(author("Ann Poe"), author("Ben Roe")),
        Some("10.1000/rrr")
      )
    )
    private val byName: Map[String, WorkRecord] =
      works.map(w => w.sourceRecordId -> w).toMap
    def workId(source: String): String = byName(source).workId
    def mention(source: String): String = byName(source).authors.head.mentionId
    val ghost: String =
      "mention:v1:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

    val candidates: Vector[MatchCandidate] = Vector(
      candidate(BlockKind.Researcher, mention("r1"), mention("r2")),
      candidate(BlockKind.Researcher, mention("r2"), mention("r3")),
      candidate(BlockKind.Researcher, mention("r1"), mention("r2"), "block-z"),
      candidate(BlockKind.Researcher, mention("r3"), mention("r4")),
      candidate(BlockKind.Researcher, mention("r5"), mention("r6")),
      candidate(
        BlockKind.Researcher,
        mention("r7"),
        mention("r8"),
        recordType = "not-a-candidate"
      ),
      candidate(BlockKind.Researcher, mention("r9"), ghost),
      candidate(BlockKind.Work, workId("wA"), workId("wB")),
      candidate(BlockKind.Work, workId("wB"), workId("wC")),
      candidate(BlockKind.Work, workId("wC"), workId("wD")),
      candidate(BlockKind.Work, workId("wP"), workId("wQ")),
      candidate(BlockKind.Work, workId("wQ"), workId("wR")),
      candidate(BlockKind.Work, workId("wP"), workId("wR"))
    )

    def write(): Unit =
      val half = works.size / 2
      val stage = runRoot.resolve("stage=graph-export")
      put(
        stage.resolve("works/v1/part-r-00000"),
        works.take(half).map(w => writeJson(w))
      )
      put(
        stage.resolve("works/v1/part-r-00001"),
        works.drop(half).map(w => writeJson(w))
      )
      put(stage.resolve("works/v1/_SUCCESS"), Vector.empty)
      put(
        stage.resolve("author-mentions/v1/part-r-00000"),
        works.flatMap(_.authors).map(m => writeJson(m))
      )
      put(
        stage.resolve("researchers/v1/part-r-00000"),
        Vector(
          writeJson(
            CanonicalResearcher(
              ContractSchema.currentVersion,
              StableIds.researcherFromExternal("orcid", validOrcidA),
              "Carol Diaz",
              Map("orcid" -> validOrcidA),
              Vector.empty,
              provenance(SourceType.OpenAlex, "r1")
            )
          )
        )
      )
      put(
        runRoot.resolve(
          "stage=candidate-features/match-candidates/v1/part-r-00000"
        ),
        candidates.take(6).map(c => writeJson(c))
      )
      put(
        runRoot.resolve(
          "stage=candidate-features/match-candidates/v1/part-r-00001"
        ),
        candidates.drop(6).map(c => writeJson(c))
      )

  private def withFixture[A](body: Fixture => A): A =
    val fixture = Fixture(Files.createTempDirectory("er-batch"))
    fixture.write()
    body(fixture)

  private def runOnce(f: Fixture, out: String = "out"): BatchResult =
    ErBatch.run(BatchConfig(f.runRoot, f.base.resolve(out)), clock)

  private def decisions(result: BatchResult): Vector[ResolutionRecord] =
    lines(result.runDir.resolve("decisions/v1"))
      .map(readJson[ResolutionRecord](_))

  private def rows(result: BatchResult, relative: String): Vector[ujson.Value] =
    lines(result.runDir.resolve(relative)).map(ujson.read(_))

  private def byKey(
      values: Vector[ujson.Value],
      key: String
  ): Map[String, ujson.Value] =
    values.map(v => v(key).str -> v).toMap

  private def tree(root: Path): Map[String, Vector[Byte]] =
    val walk = Files.walk(root)
    try
      walk
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .map { file =>
          root.relativize(file).toString -> Files.readAllBytes(file).toVector
        }
        .toMap
    finally walk.close()

  // ---- every outcome flows to the right output ----

  test("every candidate is decided once and all four outcomes are written") {
    withFixture { f =>
      val result = runOnce(f)
      val all = decisions(result)
      assertEquals(all.size, 12)
      val outcomes =
        all.groupBy(_.decision.decision).view.mapValues(_.size).toMap
      assertEquals(
        outcomes,
        Map(
          MatchOutcome.Accepted -> 6,
          MatchOutcome.Rejected -> 1,
          MatchOutcome.Unresolved -> 2,
          MatchOutcome.Conflict -> 3
        )
      )
      assertEquals(
        all.map(r => (r.leftId, r.rightId)),
        all.map(r => (r.leftId, r.rightId)).sorted
      )
      val manifest = result.manifest
      assertEquals(manifest("counts")("candidatesRead").num, 13.0)
      assertEquals(manifest("counts")("distinctPairs").num, 12.0)
      assertEquals(manifest("counts")("duplicateCandidatesDropped").num, 1.0)
      assertEquals(manifest("decisions")("byOutcome")("ACCEPTED").num, 6.0)
      assertEquals(manifest("decisions")("byOutcome")("CONFLICT").num, 3.0)
      assertEquals(manifest("decisions")("byOutcome")("REJECTED").num, 1.0)
      assertEquals(manifest("decisions")("byOutcome")("UNRESOLVED").num, 2.0)
    }
  }

  test("each outcome carries the fields the decision contract requires") {
    withFixture { f =>
      val result = runOnce(f)
      val reasons = decisions(result).map(r =>
        r.decision.decision -> r.decision.reasonCodes
      )
      assert(
        reasons.exists((o, c) =>
          o == MatchOutcome.Rejected && c.contains(
            "rejected.family-name-mismatch"
          )
        )
      )
      assert(reasons.exists((_, c) => c.contains("input.invalid-candidate")))
      assert(reasons.exists((_, c) => c.contains("input.side-missing")))
      assert(
        reasons.exists((o, c) =>
          o == MatchOutcome.Conflict && c.contains("conflict.id:orcid")
        )
      )
      decisions(result).foreach { r =>
        val d = r.decision
        assertEquals(d.runId, result.runId)
        assertEquals(d.provenance.runId, result.runId)
        assertEquals(d.policyVersion, "scholarchain.er.policy/1.4.0")
        assertEquals(d.decidedAt, "1970-01-01T00:00:00Z")
        assert(d.rulesFired.nonEmpty && d.reasonCodes.nonEmpty)
        assert(d.confidence >= 0.0 && d.confidence <= 1.0)
        assertEquals(d.provenance.source, SourceType.Derived)
        assertEquals(
          d.provenance.transformVersion,
          s"entity-resolution/${d.policyVersion}"
        )
      }
      val accepted =
        decisions(result).filter(_.decision.decision == MatchOutcome.Accepted)
      assert(accepted.forall(_.decision.features.nonEmpty))
      assertEquals(
        result.manifest("decisions")("byPath")("exact-id").num +
          result
            .manifest("decisions")("byPath")("deterministic-bibliographic")
            .num +
          result.manifest("decisions")("byPath")("probabilistic").num,
        12.0
      )
      assertEquals(result.manifest("llm")("networkCalls").num, 0.0)
    }
  }

  // ---- accepted links: transitive closure, canonical IDs, no merge on conflict ----

  test(
    "researcher links merge only accepted pairs, transitively, by the ORCID rule"
  ) {
    withFixture { f =>
      val result = runOnce(f)
      val links =
        byKey(rows(result, "accepted-links/v1/researcher-links"), "mentionId")
      assertEquals(links.size, f.works.flatMap(_.authors).size)
      val a = StableIds.researcherFromExternal("orcid", validOrcidA)
      val b = StableIds.researcherFromExternal("orcid", validOrcidB)
      val merged = Vector("r1", "r2", "r3").map(s => links(f.mention(s)))
      merged.foreach { row =>
        assertEquals(row("canonicalResearcherId").str, a)
        assertEquals(row("status").str, "merged")
        assertEquals(row("idRule").str, "orcid")
        assertEquals(row("clusterSize").num, 3.0)
        assertEquals(row("provenance")("runId").str, result.runId)
      }
      // r1 and r3 were never a candidate pair: they meet through r2 only.
      assert(
        !f.candidates.exists(c =>
          Set(c.leftId, c.rightId) == Set(f.mention("r1"), f.mention("r3"))
        )
      )
      // CONFLICT r3 - r4 never merges: r4 keeps its own ORCID identity.
      assertEquals(links(f.mention("r4"))("canonicalResearcherId").str, b)
      assertEquals(links(f.mention("r4"))("status").str, "external-id")
      assertEquals(links(f.mention("r4"))("clusterSize").num, 1.0)
      // REJECTED, UNRESOLVED (invalid candidate, missing record) and lone
      // mentions are preserved unmerged with no canonical ID.
      Vector("r5", "r6", "r7", "r8", "r9").foreach { s =>
        val row = links(f.mention(s))
        assert(row("canonicalResearcherId").isNull, s)
        assertEquals(row("status").str, "unlinked")
        assertEquals(row("clusterSize").num, 1.0)
      }
    }
  }

  test(
    "canonical researchers list each ID once and flag the graph-export table"
  ) {
    withFixture { f =>
      val result = runOnce(f)
      val canonical = byKey(
        rows(result, "accepted-links/v1/canonical-researchers"),
        "canonicalResearcherId"
      )
      val a = StableIds.researcherFromExternal("orcid", validOrcidA)
      val b = StableIds.researcherFromExternal("orcid", validOrcidB)
      assertEquals(canonical.keySet, Set(a, b))
      assertEquals(canonical(a)("memberCount").num, 3.0)
      assertEquals(canonical(a)("inResearchersExport").bool, true)
      assertEquals(canonical(b)("inResearchersExport").bool, false)
      assertEquals(
        canonical(a)("memberMentionIds").arr.map(_.str).toVector,
        Vector("r1", "r2", "r3").map(f.mention).sorted
      )
      val stats = result.manifest("clusters")("canonicalResearchers")
      assertEquals(stats("total").num, 2.0)
      assertEquals(stats("orcidRuleInResearchersExport").num, 1.0)
    }
  }

  test(
    "work links map accepted works to the smallest member and keep a conflict apart"
  ) {
    withFixture { f =>
      val result = runOnce(f)
      val links = byKey(rows(result, "accepted-links/v1/work-links"), "workId")
      assertEquals(links.size, f.works.size)
      val abc = Vector("wA", "wB", "wC").map(f.workId)
      abc.foreach { id =>
        assertEquals(links(id)("canonicalWorkId").str, abc.min)
        assertEquals(links(id)("status").str, "merged")
        assertEquals(links(id)("clusterSize").num, 3.0)
      }
      assertEquals(links(f.workId("wD"))("canonicalWorkId").str, f.workId("wD"))
      assertEquals(links(f.workId("wD"))("status").str, "unlinked")
      assertEquals(links(f.workId("r1"))("status").str, "unlinked")
    }
  }

  test(
    "a cluster that joins records an identifier says differ is an integrity failure"
  ) {
    withFixture { f =>
      val result = runOnce(f)
      val links = byKey(rows(result, "accepted-links/v1/work-links"), "workId")
      // wP - wQ and wQ - wR are ACCEPTED, but wP and wR disagree on the DOI and
      // are a CONFLICT: the chain must not merge.
      Vector("wP", "wQ", "wR").foreach { s =>
        val row = links(f.workId(s))
        assertEquals(row("status").str, "integrity-failure")
        assertEquals(row("canonicalWorkId").str, f.workId(s))
        assertEquals(row("clusterSize").num, 3.0)
      }
      val failures = rows(result, "integrity-failures/v1")
      assertEquals(
        failures.map(_("failureType").str).toSet,
        Set(
          "conflicting-authoritative-identifiers",
          "conflict-decision-inside-cluster"
        )
      )
      failures.foreach { row =>
        assertEquals(row("kind").str, "work")
        assertEquals(
          row("memberIds").arr.map(_.str).toVector,
          Vector("wP", "wQ", "wR").map(f.workId).sorted
        )
        assertEquals(row("provenance")("runId").str, result.runId)
      }
      assertEquals(result.integrityFailures, 1)
      assertEquals(result.manifest("integrityFailures")("records").num, 2.0)
    }
  }

  // ---- determinism, idempotence, output location ----

  test("the same input gives the same logical output in a fresh output root") {
    withFixture { f =>
      val first = runOnce(f, "out-one")
      val second = runOnce(f, "out-two")
      assertEquals(first.runId, second.runId)
      assertEquals(ujson.write(first.manifest), ujson.write(second.manifest))
      val one = tree(first.runDir).removed("run-metrics.json")
      val two = tree(second.runDir).removed("run-metrics.json")
      assertEquals(one.keySet, two.keySet)
      one.foreach((path, bytes) => assertEquals(bytes, two(path), path))
    }
  }

  test("a repeated run rewrites the same run directory and adds nothing") {
    withFixture { f =>
      val first = runOnce(f)
      val before = tree(first.runDir)
      val second = runOnce(f)
      assertEquals(second.runDir, first.runDir)
      val after = tree(second.runDir)
      assertEquals(after.keySet, before.keySet)
      assertEquals(
        after.removed("run-metrics.json"),
        before.removed("run-metrics.json")
      )
      assert(after.contains("_SUCCESS"))
      assert(first.runDir.toString.contains("snapshot=abc123"))
      assert(
        first.runDir.toString.contains(s"run=${first.runId.replace(':', '-')}")
      )
      val parts = (0 until 4).map(n => f"decisions/v1/part-$n%05d")
      parts.foreach(p => assert(after.contains(p), p))
    }
  }

  test(
    "changed input changes the run ID, so a different run never overwrites this one"
  ) {
    withFixture { f =>
      val first = runOnce(f)
      put(
        f.runRoot.resolve(
          "stage=candidate-features/match-candidates/v1/part-r-00002"
        ),
        Vector(writeJson(f.candidates.head.copy(blockKey = "block-new")))
      )
      val second = runOnce(f)
      assertNotEquals(first.runId, second.runId)
      assertNotEquals(first.runDir, second.runDir)
    }
  }

  test(
    "output inside the repository, under target or missing a snapshot dir is refused"
  ) {
    withFixture { f =>
      intercept[IllegalArgumentException](
        ErBatch.run(
          BatchConfig(
            f.runRoot,
            Paths.get("modules/entity-resolution/phase6-refused")
          ),
          clock
        )
      )
      assert(
        !Files.exists(Paths.get("modules/entity-resolution/phase6-refused"))
      )
      intercept[IllegalArgumentException](
        ErBatch.run(BatchConfig(f.runRoot, f.base.resolve("target/out")), clock)
      )
      intercept[IllegalArgumentException](
        ErBatch.run(
          BatchConfig(f.runRoot.getParent, f.base.resolve("out")),
          clock
        )
      )
    }
  }

  // ---- the batch reads no label path ----

  test(
    "the batch derives its inputs from the run root and reads no other path"
  ) {
    withFixture { f =>
      val sibling = f.base.resolve("labels/phase5")
      put(sibling.resolve("pairs.jsonl"), Vector("{not json"))
      put(
        f.runRoot.resolve("stage=graph-export/labels/v1/part-r-00000"),
        Vector("{not json")
      )
      val inputs = BatchInputs(f.runRoot)
      assertEquals(
        inputs.all.map(_._1),
        Vector("match-candidates", "works", "author-mentions", "researchers")
      )
      inputs.all.foreach((_, path) =>
        assert(path.startsWith(f.runRoot), path.toString)
      )
      val result = runOnce(f)
      val recorded = result
        .manifest("inputs")("paths")
        .obj
        .values
        .map(_("path").str)
        .toVector
      assertEquals(recorded.size, 4)
      recorded.foreach(p => assert(p.startsWith(f.runRoot.toString), p))
      assertEquals(
        rows(result, "accepted-links/v1/work-links").size,
        f.works.size
      )
    }
  }

  test("the command line accepts only a run root and an out root") {
    assert(ErBatch.parseArgs(Seq("--run-root", "a", "--out-root", "b")).isRight)
    Vector("--pairs", "--records", "--candidates", "--labels", "--input")
      .foreach { option =>
        assert(
          ErBatch
            .parseArgs(Seq("--run-root", "a", "--out-root", "b", option, "x"))
            .isLeft,
          option
        )
      }
    assert(ErBatch.parseArgs(Seq("--run-root", "a")).isLeft)
    assert(ErBatch.parseArgs(Seq("--out-root", "b")).isLeft)
  }

  test(
    "the batch sources reach no evaluation, review or sampling package and no network"
  ) {
    val relative = "modules/entity-resolution/src/main/scala/scholarchain/er"
    val root =
      Vector(Paths.get(relative), Paths.get("src/main/scala/scholarchain/er"))
        .find(Files.isDirectory(_))
        .getOrElse(fail("cannot locate the entity-resolution sources"))
    val text = Vector("ErBatch.scala", "Clusters.scala")
      .map(name => Files.readString(root.resolve(name), UTF_8))
    val forbidden = Vector(
      "er.eval",
      "er.review",
      "er.sampling",
      "java.net",
      "HttpClient",
      "Socket",
      "URLConnection",
      "okhttp",
      "sttp",
      "scalaj"
    )
    forbidden.foreach(word =>
      assert(!text.exists(_.contains(word)), s"batch source mentions $word")
    )
  }

  test(
    "a missing input stage fails the run instead of producing an empty result"
  ) {
    withFixture { f =>
      Files.delete(
        f.runRoot.resolve("stage=graph-export/researchers/v1/part-r-00000")
      )
      intercept[Exception](runOnce(f))
    }
  }

  // ---- the clustering rules on hand-checkable graphs ----

  private def member(
      id: String,
      workId: String = "",
      ids: Map[String, Set[String]] = Map.empty
  ) =
    Member(id, if workId.isEmpty then s"w-$id" else workId, ids)

  private def accepted(a: String, b: String) =
    DecisionEdge(a, b, MatchOutcome.Accepted)

  private def orcids(values: String*) = Map("orcid" -> values.toSet)

  test("a self link is ignored and merges nothing") {
    val result = Clusters.build(
      EntityKind.Researcher,
      Vector(member("a"), member("b")),
      Vector(accepted("a", "a"))
    )
    assertEquals(result.selfLinksIgnored, 1)
    assertEquals(
      result.clusters.map(_.members),
      Vector(Vector("a"), Vector("b"))
    )
  }

  test("CONFLICT, UNRESOLVED and REJECTED decisions never merge") {
    val edges = Vector(
      DecisionEdge("a", "b", MatchOutcome.Conflict),
      DecisionEdge("b", "c", MatchOutcome.Unresolved),
      DecisionEdge("c", "d", MatchOutcome.Rejected)
    )
    val result = Clusters.build(
      EntityKind.Researcher,
      Vector("a", "b", "c", "d").map(member(_)),
      edges
    )
    assertEquals(result.clusters.map(_.size), Vector(1, 1, 1, 1))
    assert(result.clusters.forall(!_.failed))
  }

  test("accepted pairs close transitively whatever the edge order") {
    val edges =
      Vector(accepted("a", "b"), accepted("c", "b"), accepted("d", "e"))
    val members = Vector("a", "b", "c", "d", "e", "f").map(member(_))
    val forward = Clusters.build(EntityKind.Work, members, edges)
    val backward =
      Clusters.build(EntityKind.Work, members.reverse, edges.reverse)
    assertEquals(forward, backward)
    assertEquals(
      forward.clusters.map(c => c.anchor -> c.members),
      Vector(
        "a" -> Vector("a", "b", "c"),
        "d" -> Vector("d", "e"),
        "f" -> Vector("f")
      )
    )
    assertEquals(
      forward.clusters.map(_.canonicalId),
      Vector(Some("a"), Some("d"), Some("f"))
    )
  }

  test(
    "a conflict decision inside an accepted cluster is an integrity failure"
  ) {
    val edges = Vector(
      accepted("a", "b"),
      accepted("b", "c"),
      DecisionEdge("a", "c", MatchOutcome.Conflict),
      DecisionEdge("a", "d", MatchOutcome.Conflict)
    )
    val result = Clusters.build(
      EntityKind.Researcher,
      Vector("a", "b", "c", "d").map(member(_)),
      edges
    )
    val cluster = result.clusters.head
    assertEquals(cluster.members, Vector("a", "b", "c"))
    assertEquals(
      cluster.failures.map(_.failureType),
      Vector(FailureType.ConflictDecisionInside)
    )
    assertEquals(cluster.failures.head.detail, Vector("a|c"))
    assertEquals(cluster.status, LinkStatus.IntegrityFailure)
    assertEquals(cluster.canonicalId, None)
    assertEquals(result.conflictInside, 1)
    assertEquals(result.clusters.last.failures, Vector.empty)
  }

  test(
    "two different ORCID iDs in one cluster are an integrity failure with no canonical ID"
  ) {
    val members = Vector(
      member("a", ids = orcids("X")),
      member("b"),
      member("c", ids = orcids("Y"))
    )
    val result = Clusters.build(
      EntityKind.Researcher,
      members,
      Vector(accepted("a", "b"), accepted("b", "c"))
    )
    val cluster = result.clusters.head
    assertEquals(
      cluster.failures.map(_.failureType),
      Vector(FailureType.ConflictingIdentifiers)
    )
    assertEquals(cluster.failures.head.detail, Vector("orcid:X,Y"))
    assertEquals(cluster.canonicalId, None)
    assertEquals(cluster.idRule, None)
  }

  test(
    "identifier sets that overlap on one record agree and disjoint sets conflict"
  ) {
    val overlap = Clusters.build(
      EntityKind.Work,
      Vector(
        member("a", ids = Map("doi" -> Set("x", "y"))),
        member("b", ids = Map("doi" -> Set("y")))
      ),
      Vector(accepted("a", "b"))
    )
    assert(!overlap.clusters.head.failed)
    val disjoint = Clusters.build(
      EntityKind.Work,
      Vector(
        member("a", ids = Map("doi" -> Set("x"))),
        member("b", ids = Map("doi" -> Set("y")))
      ),
      Vector(accepted("a", "b"))
    )
    assert(disjoint.clusters.head.failed)
  }

  test(
    "two mentions of one work record in one cluster are an integrity failure"
  ) {
    val members = Vector(
      member("a", workId = "w1"),
      member("b", workId = "w2"),
      member("c", workId = "w1")
    )
    val result = Clusters.build(
      EntityKind.Researcher,
      members,
      Vector(accepted("a", "b"), accepted("b", "c"))
    )
    val cluster = result.clusters.head
    assertEquals(
      cluster.failures.map(_.failureType),
      Vector(FailureType.SameRecordTwoMentions)
    )
    assertEquals(cluster.failures.head.detail, Vector("w1:a,c"))
  }

  test("one ORCID iD in two clusters that no accepted pair joins fails both") {
    val members = Vector(
      member("a", ids = orcids("X")),
      member("b", ids = orcids("X")),
      member("c", ids = orcids("Z"))
    )
    val result = Clusters.build(EntityKind.Researcher, members, Vector.empty)
    val byAnchor = result.clusters.map(c => c.anchor -> c).toMap
    assertEquals(
      byAnchor("a").failures.map(_.failureType),
      Vector(FailureType.IdentifierSplitAcrossClusters)
    )
    assertEquals(
      byAnchor("b").failures.map(_.failureType),
      Vector(FailureType.IdentifierSplitAcrossClusters)
    )
    assertEquals(byAnchor("a").canonicalId, None)
    assertEquals(byAnchor("c").status, LinkStatus.ExternalId)
  }

  test("the canonical researcher ID rule") {
    val members = Vector(
      member("a", ids = orcids("0000-0002-1825-0097")),
      member("b"),
      member("c"),
      member("d"),
      member("e")
    )
    val result = Clusters.build(
      EntityKind.Researcher,
      members,
      Vector(accepted("a", "b"), accepted("c", "d"))
    )
    val byAnchor = result.clusters.map(c => c.anchor -> c).toMap
    assertEquals(
      byAnchor("a").canonicalId,
      Some(StableIds.researcherFromExternal("orcid", "0000-0002-1825-0097"))
    )
    assertEquals(byAnchor("a").idRule, Some("orcid"))
    assertEquals(byAnchor("a").status, LinkStatus.Merged)
    assertEquals(
      byAnchor("c").canonicalId,
      Some(StableIds.researcherFromIdentity("scholarchain.er.identity/1", "c"))
    )
    assertEquals(byAnchor("c").idRule, Some("scholarchain-identity/1"))
    assertEquals(byAnchor("e").canonicalId, None)
    assertEquals(byAnchor("e").status, LinkStatus.Unlinked)
    assert(byAnchor("a").canonicalId.get.startsWith("researcher:v1:orcid:"))
    assert(byAnchor("c").canonicalId.get.matches("researcher:v1:[0-9a-f]{64}"))
  }
