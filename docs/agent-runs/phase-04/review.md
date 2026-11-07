# Phase 4 independent review

Reviewer: `code-reviewer`. Owner of the reviewed work: `hadoop-engineer`. Received by the orchestrator 2026-11-06.

Work order: [reviewer-work-order.md](reviewer-work-order.md). Gate: [prompt.md](prompt.md). Validation: [validation.md](validation.md), run 3 passed.

Usage as reported by the runtime: 172241 reviewer tokens, 45 tool uses, 370083 ms.

Reviewer declarations: read-only throughout, no file created or modified, no command run. All validation evidence quoted from `validation.md` (human-run `sbt clean check`, run 3, 118/118).

## Verdict

**REQUEST CHANGES.**

The reviewer's summary judgement: the architecture is strong and the owner's gate items are substantively met; APPROVE is withheld for four narrow defects in owner-owned files plus the reporting deficiency, and the phase cannot pass regardless until the two unrouted gate items are closed.

## The specific question: did the `SourceNormalizerSuite` repair weaken the test?

**No. It strengthened it.** All five sub-checks answered, with one recorded evidence limit.

1. **Assertions genuinely verify no text is copied, both sources.** Crossref at `SourceNormalizerSuite.scala:256-270` asserts `abstractTextRef == Some("crossref:abstract/10.1000/abc")` then encodes the whole record and asserts absence of both `"secret"` and `"<p>"`. OpenAlex at `:272-288` likewise. The whole-record scan is substantive, not cosmetic: `ContractWire.encode` is `upickle.write` over the full `WorkRecord` (`ContractWire.scala:6-8`), which serializes every field including nested `authors` with their `externalIds` and `affiliations` (`Contracts.scala:82-99`), so a leak into any nested field fails.
2. **The cases reach their subject, verifiably by construction.** The Crossref fixture's `10.1000/abc` satisfies `doiShape` (`ExactIdentifiers.scala:57,85-89`), title present, empty author array passes the gate at `SourceNormalizers.scala:430-433`, so normalization returns `Right` and `abstractTextRef` is set at `:516-517`. The OpenAlex fixture passes `openAlexOf('W', …)` and the `:149-152` gate; `abstract_inverted_index` is read by presence at `:270-271`. Neither assertion can pass vacuously — both compare against an exact expected string.
3. **The fixture was the right side to fix.** The run-2 fixture was a one-digit registrant prefix. Registered DOI prefixes are `10.` plus four to nine digits, exactly what `doiShape` encodes, and `Regex.matches` is full-anchor. The normalizer was correct to reject it (`SourceNormalizers.scala:372-378`); the fixture was invalid. The repair additionally **pinned** that rejection as its own case at `:240-254`, asserting both `ExactIdentifiers.doi("10.1/a") == None` and a `MissingRequiredField` reject.
4. **Nothing was deleted, loosened, or made tolerant of a `Left`.** Case count reconciles exactly: 15 cases in run 2 (14 pass + 1 fail), minus 1 combined case, plus 2 split abstract cases, plus 1 DOI-prefix case = 17, matching run 3. The reviewer counted 17 `test(` blocks. Both abstract cases still use `.getOrElse(fail("normalization failed"))` at `:262` and `:283`, so the run-2 failure mode was **preserved, not suppressed**. Every reject-expecting case asserts a specific `RejectReason` rather than mere `isLeft`.
5. **The repair-1 `abstractTextRef` change did not contribute to the run-2 rejection** — settled from the code without the owner's answer. The run-2 failure was a `Left` from the **Crossref** normalizer, whose only `Left` paths are the DOI gate, the title gate (`SourceNormalizers.scala:374-382`) and `readJson` (`:40-53`). The `abstractTextRef` expression is a total `Option` map in either shape and cannot produce a `Left`. The repair-1 OpenAlex change was causally unrelated; the new case at `:272-288` is a regression pin for it.

**Evidence limit, recorded as such and not as a defect:** the pre-repair text of the three changed cases is unrecoverable (never committed, baseline empty), so "at least as strict as the original" rests on the current assertions' substance plus the reconciled case count, not on a direct textual comparison.

## Are the `jobs/**` classes genuine MapReduce?

