terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# Roles, in one place so the privilege diff can be reviewed together:
#   emr_service     EMR service role (AWS-managed policy, see note below)
#   emr_ec2         EMR cluster nodes: S3 only
#   ecs_execution   Fargate agent: pull images, write logs, read three secrets
#   runtime         fast and slow services: read-mostly
#   loader          load task: write DynamoDB, S3 Vectors, Neptune loader
#   sfn             Step Functions: run EMR and ECS for the pipeline
#   deployer        deployment scripts (optional, trusts human principals)
# The Neptune bulk load role lives in the neptune module.
#
# Why emr_cluster_arn below is cluster/* (finding L3): the cluster ID (j-XXXX) is
# assigned by EMR when the state machine's RunJobFlow call creates the cluster, so
# it cannot be known when this policy is written, and the ARN cannot be named any
# other way. Blast radius: describe, terminate, add-steps, cancel-steps, list-steps
# and add-tags on any EMR cluster in this account and region. The account is
# dedicated to the project (decision D4), so that set holds only project clusters.
# A tag condition (aws:ResourceTag) is possible but untested and not added: a
# wrong condition would block the cleanup that stops billing.
#
# Encryption choice: every service uses its AWS-owned or AWS-managed key
# (S3 SSE-S3, DynamoDB owned key, SQS-managed SSE, Neptune aws/rds, Secrets
# Manager aws/secretsmanager, ECR AES256). No customer KMS key is created: each
# costs about $1 a month and the course does not require one. Consequence: no
# kms:* permissions appear below. A persistent environment should revisit this.
locals {
  arn_base = "arn:aws"

  log_group_arn        = "${local.arn_base}:logs:${var.region}:${var.account_id}:log-group:/${var.name_prefix}/*"
  ecr_repository_arn   = "${local.arn_base}:ecr:${var.region}:${var.account_id}:repository/${var.name_prefix}-*"
  ecs_cluster_arn      = "${local.arn_base}:ecs:${var.region}:${var.account_id}:cluster/${var.name_prefix}-cluster"
  ecs_taskdef_arn      = "${local.arn_base}:ecs:${var.region}:${var.account_id}:task-definition/${var.name_prefix}-*"
  ecs_task_arn         = "${local.arn_base}:ecs:${var.region}:${var.account_id}:task/${var.name_prefix}-cluster/*"
  ecs_service_arn      = "${local.arn_base}:ecs:${var.region}:${var.account_id}:service/${var.name_prefix}-cluster/${var.name_prefix}-*"
  state_machine_arn    = "${local.arn_base}:states:${var.region}:${var.account_id}:stateMachine:${var.name_prefix}-*"
  execution_arn        = "${local.arn_base}:states:${var.region}:${var.account_id}:execution:${var.name_prefix}-*:*"
  emr_cluster_arn      = "${local.arn_base}:elasticmapreduce:${var.region}:${var.account_id}:cluster/*"
  events_rule_prefix   = "${local.arn_base}:events:${var.region}:${var.account_id}:rule"
  run_requests_queue   = "${local.arn_base}:sqs:${var.region}:${var.account_id}:${var.name_prefix}-run-requests"
  embedding_work_queue = "${local.arn_base}:sqs:${var.region}:${var.account_id}:${var.name_prefix}-embedding-work"

  neptune_arn = var.neptune_enabled ? "${local.arn_base}:neptune-db:${var.region}:${var.account_id}:${var.neptune_cluster_resource_id}/*" : null

  runtime_read_tables  = [for k in ["run-ledger", "artifact-registry", "researcher-metric-cache", "entity-link-cache", "evidence-bundle"] : var.table_arns[k]]
  runtime_write_tables = [for k in ["query-session", "evidence-bundle"] : var.table_arns[k]]
  loader_tables        = [for k in ["run-ledger", "artifact-registry", "researcher-metric-cache"] : var.table_arns[k]]

  secret_names = toset(["auth-query-token", "auth-read-token", "auth-cursor-secret"])

  bedrock_all_arns = distinct(concat(var.bedrock_model_arns, var.bedrock_embedding_model_arns))
}

