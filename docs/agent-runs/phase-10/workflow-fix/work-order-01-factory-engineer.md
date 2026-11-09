# Work order 01: factory-engineer (workflow fix between Phase 10 and Phase 11)

Issued by: orchestrator, on the human's instruction. Not a phase. Do not commit.
Owner: `factory-engineer`. Files: `CLAUDE.md`, `docs/agents.md`, `.claude/hooks/**`, `.claude/settings.json` only.
Report to: `docs/agent-runs/phase-10/workflow-fix/report-01-factory-engineer.md` (the orchestrator archives it as received; return the full text in your reply).

## Items

1. **Formatter hook scope.** The PostToolUse Scala formatter hook must format only the file(s) the tool call edited, not the whole project, so parallel agents never reformat each other's files. Keep the existing collateral-change detection. Add hook probes proving another agent's file is untouched (for example: a second misformatted Scala file stays byte-identical after the hook runs for the first file's edit). Place probes with the existing hook probes and record their commands and observed output.
2. **Marker-rewrite evidence.** At `CLAUDE.md:54` and `docs/agents.md:61`, replace "later runs are expected to have rewritten it; not measured" with the observed rewrite: marker mtime 02:22, then 02:31 local on 2026-11-08, observed by Claude (Cowork), minute resolution. Source: `docs/agent-runs/phase-06/workflow-speedup/human-facts.md`. Read that file first and keep the wording faithful to it. Reconcile any sentence that the change makes inconsistent.
3. **Define "phase commit"** in `CLAUDE.md`: the commit recording the human's acceptance of a phase. Code-changing follow-ups need a passing agent `sbt check`. Docs-only follow-ups need none.
4. **`prompts/**` ownership.** In the `docs/agents.md` ownership matrix, assign `prompts/**` to `rag-engineer` (exclusive owned outputs). Check the matrix for collisions or other references that need the same update.

## Constraints

- Smallest edits that satisfy each item. No other constitutional changes.
- Run the hook probes and `validate-agent-files.sh` after the last edit, and report exact output.
- Agents run only bare `sbt quick`, `sbt check` and `sbt Test/compile`. No Scala sources change here, so no sbt run is expected.
- Do not ratify your own work. Mark hook-runtime behavior as pending until a fresh-session probe observes it.
- Report per your role file: status, files changed, complete diff, authority changes, commands run, observed results, risks, blockers, next gate.
