# Work order 08: documentation-engineer, README automation section

Owner: documentation-engineer. Documentation only. Do not edit scripts/**, tests/**, experiments/**, infra/**, docs/agents.md, docs/agent-runs/** (except none), CLAUDE.md or `.claude/**`. Do not run commands you cannot observe; you have no Bash tool, so you replay nothing: say so. Do not invent results. Do not commit.

Read first: CLAUDE.md, docs/agents.md, docs/agent-runs/phase-13/{prompt,decisions}.md, report-03, 04 and 06 in the same directory, scripts/README.md, experiments/README.md, load-tests/README.md, README.md (if it exists) and the docs/ tree layout.

## Deliver

1. In README.md (create it if absent, keep any existing content), a section "Reproducible automation (Phase 13)" with: the ten scripts in one table (purpose, needs AWS or not, needs an approval record or not); the exact commands from a clean checkout for the non-cloud path (`bash scripts/check.sh`, `bash scripts/package.sh --dry-run ...`, and the dry-run forms, copied from scripts/README.md, not paraphrased); required environment variable NAMES only; where human approval points are and what an approval record is (link to scripts/README.md, do not duplicate the field table); the exit code table by link; how to run the script test harness (`bash tests/scripts/run.sh`); rollback behavior by link; the cleanup rule (list before deletion, human only, `--i-am-the-human`).
2. A short, labelled "Status" block: which commands have been observed to pass (cite only evidence in docs/agent-runs/phase-13/checks-*.md and the harness result files that exist; today only `bash -n` on the seven Phase 12 scripts and the Terraform fmt, init and validate runs in checks-1.md are evidenced), and which are UNVERIFIED (everything else), deferred to Phase 14 (every live AWS call, plan reproducibility, the plan checksum archive, smoke test against a real bounded query, deployment) or deferred as an open constitutional item (the ratified launcher, decisions D3). Leave unknown results open.
3. Only link to files that exist; check every link and every command string against the files by reading. Mark any README step the Phase 17 reproduction section ("Reproducing the agentic workflow") will own as "Phase 17" rather than writing it now.
4. Do not touch the root "Reproducing the agentic workflow" content other than not contradicting it.

Report which check proves each item of your gate: link check by reading, and command strings compared character for character against scripts/README.md.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
