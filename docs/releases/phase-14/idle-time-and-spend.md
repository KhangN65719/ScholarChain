# Phase 14 idle time and spend arithmetic

Record author: deploy-engineer (WO 115 repair round). Records only. Nothing was run to produce this file and no billing data was read.

This is arithmetic on constants written down in the repository and the run records. It is NOT a bill and not an estimate of record. Actual AWS billing is owed by the human and is the only cost figure to quote. I make no statement about whether the total is below any operating stop or limit; only billing settles that.

## Method

For each session window (deploy approval of the first up deploy to the approval of the teardown that ended it), idle hours are the window length minus the elapsed hours of the runs inside it. Run time is already priced by each run's own burn-times-time estimate, so it is not counted twice. Idle hours are priced at a burn constant. Total = run estimates + idle cost.

Approval times stand in for apply times. An up deploy bills from a little after its approval (resource creation takes minutes) and a teardown bills until its destroy ends (down-5's writer destroy alone took 8m7s), so each window carries an error of roughly a quarter of an hour either way.

## Inputs (each with its source)

Session windows (UTC; approval times from `deploys-earlier.md` and the five release records, which come from `approvals/*.env`):

| Window | Up approval | Down approval | Length (h) | Runs inside |
|---|---|---|---|---|
| A: session 5 to down-1 (sessions 6 and 7 re-applied inside it) | 2026-11-14T20:44:30Z | 2026-11-15T03:53:46Z | 7.154 | runs 1, 2, 3 |
| B: session 8 to down-2 | 2026-11-15T04:28:49Z | 2026-11-15T05:50:17Z | 1.358 | run 4 |
| C: session 9 to down-3 | 2026-11-15T07:05:36Z | 2026-11-15T08:31:24Z | 1.430 | run 5 |
| D: session 10 to down-4 | 2026-11-15T19:02:10Z | 2026-11-15T21:20:37Z | 2.308 | run 6 |
| E: session 11 to down-5 (sessions 12 to 15 re-applied inside it) | 2026-11-16T02:29:16Z | 2026-11-16T22:17:52Z | 19.810 | runs 7 to 11 |
| Total | | | 32.060 | |

Run elapsed times and per-run estimates (burn times time):

| Run | Elapsed (min) | Estimate (USD) | Burn used (USD/h) | Source |
|---|---|---|---|---|
| 1 | 8.3 | 0.09 | 0.62 | `docs/agent-runs/phase-14/decisions-d29.md` lines 4 and 5 |
| 2 | 9.4 | 0.10 | 0.62 | `decisions-d29.md` line 8 |
| 3 | 37.4 | 0.39 | 0.62 | `decisions-d30.md` line 4 |
| 4 | 51.3 | 0.53 | 0.62 | `decisions-d32.md` line 4 and line 19 |
| 5 | 57.0 | 0.59 | 0.62 | `decisions-d34.md` line 4 |
| 6 | 87.2 | 0.90 | 0.62 | run record `runs/s-6`, `decisions-d36.md` lines 4 and 7 |
| 7 | 64.9 | 0.29 | 0.27 | run record `runs/s-7`, `decisions-d37.md` line 4 |
| 8 | 47.2 | 0.21 | 0.27 | run record `runs/s-8`, `decisions-d37.md` line 19 |
| 9 | 3.1 | 0.02 | 0.37 | run record `runs/s-9` |
| 10 | 3.1 | 0.02 | 0.37 | run record `runs/s-10` |
| 11 | 4.7 | 0.08 | 0.97 | run record `runs/s-11` |

Runs 1 to 5 total 1.70 USD (0.09 + 0.10 + 0.39 + 0.53 + 0.59). Runs 6 to 11 total 1.52 USD. All eleven: 3.22 USD. Runs 1 to 3 inside window A total 55.1 minutes (0.918 h); run 4 is 0.855 h; run 5 is 0.950 h; run 6 is 1.453 h; runs 7 to 11 total 123.0 minutes (2.050 h).

Burn constants and where they come from:
- 0.62 USD/h: runs with the EMR node, `--hourly-burn-usd 0.62` (`decisions-d31.md:9`, `decisions-d33.md:18`, `experiment-size-s.tfvars:68`). Not used for idle: no EMR cluster runs while idle.
- 0.27 USD/h: loader-only, without the EMR node, with the default 0.5 vCPU, 1 GiB loader (run records of runs 7 and 8; `scripts/run-bounded-pipeline.sh` note "give an --hourly-burn-usd without the EMR node").
- 0.37 USD/h: loader-only with the 2 vCPU, 8 GiB loader (run records of runs 9 and 10). Consistent with 0.27 plus the Fargate delta of about 0.09 USD/h given in `experiment-size-s.tfvars:28-35` (rates from memory, marked UNVERIFIED there).
- 0.97 USD/h: after the writer moved to `db.r6g.xlarge` (run 11 record). `experiment-size-s.tfvars:51-53` gives +0.52 to +0.64 USD/h over `db.t4g.medium` (UNVERIFIED), and `decisions-d38.md:28` calls 0.97 "a guess, not a measured figure". 0.37 plus 0.52 to 0.64 is 0.89 to 1.01.
- The writer resize finished about 21:17:28Z (apply start 21:06:27Z plus the 11m1s modification in `logs/phase14-deploy-15.log`). From then to the down-5 approval is 1.007 h, of which run 11 is 0.078 h. Post-resize idle is therefore 0.928 h, priced at 0.97 USD/h in every case.

## Idle hours

| Window | Window (h) | Runs (h) | Idle (h) |
|---|---|---|---|
| A | 7.154 | 0.918 | 6.236 |
| B | 1.358 | 0.855 | 0.503 |
| C | 1.430 | 0.950 | 0.480 |
| D | 2.308 | 1.453 | 0.854 |
| E | 19.810 | 2.050 | 17.760 |
| Total | 32.060 | 6.227 | 25.833 |

Of the 25.833 idle hours, 0.928 are post-resize (window E) and 24.905 are before the resize.

## Cases (arithmetic only)

| Case | Pre-resize idle rate | Pre-resize idle cost | Post-resize idle cost (0.928 h x 0.97) | Idle total | Plus run estimates (3.22) |
|---|---|---|---|---|---|
| L (low) | 0.27 USD/h | 24.905 x 0.27 = 6.72 | 0.90 | 7.62 | about 10.8 USD |
| H (high) | 0.37 USD/h | 24.905 x 0.37 = 9.21 | 0.90 | 10.12 | about 13.3 USD |
| X (stress, not expected) | 0.62 USD/h | 24.905 x 0.62 = 15.44 | 0.90 | 16.34 | about 19.6 USD |

Case X uses the EMR-run constant for idle time, when no EMR cluster was running. I include it only because every constant above is a human-supplied or from-memory figure, and the true idle rate could be higher than 0.27 or 0.37 if those constants leave out items. It shows how much the answer depends on the constant. It is not a forecast.

The range from the two named cases is about 10.8 to 13.3 USD. The named cases (about 10.8 to 13.3 USD) approach the 15 USD operating stop and the stress case (about 19.6 USD, not expected) exceeds it; only AWS billing settles it. One orchestrator chat figure of roughly 7 to 9 USD for window E alone (the chat figures otherwise counted only per-run estimates), superseded by this table, is not comparable with this total: it covers one window and excludes runs.

## Not covered by this arithmetic

- Bedrock (Titan embedding calls; run 7 alone logged 25,893 embedding calls, `decisions-d37.md:7`) and Bedrock endpoint data processing beyond the hourly rate.
- S3 storage and requests (the S run 6 outputs alone are about 1.6 GB in four prefixes, `decisions-d36.md:6`), S3 Vectors storage and PutVectors requests, DynamoDB, Secrets Manager.
- ECR storage, CloudWatch logs and metrics, Step Functions transitions.
- Data transfer, and NAT (none was in use per the confirm logs; not priced).
- The deploy-3 and session-down-4 pair on 2026-11-14 and any other time before the session-5 approval; capability runs 1 to 3 and their estimates (not read); any idle time with only storage up.
- Whether the burn constants themselves include every session item. I did not derive or verify any of them.
- Storage that remains after down-5 (see `deploy-down-5.md`).

## Sensitivity worth the human's attention

The result moves by about 2.5 USD for each 0.10 USD/h change in the idle rate (24.9 hours times 0.10). The only real figure is AWS billing for the account and period.
