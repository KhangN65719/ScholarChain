# Phase 11 review, round 3 (fresh code-reviewer, Opus, read-only): APPROVE, with open residuals (as received, condensed)

Re-review of repair round 2 only. The reviewer used Read, Grep and Glob and ran no commands. The repair budget of 2 rounds is used; findings below are recorded as open for the human.

## Round-2 items
- **N-1 (major) FIXED.** QueryService.scala:327 appends `f"$question from ${w.startYear}%04d-${w.endYear}%04d"`; pass-unchanged branch at :331-334 passes a question only when `stray` is empty and the planner-read spans equal `Vector((start, end))`; `request.window` is `parsed.window` (:370). Re-traced against Planner.scala:85-94, :152, :366-402: all six windows ({1,2009}, {500,1500}, {999,2009}, {1,9999}, {1000,2009}, {2000,9999}) are read as the echoed window. "in 0500-1500" passes only with {500,1500}; "in 0999" only with {999,999}; "since 0500" only with {500,9999} ({500,500} is 422, tested at Phase11Suite.scala:374). Length check at :328 against `MaxQuestionChars` (2000), the same limit as Workflow.scala:255. `\b` boundaries are safe: the clause starts with a space; "from" breaks any cue adjacency. Tests: Phase11Suite.scala:339-403 (padding for six windows, sub-1000 pass-or-refuse, HTTP echo and `queryPlan.window` with no `comparisonWindow`).
- **N-4 FIXED.** Comment at QueryService.scala:223-226 says test-only; callers only in Phase11Suite.scala:173-191; still public dead code (accepted option).
- **N-3 FIXED.** ADVERSARIAL_COVERAGE.md:38 says 13 of 13; line 46 limits the guarantee to tested shapes. Nit: heading on line 1 still says "updated in repair round 1".
- **N-2 PARTLY FIXED; acceptable for the gate if recorded as open.** `StreamSwapLock` (Phase10Http.scala:243-287) is a fair binary semaphore; `LogTap` takes it in the constructor and releases once in `close()` (AtomicBoolean, restore in try/finally). No deadlock or leak; `P10Suite.afterAll` (:453-455) closes the tap. The only `new LogTap` is in `P10Suite.beforeAll` (:444); no suite opens a second tap on one thread. Residual: Phase11RepairSuite.scala:31-39 swaps `System.err` under a private lock, and root and services tests run in the same sbt JVM unforked (build.sbt:208-219, :338-348). The services side cannot pass vacuously (positive checks sit beside the canary-absence checks). The root side can in a narrow race: a root tap opening during a `tapped` block gets the services buffer as `oldErr`; afterwards it stops capturing stderr and `System.err` is left on a dead buffer; root log checks (Phase10SecuritySuite:78-80, Phase11FaultSuite:438-447, :90, :189) are negative-only and would pass unchecked. Low probability; overlap under `quick` unverified. Suggested later fix (test-engineer): write a sentinel to `System.err` and assert it appears in `tap.text` before each negative assertion; alternative: fork the services tests (build-engineer).

## New findings
- **R-1 (minor, test-engineer).** The suite-wide `StreamSwapLock` with a 120 s acquire timeout is a latent loud flake: each of the 11 `P10Suite` subclasses holds it for its whole life (Phase10Http.scala:248-257, :443-455), so P10 suites run one at a time and a queued suite waits for the total time of those ahead. If those exceed 120 s, `beforeAll` throws `AssertionError("...held by another LogTap...")` and the suite errors (loud, not vacuous; current evidence 962 passed, 0 failed). Fix: take the lock per log-checking test, or scale the timeout.
- **R-2 (nit, test-engineer).** Over-refusal is checked only for the no-window question: Phase11WindowAgreementSuite.scala:158-198 still accepts any 422 for the matching-window edge rows (:116-117); :207-230 enforces 200 only for `noWindowQuestion`. The reviewer's trace says matching rows return 200, but no test forces it.
- **Observation.** A three-digit year in the question ("in 500") plus window {500,1500} composes to "... in 500 from 0500-1500"; the planner applies (500,1500) and the echo matches; the question's own "in 500" is ignored, as without a window.

## Ownership
No violation.

## Validation evidence (supplied, not re-run by the reviewer)
Service-engineer `sbt quick` exit 0 at 1:28:27 AM (services 118). Test-engineer `sbt quick` exit 0 at 1:31:49 AM (962 passed, 0 failed). Last `sbt check` exit 0 at 12:57:44 AM, before repair rounds 1 and 2.

## Pending
The human's `sbt clean check` (also reruns the Spark slow suites, not rerun since the repairs; no Spark source changed). AWS-profile tests (D1). N-2 residual, R-1 and R-2 recorded open.

## Could not verify
Counts and exit codes; whether root and services groups overlap in one JVM under `quick` or `check`; P10 suite durations (decide how close R-1 is to firing); slf4j-simple resolving `System.err` per write; the actual diff (files on disk read).
