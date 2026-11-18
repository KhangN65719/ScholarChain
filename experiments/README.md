# Experiments (Phase 13 draft manifests; nothing executed)

Phase 15 results (experiment-engineer, P15-46 step 3): the results summary is `RESULTS.md`, the limitations are `LIMITATIONS.md`, and the committed redacted results with their copy step are under `results/` (`results/README.md`). The paragraphs below predate the runs and still describe the drafts.

Phase 15 Wave 1 update (experiment-engineer, nothing executed; repaired 2026-11-17 after review 1): manifests A and B were rewritten for tier S (decision P15-1 caps 90 min, 64, 20,000). `DATASET_SNAPSHOT` is filled (`scholarchain-s-20261107-10dd7f34121b`, reported by WO-4 and verified by the human); `ACCOUNT_ID` and `OUTPUT_ROOT` stay placeholders. B uses the full tier S cap (decision P15-16): `MAX_MINUTES=90`, `SPEND_CEILING_USD=2.40`, pipeline stop 88 min; its managed scaling is primary 1 plus core 1 to 3 (limits count core and task nodes only). Entry points `a-baseline/run.sh` (only if the audit verdict is RE-RUN, and it is) and `b-burst/run.sh` exist; B has a read-only collector, a derive script and rehearsals on synthetic fixtures. Details, open prerequisites and the paste-ready commands are in `a-baseline/README.md` and `b-burst/README.md`. `pipeline-inputs/s-run-input.draft.json` is superseded and stale (it uses input keys the pipeline script refuses); use the two `*.template.json` files. The rest of this file still describes the Phase 13 drafts; the A and B rows of the experiments table and the "Per-key" notes below predate this update. The cost table under "Cost per experiment" was refreshed in Phase 16 Wave 2 (W2-RB3) from the committed manifests A to H.

Manifest JSON copies (decision P15-17): the course asks for a committed YAML or JSON manifest, and `scripts/run-experiment.sh` reads flat `KEY=VALUE`. Each manifest therefore keeps its `KEY=VALUE` file as the source (the approval record binds its sha256) and has a generated JSON copy `manifests/json/<name>.json` (all values are strings except `STOP_CONDITIONS`, `METRIC_NAMES` and `METRIC_NAMESPACES`, which are arrays). `bash experiments/check-manifest-json.sh` exits nonzero and names the key when any pair differs, when a JSON copy is missing, or when a JSON copy has no manifest. The edit rule: change the `KEY=VALUE` file, run `bash experiments/check-manifest-json.sh --generate`, commit both. `bash experiments/rehearse-manifest-json.sh` proves the check fails on a mismatch. The edit-time check is the human's or the test harness's to run; nothing in the approval path reads the JSON.

Phase 16 Wave 2 update (experiment-engineer): two evaluation manifests, G (`experiment-g-retrieval.manifest`, retrieval quality in graph, vector and fused mode) and H (`experiment-h-answers.manifest`, answer quality in fused mode with Haiku 4.5), join A to F, so `experiments/manifests/` holds eight manifests. Both run on the laptop (the Scala entry points `RetrievalEval` and `AnswerEval` in `experiments/evaluation/`, started by `g-retrieval/run.sh` and `h-answers/run.sh`), are unapproved and unexecuted, and are valid under `scripts/run-experiment.sh` (experiment ids G and H are accepted). Their call caps, deadline margin and stop rules are in `g-retrieval/README.md` and `h-answers/README.md`; H's call cap is 460 (decision P16-29). The Haiku diagnostic (H-4) is the one evaluation run without a manifest (decision P16-28).

Status of the Phase 13 text below: the six manifests A to F under `experiments/manifests/` were DRAFTS when this paragraph was written (they are now run; see `RESULTS.md`). They were unapproved, unexecuted and valid only for `bash scripts/run-experiment.sh --dry-run`. Entry-point scripts and load generators are authored in Phase 15. None exists yet, so a dry run reports `CHECK_SKIPPED entry-point` for each manifest and a real run refuses (the entry point must be a tracked file). No placeholder script exists, on purpose: a stub that pretends to run would be worse than a missing file.

Nothing was executed: no AWS, no sbt, no experiment entry point. No approval record exists. The checks below are by reading `scripts/run-experiment.sh`. The test harness (`tests/scripts/run.sh`) dry-ran the six committed manifests in its own temporary repository: cases `8.committed-manifest.A` to `F` passed (`RESULT pass=546 fail=0`, `docs/agent-runs/phase-13/checks-3.md`). In that dry run each manifest passed `MANIFEST_VALID` and `LIMITS`, and the entry point was reported as `CHECK_SKIPPED` because it does not exist until Phase 15. That is not a dry run of `run-experiment.sh` against the real working tree, which refuses a dirty tree (see "Checks the human runs").

