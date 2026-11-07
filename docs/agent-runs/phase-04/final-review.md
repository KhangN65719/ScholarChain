# Phase 4 final review

Reviewer: `code-reviewer`. Received by the orchestrator 2026-11-06.

Owners of the reviewed work: `hadoop-engineer` (repair 5 and 6, module sources), `test-engineer` (adversarial suites), `build-engineer` (build changes).

Validation: [validation.md](validation.md), run 10 passed, 214/214. Prior verdicts: [review.md](review.md), [re-review.md](re-review.md).

Usage: 214578 reviewer tokens, 40 tool uses, 576345 ms. A first instance of this review was stopped by the architect in error and could not be resumed; this is a fresh instance that re-read the evidence from scratch.

Reviewer declarations: read-only throughout, no file created or modified, no command run. All execution evidence quoted from `validation.md`.

## Two verdicts

**On the work delivered since the prior verdict: REQUEST CHANGES** — narrowly. Repair 5's reject half is correct and complete; its counter half is incomplete.

**On Phase 4's readiness for ratification: NOT RATIFIABLE.** Four confirmed defects falsify specification gate items or named operational requirements.

## Priority 1 — Repair 5's nine main sources

### Reject routing: CORRECT AND COMPLETE across all seven reducers

All seven stage reducers route the reject stream, each **before** any attempt to decode the group as its own record type:

| Stage | Reducer | Reject branch |
|---|---|---|
| dblp-extraction | `DeduplicateReducer` | `DblpExtractionJob.scala:134-139` |
| source-normalization | `NormalizeReducer` | `SourceNormalizationJob.scala:155-158` |
| identifier-join | `JoinReducer` | `IdentifierJoinJob.scala:91-93` |
| blocking | `BoundingReducer` | `BlockingJob.scala:101-104` |
| candidate-features | `FeatureReducer` | `CandidateFeatureJob.scala:97-99` |
| team-expansion | `ConsolidateReducer` | `PublicationTeamJob.scala:112-115` |
| graph-export | `MergeReducer` | `GraphExportJob.scala:174-177` |

The guard-before-decode discipline holds in all seven; where a reducer computes `encodings` ahead of the label check it is only `_.toString`, and every `ContractWire.decode` sits inside a non-reject branch.

The counting contract is coherent, with no path that double-counts or drops a reject in the Hadoop jobs: `MapOutputSink.reject` (`HadoopSink.scala:50-54`) counts at detection then routes; `ArtifactSink.commitReject` (`:109-116`) writes and deliberately does **not** count, correct because one Hadoop counter spans both phases; `ArtifactSink.reject` (`:97-99`) counts once for a reducer-detected reject then delegates. `RunContext.rejectPath:133-134` is a strict prefix of the committed `rejects/v1/<CODE>/part`, confirmed by run 7's `rejects/v1/MISSING_REQUIRED_FIELD/part=1`. `JobSupport.configureOutput:52-61` registers the reject named output unconditionally for every job, and `RunContext:37` requires `partitionCount > 0`, so no stage can become map-only and strand a routed reject.

**The reviewer's assessment: "That is a genuine fix."**

### R1 — the analogous defect that remains unfixed. Medium. MUST FIX

`ExpandedPairs`/`EmittedPairs` were split to stop one counter accumulating across both phases of one job. **The identical defect still exists in `EmittedRecords` at four sites**, because `TaskSink.emitRecord:132-138` increments `EmittedRecords` and both mapper and reducer call it:

| Job | Map-side | Reduce-side |
|---|---|---|
| team-expansion | `TeamExpansion.scala:139` | `PublicationTeamJob.scala:101` |
| blocking | `Blocking.scala:330,340` | `BlockingJob.scala:143,145` |
| identifier-join | `IdentifierJoin.scala:122` | `IdentifierJoinJob.scala:121` |
| candidate-features | `CandidateFeatureJob.scala:54` | `CandidateFeatureJob.scala:124` |

Three reducers already show the intended pattern using `sink.emit`, which does not count — `DblpExtractionJob.scala:148`, `SourceNormalizationJob.scala:164`, `GraphExportJob.scala:186`. So the fix shape exists in the codebase and four sites deviate. In team-expansion the same ten pair records are counted twice, which is exactly what the dedicated split was introduced to prevent. No test catches it: `MapReduceEndToEndSuite.scala:419-431` asserts `ExpandedPairs` and `EmittedPairs` and never reads `EmittedRecords` for job 2.

