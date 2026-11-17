# Phase 15 prompt

## Scope note (human instruction and orchestrator reading; not part of the course text)

Phase 14 is accepted at 4927595 (ratification.md). Goal of this phase: cover every course requirement for Phase 15 and its gate, sized to the remaining budget. Run all six experiments A to F, each at the smallest size that still produces the required evidence.

Hard requirements (course gate, baseline submission and minimum acceptance criteria):
- At least one measured scale-out AND one scale-in event, with synchronized timestamps (minimum acceptance criterion 12). Elasticity evidence per the course "Required scaling rules" table.
- The deployed runtime services answer a graph plus vector RAG query on AWS with evidence IDs (minimum acceptance criteria 8 and 9). Phase 14 did not show this; it is an open item from ratification.md and must be shown before D and E are run.
- Every experiment: committed manifest, immutable raw metrics, plots and tables reproducible from scripts, commit SHA, dataset snapshot, configuration, limits, start and end time, Region and run IDs recorded. Failed runs are kept and reported. No tuning during a run.
- Produce raw data, plots, a results summary and a limitations section.
- Manifests: the course says "committed YAML or JSON manifest"; the current manifests are flat KEY=VALUE (open item 1 of experiments/README.md). Propose how to meet the course wording (e.g. a committed JSON form that run-experiment.sh reads or is checked against) and route it to the owner.
- Rubric "Elasticity, observability, and resilience" asks for dashboards: a CloudWatch dashboard (in Terraform) showing the scaling metrics on one synchronized time axis, plus the evidence-archiving script output for each run.
- Evidence for every row of the course "Required scaling rules" table that applies to the deployed design; any row not shown is listed in limitations with the reason.

Sizing (budget, human decisions to confirm in the plan):
- Spend so far: 3.94 USD billed (ratification.md; the human re-reads Billing before the first apply). AWS spend limit 20 USD, human stop near 15 USD. Every run states its projected cost before approval.
- Experiment A: reuse the Phase 14 S run 6 measurements (results-s-tier.md) if the course fields can be filled from its records; otherwise one bounded S re-run. Say which and why.
- Experiment B: the course allows "a larger manifest OR a higher partition count". Prefer the cheapest option that triggers a real EMR managed-scaling scale-out and scale-in (e.g. S data, higher partition count, bounded max). Check the vCPU quota (open item 3 of experiments/README.md) read-only.
- Experiment C: a small controlled chunk count. First verify whether Terraform defines the embedding worker, SQS queue and dead-letter queue and the scaling policy (open item 4); if not, route the gap to the owner.
- Experiment D: four concurrency levels adjusted for budget (course allows this); levels must be high enough to cause an ECS scale-out and a scale-in afterward. Tier caps (D12) are a human decision; propose values with reasons.
- Experiment E: graph-only, S3-Vectors-only and fused (no OpenSearch).
- Experiment F: one safe failure (worker termination, transient downstream failure or poison message) showing retry, dead-letter, idempotency and recovery.
- Group experiments into as few AWS sessions as possible (e.g. one session for the services: query smoke, D, E, F and C; one EMR session for B). Same-day teardown with session-down.tfvars after every session.
- Before any session-up: set `neptune_instance_class` in experiment-size-s.tfvars back to db.t4g.medium, or move db.r6g.xlarge to a graph-load-only overlay (Phase 14 cost trap). Fix the stale "5,000 works" line.
- Deadline: the project is due 2026-11-25; Phases 16 and 17 follow. Plan Phase 15 to finish by about 2026-11-20.

Speed without lowering quality:
- Dispatch work orders in parallel when their write sets do not overlap (scripts, Terraform, experiments/, load-tests/ and docs owners); still never two sbt at once.
- Before the first services session-up, rehearse locally: docker build of every service image, run them against fixtures, run smoke-test.sh, and a read-only check of the task definitions' env vars, IAM actions and config keys against what the code reads. Phase 14 needed 11 runs because such gaps were found only on AWS; find them locally first.
- Prepare every `!` command and approval record for a session before it starts, so a session runs start to teardown without waiting on new code.

