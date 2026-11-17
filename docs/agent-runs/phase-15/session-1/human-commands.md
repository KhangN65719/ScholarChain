# Session 1 human commands (orchestrator, 2026-11-17)

The paste-ready commands are in **`docs/releases/phase-15/session-1-runbook.md`** (revision 2 plus the review-2 minor fixes; author deploy-engineer; review 1 REQUEST CHANGES, review 2 APPROVE: `runbook-review-1.md`, `runbook-review-2.md` here). That file is the single command source for the session; this file does not duplicate it (a copy would drift, and the guard does not let the orchestrator copy files by shell).

Billing gate: `billing-gate.md` here (3.94 billed, counted 4.50; runbook HIGH 3.68; total under 14 USD).

## Tonight: B only (P15-22), runbook revision 3
The authoritative order is the runbook's top section "B-only path (tonight, P15-22)": 0.1, 0.1b, 0.2a-0.2c, 1.1-1.5, 1.6B, 2.0 (all of it), 3.1 (session-up from the down state straight to `elasticity-s`; about 11 to add incl. 8 interface endpoints, about 3 replaced task definitions, `STATEFUL_CHANGE=no`, `IAM_CHANGES: none`), 3.2-3.11 (B block), 4.1-4.5, 5. A steps (1.6A, 2.1-2.10, 3.11 A block) are marked NOT TONIGHT. B-only HIGH 2.83 USD (B 2.40 + post-stop 0.13 + idle endpoints 0.30); wall-clock limit 4 h from 3.4R, B starts within 1 h 30 min; P15-10 limit billed <= 11.17 (3.94 gives 6.77). The A-only order for tomorrow is `docs/releases/phase-15/session-1b-a-only-draft.md` (HIGH 1.07, limit 3.5 h). Revision 3 was not independently re-reviewed (ordering, numbers and the 3.1 expected-plan text changed; the human's plan read at 3.2 is the check, with its stop conditions).

## Order (full runbook, A and B)
0. 0.1 billing gate; 0.1b preconditions (human confirms the `b-burst` PROPOSED values in the chat) and risk check (`.claude/settings.local.json` ignored and untracked); 0.2a docs-only commit; 0.2a2 optional state backup; 0.2b `git stash push -u -m p15-wave2-wip`; 0.2c hard gate `GATE=PASS`.
1. Read-only pre-checks (identity, state, inputs, manifest copies, digests).
2. 2.0 package and upload (raw-input hash check, stale-jar check); A: plan `baseline-s`, rendered checks, deploy, live checks, A records, dry run, run, results, derive.
3. B: plan `elasticity-s`, rendered checks (ComputeLimits 1/3/3/3, Wait 900, timeouts), deploy, B records, run (90 min / 2.40 USD), collect, derive, redaction copies.
4. Session-down plan, deploy, post-teardown resource check (EMR, ECS, Neptune, EIPs, load balancers, unattached EBS, NAT, endpoints, executions).
4.5 `git stash pop` and `git status --short` (Wave 2 restored; a conflict is a stop).
5. Paste-back evidence (to `/Users/winnguyen/scholarchain-data/logs/` or chat during the session; recorded in the repository only after 4.5).

## Session rules (from the runbook)
S1 no repository writes and no sbt by anyone from 0.2c to 4.5; S2 any stop from 2.4R means session-down unless the human decides otherwise within 15 minutes; S3 session-down starts within 6 h of 2.4R and B starts within 3 h 30 min; S4 emergency cluster termination only through the guarded E1 lines; S5 no persistent permission prompts.
