# Work order 03: test-engineer, Phase 11 P-10 (F1 to F6 on the round-3 test repair)

Owner: test-engineer. Scope: `tests/**` only. No production edits (if a service observer is needed for F1, report `BLOCKED-ON service-engineer`; do not edit modules/services). Local only, no commit. Run after work order 02 is reported and accepted, never in parallel with it (one sbt at a time).

Source: docs/agent-runs/phase-11/review-round-4.md (the findings below are condensed from it; read it first). Files: tests/adversarial/Phase10Http.scala, Phase10RepairSuite.scala, Phase10ApiContractSuite.scala, Phase10EarlyCloseSuite.scala.

## Required (F1, F2)
- F1. Prove directly that the oversized body was not processed. Send the 5 MiB on a raw socket with a writer thread sending chunks and the main thread reading. Assert the `HTTP/1.1 401` line arrives while bytes written are well below the declared length. A writer IOException is tolerated only after the 401 has been read. Account for the JDK HttpServer draining up to 64 KiB on close: pick a threshold with margin and say how you derived it. Optional: with `metrics.enabled`, assert the /v1/query counter rose by exactly one 401.
- F2. The replay path must check as much as the normal path. Build a `Resp` from the parsed status, headers and body in `rawHeadAnswer` and call `assertTypedError` (content type, X-Request-Id and X-Trace-Id, message, traceId and requestId, details, leak checks).

## If small (F3 to F6)
- F3. Document the 4 MiB `oversizeDrain` in the helper comment (the "refuse before reading" doc comment is false for 413), or restrict the early-close fallback to head-time refusals (401/403).
- F4. `isEarlyClose`: require broken pipe, connection reset or "fixed content-length". Drop bare "eof" and "closed".
- F5. `rawHeadAnswer`: catch resets as typed failures, parse chunked framing (chunk extensions, all chunks, Transfer-Encoding value), count Content-Length in bytes, keep 5 s timeouts and close in `finally`.
- F6. Phase10EarlyCloseSuite: make the fake server send a 401 and then reset (reproducing "fixed content-length ... Broken pipe"), assert ConnectException on the refused-connection test, close the HttpClient, and add replay-guard tests (early close then 200, 5xx, or hang must FAIL). Run `rawHeadAnswer` end to end at least once.
Anything skipped must be listed with a reason. Do not weaken or delete any existing test.

## Procedure
List which test proves each item before writing code. Run `sbt quick` once and report exact counts, exit status and whether the early-close branch ran. Do not run `sbt check`. Each new test must state how it can fail.

## Report
Contract fields, plus per finding: fixed or skipped, the proving test, and how it can fail.