### R2 — `RejectedRecords` double-counted in the local runner. Medium. MUST FIX

`LocalPipelineRunner.scala:397-400`:

```scala
mapSink.counterValues.foreach { case (counter, value) =>
  reduceSink.count(counter, value)          // copies RejectedRecords = k
}
mapSink.rejects.foreach(reduceSink.reject)  // RecordingSink.reject counts again → 2k
```

`RecordingSink.reject` (`TaskSink.scala:154-156`) increments `RejectedRecords`, and the preceding loop already copied that counter wholesale. Green today only because the local fixture's largest team is 5 against `maxAuthorTeamSize = 50`, so `TeamExpansion.emit` never rejects — **green for the same reason the original reject defect was green.**

The sibling stage is inconsistent in the other direction: `block()` at `:307-310` copies counters but never forwards `mapSink.rejects`, so a map-side reject there would be counted and absent from `StageOutcome.rejects`. Latent, but should be made consistent in the same edit, since the local runner is the source of every measured figure in `validation.md`.

### R3 — Low, non-blocking

`BlockingJob.scala:98,103` and `CandidateFeatureJob.scala:97-99` pass a variable named `blockKey` to `commitReject(reasonCode, …)`. Functionally correct, but the name states the wrong thing in new code — "the F4/F7 defect class this phase has already corrected twice."

### R5 — Low-Medium, new, non-blocking for the gate

Rejects travel the shuffle keyed on their reason code, and `StableKeyPartitioner` partitions on the logical key. With nine `RejectReason` codes (`TaskSink.scala:42-51`), all reject traffic in a job concentrates on at most nine reduce partitions, and in the realistic dominant-reason case on one. Interacts badly with R6. Unexercised because `LocalJobRunner` runs one reducer.

### F8's message correction and the pair split: APPROVE

The split is correct (`TaskSink.scala:21-31`, `TeamExpansion.scala:144-147`, `PublicationTeamJob.scala:102`, `LocalPipelineRunner.scala:410`). F8's correction is in place at both sites with identical wording and now reads `records`, matching the F3 definition.

## Priority 3 — the MapReduce gate item: CLOSED

**Satisfied, stated plainly.** `LocalJobRunner` is Hadoop's execution engine, not a substitute for it. `MapReduceEndToEndSuite.scala` submits through `Job.waitForCompletion` (`:174,185`); Hadoop computes splits through the project's own `DblpXmlInputFormat` (`:216-220`); real task attempts read distinct byte ranges; `MapOutputBuffer` spills with deflate compression; a shuffle delivers grouped keys (`REDUCE_INPUT_GROUPS == 5` at `:242`, `REDUCE_INPUT_RECORDS == 18` at `:341`); `FileOutputCommitter` v2 promotes output that `:269-283` reads back off disk. Job 2 is configured from `run.artifactPath(Artifact.DblpWorks)` (`:183`), job 1's committed output, so the chaining is genuine.

The specification's exclusion is not in play: `LocalPipelineSuite.scala:56-68` is the collection-operation harness, and this suite is categorically a different thing.

**Multi-reducer fan-out: accept as a recorded limit, not a blocker.** The gate requires execution, not fan-out, and `MapReduceEndToEndSuite.scala:60-71` states the limit honestly. But R5 turns it from a cosmetic gap into a named hazard — reject-stream skew and blocking hot keys are exactly what multi-reducer execution would expose, and nothing has exposed them. **Recorded as a Phase 5 prerequisite.**

**`hadoop-client-minicluster` (`build.sbt:152`): keep conditionally — and "unused" is itself unverified.** The reviewer cannot determine by reading whether `LocalJobRunner` resolves from `hadoop-client-runtime` or from the minicluster; both are plausible and the evidence cannot separate them, because the minicluster entry landed before run 6 while the first job ran in run 7. Its proposed decisive test, routed to `build-engineer`: delete `build.sbt:152` and run `sbt clean check`. If `MapReduceEndToEndSuite` still passes it is genuinely unused; if it fails, the gate item depends on it and the question closes. If unused, keep only against a dated work order for the multi-reducer test — otherwise drop it, since it is Hadoop's largest test artifact.

## Priority 2 — the thirteen findings, independently verified

