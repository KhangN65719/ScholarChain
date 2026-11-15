# Overlay for a tier L run (100,000 works). PROPOSED sizes: decision D2c,
# COST_ESTIMATE.md. L runs last and only after the human's spend gate (G-L).
# Apply AFTER course.tfvars (later -var-file wins). Values only, no secrets.
# Adds Neptune (a session resource: destroy it after the run). The estimate
# prices L on one primary node (0.9 to 2.4 node-hours). If one node proves too
# slow, set emr_core_instance_count = 1: it roughly doubles the EMR line and the
# spend gate must be re-run first. Two tasks per service (4 in all) for the load test.

enable_neptune = true
neptune_mode   = "provisioned"

emr_core_instance_count       = 0
emr_task_spot_instance_count  = 0
emr_managed_scaling_max_units = 3

service_desired_count = 2

# Execution timeout: the 150-minute script cap plus 30 minutes = 10800 s. When it
# fires, CleanupOnFailure does not run (README, "Pipeline bounds").
# Per-step timeouts (TerminateCluster and CleanupOnFailure stay 900 s):
#   CreateCluster 1440 s = 0.4 h, the estimate's HIGH launch overhead.
#   EMR step (Hadoop and Spark, each) 3600 s, unchanged.
#     The estimate's HIGH Hadoop + Spark time is 2.4 h - 0.4 h = 2.0 h = 7200 s.
#   Loader 3600 s, unchanged. The estimate's HIGH loader time is 4.0 h = 14400 s,
#     which is also above the 7200 s range of the loader variable.
# Rule A (work order 49 changed it to create + 1 x EMR step + 600, one long step plus
# the 300 s fetch step and 300 s margin): 1440 + 3600 + 600 = 5640 <= 10800 (holds,
# 5160 s of slack). The old form 1440 + 2 x 3600 + 300 = 8940 over-counted the step.
# Known limitation, accepted by human decision O1 (phase 13 D10), not enforced:
# 1440 + 2 x 3600 + 900 + 3600 = 13140 > 10800. At the estimate's HIGH bound
# (23040 s) a run may time out. A cap is a ceiling, not a cost.
# These values are placeholders until the human sets them from the measured S
# runtime (README, "Pipeline bounds") and records that in a Phase 14 decisions
# file before L runs. The range ends at 10800.
pipeline_max_run_seconds                = 10800
pipeline_create_cluster_timeout_seconds = 1440
pipeline_emr_step_timeout_seconds       = 3600
pipeline_loader_timeout_seconds         = 3600
