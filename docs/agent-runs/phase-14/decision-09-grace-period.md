# D9: TERM-to-KILL grace period is 5 s (orchestrator proposal, awaiting human ratification)

- Finding: Phase 13 ratification F2 and its work order say 500 ms. scripts/lib.sh uses a 5 s window (`sleep 5` in the run_bounded watchdog; `sc_group_stop` polls 10 x 0.5 s, so 5 s of waiting in 0.5 s steps). scripts/README.md says "5 s" in three places.
- Evidence: real-process run, `RESULT pass=15 fail=0` (phase14-run-bounded-real-1.log); case 6a/6c observe KILL after about 5 s.
- Proposal: code is the source of truth; the constant is unchanged. Only the Phase 13 ratification wording is stale; it is a human-owned record and is not edited by agents. The human decides whether to annotate it.
- Work order 10 (infra-engineer): read lib.sh, scripts/README.md and parts of run-experiment.sh, found no "500 ms" or "grace" text, made no edit. Other scripts, infra/terraform/** and docs/agent-runs/** were not searched (the agent guard refuses grep); a human grep is requested.
