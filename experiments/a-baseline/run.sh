#!/usr/bin/env bash
# Experiment A entry point (needed only if the audit verdict is RE-RUN): the Hadoop and
# Spark baseline on tier S at minimum EMR capacity (primary node only, core 0, no
# managed scaling), one pipeline execution through scripts/run-bounded-pipeline.sh.
#
# Started once by scripts/run-experiment.sh with the approved manifest
# experiments/manifests/experiment-a-baseline.manifest. No tuning, no retry. Raw output
# goes under SCHOLARCHAIN_RUN_DIR (the run's raw/ directory), the same files as
# experiments/b-burst/run.sh: events.jsonl, pipeline-input.json (+ .sha256),
# pipeline/*.record.env, pipeline-run.log, window.json.
#
# Human-supplied environment (paths only, no secret):
#   SCHOLARCHAIN_PIPELINE_INPUT_FILE        execution input, mode pipeline, a NEW run id
#   SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD   approval record for the pipeline script
# Committed pipeline limits (the pipeline approval record binds them):
#   --size medium          the wall-clock tier cap is 90 min; the data is the S subset.
#                          S run 6 needed about 58 min up to the end of the loader
#                          step, so the small tier cap of 60 min is too tight.
#   --max-minutes 70       leaves about 5 minutes for the teardown check inside the
#                          manifest limit of 75
#   --hourly-burn-usd 0.60 the tier S session burn of scripts/README.md, "Pipeline
#                          sizes" (UNVERIFIED rates)
#   --spend-ceiling-usd 0.75 projected 0.60 x 70 / 60 = 0.70
# What this run records that S run 6 did not (task counts, shuffle) depends on the
# on-node collection that infra-engineer adds to scripts/emr/run-s-pipeline.sh; see
# experiments/a-baseline/README.md. This script cannot add it.
SC_ENTRY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=experiments/lib/exp-lib.sh
source "$SC_ENTRY_DIR/../lib/exp-lib.sh"

trap 'exp_event ENTRY_SIGNAL "TERM or INT received"; exit 143' TERM INT

exp_init A
rc=0
exp_run_pipeline medium 70 0.75 0.60 || rc=$?
exp_event ENTRY_END "exit=$rc"
exit "$rc"
