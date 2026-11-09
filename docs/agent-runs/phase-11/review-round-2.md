# Phase 11 review, round 2 (fresh code-reviewer, Opus, read-only): REQUEST_CHANGES (as received, condensed)

Round-1 items are fixed. One new major finding.

## New findings
- **N-1 (major, service-engineer).** A structured window with a start year below 1000 is echoed but not applied. QueryService.scala:326-330 appends `s"$question from ${w.startYear}-${w.endYear}"`; `parseWindow` (:111) accepts any startYear from 1 up. The planner reads only 4-digit years (Planner.scala:368 `\b(\d{4})\s*(?:-|...)\s*(\d{4})\b`) and, with no window read, falls back to `config.defaultWindow` = YearWindow(1, 9999) (Planner.scala:15, :152). Input: "How many distinct coauthors does Ada Quillfeather have?" with window {1, 2009}: composed "...have? from 1-2009", planner applies 1-9999, response 200 echoes 1-2009 but includes post-2009 data. Also {500,1500}, {999,2009}. Same defect class as round-1 M-1; contradicts QueryService.scala:317-318, report-06 "Wire changes" and ADVERSARIAL_COVERAGE.md:46. Fix: zero-pad the clause (`f" from ${w.startYear}%04d-${w.endYear}%04d"`, so the planner reads "0001-2009" as (1,2009)) or 422 when startYear < 1000. Tests: Phase11Suite case (service-engineer); windows (1,2009), (500,1500), (1,9999) in the Phase11WindowAgreementSuite table (test-engineer; (1,9999) passes only by coincidence with the default).
- **N-2 (minor, service-engineer with test-engineer).** The System.err redirect in Phase11RepairSuite.scala:31-41 is restored but not serialized across suites. `PrintStreamLock` is private to the suite; services tests are not forked (build.sbt:338-348); tests/adversarial/Phase10Http.scala:243-263 `LogTap` also swaps System.out and System.err globally without a lock. If a root suite and this suite overlap in the sbt JVM, nested save and restore can leave System.err on a dead buffer; a redaction check could pass vacuously or flake. Unverified whether they run concurrently under `quick`. Fix: one shared lock, or fork the services tests.
- **N-3 (nit, test-engineer).** ADVERSARIAL_COVERAGE.md:38 says "Phase11FaultSuite passes 12 of 12"; it has 13 tests. Line 46 restates the "always" guarantee that N-1 refutes.
- **N-4 (nit, service-engineer).** `QueryRequests.years` (QueryService.scala:223-246) is dead in production, kept for tests; its comment ("the same context the rag planner uses") is stale.

## Round-1 items
- M-1 fixed except for N-1. `planWindows` (QueryService.scala:252-286) matches `windowsOf` (Planner.scala:366-402): patterns, `(?i)`, priority (between, dash/to/through, since, one-year cue, all-time), overlap fold, text-order sort, `idPattern` stripped first. `request.window` is `parsed.window` (:370). Inputs (a), (b), (c), "overall", "all time", "of 1000", "for 1000" re-traced as 422 or correct. `strayYears` count misclassification cannot cause a wrong acceptance. The agreement suite reads the applied window from `/v1/explain` `queryPlan.window`, fails on null plan, any `comparisonWindow`, echo mismatch or other status, and requires at least 8 refusals and 8 applied. Limits: cannot detect over-refusal (any 422 accepted) and does not cover shapes outside its table (sub-1000 windows).
- m-1 fixed (Http.scala:168-174 logs thread name and class only; cannot leak or rethrow; tests prove class logged, canaries absent, no stack frames, pool keeps serving; System.err restored in `finally`, see N-2).
- m-8 fixed (async interrupt, sync path with a thrown InterruptedException, Downstream fatal log).
- m-3, m-4, m-6, m-7, n-1 fixed. m-4: the audit-conflict test (Phase11FaultSuite:186) still accepts 500 or 503 (out of scope, unchanged). m-5 fixed apart from N-3; the hadoop-engineer owner assignment of the four `.fail` defects is the test-engineer's assumption, orchestrator to confirm.
- m-2 accepted residual (D8); the 503 is now pinned exactly.

## D9
Unlikely to have corrupted final evidence (run 2 had already printed its failure and wrote no marker; run 3 exit 0 and the later sequential test-engineer `sbt quick` exit 0 reflect clean state). Residual: Zinc analysis or `target/` races could leave stale classes; the human's `sbt clean check` resolves that.

## Ownership
No violation found.

## Pending
The human's `sbt clean check`, the Spark slow suites since the repair, AWS-profile tests (D1). Not verified by the reviewer: counts, exit codes, the actual diff, whether services and root suites run concurrently in one JVM, slf4j-simple behavior, JDK HttpServer behavior on an escaped Error.
