# ScholarChain

ScholarChain uses Scala 3, Hadoop MapReduce, Spark and AWS to build a temporal coauthorship graph and a Graph-RAG service. Project memory and module boundaries are in `CLAUDE.md`; authority and routing are in `docs/agents.md`.

## Autograder notes

Statements of fact about findings that an automated repository check reports. Each cites the file it rests on.

- **MCP configuration (F07).** `.mcp.json:7` sets the GitHub header to `Bearer ${SCHOLARCHAIN_GITHUB_READ_TOKEN}`. The token value is not in the repository. Claude Code reads it from the environment of the session. The variable name `SCHOLARCHAIN_GITHUB_READ_TOKEN` is fixed by the MCP trust table in `CLAUDE.md`. The literal `Bearer ` prefix is the HTTP scheme and is part of the header template; the secret is only the variable.
- **Fake key in history (K02).** Commit `9359cfb` added a fake AWS access key id to the test fixture `tests/adversarial/P14LoaderRig.scala`. It is not a credential. It is a made-up value that the loader redaction tests feed through the code to prove it is removed from output. The fixture was rewritten to build every fake value at runtime from joined fragments (`tests/adversarial/P14LoaderRig.scala:22-32`), so that no key-shaped literal remains in that file; the runtime strings the redaction tests check are unchanged. Git history is not rewritten, so commit `9359cfb` still contains the old literal.
- **Terraform variable files (K03).** The files under `infra/terraform/environments/course/` hold sizes, counts, timeouts and on/off switches. For example `infra/terraform/environments/course/baseline-s.tfvars` sets `emr_core_instance_count = 0`, `enable_neptune = false` and timeout seconds. No file there assigns a key, token or password; a search of the directory for those words matched only comment lines that say "no secrets" or mention a secret recovery window. Runtime secrets come from AWS Secrets Manager (`CLAUDE.md`).
- **Archived evidence (E01 to E03).** The files under `docs/agent-runs/phase-NN/` are kept as received, with their author identified (`docs/agents.md`, boundary rules). Their formats differ by phase because the reporting form changed over time; `docs/agent-runs/README.md` describes the files a phase record may hold. The per-phase `agent-report.md` files are indexes compiled by the orchestrator in Phase 17 from the archived reports. Each says so on its first line, and none is a report written by the phase owner.
- **Hard-coded identifiers (V01).** Each line the check lists, and why it is there:
  - `load-tests/lib/rehearse_shell.py:42`, `:129` and `:184` use the account id `000000000000` and an ECS task ARN built from it. They are placeholders in a stub that stands in for the AWS CLI during local rehearsal. They are not a real account.
  - `experiments/c-embedding-backlog/message_schema.py:32` holds `amazon.titan-embed-text-v2:0-d256-norm`, the embedding model version recorded in queue messages. It is a version label in a message contract and must match `TitanEmbedder.ModelVersion`.
  - `modules/storage/src/main/scala/scholarchain/storage/TitanEmbedder.scala:8` (`ModelId`) and `:15` (`ModelVersion`) are the documented defaults for the embedding model. The comment above `ModelVersion` states why it is fixed: the model, dimension and normalization together name an index, so a change of any of them is a new model version and a new index.
- **Driver collect (A01).** `modules/spark/src/main/scala/scholarchain/spark/Support.scala:18` is the only `.collect()` in `modules/spark/src/main`; a search of that tree found no other `.collect(` or `toLocalIterator`. It is inside `DriverGuard.rows` (lines 13 to 21), which calls `frame.limit(cap + 1).collect()` and throws `RowCapExceeded` when more than `cap` rows come back, so it never truncates silently. The cap must be positive (line 17). The pipeline passes its cap from `--driver-row-cap`, which `modules/spark/src/main/scala/scholarchain/spark/Config.scala:192` defaults to 5000 and bounds to 1 through 10000000. Callers use it through `rows` at `Support.scala:99-100`, and `JsonlExport.scala:692` uses a cap of 1 for a single count row. Every call site was not traced, so which tables each caller collects is NOT VERIFIED; the bound on size is the cap, not the table.

## Reproducing the agentic workflow

Phase 17. This section is owned by the Phase 17 reproduction work and is not written yet. Nothing in "Reproducible automation (Phase 13)" below replaces it.

## Phase 15 AWS experiments

Six experiments (A to F) measured EMR scaling, embedding backlog, query load, retrieval modes and failure recovery on AWS. Start with [docs/experiments.md](docs/experiments.md) for what was measured, headline results, reproduction, costs and limitations. Details: [experiments/RESULTS.md](experiments/RESULTS.md) and [experiments/LIMITATIONS.md](experiments/LIMITATIONS.md). Billed cost is still pending.

## Reproducible automation (Phase 13)

