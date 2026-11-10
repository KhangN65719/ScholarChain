terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

data "aws_caller_identity" "current" {}

# Bulk load role. Neptune assumes it to read graph files from S3. It is created
# here, not in the identity module, because the cluster needs its ARN and the
# identity module needs the cluster's resource ID.
data "aws_iam_policy_document" "load_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["rds.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

data "aws_iam_policy_document" "load_access" {
  statement {
    sid       = "ListGraphPrefix"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [var.data_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["graph/*"]
    }
  }

  statement {
    sid       = "ReadGraphObjects"
    actions   = ["s3:GetObject"]
    resources = ["${var.data_bucket_arn}/graph/*"]
  }
}

resource "aws_iam_role" "bulk_load" {
  name               = "${var.name_prefix}-neptune-bulk-load"
  assume_role_policy = data.aws_iam_policy_document.load_trust.json
}

resource "aws_iam_role_policy" "bulk_load" {
  name   = "read-graph-prefix"
  role   = aws_iam_role.bulk_load.id
  policy = data.aws_iam_policy_document.load_access.json
}

resource "aws_neptune_subnet_group" "this" {
  name       = "${var.name_prefix}-neptune"
  subnet_ids = var.data_subnet_ids
}

resource "aws_neptune_cluster_parameter_group" "this" {
  name   = "${var.name_prefix}-neptune-cluster"
  family = var.parameter_group_family

  parameter {
    name  = "neptune_enable_audit_log"
    value = "0"
  }
}

resource "aws_neptune_parameter_group" "this" {
  name   = "${var.name_prefix}-neptune-instance"
  family = var.parameter_group_family

  parameter {
    name  = "neptune_query_timeout"
    value = tostring(var.query_timeout_ms)
  }
}

# Two modes (decision D2c). provisioned (default) is one db.t4g.medium writer and
# no replica. serverless is one db.serverless writer with a 1 NCU minimum and a
# capped maximum. Both use the default AWS-managed storage key (aws/rds, free).
# IAM database authentication is on, so a client needs neptune-db:* permission,
# not just network reach.
locals {
  serverless = var.mode == "serverless"
}

resource "aws_neptune_cluster" "this" {
  cluster_identifier                   = "${var.name_prefix}-graph"
  engine                               = "neptune"
  engine_version                       = var.engine_version
  neptune_subnet_group_name            = aws_neptune_subnet_group.this.name
  neptune_cluster_parameter_group_name = aws_neptune_cluster_parameter_group.this.name
  vpc_security_group_ids               = var.security_group_ids
  iam_database_authentication_enabled  = true
  iam_roles                            = [aws_iam_role.bulk_load.arn]
  storage_encrypted                    = true
  backup_retention_period              = var.backup_retention_days
  deletion_protection                  = var.deletion_protection
  skip_final_snapshot                  = var.skip_final_snapshot
  final_snapshot_identifier            = var.skip_final_snapshot ? null : "${var.name_prefix}-graph-final"
  copy_tags_to_snapshot                = true
  apply_immediately                    = true

  dynamic "serverless_v2_scaling_configuration" {
    for_each = local.serverless ? [1] : []

    content {
      min_capacity = var.min_ncu
      max_capacity = var.max_ncu
    }
  }
}

resource "aws_neptune_cluster_instance" "writer" {
  identifier                   = "${var.name_prefix}-graph-writer"
  cluster_identifier           = aws_neptune_cluster.this.id
  engine                       = "neptune"
  engine_version               = var.engine_version
  instance_class               = local.serverless ? "db.serverless" : var.instance_class
  neptune_parameter_group_name = aws_neptune_parameter_group.this.name
  neptune_subnet_group_name    = aws_neptune_subnet_group.this.name
  publicly_accessible          = false
  apply_immediately            = true
}
