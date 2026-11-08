# Phase 05 subset run evidence (human-run commands)

Commands were run by the human architect with the `!` prefix or in a plain terminal. Logs are in `target/p5-logs/` (originals preserved, none modified). Recorded by the orchestrator, 2026-11-07.

## Run 1 (before the per-name cap repair)

| Command | Log | Result |
|---|---|---|
| unit tests | not saved | 75 passed (human report) |
| `subsettool.py enrich` | p5-enrich.txt | live requests 416/500; DOIs 3313; OpenAlex 396, Crossref 251, ORCID 100; exact-ID DOIs 354; failures 0 |
| `subsettool.py register` | p5-register.txt | registered phase5-dblp-subset, dblp 3486 rows `192f864e...` |
| `datasets.py validate` | p5-validate.txt | FAILED: `a seed name exceeds its per-name cap` |

Diagnosis by dataset-engineer: `dataset-per-name-cap-report.md`. Cause: the extractor capped per-seed heaps while the file is their union; works mentioning two seed names pushed Wei Wang to 206, Yang Liu 205, Jun Wang 202, Lei Zhang 201 (cap 200). The coauthor pass was not involved. Fix: one definition (works in the file mentioning the name), cap unchanged at 200.

## Run 1b (after the repair, before re-extract)

`v2-unittest-full.txt` (human): Ran 80, failures=1, errors=8, all `spec SHA-256 mismatch` (registered manifest predates the spec edit). Diagnosis by dataset-engineer: `dataset-unittest-round2-report.md`. Expected transitional state; no code or test change.

## Run 2 (after the repair; human, all passed)

| Command | Log | Result |
|---|---|---|
| `subsettool.py extract` | v2-extract.txt | dump verified (1116868681 bytes, SHA-256 `356634eef5696aef...`, release 2026-11-05); works total 3472 (seed 1972, coauthor 1500); subset 2111266 bytes, SHA-256 `9076e8574643fa6f64619aa912956ed0ddef60f8d0634ae4be218b0edce28c7d`; both scans read 8,848,160 records to end-of-dump; dropped total-cap 0, coauthor-cap 396293, unknown-entity 0, non-ASCII 0 |
| `subsettool.py enrich` | v2-enrich.txt | live requests 0/500 (all cached); DOIs 3299; OpenAlex 396, Crossref 251, ORCID 100; exact-ID DOIs 354; failures 0 |
| `subsettool.py register` | v2-register.txt | registered phase5-dblp-subset: dblp 3472 rows `9076e857...`, openalex 396 rows `fdeddfa3...`, crossref 251 rows `c403ccba...`, orcid 100 rows `719a1cdb...` |
| `datasets.py validate` | v2-validate.txt | Validated manifest, sample checksums and row counts: dblp, openalex, orcid, crossref |
| unit tests | v2-unittest-after.txt | Ran 80 tests, OK |

The openalex/crossref/orcid hashes are identical to run 1 because the enrichment cache is keyed by DOI/ID and the DOI set that has enrichment did not change.

## Snapshot provenance note (human instruction)

The extract output shows `registered_snapshot_match: false` and `dtd_md5_matches_registered: false`. **The subset derives from the DBLP release 2026-11-05 dump** (human-downloaded, SHA-256 `356634eef5696aef7ba4d07d864257ddcacae55c95f64a755570801df0a82fa1`, 1116868681 bytes), while the manifest's registered DBLP dataset entry is the 2026-11-01 snapshot. The subset is therefore not byte-identical to the registered DBLP snapshot; the receipt records that the release is not the registered one. Downstream provenance (Phase 4 `datasetSnapshots`, gold-label snapshot fields) must cite the 2026-11-05 subset, not the 2026-11-01 entry.

## Log loss (recorded 2026-11-07)
`target/p5-logs/` no longer exists. The human's `sbt clean check` deletes `target/`, which held every log named above (p5-*.txt, v2-*.txt, including v2-unittest-full.txt and any v2-pipeline.txt). The contents quoted in this file were read by the orchestrator before the clean and are the surviving record; the original files are gone. Future logs go outside `target/` (for example `/Users/winnguyen/scholarchain-data/logs/`).

