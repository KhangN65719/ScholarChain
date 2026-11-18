# W2-RB6: experiment-engineer report (review 6 findings 1, 2, 4)

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (formatting condensed).

---

**Status: COMPLETE.** Final bare `sbt quick`: `[success] Total time: 148 s (0:02:28.0), completed Nov 18, 2026, 4:37:12 PM`; `Passed: Total 77, Failed 0, Errors 0` and `Passed: Total 1304, Failed 0, Errors 0`. Evaluation JUnit: 19 suites, 309 tests, 0 errors, 0 failures, 0 skipped. `CostGuardSuite` 20 → 21 tests, all pass. No `sbt check` run.

## Changes
- `experiments/manifests/experiment-h-answers.manifest`: lines 10-14 state the diagnostic and WO-3 part 2 done (P16-28, P16-32, `h4-diagnostic-1`); COST block "about 1.00 USD" → "about 1.3 to 2.1 USD" with arithmetic; line 41 double-count wording removed; `SPEND_CEILING_USD` comment re-flowed; `HOURLY_BURN_USD` comment says 0.80 × 90 / 60 = 1.20 is below expected spend, not a forecast. Values and the 3.00 ceiling unchanged.
- `experiments/manifests/json/experiment-h-answers.json`: not edited (holds values only, none changed).
- `experiments/README.md` H row (line 78): expected spend about 1.3 to 2.1 with the H-4 basis; ceiling and burn unchanged.
- `experiments/h-answers/README.md`: line 5 cites P16-32 and `h4-diagnostic-1`; line 24 worst case as `CostGuard.worstCase` computes it.
- `experiments/evaluation/src/test/scala/scholarchain/evaluation/runtime/CostGuardSuite.scala`: `Prompts`/`Schemas` imports and a new test.

## Arithmetic
- Expected spend, 76 answers, one synthesis call each: input 7k × 1.00/M = 0.007; output 2000 = 0.010 → 76 × 0.017 = 1.29; output 4096 = 0.0205 → 76 × 0.0275 = 2.09. Retries and planner calls not in the range (inside the 3.00 headroom). The 20-question repeat costs about 0.35-0.55 USD, first to drop.
- Worst case, measured from code: synthesis prompt 949 characters; `synthesis-output/3` schema JSON 9104; framing 400; 48000-character input serializes to 48002. Input tokens (48002 + 949 + 9104 + 400 + 3) / 4 = 14614. Per call 0.014614 + 0.02048 = 0.035094 USD; four calls 0.140376 < 0.15 (unchanged).

## New test
"four worst-case calls with the real synthesis prompt and schema and a full 12000-token input fit 0.15": `Prompts.Synthesis.id`, `Schemas.SynthesisOutputId`, 48000-character input; asserts `worstCase` equals the formula, 4 failing calls all admitted, none refused, `guard.usd <= 0.15`.

## Commands
`sbt quick` four times: (1) compile errors in the new test (imports); (2) pass with a temporary println measurement; (3) intentional failure with a temporary `fail()` carrying the lengths into the JUnit XML; (4) final clean pass. Read-only `ls` of test-reports.

## Unverified
949, 9104, 14614 from one temporary measurement, not pinned as literals; pricing rates unverified; the 1.3-2.1 range assumes about 7k input from 4 H-4 calls, retries excluded; `.claude/.last-test-run` not stat-ed (final `sbt quick` ran after the last source edit). Findings 3, 5, 6 not this owner's.
