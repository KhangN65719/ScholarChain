# R12 report, experiment-engineer (received 2026-11-17; summary of the report as received; author: experiment-engineer subagent)

Status: COMPLETE (edits only); rehearsals not executed (guard). Files: `experiments/a-baseline/{derive-table.py,rehearse.sh,fixtures/synthetic-run-records/reports/spark-run-manifest.json}`, `experiments/b-burst/{run.sh,README.md}`.

- N1: new `spark_output_rows` reads `outputs[].table` / `outputs[].rows` (`CollaborationPipeline.scala:1276-1279`); a `tables` key is never read for Spark; `manifest_rows` object-only for the export manifest `tables.<name>.rows`; `row_count` accepts whole numbers and integral floats, rejects booleans and negatives. Fixture moved to the real shape, same numbers (120 + 7 = 127). "UNVERIFIED" wording removed.
- N2 (P15-20): `run.sh` comment says the cluster is still terminated and cost bounded, but the teardown check can take up to 300 s against about 2 minutes of margin, so the 90-minute entry limit may cut `window.json` and the last events; same limitation added to the README. `exp_run_pipeline` still only on the call line.
- N4: redaction example builds a COPY under `${COPY}` (`collected/`, `window.json`, `pipeline-run.log`, `pipeline/*.record.env`), `sed -i.bak` over every file in the copy, `.bak` cleanup and a `grep -r` check that must print nothing; raw files only read.
- N5: README run command `--cost-ceiling-usd 20` became `2.40` (B manifest `SPEND_CEILING_USD`; projection 1.55 x 90 / 60 = 2.325). Agent asks the human to confirm 2.40.

Rehearsals: A gains 5 checks (`records.spark-manifest-rows-per-table`, `records.spark-manifest-source-shape`, `legacy-tables-shape.not-recorded`, `.no-total`, `.no-per-table`), expected **49** (was 44). B unchanged, expected **59**.

Not checked: whether the harness pins the old comment, README text or fixture shape (grep refused).
