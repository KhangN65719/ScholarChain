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

# Execution timeout: the 60-minute script cap plus 30 minutes = 5400 s. When it
# fires, CleanupOnFailure does not run (README, "Pipeline bounds").
# Per-step timeouts (TerminateCluster and CleanupOnFailure stay 900 s):
#   CreateCluster 1440 s = 0.4 h, the estimate's HIGH launch overhead.
#   EMR step (Hadoop and Spark, each) 1800 s = half the script cap, unchanged.
#     The estimate's HIGH Hadoop + Spark time is 1.0 h - 0.4 h = 0.6 h = 2160 s.
#   Loader 1800 s, unchanged. The estimate's HIGH loader time is 1.0 h = 3600 s.
# Rule A: 1440 + 2 x 1800 + 300 = 5340 <= 5400 (holds, 60 s of slack).
# Known limitation, accepted by human decision O1 (phase 13 D10), not enforced:
# 1440 + 2 x 1800 + 900 + 1800 = 7740 > 5400. At the estimate's HIGH bound (7200 s)
# a run may time out. A cap is a ceiling, not a cost.
# The EMR step and loader values are NOT the estimate's HIGH values. After the S
# run the human re-derives the M and L values from the measured S runtime (README,
# "Pipeline bounds") and records them in a Phase 14 decisions file.
pipeline_max_run_seconds                = 5400
pipeline_create_cluster_timeout_seconds = 1440
pipeline_emr_step_timeout_seconds       = 1800
pipeline_loader_timeout_seconds         = 1800
