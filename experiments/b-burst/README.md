# Experiment B: Hadoop and Spark burst, EMR managed scaling (Phase 15, Wave 1)

Status: authored, NOT executed. No AWS call, no run, no approval record. Rehearsals on synthetic fixtures (`rehearse.sh`, `rehearse-collect.sh`, `../rehearse-manifest-json.sh`, see "Rehearsal") are the only checks; their results are in the Wave 1 reports, not here.

## Design
- Data: the tier S subset (3,472 works), same inputs as S run 6. The load increase is a higher reducer count, a small split size and large containers (decision P15-7): the `b-burst` profile in `scripts/emr/spark-yarn.conf` (`PARTITION_COUNT=16`, `SPLIT_MAX_BYTES=131072`, `MAP_MEMORY_MB=3072`, `REDUCE_MEMORY_MB=3072`). The default pipeline is unchanged. A larger manifest is not used.
- Cluster: `elasticity-s.tfvars` (infra-engineer): primary 1, core 1, managed scaling Min 1, Max 3, core-only (EMR limits count core and task nodes only, `docs/agent-runs/phase-15/wave-1/doc-verification.md` K2), so at most 4 instances in all; Neptune off, Bedrock off; a bounded Wait before `TerminateCluster` so a scale-in can happen while the cluster is still up.
- Entry point: `run.sh` starts ONE pipeline execution through `scripts/run-bounded-pipeline.sh` (mode `pipeline`, size `medium` for the 90 minute tier cap, `--max-minutes 88`, burn 1.55 USD/h, spend ceiling 2.30 USD) and waits. The manifest limit is `MAX_MINUTES=90` and `SPEND_CEILING_USD=2.40` (decision P15-16: the full tier S cap; the earlier 74 minute stop left about 140 s of slack in the realistic case and a stop cuts the scale-in window). The entry limit stays strictly above the pipeline limit. It tunes nothing, retries nothing and calls no other AWS verb.
- Loader: skipped by the Terraform variable `pipeline_skip_loader` in `elasticity-s.tfvars` (the state machine goes from `TerminateCluster` to done and the execution ends SUCCEEDED). The `"loader_command":["-version"]` in the execution input template is required by the pipeline input contract and is never used.
- Capacity over time is collected afterwards, read-only, by `collect.sh`, and turned into tables and a plot by `derive-capacity.py` from the raw files only. The MapReduce counters of every job (`MR_COUNTERS=true` in the profile) land in the on-node reports.

## Limitations
- Decision P15-12 (accepted 2026-11-17): in B, Hadoop MapReduce runs on HDFS across the core nodes. The two Spark applications and the export stay on the primary node (`local[4]`), because they read and write `file:` paths. B's scale-out and scale-in evidence therefore comes from the MapReduce stage only. Spark does not use the added nodes, so B does not show Spark scaling and its results must not be read as such. If B shows no clear scale-out and scale-in, decision P15-11 applies (D's ECS scaling is the second source).
- Scale-out evidence is demand-linked only (review 1, M3). `derive-capacity.py` reads the managed-scaling policy and CloudWatch `ContainerPending` and `ContainerPendingRatio`; an instance that only restores the policy floor (for example a replacement of a lost node), or that has no pending-container evidence, is listed but is not counted as scale-out, and the summary then says `scale_out_observed` `unconfirmed`, not `yes`. `get-managed-scaling-policy` on a terminated cluster is UNVERIFIED; if the call fails, the collector records it and the derive step reports `policy_available` `no`, so no instance can be labelled floor-restoring in that case.
- Whether the HDFS `-get` (copy of the MapReduce output back to the primary) also copies the `.crc` checksum files is UNVERIFIED. If it does not, the objects published under `curated/runs/<run id>/hadoop/` can differ from a file-mode run (A) by those files, while the S3 keys of the data files stay the same. Compare the object listings of A and B before saying the outputs are identical.
- B ends after the Wait; HDFS data of the run is removed before the Wait (`run-s-pipeline.sh`), so a scale-in is not blocked by blocks of ours.

- Decision P15-20 (accepted 2026-11-17): if the 88-minute pipeline stop fires, the cluster is still terminated and cost stays bounded, but the teardown check after the stop can take up to 300 s (`scripts/run-bounded-pipeline.sh`). Only about 2 minutes remain before the 90-minute entry limit, so the entry kill may cut the end of the run record (`window.json`, the last `events.jsonl` lines). Read a run that hit the stop with that in mind; the pipeline record and the collected Step Functions and EMR files still show the end state.

