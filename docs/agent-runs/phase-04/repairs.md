# Phase 4 repairs

## Repair 1 — response to validation run 1

Author: `hadoop-engineer`. Received by the orchestrator 2026-11-06.

Dispatch recorded in [validation.md](validation.md). Trigger: the compile failure at `SourceNormalizers.scala:516` in validation run 1.

Usage as reported by the runtime: 341374 subagent tokens, 210 tool uses, 297348 ms.

### Preservation note

As with the [owner report](owner-report.md), only the agent's closing summary was delivered to the orchestrator. Reproduced as received, unedited. It is the agent's own account, not an independently verified result.

### Report as received

> Same gate, same answer, and I will not try to clear it.
>
> Three reasons, all standing:
> 1. This dispatch's item 3 explicitly instructs me not to run `sbt` and not to retry it.
> 2. `sbt` cannot start in this sandbox regardless — `/Users/winnguyen/.sbt/boot/sbt.boot.lock (Operation not permitted)`.
> 3. Only a passing `sbt check` may write `.claude/.last-test-run`, the guard denies the write, and forging a green marker would be far worse than a blocked stop.
>
> The hook is telling the truth: the sources I just edited have not been compiled or tested. That is the accepted open gate in CLAUDE.md, not a defect for me to repair.
>
> State of the repair, unchanged from my report:
>
> - **Fixed** `SourceNormalizers.scala:516` — the `Option[Venue]` / `Vector[Venue]` mismatch, corrected at the producer, provenance preserved, no core edit.
> - **Fixed** two further defects the audit turned up in the OpenAlex normalizer: a dead `abstractTextRef` that could never fire, and a venue synthesized from the record `type` that would have collapsed `name-venue` blocking into one giant block per common name.
> - **Audited clean**: all three `NormalizedRecord` sites, every core-contract construction site in the module read against its declared field, and all module-local records.
> - **Verified only** that the module parses (project-wide scalafmt, via the formatter hook, on all 22 files).
> - **Unverified**: type checking of main sources beyond what the human's single-error run already proved, and *all* 115 test cases across 7 suites — `sbt check` failed at `Compile/compile`, so `Test/compile` was never reached.
>
> Next action is the human's: `sbt clean check`. If it fails again, the likeliest remaining surface is test-only type errors, since that code has never been compiled.

### Claimed changes

| Change | File | Status |
|---|---|---|
| `Option[Venue]` to `Vector[Venue]` at the producer, no core edit | `SourceNormalizers.scala:516` | Claimed fixed, uncompiled |
| Dead `abstractTextRef` that could never fire | OpenAlex normalizer | Claimed fixed, uncompiled |
| Venue synthesized from record `type`, collapsing `name-venue` blocking into one block per common name | OpenAlex normalizer | Claimed fixed, uncompiled |

The third item is a behavioral defect, not a compile error. If the description is accurate it would have defeated the specification's requirement to "prevent pathological common-name or title blocks from overwhelming reducers," and it would have invalidated any largest-blocking-group figure measured before the fix. It was found by the audit this repair asked for, and it is unverified.

### Outstanding against the work order

The "Required reporting" items were requested a second time in the repair dispatch and again were not delivered: job graph, mapper and reducer key-value contracts, partition counts and strategy, counter inventory, reject output paths, output paths with run ID and snapshot, maximum author team size, largest pair expansion, largest observed blocking group. Two reports have now closed without them.

Note also that the suite figures moved between reports without explanation: the owner report stated roughly 90 test cases across 8 suites, this one states 115 cases across 7 suites. Neither figure has been executed. Reconcile this before review.

### Gate status after repair 1

Unchanged from [validation.md](validation.md). No gate item has been closed, because no compile or test has succeeded since the repair. Validation run 2 is the next step and belongs to the human.

## Repair 2 — response to validation run 2

Author: `hadoop-engineer`. Received by the orchestrator 2026-11-06.

Dispatch recorded in [validation.md](validation.md). Trigger: the single test failure in run 2 at `SourceNormalizerSuite.scala:245`.

Usage as reported by the runtime: 366585 subagent tokens, 226 tool uses, 232818 ms.

As before, only the agent's closing summary reached the orchestrator. Reproduced as received, unedited, and not independently verified.

### Report as received

> Third time, same answer: I will not run `sbt` and will not touch the marker.
>
> Item 4 of this dispatch forbids running it, the sandbox blocks sbt's boot lock regardless, and only a passing check may write `.claude/.last-test-run`. The hook is accurate — the three test cases I just edited have not been compiled or run.
>
> What is outstanding for the human's `sbt clean check`:
>
> - **Changed, unrun:** `SourceNormalizerSuite` (corrected Crossref DOI fixture `10.1000/abc`, strengthened abstract assertion, two new cases) and `LocalPipelineSuite` (one diagnostic case). 118 cases across 7 suites now.
> - **Main sources untouched this repair**, so run 2's 114 passes still hold for them.
> - **Most likely failure point:** the hand-built OpenAlex `abstract_inverted_index` fixture in the new case. If I mis-shaped it, it will fail as a `Left` at `.getOrElse(fail("normalization failed"))` — the same signature as the bug I just fixed, and I would want to know rather than have it pass by accident.
> - **Read out of the output:** the `[phase-4 measured]` line carries the largest blocking group, its key, the group-size distribution and the partition spread — the one required figure I have never been able to observe and declined to guess again.
>
> Both blockers stay with the orchestrator: `build-engineer` for the absent Hadoop test runtime, which is what keeps the "two real MapReduce jobs execute" gate item open, and `domain-engineer` for the missing `MatchCandidate` core contract.

