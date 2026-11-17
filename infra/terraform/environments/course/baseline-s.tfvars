# Experiment A on tier S data, single node (Phase 15, P15-2, work order 1b). Values only,
# no secrets. Apply AFTER course.tfvars and experiment-size-s.tfvars (later -var-file wins):
#   -var-file course.tfvars -var-file experiment-size-s.tfvars -var-file baseline-s.tfvars
# It turns off what the S overlay switches on that A does not use (Neptune, Bedrock) and
# keeps the cluster at one primary node, the shape of S run 6. elasticity-s.tfvars is not
# used: with core 0 it would differ from this file only in the Wait state, the scaling
# cap and the profile, and a separate file keeps A's state machine free of the Wait.

emr_core_instance_count       = 0
emr_task_spot_instance_count  = 0
emr_managed_scaling_max_units = 3

enable_neptune        = false
enable_bedrock        = false
service_desired_count = 0

# The S overlay gave the loader 2 vCPU and 8 GiB for the Neptune graph phase; A has no
# graph phase and skips the loader.
loader_cpu    = 512
loader_memory = 1024

# The loader is left out of mode pipeline, so the execution ends SUCCEEDED after
# TerminateCluster. The profile a-baseline of scripts/emr/spark-yarn.conf switches on the
# plain Spark event log and mapred job -status per job, nothing else: the run is run 6's
# (single node, file:/// paths, Spark on YARN, 8 shuffle partitions, default reducers).
pipeline_skip_loader = true
pipeline_profile     = "a-baseline"

# Timeouts as the S overlay, no Wait state (default 0), loader at the module minimum:
# Rule A 1440 + 4800 + 600 = 6840 <= 7200 (holds, 360 s of slack). The script's own tier S
# stop (90 minutes) ends a run first. A cap is a ceiling, not a cost.
pipeline_max_run_seconds                = 7200
pipeline_create_cluster_timeout_seconds = 1440
pipeline_emr_step_timeout_seconds       = 4800
pipeline_loader_timeout_seconds         = 600
