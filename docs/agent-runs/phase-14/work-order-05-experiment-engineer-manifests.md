# Work order 05: experiment-engineer, manifests to us-east-2

Owner: experiment-engineer (experiments/**, load-tests/**). Local only: no AWS, no sbt, no commit, no edits to scripts/**, infra/**, tests/**, CLAUDE.md, docs/agents.md or `.claude/**`.

Read first: docs/agent-runs/phase-14/{decisions.md (D1, D6, D8),report-03-test-engineer-region.md,report-02-infra-engineer-followup.md}, scripts/run-experiment.sh (read-only; the manifest REGION check), the six files in experiments/manifests/, experiments/README.md, load-tests/README.md.

Task:
1. Set `REGION=us-east-2` in the six manifests (experiment-{a-baseline,b-burst,c-embedding-backlog,d-query-load,e-retrieval-modes,f-failure-recovery}.manifest). Keep every other field unchanged unless it cites the Region, a 25 USD ceiling, or an old budget.
2. Read the manifests and READMEs for any cost figure or ceiling above 20 USD and report it. Do not raise any limit. By D8, M and L tiers are not approved in Phase 14; keep manifests as drafts and say so in experiments/README.md if that file does not already.
3. Report the new sha256 of each manifest only if you can obtain it without a forbidden command; otherwise say the human computes it with `shasum -a 256`.
4. List remaining us-east-1 under experiments/** and load-tests/** (by reading).

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS, ASSUMPTIONS, RISKS, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
