# Graph bulk-load overlay (Phase 15, P15-5). Values only, no secrets.
# Layer it AFTER course.tfvars and experiment-size-s.tfvars (later -var-file wins):
#   -var-file course.tfvars -var-file experiment-size-s.tfvars -var-file graph-load.tfvars
# It sets one value, the Neptune writer class for the bulk load. Work order 114 / S run
# 10: the bulk loader hit OUT_OF_MEMORY_ERROR on db.t4g.medium (4 GiB); S run 11 loaded
# on db.r6g.xlarge (4 vCPU, 32 GiB).
#
# Session 2 apply order (each apply has its OWN plan, plan digest and approval record;
# no apply is chained to another):
#   1. Plan and apply  course + S + graph-load   -> Neptune is created at db.r6g.xlarge
#      (a session-up creates the writer directly at this class; no modify happens).
#   2. Run the loader in graph-only mode (loader-only execution), as in S run 11.
#   3. Plan and apply  course + S  (without this file) -> the S overlay sets
#      db.t4g.medium, so Terraform modifies the writer back down in place. Check that
#      the plan says "updated in-place" for the Neptune instance and never "must be
#      replaced": a replacement is a stop (the graph data is in the cluster volume,
#      but a replaced instance is not the plan the approval record covers).
#   4. Smoke, D, E, F, C (P15-10 order), then the same-day session-down.
# The step-3 modify is the cost control of P15-5: xlarge bills about 0.52 to 0.64 USD an
# hour more than t4g.medium (rates UNVERIFIED, COST_ESTIMATE.md work order 114), so the
# xlarge window should be only the load.
#
# Expected Neptune modify behaviour and downtime: UNVERIFIED. The module sets
# apply_immediately = true on the instance, so a class change is a ModifyDBInstance
# applied at once. Expected, from general RDS-family behaviour and not read from the
# Neptune documentation by the author: the writer restarts and the endpoint is
# unavailable for a few minutes, with no read replica to take over (the cluster has one
# instance). Plan the smoke after the endpoint answers again. Downsizing to a burstable
# t4g.medium is also not checked for orderability for engine 1.4.5.1 in the writer's
# Availability Zone at apply time (db.t4g.medium was confirmed orderable on 2026-11-13,
# checks-3.md; db.r6g.xlarge was not re-checked in this work order).

neptune_instance_class = "db.r6g.xlarge"
