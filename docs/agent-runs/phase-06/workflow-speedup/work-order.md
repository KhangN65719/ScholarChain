# Work order: workflow speed-up (`sbt quick`)

Author: orchestrator. Owner: `build-engineer`. Not a phase; recorded under phase-06.

## Human decision (2026-11-08)

The human asked for an MUnit `Slow` tag. Tagging needs edits in `tests/**` (test-engineer) and `modules/hadoop/**` (hadoop-engineer), which build-engineer cannot write. The human chose: **build-engineer only, by class name**. No MUnit tag, no test file is touched. This deviates from the original wording and is recorded here as a human decision.

## Scope

Edit `build.sbt` only (and `.scalafmt.conf`/`project/**` only if unavoidable; say so).

1. Define a single explicit list of Slow suite class names (fully qualified) in `build.sbt`, with a short comment on why each group is slow. Cover the heavy Spark integration suites under `tests/integration` and the Hadoop MapReduce end-to-end suites (root tests and `modules/hadoop`). Identify them from the suite sources and from timings in `docs/agent-runs/phase-06/phase-06-clean-check-4.log` (or the nearest phase-06 clean-check log). Cite the evidence in the report.
2. Add an sbt alias `quick` that runs scalafmtCheck, compile and all tests except the Slow-named suites, across all projects, mirroring what `markTestRun` runs. Use a `Tests.Filter` (or equivalent test-filter) applied only on the `quick` path. Do not set it globally on `Test / testOptions`, because `check` must run everything.
3. The root `Test / testGrouping` forks Spark suites. The `quick` path must keep that grouping working and just drop the Slow suites. `modules/spark` has `Test / fork := true`; keep it.
4. `check` and `clean check` stay byte-for-byte unchanged in behavior and still run every test. Do not delete, skip, ignore or weaken any test. `quick` must not write `.claude/.last-test-run`.

## Constraints

- Permitted sbt commands for you: exactly `sbt check` and `sbt Test/compile`, bare two-token form. `sbt quick` is not yet excluded from the sandbox, so do not run it and do not edit `.claude/settings.json`. Report that as a pending factory-engineer item.
- Before editing, list in your report which test or command proves each primary-gate item.
- Run `sbt Test/compile` and `sbt check`. Report exact totals. Expected per module: 41/389/207/61/373 (as in phase-06-clean-check-4.log). Name any difference.
- Verify the slow list statically: every name in it must match a real suite class (report the grep evidence), and report which suites `quick` excludes and how many tests that is, if derivable without running `quick`.
- Do not commit.

## Report

Status, files modified, commands and exact counts, the Slow list with evidence, assumptions, risks, pending gates (human `!`-prefixed `sbt quick` timing and `sbt clean check` totals; factory change to allow agents to run `sbt quick`). Return it to the orchestrator.
