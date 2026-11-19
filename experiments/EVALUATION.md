# Phase 16 evaluation: measured facts (frozen outputs only)

Author: experiment-engineer. Work orders W2-DOC1 (sections 1 to 5, 2026-11-18), W2-DOC2 (sections 6 to 8 and the corrections of decision P16-42, 2026-11-19) and W2-DOC3 (2026-11-19: `h-audit-2` figures, the H spend wording of P16-44, the H-1c reading of P16-43 and the SUMMARY.txt rewrite note), W2-DOC4 (2026-11-19: section 6.4, the human rating sample, from the frozen rating file; decisions P16-42 and P16-46), W2-DOC5 (2026-11-19: the human's H-8 provenance and H-9 factory audit results, sections 1.2, 2.3, 2.3a and 8), W2-DOC6 (2026-11-19: section 7 reproducibility, the clean-checkout build and the phase-gate `sbt clean check`, and the lines that called that build pending in sections 8 and Sources) and W2-DOC7 (2026-11-19: the human-relayed H-12 B to F regeneration, sections 7 and 8 and Sources) and W2-RB18 (2026-11-19: the H-12 counts corrected per decision P16-47 and review 25 findings 1 to 4, sections 7 and 8). Path: `experiments/EVALUATION.md` (plan.md WO-9 write set; `docs/evaluation.md` is WO-10's interpretation document and is not this file). Status: written for independent review; nothing here is a reviewed or accepted result.

Rules for this file:

- Every number is copied from the file named beside it. I computed no statistic. Where I did a subtraction, a sum or a count by reading, it is marked "arithmetic" or "counted" and the inputs are shown.
- Interpretation is kept out. "Limits" paragraphs repeat what the repository already records (benchmark README "Threats to validity", `experiments/evaluation/er-README.md`, `experiments/g-retrieval/README.md`, `experiments/h-answers/README.md`, `experiments/LIMITATIONS.md`, decisions P16-8 and P16-24).
- `RUNS` = `/Users/winnguyen/scholarchain-data/experiments/runs/phase16`; `SCD` = `/Users/winnguyen/scholarchain-data`.
- Redaction (decision P16-42): some run files (`run-record.env`, `raw/entrypoint.log`, some manifests) hold the AWS account id, in bucket names or as a field. This file copies no bucket name, ARN or account id, and no 12-digit number. File hashes are shown as the first 10 hex characters followed by an ellipsis; the full value is in the cited field. Question and answer text is not copied: the answer and rating files are cited for aggregate numbers only.
- A number no frozen file holds is written NOT DERIVED.

Structure (plan.md "Metrics per group", WO-9): 1 graph correctness; 2 agentic factory; 3 benchmark (inputs to groups 3 and 4); 4 performance and elasticity; 5 the synthesis truncation finding; 6 retrieval (G), answer quality (H), the independent audit, the human rating sample and entity resolution (replay and ErExtras); 7 reproducibility (plan group 7); 8 known items.

---

## 1. Graph integrity (plan group 2)

Run `graphchecks-a-export-2`, tool `graph-checks/1.2.0`, seed 16, sample 50, started 2026-11-18T21:25:05Z. Source: `RUNS/graphchecks-a-export-2/graph-check-report.json` and `run-info.json`. Input: A's export `SCD/rehearsal/a-export`, `sourceRunId` `run:v1:3cfb57b1-abd1-4913-8e53-da477d8d6027` (report `expectedRunId`, basis `export-manifest.json sourceRunId`), export manifest sha256 `b3e9985647…` (`run-info.json`). Status `COMPLETE`, `missingTables` empty. Console line (human, `human-commands.md` 15:25 row, log `phase16-graphchecks-2.log`): `[success] Total time: 3 s`.

### 1.1 Row totals (`rowTotals`)

| Table | Rows | Table | Rows |
|---|---|---|---|
| communities | 6,663 | nodes-institution | 469 |
| community-cross-edges | 541 | nodes-researcher | 17,569 |
| community-membership | 35,160 | nodes-topic | 473 |
| component-membership | 35,160 | nodes-venue | 1,342 |
| components | 6,171 | nodes-work | 3,556 |
| edge-support | 101,478 | path-evidence | 42 |
| edges-authored | 21,268 | researcher-metrics | 35,160 |
| edges-coauthored | 98,661 | semantic-summaries | 41,885 |
| edges-published-in | 3,488 | | |

`duplicateIds`: 0 in each of the 17 tables (`duplicateRate[...]` 0 for each).

### 1.2 Counters and rates (`counters`, `rates`)

| Counter | Value | Counter | Value |
|---|---|---|---|
| edgesTotal (distinct `edgeId` of edges-coauthored) | 98,661 | orphanResearchers | 0 |
| coauthorEdgesTotal | 98,661 | orphanWorks | 0 |
| danglingEdgeEndpoints | 0 | pathHopsChecked | 27 |
| danglingWorkRefs | 0 | pathHopsNotInEdges | 0 |
| edgesWithoutSupport | 0 | provenanceMissing | 0 |
| jointCountMismatches | 0 | provenanceRunIdMismatches | 0 |
| supportRowsToMissingEdges | 0 | traceIncomplete | 0 |

Rates: `edgeSupportRate` 1; `jointCountAgreementRate` 1; `orphanResearcherRate` 0; `orphanWorkRate` 0; `danglingEndpointRate` 0; `pathHopFailureRate` 0; `provenanceMismatchRate` 0. `provenanceMismatchesByTable`: 0 in all 17 tables. Detail: `edges[coauthor:v1]` 19,483; `edges[mention-coauthor:v1]` 79,178 (arithmetic: 98,661, equals edgesTotal); `edgesWithoutSupport` 0 for both kinds.

Provenance trace: `traceSampleSize` 50, `traceSampled` 50, `traceIncomplete` 0 (full trace lines in `RUNS/graphchecks-a-export-2/provenance-trace.jsonl`; not read). 

