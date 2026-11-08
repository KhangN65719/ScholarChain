# Phase 8 re-review brief (round 3, repair round 2 only)

Reviewer: a fresh code-reviewer (Opus), read-only (Read, Grep, Glob). Mark anything unverifiable as PENDING. Return APPROVE or REQUEST CHANGES with `file:line` evidence, all findings batched. Do not fix or approve your own work. Scope: ONLY the change below and anything it newly touched. Do not re-litigate earlier rounds.

## Read first
CLAUDE.md, docs/agents.md, docs/agent-runs/phase-08/{decisions.md (H4, the human's ruling and known limitation), work-order-11-rag-repair2.md, report-11-rag-repair2.md, review.md (rounds 1 and 2, finding N6)}.

## Context
The human's real-data ingest wrote all 41,885 vectors with 0 rejects and 0 failed batches and verified `fetchText` hashes, then ended FAILED because the collaboration-path SAMPLE query (the first 12 words of the summary) did not retrieve its own source row: templated summaries share opening words (reviewer finding N6). Repair round 2 changed only `modules/rag/src/main/scala/scholarchain/rag/IngestMain.scala` and added `modules/rag/src/test/scala/scholarchain/rag/IngestSampleSuite.scala`.

## Human constraint to check
The sample check must NOT be weakened. Acceptable fixes: full summary text query, or distinguishing endpoint fields, or accept the source row only if it ties for the top score with the tie size reported; the chosen approach must be stated; a test with tied path prefixes; the hash-embedding tie behaviour recorded as a known limitation.

## Verify
1. The query actually used is the full rendered text (the exact string `EmbeddingIngest` embeds, `RagTemplates.render(row).text`), and the printed `query:` line is a clearly labelled display prefix, not what is queried. Check both the narrow SAMPLE and the WIDE-SAMPLE.
2. Strictness is preserved or increased: the run still FAILS when the source row is absent, its vector altered, not in the top-score group, ranked below an equal-score group, or when `fetchText` fails or a hash mismatches; and still FAILS on any reject, unmapped summary, failed batch or count mismatch. Look for any path where a failed sample is downgraded or skipped (for example `MaxHits = 20` cutting a tie group, empty hits, a type with zero rows, a row with empty rendered text).
3. The tie logic: top-score group is the prefix at exactly the best distance; the source row must be inside it; the tie size is reported in the console and in `ingest-report.json` (`samples[]`); the report stays deterministic (no latency, no timestamp); `ReportVersion` stays `/1` while a field was added (judge: acceptable or should it bump).
4. The test seam `execute(args, out, sampleBucket = identity)`: confirm the default changes nothing for a real run, that it wraps only the bucket the sample queries read, and that it cannot mask a real failure in production code paths.
5. Tests in `IngestSampleSuite`: are they faithful (the regression precondition really shows the 12-word query missing the source row; duplicate-text tie size 2 passes; source-row-outside-top-group FAILS; missing and altered vector FAIL; rerun after a FAILED run works; display prefix labelled)? No existing test weakened or deleted (`IngestMainSuite` untouched? verify), no `.fail`, no `Locale.setDefault`.
6. Rerun semantics: `checkOutDir` accepts a directory with an `ingest-report.json` of the same export manifest SHA and bound, whatever its `result`; the `objects/` documents are immutable and identical; check there is no way the rerun silently keeps a stale or contradictory report, or reuses `objects/` of a different export or a changed template without refusing.
7. The known limitation is stated accurately in the scaladoc and report (hash-embed-v1 is lexical; ties and prefix collisions expected for templated summaries; Option B semantic embedder is Phase 12 or later) and does not overclaim retrieval quality.
8. Scope: only modules/rag changed; no new dependency; no constitutional file; no AWS code.

## Evidence
`sbt quick` by the owner: `[success]`, rag 85 passed, root 664 passed, 0 failed (per report-11; per-module group names not read). The orchestrator has NOT run `sbt check` after repair round 2; the human's `sbt clean check` is PENDING and is the phase gate. The human's rerun of the real-data ingest is PENDING.

## Output
One verdict: severity, `file:line`, evidence, owner, PENDING items. Note: this was repair round 2 of the permitted two; if you REQUEST CHANGES, say plainly that the budget is spent and which items the human must decide.
