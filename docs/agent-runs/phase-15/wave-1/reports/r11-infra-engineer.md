# R11 report, infra-engineer (received 2026-11-17; summary of the report as received; author: infra-engineer subagent)

Status: COMPLETE for rows 5 and 6 and the R7 risk; row 7 `BLOCKED-ON test-engineer`. Nothing executed (guard). Files: `scripts/emr/run-s-pipeline.sh`, `scripts/README.md`.

- Row 5: pre-session item 4 DLQ loop (ended `tail -n 20`) split into 4a (run-requests) and 4b (embedding-work), each `set -o pipefail; AWS_PROFILE=scholarchain aws sqs get-queue-attributes ...` with the URL from a nested `get-queue-url`, us-east-2, tee'd to `phase15-precheck-dlq-depth-<queue>.log`, ending `| tail -n 60; echo "exit: $?"`.
- Row 6 (script): the die message at `run-s-pipeline.sh:214` printed `${profile:-none}`, a local of `pipeline_main` unset in the cut block; `conf_value` reads `SC_PROFILE` (`lib-emr.sh:468`, set at `run-s-pipeline.sh:96`). Message now uses `${SC_PROFILE:-none}`. Resolution order was correct.
- R7 risk (real): `wait_for_remote_yarn_nodes "$(conf_value NM_WAIT_SECONDS ...)"` did not stop on `die` in the substitution. Now `nm_wait="$(conf_value NM_WAIT_SECONDS 300 '^(0|[1-9][0-9]{0,3})$')" || exit 1` then `wait_for_remote_yarn_nodes "$nm_wait"`; `nm_wait` added to the stage's `local` line; die message uses `$nm_wait`. Cut markers unchanged.
- Row 7 (test plumbing): case at `tests/scripts/run.sh:10507-10514` runs child mode `smaster` (line 10055), which prints MASTER, MODE, SHUFFLE, DRIVERMEM but never `SMASTER`; only mode `data` (line 10053) prints it. Suggested repair: add `SMASTER=%s` / `"$smaster"` to the `smaster)` tail.
- Same defect class found, not changed: `run-s-pipeline.sh:144-145` `write_spark_conf "$(conf_value POPULATE_HADOOP_CLASSPATH ...)"`; `run-s-pipeline.sh:369` `[[ "$(conf_value MR_COUNTERS ...)" == "true" ]]`.
- Risk: a static case may pin the old literal `wait_for_remote_yarn_nodes "$(conf_value NM_WAIT_SECONDS`.

Human commands: `bash -n` on `run-s-pipeline.sh` and `lib-emr.sh` (logs `phase15-r11-1..2.log`); `shellcheck -x scripts/emr/run-s-pipeline.sh scripts/emr/lib-emr.sh` (`phase15-r11-3.log`).

## R11b (received 2026-11-17; summary as received)
COMPLETE, not executed. Every `$(conf_value ...)` in `scripts/emr/` is now a plain assignment to a declared local ending `|| exit 1` (`capability-check.sh` has none).
- POPULATE_HADOOP_CLASSPATH: `populate="$(conf_value ...)" || exit 1`, then `write_spark_conf "$populate"` and `kv ... "$populate"`; `populate` added to `pipeline_main`'s `local` line.
- MR_COUNTERS: `local mr_counters mrc=0`; `mr_counters="$(conf_value ...)" || exit 1`; `if [[ "$mr_counters" == "true" ]]`.
- `|| exit 1` suffix added to existing assignments: run-s-pipeline.sh `spark_events`, `dmode_conf`, `smaster`, `local_cores`, `pc`, `split`, `mapmb`, `redmb`, `mode`, `SC_LOCAL_DRIVER_MEMORY`, `shuffle`; lib-emr.sh `write_spark_conf` `evlog`, `dm`, `em`, `ec`, `ei`, `am`.
- Harness cut markers unchanged (`local dmode dmode_conf hrun=""`, `pc="$(conf_value PARTITION_COUNT`, `local dsets=(crossref`, `local shuffle`, `mkdir -p "$sout"`).
- Human commands: `bash -n` on both files and shellcheck (logs `phase15-r11b-1..3.log`).

Orchestrator follow-up: row 7 and the static-literal risk sent to test-engineer (R10); the two same-class sites sent back to infra-engineer as R11b in this batch.
