# Phase 2 validation

Baseline `027c9b70512d09c4c3cde65d7bd1ccaa9f5d82f3`. Verified with the pinned sbt 1.11.7 launcher and JDK 21.

- Initial build load exited 1 because the plugin build could not see the Scala version ledger. The owner shared the ledger with the plugin build.
- The next run passed formatting and test compilation. The check exited 1 because the sbt formatter check needed explicit Compile scope. The owner scoped the key and reran the checks.
- Final `sbt fmt Test/compile check` exited 0. `projects` and `show scalaVersion` confirmed all seven module IDs plus root and Scala 3.3.7 in every project.
- `printf '{}\n' | .claude/hooks/verify-tests-ran.sh` exited 0 after the successful workspace check. This directly exercises the script.

## Marker behavior in a disposable copy

| Invocation and fixture | Exit | Marker |
| --- | --- | --- |
| `sbt Test/compile` before first check | 0 | Absent |
| `sbt check` with a deliberately unformatted Scala test source in services | 1 | Absent |
| `sbt fmt check` with that source | 0 | Created |
| `sbt check` with a formatted services source assigning a string to an integer | 1 | Exact modification time preserved |
| `sbt check` with a session-local override forcing services' Test/test task to fail | 1 | Exact modification time preserved |
| `sbt check` after removing the failure fixtures and override | 0 | Modification time advanced |

The production scaffold has no application or contract test cases. The fixture checks demonstrate sequencing and failure propagation. Phase 1's native Codex hook enforcement and reviewer tool fence remain open.

## Full course candidate

- Build owner ran `sbt fmt Test/compile check packageJobs`, exit 0.
- Orchestrator copied the final candidate without build output or a marker and ran `sbt Test/compile check packageJobs dockerLocal`, exit 0. Hadoop and entity-resolution each staged a primary JAR and nine companion JARs. Spark staged a primary JAR and eight companions. All bundles include the core, Scala 3, and uPickle runtime JARs, omit test libraries and provided Hadoop and Spark dependencies, and contain each primary JAR once. Spark relies on the distribution's Scala 2.13 library.
- Test owner ran `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false python3 -B tests/check_test_marker.py --sbt /private/tmp/scholarchain-phase2/sbt`, exit 0. The disposable clean build created the marker. A deliberate MUnit failure under `services / Test / test` preserved its exact bytes and modification timestamp. Python syntax and CLI checks also passed.
- `docker image inspect scholarchain-local:phase-2` showed user `10001:10001` and the configured Java entry point. `docker run --rm --network none --entrypoint /usr/bin/id scholarchain-local:phase-2 -u` returned 10001. `docker run --rm --network none --memory 256m scholarchain-local:phase-2 -XX:+PrintFlagsFinal -version` reported Temurin 21.0.13+9 and a max heap of 201326592 bytes, 75% of 256 MiB.
- The Docker build used only a staged build context and pulled the public digest-pinned base image. No container was published or deployed. No emulator is required for the current checks, so Compose was omitted.
- A temporary `tests/contracts/BuildContractProbeSuite.scala` was discovered by `sbt check` and passed an offline uPickle round trip. The build owner separately compiled disposable Scala 3 fixtures against Hadoop's Java job API and Spark's DataFrame API and passed a derived uPickle codec round trip. These probes stayed outside the repository.
