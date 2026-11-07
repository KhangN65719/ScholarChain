# Phase 4 validation

## Run 1 — FAILED

Executed by the human operator with the `!` prefix, 2026-11-06 02:07. Not executed by any agent.

Command:

```
SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt clean check
```

Result: **failed at compile.** One error. `.claude/.last-test-run` was not updated and remains stale.

### Output as recorded

```
[info] welcome to sbt 1.11.7 (Eclipse Adoptium Java 21.0.13)
[info] loading settings for project scholarchain-build-build from build.sbt...
[info] loading project definition from /Users/winnguyen/ScholarChain/project/project
[info] loading settings for project scholarchain-build from plugins.sbt...
[info] loading project definition from /Users/winnguyen/ScholarChain/project
[info] loading settings for project root from build.sbt...
[info] set current project to scholarchain (in build file:/Users/winnguyen/ScholarChain/)
[success] Total time: 0 s, completed Nov 6, 2026, 2:07:04 AM
[info] scalafmt: Checking 3 Scala sources (/Users/winnguyen/ScholarChain/modules/core)...
[info] scalafmt: Checking 28 Scala sources (/Users/winnguyen/ScholarChain/modules/hadoop)...
[info] scalafmt: Checking 8 Scala sources (/Users/winnguyen/ScholarChain/modules/hadoop)...
[info] scalafmt: Checking 5 Scala sources (/Users/winnguyen/ScholarChain/modules/core)...
[info] scalafmt: Checking 1 Scala sources (/Users/winnguyen/ScholarChain)...
[info] scalafmt: Checking 3 Scala sources (/Users/winnguyen/ScholarChain)...
[info] compiling 5 Scala sources to /Users/winnguyen/ScholarChain/modules/core/target/scala-3.3.7/classes ...
[info] done compiling
[info] compiling 28 Scala sources to /Users/winnguyen/ScholarChain/modules/hadoop/target/scala-3.3.7/classes ...
[error] -- [E007] Type Mismatch Error: /Users/winnguyen/ScholarChain/modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/SourceNormalizers.scala:516:25 
[error] 516 |                venues = venues,
[error]     |                         ^^^^^^
[error]     |                  Found:    (venues : Option[scholarchain.core.Venue])
[error]     |                  Required: Vector[scholarchain.core.Venue]
[error]     |
[error] one error found
[error] (hadoop / Compile / compileIncremental) Compilation failed
[error] Total time: 8 s, completed Nov 6, 2026, 2:07:13 AM
```

### What this run does and does not establish

- **Established:** formatting passes across all checked sources. `modules/core` compiles (5 sources). The build and the check alias themselves work.
- **Established as broken:** `modules/hadoop` does not compile. One type mismatch at `SourceNormalizers.scala:516`.
- **Not established:** nothing in `modules/hadoop` has been type checked past the first error, and no test in any module executed. The ~90 test cases across 8 suites the owner reported writing remain unexecuted. Every counter value, partition count, maximum team size, largest pair expansion and largest blocking group in the owner's account is still unverified.

Because compilation stopped at the first error, further type errors in `modules/hadoop` are likely still latent. The owner's report predicted the Hadoop inner-class context types, the `MultipleInputs.addInputPath` raw-type arguments in `GraphExportJob`, and the `hadoop-client-api % Provided` test classpath as probable failure sites; this run reached none of them.

## Repair dispatch 1

Routed to `hadoop-engineer` (owner of `modules/hadoop/**`) 2026-11-06, after run 1.

Scope: fix the `SourceNormalizers.scala:516` mismatch against the existing core contract without modifying `modules/core/**`; audit the other normalizers for the same optional-versus-collection error; supply the "Required reporting" items the owner report omitted.

Constraint carried forward: the agent does not run `sbt`. The human runs the next `sbt check`.

The two `BLOCKED-ON` items declared in [owner-report.md](owner-report.md) — the absent Hadoop test runtime (`build-engineer`) and the missing `MatchCandidate` core contract (`domain-engineer`) — are **not** part of this repair and remain unrouted pending the architect's decision.

Repair 1 was received 2026-11-06 and is archived at [repairs.md](repairs.md). It claims three fixes, none compiled. Validation run 2 is pending and belongs to the human.

## Run 2 — FAILED

Executed by the human operator with the `!` prefix, 2026-11-06 02:15. Not executed by any agent. Same command as run 1.

Result: **formatting and compilation passed; one test failed.** 115 tests total, 114 passed, 1 failed, 0 errors. `.claude/.last-test-run` was not updated and remains stale.

### Progress against run 1

- The `SourceNormalizers.scala:516` type mismatch is resolved. `modules/hadoop` main sources compile (28 sources).
- `Test/compile` was reached for the first time: 8 hadoop test sources and 3 core test sources compiled. The owner's predicted test-only type errors did not materialize, and neither did its predicted failures in the Hadoop inner-class context types, `MultipleInputs.addInputPath` in `GraphExportJob`, or the `hadoop-client-api % Provided` test classpath.
- `scholarchain.core.ContractSuite` passed 31 of 31. Core remains green.
- The suite-count discrepancy noted in [repairs.md](repairs.md) is resolved in favor of the repair report: 115 cases is correct. The owner report's "roughly 90 test cases across 8 suites" was wrong.

### The one failure

```
==> X scholarchain.hadoop.SourceNormalizerSuite.no abstract text is copied, only a reference  0.012s
    munit.FailException: modules/hadoop/src/test/scala/scholarchain/hadoop/SourceNormalizerSuite.scala:245 normalization failed
244:      )
245:      .getOrElse(fail("normalization failed"))
246:    val work = record.work.getOrElse(fail("no work"))
    at munit.Assertions.fail(Assertions.scala:274)
    at scholarchain.hadoop.SourceNormalizerSuite.$anonfun$13(SourceNormalizerSuite.scala:245)
    at scala.util.Either.getOrElse(Either.scala:289)
    at scholarchain.hadoop.SourceNormalizerSuite.$init$$$anonfun$15(SourceNormalizerSuite.scala:245)
```

```
[error] Failed: Total 115, Failed 1, Errors 0, Passed 114
[error] Failed tests:
[error] 	scholarchain.hadoop.SourceNormalizerSuite
[error] (hadoop / Test / test) sbt.TestsFailedException: Tests unsuccessful
```

The failure is not an assertion about abstract handling. Normalization returned a `Left` before the test reached its subject, so the test never evaluated what it exists to check. The abstract-reference requirement is therefore **unverified**, not refuted.

This test covers the same behavior as one of the two OpenAlex defects repair 1 claimed to fix — the "dead `abstractTextRef` that could never fire." The remaining 13 `SourceNormalizerSuite` cases passed, including three other OpenAlex cases, so the rejection appears specific to this fixture rather than general to OpenAlex normalization.

### What run 2 establishes

- **Established:** formatting across all sources; full compilation of main and test sources in both modules; 114 passing cases, including all of `XmlRecordStreamSuite`, `DblpParserSuite`, `TeamExpansionSuite`, `IdentifierAndKeySuite`, `BlockingAndCandidateSuite`, `LocalPipelineSuite` and `ContractSuite`.
- **Not established:** the abstract-reference behavior at `SourceNormalizerSuite.scala:245`, and the gate as a whole, which requires a passing check.

Figures that now have executed backing from `LocalPipelineSuite` rather than derivation: six works across three work-bearing sources, no oversize team or block at default bounds, every blocking group non-trivial and within bound, declared counter inventory present, every key in range under the declared partition count, repeated runs identical, output paths a function of run and snapshot only. These still need the owner's narrative report to be usable as the specification's required reporting.

## Repair dispatch 2

Routed to `hadoop-engineer` 2026-11-06, after run 2.

Scope: diagnose why normalization rejects the fixture at `SourceNormalizerSuite.scala:245` and fix the correct side, whether that is the normalizer or the fixture; confirm the abstract-reference requirement is genuinely verified once the test reaches its subject; deliver the still-outstanding "Required reporting" items.

