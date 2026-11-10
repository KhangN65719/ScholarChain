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

emr_core_instance_count      = 0
emr_task_spot_instance_count = 0
