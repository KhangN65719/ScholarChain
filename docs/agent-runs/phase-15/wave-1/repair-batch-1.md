# Wave 1 repair batch 1 (orchestrator, 2026-11-17)

Basis: human harness run `phase15-wave1-harness.log` (`RESULT pass=1599 fail=14`, `SKIPPED_CASES 0`), the human's root-cause list, and decisions P15-12 to P15-15. Common rules of `work-orders.md` apply. **Do not weaken any assertion.** No AWS, no sbt, no commit.

## R1 infra-engineer
1. `scripts/emr/lib-emr.sh:388`: the regex `^[A-Za-z0-9._=,:-]{1,300}$` exceeds macOS bash's 255 repetition limit ("invalid regular expression ... maximum repetit"), failing 9 x `15.emr.profile-arg.*` (log lines 1550 to 1558). Make the check portable with the same accepted set: a length check (1 to 300) plus a character-class match without a bounded repetition above 255. Check the whole `scripts/emr/**` and `scripts/**` for any other `{n,m}` with m > 255.
2. `scripts/emr/run-s-pipeline.sh` holds `mapred job -status` twice; case `15.emr.static.counters-...` (line 1565) expects once. Decide whether the second occurrence is a bug (remove it) or intended (then document why in the script and report it so test-engineer can update the case; do not ask for the case to change otherwise).
3. Case `15.terraform.graph-load.sets-nothing-the-s-overlay-owns...` (line 1474): `infra/terraform/environments/course/course.tfvars` assigns `neptune_instance_class`; only the S and graph-load overlays may. Remove it if the module default (`db.t4g.medium`) covers it, or record in the file why it must stay and report it.
4. Case `15.experiment.static.readme-marks-d12-as-decided-by-p15-1` (line 1465): `scripts/README.md` says "DECIDED"; the case looks for decided/closed/resolved. Reword the README line to "decided by P15-1" (keep the case unchanged).

## R2 experiment-engineer
1. Real bug: `experiments/a-baseline/run.sh` and `experiments/b-burst/run.sh` run the pipeline when given the other experiment ID (cases `entry-A`/`entry-B.refuses-the-other-experiment-id-and-starts-nothing`, lines 1591 and 1599). Expected: exit 1, stderr `EXPERIMENT_REFUSED: this entry point is for experiment A` (or B), no pipeline call, no event log. Check the order: the refusal must come before any pipeline call or event-log write.
2. Fill `DATASET_SNAPSHOT=scholarchain-s-20261107-10dd7f34121b` (WO-4, human-verified) into the A and B manifests.
3. Add the P15-12 limitation to `experiments/b-burst/README.md` (MapReduce on HDFS across core nodes; Spark and export on the primary, `local[4]`; B's scale-out evidence is the MapReduce stage).
4. Confirm or change the `b-burst` profile values infra proposed in `scripts/emr/spark-yarn.conf` (16, 131072, 3072, 3072, 32): report your values and reasons; infra edits that file if you change them.

## R3 dataset-engineer
P15-14: the chunk file must not live in the repository. Change `datasets/scripts/chunkset.py` so `chunks`, `validate` and `replay` read and write `chunks.jsonl` under an explicit `--chunks-dir` outside the repository (the human will use `/Users/winnguyen/scholarchain-data/phase15-chunkset-c`), refusing a path inside the repository. Keep `chunkset-manifest.json` (sha256, count, bytes, selection rule, chunk-file location as a non-repo path) and `snapshot-s.json` in `datasets/tier-s/`. Update `test_chunkset.py` for the new argument. Give the human the paste-ready commands to move the existing `datasets/tier-s/chunks-c/chunks.jsonl` out of the repository and to re-run `validate`, `replay` and the unit tests. No deletion by you.
