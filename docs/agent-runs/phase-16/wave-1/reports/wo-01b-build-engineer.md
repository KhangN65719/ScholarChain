# WO-1b report (build-engineer), received 2026-11-18, archived as received

Repair routed from WO-7's `BLOCKED-ON build-engineer`.

**Status: COMPLETE for the edge. The build is not green, as expected.** I ran `sbt Test/compile` only and did not run `sbt quick`. The three suites do not compile until WO-4 lands.

**Which project compiles tests/adversarial:** `root`. In `build.sbt`, `root` adds `tests/adversarial` to `Test / unmanagedSourceDirectories`, so the three P16 suites compile there.

**Diff (`build.sbt`, only file written):** the `.dependsOn(...)` list on `root` gains one entry. The last line changed from `services % "test->compile"` to `services % "test->compile",`, and the following was added before the closing `)`:
```
    // evaluation is an offline leaf (Phase 16, D2); this Test-only edge adds no
    // Compile, Runtime or packaged-classpath edge and lets the independent
    // suites under tests/adversarial reference scholarchain.evaluation.*. No
    // cycle: evaluation depends on core, storage, rag and sparkAnalytics, and
    // nothing depends on root.
    evaluation % "test->compile"
```
- **Scope:** the edge is test-only, so `evaluation` stays out of the runtime dependency graph and packaging.
- **Cycle:** none.

**Why I skipped `sbt quick`:** the suites are in a compiled source directory and are not excluded. The `quick` alias compiles tests through `quickTest` before running them, so it would fail on them.

**Command run:** `sbt Test/compile`. It exited 1: `[error] 30 errors found`, `(Test / compileIncremental) Compilation failed`, total time 4 s. The output was truncated; only part of the 30 errors was seen.

- **Root cause (E008):** one per suite file, "value evaluation is not a member of scholarchain": `P16BootstrapSuite.scala:4`, `P16GraphChecksSuite.scala:6`, `P16RetrievalMetricsSuite.scala:4`.
- **Cascading errors (E171):** "missing argument for parameter clue" on `assertEquals`, `assertEqualsDouble` and `assert` calls, read as fallout from the unresolved imports; not checked one by one.

**Not verified:**
- `experiments/evaluation/` could not be listed (guard refused `find`).
- Whether the edge resolves cleanly once WO-4 lands, and whether the suites then compile and pass.
- `sbt quick` and `sbt check` were not run, so `.claude/.last-test-run` is unchanged.

**Next gate:** WO-4 lands the evaluation packages, then `sbt quick`. If those suites run Spark, they may need `slowSuites`. The sbt slot is released.

## Orchestrator note (not part of the report)
- From this change until WO-4 lands, `sbt Test/compile` on the working tree fails by design (missing `scholarchain.evaluation.*`). WO-4's `sbt quick` is the next sbt run and must compile both its own code and the WO-7 suites.
