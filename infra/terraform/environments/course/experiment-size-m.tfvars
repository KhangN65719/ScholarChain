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

# Execution timeout: the 90-minute script cap plus 30 minutes. When it fires,
# CleanupOnFailure does not run (see the README, "Pipeline bounds"). The step
# timeout is half the script cap, so a hung second step is cut by the step
# timeout (which does run CleanupOnFailure) before the execution timeout.
pipeline_max_run_seconds      = 7200
pipeline_step_timeout_seconds = 2700
