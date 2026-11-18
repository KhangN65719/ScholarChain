# Repair R1 report (experiment-engineer), received 2026-11-18, archived as received

**Status: COMPLETE, with one deviation from the order.** Owner: experiment-engineer. Work order: Phase 16 Wave 1 repair R1 (P16-9). I did not resolve the three markers to `retrievalMode`, because that would put a false statement in each file. No sbt, AWS, Python or commit. Nothing was executed.

**Deviation.** All three markers are `PENDING-WO-10`, not `PENDING-WO-8`. Each one marks a deployment-defined name: the load-client container name, the awslogs stream prefix, the run-ledger table name. None of them is the retrieval-mode field. `retrievalMode` applies only to the seven WO-8 locations, which WO-5 already fixed. I did not know the final deployed names, and the shell guard blocked my grep, so I could not look them up. I removed the marker text and pointed each comment at the deployed definition instead.

If the actual names are wanted, give me the WO-10 task-definition container name, the stream prefix and the ledger table name; the edit is then a one-line change per file.

**Files changed (comment lines only, no code):**

1. `load-tests/lib/ecs-load-client.sh`, line 14
   - Before: `#   SCHOLARCHAIN_LC_CONTAINER        container name inside that task definition (PENDING-WO-10)`
   - After: `#   SCHOLARCHAIN_LC_CONTAINER        container name inside that task definition (read it from the deployed definition)`
2. `experiments/d-query-load/collect.sh`, line 18
   - Before: `...awslogs-stream-prefix/container-name of that task definition (PENDING-WO-10); the stream is <prefix>/<task id>`
   - After: the same text with `(read it from the deployed definition)` in place of `(PENDING-WO-10)`.
3. `experiments/f-failure-recovery/collect.sh`, lines 11-12
   - Before: `duplicated message is read with get-item; PENDING-WO-10 for the` / `name, key layout from WO-7 and UNVERIFIED live)`
   - After: `duplicated message is read with get-item; take the table` / `name from the deployed stack; key layout from WO-7 and UNVERIFIED live)`

**Digest notes.** All three script digests change. `ecs-load-client.sh` is sourced by the D and E entry points; the two `collect.sh` files are run by the human. Any approval record that pins a digest for D, E or F needs a refresh. The WO-5 edits already changed the `d-query-load/collect.sh` and `e-retrieval-modes/run.sh` digests.

**Not verified:**
- No `PENDING-WO` text remaining anywhere: not confirmed (grep blocked). Re-run the WO-7 harness.
- Whether the harness case matches only `PENDING-WO` or also other text.
- That the names are discoverable from the deployed definition and stack, as the new comments say.

**Next gate:** the human re-runs `tests/scripts/p16-wo7.sh`, then the batched code-reviewer pass with the OD-1 change.

## Orchestrator note (not part of the report)
- Deviation accepted by the orchestrator as consistent with P16-9's intent (remove the stale markers; do not change the test): writing `retrievalMode` into a container-name, stream-prefix or table-name comment would be false. Recorded for the human and for review.
