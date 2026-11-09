# Review round 1: code-reviewer (Opus), 2026-11-08

Author: code-reviewer. Verdict: REQUEST CHANGES. No blockers, two majors. Condensed by the orchestrator from the reviewer's report (findings, file locations and owners kept; the verified-clean list and per-item prose shortened). The reviewer ran no commands and executed no tests, so every count is agent-reported.

Course gate: items 1, 3 met; 2 met with one gap (m2); 4 met but weakly (m6); 5 met in source, execution agent-reported.

## Major

**M1 (service-engineer, plus spec ruling).** Query constraints are accepted with 200 but not applied. QueryService.scala:147-164, :262-264, :261, :297-303; run() at :305-329.
- `limits.maxResults` is parsed and echoed, never passed to the workflow. `{"question":"...","limits":{"maxResults":1}}` gives 200 with the answer not limited.
- A structured window is silently dropped when the question already holds a 4-digit year: `windowApplied:false`.
- topic, venue and institution filters are accepted with `filtersApplied:false`; Phase10CourseSuite.scala:133-144 and Phase10WireSuite.scala:64 treat that as supported.
- Spec says an unsupported constraint is 422.

**M2 (service-engineer; test-engineer adds the test).** Fast service not isolated from slow; call pool unbounded. LocalRuntime.scala:146-164, Resilience.scala:95, Ports.scala:172-173, Http.scala:340.
- One fixed pool with an unbounded queue feeds workflow, evidence, graph and explain ports; the RejectedExecutionException branch is dead. LocalWorkflowPort serialises with `lock.synchronized`, which ignores `cancel(true)`, so a timed-out call keeps its thread.
- Reasoned, not executed: `calls.poolSize=4`, `fault.workflow=slow`, 6 concurrent POST /v1/query, then GET of a missing evidence ID: expected 404, predicted 504 counted against the evidence breaker, which can open it and make /readyz not-ready.
- Breaks D8 and the work order's thread-bounds item. HTTP executor at Http.scala:340 also has an unbounded queue.

## Minor

- m1 (service-engineer). Http.scala:117-120 comment says bodies are never read for unauthenticated callers; Http.scala:310 drains up to 64 x bodyLimitBytes (4 MiB default) before 401, 404 and 405.
- m2 (service-engineer). Http.scala:300-308 catches only NonFatal; an interrupt at shutdown makes Resilience.scala:108-111 give Failed, then the retry `Thread.sleep` (:147) throws InterruptedException, which escapes, leaving the exchange unclosed with no error body or trace ID.
- m3 (service-engineer; spec gap). QueryService.scala:493-497: GET /v1/jobs/{id}/result for a failed job rethrows the job's stored status (a timed-out async job gives 504 on a GET that itself completed).
- m4 (service-engineer). ServiceConfig.scala:106, GraphService.scala:70-76: cursor secret not validated; an empty value makes SecretKeySpec throw (500 on cursor issue or check); a 1-byte secret is accepted.
- m5 (boundary decision). GraphService.scala:166-201 does multi-hop BFS and orders by (distance, -jointPublicationCount) in services, contradicting the "never retrieves, ranks" wording at QueryService.scala:278-282. Gate item 1 still holds (not in routes).
- m6 (test-engineer). Phase10WireSuite.scala:35-52 and `conformsSomewhere` in Phase10Http.scala:205-207: examples too thin. Unfrozen: Location, statusLink, neighbors, total, truncated, depth, limit, edgeTypes, evidence graphPath, textPointer, content.
- m7 (test-engineer). Phase10EndToEndSuite.scala:57-61: explain prompt-ID check always passes because ExplainViews lists every prompt (EvidenceService.scala:88-96). Assert `calledInThisQuery == true` for the synthesis prompt on an answered query.

## Nits (service-engineer)

- ErrorCode.IdempotencyInProgress (Errors.scala:17) unused.
- Replayed 202 always says "state":"queued" (QueryService.scala:270-276).
- Idempotency fingerprint hashes raw bytes (QueryService.scala:230-231); reordered keys give 422. Document it.
- A job rejected at submission is left as Failed with a job ID the client never receives (QueryService.scala:467-471).
- 404 and 405 are decided before authentication (Http.scala:123-138), so an anonymous caller can map routes.

## The four weak assertions

Contract nulls: no defect hidden (see m6). Explain prompt IDs: no defect found (see m7). 404/405 collapse: no defect (distinct codes, Allow header present). Cursor with dropped filter accepts 200 or 400: code always gives 400 (GraphService.scala:216-218); tighten the test to 400 (test-engineer).

## Verified clean in source (summary)

Auth (SHA-256 plus MessageDigest.isEqual, 401 versus 403, redacted tokens, no secrets in logs); request limits (413, 415, path and key patterns, duplicate keys, URI cap); no user text in store queries (parameterised Cypher behind AllowlistedNeptune); Phase 9 invariants (AnswerResponse passed through, no claim rewrite, abstention preserved, no hidden reasoning in explain); resilience (retries only on idempotent reads, 0-2, 504 versus 503, shutdown order); dependencies (JDK, slf4j, uPickle, core, rag, storage only; no AWS SDK; root edge Test-only at build.sbt:184); route table and status mapping match spec.md plus an extra 503 OVERLOADED; timeout defaults fast 2000 ms, slow 10000 ms, ready 1000 ms (local 1500, 3000, 500), retries 2, backoff 20 ms, breaker threshold 5, open 5000 ms; override keys documented at LocalRuntime.scala:8-36.

## Pending (not verifiable by the reviewer)

All test counts and sbt exit codes; whether build.sbt differs from HEAD only by the edge and project/DependencyVersions.scala is unchanged (reviewer could not run git); planner behaviour with "overall" plus an appended window (inferred); unowned untracked files AGENTS.md, .agents/**, .codex/**, docs/agent-runs/phase-06/readback-paths.py (keep out of the Phase 10 commit).
