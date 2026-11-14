# Work order 08: infra-engineer, Neptune engine version and docker login/build commands

Owner: infra-engineer. Local only: no AWS calls, no credentials, no docker run, no sbt, no commit. Read-only analysis plus edits only if a defect is found in what you own.

Evidence: docs/agent-runs/phase-14/checks-3.md. Neptune `db.t4g.medium` is orderable in us-east-2 for engine versions 1.1.1.0, 1.1.2.0, 1.2.0.0 to 1.2.1.3, 1.3.0.0 to 1.3.5.0, 1.4.0.0 to 1.4.8.1, 1.5.0.0 and 1.5.0.1.

1. Report the Neptune `engine_version` (and parameter group family) set in infra/terraform (module neptune and the course environment). Say whether it is in the orderable list and whether the parameter group family matches it. If it is not, do not guess a replacement: report `BLOCKED-ON` human decision with the options.
2. Update the "NOT CONFIRMED for us-east-2" note in modules/neptune/variables.tf and the README/COST_ESTIMATE lines that depend on it to say: confirmed orderable by a human read-only CLI check on 2026-11-13 (checks-3.md). EMR `m5.xlarge`: EC2 offers it in us-east-2; EMR page not read; first CreateCluster confirms. Do not change any cost figure.
3. Docker (Phase 13 required item): read scripts/package.sh and scripts/deploy.sh and report exactly how an image is built, tagged (from the Git commit SHA), and how and when a registry login happens (what is piped to `docker login --password-stdin`, which variables hold it, what is never printed). Then write the paste-ready human commands, as a section "Phase 14: real docker login and build" in scripts/README.md:
   - a real `docker build` through `scripts/package.sh` from a terminal (platform linux/amd64 unless the script says otherwise), with the cost ceiling and approval requirements the script has. State what this needs on the machine (Docker running, disk) and that it makes no AWS calls.
   - a real `docker login --password-stdin` against the ECR registry for the account in us-east-2, taking the password from `aws ecr get-login-password --region us-east-2 --profile scholarchain` piped straight in so it never reaches a file or the screen. Note that ECR login needs the registry to exist only as an account endpoint (repositories are created by Terraform later), and that the account ID comes from the human, not from the repository.
   - how to verify without printing credentials (`docker info` login line, or a failing/succeeding `docker manifest inspect` is not needed).
   Every command: `set -o pipefail`, logs tee'd to /Users/winnguyen/scholarchain-data/logs/, ending `| tail -n 60; echo "exit: $?"`. Never print credentials.
4. List files for the human's `bash -n` and `shellcheck -x` and `terraform fmt -check -recursive infra/terraform` (guard blocks them for agents).

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE.
