# Phase 1 sandbox repair ratification

Status: RATIFIED by the human architect.

- Date: 2026-11-05
- Reviewed: the uncommitted change set on top of `751d926` (`.claude/hooks/policy.py`, `.claude/hooks/probe_policy.py`, `CLAUDE.md`, `docs/agents.md`) and the evidence in this folder.
- Review verdicts:
  - [review-verdict.md](review-verdict.md): REQUEST CHANGES (F1–F8)
  - [review-verdict-2.md](review-verdict-2.md): REQUEST CHANGES (B1–B6)
  - [review-verdict-final.md](review-verdict-final.md): APPROVE

## Decision

- **P2 adopted.** The Stop test gate blocks the first stop and, on a repeat stop signalled by `stop_hook_active == true`, reports an unverified stop instead of looping. This is a narrowing of loop behavior, not a widening of authority.
- **P3 adopted.** Shell-bearing roles may read constitutional paths through exactly `git diff`, `git diff --stat`, `git log --oneline` and `git show --stat`, with ranges and pathspecs. This is the only authority-affecting change, and it is read-only.
- **P1 withdrawn and recorded as an open gate.** Agents cannot complete `sbt check` inside the sandbox. The sandbox write-allowlist entries and the `.jvmopts` experiment were removed. The human architect runs `sbt check` outside the sandbox at the end of each phase and before every commit, as `CLAUDE.md` records. See [p1-withdrawal-report.md](p1-withdrawal-report.md).

## Conditions from the final review

1. P3 is ratified as the only authority-affecting change, and it is read-only.
2. Items that rest on a single author's self-report stay labelled as such in the archived reports. The human-observed results below supersede them for the probe count and validation.
3. A fresh orchestrator session starts before the next phase, per the one-session-per-phase rule.

## Human-observed checks (2026-11-05, 11:08 PM CT, outside the sandbox, Temurin 21.0.13, sbt 1.11.7)

- `.claude/hooks/probe-policy.sh`: `SUMMARY: 136 probes passed, 0 failed.`
- `.claude/hooks/validate-agent-files.sh`: 4 PASS lines (18 agents with explicit tools and read-only reviewer fence; 18 ownership rows and 18 lifecycle routes; 5 workflow commands; 2 JSON files and 5 shell scripts with hook wiring).
- `sbt check`: `Passed: Total 18, Failed 0, Errors 0` and `[success]`.

## Still pending (not verified by this ratification)

- Whether Claude Code surfaces the exit-1 Stop stderr record to the human (P2's value depends on it), and durability of that record.
- Live hook interception of the new P3 forms and the P2 path in a fresh session.
- Harmonizing role files and `CLAUDE.md` Project rules that still tell specialists to run `sbt check` themselves. Until then, each work order states that the human runs it.

The human architect stated:

> I ratify the sandbox repair: P2 and P3 adopted, P1 withdrawn and recorded as an open gate with the human sbt check workflow.
