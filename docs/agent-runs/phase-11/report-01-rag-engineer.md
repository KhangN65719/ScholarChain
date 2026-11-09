# Report 01: rag-engineer (work order 01, m5 and n1), as received

STATUS: PARTIAL. Both repairs are done in modules/rag and every rag test passes. `sbt quick` exits 1 because 16 root tests under tests/ still encode the old behavior. Those are reported BLOCKED-ON test-engineer below. No marker was written, since the run failed.

Owner: rag-engineer. Work order: docs/agent-runs/phase-11/work-order-01-rag-engineer.md (m5, n1).

## Pre-code test mapping
All tests are in modules/rag/src/test/scala/scholarchain/rag/Phase11ClaimIdSuite.scala unless noted.
- m5, hostile or colliding IDs give system IDs only: "m5: hostile or colliding model claim IDs yield system IDs only". Same ID twice, an injection canary, an empty ID and a 10 000-char ID through the full workflow. Every ID matches `claim-N-kind-12hex`, IDs are unique, no canary appears in the response, the audit or the verifier report.
- m5, replays identical and independent of model text: "m5: identical replays give identical IDs, whatever the model wrote".
- m5, ID shape: "m5: the ID follows the position, kind and structure of the claim".
- m5, schema: "m5: the synthesis schema accepts a missing or any model claimId".
- n1, abstention-only answer: "n1: an abstention-only synthesis output is abstained with a reason" (abstained = true, reason `claims-unsupported`, audit shows both, fixed answer sentence, abstention-claim model text not echoed).
- n1, verifier level: "n1: an abstention-only output at the verifier is abstained".
- n1, regression: "n1: an abstention beside a kept claim does not abstain the answer".
- n1, no number echo: "n1: an unsupported number in model text is not echoed anywhere" ("98765" and "seventy-seven" absent from audit and response; the weakened claim's `uncertainty` says "2 number(s)").

## Design decisions
- m5: `ClaimDraft.fromJson(value, position)` in `Claims.scala` assigns the ID through `ClaimDraft.systemId`: `claim-<1-based position>-<kind>-<first 12 hex of sha256(kind, evidenceIds, assertion JSON)>`. Position makes IDs unique and replays identical. Model `claimId`, `text` and `uncertainty` do not affect it. The hash covers only the structured assertion and evidence IDs, hashed and never echoed. `Verification.scala` needed no change for m5.
- `claimId` in the schema is now optional and an unbounded string, and the model's value is ignored (so an older model that still sends it is not rejected).
- `checks()` in `Synthesis.scala`: removed the "claimId values repeat" retry, since colliding IDs are harmless.
- n1: `Verification.scala` counts `substantive` (kept or weakened non-abstention claims). `abstained = substantive == 0`. `Workflow.scala` already maps `fin.abstained` to the system reason code `claims-unsupported`, so it is unchanged.
- Number echo: the weakened-claim reason is now "N number(s) in the claim wording are not in the asserted value or any cited deterministic fact". The unknown-kind reason is "unknown claim kind", with no echo.
- Prompt unchanged: synthesis 1.1.0, hash 40c6597d...ba7c7. `prompts/` not touched.

## Versions and hashes
- Schema `scholarchain.rag.synthesis-output/2` bumped to `/3`, hash `3ff03e974a310c6399465499474c70c1242bed7520e9db8f45b42129bbb89226`. Old /2 hash f510dce6...046b9.
- New hash pinned in ModelPortSuite. The literal was taken from the first failing run's output, so it is not independently computed.
- Planner prompt, planner schema and verifier-report schema unchanged.

## Wire changes visible to services
- Claim IDs in `AnswerClaim.claimId`, the verifier report and the audit are now `claim-N-kind-12hex` (were `claim-N` from the stub, or model text).
- An abstention-only answer now has `abstained = true`, `abstentionReason = "claims-unsupported"`, and the fixed answer text. The abstention claim stays in `claims` with status Uncertain.
- The synthesis schema ID is /3, in audit `schemaHashes` and `prompts`.

## Changed expectations in existing tests (all asserted the defect itself)
1. VerificationSuite.scala ~170 ("a number in the text that no cited fact holds weakens the claim"). Before: `kept.uncertainty.exists(_.contains("99"))`. After: `!kept.uncertainty.exists(_.contains("99"))` and `kept.uncertainty.exists(_.contains("1 number(s)"))`.
2. RepairRound1Suite.scala ~267 ("B1d: a model's uncertainty text is not copied to a claim"). Before: `uncertainty.contains("99")`. After: `!uncertainty.contains("99")` and `uncertainty.contains("1 number(s)")`.
3. WorkflowSuite.scala ~567 ("a steered model cannot get a wrong, snippet-backed or invented claim through"). Before: verdicts looked up by model-written IDs `evil-1..3`. After: the three appended claims found as `report.claims.takeRight(3)`, none of their IDs starts with "evil", same verdicts (Conflicting, Unsupported, Unsupported), same F1 reason, "removed" changes and audit entry.
4. ModelPortSuite.scala "m2: literal hashes are pinned": schema key and hash moved from `/2` f510dce6... to `/3` 3ff03e97.... Version bump, not a weakening.
No test deleted or weakened otherwise.

## Commands and results
- `sbt quick` three times. Run 1 found the failures. Runs 2 and 3 exit 1 (stdout truncated; counts from `target/test-reports` XML). Run 3 is the final state.
- rag suites (failures 0, errors 0): Phase11ClaimIdSuite 8, ModelPortSuite 14, VerificationSuite 26, WorkflowSuite 32, RepairRound1Suite 26, RetrievalAgentsSuite 23, EvidenceBuilderSuite 12, PlannerSuite 17, ResolverSuite 10, SemanticContextSuite 15, EvaluationSuite 6, RuntimeDemoSuite 2. Other rag suites not read, unchanged.
- Root suites passing: Phase9GateSuite 20, Phase9BenchmarkSuite 65, Phase8RagSuite 32, Phase10EndToEndSuite 5, Phase10RepairSuite 19, Phase10SecuritySuite 14, Phase10WireSuite 10, Phase10ApiContractSuite 24, Phase10CourseSuite 24.
- Root suites failing: Phase9ModelSuite 27 run, 10 failed. Phase9RepairSuite 24 run, 6 failed. Other root suites not individually read.
- `sbt check` not run (not requested).

## BLOCKED-ON test-engineer (root tests that encode the old behavior; not edited)
New expected behavior: claim IDs are system-assigned (`claim-N-kind-12hex`); find a claim by position or kind, not by a model-written ID. Schema `synthesis-output/3`, hash `3ff03e974a310c6399465499474c70c1242bed7520e9db8f45b42129bbb89226`.
- tests/adversarial/Phase9ModelSuite.scala: lines 86 (x-f1a), 117 (x-f1b), 148 (x-f1c), 210 (x-path, x-comp), 251, 281, 316 (x-nocite, x-ghost), 390, 466 (x-bogus), 538 (x-bogus). `verdictOf` and `changeOf` by model ID return None.
- tests/adversarial/Phase9RepairSuite.scala: lines 90 (b1a), 110 (b1b0), 178 (b1c) `recordedInAudit` by model ID; 306 verdict by model ID; 589 and 616 pinned schema map has `/2` f510dce6..., must be `/3` 3ff03e97....

## Assumptions and risks
- No grep or find available in this role. Affected tests were located from XML reports and reading, so a root test referencing model IDs that did not fail this run would not show up.
- The ID hash includes the model-supplied assertion, hashed only.
- The stub model still emits `claimId: "claim-N"`. The runtime ignores it.
- Not repaired, out of scope: m6, m7, m8.

## Files modified
modules/rag/src/main/scala/scholarchain/rag/{Claims,Synthesis,Model,Verification}.scala; modules/rag/src/test/scala/scholarchain/rag/{VerificationSuite,RepairRound1Suite,WorkflowSuite,ModelPortSuite}.scala

## Files created
modules/rag/src/test/scala/scholarchain/rag/Phase11ClaimIdSuite.scala
