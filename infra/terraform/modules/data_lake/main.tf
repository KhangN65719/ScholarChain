terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# Two buckets instead of seven. Prefixes follow S3Prefix in modules/storage:
# raw, curated, graph, semantic-context, reports, logs. Lifecycle rules work per
# prefix, so a separate bucket only adds cost-free resources and policies.
# Versioning is bucket-wide, which is why artifacts (jars, scripts, EMR logs)
# are kept apart from the data prefixes.
locals {
  buckets = tomap({
    data      = "${var.name_prefix}-${var.account_id}-data"
    artifacts = "${var.name_prefix}-${var.account_id}-artifacts"
  })
}

resource "aws_s3_bucket" "this" {
  for_each = local.buckets

  bucket        = each.value
  force_destroy = var.force_destroy
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each = local.buckets

  bucket                  = aws_s3_bucket.this[each.key].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# SSE-S3 (AWS-owned key): free. A customer KMS key costs about $1 a month each
# plus request charges, and the course does not require one.
resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    bucket_key_enabled = true

    # AWS adds this block by default on new buckets; stating it keeps the
    # state and the plan from drifting to [] (decision D14).
    blocked_encryption_types = ["SSE-C"]

    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "this" {
  for_each = var.versioning_enabled ? local.buckets : {}

  bucket = aws_s3_bucket.this[each.key].id

  versioning_configuration {
    status = "Enabled"
  }
}

data "aws_iam_policy_document" "tls_only" {
  for_each = local.buckets

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.this[each.key].arn, "${aws_s3_bucket.this[each.key].arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "tls_only" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id
  policy = data.aws_iam_policy_document.tls_only[each.key].json

  depends_on = [aws_s3_bucket_public_access_block.this]
}

resource "aws_s3_bucket_lifecycle_configuration" "data" {
  bucket = aws_s3_bucket.this["data"].id

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "expire-scratch"
    status = "Enabled"

    filter {
      prefix = "tmp/"
    }

    expiration {
      days = var.scratch_expiry_days
    }
  }

  rule {
    id     = "expire-run-logs"
    status = "Enabled"

    filter {
      prefix = "logs/"
    }

    expiration {
      days = var.ops_log_expiry_days
    }
  }

  dynamic "rule" {
    for_each = var.versioning_enabled ? [1] : []

    content {
      id     = "expire-noncurrent"
      status = "Enabled"

      filter {}

      noncurrent_version_expiration {
        noncurrent_days = var.noncurrent_expiry_days
      }
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "artifacts" {
  bucket = aws_s3_bucket.this["artifacts"].id

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "expire-emr-logs"
    status = "Enabled"

    filter {
      prefix = "emr-logs/"
    }

    expiration {
      days = var.ops_log_expiry_days
    }
  }

  rule {
    id     = "expire-scratch"
    status = "Enabled"

    filter {
      prefix = "tmp/"
    }

    expiration {
      days = var.scratch_expiry_days
    }
  }

  dynamic "rule" {
    for_each = var.versioning_enabled ? [1] : []

    content {
      id     = "expire-noncurrent"
      status = "Enabled"

      filter {}

      noncurrent_version_expiration {
        noncurrent_days = var.noncurrent_expiry_days
      }
    }
  }
}