Constraint carried forward: the agent does not run `sbt`. The human runs the next check.

## Run 3 — PASSED

Executed by the human operator with the `!` prefix, 2026-11-06 02:23. Not executed by any agent. Same command as runs 1 and 2.

Result: **passed.** Formatting, compilation of main and test sources in both modules, and all tests. 118 tests total, 118 passed, 0 failed, 0 errors. `[success] Total time: 11 s, completed Nov 6, 2026, 2:23:12 AM`.

`.claude/.last-test-run` was updated by this run and is current as of run 3. It is a zero-byte marker; its modification time carries the state.

### Resolution of the run-2 failure

`SourceNormalizerSuite` now reports 17 passing cases, up from 14. The previously failing case is gone and three cases stand in its place:

- `no Crossref abstract text is copied, only a reference`
- `an OpenAlex inverted abstract yields a reference and no text`
- `a DOI prefix shorter than four digits is not a DOI`

The owner reported fixing a malformed Crossref DOI fixture rather than the normalizer, splitting the abstract assertion across both sources, and adding a DOI-prefix case. The case count and names are consistent with that account. Whether the assertions were strengthened or weakened is a review question, routed to `code-reviewer` in [reviewer-work-order.md](reviewer-work-order.md); passing tests alone do not settle it.

The owner's predicted failure point — a mis-shaped hand-built OpenAlex `abstract_inverted_index` fixture — did not materialize.

### Measured figures

Emitted by the `report the run's measured figures` case in `LocalPipelineSuite` as a `[phase-4 measured]` diagnostic line. These are **executed observations** on the bounded local samples, not derivations. They are the first delivery of the specification's required reporting figures.

| Figure | Value |
|---|---|
| Works | 6 |
| Maximum author team size | 5 |
| Maximum author team work ID | `work:v1:dblp:Y29uZi9qY2RsL01pY2hlbHNGTFNTMTc` |
| Largest pair expansion | 10 |
| Emitted pairs | 30 |
| Blocking groups | 20 |
| Largest blocking group | 6 |
| Largest blocking group key | `researcher\|name-coauthor\|fayzrakhmanov\|rr\|ley\|m` |
| Total rejects | 1 |
| Partition count | 4 |

Reported blocking group sizes: `Vector(6, 6, 6, 6, 6, 6, 6, 6, 6, 6)`.

Partition spread by artifact, as emitted:

```
blocking/blocking-groups->Vector((0,5), (1,4), (2,7), (3,4))
blocking/blocking-members->Vector((0,19), (1,21), (2,29), (3,18))
candidate-features/match-candidates->Vector((0,33), (1,39), (2,47), (3,25))
dblp-extraction/venues->Vector((2,1), (3,1))
dblp-extraction/works->Vector((0,1), (2,1))
graph-export/author-mentions->Vector((0,2), (1,3), (2,6), (3,7))
graph-export/institutions->Vector((0,1), (3,1))
graph-export/researchers->Vector((1,1), (2,2), (3,1))
graph-export/topics->Vector((0,1), (1,2), (2,2))
graph-export/venues->Vector((0,2), (2,1), (3,1))
graph-export/works->Vector((0,3), (2,2), (3,1))
identifier-join/identifier-keys->Vector((0,4), (1,5), (2,4), (3,2))
source-normalization/crossref/venues->Vector((0,2))
source-normalization/crossref/works->Vector((0,1), (3,1))
source-normalization/openalex/institutions->Vector((0,2), (3,1))
source-normalization/openalex/researchers->Vector((1,2), (2,2), (3,1))
source-normalization/openalex/topics->Vector((0,2), (1,2), (2,2))
source-normalization/openalex/works->Vector((0,1), (2,1))
source-normalization/orcid/researchers->Vector((1,1), (3,1))
team-expansion/author-papers->Vector((0,2), (1,3), (2,6), (3,7))
team-expansion/pair-candidates->Vector((0,8), (1,6), (2,8), (3,8))
team-expansion/teams->Vector((0,3), (2,2), (3,1))
```

Internal consistency checks the orchestrator performed on these figures:

- `blocking/blocking-groups` sums to 5 + 4 + 7 + 4 = 20, matching `blockingGroups=20`.
- `team-expansion/pair-candidates` sums to 8 + 6 + 8 + 8 = 30, matching `emittedPairs=30`.
- A maximum team of 5 yields 5 × 4 / 2 = 10, matching `largestPairExpansion=10`.

Two items for review rather than acceptance:

1. `blockingGroups=20` but `blockingGroupSizes` lists only 10 entries, every one exactly 6. The partition spread confirms 20 groups exist, so the vector appears to be a truncated top-N rather than the full distribution. The specification asks for the largest observed blocking group, which is reported; the truncation should be labeled rather than left implicit.
2. `totalRejects=1` in a run where `the DBLP stage accepts both pinned records and rejects none` passes. The single reject therefore originates outside DBLP extraction. Confirm it is an intended rejection of a known fixture and not silent data loss.

### What run 3 establishes and does not

- **Established:** formatting, full compilation, and all 118 tests across 7 hadoop suites and 2 core suites. The measured figures above.
- **Not established by any run:** that a MapReduce job has executed on a Hadoop runtime. Every passing suite is in-process. See the gate table.

## Run 4 — FAILED

Executed by the human operator with the `!` prefix, 2026-11-06 09:51. Not executed by any agent. Same command as runs 1 through 3.

Result: **formatting and compilation passed; one suite failed at runtime.** 132 tests total, 124 passed, 1 failed, 0 errors, 7 skipped. `.claude/.last-test-run` was not updated and is stale.

The single failure is `JobWiringSuite`, the new suite written specifically to answer the `Provided` classpath question. Every pre-existing suite passed. Core passed 31 of 31.

### The `Provided` classpath question is answered, and the answer is split

This was the one deliberately open result of repair 3. Neither predicted outcome is what happened, and the distinction matters for routing.

**Compile-time resolution works.** `Test/compile` succeeded on 9 hadoop test sources, including `JobWiringSuite`, which references `org.apache.hadoop.conf.Configuration` and `StableKeyPartitioner`. This confirms the reviewer's correction in [review.md](review.md): `Provided` **is** on the `Test` compile classpath, and the owner's original framing — that the absent runtime precluded all job-class tests — was too broad.

**Runtime instantiation fails.** The first case died constructing a `Configuration`:

```
==> X scholarchain.hadoop.JobWiringSuite.a run context survives a job configuration round-trip  0.008s
    java.lang.NoClassDefFoundError: org/apache/hadoop/shaded/com/ctc/wstx/io/InputBootstrapper
    at scholarchain.hadoop.JobWiringSuite.emptyConfiguration(JobWiringSuite.scala:37)
    at scholarchain.hadoop.JobWiringSuite.$init$$$anonfun$1(JobWiringSuite.scala:42)
Caused by: java.lang.ClassNotFoundException: org.apache.hadoop.shaded.com.ctc.wstx.io.InputBootstrapper
```

The suite aborted after that failure, skipping its remaining 7 cases:

```
==> s the round-trip preserves a non-default bound skipped
==> s snapshots round-trip as a sorted dataset list skipped
==> s an absent or malformed snapshot list yields no snapshots skipped
==> s a configuration missing the run identity is refused, not defaulted skipped
==> s the partitioner routes on the logical key, ignoring the stream label skipped
==> s partition indices stay in range and are stable across calls skipped
==> s an unlabeled key partitions as itself skipped
```

```
[error] Failed: Total 132, Failed 1, Errors 0, Passed 124, Skipped 7
[error] Failed tests:
[error] 	scholarchain.hadoop.JobWiringSuite
[error] (hadoop / Test / test) sbt.TestsFailedException: Tests unsuccessful
```

### Reading of the failure

`Configuration` parses XML on construction and reaches for a shaded Woodstox parser. The missing class is in the `org.apache.hadoop.shaded.*` namespace, which is the content of **`hadoop-client-runtime`**, not `hadoop-client-api`. The build declares only `hadoop-client-api % Provided` (`build.sbt:112`). So the api-only jar is sufficient to **compile** against the Hadoop API but not to **instantiate** a `Configuration`.

