# Phase 4 re-review

Reviewer: `code-reviewer`. Owners of the reviewed work: `hadoop-engineer` (F1–F5 repairs), `build-engineer` (build change). Received by the orchestrator 2026-11-06.

Work order: [re-review-work-order.md](re-review-work-order.md), plus two mid-task additions from the architect. Prior verdict: [review.md](review.md). Validation: [validation.md](validation.md), run 5 passed.

Usage as reported by the runtime: 144363 reviewer tokens, 36 tool uses, 393056 ms.

Reviewer declarations: read-only throughout, no file created or modified, no command run. All execution evidence quoted from `validation.md` (human-run `sbt clean check`, run 5, 132/132, 0 skipped).

## Two verdicts, stated separately

**On the owner's work: APPROVE.** F1 through F5 are all CLOSED on code evidence, not on the strength of a passing suite. The build change is correct on its merits. Two new minor findings, F7 and F8, are recorded as non-blocking.

**On Phase 4's readiness for ratification: NOT READY.** Two specification gate items remain open, neither owned by `hadoop-engineer`:

- "At least two real MapReduce jobs execute" — OPEN; run 5 does not close it.
- Adversarial cases — PENDING; `test-engineer` undispatched.

F6 reporting is substantially but not fully satisfied.

## Was any test weakened? (architect addition 1)

**Standalone conclusion: no.** Across all of F1–F5, nothing was made to pass by loosening an assertion, narrowing a fixture, deleting coverage, or relocating a case out of the path that exercises it. Every repair strengthened the net assertion set.

1. **No coverage was deleted.** The F1 tautology's only non-vacuous content, the `Blocking.key`/`kindOf`/`strategyOf` round-trip, is independently covered at `BlockingAndCandidateSuite.scala:116-127`. Case counts reconcile exactly: `BlockingAndCandidateSuite` 18→22, `LocalPipelineSuite` 26→28, totalling the reported 132.
2. **No fixture was narrowed.** F1 and F3 widened theirs: F1 moved from a hand-restated key literal to a real two-author `WorkRecord` driven through `Blocking.researcherKeys`; F3's agreement case uses 8 records / 7 distinct members / bound 4, strictly harder than the old `Vector(one, one)` at bound 50.
3. **Qualification — one F3 assertion cannot fail today.** In `the reducer path and the whole-block path agree exactly` (`BlockingAndCandidateSuite.scala:242-269`), `selectedGroup == wholeGroup` is tautological **by delegation**: the fix made `Blocking.bound` delegate to `boundSelected` (`Blocking.scala:407-414`), so both sides are the same computation on identical input. The reviewer accepts it as a regression guard against the two paths re-diverging — the exact defect it is named for — and notes the case's substantive content is genuine elsewhere (`observedSize == 8`, `retainedSize == 4`, `truncated`, `distinct retained == 4`). Called out because the architect asked for this class of observation, not because it needs a fix.
4. **Qualification — one expected value was flipped, legitimately.** The old case asserted `observedSize == 1` for `Vector(one, one)`; the new one asserts `observedSize == 2` (`:227-240`). A redefinition, not a relaxation: the prior verdict asked the owner to choose one definition and it chose "records the reducer received," documented at `Blocking.scala:116-125`. The new case asserts three facts where the old asserted one.

**Every new or changed case reaches its named subject**, verified by construction:

- F1 (`:129-174`) — the two sides derive from two *different* mentions. For `Michael Ley` the name block `ley|m` sorts below coauthor `schenkel|r` and takes the `then` branch at `Blocking.scala:238-240`; for `Ralf Schenkel` the `else` branch. One pair exercises both sides of the ordering the prior verdict named untested. The exact key `researcher|name-coauthor|ley|m|schenkel|r` is pinned.
- F2 (`LocalPipelineSuite.scala:385-391`) — the `of<N>` count is interpolated from `groups.size`, not hardcoded.
- F3 — the path under test is `boundSelected`, which both reducers take.
- F5 (`LocalPipelineSuite.scala:240-277`) — three `intercept[IllegalArgumentException]` blocks; each would fail if the `require` guards were removed.

