# Report 09: infra-engineer, repair 1 (as received, condensed)

STATUS: COMPLETE for the edits. Nothing run (guard, D11); every conclusion is from reading.

Files modified (scripts/): archive-evidence.sh, check.sh, package.sh, run-experiment.sh, lib.sh, cleanup.sh.

1. archive-evidence.sh:105: `.git-credentials` removed from the line-105 pattern because `*credential*` on line 101 already matched it. Refused set unchanged; no name changes its reason.
2. check.sh:13: comment reworded ("the shellcheck program, JDK 21 and sbt ...", re-wrapped so no line starts with `shellcheck,`). Whole file re-read: no other directive-like comments, unused variables, `A && B || C`, or quoting problems seen. shellcheck never parsed this file cleanly, so the next run may report new findings in it.
3. package.sh:279: `tr 'a-z' 'A-Z'` to `tr '[:lower:]' '[:upper:]'`; same result for `services` and `loader`.
4. `8.committed-manifest.*` (script defect, not circular): `--print-digest` exits at run-experiment.sh:249 and never reads a record. The "empty SCRIPT_DIGEST" came from the harness's second run (run.sh:1238-1240): the first `--print-digest` died in `validate_manifest` (run-experiment.sh:205-210) because the committed manifests name entry points that do not exist yet (experiments/README.md:3), and only a dry run tolerated that (:207). It also broke the documented draft workflow in experiments/README.md:79-80. Fix: `:207` now `elif is_dry_run || [[ "$SC_PRINT_DIGEST" -eq 1 ]]; then ENTRY_OK="no"`; a real run still refuses at :210. No tests/** change needed. Inferred from code; confirmed only when `8.committed-manifest.*` passes.
5. `5.cleanup-destroy.destroy-without-stateful-ack`: the script was wrong. `verify_approval` printed `APPROVAL_VERIFIED` (lib.sh:511) before cleanup.sh refused at :195 and :346. Fix: `verify_approval` takes an optional fifth argument `yes`; when given it checks `STATEFUL_CHANGE_ACKNOWLEDGED=yes` after every other check and before printing `APPROVAL_VERIFIED`; same refusal text. cleanup.sh passes `yes` for `apply-destroy` in the dry-run branch (:193-197) and the real branch (:345); the old post-check lines are gone.
6. `plan-destroy` untouched (D14).

Assumptions and risks: lib.sh changed, so every SCRIPT_DIGEST changed (no approval records exist). scripts/README.md may describe the old cleanup flow or `--print-digest` behavior; not checked. Item 4 cause inferred. Item 5 changes shared lib.sh, but the new parameter defaults to `no`.

Human check pending: `bash -n scripts/*.sh`, `shellcheck -x scripts/*.sh`, `bash tests/scripts/run.sh` (agent expects 546 pass if both fixes are right).
