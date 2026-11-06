# Phase 02 revalidation repair routing

Step 3 of `docs/agent-runs/phase-02/revalidation.md`. Date: November 5, 2026.

The orchestrator routes findings; it does not repair them. Both findings from `revalidation-review.md` belong to one owner.

## Routing

| ID | Severity | Location | Owner | Dispatch state |
| --- | --- | --- | --- | --- |
| F1 | LOW, latent | `build.sbt:52-53` | `build-engineer` | **Not dispatched. Accepted as a known latent finding** by the human architect, November 5, 2026. |
| F2 | LOW, latent | `build.sbt:48` | `build-engineer` | **Not dispatched. Accepted as a known latent finding** by the human architect, November 5, 2026. |

Both findings fall inside `build-engineer`'s exclusive ownership of `build.sbt` per the `docs/agents.md` ownership matrix. No other owner is implicated. `code-reviewer` named the owner and proposed no fix, as its role requires.

## Why dispatch is held rather than issued

Neither finding fails a gate item. Both are latent: F1's unwired `tests/` subdirectories currently hold only `.gitkeep` files, so nothing is being silently skipped today, and F2's narrow root test classpath is deliberate and correct for the current tree.

Repairing either one edits `build.sbt`, which:

- reopens gate item 1, since any build change requires a fresh human-run `sbt check` before the gate can be called closed again;
- changes a file that the human architect accepted and pushed as `8c8bbe0`, so the acceptance record would need to name the post-repair commit instead;
- in F2's case, requires a design decision about how far to widen the root test classpath, because a careless widening would pull batch job modules into the runtime dependency graph and violate the `CLAUDE.md` module boundaries. That scoping choice is not the orchestrator's to make unilaterally.

The work order's step 3 says to route findings and rerun the gate after repair. Rerunning the gate depends on the human architect, who is the only party able to run `sbt check`. Dispatching a build change before that decision would leave the repository in a state whose gate cannot be closed by any agent.

## Decision

The human architect accepted both findings as known latent findings on November 5, 2026, choosing not to repair them in this revalidation.

Consequences of that decision, recorded explicitly:

- `build.sbt` is unchanged. The gate evidence in `revalidation-results.md` and the acceptance commit `8c8bbe0` continue to describe the reviewed build, and gate item 1 stays closed.
- No `build-engineer` work order was issued. Neither finding was repaired, and no gate was rerun.
- F1 and F2 carry forward as open items. They become live the first time an agent places a `.scala` test under `tests/adversarial`, `tests/integration` or `tests/end-to-end`, which F1 would silently exclude from compilation and execution, or writes a test under `tests/` that must reference a module other than `core`, which F2 does not yet permit. Whichever phase does either should route the matching finding to `build-engineer` before relying on that test.
- An F2 repair still requires a scoping decision so that widening the root test classpath does not pull batch job modules into the runtime dependency graph, which would violate the `CLAUDE.md` module boundaries.

This decision accepts a known reduction in test-gate strength for F1: until the reserved directories are wired in, `sbt check` can pass and write the marker while a test placed there is never run.
