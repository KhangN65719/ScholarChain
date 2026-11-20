# Re-review 1 of WO-F1

Author: code-reviewer (Opus). Recorded by the orchestrator on 2026-11-20, verbatim as received, with one exception. Three quoted assignment forms are written as `[secret-assignment form]`, following P17-34 and pending the human's N1 decision (since decided: P17-36, redact all). They were the reviewer's quotations of the `val secret` equals-quote pattern. Nothing else is changed.

---

# Re-review 1 of WO-F1: REQUEST CHANGES

**Status:** COMPLETE. **Owner:** code-reviewer (Opus). **Work order:** WO-F1, re-review 1. **Files created or modified:** none. **Commands run:** none; I have Read, Grep and Glob only.

The documentation-engineer and hadoop-engineer repairs are acceptable. B1 (the "now" items), B2, B3, B4, B6 (README part) and non-blocking items 1 to 6 are resolved, and none of these repairs has a blocking finding.

The verdict stays REQUEST CHANGES for one reason. The tree still has AKIA key-pattern matches, and the B5 redaction added a [secret-assignment form]. This is the orchestrator's to fix, and it needs a human decision.

**Evidence used:** I read the full `git diff` file at `/Users/winnguyen/.claude/projects/-Users-winnguyen-ScholarChain/ff54167f-e850-4f21-bc10-f8199bab4597/tool-results/b1l240ehk.txt`. I checked each claim against `docs/agent-runs/phase-17/human-commands.md:90-111` and `docs/agent-runs/phase-17/decisions.md:33-40`, and ran Grep across the tree.

## Blocking finding

**N1. Key-shaped and [secret-assignment form] literals in the held archive.** Owner: orchestrator. Needs a human decision because it conflicts with P17-35's "verbatim" rule.

- **AKIA matches.** Grep for the AKIA key pattern across the whole tree finds 3 hits, all in my own archived F1 verdict, `docs/agent-runs/phase-17/final-gate/review-F1-verdict.md`:
  - `:49` quotes the old A3a literal.
  - `:74` quotes the P14 fixture value.
  - `:75` quotes the AwsIngest value.
  - The A3a redaction worked: `report-A3a-rag-engineer.md:7` no longer matches. But I put the same pattern back into the archive in my F1 verdict. That is my error, and this verdict avoids repeating it.
- **New [secret-assignment form].** `docs/agent-runs/phase-17/wave-a/report-A3a-rag-engineer.md:18`, the P17-34 redaction note, contains that form.
- **Older text in the same held archive** (`phase-17/` is untracked, so all of it lands in the close commit):
  - `docs/agent-runs/phase-17/wave-a/work-orders.md:33` quotes the exact assignment that Wave A removed from `AwsNeptuneQuerySuite.scala:362` to clear K01. This is the highest-risk line.
  - `work-orders.md:29`, `report-A3a-rag-engineer.md:11` and `report-A3b-storage-engineer.md:11` contain the form with "...".
- **Fix:** extend the P17-34 redaction, with a dated note, to the three verdict lines, the A3a `:18` wording, `work-orders.md:33` and, if the human wants, the "..." mentions. Each value should become a description. Because P17-35 records my F1 verdict as verbatim, the human decides whether redacting it is acceptable.
- **Scanner uncertainty:** the K01 scanner's exact pattern is unverified. The tracked `tests/adversarial/Phase11FaultSuite.scala:32` has a `val secret` assignment, yet K01 passed at `5157e0e`. The assignment-form risk is therefore uncertain, but the AKIA risk is the same as B5.

## Orchestrator-flagged points

**(a) `modules/hadoop/JOB-CONTRACTS.md:213` against `:259`: consistent in fact, inconsistent in wording.** Non-blocking. Owner: hadoop-engineer; fold into WO-F5.

- **`:213` is supported.**
  - `human-commands.md:99` records the H-8 part 1 `sbt check` at exit 0, with every group at Failed 0 and Errors 0.
  - A Grep of `modules/hadoop/src/test` for `.ignore`, `assume(` and `.pending` finds nothing, so the single skipped test is not a Hadoop test.
  - "Passed inside the aggregate run" is therefore justified. The line also correctly says the per-suite output was not read.
- **`:259` now reads against it.** "No runtime result is recorded here ... NOT REPLAYED" conflicts loosely with `:213`, which does record an aggregate pass.
- **Suggested wording for `:259`:** "No per-suite runtime output is recorded here. Counters and invariants in section 8 are assertions from tests; they passed only within the aggregate H-8 part 1 `sbt check` (section 8), and no standalone run was replayed."

**(b) Documentation-engineer's stated risks.** Both are non-blocking. Owner: documentation-engineer, in WO-F5.

- **`docs/api.md:205`:** "carry their own replay marks (H-8)" is stale. The README API examples are not covered by H-8 (`README.md:245`). Change it to "carry their own replay marks". This is the same class as non-blocking item 2.
- **`docs/runbook.md:3`:** "marked NOT REPLAYED until the human's replay (H-8) is archived" is more than awkward. H-8 is archived, so the sentence implies the commands below are now replayed, and the clause that follows contradicts that. Replace it with "marked NOT REPLAYED; the H-8 records ... cover only ...", which is the form `docs/threat-model.md:3` already uses.

