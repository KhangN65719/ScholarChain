# W2-RB17: experiment-engineer report (review 23 repairs, EVALUATION.md section 7)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Only `experiments/EVALUATION.md` written; reads and edits only; no sbt, AWS, Python or commit; no re-read after edits.

- Finding 1, ~841: the human's reading / "NOT CONFIRMED" replaced by the same nine totals in both logs, cited to review 23's Grep and verdict path; in-repository order 49, 331, 501 (Passed 500, Skipped 1), 389, 473, 170, 260, 99, 1427; clean checkout prints 389 before 501; all Failed 0, Errors 0, no `[error]` line.
- Finding 1, ~846: "not confirmed by me" / "2 of 9" replaced by equality 9 of 9 (review 23's Grep, not the author's tail reads), with per-group log line numbers and the 389/501 order difference.
- Finding 1, ~847: clean-checkout skip "NOT CONFIRMED" replaced: same `ExportHeapMeasureSuite` skip in both logs (lines 2405 and 2932); one `Skipped 1` total per log, so 501 by elimination; the earlier timestamp-inference note kept and marked as now supported.
- Finding 1, 948: "two of nine ... not confirmed" replaced by nine of nine equal, all Failed 0 and Errors 0, the single skip in both logs, per review 23's Grep.
- Finding 1, 960: "W2-RB17 from review 23" source added, separate from the author's tail reads (last 54 and 51 lines; the rest "not read by the author").
- Finding 2, 835 and 848: `ccbc1ec` checked out "per the human's relay (`human-commands.md` H-13 row), the log has no HEAD line"; 848 adds the log has no `HEAD=`, `CLONE_DIR`, `DIRTY_FILES` or `ccbc1ec` line.
- Finding 3, 949: two sentences: clean-checkout build no longer pending (7.1); separately, H-1c Billing was read (P16-43).
- Finding 4, 872: `phase16-wave2d-harness.log` (2026-11-19, `RESULT pass=1949 fail=0`, `human-commands.md`) added.
- Optional, 950: review 23's Grep 0 hits on the W2-DOC6 revision.

No machine paths, IPs, bucket names, ARNs, account ids or 12-digit numbers written.