### Diagnosis as reported

The agent located the rejection in the **fixture**, not the normalizer: a malformed Crossref DOI, corrected to `10.1000/abc`. It reports strengthening the abstract assertion rather than relaxing it, and adding two further cases plus one diagnostic case in `LocalPipelineSuite`. Case count moves from 115 to 118 across 7 suites.

Main sources were not touched in this repair, so run 2's 114 passing results remain the evidence for them. Only test sources changed.

The report does not state whether the repair-1 `abstractTextRef` change contributed to the run-2 rejection, which the dispatch asked for directly. Treat that as unanswered.

### Required reporting — still outstanding

Requested three times; still not delivered. The agent has instrumented the largest-blocking-group figure as a `[phase-4 measured]` diagnostic line emitted during the test run, to be read out of the human's next output rather than guessed. That is the correct handling for a figure it cannot observe, and it resolves one item. The remainder — job graph, mapper and reducer key-value contracts per job, partition counts and strategy, counter inventory, reject output paths, output paths with run ID and snapshot, maximum author team size, largest pair expansion — has now closed out of three consecutive reports without arriving.

Only the agent's closing summary reaches the orchestrator in this runtime, which is the probable mechanism. Before dispatching `code-reviewer`, either require this report as a file the orchestrator reads, or treat the required reporting as a standing Phase 4 deficiency and record it as such.

### Gate item answered

The dispatch asked directly whether any job has executed on a Hadoop runtime. The agent confirms none has, and names the `build-engineer` test-runtime blocker as the cause. **"At least two real MapReduce jobs execute" is open and cannot be closed by this owner.** It is a hard gate item in the specification, so Phase 4 cannot pass review until the runtime blocker is routed and resolved.

### Gate status after repair 2

No gate item closed. Validation run 3 belongs to the human.

## Repair 3 — response to the independent review

Author: `hadoop-engineer`. Received by the orchestrator 2026-11-06.

Trigger: the `REQUEST CHANGES` verdict in [review.md](review.md), findings F1 through F5.

Usage as reported by the runtime: 435501 subagent tokens, 286 tool uses, 293948 ms.

### Interruption note

This repair was cut off partway by an account spend limit, with F1 in progress. The agent was resumed after the limit reset and instructed to re-read its own on-disk changes before editing, so as not to redo or double-apply work. The report below is from the resumed run and covers the whole repair.

As with the previous three reports, only the closing summary reached the orchestrator. Reproduced as received, unedited, not independently verified.

### Report as received

> Fourth time, same answer: I will not run `sbt` and will not touch the marker.
>
> The constraint was restated in this dispatch, sbt cannot start in this sandbox regardless, and only a passing check may write `.claude/.last-test-run`. The hook is correct — everything I changed in repair 3 is uncompiled and unrun.
>
> Handoff state:
>
> - **F1–F5 all addressed**, plus a third F4 site the review did not name (`jobs/PipelineOptions.scala:12`, same false overwrite claim).
> - **132 cases across 8 suites**, up from 118 across 7.
> - **Unverified:** all of it. Highest-risk first — the `Provided` classpath resolution in the new `JobWiringSuite`, the exact pinned coauthor keys in the F1 replacements, and the 8-received/7-distinct/4-retained arithmetic in the F3 path-agreement test.
> - **The `Provided` question is the one genuinely open result.** If `org.apache.hadoop.*` resolves at `Test/compile`, job-class tests are possible today with no build change. If it does not, that strengthens the `build-engineer` blocker. The suite header documents both outcomes and says to report rather than delete it.
> - **Read from the test output:** the `[phase-4 measured]` line, now carrying `blockingGroupSizesTop10of<N>` with the truncation labeled.
>
> Unchanged and still not mine: Hadoop test runtime (`build-engineer`, hard gate on "two real MapReduce jobs execute"), `MatchCandidate` core contract (`domain-engineer`), adversarial cases under `tests/` (`test-engineer`). F6 remains recorded and out of scope.
>
> Next action is the human's: `sbt clean check`, then re-review of the changed files.

### Claimed disposition of the review findings

| Finding | Claimed status | Notes |
|---|---|---|
| F1 tautological block-key test | Addressed | Replacements pin exact coauthor keys; the agent flags these as a risk site |
| F2 unlabeled truncation | Addressed | `blockingGroupSizes` renamed to `blockingGroupSizesTop10of<N>` |
| F3 duplicate-inflated `observedSize` | Addressed | New path-agreement test with 8-received/7-distinct/4-retained arithmetic, flagged as a risk site |
| F4 false overwrite-on-rerun claim | Addressed, **scope widened** | A third site the review did not name: `jobs/PipelineOptions.scala:12` |
| F5 `artifactPath` missing `dataset=` | Addressed | |
| F6 reporting deficiency | Correctly not attempted | Recorded by the orchestrator; not the owner's |

The F4 widening is the owner finding a third instance of a defect the reviewer located in two places. That is the behavior the sweep was asked for, and it should be verified at re-review rather than taken on trust.

Test inventory moves from 118 cases across 7 suites to **132 across 8**, the new suite being `JobWiringSuite`, which exists to answer the `Provided` classpath question.

### The `Provided` classpath question

This is the one deliberately open result, and it is the first direct attempt to test anything under `jobs/**` — a package that had **zero** test references at review time. Two outcomes, both useful:

