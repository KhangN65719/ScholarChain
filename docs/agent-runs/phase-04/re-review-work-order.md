# Phase 4 re-review work order

Owner: `code-reviewer`. Dispatched by the orchestrator 2026-11-06, after validation run 5 passed.

Prior verdict: `REQUEST CHANGES`, recorded with evidence at [review.md](review.md).

## Authority

Read-only. No repository write, no shell execution, no self-approval. Return `APPROVE` or `REQUEST CHANGES` with exact file locations and evidence.

This is an independent re-review, not a self-check: the reviewer did not write any of the repairs below and must not treat its own prior findings as discharged merely because the owner says so. Verify each against the code.

## Verification status

`sbt clean check` **passed** at run 5, 2026-11-06 09:59, executed by the human operator: 132 tests, 132 passed, 0 failed, 0 errors, **0 skipped**. The marker is current. Runs 1, 2 and 4 failed; runs 3 and 5 passed. Full record in [validation.md](validation.md).

Agents do not run `sbt check` in this project. That is the accepted workflow, not a gap to flag.

## Scope

The re-review covers what changed since the prior verdict, and the two items the prior verdict left for the orchestrator.

### Changed since the prior review

Repair 3 and repair 4, both archived in [repairs.md](repairs.md), plus one build change by `build-engineer`:

| Area | Files |
|---|---|
| F1 replacement | `modules/hadoop/src/test/scala/scholarchain/hadoop/BlockingAndCandidateSuite.scala` |
| F2 labeling | `modules/hadoop/src/test/scala/scholarchain/hadoop/LocalPipelineSuite.scala` |
| F3 `observedSize` | `pipeline/Blocking.scala`, `jobs/BlockingJob.scala`, `pipeline/LocalPipelineRunner.scala`, plus cases in `BlockingAndCandidateSuite.scala` |
| F4 comment corrections | `pipeline/RunContext.scala`, `jobs/PipelineDriver.scala`, and a third site the owner found itself at `jobs/PipelineOptions.scala:12` |
| F5 `artifactPath` | `pipeline/RunContext.scala`, plus cases in `LocalPipelineSuite.scala` |
| New suites | `test/JobWiringSuite.scala` (5 cases), `test/PartitionerWiringSuite.scala` (3 cases, moved verbatim from `JobWiringSuite`) |
| Build | `build.sbt:112-116` — `hadoop-client-runtime % Test` added; `hadoop-client-api` unchanged at `Provided` |

`git diff` now shows the `build.sbt` change only; all `modules/hadoop/**` files remain untracked, so read them directly. The orchestrator verified `git diff -- project/DependencyVersions.scala` is empty: the approved version ledger is unchanged.

## Verify each prior finding

For each, state CLOSED or NOT CLOSED with evidence. Do not accept a finding as closed on the strength of a passing test alone where the fix was substantive.

- **F1** — the tautology at the old `BlockingAndCandidateSuite.scala:61-77` was replaced. Two new cases pass: `the coauthor key is unchanged when the author order is reversed` and `a mention derives one coauthor key per distinct coauthor`. Confirm the replacement actually exercises the lexicographic ordering at `pipeline/Blocking.scala:219-227`, which you identified as untested, and that the retained original case name is no longer tautological.
- **F2** — the measured line now reads `blockingGroupSizesTop10of20=[6,6,6,6,6,6,6,6,6,6]`. Confirm the label is accurate and that the count is derived rather than hardcoded.
- **F3** — three new cases pass: `a duplicate member is retained once but counted as a record received`, `the reducer path and the whole-block path agree exactly`, `truncation is reported only when a member was actually dropped`. This was the subtlest finding. Confirm one definition of `observedSize` now holds across `Blocking.scala:363`, `BlockingJob.scala:107-110` and `LocalPipelineRunner.scala:320-322`; that the `OversizeBlock` threshold comparison at `BlockingJob.scala:111` is no longer duplicate-inflated; that `truncated` cannot be set when nothing was dropped; and that the new agreement test exercises the path the pipeline actually takes rather than `Blocking.bound` directly.
- **F4** — comment-only across three sites. **No executed evidence exists or can exist for this one**, so it rests entirely on your reading. Confirm the corrected text states what the design gives and an explicit rerun policy, and that the false overwrite claim is gone from all three sites including `jobs/PipelineOptions.scala:12`.
- **F5** — two new cases pass: `a dataset-scoped artifact cannot be addressed without its dataset` and `a dataset-scoped path matches what the sink actually commits`. Confirm `RunContext.artifactPath` can no longer return a path nothing writes for the five `Normalized*` artifacts, and that the trap for the Spark-side reader is genuinely closed rather than only documented.

