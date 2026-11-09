# Report 03: test-engineer (work order 03, adversarial pass), as received (lightly condensed where noted)

STATUS: PARTIAL. Parts A, B and C are done and executed. One new test fails because of a production defect (D1). Last `sbt quick` exit code is 1.

Owner: test-engineer. Work order: docs/agent-runs/phase-11/work-order-03-test-engineer-adversarial.md.

## Part A: root tests repaired (rag m5 and n1)
Phase9ModelSuite 27 run, 0 failed (was 10 failed). Phase9RepairSuite 25 run, 0 failed (was 6 failed of 24; 1 test added).

Helpers: `added(r, n)` takes the last n claims of `r.verification.claims`. `assertSystemId` checks regex `claim-[1-9][0-9]*-[a-z-]+-[0-9a-f]{12}`, that the ID contains `-<kind>-`, holds no model text, and `c.kind` matches. `assertNoModelId` checks the model ID is absent from the verification JSON, audit, claims JSON and answer.

Phase9ModelSuite (lookup key only changed unless marked): x-f1a, x-f1b, x-f1c, x-path/x-comp (plus check that the two IDs differ), x-conflict, x-nocite/x-ghost, x-who/x-window, x-beyond/x-legacy, three injection tests with x-bogus (plus exactly one claim more than the clean run). x-f1d: `Option` compare became `x.verdict` (stronger). EXPECTATION CHANGES: x-f1b `contains("99")` became `!contains("99")` plus `contains("1 number(s)")`; x-partial `contains("17")` became `!contains("17")` plus `contains("1 number(s)")`. Both because the rag number-echo fix removed the defect they asserted.

Phase9RepairSuite: B1a, B1b (3 loop cases), B1b' metric, B1c, M1 (4 cases) and M1 honest now look up by `added(r)`; kept-claim assertions used `filter(_.claimId == modelId)` and would have passed vacuously, now use `keptFor`; `recordedInAudit(r, x.claimId)` added to B1b. Schema pin `/2` f510dce6... became `/3` 3ff03e974a310c6399465499474c70c1242bed7520e9db8f45b42129bbb89226. New test "m2 (Phase 11): the pinned literals equal an independent sha256" recomputes sha256 with `java.security.MessageDigest` over schema and prompt texts. No test deleted or weakened.

## Part B
- Phase10WireSuite.scala:56: `"sharesOrcidWith":null` changed to `0`. `GraphViews.node` emits `c.sharesOrcidWith`, an `Int`. Matcher still checks types; live assertion added that the field is a number >= 0. 10 run, 0 failed.
- HTTP regressions use new rig `Phase11Rig.scala` (real slow service, Routes.slow, Router, ServiceServer over loopback with a local fake workflow; LocalRuntime has no fatal-fault mode).
- Phase11CarryOverSuite: fatal Throwable frees the key and replay returns 200 (workflow ran twice); different body under freed key still 422; async job dying fatally fails and `/result` is 409 JOB_FAILED; "top 1000 coauthors" with a window is 200 (also "first 2000", "1000 papers"); count beside a matching in-question window 200; true conflicts 422 UNSUPPORTED_CONSTRAINT (four cases); m5 hostile and colliding IDs never reach the response; n1 abstention-only is `abstained=true`, reason `claims-unsupported`, no model text or "98765"; n1 abstention beside a kept claim does not abstain.

