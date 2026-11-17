# Runbook review 3 verdict, narrow re-review of the review-2 repair (code-reviewer; received 2026-11-17; summary as received)

**APPROVE.** 0 BLOCKER, 0 MAJOR, 0 MINOR; two optional cosmetic notes.
- 0.3 (:201) and 0.7 (:247): `git diff --name-only --no-renames "${W2}" HEAD`, exit captured in `DX`; pass only with `DX == 0` and no path outside `docs/`; otherwise FAIL and `false`. Other checks unchanged.
- B1.8 (:1053, :1056): `tables` is an object keyed by table name (`JsonlExport.scala:771-773`); write only when `NT == "17"`, slug pattern matches and `sourceRunId` equals the recorded value; else `FAIL ... nothing written` and `false`; `set -C`; braced variables; failed download -> empty `MAN` -> FAIL.
- B1.4a (:1020): `find ${DEST} -maxdepth 1 -type d -name 'run=*'`; missing or no match gives `N=0` and fails; `N == "1"` unchanged.
- Open items :1103-1109 numbered 3-7 after placeholders 1-2.
- Conventions hold; no S3 write in these sections.
- Cosmetic (optional): prose :196, :244 omits `--no-renames`; B1.8 "Expected" (:1059) omits `MANIFEST_TABLES_COUNT=17 (must be 17)`.
Pending: edited shell unrun; run 6 Parquet with all 17 tables (B1.4a) and its manifest `sourceRunId` (B1.4b) unverified.
