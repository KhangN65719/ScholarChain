# RB1-D report (infra-engineer), received 2026-11-18, archived as received

**Status: COMPLETE for the edits. No check was run, so the gate is pending the human.**

Owner: infra-engineer. Work order: Phase 16 Wave 1 repair batch 1, RB1-D (review findings 16 and 17, P16-11). No sbt, AWS, terraform or git write was used.

**Files modified**
- `scripts/smoke-test.sh`
- `scripts/README.md`

**smoke-test.sh**
- The `RESPONSE_HEAD` line is removed. It printed the first 300 bytes of the response body, which includes answer text.
- On a non-200 status, the step now prints `CHECK bounded-query-error error_code=<code|unreadable> trace_id=<id|unreadable>` before it fails. These come from `json_field`, which prints only values matching the identifier pattern.
- `CHECK bounded-query-answer` now carries `query_id=<id|unreadable>` ahead of `abstained`, `evidence_items` and `abstention_reason`.
- No answer text can now reach the console or a log from the bounded-query step.
- The idempotency step still reads `QUERY_ID` itself and fails closed if it is missing.

**README.md**
- The stale UNVERIFIED sentence on the bounded-query row is replaced by source citations (read and matching): `abstained` and `abstentionReason` at `modules/services/src/main/scala/scholarchain/services/QueryService.scala:449-450`; `evidence.items` at `modules/core/src/main/scala/scholarchain/core/Contracts.scala:425-428`; `claims[].evidenceIds` at `Contracts.scala:459-467`; `queryId` at `Contracts.scala:484-489`. The row still says the fields have not been observed against a deployment.
- The duplicated digest sentences are merged into one.
- The stale "Cases to add" list is removed.
- A new "Identifier-only output (P16-11)" paragraph documents the output change.
- The two marker lines that show the `bounded-query-answer` format now include `query_id`.

**Digest note**
- `smoke-test.sh` `SCRIPT_DIGEST` changes again; any earlier `smoke-test` approval record is invalid. `ARGS_DIGEST` unchanged for the same question. `lib.sh` not edited.

**Validation not run, and gaps**
- `bash -n`, grep and shellcheck were refused by the guard; the syntax check is pending.
- Could not grep `scripts/` or `tests/scripts/` for other `RESPONSE_HEAD` references; harness stubs expecting it need updating (test-engineer).
- RB1-B case 2 (distinctive-answer-string body) passes only if nothing else prints the body.

**Paste-ready check** (human, or Claude in chat per P16-5; "ran by" line names who ran it):
```
cd /Users/winnguyen/ScholarChain && set -o pipefail; bash scripts/check.sh --skip-sbt --skip-terraform 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-rb1d-check.log | tail -n 60; echo "exit: $?"
```
Expect `CHECK_RESULT=PASS_WITH_SKIPS`.

**Risks:** an operator who relied on `RESPONSE_HEAD` for debugging loses it; the error code and trace id remain.

**Blocked-on:** none.