## Part C
Matrix: tests/ADVERSARIAL_COVERAGE.md, all 21 categories, each existing-test mapping marked READ or NAME (seven rest on suite names only), residual gaps named.
New files under tests/: adversarial/Phase11Rig.scala (helper, 0 tests), Phase11CarryOverSuite.scala, Phase11FaultSuite.scala, Phase11XmlTruncationSuite.scala, Phase11ErSuite.scala, Phase11ConfigSuite.scala, ADVERSARIAL_COVERAGE.md.
Modified: Phase9ModelSuite.scala, Phase9RepairSuite.scala, contracts/Phase10WireSuite.scala. No production file, module test, build.sbt or .claude/** touched.

## Counts (JUnit XML, final `sbt quick`, 00:24)
| Suite | Tests | Failures | Errors |
|---|---|---|---|
| Phase11CarryOverSuite | 9 | 0 | 0 |
| Phase11FaultSuite | 12 | 1 | 0 |
| Phase11XmlTruncationSuite | 7 | 0 | 0 |
| Phase11ErSuite | 6 | 0 | 0 |
| Phase11ConfigSuite | 9 | 0 | 0 |
| Phase9ModelSuite | 27 | 0 | 0 |
| Phase9RepairSuite | 25 | 0 | 0 |
| Phase10WireSuite | 10 | 0 | 0 |

New Phase 11 tests: 43 run, 42 pass, 1 fails. Whole-run total NOT aggregated (shell guard blocked a tally script; stdout truncated). Exit code 1 is consistent with the single D1 failure, but not every suite header was read.

## Failing test (production defect, left failing)
D1. "S3 Vectors throttling leaves the deterministic answer intact", tests/adversarial/Phase11FaultSuite.scala:213.
- Input: POST /v1/query "How many distinct coauthors does Ada Quillfeather have in 2000-2009?" with the vector bucket's `query` throwing `TransientStorageException` (what VectorBucketPort documents for throttling).
- Observed: 503 `DOWNSTREAM_UNAVAILABLE`. Expected: 200, answered, verified metric claims.
- Cause: `SemanticContextAgent.context` in modules/rag/.../Retrieval.scala (~257-262) catches only `StorageException`, though its comment says a failed read is skipped because semantic context is optional. `TransientStorageException` is a plain RuntimeException and escapes `answer`.
- Impact: a throttled S3 Vectors query kills an answer that does not need it. A retry-exhausted `StorageException` degrades correctly (sibling test passes).
- Owner: rag-engineer (the catch). Alternative: storage-engineer, if the throttle should be wrapped as `RetryExhausted` at the port. Orchestrator to rule.

## Test-can-fail statements (summary)
- Part A repairs: removing system-ID assignment fails `assertSystemId`/`assertNoModelId`; reverting the number-echo fix fails the "1 number(s)" assertions; reverting the schema bump fails the pinned `/3` and the independent sha test.
- CarryOverSuite: removing the `case t: Throwable` release leaves the replay stuck; removing the job fatal branch leaves the job running (awaitJob times out); reverting `QueryRequests.years` returns 422 for "top 1000 coauthors"; reverting n1 `substantive == 0` or m5 fails their tests.
- FaultSuite: Neptune exception text leaking fails `assertNoLeak`; audit-write conflict returning an answer fails `assertTyped`; removing the idempotency claim gives `workflowRuns` 5 or 6; removing the `embeddingModel` check in `SemanticRetrieval.search` shows the stale key; the injection test asserts the injection reached the model and the snippet was retrieved; a secret in a log line fails redaction.
- XmlTruncationSuite: any prefix that throws or parses as a publication fails; fixtures contain mid-entity, mid-tag, mid-attribute and mid-multibyte prefixes.
- ErSuite: accepting a homoglyph pair fails; the NFC/NFD control proves the evidence can merge a legitimate pair; reordering must not change decision, confidence or reason codes.
- ConfigSuite: untyped exceptions or echoed secrets fail; at least 20 keys must refuse a bad word.

## Own errors corrected
Multibyte fixture had no multibyte character (now literal UTF-8). Injection test first asserted the canary absent from the whole body, but the retrieved snippet is shown by design; it now checks answer text and claims and requires any claim citing the snippet to use system SemanticText wording.

## Commands
`sbt quick` several times, last exit 1 (D1 only, as far as read). `sbt Test/compile` once, exit 0. `sbt check` not run per the order.

## Pending
Spark integration suites (tests/integration) not added or changed, pending until `sbt check`. AWS integration tests not run per scope. Coverage mapping for several existing suites rests on names (NAME in the matrix).

## Residual risks
- Neptune timeout: no storage `ErrorCode` for timeouts. A real timeout escapes as a generic exception and is 503. The test accepts 500, 503, 504 or a 200 abstention.
- Comment and CDATA records read as live in XmlRecordStream remain pinned open from earlier phases (hadoop-engineer).
- Blocking-group memory not measured (largest block built is a few hundred).
- Spark, storage and rag configuration omissions have no independent test.
- Phase11Rig builds its own service wiring and uses only public classes; signature changes in LocalRuntime or Routes would break its compile.
