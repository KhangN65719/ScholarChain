# Wave 2 repair batch 2, service-engineer B1b and m7 (received 2026-11-17; author: service-engineer subagent; summary as received)

Status: COMPLETE. Bare `sbt quick`: `[success] Total time: 313 s (0:05:13.0), completed Nov 17, 2026, 3:20:08 AM`; last summaries spark 77/0/0, root 1041/0/0. Services XML (03:15:22): 167 tests, 0 failures, 0 errors, 0 skipped (163 + 4).
Files (`modules/services/`): `runtime/AwsSettings.scala` (`exportRunId`; `EnvExportRunId = SCHOLARCHAIN_EXPORT_RUN_ID` required, `run-v1-[0-9a-f-]{36}`; Docs row; `AllowedRegion = us-east-2`); `runtime/AwsBackend.scala` (`storageSettings` passes `exportRunId` by name); `AwsSettingsSuite.scala` (4 new tests: valid slug kept apart from graph run; missing/blank/colon form refused by name; only us-east-2; storage settings carry it by name).
Risk: `SCHOLARCHAIN_EXPORT_RUN_ID` required for every AwsRuntime role, like `SCHOLARCHAIN_GRAPH_RUN_ID`; root 1041 passed, so no positional caller under `tests/**` broke.
