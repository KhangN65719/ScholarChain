# Report 10: test-engineer, repair round 3 (work order 13), as received (condensed)

Status: complete. `sbt quick` exit 0, root group 966 passed, 0 failed. Phase10RepairSuite 19/19, Phase10ApiContractSuite 24/24, Phase10EarlyCloseSuite 4/4.

## Classification
Test-side race, not a product defect. `Http.send` (JDK HttpClient) buffers a 5 MiB body. The service answers 401 before reading the body and closes. The client was still writing, got Broken pipe, and threw IOException with the 401 unread. No BLOCKED-ON. The agent did not read the full clean-check log; it relied on the work order text and its own first run, where the replay step fired.

## Change (tests/ only)
- Phase10Http.scala: `isEarlyClose`, `rawHeadAnswer`, `assertOversizedRefused` in `P10Suite`.
  - `isEarlyClose` accepts only an IOException whose cause chain names broken pipe, reset, fixed content-length, EOF or closed. It rejects HttpTimeoutException, ConnectException and SocketTimeoutException.
  - The body starts with a unique `M1MARK-<uuid>`. A typed response gets the full `assertTypedError` at the expected status. On an early close, the same request head is replayed on a raw socket with 2048 body bytes and must return the same status and a typed JSON error. Anything else fails.
- Phase10RepairSuite.scala: the m1 5 MiB test uses the helper, expects UNAUTHORIZED. Not deleted or skipped.
- Phase10ApiContractSuite.scala: the 3 MiB 413 case had the same race and now uses the helper.
- Phase10EarlyCloseSuite.scala (new, 4 tests): a fake server that resets mid-body; refused connection, timeout, non-IO exception and a bare IOException are not early closes.

## "Server never processed the body": what is and is not proved
- Marker absent from captured logs; replayed head refused with the same status and typed error; /healthz 200 afterwards; 10 s bound.
- NOT proved directly: no handler ran, no job or idempotency side effect (no observer exists). The replay shows the same head is refused, not that the 5 MiB attempt reached the server.

## Other large-body tests
Same race: only the 3 MiB 413 case (fixed). Not affected: Phase10CourseSuite (20,000 B), the raw-socket m1 tests (at most 2048 B), Phase10SecuritySuite, Phase10GraphBoundsSuite. Not searched: tests/contracts, tests/end-to-end, Phase 11 suites.

## Risks
The early-close branch did not fire in the final run; it is proved by the fake-server tests and the first run only. The replay read fix has been exercised only on the non-race path. Slow suites not run by `quick`.
