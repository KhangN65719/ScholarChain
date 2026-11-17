# Wave 2 repair batch 2 (orchestrator, 2026-11-17)

Source: `review-1-verdict.md` (REQUEST CHANGES) and Batch B harness `phase15-w2-rb1-harness.log`: `RESULT pass=1751 fail=1`. Decisions P15-27 (m3) and P15-28 (m4). One repair round; no weakened tests; writers serialize sbt (`sbt quick` one owner at a time).

| Item | Owner | Fix |
|---|---|---|
| B1a | storage-engineer | Add a validated export-run setting (`run-v1-<uuid>`, the pipeline slug) to `AwsReadModelSettings`; build the export prefix `semantic-context/runs/<slug>/export/` from it; `graph_run_id` (`run:v1:<uuid>`) stays the manifest `sourceRunId` check and metric pin. Tests for both values and a mismatch. |
| M1 | storage-engineer | `ExportHeapMeasureSuite` must measure: the six overlay tables need listed objects too (or a measuring path that does not fail on the listing). It still prints retained and peak heap. |
| B1b | service-engineer | New env var for the export run slug in `AwsSettings` (validated, required for role `both`/query), passed through `ReadModelSource.storageSettings`; tests. |
| m7 | service-engineer | `AwsSettings.scala:215`: accept only `us-east-2`, like the worker; test. |
| B1c | infra-engineer | Variable for the export slug (null default, pattern `^run-v1-[0-9a-f-]{36}$`, required with services as `graph_run_id` is); env entry on the query service; SESSION2 §6a: slug = A pipeline `run_id`, `graph_run_id` = manifest `sourceRunId`. |
| B2 + harness | infra-engineer | `main.tf:78`: replace `coalesce(var.graph_run_id, "")` with a null-safe form referencing `var.graph_run_id` (e.g. `var.graph_run_id == null ? "" : var.graph_run_id`), same for the new slug. Harness FAIL line 1667 `15.wave2.terraform.every-required-environment-variable-is-set-and-the-graph-run-id-comes-from-a-variable`: "SCHOLARCHAIN_GRAPH_RUN_ID is not set from a variable (WO-10 item 5)". |
| m2 | infra-engineer | `scripts/README.md:463` old b-burst values to P15-23 values. |
| B1d + harness | test-engineer | `tfcheck.py:38-45` `REQUIRED_SERVICE_ENV` gets the new env name; the "from a variable" check accepts the null-safe conditional on `var.graph_run_id` (and the slug variable) while still refusing literals and other sources; self-test for each. Fixture `tf-good` updated. |
| m1, m6 | build-engineer | `.gitignore`: `__pycache__/`, `*.pyc`. `packaging/load-client/Dockerfile`: refuse a `PYTHON_BASE` without `@sha256:`. |
| m5 | experiment-engineer | `load-tests/lib/collect-common.sh:79-81`, `experiments/b-burst/collect.sh:109-110`: exact service and verb allowlist (no `secretsmanager`, no generic `get-*`). Rehearsals still pass. |
| m3 doc | deploy-engineer (runbook) | Recorded in the A + B / session 2 runbook: Haiku smoke with exactly 1 service task and at most 4 queries (P15-27). |
