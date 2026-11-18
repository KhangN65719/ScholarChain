# Benchmark v1 (Phase 16)

Questions and relevance labels for the retrieval and answer evaluation. Produced by `scholarchain.evaluation.benchmark.BenchmarkGen` from a local Spark JSONL export, with a recorded seed. No model and no prompt produces a question or a label, and the answering prompt never sees the labels.

Files (written once per version; the generator refuses to overwrite):

- `questions-v1.jsonl`: `{questionId, version, stratum, text}`. The system sees only this (see "Questions file").
- `labels-v1.jsonl`: one line per question, see below.
- `free-form-slots-v1.jsonl`: 8 slots for questions the human writes (plan H-5). `BenchmarkGen` writes them as `pending-human`; `BenchmarkFill` (see "Free-form fill") fills them once.
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

### Operation vocabulary

The `operation` of a label is the benchmark's own stratum vocabulary (`researcher-lookup`, `coauthor-lookup`, `ranking`, `shortest-path`, `community-component`, `temporal-comparison`; the fill also writes it for free-form labels, so a free-form `operation` is the stratum its answer is scored as). It is **not** the runtime's vocabulary: the planner's `operation` (the `RankedRecord.operation` and `AnswerRecord.operation` fields, from `planDraft.operation.wire`) names runtime operations, for example `community` and `component` where the label says `community-component`, and the `intendedOperation` of a free-form slot is the human's own word (`community`). The strings are not equal and no mapping table exists yet, so **no planner-accuracy metric may compare a label `operation` with a recorded `operation` until a mapping is written and tested** (WO-9). Only `RetrievalScoring.effectiveStratum` reads a label's `operation`, and only for free-form labels; generated labels are scored by `stratum`.

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

## Free-form fill

`scholarchain.evaluation.benchmark.BenchmarkFill <export dir> <benchmark dir> --vector-export DIR [--chunkset-manifest FILE] [--generator-commit SHA] [--generator-dirty yes|no]` (or `--no-vector-export yes`, which must match the mode the manifest records) labels the 8 human questions of decision P16-23 (compiled into `BenchmarkFill.v1Specs`, verbatim) by the rules above, from the export tables alone. It is write-once and writes only after every check passed:

- it refuses if a free-form line is already present (`Q-FF-` ids in the questions or labels file, a `filled` slot, or a `free-form-fill` change entry in the manifest);
- it refuses unless the sha256 and row count of `questions-v1.jsonl`, `labels-v1.jsonl` and `free-form-slots-v1.jsonl` match `BENCHMARK-MANIFEST-v1.json`, so the generated lines are known to be unchanged; the new files are the old bytes plus the appended lines, so the 48 generated lines stay byte-identical;
- it refuses a different export (`sourceRunId` or the export manifest sha256 differs from the manifest), a different vector coverage mode or directory, and a failed coverage check (exit 3);
- it refuses `--version` other than `v1` (the eight texts are the v1 specs of P16-23; a later version needs its own fill);
- it writes the four files by moving prepared temporary files into place one after the other. If a move fails part-way, it prints `BENCHMARK ERROR: the move failed part-way: <replaced files> replaced, <pending files> NOT replaced; the benchmark directory is in a mixed state ...` (not "nothing written") and asks for a restore from the backup or `git checkout -- experiments/benchmark` before any rerun; a failure of the first move is reported as nothing written;
- it refuses, writing nothing and naming the question, a subject name that is not exactly one canonical researcher (0 or 2 or more matches; a mention is not canonical), a window with no data, a ranking tie across the cut-off, fewer than N ranked researchers, a coauthor list longer than 25 or a community larger than 30 (the label caps), an unknown-researcher name that is in the export, and a citation metric in the export (FF-08 would be rankable).

It appends the 8 question lines to `questions-v1.jsonl` and the 8 label lines to `labels-v1.jsonl` (stratum `free-form`; the operation is one the scorers know: `coauthor-lookup`, `community-component`, `temporal-comparison`, `researcher-lookup`, `ranking`), writes the filled slots file, and updates the manifest: sha256 and rows of the three files, `countsByStratum["free-form"]`, `freeFormQuestionsFilled`, and a `changes[]` entry (`id`, time, command, previous and new sha256s, the check values, the Scala 3 library version, the generator commit). `generatedQuestions` stays 48, the count BenchmarkGen generated. The `scalaVersion` field of the v1 manifest is not rewritten; it holds the Scala 2.13 standard library version (see below) and the change entry says so. It prints one `Q-FF-nn ...` check line per question (values computed from the export, to compare with an independent reading) and one `FILL WROTE <file> rows=... sha256=...` line per file.