- If `org.apache.hadoop.*` resolves at `Test/compile`, job-class unit tests are possible today with no build change, and the `build-engineer` blocker is narrower than the owner originally framed it. This matches the reviewer's correction in [review.md](review.md).
- If it does not resolve, the blocker's case is strengthened on measured evidence rather than assumption.

The agent reports documenting both outcomes in the suite header with an instruction to report rather than delete the suite. **Validation run 4 decides this**, and the outcome should be recorded either way.

### Not reported

The dispatch asked for the result of a sweep for other F1-shaped tautological tests — cases whose two sides are built from identical inputs, or whose assertion cannot fail. The closing summary does not state what the sweep found, or whether it was performed. The F4 widening suggests some cross-checking occurred, but that is a different defect class. **Treat the tautology sweep as unreported** and either ask for it or have the re-review cover it.

### Gate status after repair 3

No gate item closed. Nothing in repair 3 has been compiled or run; the marker is stale. Validation run 4 belongs to the human, and a re-review of the changed files follows it.

## Repair 4 — skip analysis after validation run 4

Author: `hadoop-engineer`. Received by the orchestrator 2026-11-06.

Trigger: the architect's questions on the 7 skipped `JobWiringSuite` cases in [validation.md](validation.md), run 4. Dispatched as a written question, not a repair; the owner's answer identified a genuine coverage gap and it made a scoped change to close it.

Usage as reported by the runtime: 452726 subagent tokens, 296 tool uses, 168847 ms.

Only the closing summary reached the orchestrator. Reproduced as received, unedited, not independently verified.

### Report as received

> Fifth time, same answer: I will not run `sbt` and will not touch the marker. The constraint is standing, sbt cannot start in this sandbox, and only a passing check may write `.claude/.last-test-run`.
>
> The hook is correct — the two test files I touched answering Q2 are uncompiled and unrun.
>
> Handoff:
>
> - **Changed:** `test/PartitionerWiringSuite.scala` (new, 3 cases moved verbatim) and `test/JobWiringSuite.scala` (now 5 cases, dead imports dropped, header corrected). 132 cases across 9 suites.
> - **Answered:** the 7 skips were collateral of a `LinkageError` aborting the suite, not any skip mechanism — there is none in the file. Five needed `Configuration`; three never did. One required operational behavior, the partition strategy as implemented in the class Hadoop actually calls, did rest solely on skipped cases, which is why I moved them. The tautology sweep found one true tautology (F1), five legitimate determinism checks, five sound swapped-input checks.
> - **Flagged, not touched:** `build.sbt` was modified by someone else to add `hadoop-client-runtime % Test`. That should unblock `JobWiringSuite`'s five cases on the next run. It does **not** add `hadoop-client-minicluster`, so the "two real MapReduce jobs execute" gate item stays open.
> - **Risk on the next run:** both files are new or edited and unverified. If `PartitionerWiringSuite` fails, the likely cause is the `StableKeyPartitioner`/`Text` class-load path, not the assertions, which are unchanged from cases that compiled in run 4.
>
> Next action is the human's: `sbt clean check`, then re-review of the changed files.

### Answers to the architect's three questions

**1. Why were the 7 cases skipped?** Collateral of a `LinkageError` aborting the suite, **not** any skip mechanism — the owner confirms the file contains none. This matches the orchestrator's reading in [validation.md](validation.md). Five of the seven required a `Configuration`; three never did.

**2. Did any skip conceal a gate item? Yes — one did.** The owner reports that the partition strategy *as implemented in the class Hadoop actually calls* rested solely on skipped cases. This is the distinction the dispatch asked about: `IdentifierAndKeySuite` covers `PartitionKey` in-process, but `StableKeyPartitioner` — the `Partitioner` subclass Hadoop invokes — had no coverage that could execute. The three partitioner cases never needed a `Configuration` and were being skipped only because they shared a suite with five cases that did.

The owner's remedy was to move those three cases verbatim into a new `PartitionerWiringSuite`, so they no longer abort alongside the `Configuration`-dependent cases. This is a scoped, well-judged change: it closes a real coverage gap by separating independent concerns rather than by weakening or adding assertions. Case total is unchanged at 132; suite count moves from 8 to 9; `JobWiringSuite` drops from 8 cases to 5.

**3. Tautology sweep — answered at last.** Across the suites: **one** true tautology, which was F1 and is fixed; five legitimate determinism checks; five sound swapped-input checks. So F1 was an isolated instance rather than a pattern, and the cases that superficially resemble it are sound. This closes the question carried through three dispatches.

### Concurrent build change, observed by the owner and not made by it

The owner reports that `build.sbt` was modified by another agent to add `hadoop-client-runtime % Test` while it was working. That is `build-engineer`, dispatched by the orchestrator for exactly that fix; the owner correctly declined to touch `build.sbt` and only noted the change. Authority held on both sides.

**The owner's qualification on that fix is material and the orchestrator records it as the live reading of the gate:** adding `hadoop-client-runtime % Test` should let `JobWiringSuite`'s five remaining cases run, but it does **not** add `hadoop-client-minicluster`. Instantiating a `Configuration` and exercising a `Partitioner` is not executing a MapReduce job. **"At least two real MapReduce jobs execute" therefore stays open after this fix**, and closing it needs a mini-cluster or an equivalent runtime, which is a separate `build-engineer` item.

### Risk flagged for the next run

Both touched files are unverified. If `PartitionerWiringSuite` fails, the owner's expectation is the `StableKeyPartitioner`/`Text` class-load path rather than the assertions, which are unchanged from cases that compiled in run 4.

### Gate status after repair 4

