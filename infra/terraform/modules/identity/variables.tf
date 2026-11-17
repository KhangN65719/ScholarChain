variable "name_prefix" {
  description = "Prefix for names. Resource ARN patterns in the policies are built from it."
  type        = string
}

variable "region" {
  description = "AWS region."
  type        = string
}

variable "account_id" {
  description = "AWS account ID."
  type        = string
}

variable "data_bucket_arn" {
  description = "Data bucket ARN."
  type        = string
}

variable "artifacts_bucket_arn" {
  description = "Artifacts bucket ARN."
  type        = string
}

variable "table_arns" {
  description = "DynamoDB table ARNs keyed by suffix (run-ledger and the other five)."
  type        = map(string)
}

variable "vector_bucket_arn" {
  description = "S3 Vectors bucket ARN, as reported by the provider."
  type        = string
}

variable "vector_index_arns" {
  description = "S3 Vectors index ARNs, as reported by the provider."
  type        = list(string)
}

variable "neptune_enabled" {
  description = "Whether Neptune exists. A flag, because the resource ID is unknown at plan time."
  type        = bool
  default     = false
}

variable "neptune_cluster_resource_id" {
  description = "Neptune cluster resource ID, or null when Neptune is not deployed."
  type        = string
  default     = null
}

variable "enable_bedrock" {
  description = "Grant Bedrock InvokeModel on the listed ARNs only. Off by default: the hash embedder and offline stub are the fallback (decision D2a)."
  type        = bool
  default     = false
}

variable "bedrock_model_arns" {
  description = "Exact inference profile and foundation model ARNs the runtime may invoke. Supplied by the environment. Never use a wildcard: a * or an empty list (with Bedrock on) is rejected."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for a in var.bedrock_model_arns : startswith(a, "arn:aws:bedrock:") && !strcontains(a, "*")])
    error_message = "Every Bedrock model ARN must start with arn:aws:bedrock: and contain no * wildcard."
  }

  validation {
    condition     = !var.enable_bedrock || length(var.bedrock_model_arns) > 0
    error_message = "enable_bedrock needs at least one exact model ARN in bedrock_model_arns."
  }
}

variable "bedrock_embedding_model_arns" {
  description = "Exact Titan Text Embeddings V2 ARNs the loader and runtime may invoke. A * or an empty list (with Bedrock on) is rejected."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for a in var.bedrock_embedding_model_arns : startswith(a, "arn:aws:bedrock:") && !strcontains(a, "*")])
    error_message = "Every Bedrock embedding model ARN must start with arn:aws:bedrock: and contain no * wildcard."
  }

  validation {
    condition     = !var.enable_bedrock || length(var.bedrock_embedding_model_arns) > 0
    error_message = "enable_bedrock needs at least one exact model ARN in bedrock_embedding_model_arns."
  }
}

variable "enable_worker_role" {
  description = "Create the embedding worker task role scholarchain-<env>-worker and its inline policy worker-embed. Off by default: the Experiment A and B applies must show IAM_CHANGES none (OD-1), so the new principal is created by session 2 (the services stack). The deployer's iam:PassRole gains the role ARN only while it exists."
  type        = bool
  default     = false
}

variable "deployer_require_mfa" {
  description = "Require aws:MultiFactorAuthPresent in the deployer role's trust policy. Off by default: the account's IAM users are programmatic-only and have no MFA device, so turning it on without one locks the role."
  type        = bool
  default     = false
}

variable "secret_recovery_days" {
  description = "Recovery window for deleted secrets. 0 deletes immediately and is for cleanup mode only."
  type        = number
  default     = 7
}

variable "deployer_trusted_principal_arns" {
  description = "IAM principals allowed to assume the deployment execution role. Empty creates no role. These are human-held identities supplied outside source."
  type        = list(string)
  default     = []
}
