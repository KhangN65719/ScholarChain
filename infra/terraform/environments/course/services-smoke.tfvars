# Phase 15, session 2, apply A2 only (WO-10, P15-4). Values only, no secrets.
# Layer it AFTER services-session.tfvars:
#   -var-file course.tfvars -var-file experiment-size-s.tfvars -var-file services-session.tfvars \
#   -var-file session2.tfvars -var-file services-smoke.tfvars
# It turns the Bedrock answer model on for the smoke test (P15-27): exactly 1 service task
# (service_desired_count = 1 from services-session.tfvars) and at most 4 Haiku-backed queries.
# bedrock_max_calls = 10 is a per-process cap and both the planner and the synthesis call Haiku,
# so 10 calls allow about 4 queries with margin, not 10. The next apply, A3,
# leaves this file out, which returns the answer model to the offline stub for D, E, F and C.
# Changing the answer model changes the task definition (an environment variable), so ECS rolls the
# query task: AwsRuntime reloads the export (about the health-check grace period).

answer_model      = "bedrock"
bedrock_max_calls = 10

# OD-1: already true through services-session.tfvars; repeated so this overlay never plans the
# services without the stack they need (the flag is a plan-time requirement of the balancer and the
# worker).
enable_services_stack = true

# P15-27: the smoke runs exactly 1 query task, so autoscaling must not add a second process with its own cap.
query_max_tasks = 1

# The Bedrock call deadline is 40 s and the code default slow deadline is 10 s, which would answer
# the HTTP caller first. 60 s is above 40 s and under the ALB idle timeout of 120 s.
slow_deadline_millis = 60000