**Genuine, unambiguously, from the code.** All seven stages are real `org.apache.hadoop.mapreduce` jobs:

| Stage | Mapper / Reducer | Location |
|---|---|---|
| 1 DBLP extraction | `ExtractMapper` / `DeduplicateReducer`, custom `DblpXmlInputFormat` + `RecordReader` | `jobs/DblpExtractionJob.scala:40,120,152`; `jobs/DblpXmlInputFormat.scala:33,62` |
| 2 Normalization | `NormalizeMapper` / `NormalizeReducer` | `jobs/SourceNormalizationJob.scala:37,140` |
| 3 Identifier join | `ClaimMapper` / reducer | `jobs/IdentifierJoinJob.scala:33` |
| 4 Blocking | `BlockingMapper` / `BoundingReducer` | `jobs/BlockingJob.scala:35,83` |
| 5 Candidate features | `RegroupMapper` / `FeatureReducer` | `jobs/CandidateFeatureJob.scala:33,79` |
| 6 Team expansion | `ExpandMapper` / `ConsolidateReducer` | `jobs/PublicationTeamJob.scala:37,77` |
| 7 Graph export | five `ExportMapper` subclasses via `MultipleInputs` / `MergeReducer` | `jobs/GraphExportJob.scala:53-149,159,191-207` |

Supporting evidence of real wiring: per-task `setup`/`cleanup` with no static state (`jobs/HadoopSink.scala:24-97`); `MultipleOutputs` named outputs plus `LazyOutputFormat` (`jobs/JobSupport.scala:52-65`); custom `Partitioner` (`jobs/StableKeyPartitioner.scala:28-34`); intermediate compression and speculation disabled (`jobs/JobConfig.scala:89-104`); `Tool`/`ToolRunner` entry points on every stage; split-aware `RecordReader` claiming a record only if its opening `<` falls in `[splitStart, splitEnd)` and then reading past the split end (`pipeline/XmlRecordStream.scala:76-95,145-151`); `isSplitable` refusing non-splittable codecs (`DblpXmlInputFormat.scala:34-37`).

**However: no test touches `jobs/**`.** A grep across the test tree for `hadoop.jobs|JobConfig|JobSupport|Mapper|Reducer|DblpXmlInputFormat|StableKeyPartitioner|PipelineDriver` returned **zero matches**. All 14 files under `jobs/**` compile and are never executed. The 118 passing tests establish the transformation logic — which `LocalPipelineRunner` honestly documents as "not a Hadoop job execution" (`pipeline/LocalPipelineRunner.scala:56-68`) — not the MapReduce wiring. `LocalPipelineRunner.extractDblp` and `.normalize` are map-only, so `DeduplicateReducer` and `NormalizeReducer` have no logical analogue under test either.

### Correction to the orchestrator's reading

The reviewer confirms both open gate items and their attribution, with one correction the orchestrator records as material:

> **Correction to the owner's framing:** the blocker justifies the absence of *execution* tests, not the total absence of job-class tests. In sbt, `Provided` is on the `Test` classpath by default, so `JobConfig.write`/`read` round-trips and `StableKeyPartitioner.getPartition` are plausibly unit-testable today against the api-only jar.

The reviewer could not verify that classpath behavior without running sbt and marks it **pending**, worth one cheap attempt by the owner before accepting the blocker as total. The build declares only `hadoop-client-api % Provided` (`build.sbt:112`); there is no `hadoop-client-runtime` or `hadoop-minicluster`, so mini-cluster execution is impossible.

On adversarial cases: `tests/` holds no Phase 4 cases. The reviewer notes `DblpParserSuite` already carries adversarial-flavored cases (Unicode `:191-197`, missing field `:116-148`, DOCTYPE entity refusal `:159-170`), which reduces but does not discharge the independent-adversarial obligation, those being module-owned rather than independent.

## The two measured figures

