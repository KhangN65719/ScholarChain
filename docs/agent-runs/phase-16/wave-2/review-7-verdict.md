# Phase 16 review 7 verdict (re-review of W2-RB6)

Author: code-reviewer. Received by the orchestrator on 2026-11-18 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: APPROVE.** Review 6 findings 1, 2 and 4 closed.

1. Finding 1 closed: manifest `:37-47` and `experiments/README.md:78` give about 1.3-2.1 USD; checked 0.017 × 76 = 1.292, 0.02748 × 76 = 2.088, repeat 0.34-0.55; retries and planner calls excluded and stated as inside the 3.00 headroom.
2. Finding 2 closed: `experiments/h-answers/README.md:24` matches `CostGuard.worstCase` (`CostGuard.scala:129-139`): (48002 + 949 + 9104 + 400 + 3) / 4 = 14614; 0.035094 per call; 0.140376 for four ≤ 0.15. The 949 and 9104 figures are the owner's measurement (not recounted); the budget holds until the schema JSON passes about 18.7k characters.
3. New test `CostGuardSuite.scala:151-177` uses the real `Prompts.Synthesis` and `Schemas.SynthesisOutputId` (`Model.scala:73,86,94`); the formula assertion repeats the implementation but fails if a lookup drops to 0; the real check (4 calls admitted, 0 refused, `usd <= 0.15`) depends on the real schema size. No lower bound on lengths pinned (acceptable).
4. Finding 4 closed: manifest `:10-14`, `h-answers/README.md:5` cite P16-28, P16-32, `h4-diagnostic-1`; double count gone (`:19-20`, `:41`).

Manifest values unchanged: `experiment-h-answers.manifest:68-85` equals `json/experiment-h-answers.json:5-22` key for key (`SPEND_CEILING_USD=3.00`, `MAX_TASKS=460`, `HOURLY_BURN_USD=0.80`, `MAX_MINUTES=90`, `MAX_CONCURRENCY=1`, the other 11 identical). The comment edits change the manifest sha256: the H approval record must bind the current digest.

Optional nits (experiment-engineer): `:47` over-long comment; `:48-50` mid-sentence break; `:57-60` "0.80 x 90 / 60 = 1.20" stated twice.

Outside scope, still open from review 6: finding 3 (release record and slow-route deadline: deploy-engineer, infra-engineer; recorded as OD-4); finding 5 (rag report line 105 vs 106: recorded here as the correction, the request is at `Synthesis.scala:106`); finding 6 (lost W2-RB5 report; recorded as OD-5).

Pending: the human's `sbt clean check` at the phase gate; the H approval record against the new digest.
