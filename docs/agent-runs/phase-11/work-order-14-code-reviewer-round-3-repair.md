# Work order 14: code-reviewer (Opus), review of repair round 3 (changes only)

Read-only (Read, Grep, Glob). Return APPROVE or REQUEST_CHANGES. Do not edit any file. Fresh reviewer. Read docs/agent-runs/phase-11/report-10-test-engineer-m1-race.md first (the author's claims; treat them as claims to check, not facts).

## Context
The human's `sbt clean check` failed one test: Phase10RepairSuite "m1: a 5 MiB body from an unauthenticated client library is refused" (IOException fixed content-length 187, bytes received 0, Broken pipe; Phase10RepairSuite.scala:287, Phase10Http.scala:311). test-engineer classified it as a test-side race (server answers 401 before reading the body and closes; client still writing). The human required: the fix may accept "413/typed response OR connection closed before the body was consumed" but must still prove the server never processed the body, and must not become a pass-anything.

## Scope: only these files
- tests/adversarial/Phase10Http.scala (`isEarlyClose`, `rawHeadAnswer`, `assertOversizedRefused` in `P10Suite`)
- tests/adversarial/Phase10RepairSuite.scala (the m1 5 MiB test)
- tests/adversarial/Phase10ApiContractSuite.scala (the 3 MiB 413 case)
- tests/adversarial/Phase10EarlyCloseSuite.scala (new, 4 tests)

## Check
1. Weakening: can any wrong behavior pass? For example a server that processes the body then closes, a 2xx, a 5xx, a wrong code, an untyped or empty error, a timeout, a connect failure. Is the `isEarlyClose` cause-chain match too broad (substring matches such as "closed" or "EOF")? Is the replay vacuous (it replays the head with 2048 bytes, so it shows the head is refused, not that the large attempt was)?
2. The "server never processed the body" proof: the author admits it is indirect (log marker absent, replay refused, healthz 200, no job or idempotency observer). Judge whether it meets the human's requirement, what a stronger test-side proof would be without editing modules/services, and whether a service-side observer is needed (owner: service-engineer).
3. Raw-socket replay correctness: framing read (Content-Length or chunked, not wait-for-EOF), timeouts, socket closed in finally, charset, header handling, behavior if the server is already refusing at the first byte.
4. Phase10EarlyCloseSuite: does the fake server really force the race, do the negative cases (refused connection, timeout, non-IO exception, bare IOException) assert what they claim, any port or thread leak, any flake risk.
5. The two edited tests: expected codes are right (UNAUTHORIZED for the 5 MiB unauthenticated body, 413 for the authorized 3 MiB), the test was not deleted or reduced, and the 3 MiB case still joins the one-code-per-status check.
6. Interaction with StreamSwapLock and LogTap in Phase10Http.scala: any new nested tap or lock use that could block 120 s.
7. Other large-body tests with the same race: search tests/contracts, tests/end-to-end, tests/adversarial including the Phase 11 suites (Phase11Rig, Phase11FaultSuite, Phase11CarryOverSuite, Phase11ConfigSuite, Phase11ErSuite, Phase11WindowAgreementSuite, Phase11XmlTruncationSuite) and tests/integration for request bodies large enough to exceed the socket buffer (roughly 64 KiB or more) sent through `Http.send` or the JDK HttpClient to a server that may reject before reading. List each with file:line and size, or state none found.

## Report
Verdict first. Findings with severity, file:line, concrete input, impact and owner. A list for item 7. State what you could not verify (counts, exit codes, whether the race fires). Findings are batched; the repair budget is already extended once by the human, so findings are recorded for the human and are not repaired in this run unless the human directs it.
