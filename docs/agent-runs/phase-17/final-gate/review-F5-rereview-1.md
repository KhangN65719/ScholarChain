# Re-review 1 of review F5

Author: code-reviewer (Opus). Recorded by the orchestrator on 2026-11-20, verbatim as received.

---

**Re-review 1 of F5 verdict: APPROVE**

B1 to B3 are fixed. Items 1 to 7, 9 and 10 are fixed. Item 8 is only partly fixed. That item and two other small issues are optional cleanups and don't block approval.

**Status:** COMPLETE. **Owner:** code-reviewer (Opus). **Work order:** re-review 1 of review F5. **Files created or modified:** none. **Commands run:** none (I have Read, Grep and Glob only).

## Blocking findings and what each repair did

- **B1:** `docs/agent-runs/phase-17/human-gate.md:9-12` now lists the three repair rounds. Re-reviews 1 and 2 returned REQUEST CHANGES and re-review 3 returned APPROVE. This matches my F5 verdict.
- **B2:**
  - `README.md:83` adds the human's gate `sbt clean check` on `264f24b`: exit 0, 1360 s, nine groups, all Failed 0 and Errors 0, one skip, citing P17-39. This matches `decisions.md:44`.
  - `README.md:85` and `:311`, and `reports/final-report.md:103`, no longer say "no run on the committed tree is recorded". They cite the `hooks-guard` run (114 pass, 0 fail) on the `264f24b` clone and note that no `.claude/` file changed after `5157e0e`.
  - The optional fixes are also done, at `final-report.md:183` and `docs/runbook.md:31`.
- **B3:**
  - New section 12 of the manifest, `docs/releases/phase-17/README.md:198-208`, gives both billing readings. They match `decisions.md:22` (P17-17) and `:38` (P17-33), including "unchanged since 07:41", "nothing running", the 20.00 limit, "Approaching limit" and the 298.10 forecast read as an extrapolation.
  - Both readings are labelled account-level and month-to-date, subject to Billing lag. The per-phase split is NOT DERIVED and the final reading is PENDING (human, H-10). The open-fields rows at `:225-227` agree.

## Non-blocking items

1. **Fixed.** The manifest now cites P17-6 at `decisions.md:12` (lines 19-21) and P17-7 at `:13` (line 193).
2. **Fixed.** The `git status` file list is gone from manifest line 19.
3. **Fixed.** Manifest line 12 cites `deploy-tn-s3-a3.md:32` and keeps UNVERIFIED. I checked that line, and its own UNVERIFIED statement matches.
4. **Fixed.** `human-gate.md:14` lists WO-A2, A2b, R1 and R2 and WO-A6. This matches `human-commands.md:79,85`.
5. **Fixed.** `human-gate.md:21` reads "M1 to M8 and M10 to M13; there is no M9".
6. **Fixed.** `JOB-CONTRACTS.md:213` and `:259` both cite H-8 part 1 and P17-39, and "only" is gone. `:259` correctly says no per-suite output was read from either run.
7. **Fixed.** `docs/demo-script.md:43` adds 10.44 USD (P17-33) and says both readings are account-level and subject to Billing lag.
8. **Partly fixed.** The repeated closing sentence about P17-37 and P17-39 is gone from `reports/final-report.md:94`. I rechecked the sorting of all 39 IDs against the source column of `decisions.md`, and it is correct. But the long parenthetical still has two full stops inside it, at "attributed note. This is" and "an M entry. P17-36". Optional cleanup for documentation-engineer.
9. **Fixed.** `agent-report.md:26` reads "automated check fixes".
10. **Fixed.**
    - `human-gate.md:13` adds the review F5 line, with the re-review outcome PENDING. The orchestrator updates it with this verdict.
    - `agent-report.md:32` adds a `review-F5-*.md` row, and its glob matches the verdict file.
    - The `final-gate/report-*.md` row at `:30` names all three F5-R1 authors.

## The `run.sh` result

