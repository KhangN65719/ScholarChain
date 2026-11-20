# WO-B8 report (infra-engineer, read-only), as received 2026-11-19 (condensed by the orchestrator; citations kept)

STATUS: COMPLETE (read-only). No AWS, Terraform or git commands; terraform fmt, validate and plan were skipped.

## 1. What the overlay sets
`services-smoke.tfvars` sets only:
- `answer_model = "bedrock"` (13)
- `bedrock_max_calls = 10` (14)
- `enable_services_stack = true` (19)
- `query_max_tasks = 1` (22)
- `slow_deadline_millis = 60000` (26), within 10000-120000 (`variables.tf:497`), below the ALB idle timeout of 120 s, reaching the task as `SCHOLARCHAIN_SERVICES_SLOW_DEADLINE_MILLIS` (`main.tf:91`). **P16-34 is satisfied with no Terraform change.**

The model is `bedrock_haiku_profile_id` (default Haiku 4.5 profile, `variables.tf:209-213`), which needs `enable_bedrock = true` (S overlay `:17`). Service counts come from `services-session.tfvars` (combined `query`, 1 task, ALB, worker 1); memory is 2048 per `session2.tfvars`.

## 2. The overlay alone is not enough
Neptune was destroyed without a snapshot, so the session needs:
- **A1:** `course` + S + `graph-load` (`db.r6g.xlarge`), then `deploy.sh`.
- **Reload:** a loader-only reload through `run-bounded-pipeline.sh` (approval record, action `pipeline`).
- **A2:** `course` + S + `services-session` + `session2` + `services-smoke` (the writer is updated in place back to t4g).
- **Load-client image:** push it at the new HEAD SHA.
- **Queries:** load-client `ecs run-task --limit-queries 4` (no approval record).
- **A4 teardown:** `session-down` + `session-down-services` + `session2`, with `STATEFUL_CHANGE_ACKNOWLEDGED=yes`.
- **Then** the post-teardown read.

Each plan and deploy needs its own approval record and typed IAM line.

## 3. Staleness and cost
- **Stale:** `session2.tfvars:5` `load_client_image_tag` (Phase 15 SHA) must be rewritten to the session HEAD. The comments in the smoke and session overlays are outdated.
- **Not stale:** the 4096 output limit and prompt 1.2.0 ship in the image.
- **Projected HIGH** (UNVERIFIED rates, Phase 15 timings), about 1.9 to 2.0 USD:
  - xlarge window: 40 min x 0.97 = 0.65
  - services: 60 min x 0.37 = 0.37
  - teardown: 0.09
  - Haiku: 0.72
  - other: about 0.10

  The margin to 2.30 is about 0.3. A 90-minute load would exceed the ceiling.
- **Set:** `--cost-ceiling-usd 2.30` (decimal acceptance unverified), and cap the reload at about 15 minutes.

## 4. Gaps (no Terraform edit needed for the P17-25 scope)
1. **Evidence and explain endpoints cannot be shown live.** The ALB forwards only port 8081; `/v1/evidence` and `/v1/explain` are on 8080 (`SESSION2.md:9-11`). There is no exec (`modules/runtime/main.tf:510`), and the load client only POSTs queries. Making them live needs a second target group and listener (infra) and a client mode (experiment-engineer).
2. **A1 state-shape risk (UNVERIFIED).** The `SESSION2.md:228` A1 command omits `enable_services_stack` and `service_layout`; a literal plan might destroy the stack. Layer `session-down-services` + `session2` onto A1. The first human plan decides; any destroy of a role, ECR repository or log group is a stop.
3. **The load client records only status, evidence count and abstained, never the answer text** (`load_client.py:34-47`). Showing a live Haiku answer needs a client change (experiment-engineer).
4. **`bedrock_max_calls` is at most 10 per process** (`variables.tf:486`). SMOKE2 used 3 calls per query, so about 3 queries fit.

## Risks and manual actions
- **Risk:** SMOKE2 failed live (all queries abstained). The Phase 16 fix is unproven on AWS.
- **Manual (human):** Billing reading; rewrite the image tag; read-only A1 plan; approval records and IAM lines; image push; run every live script.
