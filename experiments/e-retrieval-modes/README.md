# Experiment E: retrieval modes (Phase 15, Wave 2, WO-11)

Status: authored, NOT executed. No AWS call, no run, no approval record. No OpenSearch exists, so there is no fourth mode.

## Design
The same 351 fixed questions are sent in `graph`, `vector` and `fused` mode: 1,053 requests, one client, think time 0.2 to 0.4 s, stub answer model (P15-4). Order: interleaved by question with the mode order rotated (question i starts at mode i mod 3). Reason: a block per mode would put any warm-up, cache effect or time-of-day drift of the stack entirely into one mode and make the first mode always pay the cold start; interleaving gives the three modes the same questions in the same minutes, and the rotation keeps any mode from always being first for a question. The cost is that a cache filled by one mode's request can help the next mode's request for the same question (for example a shared DynamoDB or embedding cache); that effect is not removable by order, only balanced by the rotation, and is a stated limitation.

`run.sh` starts ONE load-client ECS task (same mechanism, settings and AWS calls as D; additionally `SCHOLARCHAIN_E_BASE_URL`) and writes `window.json`. The retrieval-mode request field name is PENDING-WO-8 (client default `retrievalMode`).

## Evidence
Per mode (`latency-by-mode.csv`): p50, p95, p99, error rate, and how many responses carried zero evidence items or abstained; per stage and mode (`stage-latency-by-mode.csv`, from the service log): graph retrieval, vector retrieval, fusion, synthesis, verification, planning, with skipped stages counted as skipped (a `graph` request has no vector stage). Evidence IDs: the raw lines carry `evidence_items` (the count) for every 200 response; whether fused returns more or different evidence than either alone needs the answers themselves, which the client does not keep (no answer text is stored); the evidence count per mode is the comparison this experiment makes. Cost per query is not measured (stub model, no Bedrock).

## Prerequisites
As D (stack up, smoke test passed, WO-5 `RetrievalMode`, WO-8 request field and stage-timing log lines). Manifest changes: see the report.

## Paste-ready commands (human, `!` prefix, zsh-safe)
0. Rehearsals: the same as D (command 0 of `../d-query-load/README.md`).
1. The run (needs the manifest, approval record and clean tree):
```
set -o pipefail
export SCHOLARCHAIN_E_BASE_URL="REPLACE_WITH_INTERNAL_ALB_URL"
export SCHOLARCHAIN_LC_CLUSTER="REPLACE_WITH_CLUSTER_NAME"
export SCHOLARCHAIN_LC_TASK_DEFINITION="REPLACE_WITH_LOAD_CLIENT_TASK_DEFINITION"
export SCHOLARCHAIN_LC_CONTAINER="REPLACE_WITH_LOAD_CLIENT_CONTAINER_NAME"
export SCHOLARCHAIN_LC_SUBNETS="REPLACE_WITH_SUBNET_IDS_COMMA_SEPARATED"
export SCHOLARCHAIN_LC_SECURITY_GROUPS="REPLACE_WITH_LOAD_CLIENT_SECURITY_GROUP_ID"
AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/scripts/run-experiment.sh --manifest "/Users/winnguyen/scholarchain-data/experiments/REPLACE_WITH_E_MANIFEST_COPY" --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record "/Users/winnguyen/scholarchain-data/approvals/REPLACE_WITH_E_APPROVAL.env" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-e-run.log" | tail -n 60; echo "exit: $?"
```
Expected: about 10 to 30 minutes; `exit: 0` when all 1,053 requests were sent.
2. Collection: the same variables and command as D step 2, with `experiments/e-retrieval-modes/collect.sh` (a wrapper for D's collector) and a new run directory.
3. Derive (no AWS): as D step 3 with `--group-by mode` and the out-dir names `derived-latency-modes`:
```
set -o pipefail
RUN_DIR="/abs/outside/repo/scholarchain-experiments/REPLACE_WITH_RUN_ID"
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/derive_latency.py extract --logs-json "${RUN_DIR}/collected/loadclient-logs.json" --out-dir "${RUN_DIR}/derived-requests" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-e-derive-1.log" | tail -n 8; echo "exit: $?"
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/derive_latency.py derive --requests "${RUN_DIR}/derived-requests/requests.jsonl" --events "${RUN_DIR}/derived-requests/events.jsonl" --service-logs "${RUN_DIR}/collected/service-logs.json" --group-by mode --out-dir "${RUN_DIR}/derived-latency-modes" --title "Experiment E: latency by retrieval mode" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-w2-e-derive-2.log" | tail -n 8; echo "exit: $?"
```
Expected: `EXTRACT_COMPLETE requests=1053 events=<m>`; `latency-by-mode.csv` has three rows (`fused`, `graph`, `vector`) of 351 requests each.

## Metric names
As D (the collector and template are shared); UNVERIFIED this turn.

## Limitations
- One client at low load measures retrieval cost, not behaviour under load (that is D).
- Cross-mode cache effects (see Design). A single run is one experiment; a repeat is a different run.
- The stub model makes synthesis time and answer content identical in nature across modes; answer quality is not measured.
