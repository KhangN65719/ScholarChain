# Review B12e verdict on WO-B13 and WO-B14 (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator)

Verdict: REQUEST CHANGES. Every schema-table row checks out. About 150 JOB-CONTRACTS citations were spot-checked and almost all are correct; the derived figures (100, 142, 1225, 17, 9) check out.

## hadoop-engineer (`modules/hadoop/JOB-CONTRACTS.md`)
1. Two digit/digit strings: `:172` "50 x 49 / 2 = 1225" and `:237` "4/3/1". Reword both.
2. `:36` the encoding citation: the encoding happens at `pipeline/TaskSink.scala:155-161`, and for rejects at `jobs/HadoopSink.scala:54`.
3. `:236` widen the citation to `PartitionerWiringSuite.scala:48-66`.
4. `:237` add `MapReduceEndToEndSuite.scala:496-550` (real-job rejects: Input 6, Accepted 3, Rejected 4, SchemaFailure 2, Conflict 1) to job 1.
5. Optional, `:87, :253`: replace "search refused" with the reviewer's result. The only `hadoop jar` uses are in `scripts/emr/*`, `README.md:93`, `docs/demo-script.md:175` and `tests/scripts/README.md:116`.

## dataset-engineer (`datasets/schemas/*.md`)
6. The version lines: name `2026-11-01` as the registered snapshot, and add the subset sources:
   - DBLP subset release `2026-11-05`, with a DTD mismatch (`manifest:381, 386-388, 532`);
   - OpenAlex, Crossref and ORCID subsets from live API pulls dated 2026-11-07 (`:376, :532`).
7. "use UNVERIFIED" becomes "not read by `X.scala`; other use UNVERIFIED" (`dblp.md:47, 57`; `openalex.md:49, 61`; `orcid.md:50`).

## Readers of the schema notes
Only the manifest `schema` paths and `datasets.py:180` (existence only) read them, so the edits cannot break validation. This replaces B13's UNVERIFIED note.

## Pending
- `git diff -- datasets/schemas/` for the "prose unchanged" check (orchestrator).
- The human runs the dataset validate and unit tests (expected to pass).
- H-8.
- The scaladoc decision.
