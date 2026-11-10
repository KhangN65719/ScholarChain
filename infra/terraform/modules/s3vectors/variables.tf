variable "bucket_name" {
  description = "Vector bucket name (3 to 63 lowercase characters)."
  type        = string
}

variable "force_destroy" {
  description = "Delete the bucket with its indexes and vectors. True only in cleanup mode."
  type        = bool
  default     = false
}

variable "dimension" {
  description = "Embedding dimension. Titan Text Embeddings V2 supports 256, 512 and 1024 (decision D2a leaves the choice open). The hash embedder (hash-embed-v1) is 384 and fits none of those, so the fallback path needs 384 in its own deployment. WARNING: changing the dimension of existing indexes replaces all five in place and destroys their vectors, because the dimension is not in the index names. A change is refused at plan time unless acknowledge_vector_loss is true."
  type        = number
  default     = 256

  validation {
    condition     = contains([256, 384, 512, 1024], var.dimension)
    error_message = "dimension must be 256, 384, 512 or 1024."
  }
}

variable "acknowledge_vector_loss" {
  description = "Allow, for one apply, a dimension change that replaces the indexes and destroys their vectors. False by default. KNOWN GAP: the guard does not refuse every dangerous sequence. Flipping this from true to false in the same apply as another dimension change can replace the indexes without a plan-time refusal (the record's output is unknown at plan time). Flip it back in an apply of its own with the dimension unchanged. The second layer is the plan review (plan.sh flags aws_s3vectors_index replacements; the approval record needs STATEFUL_CHANGE_ACKNOWLEDGED=yes). UNVERIFIED until a plan."
  type        = bool
  default     = false
}

variable "distance_metric" {
  description = "cosine or euclidean (Limits.S3Vectors.DistanceMetrics)."
  type        = string
  default     = "cosine"

  validation {
    condition     = contains(["cosine", "euclidean"], var.distance_metric)
    error_message = "distance_metric must be cosine or euclidean."
  }
}

variable "non_filterable_metadata_keys" {
  description = "Metadata keys excluded from filtering. Fixed at index creation. The filterable total is capped at 2048 bytes; textRef and textHash are references the filters never use."
  type        = list(string)
  default     = ["textRef", "textHash"]

  validation {
    condition     = length(var.non_filterable_metadata_keys) <= 10
    error_message = "At most 10 non-filterable keys per index."
  }
}
