# Session 1 record (B only, P15-22), recorded by the orchestrator after step 4.5 from the human's pasted outputs, 2026-11-17

All times UTC. Logs in `/Users/winnguyen/scholarchain-data/logs/phase15-s1-*.log` (unredacted, local only). Account ID redacted here.

| Step | Result |
|---|---|
| 0.1 billing gate | billed 3.94; HIGH 3.68 (A+B constants); total 7.62 GO |
| 0.1b | P15-21 precondition; `settings.local.json` ignored (`.git/info/exclude:8`) and untracked |
| 0.2a | docs-only commit `8a6beaa` (25 files under `docs/`) |
| 0.2a2 | state backup to `scholarchain-data/state-backup-p15-s1/` |
| 0.2b / 0.2c | stash `p15-wave2-wip`; `GATE=PASS` (HEAD `8a6beaa`, branch main, no non-docs diff from 085b5ec) |
| 1.1-1.6B | identity us-east-2; state clean (DLQs 0/0, no EMR, executions or EC2); raw inputs present; manifest and input copies (B run id `run-v1-2d680606-c45a-4e66-a231-c05b3b730984`); B digests: experiment args `cbf66f79...`, pipeline args `628795c6...` |
| 2.0.1 | `package.sh` `PACKAGE_COMPLETE git_sha=8a6beaa...` linux/amd64 |
| 2.0.2-2.0.5 | upload plan 42 objects / 1,038,128,626 bytes; `RAW_INPUTS_MATCH_SNAPSHOT=yes`; `REMOTE_LISTING_OK=yes` (10/10/9); `NO_STALE_JARS=yes`; upload `UPLOAD_COMPLETE objects=42`, all VERIFIED; bootstrap files MATCH; profile lines 4 a-baseline, 9 b-burst (P15-21 values) |
| 3.1-3.2 | plan `11 to add, 3 to change, 3 to destroy` (8 interface endpoints; 3 task definitions replaced; 2 ECS services and the state machine updated); `IAM_CHANGES: none`; `STATEFUL_CHANGE=no`; `PLAN_DIGEST=957d3ffb...` |
| 3.3 | definition unknown until apply (expected; 3.5 fallback) |
| 3.4R | `DEPLOY_COMPLETE git_sha=8a6beaa...`; S3 clock start 05:31:21Z |
| 3.5 | live definition: ComputeLimits Min 1 / Max 3 / MaxCore 3 / MaxOnDemand 3; IdleTimeout 1800; Wait 900; TimeoutSeconds {7200,1440,300,4200,4200,900,600,900}; RunLoader present; AfterTerminate absent; `b-burst` only |
| 3.6 | pipeline and experiment records written; both dry runs APPROVAL_VERIFIED |
| 3.7 | **B FAILED.** Run `exp-B-20261117T053516Z-8a6beaae`; execution `run-20261117T053519Z-medium` started 05:35:19Z; CreateCluster ok by 05:40:13Z, FetchScripts ok 05:41:12Z; `pipeline` step `s-09655833N3XPJITIIRD3` on cluster `j-00913722ZZCSPE7LXKE8` ran about 26 min (05:41:27Z to 06:07:30Z) and FAILED with EMR `Reason: Unknown Error` (non-zero exit of `run-s-pipeline.sh ... b-burst`); cleanup terminated the cluster; execution failed 06:13:51Z (`PipelineFailed`); entry exit 1 at 06:14:17Z; elapsed 2,341 s; estimated spend 1.01 USD (burn x time); teardown check `NO_ACTIVE_CLUSTER`. Raw run directory kept (checksummed). Root cause pending: the step stderr in `emr-logs/j-00913722ZZCSPE7LXKE8/steps/s-09655833N3XPJITIIRD3/`. |
| S2 | stop after 2.4R: human proceeded to step 4 (no rerun tonight; S1 forbids a code change) |
| 4.1-4.2 | down plan `0 to add, 1 to change, 8 to destroy` (8 endpoints; state machine reset); `IAM_CHANGES: none`; `STATEFUL_CHANGE=no`; `PLAN_DIGEST=de85cfbc...` |
| 4.3R | `DEPLOY_COMPLETE plan_digest=de85cfbc...`; images reused |
| 4.4 (06:39:41Z) | no active EMR, no ECS tasks, services 0/0, no Neptune, only Gateway s3 and dynamodb endpoints, no NAT, EIP, load balancer, unattached EBS or EC2; DLQs 0/0. Only storage remains |
| 4.5 | `git stash pop` exit 0; Wave 2 restored (status lists the Wave 2 modified and untracked files) |

