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

# Work order 49 (human decision, 2026-11-15, after S run 3): the one on-node pipeline
# step was cut by the old 1800 s cap while Spark was still running (Hadoop about
# 12.5 min, ER 108 s, Spark expected 23 to 28 min on one executor). The run is
# started with --size medium (script cap 90 minutes = 5400 s); the script stops the
# execution at --max-minutes before the 7200 s execution timeout can fire.
# Execution timeout 7200 s. When it fires, CleanupOnFailure does not run (README,
# "Pipeline bounds").
# Per-step timeouts (TerminateCluster and CleanupOnFailure stay 900 s, the
# script-fetch step stays 300 s):
#   CreateCluster 1440 s = 0.4 h, the estimate's HIGH launch overhead.
#   EMR step (the one pipeline script: Hadoop, ER, Spark, export, publish) 3600 s.
#   Loader 1800 s, unchanged. The estimate's HIGH loader time is 1.0 h = 3600 s.
# Rule A (changed in work order 49, modules/workflow): create + 1 x EMR step + 600
# (300 s fetch step + 300 s for the cleanup state to issue the terminate call)
# must not exceed the execution timeout. 1440 + 3600 + 600 = 5640 <= 7200 (holds,
# 1560 s of slack). The old rule counted 2 x the step and would refuse these
# values: 1440 + 2 x 3600 + 300 = 8940 > 7200. The state machine has one long
# step, so the old rule over-counted.
# Known limitation, accepted by human decision O1 (phase 13 D10), not enforced:
# the sequential worst case 1440 + 300 + 3600 + 900 + 1800 = 8040 > 7200, so a run
# in which every segment takes its full timeout can end TIMED_OUT in the terminate
# or loader segment (840 s short). It is 60 s tighter only if the fetch and terminate
# allowances are left out (1440 + 3600 + 300 + 1800 = 7140, 60 s slack), which is
# not the real sequence. A cap is a ceiling, not a cost.
# The EMR step value is NOT the estimate's HIGH value. After the S run the human
# re-derives the M and L values from the measured S runtime (README, "Pipeline
# bounds") and records them in a Phase 14 decisions file.
pipeline_max_run_seconds                = 7200
pipeline_create_cluster_timeout_seconds = 1440
pipeline_emr_step_timeout_seconds       = 3600
pipeline_loader_timeout_seconds         = 1800
