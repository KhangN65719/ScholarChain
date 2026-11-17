# Phase 15, session 2 (services) overlay (WO-10). Values only, no secrets.
# Layer it AFTER course.tfvars and experiment-size-s.tfvars (later -var-file wins):
#   -var-file course.tfvars -var-file experiment-size-s.tfvars -var-file services-session.tfvars
# Do NOT layer graph-load.tfvars with it: the S overlay's db.t4g.medium is what moves the
# Neptune writer back down from db.r6g.xlarge (P15-5).
#
# Session 2 applies, each with its own plan, plan digest and approval record (SESSION2.md):
#   A1 course + S + graph-load                         Neptune xlarge, bulk load (loader-only)
#   A2 course + S + services-session + session2 + services-smoke
#                                                      writer back to t4g, services, worker, ALB,
#                                                      Bedrock answer model, 1 task, at most 4 queries
#   A3 course + S + services-session + session2        stub answer model: D, E, F, then C
#   A4 course + session-down + session-down-services + session2   same-day teardown
# Do not commit between A1 and A4: the image tag is the Git SHA, and A2 and A3 start tasks that
# need the image A1's deploy pushed.
#
# S overlay (already in force): Neptune on at db.t4g.medium, Bedrock on (the ninth endpoint, Titan
# query embeddings and the Haiku grant), pipeline timeouts. This file adds the services.

# OD-1: the Wave 2 resources (worker IAM role and policy, load-client ECR repository, worker and
# load-client task definitions and log groups, alb and load_client security groups, scaling
# dashboard, Container Insights) exist only with this flag. The Experiment A and B applies do not
# set it (default false), so they create none of them. It is on from A2 on and stays on through the
# teardown (session-down-services.tfvars).
enable_services_stack = true

# The default layout is split (session 1's); this overlay switches to the one query service. The
# switch destroys the fast and slow services, task definitions and log groups of the split layout.
service_layout = "combined"

# One query task (role both: fast 8080, slow 8081) of 0.5 vCPU. The AwsRuntime holds the whole S3
# export in the heap, so memory is the large part: 4096 MiB (about 3 GiB heap at the image's
# MaxRAMPercentage 75). PROVISIONAL until the local measurement of SESSION2.md sets the final value.
# 512 CPU allows 1024 to 4096 MiB, so 4096 is the largest pair at 0.5 vCPU.
service_desired_count = 1
service_cpu           = 512
service_memory        = 4096

# graph_run_id and export_run_id are deliberately NOT set here (P15-26): both come from the A run
# of the combined A + B session (runbook step 2.9), not from session 2's own run. graph_run_id (run:v1:<uuid>) is the sourceRunId of its export manifest;
# export_run_id (run-v1-<uuid>) is the A pipeline run_id slug, the directory the services read
# (semantic-context/runs/<slug>/export/). Both are required when service_desired_count is above 0
# and have no default. Pass them in /Users/winnguyen/scholarchain-data/session2.tfvars, outside the
# repository (SESSION2.md, 6a).

# Stub answer model by default (P15-4). services-smoke.tfvars flips it for the smoke apply only.
answer_model      = "stub"
bedrock_max_calls = 10

# Internal ALB (P15-3), scaling on the query service and the worker (course scaling table rows).
enable_load_balancer       = true
health_check_grace_seconds = 300
enable_autoscaling         = true
query_min_tasks            = 1
query_max_tasks            = 4
request_count_target       = 300
cpu_target_percent         = 60
p95_latency_alarm_seconds  = 2

# Embedding worker: one task to start, up to four.
enable_worker        = true
worker_desired_count = 1
worker_min_tasks     = 1
worker_max_tasks     = 4

# load_client_image_tag is deliberately NOT set here (OD-2). deploy.sh verify_revision requires every
# ACTIVE scholarchain-course* task definition family, the load-client family included, to use an
# image ending :<HEAD SHA>, and that check is not weakened. The tag is therefore the 40-hex Git SHA
# of the commit the session runs from, which no file can carry before the commit exists: it goes in
# /Users/winnguyen/scholarchain-data/session2.tfvars beside the two run IDs (SESSION2.md, 6a), and
# the human pushes the load-client image under that same tag. The variable refuses any other value
# while enable_services_stack is on.
