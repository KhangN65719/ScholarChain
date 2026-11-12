# Work order 09: infra-engineer, repair 1 (human check run 2)

Owner: infra-engineer. Local only. Same limits as work order 04 (no AWS, no terraform plan/apply, no docker, no sbt, no edits outside scripts/** and infra/**, nothing in CLAUDE.md, docs/agents.md or `.claude/**`, no commit). The guard still refuses agent bash, shellcheck and grep (D11): read and edit only, and list the human checks.

Read first: docs/agent-runs/phase-13/checks-2.md (the observed output), decisions.md, scripts/archive-evidence.sh, scripts/check.sh, scripts/package.sh, scripts/run-experiment.sh, scripts/lib.sh, tests/scripts/run.sh (read only, cases `2.print-digest.*` and `8.committed-manifest.*`).

Fix, without changing the behavior of any real path except as stated:
1. archive-evidence.sh:101 and :105 (SC2221, SC2222): `.git-credentials` never reaches its own pattern because `*credential*` matches first. Reorder or merge so each pattern is reachable and the refusal reason is accurate, with the same set of refused names as now (no weakening). Say which names change reason.
2. check.sh:13 (SC1073, SC1072 errors): a comment starting with the word `shellcheck,` is parsed as a directive. Reword it. Because shellcheck could not parse this file cleanly, read the whole file once more for anything else it may have skipped (quoting, unused variables, `A && B || C`).
3. package.sh:279 (SC2018, SC2019 info): use `[:lower:]` and `[:upper:]` classes with identical results for the ASCII role names used.
4. The six `8.committed-manifest.*` harness failures. `bash scripts/run-experiment.sh --print-digest --manifest <file> ...` exited 1 with `EVIDENCE: approval record has an empty SCRIPT_DIGEST`. Read run-experiment.sh and tests/scripts/run.sh and decide which side is wrong:
   - If `--print-digest` verifies (or even reads) an approval record before printing the digests the record needs, that is circular and a script defect: `--print-digest` must exit before any record is read, like the other scripts, and print SCRIPT_DIGEST and ARGS_DIGEST (the ARGS_DIGEST computed from the manifest and arguments only). Fix it and say so.
   - If the script is right and the harness calls it wrongly, do NOT edit tests/**: report the exact call the harness should make, as a finding for test-engineer.
   State your conclusion with the line numbers.
5. Re-read cleanup.sh near the `STATEFUL_CHANGE_ACKNOWLEDGED` refusal for the case `5.cleanup-destroy.destroy-without-stateful-ack`: it refused with the right evidence but the harness expected `APPROVAL_VERIFIED` in stdout first. Report, from the code, whether the script prints `APPROVAL_VERIFIED` before or after that refusal and whether the order is intended; do not change the order unless the current order is itself a defect (a stateful-destroy record that fails the acknowledgement check should not be reported as verified). Tell me which side you judge wrong.
6. `plan-destroy` and `--acknowledge-data-loss`: no change; the human's rule applies to `apply-destroy` (decision D14 below). Do not edit.

D14 (orchestrator, conservative): `plan-destroy` stays without `--acknowledge-data-loss`; a plan deletes nothing. The reviewer may challenge it.

Checks the human will run afterwards: `bash -n scripts/*.sh`, `shellcheck -x scripts/*.sh`, `bash tests/scripts/run.sh`.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Report only commands you observed succeed.
