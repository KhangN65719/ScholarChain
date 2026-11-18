# Benchmark v1 (Phase 16)

Questions and relevance labels for the retrieval and answer evaluation. Produced by `scholarchain.evaluation.benchmark.BenchmarkGen` from a local Spark JSONL export, with a recorded seed. No model and no prompt produces a question or a label, and the answering prompt never sees the labels.

Files (written once per version; the generator refuses to overwrite):

- `questions-v1.jsonl`: `{questionId, version, stratum, text}`. The system sees only this (see "Questions file").
- `labels-v1.jsonl`: one line per question, see below.
- `free-form-slots-v1.jsonl`: 8 slots for questions the human writes (plan H-5). Not labelled until the human names the intended operation, subject and window.
- `BENCHMARK-MANIFEST-v1.json`: sha256 and row count of each file, seed, generator version, `sourceRunId` and sha256 of the export manifest, requested and produced counts, shortfalls, the vector coverage checks, the generator commit and tree state.

The manifest name carries the version (`BENCHMARK-MANIFEST-v1.json`, `-v2` for a new version), so a second version never overwrites the first. Plan D4 and WO-4 name `BENCHMARK-MANIFEST.json`; the versioned name replaces it (decision recorded with the Wave 1 repair batch 1).

Label line:

```
{questionId, version, stratum, operation, subjectIds, window, windows,
 expected:{items:[{id, grade}], numbers:{...}, abstain, abstainClass, ...extra},
 vectorAvailable, vectorStatus, vectorItems:{available, total},
 source:{table, sourceRunId, rowSha, rows:[{table, file, line, rowSha, id}]}}
```

## Label rules

Items are keyed on `subjectId` (researcher id) or, for paths, on the hop `edgeId`, never on `summaryId` (the manifest records how far the summary ids and the subject ids of the graph export and the vector export overlap).

- **Researcher lookup.** Grade 2 = the subject, grade 1 = each coauthor of the subject in the window (up to 25). The 25 is a generator choice, equal to the coauthor-lookup limit; it is **not** a runtime limit of this operation. The runtime takes the default `retrieve` branch for a researcher lookup (`Workflow.scala:569-584`), which returns the subject's metrics only and no coauthors, so the grade 1 items are coauthors the runtime does not retrieve for this stratum (see "Threats to validity").
- **Coauthor lookup.** Every `edges-coauthored` row of the researcher and window, `coauthor:v1:` (two resolved researchers) and `mention-coauthor:v1:` (an unresolved mention on one side) alike: the runtime `coauthors-of` template matches every `COAUTHORED_WITH` edge (`Neptune.scala:1163-1183`), so a label without the mention edges would miss coauthors the runtime returns. One item per coauthor, ordered as the runtime orders them (joint count descending, then coauthor id). Grade 3, 2, 1 = joint publication count of 3 or more, 2, 1. A subject is used only when it has 2 to 25 coauthors in the window, so the label never holds more than the 25 the runtime lists (`PathLimits.coauthorLimit`, `Retrieval.scala:70`).
- **Ranking by metric and window.** The top N of one metric in one window, as the runtime ranks it (`Ports.scala:64-71`): unresolved mentions left out, ordered by value descending then researcher id, rank = 1 + the number of strictly larger values, so equal values inside the top N share a rank. The label holds **exactly N items**. A metric and window pair whose tie crosses the cut-off (the value at place N equals the value at place N + 1) is **refused**. The runtime breaks the tie deterministically by researcher id (`Ports.scala:66-67`), so it is not arbitrary, but which tied member falls inside the top N would then depend on id order, a detail of the export and not a property of the metric, and a label built on it would grade an answer by an id ordering. So is a pair with fewer than N ranked researchers. Grades are tiered by rank (decision P16-10): ranks 1 to 3 grade 3, ranks 4 to 6 grade 2, ranks 7 to 10 grade 1 (the coauthor-lookup scale). nDCG keeps gain 2^g - 1.
- **Shortest path.** Grade 1 per hop edge of a pair held in `path-evidence`. For a pair that `path-evidence` does not hold, the runtime answers with a `path-status` evidence item of kind `NotPrecomputed`, which says nothing about whether a path exists (`EvidenceBuilder.scala:390-394`). So the label is **not an abstention**: `expected.abstain` is false, `expected.pathStatus` is `NotPrecomputed`, and `expected.acceptedOutcomes` is `["path-status:NotPrecomputed", "abstain"]`. The scorer counts an answer correct if its evidence has that status, or if it abstains (plan: "expect abstention or unreachable"); it counts a path or a reachability claim wrong.
- **Community or component.** Grade 2 = a member of the same group (up to 29 others, the largest group the generator uses has 30 members), grade 1 = a cross-edge neighbour (community only, up to 25).
- **Temporal comparison.** Grade 2 = the subject; the numbers of both windows are in `expected.numbers`.
- **Unanswerable.** `expected.abstain` is true, `expected.abstainClass` is one of the six classes below, and `expected.expectedCodes` lists the runtime codes that satisfy it.