## What each experiment measures (course Phase 15)

| Id | Manifest | Measures | Tier | Planned entry point (Phase 15) |
|---|---|---|---|---|
| A | `experiment-a-baseline.manifest` | Hadoop and Spark baseline at minimum EMR capacity: input and output records, task counts, duration, shuffle, failures, estimated cost | S | `experiments/a-baseline/run.sh` |
| B | `experiment-b-burst.manifest` | Larger manifest or higher partition count; EMR managed scaling response; requested, provisioned and released capacity over time | M plus `elasticity-example.tfvars` | `experiments/b-burst/run.sh` |
| C | `experiment-c-embedding-backlog.manifest` | A controlled number of text chunks enqueued; SQS depth, embedding-worker task count, throughput, retries, drain time | M | `experiments/c-embedding-backlog/run.sh` |
| D | `experiment-d-query-load.manifest` | Query load at four concurrency levels: p50, p95, p99 latency, throughput, error rate, ECS tasks, CPU, memory, Neptune and DynamoDB behavior, vector-query and LLM latency. Design in `load-tests/README.md` | M | `load-tests/d-query-load/run.sh` |
| E | `experiment-e-retrieval-modes.manifest` | Graph-only, S3-Vectors-only and fused retrieval (no OpenSearch exists, so no fourth mode) | M | `experiments/e-retrieval-modes/run.sh` |
| F | `experiment-f-failure-recovery.manifest` | One safe fault; retry, dead-letter, idempotency and recovery | M | `experiments/f-failure-recovery/run.sh` |
| G | `experiment-g-retrieval.manifest` | Retrieval quality of graph, vector and fused mode over benchmark v1 (Recall@K, Precision@K, nDCG@K, MRR, abstention outcomes); laptop runtime, Titan and S3 Vectors, stub answer model (Phase 16) | S | `experiments/g-retrieval/run.sh` |
| H | `experiment-h-answers.manifest` | Answer quality in fused mode with Haiku 4.5: answer records, stop reasons, independent audit, a 20-answer human rating sample; laptop runtime, call cap 460, USD stop 3.00 (Phase 16, P16-33) | S | `experiments/h-answers/run.sh` |

No manifest uses tier L, so the human's tier L spend gate (G-L) is not triggered by these drafts.

Phase 14 scope (decision D8, 2026-11-13): tiers M and L are NOT approved. Experiment A (tier S) is the only manifest that fits the Phase 14 scope; B to F are tier M and stay drafts until the human records a new decision against actual Billing spend. All six manifests are set to `REGION=us-east-2` (decision D1: the account is locked to us-east-2). The code cost ceiling is 20 USD (decision D6), the AWS spend limit is 20 USD, and the human's operating stop is near 15 USD. No limit in any manifest was raised.

## Rules

- Raw results are immutable. `run-experiment.sh` writes `<OUTPUT_ROOT>/<run id>/` once (`run-record.env`, `raw/`, `metrics/`, `SHA256SUMS`), makes `raw/` and `metrics/` read-only, and refuses an existing run directory. Read-only bits are not a security control. Derived plots and tables are separate files made by scripts from the raw data, never edits of it. A failed, timed-out or interrupted run keeps its record and is reported, not deleted.
- No tuning during a run. The entry point is committed code, started once. Changing code, a limit or a setting between attempts makes a new manifest digest, a new approval record and a new run id, and the earlier run stays in the results.
- One run is one experiment. A repeat after a change is reported as a different run.

## How a manifest becomes approved

1. The human replaces the placeholders in a copy (see "Placeholders") and the entry point is committed in Phase 15. Any edit changes the sha256.
2. `bash scripts/run-experiment.sh --print-digest ...` prints `SCRIPT_DIGEST` and `ARGS_DIGEST`. The arguments bound include the manifest sha256, the experiment id, the tier and the three limits; `GIT_SHA` binds the commit.
3. The human writes the approval record themselves (action `experiment`, at most 24 hours valid, single use; fields in `scripts/README.md`, "Approval record") and runs the script from a clean tree. An agent does not approve, and an agent shell call to the script stays blocked.
4. The human confirms the tier caps first. They are PROVISIONAL (decision D12) and not yet confirmed.

