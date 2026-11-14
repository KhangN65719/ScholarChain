# Work order 25: build-engineer, JDK target and lib contents of the job jars (read-only)

Owner: build-engineer. Read-only: no edits, no sbt, no docker, no AWS, no commit.

Evidence: docs/agent-runs/phase-14/report-20-infra-engineer-emr-design-b.md (blocked-on build-engineer), report-15 content, build.sbt, project/DependencyVersions.scala, project/plugins.sbt, packaging/**.

Questions (read from files; mark UNVERIFIED anything only a build can show):
1. The Java bytecode target of each job module (`-release:17`? set for scalac and javac, build.sbt lines 5 and 6 per earlier reading): can the job jars run on JDK 17 only (EMR bootstrap Corretto 17) or do they need 21? What Scala 3 and Java library constraints apply?
2. What `stageJob` copies into `modules/<m>/target/job/lib` for hadoop, entityResolution and sparkAnalytics: which jars are present or excluded (Provided Hadoop and Spark artifacts, scala-library, scala3-library_3, upickle family, core). Specifically: does `target/job/lib` of the hadoop module contain any `hadoop-*` jar, and does the spark module's contain any `spark-*` jar? Say how the infra scripts' filters (exclude `hadoop-*` from `-libjars`, `spark-*` and `hadoop-*` from `--jars`) interact with that, and whether a Scala 2.13 `scala-library` appears in the Spark lib (the Spark 4 distribution brings its own; two copies on one classpath is the risk).
3. Does `package.sh` (lines about 225-239: one main jar plus lib/ per job module) still describe what `upload-inputs.sh` uploads (`jars/<module>/<main>.jar` and `lib/*.jar`)? Name any mismatch.
4. Confirm `CollaborationPipelineSuite` (slow) stays in `slowSuites` after the spark and hadoop work (new suites: YarnReadinessSuite, ToolConfigurationPropagationSuite both run in under 0.3 s and need no slowSuites entry).

Report contract: STATUS, OWNER, WORK ORDER, FINDINGS (1 to 4), RISKS, UNVERIFIED LIST, NEXT GATE. Your final message must be the complete report text.
