# Phase 16 Wave 2 review 2 verdict (re-review of W2-RB1 and W2-T)

Author: code-reviewer. Received by the orchestrator on 2026-11-18 and recorded with its attribution. Read/Grep/Glob only; current files read.

**Verdict: REQUEST CHANGES.** 1 MINOR, 5 LOW. The repaired code is correct as read.

## Findings

1. **MINOR** `experiments/evaluation/src/test/scala/scholarchain/evaluation/answer/AnswerEvalSuite.scala:456-491`, `.../retrieval/RetrievalEvalSuite.scala:249-270`. The "kill" throws `InterruptedException` out of the loop, through `finally raw.close()` (`AnswerEval.scala:272`, `RetrievalEval.scala:373`), which flushes buffered bytes; a buffering appender would pass. The code is correct (`RawAppender`, `io/ExportIo.scala:170-187`, flushes per append). Required: in the `dying` callback, read `answers.jsonl`/`records.jsonl` before throwing and assert the finished lines are there (3 for H), or a `RawAppender` test reading back after `append` and before `close`. Owner: experiment-engineer.
2. **LOW** `RetrievalEval.scala:360-369`: when the direct search is skipped because `stopNow()` is true (wall-clock halt, or a USD stop set by a charge with nothing refused), vector records get `costStopped = halt.nonEmpty`, contradicting L3. Set the `directNote` only, or tie to a rise in `refusedCount`. Owner: experiment-engineer.
3. **LOW** `GraphChecks.scala:697-707`: unknown options accepted silently (a misspelt `--expect-edge` prints `expectedEdges=n/a`). Refuse by name. Owner: experiment-engineer.
4. **LOW (optional)** `GraphChecksSuite.scala:274-307`: the composition test derives its expectation from the same rule; suggest comparing `composedEdges`/`composedVertices` with `SparkGraphProjector(...).project()` over the same data. Owner: experiment-engineer.
5. **LOW** `experiments/README.md:96` "Repeat for the other five manifests" (now seven); cost table `:69-77` lacks G and H rows, Sum 8.05 omits 0.25 and 2.00. Owner: experiment-engineer.
6. **LOW (W2-T)** `tests/adversarial/P16W2CostGuardSuite.scala:471-490`: upper bounds only (`<= 4`), passes if nothing ran; gate still proven at `:492-499`. Add `spy.calls >= 1`. Owner: test-engineer.

## Review-1 findings

- M1 RESOLVED in code (absolute deadline from `run.sh` start with 10-minute margin, `h-answers/run.sh:63-78`, `g-retrieval/run.sh:45-60`, `RunDeadline.scala`; margin covers 6 Haiku calls at 40 s, `BedrockAnswerModel.scala:233`, plus 2 Titan embeds at 60 s with no retry, `LaptopPorts.scala:198-204`; per-answer/per-group flush; raw hashed before summary, `AnswerEval.scala:283`, `RetrievalEval.scala:383`; nothing still expects `--max-minutes`). Test weak (F1).
- M2 RESOLVED (`AnswerEval.scala:527-542`; refused before the run directory, `:141-145`; README `:24` cites P16-28).
- M3 RESOLVED (460 in manifest `:22-27`, `:66`, JSON `:15`, `run.sh` `:55`, `:75`, README `:15`; tier S 1.20 < 2.00; `tests/scripts/run.sh` pins no G/H values).
- M4 RESOLVED (W2-T 174 tests, 0 failures, written from the specification at spy services; F6 the one weak bound).
- m1, m2, m3 RESOLVED. m4 RESOLVED, human run pending (`human-commands.md:45-53` before the fill; `grep -A2` fits the manifest layout; the three files do not record the commit; a false MATCH needs both sides to fail). m5 RESOLVED per the report and `er-README.md` (`ErReference.scala` not read). m6 RESOLVED (P16-31).
- L1, L2 RESOLVED. L3 RESOLVED for H (`AnswerEval.scala:211`), not G (F2). L4 RESOLVED (`BenchmarkFill.scala:185-201`, `:534-540`). L5 RESOLVED (`:460-468`). L6 PARTIAL (F5). L7 RESOLVED (`FactoryCounts.scala:813-836`, `BenchmarkGen.scala:1463-1474`).
- OD-3 RESOLVED in code: composition matches P16-21 and the projector (RESOLVES_TO `Neptune.scala:325-327`, DERIVED_FROM `:401-440`; vertices 23,409 + 8,815 + 1 = 32,225); defaults removed, `--expect-source` enforced (`GraphChecks.scala:720-731`); `P16GraphChecksSuite` uses only `run(dir, seed, sample)` and counters.

## Not performed

Any sbt or harness run; `bash -n`/shellcheck on both `run.sh`; `check-manifest-json.sh`; the human's m4, GraphChecks `-2` (including whether `provenance.snapshotId` exists on the real export) and ErExtras commands; full read of `P16W2{AnswerAudit,RetrievalScoring,ErExtras,TextOnly}Suite` and `ErReference.scala`; diff against 51814c3.

Next gate: one repair round (experiment-engineer F1-F5; test-engineer F6), re-review of changed items, agent `sbt quick`, human harness and pending commands.