## Placeholders in every draft

| Key | Draft value | Why |
|---|---|---|
| `ACCOUNT_ID` | `123456789012` | The documented example from `scripts/README.md`. Not a real account, and never to be replaced by an agent. It lets a dry run parse. A real run compares it with the caller identity and refuses a mismatch. |
| `DATASET_SNAPSHOT` | `scholarchain-slice-<tier>-<works>-works-DRAFT` | The dataset-engineer has not defined snapshot ids. The name states the nested-prefix tier sizes of `COST_ESTIMATE.md` section 1.1 (also proposed, not confirmed). |
| `OUTPUT_ROOT` | `/abs/outside/repo/scholarchain-experiments` | Must be absolute and outside the repository. Replace it with a durable directory; a real run would fail to create the placeholder. |

## Open items for the human

1. Format. Resolved by decision P15-17: the script keeps parsing flat `KEY=VALUE`, and each manifest has a generated, checked JSON copy under `manifests/json/` (see the update note above).
2. Experiment D levels. The course names 1, 10, 50 and 100 clients. The tier caps (16 on M, 32 on L) force lower levels; D uses 1, 4, 8 and 16. This is forced by the provisional cap, not by budget. Raising a cap is the human's decision and is not made here. Even tier L (32) could not reach 50 or 100.
3. Experiment B burst. M-tier managed scaling with `elasticity-example.tfvars` is master plus 4 core nodes, 20 vCPUs. The vCPU quota is unchecked.
4. Experiment C needs an embedding worker and SQS queues. This work order did not verify that Terraform defines them, nor the embedder choice (Titan V2 costs tokens; the 384-dimension hash embedder cannot use the 256-dimension indexes, see `COST_ESTIMATE.md`).
5. Metric names and namespaces are written from memory of CloudWatch naming and are UNVERIFIED. `ECS/ContainerInsights` task counts exist only if Container Insights is on, which was not checked. The script lists every namespace and name pair, so a wrong name gives an empty listing, not an error. Application-level latency (p50, p95, p99, Neptune query, vector query, LLM) comes from the entry point's raw output, not CloudWatch. Whether the services emit custom metric namespaces was not checked.
6. `MAX_TASKS` for A and B (100) is a tight bound with an unconfirmed unit.
7. Entry points A and B will likely call the existing pipeline path. Whether that is `run-bounded-pipeline.sh` or an `sbt:` task is a Phase 15 design choice.
8. The estimated spend stop is after the fact. `spend-estimate` makes the script exit nonzero once burn x elapsed time exceeds the ceiling. It does not stop a run. Only `wall-clock` stops the entry point, and the script cannot stop an entry point from exceeding `MAX_CONCURRENCY` or `MAX_TASKS`.
9. Each run needs its session brought up and down (`plan.sh`, `deploy.sh`, `session-down.tfvars`), each with its own approval record. That is outside these manifests and outside their cost bound.

## Cost per experiment

Source: `infra/terraform/COST_ESTIMATE.md` (rates mostly UNVERIFIED; "LOW" is the remembered-rate case, not a verified floor) and `scripts/README.md`, "Pipeline sizes", for the hourly burn. The estimate has no per-experiment line. The mapping to its timeline rows is mine and an assumption.

| Id | Max minutes | Burn per hour (UNVERIFIED) | Projected window spend | `SPEND_CEILING_USD` | Nearest estimate row, session cost LOW / HIGH |
|---|---|---|---|---|---|
| A | 75 | 0.60 | 0.75 | 0.75 | 14.1 Pipeline S, 0.42 / 1.93 |
| B | 90 | 1.55 | 2.325 | 2.40 | 15.3 Elasticity on M, 1.22 / 4.45 (Phase 13 mapping; B now runs tier S data) |
| C | 60 | 0.45 | 0.45 | 0.60 | none; the ceiling includes Titan chunk tokens and S3 Vectors PUTs (manifest comments, HIGH about 0.58) |
| D | 70 | 0.55 | 0.642 | 0.65 | 15.4 Load and latency, 2.70 / 6.77 (Phase 13 mapping) |
| E | 60 | 0.42 | 0.42 | 0.45 | 15.1 and 15.2 query sessions, 0.62 / 2.79 and 0.73 / 3.04 (Phase 13 mapping) |
| F | 30 | 0.40 | 0.20 | 0.25 | none |
| G | 60 | 0.10 (nominal) | 0.10 | 0.25 | none; Titan and S3 only, no Haiku: expected under 0.05, HIGH 0.25 (plan.md, rates UNVERIFIED) |
| H | 90 | 0.80 (nominal) | 1.20 | 3.00 | none; Haiku 4.5 through Bedrock: expected about 1.3 to 2.1 (76 answers x 0.017 at the old 2000 output tokens to 76 x 0.0275 at 4096, from the H-4 diagnostic h4-diagnostic-1: input 4931 to 7080, output 1990 to 2000; retries not included), HIGH 3.00 (rates UNVERIFIED; raised from 2.00 by decision P16-33 because synthesis `maxOutputTokens` is 4096, P16-32; about 0.027 USD per full synthesis call) |
| Sum A to H | | | 6.087 | 8.35 | |