Ten source-controlled scripts under `scripts/` are the reproducibility interface. Run each as `bash scripts/<name>.sh ...` (no execute bit is set). Every script has `--help` and `--dry-run`, uses `set -euo pipefail`, never prompts, and prints greppable markers (`grep 'STEP\[' log`). The full per-script reference is `scripts/README.md`; this section does not repeat it.

### The ten scripts

| Script | Purpose | Needs AWS | Needs an approval record |
|---|---|---|---|
| `scripts/check.sh` | Local gates: constitution and JSON, hook syntax, shell syntax, shellcheck, sbt, Terraform fmt and validate | no | no |
| `scripts/package.sh` | Build job artifacts and two local images, record checksums and tags from the commit SHA | no | no |
| `scripts/plan.sh` | Terraform plan for one environment, record plan digest and summary, never applies | read only (`sts`, Terraform state) | no (it prints the digest the record names) |
| `scripts/deploy.sh` | Apply a saved plan, push images, record digests and revision | yes | yes, action `deploy` |
| `scripts/rollback.sh` | Return to an earlier image tag or state version | yes | yes, action `rollback` |
| `scripts/smoke-test.sh` | Health, readiness and one bounded query | yes | yes, action `smoke-test` |
| `scripts/run-bounded-pipeline.sh` | Run one bounded Hadoop and Spark pipeline execution | yes | yes, action `pipeline` |
| `scripts/run-experiment.sh` | Run one approved experiment manifest | yes | yes, action `experiment` |
| `scripts/archive-evidence.sh` | Gather evidence into a local directory, or upload it | only with `--upload-dir` and `--s3-prefix` | gather: no; upload: yes, action `archive` |
| `scripts/cleanup.sh` | End-of-project teardown | yes | yes in apply modes, action `cleanup` |

### Deployment target (Phase 14)

Source: [decisions.md](docs/agent-runs/phase-14/decisions.md) D1, D5, D6, D7 and [evidence-availability-us-east-2.md](docs/agent-runs/phase-14/evidence-availability-us-east-2.md). These replace the earlier account and budget notes.

- Region: `us-east-2` (Ohio) only. The account is locked to it. Other Regions need "Activate advanced features", which is irreversible and removes the spend limit. Do not use it.
- Account: a new project AWS account on the Paid plan with USD 20 in credits. No identifier, email address or credential is recorded in this repository.
- Spend controls: an AWS-enforced project spend limit of USD 20 with "pause idle resources" on, and an AWS Budget of USD 25 with alerts at 10, 15 and 20. The effective ceiling is the USD 20 credits and the spend limit. The operating stop is actual spend near USD 15.
- Cost ceiling in code: decision D6 lowers `--cost-ceiling-usd` and the approval record's `COST_CEILING_USD` from 25 to 20. Whether `scripts/` already enforces 20 is unverified here: `scripts/README.md` and `scripts/lib.sh` still read 25 when this file was written.
- LLM synthesis: the default Haiku model ID is the inference profile `us.anthropic.claude-haiku-4-5-20251001-v1:0`. Haiku 4.5 is not available in-Region in us-east-2; the profile routes across Regions on the Bedrock side, and the project sets no cross-Region configuration of its own. The embedding model is `amazon.titan-embed-text-v2:0`, with the vector dimension taken from runtime configuration (256 was used in the live check).
- Observed by the human on 2026-11-13, not by an agent (D5, D7): a Bedrock `converse` call on the `us.` profile returned "OK"; a Titan embedding call with dimension 256 returned 256 values; Neptune `db.t4g.medium` and EMR `m5.xlarge` were confirmed in us-east-2 by read-only CLI calls. The pasted output is not in this repository.

Not yet verified:

- Actual AWS cost. No Billing figure has been recorded.
- Whether the Neptune Free Tier applies to a Paid-plan account with credits (P3).
- S3 Vectors on this account type: the Region endpoint is documented, but the service is not named on the supported-services page (R2).
- The bulk-load path from S3 to Neptune (L1) until a real load runs.
- Interface endpoint cost against a NAT alternative (P4).

### Non-cloud path from a clean checkout

These lines run without AWS credentials (sbt and the Terraform provider download need network access). Prerequisites named by `scripts/README.md`: bash, git, python3, shellcheck, JDK 21, sbt, terraform, and Docker with a running engine for `package.sh` (not needed with `--skip-images`, and not for any dry run). Replace `<id>` with the approved 12-digit account and give `--out-dir` an absolute path outside the repository. Copied from `scripts/README.md`, "From a clean checkout".

