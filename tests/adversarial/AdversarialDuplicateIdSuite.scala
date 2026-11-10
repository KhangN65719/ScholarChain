package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.core.SourceType
import scholarchain.hadoop.pipeline.ExactIdentifiers
import scholarchain.hadoop.pipeline.IdNamespace
import scholarchain.hadoop.pipeline.IdentifierClaim
import scholarchain.hadoop.pipeline.IdentifierJoin
import scholarchain.hadoop.pipeline.TeamExpansion

/** Duplicate identifiers: the same DBLP key, DOI or ORCID arriving more than
  * once, within one source and across sources, with agreeing and with
  * conflicting payloads.
  *
  * `IdentifierJoin.resolve` is where the specification's duplicate and conflict
  * counters come from, and the reducer increments them from exactly the triple
  * this suite asserts on. No module suite drives it, so these are the first
  * cases over that function.
  */
class AdversarialDuplicateIdSuite extends FunSuite:
  private val run = AdversarialFixtures.run()

  private def claim(
      namespace: IdNamespace,
      value: String,
      entityId: String,
      source: SourceType = SourceType.Dblp,
      sourceRecordId: String = "journals/x/A09",
      title: String = "a shared title"
  ): IdentifierClaim =
    IdentifierClaim(
      namespace = namespace.wire,
      value = value,
      entityId = entityId,
      source = source,
      sourceRecordId = sourceRecordId,
      normalizedTitle = title,
      year = Some(2009)
    )

  test("contradictory native keys report one duplicate and one conflict") {
    val natives = Vector(
      (SourceType.Dblp, IdNamespace.DblpKey, "journals/x/A09"),
      (SourceType.OpenAlex, IdNamespace.OpenAlexWork, "W1"),
      (SourceType.SemanticScholar, IdNamespace.SemanticScholar, "abc123"),
      (SourceType.Crossref, IdNamespace.Doi, "10.1000/native"),
      (SourceType.Arxiv, IdNamespace.Arxiv, "2401.00001")
    )
    natives.foreach { case (source, namespace, value) =>
      val key = ExactIdentifiers.joinKey(namespace, value)
      val first = claim(namespace, value, "work:1", source, value)
      val second = first.copy(normalizedTitle = "a contradictory title")
      val nativeRun = run.copy(datasetSnapshots =
        run.datasetSnapshots.updated(source.wire, "test-snapshot")
      )
      val forward =
        IdentifierJoin.resolve(key, Vector(first, second), nativeRun)
      val reverse =
        IdentifierJoin.resolve(key, Vector(second, first), nativeRun)
      assertEquals(forward, reverse)
      val (record, duplicates, conflicts) =
        forward.getOrElse(fail(s"resolve returned nothing for $source"))
      assertEquals(duplicates, 1L)
      assertEquals(conflicts, 1L)
      assertEquals(
        record.conflictingTitles,
        Vector("a contradictory title", "a shared title")
      )
      assertEquals(record.duplicateSources, Vector(source.wire))
      assertEquals(record.entityIds, Vector("work:1"))
    }
  }

  test("a fivefold replay reports four duplicates without a conflict") {
    val key = ExactIdentifiers.joinKey(IdNamespace.DblpKey, "journals/x/A09")
    val replayed =
      Vector.fill(5)(claim(IdNamespace.DblpKey, "journals/x/A09", "work:1"))
    val resolved = IdentifierJoin.resolve(key, replayed, run)
    val (record, duplicates, conflicts) =
      resolved.getOrElse(fail("resolve returned nothing"))
    assertEquals(duplicates, 4L)
    assertEquals(conflicts, 0L)
    assertEquals(record.duplicateSources, Vector("dblp"))
    assertEquals(record.sources, Vector("dblp"))
    assertEquals(record.entityIds.size, 1)
  }

  test("a DOI claimed by two works in one source is a duplicate") {
    // The path that does work, and the contrast that isolates the defect
    // above: here the two claims differ in source record ID, so the
    // deduplication leaves both and both findings are raised.
    val key = ExactIdentifiers.joinKey(IdNamespace.Doi, "10.1000/a")
    val left = claim(
      IdNamespace.Doi,
      "10.1000/a",
      "work:a",
      sourceRecordId = "conf/x/A17",
      title = "first title"
    )
    val right = claim(
      IdNamespace.Doi,
      "10.1000/a",
      "work:b",
      sourceRecordId = "conf/x/B17",
      title = "second title"
    )
    val resolved = IdentifierJoin.resolve(key, Vector(left, right), run)
    val (record, duplicates, conflicts) =
      resolved.getOrElse(fail("resolve returned nothing"))
    assertEquals(duplicates, 1L)
    assertEquals(record.duplicateSources, Vector("dblp"))
    assertEquals(
      record.conflictingTitles,
      Vector("first title", "second title")
    )
    assertEquals(conflicts, 1L)
  }

  test("three disagreeing titles count as two title disagreements") {
    val key = ExactIdentifiers.joinKey(IdNamespace.Doi, "10.1000/b")
    val claims = Vector("t one", "t two", "t three").zipWithIndex.map {
      case (title, index) =>
        claim(
          IdNamespace.Doi,
          "10.1000/b",
          s"work:$index",
          sourceRecordId = s"conf/x/$index",
          title = title
        )
    }
    val resolved = IdentifierJoin.resolve(key, claims, run)
    val (record, _, conflicts) =
      resolved.getOrElse(fail("resolve returned nothing"))
    assertEquals(record.conflictingTitles.size, 3)
    assertEquals(conflicts, 2L)
  }

  test(
    "a prolific ORCID is correctly not a finding and incorrectly unbounded"
  ) {
    // The scope split is right: one ORCID on 500 papers from one source is
    // one researcher, not 500 duplicates, and the differing titles are not
    // conflicts. What is missing is a bound. `resolve` buffers every claim
    // and writes every entity ID into one record, so a hot person
    // identifier gives a single reducer unbounded memory and a single
    // output record of unbounded width, with no counter and no truncation.
    // Blocking has `maxBlockSize` for exactly this shape of hazard; the
    // identifier join has nothing. Owner hadoop-engineer.
    // 500 is the bound chosen here: large enough that the linear growth is
    // unmistakable, small enough to run in milliseconds. A real ORCID with
    // a thousand works is ordinary.
    val value = "0000-0001-7580-4351"
    val key = ExactIdentifiers.joinKey(IdNamespace.Orcid, value)
    val claims = (1 to 500).toVector.map { index =>
      claim(
        IdNamespace.Orcid,
        value,
        s"mention:$index",
        sourceRecordId = s"conf/x/P$index",
        title = s"title $index"
      )
    }
    val resolved = IdentifierJoin.resolve(key, claims, run)
    val (record, duplicates, conflicts) =
      resolved.getOrElse(fail("resolve returned nothing"))
    assertEquals(duplicates, 0L)
    assertEquals(conflicts, 0L)
    assertEquals(record.conflictingTitles, Vector.empty)
    assertEquals(record.entityIds.size, 500)
  }

  test("two sources agreeing on a DOI is a clean cross-source join") {
    val key = ExactIdentifiers.joinKey(IdNamespace.Doi, "10.1000/c")
    val fromDblp = claim(
      IdNamespace.Doi,
      "10.1000/c",
      "work:dblp",
      sourceRecordId = "conf/x/C17"
    )
    val fromOpenAlex = claim(
      IdNamespace.Doi,
      "10.1000/c",
      "work:openalex",
      source = SourceType.OpenAlex,
      sourceRecordId = "W1"
    )
    val claims = Vector(fromOpenAlex, fromDblp)
    val resolved = IdentifierJoin.resolve(key, claims, run)
    val (record, duplicates, conflicts) =
      resolved.getOrElse(fail("resolve returned nothing"))
    assertEquals(duplicates, 0L)
    assertEquals(conflicts, 0L)
    assertEquals(record.sources, Vector("dblp", "openalex"))
    assertEquals(record.entityIds, Vector("work:dblp", "work:openalex"))
  }

  test("an unknown namespace resolves to nothing, not to a mislabelled key") {
    val claims = Vector(claim(IdNamespace.Doi, "10.1000/d", "work:d"))
    assertEquals(IdentifierJoin.resolve("nosuch|10.1000/d", claims, run), None)
    assertEquals(IdentifierJoin.resolve("", claims, run), None)
  }

  test("an identifier value containing the delimiter survives the key") {
    val key = ExactIdentifiers.joinKey(IdNamespace.Doi, "10.1000/a|b")
    assertEquals(key, "doi|10.1000/a|b")
    assertEquals(
      ExactIdentifiers.parseJoinKey(key),
      Some(IdNamespace.Doi -> "10.1000/a|b")
    )
    val claims = Vector(claim(IdNamespace.Doi, "10.1000/a|b", "work:e"))
    val resolved = IdentifierJoin.resolve(key, claims, run)
    val (record, _, _) = resolved.getOrElse(fail("resolve returned nothing"))
    assertEquals(record.value, "10.1000/a|b")
  }

  test("one ORCID on two mentions of one work keeps a claim per mention") {
    // Formerly pinned the collapse to the first mention. Two authors of one
    // paper sharing an ORCID is a source error; each mention now keeps its own
    // claim so the reducer can report the disagreement.
    val base = AdversarialFixtures.work("dup/1", Vector("A One", "B Two"))
    val shared = "0000-0001-7580-4351"
    val authors =
      base.authors.map(_.copy(externalIds = Map("orcid" -> shared)))
    val subject = base.copy(authors = authors)
    val claims = IdentifierJoin.claims(subject, Vector.empty)
    val orcidClaims = claims.filter(_._2.namespace == "orcid")
    assertEquals(orcidClaims.size, 2)
    assertEquals(
      orcidClaims.map(_._2.entityId).toSet,
      authors.map(_.mentionId).toSet
    )
  }

  test(
    "pair consolidation keeps the lowest run ID whatever the arrival order"
  ) {
    // Formerly pinned the order dependence: for two records of one work the
    // source record ID tied and the first-delivered record was kept. The
    // retention order now breaks the tie on run ID, so both arrival orders
    // retain the same record, the one from the lower run ID.
    val subject = AdversarialFixtures.work("pair/1", Vector("A One", "B Two"))
    val pair = TeamExpansion.expand(subject, run).pairs.head
    val replayRun =
      AdversarialFixtures.run(runIdentifier = AdversarialFixtures.secondRunId)
    val replayProvenance = replayRun.provenance(
      SourceType.Dblp,
      "pair/1",
      "https://example.org/rec/pair/1",
      "dblp"
    )
    val replay = pair.copy(provenance = replayProvenance)
    val (forward, duplicates) =
      TeamExpansion.consolidate("k", Vector(pair, replay))
    val (reversed, _) = TeamExpansion.consolidate("k", Vector(replay, pair))
    assertEquals(duplicates, 1L)
    assertEquals(forward.size, 1)
    assertEquals(reversed.size, 1)
    assertEquals(forward.head.provenance.runId, AdversarialFixtures.runId)
    assertEquals(reversed.head.provenance.runId, AdversarialFixtures.runId)
    assertEquals(forward, reversed)
  }

  test("pair consolidation is independent of replay arrival order") {
    val subject = AdversarialFixtures.work("pair/1", Vector("A One", "B Two"))
    val pair = TeamExpansion.expand(subject, run).pairs.head
    val replayRun =
      AdversarialFixtures.run(runIdentifier = AdversarialFixtures.secondRunId)
    val replay = pair.copy(provenance =
      replayRun.provenance(
        SourceType.Dblp,
        "pair/1",
        "https://example.org/rec/pair/1",
        "dblp"
      )
    )
    assertEquals(
      TeamExpansion.consolidate("k", Vector(pair, replay)),
      TeamExpansion.consolidate("k", Vector(replay, pair))
    )
  }

  test("shared ORCID on two authors is reported as a conflict") {
    val base = AdversarialFixtures.work("dup/1", Vector("A One", "B Two"))
    val authors = base.authors.map(
      _.copy(externalIds = Map("orcid" -> "0000-0001-7580-4351"))
    )
    val result =
      scholarchain.hadoop.pipeline.LocalPipelineRunner.joinIdentifiers(
        Vector(base.copy(authors = authors)),
        run
      )
    assert(result.counters.toMap.getOrElse("ConflictRecords", 0L) > 0L)
  }
