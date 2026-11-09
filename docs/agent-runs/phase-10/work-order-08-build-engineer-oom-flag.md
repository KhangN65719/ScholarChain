# Work order 08: build-engineer, fail-fast on OOM in the Spark test JVMs

Owner: build-engineer. Owned paths only (build.sbt). Date: 2026-11-08. Evidence: hang-record.md and the spark-engineer diagnosis (2026-11-08).

## Order

Use the build-engineer agent to add `-XX:+ExitOnOutOfMemoryError` to `sparkJvmOptions` in build.sbt, so an OutOfMemoryError in a forked Spark test JVM fails the run with a stack trace instead of leaving sbt waiting on a stuck JVM (a `sbt check` stalled for 50 minutes this way).

## Constraints

- Change nothing else: no heap change (`-Xmx2g` stays, raising it is a human decision), no test grouping change, no `slowSuites` change, no dependency or ledger change. Update only the nearby build.sbt comment so it states the flag's purpose and does not contradict the cap's fail-fast intent.
- Edit only build.sbt. Do not touch modules/**, tests/**, CLAUDE.md, docs/agents.md or .claude/**. No commit. Read-only git only.
- Run `sbt quick` once after the edit. Do not run `sbt check` (the human runs it as a paste-ready command). Never two sbt commands at once.

## Report

Standard contract, plus the exact build.sbt diff, the line numbers, and the exact `sbt quick` counts per group.
