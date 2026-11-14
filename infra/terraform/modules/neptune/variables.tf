variable "name_prefix" {
  description = "Prefix for resource names."
  type        = string
}

variable "data_subnet_ids" {
  description = "Private data subnets (two AZs)."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups attached to the cluster (the neptune role group)."
  type        = list(string)
}

variable "data_bucket_arn" {
  description = "Data bucket ARN. The bulk load role reads only the graph/ prefix."
  type        = string
}

variable "engine_version" {
  description = "Neptune engine version. It must support Serverless (1.2.0.1 or later). 1.4.5.1 is confirmed orderable for db.t4g.medium in us-east-2 by a human read-only CLI check on 2026-11-13 (docs/agent-runs/phase-14/checks-3.md)."
  type        = string
  default     = "1.4.5.1"
}

variable "parameter_group_family" {
  description = "Parameter group family matching engine_version."
  type        = string
  default     = "neptune1.4"
}

variable "mode" {
  description = "provisioned (default, one instance, no replica) or serverless."
  type        = string
  default     = "provisioned"

  validation {
    condition     = contains(["provisioned", "serverless"], var.mode)
    error_message = "mode must be provisioned or serverless."
  }
}

variable "instance_class" {
  description = "Instance class in provisioned mode. Ignored in serverless mode. db.t4g.medium is confirmed orderable in us-east-2 (engine versions 1.1.1.0 to 1.5.0.1, including 1.4.5.1) by a human read-only CLI check on 2026-11-13 (docs/agent-runs/phase-14/checks-3.md). That check listed versions, not Availability Zones: placement in us-east-2a and us-east-2b is settled by the first plan and apply."
  type        = string
  default     = "db.t4g.medium"
}

variable "min_ncu" {
  description = "Serverless minimum capacity (serverless mode only). 1.0 is the lowest Neptune allows and it does not scale to zero, so the cluster bills while it exists."
  type        = number
  default     = 1
}

variable "max_ncu" {
  description = "Serverless maximum capacity (serverless mode only). This caps spend."
  type        = number
  default     = 2

  validation {
    condition     = var.max_ncu >= 1 && var.max_ncu <= 4
    error_message = "max_ncu must be 1 to 4."
  }
}

variable "backup_retention_days" {
  description = "Automated backup retention (1 is the minimum)."
  type        = number
  default     = 1
}

variable "deletion_protection" {
  description = "Deletion protection. Off for the ephemeral course environment."
  type        = bool
  default     = false
}

variable "skip_final_snapshot" {
  description = "Skip the final snapshot on destroy. The course environment passes true whenever it is not persistent_environment (not only in cleanup mode), so a session teardown of Neptune keeps no snapshot: reload the graph from the data bucket's graph/ prefix next session."
  type        = bool
  default     = true
}

variable "query_timeout_ms" {
  description = "neptune_query_timeout, bounding every query."
  type        = number
  default     = 60000
}