Every place it appears agrees with `human-commands.md:72`: exit 0, `RESULT pass=1949 fail=0`, `SKIPPED_CASES 0`, `real-repository-untouched PASS`, run by the human on `264f24b` at `/tmp/sc-gate` on 2026-11-20. I checked these locations:
- `README.md:84` and `:368`
- `reports/final-report.md:183`, `:242` and `:332`
- `docs/runbook.md:31`
- manifest `:183` and `:216`
- `human-gate.md:19`
- `agent-report.md:11`

Shorter forms leave out details but say nothing wrong. A search for `run.sh` or the harness near "PENDING" finds no stale text. The only hit, `final-report.md:332`, puts `scripts/check.sh` and the automated review runs under PENDING, not `run.sh`. The earlier pass on `2151c01` (`human-commands.md:74,82`) is described correctly in `README.md:84` and manifest `:183`.

## Line citations in the manifest after the insertions

The deploy-engineer did not re-read the file after editing, so I checked every changed or dependent citation against its target:
- `decisions.md`: `:12` P17-6, `:13` P17-7, `:22` P17-17, `:30` P17-25, `:38` P17-33, `:42` P17-37, `:43-44` P17-38 and P17-39. All correct.
- `human-commands.md`: `:65,69`, `:67-72`, `:68`, `:70`, `:71`, `:72`, `:82` and `:107`. All correct.
- Section numbers 1 to 12, then "Open fields", are in order. The "section 9" and "section 12" references in the open-fields table point to the right sections.
- Two fields the F5 pending list told the deploy-engineer to update after the demo still sit at the same lines: the plan checksum at `:62` and the inventory at `:195`. The insertion was below them.

## Scans

- **AWS key pattern** (AKIA followed by 16 uppercase letters or digits): no hits in the whole repository.
- **Quoted secret-assignment form** (a secret-like name, `=` or `:`, then a quoted literal): no hits in any file changed this round. The only hits are in older archive files under `phase-16/` and `phase-17/wave-a/` that this round did not touch.
- **Account ids:** in the changed files, the only 12-digit runs are the masked placeholder of twelve zeros (`README.md:18`, manifest `:3` and `:39`, `runbook.md:9`). There is no ARN and no private-key header.
- **Invented results:** none. Every new value traces to `decisions.md` or `human-commands.md`.
- **Text addressed to graders:** none in the manifest, the gate draft, the index or the changed lines. The one hit is the file name `autograder-v1-preview.md` in the index row, which I approved before.

## Optional cleanups (not required for this approval)

- **N1 (documentation-engineer):** `docs/runbook.md:31` cites `docs/agent-runs/phase-16/ratification.md` (Gates) for the 2026-11-20 run. That file can only support the Phase 16 count. The citation came over from the earlier text. Drop it, or say the Phase 16 gate had the same count.
- **N2 (documentation-engineer):** At `reports/final-report.md:94-95`, the blank line between the Class 4 paragraph and `## 5. Authority and ownership design` is gone. It still renders, but it doesn't match the rest of the file. The saved diff is against `264f24b`, so I can't tell whether this round removed it.
- **N3:** the item 8 leftover above.

## Still pending (not checked by this review)

- Final documentation commit and tag (human, P17-6).
- V1 to V3 on the final commit (human, H-14).
- Demo results (human, H-9). If the demo includes item 18 or item 22, deploy-engineer updates manifest `:62` and `:195`.
- Final billing reading (human, H-10).
- Per-group totals and exit code of the F2 `sbt quick` (not recorded).
- The orchestrator updates `human-gate.md:13` and `agent-report.md:12` with this verdict.

## Limits of this review

I read only the lines this repair changed and the lines next to them. I did not re-review lines I approved in F5 or F1. The pre-repair text of `final-report.md:94` is not in the saved diff, so for item 8 I can only judge the current text.

**Next gate:** the orchestrator records this verdict. The optional N1 to N3 can go into the docs-only follow-up, or be left as they are. After that, the phase goes to the human for acceptance.