## The 7 previously skipped tests (architect addition 2)

**All 8 case names accounted for. None dropped, renamed, merged or folded.**

| Run-4 name | Run-4 state | Now at | Needs `Configuration`? |
|---|---|---|---|
| `a run context survives a job configuration round-trip` | FAILED | `JobWiringSuite.scala:48` | Yes, `:51` |
| `the round-trip preserves a non-default bound` | skipped | `JobWiringSuite.scala:65` | Yes, `:72` |
| `snapshots round-trip as a sorted dataset list` | skipped | `JobWiringSuite.scala:77` | Yes, `:78` |
| `an absent or malformed snapshot list yields no snapshots` | skipped | `JobWiringSuite.scala:92` | Yes, `:93,:94,:97` |
| `a configuration missing the run identity is refused, not defaulted` | skipped | `JobWiringSuite.scala:102` | Yes, `:105,:108` |
| `the partitioner routes on the logical key, ignoring the stream label` | skipped | `PartitionerWiringSuite.scala:26` | No |
| `partition indices stay in range and are stable across calls` | skipped | `PartitionerWiringSuite.scala:48` | No |
| `an unlabeled key partitions as itself` | skipped | `PartitionerWiringSuite.scala:68` | No |

Names byte-identical to the run-4 skip list. 5 + 3 = 8.

**The `Configuration`-dependent cases were not rewritten to avoid a `Configuration`.** All five call `emptyConfiguration()` (`JobWiringSuite.scala:46`), which is `new Configuration(false)` — the exact construction that threw in run 4. The dependency fix is genuinely load-bearing for their passing.

**`a configuration missing the run identity is refused, not defaulted` does assert refusal.** `:102-109` uses two `intercept` blocks against two distinct refusal paths in `JobConfig.scala`: snapshots present with no run ID → `requiredValue` throws `IllegalStateException` (`:74-79`); empty configuration → `require(pinned.nonEmpty)` throws `IllegalArgumentException` (`:61`). Two different exception types, so neither block can absorb the other's failure. For the record, `transformVersion`, `partitionCount` and the three bounds *do* default silently (`:64-71`), correctly, and the case name scopes itself to run identity, meaning `runId` plus `outputRoot`. The name is accurate.

**Evidence limit: the "verbatim" move of the 3 partitioner cases cannot be byte-verified.** `modules/hadoop/**` is untracked, so no run-4 baseline exists in git and the reviewer has no copy. What is establishable: the three cases reference only `StableKeyPartitioner`, `Text`, `PartitionKey` and `StreamKey`, never `Configuration` (`PartitionerWiringSuite.scala:1-6`), corroborating that they never needed one; and their assertions are substantive — case 1 compares two differently-labelled keys *and* pins the result against the independent `PartitionKey.partitionOf`, case 3 likewise, case 2 asserts stability and range over 4 keys × 8 partition counts. If weakened during the move, the weakening is undetectable because the current text is strong.

**The split weakens nothing.** The partitioner cases gained the ability to run and lost no assertion. `PartitionerWiringSuite.scala:16-23` states correctly why it is not redundant with `IdentifierAndKeySuite`: that suite composes `PartitionKey` and `StreamKey` the way the partitioner is *supposed* to, and cannot detect `StableKeyPartitioner.getPartition` composing them wrongly.

**Orchestrator's reading of the skips: CONFIRMED.** Neither suite contains `assume`, `.ignore`, `munitIgnore` or any skip mechanism. The skips were collateral of a `LinkageError`, as `JobWiringSuite.scala:33-35` explains.

## Prior findings: CLOSED / NOT CLOSED

**F1 — CLOSED.** `BlockingAndCandidateSuite.scala:129-174`. Both branches of the ordering at `Blocking.scala:238-240` now exercised. Minor note, no action: `a mention derives one coauthor key per distinct coauthor` does not test distinctness — no duplicate coauthor in the fixture — so the `.distinct` and `.filterNot(_ == nameBlock)` filters at `Blocking.scala:235-236` remain uncovered.