No gate item closed. Validation run 5 belongs to the human. The `build-engineer` report on the dependency fix is outstanding at the time of writing.

## Repair 5 — end-to-end MapReduce execution, plus F7 and F8

Author: `hadoop-engineer`. Received by the orchestrator 2026-11-06.

Trigger: the orchestrator's dispatch after run 6 made `hadoop-client-minicluster` available, to close the "at least two real MapReduce jobs execute" gate item, and to fix findings F7 and F8 from [re-review.md](re-review.md).

Usage as reported by the runtime: 521486 subagent tokens, 344 tool uses, 517723 ms.

### Interruption note

This dispatch was cut off by an account spend limit with F8 half-applied, then resumed. The orchestrator inspected the tree at the interruption and handed the agent an exact file-by-file state so it would not rediscover or double-apply work. The report below is from the resumed run and covers the whole dispatch. As with the previous reports, only the closing summary reached the orchestrator.

### Report as received

> Sixth time, same answer: I will not run `sbt` and will not touch the marker. Standing constraint, restated in this dispatch; sbt cannot start in this sandbox regardless.
>
> The hook is correct and unusually load-bearing this time — I changed nine main-source files and added a suite that executes real Hadoop jobs, none of it compiled or run.
>
> Handoff:
>
> - **New:** `test/MapReduceEndToEndSuite.scala`, 13 cases, two real MapReduce jobs via `LocalJobRunner`. **145 cases across 10 suites.**
> - **Main sources changed:** `HadoopSink.scala` (new `commitReject`), all seven reducers (reject routing), `TaskSink.scala` + `TeamExpansion.scala` (`ExpandedPairs` split from `EmittedPairs`), `LocalPipelineRunner.scala` (F8 + `filterNot` removed).
> - **Two defects found and fixed, both only visible once I traced real execution:** the pair counter double-counting across map and reduce, and map-detected rejects being thrown on, decoder-crashed, or silently dropped in all seven jobs. The second means "counters and reject outputs are visible" was false at runtime for every stage despite passing review on inspection.
> - **F7 and F8 done.**
>
> Ranked risks for run 7, so the failure is interpretable rather than surprising: `REDUCE_INPUT_GROUPS == 5` (hand-derived), the newly written reject commit path across seven reducers, `DuplicateRecords == 2`, `splits.size > 1` against my file-size estimate, and the `FileAlreadyExistsException` match. A compile failure would be the surprise — the Hadoop API surface is the same as `JobWiringSuite`, which compiled in run 4.
>
> Expect roughly +20–60 seconds of wall time from four job submissions.
>
> Next action is the human's: `sbt clean check`. If `MapReduceEndToEndSuite` goes green, the "at least two real MapReduce jobs execute" gate item closes with executed evidence.

### Orchestrator verification of the structural claims

Read-only checks against the tree, before any run:

| Claim | Check |
|---|---|
| New end-to-end suite exists | `test/MapReduceEndToEndSuite.scala`, 19163 bytes, modified 12:03 |
| 13 cases | A count of `test(` blocks in that file returns **13** |
| Real job execution via `LocalJobRunner` | Confirmed at five sites in the suite, including "`LocalJobRunner` drives the job in-process over `file:///`. That is Hadoop's…" |
| F8 fixed at both sites | **Yes, now both.** `LocalPipelineRunner.scala:328` and `BlockingJob.scala:129` both read `holds $observed records, above the bound of ...; retained ${members.size} distinct members deterministically`. At the interruption only the `BlockingJob` site was done |
| New reject commit path | `HadoopSink.scala:99,109` — `commitReject(reasonCode, encoded)` exists and is called |

The previously unexplained 10:29 edits are now accounted for: `TaskSink.scala` and `TeamExpansion.scala` are the `ExpandedPairs`/`EmittedPairs` counter split, which is the first of the two defects.

### The two defects, and why they matter beyond themselves

**Defect 1 — pair counter double-counting across map and reduce.** The emitted-pair count was incremented on both sides of the shuffle. Remedied by splitting `ExpandedPairs` from `EmittedPairs` in `TaskSink` and `TeamExpansion`.

**Defect 2 — map-detected rejects lost in all seven jobs.** Map-side rejects were "thrown on, decoder-crashed, or silently dropped." Remedied with a new `commitReject` path in `HadoopSink` and reject routing added to all seven reducers.

The second defect deserves to be recorded prominently, because of what it says about the evidence standard rather than only about the code. The specification gate item "counters and reject outputs are visible" **passed the original review on inspection, and was false at runtime for every stage.** The reviewer read the reject paths and found them correct; they were correct in the in-process runner and broken under real job execution, where a map-side reject has to survive serialization and a shuffle to reach a committed output.

This is direct evidence for the position the orchestrator and reviewer both held: that in-process stage tests could not substitute for job execution, and that the MapReduce gate item was load-bearing rather than procedural. It also vindicates the reviewer's recorded risk that "the first mini-cluster run should be expected to find configuration-level defects."

### Scope caution for the re-review

**Nine main-source files changed, including all seven reducers.** This is a substantial change to production code *after* the re-review returned APPROVE on the owner's work. The approved state and the current state are no longer the same, and the new reject-routing code in seven reducers has never been reviewed by anyone. The re-review's APPROVE does not extend to it.

