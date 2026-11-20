# Repairs for review B12e, 2026-11-19 (condensed by the orchestrator)

## dataset-engineer, items 6 and 7 (as received)
- **Version lines:**
  - `dblp.md` now names `2026-11-01` as the registered snapshot (`manifest:16`) and adds the Phase 5 subset from release `2026-11-05` (`:388`; not registered `:386-387`; DTD MD5 mismatch `:381`; limitation `:532`).
  - `openalex.md`, `crossref.md` and `orcid.md` add that the subset rows are allowlisted projections of live API pulls, retrieval date 2026-11-07 (`:376`, `:532`).
- **UNVERIFIED cells:** "not read by `X.scala`; other use UNVERIFIED" in dblp (`mdate`, `pages`), openalex (`updated_date`, `topics[].score`) and orcid (`name.visibility`). Crossref has none.

## Orchestrator check: the "prose unchanged" item
`git diff --stat -- datasets/schemas/` gives crossref +37, dblp +38, openalex +40, orcid +29, `4 files changed, 144 insertions(+)`, **0 deletions**.

`git diff -U0` shows only two kinds of inserted hunk per file:
- the version block after line 2;
- a "## Field table" section near the end of the prose.

The existing prose is therefore unchanged. The UNVERIFIED cells are inside newly added tables, so they show as insertions, not as modifications.

## hadoop-engineer, items 1 to 5 (as received)
1. The digit/digit strings are gone: "1225, which is 50 times 49 divided by 2 (derived)" and "InputRecords 4, AcceptedRecords 3, RejectedRecords 1". Checked by re-reading, not by search (grep refused).
2. Encoding: `pipeline/TaskSink.scala:155-161` (`ContractWire.encode` at `:160`); rejects at `jobs/HadoopSink.scala:54` and `:101`; `:36` and `:88` write already-encoded strings.
3. `PartitionerWiringSuite.scala:26-66`.
4. Job 1 adds `MapReduceEndToEndSuite.scala:496-550`: Input 6, Accepted 3, Duplicate 1, Conflict 1, Rejected 4, SchemaFailure 2. Rejects for jobs 3, 4, 5 and 7 remain uncovered.
5. "No committed standalone local `hadoop jar` recipe exists", citing the B12e search result (`run-s-pipeline.sh:308-309`, `README.md:93`, `docs/demo-script.md:175`, `tests/scripts/README.md:116`).
