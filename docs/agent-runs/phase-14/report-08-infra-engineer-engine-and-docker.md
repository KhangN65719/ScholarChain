# Report 08: infra-engineer, Neptune engine version and docker commands (condensed by the orchestrator; author: infra-engineer)

STATUS: COMPLETE. Nothing blocked. No tool run (read and edit only).

1. Neptune `engine_version` is `1.4.5.1` (module default and course environment default; course.tfvars does not override). `parameter_group_family` is `neptune1.4`, matching. `1.4.5.1` appears in the human's availability log for db.t4g.medium. AZ placement in us-east-2a/b is not confirmed (the check had no AZ column).
2. "NOT CONFIRMED" notes updated to "confirmed orderable by the human read-only CLI check on 2026-11-13" in modules/neptune/variables.tf, environments/course/variables.tf, infra/terraform/README.md and COST_ESTIMATE.md (U1, U2/U3). m5.xlarge: EC2 offers it in us-east-2; EMR page not read; the first CreateCluster confirms. No cost figure changed.
3. Docker (from package.sh, deploy.sh, lib.sh): `package.sh` builds `scholarchain-services:<sha>` and `scholarchain-loader:<sha>` locally via `sbt packageJobs dockerLocal` then two `docker build`s; no AWS call, no login, no push, no ceiling or approval record. `deploy.sh` builds and tags its own ECR-named images and runs `aws ecr get-login-password | docker login --username AWS --password-stdin <registry>` after apply; the password travels only through the pipe. A `package.sh` image proves the build works but is not the image that is pushed. A `-dirty` image is never usable by deploy.sh.
4. scripts/README.md section "Phase 14: real docker login and build" (from line 294): steps 1 dry run, 2 engine and disk check, 3 real `package.sh --platform linux/amd64` build, 4 build verification, 5 ECR login with the account ID typed by the human, 6 login verification via `docker-credential-osxkeychain list` plus `docker logout`. Step 6 does not use `docker info` because it does not list ECR logins.

Risks: package.sh and the ECR login have never run. The tree is dirty so step 3 needs `--allow-dirty`. A login proves a token was issued, not push rights. The ECR login log contains the account ID and must stay out of committed evidence.

Files modified: infra/terraform/modules/neptune/variables.tf, environments/course/variables.tf, infra/terraform/README.md, COST_ESTIMATE.md, scripts/README.md. Human checks pending: terraform fmt -check (descriptions changed), bash -n, shellcheck.
