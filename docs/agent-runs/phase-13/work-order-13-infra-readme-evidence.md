# Work order 13: infra-engineer, make scripts/README.md and infra/terraform/README.md state the observed results

Owner: infra-engineer. Edit scripts/README.md and infra/terraform/README.md only (wording; no behavior change, no script edit, no Terraform edit, no figure changes). Same limits as before: no AWS, no terraform, no docker, no sbt; guard refuses agent bash (D11); no commit.

Read first: docs/agent-runs/phase-13/checks-1.md, checks-2.md, checks-3.md, report-09 and report-04, scripts/README.md (the "Dry run", "Exit codes", "Timeouts and retries", pending-checks paragraphs and "Open items"), infra/terraform/README.md "Checks to run".

Do:
1. Replace every statement in scripts/README.md that says `bash -n`, `shellcheck`, the tripwire, the refusal cases or the smoke-test physical-path check "have not been run" or are "UNVERIFIED" with the observed result and its source, but only as far as checks-3.md proves it: `bash -n` and `shellcheck -x` clean on the ten scripts and the harness; the harness `RESULT pass=546 fail=0` covers `--help`, `--print-digest`, `--dry-run`, the refusal paths and the smoke-test `--out-dir` symlink, `//` and case-change cases (the earlier "UNVERIFIED beyond syntax" note about that check now has a first test; say it is exercised by cases `5.smoke.out-dir-*` and still not a defence against a symlink swapped after the check).
2. Keep UNVERIFIED, accurately, and list them in one short block: `run_bounded` with a real docker or sbt process, Ctrl-C forwarding, `docker login --password-stdin` through the wrapper, every real AWS call, the `aws s3vectors get-vector-bucket` command and its not-found text, the cleanup live listing, plan reproducibility, the ratified launcher (open item D3), and the tier caps for run-experiment (D12).
3. Document the two repairs in the text where they belong: `verify_approval` now takes the stateful-acknowledgement requirement and refuses before printing `APPROVAL_VERIFIED`; `--print-digest` of run-experiment.sh tolerates an entry point that is not yet a tracked file (a real run still refuses).
4. In infra/terraform/README.md "Checks to run", record that the F1 timeout files passed `fmt -check -recursive -diff`, `init -backend=false` and `validate` on 2026-11-11 (checks-1.md) and that `plan` still has not been run (Phase 14).
5. Do not widen any claim. Say "observed" only for checks-1 and checks-3 results.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
