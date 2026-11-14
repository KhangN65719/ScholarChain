# Work order 28: build-engineer, exclude apache5-client and add the job-module SDK guard

Owner: build-engineer (build.sbt, project/**). Edits in those paths only. Agents may run only the bare `sbt Test/compile`, `sbt quick`, `sbt check`; never two sbt at once (none is running now). No AWS, no commit.

Decision basis: D22 (human): "Apache and Netty HTTP clients excluded", only `url-connection-client` as the HTTP client. Your report for work order 26 found `apache5-client` 2.59.1 in the resolved graph (the newer SDK ships an Apache 5 based client). The orchestrator reads D22 as covering it: it is an Apache HTTP client, so exclude it the same way. Say in your report if you disagree.

Tasks:
1. Add `.exclude("software.amazon.awssdk", "apache5-client")` to every SDK dependency in `storage` (the six declared modules), keeping the two existing exclusions. If another HTTP client artifact appears in the graph (any `*-client` other than `url-connection-client`, `http-auth*` and `*-spi`), report its name; do not guess exclusions you cannot see. You may run the one sbt form `sbt Test/compile` first and read what Coursier cached.
2. Add the guard you proposed as Option 1: a build-level check that fails when any of `hadoop`, `entityResolution` or `sparkAnalytics` has a `software.amazon.awssdk` jar on `Runtime / dependencyClasspathAsJars`, wired into `check` and `quick` (keep it cheap) and into `packageJobs`. Prove it fails when it should if you can do so without leaving a broken build (for example by temporarily asserting on a name you know is on the classpath, then reverting; tell me exactly what you did). Report how it prints.
3. Report the HTTP client situation for adapters: which client artifacts are on the `storage` Runtime classpath after your change, so storage-engineer sets `UrlConnectionHttpClient` explicitly (D22: credentials come only from the default provider chain).
4. Run `sbt Test/compile` then `sbt quick`; report exact results, eviction warnings if you can read them (the output may be large; read only what you need, the guard refuses grep), and the marker effect.

Report contract: STATUS, OWNER, WORK ORDER, TEST PLAN (item to check), FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS, ASSUMPTIONS, RISKS, BLOCKED-ON OWNER, NEXT GATE. Your final message must be the complete report text.
