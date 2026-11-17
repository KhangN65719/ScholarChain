# Experiment A: Hadoop and Spark baseline, tier S (Phase 15, Wave 1)

Status: authored, NOT executed. No AWS call, no run, no approval record.

## Audit of S run 6 (decision P15-2): verdict RE-RUN
Run 6 (`run-v1-192de0fd-1442-4508-9065-09c44775643b`, execution `run-20261115T192111Z-large`, 2026-11-15T19:21:11Z to 20:48:22Z) is the only run with the full EMR stage on single-node capacity. P15-2 reuses it only if its records hold task counts, shuffle and failures. They do not hold shuffle (neither MapReduce nor Spark) or MapReduce task counts, so the verdict is RE-RUN. The per-field table is in the Wave 1 report; `derive-table.py` reproduces it from the raw run files:
```
set -o pipefail
python3 -I /Users/winnguyen/ScholarChain/experiments/a-baseline/derive-table.py --reports-dir "/Users/winnguyen/scholarchain-data/runs/s-6/reports" --pipeline-record "/Users/winnguyen/scholarchain-data/runs/s-6/pipeline-20261115T192111Z-large.record.env" --out-dir "/Users/winnguyen/scholarchain-data/derived/s-6-a-fields-1" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-a-audit-1.log" | tail -n 60; echo "exit: $?"
```
It reads the run files and writes only the new `derived/s-6-a-fields-1/` directory next to, not inside, the raw run directory (an existing output directory is refused).

## What the A re-run records that run 6 did not (delivered by infra-engineer, not yet run)
The entry point `run.sh` starts the same pipeline path and cannot add on-node measurements. The node does, through the `a-baseline` profile of `scripts/emr/spark-yarn.conf` (`MR_COUNTERS=true`, `SPARK_EVENT_LOG=true`, `DATA_PATH=file`, `SPARK_MASTER=yarn`: the shape of run 6) and the overlay `infra/terraform/environments/course/baseline-s.tfvars` (core 0, Neptune, Bedrock and services off, no Wait, loader skipped). What each course field then comes from, by `derive-table.py`:
| Course field | Source in a re-run | Notes |
|---|---|---|
| Input records | the MapReduce `Map input records` counter of the raw-input jobs (`reports/mapreduce-counters/<job id>.txt`, `--counters-dir`) | `INPUT_LINES_*` in `run-record.json` are `wc -l` line counts only (an XML line is not a record); they are shown as line counts and never as records. Only the jobs of stage `dblp-extraction` and `source-normalization/*` (positional stage column of `mapreduce-job-ids.txt`, inferred) are summed; a sum over all jobs would re-count intermediate data |
| Output records | `reports/export-manifest.json` (`tables.<name>.rows`) and `reports/spark-run-manifest.json`, copied to the reports by the node (infra-engineer, review 1 item M4) | a missing file leaves the field NOT RECORDED |
| Task counts and shuffle (MapReduce) | `Launched map tasks`, `Launched reduce tasks`, `Reduce shuffle bytes` and others from the same counter files | the output format of `mapred job -status` on EMR is UNVERIFIED; a missing name is NOT RECORDED |
| Task counts and shuffle (Spark) | the plain Spark event log under `reports/spark-events/` (`--spark-eventlog-dir`) and the driver logs | the event log size for the 5,850-stage application is UNVERIFIED |
Command with all inputs (replace `REPLACE_RUN_ID` and the reports path by the downloaded reports of the A run):
```
set -o pipefail
python3 -I /Users/winnguyen/ScholarChain/experiments/a-baseline/derive-table.py --reports-dir "/Users/winnguyen/scholarchain-data/runs/REPLACE_RUN/reports" --pipeline-record "/Users/winnguyen/scholarchain-data/runs/REPLACE_RUN/REPLACE_PIPELINE_RECORD.record.env" --counters-dir "/Users/winnguyen/scholarchain-data/runs/REPLACE_RUN/reports/mapreduce-counters" --spark-eventlog-dir "/Users/winnguyen/scholarchain-data/runs/REPLACE_RUN/reports/spark-events" --out-dir "/Users/winnguyen/scholarchain-data/derived/REPLACE_RUN-a-fields-1" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-a-derive-1.log" | tail -n 60; echo "exit: $?"
```