Every row is read from the committed manifest in `experiments/manifests/` (`MAX_MINUTES`, `HOURLY_BURN_USD`, `SPEND_CEILING_USD`); projected spend is burn x max minutes / 60. The Sum covers all eight manifests A to H. Arithmetic: projected 0.75 + 2.325 + 0.45 + 0.642 (0.55 x 70 / 60 = 0.6417) + 0.42 + 0.20 + 0.10 + 1.20 = 6.087 (A to F alone 4.787, G and H 1.30); ceilings 0.75 + 2.40 + 0.60 + 0.65 + 0.45 + 0.25 + 0.25 + 3.00 = 8.35 (A to F alone 5.10, G and H 3.25; was 7.35 and 2.25 with H at 2.00). The projected figures are unchanged because H's nominal burn (0.80) is unchanged. The "Nearest estimate row" column keeps the Phase 13 mapping and was not re-derived.

The ceilings bound the entry-point window only. The estimate rows are whole-session costs over 1 to 8 hours, so for B and D the ceiling is below the estimate's LOW or HIGH figure because the manifest caps the window (90 and 70 minutes); the setup and teardown hours around it are not in the manifest. The cumulative ceiling is now 20 USD (decision D6; it replaced the 25 USD of `docs/agent-runs/phase-12/ratification.md`) and the script checks only that each `SPEND_CEILING_USD` is at most `--cost-ceiling-usd`, not the cumulative figure. The estimate itself puts S plus M at $25.42 at the HIGH bound, over the 20 USD ceiling (decision D8), and does not claim a fit, so the human reads Billing before each run (gates G-S, G-M) and records actual spend, and a ceiling here is not a forecast or a bill.

Known limit accepted by the human (decision O1): a pipeline run at the estimate's HIGH hours may exceed the then 60 (now 75) and 90 minute caps of A and B. A timed-out run still spends and needs a new approval record.

## Per-key check against the script

The same table, for every manifest, is in the work report. Short form: every one of the 17 keys appears exactly once per file, every line is a comment or `KEY=VALUE` with no spaces, and `STOP_CONDITIONS` is `wall-clock,first-failure,spend-estimate` in all eight (A to F and the Phase 16 manifests G and H).

## Checks the human runs

Not yet run by the human against the real working tree. The harness dry-ran the committed manifests in a temporary repository (`checks-3.md`, cases `8.committed-manifest.A` to `F`); that does not replace the commands below. `--print-digest` of a committed manifest now works although its entry point does not exist yet. The dry run needs a clean tree (commit these files first; other untracked files such as `docs/.DS_Store` also count as dirty) and an approval record that binds the manifest. The first line below (`--print-digest`) exits after manifest validation and prints the digests: it checks no tree and reads no approval record, so it does not stop at `clean-tree` or `approval-record`. The second line (the dry run) prints `MANIFEST_VALID` and `LIMITS`, then stops at `clean-tree` on a dirty tree, or at `approval-record` on a clean tree whose record does not bind the manifest.

```
bash scripts/run-experiment.sh --print-digest --manifest /abs/path/to/experiments/manifests/experiment-a-baseline.manifest --environment course --account-id 123456789012 --region us-east-2 --cost-ceiling-usd 20
bash scripts/run-experiment.sh --dry-run --manifest /abs/path/to/experiments/manifests/experiment-a-baseline.manifest --environment course --account-id 123456789012 --region us-east-2 --cost-ceiling-usd 20 --approval-record /abs/path/to/approval.env
```

Repeat for the other seven manifests. Expect `MANIFEST_VALID`, `LIMITS`, then `CHECK_SKIPPED entry-point` and `DRY_RUN_COMPLETE`.
