# Overlay for a tier S run (5,000 works). PROPOSED sizes: decision D2c, COST_ESTIMATE.md.
# Apply AFTER course.tfvars (later -var-file wins). Values only, no secrets.
# Adds Neptune (a session resource: destroy it after the run). Pipeline on one
# primary node, matching the estimate's node-hours for S.

enable_neptune = true
neptune_mode   = "provisioned"

# Bedrock on (work order 31). The loader embeds with Titan V2 in-Region
# (amazon.titan-embed-text-v2:0, us-east-2), which needs the bedrock-runtime
# interface endpoint (a ninth, about 0.010 to 0.015 USD an hour in one AZ at the
# estimate's unverified rate) and the bedrock:InvokeModel statements on the loader
# and runtime roles. It also adds the Haiku profile and foundation-model ARNs to
# the runtime role. With it off the loader cannot embed. The M and L overlays do
# not set it: a loader run under them needs the same line.
enable_bedrock = true

emr_core_instance_count       = 0
emr_task_spot_instance_count  = 0
emr_managed_scaling_max_units = 3

service_desired_count = 0

# Work orders 49 and 52 (human decisions D31 and D32, 2026-11-15, after S runs 3 and
# 4). S run 3: the one on-node pipeline step was cut by the old 1800 s cap while
# Spark was still running. S run 4 (3600 s step cap): the on-node Spark limit of
# 1500 s in scripts/emr/lib-emr.sh cut Spark at about 70% of its stages (about 2100
# to 2400 s needed); that limit is now 3000 s, as is the entity-resolution limit
# (ER needs about 103 s). S run 4 used --size medium (script cap 90 minutes =
# 5400 s); the script stops the execution at --max-minutes before the 7200 s
# execution timeout can fire.
# S run 5 (human decision D33, after the independent review): the command line is
#   --size large --max-minutes 110 --spend-ceiling-usd 1.20 --hourly-burn-usd 0.62
# (projected 0.62 x 110 / 60 = 1.14 USD, a ceiling on the estimate, not a bill). The
# script stops the execution at 6600 s, before the 7200 s execution timeout. --size
# selects only the script's wall-clock tier (the --max-minutes cap, 150 for large),
# not the dataset: the data is the 3,472-work S subset. The sequential budget, one
# segment after the other: creation about 660 s (timeout 1440) + fetch up to 300 +
# EMR step 3300 to 3900 expected (cap 4800) + terminate up to 900 + loader (cap 1800,
# never run). 660 + 300 + 3300 + 900 + 1800 = 6960 s at the low step estimate, 7560 s
# at 3900 s, and 660 + 300 + 4800 + 900 + 1800 = 8460 s with the step at its 4800 s cap
# (creation at its observed 660 s; with creation at its 1440 s timeout the same sum is
# the O1 worst case below, 9240 s), against the 6600 s script stop and the 7200 s
# execution timeout. So after
# a good pipeline run the 6600 s stop can cut the loader (about 840 to 1440 s are
# left for it after terminate). The earlier wording "no conflict expected" counted
# only the step and is withdrawn. UNVERIFIED: whether StopExecution stops the Fargate
# runTask.sync loader task (stop_everything in the script stops only EMR).
# Execution timeout 7200 s, unchanged. When it fires, CleanupOnFailure does not run
# (README, "Pipeline bounds").
# Per-step timeouts (TerminateCluster and CleanupOnFailure stay 900 s, the
# script-fetch step stays 300 s):
#   CreateCluster 1440 s = 0.4 h, the estimate's HIGH launch overhead.
#   EMR step (the one pipeline script: Hadoop, ER, Spark, export, publish) 4800 s.
#   Loader 1800 s, unchanged. The estimate's HIGH loader time is 1.0 h = 3600 s.
# Rule A (changed in work order 49, modules/workflow): create + 1 x EMR step + 600
# (300 s fetch step + 300 s for the cleanup state to issue the terminate call)
# must not exceed the execution timeout. 1440 + 4800 + 600 = 6840 <= 7200 (holds,
# 360 s of slack). The old rule counted 2 x the step and would refuse these
# values. The state machine has one long step, so the old rule over-counted.
# Known limitation, accepted by human decision O1 (phase 13 D10), not enforced:
# the sequential worst case 1440 + 300 + 4800 + 900 + 1800 = 9240 > 7200, so a run
# in which every segment takes its full timeout can end TIMED_OUT in the terminate
# or loader segment (2040 s short). The script's own stop (S run 5: --max-minutes 110
# = 6600 s) ends a run first. A cap is a ceiling, not a cost.
# The EMR step value is NOT the estimate's HIGH value. After the S run the human
# re-derives the M and L values from the measured S runtime (README, "Pipeline
# bounds") and records them in a Phase 14 decisions file.
pipeline_max_run_seconds                = 7200
pipeline_create_cluster_timeout_seconds = 1440
pipeline_emr_step_timeout_seconds       = 4800
pipeline_loader_timeout_seconds         = 1800
