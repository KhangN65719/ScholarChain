# W2-DOC6: experiment-engineer report (EVALUATION.md section 7, reproducibility)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE** (docs only; `experiments/EVALUATION.md` only). No sbt, AWS, Python or commit.

Lines changed: (1) header line 3 adds W2-DOC6; (2) section 7 intro (827): four-item status line (clean-checkout recorded; `EvaluationRun` replay cross-referenced to 6.5.1; sample-pipeline replay and B-F regeneration NOT DERIVED; deployment cited, no new deploy); (3) 7.1: "NOT DERIVED: pending" replaced by a comparison table, confirmed and unconfirmed bullets, a limits paragraph; the `scripts/README.md` sequence relabelled as not run; (4) 7.1 last paragraph: "NOT RECORDED" replaced by a pointer to the table; (5) 7.3: B-F bullet says no frozen record exists; new bullet: sample-pipeline replay NOT DERIVED; (6) section 8 "Not in any frozen file read": clean-checkout removed, points to 7.1, keeps B-F and sample replay NOT DERIVED; (7) section 8 human-rating bullet: "pending human gate" became "no longer pending (7.1)"; (8) Sources: W2-DOC6 line added; "Not read" line unchanged. Lines 409-780 (6.1-6.5) not read for stale "pending" mentions.

Figures: phase gate exit 0, 1314 s, 8:36:12 AM, ccbc1ec, per-group totals from the human's relay; clean checkout fresh clone at ccbc1ec, exit 0, 1315 s, 9:01:38 AM, no dirty-file count printed (stated); skip `ExportHeapMeasureSuite` by `assume` on `SCHOLARCHAIN_S_EXPORT_DIR`, attributed to the read-only search row.

Confirmed from the logs: last 54 lines of the phase-gate log (901,243 lines) and last 51 of the clean-checkout log (901,410 lines): last two totals 99/0 and 1427/0 then `[success] Total time`; durations and times match.

Not confirmed: the other seven totals (260, 170, 473, 389, 501, 331, 49) and the skip in both logs (no search tool; shell grep refused); group-by-group equality confirmed for 2 of 9, the other 7 NOT DERIVED, incl. whether the clean checkout skipped the same test; working-repository dirty-file count at the gate NOT RECORDED; warm dependency caches NOT STATED; `scripts/README.md` sbt, Terraform and Docker steps not run. No machine paths, private IP or 12-digit numbers copied. No H-12 entry found in `human-commands.md` Results (read through line 49). Redaction by reading only.

Manual action suggested: grep `Passed: Total` and `Skipped` over both logs; B-F regeneration and the sample-pipeline replay need the human's run or a decision to leave them NOT DERIVED.
