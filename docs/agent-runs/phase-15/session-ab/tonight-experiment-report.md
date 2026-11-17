# Tonight runbook PENDING: experiment-engineer report (received 2026-11-17; summary as received)

Edits only; `bash -n`, Python check and rehearsal blocked by the guard; nothing run.

Q1 Haiku smoke: not as-is (`--sequence none` needs `--levels`; `--limit-queries` only with `rotate-modes`; image holds only `load_client.py`). Smoke args, no wrapper: `--sequence rotate-modes --modes graph --limit-queries 4 --clients 1 --mode-field retrievalMode --think-min 1 --think-max 2 --timeout 60 --max-clients 1 --max-requests 4 --max-minutes 5 --echo` (default `builtin:windows`, seeded order). One `aws ecs run-task --launch-type FARGATE --count 1 --started-by sc-smoke-haiku` with `containerOverrides`, written in `load-tests/query-load/README.md` "Smoke". Pass: four `LOADCLIENT_RAW` lines and `LOAD_CLIENT_DONE requests=4 stop_reason=none`; check `status` 200, `evidence_items`, `abstained`. UNVERIFIED: Haiku called in graph mode; retries add model calls.

Q2 E: `experiments/e-retrieval-modes/run.sh` now reads `SCHOLARCHAIN_E_MODES` (default `graph,vector,fused`) and `SCHOLARCHAIN_E_LIMIT_QUERIES` (1-351, default 351); graph-only = 351 requests; labeled mixed smoke = `LIMIT_QUERIES=12` all modes = 36 requests, separate invocation and approval record. Non-default runs write `run_scope=subset-smoke`, `modes`, `limit_queries`, `planned_requests` to the run ledger (environment not in the approval digest; script digest changes). Manifest unchanged (MAX_TASKS 1500). Fused gap note: `evidence.uncertainty` (`Retrieval.scala:263`, `Workflow.scala:591`, `EvidenceBuilder.build`); audit `semantic` "degraded: N index read(s) failed"; only on a real vector read failure; vector mode carries "Graph evidence was not retrieved..."; no service log key. `load_client.py` raw lines now include `uncertainty_chars` and `vector_gap_note`.

Files: `load-tests/query-load/load_client.py`, `load-tests/query-load/README.md`, `experiments/e-retrieval-modes/run.sh`.
test-engineer to add: `rehearse.py` case for `--modes graph --limit-queries 4` and the two new fields; `run.sh` E_MODES/limit validation and count; confirm the existing rehearsal still passes.
Risks: `rehearse.py` may assert an exact key set; load-client image rebuild needed (E.14); `run.sh` digest changes; `evidence.uncertainty` wire key unseen.

Orchestrator note: three more uncommitted paths outside `docs/`; both runbooks' allowlists must add them.
