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

  private val provenanceWire =
    """{"schemaVersion":1,"source":"dblp","snapshotId":"fixture-v1","sourceRecordId":"fixture/work","sourceUri":"https://example.invalid/work","transformVersion":"normalize-v1","runId":"run:v1:550e8400-e29b-41d4-a716-446655440000"}"""
  private val workWire =
    s"""{"schemaVersion":1,"workId":"work:v1:dblp:Zml4dHVyZS93b3Jr","source":"dblp","sourceRecordId":"fixture/work","title":"Example Work","normalizedTitle":"example work","year":2024,"venueId":null,"doi":null,"arxivId":null,"authors":[],"abstractTextRef":null,"electronicEditionUris":[],"sourceUris":[],"recordType":"article","provenance":$provenanceWire}"""
  private val matchWire =
    s"""{"schemaVersion":1,"sourceId":"mention-source","targetId":"researcher:v1:openalex:QTE","features":{"exact-id":1},"rulesFired":["validated-id"],"decision":"ACCEPTED","confidence":1,"reasonCodes":["EXACT_ID"],"policyVersion":"policy-v1","modelVersion":null,"promptVersion":null,"decidedAt":"2024-01-01T00:00:00Z","runId":"$run","provenance":$provenanceWire}"""

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
