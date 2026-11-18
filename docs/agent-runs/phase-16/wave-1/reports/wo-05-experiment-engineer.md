# WO-5 report (experiment-engineer), received 2026-11-17, archived as received

**WO-5 report: COMPLETE** (owner: experiment-engineer; work order: Phase 16, Wave 1, WO-5). I ran nothing: no Python, no sbt, no AWS. There is no commit.

**Files changed** (all inside the WO-5 write set; `experiments/evaluation/**` untouched)
- `/Users/winnguyen/ScholarChain/load-tests/query-load/load_client.py`
- `/Users/winnguyen/ScholarChain/load-tests/lib/service_log.py`
- `/Users/winnguyen/ScholarChain/load-tests/query-load/derive_latency.py`
- `/Users/winnguyen/ScholarChain/load-tests/query-load/README.md`
- `/Users/winnguyen/ScholarChain/experiments/d-query-load/README.md`
- `/Users/winnguyen/ScholarChain/experiments/d-query-load/collect.sh` (comment only)
- `/Users/winnguyen/ScholarChain/experiments/e-retrieval-modes/README.md`
- `/Users/winnguyen/ScholarChain/experiments/e-retrieval-modes/run.sh` (comment only)

**Behavior changes**
1. **`abstentionReason` capture (finding 5).** `load_client.py` adds an `abstention_reason` key to every `requests.jsonl` line.
   - It reads the top-level `abstentionReason` of a parsed 200 body.
   - A string matching `^[A-Za-z0-9_.-]{1,64}$` is stored as is.
   - Any other string is stored as `"unparsed"`, so no free text reaches a raw line.
   - The value is `null` when the field is absent or the body was not parsed.
   - The header docstring and `load-tests/query-load/README.md` document the key.
   - `derive_latency.py` does not read it, so the derived tables are unchanged.
2. **PENDING-WO-8 markers.** All seven resolve to the final field `retrievalMode` (`QueryService.scala`), covering the seven locations the work order lists.
   - The `--mode-field` default was already `retrievalMode`.
   - Only comments, help text and README text changed.
   - In `experiments/d-query-load/README.md`, the phrase "I did not read the service source" is also removed.
3. **Bootstrap interval in `derive_latency.py derive`.** Two new options: `--bootstrap-resamples B` (default 0, meaning off; allowed 40 to 10,000) and `--bootstrap-seed` (default 16).
   - **Method:** per group, the ok latencies are resampled with replacement B times, using `random.Random("<seed>:<group>")`. Each resample takes p50 and p95 with the existing nearest-rank rule. The 95% interval is the nearest-rank 2.5th to 97.5th percentile of the B values.
   - **Output when on:** a separate `latency-ci-by-<group>.csv`, plus `bootstrap_*` lines at the end of `SUMMARY.txt`.
   - **Output when off:** no new file and no new line. `latency-by-<group>.csv` and every other output are unchanged.
   - **Validation:** option checks run before the output directory is created, so a bad value leaves nothing behind.
   - **Caveat:** the resample treats requests as independent. Closed-loop requests are correlated, so the interval understates the real uncertainty. This is stated in the docstring and the README.

**Test mapping.** The order has no sbt, and the harness cases are test-engineer's (WO-7).
- `abstention_reason` captured: a `rehearse.py` fake-server response that carries `abstentionReason`.
- Markers gone: a `grep PENDING-WO` over `load-tests` and `experiments`.
- Option off gives identical output: derive the same input with and without the option and compare the bytes.

**Paste-ready rehearsal commands** (human, `!` prefix, zsh-safe). Run the first, then the second.

Command 1: load-client rehearsal. Ran by: ______
```
set -o pipefail
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/rehearse.py 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase16-wo5-loadclient-rehearse.log" | tail -n 60; echo "exit: $?"
```
Expected last line `REHEARSAL pass=45 fail=0`. It needs local port binding.

Command 2: derive with the option off and on, then compare. Ran by: ______
```
set -o pipefail
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/derive_latency.py derive --requests "RUN_DIR/derived-requests/requests.jsonl" --events "RUN_DIR/derived-requests/events.jsonl" --group-by level --out-dir "/Users/winnguyen/scholarchain-data/phase16-ci-off" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase16-wo5-derive-off.log" | tail -n 60; echo "exit: $?"
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/derive_latency.py derive --requests "RUN_DIR/derived-requests/requests.jsonl" --events "RUN_DIR/derived-requests/events.jsonl" --group-by level --bootstrap-resamples 1000 --bootstrap-seed 16 --out-dir "/Users/winnguyen/scholarchain-data/phase16-ci-on" 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase16-wo5-derive-on.log" | tail -n 60; echo "exit: $?"
cmp "/Users/winnguyen/scholarchain-data/phase16-ci-off/latency-by-level.csv" "/Users/winnguyen/scholarchain-data/phase16-ci-on/latency-by-level.csv"; echo "cmp exit: $?"
```
- Replace `RUN_DIR` with the Phase 15 D run directory.
- The two `phase16-ci-*` output directories must not exist beforehand.
- **Expected:** both derives end with `DERIVE_COMPLETE`, `cmp` exits 0, and `phase16-ci-on` holds `latency-ci-by-level.csv` with four rows.
- **Optional seed check:** run the on-derive again into a different directory and `cmp` the two `latency-ci-by-level.csv` files. They should be identical.

**Open items and not verified**
- **Wire location of `abstentionReason` is unconfirmed.** The shell guard blocked `grep` and `find`, and I did not find the `AnswerResponse` file. I assumed a top-level key next to `abstained`. If it sits elsewhere, only the `document.get("abstentionReason")` line in `load_client.py` changes. Check one smoke response.
- **`rehearse.py` is outside my write set.** `/Users/winnguyen/ScholarChain/load-tests/query-load/rehearse.py` may assert the exact key set of a request line. If it does, the new key fails it, and that file then needs an owner decision.
- **`run.sh` digest changed.** The comment-only edit to `experiments/e-retrieval-modes/run.sh` changes the script digest. Any E run needs a new approval record.
- **Bootstrap is unmeasured.** Runtime on large groups (about 12k ok requests at 1,000 resamples) is untimed, and the interval values were not checked against a hand calculation.

**Next gate:** independent review, then the human runs the two commands above. Nothing was verified by execution.

## Orchestrator note (not part of the report)
- The first delivery was replaced by the agent's reply to the Stop hook; this is the second delivery, received in full.
- `rehearse.py` is under `load-tests/**`, which `docs/agents.md` assigns to experiment-engineer; the WO-5 write set only narrowed it. If Command 1 fails on the new key, the repair goes to experiment-engineer with the write set widened to `rehearse.py`.
- Wire location: the Phase 16 survey found `abstained` and `abstentionReason` added at the top level of the HTTP response (`QueryService.scala:443-453`). To be confirmed in review.
