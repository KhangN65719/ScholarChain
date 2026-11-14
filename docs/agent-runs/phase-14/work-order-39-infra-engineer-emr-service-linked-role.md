# Work order 39: infra-engineer, create the EMR service-linked role in Terraform

Owner: infra-engineer (infra/terraform/**, scripts/**). Edits in those paths only. No AWS, no apply, no commit. Do not read ~/.aws. The guard blocks agent grep, find, terraform, bash -n and shellcheck; the human runs those.

Evidence (human `!` run, capability-check launch 1, 2026-11-14): Step Functions execution `run-20261114T211645Z-small` failed at CreateCluster after 63 s. The EMR cluster went `TERMINATED_WITH_ERRORS`, code `VALIDATION_ERROR`, message: "Service-linked role 'AWSServiceRoleForEMRCleanup' for EMR is required. Please create this role directly or add permission to create it in your IAM entity." Zero instance hours, no cost. The account is new and has never run EMR; Neptune, ECS and S3 Vectors created their own service-linked roles. The human chose (AskUserQuestion) to fix it in Terraform, not with a one-off CLI command.

Tasks:
1. Add `aws_iam_service_linked_role` with `aws_service_name = "elasticmapreduce.amazonaws.com"` (this creates `AWSServiceRoleForEMRCleanup`) in the module where it fits best (identity or emr; your call, say why). Constraints:
   - It must NOT be destroyed by the session-down flow (`session-down.tfvars`) and must not depend on a flag that session-down flips (enable_pipeline, EMR counts, neptune). Read session-down.tfvars, cleanup.tfvars and the module conditionals to confirm. Tell me what cleanup.sh apply-destroy does with it (a service-linked role is account-level and may be shared; say whether destroying it at the end of the project is acceptable or whether it should be left in place).
   - If the role could already exist in some account, say how the apply would fail and how to recover (import); in this account it does not exist (the error above).
   - Check whether the emr-7.10.0 on-demand, no-Spot, managed-scaling path needs any other first-use service-linked role (for example `AWSServiceRoleForEC2Spot` only matters for Spot; managed scaling) and report, do not add anything beyond the EMR role without telling me.
2. The identity used to apply (the human's AdministratorAccess profile) can create it. Check the `deployer` role policy in the identity module for `iam:CreateServiceLinkedRole`: no change needed unless the deployer runs this apply (it does not: apply uses the human's credentials, see "Who runs what"); report only.
3. Update infra/terraform/README.md and COST_ESTIMATE.md only where they list created resources or IAM (a service-linked role costs nothing). Expected plan: 1 add, 0 change, 0 destroy; say what else a fresh plan from the deployed state might show (the S overlay is applied; the deployed revision is 9359cfb, the commit before this change; the image tag in the plan will change to the new HEAD, which makes the loader and services task definitions replace again: report that as expected).
4. Keep `terraform fmt` alignment; the human reruns fmt -check and validate.

Report contract: STATUS, OWNER, WORK ORDER, FILES MODIFIED, COMMANDS RUN, VALIDATION RESULTS (state what is human-run and unrun), IAM DIFF, RISKS, NEXT GATE. Your final message must be the complete report text.
