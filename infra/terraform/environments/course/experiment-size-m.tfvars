# Overlay for a tier M run (25,000 works). PROPOSED sizes: decision D2c, COST_ESTIMATE.md.
# Apply AFTER course.tfvars (later -var-file wins). Values only, no secrets.
# Adds Neptune (a session resource: destroy it after the run). Pipeline on one
# primary node, matching the estimate's node-hours for M. One task per service
# for the query session.

enable_neptune = true
neptune_mode   = "provisioned"

emr_core_instance_count       = 0
emr_task_spot_instance_count  = 0
emr_managed_scaling_max_units = 3

service_desired_count = 1

# Execution timeout: the 90-minute script cap plus 30 minutes = 7200 s. When it
# fires, CleanupOnFailure does not run (README, "Pipeline bounds").
# Per-step timeouts (TerminateCluster and CleanupOnFailure stay 900 s):
#   CreateCluster 1440 s = 0.4 h, the estimate's HIGH launch overhead.
#   EMR step (Hadoop and Spark, each) 2700 s = half the script cap, unchanged.
#     The estimate's HIGH Hadoop + Spark time is 1.4 h - 0.4 h = 1.0 h = 3600 s.
#   Loader 2700 s, unchanged. The estimate's HIGH loader time is 2.0 h = 7200 s.
# Rule A: 1440 + 2 x 2700 + 300 = 7140 <= 7200 (holds, 60 s of slack).
# Known limitation, accepted by human decision O1 (phase 13 D10), not enforced:
# 1440 + 2 x 2700 + 900 + 2700 = 10440 > 7200. At the estimate's HIGH bound
# (12240 s) a run may time out. A cap is a ceiling, not a cost.
# These values are placeholders until the human sets them from the measured S
# runtime (README, "Pipeline bounds") and records that in a Phase 14 decisions
# file before M runs.
pipeline_max_run_seconds                = 7200
pipeline_create_cluster_timeout_seconds = 1440
pipeline_emr_step_timeout_seconds       = 2700
pipeline_loader_timeout_seconds         = 2700
