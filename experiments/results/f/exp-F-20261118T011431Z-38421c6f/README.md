# Experiment F, first run (F1): INVALID as a recovery result

Run id `exp-F-20261118T011431Z-38421c6f`. Kept, not deleted (course rule: failed runs are reported). The F result is the rerun `exp-F-20261118T013740Z-38421c6f` (F2, verdict PASS 9 / FAIL 0, decision P15-45). F1 was never collected or derived: there is no `collected/`, no derived table and no verdict for it.

Why it is invalid (decision P15-45, evidence finding 4 in `docs/agent-runs/phase-15/session-tonight/evidence.md`):

- The embedding worker refused the F1 good messages with `reason=ChangedVectorContent`. The earlier ingest had stored text rendered by a different template under the same `summaryId` keys; F sends raw chunk text, so the text hash differed and the worker, by design, will not overwrite an existing vector. The renderer difference is the leading explanation and was not measured from a stored vector (UNVERIFIED).
- The harness status was `COMPLETED` (`status=drained-and-dead-lettered`, exit 0) because the queue drained and the DLQ was non-empty. That status says the harness finished, not that recovery was shown.

What the raw files in this directory show (read directly, no derive):

- `raw/poll.jsonl`: the main queue had 52 messages in flight from 01:15:06Z (tick 3); the dead-letter queue showed 4 visible at 01:17:31Z (tick 12) and 52 visible at 01:17:47Z (tick 13). Ticks 15 and 16 read 4 and 52 (the alternation is unexplained). The worker service stayed at desired 1, running 1 throughout.
- `raw/events.jsonl`: `ENTRY_START` 01:14:32Z, `INJECT_END` 01:14:34Z, `POLL_END` 01:18:37Z, `status=drained-and-dead-lettered`.
- Worker diagnosis log (outside the repository, 25 lines, a sample and not a full tally): `/Users/winnguyen/scholarchain-data/logs/phase15-tn-f-worker-diag.log`. It shows `reason=ChangedVectorContent` at receive counts 1, 2 and 3 for the messages it lists, and the injected poison message `reason=unsupported_version` at receive counts 1, 2 and 3.

What F1 does show: the poison, retry and dead-letter path (the 52 injected messages, which the queue held as 43 visible plus 9 in flight at the first poll, all ended in the DLQ at the 52 reading; the sampled worker lines show receive counts 1, 2 and 3 before the move, queue `maxReceiveCount` 3) and the no-overwrite guarantee. What it does not show: recovery of good messages, or idempotency of the duplicate.

Not derived. The `RAW-SHA256SUMS` file in this directory hashes the raw files in the immutable run directory, not the copies here; `SHA256SUMS` hashes the copies.
