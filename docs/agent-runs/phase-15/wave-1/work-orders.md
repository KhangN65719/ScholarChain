# Phase 15 Wave 1 work orders

Issued by the orchestrator on 2026-11-16 under `../plan.md` and `../decisions.md`. Common rules for every order:
- Read `CLAUDE.md`, `docs/agents.md`, your role file, `../plan.md`, `../decisions.md` and `../prompt.md` first.
- No AWS command of any kind, no terraform plan/apply/destroy, no deploy, no commit, read-only git. Region us-east-2 only.
- Write only inside your owned paths. A need outside them is reported as `BLOCKED-ON <owner>` with evidence.
- Before implementation, list which specific test proves each item of your primary gate; put it in your report.
- sbt: only the orders that say so run `sbt quick`, and never two sbt processes at once. Bare two-token form only.
- Every paste-ready human command: `AWS_PROFILE=scholarchain`, `set -o pipefail`, log tee'd to `/Users/winnguyen/scholarchain-data/logs/`, ending in `| tail -n 60; echo "exit: $?"`; zsh-safe (no unbraced argument variables).
- Do not weaken tests. No account ID in any file.
- Report: files changed, tests run with exact pass and fail counts, open items, and anything not verified.

## WO-1 infra-engineer
1. `infra/terraform/environments/course/experiment-size-s.tfvars`: set `neptune_instance_class = "db.t4g.medium"`; fix line 1 to the real size (3,472 works, Phase 14 run 6); correct the unsourced "660 s" creation comments or mark them unsourced.
2. New overlay `graph-load.tfvars`: only `neptune_instance_class = "db.r6g.xlarge"`, layered on the S overlay for the session 2 bulk load (P15-5). Document the session 2 apply order: S + graph-load up, loader graph-only, then S alone (back to t4g.medium), each apply with its own approval record. State the expected Neptune modify behaviour and downtime as UNVERIFIED unless read from AWS documentation (the aws-documentation MCP may be used).
3. New overlay `elasticity-s.tfvars` for Experiment B on S data: `emr_core_instance_count = 1`, `emr_managed_scaling_max_units = 4` (min 2, max 4 instances, 8 to 16 vCPU), `emr_task_spot_instance_count = 0`, Neptune off, `service_desired_count = 0`, Bedrock off, and the S pipeline timeouts re-checked against Rule A with the added wait. Leave `elasticity-example.tfvars` unchanged but note in its header that min = max (5) cannot scale.
4. A bounded Step Functions `Wait` state (variable, default 0 = absent; elasticity overlay about 900 s) before `TerminateCluster`, only when the variable is non-zero, so a scale-in can be observed. Keep Rule A (create + step + wait + 600 within the execution timeout) enforced by validation.
5. `scripts/run-experiment.sh` tier caps (P15-1): S becomes 90 minutes, 64 concurrency, 20,000 tasks; M and L unchanged. Update `scripts/README.md` to mark D12 decided by P15-1.
6. Paste-ready read-only checks for the human (P15-8, P15-4), in a new reviewed doc section (for example `scripts/README.md`, "Phase 15 pre-session checks"): EC2 Running On-Demand Standard instances vCPU quota and Fargate On-Demand vCPU quota in us-east-2 via `aws service-quotas get-service-quota` (verify the quota codes from AWS documentation and cite the page); and Bedrock model access for the Haiku model in us-east-2 (read-only calls only, for example `aws bedrock list-inference-profiles` / `get-foundation-model-availability`; verify the command exists in documentation and cite it; the human enables access in the console if missing).
7. Gate: `bash scripts/check.sh --skip-sbt` (terraform fmt and validate, script syntax) and the script harness `bash tests/scripts/run.sh` if your changes touch its cases; report exact counts. Do not run sbt.

## WO-2a hadoop-engineer
Experiment B needs a higher partition or reducer count without changing algorithms (P15-7). Determine whether the Hadoop jobs' reducer counts (and any split size) are already runtime configuration reachable from the pipeline invocation (`scripts/emr/**` is infra's; read it, do not edit). If a setting exists, report its exact name and how it is passed. If not, add one in `modules/hadoop/**` with the default unchanged and a feature test showing default output is byte-identical and a higher value runs on the fixture. Run `sbt quick` once at the end. If the pipeline invocation needs a new argument, report `BLOCKED-ON infra-engineer` with the exact argument.

## WO-2b spark-engineer
Same as WO-2a for `modules/spark/**`: Spark shuffle partitions and any repartition counts. **Do not run sbt in this turn**: hadoop-engineer holds the sbt slot. Make the change and its tests, then report `READY-FOR-SBT`; the orchestrator resumes you to run `sbt quick` when the slot is free.

## WO-3 experiment-engineer
1. A audit (P15-2): read S run 6 records (`docs/agent-runs/phase-14/**`, `docs/releases/phase-14/**`, local logs under `/Users/winnguyen/scholarchain-data/logs/`, read-only) and report, per course field (input records, output records, task counts, duration, shuffle, failures, estimated cost), whether a value is recorded and where. Verdict: REUSE or RE-RUN. If shuffle or task counts exist only in EMR logs in S3, give the paste-ready read-only command for the human to fetch them.
2. Entry points `experiments/a-baseline/run.sh` (only if RE-RUN) and `experiments/b-burst/run.sh`: call the existing pipeline path, start once, no tuning, write raw output under the run directory, UTC ISO-8601 timestamps.
3. B collector: paste-ready read-only commands for the human that capture requested, provisioned and released capacity over time (EMR instance-group or instance timelines, the EMR CloudWatch capacity metrics; verify metric names from AWS documentation and cite them) for the run window, written as immutable raw files.
4. Plot and table scripts that derive from raw files only, rehearsed on fixture JSON in your territory.
5. Manifests A and B: tier S, `DATASET_SNAPSHOT` left as a marked placeholder until WO-4 reports the ID, limits within the P15-1 caps, `HOURLY_BURN_USD` and `SPEND_CEILING_USD` consistent with `../plan.md`. Placeholders for account ID and output root stay as documented.
6. Gate: `bash scripts/run-experiment.sh --print-digest` on each changed manifest, collector and plot rehearsal output. No sbt.

## WO-4 dataset-engineer
1. Define the tier S snapshot ID for the data actually run in Phase 14 (DBLP subset 2026-11-05, OpenAlex, Crossref and ORCID 2026-11-07; checksums from the existing receipts) in `datasets/**`.
2. Define a bounded chunk set for Experiment C: 2,000 chunks drawn deterministically from the existing semantic-context export (the local copy), with a manifest stating source, selection rule, count, bytes and sha256. If the local export is not readable from your territory, report what you need.
3. Gate: validate the manifest and replay the bounded sample selection; report checksums. No sbt.

## WO-12a test-engineer
From the WO-1 and WO-3 specifications (do not wait for implementation): add script-harness cases under `tests/**` for the S overlay class (`db.t4g.medium`), the graph-load overlay (xlarge only), the elasticity overlay (min < max, no Neptune, no services), the Wait state absent by default and bounded when set, Rule A with the wait, the new S caps (90, 64, 20,000; M and L unchanged), and that the pre-session check commands contain no mutating AWS verb. Run the harness after WO-1 lands and report exact counts; until then report which cases fail as expected. No sbt.
