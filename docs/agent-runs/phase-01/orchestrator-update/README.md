# Phase 1 repair: Claude Code orchestrator

Status: constitutional text updated. The independent review ([review.md](review.md)) and the first re-review ([re-review.md](re-review.md)) both returned REQUEST CHANGES. After repair rounds 1 and 2, the second re-review ([re-review-2.md](re-review-2.md)) returned **APPROVE** for ratification. Its two Low findings are tracked in [repairs.md](repairs.md). N5 must be fixed before the probe run. Human ratification and the fresh-session probes are PENDING.

## Finding

The Phase 1 restart results left three gates failed or open: `terraform destroy` interception, `.env` read denial and Stop enforcement. The orchestrator runtime used for Phases 0 to 3 did not load `.claude/settings.json`, so the checked-in hooks and permission rules never ran natively. The course specification requires a main Claude Code session as orchestrator.

## Change

The range `9ee8b22..8dbd141` changes 8 files. The human architect directed the drafting of the original edits outside the subagent workflow. The human committed them, and `code-reviewer` reviewed them (see [review.md](review.md)). No subagent authored them. The authority column shows which owner each path belongs to. It does not say who wrote the edit.

| File | Change | Authority |
|---|---|---|
| `.claude/agents/factory-engineer.md` | Names the main Claude Code session as orchestrator; reports unenforced guards as open until a fresh-session probe observes them. | Human architect's Phase 0 seed |
| `CLAUDE.md` | Fresh Claude Code session or `/clear` per phase; orchestrator delegates and never does specialist work inline; MCP servers approved individually; hooks run natively, with `agent_type` gaps recorded as open. | factory-engineer territory |
| `docs/agents.md` | Dispatch paragraph rewritten for the Claude Code orchestrator. Ownership, routing and escalation tables unchanged. | factory-engineer territory |
| `.claude/hooks/probe_policy.py` | Probe label renamed only. Guard logic unchanged. | factory-engineer territory |
| `docs/agent-runs/phase-01/orchestrator-update/README.md` | This change record. | Orchestrator phase evidence |
| `docs/agent-runs/phase-01/orchestrator-update/restart-probes.md` | Fresh-session probe checklist. | Orchestrator phase evidence |
| `docs/agent-runs/phase-02/revalidation.md` | Phase 2 revalidation checklist. | Orchestrator phase evidence |
| `docs/agent-runs/phase-03/revalidation.md` | Phase 3 revalidation checklist. | Orchestrator phase evidence |

No tool list, ownership row, permission rule, deny rule or hook behavior changed. Repairs from the review are tracked in [repairs.md](repairs.md).

## Validation run with the original change

This section covers the original range `9ee8b22..8dbd141` only. Validation for the repair rounds is tracked in [repairs.md](repairs.md). After repair round 1, a codex search over the same paths finds one intentional match, the legacy probe comment at `.claude/hooks/probe_policy.py:57`.

- `grep -rni codex CLAUDE.md docs/agents.md .claude .mcp.json` returned no matches.
- `python3 -m json.tool` parsed `.claude/settings.json` and `.mcp.json`.
- `bash -n` passed for all five hook scripts; `py_compile` passed for the Python hooks.
- `.claude/hooks/validate-agent-files.sh` exited 0: 18 agents, 18 ownership rows, 18 lifecycle routes, 5 commands.
- `.claude/hooks/probe-policy.sh` exited 0.

These are direct script checks. Native enforcement is verified only by the fresh-session probes.

## Observed live evidence (not restart probes)

The following events happened during the review and repair session on 2026-11-05. Each one is live hook interception in the working session, before ratification. Record them as separate from the formal restart probes, and do not treat them as passed probes.

| # | Session and actor | Observed event | What it shows | Source |
|---|---|---|---|---|
| L1 | Main session (orchestrator) | The chained Bash command `cd /Users/winnguyen/ScholarChain && git log --oneline 9ee8b22..8dbd141 && git diff --stat 9ee8b22..8dbd141 && ls docs/agent-runs/phase-01/orchestrator-update/` was refused: "ScholarChain policy: Use explicit commands and paths. Shell expansion, pipelines, chaining and redirection require a reviewed script." | The PreToolUse Bash guard ran natively for the main session. | Orchestrator session transcript (not archived) |
| L2 | Main session (orchestrator) | `git -C /Users/winnguyen/ScholarChain log --oneline 9ee8b22..8dbd141` and `git -C /Users/winnguyen/ScholarChain diff --stat 9ee8b22..8dbd141` were refused: "ScholarChain policy: This shell command is outside the role's permitted commands." | The same as L1. The attempted git commands were refused, and the human then supplied the diff evidence. Both used `-C <path>`. Per [re-review-2.md](re-review-2.md) N6, `policy.py:111-119` allows `git log` and `git diff` in their plain form, so the refusal applies to this argv, not to git in general. The orchestrator never tried the plain form. | Orchestrator session transcript (not archived). The orchestrator note in [review.md](review.md) generalizes this to "cannot run git" and is kept as archived. |
| L3 | `factory-engineer` subagent, repair round 1 | An Edit to `restart-probes.md` was refused: "PreToolUse:Edit hook error: ... The factory owns the declared constitutional files only. Route this path to its owner." | The PreToolUse Edit guard ran natively for a subagent. That message is reachable only when `agent_type == "factory-engineer"` (`policy.py:160-163`), so `agent_type` reached the hook for at least one subagent. | [repair-round-1-report.md](repair-round-1-report.md); [re-review.md](re-review.md) N1 |
| L4 | `factory-engineer` subagent, repair round 1 | Edits to `CLAUDE.md` and `docs/agents.md` succeeded. | Consistent with L3. The constitutional-edit allowance requires `agent_type == "factory-engineer"` (`policy.py:158`). | [repair-round-1-report.md](repair-round-1-report.md); [re-review.md](re-review.md) N1 |
| L5 | `factory-engineer` subagent, repair round 1 | Interpreter calls (`python3 -m json.tool`, `bash -n`) and absolute-path constitutional script calls were refused. | The guard's wrapper and path rules ran natively for a subagent. | [repair-round-1-report.md](repair-round-1-report.md) |

## Remaining gates

1. Fresh Claude Code session: dispatch `code-reviewer` on this repair's commit range and save `review.md` here. Route findings to factory-engineer and record them in `repairs.md` here. **Done.** `review.md`, `re-review.md` and `re-review-2.md` (APPROVE) are saved, and repair rounds 1 and 2 are recorded in `repairs.md`. Before the probe run, N5 goes to factory-engineer as a probe 9 draft, and the orchestrator inserts the row.
2. Human architect records the decision in `ratification.md` here.
3. Restart Claude Code and run [restart-probes.md](restart-probes.md). Save `restart-results.md` here.
4. Record `claude --version`, model, and local tool versions in `versions.md` here.
5. Revalidate Phases 2 and 3 using their `revalidation.md` checklists before starting Phase 4.

The original Phase 1 records are kept unchanged as historical evidence.