## Other non-blocking findings (documentation-engineer, in WO-F5)

1. **`docs/threat-model.md:145`:** "Replay result lands with H-8." is still there. It is a leftover of item 2; the repair fixed `:3` and `:134` only.
2. **`reports/final-report.md:94`:**
   - It lists P17-33 among the human's decisions "by the source column", but `decisions.md:38` gives the source as "human; orchestrator". Move it to the "both" list, or drop the "by the source column" framing for it.
   - Grammar nit: "P17-23 and P17-31" should be "P17-23 and P17-31" without the double "and" ("…, P17-23 and P17-31 are orchestrator decisions" with a comma before P17-23).
3. **`reports/final-report.md:241`:** it states the WO-F1 verdict as REQUEST CHANGES and WO-F2 as PENDING. Update it once the final-gate outcome is known.
4. **`docs/service-limits.md:95`:** "...each rate). the Phase 17 plan reads..." needs a capital "The". The content now matches `plan.md:71` and `final-report.md`.

## Resolved items (evidence)

- **B1, the "now" items:**
  - README lines 218 and 219 are linked, with "committed in `dc15299`".
  - D17 and D18 are "present", with links.
  - D20 says the "after cleanup" inventory falls after the deadline, and that none exists unless demo item 22 is run.
  - README `:189` is resolved through P17-25, P17-26 and P17-33.
  - D03, D12, D14 and D19 are unchanged, as ordered for WO-F5.
- **B2:**
  - `final-report.md:102`: P1, P1b, P2, P3 PARTIAL and P4 match `human-commands.md:91-95` word for word in substance.
  - `:239` (H-8 part 2) and `:283` (now `:285`): the prefix difference, 11 tests against 8 (core 52 against 49), the process deviation, step 8 not run, no `sbt check` on the branch and the branch deleted all match `:102-111`.
  - `:180`: the 1370 s run and its group order match `:99`.
  - `:319`, `:184` and `:330` are fixed.
- **B3:** M12 matches P17-30. M13 matches P17-31 item 1 and P17-32. The `:69` sentence and the `:92` list are updated. Recording P17-34 as an orchestrator archive edit, not an M entry, is consistent with `docs/agents.md`.
- **B4:**
  - Demo script `:23` and item 4 `:121`: the refusal messages match `human-commands.md:91-92`.
  - Item 5 records P3 PARTIAL. `:308` records H-8 part 2.
  - `:19`, `:366` and the closing section state the `dc15299` commit state correctly.
  - The P17-33 removal of item 11 path (i) is complete in item 11, the time table and the final checklist.
- **B6:** `README.md:16` cites P17-35 and the condensation. The decision itself is recorded (`decisions.md:40`).
- **Non-blocking items 1 to 6:**
  1. The period after "1427" is added.
  2. Fixed in runbook `:31` and `:181`, service-limits `:176`, threat-model `:134` and JOB-CONTRACTS `:259`. Residuals are `:145` and `api.md:205`, listed above.
  3. `final-report.md:116` is aligned with README `:97`.
  4. Cost is now the orchestrator's reading.
  5. The A01 item is now cited correctly.
  6. WO-F1 and WO-F2 are now named.
- **Privacy and grader text:**
  - The only 12-digit values in the changed files are `000000000000` placeholders.
  - No ARN or credential was introduced in the changed files (apart from N1).
  - No new text addresses graders.
  - No invented result: every new OBSERVED claim traces to `human-commands.md:90-111` or `decisions.md:35-40`.

## Not ruled on

Demo item 13, the optional laptop S3 Vectors or Bedrock run, awaits a human decision. Demo `:43` (section 1.4) ties the same optional Bedrock laptop runs to item 14 (the H answer runs) as well. The orchestrator may want the human's decision to cover both items.

## Pending (not verified here)

- WO-F2 (test-engineer): `sbt check` with exact counts.
- WO-F3 (infra-engineer): Terraform checks.
- WO-F5 (documentation-engineer): D03, D12, D14 and D19, plus the non-blocking items above.
- H-14 (human): `sbt clean check` on the final commit, V1 (including K01) and the strict account-id scan. Only the V1 run can confirm what K01 actually matches.

## Limits

- I reviewed only the diffed lines, the two archive edits, and the Grep hits for "H-8", AKIA, the assignment form and 12-digit numbers.
- I did not re-read the unchanged README lines that mention H-8 (7, 89, 97, 326-332), because they are out of scope.

## Next gate

1. Orchestrator: take N1 to the human (redaction scope against P17-35 "verbatim"), then apply the decision with a dated note.
2. Fold findings (a) and (b) and non-blocking items 1 to 4 into WO-F5 for hadoop-engineer and documentation-engineer.
3. A re-review covers only the N1 edits, plus the WO-F5 lines when they land.
