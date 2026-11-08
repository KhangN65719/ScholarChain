# Phase 05 human decisions

Recorded by the orchestrator from the human architect's messages. Date: 2026-11-07.

## Matcher findings (from the entity-resolution-engineer report)

1. **CJK / non-Latin folding defect (hadoop-engineer).** Implement F2 (phase-04/decisions.md): keep original characters in stored records and add a separate folded or transliterated matching key so non-Latin names get candidates. Must land before the gold-set sample is drawn.
2. **Source-local IDs in Hadoop conflict lists.** Noted only. No change. Hadoop's `exactIdentifierConflicts` includes source-local namespaces (`source-record-id`, `crossref-family`) that always differ; entity resolution ignores them and recomputes from the records.
3. **`MatchCandidate` moves into core (domain-engineer).** Hadoop and entity-resolution both use the core type. No wire change.
4. **Conflict policy.** Only ORCID disagreements (authoritative) produce CONFLICT. Differing OpenAlex or DBLP author IDs are features, not conflicts. The policy version is bumped (from `scholarchain.er.policy/1.0.0`).

## Clarification of decision 4 (human, 2026-11-07)

- **Researchers:** only ORCID disagreements produce CONFLICT. Differing OpenAlex or DBLP author IDs are features.
- **Works:** a DOI mismatch stays CONFLICT, because DOI is authoritative for works. Both rules are recorded in the policy notes.
- **Not decided:** other work-level identifier disagreements (OpenAlex work ID, Semantic Scholar paper ID, repository URL, arXiv ID) keep their current behavior (CONFLICT, per the ER report) until the human rules on them. Flagged as open.

