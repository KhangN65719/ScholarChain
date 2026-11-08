# Phase 6 re-review brief, round 2 (orchestrator, for a fresh `code-reviewer`)

Read-only. Return APPROVE or REQUEST CHANGES with file:line evidence, batched. Ask for missing checks; mark them pending. Do not approve on agent claims. Round 1 verdict and findings: `review.md`. This re-review covers only the items that changed in repairs R1-R3 and anything those repairs touched.

## Items to verify by reading (`repairs.md` R1, R2, R3; work orders 05, 06, 07)
- B1: per-dataset duplicate-key counts, conflicting-key detection, the `conflictingDuplicateInputKeys` warning invariant (`Canonical.scala`, `CollaborationPipeline.scala`). Is a warning invariant acceptable for conflicting duplicates, given the integrity table is asserted row for row by independent tests?
- B2: sources digest in the run-ID seed (`RunContext.seed`, `Inputs.scala`); `transformVersion` rule in `DESIGN.md`; behavior outside a git checkout (digest `unavailable`): does the run ID still change with code?
- N2 summaries and `nodeKind`, N3 `unreachableReason`, N4 mention-only fallback of the local edge ID, N5 header lines, N6 distinct pair counts, N11 summary citations (`Summaries.scala`, `GraphAlgorithms.scala`, `Coauthorship.scala`, `Export.scala`, `DESIGN.md`).
- B3 code half: fragmentation limitation in the path and bridge limitation texts and manifest.
- R3: `PathReport` (`GraphAlgorithms.scala`), `pathResults` manifest key, summary lines; driver-collect policy still holds (one row per window or status through the guarded helper); `SparkDriverCollectScanSuite` still meaningful.
- Own module tests added or changed by `spark-engineer`: were any expectations relaxed rather than corrected? Compare with `tests/**`, which no repair touched.
- Part-file renaming (`Export.renameParts`): safe with partitioned and empty-schema tables and with the load manifests' SHA-256 per file?

## Evidence (observed by the orchestrator from human-pasted output)
- Gate after R2: `sbt clean check` `[success]`, 806 s, sparkAnalytics 52/0/0, root 373/0/0 (V7). Gate after R3: `[success]`, 808 s, sparkAnalytics 55/0/0, root 373/0/0 (V10). core, entityResolution and hadoop totals were outside the pasted tails (logs `phase-06-clean-check-2.log`, `-3.log`).
- Final real run `312ea79b-...` (V11): SUCCESS, exit 0, 12 invariants pass, duplicate-key counts 0, row counts equal the first run, path results and header lines present. Community convergence and shuffle totals: V9 (agent-reported, run `3ca63bcd`): no window hit the 100-pass cap.
- Human rulings (review.md): B5 accepted as a Phase 6 limitation with a core contract change as a Phase 7 prerequisite; B4 `datasets/slice/` and the `.DS_Store` files excluded from the commit.
- Check whether the round-1 non-blocking items N1, N7, N9, N10 are now only disclosed limitations and say if any should block.

## Output
Verdict; per finding: file:line, owner, blocking or disclosed limitation; items pending.
