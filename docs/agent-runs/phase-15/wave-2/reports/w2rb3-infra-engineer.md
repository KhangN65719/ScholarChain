# Wave 2 repair batch 3, infra-engineer (received 2026-11-17; author: infra-engineer subagent; summary as received)

Status: COMPLETE, authored only (no terraform, sbt, AWS, git).
- `infra/terraform/SESSION2.md`: :98 slug and `sourceRunId` independent, normally differ (cites `lib-emr.sh:372-379`, `Inputs.scala:113-117`), read each from its own source; :109 run 6 slug `run-v1-192de0fd-...` vs `sourceRunId` `run:v1:9f6e6fb0-2ec3-4148-85fc-f5ba7c56ad05`; :111 UNVERIFIED removed, cites `validate_run_id` and `run-s-pipeline.sh:580`; `sourceRunId` compared with `graph_run_id`.
- `modules/runtime/main.tf`: new precondition after :525 for `SCHOLARCHAIN_EXPORT_RUN_ID` (same form as graph); comment :513-515.
- `environments/course/services-smoke.tfvars`: :5-8 exactly 1 task, at most 4 Haiku queries (P15-27); `query_max_tasks = 1` added (runtime validation 1-6, >= `query_min_tasks` 1).
- `environments/course/services-session.tfvars:10`: wording "1 task, at most 4 queries".
Unverified: pass-through of `query_max_tasks` in `environments/course/main.tf` (not read); A3 drops the smoke file so max returns to 4.
