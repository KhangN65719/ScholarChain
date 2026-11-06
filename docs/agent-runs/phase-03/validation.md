# Phase 3 verification

## Baseline

- Initial worktree was clean on main, with HEAD and main both at `efcd46bf55b95b4d62ffef59b8556cd886097216`.
- `git merge-base --is-ancestor 32ebc6d2ac21f1f351577b38ba27f9cfb355d259 efcd46b` exited 0. The sole change between the accepted build and baseline is the Phase 2 human ratification record.
- `/private/tmp/scholarchain-phase2/sbt check` exited 2 on an optional sbt Unix socket permission error.
- `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false /private/tmp/scholarchain-phase2/sbt check` exited 0 on the baseline with sbt 1.11.7 and Temurin 21.0.13. This is a baseline check. Candidate verification follows below.

## Candidate

- `SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false /private/tmp/scholarchain-phase2/sbt check` exited 0. MUnit reported 17 passed, 0 failed, 0 errors across ConfigSuite and ContractSuite.
- `python3 -B datasets/scripts/datasets.py validate` exited 0 for DBLP, OpenAlex, ORCID and Crossref, including fixture SHA-256 and row counts.
- `python3 -B datasets/scripts/datasets.py sample --max-rows 1 --output "$sample_run"` exited 0 twice with a fresh temporary output directory. SHA-256 comparison via `shasum -a 256 "$sample_run"/*` matched all four outputs and their four provenance sidecars on replay. A zero row limit was rejected.

| Dataset | Rows | Output bytes | Output SHA-256 |
| --- | ---: | ---: | --- |
| DBLP | 1 | 332 | fdab8bb8d6acf0495d8c0ce529ab2f1ff5d67d192a0f7710e34236571e198edb |
| OpenAlex | 1 | 906 | 843de1a18456f50c504f6d93d38c6a0b36961fdcf0949d452fc17dcee5fc6128 |
| ORCID | 1 | 196 | faf3c9303a2791718057d3482922a169f95936f4d7587ea6a0a99a6ca9e30c3c |
| Crossref | 1 | 274 | ea3ba7ee6dfd4ecd9f303ad6b0bba16d69337f89e00a1dabd98f424a69a2b849 |

- `python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py'` exited 0 with six passing dataset-tool tests before independent review repairs.

## Post-repair candidate

- The orchestrator reran the same pinned `sbt check` command. Exit 0 at 7:02:21 p.m. Chicago time. 18 tests passed, 0 failures and 0 errors, including the Unicode regression.
- The orchestrator reran manifest validation, two one-row-per-source local replays and SHA-256 comparison of all eight outputs. All exited 0, and output hashes matched the table above.
- The orchestrator reran the dataset-tool test command. All 7 tests passed, including direct and symlink-aliased output path protection. `git diff --check` exited 0.

## Publisher response observations reported by dataset-engineer

- [OpenAlex machine-readable inventory](https://openalex.s3.amazonaws.com/data/jsonl/manifest.json) returned 671,964 bytes, SHA-256 `2b3d611b3ec93912b7b655ee139310936048a689c0f3508052a73af03a6c453f`. Relevant JSON was `{"date":"2026-10-14","format":"jsonl","meta":{"record_count":586176148,"content_length":755513952946}}`. The response hash identifies that inventory, separate from its listed data objects.
- [ORCID version-specific publisher JSON](https://api.figshare.com/v2/articles/34697766/versions/1) returned 11,395 bytes, SHA-256 `3c605c34abe43d91f842e552a66eab04eac8f40f01d77256e68fb39a46a4acbe`, release DOI `10.23640/07243.34697766.v1`. All 12 manifest filenames and digests matched both the supplied and computed MD5 fields in the publisher JSON.
- The 3,697,724-byte Crossref torrent metadata was fetched within a fixed cap. The exact bencoded info dictionary SHA-1 was `b5ee0e102689b3e67023dd024694c0f5f124646f`.
- DBLP's dated XML snapshot publisher sidecar matched the manifest. The independently retrieved DTD had MD5 `96e4283e71886dec2173718957293648`.
