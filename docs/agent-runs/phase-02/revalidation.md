# Phase 02 revalidation under the Claude Code orchestrator

Status: PENDING. Run after the Phase 1 orchestrator repair is ratified and its restart probes pass.

Use one fresh Claude Code session from the repository root. The orchestrator delegates; it does not run the owner's work inline.

## Gate

- `sbt Test/compile` and `sbt check` exit 0 from the repository root.
- The module graph has no cycles and matches the boundaries in `CLAUDE.md`.
- No application logic exists outside the scaffold.

## Steps

1. Orchestrator runs the gate commands above and records exact commands, exit codes and relevant output in `revalidation-results.md`.
2. Orchestrator dispatches `code-reviewer` against the accepted Phase 02 commit. Record the verdict in `revalidation-review.md`.
3. Route any finding to its owning agent and rerun the gate after repair.
4. Record `claude --version`, model, JVM, sbt and Scala versions in `revalidation-versions.md`.
5. Human architect records acceptance and the commit SHA.

The original Phase 02 records are kept unchanged as historical evidence.
