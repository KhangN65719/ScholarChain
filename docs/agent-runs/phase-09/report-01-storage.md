# Report 01: storage-engineer, read-side ports (as received; tables condensed to lists)

**Status: COMPLETE.** Work order 01. Only `modules/storage/**` edited. No new dependency; storage does not depend on rag. `sbt check` not run (per work order).

## Test-to-item mapping
All in `modules/storage/src/test/scala/scholarchain/storage/ReadPortsSuite.scala`, 35 tests.
- 1 Metric cache typed read: present with run and transform; absent is `None`; one run vs two runs (`AmbiguousRun`); window listing bounded with `truncated`; tampered item refused.
- 2 Path port: found path with weight, works, years; reverse orientation; each non-path status; unselected pair; depth and result bounds; timeout hook; query validation; malformed rows; row-order determinism.
- 3 Components (4 tests) and communities (5 tests): lookup, neighbors, absent, paging, malformed, inconsistent tables.
- 4 Directory: ambiguity with mentions marked; accent and case folding with ORCID sharing; unknown name; bounded list; malformed rows; row bound; coauthor context.
- 5 Audit: store and read back; idempotent repeat and conflict; turn without bundle, ordered and bounded reads; invalid input refused before any write.
- 6 `cypher catalog stays closed`.

## Commands and results
- `sbt quick` run 1: exit 1, one failure `communities: inconsistent tables are refused` (agent's own bad fixture, unordered cross-edge pair). Fixed in test.
- `sbt quick` run 2: `[success] Total time: 75 s`; `Passed: Total 62, Failed 0, Errors 0` and `Passed: Total 664, Failed 0, Errors 0`. `ReadPortsSuite` 35 of 35.

## New ports (package `scholarchain.storage`, all read-only, absent is `None`)
- Metric cache (`DynamoDb.scala`, `ResearcherMetricCache`): `get(table, id, window, runId: Option[String]): Option[CachedMetrics]` (several cached runs and no run ID fails with new `ErrorCode.AmbiguousRun`), `find(table, id, window, limit): MetricPage`, `windows(table, id, limit): MetricPage`. `CachedMetrics` = core `ResearcherMetrics` + `runId`, `sparkTransformVersion`, `storageTransformVersion`, `sourceRef`. Key, window and `provenance.runId` checked against the item. `limit` 1 to 100, `truncated` flag.
- `PathPort.scala`: `trait PathEvidencePort { def shortestPath(q: PathQuery): PathLookup }`; `PathQuery(window, sourceId, targetId, maxDepth, maxResults, timeoutMillis)` bounds depth 1-16, results 1-100, timeout 1-30000 ms. `PathLookup`: `Found(hops)` (`PathHop`: from, to, edgeId, jointPublicationCount, supportingWorkIds, first/last collaboration year, provenance), `NonPath(status, unreachableReason, kind, provenance)`, `NotPrecomputed`, `ExceedsDepth`, `ExceedsResults`, `TimedOut`. `noPathExists` true only for `DifferentComponent`; `BeyondHopLimit`, `UnreachableUnexplained`, `NotPrecomputed` are not "no path". Fake `InMemoryPathEvidence(rows, cost)` (cost above timeout gives `TimedOut`, no sleeping). `PathEvidenceRow` gained four defaulted fields (`pathLength`, `hopLimit`, `firstCollaborationYear`, `lastCollaborationYear`).
- `GraphPartitions.scala`: `ComponentReadPort` (`component`, `componentOf`, `members(window, id, after, limit)`), `CommunityReadPort` (`community`, `communityOf`, `members`, `crossCommunityEdgeCount`, `neighbors`), fakes `InMemoryComponents.from` / `InMemoryCommunities.from`, keyset paging limit 1-200, `MemberPage(members, total, nextAfter)`; fakes refuse inconsistent tables on construction.
- `ResearcherDirectory.scala`: `ResearcherDirectoryPort.candidates(name, limit): CandidateResult` (limit 1-50), `byId`; candidate carries `nodeId, displayName, nodeKind, orcid, linkStatus, idRule, memberCount, mentionCount, isMention, sharesOrcidWith, matchKind (Exact|TokenSet)`, provenance. Case/accent/punctuation folding only. `affiliationAvailable = false` with a note. `CoauthorContext.of(neptune, researcherId, window, limit)` types `coauthorsOf` rows; no new template.
- `Audit.scala` plus `QuerySession.turn`/`turns`: `AuditTrail.record(sessions, bundles, AuditRecord): AuditOutcome` (bundle first, then turn; idempotent; different payload under same key raises `ConditionalCheckFailed`), `AuditTrail.turn`, `AuditTrail.bundle`.

## Column mapping confirmed from Spark source
`components`: window, componentId, componentSize, isLargest. `component-membership`: window, researcherId, componentId, componentSize. `communities`: window, communityId, communitySize, internalEdges, crossEdges, internalWeight, crossWeight. `community-membership`: window, researcherId, communityId, communitySize. `community-cross-edges`: window, communityA, communityB, edgeCount, totalWeight (A < B). `path-evidence`: pairKey `window|source|target`, hop rows from source, `status`, `unreachableReason`, `hopLimit`, `pathLength`, `hopIndex`, `fromId`, `toId`, `edgeId`, `jointPublicationCount`, first/last year, `supportingWorkIds`. `nodes-researcher.nodeKind`: `canonical` or `unresolved-mention`.

## Assumptions and risks
- `linkStatus` and `idRule` values are not enumerated in Spark source; carried as opaque text.
- Fake builders validate whole tables at construction: local scale only.
- Metric reader does not check TTL (no clock); Phase 12 adapter must.
- Spark `path-evidence` holds only selected pairs: `NotPrecomputed` must not be read as "no path".
- `ErrorCode.AmbiguousRun` added; no exhaustive match known to break.
- Agent could not use grep through Bash (guard); a PostToolUse hook auto-formatted new files.

## Deferred to Phase 12
Live Neptune shortest-path, component and community templates (`CypherCatalog` unchanged, P5); DynamoDB and S3 adapters for these ports; TTL-aware reads.

## Files
Created (`modules/storage/src/`): `main/.../ReadSupport.scala`, `PathPort.scala`, `GraphPartitions.scala`, `ResearcherDirectory.scala`, `Audit.scala`, `test/.../ReadPortsSuite.scala`. Modified: `DynamoDb.scala`, `PathEvidence.scala`, `Support.scala`.

## Gate status
`sbt quick` passed on these sources (turn evidence only). Pending: rag work order 02, test work order 03, `sbt check`, independent review, human `sbt clean check`.