### Abstention classes and runtime codes

The committed mapping is `AbstentionCodes.byClass` (`BenchModel.scala`). The test `AbstentionMappingSuite` runs every unanswerable template through `CollaborationWorkflow` over the offline demo world with the deterministic stub model (no AWS), in the modes `graph` and `fused`, and pins the code each one produces. The pinned codes are written out in the test apart from the table, so the two must agree.

| Class | Template | Runtime code |
|---|---|---|
| `unknown-researcher` | a name that is in no export row (coauthors, or "Who is") | `identity-unresolved` |
| `ambiguous-name` | a name two or more canonical researchers share | `identity-unresolved` |
| `future-window` | top 5 by publications in 2031 to 2035; coauthors of a known researcher in 2040 to 2049 | `no-deterministic-evidence` |
| `out-of-scope` | "What is the capital of France?" | `planner-refused` |
| `unrankable-metric` | top 5 by citation count | `researcher-count` |
| `missing-second-window` | "Compare the publications of X between ..." with one window | `needs-two-windows` |

Notes on the mapping:

- A researcher lookup in a future window is **not** an abstention: the identity record alone carries a lookup past the evidence stage (`lookupOnly`, `Workflow.scala` pipeline), so the answer states who the researcher is. The earlier template 8 ("Who is X between 2040 and 2049?") was replaced by the coauthor lookup of the same researcher, which needs graph evidence and finds none.
- `out-of-scope` is a planner rule miss, so the classifier call goes to the model. The stub refuses it; a real model may name an operation instead. The mapping is the stub's behaviour. Run H records the code actually produced, and a different code is a finding, not a test failure.
- A known researcher that shares an ORCID with another node resolves with low confidence and gives `identity-unresolved` instead of the pinned code for the known-researcher templates; the generator does not read ORCIDs, so this is a residual risk that run H would show.
- Vector mode reads no graph and can answer from semantic context, so the mapping holds for graph and fused modes only.

## Questions file

`questions-v1.jsonl` carries `stratum` next to `text` so a human can read it. That is allowed only while `RetrievalEval` and `AnswerEval` send the question `text` and nothing else to the system: the stratum, the operation and every label field stay on the evaluation side. The rule is recorded here; the test that pins it (the request holds `text` only) lands with those entry points in the next slice. If an entry point ever sends more than `text`, `stratum` is dropped from the questions file in a new version.

## Other fields

