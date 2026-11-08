# hadoop-engineer report: pinning snapshot IDs through the pipeline (decision B)

Author: hadoop-engineer (archived by the orchestrator, condensed). Date: 2026-11-07. Edits only under modules/hadoop/**; no commit.

## Change
- `PipelineOptions.validateSnapshots(configuration, datasets)` added; `PipelineTool.run` calls it before building the RunContext. No new argument style: PipelineTool runs through ToolRunner and `PipelineOptions.runContext` already reads `scholarchain.run.snapshots` (`name=version,...`), so `-Dscholarchain.run.snapshots=...` placed before the positional arguments works. No output layout or wire format change.
- Fail-fast: nothing configured -> local defaults must cover every supplied dataset; anything configured -> set must name exactly `dblp` plus the supplied datasets; missing, extra, malformed or duplicated entries are errors (previously malformed entries were silently dropped).
- Files: PipelineOptions.scala, PipelineDriver.scala (modified); PipelineSnapshotSuite.scala (new, 11 tests). No existing test changed.

## Snapshot values (from receipts, not invented)
dblp 2026-11-05 (subset-receipt.json release); openalex, crossref, orcid 2026-11-07 (enrichment-receipt.json top-level retrieval_date; per-response retrieved_at 16:15Z-16:26Z read for crossref/orcid only, openalex responses not read individually). dataset-manifest.yaml not read. Old defaults used other forms (orcid 2026-10-13-v1, crossref 2026-03); plain dates used for all external datasets.

## Agent's `sbt check` (not the recorded gate)
`[success]`; hadoop `Passed: Total 188, Failed 0, Errors 0`; scalafmt passed. Output read in sections; the count line was the last line.

## Verification after the v5 run
1. `snapshot=<digest>` path segment differs from 26a3ad334ddf. 2. dblp-extraction works carry `dblp:2026-11-05`. 3. match-candidates provenance snapshotIds carry the pinned ids, never 2026-11-01 / 2026-10-14. 4. normalized openalex/crossref works and orcid researchers carry `<dataset>:2026-11-07`. 5. run manifest datasetSnapshots lists the four ids. 6. Output contains none of 2026-11-01, 2026-10-14, 2026-10-13-v1, 2026-03.

## Risks
Match-candidate provenance cites only the lower-ID member's dataset (existing behavior; may merit review). The run manifest provenance.snapshotId cites the alphabetically first dataset (crossref here); not changed. Full PipelineTool through sbt with `-D` has not been run; a missing flag would show as digest 26a3ad334ddf.
