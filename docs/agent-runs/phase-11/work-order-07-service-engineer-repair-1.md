# Work order 07: service-engineer, repair round 1 (M-1, m-1, m-8)

Owner: service-engineer (modules/services/**). Date: 2026-11-09. Input: review-round-1.md (M-1, m-1, m-8), report-02, decisions D5.

## Tasks

1. **M-1.** Make the composed question and the planner agree. Orchestrator ruling (conservative, scope note): when a structured window is supplied, a question year the services cannot prove the planner will read the same way is refused with 422 `UNSUPPORTED_CONSTRAINT`, never reinterpreted. Preferred design: use the planner's own window semantics. Check whether `Planner.windowsOf` (modules/rag/.../Planner.scala:366-402) or an equivalent is public to services. If it is, use it: append the window clause only when the question yields no window; pass the question unchanged only when it yields exactly one window equal to the structured (start, end); otherwise 422. If it is not public, do not edit modules/rag: report BLOCKED-ON rag-engineer for that item, and meanwhile apply the strict fallback inside services: with a structured window, any 4-digit year-like token (1000 to 9999) in the question that is not a count (per the existing count-cue rules) makes the request 422 unless the question is an exact closed-range form ("Y-Z" or "between Y and Z" or "from Y to Z") whose bounds equal the structured window. "since Y", "in Y" (single year), "before/after/until Y" and "and Y" fragments with a window are 422. Without a structured window nothing changes. Record the choice and its cost in the report.
   Must hold: (a) "...have since 2015?" with window (2015, 2015) is 422, or passes only if the planner would truly apply (2015, 2015); (b) "...in 2005 and 2009?" with window (2005, 2009) is 422 or equivalent-proven; (c) "...in 1850?" with window (2000, 2009) is 422; "...in 2015 publications" with a window is not silently rewritten; "top 1000 coauthors" with a window is still 200.
   Correct Phase11Suite.scala:208-214 (it pinned the wrong behavior) and add module tests for (a), (b), (c) and the count cases. The response's `request` echo must always equal the window actually applied.
2. **m-1.** Install an UncaughtExceptionHandler in `Threads.daemon` (Http.scala:157-162) that logs the class name only (no message, no stack, no cause message) to the same log path as the other fatal logs. Add a test that a worker thread dying with an Error whose message holds a canary secret produces no canary in the captured log and does not hang the server. If stderr capture is needed, redirect `System.err` in the test, restore it in a finally.
3. **m-8.** Add tests for the `InterruptedException` branch of `JobService.execute` (job fails, interrupt flag restored, no result), the sync-path `case t: Throwable` for an InterruptedException (key released), and the `Downstream` fatal log line (class logged, message absent).

## Constraints

Local only. No AWS, no new dependency. Edit modules/services/** only (and report BLOCKED-ON rag-engineer instead of editing modules/rag). Do not edit tests/**, CLAUDE.md, docs/agents.md, .claude/**, build.sbt. Do not weaken or delete tests; the Phase11Suite correction is an expectation fix: list before and after. Run bare `sbt quick` in the background, read its output file; never two sbt at once; 15 minutes of silence means stop and report a hang. Read-only git. No commit.

## Report

Standard contract, complete in the final message: pre-code test mapping, changed expectations, wire changes, per-suite counts, exact commands and exit codes, and a statement of which design (public windowsOf or the fallback) was used.