## Before a results commit (redaction, review 1 a3)
The raw files hold the AWS account ID inside ARNs: `<run dir>/raw/window.json`, `raw/pipeline/*.record.env`, `raw/pipeline-run.log`, and in `collected/` the files `sfn-execution.json`, `sfn-history.json`, `emr-describe-cluster.json` and any `*.error.txt`. The raw files in the run directory stay exactly as written (immutable). A copy for a commit is made from them with the 12-digit account ID replaced by `000000000000`, for example:
```
ACCOUNT_ID="REPLACE_WITH_YOUR_12_DIGIT_ACCOUNT_ID"
COPY="/abs/outside/repo/results-copy-REPLACE"
mkdir "${COPY}" "${COPY}/pipeline" && cp -R "${RUN_DIR}/collected" "${COPY}/collected" && cp "${RUN_DIR}/raw/window.json" "${RUN_DIR}/raw/pipeline-run.log" "${COPY}/" && cp "${RUN_DIR}"/raw/pipeline/*.record.env "${COPY}/pipeline/"
find "${COPY}" -type f ! -name '*.bak' -exec sed -i.bak "s/${ACCOUNT_ID}/000000000000/g" {} +
```
This covers `window.json`, `pipeline-run.log`, `pipeline/*.record.env` and every file under `collected/` (`*.json`, `*.txt`, `*.error.txt`, CSV), not only `collected/*.json`. The sources under `${RUN_DIR}` are only read, never edited. Remove the `.bak` files from the copy afterwards (`find "${COPY}" -name '*.bak' -delete`), and search the copy for the real ID before committing it (`grep -r "${ACCOUNT_ID}" "${COPY}"` must print nothing; the check, not the rule, is the control). The derived files (`derived/`) carry no account ID and may be committed as they are.

## Open prerequisites
Items resolved by infra-engineer since the first draft (HDFS path with `DATA_PATH=hdfs`, the reducer and container settings in the `b-burst` profile, S caps of 90 minutes, `skip_loader_after_pipeline`) are not repeated here. Still open and UNVERIFIED until a run:
1. Whether the 16 reducers on 3 GiB containers produce pending YARN containers on one core node (the NodeManager memory of an m5.xlarge is not read). If nothing is pending, nothing scales out; decision P15-11 applies.
2. Whether the Hadoop stage fits its fixed `timeout -k 30 1500` with HDFS put and get and 16 reducers per stage.
3. `scripts/run-experiment.sh --print-digest` on the B manifest and a new approval record: the manifest changed (limits, metric names), so every earlier record is void.

## Files
| File | Role |
|---|---|
| `run.sh` | entry point, started by `scripts/run-experiment.sh` |
| `collect.sh` | read-only AWS collector, writes `<run dir>/collected/` once, through a staging directory (see "Partial failure") |
| `derive-capacity.py` | derives tables and an SVG plot from `collected/` into a new directory |
| `rehearse.sh` | runs the derive step on `fixtures/` (synthetic, no AWS) |
| `rehearse-collect.sh` | runs `collect.sh` against a stub `aws` (synthetic, no AWS) |
| `fixtures/synthetic-*` | synthetic JSON in the shape of the CLI output; NOT measurements |
| `../manifests/experiment-b-burst.manifest` | the manifest (placeholders documented in its header) |
| `../manifests/json/experiment-b-burst.json` | generated JSON copy of the manifest, checked by `../check-manifest-json.sh` |
| `../pipeline-inputs/s-b-burst.template.json` | execution input template, the run id is a placeholder |

## Paste-ready commands (human, `!` prefix, zsh-safe)
Fill the `REPLACE` values. The account id is not stored in the repository; set it in your shell. Placeholders for the output root stay as in the manifest.

