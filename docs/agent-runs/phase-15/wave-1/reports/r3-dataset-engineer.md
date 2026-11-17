# R3 report, dataset-engineer (received 2026-11-17; preserved with commands as received; author: dataset-engineer subagent)

Status: COMPLETE for the edits; nothing executed (guard refuses python).

## Changes (`datasets/scripts/`)
- `chunkset.py`: `chunks`, `validate`, `replay` take a required `--chunks-dir`; `chunks.jsonl` read and written only there; `external_dir()` refuses the repository root, any path inside it, and symlinks resolving into it; `replay --output` uses the same check; `build_chunks` passes `CHUNK_COUNT` explicitly. `datasets/tier-s/chunks-c/chunkset-manifest.json` stays in the repo with `output.path` as the absolute external path and `output.location` "outside the repository, not tracked in git (P15-14)"; sha256, rows, bytes, rule, composition unchanged in form; `validate` rejects a relative or in-repo recorded path; recorded `validation_commands` name `--chunks-dir`.
- `test_chunkset.py`: new `ExternalChunksDirTest` (6 tests); `RegisteredFilesTest` calls `validate(chunks_dir)` from `SCHOLARCHAIN_CHUNKS_DIR`, default `/Users/winnguyen/scholarchain-data/phase15-chunkset-c`, skipping if absent.

| Change | Test |
|---|---|
| chunks writes outside, records non-repo path | `test_build_writes_chunks_outside_and_records_a_non_repo_path` |
| validate and replay read the external file | `test_validate_and_replay_read_the_external_file` |
| tampered external file rejected | `test_tampered_external_chunk_file_is_rejected` |
| in-repo `--chunks-dir` refused by every command, repo root refused | `test_chunks_dir_inside_the_repository_is_refused_by_every_command` |
| in-repo path in manifest rejected | `test_repository_path_recorded_in_the_manifest_is_rejected` |
| in-repo `replay --output` refused | `test_replay_output_inside_the_repository_is_refused` |
| real manifest and file validate (3,245,083 bytes, 4,219 rows) | `RegisteredFilesTest::test_repository_registration_validates` |

## Human commands (from /Users/winnguyen/ScholarChain)
```
mkdir -p /Users/winnguyen/scholarchain-data/phase15-chunkset-c
mv datasets/tier-s/chunks-c/chunks.jsonl /Users/winnguyen/scholarchain-data/phase15-chunkset-c/chunks.jsonl
set -o pipefail; shasum -a 256 /Users/winnguyen/scholarchain-data/phase15-chunkset-c/chunks.jsonl 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-r3-1.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/chunkset.py chunks --export-dir /Users/winnguyen/scholarchain-data/rehearsal/s-export --chunks-dir /Users/winnguyen/scholarchain-data/phase15-chunkset-c --built-on 2026-11-16 --pipeline-run-id run-v1-192de0fd-1442-4508-9065-09c44775643b 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-r3-2.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/chunkset.py validate --chunks-dir /Users/winnguyen/scholarchain-data/phase15-chunkset-c --export-dir /Users/winnguyen/scholarchain-data/rehearsal/s-export 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-r3-3.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/chunkset.py replay --chunks-dir /Users/winnguyen/scholarchain-data/phase15-chunkset-c --export-dir /Users/winnguyen/scholarchain-data/rehearsal/s-export --output /Users/winnguyen/scholarchain-data/phase15-chunk-replay 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-r3-4.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/chunkset.py validate --chunks-dir /Users/winnguyen/ScholarChain/datasets/tier-s/chunks-c 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-r3-5.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/datasets.py validate 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-r3-6.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py' 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-r3-7.log | tail -n 60; echo "exit: $?"
```
Expected: step 1 hash `1ccaefcb...220c92`; step 2 `chunks 2000 3179893 1ccaefcb...220c92` (rebuild, manifest rewritten with new path); step 3 `OK snapshot ... chunks 2000 ... export-replay`; step 4 `REPLAY_OK rows 2000 ...`; step 5 negative: exit 1 with `FAIL: --chunks-dir ... is inside the repository`; step 6 validates; step 7 all pass, 105 or more tests, repository test not skipped.

## Human execution (2026-11-17, recorded by the orchestrator; logs `phase15-r3-1..7.log`)
Move: `mkdir -p` plus `mv -n`; `ls` shows `chunks.jsonl` 3,179,893 bytes in `/Users/winnguyen/scholarchain-data/phase15-chunkset-c` and only `chunkset-manifest.json` in `datasets/tier-s/chunks-c`.
| Step | Output | Exit |
|---|---|---|
| 1 shasum | `1ccaefcb...220c92` | 0 |
| 2 chunks | `chunks 2000 3179893 1ccaefcb...220c92` | 0 |
| 3 validate | `OK snapshot scholarchain-s-20261107-10dd7f34121b chunks 2000 1ccaefcb...220c92 export-replay` | 0 |
| 4 replay | `REPLAY_OK rows 2000 sha256 1ccaefcb...220c92` | 0 |
| 5 in-repo negative | `FAIL: --chunks-dir /Users/winnguyen/ScholarChain/datasets/tier-s/chunks-c is inside the repository; the chunk file must live outside it` | 1 (expected) |
| 6 datasets.py validate | `Validated manifest, sample checksums and row counts: dblp, openalex, orcid, crossref` | 0 |
| 7 unittest | `Ran 105 tests in 1.727s` `OK` | 0 |

## Risks
- Manifest records the absolute path; other machines pass their own `--chunks-dir`.
- Other references to `tier-s/chunks-c/chunks.jsonl` outside `datasets/` not searched (guard).
- `snapshot-s.json` unchanged. Experiment C `MAX_TASKS=5000` still open for experiment-engineer.
