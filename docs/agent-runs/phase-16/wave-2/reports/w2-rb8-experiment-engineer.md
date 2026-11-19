# W2-RB8: experiment-engineer report (review 10 findings 5, 6, 8)

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Bare `sbt quick`: `[success] Total time: 152 s (0:02:32.0), completed Nov 18, 2026, 11:01:14 PM`; `Passed: Total 77, Failed 0, Errors 0` and `Passed: Total 1304, Failed 0, Errors 0`. JUnit: `RunIdentitySuite` 5, `AnswerEvalSuite` 38, `RetrievalEvalSuite` 16, 0 failures, 0 errors. An earlier run failed on the agent's own test (a 1000-call guard the diagnostic refuses), fixed. No live run, AWS or commit.

## Tests (new `experiments/evaluation/src/test/scala/scholarchain/evaluation/answer/RunIdentitySuite.scala`)
- Live versions and hashes: "the identity holds each live prompt's version, hash and schema hash" (independent `MessageDigest`, synthesis 1.2.0, schema hash, 4096, 12).
- Changes change the record: "a changed version, text, schema hash or bound changes the record".
- From the run, not hardcoded: "an AnswerEval run manifest carries the live identity, bound included" (`ragIdentity` equals `RagIdentity.json(plannerMaxLimit = 7)` with a `WorkflowConfig` limit of 7).
- Diagnostic and per-answer prompts: "a diagnostic manifest and the per-answer records carry the prompts".
- RetrievalEval: "a RetrievalEval run manifest carries the live identity".
- README arithmetic: no test (measured with a throwaway test, removed; `CostGuardSuite:152` asserts the 0.15 bound). Notes: docs only.

## Changes
- New `experiments/evaluation/src/main/scala/scholarchain/evaluation/runtime/RagIdentity.scala` (reads `Prompts.all`, `Schemas.hashes`, `AnswerSynthesisAgent.MaxOutputTokens`, planner bound at run time; parameters default to live values).
- `AnswerEval.scala`: `"ragIdentity"` in the run manifest before `"wiring"` (~355-357); diagnostic line gets `"prompts"`.
- `AnswerRecords.scala`: new `PromptRec` with `ofAudit`; `AnswerRecord` trailing `prompts: Vector[PromptRec] = Vector.empty` (written, read, filled from `audit.payload("prompts")`).
- `RetrievalEval.scala`: `ragIdentity` in the manifest.
- `experiments/h-answers/README.md`: worst-case paragraph rewritten; `terraform output` calls carry `AWS_PROFILE=scholarchain` plus an empty-value guard; rerun paragraph uses `--run-id h4-diagnostic-4`; new "Run identity" and "H cost if Haiku ignores the ranking claim cap" sections.
- `experiments/g-retrieval/README.md`: one `ragIdentity` bullet.
- `experiments/manifests/experiment-h-answers.manifest`: comment lines only (G manifest and both JSON copies untouched).

## Arithmetic
Synthesis 1.2.0 prompt 1530 characters (949 at 1.1.0), schema 9104, full 12000-token input 48002 as JSON: (48002 + 1530 + 9104 + 400 + 3) / 4 = 14759 input tokens; per call 0.014759 + 0.02048 = 0.035239 USD; four calls 0.140956 vs 0.15 (headroom about 0.009; was 0.1404). Finding 8: 10 ranking × 2 extra calls × 0.0275 ≈ 0.55 → 2.09 + 0.55 ≈ 2.64 < 3.00; `synthesis-failed` answers abstain and the repeat pass takes only answered questions. H-4 rerun: Q-RK-01 can use 3 calls, leaving 1 for Q-RK-02 (a retry there halts on `haiku-call-cap`); spend stays under 0.15.

## Unverified
Evaluation JUnit total not summed; whether the harness pins the H manifest digest (comments changed its sha256; no approval record exists); Haiku compliance with rule 6; paste-ready commands not run. Raised: `human-commands.md` pending H-4 entry still shows `h4-diagnostic-3` (orchestrator's file).
