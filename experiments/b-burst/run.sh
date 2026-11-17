#!/usr/bin/env bash
# Experiment B entry point: Hadoop and Spark burst on tier S data with EMR managed
# scaling (primary 1, core 1 growing to at most 3 core nodes: the managed-scaling limits
# count core and task nodes only, doc-verification.md K2; see infra/terraform/environments/
# course/elasticity-s.tfvars) and a bounded wait before the cluster is terminated, so a
# scale-in can be observed.
#
# Started once by scripts/run-experiment.sh with the approved manifest
# experiments/manifests/experiment-b-burst.manifest. It does exactly one thing: it
# starts one pipeline execution through scripts/run-bounded-pipeline.sh (the existing
# pipeline path) and waits for it. It changes no setting during the run and does not
# retry. Raw output goes under SCHOLARCHAIN_RUN_DIR (the run's raw/ directory):
#   events.jsonl            entry-point events with UTC ISO-8601 times
#   pipeline-input.json     copy of the execution input, with pipeline-input.sha256
#   pipeline/*.record.env   the pipeline run record (execution ARN, start, end, status)
#   pipeline-run.log        the pipeline script's own output, one POLL line per 30 s
#   window.json             start, end and status taken from that record
# Capacity over time is NOT measured here. It is collected afterwards, read-only, by
# experiments/b-burst/collect.sh (see experiments/b-burst/README.md).
#
# Human-supplied environment (paths only, no secret):
#   SCHOLARCHAIN_PIPELINE_INPUT_FILE        execution input, mode pipeline, a NEW run id
#   SCHOLARCHAIN_PIPELINE_APPROVAL_RECORD   approval record for the pipeline script
# The pipeline limits below are committed constants so the pipeline approval record
# binds them. They sit below the manifest limits (MAX_MINUTES 90, SPEND_CEILING 2.40).
# Decision P15-16 (review 1, K1): B uses the full tier S cap of 90 minutes. The earlier
# 74-minute stop was too close to the realistic case (about 4,300 s of 4,440 s), and a stop
# cuts the scale-in window and fails the run.
#   --size medium        wall-clock tier 90 min cap; the data is still the S subset
#   --max-minutes 88     the entry limit (manifest MAX_MINUTES 90) stays strictly above it
#                        (the entry helper refuses otherwise); about 2 minutes are left for
#                        the entry point to write window.json and exit. If the 88-minute
#                        stop fires, the cluster is still terminated (cost stays bounded),
#                        but the teardown check after it can take up to 300 s
#                        (scripts/run-bounded-pipeline.sh, P15-20), more than the 2 minutes
#                        left, so the 90-minute entry limit may cut the end of the run
#                        record (window.json and the last events). Accepted limitation.
#   --hourly-burn-usd 1.55   4 instances at 0.35 + 8 interface endpoints at 0.015
#                            + loader task 0.0247 (rates UNVERIFIED, COST_ESTIMATE.md)
#   --spend-ceiling-usd 2.30 projected 1.55 x 88 / 60 = 2.27
SC_ENTRY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=experiments/lib/exp-lib.sh
source "$SC_ENTRY_DIR/../lib/exp-lib.sh"

trap 'exp_event ENTRY_SIGNAL "TERM or INT received"; exit 143' TERM INT

exp_init B
rc=0
exp_run_pipeline medium 88 2.30 1.55 || rc=$?
exp_event ENTRY_END "exit=$rc"
exit "$rc"
