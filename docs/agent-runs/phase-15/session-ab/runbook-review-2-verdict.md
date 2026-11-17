# Runbook review 2 verdict, narrow re-review of revision 3 FINAL (code-reviewer; received 2026-11-17; summary as received)

**APPROVE.** 0 BLOCKER, 0 MAJOR, 3 optional MINOR.
- HEAD gates 0.3 (:201) and 0.7 (:247) pass: full SHA `6cd73419c546b2205a2e2fde09b25a747c3efb8e`, 40-hex check, `merge-base --is-ancestor`, git error -> FAIL, branch `main`, conventions hold; no `REPLACE_WITH_WAVE2_COMMIT_SHA` left.
  - MINOR-1 (:201, :247): `git diff --name-only` detects renames, so `scripts/x` moved to `docs/x` shows only `docs/x`; add `--no-renames`. Low risk (0.4 stages only docs, 0.7 stat would show the deletion).
- Decision wording passes: :172, :834, :838-839 match P15-30 (decisions.md:35); P15-31 cited at :196.
- Step B1 passes: triggers :983-985, rule for B :987, no S3 write or AWS-changing command, `if mkdir` :1010, listing cap 1 GiB / 2,000 objects :1000, cost ~0.097 within 0.10, HIGH 3.90 and T0 unchanged :1063-1065. Paths verified: `run-s-pipeline.sh:579-580, 506-507, 520`; `JsonlExport.scala:599, 56-262, 394, 765`. Both BLOCKED-ON lines accurate (`upload-inputs.sh:15-24`, `archive-evidence.sh:36-47`).
  - MINOR-2 (:1056): B1.8 gated only by its heading; pasted early it would write `session2.tfvars` naming run 6's 11-table export. Refuse unless the manifest's `tables` count is 17.
  - MINOR-3 (:1020): zsh `ls -d ${DEST}/run=*` with no match aborts "no matches found" (still fails closed); use the `find -maxdepth 1 -name 'run=*'` form of `run-s-pipeline.sh:506`.
- Status and placeholders pass (:1-5, :1098-1101: only `REPLACE_WITH_BILLED_TOTAL`, optional `REPLACE_WITH_CLUSTER_ID`). Cosmetic: open-items list :1103-1109 skips item 5.
- No account ID (only the `000000000000` redaction), us-east-2 only, spend-limit/advanced-features text only as prohibitions (:13, :176).
Pending: new shell unrun; run 6 Parquet holding the six analytics tables (B1.4a); run 6 manifest carrying `sourceRunId` (B1.4b).
