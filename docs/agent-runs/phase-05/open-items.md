# Phase 05 open items and known limitations (recorded by the orchestrator, 2026-11-07)

Updated after re-review round 3 (review of repair round 2). Sources: review-verdict-round1.md, review-verdict-round2.md, agent reports.

## Corrected or closed
- ß / ø folding: NOT deferred. `TextNormalizer.scala` (hadoop) and the ER `Text.scala` both fold them, with tests (`NonLatinFoldingSuite`, `AdversarialUnicodeSuite`).
- `DblpEntities` vs `dblp.dtd`: diffed by hand in repair round 1 (the DTD declares 65 entities, all in the table with matching code points; the table has 248 names, 183 not declared in the DTD); `DblpDtdEntitiesSuite` pins the 65; the stale comments in `DblpEntities.scala` were corrected in repair round 2. The DTD itself is still not vendored in the repo (proposal: dataset-engineer vendors it under datasets/, CC0, 13 KB).
- Same-DOI position rule and arXiv: the rule now also keys on a shared arXiv id (policy 1.3.0, `rule.same-arxiv-distinct-position`); an agreeing ORCID or a content-supported aggregator ID coinciding with the position rule is now CONFLICT (`conflict.id-agreement-contradicted-by-position`); recorded in the `Policy.scala` header under the agree-but-contradicts rung (human rulings, round 2).
- Module `LeakageSuite` (entity-resolution): strengthened in repair round 2 (recursive scan, brace/rename/wildcard imports, 22 planted forms, 11 ordinary forms, 11 tests); the class-byte scan lives in `tests/adversarial/AdversarialLeakageSuite.scala`.

## Closed in the last repair round (policy 1.4.0, human ruling)
- Agreeing ORCID on two slots of the SAME work record is CONFLICT regardless of whether the work has a DOI or arXiv id (`AuthorSlots.distinctSlotsOfOneRecord`, `rule.same-record-distinct-position`, reason `conflict.id-agreement-contradicted-by-position`); tests `SameRecordIdAgreementSuite` (11) and `tests/adversarial/AdversarialErSameRecordSuite.scala` (9). The Matcher comment, dead code and "reason" wording were cleaned up.

## Open
- DEFERRED to a later phase (human, 2026-11-07): agreeing aggregator ID on two slots of one record with NO DOI and NO arXiv id stays REJECTED (the ruling named ORCID only); with a DOI or arXiv id it is CONFLICT. Both outcomes refuse the merge.
- DEFERRED to a later phase (human, 2026-11-07): non-DOI work-level identifier disagreements (OpenAlex work ID, Semantic Scholar paper ID, repository URL, arXiv ID) keep producing CONFLICT, unruled.
- Non-DOI work-level identifier disagreements (OpenAlex work ID, Semantic Scholar paper ID, repository URL, arXiv ID) still produce CONFLICT; the human has not ruled.
- Candidate generation pairs authors with their own coauthors (undirected `name-coauthor` blocks), which produced several pairs of different people; to examine in a later phase (hadoop-owned blocking).
- Only the exact-id path accepts in the gold evaluation; the LLM path decided 0 pairs (11 eligible, offline stub abstained). Thresholds are provisional; every number is hand-set and unvalidated.
- `candidate-features` reads `blocking-members` unconditionally and would fail at submission if blocking emitted nothing.
- No resolved-entity counter: the DBLP extraction does not count how many named entities were resolved.
- CJK / non-Latin matching covers same-script keys only (original characters kept; a separate folded key is used for matching; cross-script transliteration is not matched).
- Evaluation limits: gold-v3 mixes 77 deliberate-pace labels with 153 fast pass-3 labels; 5,766 same-name agreeing-context pairs were excluded from the export, so precision does not measure the probabilistic path; 12 clear-non-match pairs labeled "same" are likely labeling errors; one reviewer; the arXiv key joined one real pair (adcec88c, DBLP CoRR `journals/corr/abs-2603-05518` with OpenAlex `W7134253765`) without changing any outcome, and the position-CONFLICT rung did not fire on real data.
- Two same-name coauthors listed in a different order by two sources would be falsely REJECTED by the position rule (names cannot tell them apart); how many frozen-sample pairs hit that is unknown.
- Dataset provenance: the manifest `retrieved: 2026-11-07` has basis `file-mtime` (inferred from the dump file's modification time, not a recorded download time); `release_specific_url` is UNVERIFIED; the "download probe returned HTTP 200" statement is operator-supplied and unverified.
- Evidence gaps: the hadoop-engineer repair-round-2 report is still PARTIAL (report body swallowed by the stop-gate; the test-engineer's full report was received, see repair-round2-reports.md); untracked files cannot be diffed against HEAD (formatter collateral inside new files, "no test weakened", "weights unchanged" rest on tests and agent statements); the Phase 4 pipeline prints no counters.
- `docs/agent-runs/.DS_Store` is untracked junk; keep it out of the commit.
- `datasets/slice/` and `slicetool.py` are retired leftovers; `subsettool.py` imports `slicetool.py`, so the script (and `test_slicetool.py`) must be committed even though no slice is registered.