Human check of traced edges (plan H-8; `human-commands.md` "Results recorded", 2026-11-19, H-8 row; recorded by W2-DOC5). The sheet was made by Claude in chat with its own script over the frozen trace, independent of agent code: `SCD/phase16-h8-provenance-sheet.md` (a `.html` copy is named in the row; not read). The human relayed: 45 of the 50 traced edges have a DBLP supporting work; the sheet used the first 10 of those 45; the human checked those 10 on dblp.org; 10 of 10 OK (both coauthors on the DBLP record and the year inside the edge's window). Selection rule, as given: the first 10 DBLP-backed edges in the trace order, not a fresh random draw (the 50 are GraphChecks' seeded sample, seed 16; the sheet header states the same rule). I read the sheet and confirm it holds 10 rows, with the OK column blank (the result is the human's relay; the sheet is not the marked record). The "45 of 50" is the human's figure; I did not read `provenance-trace.jsonl` and did not recount it. The 5 non-DBLP traced edges were outside the check: they have no recorded check. Counted by reading, the 10 sheet rows carry window "all" on 6 and "2020-2029" on 4 (arithmetic 6 + 4 = 10). Names and titles are not copied. Limits: one auditor, 10 edges, all DBLP-backed and taken in trace order; the check covers both coauthors on the record and the year in the window only, and does not cover the edge's `jointPublicationCount` or the supporting-row count.

Path hops: 27 hops checked of the 42 `path-evidence` rows; matching rule exact `edgeId`, then endpoints, then window, no normalising (decision P16-8).

### 1.3 Reconciliation with the projected Neptune load (`reconciliation`; informational, not a pass or fail)

Expected-figure source (`graph-check-report.json` `reconciliationBasis`, `run-info.json` `expectedSource`): `SCD/logs/phase14-rehearsal-graph-2.log:20` (expected vertices 32,225, expected edges 135,788). The basis text says these are the projected counts of a rehearsal (the in-memory `NeptuneExportReport`), not the count of a Neptune load. The log file itself was not re-read.

| Quantity | Value |
|---|---|
| edges-authored + edges-published-in + edges-coauthored (`edgeTablesRowSum`) | 123,417 |
| RESOLVES_TO edges (`resolvesToEdges`; one per edges-authored row whose `mentionId` differs from `researcherNodeId`) | 8,815 |
| DERIVED_FROM edges (`derivedFromEdges`; one per nodes-work row) | 3,556 |
| `composedEdges` | 135,788 |
| `expectedEdges` | 135,788 |
| `edgeDifference` | 0 |
| vertex tables sum (`vertexTablesRowSum`: researcher, work, venue, topic, institution) | 23,409 |
| AuthorMention vertices (`resolvedMentionVertices`) | 8,815 |
| DatasetSnapshot vertices (`snapshotVertices`; distinct `provenance.snapshotId` of nodes-work) | 1 |
| `composedVertices` | 32,225 |
| `expectedVertices` | 32,225 |
| `vertexDifference` | 0 |

Arithmetic from the file's own figures: 123,417 + 8,815 + 3,556 = 135,788; 23,409 + 8,815 + 1 = 32,225. The report also keeps `edgeTablesRowSumMinusExpected` -12,371 and `vertexTablesRowSumMinusExpected` -8,816, which are the bare table sums against the projected totals.

Coauthor edges per window (`edges-coauthored[window=...]`): 1900-1999 76; 2000-2004 398; 2005-2009 1,468; 2010-2014 3,342; 2015-2019 7,640; 2020-2029 36,486; all 49,251 (arithmetic: sum 98,661).

Earlier run `graphchecks-a-export-1` (08:34, no expected-figure input) reported `edgeDifference` -12,371 (`human-commands.md` 08:34 row; decision P16-21). That figure is a definitional difference, not an integrity finding: the bare edge-table sum omits the RESOLVES_TO and DERIVED_FROM edges, which have no export table (`reconciliationBasis`; decision P16-21 and open decision OD-3, closed by the 15:25 rerun). In `-1` all integrity counters were also 0 and `edgeSupportRate` 1 (human reading, `human-commands.md`; the `-1` report files were not read).

### 1.4 Limits

- The export is checked against itself and against the projected counts of a rehearsal; the checks do not read Neptune (`reconciliationBasis`).
- Ground truth and retrieval share this export (plan, "Threat stated up front").
- Known-graph unit tests of the six algorithms (plan group 2) and the counts from the human's `sbt clean check` log are not part of this section: NOT DERIVED here.
- Tool version `graph-checks/1.2.0`; `export-manifest.json` per-file sha256 verification by GraphChecks: not stated in the report (NOT STATED).

---

## 2. Factory process counts (plan group 6)

Run `factorycounts-1`, tool `factory-counts/1.1.0`, mapping sha256 `399ca4bd31…`, started 2026-11-18T14:41:24Z (`RUNS/factorycounts-1/run-info.json`). Scope: `docs/agent-runs/phase-01` to `phase-15` (`factory-counts.json` `phases`). Sources: `RUNS/factorycounts-1/factory-summary.txt` (every line below), `factory-counts.json`. Per-line evidence is in `factory-evidence.tsv` (not read). Every figure is a parser count over markdown files, labelled "approximate" by the tool where it says so.

### 2.1 Totals (`factory-summary.txt` lines 1-11)

| Quantity | Value |
|---|---|
| markdown files read / non-markdown skipped | 641 / 47 |
| BLOCKED-ON events (status lines in report-like files; approximate, deduplicated) | 24 from 27 lines; other mentions 264 |
| ownership violations caught (approximate, deduplicated; negations excluded) | 0 from 0 lines |
| reviewer findings parsed | 235: blocker 29, major 63, minor 117, advisory 3, unclassified 23 |
| review files with no parsed finding | 44 |
| repair files / scopes with review rounds | 100 / 17 |
| human command files / blocks / human rulings | 5 / 22 / 135 |
| hook or guard refusal prose | 153 lines, 150 events; harmless reads 33, state-changing 37, unclassified 80 (partial; keyword classes) |
| first-pass pairs (scope, agent) / reviewed | 86 / 74 |
| human-annotated rewrites | 0 |
| unclassified files | 0 |

(Arithmetic: 29 + 63 + 117 + 3 + 23 = 235; 33 + 37 + 80 = 150.)

### 2.2 First pass per agent, raw counts (`factory-summary.txt` lines 12-27)

"No finding" = the first review verdict of the pair parsed with no finding. "No blocker or major" = no blocker and no major finding. Counts are x of y first-pass pairs.

| Agent | No finding | No blocker or major |
|---|---|---|
| build-engineer | 5/8 | 7/8 |
| code-reviewer | 6/6 | 6/6 |
| dataset-engineer | 1/3 | 2/3 |
| deploy-engineer | 1/1 | 1/1 |
| documentation-engineer | 1/1 | 1/1 |
| domain-engineer | 3/4 | 3/4 |
| entity-resolution-engineer | 0/2 | 1/2 |
| experiment-engineer | 2/4 | 3/4 |
| factory-engineer | 3/4 | 3/4 |
| hadoop-engineer | 2/4 | 3/4 |
| infra-engineer | 3/5 | 3/5 |
| rag-engineer | 4/5 | 5/5 |
| service-engineer | 0/3 | 3/3 |
| spark-engineer | 5/6 | 5/6 |
| storage-engineer | 3/4 | 3/4 |
| test-engineer | 6/14 | 10/14 |

No agent is shown as a percentage because the denominators are 1 to 14 (decision P16-8: stated as a threat to validity). The tool does not state how a `code-reviewer` pair is defined; the 6/6 row is reported as printed.

### 2.3 Human notes (decision P16-24)

- 44 review files were left unparsed and 23 findings are unclassified (`factory-summary.txt` lines 4-5 agree: 44 and 23).
- 0 ownership violations were counted (`factory-summary.txt` line 3); the same file counts 150 hook or guard refusal events, of which 37 are keyword-classed state-changing and 80 unclassified. The two counters measure different things (a keyword class over prose lines); this file draws no link between them.
- H-9 (the human audits the counts for one phase): recorded in 2.3a below (human's audit of Phase 14, `human-commands.md` H-9 row, 2026-11-19).
- Human-annotated rewrites: 0 (`factory-summary.txt` line 10; the rewrites input is `experiments/evaluation/factory-rewrites.tsv`, not read). The human's statement (H-9 Q2, 2.3a): no substantial human rewrites, "none, confirmed by the human"; the tsv stays empty. The 0 stands as the human's statement, not as a parser finding.

### 2.3a Factory-count precision: the human's audit of Phase 14 (plan H-9; recorded by W2-DOC5)

Sources: `human-commands.md` "Results recorded", H-9 rows, 2026-11-19; the sheet `SCD/phase16-h9-factory-sheet.md` (made by Claude in chat with its own script over the frozen `factorycounts-1` output, independent of agent code). The human's relay row in `human-commands.md` (H-9, 2026-11-19) states that the categories were sampled with seed 16. The human marked the items. I read the sheet for the item lists and the Phase 14 counts it prints; its OK column is blank, so the marks below are the human's relay. No count here is recomputed. `factory-counts.json` was read only at lines 168, 262, 265, 453-455 and 462-464, for comparison (cited in 149 and 169); `factory-evidence.tsv` was not read.

Phase 14 counts as printed on the sheet: BLOCKED-ON events 6, BLOCKED-ON mentions 71, first-pass "no review" 12, hook or guard refusal prose 9, human rulings 26, repair files 5. Of these, BLOCKED-ON events 6 and repair files 5 match the frozen `factory-counts.json` (`blockedOnEventsByPhase` "phase-14" at line 262; `repairFilesByScope` "phase-14" at line 168). Mentions 71, refusals 9 and rulings 26 have no per-phase field in the JSON (totals only: 264 at line 265, 153 prose lines and 150 events at lines 454-455, 135 at line 453), so they rest on the sheet.

Items audited (the sheet's draw: all 6 BLOCKED-ON events, all 5 repair files, 5 of 26 human rulings, 4 of 9 refusals) and the human's marks:

| Category | Audited | Correct | Share (arithmetic) |
|---|---|---|---|
| BLOCKED-ON events | 6 | 5 | 5/6 |
| Repair files | 5 | 5 | 5/5 |
| Human rulings | 5 | 3 | 3/5 |
| Hook or guard refusals | 4 | 3 | 3/4 |
| Total counted items | 20 | 16 | 16/20 = 80% |

(Arithmetic: 6 + 5 + 5 + 4 = 20; 5 + 5 + 3 + 3 = 16.)

False-positive kinds the human named (4 of 20 items):

- A status line "BLOCKED-ON OWNER: none" was counted as a BLOCKED-ON event (BLOCKED-ON events item 5; it is not a blocked state).
- A deploy result (item D24) and account facts (item D1) were counted as human rulings (2 of the 5 rulings).
- A read-only note (`report-51` line 95) was counted as a hook or guard refusal.

Question 1 (the miss): Phase 14 reviews are recorded in `decisions-d33.md`, `decisions-d34.md` and `decisions-d35.md`, not in verdict files. The tool's Phase 14 first-pass "without review" count of 12 (the human's wording; the figure is printed on the sheet) is therefore wrong. It is a known parser limit for Phase 14: the tool pairs reviews through verdict files. The frozen `factory-counts.json` (lines 462-464) holds `firstPassPairs` 86, `firstPassPairsReviewed` 74 and `firstPassPairsWithoutReview` 12 as totals over phases 1-15, with no per-phase first-pass field, so attributing the 12 to Phase 14 rests on the sheet and the human. The 2.2 denominators sum to 74 (8 + 6 + 3 + 1 + 1 + 4 + 2 + 4 + 4 + 4 + 5 + 5 + 3 + 6 + 4 + 14 = 74), which equals `firstPassPairsReviewed`, so the 12 unreviewed pairs appear in no 2.2 row: the missed reviews exclude those pairs from 2.2 rather than miscount rows. This is not recomputed here; which pairs and which agents the 12 belong to is NOT DERIVED.

Question 2: no substantial human rewrites (the human's answer; "none, confirmed by the human"). `factory-rewrites.tsv` stays empty, and no FactoryCounts rerun was made.

Limits: one auditor, one phase (Phase 14 of the 15 counted), a sample of 20 items, and 2 categories audited in full and 2 by a draw (human rulings 5 of 26, refusals 4 of 9); the 80% is a share of this sample, not a precision of the tool across phases. Two categories were not audited at all: BLOCKED-ON mentions (71) and first-pass counts apart from the miss in Question 1.

### 2.4 Limits

- File conventions drift across phases (plan.md survey finding 9: phase 5 descriptive names, phases 12 to 14 numbered, phase 15 `wave-N/`; verdict and severity formats differ), so first-pass pairing depends on the mapping table `experiments/evaluation/factory-mapping.tsv` (sha256 above).
- Hook refusals are recorded only as prose; the refusal figures are partial and keyword-classed (summary line 8).
- The counts are not an acceptance rate of agents' work: small denominators, first review verdict only.

---

## 3. Benchmark v1 (inputs to plan groups 3 and 4)

Sources: `experiments/benchmark/BENCHMARK-MANIFEST-v1.json` and `experiments/benchmark/README.md`. Generator `benchmark-gen/1.0.0`, seed 20261117, `sourceRunId` `run:v1:3cfb57b1-abd1-4913-8e53-da477d8d6027`, export manifest sha256 `b3e9985647…` (manifest). Files after the free-form fill (manifest `files`; full sha256 values in the manifest):

| File | Rows | sha256 |
|---|---|---|
| questions-v1.jsonl | 56 | `0faab24b88…` |
| labels-v1.jsonl | 56 | `f7cf669519…` |
| free-form-slots-v1.jsonl | 8 | `97a357a31d…` |

`generatedQuestions` 48, `freeFormSlots` 8, `plannedTotal` 56, `freeFormQuestionsFilled` 8. No model and no prompt produced a question or a label (README, first paragraph).

### 3.1 Counts by stratum (`countsByStratum` against `requestedCounts`)

| Stratum | Requested | Produced |
|---|---|---|
| researcher-lookup | 7 | 7 |
| coauthor-lookup | 8 | 8 |
| ranking | 10 | 10 |
| shortest-path | 6 | 4 |
| community-component | 6 | 6 |
| temporal-comparison | 5 | 5 |
| unanswerable | 8 | 8 |
| free-form | 8 | 8 |
| total | 58 | 56 |

(Arithmetic: requested 7+8+10+6+6+5+8+8 = 58; produced 7+8+10+4+6+5+8+8 = 56. The plan's total of 58 to 60 included an optional bridge stratum of 0 to 2; the manifest has no bridge-analysis stratum, so bridge analysis is not covered by v1 as a stratum. Free-form question FF-06 ranks by `bridgeScore`.)

Shortfall (manifest `shortfalls`): `shortest-path (absent): wanted 2, produced 0`. The two planned pairs absent from `path-evidence` could not be produced. Per decision P16-24 the human notes that shortest-path questions are all length 1 with mention ids; the spot-check rows 11 and 12 (`SCD/phase16-label-spotcheck.md`) show two such questions (path length 1, edges `mention-coauthor:v1:...`). The remaining two shortest-path labels were not read.

### 3.2 Vector coverage checks (manifest `vectorCoverage`)

Mode `checked`, vector export `SCD/rehearsal/s-export`, 41,885 summaries, rows match the manifest (`vectorExportRowsMatchManifest` true). Checks: `sha256` true, `bytes` true, `rows` true, `by_summary_type` true, `by_window` true; `sourceFilesMatchManifest` true (three part files: 20,000 + 20,000 + 1,885 rows, sha256 and bytes equal to the chunk set manifest). `coverageMismatchOverride`: checked true, accepted false, no failed checks. Console line (human): `VECTOR_COVERAGE sha256=true bytes=true rows=true by_summary_type=true by_window=true summaryOverlap=true`.

Deleted set rebuilt from the chunk set selection rule: 2,000 keys; by type community 314, researcher-profile 1,685, work 1; by window 2000-2004 14, 2005-2009 44, 2010-2014 97, 2015-2019 205, 2020-2029 638, all 1,001, not-windowed 1; each equals the chunk set manifest (`deletedByTypeMatchesManifest`, `deletedByWindowMatchesManifest` true). Overlap: summary ids shared 41,885 of 41,885; graph summary subjects 17,552, vector summary subjects 17,552, shared 17,552; graph researchers 17,569, of which 17,490 are a subject in the vector export (arithmetic: 79 are not).

Questions with `vectorAvailable=false` (from G `summary.json`, `scopes.all` against `scopes.vectorAvailable`, `questions` field): the vectorAvailable scope holds 30 of the 56 questions (strata: researcher-lookup 6, coauthor-lookup 8, shortest-path 4, community-component 5, temporal-comparison 4, free-form 3; arithmetic 6+8+4+5+4+3 = 30). The `vectorAvailable` scope has no `ranking` and no `unanswerable` entry. The labels file itself was not read for this count.

### 3.3 Label rules and checks

Label rules per stratum are in `experiments/benchmark/README.md` "Label rules" (items keyed on `subjectId` or hop `edgeId`, ranking grades tiered 3/2/1 by rank per P16-10, tie across the cut-off refused, shortest-path label is `NotPrecomputed` or abstain and not an abstention, six abstention classes mapped to runtime codes by `AbstentionCodes.byClass`).

- Label spot-check (H-6; decisions P16-22 and P16-42): human spot-check 15 of 15 OK (the sheet); Claude in chat's independent machine check covered 25 labels (the 15 sheet rows plus 10 more ranking labels), 25 of 25 MATCH. The sheet is `SCD/phase16-label-spotcheck.md` (15 rows: 3 ranking, 3 coauthor, 2 temporal-comparison, 2 community, 2 shortest-path, 1 researcher lookup, 2 abstention). The independent check reads the export directly, not BenchmarkGen's code.
- Regeneration (m4; `human-commands.md` 14:23 row): BenchmarkGen with the 08:40 flags into a scratch directory at generator commit `afaa244b`, exit 0; `BENCHMARK RESULT: 48 questions, 8 free-form slots, shortfalls 1`; sha256 of `questions-v1.jsonl` (`52cd1c1bc4…`), `labels-v1.jsonl` (`def1c39018…`) and `free-form-slots-v1.jsonl` (`a86ad19ca9…`) equal the committed files before the fill (full values in the manifest `changes[0].previousFiles`). Three `REGEN_MATCH` lines, run by Claude in chat (P16-5).
- Free-form fill (`BenchmarkFill`, 2026-11-18T21:14:10Z, decisions P16-23 and P16-26; manifest `changes[0]`): the 48 generated lines stay byte-identical (the new files are the old bytes plus 8 appended lines). Check values (`changes[0].checks`, equal to Claude in chat's pre-checks per `human-commands.md`):
  - Q-FF-01 Osvaldo Simeone, 0 same-name mentions, window 2005-2009, coauthorCount 3, maxJoint 1.
  - Q-FF-02 Yuanqing Xia, window 2020-2029, groupSize 7, itemsListed 14.
  - Q-FF-03 Yuanqing Xia, publicationCount 4 (2015-2019) and 4 (2020-2029), distinctCoauthorCount 11 and 14.
  - Q-FF-04 Wentao Li, 0 same-name mentions, window 2020-2029, publicationCount 2, distinctCoauthorCount 10, coauthorsListed 10.
  - Q-FF-05 distinctCoauthorCount, 2015-2019, N 4, rankedTotal 858, values 30, 27, 26, 22, place 5 = 21.
  - Q-FF-06 bridgeScore, all, N 3, rankedTotal 5,037, values 0.8772, 0.8327, 0.8298, place 4 = 0.8109.
  - Q-FF-07 absent name "Elara Moonwhistle", 0 names in the export, class unknown-researcher, expected code `identity-unresolved`.
  - Q-FF-08 class unrankable-metric, 0 citation keys among the 17 metric keys, expected code `researcher-count`.
- Free-form texts and intended operations: decision P16-23.

### 3.4 Generator state

`generatorCommit` `51814c3573…` (manifest top level), `generatorTreeDirty` true (uncommitted Wave 2 files at generation, `human-commands.md` 08:40 row). The fill entry records commit `afaa244b11…` and also `generatorTreeDirty` true. `scalaVersion` "2.13.16" is the Scala 2.13 standard library version, not the build's Scala (3.3.7, `scala3LibraryVersion`); the manifest says so (`changes[0].note`).

### 3.5 Limits (benchmark README, plan, G and H READMEs)

- Ground truth and retrieval share one export: retrieval and answer metrics measure plan, resolve and retrieve fidelity to the graph, not real-world truth.
- Researcher-lookup recall in graph mode is capped by what the runtime returns (metrics only, no coauthors, `Workflow.scala:569-584`); compare modes on the subject item (grade 2) for that stratum (README "Threats to validity").
- Template wording matches the planner rules, so planner accuracy on the 48 generated questions is overstated; the 8 free-form questions exist to test that (README "Other fields").
- No label `operation` may be compared with a recorded runtime `operation` until a mapping is written and tested (README "Operation vocabulary").
- Mixed provenance: vectors come from the earlier s-export ingest, the graph from A (P15-44); 2,000 vector keys were deleted after E, so a vector-mode subject can lack vectors (labels carry `vectorAvailable`, `vectorStatus`).
- Shortest-path questions are limited to pairs in the 42 `path-evidence` rows (plan risks; `LIMITATIONS.md` L8).
- The `out-of-scope` abstention code is the stub's behaviour; a real model may name an operation instead (README).
- One human wrote the free-form questions and checked 15 labels (single rater).

---

## 4. Phase 15 performance and elasticity, with the D bootstrap intervals (plan group 5)

Source for A to F: `experiments/RESULTS.md` (author experiment-engineer, Phase 15; its own status line: "nothing here is a reviewed or accepted result"; Phase 15 was accepted by the human, P15-48 per `docs/agent-runs/phase-15/ratification.md`) and `experiments/LIMITATIONS.md` (L1 to L27). Region `us-east-2`, tier S, dataset snapshot `scholarchain-s-20261107-10dd7f34121b`. Numbers are not recomputed here. Every Phase 15 answer-model latency below is the offline stub's, not Haiku's (RESULTS.md section 3.D; LIMITATIONS L15).

### 4.1 Runs (RESULTS.md section 1)

| Exp | Run id | Final status | Elapsed | Est. spend / ceiling (USD) |
|---|---|---|---|---|
| A | `exp-A-20261117T161757Z-5c69ba90` | COMPLETED | 3,682 s | 0.61 / 0.75 |
| B | `exp-B-20261117T185928Z-5c69ba90` | COMPLETED | 4,858 s | 2.09 / 2.40 |
| C | `exp-C-20261118T014553Z-38421c6f` | COMPLETED | 509 s | 0.06 / 0.60 |
| D | `exp-D-20261118T002157Z-38421c6f` | COMPLETED | 2,896 s | 0.44 / 0.65 |
| E | `exp-E-20261118T012347Z-38421c6f` | COMPLETED | 673 s | 0.08 / 0.45 |
| F1 | `exp-F-20261118T011431Z-38421c6f` | COMPLETED by the harness; INVALID as a result | 245 s | 0.03 / 0.25 |
| F2 | `exp-F-20261118T013740Z-38421c6f` | COMPLETED | 246 s | 0.03 / 0.25 |

Costs are hourly burn x elapsed time (unverified rates), not bills (RESULTS.md section 6). Billed Phase 15 cost: PENDING in RESULTS.md. The H-1 Phase 15 reconciliation is decision P16-15: 8.91 USD month-to-date at 2026-11-18 08:07 and about 2.73 USD for Phase 15 session 2 so far (`human-commands.md` 08:07 row); a later reading, 8.93 USD month-to-date at 18:29 (P16-39). A single reconciled Phase 15 billed total beyond these readings is NOT DERIVED in the files read.

### 4.2 Results by experiment (as RESULTS.md section 3 gives them)

- A (baseline): NOT DERIVED for input records, task counts, shuffle and failures. Recorded: pipeline SUCCEEDED, 61.3 min (16:18:00Z to 17:19:19Z), export 17 tables, 34 objects, 373,888,058 bytes; `path-evidence` holds 42 rows (P15-38).
- B (EMR managed scaling, policy 1 to 3 instances): `scale_out_observed` yes, `scale_in_observed` yes; scale-out first requested 19:08:13Z, provisioned 19:10:51Z (request to ready 157 s); peak requested and provisioned instances 4 and 4; scale-in first release 19:34:14Z, last 20:04:34Z; pipeline step 3,526 s. Two caveats carried from the derive: the third release (20:04:34Z) is `INSTANCE_FAILURE`, not `TERMINATED`; `cluster_ended_utc` 20:05:37Z precedes `terminate_cluster_entered_utc` 20:19:12Z. MapReduce alone ran across the core nodes (L4).
- C (embedding backlog): 2,000 chunks; peak backlog 1,804 at 01:47:04Z; drain 406 s; throughput 4.926 msg/s; workers desired 1 to 4 and back to 1; oldest message age max 341 s; worker CPU max 23.78 %, memory max 20.83 %. C-only latency and retry count NOT DERIVED (window overlap with F2, L11).
- E (retrieval modes, mixed provenance, stub model, 351 requests per mode):

| Mode | OK/requests | Error rate | ok p50 (ms) | ok p95 (ms) | ok p99 (ms) | Abstained |
|---|---|---|---|---|---|---|
| graph | 351/351 | 0.000000 | 26.847 | 38.124 | 81.316 | 17 |
| vector | 350/351 | 0.002849 | 332.538 | 469.376 | 3,899.090 | 0 |
| fused | 351/351 | 0.000000 | 342.416 | 512.055 | 1,802.811 | 83 |

  Stage p50 and p95: graph stage 4 and 12 ms (graph and fused modes); vector stage 315 and 453 ms (vector mode), 317 and 491 ms (fused). Fused skipped the vector stage on 17 requests; the cause is UNVERIFIED (RESULTS.md).
- F2 (one injected fault: a poison message plus a duplicate, 52 messages): verdict PASS 9, FAIL 0, INFO 3, UNKNOWN 0; drain 226 s. F1 is INVALID and not derived (L10).

### 4.3 D (query load), point estimates (RESULTS.md section 3.D; same values as `latency-by-level.csv` in `/Users/winnguyen/scholarchain-data/phase16-ci-on/`)

| Level | Clients | Requests | OK | Errors | Error rate | ok p50 (ms) | ok p95 (ms) | ok p99 (ms) | ok max (ms) | OK throughput (req/s) | Abstained |
|---|---|---|---|---|---|---|---|---|---|---|---|
| L0-c1 | 1 | 134 | 134 | 0 | 0.000000 | 511.345 | 930.569 | 2,442.285 | 4,371.113 | 0.2792 | 23 |
| L1-c8 | 8 | 1,130 | 1,130 | 0 | 0.000000 | 337.545 | 597.965 | 3,300.535 | 9,010.578 | 2.3527 | 226 |
| L2-c24 | 24 | 2,840 | 2,837 | 3 | 0.001056 | 507.921 | 3,807.554 | 5,868.466 | 9,837.529 | 5.8717 | 687 |
| L3-c48 | 48 | 3,324 | 3,310 | 14 | 0.004212 | 3,768.982 | 7,990.338 | 10,130.334 | 14,017.316 | 6.8456 | 617 |

Total 7,428 requests (arithmetic 134 + 1,130 + 2,840 + 3,324 = 7,428). Each level is 480 s of load. Zero-evidence responses: 0 at every level. Service-log stage view (RESULTS.md): graph-retrieval p50 3 to 4 ms and p95 12 to 27 ms at all four levels; vector-retrieval p50 485, 311, 416, 3,250 ms and p95 862, 505, 3,578, 6,495 ms at 1, 8, 24, 48 clients. ECS service tasks 1 to 3 (scale-out at 00:43:03Z and 00:46:03Z, scale-in at 01:03:42Z and 01:09:45Z); Neptune CPU 10.489 to 13.265 %.

### 4.4 D latency bootstrap confidence intervals (Phase 16 WO-5)

Derive run by Claude in chat (P16-5): the WO-5 derive with the interval option on, over the Phase 15 D requests; `cmp` of `latency-by-level.csv` between the option-off and option-on outputs returned exit 0, so the point estimates are unchanged by the option (`human-commands.md`, 2026-11-18 rows; logs `phase16-wo5-derive-off.log` and `phase16-wo5-derive-on.log`). Outputs are in the local directory `/Users/winnguyen/scholarchain-data/phase16-ci-on/` (`latency-ci-by-level.csv`, `latency-by-level.csv`, `SUMMARY.txt`). `SUMMARY.txt` lines 2 and 3 cite these local paths (review 14 finding 6); this file cites only the local directory above. SUMMARY.txt rewrite (decision P16-44, closing P16-42 item 5): Claude in chat rewrote the paths in `SUMMARY.txt` of both `phase16-ci-on` and `phase16-ci-off` on 2026-11-19 at about 07:05 CST, replacing the sandbox prefixes with `/Users/winnguyen/` (prefixes only, no other content changed, 0 sandbox paths remain; the CSVs were not touched, so the point estimates and intervals above are unaffected).

Method (`SUMMARY.txt`): percentile method nearest-rank (`rank = ceil(p*n/100)`, no interpolation); bootstrap 1,000 resamples, seed 16; interval = percentile 2.5 to 97.5 (nearest rank); iid resampling of the OK latencies; group by level; input `derived-requests/requests.jsonl` sha256 `8527ccdc01…` (7,428 requests) and `events.jsonl` sha256 `e0fbee192f…`.

| Level | OK n | p50 (ms) | p50 95% CI (ms) | p95 (ms) | p95 95% CI (ms) |
|---|---|---|---|---|---|
| L0-c1 | 134 | 511.345 | 483.507 to 552.356 | 930.569 | 862.873 to 1,067.968 |
| L1-c8 | 1,130 | 337.545 | 333.649 to 341.383 | 597.965 | 526.897 to 653.099 |
| L2-c24 | 2,837 | 507.921 | 471.240 to 544.331 | 3,807.554 | 3,604.378 to 4,043.458 |
| L3-c48 | 3,310 | 3,768.982 | 3,676.988 to 3,903.322 | 7,990.338 | 7,872.538 to 8,185.108 |

(Source: `latency-ci-by-level.csv`, columns `ok_p50_ms`, `ok_p50_ci95_lo_ms`, `ok_p50_ci95_hi_ms`, `ok_p95_ms`, `ok_p95_ci95_lo_ms`, `ok_p95_ci95_hi_ms`; each point estimate equals the `latency-by-level.csv` value in 4.3.)

### 4.5 Limits (RESULTS.md, LIMITATIONS.md)

- The bootstrap resamples requests as independent and identically distributed. Requests in one level come from 1 to 48 concurrent clients over 480 s and are not independent (autoscaling changed capacity during L2 and L3), so the intervals describe sampling variation under the iid assumption only. Only 2xx latencies enter; errors are counted separately.
- One load-client ECS task ran the whole schedule; client saturation was not measured (L22). The p99 and max have no interval.
- The answer model was the stub; no Haiku latency, cost or quality is measured in D, E, F or C (L15). Haiku laptop-to-Bedrock latency from Phase 16 appears in section 5 and section 6.2.
- D's abstention counts (23, 226, 687, 617) were not explained at the time: the abstention reason was not captured (L17; finding 5, addressed by WO-5 for later runs).
- Neptune is shown by CPU and pending requests only; DynamoDB throttles returned NO_DATA; no separately scaled vector, rank or synthesis service (L23).
- E is mixed provenance (L9); A is not derived (L2); costs are estimates (L25).
- Elasticity timings in B (hours) and C (minutes) have no confidence interval; each is one run.

---

## 5. The truncation finding (Phase 15 findings 3 and 5; decisions P16-27, P16-32, P16-36, P16-38, P16-40, P16-41)

Background (LIMITATIONS L15): in Phase 15 SMOKE2 every Bedrock synthesis reply was about 5,000 characters and was rejected; the stop reason was not logged and truncation at `maxOutputTokens` 2,000 was UNVERIFIED. Phase 16 logs `stopReason` per call and ran four diagnostics of `AnswerEval --diagnostic` (Haiku 4.5, `us.anthropic.claude-haiku-4-5-20251001-v1:0`, fused mode, same questions and labels sha256 in all four: `0faab24b88…` and `f7cf669519…`, `run-manifest.json` `inputs`). Source files: `RUNS/h4-diagnostic-{1,2,3,4}/diagnostic-summary.json` and `run-manifest.json`; run commands and console lines in `human-commands.md`. Direct route (decision P16-28), no `run-experiment.sh`; USD figures are estimates at UNVERIFIED rates (1.00 and 5.00 USD per million input and output tokens, 0.02 for Titan; manifest `cost.rates.status`).

### 5.1 The four runs

| Run | Start (UTC) | Synthesis `maxOutputTokens` | Prompt | Haiku calls | Status | `stopReasons` (attempts) | Spend (USD, manifest) |
|---|---|---|---|---|---|---|---|
| h4-diagnostic-1 | 2026-11-18T21:38:16Z | 2,000 | not recorded in the manifest (no `ragIdentity`) | 4 | HALTED `haiku-call-cap` (designed cap) | end_turn 1, max_tokens 3, none 1 (the 5th attempt refused by the cap) | 0.066101 |
| h4-diagnostic-2 | 2026-11-18T23:11:05Z | 4,096 | not recorded in the manifest | 4 | HALTED `haiku-call-cap` | end_turn 1, max_tokens 3, none 1 | 0.100386 |
| h4-diagnostic-3 | 2026-11-19T00:37:27Z | 4,096 | synthesis 1.1.0 (per `experiments/h-answers/README.md`; no `ragIdentity` in its manifest) | 2 | COMPLETE | end_turn 1, max_tokens 1 | 0.055691 |
| h4-diagnostic-4 | 2026-11-19T05:31:58Z | 4,096 (`ragIdentity`) | synthesis 1.2.0, sha256 `ada69809da…`; `plannerMaxLimit` 12 | 2 | COMPLETE | end_turn 2 | 0.033809 |

Spend sum across the four runs: 0.255987 USD (arithmetic). These are file estimates; the billed Phase 16 Bedrock usage (H, these four diagnostics, G) is 1.46 USD month-to-date difference at H-1c (decision P16-43; section 6.2.3). Metered Haiku tokens (manifest `cost`): run 1 input 26,150, output 7,990; run 2 input 26,150, output 14,847; run 3 input 15,014, output 8,135; run 4 input 15,562, output 3,649. Titan calls 4, 4, 4, 4. Each is at or under the 0.15 USD clamp.

### 5.2 Truncation at the 2,000-token limit (h4-diagnostic-1)

`diagnostic-summary.json`: 5 attempts, 1 accepted, 4 rejected; `maxTokensAttempts` 3; rejection codes: "output is not valid JSON" 3, "model call failed: cost-stop-haiku-call-cap" 1; `outputTokensMax` 2,000. Calls log (`completionTokens`, `stopReason`, `millis`): 1,990 `end_turn` 8,178 ms; 2,000 `max_tokens` 8,416 ms; 2,000 `max_tokens` 8,401 ms; 2,000 `max_tokens` 8,702 ms. Three of four Haiku calls stopped on `max_tokens`; the one accepted answer used 1,990 of 2,000 tokens. Per `human-commands.md` and decision P16-32, the ranking question abstained `synthesis-failed`.

### 5.3 Limit raised to 4,096, ranking still truncated (h4-diagnostic-2)

Calls log: 2,559 tokens `end_turn` 10,523 ms; then 4,096 `max_tokens` 13,904 ms; 4,096 `max_tokens` 14,296 ms; 4,096 `max_tokens` 14,236 ms. Rejections: "output is not valid JSON" 3, call-cap refusal 1. Per `human-commands.md`, the non-ranking question (Q-RL-01) was accepted at 2,559 tokens (1,990 at the old limit) and Q-RK-01 (ranking) hit `max_tokens` at exactly 4,096 on all three attempts. Latencies 10.5 s at 2,559 tokens and 13.9 to 14.3 s at 4,096, under the 30 s attempt timeout (`human-commands.md`).

Note: runs 1 and 2 have no `attemptMeasures` and no `retryDecisions` (the measures `replyChars`, `claimsStarted`, `repeatRatio`, ... were added after run 2, decision P16-36). The question ids of each call are not in `diagnostic-summary.json`; they are taken from `human-commands.md`.

### 5.4 Measures and the pre-registered rules (h4-diagnostic-3)

Measures from `diagnostic-summary.json` `attemptMeasures` (text-free; prompt 1.1.0; `retryDecisions`: `max_tokens-no-retry` 1):

| Measure | Q-RK-01 | Q-RK-02 (baseline) |
|---|---|---|
| accepted / stopReason | false / `max_tokens` | true / `end_turn` |
| outputTokens | 4,096 | 4,039 |
| replyChars | 10,793 | 11,344 |
| claimsStarted | 18 | 17 |
| repeatRatio | 0.7005 | 0.6011 |
| evidenceItems | 8 | 10 |
| rankingEntries | 5 | 5 |
| rankedTotal | 27 | 858 |
| cutoffTies | 5 | 1 |
| tieAtCutoff | false | false |
| call time (ms) | 14,925 | 17,129 |

Rules fixed before the run (decision P16-38), Q-RK-02 as baseline: (a) `rankingEntries` <= 5 and `claimsStarted` >= 2 x `rankingEntries`: the model lists beyond the evidence; (b) `claimsStarted` <= `rankingEntries` + 1 and `repeatRatio` >= 2 x the baseline's: a loop inside the text; (c) `claimsStarted` = 0; (d) both questions truncate: inconclusive.

Reading against the rules (orchestrator's reading, decision P16-40; the human accepted the claim-cap design that rests on it, P16-41): Q-RK-01 matches (a): `rankingEntries` 5 <= 5 and `claimsStarted` 18 >= 10. (b) does not match: 18 > 6, and 0.7005 / 0.6011 = 1.17, below 2. (c) does not match: 18 claims. (d) does not match: Q-RK-02 completed. The baseline Q-RK-02 completed 57 tokens under the limit (4,096 - 4,039) with 17 claims for 5 entries. The hypothesis of P16-36 (the reply grows with a tie list) was not supported: only 5 entries were passed, `tieAtCutoff` false for both.

### 5.5 The fix and its confirmation

Fix (decision P16-41, commit `e6cf0c0`; `experiments/h-answers/README.md`): synthesis prompt 1.2.0 rule 6 limits a ranking reply to entries + 2 claims; a completed ranking reply over that cap is rejected and retried with a hint (up to 3 attempts) and then abstains `synthesis-failed`; a `max_tokens` reply is not retried (synthesis only, P16-37); `PlannerConfig.maxLimit` lowered from 20 to 12 (cap 14 claims, about 3,500 tokens), with larger top-N requests clamped.

Confirmation `h4-diagnostic-4` (`diagnostic-summary.json`; `run-manifest.json` `ragIdentity`): both attempts accepted, both `end_turn`, `maxTokensAttempts` 0, `retryDecisions` empty, `outputTokensMax` 1,828.

| Measure | Q-RK-01 | Q-RK-02 |
|---|---|---|
| outputTokens | 1,828 | 1,821 |
| replyChars | 5,274 | 5,323 |
| claimsStarted | 7 (= entries + 2) | 7 (= entries + 2) |
| repeatRatio | 0.4234 | 0.4971 |
| evidenceItems / rankingEntries | 8 / 5 | 10 / 5 |
| call time (ms) | 11,625 | 8,980 |

Console (human): `ANSWER_RESULT status=COMPLETE ... halt=none`, `ANSWER_COST spentUsd=0.033809 haikuCalls=2 titanCalls=4`, `DIAGNOSTIC calls=2 stopReasons=end_turn:2 maxTokens=0`. Metered input tokens 7,333 and 8,229 per call (`calls_log`). The pre-run prediction of about 1,816 tokens is in `human-commands.md`; measured 1,828 and 1,821. Both outputs are 44.6 % and 44.5 % of the 4,096 limit (arithmetic: 1,828 / 4,096 and 1,821 / 4,096).

### 5.6 Limits

- Two ranking questions and 12 Haiku calls in total across the four runs; one prompt version per run. The diagnostic result shows the cap held on these two questions. How rule 6 and the claim cap behaved across all of H is in section 6.2.
- `h4-diagnostic-1` and `-2` have no text-free measures (`replyChars` and the others): they ran before the measure code. `-1` and `-2` record no prompt identity; `-3` ran prompt 1.1.0 and has no `ragIdentity` in its manifest, so the prompt version of `-1` to `-3` rests on the decision log and the H README, not on a recorded hash (`h-answers/README.md` "Run identity": compare by these fields, not by the decision log, from `-4` on).
- The diagnostics ran on the laptop, with `InMemoryNeptune` and S3 Vectors; latency is laptop-to-Bedrock (H README "Limits").
- The 4,096 limit raises per-call latency to 13.9 to 17.1 s at the cap; decision P16-34 records a required Phase 17 item (slow-route deadline, `ServiceConfig.scala:132` default 10 s) before any deploy with Haiku. No deploy happened in Phase 16.

---

## 6. Retrieval, answers, audit, rating and entity resolution

All figures in 6.1 to 6.5 come from frozen run directories under `RUNS` (read-only; `exp-G-*` and `exp-H-*` carry `run-record.env` with `RECORD_COMPLETE=yes`). Exception: the figures in 6.4 come from the human's rating CSVs and `phase16-rating-SHA256SUMS`, which are in `SCD`, not under `RUNS`. They are frozen by the human's chmod and sha256 record (the freeze record in 6.4, lines "Sources" and "Freeze record"), not by a run record; only `rating-sample-key.json` is under `RUNS`.

### 6.1 Retrieval quality (G) `exp-G-20261119T053608Z-2e8943cf`

Run record (`exp-G-.../run-record.env`): `FINAL_STATUS` COMPLETED, `EXIT_STATUS` 0, `ELAPSED_SECONDS` 194, `START_UTC` 2026-11-19T05:36:08Z, `END_UTC` 2026-11-19T05:39:25Z, `GIT_SHA` `2e8943cf…`, `MAX_MINUTES` 60, `MAX_TASKS` 400, `SPEND_CEILING_USD` 0.25, `ESTIMATED_SPEND_USD` 0.01 (the harness's hourly-burn estimate), `SPEND_ESTIMATE_EXCEEDED` no. Entry point (`raw/retrieval/run-manifest.json`): `status` COMPLETE, `haltReason` null; `counts` planned 336, records 336, usable 336, costStopped 0, errors 0, `vectorQueries` 446 (arithmetic: 336 = 56 questions x 3 modes x 2 repeats, `config.repeats` 2, `config.modes` graph, vector, fused, `config.ks` 2, 5, 10).

Run identity (`run-manifest.json` `ragIdentity`, `wiring`, `inputs`): prompts `planner-classify` 1.0.0 (`408c5c0…`) and `synthesis` 1.2.0 (`ada69809da…`); `synthesisMaxOutputTokens` 4,096; `plannerMaxLimit` 12; `wiring.mode` live, region us-east-2, graph "InMemoryNeptune over the local export" (`graphRunId` `run:v1:3cfb57b1-abd1-4913-8e53-da477d8d6027`), `vectors` true, embedder `amazon.titan-embed-text-v2:0-d256-norm`, `answerModel` stub ("stub synthesis (retrieval only)"); `inputs.questionsSha256` `0faab24b88…`, `labelsSha256` `f7cf669519…`, `questions` 56. The prompt and schema hashes equal those of `h4-diagnostic-4` and of run H (section 6.2).

Cost (`run-manifest.json` `cost`): `spentUsd` 1.17E-4 (0.000117 USD, an estimate at UNVERIFIED rates; `rates.status` "UNVERIFIED: values given on the command line"), `titanCalls` 296, `titanEstimatedTokens` 5,846, `usdCeiling` 0.25, `titanMaxCalls` 2,000. `haikuCalls` is 210 with `haikuMeteredInputTokens` 0, `haikuMeteredOutputTokens` 0 and the Haiku rates set to 0; what the 210 counts is NOT STATED in the file (the answer model is the stub). S3 Vectors queries and S3 reads are not metered (`cost.basis`).

Interval method (`run-manifest.json` `config`): `resamples` 1,000, `seed` 20261118. The confidence level is not a field in `summary.json` or the manifest (plan D8 specifies a seeded bootstrap over questions; level NOT STATED in the files read). In `summary.json` each metric holds `mean`, `low`, `high` and `n`. `n` is the number of questions contributing: in the `all` scope the pooled `n` is 46 of 56 questions; the `unanswerable` stratum has `n` 0 and `mean` null for every metric; `free-form` has `n` 6 of 8 (arithmetic: 56 - 46 = 10 questions with an expected abstention, the 8 unanswerable plus Q-FF-07 and Q-FF-08 per decision P16-23; the audit's `abstention.unanswerable` is 10).

#### 6.1.1 Pooled, all questions (`scopes.all.pooled`, n 46): mean [low, high]

| Mode | K | Recall@K | Precision@K | nDCG@K |
|---|---|---|---|---|
| graph | 2 | 0.3177 [0.2259, 0.4185] | 0.4891 [0.3587, 0.6087] | 0.5974 [0.4641, 0.7278] |
| graph | 5 | 0.4639 [0.3363, 0.5970] | 0.3435 [0.2391, 0.4435] | 0.5642 [0.4273, 0.7057] |
| graph | 10 | 0.4935 [0.3676, 0.6254] | 0.2022 [0.1304, 0.2804] | 0.5512 [0.4106, 0.6750] |
| vector | 2 | 0.1317 [0.0460, 0.2241] | 0.1304 [0.0652, 0.1957] | 0.2344 [0.1153, 0.3535] |
| vector | 5 | 0.1433 [0.0594, 0.2423] | 0.0609 [0.0348, 0.0870] | 0.2107 [0.1059, 0.3167] |
| vector | 10 | 0.1498 [0.0657, 0.2468] | 0.0348 [0.0196, 0.0500] | 0.1996 [0.1114, 0.2997] |
| fused | 2 | 0.3177 [0.2204, 0.4263] | 0.4891 [0.3696, 0.6196] | 0.5974 [0.4754, 0.7240] |
| fused | 5 | 0.4711 [0.3374, 0.6086] | 0.3478 [0.2348, 0.4652] | 0.5681 [0.4415, 0.6905] |
| fused | 10 | 0.5008 [0.3570, 0.6318] | 0.2043 [0.1348, 0.2826] | 0.5552 [0.4203, 0.6848] |

MRR@10 (the file's only MRR cut-off, `mrr@10`), pooled all, n 46: graph 0.6196 [0.4783, 0.7500]; vector 0.2731 [0.1431, 0.3992]; fused 0.6239 [0.4913, 0.7543].

#### 6.1.2 Pooled, vector-available questions (`scopes.vectorAvailable.pooled`, n 30): mean [low, high]

| Mode | K | Recall@K | Precision@K | nDCG@K |
|---|---|---|---|---|
| graph | 2 | 0.2891 [0.1504, 0.4298] | 0.3500 [0.2333, 0.5000] | 0.4884 [0.3275, 0.6551] |
| graph | 5 | 0.3399 [0.1827, 0.4983] | 0.1867 [0.1000, 0.2867] | 0.4449 [0.2806, 0.6070] |
| graph | 10 | 0.3520 [0.2030, 0.5073] | 0.1067 [0.0500, 0.1733] | 0.4275 [0.2780, 0.5841] |
| vector | 2 | 0.1971 [0.0804, 0.3301] | 0.1833 [0.1000, 0.2667] | 0.3319 [0.1768, 0.4972] |
| vector | 5 | 0.1971 [0.0804, 0.3254] | 0.0733 [0.0400, 0.1133] | 0.2919 [0.1495, 0.4323] |
| vector | 10 | 0.1971 [0.0815, 0.3268] | 0.0367 [0.0200, 0.0533] | 0.2727 [0.1299, 0.4164] |
| fused | 2 | 0.2891 [0.1525, 0.4344] | 0.3500 [0.2333, 0.4833] | 0.4884 [0.3275, 0.6609] |
| fused | 5 | 0.3399 [0.1744, 0.5062] | 0.1867 [0.1067, 0.2867] | 0.4449 [0.2786, 0.6003] |
| fused | 10 | 0.3520 [0.2027, 0.5171] | 0.1067 [0.0533, 0.1733] | 0.4275 [0.2701, 0.5696] |

MRR@10, vector-available, n 30: graph 0.5167 [0.3333, 0.6833]; vector 0.3667 [0.2000, 0.5333]; fused 0.5167 [0.3500, 0.7000].

#### 6.1.3 Per stratum, all questions (`scopes.all.<stratum>.metrics`, means; `n` in the first column)

Columns: R = Recall@K, P = Precision@K, N = nDCG@K, for K = 2, 5, 10; MRR = `mrr@10`. The per-stratum `low` and `high` are in `summary.json` at the same path and are not reproduced here; several strata have `low` equal to `high` (for example where every question scores the same).

Graph:

| Stratum (n) | R@2 | R@5 | R@10 | P@2 | P@5 | P@10 | N@2 | N@5 | N@10 | MRR |
|---|---|---|---|---|---|---|---|---|---|---|
| researcher-lookup (7) | 0.1523 | 0.1523 | 0.1523 | 0.5000 | 0.2000 | 0.1000 | 0.7827 | 0.5914 | 0.5061 | 0.9286 |
| coauthor-lookup (8) | 0.3208 | 0.5000 | 0.5000 | 0.5000 | 0.3500 | 0.1750 | 0.5000 | 0.5000 | 0.5000 | 0.5000 |
| ranking (10) | 0.4133 | 0.9000 | 1.0000 | 1.0000 | 0.9200 | 0.5600 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| shortest-path (4) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| community-component (6) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temporal-comparison (5) | 1.0000 | 1.0000 | 1.0000 | 0.5000 | 0.2000 | 0.1000 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| unanswerable (0) | null | null | null | null | null | null | null | null | null | null |
| free-form (6) | 0.3081 | 0.3788 | 0.4394 | 0.4167 | 0.2333 | 0.1833 | 0.5000 | 0.4687 | 0.4689 | 0.5000 |

Vector:

| Stratum (n) | R@2 | R@5 | R@10 | P@2 | P@5 | P@10 | N@2 | N@5 | N@10 | MRR |
|---|---|---|---|---|---|---|---|---|---|---|
| researcher-lookup (7) | 0.1380 | 0.1380 | 0.1380 | 0.4286 | 0.1714 | 0.0857 | 0.7082 | 0.5368 | 0.4648 | 0.8571 |
| coauthor-lookup (8) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| ranking (10) | 0 | 0.0200 | 0.0500 | 0 | 0.0200 | 0.0300 | 0 | 0.0146 | 0.0289 | 0.0361 |
| shortest-path (4) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| community-component (6) | 0 | 0.0556 | 0.0556 | 0 | 0.0333 | 0.0167 | 0 | 0.0303 | 0.0303 | 0.0333 |
| temporal-comparison (5) | 0.8000 | 0.8000 | 0.8000 | 0.4000 | 0.1600 | 0.0800 | 0.8000 | 0.8000 | 0.8000 | 0.8000 |
| unanswerable (0) | null | null | null | null | null | null | null | null | null | null |
| free-form (6) | 0.1818 | 0.1818 | 0.1818 | 0.1667 | 0.0667 | 0.0333 | 0.3044 | 0.2677 | 0.2431 | 0.3333 |

Fused:

| Stratum (n) | R@2 | R@5 | R@10 | P@2 | P@5 | P@10 | N@2 | N@5 | N@10 | MRR |
|---|---|---|---|---|---|---|---|---|---|---|
| researcher-lookup (7) | 0.1523 | 0.1523 | 0.1523 | 0.5000 | 0.2000 | 0.1000 | 0.7827 | 0.5914 | 0.5061 | 0.9286 |
| coauthor-lookup (8) | 0.3208 | 0.5000 | 0.5000 | 0.5000 | 0.3500 | 0.1750 | 0.5000 | 0.5000 | 0.5000 | 0.5000 |
| ranking (10) | 0.4133 | 0.9000 | 1.0000 | 1.0000 | 0.9200 | 0.5600 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| shortest-path (4) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| community-component (6) | 0 | 0.0556 | 0.0556 | 0 | 0.0333 | 0.0167 | 0 | 0.0303 | 0.0303 | 0.0333 |
| temporal-comparison (5) | 1.0000 | 1.0000 | 1.0000 | 0.5000 | 0.2000 | 0.1000 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| unanswerable (0) | null | null | null | null | null | null | null | null | null | null |
| free-form (6) | 0.3081 | 0.3788 | 0.4394 | 0.4167 | 0.2333 | 0.1833 | 0.5000 | 0.4687 | 0.4689 | 0.5000 |

#### 6.1.4 Per stratum, vector-available questions (`scopes.vectorAvailable.<stratum>.metrics`, means)

The `vectorAvailable` scope holds no `ranking` and no `unanswerable` entry.

Graph:

| Stratum (n) | R@2 | R@5 | R@10 | P@2 | P@5 | P@10 | N@2 | N@5 | N@10 | MRR |
|---|---|---|---|---|---|---|---|---|---|---|
| researcher-lookup (6) | 0.1539 | 0.1539 | 0.1539 | 0.5000 | 0.2000 | 0.1000 | 0.7754 | 0.5890 | 0.5018 | 0.9167 |
| coauthor-lookup (8) | 0.3208 | 0.5000 | 0.5000 | 0.5000 | 0.3500 | 0.1750 | 0.5000 | 0.5000 | 0.5000 | 0.5000 |
| shortest-path (4) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| community-component (5) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temporal-comparison (4) | 1.0000 | 1.0000 | 1.0000 | 0.5000 | 0.2000 | 0.1000 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| free-form (3) | 0.3939 | 0.4242 | 0.5455 | 0.5000 | 0.2667 | 0.2667 | 0.6667 | 0.6040 | 0.6045 | 0.6667 |

Vector:

| Stratum (n) | R@2 | R@5 | R@10 | P@2 | P@5 | P@10 | N@2 | N@5 | N@10 | MRR |
|---|---|---|---|---|---|---|---|---|---|---|
| researcher-lookup (6) | 0.1372 | 0.1372 | 0.1372 | 0.4167 | 0.1667 | 0.0833 | 0.6885 | 0.5252 | 0.4536 | 0.8333 |
| coauthor-lookup (8) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| shortest-path (4) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| community-component (5) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temporal-comparison (4) | 1.0000 | 1.0000 | 1.0000 | 0.5000 | 0.2000 | 0.1000 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| free-form (3) | 0.3636 | 0.3636 | 0.3636 | 0.3333 | 0.1333 | 0.0667 | 0.6087 | 0.5354 | 0.4862 | 0.6667 |

Fused:

| Stratum (n) | R@2 | R@5 | R@10 | P@2 | P@5 | P@10 | N@2 | N@5 | N@10 | MRR |
|---|---|---|---|---|---|---|---|---|---|---|
| researcher-lookup (6) | 0.1539 | 0.1539 | 0.1539 | 0.5000 | 0.2000 | 0.1000 | 0.7754 | 0.5890 | 0.5018 | 0.9167 |
| coauthor-lookup (8) | 0.3208 | 0.5000 | 0.5000 | 0.5000 | 0.3500 | 0.1750 | 0.5000 | 0.5000 | 0.5000 | 0.5000 |
| shortest-path (4) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| community-component (5) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temporal-comparison (4) | 1.0000 | 1.0000 | 1.0000 | 0.5000 | 0.2000 | 0.1000 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| free-form (3) | 0.3939 | 0.4242 | 0.5455 | 0.5000 | 0.2667 | 0.2667 | 0.6667 | 0.6040 | 0.6045 | 0.6667 |

#### 6.1.5 Abstention-aware outcomes (`<mode>.outcomes`)

| Mode | unanswerableRecords | abstentionRecall | reasonCodeMatchAmongAbstained | acceptedOutcomeRecords | acceptedOutcomeCorrect | answerableRecords | falseAbstentionRate |
|---|---|---|---|---|---|---|---|
| graph | 20 | 0.9 | 1 | 0 | null | 92 | 0.2826 |
| vector | 20 | 0.9 | null | 0 | null | 92 | 0.2826 |
| fused | 20 | 0.9 | 1 | 0 | null | 92 | 0.2826 |

(`falseAbstentionRate` is the same value in all three modes, 0.282609 to 6 decimals; the file holds full double precision. Records are question x repeat, so 20 = 10 questions x 2 repeats and 92 = 46 x 2; arithmetic.)

#### 6.1.6 Repeats (ANN variability; `perRepeat`, `repeatStability`)

For each of the three modes, the two entries of `perRepeat` (repeat 1 and repeat 2, 56 records each) hold the same value for every metric (for example, to 6 decimals, graph recall@5 0.463891 in both, fused recall@10 0.500782 in both, vector recall@10 0.149794 in both; the file holds full double precision). `repeatStability` (each mode): `questionsWithRepeats` 56; `identicalDeployedVectorHits`, `identicalDirectVectorHits`, `identicalGraphIds` and `identicalAbstention` each 1.

#### 6.1.7 Limits (G)

Recorded in `experiments/g-retrieval/README.md` "Limits" and the benchmark README: synthesis is the stub (retrieval only); ground truth and retrieval share one export (retrieval metrics measure plan, resolve and retrieve fidelity to the graph); researcher-lookup recall in graph mode is capped by what the runtime returns (the subject item, grade 2, is the comparable item); community and component members are never retrieved in graph mode (the README's statement; the measured community-component row above is 0 in graph mode and 0 to 0.0556 in the other two); template wording overstates planner accuracy on the 48 generated questions; the graph port is `InMemoryNeptune`, not Neptune; Titan USD is an estimate. The `records.jsonl` per-record file (481,725 bytes) was not read for this section.

### 6.2 Answer quality (H) `exp-H-20261119T053926Z-2e8943cf`

Run record (`exp-H-.../run-record.env`): `FINAL_STATUS` COMPLETED, `EXIT_STATUS` 0, `ELAPSED_SECONDS` 569, `START_UTC` 2026-11-19T05:39:26Z, `END_UTC` 2026-11-19T05:48:59Z, `GIT_SHA` `2e8943cf…`, `MAX_MINUTES` 90, `MAX_TASKS` 460, `SPEND_CEILING_USD` 3.00, `ESTIMATED_SPEND_USD` 0.13 (the harness's hourly-burn estimate), `SPEND_ESTIMATE_EXCEEDED` no. Entry point (`raw/answers/run-manifest.json`): `mode` full, `status` COMPLETE, `haltReason` null; `counts` planned 76, records 76, usable 76, `costStopped` 0, errors 0, `vectorQueries` 85; `config` `retrievalMode` fused, `repeatCount` 20, `ratingSampleSize` 20, `seed` 20261118; `inputs` `questionsRun` 56, `repeatQuestions` 20, `questionsSha256` `0faab24b88…`, `labelsSha256` `f7cf669519…`. Run identity (`ragIdentity`, `wiring`): the same prompt versions and hashes as G (planner-classify 1.0.0, synthesis 1.2.0 `ada69809da…`), `synthesisMaxOutputTokens` 4,096, `plannerMaxLimit` 12, answer model `us.anthropic.claude-haiku-4-5-20251001-v1:0`, embedder `amazon.titan-embed-text-v2:0-d256-norm`, graph `InMemoryNeptune over the local export`, region us-east-2.

#### 6.2.1 Counts, tokens, latency (`raw/answers/answer-summary.json`)

| Field | Value |
|---|---|
| primaryAnswers | 56 |
| answered / abstained | 34 / 22 |
| repeatAnswers | 20 |
| modelCalls | 57 |
| promptTokens / completionTokens | 370,577 / 104,254 |
| tokensEstimated | true (a defect of the summary flag, not a measurement; see 6.2.3) |
| answerLatencyMillisP50 / P95 | 8,038 / 17,671 |

The 57 calls: `model-calls.jsonl` has 57 lines; counted by reading, 56 have `promptId` synthesis and one (line 32) `planner-classify` (492 prompt tokens, 33 completion tokens, 516 ms). Every line has `stopReason` end_turn and `outcome` ok. Per-call `millis` range, read from the file: 516 (the planner call) to 17,087; the largest `completionTokens` is 3,626 (line 18, 14,753 ms). The p50 and p95 above are `answer-summary.json` values (the field name is answer latency); no per-call percentile is stored.

#### 6.2.2 Stop reasons and retry decisions (`raw/answers/model-attempts-summary.json`)

| Field | Value |
|---|---|
| attempts / accepted / rejected | 57 / 55 / 2 |
| stopReasons | end_turn 57 |
| stopReasonsOfRejected | end_turn 2 |
| rejectionCodes | "too many claims for a ranking" 2 |
| maxTokensAttempts | 0 |
| outputTokensMax | 3,626 |

The file has no `retryDecisions` field (the diagnostic summaries do); retry decisions beyond the rejection codes are NOT DERIVED from the files read. Rule 6 and the claim cap, across all of H: there was no `max_tokens` stop; the claim cap rejected 2 completed replies (stop reason end_turn each); no primary answer abstained with `synthesis-failed` (section 6.2.4 lists every reason code). Arithmetic (assumes one accepted synthesis call per answered record; not a field): 56 synthesis calls - 2 rejected = 54 accepted = 34 answered primary + 20 repeats, so each rejected reply was followed by an accepted one. Which two questions were rejected is not in the file (`model-calls.jsonl` carries no question id). Claims per answered ranking question, from `h-audit-2/audit-answers.jsonl` field `claims` (counted by reading; N from the benchmark question): Q-RK-01 5 (N 5), Q-RK-02 5 (5), Q-RK-03 3 (3), Q-RK-04 10 (10), Q-RK-05 5 (5), Q-RK-06 5 (5), Q-RK-07 5 (5), Q-RK-08 3 (3), Q-RK-09 10 (10), Q-RK-10 5 (5), Q-FF-06 6 (N 3), Q-FF-08 5 (a top-five question). The cap is the number of entries of the ranking fact + 2 (`Synthesis.scala` lines 38 and 112 to 114; `ReplyMeasures.scala` lines 47 to 51, as cited by review 14 finding 4), not the question's N + 2. Q-FF-06's ranking fact (`answers.jsonl` line 54, metric `bridge-score`, window `all`, per the review; that file was not read for this section) holds 5 entries, so its cap is 7. The audit's `claims` field is the post-verifier count (`AnswerRecords.scala` line 322, per the review). Arithmetic: 6 <= 7, so Q-FF-06 is not a cap violation. Advisory carried from the review (for the rag owner): 5 entries were retrieved for a top-3 question.

#### 6.2.3 Spend

`raw/answers/run-manifest.json` `cost`: `spentUsd` 0.891881 against `usdCeiling` 3 (`haikuMaxCalls` 460, `titanMaxCalls` 1,000); `haikuCalls` 57, `haikuFailedCalls` 0, `refusedCalls` 0, `titanCalls` 85, `titanEstimatedTokens` 1,700, `haikuMeteredInputTokens` 370,577, `haikuMeteredOutputTokens` 104,254, `stopReason` null; `rates` 1 and 5 USD per million Haiku input and output tokens and 0.02 for Titan, status "UNVERIFIED: values given on the command line". H's 0.891881 USD is metered from Converse usage tokens (decision P16-44; review 14 MATCH): all 57 calls report measured tokens: every one of the 57 `model-calls.jsonl` lines has `promptTokens` > 0 and `completionTokens` > 0 (review 15, reviewer Grep, 57 of 57; the evidence that meets P16-44's condition). H ran before WO-3 part 6. In that build a Converse reply without usage was recorded as 0 tokens with `tokensEstimated` false, so the per-call flag (false on all 57) and `cost.haikuCallsChargedAtWorstCase` (0) could not by themselves detect such a reply, and this file does not rely on them for H (review 15 finding 2). This is fixed in WO-3 part 6 (decision P16-45): a reply without full usage is now marked estimated and CostGuard charges it the worst case. The recorded H run was not rerun and keeps the old behavior. The `tokensEstimated: true` in `answer-summary.json` was a defect, not a measurement (review 14 finding 3: `ModelUsage.zero` started with `estimated = true` and `+` ORs the flags, so the total was always estimated); it was fixed in WO-3 part 5 (`ModelUsage.zero` now `estimated = false`, rag-engineer, `sbt quick` passed), and the recorded run keeps the old flag because H was not rerun. This replaces the "upper-leaning" wording of decision P16-42 item 3. Billed figure (H-1c, decision P16-43, human reading 2026-11-19 07:41): 10.39 USD month-to-date, +1.46 since H-1b (8.93 USD at 2026-11-18 18:29, P16-39). Idle drift between H-1 and H-1b was +0.02 over about 10 h, so the 1.46 is the Phase 16 Bedrock usage (H, h4 diagnostics 1-4, G). The dashboard forecast (300.96) is an extrapolation of the overnight spike, not spend; nothing was running. Headroom to the 14 USD stop is about 3.61 USD (P16-43). Billing is a month-to-date difference and is not itemised by run, so H's own billed share is NOT DERIVED; the sums of the file estimates are 0.891881 (H), 0.255987 (diagnostics, 5.1) and 0.000117 (G), arithmetic 1.147985, against 1.46 billed; the difference (arithmetic 0.312015) is not explained in the files read, and the estimates use unverified rates. Arithmetic: 370,577 x 1 / 1,000,000 = 0.370577; 104,254 x 5 / 1,000,000 = 0.521270; Titan 1,700 x 0.02 / 1,000,000 = 0.000034; sum 0.891881, equal to `spentUsd`. The harness's own hourly-burn estimate for the run is 0.13 USD (`run-record.env`).

#### 6.2.4 Abstention by stratum and reason code

Source: `h-audit-2/audit-answers.jsonl` (fields `abstained`, `abstentionReason`, one line per primary question; counted by reading), strata from `experiments/benchmark/questions-v1.jsonl` (`stratum`), expected codes from `experiments/benchmark/labels-v1.jsonl` (`expected.expectedCodes`) for the eight unanswerable questions.

| Stratum | Questions | Abstained | Reason codes of the abstentions |
|---|---|---|---|
| researcher-lookup | 7 | 1 | identity-unresolved 1 |
| coauthor-lookup | 8 | 4 | identity-unresolved 4 |
| ranking | 10 | 0 | none |
| shortest-path | 4 | 4 | identity-unresolved 4 |
| community-component | 6 | 2 | identity-unresolved 2 |
| temporal-comparison | 5 | 0 | none |
| unanswerable | 8 | 8 | identity-unresolved 3, no-deterministic-evidence 2, planner-refused 1, researcher-count 1, needs-two-windows 1 |
| free-form | 8 | 3 | evidence-cap 1, researcher-count 1, identity-unresolved 1 |
| total | 56 | 22 | identity-unresolved 15, no-deterministic-evidence 2, researcher-count 2, planner-refused 1, needs-two-windows 1, evidence-cap 1 |

(Arithmetic: 1+4+0+4+2+0+8+3 = 22; 15+2+2+1+1+1 = 22; both equal `abstained` 22.)

Against `expectedCodes` for the eight unanswerable questions: Q-UA-01 identity-unresolved (observed identity-unresolved), Q-UA-02 no-deterministic-evidence (same), Q-UA-03 identity-unresolved (same), Q-UA-04 planner-refused (same), Q-UA-05 researcher-count (same), Q-UA-06 needs-two-windows (same), Q-UA-07 identity-unresolved (same), Q-UA-08 no-deterministic-evidence (same): 8 of 8 equal. Free-form: Q-FF-07 (expected code `identity-unresolved`, decision P16-23) abstained identity-unresolved; Q-FF-08 (expected `researcher-count`) did not abstain. The other abstentions (13) are on questions labelled answerable: Q-RL-05; Q-CA-02, -04, -06, -08; Q-SP-01 to -04; Q-CC-03, -06; Q-FF-03 (evidence-cap) and Q-FF-05 (researcher-count). The audit's totals for these are in 6.3.

#### 6.2.5 Repeat variability (20 repeated questions; `h-audit-2/audit-summary.json` `repeatVariability`)

`questionsRepeated` 20; `sameAbstention` 1; `sameEvidenceIds` 1; `sameClaimCount` 0.8; `sameAnswerHash` 0.65.

#### 6.2.6 Limits (H)

Recorded in `experiments/h-answers/README.md` "Limits": latency is laptop-to-Bedrock, not in-VPC; the audit and the labels share the export; single human rater; the `out-of-scope` code is the stub's mapping; a cost stop ends the run early (none occurred: `costStopped` 0). Haiku was called at temperature as configured by the entry point (the value is not read here). The `answers.jsonl` file (846,445 bytes) holds question and answer text and was read for no number in this file.

### 6.3 Independent audit (`h-audit-2`)

Run `h-audit-2` (`RUNS/h-audit-2/`; human-run 2026-11-19 07:43 local, after the metric-name fix of W2-RB11 and W2-TR5; console `AUDIT_RESULT status=COMPLETE audited=56`, `human-commands.md`). `run-manifest.json`: `schema` `scholarchain.evaluation.answer-audit-run/1`, `runId` `h-audit-2`, `finishedAt` 2026-11-19T13:43:01Z, `answersSha256` `efde5592e1…` (the answers file of run H), `labelsSha256` `f7cf669519…`, `records` 76, `audited` 56. Numbers tolerance (`audit-summary.json` `tolerance`): `abs` 0.01, `rel` 0.001. All rows below are `RUNS/h-audit-2/audit-summary.json`; rates are shown to 6 decimals, the file holds full double precision.

h-audit-1 (superseded) ran with the metric-name defect at AnswerAudit.scala (review 14 finding 1): ranking validity 1 of 12, exportMatchedRate 0.880914, claims citing invalid evidence 59.

| Metric | Field | Value |
|---|---|---|
| Answers audited / answered / abstained | `answers` | 56 / 34 / 22 |
| Claims; factual claims | `structuralSupport.claims`, `factualClaims` | 199; 199 |
| Reclassified as factual by the audit | `structuralSupport.reclassifiedFactualByAudit` | 0 |
| Excluded by verifier kind | `structuralSupport.excludedByVerifierKind` | 0 |
| Claims citing nothing; only semantic support | `citingNothing`, `onlySemanticSupport` | 0; 0 |
| Structural support rate | `structuralSupport.supportedRate` | 1 |
| Coauthor-edge validity | `evidenceValidity.coauthorEdges` | 28 checked, 28 valid, rate 1 |
| Path-hop validity | `evidenceValidity.pathHops` | 0 checked, 0 valid, rate null |
| Path-chain validity | `evidenceValidity.pathChains` | 0 checked, 0 valid, rate null |
| Supporting-publication validity (supporting works) | `evidenceValidity.supportingWorks` | 0 checked, 0 valid, rate null |
| Publication validity | `evidenceValidity.publications` | 6 checked, 6 valid, rate 1 |
| Metric validity | `evidenceValidity.metrics` | 93 checked, 93 valid, rate 1 |
| Ranking validity | `evidenceValidity.rankings` | 12 checked, 12 valid, rate 1 |
| Group (community, component) validity | `evidenceValidity.groups` | 9 checked, 9 valid, rate 1 |
| Identity validity | `evidenceValidity.identities` | 22 checked, 22 valid, rate 1 |
| Identifier accuracy | `identifiers` | `idsChecked` 0; `claimsWithUnknownId` 0 (a rate is NOT DERIVED: no identifier was checked) |
| Numeric accuracy | `numbers` | 613 checked; 585 export-matched; 25 evidence-only; 3 unmatched; `exportMatchedRate` 0.954323 |
| Contradiction rate | `contradictions.contradictionRate` | 0.010050 (`claimsContradicted` 2) |
| Claims citing invalid evidence | `contradictions.claimsCitingInvalidEvidence` | 0 |
| Answers with a contradiction | `contradictions.answersWithContradiction` | 2 |
| Unanswerable questions; answered anyway | `abstention.unanswerable`, `unanswerableAnswered` | 10; 1 |
| Abstention recall | `abstention.abstentionRecall` | 0.9 |
| Reason-code match among abstained | `abstention.reasonCodeMatchAmongAbstained` | 1 |
| Answerable questions; false-abstention rate | `abstention.answerable`, `falseAbstentionRate` | 46; 0.282609 |
| Accepted-outcome records; correct | `abstention.acceptedOutcomeRecords`, `acceptedOutcomeCorrect` | 0; null |

Arithmetic checks on the file's own figures: 585 + 25 + 3 = 613; 585 / 613 = 0.9543; 2 / 199 = 0.01005; 13 abstentions on answerable questions (22 - 9) / 46 = 0.2826; the reason-code match covers the 9 abstained unanswerable questions (10 - 1). The one unanswerable question that was answered is Q-FF-08 (`abstained` false in `h-audit-2/audit-answers.jsonl`; labelled unanswerable per decision P16-23).

Ranking checks by question (`h-audit-2/audit-answers.jsonl` `items[].checks[]`, counted by reading all 56 lines): the 12 ranking items (Q-RK-01 to -10, Q-FF-06 and Q-FF-08) each carry one `ranking` check with `ok` true and `problem` null, so 12 of 12 are valid; this equals `evidenceValidity.rankings`. The earlier "value-differs" results came from the metric-name defect (review 14 finding 1: the audit looked up the runtime's kebab-case metric names in an export index keyed by camelCase), fixed in W2-RB11 and W2-TR5. Every one of the 56 lines has `claimsContradicted` 0 except two (Q-CA-01 and Q-CA-05, 1 each; arithmetic: 2, equal to `claimsContradicted` 2 above). `audit-claims.jsonl` was not read for this file.

Verifier verdicts, reported apart as self-reported (`verifierSelfReported`; the file's note: "the system verifier's own verdicts, before it removed or weakened claims; not independent"): claim verdicts PARTIALLY_SUPPORTED 140, UNSUPPORTED 68, VERIFIED 59 (arithmetic: 267 verdicts against 199 audited claims; the file does not explain the difference); overall per answer PARTIALLY_SUPPORTED 33, VERIFIED 1 (34 answered); `claimsVerifiedButAuditContradicted` 2. Arithmetic, not a field: PARTIALLY_SUPPORTED 140 + VERIFIED 59 = 199, the audited claim count; review 14 finding 8 reads these verdicts as given before removal or weakening, as the note states.

Limits: the audit and the labels share the export (`h-answers/README.md`); path-hop, path-chain and supporting-work validity were checked on zero items, because no answered question produced those evidence kinds (all four shortest-path questions abstained, section 6.2.4); the audit reads no free text beyond the numbers and ids it extracts.

### 6.4 Human rating sample

Sources (W2-DOC4): `SCD/phase16-rating-filled.csv` (frozen, read-only), `SCD/phase16-rating-filled.as-entered.csv`, `SCD/phase16-rating-SHA256SUMS`, and `rating-sample-key.json` in `RUNS/exp-H-20261119T053926Z-2e8943cf/raw/answers/`. The rating files hold question, answer and evidence text; none of that text is copied here, and no note text is copied. In both rating files the `note` column is the empty string on all 20 rows (0 rows have a note; counted by reading).

Freeze record (the human's, `phase16-rating-SHA256SUMS`): `phase16-rating-filled.csv` sha256 `65cc1b77ec…` and `phase16-rating-filled.as-entered.csv` sha256 `809744048b…` (full values in that file). I cannot hash files; I did not recompute these values, and they are cited as the human's record.

Procedure facts (decision P16-46, H-11): the human rated the 20-answer sample (columns sampleId, question, answer, evidence, usefulness 1 to 5, clarity 1 to 5, supported yes or no, abstention appropriate yes, no or n.a., note) seeing the question, the answer and the evidence, and no metrics; the human did not see the key while rating. `rating-sample-key.json` was opened only after the rating was complete, to identify the 6 refusals. Single rater: there is no inter-rater agreement, and plan D7 names this as a threat to validity.

Rubric and sample design: the 1 to 5 scales for usefulness and clarity, and the supported and abstention-appropriate fields, are defined in `docs/agent-runs/phase-16/plan.md`, "Human rating rubric (D7)", lines 82 to 85; they are not copied here. Plan line 80 sets the sample: 20 Haiku answers, stratified, 2 or 3 per answerable stratum, 3 abstentions and 2 free-form. The key's `group` field matches: `abstention` is S03, S08 and S14 (3 rows); `free-form` is S09 and S18 (2 rows); the other 15 rows carry their stratum name as `group`. S08 is in the `abstention` group but its `stratum` is `free-form` and it is an answered row; the key does not say why it is in that group.

Abstention-column correction (P16-46): the abstention column was entered "yes" on all 20 rows (as-entered file; counted by reading the last fields of all 20 rows: yes 20, no 0, n.a. 0). At the human's instruction, Claude in chat then corrected it: n.a. for the 14 answered rows; for the 6 refusals S03 yes, S14 yes, S02 no, S16 no, S18 no, S20 no (the frozen file; counted: yes 2, no 4, n.a. 14). I compared usefulness, clarity, supported and note of all 20 rows between the two files by reading: they are equal; 18 of 20 rows differ in the abstention column only (14 yes to n.a., 4 yes to no; S03 and S14 are unchanged). I did not compare the question, answer and evidence text of the two files cell by cell. The frozen abstention values therefore come from the human's instruction applied by Claude in chat, not from the human's own entry, and the as-entered copy is kept.

Classification used below: "refused" is the 6 rows the key marks `abstained` true (S02, S03, S14, S16, S18, S20); I also read the answer text of each of these 6 and of the other 14 rows by reading, and the split agrees (the 6 are the refusal replies; the 14 give an answer). "Answered" is the other 14 rows. The key was used for this split and for strata only.

#### 6.4.1 Scores by sampleId (frozen file; sampleId order; U usefulness, C clarity, Sup supported)

| sampleId | Stratum (key) | Answered or refused | U | C | Sup | Abstention appropriate (frozen) | Abstention appropriate (as-entered) |
|---|---|---|---|---|---|---|---|
| S01 | community-component | answered | 3 | 3 | yes | n.a. | yes |
| S02 | researcher-lookup | refused | 4 | 4 | yes | no | yes |
| S03 | unanswerable | refused | 5 | 5 | yes | yes | yes |
| S04 | coauthor-lookup | answered | 4 | 5 | yes | n.a. | yes |
| S05 | temporal-comparison | answered | 5 | 5 | yes | n.a. | yes |
| S06 | temporal-comparison | answered | 5 | 5 | yes | n.a. | yes |
| S07 | ranking | answered | 5 | 2 | yes | n.a. | yes |
| S08 | free-form | answered | 5 | 5 | yes | n.a. | yes |
| S09 | free-form | answered | 4 | 5 | yes | n.a. | yes |
| S10 | researcher-lookup | answered | 5 | 5 | yes | n.a. | yes |
| S11 | ranking | answered | 4 | 5 | yes | n.a. | yes |
| S12 | researcher-lookup | answered | 5 | 5 | yes | n.a. | yes |
| S13 | coauthor-lookup | answered | 4 | 3 | yes | n.a. | yes |
| S14 | unanswerable | refused | 2 | 3 | yes | yes | yes |
| S15 | ranking | answered | 4 | 5 | yes | n.a. | yes |
| S16 | shortest-path | refused | 3 | 2 | yes | no | yes |
| S17 | coauthor-lookup | answered | 5 | 5 | yes | n.a. | yes |
| S18 | free-form | refused | 2 | 5 | yes | no | yes |
| S19 | community-component | answered | 3 | 4 | yes | n.a. | yes |
| S20 | shortest-path | refused | 3 | 2 | yes | no | yes |

The scores are equal in both files (checked above). S08 is in the key's `group` "abstention" and `stratum` "free-form"; it is an answered row (`abstained` false) and the stratum column above is the key's `stratum` field.

#### 6.4.2 Distributions (counts of each score; counted from 6.4.1)

| Score | Usefulness (rows) | Clarity (rows) |
|---|---|---|
| 1 | 0 | 0 |
| 2 | 2 (S14, S18) | 3 (S07, S16, S20) |
| 3 | 4 (S01, S16, S19, S20) | 3 (S01, S13, S14) |
| 4 | 6 (S02, S04, S09, S11, S13, S15) | 2 (S02, S19) |
| 5 | 8 (S03, S05, S06, S07, S08, S10, S12, S17) | 12 (S03, S04, S05, S06, S08, S09, S10, S11, S12, S15, S17, S18) |
| total | 20 | 20 |

Arithmetic: usefulness 0+2+4+6+8 = 20, sum 2x2 + 3x4 + 4x6 + 5x8 = 4 + 12 + 24 + 40 = 80; clarity 0+3+3+2+12 = 20, sum 2x3 + 3x3 + 4x2 + 5x12 = 6 + 9 + 8 + 60 = 83.

Supported: yes 20, no 0 (all 20 rows read). Abstention appropriate (frozen): yes 2 (S03, S14), no 4 (S02, S16, S18, S20), n.a. 14. The as-entered column: yes 20.

#### 6.4.3 Means (arithmetic; 2 decimals, rounded half up from the quotient shown)

| Group | Rows | Usefulness sum / mean | Clarity sum / mean |
|---|---|---|---|
| All | 20 | 80 / 20 = 4.00 | 83 / 20 = 4.15 |
| Answered (14 rows) | 14 | 61 / 14 = 4.357, 4.36 | 62 / 14 = 4.429, 4.43 |
| Refused (6 rows) | 6 | 19 / 6 = 3.167, 3.17 | 21 / 6 = 3.50 |

Inputs: refused usefulness S02 4 + S03 5 + S14 2 + S16 3 + S18 2 + S20 3 = 19; refused clarity 4 + 5 + 3 + 2 + 5 + 2 = 21; answered sums are the totals less the refused sums (arithmetic: 80 - 19 = 61; 83 - 21 = 62). The means are descriptive of 20 rows by one rater; no interval is computed.

#### 6.4.4 Counts by stratum (key `stratum`; counted)

| Stratum | Rows | Refused | Sample ids |
|---|---|---|---|
| researcher-lookup | 3 | 1 | S02, S10, S12 |
| coauthor-lookup | 3 | 0 | S04, S13, S17 |
| ranking | 3 | 0 | S07, S11, S15 |
| shortest-path | 2 | 2 | S16, S20 |
| community-component | 2 | 0 | S01, S19 |
| temporal-comparison | 2 | 0 | S05, S06 |
| unanswerable | 2 | 2 | S03, S14 |
| free-form | 3 | 1 | S08, S09, S18 |
| total | 20 | 6 | |

Arithmetic: 3+3+3+2+2+2+2+3 = 20; refused 1+0+0+2+0+0+2+1 = 6. No per-stratum mean is given because the strata hold 2 or 3 rows each.

#### 6.4.5 Check against the human's own figures (given in the work order)

| Figure | Human | Counted here | Result |
|---|---|---|---|
| Rows | 20 | 20 | match |
| Usefulness mean, all | 4.00 | 80 / 20 = 4.00 | match |
| Usefulness mean, answered (14) | 4.36 | 61 / 14 = 4.357 | match |
| Usefulness mean, refusals (6) | 3.17 | 19 / 6 = 3.167 | match |
| Clarity mean, all | 4.15 | 83 / 20 = 4.15 | match |
| Clarity mean, answered | 4.43 | 62 / 14 = 4.429 | match |
| Clarity mean, refusals | 3.50 | 21 / 6 = 3.50 | match |
| Supported | 20 of 20 yes | 20 of 20 yes | match |
| Abstention appropriate yes | 2 (S03, S14) | 2 (S03, S14) | match |
| Abstention appropriate no | 4 (S02, S16, S18, S20) | 4 (S02, S16, S18, S20) | match |
| Abstention appropriate n.a. | 14 | 14 | match |

There is no difference to report.

#### 6.4.6 Limits

- One rater, 20 rows; no inter-rater agreement (plan D7). Whether the rater is the person who wrote the free-form questions and checked 15 labels (section 3.5) is not stated in the files read.
- The sample is 20 of the 56 questions (`ratingSampleSize` 20 and `seed` 20261118 in H's `config`, 6.2; the design is stated above from plan.md line 80 and the key's `group` field, and the selection procedure itself is not read here); the strata hold 2 or 3 rows, so the stratum counts above are not rates.
- The rater saw the question, the answer and the evidence and no metrics, and did not see the key; the frozen abstention column was corrected after the rating by instruction (above).
- All 20 rows are marked supported "yes", including the 6 refusals; the file has no row marked "no", so the column does not separate rows. Not interpreted here.
- The frozen CSV sha256 is the human's record; the hash was not recomputed in this work order (no hashing tool), and the files are outside the repository.

### 6.5 Entity resolution

#### 6.5.1 Replay `er-replay-v3-r3-1` (`RUNS/er-replay-v3-r3-1/evaluation-report.txt`; also `evaluation-report.json`, `decisions.jsonl`)

`EvaluationRun` on gold v3, policy `scholarchain.er.policy/1.4.0` (accept 0.95, reject 0.35, adjudication band [0.7, 0.95); the thresholds "are the shipped provisional values, not tuned on this set"). Inputs: gold `6e5342ddd6…`, pairs `be3aea1b75…`, records directory digest `646dbc6981…`, strata-key `7a9bcd25ce…`. Console (human, `human-commands.md` overnight chain row): pooled same 77/14/4/1, different 0/109/14/8, unsure 0/1/2/0, precision 1.000, recall 0.802, F1 0.890, "equal to `eval-v3-r3`", decisions changed 0 of 230.

Pooled over researcher and work (gold rows include unsure; columns ACCEPTED, REJECTED, UNRESOLVED, CONFLICT, MISSING):

| Gold | ACCEPTED | REJECTED | UNRESOLVED | CONFLICT | MISSING |
|---|---|---|---|---|---|
| same | 77 | 14 | 4 | 1 | 0 |
| different | 0 | 109 | 14 | 8 | 0 |
| unsure | 0 | 1 | 2 | 0 | 0 |

Precision 1.000 [0.952, 1.000] (Wilson 95%); recall 0.802 [0.711, 0.869]; F1 0.890. Report warnings: 3 pairs labelled unsure excluded from precision and recall; for researcher and work "only 0 false positives available, fewer than 10", and for work "only 0 false negatives available, fewer than 10".

Comparison section (`== comparison with evaluation-report.json`, comparing against `docs/agent-runs/phase-05/gold/eval-v3-r3/`, "descriptive only"): pairs earlier 230, this 230; precision 1.000 and 1.000; recall 0.802 and 0.802; F1 0.890 and 0.890; the same, different and unsure rows of the earlier and the new report are printed equal. Decision comparison (`== matcher decisions compared with decisions.jsonl`): earlier 230, this run 230, in both 230, only earlier 0, only this run 0; "decisions changed (outcome, path, score or reasons): 0 of 230"; by outcome none; by gold label none.

The same comparison section also prints "gold labels changed between the files: 4 of 153 pass-3 pairs (different->same 1, same->different 1, unsure->different 2)" with a note that the v2 label of a pass-3 pair is its pass-1 label and that the earlier gold file itself is not read. Explanation (review 14 finding 5): that line compares each pass-3 row's gold label with the `pass1_label` stored in the same gold-v3 file (`ReportComparison.changes`, `EvaluationRun.passOneLabels`), so it describes gold v3 itself and not a difference between two gold files. `eval-v3-r3` was built on the same gold v3 (sha256 prefix `6e5342ddd6`, its `evaluation-report.json` line 7), so the pooled rows are equal and 0 decisions changed. The wording "between the files" in the report is therefore misleading when the two gold sha256 values match (advisory to the entity-resolution owner).

By stratum (private key, report only): clear-non-match 76 pairs: same 0 accepted, 11 rejected, 0 unresolved, 1 conflict; different 0, 55, 1, 8; recall 0.000 [0.000, 0.243]; precision and F1 n/a. exact-id 77 pairs: same 77 accepted; recall 1.000 [0.952, 1.000], precision 1.000 [0.952, 1.000], F1 1.000. hard-common-name 77 pairs: same 0 accepted, 3 rejected, 4 unresolved, 0 conflict; different 0, 54, 13, 0; unsure 0, 1, 2, 0; recall 0.000 [0.000, 0.354]; precision and F1 n/a. By kind: researcher 212 scored (3 unsure excluded), precision 1.000, recall 0.765, F1 0.867, decided-recall 0.816, coverage 0.873, unresolved-rate 0.085, conflict-rate 0.042; work 15 scored, precision, recall and F1 1.000. Adjudication band: UNRESOLVED in band by gold same 4, different 7, unsure 0; eligible 11, abstained by `offline-stub/abstain-1` 11, model calls 11. Per-pair error lines, names and identifiers in that report are not copied here.

Limits (report "== uncertainty", repeated): 230 labelled pairs with 3 unsure; stratified by design, not random, so not population rates; pooled precision does not measure the probabilistic path (the `includeAgreeingContext` false export excluded 5,766 same-name agreeing-context pairs) and the exact-id stratum agrees with the exact-id path by construction; Wilson intervals ignore stratification and clustering by author; recall is over candidate pairs only; one reviewer; DBLP homonym labels measure agreement with DBLP; thresholds are provisional and a change chosen from the sweep would need a separate sample.

#### 6.5.2 ErExtras `erextras-1` (`RUNS/erextras-1/er-extras-report.txt`, `er-extras-report.json`, `bootstrap-resamples.csv`, `run-info.json`)

Tool `er-extras/1.0.0`, seed 16, 1,000 resamples, level 0.950, status COMPLETE, started 2026-11-19T05:49:05Z, export manifest sha256 `b3e9985647…` (`run-info.json`). Inputs (report lines 4 to 7): decisions `34088d9cf6…` (230 rows, "gold manifest not-listed"), gold `6e5342ddd6…` (230 rows, match), strata-key `7a9bcd25ce…` (230 rows, match), gold manifest `cddf7fcc56…` (44 rows). Warning printed: "llm-adjudicated decided no pair: per-path recall for it is undefined (the replay's adjudicator is the offline stub that always abstains)".

Reference check (report line 14): `ER_REFERENCE_CHECK status=MATCH`, expected and actual both TP 77, FP 0, same 96, different 131, unsure 3, precision 1.000, recall 0.802, F1 0.890, source `docs/agent-runs/phase-05/gold/eval-v3-r3/evaluation-report.txt`.

Pooled (gold-unsure excluded): 230 pairs: same 96, different 131, unsure 3; TP 77, FP 0; precision 1.000, recall 0.802, F1 0.890. Stratified bootstrap 95% CI (resampled within strata; 1,000 resamples used, 0 undefined in each): precision 1.000 [1.000, 1.000]; recall 0.802 [0.740, 0.875]; F1 0.890 [0.851, 0.933].

Per-path recall (gold-same accepted on the path / gold-same decided on the path):

| Path | Gold-same | Accepted | Recall | Precision | Contribution to pooled recall |
|---|---|---|---|---|---|
| exact-id | 78 | 77 | 0.987 | 1.000 | 0.802 |
| deterministic-bibliographic | 9 | 0 | 0.000 | n/a | 0.000 |
| probabilistic | 9 | 0 | 0.000 | n/a | 0.000 |
| llm-adjudicated | 0 | 0 | n/a | n/a | 0.000 |

Gold-same with no decision: 0.

Sensitivity to suspected mislabelled pairs: "manifest claims 12 pairs; it names none: NOT IDENTIFIABLE from the manifest". The set used is a proxy, labelled "PROXY, not an identification: gold-same pairs of the clear-non-match stratum" (12 pairs).

| Variant | n | gold-same | Precision | Recall | F1 |
|---|---|---|---|---|---|
| all-labels | 230 | 96 | 1.000 [1.000, 1.000] | 0.802 [0.740, 0.875] | 0.890 [0.851, 0.933] |
| exclude-suspects (proxy removed from every count and from the resampled strata) | 218 | 84 | 1.000 [1.000, 1.000] | 0.917 [0.865, 0.975] | 0.957 [0.928, 0.987] |
| relabel-suspects-different (proxy counted as gold-different) | 230 | 84 | 1.000 [1.000, 1.000] | 0.917 [0.865, 0.975] | 0.957 [0.928, 0.987] |

Tier S population view (every export row of `SCD/rehearsal/a-export`, not a sample): `nodes-researcher` 17,569 rows, `linkStatus` external-id 3,041 (0.1731), integrity-failure 648 (0.0369), merged 2,075 (0.1181), unlinked 11,805 (0.6719) (arithmetic: sum 17,569). `edges-coauthored` 98,661 rows; unresolved-endpoint rates:

| Window | Rows | Endpoint A unresolved | Endpoint B unresolved | Either |
|---|---|---|---|---|
| overall | 98,661 | 0.8025 | 0.5835 | 0.8025 |
| 1900-1999 | 76 | 1.0000 | 0.8947 | 1.0000 |
| 2000-2004 | 398 | 0.8668 | 0.7588 | 0.8668 |
| 2005-2009 | 1,468 | 0.8917 | 0.7377 | 0.8917 |
| 2010-2014 | 3,342 | 0.8423 | 0.6712 | 0.8423 |
| 2015-2019 | 7,640 | 0.8370 | 0.5882 | 0.8370 |
| 2020-2029 | 36,486 | 0.7852 | 0.5644 | 0.7852 |
| all | 49,251 | 0.8038 | 0.5844 | 0.8038 |

Limits (`er-README.md` "Threats to validity", with the report text): gold is 230 pairs on the Phase 4 subset, not tier S, and the stratum mix drives the pooled numbers; the bootstrap resamples within strata and ignores clustering by author; the `llm-adjudicated` path decided no pair (offline stub), so its recall is undefined; the exact-id stratum is selected by the rule that path accepts on; one reviewer; the suspect set is a proxy, since the manifest names no pair.

---

## 7. Reproducibility (plan group 7)

Plan group 7 lists four items: a clean-checkout build, replay of the local sample pipeline and `EvaluationRun` compared by checksum, deployment from an approved plan cited from the release records (no new deploy), and regeneration of the B to F derived tables compared with the committed checksums. Decision P16-42 item 6 asked for the clean-checkout build; the human ran it on 2026-11-19 and 7.1 records it (W2-DOC6). Status of the four items: clean-checkout build recorded (7.1); replay of `EvaluationRun` recorded (6.5.1, not repeated here); replay of the bounded local sample pipeline NOT DERIVED (7.3); deployment from an approved plan cited, no new deploy (7.3); B to F regeneration recorded by W2-DOC7 and corrected by W2-RB18 (7.3: run by Claude in chat; 42 of 44 committed derived files identical, counts corrected by the human, P16-47). Nothing in this section is a result unless a file is cited.

### 7.1 Clean-checkout build (plan H-13)

Plan H-13 asks for one thing: a clean-checkout `sbt clean check` (plan.md H-13 row; group 7 text: "the human clones into a temporary directory and runs `sbt clean check`"). Recorded by W2-DOC6 from `human-commands.md` "Results recorded" (2026-11-19 rows) and the two logs named there. Ran by the human (`!` prefix, in the orchestrator session); the command was the human's own variant of the H-13 command (a `mktemp -d` directory under `/tmp`, a quiet `git clone`, no dirty-file count printed; a fresh clone holds only committed files).

| Item | In-repository phase-gate `sbt clean check` | Clean-checkout `sbt clean check` (H-13) |
|---|---|---|
| Tree | working repository at commit `ccbc1ec` | fresh `git clone` of the repository, new temporary directory; `ccbc1ec` checked out per the human's relay (`human-commands.md` H-13 row), the log has no HEAD line |
| Exit | 0 | 0 |
| `[success] Total time` | 1314 s (0:21:54.0) | 1315 s (0:21:55.0) |
| Completed | Nov 19, 2026, 8:36:12 AM | Nov 19, 2026, 9:01:38 AM |
| Log | `SCD/logs/phase16-sbt-clean-check.log` (901,243 lines) | `SCD/logs/phase16-clean-checkout.log` (901,410 lines) |
| Last two totals (confirmed in the log, last lines) | `Passed: Total 99, Failed 0, Errors 0, Passed 99` and `Passed: Total 1427, Failed 0, Errors 0, Passed 1427` | the same two lines, with the same figures |
| All suite totals (review 23 Grep, `docs/agent-runs/phase-16/wave-2/review-23-verdict.md`; the same set as the human's reading) | 49, 331, 501 (Passed 500, Skipped 1), 389, 473, 170, 260, 99, 1427 in print order; all Failed 0 and Errors 0; no `[error]` line | the same nine totals, equal group by group; printed in the order 49, 331, 389, 501, 473, 170, 260, 99, 1427 (389 before 501); all Failed 0 and Errors 0; no `[error]` line |

What I confirmed and what I did not:

- Confirmed by reading the log tails with the Read tool (lines 901,190 to 901,243 of the first log and 901,360 to 901,410 of the second): the last two `Passed: Total` lines and the `[success] Total time` line of each log, as in the table. In both logs the order is 99 then 1427. The completion times 8:36:12 AM and 9:01:38 AM and the durations 1314 s and 1315 s equal the human's relay. The two last-total pairs are equal (99 and 1427, both with 0 failed and 0 errors).
- Confirmed by review 23's Grep (`docs/agent-runs/phase-16/wave-2/review-23-verdict.md`, pattern `Passed: Total|Failed: Total|Skipped|\[success\]|\[error\]` on both logs; not by my tail reads): all nine group totals are equal group by group between the two logs (9 of 9), all with Failed 0 and Errors 0, and neither log has an `[error]` line. Log lines, phase-gate / clean-checkout: 49 at 166 / 546; 331 at 2941 / 3024; 501 at 2942 / 3149; 389 at 2943 / 3025; 473 at 3197 / 3192; 170 at 4853 / 4864; 260 at 4943 / 5667; 99 at 901240 / 901407; 1427 at 901241 / 901408. The phase-gate log prints 389 after 501 and the clean checkout prints 389 before 501; the set and the totals are the same.
- The 1 skipped test, in both logs (review 23 Grep: one `==> s` marker per log, phase-gate line 2405 and clean-checkout line 2932, and exactly one `Skipped 1` total per log, in the 501 group, so the skip belongs to the 501 group by elimination); earlier recorded for the in-repository run (`human-commands.md`, phase-gate row; a read-only search made on 2026-11-19): `scholarchain.storage.ExportHeapMeasureSuite` "heap and time to load the real tier-S export" (`modules/storage/src/test/scala/scholarchain/storage/ExportHeapMeasureSuite.scala:61`), skipped by `assume(dirName.isDefined, "SCHOLARCHAIN_S_EXPORT_DIR is not set")` at lines 62 to 63; opt-in by design (doc comment at lines 8 to 11), so it skips on any machine without that variable; its JUnit report has `skipped="1"`. The row's link of the skip to the 501 group was inferred from the timestamp only; review 23's per-log skip count now supports it by elimination. I did not open the test file myself.
- A fresh clone holds only committed files, so the clean checkout built the committed sources at the commit checked out. That this was `ccbc1ec` rests on the human's relay (`human-commands.md` H-13 row); the clean-checkout log has no `HEAD=`, `CLONE_DIR`, `DIRTY_FILES` or `ccbc1ec` line (it starts at sbt's welcome). The working repository can hold uncommitted changes; the number of dirty files in the working repository at the phase-gate run is NOT RECORDED (the human's command printed no count).
- The logs hold machine paths and a private network address from Spark output; none is copied here.

Limits: one run in each tree on one machine; no second clean-checkout run, so run-to-run variation of the suite totals is not measured; the two durations differ by 1 s. "Clean checkout" here means a fresh clone built in a new directory on the same machine; whether the dependency caches were warm is NOT STATED in the files read.

The sequence below is the one in `scripts/README.md` "From a clean checkout" (every line runs without AWS credentials; sbt and the Terraform provider download need network access). It was not run in the clean checkout; no result of any line of it is recorded in the files read. `<repository-url>` and `<id>` are placeholders the human fills (`<id>` is the approved account id; do not write it into any file in the repository). The checkout directory below is a suggestion, not a path recorded in any file read.

```
git clone <repository-url> /Users/winnguyen/scholarchain-data/clean-checkout-1/ScholarChain
cd /Users/winnguyen/scholarchain-data/clean-checkout-1/ScholarChain
sbt clean check
bash scripts/check.sh --dry-run
bash scripts/check.sh
bash scripts/package.sh --dry-run
bash scripts/package.sh --out-dir /Users/winnguyen/scholarchain-data/clean-checkout-1/package-1
bash scripts/archive-evidence.sh --dry-run --include /Users/winnguyen/scholarchain-data/clean-checkout-1/package-1
bash scripts/archive-evidence.sh --include /Users/winnguyen/scholarchain-data/clean-checkout-1/package-1
bash scripts/plan.sh --dry-run --environment course --account-id <id> --cost-ceiling-usd 20
bash scripts/run-experiment.sh --help
bash tests/scripts/run.sh
```

Prerequisites (`scripts/README.md`): bash 3.2 or later, git, python3, shellcheck, JDK 21, sbt, Terraform, and Docker with a running engine for `package.sh` (not needed with `--skip-images` or any dry run). `check.sh` ends with `CHECK_RESULT=PASS` or `PASS_WITH_SKIPS`; a skipped gate is not a pass.

Earlier recorded runs that are not this result (`human-commands.md`): the Phase 16 harness `bash tests/scripts/run.sh` at `RESULT pass=1949 fail=0`, `SKIPPED_CASES 0` (logs `phase16-wave2-harness.log`, `phase16-wave2b-harness.log`, `phase16-wave2c-harness.log`, and `phase16-wave2d-harness.log` of 2026-11-19 at `RESULT pass=1949 fail=0`, `human-commands.md`); `check.sh --skip-sbt --skip-terraform` at `CHECK_RESULT=PASS_WITH_SKIPS` (Wave 2). The human's `sbt clean check` results at `ccbc1ec` (in the repository and in the clean checkout) are in the table above.

### 7.2 Commands that reproduce each frozen run

The commands below are copied from `human-commands.md` (the blocks cited) with new run ids so no immutable directory is overwritten. Each needs no AWS call. All paths are absolute. `SCD=/Users/winnguyen/scholarchain-data`, repository `/Users/winnguyen/ScholarChain`. Because the `evaluation` project forks `runMain`, relative paths resolve in `experiments/evaluation/`: use absolute paths only (`human-commands.md` 08:33 row).

| Frozen run | Source of the command | Check |
|---|---|---|
| `graphchecks-a-export-2` | `human-commands.md` "Pending (after the Wave 1 commit)", GraphChecks rerun after OD-3 | `GRAPHCHECKS RECONCILIATION ... edgeDifference=0 ... vertexDifference=0`; compare the report files |
| `factorycounts-1` | same section, FactoryCounts block | compare `factory-summary.txt` |
| benchmark v1 (48 generated lines) | same section, m4 regeneration block (new empty scratch directory) | three `REGEN_MATCH` lines |
| `er-replay-v3-r3-1` and `erextras-1` | "Pending: G and H at e6cf0c0 and later", step 3 (the ER part only) | replay "decisions changed 0 of 230"; `ER_REFERENCE_CHECK status=MATCH` |
| `h-audit-2` | "Pending: after the overnight chain", AnswerAudit `h-audit-2` block | `AUDIT_RESULT status=COMPLETE audited=56`; compare `audit-summary.json` (the `h-audit-2` block, which includes the metric-name fix) |
| `h4-diagnostic-4`, G, H | "Pending: G and H at e6cf0c0 and later", steps 1, 2a to 2d, 3 | see below |

GraphChecks (run id new):

```
cd /Users/winnguyen/ScholarChain
set -o pipefail
sbt "evaluation/runMain scholarchain.evaluation.graph.GraphChecks /Users/winnguyen/scholarchain-data/rehearsal/a-export /Users/winnguyen/scholarchain-data/experiments/runs/phase16 --seed 16 --sample 50 --run-id graphchecks-a-export-repro-1 --expect-vertices 32225 --expect-edges 135788 --expect-source /Users/winnguyen/scholarchain-data/logs/phase14-rehearsal-graph-2.log:20" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-graphchecks-repro-1.log | tail -n 60; echo "exit: $?"
```

FactoryCounts (run id new):

```
cd /Users/winnguyen/ScholarChain
set -o pipefail
sbt "evaluation/runMain scholarchain.evaluation.factory.FactoryCounts /Users/winnguyen/ScholarChain/docs/agent-runs /Users/winnguyen/ScholarChain/experiments/evaluation/factory-mapping.tsv /Users/winnguyen/scholarchain-data/experiments/runs/phase16 --rewrites /Users/winnguyen/ScholarChain/experiments/evaluation/factory-rewrites.tsv --phases 1-15 --run-id factorycounts-repro-1" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-factorycounts-repro-1.log | tail -n 60; echo "exit: $?"
```

Note: `docs/agent-runs` grows as Phase 16 records are added, so a later FactoryCounts run over phases 1 to 15 is compared against the counts in section 2 and any difference is read, not assumed to be an error. This is stated here as a property of the input directory, not as an observed result.

ER replay and ErExtras (output directory new; `mkdir` without `-p` stops if it exists):

```
cd /Users/winnguyen/ScholarChain
set -o pipefail
SCD=/Users/winnguyen/scholarchain-data; P5=/Users/winnguyen/ScholarChain/docs/agent-runs/phase-05/gold; ER_OUT=${SCD}/experiments/runs/phase16/er-replay-v3-r3-repro-1
mkdir ${ER_OUT}
sbt "entityResolution/runMain scholarchain.er.eval.assess.EvaluationRun --pairs ${P5}/pairs.jsonl --gold ${P5}/gold-v3.jsonl --records ${SCD}/phase4-out/subset-v5/run=run-v1-d4722bc7-0d02-42da-bdb6-088ee508a143/snapshot=8e1be6626420/stage=graph-export/works/v1 --strata-key ${P5}/private/strata-key.jsonl --out ${ER_OUT} --compare-with ${P5}/eval-v3-r3/evaluation-report.json --compare-decisions ${P5}/eval-v3-r3/decisions.jsonl" 2>&1 | tee ${SCD}/logs/phase16-er-replay-repro-1.log | tail -n 40
sbt "evaluation/runMain scholarchain.evaluation.er.ErExtras --decisions ${ER_OUT}/decisions.jsonl --gold ${P5}/gold-v3.jsonl --strata-key ${P5}/private/strata-key.jsonl --gold-manifest ${P5}/GOLD-MANIFEST.json --export ${SCD}/rehearsal/a-export --out ${SCD}/experiments/runs/phase16 --run-id erextras-repro-1" 2>&1 | tee ${SCD}/logs/phase16-erextras-repro-1.log | tail -n 40
```

AnswerAudit (run id new):

```
cd /Users/winnguyen/ScholarChain
set -o pipefail
SCD=/Users/winnguyen/scholarchain-data
sbt "evaluation/runMain scholarchain.evaluation.answer.AnswerAudit --answers ${SCD}/experiments/runs/phase16/exp-H-20261119T053926Z-2e8943cf/raw/answers/answers.jsonl --labels /Users/winnguyen/ScholarChain/experiments/benchmark/labels-v1.jsonl --export-dir ${SCD}/rehearsal/a-export --out-dir ${SCD}/experiments/runs/phase16 --run-id h-audit-repro-1" 2>&1 | tee ${SCD}/logs/phase16-h-audit-repro-1.log | tail -n 40
```

G, H and the diagnostic call AWS (Bedrock, Titan, S3 Vectors, S3 reads) and are not reproduced without a new human approval record. The blocks to use are in `human-commands.md` "Pending: G and H at e6cf0c0 and later" (steps 1, 2a, 2b, 2c, 2d and 3). They read the account id from STS and the bucket names from Terraform outputs at run time and mask the account id in the console log; this file does not copy them. A rerun spends: the frozen runs' estimates were 0.000117 USD (G) and 0.891881 USD (H, metered from Converse usage tokens, 6.2.3), against ceilings of 0.25 and 3.00 USD; the billed Phase 16 Bedrock total at H-1c was 1.46 USD (P16-43). Frozen G repeats are identical (6.1.6); frozen H repeats are not byte-identical (`sameAnswerHash` 0.65, 6.2.5).

Replay results already recorded: the benchmark regeneration at generator commit `afaa244b` matched all three files (m4, section 3.3); `er-replay-v3-r3-1` equals `eval-v3-r3` with 0 of 230 decisions changed (6.5.1); `graphchecks-a-export-2` reconciliation difference 0 and 0 (1.3).

### 7.3 Deployment from an approved plan; B to F regeneration

- Deployment from an approved plan: no new deploy in Phase 16 (decision P16-34). The release records are `docs/releases/phase-14/` and `docs/releases/phase-15/` (README digest tables); they were not re-read for this file: NOT DERIVED here.
- Regeneration of the B to F derived tables from the frozen inputs and comparison by sha256 with `experiments/results` (plan H-12; recorded by W2-DOC7 from the H-12 row of `human-commands.md` "Results recorded", 2026-11-19). Run by Claude in chat (P16-5), local, no AWS: the frozen inputs in `SCD/results-copy` were re-derived into `SCD/phase16-h12-regen` and each output file was compared with the committed file. Logs: `SCD/logs/phase16-h12-regen.log` (8 `DERIVE_COMPLETE` lines then `EXIT=0`) and `SCD/logs/phase16-h12-compare.log` (49 lines, each `SAME` or `DIFF` and a relative path). Corrected result (decision P16-47, the human's correction of the H-12 row after review 25; source `phase16-h12-compare.log`; review 25's file counts): 42 of 44 committed derived files identical: D 9 of 9, E 5 of 5 with the original `--title "Experiment E: latency by mode"`, F2 12 of 12 (2 verdict, 6 queue, 4 metrics), C 10 of 10 (6 queue, 4 metrics), B 6 of 8 (`derived.json` and `summary.md` differ only in the recorded input sha256 and the source path, per the relay: because the regeneration read the redacted copy, account id masked; I did not open them). The relayed F2 15 and C 12 were miscounts by Claude in chat, corrected by the human (P16-47); my earlier "in conflict" and "unresolved" wording is withdrawn. The compare log has 49 lines, 46 SAME and 3 DIFF; the 49 includes a 5-file E run with another title (4 SAME, 1 DIFF `latency.svg`, only the title text differing per the relay), which is outside the 44 committed derived files. Confirmed by reading `phase16-h12-compare.log` (counted by reading its 49 lines; review 25 counted the same) and listing `SCD/phase16-h12-regen` (49 files in 10 output directories): D 9 SAME, 0 DIFF (5 `D-latency` + 4 `D-metrics`); E with the original title 5 SAME (the 5 lines after the C block; the output directory is `e-latency-titlematch`, inferred from the directory listing, not stated in the log); E with the other title 4 SAME and 1 DIFF, `latency.svg`; B 6 SAME and 2 DIFF, the DIFF files being `derived.json` and `summary.md`; the B `SAME` files are `capacity-timeline.csv`, `capacity.svg`, `cloudwatch-series.csv`, `events.csv`, `instances.csv` and `scale-events.csv`; `phase16-h12-regen.log` ends `EXIT=0`. The log has 12 F2 lines (2 verdict, 6 queue, 4 metrics) and 10 C lines (6 queue, 4 metrics), all SAME; the committed `experiments/results` holds 12 derived files for F2 (`derived-verdict` 2, `derived-queue` 6, `derived-metrics` 4) and 10 for C (`derived-queue` 6, `derived-metrics` 4), equal to the log's counts and to the human's correction. Totals by arithmetic from the log (not a field): 46 SAME and 3 DIFF in 49 lines (9 + 4 + 12 + 10 + 5 + 6 = 46; 1 + 2 = 3), or, leaving out the other-title E run, 42 of 44 files identical (9 + 5 + 12 + 10 + 6 = 42; 9 + 5 + 12 + 10 + 8 = 44). The earlier relayed total 47 of 49 is superseded by the 42 of 44. Not confirmed (rest on the relay alone): that the two B differences are only the input hashes and the source path (I did not open the B `derived.json` or `summary.md` of either side), that the redacted copy is the cause, that the `--title` value was the original one, and that the compare was by sha256 (the log shows `SAME` and `DIFF` only, with no hash value). The regeneration log has 8 `DERIVE_COMPLETE` lines (D 2, E 1, F2 3, C 2) and none for B nor for the title-matched E rerun (review 25); B's `b-derived` output files exist. I copied no account id, bucket name, ARN or 12-digit number from the logs; the regeneration log prints session paths, which are not copied. The derive commands themselves are not in the H-12 row and were not read. This regeneration compares derived tables only; it is not a replay of the sample pipeline.
- Replay of the bounded local sample pipeline compared by checksum (plan group 7): NOT DERIVED: no record of it in the files read. The harness runs and the benchmark regeneration in 7.1 and 7.2 are not that replay.
- The D bootstrap re-derive (WO-5) was run by Claude in chat with `cmp` of the point-estimate table returning exit 0 (section 4.4); besides the H-12 regeneration above, that is the only derive re-run recorded.

---

## 8. Known items

- Account id in raw run files (decision P16-42 item 2): both `raw/entrypoint.log` files (G and H) hold the unmasked account id inside the bucket names of sbt's `running (fork)` line, and the `run-record.env` files hold an account id field. The raw run directories stay as written, immutable and local. Every copy into the repository goes through the Phase 15 redaction step plus the strict 12-digit scan. Follow-up for later: pass the bucket names without echoing them in sbt's fork line (experiment-engineer, a later work order). This file copies none of those values; the scan status is in the redaction note at the end of this section.
- Spot-check: human spot-check 15 of 15 OK (the sheet); Claude in chat's independent machine check covered 25 labels (the 15 sheet rows plus 10 more ranking labels), 25 of 25 MATCH (P16-42 item 1).
- H spend: 0.891881 USD is metered from Converse usage tokens (all 57 `model-calls.jsonl` lines have `promptTokens` > 0 and `completionTokens` > 0, review 15; H ran before WO-3 part 6, so in its build `tokensEstimated` and `haikuCallsChargedAtWorstCase` could not detect a reply without usage (review 15 finding 2), fixed in WO-3 part 6, P16-45, and the 57 of 57 check is H's evidence; the summary `tokensEstimated: true` was a defect, review 14 finding 3, fixed in WO-3 part 5; the recorded run keeps the old flag; P16-44, 6.2.3). Billed figure (H-1c, P16-43): 10.39 USD month-to-date, +1.46 since H-1b, the Phase 16 Bedrock usage (H, h4 diagnostics 1-4, G); the dashboard forecast is an extrapolation, not spend.
- `phase16-ci-on/SUMMARY.txt` and `phase16-ci-off/SUMMARY.txt`: sandbox prefixes rewritten to `/Users/winnguyen/` by Claude in chat on 2026-11-19 at about 07:05 CST (P16-44; prefixes only, CSVs untouched, 4.4); this file cites only the local directory `/Users/winnguyen/scholarchain-data/phase16-ci-on/`.
- ER replay comparison: the "4 of 153 pass-3 pairs" label line compares pass-3 labels with `pass1_label` inside the same gold-v3 file, and `eval-v3-r3` used that same gold v3 (sha256 prefix `6e5342ddd6`), so the pooled rows are equal and 0 decisions changed (6.5.1; review 14 finding 5).
- Audit: `h-audit-2` (after the metric-name fix) passes 12 of 12 ranking items, `exportMatchedRate` 0.954323 (585 export-matched, 25 evidence-only, 3 unmatched of 613) and `claimsCitingInvalidEvidence` 0 (6.3; the superseded earlier run is noted there). The 3 unmatched numbers and the 2 contradicted claims (rate 0.010050) are not explained here.
- G: the meaning of `cost.haikuCalls` 210 in a stub-synthesis run is not stated (6.1).
- Human audits, recorded by W2-DOC5 from the human's relay (`human-commands.md`, 2026-11-19): H-8 provenance, 10 of 10 DBLP-backed traced edges OK (45 of the 50 traced edges are DBLP-backed; the first 10 of those were checked; the 5 non-DBLP edges were outside the check; 1.2). H-9 factory audit of Phase 14, 16 of 20 counted items correct (arithmetic 80%); the Phase 14 first-pass "without review" count of 12 is a known parser miss (reviews are in decision files), the frozen JSON has the 12 only as a total over phases 1-15 (lines 462-464), and the 2.2 denominators sum to 74, the reviewed pairs, so the 12 unreviewed pairs are in no 2.2 row; not recomputed, and which pairs and agents remain NOT DERIVED (2.3a); human-annotated rewrites 0 stands as the human's statement (2.3a). Single auditor each; the sheets' OK columns are blank, so these are the human's relayed results, not a marked file.
- Not in any frozen file read, so NOT DERIVED: the Phase 15 billed total beyond the readings in 4.1; the bounded local sample pipeline replay (7.3).
- Recorded elsewhere in this file, not NOT DERIVED: B to F regeneration, 7.3 (W2-RB18, P16-47: 42 of 44 committed derived files identical: D 9/9, E 5/5, F2 12/12, C 10/10, B 6/8; compare log 49 lines, 46 SAME and 3 DIFF, the 49 including a 5-file other-title E run; the relayed F2 15 and C 12 were miscounts corrected by the human). Clean-checkout build, 7.1 (exit 0; nine of nine suite totals equal group by group, all Failed 0 and Errors 0, and the single skip in both logs, per review 23's Grep).
- Human rating: filled in 6.4 by W2-DOC4 from the frozen rating file (20 rows, single rater; the abstention column was corrected after rating, P16-46). The clean-checkout build is no longer pending (7.1). Separately, H-1c Billing was read (P16-43). Independent review (code-reviewer) of this revision is the next gate.
- Redaction note: this file's own scan for account ids, bucket names and ARNs is still by hand (W2-DOC3 session: `grep` and the equivalent shell search were refused, and the file was read back with the Read tool only). Review 14's Grep of the previous revision for the 12-digit, bucket-prefix and ARN-prefix patterns found 0 hits before this edit, and review 15 found one hit in this note (the literal tokens, since reworded) and none elsewhere; review 20's Grep for the 12-digit, bucket-prefix and ARN-prefix patterns found 0 hits on the W2-DOC5 revision; that is review evidence, separate from the author's scan; review 21's Grep found 0 hits; review 23's Grep found 0 hits on the W2-DOC6 revision.

---

## Sources used (all read-only)

Repository: `CLAUDE.md`, `docs/agents.md`, `.claude/agents/experiment-engineer.md`, `docs/agent-runs/phase-16/{plan.md, decisions.md, human-commands.md, morning.md, wave-2/reports/w2-doc1-experiment-engineer.md}`, `docs/agent-runs/phase-15/ratification.md`, `experiments/RESULTS.md`, `experiments/LIMITATIONS.md`, `experiments/benchmark/{BENCHMARK-MANIFEST-v1.json, README.md, questions-v1.jsonl, labels-v1.jsonl (lines 41 to 48 only)}`, `experiments/evaluation/er-README.md`, `experiments/g-retrieval/README.md`, `experiments/h-answers/README.md`, `scripts/README.md`.
Data (under `RUNS`): `graphchecks-a-export-2/{graph-check-report.json, run-info.json}`, `factorycounts-1/{factory-summary.txt, factory-counts.json (first 150 lines), run-info.json}`, `h4-diagnostic-{1,2,3,4}/{diagnostic-summary.json, run-manifest.json}`, `exp-G-20261119T053608Z-2e8943cf/{run-record.env, raw/retrieval/summary.json, raw/retrieval/run-manifest.json}`, `exp-H-20261119T053926Z-2e8943cf/{run-record.env, raw/answers/answer-summary.json, raw/answers/model-attempts-summary.json, raw/answers/model-calls.jsonl, raw/answers/run-manifest.json}`, `h-audit-2/{audit-summary.json, audit-answers.jsonl, run-manifest.json}`, `er-replay-v3-r3-1/evaluation-report.txt`, `erextras-1/{er-extras-report.txt, run-info.json}`; under `SCD`: `phase16-ci-on/{latency-ci-by-level.csv, latency-by-level.csv, SUMMARY.txt}`, `phase16-label-spotcheck.md`.
Added by W2-DOC5 (1.2, 2.3, 2.3a): `SCD/phase16-h8-provenance-sheet.md`, `SCD/phase16-h9-factory-sheet.md`, and the H-8 and H-9 rows of `docs/agent-runs/phase-16/human-commands.md` "Results recorded". Added by W2-RB15 (2.3a, 2.2): `factorycounts-1/factory-counts.json` lines 168, 262, 265 and 453-464, read for comparison only (the "first 150 lines" entry above is the earlier reader).
Added by W2-DOC7 (7.3, 8): `SCD/logs/phase16-h12-regen.log`, `SCD/logs/phase16-h12-compare.log`, a listing of `SCD/phase16-h12-regen`, a listing of `experiments/results`, and the H-12 row of `docs/agent-runs/phase-16/human-commands.md` "Results recorded".
Added by W2-DOC4 (section 6.4): `SCD/phase16-rating-filled.csv`, `SCD/phase16-rating-filled.as-entered.csv`, `SCD/phase16-rating-SHA256SUMS`, `RUNS/exp-H-20261119T053926Z-2e8943cf/raw/answers/rating-sample-key.json`, `docs/agent-runs/phase-16/decisions.md` (P16-46 row) and `docs/agent-runs/phase-16/plan.md` (lines 78 to 88, "Human rating rubric (D7)", read for the pointer and sample design in 6.4).
Added by W2-DOC6 (section 7.1, 7.3): `docs/agent-runs/phase-16/plan.md` (group 7, H-12 and H-13 rows, lines 76, 123 and 124), the 2026-11-19 H-13 and phase-gate rows of `docs/agent-runs/phase-16/human-commands.md`, and `SCD/logs/phase16-sbt-clean-check.log` and `SCD/logs/phase16-clean-checkout.log` (last 54 and 51 lines only; the other lines of both logs were not read by the author). Added by W2-RB17 from review 23 (a source separate from the author's tail reads): the Grep log evidence table in `docs/agent-runs/phase-16/wave-2/review-23-verdict.md` (group totals, skip, `[error]` and HEAD-line absence in both logs).
Not read: `exp-*/raw/retrieval/records.jsonl`, `raw/answers/answers.jsonl`, `rating-sample.csv`, `h-audit-2/audit-claims.jsonl`, `erextras-1/{er-extras-report.json, bootstrap-resamples.csv}`, `er-replay-v3-r3-1/{evaluation-report.json, decisions.jsonl}`, the `metrics/` directories, `entrypoint.log` files.
