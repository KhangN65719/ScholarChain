# Overlay for "session down": stop the hourly billing between sessions and KEEP
# storage. Apply AFTER course.tfvars (a later -var-file wins), through plan.sh
# --extra-var-file and then deploy.sh, not through cleanup.sh. Values only.
#
# Removes: the interface endpoints (egress_mode "none" keeps only the free gateway
# endpoints), the Neptune cluster and instance (and its data: the course
# environment keeps no final snapshot, so reload the graph from graph/ next
# session), and the running service tasks (desired count 0).
# Keeps: the S3 buckets, the DynamoDB tables, the S3 Vectors bucket and indexes,
# ECR repositories and images, secrets, roles, the VPC, subnets and security
# groups, the state machine, queues, alarms and the dashboard.
# Does not touch an EMR cluster: the pipeline creates and ends it per run.
#
# The plan will list the Neptune cluster and instance as stateful destroys, so the
# approval record needs STATEFUL_CHANGE_ACKNOWLEDGED=yes.
# Overlays such as experiment-size-*.tfvars must NOT be combined with this file.

egress_mode           = "none"
enable_neptune        = false
service_desired_count = 0

# Phase 15 (WO-10): the load balancer, the scaling targets and the worker service are session
# resources too. Without them the ALB would bill (about 0.03 USD an hour, UNVERIFIED) and the
# scaling targets would hold the service above zero. Turning them off destroys them; the ECR
# repositories, the task definitions, the log groups, the queues and the dashboards stay.
#
# OD-1: this file does NOT set enable_services_stack or service_layout, so it leaves them at their
# defaults (false, split). That is right after the Experiment A and B sessions, whose state holds
# no Wave 2 resource and the split layout: the teardown plan creates and destroys none of them.
# After SESSION 2 the state holds the stack and the combined layout, and this file alone would
# destroy the worker role, the load-client ECR repository (refused while it holds images), the
# log groups with their events and the security groups, and swap the query service for fast and
# slow. The session 2 teardown (A4) therefore layers session-down-services.tfvars after this file.
enable_load_balancer = false
enable_autoscaling   = false
enable_worker        = false
answer_model         = "stub"

emr_core_instance_count      = 0
emr_task_spot_instance_count = 0