Unchanged constraints from Phase 14:
- Region us-east-2 only. Never suggest "Activate advanced features" or changing the spend limit.
- Agents never run terraform plan/apply/destroy, deploy, or any AWS-changing command. Every plan, apply, deploy, run and teardown is a paste-ready `!` command for the human through the Phase 13 scripts and approval records, with AWS_PROFILE=scholarchain, `set -o pipefail`, logs tee'd to /Users/winnguyen/scholarchain-data/logs/, ending in `| tail -n 60; echo "exit: $?"`. zsh: no unbraced argument variables.
- Experiments do not modify production algorithms (course rule). New entry-point scripts and load generators go under experiments/ and load-tests/ per experiments/README.md.
- Route work to owners per docs/agents.md. No inline fixes. No edits to CLAUDE.md, docs/agents.md or `.claude/**`. Agents run `sbt quick` during work and one `sbt check` before review; never two sbt at once. Read-only git. No commit. Do not weaken tests.
- End with a fresh read-only code-reviewer (Opus) review, at most 2 batched repair rounds, the human's `sbt clean check` and the script harness, then stop for acceptance with everything torn down except storage.

## Course text, verbatim (Phase 15 section of /Users/winnguyen/scholarchain-data/course-README.md)

## Phase 15: run the data pipeline and elasticity experiments

Purpose:

Demonstrate that the system processes real data and changes resource levels in response to load.

Why:

An architecture diagram with autoscaling arrows proves only that the student knows how to draw arrows. The project must show measured scaling events, throughput, latency, backlog, and cost behavior.

Use this work order:

```text
Use the experiment-engineer agent to execute approved ScholarChain data and elasticity experiments without modifying production algorithms.

Experiment A: Hadoop and Spark baseline
- Run the bounded corpus at minimum EMR capacity.
- Record input records, output records, task counts, duration, shuffle, failures, and estimated cost.

Experiment B: Hadoop and Spark burst
- Run a larger manifest or higher partition count.
- Observe EMR managed scaling or the approved instance-fleet response.
- Record requested, provisioned, and released capacity over time.

Experiment C: embedding backlog
- Enqueue a controlled number of text chunks.
- Observe SQS queue depth, embedding-worker task count, throughput, retry count, and drain time.

Experiment D: query load
- Run at least four concurrency levels such as 1, 10, 50, and 100 concurrent clients, adjusted for budget.
- Measure p50, p95, p99 latency, throughput, error rate, ECS task count, CPU, memory, Neptune latency, DynamoDB throttles, vector-query latency, and LLM latency.

Experiment E: retrieval modes
- Compare graph-only, S3-Vectors-only, and fused retrieval.
- If OpenSearch is implemented, compare the hot or hybrid tier as a fourth mode.

Experiment F: failure and recovery
- Trigger one safe failure such as a worker termination, transient downstream failure, or poison message.
- Show retry, dead-letter, idempotency, and recovery behavior.

Rules:
- Every experiment has a committed YAML or JSON manifest.
- Raw metrics are immutable after collection.
- Derived plots and tables are reproducible from scripts.
- Record commit SHA, dataset snapshot, configuration, resource limits, start and end time, Region, and run IDs.
- Never silently discard failed runs.
- Do not tune the implementation during a run and report the result as one experiment.

Produce raw data, plots, a results summary, and a limitations section.
```

Required scaling rules include:

| Component | Required rule or policy | Required evidence |
|---|---|---|
| EMR | Managed scaling or justified fleet policy with bounded minimum and maximum | Node or capacity units over time, pending work, duration |
| Embedding workers | ECS target tracking or step scaling on SQS backlog per task | Queue depth, desired and running tasks, drain time |
| Graph retriever | ECS target tracking on CPU or request count with latency alarm | Task count, CPU, p95 latency |
| Vector retriever | ECS target tracking on CPU or request count | Task count, query latency, errors |
| Rank and synthesis services | Queue depth, CPU, or request-based scaling with bounded maximum | Task count, queue, p95 latency, LLM latency |
| DynamoDB | On-demand or autoscaled provisioned capacity | Consumed capacity and throttles |
| Step Functions and SQS | Retry and dead-letter policy rather than manual polling loops | Execution state, retries, dead-letter count |

Gate:

- At least one scale-out and one scale-in event are shown.
- Metrics use synchronized timestamps.
- Raw experiment data is preserved.
- Costs and limits are stated.
- Failures are reported, not edited out of existence.

