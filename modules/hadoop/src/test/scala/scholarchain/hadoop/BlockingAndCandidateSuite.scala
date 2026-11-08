package scholarchain.hadoop

import munit.FunSuite
import scholarchain.core.{
  AuthorMention,
  ContractSchema,
  SourceType,
  StableIds,
  WorkRecord
}
import scholarchain.core.ContractWire
import scholarchain.core.pairing.{BlockKind, BlockStrategy, MatchCandidate}
import scholarchain.hadoop.pipeline.{
  BlockMember,
  Blocking,
  BoundedBlockSelector,
  MatchFeatures,
  TextNormalizer
}

/** Blocking and candidate-feature tests.
  *
  * The properties under test are the two that keep job 4 and job 5 safe: blocks
  * are bounded no matter how common a name is, and the retained subset of an
  * oversize block does not depend on the order the shuffle delivered it.
  */
class BlockingAndCandidateSuite extends FunSuite:
  private val run = TestRun.context()

  private def member(
      id: String,
      name: String,
      source: SourceType = SourceType.Dblp,
      year: Option[Int] = Some(2017),
      title: String = "oxpath based data acquisition for dblp",
      coauthors: Vector[String] = Vector.empty,
      externalIds: Map[String, String] = Map.empty,
      workId: String = "work:v1:dblp:AAAA"
  ): BlockMember =
    BlockMember(
      kind = BlockKind.Researcher,
      memberId = id,
      source = source,
      displayName = name,
      normalizedName = name.toLowerCase,
      normalizedTitle = title,
      year = year,
      venueId = Some("venue:v1:dblp:jcdl"),
      affiliations = Vector.empty,
      coauthorNames = coauthors,
      externalIds = externalIds,
      workId = workId
    )

  /** A real two-author work, so coauthor-context blocking can be driven through
    * `Blocking.researcherKeys` rather than by restating its output.
    *
    * The two names are chosen so their name blocks order as `ley|m` <
    * `schenkel|r`. That makes the first author take the `then` branch of the
    * ordering and the second author the `else` branch, so one pair exercises
    * both sides.
    */
  private def twoAuthorWork(
      names: Vector[String] = Vector("Michael Ley", "Ralf Schenkel")
  ): WorkRecord =
    val recordKey = "conf/jcdl/Pair09"
    val workId = StableIds.work("dblp", recordKey)
    val provenance = run.provenance(
      SourceType.Dblp,
      recordKey,
      s"https://dblp.org/rec/$recordKey",
      "dblp"
    )
    WorkRecord(
      schemaVersion = ContractSchema.currentVersion,
      workId = workId,
      source = SourceType.Dblp,
      sourceRecordId = recordKey,
      title = "A Joint Paper.",
      normalizedTitle = "a joint paper",
      year = Some(2009),
      venueId = Some("venue:v1:dblp:jcdl"),
      doi = None,
      arxivId = None,
      authors = names.zipWithIndex.map { case (name, index) =>
        AuthorMention(
          schemaVersion = ContractSchema.currentVersion,
          mentionId = StableIds.mention(workId, index + 1),
          workId = workId,
          authorPosition = index + 1,
          name = name,
          normalizedName = TextNormalizer.nameKey(name),
          externalIds = Map.empty,
          affiliations = Vector.empty,
          provenance = provenance
        )
      },
      abstractTextRef = None,
      electronicEditionUris = Vector.empty,
      sourceUris = Vector.empty,
      recordType = "inproceedings",
      provenance = provenance
    )

  /** The `NameCoauthor` keys one mention derives, given its coauthors. */
  private def coauthorKeys(
      work: WorkRecord,
      mention: AuthorMention
  ): Vector[String] =
    val others =
      work.authors.map(_.normalizedName).filterNot(_ == mention.normalizedName)
    Blocking
      .researcherKeys(mention, work, others)
      .collect { case (BlockStrategy.NameCoauthor, blockKey) => blockKey }

  test("a block key records its kind and strategy") {
    val key =
      Blocking.key(
        BlockStrategy.NameYear,
        BlockKind.Researcher,
        "ley|m",
        "2009"
      )
    assertEquals(key, "researcher|name-year|ley|m|2009")
    assertEquals(Blocking.kindOf(key), Some(BlockKind.Researcher))
    assertEquals(Blocking.strategyOf(key), Some(BlockStrategy.NameYear))
  }

  test("the coauthor block key is the same from either side of the pair") {
    // Derived independently from each mention, so the two sides are genuinely
    // different computations: the first author's name block sorts below its
    // coauthor's and the second author's sorts above, taking opposite branches
    // of the ordering. Without that ordering the two would disagree and the
    // collaborators would never meet in one block.
    val work = twoAuthorWork()
    val fromFirst = coauthorKeys(work, work.authors.head)
    val fromSecond = coauthorKeys(work, work.authors(1))
    assertEquals(fromFirst.size, 1)
    assertEquals(fromSecond.size, 1)
    assertEquals(fromFirst, fromSecond)
    // Pinned exactly, so a change in the ordering or the key layout fails here
    // rather than silently splitting the pair across two blocks.
    assertEquals(fromFirst.head, "researcher|name-coauthor|ley|m|schenkel|r")
  }

  test("the coauthor key is unchanged when the author order is reversed") {
    // Author position must not reach the key. Listing the same two people in
    // the opposite order has to produce the same coauthor block.
    val forward = twoAuthorWork()
    val reversed = twoAuthorWork(Vector("Ralf Schenkel", "Michael Ley"))
    assertEquals(
      coauthorKeys(forward, forward.authors.head),
      coauthorKeys(reversed, reversed.authors(1))
    )
    assertEquals(
      coauthorKeys(forward, forward.authors.head).head,
      "researcher|name-coauthor|ley|m|schenkel|r"
    )
  }

  test("a mention derives one coauthor key per distinct coauthor") {
    val work = twoAuthorWork(
      Vector("Michael Ley", "Ralf Schenkel", "Emanuel Sallinger")
    )
    val keys = coauthorKeys(work, work.authors.head)
    assertEquals(keys.size, 2)
    assertEquals(
      keys.sorted,
      Vector(
        "researcher|name-coauthor|ley|m|sallinger|e",
        "researcher|name-coauthor|ley|m|schenkel|r"
      )
    )
  }

  test("a bounded selector retains the smallest members by ordering") {
    val selector = new BoundedBlockSelector(3)
    val members = (1 to 20).toVector.map(index =>
      member(f"mention-$index%02d", s"Name $index")
    )
    members.foreach(selector.add)
    assertEquals(selector.observed, 20L)
    assertEquals(
      selector.retained.map(_.memberId),
      Vector("mention-01", "mention-02", "mention-03")
    )
  }

  test("the retained subset does not depend on arrival order") {
    val members = (1 to 30).toVector.map(index =>
      member(f"mention-$index%02d", s"Name $index")
    )
    val forward = new BoundedBlockSelector(5)
    members.foreach(forward.add)
    val reversed = new BoundedBlockSelector(5)
    members.reverse.foreach(reversed.add)
    val shuffled = new BoundedBlockSelector(5)
    new scala.util.Random(42).shuffle(members).foreach(shuffled.add)
    assertEquals(forward.retained, reversed.retained)
    assertEquals(forward.retained, shuffled.retained)
    assertEquals(forward.observed, 30L)
  }

  test("a pathological block is truncated, counted and marked") {
    val bounded = TestRun.context(maxBlockSize = 4)
    val members =
      (1 to 50).toVector.map(index => member(f"mention-$index%02d", "Wei Wang"))
    val (group, retained) =
      Blocking.bound("researcher|name-initials|wang|w", members, bounded)
    assertEquals(retained.size, 4)
    assertEquals(group.retainedSize, 4)
    assertEquals(group.observedSize, 50)
    assert(group.truncated)
    assertEquals(group.strategy, BlockStrategy.NameInitials)
    assertEquals(group.memberIds, retained.map(_.memberId))
  }

  test("a block within its bound is not marked truncated") {
    val members =
      (1 to 3).toVector.map(index => member(s"mention-$index", "Michael Ley"))
    val (group, retained) =
      Blocking.bound("researcher|name-year|ley|m|2009", members, run)
    assertEquals(retained.size, 3)
    assert(!group.truncated)
  }

  test("a duplicate member is retained once but counted as a record received") {
    // `observedSize` has one definition pipeline-wide: records the reducer
    // received, duplicates included. It is the load-bearing figure, because the
    // reducer pays for every record delivered whether or not two name the same
    // member. The retained set is still distinct.
    val one = member("mention-1", "Michael Ley")
    val (group, retained) =
      Blocking.bound("researcher|name-year|ley|m|2009", Vector(one, one), run)
    assertEquals(retained.size, 1)
    assertEquals(group.retainedSize, 1)
    assertEquals(group.observedSize, 2)
    // Nothing was dropped for want of room, so this is not a truncation.
    assert(!group.truncated, "a duplicate must not look like truncation")
  }

  test("the reducer path and the whole-block path agree exactly") {
    // Regression guard for two definitions of `observedSize` disagreeing: the
    // reducers call `boundSelected`, so that is the path under test, and it
    // must match the whole-block form member for member and figure for figure.
    val members = (1 to 7).toVector.map(index =>
      member(f"mention-$index%02d", s"Name $index")
    ) ++ Vector(member("mention-03", "Name 3"))
    val bounded = TestRun.context(maxBlockSize = 4)
    val blockKey = "researcher|name-initials|name|n"

    val selector = new BoundedBlockSelector(bounded.maxBlockSize)
    members.foreach(selector.add)
    val (selectedGroup, selectedMembers) =
      Blocking.boundSelected(blockKey, selector, bounded)
    val (wholeGroup, wholeMembers) =
      Blocking.bound(blockKey, members, bounded)

    assertEquals(selectedGroup, wholeGroup)
    assertEquals(selectedMembers, wholeMembers)
    // Eight records received, seven distinct members, four retained.
    assertEquals(selectedGroup.observedSize, 8)
    assertEquals(selectedGroup.retainedSize, 4)
    assert(
      selectedGroup.truncated,
      "members were dropped, so this is truncated"
    )
    assertEquals(selectedMembers.map(_.memberId).distinct.size, 4)
  }

  test("truncation is reported only when a member was actually dropped") {
    // A block exactly at the bound keeps everything, so `truncated` must be
    // false even though the selector's heap reached its limit.
    val bounded = TestRun.context(maxBlockSize = 3)
    val members = (1 to 3).toVector.map(index =>
      member(f"mention-$index%02d", s"Name $index")
    )
    val selector = new BoundedBlockSelector(bounded.maxBlockSize)
    members.foreach(selector.add)
    val (group, retained) =
      Blocking.boundSelected("researcher|name-year|n|n|2009", selector, bounded)
    assertEquals(retained.size, 3)
    assertEquals(group.observedSize, 3)
    assert(!group.truncated)
  }

  test("non-selective strategies are salted and selective ones are not") {
    assert(!BlockStrategy.NameInitials.selective)
    assert(!BlockStrategy.TitlePrefix.selective)
    assert(BlockStrategy.NameYear.selective)
    assert(BlockStrategy.NameCoauthor.selective)
  }

  test("features are finite, bounded and symmetric") {
    val left = member(
      "mention-a",
      "Michael Ley",
      coauthors = Vector("ralf schenkel"),
      externalIds = Map("orcid" -> "0000-0001-7580-4351")
    )
    val right = member(
      "mention-b",
      "Michael Ley",
      source = SourceType.OpenAlex,
      coauthors = Vector("ralf schenkel"),
      externalIds = Map("orcid" -> "0000-0001-7580-4351"),
      workId = "work:v1:openalex:BBBB"
    )
    val features = MatchFeatures.features(left, right)
    val mirrored = MatchFeatures.features(right, left)
    assertEquals(features, mirrored)
    assert(features.values.forall(_.isFinite))
    assertEquals(features("name.exact"), 1.0)
    assertEquals(features("title.exact"), 1.0)
    assertEquals(features("year.exact"), 1.0)
    assertEquals(features("venue.exact"), 1.0)
    assertEquals(features("source.distinct"), 1.0)
    assertEquals(features("coauthor.jaccard"), 1.0)
    assertEquals(features("sameWork"), 0.0)
  }

  test("edit similarity is normalized and symmetric") {
    assertEquals(MatchFeatures.editSimilarity("", ""), 1.0)
    assertEquals(MatchFeatures.editSimilarity("abc", ""), 0.0)
    assertEquals(MatchFeatures.editSimilarity("abc", "abc"), 1.0)
    assertEquals(
      MatchFeatures.editSimilarity("michael ley", "micheal ley"),
      MatchFeatures.editSimilarity("micheal ley", "michael ley")
    )
    assert(MatchFeatures.editSimilarity("abc", "abd") > 0.6)
  }

  test("jaccard handles the empty case without dividing by zero") {
    assertEquals(MatchFeatures.jaccard(Set.empty, Set("a")), 0.0)
    assertEquals(MatchFeatures.jaccard(Set("a"), Set("a")), 1.0)
    assertEquals(MatchFeatures.jaccard(Set("a", "b"), Set("b", "c")), 1.0 / 3.0)
  }

  test("identifier agreement and conflict are reported apart") {
    val (agree, conflict) = MatchFeatures.identifierComparison(
      Map("orcid" -> "0000-0001-7580-4351", "doi" -> "10.1/a"),
      Map("orcid" -> "0000-0001-7580-4351", "doi" -> "10.1/b")
    )
    assertEquals(agree, Vector("orcid"))
    assertEquals(conflict, Vector("doi"))
  }

  test("two mentions on one paper are never a researcher candidate") {
    // Two distinct authors of the same work are by construction different
    // people, so comparing them would manufacture a false positive.
    val left = member("mention-a", "Michael Ley", workId = "work:v1:dblp:SAME")
    val right = member("mention-b", "Michael Ley", workId = "work:v1:dblp:SAME")
    assertEquals(MatchFeatures.candidate("block", left, right, run), None)
  }

  test("a member is never compared with itself") {
    val one = member("mention-a", "Michael Ley")
    assertEquals(MatchFeatures.candidate("block", one, one, run), None)
  }

  test(
    "candidates are emitted in ascending ID order regardless of argument order"
  ) {
    val left = member("mention-a", "Michael Ley", workId = "work:v1:dblp:A")
    val right = member("mention-b", "Michael Ley", workId = "work:v1:dblp:B")
    val forward = MatchFeatures.candidate("block", left, right, run)
    val reversed = MatchFeatures.candidate("block", right, left, run)
    assertEquals(forward, reversed)
    assertEquals(forward.map(_.leftId), Some("mention-a"))
    assertEquals(forward.map(_.rightId), Some("mention-b"))
  }

  test("a block of n members yields n * (n - 1) / 2 candidates") {
    val members = (1 to 5).toVector.map(index =>
      member(s"mention-$index", "Michael Ley", workId = s"work:v1:dblp:W$index")
    )
    val candidates = MatchFeatures.candidates("block", members, run)
    assertEquals(candidates.size, 10)
    assertEquals(
      candidates.map(c => (c.leftId, c.rightId)).distinct.size,
      10
    )
  }

  test("a candidate carries no decision and no score") {
    val members = Vector(
      member("mention-a", "Michael Ley", workId = "work:v1:dblp:A"),
      member("mention-b", "Michael Ley", workId = "work:v1:dblp:B")
    )
    val candidate = MatchFeatures.candidates("block", members, run).head
    assertEquals(candidate.blockKey, "block")
    assertEquals(candidate.kind, BlockKind.Researcher)
    assertEquals(candidate.provenance.runId, TestRun.runId)
    assert(candidate.features.nonEmpty)
    // No field on the record can express an accept or reject outcome.
    assert(!candidate.features.contains("decision"))
  }

  private val frozenWire =
    """{"schemaVersion":1,"recordType":"scholarchain.hadoop.MatchCandidate/v1","kind":"researcher","leftId":"mention:v1:0000000000000000000000000000000000000000000000000000000000000001","rightId":"mention:v1:0000000000000000000000000000000000000000000000000000000000000002","leftSource":"dblp","rightSource":"openalex","blockKey":"researcher|name-initials|smith|j","blockStrategy":"name-initials","exactIdentifierAgreements":["orcid"],"exactIdentifierConflicts":["doi"],"features":{"name.exact":1,"title.jaccard":0.5,"coauthor.sharedCount":2},"provenance":{"schemaVersion":1,"source":"derived","snapshotId":"fixture-v1","sourceRecordId":"a|b","sourceUri":"scholarchain:block/researcher|name-initials|smith|j","transformVersion":"candidate-features/1","runId":"run:v1:550e8400-e29b-41d4-a716-446655440000"}}"""

  test("the Hadoop candidate output is the frozen Phase 4 wire shape") {
    // The record type is the core one, so this is the proof that moving it
    // changed no byte: the frozen Phase 4 example round-trips exactly, and a
    // candidate this module builds encodes with the identical field sequence
    // and the identical record-type string.
    assertEquals(
      ContractWire.encode(ContractWire.decode[MatchCandidate](frozenWire)),
      frozenWire
    )
    val members = (1 to 2).toVector.map(index =>
      member(s"mention-$index", "Michael Ley", workId = s"work:v1:dblp:W$index")
    )
    val built = MatchFeatures.candidates("block", members, run).head
    assertEquals(built.recordType, "scholarchain.hadoop.MatchCandidate/v1")
    val builtJson = ContractWire.encode(built)
    assertEquals(
      ujson.read(builtJson).obj.keys.toVector,
      ujson.read(frozenWire).obj.keys.toVector
    )
    assertEquals(
      ujson.read(builtJson)("provenance").obj.keys.toVector,
      ujson.read(frozenWire)("provenance").obj.keys.toVector
    )
    assertEquals(ContractWire.decode[MatchCandidate](builtJson), built)
  }

  test("candidate construction is deterministic") {
    val members = (1 to 4).toVector.map(index =>
      member(s"mention-$index", "Michael Ley", workId = s"work:v1:dblp:W$index")
    )
    assertEquals(
      MatchFeatures.candidates("block", members, run),
      MatchFeatures.candidates("block", members, run)
    )
  }
