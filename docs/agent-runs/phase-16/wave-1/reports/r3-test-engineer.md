# Repair R3 report (test-engineer), received 2026-11-18, archived as received

Status: COMPLETE for the edits. Nothing was executed (no sbt, bash or Python, as ordered), so none of the new or updated cases has run.

## Files modified
- `tests/scripts/run.sh` is the only file touched.
- `tests/scripts/p16-wo7.sh` is unchanged. R2 cases 1 to 4 need the tripwire stubs, the default state directory and the approval-record builders, which only `run.sh` has.

## 1. Cases added
In the smoke section of `run.sh`, directly after `5.smoke.read-secret-arn-us-east-1`, using a record valid for a run with the question, so only the missing question can cause the refusal.

**R2 cases 1 to 3.** The 8 cases `5.smoke.question-{omit,empty}.{real,dry,digest,order}-refused-before-any-work` each assert:
- exit non-zero, an `EVIDENCE:` line, and stderr contains `--question is required` and `no default`;
- no stub (`aws`, `curl`, `sts`) was called;
- none of these on stdout: `TARGET_ACCOUNT=`, `IDENTITY_CHECK=`, `IDENTITY_OK`, `identity-check`, `WOULD_RUN`, `APPROVAL_CONSUMED`, `SCRIPT_DIGEST=`, `ARGS_DIGEST=`, `ARGS_CANONICAL=`, or a `STEP[n]` line for approval-record, identity-check, health, readiness or bounded-query;
- the real run also leaves the marker directory empty;
- `order` is a dry run with a non-existent approval record, proving the question refusal comes before `approval record is missing`.

**R2 case 4 and extras:**
- `5.smoke.question-200-chars-accepted-by-validate-params`: `--print-digest` with a 200-character question, exit 0, an `ARGS_DIGEST`, no 200-character refusal, digest different from the base one.
- The existing `5.smoke.question-201-chars` is kept.
- `4.approval.smoke.question-given-dry-run-passes-validate-params-and-record`: positive control.
- `5.smoke.question-changed-after-approval-refused-by-args-digest`: a record approved for one question refuses another with `ARGS_DIGEST does not match`.
- `2.print-digest.smoke.question-is-recorded-as-sha256-and-binds-the-digest`: `;question=<sha256 of the question>;` in `ARGS_CANONICAL`, no question text.

## 2. Cases updated
- One fixture change: `build()` for the `smoke` key now appends `--question "$SM_QUESTION"`, controlled by `SM_Q_MODE` (`given` default; `omit`, `empty` for the new cases). `SM_QUESTION` is the R2 example. Because `prep_key` also calls `build`, recorded `ARGS_DIGEST` values include the question and stay consistent.
- Expectations unchanged for: `5.state-dir.inside-out-dir`; `5.smoke.out-dir-{inside,doubled-slash,symlink,case-changed,relative,dot-dot}`; `5.smoke.{url-with-path,secret-other-account,secret-us-east-1,bedrock-maybe,question-201-chars}`; `3.dry-run.smoke.new-steps-follow-deployed-revision`; `2.print-digest.smoke.read-secret-arn-only-when-given`; `4.approval.smoke.read-secret-arn-dry-run`; the four `5.smoke.read-secret-arn-*`; `13.smoke.real-run-region-us-east-1-refused`; `13.smoke-defaults-agree-with-terraform-defaults`; `3.dry-run.smoke`, `4.approval.smoke.*`, and the smoke rows of the `KEYS` loops (`2.` to `6.`).
- `5.smoke.question-201-chars`: the appended `--question` is parsed after the base one; the script takes the last `--question`, so it still tests the limit.
- Assertions changed because they encoded old behavior: none found.
- WO-6 stub bodies in `run.sh`: none found (`make_stub` makes `curl` and `aws` exit 1, so `run.sh` never reaches the bounded-query step). The WO-6 body cases are in `p16-wo7.sh`.

## Not located or not verified
- `run.sh` lines 1 to 2550 read in full; lines 2550 to 13400 sampled only (grep refused). A static check elsewhere on `smoke-test.sh` or README wording, or a direct `SC smoke-test` call outside `build smoke`, would be missed. No `--expected-image-tag` cases seen in the part read.
- Assumes `TARGET_ACCOUNT=` and `IDENTITY_CHECK=` are printed only after the new check, and `ARGS_CANONICAL=` only by `--print-digest`.
- No paragraph added to `tests/scripts/README.md`.

## 3. Commands for the human
Ran by: human, or Claude in chat per P16-5. No AWS.

```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; bash tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-r3-run.log | tail -n 60; echo "exit: $?"
```
```
cd /Users/winnguyen/ScholarChain && set -o pipefail; bash tests/scripts/p16-wo7.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-r3-wo7.log | tail -n 60; echo "exit: $?"
```

## Next gate
The human runs both commands; then the batched code-reviewer round.
