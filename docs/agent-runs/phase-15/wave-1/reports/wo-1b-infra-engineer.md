# WO-1b report, infra-engineer (received 2026-11-17; preserved as received; author: infra-engineer subagent)

Status: COMPLETE for authoring; nothing validated (guard allows only `ls`). No AWS, plan, apply, sbt or commit. aws-documentation MCP not in the agent's tools, so item 7 stays UNVERIFIED.

## Deviation from the order
HDFS or S3 for shared stages alone does not make B work: `CollaborationPipeline` and `JsonlExport` read and write `java.nio` paths and `file:` URIs (`Inputs.fileUri`, `Algo.checkpoint`, `Files.isDirectory`), invisible to executors on core nodes. Done instead:
- Hadoop stage uses HDFS when a core node exists.
- Spark and export run `--master local[4]` on the primary when a core node exists.
- B's scale-out evidence therefore comes from the MapReduce stage, not Spark.
Alternatives to put Spark on core nodes (outside this order): EFS (infra) or `hdfs://` support in the Spark module (spark-engineer). Decision for the orchestrator and human.

## Proof per item (none run)
| Item | Proof | Status |
|---|---|---|
| 1 multi-node path | `bash -n`, `shellcheck -x` on `scripts/emr/*.sh`; harness case on `DATA_PATH` branches; real proof is B's run record `DATA_PATH_MODE=hdfs`, `YARN_REMOTE_NODES` >= 1 | Not run |
| 2 partition pass-through | static cases on `-Dscholarchain.run.partitionCount` (only when positive) and `--partitions "$shuffle"`; record keys `HADOOP_EXTRA_OPTIONS`, `SPARK_SHUFFLE_PARTITIONS` | Not run |
| 3 scale trigger | default-off check on four new MR keys; pending demand only provable in B (P15-11 otherwise) | Not run |
| 4 A observability | static cases on counters and event-log lines; record keys `MAPREDUCE_COUNTERS_FILES`, `SPARK_EVENT_LOG_PUBLISHED`, `INPUT_LINES_*` | Not run |
| 5 A overlay | `terraform validate` on `baseline-s.tfvars`; Rule A 1440 + 4800 + 600 = 6840 <= 7200 | Not run |
| 6 skip loader | `terraform validate`; `AfterTerminate` removed (not left unreachable) | Not run |
| 7 doc verification | not possible without MCP | UNVERIFIED |

## Files changed
- `scripts/emr/run-s-pipeline.sh`: optional 6th arg `profile` (`^[a-z0-9-]{0,32}$`, recorded `RUN_PROFILE`); `INPUT_LINES_<file>` keys; `DATA_PATH` auto detection: core 0 stays `file:///` (same URIs and flow), core 1 puts inputs under `hdfs:///user/hadoop/sc/run/<run-id>/raw`, MR output on HDFS, copied back to local `$hout`, HDFS run dir removed before the scale-in window; ER, publish and S3 keys unchanged; `PARTITION_COUNT`, `SPLIT_MAX_BYTES`, `MAP_MEMORY_MB`, `REDUCE_MEMORY_MB` as `-D` only when positive; `mapred job -status` per job into `reports/mapreduce-counters/` (gated by `MR_COUNTERS`); `SPARK_MASTER` auto with `LOCAL_CORES`, `LOCAL_DRIVER_MEMORY`; `--partitions "$shuffle"` on `CollaborationPipeline`; event log copied to `reports/spark-events/` before publish; key-material scan of report subdirectories; header explains the trade-off.
- `scripts/emr/lib-emr.sh`: new state variables; `conf_value` reads `<profile>.KEY` before `KEY`; `detect_remote_yarn_nodes`; `write_spark_conf` uncompressed event log only when `SPARK_EVENT_LOG=true`; `run_spark_submit` local-master override (original `--master yarn` and `timeout -k 30 "${SC_SPARK_TIMEOUT:-3000}"` lines untouched); `spark_verdict` uses exit status alone for local master.
- `scripts/emr/spark-yarn.conf`: new keys with defaults equal to old behaviour; profiles `a-baseline`, `b-burst` (b-burst values 16, 131072, 3072, 3072, 32 proposed, not tuned; experiment owner confirms).
- `infra/terraform/modules/workflow/main.tf`: optional `, '<profile>'` in `PipelineStep` Args only when `pipeline_profile` non-empty; `TerminateCluster` to `Done` and `AfterTerminate` removed when the loader is skipped.
- `infra/terraform/modules/workflow/variables.tf`: `skip_loader_after_pipeline`, `pipeline_profile`.
- `infra/terraform/environments/course/main.tf`, `variables.tf`: `pipeline_skip_loader`, `pipeline_profile`, wired.
- `infra/terraform/environments/course/baseline-s.tfvars` (new): A overlay, core 0, Neptune, Bedrock, services off, no Wait.
- `infra/terraform/environments/course/elasticity-s.tfvars`: skip loader on, profile `b-burst`, worst-case sum corrected to 7740.
- `scripts/README.md`: section "Phase 15 sessions A and B"; S3 key table row.

## Answers
- Session 1: two applies, A first (`baseline-s.tfvars`, only if re-run), then B (`elasticity-s.tfvars`), then `session-down`; each with its own plan and approval record.
- Loader: Terraform variable; execution ends SUCCEEDED; `"loader_command":["-version"]` in the templates is never used (state machine still requires the field in pipeline mode).
- Scale trigger: MR reducer count, split size, container size. No Spark dynamic allocation (executors cannot read `file:` data on a core node).
- Every changed script and tfvars has a new digest; earlier approval records void.

## Harness cases likely stale (inferred, not searched)
Static pins of the Hadoop command text (`"file://$hout" "file://$raw/dblp-subset.xml"`, `dargs+=("$f=file://$raw/$f.jsonl")`), the `run_spark_submit ... --out-root "$sout" || rc=$?` call, `spark-yarn.conf` keys/profile lines, workflow Args ending `$.run_id)"`, `Next = "AfterTerminate"`, the old 8340 figure in `elasticity-s.tfvars`. Existing `run_spark_submit` and `spark_verdict` cases should pass (`SC_SPARK_MASTER` defaults to `yarn`).

## Human commands
```
set -o pipefail; AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/scripts/check.sh --skip-sbt 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo1b-check.log | tail -n 60; echo "exit: $?"
set -o pipefail; AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo1b-harness.log | tail -n 60; echo "exit: $?"
```
Also `bash -n` and `shellcheck -x` on `run-s-pipeline.sh`, `lib-emr.sh`, `capability-check.sh` (check.sh does not reach `scripts/emr/`), and `terraform fmt -check -recursive` and `terraform validate`.

## Open items and risks
1. B's Spark stage is local-master on the primary.
2. Hadoop stage limit hard-coded `timeout -k 30 1500` (a harness case pins it); a 16-reducer run on 3 GiB containers may need more.
3. `SC_JAVA_HOME` assumed at the same path on core nodes (UNVERIFIED).
4. `yarn node -list` and `mapred job -status` output formats UNVERIFIED; if detection returns `unknown` the script falls back to `file:///`, which fails on core 1.
5. Spark event log size for the 5,850-stage application UNVERIFIED (tens to hundreds of MB).
6. EMR managed scaling limits, primary-as-unit, scale-in within 900 s UNVERIFIED; P15-11 applies.
7. WO-1 pre-session checks still UNVERIFIED.