**A limitation of `LocalJobRunner` the orchestrator records rather than leaves implicit.** The suite's own header notes that `LocalJobRunner` executes a single reduce task regardless of configuration. So real submission, splits, map and reduce tasks, shuffle and output commit are exercised, but **fan-out across multiple reduce tasks is not**. The partitioner is unit-tested in `PartitionerWiringSuite` and the partition map is asserted in `LocalPipelineSuite`, but no run has ever distributed one job's keys across more than one live reducer. Whether `LocalJobRunner` satisfies the specification's "real MapReduce jobs execute, not local collection operations disguised as MapReduce" is a reviewer judgment, not the orchestrator's to declare. The orchestrator's reading is that it does — it is Hadoop's own runtime executing a real job graph, which is categorically different from an in-process collection operation — but the single-reduce-task limit should be stated in whatever record closes the gate item.

Note also that `build-engineer` added `hadoop-client-minicluster`, while the owner drove the jobs with `LocalJobRunner`. The reviewer should say whether the minicluster dependency is still needed, now redundant, or wanted for a future multi-reducer test.

### Risks the owner ranked for run 7

In its own order, so a failure is interpretable: `REDUCE_INPUT_GROUPS == 5` (hand-derived); the newly written reject commit path across seven reducers; `DuplicateRecords == 2`; `splits.size > 1` against a file-size estimate; and the `FileAlreadyExistsException` match. The owner states a compile failure would be the surprise, since the Hadoop API surface matches `JobWiringSuite`, which compiled in run 4. Expected wall-time increase: roughly 20 to 60 seconds across four job submissions.

## Repair 7 — partial, Claude subagents, 17:34–17:44

Author: `hadoop-engineer` and `test-engineer` (Claude Opus subagents). Three of five must-fix items landed before both agents hit an account spend limit.

Landed by `hadoop-engineer` (17:34–17:36): **F3 + F7 as one change with the bound first**, adding `PipelineCounter.SuppressedBlockingKeys` and a **distinct** `RejectReason.OversizeCoauthorFanOut` rather than reusing `OversizeAuthorTeam`, since sharing one code is the F13b defect; `Blocking.expectedCoauthorKeys(n) = n * (n - 1)` in `Long`, compared against `maxPairsPerWork` (default 10 000, reused rather than a new knob, so suppression begins around 101 authors) with the `O(n)` selective strategies deliberately kept so an oversize team still reaches its name, year, venue and affiliation blocks. **F4 + F13a**: `distinctBy((source.wire, sourceRecordId, entityId))` → `claimed.distinct` for full structural equality, so a byte-identical replay is still counted; conflicts become `max(0, conflictingTitles.size - 1)`. **R1 at all four sites**, adopting the existing non-counting `sink.emit` pattern.

**Correction, 2026-11-06.** This entry and the table below originally credited a helper named `identicalReplays` for keeping a byte-identical replay counted. **That construct does not exist in the repository.** The must-fix re-review grepped the tree and found it only in this file. Codex's 19:24 pass over `IdentifierJoin.scala` replaced the named helper with a different mechanism: `sourceCounts` at `:143-144` is computed from **`claimed`** rather than from `distinct`, so two identical claims still yield `duplicates = 1`. The property holds and is executed at `AdversarialCounterSuite.scala:64-71`, so this was a **record defect, not a code defect** — but the orchestrator recorded an owner's claim about a named construct without verifying it survived a later pass over the same file by a different tool. The re-review's observation stands: a single non-Claude pass editing over another agent's work in the same files can revise it without the revision surfacing in any report.

**One correction to the final review, from the owner.** The reviewer's stated sequencing hazard — that fixing F7 alone restores full `n(n-1)` fan-out for same-name teams — does not hold, because `.distinct` on the coauthor name blocks survives: for an all-same-name team every coauthor folds to one block, so the fix yields **one** key per mention, `n` total. The hazard is real for *distinct*-name teams, but those already emitted `n(n-1)` before the repair. F3's bound was needed regardless and landing it first was right; the specific mechanism cited was not what made it necessary.

**One technical correction the owner left for continuity, which proved important:** a `try` in `DblpExtractionJob.map` does **not** cover F9 or F10, because those throws originate in `XmlRecordStream.readRecordBody` reached through `DblpXmlRecordReader.nextKeyValue`, which Hadoop's `MapTask` calls *outside* the user `map` method. The reader itself had to stop throwing.

The dispatch stopped on a formatter-hook collateral-change flag. The owner established by modification time that the seven files `sbt fmt` touched were exactly the seven it had edited, all under `modules/hadoop/**`, and declined to reason past the rule anyway. The flag's underlying cause was environmental: `sbt.internal.ServerAlreadyBootingException … Address already in use` on the boot lock, from a concurrent sbt server.

## Repair 8 — completion, authored with Codex, 19:24–19:26

**Author: OpenAI Codex, driven by the human architect — not by the Claude subagents.** Recorded explicitly because the ownership matrix assumes a named Claude subagent per artifact class, and this work was produced by a different tool.

**Authorization:** Professor Grechanik's email of 6 November 2026 — "feel free to use any LLM tool that best fits your preferences or budget." The human used Codex while this account was rate-limited (5-hour limit, reset 21:50 America/Chicago).

Work order: [resumption-work-order.md](resumption-work-order.md), 17:44.

### What Codex changed, by modification time

Baseline for attribution: Claude's work is 17:34–17:44; Codex's is 19:24–19:26. `modules/core/**` is entirely Nov 5 and untouched.