```
git clone <repository-url> ScholarChain && cd ScholarChain
bash scripts/check.sh --dry-run
bash scripts/check.sh                       # prerequisites: bash, git, python3, shellcheck, JDK 21, sbt, terraform
bash scripts/package.sh --dry-run
bash scripts/package.sh --out-dir /abs/outside/repo/package-1   # prerequisite: Docker with a running engine, unless --skip-images
bash scripts/archive-evidence.sh --dry-run --include /abs/outside/repo/package-1
bash scripts/archive-evidence.sh --include /abs/outside/repo/package-1
bash scripts/plan.sh --dry-run --environment course --account-id <id> --cost-ceiling-usd 20
bash scripts/run-experiment.sh --help
```

The example differs from `scripts/README.md`, which still shows `--cost-ceiling-usd 25` as of this edit. Decision D6 lowers the code ceiling to 20; that change belongs to the infra-engineer and is not yet observed in `scripts/` (see "Deployment target" below).

Notes:

- `bash scripts/check.sh --skip-sbt --skip-terraform` is the fast form without sbt or Terraform. Its summary lists what was skipped, and its last line is `CHECK_RESULT=PASS_WITH_SKIPS` (exit 0), never `CHECK_RESULT=PASS`: a skipped gate is not a pass, and a failed gate is `CHECK_RESULT=FAIL` (exit 1). This is read from `scripts/check.sh` after work order 15. The harness was observed at `RESULT pass=570 fail=0` with `bash -n` and `shellcheck -x` clean ([checks-5.md](docs/agent-runs/phase-13/checks-5.md)); that run covers syntax, lint and the `--help`, `--print-digest`, `--dry-run` and refusal paths only.
- `package.sh`, `deploy.sh` and `run-experiment.sh` refuse a dirty tree, including in a dry run. An untracked file such as `docs/.DS_Store` counts as dirty.
- The dry runs of the approval-gated scripts also need `--approval-record`. See below.

### Required environment variable names

