# RB1-A report (experiment-engineer), received 2026-11-18, archived as received (pre-sbt)

Status: **READY-FOR-SBT**. Written, not compiled or run. Owner: experiment-engineer. Work order: RB1-A (findings 1-6, 9, 11-14; P16-10 to P16-12). No commit, no AWS, no Python, nothing under `tests/**`. Writes inside `experiments/evaluation/**` and `experiments/benchmark/**`. Line numbers approximate (find/grep refused).

## Per item: change and test

**1 / finding 1, coauthor labels.** `benchmark/BenchmarkGen.scala:175-195` uses every `edges-coauthored` row (`coauthor:v1:` and `mention-coauthor:v1:`), ordered as the runtime orders them, de-duplicated per coauthor. Researcher lookup lists up to 25 coauthors (was 10); coauthor lookup uses subjects with 2 to 25 coauthors (was 2 to 15); community members cap 30 (was 20), cross-edge neighbours 25 (was 10), through `RuntimeLimits` in `benchmark/BenchModel.scala`. Tests (`BenchmarkGenSuite`): "coauthor labels include mention-coauthor edges and coauthor edges", "a researcher lookup lists every coauthor up to the runtime limit", "a coauthor lookup labels all 25 coauthors and skips a subject with 26", "a community label lists every member of the largest allowed group".

**2 / finding 2, ranking ties and grades (P16-10).** `ranking` (`BenchmarkGen.scala:408-435`) returns exactly N entries, or `None` when place N ties place N+1, fewer than N are ranked, or the top value is not positive. Ties inside N share a rank. Grades from `Grades.ranking`: 1-3 -> 3, 4-6 -> 2, 7-10 -> 1. Tests: "the ranking tiers are 3 for ranks 1-3, 2 for 4-6, 1 for 7-10", "ranking labels grade by the tiers 3 / 2 / 1 and hold exactly N items", "the first generated ranking of the fixture has ranks 1,1,3,4,4", "a ranking of ten has grades 3,3,3,2,2,2,1,1,1,1 and ten items", "a tie that crosses the cut-off N is refused, a tie inside N is kept", "no generated ranking has a tie at its cut-off"; the existing flat-ties test kept.

**3 / finding 3, absent-pair path questions.** Label `abstain=false`, `abstainClass=None`, `extra.pathStatus="NotPrecomputed"`, `extra.acceptedOutcomes=["path-status:NotPrecomputed","abstain"]`; README states the scorer accepts either. Tests: "a path question labels the hop edges, an absent pair is NotPrecomputed"; `AbstentionMappingSuite` runs an absent pair (Ada, Gus) through the real workflow and asserts a `path-status` item of kind `NotPrecomputed`.

**4 / finding 4, abstention mapping.** Committed table `AbstentionCodes.byClass` plus `RuntimeCodes` (12 codes) in `BenchModel.scala`; every abstain label carries `extra.expectedCodes`. Template 8 (future-window researcher lookup) dropped: the identity record carries it past the evidence stage, so it never abstains; replaced by "Who are the coauthors of K between 2040 and 2049?" (coauthor-lookup, class `future-window`), reason in the README.

| Class | Runtime code |
|---|---|
| `unknown-researcher`, `ambiguous-name` | `identity-unresolved` |
| `future-window` | `no-deterministic-evidence` |
| `out-of-scope` | `planner-refused` |
| `unrankable-metric` | `researcher-count` |
| `missing-second-window` | `needs-two-windows` |

Tests: new `AbstentionMappingSuite` (DemoWorld researchers, all 8 unanswerable items through `CollaborationWorkflow` with the stub model in `graph` and `fused` modes, no AWS; pinned code per item; pinned codes equal `AbstentionCodes`; a future-window researcher lookup is not a `no-deterministic-evidence` abstention). `BenchmarkGenSuite` adds the table and `expectedCodes` checks. Not verified: pinned codes predicted from reading `Planner.scala`, `Resolver.scala`, `Workflow.scala`. Residual risks (README): `out-of-scope` code is the stub's (a real model on a rule miss may name an operation); a known researcher sharing an ORCID gives `identity-unresolved`; vector mode is outside the mapping.

**5 / finding 5, FactoryCounts aliases.** `agentsIn` matches full role names, or a role or alias inside an explicit `Owner:` / `Owners:` tag; new `agentsInName` keeps aliases for document file names. Tests: "a sentence that mentions an alias word does not attribute a finding", "aliases still attribute a document by its file name".

**6 / finding 6, first-pass evidence rows.** One `first-pass-acceptance` row per (scope, agent) pair (`file` = first-round review file; `detail` names attributing documents, review files, counts); `first-pass-no-review` rows for pairs without review; counts `firstPassPairs`, `firstPassPairsReviewed`, `firstPassPairsWithoutReview`. Tests: "each (scope, agent) pair has one evidence row naming its documents", "a delivered pair with no review round still has an evidence row".