| File | Time | Item |
|---|---|---|
| `pipeline/DblpParser.scala` | 19:24 | F1 — validation before contract construction |
| `pipeline/XmlRecordStream.scala` | 19:24 | F8 comment, F9, F10, F11 |
| `jobs/DblpExtractionJob.scala` | 19:24 | F4 conflict reporting in `DeduplicateReducer`. **Not** a map-level guard — `map:51-90` has no `try`; the backstop is in `DblpParser.parse:47-50`, which is the correct place. Corrected 2026-11-06 per the must-fix re-review |
| `pipeline/LocalPipelineRunner.scala` | 19:24 | R2 |
| `pipeline/Blocking.scala`, `pipeline/TaskSink.scala`, `pipeline/IdentifierJoin.scala` | 19:24 | further work over Claude's F3/F7/F4 changes |
| `test/MapReduceEndToEndSuite.scala`, `test/LocalPipelineSuite.scala`, `test/XmlRecordStreamSuite.scala` | 19:24 | module test updates |
| `tests/adversarial/AdversarialCounterSuite.scala` | 19:24 | **new**, 3 cases for the R1/R2 counter contract |
| `tests/adversarial/{DuplicateId,GiantTeam,JobConfig}Suite.scala` | 19:24 | pin conversions and `.fail` twins |
| `tests/adversarial/AdversarialMissingFieldSuite.scala` | 19:26 | pin conversions and `.fail` twins |

### Orchestrator verification of the must-fix items

Verified by reading the current source, not from any report.

| Item | Status | Evidence |
|---|---|---|
| **F1** | **Addressed** | `DblpParser.scala:147` rejects a blank or unmatchable author element and `:151` rejects a title that normalizes to empty, both as `MissingRequiredField` — so validation now precedes construction and the formerly dead guard is reachable |
| **F9** | **Addressed** | `XmlRecordStream.scala:115` adds `var quote = -1` with quote branches at `:123-128`, so a `>` inside a quoted attribute value no longer closes the tag |
| **F10** | **Addressed** | `:118-120` sets `exhausted` and `closed` on EOF instead of throwing, so a truncated record returns rather than destroying the split |
| **F11** | **Addressed** | `:147-150` uses a reporting `UTF_8.newDecoder()` with a `CharacterCodingException` catch, replacing the silent `new String(bytes, UTF_8)` substitution |
| **F3 + F7** | **Addressed** (Claude, 17:34; Codex touched `Blocking.scala` again at 19:24) | bound, counter and distinct reject code as described in repair 7 |
| **F4 + F13a** | **Addressed** (Claude, 17:35; Codex touched `IdentifierJoin.scala` again at 19:24) | full structural equality via `claimed.distinct`; replay still counted because `sourceCounts:143-144` reads `claimed`, not `distinct`; conflict count corrected to `max(0, size - 1)` at `:183`. **Not** via `identicalReplays`, which does not exist — see the correction above |
| **R1** | **Addressed** (Claude, 17:36) | four reducer sites switched to the non-counting `sink.emit` |
| **R2** | **Addressed, more completely than dispatched** | `RejectedRecords` is now excluded from the wholesale counter copy at **four** sites (`:270-274`, `:313-318`, `:408-412`, `:483-487`), each followed by `mapSink.rejects.foreach(reduceSink.reject)`. This also resolves the sibling inconsistency the reviewer flagged, where `block()` copied counters but never forwarded rejects |
| **F8 comment** | **Corrected** | `:89-90` now reads "This scanner does not track document-level comment or CDATA regions. Record-shaped text inside those regions remains a known limitation," replacing the claim that asserted the opposite. `:101-102` likewise corrected to "Quotes protect `>` in attribute values. Comment and CDATA regions are not tracked" |
| **F12 digest collision** | **NOT fixed — carried forward instead** | `jobs/JobConfig.scala` is unchanged at 02:03 and contains no delimiter guard; `pipeline/RunContext.scala` unchanged at 02:37. A `.fail` twin was added instead at `AdversarialJobConfigSuite.scala:186` |

### The `.fail` twins

Nine twins present, and the orchestrator verified each sits on a defect that is genuinely still **open**, which was the hazard flagged at dispatch:

| Twin | Finding |
|---|---|
| `AdversarialDuplicateIdSuite.scala:271` | F5 |
| `AdversarialDuplicateIdSuite.scala:290` | F13e |
| `AdversarialMissingFieldSuite.scala:211` | The `SingleAuthorWorks` conflation of zero authors with one, pinned at `:50-66` — **not** F13c's constructible zero-author `WorkRecord`. Label corrected 2026-11-06; both are open |
| `AdversarialJobConfigSuite.scala:155` | F12 comma half |
| `AdversarialJobConfigSuite.scala:171` | F12 equals half |
| `AdversarialJobConfigSuite.scala:186` | F12 digest collision |
| `AdversarialGiantTeamSuite.scala:207` | F13b |
| `AdversarialXmlSuite.scala:69` | F8 skip-region half |
| `AdversarialXmlSuite.scala:182` | R6 |

`AdversarialUnicodeSuite.scala:87` carries an explicit comment that **no** `.fail` twin was added for F2, "a twin would assert a key shape that does not exist" — which matches the architect's decision and the reviewer's refinement. F6 likewise has none. Both correct.

### Scope and boundary verification

- **`modules/core/**` untouched.** Every file dates to Nov 5; no contract, identifier, serialization or config source was altered. The F1 remedy was applied at the hadoop construction sites, as the reviewer required.
- **`build.sbt` unaltered beyond what was already approved.** `git diff --stat` still reports `build.sbt | 45 +++++--`, 43 insertions and 2 deletions — byte-identical to `build-engineer`'s approved change. The minicluster line was not removed; that experiment remains pending.
- **No constitutional file moved.** `git status --short` lists exactly one modified tracked file, `build.sbt`. `CLAUDE.md`, `docs/agents.md`, `.mcp.json`, `.claude/**` and `project/**` are tracked and unmodified.
- **All source changes stayed inside `modules/hadoop/**` and `tests/**`.**

