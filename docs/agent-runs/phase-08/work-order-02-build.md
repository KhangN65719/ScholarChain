# Work order 02: build-engineer, rag test edge and slowSuites

Owner: build-engineer (build.sbt, project/**). Phase 8, decision E9. Local only. No new library dependency and no change to `project/DependencyVersions.scala`.

## Required work
1. In `build.sbt`, add `rag % "test->compile"` to the root project's `dependsOn`, next to the existing `storage % "test->compile"` edge and with the same kind of comment (Test-only, no runtime edge, lets independent tests under `tests/` reference rag contracts). It must add no Compile, Runtime or packaged-classpath edge and no cycle (rag depends on core and storage; nothing depends on root).
2. `slowSuites`: the spark-engineer's report-01 says the new `JsonlExportSuite` (~9 s on the shared Spark session) is NOT slow, so add nothing unless you measure otherwise. Remove nothing. `check` must still run every test.
3. Keep every project's `quickSettings`. Do not touch module sources.

## Evidence supplied by the orchestrator
SPARK REPORT (paste of report-01): see below the line when this order is sent.

## Verify
`sbt Test/compile` once, then `sbt quick`. Report exact pass and fail counts. Confirm the edge is Test-only by reading the resulting project definition; do not run sbt forms other than the three allowed bare ones.

## Not allowed
Application source, tests/**, Terraform, version values, CLAUDE.md, docs/agents.md, .claude/**.

## Report
Role-file contract. The orchestrator archives it as `report-02-build.md`.
