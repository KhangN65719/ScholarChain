# Phase 17 final gate: work orders

Author: orchestrator, 2026-11-20, fresh session. Order set by the human: F1 review, F2 test-engineer `sbt check`, F3 infra-engineer Terraform and harness checks, then the close (F4 B9 release manifest, F5 README deliverable-index fixes, F6 `human-gate.md` and `commit.txt`). The human runs `sbt clean check` on a fresh clone. The demo video is recorded last and records probe P3.

## WO-F1: code-reviewer (Opus), final full review

- Scope: every change in `6072310..HEAD` (commits `2151c01`, `5157e0e`, `c830b90`, `4341dbb`, `dc15299`, `264f24b`; 82 files, +3723/-116 per `git diff --stat 6072310..HEAD`), plus the held, uncommitted `docs/agent-runs/phase-17/**` (76 files per `git status --porcelain --untracked-files=all`).
- Evidence supplied by the orchestrator inline in the dispatch: `git log --oneline 6072310..HEAD`, `git diff --stat 6072310..HEAD`, the full diffs of the constitutional files (`.claude/settings.json`, `.mcp.json`, `CLAUDE.md`, `docs/agents.md`, `.claude/agents/documentation-engineer.md`, `.claude/hooks/validate.py`) and of the code and test files (`experiments/EVALUATION.md`, `load-tests/query-load/rehearse.py`, `tests/adversarial/P14LoaderRig.scala`, `tests/adversarial/Phase10RepairSuite.scala`, `tests/scripts/run.sh`, `AwsIngestMainSuite.scala`, `AwsNeptuneQuerySuite.scala`). New files are read directly.
- Checks: docs against evidence; every result linked; no invented result; no account id (12-digit), bucket name or ARN; no grader- or agent-directed text; optional components stated as not implemented; guard not weakened (`guard-dangerous.sh` additions, `--list-categories`); test rewrites unchanged in meaning; every `human-gate.md` line traces to a cited file and every `commit.txt` equals its gate hash; disclosed manual edits P17-18, P17-24, P17-29, P17-30, P17-31 (class 3) read and checked; README deliverable index against what is now committed.
- Output: APPROVE or REQUEST CHANGES, all findings batched with exact file:line and evidence. Read only. Missing checks are reported as pending for their owners.
- Result: REQUEST CHANGES (`review-F1-verdict.md`): B1 to B4 to documentation-engineer; B5 and B6 to the human.
- Orchestrator note on F1's "value check" pending item: the orchestrator's `git diff 6072310..HEAD` for those files shows the old literals `"cursor-secret-canary-0123456789"` (`Phase10RepairSuite.scala`), `"TONIGHT-TOKEN-NOT-A-SECRET"` (`run.sh`) and `"REHEARSAL-TOKEN-NOT-A-SECRET"` (`rehearse.py`). The new expressions join to the same strings: `"cursor-secret-" + "canary-0123456789"`, and `"-".join` of `TONIGHT, TOKEN, NOT, A, SECRET` and of `REHEARSAL, TOKEN, NOT, A, SECRET`. Read by the orchestrator only; no independent check.

## WO-F1-R1: documentation-engineer, repair round for F1 B1 to B4 and B6

- Write set: `README.md`, `reports/final-report.md`, `docs/demo-script.md`, `docs/runbook.md`, `docs/service-limits.md`, `docs/threat-model.md`. Docs only; no sbt.
- B1 now: `README.md:189`, `:218`, `:219`, D17 (`:402`), D18 (`:403`), D20 (`:405`). B1 deferred to WO-F5, after F3, F4 and F6 exist: D03 (`:388`), D12 (`:397`), D14 (`:399`).
- B2, B3 and B4 as written in `review-F1-verdict.md`. B3 adds M12 (P17-30), M13 (P17-31 item 1) and P17-30 to P17-35 in the by-source list. P17-34's redaction is an orchestrator edit to an orchestrator-owned archive, not a manual edit outside the workflow, so it is not an M entry.
- B6: qualify `README.md:16` for Phase 17 per P17-35.
- P17-33: course item 11 in `docs/demo-script.md` is ARCHIVED only; remove the optional live Neptune path (i); state in the README and final report that no live AWS session ran in Phase 17 where they say one was optional.
- Non-blocking 1 to 6 (documentation-engineer files only).

## WO-F1-R1h: hadoop-engineer, F1 non-blocking 2

- Write set: `modules/hadoop/JOB-CONTRACTS.md:213` and `:259` only: "NOT REPLAYED until H-8" becomes "NOT REPLAYED" (H-8 ran and did not cover these items). Markdown only; no sbt.

