# Wave 2 checks (orchestrator, 2026-11-17)

| Check | Result | Source |
|---|---|---|
| storage-engineer `sbt quick`, run 1 | scalafmt and compile passed for storage, rag and services; one `EmbeddingWorkerSuite` test failed (`munit.intercept` does not catch a `ControlThrowable`); test-only fix (`crashes(...)` helper) | WO-7 report |
| storage-engineer `sbt quick`, run 2 | `[success] Total time: 117 s` (9:53:47 PM); last group lines `Passed: Total 73 ... Passed 73` and `Passed: Total 1012 ... Passed 1012`; per-group totals not read (660 KB output) | WO-7 report |
| New suites, from `target/test-reports` XML of that run (timestamps 2026-11-16T21:51:52-53 local) | `EmbeddingWorkerSuite` 42/0/0; `RetrievalModeSuite` 12/0/0; `BedrockAnswerModelSuite` 22/0/0; `AwsRuntimeSuite` 18/0/0 (service-engineer reported 19 written); `AwsSettingsSuite` 10/0/0; `RetrievalModeRequestSuite` 7/0/0 (tests/failures/errors) | read by orchestrator |

| storage-engineer `sbt quick` (WO-7b, end) | `[success] Total time: 123 s`; groups 73/0/0 and 1012/0/0; `AwsNeptuneQuerySuite` 19, `AwsNeptuneQueryHttpSuite` 7, `AwsReadModelsSuite` 20, all 0 failures | WO-7b report |
| service-engineer `sbt quick` (WO-8b) | `[success] Total time: 118 s`; groups 73 and 1012 (agent could not explain unchanged totals); `AwsLiveReadModelsSuite` 10, `AwsRuntimeSuite` 18, `AwsSettingsSuite` 10, `RetrievalModeRequestSuite` 7, all 0 failures | WO-8b report |
| test-engineer `sbt quick` (WO-12b) | `[success] Total time: 114 s` (2026-11-16 22:48:23 local); groups 73/0/0 and 1041/0/0; `P15EmbeddingMessageCrossModuleSuite` 29/0/0/0 (producer ran, all 28 bodies) | WO-12b report |
| Human `rm` of `AwsSqsWorkQueue.scala.pending` (human-commands.md section 1) | done, no output (2026-11-17) | human `!` run |

| WO-11 load-client rehearsal | 30 PASS, then crash at the connection-refused case: `FileNotFoundError ... refused-conn/requests.jsonl` (`rehearse.py:187`), exit 1; routed to experiment-engineer after R12 (Wave 1 priority) | `phase15-w2-rehearse-load-client.log` |
| WO-11 load-client rehearsal, rerun after the fix | `REHEARSAL pass=45 fail=0`, exit 0 (expected 45) | `phase15-w2-rehearse-load-client-2.log` |
| WO-11 C rehearsal | `REHEARSAL pass=35 fail=0`, exit 0 (expected 35) | `phase15-w2-rehearse-c.log` |
| WO-11 F rehearsal | `REHEARSAL pass=8 fail=0`, exit 0 (expected 8) | `phase15-w2-rehearse-f.log` |
| WO-11 shell rehearsal | `REHEARSAL pass=24 fail=0`, exit 0 (expected 24) | `phase15-w2-rehearse-shell.log` |

Consequence: the single `sbt quick` covered rag and services as well; no separate rag or services sbt slot is needed for this state of the sources. `AwsSqsWorkQueue.scala.pending` still exists (human removes it). Formatting of rag and services is verified by that run's scalafmt step.
