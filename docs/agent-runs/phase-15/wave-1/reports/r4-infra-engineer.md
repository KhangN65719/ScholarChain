# R4 report, infra-engineer (received 2026-11-17; preserved as received; author: infra-engineer subagent)

Status: COMPLETE for items 1 to 9; no checks run (bash -n, shellcheck, terraform, sbt, harness barred). Item 9's validate API written from memory (orchestrator later verified the API exists; see `doc-verification.md`).

## Proof per item (by what must be asserted)
| Item | Assertion |
|---|---|
| 1 K2 | render of `elasticity-s` Min 1, Max 3, MaxCore 3, MaxOnDemand 3; `elasticity-example` 4/4/4/4; core 0 emits no `ManagedScalingPolicy` |
| 2 K1 | comment only; Rule A 7140 <= 7200 |
| 3 M1 | conf pins present; `DATA_PATH=hdfs` with 0 remote nodes dies clearly, no `file://` fallback (dynamic case with stubbed `yarn node -list`) |
| 4 M2 | `b-burst.SHUFFLE_PARTITIONS=8`, `b-burst.SPARK_EVENT_LOG=false` |
| 5 M4 | `spark-run-manifest.json`, `export-manifest.json` in `reports/` |
| 6 m2 | `RUN_SCOPE` matches the resolved mode |
| 7 m7 | `timeout` precedes every new `hdfs dfs` call |
| 8 a1 | large event log no longer fails the run; a match still dies |
| 9 a5/a7 | README items 4 and 5 present |

## Diffs
1. `modules/emr/main.tf`: `emr_managed_scaling_max_units` keeps meaning total instances including the primary; new `scalable_max = max_units - 1`; Min = core (was core + 1); Max = scalable_max (was max_units); MaxCore = max(core, scalable_max - tasks, 1) (same value); MaxOnDemand = scalable_max (was max_units); comment cites `API_ComputeLimits.html` and `managed-scaling-allocation-strategy.html` Scenario 1. `modules/emr/variables.tf` description updated. `elasticity-s.tfvars` bounds comment rewritten; `elasticity-example.tfvars` header updated (still cannot scale).
2. `elasticity-s.tfvars`: pipeline stop about 88 min (5280 s, P15-16), manifest 90 (5400 s) is the tier cap; 5280 s above realistic ~4300 s and below 7200 s; Rule A unchanged 1440 + 4200 + 900 + 600 = 7140; O1 worst case 7740; profile comment says event log off, shuffle 8.
3. `scripts/emr/spark-yarn.conf`: `a-baseline.DATA_PATH=file`, `a-baseline.SPARK_MASTER=yarn`, `b-burst.DATA_PATH=hdfs`, `b-burst.SPARK_MASTER=local`; new `NM_WAIT_SECONDS=300`; `lib-emr.sh` `wait_for_remote_yarn_nodes` (re-detect every 15 s); `run-s-pipeline.sh`: forced `hdfs` waits, then dies clearly if the count is a number equal to 0; `unknown` is recorded and the run continues.
4. `b-burst.SHUFFLE_PARTITIONS=8`, `b-burst.SPARK_EVENT_LOG=false`; `MR_COUNTERS` stays true.
5. New `copy_report_file` in `lib-emr.sh`: after Spark, `$srun/run-manifest.json` to `reports/spark-run-manifest.json`; after export, `$xout/export/export-manifest.json` to `reports/export-manifest.json`; best effort (missing gives NOTE and `...=missing`); recorded `SPARK_RUN_MANIFEST_COPY`, `EXPORT_MANIFEST_COPY`; top-level, scanned and published.
6. Early `RUN_SCOPE` removed; recorded in the hadoop stage after data path and Spark master resolve (Spark master resolution moved there); text covers HDFS + local Spark, file + yarn, generic fallback; a run failing before hadoop has no `RUN_SCOPE`.
7. `timeout 60 hdfs dfs -mkdir -p`; `-put` 300, `-get` 600, `-rm` 300 already bounded; older `hdfs` calls in `stage_spark` untouched.
8. `scan_no_secrets_bounded` (120 s, 256 MiB cap `SC_EVENT_LOG_SCAN_MAX_BYTES`) scans event logs before the run record; over cap or unfinished: removed from the reports copy, not published, named in a NOTE and `SPARK_EVENT_LOG_PUBLISHED`, run continues; a match still dies; strict subdirectory scan excludes `spark-events/`.
9. `scripts/README.md` "Phase 15 pre-session checks": item 4 read-only DLQ depth for `<name_prefix>-run-requests-dlq` and `<name_prefix>-embedding-work-dlq`; item 5a rendered definition from the saved plan via `terraform show -json` (cannot run if unknown until apply); item 5b validate call.

## Rendered ComputeLimits
| Overlay | Core / tasks / cap | Min | Max | MaxCore | MaxOnDemand |
|---|---|---|---|---|---|
| elasticity-s | 1 / 0 / 4 | 1 | 3 | 3 | 3 |
| elasticity-example | 4 / 0 / 5 | 4 | 4 | 4 | 4 |
| course, baseline-s, experiment-size-s/m/l, session-down, cleanup, persistent.example, graph-load | core 0 | none | none | none | none |

## Risks
Harness cases to change: `run.sh:9626` (`min = 1 + core`), `run.sh:10622` (pins), any case matching old `RUN_SCOPE` text or the old `local smaster shuffle` line. Human runs `bash -n`, `shellcheck -x scripts/emr/*.sh`, terraform fmt/validate, harness.

Files: `infra/terraform/modules/emr/main.tf`, `modules/emr/variables.tf`, `environments/course/elasticity-s.tfvars`, `elasticity-example.tfvars`, `scripts/emr/spark-yarn.conf`, `lib-emr.sh`, `run-s-pipeline.sh`, `scripts/README.md`.
