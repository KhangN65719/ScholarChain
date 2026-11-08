# Phase 7 review (code-reviewer, Opus)

## Round 1: REQUEST CHANGES (summary as received; limitation: reviewer had no diff, judged file contents)

Blocking:
- B1 (storage-engineer): edge-support rows are not tied to their edge's endpoints. `Neptune.scala:249-263` reads researcherA/B only for the legacy check; `Neptune.scala:317-346` checks supporting works only against the edge-support table and Work vertices, with no check that both endpoints authored each supporting work (`edges-authored`). Module fixture `Fixtures.scala:146-165` already lacks AUTHORED(R2,W2) and AUTHORED(M1,W1). Fix: refuse support rows whose endpoints/window differ from the edge; cross-check (endpoint, work) against edges-authored or record why not; correct fixture; add tests.
- B2 (storage-engineer): transformations lack own provenance: `DynamoDb.scala:523-536` ArtifactRegistry, `:578-584` ResearcherMetricCache, `:376-382` EntityLinkCache, `:668-674` RunLedger mixes kind=spark-analytics with storage transformVersion and loses Spark's, `Vectors.scala:483-493` vector metadata has no storage transformVersion. S3 manifest and Neptune manifest do record it.

Non-blocking (ranked): 1 storage mints `authored:v1:`/`published-in:v1:` IDs with unframed `|` join instead of core `StableIds.graphEdge` (human decision, proposed D10); 2 rejected-vector log uses putImmutable under a per-run/index key, a different reject list on reload throws ImmutableKeyConflict; 3 S3RunLoader holds all files in memory, default bound 20 GiB; 4 PathEvidence checks too little (prefix only, no hop-edge membership, no fromId/toId/supportingWorkIds validation, drops provenance); 5 `clean()` rewrites IDs; 6 edge `window` text not checked against years; 7 no multi-run plan in Neptune graph; 8 `textRef` may exceed the 200-char limit for real S3 keys; 9 bulk-load CSV header types/delimiter unverified against Neptune docs; 10 deferred AWS seam lacks a DynamoDB adapter and the integration suite is ignored unconditionally rather than profile-gated; 11 core error text "Malformed researcher identifier" for mention IDs, no test pins a known `coauthor:v1:` value; 12 small: Rows.int truncates, DynamoDB size estimate omits 1 byte/element, Cypher `$limit` unbounded, `.` path segment accepted; 13 evidence errors (report-06 suite counts; no report-08); 14 process: test-per-gate-item list timing unverifiable; 15 unowned untracked paths (AGENTS.md, .codex/**, .agents/**, datasets/slice/**, readback-paths.py, .DS_Store) to stay out of the commit.

Passed: module boundaries (storage depends only on core; test-only root edge; no AWS SDK; no hardcoded endpoints); core ID design; Spark adaptation and stronger SparkCoauthorshipSuite assertion; no weakened tests; Limits.scala matches service-limits.md; TTL Required only on EntityLinkCache, QuerySession, ResearcherMetricCache; `_SUCCESS` gate; parameterized queries; D7 disclosed in code.

Pending for the human: sbt clean check; D1-D9; proposed D10; ownership of .codex/** and AGENTS.md.

## Round 2 (re-review of changed items only): APPROVE (code-reviewer, Opus; judged file contents, no diff)

Resolved: B1 (Neptune.scala support rows: edge endpoints line 373, window 378, authorship via counted AUTHORED rows 324-331/390-399, window text 312-315/365-370, IDs refused not rewritten `cleanId` 96-104); B2 (`Attr.provenance` DynamoDb.scala:226-236 applied to all six tables; ledger keeps Spark's version 692-705; vector metadata Vectors.scala:110-111/495-498); items 2, 3, 4, 12 fixed. No assertion weakened (reject-log test stronger; fixtures add rows only; ledger assertion stronger; SparkCoauthorshipSuite:349-393 stronger). New checks are consistent with Spark's table construction (Canonical.scala, Coauthorship.scala, Config.scala, GraphAlgorithms.scala) by code reading; nothing run on real data.

Non-blocking residue (no further round): (1) window label check refuses a user-supplied `1-9999` window and zero-padded labels that Spark accepts (compare parsed years; pinned by Phase7NeptuneSuite:979); (2) missing `countedInMetrics` treated as counted; (3) ledger takes the lexicographically first Spark version and does not refuse a mix (empty metrics gives "unavailable"); (4) EvidenceBundle provenance labelled as Spark; (5) hop window not cross-checked with its edge's window.

Pending the human: sbt clean check; D1-D12; Spark rerun before any real load (D4).

## Round 3 (re-review of the human's rulings D1, D5, D6, D10 only): APPROVE (code-reviewer, Opus; no diff tool)

- D1: prompt.md lines 8-90 match target/course-README.md lines 1350-1432 line for line. Closed.
- D5: `RunContext.transformVersion = spark-analytics/1.1.0` (Inputs.scala:96), DESIGN.md records it; SparkOutputsSuite:213-217 reads the constant (no Spark-side test pins the literal any more; storage tests pin 1.1.0). Non-blocking.
- D6: `munitIgnore = !AwsIntegrationGate.enabled(sys.env)`, predicate true only for "1"; with the variable set every case still skips via `assume(false, "AWS adapters deferred")`; AwsIntegrationGateSuite proves the default-off behavior; modules/storage/README.md. No AWS call or SDK.
- D10: AUTHORED/PUBLISHED_IN IDs from core `StableIds.graphEdge` (Neptune.scala:229-283), independent of provenance; `idDerivation` = `core:StableIds.graphEdge`; rewritten tests equal or stronger (Phase7NeptuneSuite:650, 698, 719, 758, 766); one weak test at :739 (unused variable `same`), harmless.
- No awssdk / software.amazon / amazonaws.com in build.sbt, project/**, modules/** or tests/**.
- Orchestrator diff evidence supplied: `git diff --stat -- CLAUDE.md docs/agents.md .claude` is empty; `git diff --stat -- build.sbt project` is `build.sbt | 5 ++++-` (4 insertions, 1 deletion: the work-order-05 test-only storage edge).

## Round 4 (re-review of D13 rulings 1 and 2, work orders 16-17 and the storage fix): APPROVE (code-reviewer, Opus; no diff tool, judged file contents; relied on V8 `sbt check` 63/0 and 618/0 as supplied)

Passed: S3 Vectors query port (topK 10,000 matches service-limits.md:13; seven README filters, ANDed, missing key never matches; unknown/malformed filters typed errors; no invented topic/institution; no long text; D7 intact; ties by key); Neptune Extended default, conflicting resolution refused as `DuplicateId` (Neptune.scala:375-383), DESIGN.md reasons for AFFILIATED_WITH/HAS_TOPIC/SUPPORTED_BY verified against the real neptune.json; Phase7NeptuneSuite pins recomputed from the fixture (Extended 14/19/33; Base 8/11/19 still tested); Repair2Suite D10 edit justified, not a weakening; mention IDs consistent with core; boundaries intact.

Non-blocking (not repaired; recorded): (1) DESIGN.md:16 "Every ID is a core ID" is wrong for the DatasetSnapshot vertex ID (free-form provenance snapshotId); (2) DESIGN.md should say Extended never ran on real Parquet (about 8,800 extra AuthorMention vertices estimated, not measured); (3) communityId is window-local so a community filter without a year range can match same labels in other windows; (4) two tests accept either of two error codes; (5) MaxFilterConstraints check can never fire.

Human ruling on round 4: notes (1) and (2) fixed by storage-engineer as a docs-only DESIGN.md change (no code, no sbt, no re-review); notes (3), (4), (5) ACCEPTED as recorded.

Pending the human: `sbt clean check`; Phase 12 AWS run; D11 paths out of the commit.

New non-blocking findings exposed by the verbatim README (decisions D13): S3 Vectors has no query/filter port and no `source`, `topic`, `institution`, `communityId` metadata keys (README 1392-1395; `VectorBucketPort` at Vectors.scala:207-211); five Neptune relationship types are not loaded (AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO, DERIVED_FROM, SUPPORTED_BY; Spark exports only AUTHORED, COAUTHORED_WITH, PUBLISHED_IN) and "selected context nodes" are not loaded; researcher metrics are in DynamoDB only (not on Neptune vertices); read-only graph credentials are deferred to infra; the bounded storage integration suite has never run (gate met as a mechanism only); D10 evidence IDs are synthetic (no stored EvidenceItem) and an empty ID string would raise a raw IllegalArgumentException from core `digestId` instead of MalformedRow (`cleanId` Neptune.scala:108-115 does not refuse empty).
