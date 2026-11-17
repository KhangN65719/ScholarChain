# Phase 15 note: this example gives minimum = maximum for the core and task nodes (the
# cap of 5 counts the primary; EMR ComputeLimits do not, so the module renders Min 4,
# Max 4, MaxCore 4, MaxOnDemand 4), so managed scaling has no room and it CANNOT scale
# out or in. It is kept as the fixed-size example. For a run that can scale on tier S
# data use elasticity-s.tfvars (rendered Min 1, Max 3, MaxCore 3, MaxOnDemand 3).
#
# Example for the Phase 15 elasticity run on tier M: master plus 4 core nodes
# (5 x m5.xlarge = 20 vCPUs, so check the On-Demand Standard vCPU quota first).
# The run repeats with emr_core_instance_count = 1, 2 and 4; only this value
# changes between repeats. Apply AFTER course.tfvars and experiment-size-m.tfvars.
# Values only, no secrets. The default cap (3) in course.tfvars is NOT raised.
# Spot task nodes stay off: no Spot price is verified.

emr_core_instance_count       = 4
emr_task_spot_instance_count  = 0
emr_managed_scaling_max_units = 5
