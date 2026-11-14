# Checks 5: plans, deploy and session-down (human-run, 2026-11-14 UTC; recorded by the orchestrator from pasted output)

Account ID omitted. Logs: /Users/winnguyen/scholarchain-data/logs/phase14-plan-{1,2,3}.log, phase14-plan-4-session-down.log, phase14-deploy-dryrun-{3,4}.log, phase14-deploy-3.log, phase14-deploy-4-session-down.log. Plans and approval records stay outside the repository.

## Plans
- Plan 1 (dirty tree, review only): 132 add, 0 change, 0 destroy. Plan 2 (git 4a5bbf5, clean): same counts. Plan 3 (git 3c0ad139, clean, digest 7aedb32c514660b811a215ff00cb3a3187b6ac85dad347addd7fc943693ca079): 132 add, 0 change, 0 destroy, STATEFUL_CHANGE=no. Foundation only; Neptune and EMR off; ECS services desired count 0; 8 interface endpoints in 1 AZ.

## Deploy 1 (foundation), record deploy-plan3, plan digest 7aedb32c...
- Dry run: APPROVAL_VERIFIED, APPROVAL_SKIPPED_FIELDS=none, DRY_RUN_COMPLETE.
- Real run: DEPLOY_COMPLETE git_sha=3c0ad139..., DEPLOYED_REVISION=3c0ad1398a30f9d39a3c02e9fd3b64e87dc52dfb. Images pushed: scholarchain-course-loader digest sha256:10311b1bda0ef62fe9e996bbb64023aced3d6ea20ed86020aca34ef36f71c595, scholarchain-course-services digest sha256:3c20281f2aebb986cc8d0fb8d0710794bd785dc693588b430f3766987e6e5e77. REVISION_OK for the fast, slow and loader task definitions and both services. The apply output itself scrolled off the screen and is in the log. First apply of S3 Vectors and the account passed (R2 resolved by the apply; confirm the vector bucket and 5 indexes from the log).
- Build contexts were `target/docker-local` for both (same content digest 251c3f67...; the loader image is the same bytes as the services image, so the loader entry point is still unverified).

## Session-down, plan 4 (course.tfvars plus session-down.tfvars), plan digest 0ab7fca145bde8600804e38f7cb1f0fb32125de8bc4724f1c33f795a09f57d8a
- Plan: 0 add, 2 change, 8 destroy, STATEFUL_CHANGE=no. The 8 destroys are the interface endpoints (ecr.api, ecr.dkr, elasticmapreduce, logs, s3vectors, secretsmanager, sqs, sts), confirmed by the human grep. No storage, repository, secret or vector resource destroyed.
- Unplanned finding: the 2 in-place changes are `aws_s3_bucket_server_side_encryption_configuration` on the artifacts and data buckets. AWS had added `blocked_encryption_types = ["SSE-C"]` by default; Terraform sets `[]`, so the apply removes the SSE-C block (AES256 and bucket key unchanged). Human accepted this as a temporary drift (AskUserQuestion, 2026-11-14); fix routed to infra-engineer.
- Real run: apply ok (`egress_mode = "none"`), ECR login ok, images reused (immutable tag), DEPLOY_COMPLETE plan_digest=0ab7fca1..., DEPLOYED_REVISION=3c0ad139.... The apply summary line (`Apply complete! Resources: ...`) is in the log, not seen on screen.
- Idle billing after session-down: endpoints gone; remaining items are storage, secrets (3), alarms, ECR images. Actual spend: pending the human's Billing figure.

## Not done tonight
- Fixture smoke test and the S real-data run: blocked, see decision D13.