### Deviation from the dispatch

One: the **F12 digest collision was not fixed**, though the dispatch asked for it as a cheap addition the reviewer recommended. It was carried forward with a `.fail` twin instead. Defensible — it was never a must-fix ratification blocker, and a twin is the sanctioned treatment for a carried-forward defect — but it was a scope deviation, so it was routed back to `hadoop-engineer` rather than silently accepted. See repair 9.

## Repair 9 — the F12 digest collision, closed by length framing

Author: `hadoop-engineer`, Claude Opus subagent, 2026-11-06. Usage: 601876 subagent tokens, 390 tool uses.

**Changed: `pipeline/RunContext.scala` only** — a `java.nio.ByteBuffer` import and length framing in the `snapshotId` digest at `:58-63`. Verified by the orchestrator.

The delimiter-based digest is replaced: each component is now digested as a four-byte big-endian length followed by its UTF-8 bytes, the scheme `StableIds.digestId` already uses. The doc comment at `:46-56` states the reasoning well — framing "removes the possibility rather than forbidding the character: the length prefix fixes where each component ends, so no two splits of the same character sequence can produce the same digest input."

### An accepted deviation from the reviewer's stated remedy

The reviewer's remedy was "one `require` at write time plus length framing in the digest." The owner implemented the framing and **deliberately declined the `require`**, with reasoning the orchestrator accepts:

> It cannot close the digest collision, because `snapshotId` is computed without any round-trip, and adding it would flip `:143`, `:155` and `:171` — including the two twins you asked to keep green.

That is correct. The reviewer had bundled remedies for two different halves of F12: the `require` addresses the **serialization** defect, which stays carried forward, while framing addresses the **digest collision**, which is reachable from `RunContext` alone with no configuration round-trip. Only the collision was in scope. Adding the write-time guard would additionally have closed a carried-forward defect and tripped two twins the architect had explicitly decided to leave green.

Recorded because it is the second time this owner has corrected the final review on a point of mechanism, and both corrections have been right.

### Consequence predicted and routed

The owner predicted **two** reds rather than one, and identified both precisely:

- `AdversarialJobConfigSuite.scala:186`, the digest `.fail` twin, tripping as designed because its body now passes.
- `AdversarialJobConfigSuite.scala:97`, a plain characterization test failing at `:115`, because a lossy round-trip now changes the digest instead of being masked by it. Verified: `Map("db=lp" -> "2026-11-01")` frames as `(5,"db=lp")(10,"2026-11-01")` while `Map("db" -> "lp=2026-11-01")` frames as `(2,"db")(13,"lp=2026-11-01")`.

Both belong to `test-engineer` and are routed to it in the same cycle, so the next check should be clean rather than carrying two known reds. The owner verified that `:155` and `:171` compare only `datasetSnapshots` and never `snapshotId`, so F12's serialization twins stay green and that half remains open.

**This is the first `.fail` twin to trip, and it tripped for the right reason** — a defect was fixed and the tripwire refused to let the fix land silently. The mechanism `test-engineer` proposed and the architect adopted did what it was designed to do.

### Repair 9b — the two flips, `test-engineer`

Modified `tests/adversarial/AdversarialJobConfigSuite.scala` only. Verified by the orchestrator: `:193` `distinct snapshot maps have distinct digests` no longer carries `.fail`; `:97` is renamed to `a renamed dataset moves the snapshot digest and the output prefix`; the two serialization twins at `:161` and `:177` are intact.

The owner verified the framing arithmetic itself before flipping rather than trusting the dispatch, and independently endorsed the omission of the write-time `require` on the same grounds.

Flip 2 was **strengthened**, not merely inverted: a third assertion pins the digest as a **pure function of the map**, with no round-trip involved, which is the property framing actually delivers.

### A consequence neither the review nor the orchestrator anticipated

**Closing the digest collision widened the observable damage of F12's still-open serialization half.**

`JobSupport.configureOutput:46-51` sets the output path client-side from the **original** `RunContext`, while each task's provenance comes from the **round-tripped** one (`JobConfig.read:59-72`). Until now the equals half was invisible there, because the colliding digest made path and provenance agree **by accident**. With framing in place they no longer do: an equals-bearing dataset name would commit records whose `provenance.snapshotId` disagrees with the `snapshot=` segment they are stored under.

So the CLAUDE.md provenance violation the final review attributed to F12's **comma** half alone now reaches the **equals** half too. This is not a regression — the serialization defect is unchanged and the framing fix is strictly correct — but the accidental masking is gone.

**Consequence for the carried-forward decision:** the final review's condition on F12 was "fixed before any snapshot source becomes config- or manifest-driven." That condition is now weaker protection than it was, because the damage is no longer masked. The orchestrator records this as an argument for fixing the serialization half rather than carrying it further, and flags it for the re-review to weigh. It is not a new defect and does not block ratification.

### Triage note for the next run, from `test-engineer`

Its F9, F10 and F11 pin conversions were written against an **API shape it did not choose**: `XmlRecordStream` has no sink, so the reject channel could sit in the reader, in `DblpXmlInputFormat`, or in the job, and the owner picked the shape. If any of those three cases is red, the likeliest cause is the test's assertion not matching the shape that landed, **not** a product defect. Established before the run so the re-review does not infer a product problem from a test mismatch.

