# Phase 03 revalidation under the Claude Code orchestrator

Status: PENDING. Run after the Phase 1 orchestrator repair is ratified and its restart probes pass.

Use one fresh Claude Code session from the repository root. The orchestrator delegates; it does not run the owner's work inline.

## Gate

- `sbt check` exits 0, with ConfigSuite and ContractSuite passing.
- `python3 -B datasets/scripts/datasets.py validate` exits 0 for all four sources.
- `python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'` exits 0.
- Every record carries provenance; no invented licenses or identifiers.

## Steps

1. Orchestrator runs the gate commands above and records exact commands, exit codes and relevant output in `revalidation-results.md`.
2. Orchestrator dispatches `code-reviewer` against the accepted Phase 03 commit. Record the verdict in `revalidation-review.md`.
3. Route any finding to its owning agent and rerun the gate after repair.
4. Record `claude --version`, model, JVM, sbt and Scala versions in `revalidation-versions.md`.
5. Human architect records acceptance and the commit SHA.

The original Phase 03 records are kept unchanged as historical evidence.
