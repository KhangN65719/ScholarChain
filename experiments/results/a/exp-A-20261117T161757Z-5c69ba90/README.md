# Experiment A: Hadoop and Spark baseline, tier S (run `exp-A-20261117T161757Z-5c69ba90`)

Status: COMPLETED by the harness; NO DERIVED FILES. There is no derived table or plot for A. The raw run files were not available to a derive step (no local reports directory for this run), so this directory holds this note only. Do not read a missing number here as zero.

What is recorded, from `docs/agent-runs/phase-15/session-ab/session-record.md` (orchestrator, from the human's pasted outputs; not a derived file):

| Item | Value | Source line in the session record |
|---|---|---|
| Run id | `exp-A-20261117T161757Z-5c69ba90` | step 2.7 |
| Commit | `5c69ba9006b9f4a3b83d4e103d6db5710483f4e0` | step 0.7 |
| Final status | `ENTRY_EXIT=0 FINAL_STATUS=COMPLETED elapsed_s=3682` | step 2.7 |
| Estimated spend | `estimated_spend_usd=0.61` (ceiling 0.75; burn x time, not a bill) | step 2.7 |
| Pipeline | `FINAL_STATUS=SUCCEEDED`, 16:18:00Z to 17:19:19Z, 61.3 min, `RECORD_COMPLETE=yes` on both records | step 2.8b |
| Graph run id (A's export) | `run:v1:3cfb57b1-abd1-4913-8e53-da477d8d6027`, `tables_in_manifest=17` | step 2.9 |
| Export | 17 tables, 34 objects, 373,888,058 bytes | step 2.10 |
| Export size and rows checks | `EXPORT_SIZE_CHECK` and `EXPORT_ROWS_CHECK` PASS; the sizes and row figures were computed by another Claude session from the saved listing and manifest and relayed by the human (not by a repository script) | step 2.10 |

NOT DERIVED for A: input records, MapReduce and Spark task counts, shuffle bytes, failure counts. The `a-baseline/derive-table.py` run over the A reports was not done (the derive script is run by the human; the guard blocks agent Python). This is a limitation, listed in `experiments/LIMITATIONS.md`.
