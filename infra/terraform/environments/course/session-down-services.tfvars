# Phase 15, session 2 teardown (A4) companion of session-down.tfvars (OD-1). Values only, no secrets.
# Layer it AFTER session-down.tfvars, and add the run-ID file, whose load_client_image_tag the
# stack's validation needs:
#   -var-file course.tfvars -var-file session-down.tfvars -var-file session-down-services.tfvars \
#   -var-file /Users/winnguyen/scholarchain-data/session2.tfvars
#
# Why it exists. session-down.tfvars leaves enable_services_stack at false and service_layout at
# split, which is right after the Experiment A and B sessions (their state holds neither). After
# session 2 the state holds the services stack and the combined layout, so session-down.tfvars alone
# would plan to destroy the worker IAM role and policy, the load-client ECR repository (the destroy
# fails while it holds images), the worker and load-client log groups and the query log group with
# their events, the security groups and the scaling dashboard, and to replace the query service with
# fast and slow. This overlay keeps all of them: with it the A4 plan matches the A3 resource set
# except for what session-down.tfvars turns off (ALB, scaling, worker service, Neptune, endpoints,
# query task count).
#
# Never use it after A and B: it would create the stack (a new IAM role and policy).

enable_services_stack = true
service_layout        = "combined"
