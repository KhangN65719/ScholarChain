# WO-Z2 re-review 1: code-reviewer

Author: code-reviewer, 2026-11-23. Archived by the orchestrator as received.

**WO-Z2 re-review 1: APPROVE.**

Status: COMPLETE (review). Owner: code-reviewer. Work order: WO-Z2 re-review 1. Files created or modified: none. Commands run: none (Read and Grep only). Evidence: the files read directly. I had no diff, so I checked the current text against my WO-Z2 verdict.

**Changed items, all resolved**

1. `README.md:389` (D03): Fixed. The "draft" and "added in the docs-only follow-up" wording is gone. It now says `human-gate.md` (the accepted decision) and `commit.txt` were added in commit Y `e3580fe`. That agrees with `human-gate.md:33`.
2. `reports/final-report.md:183`: Fixed. It now records the human's `sbt clean check` on a fresh clone of `264f24b`: exit 0, 1360 s (P17-39). It also says X, Y and Z change documentation only. This agrees with `final-report.md:334` (1360 s, P17-39) and `human-gate.md:18` (1360 s, "Commit X changes documentation only after `264f24b`").
3. `docs/agent-runs/phase-17/human-gate.md:23`: Fixed. It now reads "M1 to M8 and M10 to M14, with no M9; M14 is P17-43, added in commit Z". That matches the table (`final-report.md:86-91`) and the by-source note (`:69`).
4. `reports/final-report.md:73`: Fixed. The two sentences are now one, and the stray "also" is gone. The facts are unchanged: P17-15, F4 and L2, and the two `human-gate.md` files.
5. `docs/demo-script.md:131`: Fixed. It now carries the UNVERIFIED note on whether sbt compiled `modules/core/Probe.scala`, which sits outside `src/main/scala`.

**Owner's line correction: partly wrong (minor, no repair needed)**

The owner says line 390 holds the H-11 inventory PENDING. That part is correct. The owner also says `docs/demo-script.md:389` "has no PENDING mark" and "holds the 'after cleanup' intro". Both claims are wrong:
- Line 389 is the "Show, the cleanup path" bullet, and it contains "exact date PENDING, human" (the cleanup date, P17-7).
- The "after cleanup" text is on line 390.

My original note (PENDING at 389 and 390) stands. Both are unresolved and correctly marked. This error is only in the archived owner report, which is kept as received, so the deliverables need no change. The orchestrator may want to note it next to the report.

**Sensitive-text check on the changed lines**

None of the changed lines has a 12-digit id, an ARN, a bucket name, grader-directed text or a digit-slash-digit fraction. The grep hits are all on lines this repair did not change:
- `final-report.md:89`: the M12 row quoting the old "Autograder notes" heading, an existing disclosure.
- `final-report.md:113`.
- `demo-script.md:10`: the placeholder `s3://example-placeholder/x`.
- `demo-script.md:43` and `:120`.

"P3 / P4-D" on `demo-script.md:131` is not a digit-slash-digit fraction.

**Pending:** none from this review. The changes are docs only, so no sbt run is needed.

**Next gate:** the human commits Z, then applies the `v1.0-final` tag.
