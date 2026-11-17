# Pipeline execution inputs (experiment-engineer)

Status: templates authored, NOT executed. No AWS call, no approval record. The inputs follow the contract in `scripts/README.md`, "Execution input contract", and the validator at `scripts/run-bounded-pipeline.sh:156-229`.

| File | Mode | Used by |
|---|---|---|
| `s-a-baseline.template.json` | pipeline | Experiment A (placeholder run id, loader skipped) |
| `s-b-burst.template.json` | pipeline | Experiment B (placeholder run id, loader skipped) |
| `s-graph-only-reload.template.json` | loader-only | Session 2, step A1: Neptune graph-only reload of A's export |
| `s-run-input.draft.json` | none | superseded and stale (keys the script refuses); do not use |

## s-graph-only-reload.template.json (readiness review item B2)

Not a placeholder template: `run_id` is already A's run id, `run-v1-b76e2d37-2ac1-40d4-923b-9418d5ff72c7`, the id in the human's `p15-ab-a.json` (session A and B). The last element of `loader_command` is the same id (the script refuses a `run-` last element that differs from `run_id`, and skips `--` options when it looks). Fill rule if A is ever re-run: replace both occurrences with the new run's id and nothing else; the file must keep exactly the keys `mode`, `run_id`, `loader_command`. The loader main class `scholarchain.rag.AwsIngestMain` and the argument order are copied from the input of S run 11 (`s-graph-only-2.json`, which also carried `--tolerate-load-errors`) and from the usage line in `AwsIngestMain.scala:122`.

What it does: the Fargate loader downloads `semantic-context/runs/<run_id>/export/`, skips ingest, and bulk-loads the graph into Neptune (`AwsIngestMain.scala:71-81`). No EMR cluster is created. It writes `graph-report.json` and `graph-run.json` under `reports/runs/<run_id>/loader/`. Use the loader-only mode copy of the approval record: action `pipeline`, single use, fresh record per execution.

### Parameters for `run-bounded-pipeline.sh` (to be approved by the human; values are mine to propose)

| Parameter | Value | Why |
|---|---|---|
| `--size` | `large` | The loader timeout of the S overlay is 5400 s (90 min). `small`/`medium` caps are below that; `large` has cap 150 minutes (`scripts/README.md:505`). |
| Loader cap | 5400 s (90 min) | S overlay loader timeout, `scripts/README.md:505`; `COST_ESTIMATE.md:126` uses the same 1.5 h as the HIGH case. |
| `--max-minutes` | 100 | The 90 min loader cap plus about 10 min for task start, the export download and the report upload. Inside the `large` cap of 150. Run 11 (11 tables, 12 load jobs) took 4.7 min whole; 100 is the bound, not the expectation. |
| `--hourly-burn-usd` | 0.97 | `infra/terraform/COST_ESTIMATE.md:102`: loader task about 0.1165 + 9 endpoints at 0.015 (0.135) + Neptune `db.r6g.xlarge` at 0.70 = 0.9515, rounded to 0.97. Rate for r6g.xlarge is 0.62 to 0.70 (`:92`), UNVERIFIED (memory, anchored on r5.large 0.348). Excludes the EMR node (there is none) and the Neptune creation wait before the run. |
| `--spend-ceiling-usd` | 1.70 | 0.97 x 100 / 60 = 1.617, rounded up. The script counts burn x time only, not per-request charges (`scripts/README.md:515`). An actual run at the run 11 pace is about 0.08 to 0.30. |
| `--cost-ceiling-usd` | 20 | Code ceiling (`COST_ESTIMATE.md:7`). |
| `--stop-on` | `first-failure,dlq-nonempty` | As the S runs (run 11 record). |

Session context: A1 in `COST_ESTIMATE.md:126` is 0.76 LOW to 1.67 HIGH including about 0.2 h of Neptune creation at 0.72 to 0.84 an hour (outside the execution, so not in this ceiling). Session 2 starts only if billed spend is at most 9.5 USD (`COST_ESTIMATE.md:135`). The human reads Billing before approving.

### Recommendation on `--tolerate-load-errors`: leave it OUT (default off)

- Evidence for off: S run 11 on the same code path loaded 12 jobs with 0 parsing, datatype and insert errors (`docs/agent-runs/phase-14/results-s-tier.md:52`). Run 10's failure was a memory error on the t4g.medium writer, not a data error; the writer is now `db.r6g.xlarge` (`COST_ESTIMATE.md:90`). With 0 observed data errors the flag would change nothing.
- Without the flag a bad record fails the load loudly (`NEPTUNE_LOAD=FAILED`, exit 1). With it, up to 0.1% of Neptune's counted records are skipped and the result is `SUCCEEDED_WITH_WARNINGS` (`AwsIngestMain.scala:83-96`), so a data problem in A's export would be recorded as a pass for the experiment and a skipped edge could change a later query answer. A silent skip is the wrong default for the evidence the experiments need.
- Evidence gap, not closed here: A's export has 17 tables, not 11 (readiness review B2), and no graph-only load of 17 tables has been observed; the tolerance path has never run on real Neptune (`results-s-tier.md:55`). If the first load fails with named, countable load errors (not OOM), the decision is the human's: copy this file, add `"--tolerate-load-errors"` after `"--graph-only"` as the last element of `loader_command` (an option after the run id is allowed by the script), and run again with a NEW approval record (single use) and the same bounds. That is a second run; the first stays in the results. No second template is committed, to avoid pre-approving it.

### What this template does not settle (other owners)

- Vectors versus graph from different runs (review M1): graph-only skips ingest, so S3 Vectors hold an earlier run's vectors while Neptune holds A's. rag-engineer and storage-engineer, then the human.
- The 17-table graph-only evidence: rag-engineer.
- The session-2 runbook and the exact approval-record fields: deploy-engineer.
- Neptune must exist at `db.r6g.xlarge` (`graph-load.tfvars`, per the review) before this runs; the apply belongs to infra-engineer and deploy-engineer.
