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

# Execution timeout: the 150-minute script cap plus 30 minutes. When it fires,
# CleanupOnFailure does not run (see the README, "Pipeline bounds"). The step
# timeout stays at the 3600 default: cluster start plus two full steps is 7200 s
# plus start, which is already below 10800.
pipeline_max_run_seconds      = 10800
pipeline_step_timeout_seconds = 3600