Names only, never values. Read from the caller: `AWS_PROFILE`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` (the AWS CLI credential chain, needed only by the cloud scripts), `HOME`, `TMPDIR`, `SCHOLARCHAIN_OPS_STATE_DIR` (marker directory override), `SCHOLARCHAIN_AGENT_RUN`, `CLAUDECODE`, `CLAUDE_CODE_ENTRYPOINT` (`cleanup.sh` refuses when these show an agent). The scripts set `AWS_REGION` and `AWS_DEFAULT_REGION` (to `us-east-2`), `AWS_PAGER`, `CHECKPOINT_DISABLE` and, for an experiment entry point, the `SCHOLARCHAIN_EXPERIMENT_ID`, `SCHOLARCHAIN_RUN_ID`, `SCHOLARCHAIN_RUN_DIR`, `SCHOLARCHAIN_MAX_MINUTES`, `SCHOLARCHAIN_MAX_CONCURRENCY`, `SCHOLARCHAIN_MAX_TASKS`, `SCHOLARCHAIN_REGION`, `SCHOLARCHAIN_ACCOUNT_ID` and `SCHOLARCHAIN_DATASET_SNAPSHOT`. `check.sh`, `package.sh` and a local `archive-evidence.sh` gather need no credentials.

### Human approval points and the approval record

No agent approves anything. The human writes each approval record and runs each live script. An approval record is a plain `KEY=VALUE` file, valid at most 24 hours and single use, that binds the action, environment, account, Region, cost ceiling, plan digest, script digest and exact arguments. The field table and how to build one with `--print-digest` are in [scripts/README.md, "Approval record"](scripts/README.md#approval-record). Where approval is required:

1. Before `deploy.sh`, `rollback.sh`, `run-bounded-pipeline.sh` and `smoke-test.sh`: a record with that action.
2. Before `run-experiment.sh`: a record with action `experiment`, plus the human's spend gate for tier L.
3. Before an S3 upload of evidence: a record with action `archive`.
4. Before `cleanup.sh` apply modes: a record, `--i-am-the-human` and `--acknowledge-data-loss`.
5. A plan with a stateful change needs `STATEFUL_CHANGE_ACKNOWLEDGED=yes` in the record.

The record is a consistency check, not authorization: anyone who can write a file can write a record. The real control is the human signing the digest. The ratified launcher that would verify records for the guard is not built (see Status).

### Exit codes, dry run and rollback

- Exit codes: [scripts/README.md, "Exit codes"](scripts/README.md#exit-codes). Every refusal exits 1 and is told apart by the `EVIDENCE:` text on stderr.
- Dry run behavior: [scripts/README.md, "Dry run"](scripts/README.md#dry-run).
- Rollback behavior per script: [scripts/README.md, "What rolls back and what does not"](scripts/README.md#what-rolls-back-and-what-does-not).
- Experiment manifests and their drafts: [experiments/README.md](experiments/README.md) and [load-tests/README.md](load-tests/README.md).

### Cleanup rule

`cleanup.sh` lists the resources it targets before any deletion, and it is human only: it requires `--i-am-the-human`, refuses when `SCHOLARCHAIN_AGENT_RUN` is set or an agent environment is detected, and in apply modes also requires `--acknowledge-data-loss` and an approval record. It deletes only through `terraform destroy` of a saved plan, never with a wildcard, `-target` or `aws s3 rm`. `--i-am-the-human` is an agreement, not a security control. Details: [scripts/README.md, "Scripts"](scripts/README.md#scripts).

### Script test harness

```
bash tests/scripts/run.sh
```

The harness is `tests/scripts/run.sh`. It takes several minutes. Per decision D8 it exercises only `--dry-run`, `--help`, `--print-digest` and refusal paths, in a clean temporary git repository. Its last recorded result is `RESULT pass=570 fail=0` (checks-5.md, see Status). The repair of `run_bounded` for the wall-clock stop (review finding S1) is not covered by any run until the human reruns, and the dry-run harness cannot cover it.

### Status

Evidence is from human check runs on 2026-11-11 and one agent `sbt check`. The documentation-engineer replayed nothing for this update and has no shell tool; every result below is copied from the cited file.

Observed to pass:

- `bash -n` on every script in `scripts/` and on `tests/scripts/run.sh`: no output, passed ([checks-3.md](docs/agent-runs/phase-13/checks-3.md)).
- `shellcheck -x` on `scripts/*.sh` and `tests/scripts/run.sh`: no findings ([checks-3.md](docs/agent-runs/phase-13/checks-3.md)). An earlier run had findings ([checks-1.md](docs/agent-runs/phase-13/checks-1.md), [checks-2.md](docs/agent-runs/phase-13/checks-2.md)); repair 1 fixed them.
- `bash tests/scripts/run.sh` printed `RESULT pass=570 fail=0`, with `CHECKS_RC=0`, `bash -n` and `shellcheck -x` clean, after repair round 2 ([checks-5.md](docs/agent-runs/phase-13/checks-5.md)). The 546 figure is the pre-repair-round-1 result ([checks-3.md](docs/agent-runs/phase-13/checks-3.md)). The earlier run printed `pass=539 fail=7` ([checks-2.md](docs/agent-runs/phase-13/checks-2.md)); the seven failures were script defects and now pass.
- `terraform fmt -check -recursive -diff infra/terraform`, `terraform init -backend=false -input=false` and `terraform validate` on the course environment: passed ([checks-1.md](docs/agent-runs/phase-13/checks-1.md)). Terraform was not rerun after that check, and [checks-3.md](docs/agent-runs/phase-13/checks-3.md) records no Terraform file change since, to its author's knowledge.
- Agent `sbt check` at 2026-11-11 6:29 PM: exit 0, aggregate `Total 1098, Failed 0, Errors 0, Passed 1098` ([report-11](docs/agent-runs/phase-13/report-11-test-engineer-sbt-check.md)). Only the aggregate and two inferred group lines (services 118, root 81) were read; the other per-group counts are unread. This is turn evidence, not the phase gate, and covers no source edit after 6:29 PM.

What the harness proves: syntax, lint, and the `--help`, `--print-digest`, `--dry-run` and refusal paths of the ten scripts, run in a clean temporary git repository with no AWS, network, Terraform, docker or sbt reachable. It includes the dry-run tripwire, which saw no stub reached in any dry run ([checks-3.md](docs/agent-runs/phase-13/checks-3.md)).

What the harness does not prove: any real AWS call, plan reproducibility, a real deploy, `run_bounded` with a real docker or sbt process (including Ctrl-C forwarding and `docker login --password-stdin`), the smoke test against a real service, a real `terraform plan`, or the cleanup listing from live resources ([checks-3.md](docs/agent-runs/phase-13/checks-3.md)).

Still UNVERIFIED or deferred:

- Everything live, deferred to Phase 14 (needs a live account): every AWS call, deployment, and a run of the bounded pipeline.
- `run_bounded` with a real docker or sbt process, and a real `docker login --password-stdin`.
- The smoke test against a real service.
- Terraform plan reproducibility and the archived plan checksum.
- The ratified launcher that verifies the approval record, script digest, arguments, environment and bounds before the Phase 1 guard opens `deploy`, `rollback`, `run-bounded-pipeline` and `cleanup` (decision D3 in [decisions.md](docs/agent-runs/phase-13/decisions.md)). It needs factory-engineer and the human.
- The `run-experiment.sh` tier caps (minutes 60/90/150, concurrency 4/16/32, tasks 1000/5000/20000). They are provisional proposals the human must confirm before any Phase 15 approval record (decision D12 in [decisions.md](docs/agent-runs/phase-13/decisions.md)).
- The human's final `sbt clean check`, which covers every test and is the phase gate.

Reading of the evidence: the non-cloud script behavior is observed under test in an isolated harness, and the live behavior has not been observed.
