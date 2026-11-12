# Query-load design for Experiment D (design only; no generator exists)

Status: design. The load generator is authored in Phase 15. `load-tests/d-query-load/run.sh` is the planned entry point named in `experiments/manifests/experiment-d-query-load.manifest`; it does not exist, and no placeholder was created. Nothing was run.

## Concurrency levels

Four levels: 1, 4, 8 and 16 concurrent clients. The course text suggests 1, 10, 50 and 100, "adjusted for budget". The provisional tier caps in `scripts/run-experiment.sh` (concurrency 4, 16 and 32 for S, M and L) force the change: 16 is the tier M cap, and even tier L (32) cannot reach 50. This is a cap limit, not a budget limit, and it is an open item for the human. No cap was raised. `MAX_CONCURRENCY=16` in the manifest is the highest level.

## Request budget and duration

`MAX_TASKS=3500` requests for the whole run, counted by the entry point:

| Step | Concurrency | Requests |
|---|---|---|
| Warm-up, not reported | 1 | 100 |
| Level 1 | 1 | 200 |
| Level 2 | 4 | 600 |
| Level 3 | 8 | 1000 |
| Level 4 | 16 | 1600 |
| Total | | 3500 |

Each level has a request budget and at most 10 minutes. Levels run one after another, never together, with a short idle gap so the ECS and DynamoDB metrics separate. The planned run is under about 55 minutes. `MAX_MINUTES=90` is the hard stop: the script sends TERM to the entry point at 90 minutes and KILL 5 seconds later.

The query workload is a fixed list of bounded researcher questions from the dataset snapshot, in a fixed order, chosen before the run. The LLM is the offline stub, so no Bedrock charge arises and the LLM-latency figure is the stub's, labelled so in the results (it does not represent Haiku latency). Real Haiku queries are capped at 40 for the whole project (`COST_ESTIMATE.md`) and are not used here.

## What is measured

- Client side, written to `raw/` by the entry point: per-request start time (UTC, ms), status, latency, and the level. From it, outside the run, p50, p95, p99, throughput and error rate per level, with a fixed percentile method stated in the derivation script.
- CloudWatch, listed by the script into `metrics/` for later pulling over the recorded UTC window: ECS CPU, memory, desired and running task counts, DynamoDB throttles and latency, Neptune request queue. Namespaces `AWS/ECS`, `ECS/ContainerInsights`, `AWS/DynamoDB`, `AWS/Neptune`. Names and namespaces are UNVERIFIED. The script lists metric identities only; pulling series is a later step.
- Vector-query latency and graph (Neptune) query latency come from the service logs or entry-point output, not from a CloudWatch metric the manifest names. Whether the services emit them was not checked.

## Stop conditions

`wall-clock` (90 minutes), `first-failure` (a nonzero entry-point exit ends the run as FAILED, no retry) and `spend-estimate` (nonzero exit after the fact if burn x elapsed time exceeds the 1.50 USD ceiling; it does not stop a run). The entry point must itself honor `SCHOLARCHAIN_MAX_CONCURRENCY` and `SCHOLARCHAIN_MAX_TASKS`; the script cannot enforce them. A level that errors is recorded and reported, and the run is not repeated mid-run or tuned.

## Cost bound

0.85 USD an hour (tier M session 0.62, 2 extra runtime tasks 0.025, 4 load-test client tasks 0.1975, rounded up; all UNVERIFIED rates, from `COST_ESTIMATE.md` and `scripts/README.md`) x 90 minutes = 1.275, under the ceiling of 1.50. The load-test client tasks are not defined in Terraform (the estimate says they are the experiment owner's task); where the generator runs is a Phase 15 decision for the human and infra-engineer.
