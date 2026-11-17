# R6 report, dataset-engineer (received 2026-11-17; preserved as received; author: dataset-engineer subagent)

Status: COMPLETE for the edits; nothing executed (guard refuses python).

## Changes
- `datasets/scripts/chunkset.py`: `inside_repository` compares whole path components after Unicode NFC normalization and `casefold` (not `resolve()` plus `is_relative_to`, which misses a differently cased spelling on macOS); a sibling such as `ScholarChain-data` is not treated as inside. All guarded paths use it: `external_dir` (`chunks`, `validate`, `replay`), the recorded-path check in `check_chunk_files`, `replay --output`. Trade-off: a case-only difference is refused even on a case-sensitive filesystem (safe direction, documented). `validate` without `--export-dir` now ends `chunk-file-only` instead of `repo-only` (other references to `repo-only` not searched).
- `datasets/scripts/test_chunkset.py` (`ExternalChunksDirTest`): `test_differently_cased_repository_path_is_refused` (swapcase, upper, lower roots with empty, lowercase and mixed tails; refused by `external_dir`, `build_chunks`, `check_chunk_files`, `replay`; no manifest written); `test_replay_output_with_different_case_is_refused`; `test_sibling_with_a_shared_prefix_is_not_inside_the_repository`. String transforms only; filesystem-independent. No assertion weakened.

## Human commands
```
set -o pipefail; python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py' 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-r6-1.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/chunkset.py validate --chunks-dir /Users/winnguyen/scholarchain-data/phase15-chunkset-c 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-r6-2.log | tail -n 60; echo "exit: $?"
```
Expected: 108 tests `OK`, `RegisteredFilesTest` not skipped; validate prints `OK snapshot scholarchain-s-20261107-10dd7f34121b chunks 2000 1ccaefcb...220c92 chunk-file-only`, exit 0.

## Human execution (2026-11-17, recorded by the orchestrator)
- Unit tests: `Ran 108 tests in 1.700s` `OK`, exit 0 (log `phase15-r6-1.log`).
- Validate without export: `OK snapshot scholarchain-s-20261107-10dd7f34121b chunks 2000 1ccaefcb...220c92 chunk-file-only`, exit 0 (log `phase15-r6-2.log`).

## Risks (as reported)
- The agent states that `echo "exit: $?"` reports `tail`'s status. Orchestrator note: with `set -o pipefail` the pipeline status is the rightmost non-zero status, so a failing python run does show a non-zero exit (observed: R3 step 5 printed `exit: 1`). The claim does not hold under the common-rules form.
- Swapcase test assumes `REPO_ROOT` contains letters (it does).
