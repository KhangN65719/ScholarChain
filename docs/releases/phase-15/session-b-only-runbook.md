# Phase 15 B-only session runbook (DRAFT for review, nothing executed)

Author: deploy-engineer. Work order: WO-B1 (`docs/agent-runs/phase-15/session-ab/wo-afternoon-2026-11-17.md`). Status: DRAFT for independent review, then the human. The committed file is the human's command source.

Basis: `session-ab-runbook-draft.md` (revision 3, executed 2026-11-17; steps and numbers are cited as "A+B x.y"), `session-1-runbook.md` (its B-only path, P15-22), `infra/terraform/SESSION2.md` section 5a, `docs/agent-runs/phase-15/session-ab/session-record.md`, `docs/agent-runs/phase-15/decisions.md` (P15-10, P15-11, P15-12, P15-20, P15-22, P15-23, P15-29, P15-30, P15-33 to P15-37), `experiments/b-burst/run.sh`, `infra/terraform/environments/course/elasticity-s.tfvars`, `scripts/deploy.sh`.

I ran nothing: no AWS call, no Terraform, no script, no sbt, no git write. I read files and listed three directories. Every command below is a `!` paste for the human. Nothing here is an approval: the human writes each approval record by running the W command of that action, after reading the plan or arguments it names. A statement read from a file is cited; a statement no run has confirmed is marked UNVERIFIED. No account id is in this file. Account and Region: profile `scholarchain`, `us-east-2` only. Never change the spend limit and never use "Activate advanced features".

Remaining placeholders (two): `REPLACE_WITH_BILLED_USD` in 1.1 and `REPLACE_WITH_COMMIT_SHA` in 1.4. Both fail closed (1.1 refuses anything that is not a plain number; 1.4 requires 40 hex equal to HEAD).

## What this session is

A is done (A+B 2.7 to 2.10, session-down 5.3R `DEPLOY_COMPLETE`). B did not run (P15-35). This runbook has two parts.

- Section 0, tonight, before the B-only session: the A+B leftovers (4.1 to 4.4, 3.2 and 5.2 record reads, 5.4 EBS and 24 h reads, 2.8c, 2.8d, 2.8e). All read-only against AWS; the only local write outside logs is the download into a new directory and the human's own sbt heap measure.
- Sections 1 to 5, the B-only session: gates, session-up on `elasticity-s` from the down state, B, session-down, post-teardown check, evidence. One apply up, one run, one apply down.

| Topic | A+B session | B-only session |
|---|---|---|
| Overlays up | `course` + `experiment-size-s` + `baseline-s`, then `elasticity-s` | `course` + `experiment-size-s` + `elasticity-s` (one apply from the down state) |
| Overlays down | `course` + `session-down` only | the same; never `session-down-services.tfvars`, never `session2.tfvars` |
| Upload and package | `package.sh`, `upload-inputs.sh` | none: 1.6 re-checks the uploaded bootstrap and jars; any mismatch is a stop (no new upload without a reviewed 1.9 path) |
| Names | `phase15-ab-*`, `p15-ab-*` | logs `phase15-bo-*`, files and records `p15-bo-*`; section 0 logs keep `phase15-ab-*` because they finish the A+B session |
| B profile | P15-23 | the same; Hadoop stage limit 1,500 s; goal scale-in (P15-23), limitations P15-12, P15-20, P15-11 |

## Conventions in every command

