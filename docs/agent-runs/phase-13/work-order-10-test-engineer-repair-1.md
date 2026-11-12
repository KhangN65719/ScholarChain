# Work order 10: test-engineer, repair 1 (human check run 2)

Owner: test-engineer. Local only. Same limits as work order 07: edit only tests/scripts/**, never scripts/**, no new tools, no sbt, no commit, do not weaken or delete a case. The guard refuses agent bash and shellcheck (D11).

Read first: docs/agent-runs/phase-13/checks-2.md (observed output), decisions.md, tests/scripts/run.sh, tests/scripts/README.md, scripts/cleanup.sh and scripts/run-experiment.sh (read only).

Fix in tests/scripts/run.sh:
1. shellcheck: :517 SC2054 (the comma is part of one argument, quote or restructure so the intent is clear); :545 `D_RC`, :558 `SC_SCRIPT`, :561 `SC_APPROVAL_FILE`, :562 `SC_ENV` SC2034 (if a sourced `lib.sh` function reads them, add `# shellcheck disable=SC2034` on its own line above with the reason; if nothing reads a variable, remove it); :944 and :1232 SC2018 and SC2019 (use `[:lower:]` and `[:upper:]`). Re-read the whole file once more for anything else shellcheck would flag, since the human reruns it.
2. Case `5.cleanup-destroy.destroy-without-stateful-ack`: the script refused correctly (rc=1, evidence `destroy removes stateful resources: the record must set STATEFUL_CHANGE_ACKNOWLEDGED=yes`) but the case also asserted `APPROVAL_VERIFIED` in stdout. Read cleanup.sh and decide whether that expectation is wrong. If the script refuses before printing `APPROVAL_VERIFIED` and that order is sensible, change the case to assert the refusal and the evidence text, and assert that `APPROVAL_VERIFIED` is NOT printed. If you judge the script's order wrong, leave the case failing and report it as a finding for infra-engineer. State which and why. Do not weaken the case to make it pass.
3. `8.committed-manifest.A` to `F` fail because `--print-digest` of run-experiment.sh exited 1 with "approval record has an empty SCRIPT_DIGEST". infra-engineer is investigating in parallel (work order 09) whether the script or the harness call is wrong. Do NOT edit these cases or the call now. Only read run-experiment.sh and write in your report, from the code, how your harness calls `--print-digest` for run-experiment compared with how it calls it for the other scripts (case 2), so the two analyses can be compared.

Report which case proves each repair. The human reruns `bash -n`, `shellcheck -x` and `bash tests/scripts/run.sh`; never report a case as passing without seeing its output.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
