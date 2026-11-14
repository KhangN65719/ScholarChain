# Plan 1 summary (dirty tree, review only; recorded by the orchestrator from the human's pasted output)

Account ID omitted. Full log: /Users/winnguyen/scholarchain-data/logs/phase14-plan-1.log; plan files in /Users/winnguyen/scholarchain-data/plans/phase14-plan-1/.

- Target: course environment, us-east-2, cost ceiling 20, CPU X86_64, git f065a33371cd, DIRTY=yes (deploy.sh will refuse; digest is NOT approvable).
- Plan: 132 to add, 0 to change, 0 to destroy. STATEFUL_CHANGE=no. PLAN_EXIT=2. PLAN_DIGEST (dirty tree) 4c369cf7b0d10fdac69297774f53811f34af2d9bf2c3e6b37e4294a19ddcb953.
- Overlay in course.tfvars: enable_neptune=false, enable_bedrock=false, enable_pipeline=true, create_budgets=false, egress_mode=endpoints with interface_endpoint_az_count=1, service_desired_count=0 (ECS services created at zero tasks), emr core and task counts 0, log retention 3 days.
- Idle-billing items created: 8 interface endpoints (ecr.api, ecr.dkr, elasticmapreduce, logs, s3vectors, secretsmanager, sqs, sts) in 1 AZ, 3 secrets, alarms. Gateway endpoints s3 and dynamodb are free. 5 S3 Vectors indexes (cost by use). IAM: 7 roles, 1 instance profile, role policies, no wildcard-admin reported by the summary (not independently reviewed).
- Cost (COST_ESTIMATE.md, rates mostly unverified): foundation idle about 1.92 to 2.88 USD per day for the endpoints. Foundation plus S: LOW 1.44, HIGH 5.30. Neptune and EMR are not in this plan; they need the session overlay.
- Open: S3 Vectors CreateVectorBucket availability on this account (R2), EMR m5.xlarge and Neptune AZ placement, interface endpoint vs NAT (P4).
- Next: a clean-tree commit, re-plan (new digest), approval record, deploy. Nothing applied.
