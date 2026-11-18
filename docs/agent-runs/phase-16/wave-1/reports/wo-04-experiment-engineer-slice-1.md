# WO-4 slice 1 report (experiment-engineer), received 2026-11-18, archived as received

**Status: COMPLETE for slice 1.** Work order: Phase 16 WO-4 first slice. The sbt slot is released.

## sbt result

I ran bare `sbt quick` twice, with no other sbt process running.
- **First run:** failed to compile. `FactoryCounts.scala` used `DefaultSeverities` without the `Mapping.` qualifier. I fixed that one line in my own write set.
- **Second run:** `[success] Total time: 163 s (0:02:43.0), completed Nov 17, 2026, 11:53:54 PM`.
  - The last two totals printed were `Passed: Total 77, Failed 0, Errors 0` and `Passed: Total 1096, Failed 0, Errors 0`.
  - The 1096 compares with 1041 in WO-1's run.
  - No suite failed, and no test-engineer suite failed.
  - I did not read the per-project split, so I cannot say how many of the 1096 are the evaluation project's own tests. My four suites hold 51 tests by my count.
  - One compiler warning (E029, non-exhaustive match) on the argument loop in `GraphChecks.parseArgs`. The match is exhaustive in practice, so I left it.

## Decisions from the orchestrator's relayed points

1. **Path-evidence matching.** The premise that `edges-coauthored` only has `coauthor:v1:` ids is wrong for the real export. `export-manifest.json` lists 98,661 rows there: 79,178 with prefix `mention-coauthor:v1:` and 19,483 with `coauthor:v1:`.
   - The rule is an exact `edgeId` match, then `{fromId,toId}` against `{researcherA,researcherB}` in either order, then window equality.
   - Nothing is normalised. Each bad hop counts once in `pathHopsNotInEdges`. `detail` splits the reasons as `path-hop:edge-missing`, `path-hop:endpoints-differ` and `path-hop:window-differs`.
   - **Not verified on the real rows.** The first real `GraphChecks` run is the proof. If valid hops only match by endpoints, `detail` will show `edge-missing`, and I will report that.
2. **Fixture layout.** `<table>/part-00000.jsonl` plus `{"sourceRunId":…,"tables":{}}` is accepted. No manifest file entries or sha256 are required. Missing tables are listed in `missingTables`.
   - Id columns come from the manifest when it lists them, else built-in defaults. `path-evidence` is keyed on `(pairKey, hopIndex)`.
   - With no manifest `sourceRunId`, the expected run id is the most frequent `provenance.runId`.
3. **`jointCountMismatches`.** It does not count an edge with no support row. The two counters are disjoint: no support rows goes only to `edgesWithoutSupport`; one or more rows with a different distinct-work count goes only to `jointCountMismatches`.
   - `danglingEdgeEndpoints` counts only endpoints of `edges-coauthored` and `researcherNodeId` of `edges-authored`. Endpoints named in `edge-support` and `path-evidence` appear in `detail` only.
4. **BenchmarkGen API.** Below.

## Public APIs

All paths are under `experiments/evaluation/src/main/scala/scholarchain/evaluation/`.

- **`metrics/RetrievalMetrics.scala`**: `object RetrievalMetrics`: `recallAtK`, `precisionAtK`, `reciprocalRank`, `ndcgAtK` (names and signatures as fixed). A repeated id gains credit only at its first occurrence. `k < 1` throws.
- **`metrics/Bootstrap.scala`**: `Bootstrap.meanCi(values, resamples, seed, level): (Double, Double, Double)`.
- **`graph/GraphChecks.scala`**:
  - `GraphChecks.run(exportDir, sampleSeed, sampleSize): GraphCheckReport`; also `runWith(exportDir, sampleSeed, sampleSize, expectedVertices: Option[Long], expectedEdges: Option[Long])`, `cli(args): Int` and `main`.
  - `GraphCheckReport` Long counters: `danglingEdgeEndpoints`, `danglingWorkRefs`, `edgesTotal`, `edgesWithoutSupport`, `jointCountMismatches`, `pathHopsChecked`, `pathHopsNotInEdges`, `provenanceRunIdMismatches`, `provenanceMissing`, `orphanResearchers`, `orphanWorks`; plus `duplicateIds: Map[String, Long]`, `rowTotals`, `provenanceByTable`, `detail`, `reconciliation`, `missingTables`, `expectedRunId`, `runIdBasis`, `trace: Vector[TraceEntry]`, `traceIncomplete`; `toJson`. No NaN, so report equality works.
