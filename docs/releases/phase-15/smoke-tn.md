# Release record: smoke-tn (session 2 "tonight": SMOKE1, SMOKE2, STUB1)

Record author: deploy-engineer (P15-46 step 2, work order P15-46). Records only, written afterwards from logs under `scholarchain-data/logs/` and `docs/agent-runs/phase-15/session-tonight/evidence.md`. No AWS, terraform, docker or script was run to write it. Account masked (`000000000000`). No response bodies, secrets or approval files were read.

Runbook: `docs/releases/phase-15/session-tonight-runbook.md` revision 4, section S.6 (Haiku smoke) and the stub check after S.7. Each smoke is one `ecs run-task` of the load-client image, graph mode, `--limit-queries 4`, `--max-clients 1 --max-requests 4 --max-minutes 5`, against the internal ALB. A smoke starts no deploy action, uses no approval record and has no plan digest. It is a human `run-task` command and its read-only checks.

Runbook pass rule (S.6): four `LOADCLIENT_RAW` lines, `LOAD_CLIENT_DONE requests=4 stop_reason=none`, task exit 0, `status` 200 on each line, and `evidence_items` above 0 (read by hand per line). A cap ending the run, a non-200 line or no evidence means the smoke FAILED and D and E need the human's decision.

| Smoke | Revision | Started (UTC) | Answer model | Result |
|---|---|---|---|---|
| SMOKE1 | `32e401a0` (A2 task definitions) | 2026-11-17T23:21:28Z (first request) | Bedrock Haiku configured (A2 overlay) | FAIL: all 4 abstained with 0 evidence items (question set, finding 2) |
| SMOKE2 | `38421c6f` (after `tn-s2b`) | 2026-11-18T00:03:02Z (first request; `SMOKE_START_LOCAL=18:02`) | Bedrock Haiku | FAIL under the runbook rule: evidence 4, 3, 3, 8, but all 4 abstained after synthesis (finding 3) |
| STUB1 | `38421c6f` (after `tn-s3`) | 2026-11-18T00:14:35Z (first request) | stub | PASS: 4 of 4 answered, not abstained |

## SMOKE1: FAIL (abstained, 0 evidence)

Logs: `phase15-tn-smoke-task.log` (task started), `phase15-tn-smoke-check.log`, `phase15-tn-smoke-raw.txt`, `phase15-tn-smoke-done.txt`, `phase15-tn-smoke-svc.log`. The task ARN is in the first of those and is not copied here (it carries the account id).

- Check script output: `TASK_EXIT=0`, `LOADCLIENT_RAW_LINES=4`, `STATUS_200_LINES=4`, `LOAD_CLIENT_DONE requests=4 stop_reason=none`, `SMOKE_RESULT=PASS`. The script verdict passes on status and counts only. It does not test evidence or abstention (finding 5).
- Raw lines: every line `status: 200`, `abstained: true`, `evidence_items: 0`, `uncertainty_chars: 0`, `bytes: 1140`; client latency 981.8, 40.8, 71.0, 32.1 ms. Request ids `SMOKE1-L0-c0-n0` to `n3`.
- Service log: only `planning` ran (133, 1, 3, 2 ms); `graph-retrieval`, `vector-retrieval`, `fusion`, `synthesis` and `verification` all `skipped`. The service started its read models from run `run:v1:3cfb57b1-abd1-4913-8e53-da477d8d6027` (startup line, 14 objects).
- Verdict: the runbook's `evidence_items > 0` rule, applied by hand, catches the failure that the script verdict missed. Cause (evidence.md finding 2, rag-engineer read-only diagnosis; code lines `Workflow.scala:392-413`, `Planner.scala:123-125, 307-330`): the `builtin:windows` questions ("Which authors collaborated most ...") name no researcher and no rankable metric, so the planner picks `CollaborationMetrics` with `rankMetric=None` and abstains with `researcher-count` before retrieval. The Wave 3 fix is the human's commit `38421c6` (load-client question wording only). I did not read those source lines; they are cited from the evidence file: UNVERIFIED by me.
- No Bedrock call is visible in the extracted service lines (no `synthesis` stage ran). Whether Haiku was called at all in SMOKE1 is therefore not shown: UNVERIFIED.

## SMOKE2: FAIL under the runbook rule (Haiku synthesis rejected)

