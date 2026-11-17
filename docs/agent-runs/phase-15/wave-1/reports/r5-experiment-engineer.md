# R5 report, experiment-engineer (received 2026-11-17; summary of the report as received; author: experiment-engineer subagent)

Status: COMPLETE for items 1 to 7; nothing executed (guard allows only `ls`). Expected counts derived by reading.

## Values
| Setting | Before | After |
|---|---|---|
| `b-burst/run.sh` pipeline call | `exp_run_pipeline medium 74 2.00 1.55` | `exp_run_pipeline medium 88 2.30 1.55` |
| `MAX_MINUTES` | 80 | 90 |
| `SPEND_CEILING_USD` | 2.10 | 2.40 |
| `HOURLY_BURN_USD` | 1.55 | 1.55 (4 x 0.35 + 8 x 0.015 + loader 0.0247) |
| `METRIC_NAMES` | no ratio | adds `ContainerPendingRatio` |
| `DESCRIPTION` | `...core1-max4...` | `...core1-to-3...` |
Projected 2.27 USD (88 min) and 2.325 USD (90 min), under 2.30 and 2.40. New pipeline and experiment approval records needed.

## Items
- M3 `derive-capacity.py`: reads the managed-scaling policy and reports its bounds; `ContainerPendingRatio` added; each later non-primary instance classed `demand-linked` (pending containers or ratio > 0 from 600 s before to 300 s after the request, not a floor restore; the only class counted as scale-out), `floor-restoring` (below the policy minimum; never counted), `no-pending-evidence`, `demand-unknown` (both reported as `increase_without_demand_evidence`); `scale_out_observed` = yes/unconfirmed/no; replaced lost nodes reported as `ended_and_replaced_instances`, not scale-in; `scale-events.csv` gains `classification`, `pending_containers_max_near`, `pending_ratio_max_near`.
- M4 `derive-table.py`: input records = MR "Map input records" summed over raw-input jobs only (`dblp-extraction`, `source-normalization/*`), per-job values listed, all-jobs sum flagged; `INPUT_LINES_*` labeled line counts only; output records from `reports/export-manifest.json` (`tables.<name>.rows`, verified in `JsonlExport.scala`) and `reports/spark-run-manifest.json` (shape UNVERIFIED, tolerant parser); missing gives NOT RECORDED.
- M5 / P15-17: `experiments/lib/manifest-json.py` (`generate`, `check`; parsed comparison), `experiments/check-manifest-json.sh` (all pairs; fails on missing, orphan, duplicate or added key; `--generate`, `--manifests-dir`, `--json-dir`); six copies in `experiments/manifests/json/` (hand-written; the check confirms); rule documented in `experiments/README.md`.
- m5 `collect.sh`: writes to `collected.staging/`, renamed to `collected/` only after all required calls and checksums; a failed required call keeps `collect-failed.<stamp>/` and can be repeated; `get-managed-scaling-policy`, `list-steps`, `list-metrics` optional with `CALL_STATUS.txt` and `<name>.error.txt`.
- m1, a2, a3: stale README text fixed; `.crc` behaviour UNVERIFIED in limitations; redacted copies (12-digit ID to `000000000000`) before any results commit, raw files unchanged.

## Files
Modified: `experiments/b-burst/{run.sh,derive-capacity.py,collect.sh,rehearse.sh,README.md}`, `experiments/a-baseline/{derive-table.py,rehearse.sh,README.md}`, `experiments/manifests/experiment-b-burst.manifest`, `experiments/README.md`, fixtures in `b-burst/fixtures/synthetic-scaling/collected/`. Created: `experiments/check-manifest-json.sh`, `experiments/rehearse-manifest-json.sh`, `experiments/lib/manifest-json.py`, `experiments/b-burst/rehearse-collect.sh`, fixtures `synthetic-floor-restore/`, `synthetic-unconfirmed/`, `a-baseline/fixtures/synthetic-run-records/`, `experiments/manifests/json/*.json` (6).

## Expected
`b-burst/rehearse.sh` pass=59; `a-baseline/rehearse.sh` pass=44; `b-burst/rehearse-collect.sh` pass=25; `rehearse-manifest-json.sh` pass=10; `check-manifest-json.sh` `MANIFEST_JSON_CHECK OK manifests=6`.

## Risks
Harness may pin old B constants or `collect.sh` structure (R7 updated B helpers to 90/88/2.30); `run-experiment.sh` may cap `METRIC_NAMES` length (unchecked); B pipeline stop 88 min leaves about 2 min before the 90-min entry limit; `ContainerPending` metric name unverified; `get-managed-scaling-policy` on a terminated cluster unverified. Report names match R4 (`reports/spark-run-manifest.json`, `reports/export-manifest.json`).
