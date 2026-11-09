# Work order 03: test-engineer, independent fixtures, adversarial tests and the benchmark-query suite

Owner: test-engineer (`tests/**`). Phase 9, decisions P2, P8 and Phase 8 F1. Runs after work orders 01 and 02 (sbt-safe order, decision P9) but writes from the specification: `prompt.md`, `decisions.md`, the work orders, and the public APIs, not from the implementers' tests. LOCAL ONLY, no new dependency. No production edits: report defects as `BLOCKED-ON <owner>` with evidence. Do not edit build files; if `tests/` cannot reach a rag or storage type, report `BLOCKED-ON build-engineer`.

## Required work
1. A hand-built fixture world in `tests/` (independent of rag's own demo world): about 12 researchers across at least two windows, a shared-name pair (ambiguity), a mention/unresolved node, two communities with one bridge researcher, a component split, a paper with the maximum team size per researcher, topic and institution vectors, an `unreachable-within-bound` pair and a `different-component` pair. Served through the storage fakes.
2. Benchmark-query suite: at least 30 questions covering coauthor breadth, maximum team size, direct collaboration, shortest paths, components, communities, bridge researchers, temporal change, topics, institutions and evidence lookup. Each has expected deterministic facts computed by hand in the fixture. Report pass and fail per question category. Score deterministic graph retrieval and semantic retrieval separately (two result tables).
3. Adversarial gate tests: (a) question text containing Cypher, AWS CLI, SQL and JSON-escape strings never reaches a query or AWS call, and only allowlisted named templates run; (b) an ambiguous name returns multiple candidates or abstains, never one; (c) every numeric and path claim in answers carries evidence IDs that resolve in the bundle; (d) F1: a claim whose number appears only in a vector snippet is not Verified, and the final answer removes, weakens or labels it; (e) prompt injection inside retrieved snippets and inside researcher display names does not change the plan, tools or verdicts; (f) malformed and oversize model output is retried at most the bounded count, then the answer abstains; (g) a CONFLICTING and a PARTIALLY_SUPPORTED case; (h) context and bundle caps refuse rather than truncate; (i) audit record contains plan, researcher IDs, evidence IDs, metric versions, graph-run ID, prompt and model versions, latency, verdict and answer hash; (j) rerun determinism of answer and hash; (k) no chain-of-thought field in any output.
4. Unreachable and non-path honesty: the agent never claims "no path" for `unreachable-within-bound`.
5. Execute your tests and report exact pass and fail counts per suite. A written but unexecuted test is not evidence. Add the new slow suite names, if any, to your report for build-engineer.

## Commands
`sbt quick` only; never two sbt commands at once; do not run `sbt check`.

## Report
Role-file contract plus: fixture description, question list with expected facts, per-category benchmark results, adversarial results, defects routed to owners. Orchestrator archives it as `report-03-test.md`.
