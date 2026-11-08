# Owner report: work order 01 (ER batch run)

Produced by: `entity-resolution-engineer`, received by the orchestrator 2026-11-07. Condensed only where marked; the facts below are the agent's statements, not orchestrator observations. The orchestrator has not yet verified any count or test result independently.

**STATUS:** COMPLETE for implementation and module tests. The real batch run is not done (human-run). Input record counts, memory and wall time are not measured.

## Files
- Created: `modules/entity-resolution/src/main/scala/scholarchain/er/ErBatch.scala`, `.../er/Clusters.scala`, `modules/entity-resolution/src/test/scala/scholarchain/er/ErBatchSuite.scala` (23 tests).
- Modified: none. No test weakened or deleted; nothing edited outside `modules/entity-resolution/**`.
- Placement in the `er/` root is deliberate: `tests/adversarial/AdversarialLeakageSuite` asserts the `er/` subdirectories are exactly `eval, llm, matching, pairs, review, sampling`. A `batch/` directory would break it; a later move needs test-engineer to update that assertion.

## Commands run (agent-reported)
- `sbt Test/compile`: exit 0, `[success]`.
- `sbt check` twice, both `[success]` (the second after removing one unused test constant). Root 240/0/0; entity-resolution 389/0/0 (366 before + 23); hadoop 207/0/0. Core summary line not read (Phase 5: 41).
- No other sbt invocation. Input paths read with `ls` only; the guard refused `wc`, `grep`, `find`.

## Test-to-gate list
The agent states it was written AFTER a drafted implementation, not before (a deviation from the role rule). Mapping, abbreviated:
- all candidates decided once, four outcomes written, policy 1.4.0: "every candidate is decided once and all four outcomes are written" (13 read, 12 distinct, 1 duplicate dropped)
- decision fields: "each outcome carries the fields the decision contract requires"
- accepted-link artifact, ORCID rule: "researcher links merge only accepted pairs, transitively, by the ORCID rule", "the canonical researcher ID rule"
- transitive closure: researcher-links test; "accepted pairs close transitively whatever the edge order"
- self-link: "a self link is ignored and merges nothing"
- CONFLICT/UNRESOLVED/REJECTED never merge: "CONFLICT, UNRESOLVED and REJECTED decisions never merge"
- integrity failure on contradicting cluster: works-chain test, "two different ORCID iDs in one cluster…", "a conflict decision inside an accepted cluster…", same-work-record and ORCID-split tests
- counts, provenance, determinism, idempotent path, no gold or network path, output outside repo and `target/`, missing stage fails loudly: named tests in `ErBatchSuite`; existing `LeakageSuite` and `AdversarialLeakageSuite` stay green
- memory and wall time on the real set: NOT proven by a test; recorded by the human run in `run-metrics.json`.

## Inputs
Existence by `ls`: `stage=candidate-features/match-candidates/v1/part-r-00000..3` (about 115, 117, 103, 114 MB) and `stage=graph-export/{works,researchers,author-mentions}/v1/part-r-00000..3`. Everything derives from `--run-root`; nothing under `scholarchain-data/gold/` is read. Works feed the matcher (each carries its author mentions); author-mentions and researchers are cross-checks only.

## Accepted-link artifact
Output root `/Users/winnguyen/scholarchain-data/phase6-out/er/run=<run-id with ':' as '-'>/snapshot=8e1be6626420/`; run ID derived from the four input digests, policy version and batch version.
```
decisions/v1/part-00000..3                           ResolutionRecord JSON (core MatchDecision inside)
accepted-links/v1/researcher-links/part-00000..3     one row per author mention
accepted-links/v1/work-links/part-00000..3           one row per work
accepted-links/v1/canonical-researchers/part-00000   one row per canonical researcher
integrity-failures/v1/part-00000                     one row per (cluster, failure)
manifest.json  summary.txt  run-metrics.json  _SUCCESS
```
- Link rows are plain JSON with real nulls. Decision lines use core upickle (Option as array; `modelVersion` is `[]` or `["offline-stub/abstain-1"]`).
- researcher-links: `schemaVersion, mentionId, workId, canonicalResearcherId (nullable), status, idRule, clusterAnchor, clusterSize, provenance`.
- work-links: `workId, canonicalWorkId, status, clusterAnchor, clusterSize, provenance` (an unlinked or failed work maps to itself).
- status values: `merged, external-id, unlinked, integrity-failure`.
- canonical-researchers: `canonicalResearcherId, idRule, orcid, status, memberCount, memberMentionIds, inResearchersExport, provenance`.
- Decisions in `(leftId, rightId)` order; decision time is fixed epoch `1970-01-01T00:00:00Z` as a deterministic substitute; wall time and memory go only to `run-metrics.json`.
- The core `MatchDecision` fits; no BLOCKED-ON domain-engineer.

