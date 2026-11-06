# Phase 1 repair: Claude Code orchestrator

Status: constitutional text updated; independent review, human ratification and fresh-session probes PENDING.

## Finding

The Phase 1 restart results left three gates failed or open: `terraform destroy` interception, `.env` read denial and Stop enforcement. The orchestrator runtime used for Phases 0 to 3 did not load `.claude/settings.json`, so the checked-in hooks and permission rules never ran natively. The course specification requires a main Claude Code session as orchestrator.

## Change

| File | Change | Authority |
|---|---|---|
| `.claude/agents/factory-engineer.md` | Names the main Claude Code session as orchestrator; reports unenforced guards as open until a fresh-session probe observes them. | Phase 0 seed (human architect) |
| `CLAUDE.md` | Fresh Claude Code session or `/clear` per phase; orchestrator delegates and never does specialist work inline; MCP servers approved individually; hooks run natively, with `agent_type` gaps recorded as open. | factory-engineer territory |
| `docs/agents.md` | Dispatch paragraph rewritten for the Claude Code orchestrator. Ownership, routing and escalation tables unchanged. | factory-engineer territory |
| `.claude/hooks/probe_policy.py` | Probe label renamed only. Guard logic unchanged. | factory-engineer territory |

No tool list, ownership row, permission rule, deny rule or hook behavior changed.

## Validation run with the change

- `grep -rni codex CLAUDE.md docs/agents.md .claude .mcp.json` returned no matches.
- `python3 -m json.tool` parsed `.claude/settings.json` and `.mcp.json`.
- `bash -n` passed for all five hook scripts; `py_compile` passed for the Python hooks.
- `.claude/hooks/validate-agent-files.sh` exited 0: 18 agents, 18 ownership rows, 18 lifecycle routes, 5 commands.
- `.claude/hooks/probe-policy.sh` exited 0.

These are direct script checks. Native enforcement is verified only by the fresh-session probes.

## Remaining gates

1. Fresh Claude Code session: dispatch `code-reviewer` on this repair's commit range and save `review.md` here. Route findings to factory-engineer and record them in `repairs.md` here.
2. Human architect records the decision in `ratification.md` here.
3. Restart Claude Code and run [restart-probes.md](restart-probes.md). Save `restart-results.md` here.
4. Record `claude --version`, model, and local tool versions in `versions.md` here.
5. Revalidate Phases 2 and 3 using their `revalidation.md` checklists before starting Phase 4.

The original Phase 1 records are kept unchanged as historical evidence.