## Specific questions

1. **Does the `jobs/**` coverage change your prior assessment?** At the prior review you found zero test references to that package: 14 files compiled, never executed. Run 5 is the first run in which any of it executes — `JobConfig.write`/`read` round-trips including refusal of a configuration missing the run identity, and `StableKeyPartitioner.getPartition`. State what is now covered, what remains uncovered, and whether `DeduplicateReducer` and `NormalizeReducer` still rest on inspection alone.

2. **Was the partitioner-suite split sound?** The owner reports that a required operational behavior — the partition strategy as implemented in the class Hadoop calls — rested solely on cases that run 4 skipped, and moved the three partitioner cases verbatim into `PartitionerWiringSuite` so they no longer abort alongside `Configuration`-dependent cases. Confirm the cases were moved unchanged and that the split does not weaken anything. Confirm the orchestrator's reading that the 7 run-4 skips were collateral of a suite-level `LinkageError` and not a skip mechanism.

3. **Audit the tautology sweep.** The owner reports one true tautology across all suites (F1, fixed), five legitimate determinism checks and five sound swapped-input checks. This was requested across three dispatches and is the owner's own audit of its own work, so verify it independently rather than accept it. Your prior review already caught one case you suspected of being vacuous and found genuine (`IdentifierAndKeySuite.scala:137-138`); apply the same scrutiny here.

4. **Judge the SLF4J diagnostic.** Run 5 newly emits `SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder"` and defaults to a NOP logger, a consequence of adding `hadoop-client-runtime % Test`. Hadoop log output is therefore discarded under test. `CLAUDE.md` places runtime logging in `build-engineer` scope. State whether this matters for the specification's counter and reject visibility requirements, and whether it needs routing.

5. **Assess the build change on its merits.** `build.sbt:112-116` adds `hadoop-client-runtime` at `Test` only, with `hadoop-client-api` left at `Provided` and a comment giving the rationale. Confirm the scoping is correct for a cluster-deployed job, that nothing was added at `Compile` or `Provided` that should not be, and that the ledger key reference rather than a literal version is right.

## The MapReduce gate item

The orchestrator's position, which you should confirm or correct: **run 5 does not close it.** `hadoop-client-runtime % Test` supplies what `Configuration` needs, not a job runtime; `hadoop-client-minicluster` is absent. Instantiating a `Configuration` and exercising a `Partitioner` is not executing a MapReduce job, so "at least two real MapReduce jobs execute, not local collection operations disguised as MapReduce" stays open and routes to `build-engineer` as a new item.

Say plainly whether a green run 5 should be read as closing this item. If you disagree with the orchestrator, say so and why.

## Standing items not for the owner

- **Adversarial cases** under `tests/` — `test-engineer`, undispatched. Gate item. Record as pending.
- **`MatchCandidate` core contract** — `domain-engineer`, undispatched. Also affects Phase 5.
- **F6 reporting deficiency** — recorded by the orchestrator, explicitly not blocking on the owner. The measured figures are now delivered in [validation.md](validation.md) across runs 3 to 5. Still unreported in prose: the job graph, the per-job mapper and reducer key-value contracts, per-stage counter values, and the full reject and output path set. Restate whether the delivered evidence satisfies the specification's reporting requirement.

## Evidence

- [Phase 4 specification, verbatim](prompt.md) — the gate
- [Work order](hadoop-work-order.md) — scope and constraints
- [Prior verdict](review.md) — your findings F1 to F6
- [Repairs](repairs.md) — repairs 1 to 4 as received, with preservation caveats
- [Validation](validation.md) — runs 1 to 5, measured figures, and the artifact-level diagnosis of the run-4 failure

## Verdict

Return `APPROVE` or `REQUEST CHANGES` to the orchestrator with exact file locations and evidence. If you approve the repairs but the phase cannot pass because of the open gate items, say both clearly and separately: a verdict on the owner's work, and a verdict on Phase 4's readiness for ratification. Do not edit any file and do not run any command.