**All thirteen observations confirmed.** One interpretation correctly withdrawn by `test-engineer` (F12's path blast radius). Two interpretations it withdrew the reviewer now **restores** by reading (F3's cubic bytes, F6's cross-source step). One is **broader** than claimed (F4). One reachability caveat agreed (F5).

| # | Confirm | Severity | Verdict |
|---|---|---|---|
| F1 | Yes, escalated | **High** | **MUST FIX** |
| F2 | Yes | High (recall) | Known issue + Phase 5 condition |
| F3 | Yes, strengthened | **High** | **MUST FIX** |
| F4 | Yes, broader | **High** | **MUST FIX** |
| F5 | Yes | Medium | Known issue; fix opportunistically |
| F6 | Yes, incl. unasserted step | Medium-High | Known issue + Phase 5 condition |
| F7 | Yes | Medium-High | **MUST FIX, with F3** |
| F8 | Yes, both halves | Medium | Known issue; comment correction mandatory |
| F9 | Yes | Medium | Known issue; fix with the reader class |
| F10 | Yes | Low-Medium | Known issue; fix with the reader class |
| F11 | Yes | Medium | Known issue; fix with the reader class |
| F12 | Yes, as narrowed | Medium, latent | Known issue + config-driven condition |
| F13a–e | Yes, all five | Low (b: Low-Med) | Known issues |

### F1 — more serious than reported. MUST FIX

`TextNormalizer.fold:25-32` ends in `[^0-9a-z]+ → " "` then `.trim`, so `"李明"` folds to `""`. `AuthorMention` is constructed at `DblpParser.scala:121-135`, and `Contracts.scala:138` throws during the `mentions` construction at `:118-137` — so the parser's own guard at `:145-148` **can never run. Dead code confirmed.** A non-Latin title throws from `Contracts.scala:104` at `DblpParser.scala:170`, with no guard at all.

**The escalation:** `DblpExtractionJob.map:50-89` has no `try`. An `IllegalArgumentException` from `parse` propagates out of `map`, fails the task attempt, and after `mapreduce.map.maxattempts` **fails the stage**. Not one lost record — a single author name or title composed only of non-ASCII-letter characters anywhere in the snapshot kills job 1. Falsifies "Emit valid normalized WorkRecord and AuthorMention records" and "Write malformed and unsupported records to versioned reject outputs" on the first real snapshot. The core contracts are correct; the fix belongs at the construction sites in `modules/hadoop`.

### F3 — strengthened. MUST FIX. "The one I would block on first"

`Blocking.emit:327-346` through `researcherKeys:232-247` emits one `NameCoauthor` key per coauthor — exactly `n*(n-1)` records from **one** input record, with no bound of any kind. Pinned at `AdversarialGiantTeamSuite.scala:158` (`64*63`) and `:160`, with zero rejects at `:165`.

**The reviewer restores the cubic-bytes claim `test-engineer` withdrew as unmeasured: it is structural, not empirical.** Each of those `n(n-1)` records is a `researcherMember` (`Blocking.scala:306-324`) carrying `coauthorNames`, a vector of `n-1` names. Quadratic record count × linear per-record width = **bytes cubic in n**. The test's loose ratio at `:179` is consistent with it and understates it.

`maxPairsPerWork` guards only `TeamExpansion:72`; `maxAuthorTeamSize` only counts (`:112-113`); `maxBlockSize` bounds what one block *retains*, not map-output volume across `n(n-1)/2` distinct keys. "Emit bounded candidate blocks" and "Prevent pathological common-name or title blocks from overwhelming reducers" are both false for this path.

### F4 — generalizes beyond the one source verified. MUST FIX

`IdentifierJoin.resolve:137-139` runs `distinctBy((source.wire, sourceRecordId, entityId))` **before** duplicates (`:148-155`) or conflicts (`:156-159`) are derived. At `:70-77` the native-key value is `work.sourceRecordId` for **all five** sources that have one — DBLP, OpenAlex, SemanticScholar, Crossref (→ `Doi`) and Arxiv — and `entityId` derives from the same key. The three-tuple is invariant across any replay, and the second claim's title is discarded uncompared. `test-engineer` narrowed this to one source; **the generalization is correct by construction and the reviewer confirms it.**

Duplicates are partly compensated by job 1's `DeduplicateReducer` (`DblpExtractionJob.scala:143-147`, observed as `DuplicateRecords=2` in run 7). **Conflicts are not compensated anywhere** — that reducer takes `encodings.min` and never reports disagreement, so two contradictory titles on one DBLP key are silently resolved and counted as a duplicate. "A counter that is structurally incapable of firing on its primary case does not satisfy 'counters and reject outputs are visible' in substance — the same failure shape repair 5 already found once."

### F7 — MUST FIX, and a sequencing hazard the report did not name

`Blocking.scala:337` excludes by normalized name, not mention identity, so a paper whose authors all fold to one name emits zero `NameCoauthor` keys. Pinned at `AdversarialGiantTeamSuite.scala:195`. One-line fix.

**Fixing F7 makes F3 worse.** Excluding by `mentionId` restores the full `n(n-1)` fan-out for exactly the same-name teams that currently emit nothing. **F3's bound must land first or in the same change. Dispatch them as one repair.**

### F2, F5, F6 — confirmed, carried forward

**F2** confirmed at `TextNormalizer.scala:25-32`; a Latin letter with no canonical decomposition becomes a space mid-token. Pinned at `AdversarialUnicodeSuite.scala:78-81,88-90,114-125` including the Cyrillic homoglyph. No gate asserts recall, so not a Phase 4 blocker — but it silently renames authors and `modules/entity-resolution` consumes these keys. **Condition: fixed before Phase 5 consumes blocking keys.** The remedy is a design choice (transliterate vs retain) and **the architect owns it, not `hadoop-engineer`.**

**F6** confirmed: `BoundedBlockSelector`'s ordering is `(member.source.wire, member.memberId)` (`Blocking.scala:98-99`) with source **first**, so 60 DBLP + 60 OpenAlex at bound 50 retains 50 DBLP and zero OpenAlex. **The reviewer confirms the step `test-engineer` left unasserted:** `MatchCandidates.candidates:231-242` enumerates within-block pairs and `source.distinct` (`:169`) is only a feature, never a filter, so a block retaining one source yields **zero** cross-source candidates. Mitigated by the same members appearing in untruncated selective blocks. Same Phase 5 condition as F2.

**F5** confirmed at `TeamExpansion.scala:164`. Within one run duplicate pair records for one `workId` are byte-identical, so the tie is immaterial; it bites only on a cross-run re-ingest into one job's input. The reviewer agrees the reachability caveat is right. Fix is three added discriminators; worth taking with F13a, same area.

### F8–F11 — one defect class, not four findings

**F8** reproduced exactly: `readName`'s terminator set (`XmlRecordStream.scala:62-64`) returns `"!--"` for `<!-- <article` (terminated by the space), misses, and the scan then finds the `<article` **inside** the comment; for `<!--<article` it returns `"!--<article"` and misses, because `<` is not a name terminator. The one-space dependence is exact. CDATA behaves the same. The comment at `:87-90` asserts the opposite of the behavior — **"the third false comment this phase has produced. Correcting the comment is mandatory even if the skip-region fix is deferred."**

**F9** confirmed: `readRecordBody:124-133` treats the first `>` while `inTag` as end-of-start-tag, so `<article key="a>b"/>` never returns to depth zero and throws out of the reader. The comment at `:101-105` is correct about `<` and wrong about `>`.

**F10** confirmed: the same throw escapes `iterator:155-156`. In the Hadoop path earlier records were already emitted, but the attempt fails and Hadoop discards its output, so the split yields nothing and the job fails after retries.

**F11** confirmed at `:140-142`; U+FFFD substitution with no counter, no reject, no diagnostic, and the corruption reaches `normalizedTitle`, which `Blocking.workKeys:265-286` uses as a join key.

**The reviewer's consolidation: F1, F9 and F10 are one defect class — the record reader and parser throw out of a map task instead of producing a reject.** "Write malformed and unsupported records to versioned reject outputs" is defeated for every input in that class, and the failure mode is total rather than per-record. F1 is the member most likely at full-snapshot scale and is the must-fix; F9, F10 and F11 share the method and the remedy and should be fixed in the same change.

### F12 — withdrawal correct; the surviving half is worse-specified than reported

The reviewer verified the withdrawal independently: `RunContext.snapshotId:45-50` digests `s"$dataset=$version\u0000"`, so both maps feed `"db=lp=2026-11-01\u0000"` — byte-identical. `snapshotId`, `runPath` and every artifact path are invariant. "The agent's withdrawal is right, and it withdrew without hunting for a substitute, which is the correct behavior."

**The surviving comma half's real consequence is not what the report says, and is more serious than "paths move":** `JobSupport.configureOutput:46-51` sets the output path **client-side** from the original `RunContext`, while each task's `run.provenance` comes from the **round-tripped** one (`JobConfig.read:59-72`). A comma-bearing version therefore commits records whose `provenance.snapshotId` disagrees with the `snapshot=` segment they are stored under — **a direct violation of the CLAUDE.md provenance rule**, not only of the idempotent-path requirement.

Reachability is low: `PipelineOptions.scala:21-23` reads snapshots through the same parser, so an operator cannot express a comma through `-D`; only in-code construction can. **Condition: fixed before any snapshot source becomes config- or manifest-driven.**

**The Low digest collision is confirmed and reachable from `RunContext` alone:** `Map("a=b" -> "c")` and `Map("a" -> "b=c")` are distinct snapshot sets addressing one prefix. Fix is one `require` at write time plus length framing as `StableIds.digestId` already does. **Both halves cheap enough that the reviewer recommends fixing now rather than carrying.**

### F13a–e — all five confirmed

- **F13a** `IdentifierJoin.scala:180` returns `conflictingTitles.size`; three disagreeing titles report three conflicts. Fix with F4, same function.
- **F13b** confirmed and **worse than described**: `TeamExpansion.scala:117` and `:130` both use `RejectReason.OversizeAuthorTeam`, so both reject classes land in one directory. `AdversarialGiantTeamSuite.scala:121,127` pins the contradiction — `OversizeAuthorTeams == 0` while a reject reading `OVERSIZE_AUTHOR_TEAM` exists. **Low-Medium**, not Low.
- **F13c** confirmed; a zero-author `WorkRecord` is constructible — `Contracts.scala:113-118` constrains the sequence but does not require it nonempty.
- **F13d** confirmed; the refusal at `ExactIdentifiers.scala:105-107` is right, the silence is the defect.
- **F13e** confirmed at `IdentifierJoin.scala:86,97-105`.

### R6 — new, not among the thirteen. High for the real snapshot. Phase 5 blocker

`XmlFragment.factory():27-30` sets `disallow-doctype-decl` and `Strict:52-53` throws on any error, so an undefined-entity reference is a fatal parse error returned as a `MALFORMED_XML` reject. **The security posture is right and the reviewer would not change it.** But the real DBLP dump uses named HTML entities (`&auml;`, `&eacute;`, …) extensively, so on a full snapshot this plausibly rejects a large fraction of records — making the accepted/rejected ratio meaningless, starving the graph, and with R5 concentrating that reject traffic on one reduce partition.

`test-engineer` recorded this as category 2, "correct and deliberate," flagging only reject-output sizing. **The reviewer disagrees that nothing is owed:** the remedy is not to enable the DTD but to pre-resolve DBLP's fixed, published entity set before parsing, with a counter. Route to `hadoop-engineer` with `dataset-engineer` supplying the entity list. Not a Phase 4 gate blocker; a Phase 5 blocker, recorded because it is currently nowhere as a defect.

## Lower-priority items

### The adversarial gate item: SATISFIED, with one condition

All six categories present and executing — 11 / 14 / 11 / 10 / 8 / 8 plus 6 job-config, 68 confirmed by counting `test(` blocks. The specification wording is "adds", and characterization-over-defect satisfies it. The reviewer goes further: "pinning produced thirteen located, line-cited findings, which a red suite would not have." The cases are honestly named and commented, so nobody reading the suite could mistake them for requirements being met.

**Condition:** a green pinned case over an open defect is a silent-fix hazard, and right now the only protection against it is the written record. The `.fail` pairing removes it. **Record the item satisfied, with the `.fail` work as a dispatched follow-up rather than an optional improvement.**

### Phase 2 F1 and F2: both genuinely CLOSED

- **F1 CLOSED.** `build.sbt:73-77` puts all three reserved roots on `Test / unmanagedSourceDirectories`. Demonstrated, not inspected: run 9 compiled and ran 65 cases from `tests/adversarial` and **failed the check on them**, so the marker can no longer be written over a test placed there. The Phase 2 gate-strength reduction is retired.
- **F2 CLOSED.** `build.sbt:64` is `root.dependsOn(core % "test->compile", hadoop % "test->compile")`; all eight files import `scholarchain.hadoop` and compiled.
- **The binding constraint holds.** Both edges are `test->compile`, so `hadoop` appears in no `Compile`, `Runtime` or packaged classpath; aggregation (`:50-58`) is not a classpath edge; root is `publish / skip := true` (`:67`). The `Provided`-does-not-cross-a-project-edge subtlety is correctly handled at `:81-82`.

### The three post-verdict items

**`simplelogger.properties` — APPROVE.** `warn` is right, and `:9-13` argues it correctly: `ERROR` would discard the task-failure stacks and `FileAlreadyExistsException` the suite's cases depend on. Non-blocking note: it sits on the hadoop module's test classpath, and `test->compile` does not carry hadoop's test *resources* to the root project, so a future `tests/integration` suite running a job will flood again.

**`build.sbt` — APPROVE every entry.** `hadoop-client-api` stays `Provided` (`:142`); `hadoop-client-runtime` and `hadoop-client-minicluster` are `Test` only (`:146,152`), each with rationale in the file. The slf4j 1.7.36 → 2.0.18 eviction is `Test`-scope only and does not touch `services`, which keeps its own entries (`:174-177`). `check` ordering is correct — `Def.sequential` writes the marker only after `(Test / test).all(allProjects)` succeeds (`:107-129`), and run 9 proved the root project is inside `inAnyProject`. No version value changed.

**The Low snapshot-digest collision — confirmed.** Fix now; one `require` plus length framing.

### Architect questions — advice

**1. `.fail` pairing: agreed, with a refinement `test-engineer` did not make.** Keep both cases, since a `.fail` body passes when it fails for *any* reason — tripwire, not diagnostic. **But do not apply it uniformly to all thirteen.** For **F2 and F6 the desired behavior is an unmade design decision** (transliterate vs retain; within-source vs round-robin selection), so a `.fail` case would encode a policy `test-engineer` does not own. Those two get a comment pointing at this verdict and a `.fail` twin only after the architect fixes the policy. For the rest the corrected assertion is crisp and the pairing should land. **Sequencing: for each must-fix finding, dispatch the `.fail` twin in the same work order as the repair, so the fix flips a red test rather than being graded by prose.**

**2. `AdversarialJobConfigSuite` stays separate: agreed, with a stronger reason.** It is the only suite anywhere that instantiates a Hadoop type from the **root** project, making it the standing regression test for `build.sbt:81-82` — the two root-level Hadoop `Test` declarations nothing else covers. "If those lines are ever deleted as 'unused', this suite is what objects. That is a build-level canary and it should not be dissolved." Decline the redistribution.

## Evidence limits recorded by the reviewer

Every finding rests on reading source, because `modules/hadoop/**` and `tests/**` are untracked and `git diff` is empty for them — **there is no baseline against which to diff repair 5**, so the reviewer verified the current text on its merits rather than verifying the change. R1, R2 and R5 are unexercised by any test and confirmed by reading only. Whether `LocalJobRunner` resolves from `hadoop-client-runtime` or `hadoop-client-minicluster` is **not determinable by reading** and is pending `build-engineer`.

## Still pending, unresolved by this review

- `totalRejects=1` in the local run: the originating stage was not traced. `StageOutcome` already carries rejects per stage; the measured line should print per-stage reject counts instead of a total. Pending `hadoop-engineer`.
- **F6 reporting stays PARTIALLY SATISFIED.** The job graph (`PipelineDriver.scala:32-42`), per-job mapper and reducer key-value contracts, per-stage counter values and the full reject and output path set remain unreported in prose, as does the prior verdict's definitional note on `largestBlockingGroup`.

## What blocks ratification — exactly this and nothing more

| Item | Owner |
|---|---|
| **F1** (+ F9, F10, F11 as one reader/parser class) — throws out of a map task instead of rejecting | `hadoop-engineer` |
| **F3 + F7 as one repair, F3's bound first** — unbounded quadratic blocking fan-out | `hadoop-engineer` |
| **F4** (+ F13a) — duplicate and conflict counters structurally incapable of firing on a native key | `hadoop-engineer` |
| **R1** — `EmittedRecords` double-counted across map and reduce at four sites | `hadoop-engineer` |
| **R2** — `RejectedRecords` double-counted at `LocalPipelineRunner.scala:400` | `hadoop-engineer` |

**Does not block ratification; carried forward with this verdict as the record:** F2, F5, F6, F8 (comment correction mandatory), F12 and its digest collision, F13b–e, R3, R5, R6.

Conditions attached: F2 and F6 before Phase 5 consumes blocking keys; F12 before any config- or manifest-driven snapshot source; R5 and R6 before the first full-snapshot or EMR run.

## Next gate as the reviewer states it

Route the five must-fix items to `hadoop-engineer` as one dispatch, each paired with its `.fail` twin from `test-engineer`, then a human `sbt clean check` and an independent re-review of the changed sources. This verdict does not approve its own findings and is itself subject to independent review. Nothing committed.
