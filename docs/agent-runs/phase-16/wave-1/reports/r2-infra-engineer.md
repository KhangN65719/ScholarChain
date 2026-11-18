# Repair R2 report (infra-engineer), received 2026-11-18, archived as received

**Status: COMPLETE for the edit. No check was run, so the gate is pending the human.**

Owner: infra-engineer. Work order: Phase 16 Wave 1 repair R2, decision P16-7 (OD-1 option b). No sbt, AWS, terraform or git write was used.

**Files modified**
- `scripts/smoke-test.sh`
- `scripts/README.md`

**Script change**
- `QUESTION` now defaults to the empty string.
- A new check sits right after `step validate-params` and before `init_common`, so it fires before any network, AWS or approval-record work: `[[ -n "$QUESTION" ]] || die "--question is required and has no default: ..."`.
- The message names `--question` and includes the STUB1 example.
- It applies to real runs, `--dry-run` and `--print-digest`, since all of them pass through `validate-params`.
- An empty `--question ""` is refused by the same check.
- The 200-character limit and the control-character check are unchanged.
- The header usage line now shows `--question "text"` as required, and a new `--question` entry in the header documents it.
- The WO-6 answer checks are untouched.

**README change**
- The "Consequences" text, which said the default question is expected to fail, is replaced by the P16-7 rule.
- It shows the example "Who are the top 3 researchers by average coauthors per paper between 2015 and 2019?" and says it was answered in STUB1 with 4 evidence items (`docs/releases/phase-15/smoke-tn.md`).
- The `smoke-test.sh` row in the Scripts table now says `--question` is required with no default, and its "Refuses" column lists a missing or empty `--question`.

**Digest note**
- `smoke-test.sh` `SCRIPT_DIGEST` changes again; any earlier `smoke-test` approval record is invalid.
- `ARGS_DIGEST` is unchanged for an invocation that passes the same question.
- Only `smoke-test.sh` is affected; `lib.sh` was not edited.
- Wording flaw: the README sentence "The earlier WO-6 edit changes `smoke-test.sh`, so its digest changes..." is slightly off in tense.

**Not run, and gaps**
- No `bash -n`, shellcheck or harness run. The long `die` message with escaped quotes is the one thing shellcheck should confirm.
- Check command (human, or Claude in chat per P16-5): `cd /Users/winnguyen/ScholarChain && set -o pipefail; bash scripts/check.sh --skip-sbt --skip-terraform 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-wo6-r2-check.log | tail -n 60; echo "exit: $?"`. Expect `CHECK_RESULT=PASS_WITH_SKIPS`.
- Could not scan `tests/scripts/run.sh` for cases that call `smoke-test.sh` (grep refused). Every harness invocation that omits `--question` will now be refused in `validate-params`.

**Harness cases for test-engineer** (`tests/scripts/run.sh`)
1. Add: a real run without `--question`, other flags valid, exits 1 and prints `EVIDENCE:` text naming `--question`; no stub `aws`, `curl` or `sts` reached; no approval marker consumed.
2. Add: the same for `--dry-run` (no `DRY_RUN` steps past `validate-params`) and `--print-digest` (no `SCRIPT_DIGEST` line).
3. Add: `--question ""` is refused with the same message.
4. Add: a 201-character `--question` is still refused by the 200-character check; a valid question passes `validate-params`.
5. Update: every existing `smoke-test.sh` case that reaches validation, the approval record, the dry run or the real steps without `--question` must now pass one (including `5.smoke.out-dir-*`, dry-run, `--print-digest`, approval-record, `--bedrock`, `--expected-image-tag` cases, and refusal cases for other reasons), so each fails for its intended reason.
6. Update: any expectation relying on the old default text, including a recorded `question=<sha256>` in an `ARGS_DIGEST` or approval-record fixture.
7. The WO-6 cases still need their stub bodies updated, as listed in the WO-6 report.

**Next gate:** the human runs the check above, test-engineer updates the harness, then the batched code-reviewer round with the P16-9 repair.
