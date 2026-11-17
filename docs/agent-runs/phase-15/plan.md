# Phase 15 plan (approved 2026-11-16)

Author: orchestrator (main session). Approved by the human on 2026-11-16 with the decisions in `decisions.md` (P15-1 to P15-11). Inputs read: `prompt.md`, `../phase-14/ratification.md`, `../phase-14/results-s-tier.md`, `experiments/README.md`, `infra/terraform/COST_ESTIMATE.md`, plus a read-only survey of Terraform, services, RAG, storage, manifests and scripts (no AWS, no sbt).

## Survey findings (read-only, file citations)
- Services have no AWS entry point; only `LocalRuntime.main` (demo data, offline stub). Task definitions set no `command`, so the container runs the image default `-version` and exits (`Dockerfile:5-6`, `infra/terraform/modules/runtime/main.tf:161-178`).
- No Bedrock answer model; only `DeterministicStubModel` (`modules/rag/.../StubModel.scala`). `SCHOLARCHAIN_BEDROCK` is passed by Terraform but read by no code.
- No retrieval-mode switch; request `mode` is sync or async only (`QueryService.scala:23-25`). Vector retrieval is on or off only through `ports.semantic` (`rag/Workflow.scala:56,126`).
- No ECS autoscaling in any module; `desired_count` validated 0 to 2 (`runtime/variables.tf:80-83`); Container Insights disabled (`runtime/main.tf:104-107`); no ingress (no ALB, Cloud Map off, private subnets).
- SQS `embedding-work` and `run-requests` queues with DLQs exist, `maxReceiveCount` 3 (`workflow/main.tf:297-317`). No worker service, no consumer.
- Step Functions has Catch only, no Retry.
- EMR managed scaling is in the CreateCluster parameters (`emr/main.tf:63-71`), but `elasticity-example.tfvars` gives min = max = 5, so it cannot scale.
- No vCPU quota evidence recorded.
- Tier caps hard-coded in `scripts/run-experiment.sh:87-94`.
- `experiment-size-s.tfvars` still sets `db.r6g.xlarge` (line 57) and says "5,000 works" (line 1).

## Approach
All experiments run on tier S data. Two AWS sessions, same-day `session-down.tfvars` after each. Every AWS step is a paste-ready `!` command for the human through the Phase 13 scripts and approval records. B runs first because it alone can meet the scale-out and scale-in gate; D's ECS scaling is the second source (P15-11).

## Work orders
Wave 1 (2026-11-17): WO-1 infra-engineer (overlays, elasticity, Wait state, D12 caps, quota and Bedrock-access read-only checks); WO-2a hadoop-engineer and WO-2b spark-engineer (partition and reducer settings, default unchanged); WO-3 experiment-engineer (A audit of run 6, A and B entry points, collectors, plot scripts, manifests on S); WO-4 dataset-engineer (S snapshot IDs, 2,000-chunk set for C); WO-12a test-engineer (harness cases for Wave 1).

Wave 2: WO-5 rag-engineer (retrieval mode, configurable Bedrock Haiku adapter, per-stage timings); WO-6 domain-engineer (only if the core query contract needs the mode field); WO-7 storage-engineer (SQS embedding worker, ledger idempotency, DLQ path, poison format, in-VPC adapters); WO-8 service-engineer (AWS runtime main, latency logs, health check); WO-9 build-engineer (image commands, slowSuites); WO-10 infra-engineer (internal ALB, ECS target tracking and backlog step scaling, worker service, load-client task, Container Insights, task commands); WO-11 experiment-engineer (load generator, entry points C to F, collectors, manifests); WO-12b test-engineer (cross-module tests).

Wave 3: code review per wave (P15-9), human commits, deploy-engineer release records, sessions, results (experiment-engineer), docs page (documentation-engineer), final Opus review, at most 2 repair rounds, human `sbt clean check` and harness, acceptance with only storage left.

## Sessions and projected cost (rates mostly UNVERIFIED; HIGH stacks upper bounds)
| Session | Date | Contents | LOW / HIGH USD | Cumulative HIGH from about 4.5 |
|---|---|---|---|---|
| 1 EMR | 11-18 | A re-run only if the audit fails; B on S data, higher partition count, 2 to 4 instances, scale-in in the Wait | 0.9 / 2.0 | 6.5 |
| 2 Services | 11-19 | Neptune xlarge load, apply to t4g.medium, smoke (at most 10 Haiku), D, E, F, C | 2.0 / 4.3 | 10.8 |
| Reserve | | one failed-session repeat | up to 4.3 | 15.1 |

Stop rule (P15-10): billed spend plus the session's HIGH at most 14 USD before each session-up.

### Revision 2026-11-17 (orchestrator, from WO-3)
A verdict is RE-RUN (run 6 holds no shuffle and no MapReduce task counts). Session 1 HIGH becomes about 2.85 USD (A up to 0.75 plus B ceiling 2.10, `wo-03-experiment-engineer.md`), cumulative HIGH about 7.35 before session 2 and about 11.65 after it, still under the 14 USD pre-session rule. B is BLOCKED until WO-1b makes the on-node pipeline multi-node (`work-order-1b.md`).

## D levels and caps
D: 1, 8, 24, 48 clients, 8 min each, then 15 min at zero. Tier S caps: 90 min, 64 clients, 20,000 tasks (P15-1).

## Schedule risk
Wave 2 is large. If it slips, session 2 runs smoke, D, E first; the gate still rests on B.
