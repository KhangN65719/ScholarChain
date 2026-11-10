terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# Index names come from VectorIndexes in modules/storage/.../Vectors.scala.
# They are versioned: a new embedding model or dimension means a new name, never
# an in-place change, so changing `dimension` here replaces every index and
# destroys its vectors. The distance metric and dimension are variables because
# the application passes them to VectorIndexes.all(dimension, metric).
locals {
  index_names = toset([
    "researcher-profile-v1",
    "collaboration-community-v1",
    "paper-abstract-v1",
    "collaboration-path-v1",
    "topic-institution-summary-v1",
  ])
}

# SSE-S3 (free). No access policy resource is created: access is by IAM identity
# policies only, and no resource policy is needed with one account.
resource "aws_s3vectors_vector_bucket" "this" {
  vector_bucket_name = var.bucket_name
  force_destroy      = var.force_destroy

  encryption_configuration {
    sse_type = "AES256"
  }
}

# Guard for the dimension-change hazard. A change to `dimension` replaces every
# index in place and destroys its vectors, and neither lifecycle.prevent_destroy
# (cannot take a variable) nor the index names protect against it. This record
# keeps the dimension the indexes were first created with: ignore_changes stops
# the input from following the variable, so the index precondition below sees the
# old value and refuses a change at plan time unless acknowledge_vector_loss is
# true. Setting it true also replaces this record (triggers_replace) so the new
# dimension becomes the recorded one; setting it back to false replaces the record
# once more.
#
# KNOWN GAP (review 2, finding L-a): the flip back to false is NOT always harmless.
# It is harmless when the dimension is unchanged. If acknowledge_vector_loss goes
# from true to false in the same apply as another change of `dimension`, the record
# is replaced, its output is unknown at plan time, the index precondition cannot be
# evaluated until apply, the record is recreated holding the new dimension, and the
# indexes are then replaced without the plan having refused. A precondition cannot
# close this: it sees only the current variable values, not the previous
# acknowledge value. This is a reading of the code, UNVERIFIED until a Phase 14
# plan. Operating rule: flip acknowledge_vector_loss back to false in an apply of
# its own, with the dimension unchanged. The guard is therefore a first layer only.
# The second layer that still catches the gap is the plan review: plan.sh lists an
# aws_s3vectors_index replacement under STATEFUL_DESTROY_OR_REPLACE and deploy.sh
# then requires STATEFUL_CHANGE_ACKNOWLEDGED=yes in the approval record. The
# behavior of ignore_changes on terraform_data and of a precondition on an unknown
# value is also UNVERIFIED until a Phase 14 plan shows it.
resource "terraform_data" "dimension_record" {
  input            = var.dimension
  triggers_replace = var.acknowledge_vector_loss ? "ack-${var.dimension}" : "keep"

  lifecycle {
    ignore_changes = [input]
  }
}

resource "aws_s3vectors_index" "this" {
  for_each = local.index_names

  vector_bucket_name = aws_s3vectors_vector_bucket.this.vector_bucket_name
  index_name         = each.value
  data_type          = "float32"
  dimension          = var.dimension
  distance_metric    = var.distance_metric

  metadata_configuration {
    non_filterable_metadata_keys = var.non_filterable_metadata_keys
  }

  lifecycle {
    precondition {
      condition     = var.acknowledge_vector_loss || terraform_data.dimension_record.output == var.dimension
      error_message = "vector_dimension changed from the recorded value. This replaces all five S3 Vectors indexes and destroys their vectors. Set acknowledge_vector_loss = true for one apply to allow it, or keep the recorded dimension."
    }
  }
}
