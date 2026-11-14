# Work order 02: infra-engineer, region migration follow-up

Same limits as work order 01 (local only, no AWS, no sbt, no CLAUDE.md, docs/agents.md or `.claude/**`, no commit). Continue from report-01-infra-engineer-us-east-2.md.

Human decisions (decisions.md D6, D7):
- Cost ceiling in code is 20 USD, not 25 (D6). Change `lib.sh`, the `budget_total_usd` validation, the README text and the COST_ESTIMATE.md gate wording to 20. The AWS Budget of 25 with alerts at 10/15/20 and the human stop near 15 are described in the docs as the outer alert and the operating stop. If an existing estimate or tier now exceeds 20, do NOT hide it: report a table (tier, HIGH bound, over/under 20) as `BLOCKED-ON` human decision. Do not raise `pipeline_max_run_seconds` or the `--max-minutes` caps.
- Replace the stale "$25 and $50 budgets already exist" text in variables.tf and the infra README with the D1 account facts (credits 20, AWS spend limit 20 with pause idle resources, Budget 25 with alerts at 10/15/20).
- Neptune `db.t4g.medium` and EMR `m5.xlarge` in us-east-2 will be checked by the human with read-only CLI calls. Leave the "NOT CONFIRMED" note; add the exact check commands to infra/terraform/README.md.

Also finish what report 01 could not cover, by reading the files fully: scripts/package.sh (all), scripts/archive-evidence.sh (all), the module outputs.tf and variables.tf files you skipped, and every tfvars and overlay. Report any remaining `us-east-1` with file and line. The guard blocks search commands; read the files.

Every edited script changes its SCRIPT_DIGEST; say which approval records are invalidated (none should exist yet).

Checks: the guard refuses them for agents. List the files for the human's `!` run (terraform fmt, init -backend=false, validate; bash -n and shellcheck -x on every edited script).

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
