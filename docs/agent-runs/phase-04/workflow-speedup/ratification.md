# Workflow speed-up ratification

Status: RATIFIED by the human architect. Takes effect in a fresh Claude Code session.

- Date: November 7, 2026
- Baseline: `6d89e3d` (accepted Phase 04).
- Change set: 22 files, `.claude/settings.json`, `.claude/hooks/validate.py`, `.claude/hooks/probe_policy.py`, `CLAUDE.md` and all 18 `.claude/agents/*.md` role files.
- Review verdicts: [review.md](review.md) REQUEST CHANGES, [re-review.md](re-review.md) APPROVE. Repairs in [repairs.md](repairs.md); validation in [validation.md](validation.md).

## What is ratified

1. `sandbox.excludedCommands` with exactly `sbt check` and `sbt Test/compile`, per the documentation evidence in [sandbox-exclusion-doc-evidence.md](sandbox-exclusion-doc-evidence.md). No deny rule, permission entry, sandbox switch or write allowlist changed. This closes the P1 open gate recorded in the Phase 01 sandbox repair: an agent-turn `sbt check` was measured at exit 0 with 148 tests passing.
2. A declared default `model:` on every role: `opus` for `code-reviewer`, `sonnet` for the other 17, checked by `validate.py`.
3. Workflow rules in `CLAUDE.md`: a proof plan per gate item before coding, `test-engineer` working in parallel and executing its tests, one batched repair round with change-scoped re-review, and short orchestrator reports.
4. Role files reconciled with the new test-gate workflow.
5. `.codex/`, `.agents/` and `AGENTS.md` recorded as never committed and moved out of the repository ([external-agent-files-record.md](external-agent-files-record.md)).

## Validation observed

- `validate-agent-files.sh`: 4/4 PASS.
- `probe-policy.sh`: 160/160.
- `git status --short`: exactly the 22 change-set files plus this evidence directory.

## Accepted risks and open items

- The exclusion runs `sbt check` and `sbt Test/compile`, and therefore project test code, outside the filesystem and network sandbox during agent turns. This is the same trust already extended to the human-run `!` check.
- Fresh-session probes P4-A to P4-D have not run, including whether a non-authoring subagent receives the exclusion and whether the commands execute fully outside the sandbox.
- `CLAUDE.md:51` labels two near-miss forms as inferred although both were later measured. Conservative, to be corrected at the next factory change.
- Recommended follow-up: make `policy.py` match raw command text against the exclusion list so the guard and the sandbox use the same predicate.

The human architect stated:

> I ratify the speed-up.
