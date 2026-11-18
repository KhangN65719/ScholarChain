# Load client for Experiments D and E (Phase 15, Wave 2, WO-11)

Status: authored, NOT executed. This file supersedes `../README.md` (the Phase 13 design: levels 1, 4, 8, 16 and 3,500 requests, tier M), which is stale after decision P15-1. That file is left as it was because this work order writes new paths only; the orchestrator decides whether to replace it.

`load_client.py` is Python 3, standard library only, no AWS call and no AWS credential. It is meant to run in the VPC as an ECS task (`packaging/load-client/Dockerfile`, WO-9) against the internal load balancer, started by `experiments/d-query-load/run.sh` or `experiments/e-retrieval-modes/run.sh`. The query bearer token comes from the environment variable `SCHOLARCHAIN_QUERY_TOKEN` (the task definition fills it from Secrets Manager) and is written to no output.

## Experiment D schedule and the request arithmetic (P15-1)
Levels 1, 8, 24, 48 clients, 8 minutes each (480 s), then 15 minutes (900 s) with no client so the ECS service can scale in. Total 4 x 480 + 900 = 2,820 s = 47 minutes, under the 90 minute cap (the manifest proposal is 70).

A closed-loop client sends one request, waits for the answer, thinks for a time drawn uniformly from 2.0 to 4.0 s (seeded, so the draws are reproducible), and sends the next. A cycle is at least the think time even when the service answers in 0 ms, so one client sends at most `floor(480 / 2.0) + 1 = 241` requests per level, and the whole schedule at most

`(1 + 8 + 24 + 48) x 241 = 81 x 241 = 19,521` requests, below the 20,000 cap (P15-1).

The client refuses to start if this bound exceeds `--max-requests`, if a level exceeds `--max-clients` (64), or if the schedule is longer than `--max-minutes` (90), and enforces the same three caps while it runs (an exceeded cap stops it, exit 3, `stop_reason` in `summary.json`). The expected count is lower: with a service latency L the rate is `clients / (L + 3.0)`, so for L = 0.5 s the schedule sends about `81 x 480 / 3.5 = 11,100` requests, about 14 requests per second at the 48 client level.

Tradeoff: the 20,000 request cap fixes the minimum mean cycle at `81 x 480 / 20,000 = 1.94` s, so a shorter think time cannot raise the peak load unless the cap is raised, which is the human's decision (P15-1). Whether 48 clients at about 3.5 s per cycle (about 14 requests per second) is enough to push the services past their scaling threshold is UNVERIFIED and depends on WO-10's thresholds; if it is not, the experiment shows no scale-out and says so (P15-11 names B as the other source of scaling evidence).

## Experiment E schedule
`--sequence rotate-modes --modes graph,vector,fused`: every one of the 351 built-in questions is sent once in every mode (1,053 requests, one client, think time 0.2 to 0.4 s), mode order rotated by question. See `../../experiments/e-retrieval-modes/run.sh` for why interleaved.

## Smoke (at most 4 queries, P15-27) and Experiment E subsets
The image holds only `load_client.py`, so a question file would not be in it; the smoke uses `builtin:windows` with `--limit-queries 4` (the first four of the seeded order, the same four every time). `--sequence none` is NOT usable: it needs `--levels`. Use the one-mode sequence below. Hard caps: `--max-clients 1 --max-requests 4 --max-minutes 5`.

One `ecs run-task` (the same call `load-tests/lib/ecs-load-client.sh` makes; placeholders as in `../../experiments/e-retrieval-modes/README.md`):
```
aws ecs run-task --cluster "$SCHOLARCHAIN_LC_CLUSTER" --task-definition "$SCHOLARCHAIN_LC_TASK_DEFINITION" --launch-type FARGATE --count 1 --started-by sc-smoke-haiku --network-configuration "awsvpcConfiguration={subnets=[$SCHOLARCHAIN_LC_SUBNETS],securityGroups=[$SCHOLARCHAIN_LC_SECURITY_GROUPS],assignPublicIp=DISABLED}" --overrides "{\"containerOverrides\":[{\"name\":\"$SCHOLARCHAIN_LC_CONTAINER\",\"command\":[\"--base-url\",\"$SMOKE_BASE_URL\",\"--out-dir\",\"/tmp/loadclient/SMOKE1\",\"--run-tag\",\"SMOKE1\",\"--sequence\",\"rotate-modes\",\"--modes\",\"graph\",\"--limit-queries\",\"4\",\"--clients\",\"1\",\"--mode-field\",\"retrievalMode\",\"--think-min\",\"1\",\"--think-max\",\"2\",\"--timeout\",\"60\",\"--max-clients\",\"1\",\"--max-requests\",\"4\",\"--max-minutes\",\"5\",\"--echo\"]}]}" --region us-east-2 --output json
```
The result is the four `LOADCLIENT_RAW` lines and a `LOAD_CLIENT_DONE requests=4 stop_reason=none` line in the task's CloudWatch log stream (exit code 0; 3 would mean a cap stopped it). Per line check `status` 200, `evidence_items`, `abstained`. UNVERIFIED: that the service uses Haiku in `graph` mode (planner and synthesis are model calls in every mode; retries may add calls per query, so "4 queries" is not "4 model calls"); that the four fixed questions return evidence.

