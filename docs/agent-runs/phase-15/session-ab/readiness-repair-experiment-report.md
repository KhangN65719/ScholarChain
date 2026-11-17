# Readiness repair B2: experiment-engineer report (received 2026-11-17; summary as received)

Authored, not executed. Files: `experiments/pipeline-inputs/s-graph-only-reload.template.json` (new), `experiments/pipeline-inputs/README.md` (new).
- Template: mode `loader-only`, `run_id` `run-v1-b76e2d37-2ac1-40d4-923b-9418d5ff72c7` (A's real slug, matches `p15-ab-a.json`), `loader_command` `["scholarchain.rag.AwsIngestMain", <run_id>, "--graph-only"]` (class and order from S run 11's `s-graph-only-2.json`). README gives the fill rule if A is re-run.
- Proposed parameters (human approves): `--size large` (loader cap 5400 s; large cap 150 min, `scripts/README.md:505`); `--max-minutes 100`; `--hourly-burn-usd 0.97` (`COST_ESTIMATE.md:102`: loader 0.1165 + 9 endpoints 0.135 + Neptune db.r6g.xlarge 0.70 = 0.9515; r6g rate `:92` unverified); `--spend-ceiling-usd 1.70`; `--cost-ceiling-usd 20`; `--stop-on first-failure,dlq-nonempty`. Neptune creation wait and per-request charges outside the ceiling; session 2 starts only if billed <= 9.5 USD (`COST_ESTIMATE.md:135`).
- `--tolerate-load-errors`: off (S run 11: 12 jobs, 0 data errors, `results-s-tier.md:52`; run 10 failed on memory, not data). Fallback copy with the flag only under a new record by human decision; no second template committed.
- `check-manifest-json.sh` covers only `manifests/*` (from reading `experiments/README.md`, not run).
- For test-engineer: check whether `tests/scripts/run.sh` enumerates `pipeline-inputs/*.template.json`; add a validator case (`--print-digest` prints `INPUT_MODE=loader-only`, `LOADER_GRAPH_ONLY=yes`; extra `snapshot` key refused); dry run with `--size large --max-minutes 100`.
- Open: no graph-only load of a 17-table export observed (rag-engineer); M1 unaffected.

Orchestrator note: `COST_ESTIMATE.md:135` "billed <= 9.5" is the carry-forward rule; tonight's gate per P15-40 is billed + allowance + both HIGHs <= 14.