**F2 — CLOSED.** `LocalPipelineSuite.scala:385-391`. Label reads `blockingGroupSizesTop10of${groups.size}`; label and content cannot drift. Cross-checked at `:301-304`.

**F3 — CLOSED, with a reporting consequence recorded.**

- *One definition across all three sites.* `Blocking.observed` is `received` (`Blocking.scala:103,107,125`). `group(...)` at `:367-381` is the only `BlockingGroup` construction site; `boundSelected` at `:389-398` its only caller. Both reducers call `boundSelected` and **no longer override** the group handed to them (`BlockingJob.scala:108`, `LocalPipelineRunner.scala:319`). A grep for `observedSize` finds no other write. The former `ordered.size`-after-`distinctBy` definition is gone.
- *`OversizeBlock` no longer duplicate-inflated.* `BlockingJob.scala:109-110` and `LocalPipelineRunner.scala:320-321` both compare `selector.observed` against the bound. `CandidateFeatureJob.scala:102` uses the same definition, so all three oversize sites agree.
- *`truncated` cannot be set when nothing was dropped.* `evictedAny` is set only inside the `heap.size > limit` branch after an actual `dequeue` (`Blocking.scala:111-114`) — ground truth from the eviction, not inferred. Pinned at `:227-240` and `:271-285`.
- *The agreement test exercises the shipped path.* Yes; see the delegation qualification above.

**Recorded consequence for F6 and the Spark side:** `largestBlockingGroup` now means *the largest record count delivered to one reducer*, not *the largest distinct block*. For the measured sample the two coincide, so no figure moved across runs 3–5. Any Phase 4 report quoting it must say which definition applies. Secondarily, an `OversizeBlocks` counter and reject can now fire while `truncated` is false, when a block exceeds the bound only by duplicate records — correct under the chosen definition, but counter and `truncated` are no longer interchangeable signals.

**F4 — CLOSED, on reading only, as the work order anticipated.** The false overwrite claim is gone from all three sites, each now stating what the design gives and an explicit rerun policy: `RunContext.scala:14-24` ("That is addressing, not overwrite semantics… `FileOutputFormat` rejects an existing output directory at submission with `FileAlreadyExistsException`… either delete the prefix first and rerun, or mint a new run ID"), reinforced at `:78-83`; `PipelineDriver.scala:13-21`, which additionally labels the claim as read from Hadoop's contract and not observed; and `PipelineOptions.scala:9-17`, the third site the owner found itself. An independent sweep for `overwrit|accumulat|idempot` across the module found no fourth false site; the three surviving uses of "overwrite" all now *deny* overwrite semantics. The F4 widening is genuine.

**F5 — CLOSED structurally, not merely documented.** `artifactPath(artifact)` now carries `require(!artifact.datasetScoped, …)` (`RunContext.scala:112-117`) with a mirror guard plus non-blank check on the dataset-bearing overload (`:122-128`). All five `Normalized*` artifacts are `datasetScoped = true` (`:245-264`), so the datasetless accessor **throws** for them. `normalizedPath` is gone; `PipelineDriver.scala:52-53,59-60,84-88` compose through `artifactPath`, so driver and downstream reader share one definition. Path correspondence verified against the writing side: `JobSupport.configureOutput` sets output to `run.stagePath(stage, name)` when a dataset is given (`:46-51`), only `SourceNormalizationJob.scala:181` passes one, and `ArtifactSink` writes `<segment>/v1/part` relative to it (`HadoopSink.scala:77-82`).

Residual, non-blocking: the case named `a dataset-scoped path matches what the sink actually commits` (`LocalPipelineSuite.scala:257`) composes both sides from `RunContext` and never references `ArtifactSink`, so a change to `HadoopSink.scala:81` would not fail it. Sink correspondence rests on the reviewer's reading — unavoidable while `ArtifactSink` has no executed coverage, and worth a real assertion once a mini-cluster run exists.

