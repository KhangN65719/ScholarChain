# Work order 03: build-engineer

Owner: build-engineer. Owned paths only: build.sbt, project/** (excluding ledger version values), .scalafmt.conf, .gitignore, compose.yaml, Dockerfile, packaging/**. Date: 2026-11-08.

## Order

Use the build-engineer agent to make the root test project able to compile the Phase 10 black-box tests.

Evidence: service-engineer report 01b. `sbt check` fails at `(Test / compileIncremental)` in the root tests because `build.sbt` has no `services % "test->compile"` edge, so tests/adversarial/Phase10Http.scala cannot resolve `scholarchain.services.runtime.LocalRuntime`. The services module group itself passed 79 of 79.

## Scope

1. Add the minimal edge so the root test project sees services test-scope classes, matching how the root already depends on the other modules. Respect the module graph in CLAUDE.md: no cycles, no new dependency, no ledger change.
2. Decide from the build, not from guesses, whether the Phase 10 root suites (HTTP server on loopback) belong in `slowSuites`. Add them only if they are slow Spark or Hadoop-class suites or measured slow. Never remove a test from `check`.
3. Docker: do not run Docker. If a Dockerfile or packaging already references services, report its static state only. Container work is deferred to Phase 12 or 14.

## Constraints

Local only. Do not edit modules/**, tests/**, CLAUDE.md, docs/agents.md or .claude/**. Do not weaken or delete tests. Never run two sbt commands at once. Run `sbt quick` once after the edit. Do not run `sbt check` (the orchestrator sequences one `sbt check` before review). No commit. Read-only git only.

## Report

Standard contract, plus: the exact build.sbt diff, the module dependency graph after the change, `slowSuites` decision with evidence, and exact `sbt quick` counts per suite group, including how many Phase 10 root tests ran.
