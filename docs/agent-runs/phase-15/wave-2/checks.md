# Wave 2 checks (orchestrator, 2026-11-17)

| Check | Result | Source |
|---|---|---|
| storage-engineer `sbt quick`, run 1 | scalafmt and compile passed for storage, rag and services; one `EmbeddingWorkerSuite` test failed (`munit.intercept` does not catch a `ControlThrowable`); test-only fix (`crashes(...)` helper) | WO-7 report |
| storage-engineer `sbt quick`, run 2 | `[success] Total time: 117 s` (9:53:47 PM); last group lines `Passed: Total 73 ... Passed 73` and `Passed: Total 1012 ... Passed 1012`; per-group totals not read (660 KB output) | WO-7 report |
| New suites, from `target/test-reports` XML of that run (timestamps 2026-11-16T21:51:52-53 local) | `EmbeddingWorkerSuite` 42/0/0; `RetrievalModeSuite` 12/0/0; `BedrockAnswerModelSuite` 22/0/0; `AwsRuntimeSuite` 18/0/0 (service-engineer reported 19 written); `AwsSettingsSuite` 10/0/0; `RetrievalModeRequestSuite` 7/0/0 (tests/failures/errors) | read by orchestrator |

| storage-engineer `sbt quick` (WO-7b, end) | `[success] Total time: 123 s`; groups 73/0/0 and 1012/0/0; `AwsNeptuneQuerySuite` 19, `AwsNeptuneQueryHttpSuite` 7, `AwsReadModelsSuite` 20, all 0 failures | WO-7b report |
| service-engineer `sbt quick` (WO-8b) | `[success] Total time: 118 s`; groups 73 and 1012 (agent could not explain unchanged totals); `AwsLiveReadModelsSuite` 10, `AwsRuntimeSuite` 18, `AwsSettingsSuite` 10, `RetrievalModeRequestSuite` 7, all 0 failures | WO-8b report |
| test-engineer `sbt quick` (WO-12b) | `[success] Total time: 114 s` (2026-11-16 22:48:23 local); groups 73/0/0 and 1041/0/0; `P15EmbeddingMessageCrossModuleSuite` 29/0/0/0 (producer ran, all 28 bodies) | WO-12b report |
| Orchestrator `sbt quick` after session 1 stash pop (sources restored, unchanged since WO-12b) | `[success] Total time: 124 s` (2026-11-17 00:42:31 local); groups 73/0/0 and 1041/0/0; scalafmt and compile clean (rag: 9 deprecation warnings) | orchestrator run |
| Human `rm` of `AwsSqsWorkQueue.scala.pending` (human-commands.md section 1) | done, no output (2026-11-17) | human `!` run |

| WO-11 load-client rehearsal | 30 PASS, then crash at the connection-refused case: `FileNotFoundError ... refused-conn/requests.jsonl` (`rehearse.py:187`), exit 1; routed to experiment-engineer after R12 (Wave 1 priority) | `phase15-w2-rehearse-load-client.log` |
| WO-11 load-client rehearsal, rerun after the fix | `REHEARSAL pass=45 fail=0`, exit 0 (expected 45) | `phase15-w2-rehearse-load-client-2.log` |
| WO-11 C rehearsal | `REHEARSAL pass=35 fail=0`, exit 0 (expected 35) | `phase15-w2-rehearse-c.log` |
| WO-11 F rehearsal | `REHEARSAL pass=8 fail=0`, exit 0 (expected 8) | `phase15-w2-rehearse-f.log` |
| WO-11 shell rehearsal | `REHEARSAL pass=24 fail=0`, exit 0 (expected 24) | `phase15-w2-rehearse-shell.log` |

| Human Batch A (section 6), export size | `files=26 bytes=324158336` (~309 MiB), over the 128 MiB runtime limit; decision P15-25 | human `!` run |
| Human Batch A, harness | `RESULT pass=1742 fail=5`; five failures routed in repair batch w2-1 | `phase15-w2-harness.log` |
| storage-engineer `sbt quick` (WO-7d) | `[success] Total time: 117 s`; groups 73/0/0 and 1041/0/0; `SparkExportShapeSuite` 2/0/0 | WO-7d report |
| spark-engineer `sbt quick` (SP-2) | `[success] Total time: 162 s`; spark 77/0/0; `JsonlExportSuite` 26/0/0 | SP-2 report |
| spark-engineer `sbt check` (SP-2) | exit 1: one stale assertion, `tests/integration/SparkRoundTripSuite.scala:106` expected 11 tables, got 17; routed to test-engineer | SP-2 report |
| test-engineer `sbt check` after the 17-table fix | `Passed: Total 99, Failed 0` and `Passed: Total 1162, Failed 0`; `[success] Total time: 1347 s`, 2026-11-17 02:38:58 local; `SparkRoundTripSuite` 11/11; `SparkExportShapeSuite` XML 2/0/0 (02:16:34) | task buf8u3pr2 log; agent report |

| Human Batch B: `check.sh --skip-sbt` | `CHECK_RESULT=PASS_WITH_SKIPS`, exit 0 | `phase15-w2-rb1-check.log` |
| Human Batch B: manifest JSON | `MANIFEST_JSON_CHECK OK manifests=6`, exit 0 | `phase15-w2-rb1-check-json.log` |
| Human Batch B: harness | `RESULT pass=1751 fail=1`, `SKIPPED_CASES 0`, `real-repository-untouched` PASS; FAIL line 1667 `15.wave2.terraform.every-required-environment-variable-is-set-and-the-graph-run-id-comes-from-a-variable` ("SCHOLARCHAIN_GRAPH_RUN_ID is not set from a variable"); repair batch w2-2 | `phase15-w2-rb1-harness.log` |
| Human HEAP-MEASURE | exit 1, `MalformedManifest ... lacks required tables: communities, community-cross-edges, community-membership, component-membership, components, path-evidence` at `AwsReadModels.scala:232` via `ExportHeapMeasureSuite.scala:86`; confirms review M1; heap unmeasured | `phase15-w2-heap-measure.log` |

| storage-engineer `sbt quick` (w2rb2) | exit 0; storage 488 (487 passed, 1 skipped gated heap suite), 0 failed; two earlier runs exit 1 on a storage test-compile error | w2rb2 storage report |
| service-engineer `sbt quick` (w2rb2) | `[success] Total time: 313 s`, 03:20:08; services XML 167/0/0; spark 77, root 1041 | w2rb2 service report |
| Orchestrator `sbt check` after repair batch 2 (all Scala changes of batches 2-4; later batches 3-4 changed no compiled Scala) | `Passed: Total 99, Failed 0` and `Passed: Total 1162, Failed 0`; `[success] Total time: 1993 s (0:33:13.0), completed Nov 17, 2026, 3:53:45 AM`; `SparkRoundTripSuite` 11/11 incl. seventeen tables | task b436lic3b log, lines 900572-900598 |

Consequence: the single `sbt quick` covered rag and services as well; no separate rag or services sbt slot is needed for this state of the sources. `AwsSqsWorkQueue.scala.pending` still exists (human removes it). Formatting of rag and services is verified by that run's scalafmt step.
