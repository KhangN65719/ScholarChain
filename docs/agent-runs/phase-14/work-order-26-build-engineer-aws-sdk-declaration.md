# Work order 26: build-engineer, declare AWS SDK v2 2.59.1 in storage

Owner: build-engineer (build.sbt, project/** excluding ledger values you were not given; the human supplied and approved this value). Agents may run only the bare `sbt quick`, `sbt check` and `sbt Test/compile`; never two sbt at once (no other sbt is running now). No AWS, no commit.

Decision (human, D22): `awsSdk = "2.59.1"` approved. Evidence: docs/agent-runs/phase-14/decisions-d13-d14.md (D22), docs/dependencies/aws-sdk-v2-proposal.md (dependency-updater; its facts were UNVERIFIED there and the orchestrator verified the version and module availability on Maven Central), report-16-storage-engineer-aws-loader-inventory.md.

Tasks:
1. Add `val awsSdk = "2.59.1"` to project/DependencyVersions.scala in the existing style (build-engineer may scaffold the key with the human-approved value; later value changes belong to dependency-updater).
2. In `storage` only, declare with `% DependencyVersions.awsSdk` (group `software.amazon.awssdk`, plain `%`, Java artifacts): `s3`, `s3vectors`, `bedrockruntime`, `neptunedata`, `dynamodb`, `url-connection-client`. Exclude `apache-client` and `netty-nio-client` from every SDK module (`ExclusionRule("software.amazon.awssdk", "apache-client")` and the netty one). Do not add `sts`. Do not add the SDK to `core`, `hadoop`, `entityResolution`, `sparkAnalytics` or root Compile. Report whether `rag` and `services` get the SDK through `.dependsOn(storage)` and whether the Runtime classpath of the three job modules is unchanged (they depend on core only).
3. Resolve and compile: run `sbt Test/compile`, then `sbt quick`, and report exact results, any eviction warnings (slf4j-api 1.7 versus the ledger's 2.0.18; say what sbt reported), the resolved SDK jar count if the output shows it, and any conflict. If `dockerLocal` would now copy the SDK jars into `target/docker-local/lib` (the services runtime classpath), say so and give the jar names you can see; do not run docker.
4. Add or propose, for test-engineer, a guard check that the three job modules' Runtime classpath contains no `software.amazon.awssdk` jar (a test or a package.sh check; list who owns it, do not write tests).
5. Do not write any adapter code. No change to `slowSuites` unless a new suite is slow.

Report contract: STATUS, OWNER, WORK ORDER, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS (exact counts, eviction warnings), ASSUMPTIONS, RISKS, BLOCKED-ON OWNER, NEXT GATE. Your final message must be the complete report text.
