# Phase 4 reviewer work order

Owner: `code-reviewer`. Dispatched by the orchestrator 2026-11-06, after validation run 3 passed.

## Authority

Read-only. No repository write, no shell execution, no self-approval. Return `APPROVE` or `REQUEST CHANGES` with exact file locations and evidence. Where a check is missing, ask the owner to run it and report it as pending rather than running it.

## Review target

All Phase 4 work in `modules/hadoop/**`: 28 main sources and 8 test sources.

### Diff note

`git diff` and `git diff --stat` are **empty** for this review, and that is not an error. Every Phase 4 file is new and untracked at the time of dispatch, so there is no tracked-file delta to show. The orchestrator produced the inventory below with `git status --short --untracked-files=all`. The reviewer must read the files directly.

```
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/BlockingJob.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/CandidateFeatureJob.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/DblpExtractionJob.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/DblpXmlInputFormat.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/GraphExportJob.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/HadoopSink.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/IdentifierJoinJob.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/JobConfig.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/JobSupport.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/PipelineDriver.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/PipelineOptions.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/PublicationTeamJob.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/SourceNormalizationJob.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/StableKeyPartitioner.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/Blocking.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/DblpParser.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/ExactIdentifiers.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/IdentifierJoin.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/LocalPipelineRunner.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/MatchCandidates.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/PartitionKey.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/RunContext.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/SourceNormalizers.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/TaskSink.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/TeamExpansion.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/TextNormalizer.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/XmlFragment.scala
?? modules/hadoop/src/main/scala/scholarchain/hadoop/pipeline/XmlRecordStream.scala
?? modules/hadoop/src/test/scala/scholarchain/hadoop/BlockingAndCandidateSuite.scala
?? modules/hadoop/src/test/scala/scholarchain/hadoop/DblpParserSuite.scala
?? modules/hadoop/src/test/scala/scholarchain/hadoop/IdentifierAndKeySuite.scala
?? modules/hadoop/src/test/scala/scholarchain/hadoop/LocalPipelineSuite.scala
?? modules/hadoop/src/test/scala/scholarchain/hadoop/SourceNormalizerSuite.scala
?? modules/hadoop/src/test/scala/scholarchain/hadoop/TeamExpansionSuite.scala
?? modules/hadoop/src/test/scala/scholarchain/hadoop/TestRun.scala
?? modules/hadoop/src/test/scala/scholarchain/hadoop/XmlRecordStreamSuite.scala
```

Baseline for comparison: commit `74543bb`. `modules/hadoop/src/{main,test}/scala` were empty at that commit, so all of this is new.

## Review against the gate

Review against the full Phase 4 gate in [prompt.md](prompt.md), which is the course specification archived verbatim. Reach a per-item verdict on each of its eight gate items, and on the required operational behavior and the seven required jobs.

Two gate items are already known open and are **not** the owner's to close. Confirm or correct the orchestrator's reading of them; do not treat them as reasons to withhold judgment on the rest:

- *Two real MapReduce jobs execute.* The owner states no job has executed on a Hadoop runtime or mini-cluster; all passing suites are in-process. Assess whether the committed job classes are genuine MapReduce implementations or, in the specification's words, "local collection operations disguised as MapReduce." This is a code question you can answer by reading `jobs/**` even though nothing has been executed.
- *`test-engineer` adversarial cases.* **Pending.** Not dispatched, and `tests/` holds no Phase 4 cases. Record as pending.

## Specific question

Determine whether the `SourceNormalizerSuite` repair weakened the test. This is the orchestrator's main concern and must be answered explicitly.

History: validation run 2 failed one case, `no abstract text is copied, only a reference`, at `SourceNormalizerSuite.scala:245`, with `normalization failed` — normalization returned a `Left` before the test reached its subject, so the abstract requirement was never actually exercised. The owner reports fixing a malformed Crossref DOI fixture (corrected to `10.1000/abc`) rather than the normalizer, splitting the abstract assertion across both sources, and adding a case. Run 3 shows 17 passing cases in that suite, up from 14, with these three present:

- `no Crossref abstract text is copied, only a reference`
- `an OpenAlex inverted abstract yields a reference and no text`
- `a DOI prefix shorter than four digits is not a DOI`

Check specifically:

1. That the abstract assertions genuinely verify no abstract text is copied and only a reference is retained, for both Crossref and OpenAlex, and are at least as strict as the original single case.
2. That the cases reach their subject — that a passing result reflects verified behavior rather than normalization short-circuiting or an assertion that cannot fail.
3. That the fixture correction was the right side to fix. Confirm the original fixture DOI was genuinely invalid and the normalizer was right to reject it, rather than the fixture being bent to match a lenient normalizer.
4. That no case was deleted, loosened, or made tolerant of a `Left` return.
5. Whether the repair-1 change to the OpenAlex `abstractTextRef` path contributed to the run-2 rejection. The owner was asked directly and did not answer.

## Evidence

- [Phase 4 specification, verbatim](prompt.md) — the gate
- [Work order](hadoop-work-order.md) — scope and the orchestrator's constraints
- [Owner report](owner-report.md) — as received; note its preservation caveat
- [Repairs](repairs.md) — repairs 1 and 2 as received
- [Validation](validation.md) — runs 1 and 2 failed, run 3 passed with 118 tests; measured figures and their consistency checks

Verification status: `sbt check` passed in run 3, executed by the human operator at 02:23 on 2026-11-06, 118 of 118 tests. The marker is current. No agent ran it; that is the project's accepted workflow, not a gap for you to flag.

## Two measured figures to scrutinize

From the `[phase-4 measured]` line recorded in [validation.md](validation.md):

1. `blockingGroups=20` while `blockingGroupSizes` lists only 10 entries, each exactly 6. The partition spread independently confirms 20 groups. Assess whether this is an unlabeled truncation and whether the specification's "largest observed blocking group" is honestly reported.
2. `totalRejects=1` in a run where `the DBLP stage accepts both pinned records and rejects none` passes, so the reject originates elsewhere. Determine which stage rejects it and whether that is intended behavior on a known fixture or silent data loss.

## Required reporting deficiency

The work order's "Required reporting" section was requested three times and never delivered in the owner's reports; only closing summaries reached the orchestrator. The figures were eventually obtained from test output in run 3 and are recorded in [validation.md](validation.md). Note in your verdict whether the delivered evidence is sufficient for the specification's reporting requirement, treating the validation record rather than the owner reports as the source.

## Verdict

Return `APPROVE` or `REQUEST CHANGES` to the orchestrator with exact file locations and evidence. Do not edit any file, do not run any command, and do not approve your own findings. The orchestrator records the verdict with your attribution.