The owner also noted its own prediction record this phase is 1-for-2 and that it has not executed anything, which is the right framing for a read-only prediction.

### The F12 twin question, settled in practice

**This was the first `.fail` twin to come due, and it behaved exactly as designed:** green while the defect was open, red the instant it was fixed, and the only work it created was deleting one tag. A fix could not have landed silently. Nine twins went in; one has now paid for the mechanism in practice rather than in argument.

### `hadoop-engineer` also raised the unowned-path question unprompted

It flagged `.agents/`, `.codex/` and `AGENTS.md` as having no owner in `docs/agents.md`, noted that `AGENTS.md` "sits beside the constitutional files," and said it had not touched them. That matches the orchestrator's own finding, recorded in [versions.md](versions.md), and the unassigned-path rule it cites is the correct one.

## Repair 6 — the run-7 reduce-counter failure

Author: `hadoop-engineer`. Received by the orchestrator 2026-11-06.

Trigger: the single failing case in validation run 7, `job 2 executes and consumes job 1's committed output`, at `MapReduceEndToEndSuite.scala:329`.

Usage as reported by the runtime: 540708 subagent tokens, 12 tool uses, 196600 ms.

### Conclusion: reading 1, with readings 2 and 3 refuted

The dispatch required the owner to choose among three readings on evidence. Its answer:

> **Conclusion:** reading 1. The test read a `JobCounter` the `LocalJobRunner` path never increments. Readings 2 and 3 are refuted from the code — job 2 configures 2 reduce tasks and a reducer class, and only `ConsolidateReducer`'s `ArtifactSink` can write the artifacts whose presence the passing sibling case already established.

So the test was wrong and the job code was right. **No `main` source was changed**, which the orchestrator verified independently: no file under `modules/hadoop/src/main/` is newer than 12:02, while this repair's edits are at 12:14.

Reading 2 is refuted by job 2 configuring 2 reduce tasks and a reducer class, so it is not map-only. Reading 3 is refuted by the artifact-provenance argument: only the reducer's sink can write those artifacts, and the sibling case already established they exist.

### The fix converts the diagnosis into an executed check

Rather than simply swapping the counter, the owner added a case asserting `TOTAL_LAUNCHED_REDUCES == 0` **alongside** `REDUCE_INPUT_RECORDS > 0`. That pins the `LocalJobRunner` quirk itself, so the reason the original counter was unusable is recorded as a test rather than as a comment, and nobody can reinstate the launched-task counter without the suite objecting.

This is the right shape for the fix. It is a correction, not a relaxation: the reduce phase is still proven by an assertion that can fail, and the replacement instrument cannot pass without a reduce having consumed shuffled input.

| Change | File |
|---|---|
| Counter correction plus the quirk-pinning case; 14 cases now | `test/MapReduceEndToEndSuite.scala` |
| Default log level `warn`, fixing the INFO flood that truncated two run-7 attempts | `test/resources/simplelogger.properties` (new) |

Orchestrator checks: `simplelogger.properties` exists at 937 bytes, 12:14. `MapReduceEndToEndSuite.scala` contains **14** `test(` blocks, up from 13. Test inventory moves to **146 cases across 10 suites**.

### The owner's watch item for run 8, and why it matters

The owner hand-derived three values of `18` for `MAP_OUTPUT_RECORDS`, `REDUCE_INPUT_RECORDS` and `REDUCE_INPUT_GROUPS`, and drew a distinction worth preserving exactly:

> If `MAP_OUTPUT_RECORDS` differs, my emission count is wrong and it is a test error. If map output is 18 but reduce input is lower, records are being lost in the shuffle — that would be a real defect, not a test bug, and worth escalating rather than adjusting.

The orchestrator records this as binding on the next repair cycle. A mismatch between map output and reduce input is **not** to be fixed by adjusting the expected number.

### Caveat the owner left explicit

> I converted the launched-task-counter diagnosis from an inference into an executed check, but I could not perform it. The new case asserting `TOTAL_LAUNCHED_REDUCES == 0` alongside `REDUCE_INPUT_RECORDS > 0` is what establishes it, and that happens on run 8, not here.

Correct, and recorded as such: the diagnosis is currently reasoned from code, and run 8 is what turns it into executed evidence.

### Two durability notes from the orchestrator

1. **Job 2 configures 2 reduce tasks, but `LocalJobRunner` runs one.** This confirms the limit already recorded against the MapReduce gate item: multi-reducer fan-out remains unexercised. The partitioner is unit-tested and the partition map asserted, but no run has distributed one job's keys across more than one live reducer.
2. **Asserting `TOTAL_LAUNCHED_REDUCES == 0` pins a runtime behavior, not a project invariant.** If a future Hadoop upgrade starts populating that counter under `LocalJobRunner`, the case will fail for a benign reason. That is an acceptable trade for documenting the quirk, but `dependency-updater` should expect it on a Hadoop version bump. Flagged for the re-review to confirm the case says so in place.

### Gate status after repair 6

No gate item closed yet. Validation run 8 belongs to the human. If green, the MapReduce gate item closes with executed evidence, subject to the single-reduce-task limit above.

### Gate status after repair 5

No gate item closed yet. Test inventory moves from 132 to **145 cases across 10 suites**, all of repair 5 uncompiled and unrun. Validation run 7 belongs to the human. If `MapReduceEndToEndSuite` passes, the MapReduce gate item closes with executed evidence, subject to the `LocalJobRunner` caution above.