- `vectorStatus`: `available`, `deleted` (every vector of the subject and window was among the Phase 15 deleted keys), `absent`, `unknown` (no vector export given) or `not-applicable`. `vectorAvailable` follows the primary subject.
- `source.rows[].file` and `line` locate the export row for the human spot-check (plan H-6).
- Manifest, vector coverage: the rebuilt deleted set is checked against `chunkset-manifest.json` by the sha256, byte size and row count of each `source.files` entry, the total row count, `composition.by_summary_type` and `composition.by_window`; a mismatch is reported, not repaired. Coverage is computed and checked before anything is written (the outputs are write-once). A given `--vector-export` whose directory is missing, or whose chunk set manifest is missing or unreadable, is an error: exit 2, nothing written. When any manifest check (`sha256`, `bytes`, `rows`, `by_summary_type`, `by_window`) is false, the command prints the flags and `BENCHMARK REFUSED`, writes nothing and exits 3, because the labels' `vectorStatus`, `vectorAvailable` and `vectorItems` depend on the rebuilt deleted set. `--accept-coverage-mismatch yes` writes anyway; the manifest then records `coverageMismatchOverride: {flag, accepted: true, failedChecks}` (a run with no mismatch records `accepted: false`) and the command prints `VECTOR_COVERAGE OVERRIDE`. On a written run the command prints `VECTOR_COVERAGE sha256=... bytes=... rows=... by_summary_type=... by_window=... summaryOverlap=...`, and a `VECTOR_COVERAGE MISMATCH: <names>` line when an override was used. `summaryOverlap` is informational and not part of any check: true only when every graph-export summary id and every graph-export summary subject is also in the vector export, and at least one summary exists. It says nothing about researchers; how many graph researchers are a subject in the vector export is `graphResearchersWithVectorSubject` in `subjectIdOverlapWithVectorExport`. `subjectIdOverlapWithVectorExport` gives the distinct summary subjects of both exports, how many are shared, and how many graph researchers are a subject in the vector export.
- Command line, options and exit codes (decision P16-13). Required: `<export dir> <output dir>` and either `--vector-export DIR` or `--no-vector-export yes`. An unknown option (a misspelt flag), a run with neither, both together, a value other than `yes` for `--no-vector-export`, and `--chunkset-manifest` without `--vector-export` are all refused: `BENCHMARK ERROR: <reason>; nothing written`, exit 2, the option is named. `--no-vector-export yes` runs with no vector coverage (every `vectorStatus` is `unknown`); the manifest records `vectorCoverage.mode: "none-by-flag"` and `coverageMismatchOverride.checked: false`, where a checked run records `vectorCoverage.mode: "checked"` and `checked: true`. Exit codes: 0 written; 2 refused inputs (unknown or missing option, missing vector directory or chunk set manifest, a coverage computation that throws such as an unreadable manifest); 3 `BENCHMARK REFUSED`, a false coverage check without `--accept-coverage-mismatch yes`. Under `sbt "evaluation/runMain scholarchain.evaluation.benchmark.BenchmarkGen ..."` the shell sees sbt's own exit status, not 2 or 3. What the build shows: the `evaluation` project sets `Compile / run / fork := true` (`build.sbt:442`), so the generator runs in a forked JVM and sbt wraps its exit code. I did not run it under sbt, so the exact status sbt returns for a non-zero exit is unverified; a non-zero exit in sbt is expected to surface as a failed sbt command (status 1). Treat the printed `BENCHMARK ERROR` and `BENCHMARK REFUSED` lines, and the absence of the output files, as the reliable signal.
- Manifest, generator state: `generatorCommit` and `generatorTreeDirty` (`true`, `false`, or `null` when unrecorded). The generator asks `git` for both unless the flags `--generator-commit SHA` and `--generator-dirty yes|no` are given. Dirty means any uncommitted change in the whole working tree at generation time, so run BenchmarkGen after the Wave 1 commit for a clean record.
- Template wording matches the planner's rules, so planner accuracy on these questions is overstated; the free-form slots exist to test that.

## Threats to validity

- Researcher-lookup recall in graph mode is capped by what the runtime returns: the default `retrieve` branch gives metrics only, no coauthors (`Workflow.scala:569-584`), so the grade 1 coauthor items of this stratum cannot be retrieved in graph mode and recall there measures the cap, not retrieval quality. Compare modes on the subject item (grade 2) for this stratum.
