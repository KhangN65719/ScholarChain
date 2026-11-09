# Workflow fix ratification (between Phase 10 and Phase 11)

- Decision: RATIFIED by the human owner (Khang), 2026-11-08.
- Basis: review verdicts in this folder (code-reviewer, Opus): round 1 REQUEST CHANGES with 8 findings, round 2 APPROVE. Also the factory-engineer reports.
- Probes, run by the orchestrator after the last edit: probe-policy.sh passed 199 with 0 failed, and validate-agent-files.sh printed 4 PASS lines.

## Changes
- PostToolUse Scala formatter: formats only the edited files (`sbt "root / scalafmtOnly <files>"`), so parallel agents no longer reformat each other's files. Collateral-change detection is kept, and the probes show another agent's file stays byte-identical.
- CLAUDE.md:54 and docs/agents.md:61: the marker rewrite is recorded as observed (02:22, then 02:31 local on 2026-11-08).
- "Phase commit" is defined in CLAUDE.md.
- prompts/** is owned by rag-engineer, and rag-engineer.md is kept in sync with that ownership.

## Open (checked at the start of Phase 11, in a fresh session)
- Real-sbt check: one misformatted file each in a module main file, a module test file and a tests/ file. Each must be reformatted alone, and no other file may change.
- Live hook interception, and the Stop-gate loop (P4-D).
- An edit under prompts/** does not trigger the Stop gate. This matters only if prompt text moves there and is loaded at runtime.
- A repo path with characters outside [A-Za-z0-9._/-] would refuse every Scala edit. The current path is fine.
- No sbt clean check was run for this change. It touches only hooks and docs, and the next phase gate covers it.