**F6 — still PARTIALLY SATISFIED.** The measured figures are delivered, stable across three runs and internally cross-checked; that part is discharged. Still undelivered in prose: the job graph (an assertable value at `PipelineDriver.scala:32-42`, referenced by no test and no report), the per-job mapper and reducer key-value contracts, per-stage counter values rather than two aggregates, and the full reject and output path set. The definitional note on `largestBlockingGroup` must be included when these are written.

## The five specific questions

**1. Does the `jobs/**` coverage change the prior assessment? Yes, narrowly — from 0 of 14 files executed to 2 of 14.**

Now executed: `JobConfig.write`, `read`, `readSnapshots` including both refusal paths; `StableKeyPartitioner.getPartition` including label-stripping, range and stability. The second is materially more than a smoke test, because `IdentifierAndKeySuite` could not detect `getPartition` composing `PartitionKey` and `StreamKey` wrongly.

Still execution-free, 12 of 14: `DblpExtractionJob`, `DblpXmlInputFormat`, `SourceNormalizationJob`, `IdentifierJoinJob`, `BlockingJob`, `CandidateFeatureJob`, `PublicationTeamJob`, `GraphExportJob`, `JobSupport`, `HadoopSink`, `PipelineDriver`, `PipelineOptions`. Within `JobConfig`, `enableIntermediateCompression` and `disableSpeculation` remain unexecuted — they need a `Job`, so "compression for intermediate outputs" rests on inspection alone.

**`DeduplicateReducer` and `NormalizeReducer` still rest on inspection alone.** A grep across the test tree for `DeduplicateReducer|NormalizeReducer|JobSupport|PipelineDriver|PipelineOptions|ArtifactSink|MapOutputSink|DblpXmlInputFormat|enableIntermediateCompression|disableSpeculation|jobGraph` returns exactly one hit, and it is a comment (`LocalPipelineSuite.scala:260`). The prior verdict's risk 2 stands.

Cheap wins now unlocked: `JobSupport.configureOutput` is assertable against a real `Job` (output path, partitioner class, reduce-task count, named outputs, compression and speculation keys), and `PipelineDriver.jobGraph` is assertable as a DAG in stage order. Both would convert several PASS-but-untested items into executed evidence without a mini-cluster. Recommended as a `hadoop-engineer` item, not a blocker.

**2. Was the partitioner-suite split sound? Yes, and the 7 skips were genuinely collateral.** See above.

**3. Tautology sweep — audited independently; the owner's count is correct.** The reviewer enumerated all 132 cases across the nine hadoop suites and inspected every case whose shape could be vacuous.

- One true tautology, F1, fixed. No second case builds both sides from identical arguments.
- Five determinism checks calling one function twice on one input, all legitimate because the function could plausibly be nondeterministic through iteration order, hashing or locale: `BlockingAndCandidateSuite.scala:399`, `IdentifierAndKeySuite.scala:173`, `DblpParserSuite.scala:98`, `TeamExpansionSuite.scala:176`, `SourceNormalizerSuite.scala:231`. Each carries range or value assertions beyond the equality. `PartitionerWiringSuite.scala:48` is a sixth of this shape, near-tautological on determinism alone but carrying a substantive range invariant, so not counted as vacuous.
- Five swapped-input symmetry checks, all sound: `BlockingAndCandidateSuite.scala:309-311`, `:326-329`, `:366-368`, `IdentifierAndKeySuite.scala:34-37`, `:190-197`. Each compares genuinely different inputs.
- The case re-checked by byte evidence: `IdentifierAndKeySuite.scala:136-139`. A targeted search for `Universit\x{00e4}t` matches **line 137 only**; `Universita\x{0308}t` matches **line 138 only**. Precomposed versus decomposed, confirmed independently.
- Also checked and genuine: `a member is never compared with itself` (`:356`), `two mentions on one paper are never a researcher candidate` (`:348`), `a key with no label decodes to itself` (`IdentifierAndKeySuite.scala:201`), `the coauthor key is unchanged when the author order is reversed` (`:146`).

