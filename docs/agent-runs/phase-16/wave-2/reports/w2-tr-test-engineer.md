# W2-TR: test-engineer repair report (review 2 F6 and F4)

Author: test-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** `sbt quick`: `[success] Total time: 156 s`, completed 1:09:41 PM; `Passed: Total 77, Failed 0, Errors 0` (root forked Spark group) and `Passed: Total 1304, Failed 0, Errors 0`.

Files: modified `tests/adversarial/P16W2CostGuardSuite.scala`; created `tests/adversarial/P16W2GraphCompositionSuite.scala`. No production, build or other edits.

**F6 (done).** "the diagnostic makes at most four model calls whatever fails" now also asserts `spy.calls >= 1`, `g.haikuCallCount >= 1`, `report.records >= 1`, `raw.nonEmpty`; upper bounds unchanged. Lower bounds from the spec and the test's premise (a retryable failure invites retries).

**F4 (done).** `SparkGraphProjector` takes a `TableSource` (`Neptune.scala:115`) and starts no Spark session, so the suite is named without "Spark" and runs in-process. Fixture: `Phase7Storage.world()` written as a real JSONL export directory (`export-manifest.json` with checksums, `_SUCCESS`, empty `path-evidence`), read by the projector through `JsonlExportSource.open` and by `GraphChecks.run`. Tests:
1. `composedEdges`/`composedVertices` equal `projection.edges.size`/`projection.vertices.size`; literal pins 19 edges (7 + 1 + 3 + 5 RESOLVES_TO + 3 DERIVED_FROM) and 14 vertices (4 + 3 + 1 + 5 + 1).
2. `resolvesToEdges`, `derivedFromEdges` equal the projector's label counts; `snapshotVertices` equals the DatasetSnapshot count.
3. A second `snapshotId` adds one snapshot vertex in both.
4. `mentionId == researcherNodeId` on every row gives 0 RESOLVES_TO in both.

| Suite | Tests | Errors | Failures | Time |
|---|---|---|---|---|
| `P16W2CostGuardSuite` | 34 | 0 | 0 | 0.25 s |
| `P16W2GraphCompositionSuite` (new) | 4 | 0 | 0 | 0.109 s |

Not a `slowSuites` candidate. First two `sbt quick` runs exited 1 on the agent's compile error (`Not found: type Row`, fixed by importing `scholarchain.storage.Row`, found via `sbt Test/compile`).

Risks: one fixture shape; real-export scale and `provenance.snapshotId` presence remain the human's GraphChecks `-2` run. Pending: `sbt check`, the human's `sbt clean check`.
