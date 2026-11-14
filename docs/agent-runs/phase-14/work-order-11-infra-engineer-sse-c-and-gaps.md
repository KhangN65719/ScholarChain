# Work order 11: infra-engineer, SSE-C drift fix and the infrastructure gaps before smoke and the S run

Owner: infra-engineer (infra/terraform/**, scripts/**). Local only: no AWS, no terraform plan or apply, no commit. Read-only analysis plus edits in your paths.

Evidence: docs/agent-runs/phase-14/checks-5.md, decisions-d13-d14.md, scripts/README.md ("Smoke-test network requirement", package.sh gaps).

Part A, edit (D14): the session-down plan showed `aws_s3_bucket_server_side_encryption_configuration` on the artifacts and data buckets changing from AWS's default `blocked_encryption_types = ["SSE-C"]` to `[]`. Set `blocked_encryption_types = ["SSE-C"]` in modules/data_lake (check the provider version in the lock file supports the argument; if not, report BLOCKED-ON with the provider version). Keep AES256 and the bucket key. Update the module README or COST/README note if one mentions the encryption rule. List files for the human's `terraform fmt -check`, `init -backend=false` and `validate`.

Part B, report only (no edits; the human decides, each option needs a cost and a risk):
1. Smoke-test network path (M9): options for reaching the services from the human's laptop or a task in the VPC, each with the extra Terraform needed, hourly cost at the estimate's rates, and what it does to the idle bill. Recommend one. Services are at desired count 0 in course.tfvars; say what turns them on and the cost of a running task.
2. Getting data and jars to S3 for the S run: what exact object keys the pipeline workflow (modules/workflow, run-bounded-pipeline.sh input contract `{hadoop_args, spark_args, loader_command}`) and the loader expect in the data and artifacts buckets, and what upload path fits the rules (Terraform only; no console; no script today uploads). Propose a reviewed, approval-record-gated script or a documented `aws s3 cp` human command with a cost (data transfer, requests) and an object-count and size bound.
3. The session-up plan: which tfvars overlay (experiment-size-s.tfvars) and what it adds (Neptune, EMR, endpoints); expected plan counts if readable from the source; the hourly burn and the cap needed.

Do not read ~/.aws. You cannot run bash, terraform or shellcheck (guard D11): state that nothing was executed.

Report contract: STATUS, OWNER, WORK ORDER, FILES MODIFIED, COMMANDS RUN, FINDINGS A and B (with options and a recommendation), RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, NEXT GATE.
