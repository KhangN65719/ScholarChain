# Tonight runbook review 2 (code-reviewer, received 2026-11-17; summary as received)

**REQUEST CHANGES.** All 18 review-1 findings closed (B1 :17, :534, :537; B2 :710, :712, :752; B3 :185, :199, :219; B4 :13, :666-667, :693; M1 :579, :590; M2 :629; M3 counts match `tonight-infra-report.md`; M4 :616-625; M5 :20, :192; nine minors; billing examples recomputed). Part B faithful to the fallback (same-tag branch replaced by a stop; B.2 also checks `GIT_SHA`, `DIRTY`). `run.sh:38` refuses leading zeros.

New, would stop a good session:
- N1 :515 (reader R at S.1, S.4, S.7, H.2): `M=${SCD}/plans/p15-${NAME}/course-*.tfplan.meta` used as `${M}`; zsh does not glob a variable without `GLOB_SUBST`, so `PLAN_READ_CHECK` always fails (blocks teardown at H.2). Fix: `M="$(ls ...)"`.
- N2 :622 (smoke pass): `--query 'events[].message' --output text` prints one tab-separated line; `grep -c LOADCLIENT_RAW` = 1, so `SMOKE_RESULT=FAIL` on a good run; same for `STATUS_200_LINES`. Fix: `'events[].[message]'`.
Minor: m1 :616 `ACCOUNT_ID` unset, mask no-op; m2 :51-52 vs :53 count back from 00:55 (with D: B 19:50, A1 plan 21:25; smoke only: A1 plan 23:05); m3 D hold 10 min (:44) vs 25 min (:665); m4 :693 count occurrences with `grep -o | wc -l`.
Pending: harness 1917/0 and rehearsal 45/0 unrun (human E.6). H variant change (after this review started) not covered.
