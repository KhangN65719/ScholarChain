# Report 06: experiment-engineer, draft manifests (as received, condensed)

STATUS: COMPLETE for authoring. Manifest check against the script BLOCKED-ON the human (guard refuses agent bash). Checked only by reading run-experiment.sh and lib.sh.

Files created: experiments/manifests/experiment-{a-baseline,b-burst,c-embedding-backlog,d-query-load,e-retrieval-modes,f-failure-recovery}.manifest; experiments/README.md; load-tests/README.md. None modified.

Same 17 keys in all six. MANIFEST_VERSION=1, ENVIRONMENT=course, REGION=us-east-1, STOP_CONDITIONS=wall-clock,first-failure,spend-estimate. ACCOUNT_ID is the documented example 123456789012 (placeholder, not a real account). OUTPUT_ROOT is a placeholder absolute path outside the repository.

| | A | B | C | D | E | F |
|---|---|---|---|---|---|---|
| SIZE_TIER | S | M | M | M | M | M |
| MAX_MINUTES | 60 | 90 | 60 | 90 | 60 | 30 |
| MAX_CONCURRENCY | 1 | 1 | 4 | 16 | 2 | 2 |
| MAX_TASKS | 100 | 100 | 5000 | 3500 | 1500 | 200 |
| SPEND_CEILING_USD | 0.75 | 3.50 | 1.00 | 1.50 | 0.80 | 0.50 |
| HOURLY_BURN_USD | 0.60 | 2.02 | 0.70 | 0.85 | 0.62 | 0.62 |
| burn x min / 60 | 0.60 | 3.03 | 0.70 | 1.275 | 0.62 | 0.31 |
| ENTRY_POINT (planned, not created) | experiments/a-baseline/run.sh | b-burst | c-embedding-backlog | load-tests/d-query-load | e-retrieval-modes | f-failure-recovery |

Ceilings sum $8.05; projected window spend $6.535. They cover the entry-point window only; the estimate puts S+M at $25.42 HIGH, so no claim that the whole project fits $25. Most burn rates are UNVERIFIED rates from COST_ESTIMATE.md.

Open items for the human:
1. Format: the course says YAML or JSON manifests; the script parses only flat KEY=VALUE, so these follow the script.
2. Experiment D levels: course 1/10/50/100; provisional caps (16 on M, 32 on L) force 1, 4, 8, 16. Even L (32) cannot reach 50. No cap raised.
3. Experiment C: not verified that Terraform defines an embedding worker or SQS queues; embedder choice open (Titan V2 costs tokens; hash embedder cannot use the 256-dim indexes).
4. vCPU quota for B (20 vCPUs) unchecked.
5. Tier caps provisional (D12).
6. Custom metric namespaces for vector/graph-query latency unchecked.

Assumptions: experiment-to-estimate row mapping is the agent's; MAX_TASKS has no defined unit (C chunks, D requests, E 3 modes x 500 queries, A and B tight pipeline-level bound); metric names from memory, UNVERIFIED; Container Insights needed for ECS task counts; D and E use the offline LLM stub.

Risks: dry run needs a clean tree and an approval record (stops at `clean-tree` or `approval-record`; untracked docs/.DS_Store counts); `spend-estimate` only fails the run afterwards, only `wall-clock` stops the entry point; concurrency and task limits are not enforced by the script; A and B can time out at the HIGH hours (O1); placeholders changed later change the sha256 and need a new approval record.

Human commands pending (per manifest): `bash scripts/run-experiment.sh --print-digest ...` and `--dry-run ... --approval-record <path>`; expect `MANIFEST_VALID`, `LIMITS`, `CHECK_SKIPPED entry-point`, `DRY_RUN_COMPLETE`.
