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
#   Fetch step 300 s (fixed), one EMR step 2700 s, unchanged value. The design has ONE
#     pipeline script step (Hadoop, ER, Spark, export, publish together), not a
#     Hadoop step and a Spark step. The estimate's HIGH Hadoop + Spark time is
#     1.4 h - 0.4 h = 1.0 h = 3600 s, so 2700 s is below it.
#   Loader 2700 s, unchanged. The estimate's HIGH loader time is 2.0 h = 7200 s.
# Rule A (work order 49 changed it to create + 1 x EMR step + 600, one long step plus
# the 300 s fetch step and 300 s margin): 1440 + 2700 + 600 = 4740 <= 7200 (holds,
# 2460 s of slack).
# Known limitation, accepted by human decision O1 (phase 13 D10), not enforced:
# the sequential worst case create + fetch + step + terminate + loader is
# 1440 + 300 + 2700 + 900 + 2700 = 8040 > 7200 (840 s over), so the O1 limitation
# DOES apply to M: a run in which every segment takes its full timeout can end
# TIMED_OUT in the terminate or loader segment. The script's own cap (--size medium,
# 90 minutes = 5400 s) ends a run first. At the estimate's HIGH bound (12240 s) a run
# may time out. A cap is a ceiling, not a cost.
# These values are placeholders until the human sets them from the measured S
# runtime (README, "Pipeline bounds") and records that in a Phase 14 decisions
# file before M runs.
pipeline_max_run_seconds                = 7200
pipeline_create_cluster_timeout_seconds = 1440
pipeline_emr_step_timeout_seconds       = 2700
pipeline_loader_timeout_seconds         = 2700
