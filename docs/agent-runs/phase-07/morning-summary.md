# Phase 7 summary (orchestrator, 2026-11-08, updated after the human's rulings)

Status: local scope complete. Independent review: APPROVE (round 2) and APPROVE of the rulings (round 3). Not committed. Not the phase gate: the human runs `sbt clean check` (below).

## What was built
- domain-engineer: core `mention-coauthor:v1:<hex>` edge ID via `StableIds.coauthorEdgeFor`; `CoauthorEdge`, `GraphEdge`, `ResearcherMetrics` accept mention endpoints (no schema bump, D2).
- spark-engineer: uses the core ID; `transformVersion` bumped to `spark-analytics/1.1.0` (D5 ruling).
- storage-engineer (`modules/storage`): S3, Neptune, DynamoDB, S3 Vectors adapters/loaders against local fakes, path-evidence reader; AUTHORED/PUBLISHED_IN IDs from core `StableIds.graphEdge` (`edge:v1:<hex>`, D10 ruling); AWS integration suite gated by `SCHOLARCHAIN_AWS_IT=1` and skipped by default, with a gate test and `modules/storage/README.md` (D6 ruling). No AWS SDK, no AWS call. OpenSearch skipped. Service limits cited in `service-limits.md`.
- build-engineer: test-only `storage % "test->compile"` edge in `build.sbt` (`git diff --stat` shows 5 lines, nothing else).
- test-engineer: `tests/adversarial/Phase7*` suites; updated stale pins in `tests/integration/SparkCoauthorshipSuite.scala` and `SparkOutputsSuite.scala` (now read `RunContext.transformVersion`).
- `prompt.md` now holds the Phase 7 section verbatim (README lines 1350-1432); D1 closed.

## Test evidence (validation.md V2-V7)
- Last orchestrator `sbt check` (06:58:28, 1122 s, exit 0): `Passed: Total 558, Failed 0` and `Passed: Total 63, Failed 0`, `[success]`. Only the last two group totals were read.
- Earlier failures were all stale pins in tests (old ID form, old `spark-analytics/1.0.0`), fixed by test-engineer without weakening anything.
- Constitutional files: `git diff --stat -- CLAUDE.md docs/agents.md .claude` is empty.

## Open items for you
- D13 (new, from the verbatim README): no S3 Vectors query/filter port and no `source`/`topic`/`institution`/`communityId` metadata keys; five Neptune relationship types and "selected context nodes" not loaded; researcher metrics only in DynamoDB; the bounded AWS integration run has never executed; synthetic D10 evidence IDs and an empty-ID edge case that raises a raw IllegalArgumentException instead of MalformedRow. Decide whether any must close before the Phase 7 commit.
- Accepted as recorded by you: D2, D3, D7, D8, D9, D11, D12 (D11: keep `AGENTS.md`, `.codex/**`, `.agents/**`, `datasets/slice/**`, `readback-paths.py`, `.DS_Store` out of the commit; `.codex/**` and `AGENTS.md` still need an owner ruling).
- D4 (still needed): Phase 6 on-disk runs carry old IDs, so rerun Spark with the new code before any real graph load. Parquet reader dependency (D6 earlier note) is still not added.

## Round 2 update (after the Spark rerun and the D13 rulings)
- Spark rerun done by the human (exit 0, 407 s, run `run-v1-4600a8f4-...`); `transformVersion` `spark-analytics/1.1.0` verified in run-manifest.json; `mention-coauthor:v1:` ID unverified (IDs only in Parquet, no reader).
- D13 (1) closed: S3 Vectors query port, seven filters, compact keys (topic/institution absent, no Spark source). D13 (2) closed: Neptune Extended default adds RESOLVES_TO and DERIVED_FROM; AFFILIATED_WITH, HAS_TOPIC, SUPPORTED_BY not loaded, reasons in modules/storage/DESIGN.md. D13 (3) deferred to Phase 12.
- Orchestrator `sbt check` (09:53:56, exit 0): Passed 63/0 and 618/0. Round-4 review: APPROVE, 5 non-blocking notes in review.md. Not committed.
- Command 1 below is already done; only command 2 remains.

## Commands, in order (type with the `!` prefix, one at a time)
1. Spark rerun (D4), DONE:
! sbt "sparkAnalytics/runMain scholarchain.spark.CollaborationPipeline --er-run /Users/winnguyen/scholarchain-data/phase6-out/er/run=run-v1-2f4cd132-afcb-4bca-83c5-1a0ddf001ace/snapshot=8e1be6626420 --hadoop-root /Users/winnguyen/scholarchain-data/phase4-out/subset-v6/run=run-v1-d4722bc7-0d02-42da-bdb6-088ee508a143/snapshot=8e1be6626420 --out-root /Users/winnguyen/scholarchain-data/phase6-out/spark --event-log-dir /Users/winnguyen/scholarchain-data/logs/spark-events" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase-07-spark-rerun.log | tail -n 40; echo "sbt-exit=${pipestatus[1]}" >> /Users/winnguyen/scholarchain-data/logs/phase-07-spark-rerun.log
2. Phase gate:
! sbt clean check 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase-07-clean-check.log | tail -n 40

Source of the paths in command 1: the first line of `/Users/winnguyen/scholarchain-data/logs/phase-06-spark-run-5.log`. The same arguments as the final Phase 6 run, no `--overwrite`: the new `transformVersion` and sources digest change the run ID, so it writes a new `run=...` directory beside the old ones.