One structural qualification: the F3 agreement assertion is tautological-by-delegation — a sixth instance of the shape, introduced by the repair itself, legitimate as a re-divergence guard.

**4. The SLF4J NOP diagnostic does not matter for counter and reject visibility. It will matter for the first mini-cluster run.**

The module contains no logging. A grep for `slf4j|Logger|getLog|System.err|println` across `modules/hadoop/**` returns two hits: `PipelineDriver.scala:143` (`System.err.println`) and `LocalPipelineSuite.scala:403` (the measured `println`). Neither uses SLF4J. Counters are Hadoop counters via `context.getCounter` (`HadoopSink.scala:43-44,94-95`); rejects are artifact files via `MultipleOutputs` (`:84-92`) and the versioned reject path (`RunContext.scala:133-134`). The requirement is satisfied through data paths, not log output, so a NOP binding discards nothing the gate depends on.

Where it bites: when a job first executes, Hadoop's own diagnostics — job submission, task failure stacks, and specifically the `FileAlreadyExistsException` F4 concerns — would be discarded, on exactly the run most likely to need them. Recommended to `build-engineer` as a low-priority item bundled with the mini-cluster dependency. **Note: an SLF4J binding at `Test` scope needs a new version value in `project/DependencyVersions.scala`, which is `dependency-updater`'s, with the declaration `build-engineer`'s** — the same split as the run-4 fix, but this time the ledger genuinely needs a new key.

**5. The build change is correct on its merits.** `build.sbt:112-116`.

- *Scoping is right for a cluster-deployed job.* `hadoop-client-runtime` at `Test` only; a cluster supplies the shaded runtime, and shipping it in the job jar would risk duplicate shaded classes against the cluster's own. `hadoop-client-api` stays `Provided`, off the assembled artifact but on both compile and test classpaths. At test runtime both jars are present, which is the combination Hadoop intends for off-cluster use.
- *Nothing added at `Compile` or `Provided` that should not be.* One `Test` entry plus a rationale comment. No `ClassLoaderLayeringStrategy` change — correctly, and the orchestrator's artifact-level diagnosis is sound.
- *The ledger key reference is right.* `DependencyVersions.hadoop` rather than a literal. No version value changed. The `dependency-updater` contingency never triggered: run 5 resolving proves `hadoop-client-runtime` is published at 3.5.0.
- *The rationale is recorded in the build file*, the right place for a non-obvious scope choice.

Authority held on both sides: `build-engineer` touched only `build.sbt`; `hadoop-engineer` observed the change and declined to touch it.

## The MapReduce gate item

**The orchestrator's position is CONFIRMED. A green run 5 does not close it.** Three independent reasons:

1. `hadoop-client-runtime` supplies the shaded classes `Configuration` needs on construction. It does not supply `LocalJobRunner` plumbing exercised end to end, a job submission, split assignment, a task JVM, a shuffle, or an `OutputCommitter`. `hadoop-client-minicluster` is absent; the module declares exactly two hadoop entries, `build.sbt:112` and `:116`.
2. What executed is `JobConfig` round-tripping through a `Configuration` and `StableKeyPartitioner.getPartition` returning an `Int`. Neither is a job. The specification says "at least two real MapReduce jobs **execute**." The classes are genuine MapReduce — confirmed at the prior review, unchanged — but genuineness was never the open question. Execution was.
3. The pipeline's own test code says so, honestly: `LocalPipelineSuite.scala:22-24` states the local run "is not evidence that a Hadoop job executes," and `JobWiringSuite.scala:36-38` states that even once these cases run they cannot establish "that any job *executes*. That needs a `LocalJobRunner` or mini-cluster."

Routes to `build-engineer` as a new item. On the critical path, and the one gate item no amount of further `hadoop-engineer` work can close. Related: F4's submission-time behavior and the twelve unexecuted `jobs/**` files mean the first real execution should be expected to surface configuration-level defects.

## New findings, both minor and non-blocking

**F7 — Stale comments asserting a build state that is no longer true.** Same defect class as F4; both were accurate when written and were overtaken by the build change.