This is a missing `Test`-scope dependency, not a defect in the suite and not a `hadoop-engineer` error. It routes to `build-engineer`.

### Diagnosis: missing dependency, not class-loader layering — confirmed by artifact evidence

sbt's error output recommends changing `ClassLoaderLayeringStrategy`. **That advice is a red herring here, and the orchestrator confirmed it from the resolved artifacts rather than by inference.** sbt prints that hint generically on any test-run `NoClassDefFoundError`. Layering can only matter when the class is present on the test classpath but loaded in the wrong layer. Here the class is not on the classpath at all.

Evidence, read-only:

| Check | Finding |
|---|---|
| Declared hadoop dependency | `build.sbt:112` — `"org.apache.hadoop" % "hadoop-client-api" % DependencyVersions.hadoop % Provided`. This is the **only** hadoop entry in the module |
| Pinned version | `project/DependencyVersions.scala:9` — `val hadoop = "3.5.0"` |
| Coursier cache, `hadoop-client-api` | `3.4.1` and `3.5.0` present, each with jar and pom |
| Coursier cache, `hadoop-client-runtime` | **`3.4.1` only.** No `3.5.0` directory exists |

`hadoop-client-runtime` 3.5.0 was never resolved, which also shows `hadoop-client-api` 3.5.0 does not pull it transitively — otherwise Coursier would have fetched it. The stale 3.4.1 runtime jar in the cache is from unrelated earlier resolution and is not on this build's classpath, since the build pins 3.5.0.

The missing class `org/apache/hadoop/shaded/com/ctc/wstx/io/InputBootstrapper` is shaded Woodstox, which ships in `hadoop-client-runtime`. `Configuration` parses XML on construction and needs it. So the api-only jar compiles against the Hadoop API but cannot instantiate a `Configuration`.

**Conclusion: a missing `Test`-scope runtime dependency.** No class-loader layering change is warranted, and none should be attempted — setting `classLoaderLayeringStrategy` would not put an absent class on the classpath.

### Executed evidence for the review findings

Four of the five findings now have executed backing. F4 changed comments only and has nothing to assert.

| Finding | Executed evidence in run 4 |
|---|---|
| F1 tautological block-key test | `BlockingAndCandidateSuite` gains `the coauthor key is unchanged when the author order is reversed` and `a mention derives one coauthor key per distinct coauthor`, both passing. The original case name is retained and also passes |
| F2 unlabeled truncation | Measured line now reads `blockingGroupSizesTop10of20=[6,6,6,6,6,6,6,6,6,6]`. Truncation labeled, count disclosed |
| F3 duplicate-inflated `observedSize` | `BlockingAndCandidateSuite` gains `a duplicate member is retained once but counted as a record received`, `the reducer path and the whole-block path agree exactly`, and `truncation is reported only when a member was actually dropped`, all passing |
| F4 false overwrite-on-rerun claim | Comment-only across three sites, including the third the owner found at `jobs/PipelineOptions.scala:12`. Verify by reading at re-review |
| F5 `artifactPath` missing `dataset=` | `LocalPipelineSuite` gains `a dataset-scoped artifact cannot be addressed without its dataset` and `a dataset-scoped path matches what the sink actually commits`, both passing |

`BlockingAndCandidateSuite` moves from 18 to 22 cases and `LocalPipelineSuite` from 26 to 28, consistent with the additions above.

### Measured figures, run 4

Identical to run 3 on every figure, with F2's labeling applied: `works=6`, `maximumAuthorTeamSize=5`, `largestPairExpansion=10`, `emittedPairs=30`, `blockingGroups=20`, `largestBlockingGroup=6`, `largestBlockingGroupKey=researcher|name-coauthor|fayzrakhmanov|rr|ley|m`, `blockingGroupSizesTop10of20=[6,6,6,6,6,6,6,6,6,6]`, `totalRejects=1`, `partitionCount=4`. The partition spread is unchanged from run 3.

Stability across runs 3 and 4 is itself evidence for the determinism gate item: the F3 repair altered how `observedSize` is computed without moving any reported figure, which is consistent with the reviewer's finding that the sample contains no duplicate members.

### What run 4 establishes

- **Established:** formatting; full compilation of main and test sources in both modules; 124 passing cases; executed evidence for F1, F2, F3 and F5; and that `Provided` reaches the `Test` compile classpath.
- **Established as missing:** a Hadoop runtime dependency at `Test` scope. Job-class tests are now demonstrably writable and compilable, but not runnable.
- **Still not established:** that any MapReduce job has executed. Run 4 moves this from "untested by assumption" to "tested, blocked on one dependency," which is a narrower and better-evidenced position than before.

## Repair routing after run 4

No `hadoop-engineer` repair is indicated. The suite is correct in intent, it compiled, and the owner documented both outcomes in its header with an instruction to report rather than delete it. The failing dependency is outside the owner's authority.

Routed item: **`build-engineer`**, to add `"org.apache.hadoop" % "hadoop-client-runtime" % DependencyVersions.hadoop % Test` to the hadoop module in `build.sbt`, so `Configuration` can be instantiated under test.

**`dependency-updater` is not required for this fix.** The addition references the existing approved ledger key `DependencyVersions.hadoop`, already pinned at `3.5.0`. Per the boundary rules in `docs/agents.md`, declaring a dependency and referencing a ledger key is build structure and belongs to `build-engineer`; only changing a **version value** in `project/DependencyVersions.scala` belongs to `dependency-updater`. No version value changes here.

One contingency: if `hadoop-client-runtime` is not published at `3.5.0`, the fix would require a version change, and that routes to `dependency-updater`. The orchestrator could not verify remote availability — the sandbox permits no network fetch, and the local cache holds only `3.4.1` of that artifact. `build-engineer` will discover this on resolution and should report `BLOCKED-ON` naming `dependency-updater` if it occurs, rather than editing the ledger itself.

Second routed item: **`hadoop-engineer`**, to state why 7 `JobWiringSuite` cases are skipped and whether any skip conceals a gate item. The skips are consistent with MUnit abandoning a suite after a failure in its first case, but that is the orchestrator's reading and the owner should confirm it from the suite's own structure.

### Both routed items delivered, 2026-11-06

**`build-engineer` — dependency fix applied and verified by the orchestrator.** `build.sbt:112-116`:

```scala
libraryDependencies += "org.apache.hadoop" % "hadoop-client-api" % DependencyVersions.hadoop % Provided,
// A cluster supplies the shaded runtime, so production keeps it off the
// classpath. Tests run outside a cluster and need it: Configuration parses
// XML on construction and reaches for the shaded Woodstox parser.
libraryDependencies += "org.apache.hadoop" % "hadoop-client-runtime" % DependencyVersions.hadoop % Test
```

Orchestrator checks: `git diff --stat` reports `build.sbt | 6 +++++-`, one file, five insertions and one deletion — nothing else touched. `git diff -- project/DependencyVersions.scala` is empty, so the approved version ledger is **unchanged** and the addition references the existing `DependencyVersions.hadoop` key. `hadoop-client-api` remains at `Provided`. The scoping rationale is recorded in the build file itself. The boundary held: no ledger edit, no `modules/**` edit, no `ClassLoaderLayeringStrategy` change.

The owner reports status COMPLETE with an unverified validation gate, predicting 132 passed and 0 skipped on run 5, and states plainly that this is a prediction with no resolution or test evidence behind it. It could not confirm that `hadoop-client-runtime` resolves at 3.5.0 without running sbt, so the `dependency-updater` contingency recorded above remains untested until run 5.

**`hadoop-engineer` — skip analysis delivered, and it found a real gap.** Full record in [repairs.md](repairs.md), repair 4. Summary:

