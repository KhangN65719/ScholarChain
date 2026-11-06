# Phase 2 work order

Baseline: `027c9b70512d09c4c3cde65d7bd1ccaa9f5d82f3`

Owner: `build-engineer`. Inputs: [project memory](../../../CLAUDE.md), [ownership map](../../agents.md), [role](../../../.claude/agents/build-engineer.md), [Phase 1 restart evidence](../phase-01/restart-results.md), and the [course specification](https://github.com/0x1DOCD00D/ScholarChain/blob/main/README.md#phase-2-create-the-build-and-repository-skeleton).

Create the seven-project Scala 3 scaffold, configure formatting, and implement `sbt check`. The check must verify formatting, compile, and run tests before updating `.claude/.last-test-run`. Verify `sbt Test/compile`, a successful check, and a failing check that leaves the marker intact. Stop before Phase 3.

The architect delegated version selection to fit the course specification. The orchestrator routed Scala 3.3.7 LTS, sbt 1.11.7, sbt-scalafmt 2.5.6, and Scalafmt 3.10.0 to the build owner. This work order covers the requested build and verification scaffold. Application libraries and job and container packaging were outside that scope.

## Full course checklist extension

The architect directed that each phase satisfy the full project checklist. This extension supersedes the earlier scaffold-only scope.

- `build-engineer` owns compatible dependency selection and pinning, test classpath and offline contract discovery, Hadoop/Spark Provided scopes, runtime logging, `packageJobs`, `dockerLocal`, the pinned non-root Java container, and empty ownership roots. Include Compose only for a demonstrated emulator or test dependency. Keep application logic and cloud operations outside Phase 2.
- `test-engineer` owns a reproducible real-build marker regression under `tests/`. Verify that a clean success creates the marker and a deliberately failing MUnit test preserves it.
- Verify from clean build output and confirm the local image runs with the configured user and memory settings. Re-review the complete candidate before closing Phase 2. Preserve the human acceptance and restart boundary before Phase 3.
