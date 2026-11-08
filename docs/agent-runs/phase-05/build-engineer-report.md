# build-engineer report: build.sbt change (Phase 5)

Author: build-engineer (archived by the orchestrator, condensed). Date: 2026-11-07. The agent ran and edited nothing for this resend; no commit. (An earlier version of this file was the orchestrator's own reading of `git diff build.sbt`, written because the report body had been swallowed by the stop-gate twice; the resend later arrived and is recorded here.)

## The change
Per `git diff build.sbt` against HEAD, the root project's `dependsOn` gained a third entry, `entityResolution % "test->compile"`, beside `core % "test->compile"` and `hadoop % "test->compile"`, with a comment that entityResolution is a batch job module and gets the same treatment so independent tests under `tests/` can reach it without a runtime edge. It is the only uncommitted hunk in `build.sbt`.

## Why it was needed
Aggregation is not a classpath edge: the root test classpath sees only the projects named in `dependsOn`. The root Test configuration compiles `tests/adversarial` and the other independent-test roots. Without the new edge the test-engineer's `AdversarialEr*` suites and `AdversarialLeakageSuite` could not reference `scholarchain.er.*`. They compiled and passed in the agent's first run, which is indirect evidence the edge works (the agent did not remove the edge to confirm it fails without it).

## Module graph effect
The edge is `test->compile` only, so it appears in no Compile, Runtime or packaged classpath. `entityResolution` still depends only on `core`, `hadoop` still depends only on `core`; they have no direct dependency on each other; `core` depends on nothing; `services` is still `core, storage, rag` (no batch job module enters the runtime graph). In `hadoop`, `hadoop-client-api` is still `Provided` and the runtime/minicluster jars are `Test` scope; the root declares `hadoop-client-api` and `-runtime` at `Test` itself (sbt does not carry a Provided dependency across a project edge; predates this change). `entityResolution` declares no libraries of its own (upickle through `core`, munit through the `ThisBuild` Test dependency).

## Ledger
Unchanged: `git status --short project` printed nothing, so `project/**` including `DependencyVersions.scala` is untouched; no dependency pin changed.

## Per-module counts from the agent's first passing run
Bare `sbt check`, `[success] Total time: 17 s`, scalafmt passed, four summary lines, 717 tests, 0 failed, 0 errors: 41 (core), 298 (entityResolution), 190 (root), 188 (hadoop). `sparkAnalytics`, `storage`, `rag` and `services` printed no summary line (taken to have no tests). Attribution is inferred (41 = ConfigSuite 5 + MatchCandidateSuite 10 + ContractSuite 26; 188 matches earlier hadoop tails; 298 the ER suites; 190 includes AdversarialGiantTeamSuite). Nothing was truncated in the saved log.

## Unverified (agent)
Per-module attribution (inferred); who made the `build.sbt` edit (no archived work order); pre-change counts; whether `PipelineTool` runs on the hadoop Test classpath (not read; hadoop build settings unchanged); the "build is green" statement was the coordinator's; the agent's runs are not the recorded phase gate.