- The 7 skips were collateral of a `LinkageError` aborting the suite, **not** a skip mechanism; the file contains none. This confirms the orchestrator's reading. Five of the seven needed a `Configuration`; three never did.
- **One skip did conceal a required operational behavior.** The partition strategy *as implemented in the class Hadoop actually calls* rested solely on skipped cases: `IdentifierAndKeySuite` covers `PartitionKey` in-process, but `StableKeyPartitioner`, the `Partitioner` subclass Hadoop invokes, had no executable coverage. The three partitioner cases never needed a `Configuration` and were skipped only for sharing a suite with five cases that did.
- Remedy: those three cases moved verbatim into a new `PartitionerWiringSuite`. Case total unchanged at 132; suites move from 8 to 9; `JobWiringSuite` drops from 8 cases to 5.
- **Tautology sweep, finally answered:** one true tautology across all suites, which was F1 and is fixed; five legitimate determinism checks; five sound swapped-input checks. F1 was isolated, not a pattern.

### Material qualification on the MapReduce gate item

The owner's qualification is recorded as the orchestrator's live reading of the gate. `hadoop-client-runtime % Test` should let `JobWiringSuite`'s five remaining cases run, but it does **not** add `hadoop-client-minicluster`. Instantiating a `Configuration` and exercising a `Partitioner` is not executing a MapReduce job.

**"At least two real MapReduce jobs execute" therefore remains OPEN after this fix.** Closing it requires a mini-cluster or equivalent runtime, which is a separate `build-engineer` item and is not yet dispatched. A green run 5 must not be read as closing this item.

## Run 5 — PASSED

Executed by the human operator with the `!` prefix, 2026-11-06 09:59. Not executed by any agent. Same command as runs 1 through 4.

Result: **passed.** Formatting, compilation of main and test sources in both modules, and all tests. 132 tests total, **132 passed, 0 failed, 0 errors, 0 skipped**. `[success] Total time: 10 s, completed Nov 6, 2026, 9:59:17 AM`.

`.claude/.last-test-run` was updated by this run and is current as of run 5.

### The dependency fix worked, and both previously-blocked suites now execute

`build-engineer`'s prediction of 132 passed and 0 skipped was correct.

- **`JobWiringSuite`, 5 of 5 passing** — `a run context survives a job configuration round-trip`, `the round-trip preserves a non-default bound`, `snapshots round-trip as a sorted dataset list`, `an absent or malformed snapshot list yields no snapshots`, `a configuration missing the run identity is refused, not defaulted`. The `NoClassDefFoundError` is gone: `hadoop-client-runtime % Test` supplies the shaded Woodstox parser that `Configuration` needs on construction.
- **`PartitionerWiringSuite`, 3 of 3 passing** — `the partitioner routes on the logical key, ignoring the stream label`, `partition indices stay in range and are stable across calls`, `an unlabeled key partitions as itself`.
- **Zero skips.** The 7 skips of run 4 were confirmed collateral, exactly as the owner reported: five needed the `Configuration` the dependency now supplies, three never did and were freed by moving them to their own suite.

### First executed coverage of `jobs/**`

This is the material change from run 5, and it bears directly on the review's central observation. At review time the reviewer found **zero** test references to `jobs/**` — 14 files compiled and never executed. Run 5 is the first run in which any of that package executes:

- `JobConfig.write`/`read` round-trips, including rejection of a configuration missing the run identity rather than defaulting it.
- `StableKeyPartitioner.getPartition` — the `Partitioner` subclass Hadoop actually calls.

The second item closes a required operational behavior that `repairs.md` repair 4 identified as resting solely on skipped cases. The specification requires an explicit partition strategy; `IdentifierAndKeySuite` covered `PartitionKey` in-process, but the class Hadoop invokes had no executable coverage until now.

### Measured figures, run 5

Byte-identical to run 4 on every figure: `works=6`, `maximumAuthorTeamSize=5`, `maximumAuthorTeamWorkId=work:v1:dblp:Y29uZi9qY2RsL01pY2hlbHNGTFNTMTc`, `largestPairExpansion=10`, `emittedPairs=30`, `blockingGroups=20`, `largestBlockingGroup=6`, `largestBlockingGroupKey=researcher|name-coauthor|fayzrakhmanov|rr|ley|m`, `blockingGroupSizesTop10of20=[6,6,6,6,6,6,6,6,6,6]`, `totalRejects=1`, `partitionCount=4`, with an unchanged partition spread.

Three consecutive runs (3, 4, 5) now produce identical measured output across two repairs and a build change. That is stronger evidence for the determinism gate item than any single run.

### New diagnostic to note

Run 5 emits an SLF4J warning not present in earlier runs:

```
SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
SLF4J: Defaulting to no-operation (NOP) logger implementation
```

This is a consequence of adding `hadoop-client-runtime % Test`, which brings SLF4J's API onto the test classpath without a binding. It is not a failure and nothing depends on it, but it means Hadoop log output is discarded under test. Logging configuration is `build-engineer` territory per `CLAUDE.md`. Recorded as an observation for the re-review to judge, not as a defect.

### Review findings: executed status after run 5

| Finding | Status |
|---|---|
| F1 tautological block-key test | Replaced; two new cases passing. The tautology sweep found this was the only true instance across all suites |
| F2 unlabeled truncation | Fixed and visible: `blockingGroupSizesTop10of20` |
| F3 duplicate-inflated `observedSize` | Three new cases passing, including explicit reducer-path/whole-block-path agreement |
| F4 false overwrite-on-rerun claim | Comment-only, three sites including one the owner found itself. **Verifiable only by reading** — no executed evidence exists or can exist |
| F5 `artifactPath` missing `dataset=` | Two new cases passing, including one asserting the path matches what the sink actually commits |
| F6 reporting deficiency | Figures now delivered in this file across runs 3 to 5. Job graph, per-job key-value contracts, per-stage counter values and the full path set remain unreported in prose |

## Build change before run 6

Author: `build-engineer`, 2026-11-06, dispatched by the orchestrator to unblock the two remaining gate items. Verified by the orchestrator from `git diff`. Unverified by any test run: no `sbt` run exists over these edits.

`git diff --stat` reports `build.sbt | 45 +++++++++++++++++++++++++++++++++++++++++++--`, one file, 43 insertions and 2 deletions. **Nothing else changed.** `project/DependencyVersions.scala` is untouched.

### Item 1 — Hadoop job-execution runtime, the hard gate item

`hadoop-client-minicluster % Test` added to the hadoop module, at the pinned `DependencyVersions.hadoop`. The rationale is recorded in the build file: the runtime jar answers `Configuration`'s construction only, while executing a job needs `LocalJobRunner` plumbing, job submission, split assignment, a task JVM, a shuffle and an `OutputCommitter`. `Test` scope, so it reaches no packaged job artifact. `hadoop-client-api` remains `Provided`.

### Item 2 — Phase 2 finding F1, closed

The three reserved independent-test roots are now on the root `Test` source path:

```scala
Test / unmanagedSourceDirectories ++= Seq(
  baseDirectory.value / "tests" / "adversarial",
  baseDirectory.value / "tests" / "integration",
  baseDirectory.value / "tests" / "end-to-end"
)
```

This closes the accepted latent finding from Phase 2 (`revalidation-review.md` F1, `build.sbt:52-53`), under which a `.scala` test placed in those directories would have been neither compiled nor run while `sbt check` still passed and wrote the marker. It had to close before `test-engineer` writes the adversarial cases, or the gate item would have been satisfied by tests that never ran.

### Item 3 — Phase 2 finding F2, closed within this phase's need

`root.dependsOn(core % "test->compile", hadoop % "test->compile")`. The Phase 2 reviewer's binding constraint was that any widening must keep batch job modules out of the runtime graph. The edge is `test->compile` in both cases, so `hadoop` appears in no `Compile`, `Runtime` or packaged classpath; the rationale is recorded in the build file. Widened only as far as Phase 4 requires — `storage`, `rag`, `services`, `entityResolution` and `sparkAnalytics` were not speculatively added.