- **`benchmark/`** (`scholarchain.evaluation.benchmark`):
  - `object BenchmarkGen`: `generate(t: BenchTables, cfg: BenchConfig, coverage: VectorCoverage): BenchResult`; `writeOutputs(result, cfg, sourceRunId: String, exportManifestSha: Option[String], coverage, extra: Vector[(String, ujson.Value)], outDir: Path): Unit` (refuses to overwrite); `cli(args: Vector[String]): Int`, `main`.
  - `final class BenchmarkGen(t, cfg, coverage)`: `generate(): BenchResult`; `ranking(metric: String, window: String, n: Int): Option[(Vector[(MetricsRow, Double, Int)], Int)]`.
  - `object Wording`: `cleanName(String): Boolean`, `windowPhrase(String): Option[String]`.
  - `BenchConfig(seed = 20261117L, version = "v1", counts: Map[String, Int])`; default counts 7 researcher-lookup, 8 coauthor-lookup, 10 ranking, 6 shortest-path, 6 community-component, 5 temporal-comparison, 8 unanswerable, 8 free-form.
  - Label rules: items keyed on `subjectId`, path items on hop `edgeId`. Grades: researcher lookup 2 subject, 1 coauthors; coauthor lookup 3/2/1 by joint count (3+, 2, 1); ranking `N - rank + 1`, ties share a rank, mentions excluded; path 1 per hop; community or component 2 same group, 1 cross-edge neighbours (community only); temporal 2 subject. Unanswerable classes: unknown-researcher, future-window, ambiguous-name, out-of-scope, unrankable-metric, missing-second-window. Absent path pairs abstain with class `path-not-in-evidence`.
  - `BenchResult(items, shortfalls, freeFormSlots)`, `BenchItem(question, label)`, `Question(questionId, version, stratum, text)` (no operation, no label).
  - `Label(questionId, version, stratum, operation, subjectIds, window, windows, expected, vectorAvailable: Option[Boolean], vectorStatus, vectorItemsAvailable, vectorItemsTotal, source)`; `Expected(items: Vector[ExpectedItem(id, grade)], numbers, abstain, abstainClass, extra)`; `LabelSource(table, sourceRunId, rowSha, rows: Vector[SourceRef(table, file, line, rowSha, id)])`.
  - `BenchTables.load(exportDir)`; `trait VectorCoverage` (`status`, `describe`; `unknown`, `fromExports(vectorExportDir, chunksetManifest)`, `summaryIdOverlap(graphDir, vectorDir)`).
- **`factory/FactoryCounts.scala`**: `FactoryCounts.compute(runsDir, phases, mapping, rewrites): FactoryReport`; `Mapping.parse`, `Mapping.load`, `findings`, `agentsIn`, `cli`, `main`.
- **`io/ExportIo.scala`**: shared helpers `Digests`, `ExportFiles`, `J`, `Js`, `RunOutput`.

