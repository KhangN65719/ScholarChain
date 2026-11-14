# Work order 04: documentation-engineer, us-east-2 and account facts in docs

Owner: documentation-engineer (README.md; docs/** except agents.md, agent-runs, incidents, releases, dependencies). Do not edit infra/**, scripts/**, tests/**, CLAUDE.md, docs/agents.md or `.claude/**`. No commit.

Read first: docs/agent-runs/phase-14/{decisions.md (D1, D5, D6, D7),evidence-availability-us-east-2.md}, README.md and every file under docs/ you own (read them; the guard blocks search commands).

Task:
1. Replace us-east-1 with us-east-2 where it describes the deployment target. Leave historical evidence and quoted external sources alone.
2. Replace the old account and budget notes with D1: new project account, Paid plan, USD 20 credits, AWS-enforced USD 20 spend limit with pause idle resources, AWS Budget USD 25 with alerts 10/15/20, Region locked to us-east-2 (never activate advanced features). Do not put any email address, account ID or credential in docs.
3. Haiku default model ID is the `us.anthropic.claude-haiku-4-5-20251001-v1:0` profile (D5). Cross-Region routing is Bedrock-side.
4. Do not invent measured results. Cost and Neptune/EMR availability are unverified until the human's checks; say so.
5. List any file you could not read fully. Verify links in files you edit.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS, ASSUMPTIONS, RISKS, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