A subtlety the owner caught and the orchestrator records as correct: **sbt does not carry a `Provided` dependency across a project edge.** A test under `tests/` reaching the hadoop module's Hadoop-typed code therefore needs the API declared locally, so the root project also gains `hadoop-client-api % Test` and `hadoop-client-runtime % Test`. Without this, F2's widening would have compiled the test sources but failed to resolve the Hadoop types they reference.

### Item 4 — SLF4J binding, and no `dependency-updater` dispatch required

`slf4j-api` and `slf4j-simple` at `Test`, both reading `DependencyVersions.slf4j`, applied to the hadoop module and the root project through a shared `slf4jTestBinding` sequence.

**The ledger already carries `val slf4j = "2.0.18"` at `project/DependencyVersions.scala:8`.** The orchestrator's expectation that this item would need a new version value, and the re-review's note recommending a `dependency-updater` route for it, were both wrong: the approved value exists, so this stayed inside `build-engineer`'s authority and no ledger edit occurred. The boundary held without needing the contingency.

The owner records a deliberate consequence: `hadoop-client-runtime` brings `slf4j-api` 1.7.36 with no binding, and reading the ledger value resolves the api to 2.0.18 and **evicts** Hadoop's 1.7.36. Its stated justification is that 2.0.x is a drop-in replacement for a caller of the 1.7 api, which is all Hadoop is here. This should also explain the run-5 diagnostic: the `org.slf4j.impl.StaticLoggerBinder` lookup that failed is the 1.7 binding mechanism, which 2.0 replaces with a `ServiceLoader` provider that `slf4j-simple` supplies.

### Risks the owner flagged for run 6

Named as the three things most likely to turn the run red, each a one-line reversal:

1. The `slf4j-api` 1.7.36 → 2.0.18 eviction.
2. First-time resolution of `hadoop-client-minicluster` 3.5.0.
3. `scalafmtSbtCheck` acceptance of the new lines.

The orchestrator adds a fourth watch item: `hadoop-client-minicluster` is a large aggregate artifact and may carry its own logging bindings, so a multiple-binding warning or conflict with `slf4j-simple` is possible. Not predicted, only watched.

Everything in this section is prediction and inspection. The last executed evidence remains run 5, which predates these edits and does not cover them. `.claude/.last-test-run` is stale.

## Run 6 — PASSED

Executed by the human operator with the `!` prefix, 2026-11-06 10:27. Not executed by any agent. Same command as runs 1 through 5.

Result: **passed.** 132 tests, **132 passed, 0 failed, 0 errors, 0 skipped**. `[success] Total time: 11 s, completed Nov 6, 2026, 10:27:13 AM`. `.claude/.last-test-run` is current as of run 6.

### None of the four predicted risks materialized

| Predicted risk | Outcome |
|---|---|
| `slf4j-api` 1.7.36 → 2.0.18 eviction | **Resolved cleanly, and it worked.** The `SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder"` warning present in run 5 is **absent** from run 6. The 1.7 binding lookup is gone and `slf4j-simple` 2.0.18 supplies the `ServiceLoader` provider. Hadoop diagnostics will now reach the test output |
| First-time `hadoop-client-minicluster` 3.5.0 resolution | Resolved. No resolution error; total time 11 s, unchanged from run 5 |
| `scalafmtSbtCheck` on the new `build.sbt` lines | Accepted |
| Orchestrator's watch item: minicluster bringing conflicting logging bindings | No multiple-binding warning appeared |

The `dependency-updater` contingency never triggered on any item: both the hadoop and slf4j version values already existed in the approved ledger.

### What run 6 does and does not establish

**Established:** the build change is sound. The minicluster artifact resolves, the SLF4J binding is active, formatting passes, and all 132 pre-existing tests still pass — so the widened root test classpath, the new source directories and the three new `Test` dependencies broke nothing.

**Not established, and important to state plainly:**

- **Test count is unchanged at 132.** The build change added *capability*, not coverage. Nothing yet uses the minicluster, so **the MapReduce gate item is exactly as open as it was after run 5.** Run 6 is a prerequisite being met, not a gate item closing.
- **The Phase 2 F1 and F2 fixes are structurally in place but unexercised.** `tests/adversarial`, `tests/integration` and `tests/end-to-end` are now on the root `Test` source path, but they still hold only `.gitkeep`, so no file was compiled from them in this run. Likewise the `hadoop % "test->compile"` edge and the root-level Hadoop `Test` dependencies compiled no test that uses them. Both fixes are proven only when `test-engineer` places a real `.scala` case under `tests/adversarial` that references the hadoop module and it is observed to compile and run. Until then the wiring is inspected, not demonstrated.

### Measured figures, run 6

Identical to runs 3, 4 and 5 on every figure. Four consecutive runs now produce byte-identical measured output across three repairs and two build changes.

## Run 7 — FAILED, with the MapReduce gate item substantially evidenced

Executed by the human operator with the `!` prefix, 2026-11-06 12:10. Not executed by any agent. Three attempts were needed to read the result; see the logging note below.

Result: **145 tests, 144 passed, 1 failed, 0 errors, 0 skipped.** `.claude/.last-test-run` was not updated and is stale.

```
[error] Failed: Total 145, Failed 1, Errors 0, Passed 144
[error] Failed tests:
[error] 	scholarchain.hadoop.MapReduceEndToEndSuite
[error] (hadoop / Test / test) sbt.TestsFailedException: Tests unsuccessful
[error] Total time: 14 s, completed Nov 6, 2026, 12:10:54 PM
```

### Real MapReduce execution is now demonstrated

`MapReduceEndToEndSuite` executed real Hadoop jobs through `LocalJobRunner`, with genuine submission, split calculation, map tasks, spills, shuffle and `FileOutputCommitter` v2. Observed in the run output: distinct job IDs across attempts (`job_local906117318_0001`, `job_local2127452075_0001`), **4 splits** from the custom `DblpXmlInputFormat`, and map tasks processing `0+320` and `320+320` of the bounded fixture.

Twelve of the thirteen end-to-end cases passed:

```
+ job 1 executes as a real MapReduce job and commits its output        1.523s
+ Hadoop's split calculation divides the input, records still read once 0.012s
+ a reduce task ran and received grouped keys                           0.002s
+ job 1's counters, read off the completed job, match the input         0.004s
+ the reduce side collapses a duplicated DBLP key                       0.003s
+ committed works carry provenance and their full author sequence       0.001s
+ a real committing task writes the reject to its versioned path        0.001s
+ job 2 shuffles three keyed streams into three committed artifacts     0.002s
+ the pair counters are not double-counted across map and reduce        0.008s
+ rerunning a committed stage fails rather than overwriting it          0.007s
+ a new run ID writes a separate prefix over the same input             1.107s
+ the two runs agree on everything except the run identity              0.004s
```

Counters read from a completed job, as emitted in the run output:

```
org.apache.hadoop.mapreduce.lib.output.MultipleOutputs
    rejects/v1/MISSING_REQUIRED_FIELD/part=1
    venues/v1/part=2
    works/v1/part=2
scholarchain.input      InputRecords=4
scholarchain.outcome    AcceptedRecords=3  DuplicateRecords=2  RejectedRecords=1
scholarchain.output     EmittedRecords=6
```

### Four findings confirmed empirically by this run

1. **F4's diagnosis is confirmed.** `rerunning a committed stage fails rather than overwriting it` passes. Hadoop does reject an existing output prefix rather than overwrite it, exactly as the re-review read from the contract and contrary to the original comments. The behavior is now pinned by a test rather than asserted in prose.
2. **Repair 5's defect 1 is fixed and verified.** `the pair counters are not double-counted across map and reduce` passes.
3. **Repair 5's defect 2 is fixed and verified.** `a real committing task writes the reject to its versioned path` passes, and the `MultipleOutputs` counter shows `rejects/v1/MISSING_REQUIRED_FIELD/part=1` — a map-detected reject surviving serialization and shuffle to a committed versioned output. This gate item was previously **false at runtime** while passing review on inspection.
4. **`DeduplicateReducer` has executed coverage for the first time.** `the reduce side collapses a duplicated DBLP key` passes, and `DuplicateRecords=2` resolves one of the owner's ranked risks. The re-review recorded that this reducer had no coverage of any kind.

