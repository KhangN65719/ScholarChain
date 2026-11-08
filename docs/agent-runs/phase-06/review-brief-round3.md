# Phase 6 re-review brief, round 3 (orchestrator, for a fresh `code-reviewer`)

Read-only. Return APPROVE or REQUEST CHANGES with file:line evidence, batched. Round 2 verdict and findings: `review.md`. Cover only the items that changed in repair R4 (`repairs.md`, work order 08) and anything it touched. Do not approve on agent claims; label what you verified by reading.

## Verify by reading
- R2-B1(b): `checkInvariants` in `modules/spark/src/main/scala/scholarchain/spark/CollaborationPipeline.scala`: `conflictingIdentityInputKeys` is an `error` over researcherLinks, workLinks, canonicalResearchers; `conflictingDuplicateInputKeys` a `warn` over works, mentions, authorPapers, pairCandidates. The failure path: exit code 1, `FAILED invariants ...`, tables and manifest written first, no `_SUCCESS`. Is exit 1 consistent with the documented exit codes (2 for usage and input refusal)?
- R2-B1(a): `modules/spark/src/test/scala/scholarchain/spark/ConflictingKeysSuite.scala`: does each test prove what its name claims, with hand-derivable expectations? Do the conflicting fixtures really conflict in the identity inputs (differing `canonicalResearcherId`)? Is the warn path non-fatal as asserted?
- `CodeInfo.manifestEntry` (`inRunIdSeed` only when the digest is not `unavailable`, `runIdTracksCode` text) and `DESIGN.md` agreement.
- `_work` deletion (`finishWork`) and the new session-stop: can stopping a session the run created affect anything still reading (manifest and load-manifest writing happen before the stop)? Any path where `_work` removal could delete input or output data (check the path computation)?
- `DESIGN.md` edits: identity/Hadoop split rationale, subset-run wording, failure paragraph.
- No `tests/**` file was touched by R1-R4: confirm `tests/**` and the module suites do not contradict each other on invariant names or exit codes.

## Evidence
- Gate after R4: human `sbt clean check` `[success]`, 1133 s, sparkAnalytics 61/0/0, root 373/0/0 (V12; core, entityResolution, hadoop totals only in `phase-06-clean-check-4.log`). Owner run (agent-reported): core 41, entityResolution 389, hadoop 207, sparkAnalytics 61, root 373.
- Final real run `fc4bbd1a-...` (V13): SUCCESS, exit 0, 13 invariants pass, `_work` absent, `inRunIdSeed` true, all 7 community windows converged (max 19 passes of cap 100), counts and path results equal the previous run.
- Rulings recorded in `review.md`: B5 accepted as a Phase 6 limitation (core change before Phase 7); B4 `datasets/slice/` and `.DS_Store` files excluded from the commit.
- Disclosed limitations carried unchanged from round 2: B2 outside a git checkout, `authorPosition` in the `authorPapers` key, N1, N7 (reduced: `_work` now removed), N9, N10.

## Also state
Whether all round-1 and round-2 blocking findings are now closed; list anything still open as blocking or as a disclosed limitation to record in the phase summary.
