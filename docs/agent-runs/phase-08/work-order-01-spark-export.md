# Work order 01: spark-engineer, JSONL export of semantic summaries

Owner: spark-engineer (modules/spark/**). Phase 8, decisions E2 and E9. Local only: no AWS, credentials, Terraform or deploys. No new dependency: Spark is already Provided in `sparkAnalytics`.

## Why
Storage has no Parquet reader (Phase 7 D6). The human ruled (E2): a small Spark export job converts the finished Phase 6 run's tables to JSON lines, which storage reads through its `TableSource` seam. The same run also closes the Phase 7 unverified-ID item (D4): it must print the run's `transformVersion` and count `mention-coauthor:v1:` edge IDs.

## Input
`/Users/winnguyen/scholarchain-data/phase6-out/spark/run=run-v1-4600a8f4-d438-4683-8852-db8743eb23f0/` (has `_SUCCESS`, `run-manifest.json`, `tables/`). Refuse a run directory without `_SUCCESS`. Read only; never modify the run directory.

## Required work
1. An export job (own `main`, small, bounded) that reads these tables of one finished run and writes JSON lines:
   - `semantic-summaries` (41,885 rows in the sample run): all columns, including the nested `provenance` struct and the array columns.
   - `nodes-work`: at least `canonicalWorkId`, `year`, `source`, plus provenance.
   - `researcher-metrics`: at least `researcherId`, `window`, `communityId`, plus provenance.
   Read `modules/storage/src/main/scala/scholarchain/storage/` (`Support.scala` and `Vectors.scala`, `Rows` and `SummaryFacets.fromTables`) READ-ONLY and make the JSON shape exactly what those accessors expect: column names, `null` handling, arrays as JSON arrays, the `provenance` object with the fields in `Rows.provenance`. Report the shape you chose. Do not edit storage.
2. Output goes to a separate output directory passed as an argument, never into the run directory. Suggested: `/Users/winnguyen/scholarchain-data/phase8-out/jsonl/run=<runId>/<table>/part-*.jsonl`. Output must be deterministic: rows sorted by their id column, stable field order, fixed file split, UTF-8, `\n` line ends, so a repeat run gives byte-identical files. Write an `export-manifest.json` beside them: source run id, source `_SUCCESS` seen, row count per table, bytes and sha256 per file, `transformVersion` read from the run, and an `exportVersion`. Write `_SUCCESS` last. Refuse to overwrite a non-empty output directory unless the existing manifest matches byte for byte.
3. Close Phase 7 D4. The job must print on the console AND record in `export-manifest.json`:
   - the run's `transformVersion` from `run-manifest.json`;
   - from `edges-coauthored`: total edges; the count whose `edgeId` starts with `mention-coauthor:v1:`; the count that starts with `coauthor:v1:`; the count of any other prefix; and whether any `coauthor:v1:` edge has a mention endpoint (a node ID starting with `mention:v1:`), since the Phase 7 loader rejects those. Read the endpoint column names from the table schema; do not guess.
   Use Spark aggregation; never collect the table on the driver.
4. Bounds: the export must not collect a table to the driver. Write with a bounded number of output files. Fail with a clear error on a missing table, missing column or unsupported type, not a silent drop.
5. Feature tests (every behavior change needs one), over tiny hand-built Parquet fixtures: round trip of every column type in `semantic-summaries` (strings, ints, arrays, nested provenance, nulls), determinism (two runs give identical bytes), refusal without `_SUCCESS`, refusal to overwrite a different output, correct prefix counts on a fixture with all three prefix kinds. If a new suite starts a local Spark session it is slow: name it in your report so build-engineer adds it to `slowSuites`.

## Before writing code
List which specific test proves each item above. Put the list in your report.

## Commands
Run `sbt quick` for work and repairs. No other sbt form (you cannot run per-module tasks). You cannot run the real-data export: the human does it. Do not run it. Instead report ONE paste-ready human command in this form, after checking how `docs/agent-runs/phase-07/` and `phase-06/` invoked earlier real runs:

```
! SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt "sparkAnalytics/runMain <main class> <runDir> <outDir>" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase-08-export.log
```

Adjust only what the earlier real runs show is needed, and state what the human should see on success.

## Not allowed
Editing modules/storage, tests/**, build.sbt, project/**, CLAUDE.md, docs/agents.md, .claude/**. Changing any existing Spark output or the pipeline's behavior. Rerunning the pipeline. A new dependency. Report any need as `BLOCKED-ON` with the owner.

## Report
Use your role file's report contract. Save nothing yourself under docs/agent-runs; the orchestrator archives your report as `report-01-spark-export.md`.
