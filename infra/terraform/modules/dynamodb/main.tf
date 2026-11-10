terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# Table and index design is copied from modules/storage/.../DynamoDb.scala
# (RunLedger, EntityLinkCache, QuerySession, EvidenceBundleTable,
# ArtifactRegistry, ResearcherMetricCache). Every key attribute is a string.
# `ttl` is enabled exactly on the tables whose TtlPolicy is Required. The three
# indexes are all (gsi1pk, gsi1sk) with ALL projection.
locals {
  table_ttl = tomap({
    "run-ledger"              = false
    "entity-link-cache"       = true
    "query-session"           = true
    "evidence-bundle"         = false
    "artifact-registry"       = false
    "researcher-metric-cache" = true
  })

  table_index = tomap({
    "run-ledger"        = "status-index"
    "evidence-bundle"   = "query-index"
    "artifact-registry" = "run-index"
  })
}

# On-demand billing: no idle cost and no capacity planning for bursty bounded
# runs. Encryption uses the default AWS-owned key at no charge.
resource "aws_dynamodb_table" "this" {
  for_each = local.table_ttl

  name                        = "${var.table_prefix}-${each.key}"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "pk"
  range_key                   = "sk"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  dynamic "attribute" {
    for_each = contains(keys(local.table_index), each.key) ? ["gsi1pk", "gsi1sk"] : []

    content {
      name = attribute.value
      type = "S"
    }
  }

  dynamic "global_secondary_index" {
    for_each = contains(keys(local.table_index), each.key) ? [local.table_index[each.key]] : []

    content {
      name            = global_secondary_index.value
      hash_key        = "gsi1pk"
      range_key       = "gsi1sk"
      projection_type = "ALL"
    }
  }

  dynamic "ttl" {
    for_each = each.value ? [1] : []

    content {
      attribute_name = "ttl"
      enabled        = true
    }
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }
}