0. Before approval, no AWS: the manifest and its JSON copy match, and the three rehearsals pass.
```
set -o pipefail
bash /Users/winnguyen/ScholarChain/experiments/check-manifest-json.sh 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-manifest-json-1.log" | tail -n 20; echo "exit: $?"
bash /Users/winnguyen/ScholarChain/experiments/b-burst/rehearse.sh 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-b-rehearse-1.log" | tail -n 8; echo "exit: $?"
bash /Users/winnguyen/ScholarChain/experiments/b-burst/rehearse-collect.sh 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-b-rehearse-collect-1.log" | tail -n 8; echo "exit: $?"
bash /Users/winnguyen/ScholarChain/experiments/rehearse-manifest-json.sh 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-manifest-json-rehearse-1.log" | tail -n 8; echo "exit: $?"
```

1. Pre-run, read-only: which EMR metric names exist in this account (managed scaling metrics exist only for clusters that had a managed scaling policy, so a missing name before the B run is not a failure).
```
set -o pipefail
AWS_PROFILE=scholarchain aws cloudwatch list-metrics --namespace AWS/ElasticMapReduce --region us-east-2 --query 'Metrics[].MetricName' --output text 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-b-metric-names-1.log" | tail -n 60; echo "exit: $?"
```

2. The run (the human-approved path; shown only so the environment is clear). `SCHOLARCHAIN_PIPELINE_INPUT_FILE` is a copy of the template with a NEW lower-case UUID run id; the pipeline approval record binds that file and the limits in `run.sh`.
```
set -o pipefail
export SCHOLARCHAIN_PIPELINE_INPUT_FILE="/Users/winnguyen/scholarchain-data/pipeline-inputs/REPLACE_WITH_B_INPUT.json"
export SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD="/Users/winnguyen/scholarchain-data/approvals/REPLACE_WITH_PIPELINE_APPROVAL.env"
AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/scripts/run-experiment.sh --manifest "/Users/winnguyen/scholarchain-data/experiments/REPLACE_WITH_B_MANIFEST_COPY" --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 2.40 --approval-record "/Users/winnguyen/scholarchain-data/approvals/REPLACE_WITH_EXPERIMENT_APPROVAL.env" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-b-run-1.log" | tail -n 60; echo "exit: $?"
```

3. Optional, read-only, while the Wait state is running (watch the group counts fall):
```
set -o pipefail
AWS_PROFILE=scholarchain aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-b-live-1.log" | tail -n 60; echo "exit: $?"
CLUSTER_ID="REPLACE_WITH_CLUSTER_ID"
AWS_PROFILE=scholarchain aws emr list-instance-groups --cluster-id "${CLUSTER_ID}" --region us-east-2 --query 'InstanceGroups[].[InstanceGroupType,RequestedInstanceCount,RunningInstanceCount,Status.State]' --output text 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-b-live-2.log" | tail -n 60; echo "exit: $?"
```

4. After the run ends (the cluster may already be gone; EMR keeps it describable for about two months, CloudWatch keeps 5-minute data 63 days). Writes `<run dir>/collected/` once, read-only calls only, refuses an existing `collected/`:
```
set -o pipefail
OUTPUT_ROOT="/abs/outside/repo/scholarchain-experiments"
RUN_DIR="${OUTPUT_ROOT}/REPLACE_WITH_RUN_ID"
AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/experiments/b-burst/collect.sh --run-dir "${RUN_DIR}" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-b-collect-1.log" | tail -n 60; echo "exit: $?"
```
Partial failure: the collector writes `<run dir>/collected.staging/` and renames it to `collected/` in one step after every REQUIRED call succeeded. A failed required call stops it, keeps the partial files as `<run dir>/collect-failed.<UTC stamp>/` (never deleted) and creates no `collected/`, so the same command can be repeated. A failed OPTIONAL call (`get-managed-scaling-policy`, `list-steps`, `list-metrics`) is recorded in `CALL_STATUS.txt` and the collection goes on.

5. Derive tables and the plot from the raw files only (no AWS):
```
set -o pipefail
OUTPUT_ROOT="/abs/outside/repo/scholarchain-experiments"
RUN_DIR="${OUTPUT_ROOT}/REPLACE_WITH_RUN_ID"
python3 -I /Users/winnguyen/ScholarChain/experiments/b-burst/derive-capacity.py derive --collected-dir "${RUN_DIR}/collected" --out-dir "${RUN_DIR}/derived" --title "Experiment B: EMR capacity over time" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-b-derive-1.log" | tail -n 60; echo "exit: $?"
```
A failed or timed-out run is collected and derived the same way and reported; it is never deleted.

