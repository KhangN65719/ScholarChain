# Evening list, 2026-11-17 (orchestrator; revised after the B-only session, P15-42)

Runbook: `docs/releases/phase-15/session-tonight-runbook.md` revision 4 (session 2 only; being revised and re-reviewed now). Times local (UTC-6). No AWS and no sbt by agents.

1. Section P (B-only leftovers): post-teardown read; B `collect.sh` and `derive-capacity.py derive` (expect `COLLECT_COMPLETE`, `scale_out_observed=yes scale_in_observed=yes` per the 19:31-19:44Z polls).
2. 0.0: decision lines (D1 E modes, D2 allowlist reading, D4 hard stop, D6 smoke path, D7, D8 sbt check or ruling).
3. Billing gate (session 2 only): billed reading + today's unbilled usage (default 3.37: A + B about 1.10, B-only about 2.27) + 4.50 <= 14. At a 5.33 reading: 13.20, GO. If over: drop C, F, E, D in order.
4. E.1 clock and export, E.2 Bedrock, E.3 heap measure (your sbt) and E.3b, E.4 rule, E.5 `service_cpu`/`service_memory` lines.
5. E.6 checks: `check.sh --skip-sbt`, manifest JSON, harness `RESULT pass=1917 fail=0`, load-client rehearsal `pass=45 fail=0`.
6. E.7 tree read (allowlist), E.7b agent `sbt check` if D8 says so, E.8 commit, E.9 gate `GATE=PASS`, E.10-E.16.
7. Part S2 from the down state: A1 plan by 21:10 if D is wanted, 23:05 for smoke only; every experiment ends by 00:55; A4 plan by 01:15.
