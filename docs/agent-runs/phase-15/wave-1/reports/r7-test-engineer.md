# R7 report, test-engineer (received 2026-11-17; summary of the report as received, cases and risks preserved; author: test-engineer subagent)

Status: COMPLETE for writing, NOT EXECUTED (guard refuses `bash tests/scripts/run.sh`, `bash -n`, `shellcheck`, `grep`). Aligned to R4's landed text and R5's landed `experiments/b-burst/run.sh` (88 / 2.30 / 1.55). Files: `tests/scripts/run.sh`, `tests/scripts/README.md`.

## Existing cases changed (no assertion weakened)
- `14.emr.run-s-pipeline.single-node-scope-wording`: new file/YARN and HDFS/local `RUN_SCOPE` texts, one `kv RUN_SCOPE`, no early constant, ordered after `DATA_PATH_MODE` and Spark master, inside the hadoop stage.
- `15.emr.static.the-conf-defaults-...` (old 10622): a-baseline exactly 4 lines; b-burst `DATA_PATH=hdfs`, `SPARK_MASTER=local`, `SPARK_EVENT_LOG=false`, `SHUFFLE_PARTITIONS=8`; plain `NM_WAIT_SECONDS=300`.
- `15.emr.static.core-0-...`: new `dmode_conf` needles. `15.emr.static.counters-...`: strict find excludes `spark-events/*`.
- `15.terraform.elasticity-s.min-is-below-max-...` (old 9626): same assertions; comment cites K2 (the `1 +` is the primary as an instance count).
- `15.emr.spark-master.profile-b-burst-...` renamed `...gives-8-shuffle-partitions-and-the-local-master`.
- `15.emr.data-path.data-path-hdfs-forces-hdfs-on-a-one-node-cluster` replaced by the fails-clearly case.
- B entry helpers 80/74/2.00 changed to 90/88/2.30 (P15-16). Comment at old 9534 fixed.
- Plumbing: data-block cut starts at `local dmode dmode_conf hrun=""`; smaster block = data block + spark-stage block; child stubs for `timeout -k`, `sleep`/`date +%s` on `SECONDS`, late `yarn` node lists; data-block output prints `SMASTER=`.

## Cases added (expected pass unless noted)
- M1: `15.emr.data-path.*` (7: forced hdfs with 0 remote dies with "DATA_PATH=hdfs is forced"/"Not falling back to file:///", no hdfs call, bounded sleep; `NM_WAIT_SECONDS` 30, 0, -5 (refused); remote present; late registration; unreadable list gives `REMOTE=unknown` and continues); real-conf per profile (4); `15.emr.spark-master.*` (3).
- `15.emr.run-scope.*` (3); `15.emr.event-log.profile-b-burst-...` (no event log).
- Static: every `hdfs` call under `timeout` 1 to 900 s, `timeout 60` on mkdir; `scan_no_secrets_bounded "$lf" 120` before `write_json` and the strict scan; 256 MiB cap, removal, NOTE, `SPARK_EVENT_LOG_PUBLISHED`; wait and die before `DATA_PATH_MODE`.
- M6 `15.terraform.managed-scaling.*` (6, python3 evaluates the module's expressions): 1/0/4 renders 1/3/3/3; elasticity-example 4/4/4/4; core 0 no policy; grid of valid shapes (Min = core, Max = max(cap, fixed) - 1, MaxCore/MaxOnDemand bounds, fixed shapes); citations present, no `+ 1` in Min, no `local.max_units` in Max/MaxOnDemand.
- m3 `15.terraform.wait.*` (2): no Catch names the wait state; wait is `Type = Wait` to TerminateCluster with no Catch. `15.emr.copy-back.*` (6): success; `-get` rc 1/124 die; `-rm` rc 1/124 note only; two timeout wrappers.
- R5 item 4 `15.experiment.manifests.*` (7): six JSON copies equal (computed independently); check script exists and README names it; in a temp tree the check passes on committed files and fails on JSON change, KEY=VALUE change, missing JSON, extra key.
- K1 `15.experiment.entry.static.b-uses-the-full-tier-s-cap-...`: 86 to 89 min, `MAX_MINUTES=90`, ceiling 2.30 to 2.50.

## Risks
- R5 check-script interface assumed (name contains "check" and "manifest" under `experiments/`, no arguments); JSON compared by lower-cased leaf keys with metadata keys tolerated.
- Possible production defect: `wait_for_remote_yarn_nodes "$(conf_value NM_WAIT_SECONDS ...)"` runs `conf_value` in a command substitution where `die` may not stop the script under `set -e`; if `15.emr.data-path.a-bad-nm-wait-seconds-is-refused` fails, route to infra-engineer.
- Assumed helper names (`mr_ok`, `note`, `kv` to stdout, functions in `lib-emr.sh`). Not covered: course wiring of `emr_managed_scaling_max_units`, `terraform validate`, real yarn/hdfs, EMR scale-in, dynamic large-file scan.