**`blockingGroups=20` vs a 10-entry size vector — unlabeled truncation; the required figure is honest.** `LocalPipelineSuite.scala:337` emits `.sorted.reverse.take(10)`, an explicit descending top-10, so the gap is truncation and not a lost group. The specification's required figure, largest observed blocking group, is honest: it comes from `observations.largestBlockingGroup` (`:335`), which is `groups.maxByOption(_.observedSize)` (`LocalPipelineRunner.scala:529,552`), and `LocalPipelineSuite.scala:256-259` independently asserts it equals `groups.map(_.observedSize).max`. Groups of size ≤ 1 are not emitted (`LocalPipelineRunner.scala:341`, `BlockingJob.scala:131`), which cannot suppress a maximum. Labeling only — finding F2.

**`totalRejects=1` — identified, intended, pinned; not silent data loss.** The reject is the cross-source title disagreement on DOI `10.14778/…` raised by the identifier-join stage. `LocalPipelineSuite.scala:423-434` asserts exactly one reject with `RejectReason.IdentifierConflict`, a `sourceRecordId` starting `doi|10.14778/`, a non-zero `ConflictRecords` counter and run-scoped provenance; emitted at `LocalPipelineRunner.scala:281-293`. Every other stage is asserted to reject nothing. The record is retained with its payload, not dropped — a reported conflict on a known fixture, which is what the specification asks for.

## Per-item gate verdicts

| Gate item | Verdict | Evidence |
|---|---|---|
| At least two real MapReduce jobs **execute** | **OPEN** — not the owner's; `build-engineer`. Classes are genuine MapReduce; none has executed | `build.sbt:112`; no test references `jobs/**` |
| DBLP parser handles record boundaries correctly | **PASS** — the strongest evidence in the module: `XmlRecordStreamSuite.scala:57-77` splits the document at **every byte offset** asserting exactly-once coverage; plus self-closing child `:84`, self-closing record `:93`, nested same-name `:99`, multi-byte `:107`, unterminated raises `:122` | |
| Pair generation on 1, 2, 3 and larger lists | **PASS** — n=1 `:92`, n=2 `:100`, n=3 `:114`, n=5 `:130`, n=6 `:142`, n=7 `:176`; formula pinned including 3 000 → 4 498 500 and `Long` non-overflow `:75-90` | `TeamExpansionSuite.scala` |
| Counters and reject outputs visible | **PASS** — full required inventory declared once (`pipeline/TaskSink.scala:12-25`), asserted present (`LocalPipelineSuite.scala:353-367`); versioned reject paths per stage (`RunContext.scala:101-102`, asserted `:207-211`); reject stream on every job (`JobSupport.scala:52-61`); payload bounded at 2 048 bytes | |
| Repeated runs produce equivalent output | **PASS** for pipeline logic — record, counter and partition-map equality (`LocalPipelineSuite.scala:142-170`), run-ID independence `:172-192`, content-derived SHA-256 partitioning with no seed (`PartitionKey.scala:16-23`). **Not established at the Hadoop layer** — see F4 | |
| Sample results contain provenance | **PASS** — every work carries source, snapshot, transform version, run ID and URI (`LocalPipelineSuite.scala:85-94`); mentions share work provenance; pairs and candidates carry it | |
| Test-engineer adversarial cases | **PENDING** — `test-engineer`, undispatched | `tests/` has no Phase 4 cases |
| Code reviewer returns APPROVE | **REQUEST CHANGES** | this verdict |

**Required operational behavior:** counters PASS; explicit partition strategy PASS (`StableKeyPartitioner.scala:7-34`, single definition shared with the local runner); idempotent paths with run ID and snapshot PASS as composed (`RunContext.scala:72-102`) subject to F4/F5; no mutable shared state across tasks PASS (per-task sinks in `setup`, closed in `cleanup`, `HadoopSink.scala:15-23`; `JobConfig` the only channel into a task); intermediate compression PASS but untested (`JobConfig.scala:89-96`); determinism PASS; unit tests for parsers, pair generation, normalization and key functions PASS; mini-cluster or local integration tests **PARTIAL** — the local integration run exists and is honestly labeled, the mini-cluster half is the open item; EMR smoke script **correctly not authored**.

