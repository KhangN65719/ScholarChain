# Phase 17 human gate

Compiled by the orchestrator on 2026-11-20 from the records cited below (decision P17-4). The decision is the human's. DRAFT: the decision line and the accepted commit stay PENDING until the human accepts the phase and the final documentation commit exists (P17-6).

- Decision: PENDING (human).
- What was reviewed:
  - Wave A: reviews A5a, A5b and A9 with their re-reviews (`wave-a/review-*.md`).
  - Wave B: reviews B12a to B12e and B-H8 with their re-reviews (`wave-b/review-*.md`).
  - Final full review F1, run by code-reviewer (Opus) on `6072310..HEAD` plus this folder. It returned REQUEST CHANGES, and three repair rounds followed (`final-gate/review-F1-verdict.md`, `review-F1-rereview-1.md` to `-3.md`):
    - Round 1, WO-F1-R1 and WO-F1-R1h (documentation and Hadoop): re-review 1 returned REQUEST CHANGES (N1).
    - Round 2, the P17-34 and P17-36 redactions: re-review 2 returned REQUEST CHANGES (a missing dated note).
    - Round 3, that note: re-review 3 returned APPROVE.
  - Review F5 of the close drafts (the release manifest, the WO-F5 edits and these files) by code-reviewer (Opus): REQUEST CHANGES, followed by a repair round. Re-review 1 returned APPROVE, with three optional cleanups (N1 to N3) left open (`final-gate/review-F5-verdict.md`, `review-F5-rereview-1.md`).
  - The constitutional changes WO-A2, A2b, R1 and R2 (Wave A), and WO-A6 (Wave A2), each ratified by the human (`human-commands.md`, "Results recorded").
- Accepted or rejected: PENDING (human). Evidence for the decision:
  - The human's `sbt clean check` on a fresh clone of `264f24b`: exit 0, 9 groups all 0 failed and 0 errors (one skip), `[success]` 1360 s. A first attempt, run at the same time as an agent's sbt, failed 3 Hadoop tests with `OutOfMemoryError` (P17-38, P17-39).
  - Agent `sbt quick`: `[success]` 158 s (`final-gate/report-F2-test-engineer.md`).
  - Terraform `fmt -check` exit 0; `validate` gave "Success! The configuration is valid."; `hooks-guard.sh` 114 passed, 0 failed (F3, human, `human-commands.md`).
  - Full harness `tests/scripts/run.sh` on `264f24b` (human, 2026-11-20, `/tmp/sc-gate`): exit 0, `RESULT pass=1949 fail=0`, `SKIPPED_CASES 0`, `real-repository-untouched PASS` (`human-commands.md`, "Phase 17 final gate, F3").
  - V1 to V3 on the final commit: PENDING (human, H-14).
- Manual changes: edits by Claude in chat outside the agent workflow, recorded as P17-18, P17-24, P17-27, P17-29, P17-30 and P17-31 item 1 (`decisions.md`; final report, "manually edited" class M1 to M8 and M10 to M13; there is no M9). Orchestrator redactions in archive files, P17-34 and P17-36. The human accepted condensed archive files (P17-35).
- Remaining risk:
  - Probe P3 is PARTIAL: the full block-then-pass loop is to be recorded in the demo video.
  - The H-8 part 2 replay reproduced PARTIALLY.
  - K02 (a fake key in history) and E03 stay explained, not fixed.
  - DATA-02 is a known V2 partial (P17-29).
  - No Terraform plan checksum and no artifact checksums were recorded in Phase 17.
  - No live AWS session ran (P17-33, P17-37).
  - The demonstration video link is PENDING.
- H-4: the human confirmed the phase 01 to 16 `human-gate.md` decisions and accepted commits as compiled (P17-40).
- Accepted commit: PENDING. This will be commit X, the final documentation commit, written here and in `commit.txt` in follow-up commit Y. The video link and the P3 result follow in commit Z, which carries the tag (P17-42, amending P17-6).
