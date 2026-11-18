# WO-7 report (test-engineer), received 2026-11-18, archived as received

# WO-7 report (test-engineer): READY-FOR-SBT, BLOCKED-ON build-engineer

Nothing has been executed. I don't hold the sbt slot, and the guard refuses `grep`, `awk` and `python3` for me. All counts below are from reading the sources, not from a run.

## 1. BLOCKED-ON build-engineer: exact change
The `root` project compiles `tests/**` and has no edge to `evaluation`. `build.sbt` only aggregates it (lines 180-191). Without the edge, `Test/compile` fails on an unresolved `scholarchain.evaluation`. In `root`'s `.dependsOn(...)`, after `services % "test->compile"`, add:

```scala
    services % "test->compile",
    // evaluation is a leaf; Test-only edge so independent tests under tests/ can reach it
    evaluation % "test->compile"
```

It is Test-only, so it adds no Compile, Runtime or packaged-classpath edge. There is no cycle, because nothing depends on `evaluation`.

WO-4 must also land `scholarchain.evaluation.metrics.{RetrievalMetrics, Bootstrap}` and `scholarchain.evaluation.graph.{GraphChecks, GraphCheckReport}` as in the fixed API.

## 2. Files (tests/** only)
- `tests/adversarial/P16RetrievalMetricsSuite.scala`, 27 tests.
  - Recall@K (7): k=1, 2 and 4, k above the list length, no relevant gives NaN, empty ranking gives 0, irrelevant ids give 0.
  - Precision@K (4): k=2 and 4, k=10 on a short list gives 0.2 because it divides by k, perfect first hit.
  - Reciprocal rank (5): rank 1, rank 3, only the first relevant counts, none gives 0.0, empty relevant gives 0.0.
  - nDCG (10): graded 2/1 gives 0.7967, ideal order gives 1, k=1 gives 1/3, items beyond k are ignored, k above the list, grade order matters, tie neutrality, exponential gain (grade 3 is worth 7), no relevant gives NaN, nothing retrieved gives 0.
  - One sweep checks every metric stays within [0, 1].
- `tests/adversarial/P16BootstrapSuite.scala`, 11 tests.
  - The mean is 10.5 whatever the seed. Same seed gives identical output. A different seed gives a different interval.
  - lower <= mean <= upper, with both bounds inside [min, max].
  - Constant sample and single value give zero width. NaN is dropped and gives the same result as the filtered input.
  - Level 0.99 contains level 0.80. The interval narrows as the sample grows. Width for 1..20 is between 4 and 6.1. Width is roughly order-independent.
- `tests/adversarial/P16GraphChecksSuite.scala`, 17 tests over hand-built export dirs.
  - Clean world: R1-R3, W1-W2, edges E12 and E23, a two-hop path, plus an unreachable status row with null hop columns.
  - Cases: clean gives all zeros; clean has no duplicates; dangling endpoint (1 case); both endpoints dangling (2 case); duplicate researcher; duplicate work does not leak into other tables; no support row; joint count 2 vs 1 support; extra support row; path hop not in edges; status row is never a bad hop; provenance runId mismatch; mismatches counted per row across tables; orphan researcher; orphan work; same report for same seed; sample size 5000 and 0.
- `tests/scripts/p16-wo7.sh`, a standalone harness with 24 cases (part 2 is one case that runs the Python file).
- `tests/scripts/p16/test_load_derive.py`, 22 unittest cases.
- `tests/scripts/README.md`, one WO-7 paragraph appended.

Totals: 55 munit tests, 24 shell cases, 22 Python cases.

## 3. Harness cases
**WO-6, `scripts/smoke-test.sh`.** These cases cut the real `json_field` and the bounded-query decision out of the script and run them on scripted bodies, so no curl, aws or approval record is needed.
- 0.extract (2 cases): the blocks can be cut out, and `json_field` has `abstention-reason`.
- Fail cases (13): abstained true with evidence prints `abstentionReason=NO_EVIDENCE`; abstained true says "an abstained answer is not a pass"; abstained true with no reason prints `none`; abstained false with empty items, with no evidence key, and with null evidence all fail ("0 evidence items"); `abstained` missing, `"false"` as a string, null, and numeric 0 all fail closed; non-JSON body and empty body fail; a free-text reason shows as `unreadable` and still fails.
- Pass cases (2): abstained false with 1 item, and with 3 items. The 1-item case prints `CHECK bounded-query-answer abstained=false evidence_items=1 abstention_reason=none`.
- Every fail case also asserts the replay step is not reached. One more case checks that answer text is never printed.
- Static cases (4): the order is HTTP 200 check, then answer check, then idempotency, then `SMOKE_COMPLETE`; the dry-run WOULD_RUN text names `abstained=false`, evidence and `abstentionReason`; the dry-run text still says "expect 200"; an abstention ends in `die`, and no abstained check is swallowed by `|| true`.