Of the owner's five ranked risks, four resolved favorably: compilation, `splits.size > 1`, `DuplicateRecords == 2`, and the `FileAlreadyExistsException` match. `REDUCE_INPUT_GROUPS` is covered by the passing job-1 reduce case.

### The one failure

```
==> X scholarchain.hadoop.MapReduceEndToEndSuite.job 2 executes and consumes job 1's committed output  1.127s
    munit.FailException: modules/hadoop/src/test/scala/scholarchain/hadoop/MapReduceEndToEndSuite.scala:329 no reduce task ran: 0
328:      .getValue
329:    assert(launchedReduces >= 1, s"no reduce task ran: $launchedReduces")
```

Job 2 ran for 1.127s and its sibling case `job 2 shuffles three keyed streams into three committed artifacts` **passed**, so the job executed and committed output. What failed is a counter read: `launchedReduces` came back 0.

The orchestrator does not have enough evidence to choose among three readings, and the distinction decides whether this is a test defect or a gate-relevant one:

1. **A test defect.** The case reads a `JobCounter` that `LocalJobRunner` does not populate. `LocalJobRunner` is known not to maintain every framework counter. Note that job 1's reduce assertion **passed** while reading a different counter — the owner's risk list named `REDUCE_INPUT_GROUPS` for job 1, whereas this case reads a launched-task counter. If so, the fix is to assert on a counter the runtime actually maintains, not to relax the assertion.
2. **Job 2 is genuinely configured map-only.** If its reduce-task count is 0, then the committed artifacts are produced map-side through `MultipleOutputs`. A map-only job is still a MapReduce job, but it weakens the specification's intent, and the gate item would then rest on job 1 alone for a real reduce phase.
3. **A real wiring defect** in which job 2's reducer is silently skipped.

Reading 1 is the most likely and reading 3 the most serious. The owner must distinguish them on evidence rather than pick the convenient one.

### Targeted reproduction, 2026-11-06 12:12

The human operator re-ran the failing suite alone:

```
sbt "hadoop/testOnly scholarchain.hadoop.MapReduceEndToEndSuite" 2>&1 | grep -A 20 "==> X"
```

Result: 13 tests, 12 passed, 1 failed, in 4 seconds. The same case fails at the same line with the same value, and the timing is essentially identical (1.126s against 1.127s in run 7). **The failure is deterministic and isolated to that one case**, not a flake or an ordering effect, and it does not depend on the rest of the suite running first.

This is a diagnostic re-run, not a gate: `testOnly` skips formatting and every other suite, and does not update `.claude/.last-test-run`. Only a full `sbt clean check` closes the test gate. The command is recorded because it gives a four-second verification loop for this repair instead of the fourteen-second full check, and because `grep -A 20 "==> X"` extracts the failure without the `LocalJobRunner` INFO flood.

### Logging regression, introduced by the run-6 SLF4J binding

Two earlier attempts at run 7 could not be read: `slf4j-simple` defaults to INFO, and `LocalJobRunner` emits roughly twenty lines of buffer internals per map task, which flooded the output and truncated it mid-third-map-task, before the test summary. The result was obtained only by piping through `tail`.

This is a side effect of the fix added before run 6. The binding is wanted — it is what makes task failure stacks and `FileAlreadyExistsException` visible — but the default level must be `WARN`, which keeps warnings and errors while cutting the INFO flood. Routed to `hadoop-engineer` as a `simplelogger.properties` under `modules/hadoop/src/test/resources`, which keeps the setting with the module that needs it rather than widening build configuration.

## Run 8 — PASSED. The MapReduce gate item closes

Executed by the human operator with the `!` prefix, 2026-11-06 12:19. Not executed by any agent.

Result: **passed.** 146 tests, **146 passed, 0 failed, 0 errors, 0 skipped.** `[success] Total time: 14 s, completed Nov 6, 2026, 12:19:45 PM`. `.claude/.last-test-run` is current as of run 8.

`MapReduceEndToEndSuite`, all 14 cases:

```
+ job 1 executes as a real MapReduce job and commits its output         1.503s
+ Hadoop's split calculation divides the input, records still read once 0.012s
+ a reduce task ran and received grouped keys                           0.002s
+ job 1's counters, read off the completed job, match the input         0.005s
+ the reduce side collapses a duplicated DBLP key                       0.002s
+ committed works carry provenance and their full author sequence       0.001s
+ a real committing task writes the reject to its versioned path        0.001s
+ job 2 executes and consumes job 1's committed output                  1.119s
+ LocalJobRunner does not maintain the launched-task counters           0.004s
+ job 2 shuffles three keyed streams into three committed artifacts     0.002s
+ the pair counters are not double-counted across map and reduce        0.009s
+ rerunning a committed stage fails rather than overwriting it          0.007s
+ a new run ID writes a separate prefix over the same input             1.107s
+ the two runs agree on everything except the run identity              0.004s
```

### The reduce-counter diagnosis is now executed evidence, not reasoning

`LocalJobRunner does not maintain the launched-task counters` passes. Repair 6's conclusion — that `TOTAL_LAUNCHED_REDUCES` is never incremented on the `LocalJobRunner` path while the reduce phase genuinely runs — was reasoned from code when reported. It is now observed. The case asserts `TOTAL_LAUNCHED_REDUCES == 0` alongside `REDUCE_INPUT_RECORDS > 0`, so the quirk is pinned and the reduce phase is independently proven by an instrument that can fail.

The owner's hand-derived values held: no mismatch between `MAP_OUTPUT_RECORDS` and `REDUCE_INPUT_RECORDS` appeared, so the shuffle-loss scenario it flagged as "a real defect, not a test bug" did not occur.

### The logging fix worked

`simplelogger.properties` at `warn` reduced the output from an INFO flood that truncated two run-7 attempts to a handful of WARN lines. Run 8 was readable. The surviving warnings are benign and expected: `Hadoop command-line option parsing not performed` (the harness drives jobs directly rather than through `ToolRunner`), `No job jar file set` (expected in-process), and `JobTracker metrics system already initialized!` (repeated job submissions in one JVM). None indicates a defect.

### Gate item closed: "at least two real MapReduce jobs execute"

**CLOSED on executed evidence, with one limit stated.**

Evidence: two jobs executed through Hadoop's `LocalJobRunner` with genuine submission, split calculation producing 4 splits from the custom `DblpXmlInputFormat`, separate map task attempts over distinct byte ranges, real spills through `MapOutputBuffer` with deflate compression, a shuffle, reduce tasks consuming grouped keys, and `FileOutputCommitter` v2 committing output. Job 2 consumes job 1's committed output, so the two are genuinely chained rather than independently invoked. Counters were read back off completed jobs. The specification's wording — "not local collection operations disguised as MapReduce" — is satisfied: this is Hadoop's own runtime executing a real job graph.

**Limit to carry forward:** `LocalJobRunner` executes a single reduce task regardless of configuration, and job 2 configures 2. **Multi-reducer fan-out therefore remains unexercised.** `StableKeyPartitioner` is unit-tested in `PartitionerWiringSuite` and the partition map is asserted in `LocalPipelineSuite`, but no run has distributed one job's keys across more than one live reducer. `hadoop-client-minicluster` is on the test classpath and currently unused; whether to keep it for a future multi-reducer test is a question for the re-review.

## Run 9 — FAILED, and the Phase 2 build findings are proven closed

Executed by the human operator with the `!` prefix, 2026-11-06 13:20. Not executed by any agent.

Result: **failed.** Two tallies, because the suites live in two projects:

```
[error] Failed: Total 65, Failed 2, Errors 0, Passed 63        <- root project, tests/adversarial
[info]  Passed: Total 146, Failed 0, Errors 0, Passed 146      <- hadoop module
[error] Failed tests:
[error] 	scholarchain.tests.adversarial.AdversarialJobConfigSuite
[error] 	scholarchain.tests.adversarial.AdversarialCommonNameSuite
[error] (Test / test) sbt.TestsFailedException: Tests unsuccessful
[error] Total time: 14 s, completed Nov 6, 2026, 1:20:19 PM
```

211 cases executed in total, 209 passed. `.claude/.last-test-run` was not updated and is stale.

### Phase 2 findings F1 and F2: CLOSED on executed evidence

This is the significant outcome of run 9, independent of the two failures.

The adversarial suites **compiled and ran**, reported under the package `scholarchain.tests.adversarial`. Both Phase 2 latent findings are now demonstrated closed rather than merely inspected:

- **F1** (`build.sbt:52-53`) — `tests/adversarial` was on no source path, so a `.scala` test placed there would be neither compiled nor run while `sbt check` still passed and wrote the marker. The 65 cases executed, so the reserved directory is genuinely wired in. Had F1 still been open, the run would have reported 146 cases and the new suites would have been silently absent — which was the explicit failure signature the orchestrator was watching for.
- **F2** (`build.sbt:48`) — the root test classpath carried only `core`, so a test under `tests/` could not reference the hadoop module. Every one of the eight files imports `scholarchain.hadoop`, and they compiled, so the `hadoop % "test->compile"` edge works.

Both were accepted as latent in Phase 2 on the grounds that the directories held only `.gitkeep`. They are now exercised, and the acceptance can be retired.

**The gate-strength reduction accepted in Phase 2 is also retired.** `revalidation-repairs.md` recorded that "until the reserved directories are wired in, `sbt check` can pass and write the marker while a test placed there is never run." Run 9 shows a test placed there both running and, crucially, **failing the check** — so the gate now has real teeth over `tests/`.

### The two failures were not predicted

`test-engineer` predicted 211 of 211 passing and stated that "none of them should produce a red test, because each is pinned as the behavior currently observed." That prediction is **wrong**, and the two failures are not in its own triage list either.

Its stated triage anticipated: a compile failure or missing suite (would route to `build-engineer` — did not occur), or "a failure of the three byte-ratio inequalities in `AdversarialGiantTeamSuite`" (did not occur; the giant-team cases visible in the output all passed, including `blocking fans out quadratically on a giant team with no bound` and `doubling the team size quadruples the blocking record count`).

The actual failures are `AdversarialJobConfigSuite` and `AdversarialCommonNameSuite`, neither of which it flagged as a risk. Three readings, unresolved pending the failure detail:

1. A case pins an expectation that does not match actual behavior — a test defect in the pinning itself.
2. A genuine defect the agent misjudged as already-pinned-green.
3. Something environment-dependent in the common-name or job-config fixtures.

Reading 2 would be the most consequential, since the agent's whole account of the thirteen findings rests on the claim that each is pinned as observed. If two pins are wrong, the categorisation of the other eleven is less trustworthy.

### Failure detail, obtained 13:22

Targeted reproduction by the human operator: `testOnly` on both suites, 11 cases, 9 passed, 2 failed, 1 second.

```
==> X AdversarialJobConfigSuite.a dataset name containing an equals sign is silently renamed  0.007s
    tests/adversarial/AdversarialJobConfigSuite.scala:95 assertion failed
95:    assert(restored.snapshotId != run.snapshotId)

==> X AdversarialCommonNameSuite.the salt fan-out is a constant factor, not a bound  0.035s
    tests/adversarial/AdversarialCommonNameSuite.scala:112 researcher|name-initials|wang|ww|s7
```

**Both failures are in `tests/**`, and both are `test-engineer`'s own expectations being wrong rather than production defects** — reading 1 of the three the orchestrator recorded above. The root cause is that the agent pinned these from reading source without ever executing them, so each pin was a hypothesis presented as an observation.

Consequences recorded in [test-report.md](test-report.md):

- **F12 is partly wrong.** Its dataset-name half is not substantiated: the snapshot digest did not change. The version-comma half appears to hold. F12 is one of the five gate-bearing findings, so this matters to the record. The agent has been asked to find which field is *actually* renamed and pin that, or withdraw the half.
- **The salt case may escalate.** If the salt bucket is not deterministic across runs for identical input, the blocking key is non-deterministic, which would falsify the gate item "Repeated runs with the same input produce equivalent logical output" and the operational requirement "Deterministic outputs for deterministic inputs." That would be a **High-severity `hadoop-engineer` defect more serious than anything in the agent's list**, and the module's determinism evidence from `LocalPipelineSuite` may not cover a salted non-selective strategy at all. The agent has been instructed to establish what the salt derivation is a function of before touching the case, and to report `BLOCKED-ON` leaving the case failing if it proves non-deterministic.

**Reliability consequence for the whole findings report.** Two of thirteen pins did not survive execution, and neither appeared in the agent's own triage. The remaining eleven are not thereby invalidated — several cite verified line numbers and are internally consistent — but **none should be accepted as a defect on this report alone.** Independent verification by `code-reviewer` is recommended before any repair is dispatched to `hadoop-engineer`.

Reproduction command for later use, note `testOnly` without a project prefix since these cases belong to the root project:

```
sbt "testOnly scholarchain.tests.adversarial.AdversarialJobConfigSuite scholarchain.tests.adversarial.AdversarialCommonNameSuite" 2>&1 | grep -A 20 "==> X"
```

### The hadoop module is unaffected

All 146 module cases pass, including the full 14-case `MapReduceEndToEndSuite`. Nothing in `test-engineer`'s work disturbed the module, and the MapReduce gate item closed in run 8 stands.

## Run 10 — PASSED

Executed by the human operator with the `!` prefix, 2026-11-06 16:58. Not executed by any agent.

Result: **passed.** Two tallies across two projects:

```
[info] Passed: Total 68, Failed 0, Errors 0, Passed 68      <- root project, tests/adversarial
[info] Passed: Total 146, Failed 0, Errors 0, Passed 146    <- hadoop module
[success] Total time: 15 s, completed Nov 6, 2026, 4:58:29 PM
```

**214 of 214 passing.** `.claude/.last-test-run` is current as of run 10.

The two run-9 failures are resolved as expectation corrections, not production changes — see the corrections section of [test-report.md](test-report.md). Both corrected suites are green, and the corrections were net-additive: one false assertion removed, three cases and nine assertions added, moving the adversarial total from 65 to 68.

The full 14-case `MapReduceEndToEndSuite` passes again, so the MapReduce gate item closed in run 8 holds across four subsequent runs.

### The adversarial gate item is delivered, with a caveat the reviewer must weigh

The specification requires: "Test engineer adds adversarial XML, Unicode, missing-field, duplicate-ID, common-name, and giant-team cases." All six categories are delivered and executing: 11 XML, 14 Unicode, 11 missing-field, 10 duplicate-ID, 8 common-name, 8 giant-team, plus 6 job-config beyond the six named.

**The caveat, which is why the orchestrator does not record this item as closed on its own authority.** Thirteen findings are pinned as the behavior currently observed, so those cases are green *because* the defects are present. Five bear on gate items or operational requirements. A green 214 therefore does not mean the parser, counter, determinism and bounding requirements hold — the `test-engineer`'s own words: "Do not let a green 214/214 be read as a clean bill for Phase 4."

F12's dataset-name claim was withdrawn after run 9 and its "idempotent output paths" bearing does not hold; its comma half survives and keeps it gate-bearing. The remaining four gate-bearing findings — F1, F3, F4, F5 — are unchanged and all their pinning cases passed.

Whether the adversarial gate item is satisfied by cases that document defects rather than fail on them is a reviewer judgement, not the orchestrator's.

## Run 11 — PASSED. All five must-fix items verified green

Executed by the human operator with the `!` prefix, 2026-11-06 22:02. Not executed by any agent.