Experiment E subsets: `experiments/e-retrieval-modes/run.sh` reads `SCHOLARCHAIN_E_MODES` and `SCHOLARCHAIN_E_LIMIT_QUERIES` (see its header). New raw field `abstention_reason` (Phase 16): the response's `abstentionReason` code (a string of at most 64 characters of `A-Za-z0-9_.-`; any other string is recorded as `unparsed`, so no free text enters a raw line), `null` when the field is absent or the body was not parsed. Lines from earlier runs lack the key; `derive_latency.py` does not read it. New raw fields `uncertainty_chars` and `vector_gap_note`: `vector_gap_note: true` is the fused or vector gap note (`evidence.uncertainty` holds "Semantic context from index <name> was unavailable (the vector read failed) ..."); it is false on a healthy vector read, so it only appears when the vector read actually fails. A graph-mode response never carries it.

## Questions
`builtin:windows`: 351 ranking questions ("Who are the top 5 researchers by distinct coauthors between 2015 and 2019?"), seeded from 6 stored metric windows (2000-2004, 2005-2009, 2010-2014, 2015-2019, 2020-2029, overall) x 6 rankable metrics x top 2 to 20. They need no author IDs, so they do not depend on the dataset snapshot. Each names a window Spark stores and a metric the rules planner recognises, so it plans a ranked metric lookup (the first set, "Which authors collaborated most ...", named neither and was refused before retrieval: Phase 15 session-2 smoke, all four abstained with 0 evidence). The raw lines record `evidence_items` and `abstained` per response. `--queries FILE` takes one question per line instead.

## Request format
`POST <path>` (default `/v1/query`), JSON `{"question": ..., "<mode field>": "graph|vector|fused"}`, headers `Content-Type`, `Accept`, `X-Request-Id` (the client's own `<run tag>-L<level>-c<client>-n<seq>`, which the service echoes, `Http.scala`) and `Authorization: Bearer <token>`. The retrieval-mode field is final, `retrievalMode` (`QueryService.scala`), and is the `--mode-field` default. D leaves the field out (`--retrieval-mode none`), so D does not depend on it.

## Outputs
See the header of `load_client.py`. In ECS the raw lines are also printed as `LOADCLIENT_RAW <json>` (and `LOADCLIENT_EVENT <json>`) and read back from CloudWatch Logs by the collector, because the container disk is lost with the task. Log retention is 3 days by default: collect promptly.

## Derive (no AWS)
`derive_latency.py extract` turns the collected log events into `requests.jsonl` and `events.jsonl`; `derive_latency.py derive` writes `latency-by-level.csv` (D) or `latency-by-mode.csv` (E), `timeline-30s.csv`, `latency.svg`, and with the service logs `stage-latency-by-<group>.csv` (planning, graph, vector, fusion, synthesis, verification per group; a skipped retriever is counted as skipped, not as 0 ms). Percentiles are nearest rank (stated in the script header). Request errors are never dropped: `error_rate` counts status 0 (no response) and every non-2xx.

Optional bootstrap interval (Phase 16, off by default): `derive --bootstrap-resamples 1000 [--bootstrap-seed 16]` adds `latency-ci-by-<group>.csv` (ok p50 and p95 with a 95% percentile interval per group; seeded from the seed and the group name, so a rerun is byte-identical) and `bootstrap_*` lines in `SUMMARY.txt`. Without the option every output is byte-identical to before. The resample treats requests as independent; a closed-loop run is correlated, so the interval understates the uncertainty. Allowed range 40 to 10,000 resamples.

## Rehearsal (no AWS)
```
set -o pipefail
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/rehearse.py 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-loadclient-rehearse.log" | tail -n 8; echo "exit: $?"
```
Expected last line `REHEARSAL pass=45 fail=0`. It starts a fake HTTP server on 127.0.0.1 (a sandbox without local port binding cannot run it) and checks: the 12-request sequence with rotated modes and exact request IDs, the levels schedule (between 8 and 18 requests for a 1.0 s, 1-and-2-client plan whose bound is 18), the refusals (cap, clients, minutes, token, existing out-dir), the run-time guards, recorded 503 and refused-connection failures, the token not in any output, and exact percentile arithmetic (20 synthetic requests: ok p50 10, p95 19, p99 19, throughput 1.0).
The help text, no AWS, no network:
```
set -o pipefail
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/load_client.py --help 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-loadclient-help.log" | tail -n 20; echo "exit: $?"
```
Expected: the usage text and exit 0 (the build-engineer's image command `docker run --rm <image> --help` shows the same text).
