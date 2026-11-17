# Wave 2 review 1 verdict (code-reviewer, Opus; received 2026-11-17; summary as received)

**REQUEST CHANGES.** 2 BLOCKER, 1 MAJOR, 7 MINOR. Read-only review; no commands run.

## BLOCKER
- **B1. Export prefix uses the wrong run ID.** `AwsReadModels.scala:112-115, 153-154, 288` builds `semantic-context/runs/run:v1:<uuid>/export/`. The pipeline publishes under its slug `run-v1-<uuid>` (`scripts/emr/run-s-pipeline.sh:580`, `lib-emr.sh:372-379`). Spark's `sourceRunId` is a different, input-derived value (`Inputs.scala:113-117`); run 6 has slug `run-v1-192de0fd-...` and `sourceRunId` `run:v1:9f6e6fb0-...`. `graph_run_id` must stay the `sourceRunId` (checked against the manifest). Session 2 runtime would fail "No export objects" (exit 3). Fix: a second validated `run-v1-<uuid>` export-run setting (storage `AwsReadModelSettings`; services `AwsSettings` env plus `ReadModelSource.storageSettings`; infra variable, env entry and SESSION2 §6a: slug = A `run_id`, `graph_run_id` = manifest `sourceRunId`; tests `tfcheck.py:38-45` `REQUIRED_SERVICE_ENV`).
- **B2. `coalesce(var.graph_run_id, "")`** (`environments/course/main.tf:78`) raises "no non-null, non-empty-string arguments" when null, so the A plan, A1 and A4/session-down fail at plan time; `terraform validate` misses it. Fix: `var.graph_run_id == null ? "" : var.graph_run_id` (infra). Confirm via `terraform console` or the A1 plan; based on documented semantics.

## MAJOR
- **M1. HEAP-MEASURE cannot run.** `ExportHeapMeasureSuite.scala:21-33, 42-55` adds the six tables to the manifest only; `DirStore.listSizes` lists disk files, so `withSource` fails `MalformedManifest` "lacks required tables" (`AwsReadModels.scala:226-233`) before measuring. Fix: list overlay tables too or measure via a path that does not check the listing (storage).

## MINOR
- m1. `.gitignore` lacks `__pycache__/` and `*.pyc`; untracked `.cpython-315.pyc` under experiments/c, f and load-tests/lib, query-load (build-engineer).
- m2. `scripts/README.md:463` still quotes old b-burst values "16, 131072, 3072, 3072, 32" (infra).
- m3. `AwsBackend.scala:183` wires `BedrockAnswerModel` as `ports.model`, so planner and synthesis both call Haiku (`Workflow.scala:117-118, 140`); the 10-call cap covers about 5 smoke queries; cap is per process (A2 max 4 tasks). Document or decide (rag, services; human decision).
- m4. Path-evidence hop edges no longer checked against `edges-coauthored` (WO-7c trade-off); needs recorded human acceptance (storage).
- m5. `load-tests/lib/collect-common.sh:79-81`, `experiments/b-burst/collect.sh:109-110` allow any `get-*`/`list-*`/`describe-*` verb (e.g. `secretsmanager get-secret-value`); tighten to exact service and verb pairs (experiment-engineer; b-burst collect.sh is experiments/**).
- m6. `packaging/load-client/Dockerfile:1-2` does not enforce a digest in `PYTHON_BASE` (build-engineer).
- m7. `AwsSettings.scala:215` accepts any Region; worker pins us-east-2 (service-engineer).

## Checked and correct (reviewer)
P15-25 limit applied after selection, before download (1,000-object bound on the whole listing, fails rather than truncates); loading reads each part twice, not true streaming. Default `Fused` behavior unchanged; `retrievalMode` 400 without echo; no question text logged. IAM: Neptune OpenCypher condition, worker role scoped, load client has no task role, secrets via ECS secrets only, no real account IDs. Neptune SigV4, no retry, bounded results. Worker: poison never deleted, conditional ledger writes. P15-23 values consistent. C 0.60; C-F within caps; retention 7 days. SparkRoundTripSuite exact 17; b-collect cases stricter. `tfcheck.py` exemption sound by reading. hopIndex bounded by default hop limit 6 (validation allows 64).

Pending: Batch B harness, HEAP-MEASURE (after M1), first plan (B2), owners' `sbt quick`, human `sbt clean check`. Risk: SESSION2 HIGH 4.5 vs plan 4.3 (P15-10).