## Canonical researcher ID rule
Cluster = connected component of ACCEPTED researcher decisions only; anchor = smallest mention ID.
1. Exactly one validated ORCID: `StableIds.researcherFromExternal("orcid", id)` (same ID as the graph-export researchers table).
2. Merged cluster with no ORCID: `StableIds.researcherFromIdentity("scholarchain.er.identity/1", anchor)`.
3. Lone mention without ORCID, and every member of a failed cluster: `null`. Nothing forced.
4. Canonical work ID = anchor (an existing `work:v1:` ID); the anchor can change if a smaller ID joins later.
Integrity failures (cluster stays unmerged): conflicting authoritative identifiers in one cluster; a CONFLICT decision inside a cluster; two mentions of one source work record in one cluster (agent-added); one ORCID shared by clusters no ACCEPTED pair joins (agent-added).

## Validation, assumptions, risks (agent-stated)
- Validated on hand-checkable fixtures only; gold-set evaluator not re-run; no policy number changed.
- Assumptions: candidates and works decode with the same core codecs the Phase 5 exporter used; a `snapshot=<id>` run-root name carries the snapshot ID.
- Risks: memory and time unmeasured (estimate under 1 GB live, time unknown; matcher previously ran on 230 pairs only); many unlinked mentions expected (only the exact-id path accepts, recall 0.80), so Spark must treat a null canonical ID as the mention's own node; the ORCID-split rule is strict and may flag many mentions; the two added failure types exceed the work order's wording and need review; work-cluster conflicts treat non-DOI work-identifier disagreements as authoritative as the matcher does (human ruling still deferred); manifest `gitHead` is read from `.git/HEAD`; the agent's `sbt check` refreshed `.claude/.last-test-run` (evidence for its turn, not the phase gate).

## Manual action required (as supplied; the orchestrator removed HTML escaping only)
```
! SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false SBT_OPTS=-Xmx4g sbt "entityResolution/runMain scholarchain.er.ErBatch --run-root /Users/winnguyen/scholarchain-data/phase4-out/subset-v6/run=run-v1-d4722bc7-0d02-42da-bdb6-088ee508a143/snapshot=8e1be6626420 --out-root /Users/winnguyen/scholarchain-data/phase6-out/er" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase-06-er-batch.log | tail -n 40; echo "sbt-exit=${pipestatus[1]}" | tee -a /Users/winnguyen/scholarchain-data/logs/phase-06-er-batch.log
```
Expected summary lines: run-id, output, policy `scholarchain.er.policy/1.4.0`, candidates read (Phase 5: 362,905 read, 311,249 distinct), records indexed, decisions per outcome, paths, llm (network calls 0), researcher clusters, work clusters, integrity failures, elapsed and peak heap, `ER-BATCH RESULT: SUCCESS` (failure is `FAILED <reason>`, exit 1 or 2).

## Blocked-on
None. Observation (hadoop-engineer, only if Phase 6 touches it): the `researchers` export is source-level and links to no mention. `MatchCandidate` copy in ER: not relevant (already in core).

## Next gates
Human runs the command; independent review of `ErBatch.scala`, `Clusters.scala` and the two added failure types; review of the real manifest's integrity-failure counts before Spark consumes `accepted-links/v1`.