**Seven required jobs:** all present, each with its documented key-value contract — 1 `DblpExtractionJob`; 2 `SourceNormalizationJob` with one normalizer per dataset (registry `SourceNormalizers.scala:537-544`); 3 `IdentifierJoinJob` across DOI, arXiv, ORCID, OpenAlex, Semantic Scholar, ROR, ISSN and DBLP key (`ExactIdentifiers.scala:20-32`); 4 `BlockingJob` with seven strategies and salt fan-out on the two non-selective ones (`Blocking.scala:33-40,162-169`); 5 `CandidateFeatureJob` emitting features and identifier agreement with explicitly no decision (`MatchCandidates.scala:6-19`, asserted `BlockingAndCandidateSuite.scala:236-248`); 6 `PublicationTeamJob`; 7 `GraphExportJob`. Chained in dependency order with a published job graph (`PipelineDriver.scala:25-35,95-142`).

**Authority: clean.** No file outside `modules/hadoop/**` was touched. The reviewer explicitly endorses the owner's handling of the `MatchCandidate` gap — declared module-locally, exchanged as versioned JSON, not a compile-time dependency for any other module, with promotion to core reported as a `domain-engineer` blocker rather than performed (`MatchCandidates.scala:14-19`).

## Findings

### F1 — A test that cannot fail

`modules/hadoop/src/test/scala/scholarchain/hadoop/BlockingAndCandidateSuite.scala:61-77`. The case is titled "the coauthor block key is the same from either side of the pair", but `left` and `right` are built from **identical** arguments — `Blocking.key(NameCoauthor, Researcher, "ley|m", "schenkel|r")` twice — so `assertEquals(left, right)` is a tautology. The property it claims to cover lives in the lexicographic ordering at `pipeline/Blocking.scala:219-227`, which is untested.

Fix: call `Blocking.researcherKeys` for each of the two mentions of a real pair and assert the `NameCoauthor` keys are equal.

The reviewer notes this is **the same class of defect the run-2 failure exposed** — a case that does not reach its subject.

### F2 — Unlabeled truncation in a required reported figure

`LocalPipelineSuite.scala:337`. `blockingGroupSizes` emits `.sorted.reverse.take(10)` under a name that reads as the full distribution; with 20 groups, half are silently absent. The largest group is honest, so this is a labeling defect, not a false figure.

Fix: rename to `blockingGroupSizesTop10`, or emit the count alongside, e.g. `blockingGroupSizes(top 10 of ${groups.size})`.

### F3 — Two incompatible definitions of `observedSize`, one duplicate-inflated

`pipeline/Blocking.scala:363` sets `observedSize = ordered.size` **after** `distinctBy(_.memberId)` (`:355-357`), a distinct count. Both reduce paths then **override** it with the selector's raw add count — `jobs/BlockingJob.scala:107-110` and `pipeline/LocalPipelineRunner.scala:320-322` — and `BoundedBlockSelector.add` increments `seen` *before* the duplicate check (`Blocking.scala:105-107`), so a repeated member ID counts twice.

Consequences: the reported largest observed blocking group and the `OversizeBlock` threshold comparison (`BlockingJob.scala:111`) both count duplicates, and `truncated` can be set true when nothing was truncated. The only test pinning this (`BlockingAndCandidateSuite.scala:130-136`, asserting `observedSize == 1` for `Vector(one, one)`) exercises `Blocking.bound` directly — the path the pipeline overrides — so it does not protect shipped behavior. The measured run is unaffected because the sample has no duplicates (`LocalPipelineSuite.scala:254` asserts no group truncated), but the reported figure's definition is ambiguous.

Fix: choose one definition, apply it in both reduce paths, and test the path the pipeline actually takes.

### F4 — An operational claim about idempotency that is likely false at runtime

`pipeline/RunContext.scala:68-72` and `:14` claim "a rerun with identical inputs land[s] on the identical prefix and overwrite[s] rather than accumulat[ing]"; the same claim appears in `jobs/PipelineDriver.scala:11-14`. Hadoop's `FileOutputFormat` **rejects** an existing output directory at submission (`FileAlreadyExistsException`); it does not overwrite. The path composition is correct and deterministic — which is the specification's actual requirement — but a rerun with the same run ID will most likely fail rather than overwrite.