## What the collector reads (all describe-, list- or get- verbs; `collect.sh` refuses any other verb)
| Call | Gives |
|---|---|
| `stepfunctions describe-execution`, `get-execution-history` | state timestamps (CreateCluster, PipelineStep, Wait, TerminateCluster), the cluster id |
| `emr describe-cluster` | cluster creation, ready and end time |
| `emr list-instance-groups` | requested and running instance counts per group (final values) |
| `emr list-instances` | CreationDateTime, ReadyDateTime and EndDateTime of every instance: the requested, provisioned and released timeline with exact times |
| `emr get-managed-scaling-policy` (optional) | the minimum and maximum in force, used to label floor-restoring instances |
| `emr list-steps` (optional) | step states |
| `cloudwatch list-metrics` (cluster dimension, optional) | which metric names exist: the empirical check of the names below |
| `cloudwatch get-metric-data` (period 300 s) | requested and running nodes and units, pending containers and pending ratio, apps, YARN memory |

## CloudWatch metric names: partly UNVERIFIED
The names in `derive-capacity.py` (`METRICS`) and in the manifest are the EMR managed-scaling and YARN metrics: `TotalNodesRequested`, `TotalNodesRunning`, `TotalUnitsRequested`, `TotalUnitsRunning`, `CoreNodesRunning`, `CoreNodesRequested`, `TaskNodesRunning`, `ContainerPending`, `ContainerPendingRatio`, `ContainerAllocated`, `AppsPending`, `AppsRunning`, `YARNMemoryAvailablePercentage`, `IsIdle`, namespace `AWS/ElasticMapReduce`, dimension `JobFlowId`. Only `ContainerPendingRatio` as a managed-scaling metric has a documentation hit (`doc-verification.md`, search result only, sections not read); the others are NOT checked. Pages to check them against:
- https://docs.aws.amazon.com/emr/latest/ManagementGuide/UsingEMR_ViewingMetrics.html
- https://docs.aws.amazon.com/emr/latest/ManagementGuide/managed-scaling-metrics.html
The saved `cloudwatch-list-metrics.json` of a real run is the proof of which names exist. A name without data is listed as "no data" in the summary, never dropped. EMR publishes these every 5 minutes, so scale events inside one period are visible with exact times only in the instance timeline.

## Definitions (also in the header of `derive-capacity.py`)
Requested instance: EC2 instance from CreationDateTime to EndDateTime. Provisioned: from ReadyDateTime. Released: has an EndDateTime. Initial instances: non-primary instances created within 300 s of the first instance. An increase is a non-primary instance created after that window; it is classified as `demand-linked` (not a floor restore, and `ContainerPending` or `ContainerPendingRatio` above 0 from 600 s before to 300 s after the request: the only class that counts as scale-out), `floor-restoring` (fewer non-primary instances alive than the policy minimum when it was requested), `no-pending-evidence` or `demand-unknown`. Scale-in: a non-primary instance that ended more than 30 s before `TerminateCluster` was entered, unless a floor-restoring instance of the same role replaced it. The summary prints `scale_out_observed` as `yes`, `unconfirmed` or `no`, `scale_in_observed` as `yes`, `no` or `unknown` (no termination reference), the policy bounds, and `scale-events.csv` carries the pending containers and ratio near each event. The managed-scaling limits count core and task nodes only (K2); the timeline counts the primary as one instance.

## Rehearsal
- `bash experiments/b-burst/rehearse.sh` runs the derive step on `fixtures/synthetic-scaling` (two instances scaled out and in during a Wait, with a policy and pending containers), `fixtures/synthetic-no-scaling` (fixed size), `fixtures/synthetic-floor-restore` (a lost node replaced, one demand-linked increase, one increase with no pending evidence) and `fixtures/synthetic-unconfirmed` (an increase with no CloudWatch data and no policy). It prints one PASS or FAIL line per check and `REHEARSAL pass=<n> fail=<n>`.
- `bash experiments/b-burst/rehearse-collect.sh` runs `collect.sh` against a stub `aws` and checks the partial-failure behaviour.
- `bash experiments/rehearse-manifest-json.sh` checks that `check-manifest-json.sh` fails on every kind of mismatch.
All need `python3` and write only into a temporary directory. The fixtures are synthetic and are not raw data of any run.
