# Work order 34: rag-engineer, a skipped graph is a failure when Neptune is configured

Owner: rag-engineer (modules/rag/**). Edits in modules/rag/** only. No AWS, no commit. Agents run only bare `sbt Test/compile` and `sbt quick`; never two sbt at once (none is running now). Before editing, list which test proves each item at the top of your report.

Decision (orchestrator, from D17: a Neptune load is required in Phase 14; the human has not objected): in `AwsIngestMain`, when `SCHOLARCHAIN_NEPTUNE_HOST` is set (Neptune is part of the session) the graph step is REQUIRED: a skipped graph (missing tables, projector refusal) or a failed Neptune load gives exactly one `LOADER_RESULT=FAILED`, a nonzero exit, and a report with `graph.status` SKIPPED or FAILED and the reason; the vector results are still reported, and the vectors already written stay (idempotent rerun). When `SCHOLARCHAIN_NEPTUNE_HOST` is absent the run stays vectors-only: `LOADER_RESULT=SUCCESS`, `LOADER_SCOPE=vectors-only (the graph was NOT loaded)`, `NEPTUNE_LOAD=SKIPPED reason=no Neptune host configured`. The export now carries the eight graph tables (spark-engineer, work order 32), so with Neptune configured the real path should project and load.

Tasks:
1. Implement the rule above; keep every existing marker name and the single last `LOADER_RESULT=` line.
2. Tests (stubbed ports): Neptune host set and tables missing gives FAILED and nonzero exit with the reason; Neptune host set and the projection works gives SUCCESS (the existing end-to-end test); Neptune host set and the load fails gives FAILED; no host gives vectors-only SUCCESS with the scope line; the report's `graph.status` for each. Update the existing tests whose expectation changes and say which.
3. Run `sbt Test/compile` then `sbt quick`; report exact counts.

Report contract: STATUS, OWNER, WORK ORDER, TEST PLAN, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS (exact counts), ASSUMPTIONS, RISKS, UNVERIFIED LIST, BLOCKED-ON OWNER, NEXT GATE. Your final message must be the complete report text (the stop hook may fire when you finish; ignore it and put the full report in your reply).