Fix: state what the design gives (a deterministic prefix that is a pure function of run ID and snapshot digest) and state the rerun policy explicitly (delete-then-run, or a new run ID). Unverifiable without a runtime run, which reinforces the open MapReduce gate rather than substituting for it.

### F5 — An accessor that returns a path nothing writes

Source normalization runs once per dataset and its job output root is `stagePath(stage, dataset)` (`jobs/JobSupport.scala:46-51`, `dataset = Some(...)` from `SourceNormalizationJob.scala:181`), so `ArtifactSink` commits to `…/stage=source-normalization/dataset=<name>/works/v1/…` (`jobs/HadoopSink.scala:77-82`). But `RunContext.artifactPath(Artifact.NormalizedWorks)` returns `…/stage=source-normalization/works/v1` — no `dataset=` segment (`RunContext.scala:95-96`). `PipelineDriver` correctly avoids the accessor and uses its own `normalizedPath` (`PipelineDriver.scala:38-43`), but the accessor remains a trap for the Spark-side reader, and the test at `LocalPipelineSuite.scala:219-232` passes while asserting over paths never written for the five `Normalized*` artifacts.

Fix: make `artifactPath` require a dataset for stage-scoped-by-dataset artifacts, or fold `normalizedPath` into it.

### F6 — Reporting requirement: PARTIALLY SATISFIED

Judged against `validation.md` rather than the owner reports, as the work order directed. Delivered as executed observations: maximum team size and its work ID, largest pair expansion, emitted pairs, blocking groups, largest group and its key, total rejects, partition count and the full per-artifact partition spread.

Still undelivered anywhere in the evidence: the **job graph** (exists in code at `PipelineDriver.scala:25-35`, never stated in a report); the **per-job mapper and reducer key-value contracts** (present only as scaladoc headers); **per-stage counter values** (only the inventory's presence is asserted, plus two aggregates in the measured line); and the **reject and output path set** (only one example of each asserted in a test).

The reviewer does not treat this as a code defect and recommends it not block on the owner's third attempt: record it as a standing Phase 4 reporting deficiency and have the figures compiled into a report from the code and the measured line, with authorship routed per the ownership map.

### Non-blocking observations, no action requested

`MergeReducer` (`GraphExportJob.scala:181`) and `ConsolidateReducer` (`PublicationTeamJob.scala:112`) silently drop an unknown stream label, while `DeduplicateReducer` throws on one (`DblpExtractionJob.scala:142-145`); the stricter form is the better default. The Unicode normalization case the reviewer suspected of being vacuous is genuine: `IdentifierAndKeySuite.scala:137` holds precomposed `U+00E4` and `:138` the decomposed `U+0308` sequence, confirmed by byte-level search.

## Risks recorded by the reviewer

1. Fourteen files under `jobs/**` are compiled but never executed. The first mini-cluster run should be expected to find configuration-level defects, and F4 is a concrete candidate.
2. The only coverage of `DeduplicateReducer` and `NormalizeReducer` behavior is by inspection, since the local runner's job-1 and job-2 analogues are map-only.

## Routing required by the verdict

| Item | Owner | Status |
|---|---|---|
| F1–F5, all inside `modules/hadoop/**` | `hadoop-engineer` | Routed as repair 3 |
| Hadoop test runtime (`hadoop-client-runtime` or `hadoop-minicluster` on the Test classpath), plus the cheap check of whether `Provided` already permits unit-testing `JobConfig` and `StableKeyPartitioner` | `build-engineer` | Unrouted, awaiting the architect. Hard gate, critical path |
| `MatchCandidate` core contract | `domain-engineer` | Unrouted, awaiting the architect. Also Phase 5 |
| Adversarial cases under `tests/` | `test-engineer` | Unrouted, awaiting the architect. Gate item |
| F6 reporting deficiency | Orchestrator to record; authorship per the ownership map | Recorded here |

The reviewer requires that the owner run `sbt check` after the fix and report the result, and that the marker be treated as stale once any source is edited. Per project rule, an agent does not run it; the human does.

## Next gate, as the reviewer states it

Owner repairs F1–F5 → human `sbt clean check` → re-review of the changed files → the two routed blockers → human ratification. The reviewer does not approve its own findings; this verdict is recorded with its attribution and is itself subject to independent review.