# Secrets Manager entries are created with no value. A human puts the value with
# the console-free path in the Phase 14 runbook. Terraform never sees it.
resource "aws_secretsmanager_secret" "this" {
  for_each = local.secret_names

  name                    = "${var.name_prefix}/${each.key}"
  description             = "ScholarChain ${each.key}. Value is set outside Terraform."
  recovery_window_in_days = var.secret_recovery_days
}

# EMR service role. AmazonEMRServicePolicy_v2 is AWS-managed and cannot be
# narrowed here. It acts only on resources tagged
# for-use-with-amazon-emr-managed-policies, which the network module sets on the
# VPC, app subnets and EMR security groups.
data "aws_iam_policy_document" "emr_service_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["elasticmapreduce.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

resource "aws_iam_role" "emr_service" {
  name               = "${var.name_prefix}-emr-service"
  assume_role_policy = data.aws_iam_policy_document.emr_service_trust.json
}

resource "aws_iam_role_policy_attachment" "emr_service" {
  role       = aws_iam_role.emr_service.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEMRServicePolicy_v2"
}

# Work order 40: AmazonEMRServicePolicy_v2 allows iam:PassRole only for a role named
# EMR_EC2_DefaultRole. Ours is named ${name_prefix}-emr-ec2, so the service role
# needs this narrow grant to pass it to EC2 when it launches the nodes. Same
# PassedToService condition as the managed policy's PassRoleForEC2 statement.
data "aws_iam_policy_document" "emr_service_pass_node" {
  statement {
    sid       = "PassNodeRoleToEc2"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.emr_ec2.arn]

    condition {
      test     = "StringLike"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com*"]
    }
  }
}

resource "aws_iam_role_policy" "emr_service_pass_node" {
  name   = "pass-node-role"
  role   = aws_iam_role.emr_service.id
  policy = data.aws_iam_policy_document.emr_service_pass_node.json
}

# EMR service-linked role (AWSServiceRoleForEMRCleanup). EMR needs it before the
# first cluster in an account: the first launch of the capability check failed at
# CreateCluster with VALIDATION_ERROR because the role did not exist and the
# caller (the sfn role) may not create it. It is account-level, unconditional and
# independent of enable_pipeline, EMR counts and Neptune, so the session-down
# overlay leaves it in place. If the role already exists in an account, apply fails with InvalidInput "Service
# role name AWSServiceRoleForEMRCleanup has been taken in this account"; recover
# with `terraform import 'module.identity.aws_iam_service_linked_role.emr' <role ARN>`.
# Full teardown (cleanup.sh) destroys it with the rest of state; see README.
resource "aws_iam_service_linked_role" "emr" {
  aws_service_name = "elasticmapreduce.amazonaws.com"
}

# EMR cluster nodes: S3 only. They read raw and curated data and the job jars,
# and write the Hadoop and Spark outputs and cluster logs.
data "aws_iam_policy_document" "ec2_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "emr_ec2" {
  statement {
    sid       = "ListBuckets"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [var.data_bucket_arn, var.artifacts_bucket_arn]
  }

  statement {
    sid     = "ReadInputsAndJars"
    actions = ["s3:GetObject"]
    resources = [
      "${var.data_bucket_arn}/raw/*",
      "${var.data_bucket_arn}/curated/*",
      "${var.data_bucket_arn}/graph/*",
      "${var.artifacts_bucket_arn}/jars/*",
      "${var.artifacts_bucket_arn}/bootstrap/*",
    ]
  }

  statement {
    sid     = "WriteJobOutputs"
    actions = ["s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload"]
    resources = [
      "${var.data_bucket_arn}/curated/*",
      "${var.data_bucket_arn}/graph/*",
      "${var.data_bucket_arn}/semantic-context/*",
      "${var.data_bucket_arn}/reports/*",
      "${var.data_bucket_arn}/tmp/*",
      "${var.artifacts_bucket_arn}/emr-logs/*",
      "${var.artifacts_bucket_arn}/tmp/*",
    ]
  }
}

resource "aws_iam_role" "emr_ec2" {
  name               = "${var.name_prefix}-emr-ec2"
  assume_role_policy = data.aws_iam_policy_document.ec2_trust.json
}

resource "aws_iam_role_policy" "emr_ec2" {
  name   = "s3-only"
  role   = aws_iam_role.emr_ec2.id
  policy = data.aws_iam_policy_document.emr_ec2.json
}

resource "aws_iam_instance_profile" "emr_ec2" {
  name = "${var.name_prefix}-emr-ec2"
  role = aws_iam_role.emr_ec2.name
}

