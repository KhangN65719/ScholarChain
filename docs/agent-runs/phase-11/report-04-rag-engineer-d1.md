# Report 04: rag-engineer (work order 04, D1), as received (condensed)

STATUS: COMPLETE. Owner: rag-engineer.

## Test mapping (listed before code)
Phase11SemanticDegradeSuite: "transient vector failure: deterministic answer stays verified" (same for StorageException); "both failure kinds produce the identical known gap"; "no deterministic evidence plus a failed vector read abstains honestly"; "transient vector failure: no exception text reaches output or audit" (also the retry-exhausted case); "an unrelated programming error is not swallowed" (IllegalStateException propagates). End to end: tests/adversarial/Phase11FaultSuite.scala:199 (D1) and :219 (sibling).

## Changes (modules/rag only)
- Retrieval.scala: `SemanticContextAgent.context` catches `StorageException | TransientStorageException` only (fatal errors and InterruptedException propagate). New `SemanticContextAgent.degradedNote(index)`: fixed system string with only the index name ("Semantic context from index <index> was unavailable (the vector read failed), so none is shown; deterministic evidence is unaffected."). The old note echoing `e.code` is gone; both paths are identical.
- Workflow.scala: audit "semantic" stage outcome is "degraded: N index read(s) failed, M item(s)" when any index failed; otherwise unchanged. System-written.
- Phase11SemanticDegradeSuite.scala: new, 7 tests. The note reaches `response.evidence.uncertainty` via `builder.note`; not prompt-injectable.

## Semantic-only operations
No operation answers from semantic context alone. `Workflow.pipeline` already abstains with `no-deterministic-evidence`. No change; the abstention test covers it.

## Results
- Phase11FaultSuite (XML, 00:34:58): 12 tests, 0 failures, 0 errors. D1 passes; sibling still passes.
- Phase11SemanticDegradeSuite (00:36:23): 7 tests, 0 failures, 0 errors.
- Final `sbt quick` (12:39:04 AM, exit 0): `Passed: Total 62, Failed 0, Errors 0, Passed 62` and `Passed: Total 956, Failed 0, Errors 0, Passed 956`, `[success] Total time: 76 s`. Turn evidence only.

## Commands and exit codes (bare `sbt quick`, run as background jobs because the foreground tool truncates)
1. Exit 1: compile error in my test, fixed.
2. Exit 1: 2 failures in my new suite (assumed `uncertainty` was a Vector; it is a joined Option[String]), fixed.
3. Exit 1: 1 failure in tests/adversarial/Phase10RepairSuite "m1: a 5 MiB body from an unauthenticated client library is refused": `java.io.IOException: fixed content-length: 187, bytes received: 0`, Broken pipe on the HTTP client. Unrelated to rag. Passed in the final run: looks like a timing flake in the socket test. Not edited.
4. Exit 0: final run. `sbt check` not run.

## Notes and risks
- Foreground Bash truncates `sbt quick` output; use background and read the output file.
- A first draft used InterruptedException in a test; munit lets it propagate, so replaced with IllegalStateException.
- Only the two named storage exceptions degrade; any other runtime exception from the vector client still fails the answer (pinned by a test).
- A TransientStorageException from other stages (Neptune, metrics) is not handled by `execute()` (only `StorageException` is caught), so a throttled Neptune read still gives 503. Outside D1, not changed.
- Did not touch tests/**, build.sbt, CLAUDE.md, docs/agents.md or .claude/**.

## Next gate
Independent review, then the human's `sbt clean check`.
