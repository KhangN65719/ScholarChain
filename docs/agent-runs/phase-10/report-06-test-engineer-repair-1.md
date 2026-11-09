# Report 06: test-engineer, repair round 1 (condensed by the orchestrator, 2026-11-08)

Author: test-engineer. Status: INCOMPLETE. `sbt quick` passed; the one `sbt check` was stopped by the orchestrator after 50 minutes of silence (see hang-record.md). No production defect found. BLOCKED-ON: none.

Files (tests/** only). Created: tests/adversarial/Phase10RepairSuite.scala (19 tests). Modified: Phase10CourseSuite, Phase10WireSuite, Phase10EndToEndSuite, Phase10Http, Phase10GraphSuite, Phase10SecuritySuite.

Commands: `sbt quick` first run exit 1 (two failures in the new Phase10RepairSuite, test-side, fixed); second run `[success]`, root `Passed: Total 912, Failed 0, Errors 0`, services module Total 94, Failed 0, Errors 0. `sbt check`: stopped externally, no result, no marker. The slow Spark and Hadoop suites have not run on these sources; the run was stopped after Hadoop PipelineDriverOutputsSuite passed (207 tests).

Executed counts, second `sbt quick`, JUnit XML, 0 failures, 0 errors, 0 skipped: ApiContract 24, Course 24, Graph 12, GraphBounds 4, Security 14, Repair 19, Wire 10, EndToEnd 5, services module 94.

Changed expectations (before, after):
- Course "course 4: supported constraints are accepted": was 200 for window, topic/venue/institution filters, sync; now only applied constraints expect 200; filter cases moved to a new test asserting 422 UNSUPPORTED_CONSTRAINT with trace ID.
- Wire "frozen request examples are accepted": filters example removed; new test covers filters, limits.maxResults and conflicting window as 422 with code and trace ID.
- Graph cursor with a dropped filter: 200 or 400, now exactly 400 typed.
- Security "injection text in filters": 200, 400 or 422, now 400 or 422.
- Matcher: `conformsSomewhere` replaced by `conformsTop` (whole top-level body, typed values); added `Json.mismatch`.

New tests by finding: D13/M1 (filters, window, maxResults 1, 5, 0, 1000000, unknown limit, fractional 400, idempotent refusal); M2/D14 (slow workflow with small pool beside 12 missing-evidence reads, explain and graph reads, all 404 under 1 s; /readyz stays 200; full call pool and full job queue give 503 OVERLOADED with Retry-After); D15/m3 (failed and timed-out async jobs give 409 JOB_FAILED with original code in details, stable on repeat); m1 (raw-socket 64 MiB declared body: 401, 403, 413 promptly; 5 MiB anonymous body 401, server keeps serving); m2 (four in-flight requests at close() end in typed 5xx or clean IOException within the bound); m4 (empty, blank, short or control-character cursor secret fails config load naming auth.cursorSecret; 16 bytes loads; no secret in response or log); m6 (richer typed frozen examples plus matcher self-test); m7 (synthesis prompt calledInThisQuery == true); nits (replayed 202 reports succeeded with same Location; anonymous gets 401 on unknown routes under /v1; authenticated gets 404 and 405 with Allow; outside /v1 anonymous gets 404 and 405).

First-run failures, both test-side: an explain read was not slow (set fault.explain=slow); a cursor-secret echo assertion mis-fired on a blank secret.

Risks: M2 and OVERLOADED tests are timing-dependent (600 s pool sleeps, 3 s deadlines), passed twice, add about 10 s; m1 raw-socket tests assume the JDK server answers from headers alone. No suite added to slowSuites.

Next gate: one `sbt check`, blocked until the Spark stall is diagnosed.