## Gold labeling attempt 1 rejected as evidence of human review (human, 2026-11-07)
- Attempt 1: `/Users/winnguyen/scholarchain-data/gold/phase5/gold.jsonl`, labeled with the Codex suggestion CSV (`scholarchain-codex-suggestions.csv`) loaded. Human-reported totals: 230 of 230 labeled (same 95, different 131, unsure 4), suggestion on all 230, 64 changed from the suggestion.
- The human ran the validator: the first pass took 6.5 minutes, median gap between labels 0.7 s, 208 gaps under 2 s (numbers as reported by the human; the validator log was not read by the orchestrator). The human judged that this does not support "human-reviewed".
- Decision: record attempt 1 as it happened and keep its files unchanged (`pairs.jsonl`, `gold.jsonl`, `private/*`, the suggestions CSV). It is not used for the evaluation.
- Attempt 2 (revised decision, supersedes the earlier "full blind pass" note in this same section): a blind relabel of a SUBSET only, namely the union of every hard-common-name pair and every pair whose Codex suggestion was "unsure", without suggestions, at a deliberate pace, into a new file `/Users/winnguyen/scholarchain-data/gold/phase5/gold-v2.jsonl` against the same `pairs.jsonl`. In gold-v2 the subset pairs carry the pass-2 label and every other pair carries its pass-1 label, each row marked with its pass. The remaining pairs are NOT re-reviewed; evidence must say which labels came from which pass and give the timing of both passes. The gold-set evaluation will use gold-v2 and must report pass-2 and carried pass-1 rows separately. The evaluation has NOT been run on attempt 1.
- Open (human check): about 40 records in `pairs.jsonl` carry affiliations. Whether those strings come from Crossref free text (excluded by the manifest's privacy note) or from OpenAlex institution records is being verified by entity-resolution-engineer.

## Pass 3: blind re-review of all carried pass-1 pairs (human, 2026-11-07)
- Trigger: the gold-v2 evaluation (`gold-v2-evaluation-record.md`) listed false negatives whose pass-1 labels look like labeling errors (for example clear-non-match pairs with entirely different names labeled "same"). To avoid correcting only the matcher-flagged pairs, the human will blind re-review ALL 153 carried pass-1 pairs ("pass 3") without suggestions or pass-1 labels shown, into a new file `/Users/winnguyen/scholarchain-data/gold/phase5/gold-v3.jsonl` that keeps the 77 pass-2 labels.
- `gold.jsonl` (pass 1) and `gold-v2.jsonl` stay unchanged. The v2 error analysis and suspect list stay as the record of v2. The evaluation is rerun on gold-v3 and reported alongside v2.
- No threshold, rule or policy change from any of this. The gold archive to `docs/agent-runs/phase-05/gold/` is on hold until v3 is done.

## gold-v3 is the final gold set; known limitation (human, 2026-11-07)
- `gold-v3.jsonl` (77 pass-2 labels carried from gold-v2 plus 153 pass-3 blind re-review labels) is the final gold set. Evaluation recorded in `gold-v3-evaluation-record.md`.
- Disclosed limitation: pass 3 was fast (median gap 0.6 s, 143 of 152 gaps under 2 s) and the human applied shared-coauthor evidence even when names were clearly different, so the 12 clear-non-match pairs labeled "same" are likely labeling errors, not matcher misses. They are listed in the evaluation record. The labels are not corrected after seeing matcher output; the numbers stand as recorded (recall 0.802; descriptive bound 0.917 if all 12 were "different").
- No threshold or rule change. Thresholds remain provisional; calibration needs a deliberate re-review and a fresh held-out sample.

## Gold archive (human, 2026-11-07)
- The gold files are archived byte-identical under `docs/agent-runs/phase-05/gold/` (plus `gold-support/` for the suggestions CSV and selected logs) with `GOLD-MANIFEST.json`; `diff -rq` against `/Users/winnguyen/scholarchain-data/gold/phase5` was empty.
- The repository is private: keep the reviewer handle `khang-nguyen` and the local absolute paths as they are. Revisit before any public release.

## Rulings on review round 1 (human, 2026-11-07; see review-verdict-round1.md)
1. Finding 1 (same-name coauthors merged across sources): approved. Matcher fix: a same-work rule keyed on a shared DOI plus differing author position, with a regression test on a SYNTHETIC fixture (not gold pairs). No threshold changes. After repair: give the commands to rerun the v5 pipeline and the gold-v3 evaluation, and report before/after side by side with labels unchanged.
2. Finding 4: the agree-but-contradicts CONFLICT rung is accepted (a strong identifier agrees while content contradicts it: researcher ORCID agreement with family-name similarity below 0.60; work DOI agreement with title similarity below 0.30). Record it in the policy notes. Researcher disagreement on ORCID still produces CONFLICT; differing OpenAlex or DBLP author IDs remain features.
3. Finding 3, gold-review gate: met with a disclosed limitation. 230 blind human labels by the human reviewer; only the 77 pass-2 labels were made at a deliberate pace; pass 3 was fast (median gap 0.6 s). Finding 2 disclosure: 5,766 same-name agreeing-context pairs were excluded from the export (`includeAgreeingContext:false`), and the exact-id stratum is selected by the same shared-identifier rule the exact-id path accepts on; therefore pooled precision 1.000 does not measure the probabilistic or deterministic-bibliographic paths.
4. Finding 5: hadoop-engineer drops the Crossref free-text affiliation read now.
5. One batched repair round for findings 6-12 and 16, the missing reports and the stale notes; code-reviewer re-reviews only the changes. Do not commit.

## Rulings on re-review round 2 (human, 2026-11-07; see review-verdict-round2.md)
1. Finding 1: extend the same-work distinct-mention rule to a shared arXiv ID, the same as DOI, with a synthetic regression test (no gold pairs).
2. Finding 2: when an ORCID or a content-supported aggregator ID agrees while the position rule says the two mentions are distinct mentions of the same work, the outcome is CONFLICT, not ACCEPTED. Record it in the policy notes under the agree-but-contradicts rung. No threshold changes.
3. One batched repair round with these two plus the other nine findings (3 to 11); then the gate and evaluation commands; then code-reviewer re-reviews only what changed. Do not commit.

## Ruling on re-review round 3 (human, 2026-11-07; see review-verdict-round3.md)
- Finding 1: an agreeing ORCID on two slots of the SAME work record is CONFLICT regardless of whether the work has a DOI or an arXiv id. Tests for both the with-id and no-id cases; test-engineer writes an independent regression test; record it in the policy notes. No threshold changes.
- Last repair round: this fix; the Matcher comment, dead-code (`distinctAuthorsOfOneDoi`) and "reason" wording cleanup (finding 2); full resends of the test-engineer and hadoop-engineer repair-round-2 reports. Then the gate and evaluation commands, then code-reviewer re-reviews only what changed. Do not commit.

## Deferred rulings (human, 2026-11-07)
Both open rulings are deferred to a later phase; the current behaviour stays and is recorded in open-items.md:
1. Non-DOI work-level identifier disagreements (OpenAlex work ID, Semantic Scholar paper ID, repository URL, arXiv ID): they keep producing CONFLICT (policy notes, `Policy.scala`), unruled.
2. An agreeing aggregator ID (OpenAlex, DBLP, Semantic Scholar author ID) on two slots of ONE work record with no DOI and no arXiv id: stays REJECTED via `rule.same-work-distinct-mention` (with a DOI or arXiv id it is already CONFLICT); the ruling that named ORCID only is not extended.

## Standing instructions
- Do not commit.
- Keep test-engineer and dataset-engineer going.

## Earlier in the phase
- DBLP live search is behind an Anubis bot challenge and is not used. Candidates come from the human-downloaded official dump (SHA-256 `356634eef5696aef7ba4d07d864257ddcacae55c95f64a755570801df0a82fa1`, 1116868681 bytes).
- The 3-paper overlap slice is abandoned. Replacement: a bounded DBLP subset (common-name authors plus coauthors, documented cap), Phase 4 run on it, about 230 pairs sampled from the actual candidate output, with OpenAlex, ORCID and Crossref used only to enrich (see `dataset-slice-v2-work-order.md`).
