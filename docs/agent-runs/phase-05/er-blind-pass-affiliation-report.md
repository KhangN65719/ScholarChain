# entity-resolution-engineer report: blind subset relabel (pass 2), affiliation provenance

Author: entity-resolution-engineer (archived by the orchestrator, condensed). Date: 2026-11-07. Edits only under modules/entity-resolution/**; no commit, no LLM. The validator, labeler and evaluation were NOT run on any real gold file; attempt 1's files were not opened for writing.

## Subset counts (computed by the new selection code on the real pairs, strata key and Codex CSV, read-only)
Hard-common-name 77; Codex "unsure" 49; overlap 49 (every Codex-unsure pair is hard-common-name); union 77; carried from pass 1: 153. Strata 77/76/77. Codex suggestions: different 99, unsure 49, same 82, 0 unknown pair_ids.

## Affiliation provenance: verdict
No Crossref free text is in pairs.jsonl. 69 of 460 sides carry affiliations (59 researcher mentions, 10 work sides; 68 pairs), more than the ~40 the human counted. By source: openalex 69, dblp 0, crossref 0. 75 distinct strings, all present among OpenAlex `institutions[].display_name` values (datasets/subset/openalex.jsonl, 2343 entries, 469 distinct); none in Crossref. crossref.jsonl (251 rows) author keys are ORCID, family, given, name, sequence: no `affiliation[]`. Examples (openalex): "China United Network Communications Group (China)", "Shandong University of Science and Technology", "The University of Kitakyushu". Code path: PairSide.of -> SideRecord.fromMention / fromWork; OpenAlex SourceNormalizers.openAlex lines 142-143; DBLP parser sets empty.
Manifest wording (Crossref privacy_notes): "Exclude abstracts, full text links, free-text affiliations and contact details from checked-in fixture." Subset privacy_notes: "Enrichment rows are allowlisted projections: no abstracts, contact fields or free-text affiliations from Crossref; ORCID rows keep public name and iD only."
Latent risk (routed to hadoop-engineer, not yet done): `SourceNormalizers.crossref` lines 424-425 read `author[].affiliation[].name` (free text); nothing reaches pairs.jsonl only because the allowlisted Crossref projection omits it.

## Built
- `SubsetSelection` (union, counts, SHA-256 shuffle order independent of stratum/input order; strata key read only here).
- `LabelServer --only <subset> --carry-from <gold.jsonl>` (must go together, exclude --suggestions); gold-v2 holds all 230 pairs in pairs.jsonl order, saved atomically after each answer; carried rows = pass-1 line with every original key plus `pass:1`, `carried_from`, `carried_from_sha256`; relabeled rows `pass:2`, human method, reviewer, reviewedAt, null suggestion fields, `changed_from_suggestion:false`, `pass1_label` (in file only, never in UI); no `review_started`. Guards: refuses --gold equal to the carry file (incl. ./ and x/../), refuses an attempt-1 file as gold-v2, refuses a carry file that already has pass fields, refuses a no-suggestion run on a gold file holding suggestions. Page: "Pair k of 77 (subset)", no suggestion/stratum/pass-1 label.
- `GoldValidator --mode v2 --pass1 --subset [--strata-key --suggestions]`: pass-field, pass1-file, pass2-is-subset, pass2-blind, pass1-carried, subset-recomputed; totals optional; review-time block printed first separately per pass; label counts by pass; "NOT RE-REVIEWED: 153 pairs carry first-pass labels ... only 77 pairs were reviewed blind"; descriptive COMPARE block (agreement, Cohen's kappa over same/different, disagreements by pair_id; strata only with key), never changes pass/fail.
- `GoldEvaluation`/`EvaluationRun`: `== by pass` section (pass 1 "CARRIED ... NOT re-reviewed", pass 2 "relabeled BLIND"); files without a pass field report as before. Evaluation not run.
- Tests: BlindPassSuite 35 (0 failures; read from target/test-reports), PassSplitEvaluationSuite (new; report not read separately). Earlier 6 failures were test bugs, fixed; no existing test edited, weakened or deleted.

## Agent's `sbt check`
`Passed: Total 188, Failed 0, Errors 0` and `[success]` (the line the agent read; inline output truncated; full text saved by the agent under its tool-results path). Not the recorded gate.

## Risks / manual action
- `modules/entity-resolution/src/test/scala/scholarchain/er/TmpAffiliationAudit.scala` is a leftover diagnostic, neutralized (skipped unless SCHOLARCHAIN_PHASE5_DIR and SCHOLARCHAIN_PHASE5_SUGGESTIONS are set, writes nothing); to be removed by someone able to delete files.
- Carried-row equality compares parsed JSON values per key, not raw bytes.
- Memory/anchoring: all 77 were seen earlier, 49 with a visible Codex "unsure"; agreement and kappa partly measure recall of the earlier answer and overstate stability; disagreements are the informative part.
- Sbt project name and main class names inferred from compile output.
