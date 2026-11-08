# Dataset per-name cap repair report

Status: COMPLETE (code and docs); validation pending human `!` runs.
Owner: dataset-engineer. Work order: Phase 5 dataset repair (per-name cap).
Python was not run. No network, no sbt, no commit, nothing outside `datasets/**` edited (this report is the requested exception).

## 1. Diagnosis

The hint is half right.

Confirmed (from `datasets/subset/subset-receipt.json`): the extractor caps per-seed heaps, not per-name works in the file.

| Seed name | `kept` in receipt | Cap | Over by |
|---|---|---|---|
| Wei Wang | 206 | 200 | 6 |
| Yang Liu | 205 | 200 | 5 |
| Jun Wang | 202 | 200 | 2 |
| Lei Zhang | 201 | 200 | 1 |
| other six | 200 | 200 | 0 |

Sum of `kept` = 2014; seed works in the file = 1986; the 28 extra incidences are works mentioning two seed names. The ten heaps hold 2000 incidences, so 14 more arrived through another seed's heap (the overage 6+5+2+1 = 14).

Mechanism in `select_works` (old code): each seed has a `Smallest(200)` heap fed by every work mentioning that seed; the file is the union of the heaps. A work in Wei Wang's heap that also mentions Yang Liu is in the file and counts for Yang Liu, even if Yang Liu's own heap evicted it.

Refuted: coauthor-pass works. Pass two skips any record with a seed-name author (`if any(author_key(n) in seeds for n in names): continue`), so no coauthor work can mention a seed. The validator puts such records in `non_seed` and never counts them. The 1500 coauthor works are not part of the cause.

Definitions before the fix:
- Extractor cap: size of each per-seed heap (works offered for that name, up to 200).
- Receipt `kept`: works in the final seed set that mention the name (the file-level count; already exceeded 200, so the receipt recorded the violation).
- Validator: works in the file that mention the name (same as receipt `kept`).
Receipt and validator already agreed. The extractor was the odd one out.

## 2. Fix (one definition, cap stays 200)

Definition: for each seed name, the number of works in `dblp-subset.xml` that mention it (homonym suffix ignored). A two-seed work counts once for each name. Coauthor works mention no seed and count for none.

Chosen because it is the only definition the validator can check from the file alone. A heap-membership definition cannot be re-derived from the subset.

Changes in `datasets/scripts/subsettool.py`:
- `seed_names_in(names, seeds)` and `count_per_name(matches, spec_seeds)`: shared by extractor `kept` and validator.
- `trim_to_name_cap(pool, cap)`: after the per-name heaps (still bounded memory), admit the union in (SHA-256 rank, key) order, skipping any work that would push a mentioned name past the cap. Deterministic, independent of order. When no work is shared the result equals the old one.
- Receipt: new `seeds.<name>.dropped_by_shared_name_cap`.
- Validator error now names the cap and the offending counts.
- `subset_rule` text in the manifest entry states the definition.

Documented in `datasets/subset/subset-spec.json` (new `per_seed_name_definition`, cap value unchanged) and `datasets/README.md`.

Tradeoff: a name can end below 200 because a skipped work may have been the reason another name's evicted work was not re-admitted. Accepted: deterministic, bounded, no cap loosening.

## 3. Gate item to test (written alongside the edits; I listed them after starting, not before)

All in `datasets/scripts/test_subsettool.py`.

| Gate item | Test |
|---|---|
| Trim is deterministic and order-independent | `PerNameCapDefinitionTest.test_trim_admits_in_rank_order_whatever_the_insertion_order` |
| Two-seed work counts once per name; no-seed work counts for none | `test_count_counts_a_two_seed_work_once_for_each_name_and_no_seed_work_for_none` |
| Extractor, receipt `kept` and validator agree with a two-seed work, across 12 salts; cap never exceeded; trim fires for some salt; skipped seed works are not re-admitted as coauthor works | `test_extractor_receipt_and_validator_agree_on_works_shared_between_seeds` |
| Validator counts shared works and fails at the cap, passes at the true count | `test_validator_counts_every_work_in_the_file_that_mentions_a_seed` |
| A coauthor-pass-labelled work that mentions a seed is counted as a seed work | `test_a_coauthor_pass_work_that_mentions_a_seed_is_counted_as_a_seed_work` |
| Spec keeps cap 200 and states the definition | `CheckedInTest.test_checked_in_spec_states_every_cap` |
| Cap still binds and corruption still fails (unchanged) | existing `test_per_name_cap_binds_...`, `test_subset_rules_are_rechecked_from_the_file` |

