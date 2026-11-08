# Report 07: test-engineer, repair round 1 (as received; per-test list condensed)

**Status: COMPLETE.** `sbt quick` exit 0, `[success] Total time: 73 s`. `sbt check` not run, as ordered. Work order: work-order-07-test-repair1.

## Test-to-item (all pass)
- B1 (all in `tests/adversarial/Phase8IngestSuite`): the old `.fail` test "same execution label over a changed row" is now a plain passing test "...is refused, not silent"; "...never overwrites" rewritten (also checks a new label still gets `ChangedVectorContent`, nothing overwritten); new: added row refused, removed row refused, changed model refused, changed template version refused, "a run record or batch record with no recorded digest is refused", "an identical rerun of the same label still resumes".
- Old failing test ~648 now: "a document altered on the object store rejects only that key" (one rejected key, `ImmutableKeyConflict` with a reason, no failed batch, `COMPLETED_WITH_REJECTS`); new "a document error on the unchanged path leaves the rest of the batch".
- L2 (`Phase8ExportSuite`): symlinked part file refused (`InvalidKey`); symlink swapped in after open refused at read; symlinked table directory refused; part file under the wrong table directory refused (3 path spellings); part file in the export root or another table refused.
- L4 (`Phase8IngestSuite`): template bump rendering identical text needs no key conflict (no rejects, no new document, drift equals `mapped`, drift keys name `textRef`); bump over a shared store gives distinct documents (count doubles); deterministic document keys, `textRef` within 200 chars incl. a 5000-char run input.
- L6: "a changed facet is not rewritten and is counted as metadata drift" (entries name `source`, `year`, `communityId`; no `PutVectors` call; vectors and objects byte-identical; drift total matches report, per-index sum and ledger `STAGE#summary`).
- L8: `Phase8RagSuite` "templates render locale-sensitive input without locale effects"; `Phase8HashEmbedderSuite` "case folding is the root-locale folding, not the default locale's"; neither calls `Locale.setDefault`.

## Files modified
`tests/adversarial/Phase8IngestSuite.scala`, `Phase8ExportSuite.scala`, `Phase8RagSuite.scala`, `Phase8HashEmbedderSuite.scala`. No production file or module test touched; no test deleted.

## Commands and results
First `sbt quick` failed at test compile; `sbt Test/compile` showed `RejectedVector.message` did not exist; fixed. Second `sbt quick`: 2 failures, both the agent's own test bugs (template-bump test compared a store including the new manifest; L6 test asserted a `source` key not present). Final `sbt quick` exit 0; readable totals `Passed: Total 56, Failed 0, Errors 0` and `Passed: Total 659, Failed 0, Errors 0`. Phase 8 root suites from `target/test-reports`: Ingest 48, Export 25, Rag 27, HashEmbedder 27, EndToEnd 20 = 147, 0 failures. Module totals for core, storage, rag and others not readable (cut from output).

## Assumptions and risks
- L8: production never reads the default locale (`HashEmbedder` passes `Locale.ROOT`; `RagTemplates` uses plain interpolation). Locale independence under a Turkish default is not proven without a forked JVM with `-Duser.language=tr`; tests check root-locale behaviour directly and compare against the independent reference.
- `Phase8IngestSuite` reads `RejectedVector`'s reason through `productElement(3)`; fragile if the case class is reordered.
- L6 drift keys matched by substring, not exact differing-key set.
- Root Spark integration suites unchanged.

## Production defects
None found. Repaired B1, L2, L4, L6 behaviour matches work order 06 and review.md.

## Gate status
Stop gate: the agent's `sbt quick` wrote the marker on these sources (it saw no block). Pending: the human's `sbt clean check` and review of this round.