## Execution input
`../pipeline-inputs/s-a-baseline.template.json` is the run 6 input with a placeholder run id (a NEW lower-case UUID is required: the same id would write into run 6's S3 prefixes). It still carries `"loader_command":["-version"]` because the pipeline input contract requires the field; it is never used, because `baseline-s.tfvars` skips the loader (`pipeline_skip_loader`) and the execution ends SUCCEEDED after `TerminateCluster`.

## Limitations of an A re-run
- The re-run is a different commit from run 6 (the scripts and Terraform changed in Phase 15). Record the commit difference next to the result (decision P15-2).
- A runs on `file:///` with Spark on YARN (profile pins), the same path as run 6, so its object keys in S3 match run 6's. Experiment B differs: its MapReduce output comes back from HDFS, and whether that copy also brings the `.crc` files is UNVERIFIED (`../b-burst/README.md`).
- The raw `raw/window.json` and the pipeline record hold ARNs with the AWS account ID; make a redacted COPY before any results commit (`../b-burst/README.md`, "Before a results commit"). The derived `fields.json` and `fields.md` carry no account ID.

## Optional read-only fetch of run 6's EMR logs from S3
EMR pushes container logs to the cluster `LogUri` in S3. Whether run 6's logs hold anything beyond run 6's local reports (for example the MapReduce application master's final statistics with completed map and reduce counts) is UNVERIFIED. This cannot make the verdict REUSE (no shuffle there), but it could give MapReduce task counts for the limitations text. Four read-only steps; fill the `REPLACE` values from the previous step's output.
```
set -o pipefail
AWS_PROFILE=scholarchain aws emr list-clusters --cluster-states TERMINATED --created-after 2026-11-15T00:00:00Z --created-before 2026-11-16T00:00:00Z --region us-east-2 --query 'Clusters[].[Id,Name,Status.Timeline.CreationDateTime]' --output text 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-a-emr-clusters-1.log" | tail -n 60; echo "exit: $?"
```
Pick the cluster created at about 19:2x UTC on 2026-11-15 (run 6).
```
set -o pipefail
CLUSTER_ID="REPLACE_WITH_RUN_6_CLUSTER_ID"
AWS_PROFILE=scholarchain aws emr describe-cluster --cluster-id "${CLUSTER_ID}" --region us-east-2 --query 'Cluster.LogUri' --output text 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-a-emr-loguri-1.log" | tail -n 60; echo "exit: $?"
```
```
set -o pipefail
LOG_URI="REPLACE_WITH_LOG_URI_WITH_TRAILING_SLASH"
CLUSTER_ID="REPLACE_WITH_RUN_6_CLUSTER_ID"
AWS_PROFILE=scholarchain aws s3 ls "${LOG_URI}${CLUSTER_ID}/containers/" --recursive --summarize --region us-east-2 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-a-emr-containers-1.log" | tail -n 60; echo "exit: $?"
```
If the total is small, copy the logs to a NEW local directory (the copy is a read from S3; it does not change the bucket):
```
set -o pipefail
LOG_URI="REPLACE_WITH_LOG_URI_WITH_TRAILING_SLASH"
CLUSTER_ID="REPLACE_WITH_RUN_6_CLUSTER_ID"
mkdir -p "/Users/winnguyen/scholarchain-data/derived" && mkdir "/Users/winnguyen/scholarchain-data/derived/s-6-emr-logs" && AWS_PROFILE=scholarchain aws s3 cp "${LOG_URI}${CLUSTER_ID}/containers/" "/Users/winnguyen/scholarchain-data/derived/s-6-emr-logs/" --recursive --region us-east-2 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-a-emr-copy-1.log" | tail -n 60; echo "exit: $?"
```
The `LogUri` value is a bucket path; do not paste it into any repository file.

## Files
| File | Role |
|---|---|
| `run.sh` | entry point (RE-RUN only), started by `scripts/run-experiment.sh` |
| `derive-table.py` | course field table from raw run files |
| `rehearse.sh` | runs `derive-table.py` on `fixtures/synthetic-run` and `fixtures/synthetic-run-records` (synthetic, no AWS) |
| `../manifests/experiment-a-baseline.manifest` | the manifest (placeholders documented in its header) |
| `../manifests/json/experiment-a-baseline.json` | generated JSON copy of the manifest, checked by `../check-manifest-json.sh` |
| `../pipeline-inputs/s-a-baseline.template.json` | execution input template |

Run commands and the pipeline approval record work as in `../b-burst/README.md`, step 2, with the A manifest.
