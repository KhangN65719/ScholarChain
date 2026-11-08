# test-engineer report: network-scan scope and labeling/exporter checks

Author: test-engineer (archived by the orchestrator, as received in summary). Date: 2026-11-07. Owner of tests/** only; no production edits, no commit.

## Result
`sbt check` (agent's own run) exited 0. Passed/failed/errors by module, from the `[info] Passed: Total` lines of the final run: core 41/0/0, entity-resolution 130/0/0, tests 190/0/0, hadoop 170/0/0. Total 531 passed. Output was truncated in the Bash tool; the final run's output was saved to a file and the totals read from there. This is the agent's evidence, not the recorded phase gate (the human's `!sbt check`).

## Failing test fixed
`AdversarialErLlmSuite` "the adjudication module reaches no network" scanned all of `modules/entity-resolution/src/main` and flagged the loopback `LabelServer`. It now scans only `scholarchain/er/matching` and `scholarchain/er/llm`, asserts both directories exist, and keeps the full forbidden list (`java.net`, `HttpClient`, `okhttp`, `sttp`, `requests.`, `URLConnection`, `Socket`). Nothing loosened.

## Files
- Modified `tests/adversarial/AdversarialErLlmSuite.scala` (18/18)
- Modified `tests/adversarial/AdversarialLeakageSuite.scala` (6/6: 3 added, 3 kept)
- Created `tests/adversarial/AdversarialErLabelingSuite.scala` (8/8)

## New coverage
- Labeling (`eval/labeling/**`) and exporter (`sampling/**`, `pairs/**`): no outbound connection types; only 127.0.0.1/localhost named; `java.net` limited to InetAddress, InetSocketAddress, URI, URLDecoder; every bind uses the loopback address; no 0.0.0.0/[::]; `LabelPage` loads no remote script/link/img/iframe; exporter has no `java.net`.
- Leakage: `matching` and `llm` reference none of `scholarchain.er.{eval,labeling,sampling,review}` nor 12 named types (GoldLabel, GoldEntry, GoldSet, LabelSession, LabelServer, LabelPage, LabelNames, SuggestionSet, PairsExport, Stratifier, ReviewQueue, Evaluator).
- Note: the older vocabulary scan exempted `eval`, so the labeling tool was not covered before; the cross-reference test closes that.

## Risks
Source-text scans only (no reflection or dynamically built URLs); package-path assumptions fail loudly; the two remote-load regexes in the page check do not cover every form. `er/labeling/*.scala` four 78-byte stubs remain until the human deletes them.
