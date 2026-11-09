# Report 05: test-engineer, repair round 1 (as received; condensed)

**Status: COMPLETE.** Work order 05. Only `tests/**` changed; no production edits; no new dependency. Bare `sbt quick` exited 0 after the last edit, scalafmt passed. `sbt check` not run. Three quick runs; the first two had the known red. Counts from `target/test-reports/*.xml`; rag module tests and the whole-run `Passed: Total` line not read.

| Suite | Tests | Failed | Errors |
|---|---|---|---|
| `Phase9RepairSuite` (new) | 24 | 0 | 0 |
| `Phase9ModelSuite` | 27 | 0 | 0 |
| `Phase9GateSuite` | 20 | 0 | 0 |
| `Phase9BenchmarkSuite` | 65 | 0 | 0 |

## Changed expectation (only one)
`Phase9ModelSuite` "a model input over the token budget is refused before any call": old reason `synthesis-failed`, new `input-over-budget` (review m3, repaired `Workflow.scala`). Strengthened, not loosened: also asserts 0 synthesis calls, answer says "token budget" and not "0 attempt", audit has `abstained` true and `abstentionReason` `input-over-budget`, and `modelCalls` 0 (planner is rule-based so no model call; agent's first guess of 1 was wrong and fixed). No other pinned wording moved ("withheld", "narrow", "cannot be applied", "No direct coauthor edge between", `Descriptive only`, "differs" all pass).

## New tests by finding (`tests/adversarial/Phase9RepairSuite.scala`; `Phase9World.scala` gained an `injectTitle` option and `InjectedTitle` constant)
- B1a: steered semantic claim citing only a snippet ("direct coauthors ... main bridge between the communities"): text absent from answer, claims and audit; verdict not Verified; surviving text equals `Rendering.SemanticText`; change recorded.
- B1b: "ninety-nine", "one hundred", "thirty coauthors" in semantic claims not shown; metric claims worded with those and "twenty-one distinct coauthors" and asserted value 3 not Verified and not shown.
- B1c: abstention claim with arbitrary wording and "all certain" uncertainty: kept text equals `Rendering.AbstentionText`, status Uncertain, change recorded as "replaced".
- B1d: model analysis paragraph with a structural claim and a spelled number replaced by `Rendering.AnalysisText`, audited; with no kept claim the paragraph is not shown and removal audited.
- B1e: model per-claim and top-level uncertainty carrying numbers appears nowhere in answer, claims or audit.
- M1: Carla Chen asserted distinct coauthors 3, publication count 2: "2 distinct coauthors", "2019 coauthors", "2010 coauthors", "4 distinct coauthors" not Verified and not shown as written; answer states "3 distinct coauthors"; honest wording Verified (positive control).
- M2: injected display name, work title and coauthor name sit only under keys starting `untrusted` in the synthesis input; flagged in `r.flags` and audit as `stored-name:*`/`stored-title:*` like a snippet's `retrieved-text:*`; prompt names `untrustedData`, `knownGaps`, `facts`.
- m1: `AllowlistedNeptune` passes the real catalog query; refuses `coauthors-of` and `edge-evidence` with other read-only text and an unlisted name carrying catalog text; refused queries never reach the delegate.
- m4: all 12 claim kinds accept a minimal valid assertion and reject extra `reasoning`/`thoughts`/`extra` keys; mismatched kind and missing required key rejected; a steered `reasoning` key is retried 3 times (`maxAttempts`), abstains `synthesis-failed`, leaks nowhere.
- m2: literal sha256 pinned per prompt/schema version; pinned (id, version) set equals shipped set; audit record carries the pins; synthesis prompt not 1.0.0 and schema id not `.../synthesis-output/1`.
- m3: bundle filled to `EvidenceCaps` (real metric items, 20 long gaps, maximum-length question) needs at most 12000 estimated tokens; seven large workflow questions never abstain on budget; a forced budget failure has its own reason and is audited.

## Pinned hashes (captured from shipped source)
- prompt `planner-classify` 1.0.0: `408c5c03311abab8f71a49e8d7646d4fb0d1774e75402c9e240a9371c1dce0a1`
- prompt `synthesis` 1.1.0: `40c6597d381abb03df6a26a4b2b2984a5f95df5eb507a26041361d1de7cba7c7`
- schema `plan-output/1`: `b55d8e903f6f2084079c9c7cb66d5bd78623ea1293d534e03a05818ba354503a`
- schema `synthesis-output/2`: `f510dce62095778c1e54ce347e29e72941d7ba86246047c57f087c413d9046b9`
- schema `verifier-report/1`: `e3272063134813c56e480d638e8490ec3e7d34c556c82d8610ce1c6cb0a5e00a`
Round-0 hashes were never recorded (report 02), so a difference cannot be shown; the test asserts only that the version and schema id moved from `1.0.0` and `.../1`.

## Benchmark (still 65 of 65 tests; tables separate in `target/phase9-benchmark.txt`)
Table 1, deterministic graph retrieval 45/45: breadth 5/5, team-size 3/3, direct 4/4, path 10/10, component 3/3, community 3/3, bridge 4/4, temporal 5/5, topic 2/2, institution 2/2, evidence 3/3, lookup 1/1. Table 2, semantic retrieval: index top-k and filter 13/13, workflow chunk-present 4/4.

## Defects routed
None.

## Assumptions and risks
Verifier ignores (does not replace) model per-claim and top-level uncertainty, so no change record exists; B1e asserts only that they do not surface. B1c asserts the reason contains "replaced". The M2 prompt check is a loose keyword check. Leak checks are case-insensitive over answer, claims JSON and audit payload; snippets excluded because they legitimately contain trap digits. Not covered: storage-fake m1 (P11) and the review's non-blocking notes.

## Gate status
`sbt quick` passed (turn evidence). Pending: one agent `sbt check`, round-2 re-review, human `sbt clean check`.
