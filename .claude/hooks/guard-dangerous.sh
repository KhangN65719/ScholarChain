#!/usr/bin/env bash
# PreToolUse guard for ScholarChain. All logic lives in policy.py (`guard`); this wrapper only
# starts it. Destructive categories the guard refuses, with typical refused forms and the rule in
# policy.py that refuses each. Every rule inspects the text of an ordinary Bash tool call. None of
# them inspects what a reviewed script or an SDK call does after it starts. The settings.json
# permissions.deny list repeats some of these forms as a second layer: terraform and tofu
# apply and destroy (not terraform -chdir=<dir> destroy), git push with --force, -f or --mirror
# (not +<ref>, --delete or :<ref>), and the aws s3, s3api, dynamodb, neptune, neptunedata,
# s3vectors, ecr, kms and emr forms named in each category below. It does not cover git branch -D,
# git reset --hard, git rebase, aws ec2 terminate-* or aws s3 sync <src> <dst> --delete; the guard
# alone refuses those.
#
# 1. AWS delete and terminate
#    Refused: aws ec2 terminate-instances <id>; aws emr terminate-clusters <id>;
#             aws ecr delete-repository <name>; aws kms schedule-key-deletion <id>.
#    Rule: guard_shell, the `\baws\b` branch. Only infra-engineer passes a direct aws call, and
#          only as exactly three tokens, `aws <service> describe-*|list-*`,
#          `aws <service> get-caller-identity` or `aws <service> ls`, and never for secretsmanager
#          or ssm (policy.py:152 admits infra-engineer). Every other aws form, and every aws
#          call from any other role or the main session, is refused.
#    Not enforced here: a script or SDK client that deletes or terminates after it starts.
#
# 2. Terraform
#    Refused: terraform apply; terraform -chdir=<dir> destroy; tofu apply; tofu destroy.
#    Rule: DIRECT_DANGER, the `(terraform|tofu) ... (apply|destroy)` alternative. The
#          infra-engineer may run only terraform validate, plan, fmt -check and
#          fmt -check -recursive, and any other terraform form falls through to the final
#          refusal in guard_shell.
#
# 3. Git
#    Refused: git push --force; git push -f; git push origin +<ref>; git push --mirror;
#             git push origin --delete <ref>; git push origin :<ref>; git branch -D <name>;
#             git reset --hard; git rebase.
#    Rule: DIRECT_DANGER, the `git ... push` alternative (force, mirror, plus-refspec), and the
#          `git ... push ... (--delete|:ref|--prune)` check in guard_shell. A git branch change is
#          refused by the branch check. A local history rewrite (reset, rebase, filter-branch) has
#          no rule of its own: it is refused only because READ_GIT admits status, diff, log, show,
#          ls-files, rev-parse and branch, and every other git verb reaches the final refusal.
#
# 4. Database
#    Refused: any Bash command whose text contains DROP DATABASE, DROP TABLE, TRUNCATE TABLE or
#             DELETE FROM, in any letter case.
#    Rule: DIRECT_DANGER, the `drop (database|table)|truncate table|delete from` alternative.
#    Not enforced here: SQL inside a file that a permitted script reads, and Write or Edit
#          content. The guard checks Bash command text only.
#
# 5. S3
#    Refused: aws s3 rm <uri>; aws s3 rb <uri>; aws s3api delete-object <args>;
#             aws s3 sync <src> <dst> --delete.
#    Rule: the `\baws\b` branch of guard_shell. These forms have more than three tokens or a
#          non-metadata verb. The bare three-token `aws s3 ls` stays allowed for infra-engineer
#          only; `aws s3 ls <uri>` has four tokens and is refused for every role. settings.json
#          denies aws s3 rm, aws s3 rb and aws s3api delete-* as well.
#
# 6. Neptune
#    Refused: aws neptune delete-db-cluster <args>; aws neptunedata delete-<verb> <args>;
#             aws neptunedata cancel-<verb> <args>.
#    Rule: the `\baws\b` branch of guard_shell. The bare three-token aws neptune describe-* and
#          list-* forms stay allowed for infra-engineer only. settings.json denies aws neptune delete-*, aws neptunedata delete-* and
#          aws neptunedata cancel-* as well.
#
# 7. DynamoDB
#    Refused: aws dynamodb delete-table <args>; aws dynamodb delete-item <args>.
#    Rule: the `\baws\b` branch of guard_shell. The bare three-token aws dynamodb describe-* and
#          list-* forms stay allowed for infra-engineer only. settings.json denies aws dynamodb delete-* as well.
#
# 8. Vector index (S3 Vectors)
#    Refused: aws s3vectors delete-index <args>; aws s3vectors delete-vector-bucket <args>;
#             aws s3vectors delete-vectors <args>.
#    Rule: the `\baws\b` branch of guard_shell. settings.json denies aws s3vectors delete-* as
#          well.
#
# Related refusals outside the eight categories: sensitive paths (SENSITIVE), shell and
# interpreter wrappers, chaining, pipes and redirection, and structured writes. The structured
# write fence (policy.py:179-197) covers only Git metadata, the test marker and the constitution,
# refuses all code-reviewer writes, and confines factory-engineer, test-engineer, deploy-engineer
# and service-engineer to their paths. It is not a general per-owner path fence. See policy.py.
set -euo pipefail
command -v python3 >/dev/null || { echo "ScholarChain guard requires Python 3." >&2; exit 2; }
exec python3 -B "$(dirname "$0")/policy.py" guard
