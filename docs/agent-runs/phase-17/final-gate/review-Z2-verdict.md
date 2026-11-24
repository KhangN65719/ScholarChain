# WO-Z2 review verdict: code-reviewer

Author: code-reviewer, 2026-11-23. Archived by the orchestrator as received.

**WO-Z2 review: REQUEST CHANGES.** Three short text fixes are needed: two in documentation-engineer files and one in an orchestrator file. Everything else checks out against the records.

Status: COMPLETE (review). Owner: code-reviewer. Files created or modified: none. Commands run: none (Read, Grep and Glob only). Evidence: the saved diff and the files read directly.

**Must fix**

1. **`README.md:389` (D03, your question 8): stale, so fix it.** It still says phase-17 "now holds `agent-report.md` and a draft `human-gate.md`" and that `commit.txt` and the final `human-gate.md` "are added in the docs-only follow-up commit". Both are now false. `docs/agent-runs/phase-17/commit.txt` exists, and `human-gate.md` holds the accepted decision (`human-gate.md:3,7`), both added in `e3580fe` (P17-42 item 2, P17-44). In a deliverable index, "draft" is a wrong status, not just old wording. Owner: documentation-engineer.

2. **`reports/final-report.md:183`: "The final gate on the final commit is PENDING (human, H-14)."** This contradicts the same report's line 334 ("Final gate (H-14). Recorded: ... `sbt clean check` on `264f24b`, exit 0, 1360 s (P17-39)...") and `human-gate.md:18`. The project rules say a docs-only follow-up commit needs no check, and X, Y and Z change documentation only (`human-gate.md:18`, and this diff is all `.md`). The PENDING is not unresolved by the records. Replace it with the P17-39 result on `264f24b` and say the later commits are docs-only. Owner: documentation-engineer.

3. **`docs/agent-runs/phase-17/human-gate.md:23`: "M1 to M8 and M10 to M13, with no M9; P17-43 is added to the report in commit Z".** The report now has M14 (`final-report.md:91`, by-source note at line 69), so this should read "M10 to M14". Owner: orchestrator.

**Minor (fix in the same round if convenient)**

4. **`reports/final-report.md:73`.** The new sentence ("There is no M9 entry ... the case that was formerly M9 is recorded here as a class 2 repair") is followed straight away by the old one ("One orchestrator repair after review is also recorded here (formerly M9)"). That says the same thing twice, and "also" no longer fits. Merge them. The fact itself is right: the M table goes from M8 (line 86) to M10 (line 87).

5. **`docs/demo-script.md` item 5 ("Recorded result"):** "The quick run succeeded with the comment-only file present." This is true as a timeline. But it replaces the earlier UNVERIFIED note about whether `sbt quick` accepts a comment-only Scala file. `modules/core/Probe.scala` sits outside `src/main/scala`, and no record says sbt compiled it. Add "whether sbt compiled it is not recorded", or bring back the UNVERIFIED mark.

**Checks that pass**

- Check 1, video link: https://youtu.be/tWTDWoxji7c is right in all four places: README D19 and the "Final report and demonstration video" section, `demo-script.md:3` and the checklist line, and `final-report.md:333`. Each says the link was not opened and makes no claim about what the video shows.
- Check 2, P3 / P4-D text: It matches `human-commands.md:157-164` in the README test-stop step, demo item 5, `final-report.md:105` and `human-gate.md:25`. The block message is exact. Also matching: 168 s, the last two groups at 77 and 1306 with 0 failed each, earlier groups not tallied, the next stop not blocked, Probe.scala removed, a `/clear`ed session rather than a restarted one, and the marker mtime not read.
- Check 3, by-source list: It matches the `decisions.md` source column. P17-40, 41, 42 and 45 are the human's; P17-43 and 44 are human plus orchestrator. The M14 row matches P17-43, and the "no M9" note is accurate.
- Check 4, V1 to V3: The numbers in `final-report.md:268`, `:334` and `human-gate.md:22` match "Phase 17 close" (40/2/7/9/0; 7+1, 10+2, 11; 10 met, no flags; HADOOP-04, HADOOP-12 and DATA-02). No V1 to V3 PENDING text remains.
- Check 5, final tag: `v1.0-final` is stated the same way everywhere and always as "applied by the human after that commit". The demo says to show it "only if it exists". Nothing claims it is already applied.
- Check 6, `human-gate.md`: Consistent with the records apart from item 3.
- Check 7, sensitive text: No 12-digit ids, bucket names, ARNs, or grader- or agent-directed text in the added lines. No new digit-slash-digit fractions in the added prose; "77/0" appears only in the owner report, which is an archive file.
- Check 9, remaining PENDING items: Apart from item 2, each one is genuinely unresolved, with no record of H-9 laptop results, H-10 Billing, the H-11 cleanup date and inventory, `scripts/check.sh` or the strict account-id scan, or the Z SHA. That covers final report 218, 256, 328, 332, 334 and 335, and README 226, 230 and 406. The "(PENDING)" in the heading at `final-report.md:330` is still valid. One mismatch: the owner report's "demo-script 1" does not point at an H-9 PENDING. The demo-script's PENDING items are at lines 389 and 390 (cleanup, H-11), and both are unresolved.

**Next gate:** Route items 1, 2, 4 and 5 to documentation-engineer and item 3 to the orchestrator. Then a re-review covering only those lines. After that, the human commits Z and tags `v1.0-final`. Pending: none from this review (docs only, no sbt needed).
