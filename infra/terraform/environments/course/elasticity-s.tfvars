# Experiment B on tier S data (Phase 15, P15-1, P15-7, P15-11). Values only, no secrets.
# Apply AFTER course.tfvars and experiment-size-s.tfvars (later -var-file wins):
#   -var-file course.tfvars -var-file experiment-size-s.tfvars -var-file elasticity-s.tfvars
# It turns off everything the S overlay switches on that B does not use, and sizes the
# cluster so managed scaling CAN scale: 1 primary + 1 core at the floor, up to 4
# instances at the cap (2 to 4 x m5.xlarge = 8 to 16 vCPU; check the On-Demand
# Standard vCPU quota first, see scripts/README.md "Phase 15 pre-session checks").
# elasticity-example.tfvars (min = max = 5) cannot scale and is not used here.
#
# Managed scaling bounds, from modules/emr (review 1, K2). emr_managed_scaling_max_units
# counts the primary (4 = 1 primary + up to 3 core), but EMR ComputeLimits apply only to
# core and task nodes, so the module subtracts the primary and renders
#   MinimumCapacityUnits = 1, MaximumCapacityUnits = 3,
#   MaximumCoreCapacityUnits = 3, MaximumOnDemandCapacityUnits = 3
# (core-only scaling, Scenario 1 of the managed-scaling allocation-strategy page; sources
# in docs/agent-runs/phase-15/wave-1/doc-verification.md, K2). The floor is the one core
# node the cluster starts with, so EMR has no reason to add a node at start. Total
# cluster: 2 to 4 x m5.xlarge = 8 to 16 vCPU.
# Task nodes are not used (no Spot price is verified).
# UNVERIFIED until the first scaling cluster: when EMR scales out and in (and whether
# the Hadoop MapReduce demand in the b-burst profile is enough to trigger it).
emr_core_instance_count       = 1
emr_managed_scaling_max_units = 4
emr_task_spot_instance_count  = 0

# Neptune, Bedrock and the services are not part of B.
enable_neptune        = false
enable_bedrock        = false
service_desired_count = 0

# The S overlay gave the loader 2 vCPU and 8 GiB for the Neptune graph phase (work
# order 108). B has no graph phase: back to the module defaults.
loader_cpu    = 512
loader_memory = 1024

# A Wait state before TerminateCluster so the scale-in can be observed with the
# cluster idle (state WaitBeforeTerminate; absent when 0). The idle auto-termination
# must outlast the wait or the cluster can end itself during it, so it is raised to
# 1800 s (the course default is 900; the variable allows 60 to 86400).
# Whether managed scaling scales in within 900 s of idle is UNVERIFIED: if it does not,
# the wait shows no scale-in and P15-11 (D's ECS scaling as the second source) applies.
pipeline_wait_before_terminate_seconds = 900
emr_idle_timeout_seconds               = 1800

# Pipeline timeouts, re-checked against Rule A with the wait (workflow module):
#   create + step + wait + 600 <= execution timeout
#   1440 + 4200 + 900 + 600 = 7140 <= 7200 (holds, 60 s of slack).
# The step is 4200 s, not the S overlay's 4800 s, because 4800 would need
# 1440 + 4800 + 900 + 600 = 7740 > 7200 (refused by Rule A) and the execution timeout is
# kept at the S value, 7200 s. Run 6's single-node step ran about 2,706 s before publish
# (results-s-tier.md) and B uses more nodes and a higher partition count, so 4200 s
# leaves room. The pipeline's own stop for B is about 88 minutes (5280 s, P15-16, set in
# experiments/b-burst/run.sh), inside the tier S cap of 90 minutes (5400 s, P15-1, the
# manifest MAX_MINUTES); both are below the 7200 s execution timeout, and 5280 s is above
# the realistic run of about 4,300 s. The stop therefore ends a run first. Rule A does
# not involve the stop and is unchanged: 1440 + 4200 + 900 + 600 = 7140 <= 7200. A cap is
# a ceiling, not a cost.
# Work order 1b: the loader is left out of mode pipeline (pipeline_skip_loader), so the
# execution ends SUCCEEDED after TerminateCluster. With Neptune and Bedrock off the
# loader has nothing to do. The loader cap stays as the module minimum, 600 s, for a
# loader-only execution if one is ever started in this stack. The known O1 sequential
# limitation (create + fetch + step + wait + terminate, now without a loader) is
# 1440 + 300 + 4200 + 900 + 900 = 7740 > 7200 in the worst case; the realistic case
# (create plus fetch about 374 s, step about 2,700 s or less, wait 900, terminate about
# 350) is about 4,300 s.
pipeline_max_run_seconds                = 7200
pipeline_create_cluster_timeout_seconds = 1440
pipeline_emr_step_timeout_seconds       = 4200
pipeline_loader_timeout_seconds         = 600

# Work order 1b: the loader is skipped, and the on-node script reads the b-burst
# profile of scripts/emr/spark-yarn.conf (a higher reducer count, smaller map splits,
# larger containers: the settings that create pending YARN demand; MapReduce counters on,
# Spark event log off and 8 shuffle partitions, P15-16; the paths are pinned to HDFS and a
# local Spark master). The script puts the MapReduce
# inputs and output in HDFS and runs the two Spark applications as local[N] on the
# primary (scripts/emr/run-s-pipeline.sh, header "Phase 15"). Apply order for the
# session: baseline-s.tfvars (A) first, then this file (B); see scripts/README.md,
# "Phase 15 sessions A and B".
pipeline_skip_loader = true
pipeline_profile     = "b-burst"
