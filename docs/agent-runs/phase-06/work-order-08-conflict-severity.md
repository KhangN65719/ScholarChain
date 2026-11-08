# Work order 08: repair R2-B1 and small disclosure fixes (`modules/spark/**`)

Issued by: orchestrator, 2026-11-08. Owner: `spark-engineer`. Source: `review.md` round 2. Local only; no commit; do not edit `tests/**`; do not weaken tests.

## Items
1. **R2-B1(b):** a conflicting duplicate key in `researcherLinks`, `workLinks` or `canonicalResearchers` (one key, differing content, especially differing `canonicalResearcherId`) must be an `error` invariant (the run fails with the documented exit code), not a warning. `works`, `mentions`, `authorPapers`, `pairCandidates` stay warnings. Document the split and why (entity resolution owns identity) in `DESIGN.md`.
2. **R2-B1(a):** add module tests (pipeline-level, small synthetic run) proving: one conflicting key in a Hadoop input gives counts, `warn`, the detail text and a successful run; one conflicting key in an identity input gives `fail`/error and the exit code. Hand-derive the expected counts in the test comments.
3. `"inRunIdSeed"` in the manifest must be true only when the sources digest is not `unavailable`; when it is `unavailable`, say so in the manifest and `DESIGN.md` (the run ID then does not change with code).
4. `DESIGN.md` wording: replace "local slice run" with the subset run (run `312ea79b-637c-440b-85f7-5c0640c3609c` is the observed run for the figures; they are no longer "not recomputed").
5. `_work`: delete it after a successful run, and keep it on failure for diagnosis; state the rule in `DESIGN.md`. If this breaks an existing test, report it; do not edit `tests/**`.
6. The independent tests must not move: confirm by `sbt check` that the world C1 duplicate fixtures (exact repeats only) still pass.

## Execution
List which test proves each item before editing. `sbt Test/compile`, then `sbt check` once; no other sbt invocation, and do not start a second sbt while one runs. Report exact counts per module (core, entityResolution, hadoop, sparkAnalytics, root) — read the `Passed:` lines for every module; if you cannot find a module's line, say so. Report fields as in work order 05.
