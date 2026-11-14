# Work order 03: test-engineer, us-east-2 in the script harness

Owner: test-engineer (tests/**). Local only. Do not edit scripts/**, infra/**, CLAUDE.md, docs/agents.md or `.claude/**`. No commit. Run `sbt quick` only if you change Scala tests; never two sbt at once.

Read first: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-14/{decisions.md,report-01-infra-engineer-us-east-2.md}, tests/scripts/** (run.sh and fixtures), scripts/lib.sh (read only: `SC_APPROVED_REGION`, `SC_REGION_LOCK_NOTE`, `check_tfvars_region`).

Task:
1. Find every us-east-1 assertion, fixture or approval record in tests/** (read the files; the guard blocks search commands) and update it to us-east-2 only where the test models the account's approved Region.
2. Add cases: a plan/deploy/run refuses `us-east-1` (and any non-us-east-2 Region) with the D1 note; `check_tfvars_region` refuses a var file with another `region` and with an AZ outside us-east-2 and accepts us-east-2a/b; the approval-record Region mismatch refusal.
3. Add one case that the default Haiku model ID in the Terraform default is the `us.anthropic.claude-haiku-4-5-20251001-v1:0` profile if that is checkable from a plain file read.
4. Do not weaken or delete any existing assertion. A flipped expectation needs a one-line reason in the report.
5. Another order (02) is changing scripts/lib.sh cost ceiling from 25 to 20 in parallel. Do not hard-code the ceiling value without reading the final lib.sh; if it is mid-edit, note which cases depend on it.

The harness is run by the human (scripts and bash are blocked for agents, Phase 13 D11). Report exactly which cases you added or changed and the expected pass count; do not claim a pass you did not observe.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