## WO-F4: deploy-engineer, Phase 17 release manifest (plan item B9)

- Write set: `docs/releases/phase-17/**`.
- Records only. Run no commands that build, deploy, call AWS or start sbt.
- No demo session ran (P17-33, P17-37), so there is no session release record. The P16-34 note is required: `services-smoke.tfvars` sets `slow_deadline_millis = 60000` (P17-25), and it is not deployed in Phase 17.
- Manifest fields, each with the source file cited or marked PENDING or NOT RECORDED:
  - Code commit `264f24b`. The final documentation commit and tag are PENDING (P17-6).
  - Image digests from the Phase 14 and 15 release records.
  - Terraform: fmt, validate and git status per F3 (human, 2026-11-20). The plan checksum is NOT RECORDED, because no plan ran in Phase 17.
  - Dataset snapshots and checksums from `datasets/dataset-manifest.yaml`.
  - Run ids from the release and experiment records.
  - Prompt versions from `Model.scala:64-88`.
  - Model versions from the config or records.
  - Test counts: P17-39, the F2 quick, F3 `hooks-guard` 114/0, and `run.sh` PENDING (human).
  - Artifact checksums: NOT RECORDED unless a record holds them, because `package.sh` was not replayed.

## WO-F5-R1: repair round for review F5

- Orchestrator: B1, and items 4, 5, 9 and 10, applied to `phase-17/human-gate.md` and `agent-report.md`.
- deploy-engineer: B3, and items 1 to 3, in `docs/releases/phase-17/README.md`. Also fill in the `run.sh` result: human, 2026-11-20, `/tmp/sc-gate` at `264f24b`, exit 0, `RESULT pass=1949 fail=0`, `SKIPPED_CASES 0`, `real-repository-untouched PASS`.
- documentation-engineer: B2, and items 7 and 8. Also fill in the same `run.sh` result wherever README, the final report or the demo script say PENDING.
- hadoop-engineer: item 6, `JOB-CONTRACTS.md:259` "only".

## WO-F5 scope collected so far (dispatch after F3, F4 and the F6 drafts)

- documentation-engineer:
  - README D03, D12 and D14.
  - `docs/api.md:205` "(H-8)".
  - `docs/runbook.md:3` wording.
  - `docs/threat-model.md:145`.
  - `reports/final-report.md:94` (P17-33 source and grammar) and `:241` (final-gate outcome).
  - `docs/service-limits.md:95` capital "The".
  - P17-37: demo items 13 and 14 ARCHIVED for the AWS parts, with the local-services segments kept, plus any README and final-report text that offers a laptop AWS run.
  - Add P17-36 and P17-37 to the final report's by-source list.
- hadoop-engineer: `modules/hadoop/JOB-CONTRACTS.md:259` wording (re-review 1, point a).

## WO-F3: infra-engineer, read-only Terraform checks and the `tests/scripts` harness

- Run only after WO-F2 has finished. No sbt may run at the same time (P17-39).
- `terraform fmt -check -recursive` over `infra/terraform`.
- `terraform init -backend=false`, then `terraform validate`, in each root module (at least `infra/terraform/environments/course`).
- `bash tests/scripts/hooks-guard.sh`.
- `bash tests/scripts/run.sh`.
- Not allowed: `plan`, `apply`, AWS calls and edits to any file. If a provider download is needed and blocked, or the guard refuses a command, report BLOCKED-ON with the message verbatim.
- Report the exact output lines: fmt result, validate result for each module, and the harness `RESULT pass=N fail=M` lines with their exit codes.

## WO-F2 (revised by P17-39): test-engineer, `sbt quick` only

The first dispatch (`sbt check`) was stopped by the orchestrator under P17-38, with no result recorded. The revised order: run exactly `sbt quick` once. Nothing else may run at the same time. Report the counts per group. Text below is the original order, kept for the record.

## WO-F2 (original, void): test-engineer, final `sbt check`

- Run exactly `sbt check` once on the current tree (HEAD `264f24b`, plus only the untracked `docs/agent-runs/phase-17/**`, which holds no source). No file writes; no other sbt form. Report the exit status, `[success]` or `[error]` with total time, and each suite group's Passed/Failed/Errors/Skipped totals in order. Compare the totals with the H-8 part 1 run on `dc15299` (49, 389, 331, 473, 501 with 1 skipped, 170, 260, 99, 1427). F1 non-blocking item 7 (`hooks-guard.sh` R03 cases) is not part of this order.