## Phase 4 pipeline on the subset
Human reports the run done (log not recoverable, see above). The orchestrator listed the output tree `/Users/winnguyen/scholarchain-data/phase4-out/subset-v2/run=run-v1-05d7662a-9742-4130-be50-16805576e078/snapshot=26a3ad334ddf/`: it holds only `stage=dblp-extraction` (works, venues; `_SUCCESS`) and `stage=source-normalization` (crossref, openalex, orcid; each `_SUCCESS`; crossref has one rejects part, MISSING_REQUIRED_FIELD). There is no blocking, pair or `match-candidates` stage. The pipeline either stopped early or failed after stage 2; cause unknown without the log.

## Log restoration (human, 2026-11-07)
The human restored the v2 dataset-command outputs into `.p5-logs/` (repo root, untracked, git info/exclude): README.txt, v2-extract, v2-enrich, v2-register, v2-validate, v2-unittest-before, v2-unittest-after. Their README states they were restored by the Cowork assistant from terminal output the human pasted, not recovered byte-for-byte from the original files. The orchestrator checked file sizes against the earlier reads (extract 654, enrich 101, register 360, validate 85, unittest-after 26 bytes): identical. `v2-unittest-before.txt` is a summarized 338-byte version, not the original 13799-byte full log. `v2-unittest-full.txt`, `p5-*.txt` and `v2-pipeline.txt` were not restored.

## Phase 4 pipeline v3 (human run, alone): FAILED at stage 3
Log: `/Users/winnguyen/scholarchain-data/logs/v3-pipeline.txt`; output `/Users/winnguyen/scholarchain-data/phase4-out/subset-v3` (run `run-v1-d14f8b24-7559-4112-b7c5-ec553ef1e96e`, snapshot `26a3ad334ddf`). DBLP extraction and the three source normalizations ran. The identifier-join stage failed at submission, 6 s after start: `InvalidInputException: Input path does not exist: .../stage=source-normalization/dataset=orcid/works/v1` (PipelineDriver.scala:114, via `IdentifierJoinJob.configure(run, workPaths(run, datasets))`). The trailing `NoSuchFileException ... target/bg-jobs/.../hadoop-client-api-3.5.0.jar` in the log is a shutdown-hook error after the failure (sbt background-job temp jars removed), not a second defect.
Probable cause (hypothesis, under confirmation by hadoop-engineer): the driver assumes every external dataset writes every normalized artifact; ORCID normalizes only researchers, Crossref has no topics/institutions. Same stopping point as the earlier v2 run. Routed to hadoop-engineer (work order in the Agent call 2026-11-07).

