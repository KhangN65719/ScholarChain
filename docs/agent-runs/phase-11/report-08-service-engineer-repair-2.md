# Report 08: service-engineer (work order 10, repair round 2: N-1, N-4), as received (condensed)

STATUS: COMPLETE. Edits in modules/services only. `sbt quick` exit 0.

## N-1 choice: zero-pad the appended clause
`QueryRequests.compose` (QueryService.scala ~327) now appends `f"$question from ${w.startYear}%04d-${w.endYear}%04d"`. The 422 fallback for start years below 1000 was not used.

Proof from Planner.scala: the dash pattern is `\b(\d{4})\s*(?:-|–|—|to|through)\s*(\d{4})\b` (:368) and the planner calls `.toInt` on the groups (:384), so "0001-2009" gives exactly (1, 2009), and a 4-digit zero-padded string equals its year under `toInt` for every year 1 to 9999. The planner's window check at :87 is `from < 1 || to > 9999 || from > to`, so (1, 9999) and (500, 1500) pass. The appended clause is the only 4-digit span, so `windows.size` is 1. `planWindows` (the services mirror) uses the same `\d{4}` patterns and `.toInt`. The `MaxQuestionChars` check still runs after padding.

Pass-unchanged branch: a question carrying its own window passes only when `planWindows` reads exactly one window equal to the structured one. "in 0500-1500" with structured (500,1500) passes; any different structured window is 422. `strayYears` counts only 1000 to 9999, so a bare "0999" cannot be a stray year; the planner would not read a bare "0999" without a cue; a cued "in 0999" is a planner window that `planWindows` sees. No further gap found.

Required cases {1,2009}, {500,1500}, {999,2009}, {1,9999}, {1000,2009}, {2000,9999}: all applied exactly as echoed; none needs 422.

## N-4
`QueryRequests.years` kept and marked test-only; the stale comment rewritten (production does not call it; `compose` uses `planWindows` and `strayYears`; it is a coarser heuristic and must not gate a request). Kept public because tests/ could not be searched for other callers. No test deleted or weakened.

## Pre-code test mapping (modules/services/src/test/scala/scholarchain/services/Phase11Suite.scala)
- "N-1: compose zero-pads the appended window to four digits" (exact string, including `from 0001-2009`).
- "N-1: a question window below 1000 passes only when it is the same" (0500-1500 against (500,1500), (500,1501), (1,1500); in 0999 against (999,999), (999,1000); "since 0500" against (500,500) is 422).
- "N-1: over HTTP the echoed window is the window the planner applied" (all six windows 200, `request.window` equal to the window sent, `/v1/explain/{id}` `queryPlan.window` equal to the echo, no `comparisonWindow`; no case 422).
- N-4: the existing "P11-S2: only a year in a year context" still exercises `years` unchanged.

## Changed expectations
None.

## Commands and results
Could not confirm no other sbt was running (pgrep/ps refused). Exactly one `sbt quick` started, in the background: exit 0, `[success] Total time: 77 s`, completed Nov 9, 2026, 1:28:27 AM, marker written. Services: "Passed: Total 118, Failed 0, Errors 0, Passed 118" (was 115). Spark: "Passed: Total 62, Failed 0, Errors 0". Last total printed: "Passed: Total 960, Failed 0, Errors 0, Passed 960" (taken as the root aggregate). Other groups not read individually. `sbt check` not run. No commit.

## Assumptions and risks
The planner mirror can still drift from `Planner.windowsOf` (private[rag]); a public reader in rag would remove the copy. The HTTP test tries `/v1/explain` on the fast base and falls back to the slow base. `years` stays public since tests/ was not searched.

## Pending
Test-engineer: add (1,2009), (500,1500), (1,9999) to the Phase11WindowAgreementSuite table. Independent re-review of N-1 and N-4. N-2 and N-3 not in this order. The human's `sbt clean check`.
