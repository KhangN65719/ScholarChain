# Phase 13 Ratification

- Decision: ACCEPTED by the human owner (Khang), 2026-11-11.
- Basis: review rounds 1 to 3 (code-reviewer, Opus), ending in APPROVE (review-round-3.md). Also decisions.md (D1 to D15), checks-1.md to checks-6.md, clean-check-1.md, and the owner reports.
- Phase gate: the human ran `sbt clean check` under caffeinate with the charger on. It finished with Passed: Total 81 and 1098, 0 failed, [success] in 1179 s, and exit 0. Log: phase-13-clean-check-1.log.
- Script harness, run 6 (human): 570 passed and 0 failed. bash -n and shellcheck were clean.
- Terraform: the last fmt, init and validate run (checks-1) was clean, and nothing in Terraform has changed since.
- A third repair round (S1) was authorized by the human, beyond the 2-round cap.

## Delivered
- F1: per-step timeouts. Caps stay at 60, 90 and 150 min. D10: after the Phase 14 S run, the M and L caps are re-derived from the measured S runtime (scaled by size, 1.5x margin, still bounded) as a recorded human decision before M runs. A timed-out run wastes its spend.
- F2: one shared 500 ms grace period.
- F4: the README check block is corrected.
- Scripts: `--dry-run` on all ten scripts. New scripts: check.sh, package.sh, run-experiment.sh and archive-evidence.sh.
  - Each run records the target account and Region and refuses without a valid approval record.
  - cleanup lists resources before deleting. Only apply-destroy requires --acknowledge-data-loss (D14).
- S1: run_bounded returns 124 whenever the wall-clock marker exists. A run that is cut off ends as STOPPED_WALL_CLOCK and is never COMPLETED.
- tests/scripts/: a 570-case local harness with stubbed aws, terraform, docker, curl and sbt.
- Also delivered: six draft experiment manifests, the root README automation section, experiments/README.md and load-tests/README.md.

## Accepted-open, carried forward
- From the reviews: S2 to S6 (round 2) and T1 to T6 (round 3).
- Docs (Phase 17 docs pass): T3, T5 and experiments/README.md:84.
- D3: there is no ratified launcher.
- D11: the guard blocks agent bash -n, shellcheck and terraform, so the human runs those checks with !. No factory change, by human decision.
- D12: experiment tier caps are provisional.
- D13: exit codes are not renumbered. Refusals exit 1 and are documented.
- R11: require_human detects Claude Code only, not Codex.

## Required in Phase 14
- run_bounded with real processes, including the S1 path.
- A real `docker login --password-stdin`, and a real `docker build` from a terminal.
- Re-derive the M and L caps from the measured S runtime (D10).
- Carried from Phase 12: P1, P3, P4, L1, the Paid-plan decision, and the plan-dependent gate items.

## Excluded from the commit
- AGENTS.md, .agents/ and .codex/ (local Codex setup), datasets/slice/, docs/agent-runs/phase-06/readback-paths.py, and the .DS_Store files.
