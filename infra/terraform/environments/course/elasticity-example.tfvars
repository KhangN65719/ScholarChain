# Example for the Phase 15 elasticity run on tier M: master plus 4 core nodes
# (5 x m5.xlarge = 20 vCPUs, so check the On-Demand Standard vCPU quota first).
# The run repeats with emr_core_instance_count = 1, 2 and 4; only this value
# changes between repeats. Apply AFTER course.tfvars and experiment-size-m.tfvars.
# Values only, no secrets. The default cap (3) in course.tfvars is NOT raised.
# Spot task nodes stay off: no Spot price is verified.

emr_core_instance_count       = 4
emr_task_spot_instance_count  = 0
emr_managed_scaling_max_units = 5