A new manifest (BenchmarkGen) records `scala3LibraryVersion` (read from the `scala3-library_3` jar name; `UNRECORDED` when not found) and `scala2StandardLibraryVersion` (`scala.util.Properties`, the 2.13 library that Scala 3 builds on) instead of the former `scalaVersion`, which held only the 2.13 value.

## Questions file

`questions-v1.jsonl` carries `stratum` next to `text` so a human can read it. That is allowed only while `RetrievalEval` and `AnswerEval` send the question `text` and nothing else to the system: the stratum, the operation and every label field stay on the evaluation side. The rule is recorded here and pinned by tests: "only the question text reaches the workflow" in `RetrievalEvalSuite` and `AnswerEvalSuite` (the ask carries the text, a session without labels and the mode; `QuestionRec` has no field but `questionId` and `text`). If an entry point ever sends more than `text`, `stratum` is dropped from the questions file in a new version.

## Other fields

- `vectorStatus`: `available`, `deleted` (every vector of the subject and window was among the Phase 15 deleted keys), `absent`, `unknown` (no vector export given) or `not-applicable`. `vectorAvailable` follows the primary subject.
- `source.rows[].file` and `line` locate the export row for the human spot-check (plan H-6).
- Manifest, vector coverage: the rebuilt deleted set is checked against `chunkset-manifest.json` by the sha256, byte size and row count of each `source.files` entry, the total row count, `composition.by_summary_type` and `composition.by_window`; a mismatch is reported, not repaired. Coverage is computed and checked before anything is written (the outputs are write-once). A given `--vector-export` whose directory is missing, or whose chunk set manifest is missing or unreadable, is an error: exit 2, nothing written. When any manifest check (`sha256`, `bytes`, `rows`, `by_summary_type`, `by_window`) is false, the command prints the flags and `BENCHMARK REFUSED`, writes nothing and exits 3, because the labels' `vectorStatus`, `vectorAvailable` and `vectorItems` depend on the rebuilt deleted set. `--accept-coverage-mismatch yes` writes anyway; the manifest then records `coverageMismatchOverride: {flag, accepted: true, failedChecks}` (a run with no mismatch records `accepted: false`) and the command prints `VECTOR_COVERAGE OVERRIDE`. On a written run the command prints `VECTOR_COVERAGE sha256=... bytes=... rows=... by_summary_type=... by_window=... summaryOverlap=...`, and a `VECTOR_COVERAGE MISMATCH: <names>` line when an override was used. `summaryOverlap` is informational and not part of any check: true only when every graph-export summary id and every graph-export summary subject is also in the vector export, and at least one summary exists. It says nothing about researchers; how many graph researchers are a subject in the vector export is `graphResearchersWithVectorSubject` in `subjectIdOverlapWithVectorExport`. `subjectIdOverlapWithVectorExport` gives the distinct summary subjects of both exports, how many are shared, and how many graph researchers are a subject in the vector export.
- Command line, options and exit codes (decision P16-13). Required: `<export dir> <output dir>` and either `--vector-export DIR` or `--no-vector-export yes`. An unknown option (a misspelt flag), a `--seed` that is not a whole number (it no longer falls back to the default seed), a flag with no value (last on the line, or followed by another `--flag`), a stray argument that is no flag, a run with neither, both together, a value other than `yes` for `--no-vector-export`, and `--chunkset-manifest` without `--vector-export` are all refused: `BENCHMARK ERROR: <reason>; nothing written`, exit 2, the option is named (for example `BENCHMARK ERROR: option --seed needs a value; nothing written`). Only a missing export or output directory argument prints `Usage` (also exit 2). `--no-vector-export yes` runs with no vector coverage (every `vectorStatus` is `unknown`); the manifest records `vectorCoverage.mode: "none-by-flag"` and `coverageMismatchOverride.checked: false`, where a checked run records `vectorCoverage.mode: "checked"` and `checked: true`. Exit codes: 0 written; 2 refused inputs (unknown or missing option, missing vector directory or chunk set manifest, a coverage computation that throws such as an unreadable manifest); 3 `BENCHMARK REFUSED`, a false coverage check without `--accept-coverage-mismatch yes`. Under `sbt "evaluation/runMain scholarchain.evaluation.benchmark.BenchmarkGen ..."` the shell sees sbt's own exit status, not 2 or 3. What the build shows: the `evaluation` project sets `Compile / run / fork := true` (`build.sbt:442`), so the generator runs in a forked JVM and sbt wraps its exit code. I did not run it under sbt, so the exact status sbt returns for a non-zero exit is unverified; a non-zero exit in sbt is expected to surface as a failed sbt command (status 1). Treat the printed `BENCHMARK ERROR` and `BENCHMARK REFUSED` lines, and the absence of the output files, as the reliable signal.
- Manifest, generator state: `generatorCommit` and `generatorTreeDirty` (`true`, `false`, or `null` when unrecorded). The generator asks `git` for both unless the flags `--generator-commit SHA` and `--generator-dirty yes|no` are given. Dirty means any uncommitted change in the whole working tree at generation time, so run BenchmarkGen after the Wave 1 commit for a clean record.
- Template wording matches the planner's rules, so planner accuracy on these questions is overstated; the free-form slots exist to test that.

