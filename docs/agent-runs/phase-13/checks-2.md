# Human check run 2 (2026-11-11): bash -n, shellcheck, harness

Logs: /Users/winnguyen/scholarchain-data/logs/phase-13-checks-2.log and phase-13-harness-1.log. Command ran `bash -n scripts/*.sh tests/scripts/run.sh`, `shellcheck -x` on the same, then `bash tests/scripts/run.sh`. Overall CHECKS_RC=1.

## bash -n
No output: passed on all scripts and on tests/scripts/run.sh.

## Harness: RESULT pass=539 fail=7 (observed)
- FAIL `5.cleanup-destroy.destroy-without-stateful-ack`: the refusal happened (rc=1, `EVIDENCE: destroy removes stateful resources: the record must set STATEFUL_CHANGE_ACKNOWLEDGED=yes`) but `APPROVAL_VERIFIED` was also printed in stdout. CORRECTION (infra-engineer, report 09, by reading): the case asserts `APPROVAL_VERIFIED` is NOT printed (`a_refused`, run.sh:275-282); my first note had this backwards. The script was wrong: `verify_approval` printed `APPROVAL_VERIFIED` (lib.sh:511) before cleanup.sh refused the stateful acknowledgement. A script defect, not a test defect.
- FAIL `8.committed-manifest.A` to `F` (six cases): `--print-digest` exited 1 with `EVIDENCE: approval record has an empty SCRIPT_DIGEST`. Either `run-experiment.sh --print-digest` verifies an approval record before printing the digest the record needs (circular, a script defect), or the case passes the record in a way the script treats differently from the other scripts. The other nine scripts passed case 2 (`--print-digest`). Undetermined until read.
- 539 other cases passed, including the tripwire (no stub reached in any dry run), the consumed-record cases, the smoke-test out-dir symlink, `//` and case checks, and the safety controls.

## shellcheck (scripts, route to infra-engineer)
- archive-evidence.sh:101 and :105: SC2221 and SC2222. `*credential* | *secret*` always overrides `.git-credentials` on line 105, so that pattern never matches. The file is still refused (by the broader pattern), only with a different reason text.
- check.sh:13: SC1073 and SC1072, errors. A comment line starting `# shellcheck, JDK 21 and sbt ...` is parsed as a directive. shellcheck could not parse it, so the file may not have been fully linted. Reword the comment.
- package.sh:279: SC2018 and SC2019 (info), `tr 'a-z' 'A-Z'`.

## shellcheck (tests/scripts/run.sh, route to test-engineer)
- :517 SC2054 (comma in array element; the value is one argument `first-failure,dlq-nonempty`).
- :545 `D_RC`, :558 `SC_SCRIPT`, :561 `SC_APPROVAL_FILE`, :562 `SC_ENV`: SC2034 unused (the last three may be consumed by sourced library functions).
- :944 and :1232: SC2018 and SC2019 (info).

The bash -n and terraform results from checks-1.md still stand.
