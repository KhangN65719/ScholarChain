# Work order 06: storage-engineer, repair round 1 (B1, L2, L4, L6, L9)

Owner: storage-engineer (modules/storage/**). Source: `docs/agent-runs/phase-08/review.md` round 1. Same rules as work order 03 (local only, no new dependency, no weakening or deleting tests, do not edit tests/**; test-engineer updates tests/** in its own repair order).

## B1 (blocking): a rerun under the same `executionLabel` must not skip by position
`EmbeddingWork.scala` (~160-170, `begin` ~144-155) and `EmbeddingIngest.scala` (~345-346).
- Store the work-manifest SHA on the run ledger record, and a digest of each batch's item list (ordered summary IDs with text hashes and model version) on its batch record.
- On resume: if the manifest SHA or a batch's items digest differs from the recorded one, refuse with a NEW distinct `ErrorCode` (suggest `ExecutionInputChanged`) naming the label, and the old and new digests; tell the caller to use a new label. Do not skip, embed or write anything in that case. The check happens before any batch is skipped as resumed.
- A rerun with identical input still resumes exactly as now (FINISHED batches skipped, others redone at a higher attempt).
- Add feature tests in `modules/storage/src/test` for: changed text under the same label refused; an added row refused; a removed row refused; a changed model refused; changed template version refused; identical rerun still resumes. Nothing is overwritten in any refused case (assert the stored vectors and documents are unchanged). Keep existing tests green.
- Report the new error code so test-engineer can assert it.

## L2: symlinks and table directory in `JsonlExportSource.scala` (~85-96, ~180-205)
Resolve each part file with `toRealPath()` and require it to lie under the real export directory (or use `NOFOLLOW_LINKS` and refuse symlinks); refuse a symlinked part file even when the SHA matches. Also require each part file's path to lie under its own table's `<table>/` directory. Feature tests for both refusals (a symlink to an outside file with a matching SHA; a part file listed under the wrong table directory).

## L4: document key and error handling (`SemanticDocuments.scala` ~48-55, ~80-87; `EmbeddingIngest.scala` ~230-235)
- Include `templateVersion` in the document key (or otherwise make a template bump that renders identical text work without `ImmutableKeyConflict`) while keeping keys deterministic and under the 200-character `textRef` limit. If you change key shape, the document key spec in your report must be updated.
- Move `putDocument` on the unchanged path inside the per-key `try`, so a document error rejects that key with a recorded reason instead of failing the whole batch.
- Tests for both.

## L6: facet drift on the unchanged skip (`EmbeddingIngest.scala` ~230-235)
Today an unchanged text hash and model skips even if facet metadata (`communityId`, `source`, `year`, and the other keys) changed. Conservative choice: keep refusing to overwrite (D7), but when the stored vector's metadata differs from what the row would produce in anything other than the skip-identical case, count it in a separate `metadataDrift` counter in the report and the ledger summary, listing up to a bounded number of keys. Do not rewrite the vector. Test it.

## L9: scaladoc
`HashEmbedder.scala:19`: change "byte 8" to "the byte at index 8 (the ninth byte)". No behavior change; the exact known-vector test must still pass.

## Commands
`sbt quick` for work; never two sbt commands at once; no `sbt check`. Give your test-to-item list FIRST in your report.

## Report
Role-file contract. Orchestrator archives it as `report-06-storage-repair1.md`.
