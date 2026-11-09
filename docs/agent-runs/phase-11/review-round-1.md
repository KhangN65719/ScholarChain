# Phase 11 review, round 1 (code-reviewer, Opus, read-only): REQUEST_CHANGES (as received, condensed)

One major, verified. Minor findings batched into one repair round. The reviewer had no git or sbt access.

## M-1 (major, service-engineer; rag-engineer only if the planner's window parse must be exposed)
Structured window echoed as applied while the planner answers a different window. Phase 11 turns one case Phase 10 refused (422) into a silently reinterpreted answer.
- Location: modules/services/.../QueryService.scala:211-246 (`years`), :254-268 (`compose`); must agree with modules/rag/.../Planner.scala:366-402 (`windowsOf`) and :293 (two or more windows means TemporalComparison).
- Cause: `compose` compares the set of 4-digit years to {start, end}, ignoring cue meaning. The planner reads `since Y` as (Y, 9999), reads single years only after in, during, for, of and year, and never reads `and Y`, `from Y`, `before Y`, `after Y`, `until Y` on their own. Services counts all of those.
- Inputs: (a) "How many distinct coauthors does Ada Quillfeather have since 2015?" with window {2015,2015}: passes unchanged, the planner applies 2015-9999, the response echoes 2015-2015. Pinned by Phase11Suite.scala:208-214. (b) "...in 2005 and 2009?" with window {2005,2009}: passes, the planner reads only "in 2005". (c) Regression: "...in 1850?" with window {2000,2009}, 422 in Phase 10, now composed to "...in 1850? from 2000-2009", answered as TemporalComparison; same for "...in 2015 publications".
- Impact: wrong answer window with a misleading `request` echo, contradicting QueryService.scala:250-253 and :323-327 ("a window is applied or the request is refused"). D5 and P-2 did not record the silent operation change; the conservative option is 422.
- Fix direction: decide using the planner's window semantics. Append the window clause only when the question yields no window, pass the question only when it yields exactly `Vector((start, end))`, otherwise 422. Correct Phase11Suite.scala:208-214, add tests for (a) to (c).

## Minor
- m-1 (service-engineer): a fatal Error's message reaches stderr unredacted. QueryService.scala:558-563 and Http.scala:396-397 rethrow on threads from `Threads.daemon` (Http.scala:157-162), which sets no UncaughtExceptionHandler; the JDK default handler prints the message and stack. Redaction tests read only the log tap. Fix: install a handler in `Threads.daemon` that logs the class name only.
- m-2 (rag-engineer or storage-engineer; residual): Workflow.scala:199-219, Retrieval.scala:45-57 and Neptune reads catch only `StorageException`; a `TransientStorageException` gives 503 DOWNSTREAM_UNAVAILABLE instead of an abstention. Typed, no leak, key released. Name as a residual risk.
- m-3 (test-engineer): Phase9RepairSuite.scala:316-334 ("M1: the honest wording ... is Verified") can pass vacuously: `added(r)` is the last claim; the stub's own last verified metric claim satisfies it. Assert claim count is the clean run's plus one, or compare to `ClaimDraft.systemId`.
- m-4 (test-engineer): Phase11FaultSuite.scala:69-102 and :104-121 accept a 200 abstention or any of 500, 503, 504; the :104 test titled "abstains with no claim" passes on a 5xx. Current behavior is known (503 for a timeout, an abstention for `StorageException`): assert it. Matrix row 15 overstates.
- m-5 (test-engineer): tests/ADVERSARIAL_COVERAGE.md is stale. Row 17 still says D1 fails (fixed). Rows 6 and 8 say pending `sbt check` (ran). Row 3 and row 10 omit the carried defects pinned open with `.fail`: AdversarialDuplicateIdSuite.scala:271 and :290, AdversarialMissingFieldSuite.scala:211, AdversarialXmlSuite.scala:69. NAME-only rows: 10, 11, 15, 17, 18, 21 and parts of row 3. Rows with no independent real test: config omissions for Spark, storage and rag (21), workflow missing-metadata gaps (10, module tests only), blocking groups beyond 300 members (5).
- m-6 (test-engineer): Phase11XmlTruncationSuite.scala:78-103, :105-124, :171-180 loop over emitted records without asserting any was emitted (the :46-76 test does assert `emitted > 0`).
- m-7 (test-engineer): Phase11ErSuite.scala:89-100 checks the ORCID merge only inside `if accepted`. Phase11ConfigSuite.scala:84 and :89 use bad values "short" and "short-SE" not in the echo-check list.
- m-8 (service-engineer): QueryService.scala:555-557, the `InterruptedException` branch of `JobService.execute`, is untested; so is the sync-path interrupt claim in report-02 and the `Downstream` fatal log line (Resilience.scala:116-127).
- n-1 (nit, test-engineer): Phase9RepairSuite.scala:629-632 compares a map value to the same literal defined at :605 (tautological); the `/3` literal was copied from a failing run, so it detects later changes only. Acceptable as a pin.

## Checks of the specific items
1. Carry-overs fixed in owners' files (m5 Claims.scala:27-49, n1 Verification.scala:727-808, key release QueryService.scala:466-478 guarded by `eq` at Ports.scala:374-377, wire suite null to 0 at Phase10WireSuite.scala:56). Year fix stops the 422 for "top 1000 coauthors" (but see M-1).
2. Part A edits: positional helpers guarded (size, kind, non-default verdict) except m-3; the two expectation changes follow the number-echo fix and are not weakenings.
3. `SemanticContextAgent` catch correctly scoped (IllegalStateException, fatals and interrupts propagate, pinned at Phase11SemanticDegradeSuite.scala:108-111).
4. Fatal handling: no key leak, no double completion, caller gets typed 503; class-only logging, but not stderr (m-1).
5. Year parser: "papers 2015" and "top 2015" are not years (agrees with planner); "in 1850" and "since 2015 and 2010" misbehave (M-1).
8. Ownership: no violation found.
9. Thin categories acceptable as residual if named: Neptune timeout has no typed storage error (typed 503, no leak, key released); comment and CDATA XML records read as live (carried, `.fail`, hadoop-engineer); blocking-group memory unmeasured (measure before any full-scale run); config omissions for Spark, storage and rag untested; the four carried `.fail` defects. None major.

## Gate
- No critical or major finding remains: FAIL (M-1).
- Every production behavior has a relevant test: FAIL, minor (m-8); M-1 has a test that encodes wrong behavior.
- All tests pass under declared profiles: PASS on agent evidence only (check exit 0 at 12:57:44 AM, after the last source change; 1062 unconfirmed since group lines sum to 1047). Human `sbt clean check` pending. AWS-profile tests not run (D1).
- No reviewer wrote production code: PASS.
- Repair records identify owners and rerun evidence: PASS (stale matrix text is m-5).

## Not verified by the reviewer
The diff against HEAD (no git), test counts, marker write, whether Spark ran, the planner's default window with no named year, whether the JDK HttpServer catches the rethrown Error before the uncaught handler runs.
