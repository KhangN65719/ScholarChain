# WO-11 report, experiment-engineer (received 2026-11-17; summary of the report as received; author: experiment-engineer subagent)

Status: COMPLETE for authoring; nothing executed (guard blocks python3 and bash). Expected counts derived by reading. WO-7 schema v1, `WORKER_MSG` parser and the F design built in. Only new paths touched.

## Files
`load-tests/query-load/{load_client.py,derive_latency.py,rehearse.py,README.md}`; `load-tests/lib/{aws_poll.py,metrics_derive.py,queue_derive.py,svgplot.py,worker_log.py,service_log.py,collect-common.sh,entry-common.sh,ecs-load-client.sh,rehearse_shell.py}`; `experiments/c-embedding-backlog/{run.sh,collect.sh,enqueue.py,message_schema.py,queries.template.json,rehearse.py,README.md}`; `experiments/d-query-load/{run.sh,collect.sh,queries.template.json,README.md}`; `experiments/e-retrieval-modes/{run.sh,collect.sh,README.md}`; `experiments/f-failure-recovery/{run.sh,collect.sh,derive_f.py,rehearse.py,README.md}`.

## Design
- D and E start one load-client ECS task with `ecs run-task` (the internal ALB is not reachable from the laptop); `stop-task` only at the limit or on TERM for the task it started; raw `LOADCLIENT_RAW` lines go to stdout and are read back from CloudWatch Logs.
- D: 1, 8, 24, 48 clients x 480 s, then 900 s idle; think time uniform 2.0-4.0 s seeded; bound 81 x 241 = 19,521 requests (< 20,000); refuses to start if a bound would be exceeded and enforces all three at run time (exit 3); expected about 11,100 requests at 0.5 s latency.
- E: 351 year-window questions x 3 modes = 1,053 requests, one client, interleaved per question with rotated mode order; shared-cache effect is a limitation.
- C: `enqueue.py` sends 2,000 schema v1 messages, 10 per `send-message-batch`, all validated first, chunk sha256 checked, a failed batch stops with no retry, no text logged; read-only poll every 30 s until drained.
- F: 50 normal, 1 poison (schemaVersion 2), 1 duplicate of message 24; `derive_f.py` verdict table, missing data gives UNKNOWN never PASS.

## Rehearsals (expected, not run)
`load-tests/query-load/rehearse.py` 42 (binds a local port); `experiments/c-embedding-backlog/rehearse.py` 35; `experiments/f-failure-recovery/rehearse.py` 8; `load-tests/lib/rehearse_shell.py` 24 (also `bash -n` on 11 scripts).

## Metric names
All UNVERIFIED this turn (documentation tools unavailable); each collector saves `cloudwatch list-metrics`. SQS, Container Insights `DesiredTaskCount`/`RunningTaskCount`, `AWS/ECS` CPU/memory, ALB `TargetResponseTime` (percentile statistics unverified), `RequestCount`, 5XX, `HealthyHostCount`, DynamoDB throttles and consumed capacity, Neptune `CPUUtilization`, `MainRequestQueuePendingRequests` (dimension unverified). Also unverified: the CloudWatch Logs filter pattern, `s3vectors get-vectors` CLI name.

## Manifest changes needed (after the Wave 1 commit)
- C: `MAX_TASKS` 5000 to 2000; `SIZE_TIER` S; snapshot `scholarchain-s-20261107-10dd7f34121b`; description and header; add `NumberOfMessagesReceived` and ECS CPU/memory; recompute `HOURLY_BURN_USD` after WO-10.
- D: `ENTRY_POINT=script:experiments/d-query-load/run.sh`; S; `MAX_CONCURRENCY=48`; `MAX_TASKS=20000`; `MAX_MINUTES=70` (needs at least 65); description and header; ALB namespace and names, DynamoDB names; keep `SPEND_CEILING_USD` 1.50 until WO-10.
- E: S; snapshot; description; header 351 x 3 = 1,053; `MAX_TASKS` 1500 OK; `MAX_MINUTES` 60; ALB and Container Insights.
- F: S; snapshot; header; `MAX_TASKS` 200, `MAX_MINUTES` 30 (longer if visibility timeout is 300 s).
- All: `check-manifest-json.sh --generate` afterwards.

## Fix after the human rehearsal (received 2026-11-17; summary as received)
Cause: the rehearsal's refused-connection run used `--base-url http://127.0.0.1:1`; `URL_RE` in `load_client.py` takes a 2-5 digit port, so the client refused the plan (exit 2, `LOAD_CLIENT_REFUSED`, no out-dir) and the rehearsal crashed on the missing file. The client already records real connection failures (`fire()` gives `status 0` and the exception class; `Sink.request` writes it; summary counts status `"0"`). `load_client.py` unchanged. `rehearse.py`: the refused-connection run now targets a released, listener-free port (`closed_port()`); original check kept (2 lines, status 0, non-empty error); added checks: rc 0 and error classes end in `Error` with exactly 2 lines; summary `by_status == {"0": 2}` and `requests_sent == 2`; a one-digit port is refused with exit 2 and no out-dir. Missing or unreadable output files now print `FAIL missing or unreadable output file <path>` and the run continues (helpers `lines`, `jload`, `text`, `csv_rows`). Expected **pass=45** (was 42).

## Open items
1. Nothing executed. 2. Mode field `retrievalMode` (matches WO-8) and stage-timing log keys (orchestrator: WO-8 logs `stagesMs` with names `planning`, `graph-retrieval`, ...; check `service_log.py`). 3. PENDING-WO-10: load-client container name, log prefix, task definition, log groups, worker service, ledger table. 4. ENTRYPOINT runs `load_client.py` (matches WO-9). 5. F rows 0-49 overlap C's file; Phase 14 vectors may exist; check before reading C throughput. 6. Log retention 3 days; collect promptly. 7. Stale `load-tests/README.md` (levels 1/4/8/16, tier M) not edited. 8. Assumes `run-experiment.sh` passes the environment through, and the human role has `ecs:RunTask`/`iam:PassRole`; load-client SG the only one the ALB admits. 9. Single load-client task CPU may saturate at 48 threads. 10. Whether 48 clients (about 14 req/s) crosses WO-10's scaling threshold is unverified; the 20,000 cap fixes the minimum mean cycle at 1.94 s.
