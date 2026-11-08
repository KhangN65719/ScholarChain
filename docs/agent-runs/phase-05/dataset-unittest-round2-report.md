# Dataset unit-test round 2 report

Status: COMPLETE (diagnosis only; no code or test change).
Owner: dataset-engineer. Work order: Phase 5 dataset repair round 2.
No Python, network, sbt or commit. The only file written is this report. The log `target/p5-logs/v2-unittest-full.txt` was read, not modified.

## 1. Cause: confirmed

The orchestrator reading is correct, with one caveat on how it was confirmed.

- `datasets/dataset-manifest.yaml`, `subsets[0]` (`phase5-dblp-subset`), records `spec.sha256 = d649474f88b62d2a7cb701a453d8a486d46970e5a748441984e22d488df45b74`. The entry still carries the pre-fix state: `counts.works.seed = 1986`, the old `subset_rule` (no per-name definition), `files[dblp].rows = 3486`, `sha256 = 192f864e...`.
- `datasets/subset/subset-spec.json` now contains `per_seed_name_definition`, which I added in round 1. Any added key changes the file bytes, so its SHA-256 cannot equal the one recorded before the edit.
- `subsettool.validate_subsets` (subsettool.py:630-634) hashes `spec` first in the loop `("spec", "receipt", "enrichment_receipt")`. It raises at the first mismatch, so the log shows `spec SHA-256 mismatch` and says nothing about the receipts. The receipts and the DBLP file are stale as well, but the validator never got that far.
- Caveat: I could not compute the SHA-256 of the current spec. The sandbox hook refused `shasum`. The conclusion rests on the edit history and the validator's behavior, not on a digest comparison. The human can confirm with `!shasum -a 256 datasets/subset/subset-spec.json`. It will print something other than `d649474f...`.

This is the expected transitional state. The code is correct. The spec SHA check is doing its job and I did not touch it.

## 2. Per-failure check

| # | Test | Cause |
|---|---|---|
| 1 | `test_datasets.DatasetToolsTest.test_download_rejects_wrong_id_or_publisher_checksum` (test_datasets.py:278) | `datasets.validate()` on live manifest, spec mismatch |
| 2 | `...test_download_validates_and_reuses_a_single_bounded_record` (:245) | same |
| 3 | `...test_failed_fetch_does_not_publish_an_artifact` (:268) | same |
| 4 | `...test_manifest_links_the_same_works_and_researcher` (:176) | same |
| 5 | `...test_replay_is_bounded_and_idempotent` (:195) | same |
| 6 | `ManifestRejectionTest.test_rejection_cases_leave_the_checked_in_manifest_untouched` (:163) | same |
| 7 | `test_slicetool.CheckedInSliceTest.test_checked_in_manifest_validates_including_any_registered_slice` (:640) | same |
| 8 | `test_subsettool.CheckedInTest.test_checked_in_manifest_validates` (:570) | same |
| 9 | `test_datasets.DatasetToolsTest.test_sample_cannot_overwrite_source_fixtures` (FAIL, :241) | same, indirectly |

Item 9: the test runs `datasets.py sample ... --output <samples dir>` as a subprocess and expects the "outside the repository" refusal. The CLI calls `validate()` first, so it exits 1 with `dataset error: subset phase5-dblp-subset: spec SHA-256 mismatch` before it reaches the output-path check. The log line 143 shows exactly that stderr. Return code 1 matched, only the message differed. This is the same root cause. I found no different cause for any of the nine. The other 71 tests passed, including all five new `PerNameCapDefinitionTest` tests (80 ran, 9 not passing), which also corrects my round 1 note that the new tests were unexecuted.

## 3. Handling and commands

The unit suite cannot go green between the spec edit and `register`, because 8 tests validate the live checked-in manifest by design (that is what makes them checks of the checked-in state). Correct order is extract, enrich, register, validate, unit tests. Running the unit tests first only reproduces the same 9 failures.

Decision on code: leave unchanged. Pointing those tests at a fixture manifest would remove the tests' purpose (guarding the checked-in manifest) and would weaken coverage. Do not do it.

Commands, human, `!` prefix, repo root:

```
!python3 -B datasets/scripts/subsettool.py extract --cache /tmp/scholarchain-phase5-subset-cache --dblp-dump /Users/winnguyen/scholarchain-data/dblp/dblp.xml.gz --dblp-dtd /Users/winnguyen/scholarchain-data/dblp/dblp.dtd --dblp-release 2026-11-05 --dblp-sha256 356634eef5696aef7ba4d07d864257ddcacae55c95f64a755570801df0a82fa1 --dblp-bytes 1116868681
```
Expected: `dump verified`; `works:` seed at most 1986 (about 1970 to 1986), coauthor 1500, scan `end-of-dump` twice; new `dblp-subset.xml` SHA differs from `192f864e...`; every `seeds.*.kept` at most 200.

```
!python3 -B datasets/scripts/subsettool.py enrich --cache /tmp/scholarchain-phase5-subset-cache
```
Expected: `live requests N/500` with N well under 500, `failures 0`.

```
!python3 -B datasets/scripts/subsettool.py register
```
Expected: `registered phase5-dblp-subset: dblp ... rows <sha>, openalex ..., crossref ..., orcid ...`.

```
!python3 -B datasets/scripts/datasets.py validate
```
Expected: success, no `SHA-256 mismatch`, no `per-name cap` error.

```
!python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'
```
Expected: `Ran 80 tests`, `OK`. The count is from the log. The manifest is rewritten by `register`, so the 8 manifest tests and the CLI test should pass. The tests that print `registered phase5-...` and `live requests` lines are test output, not errors.

If `extract`, `enrich` or `register` themselves fail with `spec SHA-256 mismatch`, that is a real ordering bug in my code (a command validating the manifest before it rewrites it). I did not read those code paths for this, so treat that as unverified. Send the output back to me.

## 4. ResourceWarnings

Log lines 7-10: `ResourceWarning: Implicitly cleaning up <HTTPError 404: 'nf'>` and `<HTTPError 403: 'no'>`, raised from `tempfile.py:538` on Python 3.15. They come from tests that build `urllib.error.HTTPError` objects with a temp-file body and never close them. They are test-only noise, do not affect any assertion or production path, and appeared in a run that otherwise passed those tests. I did not locate the exact test lines (grep was blocked by the hook), so I left them alone. A later fix would be to `close()` the error object in the fake-opener tests; not worth a round now.

## Unverified

- Current spec digest (not computed; see section 1).
- Behavior of `extract`, `enrich` and `register` with a stale manifest in place (code not re-read).
- Post-run counts and request numbers are estimates.
- Test locations of the ResourceWarnings.

## Files modified

- `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-05/dataset-unittest-round2-report.md` (this report). No `datasets/**` file changed.

Next gate: human runs the sequence above; independent review of the round 1 diff.
