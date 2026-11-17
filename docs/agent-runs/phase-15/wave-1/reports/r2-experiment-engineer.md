# R2 report, experiment-engineer (received 2026-11-17; summary preserved with the claims as made; author: experiment-engineer subagent)

Status: COMPLETE for R2 items 2 to 4. Item 1 reported as a test bug, not a script bug. Edited only `experiments/**`; nothing run.

## 1. entry-A / entry-B wrong-ID cases: claimed test bug
- `exp_init` in `experiments/lib/exp-lib.sh` line 55 checks `[[ "$SCHOLARCHAIN_EXPERIMENT_ID" == "$expected" ]] || exp_die "this entry point is for experiment $expected, got ..."`, before `events.jsonl` creation (line 60) and before any pipeline call; `exp_die` prints `EXPERIMENT_REFUSED: ...` and exits 1.
- Harness helper `w15_entry` (`tests/scripts/run.sh` lines 10909 to 10926) selects the directory from the ID it passes (`case "$exp" in A) dir=a-baseline ;; *) dir=b-burst ;; esac`); the wrong-ID cases (line 10971) pass the other ID, so case A runs `b-burst/run.sh` with ID `B`, a legitimate B run (log: exit 0, stub ran, event log made).
- Fix belongs to test-engineer: select the directory of the entry point under test while exporting the other ID. No assertion weakened. Scripts not changed. Not verified by execution.

## 2. Manifests
`DATASET_SNAPSHOT=scholarchain-s-20261107-10dd7f34121b` in A and B; header comment updated. Limits unchanged (A 75 min, 1/1, 0.60 USD/h, 0.75 USD; B 80 min, 1/1, 1.55 USD/h, 2.10 USD). New approval records needed (manifest sha256 changed). `ACCOUNT_ID` and `OUTPUT_ROOT` remain placeholders. Other `experiments/**` files may still mention `PLACEHOLDER-WO4-S-SNAPSHOT-ID` (tree not searchable).

## 3. `experiments/b-burst/README.md`
New section "Limitation (decision P15-12, accepted 2026-11-17)": MapReduce on HDFS across core nodes; the two Spark applications and export on the primary (`local[4]`); B's scale-out evidence is the MapReduce stage only; P15-11 fallback. Note under "Open prerequisites" that items 1 and 2 predate P15-12 and the `DATA_PATH`/`PARTITION_COUNT` settings.

## 4. b-burst profile values: confirmed unchanged
`PARTITION_COUNT=16`, `SPLIT_MAX_BYTES=131072` (about 16 map tasks on the ~2 MB DBLP subset), `MAP_MEMORY_MB=3072`, `REDUCE_MEMORY_MB=3072` (about 4 containers per node if NodeManager memory is 12288 MB on m5.xlarge, UNVERIFIED, so the rest queue), `SHUFFLE_PARTITIONS=32` (Spark on the primary only). Risk: S MapReduce is small (about 727 s over 9 jobs); the queue may be too brief for scaling evaluation; mitigation P15-11; optional headroom: 4096 MB or 32 reducers (one-line conf change), not made without evidence.

## Files modified
`experiments/manifests/experiment-a-baseline.manifest`, `experiments/manifests/experiment-b-burst.manifest`, `experiments/b-burst/README.md`.

## Blocked-on
test-engineer: `w15_entry` directory selection (`tests/scripts/run.sh` 10909 to 10926, 10971).