- Each command is self-contained. It sets its own variables and reads files of earlier steps by name.
- `set -o pipefail;` first, output `tee`'d to `/Users/winnguyen/scholarchain-data/logs/phase15-bo-<step>.log`, ending `| tail -n 60; echo "exit: $?"`. Only braced variables. No `!` inside double quotes.
- Commands are kept short on purpose (the human's terminal broke very long one-liners today). Where the A+B form was long, it is split into small commands, each with its own log and verdict line. A verdict line that is missing from the output is a stop, not a pass.
- The account id is never typed. A command reads it with `ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"`. Any output that can carry it (ARNs, bucket names, script output) passes `sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g"` before `tail`. `tee` sits before `sed`, so only the local log is unredacted: keep `/Users/winnguyen/scholarchain-data/logs/` local.
- Variable paths: `SCD=/Users/winnguyen/scholarchain-data`, `ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course`.
- Two-command exit lines: where a step needs two scripts, each has its own command, so the final `exit: 0` is that command's status.
- Files written with `set -C` that already exist fail (`file exists` in zsh). That means the file is already there: read why before moving anything.
- The record writer uses macOS `date -u -v+8H` (UNVERIFIED on this machine; it worked in A+B 1.9W, 2.4W, 2.6W, 3.4W, 3.6W, 5.3W, so it is observed there).
- Approval record, 14 keys and rules are as in A+B "Approval record, exact fields": single use, `ENVIRONMENT=course`, `REGION=us-east-2`, `STATEFUL_CHANGE_ACKNOWLEDGED=no` unless `plan.sh` prints `STATEFUL_CHANGE=yes` (then stop), `APPROVED_BY=winnguyen`, `EXPIRES_AT` 8 h after `APPROVED_AT`, deploy and experiment records bound to HEAD (`lib.sh:514`, A+B record "Approval reuse check"), at most 24 h (`lib.sh:533-537`).

## Session rules (from the gate 1.4 until the post-teardown check 4.4 is done)

S1. No repository writes and no sbt by anyone. A write dirties the tree and `deploy.sh` refuses, including the session-down, which would leave the endpoints billing. The commit (1.3e) happens before the gate; do not edit any committed file afterwards. Evidence goes to `scholarchain-data/logs/` or the chat and is recorded in the repository only after 4.4. The heap measure (section 0) is done before the gate.

S2. Any stop from the session-up apply (2.5R) onward means "go to step 4" (session-down). From 2.5R the endpoints bill about 0.12 USD/h (8 interface endpoints x 0.015, UNVERIFIED rate). A "report" stop from that point also means: report, then session-down unless the human decides otherwise within 15 minutes, in writing in the chat with a stated end time. Stops before 2.5R change nothing billable: report and wait.

S3. Wall-clock limits (B-only, `session-1-runbook.md` B-only path): T0 is the start of 2.5R. B (3.3) starts no later than T0 + 1 h 30 min. The session-down apply (4.3R) starts no later than T0 + 4 h. Past a limit, go to step 4 without running B. A run in progress is never interrupted by hand: the script caps (88 min pipeline stop, 90 min entry limit) end it.

Expected timeline from T0 (my arithmetic from `session-1-runbook.md` and A+B 2.4R to 2.7 timings, UNVERIFIED): apply and image push 20 min (A+B 2.4R to the next record, 16:13:20Z to 16:17:07Z in the session record, suggests about 4 min with new images; my reading of the timestamps), live check, records, dry runs 30 min (B starts about T0 + 0:50), B run at most 90 min plus up to 5 min teardown check (ends about T0 + 2:25, latest T0 + 3:05), session-down plan, read, records 25 min, apply by T0 + 4:00.

S4. Emergency action, a leftover EMR cluster. Console mutation is prohibited. If a command prints `EMR cluster still active after the run`, or 2.4 shows an active `scholarchain-course-pipeline` cluster when none should run, the human runs E1 lookup and then E1 terminate, and the orchestrator records a human emergency action (log, UTC time, cluster id, reason).

E1 lookup (read-only):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; AWS_PROFILE=scholarchain aws emr list-clusters --active --region us-east-2 --query "Clusters[?Name=='scholarchain-course-pipeline'].[Id,Status.State]" --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-emergency-lookup-${STAMP}.log | tail -n 60; echo "exit: $?"
```

E1 terminate (only when exactly one active cluster of that name exists and its id looks like `j-...`; otherwise nothing is terminated and the exit is non-zero):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; IDS="$(AWS_PROFILE=scholarchain aws emr list-clusters --active --region us-east-2 --query "Clusters[?Name=='scholarchain-course-pipeline'].Id" --output text)"; N="$(printf '%s' "${IDS}" | wc -w | tr -d ' ')"; if [[ "${N}" == "1" && "${IDS}" =~ ^j-[A-Z0-9]+$ ]]; then AWS_PROFILE=scholarchain aws emr terminate-clusters --cluster-ids "${IDS}" --region us-east-2; echo "terminate requested for ${IDS}"; else echo "found ${N} active cluster(s): nothing terminated"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-emergency-terminate-${STAMP}.log | tail -n 60; echo "exit: $?"
```

`aws emr terminate-clusters --cluster-ids` is a valid verb (review 1 m6, closed); the repository scripts use `terminate-job-flows --job-flow-ids`, which is equivalent. Afterwards E1 lookup must print nothing.

S5. No persistent permission prompts: a persistent "always allow" writes `.claude/settings.local.json` (a repository path). A+B 0.2 showed it ignored (`.git/info/exclude:8`) and untracked, so a write there does not dirty the tree, but it is still a repository write under S1.

---

## Section 0. Tonight, before the B-only session (A+B leftovers; nothing billable)

Order: 0.1 to 0.12 as listed. Credentials must be valid for the S3 reads (refresh the profile first if it is a session, UNVERIFIED how it is configured). Costs: the 0.1b download is about 0.37 GB out of S3 (the A export is 373,888,058 bytes in 34 objects, session record 2.10), allowance 0.10 USD (A+B HIGH row, UNVERIFIED rate); this is inside the 2.93 gate constant of 1.1. Nothing in section 0 leaves a resource running.

A run facts used below (session record): A run id `run-v1-b76e2d37-2ac1-40d4-923b-9418d5ff72c7`, A run directory name `exp-A-20261117T161757Z-5c69ba90`, listing log `phase15-ab-a-export-ls.txt` (2.10).

### 0.1 A+B 4.1: A's 17-table export on disk (read-only S3)

0.1a check first. If it prints `A_EXPORT_MATCHES=yes`, skip 0.1b. If it prints `absent`, run 0.1b and then 0.1a again. The check compares the local file count and byte total with the saved S3 listing (34 objects, 373,888,058 bytes).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; D=${SCD}/rehearsal/a-export; L=${SCD}/logs/phase15-ab-a-export-ls.txt; if [[ -d "${D}" ]]; then F="$(find ${D} -type f | wc -l | tr -d ' ')"; B="$(find ${D} -type f -exec stat -f %z {} + | awk '{ s += $1 } END { print s + 0 }')"; TO="$(awk '$1 == "Total" && $2 == "Objects:" { print $3 }' ${L})"; TS="$(awk '$1 == "Total" && $2 == "Size:" { print $3 }' ${L})"; echo "local files=${F} bytes=${B}"; echo "listing objects=${TO} bytes=${TS}"; if [[ -n "${TO}" && "${F}" == "${TO}" && "${B}" == "${TS}" ]]; then echo "A_EXPORT_MATCHES=yes"; else echo "A_EXPORT_MATCHES=NO: STOP, report (never overwrite, never use s-export)"; false; fi; else echo "A_EXPORT_MATCHES=absent: run 0.1b"; fi 2>&1 | tee ${SCD}/logs/phase15-ab-a-export-check.log | tail -n 60; echo "exit: $?"
```

Expected: `A_EXPORT_MATCHES=yes` (or `absent`), `exit: 0`. Stop: `NO`.

0.1b download into a NEW directory (`mkdir` fails if it exists, so nothing is overwritten and `s-export` is never touched):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; A_RUN=run-v1-b76e2d37-2ac1-40d4-923b-9418d5ff72c7; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; ( mkdir -p ${SCD}/rehearsal && mkdir ${SCD}/rehearsal/a-export && AWS_PROFILE=scholarchain aws s3 cp "s3://${DATA_BUCKET}/semantic-context/runs/${A_RUN}/export/" ${SCD}/rehearsal/a-export/ --recursive --only-show-errors --region us-east-2; echo "download exit=$?" ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-export-download.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `download exit=0`, then 0.1a prints `A_EXPORT_MATCHES=yes`. Stop: a non-zero download exit, or a mismatch.

### 0.2 A+B 4.2: HEAP-MEASURE (the human's own sbt run; no agent runs it)

Preconditions, all required: 2.10 PASSED (reported in the session record), 0.1a printed `A_EXPORT_MATCHES=yes`, the session-down is done (5.3R). Why 2.10 comes first (A+B review-1 M3): the suite substitutes an empty table for a missing one (`ExportHeapMeasureSuite.scala:26-42`), and with the variable unset it skips and passes with no `HEAP-MEASURE` line (`:102-103`).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCHOLARCHAIN_S_EXPORT_DIR=/Users/winnguyen/scholarchain-data/rehearsal/a-export sbt -J-Xmx1g "storage/testOnly scholarchain.storage.ExportHeapMeasureSuite" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-heap-measure.log | tail -n 60; echo "exit: $?"
```

Read the `HEAP-MEASURE` line on screen (layout per A+B 4.2: `load=<n> ms, retained after GC=<x> MiB, peak (sum of pool peaks, an upper bound)=<x> MiB, ..., rows=<table>:<n>,...`). An `OutOfMemoryError` at `-Xmx1g` means 4096 MiB; do not retry with a larger cap. If sbt or the compile fails, nothing else changes: 4096 MiB stands.

### 0.3 A+B 4.2b: the line is present and the six SP-2 tables are non-zero

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; L=/Users/winnguyen/scholarchain-data/logs/phase15-ab-heap-measure.log; N="$(grep -a -c 'HEAP-MEASURE' ${L})"; echo "HEAP_MEASURE_LINES=${N}"; grep -a 'HEAP-MEASURE' ${L}; R="$(grep -a 'HEAP-MEASURE' ${L} | sed -n 's/.*rows=//p' | tail -n 1)"; BAD=""; for T in path-evidence components component-membership communities community-membership community-cross-edges; do V="$(printf '%s\n' "${R}" | tr ',' '\n' | awk -F: -v t="${T}" '$1 == t { print $2; exit }')"; echo "rows ${T}=${V:-MISSING}"; if [[ ! "${V}" =~ ^[0-9]+$ ]] || [[ "${V}" -le 0 ]]; then BAD="${BAD} ${T}"; fi; done; if [[ "${N}" -ge 1 && -z "${BAD}" ]]; then echo "HEAP_MEASURE_ROWS_CHECK=PASS"; else echo "HEAP_MEASURE_ROWS_CHECK=FAIL: void, 4096 MiB stands (zero or missing:${BAD})"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-heap-measure-check.log | tail -n 60; echo "exit: $?"
```

Expected: `HEAP_MEASURE_ROWS_CHECK=PASS`, `exit: 0`. `path-evidence` is expected to show 42 rows (session record 2.10, WO-PE open): non-zero, so it passes; whether 42 is a defect is WO-PE's question (P15-38 keeps the export unchanged, so the measure stands).

### 0.4 A+B 4.3: the sizing rule (RATIFIED, P15-30; no command)

The human reads the `HEAP-MEASURE` line. On A's 17-table export: retained at most 400 MiB AND peak at most 800 MiB gives query service task memory 2048 MiB; otherwise, or on an `OutOfMemoryError`, or a void measure (0.3 `FAIL`), 4096 MiB (P15-29, P15-30; the 400/800 thresholds are from `wave-2/reports/wo-07c-storage-engineer.md` line 15). The pasted line and the decision go to the orchestrator; infra-engineer sets `service_memory` for session 2 (no repository write while a session is running, S1).

### 0.5 A+B 4.4 read as "no change outside docs/" (P15-36)

sbt writes only ignored output; the tree still holds uncommitted files: `docs/` from the A+B session and the ten repair paths of 1.3d (`infra/terraform/SESSION2.md`, `infra/terraform/environments/course/services-session.tfvars`, `experiment-size-s.tfvars`, `variables.tf`, `tests/scripts/run.sh`, `experiments/pipeline-inputs/s-graph-only-reload.template.json`, `experiments/pipeline-inputs/README.md`, `load-tests/query-load/load_client.py`, `load-tests/query-load/README.md`, `experiments/e-retrieval-modes/run.sh`). A path of the list that `git status --short` does not show is simply absent; a changed path outside it stops the gate. The allowlist here is exactly the 1.3d one: `docs/` plus those ten paths. Anything else (`modules/`, `build.sbt`, `project/`, `packaging/`, `scripts/`) is a stop.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ( echo "HEAD=$(git rev-parse HEAD)"; git status --short | head -n 30; ST="$(git status --porcelain)"; GX=$?; N="$(printf '%s\n' "${ST}" | cut -c4- | grep -c -v -e '^docs/' -e '^infra/terraform/SESSION2.md$' -e '^infra/terraform/environments/course/services-session.tfvars$' -e '^infra/terraform/environments/course/experiment-size-s.tfvars$' -e '^infra/terraform/environments/course/variables.tf$' -e '^tests/scripts/run.sh$' -e '^experiments/pipeline-inputs/s-graph-only-reload.template.json$' -e '^experiments/pipeline-inputs/README.md$' -e '^load-tests/query-load/load_client.py$' -e '^load-tests/query-load/README.md$' -e '^experiments/e-retrieval-modes/run.sh$')"; echo "GIT_STATUS_EXIT=${GX} (0 = git answered)"; echo "PATHS_NOT_ALLOWED=${N}"; if [[ "${GX}" == "0" && "${N}" == "0" ]]; then echo "NO_CHANGE_OUTSIDE_ALLOWLIST=yes"; else echo "NO_CHANGE_OUTSIDE_ALLOWLIST=NO: STOP, report (a git error also stops); discard nothing"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-ab-tree-after-heap.log | tail -n 60; echo "exit: $?"
```

Expected: `NO_CHANGE_OUTSIDE_ALLOWLIST=yes`, `exit: 0`. Stop: any path outside the allowlist; never `git checkout`, `reset`, `stash drop` or `clean`.

### 0.6 A+B 3.2 for the record: read plan B (switch) from the saved log

Read-only, no AWS. Reads `phase15-ab-b-plan.log` and the plan metadata. Expected: `0 to add, 1 to change, 0 to destroy`, `IAM_CHANGES:` then `  none`, `STATEFUL_CHANGE=no`, `DIRTY=no`, the one action line `aws_sfn_state_machine.pipeline[0] will be updated in-place`.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; L=${SCD}/logs/phase15-ab-b-plan.log; ( sed '/^PLAN_SUMMARY_BEGIN/,$d' ${L} | grep -E '^[[:space:]]*# .*(will be created|will be destroyed|must be replaced|will be replaced|will be updated in-place)'; grep -E '[0-9]+ to add, [0-9]+ to change, [0-9]+ to destroy' ${L}; grep -A1 -x 'IAM_CHANGES:' ${L}; grep -E '^(GIT_SHA|DIRTY|STATEFUL_CHANGE|PLAN_EXIT|CPU_ARCHITECTURE)=' ${SCD}/plans/p15-ab-b-switch/course-*.tfplan.meta ) 2>&1 | tee ${SCD}/logs/phase15-ab-b-plan-read.log | tail -n 60; echo "exit: $?"
```

### 0.7 A+B 5.2 for the record: read plan down from the saved log

Expected: `0 to add, 1 to change, 8 to destroy`, `IAM_CHANGES:` then `  none`, `STATEFUL_CHANGE=no`, eight `aws_vpc_endpoint` destroys and the state machine update.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; L=${SCD}/logs/phase15-ab-down-plan.log; ( sed '/^PLAN_SUMMARY_BEGIN/,$d' ${L} | grep -E '^[[:space:]]*# .*(will be created|will be destroyed|must be replaced|will be replaced|will be updated in-place)'; grep -E '[0-9]+ to add, [0-9]+ to change, [0-9]+ to destroy' ${L}; grep -A1 -x 'IAM_CHANGES:' ${L}; grep -E '^(GIT_SHA|DIRTY|STATEFUL_CHANGE|PLAN_EXIT)=' ${SCD}/plans/p15-ab-down/course-*.tfplan.meta ) 2>&1 | tee ${SCD}/logs/phase15-ab-down-plan-read.log | tail -n 60; echo "exit: $?"
```

### 0.8 A+B 5.4 remainder: unattached EBS volumes (read-only)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( echo "--ebs-volumes-unattached (expect empty)"; AWS_PROFILE=scholarchain aws ec2 describe-volumes --region us-east-2 --filters "Name=status,Values=available" --query 'Volumes[].[VolumeId,Size]' --output text ) 2>&1 | tee ${SCD}/logs/phase15-ab-ebs-check.log | tail -n 60; echo "exit: $?"
```

Expected: empty. Stop (report): any listed volume (a cost item, deletion is not a step here).

### 0.9 A+B 5.4 remainder: EMR clusters of the last 24 h (read-only)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( echo "--emr-clusters-last-24h"; AWS_PROFILE=scholarchain aws emr list-clusters --created-after "$(date -u -v-24H +%Y-%m-%dT%H:%M:%SZ)" --region us-east-2 --query 'Clusters[].[Id,Name,Status.State,Status.StateChangeReason.Code]' --output text; echo "--emr-active (expect empty)"; AWS_PROFILE=scholarchain aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text ) 2>&1 | tee ${SCD}/logs/phase15-ab-emr-24h.log | tail -n 60; echo "exit: $?"
```

Expected: A's cluster `TERMINATED` (A ran 16:18:00Z to 17:19:19Z), session 1's failed B cluster `j-00913722ZZCSPE7LXKE8` TERMINATED if still inside 24 h, no B cluster (B never started), the active list empty. Stop (report at once, S4 if active): any active cluster or any other unexplained cluster.

### 0.10 A+B 2.8c: A's on-node reports (read-only S3 get)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; A_RUN=run-v1-b76e2d37-2ac1-40d4-923b-9418d5ff72c7; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; ( mkdir -p ${SCD}/runs/p15-ab-a && AWS_PROFILE=scholarchain aws s3 cp "s3://${DATA_BUCKET}/reports/runs/${A_RUN}/" ${SCD}/runs/p15-ab-a/reports/ --recursive --only-show-errors --region us-east-2; echo "download exit=$?"; echo "files: $(find ${SCD}/runs/p15-ab-a/reports -type f | wc -l | tr -d ' ')" ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-reports.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; R=${SCD}/runs/p15-ab-a/reports; ( ls ${R}; ls ${R}/mapreduce-counters; ls -l ${R}/spark-events; grep -E '^(MAPREDUCE|MAPREDUCE_JOB_COUNT|SPARK_ON_YARN|SPARK_MASTER_USED|PREFLIGHT_RESULT|RUN_SCOPE|INPUT_LINES_[A-Za-z_]+|STAGE_SECONDS_[A-Z_]+)=' ${R}/run-record.env ) 2>&1 | tee ${SCD}/logs/phase15-ab-a-reports-list.log | tail -n 60; echo "exit: $?"
```

Expected: `download exit=0`; `run-record.env`, `run-record.json`, `spark-run-manifest.json`, `export-manifest.json`, `mapreduce-counters/`, `spark-events/` (unless over the 256 MiB scan cap); keys `MAPREDUCE=yarn`, `SPARK_ON_YARN=client:yes`, `PREFLIGHT_RESULT=ok` (key names UNVERIFIED by a run for A, per A+B 2.8c). A missing file is a finding for the results, not a stop.

### 0.11 A+B 2.8d: the course field table for A (no AWS)

`derive-table.py` refuses an existing `--out-dir`.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; A_DIR=${SCD}/experiments/runs/exp-A-20261117T161757Z-5c69ba90; python3 -I /Users/winnguyen/ScholarChain/experiments/a-baseline/derive-table.py --reports-dir ${SCD}/runs/p15-ab-a/reports --pipeline-record "$(ls ${A_DIR}/raw/pipeline/pipeline-*.record.env)" --counters-dir ${SCD}/runs/p15-ab-a/reports/mapreduce-counters --spark-eventlog-dir ${SCD}/runs/p15-ab-a/reports/spark-events --out-dir ${SCD}/derived/p15-ab-a-fields 2>&1 | tee ${SCD}/logs/phase15-ab-a-derive.log | tail -n 60; echo "exit: $?"
```

Expected: `FIELDS_DIR=...`, `RECORDED=<n>`, `DERIVED=<n>`, `NOT_RECORDED=<n>`, `exit: 0`. List the NOT_RECORDED fields for the limitations (P15-2 commit difference stays).

### 0.12 A+B 2.8e (optional): capacity collection for A

Optional. The cluster is already TERMINATED; EMR keeps it describable for about two months and CloudWatch keeps 5-minute data for 63 days (UNVERIFIED, A+B 3.9). It is UNVERIFIED for A. Read-only; screen output is redacted.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; A_DIR=${SCD}/experiments/runs/exp-A-20261117T161757Z-5c69ba90; AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/experiments/b-burst/collect.sh --run-dir "${A_DIR}" 2>&1 | tee ${SCD}/logs/phase15-ab-a-collect.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `COLLECT_COMPLETE cluster=<id> window=...`, `exit: 0`. A failed required call keeps `collect-failed.<stamp>/` and the command can be repeated. Not a gate for B.

Paste back from section 0 (per step): 0.1a (and 0.1b if run), the `HEAP-MEASURE` line, 0.3, 0.5, 0.6, 0.7, 0.8, 0.9, 0.10 (both), 0.11, 0.12.

---

## Section 1. Gates for the B-only session (nothing billable)

### 1.1 Billing gate (P15-10)

The human reads the month-to-date billed total in the AWS Billing console (gross, before credits) and replaces `REPLACE_WITH_BILLED_USD`. Last recorded: 5.33 USD (P15-32, before the A+B session).

B-only HIGH used for the gate: 2.93 USD.

| Item | USD (HIGH) | Arithmetic and source |
|---|---|---|
| B entry window | 2.40 | manifest `SPEND_CEILING_USD`; 1.55 USD/h x 90 min / 60 = 2.325 rounded to the ceiling (A+B HIGH table; `b-burst/run.sh` header) |
| Cluster billing after B's 88-minute stop | 0.13 | 1.55 x 300 s / 3600 = 0.129 (P15-20) |
| Idle endpoints outside the B window | 0.30 | 0.12 USD/h x (4 h limit - 1.5 h B window) = 0.30 (`session-1-runbook.md` B-only HIGH; rate UNVERIFIED) |
| B-only session subtotal | 2.83 | `session-1-runbook.md` B-only table, equal to the A+B draft's "B-only 2.83" |
| Section 0 reads (0.1b transfer allowance) | 0.10 | the A+B 4.1 allowance, UNVERIFIED rate; section 0 is outside the A+B gate |
| **Gate constant** | **2.93** | |

Billing lag allowance. OD-7's 1.15 USD (P15-30) covered session 1 and does not apply as written. For the A+B session's usage that Billing may not show yet I propose the fixed allowance `LAG_USD=1.10`: the A+B draft's A-only HIGH is 1.07 (0.75 + 0.05 + 0.27 idle endpoints), rounded up; the actual A figure was estimated 0.61 plus about 1.4 h of endpoints. THIS ALLOWANCE IS NOT RATIFIED: the human confirms it in the chat before pasting. `1.10` and `0` are the only accepted values. If Billing already shows today's A+B usage, set `LAG_USD=0`: otherwise that usage is counted twice (once in the reading, once in the allowance).

The command prints two lines, compared in integer cents (no float comparison at the boundary):
- B part: billed + lag + 2.93 at most 14.00. It governs the B-only session and sets the exit code. With `LAG_USD=1.10` the billed reading must be at most 9.97; with `LAG_USD=0` at most 11.07.
- P15-40 (information for session 2 tomorrow): billed + lag + 2.93 + 4.50 at most 14.00 (4.50 is session 2's HIGH, `SESSION2.md` section 9 as cited in A+B). It prints `GO` or `OVER` and does not change the exit code: an `OVER` is not a stop for tonight's B, it means P15-40 applies and the human decides about session 2 (blocking beyond B is the human's call; record that P15-40 applies). With `LAG_USD=1.10` the billed reading must be at most 5.47 for `GO` (5.33 + 1.10 + 7.43 = 13.86); with `LAG_USD=0` at most 6.57.

```
set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; BILLED_USD=REPLACE_WITH_BILLED_USD; LAG_USD=1.10; if [[ "${BILLED_USD}" =~ ^[0-9]+(\.[0-9]{1,2})?$ ]] && [[ "${LAG_USD}" == "1.10" || "${LAG_USD}" == "0" ]]; then awk -v b="${BILLED_USD}" -v l="${LAG_USD}" 'BEGIN { bc = int(b * 100 + 0.5); lc = int(l * 100 + 0.5); e = bc + lc; printf "P15-10 billed=%.2f lag_allowance=%.2f effective=%.2f limit=14.00\n  B part: effective + 2.93 = %.2f -> %s\n  P15-40 (information, session 2): effective + 2.93 + 4.50 = %.2f -> %s\n", bc / 100, lc / 100, e / 100, (e + 293) / 100, (e + 293 <= 1400 ? "GO" : "STOP"), (e + 743) / 100, (e + 743 <= 1400 ? "GO" : "OVER"); exit (e + 293 <= 1400 ? 0 : 3) }'; else echo "BILLED_USD is not a plain number with at most two decimals, or LAG_USD is neither 1.10 nor 0: STOP"; false; fi 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-billing-gate.log | tail -n 60; echo "exit: $?"
```

Expected: `B part ... -> GO`, `exit: 0`; the P15-40 line is `GO` or `OVER` (information). Stop: `B part ... -> STOP`, `exit: 3`, or a non-number: nothing below runs; report. Nothing needs restoring.

### 1.2 Acknowledgements in the chat (no command; in writing, before 1.3)

1. The `LAG_USD` choice of 1.1.
2. The up plan will show a shape that depends on HEAD (see 2.2): expected `11 to add, 3 to change, 3 to destroy` when the 1.3e commit moved HEAD past `5c69ba9` (the likely case), `8 to add, 1 to change, 0 to destroy` when HEAD is still `5c69ba9`. No log retention change is expected tonight (retention 7 is already applied). Any other count is a stop at 2.2.
3. B reruns once with the P15-23 profile; a failed or stopped B is not retried in this session (A+B 3.7 rule); the goal is scale-in; P15-11 applies if B does not show both events.

### 1.3 Pre-commit checks, then the human's commit with an explicit file list

Tonight's commit is NOT docs-only (coordinator correction). Besides `docs/` it carries these uncommitted repairs: `infra/terraform/SESSION2.md`; `infra/terraform/environments/course/services-session.tfvars` and `experiment-size-s.tfvars` and `variables.tf` (comments only); `experiments/pipeline-inputs/s-graph-only-reload.template.json` and `experiments/pipeline-inputs/README.md` (new); `tests/scripts/run.sh` (harness cases); and three session 2 files from experiment-engineer: `load-tests/query-load/load_client.py`, `load-tests/query-load/README.md`, `experiments/e-retrieval-modes/run.sh` (ten exact paths in all). No Scala changed, but the commit changes code (harness, load client, E run script, tfvars comments, `variables.tf` descriptions) and CLAUDE.md requires a passing agent `sbt check` for a follow-up commit that changes code. HUMAN DECISION D8, to be answered in the chat before 1.3d: either (1) an agent `sbt check` runs before the commit (about 33 minutes; the agent runs it, not the human; its result is recorded by the orchestrator), or (2) an explicit human ruling that this commit needs none. 1.3d and 1.3e do not run until D8 is answered: stop the commit until then. The checks 1.3a to 1.3c2 below cover what changed but do not replace D8. No sbt is run in the session itself (S1). `deploy.sh` and `plan.sh` need an empty `git status` afterwards. The runbook must be in its final form: edits after the commit dirty the tree.

1.3a Terraform and script checks (no sbt). UNVERIFIED by me: the exact output wording is the coordinator's; `check.sh` may write ignored files only.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; bash scripts/check.sh --skip-sbt 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-check.log | tail -n 60; echo "exit: $?"
```

Expected: a `GATE <name>=PASS` line for every gate that ran, `terraform` among them, except `GATE sbt=SKIP` which is allowed, `SBT_RAN=no`, `SKIPPED_GATES=sbt` (a skipped gate is not a pass; any other non-PASS gate line, or any other skipped gate, is a stop), and `CHECK_RESULT=PASS_WITH_SKIPS` (`scripts/check.sh:354`: every gate that ran passed, not a full pass, which is accepted here because no Scala changed), `exit: 0`. The exact wording of the `GATE sbt=SKIP` and `SKIPPED_GATES=sbt` lines is the coordinator's (review 2), not read by me. Stop: `CHECK_RESULT=FAIL` or any gate line other than `PASS` (except `sbt=SKIP`); report, commit nothing.

1.3b script harness:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; bash tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-harness.log | tail -n 60; echo "exit: $?"
```

Expected: `RESULT pass=1917 fail=0`, `exit: 0` (1889 + 28 new test-engineer cases, the coordinator's figure). The harness takes several minutes and may be run in the background. Stop: any `fail` above 0 or a different pass count (report).

1.3c the committed manifest JSON check (as in this morning's batch):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; bash experiments/check-manifest-json.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-json-check.log | tail -n 60; echo "exit: $?"
```

Expected: `MANIFEST_JSON_CHECK OK manifests=6`, `exit: 0`.

1.3c2 the load-client rehearsal (session 2 change in `load-tests/query-load/load_client.py`; the script exists, listed from the directory):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; python3 -I load-tests/query-load/rehearse.py 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-load-rehearse.log | tail -n 60; echo "exit: $?"
```

Expected: `REHEARSAL pass=45 fail=0` (the coordinator's figure; wording UNVERIFIED by me), `exit: 0`. Stop: `fail` above 0 or a different count; commit nothing. There is no `rehearse.py` in `experiments/e-retrieval-modes/` (listed: `collect.sh`, `README.md`, `run.sh`), so no E rehearsal is run; `experiments/e-retrieval-modes/run.sh` is covered only by the harness 1.3b, if at all (UNVERIFIED).

1.3d stage the explicit list and check every staged path is allowed (nothing else is added; no `git add -A`). Precondition: D8 answered in the chat; if not, stop here.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; git add docs/releases/phase-15 docs/agent-runs/phase-15 infra/terraform/SESSION2.md infra/terraform/environments/course/services-session.tfvars infra/terraform/environments/course/experiment-size-s.tfvars infra/terraform/environments/course/variables.tf experiments/pipeline-inputs/s-graph-only-reload.template.json experiments/pipeline-inputs/README.md tests/scripts/run.sh load-tests/query-load/load_client.py load-tests/query-load/README.md experiments/e-retrieval-modes/run.sh; AX=$?; ( SL="$(git diff --cached --name-only)"; DX=$?; BAD="$(printf '%s\n' "${SL}" | grep -v -e '^$' -e '^docs/' -e '^infra/terraform/SESSION2.md$' -e '^infra/terraform/environments/course/services-session.tfvars$' -e '^infra/terraform/environments/course/experiment-size-s.tfvars$' -e '^infra/terraform/environments/course/variables.tf$' -e '^experiments/pipeline-inputs/s-graph-only-reload.template.json$' -e '^experiments/pipeline-inputs/README.md$' -e '^tests/scripts/run.sh$' -e '^load-tests/query-load/load_client.py$' -e '^load-tests/query-load/README.md$' -e '^experiments/e-retrieval-modes/run.sh$')"; echo "GIT_ADD_EXIT=${AX} GIT_DIFF_EXIT=${DX} (0 = git answered)"; echo "STAGED_FILES=$(printf '%s\n' "${SL}" | grep -c .)"; printf '%s\n' "${SL}" | head -n 60; echo "NOT_STAGED_OR_UNTRACKED (expect none):"; git status --short | grep -v '^[AMDR]  ' | head -n 20; if [[ "${AX}" == "0" && "${DX}" == "0" && -z "${BAD}" ]]; then echo "STAGED_ALLOWED_ONLY=yes"; else echo "STAGED_ALLOWED_ONLY=NO: STOP (a git error also stops):"; echo "${BAD}"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-stage.log | tail -n 60; echo "exit: $?"
```

Expected: `STAGED_ALLOWED_ONLY=yes`, the list above plus the `docs/` files, nothing under `NOT_STAGED_OR_UNTRACKED`. Stop: `NO` (unstage that path by hand with `git restore --staged <path>` and rerun; never commit it) or a leftover unstaged path (the human decides; the gate 1.4 needs an empty status).

1.3e the human's commit (only what is staged, no `-a`), then print its SHA:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; git commit -m "Phase 15 B-only session preparation: runbook, session 2 doc, tfvars comments, graph-only reload input, harness cases, load client and E retrieval run script" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-commit.log | tail -n 60; echo "exit: $?"
```

```
cd /Users/winnguyen/ScholarChain && git rev-parse HEAD
```

Expected: a commit summary listing the files above, `exit: 0`; the second command prints the 40-hex SHA the human pastes into 1.4 (`REPLACE_WITH_COMMIT_SHA`). "Nothing to commit" is fine only if the human committed already; then use that commit's SHA.

### 1.4 HEAD and clean-tree gate

The gate is pinned to the human's commit of 1.3e: the human replaces `REPLACE_WITH_COMMIT_SHA` with its SHA (checked to be 40 hex and equal to HEAD). It accepts HEAD only when: `6cd7341` (Wave 2) is an ancestor of HEAD; every path in `git diff --name-only --no-renames 6cd7341 HEAD` is under `docs/`, or is exactly one of the ten paths of 1.3d (`infra/terraform/SESSION2.md`, `infra/terraform/environments/course/services-session.tfvars`, `experiment-size-s.tfvars`, `variables.tf`, `experiments/pipeline-inputs/s-graph-only-reload.template.json`, `experiments/pipeline-inputs/README.md`, `tests/scripts/run.sh`, `load-tests/query-load/load_client.py`, `load-tests/query-load/README.md`, `experiments/e-retrieval-modes/run.sh`), so nothing under `modules/`, `scripts/`, `build.sbt`, `project/` or `packaging/`, no other file of `experiments/pipeline-inputs/`, `load-tests/` or `experiments/e-retrieval-modes/` (`s-b-burst.template.json` is read by 1.7b-new): the Scala, the node scripts, the jars and the P15-23 profile are those uploaded in A+B 1.9R (the A+B docs commit `5c69ba9` is inside this range); the changed lines in the `*.tfvars` files are comments or blank lines only, and the changed lines in `variables.tf` are only single-line `description = "..."` lines (comments and blank lines also pass; the allowlist plus the 1.3a `GATE terraform=PASS` line cover the rest); the branch is `main`; and `git status --porcelain` is empty (untracked files count). Any git error fails closed. There is no stash step: A+B 0.6 saved nothing, and a dirty tree here is a stop for the human, not something to park.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; W=6cd73419c546b2205a2e2fde09b25a747c3efb8e; C=REPLACE_WITH_COMMIT_SHA; ( git merge-base --is-ancestor ${W} HEAD; ANC=$?; DL="$(git diff --name-only --no-renames ${W} HEAD)"; DX=$?; DB="$(printf '%s\n' "${DL}" | grep -v -e '^docs/' -e '^infra/terraform/SESSION2.md$' -e '^infra/terraform/environments/course/services-session.tfvars$' -e '^infra/terraform/environments/course/experiment-size-s.tfvars$' -e '^infra/terraform/environments/course/variables.tf$' -e '^experiments/pipeline-inputs/s-graph-only-reload.template.json$' -e '^experiments/pipeline-inputs/README.md$' -e '^tests/scripts/run.sh$' -e '^load-tests/query-load/load_client.py$' -e '^load-tests/query-load/README.md$' -e '^experiments/e-retrieval-modes/run.sh$' -e '^$')"; CL="$(git diff -U0 --no-renames ${W} HEAD -- 'infra/terraform/environments/course/*.tfvars' | grep -E '^[+-]' | grep -v -e '^+++' -e '^---' | grep -v -E '^[+-][[:space:]]*(#|//)?[[:space:]]*$' | grep -v -E '^[+-][[:space:]]*(#|//)')"; VL="$(git diff -U0 --no-renames ${W} HEAD -- infra/terraform/environments/course/variables.tf | grep -E '^[+-]' | grep -v -e '^+++' -e '^---' | grep -v -E '^[+-][[:space:]]*(#|//)?[[:space:]]*$' | grep -v -E '^[+-][[:space:]]*(#|//)' | grep -v -E '^[+-][[:space:]]*description[[:space:]]*=[[:space:]]*".*"[[:space:]]*$')"; S="$(git status --porcelain | wc -l | tr -d ' ')"; BR="$(git symbolic-ref --short -q HEAD)"; H="$(git rev-parse HEAD)"; echo "HEAD=${H}"; echo "BRANCH=${BR:-DETACHED}"; echo "ANCESTOR_EXIT=${ANC} (0 = yes)"; echo "DIFF_EXIT=${DX} (0 = git answered)"; echo "PATHS_NOT_ALLOWED_SINCE_6cd7341 (expect none):"; echo "${DB}"; echo "NON_COMMENT_LINES_IN_TFVARS (expect none):"; echo "${CL}"; echo "NON_DESCRIPTION_LINES_IN_VARIABLES_TF (expect none):"; echo "${VL}"; echo "STATUS_LINES=${S}"; git status --short | head -n 20; if [[ "${C}" =~ ^[0-9a-f]{40}$ && "${C}" == "${H}" && "${ANC}" == "0" && "${DX}" == "0" && -z "${DB}" && -z "${CL}" && -z "${VL}" && "${S}" == "0" && "${BR}" == "main" ]]; then echo "GATE=PASS"; else echo "GATE=FAIL: STOP"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-tree-head.log | tail -n 60; echo "exit: $?"
```

Expected: `BRANCH=main`, `ANCESTOR_EXIT=0`, `DIFF_EXIT=0`, an empty not-allowed list, an empty non-comment tfvars list, an empty non-description `variables.tf` list, `STATUS_LINES=0`, `GATE=PASS`, `exit: 0`. The tfvars test covers every `*.tfvars` in `infra/terraform/environments/course/`, so it catches a changed value in an overlay the list does not name. A multi-line (heredoc) description would fail closed: the human then reads the printed lines and decides. The regexes are UNVERIFIED by a run. The HEAD printed here is the commit every plan, image tag and record of this session is bound to. Stop: `GATE=FAIL`; report; delete, reset and discard nothing. After a pass, S1 applies.

### 1.5 Identity and state check (read-only)

1.5a identity:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; ( echo "profile_region=$(AWS_PROFILE=scholarchain aws configure get region)"; C="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ "${C}" =~ ^[0-9]{12}$ ]]; then echo "caller_account=12-digit id (not printed)"; else echo "caller_account=NOT A 12-DIGIT ID: STOP"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-identity.log | tail -n 60; echo "exit: $?"
```

Expected: `profile_region=us-east-2` (blank is not a stop: every command passes `--region us-east-2`), `caller_account=12-digit id (not printed)`.

1.5b dead-letter queues:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; echo "--utc ${STAMP}"; for Q in run-requests embedding-work; do echo "--dlq-${Q} (expect 0 0)"; aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-${Q}-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-2 --output text; done ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-dlq-${STAMP}.log | tail -n 60; echo "exit: $?"
```

1.5c clusters, executions, instances, endpoints (the down state):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; echo "--utc ${STAMP}"; echo "--emr-active (expect empty)"; aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text; echo "--sfn-running (expect empty)"; aws stepfunctions list-executions --state-machine-arn "$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)" --status-filter RUNNING --region us-east-2 --query 'executions[].[name,status]' --output text; echo "--ec2 pending or running (expect empty)"; aws ec2 describe-instances --region us-east-2 --filters "Name=instance-state-name,Values=pending,running" --query 'Reservations[].Instances[].[InstanceId,InstanceType]' --output text; echo "--vpc-endpoints (expect only Gateway rows for s3 and dynamodb)"; aws ec2 describe-vpc-endpoints --region us-east-2 --query 'VpcEndpoints[].[VpcEndpointType,ServiceName,State]' --output text ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-state-${STAMP}.log | tail -n 60; echo "exit: $?"
```

Expected: DLQs `0 0` both, every other section empty, endpoints only `Gateway` for S3 and DynamoDB. Stop (report, nothing is purged or terminated here): a non-zero DLQ, an active cluster, a running execution or instance, or any interface endpoint (the session is not in the down state). Run 1.5b and 1.5c again right before 3.3.

### 1.6 The uploaded bootstrap and jars are the committed ones (read-only; reuse or stop)

This is A+B 1.10 split in two, with hadoop and entity-resolution jars added (A+B 1.10 checked only the Spark jars; B's MapReduce stage runs the hadoop job jar, so I add them; the S3 key `jars/<module>/<file>` is the layout of `upload-inputs.sh:116-128` and `run-s-pipeline.sh:131-132` as checked in review 1 m6, closed, and matched for Spark in A+B 1.10). 1.6b checks only the top-level jars and skips `lib/*.jar`; that is acceptable because 1.4 excludes `build.sbt` and `project/`, so the dependency set is unchanged. The local jars are those `package.sh` built in A+B 1.6 (ignored files under `modules/*/target/job/`); nothing here rebuilds them, and 1.4 proved that no path under `modules/`, `scripts/`, `build.sbt` or `packaging/` changed since Wave 2. A mismatch is a STOP: no new upload without a reviewed 1.9 path (A+B 1.9W, D, R) added by the orchestrator through infra-engineer and the human.

1.6a node scripts and the P15-23 profile:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; ART_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw artifacts_bucket)"; X=0; for F in lib-emr.sh run-s-pipeline.sh capability-check.sh spark-yarn.conf; do L="$(shasum -a 256 < /Users/winnguyen/ScholarChain/scripts/emr/${F} | cut -d' ' -f1)"; R="$(aws s3 cp "s3://${ART_BUCKET}/bootstrap/${F}" - --region us-east-2 | shasum -a 256 | cut -d' ' -f1)"; if [[ "${L}" == "${R}" ]]; then echo "MATCH ${F}"; else echo "DIFFERENT ${F}"; X=1; fi; done; P="$(aws s3 cp "s3://${ART_BUCKET}/bootstrap/spark-yarn.conf" - --region us-east-2 | grep -E '^b-burst\.(PARTITION_COUNT|SPLIT_MAX_BYTES|MAP_MEMORY_MB|REDUCE_MEMORY_MB)=' | LC_ALL=C sort | tr '\n' ' ')"; echo "${P}"; if [[ "${P}" == "b-burst.MAP_MEMORY_MB=2048 b-burst.PARTITION_COUNT=8 b-burst.REDUCE_MEMORY_MB=3072 b-burst.SPLIT_MAX_BYTES=262144 " ]]; then echo "B_PROFILE_P15_23_IN_S3=yes"; else echo "B_PROFILE_P15_23_IN_S3=NO"; X=1; fi; if [[ "${X}" == "0" ]]; then echo "BOOTSTRAP_REUSE=MATCH"; else echo "BOOTSTRAP_REUSE=NO: STOP"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-bootstrap-check.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

1.6b top-level job jars of the three modules:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; ART_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw artifacts_bucket)"; X=0; for M in hadoop entity-resolution spark; do for J in /Users/winnguyen/ScholarChain/modules/${M}/target/job/*.jar; do L="$(shasum -a 256 < "${J}" | cut -d' ' -f1)"; R="$(aws s3 cp "s3://${ART_BUCKET}/jars/${M}/${J##*/}" - --region us-east-2 | shasum -a 256 | cut -d' ' -f1)"; if [[ "${L}" == "${R}" ]]; then echo "JAR_MATCH ${M}/${J##*/}"; else echo "JAR_DIFFERENT ${M}/${J##*/}"; X=1; fi; done; done; if [[ "${X}" == "0" ]]; then echo "JARS_REUSE=MATCH"; else echo "JARS_REUSE=NO: STOP"; false; fi ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-jars-check.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: four `MATCH`, `B_PROFILE_P15_23_IN_S3=yes`, `BOOTSTRAP_REUSE=MATCH`; a `JAR_MATCH` per top-level jar, `JARS_REUSE=MATCH`; `exit: 0` both. Stop: any `DIFFERENT`, `JAR_DIFFERENT`, a missing verdict line (for example zsh `no matches found` because `target/job` was cleaned: then the local jars are gone and reuse cannot be shown), or `exit` non-zero. The A+B check (1.10) of the Spark jar printed `JAR_MATCH sparkanalytics_3-0.1.0-SNAPSHOT.jar` while 1.9R was still running; this re-check is the confirmation after the upload finished.

### 1.7 B inputs and digests (local files; no AWS call except the prefix check)

The B inputs of A+B (`p15-ab-b.json`, `p15-ab-b-burst.manifest`) were never run: B was not started (P15-35). The manifest and pipeline-input content are unchanged since `085b5ec` (session record 1.5B: B experiment digest equals session 1's), so the digests equal the A+B ones. The A+B B records (`p15-ab-b-*`) are unused, expire 2026-11-18T01:28:48Z, the experiment record is bound to `GIT_SHA` 5c69ba9, and none is used tonight.

1.7a is the B run id reusable? `run-v1-730f95f0-ff71-4763-bba6-a0ddbc39e87f` may be reused only if no S3 prefix and no local run directory holds it. Prefixes checked (the three the pipeline writes, `run-s-pipeline.sh:577-580` as cited in A+B 2.9 and B1.1; UNVERIFIED that no other prefix is used): `reports/runs/<id>/`, `semantic-context/runs/<id>/`, `graph/runs/<id>/`. The check fails closed (review 1 m2): it requires a 12-digit account and a non-empty bucket name, and the `aws s3 ls` output is read with stderr, so an S3 error counts as a non-empty prefix and gives `NO`.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; B_RUN="$(grep -o 'run-v1-[0-9a-f-]*' ${SCD}/pipeline-inputs/p15-ab-b.json | head -n 1)"; echo "B_RUN=${B_RUN}"; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; N=0; for P in reports/runs semantic-context/runs graph/runs; do C="$(aws s3 ls "s3://${DATA_BUCKET}/${P}/${B_RUN}/" --region us-east-2 2>&1 | grep -c .)"; echo "${P}/<B_RUN>/ entries_or_error_lines=${C}"; N=$((N + C)); done; F="$(grep -l "${B_RUN}" ${SCD}/experiments/runs/exp-B-*/raw/pipeline-input.json | wc -l | tr -d ' ')"; echo "local run dirs holding the id=${F}"; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ && -n "${DATA_BUCKET}" && "${B_RUN}" =~ ^run-v1-[0-9a-f-]{36}$ && "${N}" == "0" && "${F}" == "0" ]]; then echo "B_RUN_REUSABLE=yes"; else echo "B_RUN_REUSABLE=NO: use 1.7b-new instead of 1.7b (also the result of an STS, terraform or S3 error)"; fi ) 2>&1 | tee ${SCD}/logs/phase15-bo-b-run-id-check.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

1.7b (only after `B_RUN_REUSABLE=yes`) new manifest copy and a copy of the B input. Both files are written with noclobber:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REPO=/Users/winnguyen/ScholarChain; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]]; then (set -C; sed -e "s/^ACCOUNT_ID=.*/ACCOUNT_ID=${ACCOUNT_ID}/" -e "s#^OUTPUT_ROOT=.*#OUTPUT_ROOT=${SCD}/experiments/runs#" ${REPO}/experiments/manifests/experiment-b-burst.manifest > ${SCD}/experiments/manifests/p15-bo-b-burst.manifest) && (set -C; cat ${SCD}/pipeline-inputs/p15-ab-b.json > ${SCD}/pipeline-inputs/p15-bo-b.json) && grep -HE '^(EXPERIMENT_ID|DATASET_SNAPSHOT|SIZE_TIER|MAX_MINUTES|MAX_CONCURRENCY|MAX_TASKS|SPEND_CEILING_USD|HOURLY_BURN_USD|OUTPUT_ROOT|ENTRY_POINT)=' ${SCD}/experiments/manifests/p15-bo-b-burst.manifest && grep -o 'run-v1-[0-9a-f-]*' ${SCD}/pipeline-inputs/p15-bo-b.json | head -n 1; else echo "no 12-digit account id from STS: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-bo-prepare-inputs.log | tail -n 60; echo "exit: $?"
```

1.7b-new (only if 1.7a said `NO`: a NEW run id, so the digests will differ from A+B's; that is expected then):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REPO=/Users/winnguyen/ScholarChain; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; B_RUN="run-v1-$(uuidgen | tr 'A-Z' 'a-z')"; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]]; then (set -C; sed -e "s/^ACCOUNT_ID=.*/ACCOUNT_ID=${ACCOUNT_ID}/" -e "s#^OUTPUT_ROOT=.*#OUTPUT_ROOT=${SCD}/experiments/runs#" ${REPO}/experiments/manifests/experiment-b-burst.manifest > ${SCD}/experiments/manifests/p15-bo-b-burst.manifest) && (set -C; sed "s/run-v1-REPLACE-WITH-A-NEW-LOWERCASE-UUID/${B_RUN}/" ${REPO}/experiments/pipeline-inputs/s-b-burst.template.json > ${SCD}/pipeline-inputs/p15-bo-b.json) && echo "B_RUN=${B_RUN}"; else echo "no 12-digit account id from STS: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-bo-prepare-inputs.log | tail -n 60; echo "exit: $?"
```

Expected (either form): the limits `MAX_MINUTES=90`, `MAX_CONCURRENCY=1`, `MAX_TASKS=1`, `SPEND_CEILING_USD=2.40`, `HOURLY_BURN_USD=1.55`, `ENTRY_POINT=script:experiments/b-burst/run.sh`, `DATASET_SNAPSHOT=scholarchain-s-20261107-10dd7f34121b`, one `run-v1-<uuid>` line, `exit: 0`. The command prints no account id. Stop: `file exists` (an earlier `p15-bo-*` file: read why; delete only by hand and treat any digest made from it as void), an STS failure, or a snapshot id other than the one named.

1.7c print the B digests (no AWS call; the full manifest and input validation runs; the pipeline arguments are those of `experiments/b-burst/run.sh` line 49: `medium 88 2.30 1.55`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; bash scripts/run-experiment.sh --print-digest --manifest ${SCD}/experiments/manifests/p15-bo-b-burst.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 2.40 2>&1 | tee ${SCD}/logs/phase15-bo-b-experiment-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; bash scripts/run-bounded-pipeline.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --size medium --max-minutes 88 --spend-ceiling-usd 2.30 --hourly-burn-usd 1.55 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-bo-b.json 2>&1 | tee ${SCD}/logs/phase15-bo-b-pipeline-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: each prints `ACTION=`, `SCRIPT_DIGEST=`, `ARGS_CANONICAL=`, `ARGS_DIGEST=` (account shown as `000000000000`), `exit: 0`. `ARGS_CANONICAL` as A+B 1.5B: experiment `...cost=2.40;extra=manifest=<sha>;experiment=B;size=S;max_minutes=90;max_concurrency=1;max_tasks=1;spend_ceiling=2.40`; pipeline `...cost=20;extra=size=medium;max_minutes=88;spend_ceiling=2.30;hourly_burn=1.55;stop_on=first-failure,dlq-nonempty;input=<sha>`. Projected spend: 1.55 x 88 / 60 = 2.27 at most 2.30, and 2.33 at most 2.40. Stop: any `EVIDENCE:` refusal.

1.7d compare with the A+B B digests (information; equality is expected after the 1.7b form):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( for K in experiment pipeline; do A="$(awk -F= '$1 == "ARGS_DIGEST" { print $2; exit }' ${SCD}/logs/phase15-ab-b-${K}-digest.log)"; N="$(awk -F= '$1 == "ARGS_DIGEST" { print $2; exit }' ${SCD}/logs/phase15-bo-b-${K}-digest.log)"; echo "${K} ab=${A} bo=${N}"; if [[ -n "${N}" && "${A}" == "${N}" ]]; then echo "${K}_ARGS_DIGEST_EQUAL_AB=yes"; else echo "${K}_ARGS_DIGEST_EQUAL_AB=NO: read ARGS_CANONICAL (expected only after 1.7b-new)"; fi; done ) 2>&1 | tee ${SCD}/logs/phase15-bo-b-digest-compare.log | tail -n 60; echo "exit: $?"
```

Expected after 1.7b: both `yes` (the session record shows the experiment digest `cbf66f79...` and the pipeline digest `8e9e60cf...` in A+B 1.5B).

---

## Section 2. Session-up on `elasticity-s` (billable from 2.5R)

Every stop in 2.1 to 2.4 changes nothing billable: report and wait. From 2.5R, S2 applies.

### 2.1 Plan the session-up (from the down state)

Layering: `course.tfvars` (the `plan.sh` default), then `experiment-size-s.tfvars`, then `elasticity-s.tfvars` last (later wins; `--extra-var-file` repeats and keeps order). `elasticity-s.tfvars` (read) turns Neptune and Bedrock off, sets `service_desired_count = 0`, core 1 with scaling cap 4 (primary plus 3 core), the 900 s Wait, `emr_idle_timeout_seconds = 1800`, step timeout 4200 s, `pipeline_skip_loader = true`, `pipeline_profile = "b-burst"`. `plan.sh` applies nothing. A repeated plan must use a NEW out-dir (`p15-bo-up-2`): a second plan in the same out-dir leaves two `course-*.tfplan` files and every `ls ${SCD}/plans/p15-bo-up/course-*.tfplan` below would then pass two paths (review 1 m3). A re-plan replaces the out-dir in every later command that reads the plan file or its meta (2.2 to 2.5, including the `--out-dir` of the deploy commands), and no record is written from the old plan. The plan log is overwritten by `tee`, so the old log is lost unless the human copies it first.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; AWS_PROFILE=scholarchain bash scripts/plan.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --extra-var-file ${ENVDIR}/experiment-size-s.tfvars --extra-var-file ${ENVDIR}/elasticity-s.tfvars --out-dir ${SCD}/plans/p15-bo-up 2>&1 | tee ${SCD}/logs/phase15-bo-up-plan.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

### 2.2 Expected plan counts, and the read check

UNVERIFIED until this plan runs. The state is the A+B session-down state: no interface endpoint, task definitions `fast`, `slow` and loader at image tag `5c69ba9...`, the state machine in its default definition, log retention 7 days (A+B 5.1 changed only the state machine and destroyed the 8 endpoints, `0 to add, 1 to change, 8 to destroy`).

| Case | Condition | Expected counts | Resources | Source |
|---|---|---|---|---|
| NEW TAG (likely: tonight's commit, 1.3e, moves HEAD) | HEAD differs from `5c69ba9` | `11 to add, 3 to change, 3 to destroy` | ADD 8 interface endpoints (`ecr.api`, `ecr.dkr`, `logs`, `secretsmanager`, `sts`, `elasticmapreduce`, `s3vectors`, `sqs`; no `bedrock-runtime`); REPLACE 3 (`-/+`: `module.runtime.aws_ecs_task_definition.service["fast"]`, `["slow"]`, `.loader`: they make the 3 adds and 3 destroys); UPDATE 3 (`aws_ecs_service.this["fast"]`, `["slow"]`, `module.workflow.aws_sfn_state_machine.pipeline[0]`) | session 1's up plan (`SESSION2.md` section 5a, "session 1: 11, 3, 3"; `session-1-runbook.md` B-only path); same shape as the A+B A-up minus its four retention updates, which are already applied |
| SAME TAG | HEAD equals `5c69ba9` | `8 to add, 1 to change, 0 to destroy` | ADD 8 endpoints; UPDATE 1 state machine; no task definition or service change | my derivation from `SESSION2.md` section 5a (A-up ADD rows and the A to B row), NOT stated there; UNVERIFIED |

Both: `IAM_CHANGES:` then `  none`, `STATEFUL_CHANGE=no`, `PLAN_EXIT=2`, `CPU_ARCHITECTURE=X86_64`, `DIRTY=no`, `GIT_SHA` equal to HEAD, `PLAN_DIGEST=<hex>`. No retention change tonight. The residual note of `SESSION2.md` 5a (the explicit `health_check_grace_period_seconds = 0` may appear as noise on `fast` and `slow`) cannot change the counts.

2.2a counts, IAM and metadata (the expected line is chosen from HEAD):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; L=${SCD}/logs/phase15-bo-up-plan.log; H="$(git rev-parse HEAD)"; if [[ "${H}" == 5c69ba9006b9f4a3b83d4e103d6db5710483f4e0 ]]; then EXP="8 to add, 1 to change, 0 to destroy"; else EXP="11 to add, 3 to change, 3 to destroy"; fi; ( echo "HEAD=${H}"; echo "EXPECTED=${EXP}"; sed -n '/^PLAN_SUMMARY_BEGIN/,/^PLAN_SUMMARY_END/p' ${L}; grep -E '^(GIT_SHA|DIRTY|STATEFUL_CHANGE|PLAN_EXIT|CPU_ARCHITECTURE|IMAGE_TAG)=' ${SCD}/plans/p15-bo-up/course-*.tfplan.meta; grep -A1 -x 'IAM_CHANGES:' ${L}; if grep -q "${EXP}" ${L} && grep -A1 -x 'IAM_CHANGES:' ${L} | tail -n 1 | grep -qx '  none'; then echo "PLAN_UP_COUNTS_CHECK=PASS"; else echo "PLAN_UP_COUNTS_CHECK=FAIL: STOP"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-bo-up-plan-counts.log | tail -n 60; echo "exit: $?"
```

2.2b action lines and forbidden addresses (the verdict compares the shape with the one HEAD implies):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; L=${SCD}/logs/phase15-bo-up-plan.log; H="$(git rev-parse HEAD)"; A="$(sed '/^PLAN_SUMMARY_BEGIN/,$d' ${L} | grep -E '^[[:space:]]*# .*(will be created|will be destroyed|must be replaced|will be replaced|will be updated in-place)')"; ( printf '%s\n' "${A}"; CR="$(printf '%s\n' "${A}" | grep -c 'will be created')"; RP="$(printf '%s\n' "${A}" | grep -cE 'must be replaced|will be replaced')"; UP="$(printf '%s\n' "${A}" | grep -c 'will be updated in-place')"; DS="$(printf '%s\n' "${A}" | grep -c 'will be destroyed')"; BAD="$(printf '%s\n' "${A}" | grep -E 'aws_iam_|aws_lb|aws_appautoscaling_|aws_ecr_|load.client|aws_neptune|bedrock|aws_s3_|aws_dynamodb_|aws_s3vectors_|aws_secretsmanager_|aws_cloudwatch_|aws_ecs_cluster|aws_security_group|aws_ecs_service\.this\["(worker|query)"\]')"; WANT=new; if [[ "${H}" == 5c69ba9006b9f4a3b83d4e103d6db5710483f4e0 ]]; then WANT=same; fi; SHAPE=other; if [[ "${RP}" == "0" && "${UP}" == "1" ]]; then SHAPE=same; fi; if [[ "${RP}" == "3" && "${UP}" == "3" ]]; then SHAPE=new; fi; echo "ACTION_LINES created=${CR} replaced=${RP} updated=${UP} destroyed=${DS} shape=${SHAPE} wanted=${WANT} (expect created 8, destroyed 0; new = 8 3 3 0, same = 8 0 1 0)"; echo "FORBIDDEN (expect none):"; echo "${BAD}"; if [[ -z "${BAD}" && "${CR}" == "8" && "${DS}" == "0" && "${SHAPE}" == "${WANT}" ]]; then echo "PLAN_UP_ACTIONS_CHECK=PASS"; else echo "PLAN_UP_ACTIONS_CHECK=FAIL: STOP"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-bo-up-plan-actions.log | tail -n 60; echo "exit: $?"
```

The automated verdicts do not replace the human's reading of the action lines against the table. The verdict commands read the saved `plan.sh` output: Terraform's plan text, then the `PLAN_SUMMARY_BEGIN` block (A+B 2.2 notes: action lines taken only before `PLAN_SUMMARY_BEGIN`, else the endpoint lines count twice; both forms worked in A+B 2.2 and 3.1, so they are observed). If a grep prints nothing the verdict is `FAIL`; the human reads the summary by eye before deciding.

Stop (no record is written; report and wait). Any of the first four means a Wave 2 or session-2 flag leaked into a var file: do not approve, route to infra-engineer through the orchestrator.
- An `aws_iam_*` line or any `IAM_CHANGES` other than `none` (no privilege-diff approval exists for this session).
- `aws_lb*`, `aws_appautoscaling_*`, `aws_ecs_service.this["worker"]` or `["query"]`, an alarm or the scaling dashboard, the load-client or worker resources, the `query`, `worker` or `load-client` log groups.
- Any Neptune resource or `bedrock-runtime` endpoint.
- Any destroy or replacement of a bucket, table, vector bucket, index, repository or secret (`STATEFUL_CHANGE=yes`).
- A count line other than the one in the table, any log group or retention change, a pure `will be destroyed` line, `DIRTY=yes`, `GIT_SHA` different from HEAD, `CPU_ARCHITECTURE` other than `X86_64`.

There is no IAM statement to write. Rollback for a stop here: nothing is applied; plan again after the owner's fix.

### 2.3 Rendered state machine of the plan (read-only; the definition may be unknown until apply)

In the NEW TAG case the loader task definition ARN is unknown until apply, so the definition is expected to be "unknown until apply": then rely on 2.6 and say so in the evidence (an honest "cannot be checked before apply", not a pass; A+B 2.3 was in this state). In the SAME TAG case the definition may be known.

2.3a extract:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; PF="$(ls ${SCD}/plans/p15-bo-up/course-*.tfplan)"; terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course show -json "${PF}" | python3 -I -c 'import json,sys; p=json.load(sys.stdin); rc=[r for r in p["resource_changes"] if r["type"]=="aws_sfn_state_machine"]; c=rc[0]["change"]; u=(c.get("after_unknown") or {}).get("definition"); print("state_machine_changes", len(rc)); print("DEFINITION_UNKNOWN_UNTIL_APPLY: rely on 2.6" if u else "definition written"); open(sys.argv[1], "w").write("" if u else c["after"]["definition"])' ${SCD}/logs/phase15-bo-up-def-plan.json 2>&1 | tee ${SCD}/logs/phase15-bo-up-def-extract.log | tail -n 60; echo "exit: $?"
```

2.3b, only if 2.3a printed `definition written`:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; python3 -I -c 'import re,sys; s=open(sys.argv[1]).read(); t=sorted(int(x) for x in re.findall(r"\x22TimeoutSeconds\x22\s*:\s*(\d+)", s)); i=re.findall(r"\x22IdleTimeout\x22\s*:\s*(\d+)", s); w=len(re.findall(r"\x22Type\x22\s*:\s*\x22Wait\x22", s)); c=re.findall(r"\x22ComputeLimits\x22\s*:\s*\{([^}]*)\}", s); n=sorted(int(x) for x in re.findall(r":\s*(\d+)", c[0])) if c else []; rl=bool(re.search(r"\x22RunLoader\x22\s*:", s)); at=bool(re.search(r"\x22AfterTerminate\x22\s*:", s)); ok=len(s)>0 and t==[300,600,900,900,1440,4200,4200,7200] and i==["1800"] and w==1 and n==[1,3,3,3] and "Instances" in "".join(c) and "b-burst" in s and "a-baseline" not in s and rl and not at; print("timeouts", t, "idle", i, "waits", w, "limits", n, "run_loader", rl, "after_terminate", at); print("B_DEF_CHECK=" + ("PASS" if ok else "FAIL: STOP")); sys.exit(0 if ok else 1)' ${SCD}/logs/phase15-bo-up-def-plan.json 2>&1 | tee ${SCD}/logs/phase15-bo-up-def-check.log | tail -n 60; echo "exit: $?"
```

The B column (A+B "Rendered values to expect", observed to match the live definition in A+B 3.5): `ComputeLimits` exactly one object with `MaximumCapacityUnits` 3, `MaximumCoreCapacityUnits` 3, `MaximumOnDemandCapacityUnits` 3, `MinimumCapacityUnits` 1, `UnitType` `Instances`; `IdleTimeout` 1800; one Wait state, `Seconds` 900; eight `TimeoutSeconds` 7200, 1440, 300, 4200, 4200, 900, 600, 900 (no 4800); `AfterTerminate` absent; `RunLoader` present; `b-burst` present and `a-baseline` absent. The python check automates the numbers; its regexes (in particular reading the four limits as the integers of the `ComputeLimits` object) are UNVERIFIED by a run. Any difference is a stop (before the apply: report and wait).

### 2.4 Deploy, P: digests and the build contexts

`deploy.sh` applies the saved plan (never `-auto-approve`), logs in to ECR, reuses an existing immutable image tag or builds and pushes it from `target/docker-local`, and runs `verify_revision` (every ACTIVE `scholarchain-course*` task definition family must use an image ending `:<HEAD>`; `deploy.sh:206-216`, `lib.sh:941-956`). In the NEW TAG case both images are built from the `target/docker-local` that `package.sh` made in A+B 1.6 (no `modules/`, `scripts/`, `build.sbt`, `project/` or `packaging/` change since Wave 2, proven by 1.4) and pushed with the new HEAD tag, so Docker must be running at 2.5R. Do not rebuild or run `package.sh` again: a new jar would not match the S3 jars checked in 1.6. In the SAME TAG case the tags exist and nothing is built (`IMAGE_EXISTS`).

2.4P print the deploy digests:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PF="$(ls ${SCD}/plans/p15-bo-up/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --plan-file "${PF}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-bo-up 2>&1 | tee ${SCD}/logs/phase15-bo-up-deploy-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

2.4c the build contexts are those of A+B 2.4P (read-only; it compares the `services_context=` and `loader_context=` digests that `ARGS_CANONICAL` carries; UNVERIFIED that the line carries them in that form, the code builds `EXTRA` that way, `deploy.sh:94`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( X=0; for K in services_context loader_context; do A="$(grep -o "${K}=[^; ]*" ${SCD}/logs/phase15-ab-a-deploy-digest.log | head -n 1)"; N="$(grep -o "${K}=[^; ]*" ${SCD}/logs/phase15-bo-up-deploy-digest.log | head -n 1)"; echo "ab ${A}"; echo "bo ${N}"; if [[ -z "${N}" || "${A}" != "${N}" ]]; then X=1; fi; done; if [[ "${X}" == "0" ]]; then echo "CONTEXTS_EQUAL_AB=yes"; else echo "CONTEXTS_EQUAL_AB=NO: STOP, report (target/docker-local differs from the A + B build)"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-bo-up-contexts.log | tail -n 60; echo "exit: $?"
```

Expected: `CONTEXTS_EQUAL_AB=yes`. Stop: anything else (before the apply: report and wait; the human decides whether a different context is acceptable and no record is written until then).

2.4W write the deploy record (the human approves the plan digest, script revision, account, Region, cost ceiling 20 and the rollback below, after reading 2.2 with `IAM_CHANGES: none` and after the acknowledgements of 1.2):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-bo-up-deploy.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=deploy" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=$(dg ${SCD}/logs/phase15-bo-up-plan.log PLAN_DIGEST)" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-bo-up-deploy-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-bo-up-deploy-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-bo-up-deploy-record.log | tail -n 60; echo "exit: $?"
```

2.4D dry run (record, plan binding, clean tree; consumes nothing):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PF="$(ls ${SCD}/plans/p15-bo-up/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-bo-up-deploy.env --plan-file "${PF}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-bo-up 2>&1 | tee ${SCD}/logs/phase15-bo-up-deploy-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `CLEAN_TREE`, `APPROVAL_VERIFIED action=deploy ...`, `APPROVAL_NOT_CONSUMED dry-run`, `DRY_RUN_COMPLETE`, `exit: 0`, no `APPROVAL_SKIPPED_FIELDS` naming `PLAN_DIGEST` or `ARGS_DIGEST`. Stop: any refusal (still before the apply).

### 2.5 Deploy, R: the session-up apply (T0 starts here)

This is the first billable step. Write down the UTC time printed as `S3_CLOCK_START_UTC` (T0). Docker must be running in the NEW TAG case.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PF="$(ls ${SCD}/plans/p15-bo-up/course-*.tfplan)"; date -u +"S3_CLOCK_START_UTC=%Y-%m-%dT%H:%M:%SZ"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-bo-up-deploy.env --plan-file "${PF}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-bo-up 2>&1 | tee ${SCD}/logs/phase15-bo-up-deploy.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: steps `ok`, `IMAGE_PUSHED` for both repositories (NEW TAG) or `IMAGE_EXISTS` (SAME TAG), `DEPLOY_COMPLETE`, `DEPLOYED_REVISION=<HEAD>`, `exit: 0`; apply counts equal to the plan within the known state-machine plan-versus-apply gap. A `REVISION_MISMATCH` naming a `load-client` or `worker` family means a stack flag leaked: a failed apply, not a smoke finding. Known process gap (Phase 14 README, as A+B 2.4): the apply runs before the image push, so for a short time the task definitions name a tag not yet in ECR; services are at desired count 0. Stop (S2): any failure; do not re-run the record. Rollback gate: a session-down plan (step 4); pushed image tags are immutable and stay. A half-applied plan: run `plan.sh` again with the same overlays to see the drift and report; no second apply without a new plan, a new record and a new human decision.

### 2.6 Deployed definition of B (read-only; the check that always works)

2.6a write the live definition (the file holds the account id in ARNs: keep it out of the repository):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; SM="$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)"; (set -C; aws stepfunctions describe-state-machine --state-machine-arn "${SM}" --region us-east-2 --query definition --output text > ${SCD}/logs/phase15-bo-b-def-live.json) && echo "live definition written, chars: $(wc -c < ${SCD}/logs/phase15-bo-b-def-live.json | tr -d ' ')" ) 2>&1 | tee ${SCD}/logs/phase15-bo-b-def-live.log | tail -n 60; echo "exit: $?"
```

2.6b the B column check on the live file:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; python3 -I -c 'import re,sys; s=open(sys.argv[1]).read(); t=sorted(int(x) for x in re.findall(r"\x22TimeoutSeconds\x22\s*:\s*(\d+)", s)); i=re.findall(r"\x22IdleTimeout\x22\s*:\s*(\d+)", s); w=len(re.findall(r"\x22Type\x22\s*:\s*\x22Wait\x22", s)); c=re.findall(r"\x22ComputeLimits\x22\s*:\s*\{([^}]*)\}", s); n=sorted(int(x) for x in re.findall(r":\s*(\d+)", c[0])) if c else []; rl=bool(re.search(r"\x22RunLoader\x22\s*:", s)); at=bool(re.search(r"\x22AfterTerminate\x22\s*:", s)); ok=len(s)>0 and t==[300,600,900,900,1440,4200,4200,7200] and i==["1800"] and w==1 and n==[1,3,3,3] and "Instances" in "".join(c) and "b-burst" in s and "a-baseline" not in s and rl and not at; print("timeouts", t, "idle", i, "waits", w, "limits", n, "run_loader", rl, "after_terminate", at); print("B_DEF_CHECK=" + ("PASS" if ok else "FAIL: STOP")); sys.exit(0 if ok else 1)' ${SCD}/logs/phase15-bo-b-def-live.json 2>&1 | tee ${SCD}/logs/phase15-bo-b-def-check.log | tail -n 60; echo "exit: $?"
```

Expected: `B_DEF_CHECK=PASS` (the B column of 2.3). Stop (S2: no run starts): any mismatch. The numbers matched the live B definition in A+B 3.5 by the human's reading; the python automation is new and UNVERIFIED, so on a `FAIL` the human compares the printed values with the B column by eye before concluding.

---

## Section 3. B run (billable)

### 3.1 State re-check and clock

Check the clock: B must start no later than T0 + 1 h 30 min (S3). Re-run 1.5b and 1.5c immediately before 3.3 and read them: no cluster, no execution, DLQs `0 0`. The endpoints are expected now (interface endpoints, `available`); the vpc-endpoints section of 1.5c now lists 8 interface rows plus the two Gateway rows.

### 3.2 B records for the run (new records, new names)

By running each W the human approves the B manifest copy (`MAX_MINUTES` 90, ceiling 2.40) and the pipeline limits (88 min stop, 2.30, 1.55). Each record is single use. The records are written after 2.6 passed.

3.2W1 the pipeline record:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-bo-b-pipeline.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=pipeline" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-bo-b-pipeline-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-bo-b-pipeline-digest.log ARGS_DIGEST)" "GIT_SHA=n/a" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-bo-b-pipeline-record.log | tail -n 60; echo "exit: $?"
```

3.2W2 the experiment record (cost ceiling 2.40, bound to HEAD):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-bo-b-experiment.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=experiment" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=2.40" "PLAN_DIGEST=n/a" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-bo-b-experiment-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-bo-b-experiment-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-bo-b-experiment-record.log | tail -n 60; echo "exit: $?"
```

3.2D1 dry run of the pipeline script (consumes nothing; the pipeline record is read only after the experiment record is consumed, so this dry run matters most):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; AWS_PROFILE=scholarchain bash scripts/run-bounded-pipeline.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-bo-b-pipeline.env --size medium --max-minutes 88 --spend-ceiling-usd 2.30 --hourly-burn-usd 1.55 --stop-on first-failure,dlq-nonempty --input-file ${SCD}/pipeline-inputs/p15-bo-b.json 2>&1 | tee ${SCD}/logs/phase15-bo-b-pipeline-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

3.2D2 dry run of the experiment script:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --dry-run --manifest ${SCD}/experiments/manifests/p15-bo-b-burst.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 2.40 --approval-record ${SCD}/approvals/p15-bo-b-experiment.env 2>&1 | tee ${SCD}/logs/phase15-bo-b-experiment-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: both `APPROVAL_VERIFIED`, `DRY_RUN_COMPLETE`, `exit: 0` each. Stop (S2): any refusal or non-zero exit.

### 3.3 Run B

`run-experiment.sh` consumes the experiment record at `start-run` and starts `experiments/b-burst/run.sh` once under `MAX_MINUTES` 90; that script starts one pipeline execution (88-minute stop, `--stop-on first-failure,dlq-nonempty`) with the `b-burst` profile (`b-burst/run.sh:47-49`): MapReduce on HDFS across the core nodes, Spark and export as `local[4]` on the primary (P15-12), then the 900 s Wait with the cluster idle so a scale-in can happen, then terminate. Nothing is tuned during it and nothing is retried. The screen shows nothing until it ends; run the same line in an ordinary terminal if the `!` prompt cannot hold it (A+B 2.7 held a 61-minute run through this path).

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; SCHOLARCHAIN_PIPELINE_INPUT_FILE=${SCD}/pipeline-inputs/p15-bo-b.json SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD=${SCD}/approvals/p15-bo-b-pipeline.env AWS_PROFILE=scholarchain bash scripts/run-experiment.sh --manifest ${SCD}/experiments/manifests/p15-bo-b-burst.manifest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 2.40 --approval-record ${SCD}/approvals/p15-bo-b-experiment.env 2>&1 | tee ${SCD}/logs/phase15-bo-b-run.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Optional watch (second terminal, read-only, repeat a line to poll). The cluster id needs no placeholder here:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; date -u +%H:%M:%SZ; C="$(aws emr list-clusters --active --region us-east-2 --query "Clusters[?Name=='scholarchain-course-pipeline'].Id | [0]" --output text)"; echo "cluster=${C}"; aws emr describe-cluster --cluster-id "${C}" --region us-east-2 --query 'Cluster.InstanceGroups[].[InstanceGroupType,RequestedInstanceCount,RunningInstanceCount,Status.State]' --output text ) 2>&1 | tee -a ${SCD}/logs/phase15-bo-b-watch-groups.log | tail -n 60; echo "exit: $?"
```

(The installed AWS CLI has no `emr list-instance-groups`, `experiments/b-burst/README.md` as cited in A+B 3.7, so `describe-cluster` is used.)

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; B_RUN="$(grep -o 'run-v1-[0-9a-f-]*' ${SCD}/pipeline-inputs/p15-bo-b.json | head -n 1)"; F="$(grep -l "${B_RUN}" ${SCD}/experiments/runs/exp-B-*/raw/pipeline-input.json | head -n 1)"; if [[ -n "${B_RUN}" && -n "${F}" ]]; then tail -n 5 "${F%/raw/pipeline-input.json}/raw/pipeline-run.log"; else echo "no run directory holds this session's B run id yet: wait and repeat"; false; fi 2>&1 | tee -a ${SCD}/logs/phase15-bo-b-watch.log | tail -n 60; echo "exit: $?"
```

Expected at the end: `ENTRY_EXIT=0 FINAL_STATUS=COMPLETED`, `exit: 0`; pipeline `SUCCEEDED`, `NO_ACTIVE_CLUSTER`, `RECORD_COMPLETE=yes`; `estimated_spend_usd` at most 2.40. Stop (S2): any other final status (`FAILED`, `STOPPED_WALL_CLOCK`, `STOPPED_BY_SCRIPT`, `INTERRUPTED`) is kept and reported, never deleted or edited; a failed or stopped B is not retried in this session; `EMR cluster still active after the run` means S4 at once. In particular the Hadoop stage can again hit `timeout -k 30 1500` (exit 124, as in session 1) or fail if no core NodeManager registers within `NM_WAIT_SECONDS=300`: that is a finding, not something to fix mid-run. Limitations on the record as written: P15-12, P15-20, P15-11 (if B shows no clear scale-out and scale-in), P15-23.

### 3.4 What B left (read-only; may wait until after step 4 if the clock is short)

3.4a locate this session's B run directory by the run id inside its pipeline input (never session 1's failed `exp-B-20261117T053516Z-8a6beaae`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; B_RUN="$(grep -o 'run-v1-[0-9a-f-]*' ${SCD}/pipeline-inputs/p15-bo-b.json | head -n 1)"; F="$(grep -l "${B_RUN}" ${SCD}/experiments/runs/exp-B-*/raw/pipeline-input.json | head -n 1)"; if [[ -n "${B_RUN}" && -n "${F}" ]]; then printf 'B_RUN=%s\nB_DIR=%s\n' "${B_RUN}" "${F%/raw/pipeline-input.json}" > ${SCD}/logs/phase15-bo-b-rundir.txt; cat ${SCD}/logs/phase15-bo-b-rundir.txt; else echo "no run directory holds this session's B run id: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-bo-b-rundir-find.log | tail -n 60; echo "exit: $?"
```

3.4b the run result:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; B_DIR="$(awk -F= '$1 == "B_DIR" { print $2; exit }' ${SCD}/logs/phase15-bo-b-rundir.txt)"; ( echo "RUN_DIR_NAME=${B_DIR##*/}"; echo "--run-record.env"; grep -E '^(RUN_ID|EXPERIMENT_ID|GIT_SHA|DATASET_SNAPSHOT|MANIFEST_SHA256|START_UTC|END_UTC|ELAPSED_SECONDS|EXIT_STATUS|FINAL_STATUS|ESTIMATED_SPEND_USD|SPEND_ESTIMATE_EXCEEDED|METRIC_EXPORT_FAILURES|ENTRY_PROCESSES_STRAY|ENTRY_PROCESSES_REMAINING|RECORD_COMPLETE)=' ${B_DIR}/run-record.env; echo "--pipeline record"; grep -E '^(RUN_ID|SIZE|MAX_MINUTES|START_UTC|END_UTC|FINAL_STATUS|ELAPSED_MINUTES|ESTIMATED_SPEND_USD|RECORD_COMPLETE)=' ${B_DIR}/raw/pipeline/pipeline-*.record.env; echo "--events (last 20)"; tail -n 20 ${B_DIR}/raw/events.jsonl ) 2>&1 | tee ${SCD}/logs/phase15-bo-b-result.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

3.4c the on-node reports (the MapReduce counters are B's task and shuffle evidence; on a failed run the failed logs are under `reports/runs/<run id>/failed/<stamp>/`):

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; B_RUN="$(awk -F= '$1 == "B_RUN" { print $2; exit }' ${SCD}/logs/phase15-bo-b-rundir.txt)"; DATA_BUCKET="$(terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course output -raw data_bucket)"; ( mkdir -p ${SCD}/runs/p15-bo-b && AWS_PROFILE=scholarchain aws s3 cp "s3://${DATA_BUCKET}/reports/runs/${B_RUN}/" ${SCD}/runs/p15-bo-b/reports/ --recursive --only-show-errors --region us-east-2; echo "download exit=$?"; echo "files: $(find ${SCD}/runs/p15-bo-b/reports -type f | wc -l | tr -d ' ')" ) 2>&1 | tee ${SCD}/logs/phase15-bo-b-reports.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; R=${SCD}/runs/p15-bo-b/reports; ( ls ${R}; ls ${R}/mapreduce-counters; grep -E '^(MAPREDUCE|MAPREDUCE_JOB_COUNT|SPARK_ON_YARN|SPARK_MASTER_USED|PREFLIGHT_RESULT|RUN_SCOPE|DATA_PATH[A-Z_]*|NM_[A-Z_]*|HADOOP_EXTRA_OPTIONS|INPUT_LINES_[A-Za-z_]+|STAGE_SECONDS_[A-Z_]+)=' ${R}/run-record.env ) 2>&1 | tee ${SCD}/logs/phase15-bo-b-reports-list.log | tail -n 60; echo "exit: $?"
```

Expected keys (names UNVERIFIED): `SPARK_ON_YARN=local-master:yes`, `SPARK_MASTER_USED=local[4]`, `MAPREDUCE=yarn`, `HADOOP_EXTRA_OPTIONS` showing the P15-23 values (`-Dscholarchain.run.partitionCount=8 -Dmapreduce.input.fileinputformat.split.maxsize=262144 -Dmapreduce.map.memory.mb=2048 -Dmapreduce.reduce.memory.mb=3072`); `STAGE_SECONDS_HADOOP` answers whether the stage fit its 1,500 s limit.

### 3.5 B collector and derive (read-only)

The collector prints execution ARNs, so its screen output is redacted. It writes `<run dir>/collected/` once through a staging directory (a failed required call keeps `collect-failed.<stamp>/` and the command can be repeated). If the clock (S3) is short, do step 4 first: 3.4 and 3.5 need no session resources.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; B_DIR="$(awk -F= '$1 == "B_DIR" { print $2; exit }' ${SCD}/logs/phase15-bo-b-rundir.txt)"; AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/experiments/b-burst/collect.sh --run-dir "${B_DIR}" 2>&1 | tee ${SCD}/logs/phase15-bo-b-collect.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; B_DIR="$(awk -F= '$1 == "B_DIR" { print $2; exit }' ${SCD}/logs/phase15-bo-b-rundir.txt)"; python3 -I /Users/winnguyen/ScholarChain/experiments/b-burst/derive-capacity.py derive --collected-dir "${B_DIR}/collected" --out-dir "${B_DIR}/derived" --title "Experiment B: EMR capacity over time" 2>&1 | tee ${SCD}/logs/phase15-bo-b-derive.log | tail -n 60; echo "exit: $?"
```

Expected: `COLLECT_COMPLETE cluster=<id> window=...`, `exit: 0`; then `DERIVED_DIR=...` and `scale_out_observed=<yes|unconfirmed|no> scale_in_observed=<yes|no|unknown>`. The goal of this rerun is `scale_in_observed=yes` (session 1's failed run already holds a demand-linked scale-out, P15-23). `unconfirmed`, `no` or `unknown` is the result, reported as is, with P15-11 (D's ECS scaling is the second source); do not re-run B to chase a better result.

---

## Section 4. Session-down (same session), verification

Do this as soon as B has ended and its cluster is gone (or as soon as S2 or S3 sends you here); 3.4 and 3.5 can wait. The session-down apply (4.3R) starts no later than T0 + 4 h. A repeated down plan must use a NEW out-dir (`p15-bo-down-2`), for the same reason as 2.1 (two `course-*.tfplan` files in one directory; review 1 m3); the new out-dir replaces `p15-bo-down` in every later command that reads the plan file or its meta (4.2 and every 4.3 command). Teardown var files: `course.tfvars` (the default) plus `session-down.tfvars` ONLY. Never add `session-down-services.tfvars` (it sets `enable_services_stack = true` and would plan to create the services stack), never `session2.tfvars`, never combine `session-down.tfvars` with a size overlay. The tree must still be clean (S1); if `deploy.sh` refuses, something wrote to the repository: find it with `git status --short`, report, and discard nothing.

### 4.1 Plan the session-down

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ENVDIR=/Users/winnguyen/ScholarChain/infra/terraform/environments/course; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; AWS_PROFILE=scholarchain bash scripts/plan.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --extra-var-file ${ENVDIR}/session-down.tfvars --out-dir ${SCD}/plans/p15-bo-down 2>&1 | tee ${SCD}/logs/phase15-bo-down-plan.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected (A+B 5.1, observed there; `SESSION2.md` 5a "B to down"): exactly `0 to add, 1 to change, 8 to destroy`, `IAM_CHANGES:` then `  none`, `STATEFUL_CHANGE=no`, DESTROY 8 interface endpoints, UPDATE 1 `module.workflow.aws_sfn_state_machine.pipeline[0]` back to its default definition. The up apply's task definition replacement (NEW TAG case) does not appear in the down plan.

### 4.2 Read plan down

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; L=${SCD}/logs/phase15-bo-down-plan.log; A="$(sed '/^PLAN_SUMMARY_BEGIN/,$d' ${L} | grep -E '^[[:space:]]*# .*(will be created|will be destroyed|must be replaced|will be replaced|will be updated in-place)')"; ( printf '%s\n' "${A}"; sed -n '/^PLAN_SUMMARY_BEGIN/,/^PLAN_SUMMARY_END/p' ${L}; grep -E '^(GIT_SHA|DIRTY|STATEFUL_CHANGE|PLAN_EXIT)=' ${SCD}/plans/p15-bo-down/course-*.tfplan.meta; CR="$(printf '%s\n' "${A}" | grep -c 'will be created')"; RP="$(printf '%s\n' "${A}" | grep -cE 'must be replaced|will be replaced')"; UP="$(printf '%s\n' "${A}" | grep -c 'will be updated in-place')"; DS="$(printf '%s\n' "${A}" | grep -c 'will be destroyed')"; EP="$(printf '%s\n' "${A}" | grep -cE 'aws_vpc_endpoint.*will be destroyed')"; SM="$(printf '%s\n' "${A}" | grep -cE 'aws_sfn_state_machine\.pipeline.*will be updated in-place')"; echo "ACTION_LINES created=${CR} replaced=${RP} updated=${UP} destroyed=${DS} endpoints_destroyed=${EP} state_machine_updated=${SM} (expect 0 0 1 8 8 1)"; if [[ "${CR}" == "0" && "${RP}" == "0" && "${UP}" == "1" && "${DS}" == "8" && "${EP}" == "8" && "${SM}" == "1" ]] && grep -q '0 to add, 1 to change, 8 to destroy' ${L} && grep -A1 -x 'IAM_CHANGES:' ${L} | tail -n 1 | grep -qx '  none'; then echo "PLAN_DOWN_CHECK=PASS"; else echo "PLAN_DOWN_CHECK=FAIL: STOP"; false; fi ) 2>&1 | tee ${SCD}/logs/phase15-bo-down-plan-actions.log | tail -n 60; echo "exit: $?"
```

Stop: any add or replacement, any destroy line other than the eight `aws_vpc_endpoint` interface endpoints, any `aws_iam_*` line or `IAM_CHANGES` other than `none`, `STATEFUL_CHANGE=yes`, `DIRTY=yes`. A stop here is not "go to step 4" (you are in it): the endpoints are still billing, so decide within 15 minutes and say so in the chat.

### 4.3 Deploy down

4.3P:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PF="$(ls ${SCD}/plans/p15-bo-down/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --print-digest --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --plan-file "${PF}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-bo-down 2>&1 | tee ${SCD}/logs/phase15-bo-down-deploy-digest.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

4.3W:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; REC=${SCD}/approvals/p15-bo-down-deploy.env; dg() { awk -F= -v k="${2}" '$1 == k { print $2; exit }' "${1}"; }; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; EXP="$(date -u -v+8H +%Y-%m-%dT%H:%M:%SZ)"; ( (set -C; printf '%s\n' "APPROVAL_VERSION=1" "ACTION=deploy" "ENVIRONMENT=course" "ACCOUNT_ID=${ACCOUNT_ID}" "REGION=us-east-2" "COST_CEILING_USD=20" "PLAN_DIGEST=$(dg ${SCD}/logs/phase15-bo-down-plan.log PLAN_DIGEST)" "SCRIPT_DIGEST=$(dg ${SCD}/logs/phase15-bo-down-deploy-digest.log SCRIPT_DIGEST)" "ARGS_DIGEST=$(dg ${SCD}/logs/phase15-bo-down-deploy-digest.log ARGS_DIGEST)" "GIT_SHA=$(git rev-parse HEAD)" "STATEFUL_CHANGE_ACKNOWLEDGED=no" "APPROVED_BY=winnguyen" "APPROVED_AT=${NOW}" "EXPIRES_AT=${EXP}" > "${REC}") && sed "s/^ACCOUNT_ID=.*/ACCOUNT_ID=<masked>/" "${REC}" ) 2>&1 | tee ${SCD}/logs/phase15-bo-down-deploy-record.log | tail -n 60; echo "exit: $?"
```

4.3D:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PF="$(ls ${SCD}/plans/p15-bo-down/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --dry-run --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-bo-down-deploy.env --plan-file "${PF}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-bo-down 2>&1 | tee ${SCD}/logs/phase15-bo-down-deploy-dry.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

4.3R:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; PF="$(ls ${SCD}/plans/p15-bo-down/course-*.tfplan)"; AWS_PROFILE=scholarchain bash scripts/deploy.sh --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record ${SCD}/approvals/p15-bo-down-deploy.env --plan-file "${PF}" --services-context /Users/winnguyen/ScholarChain/target/docker-local --loader-context /Users/winnguyen/ScholarChain/target/docker-local --out-dir ${SCD}/plans/p15-bo-down 2>&1 | tee ${SCD}/logs/phase15-bo-down-deploy.log | sed "s/${ACCOUNT_ID:-NOACCOUNT}/000000000000/g" | tail -n 60; echo "exit: $?"
```

Expected: `IMAGE_EXISTS` for both, `DEPLOY_COMPLETE`, `DEPLOYED_REVISION=<HEAD>`, `exit: 0`. Rollback gate: there is no reversal of the endpoint destroy; the way back is a new session-up plan with a new record. Storage is untouched. Stop: a failure; the endpoints may still be up (about 0.12 USD/h, 2.88 USD a day, HIGH): report at once, run a new plan to see the drift, decide within 15 minutes.

### 4.4 Post-teardown read-only check (split in three; each has its own log)

Run each right after 4.3R and, if you wish, again later in the day. Every call is a `describe-`, `list-` or `get-` read (the `ecs` and `neptune` flag forms are UNVERIFIED against the CLI reference, as in A+B 5.4; they ran in A+B 5.4).

4.4a EMR, ECS, Neptune:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; echo "--utc ${STAMP}"; echo "--emr-active (expect empty)"; aws emr list-clusters --active --region us-east-2 --query 'Clusters[].[Id,Name,Status.State]' --output text; echo "--emr-last-24h (expect A+B A cluster and this session's B cluster TERMINATED; session 1's j-00913722ZZCSPE7LXKE8 if inside 24 h)"; aws emr list-clusters --created-after "$(date -u -v-24H +%Y-%m-%dT%H:%M:%SZ)" --region us-east-2 --query 'Clusters[].[Id,Name,Status.State,Status.StateChangeReason.Code]' --output text; echo "--ecs-running-tasks (expect none)"; for C in $(aws ecs list-clusters --region us-east-2 --query 'clusterArns[]' --output text); do echo "cluster=${C##*/}"; aws ecs list-tasks --cluster "${C}" --desired-status RUNNING --region us-east-2 --query 'taskArns[]' --output text; done; echo "--neptune-clusters (expect empty)"; aws neptune describe-db-clusters --region us-east-2 --query 'DBClusters[].[DBClusterIdentifier,Status]' --output text; echo "--neptune-instances (expect empty)"; aws neptune describe-db-instances --region us-east-2 --query 'DBInstances[].[DBInstanceIdentifier,DBInstanceClass,DBInstanceStatus]' --output text ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-down-confirm-a-${STAMP}.log | tail -n 60; echo "exit: $?"
```

4.4b network and compute:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; echo "--utc ${STAMP}"; echo "--vpc-endpoints (expect only Gateway rows for s3 and dynamodb, available)"; aws ec2 describe-vpc-endpoints --region us-east-2 --query 'VpcEndpoints[].[VpcEndpointType,ServiceName,State]' --output text; echo "--nat-gateways (expect empty)"; aws ec2 describe-nat-gateways --region us-east-2 --filter "Name=state,Values=pending,available" --query 'NatGateways[].[NatGatewayId,State]' --output text; echo "--elastic-ips (expect empty)"; aws ec2 describe-addresses --region us-east-2 --query 'Addresses[].[AllocationId,AssociationId]' --output text; echo "--load-balancers (expect empty)"; aws elbv2 describe-load-balancers --region us-east-2 --query 'LoadBalancers[].[LoadBalancerName,Type,State.Code]' --output text; echo "--ebs-volumes-unattached (expect empty)"; aws ec2 describe-volumes --region us-east-2 --filters "Name=status,Values=available" --query 'Volumes[].[VolumeId,Size]' --output text; echo "--ec2 pending or running (expect empty)"; aws ec2 describe-instances --region us-east-2 --filters "Name=instance-state-name,Values=pending,running" --query 'Reservations[].Instances[].[InstanceId,InstanceType]' --output text ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-down-confirm-b-${STAMP}.log | tail -n 60; echo "exit: $?"
```

4.4c executions, services, queues:

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; ( export AWS_PROFILE=scholarchain AWS_DEFAULT_REGION=us-east-2; SM="$(aws stepfunctions list-state-machines --region us-east-2 --query "stateMachines[?name=='scholarchain-course-pipeline'].stateMachineArn | [0]" --output text)"; echo "--utc ${STAMP}"; echo "--sfn-running (expect empty)"; aws stepfunctions list-executions --state-machine-arn "${SM}" --status-filter RUNNING --region us-east-2 --query 'executions[].[name,status]' --output text; echo "--sfn-last-executions (expect A+B A SUCCEEDED and this session's B with its real status)"; aws stepfunctions list-executions --state-machine-arn "${SM}" --max-results 5 --region us-east-2 --query 'executions[].[name,status,startDate,stopDate]' --output text; echo "--ecs-services (expect only fast and slow, desired 0 running 0; no worker or query service; a cluster with no services may print a usage error)"; for C in $(aws ecs list-clusters --region us-east-2 --query 'clusterArns[]' --output text); do aws ecs describe-services --cluster "${C}" --services $(aws ecs list-services --cluster "${C}" --region us-east-2 --query 'serviceArns[]' --output text) --region us-east-2 --query 'services[].[serviceName,desiredCount,runningCount]' --output text; done; for Q in run-requests embedding-work; do echo "--dlq-${Q} (expect 0 0)"; aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name scholarchain-course-${Q}-dlq --region us-east-2 --query QueueUrl --output text)" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible --region us-east-2 --output text; done ) 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-bo-down-confirm-c-${STAMP}.log | tail -n 60; echo "exit: $?"
```

Expected: no active EMR cluster; this session's B cluster `TERMINATED` and the A cluster `TERMINATED`; no running ECS task; services `fast` and `slow` only, desired 0 and running 0; no Neptune cluster or instance; only `Gateway` endpoints for S3 and DynamoDB, `available`; no NAT gateway, Elastic IP, load balancer or unattached EBS volume; no running EC2 instance or execution; DLQs `0 0`. Stop (report at once): any active cluster, interface endpoint, running instance, Elastic IP, load balancer or unattached volume; a leftover EMR cluster is S4. After the teardown the idle items are storage only (S3, DynamoDB, ECR, S3 Vectors, secrets, log groups). The stash step of A+B 5.5 does not exist here: no stash was made (A+B session record: `No local changes to save`; none in this runbook).

After 4.4 S1 ends and the orchestrator may write evidence into the repository.

---

## Section 5. Redaction copy, evidence

### 5.1 Redaction copy of the B run directory (before any results commit)

The raw run directory stays exactly as written. The copy replaces the 12-digit account id with `000000000000` in a COPY of the whole directory; the final grep is the control. The copy's `SHA256SUMS` no longer matches after redaction: the raw directory is the source of truth.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; B_DIR="$(awk -F= '$1 == "B_DIR" { print $2; exit }' ${SCD}/logs/phase15-bo-b-rundir.txt)"; COPY=${SCD}/results-copy/p15-bo-b; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ && -d "${B_DIR}" ]]; then mkdir -p ${SCD}/results-copy && cp -R "${B_DIR}" "${COPY}" && chmod -R u+w "${COPY}" && find "${COPY}" -type f -exec sed -i.bak "s/${ACCOUNT_ID}/000000000000/g" {} + && find "${COPY}" -name '*.bak' -delete; if grep -rq "${ACCOUNT_ID}" "${COPY}"; then echo "REDACTION_FAILED: the account id is still in ${COPY}"; false; else echo "REDACTION_OK: the account id is not in ${COPY}"; fi; else echo "no 12-digit account id from STS, or no B run directory: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-bo-b-redact.log | tail -n 60; echo "exit: $?"
```

Expected: `REDACTION_OK`, `exit: 0`. Stop: `REDACTION_FAILED` (nothing from that copy is committed). The derived outputs of 3.5 carry no account id (stated in the scripts' headers, A+B 6.1); the on-node reports under `runs/p15-bo-b/reports` and the downloaded `rehearsal/a-export` are not part of the copy and are not committed as they are.

### 5.2 Redacted paste-back file

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; SCD=/Users/winnguyen/scholarchain-data; ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"; if [[ "${ACCOUNT_ID}" =~ ^[0-9]{12}$ ]]; then (for F in ${SCD}/logs/phase15-bo-*.log; do echo "=== ${F##*/}"; tail -n 30 "${F}"; done) | sed "s/${ACCOUNT_ID}/000000000000/g" > ${SCD}/logs/phase15-bo-paste-back.txt; wc -l ${SCD}/logs/phase15-bo-paste-back.txt; else echo "no 12-digit account id from STS: STOP"; false; fi 2>&1 | tee ${SCD}/logs/phase15-bo-paste-back-run.txt | tail -n 60; echo "exit: $?"
```

### 5.3 What to paste, per step (from `phase15-bo-paste-back.txt`; section 0 logs are pasted as listed in section 0)

| Step | Logs | Why |
|---|---|---|
| 1.1 | `billing-gate` | P15-10 gate and the `LAG_USD` choice |
| 1.3, 1.4 | `check`, `harness`, `json-check`, `stage`, `commit`, `tree-head` | the checks, the commit, the pinned HEAD and clean-tree gate |
| 1.5 | `identity`, `dlq-<stamp>`, `state-<stamp>` | pre-checks |
| 1.6 | `bootstrap-check`, `jars-check` | reuse of the A+B upload (MATCH) |
| 1.7 | `b-run-id-check`, `prepare-inputs`, `b-experiment-digest`, `b-pipeline-digest`, `b-digest-compare` | B run id and digests |
| 2.1 to 2.6 | `up-plan`, `up-plan-counts`, `up-plan-actions`, `up-def-*`, `up-deploy-digest`, `up-contexts`, `up-deploy-record` (masked), `up-deploy-dry`, `up-deploy`, `b-def-live`, `b-def-check` | plan digest, counts against 2.2, `IAM_CHANGES: none`, T0, deployed definition |
| 3.2 to 3.5 | `b-pipeline-record`, `b-experiment-record`, `b-pipeline-dry`, `b-experiment-dry`, `b-run`, `b-result`, `b-reports`, `b-reports-list`, `b-collect`, `b-derive` | the B run, `scale_out_observed` and `scale_in_observed` |
| 4 | `down-plan`, `down-plan-actions`, `down-deploy-digest`, `down-deploy-record`, `down-deploy-dry`, `down-deploy`, `down-confirm-a-*`, `down-confirm-b-*`, `down-confirm-c-*` | the teardown and its confirmation |
| 5 | `b-redact` | redaction control |
| any | `emergency-lookup-*`, `emergency-terminate-*` if S4 was used | the human emergency action |
| after | the billed total after the session (next day) | actual spend against the 2.93 HIGH, and the P15-10 input for session 2 |

Do not paste: approval files (paste the W logs only), the live definition file, the raw `phase15-bo-b-def-*.json`, the on-node reports, any `s3://` bucket name, or any account id. Keep those under `scholarchain-data/`.

### 5.4 Where the orchestrator records it (only after 4.4)

The orchestrator preserves each item as received with its author under `docs/agent-runs/phase-15/session-b-only/` (`evidence.md` with the pasted tails per step, UTC times and a ledger with one row per approval record: action, plan digest, script digest, args digest, cost bound, rollback gate, approved-by and approved-at; `b-run-result.md` through experiment-engineer's results work order; limitations P15-12, P15-20, P15-23, P15-11 if B shows no clear scale-out and scale-in). Deploy-engineer writes the release records afterwards from the logs and records, with no command run: `docs/releases/phase-15/deploy-bo-up.md`, `deploy-bo-down.md` and an outcome note, with HEAD, plan digest, script and args digests, approval file names and windows, apply counts, image digests, smoke evidence, rollback gate and open items.

---

## Ordered list of the human's commands for tonight

| # | Id | What | Billable |
|---|---|---|---|
| 1 | 0.1a (0.1b, 0.1a again if absent) | A's export on disk, skip if it matches | transfer about 0.10 |
| 2 | 0.2, 0.3 | heap measure, rows check; 0.4 the sizing rule (no command) | no |
| 3 | 0.5 | no change outside `docs/` | no |
| 4 | 0.6, 0.7 | plan B and plan down records from the saved logs | no |
| 5 | 0.8, 0.9 | unattached EBS, EMR 24 h list | no |
| 6 | 0.10 (two), 0.11, 0.12 (optional) | A's reports, field table, optional collector | no |
| 7 | 1.1, 1.2 | billing gate, chat acknowledgements | no |
| 8 | 1.3a to 1.3e, 1.4 | check.sh, harness, JSON check, stage and commit, pinned HEAD gate | no |
| 9 | 1.5a to 1.5c, 1.6a, 1.6b | identity, state, reuse of the upload (stop on a mismatch) | no |
| 10 | 1.7a, 1.7b (or 1.7b-new), 1.7c (two), 1.7d | B inputs and digests | no |
| 11 | 2.1, 2.2a, 2.2b, 2.3a (2.3b) | plan up and read | no |
| 12 | 2.4P, 2.4c, 2.4W, 2.4D | deploy digests, contexts, record, dry run | no |
| 13 | 2.5 | apply (T0) | from here |
| 14 | 2.6a, 2.6b | live definition | yes |
| 15 | 3.1 (1.5b, 1.5c), 3.2W1, 3.2W2, 3.2D1, 3.2D2 | records and dry runs | yes |
| 16 | 3.3 | B run (watch commands optional) | yes |
| 17 | 4.1, 4.2, 4.3P, 4.3W, 4.3D, 4.3R | session-down | ends billing |
| 18 | 4.4a, 4.4b, 4.4c | post-teardown check | no |
| 19 | 3.4a to 3.4c, 3.5 (if not already done) | B results, collector, derive | no |
| 20 | 5.1, 5.2 | redaction copy, paste-back file | no |

## Wall-clock limits and stop rules (summary)

- T0 is the start of 2.5R. B starts by T0 + 1 h 30 min. The session-down apply (4.3R) starts by T0 + 4 h. Each hour beyond costs about 0.12 USD more (HIGH, endpoints).
- Any stop from 2.5R on goes to step 4 (S2) unless the human decides otherwise in writing within 15 minutes with a stated end time. A leftover EMR cluster is S4 at once.
- A failed or stopped B is kept and reported, never retried in this session. A scale-in that is `no` or `unknown` is the result (P15-11).
- No repository write and no sbt from 1.4 until 4.4 (S1). No account id typed, pasted or committed. No spend-limit or "Activate advanced features" change. `us-east-2` only.