## Regeneration check (human, no AWS)

The shared label builders were refactored after the files were generated (08:40, 2026-11-18). To show that the generator still writes the frozen lines, regenerate into a scratch directory outside the repository, right after the Wave 2 commit and **before** the free-form fill (the manifest then still holds the generated files' hashes), and compare the sha256 of the three files with `BENCHMARK-MANIFEST-v1.json`. The scratch directory must be new or empty (the generator refuses to overwrite). Nothing in the repository is written.

```zsh
cd /Users/winnguyen/ScholarChain
mkdir -p /Users/winnguyen/scholarchain-data/logs /Users/winnguyen/scholarchain-data/benchmark-regen-check
set -o pipefail
sbt "evaluation/runMain scholarchain.evaluation.benchmark.BenchmarkGen /Users/winnguyen/scholarchain-data/rehearsal/a-export /Users/winnguyen/scholarchain-data/benchmark-regen-check --seed 20261117 --version v1 --vector-export /Users/winnguyen/scholarchain-data/rehearsal/s-export --chunkset-manifest /Users/winnguyen/ScholarChain/datasets/tier-s/chunks-c/chunkset-manifest.json --generator-commit $(git rev-parse HEAD)" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-benchmark-regen-check.log | tail -n 20
for f in questions-v1.jsonl labels-v1.jsonl free-form-slots-v1.jsonl; do now=$(shasum -a 256 /Users/winnguyen/scholarchain-data/benchmark-regen-check/$f | awk '{print $1}'); want=$(grep -A2 "\"name\": \"$f\"" /Users/winnguyen/ScholarChain/experiments/benchmark/BENCHMARK-MANIFEST-v1.json | grep '"sha256"' | sed -E 's/.*"sha256": "([0-9a-f]+)".*/\1/'); if [ "$now" = "$want" ]; then echo "REGEN_MATCH $f $now"; else echo "REGEN_DIFFERS $f regenerated=$now manifest=$want"; fi; done
```

Expected: three `REGEN_MATCH` lines (and `BENCHMARK RESULT: 48 questions, 8 free-form slots, shortfalls 1`). A `REGEN_DIFFERS` line means the refactored generator no longer writes the frozen file: do not run the fill, report it. The regenerated manifest may differ from the committed one in the generator state fields (commit, tree state) and is not compared. `BenchmarkGenSuite` also holds a golden test over a fixture; it catches future drift in the label builders, but it cannot show that the real v1 files are unchanged, which this check does.

## Threats to validity

- Researcher-lookup recall in graph mode is capped by what the runtime returns: the default `retrieve` branch gives metrics only, no coauthors (`Workflow.scala:569-584`), so the grade 1 coauthor items of this stratum cannot be retrieved in graph mode and recall there measures the cap, not retrieval quality. Compare modes on the subject item (grade 2) for this stratum.