Logs: `phase15-tn-smoke2-task.log`, `phase15-tn-smoke2-check.log`, `phase15-tn-smoke2-raw.txt`, `phase15-tn-smoke2-done.txt`, `phase15-tn-smoke2-svc.txt`, `phase15-tn-smoke2-bedrock.log`.

- Check log: `TASK_EXIT=0`, `RAW=4 SVC=8`, `LOAD_CLIENT_DONE requests=4 stop_reason=none`. This check prints no `SMOKE_RESULT` line; the verdict below is by the S.6 rule applied by hand.
- Raw lines: `status: 200` on all four; `evidence_items` 4, 3, 3, 8 (above 0); `abstained: true` on all four; `uncertainty_chars` 129, 129, 54, 129; client latency 29,655, 22,852, 21,964 and 8,518 ms; `bytes` 6,892, 5,277, 5,189, 13,385.
- Service stages: planning 309, 1, 3, 1 ms; graph retrieval 298, 16, 7, 7 ms; fusion 4, 0, 0, 0 ms; synthesis 28,334, 22,713, 21,754 and 8,370 ms; verification `skipped` on all four. Retrieval returned evidence; the time is in synthesis.
- Bedrock log: ten `bedrock-call promptId=synthesis` lines, call numbers 1 to 10, all `outcome=ok`, model `us.anthropic.claude-haiku-4-5-20251001-v1:0`, `outputChars` 4,878 to 5,126, `inputChars` 14,338 to 19,998, 7.1 to 10.9 s each. An eleventh attempt logged `outcome=cap-exceeded maxCalls=10`. The call-to-request mapping is consistent with three synthesis calls for each of requests 1 to 3 (calls 1 to 9) and one for request 4 before the cap (call 10; request 4's 8.4 s synthesis equals one call).
- Finding 3 (evidence.md, P15-45): every Bedrock synthesis call returned `outcome=ok`, but the answer was rejected three times per request (the first call plus two repairs), then the request abstained; the fourth request hit the per-process cap `bedrock_max_calls = 10` (P15-27: "4 queries" is not "4 model calls"). The rejection counts are from evidence.md; the logs I read show the calls and the cap, not the rejections themselves.
- Diagnosis (P15-45): UNVERIFIED. Every reply is about 5,000 characters regardless of evidence size, consistent with truncation at `maxOutputTokens` 2000 (`Synthesis.scala:97`), but the stop reason is not logged, so format, citation and truncation causes are not separated. Phase 16 follow-up with rag-engineer: raise the limit, log the stop reason and the structured-call rejection reasons, capture `abstentionReason` (finding 5).
- P15-27 is answered in part: Haiku is called in graph mode on the synthesis path, and repairs add calls. Total calls observed: 10 plus the refused eleventh.
- Disposition: the S.6 rule says D and E need the human's decision after a failed smoke. The session then proceeded with A3 (stub answer model) and D, E, F, C. The human's explicit decision on that point is not recorded in decisions P15-43 to P15-46 or evidence.md, which is a gap: UNVERIFIED. The A3 move to the stub was already in the plan (runbook S.7).

## STUB1: PASS (after A3)

Logs: `phase15-tn-stub1-check.log`, `phase15-tn-stub1-raw.txt`, `phase15-tn-stub1-svc.txt`.

- Check log: `TASK_EXIT=0`, `RAW=4`, `LOAD_CLIENT_DONE requests=4 stop_reason=none`.
- Raw lines: `status: 200` on all four; `abstained: false` on all four; `evidence_items` 4, 3, 3, 8; `uncertainty_chars` 129, 129, 54, 129; client latency 2,006, 128, 165 and 222 ms.
- Service stages ran for every request: planning, graph retrieval, fusion, synthesis and verification (vector retrieval `skipped`, as expected in graph mode). Totals 1,988, 125, 163 and 220 ms.
- Meaning: the stub configuration answers with evidence. It does not test Haiku synthesis. D, E, F2 and C therefore ran on the stub (see `deploy-tn-s3-a3.md`).

## Open items

- Question set and capture fixes: finding 2 is fixed by `38421c6`; finding 5 (the load client does not capture `abstentionReason`; the smoke check should test evidence and abstention) is routed to experiment-engineer and Phase 16 (P15-45).
- Finding 3 is a recorded Phase 15 limitation with the fix in Phase 16 (P15-45).
- No further smoke run is planned. Rollback gate: a smoke changes no infrastructure; nothing to roll back.
