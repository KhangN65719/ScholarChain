# Session 1 billing gate (orchestrator, recorded from the human's message, 2026-11-17)

- Billed month-to-date: 3.94 USD (Billing page, 2026-11-16 22:27 local), counted as 4.50 USD for billing lag (human).
- Session 1 HIGH: about 3.15 USD (A up to 0.75 + B ceiling 2.40, P15-16; plan revision 2026-11-17).
- Total: about 7.65 USD, under the 14 USD pre-session rule (P15-10). Human: proceed with session 1 when the commands are ready.
- Runbook step 0.1 run by the human (log `phase15-s1-billing-gate.log`): `billed=3.94`, `session1_high=3.68 total=7.62 -> GO`, `plan_high=3.15 total=7.09 -> GO`, exit 0. Run with the A+B constants before the B-only revision (P15-22); the B-only HIGH is lower, so GO holds.
- Runbook step 0.1b risk check (log `phase15-s1-risk-checks.log`): `.claude/settings.local.json` ignored via `.git/info/exclude:8`, `SETTINGS_LOCAL_IGNORED=yes`, `SETTINGS_LOCAL_TRACKED=no`; `course.tfvars` differs from 085b5ec by 1 line (Wave 2, parked by the stash at 0.2b); exit 0. Precondition satisfied by P15-21.
- Status: session 1 runbook being drafted by deploy-engineer (`docs/releases/phase-15/session-1-runbook.md`); the orchestrator copies it to `human-commands.md` here after reading it. Nothing has run.