- `JobWiringSuite.scala:14-31` — "**Runtime side, blocked**… This suite therefore stays red until `hadoop-client-runtime` is on the Test classpath." It is on the classpath and the suite is green at 5 of 5. The header should record the run-5 resolution and keep the standing limit at `:36-38`, which remains correct and valuable.
- `LocalPipelineSuite.scala:22-24` — "That requires Hadoop's runtime on the test classpath, which this module's build does not yet provide." The runtime jar is now provided; what is missing is a mini-cluster or `LocalJobRunner`. As written it understates the build and misnames the blocker.

Owner: `hadoop-engineer`. Comment-only.

**F8 — An oversize reject message that says "members" where the figure counts records.** `BlockingJob.scala:117` and `LocalPipelineRunner.scala:328` both emit `s"Block $blockKey holds $observed members, above the bound of ${run.maxBlockSize}"`, where `$observed` is the received record count including duplicates. Under the now-explicit F3 definition this can state a member count larger than the block's distinct membership, in the one artifact an operator reads when diagnosing a pathological block. Suggested: "holds $observed records". Owner: `hadoop-engineer`.

## Gate status as the reviewer reads it

| Gate item | Verdict | Basis |
|---|---|---|
| Two real MapReduce jobs **execute** | **OPEN** — `build-engineer`, mini-cluster runtime | Section E of the verdict |
| DBLP parser handles record boundaries | PASS | `XmlRecordStreamSuite`, `DblpParserSuite`, run 5 |
| Pair generation on 1, 2, 3, larger | PASS | `TeamExpansionSuite`, run 5 |
| Counters and reject outputs visible | PASS, unaffected by the SLF4J NOP binding | question 4 |
| Repeated runs produce equivalent output | PASS, strengthened — identical measured output across runs 3, 4 and 5 spanning two repairs and a build change | `validation.md` |
| Sample results contain provenance | PASS | `LocalPipelineSuite.scala:85-94`, run 5 |
| `test-engineer` adversarial cases | **PENDING** — undispatched | `tests/` holds no Phase 4 cases |
| Code reviewer returns APPROVE | **APPROVE on the owner's work; phase not ratifiable** | this verdict |

Required operational behavior, changes since the prior verdict: explicit partition strategy moves from PASS-by-inspection to **PASS on executed evidence** (`PartitionerWiringSuite`, 3 of 3); idempotent paths move to **PASS** with F4 and F5 discharged; intermediate compression remains PASS-by-inspection and is now cheaply testable; the mini-cluster half of the integration requirement remains **PARTIAL**.

## Reviewer declarations

- Status COMPLETE. No file created or modified, no command run.
- Test and validation results quoted, not executed: human-run run 5, 132/132, 0 skipped, marker current.
- Assumptions: the run-5 transcript in `validation.md` is faithful; no source changed between run 5 and this reading.
- Evidence limits: the verbatim move of the three partitioner cases cannot be byte-verified, since `modules/hadoop/**` is untracked and no run-4 baseline exists; F4 rests entirely on reading; the `ArtifactSink` path correspondence behind F5 rests on reading, not assertion.
- Risks: twelve of fourteen `jobs/**` files remain unexecuted, so the first mini-cluster run should be expected to find configuration-level defects, with F4's submission behavior the concrete candidate; `DeduplicateReducer` and `NormalizeReducer` have no coverage of any kind; Hadoop diagnostics are discarded under test, which will hamper that first run.
- No further `sbt check` is needed for this verdict; the marker is current and nothing was edited.
- Blocked-on: `build-engineer` for a mini-cluster or `LocalJobRunner` test runtime (hard gate, critical path) and, bundled, an SLF4J `Test` binding with its version value routed to `dependency-updater`; `test-engineer` for adversarial cases under `tests/`; `domain-engineer` for the `MatchCandidate` core contract, **not a Phase 4 gate item**, also Phase 5; `hadoop-engineer` for F7 and F8, and optionally the `JobSupport`/`jobGraph` cheap-win tests.
- This verdict does not approve its own findings and is itself subject to independent review.