**WO-5, Python file.**
- `load_client.py` against a loopback server, 9 cases: a key on every line; code captured as-is; explicit null gives null; spaces gives `unparsed`, and the free text is never written; absent gives null; non-JSON gives null; 65 characters gives `unparsed` and 64 is kept; a 500 response gives null; `a.b_c-1` is kept.
- Markers, 2 cases: no `PENDING-WO` under `load-tests/` or `experiments/` (excluding `target` and `results`), and the client default is `retrievalMode`.
- `derive_latency.py` bootstrap, 11 cases: off writes no CI file and no `bootstrap` text; off equals an explicit 0; default percentiles follow nearest-rank; turning the option on changes no other output, and `SUMMARY.txt` only gains lines; resamples -1, 1, 39, 10001 and 1000000 are refused with an exit-2 `DERIVE_REFUSED` and no out dir; 40 is accepted; the same seed gives the same bytes, and the default seed is 16; a different seed changes the numbers; intervals bracket the point estimates inside the ok range; error rows never enter the resample.

**Existing stub bodies updated: none.** I could not find them. The guard refuses `grep` and `awk` on the 685 KB `run.sh`. Any `run.sh` case that asserts the old bounded-query WOULD_RUN text ("expect 200" only), or stubs a query body without `abstained` or evidence, may now fail on the human's full run. It should be fixed by reading the new text, not loosened. I did not touch `run.sh`.

## 4. Paste-ready harness command
```
cd /Users/winnguyen/ScholarChain && set -o pipefail && bash tests/scripts/p16-wo7.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-wo7-harness.log | tail -n 60; echo "exit: $?"
```
Ran by: human, or via Claude in chat per P16-5. No AWS and no `AWS_PROFILE`. Part 2 binds loopback only.

## 5. Not verified, and assumptions
- Nothing was executed: no `sbt Test/compile`, no `sbt quick`, no bash, no Python. The munit expected values are hand-computed, and the shell and Python cases are written from the behavior the orchestrator described.
- Export fixture layout is `<table>/part-00000.jsonl` plus a minimal `export-manifest.json` (`sourceRunId`, empty `tables`). If `GraphChecks` checks manifest file entries or sha256, the fixtures need those fields.
- `duplicateIds` is asserted as a sum, so key naming is unpinned.
- Real `path-evidence` edge ids use the `mention-coauthor:v1:` prefix, while `edges-coauthored` uses `coauthor:v1:`. I assume `pathHopsNotInEdges` matches a hop by `edgeId` (or by endpoints). The bad hop fails under either rule. The clean-fixture zero holds only under one of them, so experiment-engineer should confirm the matching rule.
- The no-support fixture leaves `jointCountMismatches` unasserted, since the spec doesn't say whether it counts there.
- The label-rule tests are not written, because no `BenchmarkGen` API was fixed.
- The formatter hook reformatted `P16RetrievalMetricsSuite.scala` after my write. `scalafmtCheck` under `sbt quick` will say whether the other two suites need it.

## 6. Next gate
Build-engineer adds the edge and WO-4 lands the two packages. The orchestrator grants me the slot, and I run `sbt Test/compile` then `sbt quick` and report exact counts. The human then runs the harness command above.

## Orchestrator note (not part of the report)
- The first delivery was replaced by the agent's reply to the Stop hook; this is the second delivery.
- The `BLOCKED-ON build-engineer` edge was routed as WO-1b on 2026-11-18.
- The edge-id prefix finding (`mention-coauthor:v1:` in path-evidence vs `coauthor:v1:` in edges-coauthored) bears on the P15-28 check; relayed to experiment-engineer (WO-4) to define the matching rule explicitly.
- Label-rule tests: to be written in Wave 2 once `BenchmarkGen`'s API is reported.
