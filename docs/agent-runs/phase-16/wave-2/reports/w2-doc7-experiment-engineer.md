# W2-DOC7: experiment-engineer report (EVALUATION.md section 7, H-12)

Author: experiment-engineer. Received by the orchestrator on 2026-11-19 and preserved as received (formatting condensed).

---

**Status: COMPLETE, with one unresolved relay-versus-log conflict.** Only `experiments/EVALUATION.md` edited. No `/Users/winnguyen/...` path changed; no account id, bucket name, ARN or 12-digit number copied; session paths in the regen log not copied.

Lines changed: (1) line 3 adds W2-DOC7; (2) section 7 intro (~827): B-F "recorded by W2-DOC7" with a pointer to the conflict in 7.3; (3) 7.3 B-F bullet rewritten; (4) 7.3 last bullet (D bootstrap re-derive): "besides the H-12 regeneration above" added, else the "only derive re-run recorded" claim would be false (outside the listed lines); (5) section 8 NOT DERIVED bullet: B-F removed, sample-pipeline replay stays; (6) Sources: "Added by W2-DOC7" line before the W2-DOC4 line.

Confirmed from logs and listings: `phase16-h12-regen.log` 8 `DERIVE_COMPLETE` lines then `EXIT=0`, none for B although `b-derived` outputs exist. `phase16-h12-compare.log` 49 SAME/DIFF lines: D 9/9 SAME; E original title 5/5 SAME (`e-latency-titlematch`); E other title 4 SAME, 1 DIFF (`latency.svg`); B 6 SAME, 2 DIFF (`derived.json`, `summary.md`); F2 12 lines all SAME; C 10 lines all SAME. Committed `experiments/results` holds 12 derived files for F2 and 10 for C. Arithmetic: 46 SAME, 3 DIFF of 49 lines; without the other-title E run, 42 of 44 identical.

Conflict: the relay says F2 15/15, C 12/12, 47 of 49; the log and directories give F2 12, C 10; extra 3 and 2 files not found; neither 42/44 nor 46/49 equals 47/49. Both recorded (relay attributed, log-confirmed, total unresolved); needs correction by Claude in chat or the human.

Relayed, not confirmed: B `derived.json`/`summary.md` differ only in input hashes and source path; the redacted copy as cause; the original title; "compared by sha256" (log shows SAME/DIFF only); derive commands not read.