# ECS task execution role (the Fargate agent, not the application).
data "aws_iam_policy_document" "ecs_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

data "aws_iam_policy_document" "ecs_execution" {
  # GetAuthorizationToken does not support resource-level permissions.
  statement {
    sid       = "EcrToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "EcrPull"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
    resources = [local.ecr_repository_arn]
  }

  statement {
    sid       = "TaskLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [local.log_group_arn]
  }

  statement {
    sid       = "ReadInjectedSecrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [for s in aws_secretsmanager_secret.this : s.arn]
  }
}

resource "aws_iam_role" "ecs_execution" {
  name               = "${var.name_prefix}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_trust.json
}

resource "aws_iam_role_policy" "ecs_execution" {
  name   = "pull-log-secrets"
  role   = aws_iam_role.ecs_execution.id
  policy = data.aws_iam_policy_document.ecs_execution.json
}

# Runtime readers: the fast and slow services.
data "aws_iam_policy_document" "runtime" {
  statement {
    sid       = "ListReadPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [var.data_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["curated/*", "graph/*", "semantic-context/*", "reports/*"]
    }
  }

  statement {
    sid     = "ReadEvidenceObjects"
    actions = ["s3:GetObject"]
    resources = [
      "${var.data_bucket_arn}/curated/*",
      "${var.data_bucket_arn}/graph/*",
      "${var.data_bucket_arn}/semantic-context/*",
      "${var.data_bucket_arn}/reports/*",
    ]
  }

  statement {
    sid     = "ReadTables"
    actions = ["dynamodb:GetItem", "dynamodb:Query"]
    resources = concat(
      local.runtime_read_tables,
      [for arn in local.runtime_read_tables : "${arn}/index/*"],
      [var.table_arns["query-session"]],
    )
  }

  statement {
    sid       = "WriteSessionsAndBundles"
    actions   = ["dynamodb:PutItem"]
    resources = local.runtime_write_tables
  }

  statement {
    sid       = "QueryVectors"
    actions   = ["s3vectors:GetIndex", "s3vectors:QueryVectors", "s3vectors:GetVectors"]
    resources = var.vector_index_arns
  }

  statement {
    sid       = "EnqueueRunRequests"
    actions   = ["sqs:SendMessage", "sqs:GetQueueAttributes"]
    resources = [local.run_requests_queue]
  }

  # neptune-db:QueryLanguage is a String condition key (values Gremlin, OpenCypher,
  # Sparql) supported on ReadDataViaQuery, WriteDataViaQuery, DeleteDataViaQuery,
  # GetQueryStatus and CancelQuery, and not on GetEngineStatus, which therefore sits in
  # its own statement without the condition. Verified 2026-11-17:
  #   https://docs.aws.amazon.com/neptune/latest/userguide/iam-data-condition-keys.html
  #   https://docs.aws.amazon.com/neptune/latest/userguide/data-api-dp-queries.html
  # The runtime reads the graph with openCypher only. UNVERIFIED until a live run: that
  # GetQueryStatus is authorized with the key populated for an openCypher query.
  dynamic "statement" {
    for_each = var.neptune_enabled ? [1] : []

    content {
      sid       = "NeptuneReadOpenCypher"
      actions   = ["neptune-db:ReadDataViaQuery", "neptune-db:GetQueryStatus"]
      resources = [local.neptune_arn]

      condition {
        test     = "StringEquals"
        variable = "neptune-db:QueryLanguage"
        values   = ["OpenCypher"]
      }
    }
  }

  dynamic "statement" {
    for_each = var.neptune_enabled ? [1] : []

    content {
      sid       = "NeptuneEngineStatus"
      actions   = ["neptune-db:GetEngineStatus"]
      resources = [local.neptune_arn]
    }
  }

  dynamic "statement" {
    for_each = var.enable_bedrock ? [1] : []

    content {
      sid       = "BedrockInvokeExactModels"
      actions   = ["bedrock:InvokeModel"]
      resources = local.bedrock_all_arns
    }
  }
}

resource "aws_iam_role" "runtime" {
  name               = "${var.name_prefix}-runtime"
  assume_role_policy = data.aws_iam_policy_document.ecs_trust.json
}

resource "aws_iam_role_policy" "runtime" {
  name   = "runtime-read"
  role   = aws_iam_role.runtime.id
  policy = data.aws_iam_policy_document.runtime.json
}

# Loader: the only role that writes DynamoDB ledger, registry and cache, S3
# Vectors and starts Neptune bulk loads. Index creation is Terraform's job, so
# CreateIndex is not granted.
#
# Work order 31: the statements below are matched to the SDK calls of the AWS
# adapters in modules/storage (read from AwsObjectStore, AwsVectorBucket,
# AwsDynamoTable, AwsNeptuneBulkLoader and TitanEmbedder):
#   S3        PutObject (conditional, If-None-Match), HeadObject, GetObject,
#             ListObjectsV2. HeadObject needs s3:GetObject on the key.
#   S3 Vectors GetIndex, PutVectors, GetVectors, ListVectors, QueryVectors, all on
#             the index ARN. ListIndexes and CreateIndex are not called.
#   DynamoDB  PutItem, GetItem, Query (no UpdateItem, no batch calls, no scan).
#             Condition expressions need no extra action.
#   Neptune   StartLoaderJob, GetLoaderJobStatus (data API); the others in the
#             statement are the work order's list, not called by the adapter.
#   Bedrock   InvokeModel on the Titan V2 foundation-model ARN.
#
# Prefixes the loader may touch. semantic-context is read (the export) and, per
# the work order, written. graph/neptune holds the load CSV files and
# graph/neptune-manifest the manifest (the manifest is read by the loader, never
# by Neptune). reports/runs/<run_id>/loader/ and logs/ are written. Every prefix
# the loader PUTs it must also be able to GET, because a conditional put that
# loses the race (HTTP 412) calls head(key) on the same key, and HeadObject is
# authorized by s3:GetObject.
locals {
  loader_prefixes = ["semantic-context", "graph/neptune", "graph/neptune-manifest", "reports", "logs"]
}

data "aws_iam_policy_document" "loader" {
  # StringLikeIfExists, not StringLike. A HeadObject on a missing key returns 404
  # only to a caller that holds s3:ListBucket; otherwise S3 returns 403 and the
  # adapter raises AwsAccessDenied. HeadObject carries no s3:prefix context key, so
  # a plain StringLike condition would not match and the HEAD would see 403. With
  # IfExists the statement applies when the key is absent (HEAD) and constrains the
  # prefix of every ListObjectsV2 call that does send one. Side effect: a list
  # call that sends no prefix at all is also allowed, which exposes key names
  # (not contents) of the whole data bucket. Contents stay limited by GetObject.
  # UNVERIFIED until a live run: that HeadObject sends no s3:prefix.
  statement {
    sid       = "ListLoadPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [var.data_bucket_arn]

    condition {
      test     = "StringLikeIfExists"
      variable = "s3:prefix"
      values   = [for p in local.loader_prefixes : "${p}/*"]
    }
  }

  statement {
    sid       = "ReadLoadInputs"
    actions   = ["s3:GetObject"]
    resources = [for p in local.loader_prefixes : "${var.data_bucket_arn}/${p}/*"]
  }

  statement {
    sid       = "WriteLoaderOutputs"
    actions   = ["s3:PutObject"]
    resources = [for p in local.loader_prefixes : "${var.data_bucket_arn}/${p}/*"]
  }

  statement {
    sid     = "WriteLoaderTables"
    actions = ["dynamodb:PutItem", "dynamodb:GetItem", "dynamodb:Query"]
    resources = concat(
      local.loader_tables,
      [for arn in local.loader_tables : "${arn}/index/*"],
    )
  }

  # ListVectors pages the count; QueryVectors is for the loader's own check. With
  # metadata or data returned, ListVectors and QueryVectors also need GetVectors
  # (held). All five are index-level actions, so the bucket ARN is not needed.
  # UNVERIFIED: that S3 Vectors authorizes these actions on the index ARN alone
  # (read from the runtime role's existing use, not from the IAM reference).
  statement {
    sid = "WriteVectors"
    actions = [
      "s3vectors:GetIndex",
      "s3vectors:PutVectors",
      "s3vectors:GetVectors",
      "s3vectors:ListVectors",
      "s3vectors:QueryVectors",
    ]
    resources = var.vector_index_arns
  }

  dynamic "statement" {
    for_each = var.neptune_enabled ? [1] : []

    content {
      sid = "NeptuneBulkLoad"
      actions = [
        "neptune-db:StartLoaderJob",
        "neptune-db:GetLoaderJobStatus",
        "neptune-db:ListLoaderJobs",
        "neptune-db:CancelLoaderJob",
        "neptune-db:ReadDataViaQuery",
      ]
      resources = [local.neptune_arn]
    }
  }

  dynamic "statement" {
    for_each = var.enable_bedrock ? [1] : []

    content {
      sid       = "BedrockEmbeddingsOnly"
      actions   = ["bedrock:InvokeModel"]
      resources = var.bedrock_embedding_model_arns
    }
  }
}

resource "aws_iam_role" "loader" {
  name               = "${var.name_prefix}-loader"
  assume_role_policy = data.aws_iam_policy_document.ecs_trust.json
}

resource "aws_iam_role_policy" "loader" {
  name   = "loader-write"
  role   = aws_iam_role.loader.id
  policy = data.aws_iam_policy_document.loader.json
}

# Phase 15 (WO-10): the embedding worker (scholarchain.storage.EmbeddingWorkerMain).
# Matched to the WO-7 report and the EmbeddingWorker source, not to a live run:
#   SQS       ReceiveMessage, DeleteMessage, ChangeMessageVisibility, GetQueueAttributes
#             on the embedding-work queue only (not its dead-letter queue: the queue's
#             redrive policy moves a poison message, the worker never sends one).
#   DynamoDB  GetItem and PutItem on the run-ledger table (the EMBEDWORK# ledger items:
#             get, conditional put-if-absent and conditional put-if-version).
#   S3 Vectors GetIndex, GetVectors, PutVectors on the index ARNs (the worker checks for
#             an existing identical vector, then writes).
#   S3        GetObject and PutObject under semantic-context/documents/ (the chunk
#             document, so textRef resolves). The prefix is from the WO-7 report and is
#             UNVERIFIED against the object key the code builds: a wrong prefix is an
#             AccessDenied on the first message.
#   Bedrock   InvokeModel on the Titan V2 foundation-model ARN, only with enable_bedrock.
# No Neptune, no secrets, no Haiku, no write to any other table or prefix.
data "aws_iam_policy_document" "worker" {
  statement {
    sid = "ConsumeEmbeddingWork"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueAttributes",
    ]
    resources = [local.embedding_work_queue]
  }

  statement {
    sid       = "ChunkLedger"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem"]
    resources = [var.table_arns["run-ledger"]]
  }

  statement {
    sid       = "WriteVectors"
    actions   = ["s3vectors:GetIndex", "s3vectors:GetVectors", "s3vectors:PutVectors"]
    resources = var.vector_index_arns
  }

  # StringLikeIfExists for the same reason as the loader's list statement: a HeadObject
  # on a missing key is a 403 without s3:ListBucket, and HeadObject sends no s3:prefix.
  statement {
    sid       = "ListDocumentPrefix"
    actions   = ["s3:ListBucket"]
    resources = [var.data_bucket_arn]

    condition {
      test     = "StringLikeIfExists"
      variable = "s3:prefix"
      values   = ["semantic-context/documents/*"]
    }
  }

  statement {
    sid       = "ChunkDocuments"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${var.data_bucket_arn}/semantic-context/documents/*"]
  }

  dynamic "statement" {
    for_each = var.enable_bedrock ? [1] : []

    content {
      sid       = "BedrockEmbeddingsOnly"
      actions   = ["bedrock:InvokeModel"]
      resources = var.bedrock_embedding_model_arns
    }
  }
}

# The role and its policy exist only with enable_worker_role (OD-1): the Experiment A and B
# applies are IAM_CHANGES none, so a new principal waits for session 2. The policy
# document above is only data and plans no change.
resource "aws_iam_role" "worker" {
  count = var.enable_worker_role ? 1 : 0

  name               = "${var.name_prefix}-worker"
  assume_role_policy = data.aws_iam_policy_document.ecs_trust.json
}

resource "aws_iam_role_policy" "worker" {
  count = var.enable_worker_role ? 1 : 0

  name   = "worker-embed"
  role   = aws_iam_role.worker[0].id
  policy = data.aws_iam_policy_document.worker.json
}

# Step Functions: bounded pipeline of EMR (Hadoop, Spark) then the loader task.
data "aws_iam_policy_document" "sfn_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

data "aws_iam_policy_document" "sfn" {
  # RunJobFlow cannot be limited to a resource. The roles it may pass are limited
  # below, and the cluster definition is fixed in the state machine.
  statement {
    sid       = "EmrCreate"
    actions   = ["elasticmapreduce:RunJobFlow"]
    resources = ["*"]
  }

  statement {
    sid = "EmrManageClusters"
    actions = [
      "elasticmapreduce:DescribeCluster",
      "elasticmapreduce:TerminateJobFlows",
      "elasticmapreduce:AddJobFlowSteps",
      "elasticmapreduce:DescribeStep",
      "elasticmapreduce:CancelSteps",
      "elasticmapreduce:ListSteps",
      "elasticmapreduce:AddTags",
    ]
    resources = [local.emr_cluster_arn]
  }

  # Work order 41: scoped AddTags for the tag RunJobFlow sends with Tags. Whether
  # RunJobFlow with Tags needs elasticmapreduce:AddTags is UNVERIFIED, so it is
  # granted here rather than found out at launch. RequestTag StringEquals is needed
  # beside ForAllValues, which is true for an empty key set. EmrManageClusters above
  # already allows AddTags on cluster/* unconditionally, so this statement adds no
  # effective permission (statements are ORed).
  statement {
    sid       = "EmrTagClusterForManagedPolicy"
    actions   = ["elasticmapreduce:AddTags"]
    resources = [local.emr_cluster_arn]

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/for-use-with-amazon-emr-managed-policies"
      values   = ["true"]
    }

    condition {
      test     = "ForAllValues:StringEquals"
      variable = "aws:TagKeys"
      values   = ["for-use-with-amazon-emr-managed-policies"]
    }
  }

  statement {
    sid     = "PassEmrRoles"
    actions = ["iam:PassRole"]
    resources = [
      aws_iam_role.emr_service.arn,
      aws_iam_role.emr_ec2.arn,
    ]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["elasticmapreduce.amazonaws.com", "ec2.amazonaws.com"]
    }
  }

  statement {
    sid       = "EcsRunLoader"
    actions   = ["ecs:RunTask"]
    resources = [local.ecs_taskdef_arn]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [local.ecs_cluster_arn]
    }
  }

  statement {
    sid       = "EcsManageTasks"
    actions   = ["ecs:StopTask", "ecs:DescribeTasks"]
    resources = [local.ecs_task_arn]
  }

  statement {
    sid       = "PassEcsRoles"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.ecs_execution.arn, aws_iam_role.loader.arn]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  # Managed EventBridge rules that Step Functions creates for .sync integrations.
  # VERIFIED (docs.aws.amazon.com/step-functions/latest/dg/connect-ecs.html): the
  # ECS rule is StepFunctionsGetEventsForECSTaskRule, with "Events" in the plural,
  # and needs events:PutTargets, events:PutRule and events:DescribeRule.
  # UNVERIFIED: the two EMR names below (RunJobFlow and AddJobFlowSteps) are kept
  # from the earlier draft and were not re-read this round.
  # UNVERIFIED: whether elasticmapreduce:terminateCluster.sync uses an EMR managed
  # rule. The documented terminateCluster policy lists TerminateJobFlows and
  # DescribeCluster only, no EventBridge action, and the docs say missing events
  # permissions on a .sync task cause a delay before Step Functions notices the
  # task finished, not an AccessDenied (connect-emr.html and
  # service-integration-iam-templates.html). No rule name is invented for it.
  statement {
    sid     = "SyncIntegrationRules"
    actions = ["events:PutTargets", "events:PutRule", "events:DescribeRule"]
    resources = [
      "${local.events_rule_prefix}/StepFunctionsGetEventForEMRRunJobFlowRule",
      "${local.events_rule_prefix}/StepFunctionsGetEventForEMRAddJobFlowStepsRule",
      "${local.events_rule_prefix}/StepFunctionsGetEventsForECSTaskRule",
    ]
  }

  # Log delivery APIs do not support resource-level permissions.
  statement {
    sid = "StateMachineLogDelivery"
    actions = [
      "logs:CreateLogDelivery",
      "logs:GetLogDelivery",
      "logs:UpdateLogDelivery",
      "logs:DeleteLogDelivery",
      "logs:ListLogDeliveries",
      "logs:PutResourcePolicy",
      "logs:DescribeResourcePolicies",
      "logs:DescribeLogGroups",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role" "sfn" {
  name               = "${var.name_prefix}-sfn"
  assume_role_policy = data.aws_iam_policy_document.sfn_trust.json
}

resource "aws_iam_role_policy" "sfn" {
  name   = "pipeline"
  role   = aws_iam_role.sfn.id
  policy = data.aws_iam_policy_document.sfn.json
}

# Deployment execution role: push images, roll services, start and inspect the
# pipeline, publish jars. It holds no data-plane read or write access and cannot
# run Terraform apply. That does NOT separate deployment from apply in practice:
# scripts/deploy.sh runs `terraform apply` and creates IAM, network and database
# resources, which this role cannot do, so deploy.sh runs under the human's broad
# credentials, not under this role. What this role is for: the narrower image
# push, service roll, pipeline start and inspection and jar publish calls, when
# a script or person needs only those. See infra/terraform/README.md, "Who runs
# what".
#
# MFA (finding L3): the trust policy can require MFA through deployer_require_mfa,
# default false. It is off by default because the account's IAM users are
# programmatic-only (decision D4): a user with no MFA device could not assume the
# role, and the first-run lockout costs more than the condition protects against
# for a one-account course project. Turn it on when the trusted principals carry
# an MFA device and call sts:AssumeRole with it. An external ID is not used: it
# defends against a confused deputy among third parties, and there is none here.
data "aws_iam_policy_document" "deployer_trust" {
  count = length(var.deployer_trusted_principal_arns) > 0 ? 1 : 0

  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "AWS"
      identifiers = var.deployer_trusted_principal_arns
    }

    dynamic "condition" {
      for_each = var.deployer_require_mfa ? [1] : []

      content {
        test     = "Bool"
        variable = "aws:MultiFactorAuthPresent"
        values   = ["true"]
      }
    }
  }
}

data "aws_iam_policy_document" "deployer" {
  statement {
    sid       = "EcrToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
    ]
    resources = [local.ecr_repository_arn]
  }

  # Task definition registration does not support resource-level permissions.
  statement {
    sid       = "EcsRegisterTaskDefinition"
    actions   = ["ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition"]
    resources = ["*"]
  }

  statement {
    sid       = "EcsRollServices"
    actions   = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = [local.ecs_service_arn]
  }

  statement {
    sid       = "PassTaskRoles"
    actions   = ["iam:PassRole"]
    resources = concat([aws_iam_role.ecs_execution.arn, aws_iam_role.runtime.arn, aws_iam_role.loader.arn], aws_iam_role.worker[*].arn)

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  statement {
    sid       = "RunPipeline"
    actions   = ["states:StartExecution", "states:DescribeStateMachine", "states:ListExecutions"]
    resources = [local.state_machine_arn]
  }

  statement {
    sid       = "InspectPipelineExecutions"
    actions   = ["states:DescribeExecution", "states:StopExecution", "states:GetExecutionHistory"]
    resources = [local.execution_arn]
  }

  statement {
    sid       = "PublishArtifacts"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${var.artifacts_bucket_arn}/jars/*", "${var.artifacts_bucket_arn}/bootstrap/*"]
  }

  statement {
    sid       = "ListArtifacts"
    actions   = ["s3:ListBucket"]
    resources = [var.artifacts_bucket_arn]
  }

  statement {
    sid       = "ReadDeploymentLogs"
    actions   = ["logs:GetLogEvents", "logs:FilterLogEvents", "logs:DescribeLogStreams"]
    resources = [local.log_group_arn]
  }
}

resource "aws_iam_role" "deployer" {
  count = length(var.deployer_trusted_principal_arns) > 0 ? 1 : 0

  name                 = "${var.name_prefix}-deployer"
  assume_role_policy   = data.aws_iam_policy_document.deployer_trust[0].json
  max_session_duration = 3600
}

resource "aws_iam_role_policy" "deployer" {
  count = length(var.deployer_trusted_principal_arns) > 0 ? 1 : 0

  name   = "deploy"
  role   = aws_iam_role.deployer[0].id
  policy = data.aws_iam_policy_document.deployer.json
}
