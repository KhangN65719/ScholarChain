# Work order 24: test-engineer, update the stubbed harness for the new pipeline input contract and the new scripts

Owner: test-engineer (tests/**). Edits in tests/** only. No AWS, no docker, no commit. You cannot run bash (guard D11): state that nothing was executed; the human runs `bash -n`, `shellcheck -x` and `bash tests/scripts/run.sh`. Agents may run the bare `sbt quick` only; this order needs no sbt.

Evidence: docs/agent-runs/phase-14/report-20-infra-engineer-emr-design-b.md (read it fully), scripts/run-bounded-pipeline.sh (read the input validation and record changes), scripts/upload-inputs.sh, scripts/emr/*.sh, scripts/README.md (approval record table, `upload` action), tests/scripts/run.sh and tests/scripts/README.md (how the harness stubs aws, terraform, docker, curl, sbt; the stubbed harness was 682 cases and passed).

Tasks:
1. Find every harness case that sends an old-shape pipeline input (`hadoop_args`, `spark_args`, `loader_command`) or asserts `DATASET_SNAPSHOT=not-recorded` or the old record fields, and update it to the new contract (`{"mode":"capability-check"}` and mode pipeline with `snapshot`, `snapshots`, `run_id`, `loader_command`; `DATASET_SNAPSHOT` is now `n/a` or the snapshot id). Keep every existing check's intent; do not delete a refusal case.
2. Add cases for the new refusals: unknown mode, unknown key, old `hadoop_args` shape, bad run id, empty `loader_command`, snapshots not naming exactly the supplied datasets (if the script checks it), capability-check input accepted, and the record fields written for each mode.
3. Add cases for `scripts/upload-inputs.sh` through the stubs (dry run, `--print-digest`, approval record action `upload` required, `--max-objects` and `--max-bytes` refusals, refusal of a symlink or of key-material names, no `--sse-c`, no `sync`, no `--delete`, one `aws s3 cp` per file, `head-object` verification), following the style of the existing approval-gated script cases.
4. Static content cases for scripts/emr/*.sh (a harness may grep them as text): no credential printing patterns, `set -euo pipefail`, no `--sse-c`, the `MapReduce in local mode on an EMR node` wording present where local mode is reported and no "distributed MapReduce" claim.
5. Report the exact expected counts (previous 682 plus the changes), list the files for the human's `bash -n` and `shellcheck -x`, and say that none of it was executed.

Report contract: STATUS, OWNER, WORK ORDER, FILES MODIFIED, CASES CHANGED AND ADDED (names), COMMANDS RUN, VALIDATION RESULTS (none executed), RISKS, BLOCKED-ON OWNER (say what to send to infra-engineer if a script defect is found; do not fix scripts), NEXT GATE. Your final message must be the complete report text.