## Phase 4 pipeline v4 (human run, alone, after the driver fix)
Log: `/Users/winnguyen/scholarchain-data/logs/v4-pipeline.txt` (62 lines: sbt banner, the `running scholarchain.hadoop.jobs.PipelineTool ...` line and Hadoop LocalJobRunner WARNs only; no `ScholarChain pipeline failed at stage` line, no exception; the file has no trailing `[success]` line, cause unknown, probably how the tee captured sbt's final message). Output: `/Users/winnguyen/scholarchain-data/phase4-out/subset-v4/run=run-v1-c9952ced-c040-4db3-a350-03ddc92c7ec5/snapshot=26a3ad334ddf/`. Orchestrator listing: all seven stage directories exist (dblp-extraction, source-normalization, identifier-join, blocking, candidate-features, team-expansion, graph-export); `candidate-features` and `graph-export` each carry `_SUCCESS`; `candidate-features/match-candidates/v1` exists. Counters and record counts have not been read yet (the PipelineTool prints none; to be derived from the part files by entity-resolution-engineer).

## Phase 4 pipeline v5 (snapshots pinned, human run, alone)
Log `/Users/winnguyen/scholarchain-data/logs/v5-pipeline.txt`: 62 lines, no failure line (read the tail; only WARNs). Output `/Users/winnguyen/scholarchain-data/phase4-out/subset-v5/run=run-v1-d4722bc7-0d02-42da-bdb6-088ee508a143/snapshot=8e1be6626420/` (the digest differs from the default `26a3ad334ddf`, so the `-Dscholarchain.run.snapshots` flag took effect). The first exported pair carries `snapshotId` `dblp:2026-11-05` on both sides.

## Export (human run, alone)
Log `/Users/winnguyen/scholarchain-data/logs/export.txt`, `[success]` in 6 s. Seed `scholarchain-er-phase5-v1`, total 230. Records indexed 25,686; candidates read 362,905; distinct pairs 311,249. Strata: exact-id selected 77 of 77 (eligible 3,332); hard-common-name 77 of 77 (eligible 57,214); clear-non-match 76 of 76 (eligible 240,579); no shortfall. Excluded: unclassified 4,358; same-name-agreeing-context 5,766; no missing-record exclusions. Files: `/Users/winnguyen/scholarchain-data/gold/phase5/pairs.jsonl`, `private/strata-key.jsonl`, `private/export-manifest.json`.

## Suggestions (external)
Human supplied `/Users/winnguyen/scholarchain-codex-suggestions.csv` (source: Codex), outside the repo. Orchestrator read the first 11 lines: header exactly `pair_id,suggested_label,reason`; labels same/different/unsure (all accepted by the tool); reasons such as "Matching ORCID". Rows were not otherwise audited here; the tool refuses malformed rows and reports unknown pair_ids at load. The gold file will record the suggestion source as the file name and its SHA-256 (the "Codex" origin is carried by the file name only). Suggestions are hidden by default in the tool and never feed matching.

## Dataset repair round 1 checks (human run, 2026-11-07, after the dataset-engineer's source-URL and README repair)
- Python unit tests: `Ran 86 tests`, `OK` (80 before plus 6 new RegisteredSubsetTest/CheckedInTest methods).
- `datasets.py validate` on the hand-edited manifest: `Validated manifest, sample checksums and row counts: dblp, openalex, orcid, crossref`.
- `subsettool.py register`: `registered phase5-dblp-subset: dblp 3472 rows 9076e8574643fa6f64619aa912956ed0ddef60f8d0634ae4be218b0edce28c7d, openalex 396 rows fdeddfa3..., crossref 251 rows c403ccbf..., orcid 100 rows 719a1cdb...` (digests identical to the earlier registration; the subset files are unchanged).
- `datasets.py validate` after `register`: same `Validated manifest...` line.
- `git diff --stat -- datasets/` (tracked files, against HEAD): README.md +112, dataset-manifest.yaml +180, scripts/datasets.py +8, no deletions; untracked new files (subsettool.py, test_subsettool.py, slicetool.py, test_slicetool.py, datasets/subset/, datasets/slice/) do not appear in `git diff`.
- Download date: `stat` on `/Users/winnguyen/scholarchain-data/dblp/dblp.xml.gz` gave a modification time of 2026-11-07 (the file's local mtime, not a recorded download time). The human ran `register --dblp-retrieved 2026-11-07` (same subset digests printed) and `validate` passed again, so the manifest now records `retrieved: 2026-11-07`, inferred from the file mtime.
- Still open: whether `register` rewrote the manifest byte for byte as hand-edited before the retrieval date was added; the release-specific DBLP URL remains `UNVERIFIED`.
- Crossref privacy check (human): `grep -c affiliation datasets/subset/crossref.jsonl` printed `0`, so no Crossref row carries an `affiliation` key and the dropped read should not change Phase 4 output.

## Phase 4 pipeline v6 (after hadoop repair round 1; human run, alone)
Command: PipelineTool with `-Dscholarchain.run.snapshots=dblp=2026-11-05,openalex=2026-11-07,crossref=2026-11-07,orcid=2026-11-07` and `-Dscholarchain.run.id=run:v1:d4722bc7-0d02-42da-bdb6-088ee508a143` (v5's run id), output `/Users/winnguyen/scholarchain-data/phase4-out/subset-v6`, log `/Users/winnguyen/scholarchain-data/logs/v6-pipeline.txt`. The log's new summary line reads: run `run:v1:d4722bc7-...`, datasets crossref,dblp,openalex,orcid, snapshots as pinned, output `.../subset-v6/run=run-v1-d4722bc7-.../snapshot=8e1be6626420` (same digest as v5); no default-snapshot WARNING, no `not started` or failure line; the log has no closing `[success]` line (as for v4 and v5). Comparison: `diff -rq` of `subset-v5` and `subset-v6` printed nothing, so v6 is byte-identical to v5: the Crossref affiliation drop (the Crossref subset has no `affiliation` key: `grep -c` printed 0), the PipelineTool strictness and the other hadoop repair changes did not alter the Phase 4 output for this subset. The pinned run id made the provenance runId identical.

## Open
- Human labels the 230 pairs; then gold-set evaluation (needs at least 200 human-reviewed pairs); independent code review; remaining archives; DblpEntities vs dblp.dtd diff.