Endpoint time about 1.1 h (05:31Z to about 06:38Z). Billed actual to be read from Billing later.

## B failure, step logs (human read-only fetch, log `phase15-s1-b-step-logs.log`)
- stderr: `EVIDENCE: PipelineTool exited 124: no error line` (124 = the stage `timeout` wrapper fired); last line is the PipelineTool banner for run `run:v1:2d680606-...`, output `hdfs:///user/hadoop/sc/run/.../hadoop-out/...`.
- stdout: stages 1-4 ok (inputs fetched; jars 10/10/9; Spark archive verified; preflight ok in 21 s). Stage 5 hadoop: `YARN_REMOTE_NODES=1`, `DATA_PATH_MODE=hdfs`, `RUN_SCOPE=MapReduce on YARN with HDFS ... (1 remote NodeManager(s))`, `HADOOP_EXTRA_OPTIONS= -Dscholarchain.run.partitionCount=16 -Dmapreduce.input.fileinputformat.split.maxsize=131072 -Dmapreduce.map.memory.mb=3072 -Dmapreduce.reduce.memory.mb=3072`; `STAGE[5] hadoop: FAIL (exit 1)`, `STAGE_SECONDS_HADOOP=1508`, `STAGE_SECONDS_TOTAL_AT_FAILURE=1557`; failed logs published (6 files) to the data bucket under `reports/runs/run-v1-2d680606-.../failed/20261117T060725Z/`.
- Reading: the Hadoop stage hit its time limit after about 25 minutes in the b-burst configuration (HDFS on 1 core node plus managed scaling, 128 KiB splits, 3072 MB containers). Whether managed scaling added nodes, and which MapReduce job was running, is not yet known (failed logs and the B collector pending).

## B collector after R14 (`phase15-s1-b-collect-2.log`): `COLLECT_COMPLETE cluster=j-00913722ZZCSPE7LXKE8 window=05:25:19Z..06:24:17Z`
Read by the orchestrator from `collected/` (raw, unredacted, outside the repo); times converted to UTC; 5-minute CloudWatch points.
- Instances (`emr-list-instances.json`): primary group `ig-01881753...` 1 x m5.xlarge (created 05:35:26Z, ready 05:38:41Z); core group `ig-02235843...` 1 x m5.xlarge at start (ready 05:39:17Z), **2 more core created 05:43:58Z, ready 05:46:23Z and 05:46:47Z**; all four ended 06:12:03Z (cluster termination by the cleanup path; EMR records the code `INSTANCE_FAILURE` "Instance was terminated" on termination).
- Metrics: `CoreNodesRequested` 3 from 05:40Z; `CoreNodesRunning` 1 (05:40Z) then 3 (05:45Z to 06:10Z); `TotalNodesRunning` the same; `TaskNodesRunning` 0; `ContainerPending` 15, 129, 129, 716, 522, 300, 102; `ContainerPendingRatio` 5.0 to 65.1; `ContainerAllocated` 3 then 11; `AppsRunning` 1 throughout; `AppsPending` 0; `YARNMemoryAvailablePercentage` 0 throughout; `IsIdle` 1 then 0; `TotalUnits*` empty (instance-group cluster).
- Reading: **a demand-linked scale-out happened** (pending containers before and during the request; core 1 to 3, the policy maximum, at 05:43:58Z, about 2.5 min after the pipeline step started). **No scale-in** was observed: the single MapReduce application kept YARN memory at 0 % available with hundreds of containers pending at the cap of 11 allocated 3072 MB containers until the Hadoop stage timed out (exit 124 after 1,508 s); the cluster was then terminated, not scaled in. Cause of the slowness (orchestrator's reading, to be confirmed by the owners): 128 KiB splits and 3072 MB containers make hundreds of short tasks on at most 11 slots.

Open: derive-capacity on the collected files; B rerun design; B collector (3.9) not yet run (cluster describable for about two months, CloudWatch 63 days); B rerun to be planned with the A-only session (P15-22).