```
[info] Passed: Total 81, Failed 0, Errors 0, Passed 81      <- root project, tests/adversarial
[info] Passed: Total 148, Failed 0, Errors 0, Passed 148    <- hadoop module
[success] Total time: 16 s, completed Nov 6, 2026, 10:02:03 PM
```

**229 of 229 passing**, up from 214 at run 10. `.claude/.last-test-run` is current as of run 11.

This run covers repairs 7, 8, 9 and 9b — the five must-fix items from the final review, the F8 comment correction, the F12 digest collision, the pin conversions and the nine `.fail` twins. It is the first passing run over any of it.

### The two predicted reds are gone, and the twin flip worked

`AdversarialJobConfigSuite` is green. Both consequences `hadoop-engineer` predicted from the framing change — the digest `.fail` twin tripping and the `:97` characterization assertion failing — were flipped by `test-engineer` before the run, so neither appeared. The prediction was exact.

### New end-to-end evidence for the must-fix repairs

`MapReduceEndToEndSuite` gains a fifteenth case and all fifteen pass:

```
+ malformed records reject without failing the task and native conflicts remain visible   1.134s
```

That single case is the runtime proof for two of the must-fix items at once. **F1, F9, F10 and F11** required that a malformed record become a reject rather than throwing out of a map task and failing the stage — the case asserts exactly that, under real `LocalJobRunner` execution. **F4** required that a native-key conflict stay visible rather than being silently collapsed by the pre-dedupe — the same case asserts the conflict remains observable. Both were previously established only by reading.

`AdversarialGiantTeamSuite` shows F7's repair confirmed:

```
+ a team sharing one name retains one coauthor key per mention
```

This also confirms `hadoop-engineer`'s correction to the final review. The reviewer's stated sequencing hazard was that fixing F7 alone would restore the full `n(n-1)` fan-out for same-name teams; the owner argued it yields **one** key per mention because `.distinct` on the coauthor name blocks survives. The case name is now the executed answer, and the owner was right.

### F13b remains correctly open

```
+ suppressed pair expansion has a distinct diagnostic from team size
```

This is a `.fail` twin, so a passing result means its **body still fails** — F13b is still open, as intended. `TeamExpansion.scala` was untouched by every repair in this cycle (last modified 10:29), so `:117` and `:130` still share `RejectReason.OversizeAuthorTeam`. The new `OversizeCoauthorFanOut` code that `hadoop-engineer` added belongs to F3's suppression path, not to F13b. Carried forward as the final review directed.

### Test inventory

| Scope | Run 10 | Run 11 |
|---|---|---|
| hadoop module | 146 | 148 |
| `tests/adversarial` | 68 | 81 |
| **Total** | **214** | **229** |

### What run 11 does not establish

**No independent review covers any of this.** The [final review](final-review.md) is timestamped 17:20 and covers work through repair 6. Repairs 7, 8, 9 and 9b all postdate it. The orchestrator verified repair 8's scope and each must-fix item by reading source, recorded in [repairs.md](repairs.md) and [versions.md](versions.md), but **orchestrator verification is not independent review**.

Repair 8 was additionally authored with OpenAI Codex rather than by a Claude subagent, under the authorization recorded in [versions.md](versions.md). That makes an independent verdict more important, not less.

## Gate status

As of run 11.

| Gate item | Status |
|---|---|
| `sbt check` passes | **PASSED, run 11**, 229/229. Runs 1, 2, 4, 7 and 9 failed; runs 3, 5, 6, 8, 10 and 11 passed |
| Test marker current | **Current as of run 11** |
| Two real MapReduce jobs execute | **CLOSED on executed evidence, run 8.** Two chained jobs through `LocalJobRunner`: genuine submission, 4 splits, map tasks over distinct byte ranges, spills, shuffle, reduce tasks consuming grouped keys, `FileOutputCommitter` v2. Job 2 consumes job 1's committed output. **Limit:** `LocalJobRunner` runs one reduce task, so multi-reducer fan-out is unexercised |
| DBLP record boundaries correct | Passing, `XmlRecordStreamSuite` and `DblpParserSuite`, run 5 |
| Pair generation on 1, 2, 3 and larger author lists | Passing, `TeamExpansionSuite`, run 5 |
| Counters and reject outputs visible | **PASS on runtime evidence, run 7.** Counters read off a completed job (`InputRecords=4`, `AcceptedRecords=3`, `DuplicateRecords=2`, `RejectedRecords=1`, `EmittedRecords=6`) and a map-detected reject committed to `rejects/v1/MISSING_REQUIRED_FIELD/part`. **This item was false at runtime through run 6** while passing review on inspection; repair 5 fixed it |
| Repeated runs produce equivalent output | Passing, `LocalPipelineSuite`, run 5; measured output identical across runs 3, 4 and 5 |
| Sample results contain provenance | Passing, `DblpParserSuite` and `LocalPipelineSuite`, run 5 |
| `test-engineer` adversarial cases | **SATISFIED** by the final review, with one condition: the `.fail` pairing must be dispatched as a follow-up, not left optional, because a green pinned case over an open defect is a silent-fix hazard. See [final-review.md](final-review.md) |
| `code-reviewer` APPROVE | **APPROVE on the repairs; Phase 4 RATIFIABLE.** All five must-fix items closed on run-11 evidence. See [must-fix-re-review.md](must-fix-re-review.md). Prior verdicts: [review.md](review.md), [re-review.md](re-review.md), [final-review.md](final-review.md) |

### Ratification blockers, per the final review

| Item | Owner |
|---|---|
| F1 (+ F9, F10, F11 as one reader/parser class) — throws out of a map task instead of rejecting | `hadoop-engineer` |
| F3 + F7 as one repair, F3's bound first — unbounded quadratic blocking fan-out | `hadoop-engineer` |
| F4 (+ F13a) — duplicate and conflict counters structurally incapable of firing on a native key | `hadoop-engineer` |
| R1 — `EmittedRecords` double-counted across map and reduce at four sites | `hadoop-engineer` |
| R2 — `RejectedRecords` double-counted at `LocalPipelineRunner.scala:400` | `hadoop-engineer` |

Carried forward, not blocking: F2, F5, F6, F8 (comment correction mandatory), F12 and its digest collision, F13b–e, R3, R5, R6. Conditions: F2 and F6 before Phase 5 consumes blocking keys; F12 before any config- or manifest-driven snapshot source; R5 and R6 before the first full-snapshot or EMR run.

### Correction, 2026-11-06

**The block that stood here was stale run-5-era text and contradicted the gate table above it in this same section.** It stated that the MapReduce gate item was open and unrouted and that "`tests/` holds no Phase 4 cases," while the gate table records that item **closed on run-8 evidence** and counts **81 cases** in `tests/adversarial`. It also listed as outstanding blockers two items that have since closed and one that was deferred by architect decision.

Removed and replaced on the condition set by the [must-fix re-review](must-fix-re-review.md), which identified it. The orchestrator owns this file as archivist and the error was its own: superseded narrative was left in place beneath a table that had been kept current, so the same section asserted two different states.

### Current status of the items that block or once blocked

| Item | Status |
|---|---|
| Two real MapReduce jobs execute | **CLOSED**, run 8, confirmed by the final review and again by the must-fix re-review |
| Adversarial cases under `tests/` | **SATISFIED**, 81 cases executing as of run 11 |
| Hadoop mini-cluster test runtime | **Resolved as a blocker.** `hadoop-client-minicluster % Test` is on the classpath; whether it is load-bearing is the pending experiment in [decisions.md](decisions.md) section 3, held until after this cycle's green check |
| `MatchCandidate` core contract | **Deferred to Phase 5** by architect decision. The final review confirmed it is **not** a Phase 4 gate item; `hadoop-engineer`'s module-local handling was endorsed |
| The five must-fix items from the final review | **All CLOSED**, verified by the must-fix re-review on run-11 evidence |

Everything still carried forward is listed in [must-fix-re-review.md](must-fix-re-review.md) under its carried-forward section, which supersedes the list above it in this file.
