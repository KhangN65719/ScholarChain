# Work order 20: repair round 3 (human-authorized, decisions D15): S1 and the S7 wording. Four delegated runs, one per owner.

Same limits: do exactly what is listed, nothing else (S2 to S6 are accepted-open and must NOT be touched); no new cases, no new tools, no sbt, no commit, no edits outside your own paths, read and edit only (guard, D11), shellcheck-clean by careful reading. Read first: docs/agent-runs/phase-13/review-round-2.md (S1 and S7), checks-4.md, checks-5.md, decisions.md D15.

Evidence facts to use in every wording fix (and nothing stronger): the harness was observed at `RESULT pass=570 fail=0` with `bash -n` and `shellcheck -x` clean (checks-5.md, 2026-11-11, after repair round 2); the earlier 546 figure is the pre-repair-round-1 result in checks-3.md. That run covers syntax, lint, the `--help`, `--print-digest`, `--dry-run` and refusal paths only; it does not exercise `run_bounded`'s process-group stop with real processes (including the S1 case), the real smoke steps, the pipeline record file, or any live call. The repair of S1 itself (below) is not covered by any run until the human reruns.

## infra-engineer (scripts/lib.sh and scripts/README.md)
- S1, lib.sh:628-634 in `run_bounded`: when the timeout marker exists, return 124 whatever `rc` is (including 0). Keep the marker removal. Keep every other path (finish before the limit, TERM sufficient giving 124, KILL needed giving 124, nonzero exit on its own with no marker) exactly as it is. Reread `run_bounded` and confirm in the report, case by case, that the callers (run-experiment.sh finalize logic, the docker calls in deploy.sh) handle a 124 that arrives with `rc=0` correctly: the experiment must be recorded as the wall-clock stop (`STOPPED_WALL_CLOCK` or the existing equivalent) and not `COMPLETED`.
- scripts/README.md:106: correct the claim so it states what the code now does (the marker decides; 124 is returned whenever the limit fired, even if the entry point caught TERM and exited 0). Check :205 (entry points should trap TERM) still reads true and say what happens to a run whose entry point exits 0 after the limit: it is recorded as stopped at the wall clock, not completed. Mark the whole behavior UNVERIFIED with real processes.
- S7 wording in scripts/README.md :3, :84, :320: replace "unrun" and the 546 figure with the observed state per the evidence facts above. No other text.
- SCRIPT_DIGEST of every script changes again (no approval records exist): say so.
- Reread the touched region for shellcheck issues (no `A && B || C`, quoted expansions, no unused variables).

## test-engineer (tests/scripts/README.md only)
- S7 wording at :7-8: replace the 546 and "unrun" text with the observed state per the evidence facts (570 pass, 0 fail, checks-5.md). Say that S1's repair (lib.sh `run_bounded`) is not covered by any case and cannot be with the dry-run harness (D8); real-process behavior is a Phase 14 check. Do not add or edit a case in run.sh.

## documentation-engineer (README.md only)
- S7 wording at :46, :83, :93: same replacements per the evidence facts. Keep every other line. Do not claim the S1 repair is run or tested.

## experiment-engineer (experiments/README.md only)
- S7 wording at :77: "the first line below stops at `clean-tree`" is wrong: the first line is `--print-digest`, which exits before `clean-tree` (run-experiment.sh:249 per the review). Read the paragraph and the script's order of steps and make the sentence true for each command listed there (print-digest exits before any record or tree check; dry run reaches `clean-tree` on a dirty tree and stops there). Do not change anything else.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS (not run), VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