No test was weakened or removed.

## 4. Rerun scope

Full re-extract is required, then enrich and register. Reasons:
- The extractor definition changed, so the selected file changes (about 14 seed works removed; the coauthor name set and so the coauthor works can shift). The old 3486-work file violates the new definition, and the validator cannot accept it.
- Editing `subset-spec.json` changes its SHA-256. The extract cache key includes the spec digest (so a rescan happens), and both receipts record `spec_sha256`, so enrich must also be rerun.
- Validator-only or register-only reruns cannot work: the existing file is the thing that fails.

Enrich should reuse the cache (`/tmp/scholarchain-phase5-subset-cache`). Crossref and ORCID responses are cached per URL. OpenAlex batches are URLs over 25-DOI groups, so batches after the first changed DOI shift and are likely cache misses: expect roughly up to 16 new live requests, plus a few Crossref/ORCID lookups for any new coauthor DOIs, well under the 500 bound. This relies on the Fetcher cache keying on URL; I did not read that code path, so it is unverified.

## 5. Exact commands (human, `!` prefix, from the repository root)

```
!python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'
```
Expected: all tests pass (75 before, 80 now: 5 new tests in `PerNameCapDefinitionTest`; the count is my arithmetic, not observed).

```
!python3 -B datasets/scripts/subsettool.py extract --cache /tmp/scholarchain-phase5-subset-cache --dblp-dump /Users/winnguyen/scholarchain-data/dblp/dblp.xml.gz --dblp-dtd /Users/winnguyen/scholarchain-data/dblp/dblp.dtd --dblp-release 2026-11-05 --dblp-sha256 356634eef5696aef7ba4d07d864257ddcacae55c95f64a755570801df0a82fa1 --dblp-bytes 1116868681
```
Expected: `dump verified`, then `works:` with seed about 1970 to 1986 (fewer than 1986), coauthor 1500, total about 3470 to 3486, scan `end-of-dump` twice. The SHA of `dblp-subset.xml` will differ from `192f864e...`. Receipt `seeds.*.kept` all at most 200.

```
!python3 -B datasets/scripts/subsettool.py enrich --cache /tmp/scholarchain-phase5-subset-cache
```
Expected: `live requests N/500` with N small, `failures 0`.

```
!python3 -B datasets/scripts/subsettool.py register
!python3 -B datasets/scripts/datasets.py validate
```
Expected: `registered phase5-dblp-subset: ...`, then validate succeeds with no `per-name cap` error.

Optional measurement of the current (old) file, to confirm the diagnosis independently of the receipt, before re-extracting:
```
!python3 -B -c "import sys; sys.path.insert(0,'datasets/scripts'); import json, subsettool as s; sp=json.load(open('datasets/subset/subset-spec.json')); seeds={s.author_key(n):n for n in sp['seeds']}; r=s.subset_records(open('datasets/subset/dblp-subset.xml','rb').read()); print(s.count_per_name((s.seed_names_in([a['name'] for a in s.st.dblp_authors(t)],seeds) for _,_,t in r), sp['seeds']))"
```
Expected: the four names above 200 (Wei Wang 206, Yang Liu 205, Jun Wang 202, Lei Zhang 201), matching the receipt. Note this one-liner needs the new `count_per_name`, which now exists in the edited file.

## 6. Unverified

- No Python was run: new and existing tests, and the syntax of the edits, are unexecuted. Treat them as written, not passing, until the human unit-test run.
- The new multi-salt test depends on hash ranks; it asserts that trim fires for at least one of 12 salts (failure probability about 4^-12 per my estimate, not measured).
- Post-fix work counts and the enrich request count are estimates.
- Fetcher cache keying and offline behavior were not read.
- The `.claude` hooks blocked shell pipelines and `|` in grep, so I used file reads only.

## Files modified

- `/Users/winnguyen/ScholarChain/datasets/scripts/subsettool.py`
- `/Users/winnguyen/ScholarChain/datasets/scripts/test_subsettool.py`
- `/Users/winnguyen/ScholarChain/datasets/subset/subset-spec.json`
- `/Users/winnguyen/ScholarChain/datasets/README.md`
- `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-05/dataset-per-name-cap-report.md` (this report)

Next gate: human runs the commands above; independent review (code-reviewer) of the diff.
