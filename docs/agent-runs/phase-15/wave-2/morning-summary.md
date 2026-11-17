# Phase 15 Wave 2, morning summary (orchestrator, 2026-11-17; writes stopped before 08:00)

## Status
- Wave 2 code and the A + B runbook draft: **review 5 APPROVE** (chain: reviews 1-4 REQUEST CHANGES, all routed and closed; `review-1..5-verdict.md`, `session-ab/runbook-review-1.md`).
- Agent `sbt check` after the last Scala change: `Passed: Total 99, Failed 0` / `Passed: Total 1162, Failed 0`, `[success] Total time: 1993 s`, 03:53:45 (`checks.md`). Repair batches 3-6 changed no compiled Scala.
- Not yet run (human, morning batch): `check.sh` (terraform fmt/validate of all infra edits, never validated by an agent), harness (expected `pass=1883 fail=0`), rehearsals, dockerLocal, load-client image.
- Heap: unmeasured by decision P15-29 (runs on A's export in the session); service memory stays 4096 MiB.

## Open decisions for the human
`wave-2/open-decisions.md`: OD-1 (gate all Wave 2 resources off in A + B; layout default `split`), OD-3 (400/800 MiB heap rule, from WO-7c, needs ratifying), OD-7 (1.15 USD billing-lag allowance), OD-8 (A-up plan 11/7/3: four free retention updates 3 to 7 days), OD-10 (smoke overlay exempt from the scale-out check, pinned to 1 task and 5 keys). OD-2, OD-4, OD-5, OD-6, OD-9 need no answer.

## Morning batch
`wave-2/human-commands.md`, section "Morning batch" (first line deletes the stray `scratch-unused.tmp`), then sections 3 and 4 (dockerLocal, images). No AWS. Paste every RESULT/REHEARSAL line and any FAIL.

## Files to commit (after the morning batch passes; Wave 2 commit must precede the A + B session)
Everything in `git status` except `scratch-unused.tmp` (deleted by the batch). That is: all modified and untracked paths under `.gitignore`, `build.sbt`, `docs/agent-runs/phase-15/**`, `docs/releases/phase-15/session-ab-runbook-draft.md`, `experiments/**`, `infra/terraform/**`, `load-tests/**`, `modules/{rag,services,spark,storage}/**`, `packaging/**`, `scripts/README.md`, `scripts/emr/spark-yarn.conf`, `tests/**`, including the four deleted `emr-list-instance-groups.json` fixtures. `__pycache__` is now ignored. Suggested:
```
cd /Users/winnguyen/ScholarChain && git add -A && git status --short | tail -n 60; echo "exit: $?"
```
Check that `scratch-unused.tmp` and no `*.pyc` or `*.tfstate*` appear, then commit.

## A + B plan (`docs/releases/phase-15/session-ab-runbook-draft.md`, revision 2, DRAFT)
0.0 acknowledgements (OD-1, OD-3, OD-7, OD-8) -> 0.1 billing gate (Billing reading + 1.15 <= 10.10) -> Wave 2 HEAD and clean-tree gates, docs commit, state backup, stash -> 1.x package, SP-2 jar check (`ExportTables$.class`, six names), P15-23 bootstrap upload and digests -> 2.x A plan (expected 11/7/3, `IAM_CHANGES` none, automated `PLAN_A_UP_CHECK`), deploy, A run (cap 75 min), collector, slug and `sourceRunId` into `/Users/winnguyen/scholarchain-data/session2.tfvars`, 17-table size and row check -> 3.x B switch (0/1/0), B run with P15-23 profile (cap 90 min, goal scale-in), collector -> 5.x session-down (0/1/8) and verification -> 4.x download A's export read-only, HEAP-MEASURE, sizing rule (after 5.4) -> 5.5 stash pop. Limits: A by T0+1h, B by T0+3h15, teardown apply by T0+6h. HIGH 3.90 USD. Fallbacks: A fails -> b1 (procedure not yet authored); B fails -> P15-11 (D).
Open placeholders: billed total, Wave 2 commit SHA, b1 procedure, cluster id in optional watch.
