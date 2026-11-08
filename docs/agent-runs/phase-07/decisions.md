# Phase 7 decisions (unattended run, 2026-11-08)

Standing rule from the human: for any decision needing the human, take the most conservative option, record it here as PENDING-HUMAN, continue.

## RULINGS (human, 2026-11-08, after the first morning summary)
- D1 CLOSED: the human downloaded the README to target/course-README.md; the Phase 7 section (lines 1350-1432) is copied verbatim into prompt.md.
- D5 ruled: spark-engineer bumps transformVersion (done: `spark-analytics/1.1.0`, work order 12).
- D6 ruled: storage-engineer gates the AWS integration suite behind opt-in env var `SCHOLARCHAIN_AWS_IT=1`, skipped by default, with a test that it is skipped by default (work order 13).
- D10 ruled: storage uses core StableIds for authored/published-in edge IDs (work order 13, then test-engineer work order 14).
- D2, D3, D7, D8, D9, D11, D12 accepted as recorded.

## RULINGS round 2 (human, 2026-11-08, after the Spark rerun)
- D4 PARTLY CLOSED: the human ran the Spark rerun (exit 0, `SPARK-PIPELINE RESULT: SUCCESS`, 407 s, 25 tables, 98,661 coauthored edges, integrity-violations 0). New run `run=run-v1-4600a8f4-d438-4683-8852-db8743eb23f0` under phase6-out/spark. Verified from text files: `run-manifest.json` has `transformVersion` = `spark-analytics/1.1.0` and runId `run:v1:4600a8f4-...`. UNVERIFIED: a `mention-coauthor:v1:` edge ID. The text files (run-manifest, summary.txt, load-manifests/*.json) carry schemas and counts only, no edge IDs; the IDs live in Parquet and there is no Parquet reader (D6). `summary.txt` shows canonical.unresolvedMentionNodes=12453 and 12,753 unresolved-mentions rows, so mention endpoints exist in the run. No `sbt-exit` line needed.
- D13 (1) CLOSE NOW: storage-engineer adds an S3 Vectors query port with metadata filters (researcher, year window, source, topic, institution, community, document type) and the missing compact metadata keys, against the local fake, with tests (work order 16).
- D13 (2) CLOSE NOW: Neptune loads every README relationship type that the Phase 6 Spark outputs have data for; for each remaining type, document in DESIGN or here why the implemented graph model has none (work order 16).
- D13 (3) DEFERRED to Phase 12: the bounded AWS integration run.
- Process: one batched repair, `sbt quick` then one `sbt check`, re-review of changed items only, then the human runs `sbt clean check`. Do not commit.

## D13 PENDING-HUMAN: verbatim-README gaps found by the round-3 review
- S3 Vectors: no QueryVectors/filter port; vector metadata lacks `source`, `topic`, `institution`, `communityId` keys (README asks for filters on researcher, year window, source, topic, institution, community, document type "when permitted by service limits"). Topic and institution have no Spark source data; `source` and `communityId` could be added.
- Neptune: AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO, DERIVED_FROM, SUPPORTED_BY and "selected context nodes" not loaded (Spark exports only AUTHORED, COAUTHORED_WITH, PUBLISHED_IN; README says "as required by the implemented graph model"). Researcher metrics only in DynamoDB. Read-only graph credentials are an infra matter.
- The bounded storage integration suite has never run (opt-in gate exists; adapters deferred).
- D10 evidence IDs are synthetic; an empty ID string raises a raw IllegalArgumentException from core instead of MalformedRow.
- Conservative: none implemented unattended; recorded as accepted gaps for Phase 8/9 or a follow-up work order. Human decides whether any must be closed before the Phase 7 commit.

## D1 (CLOSED, see rulings) README Phase 7 section was not verbatim
- Need: `prompt.md` should hold the Phase 7 section verbatim from the course README.
- Observed: `target/course-README.md` does not exist. The web-fetch agent read the upstream README (HTTP 200) but its quote limit (125 characters) blocked verbatim return. A direct `curl` download by the orchestrator was refused by the Bash guard ("outside the role's permitted commands").
- Conservative option taken: `prompt.md` holds a structured paraphrase labeled NON-VERBATIM plus the verified headings. I did not route around the guard.
- Human action: accept the paraphrase, or save the README to `target/course-README.md` (command in the morning summary) and have the section pasted over the paraphrase, or accept the paraphrase.

## D2 PENDING-HUMAN: no core schemaVersion bump for mention-endpoint edges
- Domain-engineer widened v1 (no bump) because a bump invalidates frozen v1 fixtures and records. Conservative: keep v1, ID kind `mention-coauthor:v1`.
- Risk: a pre-change reader rejects such v1 edges; loaders must ship with this core version. Human may require a ratified v2 plan.

## D3 PENDING-HUMAN: Spark-only metrics columns have no core form
- `nodeKind`, `unresolvedCoauthorOccurrences`, `distinctUnresolvedCoauthorCount` stay Spark-local. Conservative: loaders carry them as opaque/optional properties without a new core contract.

## D5 PENDING-HUMAN: Spark transformVersion not bumped
- Mention-edge ID meaning changed but `transformVersion` stays `spark-analytics/1.0.0`; run ID changes through the sources digest only (git checkout). Conservative: no bump; loaders check for legacy IDs.

## D6 PENDING-HUMAN: Parquet reader and AWS SDK dependencies not added
- Storage has no Parquet reader (TableSource seam only) and no AWS SDK. New dependencies need ledger approval (dependency-updater / human) and are outside the local-only scope. Conservative: none added; build-engineer not dispatched (no build change is required for the local scope; storage already compiles in the module graph). Optional: add AwsIntegrationSuite to `slowSuites`.

## D7 PENDING-HUMAN: changed vector under an existing key is refused
- Real S3 Vectors PutVectors is an upsert. Conservative: the loader/fake refuses a changed embedding or metadata under an existing key (new model means new index/key). Human may relax this to upsert.

## D8 PENDING-HUMAN: logs not stored under scholarchain-data/logs
- The Bash guard refuses redirection and pipes, so agent sbt output stayed in tool results/task output, not in /Users/winnguyen/scholarchain-data/logs/. Nothing was written to target/ by us. A factory-engineer change (reviewed script or `tee` allowance) would be needed to capture logs; no `.claude/**` edit was made.

## D9 PENDING-HUMAN: constitutional gaps hit unattended (for factory-engineer)
- Agents and the orchestrator cannot grep/find/tail in Bash, so large-log reading is by Read offsets; a reviewed log-summary script would help. Not edited.

## D10 PENDING-HUMAN: storage-local AUTHORED / PUBLISHED_IN edge IDs
- Reviewer: storage mints `authored:v1:`/`published-in:v1:` IDs (unframed `|` join) while core has `StableIds.graphEdge` with length framing. Changing core or Neptune edge IDs is a contract decision. Conservative: keep storage-local IDs (recorded in `idDerivation`), no core change unattended. Human decides whether to adopt `StableIds.graphEdge`.

## D11 PENDING-HUMAN: unowned untracked paths
- `AGENTS.md`, `.codex/**`, `.agents/**`, `datasets/slice/**`, `docs/agent-runs/phase-06/readback-paths.py`, `.DS_Store` files are outside Phase 7 and unratified. Exclude them from the Phase 7 commit; ownership of `.codex/**` and `AGENTS.md` needs a human ruling.

## D12 PENDING-HUMAN: open reviewer items accepted as limitations
- Neptune multi-run plan (no removal of vanished edges), CSV header types/array delimiter unverified against Neptune's load-format docs, `textRef` length vs 200-char limit for real S3 keys, DynamoDB deferred adapter missing, integration suite ignored not profile-gated, process note that the test-per-gate-item list timing is unverifiable.

## D4 PENDING-HUMAN: Phase 6 on-disk outputs carry old Spark-local edge IDs
- Existing runs under /Users/winnguyen/scholarchain-data/phase6-out/spark/ predate the core ID change. Conservative: no long real-data Spark rerun unattended. Storage tests use fixtures plus a loader mode that REJECTS legacy `coauthor:v1:` IDs on mention endpoints; a rerun of Spark (human, `!` command in the summary) is needed before any real graph load.
