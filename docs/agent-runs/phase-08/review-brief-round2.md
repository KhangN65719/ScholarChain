# Phase 8 re-review brief (round 2, changed items only)

Reviewer: a fresh code-reviewer (Opus), read-only (Read, Grep, Glob). You cannot run anything; mark anything unverifiable as PENDING. Return APPROVE or REQUEST CHANGES with exact `file:line` evidence, all findings batched. Do not fix or approve your own work. Cover ONLY the items below and anything the repairs newly touched; do not re-litigate round 1 items that were not changed.

## Read first
CLAUDE.md, docs/agents.md, docs/agent-runs/phase-08/{prompt.md, decisions.md, review.md (round 1 verdict)}, work orders 06 to 10, reports 07, 08, 10. Two owner reports were never delivered as received (decisions G1 storage repair, G2 rag repair; G2 was later delivered as report-08). There is NO report-06: verify the storage repair directly from source against work order 06.

## Evidence
- Orchestrator ran `sbt check` after all repairs: exit 0, `[success]`. Per-module totals not read by the orchestrator. Owner counts: rag 68 (quick), root 749, spark 62 (quick), `Phase8RagSuite` 32; 0 failures. The human's `sbt clean check` is PENDING.
- Real-data runs (`JsonlExport`, then `IngestMain`) have NOT run yet.

## Items to verify (round 1 id: repair)
- **B1** (storage, `EmbeddingWork.scala`, `EmbeddingIngest.scala`, `Support.scala`): same-label resume now stores the manifest SHA on the run record and an items digest on each batch record and refuses with a new `ErrorCode` before any batch is skipped, embedded or written. Check: the digest covers text hash, template version, model and the ordered item list; added, removed, changed-text, changed-model, changed-template cases all refused; identical rerun still resumes; a record with no recorded digest is refused; nothing is overwritten; failure is recorded not swallowed. Look for a bypass: first run of a label, concurrent labels, a FAILED batch redone at attempt 2 with unchanged input.
- **L2** (storage, `JsonlExportSource.scala`): symlinked part file, symlinked table directory, symlink swapped after open, wrong table directory, file in root.
- **L4** (storage, `SemanticDocuments.scala`, `EmbeddingIngest.scala`): `templateVersion` in the document key (key shape and 200-char `textRef` bound still hold; the spec in report-03 is superseded; check `fetchText` and any caller or test depending on the old key shape); `putDocument` on the unchanged path now rejects only that key.
- **L6** (storage): `metadataDrift` accounting: counted, reported (report, per-index, ledger `STAGE#summary`), vectors not rewritten, bounded key list.
- **L9** (storage `HashEmbedder.scala:19`): scaladoc wording only; confirm no behavior change.
- **L1, L5, L7, L10, snippet-cut nit** (rag, report-08): chunking whitespace-run refusal message (the owner chose refusal over lossless chunking; judge that), per-item snippet budget `24576 / n` with 20 items at 1228 bytes (is cutting snippets that early acceptable for Phase 9 consumers; any path where the budget makes a documented cap inconsistent), wide-sample queries and the rank check in `IngestMain`, the `localCost` line and field and determinism of `ingest-report.json`, surrogate-pair safe cut.
- **L3** (spark, report-10): `JsonlExport.scala` real-path overlap checks, marker-guarded staging cleanup, `NOFOLLOW_LINKS` deletion. **Also review `Config.scala` `OutputPolicy.check`/`resolved`**, which the Phase 6 `CollaborationPipeline` also calls: confirm the change only tightens a refusal, cannot reject a legitimate run directory (for example one under a path containing a `target` component or a symlinked home or data volume such as `/Users/winnguyen/scholarchain-data`), and that no pipeline output or algorithm changed (so no real-data rerun of Phase 6 is needed). State your conclusion explicitly.
- **Tests** (test-engineer, reports 07 and the test-engineer 09 follow-up; no report-09 exists as received: its result arrived only as a rag-engineer fix, see report-08 08b): the former `.fail` test is now a plain test; no test was deleted or weakened across `tests/adversarial/Phase8*.scala`, `modules/storage/src/test`, `modules/rag/src/test`, `modules/spark/src/test/.../JsonlExportSuite.scala`; `Locale.setDefault` removed from root suites; `SemanticContextSuite.scala:143` now pins the exact cut (judge: is that a faithful fix or a weakening). Check that Phase 7 tests are intact.
- **Gate items** (README Phase 8, verbatim in prompt.md): re-check that all six gate bullets still hold after the repairs, in particular same key never refers to different text, model version stored, text fetchable through the S3 pointer, metrics not answered from vector similarity (decision F1: snippets keep Phase 6 numbers by design, human may overrule).
- **Scope**: no AWS code, no new dependency (check `project/DependencyVersions.scala` and `build.sbt` unchanged apart from the root `rag % "test->compile"` edge), no constitutional file edit (CLAUDE.md, docs/agents.md, `.claude/**` other than the generated marker).

## Accepted (do not flag)
Phase 12 deferrals; empty topic-institution index; decisions F1, G1, G2; untracked human files (AGENTS.md, .agents/, .codex/, datasets/slice/, readback-paths.py, .DS_Store) to stay out of the commit.

## Output
One verdict: severity, `file:line`, evidence, owner, PENDING items. If you request changes, remember at most one further repair round is allowed (this is re-review 1 of the permitted two repair rounds).