## Other files (under `experiments/`)
- Tests in `evaluation/src/test/scala/scholarchain/evaluation/`: `MetricsSuite`, `GraphChecksSuite`, `BenchmarkGenSuite`, `FactoryCountsSuite`.
- Data: `evaluation/factory-mapping.tsv` (committed mapping table), `evaluation/factory-rewrites.tsv` (the human's manual input).
- Docs: `benchmark/README.md` (label schema).
- Nothing written to `experiments/i-offline` or `tests/**`.

## Gate item to proving test

| Gate item | Test |
|---|---|
| Recall, precision, MRR, nDCG correctness | `MetricsSuite` |
| Bootstrap is seeded and follows its algorithm | `MetricsSuite` |
| Each GraphChecks counter | `GraphChecksSuite`, one corrupted fixture per counter |
| Path-hop matching rule | `GraphChecksSuite` |
| Provenance run id, manifest and modal fallback | `GraphChecksSuite` |
| Sealed run directory, written once | `GraphChecksSuite` |
| Seeded provenance sample | `GraphChecksSuite` |
| Labels from tables only, graded by the plan rules | `BenchmarkGenSuite` |
| Abstentions | `BenchmarkGenSuite` |
| Shortfalls reported, not failed | `BenchmarkGenSuite` |
| Vector coverage reaches the label | `BenchmarkGenSuite` |
| Deterministic output; questions carry no label | `BenchmarkGenSuite` |
| 8 free-form slots; outputs written once with a manifest | `BenchmarkGenSuite` |
| FactoryCounts counts, each with file and line | `FactoryCountsSuite` |

## Paste-ready human commands (as given by the agent; see the orchestrator note on output paths)

Ran by: human, or Claude in chat (P16-5). None touch AWS.

```zsh
cd /Users/winnguyen/ScholarChain
mkdir -p /Users/winnguyen/scholarchain-data/logs
set -o pipefail
sbt "evaluation/runMain scholarchain.evaluation.benchmark.BenchmarkGen /Users/winnguyen/scholarchain-data/rehearsal/a-export experiments/benchmark --seed 20261117 --version v1 --vector-export /Users/winnguyen/scholarchain-data/rehearsal/s-export --chunkset-manifest datasets/tier-s/chunks-c/chunkset-manifest.json --generator-commit $(git rev-parse HEAD)" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-benchmarkgen.log | tail -n 60; echo "exit: $?"
```

```zsh
cd /Users/winnguyen/ScholarChain
set -o pipefail
sbt "evaluation/runMain scholarchain.evaluation.graph.GraphChecks /Users/winnguyen/scholarchain-data/rehearsal/a-export experiments/i-offline/runs --seed 16 --sample 50 --run-id graphchecks-a-export-1" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-graphchecks.log | tail -n 60; echo "exit: $?"
```

```zsh
cd /Users/winnguyen/ScholarChain
set -o pipefail
sbt "evaluation/runMain scholarchain.evaluation.factory.FactoryCounts docs/agent-runs experiments/evaluation/factory-mapping.tsv experiments/i-offline/runs --rewrites experiments/evaluation/factory-rewrites.tsv --phases 1-15 --run-id factorycounts-1" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-factorycounts.log | tail -n 60; echo "exit: $?"
```

- A run directory that already exists is refused; use a new `--run-id` to rerun.
- BenchmarkGen refuses to overwrite `*-v1` files. A new `--version` makes a new set.
- Each run directory ends with `SHA256SUMS`.
- The 6 GB forked run has not been exercised yet.

## Not verified
- **Real-data behaviour.** None of the three entry points has run against the real export or the real `docs/agent-runs`. The path-hop rule, support coverage of `mention-coauthor` edges, and the vector coverage rebuild (including whether summaryIds match between the exports) are unproven until the human runs the commands.
- **Neptune counts.** Reconciliation with 32,225 vertices and 135,788 edges is informational only.
- **FactoryCounts review parsing.** Partial: explicit mapping rows for phases 5, 12 and 15 and an `auto` fallback elsewhere. Inline IDs inside a paragraph are missed. Review files that yield no finding are listed under `reviewFilesWithoutParsedFindings`. First-pass acceptance is per (scope, agent), not per work order, because verdicts batch work orders.
- **Test-engineer suites.** Included in the `sbt quick` that passed; individual results not read.
- **`sbt check`.** Not run.
- **Next slice.** `RetrievalEval`, `AnswerEval --diagnostic`, `AnswerAudit`, `ErExtras` and the G/H manifests are not started. Free-form labelling needs a small tool once the human fills the slots.

## Orchestrator note (not part of the report)
- The first delivery was replaced by the agent's reply to the Stop hook; this is the second delivery.
- Output location: the GraphChecks and FactoryCounts commands above write raw run directories into the repository (`experiments/i-offline/runs`). Phase rule: raw outputs are frozen outside the repository and redacted copies are committed. `human-commands.md` therefore uses `/Users/winnguyen/scholarchain-data/experiments/runs/phase16` as the output argument; the committed copies go to `experiments/results/i/` in WO-9. No code change needed (the path is an argument).
- `--generator-commit $(git rev-parse HEAD)` names HEAD while the generator is uncommitted; BenchmarkGen runs after the Wave 1 commit, or the manifest records the commit as dirty. Raised for review.
- FactoryCounts' first-pass acceptance is per (scope, agent), not per work order as the plan states; a stated deviation for review and for the results' threats section.
