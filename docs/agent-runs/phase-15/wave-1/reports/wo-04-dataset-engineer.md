# WO-4 report, dataset-engineer (received 2026-11-17; preserved as received; author: dataset-engineer subagent)

Status: BLOCKED-ON the human `!` path (or a factory-engineer ratified interpreter allowance). Nothing executed: no hashes, no generated files, no validation results.

## Blocker
The guard refused `python3 -B datasets/scripts/datasets.py validate`, `python3 -B -I ...` and bare `python3 ...` ("Run reviewed repository scripts directly. Shell and interpreter wrappers are blocked."). Direct execution fails with exit 126 (scripts not executable); `chmod`, `grep` and `find` are outside the role's permitted commands. No workaround attempted; no hand-estimated hashes written.

## Gate items and proving checks
| Gate item | Proving check |
|---|---|
| Snapshot ID matches the data actually run | `chunkset.py snapshot` builds `tier-s/snapshot-s.json` from the registered `phase5-dblp-subset` files, re-hashing and counting rows; `chunkset.py validate` rebuilds and requires an exact match; `test_chunkset.py::RegisteredFilesTest` expects 3,245,083 bytes and 4,219 rows |
| ID is a legal `DATASET_SNAPSHOT` token | `SAFE_ID` regex (`A-Za-z0-9._-`, at most 64) in `validate`, plus a unit test |
| ID changes with content, not member order | `test_snapshot_id_changes_with_content_and_is_a_safe_token` |
| 2,000 chunks drawn deterministically | `test_selection_is_deterministic_and_order_independent`; `replay` against `chunks.jsonl` and the manifest sha256 |
| Rows verbatim and traceable to the export | `test_selected_lines_are_verbatim`; `load_export` checks each part file's bytes and sha256 against `export-manifest.json` |
| Bad input rejected | tests for duplicate `summaryId`, missing required key, tampered part file, wrong row total |
| Manifest states source, rule, count, bytes, sha256 | `validate` re-derives all from `chunks.jsonl`, checks composition and rank order |
| Existing manifest unaffected | `datasets.py validate` (`dataset-manifest.yaml` untouched) |

## Files created (unexecuted)
- `datasets/scripts/chunkset.py` (commands `snapshot`, `chunks`, `validate`, `replay`; stdlib only, no network or AWS)
- `datasets/scripts/test_chunkset.py` (8 fixture tests plus 1 repository test that skips until the files exist)
- Planned outputs: `datasets/tier-s/snapshot-s.json`, `datasets/tier-s/chunks-c/chunkset-manifest.json`, `datasets/tier-s/chunks-c/chunks.jsonl` (about 3 MB, under the 8 MiB bound)

## Design (from read evidence)
- Snapshot ID format `scholarchain-s-20261107-<12 hex>`; the hex from a hash over `{dataset, bytes, rows, sha256}` of the four files: DBLP subset 2,111,266 bytes, 3,472 works, `9076e857...ce28c7d`; OpenAlex 940,131, 396, `fdeddfa3...a062a9`; Crossref 174,011, 251, `c403ccbf...cd368`; ORCID 19,675, 100, `719a1cdb...5c5`. Totals 3,245,083 bytes, 4,219 rows. Hashes equal the `WOULD_UPLOAD` sha256 values in `phase14-upload-7.log`. The ID labels content, not an S3 prefix (Phase 14 keys `raw/phase5-dblp-subset/<file>`).
- Chunk source: `/Users/winnguyen/scholarchain-data/rehearsal/s-export/semantic-summaries/part-0000{0,1,2}.jsonl` (41,885 rows; 31,833,231, 31,902,940 and 3,007,931 bytes), re-verified against `export-manifest.json` on every build and replay.
- Selection: rank rows by SHA-256 of `phase15-chunkset-c-v1|summaryId`, ties by `summaryId`, keep the smallest 2,000, write verbatim in rank order. Mix follows the export (mostly researcher profiles); the manifest records the exact composition.

## Paste-ready commands for the human (no AWS; run from /Users/winnguyen/ScholarChain)
```
set -o pipefail; python3 -B datasets/scripts/chunkset.py snapshot 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-1.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/chunkset.py chunks --export-dir /Users/winnguyen/scholarchain-data/rehearsal/s-export --built-on 2026-11-16 --pipeline-run-id run-v1-192de0fd-1442-4508-9065-09c44775643b 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-2.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/chunkset.py validate --export-dir /Users/winnguyen/scholarchain-data/rehearsal/s-export 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-3.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/chunkset.py replay --export-dir /Users/winnguyen/scholarchain-data/rehearsal/s-export --output /Users/winnguyen/scholarchain-data/phase15-chunk-replay 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-4.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/datasets.py validate 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-5.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py' 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-6.log | tail -n 60; echo "exit: $?"
```
Then resume dataset-engineer to read the logs and report the ID, sha256 values and counts.

## Open items and risks
- Run ID mismatch: `export-manifest.json` names `sourceRunId` `run:v1:9f6e6fb0-...`; the pipeline run is `run-v1-192de0fd-...`; `s-6/reports/export.log` shows the export ran under 192de0fd with 9f6e6fb0 as the inner Spark run. The pipeline run ID is passed by hand and recorded as operator-supplied.
- The 3,472 / 4,118 / 3,556 work-count mismatch is stated as a limitation in the snapshot file, not resolved.
- Experiment C manifest has `MAX_TASKS=5000` (tier M assumption); a 2,000-chunk set needs `MAX_TASKS=2000` (experiment-engineer).
- `dataset-manifest.yaml` not changed (`validate()` rejects unknown top-level fields); registering there is a separate change.
- Whether the ~3 MB chunk file is tracked in git is the human's decision at the wave commit.
- Not verified: every hash and count of generated artifacts, the tests (0 run) and the replay.

## Human execution (2026-11-17, `!` path, logs `phase15-wo4-1.log` to `-6.log`; recorded by the orchestrator)
| Step | Output | Exit |
|---|---|---|
| snapshot | `snapshot_id scholarchain-s-20261107-10dd7f34121b` | 0 |
| chunks | `chunks 2000 3179893 1ccaefcb892b728b4ed6f97207d29827edca912df970d9c6b47c0f957d220c92` | 0 |
| validate | `OK snapshot scholarchain-s-20261107-10dd7f34121b chunks 2000 1ccaefcb...220c92 export-replay` | 0 |
| replay | `REPLAY_OK rows 2000 sha256 1ccaefcb...220c92` | 0 |
| datasets.py validate | `Validated manifest, sample checksums and row counts: dblp, openalex, orcid, crossref` | 0 |
| unittest discover | `Ran 99 tests in 1.740s` `OK` (ResourceWarnings from existing HTTP-retry tests only) | 0 |
