# Work order 16: R8 stale-documentation fixes in the files other owners hold

Used for two separate delegated runs, one per owner. Same limits: documentation wording only, no commands run, no invented results, no edits outside your own paths, no commit. Read first: docs/agent-runs/phase-13/review-round-1.md (R8), checks-3.md, decisions.md.

## experiment-engineer (experiments/README.md only)
experiments/README.md lines 5 and 77 say the dry runs are "pending, not run by anyone". checks-3.md shows harness cases `8.committed-manifest.A` to `F` passing (`RESULT pass=546 fail=0`): the six committed manifests parse and pass `MANIFEST_VALID` and `LIMITS` in a dry run inside the harness's temporary repository, with the entry point reported as `CHECK_SKIPPED`. Reword both places to say exactly that, no stronger: the manifests were dry-run by the harness (checks-3.md), not by `run-experiment.sh` against the real working tree (a dirty tree refuses), no approval record exists, nothing was executed, entry points are Phase 15. Keep every other statement. Note that `--print-digest` of a committed manifest now works although its entry point does not exist yet.

## documentation-engineer (README.md only)
README.md:30 lists the clean-checkout prerequisites without Docker. Add Docker (a running engine) for `package.sh` unless `--skip-images`, matching scripts/README.md after infra-engineer's repair; check the clean-checkout command block still matches scripts/README.md character for character after that repair (read it after work order 15 reports). Also note `check.sh` now reports `CHECK_RESULT=PASS_WITH_SKIPS` when a gate is skipped, once work order 15 has landed that; if you cannot see it in scripts/check.sh yet, do not state it.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
