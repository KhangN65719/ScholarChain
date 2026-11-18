# W2-B: experiment-engineer batch report (FF fill, OD-2, FactoryCounts refusal, Scala version)

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed, content unchanged).

---

**W2-B: COMPLETE (code and tests). The real fill has not run; it is human-run.** No AWS, no Python, no commit, no `runMain` attempted.

**sbt quick (bare, twice; second after a warning fix)**
```
[info] Passed: Total 77, Failed 0, Errors 0, Passed 77
[info] Passed: Total 1126, Failed 0, Errors 0, Passed 1126
[success] Total time: 155 s (0:02:35.0), completed Nov 18, 2026, 9:29:00 AM
```
The evaluation project's total line was not read (buried in Spark log). Its JUnit XML for the first run: `BenchmarkFillSuite` 17, `BenchmarkCliSuite` 9, `FactoryCountsCliSuite` 8, all 0 failures, 0 errors. Second run compiled with no warning, `[success]`. `BenchmarkGenSuite`, `FactoryCountsSuite` and the other evaluation suites passed.

**Test per item**
- Item 1 (fill), `BenchmarkFillSuite`: byte-identical old lines, 8 lines appended to questions and labels, slots filled, manifest sha and rows, change entry with previous shas, other manifest fields unchanged ("the fill appends the eight labelled questions and keeps the old lines"); label contents ("labels follow the v1 rules…"); printed check values ("the fill prints the computed check values"); write-once ("a second fill is refused and changes nothing"); sha mismatch ("a fill is refused when an existing line no longer matches the manifest"); wrong export, wrong coverage mode, failed coverage check (exit 3); one test per refusal reason (subject not exactly one canonical researcher: 0, 2 or mention only; ranking tie across the cut-off; fewer than N ranked; window with no data; ghost name present in the export; citation metric present; community over 30). Each refusal asserts the four files byte-identical and no `.fill` temp file left; "every problem is reported and nothing is written" covers multiple reasons; CLI refusals ("the command line refuses a missing export and an unknown option").
- Item 2 (OD-2), `BenchmarkCliSuite`: a flag at the end, a flag followed by another flag, and a stray argument each print `BENCHMARK ERROR: option --seed needs a value; nothing written`, exit 2, nothing written; valueless `--no-vector-export` added. Too few positional arguments still print `Usage` (README says so).
- Item 3 (FactoryCounts), `FactoryCountsCliSuite`: each missing or unreadable input (agent-runs directory, mapping, rewrites, a directory as mapping, unreadable file) gives one `FACTORYCOUNTS ERROR: … nothing written` line, exit 2, no stack trace, nothing written; malformed mapping row and existing run directory refused in one line; a good run still writes the sealed directory. A missing rewrites file was silently ignored before and is now refused.
- Item 4 (Scala version), `BenchmarkCliSuite`: jar-name parse; running version found, starts with `3.`; new manifests carry `scala3LibraryVersion` and `scala2StandardLibraryVersion`, no `scalaVersion`.

**Decisions**
- Scala version: `scala.util.Properties` reports the 2.13 library. New manifests record `scala3LibraryVersion` (from the `scala3-library_3-<v>.jar` file name) and `scala2StandardLibraryVersion`. The existing v1 manifest's `scalaVersion` is not rewritten; the fill's change entry carries `scala3LibraryVersion` and a note.
- Extra refusals: coauthor list over 25; community over 30 members; an unknown-researcher name present in the export; a citation metric in the export; a different export (sourceRunId or export manifest sha256); a different vector coverage mode or directory than the manifest records.
- Manifest updates: `files[]` sha256 and rows, `countsByStratum["free-form"]`=8, `freeFormQuestionsFilled`=8, a `changes[]` entry; `generatedQuestions` stays 48.
- Slot lines keep the five requested fields in order, plus `windows`, `metric`, `n`, `abstainClass` (FF-03 needs two windows).
- Round-trip guard: the fill refuses unless the manifest re-serialises byte-identically first.

**Human fill command**: copied into `../../human-commands.md` (Pending). Expect one `Q-FF-nn …` check line per question, four `FILL WROTE … sha256=…` lines, then `BENCHMARK FILL RESULT`; a refusal prints `BENCHMARK ERROR: <reason>; nothing written` (or `BENCHMARK REFUSED`, exit 3). Compare check lines with P16-26.

**Unverified**
- Nothing ran on the real export: FF-01 coauthor count (≤25) and FF-02 community size (≤30) could trigger a refusal; the real v1 manifest passing the round-trip guard; the `$(git rev-parse HEAD)` value for an uncommitted tree.
- sbt's exit status for a non-zero forked exit unobserved.
- Free-form label `operation` uses the scorer-known names (`community-component`, `ranking`); generated labels use `community`, `collaboration-metrics`, `bridge-analysis`. `RetrievalScoring.effectiveStratum` needs only the names used; other scorers comparing `operation` to runtime names were not checked.
- Existing v1 `scalaVersion` stays `2.13.16`, annotated only.
- Not done: OD-3; the `scripts/` and `tests/scripts` routings from W2-R.

Files: `experiments/evaluation/src/main/scala/scholarchain/evaluation/benchmark/BenchmarkFill.scala` (new), `.../benchmark/BenchmarkGen.scala`, `.../factory/FactoryCounts.scala` (edited), `experiments/evaluation/src/test/scala/scholarchain/evaluation/{BenchmarkFillSuite,BenchmarkCliSuite,FactoryCountsCliSuite}.scala` (new), `experiments/benchmark/README.md` (edited).
