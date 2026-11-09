# Work order 02: factory-engineer, repair round 1

Issued by: orchestrator. Input: `review-round-1.md` in this folder. Do not commit.
Scope: same files as work order 01 (`CLAUDE.md`, `docs/agents.md`, `.claude/hooks/**`; `.claude/agents/rag-engineer.md` stays as already changed).
Report to: `report-02-factory-engineer-repair-1.md` (return the full text).

Repair F1 through F7 exactly as stated in `review-round-1.md`, batched in this one round.

- F4: allowlist the sbt path argument; add a `;` probe and a leading-`-` probe.
- F3: make the whitespace probe discriminate (assert guard message or no sbt invocation/side effects).
- F8 is a report-accuracy note: do not edit report-01 (preserved as received). State in your report that `validate.py` was within work order 01's `.claude/hooks/**` list and only `rag-engineer.md` was the scope extension.
- Pending-item decision: choose between `scalafmtOnly` unscoped, `root / scalafmtOnly`, or absolute paths by reading `build.sbt` (alias at line ~350) and the sbt-scalafmt plugin version in `project/`. You cannot run arbitrary sbt, so state the choice, the reasoning and that it remains unverified until a fresh-session probe.
- Smallest edits. No other constitutional changes.
- After the last edit, run `.claude/hooks/probe-policy.sh` and `.claude/hooks/validate-agent-files.sh` and report exact output and counts.
- Do not ratify your own work.
