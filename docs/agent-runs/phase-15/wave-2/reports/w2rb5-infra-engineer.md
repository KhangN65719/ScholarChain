# Wave 2 repair batch 5, infra-engineer MINOR-1, MINOR-2 of review 3 (received 2026-11-17; author: infra-engineer subagent; summary as received)

Status: COMPLETE; comments and prose only.
- `environments/course/services-session.tfvars:7-14`: A2 course + S + services-session + session2 + services-smoke; A3 course + S + services-session + session2; A4 course + session-down + session-down-services + session2; "Do not commit between A1 and A4".
- `SESSION2.md`: :70 A2 smoke at most 4 queries on 1 task (P15-27); :111 both IDs from the A run of the combined A + B session (P15-26, runbook 2.9); :191 query scalable target `1 1` after A2, `1 4` after A3, worker `1 4`; :215 item 7 UNVERIFIED closed (`lib-emr.sh:372-379`, `run-s-pipeline.sh:85, 580`).
Not checked by the agent: that `:85` calls `validate_run_id`; `:70`/`:191` against `services-smoke.tfvars`. Line numbers shifted.
