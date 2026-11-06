package scholarchain.core

import munit.FunSuite

class ContractSuite extends FunSuite:
  private val version = ContractSchema.currentVersion
  private val run = "run:v1:550e8400-e29b-41d4-a716-446655440000"
  private val provenance =
    Provenance(
      version,
      SourceType.Dblp,
      "fixture-v1",
      "fixture/work",
      "https://example.invalid/work",
      "normalize-v1",
      run
    )
  private val workId = StableIds.work("dblp", "fixture/work")
  private val work = WorkRecord(
    version,
    workId,
    SourceType.Dblp,
    "fixture/work",
    "Example Work",
    "example work",
    Some(2024),
    None,
    None,
    None,
    Vector.empty,
    None,
    Vector.empty,
    Vector.empty,
    "article",
    provenance
  )
  private val researcherA = StableIds.researcherFromExternal("openalex", "A1")
  private val researcherB = StableIds.researcherFromExternal("openalex", "A2")
  private val window = YearWindow(2020, 2024)
  private val coauthor = CoauthorEdge(
    version,
    StableIds.coauthorEdge(researcherA, researcherB, window, "policy-v1"),
    researcherA,
    researcherB,
    window,
    "policy-v1",
    1,
    2024,
    2024,
    Vector(workId),
    provenance
  )
  private val matchDecision = MatchDecision(
    version,
    "mention-source",
    researcherA,
    Map("exact-id" -> 1.0),
    Vector("validated-id"),
    MatchOutcome.Accepted,
    1.0,
    Vector("EXACT_ID"),
    "policy-v1",
    None,
    None,
    "2024-01-01T00:00:00Z",
    run,
    provenance
  )
  private val otherWorkId = StableIds.work("dblp", "other/work")
  private val mention1Id = StableIds.mention(workId, 1)
  private val mention2Id = StableIds.mention(workId, 2)
  private val metrics = ResearcherMetrics(
    version,
    researcherA,
    window,
    12L,
    7L,
    19L,
    2.5,
    5L,
    Vector(workId),
    3.25,
    "component-1",
    42L,
    Some("community-3"),
    Some(0.5),
    Some(2),
    provenance
  )

  private def authorMention(position: Int, name: String): AuthorMention =
    AuthorMention(
      version,
      StableIds.mention(workId, position),
      workId,
      position,
      name,
      name.toLowerCase,
      Map("dblp" -> s"fixture/author/$position"),
      Vector("Example University"),
      provenance
    )

  private def rejects(expected: String)(action: => Any): Unit =
    val text = Rejection.textOf(action)
    assert(
      text.contains(expected),
      s"Expected a rejection mentioning '$expected' but got: '$text'"
    )

  private val provenanceWire =
    """{"schemaVersion":1,"source":"dblp","snapshotId":"fixture-v1","sourceRecordId":"fixture/work","sourceUri":"https://example.invalid/work","transformVersion":"normalize-v1","runId":"run:v1:550e8400-e29b-41d4-a716-446655440000"}"""
  private val workWire =
    s"""{"schemaVersion":1,"workId":"work:v1:dblp:Zml4dHVyZS93b3Jr","source":"dblp","sourceRecordId":"fixture/work","title":"Example Work","normalizedTitle":"example work","year":2024,"venueId":null,"doi":null,"arxivId":null,"authors":[],"abstractTextRef":null,"electronicEditionUris":[],"sourceUris":[],"recordType":"article","provenance":$provenanceWire}"""
  private val matchWire =
    s"""{"schemaVersion":1,"sourceId":"mention-source","targetId":"researcher:v1:openalex:QTE","features":{"exact-id":1},"rulesFired":["validated-id"],"decision":"ACCEPTED","confidence":1,"reasonCodes":["EXACT_ID"],"policyVersion":"policy-v1","modelVersion":null,"promptVersion":null,"decidedAt":"2024-01-01T00:00:00Z","runId":"$run","provenance":$provenanceWire}"""
  private val mentionWire =
    s"""{"schemaVersion":1,"mentionId":"$mention1Id","workId":"work:v1:dblp:Zml4dHVyZS93b3Jr","authorPosition":1,"name":"Ada Lovelace","normalizedName":"ada lovelace","externalIds":{"dblp":"fixture/author/1"},"affiliations":["Example University"],"provenance":$provenanceWire}"""

  test(
    "freeze work and match-decision wire shape, values, and option representation"
  ):
    assertEquals(ujson.read(ContractWire.encode(work)), ujson.read(workWire))
    assertEquals(ContractWire.decode[WorkRecord](workWire), work)
    assertEquals(
      ujson.read(ContractWire.encode(matchDecision)),
      ujson.read(matchWire)
    )
    assertEquals(ContractWire.decode[MatchDecision](matchWire), matchDecision)

  test("reject absent, null, incomplete and unversioned provenance"):
    val missing = ujson.read(workWire)
    missing.obj.remove("provenance")
    val explicitNull = ujson.read(workWire)
    explicitNull("provenance") = ujson.Null
    val blank = ujson.read(workWire)
    blank("provenance")("sourceRecordId") = ujson.Str(" ")
    val versionless = ujson.read(workWire)
    versionless("provenance").obj.remove("schemaVersion")
    Vector(missing, explicitNull, blank, versionless).foreach { malformed =>
      intercept[Exception](
        ContractWire.decode[WorkRecord](ujson.write(malformed))
      )
    }
    intercept[Exception](ContractWire.decode[WorkRecord]("null"))

  test("reject absent and unsupported top-level and nested versions"):
    val missing = ujson.read(workWire)
    missing.obj.remove("schemaVersion")
    intercept[Exception](ContractWire.decode[WorkRecord](ujson.write(missing)))
    intercept[Exception](
      ContractWire.decode[WorkRecord](
        workWire.replaceFirst("schemaVersion\":1", "schemaVersion\":2")
      )
    )
    val nested = ujson.read(workWire)
    nested("provenance")("schemaVersion") = ujson.Num(2)
    intercept[Exception](ContractWire.decode[WorkRecord](ujson.write(nested)))

  test("reject fractional and text schema versions"):
    Vector(ujson.Num(1.5), ujson.Str("1")).foreach { invalid =>
      val document = ujson.read(workWire)
      document("schemaVersion") = invalid
      intercept[Exception](
        ContractWire.decode[WorkRecord](ujson.write(document))
      )
    }

  test("reject malformed stable IDs including noncanonical base64url"):
    Vector(
      "fixture/work",
      "work:v9:dblp:QQ",
      "work:v1:dblp:QR",
      "work:v1:dblp:",
      "researcher:v1:dblp:QQ"
    )
      .foreach { invalid =>
        val document = ujson.read(workWire)
        document("workId") = ujson.Str(invalid)
        intercept[Exception](
          ContractWire.decode[WorkRecord](ujson.write(document))
        )
      }
    intercept[IllegalArgumentException](provenance.copy(runId = "run:v1:now"))

  test("reject invalid confidence through constructors and wire decoding"):
    Vector(-0.01, 1.01, Double.PositiveInfinity, Double.NaN).foreach { value =>
      intercept[IllegalArgumentException](
        matchDecision.copy(confidence = value)
      )
    }
    val document = ujson.read(matchWire)
    document("confidence") = ujson.Num(1.01)
    intercept[Exception](
      ContractWire.decode[MatchDecision](ujson.write(document))
    )
    assertEquals(matchDecision.copy(confidence = 0).confidence, 0.0)

  test(
    "canonical edge key is independent of pair order, but the contract requires sorted distinct IDs"
  ):
    assertEquals(
      StableIds.coauthorEdge(researcherB, researcherA, window, "policy-v1"),
      coauthor.edgeId
    )
    assertNotEquals(
      StableIds.coauthorEdge(researcherA, researcherB, window, "policy-v2"),
      coauthor.edgeId
    )
    assertNotEquals(
      StableIds.coauthorEdge(
        researcherA,
        researcherB,
        YearWindow(2019, 2024),
        "policy-v1"
      ),
      coauthor.edgeId
    )
    assertEquals(
      ContractWire.decode[CoauthorEdge](ContractWire.encode(coauthor)),
      coauthor
    )
    val reversed = ujson.read(ContractWire.encode(coauthor))
    reversed("researcherA") = ujson.Str(researcherB)
    reversed("researcherB") = ujson.Str(researcherA)
    intercept[Exception](
      ContractWire.decode[CoauthorEdge](ujson.write(reversed))
    )
    intercept[IllegalArgumentException](
      coauthor.copy(researcherB = researcherA)
    )

  test("candidate pairs sort mention IDs while preserving author positions"):
    val first = StableIds.mention(workId, 1)
    val second = StableIds.mention(workId, 2)
    val (a, b) = StableIds.orderedPair(first, second)
    val (positionA, positionB) = if a == first then (1, 2) else (2, 1)
    val candidate = CoauthorPairCandidate(
      version,
      workId,
      a,
      b,
      positionA,
      positionB,
      Some(2024),
      provenance
    )
    assertEquals(
      ContractWire.decode[CoauthorPairCandidate](
        ContractWire.encode(candidate)
      ),
      candidate
    )
    intercept[IllegalArgumentException](
      candidate.copy(
        mentionA = b,
        mentionB = a,
        authorPositionA = positionB,
        authorPositionB = positionA
      )
    )
    intercept[IllegalArgumentException](
      candidate.copy(authorPositionB = positionA)
    )

  test(
    "reject reversed year windows through the wire and invalid edge observation years"
  ):
    val document = ujson.read(ContractWire.encode(coauthor))
    document("window")("startYear") = ujson.Num(2025)
    intercept[Exception](
      ContractWire.decode[CoauthorEdge](ujson.write(document))
    )
    intercept[IllegalArgumentException](
      coauthor.copy(firstCollaborationYear = 2019)
    )
    assert(YearWindow(2024, 2024).contains(2024))

  test(
    "stable internal IDs use framed fields and bind identity, text, versions, and graph evidence"
  ):
    assertNotEquals(
      StableIds.researcherFromIdentity("a:b", "c"),
      StableIds.researcherFromIdentity("a", "b:c")
    )
    val chunk = StableIds.chunk(workId, "s1", "abstract", Some(0), "α")
    assertEquals(chunk, StableIds.chunk(workId, "s1", "abstract", Some(0), "α"))
    assertNotEquals(
      chunk,
      StableIds.chunk(workId, "s1", "abstract", Some(0), "β")
    )
    assertNotEquals(chunk, StableIds.chunk(workId, "s1", "abstract", None, "α"))
    val e1 = StableIds.evidence(
      SourceType.Dblp,
      "s1",
      workId,
      EvidenceType.Publication,
      "row1"
    )
    val e2 = StableIds.evidence(
      SourceType.Dblp,
      "s1",
      workId,
      EvidenceType.Publication,
      "row2"
    )
    assertNotEquals(
      e1,
      StableIds.evidence(
        SourceType.Crossref,
        "s1",
        workId,
        EvidenceType.Publication,
        "row1"
      )
    )
    val edge = GraphEdgeType.CoauthoredWith
    val both =
      StableIds.graphEdge(edge, researcherA, researcherB, "v1", Vector(e1, e2))
    assertEquals(
      both,
      StableIds.graphEdge(
        edge,
        researcherB,
        researcherA,
        "v1",
        Vector(e2, e1, e2)
      )
    )
    assertNotEquals(
      both,
      StableIds.graphEdge(edge, researcherA, researcherB, "v1", Vector(e1))
    )
    assertNotEquals(
      StableIds.graphEdge(
        GraphEdgeType.DerivedFrom,
        researcherA,
        researcherB,
        "v1",
        Vector(e1)
      ),
      StableIds.graphEdge(
        GraphEdgeType.DerivedFrom,
        researcherB,
        researcherA,
        "v1",
        Vector(e1)
      )
    )

  test(
    "stable IDs reject isolated UTF-16 surrogates and preserve supplementary characters"
  ):
    val high = 0xd83d.toChar.toString
    val low = 0xde00.toChar.toString
    Vector(high, low, low + high).foreach { malformed =>
      intercept[IllegalArgumentException](StableIds.work("dblp", malformed))
      intercept[IllegalArgumentException](
        StableIds.researcherFromIdentity("v1", malformed)
      )
      intercept[IllegalArgumentException](
        StableIds.chunk(workId, "s1", "abstract", None, malformed)
      )
    }
    val valid = high + low
    assertEquals(StableIds.work("dblp", valid), "work:v1:dblp:8J-YgA")
    assertNotEquals(
      StableIds.researcherFromIdentity("v1", valid),
      StableIds.researcherFromIdentity("v1", "?")
    )
    assertNotEquals(
      StableIds.chunk(workId, "s1", "abstract", None, valid),
      StableIds.chunk(workId, "s1", "abstract", None, "?")
    )

  test(
    "run IDs are random UUIDv4 and the immutable manifest preserves the run and snapshot IDs"
  ):
    val first = StableIds.newRun()
    val second = StableIds.newRun()
    assertNotEquals(first, second)
    val manifest = RunManifest(
      version,
      first,
      Map("dblp" -> "fixture-v1"),
      "config-v1",
      provenance.copy(runId = first)
    )
    assertEquals(
      ContractWire.decode[RunManifest](ContractWire.encode(manifest)),
      manifest
    )
    intercept[IllegalArgumentException](manifest.copy(runId = second))

  test("claims resolve to evidence in the wire response"):
    val id = StableIds.evidence(
      SourceType.Dblp,
      "fixture-v1",
      "fixture/work",
      EvidenceType.Publication,
      "row1"
    )
    val evidence = EvidenceItem(
      version,
      id,
      EvidenceType.Publication,
      Some(workId),
      "https://example.invalid/work",
      None,
      None,
      3.5,
      Some(0.8),
      None,
      None,
      provenance
    )
    val bundle =
      EvidenceBundle(version, "bundle1", Vector(evidence), None, provenance)
    val claim = AnswerClaim(
      version,
      "claim1",
      "Example claim",
      Vector(id),
      VerificationStatus.Verified,
      None,
      provenance
    )
    val response = AnswerResponse(
      version,
      "query1",
      "Example claim",
      Vector(claim),
      bundle,
      provenance
    )
    assertEquals(
      ContractWire.decode[AnswerResponse](ContractWire.encode(response)),
      response
    )
    intercept[IllegalArgumentException](claim.copy(evidenceIds = Vector.empty))
    intercept[IllegalArgumentException](
      response.copy(evidence = bundle.copy(items = Vector.empty))
    )

  test("reject unknown enum wire values"):
    val document = ujson.read(matchWire)
    document("decision") = ujson.Str("accepted")
    intercept[Exception](
      ContractWire.decode[MatchDecision](ujson.write(document))
    )

  test("coauthor graph edges require matching collaboration statistics"):
    val evidence = StableIds.evidence(
      SourceType.Dblp,
      "fixture-v1",
      "fixture/work",
      EvidenceType.Publication,
      "row"
    )
    val kind = GraphEdgeType.CoauthoredWith
    val policy = coauthor.policyVersion
    val id = StableIds.graphEdge(
      kind,
      researcherA,
      researcherB,
      policy,
      Vector(evidence)
    )
    val edge = GraphEdge(
      version,
      id,
      researcherA,
      kind,
      researcherB,
      Map.empty,
      policy,
      Vector(evidence),
      Some(coauthor),
      provenance
    )
    assertEquals(
      ContractWire.decode[GraphEdge](ContractWire.encode(edge)),
      edge
    )
    intercept[IllegalArgumentException](edge.copy(coauthorship = None))

  test(
    "work records bind author mentions to the work and its one-based sequence"
  ):
    val first = authorMention(1, "Ada Lovelace")
    val second = authorMention(2, "Grace Hopper")
    val team = work.copy(authors = Vector(first, second))
    assertEquals(
      ContractWire.decode[WorkRecord](ContractWire.encode(team)),
      team
    )
    assertEquals(team.authors.map(_.authorPosition), Vector(1, 2))
    rejects("one-based author sequence")(
      work.copy(authors = Vector(second, first))
    )
    rejects("one-based author sequence")(work.copy(authors = Vector(second)))
    rejects("one-based author sequence")(
      work.copy(authors = Vector(first, first))
    )
    val foreign = AuthorMention(
      version,
      StableIds.mention(otherWorkId, 1),
      otherWorkId,
      1,
      "Ada Lovelace",
      "ada lovelace",
      Map.empty,
      Vector.empty,
      provenance
    )
    rejects("Authors must belong to this work")(
      work.copy(authors = Vector(foreign))
    )
    val doc = ujson.read(ContractWire.encode(team))
    doc("authors")(1)("authorPosition") = ujson.Num(1)
    intercept[Exception](ContractWire.decode[WorkRecord](ujson.write(doc)))
    // The original fixture carried no authors, so the coupling above was
    // vacuously satisfied. An empty sequence stays valid for sources that
    // record no authorship.
    assertEquals(work.authors, Vector.empty[AuthorMention])

  test("freeze author-mention wire shape, values, and collection members"):
    val first = authorMention(1, "Ada Lovelace")
    assertEquals(
      ujson.read(ContractWire.encode(first)),
      ujson.read(mentionWire)
    )
    assertEquals(ContractWire.decode[AuthorMention](mentionWire), first)
    assert(mention1Id.matches("mention:v1:[0-9a-f]{64}"))
    assertNotEquals(mention1Id, mention2Id)
    rejects("Mention ID must match work and author position")(
      first.copy(authorPosition = 2)
    )
    rejects("Mention ID must match work and author position")(
      first.copy(workId = otherWorkId)
    )
    rejects("Malformed mention identifier")(first.copy(mentionId = workId))
    rejects("Author name must be nonblank")(first.copy(name = " "))
    rejects("Normalized author name must be nonblank")(
      first.copy(normalizedName = " ")
    )
    rejects("Author position must be one-based")(StableIds.mention(workId, 0))

  test(
    "canonical researchers and authorship records bind mentions to researchers"
  ):
    val researcher = CanonicalResearcher(
      version,
      researcherA,
      "Ada Lovelace",
      Map("openalex" -> "A1"),
      Vector("A. Lovelace"),
      provenance
    )
    val researcherWire = ContractWire.encode(researcher)
    assertEquals(
      ContractWire.decode[CanonicalResearcher](researcherWire),
      researcher
    )
    rejects("Malformed researcher identifier")(
      researcher.copy(researcherId = "A1")
    )
    rejects("Malformed researcher identifier")(
      researcher.copy(researcherId = workId)
    )
    rejects("Display name must be nonblank")(researcher.copy(displayName = " "))
    val authorship = AuthorshipRecord(
      version,
      workId,
      mention1Id,
      Some(researcherA),
      1,
      Some(2024),
      provenance
    )
    assertEquals(
      ContractWire.decode[AuthorshipRecord](ContractWire.encode(authorship)),
      authorship
    )
    assertEquals(
      authorship.copy(researcherId = None).researcherId,
      Option.empty[String]
    )
    rejects("Mention ID must match work and author position")(
      authorship.copy(authorPosition = 2)
    )
    rejects("Mention ID must match work and author position")(
      authorship.copy(mentionId = mention2Id)
    )
    rejects("Mention ID must match work and author position")(
      authorship.copy(workId = otherWorkId)
    )
    rejects("Malformed researcher identifier")(
      authorship.copy(researcherId = Some("A1"))
    )

  test("publication teams follow the work's one-based mention sequence"):
    val team = PublicationTeam(
      version,
      workId,
      Some(2024),
      Vector(mention1Id, mention2Id),
      provenance
    )
    assertEquals(
      ContractWire.decode[PublicationTeam](ContractWire.encode(team)),
      team
    )
    rejects("one-based author sequence")(
      team.copy(orderedMentionIds = Vector(mention2Id, mention1Id))
    )
    rejects("one-based author sequence")(
      team.copy(orderedMentionIds = Vector(mention2Id))
    )
    rejects("one-based author sequence")(
      team.copy(orderedMentionIds = Vector(mention1Id, mention1Id))
    )
    rejects("one-based author sequence")(
      team.copy(orderedMentionIds = Vector(StableIds.mention(otherWorkId, 1)))
    )
    rejects("one-based author sequence")(team.copy(workId = otherWorkId))
    rejects("Malformed work identifier")(team.copy(workId = mention1Id))
    val document = ujson.read(ContractWire.encode(team))
    document("orderedMentionIds")(0) = ujson.Str(mention2Id)
    intercept[Exception](
      ContractWire.decode[PublicationTeam](ujson.write(document))
    )
    // As with WorkRecord.authors, an empty sequence satisfies the coupling
    // vacuously and stays valid.
    assertEquals(
      team.copy(orderedMentionIds = Vector.empty).orderedMentionIds,
      Vector.empty[String]
    )

  test("researcher metrics reject negative, nonfinite and absent aggregates"):
    assertEquals(
      ContractWire.decode[ResearcherMetrics](ContractWire.encode(metrics)),
      metrics
    )
    rejects("Malformed researcher identifier")(
      metrics.copy(researcherId = workId)
    )
    rejects("Collaboration counts must be nonnegative")(
      metrics.copy(publicationCount = -1L)
    )
    rejects("Collaboration counts must be nonnegative")(
      metrics.copy(distinctCoauthorCount = -1L)
    )
    rejects("Collaboration counts must be nonnegative")(
      metrics.copy(totalCoauthorOccurrences = -1L)
    )
    rejects("Collaboration counts must be nonnegative")(
      metrics.copy(maximumCoauthorsOnOnePaper = -1L)
    )
    rejects("Component size must be positive")(metrics.copy(componentSize = 0L))
    rejects("Component ID must be nonblank")(metrics.copy(componentId = " "))
    rejects("Average coauthors per paper must be finite and nonnegative")(
      metrics.copy(averageCoauthorsPerPaper = Double.NaN)
    )
    rejects("Average coauthors per paper must be finite and nonnegative")(
      metrics.copy(averageCoauthorsPerPaper = -0.5)
    )
    rejects("Weighted degree must be finite and nonnegative")(
      metrics.copy(weightedDegree = Double.PositiveInfinity)
    )
    rejects("Bridge score must be finite and nonnegative")(
      metrics.copy(bridgeScore = Some(-0.1))
    )
    rejects("Coreness must be nonnegative")(metrics.copy(coreness = Some(-1)))
    rejects("Malformed work identifier")(
      metrics.copy(maximumTeamWorkIds = Vector(researcherA))
    )
    rejects("Metrics time window is required")(metrics.copy(window = null))
    val document = ujson.read(ContractWire.encode(metrics))
    document("window")("endYear") = ujson.Num(2019)
    intercept[Exception](
      ContractWire.decode[ResearcherMetrics](ujson.write(document))
    )

  test("venue, institution and topic records carry external vocabulary IDs"):
    val venue = Venue(
      version,
      "venue/example-journal",
      "Example Journal",
      Vector("example-issn"),
      Map("example" -> "V1"),
      provenance
    )
    assertEquals(ContractWire.decode[Venue](ContractWire.encode(venue)), venue)
    rejects("Venue ID must be nonblank")(venue.copy(venueId = " "))
    rejects("Venue name must be nonblank")(venue.copy(name = ""))
    val institution = Institution(
      version,
      "institution/example-lab",
      "Example Lab",
      Some("US"),
      Map("example" -> "I1"),
      provenance
    )
    assertEquals(
      ContractWire.decode[Institution](ContractWire.encode(institution)),
      institution
    )
    rejects("Institution ID must be nonblank")(
      institution.copy(institutionId = " ")
    )
    rejects("Institution name must be nonblank")(institution.copy(name = " "))
    val topic = Topic(
      version,
      "topic/example-topic",
      "Example Topic",
      Some("topic/example-parent"),
      provenance
    )
    assertEquals(ContractWire.decode[Topic](ContractWire.encode(topic)), topic)
    rejects("Topic ID must be nonblank")(topic.copy(topicId = " "))
    rejects("Topic name must be nonblank")(topic.copy(name = " "))
    // The asymmetry is deliberate: these three IDs are external vocabulary and
    // are only required to be nonblank, unlike the minted ID kinds. A
    // referencing work field is held to the same nonblank rule.
    assertEquals(venue.venueId, "venue/example-journal")
    assertEquals(
      work.copy(venueId = Some(venue.venueId)).venueId,
      Some("venue/example-journal")
    )
    rejects("Work venue ID must be nonblank")(work.copy(venueId = Some(" ")))

  test("graph nodes dispatch identifier discipline by node type"):
    val chunkId = StableIds.chunk(workId, "s1", "abstract", Some(0), "text")
    val node = GraphNode(
      version,
      researcherA,
      GraphNodeType.Researcher,
      Map("displayName" -> "Ada Lovelace"),
      provenance
    )
    assertEquals(
      ContractWire.decode[GraphNode](ContractWire.encode(node)),
      node
    )
    Vector(
      (GraphNodeType.Researcher, researcherA),
      (GraphNodeType.AuthorMention, mention1Id),
      (GraphNodeType.Work, workId),
      (GraphNodeType.TextChunk, chunkId)
    ).foreach { case (kind, id) =>
      assertEquals(node.copy(nodeId = id, nodeType = kind).nodeId, id)
    }
    rejects("Malformed researcher identifier")(
      node.copy(nodeId = workId, nodeType = GraphNodeType.Researcher)
    )
    rejects("Malformed mention identifier")(
      node.copy(nodeId = researcherA, nodeType = GraphNodeType.AuthorMention)
    )
    rejects("Malformed work identifier")(
      node.copy(nodeId = mention1Id, nodeType = GraphNodeType.Work)
    )
    rejects("Malformed chunk identifier")(
      node.copy(nodeId = workId, nodeType = GraphNodeType.TextChunk)
    )
    // The remaining node types fall through to a nonblank check, because their
    // identifiers come from external vocabularies.
    Vector(
      GraphNodeType.Venue,
      GraphNodeType.Institution,
      GraphNodeType.Topic,
      GraphNodeType.DatasetSnapshot
    ).foreach { kind =>
      assertEquals(
        node.copy(nodeId = "external/vocabulary", nodeType = kind).nodeId,
        "external/vocabulary"
      )
      rejects("Graph node ID must be nonblank")(
        node.copy(nodeId = " ", nodeType = kind)
      )
    }
    val doc = ujson.read(ContractWire.encode(node))
    doc("nodeType") = ujson.Str("Researchers")
    intercept[Exception](ContractWire.decode[GraphNode](ujson.write(doc)))

  test("query plans require a positive result limit and researcher IDs"):
    val plan = QueryPlan(
      version,
      "query-1",
      QueryOperation.TemporalComparison,
      Vector(researcherA, researcherB),
      Some(window),
      Some(YearWindow(2015, 2019)),
      Vector(
        CollaborationMetric.PublicationCount,
        CollaborationMetric.WeightedDegree
      ),
      25,
      provenance
    )
    assertEquals(
      ContractWire.decode[QueryPlan](ContractWire.encode(plan)),
      plan
    )
    rejects("Query ID must be nonblank")(plan.copy(queryId = " "))
    rejects("Query result limit must be positive")(plan.copy(resultLimit = 0))
    rejects("Query result limit must be positive")(plan.copy(resultLimit = -1))
    rejects("Malformed researcher identifier")(
      plan.copy(researcherIds = Vector(workId))
    )
    assertEquals(
      plan.copy(window = None, comparisonWindow = None).window,
      Option.empty[YearWindow]
    )
    assertEquals(
      plan.copy(researcherIds = Vector.empty).researcherIds,
      Vector.empty[String]
    )
    val doc = ujson.read(ContractWire.encode(plan))
    doc("operation") = ujson.Str("unknown-operation")
    intercept[Exception](ContractWire.decode[QueryPlan](ujson.write(doc)))
    val reversed = ujson.read(ContractWire.encode(plan))
    reversed("window")("endYear") = ujson.Num(1999)
    intercept[Exception](ContractWire.decode[QueryPlan](ujson.write(reversed)))

  test("evidence bundles reject duplicates and unevidenced claims explain why"):
    val id = StableIds.evidence(
      SourceType.Dblp,
      "fixture-v1",
      "fixture/work",
      EvidenceType.Publication,
      "row1"
    )
    val item = EvidenceItem(
      version,
      id,
      EvidenceType.Publication,
      Some(workId),
      "https://example.invalid/work",
      None,
      None,
      1.0,
      Some(0.5),
      None,
      None,
      provenance
    )
    val bundle =
      EvidenceBundle(version, "bundle1", Vector(item), None, provenance)
    assertEquals(
      ContractWire.decode[EvidenceBundle](ContractWire.encode(bundle)),
      bundle
    )
    rejects("Duplicate evidence ID in bundle")(
      bundle.copy(items = Vector(item, item))
    )
    rejects("Bundle ID must be nonblank")(bundle.copy(bundleId = " "))
    // Recorded decision: an empty bundle stays valid, because it is the honest
    // representation of retrieval that found nothing.
    assertEquals(
      bundle.copy(items = Vector.empty).items,
      Vector.empty[EvidenceItem]
    )
    rejects("Evidence score must be finite")(item.copy(score = Double.NaN))
    rejects("Confidence must be finite and in [0, 1]")(
      item.copy(confidence = Some(1.5))
    )
    rejects("Malformed evidence identifier")(item.copy(evidenceId = workId))
    val unsupported = AnswerClaim(
      version,
      "claim1",
      "No supporting record was found",
      Vector.empty,
      VerificationStatus.Unsupported,
      Some("retrieval returned no matching publication"),
      provenance
    )
    assertEquals(
      ContractWire.decode[AnswerClaim](ContractWire.encode(unsupported)),
      unsupported
    )
    rejects("must record its uncertainty")(unsupported.copy(uncertainty = None))
    rejects("must record its uncertainty")(
      unsupported.copy(
        verificationStatus = VerificationStatus.Uncertain,
        uncertainty = Some(" ")
      )
    )
    rejects("Verified claims require evidence")(
      unsupported.copy(verificationStatus = VerificationStatus.Verified)
    )
    val empty = EvidenceBundle(
      version,
      "bundle-empty",
      Vector.empty,
      Some("no evidence retrieved"),
      provenance
    )
    val response = AnswerResponse(
      version,
      "query1",
      "No supporting record was found",
      Vector(unsupported),
      empty,
      provenance
    )
    assertEquals(
      ContractWire.decode[AnswerResponse](ContractWire.encode(response)),
      response
    )
    rejects("must resolve within the answer evidence bundle")(
      response.copy(
        claims = Vector(
          unsupported.copy(
            evidenceIds = Vector(id),
            verificationStatus = VerificationStatus.Verified
          )
        )
      )
    )

  test("enum wire values are unique and a duplicate codec fails loudly"):
    Vector(
      SourceType.values.map(_.wire),
      MatchOutcome.values.map(_.wire),
      EvidenceType.values.map(_.wire),
      CollaborationMetric.values.map(_.wire),
      QueryOperation.values.map(_.wire),
      VerificationStatus.values.map(_.wire),
      GraphNodeType.values.map(_.wire),
      GraphEdgeType.values.map(_.wire)
    ).foreach { wires =>
      assertEquals(wires.distinct.length, wires.length)
      assert(wires.forall(_.trim.nonEmpty))
    }
    rejects("Enum wire values must be unique")(
      EnumWire.codec(Array("a", "b"), (_: String) => "same")
    )

  test("null members are rejected rather than silently accepted"):
    rejects("A wire record must be non-null")(
      ContractWire.encode[WorkRecord](null)
    )
    rejects("Provenance is required")(work.copy(provenance = null))
    rejects("Metrics time window is required")(metrics.copy(window = null))
    rejects("Match outcome is required")(matchDecision.copy(decision = null))
    rejects("Graph node type is required")(
      GraphNode(version, researcherA, null, Map.empty, provenance)
    )
    // Rejected through the wire as well. upickle returns null from its default
    // null visitor for a derived reader, so these guards are reachable rather
    // than dead, but the assertion below does not depend on which guard fires.
    intercept[Exception](ContractWire.decode[WorkRecord]("null"))
    val document = ujson.read(ContractWire.encode(metrics))
    document("window") = ujson.Null
    intercept[Exception](
      ContractWire.decode[ResearcherMetrics](ujson.write(document))
    )
