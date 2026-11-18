# WO-6 report (infra-engineer), received 2026-11-17, archived as received

**WO-6 report (infra-engineer). Status: COMPLETE for the edit; gate NOT RUN (guard refused it, so it is pending the human).**

**Files changed**
- `/Users/winnguyen/ScholarChain/scripts/smoke-test.sh`
- `/Users/winnguyen/ScholarChain/scripts/README.md`

**Exact new conditions (bounded-query step, after the existing HTTP 200 check)**
- `json_field` has three new selectors: `abstained`, `evidence-count` and `abstention-reason`. It still prints only identifier-shaped values, so no answer text reaches the console.
- The step prints `CHECK bounded-query-answer abstained=<true|false> evidence_items=<n> abstention_reason=<code|none|unreadable>`.
- It fails closed if `abstained` is missing or not a boolean ("no boolean abstained field").
- It fails if the body is not readable JSON.
- It fails if `abstained` is not `false`. The message is `bounded query abstained (abstentionReason=<code>): an abstained answer is not a pass`.
- It fails if the evidence count is not above 0. The count is the length of `evidence.items`, and 0 if the field is absent.
- After any of these failures, the replay, idempotency steps and `SMOKE_COMPLETE` are not reached.
- The dry-run WOULD_RUN text for the step now names these expectations.
- In `README.md` I updated the bounded-query table row and added a "Bounded-query answer check" paragraph. I also added a marker line and rewrote the stale "abstained answer fails the evidence step" bullet.

**Paste-ready human command (zsh-safe)**
```
cd /Users/winnguyen/ScholarChain && set -o pipefail; mkdir -p /Users/winnguyen/scholarchain-data/logs; bash scripts/check.sh --skip-sbt --skip-terraform 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-wo6-check.log | tail -n 60; echo "exit: $?"
```
Ran by: human (or through Claude in chat, per P16-5). Expect `CHECK_RESULT=PASS_WITH_SKIPS`, not `PASS`. Its shell-syntax and shellcheck gates are the only syntax evidence for the edit. The guard refused my own attempt to run `check.sh` ("shell and interpreter wrappers are blocked").

**Harness cases proposed for test-engineer (WO-7)**
1. `abstained=true` with a reason fails and prints the reason.
2. `abstained=false` with empty `evidence.items` fails.
3. `abstained=false` with one item passes.
4. A body with no `abstained` field fails closed.
5. A non-JSON body fails.
6. After an abstention, the replay step and `SMOKE_COMPLETE` are not reached.
7. The dry-run text names the new condition.
8. Existing cases that stub the query response with a body lacking `abstained` or evidence will now fail. Their stub bodies need updating, and that is not weakening the tests.

**Not verified or at risk**
- **Field names:** I could not search the repo, because the guard refused `grep` and no Grep tool was available. The names `abstained`, `abstentionReason` and `evidence.items` come from the load-client raw output, the plan and the existing evidence-id lookup. I did not check them against `QueryService.scala`. If the real field is nested differently, the step fails closed rather than passing. The README marks this UNVERIFIED.
- **Default question will now fail:** The default `--question` ("Which authors collaborated most between 2015 and 2020?") is the wording that abstained with `researcher-count` in SMOKE1. A run with the default against a real service should now FAIL. I did not change the default, because the right wording depends on the planner (commit `38421c6` changed the load-client wording). **Orchestrator decision:** change the default, or require the human to pass `--question`.
- **Digest change:** `smoke-test.sh`'s `SCRIPT_DIGEST` changed, so any earlier `smoke-test` approval record is invalid.
- **Nothing run:** no script, harness, shellcheck, AWS or service was run. I ran no sbt, in line with the work order. No git writes.
- **Stop hook:** the test marker `.claude/.last-test-run` is stale against these sources. My edits are shell and markdown, and sbt does not cover them. The Stop gate is unverified, not passed.

**Next gate:** the human runs the check above, test-engineer adds the harness cases, then independent review.

## Orchestrator note (not part of the report)
- The first two deliveries of this report were replaced by the agent's reply to the Stop hook; this is the third delivery, received in full.
- Field names: the Phase 16 survey (Explore, 2026-11-17) found the HTTP response carries `abstained` and `abstentionReason` (`QueryService.scala:443-453`) and the evidence bundle's `items` (`core/Contracts.scala:425-494`). Consistent with the names used; still to be confirmed in review.
- Open decision OD-1 (human): default `--question` of `smoke-test.sh` (change it, or require `--question`). Recorded in `../open-decisions.md`.
