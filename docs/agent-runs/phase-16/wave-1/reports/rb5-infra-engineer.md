# Repair batch 5 (P16-16): infra-engineer report

Author: infra-engineer. Received by the orchestrator on 2026-11-18 and preserved as received.

---

**Status: COMPLETE (edit), UNVERIFIED by execution. Owner: infra-engineer. Work order: P16-16.**

The guard refused `bash -n`, `shellcheck`, `grep` and `wc`, and the Grep tool was not available in this session, so no check ran. Nothing below is executed evidence.

**Diff summary.** Only `scripts/smoke-test.sh`, the `--question` arm of the option loop (now about line 95):

```bash
--question)
  if [[ $# -ge 2 && -z "$2" ]]; then QUESTION=""; shift 2; else need_arg "$@"; QUESTION="$2"; shift 2; fi ;;
```

Cause: `need_arg` (in `scripts/lib.sh`) refuses an empty value with `EVIDENCE: --question needs a value`. Fix: an empty `--question` value is accepted at parse time as an empty string, so the existing required check in `validate-params` refuses it exactly as for an absent flag. `lib.sh` and `need_arg` are untouched, so every other option keeps the generic missing-value handling, and `--question` as the last argument or followed by a `--flag` still goes through `need_arg`.

**Expected stderr (from reading the code, not from a run).** For both `--question ""` and an absent `--question`, one line, rc 1:
`EVIDENCE: --question is required and has no default: pass a question the deployed planner can answer, for example --question "Who are the top 3 researchers by average coauthors per paper between 2015 and 2019?"`
It contains `--question is required` and `no default`. The refusal is at the start of `validate-params`, before `init_common`, `maybe_print_digest`, `dry_banner`, `verify_approval` and `verify_identity`, so no AWS call, approval consumption, digest or dry-run output precedes it.

**Which test proves each item** (from `sm_q_case` in `tests/scripts/run.sh`, lines 1346-1381, read before editing):
- Required-question refusal on an empty value: the four failing cases `5.smoke.question-empty.{real,dry,digest,order}-refused-before-any-work`, each asserting `a_refused "--question is required"` and `a_err "no default"`.
- Refused before any work: the `a_not_out` assertions in those cases (no `TARGET_ACCOUNT=`, `IDENTITY_*`, `WOULD_RUN`, `APPROVAL_CONSUMED`, `SCRIPT_DIGEST=`, `ARGS_DIGEST=`, `ARGS_CANONICAL=`, approval-record, identity-check, health, readiness or bounded-query step lines).
- No approval consumption in real mode: `a_state_empty` in the `real` form.
- Question checked before the record is read: the `order` form uses a missing record and fails if "approval record is missing" appears.
- Absent-flag behavior kept: `5.smoke.question-omit.*` (not in the failure list; they never reach the `--question` arm).
- Other options' generic handling kept: other `5.smoke.*` cases such as `read-secret-arn-*` do not touch this arm.
- A given question still passes: `4.approval.smoke.question-given-dry-run-passes-validate-params-and-record`.

**Not verified.** `bash -n scripts/smoke-test.sh` and `shellcheck` could not run, so syntax and lint are unconfirmed (the edit is a single `if/else/fi` inside a `case` arm, closed with `;;`). The harness could not run; the human should run `bash tests/scripts/run.sh` and confirm the four `question-empty` cases pass with counts otherwise unchanged. The other four reported failures are not infra-engineer's. No live smoke was run.

Next gate: human harness run, then independent review.

Files modified: `/Users/winnguyen/ScholarChain/scripts/smoke-test.sh`
