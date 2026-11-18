# Phase 16 review 6 verdict (WO-3 part 2, W2-RB5, W2-TR2)

Author: code-reviewer. Received by the orchestrator on 2026-11-18 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: APPROVE.** The 4096 change and the 3.00 ceiling are correct and consistent. Findings 1, 2 and 4 are cheap and should be fixed before the human writes the H approval record (a manifest edit after it needs a new digest).

## Findings

1. **LOW** `experiments/manifests/experiment-h-answers.manifest:37` ("expected about 1.00 USD"), `experiments/README.md:78`: stale expected spend. H-4: input 4931-7080, output 1990-2000 (3 of 4 cut off). At 2000 output, 76 × (0.0065 + 0.010) ≈ 1.25 USD; at 2000-4096, about 1.5-2.1 USD; at temperature 0 a cut-off reply repeats on retry (Q-RK-01 three identical cut-offs), each up to about 0.027. Ceiling and P16-33 gate unaffected; the expected figure informs dropping the 20-question repeat. Owner: experiment-engineer.
2. **LOW** `experiments/h-answers/README.md:24` (about 0.033 USD per call at the 12000-token budget): the budget is checked on request input only (`Model.scala:644,651`), but `CostGuard.worstCase` (`CostGuard.scala:129-139`) adds prompt + schema JSON + 400 characters, so about 0.034-0.035 per call, 0.136-0.14 for 4 calls; fits 0.15 unless the schema JSON exceeds about 16k characters (not measured). The new test (`CostGuardSuite.scala:114-147`) uses `promptId "test-prompt"`. Suggested: a case with `Prompts.Synthesis.id`, `Schemas.SynthesisOutputId` and a 48000-character input asserting the 4-call total ≤ 0.15. Matters if the diagnostic is rerun. Owner: experiment-engineer.
3. **INFO** Deployed service: `AwsBackend.scala:113-114` builds the same client, so deployed synthesis becomes 4096. `ServiceConfig.scala:132` slow-route deadline defaults to 10 s, too short at about 17 s per call; `SCHOLARCHAIN_SERVICES_SLOW_DEADLINE_MILLIS` removed in session 2 (`infra/terraform/SESSION2.md:112`). ALB idle timeout 120 s (`infra/terraform/modules/runtime/main.tf:452`) equals 3 attempts at the 40 s call limit before planner calls. The release record must state the required deadline. Owners: deploy-engineer (release record), infra-engineer (`slow_deadline_millis`).
4. **NIT** Manifest lines 12-14 and `h-answers/README.md:5` still say H needs the diagnostic and WO-3 part 2; manifest line 41 "76 answers, their repeats" double-counts; manifest lines 43-45 broken comment line. Owner: experiment-engineer.
5. **NIT** rag report: the request line is `Synthesis.scala:106`, not 105. Laptop timeouts settled: `LaptopPorts.scala:284` calls `BedrockAnswerModel.sdkClient(settings.region)` with defaults (30 s attempt, 40 s call); H-4 2000 tokens about 8.2-8.7 s, so about 17 s at 4096 (unmeasured).
6. **Process** W2-RB5 report lost (CLAUDE.md requires it, including the test-to-gate mapping); this review checked the files but does not replace it. RB5's own `sbt quick` failed on the old pins; TR2's run (4:17:55 PM) is the only evidence covering both changes. Owner: orchestrator (record acceptance or get a fresh report).

## Verified

- rag: only synthesis changed (`Synthesis.scala:24`, used at `:106`); planner 200 (`Planner.scala:182`); `maxInputTokens` 12000 (`Model.scala:542`); prompt 1.1.0 and schema `synthesis-output/3` unchanged; limit in no hash; `maxOutputChars` 65536 does not bind; no `maxOutputTokens == 2000` pins in `*.scala`.
- experiment-engineer: `SPEND_CEILING_USD` 3.00 in manifest `:73`, JSON `:16`, `run.sh` (`USD_CEILING=3.00` line 59, passed line 104), both READMEs. Burn 0.80, `MAX_MINUTES` 90 unchanged; projected 1.20 < 3.00; within `--cost-ceiling-usd` (≤ 20) and tier S caps (90 min / 64 / 20000). Per call at 4096: 0.007 + 0.02048 ≈ 0.027. CostGuard charges output as `maxOutputTokens` (`CostGuard.scala:139`); 4 worst-case calls about 0.110 (7k input) and 0.130 (12k test case), within 0.15. Call cap 460 unchanged. Sums: ceilings 8.35, projected 6.087; A-G match. Gate 12.73 ≤ 14.
- test-engineer: `P16W2CostGuardSuite.scala:301-331` pins 3.0 in manifest, JSON, `run.sh`, plus `--usd-ceiling $USD_CEILING`; 460 pins and G pins intact.

## Not performed

Any sbt run (incl. `sbt check` slow suites, human `sbt clean check`); git diff against baseline; schema JSON length; real latency at 4096; whether 4096 suffices (schema allows a 1200-character analysis plus 24 claims); full search of `tests/**`.
