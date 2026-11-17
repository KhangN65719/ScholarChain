# M-1 report, experiment-engineer (received 2026-11-17; summary of the report as received; author: experiment-engineer subagent)

Status: COMPLETE for authoring; nothing executed. Files: C, D, E, F manifests and their JSON copies (hand-written), `load-tests/README.md` (SUPERSEDED notice linking `load-tests/query-load/README.md`). A and B unchanged.

| Manifest | Changes |
|---|---|
| C | DESCRIPTION tier S 2000 chunks; DATASET_SNAPSHOT `scholarchain-s-20261107-10dd7f34121b`; SIZE_TIER M to S; MAX_TASKS 5000 to 2000; METRIC_NAMES 7 to 10 (+ NotVisible, AgeOfOldestMessage, NumberOfMessagesReceived, MemoryUtilization); MAX_MINUTES 60, MAX_CONCURRENCY 4 unchanged; SPEND 1.00 / BURN 0.70 PROVISIONAL |
| D | ENTRY_POINT `script:experiments/d-query-load/run.sh`; DESCRIPTION 1-8-24-48 x 480 s + 900 s idle; snapshot; S; MAX_MINUTES 90 to 70 (minimum 65); MAX_CONCURRENCY 16 to 48; MAX_TASKS 3500 to 20000 (bound 19,521); METRIC_NAMES 8 to 14 (dropped `ThrottledRequests`, `SuccessfulRequestLatency`); + `AWS/ApplicationELB`; SPEND 1.50 / BURN 0.85 PROVISIONAL (projected 0.99) |
| E | DESCRIPTION 351 x 3; snapshot; S; METRIC_NAMES = D's 14; + ALB and `ECS/ContainerInsights`; MAX_MINUTES 60, MAX_TASKS 1500 (1,053 needed); SPEND 0.80 / BURN 0.62 PROVISIONAL |
| F | DESCRIPTION retry, dead letter, idempotency; snapshot; S; METRIC_NAMES = C's 10; + `AWS/ECS`; MAX_MINUTES 30, MAX_TASKS 200; SPEND 0.50 / BURN 0.62 PROVISIONAL |

All within P15-1 caps. Each header says the cost pair is provisional and no approval record is written until WO-10's session 2 burn replaces it. Projected/ceiling: C 0.70/1.00, D 0.99/1.50, E 0.62/0.80, F 0.31/0.50.

Risks: one more manifest edit, JSON regeneration and new digests after WO-10; a session 2 burn above about 1.0 USD/h makes E, F and possibly C, D fail the projected-spend check (ceilings would need the human's approval); D's 70 minutes leaves 5 minutes slack and assumes the scale-in cooldown fits the 15-minute idle; ACCOUNT_ID and OUTPUT_ROOT stay placeholders.

Orchestrator note: the agent's proposed commands used `$TMPDIR` logs, `PIPESTATUS` and `--cost-ceiling-usd 20`; normalized in `../human-commands.md` section 5. Digests deferred until the cost pair is final.