**7 / finding 9, prose counters.** Events merged within a phase by the first explicit id, else normalised text. Counts: `blockedOnEvents`, `blockedOnEventLines`, `ownershipViolationsCaught`, `ownershipViolationLines`, `hookRefusalEvents`, `hookRefusalEventsByClass`, `hookRefusalStateChangingUpperBound`; `proseCounterBasis` labels bounds; `classifyRefusal` into `harmless-read`, `state-changing`, `unclassified` by keywords. Tests: "one BLOCKED-ON event written in three files counts once", "one ownership violation quoted by a report counts once", "hook refusals are classified and the copies merged".

**8 / finding 11, GraphChecks.** `incomplete` flag and JSON `status` `COMPLETE`/`INCOMPLETE`; with a required table missing the CLI writes and seals the run directory, prints `GRAPHCHECKS INCOMPLETE: …`, exits 3 (`IncompleteExit`). New counter `supportRowsToMissingEdges`. `danglingEndpointRate` denominator = 2 x `edges-coauthored` rows + `edges-authored` rows. Tests: "a missing required table makes the run incomplete", "the command line exits 3 with an INCOMPLETE line on a missing table", "support rows to edges that do not exist are counted", "the dangling endpoint rate counts rows, not distinct edge ids".

**9 / finding 12, VectorCoverage.** `describe` checks sha256, bytes and rows of each `source.files` entry against `chunkset-manifest.json`, total `source.rows`, deleted rows by `composition.by_window`. New `VectorCoverage.subjectIdOverlap` / `SubjectOverlap`, written into the manifest as `subjectIdOverlapWithVectorExport`. Tests: new `VectorCoverageSuite`, 7 tests.

**10 / finding 13, manifest.** `GeneratorState` records `generatorCommit`, `generatorTreeDirty` (true/false/null), `generatorDirtyBasis`; flags `--generator-commit` and `--generator-dirty yes|no` win, otherwise `git rev-parse HEAD` and `git status --porcelain`. Versioned name `BENCHMARK-MANIFEST-v1.json` kept; README says plan D4 is amended. Tests: "the manifest records the commit and whether the tree was dirty", "the written manifest carries the dirty flag fields".

**11 / finding 14, questions file.** README "Questions file": `stratum` stays only while RetrievalEval and AnswerEval send `text` alone; the pinning test lands with slice 2.

## API changes
No signatures changed except a new `GraphCheckReport` field. `ranking` returns exactly N or `None`; ranking grades tiered; absent path label non-abstaining with `pathStatus` and `acceptedOutcomes`; unanswerable labels carry `expectedCodes`; template 8 is coauthor-lookup `future-window`; class `path-not-in-evidence` removed. New symbols: `Grades`, `RuntimeLimits`, `AbstentionCodes`, `GeneratorState`, `SubjectOverlap`, `VectorCoverage.subjectIdOverlap`, `GraphChecks.IncompleteExit`, `GraphCheckReport.incomplete`, `FactoryCounts.agentsInName`, `FactoryCounts.classifyRefusal`. Positional: `GraphCheckReport.supportRowsToMissingEdges` right after `jointCountMismatches`. `agentsIn` no longer matches bare alias words.

## Files
Modified: `experiments/evaluation/src/main/scala/scholarchain/evaluation/benchmark/{BenchmarkGen,BenchModel,VectorCoverage}.scala`, `.../graph/GraphChecks.scala`, `.../factory/FactoryCounts.scala`, `experiments/evaluation/factory-mapping.tsv` (comment only), `experiments/benchmark/README.md`, tests `BenchmarkGenSuite`, `GraphChecksSuite`, `FactoryCountsSuite`. New: `AbstentionMappingSuite.scala`, `VectorCoverageSuite.scala`.

## sbt update (received 2026-11-18 after the slot was granted, as received)
- First `sbt quick`: `[error] (evaluation / Test / compileIncremental) Compilation failed`, 160 s; `sbt Test/compile` showed 11 type mismatches in `BenchmarkGenSuite` (`.toMap("key")` on a `Vector[(String, ujson.Value)]` parsed as an implicit argument). Fix: `.toMap.apply("key")` in `BenchmarkGenSuite.scala` only; `sbt Test/compile` `[success]`.
- Final `sbt quick`: `[success] Total time: 157 s (0:02:37.0), completed Nov 18, 2026, 2:17:36 AM`. Last two totals: `Passed: Total 77, Failed 0, Errors 0` and `Passed: Total 1125, Failed 0, Errors 0`.
- From the test reports, 0 failures and 0 errors: `BenchmarkGenSuite` 26, `GraphChecksSuite` 16, `FactoryCountsSuite` 16, `VectorCoverageSuite` 7, `AbstentionMappingSuite` 6, `MetricsSuite` not read; test-engineer's `P16LabelRulesSuite` 29 tests (test-engineer counted 31 by reading; difference not investigated). No disputed failures. Abstention pins held in `graph` and `fused` modes on the first run.
- Not verified: real-data runs; harness and `p16-wo7.sh` (human); full `sbt check`; E029 warning in `GraphChecks.parseArgs` unchanged.

## Other
- Format hook failed once on `VectorCoverageSuite` (a value named `export`, a Scala 3 keyword); passed after renaming.
- No real-data run of GraphChecks, FactoryCounts or BenchmarkGen.
